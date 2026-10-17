// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.time.Duration;
import java.util.Objects;

public record StorageConfig(int queueCapacity, long maxEvidenceBytes, int maxEvidenceRecordBytes,
                            Duration evidenceRetention, int maxBackups) {
    public StorageConfig {
        Objects.requireNonNull(evidenceRetention, "evidenceRetention");
        if (queueCapacity < 1 || queueCapacity > 65536 || maxEvidenceBytes < 1024
                || maxEvidenceRecordBytes < 128 || maxEvidenceRecordBytes > maxEvidenceBytes
                || maxEvidenceRecordBytes > 4 * 1024 * 1024 || evidenceRetention.isNegative()
                || evidenceRetention.isZero() || evidenceRetention.compareTo(Duration.ofDays(3650)) > 0
                || maxBackups < 1 || maxBackups > 100) {
            throw new IllegalArgumentException("Invalid storage limits");
        }
    }
    public static StorageConfig defaults() {
        return new StorageConfig(1024, 256L * 1024 * 1024, 1024 * 1024, Duration.ofDays(90), 7);
    }
}
