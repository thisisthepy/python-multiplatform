package python.multiplatform.reflection

/**
 * Hand-written stand-ins for what the KSP processor will emit.
 *
 * The KSP processor and its Gradle wiring are a later task, so the runtime has to be testable
 * without any code generation. These fragments are written the way generated ones will look --
 * an object per module in a well-known package, entries built from lambdas rather than
 * `KFunction` references, classes carrying member names and a traverse function -- so that
 * whatever compiles against this API here will compile against generated code later. See
 * `docs/design/upcall-table-design.md` §1 and `ksp-experiment/`.
 */

/** A Kotlin class the way an exposed user class behaves: identity, state, mutation. */
class Counter(var count: Long = 0) {
    fun increment(by: Long): Long {
        count += by
        return count
    }

    fun label(prefix: String): String = "$prefix$count"
}

/**
 * Stands in for a class holding Python references. The real generated traverse reads
 * `PyObject`-typed fields and visits their raw pointers; here the fields are already the raw
 * pointers so the test does not need a live interpreter. See `docs/design/object-lifetime.md`.
 */
class RefHolder(var first: Long?, var second: Long?)

/** Top-level functions. Generated as `CallableKind.FUNCTION`: no receiver in `args`. */
object TestLibraryFragment : FunctionTableFragment {
    override val moduleName: String = "test_library"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "test.lib.add",
            arity = 2,
            paramTypes = listOf(TypeTag.INT, TypeTag.INT),
            returnType = TypeTag.INT,
        ) { args -> (args[0] as Long) + (args[1] as Long) },
        ExposedCallable(
            name = "test.lib.greet",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.STRING,
        ) { args -> "Hello, ${args[0] as String}!" },
        ExposedCallable(
            name = "test.lib.noArgs",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.UNIT,
        ) { Unit },
    )
}

/** A class, its constructor, its methods and its property accessors. */
object TestAppFragment : FunctionTableFragment {
    override val moduleName: String = "test_app"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "test.app.Counter.<init>",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.CONSTRUCTOR,
        ) { args -> Counter(args[0] as Long) },
        ExposedCallable(
            name = "test.app.Counter.increment",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            kind = CallableKind.METHOD,
        ) { args -> (args[0] as Counter).increment(args[1] as Long) },
        ExposedCallable(
            name = "test.app.Counter.label",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.STRING,
            kind = CallableKind.METHOD,
        ) { args -> (args[0] as Counter).label(args[1] as String) },
        ExposedCallable(
            name = "test.app.Counter.count",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            kind = CallableKind.GETTER,
        ) { args -> (args[0] as Counter).count },
        ExposedCallable(
            name = "test.app.Counter.count=",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.SETTER,
        ) { args -> (args[0] as Counter).count = args[1] as Long },
        ExposedCallable(
            name = "test.app.RefHolder.<init>",
            arity = 2,
            paramTypes = listOf(TypeTag.OBJECT, TypeTag.OBJECT),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.CONSTRUCTOR,
        ) { args -> RefHolder(args[0] as Long?, args[1] as Long?) },
    )

    override fun classes(): List<ReflectedClass> = listOf(
        ReflectedClass(
            name = "test.app.Counter",
            memberNames = listOf(
                "test.app.Counter.<init>",
                "test.app.Counter.increment",
                "test.app.Counter.label",
                "test.app.Counter.count",
                "test.app.Counter.count=",
            ),
        ),
        ReflectedClass(
            name = "test.app.RefHolder",
            memberNames = listOf("test.app.RefHolder.<init>"),
            // What KSP emits for a class with PyObject-typed fields: field reads and visits,
            // no allocation, no user code -- tp_traverse runs during collection.
            traverse = { obj, visit ->
                val holder = obj as RefHolder
                holder.first?.let(visit)
                holder.second?.let(visit)
            },
        ),
    )
}

/** Collides with [TestLibraryFragment] on one name; used to pin the duplicate rule. */
object CollidingFragment : FunctionTableFragment {
    override val moduleName: String = "test_colliding"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "test.lib.add",
            arity = 2,
            paramTypes = listOf(TypeTag.INT, TypeTag.INT),
            returnType = TypeTag.INT,
        ) { args -> (args[0] as Long) - (args[1] as Long) },
    )
}

/** A fragment of [count] synthetic entries, for measuring lookup against a realistic table. */
class BulkFragment(private val count: Int) : FunctionTableFragment {
    override val moduleName: String = "test_bulk"

    override fun entries(): List<ExposedCallable> = (0 until count).map { i ->
        ExposedCallable(
            name = "test.bulk.module$i.function$i",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
        ) { args -> (args[0] as Long) + i }
    }
}

/** A Kotlin singleton and a Kotlin enum, the two shapes whose members have no receiver. */
object TestRegistry {
    var size: Long = 0

    fun ping(): String = "pong"
}

enum class TestColor(val weight: Long) {
    RED(1),
    GREEN(2),
    ;

    fun describe(): String = "$name/$weight"
}

/**
 * The declaration kinds whose members do not take a receiver -- companion/object members and
 * enum entries -- plus the class descriptors that tell the Python side what shape to build.
 * Written the way `python-multiplatform-ksp` emits them; `ksp-fixtures` proves the generator
 * actually does.
 */
object TestStaticsFragment : FunctionTableFragment {
    override val moduleName: String = "test_statics"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "test.statics.Registry.ping",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            kind = CallableKind.FUNCTION,
        ) { TestRegistry.ping() },
        ExposedCallable(
            name = "test.statics.Registry.size",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            kind = CallableKind.STATIC_GETTER,
        ) { TestRegistry.size },
        ExposedCallable(
            name = "test.statics.Registry.size=",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.STATIC_SETTER,
        ) { args -> TestRegistry.size = args[0] as Long },
        ExposedCallable(
            name = "test.statics.Color.RED",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.STATIC_GETTER,
        ) { TestColor.RED },
        ExposedCallable(
            name = "test.statics.Color.describe",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            kind = CallableKind.METHOD,
        ) { args -> (args[0] as TestColor).describe() },
    )

    override fun classes(): List<ReflectedClass> = listOf(
        ReflectedClass(
            name = "test.statics.Registry",
            memberNames = listOf(
                "test.statics.Registry.ping",
                "test.statics.Registry.size",
                "test.statics.Registry.size=",
            ),
            kind = ReflectedClassKind.OBJECT,
        ),
        ReflectedClass(
            name = "test.statics.Color",
            memberNames = listOf("test.statics.Color.RED", "test.statics.Color.describe"),
            kind = ReflectedClassKind.ENUM,
            enumEntryNames = listOf("RED", "GREEN"),
        ),
    )
}
