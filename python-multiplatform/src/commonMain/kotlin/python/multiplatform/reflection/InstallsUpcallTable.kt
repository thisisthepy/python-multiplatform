package python.multiplatform.reflection

/**
 * Marks the `expect fun` that shared code calls to install the generated function table.
 *
 * ### The problem this exists for
 *
 * KSP writes `python.multiplatform.generated.FunctionTable` into the *compilation* that generated
 * it -- `build/generated/ksp/iosSimulatorArm64/iosSimulatorArm64Main/kotlin` and one sibling per
 * target. An **intermediate** source set (`commonMain`, `iosMain`, `androidNativeMain`) is one the
 * generating leaves depend on, not the other way round, so it cannot name that object at all:
 *
 *     e: TableInstall.kt: Unresolved reference 'FunctionTable'.
 *
 * observed on `python-multiplatform-ksp/fixtures/app`'s `commonMain`. ROADMAP §13 recorded the consequence: every
 * consumer wrote the same one-line `actual` once per leaf target, and shared code that wanted the
 * table had nothing to call. `androidMain` is the exception that proves the rule -- it *is* the
 * Android target's own source set, so it can name `FunctionTable` -- and an exception per platform
 * is exactly what shared code cannot be written against.
 *
 * ### What to write
 *
 * One `expect` declaration in `commonMain`, and nothing else. The processor emits the matching
 * `actual` into every leaf compilation it generates a `FunctionTable` for:
 *
 *     // commonMain
 *     @InstallsUpcallTable
 *     expect fun installGeneratedUpcallTable()
 *
 *     // anywhere at all, commonMain and intermediate source sets included
 *     installGeneratedUpcallTable()
 *
 * `expect`/`actual` rather than an interface plus a runtime registry because the reference chain
 * has to stay *static*: `docs/design/upcall.md` keeps every fragment reachable to the
 * Kotlin/Native linker by referencing it explicitly, with no `ServiceLoader` and no
 * `@EagerInitialization`. A generated object that nothing names is dead code the linker is free to
 * drop. The generated `actual` is that name, and it is generated rather than written by hand
 * because the leaf source set is the one place the author of shared code cannot put it.
 *
 * ### Rules
 *
 * - The annotated declaration must be a top-level `expect fun`, taking no parameters and
 *   returning `Unit`. `public` or `internal`; the generated `actual` matches.
 * - Only an **app-role** module can satisfy it -- a `library`-role module emits a fragment and no
 *   aggregator, so there is no `FunctionTable` for the `actual` to call. The processor reports
 *   that as an error rather than leaving an `expect` unimplemented.
 * - More than one is allowed, and each gets its own `actual`.
 *
 * Read at build time by `python-multiplatform-ksp` only; it has no effect at runtime.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class InstallsUpcallTable
