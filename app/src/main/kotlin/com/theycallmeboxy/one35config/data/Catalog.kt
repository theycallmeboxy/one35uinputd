package com.theycallmeboxy.one35config.data

/**
 * Reference data derived from `getevent -pl /dev/input/event1` (the physical controller) and
 * config.md. Every physical button emits a fixed `src` code regardless of orientation, so the
 * UI just lists these buttons and binds each directly — no remap/logical layer.
 */

enum class ButtonGroup(val label: String) {
    FACE("Face"),
    TATE("Tate shoulders"),
    SHOULDER("Shoulders"),
    MENU("Menu"),
    STICK("Stick"),
    SYSTEM("System"),
}

/**
 * The One35's physical buttons (bindable `src` codes). Codes are fixed by the hardware; TATE_L/
 * TATE_R are real extra shoulders (BTN_Z/BTN_C) usable in any orientation. There is no R3.
 */
enum class PhysicalButton(val code: Int, val label: String, val group: ButtonGroup) {
    A(304, "A", ButtonGroup.FACE),
    B(305, "B", ButtonGroup.FACE),
    X(307, "X", ButtonGroup.FACE),
    Y(308, "Y", ButtonGroup.FACE),
    TATE_L(309, "TATE_L", ButtonGroup.TATE),
    TATE_R(306, "TATE_R", ButtonGroup.TATE),
    L1(310, "L1", ButtonGroup.SHOULDER),
    R1(311, "R1", ButtonGroup.SHOULDER),
    L2(312, "L2", ButtonGroup.SHOULDER),
    R2(313, "R2", ButtonGroup.SHOULDER),
    SELECT(314, "SELECT", ButtonGroup.MENU),
    START(315, "START", ButtonGroup.MENU),
    L3(317, "L3", ButtonGroup.STICK),
    BACK(158, "Back", ButtonGroup.SYSTEM),
    VOLUME_DOWN(114, "Volume −", ButtonGroup.SYSTEM),
    VOLUME_UP(115, "Volume +", ButtonGroup.SYSTEM),
    POWER(116, "Power", ButtonGroup.SYSTEM);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun forCode(code: Int): PhysicalButton? = byCode[code]

        /** All buttons in display order, grouped. */
        fun grouped(): Map<ButtonGroup, List<PhysicalButton>> = entries.groupBy { it.group }
    }
}

/** A (code/value → label) pair for a dropdown that must preserve the raw number. */
data class Labeled<T>(val value: T, val label: String)

object Catalog {

    // ── Directional slots (JSON key → UI label, by real hardware source) ──────
    // Confirmed in daemon/one35uinputd.c: left_dpad=HAT d-pad, left_joystick=left analog
    // stick, right_dpad=digital right d-pad (BTN_DPAD_* keys). No physical right stick.
    const val SLOT_LEFT_DPAD = "left_dpad"
    const val SLOT_RIGHT_DPAD = "right_dpad"
    const val SLOT_LEFT_JOYSTICK = "left_joystick"

    val slotLabels: Map<String, String> = mapOf(
        SLOT_LEFT_DPAD to "Left D-pad",
        SLOT_LEFT_JOYSTICK to "Left stick",
        SLOT_RIGHT_DPAD to "Right D-pad",
    )

    // ── Action types (config.md "Action object") ──────────────────────────────
    data class ActionTypeSpec(
        val type: String,
        val label: String,
        val usesCode: Boolean = false,
        val usesValue: Boolean = false,
        /** valid as a bind `tap` action (layer_lt is hold-only). */
        val validAsTap: Boolean = true,
    )

    val actionTypes: List<ActionTypeSpec> = listOf(
        ActionTypeSpec("none", "None (disabled)"),
        ActionTypeSpec("gamepad_key", "Gamepad button", usesCode = true),
        ActionTypeSpec("android_key", "Key / system", usesCode = true),
        ActionTypeSpec("gamepad_axis", "Analog axis", usesCode = true, usesValue = true),
        ActionTypeSpec("mouse_btn", "Mouse button", usesCode = true),
        ActionTypeSpec("mouse_scroll", "Mouse scroll", usesValue = true),
        ActionTypeSpec("layer_tg", "Toggle layer", usesCode = true),
        ActionTypeSpec("orient_tg", "Rotate screen"),
        ActionTypeSpec("layer_lt", "Layer-tap (hold)", usesCode = true, validAsTap = false),
    )

    fun actionType(type: String?): ActionTypeSpec? = actionTypes.firstOrNull { it.type == type }

