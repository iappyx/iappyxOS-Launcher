#!/bin/bash
# iappyxOS Launcher — release build wrapper.
# Mirrors the build.sh at the root of the sibling iappyxOS repo: cd into
# the project's actual Gradle location, run the build, the assembleRelease
# task itself copies the resulting APK to /bin/iappyxOS-Launcher.apk.
# Optionally installs to a connected device.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LAUNCHER_DIR="$SCRIPT_DIR/src/launcher"
BIN_DIR="$SCRIPT_DIR/bin"

echo "▶ Building iappyxOS Launcher..."
cd "$LAUNCHER_DIR"
./gradlew :app:assembleRelease -q
echo "✅ Build complete: bin/iappyxOS-Launcher.apk"
echo ""

# Install if exactly one device is connected, otherwise leave it to the
# user — silently picking among multiple devices is a footgun.
DEVICE_COUNT=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" = "1" ]; then
    echo "▶ Installing on connected device..."
    adb install -r "$BIN_DIR/iappyxOS-Launcher.apk"
    echo "✅ Installed"
elif [ "$DEVICE_COUNT" = "0" ]; then
    echo "ℹ No device connected. APK ready at bin/iappyxOS-Launcher.apk"
else
    echo "ℹ $DEVICE_COUNT devices connected — install manually:"
    echo "    adb -s <serial> install -r bin/iappyxOS-Launcher.apk"
fi
