# Perfil de produção (spark, 10 minutos): quanto o paralelismo por região pode ganhar

Estado: análise feita sobre um único relatório do spark exportado da produção.
Nada foi enviado para fora. Números com "estimativa" são contas, não medidas.

## 0. Como foi medido e como ler

- Fonte: `anexos/spark/wIBxG0D4y9.json` (relatório do spark, 36 MB). Lido com
  `tools/spark_report.py` (do projeto) e com scripts próprios em
  `<trabalho>/tools/perfil.py` e `<trabalho>/tools/threads2.py` (somente leitura).
- **Spark** é um profiler (medidor) que olha a pilha de chamadas de cada thread a cada 4 ms.
  Ele amostra a thread rodando ou esperando, então o total de cada thread é tempo de
  parede (relógio), não CPU. Os tempos de um nó incluem os filhos ("inclusivo").
- **Tick**: um passo do jogo; o alvo é 50 ms (20 TPS). **MSPT**: milissegundos por tick.
- Cabeçalho (comando `resumo`): 602 s, 7113 ticks, TPS médio 11,82, 8 jogadores,
  uptime 7,3 h, MSPT mediana 71,1 ms (último 1 min) e 72,3 ms (5 min), p95 90,7 a 94,8 ms,
  máximo 5219 ms, 60 mundos, 4118 a 4790 chunks por janela, Temurin 21.0.13, 7 threads de
  hardware, CPU do processo 40% (média de 1 min), heap 10,3 de 14 GB.
- Base de cálculo: a "Server thread" (thread principal) tem 598,9 s de parede.
  598,9 s / 7113 ticks = **84,2 ms por tick**. Ela nunca dorme: `MinecraftServer.run`
  e `MinecraftServer.tick` têm o mesmo tempo (591.152 ms), sem `Thread.sleep`.
  Diferença para a MSPT do spark (média 73,6 a 77,2 ms): hipótese, nao verificado, de que a
  MSPT do spark no Forge é medida entre os eventos de início e fim de tick e deixa de fora
  parte dos handlers pré e pós tick (que somam 10,3 ms/tick, ver tabela).
- Nomes como `func_72939_s` são nomes SRG (ofuscados). Traduzi com
  `forge/fml/conf/methods.csv` do clone do Crucible (ex.: `func_72939_s` = updateEntities).

## 1. Orçamento do tick (thread principal)

Porcentagem do total da thread (598,9 s) e ms por tick (total / 7113).

| Fase | % | ms/tick | Classe |
|---|---:|---:|---|
| `World.updateEntities` (func_72939_s), total | 51,26 | 43,16 | |
|   tick de tile entities (via `WorldMutationGuard.runTileEntityUpdate`) | 26,44 | 22,27 | R (10,6 pontos com estado global, §2) |
|   tick de entidades (`World.updateEntity`, func_72870_g) | 20,70 | 17,43 | R |
|   laço próprio (percorrer listas, remover) | 2,58 | 2,17 | R |
|   `TileUpdateEvent` do DragonAPI | 1,03 | 0,87 | R |
|   `ActivationRange` (Spigot) | 0,25 | 0,21 | R |
| `WorldServer.tick` (func_72835_b), total | 13,77 | 11,59 | |
|   ticks de bloco e aleatórios (func_147456_g) | 7,58 | 6,38 | R |
|   spawn de mobs (`SpawnerAnimals.findChunksForSpawning`) | 3,86 | 3,25 | R (0,98 disso é carregar chunk: I) |
|   `PlayerManager.updatePlayerInstances` (envio de chunks) | 1,44 | 1,21 | R |
|   vilas, luz do céu, updates agendados, outros | 0,73 | 0,61 | R |
|   `unloadQueuedChunks` | 0,16 | 0,13 | I |
| Rede: `NetworkSystem.networkTick` (func_151269_c), total | 10,31 | 8,68 | N |
|   dentro dela: tick do jogador (`EntityPlayerMP.onUpdateEntity`) | 7,17 | 6,04 | N (região do jogador) |
|   dentro dela: pacotes de mod (`FMLProxyPacket`), 2,05 são do AE2 | 2,23 | 1,88 | N com estado global |
| FML pós tick do servidor (`onPostServerTick`) | 7,89 | 6,65 | G (0,33 é plugin: P) |
| FML pós tick de mundo (`onPostWorldTick`) | 5,50 | 4,63 | G |
| FML pré tick do servidor (`onPreServerTick`) | 4,31 | 3,63 | G |
| `EntityTracker.updateTrackedEntities` | 2,59 | 2,18 | R |
| Agendador Bukkit (`CraftScheduler.mainThreadHeartbeat`) | 1,86 | 1,56 | P |
| FML pré tick de mundo | 0,86 | 0,72 | G |
| salvamento (`saveAllWorlds` 0,16 + `saveAllPlayerData` 0,02) | 0,18 | 0,15 | I |
| `ChunkIOExecutor.tick` (fim de carregamento assíncrono) | 0,14 | 0,12 | I |
| resto dentro do tick (< 0,05 cada) | 0,04 | 0,03 | |
| **Soma dentro do tick** | **98,71** | **83,11** | |
| Fora do tick: quadros que o profiler não conseguiu ler (`native.unknown_Java`) | 0,89 | 0,75 | sem explicação |
| Fora do tick: amostras durante GC (`native.GC_active`) | 0,38 | 0,32 | JVM |
| Resto não explicado | 0,02 | 0,02 | |

