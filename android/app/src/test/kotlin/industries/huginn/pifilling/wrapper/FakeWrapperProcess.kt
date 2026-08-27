package industries.huginn.pifilling.wrapper

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An in-memory stand-in for the wrapper process, so [WrapperClient] can be
 * driven on the JVM with no device, no proot and no Node.
 *
 * The client only ever touches `java.lang.Process`, which is why this works:
 * stdout and stderr are pipes the test writes into, stdin is captured for
 * assertions, and [exitWith] releases `waitFor` so the client's process-death
 * path runs on demand.
 */
class FakeWrapperProcess : Process() {

    private val stdoutSink = PipedOutputStream()
    private val stdoutSource = PipedInputStream(stdoutSink, 64 * 1024)

    private val stderrSink = PipedOutputStream()
    private val stderrSource = PipedInputStream(stderrSink, 8 * 1024)

    private val stdinCapture = ByteArrayOutputStream()

    private val exited = CountDownLatch(1)

    @Volatile
    private var code: Int = -1

    /** Everything the client has written to the wrapper's stdin, as text. */
    val written: String get() = stdinCapture.toString(Charsets.UTF_8.name())

    /** Push one protocol line to the client, as the wrapper would. */
    fun emit(line: String) {
        stdoutSink.write((line + "\n").toByteArray())
        stdoutSink.flush()
    }

    fun emitStderr(line: String) {
        stderrSink.write((line + "\n").toByteArray())
        stderrSink.flush()
    }

    /** Terminate with [exitCode], releasing anything blocked in [waitFor]. */
    fun exitWith(exitCode: Int) {
        if (exited.count == 0L) return
        code = exitCode
        runCatching { stdoutSink.close() }
        runCatching { stderrSink.close() }
        exited.countDown()
    }

    override fun getOutputStream(): OutputStream = stdinCapture
    override fun getInputStream(): InputStream = stdoutSource
    override fun getErrorStream(): InputStream = stderrSource

    override fun waitFor(): Int {
        exited.await()
        return code
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)

    override fun exitValue(): Int {
        if (exited.count > 0L) throw IllegalThreadStateException("still running")
        return code
    }

    override fun destroy() = exitWith(143)

    override fun isAlive(): Boolean = exited.count > 0L
}
