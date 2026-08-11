package dev.ilyaask.openir.blaster

import android.util.Log
import dev.ilyaask.openir.codec.IrCodec
import dev.ilyaask.openir.data.RemoteKey
import dev.ilyaask.openir.ir.IRType
import dev.ilyaask.openir.transport.IoTransport
import dev.ilyaask.openir.transport.OpenResult
import dev.ilyaask.openir.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates transport + codec to: connect, send a prebuilt or learned key, and run the
 * learn/capture flow. Mirrors the original `ActivityMain` + `ETGlobal.mTg` flow.
 */
class IrBlaster(private val codec: IrCodec) {
    var transport: Transport? = null
        private set

    suspend fun connectIO(): OpenResult = withContext(Dispatchers.IO) {
        transport?.close()
        val t = IoTransport()
        val r = t.open()
        transport = if (r is OpenResult.Success) t else { t.close(); null }
        r
    }

    fun disconnect() {
        transport?.close(); transport = null
    }

    /** Brand catalogue for a device class (from the loaded codec's DB; empty if no DB). */
    fun brands(deviceType: Int): List<dev.ilyaask.openir.codec.BrandInfo> = codec.brands(deviceType)

    /** Send a prebuilt key (deviceType + brand + keyCode) using the codec's DB. */
    suspend fun sendKey(deviceType: Int, brandIndex: Int, keyCode: Int): SendResult =
        withContext(Dispatchers.IO) {
            val t = transport ?: return@withContext SendResult.Error("not connected")
            val frame = codec.searchKeyData(deviceType, brandIndex, keyCode)
                ?: return@withContext SendResult.Error("codec has no code for this key yet (DB/encoder WIP)")
            // Vendor behaviour (FragmentAIR): a frame starting 55 AA is a multi-part AC state
            // frame — write each 55AA-delimited part with 300ms gaps; otherwise one write.
            for ((i, part) in split55aa(frame).withIndex()) {
                val n = t.write(part)
                if (n <= 0) return@withContext SendResult.Error("write failed (part $i)")
                if (i > 0 || part.size != frame.size) Thread.sleep(300)
            }
            SendResult.Sent
        }

    /** Transmit a prebuilt frame directly (used by the debug/validation screen). */
    suspend fun writeRaw(frame: ByteArray): SendResult = withContext(Dispatchers.IO) {
        val t = transport ?: return@withContext SendResult.Error("not connected")
        val n = t.write(frame)
        if (n > 0) SendResult.Sent else SendResult.Error("write failed ($n)")
    }

    /** Replay a learned key (raw timing hex stored on the remote). */
    suspend fun sendLearned(key: RemoteKey): SendResult = withContext(Dispatchers.IO) {
        val t = transport ?: return@withContext SendResult.Error("not connected")
        val raw = key.learnedHex?.hexToBytes() ?: return@withContext SendResult.Error("key has no learned data")
        val frame = codec.studyKeyCode(raw) ?: return@withContext SendResult.Error("learned-code encoder WIP")
        val n = t.write(frame)
        if (n > 0) SendResult.Sent else SendResult.Error("write failed")
    }

    /**
     * Run the learn/capture flow: enter learn mode, read back raw timing bytes.
     * Mirrors the vendor StudyTask: one learn command, then re-read in a loop until the
     * overall timeout — a single driver read window is only ~1 s, but the vendor keeps the
     * window open ~15 s by re-reading, so the user isn't racing a 1-second press window.
     *
     * @param longCapture 230-byte capture if true, else 110-byte.
     * @param overallTimeoutMs total listen time before giving up.
     * @return raw captured bytes on success.
     */
    suspend fun learn(longCapture: Boolean, overallTimeoutMs: Long = 15_000): LearnResult = withContext(Dispatchers.IO) {
        val t = transport ?: return@withContext LearnResult.Error("not connected")
        val size = if (longCapture) 230 else 110
        val cmd = if (longCapture) IRType.CMD_LEARN_LONG else IRType.CMD_LEARN_SHORT
        Log.i(TAG, "learn: cmd=${cmd.joinToString("") { "%02x".format(it) }} expecting=$size bytes")
        t.write(cmd)
        Thread.sleep(200)
        val deadline = System.currentTimeMillis() + overallTimeoutMs
        val buf = ByteArray(size)
        var lastRead = -1
        while (System.currentTimeMillis() < deadline) {
            buf.fill(0)
            val read = t.read(buf, 1500)
            lastRead = read
            if (read > 0) {
                Log.i(TAG, "learn: read returned $read bytes")
                return@withContext if (read < size) LearnResult.Captured(buf.copyOf(read)) else LearnResult.Captured(buf)
            }
        }
        Log.i(TAG, "learn: no signal after ${overallTimeoutMs}ms (last read=$lastRead)")
        LearnResult.Error("no signal captured (timeout)")
    }

    companion object {
        private const val TAG = "OpenIR/Blaster"

        /**
         * Vendor multi-part AC frame convention (FragmentAIR): multi-part frames are stored as
         * `55 AA <part1> 55 AA <part2> …` and the vendor writes the parts *between* the
         * 55 AA delimiters (hex-string split on "55aa", empties skipped). Single frames
         * (no 55 AA prefix) return a one-element list.
         */
        internal fun split55aa(frame: ByteArray): List<ByteArray> {
            if (frame.size < 4 || frame[0] != 0x55.toByte() || frame[1] != 0xAA.toByte()) return listOf(frame)
            val parts = mutableListOf<ByteArray>()
            var start = 2
            var i = 2
            while (i + 1 < frame.size) {
                if (frame[i] == 0x55.toByte() && frame[i + 1] == 0xAA.toByte()) {
                    if (i > start) parts.add(frame.copyOfRange(start, i))
                    i += 2
                    start = i
                } else i++
            }
            if (frame.size > start) parts.add(frame.copyOfRange(start, frame.size))
            return if (parts.isEmpty()) listOf(frame) else parts
        }
    }
}

sealed interface SendResult { data object Sent : SendResult; data class Error(val msg: String) : SendResult }
sealed interface LearnResult { data class Captured(val raw: ByteArray) : LearnResult; data class Error(val msg: String) : LearnResult }

private fun String.hexToBytes(): ByteArray {
    val clean = replace(" ", "").replace(":", "")
    return ByteArray(clean.length / 2) { i ->
        ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
    }
}
