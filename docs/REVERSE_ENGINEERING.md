# HxdIR (Bigme Hibreak Pro IR Blaster) — Reverse-Engineering Report

Decompiled from `HxdIR.apk` (package `ht.song.remotestar.hxd`, versionName `88.17.101`,
versionCode `8817101`, minSdk 18, targetSdk 27, compileSdk 30) using jadx 1.5.5.

## 1. Top-level architecture

The app is built around a single transport interface

```java
package et.song.tg.face;
public interface ITg {
    void open(IFinish cb) throws Exception;   // cb.OpenCallbk(0) on success, <0 on failure
    int  read(byte[] buf, int len) throws Exception;
    int  write(byte[] buf, int len) throws Exception;
    void close() throws Exception;
    void ioctl(int cmd) throws Exception;
}
```

held in the global `ETGlobal.mTg`. Everything IR-specific (brand database, waveform encoding,
BLE/IO frame construction) is in a native library; Java/Kotlin is only **transport + UI + persistence**.

The transport is chosen manually on the "select communication" screen (`FragmentCom`) and
persisted in `ETSave` SharedPreferences under key `comType` (plus a `230` flag for the
long-capture 230-byte learn variant).

| `comType`   | Class                                                   | Transport                                  |
|-------------|---------------------------------------------------------|--------------------------------------------|
| `bleir`     | `et.song.jni.ble.ETBleClientIR`                         | BLE GATT to "HXD" IR dongle                |
| `ble`       | `et.song.jni.ble.ETBleClient`                           | BLE GATT to other HXD peripherals          |
| `bt`        | `et.song.jni.bt.ETBtClient`                             | Classic BT SPP (`00001101-...`)            |
| `wifi`      | `ETWifiClient(ETNetClientAdapter(HXDP2PClient(uid)))`   | WiFi blaster via TUTK P2P                  |
| `wifilan`   | `ETWifiClient(ETNetClientAdapter(HXDTCPClient(ip,port)))` | WiFi blaster on LAN                       |
| `wifi2285`  | `ETWifiClient(ETNetClientAdapter(HXD2285Client(ip,8899)))` | WiFi blaster (2285 firmware)             |
| `mqtt`      | `et.song.remotestar.hxd.etclass.ETMqtt` / `MqttUtil`    | Cloud relay via Bigme MQTT broker          |
| `usb`       | `et.song.jni.usb.ETUSB`                                 | USB HID blaster (`libet_jni_usb.so`)       |
| `io`        | `et.song.jni.io.ETIO`                                   | **Vendor kernel char device** (`libet_jni_io.so`) |
| `sound`     | `et.song.remotestar.hxd.ThreeManRemote` → `com.threeman.android.remote.comm.RemoteControlLib` | Audio-jack IR via `AudioManager` + per-phone-model parameter DB |

None of these use Android's standard `ConsumerIrManager` — this is the "non-standard
communication" the user observed.

## 2. The `io` path — Bigme Hibreak Pro built-in IR (most likely the user's transport)

`libet_jni_io.so` (13 KB, armeabi, stripped) exports 4 JNI functions and calls helpers
`ctrl_open/ctrl_close/ctrl_write/ctrl_read`. Its `.rodata` contains the device nodes:

```
/dev/hxd_irda
/dev/ctrl
/dev/irremote
```

So the Hibreak Pro's built-in IR LED is a **vendor kernel character device** (`/dev/hxd_irda`,
falling back to `/dev/ctrl` then `/dev/irremote`). The app opens the node and writes/reads IR
frames directly via libc `open/read/write/close` (and `ioctl`). This is why ordinary IR apps
fail — they use `ConsumerIrManager`, which is not wired to this vendor driver.

JNI surface (`ETIO`):
```java
private static native int IOOpen();
private static native int IOClose();
private static native int IORead(byte[] b, int len);
private static native int IOWrite(byte[] b, int len);
```
`IORead` returns -1/0 mapped by Java to `-1001` ("no data yet"). The frame bytes written are
the same frames produced by the IR codec (see §5), so `io` and `bleir` share the upper protocol.

> Access note: opening `/dev/*` nodes normally needs root or a vendor group. The official app
> does it without declaring root, so on the Hibreak Pro the node is presumably world/group
> writable or granted via a vendor permission. Our open-source app will try the same nodes and
> surface a clear error if permission is denied (so the user knows whether root is required on
> their firmware).

## 3. The `bleir` path — BLE "HXD" IR dongle (fully reverse-engineered)

Scan: `FragmentBleIR` does `BluetoothAdapter.startLeScan` and keeps devices whose **advertised
name contains `HXD`**. On tap it builds `new ETBleClientIR(ctx, address)` and `open()`.

GATT (after `onServicesDiscovered`, `ActivityMain.displayGattServices`):
- Service `0000ffe0-0000-1000-8000-00805f9b34fb`
- Single characteristic `0000ffe1-0000-1000-8000-00805f9b34fb` used for **send**, **recv** and
  **notify/enable** (CCCD `00002902-...` enabled with `ENABLE_NOTIFICATION_VALUE`).
- (A second peripheral variant uses service `0000ffa0` with send `0000dc86`, recv `0000ffa3`,
  enable `0000ffa1` — same framing.)

`ETBleClientIR.write(buf, len)`:
1. `readRemoteRssi()`; if `RSSI < -102` abort (out of range).
2. Special 3-byte commands are translated to 4-byte handshakes:
   - `30 10 40` → send `{0x12, 0x35, 0x56, 0x79}`  (enter **learn short** — capture 110 B)
   - `30 20 50` → send `{0x12, 0x35, 0x56, 0x7A}`  (enter **learn long**  — capture 230 B)
3. Otherwise: append terminator `{0x56, 0x78}` ("Vx") at `buf[len]`, `buf[len+1]`, then split
   into **20-byte** GATT writes with **20 ms** sleep between chunks.

