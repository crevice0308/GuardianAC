// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A JSON object containing bounded replay telemetry. Never put raw IPs or secrets in telemetry. */
public record EvidenceInput(UUID playerId, String caseId, Instant recordedAt, String telemetryJson) {
    public EvidenceInput {
        Objects.requireNonNull(playerId, "playerId"); Objects.requireNonNull(recordedAt, "recordedAt");
        StorageValidation.caseId(caseId); Objects.requireNonNull(telemetryJson, "telemetryJson");
    }
    @Override public String toString() { return "EvidenceInput[playerId=" + playerId + ", caseId=" + caseId + "]"; }
}
