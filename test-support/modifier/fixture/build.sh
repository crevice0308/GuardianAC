#!/usr/bin/env bash
set -euo pipefail
INVOCATION_DIR="$PWD"
cd "$(dirname "$0")"
: "${JAVA_HOME:?Set JAVA_HOME to JDK25}"
: "${GUARDIAN_PROJECT_ROOT:?Set GUARDIAN_PROJECT_ROOT to the exact pinned artifact project root}"
: "${GUARDIAN_PAPER_BASELINE:?Set GUARDIAN_PAPER_BASELINE to an initialized official Paper26.3 fixture}"
ARTIFACT="$GUARDIAN_PROJECT_ROOT/build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar"
CP="$ARTIFACT"
if [[ -n "${GUARDIAN_BUKKIT_API_JAR:-}" ]];then CP="$CP:$GUARDIAN_BUKKIT_API_JAR";fi
while IFS= read -r -d '' library;do CP="$CP:$library";done < <(find "$GUARDIAN_PAPER_BASELINE/libraries" -name '*.jar' -print0)
OUT="${GUARDIAN_MODIFIER_BUILD_OUTPUT:-$INVOCATION_DIR/guardianac-modifier-tools}"
mkdir -p "$OUT/classes"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$CP" -d "$OUT/classes" ModifierFixture.java ../../runtime/fixture/safety/FixturePorts.java
"$JAVA_HOME/bin/jar" cf "$OUT/ModifierFixture.jar" -C "$OUT/classes" . -C . plugin.yml
printf 'Set GUARDIAN_MODIFIER_FIXTURE_JAR=%s/ModifierFixture.jar\n' "$OUT"
