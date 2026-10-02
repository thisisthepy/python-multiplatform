#import "Bench.h"

#import <mach/mach_time.h>

@implementation Bench

RCT_EXPORT_MODULE(Bench)

// Every body below is as close to empty as its shape allows: what is timed is the crossing, not
// the callee. `nativeLoopNs` is the one exception and exists to price the callee.

// The boundary's floor: sync (it returns a value), no arguments, constant result.
- (NSNumber *)zeroArgs
{
  return @(0.0);
}

// Deliberately *not* the floor row -- being `void` makes this `VoidKind`, which RCTTurboModule
// dispatches onto the module's method queue instead of running it on the JS thread.
- (void)noop
{
}

- (NSNumber *)addInts:(double)a b:(double)b
{
  return @(a + b);
}

- (NSString *)echoString:(NSString *)s
{
  return s;
}

- (NSNumber *)stringLength:(NSString *)s
{
  return @((double)s.length);
}

- (NSNumber *)sumArray:(NSArray *)xs
{
  double total = 0.0;
  for (NSNumber *n in xs) {
    total += n.doubleValue;
  }
  return @(total);
}

- (NSNumber *)sumObject:(NSDictionary *)o
{
  double total = 0.0;
  total += [(NSNumber *)o[@"a"] doubleValue];
  total += [(NSNumber *)o[@"b"] doubleValue];
  total += [(NSNumber *)o[@"c"] doubleValue];
  return @(total);
}

- (NSNumber *)nativeLoopNs:(double)iterations
{
  long long n = (long long)iterations;
  if (n <= 0) {
    return @(0.0);
  }

  static mach_timebase_info_data_t timebase;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    mach_timebase_info(&timebase);
  });

  // `sink` is folded into the return value so the loop cannot be optimised away.
  volatile double sink = 0.0;
  uint64_t t0 = mach_absolute_time();
  for (long long i = 0; i < n; i++) {
    sink += 3.0 + 4.0;
  }
  uint64_t elapsed = mach_absolute_time() - t0;
  double ns = (double)elapsed * (double)timebase.numer / (double)timebase.denom;
  return @(ns / (double)n + sink * 0.0);
}

- (void)pingCallback:(double)value cb:(RCTResponseSenderBlock)cb
{
  cb(@[ @(value) ]);
}

- (void)pingPromise:(double)value
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  resolve(@(value));
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeBenchSpecJSI>(params);
}

@end
