// Embedding probe for the free-threaded wasm32-emscripten archive (#175 step 2, agent proposal).
//
// Usage: node embed-probe.mjs <archive root>
//
// Reaches CPython the way python-multiplatform does: python.mjs is loaded as a module factory
// with noInitialRun (main is never called), and everything goes through Module.wasmExports on the
// JS main thread. Prints one line per check, "[x] PASS|FAIL: detail", and a final
// FT-WASM-EMBED-PROBE-OK only when every check passed. A hang is not caught here; the caller
// wraps this in `timeout` and reads the last "[x]" line it printed.
//
// Node only. Node's main thread uses a real Atomics.wait; a browser's main thread busy-waits and
// services proxied calls, so this probe says nothing about the browser.
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const root = path.resolve(process.argv[2] ?? "");
if (!process.argv[2] || !fs.existsSync(path.join(root, "python.mjs"))) {
    console.log(`[setup] FAIL: usage: node embed-probe.mjs <archive root containing python.mjs>, got '${process.argv[2]}'`);
    process.exit(2);
}

let failed = false;
const pass = (id, detail) => console.log(`[${id}] PASS: ${detail}`);
const fail = (id, detail) => { failed = true; console.log(`[${id}] FAIL: ${detail}`); };
const finish = () => {
    if (failed) { console.log("FT-WASM-EMBED-PROBE-FAILED"); process.exit(1); }
    console.log("FT-WASM-EMBED-PROBE-OK");
    process.exit(0); // the pthread Workers would otherwise keep the process alive
};

// Same reason as cpython.mjs: with no main there is no WebAssembly.promising frame, so a
// JSPI-suspending syscall import would throw SuspendError.
delete WebAssembly.promising;
delete WebAssembly.Suspending;

const libDir = fs.readdirSync(path.join(root, "lib")).find((d) => /^python3\.\d+t$/.test(d));
if (!libDir) { fail("a", `no lib/python3.<minor>t in ${root}`); finish(); }
const minor = libDir.replace(/^python/, "");

// ---- a. load the factory without main -----------------------------------------------------------
let M;
try {
    const { default: EmscriptenModule } = await import(pathToFileURL(path.join(root, "python.mjs")).href);
    M = await EmscriptenModule({
        noInitialRun: true,
        thisProgram: `${root}/bin/python${minor}`,
        arguments: [],
        preRun(Module) {
            globalThis.Module = Module;
            // Minimum of node_entry.mjs: mount the host filesystem (the archive is read through
            // NODEFS) except the three directories Emscripten owns, then set the environment.
            for (const dir of fs.readdirSync("/").filter((d) => !["dev", "lib", "proc"].includes(d)).map((d) => "/" + d)) {
                Module.FS.mkdirTree(dir);
                Module.FS.mount(Module.FS.filesystems.NODEFS, { root: dir }, dir);
            }
            Module.FS.chdir(root);
            Object.assign(Module.ENV, process.env);
            delete Module.ENV.PATH;
            Module.ENV.PYTHONHOME = root;
        },
    });
    const mem = M.wasmMemory;
    if (!mem) throw new Error("Module.wasmMemory is undefined (the EXPORTED_RUNTIME_METHODS patch did not take effect)");
    if (!M.wasmExports) throw new Error("Module.wasmExports is undefined (the EXPORTED_RUNTIME_METHODS patch did not take effect)");
    if (!(mem.buffer instanceof SharedArrayBuffer)) throw new Error("wasmMemory.buffer is not a SharedArrayBuffer");
    pass("a", `wasmMemory.buffer is a SharedArrayBuffer, ${mem.buffer.byteLength / 65536} pages`);
} catch (e) {
    fail("a", String(e && e.stack || e));
    finish();
}
const E = M.wasmExports;
// A fresh view each time: a shared memory's old buffer keeps its old length after growth.
const u8 = () => new Uint8Array(M.wasmMemory.buffer);
const u32 = () => new Uint32Array(M.wasmMemory.buffer);
const exported = (name) => {
    let v = E[name];
    if (v === undefined) v = M["_" + name];
    if (v === undefined) throw new Error(`CPython export '${name}' not found on wasmExports or Module`);
    return v;
};
const addrOf = (name) => { const v = exported(name); return v instanceof WebAssembly.Global ? v.value : v; };

