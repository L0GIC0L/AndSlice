# AndSlice

A 3D model slicer for Android, tuned for the **Anycubic Kobra 3** and the Kobra 3 Combo with the ACE Pro, and
ready for about 260 other printers. It prints and controls printers over Wi-Fi through Moonraker (Rinkhals)
or OctoPrint, with the printer's camera in the app.

AndSlice runs PrusaSlicer's actual slicing engine on the phone. **libslic3r**
(PrusaSlicer 2.9.6) generates the G-code, and PrusaSlicer's own G-code viewer
(**libvgcode**) draws the toolpath preview. A Kotlin/Jetpack Compose UI sits on
top, replacing the desktop interface. It follows Material 3 and Material You, including dynamic color on
Android 12+ and light and dark themes.

## Features

- Import STL, 3MF, OBJ and AMF files, and zip archives of them (from the app, or "Open with" / "Share" from
  other apps). From an archive with several models, you pick the ones to add.
- **Find models online:** Printables, Thingiverse, MakerWorld, Thangs and Cults in a browser inside the
  app, with a search box for the chosen site. A downloaded STL, 3MF, OBJ or zip goes on the plate right
  away, and a copy stays in Downloads/AndSlice. Sites that need an account work after signing in on the
  site itself (Google sign-in is not available inside apps).
- **Projects:** save the plate as a 3MF project with its painting, transforms and settings, and open it again
  later, in AndSlice or in PrusaSlicer. The plate is also kept between sessions on its own.
- 3D plate view of the 255 × 255 × 260 mm Kobra 3 bed: orbit, pan and zoom; tap to select, drag to move
- Object tools: lay on face, duplicate, delete, auto-arrange, and a move / rotate / scale panel with step
  buttons (0.1 to 10 mm, 1 to 90°), typed values, sizes in mm or scale per axis
- **Calibration** (plate menu → Calibrate): a temperature tower, a flow cube to measure with calipers and a
  pressure advance tower (with `M900 K` on the Kobra 3, `SET_PRESSURE_ADVANCE` on other Klipper printers).
  The result becomes the filament's temperature, extrusion multiplier or pressure advance, and can be saved
  in a filament profile of your own.
- **Undo and redo** for the plate: moves, deletes, imports, cuts, settings per object and whole painting
  sessions, 30 steps back
- **Check print** (the chip over the plate, or plate menu → Check print): warns about holes in a model,
  large overhangs that supports leave out, objects that barely touch the bed, and objects that are off the
  bed or too big for it, each with fixes: repair the mesh (CGAL fills the holes), optimize orientation
  (PrusaSlicer's search for the fewest overhangs), turn on or paint supports, add a brim, scale to fit or
  arrange. After slicing it also lists PrusaSlicer's own stability check (loose extrusions, long bridges,
  floating parts, low bed adhesion)
- Mirror along X, Y or Z; split an object into separate objects, or into parts that get their own filament
  slots; and cut an object across at a height shown as a plane, with the upper part turned onto its cut face
- **Painting** with PrusaSlicer's own painting engine: paint where supports go or must not go, where seams
  go or must not go, walls with fuzzy skin, and on the Kobra 3 Combo areas in other filaments. Tools: a circle or sphere brush of 1-30 mm, smart fill that
  stops at edges sharper than a chosen angle, and height bands (0.1-10 mm) for stripes. For supports:
  paint only overhangs below a chosen angle, show those overhangs, or support or block all of them at once.
  Undo and clear. The view turns under the bed, to see and paint an object's underside.
- **Object settings** (the slider icon on an object): variable layer height with PrusaSlicer's adaptive
  algorithm (thin layers on curves and slopes, thick ones on walls, from finer to faster), settings for one
  object only (any of PrusaSlicer's object and region options, such as infill, perimeters or supports), and
  height ranges with settings of their own, such as finer layers for a detailed top
- **Z anti-aliasing** (Settings → Layers and perimeters), ported from OrcaSlicer: on sloped and curved tops
  the nozzle follows the model within a layer, which smooths the visible steps
- **Make overhangs printable** (Supports sheet, or Settings → Support material), ported from OrcaSlicer,
  which has it from Cura: material goes under overhangs steeper than a chosen angle (45° from vertical by
  default), so they print without supports. The model gets wedges of plastic under its overhangs; holes in
  its base up to a set area can keep their opening
- Kobra 3 profiles for 0.2, 0.4, 0.6 and 0.8 mm nozzles, with Anycubic's start macro (`G9111`), speeds,
  accelerations and filament presets (PLA, PLA+, PLA High Speed, PLA Matte/Silk/Glow/SE, PETG, ABS, ASA, TPU)
- **Your printers:** several saved printers, each with its profile, connection and camera. Add one from
  PrusaSlicer's vendor profiles (Prusa, Creality, Voron, Sovol, Elegoo, Qidi, Anker, Artillery, RatRig and
  more, 260 models) or as a custom printer from its bed size, nozzle, firmware and extruder type
