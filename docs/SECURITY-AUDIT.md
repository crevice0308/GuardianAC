# Security review record

> Public package scope: detailed generated execution records are omitted. See the [aggregate verification summary](../TEST-RESULTS.md) for retained totals and limitations. Ordinary source and static software manifests remain.

This is a development review, not a certification or penetration-test guarantee.
All runtime actors, names and addresses in the test reports are isolated fixtures.
No production player data, real bans or Discord webhook transmissions were used.
Exact tested binaries and results belong in `TEST-RESULTS.md`; an earlier binary's
passing report does not validate a later source change.

## Current optional-metrics correction

An earlier internal soak exposed an ineffective embedded metrics-disable setting.
The d2737dec224c artifact is blocked from deployment. See
[metrics privacy](METRICS-PRIVACY.md) for source-specific intended payload/timing,
correction boundaries and the interrupted-run limitation. No sender or successful
delivery is inferred merely from an enabled configuration. Termination of that
old process is unknown, so continued activity cannot be excluded.

Corrected fb56fa5d8fcc removes the embedded reporter and passes its retained
offline source/classfile checks. Its new supervised Paper modifier/30-minute
soak passed 194/194 assertions in a 1,801.031865543-second soak, with 30 churn
joins and cleanup observations, final tracked state zero, SQLite integrity `ok`,
zero BAN/evidence rows and normal owned JVM/supervisor/runner/log-collector
exits 0. The same plugin retains 796 JUnit passes across 39 suites. The separate
offline harness has 228 Python passes across 15 suites, including the 141-test
lifecycle subset, plus 86 dependency-free Java port-guard checks.

Both earlier interrupted runs retain unknown termination. The new normal-stop
result cannot prove those processes stopped or exclude older statistics activity
and resource contention. No bStats send is identified in retained corrected-run
observations, but no zero-attempt or universal zero-egress claim is made. The
finite two-client soak does not establish production accuracy or long-term leak
freedom. Fresh corrected Paper/Purpur matrices also passed 328/328 each, and
recovery boundaries passed 7/7 with seven normal owned stops and unchanged seed
data. The synthetic permanent-IP positive path remains separate from actual
authentication. Actual TCP core and bounded 64-client/admission suites also
passed 79/79 and 33/33 with eight normal owned stops. IP/DUAL offline fallback
passed 156/156 with four normal owned stops, protecting clean shared-address
peers. Current fixtures total 20 normal owned phases; genuine authentication,
human/graphical validation and other release gates remain open. Earlier
results do not supply missing current validation. See the
[aggregate verification summary](../TEST-RESULTS.md).

## Methods

- Manual source review of packet ingress, evidence policy, SQL transactions,
  native-ban ownership, administrative commands/GUI, private-file handling,
  optional webhook destinations, bounded caches and lifecycle shutdown.
- Java compilation with `-Xlint:all,-processing` and JUnit regression tests.
  Compiler lint is not a comprehensive vulnerability scanner.
- Historical exact-artifact assembled-JAR loading on isolated Paper/Purpur
  26.3, real Bukkit/PacketEvents fixtures and loopback protocol-777 TCP clients.
  Current corrected-artifact runtime evidence includes the completed supervised
  modifier/soak, Paper/Purpur matrices and recovery boundaries described above;
  their scopes now also include separately verified actual TCP core and bounded
  64-client/admission and offline IP/DUAL fallback suites. Genuine native
  authentication remains outside those scopes.
- Binary inventory checks for relocated dependencies, required JDBC services,
  native SQLite libraries, duplicate ZIP members and bundled license notices.

## Issues discovered and addressed during development

| Finding | Correction | Regression evidence |
| --- | --- | --- |
| Embedded PacketEvents referenced relocated Gson absent from the JAR | Bundle and relocate Gson explicitly | Actual server startup and binary inventory |
| Main-thread MOVE timestamps could suppress slightly older queued PACKET evidence | Per-kind monotonic ordering, bounded cross-kind skew and a global decay clock | Mixed-stream unit regression and malformed-packet TCP trace |
| Unrelated packets cleared a hard-check accumulation buffer | Count relevant observations within the bounded evidence window; retain time-based decay | Interleaved packet engine regression and socket BAN test |
| Global replay throttling hid packet frames among movement frames | Throttle each observation kind separately; persist exact offending observations | Replay unit assertions and saved-evidence hash/raw-value checks |
| Correlated coordinate/rotation checks could appear independent | Group movement-encoding checks into one evidence family | Independent-family policy tests |
| Hex-only administrative unban input could reach hostname resolution on the main thread | Use numeric-only IP normalization before database lookup | Literal-parser regression and command runtime fixture |
| Shared-address account count could change before sanction persistence | Recheck count in the same SQL transaction; downgrade to UUID if shared | Atomic shared-IP regression |
| Async sanction work could outlive an administrator's policy change | Invalidate authorization before publishing settings; one-shot guarded SQL commit and immutable decision revalidation | Gate/evidence/rollback/policy-publication unit tests; exact-JAR real TCP sanction/restart regression passed |
| Returning before unsupported packet decoding could hide the translation exemption | Track conservative exact-protocol eligibility independently of movement decoding | Protocol-boundary unit tests and actual Paper/Purpur PacketEvents fixtures passed |
| Empty columns consumed the scarce Xray heavy-rewrite budget | Bounded read-only palette prefilter; unknown/global palettes remain conservative candidates | Palette/concurrency tests plus all 16 actual TCP viewers masked and revealed correctly |
| Xray callback could commit a snapshot after configuration changed | Generation/current-configuration commit guard under the rewrite lock | Deterministic generation/race tests plus assembled-JAR concealment/lifecycle regression passed |

