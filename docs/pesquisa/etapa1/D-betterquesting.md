# 1.D BetterQuesting on the server tick: design report

Item: 1.D of Stage 1 ("Degrau 0"). Read-only study, nothing was built.
Pack: BetterQuesting-3.8.79-GTNH.jar (one jar holding the mods `betterquesting`, `bq_standard`,
`questbook`, `cb4bq`; see its `mcmod.info`) plus the in-house Akashic-BQRelief-2.0.0-serveronly.jar.

Short answer: the 4.62% is two different things. **2.95% is bq_standard's inventory check**
(`TaskRetrieval.detect` run on every incomplete retrieval task each time a player's inventory
changes). **1.66% is BetterQuesting core** (the per-player `onLivingUpdate` loops). Both cost a lot
because the quest database marks **every quest** (8,939 of 8,939) `lockedProgress=1`, so for
every player the "active quest" set is every quest they have not completed yet. BQRelief already
removed the cost of *finding* those quests. It did not remove the cost of *checking* them.
Proposal: one exact mixin (no behaviour change) that skips `TaskRetrieval` checks that provably
cannot change anything. Expected gain: about 0.9 to 1.4% of the server thread (about 0.8 to 1.2
ms/tick). **Recommendation: implement.** The core 1.66% is left alone in Degrau 0 (reasons in 5.4).

Paths used below:
- `PROF` = `.../scratchpad/anexos/spark/wIBxG0D4y9.json`
- `DEC` = `.../scratchpad/etapa1/decomp/D-betterquesting/` (`bq/` = CFR output of the BQ jar,
  `relief/` = CFR output of BQRelief, `jar/` = the unzipped BQ jar used for javap)
- `SRG` = `.../scratchpad/medidor-work/srg.jar` (vanilla server renamed to SRG by `medidor/build.sh`,
  used for javap of vanilla members; Forge-added members are checked through the call sites in
  BQ bytecode plus the Crucible source)
