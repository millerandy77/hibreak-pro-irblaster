package dev.ilyaask.openir.codec

import android.util.Log

/**
 * Clean-room IR codec — learned-code path recovered from `libet_jni_ir_tools.so`.
 *
 * `studyKeyCode` is reimplemented from the Thumb disassembly of
 * `Java_et_song_jni_ir_ETIR_StudyKeyCode` (0x29e8):
 *
 *  - 230-byte capture -> 232-byte frame:
 *      frame[0]    = 0x30
 *      frame[1]    = 0x03
 *      frame[2..230] = sanitized raw[1..229]
 *      frame[231]  = (0x33 + sum(sanitized raw[1..229])) and 0xFF
 *    Sanitization (matches the loops at 0x2a28/0x2ad2 + fixups at 0x2b12 — VERIFIED against a
 *    real vendor frame from the bundled native codec; see Dsp110-style golden test):
 *      - for i in 37..226: if (raw[i] and 0x08) != 0 -> raw[i] or 0x0f;
 *        then if signed(raw[i]) < 0 -> 0xff   (raw[227] is NOT sanitized)
 *      - zero-run patch: first 9-byte all-zero window among [38..46],[76..84],[114..122],
 *        [152..160],[190..198] -> buf[base+38] = 0xff, buf[base+37] |= 0x0f if low nibble empty
 *      - if signed(raw[8]) >= 33 -> raw[8] = 1 and raw[7] = 0x15
 *      - if signed(raw[2]) <= 3 -> raw[2] = 0xfe
 *      - if signed(raw[1]) <= 4 -> raw[1] = 0xfe
 *      - if raw[4] == 0 -> raw[4] = 0x34
 *
 *  - 110-byte capture -> 112-byte frame via `learn_data_in_out` (0x3e78): the DSP pipeline
 *    (`compdata` / `Modifywave` / `delfeng` / `getfigure`) is ported in [Dsp110] — VALIDATED
 *    byte-exact against the vendor codec by on-device oracle fuzzing (see
 *    docs/REVERSE_ENGINEERING.md §9.1). Only JudgeToggleBit (stateful per-protocol toggle
 *    matcher) remains stubbed — unreachable by every capture seen so far.
 *
 *  - `searchKeyData` (brand DB -> frame): header assembly recovered; see [BrandDbReader].
 *
 * NOTE: the 230-byte payload sanitization is recovered statically; a couple of header fixups
 * depend on values parsed from capture structure markers and should be validated against a real
 * capture. The frame *wrapper* (header + copy + checksum) is exact.
 */
class CleanIrCodec : IrCodec {

    /** 110B DSP with per-codec toggle-bit state (flips once per encode, like the native lib). */
    private val dsp110 = Dsp110()

    init {
        // Route the (Android-free) DSP's diagnostics to logcat.
        Dsp110.onWarn = { msg -> Log.w("OpenIR/Dsp110", msg) }
    }

    override fun searchKeyData(deviceType: Int, brandIndex: Int, keyCode: Int): ByteArray? {
        Log.w(TAG, "searchKeyData: clean-room brand-DB encoder not yet implemented (RE phase 2)")
        return null
    }

    override fun studyKeyCode(raw: ByteArray): ByteArray? = studyKeyCodeDebug(raw)?.frame

    /** Debug view: returns the sanitized payload alongside the final frame for on-device diffing. */
    fun studyKeyCodeDebug(raw: ByteArray): StudyDebug? {
        if (raw.size == LEN_LONG) {
            val sanitized = sanitizeLong(raw)
            return StudyDebug(raw = raw, sanitized = sanitized, frame = buildLongFrame(sanitized))
        }
        if (raw.size == LEN_SHORT) {
            // 110-byte path: clean-room port of the compdata/Modifywave/delfeng/getfigure DSP
            // pipeline (docs §9.1) lives in [Dsp110]. Byte-exact vs the vendor codec across the
            // on-device fuzz corpus; rejections return a zeroed 112-byte frame like the native.
            val frame = dsp110.process(raw)
            return StudyDebug(raw = raw, sanitized = raw, frame = frame)
        }
        Log.w(TAG, "studyKeyCode: unexpected capture length ${raw.size}")
        return null
    }

    /**
     * Debug A/B view for the 110-byte path that bypasses the [Dsp110] sanity gate, so the
     * conditioned frame is always available for on-device transmit/compare even when the gate
     * (which validates the *conditioned* output) rejects it. Pair with [framePassesSanityGate]
     * to report gate status. The real replay path ([studyKeyCode]) still respects the gate.
     */
    fun studyKeyCodeDebugUngated110(raw: ByteArray): StudyDebug? {
        if (raw.size != LEN_SHORT) return null
        val frame = dsp110.buildFrame(raw) ?: return null
        return StudyDebug(raw = raw, sanitized = raw, frame = frame)
    }

