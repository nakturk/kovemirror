#!/bin/bash

# ThinkerRide Firmware OTA Fetcher Script
# This script extracts TFT version information from local log files and 
# constructs an API request to the ThinkerRide Global OTA servers.

set -e

# API Endpoints
OTA_API_URL="http://api.global.support.thinkerride.com/v5/ota/device/upgrade"
OTA_ZIP_API_URL="http://api.global.support.thinkerride.com/api/ota/upgrade_zip/task"

echo "=========================================="
echo " ThinkerRide Firmware OTA Fetcher"
echo "=========================================="

# 1. Look for token in OTA log
TOKEN_FILE="/home/nakturk/Desktop/Kove/THINKERRIDE_2.19.0_APKPure/thinkerride_logs/interactive_log_motor/interactive_log/ota_file_07月04日_14:33:36.918.txt"
if [ ! -f "$TOKEN_FILE" ]; then
    echo "[!] OTA log file not found. Ensure ThinkerRide has generated OTA logs."
    exit 1
fi

TOKEN=$(grep -o "eyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]*\.[A-Za-z0-9_-]*" "$TOKEN_FILE" | head -n 1)

if [ -z "$TOKEN" ]; then
    echo "[!] Could not extract JWT token from logs."
    exit 1
fi
echo "[+] Extracted Token: ${TOKEN:0:20}...${TOKEN: -10}"

# 2. Extract Version String from 2026 Kove logs
LOG_FILE="/home/nakturk/Desktop/Kove/KoveMirror/kove_mirror_log_2026.txt"
if [ ! -f "$LOG_FILE" ]; then
    echo "[!] Kove 2026 log file not found."
    exit 1
fi

VERSION_STRING=$(grep "UC=" "$LOG_FILE" | head -n 1 | grep -o "UC=.*")

if [ -z "$VERSION_STRING" ]; then
    echo "[!] Could not extract UC version string from logs."
    exit 1
fi
echo "[+] Extracted Version String: $VERSION_STRING"

# Parse UC String (e.g. UC=202502171551189951_DA=260228_FL=01_SV=2.0.4_TUC=7000071f69c11cc9_CV=1CQKY60FAC920241501_250414_2.0.1CQKYMTC60FAC920201_260228_01_2.0.4CQKYMTC60FAC920201_250918_01_2.0.38762C92160002_240926_2.1.1)
SV=$(echo "$VERSION_STRING" | grep -o 'SV=[^_]*' | cut -d'=' -f2)
TUC=$(echo "$VERSION_STRING" | grep -o 'TUC=[^_]*' | cut -d'=' -f2)
CV=$(echo "$VERSION_STRING" | grep -o 'CV=[^_]*' | cut -d'=' -f2)

# It seems CV contains everything concatenated. ThinkerRide splits this internally or uses device config.
# For API, we'll try to request zip upgrade

echo "=========================================="
echo "    System Version: $SV"
echo "    TUC: $TUC"
echo "=========================================="

echo "[*] Querying ThinkerRide OTA Server..."

curl -s -X POST "$OTA_ZIP_API_URL" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "checkout_token=$TOKEN" \
    -d "version=$TUC" \
    -d "appversion=2.19.0" \
    -d "sysversion=$SV" \
    -d "diskspace=9999999" \
    -d "diskspace_theme=0" > ota_response.json

echo "[+] Server Response:"
cat ota_response.json | jq . || cat ota_response.json
echo ""

# Also try Device Upgrade API
echo "[*] Querying Device Upgrade API..."
curl -s -X POST "$OTA_API_URL" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "checkout_token=$TOKEN" \
    -d "version=$TUC" \
    -d "appversion=2.19.0" \
    -d "sysversion=$SV" \
    -d "fasysversion=" \
    -d "mcuversion=" \
    -d "insidenaviversion=" \
    -d "fainsidenaviversion=" \
    -d "btversion=" \
    -d "diskspace=9999999" \
    -d "is_system_ota=1" \
    -d "is_firmware_ota=1" \
    -d "is_apk_ota=1" \
    -d "is_btooth_ota=1" \
    -d "check_type=0" \
    -d "diskspace_theme=0" > ota_device_response.json

echo "[+] Server Response (Device):"
cat ota_device_response.json | jq . || cat ota_device_response.json
echo ""

echo "[*] Check ota_response.json and ota_device_response.json for download URLs."
