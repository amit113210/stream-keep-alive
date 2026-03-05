#!/bin/bash
# © 2025 Stream Keep Alive. All rights reserved.
# Licensed under the MIT License. See LICENSE file.
#
# ╔══════════════════════════════════════════════════════════╗
# ║       Stream Keep Alive — מתקין אוטומטי (Mac)           ║
# ║                                                          ║
# ║  מתקין את האפליקציה על Android TV ומפעיל הכל אוטומטית  ║
# ╚══════════════════════════════════════════════════════════╝
#

set -e

# =====================
# Colors & Formatting
# =====================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# =====================
# Configuration
# =====================
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$SCRIPT_DIR/apk/StreamKeepAlive.apk"
APK_URL="https://raw.githubusercontent.com/amit113210/stream-keep-alive/main/installer/apk/StreamKeepAlive.apk"
ADB_DIR="$SCRIPT_DIR/tools/platform-tools"
PACKAGE_NAME="com.keepalive.yesplus"
SERVICE_NAME="$PACKAGE_NAME/$PACKAGE_NAME.KeepAliveAccessibilityService"
TARGET_DEVICE=""

# Detect architecture
ARCH=$(uname -m)
if [ "$ARCH" = "arm64" ]; then
    PLATFORM_TOOLS_URL="https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"
else
    PLATFORM_TOOLS_URL="https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"
fi

