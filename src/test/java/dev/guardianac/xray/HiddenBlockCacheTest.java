/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HiddenBlockCacheTest {
    private final UUID player=UUID.randomUUID(),world=UUID.randomUUID();
    private final BlockPosition p=new BlockPosition(1,-20,2);
    @Test void nearbyTakeIsOneShotAndWorldScoped(){var c=new HiddenBlockCache();assertTrue(c.replace(player,world,p.chunkKey(),Set.of(p),10,20));assertTrue(c.takeNearby(player,UUID.randomUUID(),1,-20,2,8,10).isEmpty());assertEquals(List.of(p),c.takeNearby(player,world,1,-20,2,8,10));assertTrue(c.takeNearby(player,world,1,-20,2,8,10).isEmpty());assertEquals(0,c.size());}
    @Test void realUpdatePreventsStaleRestoration(){var c=new HiddenBlockCache();c.replace(player,world,p.chunkKey(),Set.of(p),10,20);c.update(player,p);assertTrue(c.takeAll(player,world,20).isEmpty());assertEquals(0,c.size());}
    @Test void replacementDoesNotAccumulateOldEntries(){var c=new HiddenBlockCache();c.replace(player,world,p.chunkKey(),Set.of(p),10,20);c.replace(player,world,p.chunkKey(),Set.of(new BlockPosition(2,-20,2)),10,20);assertEquals(1,c.size());}
    @Test void capacityFailureClearsSupersededColumn(){var c=new HiddenBlockCache();c.replace(player,world,p.chunkKey(),Set.of(p),10,20);assertFalse(c.replace(player,world,p.chunkKey(),Set.of(p,new BlockPosition(3,-20,2)),1,20));assertEquals(0,c.size());}
    @Test void globalCapacityIsSharedAcrossPlayers(){var c=new HiddenBlockCache();assertTrue(c.replace(player,world,p.chunkKey(),Set.of(p),10,1));assertFalse(c.replace(UUID.randomUUID(),world,p.chunkKey(),Set.of(p),10,1));assertEquals(1,c.size());}
    @Test void worldSwitchAndUnloadReleaseMemory(){var c=new HiddenBlockCache();c.replace(player,world,p.chunkKey(),Set.of(p),10,20);UUID next=UUID.randomUUID();c.replace(player,next,p.chunkKey(),Set.of(p),10,20);assertEquals(1,c.size());assertTrue(c.takeAll(player,world,20).isEmpty());c.unload(player,p.chunkKey());assertEquals(0,c.size());assertEquals(0,c.playerCount());}
    @Test void boundedTakeAndForget(){var c=new HiddenBlockCache();c.replace(player,world,p.chunkKey(),Set.of(p,new BlockPosition(3,-20,2)),10,20);assertEquals(1,c.takeAll(player,world,1).size());assertEquals(1,c.size());c.forget(player);assertEquals(0,c.size());}
    @Test void concurrentAdmissionNeverExceedsGlobalLimit()throws Exception{var c=new HiddenBlockCache();try(var executor=Executors.newFixedThreadPool(8)){List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<1000;i++)jobs.add(executor.submit(()->c.replace(UUID.randomUUID(),world,p.chunkKey(),Set.of(p),1,100)));for(var job:jobs)job.get();}assertEquals(100,c.size());}
    @Test void pendingChunkCannotRevealBeforeSuccessfulSend(){var c=new HiddenBlockCache();long token=c.reserve(player,world,p.chunkKey(),Set.of(p),10,20);assertTrue(token>0);assertTrue(c.takeAll(player,world,20).isEmpty());c.activate(player,world,p.chunkKey(),token);assertEquals(List.of(p),c.takeAll(player,world,20));}
    @Test void staleCompletionCannotActivateReplacementOrResurrectUnloadedChunk(){var c=new HiddenBlockCache();long first=c.reserve(player,world,p.chunkKey(),Set.of(p),10,20);long second=c.reserve(player,world,p.chunkKey(),Set.of(p),10,20);c.activate(player,world,p.chunkKey(),first);assertTrue(c.takeAll(player,world,20).isEmpty());c.unload(player,p.chunkKey());c.activate(player,world,p.chunkKey(),second);assertEquals(0,c.size());}
}
