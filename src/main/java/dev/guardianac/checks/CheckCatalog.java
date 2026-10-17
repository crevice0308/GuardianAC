/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.checks;

import dev.guardianac.core.*;
import java.util.*;
import java.util.function.Predicate;
import static dev.guardianac.core.Sample.Kind.*;

/** Actual observable rules. Heuristic names describe evidence, never proof of a client mod. */
public final class CheckCatalog {
    private CheckCatalog() {}
    private static Check h(String id,String ja,String en,String family,Sample.Kind kind,int n,String why,Predicate<Sample> p) {
        return new Check(id,ja,en,family,kind,false,.75,n,4,why,p);
    }
    private static Check hard(String id,String ja,String en,String family,String why,Predicate<Sample> p) {
        return new Check(id,ja,en,family,PACKET,true,.995,4,8,why,p);
    }
    private static boolean ground(Sample s) { return s.has("ground")&&!s.has("gliding")&&!s.has("water"); }
    private static boolean air(Sample s) { return !s.has("ground")&&!s.has("gliding")&&!s.has("water")&&!s.has("climbable"); }
    public static List<Check> create() { return List.of(
        h("combat.reach","攻撃距離","Reach","COMBAT_GEOMETRY",ATTACK,8,"Eye-to-box distance exceeds interaction range plus lag margin",s->s.v("reach")>s.v("reach-limit")+1.0),
        h("combat.hitbox","視線外攻撃","Hitbox","COMBAT_GEOMETRY",ATTACK,8,"Target box not intersected by expanded look ray",s->s.v("ray-miss")>.8&&s.v("reach")>2),
        h("combat.wall","遮蔽越し攻撃","Wall attack","COMBAT_GEOMETRY",ATTACK,8,"Solid obstruction before target box",s->s.has("occluded")&&s.v("reach")>2),
        h("combat.multitarget","複数対象攻撃","Multi-target aura","COMBAT_TARGETS",ATTACK,10,"Distinct attack targets inside one server tick",s->s.v("targets-tick")>3),
        h("combat.click-rate","異常な攻撃頻度","Attack rate","COMBAT_TIMING",ATTACK,12,"Sustained attack rate above conservative limit",s->s.v("attacks-second")>30&&s.v("attack-window-ms")>=1000),
        h("combat.click-regularity","等間隔クリック","Click regularity","COMBAT_TIMING",ATTACK,12,"Large sample with submillisecond interval deviation",s->s.v("interval-samples")>=60&&s.v("interval-mean")<90&&s.v("interval-stddev")<.5),
        h("combat.target-switch-rate","攻撃対象切替頻度","Target switch rate","COMBAT_TARGETS",ATTACK,12,"Frequent server-observed switches between attack targets",s->s.v("target-switches-second")>=12&&s.v("attack-window-ms")>=1000),
        h("combat.aim-snap","急激な照準一致","Aim snap","COMBAT_ROTATION",ATTACK,12,"Large rotation immediately aligns with tiny target error",s->s.v("rotation-delta")>150&&s.v("aim-error")<.05&&s.v("reach")>2),
        h("combat.aim-lock","照準固定","Aim lock","COMBAT_ROTATION",ATTACK,16,"Moving targets with unnaturally stable angular residual",s->s.v("aim-samples")>=80&&s.v("target-speed")>.15&&s.v("aim-error-stddev")<.0001),
        h("combat.ground-claim","攻撃時接地情報矛盾","Attack ground claim","MOVEMENT_GROUND",ATTACK,10,"Fresh packet ground claim during physically unsupported attack",s->s.has("packet-ground")&&air(s)&&s.v("air-ticks")>10&&s.v("support-distance")>2),
        h("combat.autototem","トーテム交換速度","Totem replacement","INVENTORY_TIMING",USE,12,"Repeated near-instant refill after totem consumption",s->s.v("totem-refill-ms")>=0&&s.v("totem-refill-ms")<30&&s.v("totem-count")>=8),
        h("combat.inventory-attack","チェスト操作中攻撃","Inventory attack","INVENTORY_CONCURRENCY",ATTACK,10,"Attacks while a server-side container is open",s->s.has("container-open")&&s.v("attacks-second")>8),
        h("movement.fly","滞空","Fly hover","MOVEMENT_KINEMATICS",MOVE,12,"Sustained unsupported hover without known modifiers",s->air(s)&&s.v("air-ticks")>40&&Math.abs(s.v("dy"))<.005&&s.v("support-distance")>3),
        h("movement.speed","水平速度","Speed","MOVEMENT_KINEMATICS",MOVE,12,"Horizontal delta exceeds attribute-adjusted allowance",s->ground(s)&&s.v("horizontal")>s.v("speed-limit")*1.6),
        h("movement.airjump","空中再上昇","Air jump","MOVEMENT_KINEMATICS",MOVE,10,"New upward impulse after falling without support",s->air(s)&&s.v("air-ticks")>8&&s.v("previous-dy")<-.1&&s.v("dy")>.35),
        h("movement.vertical","垂直上昇速度","Vertical speed","MOVEMENT_KINEMATICS",MOVE,12,"Upward delta outside ordinary jump envelope",s->air(s)&&s.v("dy")>.85&&s.v("air-ticks")>4),
        h("movement.gravity","重力軌道","Gravity","MOVEMENT_KINEMATICS",MOVE,16,"Repeated gravity residual in clear unsupported flight",s->air(s)&&s.v("air-ticks")>10&&s.v("support-distance")>4&&s.v("gravity-residual")>.2),
        h("movement.acceleration","水平加速度","Acceleration","MOVEMENT_KINEMATICS",MOVE,12,"Unexplained ground acceleration beyond movement budget",s->ground(s)&&s.v("horizontal-acceleration")>s.v("speed-limit")*2),
        h("movement.bunnyhop","連続跳躍加速","Bunny-hop acceleration","MOVEMENT_KINEMATICS",MOVE,16,"Successive jumps exceed sprint-jump budget",s->air(s)&&s.v("jump-chain")>=8&&s.v("horizontal")>s.v("speed-limit")*2),
        h("movement.jesus","水面滞留","Water walking","MOVEMENT_FLUID",MOVE,12,"Flat sustained motion above water without solid support",s->s.has("water-below")&&!s.has("water")&&s.v("water-surface-ticks")>30&&Math.abs(s.v("dy"))<.003),
        h("movement.spider","壁際上昇","Wall climb","MOVEMENT_COLLISION",MOVE,12,"Sustained wall-adjacent climb without climbable blocks",s->air(s)&&s.has("wall-near")&&s.v("air-ticks")>12&&s.v("dy")>.2),
        h("movement.step","段差上昇","Step height","MOVEMENT_COLLISION",MOVE,10,"Ground-to-ground rise above player step allowance",s->ground(s)&&s.has("previous-ground")&&s.v("dy")>s.v("step-limit")+.6),
        h("movement.phase","固体内部移動","Phase","MOVEMENT_COLLISION",MOVE,12,"Player box penetrates full solid blocks after resync grace",s->!s.has("gliding")&&s.v("solid-penetration")>.4&&s.v("horizontal")>.2),
        h("movement.landing-claim","落下中接地情報矛盾","Falling ground claim","MOVEMENT_GROUND",MOVE,12,"Fresh packet ground claim during long physically unsupported descent",s->s.has("packet-ground")&&air(s)&&s.v("tracked-fall")>6&&s.v("support-distance")>2&&s.v("dy")<-.1),
        h("movement.ground-spoof","接地情報矛盾","Ground spoof","MOVEMENT_GROUND",MOVE,12,"Packet ground claim without nearby collision support",s->s.has("packet-ground")&&air(s)&&s.v("support-distance")>2&&s.v("air-ticks")>10),
        h("movement.noslow","使用中減速無視","No slow","MOVEMENT_USING",MOVE,14,"Sustained use-item motion exceeds slowed allowance",s->ground(s)&&s.has("using-item")&&s.v("using-ticks")>8&&s.v("horizontal")>s.v("speed-limit")*1.1),
        h("movement.sneak-speed","スニーク速度","Sneak speed","MOVEMENT_USING",MOVE,14,"Sneak motion beyond generous budget",s->ground(s)&&s.has("sneaking")&&s.v("horizontal")>s.v("speed-limit")*1.2),
        h("movement.elytra-rise","滑空上昇","Elytra rise","MOVEMENT_ELYTRA",MOVE,16,"Sustained elytra gain without boost or velocity",s->s.has("gliding")&&!s.has("firework-grace")&&s.v("dy")>1.8&&s.v("glide-ticks")>40),
        h("movement.elytra-speed","滑空速度","Elytra speed","MOVEMENT_ELYTRA",MOVE,16,"Unboosted flight exceeds conservative speed ceiling",s->s.has("gliding")&&!s.has("firework-grace")&&s.v("horizontal")>4.5&&s.v("glide-ticks")>40),
        h("movement.velocity","ノックバック応答","Velocity response","MOVEMENT_VELOCITY",VELOCITY,12,"Clear-space repeated missing expected displacement",s->s.has("clear-knockback")&&s.v("expected-displacement")>1&&s.v("observed-displacement")<.05),
        h("block.fastbreak","採掘時間","Fast break","BLOCK_BREAK_TIMING",BREAK,10,"Accumulated vanilla break progress below completion",s->s.v("break-progress")<.5&&s.v("break-progress")>=0&&!s.has("instant-break")),
        h("block.nuker","同時採掘","Nuker","BLOCK_BREAK_RATE",BREAK,10,"Many distinct blocks broken in a single tick",s->s.v("breaks-tick")>4),
        h("block.break-reach","採掘距離","Break reach","BLOCK_GEOMETRY",BREAK,8,"Break beyond attribute-adjusted interaction range",s->s.v("block-reach")>s.v("block-reach-limit")+1.5),
        h("block.bed-obstruction","遮蔽ベッド破壊","Bed obstruction","BLOCK_GEOMETRY",BREAK,10,"Bed break obscured by an intervening solid block",s->s.has("bed")&&s.has("occluded")&&s.v("block-reach")>2),
        h("block.autotool","道具切替時間","Tool switch timing","BLOCK_AUTOMATION",BREAK,16,"Repeated instant tool switch followed by break start",s->s.v("tool-switch-ms")<15&&s.v("tool-switch-ms")>=0&&s.v("tool-switch-count")>=30),
        h("block.fastplace","設置頻度","Fast place","BLOCK_PLACE_TIMING",PLACE,12,"Sustained server-observed high placement rate",s->s.v("places-second")>22),
        h("block.place-burst","同時設置","Place burst","BLOCK_PLACE_TIMING",PLACE,10,"Multiple distinct placements in one tick",s->s.v("places-tick")>5),
        h("block.place-reach","設置距離","Place reach","BLOCK_GEOMETRY",PLACE,8,"Placement exceeds server interaction allowance",s->s.v("block-reach")>s.v("block-reach-limit")+1.5),
        h("block.scaffold","橋設置と視線","Scaffold alignment","BLOCK_AUTOMATION",PLACE,16,"Rapid underfoot placements with strongly inconsistent view",s->s.has("underfoot")&&s.v("pitch")<10&&s.v("places-second")>8),
        h("block.tower","垂直積上げ","Tower timing","BLOCK_AUTOMATION",PLACE,16,"Repeated upward underfoot placement above jump budget",s->s.has("underfoot")&&s.v("tower-chain")>8&&s.v("tower-rise-per-tick")>.8),
        h("inventory.move","チェスト移動","Inventory move","INVENTORY_CONCURRENCY",MOVE,14,"Container open with sustained fast ground motion",s->ground(s)&&s.has("container-open")&&s.v("horizontal")>.35),
        h("inventory.click-rate","操作頻度","Inventory rate","INVENTORY_TIMING",INVENTORY,12,"Sustained high inventory click frequency",s->s.v("inventory-clicks-second")>45),
        h("inventory.chest-stealer","チェスト転送速度","Chest transfer timing","INVENTORY_TIMING",INVENTORY,16,"Large sequence of near-instant chest transfers",s->s.has("container-open")&&s.v("transfer-count")>=30&&s.v("transfer-mean-ms")<25),
        h("inventory.cleaner","連続アイテム廃棄","Inventory cleaner","INVENTORY_TIMING",INVENTORY,14,"High-rate item discard actions",s->s.v("drops-second")>25),
        h("inventory.autoarmor","防具装備速度","Armor equip timing","INVENTORY_TIMING",INVENTORY,16,"Repeated very fast armor equip operations",s->s.has("armor-slot")&&s.v("armor-interval-ms")<25&&s.v("armor-equip-count")>=20),
        h("inventory.closed-window","閉鎖後操作","Closed container actions","INVENTORY_SEQUENCE",PACKET,14,"Repeated non-player window click without an open container",s->s.v("window-id")>0&&!s.has("container-open")&&s.v("since-close-ms")>1000),
        h("network.timer","移動パケット時間","Timer","PACKET_TIMING",PACKET,14,"Movement packet count exceeds elapsed wall-clock allowance",s->s.v("movement-window-ms")>=5000&&s.v("movement-count")>s.v("movement-window-ms")/50*1.3),
        h("network.blink","通信停止後の集中","Blink burst","PACKET_TIMING",PACKET,16,"Movement burst after long idle interval",s->s.v("previous-gap-ms")>2000&&s.v("movement-burst")>40),
        h("network.flood","パケット洪水","Packet flood","PACKET_RATE",PACKET,10,"Sustained ingress rate over budget; packets are rate-limited",s->s.v("packets-second")>1000),
        h("network.swing-flood","腕振り洪水","Swing flood","PACKET_RATE",PACKET,10,"Swing packet rate exceeds generous allowance",s->s.v("swings-second")>150),
        h("network.use-flood","操作洪水","Interaction flood","PACKET_RATE",PACKET,10,"Interaction packets exceed conservative resource budget",s->s.v("interactions-second")>200),
        hard("packet.nonfinite-position","不正座標値","Nonfinite position","PROTOCOL_MOVEMENT","IEEE nonfinite position cannot represent a legal position",s->s.has("position-present")&&s.measurements().keySet().containsAll(Set.of("x","y","z"))&&(!Double.isFinite(s.v("x"))||!Double.isFinite(s.v("y"))||!Double.isFinite(s.v("z")))),
        hard("packet.nonfinite-rotation","不正視線値","Nonfinite rotation","PROTOCOL_MOVEMENT","Nonfinite yaw/pitch is invalid",s->s.has("rotation-present")&&s.measurements().keySet().containsAll(Set.of("yaw","pitch"))&&(!Double.isFinite(s.v("yaw"))||!Double.isFinite(s.v("pitch")))),
        hard("packet.pitch","視線上下角範囲","Pitch bounds","PROTOCOL_MOVEMENT","Finite pitch outside legal -90..90 interval",s->s.has("rotation-present")&&Double.isFinite(s.v("pitch"))&&Math.abs(s.v("pitch"))>90.01),
        hard("packet.held-slot","ホットバースロット","Held slot bounds","PROTOCOL_INVENTORY","Held-item slot outside integer 0..8",s->s.has("held-slot-present")&&(s.v("held-slot")<0||s.v("held-slot")>8)),
        hard("packet.dig-face","採掘面の範囲","Dig face bounds","PROTOCOL_DIG","Block face outside protocol 0..5 for start/finish digging",s->s.has("dig-block")&&(s.v("dig-face")<0||s.v("dig-face")>5)),
        h("packet.world-boundary","座標上限","World coordinate bounds","PROTOCOL_MOVEMENT",PACKET,12,"Position far outside hard world coordinate range",s->s.has("position-present")&&(Math.abs(s.v("x"))>30_000_001||Math.abs(s.v("z"))>30_000_001||Math.abs(s.v("y"))>20_000_000)),
        h("packet.payload-size","カスタムデータ量","Payload size","PROTOCOL_PAYLOAD",PACKET,8,"Custom payload exceeds plugin resource budget",s->s.v("payload-bytes")>32_768),
        h("xray.rare-rate","希少鉱石発見率","Rare ore ratio","MINING_STATISTICS",MINING,16,"High rare-ore proportion in a sufficiently large sample",s->s.v("mined-blocks")>=500&&s.v("rare-ratio")>.12),
        h("xray.hidden-path","未露出鉱石経路","Hidden ore path","MINING_STATISTICS",MINING,16,"Frequent direction changes leading to unexposed ore clusters",s->s.v("hidden-veins")>=12&&s.v("targeted-turn-ratio")>.85),
        h("xray.vein-efficiency","鉱脈探索効率","Vein efficiency","MINING_STATISTICS",MINING,16,"Repeated isolated rare veins with little connecting excavation",s->s.v("distinct-rare-veins")>=20&&s.v("blocks-per-vein")<5)
    ); }
}
