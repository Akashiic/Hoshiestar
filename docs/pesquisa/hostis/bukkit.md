# Task 0.8: thread-hostile state in the Bukkit / CraftBukkit / Spigot / Crucible layer (area "bukkit")

Read-only code study. Nothing was run, built or edited. Every `file:line` below was opened during this study.
Production build: the tree is checked out at `67b53034` ("teleport: fire a single PlayerTeleportEvent on a world change"), same as production.

## 0. Conventions

- `CR/` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad/build/Crucible/src/main/java/` (CraftBukkit, Spigot, Crucible, Cauldron glue, Thermos, aikar timings).
- `MC/` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad/build/Crucible/eclipse/cauldron/src/main/java/` (decompiled Minecraft + Forge + **the patched Bukkit API** `MC/org/bukkit/...`, all Crucible patches applied). Line numbers are of the final files.
- Short names: `SPM` = `MC/org/bukkit/plugin/SimplePluginManager.java`, `CS` = `CR/org/bukkit/craftbukkit/v1_7_R4/CraftServer.java`, `CW` = `CR/org/bukkit/craftbukkit/v1_7_R4/CraftWorld.java`, `CEF` = `CR/org/bukkit/craftbukkit/v1_7_R4/event/CraftEventFactory.java`, `SCHED` = `CR/org/bukkit/craftbukkit/v1_7_R4/scheduler/CraftScheduler.java`, `CH` = `CR/net/minecraftforge/cauldron/CauldronHooks.java`, `W` = `MC/net/minecraft/world/World.java`, `MS` = `MC/net/minecraft/server/MinecraftServer.java`, `E` = `MC/net/minecraft/entity/Entity.java`, `FH` = `MC/net/minecraftforge/common/ForgeHooks.java`.
- **B** = Degrau B, synchronized parallel phase (3-4 region threads tick TEs and entities of different regions; everything else waits). **C** = Degrau C, independent regions.
- "Region work" = TE `updateEntity`, entity `onUpdate`, and the block updates / neighbour notifications / events they trigger.
- Prior work cited, not redone: `docs/pesquisa/crucible-tick-e-threads.md` (`[tick-threads]`), `docs/pesquisa/crucible-arquitetura-patches.md` (`[patches]`), and the sibling reports of this same task `estudo/hostis/mundo.md` (`Mxx`) and `estudo/hostis/chunks.md` (`Hxx`). Overlaps are cross-referenced, not repeated.
- Plugin/mod jar counts are "jar whose `.class` files contain the string", on the **test copy** `scratchpad/server-test3/plugins` (49 top-level jars) and `server-test3/mods` (224 top-level jars). Presence, not number of calls. Production may differ.
- Every failure description is derived from reading code, **not verified at runtime**.

### Main finding in one paragraph

The Bukkit layer itself has few data structures that region threads write, but it has three properties that bite at once in B. (1) Every synchronous Bukkit event from region work goes through **one global monitor** (`SPM:492`), so plugins are serialized, and any lock we add that a handler can wait on while holding that monitor is a deadlock waiting to happen. (2) "Context" is passed between Forge/vanilla code and Bukkit events through **static fields** (`CraftEventFactory.blockDamage/entityDamage`, `ItemStack.currentPlayer`, `BlockSapling.treeType`); the damage ones are written for every moving entity on every tick and can throw an NPE inside an entity tick. (3) "Am I the server thread?" is answered in two hard-coded ways, `Thread == primaryThread` (`CS:1599-1601`) and `thread name == "Server thread"` (`MC/cpw/mods/fml/common/FMLCommonHandler.java:157-158`); on a region thread both say **no**, which turns off Timings, makes `kickPlayer`/`getNewScoreboard` throw, and makes 72 of 224 mod jars that call `getEffectiveSide()` believe they run on the client.

---

## 1. Items

### K01. `SimplePluginManager.callEvent`: one global monitor for every synchronous Bukkit event

