package dev.guardianac.ban;

import java.util.UUID;

/** A UUID ban and its optional linked IP ban. expiresAt == 0 represents permanence. */
public record BanRecord(String id, UUID playerId, String playerName, String address,
                        String reason, String source, long createdAt, long expiresAt) {
    public BanRecord {
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Invalid ban identifier");
        id = UUID.fromString(id).toString();
        if (playerId == null) throw new IllegalArgumentException("Player UUID is required");
        validateName(playerName);
        if (address != null) address = IpAddress.normalize(address);
        reason = requireText(reason, "reason", 1024);
        source = requireText(source, "source", 128);
        if (createdAt < 0 || expiresAt < 0 || (expiresAt != 0 && expiresAt <= createdAt))
            throw new IllegalArgumentException("Invalid ban timestamps");
    }

    public boolean isActive(long now) { return expiresAt == 0 || expiresAt > now; }

    static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}"))
            throw new IllegalArgumentException("Invalid Minecraft player name");
    }

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid ban " + label);
        return value;
    }
}
