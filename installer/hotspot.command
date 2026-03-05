#!/bin/bash
#
# Stream Keep Alive — Hotspot ON/OFF Toggle
#

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# =====================
# Configuration
# =====================
HOTSPOT_SSID="StreamKeepAlive_Hotspot"
HOTSPOT_PASSWORD="streamkeepalive"
HOTSPOT_SECURITY="wpa2"
TV_IP="192.168.7.19"

# Find ADB
find_adb() {
    if command -v adb &> /dev/null; then
        ADB="adb"
    elif [ -f "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
        ADB="$HOME/Library/Android/sdk/platform-tools/adb"
    elif [ -f "$(dirname "$0")/tools/platform-tools/adb" ]; then
        ADB="$(dirname "$0")/tools/platform-tools/adb"
    else
        echo -e "${RED}❌ ADB not found!${NC}"
        echo "Install Android platform-tools or run the installer first."
        exit 1
    fi
}

# Connect to TV
connect_tv() {
    local connected=$("$ADB" devices 2>/dev/null | grep -v "List" | grep "device$" | head -1)
    if [ -z "$connected" ]; then
        echo -e "${CYAN}Connecting to $TV_IP...${NC}"
        "$ADB" connect "$TV_IP:5555" 2>/dev/null || true
        sleep 1
        # Try common ports
        "$ADB" connect "$TV_IP:41635" 2>/dev/null || true
        sleep 1
    fi

    local device=$("$ADB" devices 2>/dev/null | grep -v "List" | grep "device$" | head -1 | awk '{print $1}')
    if [ -z "$device" ]; then
        echo -e "${RED}❌ No device connected!${NC}"
        echo -ne "${YELLOW}Enter TV IP address: ${NC}"
        read -r tv_ip
        "$ADB" connect "$tv_ip:5555" 2>/dev/null
        sleep 1
        device=$("$ADB" devices 2>/dev/null | grep -v "List" | grep "device$" | head -1 | awk '{print $1}')
        if [ -z "$device" ]; then
            echo -e "${RED}❌ Cannot connect${NC}"
            exit 1
        fi
    fi
    DEVICE="$device"
    echo -e "${GREEN}✅ Connected: $DEVICE${NC}"
}

# Start Hotspot
start_hotspot() {
    echo ""
    echo -e "${CYAN}🔥 Starting Hotspot...${NC}"
    echo -e "  SSID: ${BOLD}$HOTSPOT_SSID${NC}"
    echo -e "  Password: ${BOLD}$HOTSPOT_PASSWORD${NC}"
    echo -e "  Security: ${BOLD}$HOTSPOT_SECURITY${NC}"
    echo ""

    local result=$("$ADB" -s "$DEVICE" shell cmd wifi start-softap "$HOTSPOT_SSID" "$HOTSPOT_SECURITY" "$HOTSPOT_PASSWORD" 2>&1)
    if echo "$result" | grep -q "enabled successfully"; then
        echo -e "${GREEN}╔══════════════════════════════════════════╗${NC}"
        echo -e "${GREEN}║  ✅ Hotspot Enabled!                     ║${NC}"
        echo -e "${GREEN}║                                          ║${NC}"
        echo -e "${GREEN}║${NC}  📶 SSID: ${BOLD}$HOTSPOT_SSID${NC}"
        echo -e "${GREEN}║${NC}  🔑 Password: ${BOLD}$HOTSPOT_PASSWORD${NC}"
        echo -e "${GREEN}║                                          ║${NC}"
        echo -e "${GREEN}╚══════════════════════════════════════════╝${NC}"
    else
        echo -e "${YELLOW}⚠️  Result:${NC}"
        echo "$result"
    fi
}

# Stop Hotspot
stop_hotspot() {
    echo ""
    echo -e "${CYAN}📴 Stopping Hotspot...${NC}"
    "$ADB" -s "$DEVICE" shell cmd wifi stop-softap 2>&1
    echo -e "${GREEN}✅ Hotspot stopped${NC}"
}

# =====================
# Main Menu
# =====================
clear
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║${BOLD}   Stream Keep Alive — Hotspot Control     ${NC}${CYAN}║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════╝${NC}"
echo ""

find_adb
connect_tv

echo ""
echo -e "${BOLD}  בחר פעולה:${NC}"
echo ""
echo -e "  ${GREEN}1${NC}) 🔥 הפעל Hotspot"
echo -e "  ${RED}2${NC}) 📴 כבה Hotspot"
echo -e "  ${CYAN}3${NC}) ⚙️  שנה שם/סיסמה"
echo -e "  ${YELLOW}4${NC}) יציאה"
echo ""
echo -ne "  ${BOLD}בחירה [1-4]: ${NC}"
read -r choice

case $choice in
    1)
        start_hotspot
        ;;
    2)
        stop_hotspot
        ;;
    3)
        echo -ne "  ${CYAN}שם רשת (SSID) [${HOTSPOT_SSID}]: ${NC}"
        read -r new_ssid
        [ -n "$new_ssid" ] && HOTSPOT_SSID="$new_ssid"

        echo -ne "  ${CYAN}סיסמה [${HOTSPOT_PASSWORD}]: ${NC}"
        read -r new_pass
        [ -n "$new_pass" ] && HOTSPOT_PASSWORD="$new_pass"

        start_hotspot
        ;;
    *)
        echo "  Bye! 👋"
        ;;
esac

echo ""
echo -e "  ${BLUE}Press Enter to close...${NC}"
read -r
