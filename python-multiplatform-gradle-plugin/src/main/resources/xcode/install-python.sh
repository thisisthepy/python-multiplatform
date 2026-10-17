#!/usr/bin/env bash
# Xcode Run Script phase: put CPython's standard library and the app's python/ payload into the .app.
# SPEC L-11, issue #59. Procedure and reasoning: docs/platforms/ios-app-bundle.md.
#
# Place the phase AFTER "Copy Bundle Resources" and BEFORE "Embed Frameworks", in a target whose
# ENABLE_USER_SCRIPT_SANDBOXING is NO (the phase reads the Gradle build directory). It:
#   1. runs `<gradlew> -q :python-multiplatform:stageIosPythonHomeForXcode`, which stages the stdlib
#      for the slice Xcode is building (EFFECTIVE_PLATFORM_NAME + ARCHS) and prints
#      PYTHON_HOME_DIR= and PYTHON_DYLIB_INFO_TEMPLATE=;
#   2. copies that prefix to <app>/python-multiplatform-home/ (IosPythonHome looks there);
#   3. copies the payload, when one is configured, to <app>/python/ (PythonPayload looks there);
#   4. moves every .so under both into <app>/Frameworks/<dotted.module>.framework, leaving a .fwork
#      placeholder that CPython's AppleFrameworkLoader follows -- upstream's
#      Python.xcframework/build/utils.sh `install_dylib`, because iOS loads no loose binaries.
#
# Environment (all optional except what Xcode sets):
#   GRADLEW              Gradle wrapper, default ./gradlew (the phase cd's to the Gradle root first)
#   PYTHON_HOME_TASK     default :python-multiplatform:stageIosPythonHomeForXcode
#   PYTHON_PAYLOAD_DIR   a directory whose *contents* become <app>/python/ (e.g. the app's own
#                        Python sources, or toolchain's build/pythonStaging/ios/python)
#   PYTHON_PAYLOAD_TASK  instead: a Gradle task printing PYTHON_PAYLOAD_DIR=<abs> under -q, e.g.
#                        toolchain's :app:stagePythonBundleIosForXcode
# With neither payload variable set, no payload is copied and any old <app>/python/ is removed.
set -euo pipefail

: "${CODESIGNING_FOLDER_PATH:?run this from an Xcode build phase (CODESIGNING_FOLDER_PATH is unset)}"
: "${TARGET_BUILD_DIR:?run this from an Xcode build phase (TARGET_BUILD_DIR is unset)}"
: "${UNLOCALIZED_RESOURCES_FOLDER_PATH:?run this from an Xcode build phase}"
: "${EFFECTIVE_PLATFORM_NAME:?run this from an Xcode build phase}"
: "${ARCHS:?run this from an Xcode build phase}"

bundle="$CODESIGNING_FOLDER_PATH"
resources="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
# On iOS the bundle root *is* the resource directory, and both the runtime lookup
# (NSBundle.resourcePath) and the .fwork paths (relative to the executable's directory) rely on it.
if [ "$(cd "$bundle" && pwd -P)" != "$(cd "$resources" && pwd -P)" ]; then
    echo "error: $resources is not the bundle root $bundle; only the flat iOS app layout is supported" >&2
    exit 1
fi

gradlew="${GRADLEW:-./gradlew}"
home_task="${PYTHON_HOME_TASK:-:python-multiplatform:stageIosPythonHomeForXcode}"

# Captured first, parsed second: a failing Gradle must stop the phase here (set -e on the
# assignment), never leave an empty string for rsync to treat as "/".
home_out=$("$gradlew" -q "$home_task")
home_dir=$(printf '%s\n' "$home_out" | sed -n 's/^PYTHON_HOME_DIR=//p')
template=$(printf '%s\n' "$home_out" | sed -n 's/^PYTHON_DYLIB_INFO_TEMPLATE=//p')
[ -n "$home_dir" ] && [ -d "$home_dir" ] || { echo "error: no PYTHON_HOME_DIR from $home_task" >&2; exit 1; }
[ -n "$template" ] && [ -f "$template" ] || { echo "error: no PYTHON_DYLIB_INFO_TEMPLATE from $home_task" >&2; exit 1; }

