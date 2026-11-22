# 1.E Crucible `ChunkBlockHashMap.get` and 1.G shutdown bug D-24: design report

Items 1.E and 1.G of Stage 1 ("Degrau 0"). Study and design only; no mod code was written.
Everything below was read from the real `server.jar` of the pack, the dev build of the same Crucible
commit (MCP names), the real mod jars, and the production spark profile (through `tools/spark_report.py`
or small helpers that import its own `Report`/`Thread` classes). Anything not checked is marked
**nao verificado**.

**Short answer.**
- **1.E: implement.** Replace the 4-entry MRU read cache of `thermos.wrapper.ChunkBlockHashMap` with a
  direct-mapped cache (one array slot per chunk position modulo 64x64), filled on read and invalidated
  on `remove`. The map structure (Koloboke map of 16x16 bunches), `size`, `raw()` and `bulkCheck` stay
  untouched. A differential harness against the real class (3 million random operations per run,
  negative and extreme coordinates) gave identical results in every case except one where **today's
  code is already wrong** (it returns an unloaded chunk after a double load; section 2.4). Microbenchmark
  on JDK 21/G1: 35 to 60% less time per lookup. Expected gain **0.9 to 2.2 points of the 3.63%**, about
  0.8 to 1.8 ms of the 84 ms tick. Early mixin with `@Overwrite` on 3 methods, gated at startup by the
  config (switch off = the mixin is not registered, the class bytes are exactly the jar's).
- **1.G: implement, with reservations** (default off until the SIGTERM tests pass). The shutdown hook
  asks the tick loop to stop and waits for the server thread to save; a second `stopServer()` becomes a
  no-op (or waits for the first). Termination logic proven in a standalone JVM on JDK 8 and 21. The
  reservation: the real SIGTERM test needs an admin waiver of the project rule "never kill" (pending
  item 7b); an in-process alternative is given.

Paths used below:
- `$S` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad`
- `$J` = `$S/anexos/spark/wIBxG0D4y9.json` (production profile, 602 s, 7113 ticks, 8 players)
- `$B` = `$S/build/Crucible` (Crucible `staging@67b53034`: `src/main/java` = Crucible-own sources,
  `eclipse/cauldron/src/main/java` = patched Minecraft/Forge sources, MCP names,
  `eclipse/cauldron/build/classes/java/main` = compiled dev classes)
- SRG view (vanilla + `server.jar` classes remapped notch->SRG with the jar's own
  `deobfuscation_data-1.7.10.lzma`, same recipe as `medidor/build.sh`): `$S/etapa1/E-srg/srg.jar`
- helpers: `$S/etapa1/E-tools/` (`callers.py`, `selftime.py`, `scanjars.py`, `mixintargets.py`)
- harness: `$S/etapa1/E-harness/src/` (`NewMap.java`, `Mk.java`, `Diff.java`, `Stale.java`,
  `Bench.java`, `ExitProof.java`); classpath in `$S/etapa1/E-harness/cp.txt`
- decompiled: `$S/etapa1/decomp/E-chunkmap/` (`rfb/` = RetroFuturaBootstrap launch class loaders,
  `saverelief/` = Akashic-SaveRelief 1.0.1)

---

## 1. What the profile says

Base: "Server thread" = 598 904 ms over 7113 ticks, so **1% = 0.842 ms per tick**.
The sampler is async-profiler (`samplerEngine: 1`, version 4.0 in the metadata; my reading of the
enum value is **nao verificado** against spark's proto, but the presence of `libjvm.so` frames confirms
a native-aware sampler, so there is no safepoint bias).

Commands (from `/home/user/Hoshiestar`, or from `$S/etapa1` for the helpers):

```
python3 -I tools/spark_report.py $J resumo
python3 -I tools/spark_report.py $J busca ChunkBlockHashMap
python3 -I tools/spark_report.py $J busca VanillaChunkHashMap
python3 -I tools/spark_report.py $J busca koloboke
python3 -I tools/spark_report.py $J busca ChunkProviderServer.func_73154_d
python3 -I tools/spark_report.py $J busca ChunkProviderServer.func_73149_a
python3 -I tools/spark_report.py $J busca ChunkProviderServer.getChunkIfLoaded
python3 -I tools/spark_report.py $J busca ChunkProviderServer.loadChunk
python3 -I tools/spark_report.py $J busca World.func_72964_e
python3 -I tools/spark_report.py $J arvore --raiz ChunkBlockHashMap.get --prof 4 --min 0.0
python3 -I E-tools/selftime.py $J ChunkBlockHashMap.get        # inclusive, self, direct children
python3 -I E-tools/selftime.py $J ChunkBlockHashMap.bulkCheck
python3 -I E-tools/selftime.py $J ChunkProviderServer.func_73154_d
python3 -I E-tools/selftime.py $J ChunkProviderServer.func_73149_a
python3 -I E-tools/callers.py $J ChunkBlockHashMap.get --up 1     # direct callers
python3 -I E-tools/callers.py $J ChunkBlockHashMap.get --up 2
python3 -I E-tools/callers.py $J ChunkBlockHashMap.get --up 4 --top 30
```

### 1.1 The sub-tree (% of server-thread time)

| Node | inclusive | self | ms/tick |
|---|---:|---:|---:|
| `thermos.wrapper.ChunkBlockHashMap.get` | **3.63%** (21 716 ms) | **2.95%** (17 648 ms) | 3.05 |
| -> `koloboke...MutableLHashSeparateKVLongObjMapGO.get` | 0.67% (3 992 ms) | | |
| -> -> `MutableSeparateKVLongLHashSO.index` | 0.65% (3 900 ms) | | |
| -> -> -> `LHash$SeparateKVLongKeyMixing.mix` | 0.17% (996 ms) | | |
| -> `libjvm.so SATBMarkQueueSet::enqueue_known_active` (G1 write barrier) | 0.013% (76 ms) | | |
| `ChunkBlockHashMap.bulkCheck` (not touched by this design) | 0.18% (1 096 ms) | 0.11% | |
| `VanillaChunkHashMap` (all methods) | 0.14% | | |

The G1 SATB frame under `get` can only come from reference stores inside `get`, i.e. the MRU shuffle
(`last4 = last3; ... last1 = ref`). It is the visible tip of the write-barrier cost (the card-marking
post-barrier is inlined and shows up as `get` self time). G1 is the production collector (`resumo`:
"G1 Young Generation", "G1 Concurrent GC" every 44 s).

### 1.2 Who calls it

Direct callers of `ChunkBlockHashMap.get` (`callers.py --up 1` and `--up 2`):

| Caller | % | via |
|---|---:|---|
| `ChunkProviderServer.func_73154_d` (provideChunk) | 1.91 | `World.func_72964_e` (getChunkFromChunkCoords) 1.91 |
| `ChunkProviderServer.func_73149_a` (chunkExists) | 1.63 | `World.func_72916_c` (chunkExists) 1.32; `mekanism.api.Coord4D.exists` 0.26; `com.akashic.tickrelief.ChunkGate.isLoaded` 0.04; others < 0.01 |
| `ChunkProviderServer.getChunkIfLoaded` | 0.07 | `World.getChunkIfLoaded` (light updates) |
| `ChunkProviderServer.loadChunk` | 0.01 | |

Four levels up (`--up 4`) the cost is spread over hundreds of call sites; the largest single chains
are 0.44% (`Coord4D.getTileEntity` -> `World.func_147438_o` getTileEntity), 0.33% (DragonAPI
`TileUpdateEvent.fire` -> `World.func_72899_e` blockExists), 0.29% (`World.func_147456_g` tick-chunks
-> `func_72903_x`), 0.21% (`PathFinder`), 0.20%+0.18% (`World.func_82733_a` entity selection). No
single caller dominates: **the cost is per call, paid on every block, tile-entity and chunk-existence
read**. That is why the fix has to be inside the map, not at a caller.

For context: `provideChunk` totals 3.90%, of which `ChunkBlockHashMap.get` 1.85%, chunk loading on a
miss (`func_73158_c`) 1.55% and its own self time 0.40%; `chunkExists` totals 1.70% (get 1.63%).
The 1.55% of loads on demand is a separate subject (not this item).

Loaded chunks in the profile: 4 100 to 4 800 across 60 worlds (`resumo`, per-minute windows).

---

## 2. The code

### 2.1 `thermos.wrapper.ChunkBlockHashMap` (in `server.jar`, a Crucible class)

Source: `$B/src/main/java/thermos/wrapper/ChunkBlockHashMap.java`. Runtime signatures from
`javap -p -s -cp $S/etapa1/E-srg/srg.jar thermos.wrapper.ChunkBlockHashMap` (SRG view):

| Member | Descriptor |
|---|---|
| `private final LongObjMap<Chunk[][]> map` | `Lcom/koloboke/collect/map/LongObjMap;` |
| `private int size` | `I` |
| `private Chunk last1, last2, last3, last4` | `Lnet/minecraft/world/chunk/Chunk;` |
| `public LongObjMap raw()` | `()Lcom/koloboke/collect/map/LongObjMap;` |
| `public int size()` | `()I` |
| `public boolean bulkCheck(Collection)` | `(Ljava/util/Collection;)Z` |
| `public Chunk get(int,int)` | `(II)Lnet/minecraft/world/chunk/Chunk;` |
| `public void put(Chunk)` | `(Lnet/minecraft/world/chunk/Chunk;)V` |
| `public void remove(Chunk)` | `(Lnet/minecraft/world/chunk/Chunk;)V` |

In the jar itself the class is compiled against notch names (`apx` = Chunk, `apx.g`/`apx.h`); FML's
`DeobfuscationTransformer` rewrites them to `net/minecraft/world/chunk/Chunk`, `field_76635_g`
(xPosition), `field_76647_h` (zPosition) at load time. `javap -p -cp server.jar apx` shows
`public final int g; public final int h;` (both **final**), mapped in `notch2srg.srg`
(`FD: apx/g ... field_76635_g`, `FD: apx/h ... field_76647_h`).

The hot method (source lines 105-133; `javap -c` of the jar shows the same, including that each
`lastN` field is read twice, once for the test and again for `areturn`):

```java
public Chunk get(int x, int z) {
    if (last1 != null && last1.xPosition == x && last1.zPosition == z) return last1;
    if (last2 != null && last2.xPosition == x && last2.zPosition == z) return last2;
    if (last3 != null && last3.xPosition == x && last3.zPosition == z) return last3;
    if (last4 != null && last4.xPosition == x && last4.zPosition == z) return last4;
    Chunk[][] bunch = this.map.get((((long) (x >> 4)) << 32L) ^ (z >> 4));
    if (bunch == null) return null;
    x %= 16; z %= 16;
    Chunk ref = bunch[(x + (x >> 31)) ^ (x >> 31)][(z + (z >> 31)) ^ (z >> 31)];
    if (ref != null) { last4 = last3; last3 = last2; last2 = last1; last1 = ref; }
    return ref;
}
```

`put` (lines 135-164): `size++` unconditionally, stores the chunk in its bunch slot (creating the bunch
on first use) and pushes it into `last1`. `remove` (166-203): nulls the bunch slot (and `size--`) if it
is non-null, regardless of which object it holds; then removes MRU entries with the same coordinates.
Bunches are never removed from the Koloboke map.

### 2.2 `thermos.wrapper.VanillaChunkHashMap` and `ChunkProviderServer`

`VanillaChunkHashMap extends LongHashMap` (`$B/src/main/java/thermos/wrapper/VanillaChunkHashMap.java`)
keeps a `ConcurrentHashMap<Long,Chunk> vanilla` next to the `ChunkBlockHashMap`. `add(key, value)`
-> `chunkt_TH.put(chunk)` + `vanilla.put`; `remove(key)` -> `vanilla.remove` then `chunkt_TH.remove(chunk)`.
`ChunkProviderServer` (`$B/eclipse/cauldron/src/main/java/net/minecraft/world/gen/ChunkProviderServer.java`)
has `loadedChunkHashMap_KC` (line 85) and a second face `loadedChunkHashMap = loadedChunkHashMap_KC.thisIsNotMyRealFace()`
(line 86) given to mods that use the vanilla field; both share the same `ChunkBlockHashMap`.

All mutations of the `ChunkBlockHashMap` go through `put`/`remove`:
- `put` from `VanillaChunkHashMap.add`, called by `ChunkProviderServer.originalLoadChunk` (CPS:261),
  `ChunkIOProvider.callStage2` (`net/minecraftforge/common/chunkio/ChunkIOProvider.java:46`),
  `CraftWorld.chunkLoadPostProcess` (`CraftWorld.java:301`), and any mod calling the vanilla
  `loadedChunkHashMap.add` (`func_76163_a`).
- `remove` from `VanillaChunkHashMap.remove`, called by `unloadQueuedChunks` (CPS:567) and any mod
  calling `func_76159_d`.

Readers of `get`: CPS `chunkExists` :100, `unloadChunksIfNotNearSpawn` :111,131,146, `getChunkIfLoaded`
:166, `loadChunk` :177, `originalLoadChunk` :218, `provideChunk` :308, `lastAccessed` :627;
`CraftWorld.loadChunk` :287. `bulkCheck`: only `World.checkChunksExist` (`World.java:758`).

`raw()` has **no user**: grep over the whole Crucible source tree finds none, and a byte scan of every
class in the 292 mod jars and 65 plugin jars for `thermos/wrapper`, `ChunkBlockHashMap`,
`loadedChunkHashMap_KC` and `rawThermos` finds nothing (`python3 -I E-tools/scanjars.py $S/server-test3/mods ...`,
same for `plugins`). No mod mixes into `thermos.*` either.

Iteration: `ChunkBlockHashMap` offers none. Code that iterates loaded chunks uses
`rawVanilla().values()` (the `ConcurrentHashMap`: `CPS.unloadAllChunks` :159, `CraftWorld.java:165,1450`,
`CrucibleCommand.java:236`) or `loadedChunks` (`ArrayList`). Neither is touched, so **iteration order
is unchanged**.

### 2.3 Negative coordinates: is the abs-of-modulo indexing wrong?

No, it is correct, only unusual.
- The bunch is `x >> 4` (arithmetic shift, floor division). For `x != 0`, `x` and `-x` are in
  different bunches (positive bunches are >= 0, negative ones <= -1), so they can never collide.
  Harness: 0 cases for `x` in 1..2^20 (`Diff.java`, part 2).
- Inside one bunch the 16 values of `x` give `x % 16` in {0, -15..-1} (negative bunch) or {0..15};
  `(v + (v >> 31)) ^ (v >> 31)` is `|v|`, which maps them one-to-one onto 0..15. Checked exhaustively
  for **all 2^28 bunches of the int range**: 0 violations (`Diff.java`, part 1). The permutation is
  not `x & 15` (chunk -1 goes to index 1), but nobody reads the bunches except through the same formula.
- The bunch key `((long)(x>>4) << 32) ^ (z>>4)` sign-extends `z>>4` into the high half, so for negative
  `z` the high half holds `~(x>>4)`. It is still injective (low 32 bits = `z>>4`, whose sign then tells
  how to decode the high half). Harness: 0 collisions over 2.2 million pairs including the int extremes
  (`Diff.java`, part 3).

### 2.4 An existing bug in the MRU (proven)

If the same coordinates are `put` twice without a `remove` in between (two chunk objects for one
position: the "attempt to load a chunk that is already being loaded" path at CPS:228-231, or a mod
calling `loadedChunkHashMap.add` directly), the MRU holds both. A later `remove` of either clears the
slot but its shifting loop leaves the older object in `last1`, so `get` keeps returning a chunk that
is **no longer in the map** (writes to it are never saved). Also `size` stays one too high.
`Stale.java` output (real class from `server.jar`):

```
remove B(latest): original get -> A (stale, not in map) | model get -> null | size orig=1 model=1
remove A(older):  original get -> A (stale, not in map) | model get -> null | size orig=1 model=1
remove other object: original get -> A (stale, not in map) | model get -> null | size orig=1 model=1
```

How often a double put happens in production is **nao verificado** (rare; it logs a `bigWarning`).
The proposed cache cannot hold two objects for one position, so it returns what the map holds. The
`size` overcount is kept as is (changing it would change when a dimension unloads, CPS:571).

---

## 3. Root cause

Every block, tile-entity and chunk-existence read pays a chain of dependent memory loads: up to four
MRU probes, each in a different `Chunk` object (cache lines that are usually cold), then a Koloboke
probe (mixing, key array, value array) and two more array loads for the 16x16 bunch. On every hit
that is not in the MRU it also writes four reference fields of a long-lived object, each going through
G1's write barriers. The 4-entry MRU is too small for the access pattern (hundreds of tile entities and
entities spread over thousands of chunks, each touching its own and neighbouring chunks), so it costs
on almost every call and helps on few.

---

## 4. What the in-house mods already do

- **Nothing about the chunk map.** No mod or plugin references `thermos.wrapper` (scan above). The
  in-house mods only consume it: `Akashic-TickRelief` `ChunkGate.isLoaded` calls `chunkExists` (0.04%),
  `AkashicBellow` `DeepStages.loaded` a few ms. Mixins on `ChunkProviderServer` exist in
  `Akashic-PlayerChunkAsync`, `Galacticraft-4.0-Akashic`, `hodgepodge` (fastload, chunk-loading guards),
  `etfuturum` (`python3 -I E-tools/scanjars.py $S/server-test3/mods net/minecraft/world/gen/ChunkProviderServer`);
  none targets `ChunkBlockHashMap`, so there is no mixin conflict. If any of them mutates the vanilla
  `loadedChunkHashMap`, it still ends in `ChunkBlockHashMap.put/remove` (2.2), which is where the cache
  is maintained.
- **Nothing that fixes D-24.** No jar references `ServerShutdownThread`. Relevant neighbour:
  `Akashic-SaveRelief` 1.0.1 (decompiled in `saverelief/`):
  - `@Inject(method="func_71260_j()V", at=HEAD)` -> `SaveRelief.onServerStopping()`: waits up to 60 s
    for its autosave thread, closes its IO queue and drains it (`SaveRelief.java:111-134`);
  - `@Inject` at HEAD of `DimensionManager.setWorld(ILnet/minecraft/world/WorldServer;)V` with `null`:
    waits for that world's queued writes (`beforeWorldGone`, `SaveRelief.java:262-286`);
  - its own shutdown hook "SaveRelief shutdown flush" that waits up to 60 s for queued writes
    (`SaveIO.java:363-371`).
  These run inside `stopServer`; today, with the double concurrent `stopServer` of D-24, they run twice
  and in parallel. The fix makes them run once, on the server thread (6.2).

---

## 5. The proposed fix

One server-only mod `Hoshiestar-Core` (decision 16), early mixins declared through `IEarlyMixinLoader`
as in `medidor/` (`"minVersion": "0.8.5-GTNH"`, no refmap, `remap = false`, SRG names). Compile
against the SRG view (`medidor/build.sh` recipe) so that Chunk field accesses compile to
`field_76635_g`/`field_76647_h`. All switches are read once at coremod time; the mixin plugin's
`shouldApplyMixin` returns false for a mixin whose switch is off, so **"off" leaves the target class
byte-for-byte as in the jar** (changing a switch needs a restart).

### 5.1 Can an early mixin target a class of `server.jar` outside `net.minecraft`?

Yes.
- On Java 21 the launch class loader is RetroFuturaBootstrap's `net.minecraft.launchwrapper.LaunchClassLoader`
  (RFB 1.1.0 is on the manifest `Class-Path`). Its built-in exclusions (`rfb/net/minecraft/launchwrapper/LaunchClassLoader.java:73-74`):
  class-loader `java.`, `javax.imageio.`, `javax.sound.`, `javax.swing.`, `sun.`, `org.lwjgl.`,
  `org.apache.logging.`, `org.objectweb.asm.`, `net.minecraft.launchwrapper.`, `org.slf4j.`,
  `com.gtnewhorizons.retrofuturabootstrap.`; transformer `javax.`, `argo.`, `com.google.common.`,
  `org.bouncycastle.`, `net.minecraft.launchwrapper.injector.`, `com.gtnewhorizons.retrofuturabootstrap.`.
  FML adds `cpw.mods.fml.*` / `LZMA.` / `org.apache.` / `com.google.common.` / `com.mojang.` /
  `net.minecraftforge.classloading.` (`FMLTweaker.java:122-127`, `FMLServerTweaker.java:18-22`,
  `FMLLaunchHandler.java:57-60`); Crucible adds `io.github.crucible.bootstrap.` and some JDK prefixes
  (`CrucibleCoremodHook.java:11`, `Lwjgl3ifyGlue.java:114-124`). **None covers `thermos.` or
  `org.bukkit.craftbukkit.`**, so both classes are loaded by the launch class loader and pass through
  the transformer chain (Mixin included). Coremod manifests can add exclusions (`CoreModManager.java:563`);
  no mod jar contains the string `thermos/wrapper`.
- Precedent in this pack: `necrotempus-v2.1.0.jar` has mixins on `org/bukkit/craftbukkit/v1_7_R4/entity/CraftPlayer`
  and `.../boss/CraftBossBar` (`python3 -I E-tools/mixintargets.py $S/server-test3/mods`), Crucible classes
  outside `net.minecraft`.
- `ChunkBlockHashMap` is first loaded when the first `ChunkProviderServer` is built (world load), long
  after early mixins are registered.
- That the mixin sees the class **after** FML's deobfuscation (descriptor `(II)Lnet/minecraft/world/chunk/Chunk;`,
  not `(II)Lapx;`) is inferred from the medidor and the Akashic mods, which use SRG names on classes
  that are notch-named in the jar. **nao verificado** for a non-MC class: the first boot must confirm
  it (test 8.1 step 0).

**Mixin (our mod) versus a patch for the fork/upstream: mixin now, pull request later.** The mixin runs
on the production `server.jar` (decision 1), is switchable and costs nothing to undo. The same change
is a ~40-line diff in `ChunkBlockHashMap.java`, a good pull request for upstream Crucible (decision 11),
which would also let the fork drop the dead MRU fields and fix the `size` overcount there.

### 5.2 Mixin E1: `MixinChunkBlockHashMap` (early)

Target: `thermos.wrapper.ChunkBlockHashMap` (`@Mixin(value = ChunkBlockHashMap.class, remap = false)`).

Shadows (all checked with `javap -p -s` on the SRG view, 2.1):
- `@Shadow @Final private LongObjMap map;` `Lcom/koloboke/collect/map/LongObjMap;`
- `@Shadow private int size;` `I`

Unique members:
- `private static final int hoshie$BITS` (from config, 4..7, default 6) and `hoshie$MASK = (1 << BITS) - 1`
- `@Unique private final Chunk[] hoshie$slots = new Chunk[1 << (2 * BITS)];` (initialiser merged into
  the constructor `()V`; 4096 slots = 16 KB with compressed oops; 60 worlds = about 1 MB)
- `@Unique private int hoshie$epoch;` incremented by every `remove` (unused now; it is the hook for the
  later per-thread caches, 6.4)

Index: `slot(x, z) = (x & MASK) | ((z & MASK) << BITS)` (two's complement `&` handles negatives; no
modulo, no abs).

| Method (descriptor) | Injection | New body |
|---|---|---|
| `get` `(II)Lnet/minecraft/world/chunk/Chunk;` | `@Overwrite` | `Chunk[] s = hoshie$slots; int i = slot(x,z); Chunk c = s[i]; if (c != null && c.field_76635_g == x && c.field_76647_h == z) return c; c = hoshie$lookup(x,z); if (c != null) s[i] = c; return c;` |
| `put` `(Lnet/minecraft/world/chunk/Chunk;)V` | `@Overwrite` | original body verbatim (null check, `size++`, bunch creation and slot store, same key and index formulas) **minus** the four MRU writes, **plus** `hoshie$slots[slot(cx,cz)] = chunk;` |
| `remove` `(Lnet/minecraft/world/chunk/Chunk;)V` | `@Overwrite` | original structure part verbatim (reads `chunk.xPosition` first, so `remove(null)` still throws NPE; nulls the slot and `size--` only if non-null) **minus** the MRU part, **plus** `Chunk c = hoshie$slots[i]; if (c != null && c.x == cx && c.z == cz) hoshie$slots[i] = null; hoshie$epoch++;` |

`hoshie$lookup(int,int)` (unique, private, no writes): exactly the original structure lookup
(`map.get(((long)(x>>4) << 32L) ^ (z>>4))`, null -> null, then `bunch[|x%16|][|z%16|]`).

Not touched: constructor (except the merged initialiser), `raw()`, `size()`, `bulkCheck`. The fields
`last1..last4` become dead (always null).

Why `@Overwrite` and not `@Inject(cancellable)`: a cancellable inject allocates a `CallbackInfoReturnable`
per call on a path taken about 9x10^5 times per second (21.7 s / 602 s of a ~40 ns call); `@WrapMethod`
allocates its `Operation`. Overwrite has zero overhead, and the startup gate gives the exact original
when off. Its risk (silently replacing a changed upstream method) is handled by a fingerprint: the
mixin plugin reads the class bytes before applying and compares a hash of the instruction lists of
`get`, `put` and `remove` (labels and line numbers stripped) with the values of `staging@67b53034`;
on mismatch it does not apply the mixin and logs a WARN. It also refuses to apply if another mixin
config targets `thermos.wrapper.ChunkBlockHashMap` (none today).

Config (`config/hoshiestar.cfg`, category `chunkmap`):

| Option | Default | Meaning |
|---|---|---|
| `B:chunkMapCache` | `true` | Register E1 at startup. `false`: E1 is not registered, the class is the jar's, bit for bit. Restart to change. (Default on because it changes no game behaviour; see 6.1 for the one difference in an already-corrupt state. The admin may prefer `false` until test 8.1 has run.) |
| `I:chunkMapCacheBits` | `6` (4..7) | Side of the cache = 2^bits chunks (6 = 64x64). Smaller saves memory, larger aliases less between players far apart. |
| `B:chunkMapVerify` | `false` | Test only: on every cache hit also run `hoshie$lookup` and compare references; on mismatch log coordinates and both objects (class + identity hash), count it, and return the structure's value. Costs more than the original. |

### 5.3 Mixins S1 to S3: shutdown (D-24)

The flow today (`$B/eclipse/cauldron/src/main/java/net/minecraft/server/MinecraftServer.java`):
`ServerShutdownThread.run()` (`$B/src/main/java/org/bukkit/craftbukkit/v1_7_R4/util/ServerShutdownThread.java:14-25`)
calls `server.stopServer()` directly on the hook thread. `stopServer()` (:561-617) checks
`!worldIsBeingDeleted && SERVER_STARTED && !serverStopped` without a lock, disables plugins, saves
players and worlds, fires `WorldEvent.Unload` and calls `DimensionManager.setWorld(id, null)`, which
removes the id from `worldTickTimes` (`DimensionManager.java:245-247`) while the server thread is
still in `updateTimeLightAndEntities` (`ids` copied at :934, `worldTickTimes.get(id)[...]` at :1016):
NPE, crash, and the server thread's `finally` (:750-776) runs `stopServer()` a second time,
concurrently. `serverStopped` is only set by `run()` after its own `stopServer()` returns (:755, :774),
then `systemExitNow()` (`DedicatedServer.java:407-410`, `System.exit(0)`).

Signatures (MCP names from `javap -p -s -cp $B/eclipse/cauldron/build/classes/java/main ...`, SRG
names from `$B/build/tmp/notch2srg.srg` and `$B/forge/fml/conf/methods.csv`/`fields.csv`; vanilla
obfuscated names cross-checked with `javap -c` of `ServerShutdownThread` in `server.jar`, which calls
`MinecraftServer.o:()V` = `func_71260_j`):

| Member | Runtime name | Descriptor |
|---|---|---|
| `MinecraftServer.stopServer()` | `func_71260_j` | `()V` (throws `MinecraftException`) |
| `MinecraftServer.initiateShutdown()` | `func_71263_m` | `()V` |
| `MinecraftServer.run()` | `run` | `()V` |
| `MinecraftServer.primaryThread` (Crucible field, name not obfuscated) | `primaryThread` | `Ljava/lang/Thread;` (public final) |
| `MinecraftServer.serverStopped` | `field_71316_v` | `Z` |
| `MinecraftServer.worldIsBeingDeleted` | `field_71290_O` | `Z` |
| `DedicatedServer.systemExitNow()` | `func_71240_o` | `()V` |
| `ServerShutdownThread.server` | `server` | `Lnet/minecraft/server/MinecraftServer;` (private final) |
| `ServerShutdownThread.run()` | `run` | `()V` |

That Crucible-added names (`primaryThread`) keep their names at runtime is supported by the jar's
`ServerShutdownThread` bytecode, which reads `MinecraftServer.reader` by its plain name.
The patched `MinecraftServer` comes from binpatches at runtime; I read it from the dev build of the
same commit (the rebuilt jar matched production in 5 317 of 5 318 entries, PROGRESSO 0.7).

**S2: `MixinMinecraftServerStop`** on `net.minecraft.server.MinecraftServer`, `remap = false`:
- `@Shadow @Final public Thread primaryThread;` `@Shadow private boolean field_71316_v;`
  `@Shadow private boolean field_71290_O;`; implements a small interface `HoshieStop` so S1 can read
  the state without reflection.
- `@WrapMethod(method = "func_71260_j()V")` handler `void hoshie$singleStop(Operation<Void> original)`
  (MixinExtras in UniMixins 0.3.1 has `com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod`;
  `Operation.call(Object...)`, checked with `javap`):
  ```
  if (!enabled) { original.call(); return; }
  boolean willSave = !field_71290_O && Loader.instance().hasReachedState(LoaderState.SERVER_STARTED) && !field_71316_v;
  if (!willSave) { original.call(); return; }          // same no-op path as today, no state change
  synchronized (lock) {
      while (state == RUNNING && owner != current && !giveUp && notTimedOut(T2)) lock.wait(250);
      if (state == DONE) return;                       // second call: no-op
      if (state == RUNNING && owner != current) return;// first one stuck: never run two saves at once
      if (state != RUNNING) { state = RUNNING; owner = current; }
  }
  boolean ok = false;
  try { original.call(); ok = true; }
  finally { synchronized (lock) { state = ok ? DONE : IDLE; owner = null; lock.notifyAll(); } }
  ```
  `state` is a field read by S1 under the same lock. On an exception the state goes back to IDLE so a
  later caller can retry **sequentially** (today it retries concurrently).
  `@WrapMethod` wraps the whole method including other mods' injections (SaveRelief's HEAD inject),
  per MixinExtras documentation (**nao verificado** here; confirm with the class export in 8.2 step 0).

**S3: `MixinDedicatedServerExit`** on `net.minecraft.server.dedicated.DedicatedServer`, `remap = false`:
`@Inject(method = "func_71240_o()V", at = @At("HEAD"))` sets a static volatile `exitReached = true`.
Handler never touches its `CallbackInfo` (no allocation; it runs once).

**S1: `MixinServerShutdownThread`** on `org.bukkit.craftbukkit.v1_7_R4.util.ServerShutdownThread`,
`remap = false`: `@Shadow @Final private MinecraftServer server;` and
`@Inject(method = "run()V", at = @At("HEAD"))` (not cancellable) handler:
```
if (!enabled) return;                          // original body runs as today
try {
  Thread main = server.primaryThread;
  if (main == null || !main.isAlive() || main == Thread.currentThread()) return;
  if (state == DONE || exitReached) return;     // normal "stop": server already saved and is in System.exit
  server.func_71263_m();                        // initiateShutdown(): serverRunning = false
  deadline = now + T1;
  while (!(exitReached || !main.isAlive()) && now < deadline) {
      sleep(50);
      if (state == RUNNING) deadline = max(deadline, startOfRunning + T2);   // saving: give it T2
  }
  if (timed out) { giveUp = true; log WARN with main's stack trace; }
} catch (Throwable t) { log; }
```
Then the original body runs: `server.stopServer()` (through S2: a no-op if the server thread already
saved; runs the save only if the server thread never got there, i.e. the original fallback), then
`reader.getTerminal().restore()`.

Why wait for `exitReached` and not just "stop done": after `stopServer()` the server thread still runs
`FMLCommonHandler.handleServerStopped()` (FMLServerStoppedEvent to mods, :770) before
`systemExitNow()`. Its `System.exit(0)` then blocks forever because hooks are running (the
`Runtime.exit` contract), which is harmless: the JVM halts when the hooks return. Proven with
`ExitProof.java` (below).

Config (`config/hoshiestar.cfg`, category `shutdown`):

| Option | Default | Meaning |
|---|---|---|
| `B:safeStopOnSignal` | `false` (decision 16: changes shutdown behaviour) | Register S1, S2, S3. `false`: none is registered; shutdown is exactly today's. Recommend `true` after test 8.2. |
| `I:stopLoopTimeoutSeconds` | `60` | Phase 1: how long the hook waits for the tick loop to end and the server thread to start saving. Longer than the 30 s stall seen in production. On timeout: fallback to today's behaviour. |
| `I:stopSaveTimeoutSeconds` | `600` | Phase 2: once the server thread is saving, how long the hook waits for it to finish. On timeout the JVM is allowed to halt (same as today's outcome if the save hangs). The server panel's own stop timeout must be at least this long, or it will SIGKILL first. |

Optional, not proposed now: a null check at `MinecraftServer.java:1016` (`worldTickTimes.get(id)`) and
:943 (`getWorld(id)`). With S1-S3 the removal only happens on the server thread; the null check would
only help the timeout fallback, and `func_71190_q` is crowded with other mods' injections. Better as
part of the upstream pull request.

---

## 6. Why it is safe

### 6.1 Chunk map: invariants and argument

Invariant M (structure, unchanged code): the bunch slot for `(x, z)` holds the chunk most recently
`put` at `(x, z)` and not `remove`d since (or null). Proven equivalent to a `HashMap` reference model
by `Diff.java` for the original class and the model.

Invariant C (cache): every non-null `slots[i]` is a chunk `c` with `slot(c.x, c.z) == i` that is
currently in the structure at `(c.x, c.z)`. Holds because:
1. it is written only in `get` with a value just read from the structure for exactly `(x, z)`
   (`slot(x, z) == i`, and the structure returns only a chunk stored at `(x, z)`, whose final
   coordinates equal `(x, z)` by the bijection of 2.3), or in `put` with the chunk just stored;
2. `put` at `(x, z)` overwrites the only slot that could hold a chunk of `(x, z)` (one position, one
   slot), so a replaced chunk cannot survive in the cache;
3. `remove` at `(x, z)` clears the slot if it holds a chunk of `(x, z)`; a chunk of another position
   in that slot is unaffected and still valid;
4. nothing else mutates the structure (`raw()` has no users; 2.2).
So a cache hit returns exactly what the structure returns, and a miss falls through to the structure:
**`get` always equals invariant M's answer.** Single-thread argument; the server calls it only from
the server thread today (chunk loads on the IO thread finish through `callStage2` on the main thread;
**nao verificado** for every async plugin, the same exposure as today).

Exact equivalence with today's code: identical results for every sequence of `put`/`remove`/`get`
**except** after a double `put` at one position followed by a `remove` (2.4), where today returns an
unloaded chunk and the new code returns null (what the map says). `size()`, `raw()`, `bulkCheck`,
iteration order (2.2) and exceptions (`remove(null)` NPE, `put(null)` ignored) are unchanged. The
`EmptyChunk(0,0)` case (CPS:236 when there is no generator) behaves the same: it is stored at (0,0)
and both versions return it only for (0,0).

Differential evidence (`Diff.java`, JDK 8, real `ChunkBlockHashMap` from `server.jar` vs the model in
`NewMap.java`, which is the E1 logic as a subclass): 6 runs x 3 million operations (15% put, 10%
remove including removes of never-added and of other objects at the same position, 75% get), with
70% of coordinates in -50..49, 20% near the world border (+-1 875 000) and 10% at `Integer.MIN_VALUE`/
`MAX_VALUE`, cache sizes 4, 5 and 6 bits, with and without duplicate puts:
```
no duplicate puts   bits=4..6  gets=~2 250 000 each  new!=ref=0  orig!=ref=0  size(orig)!=size(new)=0
WITH duplicate puts bits=4..6  gets=~2 250 000 each  new!=ref=0  orig!=ref=0  size(orig)!=size(new)=0
```
(the random runs never hit the narrow 2.4 window; `Stale.java` hits it on purpose).

Ways it could go wrong:
- **R1 - a mutation path that bypasses `put`/`remove`** (a future mod using `raw()` or reflection on
  the bunches). Today none exists (scan in 2.2). Mitigation: the scan is part of the test plan when
  mods change; `chunkMapVerify` detects it in place.
- **R2 - Crucible updates the class.** Fingerprint check refuses to apply (5.2).
- **R3 - the mixin is applied before deobfuscation** (descriptor with `apx`): it would fail to find
  the methods and, with `require = 1`, fail loudly at boot; with the fingerprint check it would simply
  not apply. Confirmed or refuted at the first boot (8.1 step 0).
- **R4 - memory**: 16 KB per world, about 1 MB for 60 worlds; the slots keep loaded chunks only
  (removed chunks are cleared), so no chunk is retained after unload. The dead MRU fields now hold
  nothing (today they can pin up to 4 chunks per world).
- **R5 - aliasing**: chunks 64 apart in x or z share a slot; they evict each other (a miss, never a
  wrong answer).
- **R6 - more threads later**: see 6.4. Not a risk today because the code is single-threaded like the
  original.

### 6.2 Shutdown: invariants and argument

Invariants:
- I1: worlds are removed (`setWorld(id, null)`) only by the server thread, after its tick loop has
  ended (S1 waits for that; the hook's own `stopServer()` becomes a no-op).
- I2: at most one save-and-unload body of `stopServer()` runs at any time, and once one finishes
  successfully no other runs (S2).
- I3: the hook never waits forever: phase 1 at most T1, phase 2 at most T2; after that, today's
  behaviour.
- I4: switch off = no mixin registered = today's bytecode.

Scenarios:
- `stop` command (today's safe path): the server thread saves, sets `exitReached`, calls `System.exit`;
  the hook sees DONE/`exitReached` and returns at once; S2 makes the hook's `stopServer()` a no-op
  (today it was already a no-op through `serverStopped`). Same outcome.
- SIGTERM while ticking with players: the hook clears `serverRunning`; the loop ends after the current
  tick; `handleServerStopping`, then `stopServer()` on the server thread (players saved and kicked,
  worlds saved, unload events, `setWorld(null)`), `handleServerStopped`, `System.exit` (blocks); the hook
  returns; JVM halts. No NPE at :1016, one save, plugins disabled once.
- SIGTERM during an autosave or a long tick: the loop ends when the tick ends (phase 1 is 60 s; the
  production stall was 30 s). If it does not, fallback to today's behaviour with a WARN and a stack.
- SIGTERM during boot: the hook clears `serverRunning`; the spawn-area preparation loop checks
  `isServerRunning()` (:472-474) and stops; if boot ends within T1, `run()` skips the loop and saves
  normally; otherwise the fallback runs `stopServer()`, which is a no-op before `SERVER_STARTED`
  (today's outcome).
- Crash in the tick loop: the server thread already ran `stopServer()` in its `finally` (DONE) before
  `System.exit`; hooks see DONE.
- Spigot restart / `Bukkit.shutdown()`: `CraftServer.shutdown` only calls `initiateShutdown()`
  (`CraftServer.java:1269`), the normal path.
- SaveRelief: `onServerStopping` runs once, on the server thread, before the final save (today twice,
  concurrently). Its own 60 s flush hook runs in parallel with ours, as today.

`ExitProof.java` (standalone, JDK 8 and 21) reproduces the exact JVM path: a "server thread" ticking,
another thread calling `System.exit(143)` (what SIGTERM triggers), a hook that clears the running flag
and waits; the server thread runs a 3 s "save" and then calls `System.exit(0)`:
```
server: loop ended after 11 ticks, stop done, calling System.exit
hook: waited 3051 ms, stopDone=true, stops=1; calling guarded stop()
hook: done, stops=1
exit code 143          (same on jdk21: waited 3047 ms, stops=1, exit code 143)
```
The process ends (no hang), the save ran once on the server thread, and the exit status stays 143
as for a normal SIGTERM.

Ways it could go wrong:
- **R7 - `serverRunning` is a plain field.** The loop re-reads it every iteration in practice (it
  calls `tick()` and `Thread.sleep`), and the `stop` command relies on the same field from other
  threads (`Bukkit.shutdown` from async tasks). Not guaranteed by the Java memory model
  (**nao verificado** formally); phase 1's timeout covers it.
- **R8 - the panel kills first.** If the hosting panel sends SIGKILL a few seconds after SIGTERM, the
  save is cut short, exactly as today. The admin must check the panel's stop timeout.
- **R9 - a mod's ServerStopping/ServerStopped handler hangs.** Phase 2 bound applies.
- **R10 - `@WrapMethod` ordering with SaveRelief's `@Inject`.** If SaveRelief's HEAD inject ended up
  outside our wrapper, a second (cancelled) call would still run `onServerStopping` once more; that is
  today's behaviour and harmless (it is idempotent: it only waits and drains). Checked in 8.2 step 0.
- **R11 - concurrency code.** Per project rule it must be reviewed by a different agent before merging.

### 6.3 What "switch off" restores

Both features are gated at registration: with the switch off the mixin is not in the list returned by
`getMixins`, so the target classes are exactly the jar's. Nothing is persisted by either feature (no
file, no NBT, no world data), so switching off after running with it on leaves nothing behind.

### 6.4 Base for the later per-thread cache (not done now, not made harder)

- The read path is four steps: (1) get the slot array, (2) probe it with one read into a local,
  (3) pure structure lookup (no writes), (4) fill. A per-thread version only changes step 1 (the array
  comes from the region thread's context object, cheaper than `ThreadLocal.get()`, as recommended in
  `estruturas-hostis.md` item H01); steps 2-4 write only thread-private memory.
- The probe reads the slot once and returns the same local whose `final` coordinates it checked, so
  even under a race it can never return a chunk of another position (today's `return last1` re-read can,
  H01). Final-field publication guarantees the coordinates are seen correctly provided `Chunk`'s
  constructor does not leak `this` (**nao verificado**).
- Invalidation across threads uses `hoshie$epoch` (incremented on each `remove`): a per-thread array
  stores the epoch it was filled at and clears itself when it differs. What remains for the parallel
  phases is the Koloboke map itself under concurrent `put` (H02), which this design does not change.

---

## 7. Expected gain

Upper bound: `ChunkBlockHashMap.get` = 3.63% of server-thread time (3.05 ms per tick). Not all of it
can go: a hit still costs two dependent loads (slot, then the chunk header, which the caller reads
right after anyway), and misses still pay the structure lookup.

Microbenchmark (`Bench.java`): one map with about 4 000 chunks (8 player areas of 21x21 plus 500
scattered), 2^22 precomputed lookups per pattern, 8 MB of memory swept between batches of 256 lookups
to imitate the rest of the tick evicting caches; median of 7 repetitions; separate JVM per variant.
`orig` = the real class, `new5`/`new6` = the model with 5 or 6 bits.

| Pattern | JDK 21 (G1) orig | new5 | new6 | new6 vs orig | JDK 8 (Parallel GC) orig | new6 |
|---|---:|---:|---:|---:|---:|---:|
| 1: same chunk 4x then a neighbour | 36.4 ns | 26.6 | 17.9 | -51% | 25.9 | 19.9 |
| 2: tile-entity-like, anchor +-1 chunk, 6 lookups | 49.9 | 28.7 | 19.8 | -60% | 41.2 | 21.6 |
| 3: uniform over all loaded chunks (worst case) | 62.4 | 47.8 | 40.5 | -35% | 51.3 | 37.6 |
| 4: pattern 2 with 20% misses on unloaded chunks | 48.5 | 36.9 | 29.7 | -39% | 43.3 | 30.5 |

Two control runs on JDK 21 (same harness, `Bench <impl> <pattern> <pollute>`):

| Run | orig | new6 |
|---|---:|---:|
| `-XX:+UseParallelGC`, pattern 2, with sweep | 41.2 ns | 21.4 |
| `-XX:+UseParallelGC`, pattern 3, with sweep | 50.8 | 43.1 |
| G1, pattern 2, **no** sweep (hot caches) | 35.3 | 5.7 |
| G1, pattern 3, **no** sweep | 36.7 | 12.8 |

The original is 9 to 12 ns slower per call under G1 than under Parallel GC while the new code barely
changes: that is the cost of the four MRU reference stores through G1's barriers, which the new code
pays once per miss instead of four times per non-MRU hit. With hot caches the new code is 3 to 6 times
faster; the swept runs above are the conservative figures.

Reading: on the production JVM (21, G1) the new lookup takes 35 to 60% less time per call; 6 bits beats
5 bits everywhere. The access mix and the hit rate of production are unknown, and the real per-world
maps are smaller than the single 4 000-chunk map of the benchmark (cheaper Koloboke probes, which
favours the original a little). So I take **25 to 60% of the 3.63%: 0.9 to 2.2 points, 0.8 to 1.8 ms
per tick** (of 84 ms). The lower end assumes production looks like the uniform pattern with extra
misses; the upper end like the tile-entity pattern. A real number needs the in-place measurement (8.1).

D-24: no performance gain; it removes a crash and a double concurrent save on every SIGTERM.

---

## 8. Test plan

### 8.1 Chunk map

Step 0, boot (test server, `scripts/run-test-server.sh`, stop with `stop` only): start with
`-Dmixin.debug.export=true` once; check the log line of the mixin plugin (fingerprint OK, E1 applied)
and that `.mixin.out/class/thermos/wrapper/ChunkBlockHashMap.class` exists; `javap -p -c` it and
check that `get` reads `hoshie$slots` and `field_76635_g`, and that no `apx` remains.

Step 1, standalone differential (done on the model; rerun on the real thing): take the transformed
class exported in step 0 and load it in the harness with a child-first class loader in place of the
jar's copy (Mixin itself is not needed in the harness), then run `Diff.java` and `Stale.java` against
it, original class on one side and transformed class on the other. Pass = `new!=ref=0` in all runs and
`Stale` returns null.

Step 2, differential in place (`B:chunkMapVerify=true`): with the protocol bot logged in as op, run
for about 10 minutes, using vanilla commands only:
- `/tp <bot> 0 100 0`, then `/tp <bot> -1000 100 -1000`, `/tp <bot> 1000 100 -1000`,
  `/tp <bot> -1000 100 1000` (generates and loads chunks in all four sign quadrants; chunk loads and
  unloads exercise `put`/`remove`);
- in each place: `/fill` is not in 1.7.10, so use repeated `/setblock ~X ~ ~Z minecraft:chest`
  at offsets that cross a chunk border and a bunch border (block x = -1, 0, -256, 255), then
  `/testforblock` on the same positions (each runs through `provideChunk` -> `get`);
- `/summon Pig ~ ~ ~` a few dozen times near a border (entity ticks read neighbour chunks);
- leave the area so the chunks unload (watch `/forge tps` or Crucible's chunk count), come back, and
  repeat `/testforblock` (cache invalidated on unload and refilled on reload).
Pass = verify counter 0 mismatches (`/hoshie status`), no "Chunk (..) stored at (..)" error from
`provideChunk` (CPS:327-333), blocks present after reload.

Step 3, before/after measurement: same world, same bot script, three runs each with
`chunkMapCache=false` and `true` (restart between), JFR as in the project rule
(`jcmd <pid> JFR.start duration=120s filename=...`), and with the 104 hoppers + 400 pigs setup used for
the medidor (DIAGNOSTICO / PROGRESSO 1.0.4 test) to create lookup load. Compare the JFR samples in
`ChunkBlockHashMap.get` and the mean tick time. On an empty world the absolute gain will be tiny; the
relative cost of `get` per sample is the useful number. Production confirmation: the admin's next
spark profile.

Step 4, standalone microbenchmark (done): `Bench.java` as in section 7; rerun with the real mixed
class (step 1 approach).

### 8.2 Shutdown

Step 0: boot with `safeStopOnSignal=true` and `-Dmixin.debug.export=true`; check that S1, S2, S3
applied and that SaveRelief's HEAD call sits inside our wrapper in the exported `MinecraftServer`.

Step 1 (no rule change needed): `stop` from the console, with the bot logged in. Pass = one
"Stopping server" line, one "Saving worlds", exit code 0, the bot's position saved.

Step 2, SIGTERM-equivalent without `kill`: a test-only console command in a debug build of the mod
(`hoshie testexit`, absent from the release jar) that starts a new thread calling `System.exit(143)`.
This is exactly the JVM path of SIGTERM (`Runtime.exit` -> hooks run while the server thread is
ticking). Run it (a) while ticking with the bot online and 400 pigs, (b) right after `/save-all`
(save in progress), (c) during boot is not reachable by command (see step 3). Pass = no crash report
in `crash-reports/`, no NPE at `MinecraftServer.java:1016`, one "Stopping server", worlds reopen with
the blocks placed just before, `region` files and `level.dat` intact (open the world again and
`/testforblock`).

Step 3, real SIGTERM (**needs the admin's waiver of "never kill"**, pending item 7b): `kill -TERM`
(a) while ticking with the bot, (b) during `/save-all`, (c) during boot at about 1 minute (mods
loading) and during spawn preparation. Pass as in step 2; for (c) the server either saves cleanly or
exits as today, never hangs past T1 + T2.

Step 4, control: steps 2-3 with `safeStopOnSignal=false` must reproduce D-07 (the NPE) at least once
in a few tries, to show the test can detect the bug.

---

## 9. Not verified

- That Mixin sees `thermos.wrapper.ChunkBlockHashMap` and `ServerShutdownThread` after FML's
  deobfuscation (SRG descriptors); inferred from the medidor, the Akashic mods and necrotempus.
- That `@WrapMethod` wraps SaveRelief's `@Inject` on `func_71260_j` (MixinExtras documentation, not
  checked here).
- The production hit rate of the cache and therefore the exact gain; the benchmark patterns are
  synthetic.
- How often the double `put` of 2.4 happens in production.
- That no async plugin calls `ChunkProviderServer` lookups off the main thread (same exposure as today).
- Final-field safe publication for `Chunk` (constructor does not leak `this`), relevant only for the
  later multi-thread phase.
- `serverRunning` visibility across threads under the Java memory model (works in practice, as the
  `stop` command already relies on it).
- The reading of spark's `samplerEngine: 1` as async-profiler.
- The patched `MinecraftServer` was read from the dev build of the same commit, not from the
  binpatched runtime class.
- The hosting panel's stop timeout in production.