## Boundary review

- **Permissions and GUI:** separate view/admin/unban grants; operator status is
  not automatic anti-cheat bypass. Menu actions come from server-owned holders.
  Delayed menu actions recheck actor/navigation/permission; confirmation tokens
  are one-use, actor-bound, expiring and bind exact previewed cases.
- **Evidence and sanctions:** heuristic rules never independently authorize a
  BAN. Multiple hard families, repetition, time span, confidence, decayed VL,
  supported protocol and durable matching evidence are required. A failed
  save or rejected commit guard does not cause enforcement. An ordinary logout
  cannot erase an already captured valid decision awaiting persistence. A
  confirmed administrative UNBAN/VL reset cancels that player's still-unclaimed
  decisions without revoking separately committed later cases.
- **SQLite:** parameterized queries, strict validation, evidence hashes,
  transactions, integrity checks, an exclusive owner lock and verified backups.
  Corrupt state is preserved for recovery rather than silently reset.
- **Native synchronization:** exact per-case ownership markers protect foreign
  bans and unrelated active cases. UUID/IP components may be revoked separately.
- **Files and secrets:** guarded private exports, constrained filenames,
  symlink/hardlink checks where applicable, bounded evidence/export capacity,
  encrypted IPs and separate keys. Native server IP-ban files remain plaintext;
  operators must secure those files and server logs separately.
- **Network:** exact supported protocol gate; bounded packet queues and work;
  malformed/flood cancellation is resource protection, not automatic guilt.
  The optional webhook is off by default, manually configured, restricted to
  canonical Discord HTTPS URLs, uses no redirects and suppresses mentions.
- **Concurrency:** Bukkit/world access remains on the server thread. Packet
  callbacks use immutable snapshots; SQL and exports have bounded workers.
  Authorization linearizes immediately before SQL commit. Third-party permission
  changes are observed on main-thread revalidation/ticks; Bukkit has no universal
  permission-change event.

## Residual risks and release gates

1. No guarantee against upstream decoder vulnerabilities before callbacks,
   volumetric network attacks, arbitrary hostile plugins or OS compromise.
2. Bounded fail-open Xray work can expose information during saturation. A
   no-target prefilter does not guarantee per-player coverage in dense ore terrain.
3. SQLite payload retention is not a hard cap on total history/WAL/backup disk.
   Operator backup/key custody and disk monitoring remain necessary.
4. No representative live-player false-positive/false-negative rate has been
   measured. Statistical aim/mining signals do not identify a client modification.
5. Actual Spigot runtime, graphical GUI/cave inspection, broad third-party plugin
   compatibility and non-Linux runtime remain unverified unless a later exact
   report explicitly closes a gate.
6. This review does not authorize GitHub or release publication. Formal 1.0.0
   requires the complete feature and validation ledger plus publication approval.

### Compatibility-gate regression found by the real-server matrix

The first hardening candidate `6bd582694666…` incorrectly rejected the actual
Paper/Purpur API formats `26.3.build.179-stable` and
`26.3.build.2648-experimental`. It stayed in storage/admin-only mode and did not
perform detection, so it must not be treated as a passing functional artifact.
The parser was corrected with the observed exact-release forms and negative
future/patch/unknown-label tests. Exact protocol 777 remains independently
required. The Purpur experimental channel is explicitly disclosed; accepting its
26.3 API format is not a stable-release certification.

### Quit-event telemetry resurrection found by the long-run fixture

Candidate `8eb86ea114de…` retained EventTelemetry entries after disconnect: seven
fixture departures increased its count from two to eight while only one player
remained online. Other tracked subsystems returned to one. The scheduled
30-minute run was stopped early once the failure was established; interruption
exit 130 is recorded, not represented as a successful full soak.

Inventory close and other callbacks previously auto-created UUID-only state
after quit cleanup. The correction uses a bounded, identity-bound active-session
registry. Only join/start/authoritative online snapshots admit sessions; quit
forgets them; late callbacks cannot recreate them or affect a same-UUID new
connection. Administrative/teleport history resets remain distinct from session
removal. Snapshot reconciliation also removes missed departures and retries
capacity-limited online sessions. Inventory close is non-allocating. Thirteen
pure lifecycle regressions cover this policy, including 10,000 churn cycles.
The historical `11ffe3b21f6c…` artifact passed the fresh 114-assertion matrix on each
server and the complete 1,801.022-second modifier observation with 30/30 quit
cycles and zero final player state. This establishes the bounded scenario, not
a universal memory-leak certificate; aggregate results are in `TEST-RESULTS.md`; detailed counters are not bundled.

