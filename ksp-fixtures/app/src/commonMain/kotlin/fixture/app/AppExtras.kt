package fixture.app

/**
 * A second source file in the app module.
 *
 * One module emits one fragment however many files it has, so this proves the scan is per-module
 * rather than per-file -- and it is what makes the incremental measurement in
 * `docs/design/upcall.md` §2.7 meaningful: with a single-file module every dirty-set
 * measurement is trivially 100%.
 */
fun triple(x: Long): Long = x * 3
