/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import dev.guardianac.core.Sample;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MiningStatisticsTest {
    private final UUID player=UUID.randomUUID(),world=UUID.randomUUID();
    private Sample record(MiningStatistics m,int n,boolean rare){return m.record(player,world,1000+n,1,new BlockPosition(n*10,-20,0),rare,false,20,50,Set.of());}
    @Test void largeNaturalSampleHasLowRareRatio(){var m=new MiningStatistics();Sample s=null;for(int i=0;i<600;i++)s=record(m,i,i%100==0);assertNotNull(s);assertEquals(.01,s.v("rare-ratio"),.00001);assertEquals(600,s.v("mined-blocks"));}
    @Test void abnormalRareRateHasGenuineMeasurements(){var m=new MiningStatistics();Sample s=null;for(int i=0;i<600;i++)s=record(m,i,i%4==0);assertNotNull(s);assertEquals(.25,s.v("rare-ratio"));assertEquals(150,s.v("distinct-rare-veins"));}
    @Test void neighboringOreBlocksCountAsSingleVein(){var m=new MiningStatistics();m.record(player,world,1000,1,new BlockPosition(1,-20,1),true,true,20,0,Set.of());Sample s=m.record(player,world,1100,1,new BlockPosition(2,-20,1),true,true,20,0,Set.of());assertEquals(1,s.v("distinct-rare-veins"));assertEquals(1,s.v("hidden-veins"));}
    @Test void missingHiddenObservationNeverInventsHiddenVeins(){var m=new MiningStatistics();Sample s=null;for(int i=0;i<30;i++)s=record(m,i,true);assertNotNull(s);assertEquals(0,s.v("hidden-veins"));assertEquals(0,s.v("targeted-turn-ratio"));}
    @Test void boundedHistoryAndWorldChangeReset(){var m=new MiningStatistics();for(int i=0;i<5000;i++)record(m,i,false);assertEquals(4096,m.historySize(player));Sample s=m.record(player,UUID.randomUUID(),10_000,1,new BlockPosition(1,0,1),true,false,20,0,Set.of());assertEquals(1,s.v("mined-blocks"));m.forget(player);assertEquals(0,m.historySize(player));}
    @Test void idleSessionResetAndExemptFlagsPersist(){var m=new MiningStatistics();record(m,0,false);Sample s=m.record(player,world,2_000_000,1,new BlockPosition(1,0,1),true,false,20,0,Set.of("exempt"));assertEquals(1,s.v("mined-blocks"));assertTrue(s.has("exempt"));}
    @Test void caveConcealmentIsDisabledByDefaultAndBoundsReject(){assertFalse(XraySettings.defaults().concealCaves());assertTrue(XraySettings.defaults().withConcealCaves(true).concealCaves());assertThrows(IllegalArgumentException.class,()->new XraySettings(true,true,true,true,999,8,4096,32768,524288,128));}
    @Test void miningEvidenceCarriesWorldIdentity(){assertTrue(record(new MiningStatistics(),0,false).has("world:"+world));}
    @Test void masterAndMiningSwitchBothControlObservation(){
        assertTrue(XraySettings.defaults().observesMining());
        assertFalse(XraySettings.defaults().withEnabled(false).observesMining());
        assertFalse(XraySettings.defaults().withMiningAnalysis(false).observesMining());
        assertFalse(XraySettings.defaults().withMiningAnalysis(false).withEnabled(false).withEnabled(true).observesMining());
    }
    @Test void clearDiscardsAllPlayersBeforeObservationIsReenabled(){
        var m=new MiningStatistics();record(m,0,true);UUID second=UUID.randomUUID();
        m.record(second,world,1000,1,new BlockPosition(1,0,1),false,false,20,0,Set.of());
        m.clear();assertEquals(0,m.historySize(player));assertEquals(0,m.historySize(second));
        assertEquals(1,record(m,1,false).v("mined-blocks"));
    }
}
