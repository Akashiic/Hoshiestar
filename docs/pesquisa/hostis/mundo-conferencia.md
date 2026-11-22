# Tarefa 0.8: conferência do mapa "mundo" (World / WorldServer)

Conferente cético; não escrevi `mundo.md` e não o editei. Estudo só de leitura: nada foi compilado, iniciado ou alterado.
Todo `arquivo:linha` abaixo foi aberto por mim nesta conferência. "Não verificado em execução" vale para todo cenário de falha.

Convenções (as mesmas do mapa):
- `MC/` = `.../scratchpad/build/Crucible/eclipse/cauldron/src/main/java/` (Minecraft + Forge com os patches do Crucible, nomes MCP).
- `CR/` = `.../scratchpad/build/Crucible/src/main/java/` (Crucible, CraftBukkit, Spigot, Thermos).
- `W` = `MC/net/minecraft/world/World.java`, `WS` = `MC/net/minecraft/world/WorldServer.java`.
- **B** = Degrau B (fase paralela sincronizada: 3-4 threads de região ticam TEs e entidades; o resto espera). **C** = Degrau C (regiões soltas).
- `[H..]` = itens de `estudo/hostis/chunks.md`; `[S..]` = `estudo/hostis/estatico.md`; `[K..]` = `estudo/hostis/bukkit.md`.

Um fato de ordem de tick que muda várias severidades do mapa (verificado):
`MinecraftServer` chama, por mundo, `worldserver.tick()` (`MC/net/minecraft/server/MinecraftServer.java:963`), depois
`worldserver.updateEntities()` (`:986`), e só depois `getEntityTracker().updateTrackedEntities()` (`:1010`).
Os ticks agendados (`WS:279` `tickUpdates`) e os **ticks aleatórios de bloco** (`WS:283` → `func_147456_g` `WS:438-579`,
incluindo crescimento de muda `WS:564`, gelo/neve `WS:491-538`, raio `WS:475-487`) estão dentro de `tick()`, **fora** de
`updateEntities()`. Se o Degrau B paraleliza só TEs e entidades (o enunciado da tarefa), tudo que só é alcançado por tick
aleatório ou agendado continua serial no B. O mapa às vezes deu "B alto" contando com ticks aleatórios dentro das regiões.

---

## 1. Tabela de veredictos

Legenda: **confirmado** = estrutura, linhas, quem escreve e falha conferem (pode haver acréscimo menor); **corrigido** =
algo estava errado (linha, quem escreve, modo de falha ou severidade); **refutado** = não é hostil no tick.

