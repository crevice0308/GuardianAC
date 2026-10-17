// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.time.Instant;
import java.util.UUID;

/** A retained hash/metadata tombstone has null telemetryJson after configured evidence retention. */
public record EvidenceRecord(String id, UUID playerId, String caseId, Instant recordedAt, String telemetryJson,
                             String sha256, int originalBytes) {
    public boolean payloadAvailable() { return telemetryJson != null; }
    @Override public String toString() {
        return "EvidenceRecord[id=" + id + ", caseId=" + caseId + ", payloadAvailable=" + payloadAvailable() + "]";
    }
}
