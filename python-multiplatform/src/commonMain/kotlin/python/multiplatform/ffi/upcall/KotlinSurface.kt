package python.multiplatform.ffi.upcall

import python.multiplatform.reflection.ExposedCallable

/**
 * `python_multiplatform` -- the binder's own root module, and the one thing both installers share.
 *
 * ### Why it exists
 *
 * A Kotlin-named module (`androidx.compose.material3`, `kotlin.text`, a consumer's own package)
 * carries **Kotlin's own surface**: Kotlin declaration names, keyword arguments by **Kotlin
 * parameter names**, Kotlin defaults for what a call leaves out. Nothing is renamed -- no
 * snake_case, no `pythonx` -- because a Kotlin name in Python means the original Kotlin
 * (`docs/INTENT.md` §2.2). Making that surface Pythonic is the business of a real Python package
 * built on top of it (pythonx-compose, §2.3), and this module is what such a package reads to do
 * it by rule rather than by a wrapper per declaration.
 *
 * Two installers put functions into those modules -- [PythonProxySource] renders every table entry
 * eagerly; [python.multiplatform.ffi.pythonx.PythonxAdapter] adapts names lazily, adds overload
 * dispatch, receiver proxies and value-class rules -- and each used to describe a declaration in
 * its own way. The pieces that must not differ between them live here, once:
 *
 * | name | what |
 * |---|---|
 * | `KOTLIN_DEFAULT` | the `inspect.Parameter.default` of a parameter whose Kotlin declaration has a default. Passing it (or leaving the parameter out) means "Kotlin's own default" |
 * | `describe(fn)` | the declaration(s) behind a binder-made callable: a tuple of dicts, one per overload (see [SOURCE]) |
 * | `kotlin_function(raw, row)` | what [PythonProxySource] wraps each rendered function in: keyword arguments by Kotlin name, omitted defaults, and the metadata above |
 * | `signature_of(rows)` | the `inspect.Signature` for one declaration (`(*args, **kwargs)` for an overload set) |
 *
 * ### The public contract, for a Pythonic layer
 *
 * Every function the binder puts on a Kotlin-named module -- whichever installer put it there --
 * answers `inspect.signature(fn)` with the **Kotlin** parameter names, in declaration order:
 *
 * - an extension receiver is a positional-only parameter named `receiver`;
 * - a parameter with a Kotlin default has `default is python_multiplatform.KOTLIN_DEFAULT`;
 * - a required parameter that follows a defaulted one (Compose's trailing `content`) is
 *   keyword-only, because Python has no other spelling for "required after optional";
 * - each annotation is the declared Kotlin type name as a string (`'androidx.compose.ui.unit.Dp'`);
 * - the synthetic `$composer`/`$changed`/`$default` slots of a `@Composable` never appear.
 *
 * An overload set (a base name the binding layer dispatches, `padding` for `padding__Dp`,
 * `padding__Dp_Dp`, ...) has no single signature and answers `(*args, **kwargs)`.
 *
 * `python_multiplatform.describe(fn)` answers for both, as a tuple with one dict per declaration:
 *
 *     {'name': 'androidx.compose.material3.Checkbox',   # the bound Kotlin name (overload suffix included)
 *      'kind': 'FUNCTION', 'suspend': False,
 *      'receiver': None,                                 # the extension receiver's Kotlin type, or None
 *      'returns': 'kotlin.Unit',                         # the declared return type, or None
 *      'composable': True,                               # has a $composer slot
 *      'content': None,                                  # name of a trailing @Composable lambda parameter
 *      'parameters': (
 *          {'name': 'checked', 'type': 'kotlin.Boolean', 'tag': 'BOOLEAN',
 *           'has_default': False, 'value_class': False, 'composable_lambda': False},
 *          ...)}
 *
 * `value_class` is true for a parameter whose marshalling tag is a primitive while its declared
 * type is not a Kotlin primitive (`Dp`, `Color`, `TextUnit`) -- the only machine-checkable form of
 * "this is a value class". `composable_lambda` is true for a `@Composable` function-typed slot.
 *
 * ### Member resolvers: the one hook for names on a proxy
 *
 * A proxy the binder returns (`Modifier.padding(16)` is one) serves its Kotlin members under their
 * Kotlin names only. A Pythonic package built on the binder can say what else a member may be called:
 *
 *     python_multiplatform.binding.add_member_resolver(fn)
 *     python_multiplatform.binding.remove_member_resolver(fn)
 *
 *     fn(kotlin_type_name, requested_name, kotlin_member_names) -> kotlin_name | None
 *
 * - `fn` is asked only when the proxy has **no** Kotlin member named `requested_name`; a Kotlin name
 *   never reaches it. `kotlin_member_names` is a tuple of the Kotlin member names the type has.
 * - The first resolver (in registration order) returning a name that is in `kotlin_member_names` wins,
 *   and that Kotlin member is served. Any other answer (`None`, an unknown name, a name starting with
 *   `_`) means "not mine"; with no resolver answering, the result is the usual `AttributeError`.
 * - The binder renames nothing itself: with no resolver registered behaviour is exactly the
 *   Kotlin-names-only behaviour above.
 * - Answers are cached in the resolver registry (cleared when a resolver is added or removed, and
 *   when a table is registered), never written onto a proxy class: `dir()` of a proxy and the class
 *   `__dict__` show Kotlin names only. Removing a resolver removes its aliases.
 * - Registering the same function twice is a no-op. Resolvers are process-wide, like the binding layer.
 *
 * ### `__signature__` is lazy
 *
 * [PythonProxySource] renders every entry eagerly, and building an `inspect.Signature` for each of
 * a Compose-sized table at install would be paid by every test and every app start for an answer
 * almost nobody asks for. `inspect.signature` follows `__wrapped__` to the first object that has a
 * `__signature__`, so each published function's `__wrapped__` is a small carrier whose
 * `__signature__` is built on first use. (A *callable* `__signature__` is not portable: the embedded
 * CPython rejected one with "unexpected object in __signature__ attribute".) Read it through
 * `inspect.signature`, not by reading an attribute directly.
 *
 * ### Where it lives, and why it is not `pythonx`
 *
 * A binder-owned name. `pythonx` is a real package on disk (pythonx-compose); a module the binder
 * puts into `sys.modules` under that name would be answered before `sys.path` is ever consulted,
 * and the real package's files could not load (`AGENTS.md` §12.2).
 */
