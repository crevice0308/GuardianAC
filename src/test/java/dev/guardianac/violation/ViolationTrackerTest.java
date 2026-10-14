package dev.guardianac.violation;

import dev.guardianac.detection.CheckType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViolationTrackerTest {
    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void thresholdAndElapsedEvidenceCannotReplaceMinimumIndependentFindings() {
        ViolationTracker tracker = new ViolationTracker(0, 3, 20);
        assertFalse(tracker.add(PLAYER, CheckType.SPEED, 100, 10, 0).punish());
        assertFalse(tracker.add(PLAYER, CheckType.SPEED, 1, 10, 20).punish());
        assertTrue(tracker.add(PLAYER, CheckType.SPEED, 1, 10, 21).punish());
    }

    @Test
    void sameTickBurstCannotReplaceMinimumEvidenceSpan() {
        ViolationTracker tracker = new ViolationTracker(0, 3, 20);
        assertFalse(tracker.add(PLAYER, CheckType.FLY, 100, 10, 5).punish());
        assertFalse(tracker.add(PLAYER, CheckType.FLY, 100, 10, 5).punish());
        assertFalse(tracker.add(PLAYER, CheckType.FLY, 100, 10, 5).punish());
        assertFalse(tracker.add(PLAYER, CheckType.FLY, 1, 10, 24).punish());
        assertTrue(tracker.add(PLAYER, CheckType.FLY, 1, 10, 25).punish());
    }

    @Test
    void levelsAndFindingsOfDifferentChecksNeverCombineIntoOneBan() {
        ViolationTracker tracker = new ViolationTracker(0, 2, 0);
        assertFalse(tracker.add(PLAYER, CheckType.SPEED, 8, 10, 0).punish());
        assertFalse(tracker.add(PLAYER, CheckType.REACH, 8, 10, 0).punish());
        assertEquals(8, tracker.maximum(PLAYER, 0));
        assertFalse(tracker.add(PLAYER, CheckType.REACH, 1, 10, 1).punish());
        assertTrue(tracker.add(PLAYER, CheckType.SPEED, 2, 10, 1).punish());
        assertEquals(10, tracker.maximum(PLAYER, 1));
    }

    @Test
    void decayUsesElapsedServerTicksAndNeverDropsBelowZero() {
        ViolationTracker tracker = new ViolationTracker(.75, 1, 0);
        tracker.add(PLAYER, CheckType.KILLAURA, 5, 10, 100);
        assertEquals(4.25, tracker.maximum(PLAYER, 120), 1e-9);
        assertEquals(2.75, tracker.maximum(PLAYER, 160), 1e-9);
        assertEquals(0, tracker.maximum(PLAYER, 10_000));
        assertEquals(0, tracker.maximum(UUID.randomUUID(), 10_000));
    }

    @Test
    void fullyDecayedEvidenceMustRebuildBothFindingCountAndTimeSpan() {
        ViolationTracker tracker = new ViolationTracker(20, 3, 10);
        assertFalse(tracker.add(PLAYER, CheckType.SPEED, 20, 10, 0).punish());
        assertFalse(tracker.add(PLAYER, CheckType.SPEED, 20, 10, 5).punish());
        assertTrue(tracker.add(PLAYER, CheckType.SPEED, 20, 10, 10).punish());
        assertEquals(0, tracker.maximum(PLAYER, 60));

        assertFalse(tracker.add(PLAYER, CheckType.SPEED, 100, 10, 61).punish());
        assertFalse(tracker.add(PLAYER, CheckType.SPEED, 100, 10, 71).punish());
        assertTrue(tracker.add(PLAYER, CheckType.SPEED, 100, 10, 72).punish());
    }

    @Test
    void quittingRemovesOnlyThatPlayersEvidenceAndRequiresFreshFindings() {
        UUID other = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        ViolationTracker tracker = new ViolationTracker(0, 2, 0);
        tracker.add(PLAYER, CheckType.REACH, 10, 10, 0);
        assertTrue(tracker.add(PLAYER, CheckType.REACH, 10, 10, 1).punish());
        tracker.add(other, CheckType.FLY, 25, 10, 0);
        tracker.remove(PLAYER);

        assertEquals(0, tracker.maximum(PLAYER, 2));
        assertEquals(25, tracker.maximum(other, 2));
        assertFalse(tracker.add(PLAYER, CheckType.REACH, 10, 10, 2).punish());
    }

    @Test
    void reloadClearRemovesAllPlayersEvidence() {
        ViolationTracker tracker = new ViolationTracker(0, 1, 0);
        UUID other = UUID.randomUUID();
        tracker.add(PLAYER, CheckType.SPEED, 50, 10, 1);
        tracker.add(other, CheckType.FLY, 50, 10, 1);
        tracker.clear();
        assertEquals(0, tracker.maximum(PLAYER, 2));
        assertEquals(0, tracker.maximum(other, 2));
    }

    @Test
    void invalidEvidenceCannotChangeExistingLevel() {
        ViolationTracker tracker = new ViolationTracker(0, 1, 0);
        tracker.add(PLAYER, CheckType.FLY, 5, 10, 0);
        assertThrows(IllegalArgumentException.class, () -> tracker.add(PLAYER, CheckType.FLY, 0, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> tracker.add(PLAYER, CheckType.FLY, Double.NaN, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> tracker.add(PLAYER, CheckType.FLY, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> tracker.add(PLAYER, CheckType.FLY, 1, Double.POSITIVE_INFINITY, 1));
        assertEquals(5, tracker.maximum(PLAYER, 1));
    }
}
