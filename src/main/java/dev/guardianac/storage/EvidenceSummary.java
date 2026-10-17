// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.time.Instant;
import java.util.UUID;

/** Metadata-only replay navigation, including saved anomalies that did not produce a ban. */
public record EvidenceSummary(String id, UUID playerId, String caseId, Instant recordedAt,
                              String sha256, int originalBytes, boolean payloadAvailable) { }
