package dev.ilyaask.openir.codec

import android.util.Log

/**
 * Encodes an [IrSignal] (open-format IR: Flipper/LIRC/Pronto/raw, or a learned capture) into a
 * Bigme Hibreak Pro **device frame** for transmission over the `io` transport (`/dev/hxd_irda`).
 *
 * Wire format (recovered from `libet_jni_ir_tools.so`; matches the learned-code 230-byte frame,
 * which is the confirmed general "transmit these timings" path):
 *
 *   frame[0]      = 0x30            (sync)
 *   frame[1]      = 0x03            (subcommand: 0x03 = transmit timing payload)
 *   frame[2..230] = payload (228 bytes)
 *   frame[231]    = (0x33 + sum(payload)) & 0xFF
 *
 * Payload body encoding (recovered from `Modifywave`/`getfigure`, the 0x80-overflow scheme):
 *   - a timing T in device units is emitted as a single byte if T <= 0x7F,
 *     else as two bytes: [0x80 | ((T >> 8) & 0x7F), T & 0xFF]  (max 0x7FFF = 32767 units).
 *   - mark/space samples alternate, starting with a mark.
 *
 * ── VALIDATION-DEPENDENT (the only unknowns) ──
 *   1. [unitUs]: microseconds per device unit. Open formats give timings in us; the device
 *      frame uses its own unit. A real learned capture reveals the scale (e.g. if a 9000 us
 *      header mark is stored as 0x23 0x28 = 9000, then unitUs == 1). Default 1 (assume
 *      device unit == us) — UNVALIDATED; adjust after one real capture.
 *   2. Header bytes (payload[0..7]-ish): the learned frame's header is produced by the device
 *      capture + `compdata`/`getfigure` (carrier, sample count, repeat). For an open-format
 *      signal we construct a best-guess header here. The authoritative layout comes from the
 *      proprietary brand-DB `*_info` records (see `BrandDbReader`), which store known-good
 *      headers per code. Until that's decoded + validated, treat [encode] as experimental:
 *      it builds a syntactically-valid frame but the device may not accept the header.
 *
 * The body encoding and frame wrapper are exact; only [unitUs] and the header semantics need
 * on-device confirmation.
 */
object BigmeFrameEncoder {
    private const val TAG = "OpenIR/Encoder"
    const val FRAME_LEN = 232
    const val PAYLOAD_LEN = 228
    private const val SYNC: Byte = 0x30
    private const val SUBCMD_TIMING: Byte = 0x03

    /** Default us-per-device-unit (UNVALIDATED — see class kdoc). */
    const val DEFAULT_UNIT_US = 1

    /**
     * Encode [signal] into a 232-byte device frame. Returns null if the timings don't fit the
     * 228-byte payload (after overflow expansion) — caller should then split into repeats.
     */
    fun encode(signal: IrSignal, unitUs: Int = DEFAULT_UNIT_US): ByteArray? {
        val units = signal.timings.map { (it / unitUs).coerceIn(0, 0x7FFF) }
        val body = ArrayList<Byte>(8 + units.size * 2)
        // Best-guess header (UNVALIDATED) — placeholder values within the sanitized ranges
        // observed in the learned-code path. BrandDbReader will supply the real layout.
        body.add(0x00)            // payload[0]
        body.add(0x00.toByte())   // payload[1] (capture marker — left 0 for synthetic)
        body.add(0xfe.toByte())   // payload[2]: sanitizer forces 0xfe when small
        body.add(0x00.toByte())   // payload[3]
        body.add(0x34.toByte())   // payload[4]: sanitizer forces 0x34 when 0
        // payload[5..6]: carrier hint (unused placeholder)
        body.add(((signal.frequencyHz / 1000) and 0xFF).toByte())
        body.add(0x00.toByte())
        body.add(0x15.toByte())   // payload[7]: sanitizer forces 0x15
        // body: overflow-encoded mark/space samples
        for (u in units) body.addAll(encodeUnit(u))
        if (body.size > PAYLOAD_LEN) {
            Log.w(TAG, "encode: payload overflow (${body.size} > $PAYLOAD_LEN); needs splitting")
            return null
        }
        val frame = ByteArray(FRAME_LEN)
        frame[0] = SYNC
        frame[1] = SUBCMD_TIMING
        var sum = 0x33
        for (i in body.indices) {
            val v = body[i]
            frame[2 + i] = v
            sum = (sum + (v.toInt() and 0xFF)) and 0xFF
        }
        // pad remaining payload with 0xFF (matches sanitizer's saturation value)
        for (i in body.size until PAYLOAD_LEN) {
            frame[2 + i] = 0xFF.toByte()
            sum = (sum + 0xFF) and 0xFF
        }
        frame[FRAME_LEN - 1] = sum.toByte()
        return frame
    }

    /** Decode a 232-byte timing frame back to device-unit timings (for verification/debug). */
    fun decode(frame: ByteArray, skipHeader: Int = 8): List<Int> {
        if (frame.size != FRAME_LEN || frame[0] != SYNC) return emptyList()
        val out = ArrayList<Int>()
        var i = 2 + skipHeader
        val end = FRAME_LEN - 1
        while (i < end) {
            val b = frame[i].toInt() and 0xFF
            if (b == 0xFF) break // padding
            if (b and 0x80 != 0) {
                val lo = frame.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: break
                out.add(((b and 0x7F) shl 8) or lo); i += 2
            } else {
                out.add(b); i += 1
            }
        }
        return out
    }

    private fun encodeUnit(u: Int): List<Byte> =
        if (u <= 0x7F) listOf(u.toByte())
        else listOf((0x80 or ((u shr 8) and 0x7F)).toByte(), (u and 0xFF).toByte())
}
