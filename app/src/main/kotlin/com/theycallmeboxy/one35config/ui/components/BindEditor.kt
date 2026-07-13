package com.theycallmeboxy.one35config.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theycallmeboxy.one35config.data.Catalog
import com.theycallmeboxy.one35config.data.model.Action
import com.theycallmeboxy.one35config.data.model.Bind

/**
 * Edits a single [Bind]: the tap action, plus an optional layer-tap hold (momentary layer).
 * Hold is always `layer_lt`, so it collapses to just a target-layer picker.
 */
@Composable
fun BindEditor(
    bind: Bind,
    onChange: (Bind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionEditor(action = bind.tap, onChange = { onChange(bind.copy(tap = it)) })

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hold for momentary layer", modifier = Modifier.weight(1f))
            Switch(
                checked = bind.hold != null,
                onCheckedChange = { on ->
                    onChange(bind.copy(hold = if (on) defaultActionFor("layer_lt") else null))
                },
            )
        }
        bind.hold?.let { hold ->
            LabeledDropdown(
                label = "Momentary layer",
                options = Catalog.targetLayers,
                selected = hold.code,
                onSelect = { onChange(bind.copy(hold = Action("layer_lt", code = it))) },
            )
        }
    }
}
