package industries.huginn.pifilling.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import industries.huginn.pifilling.wrapper.HostCapability
import industries.huginn.pifilling.wrapper.HostCapabilityRefusal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/**
 * Speech, as a host capability.
 *
 * Ported from TheAmericanMaker/pi-termux-android-voice, which solved this for
 * Termux by shelling out to `termux-tts-speak`. That approach cannot work here —
 * the agent is inside proot with no route to Android — but the substitution is
 * an upgrade rather than a workaround. That project's own architecture notes name
 * "a small Android companion app/service that calls Android TextToSpeech.stop()"
 * as the fix for problems it could not solve from Termux. Layer 1 is that app,
 * so this gets three things the original could not have:
 *
 *  - a **real stop**, rather than flushing the queue with an empty utterance;
 *  - **real chunking**, because [UtteranceProgressListener] reports completion,
 *    so a long reply can be spoken in pieces without guessing at timing;
 *  - **no Termux:API dependency** to install.
 *
 * Speaking aloud is off until the user turns it on. That is not caution for its
 * own sake: on a desktop an unexpected voice is startling, and on a phone in a
 * pocket during a meeting it is worse.
 */
class TtsCapabilities(
    private val context: Context,
    private val settings: VoiceSettings,
) {
    /** Both capabilities, ready to register in the allow-list. */
    fun all(): List<HostCapability> = listOf(SpeakCapability(), ConfigCapability())

    private val engineLock = Mutex()
    private var engine: TextToSpeech? = null
    private val utteranceCounter = AtomicLong(0)
    private val completions = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private suspend fun engine(): TextToSpeech = engineLock.withLock {
        engine?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!ok) {
            runCatching { tts.shutdown() }
            throw HostCapabilityRefusal(
                HostCapabilityRefusal.UNSUPPORTED,
                "no usable text-to-speech engine on this device",
            )
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { completions.remove(it)?.complete(true) }
            }

            @Deprecated("required by the base class", ReplaceWith(""))
            override fun onError(utteranceId: String?) {
                utteranceId?.let { completions.remove(it)?.complete(false) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { completions.remove(it)?.complete(false) }
            }
        })
        engine = tts
        tts
    }

    /** Stop any speech in progress. The thing the Termux version could not do. */
    suspend fun stop() {
        runCatching { engine?.stop() }
        completions.values.forEach { it.complete(false) }
        completions.clear()
    }

    fun shutdown() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
    }

    private fun requireEnabled() {
        if (!settings.enabled) {
            throw HostCapabilityRefusal(
                HostCapabilityRefusal.NOT_PERMITTED,
                "the user has speech switched off",
            )
        }
    }

    /**
     * Split on sentence boundaries so each utterance stays under the engine's
     * limit. Splitting mid-word is audible; splitting mid-sentence is merely
     * ungraceful, so sentences are the unit and an over-long one is hard-cut as
     * a last resort.
     */
    private fun chunk(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (sentence in Regex("(?<=[.!?])\\s+").split(text)) {
            when {
                sentence.length > limit -> {
                    if (buf.isNotEmpty()) { out.add(buf.toString()); buf.clear() }
                    sentence.chunked(limit).forEach(out::add)
                }
                buf.length + sentence.length + 1 > limit -> {
                    out.add(buf.toString()); buf.clear(); buf.append(sentence)
                }
                else -> {
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(sentence)
                }
            }
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out.filter { it.isNotBlank() }
    }

    private inner class SpeakCapability : HostCapability {
        override val name = "tts.speak"

        override suspend fun invoke(params: JsonObject): JsonObject {
            requireEnabled()
            val text = params["text"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
            if (text.isEmpty()) return buildJsonObject { put("spoken", false) }

            val tts = engine()
            (params["rate"] as? JsonPrimitive)?.floatOrNull?.let { tts.setSpeechRate(it) }
                ?: tts.setSpeechRate(settings.rate)
            (params["pitch"] as? JsonPrimitive)?.floatOrNull?.let { tts.setPitch(it) }
                ?: tts.setPitch(settings.pitch)

            val limit = runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(3_500)
            val pieces = chunk(text, limit)
            var lastId = ""
            for ((index, piece) in pieces.withIndex()) {
                val id = "pf-${utteranceCounter.incrementAndGet()}"
                lastId = id
                val done = CompletableDeferred<Boolean>()
                completions[id] = done
                // QUEUE_FLUSH on the first piece so a new request interrupts
                // whatever was speaking; ADD for the rest so one reply stays
                // contiguous.
                val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val queued = tts.speak(piece, mode, null, id)
                if (queued != TextToSpeech.SUCCESS) {
                    completions.remove(id)
                    throw IllegalStateException("engine refused the utterance")
                }
            }
            Log.i(TAG, "speaking ${text.length} chars in ${pieces.size} piece(s)")
            return buildJsonObject {
                put("spoken", true)
                put("utteranceId", lastId)
                put("pieces", pieces.size)
            }
        }
    }

    private inner class ConfigCapability : HostCapability {
        override val name = "tts.config"

        override suspend fun invoke(params: JsonObject): JsonObject {
            // Deliberately readable without [requireEnabled]: the agent is
            // allowed to ask what the state is, and the user's own UI is what
            // turns the feature on. An agent cannot enable its own voice.
            (params["autoSpeak"] as? JsonPrimitive)?.booleanOrNull?.let { settings.autoSpeak = it }
            (params["rate"] as? JsonPrimitive)?.floatOrNull?.let { settings.rate = it }
            (params["pitch"] as? JsonPrimitive)?.floatOrNull?.let { settings.pitch = it }
            return buildJsonObject {
                put("enabled", settings.enabled)
                put("autoSpeak", settings.enabled && settings.autoSpeak)
                put("rate", settings.rate)
                put("pitch", settings.pitch)
            }
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

    private companion object {
        const val TAG = "TtsCapability"
        const val INIT_TIMEOUT_MS = 8_000L
    }
}
