# 1.C Mekanism induction matrix energy reads: design report

Item: 1.C of Stage 1 ("Degrau 0"). Study and design only; no mod code was written.
Everything below was read from the real jars of the pack (decompiled with CFR, signatures checked with
`javap`) and from the production spark profile (via `tools/spark_report.py`). Anything I could not
check is marked **nao verificado**.

Short answer: **implement, with reservations.** The fix keeps the energy where Mekanism keeps it (in
the cells) and reads it live on every call. It only caches *which tile entity sits at each cell
coordinate*. It removes about 85-90% of the measured cost, so roughly 4.5 to 7.5 points of the
8.27%. A differential harness running the real Mekanism classes gave bitwise-identical results with
the fix on and off over 200 000 random operations. The one remaining risk is described in 6.3 (R1).

Paths used below:
- `$S` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad`
- `$J` = `$S/anexos/spark/wIBxG0D4y9.json` (production profile, 602 s, 7113 ticks, 8 players)
- decompiled sources: `$S/etapa1/decomp/C-mekanism/` (`mek/`, `mek2/`, `mek3/`, `throttle/`, `iu/`, `ae2/`, `raw/` = unzipped Mekanism classes)
- harness: `$S/etapa1/C-tools/harness/` (sources in `src/hs/`), caller-chain helper `$S/etapa1/C-tools/callers.py`

---

## 1. What the profile says

Base: the "Server thread" totals 598.9 s over 7113 ticks, so **1% = 0.842 ms per tick** (84.2 ms/tick).

Commands (run from `/home/user/Hoshiestar`):

```
python3 -I tools/spark_report.py $J busca SynchronizedMatrixData
python3 -I tools/spark_report.py $J busca SynchronizedMatrixData.getEnergy
python3 -I tools/spark_report.py $J busca SynchronizedMatrixData.setEnergy
python3 -I tools/spark_report.py $J arvore --raiz SynchronizedMatrixData --min 0.02 --prof 12
python3 -I tools/spark_report.py $J arvore --raiz EnergyNetLocal.onTickEnd --min 0.05 --prof 4
python3 -I tools/spark_report.py $J arvore --raiz TileEntityInductionPort.onUpdate --min 0.05 --prof 5
python3 -I tools/spark_report.py $J arvore --raiz TileEntityInductionCasing.onUpdate --min 0.05 --prof 5
python3 -I tools/spark_report.py $J arvore --raiz TileEntityQuantumEntangloporter.onUpdate --min 0.05 --prof 5
python3 -I tools/spark_report.py $J busca TileEntityInductionCasing.getEnergy
python3 -I tools/spark_report.py $J busca Coord4D.getTileEntity
python3 -I tools/spark_report.py $J eventos --top 50
# full caller chains: a 25-line helper that imports the reader's own Report/Thread classes
python3 $S/etapa1/C-tools/callers.py $J SynchronizedMatrixData 10
```

### 1.1 Totals (inclusive, % of server-thread time)

| Method | % | ms/tick |
|---|---:|---:|
| `SynchronizedMatrixData` (all methods) | **8.27** | 6.96 |
| `SynchronizedMatrixData.getEnergy` | 7.02 | 5.91 |
| `SynchronizedMatrixData.setEnergy` | 1.24 | 1.04 |
| `SynchronizedMatrixData.getInventory` | 0.00 | - |

Every `getEnergy`/`setEnergy` call comes through `TileEntityInductionCasing.getEnergy` (7.03%) or
`TileEntityInductionCasing.setEnergy` (1.24%).

### 1.2 Who calls it (one row per distinct top-level chain)

| Caller chain (abridged) | getEnergy % | setEnergy % |
|---|---:|---:|
| `WorldTickEvent` (post) → Uncomplication (inside IndustrialUpgrade) `EnergyNetGlobal.onTickEnd` → `EnergyNetLocal.onTickEnd` → `TileEntityInductionPort.getOfferedEnergy` → casing `getEnergy` | 2.24 | - |
| TE tick → `TileEntityInductionPort.onUpdate` → casing `getEnergy` (direct: the `prev` and "after" reads) | 2.05 | - |
| TE tick → `TileEntityInductionPort.onUpdate` → `CableUtils.emit` → casing `getEnergy` / `setEnergy` | 1.03 | 0.45 |
| TE tick → `TileEntityQuantumEntangloporter.onUpdate` → `CableUtils.emit/emit_do/emit_do_do` → `TileEntityInductionPort.transferEnergyToAcceptor` → casing `getEnergy` / `setEnergy` | 1.03 | 0.57 |
| TE tick → `TileEntityInductionCasing.onUpdate` → `ChargeUtils.discharge` → casing `getEnergy` / `setEnergy` | 0.68 | 0.22 |
| `TransmitterNetworkRegistry` → `EnergyNetwork.doEmit` → `MekanismAcceptor.needsEnergy` → casing `getEnergy` | 0.00 | - |
| **Total** | **7.03** | **1.24** |

Parent totals from `arvore`: `EnergyNetLocal.onTickEnd` 2.70%; `TileEntityInductionPort.onUpdate`
3.56%; `TileEntityInductionCasing.onUpdate` 1.33%; `TileEntityQuantumEntangloporter.onUpdate` 1.97%.
In `eventos`, the Uncomplication handler (`EventHandler_tick_WorldTickEvent`) is 2.72%, and 2.24 of
that is the matrix read.

The profile shows no comparators and no GUI packets (`getNetworkedData`) among the callers.

### 1.3 Where the time goes inside (same pattern in all 9 sub-trees)

Summed over the `getEnergy` sub-trees from the `arvore --raiz SynchronizedMatrixData` output:
- `Coord4D.getTileEntity` = 1.72 + 1.62 + 0.78 + 0.79 + 0.52 = **5.43%** (77% of getEnergy). Under it:
  - `World.func_147438_o` (getTileEntity) → `Chunk.func_150806_e` → `HashMap.get`/`getNode` with
    `ChunkPosition.equals`: 1.42% in the largest sub-tree;
  - `World.func_72964_e` → `ChunkProviderServer.func_73154_d` → `thermos.wrapper.ChunkBlockHashMap.get`,
    plus `Coord4D.exists` → `func_73149_a`: about 0.2% in the largest sub-tree.
- `HashSet`/`LinkedHashMap` iteration (`KeyIterator.next`, `HashSet.iterator`): about 0.6%.
- Self time of `getEnergy` (the `+=` loop): 0.3 + 0.3 + 0.1 + 0.1 + 0.1 ≈ 0.9%.
- The `setEnergy` sub-trees: `Coord4D.getTileEntity` 0.42 + 0.28 + 0.17 = 0.87% of the 1.24%.

So about 6.3 of the 8.27 points are spent turning a cell coordinate into a tile entity, again and
again, through hash maps.

### 1.4 How many calls per tick (estimate, **nao verificado**)

The profile has times, not call counts. `ChargeUtils.discharge` runs once per structure per tick
(only on the casing whose `isRendering` is true). Whenever it reaches `setEnergy`, which it does
here, it makes exactly 3 reads. Every item branch in the decompiled `ChargeUtils.discharge` has 3
reads. So if there is one matrix, one read costs about 0.68 / 3 = 0.23% (0.19 ms), and the matrix is
read about 7.02 / 0.23 ≈ **31 times per tick**. Sets cost about 5 more read-equivalents.

A 0.19 ms read suggests a matrix with a few thousand cells, given the ~50-65 ns per lookup measured
in the harness (section 7). This is an inference only. With several matrices there are fewer reads
per matrix.

---

## 2. The code

Source: `Mekanism-1.7.10-9.10.48-AkashicPerf.3-ALL.jar`, decompiled to `$S/etapa1/decomp/C-mekanism/mek*/`.
Descriptors come from `javap -p -s` on `$S/etapa1/decomp/C-mekanism/raw`.

### 2.1 Hot methods

`mekanism.common.content.matrix.SynchronizedMatrixData`
- field `cells : Ljava/util/Set;` (a `HashSet<Coord4D>`)
- `getEnergy (Lnet/minecraft/world/World;)D`
- `setEnergy (Lnet/minecraft/world/World;D)V`

```java
public double getEnergy(World world) {
    double ret = 0.0;
    for (Coord4D coord : this.cells) {
        TileEntity tile = coord.getTileEntity((IBlockAccess)world);
        if (!(tile instanceof TileEntityInductionCell)) continue;
        ret += ((TileEntityInductionCell)tile).getEnergy();
    }
    return ret;
}
public void setEnergy(World world, double energy) {
    for (Coord4D coord : this.cells) {
        TileEntity tile = coord.getTileEntity((IBlockAccess)world);
        if (!(tile instanceof TileEntityInductionCell)) continue;
        TileEntityInductionCell cell = (TileEntityInductionCell)tile;
        cell.setEnergy(0.0);
        double toAdd = Math.min(cell.getMaxEnergy(), energy);
        cell.setEnergy(toAdd);
        energy -= toAdd;
    }
}
```

The matrix itself stores no energy. **The energy lives in each `TileEntityInductionCell`** (field
`electricityStored : D`, saved in the cell's own NBT under `"electricityStored"`). `MatrixCache` saves
only the 2-slot inventory. Every read walks all cells, and every write zeroes and refills all cells
in iteration order (a greedy fill).

`mekanism.api.Coord4D.getTileEntity (Lnet/minecraft/world/IBlockAccess;)Lnet/minecraft/tileentity/TileEntity;`:
```java
if (world instanceof World && !this.exists((World)world)) return null;      // provider.chunkExists
return world.func_147438_o(this.xCoord, this.yCoord, this.zCoord);           // World.getTileEntity
```
In Crucible, `World.getTileEntity` (cauldron `World.java:3149`) scans `addedTileEntityList` while the TE
loop is running, then calls `getChunkFromChunkCoords` → `Chunk.func_150806_e`. That method builds a
new `ChunkPosition` and does a `HashMap` lookup (`Chunk.java:932`). So each cell costs two
chunk-provider lookups, one object allocation and one hash lookup.

`TileEntityInductionCell` (no subclasses in the pack): `getEnergy ()D` returns `electricityStored`;
`setEnergy (D)V` is `electricityStored = Math.min(energy, getMaxEnergy())`; `getMaxEnergy ()D` returns
`tier.maxEnergy`; `canUpdate()` is false (cells never tick).

### 2.2 Callers and reads per call

`TileEntityInductionCasing`: `getEnergy ()D` returns `structure.getEnergy(worldObj)` on the server.
`setEnergy (D)V` calls `structure.setEnergy(worldObj, clamp(energy, 0, storageCap))` and then
`MekanismUtils.saveChunk(this)`. Bytecode search: the only callers of `SynchronizedMatrixData.getEnergy/setEnergy`
are `TileEntityInductionCasing.getEnergy`, `.getNetworkedData` and `.setEnergy`.

Per tick, from the decompiled callers:
- `TileEntityInductionPort.onUpdate` (output mode): `prev = getEnergy()`, then `CableUtils.emit(this)`.
  `emit` reads once (`min(getEnergy, getMaxOutput)`) and, if it has sides, reads again and writes
  (`setEnergy(getEnergy()-sent)`). Then `onUpdate` reads once more. That is **3-4 full reads + 0-1 full
  writes per output port per tick**, even when nothing is accepted. The profile shows no `emit_do`
  under the ports, so they push into nothing.
- Uncomplication `EnergyNetLocal.onTickEnd` (`iu/.../EnergyNetLocal.java:482-507`): for every IC2
  source, `getOfferedEnergy()` once, then again in the loop condition: **2 full reads per port per
  tick**, in either mode. (`getOfferedEnergy` = `min(getEnergy(), getMaxOutput()) * TO_IC2`; ports
  register as IC2 sources whatever their mode.)
- Entangloporter → `TileEntityInductionPort.transferEnergyToAcceptor`: `getMaxEnergy()-getEnergy()`,
  then `setEnergy(getEnergy()+toUse)`: **2 reads + 1 write per accepted push** (the emit loop retries
  up to 100 times).
- `TileEntityInductionCasing.onUpdate` (the one rendering casing): `ChargeUtils.charge(0)` (slot empty
  here) and `ChargeUtils.discharge(1)`: **3 reads + 1 write per tick** while an item sits in the slot.

---

## 3. Root cause

The induction matrix keeps no total. Every `getEnergy` and `setEnergy` resolves every cell coordinate
to its tile entity through `Coord4D.getTileEntity` (two chunk-map lookups and a chunk TE `HashMap`
lookup per cell). Ports, the entangloporter, the casing's charge slot and Uncomplication's IC2 network
together ask the matrix about 30 times per tick (estimate). So the server repeats the same thousands
of coordinate lookups each tick, and the answer almost never changes within a tick.

---

## 4. What the in-house mods already do

- **Mekanism `9.10.48-AkashicPerf.3`**: the only "Akashic" strings are the version string in
  `Mekanism.class`, `MekanismGenerators.class` and `MekanismTools.class`. The matrix classes
  (`SynchronizedMatrixData`, `MatrixCache`, `MatrixUpdateProtocol`) have the same timestamp as the
  other 2 860 entries, and their logic is the plain per-cell loop shown above. I recall that upstream
  Mekanism 9 for 1.7.10 has this same loop, but I had no upstream jar to diff (**nao verificado**).
  Either way, **nothing in the matrix energy path was changed**.
- **`Akashic-MekThrottle-1.1.0`** (coremod, ASM, `$S/etapa1/decomp/C-mekanism/throttle/`) applies 5 patches,
  each with a `-Dakashic.mekthrottle.*` system-property switch:
  1. recipe cache in `TileEntityFormulaicAssemblicator`;
  2. `CommonWorldTickHandler.tickEnd`: `MultiblockManager.tick` / `FrequencyManager.tick` run every N ticks (default 20);
  3. `MultiblockManager.pullInventory` guard;
  4. `TileEntityBasicBlock.func_145845_h`: `destroyDisabledBlocks` check every 20 ticks;
  5. `TileEntityMultiblock.sendPacketToRenderer` / `onUpdate`: render-packet limiter. This indirectly
     reduces `getNetworkedData` → `getEnergy` for GUI and render packets.
  **None of them touches `SynchronizedMatrixData`, the casing or the port energy methods.**
- **Other mods**: scanning every jar in `mods/` for references to `content/matrix`, `SynchronizedMatrix`
  or `TileEntityInduction` found only Mekanism itself. The only other hit was IC2's unrelated induction
  furnace. No transformer or mixin targets the classes this design touches.

---

## 5. Proposed fix

### 5.1 Idea

Do **not** cache energy. Cache only the resolution "cell coordinate → `TileEntityInductionCell` object".
Each call checks cheaply that the cached resolution is still exactly what the original lookup would
return, then reads `getEnergy()` live from each cached cell object in the original iteration order.
The result is the same double, bit for bit, at about 1/10 to 1/20 of the cost. `setEnergy` gets the
same treatment: the same cells, the same order, the same arithmetic.

This differs from the "per-tick cached total" in the plan. A cached total would need an invalidation
on every write path, including direct writes to the public `electricityStored` field, chunk unloads and
cell removal, and a stale total is exactly how energy gets duplicated. Here a stale cache can only go
wrong if a tile entity disappears from the chunk without being invalidated (6.3 R1). Values are never
stale.

### 5.2 Mixin

One late mixin (it targets a class of another mod: `@LateMixin` + `ILateMixinLoader`, as the pack does).
- `mixins.hoshiestar.late.json`, `"minVersion": "0.8.5-GTNH"`, no refmap, `"required": false`.
- Loader: `ILateMixinLoader.getMixinConfig ()Ljava/lang/String;` and
  `getMixins (Ljava/util/Set;)Ljava/util/List;` (javap on `+unimixins-all-1.7.10-0.3.1.jar`). Return
  `"mekanism.MixinSynchronizedMatrixData"` only if the set contains `"Mekanism"` (modid from the
  jar's `mcmod.info`) and `matrixEnergyFastPath=true` at startup.
- Class: `@Mixin(value = SynchronizedMatrixData.class, remap = false)`.

**Target class**: `mekanism.common.content.matrix.SynchronizedMatrixData` (javap: `public class ... extends
mekanism.common.multiblock.SynchronizedData<...>`).

**@Unique state fields** (transient, per structure object, never saved):
`World hs$world; Set hs$set; int hs$size; long hs$builtAt; boolean hs$complete;`
`TileEntityInductionCell[] hs$refs; int[] hs$x, hs$y, hs$z; Chunk[] hs$chunks; int[] hs$cx, hs$cz;`
These do not affect `SynchronizedData.equals/hashCode`, which only use `locations` and the volume
fields (decompiled `SynchronizedData.java`).

**Injection 1: read**
- method `getEnergy(Lnet/minecraft/world/World;)D`
- `@Inject(method = "getEnergy(Lnet/minecraft/world/World;)D", at = @At("HEAD"), cancellable = true, remap = false)`
- handler `private void hs$getEnergy(World world, CallbackInfoReturnable<Double> cir)` (`setReturnValue (Ljava/lang/Object;)V`, javap)
- Handler steps:
  1. Return without doing anything (the original runs) if: the switch is off; `world == null`;
     `world.field_72995_K` (isRemote); or the current thread is not the server thread (captured at
     `cpw.mods.fml.common.gameevent.TickEvent$ServerTickEvent` START, javap on `server.jar`; while it
     is still `null`, always fall back).
  2. **Valid?** All of these must hold:
     - `hs$refs != null`, `hs$world == world`, `hs$set == this.cells`, `hs$size == this.cells.size()`;
     - `world.func_82737_E() - hs$builtAt < matrixRebuildIntervalTicks` (default 1: rebuild on the first read of each tick);
     - for each chunk group j (at most 4: the interior is at most 16x16 cells, so at most 2 chunks per
       axis): `hs$chunks[j].field_76636_d` (isChunkLoaded), `world.func_72863_F().func_73149_a(cx, cz)`
       (the same chunkExists the original calls) and `world.func_72964_e(cx, cz) == hs$chunks[j]`.
  3. If valid and `hs$complete`, sum over `i` in array order. For each `c = hs$refs[i]`: if
     `c.func_145837_r()` (isInvalid), or `c.field_145851_c/field_145848_d/field_145849_e` differ from
     `hs$x/y/z[i]`, or `c.func_145831_w() != world`, abort and rebuild. Otherwise `ret += c.getEnergy()`.
     Starting from `0.0`, `cir.setReturnValue(ret)`.
  4. If valid but not complete: return and let the original run (this structure uses the original path
     until the next rebuild).
  5. Otherwise **rebuild**: run the original loop exactly. For each `coord` of `this.cells` in order:
     `tile = coord.getTileEntity(world)` (the same call, so the same side effects); if it is a
     `TileEntityInductionCell`, do `ret += cell.getEnergy()` and store the ref, its coordinates and its
     chunk `world.func_72964_e(x>>4, z>>4)`. Otherwise mark `complete=false`. Also mark
     `complete=false` for any coordinate with `y < 0 || y >= 256` (AkashicBellow deep worlds patch
     `World.getTileEntity`'s y check). Store `world`, `this.cells`, its size and
     `builtAt = world.func_82737_E()`, then `cir.setReturnValue(ret)`. A rebuild costs exactly one
     original read.
  6. The whole body sits in `try/catch (Throwable)`. On any error, log once, turn the feature off
     globally and return without cancelling (the original runs).

**Injection 2: write**
- method `setEnergy(Lnet/minecraft/world/World;D)V`
- `@Inject(method = "setEnergy(Lnet/minecraft/world/World;D)V", at = @At("HEAD"), cancellable = true, remap = false)`
- handler `private void hs$setEnergy(World world, double energy, CallbackInfo ci)`
- Handler steps:
  1. Same guards as step 1 above, plus `matrixSetEnergyFastPath`.
  2. Only if the state is valid and complete, run the per-ref checks for **all** refs before writing
     anything. Any failure: return and let the original run (no rebuild here).
  3. Then, for each ref in order, run the original body unchanged:
     `cell.setEnergy(0.0); toAdd = Math.min(cell.getMaxEnergy(), energy); cell.setEnergy(toAdd); energy -= toAdd;`
     Then `ci.cancel()`.
  4. The casing's `MekanismUtils.saveChunk(this)` is outside this method and still runs.

**Every member the handlers use, with where it was checked**:

| Member | Descriptor | Checked in |
|---|---|---|
| `SynchronizedMatrixData.cells` | `Ljava/util/Set;` | javap, Mekanism jar |
| `SynchronizedMatrixData.getEnergy` / `setEnergy` | `(Lnet/minecraft/world/World;)D` / `(Lnet/minecraft/world/World;D)V` | javap, Mekanism jar |
| `Coord4D.getTileEntity` | `(Lnet/minecraft/world/IBlockAccess;)Lnet/minecraft/tileentity/TileEntity;` | javap, Mekanism jar |
| `Coord4D.xCoord/yCoord/zCoord` | `I` | javap, Mekanism jar |
| `TileEntityInductionCell.getEnergy/setEnergy/getMaxEnergy` | `()D` / `(D)V` / `()D` | javap, Mekanism jar |
| `TileEntity.func_145837_r` (isInvalid) | `()Z` | javap `$S/medidor-work/srg.jar` (vanilla in SRG names) + Crucible `TileEntity.java:203` |
| `TileEntity.func_145831_w` (getWorldObj) | `()Lnet/minecraft/world/World;` | same + `TileEntity.java:64` |
| `TileEntity.field_145851_c/_145848_d/_145849_e` | `I` | javap srg.jar |
| `World.field_72995_K` (isRemote) | `Z` | javap srg.jar + Crucible `World.java:159` |
| `World.func_82737_E` (getTotalWorldTime) | `()J` | javap srg.jar + `World.java:4358` |
| `World.func_72863_F` (getChunkProvider) | `()Lnet/minecraft/world/chunk/IChunkProvider;` | javap srg.jar + `World.java:4419` |
| `World.func_72964_e` (getChunkFromChunkCoords) | `(II)Lnet/minecraft/world/chunk/Chunk;` | javap srg.jar + `World.java:777` |
| `IChunkProvider.func_73149_a` (chunkExists) | `(II)Z` | javap srg.jar + `ChunkProviderServer.java:98` |
| `Chunk.field_76636_d` (isChunkLoaded) | `Z` | javap srg.jar + `Chunk.java:54` |

The srg.jar is vanilla, unpatched. Crucible's binary patches keep these members (cauldron sources
above), and `WorldServer` does not override `getTileEntity`.

### 5.3 Config switches (`config/hoshiestar.cfg`, category `mekanism`)

| Name | Default | Meaning |
|---|---|---|
| `B:matrixEnergyFastPath` | `true` | Master switch. `false` at startup: the loader does not register the mixin, so the Mekanism bytecode is exactly the jar's. `false` at runtime (reload): both handlers return at their first instruction without cancelling, so the original bodies run unchanged and the leftover state is ignored. |
| `B:matrixSetEnergyFastPath` | `true` | Use the cached refs for `setEnergy` too. `false`: `setEnergy` always runs the original. |
| `I:matrixRebuildIntervalTicks` | `1` (1..20) | Redo the resolution from scratch at least every N ticks, whatever the validation says. 1 limits the R1 window to a single tick. |
| `B:matrixVerify` | `false` | Test only: on every fast read, also run the original resolution and compare bitwise. On a mismatch, log the coordinates and both values, count it and use the original result. Costs as much as the original. |
| `I:matrixReportSeconds` | `300` | Log counters: fast reads, rebuilds, fallbacks, fast/slow writes, verify mismatches. |

Plus a small op-only debug command, needed by the test plan. It is part of the mod, not this design's
core: `/hoshiestar mek energy <x> <y> <z>`. For any `IStrictEnergyStorage` tile it prints `getEnergy()`.
For an induction casing it also prints the raw sum of `electricityStored` over `structure.cells`
(resolved with `Coord4D.getTileEntity`, which does not depend on the fast path), the cell count, the
`complete` flag and the counters.

---

## 6. Why it is safe

### 6.1 Invariants

**I1. Same tile entities, same order, so the read result is identical.** The original sums
`getEnergy()` of whatever `Coord4D.getTileEntity` returns for each coordinate, in `HashSet` iteration
order. The fast path sums the same objects in the same order, with the same `0.0` start and the same
`+=`. So the doubles are bitwise equal, provided every cached ref is still what the original lookup
would return. That holds because:
- (a) **The set is the same object and is never mutated**, so the iteration order cannot change.
  Bytecode search: `cells` is written in the constructor and only `add`-ed in
  `MatrixUpdateProtocol.canForm`, on a freshly created structure (`UpdateProtocol.java:148`).
- (b) **Complete**: at rebuild, every coordinate resolved to a cell. Coordinates that resolve to
  nothing could later receive a new cell that the original would count, so then the fast path is never
  used.
- (c) **Same chunk**: the cached Chunk is still flagged loaded, the provider still reports the chunk
  (the original's own `exists()` check), and `getChunkFromChunkCoords` returns the same object. So the
  original lookup would go through that same chunk's TE map.
- (d) **The ref is still the map entry**: every standard way a TE leaves or is replaced in a chunk map
  invalidates it or unloads the chunk:
  - `Chunk.func_150812_a` invalidates the previous TE before `put` (`Chunk.java:998-1003`);
  - `Chunk.removeTileEntity` invalidates (`Chunk.java:1018-1031`), and `World.removeTileEntity`
    calls it (`World.java:3253-3276`);
  - chunk unload sets `isChunkLoaded=false` (`Chunk.java:1053-1055`).

  The fast path rejects `isInvalid()`, changed coordinates, a changed world, unloaded or replaced
  chunks. Once (c) and (d) pass, `Chunk.func_150806_e` would return exactly our ref: it is not
  invalid, so the original would neither remove it nor create a new TE. Crucible's
  `addedTileEntityList` lookup could only return a different TE at that position after a replacement,
  and a replacement invalidates our ref.
- (e) **Values are read live** (`getEnergy()` on the real cell objects). Any write by anyone, at any
  time, through any path (including a direct write to the public `electricityStored`), shows up in the
  next read. So there is nothing to invalidate on writes, and several readers in the same tick (ports,
  Uncomplication, entangloporter, GUI packets, OpenComputers) see exactly what the original would show.

**I2. Same writes.** The fast `setEnergy` checks all refs before the first write. It then runs the
original loop body on exactly the objects the original would find, in the same order.
`TileEntityInductionCell.setEnergy` only assigns a field (decompiled), so no callback can run halfway
through and nothing is left half-written. Total and per-cell distribution are identical.

**I3. Nothing persisted.** The state lives in `@Unique` fields of a runtime object. Saving still
writes each cell's `electricityStored` (cell NBT) and the matrix inventory (`MatrixCache`), unchanged.
After a load, the TE objects are new and the state rebuilds.

**I4. Form/break moves no energy.** Energy sits in the cells, not in the structure. A formation creates
a new `SynchronizedMatrixData` (fresh, empty state). A break sets `structure = null` on the casings
(`UpdateProtocol.java:345-357`), so the handlers stop being called. An old structure object that is
still referenced validates against the live cells like any other.

**I5. Threads.** Only the server thread may build or use the state. Other threads (for example an
OpenComputers call through `OCDriver` → `invoke` → `getEnergy`, whose thread is **nao verificado**)
always run the original and never touch the state, so there are no data races on our fields.

**I6. Client.** `isRemote` always takes the original. The mod is server-only anyway.

### 6.2 The cases the task asked about

| Case | What happens |
|---|---|
| Inject X / extract Y (read-then-write in the same tick) | Values live (I1e), writes identical (I2). Harness: 0 differences. |
| Multiblock break / re-form | New structure object, fresh state (I4). Harness op 12: 0 differences. |
| Cell broken or placed inside a formed matrix | The broken TE is invalidated (I1d), which fails validation; rebuild sees it missing, so the structure is incomplete and uses the original path until it re-forms. Harness ops 8/9 and scenarios `break`/`replace` in the same tick: identical. |
| Chunk unload (matrix across a chunk border) | `isChunkLoaded=false` / `chunkExists` false fails validation; rebuild sees missing cells, so incomplete and original path. Reload makes new Chunk and TE objects, which fail validation, so rebuild. Harness ops 10/11 and scenario `unload` in the same tick: identical. |
| World save / load, server restart | I3. |
| Read from several sides in one tick | I1e: nothing cached but the resolution. |
| AE2 Spatial IO moving the matrix | `appeng/spatial/CachedPlane` removes TEs from `field_150816_i` **without** invalidating (line 111), then re-adds them in the storage world with `func_145834_a` (setWorldObj) + new coordinates (lines 211-215). The world/coordinate check catches this. The gap between removal and re-add sits inside one AE2 call, with no Mekanism code in between (**nao verificado** beyond reading the code). |

### 6.3 Every way it could go wrong

- **R1 (the real one).** A mod removes an inner cell's TE from the chunk map *without* `invalidate()`
  and leaves it at the same world and coordinates. In the same tick, someone then reads and writes the
  matrix. The fast path would still count that orphan cell. With the default
  `matrixRebuildIntervalTicks=1`, the window closes at the next tick boundary. Inside it, energy can be
  duplicated or lost (harness scenario `raw`: the read differs and the state diverges in the same tick;
  with a tick in between it is identical; with an interval of 10^9 it diverged for good in 1 of 3
  random runs).
  - Mods in the pack that reference `Chunk.field_150816_i`: hodgepodge, RotaryCraft, Akashic pylon
    chunkguard, gadomancy, IC2, chunkapi, Mekanism, CoFH, AE2 (spatial, see above),
    immersivepetroleum, IE, MrTJPCore, DragonAPI, ExtraUtilities, AkashicBellow, CodeChickenCore,
    OpenComputers, Galacticraft, AgriCraft, chromatifixes, MPS, IndustrialUpgrade, witchery,
    magicbees, akashicfm, ATM-Family, ForgeMultipart.
  - Most of them only read the map (**nao verificado** for each one). Cells are enclosed by the frame,
    so a player cannot target one directly.
  - Mitigations: interval 1 (default), the `matrixVerify` run on the test server, and the switch.
- **R2.** The thread capture fails (the event never fires on that thread). Effect: the fast path never
  activates. This fails safe and the counters show it.
- **R3.** Deep worlds (AkashicBellow, y<0): excluded, original path.
- **R4.** Crucible's `worldChangeHolder` capture: `World.getTileEntity` returns captured tiles during
  sapling growth and `CraftWorld.generateTree` (the only two `startCapture` callers in the Crucible
  sources). A matrix read during a tree capture would differ. Not a TE-tick context. An optional guard
  through reflection is possible (the field is Crucible-only and binpatched, so no javap).
- **R5.** Memory: up to 4096 refs, 7 small arrays and up to 4 Chunk refs per live structure. Old TE
  objects stay reachable until the structure object is collected (KB scale).
- **R6.** The mixin fails to apply (if Mekanism is ever updated): the config is not required, so the
  original code runs and a warning is logged.
- **R7.** A bug in our handler: wrapped in `try/catch`, it falls back to the original and turns the
  feature off. `setEnergy` validates before writing, so an exception cannot leave half-written cells.
- **R8.** One `Double` box per read (`CallbackInfoReturnable`): negligible next to what it replaces.
- **Not a risk: behaviour visible to players.** None. Same totals, same per-cell distribution, same
  GUI, comparator, OpenComputers and IC2 numbers.

---

## 7. Expected gain

- **Upper bound: 8.27% of the server thread** (6.96 ms of 84.2 ms per tick), all `SynchronizedMatrixData` time.
- Harness timing (`hs.Main bench`, fake world with HashMap TE lookups, so not Crucible; only the ratio matters):

  | cells | original get | fast get | original set | fast set |
  |---:|---:|---:|---:|---:|
  | 64 | 1.9-2.3 µs | 0.09-0.25 µs | 1.8-2.2 µs | 0.26-0.34 µs |
  | 512 | 17-20 µs | 1.0-2.7 µs | 17-19 µs | 2.3-3.8 µs |
  | 4096 | 260-266 µs | 11-31 µs | 296-372 µs | 20-39 µs |

  A fast call costs 4-12% of an original one. In Crucible each original lookup is heavier
  (`ChunkPosition` allocation, `ChunkBlockHashMap`), so the real ratio is probably better.
- With the default interval of 1, per structure per tick there is 1 rebuild (one original read), then
  fast reads and fast writes. Remaining share = (1 + (G-1)·f + S·f) / (G + S), with G reads and S
  write-equivalents per tick, and f ≈ 0.05-0.15:
  - one matrix, G≈31, S≈5 (section 1.4): about 10-13% remains, so **about 7.2-7.5 points saved** (≈6 ms/tick);
  - several matrices with G≈8 each: about 20-25% remains, so about 6.2-6.6 points;
  - pessimistic, G≈3: about 45% remains, so about 4.5 points.
- **Range: 4.5 to 7.5 points (3.8 to 6.3 ms per tick); most likely about 7.** On its own, 84.2 ms
  would become roughly 78-80 ms, about 12.5-12.8 TPS at the same load. With
  `matrixRebuildIntervalTicks=20` the gain would rise to about 7.5-7.9 points, but the R1 window grows
  to 20 ticks. Not recommended without a reason.
- Not touched: the Uncomplication loop's own overhead (0.46% outside the matrix read) and the emit
  logic.

---

## 8. Test plan

### 8.1 Standalone harness (already run, reproducible)

Sources `$S/etapa1/C-tools/harness/src/hs/`:
- `FakeWorld`: a `World` subclass allocated with `Unsafe`. Its `func_147438_o`, `func_72964_e`,
  `func_72863_F` and `func_82737_E` mirror Crucible's lookup path.
- `FakeProvider`: the chunk provider.
- `Fast`: prototype of exactly the handlers in 5.2.
- `Main`: differential driver.

It runs the **real** `SynchronizedMatrixData.getEnergy/setEnergy`, `Coord4D.getTileEntity` and
`TileEntityInductionCell` from the pack's Mekanism jar.

Universe A always uses the original code. Universe B routes the same calls through `Fast`, falling
back to the original when it declines. Both get the same random sequence of operations:
- inject (read, then `set(read + x)` with the casing's clamp);
- extract;
- read;
- tick;
- break a cell (invalidate; the energy goes to a "dropped" ledger like `BlockBasic.getPickBlock`);
- place a cell from the ledger;
- unload a chunk (cells to "disk", `isChunkLoaded=false`);
- reload it (new Chunk and TE objects from "NBT");
- re-form (new structure, like `canForm`);
- optionally, a non-standard raw removal.

After every operation, every cell's energy (loaded and on disk) plus the ledger must be **bitwise
equal** in A and B, and `Fast`'s read must equal the original read on B.

```
cd $S/etapa1
CP="$S/etapa1/C-tools/harness/classes:$S/etapa1/decomp/C-mekanism/raw:$S/medidor-work/srg.jar:$S/server-test3/mods/industrialcraft-2-2.2.828a-experimental.jar:$S/server-test3/libraries/net/minecraft/server/1.7.10/server-1.7.10.jar:$S/server-test3/server.jar"
J="env -u _JAVA_OPTIONS -u JAVA_TOOL_OPTIONS /usr/lib/jvm/java-8-openjdk-amd64/bin/java -cp $CP hs.Main"
$J diff <seed> 20000 8 std 1            # standard events, rebuild every tick
$J diff <seed> 20000 8 std 1000000000   # standard events, validation only (no periodic rebuild)
$J diff <seed> 20000 8 raw 1            # includes the non-standard raw removal (R1)
$J scenario break|unload|replace|raw [tick]
$J bench 4|8|16 <calls>
```

Results obtained:
- `std`, seeds 1-5, interval 1 and interval 10^9 (10 runs × 20 000 ops, 512 cells): **0 state
  differences, 0 fast≠original reads**. The fast path served 1 072-1 858 reads and 321-657 writes per
  run (the rest declined because a cell was broken and the matrix had not re-formed, or a chunk was
  unloaded).
- `scenario break/unload/replace`, in the same tick and with a tick in between: identical.
- `raw`: same-tick scenario: the read differs (3.989666e12 vs 3.989174e12) and the state diverges.
  With a tick in between: identical. Random `raw` runs: stale reads occurred (94-296 per run); the
  state diverged for good only with interval 10^9 (seed 2). This is R1, demonstrated.

What the harness does not cover: Crucible's real `World`/`Chunk`/`ChunkProviderServer` code, mixin
application, threads, AE2, the 292-mod interaction. That is what 8.2 is for.

### 8.2 On the 292-mod test server (bot logged in as op; stop only with `echo stop >> console.in`)

Blocks (registry names from `MekanismBlocks`: `GameRegistry.registerBlock(BasicBlock2, ..., "BasicBlock2")`,
modid `Mekanism`). Metadata: `BasicBlock2` meta 1 = induction casing (`MatrixUpdateProtocol.isValidFrame`),
meta 2 = port, meta 3 = cell, meta 4 = provider. NBT read by `/setblock ... replace {dataTag}` (vanilla
`CommandSetBlock` calls `readFromNBT` on the new TE):
- cell: `{tier:<0..3>,electricityStored:<double>d}`;
- provider: `{tier:<0..3>}`;
- port: `{mode:1b}` = output.

The exact registry string accepted by `/setblock` (`Mekanism:BasicBlock2`) is **nao verificado** on the
test server; check it with one command first.

**Step A: small matrix across a chunk corner** (7×7×7 frame at x=1004..1010, y=100..106, z=1004..1010,
which touches chunks 62/63 on both axes):
1. The frame: the 218 shell blocks with `/setblock X Y Z Mekanism:BasicBlock2 1`. Then replace two
   face blocks:
   - `/setblock 1004 103 1007 Mekanism:BasicBlock2 2 replace {mode:1b}` (output port);
   - `/setblock 1010 103 1007 Mekanism:BasicBlock2 2` (input port).
2. The interior, 125 positions:
   - one provider: `/setblock 1007 103 1007 Mekanism:BasicBlock2 4 replace {tier:3}`;
   - 124 cells: `/setblock X Y Z Mekanism:BasicBlock2 3 replace {tier:0,electricityStored:4.0E8d}`.
   - Known start total: 124 × 4.0e8 = 4.96e10 J.
3. A finite sink outside the output port: an energy cube at 1003 103 1007 (`Mekanism:EnergyCube`).
   Its tier comes from the NBT string `"tier"` (`Tier.EnergyCubeTier.getFromName`), and its energy
   from `electricityStored`, as in `TileEntityElectricBlock`. The exact tier name string and which
   faces of the cube accept energy are **nao verificado**: read `BlockEnergyCube`/`TileEntityEnergyCube`
   before the test. If the cube is awkward, leave the output port facing air: the profiled pattern was
   ports pushing into nothing, and the matrix is still read 3-4 times per port per tick.
4. Wait a few seconds for formation (casing `ticker==5`; MekThrottle runs `MultiblockManager.tick`
   every 20 ticks), then `/hoshiestar mek energy 1004 103 1007`: it must show complete=true and
   cells=124.

**Step B: differential in situ** (`matrixEnergyFastPath=true`, `matrixVerify=true`):
1. Let it run 10 minutes. IC2 is on (`B:BlacklistIC2Power=false` in `config/mekanism/Mekanism.cfg`), so
   Uncomplication polls both ports.
2. Then, by command, one event at a time, each followed by 1 minute of running:
   - break a cell: read it first with the debug command, then `/setblock X Y Z minecraft:air`;
   - place a cell back with a dataTag;
   - break and re-place a casing block, which forces a re-form;
   - teleport the bot more than 20 chunks away for 2 minutes and back (chunk unload/reload; the
     mid-test chunk state is **nao verificado**: check with the debug command that complete=false
     while away);
   - `save-all`;
   - `stop` and start the server again.
3. **Pass**: the verify-mismatch counter is 0 in the report lines, there is no Hoshiestar error in
   `console.log`, and the fast-read counter is > 0. If it stays 0, the thread capture failed (R2).

**Step C: conservation, fix ON vs OFF** (two fresh copies of the world, same commands):
1. Ledger: `E = Σcells_raw + cube_energy + Σ(energy read from each cell just before it was set to air)`.
2. Take E at T0 (formed), T1 (+2 min transfer), T2 (after break/place), T3 (after unload/reload),
   T4 (after save + restart).
3. **Pass**: in both runs E(Tn) = E(T0) within a relative 1e-9 (floating-point rounding in the
   original transfer code is the only allowed drift), and the ON run shows 0 verify mismatches. Raw
   per-cell distributions are *not* compared between runs, because transfer timing differs. That
   comparison is what the harness's A/B equality covers.

**Step D: before/after cost**:
1. Build a large matrix: 18×18×18 frame, 16×16×16 = 4095 cells + 1 provider, by `/setblock` from the
   bot (~5 400 commands). Most cells `{tier:0}` with no energy, 50 cells with
   `electricityStored:4.0E8d` (so the IC2 offer is > 0). 4 output ports `{mode:1b}` facing air,
   1 input port.
2. JFR: `jcmd <pid> JFR.start name=mek settings=profile filename=$S/etapa1/jfr/mek-off.jfr duration=120s`
   with `matrixEnergyFastPath=false`. Then the same with `true`, either after a reload command (if the
   mod gets one) or after a restart and re-measure.
3. Compare the share of server-thread `jdk.ExecutionSample` stacks that contain
   `SynchronizedMatrixData.getEnergy`/`setEnergy` (`jfr print --events jdk.ExecutionSample ... | grep`),
   the mod counters (rebuilds per tick ≈ 1 per structure, fast reads per tick) and MSPT (Medidor or `/forge tps`).
4. **Expected**: the matrix share falls by at least 85%. The absolute ms depend on the test machine and
   on this synthetic load (no players), so report them as relative numbers only.

---

## 9. Not verified

- Calls per tick (about 31) and matrix size (a few thousand cells): inferred from the profile
  (`discharge` = 3 reads) and the harness per-lookup cost. With more than one matrix the split differs.
  The gain range in section 7 depends on this.
- That AkashicPerf.3's matrix code equals upstream Mekanism 9: I had no upstream jar to diff.
- The thread on which OpenComputers calls `IComputerIntegration.invoke` (the fallback makes it harmless either way).
- That every mod listed in R1 only reads `Chunk.field_150816_i`. Only AE2's spatial code was read.
- That the FML `ServerTickEvent` fires on Crucible's server thread (`MinecraftServer.primaryThread`,
  Crucible source `MinecraftServer.java:187/227`, binpatched so no javap). If it does not, the
  feature stays off (R2).
- The test-server details marked above: the `/setblock` registry string, the energy cube tier string and
  its accepting faces, chunk unload by distance in the test world, and whether the mod gets a runtime
  reload command.
- The Crucible ratio of lookup cost to fast-path cost. The harness uses a simplified world, so the
  real per-call saving may be larger than measured.
- The harness checks the algorithm against the real Mekanism classes, not the mixin as applied by
  UniMixins. Mixin application and the late-loader registration are checked only in 8.2.
