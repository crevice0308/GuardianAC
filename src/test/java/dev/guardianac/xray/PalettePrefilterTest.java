/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.*;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PalettePrefilterTest {
    private static Chunk_v1_18 section(Palette palette) {
        return new Chunk_v1_18(ClientVersion.V_26_3,4096,0,
            new DataPalette(palette,palette instanceof SingletonPalette?null:new BitStorage(Math.max(4,palette.getBits()),4096),PaletteType.CHUNK),
            new DataPalette(new SingletonPalette(0),null,PaletteType.BIOME));
    }
    private static boolean targets(BaseChunk[] chunks,int minY,int maximumY,boolean ores,boolean caves) {
        return PalettePrefilter.mayContainTargets(chunks,minY,minY+chunks.length*16,maximumY,ores,caves,id->id==2,id->id==0);
    }
    @Test void actualSingletonPaletteProvesAbsenceAndFindsOre() {
        assertFalse(targets(new BaseChunk[]{section(new SingletonPalette(1))},0,64,true,false));
        assertTrue(targets(new BaseChunk[]{section(new SingletonPalette(2))},0,64,true,false));
    }
    @Test void actualListAndMapPalettesProveAbsenceAndFindFinalEntry() {
        for(Palette palette:new Palette[]{new ListPalette(4),new MapPalette(8)}) {
            palette.stateToId(1);palette.stateToId(3);
            BaseChunk[] chunks={section(palette)};
            assertFalse(targets(chunks,0,64,true,false));
            palette.stateToId(2);assertTrue(targets(chunks,0,64,true,false));
        }
    }
    @Test void actualGlobalPaletteNeverEnumeratedAsLocalAbsence() {
        assertEquals(Integer.MAX_VALUE,GlobalPalette.INSTANCE.size());
        AtomicInteger inspected=new AtomicInteger();
        assertTrue(PalettePrefilter.mayContainTargets(new BaseChunk[]{section(GlobalPalette.INSTANCE)},0,16,64,true,false,
            id->{inspected.incrementAndGet();return false;},id->false));
        assertEquals(0,inspected.get());
    }
    @Test void unknownPaletteCannotProveAbsence() {
        Palette unknown=new Palette(){public int size(){return 1;}public int stateToId(int state){return 0;}
            public int idToState(int id){fail("Unknown palette must not be traversed");return 0;}public int getBits(){return 4;}};
        assertTrue(targets(new BaseChunk[]{section(unknown)},0,64,true,false));
    }
    @Test void missingMalformedAndOversizedPalettesRemainCandidates() {
        assertTrue(targets(new BaseChunk[]{null},0,64,true,false));
        assertTrue(targets(new BaseChunk[]{section(new ListPalette(4))},0,64,true,false));
        Palette oversized=new MapPalette(9);for(int i=0;i<257;i++)oversized.stateToId(10+i);
        assertTrue(targets(new BaseChunk[]{section(oversized)},0,64,true,false));
        assertTrue(PalettePrefilter.mayContainTargets(new BaseChunk[0],0,16,64,true,false,id->false,id->false));
    }
    @Test void totalInspectionCapReturnsCandidateWithoutUnboundedWalk() {
        MapPalette palette=new MapPalette(8);for(int i=0;i<256;i++)palette.stateToId(100+i);
        BaseChunk[] chunks=new BaseChunk[24];Arrays.fill(chunks,section(palette));
        AtomicInteger inspected=new AtomicInteger();
        assertTrue(PalettePrefilter.mayContainTargets(chunks,-64,320,320,true,false,
            id->{inspected.incrementAndGet();return false;},id->false));
        assertEquals(PalettePrefilter.MAX_ENTRIES_PER_COLUMN,inspected.get());
    }
    @Test void maximumHeightAndExcludedOuterYAreRespectedConservatively() {
        BaseChunk[] chunks={section(new SingletonPalette(1)),section(new SingletonPalette(2))};
        assertFalse(targets(chunks,0,15,true,false));assertTrue(targets(chunks,0,16,true,false));
        assertFalse(targets(chunks,0,0,true,false));
    }
    @Test void caveModeIsSeparateOptInAndOnlyBelowZero() {
        BaseChunk[] air={section(new SingletonPalette(0))};
        assertFalse(targets(air,-16,64,true,false));assertFalse(targets(air,-16,64,false,false));
        assertTrue(targets(air,-16,64,false,true));assertFalse(targets(air,0,64,false,true));
        assertFalse(targets(new BaseChunk[]{section(new SingletonPalette(1))},-16,64,false,true));
    }
    @Test void absentOrePaletteDoesNotReadBlockStorageOrMutateIt() {
        var chunk=section(new SingletonPalette(1));var palette=chunk.getChunkData().palette;
        assertFalse(targets(new BaseChunk[]{chunk},0,64,true,false));
        assertSame(palette,chunk.getChunkData().palette);assertNull(chunk.getChunkData().storage);
    }
    @Test void randomizedAbsenceProofNeverMissesASelectedMask() {
        Random random=new Random(314159);
        for(int round=0;round<100;round++) {
            int minY=round%2==0?-32:0,maximumY=round%3==0?minY+8:64;
            boolean caves=round%4==0,ores=round%5!=0;
            int[] blocks=new int[32*256];Arrays.fill(blocks,1);
            for(int i=0;i<80;i++)blocks[random.nextInt(blocks.length)]=random.nextBoolean()?2:0;
            BaseChunk[] chunks=new BaseChunk[2];
            for(int n=0;n<2;n++) {
                var data=PaletteType.CHUNK.create();
                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)data.set(x,y,z,blocks[(n*16+y)*256+z*16+x]);
                chunks[n]=new Chunk_v1_18(ClientVersion.V_26_3,4096,0,data,PaletteType.BIOME.create());
            }
            BitSet mask=ConcealmentMask.select(blocks,minY,minY+32,maximumY,0,0,100,100,100,8,caves,8192,
                id->ores&&id==2,id->id==1||id==2,id->id==0);
            if(!mask.isEmpty())assertTrue(targets(chunks,minY,maximumY,ores,caves),"False absence in round "+round);
        }
    }
}
