import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

/**
 * The JS -> native boundary, one shape per method, and nothing else in any of them.
 *
 * Every method here is **synchronous**: in the New Architecture a TurboModule method whose return
 * type is not a Promise is invoked on the JS thread through JSI, with no bridge, no serialisation
 * and no scheduler hop. That is the property that makes this comparable with the reference
 * project's upcall (Python -> Kotlin), which is also a direct call on the calling thread.
 *
 * The four shapes mirror `UpcallBoundaryCostTest`'s rows:
 *
 * | here            | reference project                          |
 * |-----------------|--------------------------------------------|
 * | `noop`          | the boundary with no arguments at all       |
 * | `addInts`       | `trampoline.add` -- two ints in, one out    |
 * | `echoString`    | UTF-8 marshalling across the boundary       |
 * | `sumArray`      | collection conversion (JSI Array -> native) |
 * | `sumObject`     | collection conversion (JSI Object -> native)|
 *
 * `nativeLoopNs` and `pingCallback` are controls, not shapes -- see their comments.
 */
export interface Spec extends TurboModule {
  /**
   * The boundary and nothing else: no arguments, a constant back.
   *
   * **This, not `noop`, is the empty-call row.** Codegen marks a method
   * `@ReactMethod(isBlockingSynchronousMethod = true)` only when it returns a value; a `void`
   * method is `VoidKind` and `JavaTurboModule`/`RCTTurboModule` dispatch those onto the module's
   * method queue instead of running them on the JS thread. So `noop()` times an *enqueue*, and
   * only a value-returning method times a crossing that goes there and comes back.
   *
   * That is not a detail to note in passing -- it is the difference between comparing like with
   * like and publishing a boundary cost that is really a queue push. The generated
   * `NativeBenchSpec.java` was read to establish it rather than inferred from the docs.
   */
  zeroArgs(): number;

  /**
   * A `void` method, kept **as a contrast, not as the empty-call row**: it is dispatched
   * asynchronously (see `zeroArgs`), so what it prices is how cheap fire-and-forget is when the
   * caller never waits. Reported in its own line and never subtracted from anything.
   */
  noop(): void;

  /**
   * Two integers in, one out. The same shape as the reference project's `trampoline.add`, so this
   * is the row the cross-project comparison is actually made on.
   *
   * JS numbers are IEEE doubles; codegen types this as `double` on both platforms, so "integer"
   * here means "a number that happens to be integral", exactly as it does in JS.
   */
  addInts(a: number, b: number): number;

  /**
   * String in, the same string out. Charges the crossing for two conversions: a JSI string to the
   * platform's string type on the way in, and back on the way out. The reference project's
   * equivalent is `PyUnicode` <-> UTF-8, which its docs price at "hundreds of ns".
   */
  echoString(s: string): string;

  /** String in, its length out. One conversion instead of two, so the return leg can be priced. */
  stringLength(s: string): number;

  /**
   * An array of numbers in, their sum out. The native side must walk the whole array, which is
   * where a per-element conversion cost shows up if there is one. Call it with several lengths and
   * fit a line: the intercept is the boundary, the slope is the per-element conversion.
   */
  sumArray(xs: Array<number>): number;

  /** An object in, the sum of three known keys out. The map-shaped counterpart of `sumArray`. */
  sumObject(o: Object): number;

  /**
   * A loop that runs entirely on the native side, timed by the native side's own clock, and
   * returns ns/iteration.
   *
   * This is the control that says how much of a measured per-call cost is the boundary and how
   * much is the work behind it: it performs `addInts`' body `iterations` times without crossing
   * anything. The reference project's counterpart is `_pm_time_loop` / the pure-Python callee.
   */
  nativeLoopNs(iterations: number): number;

  /**
   * The other direction: native -> JS.
   *
   * **This is not the symmetric counterpart of the methods above and must not be read as one.**
   * The New Architecture gives app code no synchronous native -> JS entry point: a `Callback`
   * parameter is invoked through the `RuntimeScheduler`, so the figure this produces contains a
   * scheduler hop and a queue drain as well as the crossing. It is measured anyway because it is
   * what an RN app actually pays in that direction, and it is labelled as a round trip everywhere
   * it is reported.
   */
  pingCallback(value: number, cb: (result: number) => void): void;

  /** The Promise-returning round trip, for the same reason and with the same caveat. */
  pingPromise(value: number): Promise<number>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('Bench');
