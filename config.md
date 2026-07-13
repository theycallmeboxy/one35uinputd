# one35uinputd — Configuration Reference

## Overview

`one35uinputd` is a Magisk daemon that intercepts the raw input from the MagicX One35's
built-in controller and re-emits it as a virtual gamepad, mouse, and keyboard. It grabs
`/dev/input/event1` exclusively, so all remapping is transparent to games — they see a
clean virtual device, not the underlying hardware.

The daemon supports up to 5 independent layers, each with separate landscape and portrait
layouts. Layers are switched via bind actions — no root shell required during normal use.

---

## Deploying a config

The active config lives at `$MODPATH/one35uinputd.json`. To update it:

1. Copy your new JSON to `/sdcard/one35uinputd.json`
2. Tap the **action button** in Magisk Manager

The daemon restarts and ingests the file from `/sdcard`, which is then moved to the module
directory. Tapping the action button with no file on `/sdcard` resets to the bundled
`default.json`.

Config errors are written to `$MODPATH/one35uinputd.log`. If the file fails to parse, the
daemon falls back to its built-in defaults and logs the parse error with location details.

---

## Top-level structure

```json
{
  "global": { ... },
  "layers": [ ... ]
}
```

Both fields are required. `global` contains daemon-wide settings. `layers` is an ordered
array of layer objects — index 0 is the base layer, always active unless overridden.

---

## `global` block

```json
{
  "global": {
    "lt_hold_ms": 390,
    "haptics_ms": 0,
    "back_hold_cap_ms": 300,
    "mouse": {
      "dead_zone_pct": 12,
      "speed_pct": 100,
      "accel_pct": 100,
      "accel_zone_pct": 20
    }
  }
}
```

### Timing

| Field        | Type | Default | Description |
|--------------|------|---------|-------------|
| `lt_hold_ms` | int  | 390     | Hold threshold for LT keys (milliseconds). Released before this time with no other key pressed: tap fires. Another key pressed while held (before or after the threshold): layer activates immediately. Held past the threshold and released with no other key pressed: nothing happens. |
| `haptics_ms` | int  | 0       | Vibration duration (milliseconds) on layer change events: LT hold activation, TG toggles, and `orient_tg`. Set to 0 to disable. |
| `back_hold_cap_ms` | int | 300 | Maximum time (milliseconds) an emitted `KEY_BACK` is held down before the daemon auto-releases it. The One35 OS converts a long BACK hold into HOME; because that promotion can't be blocked directly, capping the emitted hold below the OS threshold prevents it. BACK still fires immediately on press — only the *held* duration is capped, so holding BACK never reaches the HOME threshold. Set to 0 to disable (pass the physical hold through unchanged). Applies to any bind whose output is `android_key` code 158. |

### Mouse settings

These apply whenever a directional input is set to `"mouse"` function.

| Field            | Type | Default | Description |
|------------------|------|---------|-------------|
| `dead_zone_pct`  | int  | 12      | Stick dead zone as a percentage of the full axis range. Deflection within this radius produces no cursor movement, preventing drift. |
| `speed_pct`      | int  | 100     | Base cursor speed as a percentage of full-speed. 100 = default, 200 = 2× faster. |
| `accel_pct`      | int  | 100     | Speed multiplier applied at maximum stick deflection. 100 = linear (no acceleration). 200 = 2× at full throw. The multiplier ramps smoothly from 1.0 at the edge of the accel zone up to this value. |
| `accel_zone_pct` | int  | 20      | Percentage of post-dead-zone travel that remains linear before acceleration begins. A higher value gives a larger precision zone before speed ramps up. |

---

## Layer system

The daemon supports up to 5 layers (indices 0–4). At any moment, exactly one layer is
active, determined by priority:

1. **Momentary layer** (LT hold) — highest priority. Active only while the LT key is held.
2. **Toggled layer** (TG) — set by toggle actions. Persists until toggled off.
3. **Layer 0** — the base layer, active when neither of the above applies.

Layers are **opaque**: if a key has no bind in the currently active layer, the event is
dropped. It does not fall through to layer 0. Every key you want to work in a non-zero
layer must be explicitly bound in that layer.

