/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.security;

import java.util.*;

/** Bounded token buckets; refuses new keys when full instead of unbounded allocation. */
public final class BoundedRateLimiter<K> {
    private final Map<K,Bucket> buckets=new HashMap<>();
    private final int keys; private final double capacity,perMillis;
    public BoundedRateLimiter(int keys,double capacity,double perSecond) {
        if(keys<1||!Double.isFinite(capacity)||!Double.isFinite(perSecond)||capacity<=0||perSecond<=0) throw new IllegalArgumentException();
        this.keys=keys;this.capacity=capacity;this.perMillis=perSecond/1000;
    }
    public synchronized boolean allow(K key,long now) {
        Bucket b=buckets.get(key);
        if(b==null) {
            if(buckets.size()>=keys) buckets.values().removeIf(old->now-old.last>120_000);
            if(buckets.size()>=keys) return false;
            b=new Bucket(capacity,now); buckets.put(key,b);
        }
        b.tokens=Math.min(capacity,b.tokens+Math.max(0,now-b.last)*perMillis); b.last=Math.max(now,b.last);
        if(b.tokens<1) return false; b.tokens--; return true;
    }
    public synchronized int size() { return buckets.size(); }
    public synchronized void clear() { buckets.clear(); }
    private static final class Bucket { double tokens;long last;Bucket(double tokens,long last){this.tokens=tokens;this.last=last;} }
}
