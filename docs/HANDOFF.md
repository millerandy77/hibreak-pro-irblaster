# HANDOFF — where the last agent left off, and exactly how to continue

**Snapshot: 2026-08-21.** Repo: `github.com/IlyaasK/hibreak-pro-irblaster` (public, MIT),
`main` @ `83791cb`. Read [AGENTS.md](../AGENTS.md) first (build/test/gotchas); this document
is the *state of the work* and the ordered continuation plan.

---

## 1. The project in one paragraph

OpenIR is a clean-room, open-source replacement for the Bigme Hibreak Pro's proprietary
IR-blaster app (`HxdIR`, package `ht.song.remotestar.hxd`). The phone's blaster is driven
through a vendor kernel char device (`/dev/ctrl`), not Android's `ConsumerIrManager`, which
is why generic IR apps don't work. All reverse engineering is **done** for the learned-code
paths; both codec paths are **proven byte-exact** against the vendor native library via
on-device oracle fuzzing. What is *not* yet proven is end-to-end hardware acceptance with
the current (fixed) build — see §2.

## 2. Current state — precise

**Done and proven (no further work needed):**

| Component | Proof |
|---|---|
| 230B learned-code codec (`StudyKeyCode` path) | 23/23 on-device oracle-fuzz cases byte-exact vs vendor `.so` |
| 110B learned-code codec (`Dsp110`: compdata/Modifywave/delfeng/getfigure port) | 97-case on-device sweep: 17 MATCH-DSP + 35 MATCH-REAL + 44 MATCH-ZERO + 1 vendor-lib crash, **zero mismatches** |
| Vendor TX parity for learned keys | Proven from decompiled source (RE §4.1): fresh `StudyKeyCode` per tap + single plain `/dev/ctrl` write, no ioctls. Our app now does exactly this |
| Toggle-bit alternation per transmit | Verified on-device: consecutive TX frames alternate `…81…/…91…` |
| 15 s learn window (vendor re-read loop) | Verified on-device (was a 1.2 s single-shot window) |
| `55 AA` multi-part AC frame TX (300 ms gaps) | Ported from `FragmentAIR`, unit-tested (`IrBlasterTest`) |
| Open-format parsers (Flipper/LIRC/Pronto/raw), catalog import/browse UI | JVM-tested; TX side pending V4 calibration |
| Debug screen: native-vs-clean byte diff, conditioned/unconditioned A/B, saved-capture replay | Working; `DebugCaptures.real110` = pristine NEC-style capture from 2026-07-31 |

**The open question (the only thing that matters next):**

> Does a learned replay, transmitted by the *toggle-fixed* build, make a real device react?

Timeline: on 2026-07-31 the user tested learned replay and reported "did not react" — but
that build had the frozen-toggle bug (every TX frame identical → devices de-dupe them as
"button held"). Three bugs were found and fixed that day (in-place buffer mutation, cached
frame across taps, global toggle state), and the fixes were verified on-device at the byte
level. **No hardware test against a real target device has happened since.** The earlier
2026-07-29 AC test ("switched off but wouldn't switch back on") is now fully explained by
the same frozen-toggle bug.

**Phone state:** the vendor `HxdIR` app is still installed (location permission denied) with
a learned `TESTKEY` button on its DIY panel — kept as a control. Uninstall it once V1/V2
pass (it phones home).

## 3. Continuation plan (in order)

### Step 1 — Hardware validation V1/V2/V3 (needs a human + phone + remote, ~15 min)

Exact steps and pass/fail criteria: [VALIDATION.md](VALIDATION.md). Short version:

1. OpenIR → Debug → **230 B (long)** → Learn (15 s window now) → press an AC/TV key →
   aim at the device from **~3 cm** → **Transmit frame** up to 3× (toggle alternates per
   tap — the *second* tap is often the one that registers).
2. Same with **110 B (short)** (or **Replay saved 110B capture** if no remote at hand).
3. If it works: V1/V2 done → update VALIDATION.md, close the loop. If not, run the vendor
   control: vendor app → DIY panel → TESTKEY → **Test Data**, same distance/angle.
   - Vendor works, ours doesn't → should be impossible (paths are proven identical);
     re-check the physical setup differed.
   - Vendor also fails → physics (LED strength, aim, device state). Not a software bug.

### Step 2 — V4: calibrate `BigmeFrameEncoder.unitUs` (needs one good capture from Step 1)

The encoder's frame wrapper and 0x80-overflow body are exact; only the µs-per-device-unit
scale and the payload header bytes are unvalidated (see the KDoc in
`app/src/main/java/dev/ilyaask/openir/codec/BigmeFrameEncoder.kt`).

