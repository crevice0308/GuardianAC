# GuardianAC Ultimate: development design

Status: internal development, 1.0.0-dev.1. Not an official 1.0.0 release.

## Target and dependencies (2026-10-13)

Primary target: Minecraft Java 26.3, Java 25, Spigot/Paper/Purpur. Paper's official V3 API returns stable 26.3 builds; Spigot publishes 26.3 BuildTools refs; Purpur lists 26.3. Actual compatibility requires the server matrix, not just API compilation. PacketEvents 2.14.0 explicitly supports 26.3. The exercised Purpur 26.3 build 2648 labels its API channel experimental, so that test is not a stable-Purpur certification. The three projects may publish builds on different schedules; do not infer cross-platform compatibility from one successful boot.

- Spigot API: compile-only, GPL-compatible Bukkit/Spigot API; no server internals linkage.
- PacketEvents 2.14.0: GPL-3.0 library, embedded and relocated per upstream bundling guidance. No prerequisite plugin and no runtime download.
- Xerial SQLite JDBC 3.53.4.0: Apache-2.0 driver and embedded SQLite, packaged into the single JAR.
- JUnit Jupiter, JaCoCo, Gradle Shadow are build/test tools only.
- GPL-3.0-only original code. All bundled license notices accompany distribution.

Sources: https://fill.papermc.io/v3/projects/paper/versions/26.3/builds ; https://hub.spigotmc.org/versions/26.3.json ; https://api.purpurmc.org/v2/purpur ; https://github.com/retrooper/packetevents/releases/tag/v2.14.0 ; https://docs.packetevents.com/setup-when-bundling/a-beginners-guide-to-bundling/ ; https://docs.papermc.io/paper/anti-xray/ .

## Safety model

An observable anomaly is not proof of a specific client modification. Aim, automation, mining statistics, and most movement heuristics are evidence for staff review. Automatic sanctions require sustained independent high-confidence evidence, environmental safety gates, no staff bypass, successful durable evidence storage, and a verified ban transaction. Correlated checks share a family and cannot inflate independent confidence. Lag, teleports, velocities, effects, creative/spectator, nonvanilla movement, protocol translation, and world changes suppress applicable heuristics. STRICT increases sensitivity within these safeguards; it never disables them. There is no normal automatic kick punishment. Disconnects occur only to enforce an already-committed ban; malformed packets can be discarded and new abusive connections denied.

Numeric “confidence” is a policy-strength score assigned to a rule, not an empirically calibrated probability that a person cheated. Distinct evidence families reduce known double-counting; they do not prove statistical independence. No 99%/99.5% real-player accuracy rate is claimed. Formal accuracy requires representative labeled gameplay evaluation.

No server can reliably detect client-side Xray or aim assist in every case. Chunk obfuscation limits transmitted information but cannot revoke information already sent, defeat known seeds completely, or guarantee cave secrecy. Paper/Purpur native Anti-Xray is detected and never processed twice. A standalone Spigot chunk path must be verified separately before release.

## Components

1. Bukkit event adapter snapshots on the main thread; packet adapter processes bounded primitive immutable data on the network thread. No Bukkit world/entity access off-thread.
2. Pure detection engine with independent check IDs, typed event kind, finite measurements, bounded per-player state, per-check settings and recorded evidence. Replay rings have entry and byte limits.
3. Single-writer SQLite repository stores cases, sanctions, audit and evidence transactionally. IPs use keyed lookup plus authenticated encryption at rest; raw addresses never enter notifications or replay. Native ban ownership markers prevent removal of other plugins' bans.
4. 54-slot custom-holder GUI with view/admin permission separation, server-side action routing, click/drag/hotbar/shift-click protection and expiring actor-bound confirmation tokens.
5. Bounded Discord worker, disabled by default; admin manually supplies a supported Discord HTTPS webhook. Secrets are never echoed or logged. The corrected GuardianAC build removes its private embedded metrics reporter and requires no plugin telemetry service. Administrator-configured Discord notifications remain optional external communication; server/other-component game-service paths are separate. This is not a universal no-egress claim.
6. Local metrics, false-positive tuning that only relaxes heuristics, durable configuration replacement, connection and packet resource limits.

## Implementation sequence and release gate

Implement pure invariants and storage tests first, then adapters/GUI/security and chunk concealment. Run build/static checks, synthetic clean/adversarial traces, database/crypto/retention and stress tests. With explicit Minecraft EULA scope approved, test cold boot/restart/ban ownership/GUI and packets on isolated loopback Spigot, Paper, Purpur servers; no real player data. Audit the assembled single JAR, verify relocation and repeat-build checksums. Obfuscation is optional and must not replace runtime validation; the unobfuscated build is the default until obfuscated runtime equivalence is proven. A feature ledger reports implementation and verification independently. Do not publish GitHub/Release without user approval and do not label incomplete work 1.0.0.