| Item | Veredicto | Nota curta |
|---|---|---|
| M01 `collidingBoundingBoxes` | confirmado | `W:1930` troca o objeto do campo e segue usando o campo; `W:2008` limpa. Faltaram chamadores: `Entity.java:277,2316`, `ItemBoat.java:102`, `EntityZombie.java:299`, `EntityMagmaCube.java:29`, `EntityPigZombie.java:74`, `EntityPlayerMP.java:205`. |
| M02 `loadedEntityList` / `HashedArrayList` | corrigido (menor) | Núcleo confirmado (`HashedArrayList.java:14-21`, recursão `:96-99`). Errado: `removePlayerEntityDangerously` não remove "por índice com o cursor"; ver §2. |
| M03 cursores e `TickLimiter` | corrigido (menor) | O ajuste de cursor de `W:1908-1918` é código morto (ver §2). Resto confirmado (`W:2421-2423,2479,2518-2534`; `CR/org/spigotmc/TickLimiter.java`). |
| M04 listas de TE | confirmado | Acréscimo: a 2ª varredura de `addedTileEntityList` em `getTileEntity` (`W:3191-3203`) roda **sem** depender da flag, sempre que o chunk não tem TE na posição (caso comum: cano sondando vizinho sem TE). |
| M05 `field_147481_N` | confirmado | `W:172,2490,2573,2587`; leitores `W:2631,3167,3222,4730`. Tratamento tem alternativa mais simples (§4). |
| M06 `field_147483_b` e troca da lista | confirmado | `W:2492-2514`, troca `W:2507` por `ArrayList` simples. |
| M07 `playerEntities` | confirmado | Acréscimo: o `onUpdate` do próprio jogador roda no laço de entidades (região), ver `[H30]`; não muta a lista. |
| M08 `weatherEffects` | corrigido (severidade) | No B base o único escritor vanilla (raio, `WS:475-487`) é tick aleatório = global. Na região só sobra raio de mod. B baixo, não médio. |
| M09 conjuntos de tick agendado | corrigido | Núcleo confirmado. Errado: "ThisTick dá CME enquanto outra thread agenda": agendar nunca escreve `pendingTickListEntriesThisTick`. Faltou um modo de falha (remoção errada no `TreeSet`). Ver §2. |
| M10 `nextTickEntryID` estático | confirmado | `NextTickListEntry.java:7,19,57`. Acréscimo em M09. |
| M11 fila dupla de block events | confirmado | `WS:103-104,1238-1277`. |
| M12 `activeChunkSet(_CB)` | corrigido | No B base o conjunto é reconstruído em `tick()` antes de `updateEntities` e só é **lido** na fase paralela. O leitor por TE só existe com `skipTileEntityTicks` ligado. B baixo. Ver §2. |
| M13 `updateLCG`, `ambientTickCountdown` | confirmado | Só ticks aleatórios (global no B base). |
| M14 `growthOdds` | confirmado | Escrito só em `W:3496` e `WS:563` (global). Leitura por acelerador/farinha de osso na região é só leitura. |
| M15 `lightUpdateBlockList` | corrigido (menor) | Severidade confirmada. Errado o detalhe do dano: as entradas guardam deslocamento relativo (±32) à origem; a thread que lê uma entrada alheia a reaplica em volta da **própria** origem, não da origem da outra. Não há AIOOBE (há checagem de limite). |
| M16 executor de luz assíncrona | **refutado** | O caminho assíncrono é inalcançável: `W:4921` força `isModded=false` antes do teste `W:4922`, então sempre roda síncrono. Ver §2. |
| M17 `World.rand` (`ThermiteRandom`) | confirmado | `ThermiteRandom.java:120-128` sem trava. Não reconferi a contagem de "124 referências". |
| M18 `entityIdMap` | confirmado | `IntHashMap.java:62-78,81-124` (`copyTo` zera os baldes antigos `:110`). Tratamento proposto tem risco (§4). |
| M19 `Entity.nextEntityID` | corrigido | Há um 2º escritor: `Entity.resetEntityId` (`Entity.java:2703-2706`), chamado por `ForgeChunkManager.java:922`. "Nada mais escreve" está errado (já anotado em `[S07]`). |
| M20 captura de snapshots do Forge | confirmado | `W:812-849`, `ForgeHooks.java:516-677`. Acréscimo: ela também faz `startCapture` de muda falhar em outra região (ver M22). |
| M21 `captureItemDrops`/`itemStackSpawnQueue` | confirmado | `W:1720-1729`, `ForgeHooks.java:528-529,549-550,632,664-668`. `capturedItems` (`W:177`) não tem uso (grep). |
| M22 captura de árvore (`worldChangeHolder`) | corrigido | Severidade no B base exagerada por um lado (muda cresce no tick aleatório, global) e subestimada por outro (qualquer colocação com FakePlayer em qualquer região apaga a captura em andamento e faz `startCapture` lançar exceção). Ver §2. |
| M23 `populating`, `scheduledUpdatesAreImmediate` | corrigido | `populating` só fica `true` enquanto rodam `BlockPopulator`s do Bukkit (`ChunkProviderServer.java:441-452`); com nenhum populador de plugin a janela é vazia. A parte de `scheduledUpdatesAreImmediate` está confirmada. |
| M24 `mapStorage` compartilhado | corrigido (severidade) | Estrutura confirmada (`MapStorage.java:22-24`, sem trava). Mas depois do 1º `loadData` o acesso é só `HashMap.get` (leitura); a corrida é só na criação preguiçosa e em `getUniqueDataId`. B médio, não alto. O conteúdo de cada `WorldSavedData` é dos módulos de mod. |
| M25 aldeias | confirmado | `VillageCollection.java:17-20,47-56,58-93`; `EntityVillager.java:117-119`. |
| M26 `SpawnerAnimals` | confirmado | Só chamado de `WS:253` (global). |
| M27 portal de entidade no tick | confirmado | `Entity.java:440-462,2469-2545`; `Teleporter.java:24-26,159-209,628-645`. O bug pré-existente (remoção da entidade errada) confere com o código (`Entity.java:2515` + `W:2478-2479`). |
| M28 `Explosion` | confirmado | `Explosion.java:34-46,91,132,162-166,226`. Tratamento "adiar" tem problema de API (§4). |
| M29 placar | confirmado | `WorldServerMulti.java:18,28`; `EntityPlayer.java:766,772`; `EntityPlayerMP.java:619,1177-1183`. |
| M30 `worldInfo` | confirmado | `WorldInfo.java:578-580`; `GameRules` é `TreeMap` só lido no tick (`GameRules.java:42-45`). |
| M31 cache de bioma | confirmado | `BiomeCache.java:12-13,21-37,44-66`; mesmo item que `[H14]`. |
| M32 `theProfiler` | confirmado | |
| M33 contadores | confirmado | `EntityTNTPrimed.java:64`, `WS:670`, `SpigotWorldConfig.java:54`, `TimingHistory.java:54-55`. |
| M34 config preguiçosa | confirmado | `WorldConfig.java:36-57`, `ConfigBase.java:23`; só no 1º TE/entidade de cada classe (cache estático `CauldronHooks.java:38-39`, `[S27]`). |
| M35 `worldAccesses` | confirmado | `WorldManager.java:26-34,48-51,67-75`; `PlayerManager.java:32-33,143-153`. |
| M36 eventos Bukkit no código do mundo | corrigido (severidade) | Confirmado `W:1096-1108`. A trava está em `MC/org/bukkit/plugin/SimplePluginManager.java:482-496` (cópia com patch). O tratamento "pular se não há ouvinte" não ajuda: na cópia de teste 4 plugins (WorldGuard entre eles) ouvem `BlockPhysicsEvent` (`[K01]`). Vazão no B: alta. Acréscimo: cada evento grava `chunksToUnload` (A02). |
| M37 `theCalendar` | confirmado | Chamadores que o mapa não rastreou: `EntityBat.java:222`, `EntityZombie.java:547`, `EntitySkeleton.java:277` (spawn e `onSpawnWithEgg`; o spawner de TE roda na região). |

