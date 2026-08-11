package dev.ilyaask.openir.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural-invariant tests for the 110-byte DSP port. These do NOT verify the wave-body
 * correctness (that needs a real capture for ground truth) — they guarantee the pipeline never
 * crashes on edge inputs and that the frame wrapper / checksum / sanity gate behave correctly.
 */
class Dsp110Test {
    private val dsp = Dsp110()


    private fun newBuf(): ByteArray = ByteArray(110)

    /** Make a buffer that passes the `buf[1] != 0` entry guard and the post sanity gate. */
    private fun plausibleBuf(): ByteArray {
        val b = newBuf()
        b[1] = 0x40                       // sample count < 128 -> modifywavem708 path
        b[2] = 8                          // small sample count
        b[3] = 6                          // >= 5
        b[5] = 32                         // in [16, 129)
        // bytes 10..14 must sum (mod 256) != 0
        b[10] = 1; b[11] = 1; b[12] = 1; b[13] = 1; b[14] = 1
        // a couple of timing bytes
        for (i in 5..40) b[i] = (i and 0x7F).toByte()
        return b
    }

    private fun checksum(frame: ByteArray): Int {
        var s = 0x32
        for (i in 0 until 109) s = (s + (frame[2 + i].toInt() and 0xFF)) and 0xFF
        return s
    }

    @Test fun wrongSizeReturnsEmpty() {
        // native JNI returns NewByteArray(0) for unknown lengths
        assertEquals(0, dsp.process(ByteArray(109)).size)
        assertEquals(0, dsp.process(ByteArray(111)).size)
        assertEquals(0, dsp.process(ByteArray(0)).size)
    }

    @Test fun emptyCaptureReturnsZeroedFrame() {
        // buf[1] == 0 -> learn_data_in_out zero-fills -> 112 zero bytes (vendor-verified)
        val frame = dsp.process(newBuf())
        assertEquals(112, frame.size)
        assertTrue(frame.all { it == 0.toByte() })
    }

    @Test fun rejectedCaptureReturnsZeroedFrame() {
        // all-0xff fails the sanity gate -> zero-filled 112-byte frame (vendor-verified)
        val frame = dsp.process(ByteArray(110) { 0xFF.toByte() })
        assertEquals(112, frame.size)
        assertTrue(frame.all { it == 0.toByte() })
    }

    @Test fun producesValidFrameShape() {
        // buildFrame skips the (UNVALIDATED) sanity gate so the wrapper + checksum can be
        // verified deterministically regardless of the conditioned output.
        val frame = dsp.buildFrame(plausibleBuf())
        assertNotNull(frame)
        frame!!
        assertEquals(112, frame.size)
        assertEquals(0x30, frame[0].toInt() and 0xFF)
        assertEquals(0x02, frame[1].toInt() and 0xFF)
        // checksum at index 111 == (0x32 + sum(payload[0..108])) & 0xFF
        assertEquals(checksum(frame), frame[111].toInt() and 0xFF)
    }

    @Test fun doesNotCrashOnAllZeroExcept1() {
        val b = newBuf(); b[1] = 1
        // may return null (sanity gate) but must not throw
        dsp.process(b)
    }

    @Test fun doesNotCrashOnAllFF() {
        val b = ByteArray(110) { 0xFF.toByte() }
        dsp.process(b)
    }

    @Test fun doesNotCrashOnHighSampleCountPath() {
        // buf[1] >= 128 -> Modifywave + delfeng path
        val b = plausibleBuf()
        b[1] = 0xC0.toByte()
        dsp.process(b)
    }

    @Test fun doesNotCrashOnLargeCount() {
        val b = plausibleBuf()
        b[2] = 0xFF.toByte() // huge sample count
        dsp.process(b)
    }

    @Test fun doesNotCrashOnRandomishInputs() {
        val rng = java.util.Random(0xC0FFEEL)
        repeat(200) {
            val b = ByteArray(110)
            for (i in b.indices) b[i] = (rng.nextInt() and 0xFF).toByte()
            dsp.process(b) // must never throw
        }
    }

    @Test fun checksumIsSeed0x32Not0x33() {
        val frame = dsp.buildFrame(plausibleBuf())!!
        // independent recompute with seed 0x32
        var s = 0x32
        for (i in 0 until 109) s = (s + (frame[2 + i].toInt() and 0xFF)) and 0xFF
        assertEquals(s, frame[111].toInt() and 0xFF)
        assertTrue("checksum should differ from a 0x33-seed computation", run {
            var s33 = 0x33
            for (i in 0 until 109) s33 = (s33 + (frame[2 + i].toInt() and 0xFF)) and 0xFF
            s33 != (frame[111].toInt() and 0xFF)
        })
    }

    @Test fun validateAcceptsWellFormedFrame() {
        val f = ByteArray(112)
        f[3] = 6
        f[5] = 32
        f[10] = 1; f[11] = 1; f[12] = 1; f[13] = 1; f[14] = 1
        assertTrue(dsp.validate(f))
    }

    @Test fun validateRejectsBadFrames() {
        // b3 < 5
        val a = ByteArray(112).also { it[5] = 32; it[10] = 1 }
        assertTrue(!dsp.validate(a))
        // b5 < 16
        val b = ByteArray(112).also { it[3] = 6; it[5] = 5; it[10] = 1 }
        assertTrue(!dsp.validate(b))
        // b5 >= 129
        val c = ByteArray(112).also { it[3] = 6; it[5] = 129.toByte(); it[10] = 1 }
        assertTrue(!dsp.validate(c))
        // sum(10..14) == 0
        val d = ByteArray(112).also { it[3] = 6; it[5] = 32 }
        assertTrue(!dsp.validate(d))
    }
}
