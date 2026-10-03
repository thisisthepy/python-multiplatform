package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.reflection.PythonInternal
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Kotlin surface this app offers to Python.
 *
 * **Nothing here is registered by hand.** This module applies
 * `id("io.github.thisisthepy.python.multiplatform.bindings")`, which runs `python-multiplatform-ksp`
 * over these sources at build time; every public declaration it finds becomes an entry in the
 * generated `python.multiplatform.generated.FunctionTable`, which [installGeneratedUpcallTable]
 * hands to [python.multiplatform.reflection.UpcallTable]. Exposure is a blacklist (ROADMAP §7,
 * `docs/design/binding-policy.md`): being `public` is all it takes, and opting *out* is what needs an
 * annotation.
 *
 * The Python-visible name of an entry is its Kotlin qualified name, which is why
 * [UPCALL_ENTRY_NAME] can be written out as a literal for the UI and the native-image check.
 */
object DemoCounter {

    private var count: Long = 0

    /** Called from the Compose button. Ordinary Kotlin state; Python is not involved. */
    fun press() {
        count += 1
    }

    /**
     * The zero-argument entry, kept because `NativeImageMain`'s closed-world check resolves and
     * calls exactly this one through the older `(long) -> long` stub pair.
     */
    fun presses(): Long = count

    /**
     * The entry that takes arguments -- the half that did not exist until
     * `python.multiplatform.ffi.upcall.UpcallTrampoline` did (ROADMAP §13).
     *
     * A `String` and a `Long` in, a `String` out: three marshalling decisions the boundary makes
     * from the `TypeTag`s the generated table has carried all along. Nothing about this
     * declaration is special -- it is exposed for being `public`, like everything else here.
     */
    fun describe(prefix: String, multiplier: Long): String = "$prefix${count * multiplier}"

    /**
     * Exposed to nobody. `@PythonInternal` is the opt-out, and this entry must be absent from the
     * generated table; the demo screen and `NativeImageMain` both assert on that, so the
     * annotation losing its effect would be visible rather than silent.
     */
    @PythonInternal
    fun internalDetail(): Long = -1L
}

/**
 * An ordinary Kotlin `class` -- constructor, instance methods, instance properties, a companion,
 * and two `suspend fun`s -- offered to Python with no annotation and no registration.
 *
 * [DemoCounter] above is an `object`, so every entry KSP emits for it is receiver-free and lands as
 * a plain module function. That is the *only* shape this sample used to exercise. Everything the
 * boundary grew after it -- `CONSTRUCTOR`/`METHOD`/`GETTER`/`SETTER` entries, a metaclass carrying
 * the companion, and `await` over a `suspend fun` -- needs a real class to have anything to attach
 * to, and none of it had a caller outside `python-multiplatform`'s own tests until this type
 * existed.
 *
 * Each member is here for a specific rule the Python surface has to reproduce:
 *
 * | member | what it pins |
 * |---|---|
 * | `Greeter(subject)` | a Python `__init__` that calls Kotlin's constructor and holds the receiver handle |
 * | [greet] | an instance method, receiver passed as `args[0]` |
 * | [subject] | a `var` -- readable *and* assignable from Python |
 * | [greetings] | a `var` with `private set` -- readable, and assignment must raise `AttributeError` |
 * | [Companion.PUNCTUATION] | a companion `val` -- reachable through the class, never through an instance, and not assignable |
 * | [Companion.built] | a companion `var` -- the same, plus assignment reaching Kotlin |
 * | [Companion.forget] | a companion *function*, which lands on the metaclass beside the properties |
 * | [greetNow] | a `suspend fun` that never suspends: `await` must take the fast path and build no `Future` |
 * | [greetLater] | a `suspend fun` that really suspends: `await` must resolve once Kotlin resumes it |
 */
class Greeter(subject: String) {

    /** A `var` with a public setter: `g.subject = "..."` from Python has to reach this. */
    var subject: String = subject

    /**
     * A `var` whose setter is not public API.
     *
     * ROADMAP §13's second defect was `FragmentScanner` emitting a `SETTER` entry from
     * `isMutable` alone; with the fix there is a `GETTER` entry here and no `SETTER`, so Python
     * reads it and refuses to write it. Assigning it must raise `AttributeError` rather than
     * quietly binding a Python attribute that shadows the Kotlin value for every later read.
     */
    var greetings: Long = 0
        private set

    init {
        built += 1
    }

    /** An instance method. The receiver arrives as `args[0]`, resolved from `self._pm_handle`. */
    fun greet(times: Long): String {
        greetings += times
        return (0 until times).joinToString(" ") { "hello $subject$PUNCTUATION" }
    }

    /**
     * `suspend`, but with no suspension point in the body -- so it is already complete when
     * `PendingCall.start` returns and the boundary hands Python the real value with no `asyncio`
     * involved. From the call site it is indistinguishable from [greetLater]; that is the point of
     * the generated `async def`.
     */
    suspend fun greetNow(times: Long): String = greet(times)

    /**
     * `suspend`, and it really does suspend: the continuation is parked until something on the
     * Kotlin side calls [PendingGreetings.deliver]. This is the path that builds an
     * `asyncio.Future` and settles it from a Kotlin thread.
     */
    suspend fun greetLater(times: Long): String =
        suspendCoroutine { continuation -> PendingGreetings.park { continuation.resume(greet(times)) } }

    companion object {

        /** A companion `val`: read through the class object, and not assignable. */
        const val PUNCTUATION: String = "!"

        /** A companion `var`: read *and* written through the class object. */
        var built: Long = 0

        /** A companion *function*. It shares the metaclass with the two properties above. */
        fun forget(): Long {
            val forgotten = built
            built = 0
            return forgotten
        }
    }
}

/**
 * Where [Greeter.greetLater]'s continuation waits.
 *
 * `internal`, so KSP does not scan it: nothing about this is part of the Python surface, and an
 * exposed "resume the parked coroutine" entry would let Python complete its own `await` on the
 * calling thread, which is the one arrangement that proves nothing about delivery.
 */
internal object PendingGreetings {

    private var parked: (() -> Unit)? = null

    fun park(resume: () -> Unit) {
        parked = resume
    }

    /** True while a suspended call is waiting. Polled by whichever thread does the delivering. */
    val isParked: Boolean get() = parked != null

    /** Resumes the parked call, if any, and says whether there was one. */
    fun deliver(): Boolean {
        val resume = parked ?: return false
        parked = null
        resume()
        return true
    }
}

/** The name [DemoCounter.presses] is registered under. */
const val UPCALL_ENTRY_NAME: String =
    "org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter.presses"

/** The name [DemoCounter.describe] is registered under. */
const val UPCALL_ARGS_ENTRY_NAME: String =
    "org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter.describe"

/** The name [DemoCounter.internalDetail] *would* carry if `@PythonInternal` did nothing. */
const val UPCALL_EXCLUDED_NAME: String =
    "org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter.internalDetail"