- `...` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2`

---

## 1. What the profile says

All percentages are the share of "Server thread" time (598.9 s, 7,113 ticks, 84.2 ms/tick), inclusive.

Commands (run from `/home/user/Hoshiestar`):

```
python3 -I tools/spark_report.py PROF eventos --top 30
python3 -I tools/spark_report.py PROF busca betterquesting
python3 -I tools/spark_report.py PROF busca bq_standard
python3 -I tools/spark_report.py PROF busca bqrelief
python3 -I tools/spark_report.py PROF busca coalesce
python3 -I tools/spark_report.py PROF busca TaskRetrieval
python3 -I tools/spark_report.py PROF busca QuestCache.updateCache
python3 -I tools/spark_report.py PROF busca QuestInstance.isUnlocked
python3 -I tools/spark_report.py PROF busca cachedUnlock
python3 -I tools/spark_report.py PROF arvore --raiz EventHandler_onServerTick_ServerTickEvent --prof 3 --min 0.01
python3 -I tools/spark_report.py PROF arvore --raiz bq_standard.handlers.EventHandler.onServerTick --prof 14 --min 0.02
python3 -I tools/spark_report.py PROF arvore --raiz betterquesting.handlers.EventHandler.onServerTick --prof 14 --min 0.02
python3 -I tools/spark_report.py PROF resumo
```

`eventos` lists `EventHandler_onServerTick_ServerTickEvent` as 4.62% (27,676 ms). That is two
handlers with the same short name:

| ASM handler | Handler | % | ms/tick |
|---|---|---:|---:|
| `ASMEventHandler_366_EventHandler_onServerTick_ServerTickEvent` | `bq_standard.handlers.EventHandler.onServerTick` (phase START) | 2.95 | 2.49 |
| `ASMEventHandler_356_EventHandler_onServerTick_ServerTickEvent` | `betterquesting.handlers.EventHandler.onServerTick` (phase END) | 1.66 | 1.40 |

### 1a. bq_standard sub-tree (2.95%)

```
2.95  bq_standard.handlers.EventHandler.onServerTick            [self 0.5]
1.85    bq_standard.tasks.TaskRetrieval.onInventoryChange
1.85      bq_standard.tasks.TaskRetrieval.detect
1.18        IntStream...forEachOrdered -> TaskRetrieval.lambda$detect$2
1.08          TaskRetrieval$Detector.run                          [self 0.3]
0.69            betterquesting.api.utils.ItemComparison.StackMatch
0.65              net.minecraft.item.ItemStack.func_77973_b (getItem)  [self 0.6]
0.05            ItemComparison.OreDictionaryMatch
0.03          InventoryPlayer.func_70301_a (getStackInSlot)
0.45        TaskRetrieval$Detector.<init>                          (getBulkProgress stream 0.30)
0.19        bq_standard.tasks.base.TaskBase.isComplete             (TreeSet<UUID>.contains)
0.36    betterquesting.api2.storage.SimpleDatabase.getEntries       [self 0.3] (synchronized)
0.07    Collections$UnmodifiableCollection.iterator, ~0.02 each for hasNext/next/entry
0.02    redirect$bkf000$akashic$inventory -> ParticipantLoopCache.filtered (BQRelief)
0.01    bq_standard.tasks.TaskFluid.onInventoryChange
```

`busca TaskRetrieval`: `TaskRetrieval.onInventoryChange` 1.85% (11,096 ms) at top level, of which
11,088 ms is under this handler. So almost all retrieval-task detection on the server happens here.

### 1b. BetterQuesting core sub-tree (1.66%)

```
1.66  betterquesting.handlers.EventHandler.onServerTick
1.66    EventBus.post(BQLivingUpdateEvent) -> ASMEventHandler.invoke
1.59      betterquesting.handlers.EventHandler.onLivingUpdate
0.84        betterquesting.api2.cache.QuestCache.updateCache
0.33          QuestCache.isQuestShown (0.26 in a Stream.allMatch)
0.23          QuestInstance.isUnlocked (0.20 BQRelief handler$bjp000$akashic$cachedUnlock)
0.09          QuestInstance.getProperty -> PropertyContainer.getProperty
0.05          QuestInstance.isComplete
0.03          NetCacheSync.sendSync
0.63        QuestInstance.isUnlocked
0.53          handler$bjp000$akashic$cachedUnlock                  [self 0.5]
0.08          Stream.count (cache misses that run the original)
0.06        QuestInstance.isComplete
0.02        QuestInstance.update
0.07      bq_standard.handlers.EventHandler.onEntityLiving (via BQRelief akashic$tickable)
```

Other: `busca QuestInstance.isUnlocked` 1.00% in total, `busca cachedUnlock` 0.87% (all callers).
BQRelief's own helpers cost 0.10% (`busca bqrelief`). `busca coalesce` = 0, so BQRelief's
optional inventory-coalescing mixin did **not** run in production (see section 4).

### 1c. Size of the data (from the quest file in the test copy, counts only)

The test world's `world-test/betterquesting/QuestDatabase.json` is empty. The pack's quest book is
in `config/betterquesting/saved_quests/AkashaQuests.json` (a second file, `Forbidden-Campanha-1.0.0-rc1.json`,
holds 1,011 quests). I counted with a Python script and printed only counts:

| AkashaQuests.json | count |
|---|---:|
| quests | 8,939 |
| quests with `lockedProgress:1` | **8,939 (all)** |
| quests with `globalShare:1` | 8,939 |
| retrieval tasks (`bq_standard:retrieval`) | 7,691 (in 7,370 quests) |
| retrieval tasks with `consume` / `autoConsume` | 0 / 0 |
| required items in retrieval tasks | 11,653 (1.52 per task, max 55) |
| distinct item ids among them | 5,116 (median 1 task per item id, top id 161 tasks, top 30 ids 1,576 tasks, top 60 ids 2,098 tasks) |
| retrieval tasks with an ore-dict item | 279 |
| retrieval tasks with an empty item list or a count of 0 or less | 0 |
| other task types | checkbox 874, hunt 375, crafting 291, location 103, fluid 21, xp 3 |

Which file production actually loaded is **not verified** (section 9). The per-call costs in 1a/1b
fit an active set of thousands of quests per player, which is what this file produces.

---

## 2. The code

### 2a. bq_standard: inventory check (the 2.95%)

**Who triggers it.** `bq_standard.handlers.PlayerContainerListener` (an `ICrafting` attached to every
player's `inventoryContainer`). `func_71111_a(Container,int,ItemStack)` (sendSlotContents) calls
`updateTasks()` for slots 9 to 44 (main inventory and hotbar), and `func_71110_a` (sendContainerAndContents)
calls it always. `updateTasks()` calls `EventHandler.schedulePlayerInventoryCheck(player)`, which
adds the player to `private static final HashSet<EntityPlayer> playerInventoryUpdates` (`DEC/bq/bq_standard/handlers/PlayerContainerListener.java`, `EventHandler.java` lines 76 and 267-272).
Vanilla calls sendSlotContents from `Container.detectAndSendChanges` whenever a slot's stack is not
equal to the last one sent. That includes NBT changes: tool damage, a charge value, a timer.

**The handler** `bq_standard.handlers.EventHandler.onServerTick`
`(Lcpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent;)V` (javap `-s` on `DEC/jar`), decompiled:

```java
if (event.phase != TickEvent.Phase.START) return;
synchronized (playerInventoryUpdates) {
  for (EntityPlayer player : playerInventoryUpdates) {
    if (player == null || player.field_71071_by == null) continue;
    ParticipantInfo pInfo = new ParticipantInfo(player);
    for (Map.Entry<UUID, IQuest> entry : QuestingAPI.getAPI(ApiReference.QUEST_DB)
            .filterKeys(pInfo.getSharedQuests()).entrySet()) {          // BQRelief redirects both calls
      for (DBEntry task : entry.getValue().getTasks().getEntries()) {
        if (!(task.getValue() instanceof ITaskInventory)) continue;
        ((ITaskInventory) task.getValue()).onInventoryChange(entry, pInfo);   // <- hot call
      }
    }
  }
  playerInventoryUpdates.clear();
}
```

javap `-c` shows the hot call as bytecode offset 196:
`invokeinterface bq_standard/tasks/ITaskInventory.onInventoryChange:(Ljava/util/Map$Entry;Lbetterquesting/api2/utils/ParticipantInfo;)V`.
It is the only invocation of that method in `onServerTick`.

`ParticipantInfo.getSharedQuests()` = the union of `QuestCache.getActiveQuests()` of all online
party members (`DEC/bq/betterquesting/api2/utils/ParticipantInfo.java`).
`QuestCache.updateCache` adds a quest to the active set when
`isUnlocked(uuid) || getProperty(LOCKED_PROGRESS)` and `canSubmit(player)` (not completed, or
repeatable and unclaimed) (`QuestCache.java` lines 93-145). With `lockedProgress=1` on every quest,
a player's active set is **every quest they have not completed**: about 8.9k for a new player.
About 7.4k of those contain a retrieval task.

**The task** `bq_standard.tasks.TaskRetrieval` (extends `TaskProgressableBase<int[]>`, implements
`ITaskInventory`). Descriptors from javap `-s`:
- `onInventoryChange(Ljava/util/Map$Entry;Lbetterquesting/api2/utils/ParticipantInfo;)V`: `if (!consume || autoConsume) detect(pInfo, quest);`
- `detect(Lbetterquesting/api2/utils/ParticipantInfo;Ljava/util/Map$Entry;)V`:

```java
if (this.isComplete(pInfo.UUID)) return;
Detector detector = new Detector(this, pInfo.ALL_UUIDS);   // getBulkProgress + reset of partial counts
invoList = consume ? [PLAYER.inventory] : inventories of pInfo.ACTIVE_PLAYERS;
for (InventoryPlayer invo : invoList)
  IntStream.range(0, invo.func_70302_i_()).forEachOrdered(i ->
      detector.run(invo.func_70301_a(i), remaining -> invo.func_70298_a(i, remaining), pInfo.UUID));
