package fixture.app

/** The app module's own exposed surface -- round 1 of `AppProcessor` must emit a fragment for
 * this before round 2 can discover it alongside the library's. */
fun double(x: Long): Long = x * 2
