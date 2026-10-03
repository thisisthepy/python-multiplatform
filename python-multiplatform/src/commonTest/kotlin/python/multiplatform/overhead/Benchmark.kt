package python.multiplatform.overhead

import kotlin.time.TimeSource
import kotlin.time.measureTime

object Benchmark {
    private val results = mutableListOf<BenchmarkResult>()

    data class BenchmarkResult(
        val name: String,
        val iterations: Int,
        val totalTimeNs: Long,
        val nsPerOp: Double
    )

    /**
     * Measures [block], appends the row to the report, and returns the ns/op it recorded -- so a
     * test that asserts a relation between two rows compares the figures it printed, not a second
     * measurement of the same thing.
     */
    fun run(
        name: String,
        warmupIterations: Int = 1000,
        iterations: Int = 100_000,
        block: () -> Unit
    ): Double {
        val nsPerOp = measure(warmupIterations, iterations, block)
        results.add(BenchmarkResult(name, iterations, (nsPerOp * iterations).toLong(), nsPerOp))
        return nsPerOp
    }

    /** Appends a row measured elsewhere -- inside Python, say -- so it prints with the rest. */
    fun record(name: String, iterations: Int, nsPerOp: Double) {
        results.add(BenchmarkResult(name, iterations, (nsPerOp * iterations).toLong(), nsPerOp))
    }

    /**
     * The same warmup-then-time loop [run] performs, returned instead of appended to the report.
     *
     * For a caller that needs the number *during* the test -- to compute a ratio against something
     * measured in the same run, which is the only kind of ratio worth having -- and that would
     * otherwise have to either re-implement the loop (so the two figures stop being comparable) or
     * append to a shared report it does not own and cannot print without clearing someone else's
     * rows. `UpcallOverheadTest` does exactly this.
     */
    fun measure(
        warmupIterations: Int = 1000,
        iterations: Int = 100_000,
        block: () -> Unit
    ): Double {
        // Warmup. Same shape and same block as the measured loop: an unwarmed first loop in this
        // repo once made a subset of the work look cheaper than the whole of it.
        for (i in 0 until warmupIterations) {
            block()
        }

        val time = measureTime {
            for (i in 0 until iterations) {
                block()
            }
        }

        return time.inWholeNanoseconds.toDouble() / iterations
    }

    fun printReport() {
        if (results.isEmpty()) return

        println("\n--- Benchmark Report ---")
        val nameWidth = results.maxOfOrNull { it.name.length }?.coerceAtLeast(30) ?: 30
        
        println("Operation".padEnd(nameWidth) + " | " + "Iterations".padStart(10) + " | " + "ns / op".padStart(15))
        println("-".repeat(nameWidth + 31))

        for (result in results) {
            val nsStr = result.nsPerOp.toString()
            val formattedNs = if (nsStr.contains(".")) {
                val parts = nsStr.split(".")
                parts[0] + "." + parts[1].take(2).padEnd(2, '0')
            } else {
                "$nsStr.00"
            }
            println(result.name.padEnd(nameWidth) + " | " + result.iterations.toString().padStart(10) + " | " + formattedNs.padStart(15))
        }
        println("-".repeat(nameWidth + 31))
        println()
        results.clear()
    }
}
