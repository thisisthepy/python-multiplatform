package fixture.aot

import java.io.File
import kotlin.system.exitProcess

/**
 * The AOT library fixture as one executable (issue #456): the same 25 scenarios [AotLibraryCallTest] runs under
 * JUnit, called directly so they can run inside a GraalVM native image without JUnit or reflection.
 *
 * When the `aot.<prefix>.description` system properties are unset (the native binary started on its own without
 * Gradle), they resolve to `libs/<prefix>/<prefix>.json` beside the executable, where `aotNativeCompile` copies them.
 */
fun main() {
    besideTheExecutable("aot.nbody.description", "libs/nbody/nbody.json")
    besideTheExecutable("aot.errors.description", "libs/errors/errors.json")
    besideTheExecutable("aot.bigints.description", "libs/bigints/bigints.json")

    val scenarios: List<Pair<String, AotLibraryCallTest.() -> Unit>> = listOf(
        "nbodyEnergiesAndPositionsEqualCPythonBitForBit" to { nbodyEnergiesAndPositionsEqualCPythonBitForBit() },
        "otherStepCountsAlsoEqualCPython" to { otherStepCountsAlsoEqualCPython() },
        "aPythonExceptionArrivesAsTypedPythonAotExceptionWithCPythonsClassAndMessage" to {
            aPythonExceptionArrivesAsTypedPythonAotExceptionWithCPythonsClassAndMessage()
        },
        "aListIsCreatedReadWrittenAndConvertedBack" to { aListIsCreatedReadWrittenAndConvertedBack() },
        "indexesAreCheckedOnTheKotlinSide" to { indexesAreCheckedOnTheKotlinSide() },
        "useAfterCloseThrowsAndCloseIsIdempotent" to { useAfterCloseThrowsAndCloseIsIdempotent() },
        "aFunctionThatReturnsItsArgumentGivesAHandleToTheSameListAndClosingBothIsBalanced" to {
            aFunctionThatReturnsItsArgumentGivesAHandleToTheSameListAndClosingBothIsBalanced()
        },
        "theSameKotlinListForTwoParametersIsOneListInside" to { theSameKotlinListForTwoParametersIsOneListInside() },
        "boolAndTupleRoundTrips" to { boolAndTupleRoundTrips() },
        "anIntListIsPassedAndAnIntListIsReturned" to { anIntListIsPassedAndAnIntListIsReturned() },
        "twoThreadsRunTheirOwnSimulationsConcurrentlyAndGetTheSingleThreadedResults" to {
            twoThreadsRunTheirOwnSimulationsConcurrentlyAndGetTheSingleThreadedResults()
        },
        "anExceptionOnOneThreadDoesNotDisturbTheOther" to { anExceptionOnOneThreadDoesNotDisturbTheOther() },
        "anIntListReadsBackFactorialsUpTo20AndThen21FactorialIsAnOverflowError" to {
            anIntListReadsBackFactorialsUpTo20AndThen21FactorialIsAnOverflowError()
        },
        "storingASmallValueOverABigOneMakesItReadable" to { storingASmallValueOverABigOneMakesItReadable() },
        "aListTheLibraryReturnsHoldsBigValuesAndIsOwnedByTheCaller" to {
            aListTheLibraryReturnsHoldsBigValuesAndIsOwnedByTheCaller()
        },
        "totalOfSmallValuesEqualsTheKotlinSumAndTotalOfABigListIsAnIntResultOverflow" to {
            totalOfSmallValuesEqualsTheKotlinSumAndTotalOfABigListIsAnIntResultOverflow()
        },
        "anIndexOutOfRangeIsTheLibrarysIndexErrorWithPythonsMessagesAndNegativeIndexesCountFromTheEnd" to {
            anIndexOutOfRangeIsTheLibrarysIndexErrorWithPythonsMessagesAndNegativeIndexesCountFromTheEnd()
        },
        "anIntListIsCreatedFromAndConvertedToALongArray" to { anIntListIsCreatedFromAndConvertedToALongArray() },
        "anIntListIsClosedOnceAndUseAfterCloseIsAnIllegalStateException" to {
            anIntListIsClosedOnceAndUseAfterCloseIsAnIllegalStateException()
        },
        "aRawListOfTheSameLibraryStillUsesI64ListWithDirectMemoryAccess" to {
            aRawListOfTheSameLibraryStillUsesI64ListWithDirectMemoryAccess()
        },
        "concurrentReadsOfOneIntListAgree" to { concurrentReadsOfOneIntListAgree() },
        "oneIntListWrittenByOneThreadAndReadByAnotherStaysMemorySafe" to {
            oneIntListWrittenByOneThreadAndReadByAnotherStaysMemorySafe()
        },
        "noClassOfTheCPythonEmbeddingLibraryIsOnTheClasspath" to { noClassOfTheCPythonEmbeddingLibraryIsOnTheClasspath() },
        "noLibpythonImageIsLoadedInTheProcessLinuxProcMapsMacosVmmapOrLsof" to {
            noLibpythonImageIsLoadedInTheProcessLinuxProcMapsMacosVmmapOrLsof()
        },
        "everyBoundMethodHandleDescriptorIsDeclaredInTheGeneratedReachabilityMetadata" to {
            everyBoundMethodHandleDescriptorIsDeclaredInTheGeneratedReachabilityMetadata()
        },
    )

    var failed = 0
    for ((name, scenario) in scenarios) {
        try {
            val test = AotLibraryCallTest()
            test.load()
            test.loadBigints()
            test.scenario()
            println("PASS $name")
        } catch (t: Throwable) {
            failed++
            println("FAIL $name: $t")
            t.printStackTrace(System.out)
        }
    }
    println("AOT_NATIVE_IMAGE: ${scenarios.size - failed} passed, $failed failed of ${scenarios.size}")
    System.out.flush()
    exitProcess(if (failed == 0) 0 else 1)
}

private fun besideTheExecutable(property: String, relativePath: String) {
    if (System.getProperty(property) != null) return
    val executable = ProcessHandle.current().info().command().orElse(null) ?: return
    System.setProperty(property, File(File(executable).absoluteFile.parentFile, relativePath).path)
}
