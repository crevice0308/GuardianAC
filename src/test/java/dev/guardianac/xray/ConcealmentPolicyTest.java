/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConcealmentPolicyTest {
    @Test void disablingInvalidatesCallbackThatAlreadyCapturedEnabledSettings(){
        var policy=new ConcealmentPolicy();var captured=policy.snapshot();AtomicInteger commits=new AtomicInteger();
        policy.configure(captured.settings().withEnabled(false));
        assertFalse(policy.commit(captured,commits::incrementAndGet));assertEquals(0,commits.get());
    }
    @Test void reusingOriginalSettingsAfterDisableCannotReviveOldCallback(){
        var policy=new ConcealmentPolicy();var captured=policy.snapshot();
        policy.configure(captured.settings().withEnabled(false));policy.configure(captured.settings());
        assertFalse(policy.commit(captured,()->fail("Old generation committed")));
        assertTrue(policy.commit(policy.snapshot(),()->{}));
    }
    @Test void anyReconfigurationInvalidatesTheCapturedGeneration(){
        var policy=new ConcealmentPolicy();var captured=policy.snapshot();policy.configure(captured.settings());
        assertFalse(policy.commit(captured,()->fail("Same settings instance must still advance generation")));
    }
    @Test void stopRejectsInflightCallbacksAndCannotBeUndoneByConfigure(){
        var policy=new ConcealmentPolicy();var captured=policy.snapshot();policy.stop();policy.configure(captured.settings());
        assertTrue(policy.stopped());assertFalse(policy.commit(captured,()->fail()));
        assertFalse(policy.commit(policy.snapshot(),()->fail()));
    }
    @Test void configureCannotInterleaveWithReservationAndPacketCommit() throws Exception {
        var policy=new ConcealmentPolicy();var captured=policy.snapshot();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),configuring=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<Boolean> commit=pool.submit(()->policy.commit(captured,()->{
                entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("No release");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<?> configuration=pool.submit(()->{configuring.countDown();policy.configure(captured.settings().withEnabled(false));});
            assertTrue(configuring.await(5,TimeUnit.SECONDS));
            try{assertThrows(TimeoutException.class,()->configuration.get(50,TimeUnit.MILLISECONDS));}
            finally{release.countDown();}
            assertTrue(commit.get(5,TimeUnit.SECONDS));configuration.get(5,TimeUnit.SECONDS);
            assertFalse(policy.commit(captured,()->fail("Commit after disable")));
        }
    }
    @Test void disablingPreservesAnAlreadyCommittedPendingSendForLaterRestoration(){
        var policy=new ConcealmentPolicy();var cache=new HiddenBlockCache();
        UUID player=UUID.randomUUID(),world=UUID.randomUUID();BlockPosition position=new BlockPosition(1,20,2);
        long[] token={0};assertTrue(policy.commit(policy.snapshot(),()->token[0]=cache.reserve(player,world,position.chunkKey(),Set.of(position),128,256)));
        policy.configure(policy.snapshot().settings().withEnabled(false));
        assertEquals(1,cache.size());assertTrue(cache.takeAll(player,world,128).isEmpty());
        cache.activate(player,world,position.chunkKey(),token[0]);
        assertEquals(List.of(position),cache.takeAll(player,world,128));assertEquals(0,cache.size());
    }
    @Test void shutdownStillIncludesAlreadyCommittedPendingSends(){
        var policy=new ConcealmentPolicy();var cache=new HiddenBlockCache();UUID player=UUID.randomUUID(),world=UUID.randomUUID();
        BlockPosition position=new BlockPosition(1,20,2);
        assertTrue(policy.commit(policy.snapshot(),()->cache.reserve(player,world,position.chunkKey(),Set.of(position),128,256)));
        policy.stop();assertEquals(List.of(position),cache.takeAllIncludingPending(player,world,128));
    }
}
