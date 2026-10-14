package dev.guardianac.detection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-thread-only, bounded-state movement and combat heuristics. No packet-level claims are made.
 * Exempt or malformed samples erase evidence for that stream instead of producing a finding.
 */
public final class DetectionEngine {
    public record MovementSettings(double speedTolerance, int speedBuffer, int airGraceTicks, int flyBuffer,
                                   double hoverVerticalTolerance, double ascentMinVertical) {
        public MovementSettings {
            finiteRange(speedTolerance, 1, 3, "speedTolerance");
            intRange(speedBuffer, 1, 100, "speedBuffer");
            intRange(airGraceTicks, 10, 200, "airGraceTicks");
            intRange(flyBuffer, 1, 100, "flyBuffer");
            finiteRange(hoverVerticalTolerance, .001, .1, "hoverVerticalTolerance");
            finiteRange(ascentMinVertical, .05, 1, "ascentMinVertical");
        }
        public static MovementSettings defaults() { return new MovementSettings(1.15, 4, 18, 8, .025, .15); }
    }

    public record CombatSettings(double reachMargin, int reachBuffer, int maxAttackGapTicks,
                                 double killAuraAngleDegrees, int killAuraBuffer, int maxAttacksPerTick,
                                 int maxDistinctTargetsPerWindow, int targetWindowTicks) {
        public CombatSettings {
            finiteRange(reachMargin, 0, 1, "reachMargin");
            intRange(reachBuffer, 1, 100, "reachBuffer");
            intRange(maxAttackGapTicks, 1, 200, "maxAttackGapTicks");
            finiteRange(killAuraAngleDegrees, 90, 180, "killAuraAngleDegrees");
            intRange(killAuraBuffer, 1, 100, "killAuraBuffer");
            intRange(maxAttacksPerTick, 1, 20, "maxAttacksPerTick");
            intRange(maxDistinctTargetsPerWindow, 1, 20, "maxDistinctTargetsPerWindow");
            intRange(targetWindowTicks, 1, 20, "targetWindowTicks");
        }
        public static CombatSettings defaults() { return new CombatSettings(.10, 3, 40, 125, 6, 3, 4, 4); }
    }

    public record Settings(MovementSettings movement, CombatSettings combat) {
        public Settings {
            Objects.requireNonNull(movement, "movement");
            Objects.requireNonNull(combat, "combat");
        }
        public static Settings defaults() { return new Settings(MovementSettings.defaults(), CombatSettings.defaults()); }
    }

    private static final class MovementState {
        long tick = -1;
        int speed, fly, airborne;
    }
    private static final class CombatState {
        long tick = -1;
        long lastSuspiciousTick = -1;
        long lastDecayTick = -1;
        int reach, aura, attacks;
        // At most configured limit + 1 (<= 21) entries, regardless of attack volume.
        final Map<UUID, Long> targets = new HashMap<>();
    }

    private final Settings settings;
    private final Map<UUID, MovementState> movement = new HashMap<>();
    private final Map<UUID, CombatState> combat = new HashMap<>();

    public DetectionEngine(Settings settings) { this.settings = Objects.requireNonNull(settings, "settings"); }

    public List<Finding> movement(UUID player, MovementSample sample) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(sample, "sample");
        if (sample.exempt() || !valid(sample)) {
            movement.remove(player);
            return List.of();
        }
        MovementState state = movement.computeIfAbsent(player, ignored -> new MovementState());
        if (state.tick < 0 || sample.tick() != state.tick + 1) {
            // A missing tick makes the delta ambiguous: begin a fresh evidence window.
            state.tick = sample.tick();
            state.speed = state.fly = state.airborne = 0;
            return List.of();
        }
        state.tick = sample.tick();
        MovementSettings config = settings.movement();
        List<Finding> findings = new ArrayList<>(2);
        if (sample.horizontal() > sample.horizontalLimit() * config.speedTolerance()) {
            if (++state.speed >= config.speedBuffer()) {
                findings.add(new Finding(CheckType.SPEED, 1, detail("horizontal", sample.horizontal(), sample.horizontalLimit())));
                state.speed = 0;
            }
        } else state.speed = Math.max(0, state.speed - 1);

