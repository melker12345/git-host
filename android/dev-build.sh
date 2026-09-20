#!/bin/sh
# Build the debug APK ("git-host2", package com.localgithub.githost.dev) and,
# if a phone is attached, install it without touching the release app.
#
# Toolchain lives in the home directory (no root needed); installed by
# ~/.local/opt/setup-android-toolchain.sh.
set -eu
export JAVA_HOME="$HOME/.local/opt/jdk-17"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
cd "$(dirname "$0")"
"$HOME/.local/opt/gradle-8.6/bin/gradle" assembleDebug --console=plain -q "$@"
APK=app/build/outputs/apk/debug/app-debug.apk
echo "built $APK"
if adb get-state >/dev/null 2>&1; then
    adb install -r "$APK"
    adb shell am start -n com.localgithub.githost.dev/com.localgithub.githost.MainActivity >/dev/null
    echo "installed and launched git-host2"
fi
