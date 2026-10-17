/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import dev.guardianac.core.Sample;
import java.util.*;

/** Bounded per-player mining evidence; ratios are review-only heuristics, not client-mod identification. */
public final class MiningStatistics {
    private static final int MAX_BLOCKS=4096,MAX_PLAYERS=10_000;
    private final Map<UUID,History> histories=new HashMap<>();
    public Sample record(UUID player,UUID world,long now,long tick,BlockPosition position,boolean rare,
                         boolean previouslyHidden,double tps,int ping,Set<String> flags) {
        History history=histories.get(player);
        if(history==null||!history.world.equals(world)||now-history.lastAt>1_800_000) {
            if(history==null&&histories.size()>=MAX_PLAYERS)return null;
            history=new History(world);histories.put(player,history);
        }
        history.lastAt=now;
        if(history.blocks.size()>=MAX_BLOCKS) history.blocks.removeFirst();
        int dx=0,dz=0;
        if(history.previous!=null) {dx=Integer.signum(position.x()-history.previous.x());dz=Integer.signum(position.z()-history.previous.z());}
        if((dx!=0||dz!=0)&&(history.dx!=0||history.dz!=0)&&(dx!=history.dx||dz!=history.dz))history.sinceTurn=0;
        else history.sinceTurn=Math.min(1000,history.sinceTurn+1);
        history.dx=dx;history.dz=dz;history.previous=position;
        // Group adjacent rare blocks into veins; avoid inflating a ratio with each block of one vein.
        boolean newVein=rare&&history.blocks.stream().noneMatch(b->b.rare&&b.position.distanceSquared(position.x()+.5,position.y()+.5,position.z()+.5)<=16);
        history.blocks.addLast(new Observation(position,rare,newVein,newVein&&previouslyHidden,newVein&&previouslyHidden&&history.sinceTurn<=6));
        int rares=0,veins=0,hidden=0,targeted=0;
        for(Observation b:history.blocks){if(b.rare)rares++;if(b.newVein)veins++;if(b.hidden)hidden++;if(b.targeted)targeted++;}
        Sample.Builder result=Sample.builder(player,Sample.Kind.MINING,now,tick)
            .flag("world:"+world).value("tps",tps).value("ping",ping).value("x",position.x()).value("y",position.y()).value("z",position.z())
            .value("mined-blocks",history.blocks.size()).value("rare-ratio",rares/(double)history.blocks.size())
            .value("hidden-veins",hidden).value("targeted-turn-ratio",hidden==0?0:targeted/(double)hidden)
            .value("distinct-rare-veins",veins).value("blocks-per-vein",veins==0?Double.NaN:history.blocks.size()/(double)veins);
        flags.forEach(result::flag);return result.build();
    }
    public void forget(UUID player){histories.remove(player);}
    public void clear(){histories.clear();}
    public int historySize(UUID player){History h=histories.get(player);return h==null?0:h.blocks.size();}
    private record Observation(BlockPosition position,boolean rare,boolean newVein,boolean hidden,boolean targeted) { }
    private static final class History {
        final UUID world;final Deque<Observation> blocks=new ArrayDeque<>();long lastAt;
        BlockPosition previous;int dx,dz,sinceTurn=1000;
        History(UUID world){this.world=world;}
    }
}
