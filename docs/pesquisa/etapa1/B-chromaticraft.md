# 1.B: cap on the ChromatiCraft area-shift ability (the 30 s stall, D-21)

Status: design only (no mod code written). Verdict: **implement** (low risk, small mixin, closes a stall class
and a lag exploit). Average TPS gain is zero; the gain is in the worst case.

Working files (all under the scratchpad, nothing in the repo):
- decompiled sources: `etapa1/decomp/B-chromaticraft/cc/` (ChromatiCraft V33a), `etapa1/decomp/B-chromaticraft/dapi/` (DragonAPI V33b-ForbiddenFix1)
- `javap -c` dump of `AbilityCalls`: `etapa1/decomp/B-chromaticraft/AbilityCalls.javap.txt`
- harnesses: `etapa1/B-harness/src/ShiftDataHarness.java`, `etapa1/B-harness/src/CostFormula.java`, stubs in `etapa1/B-harness/stub/`
- jar scanner: `etapa1/B-tools/scanjars.py`

Terms: "box" = the cuboid the player marks with two right-clicks; "volume" = number of block positions the
code visits = (dx+1)(dy+1)(dz+1); "mixin" = a small patch applied to another mod's class when it loads.

---

## 1. What the profile says

The 602 s production profile does **not** contain the ability at all. Every command below was run from
`/home/user/Hoshiestar` with `P=<scratchpad>/anexos/spark/wIBxG0D4y9.json`:

| Command | Result (share of "Server thread", total 598.9 s) |
|---|---|
| `python3 -I tools/spark_report.py $P busca shiftArea` | 0 occurrences, 0.00% |
| `python3 -I tools/spark_report.py $P busca AbilityCalls` | 0 occurrences, 0.00% |
| `python3 -I tools/spark_report.py $P busca playerCanBreakAt` | 0 occurrences, 0.00% |
| `python3 -I tools/spark_report.py $P busca BlockBox` | 0 occurrences, 0.00% |
| `python3 -I tools/spark_report.py $P busca clickBlock_PlayerInteractEvent` | 1 node, 4 ms, 0.00% (`ASMEventHandler_1399_AbilityHelper_clickBlock_PlayerInteractEvent.invoke`) |
| `python3 -I tools/spark_report.py $P busca FilledBlockArray` | 0.1 s, 0.02% (other callers: `setBlock` 64 ms, `matchInWorld`; not the shift) |
| `python3 -I tools/spark_report.py $P busca C08PacketPlayerBlockPlacement` | 1.2 s, 0.20% |
| `python3 -I tools/spark_report.py $P arvore --raiz C08PacketPlayerBlockPlacement.func_148833_a --prof 9 --min 0.01` | the 0.20% is Avaritia `BlockMatterClusterOpener` GUI (0.09%), Bukkit/Forge interact events (0.02% each), AE2 wireless terminal; no ChromatiCraft shift node |
| `python3 -I tools/spark_report.py $P busca NetworkSystem.func_151269_c` | 61.8 s, 10.31% (the whole network phase, for context) |
| `python3 -I tools/spark_report.py $P busca ChromatiCraft` | 7.8 s, 1.30% (pylons, bees, etc.; nothing from the shift) |
| `python3 -I tools/spark_report.py $P resumo` | MSPT last5m max 5219 ms in one window. Not caused by the shift (0 samples above) |

So the sub-tree is empty in the profile: **0.00% of server-thread time**. The evidence for the problem is the
stall dump (`anexos/stall/stall.txt`, lines 5-27), a single snapshot taken after at least 30000 ms without a tick:

```
"Server thread" RUNNABLE
  HashMap$TreeNode.putTreeVal(HashMap.java:2144)
  HashMap.putVal / HashMap.put
  Reika.DragonAPI...FilledBlockArray.setBlock(FilledBlockArray.java:115)
  Reika.DragonAPI...FilledBlockArray.setBlock(FilledBlockArray.java:88)
  Reika.ChromatiCraft.Auxiliary.Ability.AbilityCalls.shiftArea(AbilityCalls.java:553)
  ...AbilityHelper.playerMakeBox(AbilityHelper.java:320)
  ...AbilityHelper.addPlayerClick(AbilityHelper.java:304)
  ...AbilityHelper.clickBlock(AbilityHelper.java:327)
  ASMEventHandler_1394_AbilityHelper_clickBlock_PlayerInteractEvent.invoke
  EventBus.post / ForgeEventFactory.onPlayerBukkitInteract(:318)
  ItemInWorldManager.func_73078_a(:425) / NetHandlerPlayServer.func_147346_a(:870)
  C08PacketPlayerBlockPlacement.func_148833_a / NetworkManager.func_74428_b(:258)
  NetworkSystem.func_151269_c(:181)   - locked Collections$SynchronizedRandomAccessList@3013fac6
  MinecraftServer.func_71190_q(:1023) ...
```

