#!/usr/bin/env bash
# Helper script to package PSD2Live for Linux distribution.
# This script does NOT include proprietary Cubism SDK binaries by default.
# Developers must build and deploy the SDK locally if they want Cubism preview.
#
# Usage:
#   ./native/package_linux.sh [--include-local-cubism]
#
# Options:
#   --include-local-cubism: Include locally deployed Cubism binaries (for personal use only)
#                          WARNING: Do NOT distribute packages created with this flag!

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
INCLUDE_CUBISM=0

# Parse arguments
for arg in "$@"; do
  case "$arg" in
    --include-local-cubism)
      INCLUDE_CUBISM=1
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: $0 [--include-local-cubism]" >&2
      exit 1
      ;;
  esac
done

echo "=================================================="
echo " PSD2Live Linux Package Helper"
echo "=================================================="

# Check if we're in the right directory
if [[ ! -f "$REPO_ROOT/build.gradle.kts" ]]; then
  echo "[ERROR] Cannot find build.gradle.kts. Run from repository root." >&2
  exit 1
fi

# Build the application
echo "[1/3] Building application with Gradle..."
cd "$REPO_ROOT"
./gradlew clean build -x test

# Prefer the main application jar (exclude sources / javadoc / plain classifiers).
JAR_PATH=""
shopt -s nullglob
for candidate in "$REPO_ROOT/build/libs"/*.jar; do
  base="$(basename "$candidate")"
  case "$base" in
    *-sources.jar|*-javadoc.jar|*-plain.jar) continue ;;
  esac
  JAR_PATH="$candidate"
  break
done
shopt -u nullglob

if [[ -z "$JAR_PATH" || ! -f "$JAR_PATH" ]]; then
  echo "[ERROR] Cannot find built application JAR in build/libs/" >&2
  exit 1
fi

echo " Found JAR: $JAR_PATH"

# Create distribution directory
DIST_DIR="$REPO_ROOT/dist/linux-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DIST_DIR"

# Copy JAR
echo "[2/3] Packaging..."
cp "$JAR_PATH" "$DIST_DIR/psd2live.jar"

# Optionally include Cubism binaries (for personal use only) before writing the launcher,
# so CUBISM_SDK_PATH is exported BEFORE exec java.
CUBISM_INCLUDED=0
if [[ "$INCLUDE_CUBISM" == "1" ]]; then
  CUBISM_SRC="$REPO_ROOT/src/main/resources/cubism/linux-x86_64"
  if [[ -d "$CUBISM_SRC" ]]; then
    echo " Including local Cubism SDK binaries (PERSONAL USE ONLY)..."
    mkdir -p "$DIST_DIR/cubism/linux-x86_64"
    cp -r "$CUBISM_SRC"/* "$DIST_DIR/cubism/linux-x86_64/"
    CUBISM_INCLUDED=1

    cat > "$DIST_DIR/CUBISM_NOTICE.txt" << 'NOTICE_EOF'
WARNING: This package includes locally built Live2D Cubism SDK binaries.

The Cubism SDK is proprietary software owned by Live2D Inc.
These binaries are provided for your PERSONAL USE ONLY under the
Live2D Proprietary Software License.

DO NOT REDISTRIBUTE this package publicly.
DO NOT share this package with others.

If you want to distribute PSD2Live, use the version WITHOUT Cubism
binaries and instruct users to build and deploy the SDK themselves
following the instructions in docs/*/guide/CUBISM_SDK_SETUP.md.

For Cubism SDK licensing, visit: https://www.live2d.com/
NOTICE_EOF
  else
    echo " WARNING: Cubism binaries not found at $CUBISM_SRC"
    echo " Run './native/build_live2d_renderer.sh --deploy' first."
    INCLUDE_CUBISM=0
  fi
fi

# Create launcher script (Cubism env must come before exec — exec never returns).
if [[ "$CUBISM_INCLUDED" == "1" ]]; then
  cat > "$DIST_DIR/psd2live.sh" << 'LAUNCHER_EOF'
#!/bin/bash
# PSD2Live launcher for Linux

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Check for Java
if ! command -v java &> /dev/null; then
    echo "Error: Java not found. Please install JDK 21 or later." >&2
    exit 1
fi

# Note: This package includes locally built Cubism SDK binaries (personal use only).
export CUBISM_SDK_PATH="$SCRIPT_DIR/cubism/linux-x86_64"

# Launch PSD2Live
exec java -jar psd2live.jar "$@"
LAUNCHER_EOF
else
  cat > "$DIST_DIR/psd2live.sh" << 'LAUNCHER_EOF'
#!/bin/bash
# PSD2Live launcher for Linux

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Check for Java
if ! command -v java &> /dev/null; then
    echo "Error: Java not found. Please install JDK 21 or later." >&2
    exit 1
fi

# Launch PSD2Live
exec java -jar psd2live.jar "$@"
LAUNCHER_EOF
fi

chmod +x "$DIST_DIR/psd2live.sh"

# Create README reflecting whether Cubism was included
if [[ "$CUBISM_INCLUDED" == "1" ]]; then
  cat > "$DIST_DIR/README.txt" << 'README_EOF'
PSD2Live for Linux
==================

Launch:
  ./psd2live.sh

Requirements:
- JDK 21 or later
- OpenGL / GLX (Mesa or vendor drivers) and an X11 display
  Headless: use xvfb-run ./psd2live.sh (see CUBISM_SDK_SETUP.md)

Cubism SDK Preview:
  This package INCLUDES locally built Cubism SDK binaries for PERSONAL USE ONLY.
  See CUBISM_NOTICE.txt — do NOT redistribute this package.
  The launcher sets CUBISM_SDK_PATH automatically.

For more information, visit:
  https://github.com/tsunehimatoi/psd2live

License: GNU GPL v3
README_EOF
else
  cat > "$DIST_DIR/README.txt" << 'README_EOF'
PSD2Live for Linux
==================

Launch:
  ./psd2live.sh

Requirements:
- JDK 21 or later
- OpenGL / GLX (Mesa or vendor drivers) and an X11 display (for Cubism preview)

Optional Cubism SDK Preview:
  This package does NOT include Live2D Cubism SDK binaries.
  The application will use its built-in software renderer.

  To enable native Cubism preview:
  1. Download Cubism SDK for Native (5-r.5) from Live2D
  2. Build the native library following docs/en/guide/CUBISM_SDK_SETUP.md
  3. Set CUBISM_SDK_PATH environment variable to the deployed directory
  4. On headless hosts, run under Xvfb (e.g. xvfb-run ./psd2live.sh)

For more information, visit:
  https://github.com/tsunehimatoi/psd2live

License: GNU GPL v3
README_EOF
fi

echo "[3/3] Package complete!"
echo ""
echo " Output: $DIST_DIR"
echo " Launch: cd $DIST_DIR && ./psd2live.sh"

if [[ "$CUBISM_INCLUDED" == "1" ]]; then
  echo ""
  echo " ⚠️  WARNING: This package includes Cubism SDK binaries!"
  echo "    FOR PERSONAL USE ONLY - DO NOT REDISTRIBUTE"
  echo "    See CUBISM_NOTICE.txt for details"
fi

echo "=================================================="
