#!/bin/bash
# Installs the Android SDK Kosh builds against (compileSdk 35) in Claude Code cloud sessions and points
# Gradle at it through local.properties. Local machines use their own SDK, so this does nothing there.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

SDK="${ANDROID_HOME:-/opt/android-sdk}"
PROJECT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
TOOLS_ZIP="commandlinetools-linux-16111833_latest.zip"
TOOLS_SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$TOOLS_ZIP"
  echo "$TOOLS_SHA1  $tmp/tools.zip" | sha1sum -c - > /dev/null
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

if [ ! -d "$SDK/platforms/android-35" ] || [ ! -d "$SDK/build-tools/34.0.0" ]; then
  (yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses > /dev/null 2>&1) || true
  "$SDKMANAGER" --sdk_root="$SDK" "platforms;android-35" "build-tools;34.0.0" > /dev/null 2>&1
fi

echo "sdk.dir=$SDK" > "$PROJECT/local.properties"
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=$SDK" >> "$CLAUDE_ENV_FILE"
fi
