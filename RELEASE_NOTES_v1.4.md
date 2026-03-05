# Stream Keep Alive v1.4

Release date: March 5, 2026

## Highlights

- Major resilience hardening for Android TV accessibility workflow.
- Improved performance under noisy `TYPE_WINDOW_CONTENT_CHANGED` events.
- More reliable lifecycle cleanup to prevent stale handlers and wakelocks.
- UI layout fix for TV screens so action buttons stay visible and accessible.
- Installer APK updated to latest app build.

## Technical Changes

- Added stricter foreground package transition handling based on window state events.
- Added runtime cleanup path for `onInterrupt`, `onUnbind`, and `onDestroy`.
- Added safer micro-swipe coordinates to avoid edge/pixel-zero gesture drops on some TV devices.
- Split scan budgets for dialog detection vs action-click fallback paths.
- Added screen interactive-state logging at dialog detection time.

## Version

- `versionName`: `1.4`
- `versionCode`: `5`

## Upgrade Notes

- Reinstall via installer script to ensure APK `1.4 (5)` is installed.
- If UI cache persists on device launcher, reboot Android TV once after reinstall.
