// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.security;

import dev.guardianac.storage.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeBanReconcilerTest {
    private static final Instant NOW=Instant.parse("2026-10-14T00:00:00Z");
    private static BanRecord record(String id,BanType type,boolean revoked) {
        return new BanRecord(UUID.randomUUID(),UUID.fromString("00000000-0000-0000-0000-000000000001"),"Source",type,"Fixture",
            NOW.minusSeconds(60),null,100,id,"evidence","actor",true,"token-"+id,
            revoked?NOW.minusSeconds(1):null,null,null);
    }
    private static BanReconciliationSnapshot state(BanRecord record,long revision) {
        return new BanReconciliationSnapshot(Optional.of(record),Optional.of("127.0.2.1"),
            record.uuidActiveAt(NOW)?Optional.of(record):Optional.empty(),record.ipActiveAt(NOW)?Optional.of(record):Optional.empty(),revision);
    }
    private static final class Store implements NativeBanReconciler.Store {
        long revision;BanReconciliationSnapshot state;int reads,pages;List<String> retained=List.of();
        boolean delayed;CompletableFuture<BanReconciliationSnapshot> pending;
        public long revision(){return revision;}
        public CompletableFuture<BanReconciliationSnapshot> read(String id){reads++;if(delayed)return pending=new CompletableFuture<>();return CompletableFuture.completedFuture(state);}
        public CompletableFuture<List<String>> page(String after,int limit) {
            pages++;return CompletableFuture.completedFuture(retained.stream().filter(s->after==null||s.compareTo(after)>0).limit(limit).toList());
        }
        void set(BanRecord record){revision++;state=state(record,revision);}
    }
    private static void ticks(NativeBanReconciler r,int start,int end){for(int tick=start;tick<end;tick++)r.tick(tick);}
    @Test void revokeAfterCommitBeforeCallbackDoesNotNotifyOrDisconnect() {
        Store store=new Store();BanRecord active=record("GAC-0000000000000001",BanType.IP,false);store.set(active);
        AtomicInteger notifications=new AtomicInteger(),disconnects=new AtomicInteger();
        var r=new NativeBanReconciler(store,s->{},s->{});
        var done=r.request(active.caseId(),s->{BanRecord current=s.record().orElseThrow();if(NativeBanDecision.mayNotify(current,NOW))notifications.incrementAndGet();if(NativeBanDecision.mayDisconnect(current,current.playerId(),true,NOW))disconnects.incrementAndGet();return true;});
        store.set(record(active.caseId(),BanType.IP,true));ticks(r,0,3);
        assertTrue(done.isDone());assertEquals(0,notifications.get());assertEquals(0,disconnects.get());
    }
    @Test void changeAfterReadBeforeEffectSkipsStaleNativeAndRereads() {
        Store store=new Store();store.set(record("GAC-0000000000000001",BanType.DUAL,false));
        List<Boolean> applied=new ArrayList<>();var r=new NativeBanReconciler(store,s->applied.add(s.record().orElseThrow().activeAt(NOW)),s->{});
        var done=r.request(store.state.record().get().caseId(),null);r.tick(0);
        store.set(record("GAC-0000000000000001",BanType.DUAL,true));ticks(r,1,10);
        assertEquals(List.of(false),applied);assertTrue(done.isDone());assertEquals(2,store.reads);
    }
    @Test void mutationDuringNativeEffectRepairsAndWithholdsFinalEffect() {
        Store store=new Store();BanRecord active=record("GAC-0000000000000001",BanType.DUAL,false);store.set(active);
        AtomicInteger nativeEffects=new AtomicInteger(),notifications=new AtomicInteger();
        var r=new NativeBanReconciler(store,s->{if(nativeEffects.incrementAndGet()==1)store.set(record(active.caseId(),BanType.DUAL,true));},s->{});
        var done=r.request(active.caseId(),s->{if(NativeBanDecision.mayNotify(s.record().get(),NOW))notifications.incrementAndGet();return true;});
        ticks(r,0,12);assertEquals(2,nativeEffects.get());assertEquals(0,notifications.get());assertTrue(done.isDone());
    }
    @Test void revisionChangeDuringRetryIsRecheckedRatherThanReplayingOldSnapshot() {
        Store store=new Store();BanRecord active=record("GAC-0000000000000001",BanType.DUAL,false);store.set(active);
        AtomicInteger nativeEffects=new AtomicInteger(),finalEffects=new AtomicInteger();
        var r=new NativeBanReconciler(store,s->{if(nativeEffects.incrementAndGet()<3)store.set(record(active.caseId(),BanType.DUAL,true));},s->{});
        var done=r.request(active.caseId(),s->{if(NativeBanDecision.mayNotify(s.record().get(),NOW))finalEffects.incrementAndGet();return true;});
        ticks(r,0,30);assertEquals(3,nativeEffects.get());assertEquals(0,finalEffects.get());assertTrue(done.isDone());
    }
    @Test void finalFenceCanRequestBoundedFreshReadBeforeIrreversibleEffects() {
        Store store=new Store();store.set(record("GAC-0000000000000001",BanType.UUID,false));AtomicInteger finished=new AtomicInteger();
        var r=new NativeBanReconciler(store,s->{},s->{});var done=r.request(store.state.record().get().caseId(),s->finished.incrementAndGet()>1);
        ticks(r,0,20);assertTrue(done.isDone());assertEquals(2,store.reads);assertEquals(2,finished.get());
    }
    @Test void dirtyRetriesAreBoundedAndFailureVisible() {
        Store store=new Store();store.set(record("GAC-0000000000000001",BanType.DUAL,false));List<String> errors=new ArrayList<>();
        var r=new NativeBanReconciler(store,s->store.revision++,errors::add);var done=r.request(store.state.record().get().caseId(),s->{fail("Dirty state may not notify");return true;});
        ticks(r,0,100);assertTrue(done.isCompletedExceptionally());assertEquals(NativeBanReconciler.MAX_ATTEMPTS,store.reads);assertEquals(1,errors.size());
    }
    @Test void nativeFailureRetriesBoundedlyWithoutPretendingDatabaseRollback() {
        Store store=new Store();store.set(record("GAC-0000000000000001",BanType.DUAL,false));List<String> errors=new ArrayList<>();
        var r=new NativeBanReconciler(store,s->{throw new IllegalStateException("private provider error");},errors::add);var done=r.request(store.state.record().get().caseId(),null);
        ticks(r,0,100);assertTrue(done.isCompletedExceptionally());assertEquals(3,store.reads);assertTrue(errors.getFirst().contains("database state is unchanged"));assertFalse(errors.getFirst().contains("private"));
    }
    @Test void queueAndInFlightReadsAreBoundedAndDisableCancelsWithoutEffects() {
        Store store=new Store();store.delayed=true;AtomicInteger effects=new AtomicInteger();var r=new NativeBanReconciler(store,s->effects.incrementAndGet(),s->{});
        List<CompletableFuture<Void>> pending=new ArrayList<>();for(int i=0;i<NativeBanReconciler.CAPACITY;i++)pending.add(r.request("case-"+i,null));
        assertTrue(r.request("overflow",null).isCompletedExceptionally());ticks(r,0,100);assertEquals(1,store.reads);
        r.close();assertTrue(pending.stream().allMatch(CompletableFuture::isCompletedExceptionally));assertEquals(0,r.pendingWork());
        store.pending.complete(state(record("case-0",BanType.DUAL,false),0));ticks(r,100,120);assertEquals(0,effects.get());
    }
    @Test void retainedCaseSweepCoversBeyondFirstPageAndRepeatsAfterPause() {
        Store store=new Store();store.retained=java.util.stream.IntStream.range(0,83).mapToObj(i->String.format("GAC-%016X",i)).toList();
        store.set(record(store.retained.getFirst(),BanType.DUAL,true));List<String> reads=new ArrayList<>();
        NativeBanReconciler.Store spy=new NativeBanReconciler.Store(){public long revision(){return store.revision();}public CompletableFuture<BanReconciliationSnapshot> read(String id){reads.add(id);return store.read(id);}public CompletableFuture<List<String>> page(String after,int limit){return store.page(after,limit);}};
        var r=new NativeBanReconciler(spy,s->{},s->{});ticks(r,0,300);assertEquals(store.retained,reads);assertEquals(4,store.pages);
        ticks(r,300,1600);assertEquals(2,Collections.frequency(reads,store.retained.getLast()));
    }
    @Test void repeatedUrgentWorkCannotPermanentlyStarveRetainedSweep() {
        Store store=new Store();store.retained=List.of("sweep-a","sweep-b");store.set(record("urgent",BanType.UUID,false));List<String> reads=new ArrayList<>();
        NativeBanReconciler.Store spy=new NativeBanReconciler.Store(){public long revision(){return store.revision();}public CompletableFuture<BanReconciliationSnapshot> read(String id){reads.add(id);return store.read(id);}public CompletableFuture<List<String>> page(String after,int limit){return store.page(after,limit);}};
        var r=new NativeBanReconciler(spy,s->{},s->{});for(int tick=0;tick<40;tick++){r.request("urgent-"+tick,null);r.tick(tick);}
        assertTrue(reads.containsAll(store.retained));assertTrue(r.pendingWork()<=NativeBanReconciler.CAPACITY+NativeBanReconciler.PAGE_SIZE+1);
    }
    @Test void callbacksThatThrowAreNotReplayedAndTheirFutureCompletes() {
        Store store=new Store();store.set(record("GAC-0000000000000001",BanType.UUID,false));AtomicInteger calls=new AtomicInteger();
        var r=new NativeBanReconciler(store,s->{},s->{});var done=r.request("GAC-0000000000000001",s->{calls.incrementAndGet();throw new IllegalStateException();});
        ticks(r,0,100);assertTrue(done.isCompletedExceptionally());assertEquals(1,calls.get());
    }
    @Test void exhaustedUrgentWorkStillEnforcesSettledOnlineSourceThroughLaterSweepExactlyOnce() {
        Store store=new Store();BanRecord active=record("GAC-0000000000000001",BanType.UUID,false);store.set(active);
        store.retained=List.of(active.caseId());AtomicInteger delivered=new AtomicInteger(),announced=new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean(true),online=new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.function.Predicate<BanReconciliationSnapshot> enforce=s->{if(NativeBanDecision.mayNotify(s.record().get(),NOW)&&online.compareAndSet(true,false))delivered.incrementAndGet();return true;};
        var r=new NativeBanReconciler(store,s->{if(busy.get())store.revision++;},enforce,s->{});
        var original=r.request(active.caseId(),s->{announced.incrementAndGet();return enforce.test(s);});
        ticks(r,0,100);assertTrue(original.isCompletedExceptionally());assertEquals(0,delivered.get());
        busy.set(false);store.set(active);ticks(r,100,2800);
        assertEquals(1,delivered.get());assertEquals(0,announced.get(),"Historical announcements are deliberately not replayed");
    }
    @Test void queueFullCommittedSourceIsStillEnforcedByBoundedFairSweepExactlyOnce() {
        Store store=new Store();BanRecord active=record("GAC-0000000000000001",BanType.IP,false);store.set(active);store.retained=List.of(active.caseId());
        AtomicInteger delivered=new AtomicInteger();java.util.concurrent.atomic.AtomicBoolean online=new java.util.concurrent.atomic.AtomicBoolean(true);
        var r=new NativeBanReconciler(store,s->{},s->{if(NativeBanDecision.mayNotify(s.record().get(),NOW)&&online.compareAndSet(true,false))delivered.incrementAndGet();return true;},s->{});
        for(int i=0;i<NativeBanReconciler.CAPACITY;i++)r.request("occupied-"+i,s->true);
        assertTrue(r.request(active.caseId(),s->{fail("Rejected callback must not have been retained in an unbounded overflow");return true;}).isCompletedExceptionally());
        ticks(r,0,2800);assertEquals(1,delivered.get());assertTrue(r.pendingWork()<=NativeBanReconciler.CAPACITY+NativeBanReconciler.PAGE_SIZE+1);
    }
    @Test void revocationBeforeFallbackRecoveryPreventsAnyDelivery() {
        Store store=new Store();BanRecord active=record("GAC-0000000000000001",BanType.IP,false);store.set(active);store.retained=List.of(active.caseId());AtomicInteger delivered=new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean(true);
        var r=new NativeBanReconciler(store,s->{if(busy.get())store.revision++;},s->{if(NativeBanDecision.mayNotify(s.record().get(),NOW))delivered.incrementAndGet();return true;},s->{});
        var original=r.request(active.caseId(),s->{delivered.incrementAndGet();return true;});ticks(r,0,100);assertTrue(original.isCompletedExceptionally());
        busy.set(false);store.set(record(active.caseId(),BanType.IP,true));ticks(r,100,2800);assertEquals(0,delivered.get());
    }
    @Test void sessionDeliveryClaimCannotLeakToEqualReplacementSession() {
        Object first=new String("same-account"),replacement=new String("same-account");assertEquals(first,replacement);
        var claim=new NativeBanDelivery(first,"GAC-0000000000000001");assertTrue(claim.belongsTo(first));assertFalse(claim.belongsTo(replacement));assertFalse(claim.belongsTo(null));assertEquals("GAC-0000000000000001",claim.caseId());
        claim.playerSession().clear();assertFalse(claim.belongsTo(first));assertFalse(claim.belongsTo(null));
    }

}