if (detector.updated) this.setBulkProgress(detector.progress);
this.checkAndComplete(pInfo, quest, detector.updated, detector.progress);
```

- `Detector.<init>`: `progress = task.getBulkProgress(uuids)` (one `Tuple2` per uuid, with the
  **live** stored `int[]` when it exists and has the right length, else a new zero array: see
  `getUsersProgress`). If `!consume`: when `groupDetect`, it fills every array with 0. Otherwise it
  sets every `n` with `0 < n < required` to 0 and sets `updated = true`.
- `Detector.run(stack, ...)`: returns at once when `stack == null || stack.field_77994_a <= 0`.
  For each required item it tests `ItemComparison.StackMatch(base, stack, ...)` or
  `ItemComparison.OreDictionaryMatch(rStack.getOreIngredient(), ...)`. Only on a match does it add
  counts and set `updated`. It calls the inventory consumer only when `task.consume`.
- `checkAndComplete`: for each uuid's array, "complete" = all required counts reached (or any one
  when `requireOnlyOneItem`). If complete, `setComplete` for every uuid in the list. If anything was
  updated, `pInfo.markDirtyParty(questId)`.
- `ItemComparison.StackMatch` returns false when `stack1.func_77973_b() != stack2.func_77973_b()`,
  unless `stack1 == stack2`. So "same `Item` object" is a necessary condition for a match.
- `OreDictionaryMatch(ore, ...)` = `ore.apply(stack) && ...`. `OreIngredient.apply` loops over its
  private `ores` list (`List<ItemStack>`, filled from `OreDictionary.getOres(name)`), so an empty
  list never matches. `BigItemStack.oreIng` defaults to `NO_ORE = new OreIngredient("")`.
  (`BigItemStack.copy()` copies `oreDict` but not `oreIng`. That is harmless here because the
  matching code uses `oreIng`.)

**Cost per check** = (incomplete retrieval tasks in the party's active set, about 7.4k) x
(stack slots: `func_70302_i_()` = 40 per online party member) x (1.5 required items), with a
pointer chase (`ItemStack.getItem()` returns `delegate.get()`, per the Crucible source
`ItemStack.java` line 135) on every comparison, plus one stream and some allocations per task in
`Detector.<init>`. **Cost per tick** = that x (number of players whose slots 9 to 44 changed in
the previous tick). Party size enters twice: every member's change triggers a check, and every
check scans every member's inventory.

### 2b. BetterQuesting core (the 1.66%)

`betterquesting.handlers.EventHandler.onServerTick` (phase END) posts one
`BQLivingUpdateEvent(player)` per online player. `onLivingUpdate(Lbetterquesting/api/events/BQLivingUpdateEvent;)V`
(`DEC/bq/betterquesting/handlers/EventHandler.java` line 229) returns unless `ticksExisted % 20 == 0`. Then:
- every 60 ticks per player: loops over `filterKeys(qc.getActiveQuests())` (about 8.9k quests) and
  calls `quest.isUnlocked(playerId)` on each. Almost all are locked, so it continues. This is the
  0.63% (cache hits of BQRelief's unlock memo, about 0.2 µs each, at about 8 x 8.9k / 3 s calls).
- every 200 ticks per player, or when something completed, reset or was claimed:
  `QuestCache.updateCache(player)` walks **the whole quest database** (`isUnlocked`, `LOCKED_PROGRESS`,
  `getCompletionInfo`, `canSubmit`, `isQuestShown`, `canClaimBasically`) and then sends
  `NetCacheSync` to the client. This is the 0.84%.

---

## 3. Root cause

The pack's quest book sets `lockedProgress=1` on all 8,939 quests. In BetterQuesting that puts
every uncompleted quest into every player's "active" set. So each inventory change makes bq_standard
re-count the player's (and party's) inventory against about 7.4k retrieval tasks: about 7.4k x 40
slots x 1.5 items of `StackMatch`. Almost all of these tasks require items the player does not
carry, so the work changes nothing. The core loops are linear in the same giant active set
(every 3 s per player) and in the whole database (every 10 s per player).

---

## 4. What the in-house mods already do

Akashic-BQRelief-2.0.0 (decompiled to `DEC/relief/`; late mixins, `mixins.akashic_bqrelief.late.json`;
options are `-D` system properties read in `BQReliefSettings`):

| Mixin | What it does | Active in production? |
|---|---|---|
| `QuestInstanceMixin` | memoizes `isUnlocked(UUID)`, keyed by a global "unlock epoch" plus a per-user epoch that are bumped on completion, reset, claim and quest edits | yes (`handler$bjp000$akashic$cachedUnlock` in the profile) |
| `PropertyContainerMixin` | memoizes `PropertyContainer.getProperty` for immutable values | yes (`handler$bkc000$akashic$cachedGet`, 0.02%) |
| `QuestCacheMixin` + `CoreLoopMixin` | caches the `ImmutableSet` copies of active/visible/autoclaim sets and the `filterKeys` result in `onLivingUpdate` | yes (`redirect$bke000$akashic$filter`) |
| `StandardLoopMixin` + `ParticipantLoopCache` | memoizes `getSharedQuests()` and gives bq_standard handlers a quest map filtered by task type (for `onServerTick`: only quests holding an `ITaskInventory`) | yes (`redirect$bkf000$akashic$inventory`, 0.02%) |
| `BQServerTickMixin` | bumps the structure epoch every `refreshTicks` (default 1200) and in edit mode, logs stats every 300 s | yes |
| `StandardInventoryTickMixin` | cancels bq_standard `onServerTick` START except every N ticks (coalescing) | **no**: only registered when `-Dakashic.bqrelief.inventoryInterval` > 1 (default 1). The production `vmArgs` in the spark metadata contain no `akashic.bqrelief` key (checked by key name only), and `busca coalesce` = 0. |

Why the cost remains: BQRelief made *finding* the quests cheap (filterKeys and getSharedQuests now
cost about 0.1%). It did not touch the per-task work: `TaskRetrieval.detect` on about 7.4k tasks
per inventory change (1.85%), plus walking those tasks (`getEntries`, iterators, about 1.0%). In the
core it turned each `isUnlocked` into a memo hit, but the loops still visit about 8.9k quests per
player.

Other jars touching these classes (byte scan of `mods/`, script `.../scratchpad/etapa1/D-scan.py`):
only BQRelief mixes into `bq_standard.handlers.EventHandler`. `BukkitBridge-1.0.1`'s
`RewardCommandMixin` references `EventHandler.scheduleServerTask` (another method). Forestry's
`ContainerWorktable` looks up `onItemCrafted` by reflection. None touch `onServerTick`'s
`onInventoryChange` call, `TaskRetrieval` or `OreIngredient`.

---

## 5. Proposed fix

### 5.1 Idea: an exact "this check cannot change anything" filter

Before each `ITaskInventory.onInventoryChange(entry, pInfo)` call in bq_standard's `onServerTick`,
decide from cheap facts whether the original call would be a pure no-op. If it would, skip it. If in
doubt, run the original. The decision only ever leads to "do exactly what the original does" or
"skip a call that provably does nothing".

`skip(task, pInfo)` is true only when **all** of these hold (evaluated in this order, with `present`
= the set of `Item` objects of every stack with `stackSize > 0` in the inventories of
`pInfo.ACTIVE_PLAYERS` and `pInfo.PLAYER`):

1. `task.getClass() == TaskRetrieval.class` (exact class: `TaskOptionalRetrieval` and any add-on
   class always run the original).
2. Then, either of these is enough:
   a. `task.consume && !task.autoConsume`: `onInventoryChange` does nothing in that case. Skip.
   b. `task.isComplete(pInfo.UUID)`: `detect` returns on its first line. Skip.
3. Otherwise all of the following must hold:
   - `requiredItems` is not empty. For every required `BigItemStack r`: `r != null`;
     `r.stackSize > 0`; `r.getBaseStack() != null`; `item = r.getBaseStack().func_77973_b()`
     is not null; `!present.contains(item)` (identity); and `r.getOreIngredient()`'s private
     `ores` list is empty.
   - For every `uuid` in `pInfo.ALL_UUIDS`, with `p = task.getUsersProgress(uuid)`:
     - if `!consume && groupDetect`: every `p[i] == 0`;
     - if `!consume && !groupDetect`: every `p[i] == 0 || p[i] >= required[i]`;
     - and `p` does not satisfy the completion rule (all `p[i] >= required[i]`, or any one of
       them when `requireOnlyOneItem`).

Why this is exactly a no-op (rule 3): no stack can pass `StackMatch` (needs the same `Item`) or
`OreDictionaryMatch` (empty ore list), so `Detector.run` changes nothing. The `Detector`
constructor changes nothing: no partial counts to reset, and `groupDetect` zero-fill on all-zero
arrays. So `updated` stays false, `setBulkProgress` is not called, and `checkAndComplete` sees
unchanged arrays that do not satisfy completion: no `setComplete`, no `markDirtyParty`, no
`MarkDirtyPlayerEvent`. `getBulkProgress`/`getUsersProgress` do not write anything (they return
the live array or a fresh zero array). The only effect of the original is CPU time and garbage.

`present` is built lazily the first time a given `ParticipantInfo` is seen (a new one is created per
player per tick), and thrown away after any pass-through call that might change an inventory:
every pass-through call except a plain `TaskRetrieval` with `consume == false`. Per its code,
that task never calls the inventory consumer and only posts BQ's `MarkDirtyPlayerEvent`. So
`TaskFluid` (which can swap containers) and consuming retrievals force a rebuild. A stale
`present` can only hold extra items, which only causes extra pass-through, never a wrong skip.

### 5.2 Mixins (late mixins: target classes belong to another mod)

Pattern: `@LateMixin` + `ILateMixinLoader`, `"minVersion": "0.8.5-GTNH"`, no refmap, `remap = false`,
SRG names. The loader registers these mixins only if `betterquesting` and `bq_standard` are in the
loaded-mods set **and** a `ClassReader` scan finds the exact method and call (the same defensive
check BQRelief does in `BQReliefLateMixins.has`). Otherwise it logs and registers nothing.

**M1. `BQStdInventoryPrefilterMixin`**, target `bq_standard.handlers.EventHandler`
- Redirect:
  - method `onServerTick(Lcpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent;)V`
  - `@At(value = "INVOKE", target = "Lbq_standard/tasks/ITaskInventory;onInventoryChange(Ljava/util/Map$Entry;Lbetterquesting/api2/utils/ParticipantInfo;)V")`,
    `require = 1, allow = 1` (the call at offset 196, the only one)
  - handler `private void hoshiestar$onInventoryChange(ITaskInventory task, Map.Entry<UUID, IQuest> quest, ParticipantInfo pInfo)`:
    if `verify` is off and `Prefilter.skip(task, pInfo)` → count and return. Otherwise call
    `task.onInventoryChange(quest, pInfo)` and then `Prefilter.afterPassThrough(task)`. In verify
    mode, see 5.3.
- Inject: `@At("RETURN")` on the same method, `hoshiestar$endTick(ServerTickEvent, CallbackInfo)`:
  drops the cached `ParticipantInfo`/`present` references (no player object is kept across
  ticks) and, every `report` seconds, logs counters.

**M2. `OreIngredientAccessor`** (interface mixin), target `betterquesting.api2.utils.OreIngredient`
- `@Accessor(value = "ores", remap = false) List<ItemStack> hoshiestar$ores();` for the field
  `private final java.util.List ores` (descriptor `Ljava/util/List;`). Needed because the public
  `getMatchingStacks()` calls `Item.func_150895_a` (getSubItems) for wildcard entries, which is a
  client-only method on a dedicated server. Fallback if the accessor fails to apply: one
  `Field.setAccessible` read, cached.

**M3. `TaskProgressableBaseAccessor`** (interface mixin, verify mode only), target
`bq_standard.tasks.base.TaskProgressableBase`
- `@Accessor(value = "userProgress", remap = false) TreeMap<UUID, Object> hoshiestar$userProgress();`
  for `protected final java.util.TreeMap userProgress` (`Ljava/util/TreeMap;`). Used to tell
  "absent" from "all zero" when comparing state.

**Members used, all checked** (javap `-p -s` unless noted):

| Class | Member | Descriptor | Checked in |
|---|---|---|---|
| `bq_standard.handlers.EventHandler` | `onServerTick` | `(Lcpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent;)V` | `DEC/jar` |
| same | `playerInventoryUpdates` (private static final) | `Ljava/util/HashSet;` | `DEC/jar` (not used by the fix) |
| `bq_standard.tasks.ITaskInventory` | `onInventoryChange` | `(Ljava/util/Map$Entry;Lbetterquesting/api2/utils/ParticipantInfo;)V` | `DEC/jar` |
| `bq_standard.tasks.TaskRetrieval` | `requiredItems` (public final) | `Ljava/util/List;` | `DEC/jar` |
| same | `consume`, `autoConsume`, `groupDetect`, `requireOnlyOneItem` (public) | `Z` | `DEC/jar` |
| same | `getUsersProgress` | `(Ljava/util/UUID;)[I` | `DEC/jar` |
| same | `detect` (only read, not targeted) | `(Lbetterquesting/api2/utils/ParticipantInfo;Ljava/util/Map$Entry;)V` | `DEC/jar` |
| `bq_standard.tasks.base.TaskBase` | `isComplete` | `(Ljava/util/UUID;)Z` | `DEC/jar` |
| `bq_standard.tasks.base.TaskProgressableBase` | `userProgress` (protected final) | `Ljava/util/TreeMap;` | `DEC/jar` |
| `bq_standard.tasks.TaskOptionalRetrieval` | extends `TaskRetrieval` (excluded by the exact-class test) | n/a | `DEC/jar` |
| `betterquesting.api.utils.BigItemStack` | `stackSize` (public) | `I` | `DEC/jar` |
| same | `getBaseStack` | `()Lnet/minecraft/item/ItemStack;` | `DEC/jar` |
| same | `getOreIngredient` | `()Lbetterquesting/api2/utils/OreIngredient;` | `DEC/jar` |
| `betterquesting.api2.utils.OreIngredient` | `ores` (private final) | `Ljava/util/List;` | `DEC/jar` |
| `betterquesting.api2.utils.ParticipantInfo` | `PLAYER` | `Lnet/minecraft/entity/player/EntityPlayer;` | `DEC/jar` |
| same | `UUID` | `Ljava/util/UUID;` | `DEC/jar` |
| same | `ALL_UUIDS`, `ACTIVE_PLAYERS` | `Ljava/util/List;` | `DEC/jar` |
| `betterquesting.api2.cache.QuestCache` | `getDirtyQuests` (verify only) | `()Lcom/google/common/collect/ImmutableSet;` | `DEC/jar` |
| same | `LOC_QUEST_CACHE` (verify only) | `Lnet/minecraft/util/ResourceLocation;` | `DEC/jar` |
| `net.minecraft.entity.player.EntityPlayer` | `field_71071_by` | `Lnet/minecraft/entity/player/InventoryPlayer;` | `SRG`, plus the BQ call site |
| `net.minecraft.entity.player.InventoryPlayer` | `func_70302_i_` / `func_70301_a` | `()I` / `(I)Lnet/minecraft/item/ItemStack;` | `SRG` |
| `net.minecraft.item.ItemStack` | `func_77973_b` / `field_77994_a` | `()Lnet/minecraft/item/Item;` / `I` | `SRG` |
| `net.minecraft.entity.player.EntityPlayer` | `getExtendedProperties` (Forge, verify only) | `(Ljava/lang/String;)Lnet/minecraftforge/common/IExtendedEntityProperties;` | constant pool of `ParticipantInfo` (javap `-v`), plus Crucible `Entity.java` line 2764 |

### 5.3 Config switches (`config/hoshiestar.cfg`, category `betterquesting`)

| Name | Default | Meaning |
|---|---|---|
| `inventoryPrefilter` | `true` | Master switch. Read by the late-mixin loader at startup. `false` = M1/M2/M3 are **not registered**, so the bytecode of `bq_standard.handlers.EventHandler` is exactly what BQ (plus BQRelief) ships. Restart needed. |
| `inventoryPrefilterVerify` | `false` | Differential mode. Every call **always runs the original**, so behaviour is exactly the original. For calls the filter would have skipped, it snapshots, before and after: `isComplete(u)` for each `u` in `ALL_UUIDS`, `userProgress.containsKey(u)` plus a copy of `getUsersProgress(u)`, and whether `quest.getKey()` is in each active player's `QuestCache.getDirtyQuests()`. Any difference is a "mismatch": the first 50 are logged with quest and task id, and counted. |
| `inventoryPrefilterReport` | `300` (seconds, 0 = off) | Logs counters: calls seen, skipped (by rule 2a, 2b, 3), passed through, `present` rebuilds, mismatches (verify mode), and the mean ns spent in `onServerTick` START when it processed at least one player. |

"Switch off" path: with `inventoryPrefilter=false` nothing is injected, so the original runs.
With it on, a call the filter does not skip still runs `task.onInventoryChange(quest, pInfo)` with
the very same receiver and arguments, at the same point in the loop and in the same order.

### 5.4 What is deliberately left out

- **The core 1.66%.** Making the 60-tick loop visit only unlocked quests, or skipping
  `updateCache` when "nothing changed", needs a version over many inputs: completion, task
  progress, time-based resets, op status (`canUserEdit`), `viewMode`. BQRelief's epochs cover only
  part of that, and BQRelief is closed source. Stretching `updateCache`'s 200-tick period would
  delay newly visible quests and repeat timers by seconds, which players can see. Gain at most
  about 0.6% + 0.8%. Not worth the risk in Degrau 0.
- **Coalescing (BQRelief's `-Dakashic.bqrelief.inventoryInterval=N`).** Config only, but it changes
  behaviour. A retrieval completes up to N-1 ticks later. Holding the items for less than N ticks
  may not count. And because `playerInventoryUpdates` is a `HashSet<EntityPlayer>` (1.7.10
  `Entity.equals` compares entity ids, and a respawned player keeps its id), a player who
  respawns inside the window is checked against the **old** object's inventory. Gain depends on
  how often one player's inventory changes several times within N ticks, which is unknown.
  (Checked in Crucible source: `Entity.equals` compares `entityId`, `Entity.java` line 258, and
  `ServerConfigurationManager.respawnPlayer` copies the old entity id to the new player at line 752.) If it
  is ever used: N ≤ 5, and only after the filter is measured.
- **`lockedProgress=1` on all quests** is a game-design choice (items count toward locked quests).
  Changing it changes what players see. It is an admin decision, not a Degrau 0 change.
- **An item-to-task index** (skip even the iteration over about 7.4k tasks, about 1.0%): bigger
  change, ordering subtleties. A later step, if measurements justify it.

---

## 6. Why it is safe

Invariants:
- **I1** A skipped call is one the original would have run as a pure no-op: no write to
  `completeUsers`, `userProgress`, any inventory, any `QuestCache`, and no event posted
  (argument in 5.1).
- **I2** Every call that is not skipped is the original call, same arguments, same order. So
  quest/task completion, progress values, item consumption (`consume && autoConsume`),
  `markDirtyParty` and network sync are produced by BQ's own code exactly as before.
- **I3** Items, energy and fluids: the filter never calls an inventory method that writes
  (`func_70298_a`, decrStackSize, is called only by original code). Only `func_70302_i_` and
  `func_70301_a` reads, and only on the server thread.
- **I4** No new persistent state. Nothing is saved, and BQ's NBT is untouched. Cached references
  are dropped at the end of each `onServerTick`.
- **I5** Threading: `onServerTick` runs on the server thread. `playerInventoryUpdates` is processed
  under BQ's own `synchronized` block, and our handler runs inside it. Our static state is touched
  only from there.

What a player could notice: nothing. Retrieval tasks complete in the same tick as before, never
later, never twice (task `setComplete` is idempotent anyway, and quest completion still happens in
BQ's `onLivingUpdate`).

Ways it could go wrong, and the guard for each:
1. *An add-on subclass of `TaskRetrieval` changes `detect`.* Guard: exact-class test.
   (`TaskOptionalRetrieval` is the only subclass in the jar, and it only overrides `ignored`.)
2. *A future BQ version changes `detect`/`Detector` semantics.* Guard: the loader checks the
   method set and BQ version string (`3.8.79-GTNH`). On any other version, register nothing and log.
3. *Ore dictionary entries added after start.* `ores` is the live Forge list and is read on every
   decision. Ore-dict tasks are never skipped while their list is non-empty.
4. *`present` stale after an inventory change inside the loop.* Rebuilt after any pass-through
   call that may write an inventory. Stale only toward "more items", which is safe.
5. *Inventory changed by another thread during the loop.* Same exposure as the original, which
   also reads inventories without locks. (Crucible/Bukkit plugins run on the main thread.)
6. *Party members offline.* The original only scans `ACTIVE_PLAYERS`, so `present` uses the same
   list. Offline members' progress is in `ALL_UUIDS`, and the rule checks it.
7. *A buggy mod registers items under ore name `""`.* Then `NO_ORE.ores` is non-empty and nothing
   is skipped (lost gain, not lost correctness).
8. *Null entries / null items in `requiredItems` (missing mod).* Not skipped. The original
   behaviour, including any exception, is kept.
9. *Mixin conflict with BQRelief.* Different call sites (`getSharedQuests`/`filterKeys` vs
   `onInventoryChange`). If BQRelief's coalescing is enabled later, its HEAD cancel simply means
   our redirect runs less often.
10. *Accessor does not apply under GTNHMixins 0.8.5.* Falls back to reflection. If that fails too,
    rule 3 is disabled (only rules 2a/2b remain) and an error is logged once.

---

## 7. Expected gain

Ceiling for this item: 4.62%. The fix targets the bq_standard part only (2.95%, 2.49 ms/tick).
Inside it, the removable work is `TaskRetrieval.onInventoryChange` (1.85%) minus `isComplete`
(0.19%, still evaluated by the filter) = **1.66%**. The walk over the tasks (handler self 0.5%,
`getEntries` 0.36%, iterators about 0.15%) stays.

Fraction skipped: tasks that still run are (a) ore-dict tasks, 279 of 7,691 (3.6%), (b) tasks
requiring an item id the party carries, and (c) tasks with stored partial progress or
satisfied-but-incomplete multi-item counts. For (b), the median item id appears in 1 task. Even an
inventory holding the 30 (60) most-demanded ids would match at most 1,576 (2,098) tasks, 20%
(27%). So the run fraction is about 5% to 30%, and the skip fraction about 70% to 95%. The filter
costs a few field reads, 1 to 2 identity lookups and one TreeMap lookup per uuid per task. That is
far less than `Detector.<init>` alone (0.45%), estimated at about 0.1 to 0.15%.

Net: 1.66 x (0.70 to 0.95) - 0.15 ≈ **0.9% to 1.4% of the server thread, about 0.8 to 1.2
ms/tick** at 84.2 ms/tick. It also shrinks the spikes: each inventory change now costs a fraction
of a full 7.4k-task recount. This is an estimate from the profile plus the quest-file statistics.
It must be confirmed by the measurement in section 8.

---

## 8. Test plan

### 8a. On the 292-mod test server (preferred; the world is new and empty, one op bot)

Setup (on the test copy only):
1. Start the test server as usual with `inventoryPrefilter=false`. The bot logs in. As op it sends
   `/bq_admin default load AkashaQuests`. Per `QuestCommandDefaults` (decompiled), with no
   `saved_quests/AkashaQuests/` directory and an existing `saved_quests/AkashaQuests.json`, this
   calls `loadLegacy`, which reads `questDatabase`/`questLines`/`questSettings` into the world
   database. Check the console for errors. Then `echo stop >> console.in`, and keep a copy of the
   world folder as the **baseline world**.
2. Pick test items by counting the quest file (counts only):
   **X** = an item id required by no retrieval task (for example a vanilla decorative block whose
   count is 0), **Y** = an id required by many tasks (count printed), and a **Z set** = the exact
   requirements of one non-ore retrieval quest with a single task.

Correctness, differential (A/B on identical starts):
3. Run A: baseline world, `inventoryPrefilter=false`. The bot sends a fixed script with a fixed
   delay (for example 4 per second): 100 x (`/give <bot> X 1`, `/clear <bot> X`), then 50 x
   (`/give <bot> Y 1`, `/clear <bot> Y`), then `/give` of the full Z set and wait 10 s, then `/clear`.
   Then `echo stop >> console.in`. Keep `world-test/betterquesting/` (QuestProgress and party files).
4. Run B: a fresh copy of the baseline world, `inventoryPrefilter=true`, same script. Run C: a fresh
   copy, `inventoryPrefilter=true` + `inventoryPrefilterVerify=true`, same script.
5. Pass criteria: the BQ progress/completion data of A and B are identical after normalising
   timestamps (the Z quest's task complete in both, nothing else changed in either). Run C logs
   **0 mismatches**, and its "would skip" counter is large (most of about 7.4k per X/Y event).

Before/after measurement:
6. Same scripts, but X-churn only, 600 events at 5 per second (120 s), in runs A' (off) and B' (on).
   Start JFR on the server pid you started:
   `jcmd <pid> JFR.start name=bq settings=profile filename=.../scratchpad/etapa1/logs/bq-<A|B>.jfr`,
   stop with `jcmd <pid> JFR.stop name=bq`. Compare the execution samples under
   `bq_standard.handlers.EventHandler.onServerTick` against all server-thread samples, and the mean
   ns per processed tick from `inventoryPrefilterReport` (set it to 60). Expected: per check, the
   cost drops by roughly the skip fraction (with only X in the inventory, nearly 100% of
   `detect` work goes). Repeat with Y-churn to see the worst-case path.

### 8b. Standalone JVM harness (optional, not built)

A JDK 8 harness against the real `TaskRetrieval` needs Forge-patched `ItemStack`/`Item` with a
registered `delegate` (vanilla `SRG` classes lack the Forge fields). That is heavy, so 8a's
verify mode is the primary differential test. If built later: random tasks (1 to 3 items,
random `groupDetect`/`requireOnlyOneItem`/`consume`/`autoConsume`, random stored progress for 1 to
3 uuids), random inventories. For each case, compare `skip()==true` against "state unchanged after
calling the original `onInventoryChange`". Expected: no case where skip is true and state changed.

---

## 9. Not verified

- That production loaded `AkashaQuests.json` (and whether `Forbidden-Campanha` is merged in). The
  production world's `QuestDatabase.json` was not available. The profile's per-call costs fit it,
  but this is not proof.
- How often players' inventories change in production (checks per second). The gain range relies
  on the per-check skip fraction, not on frequency, so it holds as a share of the measured 1.85%.
- The real inventories and party sizes in production (they affect the run fraction in section 7).
- That a protocol bot gets BQ's `PlayerContainerListener` attached and that `/give`/`/clear` reach
  `sendSlotContents` for slots 9 to 44 (expected from the code, not run).
- That `@Accessor(remap = false)` on `OreIngredient`/`TaskProgressableBase` applies under
  UniMixins 0.3.1 (Mixin 0.8.5-GTNH). The fallback is designed but not run.
- The exact `/bq_admin default load AkashaQuests` behaviour on this server (read from decompiled
  code only).
- The filter's own cost (0.1 to 0.15%) is an estimate, not a measurement.
- No harness was run. All exactness arguments come from reading decompiled code
  (CFR output of BetterQuesting-3.8.79-GTNH.jar) and javap.
- `ItemStack.func_77973_b`'s self time (0.6%) is put down to pointer chasing on `delegate.get()`.
  That is an interpretation of the sample, not a measurement.

**Decision: implement M1 (+M2, +M3 for verify), default on, after the section 8a differential
passes with 0 mismatches.**
