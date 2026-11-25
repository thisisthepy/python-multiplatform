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

# ---- the proxy type's slots from Python threads (#175) ----------------------------------------
# _pm_upcall.Node's tp_traverse, tp_clear and tp_dealloc take the routes of the proxy type's
# dispatchers, with C targets that count where they ran. Cycles of nodes are made on threads and
# collected by a Python thread while the main thread waits in join(): the traverse then runs on
# the main thread during the collector's stop-the-world pause, and clear and dealloc after it.
# A deadlock shows as a thread still alive after join's timeout.
N_CYCLES = 200
gc.collect()
_pm_upcall.reset_slot_stats()
gc.disable()  # the collection below is the worker's, not an automatic one on some other thread
cycle_errors = []


def make_cycles():
    try:
        for _ in range(N_CYCLES):
            a, b = _pm_upcall.Node(), _pm_upcall.Node()
            a.ref, b.ref = b, a
    except BaseException as e:  # noqa: BLE001 - reported below
        cycle_errors.append(repr(e))


makers = [threading.Thread(target=make_cycles) for _ in range(N_THREADS)]
for t in makers:
    t.start()
for t in makers:
    t.join(timeout=120)
assert not [t for t in makers if t.is_alive()], "the threads making node cycles did not finish"
assert not cycle_errors, f"making node cycles failed: {cycle_errors}"
assert _pm_upcall.slot_stats()["dealloc"] == {"main": 0, "other": 0}, \
    f"a node in a cycle was freed before any collection: {_pm_upcall.slot_stats()}"

collected = []
referents = []


def collect_on_a_thread():
    collected.append(gc.collect())
    # Outside a pause: gc.get_referents calls tp_traverse with the world running.
    x, y = _pm_upcall.Node(), _pm_upcall.Node()
    x.ref = y
    referents.append(any(r is y for r in gc.get_referents(x)))


collector = threading.Thread(target=collect_on_a_thread)
collector.start()
collector.join(timeout=120)
gc.enable()
assert not collector.is_alive(), \
    f"gc.collect() on a Python thread did not return (a routed tp_traverse not serviced?): {_pm_upcall.slot_stats()}"
stats = _pm_upcall.slot_stats()
n_nodes = 2 * N_CYCLES * N_THREADS
assert collected and collected[0] >= n_nodes, f"the thread's collection found {collected} objects, expected >= {n_nodes}"
assert referents == [True], f"gc.get_referents on a thread did not see the node's ref: {referents}"
assert stats["traverse"]["main"] > 0 and stats["traverse"]["other"] == 0, f"traverse did not run on the main thread: {stats}"
assert stats["clear"]["other"] == 0 and stats["clear"]["main"] > 0, f"clear did not run on the main thread: {stats}"
# Every cycle's nodes. x and y may add up to 2 more: y's last reference is dropped on the main
# thread (x's dealloc runs there), and a cross-thread decref may be merged after join() returns.
assert stats["dealloc"]["other"] == 0 and n_nodes <= stats["dealloc"]["main"] <= n_nodes + 2, \
    f"dealloc ran elsewhere or not for every node: {stats}"
assert sys._is_gil_enabled() is False, "the GIL was re-enabled during the slot calls"

print(f"FT-WASM-SMOKE-OK gil={sys._is_gil_enabled()} threads={N_THREADS} upcalls={N_THREADS * N_CALLS} "
      f"slots=traverse:{stats['traverse']['main']},clear:{stats['clear']['main']},dealloc:{stats['dealloc']['main']} "
      f"abiflags={sys.abiflags} version={sys.version.split()[0]}")
