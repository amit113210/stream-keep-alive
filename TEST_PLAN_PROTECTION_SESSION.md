# Protection Session Manual Test Plan

## 1. Fresh Install
1. Uninstall previous app build from the TV.
2. Install latest APK.
3. Open the app and confirm version is visible on main screen.

## 2. Enable Accessibility
1. Press `הגדרות נגישות` in the app.
2. In Android TV accessibility settings, enable `Stream Keep Alive`.
3. Return to app and verify `Accessibility: ON`.

## 3. Notification Permission
1. On Android 13+ confirm notification permission prompt appears when starting protection.
2. Grant permission.
3. If denied, open app notification settings and enable notifications manually.

## 4. Start Protection Session
1. Press `Start Protection`.
2. Verify persistent notification appears: `Streaming protection active`.
3. Verify app shows `Protection: ON`.

## 5. Netflix Test (15+ min)
1. Open Netflix and play content for at least 15 minutes.
2. Check telemetry updates for package/profile/heartbeat fields.
3. Confirm heartbeat keeps running and no long silent period without scheduled heartbeats.

## 6. YouTube Test (15+ min)
1. Open YouTube/YouTube TV and play content for at least 15 minutes.
2. Confirm heartbeat interval switches to YouTube profile values.
3. Confirm escalation step stays low during healthy dispatch results.

## 7. Notification Persistence
1. While protection is active, press Home and reopen apps.
2. Verify foreground notification stays visible.
3. Re-open app and verify `foregroundServiceRunning=true` in telemetry.

## 8. Heartbeat Logs
1. Capture logcat while a supported package is active.
2. Verify logs include `[HB] scheduled`, `[HB] fire`, and `[HB] escalation step changed` only when needed.
3. Verify heartbeat stops if protection session is stopped.

## 9. Dialog Dismissal
1. Trigger or wait for a known `still watching` dialog.
2. Verify logs include `[DIALOG] detected` and dismiss strategy.
3. Confirm no accidental negative action clicks.

## 10. Stop Action
1. Press `Stop Protection` in app.
2. Press `Stop` action from notification.
3. In both cases verify:
   - notification disappears,
   - protection status becomes OFF,
   - heartbeat and wake lock counters stop changing.
