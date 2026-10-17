# Aggregate verification summary — fixed dev.1

## Scope and limitations first

This is a newly written aggregate summary of retained engineering results for the fixed JAR below. It does not include detailed execution streams, player names, player UUIDs, connection addresses, case records, disconnect payloads or machine-specific host identifiers. Publishing this summary does not mean tests were rerun for this documentation change.

- Plugin SHA-256: `fb56fa5d8fcca60271863f7ced20df1ff9dfc79bbbc9e0757fc68b309687a5d1`
- Artifact: GuardianAC-Ultimate-1.0.0-dev.1-fb56fa5d8fcc.jar (18,399,808 bytes)
- Runtime target: Java 25, Minecraft 26.3. Exact-target guards remain in source.
- Release state: evaluation pre-release; official all-feature 1.0.0 gates remain open.

There are 61 observation predicates: five protocol-hard predicates eligible for evidence-gated automatic sanctions and 56 review-only heuristics. Fly/Speed/Aim/Xray heuristic-to-automatic-sanction escalation is **not implemented**. A constant confidence value in source is not a measured detection-accuracy percentage.

## Retained aggregate results

| Verification group | Retained result | What this supports |
| --- | --- | --- |
| JUnit source snapshot | 796 tests across 39 suites | Unit and adapter-boundary behavior for the recorded source |
| Python fixture/tool regression | 228 tests across 15 suites | Offline packaging, source binding, safety and harness logic; includes 141 lifecycle-related checks |
| Separate inert Java port guard | 86 checks | Defensive fixture port validation; not part of the JUnit/Python totals |
| Paper / Purpur 26.3 matrices | 328 assertions per server | Bounded GUI/settings/storage/adapter fixture paths |
| Restart/recovery | 7 assertions | Tested storage and restart scenarios |
| Actual loopback TCP core | 79 assertions | Tested protocol, overlap and bounded Xray serialization paths |
| Bounded 64-client/admission suite | 33 assertions | The tested synthetic admission/viewer scenario |
| IP/DUAL fallback suite | 156 assertions | Tested eligibility, shared-IP fallback, ownership and restart paths |
| Modifier and 30-minute fixture | 194 assertions | Tested modifiers and supervised synthetic-duration scenario |

These suites overlap in purpose and must not be added into a unique gameplay-accuracy score. Synthetic accounts, synthetic trust-positive fixtures and loopback clients are not genuine authenticated human accounts. Purpur's tested experimental distribution is not stable-release certification. The corrected fixture set contains 20 normally ended owned phases; this does not resolve two older interrupted runs whose final termination was not confirmed. Historical optional-statistics transmission was not established either way. No universal no-egress or uncontended-performance claim is made.

## Static source and distribution correspondence

The fixed JAR and all 155 recorded build-bound inputs were rehashed during publication preparation. Matching source, tests, resources, build files, licenses and the embedded PacketEvents patch retain those input bytes. The public source excludes generated detailed report output and rewrites explanatory documentation; its archive hash therefore differs from older full-report packages.

The included `docs/reports/build-inputs.json` contains software paths, byte sizes and hashes only. It is retained for the source handoff verifier, not as an individual runtime record. Necessary dependency/source manifests likewise describe software artifacts and public source provenance.

Retained distribution inspection matched 21 upstream source archives, 2,636 upstream dependency class paths, two locally rebuilt PacketEvents builder classes and 20 SQLite native-library identities. The corrected JAR audit found no embedded bStats classes/references/endpoints or compile-only stubs. This is a scoped static observation, not a proof of all possible runtime network behavior. Earlier unsafe development artifacts are not release substitutes.

Same-environment repeat-build checks were retained. Source correspondence and repeated same-environment bytes are not universal cross-JDK/OS bit-for-bit reproducibility guarantees. The public CI result, when available, applies only to its exact commit and workflow; consult that run rather than treating this historical summary as a fresh CI pass.

Static official baseline software hashes and bytecode-gate facts are retained in [reviewed-server-statistics.json](test-support/runtime/reviewed-server-statistics.json). These identify software artifacts, not a server instance or a player, and do not substitute for a fresh run on another server build.

## Remaining validation and implementation gates

- Implement and independently validate behavioral evidence escalation before claiming Fly/Speed or other heuristic automatic enforcement. Do not simply mark them protocol-hard: that also bypasses physics exemptions in the current design.
- Actual Spigot server runtime; other Minecraft/API/protocol versions.
- Human clean/adversarial play, false-positive/false-negative rates, legitimate custom movement and broad plugin combinations.
- Genuine native-authenticated positive admission and realistic proxy/shared-network operation.
- Graphical GUI/rendering review, arbitrary terrain/load, broad OS/filesystem behavior and longer mixed workloads.
- Real optional Discord delivery. It remains OFF unless manually configured.

## Reproduce engineering checks

```sh
BUILD_DIR="$(dirname "$(pwd -P)")/GuardianAC-Ultimate-build"
python3 tools/prepare_verified_build.py --destination "$BUILD_DIR"
cd "$BUILD_DIR"
./gradlew --dependency-verification=strict --no-daemon clean test shadowJar sourcesJar
python3 tools/verify_jar.py build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar
python3 third-party/sources/verify_sources.py --plugin-jar build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar
```

The isolated-copy preparation tests are new build-support tests, not additions to
the historical anti-cheat runtime totals above. The original source tree remains
the source-handoff verification target; its build copy has an explicitly augmented
metadata verification file. See [BUILDING.md](BUILDING.md).

The ordinary CI lists the offline regression commands. Live harness source is supplied for controlled evaluation, but these commands do not start Minecraft. Any live rerun requires explicit EULA acceptance, a separate disposable loopback-only fixture, the retained privacy/termination gates and no real user credentials or production player data. Detailed old recordings are intentionally absent from this public package.
