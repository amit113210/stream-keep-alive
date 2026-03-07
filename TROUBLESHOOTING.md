# Troubleshooting - TV Connectivity Hub

## Session starts but readiness is missing
- Open main screen checklist.
- Fix any item marked NO (Accessibility, Notification Access, Battery, Write Settings).

## Display still turns off
- Ensure Write Settings is granted.
- Ensure screen-timeout hardening is active in telemetry.
- Check TV system display timeout setting and set to Never if available.

## Installer pulled an older build
- Use `APK_CHANNEL=main` on Mac installer.
- Re-run installer and confirm installed `versionName/versionCode` in output.

## Notifications not visible
- Re-enable app notifications from Android TV App settings.

## Device-specific limitations
Some Android TV builds restrict hotspot controls or settings deep-links.
Use App Info and system settings fallbacks from the Power & System menu.
