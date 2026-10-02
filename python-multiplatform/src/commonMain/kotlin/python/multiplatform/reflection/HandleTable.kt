package python.multiplatform.reflection


/**
 * The GC root that lets Python hold a Kotlin object.
 *
 * `docs/design/object-lifetime.md` sketches the whole thing in four lines:
 *
 *     private val liveHandles = HashMap<Long, Any>()
 *     fun register(obj: Any): Long { val h = next++; liveHandles[h] = obj; return h }
 *     fun release(h: Long)        { liveHandles.remove(h) }
 *
 * This is that, with the two properties the sketch leaves out.
 *
 * **Slot reuse.** Storage is an array indexed by the low half of the handle, so resolution is a
 * bounds check and an array read rather than a hash. A release returns the slot to a free list,
 * which keeps the array bounded by the number of *live* handles instead of the number ever
 * issued.
 *
 * **Staleness.** Reusing a slot without changing the handle would alias a released handle onto
 * the next object to occupy it: Python would keep calling, every call would land on the wrong
 * instance, and nothing anywhere would report an error. So each slot carries a generation that
 * is bumped on release, and resolution rejects a handle whose generation no longer matches.
 *
 * **This table leaks by construction.** Every entry is a strong reference the Kotlin GC can
 * see and Python cannot; nothing here can tell that the Python proxy has died. The proxy's
 * `tp_dealloc` calling [release] is the entire lifetime contract, and it is why [release]
 * reports whether it actually did anything -- a double release must not free a slot that has
 * since been handed to someone else.
 *
 * That contract is kept in exactly two places, and a handle that reaches Python by any other
 * route is a leak:
 *
 * - a **proxy instance**, through the `__del__` `python.multiplatform.ffi.upcall.PythonProxySource`
 *   renders beside every `__init__` that takes a handle. It went unwritten for as long as the
 *   generator existed, and `GeneratedProxyCostTest` ended a run with 78 002 live entries because
 *   of it; `ProxyHandleLifetimeTest` now pins both ends of the count.
 * - a **bare handle** handed to a caller that asked for one over the raw boundary, through
 *   `_pm_release`. Nothing else can give that one back: it is an integer, and an integer has
 *   nothing to hang a finaliser off.
 *
 * Cycles are a separate problem this table cannot solve on its own: a strong entry here is a
 * root, so a Python object reachable only through a Kotlin object reachable only from this
 * table never collects. That needs `tp_traverse` to reach through the handle
 * ([ReflectedClass.traverse]), and for the Kotlin-side case it needs this table to hold weakly
 * with the proxy supplying the strength -- which `docs/design/object-lifetime.md` records as not yet
 * worked through.
 *
 * ### Threading
 *
 * Not synchronised. Every mutation happens on a thread that holds the GIL: an upcall arrives
 * with it held, and handing a Kotlin object to Python requires it to build the proxy. That is
 * the same rule the rest of the boundary already lives under (`commonMain/README.md`), and it
 * is what makes the plain `ArrayList`s here safe. It stops being true under free-threading
 * (ROADMAP §9), where this needs a real lock.
 */
object HandleTable {

    /** Slot storage. A `null` element is a free slot; the index is the handle's low half. */
    private val objects = ArrayList<Any?>()

    /** Per-slot generation, bumped on release. Starts at 1 so no handle is ever `0`. */
    private val generations = ArrayList<Int>()

    /** Free slot indices, used as a stack so the hot slot stays hot. */
    private val freeSlots = ArrayList<Int>()

    private var live = 0

    /** How many handles are currently rooting an object. The leak counter. */
    val liveCount: Int get() = live

    /** How many slots the backing array holds. Bounded by peak [liveCount], not by total issued. */
    val slotCount: Int get() = objects.size

    /**
     * Roots [obj] and returns the handle Python will hold.
     *
     * Registration is not interning: registering the same object twice yields two handles, each
     * released independently. Two Python proxies over one Kotlin object therefore do not have to
     * agree about who dies first. (Whether they *should* be the same proxy -- Python `is`
     * identity across the boundary -- is the Python side's question and is not settled here.)
     */
    fun register(obj: Any): ObjectReference {
        val slot: Int
        if (freeSlots.isEmpty()) {
            slot = objects.size
            objects.add(obj)
            generations.add(1)
        } else {
            slot = freeSlots.removeAt(freeSlots.size - 1)
            objects[slot] = obj
        }
        live++
        return ObjectReference.encode(slot, generations[slot])
    }

    /** The object [ref] roots, or `null` if the handle is [ObjectReference.NONE] or stale. */
    fun resolve(ref: ObjectReference): Any? = resolveRaw(ref.raw)

    /**
     * [resolve] for the boundary, which carries the handle as a bare `Long`.
     *
     * Every rejection path is a plain comparison: a handle that was never issued, one whose slot
     * is out of range, and one whose generation has moved on all return `null` rather than
     * reading past the end of the array.
     */
    fun resolveRaw(raw: Long): Any? {
        if (raw == ObjectReference.NONE_RAW) return null
        val slot = (raw and ObjectReference.SLOT_MASK).toInt()
        if (slot < 0 || slot >= objects.size) return null
        if (generations[slot] != (raw ushr ObjectReference.GENERATION_SHIFT).toInt()) return null
        return objects[slot]
    }

    /**
     * [resolve], but throws instead of returning `null`.
     *
     * The trampolines want this one: an unresolvable handle there means Python outlived the
     * object it was holding, and carrying on would corrupt something further away.
     */
    fun require(ref: ObjectReference): Any =
        resolveRaw(ref.raw) ?: error("stale or unknown object handle: $ref")

    /**
     * Drops the root [ref] holds.
     *
     * @return true if this call released a live entry, false if the handle was already released,
     *   never issued, or [ObjectReference.NONE]. A `false` here is the double-release case, and
     *   it must stay a no-op: the slot may already belong to someone else.
     */
    fun release(ref: ObjectReference): Boolean {
        val raw = ref.raw
        if (raw == ObjectReference.NONE_RAW) return false
        val slot = (raw and ObjectReference.SLOT_MASK).toInt()
        if (slot < 0 || slot >= objects.size) return false
        if (generations[slot] != (raw ushr ObjectReference.GENERATION_SHIFT).toInt()) return false
        if (objects[slot] == null) return false
        releaseSlot(slot)
        return true
    }

    /**
     * Drops every root.
     *
     * For interpreter finalisation and for tests. Generations are bumped rather than reset, so
     * handles issued before the call stay unresolvable afterwards.
     */
    fun releaseAll() {
        for (slot in objects.indices) {
            if (objects[slot] != null) releaseSlot(slot)
        }
    }

    private fun releaseSlot(slot: Int) {
        objects[slot] = null
        generations[slot] = nextGeneration(generations[slot])
        freeSlots.add(slot)
        live--
    }

    /**
     * Wraps back to 1 rather than to 0, because generation 0 would make a handle collide with
     * [ObjectReference.NONE]. A slot released 2^31 times can alias one handle issued that long
     * ago; the alternative is a 128-bit handle, and this is the same trade every generational
     * handle table makes.
     */
    private fun nextGeneration(current: Int): Int = if (current == Int.MAX_VALUE) 1 else current + 1
}