- **Declaration / sites**: `SPM:482-496`. Synchronous event: `synchronized (this) { fireEvent(event); }` (`SPM:491-494`). Async event: throws if `Thread.holdsLock(this)` (`SPM:484-486`) or if `server.isPrimaryThread()` (`SPM:487-489`). The same monitor is taken by `loadPlugin` (`SPM:320`), `getPlugin` (`SPM:366`), `getPlugins` (`SPM:370`), `clearPlugins` (`SPM:463`), `injectModPlugin` (`SPM:742`). The listener array itself is safe: `HandlerList.handlers` is `volatile` and `bake()`/`register()` are `synchronized` (`MC/org/bukkit/event/HandlerList.java:18,107,175,189-191`).
- **Kind**: global singleton (lock) + re-entrancy/ordering assumption.
- **Who touches it in region work**: Bukkit event sites in code that runs inside TE/entity/block ticks: `MC/net/minecraft/tileentity` 11 sites in 6 files (furnace `TileEntityFurnace.java:274,358`, hopper `TileEntityHopper.java:372,564,620`, brewing `TileEntityBrewingStand.java:192`, beacon `TileEntityBeacon.java:108,130`, chest redstone `TileEntityChest.java:473,503`, note `TileEntityNote.java:73`); `MC/net/minecraft/entity` 135 sites in 52 files; `MC/net/minecraft/block` 63 sites in 37 files; `W` 8, `WorldServer` 5, `Explosion` 2 (grep `callEvent\|CraftEventFactory.call`). Forge `BlockEvent.BreakEvent/PlaceEvent` constructors call Bukkit (`MC/net/minecraftforge/event/world/BlockEvent.java:87,150`), so every quarry/miner FakePlayer block break goes through it. `World`-side sites (`BlockPhysicsEvent` on every neighbour update etc.) are in `M36`.
- **Who touches it from outside**: network phase (player actions), scheduler tasks, async plugin threads calling `getPlugins()` (e.g. metrics), commands.
- **What breaks with two region threads**: correctness is preserved (one handler at a time), but (a) **throughput**: region threads queue on one monitor. In the test copy `BlockPhysicsEvent` has listeners in 4 plugins (AncientGates, ChestShop, Skript, WorldGuard 6.2) and `InventoryMoveItemEvent` in 2 (ChestShop, WorldGuard), so every neighbour update and every hopper transfer in region work serializes. (b) **deadlock**: a handler running on region thread A holds the monitor; if it waits on anything region thread B holds (a chunk-load single-flight gate as proposed in `H07`, a region-ownership queue "run on owner and wait" as in design §4(3) mode 3, an EntityTracker lock), and B is itself blocked entering `callEvent` (e.g. `ChunkLoadEvent` fired from inside B's chunk load), both stop forever. (c) **async-event trap**: plugins that build their own events with `isAsync = !Bukkit.isPrimaryThread()` (common pattern) create an *async* event on a region thread; if that happens inside another handler (monitor held) `SPM:484-486` throws `IllegalStateException`.
- **Severity**: B **medio** (throughput; deadlock becomes **alto** the moment we add any blocking cross-region wait). C **alto**.
- **Treatment**: **keep the lock** in B, and make it a design rule: the plugin monitor is the outermost lock; code holding it may never block on a region-owned resource (route the work to a join-point queue instead). Skip building and firing events whose `HandlerList` is empty (`M36`). In C: plugin adapter (design §4(7)).

### K02. Plugin code fired inside a region tick reaching other regions or global state (Bukkit API surface)

Summary (full table right below): the plugin monitor (K01) serializes plugins **among themselves**, not against region threads. A handler running on region thread A can call API that reads or writes region B or global structures while B's thread runs unlocked: `CraftWorld.getEntities/getLivingEntities/getPlayers/getEntitiesByClass(es)` iterate `world.loadedEntityList` directly (`CW:700-806`) -> CME while another region appends (`M02`); `CraftWorld.getBlockAt` loads chunks (`CW:114-117` -> `getChunkAt` `CW:151-154` -> `ChunkProviderServer.loadChunk`, `H07/H42`); `CraftBlock.setTypeIdAndData` (`CR/.../block/CraftBlock.java:289`), `BlockState.update` (`CR/.../block/CraftBlockState.java:188`), `Block.getState` on a foreign TE runs `te.writeToNBT` while that TE ticks (`CraftBlockState.java:46,70,89`); `Entity.teleport` in the same world just moves the entity (`CR/.../entity/CraftEntity.java:580-596`), across worlds calls `teleportTo` (`H35`); `CraftWorld.setStorm/setThundering/setFullTime` write `worldInfo` and iterate players (`CW:593-603,882-935`, `M30`); `Bukkit.createWorld/unloadWorld` (K13); `Bukkit.dispatchCommand` (K11).
- **Kind**: re-entrancy (plugin code is region work it does not know it is in).
- **Severity**: B **alto** for entity enumeration and foreign block/TE access from handlers of high-frequency events (WorldGuard and GriefPreventionPlus read blocks/claims in other chunks); C **alto**.
- **Treatment**: B: an ownership check on the hot entry points (`getBlockAt`, `getEntities*`, `teleport`, `setType`) that, when called from a region thread for a position it does not own, **defers** writes to the join-point queue and serves reads from the global list only between phases (snapshot taken at phase start for `getEntities*`). Design §4(7) "problema em aberto" stays open for C.

**Bukkit API calls a plugin can make from a handler fired inside a region tick that reach another region or global state** (all opened in this study):

| API call | Implementation | What it reaches | Hazard on a region thread |
|---|---|---|---|
| `World.getBlockAt(x,y,z)` / `getBlockAt(Location)`, `Block.getRelative` | `CW:114-117,636-638` -> `getChunkAt` `CW:151-154` -> `ChunkProviderServer.loadChunk` | any chunk, **loads it** if absent | sync chunk load from a region thread (`H07`), foreign chunk read (`H01`) |
| `World.getChunkAt`, `loadChunk`, `unloadChunk*`, `regenerateChunk`, `refreshChunk`, `isChunkInUse`, `getLoadedChunks` | `CW:151-330` | chunk maps, `chunksToUnload`, second load path | `H42` |
| `Block.setType/setTypeIdAndData/setData`, `BlockState.update`, `Block.breakNaturally` | `CraftBlock.java:289,525,540`; `CraftBlockState.java:188` | foreign blocks, neighbour updates, TEs | write into another region while its thread runs |
| `Block.getState()` on a TE block, `Chunk.getTileEntities()` | `CraftBlock.java:367` -> `CraftBlockState.java:46,70,89` (`te.writeToNBT`); `CraftChunk.java:175-187` | foreign TE state | torn NBT snapshot of a TE that is ticking |
| `Inventory.getContents/setItem/addItem` on a foreign container | `CR/.../inventory/CraftInventory.java:41-62` | TE inventory arrays | lost/duplicated items vs. that TE's own tick (hopper, machine) |
| `World.getEntities/getLivingEntities/getPlayers/getEntitiesByClass(es)` | `CW:700-806` (iterate `world.loadedEntityList`) | whole-world entity list | CME / skipped entries (`M02`) |
| `World.getNearbyEntities`, `Entity.getNearbyEntities` | `CW:822-842`; `CraftEntity.java:606-614` | chunk entity lists across chunk borders | `H40` |
| `World.spawn/spawnEntity/dropItem*/spawnArrow/spawnFallingBlock/strikeLightning` | `CW:342-436,986-1012` | `spawnEntityInWorld` -> world lists, tracker, chunk lists | `M02`, `H16`, `H26` |
| `World.createExplosion` | `CW:605-623` | blocks and entities in radius | crosses region borders; writes K03 static |
| `World.generateTree` | `CW:438-553` | per-world `WorldChangeHolder` | `M22` (`startCapture` throws if another thread is capturing) |
| `World.setTime/setFullTime/setStorm/setThundering/setWeatherDuration` | `CW:583-603,882-935` | `worldInfo`, all players of the world | `M30`; fires `WeatherChangeEvent` |
| `Entity.teleport` (same world) | `CraftEntity.java:580-596` -> `setPositionAndRotation` | moves the entity into another region instantly | entity changes owner mid-phase |
| `Entity.teleport` / `Player.teleport` (other world) | `CraftEntity.java:586-589` -> `teleportTo`; `CraftPlayer.java:531-588` -> `ThermiteTeleportationHandler.transferPlayerToDimension` | two worlds, player list, tracker | `H35` |
| `Player.kickPlayer` | `CraftPlayer.java:319-325` | logout in place (`H34`) | throws today (K09) |
| `ScoreboardManager.getNewScoreboard` | `CraftScoreboardManager.java:27-33` | scoreboard registry | throws today (K09); `M29` |
| `Bukkit.getOnlinePlayers/getPlayer/getPlayerExact/matchPlayer` | `CS:447-530` | `playerEntityList` | `H34`, K24 |
| `Bukkit.broadcast(msg, perm)` | `CS:1273-1286` | permission subscription maps | K07 |
| `Bukkit.getOfflinePlayer(name)` | `CS:1290-1316` | vanilla profile cache, maybe network | blocks all regions under K01 (not verified) |
| `Bukkit.dispatchCommand` | `CS:704-718`; `CraftSimpleCommandMap.java:26-66` | any command, `vanillaConsoleSender` | K11 |
| `Bukkit.createWorld/unloadWorld`, `getWorlds` | `CS:910-963,971-1008,665-667` | `CraftServer.worlds`, Forge dimension maps | K13 |
| `Scheduler.callSyncMethod(...).get()` | `SCHED:159-164`; `CraftFuture.java:35-49` | main thread heartbeat | deadlock in B (K10) |
| `Scheduler.runTask*/cancelTask` | `SCHED:129-140,166-207` | lock-free queue | safe |
| `Metadatable.setMetadata/getMetadata` | `MetadataStoreBase.java:33-77` | global metadata maps | safe (`synchronized`) |

### K03. `CraftEventFactory.blockDamage` / `entityDamage`: damage context passed through statics

- **Declaration**: `CEF:64-65` (`public static`).
- **Writes (region work)**: `E:1069-1071` inside `Entity.func_145775_I` (block collisions), called from `moveEntity` (`E:984`) and `E:626`, i.e. **every moving entity, every tick, for every block in its bounding box** (it also builds a `CraftBlock` via `CraftWorld.getBlockAt`, which goes through `loadChunk`); `E:2282-2284` (lightning); `MC/net/minecraft/world/Explosion.java:162,166`; `MC/net/minecraft/entity/item/EntityEnderPearl.java:81,83`; `MC/net/minecraft/entity/item/EntityFallingBlock.java:207,209`; `MC/net/minecraft/entity/boss/EntityDragon.java:374,376`.
- **Reads**: `CEF:402-403` (explosion: read then clear), `CEF:465-468` (`blockDamage != null` then read and clear), `CEF:502-505` (`entityDamage != null` then `entityDamage.getBukkitEntity()` and clear).
- **Kind**: static mutable (dynamic-scope variable).
- **Who**: entity ticks of every region (collisions, explosions, falling blocks, pearls, lightning); outside: network phase (player movement collisions).
- **What breaks**: (a) **NPE inside an entity tick**: thread A passes the null check at `CEF:502`, thread B clears the field, A calls `.getBukkitEntity()` on null at `CEF:504` -> "Ticking entity" crash report (entity removed or server stops, depending on Forge settings). (b) **wrong result**: a cactus/lava hit in region A is reported as `EntityDamageByBlockEvent` with a block from region B (or with no block), an explosion in A gets B's TNT as damager; plugins that decide protection by the damager's location (WorldGuard, GriefPreventionPlus) cancel or allow damage wrongly. In the test copy 2 plugins reference `EntityDamageByBlockEvent`.
- **Severity**: B **alto** (written at the highest frequency of any static in this layer). C **alto**.
- **Treatment**: **thread-local** (or a field in a per-thread "region context" object): 8 writer sites and 3 readers, mechanical change. Also stop creating a `CraftBlock` per collided block unless a listener for `EntityDamageByBlockEvent` exists (cost, not safety).

### K04. `ItemStack.currentPlayer`: "who is using this item" as a static

- **Declaration**: `MC/net/minecraft/item/ItemStack.java:61`.
- **Writes**: `FH:533` (set before `onItemUse`), `FH:546` (cleared in `finally`), inside `ForgeHooks.onPlaceItemIntoWorld`, which runs for real players (network phase) **and for mod FakePlayers using items from TE ticks** (deployers, autonomous activators, golems; region work).
- **Reads**: `W:4869-4885` `World.canPlaceMultipart` (ForgeMultipart / Immibis microblocks) fires `BlockPlaceEvent` for `ItemStack.currentPlayer` (`W:4872-4874`).
- **Kind**: static mutable.
- **What breaks**: a microblock placed by a FakePlayer in region A is checked against the player of region B (or none): protection checks run for the wrong player (place allowed in a claim, or denied wrongly). No crash.
- **Severity**: B **medio**, C **medio**.
- **Treatment**: **thread-local**, together with the Forge capture flags of `M20/M21`.

### K05. `BlockSapling.treeType`: "which Bukkit tree is growing" as a static

- **Declaration**: `MC/net/minecraft/block/BlockSapling.java:39`.
- **Writes**: tree selection in `func_149878_d` `BlockSapling.java:116,121,136,161,173,185,191,204`; `MC/net/minecraft/block/BlockMushroom.java:125,130`. **Read and cleared**: `BlockSapling.java:62-63` (random tick growth, inside the `WorldChangeHolder` capture), `FH:559-560` (bone meal).
- **Kind**: static mutable.
- **Who**: random block ticks (if moved into region threads, `[tick-threads] §5`), bone meal by FakePlayers in TE ticks, players (network phase).
- **What breaks**: region A's sapling reads B's tree type or `null`. With `null`, `BlockSapling.java:65` takes the `else` branch and commits the tree **without** `StructureGrowEvent` (protection plugins never see it); with the wrong type, plugins see the wrong `TreeType`.
- **Severity**: B **medio** if block ticks run in region threads, **baixo** if they stay serial. C **medio**.
- **Treatment**: **thread-local**, same context object as K04.

### K06. Placement / tree / drop capture mechanism (Bukkit side)

- **Where**: `ForgeHooks.onPlaceItemIntoWorld` `FH:516-600` turns on `world.captureBlockSnapshots` (`FH:516`), `captureAndProxyBlocks` (`FH:523`), `captureItemDrops` (`FH:529`), sets K04, calls `onItemUse`, restores in `finally` (`FH:544-552`), then builds `StructureGrowEvent` from `capturedBlockSnapshots` (`FH:555-585`, event at `FH:572`). Crucible tree capture `CR/io/github/crucible/util/WorldChangeHolder.java` (`startCapture` `:30-39`) is also entered by plugins through `CraftWorld.generateTree(Location, TreeType, BlockChangeDelegate)` (`CW:546-552`); the other `generateTree` (`CW:438-493`) writes straight into the world with the shared static `CraftWorld.rand` (`CW:46`, `java.util.Random`, thread-safe but contended).
- **Kind**: per-world flags and buffers used as dynamic-scope variables.
- **What breaks**: already analysed in `M20` (Forge snapshots), `M21` (drop queue: item loss or drops spawned by the wrong thread), `M22` (`startCapture` throws `IllegalStateException` when two threads grow trees in one world; writes of one region captured and discarded by another).
- **Severity**: B **alto**, C **alto** (per `M20-M22`).
- **Treatment**: one **thread-local capture context** holding `captureBlockSnapshots`, `capturedBlockSnapshots`, `captureItemDrops`, `itemStackSpawnQueue`, `worldChangeHolder`, `ItemStack.currentPlayer`, `BlockSapling.treeType`; `World` accessors consult only the current thread's context.

### K07. Permission subscription maps written when a Bukkit wrapper for a FakePlayer is created

- **Declaration**: `SPM:50` `permSubs` (`HashMap<String, Map<Permissible,Boolean>>`, inner maps `WeakHashMap` `SPM:658`), `SPM:51` `defSubs` (`HashMap`, inner `WeakHashMap` `SPM:693`). No synchronization on any of the subscribe/unsubscribe/get methods (`SPM:653-720`).
- **Writes from region work**: a Bukkit event for a mod FakePlayer (`FakePlayer extends EntityPlayerMP`, `MC/net/minecraftforge/common/util/FakePlayer.java:18`) needs `player.getBukkitEntity()` (`CEF:119` place, `CEF:976` break). The first call creates the wrapper lazily (`E:1969-1978`) -> `new CraftPlayer` (`CR/.../entity/CraftEntity.java:49-52`) -> `CraftHumanEntity` field `new PermissibleBase(this)` (`CR/.../entity/CraftHumanEntity.java:22`) -> constructor runs `recalculatePermissions()` (`MC/org/bukkit/permissions/PermissibleBase.java:29`) -> `subscribeToDefaultPerms` and `subscribeToPermission` for every default permission (`PermissibleBase.java:148-160,178-193`), after `clearPermissions` unsubscribes (`:165-176`). This happens **before** `callEvent`, so outside the K01 monitor. The same path is taken by `CraftEntity.getPermissibleBase()` for non-player entities (`CraftEntity.java:870-886`, lazy static, also a benign race).
- **Reads**: `CS:1273-1286` `broadcast(message, permission)` copies `getPermissionSubscriptions` (`SPM:678-687`, iterates a `WeakHashMap`, which itself mutates on read by expunging stale entries); `dirtyPermissibles` (`SPM:645-651`).
- **Kind**: global singleton collections.
- **What breaks**: two region threads wrapping two new FakePlayers at once -> concurrent `HashMap.put` (lost subscription, corrupt bucket, in Java 8 a possible `ClassCastException` in a treeified bin) and concurrent `WeakHashMap.put` on the same inner map; a `broadcast` from a handler iterating while another thread subscribes -> CME. Mods that create a new FakePlayer per operation make this frequent.
- **Severity**: B **medio**, C **medio**.
- **Treatment**: **lock** (make the six subscribe/unsubscribe/get methods `synchronized` on a private lock, or switch to concurrent maps with weak keys); cheap and local.

### K08. `FMLCommonHandler.getEffectiveSide()` decides "server" by thread **name**

- **Where**: `MC/cpw/mods/fml/common/FMLCommonHandler.java:155-164`: returns `Side.SERVER` only if `Thread.currentThread().getName().equals("Server thread")`, otherwise `Side.CLIENT`. The server thread is named so at `MS:227` / `MS:287`.
- **Kind**: re-entrancy/ordering assumption (one named thread).
- **Who**: mod code inside TE/entity ticks. 72 of 224 mod jars in the test copy reference `getEffectiveSide`.
- **What breaks**: on a region thread the answer is `CLIENT`: server-only logic is skipped (machines stop processing, inventories not synced, network packets not sent) or client-only code is called (`NoClassDefFoundError` for client classes stripped from the server jar -> crash). Silent wrong behaviour is the likely common case.
- **Severity**: B **alto**, C **alto**. (FML is outside this area's folders; listed here because it is a hard-coded "primary thread" check, which the task assigns to this area.)
- **Treatment**: patch `getEffectiveSide` (early mixin) to return `SERVER` for any thread registered as a server tick thread (main, region workers), via a cheap thread-local flag. Naming region threads "Server thread" also works but makes thread dumps unreadable; not recommended.

### K09. `isPrimaryThread()` / `primaryThread` hard-coded checks

- **Definition**: `CS:1599-1601` (`Thread.currentThread().equals(console.primaryThread)`); `MS:187` `public final Thread primaryThread`.
- **Every use found** (grep `isPrimaryThread|primaryThread|Thread.currentThread()` over `CR/` and `MC/`):
  1. Timings: `CR/co/aikar/timings/TimingHandler.java:102,114` (start/stop silently do nothing off the primary thread), `CR/co/aikar/timings/TimedEventExecutor.java:75` (plugin event timing skipped), `CR/co/aikar/timings/UnsafeTimingHandler.java:36` (**throws** "Calling Timings from Async Operation").
  2. `SPM:487` async-event check (K01).
  3. `CR/.../entity/CraftPlayer.java:319-321` `kickPlayer` **throws** `IllegalStateException("Asynchronous player kick!")`.
  4. `CR/.../scoreboard/CraftScoreboardManager.java:27-29` `getNewScoreboard` **throws** `IllegalStateException("Asynchronous scoreboard creation")`.
  5. `W:4943-4945` async lighting: off the primary thread light is computed inline instead of being submitted to the per-world executor (`M16`).
  6. `CR/org/spigotmc/WatchdogThread.java:164,199` dumps only `primaryThread` (K20).
  7. `CR/io/github/crucible/Metrics.java:138` (own bStats thread; harmless).
  8. Name-based: K08.
- **Kind**: re-entrancy/ordering assumption.
- **Who**: plugin handlers fired in region work (a mob kills a player in a mob tick -> `PlayerDeathEvent` -> a deathban/combat-log plugin calls `kickPlayer` -> exception inside the entity tick); plugins themselves: 16 of 49 plugin jars reference `isPrimaryThread` (they reschedule to the next tick or refuse when it is false), 6 reference `kickPlayer`, 1 (WildSkript) `getNewScoreboard`.
- **What breaks**: exceptions in region work (3, 4, `UnsafeTimingHandler`); plugin behaviour changes (work moved one tick later, cancellations ignored); Timings report shows TEs/entities as zero (measurement loss, `[tick-threads] §4`).
- **Severity**: B **alto**, C **alto**.
- **Treatment**: introduce one "server tick thread" predicate (thread-local flag set on main and region workers) and make `isPrimaryThread()` return true on region threads during the phase, so plugins behave as today. The two operations that then become unsafe because they touch global state get **deferred to the join-point queue**: `kickPlayer` (it runs the whole logout in place, `H34`) and `getNewScoreboard` (`M29`). Keep `UnsafeTimingHandler` and `TimingHandler` gated on the *real* main thread (K18).

### K10. `CraftScheduler`: queue, `currentTick`, runners, and interleaving with the parallel phase

- **Declarations**: `SCHED:50-58` `pending` (`PriorityQueue`, "main thread exclusively controls temp and pending" `SCHED:18-20`), `SCHED:62` `temp` (`ArrayList`, main only), `SCHED:66` `runners` (`ConcurrentHashMap`), `SCHED:67` cached pool "Craft Scheduler Thread - %d", `SCHED:71` `head` (volatile), `SCHED:75` `tail` (`AtomicReference`), `SCHED:76` `currentTick` (volatile), `SCHED:77-83` `debugHead/debugTail` (main only).
- **Sites**: producers from any thread go through the lock-free `addTask` CAS (`SCHED:390-397`) via `handle` (`SCHED:399-403`, `nextRun = currentTick + delay`). Consumer: `mainThreadHeartbeat` (`SCHED:343-388`) called at `MS:905`, i.e. in the global phase **before** the world loop, runs sync tasks inline (`SCHED:356-358`) and hands async tasks to the pool (`SCHED:371`). `callSyncMethod` (`SCHED:159-164`) returns a `CraftFuture` whose `get()` waits on the future's monitor (`CR/.../scheduler/CraftFuture.java:35-49`) until the main thread runs it.
- **Kind**: cross-thread handoff.
- **Who**: plugin handlers in region work schedule tasks (safe: CAS append; they run at the next heartbeat, the same tick they would run today). Async tasks run concurrently with region threads (pre-existing hazard, now against 3-4 threads instead of one).
- **What breaks**: (a) `callSyncMethod(...).get()` (or any `Future.get()` on a sync task) called **from a region thread** during B never returns: the main thread is waiting at the join and cannot run the heartbeat -> deadlock of the whole server. 3 plugin jars reference `callSyncMethod` (Skript, Citizens 2.0.13, promenus). (b) `cancelTask` from a region thread is fine (special task `-1` processed on main, `SCHED:166-207`). (c) In C there is no single "next heartbeat" relative to a region: a sync task touching a location owned by a running region is unsafe.
- **Severity**: B **baixo** for scheduling, **alto** for the `get()` deadlock (rare but total). C **alto**.
- **Treatment**: keep the heartbeat on the global thread. Detect `CraftFuture.get()` on a region thread and run the callable inline under the plugin monitor, or fail fast with a logged error instead of waiting. C: route tasks by location/entity (design §4(7)).

### K11. Command execution from command blocks and command-block minecarts (plugin commands on region threads, no lock)

- **Where**: `MC/net/minecraft/command/server/CommandBlockLogic.java:98-249` (`func_145755_a`): Bukkit command map dispatch `:110,141-145,151-156,220`; vanilla command manager `:154-155`; `testfor` reads `ServerConfigurationManager` `:178`. Callers: `MC/net/minecraft/block/BlockCommandBlock.java:63` (scheduled block tick), `MC/net/minecraft/entity/EntityMinecartCommandBlock.java:75` (entity tick). Shared field `CR/.../command/CraftSimpleCommandMap.java:17` `vanillaConsoleSender`, written by `setVanillaConsoleSender` (`:70-72`) from `CS:691,714`; read `:48`.
- **Kind**: re-entrancy (arbitrary command code runs as region work).
- **What breaks**: plugin command executors (Essentials `/tp`, `/give`, economy) run on a region thread **without** the K01 monitor, concurrently with other plugin code; vanilla commands (`/time`, `/weather`, `/kill @e`, `/tp`) touch global and other-region state; `vanillaConsoleSender` can be swapped by another thread between set and use (a command attributed to the wrong sender).
- **Severity**: B **medio** (minecart command blocks are region work; block ones only if scheduled ticks move into regions). C **alto**.
- **Treatment**: **defer to a queue applied at the join point** (global thread). The command block's success count (`field_145764_b`, comparator output) then lags one tick; acceptable, document it. Alternative for B: take the plugin monitor around the dispatch.

### K12. Plugin listeners on the Forge event bus (`CrucibleEventBus`) run without the Bukkit monitor

- **Where**: `CR/io/github/crucible/api/CrucibleEventBus.java:37-99` registers plugin methods as Forge `IEventListener`s (`ASMEventHandler`, `:94-95`) on `MinecraftForge.EVENT_BUS` or the FML bus. Forge `EventBus.post` calls them directly (`MC/cpw/mods/fml/common/eventhandler/EventBus.java:151-175`).
- **Kind**: re-entrancy.
- **Who**: any Forge event posted from region work (e.g. `BlockEvent`, `EntityJoinWorldEvent`, `LivingDeathEvent`) reaches plugin code. In the test copy 1 plugin jar (EverNifeCore 2.0.4.4) references `CrucibleEventBus`.
- **What breaks**: plugin code that assumes one thread runs on several region threads at once with no lock: lost updates/CME in the plugin's own maps; and it is invisible to the K01 serialization.
- **Severity**: B **medio**, C **alto**.
- **Treatment**: **lock**: wrap the generated listener so it takes the same plugin monitor as `callEvent` (the wrapper is created in one place, `CrucibleEventBus.java:91-95`).

### K13. `CraftServer.worlds` and world creation / unload reachable from a region tick

- **Declaration**: `CS:121` (`LinkedHashMap<String, World>`).
- **Writes**: `CS:1031-1038` `addWorld`, called from the `World` constructor (`W:493`), i.e. whenever a dimension is hot-loaded: `MinecraftServer.worldServerForDimension` -> `DimensionManager.initDimension` (`MS:1078-1083`), which an entity tick reaches through portal/teleport into an unloaded dimension; `CS:1005` `unloadWorld` (global `unloadWorlds`, or plugins). Flag `CH:35` `craftWorldLoading`, set by `CS:933/961` (plugin `createWorld`) and read by `MC/net/minecraftforge/common/DimensionManager.java:282` (initDimension returns early while a plugin creates a world). New Bukkit `Environment` enum values are added at `DimensionManager.java:600` via `CR/io/github/crucible/util/BukkitEnumHelper.java:27-30` (`EnumHelper.addEnum`, not thread-safe).
- **Reads**: `CS:665-667` `getWorlds` copies `worlds.values()`; `CS:1015-1029` `getWorld(name)`, `getWorld(UUID)` iterates.
- **Kind**: global singleton collection.
- **What breaks**: a region thread hot-loading a dimension while a handler on another thread calls `Bukkit.getWorlds()` -> CME; a plugin `createWorld` concurrent with a portal hot-load -> `initDimension` returns early, the caller gets `null` from `getWorld` and NPEs. The rest of the hot-load (Forge `DimensionManager` maps, `MinecraftServer.worlds`) is outside this area (see `H35` and `[tick-threads]` review on `setWorld`).
- **Severity**: B **medio** (rare event, crash when it happens), C **alto**.
- **Treatment**: **defer dimension load/unload to the global phase** (the entity that needs it waits one tick, as design §5 already says for teleports); make `worlds` copy-on-write.

### K14. `CauldronHooks.tileEntityCache` / `sushchestvoCache`: static HashMaps hit on every TE and entity tick

- **Declaration**: `CH:38-39` (`public static HashMap`, key = class).
- **Sites**: `canTileEntityTick` `CH:268-303`: `get` on **every** TE tick (`CH:271`), `put` the first time a class is seen (`CH:276`); called at `W:2542`. `canSushchestvoTick` `CH:220-266`: `get` every entity tick (`CH:231`), `put` `CH:236`; called at `W:2672`. The first-time path also writes the shared YAML wrappers (`CR/net/minecraftforge/cauldron/configuration/WorldConfig.java:36-57`, `M34`). Values are `TileEntityCache`/`SushchestvoCache` objects with public mutable fields (`CR/net/minecraftforge/cauldron/TileEntityCache.java:7-11`), rewritten by the reload commands.
- **Kind**: static mutable collection (read-mostly, lazily written).
- **What breaks**: concurrent `put` during another thread's `get`/`put`: lost entries (harmless re-creation) or a corrupted map (in Java 8 a concurrent resize/treeify can throw or loop). Bursty: right after start, after chunk loads that bring new TE classes, after `/tileentity reload`.
- **Severity**: B **medio**, C **medio**.
- **Treatment**: `ConcurrentHashMap` + **pre-warm** for all registered TE/entity classes at startup (global phase). While there, key by (world, class) to fix the "first world wins" bug in `[patches] §3.4`.

### K15. `CauldronHooks.recentWarnings` (collision warning throttle)

- **Declaration**: `CH:41` (`static final TObjectLongHashMap`). Sites: `logEntitySize` `CH:193-211` (`contains/get/put` `CH:205-209`), called from `W:1982` (entity collision query, region work). Only active with `cauldron.logging.entityCollisionChecks` (`CR/io/github/crucible/CrucibleConfigs.java:91`, default **false**).
- **Kind**: static mutable collection.
- **What breaks**: concurrent Trove `put` -> corrupt map / AIOOBE, only when the logging option is on.
- **Severity**: B **baixo**, C **baixo**.
- **Treatment**: no action while the option is off; if turned on, `ConcurrentHashMap`.

### K16. Spigot `ActivationRange`: static mutable bounding boxes

- **Declaration**: `CR/org/spigotmc/ActivationRange.java:40-43` (`maxBB`, `miscBB`, `animalBB`, `monsterBB`, package-static, mutated by `growBB` `:114-121`).
- **Sites**: `activateEntities(World)` `:129-163` (writes the four boxes per player `:144-147`, iterates `chunk.entityLists` in range `:170-197`, writes `entity.activatedTick`); called once per world at `W:2416`, before the entity loop.
- **Kind**: static mutable.
- **What breaks**: if two **worlds** run `updateEntities` in parallel (design §5 "one thread per dimension for free"), the boxes of one world's player are overwritten by another's -> entities activated or left inactive wrongly. Practical impact is small: `initializeEntityActivationState` returns `true` for almost every entity because its `else return true` catches the normal case (`:76-80`), so `defaultActivationState` is true and the boxes rarely matter (`[patches] §3.5`). If `activateEntities` is moved inside region threads it also iterates other regions' `chunk.entityLists` (`H16`).
- **Severity**: B **baixo** (called serially per world), **medio** if worlds tick in parallel; C **medio**.
- **Treatment**: **per-call locals** (allocate four boxes per call, or per thread); keep the call in the serial pre-phase of each world.

### K17. `MinecraftServer.currentTick` (static global clock) and `callingForgeTick`

- **Declaration**: `MS:186` `public static int currentTick`; written only at `MS:683` (`currentTick++`, outer loop). `MS:208` `callingForgeTick`, written `MS:827,831,892,896` (global phase).
- **Reads in region work**: ActivationRange `ActivationRange.java:143,173-192,253-261`; CraftBukkit "catch-up" clocks: `TileEntityFurnace.java:45,240-241`, `TileEntityBrewingStand.java:32,88-89`, `EntityItem.java:40,114-118`, `EntityZombie.java:65,322-323` (all `MC/net/minecraft/...`); `PathNavigate.java:106,121`; `ChunkProviderServer.java:91,622`; network `NetHandlerPlayServer.java:189-190,666-669`, `NetworkSystem.java:152`.
- **Kind**: static mutable (global clock).
- **What breaks**: in B nothing: it is only written outside the parallel phase. In C a region's tick count diverges from this clock. Side effect relevant to **Degrau A**: furnaces, brewing stands, item despawn and zombie conversion compute "elapsed ticks" from this wall-clock counter, so a region that is throttled to tick 1 in N **catches up** those N ticks on its next tick for these four classes (they do not slow down while other machines do).
- **Severity**: B **baixo** (no action), C **medio**.
- **Treatment**: B: no action. C: per-region tick counter copied in at region tick start (Folia model, `arte-previa.md` §1.4). Decide explicitly for Degrau A whether the catch-up behaviour is wanted.

### K18. Timings v2 (aikar) and Spigot `CustomTimingsHandler` off the main thread

- **Declarations**: `CR/co/aikar/timings/TimingHandler.java:42-43` (`idPool` atomic, `TIMING_STACK` static `ArrayDeque`), `:49` per-handler `children` map; `CR/co/aikar/timings/TimingsManager.java:44-60` (`TIMING_MAP` concurrent; `HANDLERS`, `MINUTE_REPORTS` `ArrayList`; `HISTORY`); `CR/co/aikar/timings/TimingHistory.java:51-58` (static counters `entityTicks`, `tileEntityTicks`, ... and `worldMap`).
- **Sites**: `startTiming/stopTiming` return early off the primary thread (`TimingHandler.java:102,114`), so `TIMING_STACK`, `children`, `HANDLERS` are only touched by the main thread. Timer **creation** happens on any thread (every `TileEntity` and `Entity` constructor: `MC/net/minecraft/tileentity/TileEntity.java:42`, `E:179`; lazy per-block `MC/net/minecraft/block/Block.java:132`) through `TIMING_MAP.computeIfAbsent` on a `ConcurrentHashMap` (`CR/co/aikar/util/LoadingMap.java:269-274`) and `TimingGroup.handlers` (`synchronizedList`, `CR/co/aikar/timings/TimingIdentifier.java:97`): safe. Region-work writes to `TimingHistory.entityTicks/tileEntityTicks` (`W:2419,2443,2622`) are `M33`. `new TimingHistory()` (`FullServerTickHandler.java:74-76`, global phase) iterates every world's loaded chunks, their entities and TEs (`TimingHistory.java:104-130`). `CR/org/spigotmc/CustomTimingsHandler.java` is a deprecated façade over the same handlers (no own state besides two lazily set statics `:49-50`); there is no `SpigotTimings` class in this tree.
- **Kind**: static mutable, main-thread-confined by the `isPrimaryThread` gate.
- **What breaks**: in B nothing crashes; region work simply disappears from Timings (and if the main thread itself runs a region, only that region is measured, skewing the report). In C, `new TimingHistory()` on the global thread iterates chunk entity lists of running regions -> CME.
- **Severity**: B **baixo** (measurement loss), C **medio**.
- **Treatment**: B: **no action**; measure with JFR per thread (project rule) and our own per-region counters. C: build the history only between phases or drop it. Note: `/timings paste` uploads to an external site (`CR/co/aikar/timings/TimingsExport.java:323`, `http://timings.aikar.co/post`): same rule as spark, do not use it in the project.

### K19. Spigot `TickLimiter`

- **Where**: `CR/org/spigotmc/TickLimiter.java:3-26` (instance fields `startTime`, `tick`, `shouldContinue`; one instance per world per loop, `W:285-286,4895-4896`).
- **Analysis**: `M03` (shared cursor and budget per world; one thread's `initTick` resets another's budget).
- **Severity**: B **alto** / C **alto** per `M03` (fixed by whoever writes the region loop).
- **Treatment**: **per-region instance**; the Degrau A budget replaces it.

### K20. `WatchdogThread`: one heartbeat, one watched thread

- **Declarations**: `CR/org/spigotmc/WatchdogThread.java:24-25` (`currentPacket`, `currentPlayer` as `AtomicReference`), `:30` `lastTick` volatile, written by `tick()` `:48-50`, called once per server tick at `MS:825`.
- **Sites**: timeout path dumps `primaryThread` (`:164`) **and** all threads (`:167-171`); warning path dumps only `primaryThread` (`:197-200`); reads `MinecraftServer.worlds` and list sizes from the watchdog thread (`:114-121,188-196`, racy reads of sizes, harmless). `currentPacket/currentPlayer` set in the network phase (`MC/net/minecraft/network/NetworkManager.java:240,256,271,276`).
- **Kind**: cross-thread handoff (monitoring).
- **What breaks**: B: a stuck region thread stalls the join, so `lastTick` stops and the watchdog fires; the full dump shows the region threads, the warning dump does not (it shows the main thread waiting at the join). C: a stuck region is invisible while the global thread keeps ticking.
- **Severity**: B **baixo**, C **medio**.
- **Treatment**: per-region heartbeat and dump of the owning thread (design §4(9)); in B also dump region workers on warning.

### K21. Async lighting executor and the `isPrimaryThread` branch in lighting

- **Where**: `W:4943-4952`; executor per world, `use-async-lighting` default false (`CR/org/spigotmc/SpigotWorldConfig.java:276`). Analysed in `M16`.
- **Bukkit-layer point**: today, on a region thread, `!Bukkit.isPrimaryThread()` makes lighting run **inline** (the safe choice). If K09's treatment makes `isPrimaryThread()` true on region threads and someone turns `use-async-lighting` on, light updates from all regions would be submitted to the one per-world executor and run concurrently with region writes.
- **Severity**: B **baixo** (option off), C **baixo**.
- **Treatment**: keep `use-async-lighting: false`; make this branch test the *real* main thread, not the K09 predicate.

### K22. `Entity.getBukkitEntity()` lazy wrapper creation and the FakePlayer branch

- **Where**: `E:1969-1978` (`if (bukkitEntity == null) bukkitEntity = CraftEntity.getEntity(...)`, no synchronization). For an `EntityPlayer` that is not an `EntityPlayerMP`, `CraftEntity.getEntity` wraps `FakePlayerFactory.get(...)` (`CR/.../entity/CraftEntity.java:53-58`), which iterates and writes the static `HashMap` `FakePlayerFactory.fakePlayers` (`MC/net/minecraftforge/common/util/FakePlayerFactory.java:19,36-57`).
- **Kind**: per-entity read-that-writes; static mutable (Forge map).
- **What breaks**: two threads wrapping the same entity get two `CraftEntity` objects; one is lost with its state (`lastDamageEvent` `CraftEntity.java:37`, equipment cache). Concurrent `fakePlayers.put` -> corrupt map (rare branch: only non-MP player classes).
- **Severity**: B **baixo**, C **baixo**.
- **Treatment**: no action in B beyond the K07 lock; C: create wrappers eagerly on spawn, or CAS the field.

### K23. Runtime additions to Bukkit enums (`BukkitEnumHelper`)

- **Where**: `CR/io/github/crucible/util/BukkitEnumHelper.java:55-75` `addInventoryType` (not synchronized at `67b53034`; `[patches]` review), called from chunk load `MC/net/minecraft/world/chunk/storage/AnvilChunkLoader.java:547` (once per TE class per loader); `addBukkitEnvironment` `:27-30` (K13); `addBukkitEntityType` `:41-53` writes `EntityType.NAME_MAP/ID_MAP` (startup only, `CraftEntity.java:497-506`); `addBukkitBiome` `:22-25` (static init of `CraftBlock`, `CraftBlock.java:195`).
- **Kind**: static mutable (enum value arrays and lookup maps rewritten by reflection).
- **What breaks**: a chunk load in two region threads meeting new TE classes -> two concurrent `EnumHelper.addEnum` on `InventoryType` (lost enum value, `ArrayIndexOutOfBounds`/torn `$VALUES` for readers of `InventoryType.values()`). Covered for the loader side by `H22`.
- **Severity**: B **medio** with parallel chunk loads, otherwise **baixo**; C **medio**.
- **Treatment**: **lock** (one global lock around `addEnum`), or pre-register inventory types for all registered TE classes at startup.

### K24. Server-level player lookups (`getOnlinePlayers`, `getPlayer`, `broadcastMessage`, `getOfflinePlayer`)

- **Where**: `CS:152-157` `playerView` is a live `Lists.transform` over `playerEntityList`; `getOnlinePlayers` copies it (`CS:447-449`); `getPlayer(String)` `CS:453-470`, `getPlayerExact` `CS:474-487`, `getPlayer(UUID)` `CS:490-498`, `matchPlayer` `CS:509-530` all iterate it; `broadcast` `CS:1273-1286` (K07); `getOfflinePlayer(String)` `CS:1290-1316` may call the vanilla profile cache `func_152358_ax().func_152655_a(name)` (`CS:1303`), which can do a blocking Mojang lookup.
- **Kind**: global singleton (reads of `H34`'s list).
- **What breaks**: B: reads are safe as long as nothing removes players during the phase; the only in-phase writer is a kick/logout executed in place (`H34`, and K09 point 3). `getOfflinePlayer(name)` from a region handler can block **all** regions for the duration of an HTTP call because it runs under the K01 monitor (same class of stall as the 30 s stall, `[tick-threads] §6`). C: CME against logins.
- **Severity**: B **baixo** (lists), **medio** (blocking lookup); C **alto**.
- **Treatment**: snapshot-iterating player list (`H34`); for blocking lookups, no change in B (document), C: plugin adapter.

### K25. `JavaPluginLoader.classes` (cross-plugin class cache)

- **Where**: `MC/org/bukkit/plugin/java/JavaPluginLoader.java:64` (`HashMap`), `getClassByName` `:218-236`, `setClass` `:238-247`, `removeClass` `:249-250`; per-plugin `PluginClassLoader.classes` is already a `ConcurrentHashMap` (`MC/org/bukkit/plugin/java/PluginClassLoader.java:63`).
- **Kind**: global singleton collection written during class loading.
- **What breaks**: first use of a plugin class from a region thread (outside K01, e.g. K11/K12 paths) concurrent with another thread loading a plugin class -> lost entry or corrupt map. Same race exists today with async plugin threads.
- **Severity**: B **baixo**, C **baixo**.
- **Treatment**: `ConcurrentHashMap` (one-line change) or no action.

### K26. `processQueue` and `Waitable` (handoff to the main thread)

- **Where**: `MS:188` (`ConcurrentLinkedQueue`), drained at `MS:909-914` (global phase). Producers that wait for the result: rcon `MS:1818-1840`, chat/command paths `MC/net/minecraft/network/NetHandlerPlayServer.java:1078-1086,1177-1185,1249-1283`, login `ThreadPlayerLookupUUID` (`[tick-threads] §2`).
- **Kind**: cross-thread handoff.
- **What breaks**: nothing today from region work (no producer on that path). Any future code that posts a `Waitable` from a region thread and calls `get()` during B deadlocks (main thread is at the join).
- **Severity**: B **baixo**, C **baixo**.
- **Treatment**: no action; reuse the queue (without waiting) as the "apply at the join point" mechanism for K09/K11/K13.

### K27. Crucible.yml / bukkit.yml / spigot.yml driven features in this layer

| Feature | Key / file:line | Thread fact | Verdict |
|---|---|---|---|
| Async lighting | `use-async-lighting` (spigot.yml) `SpigotWorldConfig.java:276`; `W:4943-4952` | per-world executor | K21 / `M16` |
| Auto-save | `ticks-per.autosave` (bukkit.yml) `CS:241,757` -> `MS:860-873` | global phase, writes go to File IO thread | B: no action. C: saving needs regions paused or per-region save (`H23-H25`) |
| Chunk send throttle | `crucible.optimization.maxChunkSendsPerTick` `CrucibleConfigs.java:154`, used `MC/net/minecraft/entity/player/EntityPlayerMP.java:347`, `SpigotWorldConfig.java:244` | per player, inside the player entity tick | `H29-H30` |
| Chunk compression level | `CrucibleConfigs.java:167`, `MC/net/minecraft/network/play/server/S26PacketMapChunkBulk.java:31-38` (`ThreadLocal<Deflater>`) | Netty threads | harmless |
| Region file cache size | `crucible_chunkCacheSize` `CrucibleConfigs.java:144`, `MC/net/minecraft/world/chunk/storage/RegionFileCache.java:18,43,113` | static synchronized cache | `H25` |
| Load chunk on request / on Forge tick | `CrucibleConfigs.java:19,22` | sync loads from any caller | `H07` |
| Entity bounding-box check | `checkEntityBoundingBoxes` default true `CrucibleConfigs.java:25`; `CH:124-153` from `W:1931` | touches only the entity being checked and logs | harmless |
| Entity collision logging | `entityCollisionChecks` default false `CrucibleConfigs.java:91` | static Trove map | K15 |
| Redstone update speed | `CrucibleConfigs.java:55,58`; field `last` on the **singleton** block `MC/net/minecraft/block/BlockRedstoneDiode.java:50-54`, `BlockRedstoneTorch.java:122-126` | racy write, check disabled at default `-1` | harmless at default; if enabled, one throttle per block *type* for the whole server (already wrong semantics) |
| No grass chunk loading | `CrucibleConfigs.java:210`, `MC/net/minecraft/block/BlockGrass.java:81` | read-only | harmless |
| Protected world | `CrucibleConfigs.java:183-189`, `MC/net/minecraftforge/event/ForgeEventFactory.java:77-85,149` | read-only | harmless |
| Tick rate / time | `CrucibleConfigs.java:198-201` | outer loop | harmless (global) |
| Timings options | `CrucibleConfigs.java:228-247`, toggled by `/timings` `TimingsCommand.java:141,146` | global | K18 |
| `tileentities.yml` / `entities.yml` | `CH:38-39,220-303` | static caches | K14, `M34` |

### K28. Scoreboard (Bukkit side)

- **Where**: `CR/.../scoreboard/CraftScoreboardManager.java:14-15,27-33`. State analysed in `M29`; the thread check is K09 point 4.
- **Severity / treatment**: per `M29` (B medio) plus K09 deferral.

---

## 2. Second pass: fields checked and judged harmless (one line each)

Method: `rg` for every `static` field in `CR/org/bukkit/craftbukkit`, `CR/org/spigotmc`, `CR/co/aikar`, `CR/io/github/crucible`, `CR/thermos`, `CR/net/minecraftforge/cauldron` (311 lines, list kept in `scratchpad/estudo/work_estatico/bukkit_statics.txt`), plus the collection fields of `CraftServer`, `CraftWorld`, `CraftChunk`, `CraftPlayer`, `CraftEntity`, `SimplePluginManager`, `CraftScheduler`, and every hybrid-added mutable static in `MC/net` and `MC/cpw` (`rg 'static(?! final).*// (CraftBukkit|Cauldron|Spigot|Thermos|Crucible|Paper)'`). Constants (`static final` primitives, `String`, `Pattern`, `Logger`) and bootstrap/updater/metrics classes not reached at tick time were skipped.

- `SPM:42-45` `fileAssociations`, `loaders`, `plugins`, `lookupNames`: written only while loading plugins (startup / reload).
- `SPM:48-49` `permissions`, `defaultPerms`: written on plugin enable (`addPermission` `SPM:602-611`); read by K07 paths; no tick-time writer.
- `MC/org/bukkit/event/HandlerList.java:18,30`: volatile baked array + `synchronized` bake/register: safe.
- `MC/org/bukkit/metadata/MetadataStoreBase.java:9`: all accessors `synchronized` (`:33,56,74`): safe (metadata stores `CS:125-127`, `CW:50`).
- `MC/org/bukkit/plugin/SimpleServicesManager.java:27`: every access under `synchronized (providers)`.
- `MC/org/bukkit/plugin/messaging/StandardMessenger.java:16-21`: guarded by `incomingLock`/`outgoingLock`.
- `MC/org/bukkit/command/SimpleCommandMap.java:23` `knownCommands`: written by `register` (`synchronized`, `:138`) at plugin enable; tick-time access is read-only.
- `CR/org/spigotmc/VanillaCommandWrapper.java:18` `allowedCommands`: filled at config load (`SpigotConfig.java:149`), read-only after.
- `CS:123` `offlinePlayers`: `MapMaker().weakValues()` concurrent map.
- `CS:101` `spigot`: set once in the constructor.
- `CS:132-147` config scalars (`chunkGC*`, spawn limits, `playerCommandState` written only by console/command dispatch in the global phase).
- `CW:49` `populators`: filled at world init (`W:432`, `MS:433`, `DimensionManager.java:348,419`); read during chunk population (`ChunkProviderServer.java:445`, plugin code there is `H13`).
- `CW:96-101` spawn limits and `chunkLoadCount`/`chunkGCTickCount`: config, or racy counters for chunk GC heuristics (`CW:279`, `CW:1438-1465`, GC runs in `WorldServer.java:308`, serial).
- `CR/org/bukkit/craftbukkit/v1_7_R4/CraftChunk.java:15-17` static empty arrays: shared constants, never written.
- `CraftChunk.java:26` `weakChunk`: per Bukkit chunk, refreshed lazily (benign race; access path is `H42`).
- `CR/.../entity/CraftPlayer.java:56-57` `channels`, `hiddenPlayers`: per player; `hiddenPlayers` read by the tracker (`EntityTrackerEntry.java:423`), which runs after the parallel phase in B.
- `CR/.../block/CraftBlock.java:39-40` `BIOME_MAPPING`, `BIOMEBASE_MAPPING`: built in static init (`:180-202`).
- `CR/org/bukkit/craftbukkit/v1_7_R4/CraftSound.java:9`, `MinecraftParticles.java:55`, `CraftStatistic.java:16-17`, `util/CraftChatMessage.java:21`, `scoreboard/CraftCriteria.java:8-9`, `scoreboard/CraftScoreboardTranslations.java:9`, `inventory/CraftItemFactory.java:15-17`, `inventory/CraftMeta*.java` `ItemMetaKey`s and `ImmutableMap`s (`CraftMetaItem.java:587-588`), `TrigMath.java`: filled in static init, read-only.
- `CR/.../potion/CraftPotionBrewer.java:14` `cache` (`HashMap`): only via `ThrownPotion.getEffects()` (`CraftThrownPotion.java:23`), i.e. plugin code under the K01 monitor; low risk.
- `CR/org/bukkit/craftbukkit/v1_7_R4/CraftTravelAgent.java:9` `DEFAULT`: never assigned (always `null`), read at `Entity.java:2485`, `ServerConfigurationManager.java:713,994`.
- `CR/.../util/LongHashSet.java`, `util/LongObjectHashMap.java`, `util/WeakCollection.java`: instance structures used by `ChunkProviderServer.chunksToUnload` (`H05`), `SpawnerAnimals` (`M26`), `CraftScoreboardManager` (`M29`).
- `CR/org/bukkit/craftbukkit/v1_7_R4/util/AsynchronousExecutor.java`: dead code (no references, `chunks.md` §0).
- `CR/thermos/wrapper/ThermiteMap.java`, `LongHashMapTrove.java`: no references outside their own files (grep).
- `CR/thermos/wrapper/ChunkBlockHashMap.java`, `VanillaChunkHashMap.java`: `H01-H03`.
- `CR/thermos/thermite/ThermiteRandom.java`: per-instance non-atomic seed; the shared instance is `World.rand` (`M17`).
- `CR/thermos/thermite/ThermiteTeleportationHandler.java`: static methods only, no state; the transfer it performs is `H35`.
- `CR/org/spigotmc/AntiXray.java`: instantiated (`SpigotWorldConfig.java:222`) but `updateNearbyBlocks`/`obfuscate` have no callers in this tree (grep): inactive.
- `CR/org/spigotmc/SpigotConfig.java:35-59` statics: loaded at startup / `/reload`, read-only at tick.
- `CR/org/spigotmc/SpigotWorldConfig.java`: per world, read-only except `currentPrimedTnt` (`M33`).
- `CR/org/spigotmc/TrackingRange.java:21`: static method, no state.
- `CR/io/github/crucible/CrucibleConfigs.java:13` `configs`: read-only at tick (written by `/crucible reload` and `/timings` toggles, global phase).
- `CR/io/github/crucible/CrucibleTimings.java:29` `simpleNameMap`: concurrent weak map.
- `CR/co/aikar/timings/TimingIdentifier.java:47,93`, `TimingsManager.java:44`, `TimingHandler.java:42`: concurrent map / atomics.
- `CR/co/aikar/timings/TimingsManager.java:50-60`, `Timings.java:43-46`, `TimingsExport.java:67-68`, `TimedTimings.java:15`: main-thread or command state.
- `CR/io/github/crucible/eventfactory/PluginClassLoaderFactory.java:21` `cache`, `CR/io/github/crucible/api/CruciblePluginTransform.java:9` `transformers`: written at plugin registration only.
- `CR/net/minecraftforge/cauldron/api/inventory/OreDictionaryEntry.java:13`, `apiimpl/inventory/OreDictionaryInterface.java:17`: lazily grown by plugin API calls only.
- `CR/net/minecraftforge/cauldron/command/CauldronCommand.java:27` `debug`: flag toggled by a command, read in `ForgeEventFactory.java:92-247` (benign).
- `CR/net/minecraftforge/cauldron/CauldronUtils.java:15` `deobfuscated`; `CH:36-37` `tickingDimension`, `tickingChunk`: no references outside the declaration (grep).
- `CR/net/minecraftforge/cauldron/configuration/CauldronConfig.java:11` `instance`: startup.
- `MC/net/minecraft/block/BlockDispenser.java:33` `eventFired`: only ever set to `false` (`:150`, grep finds no `= true`), so the checks in `Bootstrap.java`, `BehaviorDefaultDispenseItem.java:80`, `BehaviorProjectileDispense.java:33` are constant: inactive.
- `MC/net/minecraft/world/storage/SaveHandler.java:40` `initializedBukkit`: startup.
- `MC/cpw/mods/fml/common/registry/EntityRegistry.java:125-126`, `MC/net/minecraft/tileentity/TileEntity.java:33` `classToNameMap`: registration time, read-only after.
- `MC/net/minecraft/block/Block.java:1200-1201,2272-2276`: Forge already uses `ThreadLocal` for harvesters and captured drops: safe.
- `CR/org/spigotmc/WatchdogThread.java:24-26`: atomics / single instance (K20).
- `CR/org/spigotmc/Metrics.java`, `CR/io/github/crucible/Metrics.java`: own threads; read player counts (pre-existing racy read, harmless).
- `CR/org/spigotmc/CustomTimingsHandler.java:49-50`: lazily set reflection handles (benign race).
- `CR/io/github/crucible/util/HashedArrayList.java`: `M02`.

---

## 3. Closing lists

### 3.1 The 10 items most likely to bite in the synchronized parallel phase (B)

1. **K08** `getEffectiveSide()` by thread name: 72/224 mod jars ask it; on region threads they get `CLIENT`. Silent wrong behaviour or client-class crashes.
2. **K03** `CraftEventFactory.blockDamage/entityDamage`: written for every moving entity every tick; NPE window at `CEF:502-504` inside the entity tick, wrong damage attribution for protection plugins.
3. **K09** `isPrimaryThread()` false on region threads: `kickPlayer` and `getNewScoreboard` throw inside region work, 16/49 plugins change behaviour, Timings go blind.
4. **K06** capture flags and buffers per world (`M20-M22`): tree growth / FakePlayer placement in one region captures or discards another region's block writes and drops.
5. **K01** the global `callEvent` monitor: serializes all plugin handlers fired from region work (WorldGuard listens to `BlockPhysicsEvent` and `InventoryMoveItemEvent`), and turns any blocking cross-region wait we add into a deadlock.
6. **K02** plugin handlers enumerating `loadedEntityList` (`CraftWorld.getEntities*`) or touching foreign blocks/TEs while other regions run: CME, torn TE NBT, sync chunk loads.
7. **K07** `SimplePluginManager.permSubs/defSubs` written outside any lock when a FakePlayer's `CraftPlayer` is created in a TE tick.
8. **K10** `callSyncMethod(...).get()` from a region thread: total deadlock (Skript, Citizens, promenus reference it).
9. **K14** `CauldronHooks` static tick caches hit on every TE/entity tick and lazily written (plus `M34` config writes).
10. **K11** command-block minecarts (and command blocks, if block ticks move to regions) running plugin and vanilla commands on region threads without the plugin lock.

Honourable mentions: K04/K05 (static placement/tree context), K12 (plugin Forge listeners unlocked), K13 (dimension hot-load from a portal in a region tick).

### 3.2 Check of `docs/pesquisa/arte-previa.md` §4 items that fall in this area

Section 4 is about vanilla/Forge structures; almost all of it belongs to the `mundo` and `chunks` reports. Items that touch the Bukkit/Crucible layer:

| arte-previa §4 item | Verdict in this area | Evidence |
|---|---|---|
| 9. `MinecraftServer` globals (`tickCounter`, `worldTickTimes`, `getServer()` static, ...) | **Confirmed and extended**: the hybrid adds more server-wide statics: `currentTick` `MS:186` (K17), `callingForgeTick` `MS:208`, `bannedTileEntityUpdates` `MS:209` (`H22`), `primaryThread` `MS:187` (K09), `processQueue` `MS:188` (K26), `currentTps` `MS:205`. `worldTickTimes`/`tickCounter` themselves: see `[tick-threads] §3` and `mundo.md` | as cited |
| 10. `DimensionManager` maps (load/unload of dimensions) | **Confirmed, Bukkit side**: hot-load also writes `CraftServer.worlds` (`CS:1031-1038` via `W:493`), adds Bukkit `Environment` enums (`DimensionManager.java:600`), and races with the `craftWorldLoading` static (`CH:35`, `DimensionManager.java:282`) (K13). Forge maps: `H35` / `[tick-threads]` review | K13 |
| 11. `EVENT_BUS` listeners assume one thread; region must decide which thread fires each event | **Confirmed and extended**: Bukkit events are serialized by one monitor (K01), but Crucible lets **plugins** subscribe to the Forge bus (`CrucibleEventBus`), and those run unlocked (K12). Forge `EventBus.post` adds Timings per listener (`EventBus.java:151-175`), safe off the main thread only because Timings no-op there (K18) | K01, K12, K18 |
| 12. `FakePlayerFactory` static cache | **Confirmed, with a Bukkit-specific path**: `fakePlayers` is `public static HashMap` (`FakePlayerFactory.java:19`), written by `CraftEntity.getEntity` for non-MP player classes (K22); more important, the first Bukkit event for any FakePlayer creates a `CraftPlayer` whose `PermissibleBase` writes the plugin manager's permission maps (K07) | K07, K22 |
| 8. `ServerConfigurationManager.playerEntityList` (global) | **Confirmed, Bukkit side**: every `Bukkit.getOnlinePlayers/getPlayer*` iterates it through `playerView` (`CS:152-157,447-530`) (K24); writer in region work is a kick executed in place (`H34`), which `CraftPlayer.kickPlayer` currently blocks by throwing (K09) | K24 |
| 1-7, 13, 14 (World lists, chunk provider, chunk internals, tick lists, lighting array, `IntCache`, `nextEntityID`, `ForgeChunkManager`, `ChunkIOExecutor`) | **Outside this area** (vanilla/Forge structures); covered by `mundo.md` (1, 4, 5, 7) and `chunks.md` (2, 3, 13, 14). `IntCache` (6) was not checked here | — |
| "Fronteiras de região" paragraph (neighbour notifications, explosions, AABB queries) | Bukkit side **confirmed**: `BlockPhysicsEvent` on every neighbour notification and `EntityExplodeEvent` route those boundary effects through the K01 monitor (`M36`); explosions also write `CraftEventFactory.entityDamage` (K03) | K01, K03 |
| Singletons of mods (IC2, AE2, ...) | Not in this area | — |

Items of §4 that were general knowledge and turn out **different** in 1.7.10 Crucible, as far as this area sees: there is no `AsyncCatcher` (confirmed again: grep `AsyncCatcher` over `CR/` and `MC/` finds nothing); thread checks exist only as the scattered `isPrimaryThread()` uses of K09 and the name check of K08.

### 3.3 What I could not find or verify

- Runtime behaviour of everything above (no server was started). In particular the K03 NPE window and the K07 map corruption are deduced from code, not reproduced.
- How often mods in this pack create a **new** FakePlayer per operation (drives K07/K22 frequency): not measured.
- Which plugins in **production** listen to `BlockPhysicsEvent`, `InventoryMoveItemEvent`, `EntityDamageByBlockEvent` or call `callSyncMethod`/`kickPlayer`: counts above are from the test copy `server-test3/plugins` (49 jars), not verified against production.
- Whether the 72 mod jars that reference `getEffectiveSide` call it on tick paths (vs. only at init or in packet handlers): not verified.
- Whether vanilla `ServerCommandManager`/`CommandHandler.executeCommand` is patched to fire Bukkit events (relevant for mods that run commands from TEs, e.g. computers): not checked.
- `PlayerProfileCache` (`func_152358_ax`) internals and whether `func_152655_a` does a network lookup on a cache miss: vanilla source not read in this pass; "may block" in K24 is **not verified**.
- `IntCache` (arte-previa §4 item 6): not checked (outside this area).
- `org/bukkit/craftbukkit/chunkio`: does not exist in this tree (same note as `chunks.md` §0).
- Exact behaviour of a Java 8 `HashMap` under the specific concurrent patterns of K07/K14 (corruption vs. lost entries vs. exceptions): general knowledge, not tested.
