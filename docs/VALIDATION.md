# Hardware validation checklist

Everything that *can* be done in software is done (build green, JVM unit tests pass, A/B UI
in place). The remaining unknowns need the physical Bigme Hibreak Pro + a target device. This
is the exact list of what to validate, in priority order, and how. Run the debug APK:

```
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open the app → **Debug / Validate codec**.

For every capture below, after Learn the screen shows: raw capture hex, sanitized/payload hex,
emitted frame hex, the frame layout + checksum, and the **sanity-gate** status (110B only).
**Copy frame hex** copies the 112/232-byte frame to the clipboard — paste it back to me with
the raw capture hex so I can iterate.

---

## V1 — 230-byte learned replay (HIGHEST confidence, do this first)

**Why:** the 230B frame wrapper + checksum + sanitization are exact; only two header fixups
(`raw[1]`/`raw[8]` from capture-structure parse) are unconfirmed. If this works, learned
replay is done and we have a ground-truth capture to calibrate everything else.

**Steps:**
1. Debug screen → select **230 B (long)** → **Open built-in IR** → **Learn**.
2. Point a real remote (TV/AC/STB) at the phone, press a key once.
3. Note the emitted frame hex (Copy frame hex). **Transmit frame** → point phone at the device.
4. Tap Transmit 2–3× — the toggle bit now alternates per tap. (The 2026-07-29 "AC switched
   off but wouldn't switch back on" was almost certainly the old frozen-toggle bug — the AC
   de-duped the identical repeat frames. Retest with this build.)

**Pass:** device reacts identically to the original remote (power toggles, vol changes, etc.).
**If fail:** paste me (a) the 110/230 raw capture hex and (b) the emitted frame hex, and tell me
which key/remote. I'll fix the `raw[1]`/`raw[8]` header fixups from the capture.

**Also capture and keep** one good 230B raw hex for a known-simple remote (e.g. a TV power key) —
this becomes the calibration source for V4 (universal-TX unit).

---

## V2 — 110-byte learned path end-to-end TX test (DSP port is now byte-exact)

**Why:** `Dsp110` (the `compdata`/`Modifywave`/`delfeng`/`getfigure` port) is **VALIDATED
byte-exact against the vendor `.so`** (96-case on-device oracle fuzz, zero mismatches). What
remains unproven is *end-to-end*: that a real 110B capture → our frame → the phone's IR LED →
the target device works. A/B testing against the unconditioned baseline also tells us whether
the conditioning matters for real hardware.

**Steps (do this with a remote whose key fits in a 110B capture — typically a single short
protocol, e.g. NEC TV power):**
1. Debug screen → **110 B (short)** → **Learn** → press the key — the window now stays open
   **~15 s** (vendor-style re-read loop), no need to race it.
   *(No remote at hand? **Replay saved 110B capture** loads the pristine 2026-07-31 capture.)*
2. With **Conditioned (DSP)** selected: read the **sanity-gate** status line. **Transmit frame**.
   Observe device. **Tap Transmit 2–3 more times** — the toggle bit alternates per tap (like a
   real remote / the vendor lib), and a device ignores frames whose toggle matches the last
   press it saw, so the *second* tap is often the one that registers. Aim the IR edge at the
   device's receiver from **~3 cm** (the vendor app's own learn dialog instructs this; the
   phone's LED is much weaker than a dedicated remote).
3. Toggle to **Unconditioned** (re-encodes instantly, no re-learn): **Transmit frame**. Observe.
4. Repeat each a couple of times.

**Control experiment (decisive, do this if step 2 fails):** the vendor `HxdIR.apk` (repo root)
implements the *identical* learned-TX path — proven by source (RE §4.1/§4.3): fresh
`StudyKeyCode` per tap + single plain write, no hidden ioctls. Install it, learn the same key
(DIY equipment → ADD BUTTON → tap it → press remote), aim, and hit **Test Data**:
- **Vendor works, ours doesn't** → impossible unless the physical conditions differed between
  tests; re-run both at the same distance/angle.
- **Vendor also doesn't work** → physical layer (LED strength, aim, device state), not
  software. Confirm the real remote still works on the device; try at 3 cm; try the AC from
  V1 (its receiver already accepted our 230B frames once).
- Uninstall the vendor app afterwards if you don't want it phoning home.

> **2026-07-31 debug-session fixes** (found after a "device did not react" report):
> - The codecs mutate their input buffer in place; the debug screen passed the *stored* capture
>   straight in, so after the first encode the "raw" capture was silently the DSP'd output —
>   "Unconditioned" was never actually unconditioned, and the on-screen native-diff ran on
>   mutated input. Fixed: `encodeForDebug` always encodes a private copy.
> - The transmitted frame was encoded once at Learn and cached, so the toggle bit never
>   alternated between transmits — devices de-duped every frame as a held-button repeat.
>   Fixed: `debugTransmit` re-encodes per tap; verified on-device heads alternate
>   `…91…, …81…, …91…, …81…` per tap.
> - `Dsp110`'s toggle state was a process-global singleton, so the native-vs-clean diff's
>   hidden encode kept the TX phase stuck. Fixed: toggle state is per-`Dsp110`-instance
>   (`Dsp110` object → class); the diff uses a throwaway codec.

**Outcomes & what they mean:**
- **Conditioned works, Unconditioned doesn't** → conditioning matters; the vendor DSP pipeline
  is doing real work. Ship as-is.
- **Both work** → device is tolerant; keep conditioned as default (matches vendor exactly).
- **Unconditioned works, Conditioned doesn't (or gate FAIL)** → something differs between the
  oracle-fuzz environment and real captures. Paste me the raw 110B hex + both frame hexes +
  the key/remote.
- **Neither works** → TX-side issue (unit timing, LED drive), not the codec. Compare against
  the V1 230B result.

**CRITICAL — paste me every 110B raw capture hex even when things work.** We have **zero real
110B captures** (`real110` in the fuzz corpus is empty); each real capture becomes a
byte-exactness oracle seed and may exercise `JudgeToggleBit` (V3).

---

## V3 — Toggle-bit / repeat-key behaviour (110B)

**Why:** `JudgeToggleBit`/`changetogglebit` (per-protocol toggle-position lookup against
`.rodata` tables) are **stubbed**. Basic toggle alternation still happens
(`send_remote_study_data` flips `g_toggle_bit` + XORs 0x10 each call), but protocols needing
position-specific toggle bits (some NEC-extended, RC5/RC6, some AC) may misbehave on 2nd press.

**Steps:** with a working 110B key from V2, press **Transmit frame** 4–5 times in a row on the
same key.

**Pass:** device reacts on every press (e.g. power toggles each time, or vol increments each
time). **Fail (reacts every-other-press or stops)** → that protocol needs JudgeToggleBit; paste
me the remote/model and I'll port it. *(2026-07-30: all 8 toggle-signature records are now
parsed into `re/toggle_records.json` with the match algorithm sketched in REVERSE_ENGINEERING.md
§9.1 — the port is well-specified; the raw capture hex you paste also serves as the oracle
seed that lets me validate the port byte-exact on-device.)*

---

## V4 — Universal TX from open IR files (Flipper/LIRC/Pronto)

**Why:** `BigmeFrameEncoder` (mark/space timings → Bigme `0x03`/`0x02` frame) has
**UNVALIDATED header fields and `unitUs`** (carrier time quantum). One good 230B capture from
V1 lets us derive `unitUs` precisely.

**Steps:**
1. Import a Flipper `.ir` or LIRC `.conf` for the *same* device you captured in V1 (Home →
   Import). Pick a signal you also have on a physical remote.
2. Transmit it. Observe.

**Pass:** device reacts. **Fail:** paste me (a) the V1 raw 230B hex (calibration source), (b) the
imported signal's frequency + first ~20 timings, (c) the emitted frame hex. I'll solve for
`unitUs` and fix the header from the V1 capture.

---

## V5 — Proprietary brand DB (optional, only if you load the .rodata)

**Why:** `BrandDbReader.searchKeyData` (per-type `*_info`/`*_table` schemas) is experimental.

**Steps:** Transport screen → load the proprietary `.rodata`; pick a brand/key; transmit.
**Pass/fail:** device reacts or not. Paste the device type, brand index, key code, and emitted
frame hex.

---

## What to paste back to me (template)

For each validation run, send:
```
Test: V1 / V2 / V3 / V4 / V5
Remote: <brand/model>   Key: <e.g. power>
Capture size: 230 or 110
Raw hex:        <…>
Frame hex:      <…>            (Conditioned / Unconditioned — say which)
Sanity gate:    PASS / FAIL    (110B only)
Device reacted: yes / no / every-other-press / partial
Notes:
```

With V1 passing and one good 230B raw hex in hand, V2/V3/V4 all become tractable from here.
