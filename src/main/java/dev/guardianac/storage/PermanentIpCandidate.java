// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Immutable, protected review provenance. This descriptor never grants an IP sanction. */
public record PermanentIpCandidate(String sourceCaseId, UUID playerId, String evidenceId,
                                   String evidenceSha256, String ipLookup, String sourceFingerprint,
                                   Instant createdAt, Instant reviewExpiresAt, Instant identityObservedAt) {
    public static final Duration MAX_REVIEW_AGE = Duration.ofHours(24);
    public static final Duration IDENTITY_WINDOW = Duration.ofDays(30);

    public PermanentIpCandidate {
        StorageValidation.caseId(sourceCaseId);
        Objects.requireNonNull(playerId);
        StorageValidation.text(evidenceId, 64, "evidenceId");
        requireDigest(evidenceSha256); requireDigest(sourceFingerprint);
        StorageValidation.text(ipLookup, 256, "protected address binding");
        Objects.requireNonNull(createdAt); Objects.requireNonNull(reviewExpiresAt);
        Objects.requireNonNull(identityObservedAt);
        if (!reviewExpiresAt.isAfter(createdAt) || reviewExpiresAt.isAfter(createdAt.plus(MAX_REVIEW_AGE)))
            throw new IllegalArgumentException("Invalid permanent IP review deadline");
        if (identityObservedAt.isAfter(createdAt) || identityObservedAt.isBefore(createdAt.minus(IDENTITY_WINDOW)))
            throw new IllegalArgumentException("Invalid trusted identity observation time");
    }

    public boolean validAt(Instant now) {
        Objects.requireNonNull(now);
        return !now.isBefore(createdAt) && now.isBefore(reviewExpiresAt);
    }

    /** Bind every source identity/scope/expiry/revocation field, without retaining plaintext IP. */
    public static String fingerprint(BanRecord source, String ipLookup) {
        Objects.requireNonNull(source);
        StorageValidation.text(ipLookup, 256, "protected address binding");
        StringBuilder canonical = new StringBuilder("GuardianAC permanent IP source v1\n");
        for (Object field : new Object[] {source.id(), source.playerId(), source.playerName(), source.type(),
                source.reason(), source.createdAt(), source.expiresAt(), source.violationLevel(), source.caseId(),
                source.evidenceId(), source.actor(), source.hasProtectedIp(), source.syncToken(), source.revokedAt(),
                source.uuidRevokedAt(), source.ipRevokedAt(), ipLookup}) {
            String text = field == null ? "<null>" : field.toString();
            canonical.append(text.length()).append(':').append(text).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid evidence or source fingerprint");
    }

    @Override public String toString() {
        return "PermanentIpCandidate[sourceCaseId=" + sourceCaseId + ", playerId=" + playerId
            + ", reviewExpiresAt=" + reviewExpiresAt + ", address=[protected]]";
    }
}
