#!/bin/bash
# Compiles the benchmark module's Objective-C++ against the CocoaPods header tree and the codegen
# output, with clang directly. **This is not a substitute for building the app** -- it does not
# link, and it does not exercise the app target. What it does establish is the thing the app build
# would otherwise be the only witness to: that `Bench.mm` satisfies the generated
# `NativeBenchSpec` protocol, that the JSI shim it names exists, and that the selectors match.
#
# It exists because on this machine `xcodebuild` cannot build for iOS at all -- Xcode 26.6 reports
# "iOS 26.5 is not installed" and offers zero iOS destinations for *any* project, including the
# Pods project's own targets. That is a missing Xcode platform component, not a project defect, and
# it is not fixable without a multi-gigabyte download onto an internal disk that does not have the
# room. See COMPARISON.md, "What is blocked".
#
#   ./check-ios-module.sh   -> compiles Bench.mm, prints the object file's size, exit 0 on success
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/env.sh"

IOS="$HERE/RNBench/ios"
PODS="$IOS/Pods"
SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
OUT="$HERE/build-check"
mkdir -p "$OUT"

# The same search paths CocoaPods generated for this pod target, taken from its xcconfig rather
# than guessed, plus the generated-code directory the app target adds.
INCLUDES=()
INCLUDES+=(-I "$PODS/Headers/Private")
INCLUDES+=(-I "$PODS/Headers/Private/react-native-bench")
INCLUDES+=(-I "$PODS/Headers/Public")
INCLUDES+=(-I "$PODS/React-Core-prebuilt/Headers")
INCLUDES+=(-I "$PODS/ReactNativeDependencies/Headers")
INCLUDES+=(-I "$IOS/build/generated/ios")
while IFS= read -r d; do
  INCLUDES+=(-I "$d")
done < <(find "$PODS/Headers/Public" -maxdepth 1 -type d | tail -n +2)

# `#import <React/RCTBridgeModule.h>` resolves through a framework search path, not a header one:
# React ships as a prebuilt xcframework in RN 0.87, so the simulator slice has to be named.
FRAMEWORKS=()
FRAMEWORKS+=(-F "$PODS/React-Core-prebuilt/React.xcframework/ios-arm64_x86_64-simulator")
for x in "$PODS"/ReactNativeDependencies/framework/packages/react-native/*.xcframework; do
  [ -d "$x/ios-arm64_x86_64-simulator" ] && FRAMEWORKS+=(-F "$x/ios-arm64_x86_64-simulator")
done
for x in "$PODS"/hermes-engine/destroot/Library/Frameworks/universal/*.xcframework; do
  [ -d "$x/ios-arm64_x86_64-simulator" ] && FRAMEWORKS+=(-F "$x/ios-arm64_x86_64-simulator")
done

exec xcrun clang \
  -c "$HERE/RNBench/modules/bench/ios/Bench.mm" \
  -o "$OUT/Bench.o" \
  -isysroot "$SDK" \
  "${FRAMEWORKS[@]}" \
  -target arm64-apple-ios15.1-simulator \
  -std=c++20 \
  -fobjc-arc \
  -DRCT_NEW_ARCH_ENABLED=1 \
  -DCOCOAPODS=1 \
  -Wno-nullability-completeness \
  -Werror=protocol \
  -Werror=incomplete-implementation \
  "${INCLUDES[@]}"
