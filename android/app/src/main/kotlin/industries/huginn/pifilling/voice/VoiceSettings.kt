package industries.huginn.pifilling.voice

import android.content.Context

/**
 * Device-level voice preferences.
 *
 * Ordinary SharedPreferences rather than the encrypted store: none of this is a
 * secret. It is separate from the sandbox because whether the phone speaks is a
 * property of the phone, not of a workspace — it should survive rebuilding the
 * sandbox, and it should not differ per repository.
 *
 * The original Termux extension persisted the equivalent to
 * `~/.pi/agent/android-tts-settings.json`, inside the agent's own state. Moving
 * it to the host is the right call here for the same reason the capability lives
 * here: the user, not the agent, owns the decision.
 */
class VoiceSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Master switch, **off until the user turns it on**. An agent can read this
     * and can toggle auto-speak, but it cannot enable speech for itself — that
     * would let a model decide to start talking out of a pocket.
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Whether replies are spoken without being asked for. Only applies when [enabled]. */
    var autoSpeak: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    var rate: Float
        get() = prefs.getFloat(KEY_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_RATE, value.coerceIn(0.1f, 3.0f)).apply()

    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value.coerceIn(0.1f, 3.0f)).apply()

    private companion object {
        const val PREFS = "pifilling_voice"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO = "auto_speak"
        const val KEY_RATE = "rate"
        const val KEY_PITCH = "pitch"
    }
}