object KotlinSurface {

    /** The root module's name in `sys.modules`. */
    const val MODULE_NAME: String = "python_multiplatform"

    /**
     * The hand-written Python. Delivered inside an `r"""..."""` literal, so it must contain no
     * triple double-quote and must not end in a backslash; docstrings are `'''` for that reason.
     */
    val SOURCE: String = """
        # GENERATED-FREE: hand-written Python. See KotlinSurface.kt.
        #
        # `python_multiplatform` -- the binder's root module. Kotlin-named modules carry Kotlin's own
        # surface; this module describes it, so that a Pythonic package can be built on top by rule.

        import inspect as _inspect
        import keyword as _keyword
        import sys as _sys

        _pm_root = True

        # Where the binding layer (`PythonxAdapter`) lives once it is installed.
        BINDING_MODULE = 'python_multiplatform.binding'

        # The `UpcallTable` epoch the proxy layer last rendered for (`PythonProxySource.install`
        # stamps it). The binding layer carries its own (`_registered_epoch`); see `kotlin_function`
        # for how the two decide which layer answers a call.
        _proxy_epoch = -1


        class _KotlinDefault:
            '''The `default` of a parameter whose Kotlin declaration has one.

            Leaving the parameter out, or passing this object, both mean "Kotlin's own default" --
            the value is never sent to Kotlin, which supplies it itself.
            '''

            __slots__ = ()

            def __repr__(self):
                return '<Kotlin default>'

            def __copy__(self):
                return self

            def __deepcopy__(self, _memo):
                return self

            def __reduce__(self):
                return 'KOTLIN_DEFAULT'


        KOTLIN_DEFAULT = _KotlinDefault()


        # One table row, as `KotlinSurface.row` renders it and `PythonxAdapter.renderTable` sends it.
        _NAME, _ARITY, _KIND, _SUSPEND, _NAMES, _TAGS, _TYPES, _RTAG, _RTYPE, _EXT, _RECV, _DEFAULTS = range(12)

        _RECEIVER = '<receiver>'
        _COMPOSER = '${'$'}composer'
        _FUNCTION_PREFIX = 'kotlin.Function'
        _COMPOSABLE_MARK = '@Composable'
        _SUPERTYPE_SEPARATOR = '<:'
        _PRIMITIVE_TAGS = ('INT', 'FLOAT', 'BOOLEAN', 'STRING')
        _KOTLIN_PRIMITIVES = frozenset((
            'kotlin.Byte', 'kotlin.Short', 'kotlin.Int', 'kotlin.Long', 'kotlin.Float', 'kotlin.Double',
            'kotlin.Boolean', 'kotlin.Char', 'kotlin.String', 'kotlin.Unit', 'kotlin.Any',
        ))


        def _field(seq, index):
            return seq[index] if seq and index < len(seq) else None


        def _is_synthetic(name):
            # `${'$'}composer`, `${'$'}changed`, `${'$'}default`: the Compose compiler's own slots. A dollar is
            # not a Python identifier character, so no caller can ever have meant one of these.
            return name.startswith('${'$'}')


        def _is_composable_lambda(type_name):
            if not type_name or not type_name.startswith(_FUNCTION_PREFIX):
                return False
            return _COMPOSABLE_MARK in type_name.partition('(')[0]


        def _declared_return(type_name):
            if not type_name:
                return None
            return type_name.split(_SUPERTYPE_SEPARATOR)[0]


        def _describe_row(row):
            names = row[_NAMES] or ()
            tags = row[_TAGS] or ()
            types = row[_TYPES] or ()
            defaults = row[_DEFAULTS] or ()
            parameters = []
            for index in range(row[_ARITY]):
                name = _field(names, index)
                if name is not None and (name == _RECEIVER or _is_synthetic(name)):
                    continue
                type_name = _field(types, index)
                tag = _field(tags, index)
                parameters.append({
                    'name': name,
                    'type': type_name,
                    'tag': tag,
                    'has_default': bool(_field(defaults, index)),
                    'value_class': bool(
                        type_name and tag in _PRIMITIVE_TAGS and type_name not in _KOTLIN_PRIMITIVES
                    ),
                    'composable_lambda': _is_composable_lambda(type_name),
                })
            last = parameters[-1] if parameters else None
            return {
                'name': row[_NAME],
                'kind': row[_KIND],
                'suspend': bool(row[_SUSPEND]),
                'receiver': row[_RECV] if row[_EXT] else None,
                'returns': _declared_return(row[_RTYPE]),
                'composable': _COMPOSER in names,
                'content': last['name'] if last is not None and last['composable_lambda'] else None,
                'parameters': tuple(parameters),
            }


        def describe(fn):
            '''The Kotlin declaration(s) behind [fn]: a tuple of dicts, one per overload.

            [fn] is anything the binder put on a Kotlin-named module -- a function the proxy layer
            rendered, a callable or overload set the binding layer adapted, or an extension already
            applied to its receiver (`Modifier.padding`). See `KotlinSurface` for the dict's keys.
            '''
            rows = getattr(fn, '__kotlin_rows__', None)
            if rows is None:
                raise TypeError(repr(fn) + ' is not a Kotlin declaration the binder exposed')
            return tuple(_describe_row(row) for row in rows)


        _GENERIC = _inspect.Signature([
            _inspect.Parameter('args', _inspect.Parameter.VAR_POSITIONAL),
            _inspect.Parameter('kwargs', _inspect.Parameter.VAR_KEYWORD),
        ])


        def _annotation(type_name):
            return type_name if type_name else _inspect.Parameter.empty


        def signature_of(rows, drop_receiver=False):
            '''The `inspect.Signature` of one declaration; `(*args, **kwargs)` for anything else.

            An overload set has no single signature, and a declaration whose Kotlin parameter name
            Python cannot spell (`in`, `is`, `from` ...) cannot be written as one -- its keyword is
            still accepted through `**{...}`, and `describe` still carries the true name.
            '''
            if len(rows) != 1:
                return _GENERIC
            row = rows[0]
            names = row[_NAMES] or ()
            types = row[_TYPES] or ()
            defaults = row[_DEFAULTS] or ()
            P = _inspect.Parameter
            params = []
            if not names:
                for index in range(row[_ARITY]):
                    params.append(P('a' + str(index), P.POSITIONAL_ONLY, annotation=_annotation(_field(types, index))))
                if drop_receiver and params:
                    params = params[1:]
                return _inspect.Signature(params, return_annotation=_annotation(_declared_return(row[_RTYPE])))
            receiver_name = 'receiver' if 'receiver' not in names else '_receiver'
            kind = P.POSITIONAL_OR_KEYWORD
            seen_default = False
            for index, name in enumerate(names):
                if _is_synthetic(name):
                    continue
                annotation = _annotation(_field(types, index))
                if name == _RECEIVER:
                    if not drop_receiver:
                        params.append(P(receiver_name, P.POSITIONAL_ONLY, annotation=annotation))
                    continue
                if not name.isidentifier() or _keyword.iskeyword(name):
                    return _GENERIC
                has_default = bool(_field(defaults, index))
                if not has_default and seen_default:
                    kind = P.KEYWORD_ONLY
                seen_default = seen_default or has_default
                params.append(P(
                    name, kind,
                    default=KOTLIN_DEFAULT if has_default else P.empty,
                    annotation=annotation,
                ))
            return _inspect.Signature(params, return_annotation=_annotation(_declared_return(row[_RTYPE])))


        class _SignatureCarrier:
            '''What a published function's `__wrapped__` points at: the raw function, plus a signature
            built on first use.

            `inspect.signature` unwraps `__wrapped__` until it reaches an object that has a
            `__signature__`, and asks that. Building an `inspect.Signature` for every entry of a
            Compose-sized table at install would be paid by every start for an answer almost nobody
            asks for; this defers it to the first `inspect.signature` call. Calling it calls the raw
            function, so anything that follows `__wrapped__` still reaches a working callable.
            '''

            __slots__ = ('_raw', '_rows', '_sig')

            def __init__(self, raw, rows):
                self._raw = raw
                self._rows = rows
                self._sig = None

            def __call__(self, *args):
                return self._raw(*args)

            @property
            def __signature__(self):
                if self._sig is None:
                    self._sig = signature_of(self._rows)
                return self._sig


        class _Missing:
            __slots__ = ()


        _MISSING = _Missing()


        def bind_slots(row, args, kwargs):
            '''Positional and Kotlin-named keyword arguments -> one value per slot of [row].

            A defaulted slot the call left out (or filled with `KOTLIN_DEFAULT`) becomes `None`: the
            walked Kotlin body tests `args[i] == null` and takes a call expression that does not
            mention the parameter, so the compiler supplies the default
            (`ArtifactScanner.presenceBranchedCall`). This is the binding the proxy layer does on its
            own; the binding layer (`PythonxAdapter`) does the same and adds coercion on top.
            '''
            name = row[_NAME]
            arity = row[_ARITY]
            names = row[_NAMES] or ()
            defaults = row[_DEFAULTS] or ()
            if _COMPOSER in names:
                raise TypeError(
                    name + ' is a @Composable: its ${'$'}composer slot is filled by the binding layer ' +
                    '(python_multiplatform.binding, installed by PythonxAdapter.install), which is not installed'
                )
            if len(args) > arity:
                raise TypeError(name + '() takes ' + str(arity) + ' arguments, got ' + str(len(args)))
            slots = list(args) + [_MISSING] * (arity - len(args))
            for key, value in kwargs.items():
                index = -1
                if key != _RECEIVER and not _is_synthetic(key):
                    for slot, declared in enumerate(names):
                        if declared == key:
                            index = slot
                            break
                if index < 0:
                    raise TypeError(name + "() got an unexpected keyword argument '" + key + "'")
                if slots[index] is not _MISSING:
                    raise TypeError(name + "() got multiple values for argument '" + key + "'")
                slots[index] = value
            for index, value in enumerate(slots):
                if value is _MISSING or value is KOTLIN_DEFAULT:
                    if _field(defaults, index):
                        slots[index] = None
                        continue
                    missing = _field(names, index) or ('a' + str(index))
                    raise TypeError(name + "() missing required argument '" + missing + "'")
            return tuple(slots)


        def kotlin_function(raw, row):
            '''What `PythonProxySource` publishes for each rendered function entry.

            [raw] is the rendered positional function (`a0..aN`, the raw boundary's own calling
            convention, unchanged). The callable returned here is the module's one owner of the name:

            - when the binding layer is installed **for a table at least as new as the one this
              function was rendered for**, and knows this declaration, the call is its -- keyword
              arguments, defaults, coercion, value-class rules, receiver proxies. Decided at call
              time from the two `UpcallTable` epochs the installers stamped, so for one table the
              answer is the same whichever installer ran first; across tables the newer install
              wins, and neither layer's handles from an older table are ever used;
            - otherwise a call that fills every slot positionally goes straight to [raw], and any
              other call is mapped onto the slots by Kotlin parameter name first (`bind_slots`).
            '''
            name = row[_NAME]
            arity = row[_ARITY]
            composable = _COMPOSER in (row[_NAMES] or ())
            has_defaults = any(row[_DEFAULTS] or ())
            modules = _sys.modules
            default = KOTLIN_DEFAULT

            def kotlin(*args, **kwargs):
                binding = modules.get(BINDING_MODULE)
                if binding is not None and binding._registered_epoch >= _proxy_epoch:
                    delegate = binding._callable_named(name)
                    if delegate is not None:
                        return delegate(*args, **kwargs)
                if not kwargs and len(args) == arity and not composable:
                    if not has_defaults:
                        return raw(*args)
                    for value in args:
                        if value is default:
                            break
                    else:
                        return raw(*args)
                return raw(*bind_slots(row, args, kwargs))

            kotlin.__wrapped__ = _SignatureCarrier(raw, (row,))
            kotlin.__kotlin_rows__ = (row,)
            if _inspect.iscoroutinefunction(raw):
                # A `suspend fun`: [raw] is an `async def`, and this hands back the coroutine it
                # builds, so it answers `inspect.iscoroutinefunction` the way [raw] does.
                _inspect.markcoroutinefunction(kotlin)
            return kotlin
    """.trimIndent()

