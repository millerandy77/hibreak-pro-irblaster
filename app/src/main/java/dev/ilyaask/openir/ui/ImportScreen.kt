package dev.ilyaask.openir.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ilyaask.openir.codec.IrSignal

/**
 * Universal-TX screen: import any open IR code file (Flipper `.ir`, LIRC `.conf`, Pronto Hex,
 * raw timings), parse it, and transmit it through the Bigme blaster. This is the open-format
 * compatibility path — no proprietary DB required. The encoder's payload unit + header are
 * unvalidated until one real capture is compared (see docs §9); transmit here is the on-device
 * validation step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(vm: OpenIrViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val signals by vm.importSignals.collectAsState()
    val idx by vm.importIdx.collectAsState()
    val msg by vm.importMsg.collectAsState()
    val busy by vm.importBusy.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching {
            val name = uri.lastPathSegment
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@runCatching
            vm.importIrText(text, name)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Import & transmit IR") }) }) { p ->
        Column(
            Modifier.padding(p).fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Import any open IR code file and transmit it. Supported: Flipper `.ir`, LIRC " +
                    "`.conf` (raw_codes), Pronto Hex, raw us timings. No proprietary DB needed — " +
                    "this is the open-source compatibility path.",
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Import IR file")
            }

            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Text(msg, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }

            if (signals.isNotEmpty()) {
                SignalList(signals, idx, onSelect = vm::importSelect)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.importTransmit() }, enabled = !busy) {
                        Icon(Icons.Filled.Send, null); Spacer(Modifier.width(8.dp)); Text("Transmit #${idx + 1}")
                    }
                    OutlinedButton(onClick = { vm.importClear() }) { Text("Clear") }
                }
                Text(
                    "Note: the device frame's payload unit (us/unit) and header are calibrated from " +
                        "one real learned capture. If the target doesn't react, that's the expected gap — " +
                        "a single capture fixes it. See docs/REVERSE_ENGINEERING.md §9.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun SignalList(signals: List<IrSignal>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        signals.forEachIndexed { i, s ->
            val sel = i == selected
            FilterChip(
                selected = sel,
                onClick = { onSelect(i) },
                label = {
                    Text("#${i + 1}: ${s.frequencyHz} Hz · ${s.timings.size} edges" +
                        if (s.repeatTimings.isNotEmpty()) " · repeat ${s.repeatTimings.size}" else "")
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
