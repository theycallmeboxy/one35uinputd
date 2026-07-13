package com.theycallmeboxy.one35config.data

import com.theycallmeboxy.one35config.data.model.Bind
import com.theycallmeboxy.one35config.data.model.BindGroup
import com.theycallmeboxy.one35config.data.model.DirConfig
import com.theycallmeboxy.one35config.data.model.Layout

/**
 * Thin helpers for reading/writing the wire [Layout] by physical button, so the UI can edit the
 * daemon config directly (no logical/remap layer). Binds whose `src` isn't in the roster are
 * left untouched, so hand-written configs never lose data.
 */

/** The current bind for a physical button code, or null if unbound in this layout. */
fun Layout.bindFor(code: Int): Bind? = allBinds().firstOrNull { it.src == code }

/**
 * Return a copy of this layout with [button]'s bind set (or cleared when [bind] is null).
 * System-group buttons go to `system_buttons`, the rest to `controller_buttons` (the daemon
 * merges either way; this just mirrors hand-written files). Any existing bind for the same
 * `src` in either array is replaced.
 */
fun Layout.withBind(button: PhysicalButton, bind: Bind?): Layout {
    val ctrl = controllerButtons?.binds.orEmpty().filterNot { it.src == button.code }.toMutableList()
    val sys = systemButtons?.binds.orEmpty().filterNot { it.src == button.code }.toMutableList()
    if (bind != null) {
        if (button.group == ButtonGroup.SYSTEM) sys += bind else ctrl += bind
    }
    return copy(
        controllerButtons = if (ctrl.isEmpty()) null else BindGroup(ctrl),
        systemButtons = if (sys.isEmpty()) null else BindGroup(sys),
    )
}

/** The directional config for a slot key ([Catalog.SLOT_*]). */
fun Layout.dirFor(slot: String): DirConfig? = when (slot) {
    Catalog.SLOT_LEFT_DPAD -> leftDpad
    Catalog.SLOT_RIGHT_DPAD -> rightDpad
    Catalog.SLOT_LEFT_JOYSTICK -> leftJoystick
    else -> null
}

/** Return a copy with the given directional slot replaced. */
fun Layout.withDir(slot: String, cfg: DirConfig): Layout = when (slot) {
    Catalog.SLOT_LEFT_DPAD -> copy(leftDpad = cfg)
    Catalog.SLOT_RIGHT_DPAD -> copy(rightDpad = cfg)
    Catalog.SLOT_LEFT_JOYSTICK -> copy(leftJoystick = cfg)
    else -> this
}
