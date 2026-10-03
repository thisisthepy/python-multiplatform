package fixture.artifact

/**
 * One declaration of this module's own, so the end-to-end test can show both producers answering in
 * the same Python interpreter: this one reaches Python through KSP's `FunctionTable`, and
 * `junit.runner.Version.id` reaches it through the walker's `ArtifactTable`.
 */
fun whichSideAmIFrom(): String = "ksp"
