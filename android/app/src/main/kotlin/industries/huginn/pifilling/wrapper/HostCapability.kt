package industries.huginn.pifilling.wrapper

import android.util.Log
import kotlinx.serialization.json.JsonObject

/**
 * One thing Layer 1 can do on the agent's behalf that the sandbox cannot do
 * itself — speak, notify, read the clipboard, open the share sheet.
 *
 * The agent runs inside proot with no route to Android, so it names a capability
 * and this side decides whether and how to honour it. See
 * `node-wrapper/DESIGN.md` for the wire format.
 */
interface HostCapability {
    /** Namespaced and host-neutral, e.g. `tts.speak`. Never platform-specific. */
    val name: String

    /**
     * Perform the capability. The returned object becomes the `result`.
     *
     * Throw [HostCapabilityRefusal] to decline with a specific code; any other
     * exception is reported as `host_error`, which is the honest label for "we
     * tried and something broke".
     */
    suspend fun invoke(params: JsonObject): JsonObject
}

/**
 * A deliberate refusal, as opposed to a failure.
 *
 * Refusal is a normal outcome: a user who has left speech switched off is not a
 * malfunction, and the agent is expected to carry on in text. The distinct code
 * is what lets the tool on the other side say something useful rather than
 * retrying forever.
 */
class HostCapabilityRefusal(val code: String, message: String) : Exception(message) {
    companion object {
        const val UNSUPPORTED = "unsupported_capability"
        const val NOT_PERMITTED = "not_permitted"
    }
}

/**
 * The allow-list.
 *
 * Capabilities are registered here by Layer 1 and addressed by name from the
 * guest. That direction matters: the agent executes model-chosen code, so it may
 * *ask* for a capability but can never *introduce* one. Anything unregistered is
 * refused rather than attempted, and an empty registry is a perfectly valid
 * configuration — it means this build offers the agent nothing.
 */
class HostCapabilityRegistry(capabilities: List<HostCapability> = emptyList()) {

    private val byName: Map<String, HostCapability> = capabilities.associateBy { it.name }

    val names: Set<String> get() = byName.keys

    /**
     * Route one request. Never throws: every outcome is a [Result] the caller
     * can turn into a `host_response`, because a capability that blows up must
     * not take the client's reader thread with it.
     */
    suspend fun dispatch(capability: String, params: JsonObject): Result {
        val impl = byName[capability]
            ?: return Result.Refused(
                HostCapabilityRefusal.UNSUPPORTED,
                "this host does not implement $capability",
            )
        return try {
            Result.Ok(impl.invoke(params))
        } catch (e: HostCapabilityRefusal) {
            Result.Refused(e.code, e.message ?: "refused")
        } catch (e: Exception) {
            Log.w(TAG, "capability $capability failed", e)
            Result.Refused("host_error", e.message ?: e.javaClass.simpleName)
        }
    }

    sealed interface Result {
        data class Ok(val result: JsonObject) : Result
        data class Refused(val code: String, val message: String) : Result
    }

    private companion object {
        const val TAG = "HostCapabilities"
    }
}
