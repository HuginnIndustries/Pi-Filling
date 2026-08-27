package industries.huginn.pifilling.runtime

import android.content.Context
import android.util.Log
import industries.huginn.pifilling.wrapper.WrapperTimeoutException
import industries.huginn.pifilling.sandbox.LinuxSandboxManager
import industries.huginn.pifilling.sandbox.AgentProvider
import industries.huginn.pifilling.sandbox.SandboxState
import industries.huginn.pifilling.service.DaemonService
import industries.huginn.pifilling.storage.SecureKeyStore
import industries.huginn.pifilling.voice.TtsCapabilities
import industries.huginn.pifilling.voice.VoiceSettings
import industries.huginn.pifilling.wrapper.HostCapabilityRegistry
import industries.huginn.pifilling.wrapper.WrapperClient
import industries.huginn.pifilling.wrapper.WrapperEventType
import industries.huginn.pifilling.wrapper.WrapperProcessExitedException
import industries.huginn.pifilling.wrapper.WrapperResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-lifetime orchestrator that the UI observes. Owns the sandbox, the key
 * store, and (when a session is live) one [WrapperClient]. Kept off the Activity
 * so a run survives configuration changes and brief backgrounding; the
 * [DaemonService] keeps the OS from reclaiming the process while a session runs.
 *
 * This is deliberately thin scaffolding: it demonstrates the Layer 1 -> Layer 3
 * wiring contract end-to-end. Transcript modeling, cost metering, and run
 * history are Stage 1.4/1.5 concerns layered on top of [events].
 */
