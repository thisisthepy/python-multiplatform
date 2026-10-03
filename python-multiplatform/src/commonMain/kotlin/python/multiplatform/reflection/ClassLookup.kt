package python.multiplatform.reflection


/**
 * Name -> [ReflectedClass] registry.
 *
 * Populated by [UpcallTable] as fragments are registered, and cleared alongside it -- kept
 * separate because class lookup (`type()`, `isinstance()`) is a distinct Python-visible operation
 * from callable resolution, even though both come from the same fragments.
 */
object ClassLookup {

    private val classes = LinkedHashMap<String, ReflectedClass>()

    internal fun register(cls: ReflectedClass) {
        classes[cls.name] = cls
    }

    internal fun clear() {
        classes.clear()
    }

    internal val count: Int get() = classes.size

    /** The class registered under [name], or `null` if nothing claims it. */
    fun find(name: String): ReflectedClass? = classes[name]

    /** [find], but throws where the caller has no better response than to give up. */
    fun require(name: String): ReflectedClass =
        find(name) ?: throw IllegalArgumentException("no such exposed class: $name")

    /**
     * Every class currently registered, in registration order.
     *
     * For generators that need to describe the whole set rather than resolve one name --
     * [python.multiplatform.ffi.upcall.PythonProxySource] is the one that does, to render a
     * Python class per [ReflectedClass] alongside the module functions it already renders. A
     * copy, for the same reason [UpcallTable.entries] returns one.
     */
    fun all(): List<ReflectedClass> = classes.values.toList()
}
