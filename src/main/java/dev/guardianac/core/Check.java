/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import java.util.Objects;
import java.util.function.Predicate;

/** One independently configurable rule with an explicit evidence family. */
public record Check(String id, String nameJa, String nameEn, String family, Sample.Kind kind,
                    boolean hardInvariant, double confidence, int requiredSamples,
                    double vlWeight, String explanation, Predicate<Sample> predicate) {
    public Check { Objects.requireNonNull(predicate); if(requiredSamples<1 || confidence<0 || confidence>1) throw new IllegalArgumentException(); }
    public boolean test(Sample sample) {
        boolean inventoryTiming = family.equals("INVENTORY_TIMING") && (kind == Sample.Kind.USE || kind == Sample.Kind.INVENTORY);
        return sample.kind() == kind && !sample.has("bypass") && !sample.has("exempt")
            && (hardInvariant || (inventoryTiming ? sample.safeInventoryTiming() : sample.safePhysics())) && predicate.test(sample);
    }
}
