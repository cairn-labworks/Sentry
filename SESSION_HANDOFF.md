# Open Dash Cam — Session Handoff

This document captures everything done in the modernization/feature session so a new agent
session (on a different machine) can pick up with full context.

> Repo: `maxneaga/open_dash_cam_android` (this is a worktree/branch:
> `open_dash_cam_android.worktrees/dashcam-app-usage-guide-android13`)
> All changes below are **uncommitted** in the working tree unless you decide to commit.

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

- `MainActivity` — launcher; requests permissions, then starts services and finishes (no real UI).
- `WidgetService` — foreground service; draws the overlay REC widget; holds a wake lock; also
  hosts the **battery/thermal monitor** (overheat alert + low-battery safe shutdown).
- `BackgroundVideoRecorder` — foreground service; legacy `android.hardware.Camera` + `MediaRecorder`
  recording into ~app-private external `Movies` dir; loop rotation; folder organization; wake lock;
  exposes `isRecording` / `recordingStartedAt`.
- `models/Widget` — the floating overlay button + menu (View recordings, Save, **Live view**,
  Settings, Stop). Supports **long-press drag** to move vertically.
- `SettingsActivity` — custom **card-based** settings with a **custom segmented control**.
- `ViewRecordingsActivity` + `ViewRecordingsRecyclerViewAdapter` — folder browser with tabs
  (All/Starred), count + total size header, delete-all, and **multi-select delete**.
- `LiveViewActivity` — **telemetry HUD** (no camera image; see decision below).
- `Util` — prefs-backed getters, storage math, event log, notifications, delete helpers.

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

---

## 5. Key decisions / caveats

- **Live view is telemetry-only** (no live camera image). Reason: the legacy `Camera` API +
  background recorder owns the single camera instance; mirroring the preview into a second screen is
  fragile/device-specific. User explicitly chose the reliable telemetry HUD. Showing the real camera
  image would require deeper rework (e.g., sharing the recorder's preview surface at clip boundaries,
  or migrating to Camera2/CameraX) and on-device iteration.
- App still uses the **deprecated legacy Camera API** (works on Android 13, but a Camera2/CameraX
  migration is the eventual right move, especially for reliable screen-off + live preview).
- App theme is `Theme.MaterialComponents.Light.DarkActionBar.Bridge` (needed for Material Slider/
  Switch/Button).
- **All testing so far was compile/build only** — there was **no physical device** on the devbox.
  Everything below needs on-device verification.

---

## 6. Verification checklist (do on a real Android 13 phone)

- [ ] App launches; grant camera, mic, notifications, "display over other apps", (location for HUD).
- [ ] REC widget appears; **long-press + drag** moves it; tap opens the menu.
- [ ] New clips are **silent** (no per-clip beep).
- [ ] **Settings**: every segmented option (Clip length, Resolution, Overheat temp, Low-battery %)
      is tappable and **persists** after reopening; storage slider + switches work; 1080p records at 1080p.
- [ ] After an hour rolls over, clips appear under `yyyy-MM-dd/HH` folders in View Recordings.
- [ ] Recordings screen: count, total size, All/Starred tabs, delete-all, multi-select delete, empty state.
- [ ] **Live view** (eye icon) updates every second; GPS/speed populate after granting location;
      temperature label changes; event log lists clip starts, foldering, warnings.
- [ ] Overheat alert fires above threshold; low-battery auto-shutdown works when enabled + unplugged.

---

## 7. Possible next steps / open items

- Camera2/CameraX migration (enables reliable screen-off recording + a true live camera preview).
- Front camera support / frame-rate option (screenshots showed these; not implemented).
- GPS/speed **overlay burned into the video** (currently HUD-only) + optional watermark/timestamp.
- G-Sensor (accelerometer) impact auto-lock of the current clip (screenshot showed a "G-Sensor" toggle).
- "Exported" tab / share/export flow (screenshot showed an Exported tab; only All/Starred implemented).
- Commit + push the branch; optionally add a GitHub Actions workflow to build signed APKs in CI.
- Consider `.gitignore` for `*.apk` if you don't want the binary committed.

---

## 8. Prompt to bootstrap the new session

> "Continue work on the Open Dash Cam Android app (branch
> dashcam-app-usage-guide-android13). Read SESSION_HANDOFF.md in the repo root for full context.
> Toolchain: JDK 17 + Gradle 8.2 + AGP 8.1.4, compileSdk 34 / targetSdk 33. Build with
> `.\gradlew.bat :mobile:assembleDebug`. A phone is now connected via adb, so please install and
> help me test the verification checklist and fix anything that misbehaves."
