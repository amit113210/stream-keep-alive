#!/bin/bash
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
ADB_DIR="$SCRIPT_DIR/tools/platform-tools"
PACKAGE_NAME="com.keepalive.yesplus"
SERVICE_NAME="$PACKAGE_NAME/$PACKAGE_NAME.KeepAliveAccessibilityService"

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

    if [ -f "$APK_PATH" ]; then
        print_success "APK נמצא: $APK_PATH"
    else
        print_error "קובץ APK לא נמצא!"
        print_info "ודא שהקובץ נמצא ב: $APK_PATH"
        exit 1
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
        print_success "מכשיר מחובר!"
        echo -e "  ${GREEN}$devices${NC}"
        return 0
    fi

    # Ask for IP address
    echo -e "  ${BOLD}לא נמצא מכשיר מחובר.${NC}"
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
        if "$ADB" connect "$tv_ip:5555" 2>/dev/null | grep -q "connected"; then
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
            echo ""
        fi
    done
}

# =====================
# Install APK
# =====================
install_apk() {
    print_step "4" "מתקין את האפליקציה..."

    # Check if already installed
    if "$ADB" shell pm list packages 2>/dev/null | grep -q "$PACKAGE_NAME"; then
        print_info "האפליקציה כבר מותקנת — מעדכן..."
        if "$ADB" install -r "$APK_PATH" 2>&1 | grep -q "Success"; then
            print_success "האפליקציה עודכנה בהצלחה!"
        else
            print_warning "עדכון נכשל, מנסה התקנה מחדש..."
            "$ADB" uninstall "$PACKAGE_NAME" 2>/dev/null || true
            if "$ADB" install "$APK_PATH" 2>&1 | grep -q "Success"; then
                print_success "האפליקציה הותקנה בהצלחה!"
            else
                print_error "ההתקנה נכשלה!"
                print_info "נסה להתקין ידנית או כבה את Google Play Protect"
                exit 1
            fi
        fi
    else
        if "$ADB" install "$APK_PATH" 2>&1 | grep -q "Success"; then
            print_success "האפליקציה הותקנה בהצלחה!"
        else
            print_error "ההתקנה נכשלה!"
            echo ""
            print_info "אם Google Play Protect חוסם את ההתקנה:"
            echo -e "  1. פתח את ${BOLD}Google Play Store${NC} ב-TV"
            echo -e "  2. ${BOLD}Settings → Play Protect → כבה סריקה${NC}"
            echo -e "  3. הרץ את המתקין שוב"
            exit 1
        fi
    fi
}

# =====================
# Enable Accessibility
# =====================
enable_accessibility() {
    print_step "5" "מפעיל שירות נגישות..."

    # Step 1: Allow restricted settings (Android 13+)
    print_info "מאפשר הגדרות מוגבלות..."
    "$ADB" shell appops set "$PACKAGE_NAME" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true
    print_success "הגדרות מוגבלות אושרו"

    # Step 2: Get current accessibility services
    local current_services
    current_services=$("$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null || echo "null")

    # Step 3: Enable our service (append to existing if needed)
    if [ "$current_services" = "null" ] || [ -z "$current_services" ]; then
        "$ADB" shell settings put secure enabled_accessibility_services "$SERVICE_NAME"
    else
        if echo "$current_services" | grep -q "$PACKAGE_NAME"; then
            print_info "שירות הנגישות כבר מוגדר"
        else
            "$ADB" shell settings put secure enabled_accessibility_services "$current_services:$SERVICE_NAME"
        fi
    fi
    print_success "שירות הנגישות הוגדר"

    # Step 4: Enable accessibility
    "$ADB" shell settings put secure accessibility_enabled 1
    print_success "נגישות הופעלה"

    # Step 5: Verify
    sleep 1
    local verify
    verify=$("$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null || echo "")

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

    "$ADB" shell pm grant "$PACKAGE_NAME" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    "$ADB" shell pm grant "$PACKAGE_NAME" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
    "$ADB" shell pm grant "$PACKAGE_NAME" android.permission.NEARBY_WIFI_DEVICES 2>/dev/null || true

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
    echo -e "${GREEN}║${NC}  ${CYAN}פתח את האפליקציה ותיהנה מצפייה ללא הפרעות 🎬${NC}         ${GREEN}║${NC}"
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
    "$ADB" disconnect 2>/dev/null || true
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
