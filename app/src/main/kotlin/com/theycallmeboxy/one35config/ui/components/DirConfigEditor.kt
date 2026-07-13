package com.theycallmeboxy.one35config.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theycallmeboxy.one35config.data.Catalog
import com.theycallmeboxy.one35config.data.Labeled
import com.theycallmeboxy.one35config.data.model.Action
import com.theycallmeboxy.one35config.data.model.DirConfig

/**
 * Edits one directional input (d-pad or joystick): its function, per-direction actions when
 * in button_mode, and an optional rotation override. [functions] differs by slot type
 * (joysticks lack button_mode).
 */
@Composable
fun DirConfigEditor(
    title: String,
    config: DirConfig?,
    functions: List<Labeled<String>>,
    onChange: (DirConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = config ?: DirConfig(function = functions.first().value)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)

            LabeledDropdown(
                label = "Function",
                options = functions,
                selected = current.function,
                onSelect = { onChange(current.copy(function = it)) },
            )

            if (current.function == "button_mode") {
                DirAction("Up", current.up) { onChange(current.copy(up = it)) }
                DirAction("Down", current.down) { onChange(current.copy(down = it)) }
                DirAction("Left", current.left) { onChange(current.copy(left = it)) }
                DirAction("Right", current.right) { onChange(current.copy(right = it)) }
            }

            LabeledDropdown(
                label = "Rotation override",
                options = Catalog.rotationOverrides,
                selected = current.rotationOverride,
                onSelect = { onChange(current.copy(rotationOverride = it)) },
            )
        }
    }
}

@Composable
private fun DirAction(label: String, action: Action?, onChange: (Action) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        ActionEditor(action = action ?: defaultActionFor("android_key"), onChange = onChange)
    }
}
