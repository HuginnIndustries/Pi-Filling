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

    private val nativeLibStageDir = File(sandboxDir, "nativelib")

    /**
     * Work around a name mismatch between talloc's SONAME and what Android is
     * able to package.
     *
     * `libproot.so` records its dependency under talloc's real SONAME,
     * `libtalloc.so.2`. An APK only extracts entries matching `lib*.so` into
     * the native library dir, so the packaged copy is necessarily named
     * `libtalloc.so` and the dynamic linker cannot satisfy the dependency:
     *
     *     CANNOT LINK EXECUTABLE ".../libproot.so":
     *     library "libtalloc.so.2" not found: needed by main executable
     *
     * Stage a copy under the name the linker actually looks for, in
     * app-private storage, and put that directory on proot's library search
     * path. The fix lives here rather than in `build-proot.sh` because that
     * script is vendored byte-identical from Kai (see
     * `proot-bootstrap/VENDORED.md`) and Android packaging is our constraint,
     * not upstream's.
     *
     * Verified on hardware; see `android/VERIFICATION.md`.
     */
    private fun stageNativeLibs(): File {
        nativeLibStageDir.mkdirs()
        val source = File(nativeLibDir, "libtalloc.so")
        val staged = File(nativeLibStageDir, "libtalloc.so.2")
        // Re-copy when absent or when an app update changed the library.
        if (source.exists() && (!staged.exists() || staged.length() != source.length())) {
            source.inputStream().use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return nativeLibStageDir
    }

    private val _state = MutableStateFlow<SandboxState>(
        if (markerFile.exists()) SandboxState.Ready else SandboxState.NotInstalled,
    )
    val state: StateFlow<SandboxState> = _state.asStateFlow()

    val isReady: Boolean get() = _state.value is SandboxState.Ready

    fun createExecutor(): ProotExecutor = ProotExecutor(
        prootPath = prootPath,
        libDir = "${stageNativeLibs().absolutePath}:$nativeLibDir",
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
            ensureCurrent()
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

            _state.value = SandboxState.Installing(APK_PACKAGES)
            val add = executor.execute(
                "apk add --no-cache $APK_PACKAGES",
                timeoutSeconds = 180,
            )
            check(add.success) { "apk add failed: ${add.stderr.take(500)}" }

            configureGitIdentity(executor)
        configureGitCredentialHelper(executor)

            _state.value = SandboxState.Installing("wrapper")
            deployWrapper(executor)

            writeMarker()
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
    /**
     * Bring an already-provisioned sandbox up to the current setup version.
     *
     * A sandbox provisioned by an older build keeps its marker, and
     * [SandboxState] starts `Ready` from that marker alone, so [setup] is never
     * called again — a new provisioning step would silently never reach existing
     * installs. Backfilling is far cheaper than forcing a ~350 MB re-provision
     * for what amounts to a couple of config writes.
     *
     * Cheap in the common case: a marker read and an integer compare, no proot
     * exec unless there is actually work to do.
     */
    suspend fun ensureCurrent() {
        if (!markerFile.exists()) return
        val have = readSetupVersion()
        val wrapperStale = readDeployedWrapperDigest() != bundledWrapperDigest()
        if (have >= SETUP_VERSION && !wrapperStale) return
        runCatching {
            val executor = createExecutor()
            if (have < 2) configureGitIdentity(executor)
            if (have < 3) installPackages(executor)
            if (have < 4) configureGitCredentialHelper(executor)
            // Not version-gated: the wrapper changes far more often than the
            // sandbox recipe does, and gating it on a hand-bumped number is a
            // standing invitation to ship a wrapper that never reaches a device.
            if (wrapperStale) {
                Log.i(TAG, "bundled wrapper differs from the deployed one; redeploying")
                deployWrapper(executor)
            }
        }
            .onSuccess {
                writeMarker()
                Log.i(TAG, "backfilled sandbox setup v$have -> v$SETUP_VERSION")
            }
            .onFailure { Log.w(TAG, "sandbox backfill failed; the agent may hit it", it) }
    }

    /**
     * Install (or top up) the guest toolchain.
     *
     * `bash` is not incidental. The agent is told it has a "bash tool" and the
     * tool is named `bash`, but Alpine ships only busybox `ash`, so models
     * reasonably emit bash-only syntax and get:
     *
     *     sh: bash: not found
     *     hello.sh: line 12: syntax error: bad substitution
     *
     * Observed on device across two different models. Cheaper to make the
     * environment match what the agent is told it has than to teach every model
     * that the "bash tool" is not bash.
     */
    private suspend fun installPackages(executor: ProotExecutor) {
        val result = executor.execute("apk add --no-cache $APK_PACKAGES", timeoutSeconds = 180)
        check(result.success) { "apk add failed: ${result.stderr.take(300)}" }
    }

    /**
     * Give the sandbox a git identity.
     *
     * Alpine ships none, so `git commit` fails with "Author identity unknown".
     * Observed consequence on hardware: the agent burned several turns on failed
     * commits and then **invented** an identity, writing
     * `Assistant Bot <assistant@example.com>` into the *user's repo* local
     * config. Committing is the product's whole point (V1_SPEC), so the
     * environment should supply this rather than leaving the model to improvise
     * a plausible-looking author.
     *
     * The value is deliberately obviously-an-agent and uses the reserved
     * `.local` TLD: it must not read as a real person. Once Stage 1.5 wires
     * GitHub auth, the operator's own identity should replace it, because
     * commits that get pushed should carry the operator's name, not this.
     */
    private suspend fun configureGitIdentity(executor: ProotExecutor) {
        val result = executor.execute(
            "git config --global user.name '$GIT_USER_NAME' && " +
                "git config --global user.email '$GIT_USER_EMAIL' && " +
                "git config --global --add safe.directory '*'",
            timeoutSeconds = 30,
        )
        check(result.success) { "git config failed: ${result.stderr.take(300)}" }
    }

    /**
     * Teach git in the guest how to authenticate to GitHub, without writing the
     * credential anywhere.
     *
     * The helper is a shell function that reads `$GITHUB_TOKEN` from the
     * environment **at use time**, so the configured value contains no secret —
     * only the name of a variable. `git-credential-store`, the obvious
     * alternative, would persist the token in plaintext at
     * `~/.git-credentials` inside a rootfs that outlives the session.
     *
     * Scoped to `https://github.com` rather than set globally, so the helper is
     * never offered to some other host the agent happens to talk to.
     *
     * The username is literal: GitHub accepts any username when the password is
     * a PAT, and `x-access-token` is its documented convention.
     *
     * This does not hide the token from the agent — the `bash` tool inherits the
     * environment, and the agent is what runs `git push`. Containment is the
     * token's own scope, which is why V1_SPEC specifies a *fine-grained* PAT.
     * See SECURITY.md.
     */
    private suspend fun configureGitCredentialHelper(executor: ProotExecutor) {
        // Single-quoted in the shell and escaped here, so $GITHUB_TOKEN reaches
        // git's config verbatim and expands only when the helper runs.
        val helper = "!f() { echo username=x-access-token; echo password=\$GITHUB_TOKEN; }; f"
        val result = executor.execute(
            "git config --global credential.https://github.com.helper '$helper'",
            timeoutSeconds = 30,
        )
        check(result.success) { "git credential helper config failed: ${result.stderr.take(300)}" }
    }

    /**
     * Content digest of the wrapper bundled in this APK.
     *
     * Deploying the wrapper was originally a provisioning step, which meant a
     * new wrapper only reached a device that had never provisioned. Every
     * wrapper change after the first install was silently invisible — the
     * symptom was an agent missing tools the build had definitely registered.
     * Comparing content is what makes that impossible to get wrong; a
     * hand-bumped version number would only work when someone remembered.
     */
    private fun bundledWrapperDigest(): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun walk(path: String) {
            val children = context.assets.list(path)
            if (children.isNullOrEmpty()) {
                runCatching {
                    context.assets.open(path).use { input ->
                        digest.update(path.toByteArray())
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            digest.update(buf, 0, n)
                        }
                    }
                }
            } else {
                children.sorted().forEach { walk("$path/$it") }
            }
        }
        walk("wrapper")
        digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }.getOrDefault("unknown")

    private fun readDeployedWrapperDigest(): String =
        runCatching {
            markerFile.readText()
                .substringAfter("wrapper=", "")
                .trim()
                .takeWhile { !it.isWhitespace() }
        }.getOrDefault("")

    private fun writeMarker() {
        markerFile.writeText(
            "alpine=${RootfsDownloader.ALPINE_VERSION} abi=$deviceAbi setup=$SETUP_VERSION " +
                "wrapper=${bundledWrapperDigest()}\n",
        )
    }

    private fun readSetupVersion(): Int =
        runCatching {
            markerFile.readText()
                .substringAfter("setup=", "0")
                .trim()
                .takeWhile(Char::isDigit)
                .toIntOrNull() ?: 0
        }.getOrDefault(0)

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
        provider: AgentProvider = AgentProvider.DEFAULT,
        model: String? = null,
        logLevel: String = "info",
        /**
         * Optional fine-grained GitHub PAT. Reaches git via the credential
         * helper configured at provisioning, which reads it from the
         * environment; it is never written to the guest filesystem. Omitting it
         * simply means `git push` fails to authenticate — everything else works.
         */
        gitHubToken: String? = null,
    ): Process {
        check(isReady) { "sandbox not ready" }
        val modelArg = model?.let { " --model $it" } ?: ""
        val command =
            "node /root/wrapper/src/wrapper.mjs --repo $repoGuestPath" +
                " --provider ${provider.id}$modelArg"
        return createExecutor().start(
            command = command,
            workingDir = repoGuestPath,
            extraEnv = buildMap {
                // Each provider reads only its own variable, so this must match
                // the wrapper's PROVIDERS table (see AgentProvider).
                put(provider.keyEnv, apiKey)
                put("WRAPPER_LOG_LEVEL", logLevel)
                // Unlike the provider key, this one must survive into the bash
                // tool's children: git is what consumes it.
                gitHubToken?.takeIf { it.isNotBlank() }?.let { put("GITHUB_TOKEN", it) }
            },
        )
    }

    /** Delete the entire sandbox and return to [SandboxState.NotInstalled]. */
    fun reset() {
        sandboxDir.deleteRecursively()
        _state.value = SandboxState.NotInstalled
    }

    private companion object {
        const val TAG = "LinuxSandboxManager"

        /** Bump when setup() gains a step that existing sandboxes need backfilled. */
        const val SETUP_VERSION = 4
        const val APK_PACKAGES = "nodejs npm git bash"
        const val GIT_USER_NAME = "Pi-Filling Agent"
        const val GIT_USER_EMAIL = "agent@pi-filling.local"
    }
}
