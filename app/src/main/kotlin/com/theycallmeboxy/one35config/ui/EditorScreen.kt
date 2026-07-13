package com.theycallmeboxy.one35config.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theycallmeboxy.one35config.data.Catalog
import com.theycallmeboxy.one35config.data.ButtonGroup
import com.theycallmeboxy.one35config.data.PhysicalButton
import com.theycallmeboxy.one35config.data.bindFor
import com.theycallmeboxy.one35config.data.dirFor
import com.theycallmeboxy.one35config.data.model.Action
import com.theycallmeboxy.one35config.data.model.Bind
import com.theycallmeboxy.one35config.data.model.DirConfig
import com.theycallmeboxy.one35config.ui.components.BindEditor
import com.theycallmeboxy.one35config.ui.components.DirConfigEditor

/** What the bottom sheet is currently editing. */
private sealed interface EditTarget {
    data class Button(val button: PhysicalButton) : EditTarget
    data class Dir(val slot: String) : EditTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onSettings: () -> Unit) {
    var layerIndex by remember { mutableIntStateOf(0) }
    var portrait by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val layers = vm.config.layers
    if (layerIndex >= layers.size) layerIndex = (layers.size - 1).coerceAtLeast(0)
    val layer = layers.getOrNull(layerIndex)
    val layout = layer?.let { if (portrait) it.portrait else it.landscape }

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editor", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.apply() }, enabled = !vm.busy) {
                        Icon(Icons.Default.Check, contentDescription = "Apply & reload")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy Landscape → Portrait") },
                                onClick = { vm.copyLandscapeToPortrait(layerIndex); menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = { onSettings(); menuOpen = false },
                            )
                            if (layers.size > 1) {
                                DropdownMenuItem(
                                    text = { Text("Delete layer $layerIndex") },
                                    onClick = { vm.removeLayer(layerIndex); menuOpen = false },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (layout == null) {
            Text("No layers", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Layer chips + orientation toggle in one compact control strip.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    layers.forEachIndexed { i, _ ->
                        FilterChip(
                            selected = i == layerIndex,
                            onClick = { layerIndex = i },
                            label = { Text(if (i == 0) "0·base" else "$i") },
                        )
                    }
                    if (layers.size < EditorViewModel.MAX_LAYERS) {
                        AssistChip(
                            onClick = { vm.addLayer() },
                            label = { Icon(Icons.Default.Add, contentDescription = "Add layer") },
                        )
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                SegmentedButton(
                    selected = !portrait,
                    onClick = { portrait = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Landscape") }
                SegmentedButton(
                    selected = portrait,
                    onClick = { portrait = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Portrait") }
            }

            // Dense grouped grid of buttons + directional slots.
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                fun header(text: String) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                header("Directional")
                items(
                    listOf(
                        Catalog.SLOT_LEFT_DPAD,
                        Catalog.SLOT_LEFT_JOYSTICK,
                        Catalog.SLOT_RIGHT_DPAD,
                    ),
                ) { slot ->
                    val cfg = layout.dirFor(slot)
                    MappingCell(
                        title = Catalog.slotLabels[slot] ?: slot,
                        subtitle = dirSummary(cfg, slot),
                        onClick = { editing = EditTarget.Dir(slot) },
                    )
                }

                PhysicalButton.grouped().forEach { (group, buttons) ->
                    header(group.label)
                    items(buttons) { btn ->
                        MappingCell(
                            title = btn.label,
                            subtitle = Catalog.describe(layout.bindFor(btn.code)?.tap).let { s ->
                                val hold = layout.bindFor(btn.code)?.hold
                                if (hold != null) "$s  · hold L${hold.code}" else s
                            },
                            onClick = { editing = EditTarget.Button(btn) },
                        )
                    }
                }
            }
        }

        // ── edit sheet ─────────────────────────────────────────────────────────
        editing?.let { target ->
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { editing = null }, sheetState = sheetState) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (target) {
                        is EditTarget.Button -> {
                            val btn = target.button
                            Text(
                                "${btn.label}  ·  ${if (portrait) "Portrait" else "Landscape"}",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            val current = layout.bindFor(btn.code) ?: Bind(btn.code, Action("none"))
                            BindEditor(
                                bind = current,
                                onChange = { newBind ->
                                    val store = if (newBind.tap.type == "none") null else newBind
                                    vm.setBind(layerIndex, portrait, btn, store)
                                },
                            )
                        }
                        is EditTarget.Dir -> {
                            val slot = target.slot
                            val functions = if (slot == Catalog.SLOT_LEFT_JOYSTICK) {
                                Catalog.joystickFunctions
                            } else {
                                Catalog.dpadFunctions
                            }
                            DirConfigEditor(
                                title = "${Catalog.slotLabels[slot] ?: slot}  ·  ${if (portrait) "Portrait" else "Landscape"}",
                                config = layout.dirFor(slot),
                                functions = functions,
                                onChange = { cfg: DirConfig -> vm.setDir(layerIndex, portrait, slot, cfg) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Compact tappable cell: bold title + secondary current-mapping line. */
@Composable
private fun MappingCell(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

private fun dirSummary(cfg: DirConfig?, slot: String): String {
    val fn = cfg?.function ?: "none"
    val list = if (slot == Catalog.SLOT_LEFT_JOYSTICK) Catalog.joystickFunctions else Catalog.dpadFunctions
    val label = list.firstOrNull { it.value == fn }?.label ?: fn
    val ro = cfg?.rotationOverride?.let { " · $it" } ?: ""
    return label + ro
}
