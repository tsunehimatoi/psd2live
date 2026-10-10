#!/bin/bash
# Cloud sessions: resolve Maven Central through Google's mirror (repo.maven.apache.org answers 429 there), then fetch
# the build's dependencies and compile once, so tests run offline-fast when the session starts.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

mkdir -p "$HOME/.gradle/init.d"
cp "$CLAUDE_PROJECT_DIR/.claude/hooks/google-maven-mirror.gradle.kts" "$HOME/.gradle/init.d/google-maven-mirror.gradle.kts"

cd "$CLAUDE_PROJECT_DIR"
./gradlew compileTestKotlin --quiet
