# GuardianAC Ultimate — evaluation pre-release

This unchanged, unobfuscated 1.0.0-dev.1 JAR targets Java 25 and Minecraft 26.3. It is not a completed all-cheat automatic-ban release. Of 61 observation predicates, five strict protocol rules can contribute to evidence-gated automatic bans. The other 56 are review-only heuristics. **Automatic punishment escalation for Fly, Speed, Aim, Xray and the other heuristic predicates is not implemented.** STRICT or threshold changes do not add that path.

Actual Spigot runtime, human-play false-positive/false-negative rates and genuine native-authenticated positive admission remain unverified. Client rendering and intent are not directly observable by this server plugin.

The implementation includes packet/event observations, a 54-slot Japanese/English GUI, SQLite bans/evidence/audit, evidence-before-enforcement safeguards, shared-IP protection, an administrator approval flow for permanent IP sanctions, confirmation-based unban, bounded observation replay, and limited Xray concealment/statistics. There is no ordinary automatic-kick punishment. Disconnecting an already-banned connection is part of enforcing that ban. No prerequisite plugin or external database is required. Optional Discord delivery defaults OFF.

Defaults are STRICT and evidence-gated 24-hour UUID bans. Operators are checked unless explicitly granted guardianac.bypass. Disable automatic bans while calibrating legitimate behavior in an isolated backed-up evaluation server. See [installation](../INSTALL.md), [configuration](../CONFIGURATION.md) and [feature gates](FEATURE-MATRIX.md).

## Build

Use JDK 25 and the included Gradle wrapper. First resolution requires the configured official dependency repositories.

```sh
./gradlew --no-daemon clean test shadowJar sourcesJar
```

The standalone output is build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar, not the plain JAR. Running Minecraft itself requires the operator's EULA acceptance; normal build/unit tests do not start Minecraft.

[Aggregate verification](../TEST-RESULTS.md) distinguishes retained historical results from unverified behavior. This public package excludes detailed execution logs and individual player/case records; ordinary test sources and the nonidentifying 155-input correspondence manifest remain. Counts are not measured production accuracy.

[Provenance](PRERELEASE-PROVENANCE.md) / [module inventory](MODULES.md) / [Japanese](../README.md)

First-party code: GPL-3.0-only. Matching dependency sources, the embedded PacketEvents changes, and license notices are included. See [LICENSE](../LICENSE.txt) and [third-party notices](../THIRD-PARTY-NOTICES.md).