### Layer interaction rules

- **TG while on a TG layer** — there is no stack. `layer_tg` sets a single toggled-layer value. Toggling to layer 2 while on layer 1 replaces the toggle; layer 1 is abandoned. Toggling layer 2 again returns to layer 0, not layer 1.
- **LT while on a TG layer** — works as expected. The momentary layer takes priority while the LT key is held, then drops back to the active TG layer on release.
- **LT while already holding an LT key** — the second LT key does not nest. It is dispatched as a normal tap in the current momentary layer (if bound there). Only one LT key can be active at a time.

Each layer object has a `landscape` and a `portrait` layout. The daemon automatically
selects the correct layout based on Android's `user_rotation` system setting, which is
monitored via inotify and updated in real time.

```json
{
  "landscape": { ... layout ... },
  "portrait":  { ... layout ... }
}
```

---

## Layout object

Each layout defines how the three directional inputs behave and maps all button presses.

```json
{
  "left_dpad":          { ... directional config ... },
  "right_dpad":         { ... directional config ... },
  "left_joystick":      { ... directional config ... },
  "controller_buttons": { "binds": [ ... ] },
  "system_buttons":     { "binds": [ ... ] }
}
```

`controller_buttons` and `system_buttons` are organizational only — both arrays are merged
into the same bind lookup table. The split exists for readability in complex configs.

---

## Directional input config

### D-pad (`left_dpad`, `right_dpad`)

`left_dpad` and `dpad` both produce directional output but use different protocols — `left_dpad` emits HAT axis events (standard for gamepad d-pads), `dpad` emits digital key events. Use `left_dpad` when targeting games that read ABS_HAT0X/Y; use `dpad` for UI navigation or apps that expect arrow keys.

| `function`       | Output                                          |
|------------------|-------------------------------------------------|
| `left_dpad`      | ABS_HAT0X/Y — standard gamepad d-pad HAT axis  |
| `dpad`           | KEY_UP / KEY_DOWN / KEY_LEFT / KEY_RIGHT        |
| `left_joystick`  | ABS_X/Y at ±32767 (digital, per direction)      |
| `right_joystick` | ABS_Z/ABS_RZ at ±32767 (digital, per direction) |
| `mouse`          | Drives virtual mouse cursor at full speed per direction (digital) |
| `button_mode`    | Each direction bound to its own action (see below) |
| `none`           | All input from this d-pad is suppressed         |

#### `button_mode`

Treats each d-pad direction as an independent button. Each direction takes a full action
object and fires on both press and release.

```json
{
  "function": "button_mode",
  "up":    { "type": "android_key", "code": 103 },
  "down":  { "type": "android_key", "code": 108 },
  "left":  { "type": "android_key", "code": 105 },
  "right": { "type": "android_key", "code": 106 }
}
```

Any action type except `layer_lt` is valid for d-pad directions.

---

### Joystick (`left_joystick`)

```json
{ "function": "left_joystick" }
```

| `function`       | Output                                                        |
|------------------|---------------------------------------------------------------|
| `left_joystick`  | ABS_X/Y — left stick pass-through                            |
| `right_joystick` | ABS_Z/ABS_RZ — right stick (Xbox axis convention)            |
| `left_dpad`      | ABS_HAT0X/Y — analog-to-digital, 50% threshold               |
| `dpad`           | KEY_UP / KEY_DOWN / KEY_LEFT / KEY_RIGHT — analog-to-digital  |
| `mouse`          | Drives virtual mouse cursor (analog, uses mouse speed/accel settings) |
| `none`           | Joystick output suppressed                                    |

Analog-to-digital conversion fires a press when the stick crosses 50% of the full axis
range in a given direction, and a release when it returns within that threshold.

---

### Rotation and `rotation_override`

In portrait orientation (device rotated 90° clockwise), physical directions are remapped
so controls feel natural:

- **D-pad:** physical UP → output RIGHT, RIGHT → DOWN, DOWN → LEFT, LEFT → UP
- **Joystick:** `out_X = in_Y`, `out_Y = −in_X`

This is applied automatically — you only define binds once per layout and rotation is
handled for you.

To lock a specific directional input to one transform regardless of layout orientation,
use `rotation_override`:

