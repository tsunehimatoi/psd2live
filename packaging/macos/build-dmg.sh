#!/bin/bash
# Builds a self-contained PSD2Live.app (Swift shell + bundled runtime) and a DMG.
# With no arguments it creates the app image first. packageDmg passes --image and --version.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

image=""
version=""
while [[ $# -gt 0 ]]; do
	case "$1" in
		--image) image="$2"; shift 2 ;;
		--version) version="$2"; shift 2 ;;
		*) echo "Unknown argument: $1" >&2; exit 2 ;;
	esac
done

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -z "$image" ]]; then
	./gradlew createDistributable -Dorg.gradle.java.installations.paths="$JAVA_HOME"
	image="$root/build/compose/binaries/main/app/PSD2Live.app"
fi
if [[ ! -d "$image/Contents/runtime" ]]; then
	echo "Not a packaged app image: $image" >&2
	exit 1
fi
if [[ -z "$version" ]]; then
	version="$(plutil -extract CFBundleShortVersionString raw -o - "$image/Contents/Info.plist")"
fi

app="$root/build/PSD2Live.app"
rm -rf "$app"
mkdir -p "$(dirname "$app")"
cp -R "$image" "$app"

java_home="$app/Contents/runtime/Contents/Home"
clang -c packaging/macos/launch.c \
	-I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
	-o build/mac-launch.o
swiftc -O -o "$app/Contents/MacOS/PSD2Live" \
	packaging/macos/main.swift build/mac-launch.o \
	-framework Foundation -framework CoreFoundation
chmod +x "$app/Contents/MacOS/PSD2Live"

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
iconutil -c icns "$iconset" -o "$app/Contents/Resources/PSD2Live.icns"

plist="$app/Contents/Info.plist"
set_plist() {
	/usr/libexec/PlistBuddy -c "Set :$1 $2" "$plist" 2>/dev/null \
		|| /usr/libexec/PlistBuddy -c "Add :$1 $2" "$plist"
}
set_plist LSMinimumSystemVersion 13.0
set_plist LSApplicationCategoryType public.app-category.graphics-design
/usr/libexec/PlistBuddy -c "Add :CFBundleDisplayName string PSD2Live" "$plist" 2>/dev/null \
	|| /usr/libexec/PlistBuddy -c "Set :CFBundleDisplayName PSD2Live" "$plist"
set_plist NSSupportsAutomaticTermination "bool false"
set_plist NSSupportsSuddenTermination "bool false"
/usr/libexec/PlistBuddy -c "Delete :CFBundleDocumentTypes" "$plist" 2>/dev/null || true
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes array" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:0 dict" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:0:CFBundleTypeName string 'PSD2Live Project'" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:0:CFBundleTypeRole string Editor" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:0:LSHandlerRank string Owner" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:0:CFBundleTypeExtensions array" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:0:CFBundleTypeExtensions:0 string psd2live" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:1 dict" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:1:CFBundleTypeName string 'Photoshop Document'" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:1:CFBundleTypeRole string Viewer" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:1:LSHandlerRank string Alternate" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:1:CFBundleTypeExtensions array" "$plist"
/usr/libexec/PlistBuddy -c "Add :CFBundleDocumentTypes:1:CFBundleTypeExtensions:0 string psd" "$plist"

rm -rf "$app/Contents/_CodeSignature"
codesign --force --deep --sign - "$app"
test -f "$java_home/lib/server/libjvm.dylib"

stage="$root/build/dmg-root"
rm -rf "$stage"
mkdir -p "$stage"
cp -R "$app" "$stage/PSD2Live.app"
ln -s /Applications "$stage/Applications"
arch="$(uname -m)"
dmg="$root/build/PSD2Live-${version}-macos-${arch}.dmg"
rm -f "$dmg"
hdiutil create -volname "PSD2Live" -srcfolder "$stage" -ov -format UDZO "$dmg" >/dev/null
echo "built $dmg"
