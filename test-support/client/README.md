# Real loopback protocol client tests

An independent Python-standard-library TCP client for Java 26.3/protocol 777. It completes real login/configuration/play, acknowledges actual teleports, sends bounded game packets, and independently decodes received ore chunks and block updates. It is not a rendered Mojang client and does not inject Bukkit players or anti-cheat samples.

## Requirements and configuration

Use JDK25 and the adjacent `../runtime` harness. Initialize official Paper/Purpur26.3 baseline folders yourself; Minecraft server binaries and EULA acceptance are not distributed here. These TCP clients connect only to the literal `127.0.0.1` on the centrally selected fixture ports (defaults `26731`/`26732`), use offline synthetic accounts, keep Discord disabled, and never connect to real game servers or players. Server-side game-service traffic has separate limits documented in `../runtime/PRIVACY.md`.

Set the environment variables documented by `../runtime/README.md`, especially:

- JAVA_HOME
- GUARDIAN_PROJECT_ROOT: immutable artifact project, with the exact JAR at build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar
- GUARDIAN_PLUGIN_SHA256: exact verified JAR hash
- GUARDIAN_PAPER_BASELINE and GUARDIAN_PURPUR_BASELINE
- GUARDIAN_RUNTIME_OUTPUT: server fixture/log output directory
- GUARDIAN_PAPER_PORT and GUARDIAN_PURPUR_PORT: optional paired overrides; both must be canonical decimal integers from 1024 through 65535 and must differ. The TCP target and handshake use the same validated selection; stale unselected targets fail before any socket is opened.
- GUARDIAN_ACCEPT_MINECRAFT_EULA=true only after reading and agreeing to https://www.minecraft.net/en-us/eula

Additionally set:

- GUARDIAN_CLIENT_OUTPUT: assertion/evidence output directory
- GUARDIAN_CLIENT_BUILD_OUTPUT: helper compilation output directory
- GUARDIAN_BUKKIT_API_JAR when the API is not available among baseline libraries
- GUARDIAN_RUNTIME_SUPPORT if the runtime helper is not at the adjacent `../runtime` path

Run `bash fixture/build.sh`, then set GUARDIAN_CLIENT_FIXTURE_JAR to its printed output path. Use a fresh run name to preserve previous evidence and avoid prior intentionally banned fixture identities.

Commands, one at a time:

1. `python run_suite.py paper-26.3 RUN_NAME`
2. `python verify_persisted_evidence.py RUN_NAME paper-26.3`
3. `python run_suite.py purpur-26.3 RUN_NAME`
4. `python verify_persisted_evidence.py RUN_NAME purpur-26.3`
5. `python run_load_smoke.py paper-26.3 LOAD_RUN_NAME`
6. Optional bounded cave wire test: `python run_cave_wire.py CAVE_RUN_NAME`
7. Same-UUID CONFIGURATION/PLAY overlap regression: `python run_configuration_overlap.py paper-26.3 OVERLAP_RUN_NAME`

The main suite covers clean standing/walking/jumping/actions, isolated malformed cancellation, repeated independent hard-evidence BAN, durable evidence, restart denial, clean rejoin, and ore mask/reveal without changing the world ore. The bounded load run uses16 clients for20seconds and records every fixture ore chunk frame per viewer, not merely aggregate rewrite counts. It also checks all16 near-reveal updates and world ore preservation.

The main suite restarts only after all 17 required first-phase assertions pass, no error is recorded, and the first server stops normally. A saved fixture BAN alone cannot trigger a second launch after a failed or incomplete phase. A successful process exit requires all 19 intended assertions and both normal stops; a written failed or partial report exits nonzero. Run `python -B test_suite_gates.py` for the no-server sequencing regressions.

## Protocol data

`schema/packet-ids.json` contains protocol registration facts for exact26.3. `extract_protocol_ids.py /path/to/server.jar /path/to/javap` can regenerate it from an independently obtained canonical server. javap outputs, Minecraft binaries, encryption keys, and databases are excluded from this source package. Codec provenance and limitations are in provenance.json.

For standalone reuse, add both this directory and the reviewed adjacent runtime directory to PYTHONPATH; the client imports its port/loopback gate from `fixture_safety`. The shipped runners arrange these paths, including the modifier extension's GUARDIAN_CLIENT_SUPPORT setting. Use `fixture_safety.fixture_port(variant)` to select the client target, and preserve the same port environment used to prepare and validate the server. Client APIs: Client(port,name,source_host=None,hold_configuration=False), wait_configuration(), wait_join(), pump(seconds), send(packet_name,bytes), tick(position=None,on_ground=True), report(), close(). chunk_decode provides decode_chunk, at, and block_update.

The overlap test holds one real socket after LOGIN_SUCCESS without acknowledging FINISH_CONFIGURATION, joins a second socket into PLAY on the same offline UUID, closes the first, and verifies the active player retains exact-protocol eligibility, packet-state identity and its reveal ledger. It also decodes actual far ore bytes and the near authoritative update. The fixture only observes state and real disconnect events; it does not inject packet samples or simulate disconnections.

The optional source_host argument accepts only an IPv4 loopback address, for example 127.0.0.2; it never changes the loopback-only destination. Its default preserves the original binding. Alternate source bindings require their own live validation.

The cave test enables hide-caves through normal config and reload, fills one below-zero fixture column with stone around a 27-block cavity, independently checks far masking and a near AIR update, and explicitly records the adjacent over-capacity column remaining fail-open. It does not establish graphical rendering or generic large-cave concealment.

This is scoped validation, not a guarantee for every vanilla mechanic, translator/modded client,100–1000-player capacity, or generic denial-of-service resilience. See each exact artifact's generated reports; never apply older passing evidence to functionally changed bytes.