- **Your profiles:** save print, filament and printer settings as profiles of your own (Settings → Save as)
- **Multi-color with the ACE Pro** (Kobra 3 Combo): a filament and color per ACE slot, objects and parts
  assigned to slots, objects drawn in their filament colors, a movable prime tower, and a filament view in the
  preview. **Read ACE** fills the slots from the printer: each loaded slot's color and the filament profile
  for its material, from Rinkhals' `mmu` object or Klipper's ACEPRO driver
- Quick settings for supports (on the plate, everywhere or only where painted; grid, snug or organic tree
  supports; on the Combo, the filament slot of the supports and of their interface layers), infill and
  brim, plus a full editor for every PrusaSlicer print, filament and
  printer option. Options sit on PrusaSlicer's own pages and groups (Simple / Advanced / Expert, with
  PrusaSlicer's own tooltips), and search covers all of them.
- Slicing with progress and cancel, then a layer-by-layer preview with feature, speed, height, width, fan,
  temperature and flow views
- Print time, filament length, weight and cost estimates; the time of each layer beside the layer slider,
  marks on the slider where the filaments change, and the weight each ACE slot prints in the filament view
- Thumbnails embedded in the G-code for the printer's screen
- Save the G-code anywhere (USB stick through OTG, Downloads, cloud drives) or share it to another app
- **OctoPrint and Moonraker:** upload G-code or upload and start the print, then watch temperatures and
  progress and pause or cancel the job. The app finds out which server it is talking to; a Kobra 3 running
  Rinkhals only needs its IP address.
- **Printer control:** live camera (MJPEG or snapshots), temperatures and preheat, homing, jogging X/Y/Z,
  fan and motors, a G-code console and an emergency stop
- **Web interface:** the printer's Mainsail, Fluidd or OctoPrint page inside the app, or in the browser.
  For Rinkhals the app finds Mainsail on port 4409.
- **Files on the printer:** the G-code stored on the printer with thumbnails and print times, to print again
  or delete, and on Moonraker the recent prints with how they ended
- **Print notifications:** while a print runs, a notification shows its progress, the time left and a camera
  snapshot, with pause and resume, so the app does not need to stay open. When the print ends, fails or is
  canceled, an alert says so, with a last look through the camera.
- **Navigation:** Plate, Preview, Printer and Settings in a bar at the bottom on phones and in a rail on
  tablets and foldables. Preview slices the plate first when it has changed, as in desktop slicers.
- **Tablets and foldables:** a side panel beside the 3D view (half the screen, or 400 dp) with a Plate tab
  (printer, profiles, filament slots, quick settings, objects) and a Settings tab with every slicing option,
  and the Slice button docked under it; printer cards in columns, settings pages as a list beside the
  options, and a split view when a foldable is half open on a table. The 3D view frames the bed for the
  shape of the screen. The edge between the 3D view and the side panel can be dragged to resize the panel
  (a double tap resets it).
- **Preferences:** light, dark or system theme, wallpaper colors, the side panel (half, narrow or off,
  resizable or not), a perspective or orthographic camera, turn speed and inverted up/down turning, bed
  grid, how the preview draws toolpaths (full shapes, simpler ones while the view moves, or always simpler),
  anti-aliasing and resolution, asking before clearing the plate, keeping the plate between sessions,
  whether the preview opens after slicing, keeping the screen on while watching the printer, the settings
  mode the settings open in, print notifications, and clearing cached files
- **Preview speed:** on big prints the layer and move sliders skip to their latest position instead of
  queueing every step (each one makes libvgcode go over all toolpath vertices), and while a finger turns
  or zooms the view, each toolpath segment is drawn as its side facing the camera, with a quarter of the
  vertices; lowering the resolution does not help there, as the work is per vertex, not per pixel

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

