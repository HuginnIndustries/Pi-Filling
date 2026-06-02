package industries.huginn.pifilling.wrapper

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    /** Suspends until the wrapper announces `wrapper_ready`, or the process dies. */
    suspend fun awaitReady(): ReadyEvent = readyDeferred.await()

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
        return deferred.await()
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

        // Backpressure-safe: tryEmit into the buffered flow; if a slow collector
        // has filled the buffer we drop the oldest rather than block the reader.
        if (!_events.tryEmit(WrapperEvent(type, data))) {
            Log.w(TAG, "event buffer full; dropped $type")
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
        const val TAG_WRAPPER = "wrapper"
    }
}
