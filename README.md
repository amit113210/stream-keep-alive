# TV Connectivity Hub (Android TV)

TV Connectivity Hub is a personal-use Android TV utility for quick connectivity actions, settings shortcuts, and readiness diagnostics.

It is designed to be practical and transparent:
- local utility workflow
- TV-friendly UI
- clear permission/status checks
- built-in setup/troubleshooting guidance

## Product Positioning

**Tagline:** Connectivity shortcuts and readiness checks for Android TV.

TV Connectivity Hub helps you reach common TV network/system settings quickly, monitor utility-session readiness, and apply optional power/display hardening for better session continuity.

## Core Features

- **Hotspot & connectivity helper**
  Quick access paths for network/hotspot workflows (device support varies by OEM/Android TV build).
- **Power & system shortcuts**
  One place to open battery optimization, write settings permission, app info, accessibility, and notification access.
- **Readiness dashboard**
  Clear YES/NO checklist for critical requirements.
- **Utility session with telemetry**
  Persistent foreground companion + runtime telemetry for debugging and support.
- **TV-first UX**
  D-pad friendly controls, compact layout, and focused primary actions.

## What This App Does NOT Do

- It does **not** promise to bypass streaming-service policies.
- It does **not** guarantee removal of every in-app interruption in every app/device combination.
- It does **not** use kiosk mode, device owner, or lock-task appliance behavior.

## Installation

### Option A: Mac Installer
Run:

```bash
APK_CHANNEL=main ./installer/install_mac.command
```

### Option B: Windows Installer
Run `installer/install_windows.bat` and choose channel `main` (recommended).

### Option C: Manual ADB
Install from:
- `installer/apk/StreamKeepAlive.apk` (stable installer artifact)

## Setup (first run)

1. Enable Accessibility service for TV Connectivity Hub.
2. Enable Notification Access (recommended for richer telemetry/signals).
3. Disable battery optimization for the app (recommended).
4. Allow Write Settings (optional but recommended) for display-timeout hardening.
5. Start Utility Session from the main screen.

## Permissions Explained

- `BIND_ACCESSIBILITY_SERVICE` — local UI automation/readiness workflows.
- `POST_NOTIFICATIONS` — persistent foreground session notification.
- `WAKE_LOCK` — best-effort continuity while utility session is active.
- `WRITE_SETTINGS` — optional display timeout hardening apply/restore.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — optional reliability improvement.

## Development

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Documentation

- [Quick Start](./QUICK_START.md)
- [Features](./FEATURES.md)
- [Permissions & Settings](./PERMISSIONS_AND_SETTINGS.md)
- [Troubleshooting](./TROUBLESHOOTING.md)

## Release

Current pivot release target: **v2.0.0**

APK distribution paths remain technically compatible:
- `installer/apk/StreamKeepAlive.apk`
- versioned artifacts in `installer/apk/`

## License

MIT License.