### Other printers

Open the printer chip under the plate (or the printer name on the Printer screen) and choose **Add printer**.
Search for the model, pick the nozzle and optionally enter its network address. Printers without a vendor
profile are added as **Custom printer**: bed size, height, nozzle, firmware (Marlin 2, Klipper,
RepRapFirmware) and direct drive or Bowden. Their start and end G-code can be edited in Settings → Printer.
Each vendor's profiles are loaded when one of its printers is added; a large vendor such as Prusa takes a few
seconds.

### Over Wi-Fi with Rinkhals, Moonraker or OctoPrint

1. Open **Printer** (printer icon in the top bar).
   - **Rinkhals / Moonraker:** enter the printer's IP address. No API key is needed. If Moonraker is not on
     port 80, the app also tries its own port 7125.
   - **OctoPrint:** tap **Find** or enter the address (for example `http://octopi.local`), and paste an API key
     from OctoPrint's **Settings → Application Keys**.
2. Tap **Test**, then **Save**.
3. After slicing, tap **Print** in the preview and choose **Upload and print** or **Upload only**.

The Printer screen shows the camera, the state, hotend and bed temperatures (tap one to set it) and job
progress with pause and cancel. It also has preheat buttons, homing, a jog pad, fan and motor switches, a
G-code console and an emergency stop. Moving is locked while a print runs.
With Moonraker the app uses Moonraker's own API, because its OctoPrint emulation cannot pause or cancel.
The camera comes from Moonraker's webcam list (as set up in Mainsail or Fluidd), the usual
`/webcam/?action=stream` path that Rinkhals serves, or OctoPrint's webcam settings; any MJPEG or snapshot
address can also be entered for the printer.
Print servers are usually reached over plain HTTP on the local network, so the app allows cleartext HTTP. The
API key is stored in the app's private storage.

### Multi-color with the ACE Pro

1. Pick a **Kobra 3 Combo** printer (the first chip in the bar under the plate).
2. Four filament chips appear, one per ACE slot. Tap each to choose its material and the color that is
   loaded there, or tap **Read ACE** to take both from a connected printer running Rinkhals.
3. Select an object and tap the colored dot in its toolbar to choose its slot. Objects made of several parts
   (from 3MF, OBJ or AMF files) can have a slot per part. Painted 3MF projects keep their painting.
4. To color areas of an object, tap the brush in its toolbar → **Paint filaments**, pick a slot and paint
   with one finger; drag beside the object to turn it. **Fill** colors a whole area up to its sharp edges.
5. Slice. The preview opens in the **Filament** view; the prime tower can be dragged on the plate.

Supports print with **Any** filament by default: the one loaded at each layer, so they add no filament
changes. Under **Supports** a slot can be chosen for the supports and another for their interface layers,
such as PETG under PLA, which does not bond and comes off cleanly. With the prime tower, PrusaSlicer allows
that only for organic supports.

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
  bridge, filament painting with undo, saving and opening a project, Z anti-aliasing on a pyramid, an app
  launch test, and the plate screen
  laid out as on an unfolded foldable. CI runs them on an x86_64 emulator.
- The command-line harness also paints (`--paint 0=colors:2`, `--paint 0=supports:1:below`), takes any
  option with `--set`, such as `--set zaa_enabled=1`, and saves and opens projects
  (`--save-project plate.3mf`, `--project plate.3mf`). `--adaptive 0=0.5`, `--object-set 0:perimeters=4` and
  `--range 0:10-20:layer_height=0.1` set variable layer height, object settings and height ranges;
  `--mirror 0=x`, `--split 0=objects` and `--cut 0=12` mirror, split and cut;
  `--calibration temperature_tower:6:8:0.4=M104 S220:8.2=M104 S215` places a test print with G-code at heights.
  `--check` prints the printability check, and `--fix 0=repair`, `--fix 0=orient` or `--fix 0=fit` applies a fix.

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
| `native/src/MeshRepair.*` | Mesh repair with CGAL: stitching, hole filling and orienting the facets |
| `native/src/SceneRenderer.*`, `PreviewRenderer.*` | OpenGL ES 3 plate view and the libvgcode preview |
| `native/src/jni_bridge.cpp` | JNI entry points for `NativeEngine.kt` |
| `native/src/stubs/` | Stand-ins for the parts of libslic3r that are not built (see below) |
| `scripts/gen-settings-layout.py` | Extracts the settings pages and groups from PrusaSlicer's `Tab.cpp` into `settings_layout.json` |
| `scripts/update-vendor-profiles.py` | Copies PrusaSlicer's FFF vendor profiles into the app and writes their catalog |
| `app/src/main/assets/profiles/CustomPrinter.ini` | Base printer and print profiles for custom printers |
| `app/src/main/assets/profiles/AnycubicKobra3.ini` | Kobra 3 profiles in PrusaSlicer's config bundle format |
| `app/src/main/java/.../octoprint/` | OctoPrint and Moonraker clients and network discovery |
| `app/src/main/java/...` | Compose UI (Material 3), view models, GL view and gestures |

