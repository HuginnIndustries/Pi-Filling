package industries.huginn.pifilling.wrapper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer 1's half of the host-capability channel: an agent asking us for
 * something the sandbox cannot do itself.
 *
 * The Android capability implementations (speech) are not covered here — they
 * need a device. What is covered is everything around them: allow-listing,
 * refusal codes, not letting a capability take down the reader, and the wire
 * shape the agent will actually parse.
 */
class HostCapabilityTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var process: FakeWrapperProcess
    private lateinit var client: WrapperClient

    private fun start(vararg capabilities: HostCapability): WrapperClient {
        process = FakeWrapperProcess()
        client = WrapperClient(
            process,
            scope,
            hostCapabilities = HostCapabilityRegistry(capabilities.toList()),
        )
        client.start()
        return client
    }

    @After
    fun tearDown() {
        runCatching { client.close() }
        runCatching { process.exitWith(0) }
        scope.cancel()
    }

    private fun capability(name: String, body: suspend (JsonObject) -> JsonObject) =
        object : HostCapability {
            override val name = name
            override suspend fun invoke(params: JsonObject) = body(params)
        }

    private fun request(id: Int, capability: String, params: String = "{}") =
        """{"host_request":{"id":$id,"capability":"$capability","params":$params}}"""

    /** Wait for the client's reply and return its `host_response` object. */
    private suspend fun awaitResponse(id: Int): JsonObject = withTimeout(5_000) {
        while (true) {
            val line = process.written.lineSequence().firstOrNull {
                it.contains("\"host_response\"") && it.contains("\"id\":$id")
            }
            if (line != null) return@withTimeout Json.parseToJsonElement(line).jsonObject["host_response"]!!.jsonObject
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    @Test
    fun `a registered capability runs and its result is returned`(): Unit = runBlocking {
        start(capability("tts.speak") { buildJsonObject { put("utteranceId", "u1") } })
        process.emit(request(1, "tts.speak", """{"text":"hello"}"""))
        val res = awaitResponse(1)
        assertEquals(true, res["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("u1", res["result"]?.jsonObject?.get("utteranceId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `params reach the capability`(): Unit = runBlocking {
        var seen: String? = null
        start(
            capability("tts.speak") { p ->
                seen = p["text"]?.jsonPrimitive?.contentOrNull
                buildJsonObject { }
            },
        )
        process.emit(request(1, "tts.speak", """{"text":"the quick brown fox"}"""))
        awaitResponse(1)
        assertEquals("the quick brown fox", seen)
    }

    /**
     * The allow-list is the security property: the guest names a capability, it
     * cannot introduce one. An unregistered name must be refused, not attempted.
     */
    @Test
    fun `an unregistered capability is refused as unsupported`(): Unit = runBlocking {
        start(capability("tts.speak") { buildJsonObject { } })
        process.emit(request(1, "shell.exec", """{"cmd":"rm -rf /"}"""))
        val res = awaitResponse(1)
        assertEquals(false, res["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(
            HostCapabilityRefusal.UNSUPPORTED,
            res["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `an empty registry refuses everything`(): Unit = runBlocking {
        start()
        process.emit(request(1, "tts.speak"))
        val res = awaitResponse(1)
        assertEquals(false, res["ok"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `a deliberate refusal keeps its own code`(): Unit = runBlocking {
        start(
            capability("tts.speak") {
                throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "speech is off")
            },
        )
        process.emit(request(1, "tts.speak"))
        val res = awaitResponse(1)
        assertEquals(
            HostCapabilityRefusal.NOT_PERMITTED,
            res["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull,
        )
    }

    /** A capability that throws must not take the reader thread with it. */
    @Test
    fun `an exploding capability becomes host_error and the client survives`(): Unit = runBlocking {
        start(
            capability("tts.speak") { error("boom") },
            capability("tts.config") { buildJsonObject { put("autoSpeak", false) } },
        )
        process.emit(request(1, "tts.speak"))
        val first = awaitResponse(1)
        assertEquals("host_error", first["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull)

        // Still serving after the failure.
        process.emit(request(2, "tts.config"))
        val second = awaitResponse(2)
        assertEquals(true, second["ok"]?.jsonPrimitive?.booleanOrNull)
    }

    /**
     * Speech runs for as long as the sentence does. If a slow capability were
     * handled inline on the stdout reader, the whole event stream would stall
     * behind it.
     */
    @Test
    fun `a slow capability does not block other traffic`(): Unit = runBlocking {
        start(
            capability("tts.speak") {
                delay(1_500)
                buildJsonObject { put("done", true) }
            },
        )
        process.emit(request(1, "tts.speak"))
        // The handshake must land while speech is still "running".
        process.emit(
            """{"event":"wrapper_ready","data":{"protocolVersion":1,"model":"m","repoPath":"/r","hasMemory":false}}""",
        )
        withTimeout(1_000) { client.awaitReady() }
        assertEquals(true, awaitResponse(1)["ok"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `a malformed host_request is dropped without answering or crashing`(): Unit = runBlocking {
        start(capability("tts.speak") { buildJsonObject { } })
        process.emit("""{"host_request":{"capability":"tts.speak"}}""") // no id
        process.emit("""{"host_request":{"id":9}}""") // no capability
        process.emit(request(1, "tts.speak"))
        assertEquals(true, awaitResponse(1)["ok"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(
            "an id-less request has nothing to answer",
            !process.written.contains("\"id\":9"),
        )
    }
}
