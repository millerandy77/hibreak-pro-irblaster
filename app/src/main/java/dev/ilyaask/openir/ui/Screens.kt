package dev.ilyaask.openir.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import dev.ilyaask.openir.data.Remote
import dev.ilyaask.openir.data.RemoteKey
import dev.ilyaask.openir.ir.DeviceClass

@Composable
fun StatusBar(vm: OpenIrViewModel, codecReady: Boolean) {
    val status by vm.status.collectAsState()
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(if (codecReady) "Codec: original lib loaded" else "Codec: clean-room (encoder WIP — learning/DB send unavailable until RE completes)",
                style = MaterialTheme.typography.labelSmall)
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun HomeScreen(
    vm: OpenIrViewModel,
    nav: NavHostController,
    onOpenRemote: (Remote) -> Unit,
) {
    val remotes by vm.remotes.collectAsState()
    val codecReady by vm.codecReady.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("OpenIR") }) }) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            StatusBar(vm, codecReady)
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate("transport") }) { Text("Connect blaster") }
                Button(onClick = { nav.navigate("add") }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Add remote") }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.navigate("debug") }) { Icon(Icons.Filled.BugReport, null); Spacer(Modifier.width(4.dp)); Text("Debug") }
                OutlinedButton(onClick = { nav.navigate("import") }) { Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(4.dp)); Text("Import IR") }
                OutlinedButton(onClick = { nav.navigate("catalog") }) { Icon(Icons.Filled.Search, null); Spacer(Modifier.width(4.dp)); Text("Browse IRDB") }
            }
            if (remotes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No remotes yet. Tap “Add remote”.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
                    items(remotes) { r ->
                        ListItem(
                            headlineContent = { Text(r.name) },
                            supportingContent = { Text("${DeviceClass.fromCode(r.deviceType)?.display ?: "Custom"} · ${r.keys.size} keys") },
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onOpenRemote(r) }
                                .padding(vertical = 2.dp)
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportScreen(vm: OpenIrViewModel, onBack: () -> Unit) {
    val codecReady by vm.codecReady.collectAsState()
    val pickRodata = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadRodata(uri)
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Connect blaster") }) }) { p ->
        Column(Modifier.padding(p).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The Hibreak Pro blasts IR through its built-in emitter:")
            Button(onClick = { vm.connectIo(); onBack() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Memory, null); Spacer(Modifier.width(8.dp))
                Text("Connect built-in IR (/dev/hxd_irda)")
            }
            HorizontalDivider()
            Text("Optional: load your own proprietary brand-DB `.rodata` (extracted from your " +
                "official APK) to enable the brand browser + experimental prebuilt codes. " +
                "The app never ships this binary; you supply it.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { pickRodata.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(8.dp))
                Text(if (codecReady) "Reload brand-DB .rodata" else "Load brand-DB .rodata (opt-in)")
            }
            Button(onClick = onBack) { Text("Back") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRemoteScreen(vm: OpenIrViewModel, onDone: () -> Unit) {
    var cls by remember { mutableStateOf<DeviceClass?>(null) }
    var name by remember { mutableStateOf("") }
    val selectedCls = cls
    val brands = remember(selectedCls) { selectedCls?.let { vm.brandsFor(it.code) } ?: emptyList() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (selectedCls == null) "Add remote" else "Pick brand — ${selectedCls.display}") })
    }) { p ->
        Column(
            Modifier.padding(p).fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selectedCls == null) {
                Text("1. Pick a device type:", style = MaterialTheme.typography.titleSmall)
                DeviceClass.entries.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = false, onClick = { cls = c; name = "My ${c.display}" })
                        Text(c.display)
                    }
                }
            } else {
                // Step 2: brand picker (universal-remote default).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { cls = null }) { Text("Back") }
                }
                Text("2. Pick your brand (try keys after; learn any key that doesn't work):",
                    style = MaterialTheme.typography.titleSmall)
                if (brands.isEmpty()) {
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "No prebuilt brand database is loaded, so universal codes aren't " +
                                "available yet. You can still create a learned-only remote and " +
                                "teach each key from your physical remote.",
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                    Button(onClick = { vm.addRemoteWithBrand(selectedCls, -1, name); onDone() }) {
                        Text("Create learned-only remote")
                    }
                } else {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        itemsIndexed(brands) { _, b ->
                            ListItem(
                                headlineContent = { Text(b.name) },
                                supportingContent = { Text("Brand index ${b.index}") },
                                modifier = Modifier.fillMaxWidth().clickable {
                                    vm.addRemoteWithBrand(selectedCls, b.index, name.ifBlank { "My ${selectedCls.display}" })
                                    onDone()
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                    HorizontalDivider()
                    Text("…or create a learned-only remote:", style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { vm.addRemoteWithBrand(selectedCls, -1, name); onDone() }) {
                        Text("Create learned-only remote")
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteScreen(vm: OpenIrViewModel, remote: Remote, onBack: () -> Unit) {
    val lastLearn by vm.lastLearn.collectAsState()
    var learningForKey by remember { mutableStateOf<RemoteKey?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text(remote.name) }) }) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            StatusBar(vm, vm.codecReady.collectAsState().value)
            LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(8.dp)) {
                items(remote.keys) { key ->
                    val learned = key.learnedHex != null
                    OutlinedButton(
                        onClick = {
                            if (learned) vm.sendLearned(key)
                            else vm.sendPrebuilt(remote.deviceType, remote.brandIndex, key.code)
                        },
                        modifier = Modifier.padding(4.dp).height(64.dp)
                    ) { Text(key.label, maxLines = 1) }
                    TextButton(onClick = {
                        learningForKey = key
                        vm.learn(longCapture = false)
                    }) { Text("learn", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
    // Capture -> save onto the key being learned.
    LaunchedEffect(lastLearn) {
        val res = lastLearn
        if (res is dev.ilyaask.openir.blaster.LearnResult.Captured && learningForKey != null) {
            vm.saveLearnedOnKey(remote.id, learningForKey!!.code, res.raw)
            learningForKey = null
        }
    }
}
