/**
 * The rows that need no native module: the JS-side floor every boundary figure is read against.
 *
 * These correspond one for one to the reference project's `_pm_time_loop` (the empty loop) and
 * `_pm_py_add` (a callee of the same shape with no boundary in it). Their job is to say what the
 * *language* charges for the loop and the call, so that "the crossing costs X" means X above that
 * floor rather than X including it.
 *
 * They also run standalone, under the host Hermes and Node binaries, which is how the warmup
 * question gets an answer without a device.
 */
(function (root, factory) {
  var harness =
    typeof module !== 'undefined' && module.exports ? require('./harness.js') : root.BenchHarness;
  var api = factory(harness);
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  } else {
    root.BenchJsRows = api;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function (H) {
  'use strict';

  // Declared at module scope so the shapes stay stable across every call: a callee redefined per
  // iteration would be measuring the host's function-allocation path instead of its call path.
  function jsAdd(a, b) {
    return a + b;
  }

  function jsEchoString(s) {
    return s;
  }

  function jsSumArray(xs) {
    var total = 0;
    for (var i = 0; i < xs.length; i++) {
      total += xs[i];
    }
    return total;
  }

  function jsSumObject(o) {
    return o.a + o.b + o.c;
  }

  // The payloads, built once. Building them inside a timed loop would price allocation, which is a
  // real cost but a different one, and it is charged separately by the `...built per call` rows.
  var SHORT_STRING = 'abcdefgh';
  var LONG_STRING = new Array(129).join('x'); // 128 chars
  var ARRAY_8 = [1, 2, 3, 4, 5, 6, 7, 8];
  var ARRAY_1000 = (function () {
    var a = [];
    for (var i = 0; i < 1000; i++) {
      a.push(i);
    }
    return a;
  })();
  var OBJECT_3 = {a: 1, b: 2, c: 3};

  /**
   * `{name, block}` in the order they should be run.
   *
   * `block` takes the loop index and returns a value; the harness writes that value into a sink it
   * can read back, so no row can be eliminated as dead.
   */
  var rows = [
    {
      name: 'empty JS loop (the loop itself)',
      block: function (i) {
        return i;
      },
    },
    {
      name: 'JS call, same shape as addInts',
      block: function () {
        return jsAdd(3, 4);
      },
    },
    {
      name: 'JS call returning a string arg',
      block: function () {
        return jsEchoString(SHORT_STRING);
      },
    },
    {
      name: 'JS sum of an 8-element array',
      block: function () {
        return jsSumArray(ARRAY_8);
      },
    },
    {
      name: 'JS sum of a 1000-element array',
      block: function () {
        return jsSumArray(ARRAY_1000);
      },
    },
    {
      name: 'JS sum of a 3-key object',
      block: function () {
        return jsSumObject(OBJECT_3);
      },
    },
    {
      name: 'JS string of 128 chars, built per call',
      block: function (i) {
        return ('' + i + LONG_STRING).length;
      },
    },
  ];

  return {
    rows: rows,
    payloads: {
      SHORT_STRING: SHORT_STRING,
      LONG_STRING: LONG_STRING,
      ARRAY_8: ARRAY_8,
      ARRAY_1000: ARRAY_1000,
      OBJECT_3: OBJECT_3,
    },
    fns: {
      jsAdd: jsAdd,
      jsEchoString: jsEchoString,
      jsSumArray: jsSumArray,
      jsSumObject: jsSumObject,
    },
    harness: H,
  };
});
