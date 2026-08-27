package industries.huginn.pifilling.wrapper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Behavioural tests for [WrapperClient], written against the failure modes the
 * CodeCartographer defect scan found (findings 1.1, 2.2, 3.1).
 *
 * These use a real dispatcher rather than virtual time on purpose: the client's
 * readers are blocking stream loops on `Dispatchers.IO`, and the defects being
 * covered are about what happens when a *separate OS process* dies or goes
 * quiet. Faking the clock would fake away the thing under test. Every test is
 * wrapped in `withTimeout`, so a regression that reintroduces a hang fails the
 * suite instead of stalling it — which matters, because the original defect's
 * symptom was an unbounded wait.
 */
class WrapperClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var process: FakeWrapperProcess
    private lateinit var client: WrapperClient

    private fun start(
        callTimeoutMs: Long = 30_000,
        readyTimeoutMs: Long = 30_000,
    ): WrapperClient {
        process = FakeWrapperProcess()
        client = WrapperClient(process, scope, callTimeoutMs, readyTimeoutMs)
        client.start()
        return client
    }

    @After
    fun tearDown() {
        runCatching { client.close() }
        runCatching { process.exitWith(0) }
        scope.cancel()
    }

    private fun readyLine() =
        """{"event":"wrapper_ready","data":{"protocolVersion":1,"provider":"ollama",""" +
            """"model":"m","repoPath":"/root/repo","hasMemory":false}}"""

    // ---- handshake ----------------------------------------------------------

    @Test
    fun `awaitReady resolves from the wrapper_ready event`(): Unit = runBlocking {
        val c = start()
        val ready = async { c.awaitReady() }
        process.emit(readyLine())
        withTimeout(5_000) {
            val r = ready.await()
            assertEquals(1, r.protocolVersion)
            assertEquals("/root/repo", r.repoPath)
            assertEquals(false, r.hasMemory)
        }
    }

    @Test
    fun `awaitReady fails when the process dies before announcing`(): Unit = runBlocking {
        val c = start()
        val ready = async { runCatching { c.awaitReady() } }
        process.exitWith(3)
        withTimeout(5_000) {
            val e = ready.await().exceptionOrNull()
            assertTrue("expected exit, got $e", e is WrapperProcessExitedException)
            assertEquals(3, (e as WrapperProcessExitedException).exitCode)
        }
    }

    /** Finding 2.2: a wrapper that is alive but silent must not hang the caller. */
    @Test
    fun `awaitReady times out when the wrapper never announces`(): Unit = runBlocking {
        val c = start(readyTimeoutMs = 300)
        withTimeout(5_000) {
            val e = runCatching { c.awaitReady() }.exceptionOrNull()
            assertTrue("expected timeout, got $e", e is WrapperTimeoutException)
        }
    }

    // ---- request/response ---------------------------------------------------

    @Test
    fun `a request is correlated to its response by id`(): Unit = runBlocking {
        val c = start()
        val call = async { c.state() }
        withTimeout(5_000) {
            while (!process.written.contains("\"method\":\"state\"")) delay(10)
        }
        process.emit("""{"id":1,"result":{"busy":false}}""")
        withTimeout(5_000) {
            val resp = call.await()
            assertTrue(resp is WrapperResponse.Ok)
            assertEquals(1, resp.id)
        }
    }

    @Test
    fun `an error response surfaces its code and message`(): Unit = runBlocking {
        val c = start()
        val call = async { c.prompt("hi") }
        withTimeout(5_000) {
            while (!process.written.contains("\"method\":\"prompt\"")) delay(10)
        }
        process.emit("""{"id":1,"error":{"code":"busy","message":"a run is in flight"}}""")
        withTimeout(5_000) {
            val resp = call.await() as WrapperResponse.Err
            assertEquals("busy", resp.code)
            assertEquals("a run is in flight", resp.message)
        }
    }

    /** Finding 2.2 again, on the request path rather than the handshake. */
    @Test
    fun `a call times out when the wrapper never answers`(): Unit = runBlocking {
        val c = start(callTimeoutMs = 300)
        withTimeout(5_000) {
            val e = runCatching { c.state() }.exceptionOrNull()
            assertTrue("expected timeout, got $e", e is WrapperTimeoutException)
        }
    }

    // ---- process death ------------------------------------------------------

    @Test
    fun `an in-flight request fails when the process exits`(): Unit = runBlocking {
        val c = start()
        val call = async { runCatching { c.state() } }
        withTimeout(5_000) {
            while (!process.written.contains("\"method\":\"state\"")) delay(10)
        }
        process.exitWith(4)
        withTimeout(5_000) {
            val e = call.await().exceptionOrNull()
            assertTrue("expected exit, got $e", e is WrapperProcessExitedException)
        }
    }

    /**
     * Finding 3.1. The original defect let a request register in `pending`
     * *after* the exit drain had run, so nothing ever failed it and the caller
     * suspended forever.
     *
     * The race window is microseconds wide, so hammering calls across the exit
     * is a probabilistic reproduction, not a deterministic one. What this test
     * pins is the contract that has to hold either way: **once the process has
     * exited, no call may hang** — every one of them terminates, and does so by
     * throwing rather than returning. A regression reopens an unbounded wait,
     * and `withTimeout` turns that into a failure instead of a stalled suite.
     */
    @Test
    fun `no call hangs when requests race process exit`(): Unit = runBlocking {
        val c = start(callTimeoutMs = 60_000) // long, so a pass cannot come from the timeout
        val outcomes = java.util.concurrent.CopyOnWriteArrayList<Throwable?>()

        val callers = (1..40).map {
            scope.async { runCatching { c.state() }.exceptionOrNull().also(outcomes::add) }
        }
        delay(15)
        process.exitWith(9)

        withTimeout(10_000) { callers.forEach { it.await() } }

        assertEquals("every caller must terminate", 40, outcomes.size)
        val bad = outcomes.filterNot { it is WrapperProcessExitedException }
        assertTrue("all failures should be process-exit, got: ${bad.map { it?.javaClass?.simpleName }}", bad.isEmpty())
    }

    @Test
    fun `a call after a known exit fails immediately`(): Unit = runBlocking {
        val c = start()
        process.exitWith(1)
        withTimeout(5_000) {
            while (process.isAlive) delay(5)
            delay(50) // let onProcessExit land
            val e = runCatching { c.state() }.exceptionOrNull()
            assertTrue("expected exit, got $e", e is WrapperProcessExitedException)
        }
    }

    // ---- events -------------------------------------------------------------

    /**
     * Finding 1.1. The flow previously used the default SUSPEND overflow policy,
     * so `tryEmit` refused the *newest* event once the buffer filled — the exact
     * opposite of the comment above it, and the wrong end to drop for a live
     * transcript. With DROP_OLDEST the newest event always survives.
     */
    @Test
    fun `event overflow drops the oldest and keeps the newest`(): Unit = runBlocking {
        val c = start()
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val collector = scope.launch {
            c.events.collect {
                delay(5) // a deliberately slow collector, to force overflow
                seen.add(it.type)
            }
        }
        delay(100)

        val total = 400 // comfortably past the 256-event buffer
        repeat(total) { i -> process.emit("""{"event":"e$i","data":{}}""") }

        withTimeout(15_000) {
            while (seen.none { it == "e${total - 1}" } && seen.size < total) delay(20)
        }
        collector.cancel()

        assertTrue(
            "the newest event must survive overflow; saw ${seen.size} of $total",
            seen.contains("e${total - 1}"),
        )
    }

    @Test
    fun `a malformed line does not kill the reader`(): Unit = runBlocking {
        val c = start()
        process.emit("this is not json")
        process.emit("""{"neither":"event nor id"}""")
        process.emit(readyLine())
        withTimeout(5_000) { c.awaitReady() }
    }

    @Test
    fun `stderr is drained without being parsed as protocol`(): Unit = runBlocking {
        val c = start()
        process.emitStderr("[wrapper:info] some free-form log line")
        process.emit(readyLine())
        withTimeout(5_000) { c.awaitReady() }
    }

    @Test
    fun `close does not kill the process it was given`(): Unit = runBlocking {
        val c = start()
        process.emit(readyLine())
        withTimeout(5_000) { c.awaitReady() }
        c.close()
        assertTrue("close() tears down the client, not the process", process.isAlive)
        if (false) fail("unreachable")
    }
}