Evidência: `perfil.py fases` e `perfil.py filhos <nó>`; as linhas acima somam 100,0%.

## 2. Classificação por tipo de trabalho

Legenda: **R** = local da região (paralelizável por região nos Degraus B e C);
**G** = handler global, sem região; **N** = pacotes por jogador; **P** = plugins Bukkit;
**I** = disco, carregamento, geração, salvamento.

| Classe | % da thread | ms/tick | O que entra |
|---|---:|---:|---|
| R puro | 56,7 | 47,7 | entidades, blocos, spawn, tracker, tile entities sem rede global |
| R com estado global | 10,6 | 8,9 | tile entities que mexem em rede/estado de mod (tabela abaixo) |
| G | 18,4 | 15,5 | pré/pós tick do servidor e do mundo (sem o holograma Bukkit) + luz do céu |
| N | 10,3 | 8,7 | rede; 7,2 pontos são o tick do jogador |
| P (nível de fase) | 2,2 | 1,8 | agendador Bukkit + `BukkitHologramBridge` |
| I (nível de fase) | 0,5 | 0,4 | salvar, descarregar, fim do chunk IO; o resto de I está dentro de R (§5) |
| sem explicação + GC | 1,3 | 1,1 | |

"R com estado global": tile entities que são R pela posição mas leem/escrevem estado de mod
compartilhado (rede, frequência, multibloco). Medido com `perfil.py sob "runTileEntityUpdate|<classe>"`:

| Tile entity | % | ms/tick | Estado compartilhado |
|---|---:|---:|---|
| Mekanism `TileEntityInductionPort` | 3,56 | 3,00 | matriz de indução (`SynchronizedMatrixData`) |
| Mekanism `TileEntityQuantumEntangloporter` | 1,97 | 1,66 | frequência global (entre dimensões) |
| Mekanism `TileEntityInductionCasing` | 1,33 | 1,12 | matriz de indução |
| Mekanism cabos/canos/tubos multipart (`PartUniversalCable`, `PartMechanicalPipe`, `PartPressurizedTube`, transportador) | 1,17 | 0,99 | `DynamicNetwork` (registro global) |
| EnderIO `TileConduitBundle` | 1,16 | 0,98 | redes de conduíte |
| EnderStorage `TileEnderTank` + `TileEnderChest` | 0,72 | 0,61 | armazenamento global por frequência |
| IC2 `TileEntityBlock` | 0,39 | 0,33 | rede de energia (Uncomplication) |
| Draconic `TileEnergyTransceiver` | 0,17 | 0,14 | rede de energia do mod |
| AE2 `AEBaseTile` | 0,12 | 0,10 | grid do AE2 |
| **Total** | **10,6** | **8,9** | |

Achado forte: a leitura de energia da matriz de indução do Mekanism
(`SynchronizedMatrixData.getEnergy`) custa **8,27% do tick inteiro** (7,0 ms/tick):
6,03 pontos vêm de tile entities e 2,24 pontos vêm da rede de energia do IC2 do mod
Uncomplication (`aroma1997.uncomplication.enet.EnergyNetGlobal.onTickEnd`, no pós tick de
mundo). Ou seja, um handler G lê o estado de um multibloco R a cada tick.

