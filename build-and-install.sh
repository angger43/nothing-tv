#!/usr/bin/env bash
# Build, install, and select Nothing TV without disabling or uninstalling any package.
set -euo pipefail
cd "$(dirname "$0")"

: "${TV_HOST:?Set TV_HOST to the TV IP address, for example: TV_HOST=192.168.1.50 $0}"
ADB_BIN="${ADB:-adb}"
command -v "$ADB_BIN" >/dev/null 2>&1 || { echo "adb was not found. Set ADB=/path/to/adb." >&2; exit 1; }

case "$TV_HOST" in
  *:*) ADB_TARGET="$TV_HOST" ;;
  *) ADB_TARGET="$TV_HOST:5555" ;;
esac

"$ADB_BIN" connect "$TV_HOST" >/dev/null
TV_SDK=$("$ADB_BIN" -s "$ADB_TARGET" shell getprop ro.build.version.sdk | tr -dc '0-9')
if [ -z "$TV_SDK" ] || [ "$TV_SDK" -lt 26 ]; then
  echo "Android API 26 or newer is required; the TV reported '${TV_SDK:-nothing}'." >&2
  exit 1
fi

./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
"$ADB_BIN" -s "$ADB_TARGET" install -r "$APK"
"$ADB_BIN" -s "$ADB_TARGET" shell am start -W -n com.gathin.tvhome/.MainActivity
"$ADB_BIN" -s "$ADB_TARGET" shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity

HOME_NOW=$("$ADB_BIN" -s "$ADB_TARGET" shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | tr -d '\r')
case "$HOME_NOW" in
  com.gathin.tvhome/*) echo "Installed. Home resolves to $HOME_NOW" ;;
  *)
    echo "Installed, but the firmware reports Home as '${HOME_NOW:-unknown}'." >&2
    echo "Choose Nothing TV manually in Android TV's default Home-app settings." >&2
    exit 1 ;;
esac