Contagem: 25 confirmados, 11 corrigidos, 1 refutado, 3 acrescentados (§3).

---

## 2. Correções por extenso

### M02 / M03: o "ajuste de cursor" de `removePlayerEntityDangerously` é código morto
`W:1906` faz `this.loadedEntityList.remove(p_72973_1_)` (por objeto; em `HashedArrayList` isso tira da lista e do conjunto,
`HashedArrayList.java:107-112`). Logo em seguida `W:1908` faz `indexOf`, que agora devolve `-1`, então o bloco de
`W:1910-1918` (decrementa `tickPosition`, remove por índice) **nunca executa**. Para M02: a remoção é por objeto, não "por
índice com o cursor compartilhado". Para M03: o método não ajusta o cursor. Efeito pré-existente (uma thread): remover uma
entidade já ticada desloca a lista e uma entidade pula um tick. É irrelevante para a concorrência, mas quem escrever o laço
de região não deve copiar esse trecho achando que funciona. Também em `HashedArrayList.set` (`:147-154`): além de tirar do
conjunto o elemento novo em vez do antigo (o mapa disse isso), ele **não adiciona** o novo ao conjunto.

### M08: severidade no B
O raio vanilla nasce em `WS:475-487`, dentro de `func_147456_g` (tick aleatório, chamado de `tick()` em `WS:283`). No B
base isso é global. Na região sobra só raio criado por mod a partir de TE ou entidade (frequência não verificada).
Severidade: **B baixo | C alto**. O tratamento (fila aplicada na junção) continua certo.

### M09: o que estava errado e o modo de falha que faltou
- Errado: "`tickUpdates` iterando `ThisTick` enquanto outra thread agenda: CME". `scheduleBlockUpdateWithPriority`
  (`WS:592-636`) e `func_147446_b` (`WS:638-653`) só escrevem no `HashSet` e no `TreeSet`. `pendingTickListEntriesThisTick`
  é escrito só por `tickUpdates` (`WS:748,758,801`) e por `getPendingBlockUpdates` (`WS:823-845`), e lido por
  `isBlockTickScheduledThisTick` (`WS:584`). No B base, `tickUpdates` é global e a lista está vazia durante a fase paralela
  (limpa em `WS:801`). Ver A03.
- Faltou: `TreeSet.remove` usa `compareTo` (`NextTickListEntry.java:57`). Com ids repetidos (M10), duas entradas
  diferentes com mesmo tempo e prioridade comparam como iguais. Então `tickUpdates` (`WS:746`) ou `getPendingBlockUpdates`
  (`WS:843`) podem tirar do `TreeSet` a entrada **errada**: ela sai da árvore, mas fica no `HashSet` (`WS:747` remove a
  certa por `equals`). Essa posição nunca mais é agendada (`WS:630`). É o mesmo estado "preso para sempre" do mapa, por
  outro caminho.
