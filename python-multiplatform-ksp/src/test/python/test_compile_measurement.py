"""What compiling buys on a scalar loop (AGENTS.md §14). Printed, with a sanity bound only."""
import time

import pytest

from test_compile import SCALAR, build

pytest.importorskip("Cython")


@pytest.mark.measurement
def test_scalar_loop_speedup(write, tmp_path):
    built = build(write, tmp_path, "scalar", SCALAR)
    compiled, interpreted = built.load(), built.load_interpreted()
    n = 2_000_000

    def best(fn):
        times = []
        for _ in range(3):
            start = time.perf_counter()
            fn(n)
            times.append(time.perf_counter() - start)
        return min(times)

    t_c, t_i = best(compiled.total), best(interpreted.total)
    print(f"\n[measure] scalar loop n={n}: interpreted {t_i * 1000:.1f} ms, "
          f"compiled {t_c * 1000:.1f} ms, speedup {t_i / t_c:.1f}x")
    assert compiled.total(n) == interpreted.total(n)
    assert t_c < t_i