stdlib_count=$(find "$home_dir"/lib -maxdepth 2 -path '*/python3.*/os.py' | wc -l | tr -d ' ')
[ "$stdlib_count" = "1" ] || { echo "error: $home_dir has $stdlib_count lib/python3.*/os.py, expected 1" >&2; exit 1; }
stdlib_rel=$(cd "$home_dir" && find lib -maxdepth 1 -type d -name 'python3.*' | head -n 1)

echo "Installing the Python standard library for $EFFECTIVE_PLATFORM_NAME/$ARCHS from $home_dir"
rsync -a --delete "$home_dir/" "$bundle/python-multiplatform-home/"

payload_dir="${PYTHON_PAYLOAD_DIR:-}"
if [ -z "$payload_dir" ] && [ -n "${PYTHON_PAYLOAD_TASK:-}" ]; then
    payload_out=$("$gradlew" -q "$PYTHON_PAYLOAD_TASK")
    payload_dir=$(printf '%s\n' "$payload_out" | sed -n 's/^PYTHON_PAYLOAD_DIR=//p')
    [ -n "$payload_dir" ] || { echo "error: no PYTHON_PAYLOAD_DIR from $PYTHON_PAYLOAD_TASK" >&2; exit 1; }
fi
if [ -n "$payload_dir" ]; then
    [ -d "$payload_dir" ] || { echo "error: Python payload directory $payload_dir does not exist" >&2; exit 1; }
    echo "Installing the Python payload from $payload_dir"
    rsync -a --delete --exclude '__pycache__' "$payload_dir/" "$bundle/python/"
else
    echo "No Python payload configured (PYTHON_PAYLOAD_DIR / PYTHON_PAYLOAD_TASK); removing any old one"
    rm -rf "$bundle/python"
fi

# --- extension modules as frameworks (upstream utils.sh install_dylib, with the template staged) ---
sign_identity="${EXPANDED_CODE_SIGN_IDENTITY:-}"

install_dylib() {  # $1 = install base relative to the bundle, with trailing slash; $2 = absolute .so
    local base="$1" ext_path="$2"
    local relative="${ext_path#"$bundle"/}"
    local in_base="${relative#"$base"}"
    local module
    module=$(printf '%s' "$in_base" | cut -d . -f 1 | tr / .)
    local framework="Frameworks/$module.framework"
    local bundle_id
    bundle_id=$(printf '%s' "${PRODUCT_BUNDLE_IDENTIFIER:-python.multiplatform}.$module" | tr _ -)

    if [ ! -d "$bundle/$framework" ]; then
        mkdir -p "$bundle/$framework"
        cp "$template" "$bundle/$framework/Info.plist"
        plutil -replace CFBundleExecutable -string "$module" "$bundle/$framework/Info.plist"
        plutil -replace CFBundleIdentifier -string "$bundle_id" "$bundle/$framework/Info.plist"
    fi
    mv "$ext_path" "$bundle/$framework/$module"
    printf '%s\n' "$framework/$module" > "${ext_path%.so}.fwork"
    printf '%s\n' "${relative%.so}.fwork" > "$bundle/$framework/$module.origin"

    # <dir>/<name>.xcprivacy beside <dir>/<name>.cpython-*.so, as upstream looks for it.
    local privacy
    privacy="$(dirname "$ext_path")/$(basename "$ext_path" | cut -d . -f 1).xcprivacy"
    if [ -e "$privacy" ]; then
        mv -f "$privacy" "$bundle/$framework/PrivacyInfo.xcprivacy"
    fi

    if [ -n "$sign_identity" ]; then
        /usr/bin/codesign --force --sign "$sign_identity" ${OTHER_CODE_SIGN_FLAGS:-} -o runtime \
            --timestamp=none --preserve-metadata=identifier,entitlements,flags \
            --generate-entitlement-der "$bundle/$framework"
    fi
}

process_dylibs() {  # $1 = directory relative to the bundle
    local base="$1"
    [ -d "$bundle/$base" ] || return 0
    local count=0
    while IFS= read -r -d '' ext_path; do
        install_dylib "$base/" "$ext_path"
        count=$((count + 1))
    done < <(find "$bundle/$base" -name '*.so' -print0)
    echo "Wrapped $count extension module(s) under $base as frameworks"
}

process_dylibs "python-multiplatform-home/$stdlib_rel/lib-dynload"
process_dylibs "python"
[ -n "$sign_identity" ] || echo "note: EXPANDED_CODE_SIGN_IDENTITY is empty; extension frameworks were not signed"
