/**
 * The measurement harness. Deliberately a copy of the reference project's
 * `python-multiplatform/src/commonTest/kotlin/python/multiplatform/overhead/Benchmark.kt`, because
 * two boundary costs measured by two different loop shapes are not comparable and the whole point
 * of this repository is that they should be.
 *
 * What is copied, and why each part is not optional:
 *
 * - **Warmup of the same shape and the same block as the timed loop.** Not a fixed sleep, not a
 *   different block. In the reference project an unwarmed first row once made a subset of the work
 *   look cheaper than the whole of it.
 * - **A warmup count large enough to reach the plateau, established by sweep rather than picked.**
 *   The reference project settled on 100 000 after a 40-repetition sweep showed the JVM and V8
 *   converging on a steady value from both sides. `sweep()` below is that experiment, so the same
 *   question can be asked of Hermes instead of assumed.
 * - **min-max over repetitions with no outlier dropped.** A single reading is not a measurement,
 *   and a dropped outlier hides the case where the host's spread is larger than the effect.
 * - **Baselines measured in the same run.** A ratio against a number from another process, another
 *   build or another machine is not a ratio. Every row a comparison is made against is produced by
 *   the same `measure()` in the same session.
 *
 * Written as UMD-ish plain JS on purpose: the same file is loaded by Metro (CommonJS) inside the
 * app and by the standalone Hermes and Node binaries (no module system) for the host-side sweeps.
 */
