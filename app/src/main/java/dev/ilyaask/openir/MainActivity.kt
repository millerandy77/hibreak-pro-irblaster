package dev.ilyaask.openir

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.ilyaask.openir.data.Remote
import dev.ilyaask.openir.ui.OpenIrTheme
import dev.ilyaask.openir.ui.OpenIrViewModel
import dev.ilyaask.openir.ui.AddRemoteScreen
import dev.ilyaask.openir.ui.CatalogScreen
import dev.ilyaask.openir.ui.DebugScreen
import dev.ilyaask.openir.ui.HomeScreen
import dev.ilyaask.openir.ui.ImportScreen
import dev.ilyaask.openir.ui.RemoteScreen
import dev.ilyaask.openir.ui.TransportScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenIrTheme {
                val nav = rememberNavController()
                val vm: OpenIrViewModel = viewModel()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(vm, nav, onOpenRemote = { nav.navigate("remote/${it.id}") })
                    }
                    composable("transport") {
                        TransportScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable("add") {
                        AddRemoteScreen(vm, onDone = { nav.popBackStack() })
                    }
                    composable("debug") {
                        DebugScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable("import") {
                        ImportScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable("catalog") {
                        CatalogScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable("remote/{id}") { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        val remotes by vm.remotes.collectAsState()
                        val remote = remember(remotes, id) { remotes.firstOrNull { it.id == id } }
                        if (remote != null) RemoteScreen(vm, remote, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
