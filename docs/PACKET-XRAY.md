# Packet and Xray implementation notes / 通信・Xray制約

> Public package scope: detailed generated execution records are omitted. See the [aggregate verification summary](../TEST-RESULTS.md) for retained totals and limitations. Ordinary source and static software manifests remain.

This is an internal development implementation, not a validated general-purpose anti-cheat release. A named detector reports an observable pattern; it does not prove a particular client modification. Unit tests do not substitute for a real-client server matrix.

## Packet ingress

PacketEvents 2.14.0 is embedded and relocated. Its official release adds Minecraft 26.3 support: https://packetevents.com/blog/release-2-14-0/ . Bundling follows https://docs.packetevents.com/setup-when-bundling/a-beginners-guide-to-bundling/ . Actual APIs were checked against the CodeMC 2.14.0 API/source/Spigot artifacts and compiled with Spigot API 26.3 and Java 25.

- `PacketAdapter.load()` runs in plugin load; `init()` runs at enable; `close()` unregisters/terminates this relocated instance. Update checking is explicitly disabled. Corrected fb56fa5d8fcc removes its private embedded bStats initialization and runtime classes; the deprecated `bStats(false)` setting alone was ineffective in earlier artifacts. This is not a claim that all server network activity is absent. PacketEvents exception/termination kicks are explicitly disabled.
- Main-thread `PacketContext` snapshots contain only UUIDs, primitives and copied flags. Main-thread `publishContext` creates connection state; unauthenticated connection IDs cannot grow this map. Context older than 500ms suppresses evidence.
- Network callbacks never query Bukkit players, inventories, locations, blocks or scheduler. Accepted telemetry enters a fixed 8192-sample queue. The server-thread `drain` enforces the configured engine-work budget. Full ingress drops samples and increments a metric.
- Position, rotation, held-slot and digging-face bounds are inspected. Plugin message size is checked before decoding attacker-controlled payload arrays. Oversize inventory-click and general frames are discarded before expensive wrapping.
- Fixed-memory rolling counters measure packet/swing/interaction rates and monotonic-time movement windows. Flood resource limits cancel packets above 2000 total, 300 swings or 400 interactions per approximately one second. General decoded frames over 1MiB, plugin-message frames over 32768 bytes and click-window frames over 65536 bytes are defensively dropped. These budgets can affect unusual custom clients/plugins; they are not evidence by themselves of cheating.
- Telemetry is emitted at most 20 ordinary plus 10 invalid observations per second per player. Decode exceptions generate defensive counters, not invented hard-invariant proof. There is no ordinary kick sanction.
- Different client/server protocol versions are marked translated and exempt from packet evidence, including hard-rule sanctions. This is deliberately conservative for an observed protocol mismatch; it is not ViaVersion/Geyser/proxy compatibility certification. A proxy presenting native protocol 777 is not automatically identifiable as Bedrock from that version value. Last valid packet-ground snapshots are timestamped and are not authoritative world collision data.
- Vanilla movement packets do not supply the Bukkit fall-distance field. No synthetic claimed-fall measurement is invented.

## Outbound concealment

The implementation performs real per-recipient outgoing chunk palette replacement. It only runs for matching native protocol, known world height, a fresh server-authored viewer position and an eligible world.

1. Effective per-world Paper/Purpur `anticheat.antiXray.enabled` is read from the runtime world configuration on the main thread. Enabled native Anti-Xray is left in control. If Paper detection cannot be resolved safely, the mode is UNKNOWN and the independent rewrite is skipped. No Paper configuration is changed. Paper behavior/reference: https://docs.papermc.io/paper/anti-xray/ and https://github.com/PaperMC/Paper/blob/main/paper-server/src/main/java/io/papermc/paper/configuration/WorldConfiguration.java .
2. The network handler copies block-state IDs from the outbound column. It never reads a live world or a Bukkit chunk asynchronously. Only sections changed by the mask are deep-copied; biome palettes, modern heightmaps, block entities and light data are preserved. Block/fluid counts use PacketEvents 26.3-aware setters.
3. Enclosed ores/ancient debris below the configured Y limit are replaced by stone/deepslate/netherrack/end stone. A conservative opaque-material allowlist prevents covering ores exposed through transparent/non-full neighboring blocks. Unknown column borders remain visible.
4. Cave masking is opt-in, defaults OFF, and only covers ordinary/cave air below Y=0 outside a minimum 24-block reveal radius. It is experimental. It does not hide heightmap/light/entity/structure side channels and cannot promise underground secrecy. Large air volumes may exceed the concealment budget and are then not masked.
5. Position-only restoration ledgers are bounded per chunk (4096 default), player (32768), globally (524288), and by cached column count. The default rewrite budget is 128 chunk packets/second globally. Admission failure leaves the entire outgoing column unchanged, not partially masked. Counters expose every skipped/failed column.
6. Ledger positions are reserved before encoding but become revealable only after PacketEvents' send-completion hook. A generation token rejects stale completion callbacks after replacement/unload/world changes. Shutdown stops new rewrite commits before restoration.
7. Main-thread player positions trigger nearby reveals. Only already-loaded chunks are read, and current authoritative block data is used, avoiding stale cached blocks. Block breaks/places, physics, explosions and piston events enter a bounded coalesced neighbor-reveal queue. Real outgoing single/multi block updates always supersede cached concealment positions. Unloads/disconnects/world changes release state.