    // ── Output gamepad buttons (for gamepad_key) ──────────────────────────────
    // R3 isn't a physical button on the One35, but it's still a valid *output* to emit.
    val gamepadKeys: List<Labeled<Int>> = listOf(
        Labeled(304, "A"), Labeled(305, "B"), Labeled(307, "X"), Labeled(308, "Y"),
        Labeled(310, "L1"), Labeled(311, "R1"), Labeled(312, "L2"), Labeled(313, "R2"),
        Labeled(314, "SELECT"), Labeled(315, "START"), Labeled(317, "L3"), Labeled(318, "R3"),
    )

    // ── Output keys (for android_key) ─────────────────────────────────────────
    val androidKeys: List<Labeled<Int>> = listOf(
        Labeled(103, "D-pad Up"), Labeled(105, "D-pad Left"), Labeled(106, "D-pad Right"),
        Labeled(108, "D-pad Down"), Labeled(114, "Volume Down"), Labeled(115, "Volume Up"),
        Labeled(116, "Power"), Labeled(158, "Back"), Labeled(172, "Home"),
        Labeled(580, "Recent apps"),
    )

    // ── Axis codes (for gamepad_axis) ─────────────────────────────────────────
    val axisCodes: List<Labeled<Int>> = listOf(
        Labeled(0, "Left stick X"), Labeled(1, "Left stick Y"),
        Labeled(2, "Right stick X"), Labeled(5, "Right stick Y"),
    )

    // ── Mouse buttons (for mouse_btn) ─────────────────────────────────────────
    val mouseButtons: List<Labeled<Int>> = listOf(
        Labeled(272, "Left"), Labeled(273, "Right"), Labeled(274, "Middle"),
    )

    // ── mouse_scroll direction (value +1 up / -1 down) ────────────────────────
    val scrollDirections: List<Labeled<Int>> = listOf(
        Labeled(1, "Scroll up"), Labeled(-1, "Scroll down"),
    )

    // ── Directional input functions (config.md) ───────────────────────────────
    // D-pad slots support button_mode; joystick slots do not.
    val dpadFunctions: List<Labeled<String>> = listOf(
        Labeled("none", "None"),
        Labeled("left_dpad", "D-pad (HAT axis)"),
        Labeled("dpad", "D-pad (arrow keys)"),
        Labeled("left_joystick", "Left joystick"),
        Labeled("right_joystick", "Right joystick"),
        Labeled("mouse", "Mouse"),
        Labeled("button_mode", "Per-direction buttons"),
    )

    val joystickFunctions: List<Labeled<String>> = listOf(
        Labeled("none", "None"),
        Labeled("left_joystick", "Left joystick"),
        Labeled("right_joystick", "Right joystick"),
        Labeled("left_dpad", "D-pad (HAT axis)"),
        Labeled("dpad", "D-pad (arrow keys)"),
        Labeled("mouse", "Mouse"),
    )

    // ── rotation_override — null = follow layout orientation ───────────────────
    val rotationOverrides: List<Labeled<String?>> = listOf(
        Labeled(null, "Follow layout"),
        Labeled("landscape", "Always landscape"),
        Labeled("portrait", "Always portrait"),
    )

    /** Layer indices selectable as a target (layer_lt / layer_tg): 1–4. */
    val targetLayers: List<Labeled<Int>> = (1..4).map { Labeled(it, "Layer $it") }

    fun labelForCode(list: List<Labeled<Int>>, code: Int?): String =
        list.firstOrNull { it.value == code }?.label ?: code?.toString() ?: "—"

    /** Short human summary of an action, e.g. "Gamepad A", "Key Back", "Toggle L2", "—". */
    fun describe(action: com.theycallmeboxy.one35config.data.model.Action?): String {
        if (action == null) return "—"
        return when (action.type) {
            "none" -> "—"
            "gamepad_key" -> "Gamepad " + labelForCode(gamepadKeys, action.code)
            "android_key" -> labelForCode(androidKeys, action.code)
            "gamepad_axis" -> "Axis " + labelForCode(axisCodes, action.code) + " " + (action.value ?: 0)
            "mouse_btn" -> "Mouse " + labelForCode(mouseButtons, action.code)
            "mouse_scroll" -> if ((action.value ?: 0) >= 0) "Scroll up" else "Scroll down"
            "layer_tg" -> "Toggle layer ${action.code ?: "?"}"
            "orient_tg" -> "Rotate screen"
            "layer_lt" -> "Hold → layer ${action.code ?: "?"}"
            else -> action.type
        }
    }
}