`ETBleClientIR.read(buf, len)`: on first call enables notifications (or read) on the recv
characteristic; notifications accumulate into a 256-byte `buf`/`index`. Subsequent calls copy
`len` bytes out when available, else return 0.

## 4. Learning / capture flow (`ActivityMain.StudyTask`)

`StudyTask.doInBackground(select, key)`:
- `select` `"0"` or `"99"` → `key = {0x30,0x10,0x40}`, `buffer = new byte[110]` (short capture)
- `select` `"1"` or `"100"` → `key = {0x30,0x20,0x50}`, `buffer = new byte[230]` (long capture)

1. `mTg.write(key, 3)` → device enters learn mode, `Thread.sleep(200)`.
2. Loop `mTg.read(buffer, buffer.length)` until >0 bytes arrive or `mIsTimeOut` (a handler
   fires the timeout flag). One driver read returns -1001 after only ~1 s — **the loop is
   what keeps the vendor's learn window open for many seconds** (observed: user can take
   10–30 s to press; our port mirrors this with a 15 s overall window, previously a single
   1.2 s shot). The buffer is zero-filled before each read attempt.
3. The captured raw timing bytes are the "learned code". On the "test" dialog button the app
   calls `ETIR.StudyKeyCode(raw, len)` → frame → `mTg.write(frame)` to replay. On "save" it
   stores `ETTool.BytesToHexString(raw)` as the key's data (keyed by `select`/`key` index).

So: **learning returns raw timing bytes; replay requires the `StudyKeyCode` encoder.**

### 4.1 Learned-key TX path (`ActivityMain` "Test Data" handler, case 5)

```java
byte[] bArrStudyKeyCode = ETIR.StudyKeyCode(bArr, bArr.length);   // fresh encode PER TAP
if (bArrStudyKeyCode == null) return;
ETGlobal.mTg.write(bArrStudyKeyCode, bArrStudyKeyCode.length);    // single plain write
```

**The vendor re-encodes on every transmit tap** → the native `g_toggle_bit` alternates per
press, exactly like a real remote. No pre/post driver commands, no repeats, no 55AA split on
the learned path. (Our app originally cached the encoded frame across taps — devices
de-duped the repeats as "button held". Fixed: re-encode per transmit; the `Dsp110` toggle
state is per-codec-instance so the on-screen native-vs-clean diff doesn't disturb TX phase.)

### 4.2 Brand-DB AC TX pattern (`FragmentAIR`)

AC state frames fetched from the brand DB can be **multi-part**: stored as
`55 AA <part1> 55 AA <part2> …`. On such a frame the vendor splits the hex on `55aa` and
writes each part (delimiter stripped) with **300 ms gaps** between parts; ordinary frames go
out as a single write. The temp up/down handlers additionally re-write the whole frame after
the parts. (Ported: `IrBlaster.split55aa` + `sendKey` 300 ms inter-part delay.)

Special AC key `49187` (manual sweep/hand): does not use a DB frame at all — writes the raw
3-byte driver commands `30 00 a0` / `30 00 a1`, alternating per tap (same `30 XX YY` driver
command family as the learn commands `30 10 40` / `30 20 50`).

### 4.3 TX parity conclusion (2026-07-31)

For learned keys, the vendor's over-the-wire behaviour is: fresh `StudyKeyCode` encode per
tap + one plain `/dev` write — byte- and pattern-identical to our app after the fixes above.
No hidden ioctls (`ETIO.ioctl` is an empty method). If a learned replay still doesn't make a
device react when the vendor app's own "Test Data" does (or doesn't), the difference is
physical (aim distance/angle — the vendor learn dialog itself instructs ~3 cm — or device
state), not software.

## 5. The proprietary core: `libet_jni_ir_tools.so` (106 MB, armeabi, stripped)

Everything IR-specific lives here + the asset DB chunks. Its JNI surface
(`et.song.jni.ir.ETIR`) is only 8 methods:

```java
static native void Init();
static native int   GetTableCount(int type);
static native int   GetBrandCount(int type, int table);
static native int[] GetBrandArray(int type, int table);
static native int   GetTypeCount(int type, int table);
static native int[] GetTypeArray(int type, int table);
static native byte[] SearchKeyData(int type, int brand, int key);  // prebuilt code → frame
static native byte[] StudyKeyCode(byte[] raw, int len);            // learned raw → frame
static native int   GetAirDelay(int type, int brand);              // AC inter-command delay
```

`SearchKeyData` returns a complete frame that the Java side passes **unchanged** to
`mTg.write()`. The brand/key catalogue is enumerated by `GetBrand*`/`GetType*`.

**Where the DB lives:** the entire IR codebook is embedded in `libet_jni_ir_tools.so`'s
`.rodata` (~101 MiB of the 106 MiB file; `.text` is only ~19 KiB — the lookup/encode logic is
tiny). `Init()` takes no args and is called right after `loadLibrary`, so the lib uses its own
embedded tables (`tv_table`, `stb_data_table`, `arc_table`, `remote_IPTV_table`, `TV_info`,
`remote_stb_info`, …). Brand *names* come from Android string arrays (`R.array.strs_tv_brand`,
…), not from assets.

**The `assets/{device}_{1|2|3}/{N}.db` files (~56 MiB, 6275 files) are NOT the codebook.** They
are a separate Java-side subsystem: ASCII hex-in-quotes fingerprint templates (plus
`air_3/1.db`, a binary blob of 230-byte AC presets) used only by the setup wizards
(`FragmentWizardsNine/Ten`) to fuzzy-match a *learned* remote to a brand
(`ETTool.DiceExx`/`DiceEx`). They are read in-place via `AssetManager.open`, never copied to
`filesDir`. This makes them easy to consume from an open-source app.

