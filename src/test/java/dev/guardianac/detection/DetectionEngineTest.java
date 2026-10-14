package dev.guardianac.detection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DetectionEngineTest {
    private static final UUID PLAYER = new UUID(1, 1);
    private static final UUID TARGET = new UUID(2, 2);
    private DetectionEngine engine;

    @BeforeEach void createEngine() { engine = new DetectionEngine(DetectionEngine.Settings.defaults()); }

    @Test void normalSprintJumpApexAndLandingNeverProduceFindings() {
        double[] jump = {.42, .333, .248, .165, .083, .003, -.075, -.152, -.228, -.3, 0, 0};
        for (int tick = 0; tick < 240; tick++) {
            int phase = tick % jump.length;
            assertTrue(engine.movement(PLAYER, move(tick, .36, jump[phase], phase >= 10, false)).isEmpty(), "tick " + tick);
        }
    }

    @Test void longNormalFallingNeverBecomesFlyEvidence() {
        for (int tick = 0; tick < 160; tick++) {
            double vertical = -Math.min(3.9, .08 + .075 * tick);
            assertTrue(engine.movement(PLAYER, move(tick, .1, vertical, false, false)).isEmpty());
        }
    }

    @Test void sustainedUnsupportedHoverAndAscentGenerateBufferedFlyFindings() {
        for (double vertical : new double[] {0, .25}) {
            engine.clear();
            List<Finding> findings = new ArrayList<>();
            for (int tick = 0; tick < 60; tick++) findings.addAll(engine.movement(PLAYER, move(tick, .1, vertical, false, false)));
            assertEquals(5, findings.size());
            assertTrue(findings.stream().allMatch(f -> f.check() == CheckType.FLY));
        }
    }

    @Test void nearbySupportAndGroundResetAirborneEvidence() {
        for (int tick = 0; tick < 100; tick++) {
            assertTrue(engine.movement(PLAYER, new MovementSample(tick, .1, 0, false, true, false, .4)).isEmpty());
        }
        // A new unsupported period must earn its own grace and evidence buffer.
        for (int tick = 100; tick < 125; tick++) assertTrue(engine.movement(PLAYER, move(tick, .1, 0, false, false)).isEmpty());
        assertEquals(CheckType.FLY, engine.movement(PLAYER, move(125, .1, 0, false, false)).get(0).check());
        assertTrue(engine.movement(PLAYER, move(126, 0, 0, true, false)).isEmpty());
    }

    @Test void sustainedSpeedRequiresMultipleSamplesAndProducesBoundedWeight() {
        List<Finding> findings = new ArrayList<>();
        for (int tick = 0; tick < 13; tick++) findings.addAll(engine.movement(PLAYER, move(tick, .9, 0, true, false)));
        assertEquals(3, findings.size());
        for (Finding finding : findings) {
            assertEquals(CheckType.SPEED, finding.check());
            assertEquals(1, finding.weight());
        }
    }

    @Test void occasionalMovementOutlierDecaysAndHigherAdapterEnvelopeIsRespected() {
        for (int tick = 0; tick < 100; tick++) {
            assertTrue(engine.movement(PLAYER, move(tick, tick % 5 == 0 ? .8 : .35, 0, true, false)).isEmpty());
        }
        for (int tick = 100; tick < 140; tick++) {
            assertTrue(engine.movement(PLAYER, new MovementSample(tick, .8, 0, true, true, false, .8)).isEmpty());
        }
    }

    @Test void missingOrRepeatedTickDiscardsAmbiguousMovementDeltaAndPriorEvidence() {
        for (int tick = 0; tick < 4; tick++) assertTrue(engine.movement(PLAYER, move(tick, .9, 0, true, false)).isEmpty());
        assertTrue(engine.movement(PLAYER, move(10, 99, 99, false, false)).isEmpty());
        for (int tick = 11; tick < 14; tick++) assertTrue(engine.movement(PLAYER, move(tick, .9, 0, true, false)).isEmpty());
        assertTrue(engine.movement(PLAYER, move(13, 99, 99, false, false)).isEmpty());
        for (int tick = 14; tick < 17; tick++) assertTrue(engine.movement(PLAYER, move(tick, .9, 0, true, false)).isEmpty());
    }

    @Test void exemptionsResetMovementAndCombatEvidence() {
        for (int tick = 0; tick < 4; tick++) engine.movement(PLAYER, move(tick, .9, 0, true, false));
        assertTrue(engine.movement(PLAYER, move(4, 20, 20, false, true)).isEmpty());
        for (int tick = 5; tick < 9; tick++) assertTrue(engine.movement(PLAYER, move(tick, .9, 0, true, false)).isEmpty());
        engine.combat(PLAYER, hit(0, TARGET, 5, 0, false));
        engine.combat(PLAYER, hit(1, TARGET, 5, 0, false));
        assertTrue(engine.combat(PLAYER, hit(2, TARGET, 50, 180, true)).isEmpty());
        assertTrue(engine.combat(PLAYER, hit(3, TARGET, 5, 0, false)).isEmpty());
        assertTrue(engine.combat(PLAYER, hit(4, TARGET, 5, 0, false)).isEmpty());
    }

    @Test void reachUsesDistanceEnvelopeMarginAndPersistentEvidence() {
        for (int tick = 0; tick < 80; tick++) assertTrue(engine.combat(PLAYER, hit(tick, TARGET, 3.14, 10, false)).isEmpty());
        assertTrue(engine.combat(PLAYER, hit(80, TARGET, 3.8, 10, false)).isEmpty());
        assertTrue(engine.combat(PLAYER, hit(90, TARGET, 3.8, 10, false)).isEmpty());
        assertEquals(CheckType.REACH, engine.combat(PLAYER, hit(100, TARGET, 3.8, 10, false)).get(0).check());
    }

    @Test void legitimateReachSamplesOrLongCombatGapEraseIsolatedReachOutliers() {
        engine.combat(PLAYER, hit(0, TARGET, 5, 0, false));
        engine.combat(PLAYER, hit(1, TARGET, 3, 0, false));
        assertTrue(engine.combat(PLAYER, hit(2, TARGET, 5, 0, false)).isEmpty());
        assertTrue(engine.combat(PLAYER, hit(50, TARGET, 5, 0, false)).isEmpty());
        assertTrue(engine.combat(PLAYER, hit(51, TARGET, 5, 0, false)).isEmpty());
        assertEquals(CheckType.REACH, engine.combat(PLAYER, hit(52, TARGET, 5, 0, false)).get(0).check());
    }

    @Test void repeatedImpossibleAngleProducesAuraButOverlappingTargetIsForgiven() {
        for (int tick = 0; tick < 5; tick++) assertTrue(engine.combat(PLAYER, hit(tick, TARGET, 2.5, 160, false)).isEmpty());
        assertEquals(CheckType.KILLAURA, engine.combat(PLAYER, hit(5, TARGET, 2.5, 160, false)).get(0).check());
        engine.clear();
        for (int tick = 0; tick < 100; tick++) assertTrue(engine.combat(PLAYER, hit(tick, TARGET, .25, 180, false)).isEmpty());
    }

    @Test void persistentAttackBurstAccumulatesAcrossTicksWithoutLegitimateAttacksErasingIt() {
        List<Finding> findings = new ArrayList<>();
        for (int tick = 0; tick < 6; tick++) {
            for (int attack = 0; attack < 4; attack++) findings.addAll(engine.combat(PLAYER, hit(tick, TARGET, 2.5, 10, false)));
        }
        assertEquals(1, findings.size());
        assertEquals(CheckType.KILLAURA, findings.get(0).check());
    }

    @Test void persistentRapidDistinctTargetsProducesAuraWithoutPerTickAttackBurst() {
        List<Finding> findings = new ArrayList<>();
        for (int tick = 0; tick < 4; tick++) {
            for (int attack = 0; attack < 3; attack++) {
                UUID target = new UUID(5, tick * 3L + attack);
                findings.addAll(engine.combat(PLAYER, hit(tick, target, 2.5, 10, false)));
            }
        }
        assertEquals(1, findings.size());
        assertEquals(CheckType.KILLAURA, findings.get(0).check());
    }

    @Test void normalSingleTargetCombatAndSpacedTargetChangesRemainClean() {
        for (int tick = 0; tick < 1000; tick += 5) {
            UUID target = new UUID(5, tick / 50);
            assertTrue(engine.combat(PLAYER, hit(tick, target, 2.5, 30, false)).isEmpty());
        }
    }

    @Test void malformedTelemetryIsIgnoredAndClearsEvidenceInsteadOfPropagatingNaN() {
        for (int tick = 0; tick < 4; tick++) engine.movement(PLAYER, move(tick, .9, 0, true, false));
        assertTrue(engine.movement(PLAYER, move(4, Double.NaN, 0, true, false)).isEmpty());
        for (int tick = 5; tick < 9; tick++) assertTrue(engine.movement(PLAYER, move(tick, .9, 0, true, false)).isEmpty());
        engine.combat(PLAYER, hit(0, TARGET, 5, 0, false));
        engine.combat(PLAYER, hit(1, TARGET, 5, 0, false));
        assertTrue(engine.combat(PLAYER, hit(2, TARGET, Double.POSITIVE_INFINITY, 0, false)).isEmpty());
        assertTrue(engine.combat(PLAYER, hit(3, null, 2, 0, false)).isEmpty());
        assertTrue(engine.combat(PLAYER, hit(4, TARGET, 5, 0, false)).isEmpty());
        assertTrue(engine.combat(PLAYER, hit(5, TARGET, 5, 0, false)).isEmpty());
    }

    @Test void playerStateIsIndependentAndLifecycleResetRemovesEvidence() {
        UUID other = new UUID(3, 3);
        for (int tick = 0; tick < 4; tick++) engine.movement(PLAYER, move(tick, .9, 0, true, false));
        assertTrue(engine.movement(other, move(4, .9, 0, true, false)).isEmpty());
        engine.reset(PLAYER);
        assertTrue(engine.movement(PLAYER, move(4, .9, 0, true, false)).isEmpty());
        engine.combat(PLAYER, hit(0, TARGET, 5, 0, false));
        engine.combat(PLAYER, hit(1, TARGET, 5, 0, false));
        engine.clear();
        assertTrue(engine.combat(PLAYER, hit(2, TARGET, 5, 0, false)).isEmpty());
    }

    @Test void invalidConfigurationFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new DetectionEngine.MovementSettings(Double.NaN, 4, 18, 8, .025, .15));
        assertThrows(IllegalArgumentException.class, () -> new DetectionEngine.MovementSettings(1.15, 0, 18, 8, .025, .15));
        assertThrows(IllegalArgumentException.class, () -> new DetectionEngine.CombatSettings(.1, 3, 40, 125, 6, 3, 21, 4));
        assertThrows(NullPointerException.class, () -> new DetectionEngine.Settings(null, DetectionEngine.CombatSettings.defaults()));
    }

    private static MovementSample move(long tick, double horizontal, double vertical, boolean onGround, boolean exempt) {
        return new MovementSample(tick, horizontal, vertical, onGround, onGround, exempt, .4);
    }

    private static CombatSample hit(long tick, UUID target, double distance, double angle, boolean exempt) {
        return new CombatSample(tick, target, distance, 3.05, angle, exempt);
    }
}
