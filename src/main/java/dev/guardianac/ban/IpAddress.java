package dev.guardianac.ban;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/** Canonical numeric IP addresses. Never resolves host names or accepts scoped addresses. */
public final class IpAddress {
    private IpAddress() {}

    public static String normalize(String address) {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("An IP address is required");
        String value = address.trim();
        if (value.length() > 45) throw new IllegalArgumentException("Invalid IP address length");
        if (!value.contains(":")) {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) throw new IllegalArgumentException("Invalid IPv4 address");
            StringBuilder result = new StringBuilder();
            for (String octet : octets) {
                if (!octet.matches("[0-9]{1,3}") || (octet.length() > 1 && octet.charAt(0) == '0'))
                    throw new IllegalArgumentException("Invalid IPv4 octet");
                int number = Integer.parseInt(octet);
                if (number > 255) throw new IllegalArgumentException("Invalid IPv4 octet");
                if (!result.isEmpty()) result.append('.');
                result.append(number);
            }
            return result.toString();
        }
        // The colon and this character whitelist ensure getByName receives only a numeric
        // address, never a DNS name, URL, bracketed host or interface scope identifier.
        if (!value.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Invalid IPv6 address");
        if (value.contains(".")) {
            normalize(value.substring(value.lastIndexOf(':') + 1));
        }
        try {
            return InetAddress.getByName(value).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Invalid IPv6 address");
        }
    }
}
