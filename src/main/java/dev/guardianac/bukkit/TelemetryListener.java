package dev.guardianac.bukkit;

import dev.guardianac.GuardianPlugin;
import dev.guardianac.detection.CombatSample;
import dev.guardianac.detection.MovementSample;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** All Bukkit world/entity reads happen on the server thread. Never loads chunks for a check. */
public final class TelemetryListener implements Listener {
    private record Frame(long tick, UUID world, BoundingBox box) {}
    private static final class State {
        Location last;
        long graceUntil;
        long movementGraceUntil;
        final ArrayDeque<Frame> history = new ArrayDeque<>();
    }
    private record Surface(boolean support, boolean special) {}
    private final GuardianPlugin plugin;
    private final Map<UUID, State> players = new HashMap<>();

    public TelemetryListener(GuardianPlugin plugin) { this.plugin = plugin; }

    public void tick() {
        long tick = plugin.tick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            State s = players.computeIfAbsent(player.getUniqueId(), id -> {
                State state = new State();
                state.graceUntil = tick + plugin.settings().safety().joinGrace();
                return state;
            });
            Location now = player.getLocation();
            boolean sameWorld = s.last != null && s.last.getWorld().equals(now.getWorld());
            double horizontal = sameWorld ? Math.hypot(now.getX() - s.last.getX(), now.getZ() - s.last.getZ()) : 0;
            double vertical = sameWorld ? now.getY() - s.last.getY() : 0;
            Surface surface = surface(player);
            boolean entitySupport = !surface.support() && player.getNearbyEntities(.8, .8, .8).stream()
                    .anyMatch(entity -> entity instanceof Boat || entity instanceof Minecart || entity instanceof Shulker);
            boolean exempt = !sameWorld || exempt(player, s) || tick < s.movementGraceUntil || surface.special() || entitySupport;
            double multiplier = Math.max(1, player.getWalkSpeed() / .2);
            var attribute = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
            // The base envelope already includes sprint-jumps; do not grant sprint twice.
            if (attribute != null) multiplier *= Math.max(1, attribute.getValue() / (player.isSprinting() ? .13 : .1));
            boolean verticalPotion = player.hasPotionEffect(PotionEffectType.LEVITATION)
                    || player.hasPotionEffect(PotionEffectType.SLOW_FALLING) || player.hasPotionEffect(PotionEffectType.JUMP);
            plugin.findings(player, plugin.engine().movement(player.getUniqueId(),
                    new MovementSample(tick, horizontal, vertical, surface.support(), surface.support() || verticalPotion, exempt,
                            plugin.settings().safety().horizontalLimit() * multiplier)));
            s.last = now;
            s.history.addLast(new Frame(tick, now.getWorld().getUID(), player.getBoundingBox().clone()));
            int maxHistory = plugin.settings().safety().historyTicks();
            while (!s.history.isEmpty() && tick - s.history.peekFirst().tick() > maxHistory) s.history.removeFirst();
        }
    }

    private boolean exempt(Player p, State s) {
        return plugin.lagging() || plugin.tick() < s.graceUntil || p.getPing() > plugin.settings().safety().maxPing()
                || p.hasPermission("guardianac.bypass") || p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE
                || p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isSwimming() || p.isRiptiding()
                || p.isInsideVehicle() || p.isDead() || p.isSleeping();
    }

    private Surface surface(Player p) {
        BoundingBox b = p.getBoundingBox();
        boolean support = false;
        // Narrow, bounded scan around the body handles stair/edge support and special movement surfaces.
        for (int x = floor(b.getMinX() - .15); x <= floor(b.getMaxX() + .15); x++) {
            for (int z = floor(b.getMinZ() - .15); z <= floor(b.getMaxZ() + .15); z++) {
                if (!p.getWorld().isChunkLoaded(x >> 4, z >> 4)) return new Surface(true, true);
                for (int y = Math.max(p.getWorld().getMinHeight(), floor(b.getMinY() - .55));
                     y <= Math.min(p.getWorld().getMaxHeight() - 1, floor(b.getMaxY() + .1)); y++) {
                    Block block = p.getWorld().getBlockAt(x, y, z);
                    String n = block.getType().name();
                    if (block.isLiquid() || n.contains("ICE") || n.contains("SLIME") || n.contains("HONEY")
                            || n.contains("VINE") || n.contains("PISTON") || n.contains("PORTAL")
                            || n.equals("SOUL_SAND") || n.equals("SOUL_SOIL") || n.equals("LADDER")
                            || n.equals("SCAFFOLDING") || n.equals("COBWEB") || n.equals("POWDER_SNOW")
                            || n.equals("BUBBLE_COLUMN") || n.equals("SWEET_BERRY_BUSH")
                            // Union bounding boxes cannot describe lower stairs and other partial supports reliably.
                            || n.endsWith("_STAIRS") || n.endsWith("_SLAB") || n.endsWith("_FENCE")
                            || n.endsWith("_WALL") || n.endsWith("TRAPDOOR") || n.equals("LILY_PAD")
                            || n.equals("FARMLAND") || n.equals("DIRT_PATH") || n.equals("SNOW")) return new Surface(true, true);
                    if (!block.isPassable()) {
                        BoundingBox shape = block.getBoundingBox();
                        if (shape.getMaxY() <= b.getMinY() + .08 && shape.getMaxY() >= b.getMinY() - .55
                                && shape.getMaxX() > b.getMinX() - .05 && shape.getMinX() < b.getMaxX() + .05
                                && shape.getMaxZ() > b.getMinZ() - .05 && shape.getMinZ() < b.getMaxZ() + .05) support = true;
                    }
                }
            }
        }
        return new Surface(support, false);
    }
    private static int floor(double value) { return (int)Math.floor(value); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player target)
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !attacker.getWorld().equals(target.getWorld())) return;
        State a = players.get(attacker.getUniqueId());
        State t = players.get(target.getUniqueId());
        boolean skip = a == null || t == null || combatExempt(attacker, a, true) || combatExempt(target, t, false);
        Location eye = attacker.getEyeLocation();
        double distance = distance(eye.toVector(), target.getBoundingBox());
        double angle = angle(eye, target.getBoundingBox());
        // History only expands the legitimate envelope; no lag-compensated accusation from stale boxes.
        int historyAge = Math.min(plugin.settings().safety().historyTicks(), (attacker.getPing() + 49) / 50 + 1);
        if (t != null) for (Frame frame : t.history) {
            if (frame.world().equals(attacker.getWorld().getUID()) && plugin.tick() - frame.tick() <= historyAge) {
                distance = Math.min(distance, distance(eye.toVector(), frame.box()));
                angle = Math.min(angle, angle(eye, frame.box()));
            }
        }
        double allowance = Math.min(plugin.settings().safety().reachLatencyAllowance(),
                (attacker.getPing() + target.getPing()) / 1000.0 * 1.5);
        plugin.findings(attacker, plugin.engine().combat(attacker.getUniqueId(), new CombatSample(plugin.tick(),
                target.getUniqueId(), distance, plugin.settings().safety().reachLimit() + allowance, angle, skip)));
    }

    public static double distance(Vector point, BoundingBox box) {
        double dx = Math.max(box.getMinX() - point.getX(), Math.max(0, point.getX() - box.getMaxX()));
        double dy = Math.max(box.getMinY() - point.getY(), Math.max(0, point.getY() - box.getMaxY()));
        double dz = Math.max(box.getMinZ() - point.getZ(), Math.max(0, point.getZ() - box.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double angle(Location eye, BoundingBox box) {
        Vector direction = box.getCenter().subtract(eye.toVector());
        return distance(eye.toVector(), box) < .5 || direction.lengthSquared() < 1.0e-8
                ? 0 : Math.min(180, Math.max(0, Math.toDegrees(eye.getDirection().angle(direction))));
    }

    private boolean combatExempt(Player p, State s, boolean attacker) {
        return plugin.lagging() || plugin.tick() < s.graceUntil || p.getPing() > plugin.settings().safety().maxPing()
                || p.isDead() || (attacker && (p.hasPermission("guardianac.bypass")
                || p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE));
    }

    private void grace(Player player, int ticks, boolean combat) {
        State s = players.computeIfAbsent(player.getUniqueId(), id -> new State());
        if (combat) s.graceUntil = Math.max(s.graceUntil, plugin.tick() + ticks);
        else s.movementGraceUntil = Math.max(s.movementGraceUntil, plugin.tick() + ticks);
        s.last = null;
        if (combat) { s.history.clear(); plugin.engine().reset(player.getUniqueId()); }
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent e) { grace(e.getPlayer(), plugin.settings().safety().teleportGrace(), true); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void velocity(PlayerVelocityEvent e) { grace(e.getPlayer(), plugin.settings().safety().velocityGrace(), false); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void damage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) grace(p, plugin.settings().safety().damageGrace(), false);
    }
    @EventHandler public void join(PlayerJoinEvent e) { grace(e.getPlayer(), plugin.settings().safety().joinGrace(), true); }
    @EventHandler public void respawn(PlayerRespawnEvent e) { grace(e.getPlayer(), plugin.settings().safety().teleportGrace(), true); }
    @EventHandler public void world(PlayerChangedWorldEvent e) { grace(e.getPlayer(), plugin.settings().safety().teleportGrace(), true); }
    @EventHandler public void quit(PlayerQuitEvent e) { players.remove(e.getPlayer().getUniqueId()); plugin.forget(e.getPlayer().getUniqueId()); }
    public void clear() { players.clear(); }
}
