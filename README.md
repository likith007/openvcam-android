# OpenVCam

A clean-room, open-source virtual camera module for rooted Android. It hooks Camera1/Camera2 in
apps you select **in LSPosed/Vector** and replaces their feed with an image or video you choose.

It is an independent implementation. It does not use, patch, or derive from any proprietary app,
and it contains **no login, licensing, or subscription code**.

## Features

- **Clean minimal UI** — one screen with a gradient status banner and rounded cards
- **Environment status** — root access, detected hooking framework, and live hook heartbeat
- **App scope** — one button opens LSPosed/Vector where you select the apps to hook
- **Playback controls** — play, pause, reset, playback speed, and video seek
- **Media source** — Main + 4 presets; pick an image or a video from your gallery
- **Live preview** — shows exactly how the selected media will look with all edits applied
- **Editing** — crop presets (Full, Square, 4:3, 16:9, 9:16), rotate ±90°, mirror, and zoom
- **Enable/disable button** — enabled once a media source is selected
- **Root helper** — one-tap grant of notification permission and root-started keep-alive service
- Camera1: preview callback NV21 injection, preview surface replacement, photo capture replacement
- Camera2: session output redirection + GL preview rendering, JPEG still-image replacement,
  `SessionConfiguration` support
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
2. Open OpenVCam. Allow notifications if asked. Tap **Fix root** if root is not detected.
3. Tap **Open LSPosed scope** (or Vector), enable the **OpenVCam** module, and select the app
   whose camera should be replaced. Scope changes require force-stopping that app.
4. Pick an image or video, adjust crop/rotation/mirror/zoom, then tap **Enable hook**.
5. Force-stop and reopen the app you scoped. Use **Disable hook** to return to the real camera.

## Status indicators

- **Root access** — whether `su` is available and grants uid 0
- **Framework** — detected LSPosed / Vector / LSPatch / Xposed installation
- **Hook integration** — green when a scoped app has recently reported a heartbeat from the hook

## Known limitations

- LSPosed/Vector module scope cannot be changed programmatically; it must be set in the framework
  UI. After changing scope, force-stop the target app before opening it again.
- Camera2 supports ordinary capture sessions, `SessionConfiguration` and basic reprocessing;
  physical multi-camera sessions may still vary by device.
- Compatibility varies by device, camera API, and target app. Test before relying on it.
- Use only where you have permission to substitute the camera feed.
