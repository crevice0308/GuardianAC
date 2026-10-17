// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IpPrivacyTest {
    @TempDir Path directory;
    @Test void encryptionAuthenticatedRandomizedAndPersistent() throws Exception {
        IpPrivacy privacy = IpPrivacy.open(directory, true);
        String encrypted = privacy.encrypt("203.0.113.8");
        assertNotEquals(encrypted, privacy.encrypt("203.0.113.8"));
        assertEquals("203.0.113.8", privacy.decrypt(encrypted));
        assertEquals("203.0.113.8", IpPrivacy.open(directory, false).decrypt(encrypted));
        assertFalse(encrypted.contains("203.0.113.8"));
        byte[] bytes = Base64.getDecoder().decode(encrypted.substring(3)); bytes[20] ^= 1;
        assertThrows(StorageException.class, () -> privacy.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes)));
        assertThrows(StorageException.class, () -> privacy.decrypt("v0:unsupported"));
        assertThrows(StorageException.class, () -> privacy.decrypt("v1:!invalid!"));
    }
    @Test void hmacIsStableKeyedAndIpv6Canonical() throws Exception {
        IpPrivacy privacy = IpPrivacy.open(directory, true);
        assertEquals(privacy.lookupHash("2001:db8::1"), privacy.lookupHash("2001:0db8:0:0:0:0:0:0001"));
        assertEquals(privacy.lookupHash("203.0.113.8"), privacy.lookupHash("::ffff:203.0.113.8"));
        Path other = Files.createDirectory(directory.resolve("other"));
        assertNotEquals(privacy.lookupHash("203.0.113.8"), IpPrivacy.open(other, true).lookupHash("203.0.113.8"));
        assertEquals(64, privacy.lookupHash("203.0.113.8").length());
    }
    @Test void neverResolveHostnamesOrAcceptInjectionAndScopeIdentifiers() {
        for (String invalid : List.of("localhost", "example.com", "127.1", "127.0.0.01", "256.0.0.1", "1.2.3.4/24", "[::1]", "fe80::1%eth0", "1.2.3.4\n", "1.2.3.4'; DROP TABLE bans;--"))
            assertThrows(IllegalArgumentException.class, () -> IpPrivacy.normalize(invalid));
    }
    @Test void hexadecimalHostnamesAndLegacyNumericHostAliasesAreNotIpLiterals() {
        for(String input:List.of("face","deadbeef","12345678","0x7f000001","127.1","0177.0.0.1"))
            assertThrows(IllegalArgumentException.class,()->IpPrivacy.normalize(input));
        assertEquals("127.0.0.1",IpPrivacy.normalize("127.0.0.1"));
        assertEquals("2001:db8:0:0:0:0:0:1",IpPrivacy.normalize("2001:db8::1"));
    }
    @Test void keyPermissionsAreOwnerOnlyOnPosix() throws Exception {
        IpPrivacy.open(directory, true);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("ip-keys.bin")));
    }
    @Test void inputAndRecordToStringNeverRevealIpOrTelemetry() {
        UUID player = UUID.randomUUID(); String ip = "203.0.113.8";
        BanRequest request = new BanRequest(player, "Player", ip, BanType.DUAL, "Reason", null, 100, "GAC-12345678", UUID.randomUUID().toString(), "Admin");
        assertFalse(request.toString().contains(ip));
        EvidenceInput input = new EvidenceInput(player, "GAC-12345678", Instant.now(), "{\"private\":\"test\"}");
        assertFalse(input.toString().contains("private"));
    }
    @Test void durationsHaveRequestedChoices() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(start.plus(Duration.ofHours(1)), BanDuration.HOUR_1.expiresAt(start));
        assertEquals(start.plus(Duration.ofHours(24)), BanDuration.HOURS_24.expiresAt(start));
        assertEquals(start.plus(Duration.ofDays(7)), BanDuration.DAYS_7.expiresAt(start));
        assertEquals(start.plus(Duration.ofDays(30)), BanDuration.DAYS_30.expiresAt(start));
        assertNull(BanDuration.PERMANENT.expiresAt(start));
    }
    @Test void invalidModelValuesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StorageConfig(0, 4096, 1024, Duration.ofDays(1), 2));
        assertThrows(IllegalArgumentException.class, () -> new StorageConfig(16, 1000, 1024, Duration.ofDays(1), 2));
        assertThrows(IllegalArgumentException.class, () -> new BanRequest(UUID.randomUUID(), "Player", null, BanType.IP, "Reason", null, 3, "GAC-12345678", "id", "Admin"));
        assertThrows(IllegalArgumentException.class, () -> new BanRequest(UUID.randomUUID(), "Player", null, BanType.UUID, "Reason", null, Double.NaN, "GAC-12345678", "id", "Admin"));
    }
}
