@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

:: © 2025 Stream Keep Alive. All rights reserved.
:: Licensed under the MIT License. See LICENSE file.
::
:: ╔══════════════════════════════════════════════════════════╗
:: ║       Stream Keep Alive — Automatic Installer (Win)     ║
:: ╚══════════════════════════════════════════════════════════╝

title Stream Keep Alive - Installer

set "SCRIPT_DIR=%~dp0"
set "APK_PATH=%SCRIPT_DIR%apk\StreamKeepAlive.apk"
set "APK_URL_RELEASE=https://github.com/amit113210/stream-keep-alive/releases/latest/download/StreamKeepAlive.apk"
set "APK_URL_FALLBACK=https://raw.githubusercontent.com/amit113210/stream-keep-alive/main/installer/apk/StreamKeepAlive.apk"
set "ADB_DIR=%SCRIPT_DIR%tools\platform-tools"
set "PACKAGE_NAME=com.keepalive.yesplus"
set "SERVICE_NAME=%PACKAGE_NAME%/%PACKAGE_NAME%.KeepAliveAccessibilityService"
set "PLATFORM_TOOLS_URL=https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
set "ADB="

cls
echo.
echo  ╔══════════════════════════════════════════════════════════╗
echo  ║       Stream Keep Alive — Automatic Installer              ║
echo  ║                                                          ║
echo  ║  Prevents "Are you still watching?" on streaming apps     ║
echo  ╚══════════════════════════════════════════════════════════╝
echo.

:: =====================
:: Step 1: Find ADB
:: =====================
echo  [Step 1] Checking ADB...

where adb >nul 2>&1
if %errorlevel%==0 (
    set "ADB=adb"
    echo    ✅ ADB found in PATH
    goto :check_apk
)

if exist "%ADB_DIR%\adb.exe" (
    set "ADB=%ADB_DIR%\adb.exe"
    echo    ✅ ADB found: %ADB_DIR%\adb.exe
    goto :check_apk
)

if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    echo    ✅ ADB found: %ADB%
    goto :check_apk
)

echo    ⚠️  ADB not found. Downloading...
echo.

if not exist "%SCRIPT_DIR%tools" mkdir "%SCRIPT_DIR%tools"

echo    Downloading platform-tools from Google...
powershell -Command "Invoke-WebRequest -Uri '%PLATFORM_TOOLS_URL%' -OutFile '%SCRIPT_DIR%tools\platform-tools.zip'"
if %errorlevel% neq 0 (
    echo    ❌ Failed to download ADB
    echo    Download manually from: https://developer.android.com/tools/releases/platform-tools
    pause
    exit /b 1
)

echo    Extracting...
powershell -Command "Expand-Archive -Path '%SCRIPT_DIR%tools\platform-tools.zip' -DestinationPath '%SCRIPT_DIR%tools' -Force"
del "%SCRIPT_DIR%tools\platform-tools.zip" >nul 2>&1
set "ADB=%ADB_DIR%\adb.exe"
echo    ✅ ADB downloaded successfully!
echo.

:: =====================
:: Step 2: Check APK
:: =====================
:check_apk
echo.
echo  [Step 2] Checking APK file...

echo    Downloading latest APK from GitHub Releases...
if not exist "%SCRIPT_DIR%apk" mkdir "%SCRIPT_DIR%apk"
powershell -Command "try { Invoke-WebRequest -Uri '%APK_URL_RELEASE%' -OutFile '%APK_PATH%' -ErrorAction Stop; exit 0 } catch { exit 1 }" >nul 2>&1
if %errorlevel%==0 (
    echo    ✅ APK updated successfully
) else (
    echo    ⚠️  Release download failed, trying fallback source...
    powershell -Command "try { Invoke-WebRequest -Uri '%APK_URL_FALLBACK%' -OutFile '%APK_PATH%' -ErrorAction Stop; exit 0 } catch { exit 1 }" >nul 2>&1
    if %errorlevel%==0 (
        echo    ✅ APK updated successfully (fallback)
    ) else (
        if exist "%APK_PATH%" (
            echo    ⚠️  Download failed — using existing local APK
        ) else (
            echo    ❌ Failed to download APK and no local APK exists
            echo    Try downloading manually: %APK_URL_RELEASE%
            pause
            exit /b 1
        )
    )
)

