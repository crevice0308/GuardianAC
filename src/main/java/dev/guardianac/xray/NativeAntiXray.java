/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import org.bukkit.Bukkit;
import org.bukkit.World;

/** Reads effective per-world Paper/Purpur runtime configuration. Never changes server settings. */
public final class NativeAntiXray {
    public enum Mode { ENABLED, DISABLED, NOT_PRESENT, UNKNOWN }
    private NativeAntiXray() { }
    public static Mode inspect(World world) {
        if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("Native configuration on server thread");
        boolean paper;
        try { Class.forName("io.papermc.paper.configuration.WorldConfiguration"); paper=true; }
        catch(ClassNotFoundException absent) { paper=false; }
        if(!paper) {
            String brand=Bukkit.getName().toLowerCase(java.util.Locale.ROOT);
            return brand.contains("paper")||brand.contains("purpur")?Mode.UNKNOWN:Mode.NOT_PRESENT;
        }
        try {
            Object handle=world.getClass().getMethod("getHandle").invoke(world);
            Object config=handle.getClass().getMethod("paperConfig").invoke(handle);
            Object anticheat=config.getClass().getField("anticheat").get(config);
            Object antiXray=anticheat.getClass().getField("antiXray").get(anticheat);
            return antiXray.getClass().getField("enabled").getBoolean(antiXray)?Mode.ENABLED:Mode.DISABLED;
        } catch(ReflectiveOperationException|RuntimeException unavailable) { return Mode.UNKNOWN; }
    }
}
