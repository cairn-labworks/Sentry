# Open Dash Cam — Session Handoff

This document captures everything done in the modernization/feature session so a new agent
session (on a different machine) can pick up with full context.

> Repo: `maxneaga/open_dash_cam_android`. Work was done in a worktree/branch
> `open_dash_cam_android.worktrees/dashcam-app-usage-guide-android13`.
>
> **Git state:** committed and pushed to a fork you own:
> - Remote `fork` = `https://github.com/punch-github/open_dash_cam_android.git`
> - Branch: `feature/dashcam-android13-modernization`
> - Commits (authored as `punch-github <14547377+punch-github@users.noreply.github.com>`):
>   `c14137b` (modernization+features), `fba6807` (home screen+shortcut+delete fix),
>   `8be7e42` (dark mode, charge automation, location safety+metadata).
> - The CLI account (`punch-github`) has **no write access to `maxneaga/...`**, so pushes go to the fork.
> - `*.apk` is git-ignored; `local.properties` is git-ignored.

---

## 1. Goal

Turn this ~6-year-unmaintained Android dashcam app into something usable/safe on **Android 13**,
then add a series of features. The app records continuously in the background (loop/segments)
and floats a draggable "REC" overlay widget over other apps (e.g. Google Maps).

---

## 2. Build environment (IMPORTANT for the new machine)

The project was modernized to a current toolchain:

- **Gradle wrapper:** 8.2  (`gradle/wrapper/gradle-wrapper.properties`)
- **Android Gradle Plugin:** 8.1.4  (root `build.gradle`)
- **JDK:** 17 (required by AGP 8). On this devbox it was at
  `C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot`
- **compileSdk 34, targetSdk 33 (Android 13), minSdk 23**  (`mobile/build.gradle`)
- **buildToolsVersion 35.0.0** (whatever is installed; 34+ works)
- Repos: `google()` + `mavenCentral()` (jcenter removed)
- `gradle.properties`: `android.useAndroidX=true`, `android.enableJetifier=false`
- `local.properties` must point at the local SDK, e.g.
  `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`

### Build command (Windows PowerShell)
```powershell
cd <repo-root>
$env:JAVA_HOME="C:\Path\To\jdk-17"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :mobile:assembleDebug --no-daemon
```
Output APK: `mobile/build/outputs/apk/debug/mobile-debug.apk`
(A copy is also kept at repo root as `OpenDashCam-debug.apk`.)

### Install on phone
```powershell
adb install "mobile\build\outputs\apk\debug\mobile-debug.apk"
```

---

## 3. Architecture (quick map)

- `MainActivity` — **home screen** (launcher). Shows a big REC button, storage bar, and
  Recordings/Settings cards. Does **NOT** auto-start recording; the user starts it from the REC
  button or a pinned "Start Recording" home-screen shortcut. Handles permission gating.
- `WidgetService` — foreground service; draws the overlay REC widget; holds a wake lock; also
  hosts the **battery/thermal monitor** (overheat alert + low-battery safe shutdown).
- `BackgroundVideoRecorder` — **CameraX** `LifecycleService`: `VideoCapture` + `Recorder` loop
  recording (segments bounded by `setDurationLimitMillis`, chained on Finalize) with an
  **`OverlayEffect`** that **burns date/time + GPS into the recorded frames**. Preserves folder
  organization, loop rotation, DB inserts, prefs (current/previous clip), wake lock, system-sound
  muting, and `isRecording`/`recordingStartedAt`. GPS also embedded as metadata.
- `PowerConnectionReceiver` — manifest receiver for `ACTION_POWER_CONNECTED/DISCONNECTED`;
  auto-starts/auto-stops recording per the Automation settings.
- `models/Widget` — the floating overlay button + menu (View recordings, Save, **Live view**,
  Settings, Stop). Supports **long-press drag** to move vertically.
- `SettingsActivity` — custom **card-based** settings with a **custom segmented control**.
- `ViewRecordingsActivity` + `ViewRecordingsRecyclerViewAdapter` — folder browser with tabs
  (All/Starred), count + total size header, delete-all, and **multi-select delete**.
