/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import java.util.*;

/** Bounded position-only ledger. Reveals use current main-thread world state, never stale originals. */
public final class HiddenBlockCache {
    private final Map<UUID,PlayerCache> players=new HashMap<>();
    private int size;
    private long generation;
    public synchronized boolean replace(UUID player,UUID world,long chunk,Set<BlockPosition> hidden,
                                        int perPlayerLimit,int globalLimit) {
        return replace0(player,world,chunk,hidden,perPlayerLimit,globalLimit,true)!=0;
    }
    /** Reserve budget before rewrite; positions become revealable only after the chunk was sent. */
    public synchronized long reserve(UUID player,UUID world,long chunk,Set<BlockPosition> hidden,
                                     int perPlayerLimit,int globalLimit) {
        return replace0(player,world,chunk,hidden,perPlayerLimit,globalLimit,false);
    }
    private long replace0(UUID player,UUID world,long chunk,Set<BlockPosition> hidden,
                          int perPlayerLimit,int globalLimit,boolean active) {
        PlayerCache cache=players.get(player);
        if(cache!=null&&!cache.world.equals(world)) {forget(player);cache=null;}
        Column old=cache==null?null:cache.chunks.get(chunk);
        int previous=old==null?0:old.positions.size(),playerSize=cache==null?0:cache.size;
        if(size-previous+hidden.size()>globalLimit||playerSize-previous+hidden.size()>perPlayerLimit
            ||(cache!=null&&!cache.chunks.containsKey(chunk)&&cache.chunks.size()>=256)) {
            // An unmasked replacement chunk supersedes any prior masked state in that column.
            unload(player,chunk);return 0;
        }
        if(cache==null){cache=new PlayerCache(world);players.put(player,cache);}
        long token=++generation;
        cache.chunks.put(chunk,new Column(new HashSet<>(hidden),token,active));
        cache.size+=hidden.size()-previous;size+=hidden.size()-previous;return token;
    }
    public synchronized void activate(UUID player,UUID world,long chunk,long token) {
        PlayerCache cache=players.get(player);if(cache==null||!cache.world.equals(world))return;
        Column column=cache.chunks.get(chunk);if(column!=null&&column.token==token)column.active=true;
    }
    public synchronized List<BlockPosition> takeNearby(UUID player,UUID world,double x,double y,double z,int radius,int limit) {
        PlayerCache cache=players.get(player);if(cache==null||!cache.world.equals(world))return List.of();
        List<BlockPosition> result=new ArrayList<>();double distance=(double)radius*radius;
        int minChunkX=((int)Math.floor(x)-radius)>>4,maxChunkX=((int)Math.floor(x)+radius)>>4;
        int minChunkZ=((int)Math.floor(z)-radius)>>4,maxChunkZ=((int)Math.floor(z)+radius)>>4;
        for(int cx=minChunkX;cx<=maxChunkX;cx++)for(int cz=minChunkZ;cz<=maxChunkZ;cz++){
            Column column=cache.chunks.get(chunkKey(cx,cz));if(column==null||!column.active)continue;
            Iterator<BlockPosition> it=column.positions.iterator();
            while(it.hasNext()&&result.size()<limit){BlockPosition pos=it.next();if(pos.distanceSquared(x,y,z)<=distance){result.add(pos);it.remove();cache.size--;size--;}}
        }
        return List.copyOf(result);
    }
    public synchronized List<BlockPosition> takeAll(UUID player,UUID world,int limit) {
        return takeAll0(player,world,limit,false);
    }
    /** Shutdown only: commit lock must be stopped before queuing restoration after pending chunks. */
    public synchronized List<BlockPosition> takeAllIncludingPending(UUID player,UUID world,int limit) {
        return takeAll0(player,world,limit,true);
    }
    private List<BlockPosition> takeAll0(UUID player,UUID world,int limit,boolean pending) {
        PlayerCache cache=players.get(player);if(cache==null||!cache.world.equals(world))return List.of();
        List<BlockPosition> result=new ArrayList<>();
        for(Column column:cache.chunks.values()){
            if(!column.active&&!pending)continue;Iterator<BlockPosition> it=column.positions.iterator();
            while(it.hasNext()&&result.size()<limit){result.add(it.next());it.remove();cache.size--;size--;}
            if(result.size()>=limit)break;
        }
        return List.copyOf(result);
    }
    public synchronized void update(UUID player,BlockPosition position) {
        PlayerCache cache=players.get(player);if(cache==null)return;
        Column column=cache.chunks.get(position.chunkKey());
        if(column!=null&&column.positions.remove(position)){cache.size--;size--;}
    }
    public synchronized void unload(UUID player,long chunk) {
        PlayerCache cache=players.get(player);if(cache==null)return;
        Column removed=cache.chunks.remove(chunk);if(removed!=null){size-=removed.positions.size();cache.size-=removed.positions.size();}
        if(cache.chunks.isEmpty())players.remove(player);
    }
    public synchronized void forget(UUID player){PlayerCache c=players.remove(player);if(c!=null)size-=c.size;}
    public synchronized int size(){return size;}
    public synchronized int playerCount(){return players.size();}
    public static long chunkKey(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    private static final class PlayerCache {
        final UUID world;final Map<Long,Column> chunks=new HashMap<>();int size;
        PlayerCache(UUID world){this.world=world;}
    }
    private static final class Column {
        final Set<BlockPosition> positions;final long token;boolean active;
        Column(Set<BlockPosition> positions,long token,boolean active){this.positions=positions;this.token=token;this.active=active;}
    }
}
