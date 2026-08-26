package industries.huginn.pifilling.runtime

import android.content.Context
import android.util.Log
import industries.huginn.pifilling.sandbox.LinuxSandboxManager
import industries.huginn.pifilling.sandbox.AgentProvider
import industries.huginn.pifilling.sandbox.SandboxState
import industries.huginn.pifilling.service.DaemonService
import industries.huginn.pifilling.storage.SecureKeyStore
import industries.huginn.pifilling.wrapper.WrapperClient
import industries.huginn.pifilling.wrapper.WrapperEventType
import industries.huginn.pifilling.wrapper.WrapperResponse
import kotlinx.coroutines.CoroutineScope
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
    private val scope = CoroutineScope(SupervisorJob())

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
                    sandbox.startWrapper(apiKey, repoGuestPath, provider, model)
                }
                val wc = WrapperClient(proc, scope)
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
            when (val resp = wc.prompt(text)) {
                is WrapperResponse.Ok -> _session.value = SessionState.Streaming
                is WrapperResponse.Err -> _session.value = SessionState.Failed("${resp.code}: ${resp.message}")
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
