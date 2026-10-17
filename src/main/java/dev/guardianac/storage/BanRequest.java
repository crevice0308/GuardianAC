// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Sensitive input. The adapter must never log ipAddress; toString intentionally redacts it. */
public record BanRequest(UUID playerId, String playerName, String ipAddress, BanType type, String reason,
                         Instant expiresAt, double violationLevel, String caseId, String evidenceId, String actor) {
    public BanRequest {
        Objects.requireNonNull(playerId, "playerId"); Objects.requireNonNull(type, "type");
        StorageValidation.text(playerName, 64, "playerName");
        StorageValidation.text(reason, 2048, "reason"); StorageValidation.text(actor, 128, "actor");
        StorageValidation.caseId(caseId); StorageValidation.text(evidenceId, 64, "evidenceId");
        if (!Double.isFinite(violationLevel) || violationLevel < 0 || violationLevel > 1e9)
            throw new IllegalArgumentException("Invalid violation level");
        if (type.appliesToIp() && (ipAddress == null || ipAddress.isBlank()))
            throw new IllegalArgumentException("IP ban requires an address");
        if (ipAddress != null) IpPrivacy.normalize(ipAddress);
    }
    @Override public String toString() {
        return "BanRequest[playerId=" + playerId + ", type=" + type + ", caseId=" + caseId + ", ip=[redacted]]";
    }
}
