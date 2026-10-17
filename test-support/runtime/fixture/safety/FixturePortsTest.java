package test.guardianfixture.safety;

import java.util.HashMap;
import java.util.Map;

/** Standalone offline test program. No Bukkit/server classes, sockets or subprocesses. */
public final class FixturePortsTest {
    private static int checks;
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Fixture port gate regression at check " + checks);
    }
    private static void rejected(Runnable action) {
        checks++;
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected port gate rejection at check " + checks);
    }
    private static Map<String, String> pair(String paper, String purpur) {
        Map<String, String> environment = new HashMap<>();
        environment.put("GUARDIAN_PAPER_PORT", paper);
        environment.put("GUARDIAN_PURPUR_PORT", purpur);
        return environment;
    }
    public static void main(String[] args) {
        check(FixturePorts.selectedPort("paper-26.3", Map.of()) == 26731);
        check(FixturePorts.selectedPort("purpur-26.3", Map.of()) == 26732);
        for (int[] ports : new int[][] {{26841, 26842}, {1024, 65535}, {65535, 1024}}) {
            Map<String, String> env = pair(Integer.toString(ports[0]), Integer.toString(ports[1]));
            check(FixturePorts.selectedPort("paper-26.3", env) == ports[0]);
            check(FixturePorts.selectedPort("purpur-26.3", env) == ports[1]);
            FixturePorts.validateEndpoint(null, "paper-26.3", "127.0.0.1", ports[0], false, env);
            FixturePorts.validateEndpoint(null, "purpur-26.3", "127.0.0.1", ports[1], false, env);
            checks += 2;
        }
        String[] invalid = {null, "", "0", "1", "1023", "65536", "99999", "123456", "-26841", "+26841",
                "026841", " 26841", "26841 ", "26841\n", "26_841", "26841.0", "2.6841e4", "0x68d9",
                "127.0.0.1:26841", "localhost:26841", "26841,26842", "\u0662\u0666\u0668\u0664\u0661"};
        for (String value : invalid) {
            rejected(() -> FixturePorts.selectedPort("paper-26.3", pair(value, "26842")));
            rejected(() -> FixturePorts.selectedPort("paper-26.3", pair("26841", value)));
        }
        for (String variant : new String[] {null, "", "paper", "Paper", "paper-26.4", "spigot-26.3"})
            rejected(() -> FixturePorts.selectedPort(variant, Map.of()));
        rejected(() -> FixturePorts.selectedPort("paper-26.3", Map.of("GUARDIAN_PAPER_PORT", "26841")));
        rejected(() -> FixturePorts.selectedPort("purpur-26.3", Map.of("GUARDIAN_PURPUR_PORT", "26842")));
        rejected(() -> FixturePorts.selectedPort("paper-26.3", pair("26841", "26841")));
        rejected(() -> FixturePorts.selectedPort("purpur-26.3", pair("26841", "26841")));
        Map<String, String> custom = pair("26841", "26842");
        for (String host : new String[] {null, "", "localhost", "127.1", "2130706433", "::1", "127.0.0.2", "0.0.0.0"})
            rejected(() -> FixturePorts.validateEndpoint(null, "paper-26.3", host, 26841, false, custom));
        for (int port : new int[] {0, 1023, 26731, 26732, 26842, 65536})
            rejected(() -> FixturePorts.validateEndpoint(null, "paper-26.3", "127.0.0.1", port, false, custom));
        rejected(() -> FixturePorts.validateEndpoint(null, "paper-26.3", "127.0.0.1", 26841, true, custom));
        rejected(() -> FixturePorts.validateEndpoint("paper-26.3", "purpur-26.3", "127.0.0.1", 26842, false, custom));
        rejected(() -> FixturePorts.validateEndpoint(null, null, "127.0.0.1", 26841, false, custom));
        System.clearProperty("guardian.fixture.variant");
        rejected(() -> FixturePorts.requireEndpoint("127.0.0.1", 26841, false));
        System.out.println("FixturePortsTest passed " + checks + " offline checks; no server or sockets");
    }
}
