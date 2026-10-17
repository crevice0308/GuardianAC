#!/usr/bin/env bash
# Offline test-only Java compilation and execution. No server classes or sockets.
set -euo pipefail
cd "$(dirname "$0")"
: "${JAVA_HOME:?Set JAVA_HOME to the reviewed JDK25 directory}"
for name in JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS; do
  if [[ -n "${!name:-}" ]]; then
    printf 'Refusing injected JVM options: %s\n' "$name" >&2
    exit 1
  fi
done
OUT=$(mktemp -d "${TMPDIR:-/tmp}/guardianac-port-guard.XXXXXX")
trap 'rm -rf -- "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -d "$OUT" fixture/safety/FixturePorts.java fixture/safety/FixturePortsTest.java
"$JAVA_HOME/bin/java" -cp "$OUT" test.guardianfixture.safety.FixturePortsTest
