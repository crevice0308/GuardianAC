// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.util.Objects;

/** Privileged review input. The address must never be placed in ordinary logs or administrative views. */
public record PermanentIpReview(PermanentIpCandidate candidate, BanRecord source, String ipAddress) {
    public PermanentIpReview { Objects.requireNonNull(candidate); Objects.requireNonNull(source); Objects.requireNonNull(ipAddress); }
    @Override public String toString() { return "PermanentIpReview[sourceCase=" + source.caseId() + ", ip=[protected]]"; }
}
