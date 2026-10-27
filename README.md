# AndSlice

A 3D model slicer for Android, tuned for the **Anycubic Kobra 3** and the Kobra 3 Combo with the ACE Pro,
with printing over Wi-Fi through OctoPrint or Moonraker (Rinkhals).

AndSlice runs PrusaSlicer's actual slicing engine on the phone. **libslic3r**
(PrusaSlicer 2.9.6) generates the G-code, and PrusaSlicer's own G-code viewer
(**libvgcode**) draws the toolpath preview. A Kotlin/Jetpack Compose UI sits on
top, replacing the desktop interface. It follows Material 3 and Material You, including dynamic color on
Android 12+ and light and dark themes.

## Features

- Import STL, 3MF, OBJ and AMF files (from the app, or "Open with" / "Share" from other apps)
- 3D plate view of the 255 × 255 × 260 mm Kobra 3 bed: orbit, pan and zoom; tap to select, drag to move
- Object tools: lay on face, rotate, move/rotate/scale by numbers, center, duplicate, delete, auto-arrange
- Kobra 3 profiles for 0.2, 0.4, 0.6 and 0.8 mm nozzles, with Anycubic's start macro (`G9111`), speeds,
  accelerations and filament presets (PLA, PLA+, PLA High Speed, PLA Matte/Silk/Glow/SE, PETG, ABS, ASA, TPU)
- **Multi-color with the ACE Pro** (Kobra 3 Combo): a filament and color per ACE slot, objects and parts
  assigned to slots, objects drawn in their filament colors, a movable prime tower, and a filament view in the
  preview
- Quick settings for supports, infill and brim, plus a full editor for every PrusaSlicer print, filament and
  printer option. Options sit on PrusaSlicer's own pages and groups (Simple / Advanced / Expert, with
  PrusaSlicer's own tooltips), and search covers all of them.
- Slicing with progress and cancel, then a layer-by-layer preview with feature, speed, height, width, fan,
  temperature and flow views
- Print time, filament length, weight and cost estimates
- Thumbnails embedded in the G-code for the printer's screen
- Save the G-code anywhere (USB stick through OTG, Downloads, cloud drives) or share it to another app
- **OctoPrint and Moonraker:** upload G-code or upload and start the print, then watch temperatures and
  progress and pause or cancel the job. The app finds out which server it is talking to; a Kobra 3 running
  Rinkhals only needs its IP address.

## Download

Every push builds APKs in GitHub Actions (**Actions → Build APK → AndSlice-apks**). Tags named `v*` also
publish them on the **Releases** page.

- `AndSlice-<version>-arm64-v8a.apk` is for phones and tablets.
- `AndSlice-<version>-x86_64.apk` is for emulators and x86 Chromebooks.
- `AndSlice-<version>-universal.apk` runs on both.

Android 9 or newer with OpenGL ES 3.0 is required.

## Printing on the Kobra 3

1. Import a model, pick the nozzle, print profile and filament, then tap **Slice**.
2. Check the preview, then tap **Save G-code** and pick a USB stick (through a USB-C OTG adapter) or any folder.
3. Put the USB stick into the Kobra 3 and start the file from the printer's screen.

### Over Wi-Fi with Rinkhals, Moonraker or OctoPrint

1. Open **Printer** (printer icon in the top bar).
   - **Rinkhals / Moonraker:** enter the printer's IP address. No API key is needed. If Moonraker is not on
     port 80, the app also tries its own port 7125.
   - **OctoPrint:** tap **Find** or enter the address (for example `http://octopi.local`), and paste an API key
     from OctoPrint's **Settings → Application Keys**.
2. Tap **Test**, then **Save**.
3. After slicing, tap **Print** in the preview and choose **Upload and print** or **Upload only**.

The Printer screen shows the state, hotend and bed temperatures and job progress, with pause and cancel.
With Moonraker the app uses Moonraker's own API, because its OctoPrint emulation cannot pause or cancel.
Print servers are usually reached over plain HTTP on the local network, so the app allows cleartext HTTP. The
API key is stored in the app's private storage.

### Multi-color with the ACE Pro

1. Pick a **Kobra 3 Combo** printer (the first chip in the bar under the plate).
2. Four filament chips appear, one per ACE slot. Tap each to choose its material and the color that is
   loaded there.
3. Select an object and tap the colored dot in its toolbar to choose its slot. Objects made of several parts
   (from 3MF, OBJ or AMF files) can have a slot per part. Painted 3MF projects keep their painting.
4. Slice. The preview opens in the **Filament** view; the prime tower can be dragged on the plate.

Every filament change is a plain `T0`-`T3`. The firmware cuts the filament, swaps it and purges into the
chute, as with Anycubic Slicer Next, and the prime tower only takes the small purge that follows. Rinkhals
maps T0-T3 to ACE slots 1-4. A single-color print on slot 1 contains no `T` command, so the printer uses the
filament it has; on another slot it starts with that slot's `T` command.

