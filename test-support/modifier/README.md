# GuardianAC modifier and cleanup fixture

This extension uses real loopback TCP protocol 777 / Java 26.3 clients from the independently developed client suite. It never creates `Sample` data or invokes the detection engine directly. Its separate Bukkit fixture changes only the isolated test world's blocks, ordinary player effects, teleports, a minecart passenger, and a server-issued velocity, then reports read-only production state on the server thread.

## Scope and stopping condition

1. Exercise finite clean/modifier scenarios, recording the actual server position, player state, packet context, recent production replay, findings/VL, cancellation counters, ping and TPS.
2. Hold actual keepalive replies for 1.2 seconds over 46 seconds; hold ten ordinary tick packets for 500 ms and release them. These are bounded controlled loopback delays, not a general network simulator.
3. Spend 80 ms in a test-only scheduled task for 60 ticks (about 4.8 seconds of sleep) to measure real low-TPS context and recovery. No production clocks or TPS fields are changed.
4. Run a full 30-minute two-client cleanup soak: one persistent moving client and a second account that joins every minute and quits about 12 seconds later. Vary benign actions and terrain every 45 seconds. Stop at 30 minutes, disconnect everyone, verify per-player caches/queues return to zero, stop the server, and read SQLite integrity/counts. A script/runtime failure stops and preserves evidence; it is not a passing soak.

The window is long enough to exercise repeated replay saturation, 30 distinct join/quit lifecycles and periodic database maintenance; it is not a long-term production, capacity, or leak-proof certification. Heap snapshots and GC counts show bounded observed usage, not retained-object attribution. The finite observed interval and JVM cap must be reported with results.

## Evidence boundaries

- Sprint/sneak use real protocol input and sprint commands, independently checked in server state.
- Flat walking on stone, water-bottom movement and modified surfaces uses conservative finite accepted positions below ordinary legal movement speeds. These are controlled protocol fixtures, not a complete vanilla client physics reconstruction.
- Jump uses initial vertical speed 0.42, jump boost I 0.52, gravity 0.08 and vertical drag 0.98. Slow falling uses descending gravity 0.01; levitation I uses `(v + (0.05 - v) * 0.2) * 0.98`. The recurrence was checked against the canonical 26.3 `LivingEntity` implementation in the development environment; raw server disassembly is not included in this source package.
- Ladder uses a bounded upward climb and checks `isClimbing` plus context. Ice, slime, honey and slab fixtures establish special-terrain handling; bouncing/sliding/step-edge dynamics are not covered.
- Knockback uses the real server `setVelocity(0.3, 0.42, 0)` event and airborne recurrence; it does not cover every collision response.
- Teleport and world change use actual server teleports and client acknowledgements.
- The minecart test checks an actual stationary passenger and its vehicle exemption; steering/vehicle prediction is excluded.
- Elytra and riptide dynamics are explicitly untested because authoritative item-use/equipment and complete flight prediction are not implemented. No invented trajectory is labeled vanilla.
- No Microsoft authentication, ViaVersion translation, public players, Discord endpoint, external service interaction, or Minecraft redistribution is involved.

## Portable reproduction

The source package contains no Minecraft or server implementation binaries and downloads none. Use already-initialized official Paper/Purpur 26.3 baselines you are authorized to run, JDK 25, and the exact immutable GuardianAC artifact you want to test. Read https://www.minecraft.net/en-us/eula and set the acceptance variable only if you agree. Run one server at a time. Both server and client use the literal `127.0.0.1` and the same validated port pair from `../runtime/fixture_safety.py`. Defaults are `26731` for Paper and `26732` for Purpur only when neither override is set. Set `GUARDIAN_PAPER_PORT` and `GUARDIAN_PURPUR_PORT` together to distinct canonical decimal integers from `1024` through `65535`; ambiguous, duplicate and unselected ports fail closed. The test helper validates the same pair and requires the exact `guardian.fixture.variant` marker supplied by the reviewed harness, the selected variant port, and offline mode. Rebuild the helper after changing its gate source. Port selection does not check availability or authorize a start. If an earlier process has unverified termination, keep it untouched and obtain a separate plan approval for a fresh independent fixture and distinct ports.

Set the variables required by `../runtime/README.md`, including `JAVA_HOME`, `GUARDIAN_PROJECT_ROOT`, `GUARDIAN_PAPER_BASELINE`, `GUARDIAN_PURPUR_BASELINE`, `GUARDIAN_RUNTIME_OUTPUT`, `GUARDIAN_PLUGIN_SHA256`, and `GUARDIAN_ACCEPT_MINECRAFT_EULA=true` after agreement. The pinned project root must contain `build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar` with the independently checked hash.

Also set:

- `GUARDIAN_CLIENT_SUPPORT`: path to the independently verified real TCP client support directory containing `protocol_client.py` and `schema/packet-ids.json`
- `GUARDIAN_MODIFIER_BUILD_OUTPUT`: an output directory outside the source package
- `GUARDIAN_MODIFIER_FIXTURE_JAR`: that output directory's `ModifierFixture.jar`, after compiling
- `GUARDIAN_MODIFIER_OUTPUT`: a directory for generated modifier evidence, outside the source package
- `GUARDIAN_BUKKIT_API_JAR`: optional explicit Spigot/Bukkit 26.3 API JAR; otherwise the baseline's library JARs are used

```
bash test-support/modifier/fixture/build.sh
python test-support/modifier/test_delay_fifo.py
python test-support/modifier/run_modifiers.py paper-26.3 UNIQUE_RUN_NAME 1800
```

`0` instead of `1800` is an explicit diagnostic-only run and cannot establish a successful soak. Fresh run names are required. The report includes artifact/server/fixture hashes, requested positions, corrective teleport lists, real server Y ranges, actual context, VL/findings, per-minute resource/SQLite measurements, and final shutdown/integrity results. No raw canonical-server disassembly or server JAR is packaged.

## Fixture regression guards

The first exploratory fixture placed a ladder client too close to its backing collision face. The corrected fixture uses z=16.5 and retains requested positions separately from incoming server corrections. A server correction can no longer be mistaken for acceptance simply because the client accepted its new coordinates.

A latency-disable transition also initially let a new immediate keepalive acknowledgement overtake an older delayed acknowledgement. The FIFO guard and unit test preserve order while delay is turned off. These are fixture corrections, not GuardianAC production fixes.

## Quit regression and controlled stop

Every run first completes five short real join/quit cycles before entering its long soak. The read-only fixture records actual `QUIT` and `INVENTORY_CLOSE` events, online state and `EventTelemetry.trackedPlayers()` in `lifecycle.jsonl`. It does not invoke those production listeners or fabricate events. All tracked player subsystems must return to the actual online count after each quit; a failed preflight prevents the long soak.

For a short lifecycle-only regression, set `GUARDIAN_MODIFIER_CLEANUP_ONLY=true` and select `0` seconds. This skips the movement/network sequence, runs the five real quit cycles, closes every client, verifies zero player state and shuts down through the normal server stop command. It cannot count as a 30-minute pass. Unset that variable for the integrated full run.

To explicitly cancel an active fixture, write the reason into a file named `STOP` in that run's modifier-evidence directory. The next client tick raises a controlled stop, preserves the reason/partial evidence, closes clients, records final cleanup, and sends the normal server stop command. Do not use a terminal/process-group interrupt if normal server shutdown evidence is required. A cancelled or failed-gate run is never reported as a successful full soak.
