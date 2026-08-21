# AGENTS.md — how to work in this repo

You are working on **OpenIR**, an open-source (MIT) Kotlin + Jetpack Compose IR-blaster app
for the **Bigme Hibreak Pro** (Android 14, E-ink phone with a built-in IR blaster that does
**not** use Android's `ConsumerIrManager`). It clean-room reimplements the proprietary
`HxdIR` app (`ht.song.remotestar.hxd`), minus its cloud/P2P phone-home.

## Read these first (in order)

1. [`docs/HANDOFF.md`](docs/HANDOFF.md) — **start here**: current state of the work and the
   ordered continuation plan (what's proven, what's pending, dead ends not to retry).
2. [`README.md`](README.md) — project overview + status table.
3. [`docs/REVERSE_ENGINEERING.md`](docs/REVERSE_ENGINEERING.md) — the RE bible. Frame
   formats, DSP pipeline, vendor learn/TX flows, brand-DB layout, toggle-bit tables.
4. [`docs/VALIDATION.md`](docs/VALIDATION.md) — hardware validation checklist (what's proven
   on-device vs. what still needs a human with the phone + a remote).
5. [`BUILD.md`](BUILD.md) — toolchain setup (macOS arm64, no Android Studio), the
   `targetSdk=27` SELinux workaround, jniLibs instructions.

## Repo layout

```
app/src/main/java/dev/ilyaask/openir/
  codec/      IrCodec iface, CleanIrCodec (clean-room), Dsp110 (110B DSP port),
              NativeIrCodec (vendor .so shim), BigmeFrameEncoder, IrSignalParser
              (Flipper/LIRC/Pronto/raw), BrandDbReader/BrandDbTables, DebugCaptures
  blaster/    IrBlaster — connect/send/learn orchestration (Transport + codec)
  transport/  IoTransport (/dev/ctrl char device), BleHxdTransport (BLE dongle)
  ir/IRType.kt  device classes + key codes + learn commands (301040 / 302050)
  data/       Remote/RemoteKey models + DataStore persistence
  ui/         Compose screens + OpenIrViewModel (debug screen drives validation)
app/src/test/          JVM unit tests (Dsp110Test, CleanIrCodec230GoldenTest, IrBlasterTest, …)
app/src/androidTest/   On-device oracle fuzz vs the vendor .so (VendorCodecFuzzTest,
                       FuzzCorpusReal, LoopbackTest) — requires the phone + vendor lib
re/toggle_records.json Extracted JudgeToggleBit tables (see RE §9.1)
docs/                  REVERSE_ENGINEERING.md + VALIDATION.md
```

**Not in the repo (gitignored, regenerable/proprietary):** `HxdIR.apk`, `jadx-src/`,
`jadx-res/`, `apk-raw/`, `re/*.so`, `ir_tools.disasm.txt`, `app/src/main/jniLibs/`,
Gradle wrapper files (regenerate via `gradle wrapper --gradle-version 8.9`, see BUILD.md).

## Build & test

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home   # JDK 17!
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

./gradlew :app:testDebugUnitTest        # JVM tests (no device)
./gradlew :app:assembleDebug            # app APK
./gradlew :app:assembleDebugAndroidTest # on-device oracle tests
```

## The oracle-fuzz protocol (how codec correctness is proven)

The vendor `libet_jni_ir_tools.so` is bundled via `jniLibs/` (user-supplied) and used as a
**ground-truth oracle**: every candidate input goes through both `native.studyKeyCode()` and
the clean Kotlin port; frames are byte-diffed. Logcat tag `FuzzDiff`, one `RESULT` line/case.

**Shard-per-case is mandatory** — the vendor lib stack-smashes (`SIGABRT`) on hostile inputs
and has global state, so each case runs in a fresh process:

```bash
for i in $(seq 0 96); do
  adb logcat -c
  adb shell am instrument -w \
    -e class dev.ilyaask.openir.VendorCodecFuzzTest#runSingle \
    -e size 110 -e caseIndex $i \
    dev.ilyaask.openir.test/androidx.test.runner.AndroidJUnitRunner >/dev/null 2>&1
  adb logcat -d | grep -E "FuzzDiff.*RESULT" >> sweep.log
done
```

(`size 230` for the 230B corpus; `-e verbose 1` logs full in/vendor/clean frames.)
Baseline (2026-07-31): 110B → 17 MATCH-DSP + 35 MATCH-REAL + 44 MATCH-ZERO + 1 vendor
stack-smash, **zero mismatches**. 230B → 23/23 byte-exact. Any future change to
`Dsp110.kt`/`CleanIrCodec.kt` must reproduce zero-mismatch before merging.

## Hard-won gotchas (learned the expensive way — do not rediscover)

1. **Codecs mutate their input `ByteArray` in place** (native and clean alike, faithfully
   ported). Never pass a stored capture to a codec — always `copyOf()`. The debug screen
   once fed `_debugLastRaw` directly and silently turned "raw" into DSP output.
2. **The toggle bit must alternate per transmit** (`g_toggle_bit` in the native lib;
   `Dsp110.toggleBit` in the port). Re-encode on every TX — never cache a frame across taps.
   Devices de-dupe same-toggle frames as "button held". (This bug presented as "AC switched
   off but wouldn't switch back on".)
3. **`Dsp110` is a class, not a singleton**: toggle state is per-instance. The native-vs-clean
   diff in the debug screen uses a throwaway `CleanIrCodec()` so its hidden encode doesn't
   advance the TX path's phase.
4. **`targetSdk` must stay 27** (`app/build.gradle.kts`) — higher values move the app into an
   SELinux domain that cannot open `/dev/ctrl` on the Hibreak Pro. IR breaks with EACCES.
5. **Vendor `.so` is 32-bit only** — `abiFilters += "armeabi-v7a"` in Gradle, and the libs
   live in `app/src/main/jniLibs/armeabi-v7a/`. The device supports 32-bit; without the
   abiFilter you get `UnsatisfiedLinkError: library not found`.
6. **One driver read = ~1 s learn window.** `IrBlaster.learn()` loops reads for ~15 s (vendor
   behaviour, `ActivityMain.StudyTask` in the RE doc). Don't regress this to a single read.
7. **AC brand-DB frames can be multi-part**: `55 AA`-delimited sub-frames written with 300 ms
   gaps (`IrBlaster.split55aa`, vendor: `FragmentAIR`). The learned-key path never splits.
8. **Gradle incremental builds can silently keep stale test APKs.** If an instrumented run
   doesn't reflect your edits, `touch` the test file and rebuild; check APK mtimes. Beware
   `./gradlew … | tail -1 && adb install …` masking a failed build's exit code.
9. **adb UI automation on macOS**: BSD `sed` breaks common coordinate-extraction snippets —
   parse `uiautomator dump` XML with python3 instead (see session history for the pattern).
10. **TX+RX are mutually exclusive** on this driver — the phone cannot hear its own blaster
    (loopback test: `LoopbackTest`, captured nothing even for known-good frames). Validating
    TX requires a real target device or the vendor app as control.
11. The **learned-key TX path is fully vendor-equivalent** (proven from source, RE §4.1):
    fresh `StudyKeyCode` per tap + single plain write, no ioctls (`ETIO.ioctl` is empty).
    If learned replay fails on hardware, suspect physics (aim, ~3 cm distance, device state)
    before suspecting the codec.

## What remains (priority order)

- **Hardware re-validation with the toggle-fixed build** (VALIDATION.md V1–V3): learned
  replay at ~3 cm, per-tap toggle; vendor app as control if it fails.
- **V4**: calibrate `BigmeFrameEncoder`'s unit timing against one real learned capture so
  Flipper/LIRC/Pronto signals transmit correctly (the parsers + browse/import UI are done).
- **`JudgeToggleBit` port** (RE §9.1, `re/toggle_records.json`): tables + algorithm fully
  extracted; stubbed in `Dsp110` because no corpus input exercises it. Port it when a real
  toggle-protocol 110B capture exists to oracle-test against.
- **Brand-DB `SearchKeyData` encoder**: `.rodata` table layout fully documented (RE §9.2);
  the reader is experimental and unvalidated.

## Rules for AI agents

- Keep the repo free of proprietary blobs: never commit `HxdIR.apk`, decompiled sources,
  `.so` files, or `jniLibs/` content. Facts *about* formats (schemas, tables, command bytes)
  belong in docs and are fine.
- Run `./gradlew :app:testDebugUnitTest` before declaring any change done; prefer testify-style
  `org.junit.Assert` assertions in JVM tests.
- Changing the codec? Re-run the oracle sweep (above) on-device if a Hibreak Pro + vendor lib
  are available; otherwise call it out as unvalidated in the PR.
- Don't add comments that narrate code; document *why* (see the existing KDoc style).
