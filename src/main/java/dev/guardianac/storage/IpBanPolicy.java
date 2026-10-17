// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.util.Objects;

/** IP equality is only an investigation signal; no candidate can be punished solely for sharing an IP. */
public final class IpBanPolicy {
    private IpBanPolicy() { }
    /** Counts distinct observed UUIDs, including untrusted ones; no observations never proves exclusivity. */
    public record Association(int observedAccounts, int trustedAccounts, boolean targetTrusted) {
        public Association {
            if (observedAccounts < 0 || trustedAccounts < 0 || trustedAccounts > observedAccounts
                    || (targetTrusted && trustedAccounts == 0))
                throw new IllegalArgumentException("Invalid IP association");
        }
        public static Association unknown() { return new Association(0, 0, false); }
    }
    /** uuidSanctionConfirmed includes an independently confirmed UUID sanction in the same atomic DUAL transaction. */
    public record Context(Association association, boolean authenticatedNativeIdentity,
                          int independentConfirmedChecks, int confirmedEvasionIncidents,
                          double confidence, boolean persistedEvidence, boolean uuidSanctionConfirmed, boolean permanent) {
        public Context {
            Objects.requireNonNull(association);
            if (independentConfirmedChecks < 0 || confirmedEvasionIncidents < 0
                    || !Double.isFinite(confidence) || confidence < 0 || confidence > 1)
                throw new IllegalArgumentException("Invalid IP policy context");
        }
    }
    public record Decision(boolean allowed, String reason) { }
    /** Also used inside the serialized storage commit; Bukkit identity checks must be captured on the server thread. */
    public static Decision identityEligibility(Association association, boolean authenticatedNativeIdentity) {
        Objects.requireNonNull(association);
        if (!authenticatedNativeIdentity) return new Decision(false, "authenticated-native-identity-required");
        if (association.observedAccounts() > 1) return new Decision(false, "shared-ip-protection");
        if (association.observedAccounts() == 0) return new Decision(false, "known-ip-observation-required");
        if (!association.targetTrusted()) return new Decision(false, "trusted-target-ip-association-required");
        return new Decision(true, "authenticated-unshared-target");
    }
    public static Decision evaluate(Context context) {
        Decision identity = identityEligibility(context.association(), context.authenticatedNativeIdentity());
        if (!identity.allowed()) return identity;
        // Unreviewed automatic permanent-IP is forbidden. A separate administrator-reviewed candidate path is required.
        if (context.permanent()) return new Decision(false, "automatic-permanent-ip-unavailable");
        if (!context.persistedEvidence()) return new Decision(false, "persisted-evidence-required");
        if (!context.uuidSanctionConfirmed()) return new Decision(false, "confirmed-uuid-sanction-required");
        if (context.independentConfirmedChecks() < 2 || context.confidence() < 0.995)
            return new Decision(false, "independent-high-confidence-evidence-required");
        return new Decision(true, "temporary-ip-corroborated");
    }
}