`AbilityCalls.java:553` is the `moved.setBlock(...)` call inside the scan loop (LineNumberTable of
`shiftArea`: line 553 starts at bytecode offset 209; the `invokevirtual FilledBlockArray.setBlock` is offset 221).
The dump does not say which player, where, or the box size.

## 2. The code

All signatures below were taken from `javap -s` / `javap -c` on `mods/ChromatiCraft 1.7.10 V33a.jar` and
`mods/DragonAPI 1.7.10 V33b-ForbiddenFix1.jar`; excerpts are from the CFR decompile (paths above).

### 2.1 Entry points (who calls `shiftArea`)

Scanning every jar in `mods/` and `plugins/` for the strings `shiftArea`, `addPlayerClick`, `playerMakeBox`,
`AbilityCalls` (`python3 -I etapa1/B-tools/scanjars.py ...`) finds them only inside ChromatiCraft itself
(`AbilityCalls.class`, `AbilityHelper.class`). The only path is:

1. **Arming (custom packet).** Client sends `ChromaData` packet `ABILITY` with ints `{abilityId, data}`
   (`Chromabilities.triggerAbility`, client branch). Server handler `ChromatiPackets` (decompiled line 437):
   ```java
   case ABILITY: {
       AbilityAPI.Ability c = Chromabilities.getAbilityByInt(data[0]);
       if (!Chromabilities.playerHasAbility(ep, c)) break;
       Chromabilities.triggerAbility(ep, c, data[1]);     // data[1] is NOT range-checked
   ```
   `Chromabilities.triggerAbility(EntityPlayer, AbilityAPI$Ability, int)` descriptor
   `(Lnet/minecraft/entity/player/EntityPlayer;LReika/ChromatiCraft/API/AbilityAPI$Ability;I)V` toggles the
   ability and calls `trigger(EntityPlayer,int)` `(Lnet/minecraft/entity/player/EntityPlayer;I)Z`, whose SHIFT case is:
   ```java
   case SHIFT:
       if (this.enabledOn(ep)) {
           AbilityHelper.instance.startDrawingBoxes(ep);
           AbilityHelper.instance.shifts.put(ep, new ScaledDirection(
               ReikaEntityHelper.getDirectionFromEntityLook(ep, true), data));   // dist = raw packet int
       } else { stopDrawingBoxes(ep); shifts.remove(ep); }
   ```
   The legit GUI (`GuiAbilitySelect`) only sends `data` in `[0, Chromabilities.maxPower(ep, a)]`, and
   `maxPower <= getMaxPower()`, which is 24 for SHIFT (`Chromabilities.getMaxPower()` `()I`, SHIFT case returns 24).
   The Thaumcraft focus path (`ItemAbilityFocus`) clamps with `Math.min(pwr, ceil(getMaxPower()*potency/5))`.
   The server does **not** clamp: a modified client can send any int, including negative.
   The client-only check `Chromabilities.canPlayerExecuteAt` (energy + Mystcraft "ability block" page) is
   skipped by the server packet path too.

2. **Clicking (vanilla packet C08 -> Forge event).** `AbilityHelper.clickBlock(PlayerInteractEvent)`
   `(Lnet/minecraftforge/event/entity/player/PlayerInteractEvent;)V`, `@SubscribeEvent`:
   ```java
   if (evt.action == RIGHT_CLICK_BLOCK) this.addPlayerClick(evt.entityPlayer, evt.world, evt.x, evt.y, evt.z);
   ```
   `addPlayerClick` `(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;III)V`:
   ```java
   if (!world.isRemote && isDrawingBox.get(ep)) {
       WorldLocation loc = new WorldLocation(world, x, y, z);
       if (playerClicks.containsKey(ep)) {               // 2nd click
           BlockBox b = new BlockBox(loc, playerClicks.get(ep));
           this.playerMakeBox(ep, b);
           this.removePlayerClick(ep);
       } else playerClicks.put(ep, loc);                 // 1st click, kept with no time limit
   }
   ```
   `playerMakeBox` (private) `(Lnet/minecraft/entity/player/EntityPlayer;LReika/DragonAPI/Instantiable/Data/Immutable/BlockBox;)V`:
   ```java
   if (!ep.worldObj.isRemote && Chromabilities.SHIFT.enabledOn(ep)) {
       ScaledDirection dir = shifts.get(ep);
       AbilityCalls.shiftArea((WorldServer)ep.worldObj, box, dir.direction, dir.distance, (EntityPlayerMP)ep);
   }
   ```

**How the box size is decided:** entirely by the player. `BlockBox(WorldLocation, WorldLocation)` just takes
min/max of the two corners (decompiled `BlockBox.java:56`; the dimension inside `WorldLocation` is ignored, so
corner 1 may even come from another dimension). The first corner is stored forever, so the player can click
one corner, fly any distance and click the other. The only server-side limit per click is Crucible's reach
check in `NetHandlerPlayServer.processPlayerBlockPlacement` (cauldron source, ~line 862):
`reachDistance > (theItemInWorldManager.getBlockReachDistance() + 1)^2 -> return`, and the ChromatiCraft REACH
ability raises that reach to up to 192 blocks (`AbilityCalls.setReachDistance` -> `setBlockReachDistance`,
`REACH_SCALE = {8,12,16,32,64,128,192}`). There is no config option for a shift size: `ChromaOptions` has only
`SHIFTTILES("World Shift Can Move TileEntities", false)` and, for a different item, `COPYSIZE("Duplication Wand Max Volume", 1000)`.

