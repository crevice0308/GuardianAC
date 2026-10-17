package test.guardian;
import dev.guardianac.bukkit.EventTelemetry;
import dev.guardianac.core.Sample;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.util.ArrayList;
import java.util.List;
/** Uses a real joined loopback Bukkit actor; a separate adapter never changes the live plugin adapter. */
final class TelemetryLifecycleFixture {
 static void run(GuardianFixture f) {
  Player actor=Bukkit.getPlayerExact("GacLifeFixture");
  f.pass("telemetry_lifecycle_real_actor_online",actor!=null&&actor.isOnline());
  if(actor==null)return;
  List<Sample> samples=new ArrayList<>();EventTelemetry adapter=new EventTelemetry(f,samples::add,()->100L,()->20d);
  try {
   f.pass("telemetry_new_adapter_has_no_sessions",adapter.trackedPlayers()==0);
   adapter.start();f.pass("telemetry_start_admits_existing_online",adapter.trackedPlayers()==1);
   adapter.heldItem(new PlayerItemHeldEvent(actor,0,1));f.pass("telemetry_existing_actor_observation_is_admitted",adapter.trackedPlayers()==1);
   adapter.reset(actor.getUniqueId());adapter.heldItem(new PlayerItemHeldEvent(actor,1,2));
   f.pass("telemetry_reset_retains_admission",adapter.trackedPlayers()==1);
   adapter.close();f.pass("telemetry_close_clears_all_sessions",adapter.trackedPlayers()==0);
   adapter.closeInventory(new InventoryCloseEvent(actor.getOpenInventory()));f.pass("telemetry_late_close_after_shutdown_no_resurrection",adapter.trackedPlayers()==0);
   adapter.heldItem(new PlayerItemHeldEvent(actor,2,3));f.pass("telemetry_late_held_after_shutdown_no_resurrection",adapter.trackedPlayers()==0);
   adapter.teleport(new PlayerTeleportEvent(actor,actor.getLocation(),actor.getLocation()));f.pass("telemetry_late_teleport_after_shutdown_no_resurrection",adapter.trackedPlayers()==0);
   adapter.reset(actor.getUniqueId());f.pass("telemetry_reset_after_shutdown_no_resurrection",adapter.trackedPlayers()==0);
   f.pass("telemetry_closed_adapter_emits_no_samples",samples.isEmpty());
   adapter.start();f.pass("telemetry_restart_readmits_existing_online",adapter.trackedPlayers()==1);
   adapter.heldItem(new PlayerItemHeldEvent(actor,0,1));adapter.forget(actor);
   f.pass("telemetry_forget_closes_identity_session",adapter.trackedPlayers()==0);
   adapter.closeInventory(new InventoryCloseEvent(actor.getOpenInventory()));adapter.heldItem(new PlayerItemHeldEvent(actor,1,2));
   adapter.teleport(new PlayerTeleportEvent(actor,actor.getLocation(),actor.getLocation()));
   f.pass("telemetry_late_events_after_forget_no_resurrection",adapter.trackedPlayers()==0);
  } finally {adapter.close();f.pass("telemetry_fixture_final_cleanup_empty",adapter.trackedPlayers()==0);}
 }
}
