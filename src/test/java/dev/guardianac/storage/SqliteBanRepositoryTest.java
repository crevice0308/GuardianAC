// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteBanRepositoryTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-10-13T20:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID PLAYER = UUID.fromString("6a7fb0f5-9e96-47be-bc00-ff5956600001");
    private static final String IP = "203.0.113.47";
    private SqliteBanRepository open() { return SqliteBanRepository.open(directory, StorageConfig.defaults(), CLOCK); }
    private EvidenceRecord evidence(SqliteBanRepository repository, UUID player, String caseId) {
        return repository.saveEvidence(new EvidenceInput(player, caseId, NOW, "{\"samples\":[{\"tick\":100,\"x\":1,\"yaw\":70}],\"checks\":[\"speed\",\"fly\"]}")).join();
    }
    private BanRecord ban(SqliteBanRepository repository, BanType type, Instant expiry) {
        String caseId = SqliteBanRepository.newCaseId(); EvidenceRecord evidence = evidence(repository, PLAYER, caseId);
        return repository.createBan(new BanRequest(PLAYER, "ExamplePlayer", IP, type, "Unfair Advantage", expiry, 150, caseId, evidence.id(), "GuardianAC")).join();
    }
    @Test void restartPreservesBansEvidenceAndProtectedIp() {
        BanRecord original;
        try (SqliteBanRepository repository = open()) { original = ban(repository, BanType.DUAL, null); }
        try (SqliteBanRepository repository = open()) {
            assertEquals(original, repository.findCase(original.caseId()).join().orElseThrow());
            assertEquals(1, repository.activeForPlayer(PLAYER, NOW).join().size());
            assertEquals(1, repository.matchIp(IP, NOW).join().size());
            assertTrue(repository.evidence(original.evidenceId()).join().orElseThrow().payloadAvailable());
            assertEquals(IP, repository.protectedIp(original.caseId()).join().orElseThrow());
        }
        assertFalse(new String(read(directory.resolve("bans/bans.db")), StandardCharsets.ISO_8859_1).contains(IP));
    }
    @Test void expiryHasExactExclusiveBoundary() {
        try (SqliteBanRepository repository = open()) {
            BanRecord ban = ban(repository, BanType.DUAL, NOW.plusSeconds(3600));
            assertTrue(ban.activeAt(NOW.plusSeconds(3599))); assertFalse(ban.activeAt(NOW.plusSeconds(3600)));
            assertTrue(repository.activeForPlayer(PLAYER, NOW.minusMillis(1)).join().isEmpty());
            assertEquals(1, repository.matchIp(IP, NOW.plusSeconds(3599)).join().size());
            assertTrue(repository.matchIp(IP, NOW.plusSeconds(3600)).join().isEmpty());
            assertEquals(1, repository.historyForPlayer(PLAYER, 10).join().size());
        }
    }
    @Test void uuidOnlyDoesNotApplyToSharedAddress() {
        try (SqliteBanRepository repository = open()) {
            ban(repository, BanType.UUID, null);
            assertEquals(1, repository.activeForPlayer(PLAYER, NOW).join().size());
            assertTrue(repository.matchIp(IP, NOW).join().isEmpty());
        }
    }
    @Test void automaticIpPolicyRechecksAllAccountsInsideCommit() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,true,NOW).join();
            assertEquals(new IpBanPolicy.Association(1,1,true),repository.ipAssociation(PLAYER,IP,NOW.minusSeconds(3600)).join());
            // A second, untrusted account appears after the caller's initial policy read.
            repository.registerIp(UUID.randomUUID(),IP,false,NOW).join();
            String caseId=SqliteBanRepository.newCaseId();EvidenceRecord ev=evidence(repository,PLAYER,caseId);
            BanRecord result=repository.createAutomaticBan(new BanRequest(PLAYER,"Player",IP,BanType.DUAL,"Reason",NOW.plusSeconds(3600),100,caseId,ev.id(),"GuardianAC"),NOW.minusSeconds(3600),NOW.plusSeconds(7200),true,()->true).join();
            assertEquals(BanType.UUID,result.type());assertFalse(result.hasProtectedIp());assertEquals(NOW.plusSeconds(7200),result.expiresAt());
            assertTrue(repository.matchIp(IP,NOW).join().isEmpty());assertEquals(1,repository.activeForPlayer(PLAYER,NOW).join().size());
        }
    }
    @Test void syntheticAuthenticatedUnsharedDualCommitsBothComponents() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,true,NOW).join();
            String caseId=SqliteBanRepository.newCaseId();EvidenceRecord ev=evidence(repository,PLAYER,caseId);
            BanRecord result=repository.createAutomaticBan(new BanRequest(PLAYER,"Player",IP,BanType.DUAL,"Reason",NOW.plusSeconds(3600),100,caseId,ev.id(),"GuardianAC"),NOW.minusSeconds(3600),NOW.plusSeconds(7200),true,()->true).join();
            assertEquals(BanType.DUAL,result.type());assertEquals(1,repository.matchIp(IP,NOW).join().size());
        }
    }
    @Test void automaticCommitGuardCanRollbackAlreadyInsertedSanction() {
        try(SqliteBanRepository repository=open()) {
            String caseId=SqliteBanRepository.newCaseId();EvidenceRecord ev=evidence(repository,PLAYER,caseId);
            BanRequest request=new BanRequest(PLAYER,"Player",IP,BanType.DUAL,"Reason",NOW.plusSeconds(3600),100,caseId,ev.id(),"GuardianAC");
            assertThrows(java.util.concurrent.CancellationException.class,()->repository.createAutomaticBan(request,NOW.minusSeconds(3600),NOW.plusSeconds(3600),()->false).join());
            assertTrue(repository.findCase(caseId).join().isEmpty());assertTrue(repository.activeForPlayer(PLAYER,NOW).join().isEmpty());
            assertTrue(repository.matchIp(IP,NOW).join().isEmpty());assertTrue(repository.evidence(ev.id()).join().orElseThrow().payloadAvailable());
            assertNotNull(repository.createAutomaticBan(request,NOW.minusSeconds(3600),NOW.plusSeconds(3600),()->true).join());
        }
    }
    @Test void authenticatedTemporaryDualStillObeysCommitGuard() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,true,NOW).join();
            String caseId=SqliteBanRepository.newCaseId();EvidenceRecord ev=evidence(repository,PLAYER,caseId);
            BanRequest request=new BanRequest(PLAYER,"Player",IP,BanType.DUAL,"Reason",NOW.plusSeconds(3600),100,caseId,ev.id(),"GuardianAC");
            assertThrows(java.util.concurrent.CancellationException.class,()->repository.createAutomaticBan(request,NOW.minusSeconds(3600),NOW.plusSeconds(7200),true,()->false).join());
            assertTrue(repository.findCase(caseId).join().isEmpty());assertTrue(repository.matchIp(IP,NOW).join().isEmpty());
            assertEquals(BanType.DUAL,repository.createAutomaticBan(request,NOW.minusSeconds(3600),NOW.plusSeconds(7200),true,()->true).join().type());
        }
    }
    private BanRecord automatic(SqliteBanRepository repository,BanType type,Instant ipExpiry,Instant uuidExpiry,boolean nativeIdentity) {
        String caseId=SqliteBanRepository.newCaseId();EvidenceRecord ev=evidence(repository,PLAYER,caseId);
        return repository.createAutomaticBan(new BanRequest(PLAYER,"Player",IP,type,"Reason",ipExpiry,100,caseId,ev.id(),"GuardianAC"),
            NOW.minusSeconds(3600),uuidExpiry,nativeIdentity,()->true).join();
    }
    private void assertUuidFallback(SqliteBanRepository repository,BanRecord result,Instant expiry) {
        assertEquals(BanType.UUID,result.type());assertFalse(result.hasProtectedIp());assertEquals(expiry,result.expiresAt());
        assertTrue(repository.matchIp(IP,NOW).join().isEmpty());
        assertEquals(result,repository.findCase(result.caseId()).join().orElseThrow());
    }
    @Test void automaticIpWithNoObservationsFallsBackToUuidAndRetainsUuidExpiry() {
        try(SqliteBanRepository repository=open()) {
            assertEquals(IpBanPolicy.Association.unknown(),repository.ipAssociation(PLAYER,IP,NOW.minusSeconds(3600)).join());
            for(BanType type:List.of(BanType.IP,BanType.DUAL))
                assertUuidFallback(repository,automatic(repository,type,NOW.plusSeconds(3600),NOW.plusSeconds(7200),true),NOW.plusSeconds(7200));
        }
    }
    @Test void twoUntrustedObservedAccountsCannotQualifyForAutomaticIp() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,false,NOW).join();repository.registerIp(UUID.randomUUID(),IP,false,NOW).join();
            assertEquals(2,repository.observedAccountCount(IP,NOW.minusSeconds(3600)).join());
            assertEquals(0,repository.trustedAccountCount(IP,NOW.minusSeconds(3600)).join());
            for(BanType type:List.of(BanType.IP,BanType.DUAL))
                assertUuidFallback(repository,automatic(repository,type,NOW.plusSeconds(3600),NOW.plusSeconds(7200),true),NOW.plusSeconds(7200));
        }
    }
    @Test void mixedTrustedAndUntrustedAccountsCannotQualifyForAutomaticIp() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,true,NOW).join();repository.registerIp(UUID.randomUUID(),IP,false,NOW).join();
            assertEquals(new IpBanPolicy.Association(2,1,true),repository.ipAssociation(PLAYER,IP,NOW.minusSeconds(3600)).join());
            assertUuidFallback(repository,automatic(repository,BanType.DUAL,NOW.plusSeconds(3600),null,true),null);
        }
    }
    @Test void onlyUntrustedTargetCannotQualifyEvenWhenCallerClaimsNativeIdentity() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,false,NOW).join();
            assertUuidFallback(repository,automatic(repository,BanType.DUAL,NOW.plusSeconds(3600),NOW.plusSeconds(7200),true),NOW.plusSeconds(7200));
        }
    }
    @Test void differentTrustedUuidCannotStandInForMissingTargetAssociation() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(UUID.randomUUID(),IP,true,NOW).join();
            assertEquals(new IpBanPolicy.Association(1,1,false),repository.ipAssociation(PLAYER,IP,NOW.minusSeconds(3600)).join());
            assertUuidFallback(repository,automatic(repository,BanType.DUAL,NOW.plusSeconds(3600),NOW.plusSeconds(7200),true),NOW.plusSeconds(7200));
        }
    }
    @Test void targetTrustAtDifferentAddressCannotQualifyThisAddress() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,"203.0.113.48",true,NOW).join();repository.registerIp(PLAYER,IP,false,NOW).join();
            assertUuidFallback(repository,automatic(repository,BanType.DUAL,NOW.plusSeconds(3600),NOW.plusSeconds(7200),true),NOW.plusSeconds(7200));
        }
    }
    @Test void expiredTargetObservationDoesNotProveExclusiveAddress() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,true,NOW.minusSeconds(3601)).join();
            assertUuidFallback(repository,automatic(repository,BanType.DUAL,NOW.plusSeconds(3600),NOW.plusSeconds(7200),true),NOW.plusSeconds(7200));
        }
    }
    @Test void nativeIdentityIsRequiredEvenWhenTrustedTargetWasPersisted() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,true,NOW).join();
            assertUuidFallback(repository,automatic(repository,BanType.DUAL,NOW.plusSeconds(3600),NOW.plusSeconds(7200),false),NOW.plusSeconds(7200));
        }
    }
    @Test void legacyAutomaticEntryPointWithoutIdentityProofFallsBack() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,true,NOW).join();
            String caseId=SqliteBanRepository.newCaseId();EvidenceRecord ev=evidence(repository,PLAYER,caseId);
            BanRecord result=repository.createAutomaticBan(new BanRequest(PLAYER,"Player",IP,BanType.DUAL,"Reason",NOW.plusSeconds(3600),100,caseId,ev.id(),"GuardianAC"),NOW.minusSeconds(3600)).join();
            assertUuidFallback(repository,result,NOW.plusSeconds(3600));
        }
    }
    @Test void automaticPermanentIpCannotBeCreatedByStorageEntryPoint() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,true,NOW).join();
            for(BanType type:List.of(BanType.IP,BanType.DUAL))
                assertUuidFallback(repository,automatic(repository,type,null,NOW.plusSeconds(7200),true),NOW.plusSeconds(7200));
        }
    }
    @Test void lateTrustDowngradeAfterPreliminaryReadForcesUuidFallback() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,true,NOW.minusSeconds(1)).join();
            assertTrue(repository.ipAssociation(PLAYER,IP,NOW.minusSeconds(3600)).join().targetTrusted());
            repository.registerIp(PLAYER,IP,false,NOW).join();
            assertUuidFallback(repository,automatic(repository,BanType.DUAL,NOW.plusSeconds(3600),NOW.plusSeconds(7200),true),NOW.plusSeconds(7200));
        }
    }
    @Test void trustTracksLatestObservationAndOldOrEqualTimeWritesCannotRestoreIt() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,true,NOW.minusSeconds(2)).join();repository.registerIp(PLAYER,IP,false,NOW).join();
            repository.registerIp(PLAYER,IP,true,NOW.minusSeconds(1)).join();repository.registerIp(PLAYER,IP,true,NOW).join();
            assertEquals(new IpBanPolicy.Association(1,0,false),repository.ipAssociation(PLAYER,IP,NOW.minusSeconds(3600)).join());
            assertEquals(0,repository.trustedAccountCount(IP,NOW.minusSeconds(3600)).join());
            repository.registerIp(PLAYER,IP,true,NOW.plusSeconds(1)).join();
            assertEquals(new IpBanPolicy.Association(1,1,true),repository.ipAssociation(PLAYER,IP,NOW.minusSeconds(3600)).join());
        }
    }
    @Test void observedAccountCountIsDistinctAndRetainsTrustlessHistory() {
        try(SqliteBanRepository repository=open()) {
            repository.registerIp(PLAYER,IP,false,NOW).join();repository.registerIp(PLAYER,IP,false,NOW).join();
            repository.registerIp(UUID.randomUUID(),IP,true,NOW).join();repository.registerIp(UUID.randomUUID(),IP,false,NOW.minusSeconds(2)).join();
            assertEquals(2,repository.observedAccountCount(IP,NOW.minusSeconds(1)).join());
            assertEquals(1,repository.trustedAccountCount(IP,NOW.minusSeconds(1)).join());
            assertEquals(3,repository.observedAccountCount(IP,NOW.minusSeconds(3)).join());
            assertEquals(0,repository.observedAccountCount(IP,NOW.plusSeconds(1)).join());
        }
    }
    @Test void ipOnlyDoesNotBanUnrelatedUuidOnAnotherAddress() {
        try (SqliteBanRepository repository = open()) {
            ban(repository, BanType.IP, null);
            assertTrue(repository.activeForPlayer(PLAYER, NOW).join().isEmpty());
            assertEquals(1, repository.matchIp(IP, NOW).join().size());
            assertTrue(repository.matchIp("203.0.113.48", NOW).join().isEmpty());
        }
    }
    @Test void banRequiresPersistedMatchingEvidence() {
        try (SqliteBanRepository repository = open()) {
            String caseId = SqliteBanRepository.newCaseId();
            assertThrows(CompletionException.class, () -> repository.createBan(new BanRequest(PLAYER, "Test", IP, BanType.UUID, "Reason", null, 4, caseId, UUID.randomUUID().toString(), "Admin")).join());
            EvidenceRecord record = evidence(repository, UUID.randomUUID(), caseId);
            assertThrows(CompletionException.class, () -> repository.createBan(new BanRequest(PLAYER, "Test", IP, BanType.UUID, "Reason", null, 4, caseId, record.id(), "Admin")).join());
            assertTrue(repository.recentBans(10).join().isEmpty());
        }
    }
    @Test void mismatchedCaseAndPastExpiryFailWithoutSavingBan() {
        try (SqliteBanRepository repository = open()) {
            String caseId = SqliteBanRepository.newCaseId(); EvidenceRecord record = evidence(repository, PLAYER, caseId);
            assertThrows(CompletionException.class, () -> repository.createBan(new BanRequest(PLAYER, "Test", IP, BanType.UUID, "Reason", null, 4, SqliteBanRepository.newCaseId(), record.id(), "Admin")).join());
            assertThrows(CompletionException.class, () -> repository.createBan(new BanRequest(PLAYER, "Test", IP, BanType.UUID, "Reason", NOW, 4, caseId, record.id(), "Admin")).join());
            assertTrue(repository.recentBans(10).join().isEmpty());
        }
    }
    @Test void parameterizedReasonCannotInjectSql() {
        try (SqliteBanRepository repository = open()) {
            String caseId = SqliteBanRepository.newCaseId(); EvidenceRecord record = evidence(repository, PLAYER, caseId);
            String attack = "x'); DROP TABLE bans; --";
            BanRecord saved = repository.createBan(new BanRequest(PLAYER, "x'OR'1'='1", IP, BanType.UUID, attack, null, 4, caseId, record.id(), "Admin")).join();
            assertEquals(attack, saved.reason()); assertEquals(1, repository.recentBans(10).join().size());
            assertThrows(IllegalArgumentException.class, () -> repository.findCase("GAC-X'OR'1'='1"));
            assertThrows(IllegalArgumentException.class, () -> repository.matchIp("1.1.1.1' OR 1=1", NOW));
        }
    }
    @Test void playerNameLookupIsExactCaseInsensitiveAndCannotBecomeWildcard() {
        try (SqliteBanRepository repository = open()) {
            ban(repository, BanType.UUID, null);
            assertEquals(1, repository.findCasesByPlayerName("examplePLAYER", 5).join().size());
            assertTrue(repository.findCasesByPlayerName("Example%", 5).join().isEmpty());
            assertTrue(repository.findCasesByPlayerName("Example", 5).join().isEmpty());
            assertTrue(repository.findCasesByPlayerName("x' OR '1'='1", 5).join().isEmpty());
        }
    }
    @Test void duplicateCaseRollsBackAndPreservesOriginal() {
        try (SqliteBanRepository repository = open()) {
            BanRecord saved = ban(repository, BanType.UUID, null);
            assertThrows(CompletionException.class, () -> repository.createBan(new BanRequest(PLAYER, "Test", IP, BanType.IP, "Changed", null, 999, saved.caseId(), saved.evidenceId(), "Admin")).join());
            assertEquals(saved, repository.findCase(saved.caseId()).join().orElseThrow());
        }
    }
    @Test void dualUnbanScopesDoNotAccidentallyRemoveOtherRestriction() {
        try (SqliteBanRepository repository = open()) {
            BanRecord ban = ban(repository, BanType.DUAL, null);
            assertEquals(1, repository.revokeUuid(PLAYER, "Admin", "Appeal approved", NOW.plusSeconds(1)).join().size());
            assertTrue(repository.activeForPlayer(PLAYER, NOW.plusSeconds(2)).join().isEmpty());
            assertEquals(1, repository.matchIp(IP, NOW.plusSeconds(2)).join().size());
            assertTrue(repository.revokeUuid(PLAYER, "Admin", "Repeat", NOW.plusSeconds(2)).join().isEmpty());
            assertEquals(1, repository.revokeIp(IP, "Admin", "IP cleared", NOW.plusSeconds(3)).join().size());
            assertTrue(repository.matchIp(IP, NOW.plusSeconds(4)).join().isEmpty());
            BanRecord revoked = repository.findCase(ban.caseId()).join().orElseThrow();
            assertNotNull(revoked.revokedAt()); assertEquals(2, repository.unbanHistory(ban.caseId()).join().size());
            assertTrue(repository.evidence(ban.evidenceId()).join().orElseThrow().payloadAvailable());
        }
    }
    @Test void caseUuidConfirmationNeverRevokesExistingOtherOrNewCase() {
        try (SqliteBanRepository repository = open()) {
            BanRecord target = ban(repository, BanType.DUAL, null);
            BanRecord otherExisting = ban(repository, BanType.DUAL, null);
            String confirmedCaseId = target.caseId(); // Snapshot shown in the confirmation preview.
            BanRecord addedAfterPreview = ban(repository, BanType.DUAL, null);
            assertEquals(List.of(target), repository.revokeCaseUuid(confirmedCaseId, "Admin", "Confirmed preview", NOW).join());
            BanRecord changed = repository.findCase(confirmedCaseId).join().orElseThrow();
            assertFalse(changed.uuidActiveAt(NOW)); assertTrue(changed.ipActiveAt(NOW));
            for (BanRecord unchanged : List.of(otherExisting, addedAfterPreview)) {
                assertEquals(unchanged, repository.findCase(unchanged.caseId()).join().orElseThrow());
                assertTrue(repository.unbanHistory(unchanged.caseId()).join().isEmpty());
            }
            assertTrue(repository.revokeCaseUuid(confirmedCaseId, "Admin", "Repeated confirmation", NOW).join().isEmpty());
            assertEquals("UUID", repository.unbanHistory(confirmedCaseId).join().getFirst().scope());
        }
    }
    @Test void caseIpConfirmationNeverRevokesExistingOtherOrNewCaseOnSameIp() {
        try (SqliteBanRepository repository = open()) {
            BanRecord target = ban(repository, BanType.DUAL, null);
            BanRecord otherExisting = ban(repository, BanType.DUAL, null);
            String confirmedCaseId = target.caseId();
            BanRecord addedAfterPreview = ban(repository, BanType.DUAL, null);
            assertEquals(List.of(target), repository.revokeCaseIp(confirmedCaseId, "Admin", "Confirmed preview", NOW).join());
            BanRecord changed = repository.findCase(confirmedCaseId).join().orElseThrow();
            assertTrue(changed.uuidActiveAt(NOW)); assertFalse(changed.ipActiveAt(NOW));
            assertEquals(2, repository.matchIp(IP, NOW).join().size());
            for (BanRecord unchanged : List.of(otherExisting, addedAfterPreview)) {
                assertEquals(unchanged, repository.findCase(unchanged.caseId()).join().orElseThrow());
                assertTrue(repository.unbanHistory(unchanged.caseId()).join().isEmpty());
            }
            assertTrue(repository.revokeCaseIp(confirmedCaseId, "Admin", "Repeated confirmation", NOW).join().isEmpty());
            assertEquals("IP", repository.unbanHistory(confirmedCaseId).join().getFirst().scope());
            repository.revokeCaseUuid(confirmedCaseId, "Admin", "Other component confirmed", NOW).join();
            assertNotNull(repository.findCase(confirmedCaseId).join().orElseThrow().revokedAt());
            assertEquals(2, repository.unbanHistory(confirmedCaseId).join().size());
        }
    }
    @Test void exactCasePartialUnbanCannotRevokeInapplicableComponent() {
        try (SqliteBanRepository repository = open()) {
            BanRecord uuid = ban(repository, BanType.UUID, null); BanRecord ip = ban(repository, BanType.IP, null);
            assertTrue(repository.revokeCaseIp(uuid.caseId(), "Admin", "Wrong component", NOW).join().isEmpty());
            assertTrue(repository.revokeCaseUuid(ip.caseId(), "Admin", "Wrong component", NOW).join().isEmpty());
            assertTrue(repository.unbanHistory(uuid.caseId()).join().isEmpty());
            assertTrue(repository.unbanHistory(ip.caseId()).join().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> repository.revokeCaseUuid("GAC-X' OR 1=1", "Admin", "Reason", NOW));
            assertThrows(IllegalArgumentException.class, () -> repository.revokeCaseIp("GAC-X' OR 1=1", "Admin", "Reason", NOW));
        }
    }
    @Test void anomalyEvidenceIsDiscoverableWithoutAnyBan() {
        try (SqliteBanRepository repository = open()) {
            String caseId = SqliteBanRepository.newCaseId();
            EvidenceRecord first = evidence(repository, PLAYER, caseId);
            EvidenceRecord latest = repository.saveEvidence(new EvidenceInput(PLAYER, caseId, NOW.plusSeconds(1), "{\"sample\":2}")).join();
            assertTrue(repository.recentBans(10).join().isEmpty());
            assertEquals(latest, repository.evidenceForCase(caseId).join().orElseThrow());
            List<EvidenceSummary> summaries = repository.recentEvidence(10).join();
            assertEquals(2, summaries.size()); assertEquals(latest.id(), summaries.getFirst().id());
            assertTrue(summaries.getFirst().payloadAvailable()); assertEquals(latest.sha256(), summaries.getFirst().sha256());
            assertEquals(first, repository.evidence(first.id()).join().orElseThrow());
            assertEquals(1, repository.recentEvidence(1).join().size());
            assertTrue(repository.evidenceForCase(SqliteBanRepository.newCaseId()).join().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> repository.evidenceForCase("GAC-X' OR 1=1"));
            assertThrows(IllegalArgumentException.class, () -> repository.recentEvidence(0));
        }
    }
    @Test void caseAndPlayerUnbanPreserveHistoryAndAreIdempotent() {
        try (SqliteBanRepository repository = open()) {
            BanRecord first = ban(repository, BanType.UUID, null); BanRecord second = ban(repository, BanType.DUAL, null);
            assertEquals(1, repository.revokeCase(first.caseId(), "Admin", "Appeal", NOW).join().size());
            assertEquals(1, repository.revokePlayer(PLAYER, "Admin", "Clear all", NOW).join().size());
            assertTrue(repository.revokePlayer(PLAYER, "Admin", "Clear all", NOW).join().isEmpty());
            assertEquals(2, repository.historyForPlayer(PLAYER, 10).join().size());
            assertEquals(1, repository.unbanHistory(second.caseId()).join().size());
        }
    }
    @Test void nativeOwnershipNeverMatchesAnotherPluginOrEditedSource() {
        try (SqliteBanRepository repository = open()) {
            BanRecord saved = ban(repository, BanType.DUAL, null);
            assertTrue(repository.ownsSyncToken(saved.caseId(), saved.syncToken()).join());
            assertFalse(repository.ownsSyncToken(saved.caseId(), UUID.randomUUID().toString()).join());
            assertTrue(repository.ownsNativeSource(saved.caseId(), saved.ownershipMarker()).join());
            assertFalse(repository.ownsNativeSource(saved.caseId(), "OtherPlugin").join());
            assertFalse(repository.ownsNativeSource(saved.caseId(), saved.ownershipMarker() + " edited").join());
            repository.revokeCase(saved.caseId(), "Admin", "Appeal", NOW).join();
            assertTrue(repository.ownsNativeSource(saved.caseId(), saved.ownershipMarker()).join());
        }
    }
    @Test void trustCountsAccountsRatherThanSessionsAndExpires() {
        try (SqliteBanRepository repository = open()) {
            repository.registerIp(PLAYER, IP, true, NOW).join(); repository.registerIp(PLAYER, IP, true, NOW).join();
            repository.registerIp(UUID.randomUUID(), IP, false, NOW).join();
            assertEquals(1, repository.trustedAccountCount(IP, NOW.minusSeconds(1)).join());
            repository.registerIp(UUID.randomUUID(), IP, true, NOW).join();
            assertEquals(2, repository.trustedAccountCount(IP, NOW.minusSeconds(1)).join());
            assertEquals(0, repository.trustedAccountCount(IP, NOW.plusSeconds(1)).join());
        }
    }
    @Test void backupSnapshotsWalAndRestoresOffline() throws Exception {
        Path backup; BanRecord record;
        try (SqliteBanRepository repository = open()) {
            record = ban(repository, BanType.DUAL, null); backup = repository.backup().join();
            assertTrue(Files.isRegularFile(backup));
            assertThrows(StorageException.class, () -> SqliteBanRepository.restoreBackup(directory, backup.getFileName().toString()));
            repository.revokeCase(record.caseId(), "Admin", "After snapshot", NOW).join();
        }
        Path quarantine = SqliteBanRepository.restoreBackup(directory, backup.getFileName().toString());
        assertTrue(Files.isRegularFile(quarantine.resolve("bans.db")));
        try (SqliteBanRepository repository = open()) {
            assertEquals(1, repository.activeForPlayer(PLAYER, NOW).join().size());
            assertEquals(IP, repository.protectedIp(record.caseId()).join().orElseThrow());
        }
    }
    @Test void backupCountBoundedAndTraversalRejected() throws Exception {
        StorageConfig config = new StorageConfig(16, 4096, 1024, Duration.ofDays(1), 2);
        try (SqliteBanRepository repository = SqliteBanRepository.open(directory, config, CLOCK)) {
            ban(repository, BanType.UUID, null);
            for (int i = 0; i < 4; i++) assertTrue(Files.isRegularFile(repository.backup().join()));
        }
        try (var backups = Files.list(directory.resolve("bans/backups"))) { assertEquals(2, backups.filter(p -> p.toString().endsWith(".db")).count()); }
        assertThrows(IllegalArgumentException.class, () -> SqliteBanRepository.restoreBackup(directory, "../../sensitive.db"));
    }
    @Test void corruptDatabaseFailsClosedWithoutReplacingOriginal() throws Exception {
        try (SqliteBanRepository repository = open()) { ban(repository, BanType.UUID, null); }
        byte[] corrupt = "NOT A DATABASE".repeat(100).getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("bans/bans.db"), corrupt);
        assertThrows(StorageException.class, this::open);
        assertArrayEquals(corrupt, Files.readAllBytes(directory.resolve("bans/bans.db")));
    }
    @Test void missingOrChangedKeyFailsClosed() throws Exception {
        try (SqliteBanRepository repository = open()) { ban(repository, BanType.DUAL, null); }
        Path key = directory.resolve("bans/ip-keys.bin"); byte[] original = Files.readAllBytes(key);
        byte[] changed = original.clone(); changed[10] ^= 1; Files.write(key, changed);
        assertThrows(StorageException.class, this::open);
        Files.delete(key); assertThrows(StorageException.class, this::open); assertFalse(Files.exists(key));
        Files.write(key, original); try (SqliteBanRepository repository = open()) { assertEquals(1, repository.recentBans(10).join().size()); }
    }
    @Test void evidenceTamperingDisablesReopening() throws Exception {
        try (SqliteBanRepository repository = open()) { ban(repository, BanType.UUID, null); }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("bans/bans.db")); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE evidence SET payload='{}'");
        }
        assertThrows(StorageException.class, this::open);
    }
    @Test void auditIsAppendOnlyEvenThroughSql() throws Exception {
        try (SqliteBanRepository repository = open()) { BanRecord ban = ban(repository, BanType.UUID, null); repository.revokeCase(ban.caseId(), "Admin", "Review", NOW).join(); }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("bans/bans.db")); Statement statement = connection.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("DELETE FROM unban_audit"));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("UPDATE unban_audit SET actor='Changed'"));
        }
    }
    @Test void secondStoreCannotAcquireSameDataDirectory() {
        try (SqliteBanRepository repository = open()) { assertThrows(StorageException.class, this::open); assertTrue(repository.recentBans(1).join().isEmpty()); }
    }
    @Test void symlinkDatabaseAndKeyAreRejected() throws Exception {
        Path outside = directory.resolve("outside"); Files.writeString(outside, "Do not overwrite");
        Files.createDirectories(directory.resolve("bans")); Files.createSymbolicLink(directory.resolve("bans/bans.db"), outside);
        assertThrows(StorageException.class, this::open); assertEquals("Do not overwrite", Files.readString(outside));
        Files.delete(directory.resolve("bans/bans.db")); Files.createSymbolicLink(directory.resolve("bans/ip-keys.bin"), outside);
        assertThrows(StorageException.class, this::open); assertEquals("Do not overwrite", Files.readString(outside));
    }
    @Test void boundedPayloadPrunesUnpinnedButNeverActiveBanEvidence() {
        StorageConfig config = new StorageConfig(16, 1024, 900, Duration.ofDays(1), 2);
        try (SqliteBanRepository repository = SqliteBanRepository.open(directory, config, CLOCK)) {
            String caseId = SqliteBanRepository.newCaseId(); String payload = "{\"samples\":\"" + "x".repeat(700) + "\"}";
            EvidenceRecord evidence = repository.saveEvidence(new EvidenceInput(PLAYER, caseId, NOW, payload)).join();
            BanRecord ban = repository.createBan(new BanRequest(PLAYER, "Test", IP, BanType.UUID, "Reason", null, 100, caseId, evidence.id(), "Admin")).join();
            assertThrows(CompletionException.class, () -> repository.saveEvidence(new EvidenceInput(PLAYER, SqliteBanRepository.newCaseId(), NOW, payload)).join());
            assertTrue(repository.evidence(evidence.id()).join().orElseThrow().payloadAvailable());
            repository.revokeCase(ban.caseId(), "Admin", "Appeal", NOW).join();
            repository.saveEvidence(new EvidenceInput(PLAYER, SqliteBanRepository.newCaseId(), NOW, payload)).join();
            assertFalse(repository.evidence(evidence.id()).join().orElseThrow().payloadAvailable());
            assertTrue(repository.evidenceBytes().join() <= 1024);
        }
    }
    @Test void timeRetentionLeavesActiveEvidenceAndHistoryTombstones() {
        StorageConfig config = new StorageConfig(16, 4096, 1024, Duration.ofDays(1), 2);
        try (SqliteBanRepository repository = SqliteBanRepository.open(directory, config, CLOCK)) {
            BanRecord active = ban(repository, BanType.UUID, null); BanRecord revoked = ban(repository, BanType.UUID, null);
            repository.revokeCase(revoked.caseId(), "Admin", "Appeal", NOW).join();
            repository.pruneEvidence(NOW.plus(Duration.ofDays(2))).join();
            assertTrue(repository.evidence(active.evidenceId()).join().orElseThrow().payloadAvailable());
            EvidenceRecord tombstone = repository.evidence(revoked.evidenceId()).join().orElseThrow();
            assertFalse(tombstone.payloadAvailable()); assertEquals(64, tombstone.sha256().length());
            assertEquals(2, repository.historyForPlayer(PLAYER, 10).join().size());
        }
    }
    @Test void malformedOversizedAndImpossibleTimestampEvidenceAreRejected() {
        StorageConfig config = new StorageConfig(16, 4096, 128, Duration.ofDays(1), 2);
        try (SqliteBanRepository repository = SqliteBanRepository.open(directory, config, CLOCK)) {
            for (String payload : List.of("not json", "[]", "{}; DROP TABLE evidence", "{\"x\":\"" + "x".repeat(200) + "\"}"))
                assertThrows(CompletionException.class, () -> repository.saveEvidence(new EvidenceInput(PLAYER, SqliteBanRepository.newCaseId(), NOW, payload)).join());
            assertThrows(CompletionException.class, () -> repository.saveEvidence(new EvidenceInput(PLAYER, SqliteBanRepository.newCaseId(), NOW.plusSeconds(9999), "{}")).join());
            assertEquals(0, repository.evidenceBytes().join());
        }
    }
    @Test void queueCanSerializeConcurrentEvidenceWrites() {
        try (SqliteBanRepository repository = open()) {
            List<CompletableFuture<EvidenceRecord>> futures = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(i -> repository.saveEvidence(new EvidenceInput(PLAYER, SqliteBanRepository.newCaseId(), NOW, "{\"sample\":" + i + "}"))).toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            assertEquals(100, futures.stream().map(CompletableFuture::join).map(EvidenceRecord::id).distinct().count());
            assertTrue(repository.evidenceBytes().join() > 0);
        }
    }
    @Test void closedStoreFailsEverySubmission() {
        SqliteBanRepository repository = open(); repository.close(); repository.close();
        assertThrows(CompletionException.class, () -> repository.recentBans(1).join());
    }
    private static byte[] read(Path path) { try { return Files.readAllBytes(path); } catch (Exception error) { throw new AssertionError(error); } }
}