### 2.2 The hot method

`Reika.ChromatiCraft.Auxiliary.Ability.AbilityCalls.shiftArea`, static, package-private, descriptor
`(Lnet/minecraft/world/WorldServer;LReika/DragonAPI/Instantiable/Data/Immutable/BlockBox;Lnet/minecraftforge/common/util/ForgeDirection;ILnet/minecraft/entity/player/EntityPlayerMP;)Z`
(javap). Decompiled (CFR, `AbilityCalls.java:514`), with the phases marked:

```java
static boolean shiftArea(WorldServer world, BlockBox box, ForgeDirection dir, int dist, EntityPlayerMP ep) {
    FilledBlockArray moved = new FilledBlockArray(world);
    BlockArray toDel = new BlockArray(); toDel.setWorld(world);
    int air = 0;
    // PHASE A - scan, read-only: (sizeX+1)*(sizeY+1)*(sizeZ+1) iterations, no limit (bytecode: if_icmpgt)
    for (int i = 0; i <= box.getSizeX(); ++i) for (int j = 0; j <= box.getSizeY(); ++j) for (int k = 0; k <= box.getSizeZ(); ++k) {
        int x = i + box.minX, y = j + box.minY, z = k + box.minZ;
        Block b = world.getBlock(x, y, z); int meta = world.getBlockMetadata(x, y, z);
        if (!creative && b instanceof SemiUnbreakable && ... || !creative && ReikaBlockHelper.isUnbreakable(...)
            || b.hasTileEntity(meta) && !ChromaOptions.SHIFTTILES.getState()) continue;
        moved.setBlock(x, y, z, b, meta);          // <- line 553, stall frame
        toDel.addBlockCoordinate(x, y, z);
        if (b.isAir(world, x, y, z)) ++air;
    }
    moved.offset(dir, dist);                       // rebuilds all maps
    // PHASE B - cost, computed only now
    int factor = (int)(Math.pow(box.getVolume() - air, 1.25) * dist / 5.0);
    ElementTagCompound cost = AbilityHelper.instance.getUsageElementsFor(Chromabilities.SHIFT, ep).scale(factor);
    boolean nrg = PlayerElementBuffer.instance.playerHas(ep, cost);
    // PHASE C - permission: one Forge BreakEvent (+ Bukkit BlockBreakEvent) per block, twice
    if (nrg && ReikaPlayerAPI.playerCanBreakAt(world, toDel, ep) && ReikaPlayerAPI.playerCanBreakAt(world, moved, ep)) {
        BlockArray toDrop = BlockArray.getIntersectedBox(toDel, moved); toDrop.setWorld(world);
        for (ItemStack s : toDrop.getAllDroppedItems(world, 0, ep)) { }   // drops computed and thrown away
        // PHASE D - mutation
        toDel.clearArea();                         // setBlock(air, flags 3) for every source block
        moved.place();                             // setBlock(block, meta, flags 3) for every destination block
        PlayerElementBuffer.instance.removeFromPlayer(ep, cost);
        flag = true;
    } else { flag = false; ChromaSounds.ERROR.playSound(ep); }
    Chromabilities.SHIFT.setToPlayer(ep, false);
    return flag;
}
```

Bytecode order confirmed with `javap -c` (offsets): scan loop 26-272, `FilledBlockArray.setBlock:(IIILnet/minecraft/block/Block;I)V`
at 221 (the only call to it in the method), `offset` 276, `getVolume` 281, `playerHas` 330, `playerCanBreakAt` 348 and 359,
`clearArea:()V` 422, `place:()V` 427, `removeFromPlayer` 437, `ChromaSounds.ERROR.playSound` 454,
`Chromabilities.SHIFT.setToPlayer:(Lnet/minecraft/entity/player/EntityPlayer;Z)V` 463, single `ireturn` 468.

### 2.3 What it does per block (DragonAPI, decompiled)

Phase A, per visited position:
- 1 `getBlock` + 1 `getBlockMetadata` in `shiftArea`; `getBlockHardness`/`getPlayerRelativeBlockHardness` in
  `ReikaBlockHelper.isUnbreakable` (survival players). **`World.getBlock` loads or generates missing chunks
  synchronously**: Crucible `World.getBlock` -> `getChunkFromChunkCoords` -> `ChunkProviderServer.provideChunk`, which
  loads when `shouldLoadChunk()`; that is true when `loadChunkOnProvideRequest`, whose default is
  `CrucibleConfigs.cauldron_settings_loadChunkOnRequest = true` (`build/Crucible/src/main/java/io/github/crucible/CrucibleConfigs.java:19`).
  The production value of that option was not read (config values are off limits).
