#!/usr/bin/env bash
# Run all JVM unit tests.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew test