- Confirmado e reforçado: há agendamento a partir de trabalho de região sem depender de ticks aleatórios. Caminho medido
  na §3 (A03): funil `markDirty` → comparador → `scheduleBlockUpdateWithPriority`.

### M12: no B base o conjunto é só leitura
A reconstrução (`W:3460-3551`) roda em `func_147456_g` (`W:3586-3589`), chamada de `tick()` (`WS:440`), antes de
`updateEntities`. A poda com `iter.remove()` (`WS:446-458`) também está em `tick()`. Durante a fase paralela ninguém escreve.
Leitores na região: `CauldronHooks.canTileEntityTick` lê `isActiveBlockCoord` só se `skipTileEntityTicks` estiver ligado
(`CR/net/minecraftforge/cauldron/CauldronHooks.java:270,280`), não "para todo TE". `ActivationRange.checkIfActive` só é
avaliado quando `canUpdate` é falso (`W:2673`, curto-circuito). Severidade: **B baixo** enquanto os ticks aleatórios forem
globais; **C alto**. Tratamento no B: nenhum (já é "construir antes, ler durante").

### M15: como o dano acontece de fato
Cada entrada guarda `dx+32 | dy+32<<6 | dz+32<<12 | nível<<18` relativo à origem da chamada (`W:3826,3864-3889`) e é
decodificada em relação à origem de quem lê (`W:3798-3800`). Uma thread que lê a entrada de outra propaga luz em posições
até ±32 blocos da **sua** origem, com o nível alheio. Os índices de leitura e escrita (`l`, `i1`) são locais, e há checagem
de limite (`W:3824`, `W:3858`), então não há `ArrayIndexOutOfBoundsException`. O resultado é luz errada silenciosa, gravada
no chunk. Severidade e tratamento (array por thread) confirmados.

### M16: refutado como risco
`updateLightByType(EnumSkyBlock,int,int,int)` faz `if (isModded == null) isModded = false;` (`W:4921`) e em seguida
`if (!useAsyncLighting || !isModded) return <síncrono>` (`W:4922-4923`). Como `isModded` acabou de virar `false`, o desvio
síncrono é sempre tomado. O bloco que a deixaria `true` (`W:3899-3907`) só roda se ela ainda for `null`, e o único caminho de
entrada (`W:4915`) a preenche antes. Para o `WorldServer`, o provedor de chunks é `ChunkProviderServer`, de pacote
`net.minecraft...` (`WS:901,907`), então seria `false` de qualquer forma. O executor (`W:4910`) nunca recebe trabalho.
Com a opção ligada sobra só um efeito pré-existente inofensivo: `W:3909-3915` decrementa `pendingLightUpdates` que nunca foi
incrementado (contadores negativos; o único leitor, `ChunkProviderServer.java:116`, testa `> 0`). Também sobra uma thread
ociosa por mundo. Tratamento: nenhuma ação; recusar o modo paralelo com `use-async-lighting` é desnecessário (inofensivo se
mantido). Ressalva: `isModded` é público; um mod que o ponha em `true` reativaria o caminho (não procurado nos jars).

### M19: dois escritores
`this.entityId = nextEntityID++` aparece no construtor (`Entity.java:201`) e em `resetEntityId()` (`Entity.java:2703-2706`),
chamado por `ForgeChunkManager.java:922`. O mixin de contador atômico precisa cobrir os dois.

### M22: o que muda na severidade e o que faltou
1. Exagero no B base: a muda que cresce sozinha passa por `BlockSapling.updateTick` (`BlockSapling.java:49-58`), chamado no
   tick aleatório (`WS:564`), que é global. Da região, a captura só começa por: farinha de osso usada por FakePlayer via
   `tryPlaceItemIntoWorld` → `ForgeHooks.java:518-523`; mods que chamam `updateTick` de blocos alheios (aceleradores de
   crescimento); `CraftWorld.generateTree` chamado por plugin (`CR/.../CraftWorld.java:546`). Frequência não verificada.