PrusaSlicer's sources are used unmodified apart from seven small patches in `patches/`:

- `0001-libvgcode-opengl-es-rgba32f.patch` moves libvgcode's OpenGL ES path to RGBA32F textures, which the
  desktop path already uses since PrusaSlicer issue #12908. Without it, the ES path in 2.9.6 does not compile.
- `0002-libvgcode-opengl-es-highp-samplers.patch` reads libvgcode's data textures at high precision. The ES
  shaders used the low-precision default, which Mali GPUs honor: the preview lost its colors and stopped
  drawing after about 65 000 moves.
- `0003-wipe-tower-skip-empty-ramming.patch` skips the ramming block of the wipe tower for single extruder
  multi-material printers whose filaments have no ramming speeds, such as the ACE Pro, which swaps filament
  in firmware. Without it, every tool change would also send `SET_PRESSURE_ADVANCE`.
- `0004-preset-bundle-public-merge.patch` makes `PresetBundle::merge_presets()` public, so the app can load
  vendor bundles the way PrusaSlicer's own `load_system_presets()` does.
- `0005-z-anti-aliasing.patch` adds Z anti-aliasing (`zaa_enabled`, `zaa_min_z`,
  `zaa_minimize_perimeter_height`), after OrcaSlicer's Z contouring (`ContourZ.cpp` by adob): every top
  surface and wall point is raised or lowered to where a vertical ray from the slicing plane meets the
  model, with flow scaled to the local layer height. The offsets ride on the per-point height and flow
  fractions PrusaSlicer already uses for scarf seams, so only G-code output changes. A contoured path is
  entered and left straight up and down, so travel moves never scrape the layer.
- `0006-make-overhangs-printable.patch` adds `make_overhang_printable`, `make_overhang_printable_angle` and
  `make_overhang_printable_hole_size`, from OrcaSlicer's port of CuraEngine's conical overhang: from the top
  down, each layer gets the layer above it shrunk by how far an overhang at the angle reaches over one layer,
  using each layer's own height so variable layer heights keep the angle.
- `0007-libvgcode-low-detail-segments.patch` adds `Viewer::set_low_detail()`: each toolpath segment is then
  drawn as the one side of its box that the vertex shader turns towards the camera, 6 vertices instead of 24.

## Limitations

- FDM only. SLA hollowing (OpenVDB) is not built.
- No solver-based arrangement for sequential ("complete objects") printing, because that needs Z3. Sequential
  printing still gets PrusaSlicer's standard extruder-clearance check.
- No STEP import, since OpenCASCADE is not built.
- One ACE Pro (four slots). Eight colors with two ACE units are not set up.
- No true non-planar (curved layer) slicing. Z anti-aliasing only bends the top surfaces within a layer, and
  makes exporting the G-code slower.
- 64-bit devices only.
- Network printing to the Kobra 3 needs Moonraker (for example through Rinkhals) or OctoPrint. Anycubic's own
  cloud and LAN protocol are not supported, nor PrusaLink or Bambu printers' own protocols.
- The camera view plays MJPEG streams and JPEG snapshots, not WebRTC or HLS camera services.

## License

AndSlice is licensed under the GNU Affero General Public License v3. It is built on PrusaSlicer, © Prusa
Research and contributors (AGPL-3.0), which is based on Slic3r by Alessandro Ranellucci. The Kobra 3 settings
follow Anycubic's profiles as published in OrcaSlicer (AGPL-3.0).
