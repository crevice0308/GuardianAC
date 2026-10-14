package dev.guardianac.detection;

import java.util.Objects;

/** A buffered observation; the separate violation tracker decides whether to punish. */
public record Finding(CheckType check, double weight, String detail) {
    public Finding {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(detail, "detail");
        if (!Double.isFinite(weight) || weight <= 0) throw new IllegalArgumentException("weight must be positive and finite");
    }
}
