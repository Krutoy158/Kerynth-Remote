# Kerynth Remote

Kerynth Remote is a private Android-to-Android remote assistance project built as two separate apps.

## Applications

- **Agent** runs on the phone being managed.
- **Console** runs on the phone used to send commands.

## Implemented in 0.5.0

- Local TCP connection over the same Wi-Fi network on port 43177
- One-time 12-character pairing code with a five-minute expiry and failed-attempt lockout
- Challenge-response authentication using HMAC-SHA256
- AES-256-GCM encrypted command and response payloads
- Persistent paired-device key storage and local revocation on Agent
- Visible foreground notification while the Agent server is running
- Agent status and battery query
- Remote screen lock through Android Device Admin
- Last-known location query when Android location permission is granted
- Text delivery through a normal Android notification
- Short audible signal
- Image delivery up to 3 MiB, with bounds validation and in-app preview on Agent
- Console confirmation before requesting a remote reboot

## Current platform limitations

- **Network scope:** 0.5.0 connects directly over one local Wi-Fi network. It does not yet work across separate networks or mobile data. Internet access needs a deployed relay service; the repository does not claim that service is already hosted.
- **Reboot:** Android permits this app to request a reboot only when it is enrolled as Device Owner. Device Admin alone is insufficient. Reboot is not power-off.
- **Location:** Console receives the last known Android location. Its age and reported accuracy are included; the result may be stale or unavailable. Background access must be granted separately by the device owner.
- **Screen sharing:** not implemented. Android requires a visible system MediaProjection approval on Agent for each new capture session.
- **Remote touch/control:** not implemented. No hidden AccessibilityService, overlay, or silent input automation is used.
- **Notifications:** Android 13+ requires the user to grant notification permission for ordinary message notifications.
- **Power off:** a normal third-party Android app cannot remotely power off the phone.

## Pairing and use

1. Install Agent on the managed phone and Console on the second phone.
2. Connect both phones to the same Wi-Fi network.
3. Open Agent and grant the permissions needed for the actions you intend to use.
4. Tap **Запустить Agent**. Keep Agent open and copy the IP address and temporary code shown on its screen.
5. Enter the IP address and code in Console, then tap **Сопрячь устройства**.
6. After pairing, the code is consumed. Console stores its device key. Agent shows that Console is paired.
7. Use Console to send commands. Stop Agent from its visible notification or the Agent screen when it is not needed.

To revoke access, use **Отозвать доступ и создать новый код** on Agent. This invalidates the previous Console key.

## Build

Both debug APKs are built by GitHub Actions:

```bash
gradle :receiver:assembleDebug :console:assembleDebug
```

Artifacts:

- `receiver/build/outputs/apk/debug/receiver-debug.apk`
- `console/build/outputs/apk/debug/console-debug.apk`

Workflow: https://github.com/Krutoy158/Kerynth-Remote/actions
