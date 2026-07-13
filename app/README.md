# One35 Config (Android app)

A standalone Android app for configuring the `one35uinputd` daemon through a UI instead of
hand-editing JSON. It reads/writes the daemon's config on the device (via root/libsu) and
applies changes live with `SIGHUP` — no restart.

## What it does

- **Status** — live daemon state (running, active layer, orientation) from `one35uinputd.state`.
- **Editor** — one dense screen per layer: layer chips, a **Landscape / Portrait** toggle, and a
  grouped grid of the device's **physical buttons** (Face, Tate shoulders, Shoulders, Menu,
  Stick, System, Extra). Tap a button → a compact sheet to set its action (and optional hold).
  Three directional cells (**Left D-pad / Left stick / Right D-pad**) edit the stick/d-pad
  functions. Global timing/mouse live on a separate **Settings** screen.
- **Apply & reload** — writes the JSON into the module dir and sends `SIGHUP` for a live reload.
- **Profiles** — save/load/apply named configs, or import the current active config.
- **Log** — tail `one35uinputd.log` for parse errors and reload confirmations.

The app never changes the daemon or its JSON format — it edits the wire config directly. The
button roster comes from the hardware itself (`getevent -pl /dev/input/event1`): every physical
button emits a **fixed** `src` code, so there is no orientation-remap magic — the two
orientations are just two layouts you edit independently. TATE_L (309) / TATE_R (306) are real
extra shoulders (usable in either orientation); there is no R3 on the device.

## Requirements

- The `one35uinputd` Magisk module installed and running (the app reads
  `/data/adb/modules/one35uinputd/…`).
- Root (Magisk). On first launch, grant the Superuser request.
- Build: Android SDK (platform 34) + a JDK 17–21. The system `java` may be too new for AGP;
  use Android Studio's bundled JBR.

## Build & install

```sh
# from the repo root
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the compiler round-trip tests:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:testDebugUnitTest
```

## Architecture

- `data/model/Config.kt` — wire model, 1:1 with the daemon JSON (edited directly).
- `data/Catalog.kt` — the `PhysicalButton` roster + code↔label maps + directional-slot labels.
- `data/ConfigMapping.kt` — thin `Layout` helpers (`bindFor`/`withBind`/`dirFor`/`withDir`);
  unrecognized `src` binds are preserved so hand-written configs are never lost.
- `data/RootRepo.kt` / `ConfigRepo.kt` / `StateRepo.kt` / `ProfileRepo.kt` — root IO and storage.
- `ui/…` — Compose screens (Home, Editor, Settings, Profiles, Log) + reusable editors
  (`ActionEditor`, `BindEditor`, `DirConfigEditor`).
