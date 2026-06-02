package industries.huginn.pifilling.sandbox

/**
 * Lifecycle state of the Alpine sandbox, ported from Kai's SandboxState.
 * Surfaced as a StateFlow by [LinuxSandboxManager] so the UI can render
 * provisioning progress and gate the agent until [Ready].
 */
sealed interface SandboxState {
    data object NotInstalled : SandboxState

    /** Downloading the Alpine minirootfs. [fraction] is 0f..1f, or null if unknown. */
    data class Downloading(val fraction: Float?) : SandboxState

    data object Extracting : SandboxState

    /** Running `apk add` for the toolchain. [message] names the current package. */
    data class Installing(val message: String) : SandboxState

    data object Ready : SandboxState

    data class Error(val message: String) : SandboxState
}

/**
 * Result envelope for a one-shot sandbox command, matching Kai's
 * `{success, stdout, stderr, exit_code, timed_out}` shape.
 */
data class CommandResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean,
)
