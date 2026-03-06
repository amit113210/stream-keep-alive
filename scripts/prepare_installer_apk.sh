#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

ALLOW_DEBUG=0
if [[ "${1:-}" == "--allow-debug" ]]; then
  ALLOW_DEBUG=1
fi

SIGNING_READY=1
for v in ANDROID_SIGNING_STORE_FILE ANDROID_SIGNING_STORE_PASSWORD ANDROID_SIGNING_KEY_ALIAS ANDROID_SIGNING_KEY_PASSWORD; do
  if [[ -z "${!v:-}" ]]; then
    SIGNING_READY=0
  fi
done

BUILD_TYPE="release"
APK_SRC=""
META_SRC=""

if [[ "$SIGNING_READY" -eq 1 ]]; then
  echo "[ship] Building signed release APK..."
  ./gradlew assembleRelease --no-daemon
  APK_SRC="app/build/outputs/apk/release/app-release.apk"
  META_SRC="app/build/outputs/apk/release/output-metadata.json"
else
  if [[ "$ALLOW_DEBUG" -ne 1 ]]; then
    echo "[ship] Signing env vars missing. Refusing to ship debug build by default."
    echo "[ship] Re-run with --allow-debug if you explicitly want a debug distributable."
    exit 1
  fi
  echo "[ship] Building debug APK fallback (explicitly allowed)..."
  ./gradlew assembleDebug --no-daemon
  BUILD_TYPE="debug"
  APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
  META_SRC="app/build/outputs/apk/debug/output-metadata.json"
fi

if [[ ! -f "$APK_SRC" ]]; then
  echo "[ship] APK not found: $APK_SRC"
  exit 1
fi

VERSION_CODE=""
VERSION_NAME=""
if [[ -f "$META_SRC" ]]; then
  VERSION_CODE="$(sed -n 's/.*"versionCode"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$META_SRC" | head -1)"
  VERSION_NAME="$(sed -n 's/.*"versionName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$META_SRC" | head -1)"
fi

if [[ -z "$VERSION_CODE" || -z "$VERSION_NAME" ]]; then
  VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
  VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
fi

if [[ -z "$VERSION_CODE" || -z "$VERSION_NAME" ]]; then
  echo "[ship] Failed to resolve versionCode/versionName"
  exit 1
fi

INSTALLER_DIR="installer/apk"
mkdir -p "$INSTALLER_DIR"

STABLE_APK="$INSTALLER_DIR/StreamKeepAlive.apk"
VERSIONED_APK="$INSTALLER_DIR/StreamKeepAlive-v${VERSION_NAME}.apk"

cp "$APK_SRC" "$STABLE_APK"
cp "$APK_SRC" "$VERSIONED_APK"

SHA_STABLE="$(shasum -a 256 "$STABLE_APK" | awk '{print $1}')"
SHA_VERSIONED="$(shasum -a 256 "$VERSIONED_APK" | awk '{print $1}')"

echo "[ship] Prepared distributables:"
echo "       - $STABLE_APK"
echo "       - $VERSIONED_APK"
echo "[ship] Build type: $BUILD_TYPE"
echo "[ship] versionName=$VERSION_NAME"
echo "[ship] versionCode=$VERSION_CODE"
echo "[ship] SHA256 stable:   $SHA_STABLE"
echo "[ship] SHA256 versioned:$SHA_VERSIONED"

echo
echo "[ship] Next steps:"
echo "  1) git add app/build.gradle.kts installer/apk/StreamKeepAlive.apk installer/apk/StreamKeepAlive-v${VERSION_NAME}.apk"
echo "  2) git commit -m 'Release v${VERSION_NAME} (${VERSION_CODE}) distributable APK'"
echo "  3) git push origin main"
echo "  4) git tag v${VERSION_NAME}"
echo "  5) git push origin v${VERSION_NAME}"
echo "  6) Publish GitHub Release for v${VERSION_NAME} if not auto-published"
