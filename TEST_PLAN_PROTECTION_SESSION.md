# Protection Session Manual Test Plan (Playback-Aware)

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

## 4. Enable Notification Listener Access
1. In app main screen press `Notification Access` (shown only if missing).
2. Enable `Playback Notification Listener` for `Stream Keep Alive`.
3. Return to app and verify checklist shows `Notification Access: YES`.

## 5. Start Protection Session
1. Press `Start Protection`.
2. Verify persistent notification appears: `Streaming protection active`.
3. Verify app shows `Protection: ON`.
4. Verify telemetry `Session: active=true` and `fgs=true`.

## 6. Netflix Test (15+ min)
1. Open Netflix and play content for at least 15 minutes.
2. Verify telemetry:
   - `Playback: state=PLAYING_ACTIVE` (or fallback with explicit source/confidence),
   - `Gate: runNow=true`,
   - `Current: profile=com.netflix`.
3. Confirm heartbeat fires repeatedly and does not stop when UI events are quiet.
4. If Hebrew still-watching dialog appears:
   - verify it is dismissed,
   - verify logs include Netflix-specific strategy,
   - verify no click on `שאל אותי שוב מאוחר יותר` or `סיימתי`.

## 7. YouTube Test (15+ min)
1. Open YouTube/YouTube TV and play content for at least 15 minutes.
2. Confirm playback source/confidence and heartbeat interval adjust to YouTube profile.
3. Confirm escalation step stays low during healthy dispatch results.

## 8. Playback Fallback Validation
1. Disable Notification Listener access temporarily.
2. Keep Accessibility + Protection Session ON.
3. Verify app still runs with package fallback and telemetry clearly shows fallback source.
4. Re-enable Notification Listener access.

## 9. Notification Persistence
1. While protection is active, press Home and reopen apps.
2. Verify foreground notification stays visible.
3. Re-open app and verify `foregroundServiceRunning=true` in telemetry.

## 10. Heartbeat Logs
1. Capture logcat while a supported package is active.
2. Verify logs include:
   - `[PLAYBACK] state/source/confidence`,
   - `[HB] gated_by_playback=...`,
   - `[HB] scheduled`, `[HB] fire`,
   - escalation logs only when needed.
3. Verify heartbeat stops if protection session is stopped.

## 11. Dialog Burst Mode
1. During Netflix playback, monitor dialog scan cadence and logs.
2. Verify faster cadence during playback (`dialogBurstModeActive` or Netflix playback cadence).
3. Verify burst mode enters/exits cleanly in telemetry/logs.

## 12. Dialog Dismissal
1. Trigger or wait for a known `still watching` dialog.
2. Verify logs include `[DIALOG] detected` and dismiss strategy.
3. Confirm no accidental negative action clicks.

## 13. Stop Action
1. Press `Stop Protection` in app.
2. Press `Stop` action from notification.
3. In both cases verify:
   - notification disappears,
   - protection status becomes OFF,
   - heartbeat and wake lock counters stop changing.
