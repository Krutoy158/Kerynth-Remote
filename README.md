# Kerynth Remote

Kerynth Remote is an Android-to-Android remote assistance project with two separately installable applications.

## Applications

### Kerynth Remote Agent

Installed on the device being assisted. It owns incoming-session permissions, displays incoming messages and media, and provides a visible way to pause or revoke access.

### Kerynth Remote Console

Installed on the operator's device. It will pair with Agent and provide controls for messages, image delivery, sound alerts, screen-sharing requests, and Lost Mode actions.

## Current stage: two-app UI and local Lost Mode foundation

- Separate `:receiver` and `:console` Android application modules
- Separate application IDs and launcher activities
- Agent interface with a local demonstration toggle for incoming-session availability
- Agent Device Admin receiver requesting only the force-lock policy
- Agent can request Device Admin activation and lock its own screen locally after the owner approves the system prompt
- Agent exposes a reboot action only when Android reports that the app is the Device Owner
- Console includes a Lost Mode panel showing planned lock, locate, and reboot actions
- GitHub Actions builds both debug APKs

The remote pairing and command transport are not implemented yet. The local Agent lock action is not a remote command. The Console buttons are placeholders and do not send commands.

## Lost Mode capability rules

- **Lock:** supported through `DevicePolicyManager.lockNow()` after the owner explicitly activates the Agent Device Admin component with the force-lock policy. The phone must have a secure screen lock configured.
- **Reboot:** supported only when the Agent is enrolled as Android Device Owner, or the app has a privileged system permission that normal Play-installed apps do not receive. Reboot is not power-off.
- **Location:** planned as opt-in location sharing. The owner must grant foreground and background location access and keep Android location services enabled. On Android 11 and later, background location is enabled separately in system settings. Location can be unavailable when the phone is offline, powered off, has location disabled, or has restricted background execution.
- **Power off:** a normal third-party Android app cannot remotely power a phone off. Kerynth Remote will not claim otherwise.

Lost Mode commands will only be accepted from a paired, authenticated Console. The Agent will show that Lost Mode is enabled and provide a way to revoke pairing. It will not use hidden overlays, covert collection, or stealth persistence.

## Other safety and consent rules

- Device pairing must be initiated and approved on the Agent device.
- Active screen-sharing sessions must remain visible on the Agent device and be revocable there.
- Remote messages use ordinary, user-visible Android notifications when notification permission is granted.
- Images are opened in the Agent app; the app will not silently force full-screen content over other applications.
- Sound alerts are opt-in, rate-limited, and locally muteable.
- Screen capture uses Android's system MediaProjection consent flow.

## Build

Build both debug APKs:

```bash
gradle :receiver:assembleDebug :console:assembleDebug
```

Outputs:

- `receiver/build/outputs/apk/debug/receiver-debug.apk`
- `console/build/outputs/apk/debug/console-debug.apk`

GitHub Actions uploads them as separate artifacts on pushes to `main`.
