package industries.huginn.pifilling.sandbox

import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * Downloads and extracts the Alpine minirootfs. Ported from Kai's
 * RootfsDownloader: a pinned Alpine version, a list of mirrors tried in order,
 * and a hand-rolled tar extractor with a zip-slip guard (an APK can't depend on
 * a system `tar`, and we will not trust archive paths blindly).
 */
class RootfsDownloader {

    /** Maps an Android ABI to Alpine's architecture directory name. */
    fun alpineArch(androidAbi: String): String = when (androidAbi) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "armv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> throw IllegalArgumentException("unsupported ABI: $androidAbi")
    }

    private fun minirootfsUrl(mirror: String, arch: String): String =
        "$mirror/$ALPINE_BRANCH/releases/$arch/alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz"

    /**
     * Download the minirootfs tarball for [androidAbi] into [dest], trying each
     * mirror until one succeeds. [onProgress] reports 0f..1f (or is passed -1f
     * when the server didn't send a content length).
     */
    suspend fun download(
        androidAbi: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val arch = alpineArch(androidAbi)
        var lastError: Exception? = null

        for (mirror in MIRRORS) {
            coroutineContext.ensureActive()
            val url = minirootfsUrl(mirror, arch)
            try {
                fetch(url, dest, onProgress)
                Log.i(TAG, "downloaded rootfs from $mirror")
                return@withContext
            } catch (e: Exception) {
                Log.w(TAG, "mirror failed ($mirror): ${e.message}")
                lastError = e
                dest.delete()
            }
        }
        throw IOException("all ${MIRRORS.size} Alpine mirrors failed", lastError)
    }

    private suspend fun fetch(urlStr: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} for $urlStr")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var sum = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        output.write(buf, 0, read)
                        sum += read
                        onProgress(if (total > 0) (sum.toFloat() / total) else -1f)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Extract a gzipped tar [archive] into [rootfs]. Handles regular files,
     * directories, symlinks, and GNU long names. Both entry paths AND relative
     * symlink targets are resolved and checked to stay within [rootfs] (zip-slip
     * / path-traversal guard). Absolute symlink targets are kept verbatim — proot
     * interprets them against the guest root at runtime.
     */
    suspend fun extract(archive: File, rootfs: File): Unit = withContext(Dispatchers.IO) {
        rootfs.mkdirs()
        val rootCanonical = rootfs.canonicalFile

        GZIPInputStream(BufferedInputStream(archive.inputStream())).use { gz ->
            val header = ByteArray(BLOCK)
            var pendingLongName: String? = null

            while (true) {
                coroutineContext.ensureActive()
                if (!readFully(gz, header)) break
                if (header.all { it.toInt() == 0 }) break // end-of-archive marker

                val name = pendingLongName ?: tarString(header, 0, 100).let { n ->
                    val prefix = tarString(header, 345, 155)
                    if (prefix.isNotEmpty()) "$prefix/$n" else n
                }
                pendingLongName = null

                val size = tarOctal(header, 124, 12)
                val mode = tarOctal(header, 100, 8)
                val type = header[156].toInt().toChar()
                val linkName = tarString(header, 157, 100)

                when (type) {
                    'L' -> { // GNU long name: the data block holds the next entry's name
                        pendingLongName = readBlockString(gz, size)
                        continue
                    }
                    'x', 'g' -> { // pax extended headers: skip the data, keep std fields
                        skip(gz, paddedSize(size))
                        continue
                    }
                }

                val target = File(rootfs, name)
                val targetCanonical = target.canonicalFile
                if (!targetCanonical.path.startsWith(rootCanonical.path)) {
                    throw IOException("blocked path traversal in rootfs tar: $name")
                }

                when (type) {
                    '5' -> target.mkdirs()
                    '2', '1' -> writeSymlink(linkName, target, rootCanonical) // symlink/hardlink
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        writeFile(gz, target, size)
                        // Apply the archived executable bit. Java creates files
                        // non-executable, so without this every binary in the
                        // rootfs lands as -rw------- and proot fails on the
                        // first command with "'/bin/sh' is not executable" —
                        // /bin/sh is a symlink to busybox, and busybox is the
                        // file that actually needs +x. Owner-only is enough:
                        // proot runs as this app's uid.
                        if (mode and TAR_EXEC_BITS != 0L) {
                            target.setExecutable(true, /* ownerOnly = */ true)
                        }
                    }
                    else -> skip(gz, paddedSize(size)) // unsupported (char/block/fifo) — skip
                }
            }
        }
    }

    private fun writeFile(input: java.io.InputStream, target: File, size: Long) {
        target.outputStream().use { out ->
            var remaining = size
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val n = input.read(buf, 0, toRead)
                if (n < 0) throw IOException("truncated tar entry: ${target.name}")
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        skip(input, padding(size))
    }

    private fun writeSymlink(linkName: String, target: File, rootCanonical: File) {
        if (linkName.isEmpty()) return
        // Reject a relative target that escapes the rootfs (zip-slip via symlink).
        // Absolute targets are kept verbatim (proot resolves them in-guest).
        if (!linkName.startsWith("/")) {
            val resolved = File(target.parentFile, linkName).canonicalFile
            if (!resolved.path.startsWith(rootCanonical.path)) {
                Log.w(TAG, "blocked symlink escaping rootfs: ${target.name} -> $linkName")
                return
            }
        }
        target.parentFile?.mkdirs()
        target.delete()
        try {
            Os.symlink(linkName, target.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "symlink failed ${target.name} -> $linkName: ${e.message}")
        }
    }

    /** Write Google DNS so name resolution works inside the sandbox. */
    fun writeResolvConf(rootfs: File) {
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
    }

    /** Point apk at the same Alpine branch we provisioned from. */
    fun writeRepositories(rootfs: File, mirror: String = MIRRORS.first()) {
        File(rootfs, "etc/apk").mkdirs()
        File(rootfs, "etc/apk/repositories").writeText(
            "$mirror/$ALPINE_BRANCH/main\n$mirror/$ALPINE_BRANCH/community\n",
        )
    }

    // ---- tar primitives ----

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off != 0 // false only if nothing at all was read
            off += n
        }
        return true
    }

    private fun readBlockString(input: java.io.InputStream, size: Long): String {
        val data = ByteArray(size.toInt())
        readFully(input, data)
        skip(input, padding(size))
        return String(data).trim('\u0000', '\n', ' ')
    }

    private fun skip(input: java.io.InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(8 * 1024)
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, toRead)
            if (read < 0) break
            remaining -= read
        }
    }

    private fun padding(size: Long): Long = (BLOCK - (size % BLOCK)) % BLOCK
    private fun paddedSize(size: Long): Long = size + padding(size)

    private fun tarString(header: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val limit = offset + len
        while (end < limit && header[end].toInt() != 0) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun tarOctal(header: ByteArray, offset: Int, len: Int): Long {
        // GNU base-256 encoding sets the high bit of the first byte. Alpine's
        // minirootfs uses plain octal; fail clearly rather than misparse if that
        // ever changes.
        if (header[offset].toInt() and 0x80 != 0) {
            throw IOException("unsupported GNU base-256 tar field at offset $offset")
        }
        val s = tarString(header, offset, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    companion object {
        const val ALPINE_VERSION = "3.21.3"
        const val ALPINE_BRANCH = "v3.21"
        private const val BLOCK = 512
        /** Owner/group/other execute bits in a tar header's mode field (0o111). */
        private const val TAR_EXEC_BITS = 0b001_001_001L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val TAG = "RootfsDownloader"

        /** Alpine mirrors tried in order (ported from Kai's RootfsDownloader). */
        val MIRRORS = listOf(
            "https://dl-cdn.alpinelinux.org/alpine",
            "https://mirrors.edge.kernel.org/alpine",
            "https://ftp.halifax.rwth-aachen.de/alpine",
            "https://alpine.ethz.ch/alpine",
            "https://mirror.csclub.uwaterloo.ca/alpine",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine",
        )
    }
}
