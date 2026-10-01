#import <Foundation/Foundation.h>
#import <RNBenchSpec/RNBenchSpec.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * The native half of the boundary on iOS.
 *
 * Structurally this is the cheaper of the two platforms: the generated `NativeBenchSpecJSI` shim
 * calls straight into these Objective-C methods, so a JS -> native call crosses **one** boundary
 * (JSI/C++ -> ObjC message send) where Android crosses two (JSI/C++ -> JNI -> JVM). The reference
 * project has the same asymmetry in the same direction, and for the same reason.
 */
@interface Bench : NSObject <NativeBenchSpec>
@end

NS_ASSUME_NONNULL_END
