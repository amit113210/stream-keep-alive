# Stream Keep Alive — Installer

Prevents the "Are you still watching?" message on all streaming apps (Android TV).

מונע את הודעת "האם אתם עדיין צופים?" באפליקציות סטרימינג.

---

## 🖥️ How to Install / איך להתקין

### Mac
1. Double-click **`install_mac.command`**
2. Follow the on-screen instructions
3. Done! ✅

### Windows
1. Double-click **`install_windows.bat`**
2. Follow the on-screen instructions
3. Done! ✅

---

## ⚙️ Requirements / דרישות

- Android TV and computer on the **same WiFi network**
- **Developer Options** enabled on Android TV:
  - `Settings → Device Preferences → About → Build` (click 7 times)
  - `Settings → Device Preferences → Developer Options → USB/ADB Debugging → ON`

## 📦 APK Channel Selection

By default the installer now downloads from `main` first (newest build), with fallback to `latest release`.

- Mac: `APK_CHANNEL=release ./install_mac.command` to force release-first
- Windows: `set APK_CHANNEL=release` before running `install_windows.bat`

---

## 📁 Folder Structure

```
installer/
├── install_mac.command    ← Mac installer (double-click)
├── install_windows.bat    ← Windows installer (double-click)
├── apk/
│   └── StreamKeepAlive.apk   ← האפליקציהThe app
└── tools/
    └── (ADB auto-downloaded here)
```

## ❓ Troubleshooting

- **Google Play Protect blocks installation**: Open Play Store → Settings → Play Protect → Disable
- **Cannot connect**: Make sure TV and computer are on the same WiFi, and ADB Debugging is enabled
- **Service turns off**: The installer handles this automatically with `appops` commands
