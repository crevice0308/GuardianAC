/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

public record BlockPosition(int x,int y,int z) {
    public long chunkKey() { return ((long)(x>>4)<<32)^((z>>4)&0xffffffffL); }
    public double distanceSquared(double px,double py,double pz) {
        double dx=x+.5-px,dy=y+.5-py,dz=z+.5-pz; return dx*dx+dy*dy+dz*dz;
    }
}
