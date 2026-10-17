// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.time.Duration;
import java.time.Instant;

public enum BanDuration {
    HOUR_1(Duration.ofHours(1)), HOURS_24(Duration.ofHours(24)), DAYS_7(Duration.ofDays(7)),
    DAYS_30(Duration.ofDays(30)), PERMANENT(null);
    private final Duration duration;
    BanDuration(Duration duration) { this.duration = duration; }
    /** Null represents an explicitly permanent ban, never a failed parse. */
    public Instant expiresAt(Instant start) { return duration == null ? null : start.plus(duration); }
    public boolean permanent() { return duration == null; }
}
