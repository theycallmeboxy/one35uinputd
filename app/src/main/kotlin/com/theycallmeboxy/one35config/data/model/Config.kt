package com.theycallmeboxy.one35config.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire model — a faithful, 1:1 mirror of the on-disk JSON that the daemon parses
 * (see config.md / daemon/one35uinputd.c parse_* functions).
 *
 * This is NOT what the UI edits. The UI edits the higher-level [com.theycallmeboxy.one35config.data.model.LogicalConfig];
 * ConfigCompiler translates between the two. The daemon's parser is lenient (unknown keys
 * ignored, missing fields fall back to defaults), and the app's Json is configured to omit
 * nulls/defaults, so we only emit what we actually set.
 */
@Serializable
data class Config(
    val global: Global = Global(),
    val layers: List<Layer> = emptyList(),
)

@Serializable
data class Global(
    @SerialName("lt_hold_ms") val ltHoldMs: Int = 390,
    @SerialName("haptics_ms") val hapticsMs: Int? = null,
    // Cap (ms) on how long an emitted BACK is held, to stop the OS turning a held BACK into
    // HOME. null → daemon default (300). 0 disables the cap.
    @SerialName("back_hold_cap_ms") val backHoldCapMs: Int? = null,
    val mouse: Mouse? = null,
)

@Serializable
data class Mouse(
    @SerialName("dead_zone_pct") val deadZonePct: Int = 12,
    @SerialName("speed_pct") val speedPct: Int = 100,
    @SerialName("accel_pct") val accelPct: Int = 100,
    @SerialName("accel_zone_pct") val accelZonePct: Int = 20,
)

@Serializable
data class Layer(
    val landscape: Layout = Layout(),
    val portrait: Layout = Layout(),
)

@Serializable
data class Layout(
    @SerialName("left_dpad") val leftDpad: DirConfig? = null,
    @SerialName("right_dpad") val rightDpad: DirConfig? = null,
    @SerialName("left_joystick") val leftJoystick: DirConfig? = null,
    @SerialName("controller_buttons") val controllerButtons: BindGroup? = null,
    @SerialName("system_buttons") val systemButtons: BindGroup? = null,
) {
    /** Both bind arrays are merged into one lookup table by the daemon; this exposes that. */
    fun allBinds(): List<Bind> =
        (controllerButtons?.binds.orEmpty()) + (systemButtons?.binds.orEmpty())
}

@Serializable
data class DirConfig(
    val function: String? = null,
    @SerialName("rotation_override") val rotationOverride: String? = null,
    // Only used when function == "button_mode".
    val up: Action? = null,
    val down: Action? = null,
    val left: Action? = null,
    val right: Action? = null,
)

@Serializable
data class BindGroup(
    val binds: List<Bind> = emptyList(),
)

@Serializable
data class Bind(
    val src: Int,
    val tap: Action,
    // Present only for LT (layer-tap) keys; always type "layer_lt".
    val hold: Action? = null,
)

@Serializable
data class Action(
    val type: String,
    val code: Int? = null,
    val value: Int? = null,
)
