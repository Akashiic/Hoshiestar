# Task 0.8: thread-hostile static and global state in vanilla, Forge and FML (area "estatico")

Read-only code study. Nothing was run, built or edited outside this file. Every failure described below is
derived from reading code ("not verified at runtime" applies to all of them).

## 0. Conventions

- `MC/` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad/build/Crucible/eclipse/cauldron/src/main/java/`
  (decompiled Minecraft + Forge + FML with all Crucible patches applied, MCP names). Line numbers are lines of the final patched file.
- `CR/` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad/build/Crucible/src/main/java/` (Crucible, CraftBukkit, Spigot, Thermos, Aikar timings).
- **B** = Degrau B, synchronized parallel phase (3-4 region threads tick TEs and entities of different regions at the same time; everything else waits).
  **C** = Degrau C, independent regions with their own clocks.
- "Region work" = TE `updateEntity`, entity `onUpdate`, and the block updates / neighbour notifications / chunk loads they trigger.
  "Global phase" = everything that stays serial (network, plugins' scheduler, tick events, chunk unload, autosave).
- Kind labels: per-world collection | per-chunk | static mutable | global singleton | re-entrancy/ordering assumption | cross-thread handoff.
- Prior work cited, not redone: `docs/pesquisa/crucible-tick-e-threads.md` = `[tick-threads §N]`, `docs/pesquisa/crucible-arquitetura-patches.md` = `[patches §N]`,
  `docs/pesquisa/arte-previa.md` §4 = `[arte §4.N]`, and the sibling report of this task for the per-world area
  `scratchpad/estudo/hostis/mundo.md` = `[mundo Mnn]`.

Three facts that shape this area:

1. **Block and Item objects are singletons** (one object per block/item type in the registry, `MC/net/minecraft/block/Block.java:59`,
   `MC/net/minecraft/item/Item.java:62`). An *instance* field of a `Block` or `Item` subclass is therefore as global as a `static` field.
   Several of the worst items below are instance fields of blocks, not `static` fields.
2. **Forge already knew part of this.** `Block.harvesters`, `Block.silk_check_meta`, `Block.captureDrops` and `Block.capturedDrops` are
   `ThreadLocal` in this code (`MC/net/minecraft/block/Block.java:1200-1201, 2272-2291`). They are safe and need no action.
3. **The object pools of 1.7.2 are gone.** `AxisAlignedBB.getBoundingBox` and `Vec3.createVectorHelper` just call `new`
   (`MC/net/minecraft/util/AxisAlignedBB.java:13-16`, `MC/net/minecraft/util/Vec3.java:10-13`). There is no `AABBPool`/`Vec3Pool` class
   (rg for `AABBPool|Vec3Pool|getAABBPool|getWorldVec3Pool` over `MC/net` and `MC/cpw`: no hits).

How the list was built: (1) rg for every `static` field that is not `final` under `MC/net/minecraft`, `MC/net/minecraftforge`, `MC/cpw`
(client, GUI, render, relauncher and launcher packages filtered out: 362 declarations left), (2) rg for every `static final` field whose
type is not a primitive/String/Logger/Pattern (761 declarations, filtered by eye for mutable collections, arrays, Random, mutable value
objects), (3) rg for non-final instance fields of every class in `net/minecraft/block`, `net/minecraft/item`, `net/minecraft/tileentity`,
`net/minecraftforge/common`, `net/minecraftforge/fluids` (396 declarations), then for each hit: find writers, decide if a writer can run
during region work. Section 2 lists what was judged harmless.

---

## 1. Items

### S01. Block bounds stored on the block singleton (`Block.minX..maxZ`, `setBlockBounds`, and helpers such as `BlockStairs.field_150152_N/field_150153_O`)

- Declaration: `MC/net/minecraft/block/Block.java:114-119` (`protected double minX, minY, minZ, maxX, maxY, maxZ`, instance fields of the singleton).
  Writer: `setBlockBounds` `Block.java:528-536`. 55 files in `MC/net/minecraft/block` call `setBlockBounds(` (rg -l), plus every mod block.
- Typical write-then-read sequences:
  - `MC/net/minecraft/block/BlockCauldron.java:53-65`: five pairs "`setBlockBounds(...)`; `super.addCollisionBoxesToList(...)`" per call.
  - `MC/net/minecraft/block/BlockFence.java:52,71,85,119` (bounds per call and in `setBlockBoundsBasedOnState` `:88`).
  - `MC/net/minecraft/block/BlockStairs.java:29-30` extra scratch fields; `collisionRayTrace` `:489-503` sets `field_150152_N = true` and
    `field_150153_O`; `setBlockBoundsBasedOnState` `:45-49` reads them.
  - Readers: `getCollisionBoundingBoxFromPool` `Block.java:593-596`, `collisionRayTrace` `Block.java:728-737` (calls
    `setBlockBoundsBasedOnState` and then reads the six fields).
- Tick-time callers: entity movement `World.getCollidingBoundingBoxes` -> `block.addCollisionBoxesToList` (`MC/net/minecraft/world/World.java:1971`,
  also `:2035`); ray traces `World.func_147447_a` -> `block.collisionRayTrace` (`World.java:1461, 1633`), used by projectiles, mob line-of-sight, mods.
- Kind: global singleton + re-entrancy/ordering assumption ("nobody else touches this block object between my set and my read").
- Who touches it: every moving entity in every region (collision against stairs, fences, walls, slabs, cauldrons, hoppers, panes, doors,
  and any mod block with a non-cube shape); TE code doing ray traces. From outside: network phase (player movement checks and block
  placement ray traces) but that is serial.
- What breaks: region 1 computes a collision box from bounds that region 2 just set for a different block state of the same block type.
  Entities clip into stairs and fences, fall through slabs, get pushed out of blocks, arrows hit air or pass through blocks, ray traces return the
  wrong face. Silent wrong results, no exception.
- Severity: **B alto | C alto** (every tick, every entity near a non-full block).
- Treatment: **lock** per block object around the call sites that do "set bounds then read bounds": mixin on the two `World` call sites
  (`addCollisionBoxesToList`, `collisionRayTrace`) wrapping the call in `synchronized (block)`. Uncontended locks are cheap and this covers
  vanilla and the mods that go through `World`. Mods that call `setBlockBounds` from their own code paths stay uncovered. The complete
  alternative, redirecting the six fields to a thread-local holder with ASM, is invasive and slower. Measure the lock first.

### S02. `BlockFurnace.field_149934_M` (static "keep inventory" flag used during the lit/unlit swap)

- Declaration: `MC/net/minecraft/block/BlockFurnace.java:27` (`private static boolean`).
- Writes: `updateFurnaceBlockState` `:118-140` sets it `true` (`:122`), swaps the block with `setBlock` (`:126/130`), sets it `false` (`:133`),
  then re-attaches the old TE (`:136-139`). Read: `breakBlock` `:180` (drops the inventory only when the flag is `false`).
- Tick-time caller: `TileEntityFurnace.updateEntity` -> `BlockFurnace.updateFurnaceBlockState` (`MC/net/minecraft/tileentity/TileEntityFurnace.java:322`).
- Kind: static mutable + re-entrancy assumption.
- Who touches it: every vanilla furnace that starts or stops burning (TE tick); any break of a furnace (player in network phase, machines and
  explosions in region work).
- What breaks: (a) region 1 holds the flag `true` while region 2 breaks a furnace: no drop, TE removed, **items lost**. (b) region 1 sets it
  `false` while region 2 is inside its own swap: region 2's `breakBlock` drops the inventory and then `:136-139` puts the same TE back,
  **items duplicated**.
- Severity: **B alto | C alto** (dupe in vanilla code, triggered by ordinary furnaces).
- Treatment: **thread-local** (mixin replacing the static boolean with a `ThreadLocal<Boolean>`, or a static `synchronized` block around
  `updateFurnaceBlockState` that is reentrant for the inner `breakBlock`). Many mods copied this exact pattern into their own machine
  blocks: a bytecode scan of the pack for "static boolean written around setBlock in a Block class" is needed (not done here, **nao verificado**).

### S03. FML network channels: `FMLEmbeddedChannel` attributes and `generatePacketFrom` (SimpleNetworkWrapper, FMLEventChannel, FMLNetworkHandler)

- Declarations: `MC/cpw/mods/fml/common/network/simpleimpl/SimpleNetworkWrapper.java:86` (`channels`, one map per wrapper; mods keep the
  wrapper in a static field), `MC/cpw/mods/fml/common/network/FMLEventChannel.java:28`, `MC/cpw/mods/fml/common/network/internal/FMLNetworkHandler.java:64`
  (`private static EnumMap<Side, FMLEmbeddedChannel> channelPair`), `MC/cpw/mods/fml/common/network/FMLEmbeddedChannel.java:20` (extends Netty `EmbeddedChannel`).
- Writes (two-step "set target attribute, then write"):
  `SimpleNetworkWrapper.sendTo` `:192-197`, `sendToAllAround` `:206-211` (and the other `sendTo*`); `FMLEventChannel.sendTo` `:135-140`,
  `sendToAllAround` `:147-152`; `FMLEmbeddedChannel.generatePacketFrom` `:45-52` (`getAndSet` target, `writeOutbound`, `outboundMessages().poll()`,
  restore target); `FMLNetworkHandler.getEntitySpawningPacket` `:145-157` (called from `EntityTrackerEntry.java:569`).
  Read: `FMLOutboundHandler.write` reads `FML_MESSAGETARGET` and `FML_MESSAGETARGETARGS` from the channel (`MC/cpw/mods/fml/common/network/FMLOutboundHandler.java:250-262`).
- Kind: global singleton (one embedded channel per mod channel, shared by all threads) + re-entrancy assumption.
- Who touches it: mod TEs that sync state to nearby players every few ticks (the most common pattern in tech mods), entity ticks, `getDescriptionPacket`
  implementations built on `getPacketFrom`. From outside: network phase handlers, global-phase tick handlers.
- What breaks: target and argument of two threads interleave. A packet goes to the wrong player or area (one base's data to a player elsewhere);
  target `PLAYER` paired with a `TargetPoint` argument gives a `ClassCastException` in `FMLOutboundHandler`; `generatePacketFrom` can poll the other
  thread's packet or `null` (swapped TE data or an NPE in the caller); the `EmbeddedChannel` internal queues are plain Netty structures meant for one thread.
- Severity: **B alto | C alto** (high frequency, every tech mod).
- Treatment: **lock** per `FMLEmbeddedChannel` around each "set attributes + write/poll" sequence (mixin into the `send*` methods of
  `SimpleNetworkWrapper`/`FMLEventChannel` and into `generatePacketFrom`). The work under the lock is short. Deferring sends to the join
  point would also work but changes packet timing. Vanilla packet queues (`NetworkManager`) belong to the network area (**nao verificado** here).

### S04. `MinecraftForge.EVENT_BUS` posted from region work (listener code of mods)

- Declarations: `MC/net/minecraftforge/common/MinecraftForge.java:28-30` (`EVENT_BUS`, `TERRAIN_GEN_BUS`, `ORE_GEN_BUS`); `EventBus.post` `MC/cpw/mods/fml/common/eventhandler/EventBus.java:151-198`.
- Posts inside region work (examples, all opened):
  `LivingUpdateEvent` for every living entity every tick (`MC/net/minecraftforge/common/ForgeHooks.java:297`, called at `MC/net/minecraft/entity/EntityLivingBase.java:1900`);
  `LivingAttackEvent` `ForgeHooks.java:302` (`EntityLivingBase.java:847`, `EntityPlayer.java:1094`); `LivingDeathEvent` `:313`; `LivingDropsEvent` `:318`;
  `EntityJoinWorldEvent` on every spawn (`MC/net/minecraft/world/World.java:1831`); `ForceChunkEvent`/`UnforceChunkEvent` (`MC/net/minecraftforge/common/ForgeChunkManager.java:743,784`);
  `ExplosionEvent.Start/Detonate` (`MC/net/minecraftforge/event/ForgeEventFactory.java:491,506`); harvest drops (`Block.java:674`).
- With Timings on, `post` wraps each listener in `CrucibleTimings` timers (`EventBus.java:153-171`); `TimingHandler.startTiming/stopTiming`
  return early off the primary thread (`CR/co/aikar/timings/TimingHandler.java:102,114`), so this does not crash, it only loses the measurement.
- Kind: cross-thread handoff into unknown code (the bus itself has no per-post shared state; the listeners do).
- Who touches it: every region thread, very often (`LivingUpdateEvent`). Listener code belongs to mods and is written for one thread
  (static HashMaps keyed by entity/player, counters, caches).
- What breaks: anything inside mod listeners: `ConcurrentModificationException`, lost updates, dupes. Cannot be judged from vanilla code.
- Severity: **B alto | C alto**.
- Treatment: **lock by default, allowlist per mod**: a post from a region thread whose listener array contains a listener of a mod not
  marked safe (module (4) of doc 02) runs under one global "mod listener lock"; posts whose listeners are all allowlisted (or empty) run
  in parallel. The listener array is known per event class (`ListenerList.getListeners(busID)`), so the decision is a cached flag per
  event class. Measure how many mods listen to `LivingUpdateEvent` in the pack first (**nao verificado**; the inventory has no column for it).

### S05. Event bus internals when listeners register or unregister at runtime (`ListenerList`, `ListenerListInst`, `ASMEventHandler`, lazy `LISTENER_LIST`)

- Declarations: `MC/cpw/mods/fml/common/eventhandler/ListenerList.java:9-10` (`static allLists`, `static maxSize`); per instance `:119-121`
  (`rebuild`, `listeners`, `priorities` are plain fields, not volatile); `EventBus.java:29` (`static int maxID`), `:31` (`ConcurrentHashMap` of
  plain `ArrayList`); `MC/cpw/mods/fml/common/eventhandler/ASMEventHandler.java:19` (`static int IDs`), `:23` (`static final HashMap cache`);
  per event class `private static ListenerList LISTENER_LIST` added by `MC/cpw/mods/fml/common/asm/transformers/EventSubscriptionTransformer.java:167-210`
  and created lazily in `setup()` ("if null, create").
- Read path used by every `post`: `ListenerListInst.getListeners` `ListenerList.java:181-185` -> `buildCache` `:195-214` when `rebuild` is set
  (copies the `priorities` ArrayLists, `:163`).
- Write path: `register` `:216-220` / `unregister` `:222-229` (plain `ArrayList.add/remove`, `rebuild = true`); `EventBus.register` `:49-98`,
  `register(Class,...)` `:101-122`, `crucible_register` `:125-138`, `unregister` `:140-149`; `ASMEventHandler.createWrapper` `:73-129` (`cache` get/put, `IDs++` in `getUniqueName` `:133-135`).
  Only `extendMasterList` is `synchronized` (`ListenerList.java:28`). There is **no** `shutdown` flag in this EventBus.
- Kind: global singleton.
- Who touches it: all region threads read (every post); writers are mods that register handlers lazily (in a TE constructor, on first use, on player
  join). How often that happens in this pack: **nao verificado**.
- What breaks: a registration from one thread while another thread rebuilds the cache: `ConcurrentModificationException` or
  `ArrayIndexOutOfBoundsException` in `buildCache`, or a stale array without the new listener (non-volatile fields); two threads creating wrappers
  at once can get the same generated class name (`IDs++` race) -> `LinkageError` (duplicate class definition), or corrupt the `cache` HashMap.
- Severity: **B medio | C medio**.
- Treatment: **defer to a queue applied at the join point** for `register/unregister` called from a region thread, and **lock** inside
  `ListenerListInst` (`buildCache`, `register`, `unregister` synchronized on the instance, `listeners` made volatile) so the read path stays lock-free
  once built. Mixin.

### S06. `FakePlayerFactory.fakePlayers`, `MINECRAFT_PLAYER`, and `ExplosionEvent.exploder_fake`

- Declarations: `MC/net/minecraftforge/common/util/FakePlayerFactory.java:19` (`public static Map<GameProfile, FakePlayer> fakePlayers = HashMap`, made public by Cauldron), `:20` (`static MINECRAFT_PLAYER`);
  `MC/net/minecraftforge/event/world/ExplosionEvent.java:36` (`public static FakePlayer exploder_fake`).
- Sites: `get` `FakePlayerFactory.java:36-57` iterates `entrySet()` and matches by **name only** (`:41-47`), so a cached FakePlayer is returned for
  any world, even if its `worldObj` is another dimension; puts at `:55`. `getMinecraft` `:22-29` (lazy, unsynchronized). `unloadWorld` `:59-70`
  (iterator remove). `ExplosionEvent` constructor `:41` re-reads the factory whenever `exploder_fake.worldObj != world`; used `:69`.
- Kind: global singleton (cache) + shared mutable entity.
- Who touches it: machines in TE ticks (67 jars reference FakePlayer, `docs/dados/inventario-mods.csv` column `fakeplayer`), explosions in region work.
  Mods then **mutate** the returned player (position, rotation, held item, inventory) before calling `onItemUse`, `interactWith`, `attackTargetEntityWithCurrentItem`.
- What breaks: concurrent `put` and iteration of the HashMap (`ConcurrentModificationException`, corruption). Worse: two regions share the same
  FakePlayer object (same name, e.g. `[Minecraft]` or a mod's fixed name) and overwrite its held item/position mid-use: wrong item used, item
  inserted into the wrong inventory (**dupe or loss**), actions at wrong coordinates, actions in the wrong world.
- Severity: **B alto | C alto**.
- Treatment: **per-region instance**: mixin on `FakePlayerFactory.get/getMinecraft` keyed by (region, world, name) in a concurrent map; `exploder_fake`
  per world/region. Mods that cache their own FakePlayer in a static field stay unsafe (module or serial list). This confirms the doc 02 §5 plan
  "FakePlayer: um por região".

### S07. `Entity.nextEntityID` (static id counter) — extends `[mundo M19]`

- Declaration: `MC/net/minecraft/entity/Entity.java:93`; writes `:201` (constructor) and `:2705` (`resetEntityId`, called for every entity of a chunk
  taken back from the dormant cache, `MC/net/minecraftforge/common/ForgeChunkManager.java:908-925`).
- New detail beyond M19: the Thermos "error correction" in `EntityTracker.addEntityToTracker` (`MC/net/minecraft/entity/EntityTracker.java:232-237`)
  sees the id already tracked, calls `removeEntityFromAllTrackingPlayers(newEntity)`, which removes **the other entity's** entry by id (`:304`),
  then re-adds the new one. With a duplicate id the older entity silently stops being sent to clients (invisible but alive). Every
  `ExplosionEvent.Start` also constructs a throwaway `EntityTNTPrimed` (`ExplosionEvent.java:64-69`), consuming ids.
- Kind: static mutable. Who: every entity construction in region work (item drops of machines, projectiles, spawns, FakePlayer construction).
- Severity: **B alto | C alto**. Treatment: **atomic counter** (`AtomicInteger`, mixin on both writers).

### S08. `BlockRedstoneWire.field_150181_a` ("wires provide power") and `field_150179_b` (set of blocks to notify)

- Declarations: `MC/net/minecraft/block/BlockRedstoneWire.java:25` (`private boolean field_150181_a = true`), `:26` (`private Set field_150179_b = new HashSet()`),
  instance fields of the wire singleton.
- Writes: `func_150175_a` `:87-182`: flag `false` at `:92`, `getStrongestIndirectPower` `:93`, flag `true` at `:94`; adds to the set at `:176-182`.
  `func_150177_e` `:74-85` copies the set into an ArrayList (`:77`), clears it (`:78`), notifies neighbours.
  Reads of the flag: `isProvidingStrongPower` `:349`, `isProvidingWeakPower` `:354`, `canProvidePower` `:405-408`.
- Kind: global singleton + re-entrancy assumption (the flag exists to stop recursion inside one update).
- Who touches it: neighbour changes caused by TEs (machines emitting or reading redstone, block placement/removal by machines) and by entities
  (pressure plates, tripwires, item frames); scheduled block ticks of repeaters/torches (block-update phase).
- What breaks: while region 1 holds the flag `false`, every wire in region 2 reports "no power" (signals drop, contraptions misfire); the shared HashSet
  is added to and cleared by two threads (`ConcurrentModificationException` at `:77`, lost notifications, wires stuck on or off).
- Severity: **B alto | C alto** (whenever two redstone-using bases tick together).
- Treatment: **thread-local** for both fields (mixin on `BlockRedstoneWire`). Mod cables that copied the pattern need the same (**nao verificado**).

### S09. `BlockRedstoneTorch.field_150112_b` (static burnout map) and Crucible's `last` rate limiters

- Declarations: `MC/net/minecraft/block/BlockRedstoneTorch.java:24` (`private static Map field_150112_b = new WeakHashMap()`, World -> List of toggles);
  `:123` (`private long last`, instance field on the singleton, added by Crucible); same `last` in `MC/net/minecraft/block/BlockRedstoneDiode.java:50`.
- Sites: `func_150111_a` `:27-58` (`containsKey`, `put`, `get`, `list.add`, iteration); `updateTick` `:125-135` (`get`, `list.remove(0)` loop).
  `last` read/written `BlockRedstoneTorch.java:127-128`, `BlockRedstoneDiode.java:53-54`. Defaults `-1` (`CR/io/github/crucible/CrucibleConfigs.java:55,58`).
- Kind: static mutable (map keyed by world, shared by all regions of all worlds).
- Who touches it: torch scheduled ticks and neighbour changes (block updates caused by TEs/entities and the tick list).
- What breaks: `WeakHashMap` is not safe even for concurrent `get` (it expunges stale entries on read); concurrent `add`/`remove(0)` on the per-world
  ArrayList: `IndexOutOfBoundsException`, wrong burnout counts (torches burn out without cause or never). `last`: with the default `-1` the test
  `now - last <= -1` is always false, so the race is harmless; with a positive value it is already a server-wide rate limit across all worlds
  (one torch update anywhere delays every torch), a pre-existing behaviour.
- Severity: **B medio | C alto**.
- Treatment: **per-region instance** of the toggle lists (keyed by world + region), or a **lock** on the map in B (rare path). Leave `last` alone while the config is `-1`.

### S10. `EnchantmentHelper` static modifier objects

- Declarations: `MC/net/minecraft/enchantment/EnchantmentHelper.java:25-28` (`enchantmentModifierDamage`, `enchantmentModifierLiving`, `field_151388_d`
  HurtIterator (thorns), `field_151389_e` DamageIterator), `:24` (`enchantmentRand`, a `java.util.Random`, safe).
- Writes: `getEnchantmentModifierDamage` `:174-185`; `func_152377_a` `:193-198`; `func_151384_a` `:201-210` (stores attacker and target in the shared iterator);
  `func_151385_b` `:213-222`.
- Tick-time callers: `EntityLivingBase.java:1254` (armor protection on every damage), `EntityMob.java:141,165,168` (every mob melee hit),
  `EntityArrow.java:355-356`, `EntityPlayer.java:1368,1427,1430`, `ItemStack.java:662`.
- Kind: static mutable scratch.
- What breaks: torn modifier sums (wrong protection / sharpness damage); the thorns iterator gets the attacker of another region and applies thorns
  damage to an entity owned by another thread (a cross-region write into an entity that is being ticked).
- Severity: **B medio | C medio**.
- Treatment: **thread-local** (mixin replacing the four `GETSTATIC` with `ThreadLocal.get()`), or allocate the small object per call.

### S11. `ForgeChunkManager` static maps

- Declarations: `MC/net/minecraftforge/common/ForgeChunkManager.java:79` (`tickets`: weak-key concurrent map of per-world `ArrayListMultimap`, created
  `:401-402`), `:83` (`playerTickets`, `HashMultimap`), `:87` (`forcedChunks`, per world immutable multimap), `:88` (`pendingEntities`, `HashBiMap`),
  `:90` (`dormantChunkCache`, Guava caches, safe), `:99` (`warnedMods`, `HashSet`).
- Writes: `requestTicket` `:654-684` (`tickets.get(world).put`, `warnedMods.add`); `releaseTicket` `:691-716`; `forceChunk` `:727-751`
  (read-copy-write of the immutable map `:745-746`); `unforceChunk` `:777-789` (same, `:785-788`). The event constructors write
  `ticket.world.activity` (`:355`, `:367`; `activity` is a ConcurrentHashMap, `[patches §2.8]`).
- Kind: static mutable (per-world entries).
- Who touches it: chunk-loader TEs and entity tickets (26 jars use ForgeChunkManager, inventory column `forge_chunkmgr`), in region work.
- What breaks: two threads of the same world force/unforce at once: the copy-on-write update of `forcedChunks` loses one change (a forced chunk
  disappears and unloads under a running machine, or a chunk is never released); concurrent `put` into the per-world `ArrayListMultimap` corrupts it
  or loses tickets; `warnedMods` HashSet race.
- Severity: **B medio | C alto**.
- Treatment: **lock** (one global or one per world) around the mutators (`requestTicket`, `requestPlayerTicket`, `releaseTicket`, `forceChunk`,
  `unforceChunk`, `reorderChunk`). Calls are rare; readers see immutable snapshots.

### S12. `DimensionManager` maps, `MinecraftServer.worlds/worldServers/worldTickTimes`, dimension load/unload from region work

- Declarations: `MC/net/minecraftforge/common/DimensionManager.java:49-60` (`providers`, `spawnSettings`, `worlds`, `dimensions` are `Hashtable`;
  `unloadQueue` `ArrayList` `:54`; `dimensionMap` `BitSet` `:55`; `weakWorldMap` `:56`; `leakedWorlds` `HashMultiset` `:57`; Crucible `classToProviders` `:59`,
  `bukkitDims` `:60`); `MC/net/minecraft/server/MinecraftServer.java:141` (`worldServers` array), `:159` (`worldTickTimes` Hashtable), `:180` (`worlds` ArrayList).
- Sites: `getIDs` `:219-222` (`keySet().toArray(new Integer[size()])`, two separate calls); `setWorld` `:224-270` (puts/removes in `worlds`, edits
  `MinecraftServer.worlds` `:235-238/245`, `worldTickTimes` `:240/247`, rebuilds `worldServers` `:251-269`); `initDimension` `:272` reached from
  `MinecraftServer.worldServerForDimension` `MinecraftServer.java:1069-1083` (portal travel and mod teleporters call it from the entity tick);
  `unloadWorld` `:488-492` (`unloadQueue.add`, called from `MC/net/minecraft/world/gen/ChunkProviderServer.java:572`); `unloadWorlds` `:496-505`
  (iterates and clears the queue, global, `MinecraftServer.java:1020`); `worldTickTimes.get(id)[...]` `MinecraftServer.java:1016`.
  `leakedWorlds`/`weakWorldMap` are only used when `cauldron_logging_worldLeakDebug` is true (default false, `CR/io/github/crucible/CrucibleConfigs.java:64`).
- Kind: global singleton + cross-thread handoff.
- Who touches it: entity tick (portals, teleporters) can load a dimension; chunk unload can queue a dimension unload; every mod reads `DimensionManager.getWorld`.
- What breaks: loading a dimension from a region thread mutates `Hashtable worlds`, the plain `ArrayList MinecraftServer.worlds` and the `worldServers`
  array while other threads iterate them (`ConcurrentModificationException` in loops over `MinecraftServer.worlds`, e.g. CraftServer); the D-07 family
  of NPEs at `MinecraftServer.java:937/944/1016` (`[tick-threads §3]`); concurrent `unloadQueue.add` loses an unload or throws.
- Severity: **B medio | C alto**.
- Treatment: **keep on the global thread**: dimension load and unload requested from a region thread go to a queue applied at the join point; the entity
  waiting for the teleport does not tick meanwhile (same rule as doc 02 §5 "Teleporte e portal"). Plain reads (`getWorld`) are fine (`Hashtable.get` is synchronized).

### S13. `ItemStack.currentPlayer` and `BlockSapling.treeType` (static "context" passed through a field)

- Declarations: `MC/net/minecraft/item/ItemStack.java:61` (`public static EntityPlayer currentPlayer`, Cauldron); `MC/net/minecraft/block/BlockSapling.java:39`
  (`public static TreeType treeType`, CraftBukkit).
- Sites: `ForgeHooks.onPlaceItemIntoWorld` writes `currentPlayer` `MC/net/minecraftforge/common/ForgeHooks.java:533` and clears it `:546` (entered from
  `ItemStack.java:154`); read by `World.canPlaceMultipart` `MC/net/minecraft/world/World.java:4872-4875` (ForgeMultipart / microblocks).
  `treeType` written in `BlockSapling.func_149878_d` (`BlockSapling.java:116-204`) and `MC/net/minecraft/block/BlockMushroom.java:125,130`;
  read and cleared `BlockSapling.java:62-63` and `ForgeHooks.java:559-560`.
- Kind: static mutable + re-entrancy assumption.
- Who touches it: machines that place items through a FakePlayer in TE ticks (deployers, planters, builders), bone meal applied by machines/dispensers,
  sapling random ticks (block-update phase). Players do it in the network phase (serial).
- What breaks: the `BlockPlaceEvent` for a multipart placement checks the wrong player (protection plugins allow or deny the wrong person); a sapling in
  one region reads the tree type set by another and fires `StructureGrowEvent` with the wrong type, or reads `null` and commits without firing the
  event at all (`BlockSapling.java:65` else branch): protection bypass.
- Severity: **B medio | C medio**.
- Treatment: **thread-local** (mixin on the field accesses in these few classes).

### S14. `OreDictionary` writes on the "read" path

- Declarations: `MC/net/minecraftforge/oredict/OreDictionary.java:41-45` (`idToName` ArrayList, `nameToId` HashMap, `idToStack`, `idToStackUn`, `stackToId` HashMap).
- Sites: `getOreID(String)` `:268-280` **creates** an id when the name is unknown; `getOres(String)` `:393-396` calls it; `getOres(int)` `:459-475` adds
  "Filler" entries; `registerOreImpl` `:526-580`. `getOreIDs(ItemStack)` `:334-...` only reads.
- Kind: global singleton (registry) with lazy writes.
- Who touches it: mod machines resolving recipes at tick time by ore name; once a name exists the path is read-only.
- What breaks: two threads create ids at once: two names get the same id, or an id points at another name's list; HashMap corruption. Recipes then
  match the wrong items until restart.
- Severity: **B medio | C medio**.
- Treatment: **lock** the creation branches (`getOreID` miss, `getOres(int)` filler, `registerOreImpl`) on `OreDictionary.class`, and swap the
  collections for concurrent versions (`nameToId`/`stackToId` -> `ConcurrentHashMap`, the lists -> `CopyOnWriteArrayList`; fields are declared as
  `List`/`Map`, so a mixin can replace the objects) so the hot read path stays lock-free.

### S15. World generation reached from region work (IntCache, `BlockFalling.fallInstantly`, `BiomeDecorator`, structure statics, `GameRegistry.generateWorld`)

- Path: a TE, entity or mod touches a chunk that does not exist; `provideChunk` loads it synchronously (`[patches §2.8]`), `originalLoadChunk`
  generates it (`MC/net/minecraft/world/gen/ChunkProviderServer.java:215, 245`) and populates neighbours (`:297` -> `MC/net/minecraft/world/chunk/Chunk.java:1182-1193`
  -> `ChunkProviderServer.populate` `:418-460`). All of this runs on the region thread.
- `IntCache`: `MC/net/minecraft/world/gen/layer/IntCache.java:8-12` static lists. Each method is `synchronized` (`:15`, `:57`, `:75`) **but** `resetIntCache`
  `:57-73` moves every in-use array back to the free list, so thread B's reset hands thread A's in-use arrays to thread C. Callers:
  `MC/net/minecraft/world/biome/WorldChunkManager.java:73,121,160,189,228`; also reached at tick by `World.getBiomeGenForCoordsBody` for unloaded
  chunks (`World.java:242-270`) through the per-world biome cache.
- `BlockFalling.fallInstantly`: `MC/net/minecraft/block/BlockFalling.java:12`, read `:50`; written around population at `ChunkProviderServer.java:431,456`,
  `ChunkProviderGenerate.java:372,455`, `ChunkProviderHell.java:438,532`, `ChunkProviderEnd.java:363,374`.
- `BiomeDecorator` (one per biome singleton): Thermos lock `MC/net/minecraft/world/biome/BiomeDecorator.java:71`; a second thread polls every 100 ms and
  **throws a RuntimeException after ~10 s** (`:104-114`); shared fields `currentWorld`, `randomGenerator`, `chunk_X`, `chunk_Z` set at `:130-133`.
- Structure statics: `MC/net/minecraft/world/gen/structure/StructureStrongholdPieces.java:36-38` (`structurePieceList`, `strongComponentType`, `totalWeight`;
  reset in `prepareStructurePieces` `:59-78`, written `:1226`); shared block selectors `StructureStrongholdPieces.java:39`, `ComponentScatteredFeaturePieces.java:356`.
- `GameRegistry.generateWorld` `MC/cpw/mods/fml/common/registry/GameRegistry.java:109-142`: static `configWorldGenCache` HashMap (`:72`, written `:126-133`);
  lazy `sortedGeneratorList` (`:111-114`); save/restore of the per-world `loadChunkOnProvideRequest` flag (`:121-122`, `:140`) is not atomic.
  `TERRAIN_GEN_BUS`/`ORE_GEN_BUS` listeners of worldgen mods also run here.
- Kind: static mutable + global singleton (biomes) + re-entrancy assumptions.
- What breaks: corrupted biome arrays written permanently into new chunks; sand/gravel in another region falls instantly (teleports to the ground) or
  worldgen sand turns into falling entities; a stalled second decorator thread and then a **server crash** after 10 s; corrupted stronghold layout or NPE;
  `loadChunkOnProvideRequest` of a world left stuck at `true` (overrides the config).
- Severity: **B alto | C alto** (rare per tick, but crash and permanent world damage).
- Treatment: **keep on one thread**: a single global "worldgen lock" taken by any thread that generates or populates a chunk (B), later a dedicated
  generation thread fed by requests (C). Do not make the region thread wait for the global thread inside B (the global thread is waiting for the
  regions: deadlock). Pre-generating the map (doc 02 §5 "Geração de mundo") shrinks how often this path runs.

### S16. Static state touched while loading a chunk from region work (`bannedTileEntityUpdates`, `SideTransformer.allowInvalidSide`, `ChunkIOExecutor`)

- `MinecraftServer.bannedTileEntityUpdates`: `MC/net/minecraft/server/MinecraftServer.java:209` (static ArrayList); written while loading TEs
  `MC/net/minecraft/world/chunk/storage/AnvilChunkLoader.java:542`; read by `CauldronHooks.canUpdate` `CR/net/minecraftforge/cauldron/CauldronHooks.java:306`
  (`[patches §4]`).
- `SideTransformer.allowInvalidSide`: `MC/cpw/mods/fml/common/asm/transformers/SideTransformer.java:35` (static boolean, read `:45` by the class
  transformer); set `true`/`false` around a reflection check `AnvilChunkLoader.java:535,544`.
- `ChunkIOExecutor.instance`: `MC/net/minecraftforge/common/chunkio/ChunkIOExecutor.java:9` (static `AsynchronousExecutor`); its `tasks` is a plain
  `HashMap` (`MC/net/minecraftforge/common/util/AsynchronousExecutor.java:220`), written by `add` `:242-249` (queued loads, global phase) and read by
  `skipQueue` `:338-346` (synchronous loads, any thread).
- Kind: static mutable / global singleton.
- What breaks: `ArrayList.add` during `contains` (`ArrayIndexOutOfBoundsException`, or a missed ban); one thread clears `allowInvalidSide` while another is
  inside the reflection check: "Attempted to load class ... for invalid side" crash, or a client-only class accepted; `tasks` HashMap read during a put;
  two threads synchronously loading the same missing chunk both run stage 1 and stage 2 (`skipQueue`), giving two `Chunk` objects for one position
  (duplicate entities/TEs) — the outcome depends on the chunk map, which belongs to the chunk area.
- Severity: **B medio | C alto**.
- Treatment: `bannedTileEntityUpdates` -> concurrent set (`CopyOnWriteArraySet`, writes are rare); `allowInvalidSide` -> **thread-local**;
  synchronous chunk loads from region threads under a per-world **lock** (the same lock family as S15; chunk ownership rules come from the chunk area).

### S17. `MinecraftServer` static clocks and flags (`currentTick`, `callingForgeTick`) and instance fields used as globals (`tickCounter`, `worldTickTimes`)

- Declarations: `MC/net/minecraft/server/MinecraftServer.java:186` (`public static int currentTick`), `:208` (`callingForgeTick`), `:145` (`tickCounter`), `:159` (`worldTickTimes`).
- Writes: `currentTick++` only in the run loop `:683`; `callingForgeTick` only around the FML server tick events `:827,831,892,896`; `++tickCounter` `:832`;
  `worldTickTimes` per world `:1016`. Reads in region work: `PathNavigate.java:121` (`currentTick`), `ChunkProviderServer.java:621` (`callingForgeTick`),
  mods calling `getTickCounter()`.
- Kind: global singleton (clock).
- What breaks: in B nothing: all writes happen in the global phase before or after the parallel phase, and the phase barrier gives visibility. In C a region
  reading a server-wide counter that another thread advances sees a clock that is not its own.
- Severity: **B baixo | C medio**.
- Treatment: **no action needed** for B; C needs a region-local tick counter (Folia copies game time into each region at tick start, `[arte §1.4]`).
  `callingForgeTick` must stay `false` on region threads (it does: it is only set around the server tick events).

### S18. Vanilla `Profiler` (one per server) and Crucible's replacement (Timings) — extends `[mundo M32]`

- Declarations: `MC/net/minecraft/server/MinecraftServer.java:134` (`theProfiler`); `MC/net/minecraft/profiler/Profiler.java:17-21` (`sectionList`,
  `timestampList` ArrayLists, `profilingSection` String, `profilingMap` HashMap). `startSection` `:31-43`, `endSection` `:46-70`. Enabled only by
  `/debug start` (`MinecraftServer.java:834-838,1512`; stopped `MC/net/minecraft/command/CommandDebug.java:64`).
- Crucible replacement: `CR/co/aikar/timings/TimingHandler.java:43` (`static ArrayDeque TIMING_STACK`); `startTiming`/`stopTiming` return early when
  `!Bukkit.isPrimaryThread()` (`:102`, `:114`); the handler map is a `ConcurrentHashMap` (`CR/co/aikar/timings/TimingsManager.java:44-46`).
- Kind: global singleton.
- What breaks: with `/debug start` on, `endSection` from two threads hits `remove(size()-1)` on shared lists: `IndexOutOfBoundsException` inside the tick.
  Timings never crashes but measures nothing that runs on a region thread (measurement gap, doc 02 component (10)).
- Severity: **B baixo | C baixo** (only with `/debug`).
- Treatment: **no action needed** by default; mixin making `startSection/endSection` no-ops off the global thread, or refuse `/debug start` in parallel
  modes. Per-region measurement is a separate design item.

### S19. Scratch arrays on block singletons: `BlockLeaves.field_150128_a`, `BlockDynamicLiquid.field_149814_b / field_149816_M / field_149815_a`

- Declarations: `MC/net/minecraft/block/BlockLeaves.java:25` (`int[] field_150128_a`, lazily allocated `:114-116`, filled and read `:138-168` in `updateTick` `:100`);
  `MC/net/minecraft/block/BlockDynamicLiquid.java:17-19` (`int field_149815_a`, `boolean[4] field_149814_b`, `int[4] field_149816_M`), written in `updateTick`
  `:33`, `:56`, `:379` and `func_149808_o` `:299-358`, which **returns the shared array** (`:358`) to its caller (`:185`).
- Kind: global singleton scratch.
- Who touches it: leaf random ticks and fluid scheduled ticks (block-update phase). TEs and entities only schedule these updates; the logic runs in
  `tickBlocks`/`tickPending`.
- What breaks: leaves decaying next to logs or floating leaves that never decay; fluids flowing in wrong directions.
- Severity: **B baixo** (only if random/scheduled block ticks move into the parallel phase; if they stay serial, none) **| C medio**.
- Treatment: **thread-local** arrays (mixin), cheap.

### S20. `BlockFire.failures` (Thermos static map)

- Declaration: `MC/net/minecraft/block/BlockFire.java:254` (`static WeakHashMap<World, HashMap<pXYZ, Long>>`); used in `tryCatchFire` `:255-300`
  (`get`/`put` `:265-266`, `get` `:269`, `put` `:281`). The key class `pXYZ` (`:244-252`) overrides `hashCode` but **not** `equals`, so a lookup never hits:
  the map only grows (pre-existing memory leak, the 1-second "failure" backoff never works).
- Kind: static mutable. Who: fire scheduled ticks (block-update phase); fires `BlockBurnEvent` (Bukkit) on the way (`:276-277`).
- What breaks: concurrent puts into `WeakHashMap`/`HashMap` (corruption). Functionally nothing, since lookups never hit.
- Severity: **B baixo | C medio**. Treatment: per-world `ConcurrentHashMap`, and fix `equals` (or remove the map) as a Degrau 0 bug fix.

### S21. `RandomPositionGenerator.staticVector`

- Declaration: `MC/net/minecraft/entity/ai/RandomPositionGenerator.java:10` (`private static Vec3 staticVector`); written `:20-22`, `:28-30`, then passed to
  `findRandomTargetBlock`. Callers in the AI tick: `EntityAIAvoidEntity.java:72`, `EntityAIMoveTowardsRestriction.java:32`, `EntityAIMoveIndoors.java:71`,
  `EntityAIMoveThroughVillage.java:68`, `EntityAIMoveTowardsTarget.java:40`.
- Kind: static mutable scratch. What breaks: a mob uses the direction vector of a mob in another region (flees toward the threat, wanders the wrong way);
  the target stays within range of its own position. Severity: **B baixo | C baixo**. Treatment: **thread-local** or a new `Vec3` per call.
- Pathfinding itself is per call: `new PathFinder(...)` per request (`World.java:4107,4126`), with its own `Path` (`PathFinder.java:14`): no static scratch.

### S22. `NextTickListEntry.nextTickEntryID` — see `[mundo M10]`

- `MC/net/minecraft/world/NextTickListEntry.java:7` (static long), `:19` (increment). Region work creates entries whenever a TE or entity schedules a
  block update. Severity and treatment as in M10: **B alto | C alto**, **atomic counter**.

### S23. `ItemPotion.effectCache` (lazy cache on the item singleton)

- Declaration: `MC/net/minecraft/item/ItemPotion.java:32` (`private HashMap effectCache`); `get`/`put` `:72-77`, `:86-91`.
- Tick-time callers: `EntityPotion.java:102` (splash impact), `EntityWitch.java:109`, dispensers/brewing via `getEffects`.
- What breaks: concurrent `put` into a HashMap during resize: lost entries (a cache, so mostly harmless) or, rarely, a corrupted bucket chain.
- Severity: **B baixo | C baixo**. Treatment: replace with `ConcurrentHashMap` (mixin), trivial.
- Note: `PotionHelper.field_77925_n` (static cache, `MC/net/minecraft/potion/PotionHelper.java:28,113-120`) is only reached from the client-only
  `ItemPotion.getColorFromDamage` (`ItemPotion.java:190-193`): harmless on the server.

### S24. `CraftingManager.lastCraftView` (context field on the recipe singleton)

- Declaration: `MC/net/minecraft/item/crafting/CraftingManager.java:25` (instance field of the singleton `:20`); written by `ContainerWorkbench.java:74` and
  `ContainerPlayer.java:93` before `findMatchingRecipe`; read `CraftingManager.java:354, 369` and passed to `PrepareItemCraftEvent`
  (`CR/org/bukkit/craftbukkit/v1_7_R4/event/CraftEventFactory.java:778-787`).
- Who: players (network phase) write it; auto-crafting machines call `findMatchingRecipe` from TE ticks without setting it.
- What breaks: the pre-craft event fired for a machine carries the view of whichever player crafted last (already true today: a stale view).
  In B players craft only in the serial network phase, so during the parallel phase the field is only read (stale, never torn). In C a player's
  crafting and a machine's crafting can overlap and the machine's event can carry a view that is being replaced.
- Severity: **B baixo | C medio** (in C players' crafting and machines' crafting overlap). Treatment: **thread-local** in C; no action in B.

### S25. Lazy writes in Forge registries read at tick time (`BiomeDictionary`, `ChestGenHooks`, `FluidRegistry.lookupFluidForBlock`)

- `BiomeDictionary.getTypesForBiome`/`isBiomeOfType` -> `checkRegistration` `MC/net/minecraftforge/common/BiomeDictionary.java:421-427` -> `makeBestGuess`
  `:314` -> `registerBiomeType` `:144-170` (writes `typeInfoList` ArrayLists `:114-116` and `biomeList` `:114`) the first time an unregistered biome is queried.
- `ChestGenHooks.getInfo` `MC/net/minecraftforge/common/ChestGenHooks.java:89-96` puts into the static `chestInfo` HashMap (`:35`) for unknown categories.
- `FluidRegistry.lookupFluidForBlock` `MC/net/minecraftforge/fluids/FluidRegistry.java:258-273` builds and publishes `fluidBlocks` (`:44`) lazily
  (benign double build, unsafe publication of a HashBiMap).
- Kind: global singleton with lazy init. What breaks: HashMap/ArrayList races on first use (lost entries, rare corruption).
- Severity: **B baixo | C baixo**. Treatment: **lock** the creation branch (synchronized on the class), or warm up these entries in the global phase at start.

### S26. `ThreadedFileIOBase.threadedIOInstance` (`queueIO` counter)

- Declaration: `MC/net/minecraft/world/storage/ThreadedFileIOBase.java:9` (static singleton); `queueIO` `:64-71` does `contains` + `++writeQueuedCounter`
  (a `volatile long`, `:11`, so `++` is not atomic) + `add`; `waitForFinish` `:73-80` loops `while (writeQueuedCounter != savedIOCounter)`.
- Who: `AnvilChunkLoader` save path (chunk unload, autosave: global phase in B); mods saving chunks from region work (rare).
- What breaks: two concurrent `queueIO` lose one increment; `savedIOCounter` then ends larger than `writeQueuedCounter` and `waitForFinish` (save-all,
  shutdown) **spins forever**; the `contains`/`add` race can queue a loader twice.
- Severity: **B baixo | C medio** (when regions unload/save chunks on their own threads).
- Treatment: **lock** (`synchronized queueIO`) via mixin; cheap.

### S27. `CauldronHooks` caches (Crucible code under `net/minecraftforge/cauldron`) — see `[patches §4]`

- `CR/net/minecraftforge/cauldron/CauldronHooks.java:38-39` (`tileEntityCache`, `sushchestvoCache`, static HashMaps), `put` in `canTileEntityTick`
  `:271-277` (called for every TE tick) and in `canSushchestvoTick`; `:41` `recentWarnings` (Trove map) written `:205-209` only when
  `cauldron_logging_entityCollisionChecks` is true (default false, `CR/io/github/crucible/CrucibleConfigs.java:91`); `:36-37` `tickingDimension`,
  `tickingChunk` have no reader or writer outside the declaration (dead).
- What breaks: first-time `put` of a TE class from two threads races in a HashMap read on every TE tick (corruption, lost entry). Severity: **B medio | C medio**.
- Treatment: `ConcurrentHashMap` with `computeIfAbsent` (and key it by (world, class), which also fixes the "first world wins" bug of `[patches §3.4]`).

### S28. `EntityTNTPrimed.random` (static non-thread-safe Random)

- Declaration: `MC/net/minecraft/entity/item/EntityTNTPrimed.java:25` (`static final Random random = new ThermiteRandom()`), used in the constructor `:39`.
  `ThermiteRandom.next` keeps the seed in a plain `long` (`CR/thermos/thermite/ThermiteRandom.java:43, 120-128`), unlike `java.util.Random` (CAS on an AtomicLong).
- Who: TNT priming (explosions, dispensers) and every `ExplosionEvent.Start` (dummy TNT, `ExplosionEvent.java:64-69`).
- What breaks: two threads get the same "random" launch angle. Severity: **B baixo | C baixo**. Treatment: no action needed (cosmetic), or `ThreadLocalRandom`.

### S29. `BlockEndPortal.field_149948_a` (set by the dragon while building the exit portal)

- `MC/net/minecraft/block/BlockEndPortal.java:22`, read `:96` in `onBlockAdded` (removes end portal blocks placed outside dimension 0 unless the flag is set);
  written `MC/net/minecraft/entity/boss/EntityDragon.java:699, 778` (entity tick, dragon death).
- What breaks: while the dragon builds its exit portal, an end portal block placed by something else outside dimension 0 in another region is not
  removed (the flag is read as `true`); and if two dragons die at once in two regions, the first one to finish sets the flag `false` while the second is
  still placing portal blocks (only matters outside dimension 0, `:98`). Rare. Severity: **B baixo | C baixo**. Treatment: thread-local, or no action.

---

## 2. Fields looked at and judged harmless at tick time (one line each)

Grouped only when every field of the group has the same reason. "Init" = written only during mod loading / server start; "client" = only reached from client code.

Static non-final, vanilla:
- `World.MAX_ENTITY_RADIUS` (`MC/net/minecraft/world/World.java:124`): written by mods at init, only read at tick (`World.java:3941-3944`, `Chunk.java:1093`).
- `World.preloadedCrashClasses` (`World.java:186`): crash-report helper, init.
- `World.s_mapStorage`, `World.s_savehandler` (`World.java:626-637`): only at world construction (global); the shared MapStorage itself is `[mundo M24]`.
- `Chunk.isLit` (`MC/net/minecraft/world/chunk/Chunk.java:49`): only ever written `true` (`:808`), never read on the server.
- `NextTickListEntry.nextTickEntryID`: not harmless, see S22.
- `Entity.nextEntityID`: not harmless, see S07.
- `EntityMinecart.defaultMaxSpeedAirLateral/defaultMaxSpeedAirVertical/defaultDragAir/collisionHandler` (`MC/net/minecraft/entity/item/EntityMinecart.java:71-76`): config set by mods at init, read at tick.
- `EntityEnderman.carriableBlocks`, `carriable` (`EntityEnderman.java:38, 498-508`): filled at init (`setCarriable`), read at tick.
- `EntityList.stringToClassMapping`, `classToStringMapping`, `IDtoClassMapping`, `classToIDMapping`, `stringToIDMapping`, `entityEggs` (`EntityList.java:77-82`): registration at init, read at tick.
- `TileEntity.nameToClassMap`, `classToNameMap` (`MC/net/minecraft/tileentity/TileEntity.java:32-33`): registration at init, read at save/load.
- `TileEntityEnchantmentTable.field_145923_r` (`TileEntityEnchantmentTable.java:19`): `java.util.Random` (thread-safe).
- `Item.itemRand` (`MC/net/minecraft/item/Item.java:65`): `java.util.Random` (thread-safe; contention only).
- `NetHandlerPlayServer.field_147376_j` (`NetHandlerPlayServer.java:167`), `NetHandlerLoginServer.field_147329_d` (`:42`): network threads, `java.util.Random`.
- `NetHandlerHandshakeTCP.throttleCounter`, `throttleTracker` (`NetHandlerHandshakeTCP.java:29-30`): Netty handshake, not region work.
- `S21PacketChunkData.field_149286_i` (`MC/net/minecraft/network/play/server/S21PacketChunkData.java:28`): only used in `readPacketData` (`:98-124`), which the server never runs for S21.
- `BlockFalling.fallInstantly`: not harmless, see S15.
- `BlockSapling.treeType`: not harmless, see S13.
- `BlockFurnace.field_149934_M`: not harmless, see S02.
- `BlockRedstoneTorch.field_150112_b`: not harmless, see S09.
- `BlockFire.failures`: see S20.
- `BlockDispenser.eventFired` (`MC/net/minecraft/block/BlockDispenser.java:33`): only ever written `false` (`:150`; rg for `eventFired = true` over MC, CR and patches: no hit), so every reader sees `false`.
- `BlockTrapDoor.disableValidation` (`BlockTrapDoor.java:21`): set by mods at init, read `:240,282`.
- `BlockEndPortal.field_149948_a`: see S29.
- `BlockSand.field_149837_b/field_149839_N` (`BlockSand.java:17-19`): icons, client.
- `MathHelper.SIN_TABLE` (`MC/net/minecraft/util/MathHelper.java:9`): filled in the static initializer `:320`, read-only.
- `DamageSource.inFire ... fallingBlock` (14 fields, `MC/net/minecraft/util/DamageSource.java:12-25`): created at class init and never mutated afterwards (setters only used in the initializers).
- `StatList` (28 fields) and `AchievementList` (38 fields) and `StatBase.simpleStatType/timeStatType/distanceStatType/field_111202_k` (`StatBase.java:23-58`): registration at init; per-player counters live in the per-player stats file.
- `StatBase.numberFormat`, `decimalFormat` (`StatBase.java:22,32`): not thread-safe formatters, used only for stat display (client/commands).
- `StatCollector.localizedName/fallbackTranslator`, `StringTranslate.instance` (`StatCollector.java:5-6`, `StringTranslate.java:25`): read-only translation tables after init.
- `ColorizerGrass.grassBuffer`, `ColorizerFoliage.foliageBuffer` (`ColorizerGrass.java:9`, `ColorizerFoliage.java:9`): client colour tables.
- `ScreenShotHelper.pixelBuffer/pixelValues`, `BossStatus.*` (4), `CreativeTabs.creativeTabArray`: client.
- `CommandBase.theAdmin` (`CommandBase.java:19`): set at server start.
- `DedicatedServer.allowPlayerLogins` (`DedicatedServer.java:67`): start-up flag.
- `SaveHandler.initializedBukkit` (`SaveHandler.java:40`): start-up.
- `Bootstrap.field_151355_a` (`Bootstrap.java:47`): start-up.
- `WorldType.worldTypes` (`WorldType.java:26`): registration.
- `WorldChunkManager.allowedBiomes` (`WorldChunkManager.java:23`), `MapGenVillage.villageSpawnBiomes` (`MapGenVillage.java:16`), `MapGenScatteredFeature.biomelist` (`:17`): set at init, read in worldgen.
- `MapGenStructureIO.field_143040_a..field_143037_d` (`MapGenStructureIO.java:13-16`): registration at init (`:21`), read when loading structures.
- `StructureStrongholdPieces.structurePieceList/strongComponentType/totalWeight`, `ComponentScatteredFeaturePieces...junglePyramidsRandomScatteredStones`: not harmless, see S15.
- `NoiseGeneratorSimplex.field_151611_e` (`NoiseGeneratorSimplex.java:7`): constant gradient table, read-only.
- `IntCache.*`: see S15.
- `RConUtils.hexDigits` (`RConUtils.java:10`): constant.
- `MinecraftServer.mcServer`, `options`, `cauldronConfig`, `tileEntityConfig`, `sushchestvoConfig`, `configuration`, `commandsConfiguration`, `configFile`, `commandFile`, `useJline`, `useConsole`, `serverStarted` (`MinecraftServer.java:128,182,197-207,210`): set at start-up, read-only at tick.
- `MinecraftServer.currentTps` (`:205`): written in the run loop, read by commands.
- `MinecraftServer.currentTick`, `callingForgeTick`: see S17. `bannedTileEntityUpdates`: see S16.

Static non-final, Forge:
- `ForgeModContainer.clumpingThreshold, removeErroringEntities, removeErroringTileEntities, disableStitchedFileSaving, fullBoundingBoxLadders, zombieSummonBaseChance, blendRanges, zombieBabyChance, shouldSortRecipies, disableVersionCheck, defaultSpawnFuzz, defaultHasSpawnFuzz, config` (`MC/net/minecraftforge/common/ForgeModContainer.java:61-74`): written by `syncConfig` (`:114-216`) at load and on the client config-GUI event (`:238-248`); only read at tick (e.g. `World.java:2375,2454,2557`, `EntityZombie.java:102`). There is no `ForgeDummyContainer` in this Forge.
- `ForgeHooks.toolInit` (`ForgeHooks.java:100`): set once from the static initializer (`:218-222` -> `:162-168`).
- `ForgeHooks.seedList` (`ForgeHooks.java:88`, static final ArrayList): filled at init, read when grass drops seeds.
- `ForgeTimeTracker.tileEntityTracking/tileEntityTrackingDuration/tileEntityTrackingTime` (`MC/net/minecraftforge/server/ForgeTimeTracker.java:16-18`): only `/forge track` writes them (`ForgeCommand.java:90-91`) and nothing in `MC/net/minecraft` calls `trackStart/trackEnd` (rg): dead at tick.
- `FluidRegistry.maxID, fluids, fluidIDs, fluidNames, masterFluidReference, defaultFluidName, delegates, renderIdFluid` (`FluidRegistry.java:39-65`): registration and world load (`initFluidIDs` `:79-125`); read-only at tick. `fluidBlocks`: see S25.
- `FluidContainerRegistry.containerFluidMap, filledContainerMap, emptyContainers` (`FluidContainerRegistry.java:71-73`): registration; read at tick. `EMPTY_BUCKET`, `EMPTY_BOTTLE`, `NULL_EMPTYCONTAINER` (static final ItemStack, `:76-78`): every getter returns `.copy()` (`:229`, `:250`), never mutated by Forge.
- `BlockFluidBase.defaultDisplacements` (`BlockFluidBase.java:29`): filled at class init.
- `FishingHooks.fish, junk, treasure` (`FishingHooks.java:20-22`): registration, read on fishing.
- `ChestGenHooks.hasInit` (`ChestGenHooks.java:36`): init. `chestInfo`: see S25.
- `DungeonHooks.dungeonMobs` (`DungeonHooks.java:10`): registration, read in worldgen.
- `BiomeManager.*` (9 fields, `BiomeManager.java:21-40`): registration, read in worldgen. `BiomeDictionary.biomeList/typeInfoList`: see S25.
- `UsernameCache.map` (`MC/net/minecraftforge/common/UsernameCache.java:38`): written only on player login (`ForgeModContainer.java:257`, global phase); reads by mods during B never overlap a write. In C a login can overlap: low.
- `AchievementPage.achievementPages` (`AchievementPage.java:37`): registration.
- `ForgeNetworkHandler.channelPair` (`ForgeNetworkHandler.java:14`): Forge's own channel, used at login/handshake (network phase).
- `Configuration.PARENT` (`Configuration.java:60`): config loading.
- `EnumHelper.*` (7 fields, `EnumHelper.java:27-36`): reflection setup at init.
- `ForgeVersion.status, target` (`ForgeVersion.java:22-23`): version-check thread, not tick.
- `RecipeSorter.categories, entries, priorities, INSTANCE, isDirty, before, after, warned` (`MC/net/minecraftforge/oredict/RecipeSorter.java:102-139`): recipe sorting at load.
- `OreDictionary.hasInit` (`OreDictionary.java:40`): init. Other OreDictionary fields: see S14.
- `ForgeChunkManager.defaultMaxCount, defaultMaxChunks, overridesEnabled, ticketConstraints, chunkConstraints, callbacks, cfgFile, config, playerTicketLength, dormantChunkCacheSize` (`ForgeChunkManager.java:75-95`): config at load/`syncConfigDefaults` (`:953`); read at tick. Mutable maps: see S11.
- `DimensionManager.hasInit`, `providers`, `spawnSettings`, `dimensions`, `dimensionMap`, `classToProviders`, `bukkitDims` (`DimensionManager.java:49-60`): registration at start and on dimension load (covered by S12's treatment); read at tick.
- `FakePlayerFactory.MINECRAFT` (`FakePlayerFactory.java:17`): a GameProfile, never mutated.
- `ExplosionEvent.exploder_profile` (`ExplosionEvent.java:37`): GameProfile constant.
- `MinecraftForge.INTERNAL_HANDLER` (`MinecraftForge.java:33`): Forge's own listener (`ForgeInternalHandler`), the bus aspects are S04/S05.
- `RotationHelper.MAPPINGS` (`RotationHelper.java:82`): filled at class init.
- `ChunkIOExecutor.instance`: see S16.

Static non-final, FML:
- `Loader.instance, major, minor, rev, build, mccversion, mcpversion, minecraftDir, injectedContainers` (`MC/cpw/mods/fml/common/Loader.java:130-167`): start-up.
- `GameData.customItemStacks, customOwners, frozen` (`GameData.java:82-84`): registration; `frozen` set by `freezeData` (`:721-726`) at start; `revertToFrozen` only at server stop (`Loader.java:851`) and on the client. Registry lookups at tick only read; world ID injection happens at world load (`FMLContainer.java:210,277`).
- `GameRegistry.worldGenerators, worldGeneratorIndex, fuelHandlers, worldGenMap` (`GameRegistry.java:66-73`): registration; read at tick (`getFuelValue` from furnaces). `sortedGeneratorList`, `configWorldGenCache`: see S15.
- `EntityRegistry.entityTypeMap, entityClassMap` (`EntityRegistry.java:125-126`): registration (Cauldron), read-only at tick.
- `ModContainerFactory.modTypes, modClass, INSTANCE` (`ModContainerFactory.java:32-34`): mod discovery.
- `ObjectHolderRef.*` (4), `ItemStackHolderRef.*` (4): reflection setup at init.
- `StartupQuery.pending, aborted` (`StartupQuery.java:60-61`): start-up, already volatile.
- `FMLSanityChecker.fmlLocation`, `SideTransformer.SIDE`, `ModAccessTransformer.embedded`: class loading at start. `SideTransformer.allowInvalidSide`: see S16.
- `FMLProxyPacket.badPackets` (`FMLProxyPacket.java:31`, `ConcurrentHashMultiset`), `packetCountWarning` (`:32`, constant).
- `NetworkModHolder.assignedIds` (`NetworkModHolder.java:86`): mod loading.
- `FMLEventChannel.factory` (`FMLEventChannel.java:72`): set at class init. `FMLNetworkHandler.channelPair`: see S03.
- `Event.listeners` (`Event.java:33`), `EventBus.maxID`, `ListenerList.allLists/maxSize`, `ASMEventHandler.IDs`: see S05.
- `FMLInterModComms.modMessages` (`FMLInterModComms.java:37`): IMC at load.
- `FMLLog.coreLog` (`FMLLog.java:21`): log4j logger, thread-safe.
- `cpw/mods/fml/relauncher/*` (`FMLInjectionData`, `ModListHelper`, `CoreModManager`, `FMLRelaunchLog`, `FMLLaunchHandler`), `FMLTweaker.jarLocation`, `ModDiscoverer.zipJar`, `ITypeDiscoverer.classFile`, `FMLForgePlugin.RUNTIME_DEOBF/forgeLocation`: launch time only.

Static final holding mutable objects (checked, harmless at tick):
- `EnchantmentHelper.enchantmentRand` (`:24`), `NetHandlerLoginServer.field_147329_d`: `java.util.Random`.
- `DataWatcher.classToId`, `dataTypes` (`MC/net/minecraft/entity/DataWatcher.java:34,37`): filled in the static initializer (`:376-381`), read-only after.
- `EntityVillager.villagersSellingList`, `blacksmithSellingList` (`EntityVillager.java:69-70`): filled at class init (`:666...`), read on trade generation.
- `EntityFishHook.field_146039_d/field_146041_e/field_146036_f` (`EntityFishHook.java:39-41`): fixed lists.
- `PotionHelper.potionRequirements`, `potionAmplifiers` (`PotionHelper.java:26-27`): filled at class init (`:550...`). `field_77925_n`: client-only, see S23 note.
- `ItemRecord.field_150928_b`, `ItemFishFood...field_150983_e`, `ItemSpade/ItemPickaxe/ItemAxe` effective-block sets, `ItemPotion.field_77835_b` (`ItemPotion.java:33`, filled when empty at `:377-414` inside the `@SideOnly(Side.CLIENT)` `getSubItems` `:372`): registration-time or client.
- `BlockDispenser.dispenseBehaviorRegistry` (`BlockDispenser.java:31`): registration; behaviours are stateless except the two cosmetic flags `Bootstrap.java:509,589` (anonymous behaviours for flint-and-steel and dye; only choose the dispense sound, `:570-584`, `:639-651`): harmless.
- `TileEntity.INFINITE_EXTENT_AABB` (`TileEntity.java:383`): never mutated by vanilla/Forge (a mod calling a mutating method on it would be a mod bug).
- `TileEntityFurnace.slotsTop/slotsBottom/slotsSides`, `TileEntityBrewingStand` slot arrays, `TileEntityBeacon.effectsList`: constant arrays.
- `BiomeGenBase.explorationBiomesList`, `temperatureNoise`, `plantNoise`, `genTallFlowers` (`BiomeGenBase.java:65,106-108`) and the static tree generators of `BiomeGenTaiga/BiomeGenForest/BiomeGenSavanna`: worldgen only, covered by S15's single worldgen thread/lock.
- `WorldServer.bonusChestContent`, structure chest-content arrays, `WorldGenDungeons.field_111189_a`, `StructureNetherBridgePieces` weights: constant tables (the stronghold weights `pieceWeightArray` carry instance counters, reset per structure: S15).
- `NBTSizeTracker.field_152451_a` (`MC/net/minecraft/nbt/NBTSizeTracker.java:5-9`): the anonymous subclass overrides the only mutator with an empty body.
- `RegionFileCache.REGIONS_BY_FILE` (`RegionFileCache.java:18`): synchronized methods (`[tick-threads §2]`).
- `ThreadedFileIOBase.threadedIOInstance`: see S26.
- `NetworkSystem.eventLoops`, `NetworkManager.*` attribute keys, `EnumConnectionState` maps, `NetHandlerPlayServer.invalidItems`: network or constants.
- `CraftingManager.instance` (recipe list, read at tick; mods adding recipes at runtime do so from commands/global phase), `FurnaceRecipes.smeltingBase`: read-only at tick. `lastCraftView`: see S24.
- `VillagerRegistry.INSTANCE`, `EntityRegistry.INSTANCE`, `LanguageRegistry.INSTANCE`, `GameData.mainData/blockRegistry/itemRegistry`, `ModAPIManager.INSTANCE`, `ClassPatchManager.INSTANCE`, `FMLDeobfuscatingRemapper.INSTANCE`, `FMLCommonHandler.INSTANCE`, `FMLServerHandler.INSTANCE`: registries/singletons with no writes at tick (FMLCommonHandler fields `MC/cpw/mods/fml/common/FMLCommonHandler.java:85-95` are set at start).
- `ASMEventHandler.LOADER` / `cache`: see S05. `ProgressManager.bars` (`CopyOnWriteArrayList`), `ForgeCommand.timeFormatter` (command, global).
- `ChatStyle.rootStyle`, `EnumChatFormatting` maps, `HoverEvent/ClickEvent` maps, `Session` map, `EnumDifficulty.difficultyEnums`, `Score.field_96658_a`: constants.

Instance fields of block/item singletons (checked, harmless at tick):
- `Block.opaque, lightOpacity, canBlockGrass, lightValue, useNeighborBrightness, blockHardness, blockResistance, needsRandomTick, isBlockContainer, stepSound, blockParticleGravity, slipperiness, isForgeBlock, timing, harvestTool, harvestLevel, isTileProvider` (`Block.java:103-129, 1458, 2191-2192`): set at registration (Forge tool levels by `setHarvestLevel`, init); `timing` set once lazily (benign). Bounds: S01.
- `BlockFire.field_149849_a/field_149848_b/blockInfo` (`BlockFire.java:34-36, 499`): written only by `setFireInfo` at init (`:505-506`).
- `BlockOre.rand`, `BlockRedstoneOre.rand`, `BlockMobSpawner.rand`, `BlockBrewingStand.field_149961_a`, `BlockDispenser.field_149942_b`, `BlockFurnace.field_149933_a`: `java.util.Random` (thread-safe).
- `BlockRailBase.renderType` (`BlockRailBase.java:294-298`): set by mods at init; `BlockRailBase.Rail` (`:304-318`) is created per call.
- `BlockPistonBase`: only `final isSticky` (`BlockPistonBase.java:31`), no mutable state. `BlockStaticLiquid`, `BlockLiquid`, `BlockHopper`, `BlockChest`: only locals.
- `BlockRedstoneTorch.field_150113_a` (`:23`), `BlockRedstoneDiode.limit` (`:25, 32`), `BlockPumpkin`, `BlockFlower`, `BlockBreakable`, `BlockLeavesBase.field_150121_P`, `BlockLeaves.field_150127_b`, `BlockSign`, `BlockPressurePlate`, `BlockBasePressurePlate`, `BlockStairs.field_150149_b/field_150151_M`, `BlockChest.field_149956_a`, `Material.*`: set in constructors/init.
- `BlockAnvil.anvilRenderSide` (`BlockAnvil.java:24`): render.
- `Item.maxStackSize, maxDamage, bFull3D, hasSubtypes, containerItem, potionEffect, canRepair` and the per-class fields of `ItemSword, ItemTool, ItemFood, ItemSeeds, ItemSeedFood, ItemReed, ItemDoor, ItemBucket, ItemHoe, ItemColored, ItemMinecart, ItemBlockWithMetadata` (`MC/net/minecraft/item/*`): set in constructors/init. `ItemPotion.effectCache`: S23.
- TileEntity helpers: `TileEntityHopper` static methods (`TileEntityHopper.java:441-790`) hold no static state; `TileEntityPiston.pushedObjects` is an instance list (`TileEntityPiston.java:24`).

---

## 3. Closing lists

### 3.1 The 10 items most likely to bite in the synchronized parallel phase (B)

1. **S03 FML network channels**: every tech mod sends sync packets from TE ticks through one shared embedded channel per mod; misrouted packets and `ClassCastException`. Fix: lock per channel.
2. **S01 Block bounds on the singleton**: every moving entity near stairs/fences/slabs every tick; silent wrong collisions. Fix: lock per block at the two `World` call sites.
3. **S04 Mod listeners on `EVENT_BUS`** (`LivingUpdateEvent` per living entity per tick, `EntityJoinWorldEvent` per spawn): unknown mod code in parallel. Fix: global listener lock unless allowlisted.
4. **S07 `Entity.nextEntityID`**: duplicate ids; the Thermos tracker "correction" makes the older entity invisible. Fix: atomic counter.
5. **S22 / `[mundo M10]` `nextTickEntryID`**: duplicate tick ids silently drop scheduled block updates. Fix: atomic counter.
6. **S02 `BlockFurnace` keep-inventory flag**: item loss or duplication from ordinary furnaces (and mods that copied it). Fix: thread-local.
7. **S06 `FakePlayerFactory`**: shared FakePlayer objects mutated by two regions' machines (67 jars use FakePlayer); HashMap races. Fix: per-region FakePlayers.
8. **S08 `BlockRedstoneWire` flag and set**: redstone in one base switches off power reporting for all wires elsewhere; CME. Fix: thread-local.
9. **S15 World generation on a region thread**: rare per tick but a crash after 10 s (`BiomeDecorator`), corrupted biomes (`IntCache`), instant-falling sand. Fix: one worldgen lock.
10. **S10 `EnchantmentHelper` modifiers** (every mob hit, every armour reduction; thorns may hit an entity of another region). Fix: thread-local.

Next in line: S05 (runtime listener registration), S11 (ForgeChunkManager lost forced chunks), S13 (static placement context), S14 (OreDictionary creation path), S16 (chunk-load statics), S27 (CauldronHooks caches).

### 3.2 Check of `docs/pesquisa/arte-previa.md` §4 items in this area

| # | Item of §4 | Verdict | Evidence |
|---|---|---|---|
| 1 | World entity/TE lists | outside this area | `[mundo M02, M04, M06]` |
| 2 | `ChunkProviderServer` maps | outside this area | chunk area |
| 3 | `Chunk` maps and storage | outside this area | chunk area |
| 4 | tick lists and static `nextTickEntryID` | **confirmed** (static part) | `NextTickListEntry.java:7,19`; tick sets in `[mundo M09, M10]` |
| 5 | `World.lightUpdateBlockList` | outside this area (per-World instance field, not static) | `[mundo M15]` |
| 6 | `IntCache` static lists, "needs ThreadLocal" | **partly wrong**: fields are static (`IntCache.java:8-12`) but all methods are `synchronized` in this code (`:15,57,75`). It is still unsafe because `resetIntCache` recycles arrays other threads still use (`:57-73`); it is reached from biome generation and biome-cache misses (`WorldChunkManager.java:73...`, `World.java:268-270`). ThreadLocal is still the right fix | S15 |
| 7 | `Entity.nextEntityID` static int | **confirmed** (`Entity.java:93,201`, plus `resetEntityId` `:2705`) | S07 |
| 7b | "static Randoms, e.g. `MathHelper`" | **wrong for MathHelper**: it has no Random field (`MathHelper.java:9-11`). Static Randoms that exist: `Item.itemRand` `:65`, `EnchantmentHelper.enchantmentRand` `:24`, `TileEntityEnchantmentTable.field_145923_r` `:19`, `NetHandlerPlayServer.field_147376_j` `:167`, `NetHandlerLoginServer.field_147329_d` `:42` (all `java.util.Random`, thread-safe) and `EntityTNTPrimed.random` `:25` (`ThermiteRandom`, **not** thread-safe) | S28, §2 |
| 8 | `PlayerManager`, `EntityTracker`, `playerEntityList` | outside this area | network/world areas, `[tick-threads §4]` |
| 9 | `MinecraftServer` globals | **confirmed, partly in area**: `worldTickTimes` Hashtable (`MinecraftServer.java:159,1016`), `tickCounter` (`:145`, written `:832`), static `mcServer` (`:128`), static `currentTick`/`callingForgeTick` (`:186,208`); all written only in global phases, so read-only during B | S17, S12 |
| 10 | `DimensionManager` static maps `worlds`, `dimensions`, `unloadQueue`, `weakWorldMap` | **confirmed** (`DimensionManager.java:49-60`); `weakWorldMap`/`leakedWorlds` only used with `worldLeakDebug` (default false); a region thread can trigger `initDimension` through `worldServerForDimension` (`MinecraftServer.java:1069-1083`) | S12 |
| 11 | `EVENT_BUS`/FML bus, "listeners in array, dynamic registration thread-safe-ish, listeners of mods assume one thread" | **confirmed with a correction**: listeners are cached in arrays (`ListenerList.java:181-214`), but registration is **not** thread-safe: plain `ArrayList` and non-volatile `rebuild`/`listeners`, only `extendMasterList` is synchronized (`:28, 119-121, 216-229`). No `shutdown` flag exists in this version. Tick events: `ServerTickEvent`/`WorldTickEvent` fire in global phases (`MinecraftServer.java:827-831, 892-896`, `[tick-threads §1.3]`); `PlayerTickEvent` fires from `EntityPlayer.onUpdate` (`EntityPlayer.java:244,377`), which on the server runs from `EntityPlayerMP.onUpdateEntity` in the network phase (`EntityPlayerMP.java:410-414`), not from the world entity loop (`EntityPlayerMP.onUpdate` `:308` does not call `super.onUpdate`). So `PlayerTickEvent` (49 jars) stays serial in B | S04, S05 |
| 12 | `FakePlayerFactory` static cache "per world/GameProfile" | **confirmed, but not per world**: static HashMap (`FakePlayerFactory.java:19`) looked up by **name only**, ignoring the world (`:41-47`), so one FakePlayer is shared across dimensions | S06 |
| 13 | `ForgeChunkManager` static maps per world | **confirmed** (`ForgeChunkManager.java:79-99`); plus Crucible's mirror into `World.activity` from the event constructors (`:355, :367`) | S11 |
| 14 | `ChunkIOExecutor` one thread | outside this area for the pool size (`[tick-threads §2]` and its review); in this area: its static executor keeps tasks in an unsynchronized `HashMap` (`AsynchronousExecutor.java:220, 242-249, 338-346`) | S16 |
| — | "Singletons de mods" and "fronteiras de região" | outside this area | mods / world areas |

### 3.3 What I could not find or verify

- **Not present in 1.7.10 / this code** (searched, not found): `AxisAlignedBB`/`Vec3` pools (objects are created with `new`, `AxisAlignedBB.java:13-16`, `Vec3.java:10-13`);
  a mutable static or instance field in `BlockPistonBase` (only `final isSticky`, `:31`), `BlockStaticLiquid`, `BlockRailBase` (except the init-time `renderType`);
  static helpers with state in `TileEntityHopper`/`TileEntityPiston`; `ForgeHooks.craftingPlayer` / a per-thread "current player" in ForgeHooks (rg `-i craftingPlayer`: no hit;
  the only "current player" is Cauldron's `ItemStack.currentPlayer`, S13); an EventBus `shutdown` flag; a `ForgeDummyContainer` class (the toggles live in `ForgeModContainer`);
  any caller of `ForgeTimeTracker.trackStart/trackEnd` in `MC/net/minecraft`; static scratch state in pathfinding (`PathFinder` is created per call, `World.java:4107,4126`).
- **Not verified (needs a bytecode scan of the 227 jars)**: how many mods copy the `BlockFurnace` keep-inventory pattern (S02) or the redstone-wire flag pattern (S08);
  how many mods register/unregister event handlers at runtime (S05); how many listeners `LivingUpdateEvent`, `EntityJoinWorldEvent` and `LivingAttackEvent` have in the
  pack (S04); how many mods use `SimpleNetworkWrapper.sendTo*`/`getPacketFrom` from TE ticks (S03; very likely most tech mods, but not counted); how many mods keep their
  own static FakePlayer (S06).
- **Not verified in execution**: every failure mode in this report (code reading only, no server started). In particular the JMM-level claim that a thread can see
  `ListenerListInst.rebuild == false` with a stale `listeners` array (S05) is theoretical; the HashMap/ArrayList races are standard but were not reproduced.
- **Not checked here (other areas)**: vanilla packet sending from region threads (`NetworkManager`/`NetHandlerPlayServer.sendPacket`), the chunk map consequences of two
  concurrent synchronous loads of the same chunk (S16), Bukkit/CraftBukkit statics (`CraftEventFactory.blockDamage/entityDamage`, `ActivationRange` boxes: `[patches §4]`),
  and the cost of the proposed locks (S01, S03) under real load.
