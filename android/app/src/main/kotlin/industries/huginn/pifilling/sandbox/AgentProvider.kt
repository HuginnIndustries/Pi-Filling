package industries.huginn.pifilling.sandbox

/**
 * LLM providers Layer 1 can drive the wrapper against.
 *
 * These mirror the `PROVIDERS` table in `node-wrapper/src/wrapper.mjs`: [id] is
 * what gets passed as `--provider`, and [keyEnv] is the environment variable the
 * wrapper reads that provider's credential from. Keeping the two in step is the
 * whole contract — the wrapper rejects an unknown `--provider`, and each
 * provider reads only its own variable, so an Anthropic key will not satisfy
 * Ollama.
 *
 * [ANTHROPIC] is what v1 ships against (V1_SPEC.md). [OLLAMA] exists so the
 * agent loop can be exercised end-to-end against a cheaper endpoint during
 * development; it is not a v1 shipping target.
 */
enum class AgentProvider(
    val id: String,
    val label: String,
    val keyEnv: String,
    val keyHint: String,
    val defaultModel: String,
) {
    ANTHROPIC(
        id = "anthropic",
        label = "Anthropic",
        keyEnv = "ANTHROPIC_API_KEY",
        keyHint = "sk-ant-...",
        defaultModel = "claude-haiku-4-5-20251001",
    ),
    OLLAMA(
        id = "ollama",
        label = "Ollama Cloud",
        keyEnv = "OLLAMA_API_KEY",
        keyHint = "Ollama Cloud API key",
        defaultModel = "gpt-oss:120b",
    ),
    ;

    /**
     * Preference key for this provider's stored credential. Anthropic keeps the
     * historical `anthropic_api_key` name so an existing install does not lose
     * its key when provider support lands.
     */
    val prefKey: String get() = "${id}_api_key"

    companion object {
        val DEFAULT = ANTHROPIC

        fun fromId(id: String?): AgentProvider =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
