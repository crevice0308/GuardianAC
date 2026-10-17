# Verified clean-cache build

Requirements: a POSIX Linux environment (such as Linux or WSL2), Python 3.11+, JDK 25, and access to the official Gradle/Maven/Spigot/CodeMC repositories configured by the pinned build. No Minecraft server is started by these commands.

The preparation helper uses POSIX descriptor-based no-follow filesystem operations; native Windows Python is not supported. This build-tool requirement does not establish server runtime compatibility on any platform.

From a fresh checkout or extracted public source, choose a **nonexistent destination outside the source tree** under a directory owned by you and not writable by other users:

```sh
python3 tools/test_prepare_verified_build.py
BUILD_DIR="$(dirname "$(pwd -P)")/GuardianAC-Ultimate-build"
python3 tools/prepare_verified_build.py --destination "$BUILD_DIR"
cd "$BUILD_DIR"
./gradlew --dependency-verification=strict --no-daemon clean test shadowJar sourcesJar
python3 tools/verify_jar.py build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar
python3 third-party/sources/verify_sources.py --plugin-jar build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar
```

The output is in the build copy's `build/libs/`; distribute the standalone JAR, not `-plain.jar`. For a second independent build, choose another fresh destination and a fresh `GRADLE_USER_HOME`.

## Why preparation is required

The historical `gradle/verification-metadata.xml` omitted some ancestor POM/BOM/module entries. A fresh GitHub runner stopped before compilation; all first 16 rejected entries were absent, rather than mismatches against existing hashes. Gradle maintainers describe how cached parsed POMs can skip ancestor traversal during verification generation ([issue #20194](https://github.com/gradle/gradle/issues/20194)). This mechanism is consistent with the observed failure; the original cache history is not independently proven.

The supported clean-cache path uses `tools/verification-supplement.json` and `tools/prepare_verified_build.py`. Preparation verifies the fixed historical build-input manifest and all 155 listed inputs, validates the supplement, then copies only selected source into a separate fresh directory. Only that copy receives explicit additional metadata SHA-256 entries. Source code, dependency binary hashes and existing verification settings are not replaced. Ordinary builds perform no online checksum generation and never disable dependency verification.

Supplement entries identify exact official Maven Central paths. Published SHA-256 sidecars were checked where available. Older entries without those sidecars were compared byte-for-byte across the two official Maven Central HTTPS endpoints and checked against the published SHA-1 sidecar for consistency; their pinned build digests are calculated SHA-256, not SHA-1. These endpoints share a registry and are not independent provenance authorities.

A direct `./gradlew` invocation in the original unprepared tree can still fail with a fresh cache. Do not work around this by disabling verification or automatically accepting newly downloaded checksums. An unknown or changed dependency must stop the build for review. Build-copy preparation does not execute Gradle, download dependencies or start servers.

## Artifact correspondence

The release JAR stays fixed at SHA-256 `fb56fa5d8fcca60271863f7ced20df1ff9dfc79bbbc9e0757fc68b309687a5d1`. The original distribution keeps all 155 recorded inputs unchanged. Run `tools/verify_source_handoff.py` against the **original distribution**, its matching source ZIP and the fixed release JAR, not against the augmented build copy.

A successful build and repeated same-environment equality do not establish universal cross-JDK/OS byte-for-byte reproduction of that historical JAR. Never substitute a rebuilt JAR for the fixed release merely to satisfy a checksum. Consult the exact CI run for its current result.
