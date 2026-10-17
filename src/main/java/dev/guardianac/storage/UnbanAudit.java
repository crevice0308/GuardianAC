// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.time.Instant;

public record UnbanAudit(long id, String caseId, String actor, String reason, Instant revokedAt, String scope) { }