### Packet-session identity boundary and completed regressions

A UUID-only packet-state lookup and queue admitted a structural race: an earlier
connection with the same account can reach LOGIN_SUCCESS, stay in CONFIGURATION,
allow another connection to reach PLAY, then disconnect. PacketEvents invokes
disconnect from that connection's Netty close future, while the former adapter
removed state by UUID alone. This could erase the current session's counters and
Xray reveal ledger. A <=500 ms old queue tail could also seed current-session
replay/counts after a reset. The isolated tail cannot by itself satisfy the
three-second, repeated, multi-family BAN gate; no false BAN has been observed.

The correction binds state to an exact main-thread Player/channel/User identity,
uses state-owned ingress envelopes, rejects retired/foreign sessions, and confines
reveal-ledger retirement to main-thread reconciliation. User lookup must use the
actual Player channel, not PacketEvents' UUID-to-channel cache, which can already
refer to the other configuration connection. Nine pure session regressions,
24 deterministic adapter assertions on each real server, and a separate 16/16
actual two-socket CONFIGURATION/PLAY overlap now pass on `11ffe3b21f6c…`. The
active connection retains its exact state token and Xray ledger without a chunk
resend, then receives the true ore on approach. The old artifact was not executed
as a negative control; old-code reachability and corrected runtime evidence are
kept distinct in `TEST-RESULTS.md`.

### Shared-address trust blind spot found after the bounded IP workflows

The `11ffe3b21f6c…` baseline's automatic IP/DUAL eligibility and serialized SQL
recheck count only `trusted=1` observations. Normal offline-mode joins record
`trusted=false`, so multiple known UUIDs at the same address count as zero.
Zero trusted identities are not evidence that an address is exclusive. This can
restrict unrelated clean users behind a shared/unsupported-proxy address when
IP/DUAL is explicitly selected; the default UUID setting does not remove that
non-default risk. The 126-assertion positive/preseeded-trust workflow did not
exercise this missing boundary and is not proof that it is safe.

The correction requires positively supported authentication and
the target's qualifying address observation; use every observed UUID, regardless
of trust, for shared-address protection; recheck the target and total count in
the same serialized commit. Unknown/offline identity falls back to the evidence-
justified UUID sanction. The user-approved administrator-reviewed permanent-IP workflow separately
requires the same trusted and exclusive-address boundary. Artifact `39bd4f060a25…`
passed 156 actual TCP IP/DUAL fallback assertions with two untrusted same-IP
actors; both clean actors stayed admitted through restart. The old direct
offline-IP behavior is no longer a successful target.

### Reviewed permanence, native recovery and immediate UNBAN

The focused independent review identified and verified three corrections:
30-day minimum address-observation retention even with shorter evidence retention;
periodic source-player enforcement after bounded urgent reconciliation failure;
and immediate cancellation of preview-bound old automatic decisions when UNBAN
is confirmed, before asynchronous SQL. The immutable evidence, candidate, exact
actor/session lease, current policy, association counts and final pure commit guard
are rechecked at their boundaries. New IP-only cases preserve source UUID expiry.

Artifact `39bd4f060a25…` passed 698 JUnit tests, 256 actual Paper fixture assertions
and 256 actual Purpur fixture assertions (79 reviewed-permanent-IP and 18 native
lifecycle checks on each). The trusted/native positive provenance is explicitly
synthetic; actual offline production intake was separately refused. These do not
prove Microsoft-authenticated admission or representative human false-positive
rates. All four matrix processes stopped normally. Later configuration/locale
source corrections require another exact-artifact run.

Native SQLite/Bukkit effects remain eventual rather than atomic. An arbitrary
trusted plugin can cancel/throw during a kick; the exact-session marker suppresses
repeated attempts, so no observed-disconnect claim follows from the attempt.

### Full-spec settings review

A later specification audit identified missing advanced GUI controls, incomplete
Japanese messages, retained heuristic relaxation after automatic tuning was
disabled, and Xray mining collection continuing after its documented master
switch was disabled. Those corrections are implemented in `d2737dec224c…`: 796 JUnit tests and
328 assertions on each Paper/Purpur server pass. The settings cases include
actual restart application of all five startup allocations and 46 advanced-GUI
assertions per server. The full follow-up source review found no further material
defect in these four areas. These changes were not part of the earlier
`39bd4f060a25…` pass.

### Matching third-party source handoff

The dependency source review additionally found pre-shaded Adventure serializers,
Option and bStats inside PacketEvents. These are absent from the direct Gradle
runtime dependency list but now have matching tagged source and retained notices.
The static verifier indexes 2,658 upstream dependency classfiles; the final
`d2737dec224c…` JAR has 2,652 indexed dependency class paths and no unmapped
dependency class. All 20 native SQLite blobs match the official Xerial tag and
SQLite source identity. Twenty-one official source archives include required
generators, patches and build inputs. This is provenance verification rather
than independent native rebuilding or legal certification.
