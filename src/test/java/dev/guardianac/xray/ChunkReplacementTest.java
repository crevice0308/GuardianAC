/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkReplacementTest {
    @Test void headerReadsExactlyEightBytesWithoutAdvancingPacketCursor(){
        ByteBuffer bytes=ByteBuffer.allocate(20);bytes.position(3);bytes.putInt(-120).putInt(42);bytes.position(3);
        AtomicInteger reads=new AtomicInteger();
        long key=ChunkConcealment.columnKey(bytes.remaining(),offset->{reads.incrementAndGet();return bytes.get(3+offset);});
        assertEquals(HiddenBlockCache.chunkKey(-120,42),key);assertEquals(8,reads.get());assertEquals(3,bytes.position());
    }
    @Test void signedExtremeCoordinatesRemainExact(){
        for(int x:new int[]{Integer.MIN_VALUE,-1,0,1,Integer.MAX_VALUE})for(int z:new int[]{Integer.MIN_VALUE,-1,0,1,Integer.MAX_VALUE}){
            byte[] bytes=ByteBuffer.allocate(8).putInt(x).putInt(z).array();
            assertEquals(HiddenBlockCache.chunkKey(x,z),ChunkConcealment.columnKey(8,i->bytes[i]));
        }
    }
    @Test void truncatedHeaderDoesNotReadOrGuessAColumn(){
        assertThrows(IllegalArgumentException.class,()->ChunkConcealment.columnKey(7,i->{fail("Read truncated coordinates");return 0;}));
    }
    @Test void everyUnmodifiedReplacementDiscardsOnlyItsSupersededColumn(){
        // All policy, absence, resource, stale-generation, and parsed-failure paths share this finalizer.
        var concealment=new ChunkConcealment();var cache=concealment.cache();UUID player=UUID.randomUUID(),world=UUID.randomUUID();
        BlockPosition replaced=new BlockPosition(1,20,2),other=new BlockPosition(17,20,2);
        long old=cache.reserve(player,world,replaced.chunkKey(),Set.of(replaced),128,256);
        long pending=cache.reserve(player,world,other.chunkKey(),Set.of(other),128,256);
        concealment.completeColumn(player,replaced.chunkKey(),false);
        cache.activate(player,world,replaced.chunkKey(),old);assertEquals(1,cache.size());
        assertTrue(cache.takeAll(player,world,128).isEmpty());
        cache.activate(player,world,other.chunkKey(),pending);assertEquals(List.of(other),cache.takeAll(player,world,128));
    }
    @Test void successfulMaskedReplacementRetainsItsPendingRestoration(){
        var concealment=new ChunkConcealment();var cache=concealment.cache();UUID player=UUID.randomUUID(),world=UUID.randomUUID();
        BlockPosition position=new BlockPosition(1,20,2);long token=cache.reserve(player,world,position.chunkKey(),Set.of(position),128,256);
        concealment.completeColumn(player,position.chunkKey(),true);assertEquals(1,cache.size());
        assertTrue(cache.takeAll(player,world,128).isEmpty());cache.activate(player,world,position.chunkKey(),token);
        assertEquals(List.of(position),cache.takeAll(player,world,128));
    }
}
