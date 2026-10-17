// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.security;

import dev.guardianac.storage.PermanentIpCandidate;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Actor/session/candidate-bound administrative approval. No Bukkit or I/O under this lock. */
public final class PermanentIpApprovalGate {
    private static final long PREVIEW_NANOS = 30_000_000_000L;
    private static final long AUTHORIZATION_NANOS = 500_000_000L;
    private final int capacity;
    private final LongSupplier nanoTime;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> pending = new HashMap<>();
    private long revision;
    private boolean permitted;
    private String policyKey;

    public PermanentIpApprovalGate(int capacity) { this(capacity, System::nanoTime, Clock.systemUTC()); }
    public PermanentIpApprovalGate(int capacity, LongSupplier nanoTime, Clock clock) {
        if (capacity < 1 || capacity > 10_000) throw new IllegalArgumentException("Invalid approval capacity");
        this.capacity = capacity; this.nanoTime = Objects.requireNonNull(nanoTime);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Invalidate before publishing any changed settings, and during shutdown. */
    public synchronized void invalidate() { revision++; permitted = false; policyKey = null; pending.clear(); }
    public synchronized void publish(String policyKey, boolean permitted) {
        Objects.requireNonNull(policyKey);
        if (policyKey.isBlank() || policyKey.length() > 4096) throw new IllegalArgumentException("Invalid policy key");
        revision++; this.policyKey = policyKey; this.permitted = permitted; pending.clear();
    }

    /** actorSession is the exact current Player instance, never a player name or UUID alone. */
    public synchronized Optional<Ticket> issue(UUID actor, Object actorSession, PermanentIpCandidate candidate,
                                             boolean authorized) {
        Objects.requireNonNull(actor); Objects.requireNonNull(actorSession); Objects.requireNonNull(candidate);
        long now = nanoTime.getAsLong();
        pending.values().removeIf(ticket -> !within(now, ticket.issued, PREVIEW_NANOS)
            || !ticket.candidate.validAt(clock.instant()));
        // Starting another review invalidates the actor's previous preview, even if this one fails.
        forget(actor);
        if (!permitted || !authorized || !candidate.validAt(clock.instant()) || pending.size() >= capacity)
            return Optional.empty();
        byte[] bytes = new byte[16]; String token;
        do { random.nextBytes(bytes); token = HexFormat.of().formatHex(bytes); } while (pending.containsKey(token));
        Ticket ticket = new Ticket(token, actor, actorSession, candidate, policyKey, revision, now);
        pending.put(token, ticket); return Optional.of(ticket);
    }

    /** Final server-thread permission/session/policy snapshot; cannot authorize the same token twice. */
    public synchronized Optional<Ticket> authorize(String token, UUID actor, Object actorSession, boolean authorized) {
        if (token == null || token.length() != 32) return Optional.empty();
        Ticket ticket = pending.get(token);
        if (ticket == null || !ticket.actor.equals(actor) || ticket.actorSession != actorSession)
            return Optional.empty();
        long now = nanoTime.getAsLong();
        if (!authorized || !current(ticket) || !within(now, ticket.issued, PREVIEW_NANOS)
                || !ticket.candidate.validAt(clock.instant())) {
            pending.remove(token, ticket); return Optional.empty();
        }
        if (ticket.validated) return Optional.empty();
        ticket.authorizedAt = now; ticket.validated = true;
        return Optional.of(ticket);
    }

    /** Main-thread pulse observes current authorization; logout also calls forget. */
    public synchronized void observeActor(UUID actor, Object actorSession, boolean authorized) {
        pending.values().removeIf(ticket -> ticket.actor.equals(actor)
            && (!authorized || ticket.actorSession != actorSession));
    }
    public synchronized void cancel(UUID actor, String token) {
        Ticket ticket = pending.get(token);
        if (ticket != null && ticket.actor.equals(actor)) pending.remove(token, ticket);
    }
    public synchronized void forget(UUID actor) {
        Objects.requireNonNull(actor); pending.values().removeIf(ticket -> ticket.actor.equals(actor));
    }
    public synchronized void cancelCase(String sourceCaseId) {
        Objects.requireNonNull(sourceCaseId);
        pending.values().removeIf(ticket -> ticket.candidate.sourceCaseId().equals(sourceCaseId));
    }

    private boolean current(Ticket ticket) {
        return permitted && ticket.revision == revision && pending.get(ticket.token) == ticket;
    }
    private static boolean within(long now, long then, long limit) {
        long age = now - then; return age >= 0 && age <= limit;
    }
    private synchronized boolean claim(Ticket ticket) {
        long now = nanoTime.getAsLong();
        if (!current(ticket) || !ticket.validated || !within(now, ticket.issued, PREVIEW_NANOS)
                || !within(now, ticket.authorizedAt, AUTHORIZATION_NANOS)
                || !ticket.candidate.validAt(clock.instant())) {
            pending.remove(ticket.token, ticket); return false;
        }
        pending.remove(ticket.token, ticket); ticket.claimed = true; return true;
    }

    public final class Ticket {
        private final String token;
        private final UUID actor;
        private final Object actorSession;
        private final PermanentIpCandidate candidate;
        private final String policyKey;
        private final long revision, issued;
        private long authorizedAt;
        private boolean validated, claimed;
        private Ticket(String token, UUID actor, Object actorSession, PermanentIpCandidate candidate,
                       String policyKey, long revision, long issued) {
            this.token = token; this.actor = actor; this.actorSession = actorSession; this.candidate = candidate;
            this.policyKey = policyKey; this.revision = revision; this.issued = issued;
        }
        public String token() { return token; }
        public UUID actor() { return actor; }
        public PermanentIpCandidate candidate() { return candidate; }
        public String policyKey() { return policyKey; }
        /** Authorization linearization point immediately before SQL commit. Never invokes Bukkit. */
        public boolean tryCommit() { return claim(this); }
        public boolean claimed() { synchronized (PermanentIpApprovalGate.this) { return claimed; } }
        @Override public String toString() {
            return "PermanentIpApprovalTicket[actor=" + actor + ", sourceCase=" + candidate.sourceCaseId() + "]";
        }
    }
}
