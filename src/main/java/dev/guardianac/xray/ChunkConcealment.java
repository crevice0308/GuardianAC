/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import java.util.function.IntUnaryOperator;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.*;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Per-recipient packet rewrite. Network callbacks use packet bytes and immutable snapshots only. */
public final class ChunkConcealment {
    public record Viewer(UUID worldId,int minY,int maxY,double x,double y,double z,long atMillis,
                         String environment,NativeAntiXray.Mode nativeMode,boolean bypass) { }
    private record Traits(boolean ore,boolean opaque,boolean air) { }
    private static final Set<String> OPAQUE=Set.of("stone","deepslate","tuff","granite","diorite","andesite",
        "netherrack","end_stone","basalt","smooth_basalt","blackstone","dirt","coarse_dirt","gravel",
        "sand","red_sand","sandstone","red_sandstone","bedrock","clay","terracotta","calcite","cobblestone");
    private final ConcurrentHashMap<UUID,Viewer> viewers=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,Traits> traits=new ConcurrentHashMap<>();
    private final HiddenBlockCache cache=new HiddenBlockCache();
    private final ConcealmentPolicy policy=new ConcealmentPolicy();
    private final ChunkWorkBudget chunkBudget=new ChunkWorkBudget();
    private final LongAdder chunks=new LongAdder(),hidden=new LongAdder(),skipped=new LongAdder(),failures=new LongAdder();
    private final LongAdder noTargets=new LongAdder(),budgetLimited=new LongAdder();
    /** Warm native mappings on the server thread, before packet callbacks perform any palette work. */
    public void prepare(ClientVersion version) {StateTypes.STONE.createBlockState(version);StateTypes.DEEPSLATE.createBlockState(version);}
    public void stop() {policy.stop();}
    public void configure(XraySettings next) {policy.configure(next);}
    public XraySettings settings() {return policy.snapshot().settings();}
    public void publish(UUID id,Viewer viewer) {if(viewers.containsKey(id)||viewers.size()<10_000)viewers.put(id,viewer);}
    public void forget(UUID id) {viewers.remove(id);cache.forget(id);}
    public HiddenBlockCache cache() {return cache;}
    public long concealedChunks() {return chunks.sum();}
    public long concealedBlocks() {return hidden.sum();}
    public long skippedChunks() {return skipped.sum();}
    public long failedChunks() {return failures.sum();}
    public long noTargetChunks() {return noTargets.sum();}
    public long budgetLimitedChunks() {return budgetLimited.sum();}
    public void onPacketSend(PacketSendEvent event) {
        if(policy.stopped())return;
        UUID id=event.getUser().getUUID(); if(id==null)return;
        var type=event.getPacketType();
        Long authoritativeColumn=null;boolean replaced=false;
        try {
            if(type==PacketType.Play.Server.UNLOAD_CHUNK) {
                var p=new WrapperPlayServerUnloadChunk(event);cache.unload(id,HiddenBlockCache.chunkKey(p.getChunkX(),p.getChunkZ()));return;
            }
            if(type==PacketType.Play.Server.RESPAWN) {cache.forget(id);return;}
            // Real block updates always win. Nearby authoritative main-thread reveal handles exposed neighbors.
            if(type==PacketType.Play.Server.BLOCK_CHANGE) {
                var p=new WrapperPlayServerBlockChange(event).getBlockPosition();cache.update(id,new BlockPosition(p.getX(),p.getY(),p.getZ()));return;
            }
            if(type==PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
                var p=new WrapperPlayServerMultiBlockChange(event);
                for(var b:p.getBlocks()) cache.update(id,new BlockPosition(b.getX(),b.getY(),b.getZ()));return;
            }
            if(type!=PacketType.Play.Server.CHUNK_DATA)return;
            // Identifying a native chunk only needs its fixed eight-byte header, even when policy skips it.
            // This does not decode native anti-Xray palettes or mutate the packet reader position.
            if(event.getUser().getClientVersion()==event.getServerVersion().toClientVersion())authoritativeColumn=columnKey(event);
            ConcealmentPolicy.Snapshot snapshot=policy.snapshot();
            XraySettings config=snapshot.settings(); Viewer viewer=viewers.get(id);
            if(!config.enabled()||(!config.concealOres()&&!config.concealCaves())||viewer==null||viewer.bypass()
                ||viewer.nativeMode()==NativeAntiXray.Mode.ENABLED||viewer.nativeMode()==NativeAntiXray.Mode.UNKNOWN
                ||System.currentTimeMillis()-viewer.atMillis()>1000
                ||event.getUser().getClientVersion()!=event.getServerVersion().toClientVersion()) {skipped.increment();return;}
            replaced=rewrite(event,id,viewer,snapshot);
        } catch(RuntimeException rejected) {failures.increment();} // fail-open without disconnect or partial world mutation
        finally {
            // Every unmodified authoritative replacement supersedes this column's older reservation only.
            if(authoritativeColumn!=null)completeColumn(id,authoritativeColumn,replaced);
        }
    }
    void completeColumn(UUID player,long chunk,boolean rewritten) {if(!rewritten)cache.unload(player,chunk);}
    private boolean rewrite(PacketSendEvent event,UUID id,Viewer viewer,ConcealmentPolicy.Snapshot snapshot) {
        XraySettings config=snapshot.settings();
        var packet=new WrapperPlayServerChunkData(event); Column column=packet.getColumn();
        int minY=viewer.minY(),height=viewer.maxY()-minY;
        BaseChunk[] sections=column.getChunks(); long key=HiddenBlockCache.chunkKey(column.getX(),column.getZ());
        if(height<16||height>1024||height%16!=0||sections.length!=height/16) {skipped.increment();return false;}
        ClientVersion version=event.getServerVersion().toClientVersion();
        for(BaseChunk section:sections)if(!(section instanceof Chunk_v1_18)) {skipped.increment();return false;}
        ChunkWorkBudget.Decision admission=chunkBudget.admit(System.nanoTime()/1_000_000,config.maxChunksPerSecond(),
            ()->PalettePrefilter.mayContainTargets(sections,minY,viewer.maxY(),config.maxHeight(),config.concealOres(),
                config.concealCaves(),block->traits(block,version).ore(),block->traits(block,version).air()));
        if(admission!=ChunkWorkBudget.Decision.ADMITTED) {
            // This authoritative unmodified column supersedes any earlier recipient-specific mask.
            if(admission==ChunkWorkBudget.Decision.NO_TARGETS)noTargets.increment();
            else {budgetLimited.increment();skipped.increment();}
            return false;
        }
        int[] original=new int[height*256];
        for(int section=0;section<sections.length;section++) {
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++)
                original[(section*16+y)*256+z*16+x]=sections[section].getBlockId(x,y,z);
        }
        int radius=config.concealCaves()?Math.max(24,config.revealRadius()):config.revealRadius();
        BitSet mask=ConcealmentMask.select(original,minY,viewer.maxY(),config.maxHeight(),column.getX(),column.getZ(),
            viewer.x(),viewer.y(),viewer.z(),radius,config.concealCaves(),config.maxHiddenPerChunk(),
            block->config.concealOres()&&traits(block,version).ore(),block->traits(block,version).opaque(),block->traits(block,version).air());
        if(mask==null) {skipped.increment();return false;}
        if(mask.isEmpty())return false;
        BaseChunk[] copy=sections.clone(); BitSet copied=new BitSet(sections.length);Set<BlockPosition> positions=new HashSet<>();
        int stone=StateTypes.STONE.createBlockState(version).getGlobalId();
        int deep=StateTypes.DEEPSLATE.createBlockState(version).getGlobalId();
        int replacement=switch(viewer.environment()) {
            case "NETHER"->StateTypes.NETHERRACK.createBlockState(version).getGlobalId();
            case "THE_END"->StateTypes.END_STONE.createBlockState(version).getGlobalId();default->-1;
        };
        for(int index=mask.nextSetBit(0);index>=0;index=mask.nextSetBit(index+1)) {
            int columnY=index/256,z=(index%256)/16,x=index%16,section=columnY/16,y=minY+columnY;
            if(!copied.get(section)) {copy[section]=copySection((Chunk_v1_18)sections[section],version);copied.set(section);}
            copy[section].set(version,x,columnY&15,z,replacement>=0?replacement:y<0?deep:stone);
            positions.add(new BlockPosition(column.getX()*16+x,y,column.getZ()*16+z));
        }
        // Nothing has touched the outgoing packet until the restoration ledger has sufficient capacity.
        Column output=new Column(column.getX(),column.getZ(),column.isFullChunk(),copy,column.getTileEntities(),column.getHeightmaps());
        boolean[] rewritten={false};
        boolean committed=policy.commit(snapshot,()->{
            long reservation=cache.reserve(id,viewer.worldId(),key,positions,config.maxHiddenPerPlayer(),config.maxHiddenGlobal());
            if(reservation==0) {skipped.increment();return;}
            packet.setColumn(output);event.markForReEncode(true);
            event.getTasksAfterSend().add(()->cache.activate(id,viewer.worldId(),key,reservation));
            rewritten[0]=true;
            chunks.increment();hidden.add(positions.size());
        });
        if(!committed)skipped.increment();
        return rewritten[0];
    }
    private static long columnKey(PacketSendEvent event) {
        if(event.getLastUsedWrapper() instanceof WrapperPlayServerChunkData packet) {
            Column column=packet.getColumn();return HiddenBlockCache.chunkKey(column.getX(),column.getZ());
        }
        Object buffer=event.getByteBuf();int start=ByteBufHelper.readerIndex(buffer);
        return columnKey(ByteBufHelper.readableBytes(buffer),offset->ByteBufHelper.getUnsignedByte(buffer,start+offset));
    }
    /** Pure fixed-header parser; the supplied indexed reader must not advance the packet cursor. */
    static long columnKey(int readableBytes,IntUnaryOperator byteAt) {
        if(readableBytes<8)throw new IllegalArgumentException("Truncated chunk coordinates");
        int x=0,z=0;
        for(int n=0;n<4;n++)x=(x<<8)|(byteAt.applyAsInt(n)&255);
        for(int n=4;n<8;n++)z=(z<<8)|(byteAt.applyAsInt(n)&255);
        return HiddenBlockCache.chunkKey(x,z);
    }
    private Traits traits(int id,ClientVersion version) {
        return traits.computeIfAbsent(id,key->{
            var state=WrappedBlockState.getByGlobalId(version,key);String name=state.getType().getName().replace("minecraft:","");
            boolean ore=name.endsWith("_ore")||name.equals("ancient_debris");
            return new Traits(ore,ore||OPAQUE.contains(name),state.getType().isAir());
        });
    }
    static Chunk_v1_18 copySection(Chunk_v1_18 source,ClientVersion version) {
        DataPalette blocks=PaletteType.CHUNK.create(),biomes=PaletteType.BIOME.create();
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)blocks.set(x,y,z,source.getChunkData().get(x,y,z));
        for(int y=0;y<4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)biomes.set(x,y,z,source.getBiomeData().get(x,y,z));
        return new Chunk_v1_18(version,source.getBlockCount(),source.getFluidCount(),blocks,biomes);
    }
}
