# Stream Keep Alive - Android TV App

[![Android CI](https://github.com/amit113210/stream-keep-alive/actions/workflows/android-ci.yml/badge.svg)](https://github.com/amit113210/stream-keep-alive/actions/workflows/android-ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/amit113210/stream-keep-alive?display_name=tag)](https://github.com/amit113210/stream-keep-alive/releases/latest)

אפליקציית Android TV שמונעת הודעות "האם אתם עדיין צופים?" באפליקציות סטרימינג.

## מה זה עושה
- מזהה אוטומטית חלונות "האם אתם עדיין צופים?" וסוגר אותם.
- מפעיל מנגנון keep-alive חכם כדי להפחית הופעת הודעות מראש.
- כולל מצב הגנה מפורש עם בחירת Mode ישירה מהמסך: `NORMAL` / `AGGRESSIVE` / `MAXIMUM`.
- עובד מקומית על המכשיר, ללא שרת חיצוני.
- תואם Android TV (API 21+) ללא Root.

## חדש ב-1.7.5
- שדרוג ממוקד ל-Netflix dialog auto-dismiss עם סריקה של כל חלונות הנגישות (multi-window), לא רק active root.
- נוספה לולאת Netflix אגרסיבית (1s) לזיהוי מהיר של "עדיין צופה" גם כשאירועי UI דלילים.
- נוספה אסטרטגיית לחיצה מדורגת עם fallback בטוח ל-Bounds Tap על כפתור היעד בלבד.
- נוספה טלמטריה ייעודית לדיאלוגים: מספר חלונות, טקסט יעד, חלון יעד, ושיטת קליק בפועל.

## חדש ב-1.7.4
- מסך בית מינימלי אמיתי ל‑TV: רק Start/Stop + Mode + More Actions.
- כל הפעולות המתקדמות רוכזו תחת More Actions (נגישות, Notification, Power, Hotspot, Calibration, Debug/Runtime).
- אזור עדכונים באתר עבר למבנה מרוכז של 2 אקורדיונים בלבד.
- אחידות מתקינים: Mac + Windows עם ברירת מחדל `main` וולידציית גרסה אחרי התקנה.

## חדש ב-1.7.3
- שיפורי UI נוספים למסך TV: כפתורים קומפקטיים יותר ופריסה מאוזנת.
- שיפור תצוגת אזור העדכונים באתר (Accordion קומפקטי יותר).
- ליטושים אחרונים ליציבות התקנה ופריסה לקראת פרודקשן.

## חדש ב-1.7.2
- מצב `TV Minimal` חדש: סטטוס קצר וברור כברירת מחדל.
- כפתור `Show Runtime Details` להצגה/הסתרה של פירוט Checklist מלא.
- נראות מסך בית נקייה יותר עם עומס מופחת לטלוויזיה.

## חדש ב-1.7.1
- שיפורי UX למסך Android TV: בחירת מצב נוחה וברורה יותר עם השלט.
- ליטוש פריסת כפתורים למסך נקי ופחות מגושם.
- עדכון אתר עם אזור עדכונים נפתח (Accordion Changelog).

## חדש ב-1.7
- כפתור מצב ייעודי במסך הראשי (ללא Long-Press נסתר).
- `MAXIMUM` זמין ונגיש ישירות עם השלט.
- Protection Session + Telemetry משופרים לניטור מצב בזמן אמת.
- שיפורי יציבות וניהול צריכת חשמל במכשירי Android TV.

## התקנה מהירה
- אתר ההתקנה: [stream-keep-alive.vercel.app](https://stream-keep-alive.vercel.app/index.html)
- הורדת סקריפטים ישירות:
  - Mac: [install_mac.command](https://github.com/amit113210/stream-keep-alive/raw/main/installer/install_mac.command)
  - Windows: [install_windows.bat](https://github.com/amit113210/stream-keep-alive/raw/main/installer/install_windows.bat)
- ברירת מחדל במתקין:
  - `main` כברירת מחדל (מומלץ) להבטחת APK הכי עדכני.
  - אפשר לבחור `release` אם רוצים רק נכסים שפורסמו כ-Release.
  - תמיד יש fallback אוטומטי בין המקורות במקרה כשל הורדה.
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
