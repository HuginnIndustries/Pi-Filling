package industries.huginn.pifilling.wrapper

import kotlinx.serialization.json.JsonObject

/**
 * Wire types for the node-wrapper JSONL protocol.
 *
 * This is the Kotlin mirror of `node-wrapper/DESIGN.md`. Keep the two in sync:
 * if the wrapper's protocol version changes, [PROTOCOL_VERSION] and these types
 * change together.
 *
 * Requests (Layer 1 -> wrapper) are `{"id":<n>,"method":<name>,"params":{...}}`.
 * The wrapper replies on the same line-delimited stdout with either a response
 * (`{"id":<n>,"result":{...}}` / `{"id":<n>,"error":{...}}`) or an async event
 * push (`{"event":<type>,"data":{...}}`). stderr is free-form logs and is not
 * part of the protocol.
 */
const val PROTOCOL_VERSION = 1

/** A response to a request, matched back to the caller by [id]. */
sealed interface WrapperResponse {
    val id: Int

    data class Ok(override val id: Int, val result: JsonObject) : WrapperResponse
    data class Err(override val id: Int, val code: String, val message: String) : WrapperResponse
}

/** An async event push from the wrapper (not correlated to any request id). */
data class WrapperEvent(
    val type: String,
    val data: JsonObject,
)

/**
 * Parsed `wrapper_ready` handshake. The wrapper emits exactly one of these
 * after the agent is constructed and before it processes any request; Layer 1
 * must await it before the first [WrapperClient.prompt].
 */
data class ReadyEvent(
    val protocolVersion: Int,
    val model: String,
    val repoPath: String,
    val hasMemory: Boolean,
)

/** Method names the wrapper understands. Centralized to avoid typos. */
object WrapperMethod {
    const val PROMPT = "prompt"
    const val ABORT = "abort"
    const val STATE = "state"
    const val SHUTDOWN = "shutdown"
}

/** Synthetic event types the wrapper emits in addition to pi-agent-core's. */
object WrapperEventType {
    const val READY = "wrapper_ready"
    const val ERROR = "wrapper_error"
    const val AGENT_START = "agent_start"
    const val AGENT_END = "agent_end"
}

/**
 * Error codes the wrapper can return in a [WrapperResponse.Err]. Mirrors the
 * codes in DESIGN.md so call sites can branch on a constant, not a string.
 */
object WrapperErrorCode {
    const val BAD_PARAMS = "bad_params"
    const val BUSY = "busy"
    const val UNKNOWN_METHOD = "unknown_method"
    const val SHUTTING_DOWN = "shutting_down"
    const val HANDLER_ERROR = "handler_error"
}

/** Thrown when the wrapper process exits before answering an in-flight request. */
class WrapperProcessExitedException(val exitCode: Int) :
    RuntimeException("wrapper process exited (code $exitCode) before responding")
