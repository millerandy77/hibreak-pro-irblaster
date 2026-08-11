package dev.ilyaask.openir.codec

/**
 * Clean-room port of the Bigme 110-byte learned-code DSP pipeline, transcribed from
 * `libet_jni_ir_tools.so`:
 *
 *   learn_data_in_out (0x3e78) -> get_remote_study_data (0x3f98) -> compdata (0x40e0)
 *     -> Modifywave (0x52bc) / modifywavem708 (0x5508) / delfeng (0x53d8) / getfigure (0x565c)
 *   -> send_remote_study_data (0x400c) -> [0x30,0x02,payload,ck@111] frame
 *   -> keytogglebit (0x4130) -> JudgeToggleBit (0x416c) / changetogglebit (0x4898)
 *
 * Word model: the 110 capture bytes are expanded into a 110-word IntArray (each byte → u32).
 * A word with bit 7 (0x80) set is a 2-slot overflow value: `((w & 0x7f) << 8) + nextWord`
 * (max 0x7FFF). Mark/space samples alternate. Header words `w[5]`/`w[6]` carry carrier/figure
 * state; `getfigure` writes `w[1]`/`w[2]`/`w[3]`.
 *
 * STATUS — VALIDATED byte-exact against the vendor `.so` via on-device oracle fuzzing
 * (shard-per-case instrumented runner, fresh process per case): every wave body
 * (Modifywave/delfeng/modifywavem708/getfigure/judgesame/cmpdata), the wrapper, expansion,
 * dispatcher, toggle-alternation, checksum and the learn_data_in_out sanity gate agree
 * byte-for-byte across the whole structured+random corpus.
 *
 * JudgeToggleBit (stateful per-protocol toggle matcher over the code_format/ToggleBit_Place/
 * STandantdata/cntx/cnty globals) is stubbed to 0: basic toggle alternation still works
 * (send_remote_study_data flips g_toggle_bit and XORs 0x10 each call), but protocols needing
 * position-specific toggle bits are not yet handled. No corpus input (nor any real capture we
 * have) matches a toggle-protocol signature, so this path is unreachable-unvalidated.
 *
 * Robustness: all array access is bounds-safe ([get]/[set]); a malformed/edge capture degrades
 * to a `null` frame rather than throwing. This file has no Android dependency so it runs in a
 * plain JVM unit test.
 */
internal class Dsp110 {
    companion object {
        private const val N = 110
        private const val FRAME = 112

        /** Optional non-Android sink for diagnostic warnings (set by the app; null in JVM tests). */
        @Volatile
        internal var onWarn: ((String) -> Unit)? = null
    }

    /**
     * Persistent toggle bit across calls (mirrors the native global `g_toggle_bit`).
     * Per-instance (not a process global) so independent codec users — e.g. the TX path
     * vs the on-screen native-vs-clean diff — don't advance each other's toggle phase.
     */
    private var toggleBit: Int = 0

    private fun warn(msg: String) { onWarn?.invoke(msg) }

    /**
     * Full 110-byte pipeline. Always returns a 112-byte frame, mirroring the native
     * `learn_data_in_out` + JNI wrapper: on rejection (empty capture `buf[1]==0`, or the
     * assembled frame failing the sanity gate) the native zero-fills the output buffer
     * and returns 112 zero bytes — VERIFIED by on-device oracle fuzzing (all 13 reject
     * cases returned all-zero frames; gate agreement was 26/26).
     */
    fun process(buf: ByteArray): ByteArray {
        if (buf.size != N) return ByteArray(0) // native JNI: NewByteArray(0) for unknown lengths
        val frame = buildFrame(buf)
        if (frame == null || !validate(frame)) {
            return ByteArray(FRAME) // native rejection path: zero-filled 112-byte array
        }
        return frame
    }

    /**
     * Expand + condition + wrap, without the [learn_data_in_out] sanity gate. Returns the
     * 112-byte frame, or null if the capture is empty (`buf[1]==0`) / wrong size / the DSP
     * throws. Exposed for unit tests so the wrapper + checksum can be verified deterministically
     * without depending on the gate outcome.
     */
    internal fun buildFrame(buf: ByteArray): ByteArray? {
        if (buf.size != N) return null
        if ((buf[1].toInt() and 0xFF) == 0) return null // learn_data_in_out: buf[1]==0 -> zero & return

        val w = IntArray(N) { buf[it].toInt() and 0xFF } // get_remote_study_data expand
        val len = Len(110)
        val r = try { compdata(w, len) } catch (t: Throwable) { warn("compdata threw: $t"); 1 }
        // get_remote_study_data: on compdata success, copy w[1..109] low bytes back to buf[0..108]
        if (r == 0) {
            for (i in 0 until N - 1) buf[i] = (w[i + 1] and 0xFF).toByte()
        }
        // learn_data_in_out: memcpy local[109] = buf[0..108]; send_remote_study_data(local, 109)
        val local = ByteArray(N - 1) { buf[it] }
        return sendRemoteStudyData(local, N - 1)
    }