        if (sample.onGround() || sample.nearSupport()) {
            state.airborne = state.fly = 0;
        } else {
            state.airborne = Math.min(config.airGraceTicks() + 1, state.airborne + 1);
            // Ordinary jumps apex within the grace period. Falling is never ascent evidence.
            boolean hovering = Math.abs(sample.vertical()) <= config.hoverVerticalTolerance();
            boolean ascending = sample.vertical() >= config.ascentMinVertical();
            if (state.airborne > config.airGraceTicks() && (hovering || ascending)) {
                if (++state.fly >= config.flyBuffer()) {
                    findings.add(new Finding(CheckType.FLY, 1, hovering ? "persistent unsupported hover" : "persistent unsupported ascent"));
                    state.fly = 0;
                }
            } else state.fly = Math.max(0, state.fly - 1);
        }
        return List.copyOf(findings);
    }

    public List<Finding> combat(UUID player, CombatSample sample) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(sample, "sample");
        if (sample.exempt() || !valid(sample)) {
            combat.remove(player);
            return List.of();
        }
        CombatSettings config = settings.combat();
        CombatState state = combat.computeIfAbsent(player, ignored -> new CombatState());
        if (state.tick >= 0 && (sample.tick() < state.tick || sample.tick() - state.tick > config.maxAttackGapTicks())) {
            state = new CombatState();
            combat.put(player, state);
        }
        boolean newTick = sample.tick() != state.tick;
        state.attacks = newTick ? 1 : Math.min(config.maxAttacksPerTick() + 1, state.attacks + 1);
        state.tick = sample.tick();
        List<Finding> findings = new ArrayList<>(2);
        if (sample.distance() > sample.reachLimit() + config.reachMargin()) {
            if (++state.reach >= config.reachBuffer()) {
                findings.add(new Finding(CheckType.REACH, 1, detail("distance", sample.distance(), sample.reachLimit())));
                state.reach = 0;
            }
        } else state.reach = Math.max(0, state.reach - 1);

        long oldest = sample.tick() - config.targetWindowTicks() + 1;
        state.targets.values().removeIf(tick -> tick < oldest);
        if (state.targets.containsKey(sample.target()) || state.targets.size() <= config.maxDistinctTargetsPerWindow()) {
            state.targets.put(sample.target(), sample.tick());
        }
        boolean impossibleAngle = sample.distance() >= .5 && sample.angleDegrees() > config.killAuraAngleDegrees();
        boolean burst = state.attacks > config.maxAttacksPerTick();
        boolean targetBurst = state.targets.size() > config.maxDistinctTargetsPerWindow();
        if (impossibleAngle || burst || targetBurst) {
            state.lastSuspiciousTick = sample.tick();
            if (++state.aura >= config.killAuraBuffer()) {
                String cause = impossibleAngle ? "persistent implausible attack angle" : burst ? "repeated attack burst" : "repeated multi-target burst";
                findings.add(new Finding(CheckType.KILLAURA, 1, cause));
                state.aura = 0;
            }
        } else if (newTick && sample.tick() != state.lastDecayTick
                && (state.lastSuspiciousTick < 0 || sample.tick() - state.lastSuspiciousTick > config.targetWindowTicks())) {
            state.aura = Math.max(0, state.aura - 1);
            state.lastDecayTick = sample.tick();
        }
        return List.copyOf(findings);
    }

    public void reset(UUID player) {
        movement.remove(player);
        combat.remove(player);
    }

    public void clear() {
        movement.clear();
        combat.clear();
    }

    private static boolean valid(MovementSample sample) {
        return sample.tick() >= 0 && Double.isFinite(sample.horizontal()) && sample.horizontal() >= 0
                && Double.isFinite(sample.vertical()) && Double.isFinite(sample.horizontalLimit()) && sample.horizontalLimit() > 0;
    }

    private static boolean valid(CombatSample sample) {
        return sample.tick() >= 0 && sample.target() != null && Double.isFinite(sample.distance()) && sample.distance() >= 0
                && Double.isFinite(sample.reachLimit()) && sample.reachLimit() > 0
                && Double.isFinite(sample.angleDegrees()) && sample.angleDegrees() >= 0 && sample.angleDegrees() <= 180;
    }

    private static String detail(String measurement, double observed, double allowed) {
        return String.format(Locale.ROOT, "%s %.3f, envelope %.3f", measurement, observed, allowed);
    }

    private static void finiteRange(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
    }

    private static void intRange(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
    }
}
