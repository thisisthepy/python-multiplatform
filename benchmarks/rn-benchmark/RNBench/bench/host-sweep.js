/**
 * The host-side run: JS-only rows, plus the convergence sweep that decides what warmup the
 * boundary rows need.
 *
 * It runs under the standalone Hermes VM and under Node/V8 **from the same file**, on purpose. The
 * reference project's 100 000-call warmup exists because the JVM's C2 and V8's tiering both take
 * tens of thousands of calls to reach a plateau, and its docs say so with a 40-repetition sweep as
 * evidence. Whether Hermes behaves the same way is not something to assume: Hermes ships without a
 * JIT in React Native, so the prediction is that its series is flat from the first repetition. This
 * file runs the identical sweep on both engines so the contrast is visible rather than argued.
 *
 * This measures **no boundary**. It cannot: there is no native module on the host. What it settles
 * is the warmup count and the JS-side floor, both of which the on-device run then inherits.
 */
(function (root) {
  'use strict';

  var isNode = typeof module !== 'undefined' && module.exports;
  var H = isNode ? require('./harness.js') : root.BenchHarness;
  var R = isNode ? require('./jsRows.js') : root.BenchJsRows;

  function say(line) {
    if (typeof print === 'function') {
      print(line);
    } else {
      console.log(line);
    }
  }

  function engineName() {
    if (typeof HermesInternal !== 'undefined' && HermesInternal) {
      var props = null;
      try {
        props = HermesInternal.getRuntimeProperties ? HermesInternal.getRuntimeProperties() : null;
      } catch (e) {
        props = null;
      }
      var ver = props && (props['OSS Release Version'] || props.Version);
      return 'Hermes' + (ver ? ' ' + ver : '');
    }
    if (typeof process !== 'undefined' && process.versions && process.versions.v8) {
      return 'Node ' + process.versions.node + ' / V8 ' + process.versions.v8;
    }
    return 'unknown engine';
  }

  /**
   * How many iterations this row needs before the clock can see it.
   *
   * Doubles until the timed interval is at least 50 clock ticks, then returns that count with a
   * 4x margin. The alternative -- a fixed count -- reports 0.00 ns/op for a cheap row on a 1 ms
   * clock, and a zero here would be read as "free" rather than as "unmeasured", which is the
   * specific mistake the reference project's asserts exist to catch.
   */
  function calibrate(block) {
    var n = 1000;
    for (var attempt = 0; attempt < 40; attempt++) {
      var t0 = H.clock.nowNs();
      var s = 0;
      for (var i = 0; i < n; i++) {
        s = block(i);
      }
      var elapsed = H.clock.nowNs() - t0;
      if (s === undefined && s !== undefined) {
        say('unreachable');
      }
      if (elapsed >= H.clock.resolutionNs * 50) {
        return n * 4;
      }
      n *= 2;
    }
    return n;
  }

  var REPS = 5;
  var SWEEP_REPS = 40;

  /**
   * The floor on warmup calls per row, matching the reference project's 100 000.
   *
   * Calibration sizes a row for the *clock*, and on a microsecond clock that comes out at a few
   * thousand iterations -- far below where V8 finishes tiering up, as the sweep above this file's
   * output demonstrates. Warming to the calibrated count alone would therefore read V8 several
   * tiers early, which is precisely the defect the reference project found in its own table and
   * had to re-cut it for. Overridable so the claim can be tested rather than trusted:
   *
   *   RN_BENCH_WARMUP=300000 ./run-host-sweep.sh node
   */
  var WARMUP_FLOOR = 100000;
  if (typeof process !== 'undefined' && process.env && process.env.RN_BENCH_WARMUP) {
    WARMUP_FLOOR = parseInt(process.env.RN_BENCH_WARMUP, 10) || WARMUP_FLOOR;
  }
  /** Upper bound, so Hermes' multi-million calibrated counts do not turn warmup into the run. */
  var WARMUP_CAP = 1000000;

  function warmupFor(n) {
    return Math.max(WARMUP_FLOOR, Math.min(n, WARMUP_CAP));
  }

  say('');
  say('=== RN boundary benchmark: host-side JS rows ===');
  say('engine:     ' + engineName());
  say('clock:      ' + H.clock.name + ' (assumed resolution ' + H.clock.resolutionNs + ' ns)');
  say('reps:       ' + REPS + ' per row, min-max reported, nothing discarded');
  say('');
  say('This run contains no native boundary. It fixes the JS-side floor and the warmup count.');
  say('');

  // ---- 1. The convergence sweep, cold: no warmup at all before the first repetition ----------
  var sweepRow = R.rows[1]; // "JS call, same shape as addInts"
  var sweepN = calibrate(sweepRow.block);
  say('--- convergence sweep: "' + sweepRow.name + '" ---');
  say('iterations per repetition: ' + sweepN + ', repetitions: ' + SWEEP_REPS + ', warmup before the first: none');
  var series = H.sweep(SWEEP_REPS, sweepN, sweepRow.block);
  var line = [];
  for (var i = 0; i < series.length; i++) {
    line.push(H.fmt(series[i]));
    if (line.length === 8) {
      say('  ' + line.join('  '));
      line = [];
    }
  }
  if (line.length) {
    say('  ' + line.join('  '));
  }
  var first = series[0];
  var tail = series.slice(Math.floor(series.length / 2));
  var tailMin = H.min(tail);
  var tailMax = H.max(tail);
  say('');
  say('  first repetition:        ' + H.fmt(first) + ' ns/op');
  say('  second half, min-max:    ' + H.fmt(tailMin) + ' - ' + H.fmt(tailMax) + ' ns/op');
  say('  first / second-half min: ' + H.fmt(first / tailMin) + 'x');
  // The same settle analysis the on-device run prints, so the host answer and the device answer
  // are stated in one vocabulary instead of two. The device one is the one that decides the
  // warmup -- this engine is not the engine the app ships, and this sweep has no boundary in it.
  var coldA = H.analyzeSweep(series, sweepN, WARMUP_FLOOR);
  if (coldA.settled) {
    say('  flat (within +/-5%) from: ' + coldA.flatFromCalls + ' calls; a warmup of ' +
        WARMUP_FLOOR + ' has ' + H.fmt(coldA.marginAtWarmup) + 'x margin');
  } else {
    say('  NEVER SETTLED within +/-5% over ' + SWEEP_REPS + ' repetitions on this engine.');
  }
  say('');
  say('  A ratio near 1.00x means this engine has no tier-up to warm and a large warmup buys');
  say('  nothing. A ratio well above 1 means it does, and the repetition at which the series');
  say('  settles multiplied by the iteration count is the warmup the boundary rows need.');
  say('');

  // ---- 2. The same sweep, warm: the reference project's control -------------------------------
  // Its 40-repetition sweep was run cold *and* warm, and the finding was that the two converge on
  // the same value from opposite sides. Running only one of them cannot distinguish "the engine
  // warmed up" from "the machine was busy at the start".
  say('--- the same sweep, but after ' + sweepN * 10 + ' calls of warmup ---');
  H.measure(sweepN * 10, 1, sweepRow.block);
  var warmSeries = H.sweep(SWEEP_REPS, sweepN, sweepRow.block);
  line = [];
  for (var k = 0; k < warmSeries.length; k++) {
    line.push(H.fmt(warmSeries[k]));
    if (line.length === 8) {
      say('  ' + line.join('  '));
      line = [];
    }
  }
  if (line.length) {
    say('  ' + line.join('  '));
  }
  say('');
  say('  warm first repetition:   ' + H.fmt(warmSeries[0]) + ' ns/op');
  say('  warm, min-max overall:   ' + H.fmt(H.min(warmSeries)) + ' - ' + H.fmt(H.max(warmSeries)) + ' ns/op');
  say('');

  // ---- 3. The JS-only rows ---------------------------------------------------------------------
  say('--- JS-only rows (no boundary anywhere in them) ---');
  var out = [];
  for (var r = 0; r < R.rows.length; r++) {
    var row = R.rows[r];
    var n = calibrate(row.block);
    var w = warmupFor(n);
    var readings = H.repeat(REPS, w, n, row.block);
    out.push({name: row.name + '  [n=' + n + ', warmup=' + w + ']', readings: readings});
  }
  say(H.formatRows(out));
  say('');
  say('sink (proves no row was eliminated): ' + H.readSink());
  say('');
})(typeof globalThis !== 'undefined' ? globalThis : this);
