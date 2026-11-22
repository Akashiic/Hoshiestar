# 1.F: Ars Magica 2 handler on every living entity, and 1.H: the 2 s watchdog

Read-only study. Nothing was started, nothing in the repo was edited. All paths below are
relative to the scratchpad `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad`
(called `<S>`), unless they start with `/home/user/Hoshiestar` (the repo).

Short answer:
- **1.F: implement, two small mixins that change nothing anyone can see.** Almost all of the 3.6% is
  not "AM2 on every mob". It is **one loop in a player-only method**. Once per player per tick it walks
  `loadedEntityList` of **every loaded world** (60 in production) and builds `UUID.toString()` for every
  living entity, to find entities sped up by the "Accelerate / ConcentrateTime" spell. When nobody is
  sped up, that set (a static `HashMap`) is empty, and the whole walk provably does nothing. Fix: skip the walk when the set
  is empty. Expected **1.5 to 2.1% of server-thread time** (about 1.3 to 1.8 ms of the 84 ms tick), plus
  **about 0.35%** from a second exact skip on every mob. Read the caveat on how the profile charges the
  time (section 1.3).
- **1.H: implement, read-only.** Crucible already publishes "current packet / current player" for its
  own watchdog (`org.spigotmc.WatchdogThread.currentPacket/currentPlayer`, checked with javap). We add
  a tick start/end stamp and two context slots (entity and tile), written with `lazySet` from hook points
  medidor already uses. A daemon thread samples the server thread's stack after 2 s and logs one
  compact report.

---

## 1. What the profile says

Profile: `<S>/anexos/spark/wIBxG0D4y9.json`. It is a spark export, 602 s long, sampling every 4 ms,
`samplerEngine = 1` (async-profiler; the tree holds `libjvm.so.*` frames). 7113 ticks, 11.82 TPS,
8 players. All percentages are of the "Server thread" total (598.9 s).

Reader commands (run from `/home/user/Hoshiestar`, `P=<S>/anexos/spark/wIBxG0D4y9.json`, `R="python3 -I tools/spark_report.py $P"`):

```
$R resumo
$R eventos --top 400 | grep -E "AMEventHandler|PGCommon|ShrinkHandler|AffinityHelper|ArmorEventHandler"
$R busca AMEventHandler
$R busca "am2."
$R arvore --raiz am2.AMEventHandler.onEntityLiving --prof 12 --min 0.02
$R arvore --raiz HandlePlayerSpellExtendedProperties --prof 6 --min 0.0
$R busca HandlePlayerSpellExtendedProperties | applyStatModifiersBasedOnBuffs | isSkillDisabled |
        getExtraVariablesContains | SkillManager.getSkill | func_82165_m | tickContingencySpell |
        getContingencyEffect | manaBurnoutTick | handleExtendedPropertySync | getExtendedProperties   (one per run)
```

The reader prints the tree once per call path, so the same method shows up in many separate pieces.
To add up the direct children over all of them, and to split by caller, I used a 40-line helper. It
imports the reader's own parser: `<S>/etapa1/F-tools/agg.py`
(`cd <S>/etapa1; python3 -I F-tools/agg.py /home/user/Hoshiestar/tools ../anexos/spark/wIBxG0D4y9.json <label> EntityPlayerMP.func_71127_g`).
"Player path" means the stack passes through `EntityPlayerMP.func_71127_g` (onUpdateEntity, the player's
living tick, driven by movement packets). Everything else is mobs and other living entities in the world loop.

### 1.1 Forge event handlers of AM2 / AM2PlayGround (`eventos`)

| Handler (LivingUpdateEvent unless noted) | % server thread |
|---|---:|
| `AMEventHandler.onEntityLiving` (AM2) | **3.57** |
| `PGCommonEventHandler.tickContingencySpell` (AM2PlayGround) | 0.41 |
| `ArmorEventHandler.onEntityLiving` (AM2) | 0.05 |
| `AMEventHandler.onEntityConstructing` (EntityConstructing) | 0.04 |
| `ShrinkHandler.onEntityLiving`, `PGCommonEventHandler.arcaneEffects`, `AffinityHelper.onEntityLivingBase` | 0.03 each |

Other totals: `busca "am2."` = 4.39%, which includes AM2 tiles, mobs and world-gen. `busca airburn` (AM2PG) = 0.57%.

### 1.2 The sub-tree of `am2.AMEventHandler.onEntityLiving` (3.56%, agg.py, merged)

| Direct child | % | Who pays |
|---|---:|---|
| `AMEventHandler.HandlePlayerSpellExtendedProperties` | **2.38** | players only (100% on player path) |
| `BuffStatModifiers.applyStatModifiersBasedOnBuffs` | 0.64 | 0.62 mobs, 0.02 players |
| `SkillTreeManager.isSkillDisabled` (the "DiluteTime" check at the top) | 0.26 | 0.25 mobs |
| `SkillManager.getSkill` | 0.07 | mostly mobs |
| `ExtendedProperties.handleExtendedPropertySync` | 0.06 | all |
| `ExtendedProperties.manaBurnoutTick` | 0.04 | all |
| `AMEventHandler.HandleSpellEffects` | 0.03 | all |
| `UUID.toString` (top of onEntityLiving) | 0.02 | mobs |
| everything else (armor infusion, flip, sync of affinity/skills, ...) | < 0.02 each | |

Split by caller (agg.py): **player path 2.47%, other 1.09%**. So "AM2 on every mob" is about 1.1%,
and the rest is the player-only method.

Inside `HandlePlayerSpellExtendedProperties` (2.38%, merged):

| Child | % |
|---|---:|
| `EntityLivingBase.func_82165_m` (isPotionActive(int)), **self time** | **1.94** |
| `ExtendedProperties.getExtraVariablesContains` | 0.28 |
| `java.util.UUID.toString` | 0.09 |
| `DimensionManager.getWorlds` | 0.05 |

### 1.3 Why the 1.94% "isPotionActive" is really the all-worlds entity loop (inference, with evidence)

`func_82165_m` is `activePotionsMap.containsKey(id)`. Three facts:
1. Across the whole profile, `func_82165_m` costs 11,704 ms (`busca func_82165_m`), and 11,628 ms of it
   sits under `HandlePlayerSpellExtendedProperties` (agg.py). Every other caller adds up to only 76 ms.
   Those other callers make millions of calls: `BuffStatModifiers` calls it 4 times per living entity per tick,
   `HandleSpellEffects` once more.
