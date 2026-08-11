package dev.ilyaask.openir.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Browse an imported open IR database (Flipper/LIRC/Pronto/raw) and transmit a selected code.
 * Drilldown: category → brand → file → signal. The user first picks a folder with the system
 * directory picker; the VM walks it and builds an [IrdbCatalog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(vm: OpenIrViewModel, onBack: () -> Unit) {
    val catalog by vm.catalog.collectAsState()
    val msg by vm.catalogMsg.collectAsState()
    val busy by vm.catalogBusy.collectAsState()

    var category by remember { mutableStateOf<String?>(null) }
    var brand by remember { mutableStateOf<String?>(null) }
    var fileIdx by remember { mutableStateOf(0) }
    var signalIdx by remember { mutableStateOf(0) }

    // Reset drilldown when a new catalog is loaded.
    LaunchedEffect(catalog) { category = null; brand = null; fileIdx = 0; signalIdx = 0 }

    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.loadCatalogFromTree(uri)
    }

    val title = buildString {
        append("Browse IRDB")
        category?.let { append(" · $it") }
        brand?.let { append(" · $it") }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { p ->
        Column(Modifier.padding(p).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Button(onClick = { pickDir.launch(null) }, enabled = !busy) {
                    Icon(Icons.Filled.Folder, null); Spacer(Modifier.width(6.dp)); Text("Pick IRDB folder")
                }
            }
            Text(
                if (catalog.isEmpty) "Pick a folder of .ir / .conf / Pronto files (e.g. the Flipper IRDB tree)."
                else "Loaded ${catalog.fileCount} files across ${catalog.categories.size} categories.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

            when {
                catalog.isEmpty -> Unit
                category == null -> {
                    Text("Categories", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(catalog.categories) { c ->
                            ListItem(
                                headlineContent = { Text(c) },
                                supportingContent = { Text("${catalog.brands(c).size} brands") },
                                modifier = Modifier.fillMaxWidth().clickable { category = c }.padding(vertical = 2.dp)
                            )
                            HorizontalDivider()
                        }
                    }
                }
                brand == null -> {
                    Text("Brands in $category", style = MaterialTheme.typography.titleSmall)
                    Row { OutlinedButton(onClick = { category = null }) { Text("Back") } }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(catalog.brands(category!!)) { b ->
                            ListItem(
                                headlineContent = { Text(b) },
                                supportingContent = { Text("${catalog.files(category!!, b).size} files") },
                                modifier = Modifier.fillMaxWidth().clickable { brand = b; fileIdx = 0; signalIdx = 0 }.padding(vertical = 2.dp)
                            )
                            HorizontalDivider()
                        }
                    }
                }
                else -> {
                    val files = catalog.files(category!!, brand!!)
                    val file = files.getOrNull(fileIdx)
                    Text("Models in $category · $brand", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { brand = null }) { Text("Back") }
                        if (file != null) {
                            AssistChip(onClick = { /* info only */ }, label = { Text(file.model) })
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        itemsIndexed(files) { i, f ->
                            ListItem(
                                headlineContent = { Text(f.model) },
                                supportingContent = { Text("${f.signals.size} code(s)") },
                                modifier = Modifier.fillMaxWidth().clickable { fileIdx = i; signalIdx = 0 }.padding(vertical = 2.dp)
                            )
                            HorizontalDivider()
                        }
                    }
                    if (file != null) {
                        Text("Codes:", style = MaterialTheme.typography.labelMedium)
                        file.signalNames.forEachIndexed { i, name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = i == signalIdx, onClick = { signalIdx = i })
                                Text(name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(
                            onClick = { vm.catalogTransmit(category!!, brand!!, fileIdx, signalIdx) },
                            enabled = !busy && file.signals.isNotEmpty()
                        ) { Text("Transmit code #${signalIdx + 1}") }
                    }
                }
            }
        }
    }
}
