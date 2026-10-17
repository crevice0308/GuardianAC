#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${JAVA_HOME:?Set JAVA_HOME to JDK25}"
: "${GUARDIAN_PROJECT_ROOT:?Set immutable verified artifact project root}"
: "${GUARDIAN_PLUGIN_SHA256:?Set immutable verified SHA256}"
: "${GUARDIAN_IP_EXPECTED_SHA256:?Set separately approved replacement artifact SHA256}"
[[ "$GUARDIAN_PLUGIN_SHA256" == "$GUARDIAN_IP_EXPECTED_SHA256" ]]
[[ "$GUARDIAN_IP_EXPECTED_SHA256" != "11ffe3b21f6c3d411320e84d85e3409e9651c21231c0a1ffa0cbba3ede7326f0" ]]
: "${GUARDIAN_PAPER_BASELINE:?Set initialized official Paper26.3 baseline}"
: "${GUARDIAN_IP_BUILD_OUTPUT:?Set isolated fixture output}"
ARTIFACT="$GUARDIAN_PROJECT_ROOT/build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar"
[[ "$(sha256sum "$ARTIFACT" | cut -d' ' -f1)" == "$GUARDIAN_PLUGIN_SHA256" ]]
CP="$ARTIFACT"
if [[ -n "${GUARDIAN_BUKKIT_API_JAR:-}" ]]; then CP="$CP:$GUARDIAN_BUKKIT_API_JAR"; fi
while IFS= read -r -d '' library; do CP="$CP:$library"; done < <(find "$GUARDIAN_PAPER_BASELINE/libraries" -name '*.jar' -print0)
mkdir -p "$GUARDIAN_IP_BUILD_OUTPUT/classes"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$CP" -d "$GUARDIAN_IP_BUILD_OUTPUT/classes" RealClientFixture.java ../../runtime/fixture/safety/FixturePorts.java
"$JAVA_HOME/bin/jar" cf "$GUARDIAN_IP_BUILD_OUTPUT/RealClientFixture.jar" -C "$GUARDIAN_IP_BUILD_OUTPUT/classes" . -C . plugin.yml
printf '%s\n' "$GUARDIAN_IP_BUILD_OUTPUT/RealClientFixture.jar"
