/**
 * The on-device run: the JS -> native boundary, priced against baselines measured in the same run.
 *
 * The shape of this file is taken from the reference project's `UpcallBoundaryCostTest`, and the
 * things it copies are the things that make the two comparable:
 *
 * - **Every baseline is measured here, in this session, on this device.** Not read from a table,
 *   not carried over from the host sweep. A per-call figure on its own says nothing across
 *   devices; what travels is a ratio against something measured beside it.
 * - **The baselines bracket the boundary from both sides.** The JS-only rows say what the language
 *   charges for the loop and the call before anything crosses; `nativeLoopNs` says what the callee
 *   costs with the crossing taken out. The boundary is what is left.
 * - **min-max over repetitions, nothing dropped.**
 * - **Nothing here asserts a duration.** A wall-clock threshold on a device is a flake generator.
 *   What is checked is that every row came back as a usable positive number rather than a zero
 *   from a clock with no resolution -- the failure mode that would otherwise be reported as "the
 *   boundary is free".
 *
 * ### The direction that is not symmetric, and is labelled everywhere it appears
 *
 * `pingCallback` and `pingPromise` are native -> JS, but they are **not** the mirror image of the
 * rows above them. The New Architecture gives app code no synchronous native -> JS entry point:
 * a `Callback` is invoked through the `RuntimeScheduler`, so those two rows contain a scheduler
 * hop and a microtask drain on top of the crossing. They are reported as round trips, in a
 * separate block, and must not be subtracted from anything.
 */
const H = require('./harness.js');
const R = require('./jsRows.js');

const REPS = 5;

/**
 * Matches the reference project's warmup, and the reason is **not** the one the host sweep gives.
 *
 * `host-sweep.js` establishes that Hermes has no JIT and therefore no tier-up, and it would be easy
 * to conclude from that alone that a large warmup buys nothing here. That conclusion is wrong on
 * Android, and the reason is the half of the boundary the host sweep cannot reach:
 *
 *   JS (Hermes, no JIT) --JSI--> C++ --JNI--> **Kotlin, running on ART**
 *
 * The callee of every boundary row is `BenchModule.kt` executing on ART, and ART is precisely the
 * runtime whose tier-up the reference project measured: its `docs/design/upcall.md` sweep found ART
 * on `pmp_api36` still 9% above its plateau at 70 000 calls and only arriving at ~90 000-100 000 --
 * the largest warmup requirement of any configuration it measured, and the one that set its
 * constant. So the RN Android boundary rows sit on the same curve, for the same reason, on the same
 * runtime. `host-sweep.js` cannot see this because it has no native module and no ART.
 *
 * That is why `SWEEP` below runs on device against **the boundary row**, not only the JS row: it is
 * the only place this question can actually be asked. The count stays where it is unless that sweep
 * says otherwise, and the sweep is printed in every report so it can say so.
 *
 * **It said otherwise, so it moved.** At 100 000 the 2026-08-19T09:55 run
 * (`results/android-20260819-095448.txt`, load 3.52) reported the JS-only sweep settling at
 * 150 000 -- "warmup 100000 has only 0.67x margin -- TOO THIN" -- which indicts the JS baseline and
 * therefore every ratio printed against it. 400 000 is the value the 2026-08-19T00:50 run had
 * already shown gives that sweep an adequate margin. It does *not* fix the boundary sweep, which
 * has never settled at either count (COMPARISON.md §7d rules out both load and warmup for that
 * one); raising it here buys the JS half only, and the boundary half needs a physical device or a
 * revised settling criterion.
 */
const WARMUP = 400000;

/** Calls per timed loop. Same as the reference project's `N`. */
const N = 10000;

/**
 * The convergence sweep, on device. 40 repetitions of `N`, matching the reference project's sweep
 * exactly -- same repetition count, same calls per repetition -- so the two series can be read
 * against each other rather than merely resembling one another.
 */
const SWEEP_REPS = 40;

