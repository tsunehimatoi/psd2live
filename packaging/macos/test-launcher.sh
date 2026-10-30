#!/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
clang -Wall -Wextra "$root/packaging/macos/launch-test.c" \
	-I"$java_home/include" -I"$java_home/include/darwin" \
	-framework CoreFoundation -o "$temp_dir/launch-test"
"$temp_dir/launch-test"