## 3. Estimativa de Amdahl (todas as linhas são estimativas)

Lei de Amdahl: tempo = parte serial + parte paralela / N. Base: T = 84,2 ms/tick.
Parte de região paralelizável (R) = 51,26 + 13,77 − 0,16 (descarregar) − 0,19 (luz do céu)
+ 2,59 (tracker) = **67,27%** = 56,6 ms. O resto é serial. Três cenários:

- **Otimista**: s = G + P + N + I + sem explicação = 32,7% (27,6 ms). R com estado global roda em paralelo.
- **Conservador**: s = 32,7% + 10,6% (R com estado global fica serial) = 43,3% (36,5 ms).
- **B com rede por região** (como no Folia): rede vai para a região, menos pacotes de mod
  (2,23%) que continuam seriais. s = 32,7 − 10,31 + 2,23 = 24,6% (20,8 ms).

Conta, para o cenário conservador com N = 4: 36,5 + 47,7 / 4 = 36,5 + 11,9 = 48,4 ms.

| Cenário | N=3 | N=4 | N=5 | 50% da região numa base só | Limite (N infinito) |
|---|---|---|---|---|---|
| Otimista | 46,4 ms (20 TPS) | 41,7 ms (20) | 38,9 ms (20) | 27,6 + 28,3 = 55,9 ms (17,9 TPS) | 27,6 ms |
| Conservador | 52,4 ms (19,1) | 48,4 ms (20) | 46,0 ms (20) | 36,5 + 23,9 = 60,3 ms (16,6) | 36,5 ms |
| B com rede por região | 41,9 ms (20) | 36,6 ms (20) | 33,4 ms (20) | 20,8 + 31,7 = 52,5 ms (19,1) | 20,8 ms |

- "50% numa base só": a região pesada não se divide; com N ≥ 2 o tempo da fase paralela
  é 0,5 × R, qualquer que seja N. Só o Degrau A (pular ticks dessa região) resolve.
- Perda por concorrência: se a parte paralela rodar 30% menos eficiente (cache, memória,
  travas; o MCMT perdeu 20 a 50%), conservador com N = 4 vira 36,5 + 11,9 / 0,7 = 53,5 ms (18,7 TPS).
- Por que N = 3 a 5: a máquina tem 7 vCores; a thread principal já usa 1, o "File IO Thread"
  gira ~1 núcleo (§6), mais GC, Netty, Distant Horizons e ~380 grupos de threads.

**Degrau A sozinho** (uma thread; a região pesada, com 50% de R = 28,3 ms, tica 1 vez a cada k):
média = 84,2 − 28,3 × (1 − 1/k).
- k = 2: 84,2 − 14,2 = 70,0 ms em média (14,3 TPS); ticks alternam 84,2 e 55,9 ms.
- k = 4: 84,2 − 21,2 = 63,0 ms (15,9 TPS).
- Mesmo sem a região pesada (k infinito): 55,9 ms (17,9 TPS).
- Para chegar a 50 ms só com o Degrau A, a região pulada teria que concentrar
  (84,2 − 50) / 56,6 = **60% de todo o trabalho de região**. O perfil não diz se isso é verdade (§8).

## 4. Quem gasta (donos, tile entities, entidades, eventos, plugins)

**Top 25 donos por tempo próprio** (`spark_report.py donos`; "dono" = pacote de mod mais
próximo da folha da pilha, então código Java chamado por um mod conta para o mod):

