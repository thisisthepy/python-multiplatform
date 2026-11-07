#!/usr/bin/env bash
# Uploads a bundle made by central_bundle.py to the Sonatype Central Portal and waits for its
# validation.
#
# Usage: central-upload.sh <bundle.zip> <deployment name> [USER_MANAGED|AUTOMATIC]
#
# Reads CENTRAL_TOKEN_USERNAME and CENTRAL_TOKEN_PASSWORD, the two halves of a Central Portal
# user token (central.sonatype.com, View Account, Generate User Token). The Portal API takes
# them as a bearer token of base64("<username>:<password>").
#
# USER_MANAGED (the default) stops after validation: the deployment waits in the Portal under
# Deployments until someone presses Publish or Drop, so a person looks at it before it becomes
# permanent. AUTOMATIC publishes as soon as validation passes. Either way nothing published to
# Central can be changed or removed afterwards.
#
# Exits 0 when the deployment is VALIDATED (USER_MANAGED) or PUBLISHING/PUBLISHED (AUTOMATIC),
# 1 when it FAILED, printing the Portal's errors, and 2 on a usage or network error.
set -euo pipefail

API="https://central.sonatype.com/api/v1/publisher"

bundle="${1:?usage: central-upload.sh <bundle.zip> <deployment name> [USER_MANAGED|AUTOMATIC]}"
name="${2:?usage: central-upload.sh <bundle.zip> <deployment name> [USER_MANAGED|AUTOMATIC]}"
type="${3:-USER_MANAGED}"
case "$type" in USER_MANAGED|AUTOMATIC) ;; *) echo "error: publishing type '$type'" >&2; exit 2 ;; esac
[[ -s "$bundle" ]] || { echo "error: no bundle at $bundle" >&2; exit 2; }
[[ -n "${CENTRAL_TOKEN_USERNAME:-}" && -n "${CENTRAL_TOKEN_PASSWORD:-}" ]] || {
    echo "error: CENTRAL_TOKEN_USERNAME and CENTRAL_TOKEN_PASSWORD must hold a Central Portal user token" >&2
    exit 2
}

token="$(printf '%s:%s' "$CENTRAL_TOKEN_USERNAME" "$CENTRAL_TOKEN_PASSWORD" | base64 | tr -d '\n')"
auth="Authorization: Bearer $token"

encoded_name="$(python3 -I -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$name")"
echo "==> uploading $bundle as '$name' ($type)"
id="$(curl --fail-with-body --silent --show-error --retry 3 \
    --header "$auth" \
    --form "bundle=@$bundle" \
    "$API/upload?publishingType=$type&name=$encoded_name")"
echo "deployment $id"

# Validation of a large bundle takes minutes; give it an hour.
for _ in $(seq 1 120); do
    status="$(curl --fail-with-body --silent --show-error --retry 3 --request POST \
        --header "$auth" "$API/status?id=$id")"
    state="$(python3 -I -c 'import json, sys; print(json.load(sys.stdin)["deploymentState"])' <<< "$status")"
    echo "state $state"
    case "$state" in
        VALIDATED)
            if [[ "$type" == USER_MANAGED ]]; then
                echo "validated; publish or drop it at https://central.sonatype.com/publishing/deployments"
                exit 0
            fi
            ;;
        PUBLISHING|PUBLISHED)
            echo "publishing; it appears on https://repo1.maven.org/maven2/org/thisisthepy/ within about 30 minutes"
            exit 0
            ;;
        FAILED)
            python3 -I -c 'import json, sys; print(json.dumps(json.load(sys.stdin).get("errors"), indent=2))' <<< "$status" >&2
            exit 1
            ;;
    esac
    sleep 30
done
echo "error: deployment $id is still $state after an hour; see https://central.sonatype.com/publishing/deployments" >&2
exit 2