    /**
     * Installs [SOURCE] as `sys.modules['python_multiplatform']`, once per interpreter.
     *
     * Guarded on `sys.modules` rather than on a Kotlin flag, for the reason `PythonxAdapter`'s
     * delivery gives: a test fixture that re-initialises the interpreter would otherwise be told
     * "installed" about an interpreter that no longer exists.
     *
     * `__path__` makes it a package, so `python_multiplatform.binding` can be a submodule of it.
     */
    val DELIVERY: String
        get() = buildString {
            require(!SOURCE.contains("\"\"\"")) { "the python_multiplatform source must not contain a triple double-quote" }
            require(!SOURCE.endsWith("\\")) { "the python_multiplatform source must not end in a backslash" }
            appendLine("import sys as _pms_sys")
            appendLine("import types as _pms_types")
            appendLine("if not getattr(_pms_sys.modules.get('$MODULE_NAME'), '_pm_root', False):")
            appendLine("    _pms_src = r\"\"\"")
            appendLine(SOURCE)
            appendLine("\"\"\"")
            appendLine("    _pms_mod = _pms_sys.modules.get('$MODULE_NAME') or _pms_types.ModuleType('$MODULE_NAME')")
            appendLine("    if not hasattr(_pms_mod, '__path__'):")
            appendLine("        _pms_mod.__path__ = []")
            appendLine("    _pms_sys.modules['$MODULE_NAME'] = _pms_mod")
            appendLine("    exec(compile(_pms_src, '$MODULE_NAME/__init__.py', 'exec'), _pms_mod.__dict__)")
            appendLine("    del _pms_src, _pms_mod")
            append("del _pms_sys, _pms_types")
        }

