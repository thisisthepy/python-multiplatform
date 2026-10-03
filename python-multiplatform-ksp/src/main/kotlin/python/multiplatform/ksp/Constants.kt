package python.multiplatform.ksp

/**
 * The well-known package fragments are emitted into and the aggregator scans.
 * `docs/design/upcall.md` §2.1/§2 -- not load-bearing for discovery (the aggregator scans
 * the whole package), but fixed so generated code from different modules never collides.
 */
const val FRAGMENTS_PACKAGE = "python.multiplatform.generated.fragments"

/** Where the app-role aggregator emits `FunctionTable`. */
const val AGGREGATOR_PACKAGE = "python.multiplatform.generated"

const val FRAGMENT_PREFIX = "Fragment_"

/**
 * `docs/design/binding-policy.md`: the library's own packages are excluded wholesale so the ~600
 * internal FFI/object-model declarations never end up in a user's table. Every consumer's
 * `excludePackages` option is added on top of this, not instead of it.
 */
val ALWAYS_EXCLUDED_PACKAGE_PREFIXES = listOf(
    "python.native.ffi",
    "python.multiplatform.ffi",
    "python.multiplatform.reflection",
    "python.multiplatform.ref",
    // generated code must never be re-scanned as if it were user source, or a library that is
    // itself processed in a later round would emit a fragment of its own fragment.
    FRAGMENTS_PACKAGE,
    AGGREGATOR_PACKAGE,
)

const val OPTION_ROLE = "python.multiplatform.role"
const val OPTION_MODULE_NAME = "python.multiplatform.moduleName"
const val OPTION_EXCLUDE_PACKAGES = "python.multiplatform.excludePackages"

const val ROLE_LIBRARY = "library"
const val ROLE_APP = "app"

/** [docs/design/upcall.md] §11.4: an entry point Python has no use for and cannot call. */
fun isMainFunction(name: String, hasNoParamsOrArgsArray: Boolean): Boolean =
    name == "main" && hasNoParamsOrArgsArray

/** Fragment object name for a given (already sanitised) module name. */
fun fragmentObjectName(moduleName: String): String = "$FRAGMENT_PREFIX$moduleName"

/** Periods and hyphens collide with Kotlin identifier syntax; `docs/design/upcall.md` §2.1. */
fun sanitiseModuleName(raw: String): String = raw.replace('.', '_').replace('-', '_')
