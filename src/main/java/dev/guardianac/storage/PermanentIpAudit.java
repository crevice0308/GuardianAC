// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.time.Instant;
import java.util.UUID;

/** Append-only review history. Contains neither addresses, lookup tokens, nor approval tokens. */
public record PermanentIpAudit(long id, String sourceCaseId, UUID actor, String event, Instant recordedAt,
                               String reasonCode, String appliedCaseId) { }
