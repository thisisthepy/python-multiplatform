package python.native.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The contract of `wasmJs`'s two string routes, and the reason there are two.
 *
 * `desktopTest/StringMarshallingTest` asserts the same shape against Panama. The rules are the
 * same because they are properties of the *arguments*, not of the platform: a repeated identifier
 * is worth caching, arbitrary content is not. What differs here is what each route costs, which
 * `WasmMarshallingOverheadTest` measures rather than assumes.
 */
class WasmStringMarshallingTest {

    @Test
    fun internReturnsTheSameAddressForEqualStrings() {
        val a = Wasm.internedUtf8("interned_probe_alpha")
        val b = Wasm.internedUtf8("interned_probe_alpha")
        assertEquals(a, b, "the whole point of interning is that the second call allocates nothing")
        assertTrue(a != 0, "interning must produce a usable address")
    }

    @Test
    fun internDistinguishesDifferentStrings() {
        val a = Wasm.internedUtf8("interned_probe_one")
        val b = Wasm.internedUtf8("interned_probe_two")
        assertNotEquals(a, b, "two different names must not share one buffer")
        assertEquals("interned_probe_one", Wasm.readUtf8String(a))
        assertEquals("interned_probe_two", Wasm.readUtf8String(b))
    }

    @Test
    fun internedContentSurvivesLaterTraffic() {
        // The failure this guards against is an interned address being handed out and then
        // overwritten by whatever came next -- which is exactly what the scratch route does on
        // purpose, and must never happen on this one.
        val addr = Wasm.internedUtf8("interned_probe_stable")
        repeat(64) { Wasm.scratchUtf8("noise-$it-${"x".repeat(it)}") }
        repeat(64) { Wasm.internedUtf8("other-name-$it") }
        assertEquals("interned_probe_stable", Wasm.readUtf8String(addr))
        assertEquals(addr, Wasm.internedUtf8("interned_probe_stable"))
    }

    @Test
    fun scratchRotatesThroughItsSlotsSoOneCallCanHoldSeveralStrings() {
        // PyErr_WarnExplicit and PyImport_ExecCodeModuleWithPathnames each stage three strings
        // before the call that reads them. If the second overwrote the first, the C function would
        // see the same bytes twice.
        val a = Wasm.scratchUtf8("scratch-a")
        val b = Wasm.scratchUtf8("scratch-b")
        val c = Wasm.scratchUtf8("scratch-c")
        assertEquals(3, setOf(a, b, c).size, "three concurrent scratch strings must not collide")
        assertEquals("scratch-a", Wasm.readUtf8String(a))
        assertEquals("scratch-b", Wasm.readUtf8String(b))
        assertEquals("scratch-c", Wasm.readUtf8String(c))
    }

    @Test
    fun scratchSlotsAreReusedRatherThanReallocated() {
        // Four slots, so the fifth call comes back to the first. That is the deliberate bound: the
        // scratch route never grows and never frees per call, and no C API function on this surface
        // stages more than three strings.
        val first = Wasm.scratchUtf8("aaaa")
        Wasm.scratchUtf8("bbbb")
        Wasm.scratchUtf8("cccc")
        Wasm.scratchUtf8("dddd")
        val fifth = Wasm.scratchUtf8("eeee")
        assertEquals(first, fifth, "slot 0 should have come round again")
        assertEquals("eeee", Wasm.readUtf8String(fifth))
    }

    @Test
    fun scratchGrowsForALongerStringAndStaysCorrect() {
        val short = "s"
        val long = "L".repeat(9000)
        repeat(3) {
            val a = Wasm.scratchUtf8(short)
            assertEquals(short, Wasm.readUtf8String(a))
            val b = Wasm.scratchUtf8(long)
            assertEquals(long, Wasm.readUtf8String(b))
        }
    }

    @Test
    fun bothRoutesEncodeNonAsciiIdentically() {
        // The encoder writes straight into linear memory rather than going through
        // ByteArray.encodeToByteArray(), so it owns the UTF-8 rules -- two-byte, three-byte and
        // the surrogate-pair case that becomes one four-byte sequence.
        val samples = listOf(
            "",
            "ascii",
            "éèê",              // 2-byte
            "한글 中文",       // 3-byte
            "emoji 😀🐍",  // surrogate pairs -> 4-byte
            "mixed aé中😀z"
        )
        for (s in samples) {
            assertEquals(s, Wasm.readUtf8String(Wasm.scratchUtf8(s)), "scratch round trip: $s")
            assertEquals(s, Wasm.readUtf8String(Wasm.internedUtf8("k:$s")).let { it!!.removePrefix("k:") },
                "interned round trip: $s")
        }
    }

    @Test
    fun ownedAllocationStillRoundTripsAndIsIndependent() {
        // `allocUtf8`/`freeUtf8` is still the route for a buffer whose lifetime the caller owns --
        // `ProxyTypeFactory` needs the type name to outlive every scratch rotation.
        val addr = Wasm.allocUtf8("owned-😀")
        try {
            assertEquals("owned-😀", Wasm.readUtf8String(addr))
            repeat(16) { Wasm.scratchUtf8("noise$it") }
            assertEquals("owned-😀", Wasm.readUtf8String(addr))
        } finally {
            Wasm.freeUtf8(addr)
        }
    }

    @Test
    fun theInternCacheIsBoundedAndFallsBackRatherThanGrowing() {
        // Callers can pass arbitrary strings to an interning argument -- `PyDict_GetItemString`
        // takes user data. Without a bound the cache would be a leak with a friendly name.
        //
        // Tested against its own small instance rather than `Wasm`'s: filling the shared cache
        // would leave every later test in this binary running on the fallback path, which is the
        // sort of order dependence that makes a suite lie.
        val cache = Utf8Intern(maxEntries = 3)
        val a = cache.addressOf("bound-a")
        val b = cache.addressOf("bound-b")
        val c = cache.addressOf("bound-c")
        assertEquals(3, cache.size, "three distinct strings should have filled a cache of three")
        assertEquals(a, cache.addressOf("bound-a"), "a hit must still be a hit once full")

        val overflow = cache.addressOf("bound-d")
        assertEquals("bound-d", Wasm.readUtf8String(overflow), "the fallback must still be usable")
        assertEquals(3, cache.size, "a miss past the bound must fall back, not grow the cache")

        // The three that were cached are untouched by the overflow.
        assertEquals("bound-a", Wasm.readUtf8String(a))
        assertEquals("bound-b", Wasm.readUtf8String(b))
        assertEquals("bound-c", Wasm.readUtf8String(c))
    }

    @Test
    fun theSharedCacheIsWithinItsBound() {
        assertTrue(
            Wasm.internedEntryCount <= Wasm.INTERN_MAX_ENTRIES,
            "shared intern cache is over its own bound: ${Wasm.internedEntryCount}"
        )
    }
}
