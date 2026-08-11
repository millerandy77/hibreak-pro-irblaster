package dev.ilyaask.openir.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ilyaask.openir.codec.StudyDebug

/** Validation/debug screen: capture raw IR from a real remote, run the clean codec, inspect+transmit. */
@Composable
fun DebugScreen(vm: OpenIrViewModel, onBack: () -> Unit) {
    val connected by vm.debugConnected.collectAsState()
    val busy by vm.debugBusy.collectAsState()
    val msg by vm.debugMsg.collectAsState()
    val long by vm.debugLong.collectAsState()
    val conditioned by vm.debugConditioned.collectAsState()
    val dbg by vm.debugDebug.collectAsState()
    val diff by vm.debugNativeDiff.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    Scaffold(topBar = { TopAppBar(title = { Text("Debug / Validate codec") }) }) { p ->
        Column(
            Modifier.padding(p).fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "This screen learns a real IR signal with our app, runs the clean-room encoder, " +
                    "and shows the raw capture next to the emitted frame. Transmit it to see if the " +
                    "target device reacts — that validates the encoder without the official app.",
                style = MaterialTheme.typography.bodySmall
            )

            if (!connected) {
                Button(onClick = { vm.debugConnectIo() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Memory, null); Spacer(Modifier.width(8.dp)); Text("Open built-in IR (/dev/hxd_irda)")
                }
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                FilterChip(selected = long, onClick = { vm.debugSetLongCapture(true) }, label = { Text("230 B (long)") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = !long, onClick = { vm.debugSetLongCapture(false) }, label = { Text("110 B (short)") })
            }

            if (!long) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("110B mode:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = conditioned,
                        onClick = { vm.debugSetConditioned(true) },
                        label = { Text("Conditioned (DSP)") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = !conditioned,
                        onClick = { vm.debugSetConditioned(false) },
                        label = { Text("Unconditioned") },
                    )
                }
                Text(
                    "A/B test: learn once, toggle mode (re-encodes instantly), transmit each. " +
                        "Conditioned = Dsp110 port (UNVALIDATED); Unconditioned = raw bytes wrapped only.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.debugLearn() }, enabled = connected && !busy) { Text("Learn") }
                Button(onClick = { vm.debugTransmit() }, enabled = connected && dbg != null && !busy) { Text("Transmit frame") }
                OutlinedButton(onClick = { vm.debugClear() }, enabled = dbg != null) { Text("Clear") }
            }

            if (!long) {
                OutlinedButton(onClick = { vm.debugLoadSavedCapture() }, enabled = !busy) {
                    Text("Replay saved 110B capture (no remote needed)")
                }
            }

            dbg?.let {
                OutlinedButton(
                    onClick = {
                        val hex = it.frame.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(hex))
                    },
                    enabled = !busy,
                ) { Text("Copy frame hex") }
            }

            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Text(msg, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }

            dbg?.let { DebugHexView(it) }

            diff?.let { NativeDiffView(it) }
        }
    }
}

@Composable
private fun NativeDiffView(diff: OpenIrViewModel.NativeDiff) {
    val matched = diff.clean != null && diff.mismatches.isEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Native vs Clean (offline ground truth)", style = MaterialTheme.typography.titleSmall)
        HexBlock(
            "Vendor codec frame (${diff.vendor.size} B)",
            diff.vendor,
            highlight = diff.mismatches.filter { it >= 0 }.toSet(),
        )
        Text(
            diff.note,
            style = MaterialTheme.typography.bodyMedium,
            color = if (matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        diff.clean?.let {
            Text(
                "clean length=${it.size}  vendor length=${diff.vendor.size}" +
                    if (diff.mismatches.contains(-1)) "  (LENGTH MISMATCH)" else "",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun DebugHexView(dbg: StudyDebug) {
    val changed = remember(dbg) { changedBytes(dbg.raw, dbg.sanitized) }
    val sub = dbg.frame[1].toInt() and 0xff
    val seed = if (sub == 0x03) 0x33 else 0x32
    val layoutDesc = if (sub == 0x03)
        "[0x30][0x03][sanitized[1..229]][checksum@231]"
    else
        "[0x30][0x02][payload[0..108]][checksum@111]"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HexBlock("Raw capture (${dbg.raw.size} B)", dbg.raw, highlight = null)
        HexBlock("Sanitized payload (${dbg.sanitized.size} B)", dbg.sanitized, highlight = changed)
        HexBlock("Emitted frame (${dbg.frame.size} B)", dbg.frame, highlight = null)
        Text(
            "Frame layout: $layoutDesc. " +
                "Checksum = (0x${"%02x".format(seed)} + sum) & 0xFF = 0x${"%02x".format(dbg.frame.last().toInt() and 0xff)}.",
            style = MaterialTheme.typography.labelSmall
        )
        if (changed.isEmpty()) {
            Text("Sanitization changed no bytes.", style = MaterialTheme.typography.labelSmall)
        } else {
            Text(
                "Sanitization changed ${changed.size} byte(s): " + changed.take(40).joinToString { "raw[$it]" } +
                    if (changed.size > 40) " …" else "",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun HexBlock(title: String, bytes: ByteArray, highlight: Set<Int>?) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Text(
                remember(bytes, highlight) { hexDump(bytes, highlight) },
                modifier = Modifier
                    .padding(8.dp)
                    .horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** Canonical hex dump: 16 bytes/row with offset + ASCII. Highlighted offsets get a leading marker. */
private fun hexDump(bytes: ByteArray, highlight: Set<Int>?): String {
    val sb = StringBuilder()
    for (i in bytes.indices step 16) {
        val mark = if (highlight != null && (i until (i + 16).coerceAtMost(bytes.size)).any { it in highlight }) '*' else ' '
        sb.append(mark)
        sb.append(String.format("%04x  ", i))
        for (j in 0 until 16) {
            val idx = i + j
            if (idx < bytes.size) sb.append(String.format("%02x ", bytes[idx].toInt() and 0xff)) else sb.append("   ")
            if (j == 7) sb.append(' ')
        }
        sb.append(' ')
        for (j in 0 until 16) {
            val idx = i + j
            if (idx < bytes.size) {
                val c = bytes[idx].toInt() and 0xff
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
        }
        sb.append('\n')
    }
    return sb.toString().trimEnd()
}

private fun changedBytes(raw: ByteArray, sanitized: ByteArray): Set<Int> {
    val out = LinkedHashSet<Int>()
    for (i in raw.indices) if (raw[i] != sanitized[i]) out.add(i)
    return out
}
