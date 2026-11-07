package fixture.notebook

import java.io.File
import kotlin.system.exitProcess

/**
 * The notebook E2E as one executable (issue #249, #191's first milestone): the same 20 scenarios the
 * `desktopTest` suite runs, called directly, so they can run inside a GraalVM native image, where
 * there is no JUnit and no reflection to find a test with.
 *
 * Each scenario is its class's `@BeforeTest` (`host()`) and then the test method, exactly what JUnit
 * does for these classes (none has an `@AfterTest`). A scenario passes when it returns and fails when
 * it throws; the summary line is what `notebookE2eNativeRun` checks, and the exit status is non-zero
 * on any failure.
 *
 * The wheel's `site-packages` and the application directory are the system properties the suite
 * reads (`NotebookHost`); when they are not set -- a native image started by hand -- they are
 * `site-packages/` and `app/` beside the executable, where the build stages them.
 */
fun main() {
    // The fork's switch (compose-multiplatform-core-extended `ExtendedMainDispatcher`): Compose's
    // main-thread work goes to `Dispatchers.Main`, which `NotebookE2eMainDispatcherFactory` supplies
    // with no AWT, instead of skiko's Swing event queue. Read once, before Compose starts; upstream
    // Compose ignores it.
    System.setProperty("compose.main.dispatcher", "coroutines")
    besideTheExecutable("notebookE2e.sitePackages", "site-packages")
    besideTheExecutable("notebookE2e.appDir", "app")

    val scenarios: List<Pair<String, () -> Unit>> = listOf(
        "cell05_theImportsResolveFromTheInstalledWheel" to { NotebookRootTest().run { host(); cell05_theImportsResolveFromTheInstalledWheel() } },
        "cell06to07_importingMainDrawsTheAppItDeclares" to { NotebookRootTest().run { host(); cell06to07_importingMainDrawsTheAppItDeclares() } },
        "cell09to13_theNotebookAndTheScreenShareOneState" to { NotebookRootTest().run { host(); cell09to13_theNotebookAndTheScreenShareOneState() } },
        "cell15to16_redeclaringTheRootChangesTheScreenWithNoUpdateCall" to { NotebookRootTest().run { host(); cell15to16_redeclaringTheRootChangesTheScreenWithNoUpdateCall() } },
        "negative_withAppStubbedOutTheRedeclarationDoesNotReachTheScreen" to { NotebookRootTest().run { host(); negative_withAppStubbedOutTheRedeclarationDoesNotReachTheScreen() } },
        "negative_aHostHandedTheRootOnceDoesNotFollowARedeclaration" to { NotebookRootTest().run { host(); negative_aHostHandedTheRootOnceDoesNotFollowARedeclaration() } },
        "cell19_text" to { NotebookPracticeTest().run { host(); cell19_text() } },
        "cell20_textWithAFontSize" to { NotebookPracticeTest().run { host(); cell20_textWithAFontSize() } },
        "cell22_anEmptyBlackButton" to { NotebookPracticeTest().run { host(); cell22_anEmptyBlackButton() } },
        "cell23_aButtonHoldingText" to { NotebookPracticeTest().run { host(); cell23_aButtonHoldingText() } },
        "cell25_aCard" to { NotebookPracticeTest().run { host(); cell25_aCard() } },
        "cell28_defaultIconsAddIsAnImageVector" to { NotebookPracticeTest().run { host(); cell28_defaultIconsAddIsAnImageVector() } },
        "cell29_anIcon" to { NotebookPracticeTest().run { host(); cell29_anIcon() } },
        "cell30_anIconInsideAButton" to { NotebookPracticeTest().run { host(); cell30_anIconInsideAButton() } },
        "cell35_aColumnOfTwoButtons" to { NotebookPracticeTest().run { host(); cell35_aColumnOfTwoButtons() } },
        "cell36_aColumnAlignedToTheEnd" to { NotebookPracticeTest().run { host(); cell36_aColumnAlignedToTheEnd() } },
        "cell37_aRowWithAnArrangement" to { NotebookPracticeTest().run { host(); cell37_aRowWithAnArrangement() } },
        "cell38_aRow" to { NotebookPracticeTest().run { host(); cell38_aRow() } },
        "cell40_aCardSpacedBySpacers" to { NotebookPracticeTest().run { host(); cell40_aCardSpacedBySpacers() } },
        "cell32to33_aTextFieldKeepsInputMethodCompositionInComposeAndTheNotebookReadsTheResult" to {
            NotebookTextFieldTest().run { host(); cell32to33_aTextFieldKeepsInputMethodCompositionInComposeAndTheNotebookReadsTheResult() }
        },
    )

    var failed = 0
    for ((name, scenario) in scenarios) {
        try {
            scenario()
            println("PASS $name")
        } catch (t: Throwable) {
            failed++
            println("FAIL $name: $t")
            t.printStackTrace(System.out)
        }
    }
    println("NOTEBOOK_E2E: ${scenarios.size - failed} passed, $failed failed of ${scenarios.size}")
    System.out.flush()
    exitProcess(if (failed == 0) 0 else 1)
}

private fun besideTheExecutable(property: String, directory: String) {
    if (System.getProperty(property) != null) return
    val executable = ProcessHandle.current().info().command().orElse(null) ?: return
    System.setProperty(property, File(File(executable).absoluteFile.parentFile, directory).path)
}