// ---- b. memory import checks A1 / A2 ------------------------------------------------------------
// Hand-assembled modules importing intrinsics.memory (what Kotlin/Wasm's import is named) and
// exporting load(i32) -> i32 = i32.load. A1 imports a non-shared memory (what Kotlin 2.4.20-Beta2
// emits); A2 a shared one with maximum 65536 pages (what a post-link patch would emit).
const head = [0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00];
const typeSec = [0x01, 0x06, 0x01, 0x60, 0x01, 0x7f, 0x01, 0x7f];
const importPrefix = [0x01, 0x0a, ...Buffer.from("intrinsics"), 0x06, ...Buffer.from("memory"), 0x02];
const tail = [0x03, 0x02, 0x01, 0x00, 0x07, 0x08, 0x01, 0x04, 0x6c, 0x6f, 0x61, 0x64, 0x00, 0x00,
              0x0a, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x28, 0x02, 0x00, 0x0b];
const mod = (limits) => new Uint8Array([...head, ...typeSec,
    0x02, importPrefix.length + limits.length, ...importPrefix, ...limits, ...tail]);
const A1 = mod([0x00, 0x00]);
const A2 = mod([0x03, 0x00, 0x80, 0x80, 0x04]);
try {
    let a1;
    try {
        new WebAssembly.Instance(new WebAssembly.Module(A1), { intrinsics: { memory: M.wasmMemory } });
        a1 = "instantiated";
    } catch (e) {
        a1 = e instanceof WebAssembly.LinkError ? "LinkError" : `other:${e && e.name}`;
        if (a1 === "LinkError") console.log(`    A1 message: ${e.message}`);
    }
    if (a1 !== "LinkError") throw new Error(`A1 (non-shared import) expected LinkError, got ${a1}`);
    const a2 = new WebAssembly.Instance(new WebAssembly.Module(A2), { intrinsics: { memory: M.wasmMemory } });
    const addr = addrOf("Py_Version");
    const viaLoad = a2.exports.load(addr) >>> 0;
    const viaHeap = u32()[addr >>> 2];
    if (viaLoad !== viaHeap) throw new Error(`A2.load(${addr}) = ${viaLoad} but HEAPU32 = ${viaHeap}`);
    pass("b", `A1 non-shared import: LinkError; A2 shared import (max 65536): instantiated; A2.load(&Py_Version=${addr}) = 0x${viaLoad.toString(16)} equals HEAPU32`);
} catch (e) {
    fail("b", String(e && e.stack || e));
}

// ---- c. embedding without main, threads, gc in a worker -----------------------------------------
const cstr = (s) => {
    const bytes = Buffer.from(s + "\0");
    const p = E.malloc(bytes.length);
    if (!p) throw new Error("malloc returned 0");
    u8().set(bytes, p);
    return p;
};
const script = `
import gc, os, sys, threading
assert sys._is_gil_enabled() is False, "GIL is enabled at the start"
sizes = {}
def reader(i):
    with open(os.__file__) as f:
        n = len(f.read())
    print(f"embed-probe thread {i} read {n} chars of os.py", flush=True)
    sizes[i] = n
threads = [threading.Thread(target=reader, args=(i,)) for i in range(4)]
for t in threads:
    t.start()
for t in threads:
    t.join(timeout=120)
alive = [t.name for t in threads if t.is_alive()]
assert not alive, f"threads still running: {alive}"
assert len(sizes) == 4 and len(set(sizes.values())) == 1, sizes
assert sys._is_gil_enabled() is False, "GIL was re-enabled by the threads"
collected = []
gc_thread = threading.Thread(target=lambda: collected.append(gc.collect()))
gc_thread.start()
gc_thread.join(timeout=120)
assert not gc_thread.is_alive() and collected, "gc.collect() in a worker thread did not finish"
print(f"embed-probe gc.collect() in a worker thread returned {collected[0]}", flush=True)
assert sys._is_gil_enabled() is False, "GIL was re-enabled by gc"
`;
try {
    E.Py_Initialize();
    pass("c1", "Py_Initialize returned");
    const tstate = E.PyEval_SaveThread();
    pass("c2", `PyEval_SaveThread returned ${tstate} (main thread detached)`);
    const gil = E.PyGILState_Ensure();
    const code = cstr(script);
    console.log("    running the thread script (a hang here is caught by the caller's timeout)");
    const rc = E.PyRun_SimpleString(code);
    E.free(code);
    E.PyGILState_Release(gil);
    if (rc !== 0) throw new Error(`PyRun_SimpleString returned ${rc} (Python traceback, if any, is above)`);
    pass("c3", "4 threads read os.py, joined, GIL stayed off, gc.collect() ran in a worker thread; PyGILState_Release returned");
} catch (e) {
    fail("c", String(e && e.stack || e));
}
finish();
