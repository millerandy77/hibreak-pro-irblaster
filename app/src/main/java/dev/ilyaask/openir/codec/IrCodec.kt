package dev.ilyaask.openir.codec

/**
 * IR codec — mirrors the 8 JNI methods of the original `et.song.jni.ir.ETIR`.
 * This is the clean-room target: produce the same device frames the original native lib did,
 * without using any proprietary binary.
 *
 * Status: `StudyKeyCode` (learned-code encoder) is the first RE target. `SearchKeyData`
 * (prebuilt brand DB) depends on the asset DB format and is a later phase — see
 * docs/REVERSE_ENGINEERING.md §5 and the project README.
 */
interface IrCodec {
    /** Build a transmit frame for a prebuilt (deviceType, brandIndex, keyCode). */
    fun searchKeyData(deviceType: Int, brandIndex: Int, keyCode: Int): ByteArray?

    /** Encode captured raw timing bytes into a transmit frame (learning replay). */
    fun studyKeyCode(raw: ByteArray): ByteArray?

    /** AC inter-command delay (ms) for a brand, used by the AC state machine. */
    fun airDelay(deviceType: Int, brandIndex: Int): Int = 200

    /** Brand catalogue for a device class (indices). Empty until the DB reader lands. */
    fun brands(deviceType: Int): List<BrandInfo> = emptyList()
}

data class BrandInfo(val index: Int, val name: String)