2. Subestimado: o `finally` de `ForgeHooks.onPlaceItemIntoWorld` chama `world.worldChangeHolder.discardChanges()` **sempre**
   (`ForgeHooks.java:544-552`), não só quando estava fazendo proxy. Qualquer colocação de bloco por FakePlayer na região A
   apaga a captura de árvore em andamento da região B. E `startCapture` lança `IllegalStateException` se
   `captureAndProxyBlocks` **ou** `captureBlockSnapshots` estiver ligado (`CR/io/github/crucible/util/WorldChangeHolder.java:30-35`).
   Ou seja, basta uma colocação por FakePlayer (M20) em outra região, não precisa de duas mudas.
3. Piora o dano entre regiões: durante a captura, `setBlock`, `getBlock`, `getBlockMetadata` e `getTileEntity` são
   desviados (`W:668-674,785-789,882-888,3161-3164`), mas `setBlockMetadataWithNotify` **não** (`W:915-961`). Um bloco da
   região B gravado por `setBlock` vai para o holder de A, e a troca de metadado do mesmo bloco vai direto ao chunk:
   bloco e metadado ficam incoerentes.

Severidade: **B médio** (pela frequência não medida de FakePlayer, farinha de osso e aceleradores) e **alto** se os ticks
aleatórios entrarem na região; **C alto**.

### M23: janela de `populating`
`populating = true` só envolve o laço `for (BlockPopulator populator : world.getPopulators())`
(`ChunkProviderServer.java:441-452`). O `populate` do gerador e o `GameRegistry.generateWorld` dos mods ficam fora dela
(`:429`, `:459`). Sem populador de plugin a janela não tem trabalho. A parte de `scheduledUpdatesAreImmediate`
(`WorldGenLiquids.java:82-84`, `WorldGenHellLava.java:90-92`, `StructureNetherBridgePieces.java:808-810` → `WS:600-617`)
continua valendo sempre que um chunk for gerado numa thread de região. Mesmo assunto que `[H12]`/`[H07]`.

### M24: severidade
`MapStorage.loadData` (`MapStorage.java:33`) só escreve no mapa na 1ª carga de uma chave; depois é leitura. A corrida real
fica em: criação preguiçosa simultânea da mesma chave, `setData` (`:82`) e `getUniqueDataId` (`:180`, que também grava
`idcounts.dat`). Isso continua errado em paralelo, mas é raro depois do aquecimento. **B médio | C médio** para o mapa em si.
O risco grande (conteúdo dos `WorldSavedData` de mod mexido por TEs de regiões diferentes) é do módulo de cada mod, como o
próprio mapa diz.

### M36: severidade e um efeito escondido
A vazão no B é **alta**, não média: `[K01]` registra que na cópia de teste 4 plugins (AncientGates, ChestShop, Skript,
WorldGuard) ouvem `BlockPhysicsEvent`, disparado em toda notificação de vizinho (`W:1096-1108`). Então "pular o evento se a
lista de ouvintes estiver vazia" não ajuda neste servidor (produção não verificada). Construir o evento ainda chama
`CraftWorld.getBlockAt` → `getChunkAt` → `ChunkProviderServer.loadChunk`, que grava em `chunksToUnload`. Ver A02.

---

## 3. Itens acrescentados

Método usado (diferente da lista de palavras-chave do mapa): (a) listei os campos de `World`, `WorldServer`,
`WorldServerMulti`, `NextTickListEntry`, `Teleporter`, `Explosion`, `MapStorage`, `VillageCollection`, `SpawnerAnimals` e
`HashedArrayList`/`WorldChangeHolder` por grep de declaração e conferi cada coleção, array, contador e flag contra o mapa;
(b) segui dois caminhos de tick do começo ao fim (abaixo). Campos que o mapa não listou e que achei inofensivos:
`WS:706` `long last` (só escrito em `tickUpdates` com `CauldronCommand.debug`, global); `Entity.captureDrops/capturedDrops`
(`Entity.java:174-175`, por entidade, não por mundo); `Block.capturedDrops` já é `ThreadLocal` (`Block.java:2276`).

