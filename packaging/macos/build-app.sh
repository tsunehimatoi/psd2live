#!/bin/bash
# Builds a Mac app that hosts the current editor and opens it.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew writeRunArgs -Dorg.gradle.java.installations.paths="$JAVA_HOME" --offline

java_bin="$(cat build/run/java)"
java_home="$(cd "$(dirname "$java_bin")/.." && pwd)"
app="build/PSD2Live.app"
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"

clang -c packaging/macos/launch.c \
	-I"$java_home/include" -I"$java_home/include/darwin" \
	-o build/mac-launch.o
swiftc -O -o "$app/Contents/MacOS/PSD2Live" \
	packaging/macos/main.swift build/mac-launch.o \
	-framework Foundation
chmod +x "$app/Contents/MacOS/PSD2Live"

cp packaging/macos/Info.plist "$app/Contents/Info.plist"
printf '%s\n' "$java_home" > "$app/Contents/Resources/java.home"
cp build/run/jvm.args "$app/Contents/Resources/jvm.args"

iconset="build/AppIcon.iconset"
rm -rf "$iconset"
mkdir -p "$iconset"
src="src/main/resources/icons/psd2live.png"
sips -z 16 16 "$src" --out "$iconset/icon_16x16.png" >/dev/null
sips -z 32 32 "$src" --out "$iconset/icon_16x16@2x.png" >/dev/null
sips -z 32 32 "$src" --out "$iconset/icon_32x32.png" >/dev/null
sips -z 64 64 "$src" --out "$iconset/icon_32x32@2x.png" >/dev/null
sips -z 128 128 "$src" --out "$iconset/icon_128x128.png" >/dev/null
sips -z 256 256 "$src" --out "$iconset/icon_128x128@2x.png" >/dev/null
sips -z 256 256 "$src" --out "$iconset/icon_256x256.png" >/dev/null
sips -z 512 512 "$src" --out "$iconset/icon_256x256@2x.png" >/dev/null
sips -z 512 512 "$src" --out "$iconset/icon_512x512.png" >/dev/null
sips -z 1024 1024 "$src" --out "$iconset/icon_512x512@2x.png" >/dev/null
iconutil -c icns "$iconset" -o "$app/Contents/Resources/AppIcon.icns"

echo "built $root/$app"
open "$root/$app"