    /** Whether a 112-byte frame passes the native `learn_data_in_out` sanity gate. */
    fun framePassesSanityGate(frame: ByteArray): Boolean = dsp110.validate(frame)

    /**
     * Experimental 110-byte frame for the debug screen ONLY: builds the 112-byte frame
     * `[0x30, 0x02, payload, …, checksum@111]` with the payload passed through UNCONDITIONED
     * (no compdata wave processing). Real `StudyKeyCode` for 110B returns null until the DSP
     * pipeline is implemented + validated. Exposed so the user can experimentally transmit and
     * observe whether the device accepts an unconditioned short capture.
     */
    fun experimentalShortFrame(raw: ByteArray): StudyDebug? {
        if (raw.size != LEN_SHORT) return null
        val frame = ByteArray(FRAME_SHORT)
        frame[0] = 0x30
        frame[1] = 0x02
        var sum = 0x32
        for (i in 0 until LEN_SHORT) {
            val v = raw[i]
            frame[2 + i] = v
            sum = (sum + (v.toInt() and 0xff)) and 0xff
        }
        frame[FRAME_SHORT - 1] = sum.toByte() // checksum at index 111
        return StudyDebug(raw = raw, sanitized = raw, frame = frame)
    }

    private fun sanitizeLong(raw: ByteArray): ByteArray {
        val b = raw.copyOf() // mutate a copy, never the caller's buffer

        // Payload sanitization over raw[37..226]. This is the exact net effect of the two
        // native loops (0x2a28..0x2a46 which early-exits after the first hit, +
        // 0x2ad2..0x2b10; the countdown runs r5=-37..-226 → index=-r5, so raw[227] is
        // NOT sanitized — verified instruction-level and by on-device fuzz vs vendor):
        // first (v & 0x08) -> v | 0x0f, then signed(v) < 0 -> 0xff.
        for (i in 37..226) {
            if (b[i].toInt() and 0x08 != 0) b[i] = (b[i].toInt() or 0x0f).toByte()
            if (b[i] < 0) b[i] = 0xff.toByte()
        }

        // Zero-run patch (0x2a76..0x2acc + 0x2bc8): a SLIDING 9-byte all-zero window
        // (buf[k+38..k+46] for k in 0..176 — the disasm increments the window by 1, not 38)
        // injects 0xff at the window start and fills the preceding byte's low nibble if
        // empty. First match wins; then the scan stops.
        for (k in 0..176) {
            if ((0 until 9).all { b[k + 38 + it] == 0.toByte() }) {
                if (b[k + 37].toInt() and 0x0f == 0) {
                    b[k + 37] = (b[k + 37].toInt() or 0x0f).toByte()
                }
                b[k + 38] = 0xff.toByte()
                break
            }
        }

        // Header fixups (0x2b12..0x2b46), conditions evaluated on the sanitized buffer.
        // Ground-truth verified: a capture with raw[7]=0xc7, raw[8]=0x00 keeps both.
        if (b[8].toInt() >= 33) { b[8] = 1; b[7] = 0x15 }
        if (b[2].toInt() <= 3) b[2] = 0xfe.toByte()
        if (b[1].toInt() <= 4) b[1] = 0xfe.toByte()
        if (b[4].toInt() == 0) b[4] = 0x34
        return b
    }

    private fun buildLongFrame(sanitized: ByteArray): ByteArray {
        val out = ByteArray(FRAME_LONG)
        out[0] = 0x30
        out[1] = 0x03
        var sum = 0x33
        for (i in 1 until LEN_LONG) { // sanitized[1..229] -> out[2..230]
            val v = sanitized[i]
            out[i + 1] = v
            sum = (sum + (v.toInt() and 0xff)) and 0xff
        }
        out[FRAME_LONG - 1] = sum.toByte()
        return out
    }

    /** Frame the device expects, given a sanitized 230-byte payload. Exposed for telemetry. */
    val frameLongLen: Int get() = FRAME_LONG

    companion object {
        private const val TAG = "OpenIR/CleanCodec"
        private const val LEN_LONG = 230
        private const val LEN_SHORT = 110
        private const val FRAME_LONG = 232
        private const val FRAME_SHORT = 112
    }
}

data class StudyDebug(val raw: ByteArray, val sanitized: ByteArray, val frame: ByteArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}
