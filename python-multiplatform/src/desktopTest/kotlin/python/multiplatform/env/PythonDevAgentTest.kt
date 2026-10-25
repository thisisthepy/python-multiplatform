package python.multiplatform.env

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SPEC L-12 (#233): the runtime loads the embedded dev agent ([DEV_AGENT_SOURCE], generated from
 * `src/commonMain/python/python_multiplatform_devagent.py`) as a module of this interpreter, and it answers
 * over a real loopback socket. The protocol itself is `src/test/python/test_devagent.py`'s.
 */
class PythonDevAgentTest {
    /** As long as the agent's minimum (`secrets.token_urlsafe(32)`). */
    private val token = "a".repeat(22) + "b".repeat(21)

    @Test
    fun `the embedded agent runs in this interpreter and answers over loopback`() =
        PythonTestFixture.withInterpreter {
            PythonDevAgent.load()
            val overlay = Files.createTempDirectory("pmp-devagent").toFile()
            Python3.exec(
                "__import__('sys').modules['__main__'].__dict__['_pmp_devagent_test'] = " +
                    "__import__('${PythonDevAgent.MODULE}').start(0, '$token', '${overlay.absolutePath}')",
            )
            val port = PythonTestFixture.eval("_pmp_devagent_test.port").toString().toInt()
            try {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 10_000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val out = socket.getOutputStream()
                    fun send(json: String) = out.write("$json\n".toByteArray(Charsets.UTF_8)).also { out.flush() }

                    send("""{"id": 1, "op": "hello", "token": "$token"}""")
                    val hello = reader.readLine()
                    assertTrue("\"type\": \"hello\"" in hello && "\"protocol\": 1" in hello, hello)

                    send("""{"id": 2, "op": "execute", "code": "import sys\nprint(sys.version_info[0])\n21 * 2"}""")
                    val replies = generateSequence { reader.readLine() }.takeWhile { it != null }
                        .takeWhileInclusive { "\"type\": \"done\"" !in it }.toList()
                    // print() writes the text and its newline as two writes, so as two stream messages.
                    assertTrue(replies.any { "\"name\": \"stdout\", \"text\": \"3\"" in it }, "$replies")
                    assertTrue(replies.any { "\"text/plain\": \"42\"" in it }, "$replies")
                    assertTrue("\"status\": \"ok\"" in replies.last(), "$replies")
                }
            } finally {
                Python3.exec("_pmp_devagent_test.stop()")
            }
        }

    /**
     * The hook in `Python3.initialize()`: a debug build (its payload carries [PythonDevAgent.ENABLE_MARKER])
     * started with [PythonDevAgent.ENVIRONMENT_VARIABLE] has an agent on that port with that token; one started
     * without it has nothing listening. Each runs in its own JVM ([DevAgentProbe]), since an environment
     * variable cannot be set in this one.
     */
    @Test
    fun `a process the launcher asked for an agent answers on the given port, and one it did not, does not`() {
        val requested = launchProbe(request = true, debugBuild = true)
        try {
            requested.awaitLine("python-multiplatform dev agent: listening on 127.0.0.1:${requested.port}")
            Socket("127.0.0.1", requested.port).use { socket ->
                socket.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val out = socket.getOutputStream()
                out.write("{\"id\": 1, \"op\": \"hello\", \"token\": \"$token\"}\n".toByteArray())
                out.write("{\"id\": 2, \"op\": \"execute\", \"code\": \"6 * 7\"}\n".toByteArray())
                out.flush()
                assertTrue("\"type\": \"hello\"" in reader.readLine())
                assertTrue("\"text/plain\": \"42\"" in reader.readLine())
            }
        } finally {
            requested.process.destroyForcibly()
        }

        val notRequested = launchProbe(request = false, debugBuild = true)
        try {
            notRequested.awaitLine(DevAgentProbe.READY)
            assertFailsWith<ConnectException> { Socket("127.0.0.1", notRequested.port).close() }
        } finally {
            notRequested.process.destroyForcibly()
        }
    }

    /**
     * A release build (its payload has no [PythonDevAgent.ENABLE_MARKER]) opens no port even when its process
     * is started with the variable: "a release launch never sets it" is the launcher's promise, not this
     * runtime's guarantee, so the runtime does not rely on it.
     */
    @Test
    fun `a release build opens no port even when the variable is set`() {
        val release = launchProbe(request = true, debugBuild = false)
        try {
            release.awaitLine(DevAgentProbe.READY)
            assertFailsWith<ConnectException> { Socket("127.0.0.1", release.port).close() }
            assertTrue(release.log.readLines().none { "listening" in it }, release.log.readText())
            assertTrue(release.log.readText().contains(PythonDevAgent.ENABLE_MARKER), release.log.readText())
        } finally {
            release.process.destroyForcibly()
        }
    }

    /** A token shorter than the agent's minimum is refused before anything listens. */
    @Test
    fun `a debug build refuses a short token and opens no port`() {
        val short = launchProbe(request = true, debugBuild = true, token = "short-token")
        try {
            short.awaitLine(DevAgentProbe.READY)
            assertFailsWith<ConnectException> { Socket("127.0.0.1", short.port).close() }
            assertTrue(short.log.readText().contains("refusing to start"), short.log.readText())
        } finally {
            short.process.destroyForcibly()
        }
    }

    private class Probe(val process: Process, val port: Int, val log: File) {
        fun awaitLine(line: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (System.nanoTime() < deadline) {
                if (log.exists() && log.readLines().any { it.trim() == line }) return
                if (!process.isAlive) break
                Thread.sleep(100)
            }
            throw AssertionError("never saw '$line':\n${if (log.exists()) log.readText() else "(no output)"}")
        }
    }

    /**
     * [debugBuild] stages a payload as toolchain does for a debug variant: a `python/` root on the class path
     * with a module and the marker. A release variant's payload is the same without the marker.
     */
    private fun launchProbe(request: Boolean, debugBuild: Boolean, token: String = this.token): Probe {
        val port = ServerSocket(0).use { it.localPort }
        val workDir = Files.createTempDirectory("pmp-devagent-probe").toFile()
        val log = File(workDir, "probe.log")
        val payload = File(workDir, "classes/${PythonPayload.PAYLOAD_ROOT}").apply { mkdirs() }
        File(payload, "devagent_probe_app.py").writeText("VALUE = 1\n")
        if (debugBuild) File(payload, PythonDevAgent.ENABLE_MARKER).writeText("")
        val command = listOf(
            File(System.getProperty("java.home"), "bin/java").path,
            "--enable-preview",
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            File(workDir, "classes").path + File.pathSeparator + System.getProperty("java.class.path"),
            DevAgentProbe::class.java.name,
        )
        val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true).redirectOutput(log)
        builder.environment().remove(PythonDevAgent.ENVIRONMENT_VARIABLE)
        if (request) builder.environment()[PythonDevAgent.ENVIRONMENT_VARIABLE] = "port=$port;token=$token"
        return Probe(builder.start(), port, log)
    }

    private fun <T> Sequence<T>.takeWhileInclusive(predicate: (T) -> Boolean): Sequence<T> = sequence {
        for (item in this@takeWhileInclusive) {
            yield(item)
            if (!predicate(item)) break
        }
    }
}

/** A JVM that starts Python and stays up, for [PythonDevAgentTest]'s launch test. */
object DevAgentProbe {
    const val READY = "DEV_AGENT_PROBE_READY"

    @JvmStatic
    fun main(args: Array<String>) {
        Python3.initialize(silent = true)
        println(READY)
        System.out.flush()
        Thread.sleep(120_000)
        exitProcess(0)
    }
}
