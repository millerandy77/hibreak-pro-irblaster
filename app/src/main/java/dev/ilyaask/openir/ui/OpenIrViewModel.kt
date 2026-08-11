package dev.ilyaask.openir.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ilyaask.openir.blaster.IrBlaster
import dev.ilyaask.openir.blaster.LearnResult
import dev.ilyaask.openir.blaster.SendResult
import dev.ilyaask.openir.codec.BigmeFrameEncoder
import dev.ilyaask.openir.codec.BrandDbReader
import dev.ilyaask.openir.codec.BrandInfo
import dev.ilyaask.openir.codec.CleanIrCodec
import dev.ilyaask.openir.codec.CodecChain
import dev.ilyaask.openir.codec.IrSignal
import dev.ilyaask.openir.codec.IrSignalParser
import dev.ilyaask.openir.codec.NativeIrCodec
import dev.ilyaask.openir.codec.StudyDebug
import dev.ilyaask.openir.data.Remote
import dev.ilyaask.openir.data.RemoteKey
import dev.ilyaask.openir.data.RemoteStore
import dev.ilyaask.openir.ir.DeviceClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OpenIrViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RemoteStore(app)
    private val nativeCodec = NativeIrCodec.load()
    private val brandDb = BrandDbReader()
    private val cleanCodec = CleanIrCodec()
    private val blaster = IrBlaster(CodecChain(nativeCodec, brandDb, cleanCodec))

    val remotes: StateFlow<List<Remote>> = store.remotes.let { f ->
        MutableStateFlow<List<Remote>>(emptyList()).also { mut ->
            viewModelScope.launch { f.collect { mut.value = it } }
        }
    }.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _lastLearn = MutableStateFlow<LearnResult?>(null)
    val lastLearn: StateFlow<LearnResult?> = _lastLearn.asStateFlow()

    /** True if a working codec is available (native lib OR opt-in brand-DB .rodata loaded). */
    private val _codecReady = MutableStateFlow(nativeCodec.isLoaded)
    val codecReady: StateFlow<Boolean> = _codecReady.asStateFlow()

    /** Opt-in: load a user-supplied proprietary `.rodata` (extracted from their own official APK)
     *  to enable the brand browser + experimental clean-room `searchKeyData`. MIT-clean: the app
     *  never ships the binary; the user supplies it. */
    fun loadRodata(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val input = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    getApplication<android.app.Application>().contentResolver.openInputStream(uri)
                } ?: run { _status.value = "Could not open that file."; return@launch }
                withContext(kotlinx.coroutines.Dispatchers.IO) { brandDb.load(input) }
                _codecReady.value = nativeCodec.isLoaded || brandDb.isLoaded()
                _status.value = if (brandDb.isLoaded())
                    "Brand-DB .rodata loaded — brand browser + experimental prebuilt codes available."
                else "Brand-DB load failed."
            } catch (e: Exception) {
                android.util.Log.e("OpenIR/VM", "loadRodata failed", e)
                _status.value = "Brand-DB load failed: ${e.message}"
            }
        }
    }

    fun connectIo() {
        viewModelScope.launch {
            _status.value = "Opening built-in IR (/dev/hxd_irda)…"
            val r = blaster.connectIO()
            _status.value = if (r is dev.ilyaask.openir.transport.OpenResult.Success)
                "Connected: built-in IR" else "Failed: ${(r as dev.ilyaask.openir.transport.OpenResult.Failure).reason}"
            store.setTransport("io")
        }
    }

    fun sendPrebuilt(deviceType: Int, brandIndex: Int, keyCode: Int) {
        viewModelScope.launch {
            val r = blaster.sendKey(deviceType, brandIndex, keyCode)
            _status.value = when (r) { is SendResult.Sent -> "Sent"; is SendResult.Error -> "Send failed: ${r.msg}" }
        }
    }

    fun sendLearned(key: RemoteKey) {
        viewModelScope.launch {
            val r = blaster.sendLearned(key)
            _status.value = when (r) { is SendResult.Sent -> "Replayed"; is SendResult.Error -> "Replay failed: ${r.msg}" }
        }
    }

    fun learn(longCapture: Boolean) {
        viewModelScope.launch {
            _status.value = "Point remote at blaster and press a key…"
            val r = blaster.learn(longCapture)
            _lastLearn.value = r
            _status.value = when (r) {
                is LearnResult.Captured -> "Captured ${r.raw.size} bytes"
                is LearnResult.Error -> "Learn failed: ${r.msg}"
            }
        }
    }

    fun addRemote(cls: DeviceClass, name: String) {
        viewModelScope.launch {
            val r = Remote(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                deviceType = cls.code,
                keys = cls.keys.map { RemoteKey(it.code, it.name, learnedHex = null) },
            )
            store.saveRemotes(remotes.value + r)
        }
    }

    /** Brand list for a device class (universal-remote browser). Empty if no codec DB loaded. */
    fun brandsFor(deviceType: Int): List<BrandInfo> = blaster.brands(deviceType)

    /** Create a remote bound to a specific prebuilt brand (universal-remote default flow). */
    fun addRemoteWithBrand(cls: DeviceClass, brandIndex: Int, name: String) {
        viewModelScope.launch {
            val r = Remote(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                deviceType = cls.code,
                brandIndex = brandIndex,
                keys = cls.keys.map { RemoteKey(it.code, it.name, learnedHex = null) },
            )
            store.saveRemotes(remotes.value + r)
        }
    }

    fun saveLearnedOnKey(remoteId: String, keyCode: Int, raw: ByteArray) {
        viewModelScope.launch {
            val hex = raw.joinToString("") { "%02x".format(it) }
            val list = remotes.value.map { r ->
                if (r.id != remoteId) r else r.copy(keys = r.keys.map { k ->
                    if (k.code != keyCode) k else k.copy(learnedHex = hex)
                })
            }
            store.saveRemotes(list)
        }
    }

    // ---- Debug / validation screen state ----
    private val _debugBusy = MutableStateFlow(false)
    val debugBusy: StateFlow<Boolean> = _debugBusy.asStateFlow()

    private val _debugMsg = MutableStateFlow("Not connected. Open the blaster first.")
    val debugMsg: StateFlow<String> = _debugMsg.asStateFlow()

    private val _debugLong = MutableStateFlow(true)
    val debugLong: StateFlow<Boolean> = _debugLong.asStateFlow()

    /** 110B A/B switch: true = conditioned (Dsp110 DSP), false = unconditioned baseline. */
    private val _debugConditioned = MutableStateFlow(true)
    val debugConditioned: StateFlow<Boolean> = _debugConditioned.asStateFlow()

    private val _debugLastRaw = MutableStateFlow<ByteArray?>(null)
    val debugLastRaw: StateFlow<ByteArray?> = _debugLastRaw.asStateFlow()

    private val _debugDebug = MutableStateFlow<StudyDebug?>(null)
    val debugDebug: StateFlow<StudyDebug?> = _debugDebug.asStateFlow()

    /** Byte-diff of the vendor codec's frame vs our clean codec's frame for the last capture. */
    data class NativeDiff(
        val vendor: ByteArray,
        val clean: ByteArray?,
        val mismatches: List<Int>,
        val note: String,
    )

    private val _debugNativeDiff = MutableStateFlow<NativeDiff?>(null)
    val debugNativeDiff: StateFlow<NativeDiff?> = _debugNativeDiff.asStateFlow()

    private val _debugConnected = MutableStateFlow(false)
    val debugConnected: StateFlow<Boolean> = _debugConnected.asStateFlow()

    fun debugConnectIo() {
        viewModelScope.launch {
            _debugBusy.value = true
            _debugMsg.value = "Opening /dev/hxd_irda…"
            val r = blaster.connectIO()
            _debugConnected.value = r is dev.ilyaask.openir.transport.OpenResult.Success
            _debugMsg.value = if (_debugConnected.value) "Connected to built-in IR. Ready to learn." else "Open failed: ${(r as dev.ilyaask.openir.transport.OpenResult.Failure).reason}"
            _debugBusy.value = false
        }
    }

    fun debugSetLongCapture(value: Boolean) { _debugLong.value = value }

    fun debugSetConditioned(value: Boolean) {
        _debugConditioned.value = value
        debugReencode()
    }

    /** Load the saved pristine 110B capture (replay without the physical remote). */
    fun debugLoadSavedCapture() {
        val raw = dev.ilyaask.openir.codec.DebugCaptures.real110.copyOf()
        _debugLastRaw.value = raw
        debugReencode()
    }

    /** Re-run the codec on the last capture with the current A/B mode (no new learn needed). */
    fun debugReencode() {
        val raw = _debugLastRaw.value ?: return
        val dbg = encodeForDebug(raw)
        if (dbg == null) {
            _debugMsg.value = "Captured ${raw.size} B, but clean codec can't encode this length yet."
            _debugDebug.value = null
        } else {
            _debugDebug.value = dbg
            _debugMsg.value = msgFor(raw, dbg)
        }
        computeNativeDiff(raw)
    }

    // Codecs mutate their input in place — always hand them a private copy so
    // _debugLastRaw stays the pristine capture for re-encodes (A/B toggle) and diffs.
    private fun encodeForDebug(raw: ByteArray): StudyDebug? = when (raw.size) {
        110 -> if (_debugConditioned.value) cleanCodec.studyKeyCodeDebugUngated110(raw.copyOf()) else cleanCodec.experimentalShortFrame(raw.copyOf())
        230 -> cleanCodec.studyKeyCodeDebug(raw.copyOf())
        else -> null
    }

    /**
     * Offline ground-truth check: run the same capture through the bundled vendor codec
     * (`libet_jni_ir_tools.so`) and our clean codec, then byte-diff the frames. Byte-equality
     * proves the clean-room codec correct without any AC-aiming. Everything is logged to
     * logcat (tag OpenIR/NativeDiff) so the result can be read over adb.
     */
    private fun computeNativeDiff(raw: ByteArray) {
        if (!nativeCodec.isLoaded) {
            _debugNativeDiff.value = null
            android.util.Log.w("OpenIR/NativeDiff", "vendor codec not loaded — cannot diff")
            return
        }
        // Both codecs mutate their input buffer in place — give each its own copy.
        val vendor = try { nativeCodec.studyKeyCode(raw.copyOf()) } catch (t: Throwable) {
            android.util.Log.w("OpenIR/NativeDiff", "vendor codec threw: $t"); null
        }
        // Diff with a throwaway codec instance: encoding advances the stateful
        // toggle bit, and an invisible extra encode here would keep the TX-path
        // codec's toggle from alternating between transmits.
        val diffCodec = dev.ilyaask.openir.codec.CleanIrCodec()
        val clean = try {
            if (raw.size == 110 && !_debugConditioned.value)
                diffCodec.experimentalShortFrame(raw.copyOf())?.frame
            else if (raw.size == 110)
                diffCodec.studyKeyCodeDebugUngated110(raw.copyOf())?.frame // always diff-able
            else
                diffCodec.studyKeyCode(raw.copyOf())
        } catch (t: Throwable) {
            android.util.Log.w("OpenIR/NativeDiff", "clean codec threw: $t"); null
        }

        if (vendor == null) {
            _debugNativeDiff.value = null
            android.util.Log.w("OpenIR/NativeDiff", "vendor codec returned null for ${raw.size}B capture")
            return
        }
        val mismatches = if (clean == null) emptyList() else {
            val n = minOf(vendor.size, clean.size)
            (0 until n).filter { vendor[it] != clean[it] } +
                if (vendor.size != clean.size) listOf(-1) else emptyList() // -1 = length mismatch
        }
        val note = when {
            clean == null -> "clean codec produced no frame (110B sanity gate rejected it, or unsupported size)"
            mismatches.isEmpty() -> "MATCH: clean codec is byte-identical to vendor codec"
            mismatches.size == 1 && clean != null && mismatches[0] >= 0 &&
                ((vendor[mismatches[0]].toInt() xor clean[mismatches[0]].toInt()) == 0x10) ->
                "single 0x10 diff at byte ${mismatches[0]} — likely toggle-bit state desync, not a codec bug"
            else -> "MISMATCH at ${mismatches.size} byte(s)"
        }
        _debugNativeDiff.value = NativeDiff(vendor, clean, mismatches, note)
        android.util.Log.i("OpenIR/NativeDiff",
            "size=${raw.size} mode=${if (raw.size == 110 && !_debugConditioned.value) "unconditioned" else "clean"} " +
                "vendor=${vendor.joinToString("") { "%02x".format(it) }} " +
                "clean=${clean?.joinToString("") { "%02x".format(it) } ?: "null"} " +
                "mismatches=$mismatches note=$note")
    }

    private fun msgFor(raw: ByteArray, dbg: StudyDebug): String {
        val base = if (raw.size == 110 && !_debugConditioned.value)
            "Captured ${raw.size} B -> UNCONDITIONED frame ${dbg.frame.size} B (A/B baseline)."
        else if (raw.size == 110)
            "Captured ${raw.size} B -> CONDITIONED frame ${dbg.frame.size} B (Dsp110, UNVALIDATED)."
        else
            "Captured ${raw.size} B -> frame ${dbg.frame.size} B."
        val gate = if (raw.size == 110) {
            val pass = cleanCodec.framePassesSanityGate(dbg.frame)
            " Sanity gate: ${if (pass) "PASS (native would accept)" else "FAIL (native would zero — frame is for A/B test only)"}."
        } else ""
        return "$base$gate Transmit & observe whether the device reacts."
    }

    fun debugLearn() {
        if (!_debugConnected.value) { _debugMsg.value = "Connect the blaster first."; return }
        viewModelScope.launch {
            _debugBusy.value = true
            _debugDebug.value = null
            _debugNativeDiff.value = null
            val long = _debugLong.value
            _debugMsg.value = if (long) "Enter learn mode (230B). Point remote, press a key…" else "Enter learn mode (110B). Point remote, press a key…"
            val r = blaster.learn(long)
            when (r) {
                is LearnResult.Error -> { _debugMsg.value = "Learn failed: ${r.msg}"; _debugBusy.value = false }
                is LearnResult.Captured -> {
                    android.util.Log.i("OpenIR/Capture",
                        "long=$long size=${r.raw.size} hex=${r.raw.joinToString("") { "%02x".format(it) }}")
                    _debugLastRaw.value = r.raw
                    val dbg = encodeForDebug(r.raw)
                    if (dbg == null) {
                        _debugMsg.value = "Captured ${r.raw.size} B, but clean codec can't encode this length yet."
                    } else {
                        _debugDebug.value = dbg
                        _debugMsg.value = msgFor(r.raw, dbg)
                    }
                    computeNativeDiff(r.raw)
                    _debugBusy.value = false
                }
            }
        }
    }

    fun debugTransmit() {
        // Re-encode on every transmit: the codec's toggle bit flips per encode,
        // matching how real remotes (and the vendor lib) alternate per key press.
        // Devices ignore repeats whose toggle matches the last press they saw.
        if (_debugLastRaw.value != null) debugReencode()
        val frame = _debugDebug.value?.frame ?: run { _debugMsg.value = "Nothing to transmit."; return }
        viewModelScope.launch {
            _debugBusy.value = true
            _debugMsg.value = "Transmitting ${frame.size} B…"
            val r = blaster.writeRaw(frame)
            _debugMsg.value = when (r) {
                is SendResult.Sent -> "Transmitted (toggle alternates per tap). Did the device react? If not, tap Transmit again."
                is SendResult.Error -> "Transmit failed: ${r.msg}"
            }
            _debugBusy.value = false
        }
    }

    fun debugClear() {
        _debugDebug.value = null
        _debugNativeDiff.value = null
        _debugLastRaw.value = null
        _debugMsg.value = if (_debugConnected.value) "Cleared. Ready to learn." else "Not connected."
    }

    // ---- Universal-TX: import an open IR file (Flipper .ir / LIRC .conf / Pronto / raw) ----
    private val _importSignals = MutableStateFlow<List<IrSignal>>(emptyList())
    val importSignals: StateFlow<List<IrSignal>> = _importSignals.asStateFlow()

    private val _importIdx = MutableStateFlow(0)
    val importIdx: StateFlow<Int> = _importIdx.asStateFlow()

    private val _importMsg = MutableStateFlow("Import a .ir / .conf / Pronto / raw-timings file.")
    val importMsg: StateFlow<String> = _importMsg.asStateFlow()

    private val _importBusy = MutableStateFlow(false)
    val importBusy: StateFlow<Boolean> = _importBusy.asStateFlow()

    /** Parse an imported IR file's text into one or more signals. */
    fun importIrText(text: String, filename: String? = null) {
        val signals = IrSignalParser.parse(text, filename)
        _importSignals.value = signals
        _importIdx.value = 0
        _importMsg.value = when {
            signals.isEmpty() -> "No IR codes found in ${filename ?: "file"}."
            else -> "Parsed ${signals.size} code(s) from ${filename ?: "file"}. " +
                "Signal #1: ${signals[0].frequencyHz} Hz, ${signals[0].timings.size} edges. " +
                "Transmit to test (encoder header/unit are UNVALIDATED — see docs)."
        }
    }

    fun importSelect(i: Int) {
        if (i in _importSignals.value.indices) _importIdx.value = i
    }

    /** Encode the selected signal and transmit it over the open transport. */
    fun importTransmit(unitUs: Int = BigmeFrameEncoder.DEFAULT_UNIT_US) {
        val signals = _importSignals.value
        val idx = _importIdx.value
        if (signals.isEmpty()) { _importMsg.value = "Import a file first."; return }
        val signal = signals[idx]
        viewModelScope.launch {
            _importBusy.value = true
            val frame = BigmeFrameEncoder.encode(signal, unitUs)
            if (frame == null) {
                _importMsg.value = "Signal too long for one frame (${signal.timings.size} edges); splitting not implemented yet."
                _importBusy.value = false; return@launch
            }
            _importMsg.value = "Transmitting signal #${idx + 1} (${frame.size} B)…"
            val r = blaster.writeRaw(frame)
            _importMsg.value = when (r) {
                is SendResult.Sent -> "Transmitted signal #${idx + 1}. Did the device react? " +
                    "If not, the unit/header need calibration (one learned capture fixes it)."
                is SendResult.Error -> "Transmit failed: ${r.msg}"
            }
            _importBusy.value = false
        }
    }

    fun importClear() {
        _importSignals.value = emptyList(); _importIdx.value = 0
        _importMsg.value = "Import a .ir / .conf / Pronto / raw-timings file."
    }

    // ---- Open IRDB catalog (browse a folder of open IR files) ----
    private val _catalog = MutableStateFlow(dev.ilyaask.openir.codec.IrdbCatalog.Catalog(emptyMap()))
    val catalog: StateFlow<dev.ilyaask.openir.codec.IrdbCatalog.Catalog> = _catalog.asStateFlow()

    private val _catalogMsg = MutableStateFlow("Pick a folder of open IR files to browse.")
    val catalogMsg: StateFlow<String> = _catalogMsg.asStateFlow()

    private val _catalogBusy = MutableStateFlow(false)
    val catalogBusy: StateFlow<Boolean> = _catalogBusy.asStateFlow()

    /**
     * Walk a SAF directory tree [uri] (from OpenDocumentTree), parse every `.ir`/`.conf`/`.txt`
     * file, and build a browseable [dev.ilyaask.openir.codec.IrdbCatalog]. Runs on IO; updates
     * [catalog] and [catalogMsg] when done.
     */
    fun loadCatalogFromTree(uri: android.net.Uri) {
        viewModelScope.launch {
            _catalogBusy.value = true
            _catalogMsg.value = "Scanning folder…"
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val seq = walkTreeUri(getApplication(), uri)
                    val cat = dev.ilyaask.openir.codec.IrdbCatalog.build(seq)
                    cat to (if (cat.isEmpty) "No parsable IR files found in that folder."
                    else "Loaded ${cat.fileCount} files across ${cat.categories.size} categories. Tap a category.")
                } catch (e: Exception) {
                    android.util.Log.e("OpenIR/Catalog", "scan failed", e)
                    dev.ilyaask.openir.codec.IrdbCatalog.Catalog(emptyMap()) to "Scan failed: ${e.message}"
                }
            }
            _catalog.value = result.first
            _catalogMsg.value = result.second
            _catalogBusy.value = false
        }
    }

    /** Transmit a selected catalog signal (category/brand/fileIdx/signalIdx). */
    fun catalogTransmit(category: String, brand: String, fileIdx: Int, signalIdx: Int) {
        val file = _catalog.value.files(category, brand).getOrNull(fileIdx) ?: run {
            _catalogMsg.value = "Selection lost — re-pick."; return
        }
        val signal = file.signals.getOrNull(signalIdx) ?: run {
            _catalogMsg.value = "No signal selected."; return
        }
        viewModelScope.launch {
            _catalogBusy.value = true
            _catalogMsg.value = "Transmitting ${file.model} #${signalIdx + 1}…"
            val frame = dev.ilyaask.openir.codec.BigmeFrameEncoder.encode(signal)
            if (frame == null) {
                _catalogMsg.value = "Signal too long for one frame (${signal.timings.size} edges); splitting not implemented."
                _catalogBusy.value = false; return@launch
            }
            val r = blaster.writeRaw(frame)
            _catalogMsg.value = when (r) {
                is SendResult.Sent -> "Transmitted ${file.model} #${signalIdx + 1}. Did the device react? " +
                    "(encoder header/unit are UNVALIDATED — one learned capture calibrates it.)"
                is SendResult.Error -> "Transmit failed: ${r.msg}"
            }
            _catalogBusy.value = false
        }
    }
}

/**
 * Recursively walk a SAF document tree [uri], emitting (path parts, file text) for every file
 * whose name looks like an IR file. Path parts are the folder names from the tree root down to
 * the file (so `tv/LG/KM.ir` -> `["tv","LG","KM.ir"]`).
 */
private suspend fun walkTreeUri(
    context: android.content.Context,
    uri: android.net.Uri,
): Sequence<Pair<List<String>, String>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val out = mutableListOf<Pair<List<String>, String>>()
    val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri) ?: return@withContext emptyList<Pair<List<String>, String>>().asSequence()
    fun walk(df: androidx.documentfile.provider.DocumentFile, parts: List<String>) {
        for (child in df.listFiles()) {
            if (child.isDirectory) walk(child, parts + child.name.orEmpty())
            else if (child.isFile && child.name?.let { isIrFile(it) } == true) {
                val text = runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                if (text != null) out.add((parts + child.name.orEmpty()) to text)
            }
        }
    }
    walk(tree, emptyList())
    out.asSequence()
}

private fun isIrFile(name: String): Boolean {
    val l = name.lowercase()
    return l.endsWith(".ir") || l.endsWith(".conf") || l.endsWith(".txt") || l.endsWith(".pronto") || l.endsWith(".hex")
}