**Learned-code runtime persistence:** native strings reveal `Log.txt.lcd`, `fopen/fread/fwrite`,
`encrypt_vigenere`/`decrypt_vigenere`, `base64_encode/decode`, `learn_data_in_out`,
`get_remote_study_data`/`send_remote_study_data` — so studied codes are persisted by the native
lib to a `Log.txt.lcd` file (path resolved inside `Init()`, likely under the app's data dir),
Vigenère-encrypted. The app's own SQLite (`com.hxd.remotestat.db`) stores saved remotes/keys
separately and also holds learned hex in `ETKEY.key_value`.

**JNI binding (confirmed by `llvm-objdump --dynamic-syms`):** the lib uses **name-based**
exports, not `RegisterNatives`:

```
Java_et_song_jni_ir_ETIR_Init
Java_et_song_jni_ir_ETIR_ClearData          (extra; clears loaded DB state)
Java_et_song_jni_ir_ETIR_GetTableCount
Java_et_song_jni_ir_ETIR_GetBrandCount / GetBrandArray
Java_et_song_jni_ir_ETIR_GetTypeCount  / GetTypeArray
Java_et_song_jni_ir_ETIR_GetAirDelay
Java_et_song_jni_ir_ETIR_SearchKeyData      (0x3d0, ~2.7 KB of code)
Java_et_song_jni_ir_ETIR_StudyKeyCode        (0x9e8, ~8.4 KB of code)
```

So a Kotlin class `et.song.jni.ir.ETIR` with `@JvmStatic external` methods of the same names
binds directly to the user's own copy of the .so (this is what `NativeIrCodec` does).

This is the long pole for a clean-room rewrite:
- `StudyKeyCode` (raw → frame) is **encoder only, DB-free** → tractable, unlocks learning/replay.
- `SearchKeyData` needs the **brand database** (copyrighted data) → realistic as an open-source
  *reader* of the existing asset format (user supplies assets from their own copy); replacing the
  data with a community IR DB is a later phase.

## 6. Device-type & key catalogue (`et.song.remotestar.hxd.ir.IRType`)

16 device classes. Key codes are `deviceType | keyIndex` (low byte = 1..N).

| Device | type code | #keys | example key            |
|--------|-----------|-------|------------------------|
| TV     | 0x2000 (8192)   | 26 | POWER=8203, VOL+=8201 |
| IPTV   | 0x2100 (8448)   | 25 | POWER=8449            |
| DC     | 0x2300 (8960)   | 1  | SWITCH=8961           |
| BOX    | 0x2500 (9472)   | —  |                       |
| AP     | 0x2700 (9984)   | 18 | POWER=9985            |
| AUDIO  | 0x2900 (10496)  | 18 | POWER=10507           |
| POWER  | 0x2B00 (11008)  | 6  | SWITCH=11009          |
| SLR    | 0x2D00 (11520)  | 1  | SWITCH=11521          |
| HW     | 0x2F00 (12032)  | 10 | POWER=12033           |
| ROBOT  | 0x3100 (12544)  | 21 | POWER_ON=12545        |
| STB    | 0x4000 (16384)  | 23 | POWER=16421 area      |
| DVD    | 0x6000 (24576)  | 19 | POWER=24587           |
| FANS   | 0x8000 (32768)  | 22 | POWER=32769           |
| PJT    | 0xA000 (40960)  | 22 | POWER_ON=40961        |
| AIR    | 0xC000 (49152)  | 18 | POWER=49153           |
| LIGHT  | 0xE000 (57344)  | 20 | POWER_ON=57345        |
| CUSTOM | 0xFE000000 (-33554432) | — | learned/custom remotes |

`ETIR.Builder(type)` returns the matching `IR` model class
(`TV/AIR/STB/.../CUSTOM`) implementing `FindBrand/FindType/Search/GetStudyData/...`.

## 7. Persistence & UI

- `ETSave` — SharedPreferences wrapper (singleton) storing `comType`, `ble_address`,
  `230`, `wifi_uid`, `wifilan_ip/port`, etc.
- `et.song.remotestar.hxd.db.ETDB` / `DBProfile` — SQLite for user remote profiles.
- Navigation: `ActivityMain` → sliding menu → `FragmentMore` → `FragmentCom` (pick transport)
  → `FragmentDevice` (pick device class) → per-class `FragmentTV/AIR/...` or `FragmentCustom`
  → `FragmentWizardsFour/Nine/Ten` (auto-search / brand-wizard flows) and the learn dialog in
  `ActivityMain` (messages 5/6 + `BROADCAST_START_LEARN`/`END_LEARN`).

## 8. Codec internals — RE findings (`libet_jni_ir_tools.so`, `.text` ~19 KB)

### Persistence (`Init`/`encode`/`decode`/`clear_file`)
- `Init()` builds `m_path = "/sdcard/Log.txt.lcd"` (bytes: `/sdcard/` + `Log.txt.lcd`), clears a
  248-byte state struct, calls `access(path, F_OK)`; if the file is missing it calls `encode(0)`
  to write an encrypted timestamp header.
- `encode(data)`: `time()` → `snprintf` → `base64_encode` → `encrypt_vigenere` → `fwrite`.
- `decode()`: `fopen(rb)` → `fseek(0)` → `fread(128)` → `decrypt_vigenere` → `base64_decode` → `atoi`. Records are 128-byte blocks.
- `clear_file()` = `ClearData()` JNI: truncates/rewrites the file.
- `encrypt_vigenere(plain, key, out)`: `out[i] = 0x30 + ((plain[i] + key[i % keylen] - 21) % 75)`.
- `decrypt_vigenere(cipher, key, out)`: `out[i] = 0x30 + ((key[i % keylen] + 75 - cipher[i]) % 75)`.
  (Printable-range Vigenère over the base64 text. The key string lives in `.rodata`; not needed
  for our app since we use our own persistence — only needed to read the official `Log.txt.lcd`.)

### `StudyKeyCode(raw, len)` → transmit frame (RE covered)
Branches on `len`:
- `len == 230` (long capture): produces a **232-byte frame**:
  `frame[0]=0x30, frame[1]=0x03, frame[2..230] = raw[1..229], frame[231] = (0x33 + sum(raw[1..229])) & 0xFF`.
  Before copying, the payload is sanitized in place (**VALIDATED 2026-07-29 — byte-exact vs the
  vendor codec on real captures, golden test `CleanIrCodec230GoldenTest`**):
  - for `i in 37..227`: if `(raw[i] & 0x08)` → `raw[i] |= 0x0f`; then if signed `raw[i] < 0` → `0xff`.
    (Net effect of the two native loops at 0x2a28/0x2ad2; order matters for bytes with both bits.)
  - zero-run patch (0x2a76 + 0x2bc8): the FIRST 9-byte all-zero window in a **sliding** scan
    (`buf[k+38..k+46]` for `k in 0..176` — the window slides by 1, not by 38) gets
    `buf[k+38] = 0xff` and `buf[k+37] |= 0x0f` if its low nibble was empty.
  - header fixups (0x2b12..0x2b46, evaluated on the sanitized buffer):
    if signed `raw[8] >= 33` → `raw[8] = 1` and `raw[7] = 0x15`;
    if signed `raw[2] <= 3` → `raw[2] = 0xfe`; if signed `raw[1] <= 4` → `raw[1] = 0xfe`;
    if `raw[4] == 0` → `raw[4] = 0x34`.
- `len == 110` (short capture): produces a **112-byte frame** via `learn_data_in_out` + the
  `compdata` DSP pipeline (ported in `Dsp110.kt`, **VALIDATED 2026-07-30 — byte-exact vs the
  vendor `.so` on a 96-case on-device oracle fuzz; see §9.1**).
- else: returns an error-length array.

The frame is then handed to the transport: BLE chunks it (20 B + `0x56 0x78` terminator), the
`/dev/hxd_irda` node takes the 232 bytes directly. So **learned-code replay is implementable in
pure Kotlin** once the 230-byte path is validated against a real capture.

### `SearchKeyData(type, brand, key)` → frame (RE: header assembly recovered)

Disassembled at `0x33d0` (`Java_et_song_jni_ir_ETIR_SearchKeyData`). It is a large `switch` on
`deviceType` (r2) with `brandIndex` in r3/[sp+28] and `keyCode` at [sp+0x148]. For the
"info-table" types (AUDIO 0x2900, PJT 0xA000, STB, DVD, FAN, …) the recovered header assembly
(AUDIO branch @ 0x348e shown; PJT @ 0x3520 identical shape) is:

```
frame[0] = 0x30            frame[1] = 0x00
r4 = brandIndex * STRIDE              (literal-pool stride per type)
record = infoTableBase + r4
frame[2] = record[0]                  ; an intra-record byte offset (payloadOff)
frame[5] = record[0x25]
frame[6] = record[0x26]
frame[7] = record[0x27]
frame[8] = record[0x28]
payloadOff = frame[2]
frame[3] = record[payloadOff + 1]
frame[4] = record[payloadOff + 2]
payload  = record[payloadOff + 3 ..]   ; IR timing bytes
checksum = (seed + sum(frame[2..end-1])) & 0xFF
```

AC (`g_remote_arc_info`, branch @ 0x38e4) is a **different schema**: each brand record holds
`keyCount` (u32) followed by `keyCount` u32 offsets into `arc_table`; the payload is the bytes
at `arc_table[offset]`. TV (0x2000) and PJT (0xA000) strides don't divide cleanly by the
`strs_*_brand` array length, confirming the multi-group table layout (`_info` / `_2_info` /
`_3_info` ≈ the `assets/{type}_{1,2,3}` wizard groups) — the brand-name index ↔ info-table
index alignment is only guaranteed for the clean-stride types (SLR, AP, AUDIO, STB, DVD, FAN,
ARC, Lamp, IPTV, Water-Heater).

**Confident:** header bytes, frame wrapper, brand-record indexing via `STRIDE`.
**Unvalidated (needs one on-device transmit per type):** exact payload length, checksum seed,
frame size (assumed 232), and the per-key stride within the payload region. The clean-room
`BrandDbReader.searchKeyData` implements this header assembly and is marked experimental.

## 9.1 The 110-byte learned path — full pipeline (RE complete; Kotlin port VALIDATED byte-exact)

`StudyKeyCode(raw, 110)` copies `raw[0..109]` into a buffer and calls `learn_data_in_out(buf)`
(0x3e78), which returns a **112-byte frame**. The pipeline:

1. `get_remote_study_data(buf)` (0x3f98): expands the 110 bytes into a 110-word int array
   (each byte → 32-bit slot), then calls `compdata(ints, &len)`. On success it copies the
   processed words' low bytes back into `buf[0..108]`.
2. `compdata(ints, &len)` (0x40e0) — the IR-signal conditioner:
   - `len -= 1`; if `ints[0] != 0` → no processing, return 1.
   - if `ints[1]` (the sample-count word, offset +4) >= 128 → `Modifywave` then `delfeng`;
     else → `modifywavem708`.
   - then `getfigure`. Returns 0 on success.
3. `send_remote_study_data(buf, len)` (0x400c): builds the frame into a global buffer:
   - `frame[0] = 0x30`, `frame[1] = 0x02`  ← 110-path subcommand is 0x02 (vs 0x03 for 230-path)
   - flips a global `g_toggle_bit` each call (`g = 1 ^ (g & 1)`); when the *previous* value
     was 0, XORs 0x10 into `words[0]`; then `keytogglebit(payload, 110)` applies
     protocol-specific toggle positioning (`JudgeToggleBit`/`changetogglebit`) for keys whose
     first byte has bits 0..2 set.
   - `frame[2..2+len-1] = payload[0..len-1]`
   - `frame[111] = (0x32 + sum(payload)) & 0xFF`  ← checksum (seed 0x32, not 0x33)
   - total 112 bytes.
4. `learn_data_in_out` then copies the 112-byte global into `buf`, runs a sanity gate
   (`buf[3] >= 5`; `16 <= buf[5] < 129`; `(sum(buf[10..14]) & 0xff) != 0`) and on failure zeroes
   the buffer. **VERIFIED**: the gate runs on the *frame* bytes (after the copy-back), and the
   JNI wrapper returns `ByteArray(0)` for wrong input sizes — never null.

**Int-array / overflow model** (shared by all the wave functions): each timing sample is a
32-bit word. Bit 7 (0x80) of the low byte is an **overflow flag**: a word `v` with bit7 set
encodes a long timing as `((v & 0x7f) << 8) + nextWord` (max 0x7FFF), consuming two slots.

### `Modifywave` (0x52bc) — VALIDATED byte-exact

Walks `w[5..]`, overflow-decoding (2-byte form only; read index clamps at `len+1` with a zero
second byte), adjusting **even samples −7 (min 2) / odd samples +3**, re-encoding with the
`0x80` two-byte form when `adj > 0xFF || (adj & 0xFF) >= 0x80` (high byte `0x80 | (adj >> 8)`,
**no 0x7f mask**). A too-short capture (`len+1 < 6`) skips the loop but **still runs the header
fixup**. Header fixup: if `w[5] >= 128` → `w[6] += 3`, or if `w[6] >= 253` → `w[6] -= 253,
w[5] += 1` (idx = 7); else `w[5] = min(w[5]+2, 127)` (idx = 6). Then: `w[idx] >= 128` →
`w[idx+1] -= 1` (or if 0: `w[idx+1] = 0xFF, w[idx] = 0x80 | ((w[idx]&0x7f)-1)`); `w[idx] <= 1`
→ `w[idx] = 2`. Returns 1 only when `w[0] != 0`.

### `delfeng` (0x53d8) — VALIDATED byte-exact

"Delete peak": compacts `w[5..]` in place, folding tiny (≤ 4) odd-parity samples into their
neighbours. Faithfully-reproduced quirks:
- `prev` (sp[0]) is vestigial (written 0 once, never updated) → merge condition is just
  `value <= 4 && (count & 1) == 1`; the merge-test `value` for a 2-byte sample is its **LOW
  byte**, not the decoded value.
- The "next" sample is read at the **current** read position (sp[40] = `i` for 1-byte / `i+1`
  for 2-byte), so a merged 1-byte sample is summed twice (`sum = prev + 2·spike + ...`) and the
  following slot is skipped.
- Merge write-back: `sum = prevParts + value + nextParts` with full 2-byte carry arithmetic
  (`(prevHigh + nextHigh) << 8`); if `sum >> 8 >= 128` writes `0x80 | hi` at `pos1` and `lo` at
  `pos2`, else writes `lo` at `pos1`; `out = writePos + 1`.
- `count` advances **+2 per merge** (+1 per keep) and the running loop limit (sp[36], initially
  `w[2]`) **shrinks by 2 per merge persistently**. Exits when `count >= limit` (unsigned) or
  read index `>= len+1`.
- The merge back-off (`pos2 = nextOut − (acc > 127 ? 2 : 1)`) uses `acc` = the previous value's
  high part (KEEP) / last merged sum's encoded high byte (MERGE) — so `acc > 127` is only
  reachable after a 2-byte merge write.

### `modifywavem708` (0x5508) — VALIDATED byte-exact

3-byte (`0xF0`-pattern: `(v&0x0f)<<16 | w[i+1]<<8 | w[i+2]`) / 2-byte / 1-byte decode,
quantise `(v+2) >> 4`, even-index: `outv = val16 - (combined > 4093 ? 1 : 0)`, **reject (return 1)
when an even-index `outv > 128`** (→ compdata passthrough of the raw capture); odd-index:
`outv = max(val16 - prev - 2, 1)`. Header fixup as in `Modifywave` but `+3`/`−5` asymmetric.

### `getfigure` (0x565c) + `judgesame` (0x5884) + `cmpdata` (0x58f8) — VALIDATED byte-exact

Peak-based classification: max overflow-decoded sample over `w[5..len-1]`, first 3 peaks with
`value >= max − max/8`, then a classification tree on `w[1] & 0x40`, peak ordinals/spacing and
`judgesame` period checks → figure class ∈ {0,1,2,3}. Writes `w[1] = (w[1] & 0x88) | class`,
`w[2] = ordA + (ordA & 1)`, `w[3] = ordB + (ordB & 1)`.

### `JudgeToggleBit` (0x416c) / `changetogglebit` (0x4898) — tables + structure RE'd, port DEFERRED

Called by `keytogglebit` only when `payload[0] & 7 != 0` (getfigure class ∈ {1,2,3}).
`send_remote_study_data` ignores `keytogglebit`'s return, so this only matters via
`changetogglebit`'s in-place frame mutation. The Kotlin port stubs `judgeToggleBit` → 0
(no mutation): byte-exact for all 96 corpus cases (none match a toggle-protocol signature).

**Globals** (all in .rodata/BSS, dumped and parsed 2026-07-30):

- `STandantdata` @ 0x65217f4: 8 × u16 = 4 (mark,space) pairs — (468,312), (467,310),
  (290,131), (439,311). These are the canonical timings of 4 NEC-style toggle protocols.
- `ToggleBit_Place` @ 0x6521804: 7 bytes [6,4,10,6,16,4,2] — toggle sample position per
  return code (code−2 index, codes 2..8).
- `code_format` @ 0x652180b: **8 records × 87 bytes** (ends exactly at the R-X segment end
  0x6521b22). Record format:
  - `[0..15]`: four acceptance windows, each `(lo,hi)` u16-LE with the same 0x80-high
    encoding as waveforms (`v = ((b[k+1]&0x7f)<<8)|b[k]`)
  - `[16..17]`: flags
  - `[18]`: selector — `0xf0` = 5-byte window-group body, `0xf1` = compact body
  - body: opcodes `f1 N` (repeat window pair N times), `f0 lo0 hi0 lo1 hi1` (explicit
    window group), `f2` = end (match success)
- `cntx`/`cnty` @ 0x6523178/0x652317c (BSS, waveform read cursors).

**Parsed records** (windows = `[lo,hi]`, body commands):

| rec | ret | win0 | win1 | win2 | win3 | flags | sel | body |
|---|---|---|---|---|---|---|---|---|
| 0 | 8 | [0,40] | [250,375] | [0,40] | [406,531] | 00 00 | f0 | ×4 groups [0,12]/[168,293], f1 2, f1 8, f2 |
| 1 | 7 | [0,40] | [212,356] | [0,40] | [368,543] | 00 00 | f1 | f1 5, f1 6, f2 |
| 2 | 6 | [22,35] | [62,187] | [22,35] | [218,343] | 00 00 | f1 | f1 5, f1 7, f2 |
| 3 | 5 | [1,55] | [218,368] | [0,55] | [387,531] | 01 00 | f0 | ×2 groups [1,55]/[140,296], f1 4, f1 6, f2 |
| 4 | 4 | [18,36] | [18,36] | [68,27] | [43,27] | 01 00 | f0 | [106,231],[48,61], ×4 [18,36] |
| 5 | 3 | [18,36] | [18,36] | [90,55] | [62,27] | 01 00 | f0 | [106,231],[49,61], f1 4, [49,61]×2, f1 8, f1 8, f2 |
| 6 | 2 | [40,65] | [40,65] | [118,52] | [93,52] | 01 01 | f1 | f1 8, f1 6, f2 |
| 7 | 1 | [18,43] | [18,43] | [18,43] | [63,31] | 01 00 | f0 | [18,43],[118,200],[18,43],[47,93]×2,[18,43] |

Rec 0/1 mark window ⊇ STandantdata 468/467 ✓; rec 2 [218,343] ∋ 290 ✓; rec 3 [387,531] ∋ 439 ✓.
Inverted windows (rec 4-7, block B) are compared via **midpoint/tolerance** (`(lo+hi)/2`,
XOR-alternating polarity — biphase/RC5-RC6-style signatures), not simple [lo,hi].

**Algorithm** (~900 insns, -O0 spaghetti with 33 aliased cached global pointers — only 2
distinct globals: `code_format`, `cntx`):

1. `count = 8`; per iteration `recIdx = 8 - count`; fail → `count--`, retry; `count == 1` on
   fail → return 0. recIdx 0-3 → block A (0x42a4), recIdx 4-7 → block B (0x4682, midpoint
   windows, recIdx==6 gets `neg=-1` flag), recIdx ≥ 8 → return 0.
2. Block A: waveform decode starts at w-index **8 for record 0, 0 for records 1-7**
   (native quirk: `r1=8` initial, `movs r1,#0` on the fail path). Windows consumed in order
   win0, win1, win2, win3, then body opcodes; `f1 N` = repeat the (winA, winB) sample pair
   N times. Any window violation → next record. `0xf2` at cursor → **success: return count**
   (rec0→8, rec1→7, … rec7→1).
3. `keytogglebit` (0x4130): if `w[1] & 7 == 0` → returns `w[1] & 0xf0` untouched; else
   `w[1] ^= 0x10`, calls JudgeToggleBit, and iff nonzero calls
   `changetogglebit(w, len, code)`; returns `(w[1] & 0xf0) | code`.
4. `changetogglebit` (0x4898, ~1000 insns): codes < 2 (record 7) ignored; `cntx = 5`;
   re-walks the waveform with helper `cmpaequbtog` (0x50d4, tolerance compare) and mutates
   the toggle sample(s) in place (positions from the record's `f1` operands /
   `ToggleBit_Place[code-2]`).

**Port + validation strategy (documented for future work):** craft a 110B input whose
post-DSP waveform matches a record (Modifywave transform is `adj = combined−7` even /
`+3` odd parity samples, so raw bytes ≈ window center +7/−3; must also pass the compdata
gate: `w[0]=0`, `w[1]≥0x80`, `w[4]∈[16,129)`, getfigure peaks at ordinal ≥3, fig ∈ {1,2,3}).
When the native matcher hits, `changetogglebit` mutates the frame → observable as a
vendor-vs-clean MISMATCH in the shard fuzz harness, directly revealing the mutation bytes.
Iterate until byte-exact. Deferred: ~2.5K insns total (JudgeToggleBit + changetogglebit +
cmpaequbtog), narrow impact (toggle-protocol repeat-press alternation only).

**Kotlin port — VALIDATED byte-exact** (`app/.../codec/Dsp110.kt`): on-device oracle fuzzing
against the vendor `.so` (shard-per-case instrumented runner, fresh process per case) over a
96-case corpus — structured NEC/Sony-like captures, ramp/alt patterns, overflow-heavy and
randomised payloads targeting each DSP branch — shows **byte-exact agreement on every case**
(final sweep 2026-07-30: 16 DSP-processed/gate-passed incl. all 8 `dspReal*` full-pipeline
cases + 35 gate-pass passthrough + 44 both-zero, plus 1 input hostile enough to stack-smash
the *vendor* lib itself). `CleanIrCodec.studyKeyCode` uses this for 110-byte captures.

## 9.2 Brand-DB `SearchKeyData` — `.rodata` table layout (RE phase 2 — FULLY recovered)

The entire 106 MB `.rodata` (106,013,669 bytes) **is** the brand DB, and every table is a
named exported symbol (resolved via `.rel.dyn` R_ARM_GLOB_DAT relocations into a GOT-like
pointer array at vaddr `0x6522df8`). vaddr == file offset for the first PT_LOAD, so all tables
are directly readable from the `.so`.

**27 `*_info` index tables** (per-brand records; record[0] = count, fixed per-type stride):

| deviceType (IRType) | code | stride | info symbol | vaddr | size |
|---|---|---|---|---|---|
| TV | 0x2000 | 8648 | `TV_info` | 0x04fbc358 | 9,460,912 |
| AC (arc) | 0x4000 | 1608 | `g_remote_arc_info` | 0x05fa87b8 | 699,480 |
| STB | 0x4000? | 2204 | `remote_stb_info` | 0x017e3fb0 | 4,125,888 |
| DVD | 0x6000 | 5208 | `remote_dvd_info` | 0x021e0468 | 3,202,920 |
| PJT | 0xa000 | 448 | `remote_pjt_info` | 0x0009f114 | 76,608 |
| FANS | 0x8000 | 836 | `remote_fan_info` | 0x001fab38 | 135,432 |
| IPTV | 0x2100 | 960 | `remote_IPTV_info` | 0x062baea4 | 137,280 |
| AUDIO | 0x2900 | 1752 | `remote_Audio_info` | 0x0647150c | 504,576 |
| SLR | 0x2d00 | 56 | `remote_SLR_info` | 0x062dcc9c | 784 |
| Water Heater (HW) | 0x2f00 | 76 | `remote_Water_Heater_info` | 0x062e84a4 | 1,064 |
| Air Purifier (AP) | 0x2700 | 80 | `remote_air_purifier_info` | 0x062e5330 | 1,360 |
| Robot Cleaner | 0x3100 | 48 | `remote_robotcleaner_info` | 0x064f2748 | 576 |
| Lamp (LIGHT) | 0x3300 | 360 | `remote_Lamp_info` | 0x0651f20c | 9,000 |
| (2nd-gen variants) | … | … | `*_2_info`, `g_remote_arc_2_info`, `g_remote_arc_3_info`, `remote_tv_info` | … | … |

(Some deviceType→stride pairings still need the branch-aware switch walker; the symbol map
itself is complete — see `re/branddb_symbols.json` and `re/branddb_full.json`.)

**13 `*_table` raw-code tables** (the actual IR frame bytes, indexed by offsets in the info records):

| symbol | vaddr | size |
|---|---|---|
| `tv_table` | 0x024eec40 | 44,881,686 |
| `stb_data_table` | 0x0021bf58 | 22,839,381 |
| `arc_table` | 0x058c97f8 | 7,204,800 |
| `dvd_data_table` | 0x01bd3eb0 | 6,342,070 |
| `remote_Audio_table` | 0x062e88fc | 1,608,717 |
| `stb_fan_table` | 0x000b1da4 | 1,346,961 |
| `remote_IPTV_table` | 0x0620d5c8 | 710,875 |
| `remote_Lamp_table` | 0x064f2a20 | 182,250 |
| `stb_pjt_table` | 0x000073f0 | 621,859 |
| `remote_air_purifier_table` | 0x062dcfdc | 33,620 |
| `remote_robotcleaner_table` | 0x064ec85c | 24,299 |
| `remote_Water_Heater_table` | 0x062e58b0 | 11,250 |
| `remote_SLR_table` | 0x062dca1c | 637 |

**Relevant globals**: `g_i2c_cmd_buffer` (112-byte TX frame buffer, vaddr 0x06523004),
`g_toggle_bit` (0x06523074), `code_format`, `ToggleBit_Place`, `STandantdata`.

`SearchKeyData(type, brand, key)` (0x33d0) per-type switch:
1. bounds-check `brand`/`key`;
2. `off = stride[type] * key` into the type's `*_info` table; read field bytes at per-type
   record offsets (e.g. AC uses 0x25/0x26/0x27/0x28) into `frame[2..8]`;
3. read payload from the type's `*_table` using offsets carried in the record;
4. assemble `frame = [0x30, 0x00, fields…, payload…, checksum]` (prebuilt subcommand 0x00).

**Implication for the open-source app**: a fully working `searchKeyData` is now feasible —
load the `.so`'s `.rodata` (or ship the named tables as assets), index by the per-type stride,
and replicate the per-type field offsets. It still needs (a) extracting the remaining
deviceType→stride/field-offset pairings via a branch-aware walker over `GetTableCount`/
`GetTypeCount`/`SearchKeyData`, and (b) hardware validation. Mechanically extractable; the
hard part (the table format) is solved. Extractors: `re/extract_branddb.py`,
`re/join_branddb.py`; outputs: `re/branddb_symbols.json`, `re/branddb_full.json`.

## 10. What we will reuse / reimplement (open-source app, MIT)

- **Transport layer (clean-room, pure Kotlin):** `io` (open `/dev/hxd_irda`/`/dev/ctrl`/
  `/dev/irremote`), `bleir` (BLE GATT HXD framing above), and `sound` (audio-jack) are all
  re-implementable from this report without the original binaries.
- **IR codec (clean-room target):** `IrCodec` interface mirroring the 8 JNI methods.
  - `.text` of the native lib is only ~19 KiB — the lookup/encode logic is small and very
    tractable to RE. The ~101 MiB `.rodata` is the embedded codebook (tables `tv_table`,
    `arc_table`, …).
  - Phase 1: reverse `StudyKeyCode` (raw timing → frame) + the `Log.txt.lcd` Vigenère
    persistence so learning + replay work with zero proprietary code.
  - Phase 2: reverse the `.rodata` table format (`*_info` index structs + `*_table` data) into
    an open-source reader, so the user's own `.so` (or an extracted `.rodata` blob) can be read
    without executing proprietary code.
  - Phase 3: the wizard fingerprint assets (`assets/{device}_{1|2,3}/{N}.db`, ASCII hex) are
    Java-side and trivial to consume open-source; optionally swap the codebook for a community
    IR DB so no proprietary data is needed at all.
- **Dropped (privacy):** `mqtt` cloud relay and TUTK P2P cloud — they phone home to Bigme
  servers and are not needed for local IR blasting.

## 11. Universal-TX architecture (open-source compatibility strategy)

Goal: a *universal* remote with maximum device compatibility, MIT-clean. The proprietary 106 MB
brand DB is **not** redistributed (copyright); it's an opt-in, user-supplied pack. Default
coverage comes from **open IR databases + open IR formats**, which are bigger and
community-maintained than the vendor snapshot.

The only TX entry points in the original app are `StudyKeyCode(raw, len)` (learn) and
`SearchKeyData(type, brand, key)` (brand DB) — both produce a **device frame**
`[0x30, subcmd, payload, checksum]` written to `/dev/hxd_irda` via `IOWrite`. There is no
"transmit arbitrary timings" API; **the device frame is the universal transmit interface.**
So open-format codes are converted into that frame:

- `IrSignal` (IR): `frequencyHz` + alternating mark/space (us) + optional repeat. Every parser
  produces it; the encoder consumes it.
- `IrSignalParser` (spec-based, no RE, no validation needed): Flipper `.ir`, LIRC `.conf`
  (`raw_codes`), Pronto Hex (0000/0100; carrier = 4145140/C Hz, count = C·0.241246 us), raw us
  timings.
- `BigmeFrameEncoder.encode(IrSignal) -> 232 B frame`: wraps timings in the confirmed
  `[0x30, 0x03, payload, checksum]` learned-code frame using the 0x80-overflow body encoding
  (T ≤ 0x7F → `[T]`; else `[0x80|(T>>8 & 0x7F), T&0xFF]`, max 0x7FFF).
  - **UNVALIDATED (the only unknowns):** (1) `unitUs` — us per device unit (default 1, assume
    device unit == us); (2) the header bytes (payload[0..7]: carrier/count/repeat). The header
    is normally produced by `compdata`/`getfigure` (learned) or read from brand-DB records
    (prebuilt). The authoritative layout is recovered from brand-DB records (§9.2); one real
    learned capture pins down `unitUs`.
- `BrandDbReader` (opt-in): loads a user-supplied `.rodata`; `brands(type)`/`keyCount(type,brand)`
  work (record first word = key count); `searchKeyData` now builds the recovered `[0x30,0x00,
  header, payload, checksum]` frame (§`SearchKeyData` above) for the info-table types —
  experimental/unvalidated, see notes there. Brand display names come from `BrandNames`
  (extracted from the app's `strs_<type>_brand` arrays — see §12).
- **Open IRDB catalog** (`IrdbCatalog` + `CatalogScreen`): the default universal-remote path.
  The user picks a folder of open IR files (e.g. the Flipper IRDB tree, `Category/Brand/Model.ir`)
  with the system directory picker; the app walks it via SAF, parses each file with
  `IrSignalParser`, and builds a browseable category→brand→model→code index. A selected code is
  fed to `BigmeFrameEncoder` and transmitted. MIT-clean, no proprietary binary, widest coverage.

### 12. Brand display names (`strs_<type>_brand`)

The original app does **not** store brand names in the `.so` `.rodata`; they live in Android
string **arrays** in `res/values-en-rUS/arrays.xml` (`strs_tv_brand`, `strs_air_brand`, …,
12 types, 4795 names total). `FragmentBrand.BrandTask` indexes `strs_<type>_brand[brandIndex]`
to label each brand. Brand names are factual identifiers (not copyrightable); the index
alignment only matters for the opt-in proprietary DB. `re/extract_brandnames.py` regenerates
`BrandNames.kt` (a `Map<deviceType, List<String>>`) from the decompiled arrays; `BrandDbReader`
uses it to name brands, falling back to `Brand N` when the proprietary table has more entries
than the name array (TV/PJT multi-group case).

### 9.2 Brand-DB record schemas (from real bytes)

Each `*_info` record's first 32-bit LE word = key count for that brand. The per-key body
differs by device type (matches the per-type field offsets in `SearchKeyData`):

- **AC (`g_remote_arc_info`, stride 1608):** `[keyCount:4][offset0:4][offset1:4]…` — each
  offset indexes into `arc_table`; `arc_table[offset]` is the per-key payload. rec[0] key
  counts 16/17 observed; offsets ~500–1100.
- **Lamp (`remote_Lamp_info`, stride 360):** `[keyCount:4][keyIndex0:4]…` — global key indices
  (0,1,…,29 for a 30-key brand; 33,…,40 for an 8-key brand) into `remote_Lamp_table`.
- **SLR (`remote_SLR_info`, stride 56):** `[1:4][brandIndex:4]` — 1 key per brand, second word
  = brand index (0,1,2,…).

Decoding the remaining per-type schemas + `SearchKeyData`'s header assembly (per-type field
offsets like 0x25/0x26/0x27/0x28 for Audio, 0x2d–0x30 for Lamp) is RE task 19 and finalizes
both the brand-DB reader **and** `BigmeFrameEncoder`'s header layout.

### App wiring (UI)

`ImportScreen`: file picker → `IrSignalParser.parse` → list/select → `importTransmit` →
`BigmeFrameEncoder.encode` → `IrBlaster.writeRaw`. This is the testable universal-TX path:
import a known-good Flipper/LIRC code for the target device and transmit; if it reacts, the
encoder is validated for that payload. `DebugScreen`: learn a real capture (230 B) → clean
encoder → inspect hex → transmit (validates the learned path + pins down `unitUs`).
