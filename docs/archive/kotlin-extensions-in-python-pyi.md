# Kotlin extensions in Python — the original `.pyi` section (archived)

> **Superseded** by [`docs/design/pyi-generation-design.md`](../design/pyi-generation-design.md) and
> [SPEC.md](../SPEC.md) B-7 on 2026-10-03; kept for history.
>
> This is §4.5 of `docs/design/kotlin-extensions-in-python.md` as it stood before 2026-10-03. It
> proposed that this repository's Gradle plugin emit a Pythonic stub — extension-as-method, the
> `Modifier` metaclass, `@overload` sets, `Dp | float` for allow-listed value classes. The plugin now
> emits Kotlin-named stubs with boundary-type annotations only, and the Pythonic stub product belongs
> to pythonx-compose. The three measured bullets (`@overload` is mandatory for 33 of 130 `Modifier`
> names; `Dp | float` versus the proxy alone; defaults as `= ...`) remain valid inputs for that
> product. Section references below are to `docs/design/kotlin-extensions-in-python.md` unless a
> path is given; the metaclass and Protocol measurements are in
> [`pyi-generation-pythonic-stubs.md`](pyi-generation-pythonic-stubs.md) §4.3–§4.4.

### 4.5 `.pyi`

Generation belongs to the Gradle plugin (`docs/design/ecosystem.md` §5b), and it reads the same metadata
as the binder, so the stub and the binding cannot drift. Extension-as-method appears as an ordinary
method, and the metaclass of §4.2 is expressible:

> **The stub below is the one that was measured to fail.** mypy resolves `Modifier.padding` to the
> instance method and rejects every class-object call; see §4.2's note and
> `docs/archive/pyi-generation-pythonic-stubs.md` §4.3. It is kept here because the three bullets that follow it are
> about `@overload`, `Dp | float` and defaults, and all three survive unchanged into the shape that
> does work — `padding: ClassVar[_Modifier_padding]` where `_Modifier_padding` is a Protocol whose
> `__call__` carries exactly these overloads (`docs/archive/pyi-generation-pythonic-stubs.md` §4.4). What changes is
> where the overloads are written, not which overloads there are.

```python
class _ModifierMeta(type):
    @overload
    def padding(cls, all: Dp | float) -> Modifier: ...
    @overload
    def padding(cls, horizontal: Dp | float = ..., vertical: Dp | float = ...) -> Modifier: ...
    @overload
    def padding(cls, padding_values: PaddingValues) -> Modifier: ...

class Modifier(metaclass=_ModifierMeta):
    @overload
    def padding(self, all: Dp | float) -> Modifier: ...
    ...
```

Three things follow from the measurements:

- **`@overload` is mandatory, not optional.** 33 of 130 `Modifier` names carry more than one
  overload, and they are the load-bearing ones (§3.1). A generator that emits one signature per
  name will mistype `padding`, `size`, `background`, `border` and `clickable`.
- **`Dp | float` is how the allowlist shows up in the stub**, and a type on the reject list is
  stubbed as the proxy alone. The stub is then the documentation for §4.4's asymmetry.
- **Defaults must be carried.** 58% of `Modifier` extension parameters declare one; a stub with
  everything required would be wrong about most of the API. Metadata's `declaresDefaultValue` is
  the source; the *value* is not in metadata, so stubs emit `= ...`.

