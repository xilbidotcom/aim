#!/usr/bin/env sh
# Gradle wrapper simplified
DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_USER_HOME="$DIR/.gradle"
exec bash -c "gradle --no-daemon "$@""