**Caminho 1, TE que move itens para o inventário vizinho e atualiza vizinhos** (funil vanilla, `TileEntityHopper`):
`updateEntity` (`TileEntityHopper.java:224-235`) → `func_145887_i` (`:238-262`) → busca do destino `func_145893_b`
(`:754-786`): `World.getTileEntity` (M04, M05, M22), `getBlock` (M22), `BlockChest.func_149951_m` (`BlockChest.java:405`, lê TEs
vizinhos), `getEntitiesWithinAABBExcludingEntity` (`[H40]`) e `world.rand` (`:785`, M17) → `InventoryMoveItemEvent` (`:371-372`,
trava global `[K01]`) → `iinventory.markDirty()` no vizinho (`:387`) e `this.markDirty()` (`:259`) →
`TileEntity.markDirty` (`TileEntity.java:160-172`): `getBlockMetadata` (M22), `markTileEntityChunkModified` (`W:4020-4026`,
`[H20]`), `func_147453_f` (`W:4661-4683`, alcança até 2 blocos) → `BlockRedstoneComparator.onNeighborChange`
(`BlockRedstoneComparator.java:233-238`) → `isBlockTickScheduledThisTick` (`:151`; A03) → `scheduleBlockUpdateWithPriority`
(`:161,165`; M09, M10). Coleta de item de cima: `func_145891_a` (`:472-517`) → `setDead` em `EntityItem` (`:639`).

**Caminho 2, entidade que cruza a borda de chunk e é rastreada por jogador:**
`W:2422-2485` (cursor M03, remoção M02) → `WS:861-873` → `W:2647-2775`: `activity`/`getPersistentChunks` (`W:2653`; `[S11]`),
`canSushchestvoTick` (`CauldronHooks.java:220-266`: cache estático `[S27]`, `chunksToUnload.contains` `[H05]`), `onUpdate` →
`moveEntity` → `getCollidingBoundingBoxes` (`Entity.java:616,747,809`; M01) e `func_145775_I` (`Entity.java:1045-1075`, colisão
com bloco: placas de pressão agendam tick, M09; portal, M27) → som de passo → `worldAccesses` → `sendToAllNear` (M35) →
troca de chunk `W:2741-2757` (`Chunk.removeEntityAtIndex/addEntity`, `[H16]`, `[H21]`) → animação e estado
(`WS:1193-1196` → `EntityTracker.func_151248_b`, `[H26]`). A atualização do rastreador em si é serial
(`MinecraftServer.java:1010`). Se for jogador, `EntityPlayerMP.onUpdate` (`EntityPlayerMP.java:308-407`) envia chunks e
mexe no rastreador de dentro do laço (`[H30]`).

O que esses caminhos e a listagem acharam que **nenhum** estudo tem:

### A01. Junção de orbes de XP do Spigot dentro de `addEntity`
- Onde: `W:1792-1815`. Ao criar um `EntityXPOrb`, procura outros orbes num raio `expMerge`
  (`getEntitiesWithinAABBExcludingEntity`, `W:1799`), soma `xpValue` e chama `setDead()` neles (`W:1807-1811`).
- Quem: toda criação de orbe (morte de mob, fornalha, mineração por FakePlayer) em trabalho de região.
- Tipo: suposição de dono único, check-then-act em outras entidades.
- O que quebra: dois orbes nascendo ao mesmo tempo em regiões vizinhas, perto da borda, leem `!loopItem.isDead` do mesmo
  orbe antes de algum deles chamar `setDead`: os dois somam o valor dele (XP duplicado). Dentro de uma região só há uma
  thread, então nada acontece.
- Severidade: **B baixo** (só na borda, e o raio de junção afasta) | **C médio**.
- Tratamento: nenhum no B se o raio de junção for maior que `expMerge`; no C, limitar a busca à região dona.

### A02. Gravação escondida em `chunksToUnload` (e carga síncrona) em toda notificação de vizinho
- Onde: `W:1100` cria `BlockPhysicsEvent(world.getBlockAt(...))` → `CraftWorld.getBlockAt` (`CR/.../CraftWorld.java:114-116`)
  → `getChunkAt` (`:151-153`) → `ChunkProviderServer.loadChunk(x,z)` (`MC/.../ChunkProviderServer.java:169-176`), que **sempre**
  faz `this.chunksToUnload.remove(x,z)`. `LongHashSet.remove` escreve (`values[index]=REMOVED`, `modCount++`, `elements--`)
  quando a chave existe (`CR/.../util/LongHashSet.java:147-151`). Se o chunk não estiver carregado, `loadChunk` o carrega na
  hora, ignorando `loadChunkOnProvideRequest`.
- Quem: toda notificação de vizinho (`notifyBlocksOfNeighborChange`), ou seja, quase todo `setBlock` com flag 1 em trabalho
  de região.