| # | Dono | % | # | Dono | % |
|---|---|---:|---|---|---:|
| 1 | vanilla/forge/bukkit | 15,96 | 14 | fossilsarcheology.server.entity | 1,42 |
| 2 | mekanism.api | 8,41 | 15 | net.bdew.lib (Gendustry) | 1,39 |
| 3 | native (stubs de chamada, libc) | 5,15 | 16 | libc nativa | 1,38 |
| 4 | thermos.wrapper (mapa de chunks do Crucible) | 3,20 | 17 | mekanism.common.multiblock | 1,23 |
| 5 | crazypants.enderio.conduit | 2,39 | 18 | appeng.container.implementations | 1,21 |
| 6 | libjvm.so | 2,35 | 19 | vazkii.botania.common | 1,19 |
| 7 | am2.AMEventHandler (Ars Magica 2) | 2,19 | 20 | cofh.asmhooks | 1,05 |
| 8 | mekanism.common.content | 2,05 | 21 | betterquesting.questing | 1,04 |
| 9 | mekanism.common.tile | 1,94 | 22 | br.com.redeakashic | 0,98 |
| 10 | com.rwtema.extrautils | 1,87 | 23 | DelirusCrux.Netherlicious | 0,94 |
| 11 | appeng.util.item | 1,75 | 24 | com.mitchej123.hodgepodge | 0,89 |
| 12 | com.akashic.worldfirewall | 1,62 | 25 | net.tclproject.mysteriumlib | 0,88 |
| 13 | Reika.DragonAPI.Instantiable | 1,53 | | (Mekanism somado: ~13,6) | |

Nota: `thermos.wrapper.ChunkBlockHashMap.get` sozinho custa 3,63% (inclusivo). Ele usa um
cache de 4 últimos chunks em campos comuns (`last1`..`last4`, arquivo
`src/main/java/thermos/wrapper/ChunkBlockHashMap.java` linhas 105 a 130 no commit 67b53034)
sem nenhuma proteção entre threads: precisa virar por thread/região antes do Degrau B.
`com.akashic.worldfirewall` custa 1,62% próprio, quase tudo em `FirewallConfig.policyFor` (0,86%).

**Top 20 tile entities** (classe concreta quando a base delega; % da thread, ms/tick):
Mekanism InductionPort 3,56 / 3,00; Gendustry Apiary (via `net.bdew.lib.Event0`) 2,63 / 2,22;
Mekanism QuantumEntangloporter 1,97 / 1,66; Mekanism base `TileEntityBasicBlock` (resto) 1,87 / 1,58;
ForgeMultipart `TileMultipart` (cabos Mekanism dentro) 1,63 / 1,37; EnderIO (conduítes) 1,38 / 1,16;
Mekanism InductionCasing 1,33 / 1,12; DragonAPI base (RotaryCraft, ChromatiCraft) 1,13 / 0,95;
MFR Harvester 0,85 / 0,72; ExtraUtils TransferNodeInventory 0,75 / 0,63; ExtraUtils
TransferNodeLiquid 0,70 / 0,59; Immersive Engineering GardenCloche 0,62 / 0,52; Mekanism
Factory 0,61 / 0,51; EnderStorage `TileFrequencyOwner` 0,46 / 0,39; ExtraUtils
RetrievalNodeInventory 0,46 / 0,39; Mekanism BoilerCasing 0,43 / 0,36; TuboRapido
TuboUniversal 0,38 / 0,32; Mekanism `TileComponentSecurity` 0,29 / 0,24; Thermal Expansion
Assembler 0,28 / 0,24; MFR base `TileEntityFactoryPowered` 0,27 / 0,22.
Total de tick de tile entity: 155,5 s, 26,0% da thread, 157 classes (`spark_report.py tiles`).

**Top 15 entidades** (`spark_report.py ents`, por classe do primeiro `onUpdate`; total
26,6% porque inclui o tick de jogador que vem pela rede): EntityPlayer 7,14; EntityLiving
(mobs que não sobrescrevem onUpdate) 6,31; EntityMob 2,31; Fossils `EntityPrehistoric` 1,76;
EntityZombie 1,56; EntityPlayerMP 1,19; EntityCreeper 1,08; EntityItem 0,64; Twilight
`EntityTFTinyBird` 0,60; EntityHorse 0,36; Thaumcraft `EntityWisp` 0,34; EntitySpider 0,30;
Twilight `EntityTFBlockGoblin` 0,28; EntitySlime 0,21; Citizens `EntityHumanNPC` 0,20.
Dentro do tick de entidade, `ForgeHooks.onLivingUpdate` (handlers de mod) pesa bastante:
só o Ars Magica 2 (`AMEventHandler.onEntityLiving`) custa 3,57%.

**Top 20 handlers de evento Forge** (`perfil.py handlers`; o mod vem do quadro filho do
`ASMEventHandler`; todos somados: 33,67% da thread):

