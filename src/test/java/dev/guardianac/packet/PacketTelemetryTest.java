/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.packet;

import dev.guardianac.core.Sample;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PacketTelemetryTest {
    private final UUID player=UUID.randomUUID(),world=UUID.randomUUID();
    private PacketContext context(long now){return new PacketContext(world,now,25,20,50,Set.of(),false,10,-64,320);}
    @Test void contextDefensivelyCopiesFlags(){var flags=new HashSet<String>();flags.add("vehicle");var c=new PacketContext(world,100,2,20,5,flags,false,0,-64,320);flags.clear();assertTrue(c.flags().contains("vehicle"));assertThrows(UnsupportedOperationException.class,()->c.flags().add("flight"));}
    @Test void snapshotFreshnessNeverAcceptsFutureOrStale(){assertTrue(context(100).fresh(599));assertFalse(context(100).fresh(601));assertFalse(context(100).fresh(99));}
    @Test void staleAndTranslatedSamplesSuppressAllEvidence(){var t=new PacketTelemetry();assertTrue(t.sample(player,context(100),1000,1000,false).build().has("exempt"));assertTrue(t.sample(player,context(100),100,100,true).build().has("exempt"));}
    @Test void legalPositionAndRotationAreAccepted(){assertFalse(PacketTelemetry.invalidPosition(-30_000_000,-64,30_000_000));assertFalse(PacketTelemetry.invalidRotation(100_000,-90));assertFalse(PacketTelemetry.invalidRotation(0,90));}
    @Test void nonfiniteOrImpossibleCoordinatesReject(){for(double d:List.of(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY))assertTrue(PacketTelemetry.invalidPosition(d,0,0));assertTrue(PacketTelemetry.invalidPosition(30_000_002,0,0));assertTrue(PacketTelemetry.invalidPosition(0,20_000_001,0));}
    @Test void nonfiniteAndOutOfRangeRotationsReject(){assertTrue(PacketTelemetry.invalidRotation(Float.NaN,0));assertTrue(PacketTelemetry.invalidRotation(0,Float.POSITIVE_INFINITY));assertTrue(PacketTelemetry.invalidRotation(0,91));}
    @Test void normalTwentyHertzMovementStaysInsideTimerBudget(){var t=new PacketTelemetry();for(long ms=0;ms<=12_000;ms+=50)assertFalse(t.record(ms,true,false,false));Sample s=t.sample(player,context(12_000),12_000,12_000,false).build();assertEquals(10_000,s.v("movement-window-ms"));assertTrue(s.v("movement-count")<=201);assertFalse(s.v("movement-count")>s.v("movement-window-ms")/50*1.3);}
    @Test void sustainedTimerProducesRealWindowEvidence(){var t=new PacketTelemetry();for(long ms=0;ms<=6000;ms+=25)t.record(ms,true,false,false);Sample s=t.sample(player,context(6000),6000,6000,false).build();assertTrue(s.v("movement-window-ms")>=5000);assertTrue(s.v("movement-count")>s.v("movement-window-ms")/50*1.3);}
    @Test void blinkRetainsGapAcrossBurst(){var t=new PacketTelemetry();t.record(100,true,false,false);for(int n=0;n<45;n++)t.record(3000+n,true,false,false);Sample s=t.sample(player,context(3050),3050,3050,false).build();assertEquals(2900,s.v("previous-gap-ms"));assertEquals(45,s.v("movement-burst"));}
    @Test void packetAndInteractionFloodsCancelWithoutUnboundedCounters(){var t=new PacketTelemetry();for(int i=0;i<400;i++)assertFalse(t.record(100,false,false,true));assertTrue(t.record(100,false,false,true));var all=new PacketTelemetry();for(int i=0;i<2000;i++)assertFalse(all.record(100,false,false,false));assertTrue(all.record(100,false,false,false));}
    @Test void emissionIsBoundedAndInvalidHasIndependentSlot(){var t=new PacketTelemetry();assertTrue(t.shouldEmit(100,false));assertFalse(t.shouldEmit(101,false));assertTrue(t.shouldEmit(101,true));assertFalse(t.shouldEmit(102,true));assertTrue(t.shouldEmit(150,false));assertTrue(t.shouldEmit(201,true));}
    @Test void rollingWindowExpiresAndHandlesNegativeMonotonicOrigin(){var r=new RateWindow(20,50);r.add(-1);r.add(0);assertEquals(2,r.count(10));assertEquals(0,r.count(1100));r.add(1_000_000);assertEquals(1,r.count(1_000_000));}
    @Test void snapshotsCarryWorldIdentity(){var t=new PacketTelemetry();assertTrue(t.sample(player,context(100),100,100,false).build().has("world:"+world));}
    @Test void monotonicClockMayHaveNegativeOrigin(){var t=new PacketTelemetry();for(long n=-12000;n<=-6000;n+=50)t.record(n,true,false,false);Sample s=t.sample(player,context(100),100,-6000,false).build();assertEquals(6000,s.v("movement-window-ms"));assertEquals(121,s.v("movement-count"));}
}
