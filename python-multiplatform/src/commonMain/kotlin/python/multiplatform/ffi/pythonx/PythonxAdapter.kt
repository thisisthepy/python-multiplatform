package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.UpcallTable

/**
 * `pythonx` -- the hand-written Python that adapts the Kotlin declarations, and the generated table
 * it reads.
 *
 * `docs/pythonx-adapter-design.md` §7 draws one line through this whole area: **if it differs per
 * Kotlin declaration it is generated or resolved at run time; if it is the same rule for every
 * declaration it is `pythonx` Python source.** This object is where the two meet, and it keeps them
 * apart on purpose:
 *
 * | | what | who wrote it |
 * |---|---|---|
 * | [SOURCE] | the finder, the name rule, the overload dispatcher, the receiver proxies | a person, once. It names no Kotlin declaration anywhere |
 * | [renderTable] | one row per entry in [UpcallTable] | this function, from the table |
 *
 * The 2024 `pythonx-compose` put both on the wrong side of that line -- a Python file per Compose
 * component, 37 of them, 28 empty -- and `docs/pythonx-adapter-design.md` §1 measures what it cost:
 * `padding()` composed nothing and `fill_max_size()` returned `self`, because a per-declaration
 * wrapper is written once and then never again. Nothing here is per-declaration.
 *
 * ### What the Python needs from the host, and what it does not
 *
 * The same two names [python.multiplatform.ffi.upcall.PythonProxySource] needs -- `_pm_resolve` and
 * `_pm_invoke` in `__main__` -- plus `_pm_release` if handles are to be given back. It does **not**
 * need `PythonProxySource.install()`: the eager whole-table render and this lazy one are two
 * independent consumers of the same boundary, and `PythonxAdapterTest` installs only this one.
 * That is §2.3's laziness claim made executable rather than argued.
 *
 * ### Why the source is a Kotlin string and not a `.py` file
 *
 * The same reason [python.multiplatform.ffi.upcall.PythonProxySource] gives, and §2.5 restates for
 * this layer specifically: **there is no resource path to put a `.py` on that works everywhere.**
 * Kotlin/Native has no `getResourceAsStream`, and `sys.path` on iOS, androidNative and wasm points
 * into the per-platform CPython trees under `src/nativeInterop/cinterop/lib/...`. `Python3.exec`
 * works on all five today and nothing else does.
 *
 * [python.multiplatform.env.PythonPayload] has since solved *half* of this, and the half it solved
 * is not this one. It puts a **consumer's** payload directory on `sys.path` before the first
 * import, from a real packaging step, on desktop (a jar resource, extracted) and Android (an APK
 * asset, unpacked). It is not a route for a *library-generated* file: iOS needs an Xcode phase
 * nobody has written, androidNative and wasm have no packaging step at all, and the table this
 * renders is assembled at run time from whatever `UpcallTable.install` was given -- the second
 * reason `PythonProxySource` gives, which no packaging work touches.
 *
 * This is the third candidate §2.5 lists, and its cost is named there and not disputed here: the
 * source is hostile to editing, diffing and shipping to PyPI, which `pythonx-compose`'s
 * `pyproject.toml` says it is eventually meant to do. **§2.5 stays open.** What is *not* paid is
 * duplication: this is the only copy, so there is nothing for a `.py` beside it to drift from.
 *
 * One mechanical constraint follows from the delivery route and is worth stating where it can be
 * broken: [SOURCE] is handed to Python inside an `r"""..."""` literal, so it must contain no triple
 * double-quote and must not end in a backslash. Its docstrings are `'''` for exactly that reason.
 * [render] checks it rather than leaving the failure to a `SyntaxError` at `exec` time.
 *
 * ### GraalVM native image
 *
 * Unchanged from `PythonProxySource`: this renders a *Python* string out of data that is already in
 * a statically-emitted Kotlin table. No reflection, no class loading, no dynamic Kotlin. The closed
 * world stays closed. (Unverified here: no native image was built for this change.)
 */
object PythonxAdapter {