| Handler (evento) | Mod (classe filha) | % | Classe |
|---|---|---:|---|
| EventHandler.onServerTick (pré 2,95 + pós 1,66) | BetterQuesting + bq_standard | 4,62 | G |
| AMEventHandler.onEntityLiving (LivingUpdate) | Ars Magica 2 | 3,57 | R |
| EventHandler.tick (WorldTick) | Uncomplication, rede IC2 (`aroma1997.uncomplication.enet`) | 2,72 | G |
| TransmitterNetworkRegistry.onTick (ServerTick) | Mekanism | 2,18 | G |
| NetworkHandler.serverPacket (CustomPacket) | AE2 | 2,05 | N global |
| TickHandler.onTick | AE2 (`appeng.hooks.TickHandler`, `Grid.update`) | 1,92 | G |
| ConduitNetworkTickHandler.onServerTick | EnderIO | 1,40 | G |
| NetherliciousEventHandler.livingUpdate | Netherlicious | 0,99 | R |
| Simulator.tick (ServerTick) | Electrical Age | 0,98 | G |
| PotionEmptiness.onSpawn (CheckSpawn) | Botania | 0,89 | R |
| PlayerAetherEvents.onPlayerAetherUpdate | Aether | 0,86 | R |
| CommonWorldTickHandler.onTick (inclui MekThrottle) | Mekanism | 0,81 | G |
| EquipmentEvents.equipped | allthemodiumlegacy (redeakashic) | 0,80 | R |
| PetroleumSurfaceMovement.onLivingUpdate | Immersive Petroleum | 0,60 | R |
| AetherEventHandler.onWorldTick | Aether | 0,45 | G |
| PGCommonEventHandler.tickContingencySpell | AM2 Playground | 0,41 | R |
| ServerEventHandler.onPostWorldTick | Et Futurum | 0,39 | G |
| TickHandler.onWorldTick | IC2 | 0,38 | G |
| EventHandlerRing.entTick | ExtraUtils (anel de anjo) | 0,37 | R |
| BukkitHologramBridge.onServerTick | Kunftig (akashic, ponte Bukkit) | 0,33 | P |

**Plugins Bukkit** (`perfil.py bukkit`): custo pequeno, **~2,9% no total**.
- Agendador (1,86%, 1,56 ms/tick): Expeditions (akashic) 0,70; AkashicFishing 0,31;
  HolographicDisplays 0,25; Overkill (akashic) 0,24; protocolobelisk 0,09; TemporaryKits 0,09.
- Listeners via `SimplePluginManager.callEvent` (0,65%, 0,55 ms/tick, já contado dentro de R/N):
  WorldGuard 0,18; GriefPrevention+ (`net.kaikk.mc.gpp`) 0,06; skript-mirror 0,04;
  AncientGates 0,04; ChestShop 0,03; WorldEdit 0,02; AkashicFishing 0,02. Chamadores:
  `processPlayer` 0,16, vizinho de bloco 0,14, fluido 0,10, `callPlayerInteractEvent` 0,09.
- Mais a ponte de hologramas no ServerTick (0,33%).

## 5. Etapa 1 (tirar IO da thread principal): vale a pena?

União sem contar duas vezes (`perfil.py`, script de categorias): **3,45% do tick = 2,9 ms/tick**.
- Carregar chunk (`ChunkProviderServer.loadChunk`): 1,72% no total; dentro dele: população
  de chunk (`Chunk.populateChunk`) 1,08, leitura do disco (`AnvilChunkLoader`) 0,32,
  carregamento síncrono forçado (`ChunkIOExecutor.syncChunkLoad`) 0,39.
- 0,98 ponto do carregamento é disparado pelo **spawn de mobs** (`perfil.py sob`).
- Geração de terreno novo: quase zero (gerador do Twilight 44 ms em 10 min).
  População de chunks já gerados: ~1,2%. Hodgepodge adia população (`ChunkGenScheduler` 0,33%).
- Luz: `updateLightByType` 1,33% e `computeLightValue` 0,76% (com sobreposição); boa parte
  dentro da população (0,78). Fora disso, ~1,1%.
- Salvamento: 0,33% (`saveAllWorlds` 0,16, `saveChunk` 0,09). O SaveRelief já grava em
  thread própria ("SaveRelief IO").