# =====================
# Helper Functions
# =====================
print_header() {
    echo ""
    echo -e "${BLUE}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${BOLD}${CYAN}       Stream Keep Alive — מתקין אוטומטי                ${NC}${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                          ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${GREEN}צפייה ללא הפרעות בכל אפליקציית סטרימינג${NC}              ${BLUE}║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

print_step() {
    echo -e "${CYAN}[שלב $1]${NC} ${BOLD}$2${NC}"
}

print_success() {
    echo -e "  ${GREEN}✅ $1${NC}"
}

print_error() {
    echo -e "  ${RED}❌ $1${NC}"
}

print_warning() {
    echo -e "  ${YELLOW}⚠️  $1${NC}"
}

print_info() {
    echo -e "  ${BLUE}ℹ️  $1${NC}"
}

wait_for_enter() {
    echo ""
    echo -e "  ${YELLOW}לחץ Enter להמשך...${NC}"
    read -r
}

# =====================
# Find or Download ADB
# =====================
setup_adb() {
    print_step "1" "בדיקת ADB..."

    # Check if adb is in PATH
    if command -v adb &> /dev/null; then
        ADB="adb"
        print_success "ADB נמצא: $(which adb)"
        return 0
    fi

    # Check common locations
    local common_paths=(
        "$HOME/Library/Android/sdk/platform-tools/adb"
        "/usr/local/bin/adb"
        "$ADB_DIR/adb"
    )

    for path in "${common_paths[@]}"; do
        if [ -f "$path" ]; then
            ADB="$path"
            print_success "ADB נמצא: $path"
            return 0
        fi
    done

    # Download ADB
    print_warning "ADB לא נמצא. מוריד אוטומטית..."
    echo ""

    mkdir -p "$SCRIPT_DIR/tools"
    local zip_path="$SCRIPT_DIR/tools/platform-tools.zip"

    echo -e "  מוריד platform-tools מגוגל..."
    if curl -L -# -o "$zip_path" "$PLATFORM_TOOLS_URL"; then
        echo -e "  חולץ קבצים..."
        unzip -q -o "$zip_path" -d "$SCRIPT_DIR/tools/"
        rm -f "$zip_path"
        chmod +x "$ADB_DIR/adb"
        ADB="$ADB_DIR/adb"
        print_success "ADB הורד והותקן בהצלחה!"
    else
        print_error "שגיאה בהורדת ADB"
        print_info "הורד ידנית מ: https://developer.android.com/tools/releases/platform-tools"
        exit 1
    fi
}

# =====================
# Check APK
# =====================
check_apk() {
    print_step "2" "בדיקת קובץ APK..."
    mkdir -p "$(dirname "$APK_PATH")"
    print_info "מוריד תמיד את ה-APK העדכני מ-GitHub..."
    if curl -L -f -# -o "$APK_PATH" "$APK_URL"; then
        print_success "APK עודכן בהצלחה: $APK_PATH"
    else
        if [ -f "$APK_PATH" ]; then
            print_warning "הורדה נכשלה — ממשיך עם APK מקומי קיים: $APK_PATH"
        else
            print_error "שגיאה בהורדת APK ואין קובץ מקומי זמין"
            print_info "נסה להוריד ידנית: $APK_URL"
            exit 1
        fi
    fi
}

# =====================
# Connect to Device
# =====================
connect_device() {
    print_step "3" "חיבור ל-Android TV..."
    echo ""

    # Check for already connected devices
    local devices
    devices=$("$ADB" devices 2>/dev/null | grep -v "List" | grep -v "^$" | grep "device$" || true)

    if [ -n "$devices" ]; then
        # Count connected devices
        local device_count
        device_count=$(echo "$devices" | wc -l | tr -d ' ')

        if [ "$device_count" -gt 1 ]; then
            # Multiple devices — let user choose
            print_warning "נמצאו $device_count מכשירים מחוברים!"
            echo ""
            echo -e "  ${BOLD}בחר את המכשיר להתקנה:${NC}"
            echo ""

            local i=1
            local serials=()
            while IFS= read -r line; do
                local serial
                serial=$(echo "$line" | awk '{print $1}')
                serials+=("$serial")

                # Try to get model name
                local model
                model=$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "לא ידוע")
                echo -e "  ${CYAN}[$i]${NC} $serial  ${BOLD}($model)${NC}"
                i=$((i + 1))
            done <<< "$devices"

            echo ""
            while true; do
                echo -ne "  ${YELLOW}${BOLD}בחר מכשיר [1-$device_count]: ${NC}"
                read -r choice
                if [ -n "$choice" ] && [ "$choice" -ge 1 ] 2>/dev/null && [ "$choice" -le "$device_count" ] 2>/dev/null; then
                    TARGET_DEVICE="${serials[$((choice - 1))]}"
                    print_success "נבחר: $TARGET_DEVICE"
                    return 0
                else
                    print_warning "בחירה לא תקינה"
                fi
            done
        else
            # Single device
            TARGET_DEVICE=$(echo "$devices" | head -1 | awk '{print $1}')
            print_success "מכשיר מחובר!"
            echo -e "  ${GREEN}$TARGET_DEVICE${NC}"
            return 0
        fi
    fi

    # Manual IP entry fallback
    echo ""
    echo -e "  ${CYAN}איך למצוא את ה-IP של ה-Android TV:${NC}"
    echo -e "  ב-TV: ${BOLD}Settings → Device Preferences → About → Status${NC}"
    echo ""
    echo -e "  ${CYAN}ודא ש:${NC}"
    echo -e "  • ${BOLD}ADB Debugging${NC} מופעל ב-Developer Options"
    echo -e "  • ה-TV והמחשב על ${BOLD}אותה רשת WiFi${NC}"
    echo ""

    while true; do
        echo -ne "  ${YELLOW}${BOLD}הזן כתובת IP: ${NC}"
        read -r tv_ip

        if [ -z "$tv_ip" ]; then
            print_warning "לא הוזנה כתובת IP"
            continue
        fi

        echo -e "  מתחבר ל-$tv_ip..."
        "$ADB" disconnect "$tv_ip:5555" 2>/dev/null >/dev/null || true
        
        if "$ADB" connect "$tv_ip:5555" 2>/dev/null | grep -q "connected"; then
            TARGET_DEVICE="$tv_ip:5555"
            print_success "מחובר ל-$tv_ip!"

            # Wait a moment and check for authorization prompt
            sleep 2
            local check
            check=$("$ADB" devices 2>/dev/null | grep "$tv_ip" || true)

            if echo "$check" | grep -q "unauthorized"; then
                echo ""
                print_warning "המכשיר דורש אישור!"
                echo -e "  ${BOLD}אשר את החיבור על מסך ה-TV (Allow USB Debugging)${NC}"
                echo -e "  ${BOLD}סמן 'Always allow from this computer'${NC}"
                wait_for_enter

                # Re-check
                check=$("$ADB" devices 2>/dev/null | grep "$tv_ip" || true)
                if echo "$check" | grep -q "device$"; then
                    print_success "חיבור אושר!"
                else
                    print_error "החיבור לא אושר. נסה שוב."
                    continue
                fi
            fi
            return 0
        else
            print_error "לא ניתן להתחבר ל-$tv_ip"
            echo -e "  ודא ש-ADB Debugging מופעל וה-TV באותה רשת WiFi"
            echo -e "  * מאפס את חיבור הרשת מנסה שוב..."
            "$ADB" kill-server 2>/dev/null >/dev/null || true
            "$ADB" start-server 2>/dev/null >/dev/null || true
            echo ""
        fi
    done
}

