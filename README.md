# OpenVCam

A clean-room, open-source virtual camera module for rooted Android. It hooks Camera1/Camera2 in
apps you select in LSPosed/Vector and replaces their feed with an image or video you choose.

It is an independent implementation. It does not use, patch, or derive from any proprietary app,
and it contains **no login, licensing, or subscription code**.

## Features

- Main source + four independently configurable presets (image or video)
- Per-source rotation, mirror, and zoom
- Global play/pause, speed, virtual/real switch, and audio mode
- Camera1: preview callback NV21 injection, preview surface replacement, photo capture replacement
- Camera2: session output redirection + GL preview rendering (basic/ordinary sessions)
- Floating draggable control bubble over other apps
- Installed target-app list with remembered selections
- Exported `ContentProvider` used by hooks to read the shared config and media

## Build

```sh
cd /data/data/com.termux/files/home/POCO-DEV/APK-Lab/OpenVCam
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk
export PATH=/data/data/com.termux/files/home/gradle-8.7/bin:$JAVA_HOME/bin:$PATH
gradle assembleDebug --no-daemon
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

The Xposed API is compile-only (`app/libs/xposed-api-stub.jar`); the real API is supplied by
LSPosed/Vector at runtime, so no Xposed classes are packaged into the APK.

## Install and use

1. Install the APK on a rooted device running Android 9+ (API 28+).
2. Install LSPosed or Vector and enable the **OpenVCam** module.
3. In the module scope, select the apps whose camera you want to replace.
4. Open OpenVCam, pick an image/video for **Main** and/or presets.
5. Force-stop and reopen each target app.
6. Use the Controls tab or floating bubble to switch presets and adjust rotation/mirror/zoom/speed.

## Known limitations

- LSPosed module scope cannot be changed programmatically; the Targets list is a reminder helper.
- Camera2 support focuses on ordinary `createCaptureSession` flows; complex reprocessing,
  physical multi-camera sessions, and `SessionConfiguration` sessions may not be replaced.
- Camera2 still-image replacement is not implemented.
- Compatibility varies by device, camera API, and target app. Test before relying on it.
- Use only where you have permission to substitute the camera feed.
