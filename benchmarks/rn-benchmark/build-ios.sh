#!/bin/bash
# Builds the iOS app for the simulator. A build never boots or touches a simulator, so this is safe
# to run while something else is using them.
#
# --- why this does not use -workspace/-scheme/-destination ---------------------------------------
#
# On this machine Xcode 26.6 refuses to enumerate ANY iOS destination, simulator ones included:
#
#   $ xcodebuild -showdestinations -workspace RNBench.xcworkspace -scheme RNBench
#         Ineligible destinations for the "RNBench" scheme:
#                 { platform:iOS, id:dvtdevice-DVTiPhonePlaceholder-iphoneos:placeholder,
#                   name:Any iOS Device, error:iOS 26.5 is not installed. Please download and
#                   install the platform from Xcode > Settings > Components. }
#
# Not one simulator destination is listed, eligible or ineligible. The same output comes back for
# the Pods project's own targets and for an unrelated project elsewhere on this machine, so it is a
# property of the Xcode install, not of this project.
#
# It is NOT a missing simulator runtime, which is what an earlier pass concluded. Seven iOS runtimes
# are installed and every one reports Ready and isAvailable=true, including iOS 26.2 (23C54) -- the
# runtime the reference project measures its iosSimulatorArm64 column on. `xcrun xcdevice list`
# shows all of their devices with available=true. CoreSimulator is entirely healthy; it is Xcode's
# scheme-destination layer that will not accept an SDK (26.5) with no exactly-matching platform
# component installed.
#
# The destination system is only consulted for -scheme builds. A target build with -sdk resolves
# straight to the SDK, which is present and usable (SDKROOT=iPhoneSimulator26.5.sdk), so that is
# what this uses. Nothing is downloaded and no runtime is needed at build time -- the deployment
# target is 15.1 and every installed runtime clears it.
#
# CocoaPods' Pods-RNBench target is built first because a -project build resolves no cross-project
# dependency for us; both invocations share one SYMROOT so the app finds libPods-RNBench.a.
#
# --- why LaunchScreen.storyboard is excluded ------------------------------------------------------
#
# One tool still consults the platform check that the SDK path avoids: `ibtool`. With everything
# else compiled and linked, the build ended on
#
#   CompileStoryboard .../RNBench/LaunchScreen.storyboard
#   /* com.apple.ibtool.errors */
#   .../LaunchScreen.storyboard: error: iOS 26.5 Platform Not Installed.
#
# clang, swiftc, actool and the linker all worked; only ibtool refuses. EXCLUDED_SOURCE_FILE_NAMES
# drops the storyboard from the resource phase, and the app builds and launches without it. What is
# lost is the launch image: Info.plist still names UILaunchStoryboardName=LaunchScreen and the
# resource is absent, so the launch placeholder is blank. That has no effect on the benchmark, which
# times calls after the JS runtime is up, but it does mean this app is not shippable as built.
#
#   ./build-ios.sh            Release (default -- see run-bench.sh for why Release)
#   ./build-ios.sh Debug      Debug; needs a Metro server at run time, so not what the bench uses
#
# Derived data goes to the external SSD: an RN build tree is several GB and the internal disk does
# not have it.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/env.sh"

CONFIG="${1:-Release}"
SYM="$HERE/DerivedData/Build/Products"
OBJ="$HERE/DerivedData/Build/Intermediates.noindex"

cd "$HERE/RNBench/ios" || exit 1

common=(
  -sdk iphonesimulator
  -configuration "$CONFIG"
  SYMROOT="$SYM"
  OBJROOT="$OBJ"
  ARCHS=arm64
  ONLY_ACTIVE_ARCH=NO
  CODE_SIGNING_ALLOWED=NO
  CODE_SIGNING_REQUIRED=NO
  CODE_SIGN_IDENTITY=
  EXCLUDED_SOURCE_FILE_NAMES=LaunchScreen.storyboard
)

echo "### building Pods-RNBench ($CONFIG)"
xcodebuild -project Pods/Pods.xcodeproj -target Pods-RNBench "${common[@]}" build || exit $?

echo "### building RNBench ($CONFIG)"
exec xcodebuild -project RNBench.xcodeproj -target RNBench "${common[@]}" build