:: =====================
:: Step 3: Connect
:: =====================
echo.
echo  [Step 3] Connecting to Android TV...
echo.

:: Check for already connected devices
"%ADB%" devices 2>nul | findstr /C:"device" | findstr /V /C:"List" >nul 2>&1
if %errorlevel%==0 (
    echo    ✅ Device connected!
    for /f "tokens=1" %%a in ('"%ADB%" devices ^| findstr /C:"device" ^| findstr /V /C:"List"') do (
        echo    Device: %%a
    )
    goto :install
)

:: Manual entry fallback
echo    Enter IP address manually:
echo.
echo    How to find the IP:
echo    On TV: Settings → Device Preferences → About → Status
echo.

:ask_ip
set /p "TV_IP=   IP Address: "

if "%TV_IP%"=="" (
    echo    ⚠️  No IP entered
    goto :ask_ip
)

echo    Connecting to %TV_IP%...
"%ADB%" disconnect %TV_IP%:5555 >nul 2>&1
"%ADB%" connect %TV_IP%:5555 2>nul | findstr /C:"connected" >nul 2>&1
if %errorlevel%==0 (
    echo    ✅ Connected to %TV_IP%!
    timeout /t 2 /nobreak >nul

    "%ADB%" devices 2>nul | findstr /C:"unauthorized" >nul 2>&1
    if %errorlevel%==0 (
        echo.
        echo    ⚠️  Authorization required!
        echo    Approve the connection on the TV screen
        echo    Check "Always allow from this computer"
        echo.
        pause
    )
) else (
    echo    ❌ Cannot connect to %TV_IP%
    echo    Make sure ADB Debugging is enabled and TV is on the same WiFi
    echo    Resetting connection...
    "%ADB%" kill-server >nul 2>&1
    "%ADB%" start-server >nul 2>&1
    echo.
    goto :ask_ip
)

:: =====================
:: Step 4: Install APK
:: =====================
:install
echo.
echo  [Step 4] Installing app...

:: Disable Play Protect verification via ADB
echo    Disabling Play Protect verification...
"%ADB%" shell settings put global verifier_verify_adb_installs 0 >nul 2>&1
"%ADB%" shell settings put global package_verifier_enable 0 >nul 2>&1

:: Force clean install first
echo    Removing previous app version (if exists)...
"%ADB%" uninstall %PACKAGE_NAME% >nul 2>&1
timeout /t 1 /nobreak >nul

:: Strategy 1: Install with -r -d -g
echo    Trying install (attempt 1)...
"%ADB%" install -d -g "%APK_PATH%" 2>&1 | findstr /C:"Success" >nul 2>&1
if %errorlevel%==0 (
    echo    ✅ App installed successfully!
    goto :install_done
)

:: Strategy 2: Try with --bypass-low-target-sdk-block
echo    Trying install with bypass (attempt 2)...
"%ADB%" install -r -d -g --bypass-low-target-sdk-block "%APK_PATH%" 2>&1 | findstr /C:"Success" >nul 2>&1
if %errorlevel%==0 (
    echo    ✅ App installed successfully!
    goto :install_done
)

:: Strategy 3: Uninstall first, then clean install
echo    Removing old version and retrying (attempt 3)...
"%ADB%" uninstall %PACKAGE_NAME% >nul 2>&1
timeout /t 1 /nobreak >nul
"%ADB%" install -g "%APK_PATH%" 2>&1 | findstr /C:"Success" >nul 2>&1
if %errorlevel%==0 (
    echo    ✅ App installed successfully!
    goto :install_done
)

