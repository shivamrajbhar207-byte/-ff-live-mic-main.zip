# FF Live Mic

Android app that live-streams your **screen**, **microphone** and (Android 10+) **in-game / internal audio** to **YouTube Live** over RTMP/RTMPS, with an optional floating live-chat overlay.

## Features
- Screen capture via `MediaProjection` → H.264 (RootEncoder 2.8.1)
- Mic + internal playback mixed into AAC audio (Android 10+; mic-only on Android 8/9)
- Quality presets: Auto / HD (720p30) / Ultra (1080p60)
- Stream modes: Landscape (Long), Portrait Crop, Portrait Fit
- YouTube live-chat overlay (floating window + burned into the stream)
- Foreground service with a **Stop Live** notification action
- Stream key is masked, validated, never logged, and cleared from the UI after hand-off

## Requirements
- Android 8.0+ (API 26); Android 10+ recommended for game audio
- JDK 17, Android SDK 35, Gradle 8.9

## Build
```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/
```
CI: the *Build Android APK* workflow builds on every push to `main` and uploads the APK as an artifact.

### Optional stable signing
Add these repository secrets so every build uses the same key (otherwise the default debug key is used):
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## Usage
1. In YouTube Studio → *Go Live* → *Stream*, copy the **Server URL** and **Stream key** (prefer RTMPS if offered).
2. Open the app, paste both, pick quality and mode.
3. Grant microphone, notification and screen-capture permissions, then start.
4. Stop from the app or the notification.

## Security notes
- Never commit stream keys or keystores (`.gitignore` already excludes `*.jks`).
- Plain `rtmp://` does not encrypt the stream key in transit; use `rtmps://` when available.

## Status
See `PROJECT_STATUS.txt`. Not yet verified on a physical device or against a live YouTube ingest.
