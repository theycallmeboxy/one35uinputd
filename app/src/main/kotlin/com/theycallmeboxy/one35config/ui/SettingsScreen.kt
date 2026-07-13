package com.theycallmeboxy.one35config.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theycallmeboxy.one35config.data.model.Global
import com.theycallmeboxy.one35config.data.model.Mouse
import com.theycallmeboxy.one35config.ui.components.IntField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: EditorViewModel, onBack: () -> Unit) {
    val global = vm.config.global
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Timing", style = MaterialTheme.typography.titleMedium)
            IntField(
                label = "LT hold threshold (ms)",
                value = global.ltHoldMs,
                onChange = { vm.updateGlobal(global.copy(ltHoldMs = it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            IntField(
                label = "Haptics (ms, 0 = off)",
                value = global.hapticsMs ?: 0,
                onChange = { vm.updateGlobal(global.copy(hapticsMs = it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            IntField(
                label = "Back hold cap (ms, 0 = off) — prevents held BACK → HOME",
                value = global.backHoldCapMs ?: 300,
                onChange = { vm.updateGlobal(global.copy(backHoldCapMs = it)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Mouse", style = MaterialTheme.typography.titleMedium)
            val mouse = global.mouse ?: Mouse()
            fun setMouse(m: Mouse) = vm.updateGlobal(global.copy(mouse = m))
            IntField("Dead zone %", mouse.deadZonePct, { setMouse(mouse.copy(deadZonePct = it)) }, Modifier.fillMaxWidth())
            IntField("Speed %", mouse.speedPct, { setMouse(mouse.copy(speedPct = it)) }, Modifier.fillMaxWidth())
            IntField("Acceleration %", mouse.accelPct, { setMouse(mouse.copy(accelPct = it)) }, Modifier.fillMaxWidth())
            IntField("Accel zone %", mouse.accelZonePct, { setMouse(mouse.copy(accelZonePct = it)) }, Modifier.fillMaxWidth())
        }
    }
}
