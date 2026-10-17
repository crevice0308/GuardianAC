/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Nonblocking authorization handoff between the server thread and SQL writer.
 * All synchronized sections only change in-memory values; no caller executes I/O under this lock.
 * A successful tryCommit is the authorization linearization point, immediately before SQL commit.
 * Later configuration changes do not revoke an already claimed/committed sanction.
 */
public final class AutomaticBanGate {
    public record Policy(boolean enabled, boolean supportedRuntime, Preset preset,
                         Set<String> enabledChecks, String sanctionPolicy) {
        public Policy { Objects.requireNonNull(preset); enabledChecks=Set.copyOf(enabledChecks); Objects.requireNonNull(sanctionPolicy); }
        public boolean permits() { return enabled && supportedRuntime; }
    }
    private static final long DECISION_NANOS=5_000_000_000L, AUTHORIZATION_NANOS=500_000_000L;
    private final LongSupplier nanoTime;
    private final int capacity;
    private final Map<UUID,Ticket> pending=new HashMap<>();
    private Policy policy;
    private long revision;
    public AutomaticBanGate(int capacity) { this(capacity,System::nanoTime); }
    public AutomaticBanGate(int capacity,LongSupplier nanoTime) {
        if(capacity<1)throw new IllegalArgumentException("Positive capacity required");
        this.capacity=capacity;this.nanoTime=Objects.requireNonNull(nanoTime);
    }
    /** Called before a changed configuration is published, and at lifecycle shutdown. */
    public synchronized void invalidate() { revision++;policy=null;pending.clear(); }
    /** Main-thread publication of an immutable enforcement policy. */
    public synchronized void publish(Policy next) { revision++;policy=Objects.requireNonNull(next);pending.clear(); }
    public synchronized Optional<Ticket> issue(UUID playerId,boolean bypass) {
        Objects.requireNonNull(playerId);
        if(bypass||policy==null||!policy.permits()||pending.containsKey(playerId)||pending.size()>=capacity)return Optional.empty();
        Ticket ticket=new Ticket(playerId,revision,nanoTime.getAsLong());pending.put(playerId,ticket);return Optional.of(ticket);
    }
    /** Only call with a permission snapshot read on the server thread. Offline actors retain their last snapshot. */
    public synchronized void observeBypass(UUID playerId,boolean bypass) { if(bypass)pending.remove(playerId); }
    /** A confirmed administrative evidence/VL reset cancels unclaimed decisions for this actor only. */
    public synchronized void cancelPending(UUID playerId) { pending.remove(Objects.requireNonNull(playerId)); }
    /** Revalidating immutable evidence must happen on the server thread after asynchronous evidence persistence. */
    public synchronized boolean authorize(Ticket ticket,boolean evidenceStillQualifies) {
        long now=nanoTime.getAsLong();
        if(!current(ticket)||!evidenceStillQualifies||!within(now,ticket.issued,DECISION_NANOS)) { pending.remove(ticket.playerId,ticket);return false; }
        ticket.authorized=now;ticket.validated=true;return true;
    }
    private boolean current(Ticket ticket) {
        return ticket!=null&&ticket.owner()==this&&policy!=null&&policy.permits()&&ticket.revision==revision&&pending.get(ticket.playerId)==ticket;
    }
    private static boolean within(long now,long before,long limit) { long age=now-before;return age>=0&&age<=limit; }
    private synchronized boolean claim(Ticket ticket) {
        long now=nanoTime.getAsLong();
        if(!current(ticket)||!ticket.validated||!within(now,ticket.issued,DECISION_NANOS)
            ||!within(now,ticket.authorized,AUTHORIZATION_NANOS)) { pending.remove(ticket.playerId,ticket);return false; }
        pending.remove(ticket.playerId);ticket.claimed=true;return true;
    }
    public synchronized void finish(Ticket ticket) { if(ticket!=null)pending.remove(ticket.playerId,ticket); }
    public final class Ticket {
        private final UUID playerId;private final long revision,issued;
        private long authorized;private boolean validated,claimed;
        private Ticket(UUID playerId,long revision,long issued){this.playerId=playerId;this.revision=revision;this.issued=issued;}
        private AutomaticBanGate owner(){return AutomaticBanGate.this;}
        /** Pure worker-safe one-shot guard. Never call Bukkit or block waiting for the server thread here. */
        public boolean tryCommit(){return claim(this);}
        public boolean claimed(){synchronized(AutomaticBanGate.this){return claimed;}}
    }
}
