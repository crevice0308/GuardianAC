package test.guardianfixture.safety;

import java.util.Map;

/** Test-helper-only mirror of fixture_safety's fail-closed endpoint contract. */
public final class FixturePorts {
    private FixturePorts() {}

    static int selectedPort(String variant, Map<String, String> environment) {
        if (!"paper-26.3".equals(variant) && !"purpur-26.3".equals(variant))
            throw new IllegalStateException("Missing or unknown fixture variant marker");
        boolean paperSet = environment.containsKey("GUARDIAN_PAPER_PORT");
        boolean purpurSet = environment.containsKey("GUARDIAN_PURPUR_PORT");
        if (paperSet != purpurSet)
            throw new IllegalStateException("Both fixture port overrides must be supplied together");
        int paper = parsePort(paperSet ? environment.get("GUARDIAN_PAPER_PORT") : "26731");
        int purpur = parsePort(purpurSet ? environment.get("GUARDIAN_PURPUR_PORT") : "26732");
        if (paper == purpur)
            throw new IllegalStateException("Paper and Purpur fixture ports must be distinct");
        return "paper-26.3".equals(variant) ? paper : purpur;
    }

    private static int parsePort(String value) {
        if (value == null || !value.matches("[1-9][0-9]{3,4}"))
            throw new IllegalStateException("Fixture port must be a canonical decimal integer");
        int port = Integer.parseInt(value);
        if (port < 1024 || port > 65535)
            throw new IllegalStateException("Fixture port must be between 1024 and 65535");
        return port;
    }

    static void validateEndpoint(String requiredVariant, String variant, String host,
                                 int port, boolean onlineMode, Map<String, String> environment) {
        int expected = selectedPort(variant, environment);
        if ((requiredVariant != null && !requiredVariant.equals(variant))
                || !"127.0.0.1".equals(host) || onlineMode || port != expected)
            throw new IllegalStateException("Fixture requires its exact selected offline loopback endpoint");
    }

    public static void requireEndpoint(String host, int port, boolean onlineMode) {
        requireEndpoint(null, host, port, onlineMode);
    }

    public static void requireEndpoint(String requiredVariant, String host, int port, boolean onlineMode) {
        validateEndpoint(requiredVariant, System.getProperty("guardian.fixture.variant"), host,
                         port, onlineMode, System.getenv());
    }
}
