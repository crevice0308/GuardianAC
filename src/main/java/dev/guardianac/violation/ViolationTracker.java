package dev.guardianac.violation;

import dev.guardianac.detection.CheckType;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Main-thread confined, with independent decaying evidence for each check. */
public final class ViolationTracker {
    public record Verdict(double level, boolean punish) {}
    private static final class Evidence {
        double level;
        int findings;
        long firstTick;
        long lastTick;
    }
    private final Map<UUID, EnumMap<CheckType, Evidence>> players = new HashMap<>();
    private final double decayPerTick;
    private final int minimumFindings;
    private final int minimumTicks;

    public ViolationTracker(double decayPerSecond, int minimumFindings, int minimumTicks) {
        if (!Double.isFinite(decayPerSecond) || decayPerSecond < 0 || minimumFindings < 1 || minimumTicks < 0)
            throw new IllegalArgumentException("Invalid violation policy");
        this.decayPerTick = decayPerSecond / 20.0;
        this.minimumFindings = minimumFindings;
        this.minimumTicks = minimumTicks;
    }

    public Verdict add(UUID player, CheckType check, double amount, double threshold, long tick) {
        if (!Double.isFinite(amount) || amount <= 0 || !Double.isFinite(threshold) || threshold <= 0)
            throw new IllegalArgumentException("Invalid violation amount/threshold");
        Evidence e = players.computeIfAbsent(player, k -> new EnumMap<>(CheckType.class))
                .computeIfAbsent(check, k -> new Evidence());
        decay(e, tick);
        if (e.level == 0) { e.findings = 0; e.firstTick = tick; }
        e.level = Math.min(1_000_000, e.level + amount);
        e.findings = Math.min(Integer.MAX_VALUE - 1, e.findings + 1);
        return new Verdict(e.level, e.level >= threshold && e.findings >= minimumFindings
                && tick - e.firstTick >= minimumTicks);
    }

    public double maximum(UUID player, long tick) {
        var checks = players.get(player);
        if (checks == null) return 0;
        double max = 0;
        for (Evidence e : checks.values()) { decay(e, tick); max = Math.max(max, e.level); }
        return max;
    }

    private void decay(Evidence e, long tick) {
        e.level = Math.max(0, e.level - Math.max(0, tick - e.lastTick) * decayPerTick);
        e.lastTick = tick;
    }
    public void remove(UUID player) { players.remove(player); }
    public void clear() { players.clear(); }
}
