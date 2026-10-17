/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.packet;

import dev.guardianac.core.Sample;
import java.util.Objects;

/**
 * One admitted connection and world generation. Player/User references are opaque
 * identity tokens here; packet callbacks never invoke Bukkit methods on them.
 * Mutations are serialized on this object; retirement is visible to main-thread reads.
 */
final class PacketSession {
    final Object player, user;
    volatile PacketContext context;
    volatile PacketMovement latest;
    volatile boolean exactProtocol, retired;
    final PacketTelemetry telemetry=new PacketTelemetry();

    PacketSession(Object player, Object user, PacketContext context) {
        this.player=Objects.requireNonNull(player); this.user=Objects.requireNonNull(user);
        this.context=Objects.requireNonNull(context);
    }
    boolean sameConnection(Object player, Object user) { return this.player==player&&this.user==user; }
    boolean acceptsUser(Object user) { return !retired&&this.user==user; }
    void retire() { retired=true; latest=null; exactProtocol=false; }
    boolean retireUser(Object user) {
        if(this.user!=user) return false;
        retire(); return true;
    }
    boolean acceptsSample(PacketSession owner, Sample sample, long now) {
        long age=now-sample.timeMillis();
        return this==owner&&!retired&&age>=0&&age<=500&&sample.has("world:"+context.worldId());
    }
}