- Concentração no tempo: 7,0 s de carregamento e 7,1 s de luz na janela 0 contra ~0,3 s nas outras.

Conclusão: no regime normal, a Etapa 1 ganha no máximo ~3% (2,9 ms de 84 ms). Ela não é o
gargalo. Vale só como alvo pontual de picos (login/teleporte/exploração, janela 0) e para
remover o carregamento de chunk disparado por spawn de mob, que é barato de limitar.

## 6. Outras threads: falta CPU?

Classifiquei folha por folha: espera = `Unsafe.park`, `Object.wait`, `EPoll.wait`, sono,
futex/libc sob park, bloqueio de safepoint (`<trabalho>/tools/threads2.py`, `perfil.py folhas`).

| Thread | Parede | Rodando (estimativa) | Situação |
|---|---:|---:|---|
| Server thread | 599 s | ~580 s (~0,97 núcleo) | sempre ocupada: é o gargalo |
| **File IO Thread** | 599 s | ~596 s (~1 núcleo) | 95,3% em `Thread.yield` → `__sched_yield`, trocado pelo mixin do Hodgepodge `noSleepForTheWicked`: é um laço que gira sem dormir |
| GC Thread (x7) | 4196 s | ~13 s | quase tudo em espera (libc/futex) |
| G1 Conc (x2) | 1199 s | ~59 s (~0,1 núcleo) | marcação concorrente, leve |
| Netty IO (x7) | 4192 s | ~12 s | 99% em `EPoll.wait` (esperando) |
| Distant Horizons (`DH-*`, muitas) | ~138.900 s | ~159 s (~0,27 núcleo) | maioria dormindo; trabalho real em leitura de dados LOD |
| Craft Scheduler Thread (x7) | 1652 s | < 1 s | 98% em `SynchronousQueue.poll` (esperando) |
| luckperms-worker (x15) | 8991 s | < 1 s | park / `ForkJoinPool.awaitWork` (esperando) |
| C2 compilador JIT | 600 s | ~14 s | leve |

- Soma estimada de CPU: ~2,4 a 2,8 núcleos de 7. O spark mede 37% a 44% por janela
  (`cpuProcess`, 2,6 a 3,1 núcleos). **Não falta CPU no total**: sobram ~4 núcleos.
  O servidor é limitado por **uma** thread.
- O "File IO Thread" girando pode estar queimando ~1 núcleo de cota do contêiner
  (estimativa; nao verificado se o host cobra `sched_yield` como CPU cheia).
- GC: 1600 coletas jovens × 41,5 ms = 66,4 s em 7,3 h (26.280 s) = **0,25%** do tempo;
  pausas do "G1 Concurrent" 598 × 17,9 ms = 10,7 s = 0,04%. Total ~0,3%. Na janela,
  `native.GC_active` na thread principal = 0,38%. GC não é problema; cada coleta soma ~41 ms a um tick.

## 7. Picos de MSPT

Usei `times[]` por janela de 1 minuto (`perfil.py janelas`, `difjanela`, `difinc`).
A thread tem 59,9 s em toda janela, então um pico aparece como fase que "rouba" tempo das outras.

| Janela | MSPT máx | O que cresceu contra a janela calma 2 (máx 183 ms) |
|---|---:|---|
| 0 | 1375 | carregar chunk 7,0 s (vs 0,3), população 6,9 s, luz 7,1 s, spawn 7,4 s (vs 1,4), mapa mágico do Twilight no inventário (`ItemTFMagicMap.updateMapData`) 9,4 s no tick do jogador |
| 4 | 1475 | `saveAllWorlds` 0,9 s (só nesta janela): provável autosave |
| 5 | **5219** | rede 13,1 s (vs 3,6): 7,2 s em AE2 terminal de crafting (`PacketInventoryAction` → `SlotCraftingTerm.craftItem` → `CraftingManager.findMatchingRecipe`) |

- O pico de 5219 ms quase certamente é um jogador fazendo crafting em massa (shift-clique)
  no terminal de crafting do AE2: cada item procura a receita na lista inteira. Não dá para
  provar que é o mesmo tick (o spark não guarda tick a tick): nao verificado.
- O pico da janela 0 é exploração/teleporte: chunk carregado e populado na thread principal,
  com spawn de mob puxando chunk, e o mapa mágico do Twilight (1,89% no total dos 10 min).

