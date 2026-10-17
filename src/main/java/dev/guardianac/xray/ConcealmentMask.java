/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import java.util.BitSet;
import java.util.function.IntPredicate;

/** Pure mask selection over a caller-owned immutable column snapshot. Unknown borders stay visible. */
public final class ConcealmentMask {
    private ConcealmentMask() { }
    public static BitSet select(int[] blocks,int minY,int maxY,int maximumY,int chunkX,int chunkZ,
                                double playerX,double playerY,double playerZ,int radius,
                                boolean caves,int limit,IntPredicate hidden,IntPredicate opaque,
                                IntPredicate air) {
        int height=maxY-minY;
        if(height<1||height>1024||blocks.length!=height*256) throw new IllegalArgumentException("Invalid column dimensions");
        BitSet result=new BitSet(blocks.length);
        long radiusSquared=(long)radius*radius;
        int selected=0;
        for(int y=1;y<height-1&&minY+y<=maximumY;y++) for(int z=1;z<15;z++) for(int x=1;x<15;x++) {
            int index=y*256+z*16+x,id=blocks[index];
            boolean cave=caves&&minY+y<0&&air.test(id);
            if(!hidden.test(id)&&!cave) continue;
            double dx=chunkX*16.0+x+.5-playerX,dy=minY+y+.5-playerY,dz=chunkZ*16.0+z+.5-playerZ;
            if(dx*dx+dy*dy+dz*dz<=radiusSquared) continue;
            if(!cave&&(!opaque.test(blocks[index-1])||!opaque.test(blocks[index+1])
                ||!opaque.test(blocks[index-16])||!opaque.test(blocks[index+16])
                ||!opaque.test(blocks[index-256])||!opaque.test(blocks[index+256]))) continue;
            result.set(index);
            if(++selected>limit) return null; // atomic fail-open; never partially mask without restoration budget
        }
        return result;
    }
}
