package fixture.artifact

/**
 * One declaration of this module's own, so the end-to-end test can show both producers answering in
 * the same Python interpreter: this one reaches Python through KSP's `FunctionTable`, and
 * `kotlinx.coroutines.flow.internal.checkIndexOverflow` reaches it through the walker's
 * `ArtifactTable`.
 *
 * **Kept, not deleted with the rest of the copied-from-`:artifact` fixture.** Everything else this
 * module inherited from `:python-multiplatform-gradle-plugin-fixtures:artifact` was a *jar*-path duplicate and went, but this file
 * is not about jars: `pythonBindings.role.set("app")` makes this module the aggregator, and an
 * aggregator with no source declarations of its own emits an **empty** `FunctionTable`. That
 * compiles and passes, and it quietly stops the fixture from demonstrating the one property
 * `docs/design/ecosystem.md` §5b actually rests on -- that KSP's producer and the artefact walker's land in
 * one `UpcallTable` under one Python namespace. `WalkedKlibArtifactPythonImportTest
 * .kspAndTheKlibWalkerShareOnePythonNamespace` is that demonstration, and it needs this to exist.
 */
fun whichSideAmIFrom(): String = "ksp"
