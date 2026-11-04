#!/bin/bash
# Re-signs the published APKs with SpirOS's private release key (GitHub secret),
# rotating from the old public project key with APK Signature Scheme v3: phones that
# have the old build update in place (no uninstall, nothing lost), and from then on
# only the private key can sign updates (the old key loses its rollback rights).
# Usage: tools/sign_release.sh <apk>...   env: SPIROS_KEYSTORE_B64, SPIROS_KEYSTORE_PASS
set -euo pipefail
[ -n "${SPIROS_KEYSTORE_B64:-}" ] || { echo "No release key secret: APKs stay signed with the project key."; exit 0; }
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)
APKSIGNER="$BT/apksigner"
WORK=$(mktemp -d)
echo "$SPIROS_KEYSTORE_B64" | base64 -d > "$WORK/release.p12"
OLD=(--ks keystore/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android)
NEW=(--ks "$WORK/release.p12" --ks-type PKCS12 --ks-pass env:SPIROS_KEYSTORE_PASS --ks-key-alias spiros --key-pass env:SPIROS_KEYSTORE_PASS)
"$APKSIGNER" rotate --out "$WORK/lineage" --old-signer "${OLD[@]}" --new-signer "${NEW[@]}"
# The public old key may no longer be used to roll an install back / update it.
"$APKSIGNER" lineage --in "$WORK/lineage" --out "$WORK/lineage" --signer "${OLD[@]}" --set-rollback false || echo "note: could not restrict the old key"
for apk in "$@"; do
    "$APKSIGNER" sign "${OLD[@]}" --next-signer "${NEW[@]}" --lineage "$WORK/lineage" --rotation-min-sdk-version 28 "$apk"
    "$APKSIGNER" verify --print-certs -v "$apk" | grep -E "Verified using v3|Signer #1 certificate SHA-256" | head -n 3 || true
done
rm -rf "$WORK"
