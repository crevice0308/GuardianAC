/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

/** An explicit compatibility boundary, never a 'latest supported' or minimum-version guess. */
public final class RuntimeTarget {
    public static final String MINECRAFT="26.3";
    public static final int PROTOCOL=777;
    private RuntimeTarget() { }
    public static boolean supportsBukkitVersion(String version) {
        // API build-channel labels are not Minecraft release suffixes. Paper/Purpur 26.3
        // expose these exact forms; the independent PacketEvents protocol gate still applies.
        return version!=null&&(version.equals(MINECRAFT)
            ||version.matches("26\\.3-R[0-9]+\\.[0-9]+(?:-SNAPSHOT)?")
            ||version.matches("26\\.3\\.build\\.[0-9]+-(?:stable|experimental)"));
    }
    public static boolean supportsProtocol(int serverProtocol,int clientProtocol) {
        return serverProtocol==PROTOCOL&&clientProtocol==PROTOCOL;
    }
}
