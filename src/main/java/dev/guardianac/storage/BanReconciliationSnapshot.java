// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.util.Objects;
import java.util.Optional;

/** Privileged native synchronization snapshot; its address is never printed or logged. */
public record BanReconciliationSnapshot(Optional<BanRecord> record, Optional<String> protectedIp,
                                        Optional<BanRecord> uuidRestriction, Optional<BanRecord> ipRestriction,
                                        long revision) {
    public BanReconciliationSnapshot {
        Objects.requireNonNull(record); Objects.requireNonNull(protectedIp); Objects.requireNonNull(uuidRestriction); Objects.requireNonNull(ipRestriction);
        if (revision < 0) throw new IllegalArgumentException("Invalid storage revision");
    }
    @Override public String toString() {
        return "BanReconciliationSnapshot[record=" + record + ", ip=[protected], uuidRestriction=" + uuidRestriction
                + ", ipRestriction=" + ipRestriction + ", revision=" + revision + "]";
    }
}
