#!/usr/bin/env bash
# Assembles the assets of a desktop-only GitHub Release (no Maven publishing): run from the repository root.
#   .github/scripts/release/assemble-desktop-release.sh <out-dir>
# Produces in <out-dir>:
#   python-multiplatform-maven-repo-<version>.zip   a Maven repository (org/thisisthepy/...): the library's
#                                                   desktop variant and KMP root, the KSP processor with its
#                                                   typedpython-gate zip, the Gradle plugin and its marker
#   typedpython-<pyversion>-py3-none-any.whl        the TypedPython compiler (pure Python + its C runtime header)
#   python-multiplatform-ksp-<version>-typedpython-gate.zip
#   python-multiplatform-examples-<version>.zip     docs/release/desktop (the examples below, verified per release)
#   SHA256SUMS
set -euo pipefail
out="$(mkdir -p "${1:?usage: assemble-desktop-release.sh <out-dir>}" && cd "$1" && pwd)"
root="$(pwd)"
version="$(sed -nE 's/^pythonVersion=(.*)$/\1/p' gradle.properties)-$(sed -nE 's/^libraryVersionSuffix=(.*)$/\1/p' gradle.properties)"
repo="$out/maven-repo"
rm -rf "$repo"
mkdir -p "$repo"

# The desktop variant and the KMP root metadata of the library, the KSP processor, and the Gradle plugin
# (an included build: its own invocation), all into one repository directory, never ~/.m2.
./gradlew --console=plain -Dmaven.repo.local="$repo" \
    :python-multiplatform:publishDesktopPublicationToMavenLocal \
    :python-multiplatform:publishKotlinMultiplatformPublicationToMavenLocal \
    :python-multiplatform-ksp:publishToMavenLocal
./gradlew --console=plain -p python-multiplatform-gradle-plugin -Dmaven.repo.local="$repo" publishToMavenLocal

test -f "$repo/org/thisisthepy/python-multiplatform-desktop/$version/python-multiplatform-desktop-$version.jar"
test -f "$repo/org/thisisthepy/python-multiplatform-ksp/$version/python-multiplatform-ksp-$version-typedpython-gate.zip"
test -d "$repo/org/thisisthepy/python/multiplatform/bindings/org.thisisthepy.python.multiplatform.bindings.gradle.plugin"
if find "$repo" -path '*io/github/thisisthepy*' | grep -q .; then
    echo "error: an io.github.thisisthepy coordinate was published (#95)" >&2; exit 1
fi
(cd "$out" && rm -f "python-multiplatform-maven-repo-$version.zip" && zip -qr "python-multiplatform-maven-repo-$version.zip" maven-repo)
cp "$repo/org/thisisthepy/python-multiplatform-ksp/$version/python-multiplatform-ksp-$version-typedpython-gate.zip" "$out/"

# The TypedPython wheel.
rm -f "$out"/typedpython-*.whl
uv build --wheel --out-dir "$out" python-multiplatform-ksp >/dev/null

# The examples, with the release version written into them.
ex="$out/examples"
rm -rf "$ex"
cp -R docs/release/desktop "$ex"
find "$ex" -type f \( -name '*.kts' -o -name '*.md' -o -name '*.properties' \) -exec sed -i.bak "s/@VERSION@/$version/g" {} +
find "$ex" -name '*.bak' -delete
(cd "$out" && rm -f "python-multiplatform-examples-$version.zip" && zip -qr "python-multiplatform-examples-$version.zip" examples)

(cd "$out" && rm -f SHA256SUMS && shasum -a 256 python-multiplatform-maven-repo-*.zip typedpython-*.whl \
    python-multiplatform-ksp-*-typedpython-gate.zip python-multiplatform-examples-*.zip > SHA256SUMS)
echo "version=$version"
ls -1 "$out" | grep -v '^maven-repo$\|^examples$'
