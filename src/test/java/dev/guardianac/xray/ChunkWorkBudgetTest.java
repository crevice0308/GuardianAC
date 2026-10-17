/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkWorkBudgetTest {
    private static BaseChunk[] column(int id) {return new BaseChunk[]{new Chunk_v1_18(ClientVersion.V_26_3,4096,0,
        new DataPalette(new SingletonPalette(id),null,PaletteType.CHUNK),new DataPalette(new SingletonPalette(0),null,PaletteType.BIOME))};}
    @Test void sixteenJoinBurstsDoNotSpendOreBudgetOn1280EmptyColumns() {
        ChunkWorkBudget budget=new ChunkWorkBudget();BaseChunk[] empty=column(1),ore=column(2);
        int admitted=0;
        for(int client=0;client<16;client++) {
            for(int n=0;n<80;n++)assertEquals(ChunkWorkBudget.Decision.NO_TARGETS,budget.admit(1000,128,
                ()->PalettePrefilter.mayContainTargets(empty,0,16,64,true,false,id->id==2,id->id==0)));
            if(budget.admit(1000,128,()->PalettePrefilter.mayContainTargets(ore,0,16,64,true,false,id->id==2,id->id==0))==ChunkWorkBudget.Decision.ADMITTED)admitted++;
        }
        assertEquals(16,admitted);
        for(int i=16;i<128;i++)assertEquals(ChunkWorkBudget.Decision.ADMITTED,budget.admit(1000,128,()->true));
        assertEquals(ChunkWorkBudget.Decision.LIMITED,budget.admit(1000,128,()->true));
    }
    @Test void emptyColumnsStayFreeEvenWhenHeavyBudgetExhausted() {
        ChunkWorkBudget budget=new ChunkWorkBudget();assertEquals(ChunkWorkBudget.Decision.ADMITTED,budget.admit(1000,1,()->true));
        assertEquals(ChunkWorkBudget.Decision.NO_TARGETS,budget.admit(1000,1,()->false));
        assertEquals(ChunkWorkBudget.Decision.LIMITED,budget.admit(1000,1,()->true));
    }
    @Test void rollingBudgetRenewsAndAppliesConfigurationLimitChanges() {
        ChunkWorkBudget budget=new ChunkWorkBudget();
        assertEquals(ChunkWorkBudget.Decision.ADMITTED,budget.admit(1000,2,()->true));
        assertEquals(ChunkWorkBudget.Decision.LIMITED,budget.admit(1001,1,()->true));
        assertEquals(ChunkWorkBudget.Decision.LIMITED,budget.admit(1999,1,()->true));
        assertEquals(ChunkWorkBudget.Decision.ADMITTED,budget.admit(2000,1,()->true));
    }
    @Test void concurrentCallbacksCannotOverrunGlobalBudget() throws Exception {
        ChunkWorkBudget budget=new ChunkWorkBudget();AtomicInteger admitted=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(16)) {
            var results=new ArrayList<Future<?>>();
            for(int i=0;i<512;i++)results.add(executor.submit(()->{if(budget.admit(1000,128,()->true)==ChunkWorkBudget.Decision.ADMITTED)admitted.incrementAndGet();}));
            for(Future<?> result:results)result.get(5,TimeUnit.SECONDS);
        }
        assertEquals(128,admitted.get());
    }
}
