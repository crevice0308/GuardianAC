# Bounded 64-client load and connection-limit fixture

This is source-only test support for the immutable GuardianAC development JAR. It uses actual loopback TCP logins and socket-delivered protocol 777 chunk/block-update bytes. It does not call the production detection engine, inject samples, raise anti-cheat budgets, disable limiters, change OS/network settings, use external servers or read live player data.

Run one fixture server at a time. Optional `GUARDIAN_PAPER_PORT` and `GUARDIAN_PURPUR_PORT` overrides must be supplied together as distinct canonical decimal integers from `1024` through `65535`. The server properties, every TCP target and handshake share the validated selection. Missing, ambiguous, duplicate or stale targets fail closed; no port probe is performed. The existing runtime harness restricts the server to offline literal `127.0.0.1` on the centrally selected Paper/Purpur port pair (defaults `26731`/`26732`), validates the exact artifact hash, disables RCON/query and caps Java heap at768MiB. The metrics helper must expose the public context-capture failure counter and session-registry tracked-player count. Every observed context-capture failure counter must remain zero. The only server property changes made by this runner are allowing64 fixture slots and disabling the fixture whitelist. The server's original connection throttle stays unchanged.

## Fixed phases and stopping conditions

1. Up to64 clients use distinct source addresses127.0.0.2 through127.0.0.65, all connecting to127.0.0.1. Each must reach actual play and an acknowledged server position within15seconds; the complete join phase has a120-second limit. Any failure is retained and ends the load run without increasing limits.
2. Hold all64 connected for30seconds of conservative stationary status/tick traffic at no more than20iterations per second. Record each client's accepted state, findings/BANs, packet drops, sampled TPS/RSS/heap and GC counts. The bounded smoke also requires each of its six traffic TPS snapshots to be at least18; this coarse condition does not establish p99 tick latency.
3. Independently decode every retained initial fixture ore chunk per viewer, require actual wire coverage and all ore positions masked, then request ordinary server teleports near the ore and require each viewer's actual ore block update. Check the authoritative world ore remains unchanged. Missing/truncated wire evidence fails.
4. Quit all clients, wait a bounded3seconds for draining, then require all observed per-player caches, packet queue and replay bytes to return to zero.
5. Start exactly40 TCP login attempts from127.0.0.1 without waiting between handshakes, observe up to15seconds, and distinguish native GuardianAC rate-limit disconnects from accepted and unexplained outcomes. Require wire rate denials to match GuardianAC's denied-join counter and zero unintended BANs/findings/evidence. A platform throttle or unexplained disconnect is a failed or incomplete result, not credited as GuardianAC protection.
6. Quit burst clients, check cleanup, wait11additional seconds for the20-token/2-per-second per-IP bucket to refill, and require one same-IP clean login to succeed. Check final cleanup, stop server, verify SQLite integrity/counts and exit status.

The burst is finite, local and deliberately small. This does not prove general denial-of-service resilience or public-server capacity.64 simultaneous stationary minimal clients are not a representative workload, and30seconds is not an endurance test. Microsoft authentication, rendered clients, gameplay diversity, plugin interactions and network translation are out of scope.

## Reproduction

Use JDK25, independently initialized official Paper26.3/Purpur26.3 baselines, and the exact immutable artifact. No server binaries, worlds, databases, private keys or raw server disassembly are distributed here. Read and accept https://www.minecraft.net/en-us/eula before setting the acceptance variable.

Set the environment required by `../runtime/README.md`:

- JAVA_HOME
- GUARDIAN_PROJECT_ROOT
- GUARDIAN_PLUGIN_SHA256
- GUARDIAN_PAPER_BASELINE and GUARDIAN_PURPUR_BASELINE
- GUARDIAN_RUNTIME_OUTPUT
- GUARDIAN_ACCEPT_MINECRAFT_EULA=true after explicit agreement

Also set:

- GUARDIAN_CLIENT_SUPPORT: adjacent client support directory, including its optional loopback source_host client argument
- GUARDIAN_RUNTIME_SUPPORT: adjacent runtime support directory, if not using the default
- GUARDIAN_CLIENT_FIXTURE_JAR: compile the adjacent client helper using its documented build script
- GUARDIAN_MODIFIER_FIXTURE_JAR: compile the adjacent modifier helper using its documented build script; only its read-only metrics command is used
- GUARDIAN_LOAD_LIMITS_OUTPUT: generated evidence directory outside the source package

Run the lightweight result-oracle checks with `python -B test_observations.py` and `python -B test_source_binding.py`. The latter mocks socket creation and opens no connection. Run the server scenario with `python -B run_load_limits.py paper-26.3 UNIQUE_RUN_NAME`, or explicitly select purpur-26.3. An optional third argument selects `load` or `burst` alone; omitted means both phases. Use this to retain a failed load run while independently testing the bounded burst, never to relabel the failed load as passed. Do not reuse run names. Do not overlap compilation or another server with a resource-measurement run.

A helper process failure, missing data, incomplete stage or nonzero server exit cannot pass. JSON evidence pins source/helper/server/production SHA-256 values, actual loopback source addresses, every ore frame's hash, measurements, SQLite counts and individual assertions. Raw synthetic ore frames are retained outside source. Per-client replay reports remain inside the disposable private fixture, not the source archive.

## Fixture correction retained in evidence

The first64-client run stopped after31.28seconds because the runner incorrectly required exactly one recursively found`.db` file. GuardianAC's scheduled backup correctly created another database under`bans/backups`. This was a fixture failure, not a passing test. The runner now selects the authoritative`bans/bans.db` path, rejects a missing/symlinked primary, and has regression tests proving backups cannot become the primary. Original failed evidence remains separate; reruns must use a fresh name and identical production/cap settings.
