# OpenVCam

A clean-room, open-source virtual camera module for rooted Android. It hooks Camera1/Camera2 in
apps you select in LSPosed/Vector and replaces their feed with an image or video you choose.

It is an independent implementation. It does not use, patch, or derive from any proprietary app,
and it contains **no login, licensing, or subscription code**.

## Features

- **Clean minimal UI** — bottom navigation (Home, Media, Targets, Settings), material icons,
  gradient status banners, rounded cards, and smooth animated sections
- **Theme switch** — System, Light, or a dark **Pro** look, saved with the rest of your config
- **Environment status** — root access, installed hooking framework, active hook integration,
  and the number of selected scopes at a glance
- **Playback controls** — play, pause, reset, virtual/real toggle, playback speed, and audio mode
- **Target apps** — searchable, selectable list of installed apps whose camera should be hooked
- **Media source** — Main + 4 presets; pick an image or a video from your gallery
- **Live preview** — shows exactly how the selected media will look with all edits applied
- **Editing** — crop to universal presets (Full, Square, 4:3, 16:9, 9:16), rotate ±90°, mirror,
  and zoom, with changes applied to both the preview and the injected camera feed
- **Enable/disable switch** — instantly turn the virtual camera on or off
- **Root helper** — one-tap grant of overlay and notification permissions plus root-started
  background services for best reliability
- Camera1: preview callback NV21 injection, preview surface replacement, photo capture replacement
- Camera2: session output redirection + GL preview rendering, JPEG still-image replacement
- Floating draggable control bubble over other apps
- Exported `ContentProvider` used by hooks to read the shared config and media

## Build

```sh
cd /data/data/com.termux/files/home/POCO-DEV/APK-Lab/OpenVCam
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk
export PATH=/data/data/com.termux/files/home/gradle-8.7/bin:$JAVA_HOME/bin:$PATH
gradle assembleDebug --no-daemon
```

Outputs:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

For a release build with your own key, create `keystore.properties` in the project root:

```properties
storeFile=/absolute/path/to/your/release.jks
storePassword=your_store_password
keyAlias=your_alias
keyPassword=your_key_password
```

Then run `gradle assembleRelease`. Both `keystore.properties` and `keys/` are gitignored so
secrets are never committed. Without that file, release builds are unsigned.

The Xposed API is compile-only (`app/libs/xposed-api-stub.jar`); the real API is supplied by
LSPosed/Vector at runtime, so no Xposed classes are packaged into the APK.

## Install and use

1. Install the APK on a rooted device running Android 9+ (API 28+).
2. Open OpenVCam. Allow notifications (Android 13+) and grant overlay access if asked.
3. Tap **Fix with root** on the Environment card to grant permissions and launch the services
   with root for best results.
4. Install LSPosed or Vector, enable the **OpenVCam** module, and scope the apps from the
   **Target apps** list. Scope must be set in the framework's own UI — OpenVCam can only remember
   your selection.
5. Pick an image or video for Main (and optionally the presets), adjust crop/rotation/mirror/zoom,
   then force-stop and reopen each target app.
6. The live preview shows exactly what will be injected. Use the enable switch to toggle between
   the virtual feed and the real camera.

## Status indicators

- **Root access** — whether `su` is available and grants uid 0
- **Framework** — detected LSPosed / LSPatch / Xposed / EdXposed installation
- **Hook integration** — green when a scoped app has recently reported a heartbeat from the hook
- **Selected scopes** — how many target apps you have selected

## Known limitations

- LSPosed module scope cannot be changed programmatically. The Target apps list is also required:
  only apps in that list are allowed to read config/media from OpenVCam's provider.
- Camera2 supports ordinary `createCaptureSession` flows; complex reprocessing, physical
  multi-camera sessions, and `SessionConfiguration` sessions may not be replaced.
- Compatibility varies by device, camera API, and target app. Test before relying on it.
- Use only where you have permission to substitute the camera feed.