# =====================
# Install APK
# =====================
install_apk() {
    print_step "4" "מתקין את האפליקציה..."

    # Step 0: Disable Play Protect verification via ADB
    print_info "מכבה Play Protect verification..."
    "$ADB" -s "$TARGET_DEVICE" shell settings put global verifier_verify_adb_installs 0 2>/dev/null || true
    "$ADB" -s "$TARGET_DEVICE" shell settings put global package_verifier_enable 0 2>/dev/null || true

    local install_output
    local install_success=false
    print_info "מבצע התקנה נקייה (מסיר גרסה קודמת אם קיימת)..."
    "$ADB" -s "$TARGET_DEVICE" uninstall "$PACKAGE_NAME" 2>/dev/null || true
    sleep 1

    # Strategy 1: Install with -r (replace) -d (downgrade) -g (grant permissions)
    print_info "מנסה התקנה (ניסיון 1)..."
    install_output=$("$ADB" -s "$TARGET_DEVICE" install -d -g "$APK_PATH" 2>&1) || true

    if echo "$install_output" | grep -q "Success"; then
        install_success=true
        print_success "האפליקציה הותקנה בהצלחה!"
    fi

    # Strategy 2: Try with --bypass-low-target-sdk-block (Android 14+)
    if [ "$install_success" = false ]; then
        print_info "מנסה התקנה עם bypass (ניסיון 2)..."
        install_output=$("$ADB" -s "$TARGET_DEVICE" install -r -d -g --bypass-low-target-sdk-block "$APK_PATH" 2>&1) || true

        if echo "$install_output" | grep -q "Success"; then
            install_success=true
            print_success "האפליקציה הותקנה בהצלחה!"
        fi
    fi

    # Strategy 3: Retry clean install
    if [ "$install_success" = false ]; then
        print_info "מנסה התקנה נקייה מחדש (ניסיון 3)..."
        install_output=$("$ADB" -s "$TARGET_DEVICE" install -g "$APK_PATH" 2>&1) || true

        if echo "$install_output" | grep -q "Success"; then
            install_success=true
            print_success "האפליקציה הותקנה בהצלחה!"
        fi
    fi

    # Strategy 4: Push APK and install via pm
    if [ "$install_success" = false ]; then
        print_info "מנסה התקנה דרך pm install (ניסיון 4)..."
        "$ADB" -s "$TARGET_DEVICE" push "$APK_PATH" /data/local/tmp/keepalive.apk 2>/dev/null || true
        install_output=$("$ADB" -s "$TARGET_DEVICE" shell pm install -r -d -g /data/local/tmp/keepalive.apk 2>&1) || true
        "$ADB" -s "$TARGET_DEVICE" shell rm /data/local/tmp/keepalive.apk 2>/dev/null || true

        if echo "$install_output" | grep -q "Success"; then
            install_success=true
            print_success "האפליקציה הותקנה בהצלחה!"
        fi
    fi

    # Final: show error if all strategies failed
    if [ "$install_success" = false ]; then
        print_error "ההתקנה נכשלה!"
        echo ""
        echo -e "  ${RED}שגיאת ADB:${NC}"
        echo -e "  ${YELLOW}$install_output${NC}"
        echo ""
        print_info "פתרונות אפשריים:"
        echo -e "  1. כבה ${BOLD}Play Protect${NC}: Play Store → Settings → Play Protect → כבה"
        echo -e "  2. ${BOLD}הפעל מחדש${NC} את ה-TV ונסה שוב"
        echo -e "  3. בדוק שיש מספיק ${BOLD}מקום פנוי${NC} ב-TV (Settings → Storage)"
        echo -e "  4. נסה ידנית: ${BOLD}adb install -r -d -g \"$APK_PATH\"${NC}"
        exit 1
    fi

    # Re-enable verifier (good practice)
    "$ADB" -s "$TARGET_DEVICE" shell settings put global verifier_verify_adb_installs 1 2>/dev/null || true

    # Print installed version for verification
    local installed_version
    installed_version=$("$ADB" -s "$TARGET_DEVICE" shell dumpsys package "$PACKAGE_NAME" 2>/dev/null | grep -E "versionName=|versionCode=" | tr -d '\r' | head -2 || true)
    if [ -n "$installed_version" ]; then
        print_info "גרסה מותקנת:"
        echo "$installed_version" | sed 's/^/  /'
    fi
}

