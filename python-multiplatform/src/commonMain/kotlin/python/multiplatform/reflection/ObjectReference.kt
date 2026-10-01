package python.multiplatform.reflection

import kotlin.jvm.JvmInline


/**
 * A Python-side reference to a Kotlin object: an integer, and nothing else.
 *
 * Python cannot hold the object itself on any of our targets -- a JVM reference stored in
 * native memory is invisible to the GC, a Kotlin/Native reference is a raw pointer with no
 * ownership, and a WasmGC reference cannot be written into linear memory at all. So the proxy's
 * instance data holds this integer and [HandleTable] holds the object. `docs/design/object-lifetime.md`
 * calls this "what JNI's `NewGlobalRef` does, made explicit".
 *
 * The raw value packs a **generation** into the high 32 bits and a **slot index** into the low
 * 32. Slots are reused; generations are not, so a handle that outlived its release does not
 * resolve to whatever took its slot. Without that, a stale handle would silently call methods
 * on the wrong instance -- the one boundary bug that produces no error at all.
 *
 * Generations start at 1, so a valid handle is never `0`. Zero is [NONE], which is what the
 * boundary uses for "no object".
 */
@JvmInline
value class ObjectReference(val raw: Long) {

    /** False only for [NONE]; a non-zero handle may still be stale, which only the table knows. */
    val isValid: Boolean get() = raw != NONE_RAW

    internal val slot: Int get() = (raw and SLOT_MASK).toInt()

    internal val generation: Int get() = (raw ushr GENERATION_SHIFT).toInt()

    override fun toString(): String =
        if (raw == NONE_RAW) "ObjectReference(NONE)" else "ObjectReference(slot=$slot, gen=$generation)"

    companion object {
        internal const val NONE_RAW: Long = 0L
        internal const val SLOT_MASK: Long = 0xFFFF_FFFFL
        internal const val GENERATION_SHIFT: Int = 32

        /** The null handle. Never issued by [HandleTable]. */
        val NONE: ObjectReference = ObjectReference(NONE_RAW)

        internal fun encode(slot: Int, generation: Int): ObjectReference =
            ObjectReference((generation.toLong() shl GENERATION_SHIFT) or (slot.toLong() and SLOT_MASK))
    }
}