- `FilledBlockArray.setBlock(x,y,z,Block,int)` -> `setBlock(x,y,z,BlockCheck)` -> `StructuredBlockArray.addBlockCoordinate`
  (another `getBlock` + `getBlockMetadata`, a `HashMap.put(new Coordinate, new BlockKey)`) -> `BlockArray.addBlockCoordinate`
  (`HashSet.contains`, `ArrayList.add`, `HashSet.add`, limits) -> then `FilledBlockArray.data.put(new Coordinate, bk)` (the stall frame).
- `toDel.addBlockCoordinate` (`HashSet` + `ArrayList` again).
- So per block: 3 block reads, 2 meta reads, 5 hash-structure inserts, 4 new `Coordinate` objects.
- `Coordinate.hashCode() = coordHash(x,y,z) = ((31+x)*31+y)*31+z` (javap `-c`), so `(x,y,z)` collides with
  `(x+1, y-31, z)` etc. Large boxes put many keys in the same bucket; Java then turns buckets into trees
  (`HashMap$TreeNode.putTreeVal`, the top of the stall stack). Measured below.

Phase C, per block, twice (`toDel`, then `moved`): `ReikaPlayerAPI.playerCanBreakAt(WorldServer,int,int,int,EntityPlayerMP)`
reads the block, calls `MinecraftServer.isBlockProtected`, then builds a Forge `BlockEvent.BreakEvent`, whose
Crucible constructor calls `CraftEventFactory.callBlockBreakEvent` (Bukkit `BlockBreakEvent` to every plugin:
GriefPreventionPlus, WorldGuard, PlotMe, ... are installed) and then posts it on the Forge bus. Reads at the
destination also load/generate chunks.

Phase D, per block: `Coordinate.setBlock(world, air, 0)` -> `world.setBlock(..., flags 3)` and `BlockKey.place(..., 3)`:
light update, neighbour notifications, client packets.

Tile entities: skipped when `SHIFTTILES` is false (left in place, not moved, not deleted). When true, only
block + meta are copied (`BlockKey`), so the tile entity's data would be lost; that is existing ChromatiCraft
behaviour, unchanged by this design; the production value of `SHIFTTILES` was not read.

### 2.4 Two cost bugs that make it cheap (measured, `etapa1/B-harness/src/CostFormula.java`)

`BlockBox.getVolume()` is `getSizeX()*getSizeY()*getSizeZ()` (no +1; decompiled `BlockBox.java:98`), while the loop visits
`(size+1)` positions per axis. And `ElementTagCompound.scale(float)` floors every element at 1
(`if ((amt = (int)(amt * s)) <= 0) amt = 1;`, decompiled line 192). Running the exact formula:

```
size=(1999,0,1999) iterations=4000000 air=0       dist=24 -> factor=0 -> an element costing 50 becomes 1
size=(1999,0,1999) iterations=4000000 air=4000000 dist=24 -> factor=0 -> 1
size=(9,9,9)       iterations=1000    air=1000    dist=24 -> factor=0 -> 1
size=(9,9,9)       iterations=1000    air=0       dist=24 -> factor=18182 -> 909100
size=(31,31,31)    iterations=32768   air=0       dist=24 -> factor=1878657 -> 93932848
size=(31,31,31)    iterations=32768   air=0       dist=1000000 -> factor=2147483647 -> 2147483647
size=(31,31,31)    iterations=32768   air=0       dist=-5 -> factor=-391386 -> 1
```

("50" is an arbitrary example base cost; the real base of SHIFT was not computed.) Consequences:
- A **one-layer box (dy = 0) of any area costs 1 unit per element** (volume 0, `pow(<=0, 1.25)` is 0 or NaN, `(int)NaN = 0`).
- A box that is mostly air also costs 1.
- **Negative `dist` (modified client) makes any box cost 1.**
- A creative player always passes (`PlayerElementBuffer.playerHas(EntityPlayer, CrystalElement, int)` returns true when `capabilities.isCreativeMode`).
- Even when the player cannot pay, phase A (the full scan) has already run: "pay after you work".

## 3. Root cause

`AbilityCalls.shiftArea` scans a box whose size the player chooses freely (two clicks, first corner kept
forever, reach up to 192 with the REACH ability), with no volume or time limit, on the server thread inside
the network phase; the energy cost and the protection check come only after the scan, and the cost formula
is 1 for flat or airy boxes and for negative distances. Each visited block costs several world reads (with
synchronous chunk loading), five hash inserts into maps whose `Coordinate` hash collides badly for large boxes,
two Forge+Bukkit break events and two `setBlock` calls, so a large box freezes the whole server for seconds
to minutes.

## 4. What the in-house mods already do about it

