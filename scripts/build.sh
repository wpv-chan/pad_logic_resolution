#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -x '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java' ]; then
    JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
    export JAVA_HOME
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    ANDROID_HOME="$HOME/Library/Android/sdk"
    export ANDROID_HOME
fi
./gradlew :adb-lib:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain "$@"
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/PadDisplay-debug.apk
if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 dist/PadDisplay-debug.apk > dist/SHA256SUMS
fi
printf '\nAPK: %s/dist/PadDisplay-debug.apk\n' "$(pwd)"
