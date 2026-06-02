package industries.huginn.pifilling.sandbox

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileNotFoundException

/**
 * Owns the Alpine sandbox lifecycle on Android. Provisions the rootfs + Node
 * toolchain, deploys the Layer 3 wrapper into it, and launches the wrapper
 * process for [industries.huginn.pifilling.wrapper.WrapperClient] to drive.
 *
 * Layout (all app-private, under filesDir):
 *   linux-sandbox/
 *     rootfs/            extracted Alpine
 *     tmp/               PROOT_TMP_DIR
 *     rootfs/root/       guest HOME, bound to /root
 *     rootfs/root/wrapper/   the node wrapper (deployed from assets)
 *     .setup-complete    marker written after a successful provision
 */
class LinuxSandboxManager(
    private val context: Context,
    private val downloader: RootfsDownloader = RootfsDownloader(),
) {
    private val sandboxDir = File(context.filesDir, "linux-sandbox")
    private val rootfsDir = File(sandboxDir, "rootfs")
    private val tmpDir = File(sandboxDir, "tmp")
    private val homeDir = File(rootfsDir, "root")
    private val markerFile = File(sandboxDir, ".setup-complete")

    private val nativeLibDir: String get() = context.applicationInfo.nativeLibraryDir
    private val prootPath: String get() = File(nativeLibDir, "libproot.so").absolutePath

    private val deviceAbi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    private val _state = MutableStateFlow<SandboxState>(
        if (markerFile.exists()) SandboxState.Ready else SandboxState.NotInstalled,
    )
    val state: StateFlow<SandboxState> = _state.asStateFlow()

    val isReady: Boolean get() = _state.value is SandboxState.Ready

    fun createExecutor(): ProotExecutor = ProotExecutor(
        prootPath = prootPath,
        libDir = nativeLibDir,
        rootfsPath = rootfsDir.absolutePath,
        homePath = homeDir.absolutePath,
        tmpPath = tmpDir.absolutePath,
    )

    /**
     * Provision the sandbox end-to-end. Idempotent: if the marker exists it
     * short-circuits to [SandboxState.Ready]. Safe to cancel — a cancelled
     * setup leaves no marker, so the next call re-provisions cleanly.
     */
    suspend fun setup() {
        if (markerFile.exists()) {
            _state.value = SandboxState.Ready
            return
        }
        try {
            check(File(prootPath).exists()) {
                "libproot.so missing from $nativeLibDir — run proot-bootstrap/build-proot.sh " +
                    "and bundle the .so files into jniLibs before building the app."
            }

            sandboxDir.mkdirs()
            tmpDir.mkdirs()
            homeDir.mkdirs()

            val archive = File(sandboxDir, "rootfs.tar.gz")
            _state.value = SandboxState.Downloading(0f)
            downloader.download(deviceAbi, archive) { fraction ->
                _state.value = SandboxState.Downloading(fraction.takeIf { it >= 0f })
            }

            _state.value = SandboxState.Extracting
            downloader.extract(archive, rootfsDir)
            archive.delete()
            downloader.writeResolvConf(rootfsDir)
            downloader.writeRepositories(rootfsDir)

            val executor = createExecutor()

            _state.value = SandboxState.Installing("apk update")
            val update = executor.execute("apk update", timeoutSeconds = 60)
            check(update.success) { "apk update failed: ${update.stderr.take(500)}" }

            _state.value = SandboxState.Installing("nodejs npm git")
            val add = executor.execute(
                "apk add --no-cache nodejs npm git",
                timeoutSeconds = 180,
            )
            check(add.success) { "apk add failed: ${add.stderr.take(500)}" }

            _state.value = SandboxState.Installing("wrapper")
            deployWrapper(executor)

            markerFile.writeText(
                "alpine=${RootfsDownloader.ALPINE_VERSION} abi=$deviceAbi\n",
            )
            _state.value = SandboxState.Ready
            Log.i(TAG, "sandbox ready ($deviceAbi, Alpine ${RootfsDownloader.ALPINE_VERSION})")
        } catch (e: Exception) {
            Log.e(TAG, "sandbox setup failed", e)
            _state.value = SandboxState.Error(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * Copy the bundled wrapper (app assets `wrapper/`) into the rootfs and run a
     * production install. The app build copies `node-wrapper/{src,package.json,
     * package-lock.json}` into `assets/wrapper/` (see android/README.md).
     */
    private suspend fun deployWrapper(executor: ProotExecutor) {
        val wrapperDir = File(homeDir, "wrapper")
        wrapperDir.mkdirs()
        copyAsset("wrapper", wrapperDir)

        val install = executor.execute(
            "cd /root/wrapper && npm ci --omit=dev",
            timeoutSeconds = 180,
        )
        check(install.success) { "npm ci failed: ${install.stderr.take(500)}" }
    }

    private fun copyAsset(assetPath: String, dest: File) {
        val assets = context.assets
        val children = assets.list(assetPath)
        // AssetManager.list() returns [] for an empty directory AND can return []
        // for some files, so we can't infer file-vs-dir from emptiness. Try to
        // open as a file; if that throws, it's a directory (possibly empty).
        if (children.isNullOrEmpty()) {
            try {
                assets.open(assetPath).use { input ->
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: FileNotFoundException) {
                dest.mkdirs()
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAsset("$assetPath/$child", File(dest, child))
        }
    }

    /**
     * Launch the wrapper process inside the sandbox, returning the raw [Process].
     * The API key is supplied via env at spawn time (per ARCHITECTURE.md the key
     * never lands on disk); [repoGuestPath] is a path inside the rootfs, e.g.
     * `/root/repo`.
     */
    fun startWrapper(
        apiKey: String,
        repoGuestPath: String,
        model: String? = null,
        logLevel: String = "info",
    ): Process {
        check(isReady) { "sandbox not ready" }
        val modelArg = model?.let { " --model $it" } ?: ""
        val command = "node /root/wrapper/src/wrapper.mjs --repo $repoGuestPath$modelArg"
        return createExecutor().start(
            command = command,
            workingDir = repoGuestPath,
            extraEnv = mapOf(
                "ANTHROPIC_API_KEY" to apiKey,
                "WRAPPER_LOG_LEVEL" to logLevel,
            ),
        )
    }

    /** Delete the entire sandbox and return to [SandboxState.NotInstalled]. */
    fun reset() {
        sandboxDir.deleteRecursively()
        _state.value = SandboxState.NotInstalled
    }

    private companion object {
        const val TAG = "LinuxSandboxManager"
    }
}
