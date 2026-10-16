#!/usr/bin/env bash
set -euo pipefail
: "${RUNNER_TEMP:?run this check in an owned temporary environment}"
: "${ANDROID_HOME:?set ANDROID_HOME}"

umask 077
scratch="$(mktemp -d "$RUNNER_TEMP/ideaforge-signing-XXXXXX")"
trap 'rm -f "$scratch/app.jks" "$scratch/upload.jks"' EXIT
password=ideaforge-disposable-test-only
export ANDROID_KEYSTORE_PASSWORD="$password" ANDROID_KEY_PASSWORD="$password"

# Exercise the actual workflow gate, including each partially configured secret set.
node --input-type=module - "$scratch/require-upload.sh" <<'NODE'
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
const workflow = readFileSync('.github/workflows/release.yml', 'utf8').replace(/\r\n/g, '\n');
const start = workflow.indexOf('      - name: Require the upload keystore secrets\n');
assert.ok(start >= 0, 'missing upload-secret gate');
const block = workflow.slice(start).match(/^ {8}run: \|\n((?: {10}[^\n]*\n)+)/m);
assert.ok(block, 'missing upload-secret script');
writeFileSync(process.argv[2], block[1].replace(/^ {10}/gm, ''));
NODE
secrets=(ANDROID_UPLOAD_KEYSTORE_B64 ANDROID_UPLOAD_KEYSTORE_PASSWORD ANDROID_UPLOAD_KEY_ALIAS ANDROID_UPLOAD_KEY_PASSWORD)
present=()
absent=()
for name in "${secrets[@]}"; do
  present+=("$name=test-placeholder")
  absent+=("$name=")
done
if env "${absent[@]}" bash "$scratch/require-upload.sh" > "$scratch/missing.log" 2>&1; then
  echo "::error::missing upload secrets passed"; exit 1
fi
for name in "${secrets[@]}"; do
  if env "${present[@]}" "$name=" bash "$scratch/require-upload.sh" > "$scratch/missing.log" 2>&1; then
    echo "::error::missing $name passed"; exit 1
  fi
  grep -F "$name" "$scratch/missing.log" > /dev/null
done
env "${present[@]}" bash "$scratch/require-upload.sh"

for alias in app upload; do
  keytool -genkeypair -keystore "$scratch/$alias.jks" -storetype PKCS12 -alias "$alias" \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=IdeaForge disposable $alias" \
    -storepass:env ANDROID_KEYSTORE_PASSWORD -keypass:env ANDROID_KEY_PASSWORD
done
export ANDROID_KEYSTORE_PATH="$scratch/app.jks" ANDROID_KEY_ALIAS=app
(cd android && ./gradlew --no-daemon :app:assembleRelease :app:verifyReleaseApk)
keytool -exportcert -keystore "$ANDROID_KEYSTORE_PATH" -alias app \
  -storepass:env ANDROID_KEYSTORE_PASSWORD > "$scratch/app.der"
fingerprint="$(node -p "new (require('node:crypto').X509Certificate)(require('node:fs').readFileSync(process.argv[1])).fingerprint256" "$scratch/app.der")"
printf '## Release signing certificate\n\nSigner certificate SHA-256: `%s`\n' "$fingerprint" > "$scratch/policy.md"
build_tools="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
apk=android/app/build/outputs/apk/release/app-release.apk
"$build_tools/apksigner" verify --print-certs "$apk" > "$scratch/apksigner.txt"
node tools/apk-cert-pin.mjs "$scratch/apksigner.txt" "$scratch/policy.md"

export ANDROID_KEYSTORE_PATH="$scratch/upload.jks" ANDROID_KEY_ALIAS=upload
(cd android && ./gradlew --no-daemon :app:bundleRelease :app:verifyReleaseBundle)
bundle=android/app/build/outputs/bundle/release/app-release.aab
bash tools/check-android-bundle.sh "$bundle" "$ANDROID_KEYSTORE_PATH" upload "$scratch/policy.md"
keytool -exportcert -rfc -keystore "$ANDROID_KEYSTORE_PATH" -alias upload \
  -storepass:env ANDROID_KEYSTORE_PASSWORD > "$scratch/upload.pem"
keytool -importcert -noprompt -alias upload -file "$scratch/upload.pem" \
  -keystore "$scratch/public-trust.p12" -storetype PKCS12 -storepass:env ANDROID_KEYSTORE_PASSWORD
bash tools/check-android-bundle.sh "$bundle" "$scratch/public-trust.p12" upload "$scratch/policy.md"
test -s android/app/build/outputs/mapping/release/mapping.txt
"$build_tools/aapt" dump badging "$apk" > "$scratch/badging.txt"
code="$(java -jar "$RUNNER_TEMP/bundletool.jar" dump manifest --bundle="$bundle" --xpath='/manifest/@android:versionCode')"
node --input-type=module - "$code" "$scratch/badging.txt" <<'NODE'
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const version = JSON.parse(readFileSync('package.json', 'utf8')).version;
const [major, minor, patch] = version.split('.').map(Number);
const expected = String(major * 10_000 + minor * 100 + patch);
const apk = /versionCode='(\d+)'/.exec(readFileSync(process.argv[3], 'utf8'))?.[1];
assert.equal(apk, expected, 'APK versionCode');
assert.equal(process.argv[2].trim(), expected, 'AAB versionCode');
console.log(`APK and AAB share versionCode ${expected}`);
NODE

if node tools/apk-cert-pin.mjs --upload-key "$fingerprint" "$scratch/policy.md" > "$scratch/reused.log" 2>&1; then
  echo "::error::reused app/upload certificate passed"; exit 1
fi
grep -F 'must be different' "$scratch/reused.log" > /dev/null

python3 - "$bundle" "$scratch/unsigned.aab" <<'PY'
import sys
from zipfile import ZipFile
with ZipFile(sys.argv[1]) as source, ZipFile(sys.argv[2], "w") as target:
    for item in source.infolist():
        if not item.filename.startswith("META-INF/"):
            target.writestr(item, source.read(item.filename))
PY
if bash tools/check-android-bundle.sh "$scratch/unsigned.aab" "$ANDROID_KEYSTORE_PATH" upload "$scratch/policy.md" > "$scratch/unsigned.log" 2>&1; then
  echo "::error::unsigned bundle passed"; exit 1
fi
grep -F 'the bundle has no verified upload signature' "$scratch/unsigned.log" > /dev/null

mv "$RUNNER_TEMP/bundletool.jar" "$scratch/bundletool-verified.jar"
printf 'corrupt cached download' > "$RUNNER_TEMP/bundletool.jar"
if bash tools/check-android-bundle.sh "$bundle" "$ANDROID_KEYSTORE_PATH" upload "$scratch/policy.md" > "$scratch/corrupt.log" 2>&1; then
  echo "::error::corrupt bundletool passed"; exit 1
fi
grep -F 'FAILED' "$scratch/corrupt.log" > /dev/null
mv "$scratch/bundletool-verified.jar" "$RUNNER_TEMP/bundletool.jar"
echo 'Signed APK/AAB, distinct upload key, shared versionCode and rejection controls passed'