    /**
     * The adaptation layer itself: hand-written Python, the same for every table.
     *
     * Read it as a file -- it is one, and the indentation the Kotlin literal adds is removed by
     * `trimIndent`. What it contains, in the order the design asks for it:
     *
     * | | `docs/pythonx-adapter-design.md` |
     * |---|---|
     * | `_Finder` / `_Loader` | §2.3, the hook a module `__getattr__` cannot replace |
     * | the module `__getattr__` the loader installs | §4.1, adapted once and then a dict hit |
     * | `to_python_name` / `to_kotlin_name` / `kotlin_name_for` | §3, forward by rule, backward by index |
     * | `_Overloads` | `docs/kotlin-extensions-in-python.md` §3.1, the dispatcher that has to live here |
     * | `_proxy_type` / `_Hybrid` | §4.2, an extension as a method on its receiver, both spellings |
     * | `_coerce`'s allowlist | §4.4, `Dp` yes, a packed value class no |
     */
    val SOURCE: String = """
        # GENERATED-FREE: this file is hand-written Python. See PythonxAdapter.kt.
        #
        # `pythonx` -- the adaptation layer over the Kotlin declarations the upcall table exposes.
        #
        # `docs/pythonx-adapter-design.md` §7 draws the line this file lives on: *if it differs per Kotlin
        # declaration it is generated or resolved at run time; if it is the same rule for every declaration
        # it is `pythonx` Python source.* Nothing here mentions a Kotlin declaration by name. What arrives
        # per declaration is the table `PythonxAdapter.renderTable` emits into `_register_table`, and the
        # only per-library knowledge is the package map and the value-class allowlist, both of which are
        # data and both of which a consumer can extend at run time.

        import importlib.machinery as _machinery
        # Read once per crossing, never per invocation: `_positional_capacity` asks it how many
        # arguments a callable takes at the moment the callable becomes a Kotlin `FunctionN`, and the
        # answer is closed over by the thunk.
        import inspect as _inspect
        import sys as _sys
        import types as _types

        __path__ = []  # a package with no directory: submodules come from _Finder below



        # --------------------------------------------------------------------------- the raw boundary

        _BOUNDARY = {}


        def _no_release(_handle):
            # A host that bound no `_pm_release` gets the behaviour that existed before anything released:
            # a leak, rather than a NameError raised inside `__del__` where nothing can act on it. Same
            # policy as `PythonProxySource._pm_no_release`.
            return 0


        def _boundary():
            if not _BOUNDARY:
                _main = _sys.modules['__main__']
                _resolve_fn = getattr(_main, '_pm_resolve', None)
                _invoke_fn = getattr(_main, '_pm_invoke', None)
                if _resolve_fn is None or _invoke_fn is None:
                    raise RuntimeError(
                        'the raw upcall entry points are not bound: define _pm_resolve(name_bytes) -> '
                        'handle and _pm_invoke(handle, args_tuple) -> result before installing pythonx'
                    )
                _BOUNDARY['resolve'] = _resolve_fn
                _BOUNDARY['invoke'] = _invoke_fn
                _BOUNDARY['release'] = getattr(_main, '_pm_release', _no_release)
            return _BOUNDARY


        def _resolve(kotlin_name):
            # `bytes`, not `str`: desktop reaches its resolver through
            # `ctypes.CFUNCTYPE(c_long, c_char_p)` and `c_char_p` refuses a `str` outright. Every other
            # bootstrap accepts both. Same reasoning as `PythonProxySource._pm_lookup`.
            handle = _boundary()['resolve'](kotlin_name.encode('utf-8'))
            if handle == -1:
                raise AttributeError('no exposed Kotlin declaration named ' + kotlin_name)
            return handle


        # --------------------------------------------------------------------------- the table

        class _Decl:
            '''One row of what `PythonxAdapter.renderTable` sent, plus its resolved handle.'''

            __slots__ = (
                'kotlin_name', 'package', 'leaf', 'base', 'suffix', 'arity', 'kind', 'is_suspend',
                'param_names', 'param_tags', 'param_type_names', 'return_tag', 'return_type_name',
                'is_extension', 'receiver_type_name', 'param_has_default', 'handle',
                'composer_index', 'changed_slots', 'default_slots', 'return_supertypes',
            )

            def __init__(self, row):
                self.update(row)

            def update(self, row):
                # In place, and reusing the object: everything already adapted -- a `_Binding` in a module
                # dict, a `_Hybrid` on a proxy type -- points at this object, and a table reinstall must
                # refresh what they see rather than leave them looking at the previous epoch's row.
                (self.kotlin_name, self.arity, self.kind, self.is_suspend, self.param_names,
                 self.param_tags, self.param_type_names, self.return_tag, self.return_type_name,
                 self.is_extension, self.receiver_type_name, self.param_has_default) = row
                # The declared return type and what it **is a** travel in one string; see
                # `_split_supertypes`. Split here, once, so that everything downstream -- `_wrap`,
                # `_pythonx_type_name`, every message -- keeps seeing exactly the name it did before.
                self.return_type_name, self.return_supertypes = _split_supertypes(self.return_type_name)
                self.package, _, self.leaf = self.kotlin_name.rpartition('.')
                self.base, _, self.suffix = self.leaf.partition('__')
                self.handle = None
                # A `@Composable`, recognised by the slot no ordinary declaration has. Nothing was
                # added to `ExposedCallable` to say so: `${'$'}composer` is the Compose compiler's own
                # spelling, a dollar is not a Python identifier character, and only `ArtifactScanner`'s
                # composable path ever emits it -- so the name *is* the flag, and one that cannot
                # drift out of step with the slot it describes.
                names = self.param_names or ()
                self.composer_index = names.index('${'$'}composer') if '${'$'}composer' in names else -1
                self.changed_slots = tuple(i for i, n in enumerate(names) if n.startswith('${'$'}changed'))
                self.default_slots = tuple(i for i, n in enumerate(names) if n.startswith('${'$'}default'))

            def declared_arity(self):
                '''The slots a Python caller writes: everything before `${'$'}composer`.

                The synthetic ones are real parameters of the JVM method and real slots of the
                binding -- that is the whole mechanism -- but they are never the caller's to fill.
                '''
                return self.arity if self.composer_index < 0 else self.composer_index

            def python_name(self):
                return to_python_name(self.leaf)

            def signature(self):
                parts = []
                for index in range(self.declared_arity()):
                    name = self.param_names[index] if self.param_names else 'a' + str(index)
                    if name == '<receiver>':
                        continue
                    type_name = self.param_type_names[index] if self.param_type_names else self.param_tags[index]
                    part = to_python_name(name) + ': ' + _simple_name(type_name)
                    if self.omittable(index):
                        # The same `= ...` a `.pyi` writes, and for the same reason: a refusal that
                        # listed an optional parameter as if it were required would send the caller
                        # looking for a value they never had to supply.
                        part += ' = ...'
                    parts.append(part)
                return to_python_name(self.leaf) + '(' + ', '.join(parts) + ')'

            def omittable(self, index):
                '''Whether slot [index] may be left out of a call.

                Read from `ExposedCallable.paramHasDefault`, which is a statement about the **binding**
                and not quite about the Kotlin declaration: it marks the slots the generated Kotlin
                body has a call expression for that does not mention them. Those are the same set for
                everything the walker binds today, and they part company where the generator declined
                to enumerate the omission sets -- see `ArtifactScanner.MAX_OMITTABLE_PARAMETERS`. The
                binding's answer is the one that matters here, because it is the one that decides
                whether Kotlin will actually reach a default.
                '''
                if not self.param_has_default or index >= len(self.param_has_default):
                    return False
                return bool(self.param_has_default[index])

            def bound_handle(self):
                if self.handle is None:
                    self.handle = _resolve(self.kotlin_name)
                return self.handle


        _TABLE = {}          # kotlin fqn -> _Decl
        _MODULES = []        # the pythonx.* modules the loader has built
        _BY_PACKAGE = {}     # kotlin package -> {python name -> [_Decl]}
        _BY_RECEIVER = {}    # kotlin receiver type -> {python name -> [_Decl]}
        _PACKAGES_SEEN = set()
        _SUPERTYPES = {}     # kotlin type name -> the types it is a, nearest first


        # What `ArtifactRendering.SUPERTYPE_SEPARATOR` writes between a declared return type and its
        # ancestry. Neither character is legal in a Kotlin fully-qualified name, which is the same
        # property `${'$'}composer` and `_COMPOSABLE_MARK` rely on: the string is the flag, and it cannot
        # drift out of step with the thing it describes.
        _SUPERTYPE_SEPARATOR = '<:'


        def _split_supertypes(type_name):
            '''`'A<:B<:C'` -> `('A', ('B', 'C'))`. A name with no ancestry comes back unchanged.

            Only a *walked jar* can answer what a type is a subtype of -- it is the one producer with
            the class files open -- so this is written by `ArtifactScanner` and by nothing else. A KSP
            fragment's rows have no separator in them and reach the same code path with an empty
            ancestry, which is the truth rather than a special case.
            '''
            if not type_name or _SUPERTYPE_SEPARATOR not in type_name:
                return type_name, ()
            parts = type_name.split(_SUPERTYPE_SEPARATOR)
            return parts[0], tuple(parts[1:])


        def _is_a(declared, wanted):
            '''Whether a value declaring type [declared] may fill a slot declared [wanted].

            Nominal equality, widened by exactly the ancestry the table carries and by nothing else.
            There is no rule here for a type nothing produced -- an entry appears in `_SUPERTYPES`
            only because some bound declaration *returns* it, which is the only way a value of it can
            reach a slot in the first place.
            '''
            return declared == wanted or wanted in _SUPERTYPES.get(declared, ())


        def _register_table(rows):
            '''Called by the generated fragment. Per-declaration data, produced by Kotlin, consumed here.

            Idempotent, and **destructive of everything already adapted**, because a reinstall is a new
            table: `CallableHandle` packs the table epoch precisely so that a handle cached across one is
            detected rather than silently invoked against a different function, and every name this layer
            has resolved is such a cached handle. `UpcallTable.install` bumps that epoch; this is the other
            half of that contract.
            '''
            _invalidate()
            _BY_PACKAGE.clear()
            _BY_RECEIVER.clear()
            _PACKAGES_SEEN.clear()
            _SUPERTYPES.clear()
            present = set()
            for row in rows:
                kotlin_name = row[0]
                decl = _TABLE.get(kotlin_name)
                if decl is None:
                    decl = _Decl(row)
                    _TABLE[kotlin_name] = decl
                else:
                    decl.update(row)
                present.add(kotlin_name)
                # Keyed by the type rather than by the declaration: an ancestry is a fact about a
                # type, and several declarations routinely return the same one. Written once and
                # never merged -- two producers of one type read it off the same class file, so a
                # disagreement would be a walker bug rather than something to reconcile here.
                if decl.return_supertypes and decl.return_type_name not in _SUPERTYPES:
                    _SUPERTYPES[decl.return_type_name] = decl.return_supertypes
                _index(_BY_PACKAGE.setdefault(decl.package, {}), decl)
                if decl.is_extension and decl.receiver_type_name:
                    _index(_BY_RECEIVER.setdefault(decl.receiver_type_name, {}), decl)
                segments = decl.package.split('.')
                for count in range(1, len(segments) + 1):
                    _PACKAGES_SEEN.add('.'.join(segments[:count]))
            for gone in [name for name in _TABLE if name not in present]:
                del _TABLE[gone]
            # Here rather than at the end of this file: `_PACKAGES_SEEN` is empty until this
            # function has run, and a module `PythonProxySource` created before it will never reach
            # the finder -- CPython answers from `sys.modules` first. Called on every table change
            # so the two halves meet whichever order they arrive in.
            adapt_existing_modules()


        def _invalidate():
            '''Drops every handle, adapted name and attached method, so the next read resolves again.'''
            for decl in _TABLE.values():
                decl.handle = None
            for module in _MODULES:
                for name in module._pythonx_adapted:
                    module.__dict__.pop(name, None)
                module._pythonx_adapted = []
            for cls in _PROXY_TYPES.values():
                for name in cls._pythonx_attached:
                    type.__delattr__(cls, name)
                cls._pythonx_attached = []
            # The host rebinds `_pm_resolve`/`_pm_invoke` per interpreter and, in this repository's
            # fixture, per test. Re-reading them costs one attribute lookup per install.
            _BOUNDARY.clear()


        def _index(table, decl):
            # Two keys per declaration: the base name, which an overload set shares, and the explicit
            # `name__Types` spelling, which is always exactly one declaration. `docs/kotlin-extensions-in-
            # python.md` §3.1 -- the explicit name is the caller saying which, and it has to keep working
            # whether or not the dispatcher can decide.
            table.setdefault(to_python_name(decl.base), []).append(decl)
            if decl.suffix:
                table.setdefault(to_python_name(decl.leaf), []).append(decl)


        def bound_names():
            '''Every Kotlin fully-qualified name the adapter knows about.'''
            return list(_TABLE)


        # --------------------------------------------------------------------------- names (§3)

        _KOTLIN_PRIMITIVES = frozenset((
            'kotlin.Byte', 'kotlin.Short', 'kotlin.Int', 'kotlin.Long', 'kotlin.Float', 'kotlin.Double',
            'kotlin.Boolean', 'kotlin.Char', 'kotlin.String', 'kotlin.Unit', 'kotlin.Any',
        ))


        def to_python_name(kotlin_name):
            '''Kotlin -> Python, the forward direction, which is the one the `.pyi` generator runs.

            A name that starts with an upper-case letter is a type, an object, an enum entry or a
            composable, and stays PascalCase; everything else is a function, a method, a property or a
            parameter, and becomes snake_case. The `__Types` suffix of an overload is a list of Kotlin type
            names and is not touched.
            '''
            base, sep, suffix = kotlin_name.partition('__')
            if not base or base[0].isupper():
                return kotlin_name
            out = []
            for index, ch in enumerate(base):
                if ch.isupper():
                    previous = base[index - 1] if index else ''
                    following = base[index + 1] if index + 1 < len(base) else ''
                    # A boundary is where a run of capitals starts or where it ends: `zIndex` -> `z_index`,
                    # `toURLString` -> `to_url_string`.
                    if index and (not previous.isupper() or (following and not following.isupper())):
                        out.append('_')
                    out.append(ch.lower())
                else:
                    out.append(ch)
            return ''.join(out) + sep + suffix


        def to_kotlin_name(python_name):
            '''Python -> Kotlin, by rule alone.

            **This is not injective and the adapter does not rely on it.** `to_url_string` comes back as
            `toUrlString`, which is not a declaration anybody wrote. It is the last resort under
            `kotlin_name_for`, which consults the index the forward rule built first -- that index is the
            "map of exceptions" `docs/pythonx-adapter-design.md` §3 asks the plugin to emit, except that it
            is derived from the table at run time and so cannot drift from it.
            '''
            base, sep, suffix = python_name.partition('__')
            if not base or base[0].isupper():
                return python_name
            parts = base.split('_')
            head = parts[0]
            tail = ''.join(part[:1].upper() + part[1:] for part in parts[1:])
            return head + tail + sep + suffix


        def kotlin_name_for(kotlin_package, python_name):
            '''The Kotlin declaration a Python name in [kotlin_package] means, or `None`.

            Index first, rule second. `docs/pythonx-adapter-design.md` §3's invariant -- every name the
            stub generator emits must resolve through the adapter -- is a statement about this function.
            '''
            decls = _BY_PACKAGE.get(kotlin_package, {}).get(python_name)
            if decls:
                if len(decls) == 1:
                    return decls[0].kotlin_name
                return [decl.kotlin_name for decl in decls]
            candidate = kotlin_package + '.' + to_kotlin_name(python_name)
            if candidate in _TABLE:
                return candidate
            return None


        def _simple_name(qualified):
            if not qualified:
                return '?'
            # A function-typed slot's name is a signature rather than a classifier, so its last
            # dotted component is the *return* type: `kotlin.Function3@Composable(…)->kotlin.Unit`
            # would print as `Unit`, which names the one part of it a reader does not need. Rendered
            # as Kotlin instead, because every message this appears in is telling somebody what they
            # should have passed. (`_function_slot` is defined below; the name is resolved when this
            # runs, which is after the module is built.)
            slot = _function_slot(qualified)
            if slot is not None:
                composable, args, returns = slot[1], slot[2], slot[3]
                return ('@Composable ' if composable else '') + '(' + \
                    ', '.join(_simple_name(arg) for arg in args) + ') -> ' + _simple_name(returns)
            return qualified.rpartition('.')[2]


        def _declared_type_name(value):
            '''The Kotlin type name an owned OBJECT value declares, from whichever owner produced it.

            Two owners exist, and neither's shape is optional, so this does not assume either one.

            `PythonProxySource`'s `_PmObject` -- the owner a *walked* or KSP-rendered function result
            comes back wrapped in -- is deliberately **one shared class for every handle it owns**;
            its own KDoc's cost table is why (a per-type subclass was ~2--9x more expensive to
            construct and destruct, measured). Being shared, it cannot carry the type name on the
            *class*, so `_pm_own` stamps it on the *instance* instead (`_pm_type`).

            A `pythonx` proxy (`_proxy_type`) is the opposite on purpose: it is one class **per**
            Kotlin type, because `_attach` needs a distinct class to hang each receiver's extension
            methods off (`_BY_RECEIVER` is keyed on it). That is already known at class-definition
            time, so it is a *class* attribute (`_pythonx_type_name`), not an instance one.

            Before this, `_coerce` read `type(value)._pythonx_type_name` unconditionally and crashed
            -- `AttributeError: type object '_PmObject' has no attribute '_pythonx_type_name'` --
            the first time a walked result (an `_PmObject`) was passed into a `pythonx`-adapted
            call, because `_PmObject` never had that attribute and was never going to: giving it one
            would mean a class per type, which is the cost `_PmObject` exists to avoid.
            '''
            declared = getattr(type(value), '_pythonx_type_name', None)
            if declared is not None:
                return declared
            return getattr(value, '_pm_type', None)


        # --------------------------------------------------------------------------- packages (§5b)

        # **No namespace is renamed here, and there is no map that could.** A Kotlin package is
        # importable under its own name -- `from androidx.compose.foundation.layout import Row` --
        # because a binder that exported `androidx.*` as `pythonx.*` would be deciding what a
        # Python package is called, which is the Python package's decision and nobody else's.
        #
        # `pythonx-compose` is a real distribution with real modules on disk. Its code imports the
        # Kotlin surface under the Kotlin name and restructures it into something Pythonic --
        # `Modifier.padding(16).background(...)` as method chaining, `to_dp(x)` where Python cannot
        # attach a method to `int` (`docs/kotlin-extensions-in-python.md` §4.1). This file used to
        # synthesise `pythonx.*` itself, with `__path__ = []` on every module it made, which took
        # those names *and* stopped the real package's files from ever being found. Both are gone.

        # --------------------------------------------------------------------------- value classes (§4.4)

        # Empty, and injected only. A value class that may be written as its raw primitive is a
        # fact about the library that declares it -- `pythonx-compose` says `Dp` may, and this file
        # has no business knowing that Dp exists. `allow_raw_primitive` below is the whole seam.
        _VALUE_CLASS_ALLOWLIST = set()


        def allow_raw_primitive(kotlin_type_name):
            '''Extend the allowlist. Manual by design: whether a wrapper *packs* has no bytecode witness.'''
            _VALUE_CLASS_ALLOWLIST.add(kotlin_type_name)


        # ------------------------------------------------------------------- callables (§6)

        _FUNCTION_PREFIX = 'kotlin.Function'

        # What `ArtifactScanner.functionSlotTypeName` appends to a slot whose declared
        # `kotlin.FunctionN` was lowered by the Compose plugin into a JVM `FunctionN+2`. Not a legal
        # character in a Kotlin fully-qualified name, which is the same trick `${'$'}composer` plays one
        # layer up: the name is the flag, and it cannot drift out of step with the slot it describes.
        _COMPOSABLE_MARK = '@Composable'


        # `kotlin.Function3@Composable(androidx.compose.foundation.layout.ColumnScope)->kotlin.Unit`:
        # what separates the types the lambda is *invoked with* from the one it has to give back.
        # Neither character can occur in a Kotlin fully-qualified name, so the grammar is unambiguous
        # for the same reason `@Composable` is. `ArtifactScanner.FUNCTION_RETURNS` is the writer.
        _FUNCTION_RETURNS = '->'


        def _function_slot(type_name):
            '''`(jvm_arity, composable, arg_type_names, return_type_name)`, or `None`.

            The arity is the **compiled** one -- what the object will be invoked with -- because that
            is what decides which `FunctionN` interface Kotlin has to hand back. For a lowered
            composable lambda the last two of those are the `${'$'}composer` and the `${'$'}changed` Compose
            appends to a function *type* exactly as it appends them to a function, and are not among
            [arg_type_names]: those are the declaration's own, the ones Python is given.

            `None` for anything without a signature payload, which is how the walker spells a slot it
            could not fully describe -- a type argument that is a type *parameter* has no name a proxy
            could be built over, so the slot stays an ordinary object handle nothing can fill.
            '''
            if not type_name or not type_name.startswith(_FUNCTION_PREFIX):
                return None
            open_at = type_name.find('(')
            close_at = type_name.find(')', open_at + 1) if open_at >= 0 else -1
            if open_at < 0 or close_at < 0:
                return None
            if type_name[close_at + 1:close_at + 1 + len(_FUNCTION_RETURNS)] != _FUNCTION_RETURNS:
                return None
            head = type_name[len(_FUNCTION_PREFIX):open_at]
            composable = head.endswith(_COMPOSABLE_MARK)
            if composable:
                head = head[:-len(_COMPOSABLE_MARK)]
            if not head.isdigit():
                return None
            inner = type_name[open_at + 1:close_at]
            args = tuple(inner.split(',')) if inner else ()
            return (int(head), composable, args, type_name[close_at + 1 + len(_FUNCTION_RETURNS):])


        def _is_python_callable(value):
            '''A Python callable that is not already something the boundary can carry.

            An `int` is excluded because it is the raw boundary's own currency for an object handle,
            and a proxy is excluded because it already owns a Kotlin object -- either could in
            principle be callable, and neither is what this path is for.
            '''
            if getattr(value, '_pm_handle', None) is not None:
                return False
            if isinstance(value, int):
                return False
            return callable(value)


        # How a Kotlin type named in a function slot's signature is marshalled into the invocation.
        # The tags are `python.multiplatform.reflection.TypeTag`'s own names, and this list is sent to
        # Kotlin so that both sides marshal from one decision rather than two -- see
        # `PythonFunction.marshalAn`.
        _ARG_TAGS = {
            'kotlin.Byte': 'INT', 'kotlin.Short': 'INT', 'kotlin.Int': 'INT', 'kotlin.Long': 'INT',
            'kotlin.Float': 'FLOAT', 'kotlin.Double': 'FLOAT',
            'kotlin.Boolean': 'BOOLEAN', 'kotlin.String': 'STRING',
        }


        def _arg_tag(kotlin_type_name):
            '''How one forwarded argument crosses. Anything not a primitive is a handle.

            Deliberately **not** `_KOTLIN_PRIMITIVES`: that set contains `kotlin.Any` and
            `kotlin.Unit`, neither of which has a marshalling rule -- an `Any` argument is whatever
            Kotlin put in it and can only travel as a handle, which is what OBJECT means.
            '''
            return _ARG_TAGS.get(kotlin_type_name, 'OBJECT')


        def _positional_capacity(fn, available):
            '''How many of [available] positional arguments [fn] will take, or `None` for "not any".

            A Kotlin `ColumnScope.() -> Unit` is written `{ Text(...) }` as often as `{ scope -> }`,
            and a `(Float) -> Unit` is written `{ }` when the value is not wanted; a Python author
            writing `lambda: Text('hi')` is making the same statement, and it was the *only* thing
            they could write until this slot forwarded anything. So the arguments a callable does not
            declare are dropped rather than forced on it, and a callable that wants **more** than the
            slot supplies is refused -- there is no value to give it, and the failure would otherwise
            arrive from inside Compose on a later recomposition.

            Measured by binding rather than by counting `__code__.co_argcount`: a bound method, a
            `functools.partial` and a callable object all answer correctly through `signature` and
            none of them through the code object. A callable `signature` cannot describe at all (a C
            builtin) is handed everything, which is what it would have got before this existed.
            '''
            try:
                sig = _inspect.signature(fn)
            except (TypeError, ValueError):
                return available
            probe = (None,) * available
            for count in range(available, -1, -1):
                try:
                    sig.bind(*probe[:count])
                except TypeError:
                    continue
                return count
            return None


        def _adapt_arguments(values, owners, wanted):
            '''The values Kotlin sent, given owners, and cut down to what the callable asked for.

            **Every** value is wrapped before any is dropped, and that order is the whole of the
            lifetime rule for a forwarded object: Kotlin roots it in `HandleTable` and does not
            release it, because a callback is allowed to keep what it was handed. The proxy's
            `__del__` is what gives it back -- so an argument the callable did not declare must still
            be given a proxy, which then dies here at the end of this call. Truncating first would
            leak one `HandleTable` entry per invocation, and a composition invokes its content again
            on every recomposition.
            '''
            adapted = [_wrap(value, owner) for value, owner in zip(values, owners)]
            return adapted[:wanted]


        def _callable_thunk(fn, composable, arg_types, wanted):
            '''Wraps a Python callable in whatever its slot's invocation convention needs.

            For a **composable** slot that is the composer: the push and the pop are here rather than
            on the Kotlin side because the composer stack is this module's, and reaching it from
            Kotlin would be two more boundary crossings per invocation -- one to resolve
            `push_composer` and one to call it -- for a `try/finally` Python can write directly.

            **The composer is the one this invocation was given**, not the one that was current when
            the lambda crossed. Compose invokes a stored content lambda again on later
            recompositions, with whatever composer is current then; a thunk that closed over the
            creating composer would be pushing a position that no longer exists.

            For a **plain** slot with nothing to adapt -- no arguments, or none that is an object and
            none to drop -- the callable is handed over as it is, so the case that already worked
            keeps costing exactly what it did.
            '''
            owners = tuple(t if _arg_tag(t) == 'OBJECT' else None for t in arg_types)
            if composable:
                def _thunk(composer, *values):
                    _COMPOSER.append(composer)
                    try:
                        return fn(*_adapt_arguments(values, owners, wanted))
                    finally:
                        _COMPOSER.pop()
                return _thunk
            if not any(owners) and wanted == len(arg_types):
                return fn
            def _plain(*values):
                return fn(*_adapt_arguments(values, owners, wanted))
            return _plain


        # Returned by `_coerce` for a callable during a *trial* bind. `_Overloads` binds every
        # candidate to find out which one matches, and building a wrapper for a candidate that then
        # loses would hand Kotlin a Python reference nothing ever calls -- held until the composition
        # ends, because the scope is what releases it. So the wrapper is built only in the strict
        # bind, and `_Overloads` re-binds its winner strictly before calling.
        _CALLABLE_PENDING = object()


        def _code_key(code):
            '''One code object, described **by value**, so that two compilations of one source agree.

            This is the part `id()` cannot do, and the reason is `PythonComposition`: it `exec`s a
            source *string* on every composition pass, and `exec` compiles. A `lambda` written in that
            string therefore gets a brand-new code object every pass -- CPython's constant-sharing,
            which is what makes two evaluations of one `lambda` statement share a code object, only
            holds within a single compilation. So the code object's address changes every frame and
            the only thing that does not is what the code *is*.

            `co_code` alone is not it: `lambda: Text('hi')` and `lambda: Text('bye')` compile to
            identical bytecode and differ only in `co_consts`. Position alone is not it either, for
            the same reason -- two different sources both have a `<string>` line 4. The two together,
            plus the names and the argument shape, are.

            Constants are recursed into rather than `repr`ed, because a nested function's constant is
            a code object whose `repr` carries its address -- which would make a `content=` that
            itself declares a `content=` fail to intern for a reason with nothing to do with what it
            does.
            '''
            parts = [
                getattr(code, 'co_qualname', None) or code.co_name,
                code.co_filename,
                str(code.co_firstlineno),
                str(code.co_flags),
                str(code.co_argcount) + '/' + str(code.co_posonlyargcount) + '/' +
                str(code.co_kwonlyargcount),
                code.co_code.hex(),
                repr(code.co_names), repr(code.co_varnames),
                repr(code.co_freevars), repr(code.co_cellvars),
            ]
            for const in code.co_consts:
                # `hasattr(const, 'co_code')` rather than an `isinstance` against `types.CodeType`:
                # this module imports nothing it does not have to, and a code object is the only
                # constant that answers to it.
                parts.append(_code_key(const) if hasattr(const, 'co_code') else repr(const))
            return '\x1f'.join(parts)


        def _intern_key(value, slot):
            '''What makes two crossings **the same callable in the same slot**, or `''` for "cannot say".

            ### The measurement this answers

            `RecompositionAccumulationTest` composes one body twelve times and counted, before this
            existed, twelve wrappers, twelve `HandleTable` roots and twelve Python references held --
            for one `content=`. All of it comes back at disposal, so it is not a leak; it is a live UI
            growing linearly in frames, and a UI recomposes several times a second.

            ### Why identity is not the criterion

            `content=lambda: Text('hi')` builds a **new function object on every pass**, so `id(value)`
            is a fresh number every time and interns nothing at all -- and that is the spelling every
            example in this module uses. Two lambdas written at one source position are nonetheless
            the same callable in every sense that matters here, and CPython says so structurally: the
            code object is stored in the enclosing code's constants and is therefore *one* object for
            the life of the module. So the key is built out of what the callable is made of:

            | part | why it is in the key |
            |---|---|
            | `__code__`, **by value** (`_code_key`) | the body. Its *address* is no good: `PythonComposition` `exec`s a source string, so every pass compiles a new code object |
            | `__globals__` | the same source in two modules is two callables |
            | `__closure__` cell **contents** | `lambda: Text(n)` for two `n`s does two things |
            | `__defaults__`, `__kwdefaults__` | same, spelled differently |
            | `__self__` for a bound method | `a.on_click` and `b.on_click` are not interchangeable |
            | the slot | a wrapper carries its invocation convention: a composable `content` threads a composer and an `onClick` does not, and one object cannot be both |

            Captured values are compared **by address, not by `==`**, so a capture that changed to an
            equal-but-distinct object misses and gets its own wrapper. That is the conservative
            direction: a miss costs a wrapper, a wrong hit renders a stale frame.

            ### Why addresses are sound here and would not be in a plain memo

            An `id()` is only unambiguous while its object is alive, and CPython reuses addresses
            eagerly. Every key this returns is stored by `PythonCallableScope`, whose entry roots a
            wrapper that holds the callable -- which holds its cells, its cell *contents*, its
            defaults, its globals and its receiver. Nothing a live key names can have been freed, so
            no two distinct callables can collide. A cache that did not hold its keys' objects alive
            could not make that argument, which is why this table lives on the Kotlin scope. (The
            code object is the exception and is keyed by value; see `_code_key` for why it has to be.)

            The one case where a cell can outlive what the key said it held is a rebound variable in
            a shared enclosing frame -- and there the *cached* callable's cell is the same cell, so it
            sees the new value too and the two agree anyway.

            ### What returns `''`

            A callable with no `__code__` at all -- a C builtin, a `functools.partial`, an instance
            with `__call__` -- and a closure cell that is still empty (a recursive definition being
            defined). Those get a wrapper per crossing, which is what everything got before.
            '''
            func = value
            receiver = ''
            inner = getattr(value, '__func__', None)
            if inner is not None:
                # A bound method is a *fresh object* on every attribute access, so its own `id` is
                # useless; the pair that identifies it is the underlying function and the receiver.
                receiver = 's' + str(id(getattr(value, '__self__', None)))
                func = inner
            code = getattr(func, '__code__', None)
            if code is None:
                return ''
            parts = ['c' + _code_key(code), 'g' + str(id(getattr(func, '__globals__', None))), receiver]
            closure = getattr(func, '__closure__', None)
            if closure:
                for cell in closure:
                    try:
                        parts.append('z' + str(id(cell.cell_contents)))
                    except ValueError:
                        # An empty cell: the callable is not fully defined yet, so nothing here can
                        # describe what it will do.
                        return ''
            defaults = getattr(func, '__defaults__', None)
            if defaults:
                parts.extend('d' + str(id(d)) for d in defaults)
            kwdefaults = getattr(func, '__kwdefaults__', None)
            if kwdefaults:
                parts.extend('k' + name + ':' + str(id(kwdefaults[name])) for name in sorted(kwdefaults))
            # `repr` of the slot rather than its arity: two `Function3@Composable`s differ only in the
            # receiver type they forward, which is what decides whether an argument is wrapped as a
            # `ColumnScope` proxy or a `RowScope` one.
            parts.append('#' + repr(slot))
            return '|'.join(parts)


        def _make_function(value, slot):
            '''Hands [value] to Kotlin as a `FunctionN`, and returns the handle of the wrapper.

            The wrapper belongs to the enclosing `PythonCallables` scope, which is the composition's:
            Compose stores a `content` in the slot table and calls it on later recompositions, long
            after the statement that wrote `content=lambda: ...` dropped Python's last reference to
            it. A bare handle is what comes back, which is exactly what an OBJECT slot takes.

            **The scope is asked for an existing wrapper first**, and that lookup is a separate entry
            point rather than a flag on `newFunction` so that a hit costs one crossing and nothing
            else: `_positional_capacity` runs `inspect.signature`, which is the most expensive thing
            on this path, and `_callable_thunk` allocates a closure. A recomposing composition takes
            the hit path on every pass after the first, so those are exactly the costs worth not
            paying twelve times for one `content=`.

            A **miss** pays for the lookup on top of everything it paid before -- one `_resolve` and
            one `invoke` that answer nothing. That is the trade, and no wall-clock figure is claimed
            for either side of it (`agent-rules` §11): what is measured here is allocation, in
            `RecompositionAccumulationTest`, and the machine that ran it was not idle.
            '''
            jvm_arity, composable, arg_types, _return_type = slot
            key = _intern_key(value, slot)
            if key:
                found = _boundary()['invoke'](_resolve('pythonx.runtime.findFunction'), (key,))
                if found:
                    return found
            wanted = _positional_capacity(value, len(arg_types))
            if wanted is None:
                raise TypeError(
                    'this slot invokes its callable with ' + str(len(arg_types)) + ' argument(s) (' +
                    ', '.join(_simple_name(t) for t in arg_types) + '), which ' +
                    getattr(value, '__name__', repr(value)) + ' does not accept'
                )
            body = _callable_thunk(value, composable, arg_types, wanted)
            return _boundary()['invoke'](
                _resolve('pythonx.runtime.newFunction'),
                (body, jvm_arity, composable, ','.join(_arg_tag(t) for t in arg_types), key),
            )


        def _is_value_class_over_primitive(tag, type_name):
            # The table cannot say "value class" -- but a parameter whose marshalling tag is a primitive
            # while its *declared* type is not a Kotlin primitive is one, and that is enough. `Dp` is
            # FLOAT/`androidx.compose.ui.unit.Dp`; `zIndex`'s parameter is FLOAT/`kotlin.Float`.
            if type_name is None:
                return False
            if tag not in ('INT', 'FLOAT', 'BOOLEAN', 'STRING'):
                return False
            return type_name not in _KOTLIN_PRIMITIVES


        # --------------------------------------------------------------------------- proxies

        class _ValueProxy:
            '''`dp(16)` -- a value class held in Python, unwrapped to its underlying primitive on the way in.'''

            __slots__ = ('kotlin_type_name', 'raw')

            def __init__(self, kotlin_type_name, raw):
                self.kotlin_type_name = kotlin_type_name
                self.raw = raw

            def __repr__(self):
                return _simple_name(self.kotlin_type_name) + '(' + repr(self.raw) + ')'


        def value_of(kotlin_type_name, raw):
            '''Wrap `raw` as the value class `kotlin_type_name`.

            The generic form of what used to be a `dp()` function sitting here. Naming a Compose
            type in this file made `pythonx.dp` a name the *binder* owned, which put every other
            library one core edit away from the same convenience. A package defines its own
            spellings on top of this -- `pythonx-compose` is where `dp` belongs.
            '''
            return _ValueProxy(kotlin_type_name, raw)


        _PROXY_TYPES = {}
        _EMPTY_FACTORIES = {}


        def register_empty(kotlin_type_name, kotlin_factory_name):
            '''Where a chain starts.

            `Modifier` as an *expression* is `Modifier.Companion`, an object instance, and the artefact
            walker binds functions -- so the empty modifier has no bound name and `pythonx` cannot invent
            one. Registering the factory by name is the seam; a proxy type with none refuses the
            class-object spelling with a message that says so rather than guessing.
            '''
            _EMPTY_FACTORIES[kotlin_type_name] = kotlin_factory_name


        class _BoundMember:
            '''An extension applied to a receiver: literally the module-level callable with slot 0 filled.'''

            __slots__ = ('_fn', '_receiver', '__name__')

            def __init__(self, fn, receiver, name):
                self._fn = fn
                self._receiver = receiver
                self.__name__ = name

            def __call__(self, *args, **kwargs):
                return self._fn(self._receiver, *args, **kwargs)


        class _Hybrid:
            '''`Modifier.padding(16)` and `m.padding(16)`, from one descriptor.

            `docs/pyi-generation-design.md` §4.3 measured the metaclass alternative failing at run time: a
            plain `def` on a metaclass is a *non-data* descriptor, so `type.__getattribute__` searches the
            class's own MRO first and `Modifier.padding(16)` binds `16` to `self`. A descriptor in the class
            body is found for both spellings and is told which one it is by `obj`.
            '''

            __slots__ = ('_fn', '_name')

            def __init__(self, fn, name):
                self._fn = fn
                self._name = name

            def __get__(self, obj, owner=None):
                if obj is None:
                    obj = owner.empty()
                return _BoundMember(self._fn, obj, self._name)


        class _ProxyMeta(type):

            def __getattr__(cls, name):
                # Only reached for a name the class does not have yet; once `_attach` puts a `_Hybrid` in
                # the class dict, ordinary lookup answers and this never runs again for that name.
                if name.startswith('_'):
                    raise AttributeError(name)
                if not _attach(cls, name):
                    raise AttributeError(
                        'no Kotlin extension named ' + name + ' on ' + cls._pythonx_type_name
                    )
                return getattr(cls, name)

            def __repr__(cls):
                return "<pythonx proxy for '" + cls._pythonx_type_name + "'>"


        def _attach(cls, python_name):
            '''Installs one extension as a method on [cls], and reports whether there was one.'''
            decls = _BY_RECEIVER.get(cls._pythonx_type_name, {}).get(python_name)
            if not decls:
                return False
            setattr(cls, python_name, _Hybrid(_callable_for(python_name, decls), python_name))
            cls._pythonx_attached.append(python_name)
            return True


        def _proxy_type(kotlin_type_name):
            '''The Python class that owns a handle to a Kotlin [kotlin_type_name], created once.'''
            existing = _PROXY_TYPES.get(kotlin_type_name)
            if existing is not None:
                return existing

            release = _boundary()['release']

            def __init__(self, handle):
                self._pm_handle = handle

            def __del__(self, _release=release):
                # The other half of `HandleTable`'s contract, and the thing
                # `docs/kotlin-extensions-in-python.md` §3.2 recorded as missing: "three handles leak per
                # run of that test, deliberately, because owning them is what §4.1's proxy is for and
                # §4.1's proxy does not exist yet." This is that proxy.
                handle = getattr(self, '_pm_handle', None)
                if handle is not None:
                    _release(handle)

            def __getattr__(self, name):
                if name.startswith('_'):
                    raise AttributeError(name)
                if not _attach(type(self), name):
                    raise AttributeError(
                        'no Kotlin extension named ' + name + ' on ' + type(self)._pythonx_type_name
                    )
                return getattr(self, name)

            def __repr__(self):
                return '<' + _simple_name(kotlin_type_name) + ' handle=' + repr(self._pm_handle) + '>'

            @classmethod
            def empty(cls):
                factory = _EMPTY_FACTORIES.get(kotlin_type_name)
                if factory is None:
                    raise TypeError(
                        'no empty ' + _simple_name(kotlin_type_name) + ' is bound: call '
                        "pythonx.register_empty('" + kotlin_type_name + "', '<kotlin function name>') "
                        'or start the chain from a value Kotlin handed you'
                    )
                return _wrap(_boundary()['invoke'](_resolve(factory), ()), kotlin_type_name)

            body = {
                '__init__': __init__,
                '__del__': __del__,
                '__getattr__': __getattr__,
                '__repr__': __repr__,
                'empty': empty,
                '_pythonx_type_name': kotlin_type_name,
                '_pythonx_attached': [],
                '__qualname__': _simple_name(kotlin_type_name),
            }
            cls = _ProxyMeta(_simple_name(kotlin_type_name), (object,), body)
            _PROXY_TYPES[kotlin_type_name] = cls
            return cls


        def _wrap(result, kotlin_type_name):
            '''A `TypeTag.OBJECT` result: a handle integer, given an owner.'''
            if kotlin_type_name is None or not isinstance(result, int):
                return result
            return _proxy_type(kotlin_type_name)(result)


        # --------------------------------------------------------------------------- calling

        def _coerce(value, tag, type_name, decl, slot, strict):
            '''One argument on its way in. Returns the marshalled value, or raises when `strict`.'''
            if isinstance(value, _ValueProxy):
                if type_name is not None and value.kotlin_type_name != type_name:
                    return _refuse(
                        strict,
                        'expected ' + _simple_name(type_name) + ' but got ' +
                        _simple_name(value.kotlin_type_name),
                    )
                return value.raw
            if tag == 'OBJECT':
                # A Kotlin function type is the one OBJECT slot Python can fill with something it
                # made itself. Everything else in this branch requires a handle, because a Kotlin
                # object is the only thing a Kotlin parameter can hold; a function is the exception
                # because Kotlin can be given one that calls back.
                slot = _function_slot(type_name)
                if slot is not None and _is_python_callable(value):
                    # A Python callable cannot stand in for a lambda that has to give something back.
                    # `kotlin.Function1` is the spelling of both `(Float) -> Unit` and
                    # `(Float) -> Boolean`, and only the return type in the name tells them apart:
                    # `TypeTag` has one INT for `Byte` through `Long`, so nothing on either side can
                    # say which boxed type Kotlin will cast the answer to, and guessing would be a
                    # `ClassCastException` inside Compose rather than a refusal here.
                    if slot[3] != 'kotlin.Unit':
                        return _refuse(
                            strict,
                            'a Kotlin lambda returning ' + _simple_name(slot[3]) + ' cannot be '
                            'written in Python yet: the boundary carries one INT for every integral '
                            'width, so the type Kotlin would cast the result to is not recoverable',
                        )
                    if not strict:
                        return _CALLABLE_PENDING
                    return _make_function(value, slot)
                if slot is not None and getattr(value, '_pm_handle', None) is None:
                    # A function slot and something that is not callable. Refused with the signature
                    # rather than falling through to "expected a handle": Kotlin will never hand a
                    # `FunctionN` back out for Python to pass in again, so a handle is not the answer
                    # here and saying so would send the caller looking for one.
                    return _refuse(strict, 'expected a callable of ' + _simple_name(type_name))
                handle = getattr(value, '_pm_handle', None)
                if handle is not None:
                    declared = _declared_type_name(value)
                    # `declared is None` means the owner could not say what it holds -- an `_PmObject`
                    # built by a rendered *class*'s `__init__` (no `_pm_own` call, so no `_pm_type`)
                    # rather than by a function's owned result. That is not a refusal: a bare `int`
                    # handle a few lines below is already trusted with no type check at all in strict
                    # mode, so an owned-but-untyped value gets the same trust rather than a stricter
                    # rule than the boundary's own currency.
                    # Subtyping, not equality: `BitmapPainter` fills a `Painter` slot. The ancestry
                    # is the value's own -- carried on the declaration that produced it, read off the
                    # jar by `ArtifactScanner.nameablePublicSupertypesOf` -- so nothing here has to
                    # know a hierarchy it cannot see. A value with no ancestry recorded is still
                    # matched by name alone, which is what every KSP-produced row is.
                    if type_name is not None and declared is not None and not _is_a(declared, type_name):
                        return _refuse(
                            strict,
                            'expected ' + _simple_name(type_name) + ' but got ' + _simple_name(declared),
                        )
                    return handle
                if isinstance(value, int) and not isinstance(value, bool):
                    # A bare handle, the raw boundary's own currency -- accepted when the caller already
                    # said which declaration they meant, and **not** allowed to vote in a dispatch: an
                    # integer is indistinguishable from a number, so letting it match an OBJECT slot would
                    # make every arity-matched overload a candidate for `padding(m, 16)`.
                    if strict:
                        return value
                    return _refuse(strict, 'a bare handle cannot select an overload')
                return _refuse(strict, 'expected a ' + _simple_name(type_name) + ' handle')
            if tag in ('INT', 'FLOAT'):
                if isinstance(value, bool) or not isinstance(value, (int, float)):
                    return _refuse(strict, 'expected a number for ' + _simple_name(type_name or tag))
                if _is_value_class_over_primitive(tag, type_name):
                    if type_name not in _VALUE_CLASS_ALLOWLIST:
                        # §4.4: a packed value class decodes a raw number as something else entirely and
                        # renders wrong without raising. `TextUnit(16)` is `Unspecified`, not 16 of
                        # anything. Refusing is the only answer that reports itself.
                        return _refuse(
                            strict,
                            'a raw number is not accepted for ' + _simple_name(type_name) +
                            ': it is a value class and not on pythonx allowlist, so ' + repr(value) +
                            ' would be reinterpreted rather than converted',
                        )
                return float(value) if tag == 'FLOAT' else int(value)
            if tag == 'BOOLEAN':
                if not isinstance(value, bool):
                    return _refuse(strict, 'expected a bool')
                return value
            if tag == 'STRING':
                if not isinstance(value, str):
                    return _refuse(strict, 'expected a str')
                return value
            if tag == 'BYTES':
                if not isinstance(value, (bytes, bytearray)):
                    return _refuse(strict, 'expected bytes')
                return bytes(value)
            return value


        class _Mismatch(Exception):
            pass


        _NO_MATCH = object()


        def _refuse(strict, message):
            if strict:
                raise _Mismatch(message)
            return _NO_MATCH


        # ------------------------------------------------------------------- @Composable (§5)

        # One composer per composition, pushed by the single hand-written Kotlin entry point and
        # popped when it returns. A list rather than a scalar because a composition can nest --
        # a Python composable that calls another Kotlin container that calls back into Python --
        # and the innermost is the one a call belongs to.
        _COMPOSER = []


        # Compose's own `BITS_PER_INT`: one `${'$'}default` bit per parameter, 31 to an `int`. Every
        # composable measured (281 of them, `ComposableBindingTest`) fits one word, so the second
        # word is written by the arithmetic and not by anything that has been observed.
        _DEFAULT_BITS_PER_WORD = 31


        def push_composer(handle):
            '''Called from Kotlin, from inside a composition, with a handle to the live `Composer`.

            This is the one value Python cannot invent: `${'$'}composer` exists only inside a composition,
            which is why one hand-written `@Composable` entry point exists at all. Everything else
            about calling a composable -- which arguments were written, what mask that implies -- is
            arithmetic Python does here.

            **The handle is the caller's to keep alive.** Nothing here retains it, so a caller that
            pushes a proxy and drops its last reference has released the composer; the next call
            through the boundary then fails with "stale or unknown Kotlin object handle". Kotlin's
            entry point holds it for the composition's lifetime by construction, which is the only
            caller this is written for.
            '''
            # Normalised to the raw boundary currency here rather than at every use: Kotlin pushes an
            # integer handle, but a Python caller that got its composer out of a bound declaration
            # holds a proxy wrapping one, and the `${'$'}composer` slot is filled without going through
            # `_coerce` (there is nothing to decide about it).
            _COMPOSER.append(getattr(handle, '_pm_handle', handle))
            return len(_COMPOSER)


        def pop_composer():
            _COMPOSER.pop()
            return len(_COMPOSER)


        def current_composer():
            if not _COMPOSER:
                raise RuntimeError(
                    'no composer is in scope: a @Composable can only run inside a composition, and '
                    'nothing has pushed one. Call it from the body Kotlin passed to the composable '
                    'entry point.'
                )
            return _COMPOSER[-1]


        def _absent(tag):
            '''What goes in a slot the `${'$'}default` mask says the callee will overwrite.

            It is never read -- the generated prologue of every composable assigns over it before its
            first use, which is what the mask *means* -- but it still has to survive the boundary and
            the thunk's unboxing. A primitive slot therefore gets a zero of the right shape rather
            than `None`, which would reach `Number.intValue()` and raise.
            '''
            if tag == 'INT':
                return 0
            if tag == 'FLOAT':
                return 0.0
            if tag == 'BOOLEAN':
                return False
            return None


        def _bind_composable(decl, args, kwargs, strict):
            '''`_bind` for a `@Composable`, where omission is a bitmask and not a sentinel.

            ### The encoding, and how it was checked

            `${'$'}default` bit *i* set means "parameter *i* was not passed, use its declared default".
            Read out of the callee rather than assumed: `javap -c androidx/compose/material3/TextKt`
            shows `Text-fLXpl1I` opening with

                iload ${'$'}default; iconst_2; iand; ifeq +10
                getstatic androidx/compose/ui/Modifier.Companion
                astore_1

            -- `${'$'}default & 2` guarding the assignment to parameter 1 (`modifier`), `& 4` guarding
            parameter 2 (`color`), `& 8` parameter 3, and so on with no gaps. Bits are assigned to
            *every* parameter in declaration order, including ones that declare no default (`text`
            owns bit 0 and nothing ever sets it), so the bit index is the parameter index and needs
            no correction.

            ### Except for a receiver, which is the one correction

            `${'$'}default` bits are assigned over **value** parameters, and an extension composable's
            receiver is not one. Measured the same way, on `androidx/compose/material3/NavigationBarKt`
            -- `RowScope.NavigationBarItem(selected, onClick, icon, modifier = …)` guards its
            `modifier` with `${'$'}default & 8`, the **4th** value parameter's bit, although `modifier`
            is slot 4 of the binding once the receiver is counted. The receiver is given bit **31**
            instead (`& -2147483648` in the prologue), which nothing here ever sets because a receiver
            cannot be omitted.

            `${'$'}changed` numbers the other way -- the receiver *is* slot 0 there -- and it does not
            matter, because `${'$'}changed` is passed as 0. That is the conservative value: it is a
            per-call-site claim about which arguments the *caller* knows to be unchanged, and this
            caller knows nothing. The callee then computes it with `composer.changed(...)` itself,
            which is the branch its prologue takes when `${'$'}changed & mask == 0`.
            '''
            declared = decl.declared_arity()
            if len(args) > declared:
                return _refuse(strict, 'takes ' + str(declared) + ' arguments, got ' + str(len(args)))
            slots = list(args) + [_NO_MATCH] * (decl.arity - len(args))
            # How far a `${'$'}default` bit index sits behind its slot index. See the docstring: 1 for an
            # extension composable, 0 otherwise.
            bit_offset = 1 if decl.is_extension else 0
            if kwargs:
                for key, value in kwargs.items():
                    index = -1
                    for slot in range(declared):
                        # `<receiver>` is deliberately not a Python identifier, so no keyword can name
                        # it -- but `to_python_name` is not asked to make sense of one either.
                        if decl.param_names[slot] == '<receiver>':
                            continue
                        if to_python_name(decl.param_names[slot]) == key:
                            index = slot
                            break
                    if index < 0:
                        return _refuse(strict, 'has no parameter named ' + key)
                    if slots[index] is not _NO_MATCH:
                        return _refuse(strict, 'got two values for ' + key)
                    slots[index] = value
            mask = [0] * len(decl.default_slots)
            omitted = 0
            for index in range(declared):
                value = slots[index]
                if value is _NO_MATCH or value is None:
                    if not decl.omittable(index):
                        missing = decl.param_names[index] if decl.param_names else 'argument ' + str(index)
                        return _refuse(strict, 'no value for ' + to_python_name(missing))
                    word, bit = divmod(index - bit_offset, _DEFAULT_BITS_PER_WORD)
                    if word >= len(mask):
                        return _refuse(strict, 'no ${'$'}default word covers parameter ' + str(index))
                    mask[word] |= 1 << bit
                    slots[index] = _absent(decl.param_tags[index] if decl.param_tags else 'OBJECT')
                    omitted += 1
                    continue
                tag = decl.param_tags[index] if decl.param_tags else 'OBJECT'
                type_name = decl.param_type_names[index] if decl.param_type_names else None
                coerced = _coerce(value, tag, type_name, decl, index, strict)
                if coerced is _NO_MATCH:
                    return _NO_MATCH
                slots[index] = coerced
            slots[decl.composer_index] = current_composer()
            for index in decl.changed_slots:
                slots[index] = 0
            for position, index in enumerate(decl.default_slots):
                slots[index] = mask[position]
            return (tuple(slots), omitted)


        def _bind(decl, args, kwargs, strict):
            '''Maps a Python call onto [decl]'s positional slots.

            Returns `(slots, defaults_used)`, or `_NO_MATCH` when not `strict`; raises `_Mismatch`
            when `strict`.

            `defaults_used` is how many slots this call left to Kotlin, and it exists for `_Overloads`
            -- see there. It is not a diagnostic.
            '''
            if decl.composer_index >= 0:
                return _bind_composable(decl, args, kwargs, strict)
            if len(args) > decl.arity:
                return _refuse(strict, 'takes ' + str(decl.arity) + ' arguments, got ' + str(len(args)))
            slots = list(args) + [_NO_MATCH] * (decl.arity - len(args))
            if kwargs:
                if not decl.param_names:
                    return _refuse(strict, 'the producer supplied no parameter names, so it is positional only')
                for key, value in kwargs.items():
                    index = -1
                    for slot, name in enumerate(decl.param_names):
                        if name != '<receiver>' and to_python_name(name) == key:
                            index = slot
                            break
                    if index < 0:
                        return _refuse(strict, 'has no parameter named ' + key)
                    if slots[index] is not _NO_MATCH:
                        return _refuse(strict, 'got two values for ' + key)
                    slots[index] = value
            defaults_used = 0
            for index, value in enumerate(slots):
                omittable = decl.omittable(index)
                if value is _NO_MATCH or (value is None and omittable):
                    if not omittable:
                        missing = decl.param_names[index] if decl.param_names else 'argument ' + str(index)
                        return _refuse(strict, 'no value for ' + to_python_name(missing))
                    # `None` is the whole mechanism, and it is not a value being passed: the generated
                    # Kotlin body tests `args[i] == null` and takes a branch whose call expression does
                    # not mention this parameter at all, so the *compiler* supplies the default.
                    # `docs/pythonx-adapter-design.md` §4.5 -- metadata carries the flag and never the
                    # expression, so this is the only place the default value can come from.
                    #
                    # It costs nothing that was previously possible, and the reason is `_coerce` rather
                    # than the walker: `_coerce` refuses `None` for every tag it knows -- 'expected a
                    # str' for STRING, 'expected a bool' for BOOLEAN, a handle or a callable for OBJECT
                    # -- so no `None` a caller wrote in a slot has ever reached Kotlin as a *value*.
                    # `resolveKotlinType` narrows it further by declining a nullable number, a nullable
                    # Boolean and a nullable value class outright; a nullable `String`/`ByteArray` is
                    # bound (`nullablePrimitiveBoundaryTypeOf`, which is what makes `Modifier.clickable`
                    # reachable) and is the one case where "omitted" is the only way to spell `null`
                    # from `pythonx`. That is exact for a slot whose Kotlin default *is* `null`, which
                    # every such slot measured so far has, and it is a refusal rather than a wrong
                    # value for one that is not.
                    slots[index] = None
                    defaults_used += 1
                    continue
                tag = decl.param_tags[index] if decl.param_tags else 'OBJECT'
                type_name = decl.param_type_names[index] if decl.param_type_names else None
                coerced = _coerce(value, tag, type_name, decl, index, strict)
                if coerced is _NO_MATCH:
                    return _NO_MATCH
                slots[index] = coerced
            return (tuple(slots), defaults_used)


        class _Binding:
            '''One Kotlin declaration, resolved once, callable from Python.'''

            __slots__ = ('_decl', '__name__', '__qualname__')

            def __init__(self, decl):
                self._decl = decl
                self.__name__ = decl.python_name()
                self.__qualname__ = decl.kotlin_name

            def __call__(self, *args, **kwargs):
                decl = self._decl
                try:
                    bound, _ = _bind(decl, args, kwargs, True)
                except _Mismatch as mismatch:
                    raise TypeError(decl.signature() + ': ' + str(mismatch)) from None
                return _wrap(
                    _boundary()['invoke'](decl.bound_handle(), bound),
                    decl.return_type_name if decl.return_tag == 'OBJECT' else None,
                )


        class _Overloads:
            '''The dispatcher `docs/kotlin-extensions-in-python.md` §3.1 says has to live here.

            The walker refuses to arbitrate between overloads because it has only a name to go on; Python
            has the arguments. Selection is on argument count, on keyword names, and on declared type --
            and when that still does not separate them, this refuses and names the candidates rather than
            picking one, which is the same rule the walker applies for the same reason.

            **Defaults made almost every call ambiguous, so there is one more rule.** Once
            `padding(horizontal =, vertical =)` accepts a single argument, `padding(m, 16)` binds against
            it, against `padding(all =)` and against `padding(start =, top =, end =, bottom =)` -- three
            candidates for a call that has exactly one obvious meaning. The tie-break is Kotlin's own and
            not an invention here: **a candidate that fills no default beats one that does**, and the
            comparison extends to a count so that it is a total order. `Modifier.padding(16.dp)` resolves
            to `padding(all:)` in Kotlin for the same reason it does here.

            Where the fewest-defaults score is *equal*, nothing has changed: this still refuses and names
            the candidates. It also parts company with `kotlinc` in one direction and does so knowingly --
            Kotlin reports `padding()` with no arguments as ambiguous between the two- and four-`Dp`
            overloads, where this picks the two-`Dp` one because it fills fewer. Both reach
            `padding(0.dp, 0.dp)`; refusing a call every candidate agrees about would be the worse answer.
            '''

            __slots__ = ('_decls', '__name__')

            def __init__(self, name, decls):
                self._decls = decls
                self.__name__ = name

            def __call__(self, *args, **kwargs):
                matched = []
                for decl in self._decls:
                    bound = _bind(decl, args, kwargs, False)
                    if bound is not _NO_MATCH:
                        matched.append((decl, bound[0], bound[1]))
                if matched:
                    fewest = min(entry[2] for entry in matched)
                    matched = [entry for entry in matched if entry[2] == fewest]
                if len(matched) == 1:
                    decl = matched[0][0]
                    # Re-bound strictly, and the trial binding above is discarded. A trial bind is a
                    # question -- *would* this candidate accept these arguments -- and a coercion
                    # with a side effect must not answer it: `_coerce` hands a Python callable to
                    # Kotlin, and doing that for a candidate that then loses would leave a Python
                    # reference held for the life of the composition with nothing ever calling it.
                    # `_CALLABLE_PENDING` is what a trial bind puts in such a slot instead, and this
                    # is why it never reaches the boundary.
                    bound, _ = _bind(decl, args, kwargs, True)
                    return _wrap(
                        _boundary()['invoke'](decl.bound_handle(), bound),
                        decl.return_type_name if decl.return_tag == 'OBJECT' else None,
                    )
                candidates = ', '.join(decl.signature() for decl in self._decls)
                spellings = ', '.join(to_python_name(decl.leaf) for decl in self._decls)
                if not matched:
                    raise TypeError(
                        'no overload of ' + self.__name__ + ' accepts these arguments. Candidates: ' +
                        candidates + '. Call one by name: ' + spellings
                    )
                raise TypeError(
                    'the arguments to ' + self.__name__ + ' match more than one overload (' +
                    ', '.join(entry[0].signature() for entry in matched) +
                    '). Nothing arbitrates -- call one by name: ' + spellings
                )


        def _callable_for(python_name, decls):
            if len(decls) == 1:
                return _Binding(decls[0])
            return _Overloads(python_name, decls)


        # --------------------------------------------------------------------------- modules (§2.3)

        def _read_constant(decl):
            '''The current value of a `kind == 'STATIC_GETTER'` declaration -- invoked, not called.

            The body `_Binding.__call__` would run for a zero-argument call, minus the call: `args`
            is always empty (`CallableKind.STATIC_GETTER.hasReceiver` is `False` and the scanner
            gives it no parameters either), so there is nothing to `_bind`.
            '''
            return _wrap(
                _boundary()['invoke'](decl.bound_handle(), ()),
                decl.return_type_name if decl.return_tag == 'OBJECT' else None,
            )


        def _adapt(kotlin_package, python_name):
            '''The value or callable [python_name] means in [kotlin_package], and whether `_module_
            getattr` may freeze it into the module dict.

            A `kind == 'STATIC_GETTER'` decl is read rather than called (see `_read_constant`) and
            is never cacheable: `ArtifactScanner.constantsOf` binds every public property of an
            `object`/companion it can reach, `var` included, and carries nothing that says which are
            which. A `var`'s value can change between two reads -- an object holding a mutable
            counter, say -- so the generic "resolved once, then a module-dict hit" cache every other
            adapted name gets here is refused for this one kind, and every read re-enters Kotlin.
            '''
            decls = _BY_PACKAGE.get(kotlin_package, {}).get(python_name)
            if decls:
                if len(decls) == 1 and decls[0].kind == 'STATIC_GETTER':
                    return _read_constant(decls[0]), False
                return _callable_for(python_name, decls), True
            qualified = kotlin_package + '.' + python_name
            if python_name[:1].isupper() and (qualified in _BY_RECEIVER or qualified in _PROXY_TYPES):
                # A type, not a declaration: `pythonx.compose.ui.Modifier` is the receiver proxy.
                return _proxy_type(qualified), True
            candidate = kotlin_package + '.' + to_kotlin_name(python_name)
            if candidate in _TABLE:
                decl = _TABLE[candidate]
                if decl.kind == 'STATIC_GETTER':
                    return _read_constant(decl), False
                return _Binding(decl), True
            return None, False


        def _module_getattr(module, kotlin_package):
            def __getattr__(name):
                if name.startswith('__') and name.endswith('__'):
                    raise AttributeError(name)
                adapted, cacheable = _adapt(kotlin_package, name)
                if adapted is None:
                    raise AttributeError(
                        "module '" + module.__name__ + "' has no attribute '" + name +
                        "' (nothing named that is exposed from Kotlin package " + kotlin_package + ')'
                    )
                if not cacheable:
                    # A live property (see `_adapt`): every read has to re-enter Kotlin, so the name
                    # is deliberately left out of the module dict and this hook runs again next time.
                    return adapted
                # Adapted once. Every later read is an ordinary module-dict hit and this never runs again
                # for this name -- which is why the 551-587 ns figure for a live-property `__getattr__`
                # does not price this one.
                setattr(module, name, adapted)
                module._pythonx_adapted.append(name)
                return adapted

            return __getattr__


        def _module_dir(kotlin_package):
            def __dir__():
                names = set(_BY_PACKAGE.get(kotlin_package, {}))
                for type_name in _BY_RECEIVER:
                    package, _, leaf = type_name.rpartition('.')
                    if package == kotlin_package:
                        names.add(leaf)
                return sorted(names)

            return __dir__


        def _adapt_module(module, kotlin_package):
            '''Attach this layer's half to `module`, whoever created it.

            **One module object per Kotlin package, shared.** `PythonProxySource` puts proxy
            classes into `sys.modules` under the same Kotlin names this layer serves top-level
            declarations and extensions under -- and CPython returns a `sys.modules` hit before it
            consults any finder, so whichever of the two got there first used to win and the other
            was simply absent. That was invisible only while this layer answered to `pythonx.*`
            instead: two names for one package, and each half in a different one.

            **Re-attached rather than skipped when it is already adapted.** A module lives in
            `sys.modules` for the life of the interpreter, while a fresh `install()` builds a fresh
            table -- so a module adapted by an earlier install closes over dictionaries the current
            one has replaced, and every name in it resolves to nothing. This was invisible while
            the modules were called `pythonx.*`, because dropping that one root cleared them all;
            a module named after its Kotlin package has no such root to drop.
            '''
            module.__path__ = getattr(module, '__path__', [])
            module._pythonx_kotlin_package = kotlin_package
            # Names adapted by a previous install are stale with it; the fresh `__getattr__` below
            # re-resolves each on first use.
            for _stale in getattr(module, '_pythonx_adapted', ()):
                module.__dict__.pop(_stale, None)
            module._pythonx_adapted = []
            if kotlin_package is None:
                # A step on the way to a package that has bindings, carrying none of its own.
                return module
            _MODULES.append(module)
            module.__getattr__ = _module_getattr(module, kotlin_package)
            module.__dir__ = _module_dir(kotlin_package)
            return module


        def adapt_existing_modules():
            '''Adapt every already-imported module a bound package names.

            The finder below only ever sees a name `sys.modules` does not already hold, so a module
            `PythonProxySource` created first would never reach it. Called at install time, and
            again after any table change, so the two halves meet however they are ordered.
            '''
            for name in list(_PACKAGES_SEEN):
                module = _sys.modules.get(name)
                if module is not None:
                    _adapt_module(module, name)


        class _Loader:

            def __init__(self, kotlin_package):
                self._kotlin_package = kotlin_package

            def create_module(self, spec):
                return None  # the default module object is exactly right

            def exec_module(self, module):
                _adapt_module(module, self._kotlin_package)


        class _Finder:
            '''The half a module `__getattr__` cannot do.

            `import pythonx.compose.material3` fails *before* any attribute is touched, so laziness inside
            a module is not enough to make the module lazy. A finder answers the import, and the
            `__getattr__` the loader installs answers the names inside it -- two hooks,
            `docs/pythonx-adapter-design.md` §2.3.
            '''

            def find_spec(self, fullname, path=None, target=None):
                # The name asked for *is* the Kotlin package name. No translation, no prefix, no
                # map: `import androidx.compose.foundation.layout` resolves because that package
                # has bindings, and `import pythonx.compose.layout` does not resolve here at all --
                # that name belongs to a real distribution whose files are on `sys.path`, and this
                # finder is appended to `sys.meta_path` so the ordinary path finder answers first.
                #
                # A package nothing is bound under is not importable, so a typo is a
                # ModuleNotFoundError at the import rather than an AttributeError several lines
                # later. `_PACKAGES_SEEN` carries every ancestor of every bound declaration's
                # package, so intermediate segments resolve as containers on the way down.
                if fullname not in _PACKAGES_SEEN:
                    return None
                return _machinery.ModuleSpec(fullname, _Loader(fullname), is_package=True)


        def _install_finder():
            for finder in _sys.meta_path:
                if isinstance(finder, _Finder):
                    return
            _sys.meta_path.append(_Finder())


        _install_finder()
    """.trimIndent()

