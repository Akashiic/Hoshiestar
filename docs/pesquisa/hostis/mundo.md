# Task 0.8: thread-hostile state in World / WorldServer tick (area "mundo")

Read-only code study. Nothing was run, built or edited. Every `file:line` below was opened during this study.

## 0. Conventions

- `MC/` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad/build/Crucible/eclipse/cauldron/src/main/java/` (decompiled Minecraft + Forge with all Crucible patches applied, MCP names). Line numbers are lines of the final patched file.
- `CR/` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad/build/Crucible/src/main/java/` (Crucible, CraftBukkit, Spigot, Thermos classes).
- `PT/` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad/build/Crucible/patches/`.
- `W` = `MC/net/minecraft/world/World.java`, `WS` = `MC/net/minecraft/world/WorldServer.java`.
- **B** = Degrau B, synchronized parallel phase: 3 to 4 region threads tick TEs, entities (and, if moved there, scheduled/random block ticks) of different regions at the same time while everything else waits. **C** = Degrau C, independent regions with their own tick clocks.
- "Region work" = code that runs inside the per-region part of the tick: TE `updateEntity`, entity `onUpdate`, and the block updates/neighbour notifications they trigger. "Global phase" = everything that stays serial.
- Prior work this report builds on (not redone): `docs/pesquisa/crucible-tick-e-threads.md` (tick order, threads, §2 note on `HashedArrayList` and `synchronizedSet`, §4 public-field table) and `docs/pesquisa/crucible-arquitetura-patches.md` §4 (static state table). Cited as `[tick-threads §N]` and `[patches §N]`.
- Kind labels: per-world collection | per-chunk | static mutable | global singleton | re-entrancy/ordering assumption | cross-thread handoff.
- "Not verified at runtime" applies to every failure description: all of it is derived from reading code.

Two facts that shape almost every item:

1. **One `World` object is shared by all regions of a dimension.** Every per-world field below is touched by all region threads of that dimension at once in B.
2. **Some "per-world" objects are actually per-server.** `World.mapStorage` is the overworld's for every dimension (`W:377-385`, `W:499-511`, `MC/net/minecraft/world/WorldServerMulti.java:17,27`), and `worldScoreboard` of every `WorldServerMulti` is the overworld's (`WorldServerMulti.java:18,28`). These are hostile even with "one thread per dimension".

---

## 1. Items

### M01. `World.collidingBoundingBoxes` (collision scratch list)

- Declaration: `W:171` (`private ArrayList collidingBoundingBoxes`), initialised `W:305,370,525`.
- Main sites:
  - `getCollidingBoundingBoxes` `W:1928-2004`: Cauldron/Spigot change assigns a **new list to the shared field** (`W:1930`, patch `PT/net/minecraft/world/World.java.patch:887`), then keeps reading the field: passes it to `Block.addCollisionBoxesToList` (`W:1971`), `ensureCapacity` (`W:1984`), `add` (`W:1992,1999`) and returns the field (`W:2003`).
  - `func_147461_a` `W:2006-2042`: `clear()` and reuse of the same field (`W:2008`), returns it (`W:2041`).
  - Callers in region work: `Entity.moveEntity` (`MC/net/minecraft/entity/Entity.java:616,682,698,714,747,809,2084`), `EntityLiving.getCanSpawnHere` (`MC/.../entity/EntityLiving.java:700`), `EntityLivingBase:1674` (`func_147461_a`), Ghast, Enderman, Ocelot, `EntityHanging:176`. From the network phase: `NetHandlerPlayServer.java:518,558`, `ServerConfigurationManager.java:840`.
- Kind: per-world collection used as a re-entrancy-unsafe scratch buffer.
- Region work: every moving entity, every tick. Outside: network phase (player movement checks).
- Breaks: thread A sets the field to list LA, thread B sets it to LB; A then adds its boxes into LB and returns LB. Entities get the other region's collision boxes (walk through walls, stop on invisible walls, fall through floors, suffocation checks wrong). Concurrent `ArrayList.add` on the same list: lost boxes or `ArrayIndexOutOfBoundsException`. With `func_147461_a`, a `clear()` from one thread empties the list another thread is filling.
- Severity: **B alto | C alto** (hottest path of the entity tick).
- Treatment: **local variable**. Mixin that rewrites both methods to build and return a local `ArrayList` (the field is private, only reachable by reflection). One sentence: the field has no meaning across calls, so removing the sharing is free and changes nothing observable.

### M02. `World.loadedEntityList` and `unloadedEntityList` (and the `HashedArrayList` class)

- Declaration: `W:129-130` (`new HashedArrayList()`, `PT/.../World.java.patch:125-126`). Class `CR/io/github/crucible/util/HashedArrayList.java:5-7`: an `ArrayList` plus a `Collections.synchronizedSet(LinkedHashSet)` used for `contains` and de-duplication.
- Writes:
  - `addEntity` `W:1839` (from `spawnEntityInWorld` `W:1723-1732`; called by TE ticks, entity ticks, block drops, spawner).
  - `addLoadedEntities` `W:4058-4070` (chunk load, `MC/net/minecraft/world/chunk/Chunk.java:1048`; can happen inside region work through a synchronous `provideChunk`, see M23).
  - `joinEntityInSurroundings` `W:4384-4405`.
  - Removal by index with the shared cursor: `W:2479` (`remove(this.tickPosition--)`), `removePlayerEntityDangerously` `W:1906-1918`.
  - Bulk removal `W:2393` (`removeAll(unloadedEntityList)`; `HashedArrayList.removeAll` at `HashedArrayList.java:125-134` rebuilds the whole list from the set).
  - `Entity.teleportTo` `MC/.../entity/Entity.java:2515` (`this.worldObj.loadedEntityList.remove(this)`, Thermos patch `PT/net/minecraft/entity/Entity.java.patch:788`), see M27.
  - `unloadedEntityList`: filled by `unloadEntities` `W:4072-4075` (from `Chunk.onChunkUnload`, `Chunk.java:1081`), drained `W:2393-2414`.
- Reads: tick loop `W:2421-2485`; `countEntities` `W:4028-4056` and `W:4815-4826` (spawner, mods); CraftWorld `getEntities/getLivingEntities/getEntitiesByClass(es)/getPlayers` iterate it (`CR/org/bukkit/craftbukkit/v1_7_R4/CraftWorld.java:703,721,746,768,793`); watchdog and commands read `size()` from other threads (`CR/org/spigotmc/WatchdogThread.java:118,192`); 34 mod jars read the field directly [tick-threads §4].
- Kind: per-world collection (public field).
- Region work: spawning (drops, projectiles, mob births, XP orbs), death removal, chunk crossing (`W:2741-2757` touches `Chunk` lists of the target chunk). Outside: plugins (any event handler fired from a region thread can call `CraftWorld.getEntities()`), mods iterating the field, watchdog.
- Breaks:
  - `HashedArrayList.add` is two steps (`hashed.add` then `super.add`, `HashedArrayList.java:14-21`). Two concurrent adds: both pass the set, the `ArrayList` loses one element or throws `ArrayIndexOutOfBoundsException`. The lost entity is in the set but not in the list: it never ticks and can never be re-added (the set rejects it). Permanent ghost until the chunk unloads.
  - Index removal with a per-world `tickPosition` (M03) from two threads removes the wrong entity.
  - Any iterator (CraftWorld, mods) throws `ConcurrentModificationException` while another thread appends.
- Pre-existing bugs found (single thread, not verified at runtime): `HashedArrayList.listIterator()` calls itself forever (`HashedArrayList.java:96-99`) and `set()` removes the new element from the set instead of the old one (`:146-154`). Our replacement list must not inherit either.
- Severity: **B alto | C alto**.
- Treatment: **per-region instance + defer to a queue applied at the join point**. During the parallel phase the world list must not change size (as `docs/02-arquitetura.md` §3 already states): `addEntity` from a region thread appends to that region's pending list; removals mark dead and are swept at the join. Region loops iterate a region-owned list. The public field keeps one stable object (a view, design §4(3)) so mods that cached the reference keep working.

### M03. Per-world tick cursors and budgets: `tickPosition`, `tileTickPosition`, `entityLimiter`, `tileLimiter`

- Declaration: `W:167` (`tickPosition`), `W:287` (`tileTickPosition`), `W:285-286` (`TickLimiter`), created `W:4894-4897`.
- Sites: entity loop `W:2421-2423,2479`; `removePlayerEntityDangerously` adjusts the cursor `W:1912-1915`; TE loop `W:2518-2534`; `initTick`/`shouldContinue` `W:2422,2518`.
- Kind: per-world scalar state with a single-iterator assumption (re-entrancy/ordering assumption).
- Region work: the loops themselves. Outside: `removePlayerEntityDangerously` from network/global phase (dimension change, logout).
- Breaks: two regions sharing one cursor skip or double-tick entities and remove the wrong one; one thread's `initTick()` resets the other's time budget (wrong result, budget never or always exceeded).
- Severity: **B alto | C alto** (but it is fixed automatically by whoever writes the region loop).
- Treatment: **per-region instance** (cursor and `TickLimiter` belong to the region object; the Degrau A budget replaces `TickLimiter` anyway). Keep the per-world fields for the serial fallback path.

### M04. `World.loadedTileEntityList` and `addedTileEntityList`

- Declaration: `W:131-132` (`HashedArrayList`, `PT/.../World.java.patch:127-128`).
- Writes: `setTileEntity` `W:3220-3243` (to `addedTileEntityList` with an iterator `remove` at `W:3224-3237` when the flag M05 is on, else straight to `loadedTileEntityList` `W:3241`); `func_147448_a` `W:2628-2640` and `addTileEntity` `W:4728-4735` (destination chosen by the flag); `Chunk.addTileEntity` → `World.addTileEntity` (`Chunk.java:969`); chunk load → `func_147448_a` (`Chunk.java:1036`); tick loop removals `W:2527,2534`; pending merge `W:2591-2619`.
- Reads: TE loop `W:2518-2569`; `getTileEntity` scans `addedTileEntityList` linearly `W:3167-3179,3191-3203`; CraftWorld/commands/watchdog sizes; 26 mod jars read `loadedTileEntityList` directly [tick-threads §4].
- Kind: per-world collection (public field for the loaded list).
- Region work: machines placing/breaking blocks with TEs, `setTileEntity` from mods, `getTileEntity` on every neighbour lookup (very hot: pipes, cables, inventories). Outside: chunk loading (global, or synchronous inside region work), mods iterating the field.
- Breaks: concurrent `add` on the `ArrayList` part (lost TE, AIOOBE, entry in the set but not in the list: TE never ticks again); `getTileEntity` from region A reading `addedTileEntityList` while region B appends gets a `null` slot and throws NPE at `tileentity1.isInvalid()` (`W:3173`/`W:3197`); the iterator in `setTileEntity` throws CME if another thread adds.
- Severity: **B alto | C alto**.
- Treatment: **per-region instance + defer to join point**: each region owns its TE list and its "pending" list; `getTileEntity` must look in the caller region's pending list (then the chunk). The world-wide lists are only mutated in the global phase.

### M05. `World.field_147481_N` (the 1.7.10 "processing loaded tiles" flag)

- Declaration: `W:172`. Set `true` `W:2490`, `false` `W:2573` and again `W:2587`.
- Readers: `getTileEntity` `W:3167`, `setTileEntity` `W:3222`, `func_147448_a` `W:2631`, `addTileEntity` `W:4730`.
- Kind: re-entrancy/ordering assumption stored per world (it means "the single tick thread is inside the TE loop").
- Region work: all of M04's writers consult it. Outside: chunk loads in the global phase (flag false, so they write the loaded list directly).
- Breaks: with regions, "inside the TE loop" is a per-thread fact. Region A finishes and clears the flag while region B is still iterating: B's `setTileEntity` now writes straight into `loadedTileEntityList` that B (and others) are iterating, so indices shift (skipped or double-ticked TEs, AIOOBE). Conversely a region still in its entity phase sees `true` because another region is in its TE loop and puts TEs in the pending list (harmless by itself, but that pending list is shared, M04).
- Severity: **B alto | C alto**.
- Treatment: **thread-local** (or a field of the region context): "is the current thread inside a TE loop". Outside the parallel phase it keeps today's meaning.

### M06. `World.field_147483_b` (TE removal buffer) and the list swap of `loadedTileEntityList`

- Declaration: `W:133` (plain `ArrayList`, Cauldron made it public).
- Writes: `func_147457_a` `W:3279-3283`, called by `Chunk.onChunkUnload` (`Chunk.java:1061`). Drained `W:2492-2514`.
- The drain **replaces the public list object**: `this.loadedTileEntityList = temporary_tile_entity_list` (`W:2507`, `PT/.../World.java.patch:1045`), and the replacement is a plain `ArrayList`, not a `HashedArrayList`.
- Kind: per-world collection + reference swap of a public field.
- Region work: none today (chunk unload is in `WorldServer.tick`, `WS:261`). Outside: chunk unload (global), mods holding the old `loadedTileEntityList` reference.
- Breaks: (a) any thread or mod holding the old list keeps iterating a dead list; with regions, a region thread that read the field before the swap ticks the old list. (b) After the first swap `loadedTileEntityList.contains` is linear (`W:2599`) and de-duplication is gone: `setTileEntity` `W:3241` and `addTileEntity` `W:4733` can add the same TE twice, which then ticks twice per tick (pre-existing; plausible from code, not verified at runtime).
- Severity: **B medio | C alto** (B is safe only if the drain stays in the global phase, which it does today).
- Treatment: **keep on the global thread** (drain before the parallel phase) and stop swapping the object: filter in place so mod-held references and region views stay valid.

### M07. `World.playerEntities`

- Declaration: `W:134` (plain `ArrayList`).
- Writes: `addEntity` `W:1835`, `removeEntity` `W:1882`, `removePlayerEntityDangerously` `W:1894`. Callers are login, respawn, dimension change and logout. I checked that the player's own `onUpdate` with portal handling runs from the network phase: `EntityPlayerMP.onUpdateEntity` calls `super.onUpdate()` (`MC/.../entity/player/EntityPlayerMP.java:410-414`), invoked from `NetHandlerPlayServer.java:416,445,488`.
- Reads in region work: `getClosestPlayer`/`getClosestVulnerablePlayer` `W:4231-4307` (mob AI, despawn checks, spawner), `getPlayerEntityByName` `W:4309`, `SpawnerAnimals` `MC/.../world/SpawnerAnimals.java:54-56`, explosion packets `WS:1223-1233`, particles `WS:1380-1393`, `setActivePlayerChunksAndCheckLight` `W:3487,3504-3547`, `updateAllPlayersSleepingFlag` `WS:319-334`. 63 mod jars read the field [tick-threads §4].
- Kind: per-world collection (public field).
- Breaks: in B, nothing as long as all writes stay in the network/global phase (verified above for the vanilla paths). A mod that adds/removes a player (or a `FakePlayer` added to the world, which some mods do) from a TE tick would cause CME in every concurrent reader. In C, the network phase and region ticks overlap: CME and stale reads everywhere.
- Severity: **B baixo | C alto**.
- Treatment: B: **no action**, plus a write check (warn if mutated during the parallel phase). C: **snapshot** (copy-on-write list replaced at each join) or per-region player lists.

### M08. `World.weatherEffects`

- Declaration: `W:135`.
- Writes: `World.addWeatherEffect` `W:1714-1718`, overridden in `WS:1166-1191` (fires `LightningStrikeEvent`, sends `S2CPacketSpawnGlobalEntity` to all near). Callers: random-tick thunder `WS:475-487` and mods (lightning from TEs/items). Removal in the tick loop `W:2388`.
- Reads: tick loop `W:2345-2390`; 2 mod jars read it directly [tick-threads §4].
- Kind: per-world collection.
- Region work: thunder strikes (if random ticks move into regions) and mod lightning. Outside: global phase iteration.
- Breaks: concurrent `add` (lost bolt, AIOOBE); add while the global loop iterates is impossible in B (global loop runs alone) but certain in C.
- Severity: **B medio | C alto**.
- Treatment: **defer to a queue applied at the join point** (region threads enqueue, the global phase appends); weather entities keep ticking in the global phase.

### M09. `WorldServer` scheduled block updates: `pendingTickListEntriesHashSet`, `pendingTickListEntriesTreeSet`, `pendingTickListEntriesThisTick`

- Declaration: `WS:95-96,106`; created `WS:130-138,171-179,969-977`. The hash set is `Collections.synchronizedSet(LinkedHashSet)` (Crucible, `PT/net/minecraft/world/WorldServer.java.patch:75,112,636`); the `TreeSet` and the `ThisTick` list are not synchronized.
- Writes: `scheduleBlockUpdateWithPriority` `WS:592-636` (check-then-act: `contains` then `add` to both sets, `WS:630-634`); `func_147446_b` `WS:638-653` (chunk load restoring pending ticks); `tickUpdates` `WS:707-803` (moves due entries from TreeSet to `ThisTick` `WS:737-749`, iterates and removes `WS:753-798`, re-schedules unloaded ones `WS:796`); `getPendingBlockUpdates` `WS:805-859` (chunk save/unload, removes while iterating).
- Reads: `isBlockTickScheduledThisTick` `WS:581-585`; debug `reevaluate` `WS:678-704` (prints "TickNextTick list out of synch", `WS:711-719`).
- Kind: per-world collection.
- Region work: every block that schedules a tick (fluids, redstone, pistons, crops, many mod blocks) from TE ticks, entity ticks, neighbour updates. Outside: global `tickUpdates` today, chunk load/save.
- Breaks: concurrent `TreeSet.add` corrupts the red-black tree (lost entries, NPE in `fixAfterInsertion`, possible endless loop); the two-set check-then-act is not atomic, so the sets drift apart. An entry left in the hash set but missing from the tree set blocks that position+block from ever being scheduled again (`WS:630` says "already scheduled") until restart: water, lava, redstone or a machine block silently stops updating. `tickUpdates` iterating `ThisTick` while another thread schedules: CME.
- Severity: **B alto | C alto** (B alto even if `tickUpdates` stays global, because scheduling happens from region work).
- Treatment: **per-region instance** (Folia does per-region tick lists) with **defer to join point** for ticks scheduled into another region; until then, scheduling from a region thread goes into a per-region buffer merged at the join, and `tickUpdates` runs per region on its own entries.

### M10. `NextTickListEntry.nextTickEntryID` (static counter)

- Declaration: `MC/net/minecraft/world/NextTickListEntry.java:7` (`private static long nextTickEntryID`), incremented without synchronization in the constructor `:19`. Used as the last tie-breaker in `compareTo` `:55-58`. (Thermos only changed `hashCode`, `PT/net/minecraft/world/NextTickListEntry.java.patch`.)
- Writers: every `new NextTickListEntry` (`WS:583,594,640`) in every world.
- Kind: static mutable (shared by all dimensions).
- Breaks: two threads get the same id. Two different entries with equal `scheduledTime`, equal `priority` and equal id compare as 0, so `TreeSet.add` silently refuses the second one while the hash set accepted it: the M09 "stuck forever" state, even with only one thread per dimension.
- Severity: **B alto | C alto**.
- Treatment: **atomic counter** (mixin replacing the field use with an `AtomicLong`) or a per-region counter (ids only order entries inside one tree). Cheap and removes a silent bug.

### M11. `WorldServer` block event double buffer: `field_147490_S`, `blockEventCacheIndex`

- Declaration: `WS:103-104` (two `ServerBlockEventList`, plain `ArrayList` subclass `WS:1401-1411`).
- Writes: `addBlockEvent` `WS:1238-1255` (linear duplicate scan **with an iterator**, then `add`). Called from region work: pistons, chests (open/close lid), note blocks, mod TEs (`world.addBlockEvent` is the usual way to animate client-side). Drained by `func_147488_Z` `WS:1257-1277`, which flips the index and runs `onBlockEventReceived` (pistons move blocks here) and sends `S24PacketBlockAction` to all near.
- Kind: per-world collection + index flag.
- Region work: adds. Outside: global drain in `WorldServer.tick` (`WS:304`).
- Breaks: thread A iterating the list for duplicates while thread B adds: `ConcurrentModificationException` (very likely, since the scan iterates every time). Concurrent `add`: lost event (piston never extends, chest animation stuck). A flip of `blockEventCacheIndex` during a region add (only in C) sends the event to the wrong buffer.
- Severity: **B alto | C alto**.
- Treatment: **per-region instance** of the double buffer; B drains all buffers in the global phase (piston moves stay serial); C drains per region.

### M12. Active chunk sets: `activeChunkSet` and `activeChunkSet_CB`

- Declaration: `W:169` (`HashSet`, made public by Cauldron), `W:211` (Trove `TLongShortHashMap`), created `W:364-365,508-509`.
- Writes: rebuilt every tick in `setActivePlayerChunksAndCheckLight` `W:3460-3551` (clear `W:3462-3464`, persistent chunks `W:3465-3474`, player chunks and random picks `W:3504-3529`); the random-tick loop removes unloaded chunks **while iterating** `WS:446-458`; optional clear `WS:573-577`.
- Reads: random-tick loop `WS:446-571` (value = player count, used for `growthOdds` `WS:563`); `World.isActiveChunk` `W:4841-4844`, used by `CauldronHooks.canTileEntityTick` for every TE (`CR/net/minecraftforge/cauldron/CauldronHooks.java:280`); watchdog/commands sizes (`WatchdogThread.java:117,191`).
- Kind: per-world collection.
- Region work: TE tick filter reads it (every TE, every tick). If random ticks move into regions, iteration and `iter.remove()` happen in region threads.
- Breaks: concurrent reads of a Trove map are fine only if nobody writes. `iter.remove()` from one region thread while another reads `isActiveChunk` gives wrong answers or `ArrayIndexOutOfBoundsException` inside Trove during rehash/compaction (auto-compaction disabled `W:365`, removal still mutates slots). Rebuild during a parallel read: TEs tick or skip at random.
- Severity: **B medio | C alto**.
- Treatment: **keep on the global thread / snapshot**: build (and prune unloaded chunks) before the parallel phase, then treat as read-only; each region iterates only its own chunks from that snapshot. In C, per-region active set.

### M13. `World.updateLCG` and `ambientTickCountdown`

- Declaration: `W:146` (seeded from `rand`), `W:170`.
- Writes: `func_147467_a` `W:3555-3584` (`updateLCG` `W:3561`, `ambientTickCountdown` `W:3533-3536,3577`); random ticks `WS:477,493,552-553`.
- Kind: per-world scalar (pseudo-random state).
- Region work: random block ticks, ice/snow, thunder positions (if moved into regions).
- Breaks: lost read-modify-write: two regions draw the same "random" block offsets for a while. No crash, slightly biased randomness.
- Severity: **B baixo | C baixo**.
- Treatment: **per-region instance** (one LCG per region, seeded from the world's).

### M14. `World.growthOdds` / `modifiedOdds`

- Declaration: `W:212-213`. Written `W:3496` (per tick) and `WS:563` (per random-ticked block, depending on whether the chunk has players).
- Readers: crop/sapling/cactus/reed/mushroom/stem/grass `updateTick` (`MC/net/minecraft/block/BlockCrops.java:53`, `BlockReed.java:48`, `BlockCactus.java:51`, `BlockMushroom.java:30`, `BlockMycelium.java:64`, `BlockStem.java:51`, `BlockSapling.java:55`, `BlockGrass.java:72`).
- Kind: per-world scalar used as a hidden parameter between `WS:563` and the next `updateTick` call (re-entrancy/ordering assumption).
- Breaks: region A sets 100, region B's crop reads it before its own write is used: farms in player-less chunks grow at the wrong speed. No crash.
- Severity: **B baixo | C baixo**.
- Treatment: **thread-local** (or per-region field) for `growthOdds`; `modifiedOdds` is written once per tick in the global build step (M12) and can stay.

### M15. `World.lightUpdateBlockList` (light propagation queue)

- Declaration: `W:173` (`int[]`), allocated `int[32768]` per world `W:306,371,526`.
- Sites: only `updateLightByType(EnumSkyBlock,int,int,int,Chunk,List)` `W:3764-3921` (writes/reads at `W:3789,3793,3797,3824,3826,3842,3858,3864-3889`). Reached from `func_147451_t` `W:3695-3706`, which `setBlock` calls for every block change (`W:841`), and from `markBlocksDirtyVertical` `W:1029`, `setActivePlayerChunksAndCheckLight` `W:3547`.
- Kind: per-world array used as scratch space (non-reentrant).
- Region work: every block change in every region (machines, block breakers, fluids, players).
- Breaks: two threads use the same queue with their own local indices: each overwrites the other's entries, so light is propagated from wrong relative positions (dark spots, light leaks), the propagation can walk to coordinates near the other thread's origin (possibly another region, loading chunks there). No exception, silent wrong world state that gets saved.
- Severity: **B alto | C alto**.
- Treatment: **thread-local** `int[32768]` (128 KB per thread; mixin redirecting the field reads in this one method). Per-chunk light data races at region borders belong to the chunk area.

### M16. `World.lightingExecutor` (PaperSpigot async lighting)

- Declaration: `W:4910` (one single-thread executor per world, "PaperSpigot - Lighting Thread"). Used by `updateLightByType(EnumSkyBlock,int,int,int)` `W:4915-4954`: only if `useAsyncLighting` and the world `isModded` (`W:4922`), it bumps `Chunk.pendingLightUpdates` and submits to the executor, unless `!Bukkit.isPrimaryThread()` (`W:4943-4945`), in which case it runs synchronously.
- Kind: cross-thread handoff.
- Region work: on a region thread `isPrimaryThread()` is false, so the update runs inline on the region thread (then M15 applies). On the main thread it runs on the lighting thread concurrently with region threads writing the same chunks.
- Breaks: with the option on, light thread and region threads write `Chunk` light arrays and `lightUpdateBlockList` at once.
- Severity: **B baixo | C baixo** while the option is off (default `false`, test copy `spigot.yml:95` = false per [tick-threads §2]); **alto** if someone turns it on.
- Treatment: **no action**, but refuse to start the parallel modes if `use-async-lighting` is true.

### M17. `World.rand` (a `ThermiteRandom`) and `World.setRandomSeed`

- Declaration: `W:145` (`new thermos.thermite.ThermiteRandom()`, `PT/.../World.java.patch:144`). Class `CR/thermos/thermite/ThermiteRandom.java`: `next(int)` reads and writes a plain `long seed` with no CAS and no lock (`:120-128`); only `setSeed`/`getSeed`/`nextGaussian` are `synchronized` (`:89,100,130`).
- Writers/users in region work: 124 references in 53 files under `MC/net/minecraft` (grep count), for example `Explosion.java:91,188,253-264`, `SpawnerAnimals.java:36-38,161-163,202`, `WS:316,475,491,564,612,772` (passed as the `Random` to every `updateTick`), `W:3518-3519,3542-3546`. Every mod using `world.rand` from a TE.
- Reseeding: `setRandomSeed` `W:4577-4582` sets the shared generator to a deterministic seed; called from worldgen `MapGenScatteredFeature.java:69`, `MapGenVillage.java:70` (chunk generation, which can run inside region work, M23).
- Kind: per-world shared object (public field).
- Breaks: unlike `java.util.Random`, this one is **not** thread-safe: lost updates of `seed`, so two regions draw identical sequences (correlated drops, spawns and crop growth). A reseed from worldgen in one thread makes the other thread's next values predictable. No exception.
- Severity: **B medio | C medio** (silent, hard to observe, gameplay-visible over time).
- Treatment: **thread-local** state behind the same object: replace the field value with a `Random` subclass whose `next()`/`setSeed()` use a per-thread (per-region) seed. Mods call methods on the object, so swapping the object at world creation is invisible to them.

### M18. `WorldServer.entityIdMap` (`IntHashMap`)

- Declaration: `WS:107`, created `WS:125-128,166-169,964-967`. Class `MC/net/minecraft/util/IntHashMap.java` (unsynchronized buckets, `grow`/`copyTo` rehash `:79-120`).
- Writes: `onEntityAdded` `WS:1131-1144` and `onEntityRemoved` `WS:1146-1159` (spawn, death, chunk load/unload, multipart parts).
- Reads: `getEntityByID` `WS:1161-1164`: network packets (use entity, entity actions), `EntityTracker`, mods.
- Kind: per-world collection.
- Region work: every spawn and every removal. Outside: network phase lookups.
- Breaks: concurrent insert during `grow()` loses entries (`getEntityByID` returns `null`: client clicks on that entity do nothing, mods lose references); concurrent bucket edits can drop chains.
- Severity: **B alto | C alto**.
- Treatment: **defer to a queue applied at the join point** in B (adds/removes recorded per region, applied serially; lookups during the parallel phase are rare and only need entities that existed at phase start). C: replace with a concurrent map (mixin on the three methods, the field is private).

### M19. `Entity.nextEntityID` (static id counter)

- Declaration: `MC/net/minecraft/entity/Entity.java:93` (`private static int nextEntityID`); `this.entityId = nextEntityID++` in the constructor `:201`.
- Writers: every entity construction in every world and thread (spawner, drops, projectiles, mods, chunk load).
- Kind: static mutable (whole server).
- Breaks: duplicate ids. `IntHashMap.addKey` overwrites an existing key (`IntHashMap.java:62-78`), so `getEntityByID` returns the newer entity for both; when either dies `removeObject` drops the mapping of the survivor. Clients receive two spawns with the same id (entity replaced or invisible, wrong entity attacked through `C02PacketUseEntity`). Happens even with one thread per dimension.
- Severity: **B alto | C alto**.
- Treatment: **atomic counter** via a mixin on `Entity.<init>` (`AtomicInteger.getAndIncrement`). Nothing else writes the field.

### M20. Forge block capture: `captureBlockSnapshots`, `capturedBlockSnapshots`, `restoringBlockSnapshots`

- Declaration: `W:233-235` (public, per world).
- Writes: `ForgeHooks.onPlaceItemIntoWorld` (`MC/net/minecraftforge/common/ForgeHooks.java:516` on, `:541,:547` off, snapshots consumed `:555-601,675`, restore with `restoringBlockSnapshots` `:628-630`). Reached through `ItemStack.tryPlaceItemIntoWorld` (`MC/net/minecraft/item/ItemStack.java:152-154`), which players use (network phase) and **FakePlayer block placers use from TE ticks** (region work).
- Reads: `World.setBlock` `W:812-838` (adds a snapshot, twice because of the Cauldron block at `W:818-831` [patches §2.6]) and, crucially, `W:845`: when a snapshot was taken, `markAndNotifyBlock` is **skipped** (no client update, no neighbour notification). `addEntity` `W:1738` drops `EntityItem` spawns while `restoringBlockSnapshots`. `Block.java:671,688` (drops). `WorldChangeHolder` checks `:31,94`.
- Kind: per-world flag + collection used as a dynamic-scope variable (re-entrancy assumption: "only the placing code runs while the flag is on").
- Region work: FakePlayer placement in region A turns the flag on for the whole world.
- Breaks: while A captures, every `setBlock` of region B is captured into A's list and B's neighbour/client updates are suppressed; A then fires one `PlaceEvent`/`MultiPlaceEvent` containing B's blocks, and if a protection plugin cancels it, A **restores B's blocks** (machines un-done, items possibly duplicated). Concurrent `add` to `capturedBlockSnapshots` (AIOOBE). B's item drops vanish while A restores (`W:1738`).
- Severity: **B alto | C alto**.
- Treatment: **thread-local** capture context: the flags and the list become per-thread for code running in region threads (mixin on `setBlock`/`addEntity`/`Block` drop checks reads a thread-local; the public fields keep working for the global phase and for mods that set them, with a warning if set from a region thread).

### M21. CraftBukkit/Thermos drop capture: `captureItemDrops`, `itemStackSpawnQueue`

- Declaration: `W:1720-1721` (public). `capturedItems` `W:177` is declared and never used (grep: no other reference).
- Writes: `ForgeHooks.java:529` (on), `:550` (off), queue cleared `:632` (event cancelled) and spawned `:664-668` (event accepted).
- Reads: `spawnEntityInWorld` `W:1726-1729` diverts every `EntityItem` into the queue while the flag is on.
- Kind: per-world flag + collection (dynamic-scope variable).
- Breaks: during A's FakePlayer placement, B's drops (mined blocks, killed mobs) land in A's queue: if A's placement is cancelled they are deleted (`:632`, item loss); if accepted they are spawned by A's thread into B's chunks. Concurrent queue `add` (AIOOBE).
- Severity: **B alto | C alto**.
- Treatment: **thread-local**, same context object as M20.

### M22. Crucible tree capture: `captureAndProxyBlocks`, `worldChangeHolder`

- Declaration: `W:175-176`. Class `CR/io/github/crucible/util/WorldChangeHolder.java` (a `Long2ObjectLinkedOpenHashMap` of changes `:24`, `startCapture` throws if a capture is active `:30-39`, `isCapturing` reads the world flag `:133-135`).
- Writes: `BlockSapling.updateTick` starts a capture for every sapling that grows (`MC/net/minecraft/block/BlockSapling.java:58`) (random tick = region work); `ForgeHooks.java:523,542,551,557,588` (bone meal on saplings/mushrooms); `CraftWorld.generateTree` (`CR/.../CraftWorld.java:546`, plugins).
- Reads: while capturing, **every** `getBlock` `W:668-674`, `setBlock` `W:785-789`, `getBlockMetadata` `W:882-888` and `getTileEntity` `W:3161-3164` of that world is answered from or written into the holder, regardless of which thread calls.
- Kind: per-world flag + collection (dynamic-scope variable).
- Breaks: a tree growing in region A captures region B's block writes: B's changes are later committed by A or **discarded** (`context.discardChanges()` when the `StructureGrowEvent` path is used, `BlockSapling.java:67`; `discardChanges` in `ForgeHooks.java:551`). B's reads return A's tree blocks for positions A touched. If B grows a sapling at the same time, `startCapture` throws `IllegalStateException("Capture already in progress!")` in B (crash report for a block tick). Concurrent use of the fastutil map: corrupt map.
- Severity: **B alto | C alto** (saplings and mod trees grow constantly on a populated server).
- Treatment: **thread-local** capture holder (one per thread/region); `World` accessors check the current thread's holder only.

### M23. Worldgen flags read by the whole world: `populating`, `scheduledUpdatesAreImmediate` (and synchronous chunk load re-entrancy)

- Declaration: `W:166` (`populating`), `W:128` (`scheduledUpdatesAreImmediate`).
- Writes: `populating` around Bukkit populators `MC/net/minecraft/world/gen/ChunkProviderServer.java:441,452`; `scheduledUpdatesAreImmediate` in `WorldGenLiquids.java:82,84`, `WorldGenHellLava.java:90,92`, `StructureNetherBridgePieces.java:808,810`.
- Reads: `notifyBlockChange` returns early while `populating` (`W:1006-1009`); `scheduleBlockUpdateWithPriority` runs `updateTick` **immediately** while `scheduledUpdatesAreImmediate` (`WS:600-620`).
- Related re-entrancy: chunk generation and synchronous chunk load can happen inside region work (`provideChunk` loads on request, [patches §2.8], [tick-threads review]); loading then calls `addLoadedEntities` (`Chunk.java:1048`), `func_147448_a` (`Chunk.java:1036`) and reseeds `world.rand` (M17). Also the static `BlockSand.fallInstantly` is toggled in the same populate path (`ChunkProviderServer.java:431,456`, other area).
- Kind: per-world flags (dynamic-scope variables) + re-entrancy.
- Breaks: while region A generates a chunk, region B's neighbour notifications are dropped (redstone/fluids miss updates) and B's scheduled ticks run instantly and recursively (fluids flash-flow, possible deep recursion).
- Severity: **B medio | C alto** (depends on how much generation happens during play; production pre-generates per design §5, not verified).
- Treatment: **thread-local** flags; and in B, **defer** synchronous chunk generation requested from region threads to the global phase where possible (other area decides).

### M24. `World.mapStorage` (shared by all dimensions), `perWorldStorage`, `WorldSavedData.dirty`

- Declaration: `W:153` (`mapStorage`), `W:126` (`perWorldStorage`). Cauldron makes every world use the overworld's `mapStorage` (`W:375-385,510`; `WorldServerMulti.java:17,27`). Class `MC/net/minecraft/world/storage/MapStorage.java`: `HashMap loadedDataMap`, `ArrayList loadedDataList`, `HashMap idCounts` (`:22-24`), no locks; `getUniqueDataId` writes `idcounts.dat` to disk synchronously (`:186-228`). `WorldSavedData.dirty` plain boolean (`MC/net/minecraft/world/WorldSavedData.java:8,20-33`).
- Callers in region work: `World.loadItemData/setItemData/getUniqueDataId` `W:4518-4531` from `ItemMap` (`MC/net/minecraft/item/ItemMap.java:40,45,54,58,67,277,290`, map items in inventories/item frames) and from mods (34 jars use `WorldSavedData` [docs/02-arquitetura.md §2]). `perWorldStorage`: villages (`W:478-489`), structures `MapGenStructure.java:245,256` (generation). Save: `WS:1127-1128` (global).
- Kind: global singleton (mapStorage) / per-world collection (perWorldStorage).
- Breaks: two regions (or two dimensions) loading the same key at once both miss the cache, both create an instance, last `put` wins: one TE keeps writing into an orphan object that is never saved (data loss, dupes in mods that keep inventories/energy there). Concurrent `HashMap.put` corrupts the map. Two `getUniqueDataId("map")` calls can return the same map id.
- Severity: **B alto | C alto** for mods that use `WorldSavedData` from TE ticks; **medio** for vanilla (maps only).
- Treatment: **lock** (synchronize `MapStorage` methods; calls are rare) for the map itself; the contents of each mod's `WorldSavedData` are the mod module's problem (design §4(4)). `perWorldStorage` gets the same lock.

### M25. Villages: `World.villageCollectionObj`, `Village`, `VillageSiege`

- Declaration: `W:154-155`. `MC/net/minecraft/village/VillageCollection.java:17-20` (`villagerPositionsList`, `newDoors`, `villageList` ArrayLists, `tickCounter`); `Village.java:23-33` (door list, `TreeMap playerReputation`, `villageAgressors`); `VillageSiege.java:17-25`.
- Writes from region work: `addVillagerPosition` (`VillageCollection.java:47-56`) from `EntityVillager.java:117`; `Village.setReputationForPlayer` (`EntityVillager.java:167,297,317`, `EntityIronGolem.java:269`), `addOrRenewAgressor` (`EntityVillager.java:286`), `endMatingSeason` (`EntityVillager.java:321,330`).
- Reads from region work: `findNearestVillage` (`EntityVillager.java:119`, `EntityIronGolem.java:75`, AI tasks `EntityAIRestrictOpenDoor.java:27`, `EntityAIMoveThroughVillage.java:41`, `EntityAIVillagerMate.java:37`, `EntityAIMoveIndoors.java:41`).
- Global: `VillageCollection.tick()` and `VillageSiege.tick()` from `WS:291-292` (adds/removes villages, `iterator.remove()` `VillageCollection.java:78-93`).
- Kind: per-world collection.
- Breaks: concurrent `addVillagerPosition` (lost or AIOOBE); a village whose villagers sit in two regions gets concurrent `TreeMap.put` on reputation (corrupt tree). In C, `findNearestVillage` iterating `villageList` while the village tick removes entries: CME.
- Severity: **B medio | C alto**.
- Treatment: B: **lock** on the `VillageCollection` for the two writers (rare calls) and keep both ticks on the global thread. C: **per-region instance** (villages are small; force a village's area into one region through the merge radius, design §4(2)).

### M26. Mob spawning: `WorldServer.animalSpawner` (`SpawnerAnimals`)

- Declaration: `WS:102` (one instance per world). `MC/net/minecraft/world/SpawnerAnimals.java:30` (`LongObjectHashMap eligibleChunksForSpawning`).
- Sites: `findChunksForSpawning` `SpawnerAnimals.java:42-243`: clears and fills the map from all players `:50-86`, counts creatures over the **whole world** `countEntities` (`:122` → `W:4028-4056`), picks random chunks (`getChunkFromChunkCoords` can load chunks `:35`), creates and adds entities `:194-213`, uses `world.rand` throughout. Called only from `WS:250-257` (global part of `WorldServer.tick`).
- Kind: per-world collection + world-wide count.
- Breaks: B: none while it stays in the global phase. If moved into regions: shared map clears, global counts read while other regions add entities, per-world caps exceeded.
- Severity: **B baixo (kept global) | C alto**.
- Treatment: B: **keep on the global thread**. C: **per-region instance** with per-region caps (Folia's approach; caps proportional to the region's eligible chunks).

### M27. Portals and cross-dimension transfer from the entity tick: `Teleporter` caches, `Entity.travelToDimension/teleportTo`

- Declarations: `MC/net/minecraft/world/Teleporter.java:24-26` (`Random random`, `LongHashMap destinationCoordinateCache`, `ArrayList destinationCoordinateKeys`); `WS:101` (`worldTeleporter`, a `CraftTravelAgent`, `WS:181`), `WS:112` (`customTeleporters`, only iterated `WS:297-300`).
- Sites: non-player entities reach a portal inside their own tick (`Entity.java:440-462` → `travelToDimension` `:2469-2497`): fires `EntityPortalEvent` (`:2488`), then `CraftTravelAgent.findOrCreate` (`CR/.../CraftTravelAgent.java:22-30`) → `Teleporter.findPortal` scans the **destination** world and writes its cache (`Teleporter.java:159-209`), may `createPortal` (`:397`, block writes in the destination world). `teleportTo` `Entity.java:2501-2540`: removes from the source world (`:2510`, `:2515`), toggles the destination provider's `loadChunkOnProvideRequest` (`:2522-2525`, a field of another world's chunk provider) and `spawnEntityInWorld` into the destination world (`:2533`). Stale cleanup `Teleporter.removeStalePortalLocations` `:628-645` from `WS:296`.
- Kind: per-world collection written from another world's thread + cross-thread handoff.
- Region work: any item/minecart/mob in a portal. Players are not affected (their portal logic runs in the network phase, see M07).
- Breaks: with dimensions ticking in parallel (design §5: "uma thread por dimensão sai de graça no Degrau B"), the source thread writes the destination world's `loadedEntityList`, `entityIdMap`, chunk lists, teleporter cache and blocks while the destination's own region thread is ticking: CME, lost entities, corrupt `LongHashMap`. Pre-existing single-thread bug, not verified at runtime: for non-living entities `teleportTo` removes `this` from `loadedEntityList` (`:2515`) and the tick loop then removes index `tickPosition` again (`W:2479`), which is now the **next** entity: that entity stops ticking until its chunk reloads.
- Severity: **B alto | C alto**.
- Treatment: **defer to a queue applied at the join point** (design §5 already: leave the source at the end of the tick, enter the destination as a task); portal search/creation runs in the global phase or in the destination region.

### M28. `Explosion`

- Declaration: per-call object `MC/net/minecraft/world/Explosion.java:34-46` (its own `explosionRNG`, `affectedBlockPositions`, player knockback map). Created by `W:3043-3052`/`WS:1198-1236`.
- Shared state it touches: `world.rand` (`Explosion.java:91,188,253-264`, M17); static `CraftEventFactory.entityDamage` (`:162,166`, already in [patches §4]); `EntityExplodeEvent` under the global plugin lock (`:225-226`); `playerEntities` for packets (`WS:1223-1233`); blocks and entities in a box of `2*size+1` around the centre (`:125-137`), rays reach roughly `1.3*size/0.225*0.3 ≈ 1.7*size` blocks (`:91-117`, my arithmetic).
- Kind: re-entrancy/ordering assumption (reach) + uses of shared singletons.
- Region work: TNT, creepers, mod explosives and reactors.
- Breaks: an explosion near a border writes blocks/entities owned by another region; `entityDamage` static leaks the exploder to damage events of another thread (wrong attribution, wrong event cancellation).
- Severity: **B medio | C alto** (vanilla sizes fit inside a sensible merge radius; mod nukes do not).
- Treatment: the object itself needs **no action**; the static goes **thread-local** (shared with the event-factory area); explosions whose box crosses the region boundary are **deferred to the global phase** (or forced region merge).

### M29. Scoreboard: `World.worldScoreboard` and the CraftBukkit scoreboard manager

- Declaration: `W:158` (public); every `WorldServerMulti` uses the overworld's (`WorldServerMulti.java:18,28`). `MC/net/minecraft/scoreboard/Scoreboard.java:13-18` (HashMaps), `ServerScoreboard.java:20` (`HashSet`). `CR/org/bukkit/craftbukkit/v1_7_R4/scoreboard/CraftScoreboardManager.java:14-15` (`WeakCollection`, `HashMap`), `getScoreboardScores` `:87-95` creates scores lazily (`Scoreboard.func_96529_a` `Scoreboard.java:58-77` puts into HashMaps).
- Region-work callers: `EntityPlayer.java:766,772` (kill counts, when an entity tick such as an arrow kills a mob credited to a player), `EntityPlayerMP.java:619` (death count), `EntityPlayerMP.addStat` `:1177-1183`. Health updates `EntityPlayerMP.java:451` run in the network phase.
- Kind: global singleton.
- Breaks: concurrent lazy creation of `Score` objects (HashMap corruption, lost score increments); iteration of `WeakCollection` while a plugin creates a board (CME).
- Severity: **B medio | C medio** (low frequency).
- Treatment: **lock** (one lock around scoreboard writes) or **defer to the global phase** (score increments are commutative and can be queued).

### M30. `World.worldInfo` (time, weather, spawn, game rules)

- Declaration: `W:151` (public). `MC/net/minecraft/world/storage/WorldInfo.java:18-42` (plain fields), `tick()` `:578-580` (`totalTime++`, Crucible addition). Rules: `getGameRulesInstance` `:459-462`.
- Writes: `WS:269` (`worldInfo.tick()`), `WS:273` (`setWorldTime`), weather `W:3362-3458`, `WS:354-375`; all in the global part today. Mods and commands can set raining/spawn.
- Reads in region work: `getWorldTotalTime` (scheduled ticks `WS:626,645,741`, TE tick interval `CauldronHooks.java:285`, `getCurrentDate`), game rules (mob griefing, drops).
- Kind: per-world object (some dimensions share one through `DerivedWorldInfo`, `MC/net/minecraft/world/storage/DerivedWorldInfo.java`, only for the vanilla-compat constructor `WorldServerMulti.java:29`).
- Breaks: B: none (writes only in the global phase). C: regions read time while the global clock advances (inconsistent `% N` decisions within one region tick).
- Severity: **B baixo | C medio**.
- Treatment: B: **no action**. C: **snapshot** of time at the start of each region tick (Folia copies game time into the region).

### M31. `WorldProvider.worldChunkMgr` biome cache (`BiomeCache`)

- Declaration: `MC/net/minecraft/world/WorldProvider.java:31`; `MC/net/minecraft/world/biome/WorldChunkManager.java:26,34,65-68`; `MC/net/minecraft/world/biome/BiomeCache.java:12-13` (`LongHashMap cacheMap`, `ArrayList cache`), `getBiomeCacheBlock` `:21-37` (put on miss), `cleanupCache` `:44-66`.
- Callers: `World.getBiomeGenForCoordsBody` `W:242-271` → `Chunk.getBiomeGenForWorldCoords` falls back to the cache when the chunk's biome byte is 255 (`Chunk.java:1437-1446`) and directly when the block is not loaded (`W:269`). Region work: snow/rain, mob spawn checks, mods asking for biomes. Cleanup: `WS:233` (global). The cache-miss path generates biomes through `GenLayer` and the static `IntCache` (`MC/net/minecraft/world/gen/layer/IntCache.java:8-12`, methods `synchronized` `:15,57,75` but `resetIntCache` frees arrays still in use by another thread: worldgen area).
- Kind: per-world (per provider) collection.
- Breaks: concurrent `LongHashMap.add` (lost or corrupt entries, wrong biome returned); `IntCache` reuse corrupts biome arrays.
- Severity: **B medio | C medio**.
- Treatment: **lock** on `BiomeCache` (cheap; hits are short) and keep `cleanupCache` in the global phase; `IntCache` belongs to the worldgen study.

### M32. `World.theProfiler` (one `Profiler` for the whole server)

- Declaration: `W:156`; every world gets `MinecraftServer.theProfiler` (`MC/net/minecraft/server/MinecraftServer.java:134,372,429`; `MC/net/minecraftforge/common/DimensionManager.java:344,415`). `MC/net/minecraft/profiler/Profiler.java:17-21` (ArrayLists, HashMap, `profilingEnabled`).
- Sites: `startSection/endSection` all over the tick (`W:2334-2624`, `WS:246-302`). Active only after `/debug start` (`MinecraftServer.java:834-838,1512`).
- Kind: global singleton.
- Breaks: with profiling on, section stacks interleave between threads (`IndexOutOfBoundsException` in `endSection`, garbage results).
- Severity: **B baixo | C baixo** (only when an admin runs `/debug start`).
- Treatment: **no action** except: ignore `startSection/endSection` off the main thread (mixin) or block `/debug` in parallel modes.

### M33. Per-world counters: `entitiesTicked`, `tilesTicked`, `spigotConfig.currentPrimedTnt`, `TimingHistory.entityTicks/tileEntityTicks`

- Declarations: `W:178-179`; `CR/org/spigotmc/SpigotWorldConfig.java:54` (via `World.spigotConfig`); `CR/co/aikar/timings/TimingHistory.java:54-55` (static).
- Sites: `W:2341-2342,2547,2622,2689`, `W:2419,2443`; `MC/.../entity/item/EntityTNTPrimed.java:64` (`currentPrimedTnt++ > maxTntTicksPerTick`), reset `WS:670`. Readers: logging (`CauldronHooks.java:82-83,196`), watchdog dump (`WatchdogThread.java:119-120,194-195`), commands.
- Kind: per-world scalar / static mutable.
- Breaks: lost increments (statistics wrong; TNT-per-tick limit applied loosely).
- Severity: **B baixo | C baixo**.
- Treatment: **per-region instance** summed at the join (or `LongAdder`); no correctness impact.

### M34. Lazy writes to shared config from the tick: `World.tileentityConfig`, `World.sushchestvoConfig`

- Declaration: `W:180-182` (per-world wrapper around one server-wide `ConfigBase`). `CR/net/minecraftforge/cauldron/configuration/WorldConfig.java:36-57`: `getBoolean/getInt` **write** into `baseConfig.settings` (a `HashMap`, `ConfigBase.java:23`) and call `config.addDefault` (YAML defaults) the first time a path is seen.
- Callers in region work: `CauldronHooks.canTileEntityTick` `CauldronHooks.java:275` (first tick of each TE class), `canSushchestvoTick` `:234` (first tick of each entity class). The static `tileEntityCache`/`sushchestvoCache` HashMaps next to them are already listed in [patches §4].
- Kind: global singleton written lazily.
- Breaks: two regions meeting two new TE classes at once: concurrent `HashMap.put` on the shared settings map and on the YAML defaults map (lost entries, corrupt map; a later save may throw CME).
- Severity: **B medio | C medio** (bursty: after restart and when a new mod block appears).
- Treatment: **lock** (synchronize the first-time path) or pre-warm the caches for all registered TE/entity classes at startup (global phase).

### M35. `World.worldAccesses` listeners (`WorldManager`) and what they reach

- Declaration: `W:148`; added only at world creation (`MinecraftServer.java:438`, `DimensionManager.java:351,424`). Iterated by `markBlockForUpdate` `W:995-1001`, `onEntityAdded/Removed` `W:1846-1864`, sounds `W:1660-1712`, `playAuxSFXAtEntity` `W:4546-4565`, `destroyBlockInWorldPartially` `W:4634-4641`.
- `MC/net/minecraft/world/WorldManager.java`: `onEntityCreate` → `EntityTracker.addEntityToTracker` (`:28`, synchronized per [tick-threads §2]); `markBlockForUpdate` → `PlayerManager.markBlockForUpdate` (`:50`; `MC/net/minecraft/server/management/PlayerManager.java:143-153`, per-chunk `PlayerInstance.flagChunkForUpdate` `:511-535`; the to-update queue is a `ConcurrentLinkedQueue` `:32`); sounds/effects → `ServerConfigurationManager.sendToAllNear(Except)` (iterates the global `playerEntityList`); `destroyBlockPartially` iterates `playerEntityList` directly (`WorldManager.java:69`).
- Kind: per-world collection (stable) whose listeners reach per-chunk and global singletons.
- Breaks: the list itself is safe (written only at world creation). The targets: two regions flagging the same border chunk's `PlayerInstance` (lost block-change entries: client sees a stale block); iterating `playerEntityList` while the network phase changes it (only in C; also the async-chat thread today, [tick-threads review]).
- Severity: **B medio | C alto**.
- Treatment: list: **no action**. Targets: cross-reference to the chunk-map/tracker/player-list studies; in B the per-chunk `PlayerInstance` races are limited to border chunks, which the merge radius keeps out of two regions.

### M36. Bukkit events fired from World code inside region work

- Sites in my scope: `BlockPhysicsEvent` on **every** neighbour notification (`W:1096-1108`); `CreatureSpawnEvent`/`ItemSpawnEvent`/`ProjectileLaunchEvent` on every spawn (`W:1780-1790`); `BlockFormEvent` for ice/snow (`WS:502-525`); `LightningStrikeEvent` (`WS:1172-1173`); `EntityExplodeEvent` (`Explosion.java:225-226`); `EntityPortalEvent` (`Entity.java:2486-2488`); `StructureGrowEvent` (`BlockSapling.java` capture block, `ForgeHooks.java:572-573`); `ThunderChangeEvent`/`WeatherChangeEvent` (global, `W:3389-3390,3433-3434`).
- Shared state: `SimplePluginManager.callEvent` takes `synchronized(pluginManager)` for every synchronous event [tick-threads review, `SimplePluginManager.java:476-487`]; handlers then read world-wide lists (`CraftWorld.getEntities` M02), plugin state and other regions' blocks (design §4(7) open problem).
- Kind: global singleton lock + cross-region reads by plugin code.
- Breaks: correctness is kept by the lock (one handler at a time), but region threads serialize on it; `BlockPhysicsEvent` is fired for every neighbour update, so with listeners registered the parallel phase degenerates to serial. Handlers reading `loadedEntityList` while another region appends: CME (M02).
- Severity: **B medio (throughput) | C alto**.
- Treatment: B: keep the lock; skip building the event when `BlockPhysicsEvent.getHandlerList()` is empty (Paper does this; not verified that our plugins leave it empty). C: plugin adapter (design §4(7)).

### M37. `World.theCalendar`

- Declaration: `W:157` (`Calendar`). Written by `getCurrentDate` `W:4643-4651` every 600 ticks; called by entity code (Halloween/holiday checks during entity spawn, not traced further).
- Kind: per-world object.
- Breaks: torn `Calendar` state for one call (wrong date fields); no crash expected.
- Severity: **B baixo | C baixo**.
- Treatment: **no action** (or thread-local `Calendar`).

---

## 2. Second pass: fields checked and judged harmless (one line each)

Method: listed every field declaration of `World`, `WorldServer`, `WorldServerMulti`, `WorldProvider`, `Explosion`, `Teleporter`, `NextTickListEntry`, `SpawnerAnimals`, `MapStorage`, `WorldSavedData`, `WorldInfo`, `VillageCollection`, `Village`, `VillageSiege`, `Scoreboard`, `ServerScoreboard` with a grep for declarations, then grepped writers of each one outside its class.

World (`W`):
- `MAX_ENTITY_RADIUS` `W:124` static mutable: only read at tick (`W:3941-3944`, `Chunk.java:1093-1094`); mods raise it at init. Harmless unless a mod writes it at runtime (not seen in vanilla code).
- `perWorldStorage` `W:126` final reference: contents covered in M24.
- `cloudColour` `W:136`: client rendering only.
- `skylightSubtracted` `W:137`: written in the global part (`WS:262-267`, `W:3323-3326`), read in region work (`EntityMob.java:204-207`, `BlockDaylightDetector.java:51`). Read-only during the parallel phase.
- `DIST_HASH_MAGIC` `W:138`: final constant.
- `prevRainingStrength`, `rainingStrength`, `prevThunderingStrength`, `thunderingStrength` `W:139-142`: written in `updateWeatherBody` (global); read in region work.
- `lastLightningBolt` `W:143`: written only when `isRemote` (`EntityLightningBolt.java:124-126`), client only.
- `difficultySetting` `W:144`: written by commands/`CraftWorld.java:871`/`WS:230` (global).
- `provider` `W:147`: set at construction.
- `chunkProvider` `W:149`: reference set at construction (its contents: chunk area).
- `saveHandler` `W:150`: final.
- `findingSpawnPoint` `W:152`: written only during spawn search at world creation (`WS:992,1016,1047`).
- `isRemote` `W:159`: never assigned on the server (grep `isRemote = ` in server code: no hit).
- `spawnHostileMobs`, `spawnPeacefulMobs` `W:161-162`: written by `WorldProvider.setAllowedSpawnTypes` `WorldProvider.java:428` (global).
- `ticksPerAnimalSpawns`, `ticksPerMonsterSpawns` `W:164-165`: config, set at construction.
- `capturedItems` `W:177`: declared, never referenced.
- `cauldronConfig`, `tileentityConfig`, `sushchestvoConfig` references `W:180-182`: set at construction (lazy writes inside: M34).
- `isModded` `W:184`: lazily set to the same value by any thread (`W:3899-3906`, `W:4921`); benign race.
- `preloadedCrashClasses` `W:186` static: written once during the first world's construction.
- `world` (CraftWorld) `W:279`: final.
- `pvpMode`, `keepSpawnInMemory`, `generator` `W:280-282`: written by commands/plugins/init (global).
- `spigotConfig` `W:283`: final; read-only except `currentPrimedTnt` (M33).
- `timings` `W:284`: `TimingHandler.startTiming/stopTiming` return immediately off the primary thread (`CR/co/aikar/timings/TimingHandler.java:101-104,113-115`), so it is blind in region threads but not corrupted [patches §4]. Same for `MinecraftTimings.tickEntityTimer` (`W:2444-2446`) and per-TE/entity `tickTimer`.
- `s_mapStorage`, `s_savehandler` `W:626-627` static and `getMapStorage` `W:630-638`: dead code (no caller).
- `activity` `W:4839`: `ConcurrentHashMap` (safe; written by `ForgeChunkManager`, other area).
- `access_cache` `W:4851`: declared, never used.

WorldServer (`WS`):
- `logger` `WS:91`, `mcServer` `WS:92`, `dimension` `WS:115`: final.
- `theEntityTracker` `WS:93`, `theChunkProviderServer` `WS:97`: references; contents belong to the tracker and chunk studies.
- `thePlayerManager` `WS:94`: reference; reached through M35.
- `levelSaving` `WS:98`: written by save commands and `CraftWorld.save` (global).
- `allPlayersSleeping` `WS:99`: written by `updateAllPlayersSleepingFlag`/`wakeAllPlayers` (`WS:319-352`), called from player add/remove/bed (network/global).
- `updateEntityTick` `WS:100`: written in the preamble of `WorldServer.updateEntities` `WS:657-667`, which stays global.
- `bonusChestContent` `WS:105`: static final, read only at world creation.
- `doneChunks` `WS:111`: declared, never referenced (grep).

WorldServerMulti: no fields of its own (`WorldServerMulti.java:10` is `__OBFID`); it shares `mapStorage` and `worldScoreboard` (M24, M29).

WorldProvider: `moonPhaseFactors` static final array never written; `worldObj`, `terrainType`, `field_82913_c`, `isHellWorld`, `hasNoSky`, `dimensionId` set at registration; `lightBrightnessTable` filled once by `generateLightBrightnessTable` at registration; `colorsSunriseSunset` client sky colour; render handlers client only (`WorldProvider.java:27-36,206-208`). `worldChunkMgr` contents: M31.

Explosion: all fields per instance (`Explosion.java:34-46`); shared dependencies in M28.

Teleporter: `worldServerInstance` final; `random` per instance (only used in `createPortal` `Teleporter.java:416`, same risk as M27).

NextTickListEntry: instance fields are effectively immutable after scheduling; only the static id is hostile (M10).

SpawnerAnimals: only `eligibleChunksForSpawning` (M26).

MapStorage: `saveHandler` set at construction; collections in M24. WorldSavedData: `mapName` final; `dirty` is a benign boolean (a lost `markDirty` is re-set on the next change).

WorldInfo: all fields covered by M30 (written globally); `additionalProperties` and `playerTag` are written at load/save only.

VillageCollection/Village/VillageSiege: `worldObj` references set at load; collections and counters in M25 (`VillageSiege` fields are only touched by its own global tick).

Scoreboard/ServerScoreboard: covered by M29.

Static fields of the classes in scope (complete list from the grep): `World.MAX_ENTITY_RADIUS`, `World.preloadedCrashClasses`, `World.s_mapStorage`, `World.s_savehandler`, `WorldServer.logger`, `WorldServer.bonusChestContent`, `WorldProvider.moonPhaseFactors`, `NextTickListEntry.nextTickEntryID` (M10), plus `__OBFID` constants. Outside the classes but listed in the task: `Entity.nextEntityID` (M19).

---

## 3. Closing lists

### 3.1 The 10 items most likely to bite in the synchronized parallel phase (B)

1. **M01 `collidingBoundingBoxes`**: every entity move in every region shares one field; wrong collisions or AIOOBE from the first second. Fix is trivial (local list).
2. **M15 `lightUpdateBlockList`**: every `setBlock` in every region shares one 32768-int queue; silent wrong light saved to disk. Fix: thread-local array.
3. **M02 `loadedEntityList` (HashedArrayList)**: spawns from TE/entity ticks and sync chunk loads append concurrently; lost entities become permanent ghosts; plugin/mod iterators throw CME.
4. **M04/M05 TE lists and `field_147481_N`**: per-world "in TE loop" flag plus shared pending list; `getTileEntity` NPE, TEs skipped or double-ticked.
5. **M22 tree capture (`captureAndProxyBlocks`/`worldChangeHolder`)**: any growing sapling hijacks every block read/write of the dimension; other regions' changes discarded, or `IllegalStateException` when two saplings grow at once.
6. **M20/M21 Forge/Thermos placement capture** (`captureBlockSnapshots`, `captureItemDrops`, `restoringBlockSnapshots`): a FakePlayer block placer in one region suppresses notifications, captures blocks and drops of other regions, and can revert them (dupes and item loss).
7. **M09 + M10 scheduled tick sets and static `nextTickEntryID`**: unsynchronized `TreeSet` writes from block updates plus duplicate ids; positions that stop ticking forever (fluids/redstone/machines freeze silently).
8. **M11 block event buffer**: `addBlockEvent` iterates the shared list on every call; CME from pistons, chests and mod animations.
9. **M18 + M19 `entityIdMap` and static `Entity.nextEntityID`**: duplicate ids and lost map entries; wrong entity hit, ghosts on clients. Bites even with one thread per dimension.
10. **M24 shared `mapStorage`** (and **M27 cross-dimension portal transfer** if dimensions tick in parallel): mod `WorldSavedData` created twice and one copy lost; portal travel writes another world's lists from the wrong thread.

Runner-ups: M17 (`world.rand` is a non-thread-safe `ThermiteRandom`, silent), M34 (lazy config writes on first sight of a TE class), M03 (cursors/limiters; fixed by construction when the region loop is written).

### 3.2 Check of `docs/pesquisa/arte-previa.md` §4 items in this area

| # in arte-previa §4 | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | `loadedEntityList`, `loadedTileEntityList`, `addedTileEntityList`, `field_147483_b` are `ArrayList`s iterated in `updateEntities`; TEs added during the tick go to `addedTileEntityList` under flag `field_147481_N`; adding from another thread gives CME | **Confirmed, with corrections.** In Crucible the first three are `HashedArrayList` (`W:129-132`), `field_147483_b` is a plain `ArrayList` (`W:133`); `loadedTileEntityList` becomes a plain `ArrayList` after the first chunk unload (`W:2507`). The flag exists and is checked by `setTileEntity`, `func_147448_a`, `addTileEntity`, `getTileEntity` (`W:3167,3222,2631,4730`). Entities have **no** such buffer: `addEntity` appends straight to `loadedEntityList` (`W:1839`). The loops are index-based, so a single-thread add does not throw CME; with threads the typical failure is lost elements/AIOOBE (CME for iterators such as `CraftWorld.getEntities`). | M02, M04, M05, M06 |
| 4 | `pendingTickListEntriesHashSet` + `pendingTickListEntriesTreeSet` + `pendingTickListEntriesThisTick`; `nextTickEntryID` is a `static long` in `NextTickListEntry` | **Confirmed, with a correction.** All three exist (`WS:95-96,106`); `nextTickEntryID` is `private static long` (`NextTickListEntry.java:7`), unsynchronized (`:19`). Correction: in Crucible the hash set is already `Collections.synchronizedSet` (`WS:132,173,971`), but the `TreeSet` and the check-then-act are not protected. | M09, M10 |
| 5 | `World.lightUpdateBlockList` (`int[32768]`) per World, reused, non-reentrant | **Confirmed.** `W:173`, allocated `W:306,371,526`, used only in `W:3764-3921`. Extra: a per-world PaperSpigot lighting thread exists (`W:4910`), off by default. Chunk skylight arrays belong to the chunk study. | M15, M16 |
| 7a | `Entity.nextEntityID` is `private static int` incremented in the constructor | **Confirmed.** `Entity.java:93,201`. | M19 |
| 7b | static `Random`s, e.g. in `MathHelper` | **Not present in 1.7.10 `MathHelper`** (it only takes a `Random` as a parameter, `MathHelper.java:132-145`). Static randoms do exist elsewhere: `EntityTNTPrimed.java:25` (`static final ThermiteRandom`) and `TileEntityEnchantmentTable.java:19` (outside this area). | M17 |
| 7c | "`Random` is thread-safe but contended" | **Wrong for `World.rand`.** It is a `thermos.thermite.ThermiteRandom` whose `next()` has no CAS or lock (`ThermiteRandom.java:120-128`), so it is not thread-safe at all. | M17 |
| 8 (part) | `WorldServer.theChunkProviderServer`, `PlayerManager`, `EntityTracker` per world, used by players crossing regions | **Present** (`WS:93-94,97`); belongs to the chunk-map/tracker studies. From this area: `World.markBlockForUpdate` reaches `PlayerManager` for every block change (`WorldManager.java:50`), whose queue is already a `ConcurrentLinkedQueue` (`PlayerManager.java:32`). | M35 |
| Border list ("redstone/neighbour updates across chunks, fluids, explosions, spawns with `getEntitiesWithinAABB`") | **Confirmed in code paths**: neighbour notifications `W:1044-1146`, explosion reach `Explosion.java:76-137`, spawner random chunks `SpawnerAnimals.java:33-40`. | M28, M26 |

Items 2, 3, 6, 9-14 of arte-previa §4 are outside this area (chunk provider, chunk internals, `IntCache`, server globals, `DimensionManager`, event buses, FakePlayers, ForgeChunkManager, ChunkIOExecutor). Only `IntCache` was touched here, through the biome cache (M31): its methods are `synchronized` (`IntCache.java:15,57,75`), which the arte-previa text does not say, but `resetIntCache` still frees arrays another thread is using.

### 3.3 What I could not find or verify

Not found (searched, absent in this code base):
- `WorldEntitySpawner`: does not exist in 1.7.10; the equivalent is `SpawnerAnimals` (M26).
- `captureTreeGeneration`: no such field; Crucible uses `captureAndProxyBlocks` + `WorldChangeHolder` (M22).
- `callingPlaceEvent`: no such field anywhere in `MC/` or `CR/`.
- A field named `processingLoadedTiles`: in 1.7.10 it is the SRG-named `field_147481_N` (M05).
- Object pools: `AxisAlignedBB.getBoundingBox` and `Vec3.createVectorHelper` allocate new objects (`MC/net/minecraft/util/AxisAlignedBB.java:13-16`, `Vec3.java:10-13`); no `AABBPool`/`Vec3Pool` exists (grep empty). The 1.7.2 pools are gone.
- Static `Random` in `MathHelper`: none.

Not verified:
- Every failure scenario above is deduced from code; nothing was executed.
- Whether any mod or plugin in the pack calls `listIterator()` on `loadedEntityList`/`loadedTileEntityList` (would hit the pre-existing infinite recursion `HashedArrayList.java:96-99`).
- The pre-existing wrong-entity removal after a non-living entity goes through a portal (`Entity.java:2515` + `W:2479`), and the pre-existing double TE registration after the list swap (`W:2507` then `W:3241`/`W:4733`): both plausible from code only.
- Which entity code calls `getCurrentDate` (M37) and how often; not traced.
- How often chunk generation happens during play in production (drives M23 severity); design §5 says pre-generation is planned.
- Real reach of mod explosions (M28) and whether production plugins register `BlockPhysicsEvent` listeners (M36 throughput).
- Production values of `use-async-lighting` (M16) and `max-tnt-per-tick` (M33): only the test copy was cited by earlier work.
- Whether `PlayerManager.getOrCreateChunkWatcher(..., false)` can still mutate `playerInstances` on some path (read it only at `PlayerManager.java:143-153`; the class belongs to another study).