The start G-code calls Anycubic's `G9111` macro, which heats up, levels the bed and draws the purge line, just
as Anycubic Slicer Next does.

## Building

Requirements: Linux, JDK 17, Android SDK with NDK `27.3.13750724` and CMake `3.31.6`, plus `git`, `curl`,
`autoconf` and `make`.

```sh
export ANDROID_HOME=~/Android/Sdk
scripts/build-deps.sh arm64-v8a      # Boost, oneTBB, GMP/MPFR, CGAL, ... (once, ~20 min)
scripts/build-native.sh arm64-v8a    # libslic3r + libvgcode + JNI -> app/src/main/jniLibs
./gradlew assembleRelease            # APKs in app/build/outputs/apk/release
```

`scripts/fetch-prusaslicer.sh` clones PrusaSlicer `version_2.9.6` into `third_party/` and applies `patches/`.
Repeat the two native steps with `x86_64` for emulator builds.

### Testing

- `scripts/build-deps.sh host && scripts/build-native.sh host andslice_cli` builds a desktop command-line
  harness around the same engine code the app uses:
  `build/native/host/andslice_cli --bundle app/src/main/assets/profiles/AnycubicKobra3.ini -o out/ model.stl`
- `./gradlew testDebugUnitTest` runs the JVM tests, including the OctoPrint client against a fake server.
- `./gradlew connectedDebugAndroidTest` runs the on-device tests: a full Kobra 3 slice through the JNI
  bridge, and an app launch test. CI runs them on an x86_64 emulator.

### Release signing

Without signing secrets, CI signs release APKs with a throw-away debug key. Android then refuses to update
over a build signed with a different key, so uninstall first. For updatable releases, add these repository
secrets: `ANDSLICE_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `ANDSLICE_KEYSTORE_PASSWORD`,
`ANDSLICE_KEY_ALIAS` and `ANDSLICE_KEY_PASSWORD`.

## How it fits together

| Path | Contents |
| --- | --- |
| `native/CMakeLists.txt` | Builds PrusaSlicer's `libslic3r`, its bundled libraries and `libvgcode` for Android |
| `native/src/Engine.*` | Presets, plate and slicing on top of libslic3r, following PrusaSlicer's CLI |
| `native/src/SceneRenderer.*`, `PreviewRenderer.*` | OpenGL ES 3 plate view and the libvgcode preview |
| `native/src/jni_bridge.cpp` | JNI entry points for `NativeEngine.kt` |
| `native/src/stubs/` | Stand-ins for the parts of libslic3r that are not built (see below) |
| `scripts/gen-settings-layout.py` | Extracts the settings pages and groups from PrusaSlicer's `Tab.cpp` into `settings_layout.json` |
| `app/src/main/assets/profiles/AnycubicKobra3.ini` | Kobra 3 profiles in PrusaSlicer's config bundle format |
| `app/src/main/java/.../octoprint/` | OctoPrint and Moonraker clients and network discovery |
| `app/src/main/java/...` | Compose UI (Material 3), view models, GL view and gestures |

PrusaSlicer's sources are used unmodified apart from three small patches in `patches/`:

- `0001-libvgcode-opengl-es-rgba32f.patch` moves libvgcode's OpenGL ES path to RGBA32F textures, which the
  desktop path already uses since PrusaSlicer issue #12908. Without it, the ES path in 2.9.6 does not compile.
- `0002-libvgcode-opengl-es-highp-samplers.patch` reads libvgcode's data textures at high precision. The ES
  shaders used the low-precision default, which Mali GPUs honor: the preview lost its colors and stopped
  drawing after about 65 000 moves.
- `0003-wipe-tower-skip-empty-ramming.patch` skips the ramming block of the wipe tower for single extruder
  multi-material printers whose filaments have no ramming speeds, such as the ACE Pro, which swaps filament
  in firmware. Without it, every tool change would also send `SET_PRESSURE_ADVANCE`.

## Limitations

- FDM only. SLA hollowing (OpenVDB) is not built.
- No solver-based arrangement for sequential ("complete objects") printing, because that needs Z3. Sequential
  printing still gets PrusaSlicer's standard extruder-clearance check.
- No STEP import, since OpenCASCADE is not built.
- One ACE Pro (four slots). Eight colors with two ACE units are not set up.
- No multi-material painting on the phone; painted 3MF projects from PrusaSlicer slice as painted.
- 64-bit devices only.
- Network printing to the Kobra 3 needs Moonraker (for example through Rinkhals) or OctoPrint. Anycubic's own
  cloud and LAN protocol are not supported.

## License

AndSlice is licensed under the GNU Affero General Public License v3. It is built on PrusaSlicer, © Prusa
Research and contributors (AGPL-3.0), which is based on Slic3r by Alessandro Ranellucci. The Kobra 3 settings
follow Anycubic's profiles as published in OrcaSlicer (AGPL-3.0).
