package dev.ilyaask.openir

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ilyaask.openir.codec.CleanIrCodec
import dev.ilyaask.openir.codec.Dsp110
import dev.ilyaask.openir.codec.NativeIrCodec
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * On-device oracle fuzzing: feeds synthetic + real captures through BOTH the bundled vendor
 * codec (`libet_jni_ir_tools.so`) and the clean-room port, then diffs frames byte-for-byte.
 * Telemetry goes to logcat tag `FuzzDiff`; the test always passes so the whole corpus runs.
 *
 * For 110B it also checks gate AGREEMENT: the vendor returning null (its learn_data_in_out
 * sanity gate rejecting) should coincide with [Dsp110.validate] rejecting the clean frame.
 */
@RunWith(AndroidJUnit4::class)
class VendorCodecFuzzTest {

    private val native = NativeIrCodec.load()
    private val clean = CleanIrCodec()
    private val dsp110 = Dsp110()

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun diff(a: ByteArray, b: ByteArray): List<Int> {
        val n = minOf(a.size, b.size)
        return (0 until n).filter { a[it] != b[it] } +
            if (a.size != b.size) listOf(-1) else emptyList()
    }

    private fun corpus110(): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        out += "zeros" to ByteArray(110)
        out += "ff" to ByteArray(110) { 0xff.toByte() }
        out += "junkReal" to (byteArrayOf(0x00, 0x88.toByte(), 0x99.toByte(), 0xaa.toByte()) + ByteArray(106))
        out += "ramp" to ByteArray(110) { (it % 0x60 + 1).toByte() }
        out += "rampDown" to ByteArray(110) { (0x60 - it % 0x60).toByte() }
        out += "altMark" to ByteArray(110) { if (it % 2 == 0) 0x16 else 0x40 }
        out += "tinyVals" to ByteArray(110) { if (it == 1) 0x40 else 0x01 }
        out += "leaderNec" to ByteArray(110) { i ->
            when (i) {
                0 -> 0x00; 1 -> 0x88.toByte(); 2 -> 0x99.toByte(); 3 -> 0xaa.toByte(); 4 -> 0x00
                else -> if (i % 2 == 1) 0x20 else if (i % 4 == 0) 0x40 else 0x16
            }
        }
        out += "sonyish" to ByteArray(110) { i ->
            if (i < 5) byteArrayOf(0, 0x60, 0x12, 0x30, 0)[i]
            else if (i % 2 == 0) 0x0c else if (i % 3 == 0) 0x24 else 0x12
        }
        out += "overflowHeavy" to ByteArray(110) { i ->
            if (i % 3 == 0 && i > 3) (0x80 or (i % 16)).toByte() else (0x10 + i % 0x20).toByte()
        }
        out += "bit08Heavy" to ByteArray(110) { i ->
            val v = 0x08 + (i % 7); if (i % 2 == 0) v.toByte() else (0x20 + i % 0x10).toByte()
        }
        out += "highCount" to ByteArray(110) { (0x10 + it % 0x21).toByte() }
        out += "sparse" to ByteArray(110) { i -> if (i % 7 == 0) 0x55 else 0x00 }
        out += "sparse2" to ByteArray(110) { i -> if (i % 11 == 3) 0x33 else 0x00 }
        for (seed in 1..24) {
            out += "rand$seed" to ByteArray(110) { Random(seed * 1000 + it).nextInt(256).toByte() }
        }
        for (seed in 1..12) {
            out += "randSmall$seed" to ByteArray(110) { Random(seed * 777 + it).nextInt(1, 0x41).toByte() }
        }
        for (seed in 1..6) {
            // plausible captures: small timings with occasional long (overflow) gaps
            out += "plausible$seed" to ByteArray(110) { i ->
                val r = Random(seed * 31337 + i)
                when {
                    i < 4 -> byteArrayOf(0, 0x55, 0x33, 0x21)[i]
                    i % 9 == 0 -> (0x80 or r.nextInt(1, 8)).toByte()
                    else -> r.nextInt(0x0c, 0x48).toByte()
                }
            }
        }
        // DSP-focused: buf[0]=0 so compdata actually processes. m708 path (buf[1] < 128).
        for (seed in 1..8) {
            out += "dsp708_$seed" to ByteArray(110) { i ->
                val r = Random(seed * 911 + i * 7)
                when {
                    i == 0 -> 0
                    i == 1 -> r.nextInt(0x20, 0x80).toByte()
                    i == 2 -> r.nextInt(8, 60).toByte()
                    i == 3 -> r.nextInt(0x10, 0x40).toByte()
                    i == 4 -> 0
                    i % 13 == 0 -> (0xF0 or r.nextInt(0, 8)).toByte() // 3-byte overflow form
                    i % 9 == 4 -> (0x80 or r.nextInt(1, 16)).toByte() // 2-byte overflow form
                    else -> r.nextInt(0x08, 0x50).toByte()
                }
            }
        }
        for (seed in 1..4) {
            out += "dsp708wild_$seed" to ByteArray(110) { i ->
                val r = Random(seed * 733 + i * 3)
                when {
                    i == 0 -> 0
                    i == 1 -> r.nextInt(0x10, 0x80).toByte()
                    else -> r.nextInt(256).toByte()
                }
            }
        }
        // Modifywave + delfeng path (buf[1] >= 128).
        for (seed in 1..8) {
            out += "dspWave_$seed" to ByteArray(110) { i ->
                val r = Random(seed * 577 + i * 11)
                when {
                    i == 0 -> 0
                    i == 1 -> r.nextInt(0x80, 0x100).toByte()
                    i == 2 -> r.nextInt(8, 60).toByte()
                    i == 3 -> r.nextInt(0x10, 0x40).toByte()
                    i == 4 -> 0
                    i % 13 == 0 -> (0xF0 or r.nextInt(0, 8)).toByte()
                    i % 9 == 4 -> (0x80 or r.nextInt(1, 16)).toByte()
                    else -> r.nextInt(0x08, 0x50).toByte()
                }
            }
        }
        for (seed in 1..4) {
            out += "dspWaveWild_$seed" to ByteArray(110) { i ->
                val r = Random(seed * 389 + i * 5)
                when {
                    i == 0 -> 0
                    i == 1 -> r.nextInt(0x80, 0x100).toByte()
                    else -> r.nextInt(256).toByte()
                }
            }
        }
        // delfeng-merge-focused: Modifywave path with tiny (<=4) samples sprinkled in.
        for (seed in 1..8) {
            out += "dspMerge_$seed" to ByteArray(110) { i ->
                val r = Random(seed * 1237 + i * 13)
                when {
                    i == 0 -> 0
                    i == 1 -> r.nextInt(0x80, 0x100).toByte()
                    i == 2 -> r.nextInt(20, 80).toByte()
                    i == 3 -> r.nextInt(0x10, 0x40).toByte()
                    i == 4 -> 0
                    i % 7 == 3 -> r.nextInt(1, 5).toByte() // tiny spike candidates
                    i % 11 == 5 -> (0x80 or r.nextInt(1, 8)).toByte() // 2-byte form
                    else -> r.nextInt(0x10, 0x60).toByte()
                }
            }
        }
        // Real-DSP gate-passers: compdata accepts (Modifywave path), gate passes
        // (buf[4] in [16,129), getfigure w[2] >= 5 via peaks at ordinals >= 3),
        // with tiny samples sprinkled for delfeng merges and 2-byte forms throughout.
        for (seed in 1..8) {
            out += "dspReal$seed" to ByteArray(110) { i ->
                val r = Random(seed * 9119 + i * 17)
                when {
                    i == 0 -> 0
                    i == 1 -> (0x80 or r.nextInt(1, 0x80)).toByte()
                    i == 2 -> r.nextInt(60, 100).toByte()
                    i == 3 -> r.nextInt(0x10, 0x40).toByte()
                    i == 4 -> r.nextInt(0x10, 0x80).toByte()
                    // dominant peaks (2-byte forms) starting at waveform ordinal >= 3
                    i == 9 || i == 10 || i == 21 || i == 22 || i == 33 || i == 34 ->
                        (0x80 or r.nextInt(1, 6)).toByte()
                    i % 7 == 3 -> r.nextInt(1, 5).toByte() // delfeng merge candidates
                    i % 11 == 5 -> (0x80 or r.nextInt(1, 8)).toByte()
                    else -> r.nextInt(0x08, 0x50).toByte()
                }
            }
        }
        FuzzCorpusReal.real110.forEachIndexed { i, b -> out += "real110_$i" to b }
        return out
    }

    private fun corpus230(): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        out += "zeros" to ByteArray(230)
        out += "ff" to ByteArray(230) { 0xff.toByte() }
        out += "ramp" to ByteArray(230) { (it % 0x60 + 1).toByte() }
        // zero-run torture: nonzero head, then all zeros (window fires immediately at k=0)
        out += "headThenZeros" to (ByteArray(45) { (0x20 + it % 0x10).toByte() } + ByteArray(185))
        // zero run deep in the payload
        out += "midZeroRun" to ByteArray(230) { i ->
            if (i in 100..150) 0x00 else (0x20 + i % 0x10).toByte()
        }
        // no 9-zero run anywhere
        out += "noZeroRun" to ByteArray(230) { i -> if (i % 8 == 0) 0x01 else 0x00 }
        for (seed in 1..6) {
            out += "rand$seed" to ByteArray(230) { Random(seed * 4242 + it).nextInt(256).toByte() }
        }
        for (seed in 1..6) {
            // random with embedded long zero runs at random offsets (hammer the sliding patch)
            out += "zeroRun$seed" to ByteArray(230) { i ->
                val r = Random(seed * 999 + i)
                if (i in (40 + seed * 10)..(80 + seed * 10)) 0x00 else r.nextInt(1, 256).toByte()
            }
        }
        for (seed in 1..4) {
            // plausible AC-like: header + timing bytes, trailing zeros
            out += "acLike$seed" to ByteArray(230) { i ->
                val r = Random(seed * 5150 + i)
                when {
                    i == 0 -> 0
                    i < 60 -> r.nextInt(0x08, 0x68).toByte()
                    else -> 0
                }
            }
        }
        FuzzCorpusReal.real230.forEachIndexed { i, b -> out += "real230_$i" to b }
        return out
    }

    /**
     * Single-case shard: `-e size 110|230 -e caseIndex N`. Runs one corpus case in a fresh
     * process so a native stack-smash only kills that shard, and native/clean global state
     * (toggle bits) is virgin per case. Logs a single `RESULT` line + hex on mismatch.
     */
    @Test
    fun runSingle() {
        assertTrue("vendor codec must load for oracle fuzzing", native.isLoaded)
        val args = InstrumentationRegistry.getArguments()
        val size = args.getString("size")?.toIntOrNull() ?: 110
        val idx = args.getString("caseIndex")?.toIntOrNull() ?: 0
        val corpus = if (size == 110) corpus110() else corpus230()
        Log.i("FuzzDiff", "corpus size=$size count=${corpus.size}")
        val (name, input) = corpus[idx]
        val verbose = args.getString("verbose") == "1"
        val vendor = native.studyKeyCode(input.copyOf())
        val cleanFrame = if (size == 110) dsp110.process(input.copyOf()) else clean.studyKeyCode(input.copyOf())
        // compdata-accept heuristic: buildFrame mutates buf via copy-back iff the DSP accepted
        val dspAccepted = if (size == 110) {
            val probe = input.copyOf()
            dsp110.buildFrame(probe)
            !probe.contentEquals(input)
        } else {
            false
        }
        if (verbose && vendor != null && cleanFrame != null) {
            Log.i("FuzzDiff", "$size/$name in    =${hex(input)}")
            Log.i("FuzzDiff", "$size/$name vendor=${hex(vendor)}")
            Log.i("FuzzDiff", "$size/$name clean =${hex(cleanFrame)}")
        }
        when {
            vendor == null -> Log.i("FuzzDiff", "$size/$name RESULT VENDOR-NULL")
            cleanFrame == null -> Log.i("FuzzDiff", "$size/$name RESULT CLEAN-NULL vendor=${hex(vendor)}")
            else -> {
                val d = diff(vendor, cleanFrame)
                if (d.isEmpty()) {
                    val kind = when {
                        vendor.all { it == 0.toByte() } -> "MATCH-ZERO"
                        dspAccepted -> "MATCH-DSP"
                        else -> "MATCH-REAL"
                    }
                    Log.i("FuzzDiff", "$size/$name RESULT $kind")
                } else {
                    Log.i("FuzzDiff", "$size/$name RESULT MISMATCH@${d.take(24)} (of ${d.size}) in=${hex(input)}")
                    Log.i("FuzzDiff", "$size/$name vendor=${hex(vendor)}")
                    Log.i("FuzzDiff", "$size/$name clean =${hex(cleanFrame)}")
                }
            }
        }
    }

    /**
     * Toggle-state probe: calls the SAME input twice in one process and logs both vendor
     * frames, pinning the native g_toggle initial value + flip semantics.
     */
    @Test
    fun toggleProbe() {
        assertTrue(native.isLoaded)
        val cases = listOf("rampDown" to corpus110()[4].second, "altMark" to corpus110()[5].second)
        for ((name, input) in cases) {
            val f1 = native.studyKeyCode(input.copyOf())
            val f2 = native.studyKeyCode(input.copyOf())
            val f3 = native.studyKeyCode(input.copyOf())
            Log.i("FuzzDiff", "probe/$name call1=${f1?.let { hex(it) }}")
            Log.i("FuzzDiff", "probe/$name call2=${f2?.let { hex(it) }}")
            Log.i("FuzzDiff", "probe/$name call3=${f3?.let { hex(it) }}")
        }
    }

    /**
     * Scale probe: dumps SearchKeyData frames across device types / brands / keys so we can
     * spot known-protocol timing signatures (NEC 9000us leader etc.) and derive us-per-unit
     * for the universal encoder. Logs tag `ScaleProbe`.
     */
    @Test
    fun scaleProbe() {
        assertTrue(native.isLoaded)
        for (dt in 0..10) {
            val count = try { et.song.jni.ir.ETIR.GetBrandCount(dt, 0) } catch (t: Throwable) { -1 }
            Log.i("ScaleProbe", "dt=$dt brandCount=$count")
        }
        outer@ for (dt in 0..10) {
            val count = try { et.song.jni.ir.ETIR.GetBrandCount(dt, 0) } catch (t: Throwable) { 0 }
            if (count <= 0) continue
            val arr = try { et.song.jni.ir.ETIR.GetBrandArray(dt, 0) } catch (t: Throwable) { continue }
            for (b in arr.take(count).take(2)) {
                var logged = 0
                for (key in 0..40) {
                    val f = try { native.searchKeyData(dt, b, key) } catch (t: Throwable) { null }
                    if (f != null && f.isNotEmpty() && f.any { it != 0.toByte() }) {
                        Log.i("ScaleProbe", "dt=$dt brand=$b key=$key len=${f.size} hex=${hex(f)}")
                        if (++logged >= 3) break
                    }
                }
            }
        }
        Log.i("ScaleProbe", "scaleProbe DONE")
    }

    @Test
    fun fuzz110() {
        assertTrue("vendor codec must load for oracle fuzzing", native.isLoaded)
        var match = 0; var mismatch = 0; var vendorNull = 0
        var logged = 0
        for ((name, input) in corpus110()) {
            Log.i("FuzzDiff", "110/$name START") // last START before a native crash = culprit
            val vendor = try { native.studyKeyCode(input.copyOf()) } catch (t: Throwable) {
                Log.w("FuzzDiff", "110/$name vendor threw $t"); null
            }
            // Dsp110.process mirrors native semantics exactly: real frame, or 112 zeros on reject.
            val cleanFrame = try { dsp110.process(input.copyOf()) } catch (t: Throwable) {
                Log.w("FuzzDiff", "110/$name clean threw $t"); null
            }
            val gate = try { dsp110.buildFrame(input.copyOf())?.let { dsp110.validate(it) } } catch (t: Throwable) { null }
            when {
                vendor == null -> { vendorNull++; Log.i("FuzzDiff", "110/$name vendor=null clean=${cleanFrame?.size}") }
                cleanFrame == null -> Log.i("FuzzDiff", "110/$name CLEAN-THREW vendor=${hex(vendor)}")
                else -> {
                    val d = diff(vendor, cleanFrame)
                    if (d.isEmpty()) match++ else {
                        mismatch++
                        if (logged++ < 12) {
                            Log.i("FuzzDiff", "110/$name MISMATCH@${d.take(24)} (of ${d.size}) gate=$gate in=${hex(input)}")
                            Log.i("FuzzDiff", "110/$name vendor=${hex(vendor)}")
                            Log.i("FuzzDiff", "110/$name clean =${hex(cleanFrame)}")
                        }
                    }
                }
            }
        }
        Log.i("FuzzDiff", "110 SUMMARY match=$match mismatch=$mismatch vendorNull=$vendorNull")
    }

    @Test
    fun fuzz230() {
        assertTrue("vendor codec must load for oracle fuzzing", native.isLoaded)
        var match = 0; var mismatch = 0; var vendorNull = 0
        var logged = 0
        for ((name, input) in corpus230()) {
            Log.i("FuzzDiff", "230/$name START") // last START before a native crash = culprit
            val vendor = try { native.studyKeyCode(input.copyOf()) } catch (t: Throwable) {
                Log.w("FuzzDiff", "230/$name vendor threw $t"); null
            }
            val cleanFrame = try { clean.studyKeyCode(input.copyOf()) } catch (t: Throwable) {
                Log.w("FuzzDiff", "230/$name clean threw $t"); null
            }
            when {
                vendor == null -> { vendorNull++; Log.i("FuzzDiff", "230/$name vendor=null") }
                cleanFrame == null -> Log.i("FuzzDiff", "230/$name CLEAN-NULL vendor=${hex(vendor)}")
                else -> {
                    val d = diff(vendor, cleanFrame)
                    if (d.isEmpty()) match++ else {
                        mismatch++
                        if (logged++ < 12) {
                            Log.i("FuzzDiff", "230/$name MISMATCH@${d.take(24)} (of ${d.size}) in=${hex(input)}")
                            Log.i("FuzzDiff", "230/$name vendor=${hex(vendor)}")
                            Log.i("FuzzDiff", "230/$name clean =${hex(cleanFrame)}")
                        }
                    }
                }
            }
        }
        Log.i("FuzzDiff", "230 SUMMARY match=$match mismatch=$mismatch vendorNull=$vendorNull")
    }
}