    /**
     * Puts [SOURCE] into `sys.modules` as the package `pythonx`, exactly once per interpreter.
     *
     * The module object is built here rather than by an import, because there is no file for an
     * import to find (see the class KDoc). `__path__ = []` is what makes it a *package*, which is
     * what lets `pythonx.compose.ui` be a submodule at all; the finder [SOURCE] installs answers
     * for those.
     *
     * Guarded on `sys.modules` rather than on a Kotlin flag: this repository's test fixture
     * finalizes and re-initialises the interpreter between tests, and a Kotlin flag would then say
     * "installed" about an interpreter that no longer exists.
     */
    private val DELIVERY: String = """
        import sys as _px_sys
        import types as _px_types

        if 'pythonx' not in _px_sys.modules:
            _px_mod = _px_types.ModuleType('pythonx')
            _px_mod.__path__ = []
            _px_sys.modules['pythonx'] = _px_mod
            exec(compile(_px_src, 'pythonx/__init__.py', 'exec'), _px_mod.__dict__)
            del _px_mod
        del _px_src, _px_sys, _px_types
    """.trimIndent()

    /**
     * Renders the whole installable source: [SOURCE], its delivery, and one row per entry.
     *
     * A pure function from entries to text, for the reason `PythonProxySource.render` is one --
     * `PythonxTableSourceTest` pins it in `commonTest` with no interpreter anywhere, so the
     * generated half is a readable artefact rather than a behaviour.
     */
    fun render(entries: List<ExposedCallable>): String {
        // The delivery route is an `r"""..."""` Python literal, so a triple double-quote inside
        // [SOURCE] would end it early and a trailing backslash would escape its closing quote.
        // Both would surface as a SyntaxError with a line number inside a string nobody can see.
        require(!SOURCE.contains("\"\"\"")) { "the pythonx source must not contain a triple double-quote" }
        require(!SOURCE.endsWith("\\")) { "the pythonx source must not end in a backslash" }
        return buildString {
            appendLine("_px_src = r\"\"\"")
            appendLine(SOURCE)
            appendLine("\"\"\"")
            appendLine(DELIVERY)
            appendLine()
            appendLine(renderTable(entries))
        }
    }

