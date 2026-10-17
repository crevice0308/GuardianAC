/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.security;

/** Exact per-case source markers only; names/prefixes and source-less external bans are not owned. */
public final class NativeBanOwnership {
    private NativeBanOwnership() { }
    public static boolean mayRemove(String expectedSource,String existingSource) {
        return expectedSource!=null&&!expectedSource.isEmpty()&&expectedSource.equals(existingSource);
    }
    public static boolean mayReplace(String expectedSource,boolean existingEntry,String existingSource) {
        return !existingEntry||mayRemove(expectedSource,existingSource);
    }
}
