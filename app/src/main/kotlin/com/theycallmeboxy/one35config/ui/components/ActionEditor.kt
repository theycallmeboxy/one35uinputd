package com.theycallmeboxy.one35config.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theycallmeboxy.one35config.data.Catalog
import com.theycallmeboxy.one35config.data.Labeled
import com.theycallmeboxy.one35config.data.model.Action

/** A sensible default Action for a freshly-selected type (first option for its fields). */
fun defaultActionFor(type: String): Action = when (type) {
    "gamepad_key" -> Action(type, code = Catalog.gamepadKeys.first().value)
    "android_key" -> Action(type, code = Catalog.androidKeys.first().value)
    "gamepad_axis" -> Action(type, code = Catalog.axisCodes.first().value, value = 32767)
    "mouse_btn" -> Action(type, code = Catalog.mouseButtons.first().value)
    "mouse_scroll" -> Action(type, value = Catalog.scrollDirections.first().value)
    "layer_tg" -> Action(type, code = Catalog.targetLayers.first().value)
    "layer_lt" -> Action(type, code = Catalog.targetLayers.first().value)
    else -> Action(type) // orient_tg, none
}

/**
 * Edits one [Action]: a type dropdown followed by only the fields that type uses.
 * [allowLayerLt] adds the hold-only `layer_lt` type (used by the hold editor).
 */
@Composable
fun ActionEditor(
    action: Action,
    onChange: (Action) -> Unit,
    modifier: Modifier = Modifier,
    allowLayerLt: Boolean = false,
) {
    val types = Catalog.actionTypes
        .filter { allowLayerLt || it.validAsTap }
        .map { Labeled(it.type, it.label) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledDropdown(
            label = "Action",
            options = types,
            selected = action.type,
            onSelect = { newType -> if (newType != action.type) onChange(defaultActionFor(newType)) },
        )

        when (action.type) {
            "gamepad_key" -> CodeDropdown("Button", Catalog.gamepadKeys, action) { onChange(it) }
            "android_key" -> CodeDropdown("Key", Catalog.androidKeys, action) { onChange(it) }
            "mouse_btn" -> CodeDropdown("Mouse button", Catalog.mouseButtons, action) { onChange(it) }
            "layer_tg" -> CodeDropdown("Target layer", Catalog.targetLayers, action) { onChange(it) }
            "layer_lt" -> CodeDropdown("Momentary layer", Catalog.targetLayers, action) { onChange(it) }
            "mouse_scroll" -> LabeledDropdown(
                label = "Direction",
                options = Catalog.scrollDirections,
                selected = action.value,
                onSelect = { onChange(action.copy(value = it)) },
            )
            "gamepad_axis" -> {
                CodeDropdown("Axis", Catalog.axisCodes, action) { onChange(it) }
                IntField(
                    label = "Value (−32767..32767)",
                    value = action.value ?: 0,
                    onChange = { onChange(action.copy(value = it.coerceIn(-32767, 32767))) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // orient_tg, none: no extra fields.
        }
    }
}

@Composable
private fun CodeDropdown(
    label: String,
    options: List<Labeled<Int>>,
    action: Action,
    onChange: (Action) -> Unit,
) {
    LabeledDropdown(
        label = label,
        options = options,
        selected = action.code,
        onSelect = { onChange(action.copy(code = it)) },
    )
}
