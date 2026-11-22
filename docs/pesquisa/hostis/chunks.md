# Task 0.8: thread-hostile state in chunks, chunk loading and players (area "chunks")

Read-only study of the Crucible production code (staging@67b53034 build tree). Nothing here was run; every line
below was opened in the source. Where something is a deduction from code and not observed, it says so.

## 0. Conventions

- `M/` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad/build/Crucible/eclipse/cauldron/src/main/java/`
  (decompiled Minecraft + Forge with all Crucible patches applied, MCP names). Line numbers are of these final files,
  not of the `.patch` files.
- `C/` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad/build/Crucible/src/main/java/`
  (Crucible's own classes, CraftBukkit, Thermos).
- Short names used below: `CPS` = `M/net/minecraft/world/gen/ChunkProviderServer.java`, `Chunk` = `M/net/minecraft/world/chunk/Chunk.java`,
  `World` = `M/net/minecraft/world/World.java`, `WS` = `M/net/minecraft/world/WorldServer.java`,
  `ACL` = `M/net/minecraft/world/chunk/storage/AnvilChunkLoader.java`, `PM` = `M/net/minecraft/server/management/PlayerManager.java`,
  `SCM` = `M/net/minecraft/server/management/ServerConfigurationManager.java`, `ET` = `M/net/minecraft/entity/EntityTracker.java`,
  `ETE` = `M/net/minecraft/entity/EntityTrackerEntry.java`, `EPMP` = `M/net/minecraft/entity/player/EntityPlayerMP.java`,
  `AE` = `M/net/minecraftforge/common/util/AsynchronousExecutor.java`, `CIOP` = `M/net/minecraftforge/common/chunkio/ChunkIOProvider.java`,
  `FCM` = `M/net/minecraftforge/common/ForgeChunkManager.java`, `CBHM` = `C/thermos/wrapper/ChunkBlockHashMap.java`,
  `VCHM` = `C/thermos/wrapper/VanillaChunkHashMap.java`.
- "B" = Degrau B, synchronized parallel phase (3-4 region threads tick TEs/entities of different regions, everything
  else waits). "C" = Degrau C, independent regions. Severity is `alto | medio | baixo` for each.
- Prior work cited, not redone: `docs/pesquisa/crucible-tick-e-threads.md` (tick order, threads, public-field jar counts,
  sync chunk load through `skipQueue`, Bukkit `callEvent` global lock), `docs/pesquisa/crucible-arquitetura-patches.md`
  (§2.8 chunk load/unload, §4 static state table). The sibling report `estudo/hostis/mundo.md` covers World/WorldServer
  fields; overlaps are cross-referenced as `mundo.md Mxx` instead of repeated.
- Note on scope: the task text names `org/bukkit/craftbukkit/chunkio`. That package does not exist in this tree. The
  chunk I/O executor of 1.7.10 Crucible is Forge's `net/minecraftforge/common/chunkio` + `net/minecraftforge/common/util/AsynchronousExecutor`.
  `C/org/bukkit/craftbukkit/v1_7_R4/util/AsynchronousExecutor.java` exists but nothing references it (grep over `M/`, `C/`
  and `patches/` finds only the file itself): dead code.

### Main finding in one paragraph

The chunk lookup that every `getBlock`/`setBlock`/`getTileEntity`/`blockExists`/AABB query goes through
(`ChunkBlockHashMap.get`) **writes shared fields on every read** (a 4-entry most-recently-used cache), and can return
a different chunk than the one it checked. So even a parallel phase where region threads "only read" the world is not
safe until that cache is made per thread. Second, any chunk load triggered from inside a TE/entity tick runs the whole
load pipeline (disk read, entity and TE creation, world list inserts, tracker, mod and Bukkit events, neighbour bits,
population and mod world generators) on the calling thread. Third, the per-world `EntityTracker` and the per-player
queues are written from whatever thread spawns, kills or teleports an entity, and the Thermos "lock" on the tracker
is itself broken.

## 1. Items

### H01. `ChunkBlockHashMap` MRU cache `last1..last4` (a read that writes)

- **Where**: declaration `CBHM:40`. Written on every successful `get` `CBHM:126-131`, on `put` `CBHM:158-163`, on `remove`
  `CBHM:182-202`. Read `CBHM:106-117`.
- **Reached by**: `CPS.chunkExists` `CPS:100`, `getChunkIfLoaded` `CPS:166`, `loadChunk` `CPS:177`, `originalLoadChunk` `CPS:218`,
  `provideChunk` `CPS:308`, `lastAccessed` `CPS:627`; `World.chunkExists` `World:767-770` (so `blockExists` `World:706-709`,
  `getEntitiesWithinAABBExcludingEntity` `World:3952`, `selectEntitiesWithinAABB` `World:3976`), `World.getChunkIfLoaded`
  `World:4906-4908`, `World.getBlock` `World:682` via `getChunkFromChunkCoords` `World:777-780`, `CraftWorld.loadChunk`
  `C/org/bukkit/craftbukkit/v1_7_R4/CraftWorld.java:287`.
- **Kind**: per-world collection (hidden mutable state inside a read path).
- **Who touches it in the region part**: every TE tick, entity tick and block update that reads or writes a block, a
  TE or an entity list. **From outside**: network phase (block placement, digging), plugins, mods, `WatchdogThread` (only `size()`).
- **What breaks**: `get` tests `last1.xPosition == x && last1.zPosition == z` and then executes `return last1` (`CBHM:106-107`),
  reading the field again. If another thread shifts the cache in between (`CBHM:127-130`), the caller receives a **different
  chunk** than the one it asked for: reads return blocks of another chunk, `setBlock` writes into the wrong chunk (block
  appears 16+ blocks away), `getTileEntity` creates a TE in the wrong chunk. `remove` sets `last1 = null` for a moment
  (`CBHM:183`), so a concurrent `get` can NPE between the `!= null` test and the field read (needs an unload in parallel: C).
  The window is tiny, but this is called millions of times per second.
- **Severity**: B **alto**, C **alto**.
- **Treatment**: thread-local (or per-region) MRU cache, or remove the cache; must be done before any parallel phase,
  because it breaks even read-only sharing. Coordinate with Degrau 0, which lists "the Crucible chunk map" as a 3.6% hotspot:
  any optimization there must keep the cache per thread.

### H02. `ChunkBlockHashMap.map` (Koloboke `LongObjMap<Chunk[][]>`), the 16x16 buckets and `size`

- **Where**: `CBHM:12-13`. Writes: `put` `CBHM:135-157` (via `VCHM.add` `VCHM:52-58`), `remove` `CBHM:166-181`. Reads: `get`
  `CBHM:119-124`, `bulkCheck` `CBHM:50-103` (from `World.checkChunksExist` `World:758`), `size` `CBHM:46-48`.
- **Writers at tick time**: `CPS.originalLoadChunk` `CPS:261`, `CIOP.callStage2` `CIOP:46`, `CraftWorld.chunkLoadPostProcess`
  `CraftWorld.java:301`, `CPS.unloadQueuedChunks` `CPS:567`.
- **Kind**: per-world collection.
- **What breaks**: two concurrent `put` (two region threads loading chunks) can lose an entry: the chunk is loaded, in the
  vanilla CHM, in `loadedChunks`, but not findable here, so the next `provideChunk` loads it **again** (two `Chunk` objects,
  TEs and entities duplicated in the world lists). A `put` during a `get` can return null or a wrong bucket. `size++/--`
  are not atomic; `CPS:571` unloads the **whole dimension** when `rawThermos().size() == 0` (and no forced chunks and
  `!shouldLoadSpawn`), so a drifted size can unload a world that still has chunks. Pre-existing: `put` does `size++`
  even when it replaces an occupied slot (`CBHM:138`), so double loads already drift the count upward.
- **Severity**: B **alto** (as soon as any load happens in the parallel phase, see H07), C **alto**.
- **Treatment**: B: no writer during the parallel phase (loads gated, H07); concurrent readers are then safe once H01 is
  fixed. C: per-region ownership of chunk slots plus a lock or concurrent map for structural changes.

### H03. `VanillaChunkHashMap` pair: `ChunkProviderServer.loadedChunkHashMap_KC` / `loadedChunkHashMap`

- **Where**: `CPS:85-86`; `VCHM:11-12` (thermos map + `ConcurrentHashMap vanilla`), `add` `VCHM:52-58` (two independent puts),
  `remove` `VCHM:72-80`, `getValueByKey` `VCHM:67-69` (used by `unloadQueuedChunks` `CPS:527`). `rawVanilla()` iterated by
  `CPS.unloadAllChunks` `CPS:159`, `CraftWorld.getLoadedChunks` `CraftWorld.java:164-174`, `CrucibleCommand.java:236`.
  Mods use the vanilla face directly (comment "vanilla/mystcraft compatibility" `CPS:86`).
- **Kind**: per-world collection.
- **What breaks**: each map is consistent per operation (CHM), but the pair is not atomic: during a load/unload the two
  faces disagree (`chunkExists` false while `getValueByKey` returns the chunk). Wrong answer, no crash.
- **Severity**: B baixo, C medio.
- **Treatment**: no action beyond H02 (once structural writes are serialized, the window is only visible to readers that
  run during a write).

### H04. `ChunkProviderServer.loadedChunks` (`ArrayList`)

- **Where**: `CPS:82`; `add` `CPS:262`, `CIOP:47`, `CraftWorld.java:302`; `remove` `CPS:568` (linear search); read by
  `saveChunks` `CPS:471` (`toArray`), returned by `func_152380_a` `CPS:103-106`. 9 mod jars reference the field
  (`crucible-tick-e-threads.md` §4 table).
- **Kind**: per-world collection.
- **What breaks**: two concurrent `add` lose an element or throw `ArrayIndexOutOfBoundsException` while growing. A chunk
  missing from this list is **never saved by `saveChunks`** (autosave and the shutdown save iterate this list), so its
  changes are lost at shutdown if it was never unloaded. Mods iterating it get `ConcurrentModificationException`.
- **Severity**: B **alto** (with parallel loads), C **alto**.
- **Treatment**: appends only at the join point (loads done in the parallel phase are put in a "pending loaded" queue
  drained by the global thread); C: per-region list plus a global snapshot for mods.

### H05. `ChunkProviderServer.chunksToUnload` (CraftBukkit `LongHashSet`)

- **Where**: `CPS:76`; `remove` on every load `CPS:176`, `CPS:217`, `CraftWorld.java:285`; `add` `CPS:130`, `CPS:145`
  (`unloadChunksIfNotNearSpawn`, called by `PM:495` and `CPS.unloadAllChunks`); forced chunks removed `CPS:517`; `popFirst`
  `CPS:526`. Class `C/org/bukkit/craftbukkit/v1_7_R4/util/LongHashSet.java:24-34` (open addressing, plain arrays, own `modCount`).
- **Kind**: per-world collection.
- **What breaks**: concurrent remove/add/rehash corrupts the arrays: a chunk just loaded by a region thread stays queued
  and is unloaded under its feet, or a chunk is never unloaded (leak).
- **Severity**: B medio (only with parallel loads or mod teleports), C **alto**.
- **Treatment**: only the global thread touches it; a load in the parallel phase records "touched" and the removal is
  applied at the join.

### H06. `ChunkProviderServer.loadingChunks` (`HashSet<Long>`) and the double load of one chunk

- **Where**: `CPS:84`; `add` `CPS:224` (with a warning when already present `CPS:225-228`), `remove` `CPS:263`. Only the
  `originalLoadChunk` path uses it; the `ChunkIOExecutor.syncChunkLoad` path (`CPS:198`) does not.
- **Kind**: per-world collection / re-entrancy assumption.
- **What breaks**: two region threads asking for the same missing chunk (two TEs reading the same remote chunk, or a
  border chunk) both see `chunk == null` (`CPS:189`, `CPS:221`) and both load or generate it. The second insert overwrites
  the first in the maps, both run `onChunkLoad`, so both chunks' TEs and entities enter the world lists: **duplicated
  entities and TEs (item dupes)**, one orphan chunk that ticks but is not saved. The `HashSet` itself can also corrupt.
- **Severity**: B **alto**, C **alto**.
- **Treatment**: single flight per chunk key (one loader, others wait for its future) under the same gate as H07.

### H07. Synchronous chunk load or generation triggered from inside a tick (mechanism)

- **Entry**: `World.getBlock` `World:665-697` (and every other world accessor) -> `getChunkFromChunkCoords` `World:777-780`
  -> `CPS.provideChunk` `CPS:304-342`: a missing chunk is loaded if `shouldLoadChunk()` `CPS:617-623`, i.e.
  `loadChunkOnProvideRequest` (default `true`, `C/io/github/crucible/CrucibleConfigs.java:19`; test copy
  `scratchpad/server-test3/Crucible.yml:6` = `true`). Then `CPS.loadChunk` `CPS:169-213`: if the region file has it,
  `ChunkIOExecutor.syncChunkLoad` `CPS:198` -> `AE.skipQueue` `AE:338-346` runs stage 1 (disk read + NBT decode) and stage 2
  on the caller; otherwise `originalLoadChunk` `CPS:215-302` generates it.
- **What it drags onto the calling (region) thread**:
  - the global `RegionFileCache` monitor and disk I/O (`ACL.chunkExists` `ACL:59-81`, `ACL.loadChunk__Async` `ACL:101-137`) (H25);
  - `ACL.loadEntities` `ACL:476-574`: entity creation (`EntityList.createEntityFromNBT`), `Chunk.addEntity`, TE creation,
    `checkedTileEntities` / `bannedTileEntityUpdates` / `SideTransformer.allowInvalidSide` / `BukkitEnumHelper` (H22),
    scheduled block ticks into the world tick lists `ACL:567` (`mundo.md` M09);
  - `ChunkDataEvent.Load` on `EVENT_BUS` (`CIOP:44`, `ACL:94`);
  - inserts into H02/H03/H04;
  - `Chunk.onChunkLoad` `Chunk:1033-1051` -> `World.func_147448_a` `World:2628-2639` (TEs into `loadedTileEntityList` or
    `addedTileEntityList`, `mundo.md` M04) and `World.addLoadedEntities` `World:4058-4070` (`EntityJoinWorldEvent`,
    `loadedEntityList.add`, `onEntityAdded` -> `WS.entityIdMap` `WS:1131-1143` and `EntityTracker.addEntityToTracker` via
    `M/net/minecraft/world/WorldManager.java:26-29`, H26), `ChunkEvent.Load`;
  - `recreateStructures` (`CPS:364`, `CIOP:52`); Bukkit `ChunkLoadEvent` (`CPS:279`, `CIOP:58`) through the global
    `SimplePluginManager` lock (`crucible-tick-e-threads.md` review);
  - neighbour bitmask of up to 24 chunks (`CPS:283-295`, `CIOP:62-74`), possibly owned by other regions (H19);
  - `Chunk.populateChunk` `Chunk:1182-1205` -> `CPS.populate` `CPS:418-464` for this chunk and up to three neighbours:
    the world generator (H13), `BlockFalling.fallInstantly` and `World.populating` (H12), Bukkit populators and
    `ChunkPopulateEvent`, `GameRegistry.generateWorld` (every mod world generator), which can request more chunks (cascade);
  - lighting of neighbours `Chunk.func_150809_p` `Chunk:1524-1562`.
- **Kind**: re-entrancy / ordering assumption.
- **What breaks**: all the hazards listed, at once, from a region thread. And a deadlock trap: "hand the load to the global
  thread and wait for it" deadlocks in B, because the global thread is parked at the barrier waiting for the regions.
- **Severity**: B **alto**, C **alto**.
- **Treatment**: in B no load may run unguarded in the parallel phase. Two options to decide with Etapa 2 data:
  (a) regions keep a loaded margin equal to the junction radius, and a load requested in the parallel phase is recorded
  and served at the join point (that tick the caller gets the empty chunk, i.e. `loadChunkOnRequest=false` behaviour,
  risky for mods that assume loads succeed); (b) one global "chunk load" gate (single flight, H06) where stage 1 runs in
  place but `onChunkLoad` side effects (world lists, tracker, events, population) are deferred to the join point. Never
  block waiting for the global thread inside the parallel phase. First measure in observer mode how often region ticks
  trigger loads (not measured; not verified per mod).

### H08. `AsynchronousExecutor.tasks` (`HashMap`) and `Task.callbacks` (`LinkedList`) of the `ChunkIOExecutor` singleton

- **Where**: `AE:220`, `AE:78`; singleton for all worlds `M/net/minecraftforge/common/chunkio/ChunkIOExecutor.java:9`.
  Writes: `add` `AE:242-249` (from `CPS.loadChunk(x,z,runnable)` `CPS:193`, i.e. `PlayerManager`), `drop` `AE:266-282`
  (`PM:455`, `PM:461`), `tasks.remove` in `finish` `AE:199` and `drop` `AE:209`. Reads: `skipQueue` `AE:338-346`. The class
  doc says stage 2/3 are synchronous and exceptions are "handled by the main thread" (`AE:27`).
- **Kind**: global singleton.
- **What breaks**: region threads finishing or skipping tasks call `HashMap.get/remove` concurrently -> corrupted table, lost
  task -> its callbacks never run (the `PlayerInstance` never becomes `loaded`, the player never receives that chunk).
  Two threads that `skipQueue` the same chunk while a queued task exists both call `task.get()`; both see
  `STAGE_1_COMPLETE` in `finish` (`AE:169-204`) and both run `callStage2`, i.e. the chunk is inserted twice (same dupe as H06).
  Because it is one object for all dimensions, "one thread per dimension" also hits it.
- **Severity**: B **alto** (with parallel loads), C **alto**.
- **Treatment**: keep on the global thread in B; C: lock (or concurrent map) plus single flight, and stage 2 run by the
  owning region.

### H09. Stage-3 callbacks run on whichever thread finishes the task (cross-thread handoff)

- **Where**: `AE.finish` runs `callStage2` `AE:189` and then `callStage3` for every registered callback `AE:190-197`; `CIOP.callStage3` `CIOP:79-81`. The
  callbacks are `PlayerManager` runnables: `PlayerInstance.loadedRunnable` sets `loaded` (`PM:395-402`), the per-player
  runnable adds to `EntityPlayerMP.loadedChunks` (`PM:434-440`).
- **Kind**: cross-thread handoff.
- **What breaks**: a region thread that sync-loads a chunk some player had queued executes that player's callback, adding
  to the player's `loadedChunks` (`EPMP:135`, `LinkedList`) while the player's own region thread iterates it in
  `EntityPlayerMP.onUpdate` (`EPMP:351-381`) -> CME, or a lost entry (chunk never sent). `loaded` is a plain boolean.
- **Severity**: B medio, C **alto**.
- **Treatment**: defer to a queue applied at the join point: callbacks are always posted to the global thread (or the
  player's region), never run inline.

### H10. `ChunkIOExecutor.tick()` / `finishActive` in the global phase

- **Where**: called at `M/net/minecraft/server/MinecraftServer.java:917`; `AE.finishActive` `AE:351-356`.
- **Kind**: cross-thread handoff (I/O thread -> tick).
- **What breaks**: B: nothing, it stays serial and never overlaps the regions. C: stage 2 inserts into a region's chunk
  map and world lists while that region is ticking.
- **Severity**: B baixo, C **alto**.
- **Treatment**: B no action; C: per-region "finished loads" queue drained by the owning region.

### H11. Chunk unload pipeline and chunk lifetime

- **Where**: `CPS.unloadQueuedChunks` `CPS:508-587` (called in the `chunkSource` phase of `WorldServer.tick`,
  `crucible-tick-e-threads.md` §1.4): `ChunkUnloadEvent`, `Chunk.onChunkUnload` `Chunk:1053-1084` (TEs to
  `World.func_147457_a`, entities to `World.unloadedEntityList` `World:4072-4075`), save, neighbour bits, map removal,
  `ForgeChunkManager.putDormantChunk` `CPS:570`, `DimensionManager.unloadWorld` `CPS:571-574`.
- **Kind**: re-entrancy / ordering assumption.
- **What breaks**: B: nothing if it stays in the serial phase (the design table already says serial). C: a region can still
  hold a `Chunk` reference (an entity or TE of another region, or a mod that cached the `Chunk`) after the chunk was saved
  and removed; writes to that dead object are lost; the dormant copy can come back later with a stale entity list
  (`FCM.fetchDormantChunk` `FCM:908-930`).
- **Severity**: B baixo, C **alto**.
- **Treatment**: B keep on the global thread; C unload only by the owning region at its tick end, with an ownership or
  "generation" check on chunk access.

### H12. Shared mode flags toggled around generation and teleport (save, set, restore)

- **Where**:
  - (a) `ChunkProviderServer.loadChunkOnProvideRequest` (public per-world boolean, `CPS:80`, read `CPS:620`), toggled at
    `M/net/minecraft/entity/Entity.java:2522-2525`, `SCM:1015-1018`, `M/cpw/mods/fml/common/registry/GameRegistry.java:121-122/140`,
    `C/org/bukkit/craftbukkit/v1_7_R4/CraftTravelAgent.java:24-36`, `MinecraftServer.java:468-488`.
  - (b) `World.populating` (`World:166`), set `CPS:441` / `CPS:452`, read `World.notifyBlockChange` `World:1006` (returns
    without notifying neighbours). Also `mundo.md` M23.
  - (c) `BlockFalling.fallInstantly` static (`M/net/minecraft/block/BlockFalling.java:12`), set `CPS:431/456`,
    `ChunkProviderGenerate.java:372/455`, `ChunkProviderHell.java:438/532`, `ChunkProviderEnd.java:363/374`; read
    `BlockFalling.java:50`, `BlockDragonEgg.java:46`.
- **Kind**: (a)(b) per-world flag, (c) static mutable; all are re-entrancy assumptions.
- **What breaks**: interleaved save/restore leaves the flag in the wrong final state, and the "mode" leaks to other threads.
  (a) only matters if the admin sets `loadChunkOnRequest: false`; with production's `true` it toggles true->true, so
  harmless today. (b) while one thread runs Bukkit populators, every `notifyBlockChange` of that world on other threads is
  silently dropped (lost redstone and machine updates). (c) while one thread generates, falling blocks on every other thread
  of every world "fall instantly" (teleported down instead of becoming entities); the generating thread can see `false`
  mid-generation and spawn falling-block entities into ungenerated neighbours.
- **Severity**: (a) B baixo / C baixo; (b) B medio / C medio; (c) B medio / C medio.
- **Treatment**: thread-local flags (b, c) and a thread-local "force load" scope instead of writing (a); moot if all
  generation stays on one thread (H13).

### H13. World generation state reached by chunk generation

- **Where**: per-world generator objects, e.g. `ChunkProviderGenerate` `rand` `M/net/minecraft/world/gen/ChunkProviderGenerate.java:33`,
  noise buffers `:44-46`, structure generators `:47-52`; `MapGenStructure.structureMap` `HashMap`
  (`M/net/minecraft/world/gen/structure/MapGenStructure.java:23`); static `IntCache`
  (`M/net/minecraft/world/gen/layer/IntCache.java:8-12`, methods `static synchronized` `:15`, `:57`, `:75`, but `resetIntCache`
  `:57-72` is called at the start of every `WorldChunkManager` query, `M/net/minecraft/world/biome/WorldChunkManager.java:73,121,160,189,228`);
  `GameRegistry.worldGenerators` (`GameRegistry.java:66`, read) and `configWorldGenCache` `HashMap` (`GameRegistry.java:72`,
  `put` on first use inside `generateWorld` `:126-132`); every mod `IWorldGenerator` (internal state unknown).
- **Called from**: `CPS.originalLoadChunk` `CPS:245` (`currentChunkProvider.provideChunk`), `CPS.populate` `CPS:429`, `CPS:459`.
- **Kind**: per-world singleton (generator) and static mutable (IntCache, GameRegistry).
- **What breaks**: two region threads of the same world generating at once interleave the generator's `Random` and noise
  buffers -> corrupted terrain and structures. `IntCache`: thread A holds array X; thread B's `resetIntCache` moves X back
  to the free list; B's next `getIntCache` hands out X -> both write the same `int[]` -> wrong biomes. `HashMap` corruption
  in `configWorldGenCache` and `structureMap`. Mod generators with static state.
- **Severity**: B **alto**, C **alto**.
- **Treatment**: keep generation on one thread (global phase or one dedicated generation thread fed by a queue); pre-generate
  the map (design §5 already puts world generation outside the regions; Etapa 1 pre-generation).

### H14. `BiomeCache` reached through biome lookups (a read that writes)

- **Where**: `World.getBiomeGenForCoordsBody` `World:242-271`: loaded chunk -> `Chunk.getBiomeGenForWorldCoords`
  `Chunk:1437-1449` (writes `blockBiomeArray` when the byte is 255); not loaded -> `worldChunkMgr.getBiomeGenAt` `World:269`
  -> `WorldChunkManager.getBiomeGenAt` `WorldChunkManager.java:65-67` -> `BiomeCache.getBiomeCacheBlock`
  `M/net/minecraft/world/biome/BiomeCache.java:21-36` (`LongHashMap.add` + `ArrayList.add` on a miss; the new `Block`
  computes biomes through `getBiomeGenAt` -> `IntCache`, `BiomeCache.java:82-88`). `cleanupCache` `BiomeCache.java:44-66`
  from `WS:233`. Also `mundo.md` M31.
- **Kind**: per-world collection.
- **What breaks**: concurrent miss + add corrupts the vanilla `LongHashMap` (resize `M/net/minecraft/util/LongHashMap.java:86-102`)
  and the list; wrong biome returned (rain/snow, spawning, mod logic by biome).
- **Severity**: B medio, C **alto**.
- **Treatment**: per-thread `BiomeCache` or a lock (cheap); IntCache per thread (H13).

### H15. `Chunk.chunkTileEntityMap` (`HashMap(512, 0.9)`)

- **Where**: declaration `Chunk:60`, created `Chunk:116`. Writes: `func_150812_a` `put` `Chunk:998-1004` (from
  `World.setTileEntity` `World:3245-3249`, `Chunk.addTileEntity` `Chunk:960-971` on load); `func_150806_e` (the "get TE" of
  `World.getTileEntity` `World:3187`) **removes invalid entries `Chunk:939` and creates a TE `Chunk:953-954`**;
  `getTileEntityUnsafe` removes `Chunk:1654` (from `World.removeTileEntity` `World:3256`, `Chunk.func_150807_a` `Chunk:653/661`);
  `removeTileEntity` `Chunk:1024`; `removeInvalidTileEntity` `Chunk:1677` (World TE loop `World:2537`, `World:2612`).
  Iterated by: `onChunkLoad` `Chunk:1036`, `onChunkUnload` `Chunk:1056`, player chunk send `EPMP:368`,
  `WS.func_147486_a` `WS:930`, `ACL.writeChunkToNBT` `ACL:385`, `CraftChunk.getTileEntities`
  `C/org/bukkit/craftbukkit/v1_7_R4/CraftChunk.java:178-180`; 28 mod jars reference the field (`crucible-tick-e-threads.md` §4).
- **Kind**: per-chunk.
- **Who**: TEs/entities of the owning region; from outside, any TE of another region that reads a neighbour block's TE
  (pipes, cables, multiblocks across a border), the player chunk send (H30), the network phase, plugins, mods iterating
  the field.
- **What breaks**: iteration concurrent with `put/remove` -> CME. Concurrent `put/remove` -> lost entry: a TE that ticks
  (in the world list) but is not in its chunk is not saved (machine vanishes on reload), or `func_150806_e` finds nothing
  and **creates a second TE** at the same position (duplicated inventory = dupe).
- **Severity**: B medio (needs cross-region access; mitigated by the junction radius), C **alto**.
- **Treatment**: ownership check (observer then check mode) and routing of cross-region TE access to the owner (C);
  in B, junction radius plus violation log; foreign-chunk iteration only at the join point.

### H16. `Chunk.entityLists` (`UnsafeList[16]`)

- **Where**: `Chunk:61`, filled with `UnsafeList` `Chunk:114-124` (`C/org/bukkit/craftbukkit/v1_7_R4/util/UnsafeList.java`).
  Writes: `Chunk.addEntity` `Chunk:823-885` (posts `EntityEvent.EnteringChunk` `Chunk:859`), `removeEntityAtIndex`
  `Chunk:892-925`; callers: chunk crossing in `World.updateEntityWithOptionalForce` `World:2741-2756`, `spawnEntityInWorld`
  `World:1838`, dead-entity removal `World:2476`, `Entity.teleportTo` `Entity.java:2510-2513`, `ACL.loadEntities` `ACL:492/504`.
  Reads: `getEntitiesWithinAABBForEntity` `Chunk:1091-1130` and `getEntitiesOfTypeWithinAAAB` `Chunk:1132-1153` (index
  loops), iterators in `onChunkLoad` `Chunk:1040`, `onChunkUnload` `Chunk:1067`, `ACL.writeChunkToNBT` `ACL:359`,
  `FCM.fetchDormantChunk` `FCM:917-923`; 11 mod jars reference the field.
- **Kind**: per-chunk.
- **What breaks**: `UnsafeList.add/remove` never bump `modCount` (`UnsafeList.java:58-91`), so concurrent change is never
  detected: silent skips and duplicates. `remove` nulls the last slot (`UnsafeList.java:79`): an index-loop reader with a stale
  size reads `null` and NPEs at `entity1.boundingBox` (`Chunk:1110`). Concurrent `growIfNeeded` -> AIOOBE or lost entity.
  `iterator()` hands out **pooled** `Itr` objects (`UnsafeList.java:151-176`): two threads can receive the same iterator
  instance and share its cursor, so even read-only iteration from two threads is unsafe. Result: an entity missing from its
  chunk list (not saved, not found by AABB queries) or in two lists; NPE crash.
- **Severity**: B medio (regions are separated by the junction radius, so crossings stay inside a region; AABB queries
  from neighbours remain), C **alto**.
- **Treatment**: chunk crossing into a foreign chunk deferred to the join point (design §5); AABB queries limited to owned
  chunks (check mode); non-pooled iterators.

### H17. `ExtendedBlockStorage` / `NibbleArray` (block, metadata and light arrays)

- **Where**: `M/net/minecraft/world/chunk/storage/ExtendedBlockStorage.java:13-19`; `func_150818_a` `:74-121` (refcounts
  `--/++`, LSB byte then MSB nibble written separately); `NibbleArray.set` `M/net/minecraft/world/chunk/NibbleArray.java:181-214`
  (Spigot lazy allocation of `data` `:184-199`; read-modify-write of a byte that holds two nibbles `:208-213`). Sections
  created lazily in `Chunk.func_150807_a` `Chunk:631` and `setLightValue` `Chunk:775`.
- **Kind**: per-chunk.
- **Who**: `setBlock`/metadata/light of the owning region; from outside, light spilling from a neighbour region (H18),
  mods writing the raw arrays (`getBlockStorageArray`, 26 jars), the save (serial in B).
- **What breaks** (two writers in one 16x16x16 section): both threads allocate the lazy nibble array and one array, with its
  writes, is lost; two adjacent nibbles in one byte -> one write silently reverted (metadata or light); torn block id
  (LSB from one write, MSB from another) -> a **different block id**; lost refcount update -> `isEmpty()` / `getNeedsRandomTick()`
  wrong (section skipped by random ticks or treated as empty); a section created twice by `Chunk:631` -> every write to the
  losing section lost.
- **Severity**: B medio (cross-border writes; light is the likely one), C **alto**.
- **Treatment**: one writer per chunk (ownership); light spill into foreign chunks deferred to the join point, or junction
  radius of at least 2 chunks.

### H18. Height map, precipitation cache and sky-light bookkeeping in `Chunk`

- **Where**: `heightMap` `Chunk:56`, `precipitationHeightMap` `Chunk:52`, `updateSkylightColumns` `Chunk:53`,
  `isGapLightingUpdated` `Chunk:59`, `heightMapMinimum` `Chunk:69`, `queuedLightChecks` `Chunk:71`. Writes:
  `func_150807_a` `Chunk:606-611`; `relightBlock` `Chunk:437-546` (`heightMap` `:455`, minimum `:530-533`, and light into the four
  neighbouring columns, which can be in adjacent chunks, through `updateSkylightNeighborHeight` `:424-435` -> `World.updateLightByType`
  `:537-541`); `generateSkylightMap` `:274-345`; `propagateSkylightOcclusion` `:347-351`; `recheckGaps` `:353-408`
  (`doChunksNearChunkExist(…,16)` `:357`, neighbour checks `:369-393`), reached per tick from `func_150804_b` `:1238-1251`
  <- `WS:465-468` and via `recheckGapsAsync` `:1685-1698`; `getPrecipitationHeight` fills its cache on read (`:1207-1236`,
  write at `:1232`); `enqueueRelightChecks` `:1466-1522`. Read across chunks: `canBlockSeeTheSky` `:927-930`,
  `getHeightValue` `:207-210` (via `World.getHeightValue`, `checkSkylightNeighborHeight` `:412`).
- **Kind**: per-chunk (with cross-chunk writes).
- **What breaks**: torn or lost height map updates -> wrong sky light, rain/snow placed at wrong height, mobs spawning
  in lit areas; lost cache invalidation (one thread writes -999, another refills with a stale value); two threads
  propagating light into the same neighbour section -> H17 lost nibbles -> persistent dark or bright spots.
- **Severity**: B medio, C **alto**.
- **Treatment**: junction radius >= light reach (one chunk of light plus the 16-block `recheckGaps` check, so 2 chunks);
  the world light queue `World.lightUpdateBlockList` (`World:173`) per thread (`mundo.md` M15).

### H19. `Chunk.neighbors` bitmask (neighbour-loaded cache)

- **Where**: `Chunk:80`; `setNeighborLoaded` `Chunk:101-103` (`|=`) and `setNeighborUnloaded` `Chunk:105-107` (`&=`); written by
  `CPS:283-295` (load), `CPS:554-566` (unload), `CIOP:62-74`; read by `areNeighborsLoaded` `Chunk:83-99`, used in
  `World.updateEntityWithOptionalForce` `World:2657` and `World:4917` (light).
- **Kind**: per-chunk.
- **What breaks**: two threads loading chunks that share a neighbour (up to 2 chunks away) do a racing read-modify-write;
  one bit is lost -> `areNeighborsLoaded(2)` stays false -> entities in that chunk stop ticking (`canUpdate` false) until the
  chunk reloads; or a stale set bit lets entities tick next to unloaded chunks.
- **Severity**: B medio (only with parallel loads), C **alto**.
- **Treatment**: neighbour updates at the join point (B); CAS on an `AtomicInteger` (C).

### H20. Chunk save/dirty flags and per-chunk counters

- **Where**: `isModified` `Chunk:65` (set `Chunk:720`, `:744`, `:779`, `setChunkModified` `:1086-1089` <- `World.markTileEntityChunkModified`
  `World:4020-4026`; cleared `CPS:487`); `lastSaveTime` `Chunk:67` (`CPS:359`, `CPS:401`, `CIOP:45`); `hasEntities` `Chunk:66`
  (`Chunk:825`, `ACL:353/370`); `needsSaving` `Chunk:1155-1170`; `lastAccessedTick` `Chunk:78` (written on every `provideChunk`
  `CPS:339`, `CPS:367`; read `CPS:632-636`); `inhabitedTime` `Chunk:70` (`PM:505-509`); `isTerrainPopulated`/`isLightPopulated`
  (`Chunk:1526-1527`, `CPS:422-424`).
- **Kind**: per-chunk.
- **What breaks**: B: writers store idempotent values (`true`, the same global tick number) and saving is serial ->
  harmless. C: a save of region X's chunk while X writes reads arrays and maps mid-update, then `saveChunks` sets
  `isModified = false` (`CPS:487`) after a concurrent write -> that write is never saved (lost on restart).
- **Severity**: B baixo, C **alto**.
- **Treatment**: B none; C: the owning region saves (or snapshots at its tick end), and `isModified` is cleared before
  serializing (copy, then clear).

### H21. `Chunk.entityCount` (Trove `TObjectIntHashMap`)

- **Where**: `Chunk:72`; `adjustOrPutValue` `Chunk:881`, `adjustValue` `Chunk:921`. No reader found in `M/` or `C/` (grep).
- **Kind**: per-chunk.
- **What breaks**: two threads adjusting the same chunk's map corrupt Trove's internals (exceptions); nothing reads it in
  the base, mods might (public field).
- **Severity**: B baixo, C baixo.
- **Treatment**: no action beyond chunk ownership.

### H22. Side state written by `AnvilChunkLoader.loadEntities`

- **Where**: `checkedTileEntities` (`ArrayList`, per loader = per world) `ACL:51`, `contains/add` `ACL:530/548`;
  `MinecraftServer.bannedTileEntityUpdates.add` `ACL:542` (already in `crucible-arquitetura-patches.md` §4);
  `SideTransformer.allowInvalidSide` static toggled `ACL:535/544` (already listed there); `BukkitEnumHelper.addInventoryType`
  `ACL:547` (not synchronized at 67b53034, per that report's review).
- **Kind**: per-world collection + static mutable.
- **Who**: any thread running a load (H07); `ChunkIOExecutor.tick` (global).
- **What breaks**: concurrent `ArrayList.add` -> AIOOBE or a lost element (only causes a re-check); `bannedTileEntityUpdates`
  grows while every TE add reads it (`CauldronHooks.canUpdate`) -> CME/AIOOBE; `allowInvalidSide` reset by another thread
  mid-check (effect on class loading not verified).
- **Severity**: B medio (with parallel loads), C medio.
- **Treatment**: lock around the TE class check (it runs once per class); concurrent set for `bannedTileEntityUpdates`.

### H23. `AnvilChunkLoader` pending-save list

- **Where**: `chunksToRemove`, `pendingAnvilChunksCoordinates`, `syncLockObject` `ACL:47-49`; every access under the lock
  (`ACL:63-77`, `ACL:107-122`, `ACL:233-250`, `ACL:258-267`).
- **Kind**: cross-thread handoff (tick -> File IO Thread).
- **What breaks**: nothing found: it is already guarded; linear scans under the lock only add contention.
- **Severity**: B baixo, C baixo.
- **Treatment**: no action needed.

### H24. `ThreadedFileIOBase` queue and counters (global singleton)

- **Where**: `M/net/minecraft/world/storage/ThreadedFileIOBase.java:9-13`; `queueIO` `:65-72` (`contains` then `add`, and
  `++writeQueuedCounter` on a `volatile`, not atomic); `waitForFinish` `:74-84` (spins until the two counters are equal);
  `processQueue` `:29-63` (File IO Thread). Called from `ACL.addChunkToPending` `ACL:249` under the per-world lock, so two
  worlds do not exclude each other.
- **Kind**: global singleton / cross-thread handoff.
- **What breaks**: two threads saving chunks of different worlds at once lose an increment of `writeQueuedCounter`; later
  `savedIOCounter` passes it and the `!=` loop in `waitForFinish` never ends -> hang on `save-all` / stop. Duplicate queue
  entries are harmless.
- **Severity**: B baixo (saving stays serial), C medio (per-region saving).
- **Treatment**: `AtomicLong` and a synchronized `queueIO`, or keep every save call on the global thread.

### H25. `RegionFileCache` / `RegionFile` (static, synchronized, but a file can be closed under a reader)

- **Where**: `REGIONS_BY_FILE` `M/net/minecraft/world/chunk/storage/RegionFileCache.java:18` (access-ordered `LinkedHashMap`,
  capacity `crucible_chunkCacheSize` = 256, `CrucibleConfigs.java:144`); lookups `static synchronized` `:21-126`; but
  `getChunkInputStream` `:128-132`, `getChunkOutputStream` `:134-138`, `chunkExists` `:148-152` use the `RegionFile` after
  leaving the class lock; `deleteOldestReference` `:57-75` closes the evicted file; `RegionFile.close`
  `M/net/minecraft/world/chunk/storage/RegionFile.java:303-308` is not synchronized; `getChunkDataInputStream` `:113-165`
  returns `null` on `IOException`.
- **Kind**: global singleton.
- **What breaks** (pre-existing between the Chunk I/O, File IO and tick threads; more loader threads make it likelier):
  thread A takes region file R; thread B's lookup evicts and closes R; A reads a closed file -> `IOException` -> `null` ->
  the chunk is treated as absent -> `CIOP.callStage2(null)` falls back to `originalLoadChunk` `CIOP:39`; if the retry also
  fails, the chunk is **regenerated** and later saved over the real data. Also one global monitor for all region lookups.
- **Severity**: B baixo (no loads in parallel per H07; otherwise medio), C medio.
- **Treatment**: B none if H07 holds; C: reference-count open region files or evict only from one thread.

### H26. `EntityTracker.trackedEntities` / `trackedEntityIDs` and the Thermos `trackerLock`

- **Where**: `ET:50-53`. `addEntityToTracker(Entity)` `ET:94-215` (player branch iterates all entries under the lock
  `ET:106-117`); `addEntityToTracker(…, 4 args)` `synchronized` `ET:222-288` (set add under the lock `ET:242-244`,
  `trackedEntityIDs.addKey` outside it `ET:246`, `sendEventsToPlayers` `ET:247`); `removeEntityFromAllTrackingPlayers`
  **not synchronized** `ET:290-313` (`trackedEntityIDs.removeObject` `ET:304` without any lock); `func_151247_a` /
  `func_151248_b` `ET:348-366` (unlocked `IntHashMap` lookup); `updateTrackedEntities` `ET:315-346`; `func_85172_a`
  `ET:379-390`. `waitForLock` `ET:63-83`: busy-waits while `isLocked()` (true even when the current thread owns it),
  sleeps 100 ms per check, after 100 checks calls `unlock()`, which throws `IllegalMonitorStateException` when another
  thread owns the lock; the exception is swallowed by the `catch` and the loop spins without sleeping. `unlock` `ET:88-92`
  checks `isLocked()`, not ownership.
- **Who in the region part**: entity spawn (`World.spawnEntityInWorld` `World:1840` -> `onEntityAdded` `World:1846-1854` ->
  `WorldManager.onEntityCreate` `WorldManager.java:26-29`); chunk loads (H07); entity death in the entity loop (`World:2481` ->
  `onEntityRemoved` `World:1856-1864` -> `WorldManager.onEntityDestroy` `WorldManager.java:31-34`); status and animation packets
  (`WS:1195`, `EntityLivingBase.java:1537`, `:1930`, `EntityLiving.java:1138`, `:1165`, `EPMP:758`, `:775`, `:1238`, `:1293`, `:1298`);
  player chunk send `EPMP:396` -> `func_85172_a`. From outside: tracker update after the world tick (serial), logout
  (`SCM:725`), plugins.
- **Kind**: per-world collection.
- **What breaks**: `trackedEntityIDs` written by two threads (add under `synchronized(this)`, remove under nothing) ->
  corrupted buckets -> an entity is never untracked (ghost on clients) or hits the "already tracked" re-add path `ET:232-237`.
  Lock contention turns into **100 ms sleeps** in `waitForLock` for region threads; a re-entrant call from the thread that
  holds the lock waits 10 s and then steals it; the swallowed `IllegalMonitorStateException` can spin a core.
- **Severity**: B **alto**, C **alto**.
- **Treatment**: defer tracker add/remove to a per-world queue applied at the join point, before `updateTrackedEntities`
  (serial); replace the Thermos lock with nothing (serial) or a plain lock. Packets for entities created this tick then go
  out at the join (see H37 for ordering).

### H27. `EntityTrackerEntry.trackingPlayers` (`HashSet`)

- **Where**: `ETE:93` (plus position cache fields `ETE:74-92`); iterated by `func_151259_a` `ETE:368-377` (called from region
  threads through `ET.func_151247_a`), `informAllAssociatedPlayersOfItemDestruction` `ETE:389-398`; modified by
  `tryStartWachingThis` (add `ETE:432`), `removeFromWatchingList` `ETE:400-407`, `removePlayerFromTracker` `ETE:721`.
  Forge `getTrackingPlayers` returns a live unmodifiable view `ET:400-407` that mods iterate.
- **Kind**: per-entity collection inside a per-world structure.
- **What breaks**: a region thread sending an animation iterates the set while another thread modifies it (tracker update,
  or `removeEntityFromAllTrackingPlayers(player)` on a region thread when a player is removed) -> CME; same for mods
  holding the view.
- **Severity**: B medio (modifications are mostly in the serial tracker phase), C **alto**.
- **Treatment**: all modifications in the serial tracker phase (covered by the H26 deferral); reads in the parallel phase
  are then safe.

### H28. `EntityPlayerMP.destroyedItemsNetCache` (`LinkedList`)

- **Where**: `EPMP:136`; `add` in `func_152339_d` `EPMP:1404-1414` (called by `ETE.removeFromWatchingList` `ETE:404` and
  `ETE.informAllAssociatedPlayersOfItemDestruction` `ETE:396`); `remove` in `ETE.tryStartWachingThis` `ETE:430`; drained
  with `iterator.remove()` in `EntityPlayerMP.onUpdate` `EPMP:326-339` (the player's region thread); `addAll` on clone `EPMP:1249`.
- **Kind**: per-player collection, cross-thread handoff.
- **What breaks**: an entity dies in region B (tracker removal runs on B, H26) and adds its id to player P's list while
  P's region A drains it -> CME in P's tick, or a lost id -> the client keeps a ghost entity forever. Tracking range
  (64-512 blocks) is larger than a 256-block section, so cross-region tracking is common.
- **Severity**: B **alto**, C **alto**.
- **Treatment**: covered by deferring tracker removal to the join point (H26); otherwise a concurrent queue.

### H29. `EntityPlayerMP.loadedChunks` (`LinkedList`, the per-player "chunks to send" queue)

- **Where**: `EPMP:135`; written by `PM.filterChunkLoadQueue` `PM:183-235` (clear + re-add), `PlayerInstance.addPlayer` `PM:430`
  and its callback `PM:438` (H09), `removePlayer` `PM:479`, sort `PM:318`; iterated in `EntityPlayerMP.onUpdate` `EPMP:342-381`
  (with `iterator.remove()`); read by `PM.isPlayerWatchingChunk` `PM:327`, `sendToAllPlayersWatchingChunk` `PM:546`.
- **Kind**: per-player collection.
- **What breaks**: a stage-3 callback on a region thread (H09) or a mod teleport from a TE tick (H35, which calls
  `PlayerManager.addPlayer`) mutates it while the player's region iterates -> CME, or a lost entry -> chunk never sent
  (hole in the client's world).
- **Severity**: B medio, C **alto**.
- **Treatment**: only the global thread (or the player's owning region) touches it; callbacks queued (H09).

### H30. Player chunk sending inside the region entity loop (`EntityPlayerMP.onUpdate`)

- **Where**: `EPMP:308-405`, called by `World.updateEntityWithOptionalForce` (`onUpdate()` at `World:2706`), i.e. inside the
  entity loop of the region that owns the player. It walks `loadedChunks` up to the view distance (clamped 3-20 by
  `PM:332`): `blockExists` (H01), `getChunkFromChunkCoords`, iterates `chunk.chunkTileEntityMap` `EPMP:368`, builds
  `S26PacketMapChunkBulk`, which copies block and light arrays in its constructor
  (`M/net/minecraft/network/play/server/S26PacketMapChunkBulk.java:45-…`), sends each TE description packet
  `EPMP:388-391` (mod code of a foreign TE), calls `EntityTracker.func_85172_a` `EPMP:396` (takes `trackerLock`, H26) and
  posts `ChunkWatchEvent.Watch` `EPMP:397`. Also `openContainer.detectAndSendChanges()` `EPMP:318`, whose container can
  belong to a TE in another region.
- **Kind**: re-entrancy / ordering assumption (player tick reads the whole view area).
- **What breaks**: the view area spans other regions: torn chunk data sent to the client (wrong blocks until the next
  update), CME iterating a foreign chunk's TE map (H15), foreign TE state read mid-tick by mod code, tracker lock contention
  (100 ms sleeps).
- **Severity**: B **alto**, C **alto**.
- **Treatment**: move the chunk-send block (and the `ChunkWatchEvent`) out of the parallel phase into the serial
  chunk-map phase at the join point; in C, the owning region produces a snapshot of the chunk data for the player's region.

### H31. `PlayerManager` per-world maps

- **Where**: `players` `PM:30`, `playerInstances` (vanilla `LongHashMap`) `PM:31`, `chunkWatcherWithPlayers` and
  `playerInstanceList` (`ConcurrentLinkedQueue`) `PM:32-33`, `playerViewRadius` `PM:34`, `previousTotalWorldTime` `PM:35`.
  `getOrCreateChunkWatcher` `PM:114-127` (read, create, add); removals `PM:462-464`, `PM:485-488`; `players` add `PM:179`,
  remove `PM:255`. Read by `markBlockForUpdate` `PM:143-153` on **every block change** (`World.markBlockForUpdate`
  `World:995-1001` -> `WorldManager.markBlockForUpdate` `WorldManager.java:48-51`), by `isChunkInUse` `PM:130-140`
  (`CraftWorld.isChunkInUse` `CraftWorld.java:272-276`, chunk GC) and `func_152621_a` `PM:108-112`. `updatePlayerInstances`
  `PM:58-106` runs in the serial chunk-map phase (`WS:287`) and can call `unloadAllChunks` `PM:103`.
- **Kind**: per-world collection. `LongHashMap` (`M/net/minecraft/util/LongHashMap.java:5-9`, resize `:86-102`) is not
  thread-safe.
- **Writers in the parallel phase**: only player add/remove/move: mod teleports from TE ticks (`SCM.func_72375_a`
  `SCM:297-308`), logout through a kick on a region thread (H34).
- **What breaks**: a reader during a resize gets `null` (block change never sent -> ghost block) or a wrong instance; two
  writers lose a `PlayerInstance` (chunk never sent or never released).
- **Severity**: B medio, C **alto**.
- **Treatment**: writes only in the serial phase (defer teleports and logouts, H34/H35); C: per-region slice or a
  concurrent map.

### H32. `PlayerInstance` block-change buffer

- **Where**: `locationOfBlockChange`, `numberOfTilesToUpdate`, `flagsYAreasToUpdate` `PM:390-393`; `flagChunkForUpdate`
  `PM:511-538` (dedupe loop, array growth `PM:532-535`, increment `PM:536`, enqueue `PM:513-516`); `sendChunkUpdate`
  `PM:554-623` (serial) resets at `PM:620-621`.
- **Kind**: per-chunk.
- **What breaks**: two threads flagging changes in the same chunk -> lost entry, AIOOBE during growth (ghost block on
  clients); in C, a flag concurrent with `sendChunkUpdate` is wiped by the reset.
- **Severity**: B baixo (same chunk = same region), C **alto**.
- **Treatment**: B none (ownership); C: chunk-map phase per region.

### H33. `PlayerInstance` watchers

- **Where**: `playersWatchingChunk` `PM:388` (`ArrayList`), `players` `PM:394` (`HashMap`), `loaded` `PM:395`; `addPlayer`
  `PM:411-446`, `removePlayer` `PM:448-498` (sends `S21PacketChunkData` `PM:474`, posts `ChunkWatchEvent.UnWatch` `PM:481`,
  calls `unloadChunksIfNotNearSpawn` `PM:495` -> H05), `loadedRunnable` `PM:396-402` (H09); read by
  `sendToAllPlayersWatchingChunk` `PM:540-551`, `isChunkInUse` `PM:136`, `isPlayerWatchingChunk` `PM:327` (used by
  `ETE.tryStartWachingThis`).
- **Kind**: per-chunk.
- **What breaks**: same writers as H31; CME / lost watcher.
- **Severity**: B baixo, C **alto**.
- **Treatment**: as H31.

### H34. `ServerConfigurationManager.playerEntityList` and logout executed in place by a kick

- **Where**: `SCM:112`; `add` `SCM:366`, `SCM:906`; `remove` `SCM:459`, `SCM:727`, `SCM:1698`. Readers from region threads:
  `sendToAllNearExcept` `SCM:1508-1537` (sounds and effects: `WorldManager.playSound` `WorldManager.java:35-38`,
  `playSoundToNearExcept` `:40-43`, `playAuxSFX` `:57-60`), `sendPacketToAllPlayers` `SCM:1255-1261` (`broadcastSound`
  `WorldManager.java:62-65`), `sendPacketToAllPlayersInDimension` `SCM:1263-1274`, `WorldManager.destroyBlockPartially`
  `WorldManager.java:67-87` (uses an `Iterator`), `func_152612_a` `SCM:1353` (player by name); 46 mod jars reference the
  field. **Writer on a region thread**: `NetHandlerPlayServer.kickPlayerFromServer`
  (`M/net/minecraft/network/NetHandlerPlayServer.java:260-291`) calls `onDisconnect` `:940-975` "immediately" (`:289`) ->
  `SCM.disconnect` `SCM:432-470`: `playerEntityList.remove`, `world.removeEntity`, `PlayerManager.removePlayer`,
  `ChunkIOExecutor.adjustPoolSize`, player data write, `PlayerQuitEvent`, all on the calling thread (a mod or plugin
  kicking from a TE tick or from an event handler fired in the region). Async chat also iterates this list
  (`crucible-tick-e-threads.md` §2; fix `ac070ce` is not in production).
- **Kind**: global singleton list.
- **What breaks**: CME in `destroyBlockPartially`, `IndexOutOfBounds` or a skipped player in index loops.
- **Severity**: B medio (only via kicks or teleports from region threads), C **alto** (logins concurrent with regions).
- **Treatment**: snapshot-iterating list (as `ac070ce`) plus deferring kick/disconnect processing to a queue applied by the
  global thread at the join point.

### H35. Cross-world transfer from a tick (`Entity.travelToDimension/teleportTo`, mod calls to `transferPlayerToDimension`)

- **Where**: portal counter in the entity tick `Entity.java:443-462` -> `travelToDimension` `Entity.java:2469-2499`
  (`EntityPortalEvent` `:2489-2491`, `CraftTravelAgent.findOrCreate` on the target world's `Teleporter`, whose
  `destinationCoordinateCache` is a vanilla `LongHashMap`, `M/net/minecraft/world/Teleporter.java:25`) -> `teleportTo`
  `Entity.java:2501-2547` (removes from source chunk and list `:2508-2513`, toggles target `loadChunkOnProvideRequest`
  `:2522-2525`, `SCM.repositionEntity` `SCM:1195-1237` -> target `spawnEntityInWorld` + `updateEntityWithOptionalForce`,
  `resetUpdateEntityTick` on both worlds `:2543-2544`). `MinecraftServer.worldServerForDimension`
  `MinecraftServer.java:1069-1085` hot-loads a missing dimension through `DimensionManager.initDimension`. Mods call
  `SCM.transferPlayerToDimension` `SCM:955-1042` directly (comment `SCM:945`), possibly from TE ticks: both worlds'
  `PlayerManager` (`func_72375_a` `SCM:297-308`), `removePlayerEntityDangerously`, trackers, a `loadChunk` in the target,
  FML `PlayerChangedDimensionEvent`. For players, nether-portal travel runs from `onUpdateEntity`, which is called from the
  network phase (`NetHandlerPlayServer.java:416`, `:445`, `:488`), so only non-player entities and mod-triggered teleports
  reach this from region ticks. Also `mundo.md` M27 (Teleporter caches).
- **Kind**: re-entrancy / ordering assumption; cross-world.
- **What breaks**: every hazard of this report in the **target** world from a foreign thread (the target may be ticking on
  another thread when dimensions run in parallel); dimension creation in the middle of the parallel phase.
- **Severity**: B **alto**, C **alto**.
- **Treatment**: defer to the join point (design §5): mark the entity "in transit", remove it from the source at the join,
  add it to the target at the join or as a task of the target region.

### H36. Player statistics

- **Where**: `SCM.field_148547_k` (`HashMap`, `SCM:142`), get/put `SCM:1725-1752` (put at player construction `EPMP:200`,
  i.e. login, serial); `remove` `SCM:460` uses the player name while the key is the UUID (pre-existing leak, not a threading
  issue); `StatisticsFile.field_150888_e` (`HashSet`, `M/net/minecraft/stats/StatisticsFile.java:34`), added in
  `func_150873_a` `:77-92`; the stat values map is concurrent (`M/net/minecraft/stats/StatFileWriter.java:14`).
- **Kind**: per-player collection.
- **What breaks**: a stat credited to a player from another region's thread (kill by a projectile or pet in another region,
  achievement) concurrent with the player's own region -> `HashSet` corruption (dirty mark lost, stat not synced); the
  achievement broadcast iterates `playerEntityList` (H34).
- **Severity**: B baixo, C medio.
- **Treatment**: B none; C: stat updates queued to the player's owner.

### H37. `NetworkManager` outbound path used from region threads (ordering)

- **Where**: queues `M/net/minecraft/network/NetworkManager.java:62-63` (`ConcurrentLinkedQueue`); `scheduleOutboundPacket`
  `:157-169`; `dispatchPacket` `:171-207` (off the event loop each packet becomes a `Runnable` on the channel's event loop
  `:193-206`); `flushOutboundQueue` `:209-219`. `NetHandlerPlayServer.sendPacket` `NetHandlerPlayServer.java:977-1025`
  (also writes `playerEntity.compassTarget` on `S05` `:1000-1004`).
- **Kind**: cross-thread handoff.
- **What breaks**: thread safe per packet, but packets from different threads to the same player interleave in an order
  that no longer matches game order (Netty keeps FIFO per submitting thread only): entity metadata or animation before the
  spawn packet, inventory updates out of order -> client drops or shows stale state. Two threads in `flushOutboundQueue`
  (only while the channel is not open yet) reorder queued packets. Packets that reference live objects are encoded later
  on the Netty thread (pre-existing, unchanged).
- **Severity**: B medio, C medio.
- **Treatment**: accept, but give tracker-related packets a defined order (H26 deferral); if testing shows client desync,
  per-player outbound buffer flushed at the join point.

### H38. `NetworkManager.receivedPacketsQueue` and packet processing

- **Where**: `NetworkManager.java:62`; filled by Netty `:145`; drained in `processReceivedPackets` `:221-…` inside
  `networkTick` (serial; lock on `networkManagers` already described in `crucible-tick-e-threads.md` §2/§6).
- **Kind**: cross-thread handoff (Netty -> tick).
- **What breaks**: B nothing (network phase stays serial). C: a player's packets touch any region (placement, containers,
  teleports), so processing must run on the player's region and route out-of-region effects.
- **Severity**: B baixo, C **alto**.
- **Treatment**: B none; C route per player region.

### H39. `ForgeChunkManager` static ticket state

- **Where**: `FCM:75-99`: `tickets` (`MapMaker().weakKeys()` concurrent outer map, values `ArrayListMultimap` `FCM:401-402`,
  not thread-safe), `playerTickets` `HashMultimap` `FCM:83`, `forcedChunks` `FCM:87` (immutable values, replaced
  copy-on-write), `dormantChunkCache` `FCM:90` (Guava `Cache`, thread-safe), `warnedMods` `HashSet` `FCM:99`,
  `Ticket.requestedChunks` `LinkedHashSet` `FCM:210`. Mutators callable from TE/entity ticks: `requestPlayerTicket`
  `FCM:628-645`, `requestTicket` `FCM:654-686`, `releaseTicket` `FCM:691-720`, `forceChunk` `FCM:727-752`, `reorderChunk`
  `FCM:762-770`, `unforceChunk` `FCM:777-789`; `ForceChunkEvent`/`UnforceChunkEvent` constructors write `world.activity`
  (`ConcurrentHashMap`, `World:4839`) `FCM:351-356`, `FCM:362-368`; `EVENT_BUS` posts `FCM:739`, `FCM:783`. Readers:
  `World.getPersistentChunks` `World:4784-4787` (used `World:2653`, `World:3465`), `CPS:515`, `CPS:571`.
- **Kind**: static mutable.
- **What breaks**: `forceChunk` reads `forcedChunks.get(world)`, builds a new map and puts it (`FCM:745-746`) with no lock;
  `unforceChunk` the same (`FCM:785-788`). Two threads forcing chunks in one world at once lose one update: a chunk the
  loader believes forced unloads (machines stop) or a released chunk stays forced (leak). Concurrent request/release on the
  multimaps -> lost ticket (chunk loader dead after restart).
- **Severity**: B medio (how often mods call these inside ticks is not measured), C **alto**.
- **Treatment**: one global lock around the FCM mutators (rare and cheap calls; keeps the API synchronous for mods).

### H40. Cross-chunk queries from a tick: `getEntitiesWithinAABB*`, `WorldServer.func_147486_a`, `checkChunksExist`

- **Where**: `World.getEntitiesWithinAABBExcludingEntity` `World:3938-3958`, `selectEntitiesWithinAABB` `World:3965-3985`
  (box widened by the static `MAX_ENTITY_RADIUS`, `World:124`, which mods may raise); `WS.func_147486_a` `WS:913-…` (uses
  `getChunkFromChunkCoords` `WS:923`, so it can **load** chunks); `World.checkChunksExist` `World:716-763`.
- **Kind**: mechanism (reads that cross region borders).
- **What breaks**: a box crossing into another region reads that region's `entityLists` and TE maps while it ticks
  (H15/H16); `func_147486_a` can trigger H07.
- **Severity**: B medio (depends on junction radius versus query size), C **alto**.
- **Treatment**: ownership check in observer mode to measure query reach; size the junction radius from that data (and
  from the runtime value of `MAX_ENTITY_RADIUS`).

### H41. Chunk-related Forge events fired from region threads

- **Where**: `EntityEvent.EnteringChunk` `Chunk:859` (on **every** chunk crossing, inside the entity tick),
  `ChunkEvent.Load` `Chunk:1050` / `Unload` `Chunk:1083`, `ChunkDataEvent.Load` (`ACL:94`, `CIOP:44`) / `Save` (`ACL:220`),
  `ChunkWatchEvent.Watch` `EPMP:397` / `UnWatch` `PM:481`, `ForceChunkEvent` / `UnforceChunkEvent` (`FCM:739`, `FCM:783`),
  `EntityJoinWorldEvent` `World:4064`. No listener for `EnteringChunk` in the base (grep); mod listeners not inventoried.
- **Kind**: re-entrancy / ordering assumption.
- **What breaks**: listeners of several mods run concurrently from several region threads; mods commonly keep per-chunk
  `HashMap`s in such listeners (not verified per mod in this pack).
- **Severity**: B medio (listener set unknown), C **alto**.
- **Treatment**: static scan of listeners per event (same method as `docs/dados/inventario-mods.csv`); events with
  listeners of non-cleared mods are deferred to the join point or run under one "mod event" lock.

### H42. CraftBukkit chunk API used by plugins from region threads

- **Where**: `CraftWorld.loadChunk` `CraftWorld.java:278-297` (a second load path: `safeLoadChunk` + `chunkLoadPostProcess`
  `:299-322` -> map, list, `onChunkLoad`, `populate`, without `loadingChunks` and without `ChunkIOExecutor`),
  `chunkLoadCount++` `:279`, `getLoadedChunks` `:164-174`, `isChunkInUse` `:272-276`; `CraftChunk.getTileEntities`
  `CraftChunk.java:178-180`.
- **Kind**: re-entrancy (plugin event handlers fired in a region run under the Bukkit `callEvent` lock, which serializes
  plugins among themselves but not against region threads).
- **What breaks**: same as H02/H04/H06/H07 through a path that bypasses the single-flight guard.
- **Severity**: B **alto** when plugin handlers load chunks, C **alto**.
- **Treatment**: the same gate as H07 for every load path (`provideChunk`, `loadChunk`, `CraftWorld.loadChunk`).

## 2. Second pass: fields checked and judged harmless (one line each)

Method: listed every class-level field of `ChunkProviderServer`, `Chunk`, `EmptyChunk`, `ExtendedBlockStorage`, `NibbleArray`,
`AnvilChunkLoader`, `RegionFileCache`, `RegionFile`, `ThreadedFileIOBase`, `LongHashMap`, `ChunkBlockHashMap`,
`VanillaChunkHashMap`, `LongHashMapTrove`, `ThermiteMap`, `PlayerManager`(+`PlayerInstance`), `EntityTracker`,
`EntityTrackerEntry`, `EntityPlayerMP` (collections and chunk/tracker fields), `ServerConfigurationManager`, `NetworkManager`,
`NetHandlerPlayServer` (statics and collections), `ForgeChunkManager` (statics), `ChunkIOExecutor`, `ChunkIOProvider`,
`QueuedChunk`, `AsynchronousExecutor`; decided for each whether tick code touches it. Items above are excluded here.

- `CPS.logger` `CPS:75` static final logger: thread-safe.
- `CPS.defaultEmptyChunk` `CPS:77`: shared `EmptyChunk`, all mutators are no-ops (`M/net/minecraft/world/chunk/EmptyChunk.java:35-107`); only `getPrecipitationHeight`/`getBiomeGenForWorldCoords` lazily write its arrays with the same value every time: benign.
- `CPS.currentChunkProvider`, `currentChunkLoader`, `worldObj` `CPS:78-79`, `CPS:83`: set once in constructor `CPS:89-96`.
- `CPS.initialTick` `CPS:81`: set once; read in `shouldLoadChunk` `CPS:622`.
- `Chunk.logger` `Chunk:48`: static final.
- `Chunk.isLit` `Chunk:49` static boolean: written in `getBlockLightValue` `Chunk:808`, never read on the server (grep): harmless.
- `Chunk.storageArrays` reference `Chunk:50`: replaced only on load (`setStorageArrays` `Chunk:1289-1292` from `ACL:465`) before the chunk is published; the array contents are H17.
- `Chunk.blockBiomeArray` `Chunk:51`: per chunk; lazily filled with deterministic values (`Chunk:1445`): benign race; the source of the value is H14.
- `Chunk.isChunkLoaded` `Chunk:55`: set in `onChunkLoad`/`onChunkUnload` (serial in B; part of H11 in C).
- `Chunk.worldObj`, `xPosition`, `zPosition` `Chunk:56-58`: final or set once.
- `Chunk.field_150815_m`, `sendUpdates` `Chunk:64`, `Chunk:68`: idempotent flags; `func_150802_k` always returns true `Chunk:1253-1257`.
- `Chunk.pendingLightUpdates` `Chunk:74` (`AtomicInteger`), `lightUpdateTime` `Chunk:75`: only async lighting (off by default): no change.
- `Chunk.bukkitChunk` `Chunk:136`: set in constructor.
- `Chunk.mustSave` `Chunk:137`: written `CPS:135`, `CPS:150`, never read (grep).
- `ExtendedBlockStorage.yBase` `ExtendedBlockStorage.java:12`: set once.
- `NibbleArray.TrivLen2k` static `NibbleArray.java:17-21`: shared read-only arrays, written only in the static initializer.
- `NibbleArray.depthBits/depthBitsPlusFour` `NibbleArray.java:8-9`: final.
- `ACL.logger` `ACL:46`, `ACL.chunkSaveLocation` `ACL:50`: immutable.
- `ACL.PendingChunk` fields `ACL:578-579`: final.
- `RegionFile.offsets/chunkTimestamps/sectorFree/dataFile` `RegionFile.java:24-32`: all accesses inside `synchronized` methods except `close` (H25).
- `RegionFile.FORGE_ENABLE_EXTENDED_*`, `EMPTY_SECTOR` `RegionFile.java:19-22`: static final config.
- `ThreadedFileIOBase.savedIOCounter`, `isThreadWaiting` `ThreadedFileIOBase.java:12-13`: single writer each (IO thread / saver).
- `LongHashMap.modCount` `LongHashMap.java:9` volatile: not used for checks; the map is H14/H31.
- `LongHashMapTrove` and `ThermiteMap` (`C/thermos/wrapper/LongHashMapTrove.java`, `ThermiteMap.java`): never instantiated (grep in `M/`, `C/`, `patches/`).
- `VCHM.notRealFace`, `chunkt_TH`, `vanilla` `VCHM:11-13`: final or set in constructor.
- `PM.xzDirectionsConst` `PM:36`: final constant array, read-only.
- `PM.theWorldServer` `PM:29`: final.
- `PlayerInstance.chunkLocation` `PM:389`: final.
- `PlayerInstance.previousWorldTime` `PM:393`: written in `addPlayer`/`increaseInhabitedTime` (serial phases).
- `ET.theWorld` `ET:49`, `entityViewDistance` `ET:54`: set once (view distance changes only by command, serial).
- `ETE` position cache fields `ETE:74-92`: written only by `sendLocationToAllClients` in the serial tracker phase.
- `ETE.myEntity` `ETE:71`: set once.
- `EPMP.managedPosX/Z` `EPMP:133-134`: written by `PlayerManager` (serial phases).
- `EPMP.field_147103_bO` (stats file reference) `EPMP:137`: final.
- `SCM` static `File`s and `dateFormat` `SCM:105-110`: constants (`SimpleDateFormat` is not thread-safe, but only used for ban entries in command/login code, serial).
- `SCM.bannedPlayers`, `bannedIPs`, `ops`, `whiteListedPlayers` `SCM:113-116`: touched by login and commands (serial in B; C: global thread).
- `SCM.maxPlayers`, `viewDistance`, `gameType`, `commandsAllowedForAll`, `whiteListEnforced` `SCM:119-123`: config, written by commands (serial).
- `SCM.playerPingIndex` `SCM:124`: only `sendPlayerInfoToAllPlayers` (serial, `crucible-tick-e-threads.md` §1.3 i).
- `NetworkManager` static markers, attribute keys, `eventLoops`, `field_152462_h` `NetworkManager.java:53-60`: static final, Netty-managed.
- `NetHandlerPlayServer.field_147376_j` static `Random` `NetHandlerPlayServer.java:167`: `java.util.Random` is thread-safe (contended only); used in network phase.
- `NetHandlerPlayServer.chatSpamThresholdCount` `:169` volatile + `AtomicIntegerFieldUpdater` `:170`: already made thread-safe.
- `NetHandlerPlayServer.field_147372_n` (`IntHashMap`) `:172` and the movement fields `:162-166`, `:195-200`: only network phase (serial in B; C: player's region).
- `FCM.defaultMaxCount`, `defaultMaxChunks`, `overridesEnabled`, `ticketConstraints`, `chunkConstraints`, `callbacks`, `cfgFile`, `config`, `playerTicketLength`, `dormantChunkCacheSize`, `MOD_PROP_ORDER` `FCM:75-97`: written at startup or config reload only; read in ticks.
- `FCM.pendingEntities` `FCM:88`: only during world load of tickets (`FCM:399-…`, serial).
- `FCM.dormantChunkCache` `FCM:90`: Guava cache, thread-safe per operation (its use in `fetchDormantChunk` calls `Entity.resetEntityId`, see `mundo.md` M19).
- `ChunkIOExecutor.BASE_THREADS`, `PLAYERS_PER_THREAD` `ChunkIOExecutor.java:6-7`: constants; `adjustPoolSize` `:24-27` calls `ThreadPoolExecutor.setCorePoolSize` (thread-safe).
- `ChunkIOProvider.threadNumber` `CIOP:15` (`AtomicInteger`): thread-safe.
- `QueuedChunk` fields `M/net/minecraftforge/common/chunkio/QueuedChunk.java:5-9`: final, except `compound` written by stage 1 and read by stage 2 after the task state CAS (happens-before through the `volatile state`, `AE:75`).
- `AE.finished` `AE:219` (`ConcurrentLinkedQueue`), `AE.pool` `AE:221/234` (`ThreadPoolExecutor`), `Task.state` `AE:75` (volatile + CAS): thread-safe.
- `S21PacketChunkData.field_149286_i` static buffer `S21PacketChunkData.java:28`: used only in `readPacketData` (client side) `:98-124`.
- `S26PacketMapChunkBulk.localDeflater` `S26PacketMapChunkBulk.java:31-41`: `ThreadLocal`, safe from any thread.
- `World.MAX_ENTITY_RADIUS` static `World:124`: written by mods at init; read-only at tick time (its value matters for H40).
- `CauldronHooks.logChunkLoad/logChunkUnload` `C/net/minecraftforge/cauldron/CauldronHooks.java:89-107`: logging only, reads config.
- `CrucibleConfigs` chunk settings (`CrucibleConfigs.java:19`, `:22`, `:37`, `:144`, `:154`): read-only at tick time.

## 3. Closing lists

### 3.1 The 10 items most likely to bite in the synchronized parallel phase (B)

1. **H01** `ChunkBlockHashMap` MRU cache: every world read writes it and can return the wrong chunk. Blocks any parallel phase, even read-only.
2. **H07** synchronous chunk load/generation from a TE/entity tick: drags disk, entity/TE creation, world lists, tracker, events, population and mod generators onto the region thread; deadlock trap if handed to the barrier-waiting global thread.
3. **H26** `EntityTracker`: spawn/death on region threads write `trackedEntityIDs` without a common lock; the Thermos lock sleeps 100 ms on contention and can spin.
4. **H28** `EntityPlayerMP.destroyedItemsNetCache`: entity deaths in one region write the list a player drains in another -> CME or ghost entities.
5. **H30** player chunk sending inside the entity loop reads chunks, TE maps and TEs of other regions.
6. **H06 / H08** the same chunk loaded twice (no single flight across the two load paths; `AsynchronousExecutor.tasks` is a main-thread `HashMap`) -> duplicated TEs and entities (dupes).
7. **H35** cross-world transfer from a tick (non-player portals, mod teleporters) touches the target world, its tracker, `PlayerManager`, `Teleporter` and may hot-load a dimension.
8. **H04 / H02** `loadedChunks` and the Koloboke chunk map under concurrent loads -> chunks missing from the save list (data loss at shutdown) or from the lookup (reload = dupe).
9. **H13** world generation state (generator `Random` and buffers, `IntCache.resetIntCache`, `GameRegistry` cache, mod generators) when two regions of one world generate at once.
10. **H42 / H41** plugin and mod hooks around chunks (CraftWorld's second load path; `EnteringChunk` and other chunk events with unknown mod listeners) running concurrently from several region threads.

### 3.2 Check of `docs/pesquisa/arte-previa.md` §4 items that fall in this area

| Item | Verdict | Evidence |
|---|---|---|
| 2. `ChunkProviderServer`: `loadedChunkHashMap` (`LongHashMap`) + `loadedChunks` (`ArrayList`) + `chunksToUnload` (`HashSet`) | **Confirmed in substance, wrong in details.** Not thread-safe and the structures must stay consistent, but in Crucible the lookup is Thermos' `ChunkBlockHashMap` (Koloboke map + a 4-entry cache written on every read), `loadedChunkHashMap` is a `VanillaChunkHashMap` wrapping a `ConcurrentHashMap`, `chunksToUnload` is CraftBukkit's `LongHashSet`, and there is a fourth set, `loadingChunks` (`HashSet`). `loadedChunks` is an `ArrayList` as stated. | `CPS:76`, `CPS:82`, `CPS:84-86`; `CBHM:12`, `CBHM:40`; `VCHM:12`; H01-H06 |
| 3. `Chunk`: `chunkTileEntityMap` (`HashMap`), `entityLists`, `ExtendedBlockStorage` | **Confirmed, with corrections.** `chunkTileEntityMap` is a `HashMap(512,0.9)` and even "get TE" writes it; `entityLists` are CraftBukkit `UnsafeList`s, not `ArrayList`s (no CME detection, pooled iterators); `ExtendedBlockStorage` hazards confirmed and sharper than stated (lazy nibble allocation, nibble read-modify-write, torn LSB/MSB block ids). | `Chunk:60-61`, `Chunk:116`, `Chunk:114-124`, `Chunk:932-958`; `UnsafeList.java:58-91`, `:151-176`; `NibbleArray.java:181-214`; H15-H17 |
| 5. Lighting: `World.lightUpdateBlockList` and `Chunk` skylight | **Chunk part confirmed**: chunk light code writes neighbour chunks through `World.updateLightByType` and keeps per-chunk height/gap state. The `int[32768]` array exists at `World:173` (`new int[32768]` `World:306`, `:371`); its analysis belongs to `mundo.md` M15. | `Chunk:424-435`, `Chunk:537-541`, `Chunk:353-408`; H18 |
| 6. `IntCache` static lists without synchronization; generation in parallel corrupts | **Wrong in detail, right in conclusion.** In this code every `IntCache` method is `static synchronized`, so the lists do not corrupt structurally. It is still unsafe: `resetIntCache`, called at the start of every `WorldChunkManager` query, returns arrays still in use by another thread to the free list, so two threads can get the same `int[]`. Per-thread cache (or single-threaded generation) is still needed. | `IntCache.java:8-12`, `:15`, `:57-72`; `WorldChunkManager.java:73,121,160,189,228`; H13 |
| 8. `PlayerManager`/`EntityTracker`/`PlayerInstance` per world, used by players crossing regions; `playerEntityList` global | **Confirmed**, plus: the tracker carries a Thermos `ReentrantLock` that does not protect `trackedEntityIDs` and degrades to 100 ms sleeps or spinning; `PlayerManager` uses the vanilla `LongHashMap` and is read on every block change; logout can run on a region thread through a kick. | `ET:50-92`, `ET:290-313`; `PM:31`, `PM:143-153`; `SCM:112`; `NetHandlerPlayServer.java:289`; H26-H34 |
| 9. `MinecraftServer` globals, including `ServerConfigurationManager` | **Partly in area: `ServerConfigurationManager.playerEntityList` confirmed** (H34). `worldTickTimes`, `tickCounter`, `networkManagers` were covered in `crucible-tick-e-threads.md` and not redone. | `SCM:112` |
| 13. `ForgeChunkManager`/tickets: static per-world maps; `ChunkProviderServer.loadChunk` called by mods | **Confirmed**, plus a concrete lost-update race: `forceChunk`/`unforceChunk` rebuild the per-world immutable map with no lock; ticket multimaps are not thread-safe; `dormantChunkCache` is a thread-safe Guava cache. | `FCM:79-99`, `FCM:745-746`, `FCM:785-788`; H39 |
| 14. `ChunkIOExecutor` (Forge): pool of 1 thread, unbounded queue | **Confirmed for 1.7.10, with the size correction already made in `crucible-tick-e-threads.md`**: core size `max(1, ceil(players/50))`, unbounded `LinkedBlockingQueue`. The bigger hazard for regions is not the pool but its `tasks` `HashMap` (main-thread only) and the synchronous `skipQueue` path that runs stages 1-3 on the caller. | `ChunkIOExecutor.java:9`, `:24-27`; `AE:220`, `AE:234`, `AE:338-346`; H08-H10 |
| "Fronteiras de região" (neighbour updates, `getEntitiesWithinAABB` across chunks) — chunk side only | **Confirmed**: AABB queries iterate every chunk overlapping the box widened by the static `MAX_ENTITY_RADIUS`; `func_147486_a` can even load chunks. | `World:3938-3985`, `World:124`, `WS:913-930`; H40 |
| 1, 4, 7, 10, 11, 12 | Outside this area (World lists, tick lists, entity ids, `DimensionManager`, event bus, `FakePlayer`): see `mundo.md` and the other area reports. Touch points from chunk code are noted in H07 (lists, tick lists), `fetchDormantChunk` -> `Entity.resetEntityId` (§2 list), H35 (`DimensionManager.initDimension`). | — |

### 3.3 What I could not find or verify

- `org/bukkit/craftbukkit/chunkio` (named in the task): **nao encontrado** in this tree; the 1.7.10 chunk I/O lives in Forge's `net/minecraftforge/common/chunkio`. `C/org/bukkit/craftbukkit/v1_7_R4/util/AsynchronousExecutor.java` exists but is unreferenced.
- How often TE/entity ticks of this modpack trigger chunk loads, chunk generation, `ForgeChunkManager` mutations, cross-world teleports or kicks: **nao medido** (needs the Etapa 2 observer).
- Which mods listen to `EntityEvent.EnteringChunk`, `ChunkEvent`, `ChunkDataEvent`, `ChunkWatchEvent`, `ForceChunkEvent`: **nao verificado** (no listener in the base; mods not scanned).
- Whether the JIT in practice re-reads `last1` in `ChunkBlockHashMap.get` (H01): the Java memory model allows both behaviours; the race is real by the code, its frequency is **nao verificado**.
- Effect of `SideTransformer.allowInvalidSide` being reset by another thread during a TE class check (H22): **nao verificado**.
- Internal state of the modpack's `IWorldGenerator`s and chunk providers of mod dimensions (H13): **nao verificado**.
- Whether client desync from cross-thread packet reordering (H37) is visible in practice: **nao verificado** (needs a test with bots).
- The production values of `Crucible.yml`/`cauldron.yml` chunk settings: only the test copy was read (`server-test3/Crucible.yml:6`, `loadChunkOnRequest: true`); production equality assumed from `prepare-test-env.sh` copying configs, **nao verificado** here.
- Pre-existing issues seen in passing, not threading: `ChunkBlockHashMap.put` increments `size` when replacing a slot (`CBHM:138`); `SCM.disconnect` removes stats by name while the map key is the UUID (`SCM:460` vs `SCM:1727-1749`); `ForgeChunkManager` `world.activity` keeps one ticket per chunk, so unforcing one of two tickets on the same chunk clears the "active" mark (`FCM:362-368`).