Nothing.
- `Akashic-ChromatiCraft-Compatibility-Fix-1.3.0.jar`: 3 classes (`AkashicChromaSoundHashFix`, core plugin,
  `WorldClientVerifierRepairTransformer`): client sound-hash fix, unrelated.
- `Akashic-ChromatiFixes-UATrades-Server-Guard-1.0.0-serveronly.jar`: guards chromatifixes' `UATradesMixin` on the server, unrelated.
- `chromatifixes-1.14.5.jar`: about 100 mixins (worldgen, particles, Waila, `ShiftMazeGeneratorMixin` which is
  the "shift maze" dimension structure, not the ability); no class references `AbilityCalls`, `AbilityHelper`,
  `Chromabilities` or `ChromatiPackets`.
- Scan of all 292 mod jars and 65 plugin jars for `AbilityCalls`, `shiftArea`, `Ability/AbilityHelper`: matches only inside ChromatiCraft.
- ChromatiCraft has no size option for the shift (`ChromaOptions`, section 2.1).

## 5. Proposed fix

One late mixin class in Hoshiestar-Core, `HoshieShiftCapMixin`, target `Reika.ChromatiCraft.Auxiliary.Ability.AbilityCalls`
(a ChromatiCraft class, so `@LateMixin` loader implementing `com.gtnewhorizon.gtnhmixins.ILateMixinLoader`:
`getMixinConfig()Ljava/lang/String;`, `getMixins(Ljava/util/Set;)Ljava/util/List;`, javap on
`+unimixins-all-1.7.10-0.3.1.jar`; list the mixin only when `loadedMods` contains `ChromatiCraft`, the `@Mod modid`
read with `javap -v`). Same pattern as `com.akashic.bqrelief.BQReliefLateMixins`. `remap = false`, SRG names, no refmap,
`"minVersion": "0.8.5-GTNH"`. All injections use the full descriptor below.

Target method (all three injections):
`shiftArea(Lnet/minecraft/world/WorldServer;LReika/DragonAPI/Instantiable/Data/Immutable/BlockBox;Lnet/minecraftforge/common/util/ForgeDirection;ILnet/minecraft/entity/player/EntityPlayerMP;)Z`
(static, so all handlers are `private static` with the five target arguments plus `CallbackInfoReturnable<Boolean>`).

### 5.1 Injection 1: hard cap and distance check, before anything happens

- `@Inject(method = "shiftArea(...)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 1)`
- Handler:
  1. `start = System.nanoTime(); scanned = 0;` (static fields of the mixin, server thread only).
  2. `long vol = (box.getSizeX() + 1L) * (box.getSizeY() + 1L) * (box.getSizeZ() + 1L);`
     (`BlockBox.getSizeX/Y/Z ()I`, javap) = exactly the number of loop iterations of the original.
  3. `badDist = validateDistance && (dist < 0 || dist > Chromabilities.SHIFT.getMaxPower())`
     (`Chromabilities.SHIFT` `LReika/ChromatiCraft/Registry/Chromabilities;`, `getMaxPower ()I`, javap; 24 in this jar).
  4. If `vol > maxVolume || badDist`: call `refuse(ep, reason)` and `cir.setReturnValue(false)`.
- `refuse` copies the original "else" branch exactly, then adds a message and a log line:
  - `ChromaSounds.ERROR.playSound(ep)` (`LReika/ChromatiCraft/Registry/ChromaSounds;`, `playSound (Lnet/minecraft/entity/Entity;)V`, javap)
  - `Chromabilities.SHIFT.setToPlayer(ep, false)` (`(Lnet/minecraft/entity/player/EntityPlayer;Z)V`, javap)
  - `ep.func_145747_a(new ChatComponentText(msg))` (`EntityPlayer.func_145747_a:(Lnet/minecraft/util/IChatComponent;)V`,
    seen in DragonAPI `ReikaChatHelper` bytecode; Crucible source `EntityPlayerMP.addChatMessage(IChatComponent p_145747_1_)` line 1320;
    `ChatComponentText."<init>":(Ljava/lang/String;)V` seen in AM2 bytecode)
  - log (one line, INFO): player `ep.func_70005_c_()` (`Entity.func_70005_c_:()Ljava/lang/String;`), dimension
    `ep.field_71093_bK` (`Entity.field_71093_bK:I`), box `minX..maxZ` (`BlockBox.minX..maxZ`, `public final int`, javap), volume, dist, reason.
    This also fills the D-21 gap "who clicked and where".
- Energy is not touched (the original only removes energy after a successful move).

### 5.2 Injection 2: time budget during the read-only scan

- `@Inject(method = "shiftArea(...)Z", at = @At(value = "INVOKE", target = "LReika/DragonAPI/Instantiable/Data/BlockStruct/FilledBlockArray;setBlock(IIILnet/minecraft/block/Block;I)V", ordinal = 0), cancellable = true, remap = false, require = 1)`
  (the only call to that method in `shiftArea`, offset 221, javap).
