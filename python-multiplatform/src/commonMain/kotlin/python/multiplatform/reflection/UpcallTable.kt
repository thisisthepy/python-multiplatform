package python.multiplatform.reflection


/**
 * What a KSP-generated fragment implements: one object per module, holding the calls and
 * classes that module exposes.
 *
 * `docs/design/upcall-table-design.md` names the generated objects `Fragment_<module>`; this interface
 * is the shape they are emitted into and the shape [UpcallTable] registers. `moduleName` is what
 * makes [UpcallTable.register] idempotent -- an aggregator that references a fragment already
 * installed manually must not double its entries.
 */
interface FunctionTableFragment {
    /** Sanitised module name, the same value the generator was passed as a KSP option. */
    val moduleName: String

    fun entries(): List<ExposedCallable>

    /** Classes this fragment exposes. Most fragments -- anything without a class -- expose none. */
    fun classes(): List<ReflectedClass> = emptyList()
}


/**
 * The runtime half of the build-time function table (ROADMAP §7, `docs/design/upcall-design.md`).
 *
 * A name is resolved to a [CallableHandle] once; every call after that carries only the handle --
 * a table index plus an epoch that rejects handles from a table that has since been reinstalled.
 * `docs/design/upcall-design.md` takes this from ObjC: the win is not having a table, it is never
 * hashing and comparing a string more than once per name.
 *
 * [ClassLookup] is the companion registry for the [ReflectedClass] half of a fragment; it is
 * cleared and populated in lockstep with this table rather than merged into it, because class
 * lookup (`type()`, `isinstance()`) is a distinct Python-visible operation from callable
 * resolution even though both come from the same fragments.
 *
 * ### Threading
 * Not synchronised, for the same reason as [HandleTable]: installation happens once at start-up,
 * or under the GIL for an upcall-driven [register], never concurrently with a lookup.
 */
object UpcallTable {

    /** Bumped on every [clear]. A handle's epoch must match this or it is treated as unresolved. */
    private var epoch = 0

    private val callables = ArrayList<ExposedCallable>()
    private val nameToIndex = HashMap<String, Int>()
    private val installedModules = LinkedHashSet<String>()

    val callableCount: Int get() = callables.size
    val classCount: Int get() = ClassLookup.count
    val moduleNames: Set<String> get() = installedModules

    /** Drops every registration and invalidates every handle issued so far. */
    fun clear() {
        epoch++
        callables.clear()
        nameToIndex.clear()
        installedModules.clear()
        ClassLookup.clear()
    }

    /**
     * What the generated aggregator calls: replace the table wholesale with [fragments].
     *
     * Equivalent to [clear] followed by [register] on each fragment. Reinstalling deliberately
     * invalidates handles resolved against the old table rather than trying to preserve them --
     * continuing to trust a handle across a reinstall is the failure the epoch exists to catch.
     */
    fun install(fragments: List<FunctionTableFragment>) {
        clear()
        fragments.forEach { register(it) }
    }

    /**
     * Adds one fragment's entries and classes.
     *
     * A fragment already registered (by [FunctionTableFragment.moduleName]) is a no-op, so a
     * fragment swept up by both a manual call and an aggregator does not get counted twice. Name
     * collisions against entries from a *different* fragment are validated before anything is
     * mutated, so a failed registration leaves the table exactly as it was.
     */
    fun register(fragment: FunctionTableFragment) {
        if (fragment.moduleName in installedModules) return

        val entries = fragment.entries()
        for (entry in entries) {
            if (entry.name in nameToIndex) {
                error("duplicate exposed name '${entry.name}': already registered by another fragment")
            }
        }

        for (entry in entries) {
            nameToIndex[entry.name] = callables.size
            callables.add(entry)
        }
        fragment.classes().forEach(ClassLookup::register)
        installedModules.add(fragment.moduleName)
    }

    /**
     * Every entry currently installed, in registration order.
     *
     * For generators that need to describe the whole table rather than resolve one name --
     * [python.multiplatform.ffi.upcall.PythonProxySource] is the one that does. A copy, because a
     * caller iterating the live list while an upcall registers into it would be reading a table
     * mid-mutation; this path is not hot enough for that to matter.
     */
    fun entries(): List<ExposedCallable> = callables.toList()

    /** Resolves [name] to a handle, or [CallableHandle.NONE] if nothing claims it. */
    fun resolve(name: String): CallableHandle {
        val index = nameToIndex[name] ?: return CallableHandle.NONE
        return CallableHandle.encode(index, epoch)
    }

    /** The entry [handle] resolves to. Throws if [handle] is [CallableHandle.NONE], unknown, or stale. */
    fun callable(handle: CallableHandle): ExposedCallable =
        resolveEntry(handle) ?: throw IllegalArgumentException("invalid or stale callable handle: $handle")

    /** The fast path: call through an already-resolved [handle]. */
    fun invoke(handle: CallableHandle, args: Array<Any?>): Any? = callable(handle).callable(args)

    /**
     * The slow path: resolve and call by name in one step. Exists for the cost comparison in
     * `UpcallOverheadTest`; production trampolines [resolve] once and [invoke] on every call
     * after that.
     */
    fun invokeByName(name: String, args: Array<Any?>): Any? {
        val index = nameToIndex[name]
            ?: throw IllegalArgumentException("no such exposed name: $name")
        return callables[index].callable(args)
    }

    private fun resolveEntry(handle: CallableHandle): ExposedCallable? {
        if (!handle.isValid) return null
        if (handle.epoch != epoch) return null
        val index = handle.index
        if (index < 0 || index >= callables.size) return null
        return callables[index]
    }
}
