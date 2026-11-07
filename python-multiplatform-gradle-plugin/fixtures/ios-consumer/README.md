# ios-consumer fixture (issue #90)

A Kotlin Multiplatform module that builds an iOS simulator app using only what the bindings plugin
provides. Nothing here defines a Python download, a linker option or an Xcode script.

Layout: `build.gradle.kts` (two `Probe` framework binaries), `src/iosMain/kotlin/ProbeMain.kt` (the
probe), `python/probe_payload.py` (the payload), `xcode/` (the Xcode project).

Run everything from the repository root, with JDK 21 in `JAVA_HOME`. `FX` is the fixture's project path.

```bash
FX=:python-multiplatform-gradle-plugin-fixtures:ios-consumer
mkdir -p .tmp

# 1. Link the framework: downloads and verifies the archive into the fixture's build/python-standalone,
#    stages build/xcode-frameworks/Python.xcframework, links with -framework Python -F<slice>
./gradlew $FX:linkDebugFrameworkIosSimulatorArm64 --console=plain > .tmp/link.log 2>&1; echo "EXIT=$?"

# 2. Stage the stdlib and install the script
EFFECTIVE_PLATFORM_NAME=-iphonesimulator ARCHS=arm64 ./gradlew -q $FX:stageIosPythonHomeForXcode   # PYTHON_HOME_DIR=, PYTHON_DYLIB_INFO_TEMPLATE=
./gradlew $FX:installPythonXcodeScript

# 3. Build the app
xcodebuild -project python-multiplatform-gradle-plugin/fixtures/ios-consumer/xcode/app.xcodeproj \
    -target app -configuration Debug -sdk iphonesimulator ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
    SYMROOT="$PWD/python-multiplatform-gradle-plugin/fixtures/ios-consumer/build/xcode-app" \
    CODE_SIGN_IDENTITY="-" CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="" build > .tmp/xcodebuild.log 2>&1; echo "EXIT=$?"

# 4. Install and launch with no SIMCTL_CHILD_* variable; the probe prints one line
APP=python-multiplatform-gradle-plugin/fixtures/ios-consumer/build/xcode-app/Debug-iphonesimulator/IosConsumer.app
UDID=$(xcrun simctl list devices booted | grep -oE '[0-9A-F-]{36}' | head -n 1)
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch --console-pty --terminate-running-process "$UDID" \
    org.thisisthepy.python.multiplatform.iosconsumer > .tmp/launch.log 2>&1 &
# once the line appears: kill %1; grep IOS_CONSUMER_PROBE .tmp/launch.log
```

Pass: `IOS_CONSUMER_PROBE stdlib=ok ext=ok payload=ok`. The Xcode project was adapted from
`sample/src/iosMain/app.xcodeproj` (not opened in Xcode when written; if `xcodebuild` rejects it, the
phases to recreate are: Compile Kotlin Framework running `$FX:embedAndSignAppleFrameworkForXcode`; and, after
Resources and before Embed Frameworks, a phase running `$FX:installPythonXcodeScript` and then
`build/python-multiplatform/xcode/install-python.sh` with `PYTHON_PAYLOAD_DIR` set to `python/`, with
`ENABLE_USER_SCRIPT_SANDBOXING = NO`).
