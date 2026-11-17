# Kerynth Remote

Android-to-Android remote assistance and media sharing.

## Current stage

- Initial Jetpack Compose application shell
- Explicit receiver-mode toggle
- Image picker and local preview
- GitHub Actions debug APK build

## Safety model

Sessions are user-initiated and visible on the receiving device. Media is displayed inside the app; no hidden overlays, background display, or covert device access.

## Build

Run:

```bash
gradle assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