    /** learn_data_in_out's acceptance gate: buf[3]>=5, 16<=buf[5]<129, sum(buf[10..14])&0xff != 0. */
    internal fun validate(frame: ByteArray): Boolean {
        if (frame.size != FRAME) return false
        val b3 = frame[3].toInt() and 0xFF
        val b5 = frame[5].toInt() and 0xFF
        if (b3 < 5) return false
        if (b5 < 16 || b5 >= 129) return false
        var s = 0
        for (i in 10..14) s = (s + (frame[i].toInt() and 0xFF)) and 0xFF
        return s != 0
    }

    // ---- compdata (0x40e0) -------------------------------------------------
    private fun compdata(w: IntArray, len: Len): Int {
        len.v -= 1
        if (w[0] != 0) return 1
        val r: Int = if (w[1] >= 128) {
            val mr = modifyWave(w, len)
            delfeng(w, len)
            mr
        } else {
            modifyWaveM708(w, len)
        }
        if (r != 0) return 1
        getfigure(w, len)
        return 0
    }

    // ---- send_remote_study_data (0x400c) -----------------------------------
    private fun sendRemoteStudyData(local: ByteArray, len: Int): ByteArray {
        val w = IntArray(N) { if (it < len) local[it].toInt() and 0xFF else 0 }
        // toggle the global; if previous was 0, XOR 0x10 into w[0]
        val prev = toggleBit and 1
        toggleBit = prev xor 1
        if (prev == 0) w[0] = w[0] xor 0x10
        keyToggleBit(w, len)
        val frame = ByteArray(FRAME)
        frame[0] = 0x30
        frame[1] = 0x02
        var sum = 0x32
        for (i in 0 until len) {
            frame[2 + i] = (w[i] and 0xFF).toByte()
            sum = (sum + (w[i] and 0xFF)) and 0xFF
        }
        frame[FRAME - 1] = sum.toByte()
        return frame
    }

    // ---- keytogglebit (0x4130) ---------------------------------------------
    private fun keyToggleBit(w: IntArray, len: Int) {
        if (w[0] and 7 == 0) return
        w[0] = w[0] xor 0x10
        val pos = judgeToggleBit(w) // stubbed: returns 0 -> no changetogglebit
        if (pos != 0) changeToggleBit(w, len, pos)
    }

    /** STUB: per-protocol toggle-position lookup against .rodata tables — not yet ported. */
    private fun judgeToggleBit(w: IntArray): Int = 0
    private fun changeToggleBit(w: IntArray, len: Int, pos: Int) { /* not reached while stubbed */ }

    // ---- Overflow read/write helpers (bounds-safe) -------------------------
    private fun get(w: IntArray, i: Int): Int = if (i in 0 until w.size) w[i] else 0
    private fun set(w: IntArray, i: Int, v: Int) { if (i in 0 until w.size) w[i] = v }

    // ---- Modifywave (0x52bc) — exact port ----------------------------------
    /**
     * Walks the waveform from w[5], overflow-decoding (2-byte form only), adjusting even
     * samples -7 (min 2) / odd samples +3, and re-encoding with the 0x80 two-byte form.
     * A too-short capture skips the loop but STILL runs the header fixup (0x52de bcc 536e).
     */
    private fun modifyWave(w: IntArray, len: Len): Int {
        if (w[0] != 0) return 1
        if (len.v + 1 >= 6) {
            val count = w[2]
            var i = 5
            var par = 0
            var out = 5
            while (true) {
                val v = get(w, i)
                val combined: Int
                if (v >= 128) {
                    val high = v and 0x7F
                    i += 1
                    val low: Int
                    if (i != len.v + 1) {
                        low = get(w, i)
                    } else {
                        i = len.v + 1 // native clamps the read index at end of buffer
                        low = 0
                    }
                    combined = (high shl 8) + low
                } else {
                    combined = v
                }
                val adj = if (par and 1 == 0) {
                    if (combined > 7) combined - 7 else 2
                } else {
                    combined + 3
                }
                val low = adj and 0xFF
                if (adj + 0xFF > 0x1FE || low >= 0x80) {
                    set(w, out, 0x80 or (adj shr 8)) // native: no 0x7f mask on the high byte
                    out += 1
                }
                set(w, out, low)
                par += 1
                if (par >= count) break
                i += 1
                out += 1
                if (i >= len.v + 1) break
            }
        }
        modifyWaveHeaderFixup(w)
        return 0
    }