- Handler: `if (scanBudgetNanos > 0 && (++scanned & 1023) == 0 && System.nanoTime() - start > scanBudgetNanos) { refuse(ep, "budget"); cir.setReturnValue(false); }`
- Only phase A can be interrupted, and phase A only reads the world and fills local arrays (section 6).
  The point exists for boxes under the cap that sit in unloaded/ungenerated chunks (synchronous chunk loads).

### 5.3 Injection 3: timing log (diagnostics, optional)

- `@Inject(method = "shiftArea(...)Z", at = @At("RETURN"), remap = false)` (one `ireturn` in the original, offset 468).
- Handler: if `logAboveMs > 0` and elapsed > `logAboveMs`, log player, dimension, box, volume, dist, result
  (`cir.getReturnValue()`), elapsed ms. Never changes anything.

All handlers: first line `if (!Cfg.shiftCapActive) return;`, and the body inside `try { } catch (Throwable t)`
that logs once and returns without touching `cir` (fail open to the original path).

### 5.4 Config (`config/hoshiestar.cfg`, category `chromaticraft_shift`)

| Key | Type, default | Meaning |
|---|---|---|
| `enabled` | B, `true` | Read once at startup by the late loader. `false` -> the mixin is not listed, so `AbilityCalls` is loaded byte-for-byte original. |
| `maxVolume` | I, `32768` (32x32x32) | Max positions visited, counted as (dx+1)(dy+1)(dz+1). Above it the shift is refused before the scan. Provisional: calibrate with test 8.2 (target: one shift at the cap <= 500 ms on the test server). |
| `validateDistance` | B, `true` | Refuse when `dist < 0` or `dist > Chromabilities.SHIFT.getMaxPower()` (24). A normal client never sends these values. |
| `scanBudgetMs` | I, `250` (0 = off) | Refuse if the read-only scan takes longer than this. |
| `logAboveMs` | I, `50` (0 = off) | Log shifts slower than this; refusals are always logged. |
| `refuseMessage` | S, `"Shift recusado: area de %1$d blocos, limite %2$d."` | Chat text for volume refusals; fixed texts for distance and budget refusals. |

All values are read at startup (no reload in this stage).

### 5.5 How "switch off" restores the exact original path

`enabled=false`: the mixin is not applied at all; the class is the original bytecode. With `enabled=true`, a
shift under the cap, with `dist` in 0..24 and a scan under the budget, runs the original code with only the
three callbacks added (two `nanoTime` reads, one counter, no state written to the world or the player); the
original statements run in the original order with the original arguments.

## 6. Why it is safe

Invariants:
1. **No block is moved or removed when we refuse.** Both refusal points are before offset 422 (`toDel.clearArea()`), the first
   world mutation. Injection 1 runs before line 534 (before even `new FilledBlockArray`). Injection 2 runs inside phase A,
   which only does: block/meta reads, `isUnbreakable` checks (hardness reads, Forge `BreakSpeed` event for survival players,
   read-only by contract), local `HashMap/HashSet/ArrayList` inserts, and chunk loads. Chunk loading is not a game change.
   The arrays are local to the call and are dropped. So there is never a half-moved area.
2. **No energy is created or lost.** The original removes energy only after `place()` (offset 437); we return before it.
   The arming fee charged by `triggerAbility` is charged today too when the shift later fails for lack of energy.
3. **Same player-visible state as an existing failure.** The refusal does what the original "not enough energy" branch
   does (error sound, ability switched off via `setToPlayer(ep,false)`), plus a chat line. `addPlayerClick` still calls
   `removePlayerClick` after `playerMakeBox` returns.
4. **No new thread, no shared state.** `shiftArea` runs only on the server thread (packet phase); the two static fields are
   written at HEAD of each call; the method is not re-entrant.
5. **Distance check does not refuse any normal input.** GUI sends `0..maxPower <= 24`; the Thaumcraft focus clamps to
   `ceil(24*potency/5)`; only a modified client sends other values.

Ways it could go wrong:
- **Gameplay change (visible):** shifts larger than `maxVolume` stop working. Legit paid shifts of solid boxes that
  large are probably unaffordable already (factor grows like volume^1.25; 32^3 solid at dist 24 gives factor 1.88M per
  base unit), but **flat or airy boxes, which today cost 1 because of the cost bug, are refused above the cap, also for
  creative players and admins**. Admins can raise `maxVolume`. This is intended.
- **Budget refusals are not deterministic:** under heavy lag or ungenerated chunks an in-cap shift may be refused; the
  player retries (chunks are loaded by then). No state change. Set `scanBudgetMs=0` to disable.
- **Late mixin not applied** (for example `AbilityCalls` loaded before late mixins are registered): no protection, no harm.
  Test 8.1 checks the log line. Not verified yet.
- **`require = 1` on a different ChromatiCraft version:** the injector fails when `AbilityCalls` is loaded, which can crash the
  server at the first use of any `AbilityCalls` method. Mitigation: the jar is pinned in the pack; keep the loader check on
  `ChromatiCraft` being present, and re-run test 8.1 whenever ChromatiCraft is updated.
