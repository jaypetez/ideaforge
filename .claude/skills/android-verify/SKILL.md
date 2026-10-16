---
name: android-verify
description: Build, test and smoke-run the native Android app (android/), from Gradle unit tests through golden vectors to an emulator screenshot.
---

# Verifying the Android app

Use this skill for any change under `android/`, for a JavaScript change that regenerates the
golden vectors, or to prove the debug build still installs and runs. It is the Android
counterpart of the web ladder in [AGENTS.md](../../../AGENTS.md); the invariants it protects
are in [CLAUDE.md](../../../CLAUDE.md#android-invariants), and the project layout is in
[CONTRIBUTING.md](../../../CONTRIBUTING.md#the-android-app).

This workflow downloads Gradle dependencies, starts a Gradle daemon and may boot an emulator.
Inspect what is already running first. Do not wipe an existing AVD, uninstall an app someone
else is testing, or kill an emulator you did not start. Never copy or commit a release
keystore or its passwords; creating the real key and its secrets is a maintainer task. To
exercise a signed, minified build, generate a throwaway keystore outside the repository and
point the `ANDROID_KEYSTORE_*`/`ANDROID_KEY_*` variables at it (section 5b).

## 0. Prerequisites

- JDK 21 on `PATH` or `JAVA_HOME` (every module uses `jvmToolchain(21)`).
- The Android SDK, found through `ANDROID_HOME` or `android/local.properties` (`sdk.dir=…`,
  ignored by git). `adb` and `emulator` live in the SDK's `platform-tools/` and `emulator/`.
- Node 22+ for the vector side of the contract.

Run Gradle from `android/`. On Windows use `gradlew.bat`, or `./gradlew` from Git Bash.

## 1. The cheapest rung: one module's unit tests

```sh
cd android
./gradlew :core:test                    # pure Kotlin port; vectors, purity, drive loop
./gradlew :providers:test               # HTTP policy, adapters, providers/speech vectors
./gradlew :app:testDebugUnitTest        # Robolectric: stores, view models, Compose screens
./gradlew :core:test --tests "*VectorSuiteTest*"
./gradlew :app:testDebugUnitTest --tests "*WalkthroughVectorTest*"
```

`:core` and `:providers` are plain JVM modules on JUnit 5. `:app` runs its tests under
Robolectric on JUnit 4, with no device. `WalkthroughVectorTest` lives in `src/testDebug/`, so
it runs in the debug unit-test task only. Reports land in `android/<module>/build/reports/`.
Use these selectors while correcting a failure, preserving warm Gradle state. Routine
`clean`, `--rerun-tasks`, cache deletion or daemon shutdown defeats incremental validation;
the worktree-removal shutdown in section 6 is a different operation.

## 2. The whole Android gate

```sh
./gradlew check :app:assembleDebug
```

This is exactly what CI runs (section 7). `check` covers every module's configured unit
tests and Android lint (`abortOnError`). The APK lands in
`android/app/build/outputs/apk/debug/`.
Run this whole gate on the final reviewed head, not after every focused fix. Retain its
command/result and tested SHA; changed sources, fixtures or validation inputs invalidate
the corresponding evidence. The web pre-push `npm run test:all` gate is still required.

## 3. Golden vectors: the contract with the JavaScript core

The JavaScript core is the reference. `tools/vectors.mjs` records real calls into it as
`test/vectors/*.json`; `:core` and `:providers` replay them through the consumers in
`android/core/src/test/kotlin/io/github/jaypetez/ideaforge/core/vectors/Consumers.kt`.

```sh
node tools/vectors.mjs --check          # exit 1 if any vector on disk is stale
npm run vectors                         # rewrite test/vectors/*.json from the JS
node --test test/vectors.test.mjs       # stale, deterministic, named-invariant checks
```

- A JavaScript change that alters behaviour regenerates the vectors in the same PR. The
  Android build then fails until the Kotlin port follows. That is the point.
- Never edit a vector by hand, and never loosen a Kotlin consumer, comparison or
  `pending` entry to make a replay pass.
- `VectorCoverageTest` fails when a family on disk has no registered consumer, when a family
  disappears, or when nothing is replayed at all. A new family needs a `Consumer` entry.
- Porting a function moves it from `pending` to `ported` in `Consumers.kt` in the same PR
  as the Kotlin code.

The backup fixtures are a second cross-platform contract. `test/fixtures/backup-from-web.json`
comes from `tools/backup-fixtures.mjs`; `test/fixtures/backup-from-android.json` is rewritten
by `BackupInteropTest` only when `IDEAFORGE_UPDATE_FIXTURES=1` is set, and the web's
`test/backup.test.mjs` imports it.

## 4. Prove a test bites before trusting it

A green suite is evidence only if it can go red. Before claiming a new test protects
something, plant the fault it is meant to catch, run the narrowest task, see it fail for the
right reason, then restore the file.

```sh
# e.g. loosen the coverage ratchet in core/src/main/kotlin/.../core/Session.kt, then:
./gradlew :core:test --tests "*VectorSuiteTest*"   # must FAIL, naming the ratchet cases
git restore core/src/main/kotlin/io/github/jaypetez/ideaforge/core/Session.kt
./gradlew :core:test --tests "*VectorSuiteTest*"   # green again
```

For the vector side, change a JavaScript reducer without regenerating and confirm
`test/vectors.test.mjs` reports the stale family; then restore it. Keep planted faults out
of commits: check `git status` before staging.

## 5. Emulator smoke: install, launch scripted, look at it

The debug build carries a scripted provider that replays the walkthrough vector with no key
and no network. A release build compiles it to nothing.

```sh
emulator -list-avds
emulator -avd <name> -no-snapshot-save &          # or start it from Android Studio
adb wait-for-device
adb shell getprop sys.boot_completed              # repeat until it prints 1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -S \
  -n io.github.jaypetez.ideaforge.debug/io.github.jaypetez.ideaforge.MainActivity \
  --es provider scripted
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml <scratchpad>/ui.xml
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png <scratchpad>/screen.png
```

Then **read the PNG** with the client's image viewer and read `ui.xml` for the visible text.
A launch that returned 0 is not evidence; a screenshot showing the expected screen is.

- `--es provider scripted` replaces only the interview's model. It does not skip onboarding:
  a fresh install opens on onboarding until the keyring holds a usable provider. Complete it
  once (for example Ollama at `10.0.2.2` with a model pulled); `adb install -r` keeps that
  data, and later scripted launches open on the library.
- The scripted replies are keyed by turn number; a turn with no scripted reply falls back to
  the built-in checklist, exactly as the web harness does.
- Type into the focused field with `adb shell input text 'an%sanswer'` (`%s` is a space)
  and tap with `adb shell input tap X Y`, using bounds from `ui.xml`.
- In Git Bash, MSYS rewrites `/sdcard/...` into a Windows path. Prefix the command with
  `MSYS_NO_PATHCONV=1` or write `//sdcard/...`.
- In Windows PowerShell 5.1, never redirect `adb exec-out screencap -p > file.png`: the
  pipeline re-encodes the bytes and the PNG is corrupt. Capture on the device and `adb pull`.

No physical-device result is recorded. Report an emulator smoke, or the instrumented run
below, as emulator evidence, not as phone support.

## 5a. The instrumented walkthrough

`app/src/androidTest/.../WalkthroughDeviceTest.kt` does the smoke above as a test: it launches
`MainActivity` with `provider=scripted`, answers the vector's "a chip answer" case through the
screen, presses Write it up and asserts the Result screen's title and the export's `## `
sections. Every question must be the scripted one for its turn, and the checklist marker must
never appear: a degraded interview still reaches a result. It passes onboarding by seeding the
keyring through the app's own `KeyringStore` (only when no usable provider is stored), then
puts the keyring back and deletes the idea it created, so a shared emulator is left as found.

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest   -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
./gradlew pixel6Api34DebugAndroidTest   -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

- By default `connectedDebugAndroidTest` uninstalls the app after the run, which wipes its
  data. On an emulator someone else is using, pass `leaveApksInstalledAfterRun=true` as above.
- `pixel6Api34` is the Gradle managed device in `app/build.gradle.kts` (an `aosp-atd` image,
  API 34, x86_64). Gradle downloads the image and boots its own emulator.
- Results land in `app/build/outputs/androidTest-results/` and `app/build/reports/androidTests/`.
- `ScriptedProviderIsDebugOnlyTest` (a debug unit test, so part of `check`) fails if the
  scripted provider or its walkthrough script reaches `src/main`, `src/release` or the release
  variant's merged assets.
- To prove it bites, make the scripted provider throw for one turn: the run fails with
  "turn N is the scripted question, not the checklist".

## 5b. The signed, minified release build

The release build runs R8, and a crash there never shows in a debug build or a JVM test.
Sign it with a throwaway key kept outside the repository (the scratchpad), never the real one:

```sh
keytool -genkeypair -keystore "$SCRATCH/throwaway.jks" -alias ideaforge -keyalg RSA \
  -keysize 4096 -validity 10000 -storepass throwaway -keypass throwaway -dname "CN=throwaway"
ANDROID_KEYSTORE_PATH="$SCRATCH/throwaway.jks" ANDROID_KEYSTORE_PASSWORD=throwaway \
  ANDROID_KEY_ALIAS=ideaforge ANDROID_KEY_PASSWORD=throwaway ./gradlew :app:assembleRelease
"$ANDROID_HOME/build-tools/<version>/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
adb install app/build/outputs/apk/release/app-release.apk
adb shell am start -W -n io.github.jaypetez.ideaforge/.MainActivity
adb logcat -d | grep -E "FATAL|E AndroidRuntime"
adb uninstall io.github.jaypetez.ideaforge
```

- `assembleRelease` is finalised by `verifyReleaseApk`, which fails if the APK packages the
  walkthrough script or R8's mapping file lists a class declared only under `src/debug`.
- Without the variables (or `android/keystore.properties`) the build is
  `app-release-unsigned.apk`, which cannot be installed; setting only some of them is an error.
- Go past onboarding (list a local server's models, save, open Settings, run one turn) before
  calling R8 safe: that is where serialization and OkHttp run. Add a keep rule to
  `app/proguard-rules.pro` only for a crash reproduced here, with a comment saying why.
- The release app id has no `.debug` suffix, so it installs beside the debug build. Uninstall
  it afterwards; the throwaway key would block a real release from updating it.

## 6. Windows test-host quirks

These are known and handled; do not "fix" them by loosening a test.

- **Rename retries.** Defender and the indexer briefly lock freshly written files, so
  `writeAtomically` in `android/app/src/main/kotlin/io/github/jaypetez/ideaforge/data/AtomicFiles.kt`
  retries an `AccessDeniedException` on Windows only. A save failing there after the retries
  is real. Directory fsync is skipped on Windows only, because a Windows JVM cannot open a
  directory as a channel.
- **FileProvider under Robolectric.** FileProvider matches roots by `/`-joined canonical
  paths and finds none on a Windows host. `ResultActions.contentUri` is a seam that
  `ResultScreenTest` swaps only when a probe through the real provider fails, and it
  requires the real provider off Windows.
- A Gradle daemon can keep files under `build/` open. `./gradlew --stop` before deleting a
  worktree.

## 7. How CI runs it

The `android` job in `.github/workflows/ci.yml` runs on Ubuntu with Temurin JDK 21.
`gradle/actions/setup-gradle` validates the wrapper JAR and caches dependencies (read-only on
PRs). It then runs `./gradlew --no-daemon check :app:assembleDebug`. It uploads the debug APK
as the `ideaforge-debug-apk` artifact for 14 days, and the reports and test results on
failure. The job feeds the single required `ci` check, so a red Android build blocks every
PR.

`.github/workflows/android-device.yml` is separate and not required. On every push to `main`,
nightly and on `workflow_dispatch`, it enables KVM on Ubuntu and runs
`./gradlew --no-daemon :app:pixel6Api34DebugAndroidTest` with software rendering
(`swiftshader_indirect`), uploading the reports on failure. Start a run with
`gh workflow run android-device.yml`.

## 8. Report

State which rungs ran and their results, any vectors regenerated and why, the fault you
planted and the failure it produced, and whether an emulator smoke ran (with the screenshot
you read). Name what did not run, such as a physical device or an emulator in CI.