```json
{
  "function": "left_joystick",
  "rotation_override": "landscape"
}
```

| Value       | Behavior                              |
|-------------|---------------------------------------|
| `"landscape"` | Always use landscape axis mapping   |
| `"portrait"`  | Always use portrait axis mapping    |
| omitted / `null` | Follow the active layout orientation (default) |

---

## Bind object

A bind maps one physical button to one or two actions.

```json
{ "src": 304, "tap": { ... action ... }, "hold": { ... action ... } }
```

| Field  | Type   | Required | Description |
|--------|--------|----------|-------------|
| `src`  | int    | yes      | Physical button code (see **Physical button codes** below). |
| `tap`  | action | yes      | Action fired on press and release. For LT keys, this is the tap action. |
| `hold` | action | no       | Must be `layer_lt` if present. Makes this bind an LT (layer-tap) key. |

A bind with no `hold` is a simple button — the tap action fires immediately on press (value=1)
and release (value=0). A bind with `hold` set to `layer_lt` enters a dual-role state machine
described in the `layer_lt` action section.

---

## Action object

```json
{ "type": "...", "code": 0, "value": 0 }
```

Not all fields are used by every action type. Unused fields can be omitted.

---

### `none`

Explicitly silences a button. Use this in non-zero layers to block keys that have no
function in that layer rather than leaving them unbound (which would also block them,
but this makes intent explicit).

```json
{ "type": "none" }
```

---

### `gamepad_key`

Emits a gamepad button event on the virtual gamepad device. `code` is a Linux `BTN_*` value.

```json
{ "type": "gamepad_key", "code": 304 }
```

See **Output button codes** table below for values.

---

### `android_key`

Emits a keyboard/system key event. Android maps these to `KeyEvent` constants. Use this
for BACK, HOME, volume, and d-pad navigation keys.

```json
{ "type": "android_key", "code": 158 }
```

See **Output key codes** table below for values.

---

### `gamepad_axis`

Emits an analog axis at a fixed value when pressed. Automatically resets to 0 on release.
Useful for binding a direction to a specific analog position.

```json
{ "type": "gamepad_axis", "code": 0, "value": -32767 }
```

| Field   | Description |
|---------|-------------|
| `code`  | Axis code (see **Axis codes** table below) |
| `value` | Axis value when pressed (range: −32767 to 32767) |

---

### `mouse_btn`

Emits a mouse button event on the virtual mouse device.

```json
{ "type": "mouse_btn", "code": 272 }
```

| Code | Button |
|------|--------|
| 272  | Left   |
| 273  | Right  |
| 274  | Middle |

---

### `mouse_scroll`

Emits a scroll wheel event. Only fires on key press; release is ignored.

```json
{ "type": "mouse_scroll", "value": 1 }
```

| `value` | Direction |
|---------|-----------|
| positive | Scroll up |
| negative | Scroll down |

---

### `layer_tg`

Toggles a layer on or off. If the target layer is already the active toggled layer, it
returns to layer 0. If a different layer is toggled, it becomes the new toggled layer.

```json
{ "type": "layer_tg", "code": 2 }
```

`code` is the layer index to toggle (1–4). The toggle bind should be present in the target
layer as well, pointing back to its own index, so the user has a way to return to layer 0.

**Example** — SELECT toggles layer 2 on; the same bind in layer 2 turns it off:

```json
{ "src": 314, "tap": { "type": "layer_tg", "code": 2 } }
```

---

### `orient_tg`

Toggles orientation between landscape and portrait. Flips the active layout immediately
and issues `settings put system user_rotation` to rotate the Android screen to match.
If the device is physically rotated afterward, the system setting re-syncs automatically.

```json
{ "type": "orient_tg" }
```

```json
{ "src": 317, "tap": { "type": "orient_tg" } }
```

---

### `layer_lt`

Only valid in the `hold` field of a bind. Turns the key into a dual-role LT (layer-tap) key.

- **Tap** — press and release within `lt_hold_ms` with no other key pressed: fires the `tap` action.
- **Another key pressed while held** — activates the layer immediately, before or after the threshold. All subsequent input uses that layer until the LT key is released.
- **Held past `lt_hold_ms` and released with no other key** — nothing happens. No tap, no layer.