- **Exception in a handler:** caught, logged once, falls back to the original path (fail open). A bug there would only remove protection.
- **Per-iteration overhead of injection 2:** Mixin allocates a `CallbackInfoReturnable` per callback, so one small object per
  scanned block (tens of ns versus 0.3-4.5 us measured per block for the data structures alone). Acceptable; if it shows up in
  test 8.2, drop injection 2 (`scanBudgetMs=0` does not remove the callback; removing it needs a rebuild) and rely on the cap.
- **Things this does not fix:** the cost bug itself (changing prices is a game-rule change, left as is); destination blocks
  outside the source being overwritten without drops; blocks shifted above y=255 (possible even with a
  normal client: dist 24 UP from y >= 232) being cleared at the source and not placed (read from the code, not run); tile-entity data loss when `SHIFTTILES=true`; the unvalidated `data`
  of other abilities in `ChromatiPackets` (for example REACH uses `REACH_SCALE[data]` with no bounds check). These are
  existing behaviours and are out of scope for 1.B.

**Is it a deliberate lag machine?** Yes. Any player who has the SHIFT ability (progression-gated), with an unmodified
client: arm the ability, right-click one corner, fly away (or use REACH 192), right-click the other corner. A one-layer
slab of any area is free (section 2.4) and is actually moved; a box over someone else's claim is refused only after the
full scan and after one break event per block up to the first protected one, so the freeze happens anyway and costs
nothing. It can be repeated after re-arming. With a modified client, negative `dist` makes any box free, and huge `dist`
with a flat box moves blocks thousands of blocks away (destination chunks generated synchronously, world size grows); a `dist`
that pushes the destination above y=255 clears the source and places nothing (blocks destroyed; read from the code, not run).

**Is server-side packet validation needed?** For this item, yes for the distance, and it is done where it matters, at
`shiftArea` HEAD (covers the packet path and the focus path). The volume cap is itself the validation of the box, which
comes from two ordinary C08 clicks that cannot be validated in isolation. A general check of the `ChromaData ABILITY`
packet for all abilities is a separate item (not designed here).

## 7. Expected gain

- **Average TPS/MSPT: 0%.** The profile share is 0.00% (section 1); the ability was not used in the 602 s window.
- **Worst case:** removes the stall class of D-21. Today one call is unbounded (>= 30 000 ms observed). With the cap, one call
  visits at most `maxVolume` positions.

Lower bound of the cost per call, measured here with the real DragonAPI classes (`ShiftDataHarness`: replays only the
data-structure part of `shiftArea`, phase A inserts + `offset(0,24,0)` + `getIntersectedBox`; world reads stubbed,
no events, no `setBlock`; JDK 8, `-Xmx8g`, second (warm) run):

| Box | Positions | Data-structure time | per block | distinct hashes |
|---|---|---|---|---|
| 16x16x16 | 4 096 | 1 ms | 0.32 us | 100% |
| 32x32x32 | 32 768 | 11 ms | 0.35 us | 93.9% |
| 64x64x64 | 262 144 | 183 ms | 0.70 us | 23.9% |
| 1000x1x1000 | 1 000 000 | 822 ms | 0.82 us | 96.1% |
| 128x128x128 | 2 097 152 | 9 378 ms | 4.47 us | 6.0% |

Command: `cd etapa1 && env -u _JAVA_OPTIONS -u JAVA_TOOL_OPTIONS /usr/lib/jvm/java-8-openjdk-amd64/bin/java -Xmx8g -cp "B-harness/out:<stubs>:<DragonAPI jar>:<Crucible jar>:<libraries>" ShiftDataHarness 16x16x16 32x32x32 64x64x64 1000x1x1000 128x128x128`.
The per-block cost grows with size because of the `Coordinate` hash collisions (only 6% distinct hashes at 128^3), which
matches the `TreeNode.putTreeVal` top frame of the stall. This is only a floor: the in-game cost adds world reads, chunk
loads, 2 Forge+Bukkit break events and 2 `setBlock` per block (not measured; test 8.2 measures it).

So: with the cap at 32 768, the data-structure part of the worst call goes from unbounded (9.4 s already at 2.1 M
positions, more for the box of D-21) to about 11 ms; the full in-game cost at the cap is expected in the tens to hundreds
of ms (estimate, not measured) and is what test 8.2 must confirm before choosing the final `maxVolume`.

## 8. Test plan

### 8.1 Correctness and differential test on the 292-mod test server

Setup (test copy only; stop with `echo stop >> console.in`):
1. Build Hoshiestar-Core, start the test server with `enabled=true`. Check the log: the mixin config loaded and, at first use,
   no injection error (and the timing log line of step 4 appears, which proves the mixin is applied).
2. Bot logs in. Console: `op HoshieBot`, `gamemode 1 HoshieBot`. Bot chat: `/chromaprog maximize abilities`
   (ChromatiCraft `ProgressModifyCommand`; it requires the caller in creative; gives SHIFT; creative also passes the energy check).
