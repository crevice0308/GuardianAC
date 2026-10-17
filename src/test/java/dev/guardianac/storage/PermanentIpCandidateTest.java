// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PermanentIpCandidateTest {
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final UUID ID = UUID.fromString("01234567-89ab-4def-8123-456789abcdef");
    private static PermanentIpCandidate candidate(Instant expiry, Instant observation) {
        return new PermanentIpCandidate("GAC-0123456789ABCDEF", ID, "evidence", "a".repeat(64),
            "protected-lookup", "b".repeat(64), NOW, expiry, observation);
    }
    @Test void deadlineIsStrictAndCannotExceedTwentyFourHours() {
        var candidate = candidate(NOW.plusSeconds(86_400), NOW.minusSeconds(1));
        assertFalse(candidate.validAt(NOW.minusMillis(1))); assertTrue(candidate.validAt(NOW));
        assertTrue(candidate.validAt(NOW.plusSeconds(86_400).minusMillis(1)));
        assertFalse(candidate.validAt(NOW.plusSeconds(86_400)));
        assertThrows(IllegalArgumentException.class, () -> candidate(NOW.plusSeconds(86_401), NOW));
        assertThrows(IllegalArgumentException.class, () -> candidate(NOW, NOW));
    }
    @Test void identityObservationMustBePastAndInExistingTrustWindow() {
        assertThrows(IllegalArgumentException.class, () -> candidate(NOW.plusSeconds(10), NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> candidate(NOW.plusSeconds(10), NOW.minusSeconds(30 * 86_400L + 1)));
    }
    @Test void protectedBindingsAreRedacted() {
        String rendered = candidate(NOW.plusSeconds(10), NOW).toString();
        assertFalse(rendered.contains("protected-lookup")); assertFalse(rendered.contains("a".repeat(64)));
        assertFalse(rendered.contains("b".repeat(64)));
    }
    private BanRecord source(Instant expiry, Instant revoked) {
        return new BanRecord(ID, ID, "Synthetic", BanType.UUID, "Unfair Advantage", NOW, expiry, 160,
            "GAC-0123456789ABCDEF", "evidence", "automatic", true, "ownership", revoked, revoked, null);
    }
    @Test void fingerprintBindsExpiryScopeOwnershipAndRevocation() {
        String original = PermanentIpCandidate.fingerprint(source(NOW.plusSeconds(3600), null), "lookup");
        assertEquals(64, original.length());
        assertNotEquals(original, PermanentIpCandidate.fingerprint(source(null, null), "lookup"));
        assertNotEquals(original, PermanentIpCandidate.fingerprint(source(NOW.plusSeconds(3600), NOW), "lookup"));
        assertNotEquals(original, PermanentIpCandidate.fingerprint(source(NOW.plusSeconds(3600), null), "changed"));
    }
}
