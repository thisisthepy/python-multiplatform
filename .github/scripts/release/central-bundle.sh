#!/usr/bin/env bash
# Builds the Maven Central deployment of python-multiplatform: every component, signed, in one zip.
#   .github/scripts/release/central-bundle.sh <out.zip> [--throwaway-key]
# Run from the repository root. Reads MAVEN_GPG_PRIVATE_KEY and MAVEN_GPG_PASSPHRASE (Gradle signs
# in memory, gradle/central-publishing.gradle.kts). --throwaway-key makes a key for this run only,
# for a dry run that must prove signing works without the release key. Without either, the bundle is
# unsigned and the script says so; Central would refuse it.
# Writes build/central-staging (a Maven layout) and <out.zip>; uploads nothing, see central-upload.sh.
set -euo pipefail
out="${1:?usage: central-bundle.sh <out.zip> [--throwaway-key]}"
mode="${2:-}"
root="$(pwd)"
staging="$root/build/central-staging"
version="$(sed -nE 's/^pythonVersion=(.*)$/\1/p' gradle.properties)-$(sed -nE 's/^libraryVersionSuffix=(.*)$/\1/p' gradle.properties)"

# Not under the repository: gpg-agent's socket path must stay under about 100 characters.
verify_home="$(mktemp -d)"
chmod 700 "$verify_home"
trap 'rm -rf "$verify_home"' EXIT

if [[ "$mode" == "--throwaway-key" ]]; then
    GNUPGHOME="$verify_home" gpg --batch --quiet --passphrase '' --quick-generate-key \
        "python-multiplatform dry run <dry-run@invalid>" rsa3072 sign 1d
    MAVEN_GPG_PRIVATE_KEY="$(GNUPGHOME="$verify_home" gpg --batch --pinentry-mode loopback --passphrase '' --armor --export-secret-keys)"
    export MAVEN_GPG_PRIVATE_KEY MAVEN_GPG_PASSPHRASE=""
elif [[ -n "${MAVEN_GPG_PRIVATE_KEY:-}" ]]; then
    GNUPGHOME="$verify_home" gpg --batch --quiet --import <<< "$MAVEN_GPG_PRIVATE_KEY"
fi
signed=false
[[ -n "${MAVEN_GPG_PRIVATE_KEY:-}" ]] && signed=true

mkdir -p "$root/.cache"
rm -rf "$staging"
# Gradle reads the key from the environment; the plugin build is a separate build but inherits it.
./gradlew --console=plain publishAllToCentralStaging 2>&1 | tee "$root/.cache/central-gradle.log" || { echo "error: gradle failed (pipe status ${PIPESTATUS[0]})" >&2; exit 1; }

# What Central checks first: every module has pom + jar/klib/zip, sources and javadoc where it is a
# jar publication, and (signed) a good .asc beside every file.
fail=0
while IFS= read -r pom; do
    dir="$(dirname "$pom")"; base="$(basename "$pom" .pom)"
    ver="$(basename "$dir")"; art="$(basename "$(dirname "$dir")")"
    [[ "$ver" == "$version" ]] || { echo "error: $pom is not at $version" >&2; fail=1; }
    grep -q '<url>https://github.com/thisisthepy/python-multiplatform</url>' "$pom" || { echo "error: $pom has no project url" >&2; fail=1; }
    grep -q '<licenses>' "$pom" && grep -q '<developers>' "$pom" && grep -q '<scm>' "$pom" || { echo "error: $pom lacks licenses, developers or scm" >&2; fail=1; }
    if ! grep -q '<packaging>pom</packaging>' "$pom"; then
        [[ -e "$dir/$base-javadoc.jar" ]] || { echo "error: $art has no javadoc jar" >&2; fail=1; }
        [[ -e "$dir/$base-sources.jar" ]] || { echo "error: $art has no sources jar" >&2; fail=1; }
    fi
done < <(find "$staging" -name '*.pom')
# An AAR must carry the native libraries of this pythonVersion only. A Copy-staged jniLibs tree once
# kept libpython3.14.so beside libpython3.15.so after a version bump. libpython3.so (the stable-ABI
# stub) carries no minor version and is allowed.
py_mm="$(sed -nE 's/^pythonVersion=([0-9]+\.[0-9]+).*$/\1/p' gradle.properties)"
while IFS= read -r aar; do
    stale="$(unzip -Z1 "$aar" | grep -E '(^|/)lib(multiplatform_)?python[0-9]+\.[0-9]+[^/]*\.so$' | grep -vE "(^|/)lib(multiplatform_)?python${py_mm//./\\.}(t)?\.so$" || true)"
    [[ -z "$stale" ]] || { echo "error: $aar carries native libraries for another python than $py_mm:" >&2; echo "$stale" >&2; fail=1; }
done < <(find "$staging" -name '*.aar')
if $signed; then
    while IFS= read -r f; do
        case "$f" in *.asc|*.md5|*.sha1|*.sha256|*.sha512) continue ;; esac
        [[ -e "$f.asc" ]] || { echo "error: $f is not signed" >&2; fail=1; continue; }
        GNUPGHOME="$verify_home" gpg --batch --quiet --verify "$f.asc" "$f" 2>/dev/null || { echo "error: bad signature on $f" >&2; fail=1; }
    done < <(find "$staging" -type f -not -name 'maven-metadata*')
else
    echo "warning: no signing key, the bundle is unsigned and Central will refuse it" >&2
fi
(( fail == 0 )) || exit 1

echo "== left out of the bundle (not built on this machine)"
grep -h "CENTRAL-SKIPPED" "$root/.cache/central-gradle.log" || echo "(none)"
echo "== coordinates in the bundle"
find "$staging" -name '*.pom' | while IFS= read -r pom; do
    rel="${pom#"$staging"/}"; ver="$(basename "$(dirname "$rel")")"; art="$(basename "$(dirname "$(dirname "$rel")")")"
    grp="$(dirname "$(dirname "$(dirname "$rel")")" | tr / .)"
    echo "$grp:$art:$ver"
done | sort

mkdir -p "$(dirname "$out")"
out="$(cd "$(dirname "$out")" && pwd)/$(basename "$out")"
rm -f "$out"
(cd "$staging" && zip -qr "$out" . -x 'maven-metadata*' '*/maven-metadata*')
echo "== bundle $out ($(find "$staging" -type f | wc -l | tr -d ' ') files)"
