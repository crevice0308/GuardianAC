package dev.guardianac.ban;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IpAddressTest {
    @Test void normalizesEquivalentNumericAddresses() {
        assertEquals("192.0.2.1", IpAddress.normalize("192.0.2.1"));
        assertEquals(IpAddress.normalize("2001:db8::a"), IpAddress.normalize("2001:0DB8:0:0:0:0:0:A"));
        assertEquals("192.0.2.1", IpAddress.normalize("::ffff:192.0.2.1"));
    }

    @Test void rejectsHostNamesPortsScopesAndAmbiguousNumericInput() {
        for (String invalid : List.of("", "localhost", "example.com", "127.1", "2130706433", "192.0.2.1:25565",
                "256.0.0.1", "01.2.3.4", "1.2.3.-1", "1.2.3.4.", "[2001:db8::1]", "fe80::1%eth0", ":::", "2001:db8::g")) {
            assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize(null));
    }
}
