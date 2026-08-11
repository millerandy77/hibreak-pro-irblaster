package dev.ilyaask.openir.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Golden test: a real 230-byte AC capture run through the bundled vendor codec
 * (`libet_jni_ir_tools.so` on-device, tag OpenIR/NativeDiff, 2026-07-29) produced the
 * expected frame below. The clean-room codec must reproduce it byte-for-byte.
 * This locks in the sanitization + zero-run-patch + header-fixup rules permanently.
 */
class CleanIrCodec230GoldenTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val raw = hex(
        "00ff0000352c000c005c00090049001b00120022004200230065" +
            "1122008100a60022001300012233333332223333333333333333" +
            "3333333333333333333333333334323233235622377000000000" +
            "0000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000002222ea"
    )

    private val vendorFrame = hex(
        "3003fefe00352c000c005c00090049001b001200220042002300" +
            "651122008100a600220013000122333333322233333333333333" +
            "333333333333333333333333333334323233235622377fff0000" +
            "0000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000" +
            "00000000000000000000000000000000000000002222ea7a"
    )

    @Test fun cleanCodecMatchesVendorByteForByte() {
        assertEquals(230, raw.size)
        assertEquals(232, vendorFrame.size)
        val frame = CleanIrCodec().studyKeyCode(raw.copyOf())
        assertNotNull(frame)
        assertEquals(
            "clean frame must equal vendor frame byte-for-byte",
            vendorFrame.toList(),
            frame!!.toList(),
        )
    }
}
