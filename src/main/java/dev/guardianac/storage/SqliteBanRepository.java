// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.guardianac.checks.CheckCatalog;
import dev.guardianac.core.AutomaticBanGate;
import dev.guardianac.core.PersistedBanEvidence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * A single-owner SQLite store. All ordinary I/O runs on one bounded worker; open/close are lifecycle operations.
 * Failed writes must never lead to enforcement. Raw IP disclosure requires an explicit protectedIp(caseId) call.
 * Backups contain encrypted IPs and require ip-keys.bin; keep both private and off-site together.
 */
public final class SqliteBanRepository implements AutoCloseable {
    private static final String ACTIVE = "revoked_at IS NULL AND created_at<=? AND (expires_at IS NULL OR expires_at>?)";
    private final Path directory;
    private final Path database;
    private final StorageConfig config;
    private final Clock clock;
    private final IpPrivacy privacy;
    private final Connection connection;
    private final BoundedStorageExecutor worker;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong mutationRevision = new AtomicLong();

    private SqliteBanRepository(Path directory, StorageConfig config, Clock clock, IpPrivacy privacy,
                                Connection connection, FileChannel lockChannel, FileLock lock) {
        this.directory = directory; this.database = directory.resolve("bans.db"); this.config = config;
        this.clock = clock; this.privacy = privacy; this.connection = connection; this.lockChannel = lockChannel; this.lock = lock;
        worker = new BoundedStorageExecutor(config.queueCapacity());
    }
    public static SqliteBanRepository open(Path pluginDataDirectory, StorageConfig config) {
        return open(pluginDataDirectory, config, Clock.systemUTC());
    }
    public static SqliteBanRepository open(Path pluginDataDirectory, StorageConfig config, Clock clock) {
        Objects.requireNonNull(config); Objects.requireNonNull(clock);
        Connection connection = null; FileChannel channel = null; FileLock lock = null;
        try {
            Path directory = SecurePaths.directory(pluginDataDirectory.resolve("bans"));
            Path database = directory.resolve("bans.db"); checkDatabasePaths(database);
            Path lockPath = directory.resolve("store.lock"); SecurePaths.rejectSymlinks(lockPath);
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            SecurePaths.privatePermissions(lockPath, false); lock = acquireLock(channel);
            boolean existing = Files.exists(database, LinkOption.NOFOLLOW_LINKS) && Files.size(database) > 0;
            IpPrivacy privacy = IpPrivacy.open(directory, !existing);
            connection = connect(database, false);
            SecurePaths.privatePermissions(database, false);
            verifyIntegrity(connection);
            initialize(connection, privacy, existing);
            SqliteBanRepository repository = new SqliteBanRepository(directory, config, clock, privacy, connection, channel, lock);
            repository.verifyActiveEvidence(clock.instant());
            repository.transaction(() -> repository.expirePermanentIpReviewsNow(clock.instant(), true));
            return repository;
        } catch (Exception error) {
            closeQuietly(connection); closeQuietly(lock); closeQuietly(channel);
            if (error instanceof StorageException safe) throw safe;
            throw new StorageException("Ban storage could not be opened safely; automatic enforcement must remain disabled");
        }
    }
    private static FileLock acquireLock(FileChannel channel) throws IOException {
        try {
            FileLock result = channel.tryLock();
            if (result == null) throw new StorageException("Ban storage is already in use");
            return result;
        } catch (OverlappingFileLockException ignored) { throw new StorageException("Ban storage is already in use"); }
    }
    private static void checkDatabasePaths(Path database) throws IOException {
        SecurePaths.rejectSymlinks(database);
        SecurePaths.rejectSymlinks(Path.of(database + "-wal"));
        SecurePaths.rejectSymlinks(Path.of(database + "-shm"));
        SecurePaths.rejectSymlinks(Path.of(database + "-journal"));
    }
    private static Connection connect(Path database, boolean readOnly) throws SQLException {
        // The URI is generated by Path, never from SQL or a user-supplied JDBC URL.
        String uri = database.toUri().toASCIIString();
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + uri + (readOnly ? "?mode=ro" : "?mode=rwc"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000"); statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA trusted_schema=OFF");
            if (readOnly) statement.execute("PRAGMA query_only=ON");
        }
        return connection;
    }
    private static void verifyIntegrity(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equals(result.getString(1)) || result.next())
                throw new StorageException("Ban database integrity check failed; restore a verified backup before enabling enforcement");
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) throw new StorageException("Ban database references are inconsistent; enforcement must remain disabled");
        }
    }
    private static void initialize(Connection connection, IpPrivacy privacy, boolean existing) throws SQLException {
        int version;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            if (!result.next()) throw new StorageException("Database version is unavailable");
            version = result.getInt(1);
        }
        if (version != 0 && version != 1 && version != 2) throw new StorageException("Unsupported ban database version");
        if (existing && version == 0) throw new StorageException("Existing database has no recognized GuardianAC schema");
        try (Statement statement = connection.createStatement()) {
            if (version == 0) statement.execute("PRAGMA auto_vacuum=INCREMENTAL");
            statement.execute("PRAGMA secure_delete=ON");
            try (ResultSet mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!mode.next() || !"wal".equalsIgnoreCase(mode.getString(1))) throw new StorageException("WAL mode is unavailable for ban storage");
            }
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA wal_autocheckpoint=1000"); statement.execute("PRAGMA journal_size_limit=8388608");
        }
        if (version == 0) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                statement.execute("CREATE TABLE evidence (id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, case_id TEXT NOT NULL, recorded_at INTEGER NOT NULL, payload TEXT, sha256 TEXT NOT NULL, original_bytes INTEGER NOT NULL CHECK(original_bytes>0), payload_bytes INTEGER NOT NULL CHECK(payload_bytes>=0), CHECK(payload IS NULL OR json_valid(payload)))");
                statement.execute("CREATE TABLE bans (id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, ip_encrypted TEXT, ip_lookup TEXT, type TEXT NOT NULL CHECK(type IN ('UUID','IP','DUAL')), reason TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER, permanent INTEGER NOT NULL CHECK(permanent IN (0,1)), vl REAL NOT NULL CHECK(vl>=0), case_id TEXT UNIQUE NOT NULL, evidence_id TEXT NOT NULL REFERENCES evidence(id), actor TEXT NOT NULL, sync_token TEXT UNIQUE NOT NULL, revoked_at INTEGER, uuid_revoked_at INTEGER, ip_revoked_at INTEGER, CHECK((expires_at IS NULL AND permanent=1) OR (expires_at IS NOT NULL AND permanent=0 AND expires_at>created_at)), CHECK(type='UUID' OR (ip_lookup IS NOT NULL AND ip_encrypted IS NOT NULL)))");
                statement.execute("CREATE TABLE unban_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, case_id TEXT NOT NULL REFERENCES bans(case_id), actor TEXT NOT NULL, reason TEXT NOT NULL, revoked_at INTEGER NOT NULL, scope TEXT NOT NULL CHECK(scope IN ('UUID','IP','ALL')))");
                statement.execute("CREATE TABLE ip_observations (player_uuid TEXT NOT NULL, ip_lookup TEXT NOT NULL, trusted INTEGER NOT NULL CHECK(trusted IN (0,1)), last_seen INTEGER NOT NULL, PRIMARY KEY(player_uuid,ip_lookup))");
                statement.execute("CREATE INDEX bans_uuid_active ON bans(player_uuid,revoked_at,expires_at)");
                statement.execute("CREATE INDEX bans_ip_active ON bans(ip_lookup,revoked_at,expires_at)");
                statement.execute("CREATE INDEX evidence_time ON evidence(recorded_at)");
                statement.execute("CREATE INDEX ip_trust ON ip_observations(ip_lookup,trusted,last_seen)");
                statement.execute("CREATE TRIGGER audit_no_update BEFORE UPDATE ON unban_audit BEGIN SELECT RAISE(ABORT,'Audit is append only'); END");
                statement.execute("CREATE TRIGGER audit_no_delete BEFORE DELETE ON unban_audit BEGIN SELECT RAISE(ABORT,'Audit is append only'); END");
                statement.execute("PRAGMA user_version=1");
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO meta(key,value) VALUES ('ip_key_fingerprint',?)")) {
                    insert.setString(1, privacy.keyFingerprint()); insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
            finally { connection.setAutoCommit(true); }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM meta WHERE key='ip_key_fingerprint'"); ResultSet result = statement.executeQuery()) {
            if (!result.next() || !IpPrivacy.sameToken(privacy.keyFingerprint(), result.getString(1)))
                throw new StorageException("Protected IP key does not match this database; restore its matching key");
        }
        // Version 1 remains fully intact. Candidate provenance is explicit; old bans never become eligible.
        if (version < 2) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE permanent_ip_candidates (source_case_id TEXT PRIMARY KEY REFERENCES bans(case_id), player_uuid TEXT NOT NULL, evidence_id TEXT NOT NULL REFERENCES evidence(id), evidence_sha256 TEXT NOT NULL, ip_lookup TEXT NOT NULL, source_fingerprint TEXT NOT NULL, created_at INTEGER NOT NULL, review_expires_at INTEGER NOT NULL, identity_observed_at INTEGER NOT NULL, observed_since INTEGER NOT NULL, native_identity INTEGER NOT NULL CHECK(native_identity=1), observed_accounts INTEGER NOT NULL CHECK(observed_accounts=1), target_trusted INTEGER NOT NULL CHECK(target_trusted=1), consumed_at INTEGER, applied_case_id TEXT REFERENCES bans(case_id), CHECK(review_expires_at>created_at AND review_expires_at<=created_at+86400000), CHECK(identity_observed_at<=created_at), CHECK((consumed_at IS NULL AND applied_case_id IS NULL) OR (consumed_at IS NOT NULL AND applied_case_id IS NOT NULL)))");
                statement.execute("CREATE TABLE permanent_ip_requests (token_hash TEXT PRIMARY KEY, actor_uuid TEXT NOT NULL, source_case_id TEXT NOT NULL REFERENCES permanent_ip_candidates(source_case_id), candidate_binding TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL CHECK(status IN ('PENDING','CANCELLED','EXPIRED','REJECTED','APPLIED')), resolved_at INTEGER, applied_case_id TEXT REFERENCES bans(case_id), CHECK(expires_at>created_at AND expires_at<=created_at+30000), CHECK((status='PENDING' AND resolved_at IS NULL) OR (status<>'PENDING' AND resolved_at IS NOT NULL)))");
                statement.execute("CREATE TABLE permanent_ip_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, source_case_id TEXT NOT NULL REFERENCES permanent_ip_candidates(source_case_id), request_hash TEXT NOT NULL REFERENCES permanent_ip_requests(token_hash), actor_uuid TEXT NOT NULL, event TEXT NOT NULL CHECK(event IN ('REQUEST','CANCEL','EXPIRE','REJECT','APPLY')), recorded_at INTEGER NOT NULL, reason_code TEXT NOT NULL, applied_case_id TEXT REFERENCES bans(case_id))");
                statement.execute("CREATE INDEX permanent_ip_candidates_address ON permanent_ip_candidates(ip_lookup,consumed_at,review_expires_at)");
                statement.execute("CREATE INDEX permanent_ip_candidates_player ON permanent_ip_candidates(player_uuid,consumed_at,review_expires_at)");
                statement.execute("CREATE UNIQUE INDEX permanent_ip_candidates_applied ON permanent_ip_candidates(applied_case_id)");
                statement.execute("CREATE INDEX permanent_ip_requests_pending ON permanent_ip_requests(status,expires_at)");
                statement.execute("CREATE INDEX permanent_ip_audit_case ON permanent_ip_audit(source_case_id,id)");
                statement.execute("CREATE TRIGGER permanent_ip_audit_no_update BEFORE UPDATE ON permanent_ip_audit BEGIN SELECT RAISE(ABORT,'Audit is append only'); END");
                statement.execute("CREATE TRIGGER permanent_ip_audit_no_delete BEFORE DELETE ON permanent_ip_audit BEGIN SELECT RAISE(ABORT,'Audit is append only'); END");
                statement.execute("PRAGMA user_version=2");
                connection.commit();
            } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
            finally { connection.setAutoCommit(true); }
        }
    }

    /** Generate a non-sequential case ID without embedding player or network information. */
    public static String newCaseId() { return "GAC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(java.util.Locale.ROOT); }

    public CompletableFuture<EvidenceRecord> saveEvidence(EvidenceInput input) {
        Objects.requireNonNull(input);
        return submit(() -> transaction(() -> saveEvidenceNow(input)));
    }
    /** Only called from the serialized worker inside the caller's transaction. */
    private EvidenceRecord saveEvidenceNow(EvidenceInput input) throws SQLException {
            if (input.telemetryJson().length() > config.maxEvidenceRecordBytes()) throw new StorageException("Evidence record exceeds configured size");
            byte[] encoded = input.telemetryJson().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > config.maxEvidenceRecordBytes()) throw new StorageException("Evidence record exceeds configured size");
            try (PreparedStatement validation = connection.prepareStatement("SELECT CASE WHEN json_valid(?) THEN json_type(?) ELSE 'invalid' END")) {
                validation.setString(1, input.telemetryJson()); validation.setString(2, input.telemetryJson());
                try (ResultSet result = validation.executeQuery()) {
                    if (!result.next() || !"object".equals(result.getString(1))) throw new StorageException("Evidence must be a valid JSON object");
                }
            }
            Instant now = clock.instant();
            if (input.recordedAt().isAfter(now.plusSeconds(300)) || input.recordedAt().isBefore(now.minus(config.evidenceRetention())))
                throw new StorageException("Evidence timestamp is outside the permitted recording window");
            String id = UUID.randomUUID().toString(); String hash = sha256(encoded);
            prune(now, encoded.length);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO evidence(id,player_uuid,case_id,recorded_at,payload,sha256,original_bytes,payload_bytes) VALUES (?,?,?,?,?,?,?,?)")) {
                statement.setString(1, id); statement.setString(2, input.playerId().toString()); statement.setString(3, input.caseId());
                statement.setLong(4, input.recordedAt().toEpochMilli()); statement.setString(5, input.telemetryJson());
                statement.setString(6, hash); statement.setInt(7, encoded.length); statement.setInt(8, encoded.length); statement.executeUpdate();
            }
            return new EvidenceRecord(id, input.playerId(), input.caseId(), input.recordedAt(), input.telemetryJson(), hash, encoded.length);
    }
    /** Evidence must already be committed for this exact player and case. A failed future must not be enforced. */
    public CompletableFuture<BanRecord> createBan(BanRequest request) {
        Objects.requireNonNull(request);
        return submit(() -> transaction(() -> createBanNow(request)));
    }
    /** The shared-address recheck and automatic sanction commit are one serialized SQL transaction. */
    public CompletableFuture<BanRecord> createAutomaticBan(BanRequest request, Instant observedSince) {
        return createAutomaticBan(request,observedSince,request.expiresAt());
    }
    public CompletableFuture<BanRecord> createAutomaticBan(BanRequest request, Instant observedSince, Instant fallbackUuidExpiry) {
        return createAutomaticBan(request,observedSince,fallbackUuidExpiry,()->true);
    }
    /** Callers without an explicit native identity snapshot cannot create an automatic IP component. */
    public CompletableFuture<BanRecord> createAutomaticBan(BanRequest request, Instant observedSince, Instant fallbackUuidExpiry,
                                                         java.util.function.BooleanSupplier commitGuard) {
        return createAutomaticBan(request,observedSince,fallbackUuidExpiry,false,commitGuard);
    }
    /** The pure guard claims authorization immediately before the transaction commits; false rolls back.
     * authenticatedNativeIdentity must describe the target's successfully persisted, native online-mode session.
     * Automatic permanent-IP is deliberately unavailable, including through this lower-level entry point. */
    public CompletableFuture<BanRecord> createAutomaticBan(BanRequest request, Instant observedSince, Instant fallbackUuidExpiry,
                                                         boolean authenticatedNativeIdentity,
                                                         java.util.function.BooleanSupplier commitGuard) {
        Objects.requireNonNull(request); Objects.requireNonNull(observedSince); Objects.requireNonNull(commitGuard);
        return submit(() -> transaction(() -> {
            BanRequest selected=request;
            if(request.type().appliesToIp()) {
                IpBanPolicy.Association association=ipAssociationNow(request.playerId(),privacy.lookupHash(request.ipAddress()),observedSince);
                if(request.expiresAt()==null || !IpBanPolicy.identityEligibility(association,authenticatedNativeIdentity).allowed())
                    selected=new BanRequest(request.playerId(),request.playerName(),null,BanType.UUID,
                    request.reason(),fallbackUuidExpiry,request.violationLevel(),request.caseId(),request.evidenceId(),request.actor());
            }
            BanRecord record=createBanNow(selected);
            if(!commitGuard.getAsBoolean())throw new java.util.concurrent.CancellationException("Automatic sanction authorization expired");
            return record;
        }));
    }
    /** Creates only a UUID sanction. Protected review provenance is not an IP restriction or approval. */
    public CompletableFuture<BanRecord> createAutomaticReviewCandidate(BanRequest request, Instant observedSince,
            boolean authenticatedNativeIdentity, AutomaticBanGate.Policy policy, BooleanSupplier commitGuard) {
        Objects.requireNonNull(request); Objects.requireNonNull(observedSince); Objects.requireNonNull(policy); Objects.requireNonNull(commitGuard);
        if (request.type() != BanType.UUID) throw new IllegalArgumentException("Review candidates require a UUID source sanction");
        return submit(() -> transaction(() -> {
            Instant now = Instant.ofEpochMilli(clock.millis());
            Instant since = observedSince.isBefore(now.minus(Duration.ofDays(30))) ? observedSince : now.minus(Duration.ofDays(30));
            String lookup = request.ipAddress() == null ? null : privacy.lookupHash(request.ipAddress());
            EvidenceRecord evidence = readEvidence(request.evidenceId()).orElseThrow(() -> new StorageException("Persisted evidence is required"));
            verifyEvidence(evidence);
            boolean eligible = authenticatedNativeIdentity && lookup != null && permanentReviewPolicy(policy)
                    && evidence.originalBytes() <= config.maxEvidenceRecordBytes() - 4096
                    && !observedSince.isAfter(now) && evidence.playerId().equals(request.playerId()) && evidence.caseId().equals(request.caseId());
            Instant identityAt = null;
            if (eligible) {
                eligible = IpBanPolicy.identityEligibility(ipAssociationNow(request.playerId(), lookup, since), true).allowed();
                identityAt = trustedIdentityAt(request.playerId(), lookup, since, now);
                eligible &= identityAt != null && !identityAt.isBefore(now.minus(Duration.ofDays(30)));
            }
            if (eligible) {
                try {
                    var proof = PersistedBanEvidence.parse(evidence.telemetryJson(), request.playerId());
                    long captured = proof.evidence().capturedAtMillis();
                    eligible = JsonParser.parseString(evidence.telemetryJson()).toString().getBytes(StandardCharsets.UTF_8).length <= config.maxEvidenceRecordBytes() - 4096
                            && proof.capturedPolicy().equals(policy) && captured <= now.toEpochMilli()
                            && now.toEpochMilli() - captured <= 5000
                            && proof.evaluate(CheckCatalog.create(), policy).banRecommended();
                } catch (IllegalArgumentException error) { eligible = false; }
            }
            BanRequest selected = eligible ? request : new BanRequest(request.playerId(), request.playerName(), null, BanType.UUID,
                    request.reason(), request.expiresAt(), request.violationLevel(), request.caseId(), request.evidenceId(), request.actor());
            BanRecord source = createBanNow(selected);
            if (eligible) {
                PermanentIpCandidate candidate = new PermanentIpCandidate(source.caseId(), source.playerId(), evidence.id(), evidence.sha256(),
                        lookup, PermanentIpCandidate.fingerprint(source, lookup), now, now.plus(Duration.ofHours(24)), identityAt);
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO permanent_ip_candidates(source_case_id,player_uuid,evidence_id,evidence_sha256,ip_lookup,source_fingerprint,created_at,review_expires_at,identity_observed_at,observed_since,native_identity,observed_accounts,target_trusted) VALUES (?,?,?,?,?,?,?,?,?,?,1,1,1)")) {
                    bindCandidate(insert, candidate); insert.setLong(10, since.toEpochMilli()); insert.executeUpdate();
                }
            }
            if (!source.uuidActiveAt(clock.instant())) throw new java.util.concurrent.CancellationException("Source UUID sanction expired before commit");
            // No I/O, checks, or writes may be added after this one-shot authorization guard.
            if (!commitGuard.getAsBoolean()) throw new java.util.concurrent.CancellationException("Automatic sanction authorization expired");
            return source;
        }));
    }
    /** Explicit privileged preview; old or temporary-DUAL cases have no candidate and cannot be upgraded. */
    public CompletableFuture<Optional<PermanentIpReview>> previewPermanentIp(String sourceCase, AutomaticBanGate.Policy current,
                                                                            boolean supportedNativeEnvironment) {
        StorageValidation.caseId(sourceCase); Objects.requireNonNull(current);
        return submit(() -> {
            try { return Optional.of(reviewNow(sourceCase, current, supportedNativeEnvironment, clock.instant())); }
            catch (ReviewRefusal refused) { return Optional.empty(); }
        });
    }
    /** Persists a bounded, actor-specific 30-second request. It grants no enforcement authority. */
    public CompletableFuture<Void> openPermanentIpReview(String token, UUID actor, PermanentIpCandidate candidate) {
        String tokenHash = reviewTokenHash(token); Objects.requireNonNull(actor); Objects.requireNonNull(candidate);
        return submit(() -> transaction(() -> {
            Instant now = Instant.ofEpochMilli(clock.millis()); expirePermanentIpReviewsNow(now, false);
            var row = candidateNow(candidate.sourceCaseId()).orElseThrow(() -> refusal("candidate-unavailable"));
            if (!row.candidate().equals(candidate)) throw refusal("candidate-changed");
            EvidenceRecord evidence = readEvidence(candidate.evidenceId()).orElseThrow(() -> refusal("evidence-unavailable"));
            AutomaticBanGate.Policy policy;
            try { policy = PersistedBanEvidence.parse(evidence.telemetryJson(), candidate.playerId()).capturedPolicy(); }
            catch (IllegalArgumentException error) { throw refusal("evidence-invalid"); }
            reviewNow(candidate.sourceCaseId(), policy, true, now);
            try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM permanent_ip_requests WHERE status='PENDING'"); ResultSet rs = count.executeQuery()) {
                if (!rs.next() || rs.getInt(1) >= Math.min(128, config.queueCapacity())) throw refusal("review-capacity");
            }
            Instant expires = now.plusSeconds(30).isAfter(candidate.reviewExpiresAt()) ? candidate.reviewExpiresAt() : now.plusSeconds(30);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO permanent_ip_requests(token_hash,actor_uuid,source_case_id,candidate_binding,created_at,expires_at,status) VALUES (?,?,?,?,?,?,'PENDING')")) {
                insert.setString(1, tokenHash); insert.setString(2, actor.toString()); insert.setString(3, candidate.sourceCaseId());
                insert.setString(4, candidateBinding(candidate)); insert.setLong(5, now.toEpochMilli()); insert.setLong(6, expires.toEpochMilli()); insert.executeUpdate();
            }
            reviewAudit(candidate.sourceCaseId(), tokenHash, actor, "REQUEST", now, "review-opened", null);
            return null;
        }));
    }
    /** Independently revalidates immutable proof and source state, then applies a NEW IP-only permanent case atomically. */
    public CompletableFuture<BanRecord> applyReviewedPermanentIp(String token, UUID actor, PermanentIpCandidate candidate,
            String reason, AutomaticBanGate.Policy current, boolean nativeEnvironment, BooleanSupplier guard) {
        String tokenHash = reviewTokenHash(token); Objects.requireNonNull(actor); Objects.requireNonNull(candidate);
        StorageValidation.text(reason, 2048, "reason"); Objects.requireNonNull(current); Objects.requireNonNull(guard);
        return submit(() -> {
            try {
                return transaction(() -> {
                    Instant now = Instant.ofEpochMilli(clock.millis());
                    ReviewRequest request = requestNow(tokenHash).orElseThrow(() -> refusal("request-unavailable"));
                    if (!request.actor().equals(actor)) throw refusal("actor-mismatch");
                    if (!request.status().equals("PENDING")) throw refusal("request-not-pending");
                    if (now.isBefore(request.createdAt()) || !now.isBefore(request.expiresAt())) throw refusal("request-expired");
                    if (!request.sourceCase().equals(candidate.sourceCaseId()) || !IpPrivacy.sameToken(request.binding(), candidateBinding(candidate))) throw refusal("candidate-changed");
                    PermanentIpReview review = reviewNow(candidate.sourceCaseId(), current, nativeEnvironment, now);
                    if (!review.candidate().equals(candidate)) throw refusal("candidate-changed");
                    EvidenceRecord sourceEvidence = readEvidence(candidate.evidenceId()).orElseThrow(() -> refusal("evidence-unavailable"));
                    String caseId = newCaseId();
                    JsonObject linked = new JsonObject(); linked.addProperty("schemaVersion", 1); linked.addProperty("kind", "administrator-reviewed-permanent-ip");
                    linked.addProperty("playerId", candidate.playerId().toString()); linked.addProperty("sourceCaseId", candidate.sourceCaseId());
                    linked.addProperty("sourceEvidenceId", candidate.evidenceId()); linked.addProperty("sourceEvidenceSha256", candidate.evidenceSha256());
                    linked.addProperty("administratorId", actor.toString()); linked.addProperty("approvedAtMillis", now.toEpochMilli());
                    linked.add("sourceProof", JsonParser.parseString(sourceEvidence.telemetryJson()));
                    EvidenceRecord newEvidence = saveEvidenceNow(new EvidenceInput(candidate.playerId(), caseId, now, linked.toString()));
                    // Even a typed address in an administrative reason must not escape protected storage.
                    String safeReason = reason.replace(review.ipAddress(), "[protected]");
                    BanRecord result = createBanNow(new BanRequest(candidate.playerId(), review.source().playerName(), review.ipAddress(), BanType.IP,
                            safeReason, null, review.source().violationLevel(), caseId, newEvidence.id(), "Administrator " + actor));
                    try (PreparedStatement update = connection.prepareStatement("UPDATE permanent_ip_candidates SET consumed_at=?,applied_case_id=? WHERE source_case_id=? AND consumed_at IS NULL")) {
                        update.setLong(1, now.toEpochMilli()); update.setString(2, caseId); update.setString(3, candidate.sourceCaseId());
                        if (update.executeUpdate() != 1) throw refusal("candidate-consumed");
                    }
                    resolveRequest(tokenHash, "APPLIED", now, caseId);
                    reviewAudit(candidate.sourceCaseId(), tokenHash, actor, "APPLY", now, "administrator-approved", caseId);
                    Instant finalTime = clock.instant();
                    if (!review.source().uuidActiveAt(finalTime) || !candidate.validAt(finalTime)) throw refusal("source-expired-before-commit");
                    if (finalTime.isBefore(request.createdAt()) || !finalTime.isBefore(request.expiresAt())) throw refusal("request-expired");
                    // This pure, one-use guard is the final operation before commit; false rolls back ALL writes above.
                    if (!guard.getAsBoolean()) throw new java.util.concurrent.CancellationException("Permanent IP approval authorization expired");
                    return result;
                });
            } catch (ReviewRefusal refused) {
                // A known actor's refused request becomes terminal. Unknown tokens/actors cannot mutate someone else's request.
                transaction(() -> {
                    var request = requestNow(tokenHash);
                    if (request.isPresent() && request.get().actor().equals(actor) && request.get().status().equals("PENDING")) {
                        var row = request.get(); Instant now = clock.instant();
                        String event = refused.code.equals("request-expired") ? "EXPIRE" : "REJECT";
                        resolveRequest(tokenHash, event.equals("EXPIRE") ? "EXPIRED" : "REJECTED", now, null);
                        reviewAudit(row.sourceCase(), tokenHash, actor, event, now, refused.code, null);
                    }
                    return null;
                });
                throw new StorageException("Permanent IP review refused: " + refused.code);
            }
        });
    }
    /** Cancellation is idempotent and cannot undo an applied ban, including when queued after approval. */
    public CompletableFuture<Void> cancelPermanentIpReview(String token, UUID actor, String reason) {
        String tokenHash = reviewTokenHash(token); Objects.requireNonNull(actor); StorageValidation.text(reason, 128, "reason");
        String code = java.util.Set.of("CLOSED", "CANCELLED", "POLICY_CHANGED", "PERMISSION_LOST", "SESSION_CHANGED", "EXPIRED", "SOURCE_CHANGED", "FAILED")
                .contains(reason) ? reason.toLowerCase(java.util.Locale.ROOT).replace('_', '-') : "administrator-cancelled";
        return submit(() -> transaction(() -> {
            Optional<ReviewRequest> found = requestNow(tokenHash);
            if (found.isEmpty()) return null;
            ReviewRequest request = found.get();
            if (!request.actor().equals(actor)) throw new StorageException("Permanent IP request actor mismatch");
            if (!request.status().equals("PENDING")) return null;
            Instant now = clock.instant(); resolveRequest(tokenHash, "CANCELLED", now, null);
            reviewAudit(request.sourceCase(), tokenHash, actor, "CANCEL", now, code, null); return null;
        }));
    }
    public CompletableFuture<Integer> expirePermanentIpReviews() {
        return submit(() -> transaction(() -> expirePermanentIpReviewsNow(clock.instant(), false)));
    }
    /** Bounded newest-first audit page, containing no IP or bearer approval token. */
    public CompletableFuture<List<PermanentIpAudit>> permanentIpReviewHistory(String sourceCase, int limit) {
        StorageValidation.caseId(sourceCase); validateLimit(limit);
        return submit(() -> {
            List<PermanentIpAudit> rows = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT id,source_case_id,actor_uuid,event,recorded_at,reason_code,applied_case_id FROM permanent_ip_audit WHERE source_case_id=? OR source_case_id=(SELECT source_case_id FROM permanent_ip_candidates WHERE applied_case_id=?) ORDER BY id DESC LIMIT ?")) {
                query.setString(1, sourceCase); query.setString(2, sourceCase); query.setInt(3, limit);
                try (ResultSet rs = query.executeQuery()) { while (rs.next()) rows.add(new PermanentIpAudit(rs.getLong(1), rs.getString(2), UUID.fromString(rs.getString(3)), rs.getString(4), Instant.ofEpochMilli(rs.getLong(5)), rs.getString(6), rs.getString(7))); }
            }
            return List.copyOf(rows);
        });
    }
    private PermanentIpReview reviewNow(String sourceCase, AutomaticBanGate.Policy current, boolean nativeEnvironment, Instant now) throws SQLException {
        if (!nativeEnvironment || !permanentReviewPolicy(current)) throw refusal("current-policy-ineligible");
        CandidateRow row = candidateNow(sourceCase).orElseThrow(() -> refusal("candidate-unavailable"));
        PermanentIpCandidate candidate = row.candidate();
        if (row.consumed() || !candidate.validAt(now)) throw refusal("candidate-expired-or-consumed");
        BanRecord source = findCaseNow(sourceCase).orElseThrow(() -> refusal("source-unavailable"));
        if (source.type() != BanType.UUID || !source.uuidActiveAt(now) || !source.hasProtectedIp()
                || !source.playerId().equals(candidate.playerId()) || !source.evidenceId().equals(candidate.evidenceId())) throw refusal("source-inactive-or-changed");
        if (!IpPrivacy.sameToken(candidate.sourceFingerprint(), PermanentIpCandidate.fingerprint(source, candidate.ipLookup()))) throw refusal("source-fingerprint-changed");
        EvidenceRecord evidence = readEvidence(candidate.evidenceId()).orElseThrow(() -> refusal("evidence-unavailable"));
        try { verifyEvidence(evidence); } catch (StorageException invalid) { throw refusal("evidence-integrity-failed"); }
        if (!evidence.payloadAvailable() || !evidence.playerId().equals(candidate.playerId()) || !evidence.caseId().equals(sourceCase)
                || !IpPrivacy.sameToken(evidence.sha256(), candidate.evidenceSha256())) throw refusal("evidence-changed");
        try {
            var proof = PersistedBanEvidence.parse(evidence.telemetryJson(), candidate.playerId());
            if (!proof.capturedPolicy().equals(current) || proof.evidence().capturedAtMillis() > candidate.createdAt().toEpochMilli()
                    || candidate.createdAt().toEpochMilli() - proof.evidence().capturedAtMillis() > 5000
                    || !proof.evaluate(CheckCatalog.create(), current).banRecommended()) throw refusal("evidence-policy-no-longer-qualifies");
        } catch (IllegalArgumentException invalid) { throw refusal("evidence-invalid"); }
        String address;
        try (PreparedStatement query = connection.prepareStatement("SELECT ip_lookup,ip_encrypted FROM bans WHERE case_id=?")) {
            query.setString(1, sourceCase);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next() || !IpPrivacy.sameToken(rs.getString(1), candidate.ipLookup())) throw refusal("protected-address-changed");
                address = privacy.decrypt(rs.getString(2));
            }
        }
        if (!IpPrivacy.sameToken(privacy.lookupHash(address), candidate.ipLookup())) throw refusal("protected-address-changed");
        if (!IpBanPolicy.identityEligibility(ipAssociationNow(candidate.playerId(), candidate.ipLookup(), row.observedSince()), true).allowed()
                || trustedIdentityAt(candidate.playerId(), candidate.ipLookup(), candidate.identityObservedAt(), now) == null) throw refusal("identity-or-shared-address-changed");
        return new PermanentIpReview(candidate, source, address);
    }
    private static boolean permanentReviewPolicy(AutomaticBanGate.Policy policy) {
        if (!policy.permits()) return false;
        String[] parts = policy.sanctionPolicy().split(":", -1);
        if (parts.length != 4 || !(parts[0].equals("IP") || parts[0].equals("DUAL")) || !parts[2].equals("0") || !parts[3].equals("true")) return false;
        try { return Long.parseLong(parts[1]) >= 0; } catch (NumberFormatException invalid) { return false; }
    }
    private Instant trustedIdentityAt(UUID playerId, String lookup, Instant since, Instant now) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT o.last_seen FROM ip_observations o WHERE o.player_uuid=? AND o.ip_lookup=? AND o.trusted=1 AND o.last_seen>=? AND o.last_seen<=? AND NOT EXISTS (SELECT 1 FROM ip_observations newer WHERE newer.player_uuid=o.player_uuid AND newer.ip_lookup<>o.ip_lookup AND newer.last_seen>=o.last_seen)")) {
            query.setString(1, playerId.toString()); query.setString(2, lookup); query.setLong(3, since.toEpochMilli()); query.setLong(4, now.toEpochMilli());
            try (ResultSet rs = query.executeQuery()) { return rs.next() ? Instant.ofEpochMilli(rs.getLong(1)) : null; }
        }
    }
    private Optional<CandidateRow> candidateNow(String sourceCase) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM permanent_ip_candidates WHERE source_case_id=? AND native_identity=1 AND observed_accounts=1 AND target_trusted=1")) {
            query.setString(1, sourceCase);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                PermanentIpCandidate candidate = new PermanentIpCandidate(rs.getString("source_case_id"), UUID.fromString(rs.getString("player_uuid")), rs.getString("evidence_id"), rs.getString("evidence_sha256"), rs.getString("ip_lookup"), rs.getString("source_fingerprint"),
                        Instant.ofEpochMilli(rs.getLong("created_at")), Instant.ofEpochMilli(rs.getLong("review_expires_at")), Instant.ofEpochMilli(rs.getLong("identity_observed_at")));
                return Optional.of(new CandidateRow(candidate, Instant.ofEpochMilli(rs.getLong("observed_since")), getInstant(rs, "consumed_at") != null));
            }
        }
    }
    private static void bindCandidate(PreparedStatement insert, PermanentIpCandidate candidate) throws SQLException {
        insert.setString(1, candidate.sourceCaseId()); insert.setString(2, candidate.playerId().toString()); insert.setString(3, candidate.evidenceId());
        insert.setString(4, candidate.evidenceSha256()); insert.setString(5, candidate.ipLookup()); insert.setString(6, candidate.sourceFingerprint());
        insert.setLong(7, candidate.createdAt().toEpochMilli()); insert.setLong(8, candidate.reviewExpiresAt().toEpochMilli()); insert.setLong(9, candidate.identityObservedAt().toEpochMilli());
    }
    private static String candidateBinding(PermanentIpCandidate candidate) {
        // Canonical JSON arrays avoid ambiguous delimiter concatenation and include every immutable field.
        com.google.gson.JsonArray values = new com.google.gson.JsonArray();
        values.add(candidate.sourceCaseId()); values.add(candidate.playerId().toString()); values.add(candidate.evidenceId());
        values.add(candidate.evidenceSha256()); values.add(candidate.ipLookup()); values.add(candidate.sourceFingerprint());
        values.add(candidate.createdAt().toEpochMilli()); values.add(candidate.reviewExpiresAt().toEpochMilli()); values.add(candidate.identityObservedAt().toEpochMilli());
        return sha256(values.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String reviewTokenHash(String token) {
        StorageValidation.text(token, 256, "reviewToken");
        if (token.length() < 16) throw new IllegalArgumentException("Review token is too short");
        return sha256(token.getBytes(StandardCharsets.UTF_8));
    }
    private Optional<ReviewRequest> requestNow(String tokenHash) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM permanent_ip_requests WHERE token_hash=?")) {
            query.setString(1, tokenHash);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? Optional.of(new ReviewRequest(UUID.fromString(rs.getString("actor_uuid")), rs.getString("source_case_id"), rs.getString("candidate_binding"), Instant.ofEpochMilli(rs.getLong("created_at")), Instant.ofEpochMilli(rs.getLong("expires_at")), rs.getString("status"))) : Optional.empty();
            }
        }
    }
    private void resolveRequest(String hash, String state, Instant now, String appliedCase) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE permanent_ip_requests SET status=?,resolved_at=?,applied_case_id=? WHERE token_hash=? AND status='PENDING'")) {
            update.setString(1, state); update.setLong(2, now.toEpochMilli()); update.setString(3, appliedCase); update.setString(4, hash);
            if (update.executeUpdate() != 1) throw refusal("request-no-longer-pending");
        }
    }
    private void reviewAudit(String sourceCase, String tokenHash, UUID actor, String event, Instant now, String reasonCode, String appliedCase) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO permanent_ip_audit(source_case_id,request_hash,actor_uuid,event,recorded_at,reason_code,applied_case_id) VALUES (?,?,?,?,?,?,?)")) {
            insert.setString(1, sourceCase); insert.setString(2, tokenHash); insert.setString(3, actor.toString()); insert.setString(4, event);
            insert.setLong(5, now.toEpochMilli()); insert.setString(6, reasonCode); insert.setString(7, appliedCase); insert.executeUpdate();
        }
    }
    private int expirePermanentIpReviewsNow(Instant now, boolean restart) throws SQLException {
        List<String> tokens = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT token_hash FROM permanent_ip_requests WHERE status='PENDING'" + (restart ? "" : " AND expires_at<=?"))) {
            if (!restart) query.setLong(1, now.toEpochMilli());
            try (ResultSet rs = query.executeQuery()) { while (rs.next()) tokens.add(rs.getString(1)); }
        }
        for (String hash : tokens) {
            ReviewRequest request = requestNow(hash).orElseThrow(() -> refusal("request-unavailable"));
            resolveRequest(hash, restart ? "CANCELLED" : "EXPIRED", now, null);
            reviewAudit(request.sourceCase(), hash, request.actor(), restart ? "CANCEL" : "EXPIRE", now, restart ? "server-restarted" : "request-expired", null);
        }
        return tokens.size();
    }
    private record CandidateRow(PermanentIpCandidate candidate, Instant observedSince, boolean consumed) { }
    private record ReviewRequest(UUID actor, String sourceCase, String binding, Instant createdAt, Instant expiresAt, String status) { }
    private static ReviewRefusal refusal(String code) { return new ReviewRefusal(code); }
    private static final class ReviewRefusal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private ReviewRefusal(String code) { super("Permanent IP review refused: " + code); this.code = code; }
    }

    private BanRecord createBanNow(BanRequest request) throws SQLException {
            Instant now = clock.instant();
            if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) throw new StorageException("Ban expiry must be in the future");
            EvidenceRecord evidence = readEvidence(request.evidenceId()).orElseThrow(() -> new StorageException("Persisted evidence is required before banning"));
            if (!evidence.playerId().equals(request.playerId()) || !evidence.caseId().equals(request.caseId()) || !evidence.payloadAvailable())
                throw new StorageException("Ban evidence does not match this player and case, or has expired");
            verifyEvidence(evidence);
            String cipher = request.ipAddress() == null ? null : privacy.encrypt(request.ipAddress());
            String hash = request.ipAddress() == null ? null : privacy.lookupHash(request.ipAddress());
            UUID id = UUID.randomUUID(); String token = UUID.randomUUID().toString();
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO bans(id,player_uuid,player_name,ip_encrypted,ip_lookup,type,reason,created_at,expires_at,permanent,vl,case_id,evidence_id,actor,sync_token) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, id.toString()); statement.setString(2, request.playerId().toString()); statement.setString(3, request.playerName());
                statement.setString(4, cipher); statement.setString(5, hash); statement.setString(6, request.type().name()); statement.setString(7, request.reason());
                statement.setLong(8, now.toEpochMilli()); setInstant(statement, 9, request.expiresAt()); statement.setInt(10, request.expiresAt() == null ? 1 : 0);
                statement.setDouble(11, request.violationLevel()); statement.setString(12, request.caseId()); statement.setString(13, request.evidenceId());
                statement.setString(14, request.actor()); statement.setString(15, token); statement.executeUpdate();
            }
            return findCaseNow(request.caseId()).orElseThrow(() -> new StorageException("Ban transaction verification failed"));
    }
    /** Monotonic process-local revision, published only after durable successful transaction commit. */
    public long mutationRevision() { return mutationRevision.get(); }
    /** The record and revision are captured together on the serialized storage worker. */
    public CompletableFuture<BanStateSnapshot> caseState(String caseId) {
        StorageValidation.caseId(caseId);
        return submit(() -> new BanStateSnapshot(findCaseNow(caseId), mutationRevision.get()));
    }
    /** Bounded keyset sweep, including expired and revoked cases needed for exact-owned native cleanup. */
    public CompletableFuture<List<String>> retainedCaseIdsAfter(String afterCaseId, int limit) {
        if (afterCaseId != null && !afterCaseId.isEmpty()) StorageValidation.caseId(afterCaseId);
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("Invalid reconciliation page limit");
        String cursor = afterCaseId == null ? "" : afterCaseId;
        return submit(() -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT case_id FROM bans WHERE case_id>? ORDER BY case_id LIMIT ?")) {
                query.setString(1, cursor); query.setInt(2, limit);
                try (ResultSet rs = query.executeQuery()) { while (rs.next()) ids.add(rs.getString(1)); }
            }
            return List.copyOf(ids);
        });
    }
    /** One serialized view of this retained case and the strongest surviving restrictions for its identities. */
    public CompletableFuture<BanReconciliationSnapshot> reconciliationState(String caseId) {
        StorageValidation.caseId(caseId);
        return submit(() -> {
            Optional<BanRecord> found = findCaseNow(caseId);
            if (found.isEmpty()) return new BanReconciliationSnapshot(found, Optional.empty(), Optional.empty(), Optional.empty(), mutationRevision.get());
            BanRecord source = found.get(); Instant now = clock.instant(); String address = null, lookup = null;
            try (PreparedStatement query = connection.prepareStatement("SELECT ip_encrypted,ip_lookup FROM bans WHERE case_id=?")) {
                query.setString(1, caseId);
                try (ResultSet rs = query.executeQuery()) { if (rs.next() && rs.getString(1) != null) { address = privacy.decrypt(rs.getString(1)); lookup = rs.getString(2); } }
            }
            String strongest = " ORDER BY expires_at IS NULL DESC,expires_at DESC,case_id LIMIT 1";
            List<BanRecord> uuid = queryBans("SELECT * FROM bans WHERE player_uuid=? AND type IN ('UUID','DUAL') AND uuid_revoked_at IS NULL AND " + ACTIVE + strongest,
                    source.playerId().toString(), now.toEpochMilli(), now.toEpochMilli());
            List<BanRecord> ip = lookup == null ? List.of() : queryBans("SELECT * FROM bans WHERE ip_lookup=? AND type IN ('IP','DUAL') AND ip_revoked_at IS NULL AND " + ACTIVE + strongest,
                    lookup, now.toEpochMilli(), now.toEpochMilli());
            return new BanReconciliationSnapshot(found, Optional.ofNullable(address), uuid.stream().findFirst(), ip.stream().findFirst(), mutationRevision.get());
        });
    }
    public CompletableFuture<Optional<BanRecord>> findCase(String caseId) {
        StorageValidation.caseId(caseId); return submit(() -> findCaseNow(caseId));
    }
    public CompletableFuture<Optional<EvidenceRecord>> evidence(String id) {
        StorageValidation.text(id, 64, "evidenceId"); return submit(() -> {
            Optional<EvidenceRecord> result = readEvidence(id); result.ifPresent(SqliteBanRepository::verifyEvidence); return result;
        });
    }
    /** Latest evidence for the exact case, whether or not that case resulted in a ban. */
    public CompletableFuture<Optional<EvidenceRecord>> evidenceForCase(String caseId) {
        StorageValidation.caseId(caseId);
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM evidence WHERE case_id=? ORDER BY recorded_at DESC,id DESC LIMIT 1")) {
                statement.setString(1, caseId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    EvidenceRecord evidence = mapEvidence(result); verifyEvidence(evidence); return Optional.of(evidence);
                }
            }
        });
    }
    /** Metadata only: retrieve the selected JSON using evidence(id), never bulk-load payloads for a menu. */
    public CompletableFuture<List<EvidenceSummary>> recentEvidence(int limit) {
        validateLimit(limit);
        return submit(() -> {
            List<EvidenceSummary> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT id,player_uuid,case_id,recorded_at,sha256,original_bytes,payload IS NOT NULL AS payload_available FROM evidence ORDER BY recorded_at DESC,id DESC LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) records.add(new EvidenceSummary(result.getString("id"), UUID.fromString(result.getString("player_uuid")),
                            result.getString("case_id"), Instant.ofEpochMilli(result.getLong("recorded_at")), result.getString("sha256"),
                            result.getInt("original_bytes"), result.getBoolean("payload_available")));
                }
            }
            return List.copyOf(records);
        });
    }
    public CompletableFuture<List<BanRecord>> activeForPlayer(UUID playerId, Instant at) {
        Objects.requireNonNull(playerId); Objects.requireNonNull(at);
        return submit(() -> queryBans("SELECT * FROM bans WHERE player_uuid=? AND type IN ('UUID','DUAL') AND uuid_revoked_at IS NULL AND " + ACTIVE + " ORDER BY created_at DESC", playerId.toString(), at.toEpochMilli(), at.toEpochMilli()));
    }
    /** Existing explicit IP restrictions only; a match does not establish guilt or authorize creating a new ban. */
    public CompletableFuture<List<BanRecord>> matchIp(String ipAddress, Instant at) {
        String lookup = privacy.lookupHash(ipAddress); Objects.requireNonNull(at);
        return submit(() -> queryBans("SELECT * FROM bans WHERE ip_lookup=? AND type IN ('IP','DUAL') AND ip_revoked_at IS NULL AND " + ACTIVE + " ORDER BY created_at DESC", lookup, at.toEpochMilli(), at.toEpochMilli()));
    }
    public CompletableFuture<List<BanRecord>> historyForPlayer(UUID playerId, int limit) {
        Objects.requireNonNull(playerId); validateLimit(limit);
        return submit(() -> queryBans("SELECT * FROM bans WHERE player_uuid=? ORDER BY created_at DESC,id LIMIT ?", playerId.toString(), limit));
    }
    /** Exact, case-insensitive account-name lookup; never a LIKE pattern or partial match. */
    public CompletableFuture<List<BanRecord>> findCasesByPlayerName(String playerName, int limit) {
        StorageValidation.text(playerName, 64, "playerName"); validateLimit(limit);
        return submit(() -> queryBans("SELECT * FROM bans WHERE player_name=? COLLATE NOCASE ORDER BY created_at DESC,id LIMIT ?", playerName, limit));
    }
    public CompletableFuture<List<BanRecord>> recentBans(int limit) {
        validateLimit(limit); return submit(() -> queryBans("SELECT * FROM bans ORDER BY created_at DESC,id LIMIT ?", limit));
    }
    public CompletableFuture<List<BanRecord>> activeBans(Instant at, int limit) {
        validateLimit(limit); Objects.requireNonNull(at);
        return submit(() -> queryBans("SELECT * FROM bans WHERE " + ACTIVE + " ORDER BY created_at DESC,id LIMIT ?", at.toEpochMilli(), at.toEpochMilli(), limit));
    }
    /** Explicit privileged lookup for native IP-list synchronization. Do not put the returned address in logs or GUI. */
    public CompletableFuture<Optional<String>> protectedIp(String caseId) {
        StorageValidation.caseId(caseId); return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT ip_encrypted FROM bans WHERE case_id=?")) {
                statement.setString(1, caseId); try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getString(1) == null) return Optional.empty();
                    return Optional.of(privacy.decrypt(result.getString(1)));
                }
            }
        });
    }
    public CompletableFuture<Boolean> ownsSyncToken(String caseId, String token) {
        StorageValidation.caseId(caseId); return submit(() -> findCaseNow(caseId).map(ban -> IpPrivacy.sameToken(ban.syncToken(), token)).orElse(false));
    }
    public CompletableFuture<Boolean> ownsNativeSource(String caseId, String source) {
        StorageValidation.caseId(caseId); return submit(() -> findCaseNow(caseId).map(ban -> IpPrivacy.sameToken(ban.ownershipMarker(), source)).orElse(false));
    }
    public CompletableFuture<Void> registerIp(UUID playerId, String ipAddress, boolean trusted, Instant at) {
        Objects.requireNonNull(playerId); Objects.requireNonNull(at); String lookup = privacy.lookupHash(ipAddress);
        return submit(() -> {
            // A later untrusted session must not refresh an earlier trusted association.
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ip_observations(player_uuid,ip_lookup,trusted,last_seen) VALUES (?,?,?,?) ON CONFLICT(player_uuid,ip_lookup) DO UPDATE SET trusted=CASE WHEN excluded.last_seen>last_seen THEN excluded.trusted WHEN excluded.last_seen=last_seen THEN MIN(trusted,excluded.trusted) ELSE trusted END,last_seen=MAX(last_seen,excluded.last_seen)")) {
                statement.setString(1, playerId.toString()); statement.setString(2, lookup); statement.setInt(3, trusted ? 1 : 0); statement.setLong(4, at.toEpochMilli()); statement.executeUpdate();
            }
            mutationRevision.incrementAndGet();
            return null;
        });
    }
    /** All observed UUIDs count toward shared-address protection, irrespective of authentication. */
    public CompletableFuture<Integer> observedAccountCount(String ipAddress, Instant since) {
        String lookup=privacy.lookupHash(ipAddress);Objects.requireNonNull(since);
        return submit(()->ipAssociationNow(null,lookup,since).observedAccounts());
    }
    /** One consistent snapshot of total accounts, trusted accounts, and the exact target's association. */
    public CompletableFuture<IpBanPolicy.Association> ipAssociation(UUID playerId, String ipAddress, Instant since) {
        Objects.requireNonNull(playerId);Objects.requireNonNull(since);String lookup=privacy.lookupHash(ipAddress);
        return submit(()->ipAssociationNow(playerId,lookup,since));
    }
    private IpBanPolicy.Association ipAssociationNow(UUID playerId,String lookup,Instant since) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("SELECT COUNT(DISTINCT player_uuid),COUNT(DISTINCT CASE WHEN trusted=1 THEN player_uuid END),COALESCE(MAX(CASE WHEN player_uuid=? AND trusted=1 THEN 1 ELSE 0 END),0) FROM ip_observations WHERE ip_lookup=? AND last_seen>=?")) {
            statement.setString(1,playerId==null?null:playerId.toString());statement.setString(2,lookup);statement.setLong(3,since.toEpochMilli());
            try(ResultSet result=statement.executeQuery()) {
                if(!result.next())throw new StorageException("IP association unavailable");
                return new IpBanPolicy.Association(result.getInt(1),result.getInt(2),result.getInt(3)==1);
            }
        }
    }
    public CompletableFuture<Integer> trustedAccountCount(String ipAddress, Instant since) {
        String lookup = privacy.lookupHash(ipAddress); Objects.requireNonNull(since);
        return submit(() -> trustedAccountCountNow(lookup,since));
    }
    private int trustedAccountCountNow(String lookup,Instant since) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM ip_observations WHERE ip_lookup=? AND trusted=1 AND last_seen>=?")) {
            statement.setString(1, lookup); statement.setLong(2, since.toEpochMilli());
            try (ResultSet result = statement.executeQuery()) { if (!result.next()) throw new StorageException("Trust count unavailable"); return result.getInt(1); }
        }
    }
    public CompletableFuture<List<BanRecord>> revokeCase(String caseId, String actor, String reason, Instant at) {
        StorageValidation.caseId(caseId); return revoke("case_id=?", caseId, "ALL", actor, reason, at);
    }
    /** Confirmation-safe UUID unban: cannot revoke a different or subsequently created case. */
    public CompletableFuture<List<BanRecord>> revokeCaseUuid(String caseId, String actor, String reason, Instant at) {
        StorageValidation.caseId(caseId);
        return revoke("case_id=? AND type IN ('UUID','DUAL') AND uuid_revoked_at IS NULL", caseId, "UUID", actor, reason, at);
    }
    /** Confirmation-safe IP unban: only this case's IP component, never every case sharing the address. */
    public CompletableFuture<List<BanRecord>> revokeCaseIp(String caseId, String actor, String reason, Instant at) {
        StorageValidation.caseId(caseId);
        return revoke("case_id=? AND type IN ('IP','DUAL') AND ip_revoked_at IS NULL", caseId, "IP", actor, reason, at);
    }
    public CompletableFuture<List<BanRecord>> revokePlayer(UUID playerId, String actor, String reason, Instant at) {
        return revoke("player_uuid=?", Objects.requireNonNull(playerId).toString(), "ALL", actor, reason, at);
    }
    public CompletableFuture<List<BanRecord>> revokeUuid(UUID playerId, String actor, String reason, Instant at) {
        return revoke("player_uuid=? AND type IN ('UUID','DUAL') AND uuid_revoked_at IS NULL", Objects.requireNonNull(playerId).toString(), "UUID", actor, reason, at);
    }
    public CompletableFuture<List<BanRecord>> revokeIp(String ipAddress, String actor, String reason, Instant at) {
        return revoke("ip_lookup=? AND type IN ('IP','DUAL') AND ip_revoked_at IS NULL", privacy.lookupHash(ipAddress), "IP", actor, reason, at);
    }
    private CompletableFuture<List<BanRecord>> revoke(String where, String value, String scope, String actor, String reason, Instant at) {
        StorageValidation.text(actor, 128, "actor"); StorageValidation.text(reason, 2048, "reason"); Objects.requireNonNull(at);
        return submit(() -> transaction(() -> {
            List<BanRecord> before = queryBans("SELECT * FROM bans WHERE " + where + " AND revoked_at IS NULL AND created_at<=?", value, at.toEpochMilli());
            for (BanRecord ban : before) {
                Instant uuidRevoked = ban.uuidRevokedAt(); Instant ipRevoked = ban.ipRevokedAt();
                if ((scope.equals("ALL") || scope.equals("UUID")) && ban.type().appliesToUuid() && uuidRevoked == null) uuidRevoked = at;
                if ((scope.equals("ALL") || scope.equals("IP")) && ban.type().appliesToIp() && ipRevoked == null) ipRevoked = at;
                boolean all = (!ban.type().appliesToUuid() || uuidRevoked != null) && (!ban.type().appliesToIp() || ipRevoked != null);
                try (PreparedStatement update = connection.prepareStatement("UPDATE bans SET uuid_revoked_at=?,ip_revoked_at=?,revoked_at=? WHERE id=? AND revoked_at IS NULL")) {
                    setInstant(update, 1, uuidRevoked); setInstant(update, 2, ipRevoked); setInstant(update, 3, all ? at : null);
                    update.setString(4, ban.id().toString()); update.executeUpdate();
                }
                try (PreparedStatement audit = connection.prepareStatement("INSERT INTO unban_audit(case_id,actor,reason,revoked_at,scope) VALUES (?,?,?,?,?)")) {
                    audit.setString(1, ban.caseId()); audit.setString(2, actor); audit.setString(3, reason); audit.setLong(4, at.toEpochMilli()); audit.setString(5, scope); audit.executeUpdate();
                }
            }
            return List.copyOf(before);
        }));
    }
    public CompletableFuture<List<UnbanAudit>> unbanHistory(String caseId) {
        StorageValidation.caseId(caseId); return submit(() -> {
            List<UnbanAudit> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM unban_audit WHERE case_id=? ORDER BY id")) {
                statement.setString(1, caseId); try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) records.add(new UnbanAudit(result.getLong("id"), caseId, result.getString("actor"), result.getString("reason"), Instant.ofEpochMilli(result.getLong("revoked_at")), result.getString("scope")));
                }
            }
            return List.copyOf(records);
        });
    }
    public CompletableFuture<Long> evidenceBytes() {
        return submit(() -> payloadBytes());
    }
    public CompletableFuture<Void> pruneEvidence(Instant now) {
        Objects.requireNonNull(now); return submit(() -> transaction(() -> { expirePermanentIpReviewsNow(clock.instant(), false); prune(now, 0); return null; }));
    }
    private void prune(Instant now, int reserve) throws SQLException {
        String unpinned = "NOT EXISTS (SELECT 1 FROM bans b WHERE b.evidence_id=evidence.id AND b.revoked_at IS NULL AND (b.expires_at IS NULL OR b.expires_at>?))";
        try (PreparedStatement statement = connection.prepareStatement("UPDATE evidence SET payload=NULL,payload_bytes=0 WHERE payload IS NOT NULL AND recorded_at<? AND " + unpinned)) {
            statement.setLong(1, now.minus(config.evidenceRetention()).toEpochMilli()); statement.setLong(2, now.toEpochMilli()); statement.executeUpdate();
        }
        long bytes = payloadBytes();
        if (bytes + reserve > config.maxEvidenceBytes()) {
            try (PreparedStatement oldest = connection.prepareStatement("SELECT id,payload_bytes FROM evidence WHERE payload IS NOT NULL AND " + unpinned + " ORDER BY recorded_at,id")) {
                oldest.setLong(1, now.toEpochMilli());
                List<String> victims = new ArrayList<>();
                try (ResultSet result = oldest.executeQuery()) {
                    while (bytes + reserve > config.maxEvidenceBytes() && result.next()) { victims.add(result.getString(1)); bytes -= result.getInt(2); }
                }
                if (bytes + reserve > config.maxEvidenceBytes()) throw new StorageException("Evidence capacity is pinned by active bans; no evidence or ban was saved");
                try (PreparedStatement remove = connection.prepareStatement("UPDATE evidence SET payload=NULL,payload_bytes=0 WHERE id=?")) {
                    for (String id : victims) { remove.setString(1, id); remove.addBatch(); } remove.executeBatch();
                }
            }
        }
        try (Statement cleanup = connection.createStatement()) {
            cleanup.executeUpdate("DELETE FROM evidence WHERE payload IS NULL AND NOT EXISTS (SELECT 1 FROM bans b WHERE b.evidence_id=evidence.id)");
            cleanup.execute("PRAGMA incremental_vacuum(200)");
        }
        // Evidence retention must never shorten the independent shared-address safety window.
        // Preserve every observed UUID (including untrusted ones), even before a review candidate exists.
        Duration associationRetention = config.evidenceRetention().compareTo(Duration.ofDays(30)) < 0
                ? Duration.ofDays(30) : config.evidenceRetention();
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM ip_observations WHERE last_seen<? AND NOT EXISTS (SELECT 1 FROM permanent_ip_candidates c WHERE (c.ip_lookup=ip_observations.ip_lookup OR c.player_uuid=ip_observations.player_uuid) AND c.consumed_at IS NULL AND c.review_expires_at>?)")) {
            statement.setLong(1, now.minus(associationRetention).toEpochMilli()); statement.setLong(2, now.toEpochMilli()); statement.executeUpdate();
        }
    }
    private long payloadBytes() throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COALESCE(sum(payload_bytes),0) FROM evidence")) { if (!result.next()) throw new StorageException("Evidence size unavailable"); return result.getLong(1); }
    }

    /** Uses SQLite VACUUM INTO for a consistent online snapshot, never copies an open WAL database. */
    public CompletableFuture<Path> backup() {
        return submit(() -> {
            Path backups = SecurePaths.directory(directory.resolve("backups"));
            String name = "bans-" + clock.instant().toEpochMilli() + "-" + UUID.randomUUID() + ".db";
            Path target = backups.resolve(name); Path temporary = backups.resolve(name + ".pending");
            SecurePaths.rejectSymlinks(temporary);
            try {
                try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) { statement.setString(1, temporary.toString()); statement.execute(); }
                SecurePaths.privatePermissions(temporary, false);
                try (Connection verify = connect(temporary, true)) { verifyIntegrity(verify); }
                try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                pruneBackups(backups, target);
                return target;
            } finally { Files.deleteIfExists(temporary); }
        });
    }
    private void pruneBackups(Path backups, Path newest) throws IOException {
        try (var stream = Files.list(backups)) {
            List<Path> files = stream.filter(path -> path.getFileName().toString().matches("bans-[0-9]+-[a-f0-9-]{36}\\.db"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing((Path path) -> !path.equals(newest)).thenComparing(path -> path.getFileName().toString(), Comparator.reverseOrder())).toList();
            for (int i = config.maxBackups(); i < files.size(); i++) Files.delete(files.get(i));
        }
    }
    /** Explicit offline recovery only. Original files are quarantined; the input must be a local generated backup name. */
    public static Path restoreBackup(Path pluginDataDirectory, String backupFileName) {
        if (backupFileName == null || !backupFileName.matches("bans-[0-9]+-[a-f0-9-]{36}\\.db"))
            throw new IllegalArgumentException("Invalid backup name");
        try {
            Path directory = SecurePaths.directory(pluginDataDirectory.resolve("bans"));
            Path lockPath = directory.resolve("store.lock"); SecurePaths.rejectSymlinks(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock held = acquireLock(channel)) {
                if (!held.isValid()) throw new StorageException("Recovery lock is unavailable");
                Path source = directory.resolve("backups").resolve(backupFileName); checkDatabasePaths(source);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new StorageException("Selected backup is unavailable");
                IpPrivacy privacy = IpPrivacy.open(directory, false);
                try (Connection verify = connect(source, true)) {
                    verifyIntegrity(verify);
                    try (PreparedStatement statement = verify.prepareStatement("SELECT value FROM meta WHERE key='ip_key_fingerprint'"); ResultSet result = statement.executeQuery()) {
                        if (!result.next() || !IpPrivacy.sameToken(privacy.keyFingerprint(), result.getString(1))) throw new StorageException("Backup and protected IP key do not match");
                    }
                }
                Path database = directory.resolve("bans.db"); checkDatabasePaths(database);
                Path temporary = directory.resolve("restore-" + UUID.randomUUID() + ".pending");
                Path quarantine = SecurePaths.directory(directory.resolve("recovery-" + UUID.randomUUID()));
                try {
                    Files.copy(source, temporary); SecurePaths.privatePermissions(temporary, false);
                    try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
                    // Preserve originals, including WAL, for expert recovery. No deletion or silent auto-restore.
                    for (String suffix : List.of("", "-wal", "-shm", "-journal")) {
                        Path original = Path.of(database + suffix);
                        if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) Files.move(original, quarantine.resolve("bans.db" + suffix), StandardCopyOption.ATOMIC_MOVE);
                    }
                    Files.move(temporary, database, StandardCopyOption.ATOMIC_MOVE);
                    return quarantine;
                } finally { Files.deleteIfExists(temporary); }
            }
        } catch (StorageException error) { throw error; }
        catch (Exception error) { throw new StorageException("Offline backup restoration failed; preserved recovery files must be inspected"); }
    }

    private void verifyActiveEvidence(Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT e.* FROM evidence e JOIN bans b ON b.evidence_id=e.id WHERE b.revoked_at IS NULL AND (b.expires_at IS NULL OR b.expires_at>?)")) {
            statement.setLong(1, now.toEpochMilli()); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    EvidenceRecord evidence = mapEvidence(result);
                    if (!evidence.payloadAvailable()) throw new StorageException("Active ban evidence is missing; enforcement must remain disabled");
                    verifyEvidence(evidence);
                }
            }
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT 1 FROM bans b JOIN evidence e ON e.id=b.evidence_id WHERE b.player_uuid<>e.player_uuid OR b.case_id<>e.case_id LIMIT 1")) {
            if (result.next()) throw new StorageException("Ban evidence identity mismatch; enforcement must remain disabled");
        }
    }
    private Optional<BanRecord> findCaseNow(String caseId) throws SQLException {
        List<BanRecord> results = queryBans("SELECT * FROM bans WHERE case_id=?", caseId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }
    private List<BanRecord> queryBans(String sql, Object... parameters) throws SQLException {
        List<BanRecord> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (ResultSet result = statement.executeQuery()) { while (result.next()) results.add(mapBan(result)); }
        }
        return List.copyOf(results);
    }
    private Optional<EvidenceRecord> readEvidence(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM evidence WHERE id=?")) {
            statement.setString(1, id); try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapEvidence(result)) : Optional.empty();
            }
        }
    }
    private static BanRecord mapBan(ResultSet result) throws SQLException {
        return new BanRecord(UUID.fromString(result.getString("id")), UUID.fromString(result.getString("player_uuid")), result.getString("player_name"),
                BanType.valueOf(result.getString("type")), result.getString("reason"), Instant.ofEpochMilli(result.getLong("created_at")), getInstant(result, "expires_at"),
                result.getDouble("vl"), result.getString("case_id"), result.getString("evidence_id"), result.getString("actor"), result.getString("ip_lookup") != null,
                result.getString("sync_token"), getInstant(result, "revoked_at"), getInstant(result, "uuid_revoked_at"), getInstant(result, "ip_revoked_at"));
    }
    private static EvidenceRecord mapEvidence(ResultSet result) throws SQLException {
        return new EvidenceRecord(result.getString("id"), UUID.fromString(result.getString("player_uuid")), result.getString("case_id"),
                Instant.ofEpochMilli(result.getLong("recorded_at")), result.getString("payload"), result.getString("sha256"), result.getInt("original_bytes"));
    }
    private static void verifyEvidence(EvidenceRecord evidence) {
        if (evidence.payloadAvailable()) {
            byte[] encoded = evidence.telemetryJson().getBytes(StandardCharsets.UTF_8);
            if (encoded.length != evidence.originalBytes() || !IpPrivacy.sameToken(sha256(encoded), evidence.sha256()))
                throw new StorageException("Evidence integrity verification failed; enforcement must remain disabled");
        }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static void setInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        if (instant == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, instant.toEpochMilli());
    }
    private static Instant getInstant(ResultSet result, String name) throws SQLException {
        long value = result.getLong(name); return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }
    private static void validateLimit(int limit) { if (limit < 1 || limit > 10000) throw new IllegalArgumentException("Invalid result limit"); }
    private <T> CompletableFuture<T> submit(SqlAction<T> action) {
        if (closed.get()) return CompletableFuture.failedFuture(new StorageException("Ban storage is closed"));
        return worker.submit(() -> {
            try { return action.run(); }
            catch (SQLException | IOException error) { throw new StorageException("Ban storage operation failed; action must not be enforced"); }
        });
    }
    private <T> T transaction(SqlAction<T> action) throws Exception {
        connection.setAutoCommit(false);
        try { T result = action.run(); connection.commit(); mutationRevision.incrementAndGet(); return result; }
        catch (Exception error) { connection.rollback(); throw error; }
        finally { connection.setAutoCommit(true); }
    }
    @FunctionalInterface private interface SqlAction<T> { T run() throws Exception; }
    private static void closeQuietly(AutoCloseable value) { if (value != null) try { value.close(); } catch (Exception ignored) { } }
    @Override public void close() {
        if (worker.isWorkerThread()) throw new StorageException("Close ban storage from the lifecycle owner, not an inline completion callback");
        if (!closed.compareAndSet(false, true)) return;
        worker.close();
        try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA wal_checkpoint(TRUNCATE)"); }
        catch (SQLException ignored) { /* WAL remains valid and recoverable on restart. */ }
        finally { closeQuietly(connection); closeQuietly(lock); closeQuietly(lockChannel); }
    }
}
