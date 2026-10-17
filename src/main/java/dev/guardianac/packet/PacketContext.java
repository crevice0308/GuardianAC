/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.packet;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Main-thread snapshot. No Bukkit objects may cross the network-thread boundary. */
public record PacketContext(UUID worldId, long capturedAtMillis, long tick, double tps, int ping,
                            Set<String> flags, boolean containerOpen, long containerClosedAtMillis,
                            int minHeight, int maxHeight) {
    public PacketContext {
        Objects.requireNonNull(worldId);
        flags = Set.copyOf(flags);
        if (flags.size() > 24 || minHeight >= maxHeight) throw new IllegalArgumentException("Invalid context");
    }
    public boolean fresh(long now) { return now >= capturedAtMillis && now - capturedAtMillis <= 500; }
}