    /**
     * The generated half: one row per [ExposedCallable], and nothing else.
     *
     * Every field here is one `docs/pythonx-adapter-design.md` §2.4 recorded as *missing* from the
     * boundary -- parameter names, whether a slot is an extension receiver, the receiver's type, the
     * **declared** type of a parameter as opposed to its marshalling tag, and whether a parameter
     * has a default. They are on [ExposedCallable] now, and this is what carries them the last step,
     * into Python, where §2.4's three consequences are decided:
     *
     * 1. `paramNames` is what makes `padding(horizontal=8, vertical=4)` possible at all.
     * 2. `isExtension` + `receiverTypeName` is what lets an extension become a method on its
     *    receiver, and therefore what makes the chain work.
     * 3. `paramTypeNames` is what lets the allowlist be enforced in Python: a `FLOAT` tag over a
     *    declared type that is not `kotlin.Float` is a value class over a primitive, which is the
     *    only machine-checkable form of "this might pack".
     *
     * A third entry point (`_pm_describe`) on all five bootstraps would have been the alternative.
     * This costs no platform code and cannot disagree with the table, because it *is* the table.
     *
     * 4. `paramHasDefault` is what closes §4.5. It was carried and unused while every generated
     *    Kotlin body passed every argument; a walked body now carries one call expression per subset
     *    of its defaulted parameters and picks between them on `args[i] == null`
     *    (`ArtifactScanner.presenceBranchedCall`), so this column is the list of slots `_bind` may
     *    fill with that sentinel. Nothing about the row *shape* changed to do it, which is why it
     *    was worth carrying before anything read it.
     */
    fun renderTable(entries: List<ExposedCallable>): String = buildString {
        appendLine("import pythonx as _px_pythonx")
        appendLine("_px_pythonx._register_table((")
        entries.forEach { entry ->
            append("    (")
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
            appendLine("),")
        }
        appendLine("))")
        // Eager, and the only eager thing here: a target whose bootstrap publishes no entry points
        // must fail at install with the message that says so, rather than at the first attribute
        // read several files away. Everything else -- every handle, every proxy type, every module
        // -- is resolved on first use.
        appendLine("_px_pythonx._boundary()")
        append("del _px_pythonx")
    }

