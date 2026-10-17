// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Local encryption and keyed lookup. Keys are independent; raw IPs never appear in exceptions. */
public final class IpPrivacy {
    private static final byte[] MAGIC = "GACIP001".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AAD = "GuardianAC/IP/v1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec lookupKey;
    private IpPrivacy(byte[] bytes) {
        encryptionKey = new SecretKeySpec(Arrays.copyOfRange(bytes, 8, 40), "AES");
        lookupKey = new SecretKeySpec(Arrays.copyOfRange(bytes, 40, 72), "HmacSHA256");
    }
    static IpPrivacy open(Path directory, boolean allowCreate) throws IOException {
        Path keyFile = directory.resolve("ip-keys.bin");
        SecurePaths.rejectSymlinks(keyFile);
        if (!Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!allowCreate) throw new StorageException("Protected IP key is missing; restore the matching key backup");
            byte[] material = new byte[72]; RANDOM.nextBytes(material); System.arraycopy(MAGIC, 0, material, 0, MAGIC.length);
            Path temporary = null;
            try {
                try { temporary = Files.createTempFile(directory, ".ip-key-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
                catch (UnsupportedOperationException ignored) { temporary = Files.createTempFile(directory, ".ip-key-", ".tmp"); }
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(material);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                Files.move(temporary, keyFile, StandardCopyOption.ATOMIC_MOVE);
            } finally { Arrays.fill(material, (byte) 0); if (temporary != null) Files.deleteIfExists(temporary); }
        }
        if (!Files.isRegularFile(keyFile, LinkOption.NOFOLLOW_LINKS) || Files.size(keyFile) != 72)
            throw new StorageException("Protected IP key file is invalid");
        SecurePaths.privatePermissions(keyFile, false);
        byte[] material = Files.readAllBytes(keyFile);
        try {
            if (!MessageDigest.isEqual(MAGIC, Arrays.copyOf(material, 8))) throw new StorageException("Protected IP key file is invalid");
            return new IpPrivacy(material);
        } finally { Arrays.fill(material, (byte) 0); }
    }
    public String encrypt(String address) {
        byte[] plain = normalize(address).getBytes(StandardCharsets.US_ASCII);
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce)); cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plain);
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException error) { throw new StorageException("Protected IP encryption failed"); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    public String decrypt(String encrypted) {
        if (encrypted == null || !encrypted.startsWith("v1:") || encrypted.length() > 256)
            throw new StorageException("Protected IP data is invalid");
        try {
            byte[] all = Base64.getDecoder().decode(encrypted.substring(3));
            if (all.length < 29) throw new StorageException("Protected IP data is invalid");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, Arrays.copyOf(all, 12))); cipher.updateAAD(AAD);
            byte[] plain = cipher.doFinal(all, 12, all.length - 12);
            try { return normalize(new String(plain, StandardCharsets.US_ASCII)); }
            finally { Arrays.fill(plain, (byte) 0); }
        } catch (GeneralSecurityException | IllegalArgumentException error) { throw new StorageException("Protected IP authentication failed"); }
    }
    public String lookupHash(String address) { return hmac(normalize(address)); }
    String keyFingerprint() { return hmac("GuardianAC/key-check/v1:" + Base64.getEncoder().encodeToString(encryptionKey.getEncoded())); }
    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(lookupKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException error) { throw new StorageException("Protected IP lookup failed"); }
    }
    /** Accepts numerical literals only. There is no hostname resolution or subnet wildcard input. */
    public static String normalize(String input) {
        if (input == null || input.isEmpty() || input.length() > 45) throw new IllegalArgumentException("Invalid numeric IP address");
        if (input.indexOf(':') >= 0) {
            if (!input.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Invalid numeric IP address");
            try { return InetAddress.getByName(input).getHostAddress(); }
            catch (UnknownHostException ignored) { throw new IllegalArgumentException("Invalid numeric IP address"); }
        }
        String[] pieces = input.split("\\.", -1);
        if (pieces.length != 4) throw new IllegalArgumentException("Invalid numeric IP address");
        for (String piece : pieces) {
            if (!piece.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(piece) > 255)
                throw new IllegalArgumentException("Invalid numeric IP address");
        }
        return String.join(".", pieces);
    }
    static boolean sameToken(String expected, String actual) {
        return expected != null && actual != null && actual.length() <= 256
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