- `LiveViewActivity` — **telemetry HUD** (no camera image; see decision below).
- `Util` — prefs-backed getters, storage math, event log, notifications, delete helpers,
  `startRecordingServices`/`stopRecordingServices`/`isRecording`/`hasRecordingPermissions`.
- `OpenDashApp` — sets `AppCompatDelegate` night mode to follow the system.

Recordings are stored under the app-private external `Movies` folder and organized into
`yyyy-MM-dd/HH/clip.mp4` once each hour ends.

---

## 4. What was implemented (chronological)

### Round 1 — Android 13 modernization + first features
- Full **AndroidX migration** (all `android.support.*` → `androidx.*`, layouts, FileProvider).
- Toolchain bump (Gradle/AGP/SDK) as above.
- Android 13 correctness: runtime `POST_NOTIFICATIONS`; storage permission scoped with
  `maxSdkVersion`; only camera/mic mandatory; foreground-service type `camera|microphone`.
- **Loop-retention bug fixed**: `Util.getFolderSize` returned KB but was compared as MB, so it
  kept almost nothing. Now consistent in MB.
- Preference-backed clip length / storage quota.
- **Overheating alert** (battery temperature threshold) + **low-battery safe shutdown** toggle.
- **Screen-off wake lock** in the recorder.

### Round 2 — storage + browser
- Recordings **organized into `yyyy-MM-dd/HH` folders** by a low-priority pass in the recorder
  once an hour ends; loop rotation made **recursive**; "Delete all" wipes the tree.
- **View Recordings reworked** into a folder browser with **multi-select delete** (files+folders).
- Removed the old MVP presenter/interface classes.

### Round 3 — the 5 requested features
1. **Clip length options 1/3/5/10 min, default 5 min** (`arrays.xml`, `Util.DEFAULT_CLIP_DURATION_SEC=300`).
2. **Draggable REC widget** — long-press (haptic) then drag up/down; tap still opens menu
   (`models/Widget.java`).
3. **Card-based Settings** matching the provided screenshots (segmented selectors + storage slider
   + safety toggles + general). Also added a real **Resolution (720p/1080p)** setting wired into
   the recorder (`Util.getVideoQuality()` + `BackgroundVideoRecorder`).
   - NOTE: my round-1 preference XML was never actually applied, which is why clip length wasn't
     configurable before; the custom Settings screen replaced the preference screen entirely.
4. **Recordings screen redesign** — "Recordings (N)" count, total size, **All/Starred tabs**,
   **delete-all** trash action, film-icon empty state.
5. **Live HUD** (`LiveViewActivity`) opened from a new **eye icon** in the overlay menu — shows
   clock/date, **GPS + speed** (needs location permission), **battery % + temperature** (Cool/Warm/
   Hot), **resolution/FPS**, **storage used/limit**, **REC status + elapsed + current clip**, and a
   rolling **event log** (`Util.logEvent`).

### Round 3 fix — Settings selectors not tappable
- The Material `MaterialButtonToggleGroup` segmented selectors weren't registering taps (and the
  two Safety ones were disabled when their switch was off). Replaced all four with a **custom
  segmented control** (plain clickable views; selected = solid blue/white bold; unselected = light).
  Files: `SettingsActivity.java`, `activity_settings.xml`, drawables `seg_selected.xml`/
  `seg_unselected.xml`, color `colorSegmentSelected`.

### Round 4 — home screen + shortcut + delete-button fix
- **App launch now opens a home screen** ([activity_main.xml]) instead of auto-starting recording:
  big REC button ("Mount securely. Tap to start."), storage bar (used/limit + %), and
  Recordings/Settings cards + settings icon.
- **Start Recording**: the REC button starts (and can stop) the overlay-widget recording via the
  shared `Util.startRecordingServices()`. A **pinned home-screen shortcut** ("Start Recording",
  via `ShortcutManager`) launches `MainActivity` with `EXTRA_START_RECORDING` to start directly.
  `MainActivity` is `singleTop` + handles `onNewIntent`.
