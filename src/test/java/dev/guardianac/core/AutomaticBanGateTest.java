/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticBanGateTest {
    private static final UUID PLAYER=new UUID(17,19);
    private static final Set<String> CHECKS=Set.of("a","b");
    private static AutomaticBanGate.Policy policy(boolean enabled,Preset preset,Set<String> checks) {
        return new AutomaticBanGate.Policy(enabled,true,preset,checks,"UUID:24");
    }
    private static AutomaticBanGate gate(AtomicLong clock) {
        var gate=new AutomaticBanGate(2,clock::get);gate.publish(policy(true,Preset.STRICT,CHECKS));return gate;
    }
    @Test void evidenceMustBeRevalidatedBeforeSqlCommit() {
        var gate=gate(new AtomicLong());var ticket=gate.issue(PLAYER,false).orElseThrow();
        assertFalse(ticket.tryCommit());assertFalse(gate.authorize(ticket,true));
    }
    @Test void toggleOffWhileEvidenceWritePendingCancelsEvenIfTurnedBackOn() {
        var gate=gate(new AtomicLong());var ticket=gate.issue(PLAYER,false).orElseThrow();
        var evidence=new CompletableFuture<Void>();
        var outcome=evidence.thenApply(ignored->gate.authorize(ticket,true)).thenApply(allowed->allowed&&ticket.tryCommit());
        gate.publish(policy(false,Preset.STRICT,CHECKS));gate.publish(policy(true,Preset.STRICT,CHECKS));
        evidence.complete(null);assertFalse(outcome.join());
    }
    @Test void disabledCheckAndPresetChangesCancelPendingDecision() {
        for(var changed:List.of(policy(true,Preset.STRICT,Set.of("a")),policy(true,Preset.RELAXED,CHECKS))) {
            var gate=gate(new AtomicLong());var ticket=gate.issue(PLAYER,false).orElseThrow();
            gate.publish(changed);assertFalse(gate.authorize(ticket,true));assertFalse(ticket.tryCommit());
        }
    }
    @Test void publicationHookInvalidatesBeforeReplacementPolicyExists() {
        var gate=gate(new AtomicLong());var ticket=gate.issue(PLAYER,false).orElseThrow();assertTrue(gate.authorize(ticket,true));
        gate.invalidate();assertFalse(ticket.tryCommit());assertTrue(gate.issue(PLAYER,false).isEmpty());
    }
    @Test void bypassGrantDuringEvidencePersistenceOrSqlQueueCancels() {
        for(boolean alreadyAuthorized:new boolean[]{false,true}) {
            var gate=gate(new AtomicLong());var ticket=gate.issue(PLAYER,false).orElseThrow();
            if(alreadyAuthorized)assertTrue(gate.authorize(ticket,true));
            gate.observeBypass(PLAYER,true);gate.observeBypass(PLAYER,false);
            assertFalse(gate.authorize(ticket,true));assertFalse(ticket.tryCommit());
        }
    }
    @Test void logoutUsesLastPublishedBypassAndDoesNotEraseQualifiedDecision() {
        var gate=gate(new AtomicLong());var ticket=gate.issue(PLAYER,false).orElseThrow();
        gate.observeBypass(PLAYER,false); // Last main-thread snapshot before player object disappears.
        assertTrue(gate.authorize(ticket,true));assertTrue(ticket.tryCommit());assertTrue(ticket.claimed());
    }
    @Test void disableBeforeQueuedSqlClaimCancelsWithoutBlockingOrCallingMainFromWorker() throws Exception {
        var gate=gate(new AtomicLong());var ticket=gate.issue(PLAYER,false).orElseThrow();assertTrue(gate.authorize(ticket,true));
        try(var worker=Executors.newSingleThreadExecutor()) {
            var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
            var queued=worker.submit(()->{ready.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return ticket.tryCommit();});
            assertTrue(ready.await(5,TimeUnit.SECONDS));gate.invalidate();release.countDown();assertFalse(queued.get(5,TimeUnit.SECONDS));
        }
    }
    @Test void configurationChangeAfterClaimDoesNotRetroactivelyUnban() {
        var gate=gate(new AtomicLong());var ticket=gate.issue(PLAYER,false).orElseThrow();assertTrue(gate.authorize(ticket,true));assertTrue(ticket.tryCommit());
        gate.publish(policy(false,Preset.STRICT,CHECKS));assertTrue(ticket.claimed());assertFalse(ticket.tryCommit());
    }
    @Test void validationAndDecisionDeadlinesFailClosed() {
        var clock=new AtomicLong();var gate=gate(clock);var ticket=gate.issue(PLAYER,false).orElseThrow();assertTrue(gate.authorize(ticket,true));
        clock.set(500_000_001);assertFalse(ticket.tryCommit());
        clock.set(0);ticket=gate.issue(PLAYER,false).orElseThrow();clock.set(5_000_000_001L);assertFalse(gate.authorize(ticket,true));
        clock.set(20);ticket=gate.issue(PLAYER,false).orElseThrow();clock.set(19);assertFalse(gate.authorize(ticket,true));
    }
    @Test void oldCompletionCannotRemoveNewDecisionAndCapacityIsBounded() {
        var gate=gate(new AtomicLong());var old=gate.issue(PLAYER,false).orElseThrow();gate.publish(policy(true,Preset.STRICT,CHECKS));
        var current=gate.issue(PLAYER,false).orElseThrow();gate.finish(old);
        assertTrue(gate.authorize(current,true));assertTrue(gate.issue(new UUID(2,3),false).isPresent());
        assertTrue(gate.issue(new UUID(4,5),false).isEmpty());assertTrue(current.tryCommit());
    }
    @Test void unsupportedRuntimeAndBypassCannotIssueTickets() {
        var gate=gate(new AtomicLong());assertTrue(gate.issue(PLAYER,true).isEmpty());
        gate.publish(new AutomaticBanGate.Policy(true,false,Preset.STRICT,CHECKS,"UUID:24"));assertTrue(gate.issue(PLAYER,false).isEmpty());
    }
    @Test void confirmedUnbanResetCancelsOldUnclaimedEvidenceForOnlyThatActor() {
        var gate=gate(new AtomicLong());var old=gate.issue(PLAYER,false).orElseThrow();
        var other=gate.issue(new UUID(2,3),false).orElseThrow();
        assertTrue(gate.authorize(old,true));assertTrue(gate.authorize(other,true));
        gate.cancelPending(PLAYER);assertFalse(old.tryCommit());assertTrue(other.tryCommit());
        var fresh=gate.issue(PLAYER,false).orElseThrow();assertTrue(gate.authorize(fresh,true));
        assertTrue(fresh.tryCommit());gate.cancelPending(PLAYER);assertTrue(fresh.claimed());
    }
}
