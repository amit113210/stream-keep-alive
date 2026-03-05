@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

:: ╔══════════════════════════════════════════════════════════╗
:: ║       Stream Keep Alive — Automatic Installer (Win)     ║
:: ╚══════════════════════════════════════════════════════════╝

title Stream Keep Alive - Installer

set "SCRIPT_DIR=%~dp0"
set "APK_PATH=%SCRIPT_DIR%apk\YesPlusKeepAlive.apk"
set "ADB_DIR=%SCRIPT_DIR%tools\platform-tools"
set "PACKAGE_NAME=com.keepalive.yesplus"
set "SERVICE_NAME=%PACKAGE_NAME%/%PACKAGE_NAME%.KeepAliveAccessibilityService"
set "PLATFORM_TOOLS_URL=https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
set "ADB="

cls
echo.
echo  ╔══════════════════════════════════════════════════════════╗
echo  ║       Yes Plus Keep Alive — Automatic Installer          ║
echo  ║                                                          ║
echo  ║  Prevents "Are you still watching?" on Yes Plus          ║
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

if exist "%APK_PATH%" (
    echo    ✅ APK found
) else (
    echo    ❌ APK file not found!
    echo    Make sure YesPlusKeepAlive.apk is in the apk folder
    pause
    exit /b 1
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

:: Scan network for Android TV devices
echo    🔍 Scanning network for Android TV devices...
echo.

set DEVICE_COUNT=0

:: Get IPs from ARP table via PowerShell
for /f "tokens=*" %%i in ('powershell -Command "Get-NetNeighbor -State Reachable,Stale 2>$null | Where-Object { $_.IPAddress -match '^\d+\.\d+\.\d+\.\d+$' } | Select-Object -ExpandProperty IPAddress" 2^>nul') do (
    :: Try ADB connect on each IP
    "%ADB%" connect %%i:5555 2>nul | findstr /C:"connected" >nul 2>&1
    if !errorlevel!==0 (
        set /a DEVICE_COUNT+=1
        :: Get device model
        for /f "tokens=*" %%m in ('"%ADB%" -s %%i:5555 shell getprop ro.product.model 2^>nul') do (
            set "DEVICE_!DEVICE_COUNT!_IP=%%i:5555"
            set "DEVICE_!DEVICE_COUNT!_NAME=%%m"
            echo    [!DEVICE_COUNT!] %%m — %%i
        )
        "%ADB%" disconnect %%i:5555 >nul 2>&1
    )
)

echo.

if !DEVICE_COUNT! gtr 0 (
    set /a MANUAL_OPT=DEVICE_COUNT+1
    echo    [!MANUAL_OPT!] ✏️  Enter IP manually
    echo.
    set /p "DEVICE_CHOICE=   Choose device [1-!MANUAL_OPT!]: "

    if !DEVICE_CHOICE! leq !DEVICE_COUNT! (
        set "CHOSEN_IP=!DEVICE_!DEVICE_CHOICE!_IP!"
        echo    Connecting to !CHOSEN_IP!...
        "%ADB%" connect !CHOSEN_IP! >nul 2>&1
        timeout /t 1 /nobreak >nul
        goto :install
    )
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
    echo.
    goto :ask_ip
)

:: =====================
:: Step 4: Install APK
:: =====================
:install
echo.
echo  [Step 4] Installing app...

"%ADB%" install -r "%APK_PATH%" 2>&1 | findstr /C:"Success" >nul 2>&1
if %errorlevel%==0 (
    echo    ✅ App installed successfully!
) else (
    echo    ❌ Installation failed!
    echo.
    echo    If Google Play Protect blocked it:
    echo    1. Open Google Play Store on TV
    echo    2. Settings → Play Protect → Disable scanning
    echo    3. Run this installer again
    pause
    exit /b 1
)

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
echo  ║  Open Yes Plus and enjoy uninterrupted viewing! 🎬        ║
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

