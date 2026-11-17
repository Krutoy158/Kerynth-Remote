# Kerynth Remote

Kerynth Remote is an Android-to-Android remote assistance project with two separately installable applications.

## Applications

### Kerynth Remote Agent
Installed on the device being assisted. It owns the incoming-session permissions, displays incoming messages and media, and provides a visible way to pause or revoke access.

### Kerynth Remote Console
Installed on the operator's device. It will pair with Agent and provide controls for messages, image delivery, sound alerts, and screen-sharing requests.

## Current stage: two-app UI scaffold

- Separate `:receiver` and `:console` Android application modules
- Separate application IDs and launcher activities
- Agent interface with a local demonstration toggle for incoming-session availability
- Console interface listing the planned remote actions
- Notification permission declared for Agent
- GitHub Actions workflow builds both debug APKs

The current buttons are UI placeholders. Pairing, networking, message delivery, media transfer, screen sharing, and device-policy actions are not implemented yet.

## Safety and consent model

- Device pairing must be initiated and approved on the Agent device.
- Active remote sessions must remain visible on the Agent device and be revocable there.
- Remote messages must use ordinary, user-visible Android notifications when notification permission is granted.
- Images are opened in the Agent app; the app will not silently force full-screen content over other applications.
- Sound alerts must be opt-in, rate-limited, and locally muteable.
- Screen capture must use Android's system MediaProjection consent flow.
- No hidden control, covert collection, stealth persistence, or silent installation is part of this project.

## Lost Mode

Lost Mode is planned as an explicit, owner-enabled feature. The normal consumer-app path should provide safe actions such as an audible alert and, where separately permitted, location sharing.

Android's `DevicePolicyManager.lockNow()` can lock a device only when the required device-admin authority is active. `DevicePolicyManager.reboot()` is restricted to a device owner (or holders of a privileged system permission); rebooting is not the same as powering off. Full device-management enrollment is a separate opt-in setup and is not enabled by this scaffold.

The app will not attempt to bypass Android's device-owner, permission, or lock-screen safeguards.

## Build

Build both debug APKs:

```bash
gradle :receiver:assembleDebug :console:assembleDebug
```

Outputs:

- `receiver/build/outputs/apk/debug/receiver-debug.apk`
- `console/build/outputs/apk/debug/console-debug.apk`

GitHub Actions uploads them as separate artifacts on pushes to `main`.
