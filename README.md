# OpenIR — open-source IR blaster for the Bigme Hibreak Pro

An open-source (MIT), Kotlin + Jetpack Compose reimplementation of the proprietary
`HxdIR` IR-blaster app shipped by Bigme for the Hibreak Pro. Reverse-engineered from
the official APK; see [`docs/REVERSE_ENGINEERING.md`](docs/REVERSE_ENGINEERING.md) for
the full protocol write-up.

## Why

The official app is closed-source, phones home (TUTC P2P + MQTT to Bigme servers), and
talks to the Hibreak Pro's IR blaster through a **vendor kernel device node**
(`/dev/hxd_irda`) and a proprietary **BLE "HXD" dongle** protocol — neither of which is
Android's standard `ConsumerIrManager`. That's why ordinary IR apps don't work. OpenIR
reimplements those transports in clean Kotlin and drops the cloud phone-home entirely.

## Status

| Area | Status |
|---|---|
| Transport: built-in IR (`/dev/hxd_irda`, `/dev/ctrl`, `/dev/irremote`) | ✅ clean-room, pure Kotlin |
| Transport: BLE "HXD" dongle (GATT ffe0/ffe1, 20-byte framing, learn handshakes) | ✅ clean-room, pure Kotlin |
| UI: remotes, device classes, key grids, learn flow (15 s vendor-style learn window) | ✅ |
| Persistence (DataStore) | ✅ |
| IR codec: learned-code encoder (`StudyKeyCode`, 230-byte path) | ✅ **validated byte-exact** vs vendor `.so` (on-device oracle fuzz) |
| IR codec: learned-code encoder (`learn_data_in_out` DSP, 110-byte path) | ✅ **validated byte-exact** vs vendor `.so` (97-case on-device fuzz incl. real captures) |
| TX behaviour parity with vendor app (per-tap toggle alternation, multi-part AC `55AA` frames) | ✅ mirrored from source (RE §4) |
| IR codec: prebuilt brand DB (`SearchKeyData`) | 🛠️ table layout fully recovered; open reader + Flipper/LIRC import in progress |
| Open-format universal remote (Flipper `.ir` / LIRC / Pronto) | 🛠️ parsers + frame encoder done, unit calibration pending (V4) |
| Cloud (TUTK/MQTT) | ❌ intentionally dropped (privacy) |

> The IR codec (the byte frames the firmware expects) is the long pole. While it's being
> reverse-engineered, OpenIR can optionally load **your own copy** of the original
> `libet_jni_ir_tools.so` (and `assets/tv_1, air_1, …`) via `NativeIrCodec` so the app is
> fully functional today. The app source stays MIT; the codec is a swappable module you
> supply from a copy of the official app you legitimately own. The clean-room
> `CleanIrCodec` replaces it once the RE is done.

### On-device validation (no official app needed)
The **Debug** screen (home → Debug) learns a real IR signal through our own app (15 s learn
window), runs both the bundled vendor codec and the clean-room encoder on it, and shows a
byte-by-byte diff — byte-equality proves the clean codec without any AC-aiming. It can
transmit the emitted frame (toggle bit alternates per tap, like a real remote), A/B between
the DSP-conditioned and raw-wrapped 110-byte frame, and replay a saved capture when no
remote is at hand. See [`docs/VALIDATION.md`](docs/VALIDATION.md) for the hardware checklist.

## Build

Requires Android Studio / JDK 17 + Android SDK 34. From the repo root:

```bash
gradle wrapper          # once, if no gradlew is present
./gradlew :app:assembleDebug
./gradlew installDebug  # or adb install app/build/outputs/apk/debug/app-debug.apk
```

Min SDK 26, target SDK 34. The Hibreak Pro runs Android 14 and supports the armeabi
native lib from the original app (if you use `NativeIrCodec`).

## Architecture

```
UI (Compose)  ──►  OpenIrViewModel  ──►  IrBlaster  ──►  Transport (io | bleir)
                                          └──►  IrCodec (CleanIrCodec | NativeIrCodec)
                                          └──►  RemoteStore (DataStore)
```

- `transport/` — uniform `Transport` interface + `IoTransport` + `BleHxdTransport`.
- `codec/` — `IrCodec` interface (the 8 original JNI methods) + clean/native impls.
- `blaster/IrBlaster.kt` — connect, send, learn orchestration.
- `ir/IRType.kt` — 16 device classes + key catalog.
- `data/` — `Remote`/`RemoteKey` models + DataStore persistence.

## Reverse-engineering roadmap (codec)

The native lib's `.text` is only ~19 KiB (lookup/encode logic); the ~101 MiB `.rodata` is the
embedded codebook. The asset `.db` files are separate Java-side wizard fingerprint data.

1. **`StudyKeyCode` (raw → frame) + `Log.txt.lcd`** — disassemble the small `.text`, recover
   the raw-timing → transmit-frame transform and the Vigenère persistence format. Unlocks
   learning + replay with zero proprietary code. *(in progress)*
2. **`.rodata` table reader** — document the `*_info` index structs + `*_table` data layout so
   an open-source reader can extract codes from the user's own `.so`/`.rodata` for
   `SearchKeyData` + the brand catalogue (names already live in `R.array.strs_*`).
3. **Wizard fingerprints + community IR DB** — consume the ASCII-hex `assets/*.db` for
   fuzzy brand matching; optionally source codes from an open IR database so no proprietary
   data is needed at all.

## Contributing / coding agents

See [AGENTS.md](AGENTS.md) for the repo handbook: build/test commands, the on-device
oracle-fuzz protocol that proves codec correctness, hard-won gotchas (in-place codec
buffers, per-tap toggle bits, `targetSdk=27`), and the remaining roadmap.

## License

MIT — see [LICENSE](LICENSE). The original `HxdIR` APK and its native libraries are
proprietary and are **not** distributed here; `NativeIrCodec` only loads a copy the user
provides from their own device.
