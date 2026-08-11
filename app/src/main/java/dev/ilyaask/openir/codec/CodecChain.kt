package dev.ilyaask.openir.codec

/**
 * Codec dispatcher: picks the best available backend per call.
 *
 * Order of preference:
 *  1. [NativeIrCodec] — the user's own original `libet_jni_ir_tools.so` (exact behaviour) if it
 *     loaded. This is the only "known-good" encoder; everything below is clean-room/experimental.
 *  2. [BrandDbReader] — the opt-in proprietary `.rodata` pack (user-supplied). Provides brand
 *     names + an experimental clean-room `searchKeyData` (header assembly recovered; payload
 *     unvalidated — see [BrandDbReader]). `studyKeyCode` is not available here (learned-code
 *     encoding is the clean codec's job).
 *  3. [CleanIrCodec] — pure-Kotlin learned-code encoder (230-byte path recovered; 110-byte DSP
 *     not yet ported). `searchKeyData` returns null.
 *
 * This lets one [dev.ilyaask.openir.blaster.IrBlaster] transparently serve all three sources,
 * so the UI's brand browser and send path work whether the user supplied the native lib, the
 * `.rodata` pack, or nothing (open-IRDB / learned-only).
 */
class CodecChain(
    private val native: NativeIrCodec,
    private val db: BrandDbReader,
    private val clean: CleanIrCodec,
) : IrCodec {

    override fun searchKeyData(deviceType: Int, brandIndex: Int, keyCode: Int): ByteArray? =
        if (native.isLoaded) native.searchKeyData(deviceType, brandIndex, keyCode)
        else if (db.isLoaded()) db.searchKeyData(deviceType, brandIndex, keyCode)
        else clean.searchKeyData(deviceType, brandIndex, keyCode)

    override fun studyKeyCode(raw: ByteArray): ByteArray? =
        if (native.isLoaded) native.studyKeyCode(raw) else clean.studyKeyCode(raw)

    override fun airDelay(deviceType: Int, brandIndex: Int): Int =
        if (native.isLoaded) native.airDelay(deviceType, brandIndex) else 200

    override fun brands(deviceType: Int): List<BrandInfo> = when {
        native.isLoaded -> native.brands(deviceType)
        db.isLoaded() -> db.brands(deviceType)
        else -> clean.brands(deviceType)
    }
}
