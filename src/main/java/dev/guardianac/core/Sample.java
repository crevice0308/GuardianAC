/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import java.util.*;

/** Immutable, bounded telemetry. Missing measurements never count as violations. */
public record Sample(UUID playerId, long timeMillis, long tick, Kind kind,
                     Map<String, Double> measurements, Set<String> flags) {
    public enum Kind { MOVE, ATTACK, BREAK, PLACE, INVENTORY, PACKET, MINING, USE, VELOCITY }
    public Sample {
        Objects.requireNonNull(playerId); Objects.requireNonNull(kind);
        if (measurements.size() > 96 || flags.size() > 32) throw new IllegalArgumentException("Telemetry too large");
        measurements = Map.copyOf(measurements); flags = Set.copyOf(flags);
    }
    public double v(String key) { return measurements.getOrDefault(key, Double.NaN); }
    public boolean has(String flag) { return flags.contains(flag); }
    public boolean safePhysics() {
        return !has("exempt") && !has("vehicle") && !has("flight") && !has("effects")
            && !has("velocity-grace") && !has("teleport-grace") && !has("special-terrain")
            && !has("protocol-translated") && v("tps") >= 18 && v("ping") <= 200;
    }
    /** Inventory timing is observable during totem potion effects and knockback; physics exemptions do not erase it. */
    public boolean safeInventoryTiming() {
        return !has("exempt") && !has("vehicle") && !has("flight") && !has("teleport-grace")
            && !has("protocol-translated") && v("tps") >= 18 && v("ping") <= 200;
    }
    public static Builder builder(UUID playerId, Kind kind, long timeMillis, long tick) {
        return new Builder(playerId, kind, timeMillis, tick);
    }
    public static final class Builder {
        private final UUID id; private final Kind kind; private final long time, tick;
        private final Map<String,Double> values = new HashMap<>(); private final Set<String> flags = new HashSet<>();
        private Builder(UUID id, Kind kind, long time, long tick) { this.id=id; this.kind=kind; this.time=time; this.tick=tick; }
        public Builder value(String key, double value) { values.put(key,value); return this; }
        public Builder flag(String flag) { flags.add(flag); return this; }
        public Builder flag(String flag, boolean yes) { if(yes) flags.add(flag); return this; }
        public Sample build() { return new Sample(id,time,tick,kind,values,flags); }
    }
}
