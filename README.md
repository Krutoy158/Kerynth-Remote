# Kerynth Remote

Kerynth Remote is an Android-to-Android remote assistance project with two separately installable applications.

## Applications

### Kerynth Remote Agent

Installed on the device being assisted. It contains the setup wizard, owns device permissions, displays incoming messages and media, and provides visible session controls.

### Kerynth Remote Console

Installed on the operator's device. It will pair with Agent and provide controls for messages, image delivery, sound alerts, screen-sharing requests, and Lost Mode actions.

## Current stage: permission setup wizard

- Separate `:receiver` and `:console` Android application modules
- Separate application IDs and launcher activities
- Agent setup screen checks notification, foreground location, background location, Device Admin, and Device Owner status
- Agent can request notification and foreground location permissions through Android runtime dialogs
- Background location opens the app settings on Android 11+; the owner must select the appropriate location permission option there
- Agent Device Admin component requests only the force-lock policy
- Agent can lock its own screen locally after the owner activates Device Admin
- Agent shows reboot only when Android reports that the app is Device Owner
- Screen capture is explicitly marked as requiring system consent for every session
- Console contains a separate dashboard for the future connected-device capability status
- GitHub Actions builds both debug APKs

The pairing and remote command transport are not implemented yet. Console controls are UI placeholders and do not send commands.

## Android permission behavior

- **Notifications:** Android 13+ requires the runtime `POST_NOTIFICATIONS` grant.
- **Foreground location:** Agent asks for fine or approximate location using the Android runtime permission dialog.
- **Background location:** Android 11+ requires a separate change in app settings. The setup wizard opens the Agent app settings and explains the required step. The owner can decline and continue without this feature.
- **Device Admin:** The owner activates the platform administrator screen. Agent requests only the force-lock policy.
- **Device Owner:** This is not an ordinary runtime permission. It requires a separate managed-device provisioning flow and is not enabled by the setup wizard.
- **Screen capture:** Android requires system consent for each MediaProjection session.
- **Accessibility:** Not requested by this setup wizard. Any future input-control feature must be separately explained and explicitly enabled by the device owner.

## Lost Mode

Lost Mode is planned for devices the owner has explicitly paired and enrolled.

- **Lock:** supported through `DevicePolicyManager.lockNow()` after the owner activates the Agent Device Admin component. The device should have a secure screen lock configured.
- **Reboot:** supported only when Agent is enrolled as Android Device Owner, or has privileged system permissions unavailable to ordinary apps. Reboot is not power-off.
- **Location:** planned as opt-in location sharing. Android may limit background updates, and location can be unavailable when the phone is offline, powered off, has location disabled, or has restricted background execution.
- **Power off:** a normal third-party Android app cannot remotely power a phone off.

All remote commands will require an authenticated, paired Console. Active remote sessions must remain visible on Agent and revocable by the device owner. The project does not include hidden overlays, covert collection, stealth persistence, or silent control.

## Build

Build both debug APKs:

```bash
gradle :receiver:assembleDebug :console:assembleDebug
```

Outputs:

- `receiver/build/outputs/apk/debug/receiver-debug.apk`
- `console/build/outputs/apk/debug/console-debug.apk`

GitHub Actions uploads them as separate artifacts on pushes to `main`.
