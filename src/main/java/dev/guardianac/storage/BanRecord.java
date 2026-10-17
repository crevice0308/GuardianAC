// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.time.Instant;
import java.util.UUID;

/** Safe for administrative views: no plaintext IP, lookup hash, ciphertext, or key is exposed. */
public record BanRecord(UUID id, UUID playerId, String playerName, BanType type, String reason,
                        Instant createdAt, Instant expiresAt, double violationLevel, String caseId,
                        String evidenceId, String actor, boolean hasProtectedIp, String syncToken,
                        Instant revokedAt, Instant uuidRevokedAt, Instant ipRevokedAt) {
    public boolean permanent() { return expiresAt == null; }
    public boolean activeAt(Instant instant) {
        return revokedAt == null && !createdAt.isAfter(instant)
                && (expiresAt == null || expiresAt.isAfter(instant));
    }
    public boolean uuidActiveAt(Instant instant) { return type.appliesToUuid() && uuidRevokedAt == null && activeAt(instant); }
    public boolean ipActiveAt(Instant instant) { return type.appliesToIp() && ipRevokedAt == null && activeAt(instant); }
    public String protectedIpLabel() { return hasProtectedIp ? "[protected]" : "[none]"; }
    /** Store this exact marker in the native ban source; only remove an entry whose source still matches. */
    public String ownershipMarker() { return "GuardianAC:" + caseId + ":" + syncToken; }
    @Override public String toString() {
        return "BanRecord[caseId=" + caseId + ", playerId=" + playerId + ", type=" + type
                + ", permanent=" + permanent() + ", revoked=" + (revokedAt != null) + "]";
    }
}
