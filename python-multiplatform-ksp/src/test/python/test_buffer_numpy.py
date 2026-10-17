"""numpy arrays are not compiled yet (#220): an `NDArray[...]` parameter is an ordinary object
parameter, so results and their numpy scalar types equal the interpreter's. Skipped without numpy."""
import textwrap

import pytest

np = pytest.importorskip("numpy")

from typedpython import cbuild, ir  # noqa: E402
from test_buffer_arrays import AD, F, build_source, differential, load_so  # noqa: E402

SOURCE = textwrap.dedent('''
    # typedpython: compiled
    import array
    import numpy as np
    import numpy.typing as npt


    def sum_ad(a: array.array[float]) -> float:
        s = 0.0
        for i in range(len(a)):
            s += a[i]
        return s


    def nd_first(x: npt.NDArray[np.float64]) -> object:
        return x[0]


    def nd_sum(x: npt.NDArray[np.float64]) -> object:
        s = 0.0
        for i in range(len(x)):
            s += x[i]
        return s
''')


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("bufnp")
    path, proved, diags, c = build_source(d, SOURCE)
    so = cbuild.build(c, path.stem, d / "out")
    return proved, load_so(so)


def test_numpy_parameter_is_an_ordinary_object_and_keeps_numpy_results(built):
    proved, m = built
    for f in proved.functions:
        assert all(not isinstance(p, ir.ArrayParam) or p.buffer == "array" or p.buffer is None
                   for p in f.params)
        assert not (f.name.startswith("nd_") and any(isinstance(p, ir.ArrayParam) for p in f.params))
    x = np.array(F, dtype=np.float64)
    r = m.nd_first(x)
    assert type(r) is np.float64 and r == F[0]
    r = m.nd_sum(x)
    ref = 0.0
    for v in x:
        ref += v
    assert type(r) is np.float64 and r == ref


def test_a_numpy_array_given_to_an_array_parameter_takes_the_object_path(built):
    _, m = built
    differential(m, "sum_ad", lambda: (np.array(F),), expect_deopt=True)