## 8. Limites desta medida

- 8 jogadores, não um pico; 1 amostra de 10 min; 7,3 h depois do boot.
- O spark não separa por dimensão nem por posição. Não achei forma de dividir o tempo por
  mundo na árvore. O metadado só traz contagem de entidades por mundo e por aglomerado no
  momento da exportação (765 entidades em 4 mundos: Twilight Forest 460, sendo 401 num aglomerado de
  136 chunks; Overworld 154; Nether 145). Isso é contagem, não tempo.
- Para saber se uma base concentra 60% do trabalho (o que decide o Degrau A, §3), falta:
  `/forge tps` (tempo médio por dimensão) durante o pico; timings do Crucible
  (`co.aikar.timings` existe no tree) por mundo; ou, melhor, o contador por região do
  Hoshiestar (Etapa 0/2) somando o tempo de tick por chunk/região.
- Classificar como "R com estado global" foi feito por leitura dos nomes de classe e do mod;
  não auditei o código de cada mod.
- MSPT do spark (71 ms) e tempo de parede por tick (84,2 ms) diferem; usei 84,2 ms.

## 9. Cinco conclusões para o plano

1. **Paralelizar regiões pode render, mas tem teto baixo.** R = 67% do tick; parte serial
   de 25% a 43%. Limite teórico: 2,3× a 4×. Com N = 4 e carga equilibrada, o Degrau B leva
   84 ms para 37 a 48 ms (estimativa). O primeiro trabalho do Degrau B é diminuir a parte G
   (18,4%, 15,5 ms) e decidir a rede (10,3%), não só criar threads.
2. **O Degrau A sozinho não entrega 20 TPS nesta carga.** Mesmo removendo uma base com 50%
   do trabalho de região, sobram 55,9 ms. Só chega a 50 ms se uma região tiver ≥ 60% do
   trabalho de região. Medir isso (§8) é pré-requisito do portão "vai / não vai".
3. **A Etapa 1 é pequena aqui: 3,45% do tick (2,9 ms).** Vale como correção de picos
   (janela 0) e para impedir o spawn de mob de carregar chunk (0,98%), não como etapa grande.
4. **Há ganhos baratos antes de qualquer thread** (estimativas de teto, não promessas):
   leitura da matriz de indução do Mekanism 8,27% (7,0 ms), BetterQuesting 4,62%
   (3,9 ms), Ars Magica 2 em LivingUpdate 3,57% (3,0 ms), mapa de chunks do Crucible 3,63%
   (3,1 ms), crafting em massa do AE2 (pico de 5,2 s). Juntos ~20% do tick; parte é cacheável.
5. **CPU sobra (~4 de 7 núcleos), mas há um núcleo desperdiçado e dados compartilhados
   perigosos.** O "File IO Thread" gira em `sched_yield` (opção `noSleepForTheWicked` do
   Hodgepodge, nome da chave de config nao verificado) e o cache `last1..last4` do
   `ChunkBlockHashMap` não é seguro entre threads. Os dois entram na lista de pré-requisitos
   do Degrau B, junto com os 10,6% de tile entities com estado global, que começam seriais.

---

## Revisão independente (outro agente, instruído a tentar refutar)

Resultado: confirmado: 10, impreciso: 2.

Recalculei tudo do JSON bruto com scripts próprios em <trabalho>/tools/rev/. Os números centrais se sustentam: base de 84,2 ms/tick, tabela de fases, classificação R/G/N/P/I, aritmética de Amdahl e Degrau A, matriz do Mekanism em 8,27%, pico AE2 na janela 5, File IO Thread em sched_yield, CPU ociosa, GC ~0,3%, ChunkBlockHashMap 3,63%, ranking de handlers. Há dois pontos imprecisos. A soma de IO/chunk/luz é ~2,9% (2,44 ms), não 3,45%. O custo de plugins fica perto de 5%, não 2,9%, porque o ProtocolLib (~2,2%) ficou de fora. A causa da File IO Thread girando está incompleta: é a interação do patch Spigot do Crucible com o redirect ordinal 0 do Hodgepodge. As conclusões gerais do relatório não mudam.

### Afirmações corrigidas

