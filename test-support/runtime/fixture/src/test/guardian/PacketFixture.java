package test.guardian;
import dev.guardianac.bukkit.*;
import dev.guardianac.packet.*;
import dev.guardianac.xray.*;
import dev.guardianac.libs.pe.api.packetevents.PacketEvents;
import dev.guardianac.libs.pe.api.packetevents.event.*;
import dev.guardianac.libs.pe.api.packetevents.manager.server.ServerVersion;
import dev.guardianac.libs.pe.api.packetevents.protocol.ConnectionState;
import dev.guardianac.libs.pe.api.packetevents.protocol.packettype.*;
import dev.guardianac.libs.pe.api.packetevents.protocol.player.*;
import dev.guardianac.libs.pe.api.packetevents.protocol.world.chunk.*;
import dev.guardianac.libs.pe.api.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import dev.guardianac.libs.pe.api.packetevents.protocol.world.states.type.StateTypes;
import dev.guardianac.libs.pe.api.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.lang.reflect.*;
import java.util.*;

public class PacketFixture {
 static class Send extends PacketSendEvent {Send(User u,Object buffer){super(PacketType.Play.Server.CHUNK_DATA.getId(u.getClientVersion()),PacketType.Play.Server.CHUNK_DATA,PacketEvents.getAPI().getServerManager().getVersion(),u.getChannel(),u,null,buffer);}}
 static class Receive extends PacketReceiveEvent {Receive(User u,PacketTypeCommon type,Object buffer){super(type.getId(PacketEvents.getAPI().getServerManager().getVersion().toClientVersion()),type,PacketEvents.getAPI().getServerManager().getVersion(),u.getChannel(),u,null,buffer);}}
 static void run(GuardianFixture f,GuardianPlugin g)throws Exception{
  ServerVersion sv=PacketEvents.getAPI().getServerManager().getVersion();ClientVersion cv=sv.toClientVersion();UUID id=UUID.fromString("00000000-0000-4000-8000-000000000080"),world=Bukkit.getWorlds().getFirst().getUID();
  f.pass("packet_events_runtime_version_26_3",sv.toString().contains("26_3"));
  EmbeddedChannel channel=new EmbeddedChannel();User u=new User(channel,ConnectionState.PLAY,cv,new UserProfile(id,"GACPacketFixture"));
  int stone=StateTypes.STONE.createBlockState(cv).getGlobalId(),ore=StateTypes.DIAMOND_ORE.createBlockState(cv).getGlobalId();
  BaseChunk[] sections=new BaseChunk[24];for(int s=0;s<24;s++){Chunk_v1_18 c=new Chunk_v1_18(cv);for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)c.set(cv,x,y,z,stone);sections[s]=c;}
  sections[2].set(cv,8,8,8,ore); // y=-24, fully enclosed and distant from fixture viewer.
  Map<HeightmapType,long[]> heightmaps=Map.of(HeightmapType.WORLD_SURFACE,new long[]{1,2,3,4});
  Column col=new Column(0,0,true,sections,new TileEntity[0],heightmaps);
  LightData light=new LightData(false,new BitSet(),new BitSet(),new BitSet(),new BitSet(),0,0,new byte[0][],new byte[0][]);
  WrapperPlayServerChunkData input=new WrapperPlayServerChunkData(col,light);input.setClientVersion(cv);input.setServerVersion(sv);
  ChunkConcealment cc=g.xray().concealment();cc.configure(XraySettings.defaults().withConcealCaves(false));
  cc.publish(id,new ChunkConcealment.Viewer(world,-64,320,100,100,100,System.currentTimeMillis(),"NORMAL",NativeAntiXray.Mode.DISABLED,false));
  ByteBuf buf=Unpooled.buffer();Send e=new Send(u,buf);e.setLastUsedWrapper(input);long before=cc.concealedChunks();cc.onPacketSend(e);
  f.pass("chunk_rewrite_requested",e.needsReEncode()&&cc.concealedChunks()==before+1);
  WrapperPlayServerChunkData output=(WrapperPlayServerChunkData)e.getLastUsedWrapper();
  f.pass("chunk_ore_concealed",output.getColumn().getChunks()[2].getBlockId(8,8,8)==StateTypes.DEEPSLATE.createBlockState(cv).getGlobalId());
  f.pass("chunk_original_not_mutated",sections[2].getBlockId(8,8,8)==ore);
  f.pass("chunk_heightmaps_preserved",Arrays.equals(output.getColumn().getHeightmaps().get(HeightmapType.WORLD_SURFACE),new long[]{1,2,3,4}));
  f.pass("chunk_ledger_inactive_before_send",cc.cache().takeAll(id,world,100).isEmpty());
  output.buffer=buf;output.write();f.pass("chunk_reencoded_nonempty",buf.readableBytes()>100);
  Send decode=new Send(u,buf);WrapperPlayServerChunkData roundtrip=new WrapperPlayServerChunkData(decode);
  f.pass("chunk_wire_roundtrip_ore_hidden",roundtrip.getColumn().getChunks()[2].getBlockId(8,8,8)==StateTypes.DEEPSLATE.createBlockState(cv).getGlobalId());
  f.pass("chunk_wire_roundtrip_biomes",((Chunk_v1_18)roundtrip.getColumn().getChunks()[2]).getBiomeData().get(0,0,0)==((Chunk_v1_18)sections[2]).getBiomeData().get(0,0,0));
  e.getTasksAfterSend().forEach(Runnable::run);f.pass("chunk_ledger_active_after_send",cc.cache().takeAll(id,world,100).contains(new BlockPosition(8,-24,8)));
  cc.publish(id,new ChunkConcealment.Viewer(world,-64,320,100,100,100,System.currentTimeMillis(),"NORMAL",NativeAntiXray.Mode.ENABLED,false));
  Send nativeEvent=new Send(u,Unpooled.buffer());nativeEvent.setLastUsedWrapper(input);cc.onPacketSend(nativeEvent);f.pass("chunk_native_antixray_skips_rewrite",!nativeEvent.needsReEncode());((ByteBuf)nativeEvent.getByteBuf()).release();
  cc.publish(id,new ChunkConcealment.Viewer(world,-64,320,100,100,100,System.currentTimeMillis(),"NORMAL",NativeAntiXray.Mode.UNKNOWN,false));
  Send unknown=new Send(u,Unpooled.buffer());unknown.setLastUsedWrapper(input);cc.onPacketSend(unknown);f.pass("chunk_unknown_native_state_skips",!unknown.needsReEncode());((ByteBuf)unknown.getByteBuf()).release();
  cc.forget(id);buf.release();
  Field pf=GuardianPlugin.class.getDeclaredField("packets");pf.setAccessible(true);PacketAdapter pa=(PacketAdapter)pf.get(g);
  GuardianFixture.FakePlayer actor=actor(id);
  bind(pa,actor.proxy,u,world);
  Method receive=PacketAdapter.class.getDeclaredMethod("receive",PacketReceiveEvent.class);receive.setAccessible(true);
  Method exact=PacketAdapter.class.getMethod("exactProtocol",UUID.class);
  Field telemetryField=GuardianPlugin.class.getDeclaredField("telemetry");telemetryField.setAccessible(true);Object telemetry=telemetryField.get(g);
  Field translatedField=EventTelemetry.class.getDeclaredField("translated");translatedField.setAccessible(true);
  @SuppressWarnings("unchecked") java.util.function.Predicate<UUID> translated=(java.util.function.Predicate<UUID>)translatedField.get(telemetry);
  f.pass("packet_unobserved_protocol_ineligible",!(boolean)exact.invoke(pa,id)&&translated.test(id));
  long cancelled=pa.cancelledPackets(),malformed=pa.malformedPackets();
  ByteBuf valid=Unpooled.buffer().writeShort(3);Receive validEvent=new Receive(u,PacketType.Play.Client.HELD_ITEM_CHANGE,valid);receive.invoke(pa,validEvent);f.pass("packet_valid_held_slot_allowed",!validEvent.isCancelled());valid.release();f.pass("packet_native_protocol_eligible",(boolean)exact.invoke(pa,id)&&!translated.test(id));
  ByteBuf invalid=Unpooled.buffer().writeShort(15);Receive invalidEvent=new Receive(u,PacketType.Play.Client.HELD_ITEM_CHANGE,invalid);receive.invoke(pa,invalidEvent);f.pass("packet_invalid_held_slot_cancelled",invalidEvent.isCancelled()&&pa.cancelledPackets()>cancelled&&pa.malformedPackets()>malformed);invalid.release();
  ByteBuf shortbuf=Unpooled.buffer();Receive shortEvent=new Receive(u,PacketType.Play.Client.HELD_ITEM_CHANGE,shortbuf);receive.invoke(pa,shortEvent);f.pass("packet_truncated_decode_cancelled",shortEvent.isCancelled());shortbuf.release();
  f.pass("packet_ingress_drains_on_main",pa.drain(100)>0);
  for(ClientVersion unsupported:List.of(ClientVersion.V_1_21_11,ClientVersion.UNKNOWN)){
   String label=unsupported==ClientVersion.UNKNOWN?"unknown":"non_target";
   EmbeddedChannel foreignChannel=new EmbeddedChannel();
   User foreign=new User(foreignChannel,ConnectionState.PLAY,unsupported,new UserProfile(id,"GACProtocolFixture"));
   GuardianFixture.FakePlayer foreignActor=actor(id);bind(pa,foreignActor.proxy,foreign,world);
   ByteBuf payload=Unpooled.buffer().writeShort(15);Receive candidate=new Receive(foreign,PacketType.Play.Client.HELD_ITEM_CHANGE,payload);
   long oldCancelled=pa.cancelledPackets(),oldMalformed=pa.malformedPackets();int oldQueued=pa.queuedSamples();receive.invoke(pa,candidate);
   f.pass("packet_"+label+"_not_decoded",payload.readerIndex()==0);
   f.pass("packet_"+label+"_not_cancelled",!candidate.isCancelled()&&pa.cancelledPackets()==oldCancelled);
   f.pass("packet_"+label+"_not_malformed",pa.malformedPackets()==oldMalformed);
   f.pass("packet_"+label+"_no_evidence_emitted",pa.queuedSamples()==oldQueued);
   f.pass("packet_"+label+"_event_heuristics_ineligible",!(boolean)exact.invoke(pa,id)&&translated.test(id));payload.release();
   pa.forget(foreignActor.proxy);foreignChannel.finishAndReleaseAll();
  }
  sessionBoundaries(f,pa,receive,cc,actor,u,world,cv);
  pa.forget(actor.proxy);f.pass("packet_disconnect_context_forgotten",pa.context(id).isEmpty());
  f.pass("packet_missing_protocol_ineligible",!(boolean)exact.invoke(pa,id)&&translated.test(id));channel.finishAndReleaseAll();
 }

 static GuardianFixture.FakePlayer actor(UUID id){GuardianFixture.FakePlayer actor=new GuardianFixture.FakePlayer(Set.of());actor.id=id;return actor;}
 static void bind(PacketAdapter pa,Player player,User user,UUID world)throws Exception{
  Method bind=PacketAdapter.class.getDeclaredMethod("bind",Player.class,User.class,PacketContext.class);bind.setAccessible(true);
  bind.invoke(pa,player,user,new PacketContext(world,System.currentTimeMillis(),1,20,0,Set.of(),false,10000,-64,320));
 }
 static Object state(PacketAdapter pa,UUID id)throws Exception{
  Field states=PacketAdapter.class.getDeclaredField("states");states.setAccessible(true);return ((Map<?,?>)states.get(pa)).get(id);
 }
 /** Deliver the real embedded PacketEvents disconnect event on a separate thread. */
 static void disconnect(PacketAdapter pa,User user)throws Exception{
  Field field=PacketAdapter.class.getDeclaredField("listener");field.setAccessible(true);PacketListenerAbstract listener=(PacketListenerAbstract)field.get(pa);
  java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
  Thread thread=new Thread(()->{try{listener.onUserDisconnect(new UserDisconnectEvent(user));}catch(Throwable t){failure.set(t);}},"GACPacketDisconnectFixture");
  thread.setDaemon(true);thread.start();thread.join(2000);
  if(thread.isAlive())throw new IllegalStateException("Packet disconnect fixture did not finish within 2 seconds");
  if(failure.get()!=null)throw new IllegalStateException("Packet disconnect fixture failed",failure.get());
 }
 static void sessionBoundaries(GuardianFixture f,PacketAdapter pa,Method receive,ChunkConcealment cc,GuardianFixture.FakePlayer oldActor,User oldUser,UUID world,ClientVersion cv)throws Exception{
  UUID id=oldActor.id;bind(pa,oldActor.proxy,oldUser,world);pa.drain(8192);Object oldState=state(pa,id);
  ByteBuf queued=Unpooled.buffer().writeShort(15);Receive queuedEvent=new Receive(oldUser,PacketType.Play.Client.HELD_ITEM_CHANGE,queued);
  receive.invoke(pa,queuedEvent);queued.release();
  f.pass("packet_session_old_sample_enqueued",queuedEvent.isCancelled()&&pa.queuedSamples()==1);
  GuardianFixture.FakePlayer newActor=actor(id);EmbeddedChannel newChannel=new EmbeddedChannel();
  User newUser=new User(newChannel,ConnectionState.PLAY,cv,new UserProfile(id,"GACReplacementFixture"));
  try{
   bind(pa,newActor.proxy,newUser,world);Object newState=state(pa,id);
   f.pass("packet_session_same_uuid_world_replaces_identity",newState!=null&&newState!=oldState&&pa.context(id).orElseThrow().worldId().equals(world));
   f.pass("packet_session_replacement_protocol_unobserved",!pa.exactProtocol(id));
   long oldDropped=pa.droppedSamples();int drained=pa.drain(8192);
   f.pass("packet_session_old_owner_queue_dropped",drained==1&&pa.queuedSamples()==0&&pa.droppedSamples()==oldDropped+1);
   ByteBuf valid=Unpooled.buffer().writeShort(3);Receive nativeEvent=new Receive(newUser,PacketType.Play.Client.HELD_ITEM_CHANGE,valid);receive.invoke(pa,nativeEvent);valid.release();pa.drain(8192);
   f.pass("packet_session_new_owner_protocol_observed",!nativeEvent.isCancelled()&&pa.exactProtocol(id));
   ByteBuf stale=Unpooled.buffer().writeShort(15);Receive staleEvent=new Receive(oldUser,PacketType.Play.Client.HELD_ITEM_CHANGE,stale);
   long cancelled=pa.cancelledPackets(),malformed=pa.malformedPackets();int before=pa.queuedSamples();receive.invoke(pa,staleEvent);
   f.pass("packet_session_old_receive_unread",stale.readerIndex()==0);
   f.pass("packet_session_old_receive_not_cancelled",!staleEvent.isCancelled()&&pa.cancelledPackets()==cancelled);
   f.pass("packet_session_old_receive_not_malformed",pa.malformedPackets()==malformed);
   f.pass("packet_session_old_receive_no_evidence",pa.queuedSamples()==before);
   f.pass("packet_session_old_receive_preserves_protocol",pa.exactProtocol(id));stale.release();
   pa.forget(oldActor.proxy);f.pass("packet_session_old_player_forget_preserves_new_owner",state(pa,id)==newState&&pa.context(id).isPresent());
   cc.publish(id,new ChunkConcealment.Viewer(world,-64,320,100,100,100,System.currentTimeMillis(),"NORMAL",NativeAntiXray.Mode.DISABLED,false));
   int ledgerBefore=cc.cache().size();BlockPosition hidden=new BlockPosition(8,-24,8);
   f.pass("packet_session_xray_ledger_seeded",cc.cache().replace(id,world,hidden.chunkKey(),Set.of(hidden),100,1000)&&cc.cache().size()==ledgerBefore+1);
   ((EmbeddedChannel)oldUser.getChannel()).close();disconnect(pa,oldUser);
   f.pass("packet_session_old_disconnect_preserves_new_owner",state(pa,id)==newState&&pa.context(id).isPresent());
   f.pass("packet_session_old_disconnect_preserves_protocol",pa.exactProtocol(id));
   f.pass("packet_session_old_disconnect_preserves_xray_ledger",cc.cache().size()==ledgerBefore+1);
   newChannel.close();disconnect(pa,newUser);
   f.pass("packet_session_current_disconnect_defers_state_cleanup",state(pa,id)==newState);
   f.pass("packet_session_current_disconnect_ineligible",!pa.exactProtocol(id));
   f.pass("packet_session_current_disconnect_hides_context",pa.context(id).isEmpty());
   f.pass("packet_session_current_disconnect_defers_xray_cleanup",cc.cache().size()==ledgerBefore+1);
   bind(pa,newActor.proxy,newUser,world);
   f.pass("packet_session_retired_user_not_readmitted",state(pa,id)==newState&&!pa.exactProtocol(id)&&pa.context(id).isEmpty());
   ByteBuf retired=Unpooled.buffer().writeShort(15);Receive retiredEvent=new Receive(newUser,PacketType.Play.Client.HELD_ITEM_CHANGE,retired);receive.invoke(pa,retiredEvent);
   f.pass("packet_session_retired_receive_ignored",retired.readerIndex()==0&&!retiredEvent.isCancelled()&&pa.queuedSamples()==before);retired.release();
   pa.drain(8192);
   f.pass("packet_session_current_disconnect_main_cleanup",state(pa,id)==null&&pa.context(id).isEmpty());
   f.pass("packet_session_current_disconnect_xray_cleanup",cc.cache().size()==ledgerBefore);
   bind(pa,newActor.proxy,newUser,world);
   f.pass("packet_session_closed_user_not_readmitted_after_reap",state(pa,id)==null&&pa.context(id).isEmpty());
  }finally{pa.forget(newActor.proxy);cc.forget(id);newChannel.finishAndReleaseAll();}
 }
}
