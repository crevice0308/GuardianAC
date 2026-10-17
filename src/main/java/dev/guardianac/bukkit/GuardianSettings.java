/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import dev.guardianac.core.Preset;
import dev.guardianac.storage.BanType;
import dev.guardianac.security.PrivateFiles;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class GuardianSettings {
    private final Path root;
    private long revision;
    private Runnable beforePublish=()->{};
    private volatile YamlConfiguration config; // Published snapshots are never mutated.
    public GuardianSettings(JavaPlugin plugin) throws IOException {
        plugin.saveDefaultConfig();root=plugin.getDataFolder().toPath();reload();
    }
    /** Existing-file constructor for persistence tests; production still creates the default configuration. */
    GuardianSettings(Path root) throws IOException {
        this.root=Objects.requireNonNull(root);reload();
    }
    public synchronized void reload() throws IOException {
        Path path=root.resolve("config.yml");
        PrivateFiles.rejectSymlinks(path);
        YamlConfiguration next=new YamlConfiguration();
        try { next.loadFromString(new String(PrivateFiles.read(path,1024*1024),StandardCharsets.UTF_8)); } catch(Exception e) { throw new IOException("Invalid configuration",e); }
        long nextRevision=Math.incrementExact(revision);
        beforePublish.run();config=next;revision=nextRevision;
    }
    /** Lifecycle owner installs a pure invalidation hook before any live configuration changes. */
    public synchronized void beforePublish(Runnable hook) { beforePublish=Objects.requireNonNull(hook); }
    public String language() { return config.getString("language","ja").equals("en")?"en":"ja"; }
    public String text(String ja,String en) { return language().equals("ja")?ja:en; }
    public Preset preset() { try { return Preset.valueOf(config.getString("preset","STRICT")); } catch(IllegalArgumentException e) { return Preset.STRICT; } }
    public BanType banType() { try { return BanType.valueOf(config.getString("ban-type","UUID")); } catch(IllegalArgumentException e) { return BanType.UUID; } }
    public boolean enabled(String path) { return config.getBoolean(path,false); }
    public boolean checkEnabled(String id) { return config.getBoolean("checks."+id,true); }
    public int hours(boolean ip) { int n=config.getInt(ip?"ip-duration-hours":"ban-duration-hours",24); return Set.of(0,1,24,168,720).contains(n)?n:24; }
    public int limit(String id,int fallback,int min,int max) { return Math.max(min,Math.min(max,config.getInt("limits."+id,fallback))); }
    /** Process-local publication revision; open GUI sessions do not survive a server restart. */
    public synchronized long revision() { return revision; }
    /** Missing keys use their documented defaults; manual malformed values are never displayed verbatim. */
    public boolean validLimit(String id,int min,int max) {
        YamlConfiguration snapshot=config;String key="limits."+id;
        return !snapshot.contains(key)||(snapshot.isInt(key)&&snapshot.getInt(key)>=min&&snapshot.getInt(key)<=max);
    }
    public synchronized boolean setIfRevision(String key,Object value,long expectedRevision) throws IOException {
        if(revision!=expectedRevision)return false;
        set(key,value);return true;
    }
    public synchronized void set(String key,Object value) throws IOException {
        YamlConfiguration next=new YamlConfiguration();
        try {next.loadFromString(config.saveToString());}catch(Exception e){throw new IOException("Invalid configuration snapshot",e);}
        long nextRevision=Math.incrementExact(revision);
        next.set(key,value);save(next);beforePublish.run();config=next;revision=nextRevision;
    }
    public synchronized void toggle(String key) throws IOException { set(key,!enabled(key)); }
    private void save(YamlConfiguration snapshot) throws IOException {
        Path target=root.resolve("config.yml");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))
            PrivateFiles.replace(root,"config.yml.backup",PrivateFiles.read(target,1024*1024),1024*1024,1024);
        PrivateFiles.replace(root,"config.yml",snapshot.saveToString().getBytes(StandardCharsets.UTF_8),1024*1024,1024);
    }
}