# =====================
# Enable Accessibility
# =====================
enable_accessibility() {
    print_step "5" "מפעיל שירות נגישות..."

    # Step 1: Allow restricted settings (Android 13+)
    print_info "מאפשר הגדרות מוגבלות..."
    "$ADB" -s "$TARGET_DEVICE" shell appops set "$PACKAGE_NAME" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true
    print_success "הגדרות מוגבלות אושרו"

    # Step 2: Get current accessibility services
    local current_services
    current_services=$("$ADB" -s "$TARGET_DEVICE" shell settings get secure enabled_accessibility_services 2>/dev/null || echo "null")

    # Step 3: Enable our service (append to existing if needed)
    if [ "$current_services" = "null" ] || [ -z "$current_services" ]; then
        "$ADB" -s "$TARGET_DEVICE" shell settings put secure enabled_accessibility_services "$SERVICE_NAME"
    else
        if echo "$current_services" | grep -q "$PACKAGE_NAME"; then
            print_info "שירות הנגישות כבר מוגדר"
        else
            "$ADB" -s "$TARGET_DEVICE" shell settings put secure enabled_accessibility_services "$current_services:$SERVICE_NAME"
        fi
    fi
    print_success "שירות הנגישות הוגדר"

    # Step 4: Enable accessibility
    "$ADB" -s "$TARGET_DEVICE" shell settings put secure accessibility_enabled 1
    print_success "נגישות הופעלה"

    # Step 5: Verify
    sleep 1
    local verify
    verify=$("$ADB" -s "$TARGET_DEVICE" shell settings get secure enabled_accessibility_services 2>/dev/null || echo "")

    if echo "$verify" | grep -q "$PACKAGE_NAME"; then
        print_success "שירות הנגישות פעיל ומאומת!"
    else
        print_warning "לא ניתן לאמת את השירות — בדוק ידנית ב-Settings → Accessibility"
    fi
}

# =====================
# Grant Hotspot Permissions
# =====================
grant_hotspot_permissions() {
    print_step "6" "מעניק הרשאות Hotspot..."

    "$ADB" -s "$TARGET_DEVICE" shell pm grant "$PACKAGE_NAME" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    "$ADB" -s "$TARGET_DEVICE" shell pm grant "$PACKAGE_NAME" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
    "$ADB" -s "$TARGET_DEVICE" shell pm grant "$PACKAGE_NAME" android.permission.NEARBY_WIFI_DEVICES 2>/dev/null || true

    print_success "הרשאות Hotspot הוענקו"
}

# =====================
# Done!
# =====================
print_done() {
    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║                                                          ║${NC}"
    echo -e "${GREEN}║${BOLD}            ✅ ההתקנה הושלמה בהצלחה!                     ${NC}${GREEN}║${NC}"
    echo -e "${GREEN}║                                                          ║${NC}"
    echo -e "${GREEN}║${NC}  האפליקציה מותקנת ושירות הנגישות פעיל.                  ${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}  הודעת ״האם אתם עדיין צופים?״ לא תופיע יותר!          ${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}  Hotspot ניתן להפעלה ישירות מהאפליקציה!                ${GREEN}║${NC}"
    echo -e "${GREEN}║                                                          ║${NC}"
    echo -e "${GREEN}║${NC}  ${CYAN}פתח אפליקציית סטרימינג ותיהנה מצפייה ללא הפרעות 🎬${NC}  ${GREEN}║${NC}"
    echo -e "${GREEN}║                                                          ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

# =====================
# Disconnect Device
# =====================
disconnect_device() {
    echo ""
    print_info "מנתק את המכשיר..."
    "$ADB" -s "$TARGET_DEVICE" disconnect 2>/dev/null || "$ADB" disconnect 2>/dev/null || true
    print_success "המכשיר נותק"
}

# =====================
# Main Flow
# =====================
main() {
    clear
    print_header

    setup_adb
    echo ""

    check_apk
    echo ""

    # Installation loop — install on multiple devices
    while true; do
        connect_device
        echo ""

        install_apk
        echo ""

        enable_accessibility
        echo ""

        grant_hotspot_permissions
        echo ""

        print_done

        disconnect_device
        echo ""

        echo -ne "  ${YELLOW}${BOLD}האם להתקין על מכשיר נוסף? (y/n): ${NC}"
        read -r answer

        if [ "$answer" != "y" ] && [ "$answer" != "Y" ] && [ "$answer" != "כ" ]; then
            echo ""
            echo -e "  ${GREEN}👋 תודה! תיהנה מצפייה ללא הפרעות!${NC}"
            echo ""
            break
        fi

        echo ""
        echo -e "  ${CYAN}══════════════════════════════════════════${NC}"
        echo -e "  ${BOLD}  מתחיל התקנה למכשיר הבא...${NC}"
        echo -e "  ${CYAN}══════════════════════════════════════════${NC}"
        echo ""
    done

    echo -e "  ${BLUE}לחץ Enter לסגירה...${NC}"
    read -r
}

main "$@"
