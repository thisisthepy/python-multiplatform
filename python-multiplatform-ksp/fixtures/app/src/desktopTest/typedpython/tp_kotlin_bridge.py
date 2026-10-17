# typedpython: compiled
"""Issue #45: compiled TypedPython code calling Kotlin through the binder.

Every name imported below is a binder proxy (`fixture/app/TypedPythonBridge.kt`, published by
`PythonProxySource`). In the IR they are opaque objects: `Global` reads them, `GetAttr` reaches the
proxy's method, `CallObject` calls them. The proxy comes from calling the Kotlin class itself
(`TpSample(...)`): a Kotlin *function* returning a Kotlin object hands Python a generic owner
without the class's methods (see `TypedPythonBridge.kt`). That makes both functions impure, so nothing in them may
deopt -- the floats and the `range()` indices around the calls stay native, everything crossing to
Kotlin is boxed.

`TypedPythonKotlinCallTest` imports this file twice in the embedded interpreter: as source
(interpreted) and as the extension `build_bridge.py` built from it against the embedded CPython's
headers (compiled), and compares the two.

The gate type-checks the imports against `fixture/app/__init__.pyi` beside this file, a hand-written
stub of the same Kotlin surface (the bindings plugin generates `.pyi` only for walked artefacts, not
for a KSP-scanned module). Nothing at run time reads that stub: the binder's modules are in
`sys.modules` before this file is loaded.
"""
from fixture.app import TpSample, tpAccumulate, tpCheck, tpTouch


def drive(n: int, scale: float, token: object) -> float:
    """Per step: a Kotlin constructor call that returns a typed proxy, a method call on that proxy,
    the proxy handed back to a Kotlin function, and a PyObject handed to Kotlin -- inside native
    float math."""
    total: float = 0.0
    for i in range(n):
        x: float = float(i) * scale + 0.5
        sample: TpSample = TpSample(i, x)
        w: float = float(sample.weigh(scale))
        total = total * 0.75 + float(tpAccumulate(sample, w * w + 1.0))
        tpTouch(token)
    return total


def checked(n: int, limit: float) -> float:
    """`tpCheck` throws in Kotlin once `acc` passes `limit`; the caller sees what Python sees."""
    acc: float = 0.0
    for i in range(n):
        acc = acc + float(i) * 1.5
        acc = float(tpCheck(acc, limit))
    return acc
