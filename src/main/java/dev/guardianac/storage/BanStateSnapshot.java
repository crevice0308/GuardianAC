// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.util.Objects;
import java.util.Optional;

/** One storage-worker snapshot used to revalidate later external native synchronization. */
public record BanStateSnapshot(Optional<BanRecord> record, long revision) {
    public BanStateSnapshot { Objects.requireNonNull(record); if (revision < 0) throw new IllegalArgumentException("Invalid storage revision"); }
}
