# Stream Keep Alive - Android TV App

[![Android CI](https://github.com/amit113210/stream-keep-alive/actions/workflows/android-ci.yml/badge.svg)](https://github.com/amit113210/stream-keep-alive/actions/workflows/android-ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/amit113210/stream-keep-alive?display_name=tag)](https://github.com/amit113210/stream-keep-alive/releases/latest)

אפליקציית Android TV שמונעת הודעות "האם אתם עדיין צופים?" באפליקציות סטרימינג.

## איך זה עובד?

**שלוש שכבות הגנה:**

1. **זיהוי ודחייה אוטומטית** — שירות הנגישות מזהה כשהדיאלוג מופיע ולוחץ אוטומטית על כפתור האישור.
2. **סימולציית פעילות (App Level)** — השירות מדמה פעילות מיקרו-סוויפ במרווחים אדפטיביים לפי האפליקציה (למשל Netflix/YouTube).
3. **שמירה על מסך דולק (System Level)** — שימוש ב-WakeLock מנוהל דינמית (מופעל רק כשצריך, עם ניקוי lifecycle).

## מה חדש בגרסה 1.4 (versionCode 5)

- שיפור משמעותי לעמידות השירות במקרי קצה של Android TV.
- Debounce/Throttle חכם לסריקות דיאלוגים להפחתת עומס CPU.
- ניקוי משאבים קשיח ב-`onInterrupt`/`onUnbind`/`onDestroy`.
- טלמטריה ו-Logging משופרים לניטור תקלות.
- תיקון ממשק TV: הכפתורים מעוגנים לתחתית המסך עם פריסה יציבה יותר.

## בניית האפליקציה (Build)

### דרישות מקדימות
- Android Studio (Arctic Fox ומעלה)
- Android SDK 34
- JDK 17+

### בנייה מ-Android Studio
1. פתח את התיקייה `yes-plus-keep-alive` ב-Android Studio
2. המתן לסנכרון Gradle
3. לחץ **Build → Build Bundle(s) / APK(s) → Build APK**
4. קובץ ה-APK נמצא ב: `app/build/outputs/apk/debug/app-debug.apk`

### בנייה מהטרמינל
```bash
cd yes-plus-keep-alive
./gradlew assembleDebug
```

## התקנה על Android TV

### דרך ADB (המומלץ)
```bash
# חבר את ה-Android TV ל-ADB (WiFi או USB)
adb connect <TV_IP_ADDRESS>:5555

# התקן את ה-APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

### דרך USB
העתק את קובץ ה-APK ל-USB, חבר ל-TV, והתקן דרך סייר הקבצים.

## הגדרה (Setup) - חד-פעמי!

After installing, you must enable the Accessibility Service:

1. פתח את **Stream Keep Alive** מתפריט האפליקציות ב-TV
2. לחץ על **"הגדרות נגישות"**
3. מצא את **"Stream Keep Alive"** ברשימה
4. הפעל את השירות ואשר

> ⚠️ **חשוב:** זהו שלב חד-פעמי. השירות ימשיך לרוץ גם אחרי הפעלה מחדש של ה-TV.

## בדיקת פעולה

- פתח את האפליקציה — אינדיקטור ירוק = השירות פעיל
- צפה בלוגים: `adb logcat -s StreamKeepAlive`

## מבנה הפרויקט

```
app/src/main/
├── AndroidManifest.xml
├── java/com/keepalive/yesplus/
│   ├── MainActivity.kt              # מסך ראשי עם סטטוס
│   ├── KeepAliveAccessibilityService.kt  # הלוגיקה המרכזית
│   └── BootReceiver.kt              # הפעלה אוטומטית בהדלקה
└── res/
    ├── layout/activity_main.xml      # ממשק TV
    ├── values/strings.xml            # טקסטים בעברית
    ├── values/colors.xml
    ├── values/themes.xml
    └── xml/accessibility_service_config.xml
```

## דרישות מערכת
- Android TV עם Android 5.0 (API 21) ומעלה
- לא צריך Root!

## אוטומציה ב-GitHub
- בכל Push / PR ל-`main` רץ CI אוטומטי: בדיקות יחידה + בניית APK.
- בכל תגית חדשה (`v*`) נוצר Release אוטומטי עם קובץ `StreamKeepAlive.apk`.
