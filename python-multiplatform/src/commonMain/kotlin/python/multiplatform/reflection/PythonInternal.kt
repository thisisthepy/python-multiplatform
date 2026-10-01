package python.multiplatform.reflection

/**
 * Opt-out of the upcall table. `docs/design/binding-policy.md` settles the exposure model as a
 * blacklist -- every `public` declaration is exposed to Python by default -- so the only knob a
 * library author needs is a way to say "not this one".
 *
 * Applies to a top-level function, a class (excluding the whole class and its members), or a
 * member function/property. The KSP fragment generator (`docs/design/upcall-table-design.md`) is the
 * only reader of this annotation; it has no effect at runtime.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class PythonInternal
