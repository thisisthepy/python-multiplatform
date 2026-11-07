package fixture.notebook

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * `Dispatchers.Main` for the notebook E2E's native image (issue #249): one dedicated thread, with no
 * AWT behind it.
 *
 * Compose's main-thread work (`GlobalSnapshotManager`, `postDelayed`) goes to skiko's `SwingDispatcher`
 * on upstream Compose, which starts AWT. The fork's Compose sends it to `Dispatchers.Main` instead when
 * `compose.main.dispatcher` is `coroutines` (`NotebookE2eMain` sets it), and this supplies that. In a native image AWT
 * needs the JDK's `libawt*.dylib` beside the executable, and those link `libjvm`, which the
 * milestone rules out. The scenes are offscreen, so nothing needs the event dispatch thread; what
 * Compose needs is a single thread its snapshot work is serialised on, which is what the EDT is in
 * the headless JVM `desktopTest` runs in.
 *
 * Registered only on the native image's classpath (`src/desktopTest/native-image-main/`), where its
 * [loadPriority] wins over Swing's; `desktopTest` keeps Swing, as before.
 */
@OptIn(InternalCoroutinesApi::class)
class NotebookE2eMainDispatcherFactory : MainDispatcherFactory {

    override val loadPriority: Int = Int.MAX_VALUE / 2

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = NotebookE2eMain

    override fun hintOnError(): String = "the notebook E2E's single-thread Main dispatcher"
}

private object NotebookE2eMain : MainCoroutineDispatcher() {

    private val thread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "notebook-e2e-main").apply { isDaemon = true }
    }

    override val immediate: MainCoroutineDispatcher get() = this

    override fun dispatch(context: CoroutineContext, block: Runnable) = thread.execute(block)

    override fun toString(): String = "NotebookE2eMain"
}