- Tipo: leitura que escreve, numa coleção por mundo.
- O que quebra: com chunks na fila de descarga (comum na beira da área carregada quando jogadores andam), duas regiões
  removendo ao mesmo tempo perdem atualizações de `elements` e `modCount`, e as decisões de redimensionar ficam erradas.
  `[H05]` calculou a severidade supondo "só com cargas paralelas ou teleporte de mod"; este caminho é bem mais frequente.
  Notificação na beira de um chunk descarregado dispara carga síncrona de dentro da região (`[H07]`).
- Severidade: **B médio | C alto**.
- Tratamento: o mesmo de `[H05]` (só a thread global mexe; a região anota "tocado" e a remoção é aplicada na junção) e,
  ainda melhor, construir o `CraftBlock` do evento sem passar por `loadChunk` (usar o chunk já carregado; se não houver,
  não carregar). Levar ao dono do estudo de chunks para rever a severidade de `[H05]`.

### A03. `isBlockTickScheduledThisTick` / `pendingTickListEntriesThisTick` lidos da região
- Onde: `WS:581-585` cria um `NextTickListEntry` (incrementa o contador estático de M10) e faz `contains` na lista
  `pendingTickListEntriesThisTick` (`ArrayList` simples, `WS:106`).
- Quem: repetidores e comparadores (`BlockRedstoneDiode.java:158`, `BlockRedstoneComparator.java:151`), alcançados da
  região pelo caminho 1 (`markDirty` de qualquer TE com inventário ao lado de um comparador) e por redstone ligada a máquinas.
- Tipo: coleção por mundo + contador estático.
- O que quebra: no B base, nada: `tickUpdates` é global e a lista está vazia na fase paralela, o mesmo estado que o vanilla
  vê nesse ponto do tick. Mas o tratamento de M09 diz "`tickUpdates` roda por região nas próprias entradas"; se isso for
  feito, `ThisTick` precisa ser por região também, senão uma região lê a lista que outra está esvaziando (`WS:753-758`,
  `iterator.remove()`): CME ou resposta errada (comparador que agenda duas vezes, ou nunca).
- Severidade: **B baixo** (com `tickUpdates` global) | **C alto**.
- Tratamento: junto com M09: a lista `ThisTick` acompanha a estrutura de ticks agendados (global no B, por região no C).

---

## 4. Julgamento dos tratamentos propostos

Inseguros ou caros demais para a fase paralela sincronizada:

1. **M20, M21, M22: "thread-local" nos campos de captura.** A ideia está certa, mas o custo não foi pensado.
   `World.getBlock` (`W:665-674`), `setBlock` (`W:785`), `getBlockMetadata` (`W:882`) e `getTileEntity` (`W:3161`) testam a
   captura em **toda** chamada, e `getBlock` é o método mais chamado do jogo. Um `ThreadLocal.get()` em cada chamada é caro.
   Proposta: um contador global barato ("há alguma captura ativa em algum lugar?") como caminho rápido, e o `ThreadLocal` só
   quando ele for diferente de zero. Também precisa redirecionar o `discardChanges()` incondicional de
   `ForgeHooks.java:551` e o teste de `WorldChangeHolder.java:31` para o contexto da thread. Senão o problema da §2/M22
   continua.
2. **M18: "adiar adições e remoções do `entityIdMap` para a junção".** Muda o comportamento: um mod que cria uma entidade e
   logo a procura por id (`getEntityByID`, `WS:1161-1164`) recebe `null` até o fim da fase. Escritas no mapa são raras
   (spawn e morte), então uma trava simples nos três métodos (`WS:1131-1164`) é barata e não muda o comportamento.
   Recomendo trava no B e mapa concorrente no C.
3. **M28: "adiar para a fase global explosões cuja caixa cruza a borda".** `newExplosion` devolve o objeto `Explosion`
   (`W:3043-3052`, `WS:1198-1236`) e quem chama pode ler `affectedBlockPositions` e `wasCanceled` na hora. Adiar exige
   devolver um objeto falso e muda a ordem (a TNT já está morta). Fica como problema em aberto. Opções: raio de junção
   calculado pelo maior explosivo conhecido; lista de explosivos de mod que forçam a dimensão a ficar serial.
4. **M23: flags por thread.** Desnecessário se a outra metade do próprio tratamento for feita (geração de chunk pedida da
   região adiada para a fase global). Aí as flags só mudam quando as regiões estão paradas. Fazer flags por thread no
   caminho quente de `notifyBlockChange` e `scheduleBlockUpdate` custa caro sem ganho.