- **impreciso**: 6. Chunk IO, geração, luz e salvamento somam 3,45% (2,9 ms), concentrados na janela 0; spawn dispara 0,98% de carregamento
  - Correção: Minha união sem dupla contagem (loadChunk, populateChunk/populate, AnvilChunkLoader/RegionFile, syncChunkLoad, ChunkGenScheduler, updateLightByType/computeLightValue/func_147451_t, saveAllWorlds/safeSaveChunk/saveAllPlayerData/saveChunk, unloadQueuedChunks, ChunkIOExecutor.tick) deu 2,90% (com variação de nomes, até 2,95%), ou seja 2,44 ms/tick, e não 3,45%/2,9 ms. A diferença é cerca de 16% relativa. A causa provável: o relatório põe ~1,1% de luz fora da população, mas a luz total é 1,34%, dos quais 0,78 caem dentro de carregar/popular. Fora disso sobram 0,56. O salvamento é ~0,17 a 0,24, não 0,33. Confirmado: spawn -> loadChunk = 0,977% e concentração na janela 0 (IO 10,3 s contra 0,5 a 1,6 s nas outras; loadChunk 7,0 s; luz 7,1 s).
- **impreciso**: 11. Plugins Bukkit custam ~2,9% no total: scheduler 1,86, listeners 0,65 (WorldGuard 0,18), ponte de hologramas 0,33
  - Correção: Os itens listados batem: scheduler 1,857 (Expeditions 0,699, AkashicFishing 0,301, HolographicDisplays 0,244, Overkill 0,237); callEvent 0,649 (WorldGuard 0,18); BukkitHologramBridge 0,329. Mas o total deixa de fora o ProtocolLib na thread principal: com.comphenix.protocol.injector.netty.EventLoopProxy.execute = 2,19% ao enviar pacotes (NetHandlerPlayServer.sendPacket e NetworkDispatcher.sendProxy). Desses, 1,71% é ChannelInjector.processSending (ouvintes de pacote do plugin, com Throwable.fillInStackTrace visível). Também ficou de fora o NPC do Citizens (EntityHumanNPC.onUpdate, 0,20). O custo real de plugins fica perto de 4,7 a 5,1%, não 2,9%.

### O que o relatório deixou de fora ou errou

- Causa da File IO Thread girando: não é só o Hodgepodge. O patch Spigot do Crucible remove o sleep entre itens, e com isso o @Redirect ordinal=0 do Hodgepodge cai no Thread.sleep(25L) da fila vazia (build/Crucible/eclipse/cauldron/.../ThreadedFileIOBase.java:56). A thread gira justamente quando está ociosa. A correção certa é no Crucible ou desligando o mixin noSleep, e isso vale nota no plano.
- O ProtocolLib custa ~2,2% da thread principal (1,71% em ChannelInjector.processSending), dentro da fase de rede e do envio de pacotes. O relatório não menciona isso e por isso subestima o custo de plugins (cerca de 5%, não 2,9%). Para o Degrau C com rede por região, os ouvintes de pacote do ProtocolLib são estado global.
- Janela 4 (pico de 1475 ms): o relatório diz 'provável autosave' (saveAllWorlds 0,93 s), mas a mesma janela tem 2,36 s de AE2 PacketInventoryAction/craftItem e rede de 6,1 s contra 3,6 s. O crafting em massa do AE2 é uma explicação pelo menos tão provável quanto o autosave.
- Os multiblocos Mekanism de caldeira e turbina (~1,4%) usam o mesmo mecanismo da matriz de indução e ficaram como 'R puro'. Pelo critério do próprio relatório, 'R com estado global' iria de 10,6 para ~12%.
- O msptMax por janela parece se repetir de uma janela para a seguinte (1375 nas janelas 0 e 1; 647 nas janelas 7 e 8), então pode ser uma medida rolante. Atribuir o pico a uma janela exige cuidado.
- O tempo de IO e luz dentro de R (carregamento de chunk 1,8%, luz fora da população 0,56%) não sai da parte paralela no Amdahl. No Degrau B, carregamento de chunk vindo de threads de região exige um provedor de chunks seguro entre threads ou serialização.
- O relatório diz que getEnergy custa 8,27%. getEnergy sozinho custa 7,03%; os 8,27% somam todos os métodos de SynchronizedMatrixData (setEnergy 1,24).