Method: take a learned capture whose protocol timings are known (e.g. NEC: 9000 µs header
mark). Find that mark's stored value in the raw capture (or the DSP'd frame) →
`unitUs = 9000 / storedValue`. Set `DEFAULT_UNIT_US` accordingly, then fix the header bytes
(`payload[0..7]`) from the same capture. Validate by transmitting a Flipper/LIRC NEC power
signal to a real device. Until then the catalog/import TX paths are experimental.

### Step 3 — Port `JudgeToggleBit` (only when a toggle-protocol 110B capture exists)

Tables + algorithm fully extracted: `re/toggle_records.json` + RE §9.1. It is stubbed in
`Dsp110` (`judgeToggleBit` returns 0) because no corpus input exercises it — porting it
without an oracle input would be unverifiable. When a real capture of a toggle-protocol
remote (e.g. RC5/RC6) exists: add it to `FuzzCorpusReal`, port the matcher, and prove it
with the shard-per-case sweep (AGENTS.md has the exact command loop).

### Step 4 — Brand-DB `SearchKeyData` encoder (the 106 MB codebook)

`.rodata` table layout fully documented in RE §9.2; `BrandDbReader`/`BrandDbTables` are
experimental and unvalidated. This unlocks the vendor's built-in brand catalogue without
learning. Note the strategic decision (agreed with the user): open formats
(Flipper IRDB / LIRC) are the *default* universal-remote source; the proprietary DB is an
optional, user-supplied pack. Prioritize Step 2 over this.

### Step 5 — Cleanup

Uninstall the vendor app from the phone once validation passes. Consider flipping the repo
private→public decision is settled (user chose public); nothing to do.

## 4. Environment & workflow recipes (so you don't rediscover them)

**Build** (macOS arm64, no Android Studio — full guide in BUILD.md):

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:testDebugUnitTest   # always before declaring done
```

**Driving the phone over adb** (screen taps): dump UI XML, parse coordinates with python3
(BSD `sed` on macOS breaks the usual snippets):

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml   # parse text="..." + bounds="[x1,y1][x2,y2]" in python3
adb shell input tap X Y
```

**Oracle-fuzz sweep**: exact shard-per-case command loop is in AGENTS.md §"oracle-fuzz
protocol". Always capture logcat per case into a host file (a single big buffer rotates and
silently drops RESULT lines).

**Vendor-app control**: `adb install HxdIR.apk` (local repo root, gitignored) →
DIY equipment → ADD BUTTON → tap the button → press remote → "Test Data" transmits.
Deny its location permission. Its private DB and logs are inaccessible without root
(scoped storage) — don't bother looking; the source of truth is the decompiled code.

## 5. Dead ends (already tried — don't retry)

- **TX→RX loopback on the same phone**: the driver cannot receive while transmitting;
  `LoopbackTest` captured nothing even for known-good frames. TX validation requires a real
  target device or the vendor app as control.
- **Snooping the vendor app's `/dev/ctrl` writes**: impossible without root.
- **Vendor app logs** (`/sdcard/Log.txt.lcd`): blocked by scoped storage on Android 14.
- **Single-read learn**: one driver read is a ~1 s window; the vendor loops reads for ~15 s.
  Our `IrBlaster.learn()` does the same — don't regress it.

## 6. Where things live (quick map)

| What | Where |
|---|---|
| 110B DSP port (byte-exact) | `app/src/main/java/dev/ilyaask/openir/codec/Dsp110.kt` |
| 230B path + codec entry points | `codec/CleanIrCodec.kt` |
| Vendor `.so` shim (oracle) | `codec/NativeIrCodec.kt` (+ user-supplied `jniLibs/`) |
| Open-format → device frame | `codec/BigmeFrameEncoder.kt` (V4 calibration target) |
| Flipper/LIRC/Pronto parsers | `codec/IrSignalParser.kt`, `codec/IrdbCatalog.kt` |
| Learn/TX orchestration, `split55aa` | `blaster/IrBlaster.kt` |
| `/dev/ctrl` transport | `transport/IoTransport.kt` |
| Debug/validation screen + view model | `ui/DebugScreen.kt`, `ui/OpenIrViewModel.kt` |
| On-device oracle fuzz (shard runner) | `app/src/androidTest/.../VendorCodecFuzzTest.kt` |
| Real captures (fuzz seeds) | `app/src/androidTest/.../FuzzCorpusReal.kt`, `codec/DebugCaptures.kt` |
| JudgeToggleBit tables | `re/toggle_records.json` + RE §9.1 |
| Brand-DB layout | RE §9.2 + `re/branddb_*.json` + `re/extract_*.py` |
| RE bible | `docs/REVERSE_ENGINEERING.md` |
| Hardware checklist | `docs/VALIDATION.md` |
| Agent handbook (gotchas!) | `AGENTS.md` |