3. Pre-load the test areas: console `tp HoshieBot 200 100 200`, wait 10 s; repeat for each area below.
4. Drive the ability. The bot cannot send the `ChromaData` packet, so call the same server-side entry points:
   - Option A (no mod code): a test-only Skript file in the test copy using the installed `skript-reflect` plugin, command
     `/shifttest <x1> <y1> <z1> <x2> <y2> <z2> <dist>`: get the NMS player (`CraftPlayer.getHandle()`), call
     `Chromabilities.triggerAbility(handle, Chromabilities.SHIFT, dist)`, then `AbilityHelper.instance.addPlayerClick(handle, handle.field_70170_p, x1,y1,z1)`
     and the same with `x2,y2,z2`, timing the second call with `System.nanoTime()`, then print elapsed ms and a checksum
     (sum over source and destination boxes of `Block.func_149682_b(getBlock)` * 16 + meta). Direction comes from the bot's
     look: use `tp HoshieBot x y z 0 -90` (look up) so the direction is UP (assumed, not verified).
     Whether skript-reflect can see mod classes on Crucible is not verified.
   - Option B (fallback): a debug command in Hoshiestar-Core, off by default (`debug.testCommands=false`), doing exactly the same calls.
   This skips the C08/PlayerInteractEvent layers, which the fix does not touch.
5. Cases (each on a fresh copy of the same world, run once with `enabled=false` = BEFORE and once with `enabled=true` = AFTER):

| # | Box (natural terrain near 200,200) | dist | BEFORE expected | AFTER expected |
|---|---|---|---|---|
| a | (200,60,200)-(215,75,215), 4 096 | 24 | moved | moved, **same checksum as BEFORE** |
| b | (200,60,200)-(231,91,231), 32 768 (= cap) | 24 | moved | moved, same checksum |
| c | (200,64,200)-(399,64,399), 40 000, flat | 24 | moved (free) | refused, chat message, checksum = untouched world, ability off |
| d | (200,64,200)-(799,64,799), 360 000, flat | 24 | moved, long freeze | refused in < 1 ms |
| e | box (a) | -5 | moved 5 blocks down | refused (distance) |
| f | box (a) | 1000 | source cleared, destination above y=255 so `setBlock` fails: **the blocks vanish** (expected from the code, verify) | refused (distance), checksum untouched |
| g | box (b) in an ungenerated area (e.g. 20000,60,20000), `scanBudgetMs=1` | 24 | moved | refused (budget), checksum = untouched |
| h | repeat (c) with `enabled=false` | 24 | | identical to BEFORE (switch-off check) |

   "Untouched" checksum: compute it once before the call and compare after.
6. After each run: no exception in the log, the TPS returns to 20 (`/tps` or the medidor), no item entities spawned around the boxes (medidor entity count before/after).

### 8.2 Before/after measurement (calibrates `maxVolume`)

- With `enabled=false`, run `/shifttest` 5 times each on solid-ish boxes of 8^3, 16^3, 24^3, 32^3, 48^3 (fresh area each time,
  already loaded) and on flat 100x1x100, 200x1x200, 400x1x400. Record the printed elapsed ms and the `logAboveMs` lines.
  Optionally record a JFR during the run (`jcmd <pid> JFR.start ... filename=...`) to split world reads / events / setBlock.
- With `enabled=true`, same commands: in-cap rows must match BEFORE within noise; above-cap rows must be < 1 ms.
- Choose `maxVolume` = largest tested volume whose median is <= 500 ms on the test server (4 cores; production has 7, so this is conservative).

### 8.3 Standalone harnesses (done here, repeatable)

- `ShiftDataHarness` (section 7 table) and `CostFormula` (section 2.4) under `etapa1/B-harness/`. After the mod is written, add a
  harness that calls the mixin's pure decision function (`volume`, `badDist`) with edge cases: sizes 0, cap-1, cap, cap+1,
  int-overflow-sized boxes, `dist` -1/0/24/25.

## 9. Not verified

- The box size, player and location of the D-21 incident (not in the dump).
- That the 30 s were one call (single snapshot).
- The real in-game cost per block (events, chunk loads, `setBlock`); only the data-structure floor was measured.
- The production values of Crucible `cauldron_settings_loadChunkOnRequest` and ChromatiCraft `SHIFTTILES` (config values not read).
- The real base cost of SHIFT (`tagMap` value) and the player energy cap, so "large solid shifts are unaffordable" is an inference.
- That the late mixin is applied in time to `AbilityCalls` (load order), and the behaviour of `require = 1` failing at runtime.
- That `getDirectionFromEntityLook(ep, true)` returns UP for pitch -90.
- That skript-reflect on Crucible can reach mod classes (test option A).
- What happens with an out-of-range REACH `data` in the packet handler (mentioned only as a side note).
- Whether a Forge `BreakSpeed` event handler in this pack has side effects during the scan (assumed read-only).
