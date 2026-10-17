# Implementation and release-gate ledger

> Public package scope: detailed generated execution records are omitted. See the [aggregate verification summary](../TEST-RESULTS.md) for retained totals and limitations. Ordinary source and static software manifests remain.

Status: evaluation pre-release 1.0.0-dev.1, not official 1.0.0. Implementation and validation
are separate. A feature's presence does not prove production accuracy, full
compatibility or completeness against every cheat.

**Implementation gap:** 61 observations comprise 5 hard protocol rules eligible for evidence-gated automatic bans and 56 review-only heuristics. The path that would turn Fly/Speed/Aim/Xray and other heuristic observations into independently justified high-confidence automatic sanctions is **not implemented**. It is not a dormant setting or merely a pending test. The original broad feature objective is therefore incomplete.

**Separate validation gaps:** actual Spigot runtime, representative clean/adversarial human-play false-positive/false-negative measurements and genuine native Microsoft-authenticated positive admission remain unverified.

**Observability limits:** client rendering, hidden client internals and IP-based personal identity cannot be established with certainty from server observations alone. Those limits cannot be closed just by raising a threshold or calling a fixture a real-player test.

Current frozen artifact `fb56fa5d8fcc` has retained offline validation and a
**completed supervised Paper modifier/30-minute soak**. All 194 assertions passed:
155 pre-soak + 30 churn joins + 6 soak-completion + 2 final-cleanup + 1 SQLite
integrity check. The 30 churn-cleanup snapshots are observations, not extra
assertions. The soak lasted 1,801.031865543 seconds with at most two simultaneous
synthetic loopback clients. Final tracked state was zero, SQLite integrity was
`ok` with zero BAN/evidence rows, and owned JVM/supervisor/runner/log-collector
exits were 0. See the [aggregate verification summary](../TEST-RESULTS.md).

Both earlier interrupted runs retain unknown termination. No old-process
termination, uncontended benchmark or universal zero-egress guarantee follows
from this new result. Fresh corrected Paper/Purpur matrices each passed 328/328
assertions; startup/native/corruption/offline-recovery boundaries passed 7/7.
All seven matrix/boundary server phases stopped normally, all three original
runners exited 0, both matrix databases had integrity `ok`, and the seed was
unchanged. See the [aggregate verification summary](../TEST-RESULTS.md).
Actual TCP core and bounded 64-client/admission suites pass 79/79 and 33/33,
with eight normal owned stops. See the [aggregate verification summary](../TEST-RESULTS.md).
The separate IP/DUAL offline-fallback suite passes 156/156 with four normal owned
stops. See the [aggregate verification summary](../TEST-RESULTS.md).
These current fixtures total 20 normal owned server phases. Exact source-archive
correspondence is reported in the accompanying handoff-verification manifest;
this source document alone does not establish that pass.

The matching production result remains 796 JUnit tests across 39 suites with
155 unchanged build inputs. The later offline harness aggregate passed 228 Python tests across 15
suites, zero failures/skips; its 141-test lifecycle subset is included, not
additive. The dependency-free Java port guard separately passed 86 checks.

Runtime observations below are hash-specific and distinguish the current
supervised fixtures from historical suites.
`d2737dec224c` has an embedded metrics-disable defect and must not be deployed.
`11ffe3b21f6c` has a shared-address trust defect and must not be deployed. Neither
artifact supplies missing corrected-runtime results. See [TEST-RESULTS.md](../TEST-RESULTS.md)
for complete artifact hashes, assertion scopes and evidence.

