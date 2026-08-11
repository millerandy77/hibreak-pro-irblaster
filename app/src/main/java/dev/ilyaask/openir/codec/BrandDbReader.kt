package dev.ilyaask.openir.codec

import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader for the proprietary brand DB embedded in `libet_jni_ir_tools.so`'s `.rodata`.
 *
 * This is an **opt-in** compatibility pack: the app does NOT ship the 106 MB proprietary
 * binary (it isn't ours to relicense under MIT). A user who has the official APK supplies the
 * `.rodata` once (see BUILD.md / docs), and this reader indexes the named `*_info`/`*_table`
 * symbols recovered in `re/branddb_symbols.json` (vaddr == file offset).
 *
 * What's implemented (high confidence):
 *  - loading the .rodata bytes and resolving any symbol in [INFO_TABLES]/[DATA_TABLES];
 *  - `brandCount(type)` and `brands(type)`: each `*_info` record's first 32-bit word is the
 *    key count for that brand (verified on AC/SLR/Lamp records — see docs §9.2).
 *
 * What's pending (per-type record schema — RE task 19):
 *  - `searchKeyData(type, brand, key)`: each device type stores per-key data differently
 *    (AC stores offsets into `arc_table`; Lamp stores global key indices; SLR a brand index;
 *    and `SearchKeyData` assembles the `[0x30, 0x00, header, payload, checksum]` frame using
 *    per-type record field offsets like 0x25/0x26/0x27/0x28). The record bytes are accessible
 *    via [recordBytes] for inspection; once the per-type schemas are decoded, `searchKeyData`
 *    fills in. The decoded records also pin down [BigmeFrameEncoder]'s header layout.
 */
class BrandDbReader {
    private var buf: ByteBuffer? = null

    /** Load the proprietary .rodata. `input` = the raw .so file or an extracted .rodata blob. */
    fun load(input: InputStream) {
        val bytes = input.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf = b
        Log.i(TAG, "loaded ${bytes.size} bytes of brand-DB .rodata")
    }

    fun isLoaded(): Boolean = buf != null

    private fun bb(): ByteBuffer = buf ?: error("BrandDbReader not loaded")

    private fun table(name: String): Table =
        INFO_TABLES[name] ?: DATA_TABLES[name] ?: error("unknown table $name")

    private fun u32At(vaddr: Int): Int = bb().getInt(vaddr)

    /** Number of brands for a device type (info-table size / stride). */
    fun brandCount(deviceType: Int): Int {
        val info = infoTableFor(deviceType) ?: return 0
        if (info.stride <= 0) return 0
        return info.size / info.stride
    }

    /**
     * Brand catalogue: each entry's first word = key count for that brand; the display name
     * comes from [BrandNames] (extracted from the official app's `strs_<type>_brand` arrays),
     * falling back to `Brand N` when the proprietary DB has more brands than the name table
     * (some types group brands differently across their `_1/_2/_3` tables).
     */
    fun brands(deviceType: Int): List<BrandInfo> {
        val info = infoTableFor(deviceType) ?: return emptyList()
        if (info.stride <= 0) return emptyList()
        val n = brandCount(deviceType)
        val names = BrandNames.names(deviceType)
        return (0 until n).map { BrandInfo(it, names.getOrNull(it) ?: "Brand $it") }
    }

    /** Key count for a specific brand (record[0..3]). */
    fun keyCount(deviceType: Int, brandIndex: Int): Int {
        val info = infoTableFor(deviceType) ?: return 0
        if (info.stride <= 0) return 0
        val off = info.vaddr + brandIndex * info.stride
        if (off + 4 > info.vaddr + info.size) return 0
        return u32At(off)
    }

    /** Raw bytes of a brand record (for UI inspection / schema finalization). */
    fun recordBytes(deviceType: Int, brandIndex: Int): ByteArray? {
        val info = infoTableFor(deviceType) ?: return null
        if (info.stride <= 0) return null
        val off = info.vaddr + brandIndex * info.stride
        val b = bb()
        val out = ByteArray(info.stride)
        b.position(off); b.get(out)
        return out
    }

    /**
     * Build a transmit frame for a prebuilt code.
     *
     * RE status (from `Java_et_song_jni_ir_ETIR_SearchKeyData` @ 0x33d0): the function assembles
     *   frame[0]=0x30, frame[1]=0x00, then a per-type header, then a payload copied from the
     *   brand DB, then a checksum. For the "info-table" device types (AUDIO/PJT/STB/DVD/FAN/...)
     *   the recovered header layout is:
     *     frame[2] = infoTable[brandIndex*stride]            (record[0], an intra-record offset)
     *     frame[3] = record[payloadOff + 1]
     *     frame[4] = record[payloadOff + 2]
     *     frame[5] = record[0x25]
     *     frame[6] = record[0x26]
     *     frame[7] = record[0x27]
     *     frame[8] = record[0x28]
     *   where payloadOff = frame[2] (an offset *within* the brand record), and the IR timing
     *   payload follows at record[payloadOff + 3 ..]. AC (g_remote_arc_info) is a different
     *   schema: per-key 32-bit offsets into `arc_table` (see [experimentalArcPayload]).
     *
     * What's confident: the header bytes above and the frame wrapper (0x30/0x00 + checksum).
     * What's UNVALIDATED (needs one on-device transmit per type): the exact payload length, the
     * checksum seed (assumed 0x30 = sum of frame[0..1]), and the frame size (assumed 232, the
     * device's standard timing-frame length). Returns null for types whose table/stride isn't
     * mapped. Emits an UNVALIDATED warning so callers don't mistake this for a confirmed codec.
     */
    fun searchKeyData(deviceType: Int, brandIndex: Int, keyCode: Int): ByteArray? {
        val info = infoTableFor(deviceType) ?: return null
        if (info.stride <= 0) return null
        val record = recordBytes(deviceType, brandIndex) ?: return null
        // AC uses an offset-into-arc_table schema, not the intra-record 0x25/0x28 layout.
        if (deviceType == 0xC000) return buildArcFrame(brandIndex, keyCode)
        return buildInfoFrame(record, keyCode)
    }

    /** "Info-table" frame: header from record[0x25..0x28], payload from record[payloadOff+3..]. */
    private fun buildInfoFrame(record: ByteArray, keyCode: Int): ByteArray? {
        val frame = ByteArray(232)
        frame[0] = 0x30
        frame[1] = 0x00
        val payloadOff = record[0].toInt() and 0xFF
        frame[2] = record[0]
        if (payloadOff + 2 < record.size) {
            frame[3] = record[payloadOff + 1]
            frame[4] = record[payloadOff + 2]
        }
        // record[0x25..0x28] -> frame[5..8] (only if the record is that long)
        intArrayOf(0x25, 0x26, 0x27, 0x28).forEachIndexed { i, off ->
            if (off < record.size) frame[5 + i] = record[off]
        }
        // payload: timing bytes that follow the per-key header within the record.
        var wi = 9
        var ri = payloadOff + 3
        // keyCode selects among codes packed in the payload region; advance past earlier codes
        // is type-specific — without the per-type key index we start at the region base. The
        // device may still react for key 0; other keys need the per-type key stride (TODO).
        while (ri < record.size && wi < frame.size - 1) {
            frame[wi++] = record[ri++]
        }
        // pad remainder with 0x00 (the device's timing frames pad with 0xFF; for prebuilt codes
        // the original lib trims to the code length — UNVALIDATED).
        var sum = 0x30
        for (i in 2 until frame.size - 1) sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFF
        frame[frame.size - 1] = sum.toByte()
        Log.w(TAG, "searchKeyData: UNVALIDATED info-frame (keyCode=$keyCode, payloadOff=$payloadOff)")
        return frame
    }

    /** AC frame: payload fetched from `arc_table` at the per-key 32-bit offset. UNVALIDATED. */
    private fun buildArcFrame(brandIndex: Int, keyCode: Int): ByteArray? {
        val payload = experimentalArcPayload(brandIndex, keyCode, maxLen = 232 - 9) ?: return null
        val frame = ByteArray(232)
        frame[0] = 0x30
        frame[1] = 0x00
        // AC header bytes (0x25..0x28) come from the arc info record too; copy what we can.
        val info = INFO_TABLES["g_remote_arc_info"] ?: return null
        val recOff = info.vaddr + brandIndex * info.stride
        val b = bb()
        intArrayOf(0x25, 0x26, 0x27, 0x28).forEachIndexed { i, off ->
            if (recOff + off < info.vaddr + info.size) {
                b.position(recOff + off); frame[5 + i] = b.get()
            }
        }
        for (i in payload.indices) frame[9 + i] = payload[i]
        var sum = 0x30
        for (i in 2 until frame.size - 1) sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFF
        frame[frame.size - 1] = sum.toByte()
        Log.w(TAG, "searchKeyData: UNVALIDATED arc-frame (brand=$brandIndex, key=$keyCode)")
        return frame
    }

    /**
     * Experimental: AC records store per-key 32-bit offsets into `arc_table`. Returns the raw
     * payload bytes at `arc_table[offset]` for a given (brand, key), or null. Length of the
     * payload is unknown without the schema — caller must bound it. UNVALIDATED.
     */
    fun experimentalArcPayload(brandIndex: Int, keyCode: Int, maxLen: Int = 128): ByteArray? {
        val info = INFO_TABLES["g_remote_arc_info"] ?: return null
        if (info.stride <= 0) return null
        val recOff = info.vaddr + brandIndex * info.stride
        val keyCnt = u32At(recOff)
        if (keyCode < 0 || keyCode >= keyCnt) return null
        val offsetIntoArc = u32At(recOff + 4 + keyCode * 4)
        val arc = DATA_TABLES["arc_table"] ?: return null
        if (offsetIntoArc < 0 || offsetIntoArc + maxLen > arc.size) return null
        val out = ByteArray(maxLen)
        val b = bb(); b.position(arc.vaddr + offsetIntoArc); b.get(out)
        return out
    }

    private fun infoTableFor(deviceType: Int): Table? = when (deviceType) {
        0xC000 -> INFO_TABLES["g_remote_arc_info"]            // AIR (AC)
        0x2000 -> INFO_TABLES["TV_info"]
        0x6000 -> INFO_TABLES["remote_dvd_info"]
        0x4000 -> INFO_TABLES["remote_stb_info"]
        0xA000 -> INFO_TABLES["remote_pjt_info"]
        0x8000 -> INFO_TABLES["remote_fan_info"]
        0x2100 -> INFO_TABLES["remote_IPTV_info"]
        0x2900 -> INFO_TABLES["remote_Audio_info"]
        0x2D00 -> INFO_TABLES["remote_SLR_info"]
        0x2F00 -> INFO_TABLES["remote_Water_Heater_info"]
        0x2700 -> INFO_TABLES["remote_air_purifier_info"]
        0x3100 -> INFO_TABLES["remote_robotcleaner_info"]
        0x3300 -> INFO_TABLES["remote_Lamp_info"]
        else -> null
    }

    companion object { private const val TAG = "OpenIR/BrandDb" }
}