    /**
     * Renders the adapter for whatever [UpcallTable] currently holds and `exec`s it.
     *
     * @return the source that was executed, so a caller can log or inspect exactly what ran.
     */
    fun install(rawPrimitiveValueClasses: Set<String> = emptySet()): String {
        // The adapter's own Kotlin service, registered by the layer that needs it rather than by
        // every consumer's table. Idempotent by `moduleName`, so a second install after a `clear()`
        // puts it back and one after a `register` does nothing. See [PythonCallables.Fragment].
        UpcallTable.register(PythonCallables.Fragment)
        val source = render(UpcallTable.entries())
        Python3.exec(source)
        // Applied after the adapter exists, because `allow_raw_primitive` is one of the names it
        // defines. Which value classes may be written as a raw primitive is a fact about the
        // library that declares them, so it is injected rather than known here.
        if (rawPrimitiveValueClasses.isNotEmpty()) {
            Python3.exec(
                (listOf("import pythonx") + rawPrimitiveValueClasses.map {
                    "pythonx.allow_raw_primitive(${it.pyStr()})"
                }).joinToString("\n")
            )
        }
        return source
    }

    /** A Python string literal for [this], quoted the way the rendered source quotes its own. */
    private fun String.pyStr(): String = "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun Boolean.py(): String = if (this) "True" else "False"

    private fun List<String>.tuple(): String = when (size) {
        0 -> "()"
        1 -> "(${this[0]},)"
        else -> "(${joinToString(", ")})"
    }

    private fun String.quoted(): String =
        "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"
}
