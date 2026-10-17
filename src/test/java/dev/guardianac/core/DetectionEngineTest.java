/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import dev.guardianac.checks.CheckCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DetectionEngineTest {
    private static final UUID PLAYER = new UUID(1, 2);
    private static Check rule(String id, String family, boolean hard, int required, double weight) {
        return new Check(id, id, id, family, Sample.Kind.PACKET, hard, hard ? .995 : .75, required, weight, "fixture", s -> s.has(id));
    }
    private static Sample sample(UUID player, long time, String... flags) {
        return new Sample(player, time, time / 50, Sample.Kind.PACKET, Map.of("tps", 20d, "ping", 25d), Set.of(flags));
    }
    private static Sample sample(long time, String... flags) { return sample(PLAYER, time, flags); }
    private static DetectionEngine engine(Check... rules) { return new DetectionEngine(List.of(rules), 8); }
    @Test void requiresRepeatedSamplesAndRejectsBurstsWithinCooldown() {
        DetectionEngine engine = engine(rule("a", "A", true, 4, 8));
        assertTrue(engine.accept(sample(1000, "a")).findings().isEmpty());
        for (int i = 1; i < 500; i++) assertTrue(engine.accept(sample(1000 + i, "a")).findings().isEmpty());
        assertTrue(engine.accept(sample(1500, "a")).findings().isEmpty());
        assertTrue(engine.accept(sample(2000, "a")).findings().isEmpty());
        var result = engine.accept(sample(2500, "a"));
        assertEquals(1, result.findings().size()); assertEquals(4, result.findings().getFirst().occurrences());
        assertFalse(result.banRecommended());
    }
    @Test void disabledChecksDoNotRecordOrContributeToBans() {
        DetectionEngine engine = engine(rule("a", "A", true, 1, 40), rule("b", "B", true, 1, 40));
        engine.enabled(id -> !id.equals("b"));
        DetectionEngine.Evaluation result = null;
        for (int i = 0; i < 20; i++) result = engine.accept(sample(1000 + i * 600, "a", "b"));
        assertNotNull(result); assertFalse(result.banRecommended()); assertEquals(1, result.independentFamilies());
        assertTrue(engine.current(PLAYER).stream().noneMatch(f -> f.checkId().equals("b")));
        engine.enabled(id -> false);
        result = engine.accept(sample(20000, "a", "b"));
        assertEquals(0, result.independentFamilies()); assertEquals(0, result.totalVl());
    }
    @Test void correlatedChecksNeverCountAsIndependentFamilies() {
        DetectionEngine engine = engine(rule("a", "SAME", true, 1, 40), rule("b", "SAME", true, 1, 40));
        DetectionEngine.Evaluation result = null;
        for (int i = 0; i < 20; i++) result = engine.accept(sample(1000 + i * 600, "a", "b"));
        assertNotNull(result); assertEquals(1, result.independentFamilies()); assertFalse(result.banRecommended());
    }
    @Test void heuristicsCannotBanEvenWithManyIndependentFamilies() {
        DetectionEngine engine = engine(rule("a", "A", false, 1, 100), rule("b", "B", false, 1, 100), rule("c", "C", false, 1, 100));
        DetectionEngine.Evaluation result = null;
        for (int i = 0; i < 100; i++) result = engine.accept(sample(1000 + i * 600, "a", "b", "c"));
        assertNotNull(result); assertEquals(0, result.independentFamilies()); assertFalse(result.banRecommended());
    }
    @Test void twoSustainedHardFamiliesCanRecommendButNotBeforeTemporalGate() {
        DetectionEngine engine = engine(rule("a", "A", true, 1, 30), rule("b", "B", true, 1, 30));
        for (int i = 0; i < 5; i++) assertFalse(engine.accept(sample(1000 + i * 600, "a", "b")).banRecommended());
        var result = engine.accept(sample(4000, "a", "b"));
        assertEquals(2, result.independentFamilies()); assertTrue(result.banRecommended());
        assertFalse(engine.accept(sample(4600, "a", "b", "exempt")).banRecommended());
        assertFalse(engine.accept(sample(5200, "a", "b", "bypass")).banRecommended());
    }
    @Test void lowerConfidenceHardRuleCannotContributeToAutomaticBan() {
        Check low = new Check("b", "b", "b", "B", Sample.Kind.PACKET, true, .98, 1, 100, "fixture", s -> s.has("b"));
        DetectionEngine engine = engine(rule("a", "A", true, 1, 100), low);
        for (int i = 0; i < 20; i++) assertFalse(engine.accept(sample(1000 + i * 600, "a", "b")).banRecommended());
    }
    @Test void staleAndDuplicateReplayDoesNotAddEvidence() {
        DetectionEngine engine = engine(rule("a", "A", true, 1, 8));
        var first = engine.accept(sample(2000, "a")); assertEquals(1, first.findings().size());
        assertTrue(engine.accept(sample(1000, "a")).findings().isEmpty());
        assertTrue(engine.accept(sample(2000, "a")).findings().isEmpty());
        assertEquals(1, engine.current(PLAYER).getFirst().occurrences());
        assertEquals(1, engine.accept(sample(2600, "a")).findings().size());
    }
    @Test void violationLevelDecaysDuringQuietPeriod() {
        DetectionEngine engine = engine(rule("a", "A", true, 1, 20), rule("b", "B", true, 1, 20));
        DetectionEngine.Evaluation result = null;
        for (int i = 0; i < 6; i++) result = engine.accept(sample(1000 + i * 600, "a", "b"));
        assertNotNull(result); double previous = result.totalVl(); assertTrue(previous > 0);
        var after = engine.accept(sample(14000));
        assertEquals(previous - 5, after.totalVl(), .000001);
    }
    @Test void evidenceExpiresAndAccumulationMustStartFresh() {
        DetectionEngine engine = engine(rule("a", "A", true, 4, 8));
        for (int i = 0; i < 4; i++) engine.accept(sample(1000 + i * 600, "a"));
        assertFalse(engine.current(PLAYER).isEmpty());
        assertTrue(engine.accept(sample(122000, "a")).findings().isEmpty(), "Expired buffer must reset");
        assertTrue(engine.accept(sample(122600, "a")).findings().isEmpty());
        assertTrue(engine.accept(sample(123200, "a")).findings().isEmpty());
        var fresh = engine.accept(sample(123800, "a"));
        assertEquals(1, fresh.findings().size()); assertEquals(4, fresh.findings().getFirst().occurrences());
    }
    @Test void playerBoundsFailOpenAndResetReleasesCapacity() {
        DetectionEngine engine = new DetectionEngine(List.of(rule("a", "A", true, 1, 8)), 1);
        UUID other = new UUID(3, 4);
        assertEquals(1, engine.accept(sample(PLAYER, 1000, "a")).findings().size());
        assertTrue(engine.accept(sample(other, 1000, "a")).findings().isEmpty());
        assertTrue(engine.current(other).isEmpty());
        engine.reset(PLAYER);
        assertEquals(1, engine.accept(sample(other, 2000, "a")).findings().size());
        engine.clear(); assertTrue(engine.current(other).isEmpty());
    }
    @Test void violationLevelIsBounded() {
        DetectionEngine engine = engine(rule("a", "A", true, 1, 100));
        for (int i = 0; i < 100; i++) engine.accept(sample(1000 + i * 600, "a"));
        assertEquals(500, engine.current(PLAYER).getFirst().violationLevel());
    }
    @Test void relaxationCanOnlyIncreaseHeuristicEvidenceRequirement() {
        DetectionEngine engine = engine(rule("a", "A", false, 4, 8));
        engine.relaxation(-99);
        for (int i = 0; i < 3; i++) assertTrue(engine.accept(sample(1000 + i * 600, "a")).findings().isEmpty());
        assertFalse(engine.accept(sample(2800, "a")).findings().isEmpty());
        engine.clear(); engine.relaxation(Double.NaN);
        for (int i = 0; i < 11; i++) assertTrue(engine.accept(sample(1000 + i * 600, "a")).findings().isEmpty());
        assertFalse(engine.accept(sample(7600, "a")).findings().isEmpty());
    }
    @Test void relaxedPresetRequiresMoreSamplesThanStrict() {
        DetectionEngine engine = engine(rule("a", "A", false, 4, 8)); engine.preset(Preset.RELAXED);
        for (int i = 0; i < 5; i++) assertTrue(engine.accept(sample(1000 + i * 600, "a")).findings().isEmpty());
        assertFalse(engine.accept(sample(4000, "a")).findings().isEmpty());
    }
    @Test void disablingAutomaticTuningImmediatelyRestoresNormalRequirement() {
        DetectionEngine engine=engine(rule("a","A",false,4,8));
        engine.automaticTuning(true,18);
        for(int i=0;i<4;i++)assertTrue(engine.accept(sample(1000+i*600,"a")).findings().isEmpty());
        engine.automaticTuning(false,18);
        assertFalse(engine.accept(sample(3400,"a")).findings().isEmpty());
    }
    @Test void automaticTuningRecoversAtTpsBoundaryAndUnknownTpsIsCautious() {
        for(double tps:new double[]{18.999,Double.NaN,Double.POSITIVE_INFINITY}) {
            DetectionEngine engine=engine(rule("a","A",false,4,8));engine.automaticTuning(true,tps);
            for(int i=0;i<4;i++)assertTrue(engine.accept(sample(1000+i*600,"a")).findings().isEmpty());
            engine.automaticTuning(true,19);
            assertFalse(engine.accept(sample(3400,"a")).findings().isEmpty());
        }
    }
    @Test void automaticTuningNeverChangesHardInvariantRequirements() {
        DetectionEngine engine=engine(rule("a","A",true,4,8));engine.automaticTuning(true,10);
        for(int i=0;i<3;i++)assertTrue(engine.accept(sample(1000+i*600,"a")).findings().isEmpty());
        assertFalse(engine.accept(sample(2800,"a")).findings().isEmpty());
    }
    @Test void positionAndRotationWithinMovementPacketCannotManufactureIndependence() {
        DetectionEngine engine = new DetectionEngine(CheckCatalog.create(), 1);
        DetectionEngine.Evaluation result = null;
        for (int i = 0; i < 14; i++) result = engine.accept(new Sample(PLAYER, 1000 + i * 600, i * 12,
            Sample.Kind.PACKET, Map.of("x", Double.NaN, "y", 64d, "z", 0d, "yaw", Double.NaN, "pitch", 0d), Set.of("position-present", "rotation-present")));
        assertNotNull(result); assertEquals(1, result.independentFamilies()); assertFalse(result.banRecommended());
    }
    @Test void unrelatedPacketTypesCannotEraseRepeatedHardEvidence() {
        DetectionEngine engine = engine(rule("a", "A", true, 2, 8));
        engine.accept(sample(1000, "a"));
        for (int i = 0; i < 4; i++) engine.accept(sample(1100 + 100 * i));
        assertFalse(engine.accept(sample(2000, "a")).findings().isEmpty());
        assertFalse(engine.accept(sample(2600, "a")).findings().isEmpty());
    }
    @Test void queuedPacketEvidenceSurvivesNewerMainThreadMovement() {
        DetectionEngine engine=new DetectionEngine(CheckCatalog.create(),1);
        DetectionEngine.Evaluation result=null;
        for(int i=0;i<20;i++) {
            long time=1000+i*600L;
            engine.accept(Sample.builder(PLAYER,Sample.Kind.MOVE,time+49,i).value("tps",20).value("ping",0).build());
            engine.accept(Sample.builder(PLAYER,Sample.Kind.PACKET,time+5,i).flag("held-slot-present").value("held-slot",15).build());
            result=engine.accept(Sample.builder(PLAYER,Sample.Kind.PACKET,time+6,i).flag("rotation-present").value("yaw",0).value("pitch",100).build());
            for(int n=7;n<20;n++)engine.accept(Sample.builder(PLAYER,Sample.Kind.PACKET,time+n,i).build());
        }
        assertNotNull(result);assertTrue(result.banRecommended());assertEquals(2,result.independentFamilies());
        assertTrue(engine.current(PLAYER).stream().anyMatch(f->f.checkId().equals("packet.held-slot")));
        assertTrue(engine.accept(Sample.builder(PLAYER,Sample.Kind.ATTACK,1000,1).build()).findings().isEmpty());
    }
}
