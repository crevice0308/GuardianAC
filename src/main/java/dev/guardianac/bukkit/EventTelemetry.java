/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import dev.guardianac.bukkit.telemetry.BoundedHistory;
import dev.guardianac.bukkit.telemetry.SessionStateStore;
import dev.guardianac.core.Sample;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Real, main-thread Bukkit telemetry. Heuristics are observations, not client-mod identification.
 * Movement is sampled once per server tick, never once per move packet. Unknown context is exempt.
 * No event is cancelled, no velocity is changed, and no player is teleported by this adapter.
 */
public final class EventTelemetry implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Consumer<Sample> samples;
    private final LongSupplier ticks;
    private final DoubleSupplier tps;
    private final SessionStateStore<UUID, Player, State> players;
    private BukkitTask task;
    private Predicate<UUID> translated = id -> false;
    private BiFunction<UUID, Long, Optional<Boolean>> packetGround = (id, now) -> Optional.empty();

    public EventTelemetry(JavaPlugin plugin, Consumer<Sample> samples, LongSupplier tick, DoubleSupplier tps) {
        this(plugin,samples,tick,tps,Math.max(16,Math.min(10000,plugin.getConfig().getInt("limits.max-players",2000))));
    }
    /** Production passes the same validated startup snapshot as the engine, avoiding Bukkit's stale config cache. */
    public EventTelemetry(JavaPlugin plugin, Consumer<Sample> samples, LongSupplier tick, DoubleSupplier tps,int maxPlayers) {
        this.plugin = Objects.requireNonNull(plugin); this.samples = Objects.requireNonNull(samples);
        this.ticks = Objects.requireNonNull(tick); this.tps = Objects.requireNonNull(tps);
        if(maxPlayers<16||maxPlayers>10000)throw new IllegalArgumentException("maxPlayers");
        players = new SessionStateStore<>(maxPlayers);
    }
    public void start() {
        mainThread();
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::snapshot, 1, 1);
        reconcileOnline();
    }
    /** Bridge must return empty for stale/unknown/translated packet observations. */
    public void packetGround(BiFunction<UUID, Long, Optional<Boolean>> supplier) { mainThread(); packetGround = Objects.requireNonNull(supplier); }
    public void translatedPlayers(Predicate<UUID> predicate) { mainThread(); translated = Objects.requireNonNull(predicate); }
    public void reset(UUID id) { mainThread(); players.reset(id); }
    public void forget(Player player) { mainThread(); players.forget(player.getUniqueId(), player); }
    private void reset(Player player) { mainThread(); players.reset(player.getUniqueId(), player); }
    public int trackedPlayers() { mainThread(); return players.size(); }
    @Override public void close() { mainThread(); if (task != null) task.cancel(); task = null; players.clear(); }
    private static void mainThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Bukkit telemetry requires the main thread"); }
    private State state(Player p) {
        mainThread();
        if (task == null) return null;
        return players.state(p.getUniqueId(), p, () -> {
            State created = new State(); created.teleportUntil = ticks.getAsLong() + 40; return created;
        });
    }
    private void reconcileOnline() {
        Map<UUID, Player> online = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) online.put(player.getUniqueId(), player);
        players.reconcile(online);
    }
    public boolean containerOpen(Player player) {
        mainThread(); InventoryType type = player.getOpenInventory().getType();
        return type != InventoryType.CRAFTING && type != InventoryType.CREATIVE && type != InventoryType.PLAYER;
    }
    public long sinceCloseMillis(Player player) {
        mainThread(); State s = players.peek(player.getUniqueId(), player);
        return s == null || s.closedAt == 0 ? 0 : Math.max(0, now() - s.closedAt);
    }
    /** Immutable main-thread context can safely be published to the packet adapter. */
    public Set<String> context(Player p) {
        mainThread(); State s = state(p); return Set.copyOf(flags(p, s, false));
    }
    private Set<String> flags(Player p, State s, boolean observedVelocity) {
        Set<String> f = new HashSet<>(); long tick = ticks.getAsLong();
        if (s == null || p.isDead() || p.getGameMode() != GameMode.SURVIVAL || p.isSleeping()
            || p.hasMetadata("NPC") || !p.getWorld().isChunkLoaded(p.getLocation().getBlockX() >> 4, p.getLocation().getBlockZ() >> 4)) f.add("exempt");
        if (p.hasPermission("guardianac.bypass")) f.add("bypass");
        if (p.isInsideVehicle()) f.add("vehicle");
        if (p.getAllowFlight() || p.isFlying() || p.isRiptiding()) f.add("flight");
        if (!p.getActivePotionEffects().isEmpty() || !p.hasGravity() || unsupportedAttributes(p)) f.add("effects");
        if (translated.test(p.getUniqueId())) f.add("protocol-translated");
        if (p.isGliding()) f.add("gliding");
        if (p.isSwimming() || p.isInWater()) f.add("water");
        if (p.isClimbing()) f.add("climbable");
        if (p.isSneaking()) f.add("sneaking");
        if (containerOpen(p)) f.add("container-open");
        if (p.getItemInUse() != null) f.add("using-item");
        if (s != null) {
            if (tick < s.teleportUntil) f.add("teleport-grace");
            if (!observedVelocity && tick < s.velocityUntil) f.add("velocity-grace");
            if (tick < s.fireworkUntil) f.add("firework-grace");
            if (tick < s.terrainUntil || s.terrainUnknown) f.add("special-terrain");
            if (s.ground) f.add("ground");
            if (s.waterBelow) f.add("water-below");
            if (s.wallNear) f.add("wall-near");
        }
        return f;
    }
    private Sample.Builder base(Player p, State s, Sample.Kind kind) { return base(p, s, kind, false); }
    private Sample.Builder base(Player p, State s, Sample.Kind kind, boolean observedVelocity) {
        Sample.Builder b = Sample.builder(p.getUniqueId(), kind, now(), ticks.getAsLong())
            .value("tps", tps.getAsDouble()).value("ping", Math.max(0, p.getPing()));
        Location at = p.getLocation(); b.flag("world:" + p.getWorld().getUID());
        if (finite(at)) b.value("x", at.getX()).value("y", at.getY()).value("z", at.getZ())
            .value("yaw", at.getYaw()).value("pitch", at.getPitch());
        else b.flag("exempt");
        flags(p, s, observedVelocity).forEach(b::flag); return b;
    }
    private static long now() { return System.currentTimeMillis(); }
    private static double millis() { return System.nanoTime() / 1_000_000.0; }
    private static boolean custom(AttributeInstance attr) {
        return attr != null && attr.getModifiers().stream().anyMatch(m -> !m.getKey().getNamespace().equals("minecraft"));
    }
    private static double attribute(Player p, Attribute attr, double fallback) {
        AttributeInstance value = p.getAttribute(attr);
        return value == null || !Double.isFinite(value.getValue()) ? fallback : value.getValue();
    }
    private static boolean unsupportedAttributes(Player p) {
        Attribute[] sensitive = {Attribute.MOVEMENT_SPEED, Attribute.GRAVITY, Attribute.JUMP_STRENGTH,
            Attribute.STEP_HEIGHT, Attribute.SCALE, Attribute.MOVEMENT_EFFICIENCY, Attribute.SNEAKING_SPEED,
            Attribute.WATER_MOVEMENT_EFFICIENCY, Attribute.FLYING_SPEED, Attribute.KNOCKBACK_RESISTANCE,
            Attribute.ENTITY_INTERACTION_RANGE, Attribute.BLOCK_INTERACTION_RANGE, Attribute.AIR_DRAG_MODIFIER,
            Attribute.FRICTION_MODIFIER, Attribute.BOUNCINESS};
        for (Attribute key : sensitive) {
            AttributeInstance a = p.getAttribute(key);
            if (custom(a)) return true;
            if (a != null && key != Attribute.MOVEMENT_SPEED && key != Attribute.STEP_HEIGHT
                && key != Attribute.ENTITY_INTERACTION_RANGE && key != Attribute.BLOCK_INTERACTION_RANGE
                && Math.abs(a.getValue() - a.getDefaultValue()) > .000001) return true;
        }
        return Math.abs(p.getWalkSpeed() - .2) > .00001;
    }
    private void snapshot() {
        mainThread(); if (task == null) return;
        reconcileOnline();
        long tick = ticks.getAsLong(); long time = now();
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = state(p); if (s == null) continue;
            Location at = p.getLocation();
            if (!finite(at)) { s.last = null; s.teleportUntil = tick + 40; continue; }
            boolean previousGround = s.ground; boolean contiguous = s.last != null && tick == s.lastTick + 1
                && s.last.getWorld().equals(at.getWorld()) && s.last.distanceSquared(at) < 256;
            inspectTerrain(p, s, tick);
            if (contiguous) {
                double dy = at.getY() - s.last.getY(), horizontal = Math.hypot(at.getX() - s.last.getX(), at.getZ() - s.last.getZ());
                s.rotationDelta = Math.hypot(wrapDegrees(at.getYaw() - s.last.getYaw()), at.getPitch() - s.last.getPitch());
                s.airTicks = s.ground ? 0 : Math.min(10000, s.airTicks + 1);
                s.glideTicks = p.isGliding() ? Math.min(10000, s.glideTicks + 1) : 0;
                s.waterTicks = s.waterBelow && !p.isInWater() && Math.abs(dy) < .003 ? Math.min(10000, s.waterTicks + 1) : 0;
                if (previousGround && !s.ground && dy > .15) {
                    s.jumpChain = tick - s.lastJumpTick < 30 ? Math.min(100, s.jumpChain + 1) : 1; s.lastJumpTick = tick;
                } else if (tick - s.lastJumpTick > 40) s.jumpChain = 0;
                if (dy < 0 && !s.ground) s.trackedFall += -dy;
                Sample.Builder b = base(p, s, Sample.Kind.MOVE).flag("previous-ground", previousGround)
                    .flag("landed", s.ground && !previousGround).value("dy", dy).value("previous-dy", s.dy)
                    .value("horizontal", horizontal).value("horizontal-acceleration", horizontal - s.horizontal)
                    .value("air-ticks", s.airTicks).value("glide-ticks", s.glideTicks).value("water-surface-ticks", s.waterTicks)
                    .value("jump-chain", s.jumpChain).value("tracked-fall", s.trackedFall).value("using-ticks", p.getItemInUseTicks())
                    .value("speed-limit", .36 * Math.max(1, attribute(p, Attribute.MOVEMENT_SPEED, .1) / .1))
                    .value("step-limit", attribute(p, Attribute.STEP_HEIGHT, .6)).value("solid-penetration", s.penetration);
                if (Double.isFinite(s.support)) b.value("support-distance", s.support);
                if (s.previousAir && !previousGround && !s.ground) b.value("gravity-residual", Math.abs(dy - (s.dy - .08) * .98));
                packetGround.apply(p.getUniqueId(), time).ifPresent(onGround -> b.flag("packet-ground", onGround));
                samples.accept(b.build());
                if (s.penetration > .4 && horizontal > .2) { if (++s.penetrationRepeats >= 5) resync(p, s, at.getBlock(), false); }
                else s.penetrationRepeats = Math.max(0, s.penetrationRepeats - 1);
                if (s.ground || p.isInWater() || p.isGliding() || p.isClimbing()) s.trackedFall = 0;
                s.previousAir = !s.ground; s.dy = dy; s.horizontal = horizontal;
            } else {
                s.teleportUntil = Math.max(s.teleportUntil, tick + 20);
                s.airTicks = 0; s.dy = 0; s.horizontal = 0; s.trackedFall = 0; s.previousAir = false;
                s.rotationDelta = Double.NaN; s.aim.clear(); s.pendingVelocity = null;
            }
            s.last = at.clone(); s.lastTick = tick;
            sampleAim(p, s, time);
            advanceDig(p, s, tick);
            observeTotem(p, s, time);
            observeVelocity(p, s, tick);
        }
    }
    private void inspectTerrain(Player p, State s, long tick) {
        Location at = p.getLocation(); BoundingBox body = p.getBoundingBox(); World world = p.getWorld();
        s.ground = false; s.wallNear = false; s.waterBelow = false; s.terrainUnknown = false; s.penetration = 0; s.support = Double.NaN;
        if (body.getWidthX() > 2 || body.getWidthZ() > 2 || body.getHeight() > 3) { s.terrainUnknown = true; return; }
        int minX = floor(body.getMinX() - .3), maxX = floor(body.getMaxX() + .3);
        int minZ = floor(body.getMinZ() - .3), maxZ = floor(body.getMaxZ() + .3);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) { s.terrainUnknown = true; continue; }
            for (int y = Math.max(world.getMinHeight(), floor(body.getMinY()) - 1); y <= Math.min(world.getMaxHeight() - 1, floor(body.getMaxY()) + 1); y++) {
                Block block = world.getBlockAt(x, y, z); Material m = block.getType();
                if (special(m) || block.getBlockData() instanceof Waterlogged water && water.isWaterlogged()) s.terrainUntil = tick + 20;
                if (m == Material.WATER && y < body.getMinY() && y + 1 >= body.getMinY() - .25) s.waterBelow = true;
                if (!block.isPassable()) {
                    BoundingBox box = block.getBoundingBox();
                    if (box.getMaxY() > body.getMinY() + .2 && box.getMinY() < body.getMaxY() && box.overlaps(body.clone().expand(.2, 0, .2))) s.wallNear = true;
                    // Partial blocks are explicitly exempt; their approximate Bukkit box is not proof of penetration.
                    if (m.isOccluding() && fullCube(box, x, y, z) && box.overlaps(body)) {
                        double depth = Math.min(Math.min(body.getMaxX() - box.getMinX(), box.getMaxX() - body.getMinX()),
                            Math.min(body.getMaxZ() - box.getMinZ(), box.getMaxZ() - body.getMinZ()));
                        if (box.getMaxY() - body.getMinY() > .12 && body.getMaxY() - box.getMinY() > .12) s.penetration = Math.max(s.penetration, depth);
                    } else if (!fullCube(box, x, y, z)) s.terrainUntil = tick + 10;
                }
            }
        }
        // Boats/minecarts and collidable entities can support or push a player without block support.
        // Their exact collision response is not modeled, so nearby entity contact grants grace.
        int entities = 0;
        for (Entity nearby : world.getNearbyEntities(body.clone().expand(.35, .6, .35))) {
            if (nearby == p) continue;
            if (++entities > 64) { s.terrainUnknown = true; break; }
            if (nearby instanceof Vehicle || nearby instanceof LivingEntity || nearby instanceof FallingBlock)
                s.terrainUntil = Math.max(s.terrainUntil, tick + 10);
        }
        if (s.terrainUnknown) return;
        double support = 6;
        double[] xs = {body.getMinX() + .02, body.getMaxX() - .02}; double[] zs = {body.getMinZ() + .02, body.getMaxZ() - .02};
        for (double x : xs) for (double z : zs) {
            Location from = new Location(world, x, body.getMinY() + .08, z);
            RayTraceResult hit = world.rayTraceBlocks(from, new Vector(0, -1, 0), 6.08, FluidCollisionMode.NEVER, true);
            if (hit != null) support = Math.min(support, Math.max(0, body.getMinY() - hit.getHitPosition().getY()));
        }
        s.support = support; s.ground = support <= .08;
    }
    private static boolean special(Material m) {
        String n = m.name();
        return n.contains("ICE") || n.contains("SLIME") || n.contains("HONEY") || n.contains("PISTON")
            || n.contains("SOUL_SAND") || n.contains("SOUL_SOIL") || n.contains("COBWEB") || n.contains("BUBBLE")
            || n.contains("POWDER_SNOW") || n.contains("SCAFFOLD") || n.contains("LADDER") || n.contains("VINE")
            || n.contains("TRAPDOOR") || n.contains("STAIRS") || n.contains("SLAB") || n.contains("BED")
            || n.contains("BOUNCE") || n.contains("SPRING") || m == Material.LAVA;
    }
    private static boolean fullCube(BoundingBox b, int x, int y, int z) {
        return Math.abs(b.getMinX() - x) < .0001 && Math.abs(b.getMinY() - y) < .0001 && Math.abs(b.getMinZ() - z) < .0001
            && Math.abs(b.getMaxX() - x - 1) < .0001 && Math.abs(b.getMaxY() - y - 1) < .0001 && Math.abs(b.getMaxZ() - z - 1) < .0001;
    }
    private static int floor(double value) { return (int) Math.floor(value); }
    private static boolean finite(Location l) { return Double.isFinite(l.getX()) && Double.isFinite(l.getY()) && Double.isFinite(l.getZ()) && Float.isFinite(l.getYaw()) && Float.isFinite(l.getPitch()); }
    private static double wrapDegrees(double value) { return (value % 360 + 540) % 360 - 180; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        mainThread();
        if (!(event.getDamager() instanceof Player p) || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        Entity target = event.getEntity(); State s = state(p); if (s == null || !target.getWorld().equals(p.getWorld())) return;
        long time = now(), tick = ticks.getAsLong(); double mono = millis();
        if (s.attackTick != tick) { s.attackTick = tick; s.targets.clear(); }
        if (s.targets.size() < 64) s.targets.add(target.getUniqueId());
        s.attacks.add(time, 1);
        if (s.lastAttackMono > 0 && mono > s.lastAttackMono && mono - s.lastAttackMono < 1000) s.attackIntervals.add(time, mono - s.lastAttackMono);
        s.lastAttackMono = mono;
        if (s.aimTarget != null && !s.aimTarget.equals(target.getUniqueId())) s.targetSwitches.add(time, 1);
        if (!target.getUniqueId().equals(s.aimTarget)) { s.aimTarget = target.getUniqueId(); s.aim.clear(); s.targetLast = null; }
        s.lastCombatTime = time;
        Location eye = p.getEyeLocation(); BoundingBox targetBox = target.getBoundingBox();
        double lagMargin = .15 + Math.min(200, Math.max(0, p.getPing())) * .004;
        double reach = distance(eye.toVector(), targetBox);
        Location targetAt = target.getLocation();
        Sample.Builder b = base(p, s, Sample.Kind.ATTACK).value("reach", reach)
            .value("target-x", targetAt.getX()).value("target-y", targetAt.getY()).value("target-z", targetAt.getZ())
            .value("reach-limit", attribute(p, Attribute.ENTITY_INTERACTION_RANGE, 3) + lagMargin)
            .value("targets-tick", s.targets.size()).value("attacks-second", s.attacks.countSince(time - 1000))
            .value("attack-window-ms", Math.min(1000, s.attacks.spanMillis()))
            .value("target-switches-second", s.targetSwitches.countSince(time - 1000))
            .value("air-ticks", s.airTicks).value("tracked-fall", s.trackedFall);
        if (Double.isFinite(s.support)) b.value("support-distance", s.support);
        packetGround.apply(p.getUniqueId(), time).ifPresent(onGround -> b.flag("packet-ground", onGround));
        if (reach <= 16 && loadedRay(p.getWorld(), eye.toVector(), targetBox.getCenter())) {
            BoundingBox expanded = targetBox.clone().expand(lagMargin + .1);
            boolean misses = expanded.rayTrace(eye.toVector(), eye.getDirection(), reach + targetBox.getWidthX() + targetBox.getHeight() + 3) == null;
            b.value("ray-miss", misses ? 1 : 0);
            // Occlusion requires all visible box points to be blocked, not just its center.
            b.flag("occluded", occluded(p.getWorld(), eye, targetBox, lagMargin));
            b.value("aim-error", aimError(eye, targetBox.getCenter()));
        }
        if (Double.isFinite(s.rotationDelta)) b.value("rotation-delta", s.rotationDelta);
        BoundedHistory.Summary intervals = s.attackIntervals.summarySince(time - 10000);
        b.value("interval-samples", intervals.count());
        if (intervals.count() >= 60) b.value("interval-mean", intervals.mean()).value("interval-stddev", intervals.stddev());
        BoundedHistory.Summary aim = s.aim.summarySince(time - 10000);
        b.value("aim-samples", aim.count());
        if (aim.count() >= 80 && Double.isFinite(s.targetSpeed)) b.value("aim-error-stddev", aim.stddev()).value("target-speed", s.targetSpeed);
        samples.accept(b.build());
    }
    private void sampleAim(Player p, State s, long time) {
        if (s.aimTarget == null || time - s.lastCombatTime > 5000) { s.aim.clear(); s.targetLast = null; return; }
        Entity target = Bukkit.getEntity(s.aimTarget);
        if (target == null || !target.isValid() || !target.getWorld().equals(p.getWorld()) || target.getLocation().distanceSquared(p.getLocation()) > 256) {
            s.aim.clear(); s.targetLast = null; return;
        }
        Location at = target.getLocation();
        s.targetSpeed = s.targetLast == null ? Double.NaN : at.distance(s.targetLast);
        s.targetLast = at.clone();
        if (s.targetSpeed > .15) s.aim.add(time, aimError(p.getEyeLocation(), target.getBoundingBox().getCenter()));
        else s.aim.clear();
    }
    private static double aimError(Location eye, Vector point) {
        Vector direction = point.clone().subtract(eye.toVector());
        if (direction.lengthSquared() < .000001) return 0;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, eye.getDirection().normalize().dot(direction.normalize())))));
    }
    private static double distance(Vector point, BoundingBox box) {
        double dx = Math.max(box.getMinX() - point.getX(), Math.max(0, point.getX() - box.getMaxX()));
        double dy = Math.max(box.getMinY() - point.getY(), Math.max(0, point.getY() - box.getMaxY()));
        double dz = Math.max(box.getMinZ() - point.getZ(), Math.max(0, point.getZ() - box.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    private static boolean loadedRay(World world, Vector from, Vector to) {
        double length = from.distance(to); if (!Double.isFinite(length) || length > 24) return false;
        int steps = Math.max(1, (int)Math.ceil(length));
        for (int i = 0; i <= steps; i++) {
            double t = (double)i / steps;
            if (!world.isChunkLoaded(floor(from.getX() + (to.getX() - from.getX()) * t) >> 4,
                floor(from.getZ() + (to.getZ() - from.getZ()) * t) >> 4)) return false;
        }
        return true;
    }
    private static boolean occluded(World world, Location eye, BoundingBox target, double margin) {
        double[] xs = {target.getMinX() + .02, target.getMaxX() - .02};
        double[] ys = {target.getMinY() + .02, target.getCenterY(), target.getMaxY() - .02};
        double[] zs = {target.getMinZ() + .02, target.getMaxZ() - .02};
        for (double x : xs) for (double y : ys) for (double z : zs) {
            Vector toward = new Vector(x, y, z).subtract(eye.toVector()); double length = toward.length();
            if (length <= margin + .1 || !loadedRay(world, eye.toVector(), new Vector(x, y, z))) return false;
            RayTraceResult hit = world.rayTraceBlocks(eye, toward.normalize(), length, FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null || !hit.getHitBlock().getType().isOccluding()
                || eye.toVector().distance(hit.getHitPosition()) + margin + .15 >= length) return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damageBlock(BlockDamageEvent event) {
        mainThread(); Player p = event.getPlayer(); State s = state(p); if (s == null) return;
        Block block = event.getBlock(); long tick = ticks.getAsLong(); double mono = millis();
        // Repeated damage notifications for the same active dig do not reset legitimate progress.
        if (s.dig == null || !s.dig.matches(block)) s.dig = new Dig(block, p.getInventory().getItemInMainHand().clone(), tick);
        s.dig.instant |= event.getInstaBreak();
        updateDigSpeed(p, s.dig, block);
        double delay = mono - s.toolChangedMono;
        if (s.toolChangedMono > 0 && delay >= 0 && delay <= 1000 && isTool(p.getInventory().getItemInMainHand())) {
            s.toolSwitches.add(now(), delay); s.dig.toolSwitchMs = delay;
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void abortBlock(BlockDamageAbortEvent event) { State s = players.peek(event.getPlayer().getUniqueId(), event.getPlayer()); if (s != null) s.dig = null; }
    private void advanceDig(Player p, State s, long tick) {
        Dig d = s.dig; if (d == null) return;
        if (tick - d.startTick > 1200 || !p.getWorld().getUID().equals(d.world)
            || !p.getWorld().isChunkLoaded(d.x >> 4, d.z >> 4) || !p.getInventory().getItemInMainHand().isSimilar(d.tool)) { s.dig = null; return; }
        Block block = p.getWorld().getBlockAt(d.x, d.y, d.z);
        if (!d.matches(block) || p.getEyeLocation().distanceSquared(block.getLocation().add(.5, .5, .5)) > 144) { s.dig = null; return; }
        updateDigSpeed(p, d, block);
        long elapsed = tick - d.lastTick;
        if (elapsed == 1) d.progress += d.lastSpeed; else if (elapsed > 1) d.valid = false;
        d.lastTick = tick;
    }
    private static void updateDigSpeed(Player p, Dig d, Block block) {
        double speed = block.getBreakSpeed(p);
        if (!Double.isFinite(speed) || speed < 0) { d.valid = false; return; }
        d.lastSpeed = speed; d.maxSpeed = Math.max(d.maxSpeed, speed); if (speed >= 1) d.instant = true;
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void breakBlock(BlockBreakEvent event) {
        mainThread(); Player p = event.getPlayer(); State s = state(p); if (s == null) return;
        if (event.isCancelled()) { resync(p, s, event.getBlock(), true); s.dig = null; return; }
        Block block = event.getBlock(); long tick = ticks.getAsLong(), time = now();
        if (s.breakTick != tick) { s.breakTick = tick; s.breaks.clear(); }
        if (s.breaks.size() < 128) s.breaks.add(new BlockKey(block));
        Sample.Builder b = base(p, s, Sample.Kind.BREAK).value("breaks-tick", s.breaks.size())
            .flag("bed", block.getType().name().endsWith("_BED"));
        blockGeometry(p, block, b);
        Dig d = s.dig;
        if (d != null && d.matches(block) && d.valid && p.getInventory().getItemInMainHand().isSimilar(d.tool)) {
            updateDigSpeed(p, d, block);
            // Extra ticks deliberately overestimate progress to allow event ordering and network jitter.
            double progress = d.progress + d.maxSpeed * (3 + Math.ceil(Math.max(0, p.getPing()) / 50.0));
            if (d.valid && Double.isFinite(progress)) b.value("break-progress", progress);
            b.flag("instant-break", d.instant);
            if (Double.isFinite(d.toolSwitchMs)) b.value("tool-switch-ms", d.toolSwitchMs)
                .value("tool-switch-count", s.toolSwitches.countSince(time - 60000));
        }
        s.dig = null; samples.accept(b.build());
    }
    private static void blockGeometry(Player p, Block block, Sample.Builder b) {
        Location eye = p.getEyeLocation(); BoundingBox box = new BoundingBox(block.getX(), block.getY(), block.getZ(), block.getX() + 1, block.getY() + 1, block.getZ() + 1);
        double lag = .2 + Math.min(200, Math.max(0, p.getPing())) * .004;
        b.value("block-reach", distance(eye.toVector(), box)).value("block-reach-limit", attribute(p, Attribute.BLOCK_INTERACTION_RANGE, 4.5) + lag);
        if (loadedRay(p.getWorld(), eye.toVector(), box.getCenter())) b.flag("occluded", occluded(p.getWorld(), eye, box, lag));
    }
    private static boolean isTool(ItemStack item) {
        String name = item.getType().name(); return name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.equals("SHEARS");
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void heldItem(PlayerItemHeldEvent event) { State s = state(event.getPlayer()); if (s != null) { s.toolChangedMono = millis(); s.dig = null; } }

    @EventHandler(priority = EventPriority.MONITOR)
    public void placeBlock(BlockPlaceEvent event) {
        mainThread(); Player p = event.getPlayer(); State s = state(p); if (s == null) return;
        if (event.isCancelled() || !event.canBuild()) { resync(p, s, event.getBlockPlaced(), true); return; }
        long time = now(), tick = ticks.getAsLong(); Block block = event.getBlockPlaced();
        if (s.placeTick != tick) { s.placeTick = tick; s.places.clear(); }
        if (s.places.size() < 128) s.places.add(new BlockKey(block)); s.placeTimes.add(time, 1);
        Location pAt = p.getLocation(); boolean underfoot = Math.abs(pAt.getX() - block.getX() - .5) < .85
            && Math.abs(pAt.getZ() - block.getZ() - .5) < .85 && pAt.getY() >= block.getY() + .8 && pAt.getY() <= block.getY() + 2;
        Sample.Builder b = base(p, s, Sample.Kind.PLACE).value("places-second", s.placeTimes.countSince(time - 1000))
            .value("places-tick", s.places.size()).value("pitch", pAt.getPitch()).flag("underfoot", underfoot);
        blockGeometry(p, block, b);
        if (underfoot) {
            long elapsed = tick - s.lastTowerTick;
            if (elapsed > 0 && elapsed <= 30 && s.lastTower != null && s.lastTower.getWorld().equals(pAt.getWorld())
                && Math.hypot(pAt.getX() - s.lastTower.getX(), pAt.getZ() - s.lastTower.getZ()) < 1.5) {
                s.towerChain = Math.min(100, s.towerChain + 1);
                b.value("tower-rise-per-tick", (pAt.getY() - s.lastTower.getY()) / elapsed);
            } else s.towerChain = 1;
            s.lastTowerTick = tick; s.lastTower = pAt.clone(); b.value("tower-chain", s.towerChain);
        } else s.towerChain = 0;
        samples.accept(b.build());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void openInventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player p) { State s = state(p); if (s != null) { s.transferIntervals.clear(); s.transfers.clear(); s.lastTransferMono = 0; } }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void closeInventory(InventoryCloseEvent event) {
        mainThread();
        // Servers may emit this after PlayerQuitEvent; closing cannot allocate new history.
        if (event.getPlayer() instanceof Player p) { State s = players.peek(p.getUniqueId(), p); if (s != null) { s.closedAt = now(); s.transferIntervals.clear(); s.transfers.clear(); s.lastTransferMono = 0; } }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void inventoryClick(InventoryClickEvent event) {
        mainThread(); if (!(event.getWhoClicked() instanceof Player p)) return;
        State s = state(p); if (s == null) return; long time = now(); double mono = millis(); s.inventoryClicks.add(time, 1);
        Sample.Builder b = base(p, s, Sample.Kind.INVENTORY).value("inventory-clicks-second", s.inventoryClicks.countSince(time - 1000))
            .value("drops-second", s.drops.countSince(time - 1000));
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && event.getClickedInventory() == event.getView().getTopInventory() && containerOpen(p)
            && event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()) {
            s.transfers.add(time, 1);
            if (s.lastTransferMono > 0 && mono - s.lastTransferMono < 2000) s.transferIntervals.add(time, mono - s.lastTransferMono);
            else s.transferIntervals.clear();
            s.lastTransferMono = mono;
            BoundedHistory.Summary transfer = s.transferIntervals.summarySince(time - 60000);
            b.value("transfer-count", transfer.count());
            if (transfer.count() >= 30) b.value("transfer-mean-ms", transfer.mean());
        }
        boolean armorPlacement = event.getSlotType() == InventoryType.SlotType.ARMOR
            && (event.getAction() == InventoryAction.PLACE_ALL || event.getAction() == InventoryAction.PLACE_ONE
                || event.getAction() == InventoryAction.SWAP_WITH_CURSOR || event.getAction() == InventoryAction.HOTBAR_SWAP)
            && (event.getAction() == InventoryAction.HOTBAR_SWAP || event.getCursor() != null && !event.getCursor().getType().isAir());
        if (armorPlacement) {
            b.flag("armor-slot");
            if (s.lastArmorMono > 0 && mono - s.lastArmorMono < 2000) s.armorIntervals.add(time, mono - s.lastArmorMono);
            else s.armorIntervals.clear();
            b.value("armor-equip-count", s.armorIntervals.countSince(time - 60000));
            if (s.lastArmorMono > 0) b.value("armor-interval-ms", mono - s.lastArmorMono);
            s.lastArmorMono = mono;
        }
        samples.accept(b.build());
        observeTotemClick(p, s, event);
        // Ambiguous shift/drag operations are verified only by later snapshots.
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void inventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return; State s = state(p); if (s == null) return;
        long time = now(); s.inventoryClicks.add(time, 1);
        samples.accept(base(p, s, Sample.Kind.INVENTORY).value("inventory-clicks-second", s.inventoryClicks.countSince(time - 1000)).build());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void dropItem(PlayerDropItemEvent event) {
        State s = state(event.getPlayer()); if (s == null) return; long time = now(); s.drops.add(time, 1);
        samples.accept(base(event.getPlayer(), s, Sample.Kind.INVENTORY).value("drops-second", s.drops.countSince(time - 1000)).build());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void resurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player p) || event.getHand() == null) return;
        ItemStack used = event.getHand() == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (used.getType() != Material.TOTEM_OF_UNDYING) return;
        State s = state(p); if (s == null) return;
        s.totemMono = millis(); s.totemHand = event.getHand(); s.totemEmptyObserved = false;
    }
    private void observeTotem(Player p, State s, long time) {
        if (s.totemMono <= 0) return; double delay = millis() - s.totemMono;
        if (delay > 5000 || delay < 0) { s.totemMono = 0; return; }
        ItemStack hand = s.totemHand == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (hand.getType() != Material.TOTEM_OF_UNDYING) { s.totemEmptyObserved = true; return; }
        if (!s.totemEmptyObserved) return; // Refill before the first snapshot is unobservable, not zero ms.
        recordTotemRefill(p, s, time, delay);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swapHands(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer(); State s = state(p); if (s == null || s.totemMono <= 0) return;
        ItemStack before = s.totemHand == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        ItemStack after = s.totemHand == EquipmentSlot.OFF_HAND ? event.getOffHandItem() : event.getMainHandItem();
        if (before.getType().isAir() && after != null && after.getType() == Material.TOTEM_OF_UNDYING)
            recordTotemRefill(p, s, now(), millis() - s.totemMono);
    }
    private void observeTotemClick(Player p, State s, InventoryClickEvent event) {
        if (s.totemMono <= 0 || event.getClickedInventory() != p.getInventory()) return;
        int slot = s.totemHand == EquipmentSlot.OFF_HAND ? 40 : p.getInventory().getHeldItemSlot();
        if (event.getSlot() != slot || event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()) return;
        ItemStack incoming = null;
        if (event.getAction() == InventoryAction.PLACE_ALL || event.getAction() == InventoryAction.PLACE_ONE
            || event.getAction() == InventoryAction.SWAP_WITH_CURSOR) incoming = event.getCursor();
        else if (event.getAction() == InventoryAction.HOTBAR_SWAP && event.getHotbarButton() >= 0 && event.getHotbarButton() <= 8)
            incoming = p.getInventory().getItem(event.getHotbarButton());
        if (incoming != null && incoming.getType() == Material.TOTEM_OF_UNDYING)
            recordTotemRefill(p, s, now(), millis() - s.totemMono);
    }
    private void recordTotemRefill(Player p, State s, long time, double delay) {
        if (delay < 0 || delay > 5000 || !Double.isFinite(delay)) return;
        s.totemRefills.add(time, delay);
        samples.accept(base(p, s, Sample.Kind.USE).value("totem-refill-ms", delay)
            .value("totem-count", s.totemRefills.countSince(time - 600000)).build());
        s.totemMono = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void velocity(PlayerVelocityEvent event) {
        mainThread(); Player p = event.getPlayer(); State s = state(p); if (s == null) return;
        long tick = ticks.getAsLong(); Vector velocity = event.getVelocity();
        s.velocityUntil = tick + 40; s.pendingVelocity = null;
        double horizontal = Math.hypot(velocity.getX(), velocity.getZ());
        if (horizontal < 1.1 || horizontal > 4 || !Double.isFinite(horizontal) || !Double.isFinite(velocity.getY()) || Math.abs(velocity.getY()) > 2
            || s.ground || s.terrainUnknown || tick < s.terrainUntil || tick < s.teleportUntil || unsupportedAttributes(p)
            || p.isGliding() || p.isInWater() || p.isInsideVehicle() || p.isFlying()
            || !p.getWorld().getNearbyEntities(p.getBoundingBox().clone().expand(2), e -> e != p).isEmpty()) return;
        Vector direction = new Vector(velocity.getX(), 0, velocity.getZ()).normalize();
        if (!clearCorridor(p, direction, horizontal * 3)) return;
        s.pendingVelocity = new PendingVelocity(p.getLocation(), direction, horizontal, tick);
    }
    private void observeVelocity(Player p, State s, long tick) {
        PendingVelocity v = s.pendingVelocity; if (v == null) return;
        long elapsed = tick - v.tick;
        if (elapsed > 4 || elapsed < 0 || !v.start.getWorld().equals(p.getWorld()) || s.ground || s.terrainUnknown
            || tick < s.terrainUntil || tick < s.teleportUntil || p.isGliding() || p.isInWater()
            || !clearCorridor(p, v.direction, 2)) { s.pendingVelocity = null; return; }
        if (elapsed < 4) return;
        double expected = 0, speed = v.speed;
        // Conservative lower bound: drag plus opposing ordinary air input, for three complete ticks.
        for (int i = 0; i < 3; i++) { speed = Math.max(0, speed * .91 - .15); expected += speed; }
        double observed = Math.max(0, p.getLocation().toVector().subtract(v.start.toVector()).dot(v.direction));
        samples.accept(base(p, s, Sample.Kind.VELOCITY, true).flag("clear-knockback")
            .value("expected-displacement", expected).value("observed-displacement", observed).build());
        s.pendingVelocity = null;
    }
    private static boolean clearCorridor(Player p, Vector direction, double distance) {
        if (!Double.isFinite(distance) || distance < 0 || distance > 12) return false;
        Location feet = p.getLocation();
        for (double side : new double[]{-.4, .4}) for (double height : new double[]{.1, 1, 1.9}) {
            Location start = feet.clone().add(-direction.getZ() * side, height, direction.getX() * side);
            Vector end = start.toVector().add(direction.clone().multiply(distance));
            if (!loadedRay(p.getWorld(), start.toVector(), end)) return false;
            if (p.getWorld().rayTraceBlocks(start, direction, distance, FluidCollisionMode.ALWAYS, false) != null) return false;
        }
        return true;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        State s = state(event.getPlayer()); if (s == null) return;
        if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
            && event.getItem() != null && event.getItem().getType() == Material.FIREWORK_ROCKET && event.getPlayer().isGliding())
            s.fireworkUntil = ticks.getAsLong() + 100;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { reset(event.getPlayer()); state(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event) { reset(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void changedWorld(PlayerChangedWorldEvent event) { reset(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        mainThread(); if (task == null) return;
        Player player = event.getPlayer(); players.admit(player.getUniqueId(), player); reset(player); state(player);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { forget(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void gameMode(PlayerGameModeChangeEvent event) { reset(event.getPlayer()); }

    /** Visual block resync only, capped at 27 blocks once per two seconds. No movement punishment. */
    private void resync(Player p, State s, Block center, boolean cancelled) {
        mainThread(); long time = now();
        if (time - s.lastResync < 2000 || s.resyncScheduled || !cancelled && s.penetrationRepeats < 5) return;
        if (!p.getWorld().equals(center.getWorld()) || p.getLocation().distanceSquared(center.getLocation()) > 100) return;
        s.lastResync = time; s.penetrationRepeats = 0; s.resyncScheduled = true;
        s.teleportUntil = Math.max(s.teleportUntil, ticks.getAsLong() + 10);
        UUID playerId = p.getUniqueId(); BlockKey key = new BlockKey(center);
        // A cancelled placement is reverted after its event finishes. Read authoritative data next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            s.resyncScheduled = false;
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || players.peek(playerId, online) != s || !online.getWorld().getUID().equals(key.world)) return;
            World world = online.getWorld(); Location centerAt = new Location(world, key.x, key.y, key.z);
            if (online.getLocation().distanceSquared(centerAt) > 100) return;
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                int bx = key.x + x, by = key.y + y, bz = key.z + z;
                if (by < world.getMinHeight() || by >= world.getMaxHeight() || !world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                Block block = world.getBlockAt(bx, by, bz); online.sendBlockChange(block.getLocation(), block.getBlockData());
            }
        });
    }
    private record BlockKey(UUID world, int x, int y, int z) {
        BlockKey(Block b) { this(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()); }
    }
    private static final class Dig {
        final UUID world; final int x, y, z; final Material type; final ItemStack tool; final long startTick;
        long lastTick; double progress, maxSpeed, lastSpeed, toolSwitchMs = Double.NaN; boolean instant, valid = true;
        Dig(Block b, ItemStack tool, long tick) {
            world = b.getWorld().getUID(); x = b.getX(); y = b.getY(); z = b.getZ(); type = b.getType(); this.tool = tool; startTick = tick; lastTick = tick;
        }
        boolean matches(Block b) { return b.getX() == x && b.getY() == y && b.getZ() == z && b.getWorld().getUID().equals(world) && b.getType() == type; }
    }
    private record PendingVelocity(Location start, Vector direction, double speed, long tick) {}
    private static final class State {
        Location last, lastTower, targetLast; long lastTick, teleportUntil, velocityUntil, fireworkUntil, terrainUntil;
        long attackTick = -1, breakTick = -1, placeTick = -1, lastJumpTick, lastTowerTick, closedAt, lastCombatTime, lastResync;
        boolean ground, previousAir, waterBelow, wallNear, terrainUnknown = true, totemEmptyObserved, resyncScheduled;
        int airTicks, glideTicks, waterTicks, jumpChain, towerChain, penetrationRepeats;
        double support = Double.NaN, penetration, dy, horizontal, trackedFall, rotationDelta = Double.NaN, targetSpeed = Double.NaN;
        double lastAttackMono, toolChangedMono, lastTransferMono, lastArmorMono, totemMono;
        UUID aimTarget; EquipmentSlot totemHand; Dig dig; PendingVelocity pendingVelocity;
        final Set<UUID> targets = new HashSet<>(); final Set<BlockKey> breaks = new HashSet<>(), places = new HashSet<>();
        final BoundedHistory attacks = new BoundedHistory(256), attackIntervals = new BoundedHistory(256), aim = new BoundedHistory(160),
            targetSwitches = new BoundedHistory(128), toolSwitches = new BoundedHistory(128), placeTimes = new BoundedHistory(128),
            inventoryClicks = new BoundedHistory(256), transfers = new BoundedHistory(128), transferIntervals = new BoundedHistory(128),
            armorIntervals = new BoundedHistory(128), drops = new BoundedHistory(128), totemRefills = new BoundedHistory(64);
    }
}
