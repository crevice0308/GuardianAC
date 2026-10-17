/* SPDX-License-Identifier: GPL-3.0-only */
package org.bukkit;

import java.util.Collection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;

/**
 * Compiler-only signatures used by the upstream PacketEvents Spigot builder.
 * Its source also targets Paper's getMinecraftVersion(), absent in the pinned
 * Spigot API. This stub permits preserving the upstream invocation and existing
 * NoSuchMethodError fallback exactly. It is NEVER included in runtime or tests.
 * The actual server supplies org.bukkit.Bukkit. Do not execute this class.
 */
public final class Bukkit {
    private Bukkit() { throw new AssertionError("Compiler-only signature stub"); }
    public static String getMinecraftVersion() { throw new AssertionError("Compiler-only signature stub"); }
    public static String getBukkitVersion() { throw new AssertionError("Compiler-only signature stub"); }
    public static PluginManager getPluginManager() { throw new AssertionError("Compiler-only signature stub"); }
    public static Collection<? extends Player> getOnlinePlayers() { throw new AssertionError("Compiler-only signature stub"); }
}
