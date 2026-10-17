// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.security;

import dev.guardianac.storage.PermanentIpCandidate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PermanentIpApprovalGateTest {
    private static final UUID ACTOR = UUID.fromString("01234567-89ab-4def-8123-456789abcdef");
    private static final UUID TARGET = UUID.fromString("11234567-89ab-4def-8123-456789abcdef");
    private static final Instant START = Instant.parse("2026-10-14T00:00:00Z");
    private static final String CASE = "GAC-0123456789ABCDEF";
    private static final class MutableClock extends Clock {
        private Instant time = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException(); return this; }
        @Override public Instant instant() { return time; }
    }
    private static final class Fixture {
        final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        final MutableClock clock = new MutableClock();
        final Object session = new Object();
        final PermanentIpApprovalGate gate = new PermanentIpApprovalGate(2, nanos::get, clock);
        final PermanentIpCandidate candidate = new PermanentIpCandidate(CASE, TARGET, "evidence", "a".repeat(64),
            "protected-lookup", "b".repeat(64), START, START.plusSeconds(86_400), START);
        Fixture() { gate.publish("policy-1", true); }
        PermanentIpApprovalGate.Ticket issue() { return gate.issue(ACTOR, session, candidate, true).orElseThrow(); }
        PermanentIpApprovalGate.Ticket authorize(PermanentIpApprovalGate.Ticket ticket) {
            return gate.authorize(ticket.token(), ACTOR, session, true).orElseThrow();
        }
    }
    @Test void oneShotActorAndSessionBoundPositivePath() {
        Fixture f = new Fixture(); var ticket = f.issue();
        assertEquals(ACTOR, ticket.actor()); assertEquals(f.candidate, ticket.candidate()); assertEquals("policy-1", ticket.policyKey());
        assertFalse(ticket.tryCommit(), "Unconfirmed preview never grants authorization");
        ticket = f.issue();
        assertTrue(f.gate.authorize(ticket.token(), UUID.randomUUID(), f.session, true).isEmpty());
        assertTrue(f.gate.authorize(ticket.token(), ACTOR, new Object(), true).isEmpty());
        assertSame(ticket, f.authorize(ticket)); assertTrue(ticket.tryCommit()); assertTrue(ticket.claimed());
        assertFalse(ticket.tryCommit()); assertTrue(f.gate.authorize(ticket.token(), ACTOR, f.session, true).isEmpty());
    }
    @Test void authorizationCannotBeRefreshedWithARepeatedClick() {
        Fixture f = new Fixture(); var ticket = f.authorize(f.issue());
        f.nanos.addAndGet(400_000_000L);
        assertTrue(f.gate.authorize(ticket.token(), ACTOR, f.session, true).isEmpty());
        f.nanos.addAndGet(100_000_001L); assertFalse(ticket.tryCommit());
    }
    @Test void thirtySecondPreviewAndFiveHundredMillisecondCommitBoundaries() {
        Fixture f = new Fixture(); var ticket = f.issue(); f.nanos.addAndGet(29_500_000_000L);
        f.authorize(ticket); f.nanos.addAndGet(500_000_000L); assertTrue(ticket.tryCommit());
        f = new Fixture(); ticket = f.issue(); f.nanos.addAndGet(30_000_000_001L);
        assertTrue(f.gate.authorize(ticket.token(), ACTOR, f.session, true).isEmpty());
        f = new Fixture(); ticket = f.authorize(f.issue()); f.nanos.addAndGet(500_000_001L); assertFalse(ticket.tryCommit());
    }
    @Test void previewExpiryStillAppliesAfterAuthorization() {
        Fixture f = new Fixture(); var ticket = f.issue(); f.nanos.addAndGet(30_000_000_000L);
        f.authorize(ticket); f.nanos.incrementAndGet(); assertFalse(ticket.tryCommit());
    }
    @Test void clockRollbackAndCandidateExpiryFailClosed() {
        Fixture f = new Fixture(); var ticket = f.issue(); f.nanos.decrementAndGet();
        assertTrue(f.gate.authorize(ticket.token(), ACTOR, f.session, true).isEmpty());
        f = new Fixture(); ticket = f.authorize(f.issue()); f.clock.time = START.minusNanos(1); assertFalse(ticket.tryCommit());
        f = new Fixture(); ticket = f.authorize(f.issue()); f.clock.time = START.plusSeconds(86_400); assertFalse(ticket.tryCommit());
    }
    @Test void closeQuitPermissionsSessionAndCaseCancellationInvalidateUnclaimedTickets() {
        for (int mode = 0; mode < 7; mode++) {
            Fixture f = new Fixture(); var ticket = f.authorize(f.issue());
            switch (mode) {
                case 0 -> f.gate.cancel(ACTOR, ticket.token());
                case 1 -> f.gate.forget(ACTOR);
                case 2 -> f.gate.observeActor(ACTOR, f.session, false);
                case 3 -> f.gate.observeActor(ACTOR, new Object(), true);
                case 4 -> f.gate.cancelCase(CASE);
                case 5 -> f.gate.invalidate();
                case 6 -> f.gate.publish("policy-2", true);
                default -> throw new AssertionError();
            }
            assertFalse(ticket.tryCommit(), "mode=" + mode);
        }
    }
    @Test void freshReviewReplacesOnlyTheActorsEarlierPreview() {
        Fixture f = new Fixture(); var old = f.authorize(f.issue()); var fresh = f.issue();
        assertFalse(old.tryCommit()); assertTrue(f.authorize(fresh).tryCommit());
        var ticket = f.issue(); f.gate.cancel(UUID.randomUUID(), ticket.token());
        f.gate.cancelCase("GAC-FFFFFFFFFFFFFFFF"); f.gate.observeActor(UUID.randomUUID(), new Object(), false);
        assertTrue(f.authorize(ticket).tryCommit());
    }
    @Test void unauthorizedIssueAndAuthorizeFailClosed() {
        Fixture f = new Fixture(); assertTrue(f.gate.issue(ACTOR, f.session, f.candidate, false).isEmpty());
        var ticket = f.issue(); assertTrue(f.gate.authorize(ticket.token(), ACTOR, f.session, false).isEmpty());
        assertFalse(ticket.tryCommit()); f.gate.publish("policy-disabled", false);
        assertTrue(f.gate.issue(ACTOR, f.session, f.candidate, true).isEmpty());
    }
    @Test void boundedCapacityAndExpiredTicketCleanup() {
        Fixture f = new Fixture(); f.issue();
        assertTrue(f.gate.issue(UUID.randomUUID(), new Object(), f.candidate, true).isPresent());
        assertTrue(f.gate.issue(UUID.randomUUID(), new Object(), f.candidate, true).isEmpty());
        f.nanos.addAndGet(30_000_000_001L);
        assertTrue(f.gate.issue(UUID.randomUUID(), new Object(), f.candidate, true).isPresent());
    }
    @Test void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        Fixture f = new Fixture(); var ticket = f.authorize(f.issue());
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Boolean>> claims = new ArrayList<>(); for (int i = 0; i < 12; i++) claims.add(ticket::tryCommit);
            int successful = 0; for (var result : pool.invokeAll(claims)) if (result.get()) successful++;
            assertEquals(1, successful);
        }
    }
    @Test void ticketStringDoesNotRevealTokenOrProtectedBindings() {
        Fixture f = new Fixture(); var ticket = f.issue(); String rendered = ticket.toString();
        assertFalse(rendered.contains(ticket.token())); assertFalse(rendered.contains(f.candidate.ipLookup()));
        assertFalse(rendered.contains(f.candidate.evidenceSha256()));
    }
}