`code` is the layer index to activate (1–4).

```json
{
  "src": 315,
  "tap":  { "type": "gamepad_key", "code": 315 },
  "hold": { "type": "layer_lt",    "code": 1   }
}
```

In this example: tapping START sends BTN_START. Pressing any other button while START is
held activates layer 1 for as long as START is held. Holding START alone past the threshold
and releasing does nothing.

---

## Reference tables

### Axis codes (for `gamepad_axis`)

| Code | Name   | Description             |
|------|--------|-------------------------|
| 0    | ABS_X  | Left stick horizontal   |
| 1    | ABS_Y  | Left stick vertical     |
| 2    | ABS_Z  | Right stick horizontal  |
| 5    | ABS_RZ | Right stick vertical    |

---

### Physical button codes (One35 hardware `src` values)

These are the Linux input codes reported by the physical controller hardware.

| Code | Button         | Notes |
|------|----------------|-------|
| 304  | A              | Landscape only |
| 305  | B              | Landscape only |
| 306  | —              | R1 in portrait orientation |
| 307  | X              | Landscape only |
| 308  | Y              | Landscape only |
| 309  | —              | L1 in portrait orientation |
| 310  | L1             | Landscape only |
| 311  | R1             | Landscape only |
| 312  | L2             | Digital button (not analog) |
| 313  | R2             | Digital button (not analog) |
| 314  | SELECT         | |
| 315  | START          | |
| 317  | L3             | Left stick click |
| 318  | R3             | Right stick click — **not physically present on the One35** |
| 158  | BACK           | Hardware back button |
| 114  | VOLUME DOWN    | |
| 115  | VOLUME UP      | |
| 116  | POWER          | |

**Portrait hardware note:** When the device is held in portrait orientation, the face
buttons physically rotate and the hardware reports different codes. A (304), B (305),
X (307), Y (308) are replaced by remapped codes, and L1/R1 report as 309/306. The
portrait layout in your config should account for this by using these alternate codes
as `src` values and mapping them to the intended output codes.

**D-pad notes:** Neither d-pad has `src` codes in the bind table — they are handled
entirely by the directional config (`left_dpad`, `right_dpad`). The left d-pad fires
both `ABS_HAT0X/Y` and `KEY_*` events simultaneously; the daemon uses the HAT as
canonical and suppresses the duplicate key events.

---

### Output button codes (for `gamepad_key`)

Standard Linux `BTN_*` constants. Android maps these to standard gamepad `KeyEvent` codes.

| Code | Name         | Gamepad button |
|------|--------------|----------------|
| 304  | BTN_A        | A              |
| 305  | BTN_B        | B              |
| 307  | BTN_X        | X              |
| 308  | BTN_Y        | Y              |
| 310  | BTN_TL       | L1             |
| 311  | BTN_TR       | R1             |
| 312  | BTN_TL2      | L2             |
| 313  | BTN_TR2      | R2             |
| 314  | BTN_SELECT   | SELECT / View  |
| 315  | BTN_START    | START / Menu   |
| 316  | BTN_MODE     | Guide / Home   |
| 317  | BTN_THUMBL   | L3             |
| 318  | BTN_THUMBR   | R3             |