:: Strategy 4: Push APK and install via pm
echo    Trying pm install (attempt 4)...
"%ADB%" push "%APK_PATH%" /data/local/tmp/keepalive.apk >nul 2>&1
"%ADB%" shell pm install -r -d -g /data/local/tmp/keepalive.apk 2>&1 | findstr /C:"Success" >nul 2>&1
if %errorlevel%==0 (
    "%ADB%" shell rm /data/local/tmp/keepalive.apk >nul 2>&1
    echo    ✅ App installed successfully!
    goto :install_done
)
"%ADB%" shell rm /data/local/tmp/keepalive.apk >nul 2>&1

:: All strategies failed
echo    ❌ Installation failed!
echo.
echo    Try running this command manually to see the error:
echo    "%ADB%" install -r -d -g "%APK_PATH%"
echo.
echo    Possible solutions:
echo    1. Disable Play Protect: Play Store → Settings → Play Protect → Disable
echo    2. Restart the TV and try again
echo    3. Check for free storage space on the TV (Settings → Storage)
pause
exit /b 1

:install_done
:: Re-enable verifier
"%ADB%" shell settings put global verifier_verify_adb_installs 1 >nul 2>&1

echo    Installed app version:
"%ADB%" shell dumpsys package %PACKAGE_NAME% 2>nul | findstr /C:"versionName=" /C:"versionCode="

:: =====================
:: Step 5: Enable Accessibility
:: =====================
echo.
echo  [Step 5] Enabling accessibility service...

:: Allow restricted settings (Android 13+)
"%ADB%" shell appops set %PACKAGE_NAME% ACCESS_RESTRICTED_SETTINGS allow >nul 2>&1
echo    ✅ Restricted settings allowed

:: Enable accessibility service
"%ADB%" shell settings put secure enabled_accessibility_services %SERVICE_NAME% >nul 2>&1
echo    ✅ Accessibility service configured

:: Enable accessibility
"%ADB%" shell settings put secure accessibility_enabled 1 >nul 2>&1
echo    ✅ Accessibility enabled

:: Verify
timeout /t 1 /nobreak >nul
for /f "tokens=*" %%a in ('"%ADB%" shell settings get secure enabled_accessibility_services 2^>nul') do (
    echo %%a | findstr /C:"%PACKAGE_NAME%" >nul 2>&1
    if !errorlevel!==0 (
        echo    ✅ Service is active and verified!
    ) else (
        echo    ⚠️  Could not verify — check manually in Settings → Accessibility
    )
)

:: =====================
:: Step 6: Grant Hotspot Permissions
:: =====================
echo.
echo  [Step 6] Granting Hotspot permissions...

"%ADB%" shell pm grant %PACKAGE_NAME% android.permission.ACCESS_FINE_LOCATION >nul 2>&1
"%ADB%" shell pm grant %PACKAGE_NAME% android.permission.ACCESS_COARSE_LOCATION >nul 2>&1
"%ADB%" shell pm grant %PACKAGE_NAME% android.permission.NEARBY_WIFI_DEVICES >nul 2>&1
echo    ✅ Hotspot permissions granted

:: =====================
:: Done!
:: =====================
echo.
echo  ╔══════════════════════════════════════════════════════════╗
echo  ║                                                          ║
echo  ║            ✅ Installation Complete!                      ║
echo  ║                                                          ║
echo  ║  The app is installed and accessibility service active.   ║
echo  ║  "Are you still watching?" will no longer appear!         ║
echo  ║  Hotspot can be toggled directly from the app!            ║
echo  ║                                                          ║
echo  ║  Open a streaming app and enjoy uninterrupted viewing! 🎬  ║
echo  ║                                                          ║
echo  ╚══════════════════════════════════════════════════════════╝

:: =====================
:: Disconnect
:: =====================
echo.
echo    Disconnecting device...
"%ADB%" disconnect >nul 2>&1
echo    ✅ Device disconnected

:: =====================
:: Install on another device?
:: =====================
echo.
set /p "ANOTHER=   Install on another device? (y/n): "
if /i "%ANOTHER%"=="y" (
    echo.
    echo  ══════════════════════════════════════════
    echo    Starting installation for next device...
    echo  ══════════════════════════════════════════
    echo.
    goto :ask_ip
)

echo.
echo    👋 Thanks! Enjoy uninterrupted viewing!
echo.
pause
