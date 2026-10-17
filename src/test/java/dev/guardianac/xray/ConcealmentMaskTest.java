/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConcealmentMaskTest {
    private int[] stone(){int[] b=new int[32*256];Arrays.fill(b,1);return b;}
    private int index(int x,int y,int z){return y*256+z*16+x;}
    private BitSet mask(int[] b,boolean caves,int limit,double playerX){return ConcealmentMask.select(b,-32,0,64,0,0,playerX,100,100,8,caves,limit,id->id==2,id->id==1||id==2,id->id==0);}
    @Test void enclosesOreWithoutMutatingSnapshot(){int[] b=stone();int i=index(8,8,8);b[i]=2;int[] before=b.clone();assertTrue(mask(b,false,100,100).get(i));assertArrayEquals(before,b);}
    @Test void airExposureLeavesOreVisible(){int[] b=stone();int i=index(8,8,8);b[i]=2;b[i+1]=0;assertFalse(mask(b,false,100,100).get(i));}
    @Test void allSixFacesMustBeOpaque(){for(int offset:new int[]{1,-1,16,-16,256,-256}){int[] b=stone();int i=index(8,8,8);b[i]=2;b[i+offset]=3;assertFalse(mask(b,false,100,100).get(i));}}
    @Test void unknownChunkBordersNeverHide(){int[] b=stone();for(int[] p:new int[][]{{0,8,8},{15,8,8},{8,0,8},{8,31,8},{8,8,0},{8,8,15}})b[index(p[0],p[1],p[2])]=2;assertTrue(mask(b,false,100,100).isEmpty());}
    @Test void nearbyOreIsNotMasked(){int[] b=stone();int i=index(8,8,8);b[i]=2;BitSet result=ConcealmentMask.select(b,-32,0,64,0,0,8.5,-23.5,8.5,8,false,100,id->id==2,id->id!=0,id->id==0);assertFalse(result.get(i));}
    @Test void negativeChunkCoordinatesMatchPlayerDistance(){int[] b=stone();int i=index(8,8,8);b[i]=2;BitSet result=ConcealmentMask.select(b,-32,0,64,-1,-1,-7.5,-23.5,-7.5,8,false,100,id->id==2,id->id!=0,id->id==0);assertTrue(result.isEmpty());}
    @Test void caveMaskRequiresExplicitOptIn(){int[] b=stone();int i=index(8,8,8);b[i]=0;assertFalse(mask(b,false,100,100).get(i));assertTrue(mask(b,true,100,100).get(i));}
    @Test void budgetOverflowAbandonsWholeMask(){int[] b=stone();b[index(4,8,4)]=2;b[index(8,8,8)]=2;assertNull(mask(b,false,1,100));}
    @Test void heightLimitHonored(){int[] b=stone();int i=index(8,25,8);b[i]=2;BitSet result=ConcealmentMask.select(b,-32,0,-16,0,0,100,100,100,8,false,100,id->id==2,id->id!=0,id->id==0);assertFalse(result.get(i));}
    @Test void unreasonableDimensionsRejected(){assertThrows(IllegalArgumentException.class,()->ConcealmentMask.select(new int[1],-64,320,64,0,0,0,0,0,8,false,100,id->true,id->true,id->false));}
}
