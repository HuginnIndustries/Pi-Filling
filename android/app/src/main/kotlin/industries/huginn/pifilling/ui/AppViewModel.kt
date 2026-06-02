package industries.huginn.pifilling.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import industries.huginn.pifilling.runtime.AgentController
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

    private val _hasApiKey = MutableStateFlow(agent.keyStore.hasApiKey())
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val transcript: StateFlow<List<TranscriptLine>> = _transcript.asStateFlow()

    data class TranscriptLine(val role: String, val text: String)

    init {
        agent.onEvent = ::onWrapperEvent
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            agent.keyStore.setApiKey(key.trim())
            _hasApiKey.value = true
        }
    }

    fun clearApiKey() {
        viewModelScope.launch(Dispatchers.IO) {
            agent.keyStore.clearApiKey()
            _hasApiKey.value = false
        }
    }

    fun provision() = agent.provision()

    fun startSession(repoGuestPath: String) = agent.startSession(repoGuestPath)

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
                val delta = event.data["assistantMessageEvent"]?.jsonObject
                    ?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: event.data["delta"]?.jsonPrimitive?.contentOrNull
                if (!delta.isNullOrEmpty()) appendToLast("assistant", delta)
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
