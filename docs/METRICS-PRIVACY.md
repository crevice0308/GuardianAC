# Embedded metrics and isolated-test privacy

> Public package scope: detailed generated execution records are omitted. See the [aggregate verification summary](../TEST-RESULTS.md) for retained totals and limitations. Ordinary source and static software manifests remain.

## Defect found during internal validation

The internal artifact with SHA-256
`d2737dec224ca4baf8421485e298ae8fecff35664b2d9087ae50e81bb2d6ec42`
requested `PacketEventsSettings.bStats(false)`. PacketEvents 2.14.0 implements
that deprecated setter as a no-op. Its Spigot builder still constructs the
metrics reporter. A shared bStats configuration generated in the isolated
fixture had metrics enabled. The startup claim of no external telemetry was
therefore unjustified. Do not deploy that artifact or use its functional tests
as evidence that metrics were disabled.

Observation of that artifact's modifier soak was interrupted after unexpected
external metrics activity. Retained logs do not establish the exact sender,
payload actually sent, or successful delivery. Process termination is unknown;
continued activity from the old enabled collector cannot be excluded. The partial
run is not a completed 30-minute soak and has no verified normal shutdown. The unresolved historical limitations are summarized in
[TEST-RESULTS.md](../TEST-RESULTS.md); detailed partial recordings are not bundled.

## Verified intended behavior in upstream code

The bundled PacketEvents reporter identifies bStats service 11327 and sends
compressed JSON to `https://bStats.org/api/v2/data/bukkit`. The upstream payload
includes a random persistent server identifier, player count, online mode,
Bukkit version/name, Java version, OS name/architecture/version, processor
count, plugin version, metrics version and PacketEvents-version chart.

Its initial report is scheduled 3–6 minutes after initialization. A second
randomized phase starts later and repeats every 30 minutes. Thus a short boot
and immediate shutdown cannot establish that optional reporting is disabled.
These are source-level intended fields and timing, not a reconstruction of a
particular transmitted request. Actual identifiers are deliberately omitted.

The tested Paper and Purpur server JARs have a separate built-in reporter,
using `https://bstats.org/submitData/server-implementation`. Its fields include
server identifier, OS/processor information and version/Java/online-mode/
player-count/legacy-plugin charts. Their reviewed `PaperMetrics.startMetrics`
methods return before reporter construction and scheduling when the shared
configuration explicitly sets `enabled: false`.

## Correction boundaries

Corrected frozen artifact: `fb56fa5d8fcca60271863f7ced20df1ff9dfc79bbbc9e0757fc68b309687a5d1`. Its matching 796-test JUnit snapshot and byte-identical fresh-source rebuild are retained with 155 unchanged production build inputs. The later offline harness aggregate passed 228 Python tests across 15 suites with zero failures/skips. A lifecycle-focused subset of 141 is included in that total. A separate dependency-free Java port guard passed 86 checks; these are not part of the JUnit or Python counts. See the aggregate counts and limitations in `TEST-RESULTS.md`.

The new supervised corrected Paper run passed **194/194 assertions**, including
a completed 1,801.031865543-second soak, 30 churn joins, 30 cleanup observations,
final tracked state zero, SQLite integrity `ok` with zero BAN/evidence rows,
and normal owned JVM/supervisor/runner/log-collector exits 0. The assertion
breakdown is 155 pre-soak + 30 joins + 6 soak-completion + 2 final-cleanup +
1 database-integrity check. Cleanup observations and the 37 secondary export
consistency checks are not extra runtime assertions. See the [aggregate verification summary](../TEST-RESULTS.md).

The older corrected attempt remains **interrupted PARTIAL**, with 168 recorded
assertions and a last periodic sample at 721.858 seconds; its normal exit,
final database result and final all-player-zero cleanup were not established.
Termination of both interrupted runs remains unknown. The new test-only owned
supervisor's normal shutdown evidence applies only to its new run and cannot
establish old-process termination or exclude older metrics activity. Likewise,
old-process CPU/memory/disk contention cannot be excluded from performance
observations. See [process lifecycle](../test-support/runtime/PROCESS-LIFECYCLE.md).
The corrected Paper/Purpur matrices subsequently passed 328/328 each, and
recovery boundaries passed 7/7 with seven normal owned stops, using the same
optional-reporting controls. Actual TCP core and bounded 64-client/admission
suites also passed 79/79 and 33/33 with eight normal owned stops under those
controls. IP/DUAL offline fallback also passed 156/156 with four normal owned
stops under separately checked statistics settings. Together with the soak,
these fixtures have 20 normal owned server phases, without any broader egress
or historical-process claim. See
the [aggregate verification summary](../TEST-RESULTS.md).

The embedded-library correction uses a preferred editable Java copy of the
upstream Spigot builder with only bStats imports and initialization removed.
The build checks that exact minimal delta against the pinned pristine source
archive. Assembly excludes the upstream builder/nested implementation and
unused bStats runtime classes, then adds the source-built replacement through
the ordinary compiler and relocation pipeline. The matching patch, attribution
and build instructions are included with the source distribution.

GuardianAC must not change another plugin's or the server's global bStats
preferences. Its production correction affects only its private embedded copy.
PacketEvents update checks remain explicitly disabled. Discord is separately
manual and OFF by default; its configured operation is external communication.

Isolated fixture preparation explicitly disables the server's own optional
reporting, bundled Spark and startup version checks before launch; Purpur startup commands are empty/disabled. The common launcher validates the configuration
before invoking any JVM. Missing, ambiguous or enabled metrics settings must
prevent launch. These fixture controls do not alter a production server.

Final classfile scans, exact source-delta checks, negative/mutation tests and
fixture preflight regressions provide component-specific evidence. They do not
constitute a general sandbox or prove that every possible official-server,
third-party plugin or administrator-configured network operation is absent.
Corrected-artifact runtime status belongs in the current test ledger; source
changes alone are not a runtime pass.

The completed supervised run's settings snapshots before startup, during the run
and after normal stop show optional bStats, Spark and startup-version checks
disabled. Paper rewrote formatting/comments, but the entire parsed YAML mapping
remained equal to the prepared configuration. GuardianAC's configuration remained
byte-identical to the JAR defaults. No bStats send is identified in retained
observations; that does not establish that no attempt occurred. No packet capture
or universal egress audit was performed. Standard Mojang discovery DNS failures
are recorded separately, with no manual retry or successful delivery inferred.
See the [aggregate verification summary](../TEST-RESULTS.md).
The earlier interrupted corrected attempt still has unknown termination; its
individual snapshots and detailed point-in-time results are excluded.

The official server still contains authentication/session/profile/public-key
service paths. These are distinct game-service requests, not the optional metrics
removed or disabled here. Their complete absence is not asserted. See
[test-harness privacy](../test-support/runtime/PRIVACY.md) for exact reviewed
server/class hashes, static branch evidence and launcher limitations.
