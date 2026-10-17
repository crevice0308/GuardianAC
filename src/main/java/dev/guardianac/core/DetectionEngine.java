/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import java.util.*;
import java.util.function.Predicate;

/** Per-player engine. Call on the server thread only. Packet ingress is queued by the adapter. */
public final class DetectionEngine {
    public record Evaluation(List<Finding> findings, boolean banRecommended, double totalVl, int independentFamilies) {}
    private static final long WINDOW=120_000, COOLDOWN=500;
    private final List<Check> checks;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final int maxPlayers;
    private Preset preset = Preset.STRICT;
    private Predicate<String> enabled = id -> true;
    private double relaxation=1;
    public DetectionEngine(List<Check> checks, int maxPlayers) { this.checks=List.copyOf(checks); this.maxPlayers=maxPlayers; }
    public void preset(Preset preset) { this.preset=Objects.requireNonNull(preset); }
    public void enabled(Predicate<String> enabled) { this.enabled=Objects.requireNonNull(enabled); }
    /** Automatic tuning can only require more evidence, never make bans easier. */
    public void relaxation(double value) { relaxation=Math.max(1,Math.min(3,Double.isFinite(value)?value:3)); }
    /** Recomputed on configuration publication too, so OFF cannot retain a prior TPS multiplier. */
    public void automaticTuning(boolean enabled,double tps) {
        relaxation(enabled&&(!Double.isFinite(tps)||tps<19)?2:1);
    }
    public void reset(UUID player) { players.remove(player); }
    public void clear() { players.clear(); }
    public List<Check> checks() { return checks; }
    public List<Finding> current(UUID id) { var s=players.get(id); return s==null?List.of():List.copyOf(s.latest.values()); }
    /** Captures the actual current (decayed) cell values, not stale VL embedded in earlier findings. */
    public BanEvidence evidence(UUID id,long now) {
        PlayerState state=players.get(id);if(state==null)return new BanEvidence(List.of(),now);
        double decay=Math.min(3600,Math.max(0,now-state.lastTime)/1000.0)*.25;
        List<Finding> captured=new ArrayList<>();
        for(Finding f:state.latest.values()) {
            Cell cell=state.cells.get(f.checkId());if(cell==null)continue;
            captured.add(new Finding(f.checkId(),f.family(),f.hardInvariant(),f.confidence(),Math.max(0,cell.vl-decay),
                f.occurrences(),f.firstSeenMillis(),f.lastSeenMillis(),f.sample()));
        }
        return new BanEvidence(captured,now);
    }
    public Evaluation accept(Sample sample) {
        PlayerState state=players.get(sample.playerId());
        if(state==null) {
            if(players.size()>=maxPlayers) return new Evaluation(List.of(),false,0,0);
            state=new PlayerState(); players.put(sample.playerId(),state);
        }
        // Main-thread event snapshots and drained Netty data arrive in different orders.
        // Reject stale/replayed data within its own stream, while permitting bounded cross-stream lag.
        long lastKind=state.lastKind.getOrDefault(sample.kind(),Long.MIN_VALUE);
        if(sample.timeMillis()<lastKind || state.lastTime-sample.timeMillis()>500) return new Evaluation(List.of(),false,0,0);
        state.lastKind.put(sample.kind(),sample.timeMillis());
        double elapsed=Math.min(3600,Math.max(0,sample.timeMillis()-state.lastTime)/1000.0);
        if(state.lastTime>0 && elapsed>0) for(var cell:state.cells.values()) cell.vl=Math.max(0,cell.vl-elapsed*.25);
        state.lastTime=Math.max(state.lastTime,sample.timeMillis());
        List<Finding> findings=new ArrayList<>();
        for(Check check:checks) {
            if(check.kind()!=sample.kind() || !enabled.test(check.id())) continue;
            Cell cell=state.cells.computeIfAbsent(check.id(),id->new Cell());
            if(sample.timeMillis()-cell.first>WINDOW) { cell.first=sample.timeMillis(); cell.count=0; cell.vl=0; }
            // Unrelated packet types are missing data, not exculpatory observations.
            // Normal elapsed time decays VL above; it cannot erase history by flooding other packet types.
            if(!check.test(sample)) continue;
            if(cell.last>0 && sample.timeMillis()-cell.last<COOLDOWN) continue;
            cell.last=sample.timeMillis(); if(cell.first==0) cell.first=sample.timeMillis();
            cell.count++;
            int required=(int)Math.ceil(check.requiredSamples()*preset.sampleMultiplier*(check.hardInvariant()?1:relaxation));
            if(cell.count<required) continue;
            cell.vl=Math.min(500,cell.vl+check.vlWeight());
            Finding f=new Finding(check.id(),check.family(),check.hardInvariant(),check.confidence(),cell.vl,cell.count,cell.first,cell.last,sample);
            state.latest.put(check.id(),f); findings.add(f);
        }
        Set<String> families=new HashSet<>(); double vl=0;
        for(var entry:state.latest.entrySet()) {
            Finding f=entry.getValue(); Cell cell=state.cells.get(entry.getKey());
            if(!enabled.test(f.checkId()) || sample.timeMillis()-f.lastSeenMillis()>WINDOW || cell.vl<=0) continue;
            if(f.hardInvariant() && f.confidence()>=.99 && f.occurrences()>=6
                && f.lastSeenMillis()-f.firstSeenMillis()>=3000) { families.add(f.family()); vl+=cell.vl; }
        }
        boolean recommend=!sample.has("bypass") && !sample.has("exempt") && families.size()>=2 && vl>=preset.banThreshold;
        return new Evaluation(List.copyOf(findings),recommend,vl,families.size());
    }
    private static final class PlayerState {
        long lastTime; final EnumMap<Sample.Kind,Long> lastKind=new EnumMap<>(Sample.Kind.class);
        final Map<String,Cell> cells=new HashMap<>(); final Map<String,Finding> latest=new HashMap<>();
    }
    private static final class Cell { double vl; long first,last; int count; }
}