Full reference: [linux/input-event-codes.h](https://github.com/torvalds/linux/blob/master/include/uapi/linux/input-event-codes.h)

---

### Output key codes (for `android_key`)

| Code | Linux name     | Android equivalent    |
|------|----------------|-----------------------|
| 103  | KEY_UP         | KEYCODE_DPAD_UP       |
| 105  | KEY_LEFT       | KEYCODE_DPAD_LEFT     |
| 106  | KEY_RIGHT      | KEYCODE_DPAD_RIGHT    |
| 108  | KEY_DOWN       | KEYCODE_DPAD_DOWN     |
| 114  | KEY_VOLUMEDOWN | KEYCODE_VOLUME_DOWN   |
| 115  | KEY_VOLUMEUP   | KEYCODE_VOLUME_UP     |
| 116  | KEY_POWER      | KEYCODE_POWER         |
| 158  | KEY_BACK       | KEYCODE_BACK          |
| 172  | KEY_HOMEPAGE   | KEYCODE_HOME          |
| 580  | KEY_APPSELECT  | KEYCODE_APP_SWITCH (recent apps) |

---

## Example configs

### Minimal — single layer, pass-through gamepad

All buttons mapped 1:1. Right d-pad acts as right joystick in landscape, digital d-pad keys in portrait.

```json
{
  "global": {
    "lt_hold_ms": 390
  },
  "layers": [
    {
      "landscape": {
        "left_dpad":     { "function": "left_dpad" },
        "right_dpad":    { "function": "right_joystick" },
        "left_joystick": { "function": "left_joystick" },
        "controller_buttons": {
          "binds": [
            { "src": 304, "tap": { "type": "gamepad_key", "code": 304 } },
            { "src": 305, "tap": { "type": "gamepad_key", "code": 305 } },
            { "src": 307, "tap": { "type": "gamepad_key", "code": 307 } },
            { "src": 308, "tap": { "type": "gamepad_key", "code": 308 } },
            { "src": 310, "tap": { "type": "gamepad_key", "code": 310 } },
            { "src": 311, "tap": { "type": "gamepad_key", "code": 311 } },
            { "src": 312, "tap": { "type": "gamepad_key", "code": 312 } },
            { "src": 313, "tap": { "type": "gamepad_key", "code": 313 } },
            { "src": 314, "tap": { "type": "gamepad_key", "code": 314 } },
            { "src": 315, "tap": { "type": "gamepad_key", "code": 315 } },
            { "src": 317, "tap": { "type": "gamepad_key", "code": 317 } }
          ]
        },
        "system_buttons": {
          "binds": [
            { "src": 158, "tap": { "type": "android_key", "code": 158 } },
            { "src": 114, "tap": { "type": "android_key", "code": 114 } },
            { "src": 115, "tap": { "type": "android_key", "code": 115 } },
            { "src": 116, "tap": { "type": "android_key", "code": 116 } }
          ]
        }
      },
      "portrait": {
        "left_dpad":     { "function": "none" },
        "right_dpad":    { "function": "dpad" },
        "left_joystick": { "function": "none" },
        "controller_buttons": {
          "binds": [
            { "src": 307, "tap": { "type": "gamepad_key", "code": 304 } },
            { "src": 304, "tap": { "type": "gamepad_key", "code": 305 } },
            { "src": 305, "tap": { "type": "gamepad_key", "code": 308 } },
            { "src": 308, "tap": { "type": "gamepad_key", "code": 307 } },
            { "src": 309, "tap": { "type": "gamepad_key", "code": 310 } },
            { "src": 306, "tap": { "type": "gamepad_key", "code": 311 } },
            { "src": 314, "tap": { "type": "gamepad_key", "code": 314 } },
            { "src": 315, "tap": { "type": "gamepad_key", "code": 315 } },
            { "src": 310, "tap": { "type": "none" } },
            { "src": 311, "tap": { "type": "none" } },
            { "src": 312, "tap": { "type": "none" } },
            { "src": 313, "tap": { "type": "none" } }
          ]
        },
        "system_buttons": {
          "binds": [
            { "src": 158, "tap": { "type": "android_key", "code": 158 } },
            { "src": 114, "tap": { "type": "android_key", "code": 114 } },
            { "src": 115, "tap": { "type": "android_key", "code": 115 } },
            { "src": 116, "tap": { "type": "android_key", "code": 116 } }
          ]
        }
      }
    }
  ]
}
```

---

### Two layers — gamepad + mouse mode via LT hold

Layer 0 = standard gamepad. Layer 1 = mouse mode.
Tapping START sends BTN_START normally. Pressing any other button while START is held activates layer 1.
Mouse uses faster speed and acceleration for comfort.

```json
{
  "global": {
    "lt_hold_ms": 390,
    "haptics_ms": 40,
    "mouse": {
      "dead_zone_pct": 12,
      "speed_pct": 150,
      "accel_pct": 180,
      "accel_zone_pct": 20
    }
  },
  "layers": [
    {
      "landscape": {
        "left_dpad":     { "function": "left_dpad" },
        "right_dpad":    { "function": "right_joystick" },
        "left_joystick": { "function": "left_joystick" },
        "controller_buttons": {
          "binds": [
            { "src": 304, "tap": { "type": "gamepad_key", "code": 304 } },
            { "src": 305, "tap": { "type": "gamepad_key", "code": 305 } },
            { "src": 307, "tap": { "type": "gamepad_key", "code": 307 } },
            { "src": 308, "tap": { "type": "gamepad_key", "code": 308 } },
            { "src": 310, "tap": { "type": "gamepad_key", "code": 310 } },
            { "src": 311, "tap": { "type": "gamepad_key", "code": 311 } },
            { "src": 314, "tap": { "type": "gamepad_key", "code": 314 } },
            {
              "src":  315,
              "tap":  { "type": "gamepad_key", "code": 315 },
              "hold": { "type": "layer_lt",    "code": 1   }
            }
          ]
        },
        "system_buttons": {
          "binds": [
            { "src": 158, "tap": { "type": "android_key", "code": 158 } },
            { "src": 114, "tap": { "type": "android_key", "code": 114 } },
            { "src": 115, "tap": { "type": "android_key", "code": 115 } },
            { "src": 116, "tap": { "type": "android_key", "code": 116 } }
          ]
        }
      },
      "portrait": {
        "left_dpad":     { "function": "none" },
        "right_dpad":    { "function": "dpad" },
        "left_joystick": { "function": "none" },
        "controller_buttons": {
          "binds": [
            { "src": 307, "tap": { "type": "gamepad_key", "code": 304 } },
            { "src": 304, "tap": { "type": "gamepad_key", "code": 305 } },
            { "src": 305, "tap": { "type": "gamepad_key", "code": 308 } },
            { "src": 308, "tap": { "type": "gamepad_key", "code": 307 } },
            { "src": 309, "tap": { "type": "gamepad_key", "code": 310 } },
            { "src": 306, "tap": { "type": "gamepad_key", "code": 311 } },
            { "src": 314, "tap": { "type": "gamepad_key", "code": 314 } },
            {
              "src":  315,
              "tap":  { "type": "gamepad_key", "code": 315 },
              "hold": { "type": "layer_lt",    "code": 1   }
            },
            { "src": 310, "tap": { "type": "none" } },
            { "src": 311, "tap": { "type": "none" } },
            { "src": 312, "tap": { "type": "none" } },
            { "src": 313, "tap": { "type": "none" } }
          ]
        },
        "system_buttons": {
          "binds": [
            { "src": 158, "tap": { "type": "android_key", "code": 158 } },
            { "src": 114, "tap": { "type": "android_key", "code": 114 } },
            { "src": 115, "tap": { "type": "android_key", "code": 115 } },
            { "src": 116, "tap": { "type": "android_key", "code": 116 } }
          ]
        }
      }
    },
    {
      "landscape": {
        "left_dpad":     { "function": "none" },
        "right_dpad":    { "function": "none" },
        "left_joystick": { "function": "mouse" },
        "controller_buttons": {
          "binds": [
            { "src": 304, "tap": { "type": "mouse_btn",    "code": 272  } },
            { "src": 305, "tap": { "type": "mouse_btn",    "code": 273  } },
            { "src": 307, "tap": { "type": "mouse_scroll", "value": 1   } },
            { "src": 308, "tap": { "type": "mouse_scroll", "value": -1  } }
          ]
        },
        "system_buttons": { "binds": [] }
      },
      "portrait": {
        "left_dpad":     { "function": "none" },
        "right_dpad":    { "function": "mouse" },
        "left_joystick": { "function": "none" },
        "controller_buttons": {
          "binds": [
            { "src": 307, "tap": { "type": "mouse_btn",    "code": 272  } },
            { "src": 304, "tap": { "type": "mouse_btn",    "code": 273  } },
            { "src": 305, "tap": { "type": "mouse_scroll", "value": 1   } },
            { "src": 308, "tap": { "type": "mouse_scroll", "value": -1  } }
          ]
        },
        "system_buttons": { "binds": [] }
      }
    }
  ]
}
```
