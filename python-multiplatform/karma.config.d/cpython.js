// Appended verbatim into the generated karma.conf.js (KotlinKarma reads every *.js in this
// directory). ROADMAP §10 -- what a browser needs that Node does not.
//
// `wasmJsNodeTest` reaches CPython off the filesystem. karma serves over HTTP, and the two runtime
// facts that follow from that are the whole of this file:
//
//   1. `cpython.mjs` loads Emscripten's glue with `import(/* webpackIgnore: true */ './python.mjs')`.
//      webpackIgnore means the *browser* resolves that specifier -- relative to the bundle, which
//      karma-webpack writes to a temp directory whose name changes every run. So the files cannot be
//      served from a fixed URL; they have to be copied next to the bundle. `webpackCopy` is exactly
//      that hook (see kotlin-web-helpers/dist/karma-webpack-output.js), and it is why this is a
//      karma config rather than a Gradle copy step. `python.mjs` then finds `python.wasm` beside
//      itself the same way.
//
//   2. `browserSettings()` fetches the standard library as `STDLIB_ZIP_URL`, which is
//      `./python3<minor>.zip` -- a *document*-relative URL, because in a real distribution the zip
//      sits next to index.html. karma's document is `/context.html` at the server root, so the
//      proxy below is what makes karma's page look like that distribution. Getting this wrong is a
//      404 during `preRun`, i.e. a failure inside `addRunDependency` rather than a test failure.
//
// Everything is located relative to `config.basePath`, so no path from the build script is
// duplicated here. The staging directory is the npm project's `kotlin/`, which
// `python-multiplatform/build.gradle.kts` fills in the `KotlinJsTest` `doFirst`.

const path = require('path');
const fs = require('fs');

const staged = path.resolve(config.basePath, 'kotlin');

// Located rather than named: the zip carries the interpreter's version, and a literal here would be
// a second place to keep in step with the CPython build (`cpython.mjs` already reads the version out
// of the running interpreter to decide where in MEMFS the zip goes).
const stdlibZip = fs.existsSync(staged)
    ? fs.readdirSync(staged).find((name) => /^python\d+\.\d+\.zip$/.test(name))
    : undefined;

const runtimeFiles = ['python.mjs', 'python.wasm', stdlibZip]
    .filter(Boolean)
    .map((name) => path.join(staged, name))
    .filter(fs.existsSync);

if (runtimeFiles.length === 0) {
    // Not an error: the Gradle task skips when there is no Emscripten CPython build at all, and a
    // karma run without one would fail on the import of './python.mjs' with no explanation.
    console.warn(
        '[cpython] no staged CPython runtime in ' + staged + '. wasmJsBrowserTest will not be able ' +
        'to load the interpreter; see stageWasmBrowserRuntime / -PwasmPythonDir.'
    );
}

config.webpackCopy = (config.webpackCopy || []).concat(runtimeFiles);

if (stdlibZip) {
    // karma serves anything in `files` under `/absolute<absolute path>`; the proxy puts it where a
    // document-relative fetch from `/context.html` will look for it.
    const zipPath = path.join(staged, stdlibZip);
    config.files.push({ pattern: zipPath, included: false, served: true, watched: false });
    config.proxies['/' + stdlibZip] = '/absolute' + zipPath;
}

// karma's default is 10 s of silence before it gives up, and bring-up here is `python.wasm` (9.6 MB)
// plus the stdlib zip (3.7 MB) over HTTP, compiled, and then `Py_Initialize` -- none of which
// reports anything, because the first test does not run until all of it is done.
//
// 60 s rather than something larger, and the number is a measurement: a whole green run of this task
// is 4.5 s wall on a warm build -- karma start, browser launch, HTTP, CPython bring-up and ten tests,
// all of it. This budget is what a *failing* run costs before karma gives up, so an over-generous one
// is paid on every red build. Measured: the `node:fs` break below took 2m07s at 120 s.
config.browserNoActivityTimeout = 60000;
config.captureTimeout = 60000;