Protection limitations are material: already-transmitted information cannot be revoked, known seeds and inference can bypass concealment, exposed ores and unknown boundaries are visible, later real block updates may reveal hidden blocks, translated clients and resource-budget skips receive unmodified chunks. This is fail-open availability protection, not a blanket claim of complete Xray resistance. Cave masking and restoration behavior still require real-client visual/interaction and native coexistence tests before release.

## Mining evidence

`MiningStatistics` keeps at most 4096 observations per player and resets on world change or 30-minute idle. It computes rare-block ratio, locally grouped rare veins, hidden-vein observations and recent-turn association. Before a server-confirmed block break exposes neighboring rare ore, the service may record that it was fully enclosed; unknown exposure is never asserted as hidden. The temporary exposure-candidate map expires after two minutes and holds at most 512 positions/player. Mining features retain environmental exemption context and can only produce heuristic review evidence, never an independent automatic-ban assertion. Player-built ore, vein geometry, routes, seed knowledge, world generation and other players' excavation can bias these statistics.

## Verification boundary

The historical `11ffe3b21f6c…` build had 82 packet/Xray JUnit tests (25 packet, including 9 session regressions, and 57 Xray), zero skipped. Three use actual PacketEvents 2.14.0 section palettes: deep-copy isolation, every block/biome preservation, and Minecraft 26.3 section-byte round trip including fluid counts. Unit tests also cover bounds, timing, snapshot freshness, bounded emission, translation suppression, masks, capacity failure, cache concurrency/generations/send ordering and mining statistics.

Separate historical assembled-JAR server and actual TCP fixtures verified full outgoing columns, wire-level ore concealment/reveal for 16 and 64 viewers, a bounded opted-in cave, explicit over-capacity fail-open behavior, protocol eligibility and native Anti-Xray coexistence. The later d2737dec224c suites repeated these bounded observations, but that artifact has an embedded metrics defect and must not be deployed. Corrected fb56fa5d8fcc has a completed supervised 194-assertion modifier/30-minute soak and 328/328-per-server Paper/Purpur matrices, including bounded adapter, chunk serialization and native coexistence fixtures. Its separate actual TCP core suite now passes 79/79, including same-UUID overlap, 16 viewers and bounded cave masking; the 64-viewer/admission suite passes 33/33. See [aggregate verification summary](../TEST-RESULTS.md). The aggregate scope and fixed plugin hash are in `TEST-RESULTS.md`; detailed recordings are not bundled. These results do not establish graphical cave rendering, generic proxy-plugin compatibility, coverage under arbitrary dense-terrain load, or a human false-positive rate. API compilation and pure section tests are not substitutes for those distinct validation scopes.

## Admission fairness and saturation

Heavy chunk rewriting has a global bounded budget. A read-only palette prefilter
can prove an eligible-height column has no ore (or opted-in cave-air) candidates;
such columns do not consume that budget. It examines at most 2,048 local palette
entries per column. Global/unknown palettes, malformed metadata or the inspection
cap conservatively enter the budgeted path rather than claiming absence.

The GUI distinguishes `noTargetChunks` from `budgetLimitedChunks`. The former is
not lost concealment coverage; the latter can be. Candidate-bearing columns are
still admitted first-come under the global cap, so dense terrain or a large join
burst can leave some eligible information visible. There is no per-player full
coverage guarantee, unbounded retry queue or unsafe cancellation without resend.
Measure actual received chunk contents, not just aggregate concealment counts.

Configuration publication and outgoing packet commits share a generation gate:
a stale callback cannot commit after disable/reconfigure/stop, even if the same
settings object is later re-enabled. Already committed pending-send restoration
records are retained. After send activation, restoration is bounded to 1,024
blocks per player and 8,192 globally per tick; stalled networking or low TPS
prevents a wall-clock completion guarantee. Identifiable unmodified replacement
columns invalidate only their superseded ledger entries. Truncated headers do
not cause guessed-column invalidation.

## Connection identity and delayed callbacks

Packet state binds an actual main-thread Player channel to its PacketEvents User.
The UUID-to-channel convenience cache is not used to authorize packet state: a
second same-account connection can overwrite that cache while configuring. Old
User receive/send/disconnect callbacks cannot affect the active replacement.
Queued samples carry their exact state owner as well as time/world guards. Netty
disconnect only retires its matching state; main-thread reconciliation clears
reveal state before replacement, under the per-session lock. Closed channels
remain ineligible. `/gac status` includes context-capture failures; capture
exceptions emit one privacy-preserving warning, never a fallback UUID admission.

Deterministic real-server adapter fixtures cover old-queue rejection, stale
receive/forget/disconnect preservation and current-disconnect cleanup. The actual
two-socket CONFIGURATION/PLAY overlap passed 16/16 assertions on historical
`11ffe3b21f6c…` and was repeated in the d2737dec224c core TCP suite, including
unchanged active state/ledger after the old socket closes and a later true-ore
reveal with no rebuilding chunk resend. The corrected fb56fa5d8fcc supervised
core suite independently repeats all 16 overlap checks with a normal owned
server stop; these are included within its 79 checks. See the
[aggregate verification summary](../TEST-RESULTS.md).
Historical detailed recordings are excluded from this public package. `TEST-RESULTS.md` preserves aggregate results and unresolved limitations.
