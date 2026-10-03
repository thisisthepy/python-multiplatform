package python.multiplatform.ffi.upcall

import python.multiplatform.reflection.ExposedCallable

/**
 * `python_multiplatform` -- the binder's own root module, and the one thing both installers share.
 *
 * ### Why it exists
 *
 * A Kotlin-named module (`androidx.compose.material3`, `kotlin.text`, a consumer's own package)
 * is importable under its Kotlin package name and nothing else: **a namespace is never renamed**,
 * no `pythonx` module is made (`docs/INTENT.md` §2.2, §2.3). Inside it, each declaration is reached
 * by its Kotlin name **and** by its Pythonic name, and takes keyword arguments by **either** spelling
 * of each parameter (issue #131):
 *
 * | Kotlin | also reachable as | rule |
 * |---|---|---|
 * | `rememberTextFieldState` | `remember_text_field_state` | snake_case of a lower-case-first name |
 * | `toURLString` | `to_url_string` | a run of capitals is one word |
 * | `paddingFromBaseline__TextUnit` | `padding_from_baseline__TextUnit` | an explicit overload key keeps its suffix |
 * | `Checkbox`, `Modifier`, `Alignment.End` | (unchanged) | upper-case first: types, objects, composables, constants |
 * | `onCheckedChange=` | `on_checked_change=` | a keyword is snake_case of the Kotlin parameter name |
 *
 * The rule is pythonx-compose 0.1.0a1's (`_reexport.py`'s `snake_case`/`python_name`), so a package
 * built on top sees the same spelling it always computed. An alias is served only when it is
 * unambiguous in its namespace -- one module, one proxy type (supertypes included), or one
 * declaration's parameters: it must not already be a Kotlin name there and no second Kotlin name may
 * map to it ([SOURCE]'s `pythonic_aliases`). An ambiguous alias is not served, and every Kotlin name
 * involved stays reachable as itself. `dir()` lists Kotlin names only.
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
 * | `describe(module, name)` | the declaration(s) a bound name of a Kotlin-named module stands for, read from the table and never by reading the name |
 * | `describe_member(type, name)` | the declaration(s) a member name of a Kotlin type stands for on its proxy -- an extension function or a property, supertypes included -- read from the table |
 * | `kotlin_function(raw, row)` | what [PythonProxySource] wraps each rendered function in: keyword arguments by Kotlin name, omitted defaults, and the metadata above |
 * | `signature_of(rows)` | the `inspect.Signature` for one declaration (`(*args, **kwargs)` for an overload set) |
 * | `snake_case(name)`, `python_name(name)` | the naming rule below, the one both installers and the stub generator apply |
 * | `pythonic_aliases(names)` | `{alias: kotlin_name}` for one namespace, ambiguous aliases left out |
 * | `keyword_slots(row)` | `{keyword: slot}` for one declaration: Kotlin parameter names and their aliases |
 * | `module_alias_getattr(module)` | the PEP 562 `__getattr__` [PythonProxySource] gives the modules it creates, serving the aliases |
 *
 * ### The public contract, for a Pythonic layer
 *
 * Every function the binder puts on a Kotlin-named module -- whichever installer put it there --
 * answers `inspect.signature(fn)` with the **Pythonic** keyword of each parameter (its snake_case
 * alias, or its Kotlin name where none is served), in declaration order. `describe()` carries both:
 * `name` is the Kotlin parameter name, `python_name` the keyword the signature shows. The signature
 * is the call surface Python tools read (`help()`, IDEs, `Signature.bind`), so it shows the spelling
 * Python code is written in; the description is the Kotlin declaration, so it keeps Kotlin's.
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
 *          {'name': 'checked', 'python_name': 'checked', 'type': 'kotlin.Boolean', 'tag': 'BOOLEAN',
 *           'has_default': False, 'value_class': False, 'composable_lambda': False},
 *          {'name': 'onCheckedChange', 'python_name': 'on_checked_change', ...},
 *          ...)}
 *
 * `python_multiplatform.describe(module, name)` describes **any** bound name of a Kotlin-named
 * module -- a function, an overload set's base name, an explicit `name__Types` spelling, and a
 * named constant (`kind: 'STATIC_GETTER'`, `returns` its declared type, no parameters) -- **without
 * evaluating it**: a constant's Kotlin getter is never invoked. One declaration answers one dict;
 * an overload set's base name answers the tuple `describe(fn)` gives for it. A name that is not a
 * bound declaration (a child package, a typo) raises `AttributeError`; `describe(fn)` with one
 * argument is unchanged. So `describe(androidx.compose.ui.Alignment, 'End')['returns']` is
 * `'androidx.compose.ui.Alignment.Horizontal'`.
 *
 * ### Members of a type: `describe_member(kotlin_type_name, kotlin_member_name)`
 *
 * What a member resolver needs to build a keyword map, and what `describe(module, name)` cannot
 * give it: an extension is declared in *its* package, which is not the receiver's, so the receiver's
 * type is the only key a resolver holds (issue #54). The answer has `describe()`'s shape -- a tuple of
 * dicts, one per declaration -- and is exactly what the proxy of that type serves under that name:
 *
 * - an **extension function** answers every declaration the name stands for on that type -- for an
 *   overload set's base name every candidate, for an explicit `name__Types` spelling that one;
 *   `describe_member('androidx.compose.ui.Modifier', 'padding')` lists the `padding` overloads with
 *   their Kotlin parameter names;
 * - a **property** (issue #38) answers its getter (`kind: 'GETTER'`, no parameters, `returns` its
 *   type) and, for a `var`, its setter (`kind: 'SETTER'`, one parameter, the value written). A member
 *   property and an extension property look the same here; `receiver` is the type it is read on;
 * - a name the type does not have is looked up on each type the table says it **is a**, nearest
 *   first -- the same lookup the proxy uses, so a description cannot disagree with the attribute;
 * - nothing is invoked: no getter runs, no handle is resolved;
 * - a member's Pythonic alias (`fill_max_width`) describes the Kotlin member it is served for, and so
 *   does `describe(module, alias)` for a module name;
 * - a name served on neither the type nor any supertype, or any name while the binding layer is not
 *   installed (it is what serves members on a proxy), raises `AttributeError`.
 *
 * ### Modules list and serve their children
 *
 * A Kotlin-named module's `dir()` lists its bound declarations, the receiver types under it, and
 * its **direct child packages and objects** (an object whose members are bound, `Alignment`, is a
 * package here). Each under its Kotlin name only -- the Pythonic aliases are served but not listed,
 * so `dir()` names each declaration once (and a layer that converts `dir()` by the same rule, as
 * pythonx-compose 0.1.0a1's `_name_table` does, sees no two entries for one name). Reading a child
 * as an attribute imports it, so `androidx.compose.ui.Alignment.End` works after `import androidx`
 * alone; a name nothing is bound under stays an `AttributeError`. A package segment is a namespace
 * and is never given an alias.
 *
 * `value_class` is true for a parameter whose marshalling tag is a primitive while its declared
 * type is not a Kotlin primitive (`Dp`, `Color`, `TextUnit`) -- the only machine-checkable form of
 * "this is a value class". `composable_lambda` is true for a `@Composable` function-typed slot.
 *
 * ### Member resolvers: the one hook for names on a proxy
 *
 * A proxy the binder returns (`Modifier.padding(16)` is one) serves its Kotlin members under their
 * Kotlin names and their Pythonic aliases. The order a name is looked up in is fixed: **the Kotlin
 * member of that name, then the registered resolvers, then the binder's own snake_case alias** -- so
 * a resolver can still decide what a name means, and with none registered the alias is served. A
 * Pythonic package built on the binder can say what else a member may be called:
 *
 *     python_multiplatform.binding.add_member_resolver(fn)
 *     python_multiplatform.binding.remove_member_resolver(fn)
 *
 *     fn(kotlin_type_name, requested_name, kotlin_member_names)
 *         -> kotlin_name | (kotlin_name, keyword_map) | None
 *
 * - `fn` is asked only when the proxy has **no** Kotlin member named `requested_name`; a Kotlin name
 *   never reaches it. `kotlin_member_names` is a tuple of the Kotlin member names the type has.
 * - The first resolver (in registration order) returning a name that is in `kotlin_member_names` wins,
 *   and that Kotlin member is served. Any other answer (`None`, an unknown name, a name starting with
 *   `_`) means "not mine"; with no resolver answering, the binder's own alias is served, and a name
 *   that is no alias either is the usual `AttributeError`.
 * - **Keyword maps.** An answer may be `(kotlin_name, keyword_map)`, `keyword_map` being
 *   `{python_kw: kotlinParam}`: it is applied when that member is called, and keywords it does not
 *   name pass through unchanged, to be matched as a Kotlin parameter name or its Pythonic alias (an
 *   unknown one is still the binding layer's `TypeError`). A map that turns `padding_values` into
 *   `paddingValues` is therefore harmless: the binder would have accepted either. For a member the proxy *does* have under its
 *   Kotlin name (`padding`), resolvers are asked only when a call to it passes keyword arguments, and
 *   only for its keyword map: an answer counts when it is `(that same name, keyword_map)`; a plain
 *   name or `None` means no map. A call with no keywords never asks. A tuple of any other shape is a
 *   `TypeError`.
 * - With no resolver registered, a proxy serves its Kotlin names and the binder's aliases (issue #131).
 * - Answers are cached in the resolver registry (cleared when a resolver is added or removed, and
 *   when a table is registered), never written onto a proxy class: `dir()` of a proxy and the class
 *   `__dict__` show Kotlin names only. Removing a resolver removes its aliases. The binder's own
 *   aliases follow the same rule: computed per type, cached beside the table, never written onto the
 *   class, so reading an alias costs a `__getattr__` miss every time.
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
        import re as _re
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
        _PROPERTY_KINDS = ('GETTER', 'SETTER')
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


        # ------------------------------------------------------------- Pythonic names (issue #131)
        #
        # A Kotlin-named module is reachable under its Kotlin package name and nothing else -- a
        # namespace is never renamed (`docs/INTENT.md` §2.2). Inside it, every declaration, member and
        # keyword parameter is reachable by its Kotlin name **and** by its Pythonic name. The rule is
        # the one pythonx-compose 0.1.0a1 uses (`pythonx/compose/_reexport.py`), character for
        # character, so a package built on top sees no change of spelling:
        #
        # - a name that starts upper-case (a type, an object, a `@Composable`, an enum entry, a
        #   PascalCase constant) is unchanged;
        # - any other name is snake_case, a run of capitals counting as one word
        #   (`fillMaxWidth` -> `fill_max_width`, `toURLString` -> `to_url_string`);
        # - an explicit overload key converts its base and keeps its `__Types` suffix
        #   (`paddingFromBaseline__TextUnit` -> `padding_from_baseline__TextUnit`);
        # - a keyword parameter is `snake_case` of its Kotlin name (`onCheckedChange` -> `on_checked_change`).
        #
        # An alias is served only when it is unambiguous in its namespace (`pythonic_aliases`): when it
        # is not already some Kotlin name there, and when no second Kotlin name maps to it. Otherwise
        # the Kotlin names are still the only spellings, and nothing is guessed.

        _LOWER_UPPER = _re.compile(r'([a-z0-9])([A-Z])')
        _ACRONYM_WORD = _re.compile(r'([A-Z]+)([A-Z][a-z])')


        def snake_case(kotlin_name):
            '''`fillMaxWidth` -> `fill_max_width`, `toURLString` -> `to_url_string`, `zIndex` -> `z_index`.'''
            return _LOWER_UPPER.sub(r'\1_\2', _ACRONYM_WORD.sub(r'\1_\2', kotlin_name)).lower()


        def python_name(kotlin_name):
            '''The one Pythonic spelling of a Kotlin declaration or member name.

            Upper-case names keep their Kotlin spelling. An explicit overload key (`padding__Dp`)
            converts its base and keeps the type suffix, which names Kotlin types.
            '''
            if not kotlin_name or kotlin_name[:1].isupper():
                return kotlin_name
            base, sep, suffix = kotlin_name.partition('__')
            return snake_case(base) + sep + suffix


        def pythonic_aliases(kotlin_names, rule=python_name):
            '''`{alias: kotlin_name}` for the names of one namespace, ambiguity refused.

            An alias is served when it differs from its Kotlin name, is a Python identifier, is not
            itself one of [kotlin_names] (that Kotlin name owns it), and exactly one Kotlin name maps
            to it. Two Kotlin names that map to one alias (`toURL` and `toUrl` -> `to_url`) serve no
            alias at all: both stay reachable by their Kotlin names only. Independent of order.
            '''
            names = set(kotlin_names)
            found = {}
            clashed = set()
            for kotlin in names:
                if not isinstance(kotlin, str):
                    continue
                alias = rule(kotlin)
                if alias == kotlin or alias in names or not alias.isidentifier():
                    continue
                other = found.get(alias)
                if other is None:
                    found[alias] = kotlin
                elif other != kotlin:
                    clashed.add(alias)
            for alias in clashed:
                del found[alias]
            return found


        def _parameter_aliases(names):
            '''`{kotlin_parameter: pythonic_keyword}` for one declaration's own parameter names.'''
            own = [name for name in names if name != _RECEIVER and not _is_synthetic(name)]
            return {kotlin: alias for alias, kotlin in pythonic_aliases(own, snake_case).items()}


        def keyword_slots(row):
            '''`{keyword: slot}` for one table row: each Kotlin parameter name, and its Pythonic alias.

            The receiver and the synthetic Compose slots answer to no keyword. An alias that collides
            with another parameter's Kotlin name, or that two parameters share, is not served
            (`pythonic_aliases`); those parameters are reached by their Kotlin names.
            '''
            names = row[_NAMES] or ()
            index = {}
            for slot, name in enumerate(names):
                if name == _RECEIVER or _is_synthetic(name):
                    continue
                index.setdefault(name, slot)
            for kotlin, alias in _parameter_aliases(names).items():
                index.setdefault(alias, index[kotlin])
            return index


        def _module_aliases(module):
            '''`{alias: kotlin_name}` for the names the proxy layer published on [module].

            Recomputed when the proxy layer renders a new table or the module gains a name, so an alias
            always reads the module's *current* Kotlin attribute and never a value cached from an
            older render.
            '''
            names = [name for name in module.__dict__ if not name.startswith('_')]
            rows = getattr(type(module), '_pm_kotlin_rows', None) or {}
            names.extend(name for name in rows if name not in module.__dict__)
            key = (_proxy_epoch, len(names))
            cached = module.__dict__.get('_pm_aliases')
            if cached is not None and cached[0] == key:
                return cached[1]
            aliases = pythonic_aliases(names)
            module.__dict__['_pm_aliases'] = (key, aliases)
            return aliases


        def module_alias_getattr(module):
            '''The PEP 562 `__getattr__` the proxy layer gives a module it creates: Pythonic aliases.

            Asked only for a name the module does not have. The binding layer, when it adapts the
            module, replaces this with its own `__getattr__`, which serves the same aliases from the
            table. The alias is never written into the module: every read goes to the Kotlin name.
            '''
            def __getattr__(name):
                if name.startswith('__') and name.endswith('__'):
                    raise AttributeError(name)
                kotlin = _module_aliases(module).get(name)
                if kotlin is None:
                    raise AttributeError("module '" + module.__name__ + "' has no attribute '" + name + "'")
                return getattr(module, kotlin)

            return __getattr__


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
            aliases = _parameter_aliases(names)
            parameters = []
            for index in range(row[_ARITY]):
                name = _field(names, index)
                if name is not None and (name == _RECEIVER or _is_synthetic(name)):
                    continue
                type_name = _field(types, index)
                tag = _field(tags, index)
                parameters.append({
                    'name': name,
                    # The keyword `inspect.signature` shows for it: its snake_case alias, or its Kotlin
                    # name where no alias is served (issue #131). Both are accepted by a call.
                    'python_name': aliases.get(name, name),
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
                # A property's receiver is not one of its slots (`CallableKind.GETTER`), but it is
                # still the type the property is read on -- the one fact a reader of a property needs.
                'receiver': row[_RECV] if row[_EXT] or row[_KIND] in _PROPERTY_KINDS else None,
                'returns': _declared_return(row[_RTYPE]),
                'composable': _COMPOSER in names,
                'content': last['name'] if last is not None and last['composable_lambda'] else None,
                'parameters': tuple(parameters),
            }


        _NO_NAME = object()


        def describe(fn, name=_NO_NAME):
            '''The Kotlin declaration(s) behind [fn]: a tuple of dicts, one per overload.

            [fn] is anything the binder put on a Kotlin-named module -- a function the proxy layer
            rendered, a callable or overload set the binding layer adapted, or an extension already
            applied to its receiver (`Modifier.padding`). See `KotlinSurface` for the dict's keys.

            `describe(module, name)` describes the bound Kotlin name [name] of a Kotlin-named
            [module] without reading it -- a `STATIC_GETTER` (`Alignment.End`) included, whose getter
            is never run. One declaration answers one dict; an overload set's base name answers the
            same tuple `describe(fn)` gives for it.
            '''
            if name is not _NO_NAME:
                return _describe_named(fn, name)
            rows = getattr(fn, '__kotlin_rows__', None)
            if rows is None:
                raise TypeError(repr(fn) + ' is not a Kotlin declaration the binder exposed')
            return tuple(_describe_row(row) for row in rows)


        def _describe_named(module, name):
            if not isinstance(module, type(_sys)):
                raise TypeError('describe(module, name) takes a Kotlin-named module, not ' + repr(module))
            if not isinstance(name, str):
                raise TypeError('describe(module, name): name must be a str')
            rows = None
            binding = _sys.modules.get(BINDING_MODULE)
            if binding is not None:
                rows = binding._rows_named(module.__name__, name)
            if rows is None:
                # The proxy layer's own, read without touching the attribute: a static property's row
                # is kept on the module's type (`PythonProxySource._pm_kotlin_rows`), and a rendered
                # function is found by `getattr_static`, which runs no descriptor -- so a constant's
                # Kotlin getter is never invoked here.
                row = (getattr(type(module), '_pm_kotlin_rows', None) or {}).get(name)
                if row is not None:
                    rows = (row,)
                else:
                    rows = getattr(_inspect.getattr_static(module, name, None), '__kotlin_rows__', None)
            if not rows:
                raise AttributeError(
                    "module '" + module.__name__ + "' has no bound Kotlin declaration named '" + name + "'"
                )
            described = tuple(_describe_row(row) for row in rows)
            return described[0] if len(described) == 1 else described


        def describe_member(kotlin_type_name, kotlin_member_name):
            '''The Kotlin declaration(s) the member [kotlin_member_name] of [kotlin_type_name] stands for.

            A tuple of dicts, `describe()`'s shape: every overload of an extension function, or a
            property's getter (and setter, for a `var`). Looked up on the type and then on every type
            the table says it is a, nearest first -- exactly what a proxy of that type serves under
            that name. Invokes nothing. `AttributeError` for a name the type is not served under.
            '''
            if not isinstance(kotlin_type_name, str) or not isinstance(kotlin_member_name, str):
                raise TypeError('describe_member(kotlin_type_name, kotlin_member_name) takes two str')
            binding = _sys.modules.get(BINDING_MODULE)
            rows = binding._member_rows(kotlin_type_name, kotlin_member_name) if binding is not None else None
            if not rows:
                raise AttributeError(
                    "Kotlin type '" + kotlin_type_name + "' has no member named '" + kotlin_member_name + "'" +
                    ('' if binding is not None else ' (the binding layer, which serves members, is not installed)')
                )
            return tuple(_describe_row(row) for row in rows)


        _GENERIC = _inspect.Signature([
            _inspect.Parameter('args', _inspect.Parameter.VAR_POSITIONAL),
            _inspect.Parameter('kwargs', _inspect.Parameter.VAR_KEYWORD),
        ])


        def _annotation(type_name):
            return type_name if type_name else _inspect.Parameter.empty


        def signature_of(rows, drop_receiver=False):
            '''The `inspect.Signature` of one declaration; `(*args, **kwargs)` for anything else.

            Each parameter is shown under its **Pythonic** keyword (`on_checked_change`), or under its
            Kotlin name where no alias is served (issue #131); a call accepts both spellings, and
            `describe` carries both (`name`, `python_name`).

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
            aliases = _parameter_aliases(names)
            shown = {}
            for name in names:
                alias = aliases.get(name)
                shown[name] = alias if alias is not None and not _keyword.iskeyword(alias) else name
            receiver_name = 'receiver' if 'receiver' not in shown.values() else '_receiver'
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
                name = shown[name]
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


        def bind_slots(row, args, kwargs, keywords=None):
            '''Positional and keyword arguments -> one value per slot of [row].

            A keyword is a Kotlin parameter name or its Pythonic alias ([keywords], `keyword_slots`
            when not given); writing both spellings of one parameter is "multiple values".

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
            if kwargs and keywords is None:
                keywords = keyword_slots(row)
            for key, value in kwargs.items():
                index = keywords.get(key, -1)
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
              other call is mapped onto the slots by Kotlin parameter name or Pythonic alias first
              (`bind_slots`; the keyword map is built on the first call that passes a keyword).
            '''
            name = row[_NAME]
            arity = row[_ARITY]
            composable = _COMPOSER in (row[_NAMES] or ())
            has_defaults = any(row[_DEFAULTS] or ())
            modules = _sys.modules
            default = KOTLIN_DEFAULT
            keywords = []

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
                if kwargs and not keywords:
                    keywords.append(keyword_slots(row))
                return raw(*bind_slots(row, args, kwargs, keywords[0] if keywords else None))

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
