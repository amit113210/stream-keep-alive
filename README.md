# Stream Keep Alive - Android TV App

[![Android CI](https://github.com/amit113210/stream-keep-alive/actions/workflows/android-ci.yml/badge.svg)](https://github.com/amit113210/stream-keep-alive/actions/workflows/android-ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/amit113210/stream-keep-alive?display_name=tag)](https://github.com/amit113210/stream-keep-alive/releases/latest)

אפליקציית Android TV שמונעת הודעות "האם אתם עדיין צופים?" באפליקציות סטרימינג.

## מה זה עושה
- מזהה אוטומטית חלונות "האם אתם עדיין צופים?" וסוגר אותם.
- מפעיל מנגנון keep-alive חכם כדי להפחית הופעת הודעות מראש.
- עובד מקומית על המכשיר, ללא שרת חיצוני.
- תואם Android TV (API 21+) ללא Root.

## התקנה מהירה
- אתר ההתקנה: [stream-keep-alive.vercel.app](https://stream-keep-alive.vercel.app/index.html)
- הורדת סקריפטים ישירות:
  - Mac: [install_mac.command](https://github.com/amit113210/stream-keep-alive/raw/main/installer/install_mac.command)
  - Windows: [install_windows.bat](https://github.com/amit113210/stream-keep-alive/raw/main/installer/install_windows.bat)
- ברירת מחדל במתקין:
  - קודם מנסה `latest GitHub Release asset` בפורמט `StreamKeepAlive-vX.Y.apk`
  - אם אין asset מתאים, מבצע fallback ל־`main`:
    `installer/apk/StreamKeepAlive.apk`
  - המתקין מציג מקור הורדה, SHA256, וגרסה מותקנת בפועל (versionName/versionCode)

## עדכון / הסרה
- עדכון: הרץ שוב את סקריפט ההתקנה (מתקין את הגרסה העדכנית).
- הסרה:
  1. כבה את שירות הנגישות ב-TV.
  2. הסר את האפליקציה דרך Settings → Apps.
  3. או דרך ADB: `adb uninstall com.keepalive.yesplus`

## FAQ קצר

**זה בטוח?**  
כן. האפליקציה עובדת מקומית ולא דורשת חשבון משתמש.

**למה צריך ADB?**  
ADB משמש להתקנה והגדרה ראשונית אוטומטית על Android TV.

**זה עובד על כל סטרימר?**  
עובד על רוב מכשירי Android TV. תמיכה משתפרת לפי דיווחים מהשטח.

**מה עושים אם זה לא עובד מיד?**  
הרץ את סקריפט ההתקנה שוב וודא ששירות הנגישות פעיל.

## קישורים חשובים
- Releases: [Latest Release](https://github.com/amit113210/stream-keep-alive/releases/latest)
- Issues / בקשות פיצ'ר: [GitHub Issues](https://github.com/amit113210/stream-keep-alive/issues)
- אתר + מדריך מלא: [stream-keep-alive.vercel.app](https://stream-keep-alive.vercel.app/index.html)
- פרטיות ותנאי שימוש: באתר הרשמי בעמוד הראשי.

## למפתחים
- Build מקומי:
  ```bash
  ./gradlew testDebugUnitTest assembleDebug
  ```
- CI רץ אוטומטית בכל Push/PR ל-`main`.
- תגית `v*` מפעילה release workflow.
- הכנת APK להפצה למתקין:
  ```bash
  ./scripts/prepare_installer_apk.sh --allow-debug
  ```
  (בלי `--allow-debug` הסקריפט דורש סביבת חתימה ל־release חתום.)