    private fun modifyWaveHeaderFixup(w: IntArray) {
        val a = get(w, 5)
        val idx: Int
        if (a >= 128) {
            val c = get(w, 6)
            if (c >= 253) {
                set(w, 6, c - 253)
                set(w, 5, a + 1)
            } else {
                set(w, 6, c + 3) // w[5] untouched in this branch
            }
            idx = 7
        } else {
            set(w, 5, if (a < 126) a + 2 else 0x7F)
            idx = 6
        }
        val t = get(w, idx)
        if (t >= 128) {
            val nxt = get(w, idx + 1)
            if (nxt != 0) {
                set(w, idx + 1, nxt - 1)
            } else {
                set(w, idx + 1, 0xFF)
                set(w, idx, 0x80 or ((t and 0x7F) - 1)) // native orr: (t&0x7f)-1 may be -1
            }
        } else {
            if (t <= 1) set(w, idx, 2)
        }
    }

    // ---- delfeng (0x53d8) — exact port --------------------------------------
    /**
     * "Delete peak": compacts the waveform in place from w[5], folding tiny (<= 4) odd-parity
     * samples into their neighbours. Native quirks reproduced faithfully:
     *  - `prev` (sp[0]) is vestigial — written once to 0, never updated — so the merge
     *    condition is just `value <= 4 && (count & 1) == 1`.
     *  - The "next" sample is read at the CURRENT read position (sp[40]), so a merged 1-byte
     *    sample is summed twice (`prev + 2*spike`) and the following slot is skipped.
     *  - `count` advances by 2 per merge and the running loop limit (sp[36], initially w[2])
     *    shrinks by 2 per merge persistently.
     *  - The merge-test `value` for a 2-byte sample is its LOW byte, not the decoded value.
     */
    private fun delfeng(w: IntArray, len: Len) {
        if (len.v + 1 < 6) return
        var totalStored = w[2]
        var i = 5
        var count = 0
        var out = 5
        var acc = 0
        while (true) {
            val cur = get(w, i)
            set(w, out, cur) // compaction copy happens before the merge decision
            var nextOut = out + 1
            val readPos: Int
            val value: Int
            val highPart: Int
            if (cur >= 128) {
                highPart = cur and 0x7F
                readPos = i + 1
                value = if (i == len.v) 0 else get(w, i + 1)
                set(w, out + 1, value)
                nextOut = out + 2
            } else {
                value = cur
                highPart = 0
                readPos = i
            }
            val limit: Int
            if (value > 4 || count and 1 == 0) { // KEEP
                acc = highPart
                out = nextOut
                i = readPos
                limit = totalStored
            } else { // MERGE
                val pos2 = nextOut - if (acc > 127) 2 else 1
                val pos1 = pos2 - 1
                val pw = get(w, pos1)
                val prevHigh: Int
                if (pw >= 128) {
                    prevHigh = pw and 0x7F
                    acc = get(w, pos2)
                } else {
                    acc = pw
                    prevHigh = 0
                }
                val nx = get(w, readPos)
                val nextLow: Int
                val nextHigh: Int
                val readPosNext: Int
                if (nx >= 128) {
                    nextHigh = nx and 0x7F
                    nextLow = get(w, readPos + 1)
                    readPosNext = readPos + 2
                } else {
                    nextLow = nx
                    nextHigh = 0
                    readPosNext = readPos + 1
                }
                val sum = acc + value + nextLow + ((prevHigh + nextHigh) shl 8)
                val hi = sum shr 8 // sum is always >= 0
                val lo = sum and 0xFF
                val writePos: Int
                if (hi >= 128) {
                    val hiEnc = 0x80 or hi
                    acc = hiEnc
                    set(w, pos1, hiEnc)
                    writePos = pos2
                } else {
                    acc = hi
                    writePos = pos1
                }
                set(w, writePos, lo)
                count += 1
                totalStored -= 2
                limit = totalStored
                out = writePos + 1
                i = readPosNext
            }
            i += 1
            count += 1
            if (Integer.compareUnsigned(count, limit) >= 0) return
            if (Integer.compareUnsigned(i, len.v + 1) >= 0) return
        }
    }