| Requirement | Implementation | Verification / remaining gate |
| --- | --- | --- |
| Standalone Java/Gradle single JAR | Implemented; PacketEvents/Gson/SQLite included and relocated where applicable | Corrected distribution audit and supervised Paper/Purpur 328/328 matrices pass; actual Spigot runtime remains unverified |
| 50+ actual independent modules | 61 predicates with separate IDs, guards and family grouping | Per-rule positive/boundary/missing/exemption/type tests. Real-world false-positive rate remains unmeasured |
| Hybrid packet/events | Main-thread snapshots and bounded packet ingress | Corrected 194-assertion modifier/soak, Paper/Purpur adapter/event matrices and 79-check actual TCP core pass within their bounded scopes |
| STRICT/NORMAL/RELAXED, VL/decay | Implemented | Pure engine temporal/family/lag/expiry tests |
| Evidence-gated automatic BAN | Implemented for 5 hard protocol rules only; the 56 heuristics do not contribute | Corrected Paper/Purpur actual TCP malformed slot/pitch traces verify cancellation, durable multi-family evidence/BAN and post-restart enforcement |
| Heuristic-to-high-confidence automatic sanctions | NOT IMPLEMENTED for the 56 review-only predicates, including Fly/Speed/Aim/Xray observations | Requires new evidence models and clean/adversarial validation; not enabled by configuration |
| UUID, IP, DUAL, temporary/permanent | Record/enforcement paths implemented | Database unit regressions, corrected Paper/Purpur native-list fixtures, actual TCP UUID sanction/restart and 156 IP/DUAL offline-fallback checks pass; genuine authenticated IP-positive admission unverified |
| Shared-IP protection | Native-current-session provenance and all-observed-UUID safeguards; unknown/offline/proxy identity falls back to UUID | Unit/transaction/30-day retention regressions and current 156/156 TCP IP/DUAL offline-fallback checks pass; clean same-IP peers remain protected despite two observed untrusted UUIDs; genuine authentication and isolated late SQL race coverage are separate |
| Administrator-reviewed permanent IP | Encrypted case/evidence-bound candidates, complete evidence inspection and separate permanent IP-only records | Unit regressions and 79 current permanent-IP assertions per server within 328 pass, with synthetic-positive/actual-offline-refusal/restart paths. UUID expiry unchanged; actual Microsoft authentication unverified |
| BAN evasion | Existing restrictions enforced; bounded IP-match advisories/evidence | Advisory only, never proof a new UUID is the same person |
| SQLite evidence, keys, recovery | WAL, transactions, integrity/hash checks, encrypted IPs, backups/offline restore, ownership lock | Unit regressions plus corrected 7/7 recovery boundaries, unchanged seed, integrity `ok` after both matrices and the soak; zero BAN/evidence rows in the soak |
| Native BAN ownership | Exact source markers and targeted reconciliation | Foreign native bans and other active cases preserved in current corrected Paper/Purpur matrices, including 18 native-lifecycle checks per server |
| Confirmed UNBAN | Actor/case-scoped one-shot confirmation, scope/case preview, audit and immediate old-decision reset; separate case IP-only GUI action | Replay/cancel/later-case/permission-loss/binding unit tests and current corrected server-side GUI cases pass; graphical-client usability unverified |
| 54-slot GUI, ja/en | Nine sections, paging, protected holders, read/admin separation, six bounded advanced resource editors, 60-key ja/en runtime catalog | Unit regressions and current Paper/Purpur inventory fixtures, including 46 advanced-GUI and 26 settings/runtime assertions per server; graphical-client visual/UX inspection pending |
| BAN presentation/notifications | Minimal player-name broadcast, detailed staff notice, ban screen, throttles | Current corrected server-side matrix fixtures; graphical disconnect-screen review pending |
| Xray ore concealment | Standalone per-client chunk masking and authoritative reveal | Corrected server-side chunk fixtures plus actual TCP far conceal/near reveal for 16 and 64 viewers pass with world data unchanged |
| Paper/Purpur native coexistence | Effective per-world mode detection; skip when enabled/unknown | Corrected Paper/Purpur enabled/disabled/unknown-state matrix fixtures plus Paper native-enabled boundary pass |
| Cave protection | Optional bounded air masking, off by default | Corrected TCP 27-cell cavity sample conceal/reveal and 11,760-candidate over-capacity fail-open pass; graphical rendering and broader multiplayer interaction pending |
| Mining path/rate/history | Bounded statistical analysis and evidence | Deterministic statistics tests; no Xray guilt claims or stat-only bans |
| Ghost blocks | Throttled next-tick visual block refresh after cancelled actions | Code/integration-path review; broader real-client interactions pending |
| Replay | Bounded position/view/action timeline, offending observations, private export | Memory/order/export tests and corrected actual TCP persisted independent malformed-packet evidence with verified payload hashes |
| Bots/flood/crash input | Connection/packet/payload limits, cancellation, bounded workers, notices | Algorithm tests, corrected malformed-input core and finite 40-login burst/recovery pass; volumetric DDoS and pre-callback decoder bugs outside plugin guarantee |
| Auto tuning | Only increases heuristic caution under reduced TPS; OFF immediately clears the automatic multiplier | Core regression and current corrected settings fixture plus finite induced low-TPS scenario pass; production tuning study pending |
| Optional external metrics | Ineffective deprecated setting in d273; source-built removal of private embedded reporter in fb56fa5d8fcc | Corrected source/classfile checks and completed supervised soak with disabled-setting snapshots. No identified bStats send does not prove no attempts; no universal egress audit |
| Discord | Disabled by default, manually registered secret, canonical host/no redirects, bounded sends | URL/rate/secrecy tests; no real webhook transmission performed |
| Test-harness process safety | Bounded owned-process supervisor, parent-loss handling, port/source bindings and strict terminal predicates | 141 lifecycle-focused Python checks are included in 228; 86 Java port-guard checks are separate. New supervised soak verifies normal owned Minecraft exit 0, not all failure paths or old-process termination; [bounds](../test-support/runtime/PROCESS-LIFECYCLE.md) |
| Player-state lifecycle | Bounded identity-bound admission; quit removes session, reset retains admission with fresh history | Pure lifecycle tests include 10,000 churn cycles. Corrected supervised soak completes five preflight quit cycles, 30 churn cleanup observations and final all-zero tracked state; finite two-client scope only |
| Performance | Local metrics, bounded queues/caches, async SQL/exports | Corrected offline benchmark, 1,801.032-second two-client soak and 64-client/31.292-second stationary smoke pass. Periodic samples and possible old-process contention limit inference; long mixed gameplay remains unverified |
| Spigot 26.3 | Pinned Spigot API compilation; independent masking path | Actual Spigot runtime unverified; official runtime and full exact-artifact matrix still required |
| Paper/Purpur 26.3 | Implemented | Corrected Paper modifier/soak and Paper/Purpur 328/328 matrices complete. Purpur experimental channel is not stable certification; exact hashes in TEST-RESULTS |
| Other plugins/Windows/Folia | Conservative modifiers/translation handling; documented platform limits | Broad third-party plugin conflicts and Windows runtime unverified; Folia not supported |
| GPL, source, docs, CI | Upstream source/notices included; no obfuscation selected | 21 official upstream archives; corrected final-JAR class/native source verification and byte-identical same-toolchain rebuild. Source-handoff verifier has 13 tests included in the 228; exact source-ZIP correspondence is recorded in the accompanying handoff-verification manifest, not established by this document alone. ZIP identity checking is not independent rebuilding or runtime validation |
| Public GitHub pre-release | Documentation-only pre-release preparation for the unchanged dev.1 artifact | Publication state is established by the actual GitHub release, not by this file; official 1.0.0 completion gates remain open |

## Formal 1.0.0 gates still open

Before the validation gates below, resolve the implementation gap above: develop justified high-confidence escalation for supported observable behavior, or explicitly agree on a narrower final scope. Do not treat the current 56 review-only predicates as complete automatic cheat enforcement.

1. Verify supported native Microsoft-authenticated admission and provenance in an authorized integration scenario. Current administrator-reviewed positive/refusal/race/restart fixtures use synthetic positive provenance and do not close real authentication coverage.
2. Obtain an official Spigot 26.3 runtime and complete the same exact-artifact matrix.
3. Gather representative clean/adversarial combat, movement, inventory and mining traces across mechanics, effects and realistic latency. Measure false-positive/negative behavior instead of inferring it from synthetic fixtures.
4. Perform graphical-client GUI/disconnect/cave-reveal inspection and extended multiplayer/load/long-run state-leak tests.
5. Complete conflict testing with representative mechanics/protocol/permissions plugins and any additional OS claimed as supported.
6. Review the exact final feature ledger, binary/source/licenses and receive explicit approval for public repository/release publication.

Internal development can proceed in stages. These gaps must not be concealed by
renaming a partial build to 1.0.0 or declaring every heuristic a reliable cheat detector.
