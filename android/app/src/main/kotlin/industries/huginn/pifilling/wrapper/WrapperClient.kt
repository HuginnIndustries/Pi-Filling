package industries.huginn.pifilling.wrapper

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives one already-started node-wrapper [Process] over its stdio JSONL
 * protocol (see [Protocol] and `node-wrapper/DESIGN.md`).
 *
 * Responsibilities:
 *  - read stdout and stderr on independent IO coroutines (never one thread for
 *    both — that deadlocks on a full pipe buffer);
 *  - correlate `{"id":...}` responses back to suspending [call]ers;
 *  - fan out `{"event":...}` pushes via [events];
 *  - surface the one-time `wrapper_ready` handshake via [awaitReady];
 *  - fail every outstanding and future request if the process dies.
 *
 * The caller owns process creation (it must be launched inside proot with the
 * API key supplied via env at spawn — see DESIGN.md / ARCHITECTURE.md) and
 * lifetime; [close] only tears down this client's coroutines and streams.
 */
class WrapperClient(
    private val process: Process,
    private val scope: CoroutineScope,
    /** Bound on a single request/response round trip. See [call]. */
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    /** Bound on the startup handshake. See [awaitReady]. */
    private val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val writer: BufferedWriter = process.outputStream.bufferedWriter()
    private val writeMutex = Mutex()

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<WrapperResponse>>()

    private val _events = MutableSharedFlow<WrapperEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        // Drop the oldest, not the newest. With the default (SUSPEND) policy
        // tryEmit refuses the *new* event and returns false, which is the
        // opposite of what a live transcript wants: the newest agent events are
        // the ones the UI most needs.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<WrapperEvent> = _events.asSharedFlow()

    private val readyDeferred = CompletableDeferred<ReadyEvent>()

    @Volatile
    private var exitCode: Int? = null

    private val jobs = mutableListOf<Job>()

    /** Start the reader coroutines. Call exactly once, right after construction. */
    fun start() {
        jobs += scope.launch(Dispatchers.IO) { pumpStdout() }
        jobs += scope.launch(Dispatchers.IO) { pumpStderr() }
        jobs += scope.launch(Dispatchers.IO) {
            val code = runInterruptible { process.waitFor() }
            onProcessExit(code)
        }
    }

    /**
     * Suspends until the wrapper announces `wrapper_ready`, the process dies, or
     * [readyTimeoutMs] elapses.
     *
     * The timeout is not belt-and-braces: the wrapper boots Node and loads the
     * agent stack inside Alpine under proot on a phone. If that wedges rather
     * than exits, the process-death path never fires and this would otherwise
     * suspend forever with the UI stuck on "connecting".
     */
    suspend fun awaitReady(): ReadyEvent =
        withTimeoutOrNull(readyTimeoutMs) { readyDeferred.await() }
            ?: throw WrapperTimeoutException("wrapper did not report ready within ${readyTimeoutMs}ms")

    suspend fun prompt(text: String): WrapperResponse =
        call(WrapperMethod.PROMPT, buildJsonObject { put("text", text) })

    suspend fun abort(): WrapperResponse = call(WrapperMethod.ABORT, null)

    suspend fun state(): WrapperResponse = call(WrapperMethod.STATE, null)

    suspend fun shutdown(): WrapperResponse = call(WrapperMethod.SHUTDOWN, null)

    private suspend fun call(method: String, params: JsonObject?): WrapperResponse {
        exitCode?.let { throw WrapperProcessExitedException(it) }

        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<WrapperResponse>()
        pending[id] = deferred

        // Re-check after registering, not only before. [onProcessExit] sets
        // exitCode and *then* drains `pending`, so a request that registered
        // after that drain would never be failed and its caller would suspend
        // forever — the exact hang the drain exists to prevent. Because the
        // ordering there is exit-code-first, observing a non-null exitCode here
        // means the drain has already run or is guaranteed to see our entry.
        // Do not reorder onProcessExit without revisiting this.
        exitCode?.let {
            pending.remove(id)
            throw WrapperProcessExitedException(it)
        }

        val request = buildJsonObject {
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

        try {
            writeLine(request.toString())
        } catch (e: IOException) {
            pending.remove(id)
            throw WrapperProcessExitedException(exitCode ?: -1).initCause(e) as WrapperProcessExitedException
        }

        // A wrapper that is alive but wedged answers nothing, and the
        // process-death path never fires. Bound the wait and clean up the
        // registration so a timed-out id cannot be completed later.
        return withTimeoutOrNull(callTimeoutMs) { deferred.await() }
            ?: run {
                pending.remove(id)
                throw WrapperTimeoutException("wrapper did not answer $method within ${callTimeoutMs}ms")
            }
    }

    private suspend fun writeLine(line: String) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    private fun pumpStdout() {
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) handleLine(line)
            }
        } catch (e: IOException) {
            Log.d(TAG, "stdout pump ended: ${e.message}")
        }
    }

    private fun pumpStderr() {
        try {
            process.errorStream.bufferedReader().forEachLine { line ->
                // stderr is free-form wrapper logs; surface at debug only.
                Log.d(TAG_WRAPPER, line)
            }
        } catch (e: IOException) {
            Log.d(TAG, "stderr pump ended: ${e.message}")
        }
    }

    private fun handleLine(line: String) {
        val obj = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "unparseable line from wrapper: $line")
            return
        }

        when {
            obj.containsKey("event") -> handleEvent(obj)
            obj.containsKey("id") -> handleResponse(obj)
            else -> Log.w(TAG, "wrapper line had neither event nor id: $line")
        }
    }

    private fun handleEvent(obj: JsonObject) {
        val type = obj["event"]?.jsonPrimitive?.contentOrNull ?: return
        val data = obj["data"]?.jsonObject ?: JsonObject(emptyMap())

        if (type == WrapperEventType.READY && !readyDeferred.isCompleted) {
            readyDeferred.complete(
                ReadyEvent(
                    protocolVersion = (data["protocolVersion"] as? JsonPrimitive)?.intOrNull ?: 0,
                    model = data["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    repoPath = data["repoPath"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    hasMemory = (data["hasMemory"] as? JsonPrimitive)?.content?.toBoolean() ?: false,
                ),
            )
        }

        // Backpressure-safe: DROP_OLDEST means tryEmit always succeeds, so the
        // reader never blocks on a slow collector. A false return would mean the
        // policy changed underneath us.
        if (!_events.tryEmit(WrapperEvent(type, data))) {
            Log.w(TAG, "event dropped despite DROP_OLDEST; overflow policy changed? type=$type")
        }
    }

    private fun handleResponse(obj: JsonObject) {
        val id = (obj["id"] as? JsonPrimitive)?.intOrNull ?: return
        val deferred = pending.remove(id) ?: run {
            Log.w(TAG, "response for unknown id=$id")
            return
        }
        val error = obj["error"]?.jsonObject
        if (error != null) {
            deferred.complete(
                WrapperResponse.Err(
                    id = id,
                    code = error["code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ),
            )
        } else {
            val result = obj["result"]?.jsonObject ?: JsonObject(emptyMap())
            deferred.complete(WrapperResponse.Ok(id, result))
        }
    }

    private fun onProcessExit(code: Int) {
        exitCode = code
        if (!readyDeferred.isCompleted) {
            readyDeferred.completeExceptionally(WrapperProcessExitedException(code))
        }
        // Fail every outstanding request so callers don't hang forever.
        val drained = pending.keys.toList()
        for (id in drained) {
            pending.remove(id)?.completeExceptionally(WrapperProcessExitedException(code))
        }
        Log.i(TAG, "wrapper process exited with code $code")
    }

    /**
     * Tear down: cancel the reader coroutines and close streams so the blocking
     * line readers unblock. Does not kill the process — the owner does that.
     */
    fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { writer.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private companion object {
        const val TAG = "WrapperClient"

        /**
         * A request is acknowledged, not completed, within this bound — `prompt`
         * returns `started:true` immediately and the run streams as events — so
         * this measures wrapper responsiveness, not agent runtime.
         */
        const val DEFAULT_CALL_TIMEOUT_MS = 30_000L

        /** Node boot plus agent-stack load inside Alpine under proot on a phone. */
        const val DEFAULT_READY_TIMEOUT_MS = 120_000L
        const val TAG_WRAPPER = "wrapper"
    }
}
