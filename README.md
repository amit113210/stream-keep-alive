# Yes Plus Keep Alive - Android TV App

אפליקציית Android TV שמונעת את הודעת "האם אתם עדיין צופים?" של יס פלוס.

## איך זה עובד?

**שתי שכבות הגנה:**

1. **זיהוי ודחייה אוטומטית** — שירות הנגישות מזהה כשהדיאלוג מופיע ולוחץ אוטומטית על כפתור האישור
2. **סימולציית פעילות** — כל 45 דקות השירות מדמה לחיצת שלט כדי למנוע את הופעת הדיאלוג מלכתחילה

## בניית האפליקציה (Build)

### דרישות מקדימות
- Android Studio (Arctic Fox ומעלה)
- Android SDK 34
- JDK 8+

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

1. פתח את **Yes Plus Keep Alive** מתפריט האפליקציות ב-TV
2. לחץ על **"הפעל שירות נגישות"**
3. מצא את **"Yes Plus Keep Alive"** ברשימה
4. הפעל את השירות ואשר

> ⚠️ **חשוב:** זהו שלב חד-פעמי. השירות ימשיך לרוץ גם אחרי הפעלה מחדש של ה-TV.

## בדיקת פעולה

- פתח את האפליקציה — אינדיקטור ירוק = השירות פעיל
- צפה בלוגים: `adb logcat -s YesPlusKeepAlive`

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
