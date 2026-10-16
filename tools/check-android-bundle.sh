#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
  echo "usage: bash tools/check-android-bundle.sh BUNDLE KEYSTORE ALIAS [SECURITY.md]" >&2
  exit 2
fi
: "${RUNNER_TEMP:?set RUNNER_TEMP to an owned temporary directory}"
: "${ANDROID_KEYSTORE_PASSWORD:?set the upload keystore password}"
bundle="$1"
keystore="$2"
alias="$3"
policy="${4:-SECURITY.md}"

version=1.18.3
sha256=a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29
tool="$RUNNER_TEMP/bundletool.jar"
if [ ! -f "$tool" ]; then
  curl --fail --location --silent --show-error \
    "https://github.com/google/bundletool/releases/download/${version}/bundletool-all-${version}.jar" \
    --output "$tool"
fi
printf '%s  %s\n' "$sha256" "$tool" | sha256sum --check --strict
java -jar "$tool" validate --bundle="$bundle"

# Trust only the selected upload alias, not any other key in the keystore.
if ! jarsigner -J-Duser.language=en -J-Duser.country=US -verify -strict \
  -keystore "$keystore" -storepass:env ANDROID_KEYSTORE_PASSWORD \
  "$bundle" "$alias" | tee "$RUNNER_TEMP/ideaforge-bundle-signature.txt"; then
  echo '::error::the bundle has no verified upload signature' >&2
  exit 1
fi
# An entirely unsigned archive can return zero; require the verification verdict too.
grep -Fx 'jar verified.' "$RUNNER_TEMP/ideaforge-bundle-signature.txt" > /dev/null || {
  echo '::error::the bundle has no verified upload signature' >&2
  exit 1
}
certificate="$RUNNER_TEMP/ideaforge-upload-certificate.der"
keytool -exportcert -keystore "$keystore" -storepass:env ANDROID_KEYSTORE_PASSWORD \
  -alias "$alias" > "$certificate"
fingerprint="$(node -p "new (require('node:crypto').X509Certificate)(require('node:fs').readFileSync(process.argv[1])).fingerprint256" "$certificate")"
node tools/apk-cert-pin.mjs --upload-key "$fingerprint" "$policy"
