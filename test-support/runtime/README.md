# Isolated real-server integration harness

This is test source only. No Minecraft server binaries, worlds, private SQLite databases or IP encryption keys are included. Do not install `GuardianFixture` on an operating server. Use separately obtained official, initialized Paper 26.3 and Purpur 26.3 fixtures and JDK25.

## Required environment

- Python 3.10+ and dependencies pinned in `test-support/requirements-fixtures.txt` (including PyYAML for strict privacy validation)
- `JAVA_HOME`: JDK25 directory
- `GUARDIAN_PROJECT_ROOT`: absolute directory containing GuardianAC `build.gradle` and `build/libs/`
- `GUARDIAN_PAPER_BASELINE`: absolute initialized official Paper 26.3 server directory
- `GUARDIAN_PURPUR_BASELINE`: absolute initialized official Purpur 26.3 server directory
- `GUARDIAN_PLUGIN_SHA256`: the independently verified full SHA-256 of `build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar`
- `GUARDIAN_FIXTURE_MAX_SECONDS`: optional bounded per-server lifecycle budget, default 3600 seconds, accepted range 300–7200. The last 100 seconds reserve graceful automatic shutdown; timeout is always a failed result. See [PROCESS-LIFECYCLE.md](PROCESS-LIFECYCLE.md).
- `GUARDIAN_RUNTIME_OUTPUT`: optional absolute evidence directory, default `./guardianac-runtime-evidence`
- `GUARDIAN_PAPER_PORT` and `GUARDIAN_PURPUR_PORT`: optional paired overrides, with defaults `26731` and `26732` only when both are absent. Set both to distinct canonical decimal integers from `1024` through `65535`. Partial pairs, whitespace, signs, leading zeros, nonintegers and duplicate ports are rejected. The destination host is always the literal `127.0.0.1`; there is no host override.
- `GUARDIAN_ACCEPT_MINECRAFT_EULA`: set to `true` only after reading and accepting [Minecraft EULA](https://www.minecraft.net/en-us/eula). The runner refuses to start without it.

Privacy preparation and tests do not authorize a server start. Any outstanding server hold must be explicitly released before a launch. See [PRIVACY.md](PRIVACY.md) for the exact-bytecode evidence, no-server tests, and remaining network limitations.

The baseline directories are read-only inputs. Their server JARs and required libraries must already be present. The harness creates new disposable copies and binds only the literal `127.0.0.1` on the centrally selected Paper/Purpur ports, in offline mode. The same validated pair is used by every Python runtime, TCP-client, modifier, IP-workflow and load runner. Clients reject unselected target ports before opening a socket. Preparation writes any missing safety settings only for a new copy and every start revalidates loopback binding, exact port, offline mode, disabled RCON/query, EULA, artifact SHA-256, allowed helper JAR names, and absence of a Discord webhook file. New copies explicitly disable bStats, its payload logging, bundled spark enablement, and startup version checks before any JVM; Purpur startup commands are cleared. Every start rejects missing/enabled/ambiguous privacy settings, unreviewed server bytes, and injected JVM options before even checking Java version. Existing unsafe fixtures, including a prepared port that differs from the selected environment, are refused rather than rewritten. Per-phase redacted privacy descriptors bind the checked settings and source/server hashes without disclosing a metrics identifier. This does not assert universal network isolation or suppress Minecraft game-service traffic. Unexpected plugins or altered safety settings fail closed. Each JVM heap is capped at 768MiB. Each fresh server is owned by a bounded Python supervisor; Linux additionally guards the direct child against supervisor death without changing the JVM PID. Lifecycle journals distinguish normal stop, forced stop, and unavailable termination evidence. See [PROCESS-LIFECYCLE.md](PROCESS-LIFECYCLE.md) for exact budgets, supported modes, redaction and limitations. Run one at a time. Never reuse a run name for a different build; logs are preserved. If an older run has unverified termination, do not start a duplicate: a later approved proposal must use a fresh independent fixture and a distinct port pair. Selecting ports does not probe, reserve, or prove their availability and does not authorize a launch.

## Commands

1. Build the production artifact following the project instructions.
2. Run `./fixture/build.sh`. It compiles the helper against your artifact and Paper baseline libraries. `GUARDIAN_BUKKIT_API_JAR` can optionally provide a specific compile API.
3. Run `python run_matrix.py paper-26.3 YOUR_NEW_RUN_NAME`.
4. Run `python run_matrix.py purpur-26.3 YOUR_NEW_RUN_NAME`.
5. Optionally run `python test_boundaries.py YOUR_NEW_BOUNDARY_RUN YOUR_COMPLETED_RUN_NAME` for native Anti-Xray enabled detection, corrupt-database safe disable, offline backup restore and successful restart. It deliberately corrupts only the new disposable copy's database.
6. Run `python summarize.py` to produce the aggregate JSON report.

Inspect failures, exit statuses and exact artifact hashes. Passing assertions on an older artifact do not establish that a newer artifact passed. The helper JAR is never part of the production artifact.

## Coverage and limits

The matrix also uses one bounded loopback TCP actor from the sibling `client/protocol_client.py` fixture to exercise EventTelemetry admission, close/late-event non-resurrection and restart readmission using an independent adapter. This is not a movement or rendering compatibility claim.

Actual Bukkit plugin lifecycle, listener registration, commands, SQLite persistence/backup/recovery, real native profile/IP ban ownership, and server-side GUI routing are covered. The `guiipunban` stage exercises the IP-only case button, permission rechecks, actor/close/replay rejection, exact preview pagination, no-item-transfer cancellation, DUAL partial revocation, actor/scope/reason audit, retained evidence and UUID sanctions, same-address cases added before/after preview, and owned versus foreign native IP bans. It uses synthetic loopback sanctions and proxy players; it does not operate on real accounts. GUI tests use real inventories with synthetic proxy players/events; they do not demonstrate graphical-client usability. Embedded PacketEvents tests use actual 26.3 runtime mappings and chunk bytes serialized then parsed again, alongside malformed receive-packet fixtures. Unsupported/unknown protocol fixtures verify no parsing, cancellation or emitted hard evidence and conservative event-heuristic ineligibility. They do not constitute a ViaVersion/Geyser integration run. They do not demonstrate joined-client network interoperability, broad clean-play false-positive rates or many-player load. Those require separate scenarios.

The Spigot 26.3 runtime stage is unverified and on hold while the earlier official BuildTools access/approval attempt is clarified. Its exact refusal record has not been established in this handoff. No retry or alternate access route is authorized here, and the harness does not use unofficial mirrors or infer Spigot success from Paper/Purpur.

Run `python -B test_fixture_safety.py`, `python -B test_fixture_privacy.py`, and `python -B test_server_statistics.py` for the no-server fixture safety, privacy, bytecode-proof and import-binding tests. The safety suite also checks valid custom pairs, invalid or ambiguous overrides, duplicate ports, changed prepared properties, literal loopback binding, mocked TCP/handshake consistency and stale hardcoded runner targets. Run `python -B verify_harness_binding.py /absolute/planned/runner.py` under its exact planned environment to reject stale private helper copies without importing or executing the runner. The source README and [PRIVACY.md](PRIVACY.md) describe the local official-JAR audit separately.

### Offline test-helper port guard checks

The modifier and IP helpers compile the shared test-only `fixture/safety/FixturePorts.java`. It mirrors the Python canonical port-pair contract and requires the reviewed harness's exact `guardian.fixture.variant` launch marker before checking the selected literal-loopback, offline endpoint. The IP helper additionally requires Paper. Old fixed-port helper JARs must be rebuilt before using custom ports. This changes no production GuardianAC source or JAR.

Run `bash test_fixture_ports_java.sh` with `JAVA_HOME` set to the reviewed JDK25 directory. This compiles only the dependency-free guard and its standalone regression program into a temporary directory, runs 86 checks, and removes that temporary output. It never loads Bukkit, GuardianAC or a server class, creates a fixture/world copy, opens a socket, or checks ports. Its Java check count is separate from the Python safety tests and production JUnit tests. The script rejects injected JVM-option environment variables.

### Administrator-reviewed permanent IP stage

The matrix now includes three separate commands:

- `gactest permanentipoffline`: uses the already joined loopback TCP `GacLifeFixture` Player and the untouched production controller. It requires an untrusted offline observation and verifies that the default offline environment cannot open permanent-IP review. It does not simulate successful Microsoft authentication.
- `gactest permanentip`: explicitly synthetic v4 identities, trusted loopback observations and strict v2 hard-proof snapshots enter real SQLite. A separate, package-private-constructor-injected controller supplies the synthetic native environment and exact proxy-session lookup. It uses the real Bukkit inventories/events and native ban lists; no production controller or native authentication result is modified. The source UUID case stays at its configured expiry and causes no IP enforcement before approval. Every saved evidence page, its SHA-256 binding and the separate shared-address acknowledgement precede a one-use final confirmation. The fixture checks successful distinct permanent IP-only creation, immutable linked proof, actor/time/reason audit, native ownership, replay/double-click rejection and privacy. Negative scenarios cover unprivileged viewers/unbanners, legacy/invalid/incomplete proof, untrusted identity, multiple observed accounts including an untrusted later arrival, another actor or replacement Player with the same UUID, close/navigation/cancel/quit, permission/policy/source changes and an actual 30-second expiry. All 54 inventory slots are checked against shift/hotbar/double-click/drop/offhand transfers, with drag protection and cross-controller ownership isolation. The dedicated stage timeout is 150 seconds; it does not hold the server thread during expiry or UI waits.
- `gactest permanentiprestart`: after a real server restart, checks durable permanent IP-only enforcement, source UUID expiry, linked proof and audit. It then invokes the normal IP-only unban confirmation and checks that the source UUID and both evidence payloads remain.

The report's `permanent_ip.synthetic_real_inventory_positive_reachable` and `permanent_ip.default_real_offline_client_refused` are intentionally separate findings. Synthetic trusted identity is not a native-online network authentication integration test. No actual account or non-loopback address is used. Deep serialized-SQL races and payload/hash-tamper cases belong to the accompanying unit suites, rather than adversarially editing the live server database.

Run the complete matrix only after building the exact production and fixture artifacts and explicitly authorizing server start, one server at a time:

```
python run_matrix.py paper-26.3 permanent-ip-NEW-RUN
python run_matrix.py purpur-26.3 permanent-ip-NEW-RUN
```

Use a fresh run name. Source inspection or helper compilation alone is not a runtime pass; inspect each exact-hash report and all individual assertions before reporting success.

The accompanying `nativelifecycle` stage checks revoked-before-callback automatic/reviewed completions, exact-owned inactive native orphan cleanup, surviving same-target cases, foreign or mismatched native markers and partial IP revocation. The final first-run command, `nativeseed`, freezes only the isolated native reconciler and seeds a durable-revocation/native-entry crash window. No further review/unban work runs before shutdown. After restart, `nativerestart` observes the ordinary startup sweep, without triggering repair itself, and checks exact-owned cleanup, survivor restoration and foreign/unknown-marker preservation. These use only synthetic loopback records and are bounded to 30/45-second stage timeouts.

### Settings behavior and advanced-resource GUI stage

New runs seed `limits.max-players: 37` into the disposable fixture configuration extracted from the exact pinned plugin JAR. This deliberately nondefault value lets `settingsruntime` verify that the engine and EventTelemetry use one validated startup capacity. It does not change the source defaults or baseline directories.

`SettingsRuntimeFixture` checks synthetic low-TPS configuration binding and the real periodic plugin pulse: turning automatic tuning off clears its old multiplier both immediately and on refresh. A separate Xray observer collects samples while actual Bukkit `BlockBreakEvent` objects are dispatched against a small temporary block arrangement in the isolated spawn chunk. It loads at most one isolated fixture chunk, keeps the 27-block arrangement wholly inside it, restores those blocks afterward, and unloads the chunk if the fixture loaded it. It verifies ON observation, master-OFF sink suppression and history/candidate cleanup, and fresh history after re-enabling. The temporary observer has its own unstarted packet adapter; production packet hooks are not replaced.

`AdvancedSettingsRuntimeFixture` traverses actual 54-slot menus with synthetic viewers/administrators. It checks all six resource options' labels, staged minimum/maximum/default values, bound clamping, explicit save and on-disk persistence, restart-pending versus live-budget labels, exact-session ownership, admin permission loss, close cancellation and stale drafts after unrelated changes or reload. It restores the original configuration before the later permanent-IP stages. The settings stage has a 60-second timeout. These added source scenarios require a fresh helper build and exact-artifact matrix run before being reported as passed.

After the main checks, `settingsseed` persists five changed restart-only allocations (41 tracked players, 257 MiB/91 days of evidence, and 33 MiB global/65 KiB per-player replay). It verifies that reload leaves the old live allocations and pending GUI labels intact. This runs before the final `nativeseed` command. On the second boot, `settingsrestart` verifies the actual engine, telemetry, storage and replay fields plus updated GUI active values. Thus restart-required labels are backed by a real allocation-change restart check, not just draft persistence.

### Corruption and offline-recovery boundary checks

`test_boundaries.py` requires a completed, passing Paper seed for the same exact production artifact. It creates a fresh isolated server, enables native Anti-Xray there, and then copies only the seed's storage into that disposable fixture. Corruption is applied only to that new copy. The seed database is hashed before and after to verify it stayed unchanged. The runner requires all seven named boundary checks and three normal server exits; a partial run cannot pass. Offline restoration uses the helper's `OfflineRecovery` entry point and a generated backup from the copied fixture storage. Original failed attempts are retained.
