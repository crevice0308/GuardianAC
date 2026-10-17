// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import static org.junit.jupiter.api.Assertions.*;
import dev.guardianac.checks.CheckCatalog;
import dev.guardianac.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Isolated synthetic fixtures: never connect to or sanction real accounts. */
class PermanentIpRepositoryTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-10-14T01:00:00Z");
    private static final UUID PLAYER = new UUID(700, 900), ACTOR = new UUID(800, 901);
    private static final String IP = "203.0.113.47";
    private final MovingClock clock = new MovingClock();
    private static final Set<String> CHECKS = Set.of("packet.held-slot", "packet.pitch");
    private static final AutomaticBanGate.Policy POLICY = new AutomaticBanGate.Policy(true, true, Preset.STRICT, CHECKS, "DUAL:72:0:true");
    private SqliteBanRepository open() { return SqliteBanRepository.open(directory, StorageConfig.defaults(), clock); }
    private static String token() { return UUID.randomUUID().toString(); }
    private BanEvidence proof() {
        var checks = CheckCatalog.create(); var engine = new DetectionEngine(checks, 1);
        engine.preset(Preset.STRICT); engine.enabled(CHECKS::contains);
        long start = clock.millis() - 6601;
        for (int i = 0; i < 12; i++) {
            engine.accept(Sample.builder(PLAYER, Sample.Kind.PACKET, start + i * 600L, i).flag("held-slot-present").value("held-slot", 15).build());
            engine.accept(Sample.builder(PLAYER, Sample.Kind.PACKET, start + i * 600L + 1, i).flag("rotation-present").value("yaw", 0).value("pitch", 100).build());
        }
        BanEvidence evidence = engine.evidence(PLAYER, clock.millis());
        assertTrue(evidence.evaluate(checks, POLICY, clock.millis()).banRecommended());
        return evidence;
    }
    private BanRequest request(SqliteBanRepository repository, String json) {
        String id = SqliteBanRepository.newCaseId();
        EvidenceRecord evidence = repository.saveEvidence(new EvidenceInput(PLAYER, id, clock.instant(), json)).join();
        return new BanRequest(PLAYER, "SyntheticOnly", IP, BanType.UUID, "Synthetic evidence", NOW.plus(Duration.ofHours(72)), 175, id, evidence.id(), "fixture");
    }
    private BanRequest request(SqliteBanRepository repository) { return request(repository, PersistedBanEvidence.encode(PLAYER, proof(), POLICY, "[]")); }
    private BanRecord candidate(SqliteBanRepository repository) {
        repository.registerIp(PLAYER, IP, true, clock.instant()).join();
        return repository.createAutomaticReviewCandidate(request(repository), NOW.minus(Duration.ofDays(30)), true, POLICY, () -> true).join();
    }
    private PermanentIpReview preview(SqliteBanRepository repository, BanRecord source) {
        return repository.previewPermanentIp(source.caseId(), POLICY, true).join().orElseThrow();
    }
    private BanRecord apply(SqliteBanRepository repository, String token, PermanentIpReview review) {
        return repository.applyReviewedPermanentIp(token, ACTOR, review.candidate(), "Administrator reviewed independent proof", POLICY, true, () -> true).join();
    }
    private String pending(SqliteBanRepository repository, PermanentIpReview review) {
        String token = token(); repository.openPermanentIpReview(token, ACTOR, review.candidate()).join(); return token;
    }
    private Connection raw() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("bans/bans.db").toAbsolutePath()); }
    private long count(String table) throws SQLException {
        try (Connection c = raw(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(r.next()); return r.getLong(1); }
    }
    private void sql(String sql) throws SQLException { try (Connection c = raw(); Statement s = c.createStatement()) { s.execute(sql); } }
    private static void refuses(Runnable action) { assertThrows(CompletionException.class, action::run); }

    @Test void reachableNativeCandidateHasNoIpUntilAdminApprovalAndPreservesUuidExpiry() {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source);
            assertEquals(BanType.UUID, source.type()); assertTrue(source.hasProtectedIp()); assertEquals(IP, review.ipAddress());
            assertTrue(repository.matchIp(IP, NOW).join().isEmpty()); assertEquals(List.of(source), repository.activeForPlayer(PLAYER, NOW).join());
            String token = pending(repository, review); BanRecord applied = apply(repository, token, review);
            assertEquals(BanType.IP, applied.type()); assertTrue(applied.permanent()); assertNotEquals(source.caseId(), applied.caseId());
            assertEquals(source, repository.findCase(source.caseId()).join().orElseThrow());
            assertEquals(List.of(source), repository.activeForPlayer(PLAYER, NOW).join()); assertEquals(List.of(applied), repository.matchIp(IP, NOW).join());
            String payload = repository.evidence(applied.evidenceId()).join().orElseThrow().telemetryJson();
            assertTrue(payload.contains(source.caseId())); assertTrue(payload.contains(review.candidate().evidenceSha256())); assertTrue(payload.contains(ACTOR.toString()));
            assertTrue(payload.contains("sourceProof")); assertFalse(payload.contains(IP));
            List<PermanentIpAudit> audit = repository.permanentIpReviewHistory(source.caseId(), 10).join();
            assertEquals(List.of("APPLY", "REQUEST"), audit.stream().map(PermanentIpAudit::event).toList()); assertTrue(audit.stream().allMatch(a -> a.actor().equals(ACTOR)));
            assertEquals(applied.caseId(), audit.getFirst().appliedCaseId());
            assertEquals(audit, repository.permanentIpReviewHistory(applied.caseId(), 10).join());
            repository.cancelPermanentIpReview(token, ACTOR, "CLOSED").join(); assertEquals(List.of(applied), repository.matchIp(IP, NOW).join());
            refuses(() -> apply(repository, token, review)); assertEquals(2, repository.historyForPlayer(PLAYER, 10).join().size());
        }
    }
    @Test void unknownOfflineSharedAndUntrustedIdentitiesFallBackToPlainUuid() {
        for (int scenario = 0; scenario < 5; scenario++) {
            Path sub = directory.resolve("scenario" + scenario);
            try (SqliteBanRepository repository = SqliteBanRepository.open(sub, StorageConfig.defaults(), clock)) {
                if (scenario != 0) repository.registerIp(PLAYER, IP, scenario != 2, NOW).join();
                if (scenario == 3) repository.registerIp(UUID.randomUUID(), IP, false, NOW).join();
                if (scenario == 4) repository.registerIp(UUID.randomUUID(), IP, true, NOW).join();
                BanRecord source = repository.createAutomaticReviewCandidate(request(repository), NOW.minus(Duration.ofDays(30)), scenario != 1, POLICY, () -> true).join();
                assertEquals(BanType.UUID, source.type()); assertFalse(source.hasProtectedIp()); assertTrue(repository.previewPermanentIp(source.caseId(), POLICY, true).join().isEmpty());
                assertTrue(repository.matchIp(IP, NOW).join().isEmpty());
            }
        }
    }
    @Test void legacyProtectedUuidAndTemporaryDualNeverBecomeCandidates() {
        try (SqliteBanRepository repository = open()) {
            BanRequest legacy = request(repository, "{\"version\":1,\"checks\":[\"packet.pitch\",\"packet.held-slot\"],\"vl\":999}");
            BanRecord oldUuid = repository.createBan(legacy).join(); assertTrue(oldUuid.hasProtectedIp());
            assertTrue(repository.previewPermanentIp(oldUuid.caseId(), POLICY, true).join().isEmpty());
            BanRequest good = request(repository);
            BanRecord oldDual = repository.createBan(new BanRequest(PLAYER, good.playerName(), IP, BanType.DUAL, good.reason(), good.expiresAt(), 175, good.caseId(), good.evidenceId(), "fixture")).join();
            assertTrue(repository.previewPermanentIp(oldDual.caseId(), POLICY, true).join().isEmpty());
            repository.registerIp(PLAYER, IP, true, NOW).join();
            BanRecord noProof = repository.createAutomaticReviewCandidate(request(repository, "{}"), NOW.minus(Duration.ofDays(30)), true, POLICY, () -> true).join();
            assertFalse(noProof.hasProtectedIp());
        }
    }
    @Test void fabricatedUnknownAndHeuristicProofCannotCreateReviewProvenance() {
        try (SqliteBanRepository repository = open()) {
            repository.registerIp(PLAYER, IP, true, NOW).join();
            BanEvidence valid = proof();
            for (boolean unknown : List.of(false, true)) {
                List<Finding> findings = valid.findings().stream().map(f -> new Finding(unknown ? "unknown." + f.checkId() : f.checkId(), f.family(), unknown || !f.hardInvariant(), f.confidence(), f.violationLevel(), f.occurrences(), f.firstSeenMillis(), f.lastSeenMillis(), f.sample())).toList();
                String json = PersistedBanEvidence.encode(PLAYER, new BanEvidence(findings, clock.millis()), POLICY, "[]");
                BanRecord source = repository.createAutomaticReviewCandidate(request(repository, json), NOW.minus(Duration.ofDays(30)), true, POLICY, () -> true).join();
                assertFalse(source.hasProtectedIp()); assertTrue(repository.previewPermanentIp(source.caseId(), POLICY, true).join().isEmpty());
            }
        }
    }
    @Test void originalPolicyAndCurrentPolicyMustBothAuthorizePermanentReview() {
        try (SqliteBanRepository repository = open()) {
            repository.registerIp(PLAYER, IP, true, NOW).join();
            var disabled = new AutomaticBanGate.Policy(true, true, Preset.STRICT, CHECKS, "DUAL:72:0:false");
            BanRecord plain = repository.createAutomaticReviewCandidate(request(repository), NOW.minus(Duration.ofDays(30)), true, disabled, () -> true).join(); assertFalse(plain.hasProtectedIp());
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); String token = pending(repository, review);
            assertTrue(repository.previewPermanentIp(source.caseId(), disabled, true).join().isEmpty());
            refuses(() -> repository.applyReviewedPermanentIp(token, ACTOR, review.candidate(), "Reason", disabled, true, () -> true).join());
            assertTrue(repository.matchIp(IP, NOW).join().isEmpty()); assertEquals("REJECT", repository.permanentIpReviewHistory(source.caseId(), 1).join().getFirst().event());
        }
    }
    @Test void nativeEnvironmentAndEnabledKnownChecksAreRechecked() {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source);
            assertTrue(repository.previewPermanentIp(source.caseId(), POLICY, false).join().isEmpty());
            String token = pending(repository, review);
            refuses(() -> repository.applyReviewedPermanentIp(token, ACTOR, review.candidate(), "Reason", POLICY, false, () -> true).join());
            var changed = new AutomaticBanGate.Policy(true, true, Preset.STRICT, Set.of("packet.pitch"), POLICY.sanctionPolicy());
            assertTrue(repository.previewPermanentIp(source.caseId(), changed, true).join().isEmpty()); assertTrue(repository.matchIp(IP, NOW).join().isEmpty());
        }
    }
    @Test void staleOrFutureSourceDecisionCannotBecomeCandidate() {
        try (SqliteBanRepository repository = open()) {
            repository.registerIp(PLAYER, IP, true, NOW).join(); BanEvidence proof = proof();
            for (long shift : new long[] {-5001, 1}) {
                BanEvidence moved = new BanEvidence(proof.findings(), proof.capturedAtMillis() + shift);
                if (shift < 0) { // Shift observations as well so the historical proof itself remains structurally valid.
                    moved = new BanEvidence(proof.findings().stream().map(f -> new Finding(f.checkId(), f.family(), f.hardInvariant(), f.confidence(), f.violationLevel(), f.occurrences(), f.firstSeenMillis() + shift, f.lastSeenMillis() + shift,
                            new Sample(PLAYER, f.sample().timeMillis() + shift, f.sample().tick(), f.sample().kind(), f.sample().measurements(), f.sample().flags()))).toList(), proof.capturedAtMillis() + shift);
                }
                BanRecord source = repository.createAutomaticReviewCandidate(request(repository, PersistedBanEvidence.encode(PLAYER, moved, POLICY, "[]")), NOW.minus(Duration.ofDays(30)), true, POLICY, () -> true).join(); assertFalse(source.hasProtectedIp());
            }
        }
    }
    @Test void candidateAdmissionReservesLinkedEvidenceHeadroomAtExactBoundary() {
        StorageConfig d = StorageConfig.defaults(); int max = 8192, reserve = 4096;
        StorageConfig smallRecord = new StorageConfig(64, d.maxEvidenceBytes(), max, d.evidenceRetention(), d.maxBackups());
        try (SqliteBanRepository repository = SqliteBanRepository.open(directory, smallRecord, clock)) {
            repository.registerIp(PLAYER, IP, true, NOW).join();
            for (int extra : new int[] {0, 1}) {
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(PersistedBanEvidence.encode(PLAYER, proof(), POLICY, "[]")).getAsJsonObject();
                json.addProperty("padding", ""); int fixed = json.toString().getBytes(StandardCharsets.UTF_8).length;
                assertTrue(fixed < max - reserve); json.addProperty("padding", "a".repeat(max - reserve - fixed + extra));
                assertEquals(max - reserve + extra, json.toString().getBytes(StandardCharsets.UTF_8).length);
                BanRecord source = repository.createAutomaticReviewCandidate(request(repository, json.toString()), NOW.minus(Duration.ofDays(30)), true, POLICY, () -> true).join();
                assertEquals(extra == 0, source.hasProtectedIp());
                if (extra == 0) { PermanentIpReview review = preview(repository, source); assertNotNull(apply(repository, pending(repository, review), review)); }
                else assertTrue(repository.previewPermanentIp(source.caseId(), POLICY, true).join().isEmpty());
            }
        }
    }
    @Test void creationGuardRollsBackUuidCandidateAndProtectedAddressTogether() throws Exception {
        try (SqliteBanRepository repository = open()) {
            repository.registerIp(PLAYER, IP, true, NOW).join(); BanRequest request = request(repository);
            assertThrows(CancellationException.class, () -> repository.createAutomaticReviewCandidate(request, NOW.minus(Duration.ofDays(30)), true, POLICY, () -> false).join());
            assertTrue(repository.findCase(request.caseId()).join().isEmpty()); assertEquals(0, count("permanent_ip_candidates")); assertEquals(0, count("bans"));
            assertNotNull(repository.createAutomaticReviewCandidate(request, NOW.minus(Duration.ofDays(30)), true, POLICY, () -> true).join());
        }
    }
    @Test void mutationRevisionAndWorkerSnapshotAdvanceOnlyAfterCommittedWrites() {
        try (SqliteBanRepository repository = open()) {
            long before = repository.mutationRevision();
            BanRecord source = candidate(repository); BanStateSnapshot snapshot = repository.caseState(source.caseId()).join();
            assertEquals(Optional.of(source), snapshot.record()); assertEquals(repository.mutationRevision(), snapshot.revision()); assertTrue(snapshot.revision() > before);
            PermanentIpReview review = preview(repository, source); String token = pending(repository, review); long afterRequest = repository.mutationRevision();
            assertThrows(CancellationException.class, () -> repository.applyReviewedPermanentIp(token, ACTOR, review.candidate(), "Reason", POLICY, true, () -> false).join());
            assertEquals(afterRequest, repository.mutationRevision());
            apply(repository, token, review); assertTrue(repository.mutationRevision() > afterRequest);
            repository.revokeCaseUuid(source.caseId(), ACTOR.toString(), "Unban", NOW).join();
            BanStateSnapshot revoked = repository.caseState(source.caseId()).join(); assertFalse(revoked.record().orElseThrow().uuidActiveAt(NOW));
            assertEquals(repository.mutationRevision(), revoked.revision());
        }
    }
    @Test void approvalGuardRollsBackEveryWriteIncludingNewEvidenceConsumptionAndAudit() throws Exception {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); String token = pending(repository, review);
            long evidence = count("evidence");
            assertThrows(CancellationException.class, () -> repository.applyReviewedPermanentIp(token, ACTOR, review.candidate(), "Reason", POLICY, true, () -> false).join());
            assertEquals(evidence, count("evidence")); assertEquals(1, count("bans")); assertEquals(1, count("permanent_ip_audit")); assertTrue(repository.matchIp(IP, NOW).join().isEmpty());
            assertEquals(source, repository.findCase(source.caseId()).join().orElseThrow()); assertTrue(repository.previewPermanentIp(source.caseId(), POLICY, true).join().isPresent());
            assertNotNull(apply(repository, token, review));
        }
    }
    @Test void tokensBindActorAndCandidateAndCannotBeReplayed() {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); String token = pending(repository, review);
            refuses(() -> repository.applyReviewedPermanentIp(token, UUID.randomUUID(), review.candidate(), "Reason", POLICY, true, () -> true).join());
            refuses(() -> repository.cancelPermanentIpReview(token, UUID.randomUUID(), "CANCELLED").join());
            refuses(() -> repository.applyReviewedPermanentIp(token(), ACTOR, review.candidate(), "Reason", POLICY, true, () -> true).join());
            assertNotNull(apply(repository, token, review)); refuses(() -> apply(repository, token, review));
        }
    }
    @Test void exactCandidateEqualityIsRechecked() {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); String token = pending(repository, review); var c = review.candidate();
            var changed = new PermanentIpCandidate(c.sourceCaseId(), c.playerId(), c.evidenceId(), c.evidenceSha256(), c.ipLookup(), c.sourceFingerprint(), c.createdAt(), c.reviewExpiresAt().minusSeconds(1), c.identityObservedAt());
            refuses(() -> repository.openPermanentIpReview(token(), ACTOR, changed).join());
            refuses(() -> repository.applyReviewedPermanentIp(token, ACTOR, changed, "Reason", POLICY, true, () -> true).join());
            assertTrue(repository.matchIp(IP, NOW).join().isEmpty());
        }
    }
    @Test void thirtySecondRequestBoundaryIsExclusiveAndAudited() {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); String token = pending(repository, review);
            clock.now = NOW.plusSeconds(30); refuses(() -> apply(repository, token, review));
            assertEquals("EXPIRE", repository.permanentIpReviewHistory(source.caseId(), 1).join().getFirst().event());
            assertTrue(repository.matchIp(IP, clock.instant()).join().isEmpty());
        }
    }
    @Test void sourceUnbanAndExpiryBlockApproval() {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); String token = pending(repository, review);
            repository.revokeCaseUuid(source.caseId(), ACTOR.toString(), "Reviewed unban", NOW).join();
            refuses(() -> apply(repository, token, review)); assertTrue(repository.previewPermanentIp(source.caseId(), POLICY, true).join().isEmpty());
        }
    }
    @Test void expiredUuidSourceCannotBeApprovedEvenWithUnexpiredCandidate() {
        try (SqliteBanRepository repository = open()) {
            repository.registerIp(PLAYER, IP, true, NOW).join(); BanRequest r = request(repository);
            BanRequest shortSource = new BanRequest(r.playerId(), r.playerName(), r.ipAddress(), r.type(), r.reason(), NOW.plusSeconds(1), r.violationLevel(), r.caseId(), r.evidenceId(), r.actor());
            BanRecord source = repository.createAutomaticReviewCandidate(shortSource, NOW.minus(Duration.ofDays(30)), true, POLICY, () -> true).join();
            PermanentIpReview review = preview(repository, source); String token = pending(repository, review);
            clock.now = NOW.plusSeconds(1); refuses(() -> apply(repository, token, review));
            assertTrue(repository.matchIp(IP, clock.instant()).join().isEmpty());
        }
    }
    @Test void observationRetentionCannotForgetALateSharedAccount() {
        StorageConfig d = StorageConfig.defaults();
        StorageConfig shortRetention = new StorageConfig(64, d.maxEvidenceBytes(), d.maxEvidenceRecordBytes(), Duration.ofSeconds(1), d.maxBackups());
        try (SqliteBanRepository repository = SqliteBanRepository.open(directory, shortRetention, clock)) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); String token = pending(repository, review);
            repository.registerIp(UUID.randomUUID(), IP, false, NOW).join();
            clock.now = NOW.plusSeconds(2); repository.registerIp(PLAYER, IP, true, clock.instant()).join();
            repository.pruneEvidence(clock.instant()).join();
            assertEquals(2, repository.observedAccountCount(IP, NOW.minus(Duration.ofDays(30))).join());
            refuses(() -> apply(repository, token, review)); assertTrue(repository.matchIp(IP, clock.instant()).join().isEmpty());
        }
    }
    @Test void candidateExpiresAfter24HoursEvenWhenUuidRemainsActive() {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); clock.now = NOW.plus(Duration.ofHours(24));
            assertTrue(source.uuidActiveAt(clock.instant())); assertTrue(repository.previewPermanentIp(source.caseId(), POLICY, true).join().isEmpty());
        }
    }
    @Test void secondObservedUuidTrustedOrNotAlwaysBlocksLateApproval() {
        for (boolean trusted : List.of(false, true)) {
            try (SqliteBanRepository repository = SqliteBanRepository.open(directory.resolve("trusted" + trusted), StorageConfig.defaults(), clock)) {
                BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); String token = pending(repository, review);
                repository.registerIp(UUID.randomUUID(), IP, trusted, NOW).join(); refuses(() -> apply(repository, token, review));
                assertTrue(repository.matchIp(IP, NOW).join().isEmpty()); assertEquals(source, repository.findCase(source.caseId()).join().orElseThrow());
            }
        }
    }
    @Test void shortEvidenceRetentionPreservesEarlierSharedAccountBeforeAnyCandidateExists() throws Exception {
        StorageConfig d = StorageConfig.defaults();
        StorageConfig oneDay = new StorageConfig(64, d.maxEvidenceBytes(), d.maxEvidenceRecordBytes(), Duration.ofDays(1), d.maxBackups());
        try (SqliteBanRepository repository = SqliteBanRepository.open(directory, oneDay, clock)) {
            repository.registerIp(UUID.randomUUID(), IP, false, NOW.minus(Duration.ofDays(2))).join();
            repository.registerIp(PLAYER, IP, true, NOW).join(); assertEquals(0, count("permanent_ip_candidates"));
            repository.pruneEvidence(NOW).join();
            assertEquals(new IpBanPolicy.Association(2, 1, true), repository.ipAssociation(PLAYER, IP, NOW.minus(Duration.ofDays(30))).join());
            for (BanType type : List.of(BanType.IP, BanType.DUAL)) {
                BanRequest evidence = request(repository);
                BanRequest requested = new BanRequest(PLAYER, evidence.playerName(), IP, type, evidence.reason(), NOW.plusSeconds(3600), evidence.violationLevel(), evidence.caseId(), evidence.evidenceId(), evidence.actor());
                BanRecord fallback = repository.createAutomaticBan(requested, NOW.minus(Duration.ofDays(30)), evidence.expiresAt(), true, () -> true).join();
                assertEquals(BanType.UUID, fallback.type()); assertFalse(fallback.hasProtectedIp()); assertEquals(evidence.expiresAt(), fallback.expiresAt());
            }
            BanRecord source = repository.createAutomaticReviewCandidate(request(repository), NOW.minus(Duration.ofDays(30)), true, POLICY, () -> true).join();
            assertEquals(BanType.UUID, source.type()); assertFalse(source.hasProtectedIp());
            assertTrue(repository.previewPermanentIp(source.caseId(), POLICY, true).join().isEmpty());
            assertEquals(0, count("permanent_ip_candidates")); assertTrue(repository.matchIp(IP, NOW).join().isEmpty());
        }
    }
    @Test void associationRetentionKeepsExact30DayBoundaryAndPrunesOnlyOlderUnpinnedRows() throws Exception {
        StorageConfig d = StorageConfig.defaults();
        StorageConfig oneDay = new StorageConfig(64, d.maxEvidenceBytes(), d.maxEvidenceRecordBytes(), Duration.ofDays(1), d.maxBackups());
        try (SqliteBanRepository repository = SqliteBanRepository.open(directory, oneDay, clock)) {
            Instant boundary = NOW.minus(Duration.ofDays(30));
            repository.registerIp(PLAYER, IP, true, NOW).join();
            repository.registerIp(UUID.randomUUID(), IP, false, boundary).join();
            repository.registerIp(UUID.randomUUID(), IP, false, boundary.minusMillis(1)).join();
            repository.pruneEvidence(NOW).join();
            assertEquals(2, count("ip_observations")); assertEquals(2, repository.observedAccountCount(IP, boundary).join());
            BanRecord source = repository.createAutomaticReviewCandidate(request(repository), boundary, true, POLICY, () -> true).join();
            assertFalse(source.hasProtectedIp()); assertEquals(0, count("permanent_ip_candidates"));
            clock.now = NOW.plusMillis(1); repository.pruneEvidence(clock.instant()).join();
            assertEquals(1, count("ip_observations"));
            assertEquals(new IpBanPolicy.Association(1, 1, true), repository.ipAssociation(PLAYER, IP, clock.instant().minus(Duration.ofDays(30))).join());
        }
    }
    @Test void longerConfiguredRetentionStillPreservesAssociationsBeyond30Days() throws Exception {
        StorageConfig d = StorageConfig.defaults();
        StorageConfig sixtyDays = new StorageConfig(64, d.maxEvidenceBytes(), d.maxEvidenceRecordBytes(), Duration.ofDays(60), d.maxBackups());
        try (SqliteBanRepository repository = SqliteBanRepository.open(directory, sixtyDays, clock)) {
            repository.registerIp(PLAYER, IP, false, NOW.minus(Duration.ofDays(31))).join();
            repository.pruneEvidence(NOW).join();
            assertEquals(1, count("ip_observations")); assertEquals(0, repository.observedAccountCount(IP, NOW.minus(Duration.ofDays(30))).join());
            assertEquals(1, repository.observedAccountCount(IP, NOW.minus(Duration.ofDays(60))).join());
        }
    }
    @Test void targetTrustDowngradeOrLaterDifferentAddressBlocksApproval() {
        for (boolean newAddress : List.of(false, true)) {
            try (SqliteBanRepository repository = SqliteBanRepository.open(directory.resolve("move" + newAddress), StorageConfig.defaults(), clock)) {
                BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); String token = pending(repository, review);
                clock.now = clock.now.plusMillis(1);
                repository.registerIp(PLAYER, newAddress ? "203.0.113.48" : IP, newAddress, clock.instant()).join();
                refuses(() -> apply(repository, token, review)); assertTrue(repository.matchIp(IP, clock.instant()).join().isEmpty());
            }
        }
    }
    @Test void concurrentDifferentRequestsCanApplyCandidateOnlyOnce() {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source);
            String first = pending(repository, review), second = pending(repository, review);
            var a = repository.applyReviewedPermanentIp(first, ACTOR, review.candidate(), "Reason", POLICY, true, () -> true);
            var b = repository.applyReviewedPermanentIp(second, ACTOR, review.candidate(), "Reason", POLICY, true, () -> true);
            assertNotNull(a.join()); refuses(b::join); assertEquals(1, repository.matchIp(IP, NOW).join().size()); assertEquals(2, repository.historyForPlayer(PLAYER, 10).join().size());
        }
    }
    @Test void restartCancelsOutstandingAuthorityButPreservesCandidateAndAppliedBans() {
        BanRecord source; PermanentIpReview review; String token;
        try (SqliteBanRepository repository = open()) { source = candidate(repository); review = preview(repository, source); token = pending(repository, review); }
        BanRecord applied;
        try (SqliteBanRepository repository = open()) {
            refuses(() -> apply(repository, token, review)); assertEquals("CANCEL", repository.permanentIpReviewHistory(source.caseId(), 1).join().getFirst().event());
            applied = apply(repository, pending(repository, preview(repository, source)), review);
        }
        try (SqliteBanRepository repository = open()) { assertEquals(List.of(applied), repository.matchIp(IP, NOW).join()); assertEquals(source, repository.findCase(source.caseId()).join().orElseThrow()); }
    }
    @Test void appendOnlyAuditPrivacyAndBoundedHistory() throws Exception {
        String secretToken;
        BanRecord source;
        try (SqliteBanRepository repository = open()) {
            source = candidate(repository); PermanentIpReview review = preview(repository, source); secretToken = pending(repository, review);
            repository.cancelPermanentIpReview(secretToken, ACTOR, "User typed " + IP).join();
            var audit = repository.permanentIpReviewHistory(source.caseId(), 1).join(); assertEquals(1, audit.size()); assertEquals("administrator-cancelled", audit.getFirst().reasonCode());
            assertFalse(audit.toString().contains(IP)); assertFalse(review.toString().contains(IP)); assertFalse(review.candidate().toString().contains(review.candidate().ipLookup()));
            assertThrows(SQLException.class, () -> sql("UPDATE permanent_ip_audit SET reason_code='changed'"));
            assertThrows(SQLException.class, () -> sql("DELETE FROM permanent_ip_audit"));
            assertThrows(IllegalArgumentException.class, () -> repository.permanentIpReviewHistory(source.caseId(), 0));
        }
        String bytes = new String(Files.readAllBytes(directory.resolve("bans/bans.db")), StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains(IP)); assertFalse(bytes.contains(secretToken));
    }
    @Test void policySourceAndProofTamperingCannotApply() throws Exception {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); String token = pending(repository, review);
            sql("UPDATE bans SET reason='changed source' WHERE case_id='" + source.caseId() + "'");
            refuses(() -> apply(repository, token, review)); assertTrue(repository.matchIp(IP, NOW).join().isEmpty());
        }
    }
    @Test void evidenceHashMismatchCannotApplyAndIsAudited() throws Exception {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); String token = pending(repository, review);
            sql("UPDATE evidence SET sha256='" + "0".repeat(64) + "' WHERE id='" + source.evidenceId() + "'");
            refuses(() -> apply(repository, token, review)); assertTrue(repository.matchIp(IP, NOW).join().isEmpty());
            assertEquals("REJECT", repository.permanentIpReviewHistory(source.caseId(), 1).join().getFirst().event());
        }
    }
    @Test void requestCapacityIsBoundedAndExpiredRowsReleaseSlots() {
        StorageConfig defaults = StorageConfig.defaults();
        StorageConfig small = new StorageConfig(2, defaults.maxEvidenceBytes(), defaults.maxEvidenceRecordBytes(), defaults.evidenceRetention(), defaults.maxBackups());
        try (SqliteBanRepository repository = SqliteBanRepository.open(directory, small, clock)) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source);
            pending(repository, review); pending(repository, review); refuses(() -> pending(repository, review));
            clock.now = NOW.plusSeconds(30); assertEquals(2, repository.expirePermanentIpReviews().join());
            assertNotNull(pending(repository, preview(repository, source)));
        }
    }
    @Test void schemaOneMigrationPreservesOldBansEvidenceAuditAndNeverInfersCandidate() throws Exception {
        BanRecord old;
        try (SqliteBanRepository repository = open()) {
            old = repository.createBan(request(repository, "{\"legacy\":true}")).join();
            repository.revokeCaseUuid(old.caseId(), "legacy-admin", "old unban", NOW).join();
        }
        // Exact pre-v2 schema, preserving all prior tables and data.
        sql("DROP TABLE permanent_ip_audit"); sql("DROP TABLE permanent_ip_requests"); sql("DROP TABLE permanent_ip_candidates"); sql("PRAGMA user_version=1");
        try (SqliteBanRepository repository = open()) {
            assertEquals(old.id(), repository.findCase(old.caseId()).join().orElseThrow().id()); assertEquals(1, repository.unbanHistory(old.caseId()).join().size());
            assertEquals("{\"legacy\":true}", repository.evidence(old.evidenceId()).join().orElseThrow().telemetryJson());
            assertTrue(repository.previewPermanentIp(old.caseId(), POLICY, true).join().isEmpty()); assertEquals(0, count("permanent_ip_candidates"));
            try (Connection c = raw(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) { assertTrue(r.next()); assertEquals(2, r.getInt(1)); }
        }
    }
    @Test void wrongKeySchemaOneDatabaseIsNeverMigrated() throws Exception {
        BanRecord source;
        try (SqliteBanRepository repository = open()) { source = repository.createBan(request(repository, "{}" )).join(); }
        sql("DROP TABLE permanent_ip_audit"); sql("DROP TABLE permanent_ip_requests"); sql("DROP TABLE permanent_ip_candidates"); sql("PRAGMA user_version=1");
        sql("UPDATE meta SET value='wrong-key-fingerprint' WHERE key='ip_key_fingerprint'");
        assertThrows(StorageException.class, this::open);
        try (Connection c = raw(); Statement statement = c.createStatement()) {
            try (ResultSet r = statement.executeQuery("PRAGMA user_version")) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
            try (ResultSet r = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='permanent_ip_candidates'")) { assertTrue(r.next()); assertEquals(0, r.getInt(1)); }
            try (ResultSet r = statement.executeQuery("SELECT case_id FROM bans")) { assertTrue(r.next()); assertEquals(source.caseId(), r.getString(1)); }
        }
    }
    @Test void schemaOneMigrationFailureRollsBackAllNewTablesAndVersion() throws Exception {
        try (SqliteBanRepository repository = open()) { repository.createBan(request(repository, "{}" )).join(); }
        sql("DROP TABLE permanent_ip_audit"); sql("DROP TABLE permanent_ip_requests"); sql("DROP TABLE permanent_ip_candidates"); sql("PRAGMA user_version=1");
        sql("CREATE TABLE permanent_ip_requests (original_data TEXT)"); sql("INSERT INTO permanent_ip_requests VALUES ('preserve')");
        assertThrows(StorageException.class, this::open);
        try (Connection c = raw(); Statement statement = c.createStatement()) {
            try (ResultSet r = statement.executeQuery("PRAGMA user_version")) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
            try (ResultSet r = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='permanent_ip_candidates'")) { assertTrue(r.next()); assertEquals(0, r.getInt(1)); }
            try (ResultSet r = statement.executeQuery("SELECT original_data FROM permanent_ip_requests")) { assertTrue(r.next()); assertEquals("preserve", r.getString(1)); }
        }
    }
    @Test void reconciliationSweepUsesBoundedStableKeysetAndStrongestActiveScopes() {
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); BanRecord applied = apply(repository, pending(repository, review), review);
            BanReconciliationSnapshot snapshot = repository.reconciliationState(source.caseId()).join();
            assertEquals(Optional.of(source), snapshot.record()); assertEquals(Optional.of(source), snapshot.uuidRestriction()); assertEquals(Optional.of(applied), snapshot.ipRestriction());
            assertEquals(Optional.of(IP), snapshot.protectedIp()); assertFalse(snapshot.toString().contains(IP)); assertEquals(repository.mutationRevision(), snapshot.revision());
            List<String> all = new ArrayList<>(); String cursor = null;
            while (true) { List<String> page = repository.retainedCaseIdsAfter(cursor, 1).join(); if (page.isEmpty()) break; all.addAll(page); cursor = page.getLast(); }
            assertEquals(List.of(source.caseId(), applied.caseId()).stream().sorted().toList(), all);
            assertThrows(IllegalArgumentException.class, () -> repository.retainedCaseIdsAfter(null, 129));
            repository.revokeCaseUuid(source.caseId(), ACTOR.toString(), "Unban source", NOW).join();
            BanReconciliationSnapshot after = repository.reconciliationState(source.caseId()).join(); assertTrue(after.uuidRestriction().isEmpty()); assertEquals(Optional.of(applied), after.ipRestriction());
            assertEquals(2, repository.retainedCaseIdsAfter("", 128).join().size());
        }
    }
    @Test void permanentApprovalEvidenceRemainsPinnedAfterSourceUuidExpires() {
        BanRecord applied;
        try (SqliteBanRepository repository = open()) {
            BanRecord source = candidate(repository); PermanentIpReview review = preview(repository, source); applied = apply(repository, pending(repository, review), review);
            clock.now = NOW.plus(Duration.ofDays(100)); repository.pruneEvidence(clock.instant()).join();
            assertTrue(repository.evidence(applied.evidenceId()).join().orElseThrow().payloadAvailable()); assertEquals(List.of(applied), repository.matchIp(IP, clock.instant()).join());
        }
        try (SqliteBanRepository repository = open()) { assertEquals(List.of(applied), repository.matchIp(IP, clock.instant()).join()); }
    }
    private static final class MovingClock extends Clock {
        private Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
