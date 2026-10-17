/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

public record Finding(String checkId, String family, boolean hardInvariant,
                      double confidence, double violationLevel, int occurrences,
                      long firstSeenMillis, long lastSeenMillis, Sample sample) {}