(function (root, factory) {
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  } else {
    root.BenchHarness = api;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  /**
   * The clock, and its name, because the name changes what a figure is worth.
   *
   * `performance.now()` is sub-millisecond and is what the app has. Standalone Hermes has no
   * `performance` at all, so the host sweeps fall back to `Date.now()` at 1 ms -- which is why
   * `suggestIterations` below asks for far more iterations there. A row timed on a 1 ms clock over
   * 10 000 iterations of a 5 ns operation would read 0, and "the boundary is free" is exactly the
   * failure this guards against.
   */
  function makeClock() {
    if (typeof performance !== 'undefined' && performance && typeof performance.now === 'function') {
      return {
        name: 'performance.now',
        resolutionNs: 1e3, // conservative; RN reports at least microsecond granularity
        nowNs: function () {
          return performance.now() * 1e6;
        },
      };
    }
    return {
      name: 'Date.now',
      resolutionNs: 1e6,
      nowNs: function () {
        return Date.now() * 1e6;
      },
    };
  }

  var clock = makeClock();

  /**
   * A sink the loops write into, read back by `readSink()`.
   *
   * Without it an empty-bodied row is dead code, and a host that eliminates it reports the boundary
   * as free. Kept on the module object rather than in a local so nothing can prove it unused.
   */
  var sink = 0;

  function readSink() {
    return sink;
  }

  /**
   * The reference project's `Benchmark.measure`, one for one: warm with the same block, then time
   * the same block, then divide. Returns ns per operation.
   */
  function measure(warmupIterations, iterations, block) {
    for (var w = 0; w < warmupIterations; w++) {
      sink = block(w);
    }

    var t0 = clock.nowNs();
    for (var i = 0; i < iterations; i++) {
      sink = block(i);
    }
    var t1 = clock.nowNs();

    return (t1 - t0) / iterations;
  }

  /**
   * `measure` run `reps` times over, returning every reading.
   *
   * The caller reports min-max over these. Nothing here averages and nothing here discards: the
   * spread is part of the result, and on an emulator it can be larger than what is being measured.
   */
  function repeat(reps, warmupIterations, iterations, block) {
    var readings = [];
    for (var r = 0; r < reps; r++) {
      readings.push(measure(warmupIterations, iterations, block));
    }
    return readings;
  }

  /**
   * The convergence experiment, not a benchmark.
   *
   * Runs the timed loop `reps` times back to back **with no warmup at all before the first**, and
   * returns every reading in order. If the host tiers up -- JIT compilation, inline caches, shape
   * feedback -- the early readings are high and the series settles; the index at which it settles
   * is the warmup count the real rows need. If the host does not tier up, the series is flat from
   * the first reading, and a large warmup buys nothing.
   *
   * The reference project ran exactly this, 40 repetitions, and read the JVM converging from 1307
   * to ~535 ns and V8 similarly. That is where its 100 000 came from. Hermes is a different kind of
   * engine and the answer should not be carried over from those two without asking.
   */
  function sweep(reps, iterations, block) {
    var readings = [];
    for (var r = 0; r < reps; r++) {
      var t0 = clock.nowNs();
      for (var i = 0; i < iterations; i++) {
        sink = block(i);
      }
      var t1 = clock.nowNs();
      readings.push((t1 - t0) / iterations);
    }
    return readings;
  }

  /**
   * Where a sweep settles, in the same terms the reference project's device sweep table uses
   * (`docs/design/upcall.md` §7.2): first repetition over the plateau,
   * the call count from which the series stays flat, and the margin a given warmup has over it.
   *
   * The plateau is the mean of the second half, so it cannot be dragged by the early readings the
   * analysis is trying to identify. "Flat from" is the **last** repetition that is outside the band,
   * not the first one inside it: a series that dips into the band and back out has not settled, and
   * scanning forwards would call it settled at the dip. The reference project's ART `pmp_api36`
   * sweep is exactly that shape -- 1218, 1173, 1129, **1532**, 1057 -- so this is not hypothetical.
   *
   * `band` is printed with the result rather than hidden, because the answer depends on it: at 5%
   * that ART series settles at ~90 000 calls and at 10% it settles at ~50 000, and quoting either
   * without the threshold is quoting a number nobody can reproduce.
   */
  function analyzeSweep(readings, iterationsPerRep, warmupUnderTest, band) {
    band = band || 0.05;
    var half = Math.floor(readings.length / 2);
    var sum = 0;
    for (var i = half; i < readings.length; i++) {
      sum += readings[i];
    }
    var plateau = sum / (readings.length - half);

    var lastOutside = -1;
    for (var j = 0; j < readings.length; j++) {
      if (plateau > 0 && Math.abs(readings[j] - plateau) / plateau > band) {
        lastOutside = j;
      }
    }
    // Repetition `lastOutside` is the final bad one, so the series is flat from the one after it,
    // by which point `(lastOutside + 1) * iterationsPerRep` calls have been made.
    var flatFromCalls = (lastOutside + 1) * iterationsPerRep;

    return {
      band: band,
      plateau: plateau,
      first: readings[0],
      firstOverPlateau: plateau > 0 ? readings[0] / plateau : 0,
      flatFromCalls: flatFromCalls,
      settled: lastOutside < readings.length - 1,
      marginAtWarmup: flatFromCalls > 0 ? warmupUnderTest / flatFromCalls : Infinity,
      warmupUnderTest: warmupUnderTest,
    };
  }

  /** `analyzeSweep`'s result plus the series itself, formatted eight readings to a line. */
  function formatSweep(title, readings, iterationsPerRep, warmupUnderTest, band) {
    var a = analyzeSweep(readings, iterationsPerRep, warmupUnderTest, band);
    var out = [];
    out.push('--- convergence sweep: ' + title + ' ---');
    out.push(
      '  ' + readings.length + ' repetitions of ' + iterationsPerRep +
        ' calls, no warmup before the first',
    );
    var line = [];
    for (var i = 0; i < readings.length; i++) {
      line.push(padLeft(fmt(readings[i]), 9));
      if (line.length === 8) {
        out.push('   ' + line.join(' '));
        line = [];
      }
    }
    if (line.length) {
      out.push('   ' + line.join(' '));
    }
    out.push('');
    out.push('  plateau (mean of 2nd half):  ' + fmt(a.plateau) + ' ns/op');
    out.push('  first repetition:            ' + fmt(a.first) + ' ns/op  (' + fmt(a.firstOverPlateau) + 'x the plateau)');
    if (a.settled) {
      out.push(
        '  flat (within +/-' + (a.band * 100).toFixed(0) + '%) from:   ' + a.flatFromCalls + ' calls',
      );
      out.push(
        '  margin at a warmup of ' + a.warmupUnderTest + ':  ' + fmt(a.marginAtWarmup) + 'x' +
          (a.marginAtWarmup < 1.5 ? '   <-- THIN. A slower or busier host would read this row unsettled.' : ''),
      );
    } else {
      out.push(
        '  NEVER SETTLED within +/-' + (a.band * 100).toFixed(0) + '% over ' + readings.length +
          ' repetitions. This sweep does not license any warmup count; lengthen it.',
      );
    }
    return {text: out.join('\n'), analysis: a};
  }

  function min(xs) {
    var m = xs[0];
    for (var i = 1; i < xs.length; i++) {
      if (xs[i] < m) {
        m = xs[i];
      }
    }
    return m;
  }

  function max(xs) {
    var m = xs[0];
    for (var i = 1; i < xs.length; i++) {
      if (xs[i] > m) {
        m = xs[i];
      }
    }
    return m;
  }

  function fmt(v) {
    if (!isFinite(v)) {
      return String(v);
    }
    return v.toFixed(2);
  }

  function pad(s, n) {
    s = String(s);
    while (s.length < n) {
      s += ' ';
    }
    return s;
  }

  function padLeft(s, n) {
    s = String(s);
    while (s.length < n) {
      s = ' ' + s;
    }
    return s;
  }

  /** `name` -> min-max, in the order given. Nothing is sorted; the order is the order run. */
  function formatRows(rows) {
    var width = 34;
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].name.length > width) {
        width = rows[i].name.length;
      }
    }
    var out = [];
    out.push(pad('row', width) + ' | ' + padLeft('min ns/op', 12) + ' | ' + padLeft('max ns/op', 12) + ' | reps');
    out.push(new Array(width + 36).join('-'));
    for (var j = 0; j < rows.length; j++) {
      var r = rows[j];
      out.push(
        pad(r.name, width) +
          ' | ' +
          padLeft(fmt(min(r.readings)), 12) +
          ' | ' +
          padLeft(fmt(max(r.readings)), 12) +
          ' | ' +
          r.readings.length,
      );
    }
    return out.join('\n');
  }

  /**
   * The iteration count a row needs so that the *total* timed interval is at least 200 clock ticks.
   *
   * This is the guard against reporting a number the clock cannot represent. With `Date.now()` at
   * 1 ms and an operation around 10 ns, it asks for 20 000 000 iterations; with `performance.now()`
   * it asks for far fewer. The caller may pass a floor.
   */
  function suggestIterations(expectedNsPerOp, floor) {
    var needed = Math.ceil((clock.resolutionNs * 200) / Math.max(expectedNsPerOp, 0.5));
    return Math.max(needed, floor || 0);
  }

  return {
    clock: clock,
    measure: measure,
    repeat: repeat,
    sweep: sweep,
    analyzeSweep: analyzeSweep,
    formatSweep: formatSweep,
    formatRows: formatRows,
    suggestIterations: suggestIterations,
    readSink: readSink,
    min: min,
    max: max,
    fmt: fmt,
  };
});