    // ---- modifywavem708 (0x5508) — UNVALIDATED transcription ---------------
    /**
     * modifywavem708 (0x5508) — exact port. Walks the waveform from w[5], overflow-decoding
     * (1/2/3-byte forms), quantising (v+2)/16, alternating even/odd adjustments with `prev`,
     * and re-encoding with the 0x80 two-byte form. Returns 1 (reject) when an even-index
     * quantised value exceeds 128 — the native compdata passthrough trigger.
     */
    private fun modifyWaveM708(w: IntArray, len: Len): Int {
        if (w[0] != 0) return 1
        if (len.v + 1 < 6) return 0
        val total = w[2]
        var i = 5
        var out = 5
        var count = 0
        var prev = 0
        while (true) {
            val v = get(w, i)
            val combined: Int
            when {
                v and 0xF0 == 0xF0 -> { // 3-byte form: (v&0x0f)<<16 | w[i+1]<<8 | w[i+2]
                    combined = ((v and 0x0F) shl 16) + (get(w, i + 1) shl 8) + get(w, i + 2)
                    i += 2
                }
                v >= 128 -> { // 2-byte form: (v&0x7f)<<8 | w[i+1]
                    combined = ((v and 0x7F) shl 8) + get(w, i + 1)
                    i += 1
                }
                else -> combined = v
            }
            val val16 = (combined + 2) shr 4 // arithmetic /16
            val outv: Int
            if (count and 1 == 0) {
                outv = if (combined > 4093) val16 - 1 else val16
                if (outv > 128) return 1 // native reject -> compdata passthrough
            } else {
                val d = val16 - prev
                outv = if (d >= 3) d - 2 else 1
            }
            prev = outv
            val lo = outv and 0xFF
            if (outv + 0xFF > 0x1FE || lo >= 0x80) {
                set(w, out, 0x80 or (outv shr 8))
                out += 1
            }
            set(w, out, lo)
            out += 1
            count += 1
            if (count >= total) break
            i += 1
            if (i >= len.v + 1) break
        }
        modifyWaveM708HeaderFixup(w)
        return 0
    }

    private fun modifyWaveM708HeaderFixup(w: IntArray) {
        val a = w[5]
        val idx: Int
        if (a and 0x80 != 0) {
            val c = w[6]
            if (c >= 5) {
                w[6] = c - 5 // w[5] untouched in this branch
            } else {
                w[5] = 0x80 or ((a and 0x7F) - 1)
                w[6] = c + 251
            }
            idx = 7
        } else {
            w[5] = if (a > 3) a - 3 else 1
            idx = 6
        }
        val t = get(w, idx)
        if (t and 0x80 != 0) {
            val nxt = get(w, idx + 1)
            if (nxt <= 251) {
                set(w, idx + 1, nxt + 4) // w[idx] untouched
            } else {
                set(w, idx + 1, nxt - 252)
                set(w, idx, t + 1)
            }
        } else {
            set(w, idx, if (t < 124) t + 3 else 0x7F)
        }
    }

    // ---- getfigure (0x565c) + judgesame (0x5884) + cmpdata (0x58f8) — exact ports -----

