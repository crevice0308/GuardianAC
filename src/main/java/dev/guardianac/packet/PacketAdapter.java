/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import dev.guardianac.core.Sample;
import dev.guardianac.core.RuntimeTarget;
import dev.guardianac.xray.ChunkConcealment;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Embedded, relocated PacketEvents lifecycle and bounded ingress; no Bukkit in packet callbacks. */
public final class PacketAdapter implements AutoCloseable {
    private static final int MAX_TRACKED = 10_000;
    private final JavaPlugin plugin;
    private final Consumer<Sample> sink;
    private final ConcurrentHashMap<UUID, PacketSession> states = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Envelope> ingress = new ArrayBlockingQueue<>(8192);
    private final LongAdder dropped = new LongAdder(), cancelled = new LongAdder(), malformed = new LongAdder();
    private final LongAdder contextCaptureFailures = new LongAdder();
    private boolean warnedCaptureFailure;
    private PacketEventsAPI<Plugin> api;
    private volatile boolean running;
    private volatile ChunkConcealment concealment;
    private final PacketListenerAbstract listener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        @Override public void onPacketReceive(PacketReceiveEvent event) { receive(event); }
        @Override public void onPacketSend(PacketSendEvent event) {
            if (!running || event.isCancelled()) return;
            User user=event.getUser(); UUID id=user.getUUID(); if(id==null) return;
            PacketSession state=states.get(id); if(state==null) return;
            synchronized(state) {
                ChunkConcealment current=concealment;
                if(running && current!=null && states.get(id)==state && state.acceptsUser(user)
                    && supportedProtocol(event.getServerVersion(),user.getClientVersion())) current.onPacketSend(event);
            }
        }
        @Override public void onUserDisconnect(UserDisconnectEvent event) { retire(event.getUser()); }

    };
    public PacketAdapter(JavaPlugin plugin, Consumer<Sample> sink) { this.plugin=plugin; this.sink=sink; }
    /** Call from JavaPlugin.onLoad, before listeners can receive players. */
    public void load() {
        if (api != null) return;
        api = SpigotPacketEventsBuilder.build(plugin);
        PacketEvents.setAPI(api);
        // The embedded builder is source-patched to omit bStats; its upstream setting is a no-op.
        api.getSettings().checkForUpdates(false).debug(false)
            .kickOnPacketException(false).kickIfTerminated(false).reEncodeByDefault(false);
        api.load();
    }
    /** Call from onEnable on the server thread. */
    public void init() {
        if (api == null) throw new IllegalStateException("load must run first");
        if(api.getServerManager().getVersion()!=ServerVersion.V_26_3)return;
        api.getEventManager().registerListener(listener);
        api.init(); running=true;
    }
    public boolean supportedRuntime() { return running&&api!=null&&api.getServerManager().getVersion()==ServerVersion.V_26_3; }
    public void concealment(ChunkConcealment concealment) { this.concealment=concealment; }
    /** Main thread only: resolve the exact Player connection, never PE's UUID-to-channel cache. */
    public void publishContext(Player player, PacketContext context) {
        mainThread("Snapshot");
        if(!running || api==null) return;
        try {
            Object channel=SpigotReflectionUtil.getChannel(player);
            if(channel==null || !ChannelHelper.isOpen(channel)) return;
            User user=api.getProtocolManager().getUser(channel);
            if(user==null || user.getChannel()!=channel) return;
            bind(player,user,context);
        } catch(RuntimeException | LinkageError unavailable) {
            // An unavailable native connection cannot authorize another UUID-matching User.
            // Existing snapshots expire normally; do not stop the server's pulse.
            contextCaptureFailures.increment();
            if(plugin!=null && !warnedCaptureFailure) {
                warnedCaptureFailure=true;
                plugin.getLogger().warning("Packet session capture failed; new session telemetry is skipped. Check server and embedded PacketEvents compatibility.");
            }
        }
    }
    /** Main-thread admission seam. Package-private for isolated adapter-boundary fixtures only. */
    void bind(Player player, User user, PacketContext context) {
        mainThread("Admission");
        Objects.requireNonNull(player); Objects.requireNonNull(user); Objects.requireNonNull(context);
        UUID id=player.getUniqueId();
        if(!running || !id.equals(user.getUUID()) || !open(user)) return;
        PacketSession state=states.get(id);
        if(state!=null) {
            synchronized(state) {
                // A disconnect cannot be undone while its retirement is waiting for the main thread.
                if(state.user==user && state.retired) return;
                if(state.sameConnection(player,user) && state.context.worldId().equals(context.worldId())) {
                    state.context=context; return;
                }
                remove(id,state);
            }
        }
        if(states.size()<MAX_TRACKED && open(user)) states.put(id,new PacketSession(player,user,context));
    }
    /** Unknown/unobserved sessions are ineligible until their exact native protocol has been observed. */
    public boolean exactProtocol(UUID id) { PacketSession state=states.get(id);return state!=null&&!state.retired&&state.exactProtocol; }
    public Optional<PacketMovement> movement(UUID id) {
        PacketSession state=states.get(id); return Optional.ofNullable(state==null||state.retired?null:state.latest);
    }
    public Optional<PacketContext> context(UUID id) {
        PacketSession state=states.get(id); return Optional.ofNullable(state==null||state.retired?null:state.context);
    }
    /** Main-thread drain bounds engine work and rejects observations from replaced sessions. */
    public int drain(int maximum) {
        mainThread("Drain");
        reapRetired();
        int count=0;
        while (count<Math.min(8192,Math.max(0,maximum))) {
            Envelope envelope=ingress.poll(); if(envelope==null) break;
            Sample sample=envelope.sample(); PacketSession current=states.get(sample.playerId());
            if(current!=null&&current.acceptsSample(envelope.owner(),sample,System.currentTimeMillis())) sink.accept(sample);
            else dropped.increment();
            count++;
        }
        return count;
    }
    public long droppedSamples() { return dropped.sum(); }
    public long cancelledPackets() { return cancelled.sum(); }
    public long malformedPackets() { return malformed.sum(); }
    public long contextCaptureFailures() { return contextCaptureFailures.sum(); }
    public int queuedSamples() { return ingress.size(); }
    public void forget(Player player) {
        mainThread("Forget");
        UUID id=player.getUniqueId(); PacketSession state=states.get(id);
        if(state==null) return;
        synchronized(state) { if(state.player==player) remove(id,state); }
    }
    /** Netty only retires the matching connection; reveal-ledger cleanup is owned by the main thread. */
    void retire(User user) {
        UUID id=user.getUUID(); if(id==null) return;
        PacketSession state=states.get(id); if(state==null) return;
        synchronized(state) { if(states.get(id)==state) state.retireUser(user); }
    }
    private void reapRetired() {
        states.forEach((id,state)->{
            synchronized(state) {
                if(state.retired || !open((User)state.user)) remove(id,state);
            }
        });
    }
    /** Caller holds the session lock, so no old chunk rewrite can reserve after this cleanup. */
    private void remove(UUID id, PacketSession state) {
        state.retire();
        if(states.remove(id,state)) {
            ChunkConcealment current=concealment; if(current!=null) current.forget(id);
        }
    }
    private static boolean open(User user) {
        try { return ChannelHelper.isOpen(user.getChannel()); }
        catch(RuntimeException | LinkageError unavailable) { return false; }
    }
    private static void mainThread(String action) {
        if(!Bukkit.isPrimaryThread()) throw new IllegalStateException(action+" on server thread");
    }
    static boolean supportedProtocol(ServerVersion server,ClientVersion client) {
        return server==ServerVersion.V_26_3&&client==ClientVersion.V_26_3
            &&RuntimeTarget.supportsProtocol(server.getProtocolVersion(),client.getProtocolVersion());
    }
    private void receive(PacketReceiveEvent event) {
        if (!running || event.isCancelled() || event.getConnectionState()!=ConnectionState.PLAY) return;
        UUID id=event.getUser().getUUID(); if(id==null) return;
        PacketSession state=states.get(id); if(state==null) return;
        long now=System.currentTimeMillis(), mono=System.nanoTime()/1_000_000;
        synchronized(state) {
            if(!running || states.get(id)!=state || !state.acceptsUser(event.getUser())) return;
            state.exactProtocol=supportedProtocol(event.getServerVersion(),event.getUser().getClientVersion());
            // Keep a conservative eligibility snapshot even when no movement is decoded.
            // Unknown/future/translated protocols are never interpreted as evidence or defensively cancelled.
            if(!state.exactProtocol){state.latest=null;return;}
            PacketContext context=state.context;
            boolean translated=event.getUser().getClientVersion()!=event.getServerVersion().toClientVersion();
            var type=event.getPacketType();
            boolean move=WrapperPlayClientPlayerFlying.isFlying(type);
            boolean swing=type==PacketType.Play.Client.ANIMATION||type==PacketType.Play.Client.PUNCH;
            boolean interaction=type==PacketType.Play.Client.INTERACT_ENTITY||type==PacketType.Play.Client.ATTACK
                ||type==PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT||type==PacketType.Play.Client.USE_ITEM
                ||type==PacketType.Play.Client.PLAYER_DIGGING;
            boolean flood=state.telemetry.record(mono,move,swing,interaction);
            Sample.Builder out=state.telemetry.sample(id,context,now,mono,translated);
            boolean invalid=false;
            try {
                int readable=ByteBufHelper.readableBytes(event.getByteBuf());
                if (flood || readable>1_048_576) {
                    event.setCancelled(true); cancelled.increment();
                    if (readable>1_048_576) out.value("payload-bytes",readable);
                } else if (move) {
                    var packet=new WrapperPlayClientPlayerFlying(event); var location=packet.getLocation();
                    if (packet.hasPositionChanged()) {
                        double x=location.getX(),y=location.getY(),z=location.getZ();
                        out.flag("position-present").value("x",x).value("y",y).value("z",z);
                        invalid=PacketTelemetry.invalidPosition(x,y,z);
                    }
                    if (packet.hasRotationChanged()) {
                        out.flag("rotation-present").value("yaw",location.getYaw()).value("pitch",location.getPitch());
                        invalid|=PacketTelemetry.invalidRotation(location.getYaw(),location.getPitch());
                    }
                    out.flag("packet-ground",packet.isOnGround());
                    if(!invalid) {
                        PacketMovement before=state.latest;
                        state.latest=new PacketMovement(now,
                            packet.hasPositionChanged()?location.getX():before==null?Double.NaN:before.x(),
                            packet.hasPositionChanged()?location.getY():before==null?Double.NaN:before.y(),
                            packet.hasPositionChanged()?location.getZ():before==null?Double.NaN:before.z(),
                            packet.hasRotationChanged()?location.getYaw():before==null?Float.NaN:before.yaw(),
                            packet.hasRotationChanged()?location.getPitch():before==null?Float.NaN:before.pitch(),
                            packet.isOnGround(),packet.hasPositionChanged()||(before!=null&&before.hasPosition()),
                            packet.hasRotationChanged()||(before!=null&&before.hasRotation()),translated);
                    }
                } else if (type==PacketType.Play.Client.HELD_ITEM_CHANGE) {
                    int slot=new WrapperPlayClientHeldItemChange(event).getSlot();
                    out.flag("held-slot-present").value("held-slot",slot); invalid=slot<0||slot>8;
                } else if (type==PacketType.Play.Client.PLAYER_DIGGING) {
                    var packet=new WrapperPlayClientPlayerDigging(event);
                    boolean digging=packet.getAction()==DiggingAction.START_DIGGING||packet.getAction()==DiggingAction.FINISHED_DIGGING;
                    if(digging) { int face=packet.getBlockFaceId(); out.flag("dig-block").value("dig-face",face); invalid=face<0||face>5; }
                } else if (type==PacketType.Play.Client.PLUGIN_MESSAGE) {
                    // Measure before decoding channel/data to avoid an attacker-controlled allocation.
                    out.value("payload-bytes",readable);
                    if(readable>32_768) { event.setCancelled(true); cancelled.increment(); }
                } else if (type==PacketType.Play.Client.CLICK_WINDOW) {
                    if(readable>65_536) { event.setCancelled(true); cancelled.increment(); out.value("payload-bytes",readable); }
                    else { var packet=new WrapperPlayClientClickWindow(event); out.value("window-id",packet.getWindowId()); }
                }
                if(invalid) { event.setCancelled(true); cancelled.increment(); malformed.increment(); }
            } catch (RuntimeException decodeFailure) {
                // A decoder exception is defensive cancellation, never fabricated hard evidence or a kick.
                event.setCancelled(true); cancelled.increment(); malformed.increment();
                out.flag("decoder-rejected"); invalid=true;
            }
            if(state.telemetry.shouldEmit(mono,invalid) && !ingress.offer(new Envelope(state,out.build()))) dropped.increment();
        }
    }
    @Override public void close() {
        running=false;
        states.values().forEach(state->{ synchronized(state) { state.retire(); } });
        states.clear(); ingress.clear(); concealment=null;
        if(api!=null) { api.getEventManager().unregisterListener(listener); api.terminate(); api=null; }
    }
    private record Envelope(PacketSession owner, Sample sample) {}
}