    /**
     * One table row as a Python tuple literal -- the shape `_register_table` and
     * `kotlin_function` both read. Shared so the two installers cannot describe one entry
     * differently.
     */
    fun row(entry: ExposedCallable): String = buildString {
        append("(")
        append(entry.name.quoted())
        append(", ${entry.arity}")
        append(", ${entry.kind.name.quoted()}")
        append(", ${entry.isSuspend.py()}")
        append(", ${entry.paramNames.map { it.quoted() }.tuple()}")
        append(", ${entry.paramTypes.map { it.name.quoted() }.tuple()}")
        append(", ${entry.paramTypeNames.map { it.quoted() }.tuple()}")
        append(", ${entry.returnType.name.quoted()}")
        append(", ${entry.returnTypeName?.quoted() ?: "None"}")
        append(", ${entry.isExtension.py()}")
        append(", ${entry.receiverTypeName?.quoted() ?: "None"}")
        append(", ${entry.paramHasDefault.map { it.py() }.tuple()}")
        append(")")
    }

    private fun Boolean.py(): String = if (this) "True" else "False"

    private fun List<String>.tuple(): String = when (size) {
        0 -> "()"
        1 -> "(${this[0]},)"
        else -> "(${joinToString(", ")})"
    }

    private fun String.quoted(): String =
        "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"
}
