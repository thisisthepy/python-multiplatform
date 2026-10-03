package python.multiplatform.reflection

import python.multiplatform.overhead.Benchmark
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

private const val TABLE_ENTRIES = 200

/**
 * What the upcall path costs, and specifically the comparison the design rests on: a name
 * resolved on every call against a handle resolved once and cached.
 *
 * `docs/design/upcall.md` puts per-call name passing at hundreds of nanoseconds and a cached
 * handle at 10--20 ns. **These numbers are a lower bound on the gap, not the gap.** Everything
 * measured here is inside Kotlin; the expensive part of the name path is what happens before
 * it -- `PyUnicode` to UTF-8 (an allocation) and the FFI boundary crossing, both measured
 * separately in `BenchmarkTest`. Read this as "even with the boundary removed, name resolution
 * still costs more than a table index, and the argument array costs more than either".
 *
 * Nothing here asserts a timing bound. Wall-clock assertions on a shared build machine fail
 * for reasons that have nothing to do with this code; the numbers are printed and the
 * structural claims (a stable handle, an identical result down both paths) are what is checked.
 */
class UpcallOverheadTest {

    private val bulk = BulkFragment(TABLE_ENTRIES)

    // A name from the middle of the table, at realistic qualified-name length: hashing and
    // comparison both scale with it, and a two-character key would flatter the name path.
    private val hotName = "test.bulk.module137.function137"

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(bulk, TestLibraryFragment, TestAppFragment))
    }

    @AfterTest
    fun report() {
        Benchmark.printReport()
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    @Test
    fun testNameResolutionVersusCachedHandle() {
        val handle = UpcallTable.resolve(hotName)
        assertTrue(handle.isValid)
        assertEquals(
            UpcallTable.invoke(handle, arrayOf(1L)),
            UpcallTable.invokeByName(hotName, arrayOf(1L)),
            "the two paths must agree before their costs are compared",
        )

        Benchmark.run("UpcallTable.resolve(name) [$TABLE_ENTRIES entries]", iterations = 1_000_000) {
            UpcallTable.resolve(hotName)
        }
        Benchmark.run("UpcallTable.invoke(handle, args)", iterations = 1_000_000) {
            UpcallTable.invoke(handle, arrayOf(1L))
        }
        Benchmark.run("UpcallTable.invokeByName(name, args)", iterations = 1_000_000) {
            UpcallTable.invokeByName(hotName, arrayOf(1L))
        }

        // Benchmark keeps its results private, so the comparison is timed here as well.
        val byName = nsPerOp(1_000_000) { UpcallTable.invokeByName(hotName, arrayOf(1L)) }
        val cached = nsPerOp(1_000_000) { UpcallTable.invoke(handle, arrayOf(1L)) }
        println(
            "  name resolved per call : ${format(byName)} ns/op\n" +
                "  handle cached          : ${format(cached)} ns/op\n" +
                "  ratio                  : ${format(byName / cached)}x  " +
                "(in-process only; the PyUnicode -> UTF-8 conversion and the FFI crossing " +
                "are on top of the first number and not the second)"
        )
    }

    @Test
    fun testArgumentArrayCost() {
        // The table entry is a lambda over Array<Any?>, so every call allocates an array and
        // boxes every primitive. At the scale the cached path is trying to hit, this is the
        // dominant term -- worth seeing next to the dispatch it is paying for.
        val handle = UpcallTable.resolve(hotName)
        val reusable = arrayOf<Any?>(1L)

        Benchmark.run("arrayOf(1L) allocation + boxing", iterations = 1_000_000) {
            arrayOf<Any?>(1L)
        }
        Benchmark.run("invoke with a pre-built args array", iterations = 1_000_000) {
            UpcallTable.invoke(handle, reusable)
        }
        Benchmark.run("direct Kotlin call (floor)", iterations = 1_000_000) {
            directAdd(1L)
        }
    }

    @Test
    fun testHandleTableCost() {
        val ref = HandleTable.register(Counter(0))

        Benchmark.run("HandleTable.resolve(handle)", iterations = 1_000_000) {
            HandleTable.resolve(ref)
        }
        Benchmark.run("HandleTable.register + release", iterations = 500_000) {
            HandleTable.release(HandleTable.register(counterInstance))
        }

        HandleTable.release(ref)
        assertEquals(0, HandleTable.liveCount, "the measurement itself must not leak roots")
    }

    @Test
    fun testTableSizeDoesNotChangeTheCachedPath() {
        // The claim that lookup is an array index: growing the table by an order of magnitude
        // must not move the cached number, only the name-resolution one.
        val small = UpcallTable.resolve(hotName)
        val smallCached = nsPerOp(500_000) { UpcallTable.invoke(small, arrayOf(1L)) }

        UpcallTable.install(listOf(BulkFragment(TABLE_ENTRIES * 10)))
        val largeName = "test.bulk.module1370.function1370"
        val large = UpcallTable.resolve(largeName)
        assertTrue(large.isValid)
        val largeCached = nsPerOp(500_000) { UpcallTable.invoke(large, arrayOf(1L)) }
        val largeByName = nsPerOp(500_000) { UpcallTable.resolve(largeName) }

        println(
            "  cached invoke @ $TABLE_ENTRIES entries    : ${format(smallCached)} ns/op\n" +
                "  cached invoke @ ${TABLE_ENTRIES * 10} entries   : ${format(largeCached)} ns/op\n" +
                "  name resolve  @ ${TABLE_ENTRIES * 10} entries   : ${format(largeByName)} ns/op"
        )
    }

    private val counterInstance = Counter(0)

    private fun directAdd(x: Long): Long = x + 137

    private fun nsPerOp(iterations: Int, block: () -> Unit): Double {
        repeat(1_000) { block() }
        val elapsed = measureTime { repeat(iterations) { block() } }
        return elapsed.inWholeNanoseconds.toDouble() / iterations
    }

    private fun format(value: Double): String {
        val scaled = (value * 100).toLong()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }
}
