package et.song.jni.ir

/**
 * JNI binding shim that matches the ORIGINAL native library's exported symbol names
 * (`Java_et_song_jni_ir_ETIR_*`, discovered via `llvm-objdump --dynamic-syms`). Because the
 * original lib uses name-based JNI binding (not RegisterNatives), declaring the native methods
 * on a class with this exact package + name makes [System.loadLibrary] bind them directly.
 *
 * This class only exists so [dev.ilyaask.openir.codec.NativeIrCodec] can drive the user's own
 * copy of `libet_jni_ir_tools.so` as a temporary compatibility codec. It is NOT used by the
 * clean-room path. The native lib is never shipped by this project.
 */
object ETIR {
    @JvmStatic external fun Init()
    @JvmStatic external fun ClearData()
    @JvmStatic external fun GetTableCount(type: Int): Int
    @JvmStatic external fun GetBrandCount(type: Int, table: Int): Int
    @JvmStatic external fun GetTypeCount(type: Int, table: Int): Int
    @JvmStatic external fun GetBrandArray(type: Int, table: Int): IntArray
    @JvmStatic external fun GetTypeArray(type: Int, table: Int): IntArray
    @JvmStatic external fun GetAirDelay(type: Int, brand: Int): Int
    @JvmStatic external fun SearchKeyData(type: Int, brand: Int, key: Int): ByteArray
    @JvmStatic external fun StudyKeyCode(raw: ByteArray, len: Int): ByteArray
}
