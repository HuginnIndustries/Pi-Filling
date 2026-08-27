package industries.huginn.pifilling.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import industries.huginn.pifilling.runtime.AgentController
import industries.huginn.pifilling.sandbox.AgentProvider
import industries.huginn.pifilling.sandbox.SandboxState
import industries.huginn.pifilling.wrapper.WrapperEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * UI state holder. Bridges [AgentController]'s flows to Compose and accumulates
 * a lightweight transcript from the wrapper's event stream. The transcript model
 * is intentionally minimal for the scaffold — a production UI would render tool
 * calls, diffs, and token/cost meters from the same events.
 */
class AppViewModel(private val agent: AgentController) : ViewModel() {

    val sandboxState: StateFlow<SandboxState> = agent.sandboxState

    val sessionState: StateFlow<AgentController.SessionState> =
        agent.session.stateIn(viewModelScope, SharingStarted.Eagerly, agent.session.value)

    private val _provider = MutableStateFlow(AgentProvider.DEFAULT)
    val provider: StateFlow<AgentProvider> = _provider.asStateFlow()

    private val _hasApiKey = MutableStateFlow(agent.keyStore.hasApiKey(AgentProvider.DEFAULT))
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    /**
     * Switching provider re-reads whether *that* provider has a key, so the UI
     * asks for a credential per provider rather than assuming one covers both.
     */
    fun setProvider(next: AgentProvider) {
        _provider.value = next
        _hasApiKey.value = agent.keyStore.hasApiKey(next)
    }

    private val _transcript = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val transcript: StateFlow<List<TranscriptLine>> = _transcript.asStateFlow()

    data class TranscriptLine(val role: String, val text: String)

    init {
        agent.onEvent = ::onWrapperEvent
    }

    fun saveApiKey(key: String) {
        val target = _provider.value
        viewModelScope.launch(Dispatchers.IO) {
            agent.keyStore.setApiKey(key.trim(), target)
            _hasApiKey.value = true
        }
    }

    private val _hasGitHubToken = MutableStateFlow(agent.keyStore.hasGitHubToken())
    val hasGitHubToken: StateFlow<Boolean> = _hasGitHubToken.asStateFlow()

    fun saveGitHubToken(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            agent.keyStore.setGitHubToken(token.trim())
            _hasGitHubToken.value = true
        }
    }

    private val _voiceEnabled = MutableStateFlow(agent.voiceSettings.enabled)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    /**
     * The user's own switch. Deliberately not reachable by the agent: it can ask
     * what the setting is and can toggle auto-speak, but turning speech on is a
     * decision a model should not be able to make for someone whose phone is in
     * a pocket.
     */
    fun setVoiceEnabled(enabled: Boolean) {
        agent.voiceSettings.enabled = enabled
        _voiceEnabled.value = enabled
        if (!enabled) viewModelScope.launch { agent.stopSpeaking() }
    }

    fun clearGitHubToken() {
        viewModelScope.launch(Dispatchers.IO) {
            agent.keyStore.clearGitHubToken()
            _hasGitHubToken.value = false
        }
    }

    fun clearApiKey() {
        val target = _provider.value
        viewModelScope.launch(Dispatchers.IO) {
            agent.keyStore.clearApiKey(target)
            _hasApiKey.value = false
        }
    }

    fun provision() = agent.provision()

    fun startSession(repoGuestPath: String) =
        agent.startSession(repoGuestPath, _provider.value, _provider.value.defaultModel)

    fun send(text: String) {
        if (text.isBlank()) return
        append("you", text)
        agent.prompt(text)
    }

    fun abort() = agent.abort()

    fun endSession() = agent.endSession()

    private fun onWrapperEvent(event: WrapperEvent) {
        // Accumulate streamed assistant text deltas; tag other events as system.
        when (event.type) {
            "message_update" -> {
                // The wrapper nests the streamed chunk as
                //   data.assistantMessageEvent = { type: "text_delta", delta: "..." }
                // Neither `assistantMessageEvent.text` nor a top-level `data.delta`
                // exists, so the previous lookup never matched and the agent's
                // answer never reached the transcript.
                //
                // The type check is not optional: `thinking_delta` carries a
                // `delta` field too, and reading it unconditionally would splice
                // the model's reasoning into the visible reply.
                val assistantEvent = event.data["assistantMessageEvent"]?.jsonObject
                if (assistantEvent?.get("type")?.jsonPrimitive?.contentOrNull == "text_delta") {
                    val delta = assistantEvent["delta"]?.jsonPrimitive?.contentOrNull
                    if (!delta.isNullOrEmpty()) appendToLast("assistant", delta)
                }
            }
            "tool_execution_start" -> {
                val name = event.data["toolName"]?.jsonPrimitive?.contentOrNull ?: "tool"
                append("system", "▸ $name")
            }
            "agent_end" -> append("system", "— done —")
            "wrapper_error" -> append(
                "system",
                "error: ${event.data["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}",
            )
        }
    }

    private fun append(role: String, text: String) {
        _transcript.value = _transcript.value + TranscriptLine(role, text)
    }

    private fun appendToLast(role: String, delta: String) {
        val current = _transcript.value
        val last = current.lastOrNull()
        _transcript.value = if (last != null && last.role == role) {
            current.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            current + TranscriptLine(role, delta)
        }
    }

    companion object {
        fun factory(agent: AgentController): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(agent) as T
            }
    }
}
