# one35uinputd

A Magisk daemon for the **MagicX One35** (MT6768) that intercepts the built-in controller and re-emits it as a virtual gamepad, mouse, and keyboard. Supports QMK-style layer switching, LT (layer-tap) keys, auto-orientation, and haptic feedback.

*DISCLAIMER: This was made with Claude.  Do with that what you will.*

## Features

- Up to 5 independent layers with per-layer landscape and portrait layouts
- LT (layer-tap): tap = button, hold + second input = momentary layer
- TG (layer-toggle): persistent layer switching
- Auto-orientation via inotify — layout switches in real time as the device rotates
- `orient_tg` action: rotate screen and switch layout with one button press
- Analog mouse mode with configurable speed, acceleration, and dead zone
- Haptic feedback on layer changes
- Live config reload via SIGHUP (Magisk action button)

## Install

Download `one35uinputd.zip` from [Releases](../../releases) and flash it through Magisk Manager.

The bundled `default.json` config is installed automatically. To use a custom config, see [config.md](config.md).

## Build

**Requirements:**
- Android NDK installed via Android Studio (`~/Library/Android/sdk/ndk/`)
- `adb` in your PATH
- macOS (the daemon Makefile targets `darwin-x86_64` — adjust `NDK_BIN` in `daemon/Makefile` for Linux)

**Build the module zip:**

```sh
make
```

This compiles the daemon with the NDK and produces `one35uinputd.zip`.

**Flash to a connected device:**

```sh
make install
# then run the printed adb command to flash via magisk
```

Or push and flash manually:

```sh
adb push one35uinputd.zip /sdcard/
# flash through Magisk Manager → Modules → Install from storage
```

**Clean:**

```sh
make clean
```

## Configuration

The recommended way to configure the daemon is the **One35 Config app** (`app/`) — a
standalone Android app that edits the config through a UI and applies changes live, with no
JSON hand-editing. See [app/README.md](app/README.md).

The underlying JSON format is still documented in [config.md](config.md) as the schema
reference (layer system, action types, bind format, example configs). Dropping a
`one35uinputd.json` on `/sdcard` and tapping the Magisk action button remains a working
fallback.

## License

MIT. See license headers in `daemon/cJSON.h` and `daemon/cJSON.c` (© Dave Gamble and cJSON contributors). All other code is original.