/** Async rows are three orders of magnitude slower, so they get their own, much smaller counts. */
const ASYNC_WARMUP = 200;
const ASYNC_N = 1000;

async function measureAsync(warmupIterations, iterations, block) {
  for (let w = 0; w < warmupIterations; w++) {
    await block(w);
  }
  const t0 = H.clock.nowNs();
  for (let i = 0; i < iterations; i++) {
    await block(i);
  }
  const t1 = H.clock.nowNs();
  return (t1 - t0) / iterations;
}

async function repeatAsync(reps, warmupIterations, iterations, block) {
  const readings = [];
  for (let r = 0; r < reps; r++) {
    readings.push(await measureAsync(warmupIterations, iterations, block));
  }
  return readings;
}

/**
 * The engine, named by itself rather than by the package.json it was built from.
 *
 * A report whose conditions say "React Native 0.87" does not say which Hermes that shipped, and the
 * host sweep's Hermes is a four-year-old standalone build that is emphatically not this one. This
 * asks the runtime.
 */
function engineDescription() {
  if (typeof HermesInternal !== 'undefined' && HermesInternal) {
    let props = null;
    try {
      props = HermesInternal.getRuntimeProperties ? HermesInternal.getRuntimeProperties() : null;
    } catch (e) {
      props = null;
    }
    if (!props) {
      return 'Hermes (getRuntimeProperties unavailable)';
    }
    const parts = [];
    for (const key of ['OSS Release Version', 'Version', 'Build', 'Bytecode Version']) {
      if (props[key] !== undefined) {
        parts.push(key + '=' + props[key]);
      }
    }
    return 'Hermes ' + (parts.length ? parts.join(', ') : JSON.stringify(props));
  }
  if (typeof process !== 'undefined' && process.versions && process.versions.v8) {
    return 'JSC/V8-family: Node ' + process.versions.node + ' / V8 ' + process.versions.v8;
  }
  // JSC in an RN app reports neither; say so rather than guessing.
  return 'not Hermes (no HermesInternal) -- probably JSC. hermesEnabled is a build-time choice.';
}

/**
 * @param {object} Bench the TurboModule, or null to run the JS-only rows alone
 * @param {object} [opts] `{log, platform}`. `log` is called with each progress line as it happens,
 *        so a run that stalls shows where; the report itself is returned, not logged, so progress
 *        cannot contaminate it. `platform` is `Platform.OS`, and is passed in rather than imported
 *        so this file stays free of a `react-native` dependency -- it is concatenated into a plain
 *        script for the standalone Hermes run.
 * @returns {Promise<{text: string, rows: Array}>}
 */