- **Delete-all button** restyled from the odd-looking Material button to a clean red-outlined
  control (`bg_delete_button`) + confirmation dialog.

### Round 5 — dark mode + charge automation + location
- **Dark mode**: Material **DayNight** theme + `values-night/colors.xml`; semantic colors
  (`colorWindowBg/colorCard/colorCardAlt/colorDivider/segUnsel*`); follows system via `OpenDashApp`.
- **Auto-start on charge / auto-stop on unplug**: Settings "Automation" toggles
  (`auto_start_on_charge`, `auto_stop_on_discharge`) + `PowerConnectionReceiver`.
- **Location-off safety**: all location access guarded so disabled/denied location never crashes.
- **Location/time metadata**: `MediaRecorder.setLocation()` embeds GPS into each clip when available.

---

## 5. Key decisions / caveats

- **Live view is telemetry-only** (no live camera image). Reason: the legacy `Camera` API +
  background recorder owns the single camera instance; mirroring the preview into a second screen is
  fragile/device-specific. User explicitly chose the reliable telemetry HUD. Showing the real camera
  image would require deeper rework (e.g., sharing the recorder's preview surface at clip boundaries,
  or migrating to Camera2/CameraX) and on-device iteration.
- **Capture pipeline:** migrated from legacy `android.hardware.Camera` + `MediaRecorder` to
  **CameraX** (`camera-video` + `camera-effects` `OverlayEffect`, v1.4.2) so the timestamp/GPS can be
  **burned into the frames**. The recorder is now a `LifecycleService`. This is a big change that
  **must be validated on a device** (recording start/stop, segment chaining, overlay orientation,
  performance). Overlay text is drawn rotation-corrected via `Frame.getRotationDegrees()`; on some
  devices/mounts the position may need tuning.
- App theme is `Theme.MaterialComponents.DayNight.DarkActionBar.Bridge` (Material Slider/Switch/Button
  support + light/dark). Night overrides live in `values-night/colors.xml`.
- **All testing so far was compile/build only** — there was **no physical device** on the devbox.
  Everything below needs on-device verification.
- **Charge automation** (auto start/stop) depends on OEM background-start behavior; auto-start relies
  on the overlay-permission exemption for background foreground-service starts. Verify per device.

---

## 6. Verification checklist (do on a real Android 13 phone)

Status legend: [x] verified on device · [~] partially verified (see note) · [ ] not yet verified.
Tested on OPPO Find X2 (CPH2023), Android 13 / API 33, ColorOS.

- [x] Launch shows the **home screen** (no auto-record). Grant camera, mic, notifications,
      "display over other apps", (location for HUD/metadata).
      Note: ColorOS blocks adb `pm grant`/`appops`; permissions were granted manually via App Info.
- [x] Home **REC button** starts recording (widget appears); tapping again / Stop ends it.
- [x] **"Add Start Recording to home screen"** creates a launcher shortcut that starts recording.
- [x] REC widget: **long-press + drag** moves it; tap opens the menu.
- [x] New clips are **silent** (no per-clip beep).
      Note: no beep API in code (no MediaActionSound/ToneGenerator); app force-mutes STREAM_SYSTEM
      for the whole recording. Verified STREAM_SYSTEM muted during recording, no shutter/tone in logcat.
- [x] **Recorded video shows burned-in date/time + GPS** at the bottom (CameraX OverlayEffect);
      verify text is upright and on-screen for your mount, and that clips chain every N minutes.
      Note: verified on an extracted frame — date/time (2026-08-11 09:21:38), GPS (12.90295, 77.70988)
      and speed (0 km/h) burned in, upright, bottom-left. Clips chain per clip-length setting.
- [~] **Settings**: every segmented option (Clip length, Resolution, Overheat temp, Low-battery %)
      is tappable and **persists**; storage slider + switches work; 1080p records at 1080p.
      Note: 1080p output verified via ffprobe; automation toggles verified persisting to prefs.
      Remaining segmented options not each exercised.
