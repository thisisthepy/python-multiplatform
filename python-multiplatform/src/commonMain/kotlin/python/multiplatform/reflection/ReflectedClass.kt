package python.multiplatform.reflection


/**
 * Which Kotlin declaration a [ReflectedClass] describes.
 *
 * The Python side cannot infer this from [ReflectedClass.memberNames]: a class with a private
 * constructor and an interface both simply have no `<init>` entry, yet one may gain instances
 * from a factory and the other never has a Python-constructible form at all. Each value here
 * names a different proxy shape, so the decision is recorded at generation time rather than
 * guessed at import time.
 */
enum class ReflectedClassKind {
    /** An ordinary class. Constructible from Python if a `<init>` entry is present. */
    CLASS,

    /**
     * An interface. Never constructible; its entries exist so that a Kotlin object handed to
     * Python can be called through the interface even when its concrete class is not exposed.
     */
    INTERFACE,

    /** A Kotlin `object`. Exactly one instance, so every member is reached without a receiver. */
    OBJECT,

    /** An `enum class`. [ReflectedClass.enumEntryNames] lists its members. */
    ENUM,
}


/**
 * A build-time descriptor for one exposed Kotlin class: what to call it from Python, which
 * [UpcallTable] entries are its members, and how to walk the Python references it holds for
 * cycle collection.
 *
 * KSP emits one of these per exposed class alongside the [ExposedCallable] entries for its
 * constructor, methods and accessors -- see `docs/design/upcall-table-design.md`, and for why
 * [traverse] exists at all, `docs/design/object-lifetime.md`'s "Cycle collection is part of the
 * table's job".
 */
class ReflectedClass(
    /** The Python-visible qualified name, and the prefix every entry in [memberNames] shares. */
    val name: String,
    /** [UpcallTable]-resolvable names of this class's constructor, methods and accessors. */
    val memberNames: List<String>,
    traverse: ((Any, (Long) -> Unit) -> Unit)? = null,
    /** Which Kotlin shape this came from; see [ReflectedClassKind]. */
    val kind: ReflectedClassKind = ReflectedClassKind.CLASS,
    /**
     * For [ReflectedClassKind.ENUM], the entry names in declaration order -- everything a Python
     * `enum.Enum` mirror needs, without the boundary having to marshal a collection. Empty for
     * every other kind.
     *
     * Each name has a matching [CallableKind.STATIC_GETTER] entry `"$name.$entry"` that returns
     * the single instance.
     */
    val enumEntryNames: List<String> = emptyList(),
) {
    private val traverseImpl = traverse

    /**
     * Whether the proxy type needs a `tp_traverse` slot at all. A class with no `PyObject`-typed
     * fields cannot be part of a cycle through this object, so the generator leaves [traverse]
     * unset here rather than emitting an always-present no-op.
     */
    val hasTraverse: Boolean get() = traverseImpl != null

    /**
     * Visits the raw pointer of every live `PyObject`-typed field [obj] holds. CPython runs
     * `tp_traverse` during collection, which is why the generated [traverse] is restricted to
     * field reads and [visit] calls -- no allocation, no other user code. A no-op when
     * [hasTraverse] is false.
     */
    fun traverse(obj: Any, visit: (Long) -> Unit) {
        traverseImpl?.invoke(obj, visit)
    }

    override fun toString(): String = "ReflectedClass($kind $name, ${memberNames.size} members)"
}