5. **M24, M25, M29, M31: travas.** Baratas, mas há uma regra que o mapa não escreveu: **nunca segurar uma dessas travas
   enquanto chama código de mod ou plugin** (evento Bukkit, evento Forge, construtor de `WorldSavedData`). Um handler de
   plugin roda dentro do monitor global (`SimplePluginManager.java:492-494`); se ele esperar por uma trava nossa que outra
   região segura, e essa região estiver esperando para entrar em `callEvent`, as duas param para sempre (`[K01]`).
   `MapStorage.loadData` instancia o `WorldSavedData` do mod (`MapStorage.java:33+`) e é o caso de maior risco: é preciso
   criar fora da trava e publicar com "put se ausente".
6. **M16: recusar o modo paralelo com `use-async-lighting`.** Desnecessário (caminho morto, §2). Inofensivo se mantido.
7. **M36: "pular o evento se não há ouvintes".** Não resolve aqui (WorldGuard ouve). O ponto de espera é real e precisa
   entrar na estimativa de ganho do Degrau B.

Corretos, com nota:
- M05: thread-local funciona, mas há algo mais simples. Como as listas do mundo não podem mudar de tamanho na fase paralela
  (desenho §3), basta deixar `field_147481_N = true` durante **toda** a fase paralela (entidades e TEs), com a lista de
  pendentes por região (M04). Assim toda adição de TE vai para pendentes, que é a semântica desejada.
- M04: lista de pendentes **por região** é necessária, não só desejável. A varredura linear de `W:3191-3203` roda em quase
  todo `getTileEntity` sem TE, então uma lista única sincronizada viraria disputa.
- M09 + M10: buffer por região mesclado na junção serve. A ordem entre entradas com mesmo tempo e prioridade só é preservada
  **dentro** de uma região se os ids crescerem na ordem do buffer. Entre regiões a ordem não importa (são independentes). A
  deduplicação contra o `HashSet` tem que acontecer na mescla, porque `contains` não enxerga o buffer.
- M12: no B não precisa de nada (§2).
- M17: `Random` que despacha para um estado por thread custa um `ThreadLocal.get()` por número sorteado: aceitável. Manter
  `setSeed` por thread, para `setRandomSeed` (`W:4577-4582`) continuar determinístico na geração.
- M19: o mixin precisa cobrir também `resetEntityId` (§2).
- M02: a "lista visão" pública (desenho §4(3)) não está provada (o próprio desenho diz "a confirmar"). Até lá, o caminho
  seguro é a lista do mundo não mudar na fase paralela; leitores de fora (mods, `CraftWorld.getEntities`) veem a lista
  estável do começo do tick.
- M06, M07, M11, M15, M26, M27, M30, M32, M33, M34, M35, M37: de acordo.

---

## 5. Lacunas que continuam

- **Decisão que muda severidades:** se os ticks aleatórios e agendados entram ou não na fase paralela do B. M08, M12, M13,
  M14 e M22 (e A03, pelo lado de M09) mudam de baixo para alto com isso. Hoje o desenho diz "tile entities, entidades e
  blocos" (`docs/02-arquitetura.md:84`) e o enunciado da tarefa diz só TEs e entidades.
- Frequência, em produção, de FakePlayer colocando bloco ou usando farinha de osso e de aceleradores de crescimento (M20 a
  M22). Não medido.
- Plugins de **produção** que ouvem `BlockPhysicsEvent` e `InventoryMoveItemEvent`: só a cópia de teste foi contada (`[K01]`).
- Se algum mod do pack escreve direto em `world.loadedEntityList` / `loadedTileEntityList` (`add`/`remove`), liga
  `world.captureBlockSnapshots` por conta própria ou põe `isModded = true`. Os jars não foram examinados nesta conferência.
- Subclasses de `WorldProvider` de mod (Galacticraft, Mystcraft etc.) podem guardar estado mutável lido no tick. Fora do
  que foi listado.
- `Village.java` (listas de portas, `playerReputation`, agressores) e `MapData` (conteúdo do mapa de item) não tiveram cada
  campo conferido; só o que o mapa citou.
- Contagens herdadas do mapa e de estudos anteriores ("34 jars", "63 jars", "124 referências a `world.rand`") não foram
  recontadas.
- Nada foi executado: todo cenário de falha, os dois bugs pré-existentes de M27 e M02/M03 e o caminho morto de M16 vêm só
  da leitura do código.
