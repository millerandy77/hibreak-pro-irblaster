package dev.ilyaask.openir.codec

import et.song.jni.ir.ETIR as NativeETIR

/**
 * Optional compatibility codec: drives the user's own copy of the original
 * `libet_jni_ir_tools.so` via the JNI shim [NativeETIR], whose package/class name matches the
 * library's exported `Java_et_song_jni_ir_ETIR_*` symbols (name-based JNI binding, confirmed
 * by disassembly).
 *
 * This loads NO binary by default. To use it, the user extracts `libet_jni_ir_tools.so`
 * from their own legitimately-owned copy of the official app and places it where
 * [System.loadLibrary] can find it (e.g. the app's native lib dir). The lib embeds its own
 * ~101 MiB codebook in `.rodata`, so no asset files are required for `SearchKeyData`/brand
 * lookups. (The `*.db` fingerprint files under `assets/{device}_{1,2,3}/` are a separate
 * Java-side wizard feature, not needed by this codec.)
 * The app source remains MIT; the codec is a swappable module the user supplies. The clean-room
 * [CleanIrCodec] replaces this once the RE is finished.
 */
class NativeIrCodec private constructor(val isLoaded: Boolean) : IrCodec {

    override fun searchKeyData(deviceType: Int, brandIndex: Int, keyCode: Int): ByteArray? =
        if (isLoaded) NativeETIR.SearchKeyData(deviceType, brandIndex, keyCode) else null

    override fun studyKeyCode(raw: ByteArray): ByteArray? =
        if (isLoaded) NativeETIR.StudyKeyCode(raw, raw.size) else null

    override fun airDelay(deviceType: Int, brandIndex: Int): Int =
        if (isLoaded) NativeETIR.GetAirDelay(deviceType, brandIndex) else 200

    override fun brands(deviceType: Int): List<BrandInfo> {
        if (!isLoaded) return emptyList()
        val count = NativeETIR.GetBrandCount(deviceType, 0)
        val arr = NativeETIR.GetBrandArray(deviceType, 0)
        return arr.take(count).mapIndexed { i, idx -> BrandInfo(idx, "Brand ${i + 1}") }
    }

    companion object {
        private var attempted = false
        private var available = false

        /** Try to load `libet_jni_ir_tools.so` and init it. Idempotent. */
        fun load(): NativeIrCodec {
            if (!attempted) {
                attempted = true
                available = try {
                    System.loadLibrary("et_jni_ir_tools")
                    NativeETIR.Init()
                    android.util.Log.i("OpenIR/NativeCodec", "native lib loaded + Init() OK")
                    true
                } catch (e: Throwable) {
                    android.util.Log.w("OpenIR/NativeCodec", "native lib NOT available: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
            }
            return NativeIrCodec(available)
        }

        fun isAvailable(): Boolean = available
    }
}