async function run(Bench, opts) {
  const P = R.payloads;
  const lines = [];
  const say = l => lines.push(l);
  const progress = (opts && opts.log) || (() => {});

  // What is on the far side of the boundary, named truthfully per platform. This was hardcoded to
  // the Android path, so the iOS report described its own calls as going "JNI -> ART" and its
  // warmup verdict was labelled "Hermes+JSI+JNI+ART". Neither is true on iOS, and the difference is
  // not cosmetic: the entire justification for a 100 000-call warmup is ART's tier-up, which does
  // not exist on the iOS side of this benchmark. A reader comparing the two columns has to be able
  // to see that the warmup argument applies to one of them and not the other.
  const ios = (opts && opts.platform) === 'ios';
  const CALLEE = ios ? 'Objective-C++, AOT' : 'Kotlin on ART';
  const PATH = ios ? 'Hermes -> JSI -> Objective-C++' : 'Hermes -> JSI -> JNI -> ART';
  const SWEEP_LABEL = ios ? 'boundary (Hermes+JSI+ObjC++)' : 'boundary (Hermes+JSI+JNI+ART)';

  say('');
  say('=== RN boundary benchmark: on-device ===');
  say('engine:  ' + engineDescription());
  say('clock:   ' + H.clock.name);
  say('warmup:  ' + WARMUP + ' calls per row, iterations per timed loop: ' + N);
  say('reps:    ' + REPS + ' per row, min-max reported, nothing discarded');
  say('module:  ' + (Bench ? 'present' : 'ABSENT -- JS-only rows only'));
  say('');

  const rows = [];

  // ---- 0. The convergence sweeps, COLD, before anything else has run --------------------------
  //
  // These must come first and in this order, and the ordering is the measurement: a sweep is only
  // a cold sweep if nothing has driven the path before it. The JS sweep touches only `jsAdd`, so
  // the boundary is still cold when the second sweep starts.
  //
  // The boundary sweep is the one that matters. See `WARMUP` above: Hermes has no JIT, but the
  // callee behind every boundary row is Kotlin on ART, which does, and which the reference project
  // measured needing ~90 000-100 000 calls on `pmp_api36`. This is the only place that can be
  // checked for React Native.
  progress('sweep: JS-only row, cold');
  const jsSweepRow = R.rows[1]; // "JS call, same shape as addInts"
  const jsSweep = H.formatSweep(
    '"' + jsSweepRow.name + '" (no boundary: Hermes only)',
    H.sweep(SWEEP_REPS, N, jsSweepRow.block),
    N,
    WARMUP,
  );
  say(jsSweep.text);
  say('');

  let boundarySweep = null;
  if (Bench) {
    progress('sweep: boundary row addInts, cold');
    boundarySweep = H.formatSweep(
      'addInts(3, 4) through the TurboModule (' + PATH + ')',
      H.sweep(SWEEP_REPS, N, () => Bench.addInts(3, 4)),
      N,
      WARMUP,
    );
    say(boundarySweep.text);
    say('');
    say('  Read the two sweeps together. The JS one prices Hermes, which has no JIT and is expected');
    say('  to be flat from the first repetition. The boundary one prices Hermes plus a callee');
    say('  running as ' + CALLEE + '.');
    if (ios) {
      say('  On iOS that callee is AOT-compiled and does NOT tier up, so neither side of this');
      say('  boundary has a warm-up curve to climb: a sweep that fails to settle here is evidence');
      say('  of a busy machine, not of a warmup that is too short. Check the load average.');
    } else {
      say('  ART does tier up -- so if either sweep justifies the ' + WARMUP + ' warmup, it is this');
      say('  one, and if this one shows a thin margin the warmup must be raised.');
    }
    say('');
  }

  // ---- 1. The JS-side floor, measured here rather than borrowed -------------------------------
  for (const row of R.rows) {
    progress('js row: ' + row.name);
    rows.push({
      group: 'js',
      name: row.name,
      readings: H.repeat(REPS, WARMUP, N, row.block),
    });
  }

  if (Bench) {
    // ---- 2. The boundary, one row per shape ---------------------------------------------------
    const nativeRows = [
      ['zeroArgs() -- boundary, no arguments', () => Bench.zeroArgs()],
      ['addInts(3, 4) -- two numbers', () => Bench.addInts(3, 4)],
      ['stringLength(8 chars)', () => Bench.stringLength(P.SHORT_STRING)],
      ['stringLength(128 chars)', () => Bench.stringLength(P.LONG_STRING)],
      ['echoString(8 chars) -- 2 conversions', () => Bench.echoString(P.SHORT_STRING)],
      ['echoString(128 chars) -- 2 conversions', () => Bench.echoString(P.LONG_STRING)],
      ['sumArray(8 elements)', () => Bench.sumArray(P.ARRAY_8)],
      ['sumArray(1000 elements)', () => Bench.sumArray(P.ARRAY_1000)],
      ['sumObject(3 keys)', () => Bench.sumObject(P.OBJECT_3)],
      // Last, and apart: `void` is dispatched asynchronously, so this row is a queue push and is
      // not comparable with the eight above it. See NativeBench.ts on `zeroArgs` vs `noop`.
      // The `return 1` is not decoration: every other block returns a value into the harness' sink,
      // and a block returning `undefined` sets that sink to `undefined`, which makes the
      // "sink (proves no row was eliminated)" line print `undefined` and prove nothing.
      ['noop() -- void, ASYNC DISPATCH, not a crossing', () => { Bench.noop(); return 1; }],
    ];
    for (const [name, block] of nativeRows) {
      progress('boundary row: ' + name);
      rows.push({group: 'boundary', name, readings: H.repeat(REPS, WARMUP, N, block)});
    }

    // ---- 3. The callee with the crossing removed ----------------------------------------------
    // Timed by the *native* clock inside a native loop. The gap between this and `addInts` above
    // is what one crossing adds, with the callee held constant.
    const nativeOnly = [];
    for (let r = 0; r < REPS; r++) {
      nativeOnly.push(Bench.nativeLoopNs(1000000));
    }
    rows.push({group: 'control', name: "addInts' body, native loop, no crossing", readings: nativeOnly});
  }

  say(H.formatRows(rows));
  say('');
  say('sink (proves no row was eliminated): ' + H.readSink());

  // ---- 4. The asymmetric direction, kept apart -------------------------------------------------
  if (Bench) {
    progress('async round-trip rows');
    const asyncRows = [];
    asyncRows.push({
      name: 'native -> JS callback round trip',
      readings: await repeatAsync(REPS, ASYNC_WARMUP, ASYNC_N, () =>
        new Promise(resolve => Bench.pingCallback(1, resolve)),
      ),
    });
    asyncRows.push({
      name: 'native -> JS promise round trip',
      readings: await repeatAsync(REPS, ASYNC_WARMUP, ASYNC_N, () => Bench.pingPromise(1)),
    });
    asyncRows.push({
      name: 'bare JS Promise.resolve await (floor)',
      readings: await repeatAsync(REPS, ASYNC_WARMUP, ASYNC_N, () => Promise.resolve(1)),
    });
    say('');
    say('--- native -> JS, ROUND TRIPS (scheduler hop included; not comparable with the rows above) ---');
    say(H.formatRows(asyncRows));
    for (const r of asyncRows) {
      rows.push({group: 'roundtrip', name: r.name, readings: r.readings});
    }
  }

  // ---- 5. Ratios, all from this run ------------------------------------------------------------
  const byName = {};
  for (const r of rows) {
    byName[r.name] = H.min(r.readings);
  }
  /**
   * A ratio, or a named complaint.
   *
   * Looking a row up by a string that no row has produces `undefined / undefined` = `NaN`, and
   * `NaN` printed in a ratio column reads as a quirk of the host rather than as a bug in this
   * file. It was one: these lookups used `'echoString(128 chars)'` while the row is named
   * `'echoString(128 chars) -- 2 conversions'`, so that ratio printed `NaNx` in every report.
   */
  const ratio = (label, numeratorName, denominatorName) => {
    const a = byName[numeratorName];
    const b = byName[denominatorName];
    if (a === undefined || b === undefined) {
      const missing = [
        a === undefined ? numeratorName : null,
        b === undefined ? denominatorName : null,
      ].filter(Boolean);
      say('  ' + label + ' UNAVAILABLE -- no row named: ' + missing.join(' | '));
      return;
    }
    say('  ' + label + ' ' + H.fmt(a / b) + 'x');
  };

  const jsCall = byName['JS call, same shape as addInts'];
  const addInts = byName['addInts(3, 4) -- two numbers'];
  if (Bench && jsCall && addInts) {
    say('');
    say('--- ratios, every term from this run ---');
    ratio(
      'addInts / a JS call of the same shape:     ',
      'addInts(3, 4) -- two numbers',
      'JS call, same shape as addInts',
    );
    ratio(
      'addInts / zeroArgs (what two numbers cost):',
      'addInts(3, 4) -- two numbers',
      'zeroArgs() -- boundary, no arguments',
    );
    ratio(
      'sumArray(1000) / sumArray(8):             ',
      'sumArray(1000 elements)',
      'sumArray(8 elements)',
    );
    ratio(
      'echoString(128) / echoString(8):          ',
      'echoString(128 chars) -- 2 conversions',
      'echoString(8 chars) -- 2 conversions',
    );
  }

  // ---- 6. The run's verdict on its own warmup ---------------------------------------------------
  //
  // Stated by the run rather than left to the reader, because the failure this guards against is
  // precisely a reader quoting a table whose warmup was too small. The reference project published
  // two boundary costs that way and neither reproduced.
  say('');
  say('--- warmup verdict, from this run\'s own sweeps ---');
  const verdicts = [
    ['js', 'JS-only (Hermes)', jsSweep && jsSweep.analysis],
    ['boundary', SWEEP_LABEL, boundarySweep && boundarySweep.analysis],
  ];
  let inadequate = false;
  // Which one, not merely whether. The two sweeps indict different rows, and saying only that "at
  // least one" was thin sent a reader to the wrong half: a run on this machine had the boundary
  // sweep flat from repetition 1 (margin infinite -- there is no warm-up curve when the callee is
  // AOT) while the JS-only baseline drifted 14.15 -> 13.57 ns under CPU frequency scaling and came
  // out at 1.43x, and the summary below still told them the *boundary* rows were unsettled. They
  // were not; the baseline the ratios divide by was.
  const badSweeps = [];
  for (const [key, label, a] of verdicts) {
    if (!a) {
      say('  ' + label + ': not swept (no native module)');
      continue;
    }
    if (!a.settled) {
      inadequate = true;
      badSweeps.push(key);
      say('  ' + label + ': NEVER SETTLED. Warmup ' + WARMUP + ' is not justified by this run.');
    } else if (a.marginAtWarmup < 1.5) {
      inadequate = true;
      badSweeps.push(key);
      say(
        '  ' + label + ': settles at ' + a.flatFromCalls + ' calls, so warmup ' + WARMUP +
          ' has only ' + H.fmt(a.marginAtWarmup) + 'x margin -- TOO THIN. Raise it.',
      );
    } else {
      say(
        '  ' + label + ': settles at ' + a.flatFromCalls + ' calls; warmup ' + WARMUP + ' has ' +
          H.fmt(a.marginAtWarmup) + 'x margin. Adequate.',
      );
    }
  }
  if (inadequate) {
    const badBoundary = badSweeps.indexOf('boundary') !== -1;
    const badJs = badSweeps.indexOf('js') !== -1;
    say('');
    say('  ==> AT LEAST ONE SWEEP SAYS THIS WARMUP IS INSUFFICIENT.');
    if (badBoundary) {
      say('      THE BOUNDARY ROWS ARE NOT FIGURES. They were read before the boundary settled;');
      say('      that is every row that crosses into native, which is the point of this benchmark.');
    }
    if (badJs) {
      say('      THE JS-ONLY BASELINE IS NOT SETTLED. That indicts the JS rows and every ratio');
      say('      printed above, since those divide by one -- but NOT the absolute boundary');
      say('      figures, which do not depend on it.');
    }
    if (badJs && !badBoundary) {
      say('      Note what this verdict does NOT say: the boundary sweep above settled. Read its');
      say('      "flat from" line before discarding the boundary rows -- an unsettled Hermes');
      say('      baseline on a machine with frequency scaling is not a statement about them.');
    }
    say('      Raise WARMUP in bench/appRun.js and re-run, or re-run on a quieter machine.');
  }

  // ---- 7. The only assertion: a number came back, not a zero -----------------------------------
  const zeros = rows.filter(r => H.min(r.readings) <= 0).map(r => r.name);
  say('');
  if (zeros.length) {
    say('UNUSABLE ROWS (measured <= 0, the clock has no resolution here): ' + zeros.join(', '));
  } else {
    say('all ' + rows.length + ' rows returned a positive duration');
  }
  say('');

  return {text: lines.join('\n'), rows, warmupAdequate: !inadequate};
}

module.exports = {run, REPS, WARMUP, N, SWEEP_REPS};
