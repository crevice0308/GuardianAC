/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.packet;

import dev.guardianac.core.Sample;
import java.util.UUID;

/** Pure, bounded per-connection counters; callers serialize access per connection. */
public final class PacketTelemetry {
    private final RateWindow packets = new RateWindow(20,50), swings = new RateWindow(20,50),
        interactions = new RateWindow(20,50), movement = new RateWindow(100,100);
    private long firstMovement = Long.MIN_VALUE, lastMovement = Long.MIN_VALUE, burstStart = Long.MIN_VALUE, gapBeforeBurst;
    private int burst;
    private long lastEmission = Long.MIN_VALUE;
    private long lastInvalidEmission = Long.MIN_VALUE;
    public boolean record(long monotonicMillis, boolean moving, boolean swing, boolean interaction) {
        packets.add(monotonicMillis);
        if (swing) swings.add(monotonicMillis);
        if (interaction) interactions.add(monotonicMillis);
        if (moving) {
            movement.add(monotonicMillis);
            if (firstMovement == Long.MIN_VALUE) firstMovement = monotonicMillis;
            long gap = lastMovement == Long.MIN_VALUE ? 0 : monotonicMillis - lastMovement;
            if (burstStart == Long.MIN_VALUE || monotonicMillis - burstStart >= 250) {
                burstStart = monotonicMillis; burst = 0; gapBeforeBurst = gap;
            }
            burst++; lastMovement = monotonicMillis;
        }
        return packets.count(monotonicMillis) > 2000 || swings.count(monotonicMillis) > 300
            || interactions.count(monotonicMillis) > 400;
    }
    public Sample.Builder sample(UUID id, PacketContext context, long wallMillis, long monoMillis,
                                 boolean translated) {
        Sample.Builder out = Sample.builder(id, Sample.Kind.PACKET, wallMillis, context.tick())
            .value("tps", context.tps()).value("ping", context.ping()).flag("world:"+context.worldId())
            .value("packets-second", packets.count(monoMillis)).value("swings-second", swings.count(monoMillis))
            .value("interactions-second", interactions.count(monoMillis))
            .flag("container-open", context.containerOpen()).flag("protocol-translated", translated)
            .flag("exempt", translated || !context.fresh(wallMillis));
        context.flags().forEach(out::flag);
        if (context.containerClosedAtMillis() > 0)
            out.value("since-close-ms", Math.max(0, wallMillis - context.containerClosedAtMillis()));
        if (firstMovement != Long.MIN_VALUE) {
            out.value("movement-window-ms", Math.min(movement.durationMillis(), Math.max(0,monoMillis-firstMovement)))
                .value("movement-count", movement.count(monoMillis))
                .value("previous-gap-ms", gapBeforeBurst).value("movement-burst", burst);
        }
        return out;
    }
    public boolean shouldEmit(long now, boolean invalid) {
        if (invalid) {
            if (lastInvalidEmission == Long.MIN_VALUE || now-lastInvalidEmission >= 100) { lastInvalidEmission=now; return true; }
            return false;
        }
        if (lastEmission == Long.MIN_VALUE || now-lastEmission >= 50) { lastEmission=now; return true; }
        return false;
    }
    public static boolean invalidPosition(double x,double y,double z) {
        return !Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
            ||Math.abs(x)>30_000_001||Math.abs(z)>30_000_001||Math.abs(y)>20_000_000;
    }
    public static boolean invalidRotation(float yaw,float pitch) {
        return !Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(pitch)>90.01;
    }
}
