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

# ---- upcalls from Python threads (#175 step 2) -------------------------------------------
# _pm_upcall routes a call made on any thread to the main runtime thread, where Kotlin/Wasm
# runs. selftest() uses the same route with a C target that reports where it ran.
import gc
import _pm_upcall

assert sys._is_gil_enabled() is False, "importing _pm_upcall re-enabled the GIL"
main_ident = threading.get_ident()
assert _pm_upcall.selftest() == (main_ident, True), "a main-thread upcall did not run in place"

N_CALLS = 50
upcall_results = [None] * N_THREADS
upcall_errors = [None] * N_THREADS


def call_kotlin_side(i):
    seen = set()
    for _ in range(N_CALLS):
        seen.add(_pm_upcall.selftest())
    try:
        _pm_upcall.selftest("raise")
    except ValueError as e:
        upcall_errors[i] = str(e)
    upcall_results[i] = (threading.get_ident(), seen)


def collect_while_calling():
    for _ in range(20):
        gc.collect()


# The main thread waits in join() while the threads call: it must service their calls there.
threads = [threading.Thread(target=call_kotlin_side, args=(i,)) for i in range(N_THREADS)]
threads.append(threading.Thread(target=collect_while_calling))
for t in threads:
    t.start()
for t in threads:
    t.join(timeout=120)
alive = [t.name for t in threads if t.is_alive()]
assert not alive, f"upcall threads still running after join (a proxied call was not serviced?): {alive}"
for i, r in enumerate(upcall_results):
    assert r is not None, f"upcall thread {i} did not finish"
    ident, seen = r
    assert ident != main_ident, f"upcall thread {i} ran on the main thread"
    assert seen == {(main_ident, True)}, f"upcall thread {i}: calls ran at {seen}, not on the main thread"
    assert upcall_errors[i] == "raised on the main thread", f"upcall thread {i}: exception was {upcall_errors[i]!r}"
assert sys._is_gil_enabled() is False, "the GIL was re-enabled during the upcalls"

print(f"FT-WASM-SMOKE-OK gil={sys._is_gil_enabled()} threads={N_THREADS} upcalls={N_THREADS * N_CALLS} "
      f"abiflags={sys.abiflags} version={sys.version.split()[0]}")
