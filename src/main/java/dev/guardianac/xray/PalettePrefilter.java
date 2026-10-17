/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.*;
import java.util.function.IntPredicate;

/** Read-only absence proof. Uncertain/expensive palettes always enter the bounded full rewrite path. */
final class PalettePrefilter {
    static final int MAX_ENTRIES_PER_COLUMN=2048;
    private PalettePrefilter() { }

    static boolean mayContainTargets(BaseChunk[] sections,int minY,int maxY,int maximumY,
                                     boolean ores,boolean caves,IntPredicate ore,IntPredicate air) {
        if(!ores&&!caves)return false;
        int height=maxY-minY;
        if(height<16||height>1024||height%16!=0||sections.length!=height/16)return true;
        int highest=Math.min(maxY-2,maximumY),inspected=0;
        for(int section=0;section<sections.length;section++) {
            int low=Math.max(minY+1,minY+section*16),high=Math.min(highest,minY+section*16+15);
            if(low>high)continue;
            boolean caveSection=caves&&low<0;
            if(!ores&&!caveSection)continue;
            if(!(sections[section] instanceof Chunk_v1_18 chunk))return true;
            DataPalette data=chunk.getChunkData();
            if(data==null)return true;
            Palette palette=data.palette;
            // GlobalPalette.size() is Integer.MAX_VALUE in PacketEvents 2.14. Never enumerate it.
            if(!(palette instanceof SingletonPalette||palette instanceof ListPalette||palette instanceof MapPalette))return true;
            int size=palette.size();
            if(size<1||size>256)return true;
            for(int index=0;index<size;index++) {
                if(inspected++>=MAX_ENTRIES_PER_COLUMN)return true;
                int state=palette.idToState(index);
                if((ores&&ore.test(state))||(caveSection&&air.test(state)))return true;
            }
        }
        return false;
    }
}