    /**
     * getfigure: classify the conditioned waveform. Finds the max overflow-decoded sample
     * over w[5..len-1], collects the first 3 peak positions (value >= max*7/8), then decides
     * a figure class from the peak spacing. Writes:
     *   w[1] = (w[1] & 0x88) | figureClass
     *   w[2] = ordA + (ordA & 1)
     *   w[3] = ordB + (ordB & 1)
     */
    private fun getfigure(w: IntArray, len: Len): Int {
        if (w[0] != 0) return 1
        val hitsPos = IntArray(3)
        val hitsOrd = IntArray(3)
        if (len.v >= 6) {
            var max = 0
            var i = 5
            while (i < len.v) {
                var v = get(w, i)
                if (v >= 128) { v = ((v and 0x7F) shl 8) + get(w, i + 1); i += 1 }
                if (max < v) max = v
                i += 1
            }
            val rem = max - (max ushr 3)
            var hits = 0
            var j = 0
            i = 5
            while (i < len.v) {
                var v = get(w, i)
                var ni = i
                if (v >= 128) { v = ((v and 0x7F) shl 8) + get(w, i + 1); ni += 1 }
                if (rem <= v && v <= max) {
                    hitsPos[hits] = i
                    hitsOrd[hits] = j
                    hits += 1
                    if (hits > 2) break
                }
                j += 1
                i = ni + 1
            }
        }
        val w1 = get(w, 1)
        val w2 = get(w, 2)
        val fig: Int
        var ordA: Int
        var ordB: Int = hitsOrd[1]
        val classifyDiff = w1 and 0x40 == 0 && ordB != 0 ||
            w1 and 0x40 != 0 && ordB != 0 && get(w, hitsPos[1]) >= 136
        if (!classifyDiff) {
            if (w1 and 0x40 == 0) {
                fig = 1
                ordA = hitsOrd[0]
                if (ordA == 0) { ordA = w2; ordB = w2 } else ordB = ordA
            } else {
                fig = 0
                ordA = w2; ordB = w2
            }
        } else {
            ordA = hitsOrd[0]
            val gap = ordB - ordA
            if (gap <= 1) {
                fig = 1
                if (ordA == 0) { ordA = w2; ordB = w2 } else ordB = ordA
            } else if (gap >= ordA) {
                // period check skipping PAST the hit words (0x57ea path)
                val carry = w1 and 0x80
                val saveW2 = w2
                var p0 = hitsPos[0]; if (get(w, p0) > 127) p0 += 2 else p0 += 1
                if (judgesame(w, 5, p0, ordA - 2, carry) == 0) {
                    fig = 1
                    if (ordA == 0) { ordA = saveW2; ordB = saveW2 } else ordB = ordA
                } else {
                    var p1 = hitsPos[1]; if (get(w, p1) > 127) p1 += 2 else p1 += 1
                    fig = if (judgesame(w, 5, p1, ordA - 2, carry) == 0) 3 else 2
                }
            } else if (gap >= ordA ushr 1) {
                fig = 2
            } else {
                // period check pointing AT the hit word (or its low byte) (0x575e path)
                val carry = w1 and 0x80
                var p0 = hitsPos[0]; if (get(w, p0) > 127) p0 += 1
                if (judgesame(w, 5, p0, ordA - 2, carry) == 0) {
                    fig = 1
                    ordB = ordA
                } else {
                    var p1 = hitsPos[1]; if (get(w, p1) > 127) p1 += 1
                    fig = if (judgesame(w, 5, p1, ordA - 2, carry) == 0) 3 else 2
                }
            }
        }
        set(w, 1, (w1 and 0x88) or fig)
        set(w, 2, ordA + (ordA and 1))
        set(w, 3, ordB + (ordB and 1))
        return 0
    }

    /**
     * judgesame: compare two overflow-decoded waveform cursors pairwise; returns 1 on the
     * first pair that differs (per [cmpdata]), 0 when [count] pairs all match. A negative
     * [count] is native UB (unsigned compare) — here it means "until first diff", bounded
     * for memory safety; real signals diff quickly.
     */
    private fun judgesame(w: IntArray, baseIdx: Int, ptrIdx: Int, count: Int, carry: Int): Int {
        if (count == 0) return 0
        val limit = if (count < 0) N * 4 else count
        var i = baseIdx
        var j = ptrIdx
        var k = 0
        while (true) {
            var va = get(w, i)
            if (va >= 128) { va = ((va and 0x7F) shl 8) + get(w, i + 1); i += 1 }
            i += 1
            var vb = get(w, j)
            if (vb >= 128) { vb = ((vb and 0x7F) shl 8) + get(w, j + 1); j += 1 }
            j += 1
            if (cmpdata(va, vb, carry) != 0) return 1
            k += 1
            if (k >= limit) return 0
        }
    }

    /** cmpdata: tolerance comparator (0 = same-enough, 1 = different). */
    private fun cmpdata(a: Int, b: Int, carry: Int): Int {
        if (a >= 32) {
            val diff = if (a < b) b - a else a - b
            return if ((a ushr 3) < diff) 1 else 0
        }
        if (b > 128) return 1
        val diff = if (a < b) b - a else a - b
        val mn = minOf(a, b)
        val mx = maxOf(a, b) ushr 2
        if (mn > 15) return if (diff >= mx) 1 else 0
        if (carry != 0) return if (diff <= 4) 0 else 1
        if (mn > 4) return if (diff >= mx) 1 else 0
        return if (mn >= mx * 2) 0 else 1
    }

    private class Len(@JvmField var v: Int)
}
