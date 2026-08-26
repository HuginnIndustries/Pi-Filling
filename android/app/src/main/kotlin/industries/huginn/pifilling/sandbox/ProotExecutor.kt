package industries.huginn.pifilling.sandbox

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs commands inside the Alpine rootfs via proot. Ported from Kai's
 * ProotExecutor: the proot ELF is shipped as `libproot.so` under jniLibs and
 * executed from the native library dir (files there are exempt from Android's
 * W^X restriction on app data), with its loader and talloc beside it.
 *
 * Two entry points:
 *  - [execute] runs a single command to completion and returns a [CommandResult].
 *  - [start] launches a long-lived process (e.g. `node wrapper.mjs`) and returns
 *    the raw [Process] for the caller to drive over stdio (see WrapperClient).
 */
class ProotExecutor(
    private val prootPath: String,
    /** `LD_LIBRARY_PATH` for proot: one or more dirs, colon-separated. */
    private val libDir: String,
    private val rootfsPath: String,
    private val homePath: String,
    private val tmpPath: String,
) {
    /**
     * Build the proot argv. [command] is run via `/bin/sh -c`. `-0` makes the
     * guest believe it is root (proot's uid/gid emulation), which Alpine's apk
     * and most tooling expect.
     */
    private fun buildArgs(command: String, workingDir: String): List<String> = listOf(
        prootPath,
        "--rootfs=$rootfsPath",
        "--bind=/dev",
        "--bind=/proc",
        "--bind=/sys",
        "--bind=$homePath:/root",
        "--bind=$tmpPath:/tmp",
        "-0",
        "-w", workingDir,
        "/bin/sh", "-c", command,
    )

    private fun buildEnv(extraEnv: Map<String, String>): Map<String, String> {
        val loaderPath = File(prootPath).parent?.let { "$it/libproot-loader.so" }.orEmpty()
        val base = linkedMapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LD_LIBRARY_PATH" to libDir,
            "PROOT_TMP_DIR" to tmpPath,
            "PROOT_LOADER" to loaderPath,
        )
        base.putAll(extraEnv)
        return base
    }

    /**
     * Start a long-lived process inside the sandbox. The returned [Process] has
     * stdin/stdout/stderr pipes wired (stderr NOT merged into stdout, because
     * the wrapper's stdout is structured JSONL while stderr is free-form logs).
     */
    fun start(
        command: String,
        workingDir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
    ): Process {
        val pb = ProcessBuilder(buildArgs(command, workingDir))
        pb.directory(File(rootfsPath).parentFile)
        pb.environment().clear()
        pb.environment().putAll(buildEnv(extraEnv))
        pb.redirectErrorStream(false)
        return pb.start()
    }

    /**
     * Run [command] to completion. Output is bounded at [MAX_OUTPUT_CHARS] each
     * for stdout/stderr (matching Kai), and the call is force-killed after
     * [timeoutSeconds] (clamped to 1..180).
     */
    suspend fun execute(
        command: String,
        workingDir: String = "/root",
        timeoutSeconds: Long = 30,
        extraEnv: Map<String, String> = emptyMap(),
    ): CommandResult = withContext(Dispatchers.IO) {
        val clamped = timeoutSeconds.coerceIn(1, 180)
        val process = start(command, workingDir, extraEnv)

        // Drain both streams concurrently to avoid pipe-buffer deadlock.
        val stdoutThread = drainAsync(process.inputStream.bufferedReader())
        val stderrThread = drainAsync(process.errorStream.bufferedReader())

        val finished = runInterruptible {
            process.waitFor(clamped, TimeUnit.SECONDS)
        }

        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(DRAIN_JOIN_MS)
            stderrThread.join(DRAIN_JOIN_MS)
            return@withContext CommandResult(
                success = false,
                stdout = stdoutThread.result(),
                stderr = stderrThread.result(),
                exitCode = -1,
                timedOut = true,
            )
        }

        stdoutThread.join(DRAIN_JOIN_MS)
        stderrThread.join(DRAIN_JOIN_MS)
        val code = process.exitValue()
        CommandResult(
            success = code == 0,
            stdout = stdoutThread.result(),
            stderr = stderrThread.result(),
            exitCode = code,
            timedOut = false,
        )
    }

    private fun drainAsync(reader: java.io.BufferedReader): DrainThread {
        val t = DrainThread(reader)
        // Daemon so a proot grandchild stuck holding the pipe open can never
        // wedge the JVM/worker thread past the join timeout.
        t.isDaemon = true
        t.start()
        return t
    }

    private class DrainThread(private val reader: java.io.BufferedReader) : Thread() {
        private val sb = StringBuilder()
        override fun run() {
            try {
                reader.useLines { lines ->
                    for (line in lines) {
                        if (sb.length < MAX_OUTPUT_CHARS) {
                            sb.append(line).append('\n')
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("ProotExecutor", "drain ended: ${e.message}")
            }
        }

        fun result(): String =
            if (sb.length > MAX_OUTPUT_CHARS) sb.substring(0, MAX_OUTPUT_CHARS) else sb.toString()
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 15_000
        const val DRAIN_JOIN_MS = 2_000L
    }
}