2. In `HandlePlayerSpellExtendedProperties` there are only two call sites (bytecode offsets 4397 and 4410,
   `javap -c`, see 2.2). With 7113 ticks x 8 players = 56,904 calls of the method, 11,628 ms works out to
   at least 102 µs per `containsKey`. That is impossible, while the same call costs a few ns elsewhere.
3. The production JVM ran without `-XX:+DebugNonSafepoints` (vmArgs in the spark metadata). spark
   attaches async-profiler at runtime, and the server had been up 7.3 h. Without that flag, the JIT keeps
   debug info only at call sites, so samples taken inside an inlined loop get charged to a nearby call
   site's scope. The loop at offsets 440 to 618 ends right before the `func_82165_m` call at 4397, and
   the code in between only runs every ~45 player-ticks. (The mechanism is well known for async-profiler
   without that flag. That it is what happened here is **not verified**; test 8.2 checks it.)

The arithmetic fits as well: 14,260 ms / 56,904 = **251 µs per player per tick**. The loop visits all
entities of all worlds (spark's 1-min windows show 2,091 to 2,903 entities), so that is about 100 ns per
entity: one cache miss for `instanceof`, plus a `UUID.toString()` allocation for living ones. I ran a
synthetic JDK 8 model of the same loop shape: `<S>/etapa1/F-harness/LoopShape.java`, 2,500 padded objects
over 60 lists, 30 to 50% "living", heap churned between passes. Result: **0.29 to 1.3 ms per pass** on
this machine (noisy, GC included). Same order of magnitude. It does not prove the production number;
it shows the loop alone can cost this much.

### 1.4 Other small pieces (not proposed now)

- `PGCommonEventHandler.tickContingencySpell` 0.41%. Of that, `PGContingencyTypesHelper.getContingencyEffect` 0.36%,
  about 0.11 to 0.13% of it inside `java.lang.reflect.Method.invoke` (two reflective calls per living entity per tick).
- `ExtendedProperties.getContingencyEffect` 0.22% (an array read; memory-bound, nothing to gain).
- `Entity.getExtendedProperties` 0.45% in total, across all mods.

---

## 2. The code

Jars: `<S>/server-test3/mods/AM2.5-LTS-1.6.11-LE-CR40.jar` (AM2) and `AM2PlayGround-Beta-0.12.1-CR2.jar`
(AM2PG). Decompiled with CFR to `<S>/etapa1/decomp/F/{am2,am2full,am2pg,tickrelief}`. Full bytecode of
AMEventHandler: `<S>/etapa1/F-tools/AMEventHandler.javap.txt` (`javap -p -c`). AM2 is built with SRG
names for Minecraft members.

### 2.1 What actually runs (AM2PG's ASM transforms)

AM2PG rewrites AM2 classes when they load. Its boot log (`<S>/medidor-test/t6/logs/fml-server-latest.log`,
lines `[am2pg/arsmagica2] Method 'X' in class 'Y' transformed successfully`) lists, for the classes on
our path, only `ExtendedProperties.{saveNBTData, loadNBTData, handleDataPacket, getUpdateData, getMaxMana,
manaBurnoutTick, getCanHaveMoreSummons}`, `AffinityHelper.*`, `ArmorHelper.*`, `SkillTreeManager.init` and `SpellUtils.*`.
**`am2.AMEventHandler` is not transformed.** Across all 227 mod jars, the strings
`AMEventHandler` / `HandlePlayerSpellExtendedProperties` appear only in the AM2 jar (scan with
`unzip -p | grep -a`). So the bytecode of the two methods we target is the jar's bytecode. Generic
transformers that touch every class could still change details. Not verified; the self-check in 5.4 covers that.

### 2.2 Hot method 1: `am2.AMEventHandler.HandlePlayerSpellExtendedProperties`

`private void HandlePlayerSpellExtendedProperties(EntityPlayer, ExtendedProperties)`,
descriptor `(Lnet/minecraft/entity/player/EntityPlayer;Lam2/playerextensions/ExtendedProperties;)V`
(javap -p -s on the AM2 jar). Called only from `onEntityLiving` when `ent instanceof EntityPlayer`
(decompiled `am2full/am2/AMEventHandler.java`, around line 596).

The part that matters (CFR, the `enabled_accelerate` block):

```java
if (enabled_accelerate) {
    acceleratedBlocks = extendedProperties.getExtraVariablesContains("accelerated_fast_tile_");
    for (...) { ... }                               // usually empty
    WorldServer[] worlds = DimensionManager.getWorlds();          // bytecode 440 (outside the try)
    int s1 = worlds.length;
    try {
        for (int l = 0; l < s1 && l < worlds.length; ++l) {
            int s2 = worlds[l].field_72996_f.size();              // loadedEntityList
            for (int f = 0; f < s2 && f < worlds[l].field_72996_f.size(); ++f) {
                Object entityobj = worlds[l].field_72996_f.get(f);
                if (!(entityobj instanceof EntityLivingBase)
                    || !acceleratedEntitiesUUIDs.containsKey(UUID2 = ((EntityLivingBase)entityobj).func_110124_au().toString()))
                    continue;
                for (int i = 0; i < acceleratedEntitiesUUIDs.get(UUID2); ++i)
                    ((EntityLivingBase)entityobj).func_70071_h_();   // extra onUpdate = the "Accelerate" effect
            }
        }
    } catch (IndexOutOfBoundsException l) { } catch (Exception e) { e.printStackTrace(); }
}
```

Bytecode facts (javap -c, offsets in `F-tools/AMEventHandler.javap.txt`):
- `440: invokestatic net/minecraftforge/common/DimensionManager.getWorlds:()[Lnet/minecraft/world/WorldServer;`
  is the **only** `getWorlds` call in the method. Its result goes into local 4. The try range is 450 to 618.
- After the loop (633 and later), locals 4, 5 and 6 are stored again (720, 765, 787) before any load. Nothing
  later reads the loop's values.
- `DimensionManager.getWorlds()` in Crucible is `worlds.values().toArray(new WorldServer[worlds.size()])`
  (`build/Crucible/eclipse/cauldron/src/main/java/net/minecraftforge/common/DimensionManager.java:441`).
  It has no side effects.

The set `acceleratedEntitiesUUIDs` is `public static Map<String,Integer>`, a plain `HashMap`
(javap: `Ljava/util/Map;`). Only `AMEventHandler` reads or writes it (grep over all of decompiled AM2 and AM2PG,
plus the jar scan above). The only `put` is at `AMEventHandler.java:811` (am2full): it fills from the player's extra
variables `accelerated_fast_entity_<power>_<uuid>`, which `am2/spell/components/ConcentrateTime.java:79` writes.
The only `remove` is at line 806, when that extra variable runs out. `enabled_accelerate` is
`skillConfig.isSkillEnabled("Accelerate")` (`AMCore.java:210`). The production profile shows `getWorlds` and
`getExtraVariablesContains` under this method, so it is `true` in production.

Also note: the loop runs **once per player per tick**. Cost is O(players x all entities in all worlds).
With 20 players it would be 2.5 times the 8-player cost.

### 2.3 Hot method 2: the "DiluteTime" check at the top of `onEntityLiving`

`public void onEntityLiving(LivingEvent$LivingUpdateEvent)`, descriptor
`(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V` (javap). Called by Forge's
`EventBus` for every `LivingUpdateEvent`, which means every living entity, every tick (`ASMEventHandler_2006_...` in the profile).

```java
if (!SkillTreeManager.instance.isSkillDisabled(SkillManager.instance.getSkill("DiluteTime"))
    && slowedEntitiesUUIDs.containsKey(UUID2 = ent.func_110124_au().toString())
    && ent.field_70173_aa % slowedEntitiesUUIDs.get(UUID2) != 0) {
    event.setCanceled(true);
    return;
}
```

Bytecode: `14: invokevirtual am2/spell/SkillManager.getSkill:(Ljava/lang/String;)Lam2/api/spell/component/interfaces/ISkillTreeEntry;`,
`17: invokevirtual am2/spell/SkillTreeManager.isSkillDisabled:(Lam2/api/spell/component/interfaces/ISkillTreeEntry;)Z`,
`20: ifne 72`. Each appears **once** in the method (counted in the javap output).
`isSkillDisabled` calls `getSkillTreeEntry`, which calls `getTree(...)` up to 4 times. Each `getTree` **copies
a whole skill-tree ArrayList** (`new ArrayList` + `addAll`, `SkillTreeManager.java:67-87`): allocation on
every mob, every tick. That shows in the profile as `ArrayList.addAll` + `jint_disjoint_arraycopy`.
`slowedEntitiesUUIDs` behaves like the accelerate set: only AMEventHandler writes it (put at line 830,
remove at 825), and only `DiluteTime.java:85` writes the extra variable behind it.

### 2.4 Other methods looked at (not targeted)

- `am2.buffs.BuffStatModifiers.applyStatModifiersBasedOnBuffs(Lnet/minecraft/entity/EntityLivingBase;)V`
  (descriptor from the call site at offset 81 of onEntityLiving). For each of 4 AM2 buffs it checks the
  potion and looks up the movement and attack attribute instances to remove AM2 modifiers that might be
  left over. The cost is `BaseAttributeMap.func_111151_a` -> `HashMap.getNode`, i.e. cache misses per mob.
  See 5.3.
- `PGContingencyTypesHelper.getContingencyEffect(ExtendedProperties,int)` (AM2PG) calls
  `ExtendedProperties.getContingencyEffect(int)` by reflection (`Method.invoke`) every time. See 5.3.
- `ExtendedProperties.getExtraVariablesContains(String)` copies matching keys of `extra_properties` into a new
  `HashMap` on every call (0.28%). The caller mutates and `clear()`s the result. Caching it would need
  invalidation on every write to `extra_properties`, so it is not proposed.

---

## 3. Root cause

AM2.5-LE adds an "Accelerate" feature. To find accelerated entities, it scans the entity list of
**every loaded world, once per online player, every tick**, and builds a UUID string for each living entity.
The set it looks them up in is empty almost all the time, but nothing checks for that before the scan.
That is about 2% of the server thread. Smaller: the same pattern ("build a string or copy a list, then
look it up in a map that is usually empty") runs at the top of the handler for every mob (`DiluteTime`, ~0.35%).

---

## 4. What the in-house mods already do about it

- `Akashic-TickRelief-1.2.0-serveronly.jar` has `com.akashic.tickrelief.core.AM2PGRegenFixTransformer`
  + `com.akashic.tickrelief.am2.AM2RegenHooks` (decompiled in `<S>/etapa1/decomp/F/tickrelief`). This is a
  **correctness** fix for AM2PG's mana-regen hooks in `ExtendedProperties.manaBurnoutTick` (it stops a stage
  from "looping"). It does not touch `AMEventHandler`. In this pack it **does nothing**: every boot log has
  `[TickRelief] AM2PlayGround mana regen hooks not present in manaBurnoutTick (AM2PlayGround absent or not
  yet applied); nothing done.` (6 occurrences in `<S>/medidor-test/*/logs` and `<S>/boot*.log`). TickRelief's
  transformer seems to run before AM2PG's. It is not a performance item (`manaBurnoutTick` is 0.04%), but
  the coordinator may want to tell the admin, because the fix the admin thinks is active is not.
- `Akashic-ATM-Family` uses only AM2's power API. No other `Akashic-*` jar references `am2/` or `airburn/` (jar scan).
- Conclusion: **nothing in-house addresses this cost.** No other jar targets `am2.AMEventHandler`, so no mixin conflicts.

---

## 5. Proposed fix

One late mixin class on an AM2 class (AM2 is a mod jar), plus the early mixins of the watchdog.
Following the pack pattern: `ILateMixinLoader` (javap on `+unimixins-all-1.7.10-0.3.1.jar`:
`String getMixinConfig()`, `List<String> getMixins(Set<String>)`), `@LateMixin` on the loader class,
config `mixins.hoshiestar_core.late.json` with `"minVersion": "0.8.5-GTNH"`, no refmap, `remap = false`,
and the mixin is listed only when `arsmagica2` is in the loaded-mod set. Injectors use MixinExtras
`@WrapOperation` (present in UniMixins: `com/llamalad7/mixinextras/injector/wrapoperation/WrapOperation.class`).
Unlike `@Redirect`, it chains with other wrappers. No other jar targets these methods, so `@Redirect`
would also work. `require = 0` on every injector, plus a self-check flag (like medidor's `Ganchos`) so the boot log
states "applied / not applied" instead of failing the boot.

### 5.1 Mixin F1: skip the all-worlds scan when no entity is accelerated (main gain)

- Target class: `am2.AMEventHandler`
- Method: `HandlePlayerSpellExtendedProperties(Lnet/minecraft/entity/player/EntityPlayer;Lam2/playerextensions/ExtendedProperties;)V`
- Injection: `@WrapOperation(method = "HandlePlayerSpellExtendedProperties(Lnet/minecraft/entity/player/EntityPlayer;Lam2/playerextensions/ExtendedProperties;)V",
  at = @At(value = "INVOKE", target = "Lnet/minecraftforge/common/DimensionManager;getWorlds()[Lnet/minecraft/world/WorldServer;", remap = false), require = 0, remap = false)`
  (the one call, offset 440)
- Handler:
  ```java
  private static final WorldServer[] NO_WORLDS = new WorldServer[0];
  private WorldServer[] hoshiestar$getWorlds(Operation<WorldServer[]> original) {
      if (CoreConfig.am2SkipAccelScan && AMEventHandler.acceleratedEntitiesUUIDs.isEmpty()) {
          return NO_WORLDS;              // s1 = 0: the loop body never runs
      }
      return original.call();            // exact original call
  }
  ```
  A shared zero-length array is safe: nothing can be written into it, and the method only reads `.length`.
- Effect: with the set empty, offsets 450 to 618 do zero iterations and jump to 633, which is exactly where the
  original goes after its loop. With the set non-empty (somebody is accelerated right now), the original
  runs unchanged, including AM2's own quirk (an accelerated entity gets extra updates once per online player).

### 5.2 Mixin F2: skip the "DiluteTime" lookup when no entity is slowed

- Target class: `am2.AMEventHandler`, method `onEntityLiving(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V`
- Injection A: `@WrapOperation(..., at = @At(value = "INVOKE", target = "Lam2/spell/SkillManager;getSkill(Ljava/lang/String;)Lam2/api/spell/component/interfaces/ISkillTreeEntry;", remap = false))` (offset 14, the only one)
- Injection B: `@WrapOperation(..., at = @At(value = "INVOKE", target = "Lam2/spell/SkillTreeManager;isSkillDisabled(Lam2/api/spell/component/interfaces/ISkillTreeEntry;)Z", remap = false))` (offset 17, the only one)
- Handlers:
  ```java
  private ISkillTreeEntry hoshiestar$getSkill(SkillManager self, String name, Operation<ISkillTreeEntry> original) {
      if (CoreConfig.am2SkipDiluteCheck && AMEventHandler.slowedEntitiesUUIDs.isEmpty()) return null;
      return original.call(self, name);
  }
  private boolean hoshiestar$isSkillDisabled(SkillTreeManager self, ISkillTreeEntry e, Operation<Boolean> original) {
      if (CoreConfig.am2SkipDiluteCheck && AMEventHandler.slowedEntitiesUUIDs.isEmpty()) return true; // "&&" short-circuits
      return original.call(self, e);
  }
  ```
- Effect: with the set empty, the original path would have evaluated `!disabled && containsKey(...)`, where
  `containsKey` on an empty map is `false`, so the `if` fails. Returning `true` takes the same `ifne 72` branch
  as "skill disabled", which is a branch the original takes in normal play. Skipped: one map lookup, four
  ArrayList copies, one `UUID.toString()`. None of them has side effects.

### 5.3 Considered and deferred (designed, not in the first jar)

- **F3 `BuffStatModifiers` skip** (≤0.64%, mobs). Target `am2.buffs.BuffStatModifiers.applyStatModifiersBasedOnBuffs(Lnet/minecraft/entity/EntityLivingBase;)V`,
  `@Inject(HEAD, cancellable)`. It would skip when a per-entity "clean" flag is set and none of the 4 buff potions
  (entangled, frostSlowed, fury, haste) is active. The flag is set after an original run that saw none of them
  active, and cleared whenever one is seen active. The original is idempotent while no potion is active
  (scaling modifiers are removed by UUID; the fury/entangled ones are removed only when the instance is
  identical). But `BuffEffectHaste` / `BuffEffectFrostSlowed` add modifiers **with the same UUIDs**
  (`am2full/am2/buffs/BuffEffectHaste.java`), and modifiers come back from NBT. So exactness depends on an
  invariant ("nobody adds these UUIDs between two ticks while no AM2 buff is active") that I cannot check for
  all 292 mods. Not proposed for Degrau 0. If wanted later: switch `am2.skipBuffModifierScan`, default `false`.
- **F4 AM2PG contingency reflection** (~0.1%). `@WrapOperation` on `java/lang/reflect/Method.invoke` inside
  `airburn.am2playground.utils.helpers.PGContingencyTypesHelper.getContingencyEffect` would call
  `p.getContingencyEffect(num)` directly. It is exact, because the `catch (Exception)` around it also catches a direct
  exception. The gain is too small for a late mixin into a second mod. Not proposed.
- **Early exit for "mobs with no AM2 state"**: the whole mob path is 1.09%, of which 0.62% is F3's part and 0.35% is
  F2's. What is left (contingency, flip, mana links, sync) is ~0.1% and would need about ten conditions to
  prove "no state". Not worth it.

### 5.4 Config switches (`config/hoshiestar.cfg`, category `am2`)

| Name | Default | Meaning |
|---|---|---|
| `am2.skipAccelScan` | `false` in the first jar, then `true` after test 8.1 | F1: skip the all-worlds entity scan when no entity is accelerated |
| `am2.skipDiluteCheck` | `false` in the first jar, then `true` after test 8.1 | F2: skip the DiluteTime skill lookup and UUID string when no entity is slowed |
| `am2.logStatsMinutes` | `5` (0 = off) | every N minutes, log one line: sizes of `acceleratedEntitiesUUIDs` / `slowedEntitiesUUIDs`, and how many player-ticks F1 skipped vs ran. This tells the admin whether the gain is real in production (see caveat in 6) |

Defaults follow decision 16 in PROGRESSO ("behaviour changers come off; the admin turns them on one by one").
These two do not change behaviour, so they can default to `true` once 8.1 passes.
Self-check: the boot line `Hoshiestar-Core: am2 F1 applied=yes/no, F2 applied=yes/no`, filled by the mixin plugin's `postApply`.

### 5.5 How "switch off" restores the exact original path

With a switch `false`, each handler executes `original.call(...)` with the same receiver and arguments. The
original instruction runs, gets the same value, and the method continues on the same bytecode. The only
difference is one static boolean read and one extra call frame. If AM2 is absent, the late mixin is not listed.
If the target is missing, `require = 0` leaves the class as it was.

---

## 1.H: the watchdog ("vigia")

### H.1 What exists already (checked)

- `org.spigotmc.WatchdogThread` (in `server.jar`, javap: `public static final AtomicReference currentPacket`, `public static final
  AtomicReference currentPlayer`, `private volatile long lastTick`, `public static void tick()`). Source
  `build/Crucible/src/main/java/org/spigotmc/WatchdogThread.java`. It wakes every **10 s** (`sleep(10000)`).
  It warns once a tick has run `max(timeout/3, 5000)` ms, and at `timeout-time` it dumps all threads (and restarts,
  if `restart-on-crash`). It already reads world fields from its own thread.
- Crucible's patched `NetworkManager.processReceivedPackets` (`func_74428_b`, `eclipse/cauldron/.../NetworkManager.java:237-276`)
  sets `WatchdogThread.currentPlayer` (the `NetHandlerPlayServer.playerEntity`) before the packet loop and
  `currentPacket` around each `packet.processPacket(...)`, and clears both afterwards. **So packet and player context is
  already published from the main thread.** We only read it.
- `MinecraftServer.tick()` = `func_71217_p()V` (joined.srg: `MD: net/minecraft/server/MinecraftServer/u ()V ... func_71217_p ()V`;
  javap on the vanilla class: `protected void u()`). Crucible calls `WatchdogThread.tick()` first thing in it (`MinecraftServer.java:825`).
- medidor (`/home/user/Hoshiestar/medidor`) already hooks, with tests T1 to T6 passed: `World.func_72866_a(Lnet/minecraft/entity/Entity;Z)V` HEAD/RETURN,
  `World.func_72939_s()V` HEAD, the `TileEntity.func_145845_h()V` call inside it (`@ModifyReceiver` + `@Inject AFTER`),
  the same pair on `Lcom/akashic/worldfirewall/WorldMutationGuard;runTileEntityUpdate(Lnet/minecraft/tileentity/TileEntity;Lnet/minecraft/world/World;)V`,
  and `EntityPlayerMP.func_71127_g()V` HEAD/RETURN.

### H.2 Design

Main thread: writes only, no locks, no allocation. The watchdog thread does all reading and logging.

```
final class Vigia {                                       // Hoshiestar-Core
  static final AtomicLong tickStart = new AtomicLong();   // 0 = not in a tick
  static final AtomicLong tickSeq   = new AtomicLong();
  static final AtomicReference<Object> curEntity = new AtomicReference<>();
  static final AtomicReference<Object> curTile   = new AtomicReference<>();
  static volatile Thread serverThread;
  // main thread:
  static void tickStart() { if (serverThread == null) serverThread = Thread.currentThread();
                            tickSeq.lazySet(tickSeq.get() + 1); tickStart.lazySet(System.nanoTime()); }
  static void tickEnd()   { tickStart.lazySet(0); }
  static void entity(Object e) { curEntity.lazySet(e); }   // null at RETURN
  static void tile(Object t)   { curTile.lazySet(t); }
}
```

`lazySet` is an ordered store with no full fence (about 1 ns on x86). A watchdog that wakes every 100 ms
does not need anything stronger.

Hooks (early mixins, `remap = false`, `require = 0`, all `@Inject` or `@ModifyReceiver` returning the
same object, none cancellable, none changing a value). These are the same points as medidor, except the tick pair:

| # | Target class.method + descriptor | Point | Handler |
|---|---|---|---|
| W1 | `net.minecraft.server.MinecraftServer.func_71217_p()V` | `HEAD` | `Vigia.tickStart()` |
| W2 | same | `RETURN` | `Vigia.tickEnd()` (an exception skips it; the next HEAD resets) |
| W3 | `net.minecraft.world.World.func_72866_a(Lnet/minecraft/entity/Entity;Z)V` | `HEAD` / `RETURN` | `Vigia.entity(e)` / `Vigia.entity(null)` |
| W4 | `net.minecraft.entity.player.EntityPlayerMP.func_71127_g()V` | `HEAD` / `RETURN` | `Vigia.entity(this)` / `Vigia.entity(null)` |
| W5 | `net.minecraft.world.World.func_72939_s()V`, `INVOKE Lnet/minecraft/tileentity/TileEntity;func_145845_h()V` | `@ModifyReceiver` / `@Inject AFTER` | `Vigia.tile(te)` / `Vigia.tile(null)` |
| W6 | same method, `INVOKE Lcom/akashic/worldfirewall/WorldMutationGuard;runTileEntityUpdate(...)V` (firewall variant) | `@ModifyArg index 0` / `@Inject AFTER` | same as W5 |

Signatures: W1 from joined.srg + javap (`net.minecraft.server.MinecraftServer` has `u()V`). W3/W5/W6
from joined.srg (`MD: ahb/a (Lsa;Z)V ... func_72866_a`, `MD: ahb/h ()V ... func_72939_s`) + javap
(`ahb`: `public void a(sa, boolean)`, `public void h()`), and medidor's runtime test. W4 from
`MD: mw/i ()V ... func_71127_g` + javap (`mw`: `public void i()`). Minecraft classes are not in
`server.jar`. They come from the vanilla jar on its Class-Path, with Forge binpatches applied at runtime.
So javap shows the vanilla signature, and Crucible's patched source shows the signatures are unchanged.

Nesting (a rider updated inside its mount's update) sets the slot to null early, so the outer entity is lost for
the rest of its update. That is diagnostic only, and documented. Restoring it would need `@Share` or a
main-thread stack; not worth it.

Watchdog thread `Hoshiestar Vigia` (daemon, started at `FMLServerStartedEvent`, stopped at `FMLServerStoppingEvent`):

```
loop every pollMs (100):
  t0 = tickStart.get(); seq = tickSeq.get()
  if t0 != 0 && now - t0 >= limitMs && seq != lastReportedSeq && !stopping:
      take sample 1: ctx before -> serverThread.getStackTrace() -> ctx after
                     (keep ctx only if the same object before and after)
      while same seq still running and samples < N (5): sleep sampleMs (500), take another sample
      wait until the tick ends (seq changes or tickStart == 0) or capMs (30 s), whichever is first
      if rate limiter allows: log ONE report; else count it as suppressed
      lastReportedSeq = seq
```

Report (one log4j block, logger `Hoshiestar-Vigia`, about 20 lines):

```
[Vigia] Slow tick #123456: 3120 ms (limit 2000 ms; tick ended). GC during tick: 40 ms.
  Samples: 5 every 500 ms; same top frame in 5/5.
  Packet: C08PacketPlayerBlockPlacement (x=.., y=.., z=.., face=..) from player <name>, dim 7, pos 123.5 64.0 -88.2
  Entity: -          Tile: -
  Likely owner: ChromatiCraft (jar 'ChromatiCraft 1.7.10 V33a.jar'): Reika.ChromatiCraft.Auxiliary.Ability.AbilityCalls.shiftArea
  Stack (top 15, java.*/sun.* folded):
    ...
  Suppressed since last report: 3 slow ticks (longest 2450 ms).
```

How each piece is read, safely, from the watchdog thread:
- **Stack**: `serverThread.getStackTrace()`. It stops only the server thread, briefly (a safepoint operation;
  on JDK 21 a per-thread handshake, not verified). At most 5 times per slow tick, and only after it is already
  2 s late. The thread itself is never interrupted, suspended or stopped.
- **Owner mod**: the first frame from the top that is not a neutral package (same list as
  `tools/spark_report.py NEUTRAL`). Class name goes to `Launch.classLoader.findResource(name.replace('.','/')+".class")`, giving
  the jar URL, then the FML mod id via a map `source file -> modid`. That map is built once, on the main thread,
  at `FMLServerStartedEvent`, from `Loader.instance().getActiveModList()` / `ModContainer.getSource()`.
  Results are cached per class name. No `Class.forName`, so no class initialisation on the watchdog thread.
- **Packet**: `WatchdogThread.currentPacket.get()`. Use `getClass().getName()`, plus declared fields of
  primitive or `String` type only, via reflection. Not Crucible's `Stringify.describePacket`: it calls
  `toString()` on every field value (`build/Crucible/src/main/java/io/github/crucible/util/Stringify.java:10-25`),
  and that could run mod code.
- **Player**: `WatchdogThread.currentPlayer.get()`. Name via `func_146103_bH()` (getGameProfile; `GameProfile`
  is immutable), dimension via field `field_71093_bK` (int), position via `field_70165_t/u/v` (double).
  Plain field reads can be stale, never unsafe. We never call `toString()` on an entity (Entity.toString formats
  the world name and could NPE).
- **Entity / tile**: class name. For an entity: `func_145782_y()` (entity id), `field_71093_bK`, position. For a tile:
  `field_145851_c/field_145848_d/field_145849_e` (x, y, z, public int), and world via `func_145831_w()` then
  `field_73011_w` (provider) then `field_76574_g` (dimension id). All of it in `try/catch (Throwable)`; a null link
  prints "?".
  (javap + joined.srg: `sa/s,t,u` D, `sa/ap` I, `sa/y()I`, `aor/c,d,e` I, `aor/w()Lahb;`, `ahb/t Laqo;`, `aqo/i` I, `yz/bJ()LGameProfile;`.)
- **GC during tick**: sum of `GarbageCollectorMXBean.getCollectionTime()` at the poll before the limit was
  crossed and at report time.

Rate limiting: at most one report per `vigia.minIntervalSeconds` (60) and at most `vigia.maxPerHour` (30).
Suppressed ticks are counted, and the longest one is noted in the next report. Three consecutive internal
errors make the vigia log once and switch itself off. During `FMLServerStoppingEvent` it stops (shutdown saves
are long and expected).

Coexistence:
- **Spigot WatchdogThread**: we never call `WatchdogThread.tick()` and never write its fields; we only `get()` its two
  AtomicReferences. Our limit (2 s) sits below its 5 s minimum warning. Its timeout/restart behaviour is untouched.
- **Crucible timings / packet-timeout log** (`crucible_logging_packetTimeout`, off by default): untouched.
- **sampler mod's 30 s stall dump** (`Sampler watchdog thread`): independent. Both only read stacks.
- **medidor**: two `@Inject`s at the same HEAD/RETURN points coexist (Mixin orders them). Nothing is shared.
- **Akashic-World-Firewall** ASM transformer: W5/W6 copy medidor's dual-path design, so either transformer order works.

Config (`config/hoshiestar.cfg`, category `vigia`):

| Name | Default | Meaning |
|---|---|---|
| `vigia.enabled` | `true` | decision 16: the read-only watchdog comes on. `false` = thread not started, hooks return after one boolean read |
| `vigia.limitMs` | `2000` | tick length that triggers a report |
| `vigia.samples` / `vigia.sampleMs` | `5` / `500` | extra stack samples while the same tick runs |
| `vigia.pollMs` | `100` | watchdog wake-up period |
| `vigia.minIntervalSeconds` / `vigia.maxPerHour` | `60` / `30` | rate limit |
| `vigia.frames` | `15` | stack frames printed |
| `vigia.entityContext` / `vigia.tileContext` | `true` / `true` | W3-W4 / W5-W6 publish context (when `false` the hook is one boolean read) |
| `vigia.positions` | `true` | include coordinates and player name in the report |

"Switch off" restores the original exactly. Every vigia hook is an `@Inject` without cancel, or a
`@ModifyReceiver`/`@ModifyArg` that returns the object it got. None of them changes control flow or values.
Off, they read one boolean and return.

---

## 6. Why it is safe

### F1 / F2 invariants and argument
1. **Same thread.** `acceleratedEntitiesUUIDs` and `slowedEntitiesUUIDs` are plain HashMaps that only
   `AMEventHandler` touches, on the server thread (Forge `LivingUpdateEvent`). Our `isEmpty()` runs on the same
   thread, at the exact point where the original would read the map. No new race.
2. **Same result.** Empty set, F1: the original loop's only observable effect is `func_70071_h_()` on entities whose
   UUID is in the set, and there are none. Its other operations (`size`, `get`, `instanceof`, `toString`,
   `containsKey`) have no side effects. Skipping them changes nothing. Empty set, F2: the `if` is false in both versions.
3. **Values used later.** The loop's locals are not read after offset 618 (2.2). F2 returns `true` only into an `ifne`
   that the original also takes when DiluteTime is disabled.
4. **Non-empty set, both:** the original code runs. The emptiness is read live at every call, so an entity added to
   the set during a tick is seen by the very next scan, as in the original.
5. **No items, energy, fluids or saved data** are involved. Neither method writes NBT on the skipped path.

### Ways it could go wrong
- **The set rarely empties.** Entries are removed only when the caster's own player tick sees the extra
  variable expire (line 806). If the caster logs out first, the entry stays in the static map until the caster
  comes back or the server restarts. **Then F1 gains nothing** (it never does harm). The `am2.logStatsMinutes`
  line will show it. Production size of the sets: not verified.
- **Exceptions.** In the original, an odd exception inside the loop (not IndexOutOfBounds) is printed with
  `printStackTrace` and swallowed. With F1 active and the set empty, that log line could no longer appear.
  It is only a log difference.
- **The bytecode at run time is different** (a generic transformer reshapes the method). `require = 0`, so the
  injector just does not apply; the self-check line says `applied=no`. No crash, no behaviour change.
- **A future AM2 update** moves the call. Same as above.
- **Attribution is wrong** (the 1.94% is not the loop). Then F1 only saves ~0.14% (UUID.toString + getWorlds). It is still
  correct. Test 8.2 decides it before anything goes to production.
- **Client side:** the mod is server-only. On an integrated server (single player) the maps are per-JVM statics
  shared with the client thread in the original too; not a target.

### Vigia
- **Main thread:** only `lazySet` of a long or a reference. No lock, no allocation, no exception path (`System.nanoTime()` and
  `lazySet` cannot throw). Cost estimate: about 2 stores per entity and per tile per tick. With production's
  ~2,500 entities and tens of thousands of tiles (not verified), that is up to ~60k stores x ~1 ns, about 0.06 ms
  per tick (0.07%). For comparison, medidor measured its heavier hooks at 0.21 ms per tick with 2,088 hoppers + 400 pigs.
- **Watchdog thread:** never takes a lock the server holds. `findResource` may briefly take URLClassPath's internal
  lock, which the main thread also takes when it loads a class. That is contention for microseconds, and no deadlock
  is possible because we hold nothing else. log4j appenders are synchronized, so the main thread could wait a moment
  if it logs at the same instant. That happens once per report at most.
- **Racy reads** of entity, tile or player fields can be stale or inconsistent (for example a position from one tick
  and a dimension from the next). Java memory safety means no crash; every read is in `try/catch (Throwable)`. The report
  marks context as "consistent" only when the slot held the same object before and after the stack capture.
- **Leaks:** the slots keep a reference to the last entity or tile until the next write. Bounded; cleared at every
  RETURN; at most one object each.
- **It must never kill anything:** no `interrupt`, `stop`, `suspend` or `System.exit` anywhere in the class. Only a
  review can check that.
- **Privacy:** the report contains a player name and position (the admin asked for that, D-21). It goes only to the
  server log. `vigia.positions=false` removes them.

---

## 7. Expected gain

Upper bounds from the profile (server-thread share, 84 ms tick):

| Piece | Profile share it can remove | Fraction removed | Expected |
|---|---:|---|---:|
| F1 | 1.94 (attributed loop) + 0.09 (UUID.toString) + 0.05 (getWorlds) = **2.08%** | all of it, while the set is empty; 0 while not empty | **1.5 to 2.1%** (1.3 to 1.8 ms/tick) |
| F2 | 0.26 (isSkillDisabled) + 0.07 (getSkill) + 0.02 (UUID.toString) = **0.35%** | all of it, while the slowed set is empty | **0.3%** (0.25 ms) |
| Vigia | adds cost | | about -0.05% |
| **Total** | | | **about 1.8 to 2.4%** of the server thread. At saturation that is about 11.8 to 12.1 TPS |

Why a range: the low end assumes part of the 1.94% belongs to something else, and that the accelerate set is
sometimes non-empty. **Worst case** (attribution wrong): F1 ~0.14%, total ~0.5%. The gain grows with
players x entities: with 16 players and the same entity count, F1 would be worth about twice as much.
What stays unchanged: `getExtraVariablesContains` 0.28% (player), `BuffStatModifiers` 0.64%, AM2PG contingency 0.41%,
and the other small AM2 handlers (~0.2%).

---

## 8. Test plan

Everything on the test copy prepared by `scripts/prepare-test-env.sh` (new world, the protocol bot as op).
Stop always with `echo stop >> console.in`. Profiles with JFR (`jcmd <pid> JFR.start settings=profile filename=...`), never spark upload.

### 8.1 Correctness / differential test

Setup (console commands into `console.in`, at spawn, which stays loaded):
1. Bot logs in (`tools/test_bot.py`, it sends C03 20/s, so its `func_71127_g` and the AM2 player path run).
2. `summon Pig <x> 70 <z>` for a 20 x 20 grid around spawn (400 pigs), plus
   `summon Item <x> 70 <z> {Item:{id:"minecraft:stone",Count:1},Age:-30000}` on a 2-block grid (1,500 items;
   the spacing stops them merging; whether 1.7.10 honours `Age` this way is not verified, and they last at least 5 min anyway).
3. A **test-only** console command in Hoshiestar-Core, compiled in only when `-Dhoshiestar.test=true`:
   `hoshie test am2 accel <pigUUID> <n>` / `... slow <pigUUID> <n>` / `... clear`. It puts or removes entries in
   `AMEventHandler.acceleratedEntitiesUUIDs` / `slowedEntitiesUUIDs`. It also prints, after 200 ticks, the
   `field_70173_aa` (ticksExisted) of the marked pig and of an unmarked pig, and the number of non-cancelled
   `LivingUpdateEvent`s of the marked pig (from a lowest-priority listener of the test command).

Runs, each with switches F1/F2 off and then on (live toggle if the core supports reload; otherwise two boots):

| Case | Expected, identical with switch off and on |
|---|---|
| sets empty | marked and unmarked pigs age by 200 in 200 ticks; 200 non-cancelled events |
| `accel <pig> 2`, 1 player online | marked pig ages 200 + 2 x 1 x 200 = 600 (AM2 updates it n times per online player per tick); unmarked 200 |
| `slow <pig> 2` | marked pig: LivingUpdateEvent cancelled on odd ticks, so 100 non-cancelled events; unmarked 200 |
| `clear`, then 200 ticks | back to case 1 |

Pass = every number equal between off and on. Also: no new ERROR lines in `logs/fml-server-latest.log`, and
the boot line says `F1 applied=yes, F2 applied=yes`.

Vigia correctness (test-only commands, same `-Dhoshiestar.test=true` guard):
- `hoshie test stall 3000`, sent **by the bot as chat**. Expect one report: ~3000 ms, packet
  `C01PacketChatMessage`, player = bot name, dim 0, bot position, top frame `Thread.sleep` <- Hoshiestar test
  command, owner `hoshiestar_core`.
- Same from the **console**: report with "Packet: -" (console commands run inside the tick, not in packet handling).
- `hoshie test stallentity <pigUUID> 2500`: a test listener sleeps in that pig's LivingUpdateEvent. Report shows
  `Entity: EntityPig id=.. dim 0 pos ..`.
- `hoshie test stalltile <x> <y> <z> 2500`, with a vanilla hopper placed there by `setblock <x> <y> <z> hopper`.
  The test hook inside W5 sleeps when that tile ticks. Report shows `Tile: TileEntityHopper @ x y z dim 0`.
- Rate limit: 5 x `stall 2500` within 60 s. Expect 1 report now, and "Suppressed since last report: 4" in the next
  report after 60 s.
- Coexistence: one `stall 6000` (above Spigot's 5 s minimum warning, far below any `timeout-time`; the tester must
  first read the copy's `settings.timeout-time` without printing it, and skip this case if it is below 20 s). Expect
  both Spigot's warning and our report; the server keeps ticking.
- Stop with `stop`: no report during shutdown; the thread is gone (`jcmd <pid> Thread.print` has no `Hoshiestar Vigia` after stop begins).

### 8.2 Before/after measurement (and checking the attribution in 1.3)

1. Add `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` to the **test** start script only.
   With the 1,900 entities of 8.1 and the bot online, record JFR for 300 s with F1/F2 **off**:
   `jcmd <pid> JFR.start name=f1off settings=profile duration=300s filename=<S>/etapa1/F-jfr/f1off.jfr`.
   Read with `jfr print --events jdk.ExecutionSample`, then count Server-thread samples whose stack contains
   `AMEventHandler.HandlePlayerSpellExtendedProperties`, and see which **source lines** of that frame they land on.
   The AM2 jar has a LineNumberTable (`javap -l`): the loop (offsets 440 to 618) is **lines 775 to 799**, and the two
   `func_82165_m` calls (offsets 4397, 4410) are **line 1175**. Expected: most samples on lines 775 to 799, few in
   `func_82165_m`. That confirms 1.3.
2. Same with F1/F2 **on** (`f1on.jfr`). Expected: samples in HandlePlayerSpellExtendedProperties drop by the loop's
   share; `SkillTreeManager.isSkillDisabled` almost gone from the mob path.
3. Tick time: `forge tps` on the console 3 times per run (mean tick ms overall), plus the
   `am2.logStatsMinutes` line (skipped vs ran scans). With one bot and ~1,900 entities in 1 to 3 loaded worlds,
   the loop is about 0.1 to 0.5 ms/tick (harness scale). That is visible in JFR sample counts, and is at the edge
   of noise in `forge tps`. Repeat off/on/off/on, 300 s each.
4. Vigia cost: same setup plus 2,000 hoppers (as in medidor test T6), `vigia.enabled` false vs true, 300 s
   each, `forge tps`. Pass = difference within run-to-run noise.

### 8.3 Standalone harness (done here)

`<S>/etapa1/F-harness/LoopShape.java` (JDK 8, run with `env -u _JAVA_OPTIONS`, cwd `<S>/etapa1/F-harness`):
`java -Xmx2g LoopShape 2500 0.5 60 2000` gave 0.64 to 1.3 ms per pass; `... 2500 0.3 ...` 0.29 to 0.62 ms;
`... 1000 0.5 ...` 0.30 to 0.63 ms. It is a synthetic model (not Minecraft classes); it only supports
"hundreds of µs per scan", which matches the 251 µs inferred from production. A differential harness against the
real `AMEventHandler` is not practical outside the server: AM2's classes link against SRG-named Minecraft
classes that exist only after FML deobfuscation. That is why the differential test is in-game (8.1).

---

## 9. Not verified

1. That the 1.94% charged to `func_82165_m` is the entity loop (strong inference in 1.3; test 8.2 step 1 checks it).
2. That `acceleratedEntitiesUUIDs` / `slowedEntitiesUUIDs` are empty most of the time in production (they can stay
   non-empty while a caster is offline; the `am2.logStatsMinutes` line will tell).
3. That 8 players were online for the whole 602 s (the spark header gives the count at the end only). The
   per-player-tick figures (251 µs, 102 µs per call) assume 8.
4. That `enabled_accelerate` is `true` in production: inferred from `getWorlds` / `getExtraVariablesContains` frames
   under the method, not read from config.
5. The run-time bytecode of `AMEventHandler` after all transformers. AM2PG's log does not list it and no jar names it,
   but generic transformers were not inspected.
6. JDK 21 `Thread.getStackTrace()` on another thread using a per-thread handshake (pause length not measured here).
7. `LaunchClassLoader.findResource` returning the mod jar URL for mod classes, and its locking.
8. The production tile count used in the vigia cost estimate.
9. Whether 1.7.10's `EntityItem` honours `Age:-30000` (test setup detail only).
10. Why TickRelief's AM2PG regen fix never applies (seen in logs; cause not investigated, out of scope).
11. Whether Hoshiestar-Core will support live reload of switches (the test plan has a two-boot fallback).

## Recommendation

**Implement F1 and F2** in Hoshiestar-Core (late mixin on `am2.AMEventHandler`, two switches, stats line), and **implement the vigia
(W1 to W6)** in the same jar, on by default. Do not implement F3/F4 now. Gate F1/F2 on test 8.1 (differential) and on
8.2 step 1 (confirms where the time is). Concurrency review by a different agent is required for the vigia
(PROMPT rule), not for F1/F2 (single-threaded).
