/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

/** Immutable GUI/config handoff. Cave masking remains opt-in pending real-client validation. */
public record XraySettings(boolean enabled, boolean concealOres, boolean concealCaves,
                           boolean miningAnalysis, int maxHeight, int revealRadius,
                           int maxHiddenPerChunk, int maxHiddenPerPlayer, int maxHiddenGlobal,
                           int maxChunksPerSecond) {
    public XraySettings {
        if(maxHeight < -64 || maxHeight > 320 || revealRadius < 6 || revealRadius > 32
            || maxHiddenPerChunk < 128 || maxHiddenPerChunk > 8192
            || maxHiddenPerPlayer < maxHiddenPerChunk || maxHiddenPerPlayer > 131072
            || maxHiddenGlobal < maxHiddenPerPlayer || maxHiddenGlobal > 1_048_576
            || maxChunksPerSecond < 1 || maxChunksPerSecond > 512)
            throw new IllegalArgumentException("Unsafe Xray resource limits");
    }
    public static XraySettings defaults() { return new XraySettings(true,true,false,true,64,8,4096,32768,524288,128); }
    /** The documented Xray master switch controls mining observation as well as masking. */
    public boolean observesMining() { return enabled&&miningAnalysis; }
    public XraySettings withEnabled(boolean value) { return new XraySettings(value,concealOres,concealCaves,miningAnalysis,maxHeight,revealRadius,maxHiddenPerChunk,maxHiddenPerPlayer,maxHiddenGlobal,maxChunksPerSecond); }
    public XraySettings withConcealOres(boolean value) { return new XraySettings(enabled,value,concealCaves,miningAnalysis,maxHeight,revealRadius,maxHiddenPerChunk,maxHiddenPerPlayer,maxHiddenGlobal,maxChunksPerSecond); }
    public XraySettings withConcealCaves(boolean value) { return new XraySettings(enabled,concealOres,value,miningAnalysis,maxHeight,revealRadius,maxHiddenPerChunk,maxHiddenPerPlayer,maxHiddenGlobal,maxChunksPerSecond); }
    public XraySettings withMiningAnalysis(boolean value) { return new XraySettings(enabled,concealOres,concealCaves,value,maxHeight,revealRadius,maxHiddenPerChunk,maxHiddenPerPlayer,maxHiddenGlobal,maxChunksPerSecond); }
}
