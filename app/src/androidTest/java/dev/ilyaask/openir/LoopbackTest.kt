package dev.ilyaask.openir

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ilyaask.openir.blaster.IrBlaster
import dev.ilyaask.openir.blaster.LearnResult
import dev.ilyaask.openir.codec.CleanIrCodec
import dev.ilyaask.openir.transport.OpenResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TX→RX loopback probe: the phone's IR receiver listens (learn mode) while a frame is
 * transmitted from the same phone. IR crosstalk/bounce between the adjacent TX LED and
 * receiver is enough to capture *something* if the blaster is really emitting. This lets us
 * compare "what the hardware actually emits" between our TX path and the vendor app's —
 * without needing the target device or a second pair of eyes.
 *
 * Run one method at a time (fresh process per run), results in logcat tag OpenIR/Loopback.
 */
@RunWith(AndroidJUnit4::class)
class LoopbackTest {

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun connect(): IrBlaster {
        val blaster = IrBlaster(CleanIrCodec())
        val r = runBlocking { blaster.connectIO() }
        Log.i(TAG, "connectIO: $r")
        org.junit.Assert.assertTrue("transport must open", r is OpenResult.Success)
        return blaster
    }

    /** Our 110B conditioned frame bounced back into our own receiver. */
    @Test
    fun loopbackSelfTx110() = runBlocking {
        val blaster = connect()
        val codec = CleanIrCodec()
        val frame = codec.studyKeyCodeDebugUngated110(
            dev.ilyaask.openir.codec.DebugCaptures.real110.copyOf()
        )!!.frame
        Log.i(TAG, "TX frame (110B conditioned): ${hex(frame)}")
        val capture = async {
            val r = blaster.learn(false)
            when (r) {
                is LearnResult.Captured -> { Log.i(TAG, "RX captured ${r.raw.size} B: ${hex(r.raw)}"); r.raw }
                is LearnResult.Error -> { Log.i(TAG, "RX error: ${r.msg}"); null }
            }
        }
        delay(400) // let learn mode engage before transmitting
        blaster.writeRaw(frame)
        Log.i(TAG, "TX written")
        capture.await()
        blaster.disconnect()
        Unit
    }

    /** Same with the 230B capture that is known to have physically controlled the AC. */
    @Test
    fun loopbackSelfTx230() = runBlocking {
        val blaster = connect()
        val codec = CleanIrCodec()
        val frame = codec.studyKeyCode(FuzzCorpusReal.real230[0].copyOf())!!
        Log.i(TAG, "TX frame (230B): ${hex(frame)}")
        val capture = async {
            val r = blaster.learn(true)
            when (r) {
                is LearnResult.Captured -> { Log.i(TAG, "RX captured ${r.raw.size} B: ${hex(r.raw)}"); r.raw }
                is LearnResult.Error -> { Log.i(TAG, "RX error: ${r.msg}"); null }
            }
        }
        delay(400)
        blaster.writeRaw(frame)
        Log.i(TAG, "TX written")
        capture.await()
        blaster.disconnect()
        Unit
    }

    /**
     * 15-second open receive window for capturing an EXTERNAL transmitter (e.g. the vendor
     * app's "Test Data" button, tapped from another shell while this test runs in the
     * background). Logs whatever arrives.
     */
    @Test
    fun loopbackExternalTx110() = runBlocking {
        val blaster = connect()
        Log.i(TAG, "RX window open (110B) — fire the external transmitter now")
        val r = blaster.learn(false)
        when (r) {
            is LearnResult.Captured -> Log.i(TAG, "RX captured ${r.raw.size} B: ${hex(r.raw)}")
            is LearnResult.Error -> Log.i(TAG, "RX error: ${r.msg}")
        }
        blaster.disconnect()
        Unit
    }

    companion object { private const val TAG = "OpenIR/Loopback" }
}
