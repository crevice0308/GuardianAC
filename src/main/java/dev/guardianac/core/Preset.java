/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

public enum Preset {
    RELAXED(1.5, 180), NORMAL(1.2, 140), STRICT(1.0, 100);
    public final double sampleMultiplier, banThreshold;
    Preset(double sampleMultiplier, double banThreshold) { this.sampleMultiplier=sampleMultiplier; this.banThreshold=banThreshold; }
}
