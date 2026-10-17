/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.stream.NetStreamInput;
import com.github.retrooper.packetevents.protocol.stream.NetStreamOutput;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PacketEvents 2.14.0 palettes/26.3 section serialization, without booting a server. */
@SuppressWarnings("deprecation")
class ChunkPaletteApiTest {
    private Chunk_v1_18 section(){
        var blocks=PaletteType.CHUNK.create();var biomes=PaletteType.BIOME.create();
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)blocks.set(x,y,z,1+(x+y+z)%3);
        for(int y=0;y<4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)biomes.set(x,y,z,(x+y+z)%4);
        return new Chunk_v1_18(ClientVersion.V_26_3,4096,17,blocks,biomes);
    }
    @Test void copyPreservesEveryBlockAndBiome(){var source=section();var copy=ChunkConcealment.copySection(source,ClientVersion.V_26_3);assertNotSame(source,copy);
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)assertEquals(source.getBlockId(x,y,z),copy.getBlockId(x,y,z));
        for(int y=0;y<4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)assertEquals(source.getBiomeData().get(x,y,z),copy.getBiomeData().get(x,y,z));
        assertEquals(4096,copy.getBlockCount());assertEquals(17,copy.getFluidCount());}
    @Test void copyMutationCannotAffectAnotherRecipient(){var source=section();var copy=ChunkConcealment.copySection(source,ClientVersion.V_26_3);int original=source.getBlockId(8,8,8);copy.getChunkData().set(8,8,8,999);copy.getBiomeData().set(0,0,0,7);assertEquals(original,source.getBlockId(8,8,8));assertEquals(0,source.getBiomeData().get(0,0,0));}
    @Test void native263SectionRoundTripPreservesFluidFieldAndPalettes(){var source=section();ByteArrayOutputStream bytes=new ByteArrayOutputStream();Chunk_v1_18.write(new NetStreamOutput(bytes),source,false,true);
        ByteArrayInputStream input=new ByteArrayInputStream(bytes.toByteArray());var parsed=Chunk_v1_18.read(ClientVersion.V_26_3,new NetStreamInput(input),false,true);
        assertEquals(0,input.available());assertEquals(source.getBlockCount(),parsed.getBlockCount());assertEquals(source.getFluidCount(),parsed.getFluidCount());
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)assertEquals(source.getBlockId(x,y,z),parsed.getBlockId(x,y,z));
        for(int y=0;y<4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)assertEquals(source.getBiomeData().get(x,y,z),parsed.getBiomeData().get(x,y,z));}
}
