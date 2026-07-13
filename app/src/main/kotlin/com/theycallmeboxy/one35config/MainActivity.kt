package com.theycallmeboxy.one35config

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.theycallmeboxy.one35config.ui.EditorScreen
import com.theycallmeboxy.one35config.ui.EditorViewModel
import com.theycallmeboxy.one35config.ui.HomeScreen
import com.theycallmeboxy.one35config.ui.LogScreen
import com.theycallmeboxy.one35config.ui.ProfilesScreen
import com.theycallmeboxy.one35config.ui.SettingsScreen
import com.theycallmeboxy.one35config.ui.theme.One35ConfigTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            One35ConfigTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val vm: EditorViewModel = viewModel()
    val nav = rememberNavController()
    val context = LocalContext.current

    // One-shot messages surfaced as toasts, app-wide.
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onEdit = { nav.navigate("editor") },
                onProfiles = { nav.navigate("profiles") },
                onLog = { nav.navigate("log") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("editor") {
            EditorScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable("profiles") {
            ProfilesScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable("log") {
            LogScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
