# Bukkit event telemetry and evidence boundaries

This development build emits real server observations. The 61 rule predicates are not 61 independently validated cheat detectors. Core fixture tests establish predicate and evidence-policy behavior; they do not establish production false-positive rates or prove a particular client modification.

## Ownership and lifecycle

`EventTelemetry` is a Bukkit `Listener` and `AutoCloseable`. Register it, then call `start()` on the server thread. `start()` admits existing online Player identities; join and authoritative online snapshots admit later connections. `close()` cancels its periodic task and clears all admissions/history. Quit removes only the matching Player identity. Respawn, teleport, game-mode/world changes and administrative reset clear history while retaining the live admission. Late close/other events cannot recreate a departed connection or mutate a same-UUID replacement. Plugin shutdown cancels all plugin tasks. All Bukkit reads, collision queries, inventory observations and block resync writes are on the server thread. No event is cancelled, player teleported or velocity modified by this adapter.

The constructor is `EventTelemetry(JavaPlugin, Consumer<Sample>, LongSupplier tick, DoubleSupplier tps)`. The orchestrator supplies the tick/TPS clock, a fresh packet-ground bridge and a translated-client predicate. `context(Player)` returns an immutable main-thread context for packet publication. `sinceCloseMillis(Player)` is elapsed time since a known close; absence returns zero and is not proof of an old closed window.

Every emitted event sample includes actual player x/y/z/yaw/pitch and a world-UUID flag for bounded replay. Attack samples also include actual target x/y/z. No client fall-distance claim is invented: the vanilla protocol does not supply one.

## Physical context and movement

Movement is measured once per server tick, including stationary players. It is not a per-packet delta, and irregular snapshots/world changes/displacements of 16 blocks or more start a grace window instead of being evaluated as speed. Ground/support are derived from four downward block-collision rays at the player's feet. A clear six-block ray is a measured lower bound of six blocks of unsupported space. Unloaded neighboring chunks leave support unknown and the physics sample exempt; the adapter does not intentionally load them.

Per-tick measurements include horizontal and vertical displacement, previous vertical displacement, horizontal acceleration, gravity residual, airborne/gliding/water-surface duration, jump chains, tracked downward travel, block support, full-cube penetration, item-use duration, movement-speed attribute allowance and step-height allowance. Ground, previous-ground, landed, water, water-below, climbing, walls, glide and sneak context is supplied.

Movement/geometry heuristics are suppressed for non-survival modes, flight/riptide, vehicles, sleep/death, NPC metadata, arbitrary movement attributes/modifiers, missing gravity, potion effects, velocity and teleport grace, translated clients, low TPS/high ping, and unknown or unusual terrain. Partial blocks, moving-block mechanisms, fluids with special physics, ice/slime/honey, webs, ladders/vines/scaffolding and similar cases are deliberately conservative. Known speed, step and interaction-range attributes are incorporated. Unknown/custom movement namespaces, drag/friction/bounciness and nonstandard scale/jump/gravity are exempt.

The following use real packet ground claims only when the orchestrator supplies a fresh observation: `combat.ground-claim`, `movement.ground-spoof`, `movement.landing-claim`. They compare it with collision support. Landing-claim additionally requires tracked descent; it is not a claim that vanilla fall damage was suppressed. They share one evidence family.

Elytra checks require sustained unboosted flight. Observed firework interactions grant a generous boost grace. Velocity response only measures a bounded high-speed clear-air case, excludes nearby entities and collision corridors, and uses a conservative drag/opposing-input displacement bound; ordinary ground knockback is not scored.

## Combat

Only accepted direct `ENTITY_ATTACK` damage events are counted. Sweep/projectile events and cancelled damage events are excluded. These are server attack observations, not a complete record of client clicks or missed swings.