- [x] **Delete all recordings** button looks right and shows a confirmation dialog.
- [x] After an hour rolls over, clips appear under `yyyy-MM-dd/HH` folders in View Recordings.
      Note: verified by pushing a clip with a previous-hour mtime and starting a segment — the
      organize step moved it to Movies/2026-08-11/08/… while the current-hour clip stayed flat.
- [x] Recordings screen: count, total size, All/Starred tabs, delete-all, multi-select delete, empty state.
      Note: verified title count, header size, tab switching, long-press multi-select + confirm-dialog
      delete, and empty-state clapperboard on both tabs.
- [x] **Live view** (eye icon) updates every second; GPS/speed populate after granting location;
      temperature label changes; event log lists clip starts, foldering, warnings.
      Note: verified 1s refresh (clock + REC timer advancing), GPS fix (12.90°, 77.71°), speed,
      colored temperature, storage, and event log ("New clip…", "Recording started"). Fixed a
      landscape bug where the event log was pushed off-screen — added layout-land variant (commit 8a6cedb).
- [x] Overheat alert fires above threshold; low-battery auto-shutdown works when enabled + unplugged.
      Note: overheat verified via `dumpsys battery set temp 460` (46°C ≥ 45°C default) → "Phone
      overheating" notification + toast. Low-battery verified by enabling the toggle and dropping
      level to 10% while unplugged (isolated from unplug auto-stop by starting already-unplugged at
      50%): services + media codec shut down cleanly only at 10%, not at 50%.
- [x] **Dark mode**: toggle system dark theme → all screens adapt.
      Note: toggled via `adb shell cmd uimode night no/yes`. In light mode Home, Settings, and
      Recordings all adapt correctly (light backgrounds, dark text, themed cards/tabs); Live view
      already shown theme-aware. Restored device to dark after testing.
- [x] **Automation**: with toggles on, connecting the charger auto-starts and disconnecting auto-stops.
      Note: unplug auto-stop verified via `dumpsys battery unplug` (WidgetService runtime receiver).
      The manifest PowerConnectionReceiver is blocked in background by ColorOS for POWER_DISCONNECTED;
      POWER_CONNECTED still fires and auto-starts. See auto-stop fix in commit 583d58c.
- [x] Turn **location off** → recording and Recordings/HUD do not crash.
      Note: with system Location OFF, recording starts/writes normally and the Live view HUD shows
      GPS "Acquiring…" (speed 0 km/h) with no crash — logcat clean, no FATAL/AndroidRuntime.
- [x] Play a clip → its GPS **metadata** is present (in a player/details view). 
- [   Note: recorder now embeds an ISO 6709 location tag via FileOutputOptions.setLocation()
      (commit c870ce3). Verified with ffprobe: location=+12.9029+077.7099/. Previously absent.

- [ ] 4K resolution support. only available if device camera supports it.
- [ ] Since a large Settings button is already available on home screen, we can tunr the settings icon on top right into dark/light mode switcher with appropriate icon.
- [ ] Night mode in camera if its possible. And if possible, allow a feature to sett automatic switching recording to night mode (from next recording onwards if recording is already running) during configured hours of the day
-

## 7. Possible next steps / open items

- Front camera support / frame-rate option.
- G-Sensor (accelerometer) impact auto-lock of the current clip.
- "Exported" tab / share/export flow.
- Tune overlay text position/orientation per device if needed.

## 8. Prompt to bootstrap the new session

> "Continue work on the Open Dash Cam Android app. The code is on my fork
> `punch-github/open_dash_cam_android`, branch `feature/dashcam-android13-modernization`.
> Read SESSION_HANDOFF.md in the repo root for full context. Toolchain: JDK 17 + Gradle 8.2 +
> AGP 8.1.4, compileSdk 34 / targetSdk 33. Build with `.\gradlew.bat :mobile:assembleDebug`.
> A phone is now connected via adb, so please install and help me test the section-6 verification
> checklist and fix anything that misbehaves. Priorities: verify charge-based auto start/stop per my
> device, and (optionally) start the Camera2/CameraX migration to burn timestamp/GPS into the video."
