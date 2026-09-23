#!/usr/bin/env bash
# Build liblive2d_renderer.so for optional Linux x64 Cubism preview.
# The wrapper links against a locally supplied SDK; proprietary Core,
# Framework and shaders are not included in this repository.
#
# Usage:
#   export CUBISM_SDK_ROOT=/path/to/CubismSdkForNative-5-r.5
#   ./native/build_live2d_renderer.sh
#   ./native/build_live2d_renderer.sh --deploy
#   ./native/build_live2d_renderer.sh --clean --deploy
#   ./native/build_live2d_renderer.sh --cubism-sdk-root=/path/to/CubismSdkForNative-5-r.5

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$SCRIPT_DIR/live2d_renderer"
BUILD_DIR="$SRC_DIR/build"
DEPLOY=0
CLEAN=0

# Parse command-line arguments
for arg in "$@"; do
  case "$arg" in
    --deploy|-Deploy)
      DEPLOY=1
      ;;
    --clean|-Clean)
      CLEAN=1
      ;;
    --cubism-sdk-root=*)
      CUBISM_SDK_ROOT="${arg#*=}"
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 1
      ;;
  esac
done

# Verify CUBISM_SDK_ROOT is set
if [[ -z "$CUBISM_SDK_ROOT" ]]; then
  echo "[ERROR] CUBISM_SDK_ROOT is not set." >&2
  echo "Download Cubism 5 SDK for Native, extract it, then either:" >&2
  echo "  export CUBISM_SDK_ROOT=/path/to/CubismSdkForNative-5-r.5" >&2
  echo "or pass:" >&2
  echo "  $0 --cubism-sdk-root=/path/to/CubismSdkForNative-5-r.5" >&2
  exit 1
fi

# Verify SDK structure
if [[ ! -f "$CUBISM_SDK_ROOT/Core/include/Live2DCubismCore.h" ]]; then
  echo "[ERROR] CUBISM_SDK_ROOT does not look like CubismSdkForNative-5-r.5:" >&2
  echo "  $CUBISM_SDK_ROOT" >&2
  echo "Expected Core/include/Live2DCubismCore.h under that directory." >&2
  exit 1
fi

if [[ ! -f "$CUBISM_SDK_ROOT/Core/lib/linux/x86_64/libLive2DCubismCore.a" ]]; then
  echo "[ERROR] Missing libLive2DCubismCore.a (required for Linux x86_64)." >&2
  echo "Expected under:" >&2
  echo "  $CUBISM_SDK_ROOT/Core/lib/linux/x86_64/" >&2
  exit 1
fi

echo "==================================================="
echo " Building liblive2d_renderer.so"
echo " SDK: $CUBISM_SDK_ROOT"
echo "==================================================="

# Check for CMake
if ! command -v cmake &> /dev/null; then
  echo "[ERROR] cmake not found on PATH. Install CMake 3.16+ and retry." >&2
  exit 1
fi

# Clean build if requested
if [[ "$CLEAN" == "1" ]]; then
  echo "[0/3] Cleaning $BUILD_DIR ..."
  rm -rf "$BUILD_DIR"
fi

# Create build directory
mkdir -p "$BUILD_DIR"

# Configure CMake
echo "[1/3] Configuring CMake..."
cmake -DCUBISM_SDK_ROOT="$CUBISM_SDK_ROOT" \
  -DCMAKE_BUILD_TYPE=Release \
  -B "$BUILD_DIR" \
  -S "$SRC_DIR"

# Build
echo "[2/3] Building Release..."
cmake --build "$BUILD_DIR" --config Release --target live2d_renderer

# Verify output
SO_OUT="$BUILD_DIR/bin/liblive2d_renderer.so"
SHADER_OUT="$BUILD_DIR/bin/FrameworkShaders"
if [[ ! -f "$SO_OUT" ]]; then
  echo "[ERROR] Expected output missing: $SO_OUT" >&2
  exit 1
fi

echo ""
echo " Build Successful!"
echo " SO:      $SO_OUT"
echo " Shaders: $SHADER_OUT"
echo " Expected dependents: libGL libX11 libpthread libdl (system libraries)"

# Deploy if requested
if [[ "$DEPLOY" == "1" ]]; then
  echo "[3/3] Deploying to src/main/resources/cubism/linux-x86_64/ ..."
  DEST="$REPO_ROOT/src/main/resources/cubism/linux-x86_64"
  mkdir -p "$DEST"
  cp -f "$SO_OUT" "$DEST/liblive2d_renderer.so"
  rm -rf "$DEST/FrameworkShaders"
  cp -r "$SHADER_OUT" "$DEST/FrameworkShaders"
  echo " Deployed to $DEST"
  echo " (path is gitignored; will not be committed)"
else
  echo "[3/3] Skip deploy. Pass --deploy to copy into resources/, or copy manually."
fi

echo "==================================================="
