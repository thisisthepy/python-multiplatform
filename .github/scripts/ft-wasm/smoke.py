# Smoke test for the free-threaded wasm32-emscripten build (#175), run by build-wasm.sh
# under node. Prints one line starting with FT-WASM-SMOKE-OK, which the script greps for;
# any failed assertion exits non-zero instead.
import sys
import sysconfig
import threading

assert sys.platform == "emscripten", sys.platform
assert sysconfig.get_config_var("Py_GIL_DISABLED") == 1, "not a free-threaded build"
assert sys._is_gil_enabled() is False, "the GIL is enabled at run time"

N_THREADS = 4
N_ITER = 200_000
results = [None] * N_THREADS


def work(i):
    total = 0
    for k in range(N_ITER):
        total += k * (i + 1)
    results[i] = (threading.get_ident(), total)


threads = [threading.Thread(target=work, args=(i,)) for i in range(N_THREADS)]
for t in threads:
    t.start()
for t in threads:
    t.join(timeout=120)
alive = [t.name for t in threads if t.is_alive()]
assert not alive, f"threads still running after join: {alive}"

expected = [(i + 1) * N_ITER * (N_ITER - 1) // 2 for i in range(N_THREADS)]
got = [r[1] if r else None for r in results]
assert got == expected, f"results {got} != {expected}"
idents = {r[0] for r in results}
assert len(idents) == N_THREADS, f"expected {N_THREADS} distinct thread idents, got {idents}"
assert threading.get_ident() not in idents, "a worker ran on the main thread"
# Importing threading must not have re-enabled the GIL.
assert sys._is_gil_enabled() is False, "the GIL was re-enabled during the run"

print(f"FT-WASM-SMOKE-OK gil={sys._is_gil_enabled()} threads={N_THREADS} "
      f"abiflags={sys.abiflags} version={sys.version.split()[0]}")