class AgentController(
    private val appContext: Context,
    val sandbox: LinuxSandboxManager,
    val keyStore: SecureKeyStore,
) {
    // Without a handler an uncaught exception in any child reaches the thread's
    // default handler, which on Android means the app dies. Every call site
    // below should be catching, but the wrapper is a separate process that can
    // fail in ways call sites do not anticipate, so this is the backstop rather
    // than the plan. See CONVENTIONS.md C04.
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "unhandled failure in agent scope", e)
        _session.value = SessionState.Failed(e.message ?: e.javaClass.simpleName)
    }

    private val scope = CoroutineScope(SupervisorJob() + exceptionHandler)

    /**
     * What this host is willing to do on the agent's behalf. Built once and
     * allow-listed here: the agent may ask for a capability by name, but only
     * Layer 1 decides what exists. See wrapper/HostCapability.kt.
     */
    val voiceSettings = VoiceSettings(appContext)
    private val tts = TtsCapabilities(appContext, voiceSettings)
    private val hostCapabilities = HostCapabilityRegistry(tts.all())

    /** Stop any speech in progress. Exposed so the UI can offer a stop control. */
    suspend fun stopSpeaking() = tts.stop()

    private val _session = MutableStateFlow<SessionState>(SessionState.Idle)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    val sandboxState: StateFlow<SandboxState> get() = sandbox.state

    private var client: WrapperClient? = null
    private var process: Process? = null
    private var lastReady: SessionState.Ready? = null
    private var eventsJob: Job? = null

    sealed interface SessionState {
        data object Idle : SessionState
        data object Provisioning : SessionState
        data object Connecting : SessionState
        data class Ready(val model: String, val repoPath: String, val hasMemory: Boolean) : SessionState
        data object Streaming : SessionState
        data class Failed(val message: String) : SessionState
    }

    /** Provision the sandbox if needed. Surfaces progress via [sandboxState]. */
    fun provision() {
        scope.launch {
            _session.value = SessionState.Provisioning
            try {
                sandbox.setup()
                _session.value = SessionState.Idle
            } catch (e: Exception) {
                _session.value = SessionState.Failed(e.message ?: "provision failed")
            }
        }
    }

    /**
     * Start an agent session against [repoGuestPath] (a path inside the rootfs).
     * Requires a stored API key and a [SandboxState.Ready] sandbox.
     */
    fun startSession(
        repoGuestPath: String,
        provider: AgentProvider = AgentProvider.DEFAULT,
        model: String? = null,
    ) {
        scope.launch {
            // Key decryption touches the AndroidKeyStore + disk; keep it off the
            // default dispatcher's small pool.
            val apiKey = withContext(Dispatchers.IO) { keyStore.getApiKey(provider) }
            // Optional: only `git push` needs it, so its absence must not block
            // a session that never pushes.
            val gitHubToken = withContext(Dispatchers.IO) { keyStore.getGitHubToken() }
            if (apiKey.isNullOrBlank()) {
                _session.value = SessionState.Failed("no ${provider.label} API key set")
                return@launch
            }
            if (!sandbox.isReady) {
                _session.value = SessionState.Failed("sandbox not provisioned")
                return@launch
            }
            // A sandbox provisioned by an older build never re-enters setup(),
            // so apply any missing setup steps here — this is the point where a
            // missing git identity would otherwise surface as a failed commit.
            withContext(Dispatchers.IO) { sandbox.ensureCurrent() }
            try {
                _session.value = SessionState.Connecting
                DaemonService.start(appContext)

                val proc = withContext(Dispatchers.IO) {
                    sandbox.startWrapper(apiKey, repoGuestPath, provider, model, gitHubToken = gitHubToken)
                }
                val wc = WrapperClient(proc, scope, hostCapabilities = hostCapabilities)
                process = proc
                client = wc

                eventsJob = scope.launch { drainEvents(wc) }
                wc.start()

                val ready = wc.awaitReady()
                val readyState = SessionState.Ready(ready.model, ready.repoPath, ready.hasMemory)
                lastReady = readyState
                _session.value = readyState
            } catch (e: Exception) {
                Log.e(TAG, "startSession failed", e)
                _session.value = SessionState.Failed(e.message ?: "connect failed")
                teardown()
            }
        }
    }

    fun prompt(text: String) {
        val wc = client ?: run {
            _session.value = SessionState.Failed("no active session")
            return
        }
        scope.launch {
            // The wrapper is a separate process under proot; it can die or wedge
            // mid-request. Unguarded, both surfaced as an app crash rather than a
            // failed session — this was the only unguarded caller (C04).
            try {
                when (val resp = wc.prompt(text)) {
                    is WrapperResponse.Ok -> _session.value = SessionState.Streaming
                    is WrapperResponse.Err ->
                        _session.value = SessionState.Failed("${resp.code}: ${resp.message}")
                }
            } catch (e: WrapperProcessExitedException) {
                Log.w(TAG, "wrapper exited during prompt", e)
                _session.value = SessionState.Failed("wrapper exited (code ${e.exitCode})")
                teardown()
            } catch (e: WrapperTimeoutException) {
                // Alive but unresponsive: leave the process up so abort/shutdown
                // still have something to talk to, and let the user decide.
                Log.w(TAG, "wrapper timed out during prompt", e)
                _session.value = SessionState.Failed(e.message ?: "wrapper timed out")
            }
        }
    }

    fun abort() {
        val wc = client ?: return
        scope.launch { runCatching { wc.abort() } }
    }

    /**
     * Re-assert the foreground service if a session is live. Called from
     * MainActivity.onStart to recover from OEM battery-manager kills.
     */
    fun reassertDaemonIfActive(context: Context) {
        val active = _session.value.let {
            it is SessionState.Connecting || it is SessionState.Ready || it is SessionState.Streaming
        }
        if (active) DaemonService.start(context)
    }

    fun endSession() {
        scope.launch {
            runCatching { client?.shutdown() }
            teardown()
            _session.value = SessionState.Idle
        }
    }

    private suspend fun drainEvents(wc: WrapperClient) {
        wc.events.collect { event ->
            when (event.type) {
                WrapperEventType.AGENT_END -> {
                    // A run finished; return to the live session's Ready state.
                    if (_session.value is SessionState.Streaming) {
                        _session.value = lastReady ?: SessionState.Idle
                    }
                }
                WrapperEventType.ERROR -> Log.w(TAG, "wrapper_error: ${event.data}")
            }
            onEvent(event)
        }
    }

    /** Override / observe in the UI layer; default no-op keeps the controller UI-agnostic. */
    var onEvent: (industries.huginn.pifilling.wrapper.WrapperEvent) -> Unit = {}

    private fun teardown() {
        eventsJob?.cancel()
        eventsJob = null
        runCatching { client?.close() }
        runCatching { process?.destroy() }
        client = null
        process = null
        DaemonService.stop(appContext)
    }

    private companion object {
        const val TAG = "AgentController"
    }
}
