# Detection module inventory

Each ID is an independently configurable real predicate. All 61 have synthetic positive, legitimate-boundary, missing-data, exemption and wrong-kind tests. This does not measure real-world detection accuracy.

| ID | 日本語 | English | Family | Automatic-ban eligible? |
| --- | --- | --- | --- | --- |
| combat.reach | 攻撃距離 | Reach | COMBAT_GEOMETRY | No, review only |
| combat.hitbox | 視線外攻撃 | Hitbox | COMBAT_GEOMETRY | No, review only |
| combat.wall | 遮蔽越し攻撃 | Wall attack | COMBAT_GEOMETRY | No, review only |
| combat.multitarget | 複数対象攻撃 | Multi-target aura | COMBAT_TARGETS | No, review only |
| combat.click-rate | 異常な攻撃頻度 | Attack rate | COMBAT_TIMING | No, review only |
| combat.click-regularity | 等間隔クリック | Click regularity | COMBAT_TIMING | No, review only |
| combat.target-switch-rate | 攻撃対象切替頻度 | Target switch rate | COMBAT_TARGETS | No, review only |
| combat.aim-snap | 急激な照準一致 | Aim snap | COMBAT_ROTATION | No, review only |
| combat.aim-lock | 照準固定 | Aim lock | COMBAT_ROTATION | No, review only |
| combat.ground-claim | 攻撃時接地情報矛盾 | Attack ground claim | MOVEMENT_GROUND | No, review only |
| combat.autototem | トーテム交換速度 | Totem replacement | INVENTORY_TIMING | No, review only |
| combat.inventory-attack | チェスト操作中攻撃 | Inventory attack | INVENTORY_CONCURRENCY | No, review only |
| movement.fly | 滞空 | Fly hover | MOVEMENT_KINEMATICS | No, review only |
| movement.speed | 水平速度 | Speed | MOVEMENT_KINEMATICS | No, review only |
| movement.airjump | 空中再上昇 | Air jump | MOVEMENT_KINEMATICS | No, review only |
| movement.vertical | 垂直上昇速度 | Vertical speed | MOVEMENT_KINEMATICS | No, review only |
| movement.gravity | 重力軌道 | Gravity | MOVEMENT_KINEMATICS | No, review only |
| movement.acceleration | 水平加速度 | Acceleration | MOVEMENT_KINEMATICS | No, review only |
| movement.bunnyhop | 連続跳躍加速 | Bunny-hop acceleration | MOVEMENT_KINEMATICS | No, review only |
| movement.jesus | 水面滞留 | Water walking | MOVEMENT_FLUID | No, review only |
| movement.spider | 壁際上昇 | Wall climb | MOVEMENT_COLLISION | No, review only |
| movement.step | 段差上昇 | Step height | MOVEMENT_COLLISION | No, review only |
| movement.phase | 固体内部移動 | Phase | MOVEMENT_COLLISION | No, review only |
| movement.landing-claim | 落下中接地情報矛盾 | Falling ground claim | MOVEMENT_GROUND | No, review only |
| movement.ground-spoof | 接地情報矛盾 | Ground spoof | MOVEMENT_GROUND | No, review only |
| movement.noslow | 使用中減速無視 | No slow | MOVEMENT_USING | No, review only |
| movement.sneak-speed | スニーク速度 | Sneak speed | MOVEMENT_USING | No, review only |
| movement.elytra-rise | 滑空上昇 | Elytra rise | MOVEMENT_ELYTRA | No, review only |
| movement.elytra-speed | 滑空速度 | Elytra speed | MOVEMENT_ELYTRA | No, review only |
| movement.velocity | ノックバック応答 | Velocity response | MOVEMENT_VELOCITY | No, review only |
| block.fastbreak | 採掘時間 | Fast break | BLOCK_BREAK_TIMING | No, review only |
| block.nuker | 同時採掘 | Nuker | BLOCK_BREAK_RATE | No, review only |
| block.break-reach | 採掘距離 | Break reach | BLOCK_GEOMETRY | No, review only |
| block.bed-obstruction | 遮蔽ベッド破壊 | Bed obstruction | BLOCK_GEOMETRY | No, review only |
| block.autotool | 道具切替時間 | Tool switch timing | BLOCK_AUTOMATION | No, review only |
| block.fastplace | 設置頻度 | Fast place | BLOCK_PLACE_TIMING | No, review only |
| block.place-burst | 同時設置 | Place burst | BLOCK_PLACE_TIMING | No, review only |
| block.place-reach | 設置距離 | Place reach | BLOCK_GEOMETRY | No, review only |
| block.scaffold | 橋設置と視線 | Scaffold alignment | BLOCK_AUTOMATION | No, review only |
| block.tower | 垂直積上げ | Tower timing | BLOCK_AUTOMATION | No, review only |
| inventory.move | チェスト移動 | Inventory move | INVENTORY_CONCURRENCY | No, review only |
| inventory.click-rate | 操作頻度 | Inventory rate | INVENTORY_TIMING | No, review only |
| inventory.chest-stealer | チェスト転送速度 | Chest transfer timing | INVENTORY_TIMING | No, review only |
| inventory.cleaner | 連続アイテム廃棄 | Inventory cleaner | INVENTORY_TIMING | No, review only |
| inventory.autoarmor | 防具装備速度 | Armor equip timing | INVENTORY_TIMING | No, review only |
| inventory.closed-window | 閉鎖後操作 | Closed container actions | INVENTORY_SEQUENCE | No, review only |
| network.timer | 移動パケット時間 | Timer | PACKET_TIMING | No, review only |
| network.blink | 通信停止後の集中 | Blink burst | PACKET_TIMING | No, review only |
| network.flood | パケット洪水 | Packet flood | PACKET_RATE | No, review only |
| network.swing-flood | 腕振り洪水 | Swing flood | PACKET_RATE | No, review only |
| network.use-flood | 操作洪水 | Interaction flood | PACKET_RATE | No, review only |
| packet.nonfinite-position | 不正座標値 | Nonfinite position | PROTOCOL_MOVEMENT | Only with all global evidence gates |
| packet.nonfinite-rotation | 不正視線値 | Nonfinite rotation | PROTOCOL_MOVEMENT | Only with all global evidence gates |
| packet.pitch | 視線上下角範囲 | Pitch bounds | PROTOCOL_MOVEMENT | Only with all global evidence gates |
| packet.held-slot | ホットバースロット | Held slot bounds | PROTOCOL_INVENTORY | Only with all global evidence gates |
| packet.dig-face | 採掘面の範囲 | Dig face bounds | PROTOCOL_DIG | Only with all global evidence gates |
| packet.world-boundary | 座標上限 | World coordinate bounds | PROTOCOL_MOVEMENT | No, review only |
| packet.payload-size | カスタムデータ量 | Payload size | PROTOCOL_PAYLOAD | No, review only |
| xray.rare-rate | 希少鉱石発見率 | Rare ore ratio | MINING_STATISTICS | No, review only |
| xray.hidden-path | 未露出鉱石経路 | Hidden ore path | MINING_STATISTICS | No, review only |
| xray.vein-efficiency | 鉱脈探索効率 | Vein efficiency | MINING_STATISTICS | No, review only |

## Confidence interpretation

The displayed confidence values are conservative policy scores, not measured
probabilities of guilt or advertised detection accuracy. Family grouping prevents
known correlated rules from counting as independent BAN evidence, but does not
establish statistical independence. The representative human false-positive and
false-negative evaluation remains a formal-release gate.

表示される確信度は判定ポリシーの強さを表す値で、実測の不正率・正解率ではありません。
独立familyは既知の重複加算を避ける区分で、統計的独立性や99%以上の精度を保証しません。
