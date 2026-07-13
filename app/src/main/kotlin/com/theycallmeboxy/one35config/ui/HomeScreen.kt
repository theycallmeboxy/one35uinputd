package com.theycallmeboxy.one35config.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: EditorViewModel,
    onEdit: () -> Unit,
    onProfiles: () -> Unit,
    onLog: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("One35 Config") },
                actions = {
                    IconButton(onClick = { vm.refreshEnvironment() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh status")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(vm)

            if (vm.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text("Working…")
                }
            }

            Button(
                onClick = { vm.loadActive() },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Load active config") }

            FilledTonalButton(
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Edit config") }

            Button(
                onClick = { vm.apply() },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apply & reload") }

            OutlinedButton(onClick = onProfiles, modifier = Modifier.fillMaxWidth()) { Text("Profiles") }
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
            OutlinedButton(
                onClick = { vm.refreshLog(); onLog() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("View log") }
        }
    }
}

@Composable
private fun StatusCard(vm: EditorViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusRow("Root access", boolLabel(vm.rootAvailable, yes = "Granted", no = "Denied"))
            StatusRow("Module installed", boolLabel(vm.moduleInstalled, yes = "Yes", no = "No"))
            val st = vm.status
            StatusRow("Daemon", if (st?.running == true) "Running (pid ${st.pid})" else "Not running")
            StatusRow("Active layer", st?.activeLayer?.toString() ?: "—")
            StatusRow("Orientation", st?.orientation ?: "—")
            StatusRow("Layers in editor", vm.config.layers.size.toString())
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun boolLabel(value: Boolean?, yes: String, no: String): String =
    when (value) {
        true -> yes
        false -> no
        null -> "Checking…"
    }