- Reach is eye-to-target-box distance with the server's entity interaction-range attribute and latency allowance.
- Hitbox evidence uses an expanded target box and the actual look ray.
- Occlusion requires all tested target-box points to be obstructed by solid occluding blocks before the target, with lag clearance. Unloaded paths leave occlusion unproven.
- Multi-target evidence counts distinct damaged entities in the same server tick; target-switch rate counts observed changes in attacked target over one second.
- Attack rate and regularity use bounded server event timing, requiring a full observation window and at least 60 intervals for regularity. They are susceptible to server/network batching and are review-only.
- Aim snap combines real sampled rotation and current target angular error. Aim lock requires 80 uninterrupted samples of a moving tracked target and records error variance. No unobservable sub-tick target-acquisition timestamp is invented.

## Blocks

Break start, abort, slot changes and accepted breaks are correlated by exact world/block/type/tool. `Block.getBreakSpeed(Player)` provides vanilla per-tick progress with tool/enchantment/effect/water context. Progress is accumulated only for known consecutive ticks; changed tool/block and missing starts invalidate the measurement. Additional progress allowance accounts for ordering and latency, avoiding an undercount that would falsely imply fast break. Instant-break observations are exempt.

Break/place counters are bounded distinct-block sets per tick and timestamp rings per second. Geometry uses the block unit box, interaction-range attributes and lag allowance. Bed obstruction requires actual occlusion. Tool-switch timing is observed held-slot change to damage-start delay with at least 30 recorded switches. Scaffold and tower evidence uses actual placement position, player pitch and successive underfoot placement heights/ticks.

Ghost-block recovery is visual only: after five repeated penetration observations or a cancelled block action, the adapter schedules a next-tick authoritative block refresh. It sends at most 27 existing loaded block states once per two seconds per player, after cancelled placement rollback has completed, and grants resync grace. It never cancels movement or rewrites world blocks.

## Inventory and totems

Accepted clicks/drags, drops, known top-container shift transfers and explicit armor-slot placements are measured. Timing histories are bounded and short/unknown histories do not populate the required statistical measurements. Unobservable auto-equip destinations are not inferred from arbitrary shift-click intent.

Totem timing starts only for a successful resurrection with a real totem hand. Refill is observed either by an accepted known hand-swap/slot-placement into an actually empty consumed hand, or by a subsequently verified empty-to-totem snapshot transition. Refills completed before the first snapshot with no corresponding known operation remain unknown; they are never recorded as zero milliseconds. Snapshot-only timing is tick-granular, while supported event operations use monotonic event timestamps.

`INVENTORY_TIMING` USE/INVENTORY rules ignore effects/velocity/special-terrain exemptions because these do not make inventory timestamps unobservable, and totems themselves grant potion effects. They still require explicit nonexempt context, no flight/vehicle/teleport/translation, acceptable TPS and ping. These rules are never automatic-ban evidence.

## Resource bounds and remaining validation

Player state is capped by `limits.max-players`; capacity exhaustion skips untracked players conservatively. Every timing/aim buffer is fixed-size, per-tick target/block sets have hard caps, dig tracking has a timeout, and quit/close frees the connection admission. History reset retains only the live bounded admission; `trackedPlayers()` counts admissions, including one whose history was reset. Snapshot reconciliation frees missed departures and retries previously capacity-limited online players. Synchronous collision/ray costs still need production profiling at the intended player count. Custom minigame mechanics should use bypass/exempt integration until tested.

Synthetic fixtures cover every catalog predicate with positive, legitimate-boundary, missing-data, exemption and wrong-event-kind cases. Additional tests exercise correlated families, hard-evidence thresholds and time gates, decay, expiration, duplicate/stale replay, disabled checks, bounded state, presets and totem-effect behavior. These are not substitutes for clean-client/adversarial-client play traces on each supported server/protocol combination.

Primary API references:
- https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/block/Block.html#getBreakSpeed(org.bukkit.entity.Player)
- https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/block/Block.html#rayTrace(org.bukkit.Location,org.bukkit.util.Vector,double,org.bukkit.FluidCollisionMode)
- https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/World.html
