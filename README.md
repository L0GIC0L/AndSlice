# AndSlice

A 3D model slicer for Android, tuned for the **Anycubic Kobra 3**.

AndSlice runs PrusaSlicer's actual slicing engine on the phone. **libslic3r**
(PrusaSlicer 2.9.6) generates the G-code, and PrusaSlicer's own G-code viewer
(**libvgcode**) draws the toolpath preview. A Kotlin/Jetpack Compose UI sits on
top, replacing the desktop interface.

## Features

- Import STL, 3MF, OBJ and AMF files (from the app, or "Open with" / "Share" from other apps)
- 3D plate view of the 255 × 255 × 260 mm Kobra 3 bed: orbit, pan and zoom; tap to select, drag to move
- Object tools: lay on face, rotate, move/rotate/scale by numbers, center, duplicate, delete, auto-arrange
- Kobra 3 profiles for 0.2, 0.4, 0.6 and 0.8 mm nozzles, with Anycubic's start macro (`G9111`), speeds,
  accelerations and filament presets (PLA, PLA+, PLA High Speed, PLA Matte/Silk/Glow/SE, PETG, ABS, ASA, TPU)
- Quick settings for supports, infill and brim, plus a full editor for every PrusaSlicer print, filament and
  printer option (Simple / Advanced / Expert, with PrusaSlicer's own tooltips)
- Slicing with progress and cancel, then a layer-by-layer preview with feature, speed, height, width, fan,
  temperature and flow views
- Print time, filament length, weight and cost estimates
- Thumbnails embedded in the G-code for the printer's screen
- Save the G-code anywhere (USB stick through OTG, Downloads, cloud drives) or share it to another app

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
| `app/src/main/assets/profiles/AnycubicKobra3.ini` | Kobra 3 profiles in PrusaSlicer's config bundle format |
| `app/src/main/java/...` | Compose UI, view model, GL view and gestures |

PrusaSlicer's sources are used unmodified apart from `patches/0001-libvgcode-opengl-es-rgba32f.patch`. That
patch moves libvgcode's OpenGL ES path to RGBA32F textures, which the desktop path already uses since
PrusaSlicer issue #12908. Without it, the ES path in 2.9.6 does not compile.

## Limitations

- FDM only. SLA hollowing (OpenVDB) is not built.
- No solver-based arrangement for sequential ("complete objects") printing, because that needs Z3. Sequential
  printing still gets PrusaSlicer's standard extruder-clearance check.
- No STEP import, since OpenCASCADE is not built.
- Single extruder. Multi-color printing with the ACE Pro is not set up.
- 64-bit devices only.
- No network upload to the printer yet. Transfer the G-code by USB.

## License

AndSlice is licensed under the GNU Affero General Public License v3. It is built on PrusaSlicer, © Prusa
Research and contributors (AGPL-3.0), which is based on Slic3r by Alessandro Ranellucci. The Kobra 3 settings
follow Anycubic's profiles as published in OrcaSlicer (AGPL-3.0).
