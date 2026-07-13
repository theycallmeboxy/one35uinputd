package com.theycallmeboxy.one35config.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(vm: EditorViewModel, onBack: () -> Unit) {
    // Dialog state: (title, initialText, onConfirm) — null when closed.
    var dialog by remember { mutableStateOf<NameDialog?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        dialog = NameDialog("Save current editor as", "") { vm.saveCurrentAsProfile(it) }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save editor") }
                OutlinedButton(
                    onClick = {
                        dialog = NameDialog("Import active config as", "") { vm.importActiveToProfile(it) }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Import active") }
            }

            if (vm.profiles.isEmpty()) {
                Text("No profiles yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.profiles, key = { it }) { name ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                ListItem(
                                    headlineContent = { Text(name) },
                                    trailingContent = {
                                        Row {
                                            IconButton(onClick = {
                                                dialog = NameDialog("Rename profile", name) { newName ->
                                                    vm.renameProfile(name, newName)
                                                }
                                            }) {
                                                Icon(Icons.Default.Edit, contentDescription = "Rename $name")
                                            }
                                            IconButton(onClick = { vm.deleteProfile(name) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete $name")
                                            }
                                        }
                                    },
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { vm.loadProfileIntoEditor(name) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Load") }
                                    OutlinedButton(
                                        onClick = { vm.applyProfile(name) },
                                        enabled = !vm.busy,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Apply") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    dialog?.let { d ->
        NameInputDialog(
            title = d.title,
            initial = d.initial,
            onDismiss = { dialog = null },
            onConfirm = { text -> d.onConfirm(text); dialog = null },
        )
    }
}

private data class NameDialog(
    val title: String,
    val initial: String,
    val onConfirm: (String) -> Unit,
)

@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
