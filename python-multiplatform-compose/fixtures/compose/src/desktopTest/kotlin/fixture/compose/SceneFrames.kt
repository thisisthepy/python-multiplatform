package fixture.compose

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene

/**
 * Renders the frame that a state write made just before this call must produce (#157).
 *
 * `Snapshot.sendApplyNotifications()` followed by `render()` is not enough on its own. Compose
 * desktop's global snapshot manager applies notifications on a thread of its own as soon as a write
 * is observed, so the call here can find nothing left to apply while the recomposer's wake-up from
 * that other apply has not run yet; `render()` then sees no invalidation and the frame is lost. So
 * this waits, bounded, until the scene reports a pending invalidation, then renders. Use it only
 * after a write that must recompose: an idle frame never becomes invalid and would wait out the
 * timeout.
 */
internal fun ImageComposeScene.renderAfterWrite(timeoutMillis: Long = 5_000) {
    Snapshot.sendApplyNotifications()
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (!hasInvalidations() && System.nanoTime() < deadline) {
        Thread.sleep(1)
    }
    render()
}
