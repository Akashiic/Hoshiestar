# Crucible por dentro: como Forge e CraftBukkit são costurados

Estudo para o Hoshiestar. Base lida: clone `Crucible-staging` (ponta `cd1de3d`), conferido contra
o commit de produção `67b53034`. Abreviação usada: `R/` = raiz do clone.
"Linha N do patch" significa a linha N do arquivo `.patch`, não do Java final.

Termos: **patch** = arquivo diff (diferença) aplicado sobre o código decompilado do Minecraft.
**Tick** = um passo do jogo (50 ms). **TE** (tile entity) = bloco com lógica própria (máquina, baú).
**Estado global** = dado guardado em campo `static`, visto por qualquer thread.

Diferença produção x ponta: entre `67b53034` e `cd1de3d` há 21 commits (`git log --oneline 67b53034..cd1de3d | wc -l`).
Nenhum deles toca `World.java.patch`, `WorldServer.java.patch`, `CauldronHooks.java`, `ActivationRange.java`
nem os arquivos de config (`git diff --stat 67b53034 cd1de3d`). Mudam: `MinecraftServer`, `ServerConfigurationManager`,
`S21PacketChunkData`, `S26PacketMapChunkBulk`, `SpawnerAnimals`, handshake FML e bootstrap.

## 1. Layout do repositório

### 1.1 Como o código final é montado
`R/buildSrc/src/main/java/io/github/cruciblemc/forgegradle/CrucibleDevPlugin.java:105-133` monta o código em estágios:
1. Minecraft decompilado (MCP 9.08) + patches FML (`R/forge/fml/patches/minecraft`, 68 arquivos);
2. patches Forge (`R/forge/patches/minecraft`, 243 arquivos) + fontes Forge;
3. fontes da API Bukkit (`R/bukkit/src/main/java`, 645 arquivos `.java`);
4. por cima de tudo, os patches do Crucible (`R/patches`, constante `EXTRA_PATCH_DIR`, `DevConstants.java:148`);
5. `R/src/main/java` (406 arquivos) entra como código novo (`DevConstants.java:149`, `CrucibleDevPlugin.java:213`).

Consequência: um patch do Crucible pode alterar Minecraft, FML, Forge **e** a API Bukkit, no mesmo formato.

### 1.2 `R/patches` (406 patches; +19.434 / -3.273 linhas)
| Pasta | Patches | O que altera |
|---|---|---|
| `net/minecraft/**` | 301 | Minecraft vanilla (entity 75, block 53, world 37, item 27, inventory 21, network 17, tileentity 17, server 16, nbt 13, outros) |
| `net/minecraftforge/**` | 19 | Forge (ForgeEventFactory, ForgeHooks, DimensionManager, ForgeChunkManager, BlockEvent, oredict...) |
| `cpw/mods/fml/**` | 31 | FML (EventBus, ASMEventHandler, GameData, Loader, rede/handshake, relauncher 4) |
| `org/bukkit/**` | 55 | API Bukkit (Material, PluginClassLoader, Player, World, comandos padrão 13...) |

Contagem: `find R/patches/<pasta> -name '*.patch' | wc -l`. Linhas: soma de linhas que começam com `+`/`-` simples
(script em `<trabalho>/patchstats.txt`).

### 1.3 `R/src/main/java` (406 arquivos)
| Pacote | Arq. | Papel |
|---|---|---|
| `org/bukkit/craftbukkit/v1_7_R4/**` | ~227 | Implementação CraftBukkit: `CraftServer`, `CraftWorld`, `entity/` (81), `inventory/` (32), `event/CraftEventFactory`, `scheduler/`, `util/` |
| `net/minecraftforge/cauldron/**` | 37 | Cola Forge-Bukkit herdada do Cauldron: `CauldronHooks`, caches de tick, `configuration/` (16), inventário custom, comandos |
| `io/github/crucible/**` | 66 | Código próprio Crucible: `CrucibleConfigs` (Crucible.yml), bootstrap/bibliotecas, `util/config` (mapeador YAML), `BukkitEnumHelper`, `CrucibleEventBus`, `wrapper/ModInventoryWrapper` |
| `org/spigotmc/**` | 14 | Spigot: `SpigotConfig`, `SpigotWorldConfig`, `ActivationRange`, `TickLimiter`, `WatchdogThread`, `TrackingRange` |
| `co/aikar/timings` + `util` | 24 | TimingsV2 (medição por evento, TE, entidade) |
| `thermos/**` | 10 | Herança Thermos: `ThermosRemapper`, mapas de chunk (`VanillaChunkHashMap`, `ChunkBlockHashMap`), teleporte |
| `net/md_5/bungee/**` | 12 | API de chat Bungee |
| `net/minecraft/**` | 6 | Classes novas em pacote vanilla (ex.: `ContainerEnchantTableInventory`) |

### 1.4 Outras pastas
- `R/bukkit/`: fonte da API Bukkit (645 `.java`), que os patches `org/bukkit` alteram.
- `R/forge/`: Forge 10.13.4.1614 e FML com seus próprios patches (`build.gradle.kts:74,180`).
- `R/buildSrc/`: plugin Gradle próprio (24 `.java`): aplicação de patches, decompilação determinística,
  `reobf/JarRemapperWrapper`, e `com/juanmuscaria/uncode` (remove código cliente).

### 1.5 Top 25 patches por linhas adicionadas (onde vamos colidir)
| # | Patch | + | - |
|---|---|---|---|
| 1 | net/minecraft/network/NetHandlerPlayServer | 1399 | 89 |
| 2 | net/minecraft/world/World | 940 | 100 |
| 3 | net/minecraft/server/management/ServerConfigurationManager | 723 (703 em 67b53034) | 94 |
| 4 | net/minecraft/server/MinecraftServer | 667 | 94 |
| 5 | net/minecraft/entity/player/EntityPlayerMP | 478 | 64 |
| 6 | net/minecraft/entity/Entity | 462 | 36 |
| 7 | org/bukkit/plugin/java/PluginClassLoader | 450 | 15 |
| 8 | net/minecraft/world/WorldServer | 406 | 53 |
| 9 | net/minecraft/entity/EntityLivingBase | 389 | 45 |
| 10 | org/bukkit/entity/Player | 351 | 15 |
| 11 | net/minecraft/world/Teleporter | 344 | 176 |
| 12 | net/minecraft/entity/EntityTracker | 328 | 282 |
| 13 | net/minecraft/init/Bootstrap (eventos de dispenser) | 284 | 18 |
| 14 | net/minecraftforge/event/ForgeEventFactory | 271 | 28 |
| 15 | net/minecraft/world/gen/ChunkProviderServer | 264 | 59 |
| 16 | net/minecraft/entity/player/EntityPlayer | 241 | 37 |
| 17 | net/minecraft/network/play/server/S21PacketChunkData | 223 (105 em 67b53034) | 10 |
| 18 | net/minecraftforge/common/DimensionManager | 219 | 38 |
| 19 | org/bukkit/Material | 202 | 4 |
| 20 | net/minecraft/world/chunk/storage/RegionFile | 199 | 243 |
| 21 | org/bukkit/Effect | 193 | 8 |
| 22 | net/minecraft/world/chunk/Chunk | 190 | 28 |
| 23 | org/bukkit/util/Vector | 169 | 0 |
| 24 | net/minecraft/tileentity/TileEntityHopper | 168 | 5 |
| 25 | net/minecraft/world/chunk/storage/ExtendedBlockStorage | 166 | 5 |

Para o Hoshiestar, os perigosos são World, WorldServer, MinecraftServer, Entity, ChunkProviderServer, Chunk,
EntityTracker e DimensionManager: é exatamente onde o tick por região tem de entrar.

## 2. Mecanismos

### 2.1 Carregamento de plugins e remapeamento de bytecode
- `R/patches/org/bukkit/plugin/java/PluginClassLoader.java.patch`: o carregador de classes de cada plugin.
  - Linhas 86-100: mapa `static jarMappings` (ConcurrentHashMap) e flags `F_REMAP_NMS1710`, `F_REMAP_OBC...`, `F_REMAP_GUAVA`.
  - Linhas 117-162: lê de `cauldron.yml` as chaves `plugin-settings.default.remap-*` e por plugin `plugin-settings.<nome>.remap-*`.
  - Linhas 197-221: cria `JarMapping` (SpecialSource, biblioteca do md_5 que traduz nomes),
    herança global, `thermos.ThermosRemapper` e `RemapperProcessor` (remapeia chamadas de reflexão
    `getField`/`Class.forName` quando `remap-reflect-field/class` = true).
  - Linhas 263-300 (ex.: 272): carrega `mappings/<versao>/cb2pkgmcp.srg` e `obf2pkgmcp.srg`: nomes NMS/OBC do plugin
    (`net.minecraft.server.v1_7_R4.X`) viram nomes MCP (`net.minecraft.world.World`).
  - Linhas 414-430 (417): classe NMS pedida pelo plugin é traduzida e buscada no `LaunchClassLoader` do Forge.
  - Linhas 476-560 (`remappedFindClass`, `defineClass` na 555): bytes da classe passam por `RemapperProcessor.process`,
    `remapper.remapClassFile`, opcionalmente pelos transformers do LaunchWrapper, `CruciblePluginTransform`
    e RFB, e só então `defineClass`. Mapa `classes` virou ConcurrentHashMap (linha 69).
- `R/src/main/java/thermos/ThermosRemapper.java`: só engole exceções de assinatura.
- Plugins ouvindo eventos Forge: `io/github/crucible/api/CrucibleEventBus.java:37-91` gera, com ASM,
  um `IEventListener` por método (`eventfactory/PluginClassLoaderFactory.java`, cache `static HashMap cache`, linha 21)
  e registra no barramento Forge via `EventBus.crucible_register` (`R/patches/cpw/mods/fml/common/eventhandler/EventBus.java.patch`).

### 2.2 Material, EntityType, Biome e afins para conteúdo de mod
- Toda vez que um bloco/item entra no registro, `RegistryNamespaced.addObject` chama `Material.addMaterial`
  (`R/patches/net/minecraft/util/RegistryNamespaced.java.patch:22`).
- `Material.addMaterial` (`R/patches/org/bukkit/Material.java.patch:124-134`) cria um novo valor de enum com
  `EnumHelper.addEnum` e grava em `static byId[]` e `BY_NAME`.
- Reinjeção antes dos plugins: `SaveHandler.initBukkitData` chama `GameData.injectBlockBukkitMaterials/ItemBukkitMaterials`
  e só depois `loadPlugins()` (`R/patches/net/minecraft/world/storage/SaveHandler.java.patch:205-220`;
  implementação em `R/patches/cpw/mods/fml/common/registry/GameData.java.patch:27-69`).
- `io/github/crucible/util/BukkitEnumHelper.java`: `addBukkitBiome` (usado em `CraftBlock.java:195`),
  `addBukkitEnvironment` (DimensionManager), `addBukkitWorldType` (`WorldType.java.patch:18`),
  `addBukkitEntityType` (`CraftEntity.java:504`), `addInventoryType` (`synchronized`, linha 65), chamado ao
  carregar TEs do disco (`AnvilChunkLoader.java.patch:261`).

### 2.3 Dimensões de mod como mundos Bukkit
- Cada `World` cria seu `CraftWorld` no construtor (`World.java.patch:298`) e guarda configs próprias:
  `spigotConfig`, `cauldronConfig`, `tileentityConfig`, `sushchestvoConfig` (linhas 289-294).
- `DimensionManager.initDimension(int)` (`R/patches/net/minecraftforge/common/DimensionManager.java.patch:165-227`):
  inventa um nome de mundo a partir do `WorldProvider`, registra um `World.Environment` Bukkit novo se preciso,
  permite desligar a dimensão (`world-environment-settings.<tipo>.enabled` em cauldron.yml), usa um `AnvilSaveHandler`
  próprio por dimensão e dispara `WorldEvent.Load` (Forge) e `WorldLoadEvent` (Bukkit).
- `initDimension(WorldCreator, ...)` (linhas 235-300) é o caminho do `Bukkit.createWorld` (`CraftServer.java:930-932`).
- Descarregar passa por `CraftServer.unloadWorld` (patch linha 354). `CraftServer` guarda `LinkedHashMap worlds` (`CraftServer.java:121`).
- Estado estático: `bukkitDims` (ArrayList), `classToProviders` (Hashtable), além dos mapas Forge originais (linhas 36-42).

### 2.4 Inventários
- `IInventory` ganha métodos Bukkit (`getContents`, `onOpen`, `onClose`, `getViewers`, `getOwner`, `setMaxStackSize`):
  `R/patches/net/minecraft/inventory/IInventory.java.patch`. Mods antigos não os implementam; o código trata
  `AbstractMethodError` (ex.: `Container.java.patch:60-75`).
- `Container` ganha `bukkitView`; contêiner de mod normalmente fica com `null`, e `CraftEventFactory.callInventoryOpenEvent`
  testa `getBukkitView() != null` (`CraftEventFactory.java:759-772`). GUIs de mod abertas via FML também passam por ele
  (`R/patches/cpw/mods/fml/common/network/internal/FMLNetworkHandler.java.patch:40`).
- Embrulhos: `io/github/crucible/wrapper/ModInventoryWrapper.java` (usado pelo funil, `TileEntityHopper.java.patch:187`),
  `net/minecraftforge/cauldron/inventory/CraftCustomInventory*.java`, `CustomModRecipe` (receitas OreDict, `ShapedOreRecipe.java.patch:48`).

### 2.5 Ponte de eventos Forge <-> Bukkit
- Quebra e colocação: os **construtores** dos eventos Forge chamam o Bukkit.
  `BlockEvent.BreakEvent` chama `CraftEventFactory.callBlockBreakEvent` e copia cancelamento/XP;
  `PlaceEvent` chama `callBlockPlaceEvent` (`R/patches/net/minecraftforge/event/world/BlockEvent.java.patch:31,56`).
- Interação: `ForgeEventFactory.onPlayerInteract` primeiro dispara o `PlayerInteractEvent` do Bukkit e passa o resultado
  para `onPlayerBukkitInteract`, que copia o cancelamento para o evento Forge antes de postá-lo
  (`ForgeEventFactory.java.patch:160-296`). FakePlayer do CoFH (`[CoFH]`) não gera evento Bukkit (linhas 286-293).
  Observação de leitura: no `switch` (linhas 168-180 e 234-246) falta `break` depois de `LEFT_CLICK_BLOCK`,
  então ele vira `LEFT_CLICK_AIR` no Bukkit (não testado em jogo).
- Física, clima, explosões, dano etc.: chamadas diretas a `CraftEventFactory`/`callEvent` espalhadas nos patches
  (ex.: `BlockPhysicsEvent` em `World.java.patch:679-698`, clima em 1313/1330).
- `EventBus.post` do FML ganhou medição TimingsV2 por ouvinte (`EventBus.java.patch:38-87`).

### 2.6 Captura de blocos para eventos de colocação
- `ForgeHooks.onPlaceItemIntoWorld` (`R/patches/net/minecraftforge/common/ForgeHooks.java.patch:77-250`):
  liga `world.captureBlockSnapshots` (Forge), `captureItemDrops` e, para farinha de osso em muda,
  `captureAndProxyBlocks`; guarda o jogador em `ItemStack.currentPlayer` (estático); chama `onItemUse`;
  em `finally` desliga tudo. Depois gera `StructureGrowEvent` (árvore) ou o `PlaceEvent`/`MultiPlaceEvent`
  e restaura os snapshots se cancelado.
- `World.setBlock` (`World.java.patch:595-640`): se `worldChangeHolder.isCapturing()` (geração de árvore Crucible,
  `io/github/crucible/util/WorldChangeHolder.java`) desvia para um buffer; senão grava `BlockSnapshot`.
  Leitura: com `captureBlockSnapshots` ligado o trecho Cauldron (linhas 618-630) adiciona um segundo snapshot ao
  lado do que o Forge já cria (contexto linha 610). Efeito real nao verificado.
- `World.canPlaceMultipart` para ForgeMultipart usa `ItemStack.currentPlayer` (`World.java.patch:1640-1655`).

### 2.7 FakePlayer e permissões
- `FakePlayerFactory.fakePlayers` virou `public static HashMap` e a busca passou a ser por nome
  (`R/patches/net/minecraftforge/common/util/FakePlayerFactory.java.patch`).
- Qualquer `EntityPlayer` que não seja `EntityPlayerMP` vira `CraftPlayer` de um FakePlayer (`CraftEntity.java:54-60`).
  `CraftPlayer.isOnline()` responde `true` para FakePlayer (`CraftPlayer.java:181`). Permissões vêm do
  `PermissibleBase` normal (`CraftHumanEntity.java:83-105`), ou seja, o plugin de permissões decide pelo nome/UUID.
- `ConfigBase.getFakePlayer` lê `fake-players.<classe>.username` (`ConfigBase.java:132-134`).

### 2.8 Carga e descarga de chunks
- `ChunkProviderServer.provideChunk` (`ChunkProviderServer.java.patch:297`): chunk ausente é **carregado de forma síncrona**
  se `shouldLoadChunk()` (linhas 577-582): `findingSpawnPoint`, ou `loadChunkOnProvideRequest`
  (`Crucible.yml cauldron.settings.loadChunkOnRequest`, padrão `true`, `CrucibleConfigs.java:19`),
  ou durante evento de tick Forge com `loadChunkOnForgeTick`, ou nos primeiros 100 ticks do provedor.
  Todo acesso grava `chunk.lastAccessedTick = tickCounter` (linha 327).
- Descarga (linhas 464-550): tira da fila os chunks forçados por `ForgeChunkManager`, processa até 100 por tick,
  pula chunks acessados há menos de `chunkGCGracePeriod` ticks se houver jogadores (linhas 506-511, 592-595; padrão 0,
  `CrucibleConfigs.java:37`), dispara `ChunkUnloadEvent` e usa `ForgeChunkManager.putDormantChunk`.
- `ForgeChunkManager` espelha cada ticket em `world.activity` (ConcurrentHashMap) (`ForgeChunkManager.java.patch:7,15`;
  campo em `World.java.patch:1610`). Isso faz o chunk contar como "ativo" (`isActiveChunk`, linha 1612).
- Mapa de chunks: `thermos.wrapper.VanillaChunkHashMap` com visão vanilla para mods (linhas 88-89).

### 2.9 Sistema de configuração
| Arquivo | Classe | Onde nasce |
|---|---|---|
| `cauldron.yml` | `net/minecraftforge/cauldron/configuration/CauldronConfig` (+ `CauldronWorldConfig`) | `MinecraftServer.java.patch:160` |
| `tileentities.yml` | `TileEntityConfig` (+ `TileEntityWorldConfig`) | linha 161 |
| `entities.yml` | `SushchestvoConfig` (+ `SushchestvoWorldConfig`) ("sushchestvo" = "criatura" em russo) | linha 162 |
| `Crucible.yml` | `io/github/crucible/CrucibleConfigs` (singleton `static final configs`, mapeia campos por anotação) | `CrucibleConfigs.java:258` |
| `spigot.yml` | `org/spigotmc/SpigotConfig` + `SpigotWorldConfig` por mundo | `SpigotConfig.java:23`; `World.java.patch:289` |
| `bukkit.yml`, `commands.yml`, `help.yml` | `CraftServer` | `CraftServer.java:200-212` |

Os `*WorldConfig` não têm arquivo próprio: `WorldConfig.getBoolean/getInt` procuram
`world-settings.<mundo>.<caminho>` e caem em `world-settings.default.<caminho>` (`WorldConfig.java:36-56`).
Várias chaves existem em dois lugares (ex.: `settings.load-chunk-on-request` em `CauldronConfig.java:34` e no
Crucible.yml); o código de chunks lê o Crucible.yml (`ChunkProviderServer.java.patch:83`).

## 3. Controles de tick por TE e por entidade (base do Degrau A)

### 3.1 Onde o tick acontece
`MinecraftServer.updateTimeLightAndEntities` percorre as dimensões em série: `onPreWorldTick`, `worldserver.tick()`,
`updateEntities()`, `onPostWorldTick`, `EntityTracker` (`MinecraftServer.java.patch:688-830`).
Dentro de `World.updateEntities` (func_72939_s):
- **Entidades** (`World.java.patch:983-1028`): `ActivationRange.activateEntities(this)` (987); laço com índice
  rotativo `tickPosition` e `entityLimiter` (995-998); cada entidade vai para `updateEntity` -> `updateEntityWithOptionalForce`.
- **TEs** (`World.java.patch:1029-1101`): primeiro limpa TEs de chunks descarregados (1033-1056); laço com
  `tileTickPosition` e `tileLimiter` (1064-1066); remove inválidas; só chama `updateEntity()` se
  `CauldronHooks.canTileEntityTick(te, this)` e `blockExists` (1088).
- TE entra na lista só se `CauldronHooks.canUpdate(te)`: `te.canUpdate()` e classe fora de
  `MinecraftServer.bannedTileEntityUpdates` (`World.java.patch:1176-1177, 1267-1268, 1588-1590`; `CauldronHooks.java:305-306`).
  A lista de banidas é preenchida ao carregar chunks, quando a classe diz `canUpdate()=true` mas não sobrescreve
  `updateEntity` (`AnvilChunkLoader.java.patch:242-256`, chave `settings.prevent-invalid-tileentity-updates`, padrão true).

### 3.2 Regras por classe de TE (`tileentities.yml`)
`CauldronHooks.canTileEntityTick` (`CauldronHooks.java:268-303`), só se `settings.skip-tileentity-ticks` = true
(padrão **true**, `TileEntityConfig.java:11`):
1. Busca `TileEntityCache` no mapa `static HashMap tileEntityCache`, chave = **classe** (linha 38, 271).
   Na primeira vez lê `world-settings.<mundo>.<classe-com-hifens>.tick-no-players` (padrão false) e `.tick-interval` (padrão 1).
2. Se `tick-no-players` = false e o chunk do TE **não está ativo** -> não tica (linha 280).
   "Ativo" = `World.isActiveChunk` (`World.java.patch:1612-1626`): chunk em `activeChunkSet_CB` ou com ticket ForgeChunkManager.
   `activeChunkSet_CB` é refeito a cada tick (`World.java.patch:1340-1418`, método na 1342): chunks forçados, o chunk de cada jogador,
   e até `chunksPerPlayer = min(200, (chunks-per-tick - jogadores)/jogadores)` chunks **sorteados** num raio igual à
   view-distance (`chunks-per-tick` padrão 650, `SpigotWorldConfig.java:108`).
3. Se `tempoTotalDoMundo % tick-interval == 0` -> tica (linha 285). Senão não tica (exceção abaixo).
4. Exceção de "chunk descarregando" exige `lastAccessedTick < 2` (linhas 289-296). Como `lastAccessedTick` guarda o
   contador absoluto de ticks (`ChunkProviderServer.java.patch:327`), isso na prática só vale no início do servidor.

### 3.3 Regras por classe de entidade (`entities.yml`)
`CauldronHooks.canSushchestvoTick` (`CauldronHooks.java:220-266`), ativo se `settings.skip-entity-ticks` (padrão true,
`SushchestvoConfig.java:11`). Mesmo esquema: mapa `static HashMap sushchestvoCache` por classe (linha 39), chaves
`tick-no-players`, `never-ever-tick`, `tick-interval`. Retorna -1 (nunca), 0 (normal) ou 1 (forçar).
Uso em `updateEntityWithOptionalForce` (`World.java.patch:1206-1236`): `-1` pula o tick (chama `inactiveTick()`);
com `tick-interval: N` e `tick-no-players: false`, a entidade tica 1 de cada N ticks. `tick-no-players: true` faz o
valor virar 1 nos ticks pulados, o que anula o intervalo. Entidade em chunk com ticket (`activity`) ou persistente
conta como `isForced` (linha 1197).

### 3.4 Respostas diretas
- **O admin já consegue desacelerar uma classe de TE?** Sim: `tick-interval: N` em `tileentities.yml` faz a classe
  ticar 1 em N ticks. Para entidades, o mesmo em `entities.yml`, mais `never-ever-tick`.
- **Por mundo?** No YAML sim (`world-settings.<mundo>`), mas o cache é chaveado só pela classe e guarda o nome do
  **primeiro** mundo que ticou aquela classe (`CauldronHooks.java:274-276`; recarga em `TileEntityConfig.java:60-63`
  usa o mesmo `worldName`). Na prática o valor de um mundo vale para todos. Leitura de código, nao verificado em execução.
- **Por chunk ou posição?** Não existe ajuste. Só há granularidade implícita: "perto de jogador" (chunk ativo/sorteado)
  e "chunk com ticket". Nada mede custo por chunk.
- **Efeito colateral importante:** com o padrão (`skip-tileentity-ticks: true`, `tick-no-players: false`), uma TE fora
  do chunk do jogador só tica nos ticks em que seu chunk é sorteado. Com muitos jogadores `chunksPerPlayer` cai e as
  máquinas desaceleram. O valor de produção de `tileentities.yml` nao verificado (arquivo não está em `server-orig/`).

### 3.5 ActivationRange e TickLimiter (Spigot)
- `TickLimiter` (`org/spigotmc/TickLimiter.java`): orçamento de tempo **por mundo** para o laço de entidades e o de TEs
  (`max-tick-time.entity/.tile`, padrão 1000 ms, `SpigotWorldConfig.java:262-263`; criado em `World.java.patch:1665-1668`).
  Confere o relógio a cada 300 itens; ao estourar, o laço para e o próximo tick continua de onde parou
  (`tickPosition`/`tileTickPosition`). É o parente mais próximo do Degrau A, mas pune quem estiver no fim da lista,
  não a base pesada, e com 1000 ms está praticamente desligado.
- `ActivationRange` (`org/spigotmc/ActivationRange.java`): marca entidades perto de jogadores
  (`activateEntities`, linhas 129-158) e `checkIfActive` (linhas 249-285) decide se a entidade "dorme".
  No Crucible quase nunca age, por dois motivos de código:
  (a) `initializeEntityActivationState` retorna `true` sempre que o mundo tem `spigotConfig` (linhas 76-80: o `else return true`
  pega o caso normal), então `defaultActivationState` fica true para quase toda entidade (`Entity.java.patch:140`);
  (b) `checkIfActive` só é consultado quando `canUpdate` é falso (`World.java.patch:1218`).
  Conclusão de leitura, nao verificado em execução.

## 4. Estado global ou compartilhado por mecanismo (risco para threads de região)
| Mecanismo | Estado | Onde | Problema com várias threads |
|---|---|---|---|
| Tick TE/entidade | `tileEntityCache`, `sushchestvoCache` (HashMap) | `CauldronHooks.java:38-39` | `put` concorrente em HashMap |
| Tick TE | `MinecraftServer.bannedTileEntityUpdates` (ArrayList) | `MinecraftServer.java.patch:145`; escrita `AnvilChunkLoader.java.patch:256` | lido em todo `canUpdate`, escrito ao carregar chunk |
| Tick/chunks | `MinecraftServer.currentTick` (static int), `callingForgeTick` | `MinecraftServer.java.patch:122,144` | ActivationRange e chunks usam um relógio único |
| ActivationRange | `maxBB`, `miscBB`, `animalBB`, `monsterBB` estáticos e mutáveis | `ActivationRange.java:40-43` | duas regiões sobrescrevem a mesma caixa |
| Chunk ativo | `activeChunkSet_CB`, `activity`, `tickPosition`, listas do mundo (`HashedArrayList`) | `World.java.patch:125-128,170,216,1610` | por mundo, hoje percorrido por uma thread só |
| Colocação | `ItemStack.currentPlayer`, `BlockSapling.treeType` | `ItemStack.java.patch:45`, `BlockSapling.java.patch:28` | jogador/árvore de uma região vaza para outra |
| Captura | `world.captureBlockSnapshots`, `capturedBlockSnapshots`, `captureAndProxyBlocks`, `worldChangeHolder` | `World.java.patch:180-181,239` | flag por mundo, não por região |
| Eventos | `CraftEventFactory.blockDamage`, `entityDamage` | `CraftEventFactory.java:64-65` | "contexto" passado por campo estático |
| Eventos | `CauldronCommand.debug`, `SideTransformer.allowInvalidSide` | `CauldronCommand.java:27`; `AnvilChunkLoader.java.patch:248` | flags globais |
| Timings | `TimingHandler.TIMING_STACK` (ArrayDeque) | `TimingHandler.java:43` | ignora tudo fora da thread principal (linhas 102,114): medições de região somem |
| Primária | `primaryThread`; `isPrimaryThread()` | `MinecraftServer.java.patch:123`; `CraftServer.java:1599-1600` | API Bukkit e Timings supõem uma thread |
| Plugins | `jarMappings` (Concurrent), `PluginClassLoaderFactory.cache` (HashMap) | patch linha 86; `PluginClassLoaderFactory.java:21` | o segundo não é seguro |
| Enums | `Material.byId`, `BY_NAME`; `BukkitEnumHelper.addInventoryType` (synchronized) | `Material.java.patch:124-134`; `BukkitEnumHelper.java:65` | escrita em runtime ao carregar TE nova |
| Dimensões | `bukkitDims`, `classToProviders`, mapas Forge; `CraftServer.worlds` (LinkedHashMap) | `DimensionManager.java.patch:36-42`; `CraftServer.java:121` | criar/descarregar mundo durante tick paralelo |
| FakePlayer | `FakePlayerFactory.fakePlayers` (HashMap) | `FakePlayerFactory.java.patch` | mods criam FakePlayer dentro do tick de TE |
| Fila | `processQueue` (ConcurrentLinkedQueue), `ChunkIOExecutor` | `MinecraftServer.java.patch:124,694-704` | já seguros, mas executados na fase global |
| Config | `CrucibleConfigs.configs`, `MinecraftServer.*Config` | `CrucibleConfigs.java:20` | só leitura no tick, baixo risco |
| Tracker | `EntityTracker` com `ReentrantLock` próprio | `EntityTracker.java.patch` | já tem trava (Thermos), revisar antes de paralelizar |

Não existe `AsyncCatcher` (verificação de thread do Spigot): `grep -rl AsyncCatcher R/src R/patches` não retorna nada.

## 5. Onde o Hoshiestar terá de mexer
Risco de conflito = chance de um patch nosso brigar com mudanças futuras do upstream Crucible (medida pelo tamanho
do patch e por ele ter mudado nos 21 commits recentes).
| Arquivo | Mecanismo | Risco |
|---|---|---|
| `patches/net/minecraft/world/World.java.patch` (hunks 983-1236, 1340-1418) | laços de entidade/TE, `updateEntityWithOptionalForce`, chunks ativos: ponto de entrada do orçamento por região | Alto (2º maior patch; estável nos 21 commits) |
| `patches/net/minecraft/world/WorldServer.java.patch` (165-233) | `tick()`: spawn, `tickUpdates`, `func_147456_g` (ticks de bloco) por região | Alto |
| `patches/net/minecraft/server/MinecraftServer.java.patch` (628-840) | laço de dimensões, fases global/paralela, `currentTick` | Alto (mudou em `67b53034..cd1de3d`) |
| `src/main/java/net/minecraftforge/cauldron/CauldronHooks.java` | trocar cache por classe por cache por (mundo, classe); orçamento por região | Médio (arquivo próprio, pouco tocado) |
| `.../configuration/TileEntityConfig.java`, `SushchestvoConfig.java`, `WorldConfig.java` | novas chaves (orçamento, por região) | Baixo |
| `src/main/java/org/spigotmc/TickLimiter.java`, `ActivationRange.java` | reaproveitar o limitador por região; tornar BBs locais | Baixo |
| `patches/net/minecraft/world/gen/ChunkProviderServer.java.patch` | carga síncrona `provideChunk`, descarga, `lastAccessedTick`: dono do chunk por região | Alto |
| `patches/net/minecraft/world/chunk/Chunk.java.patch` | campo de região no chunk, listas de entidades | Médio |
| `patches/net/minecraftforge/common/ForgeChunkManager.java.patch` + `World.activity` | tickets definem regiões "sempre ativas" | Baixo (patch de 2 linhas) |
| `patches/net/minecraft/entity/Entity.java.patch` | movimento entre regiões, `activatedTick` | Alto |
| `patches/net/minecraft/entity/EntityTracker.java.patch` | rastreamento entre regiões | Alto (reescrita grande: +328/-282) |
| `patches/net/minecraftforge/common/ForgeHooks.java.patch`, `ItemStack.java.patch`, `BlockSapling.java.patch` | estáticos de colocação -> por thread | Médio |
| `src/main/java/org/bukkit/craftbukkit/v1_7_R4/event/CraftEventFactory.java` | `blockDamage`/`entityDamage` -> por thread | Médio |
| `src/main/java/co/aikar/timings/TimingHandler.java` | medir por região (hoje descarta fora da thread principal) | Baixo |
| `CraftServer.java` (`isPrimaryThread`) | conceito de "thread dona" para a API Bukkit | Médio |
| `patches/net/minecraftforge/common/DimensionManager.java.patch` | criação/descarga de mundo fora da fase paralela | Médio |

## Não verificado
- Comportamento em execução de tudo acima: nenhuma conclusão foi testada com servidor rodando (regra 6).
- Valores reais de `tileentities.yml`, `entities.yml`, `cauldron.yml`, `Crucible.yml` e `spigot.yml` em produção.
- Detalhes internos do `RemapperProcessor` do SpecialSource 1.10.0 (só vi a configuração no patch).
- Que `World.func_147456_g` vanilla chama `setActivePlayerChunksAndCheckLight` a cada tick (sem fonte decompilado; inferido do hunk em `World.java.patch:1340`).

---

## Revisão independente (outro agente, instruído a tentar refutar)

Resultado: confirmado: 12, impreciso: 2.

As afirmações centrais do relatório se sustentam quando conferidas no código: o filtro de tick de TE por CauldronHooks, o cache estático por classe que fixa o mundo da primeira vez, os padrões true de skip-*-ticks, o ActivationRange praticamente inerte, a carga síncrona de chunk, os estáticos de colocação e eventos, o Timings preso à thread principal e a ausência de AsyncCatcher. Os erros são secundários, mas reais. As linhas dos patches foram contadas com um critério não declarado. A afirmação de que as configs não mudaram entre produção e ponta é falsa (CrucibleConfigs mudou). O addInventoryType é citado como synchronized, mas isso só vale na ponta, não em produção. O TickLimiter confere o relógio a cada cerca de 333 itens, não 300. E a seção de entidades omite que canSushchestvoTick ignora proximidade de jogador e que o -1 vence chunk forçado. Para o design, também faltam a rajada causada pelo intervalo de fase única e o executor de iluminação assíncrona que já existe.

### Afirmações corrigidas

- **impreciso**: 2. 406 patches: 301 net/minecraft, 19 net/minecraftforge, 31 cpw/fml, 55 org/bukkit; +19434/-3273 linhas
  - Correção: As contagens de arquivos batem: 301/19/31/55, total 406, pelo find. As linhas não batem com a descrição do método ('linhas que começam com +/- simples'). A contagem bruta, sem os cabeçalhos +++/---, dá +21.734/-3.440. Sem as linhas em branco dá +19.267/-3.267. O patchstats.txt soma 19.434/3.273 porque o script não conta linhas +/- em branco, e isso não está dito no texto. Exemplo no World.java.patch: 1.034 linhas '+', das quais 94 em branco, o que dá 940. A tabela dos top 25 usa o mesmo critério não declarado. Coincidência que vale anotar: src/main/java também tem 406 arquivos .java.
- **impreciso**: 9. TickLimiter é orçamento por mundo (max-tick-time 1000 ms), confere o relógio a cada 300 itens e retoma por índice rotativo
  - Correção: O padrão de 1000 ms está certo (SpigotWorldConfig.java:262-263). São dois limitadores por mundo, um para entidades e um para TEs (World.java.patch:1665-1668). A retomada por tickPosition/tileTickPosition também está certa (linhas 995-998, 1064-1066). O detalhe errado: shouldContinue() só é chamado quando contador % 10 != 0 (linhas 996 e 1064), e o relógio é conferido a cada 300 chamadas (TickLimiter.java:20). Na prática isso dá cerca de 333 itens por conferência, não 300. Além disso, o orçamento é contado em System.currentTimeMillis, com granularidade de milissegundo.

### O que o relatório deixou de fora ou errou

- BukkitEnumHelper.addInventoryType: o relatório cita 'synchronized, linha 65' como estado atual, mas isso só existe na ponta cd1de3d. No commit de produção 67b53034 o método está na linha 55, sem synchronized e sem cache por classe (git show 67b53034:src/main/java/io/github/crucible/util/BukkitEnumHelper.java). Em produção, cada mundo cria de novo o enum InventoryType para a mesma classe de TE.
- A linha 13 do relatório afirma que 'os arquivos de config' não mudaram entre produção e ponta, mas CrucibleConfigs.java mudou (crucible_tabListPingInterval). A lista de arquivos alterados também está incompleta (BukkitEnumHelper, SnapshotIteratingArrayList, CraftPlayer, GameMode, RegionFileCache, PreCreatureSpawnEvent).
- As contagens de linhas (+19434/-3273 e o top 25) descartam linhas +/- em branco sem dizer isso. A contagem bruta é +21734/-3440.
- Entidades e TEs não seguem 'o mesmo esquema' (seção 3.3). canSushchestvoTick nunca consulta chunk ativo nem proximidade de jogador: com tick-interval 1 (padrão) sempre devolve 0, e com N > 1 devolve -1 nos outros ticks. Esse -1 vence até chunk forçado (o ramo else-if em World.java.patch:1225 não olha isForced). Já a TE, com o padrão, para de ticar fora de chunk ativo.
- tick-interval é aplicado por fase global (getWorldTotalTime() % N == 0): todas as instâncias da classe ticam juntas no mesmo tick, o que gera picos. Isso importa para o Degrau A (orçamento por região), que precisaria escalonar por posição ou por instância.
- Se chunks-per-tick <= 0, setActivePlayerChunksAndCheckLight retorna antes de marcar o chunk dos jogadores. Com skip-tileentity-ticks=true, as TEs passam a ticar só em chunks forçados. O sorteio usa raio view-distance menos 1 e só pega chunks já carregados.
- Já existe um componente multithread por mundo que o relatório não cita: o lightingExecutor do PaperSpigot (World.java.patch:1681, uma thread por mundo, 'PaperSpigot - Lighting Thread'), controlado por use-async-lighting (padrão false, SpigotWorldConfig.java:276) e desligado para provedores de chunk de mod. É relevante para a fase paralela.
- A exceção 'chunk descarregando' (lastAccessedTick < 2) também vale para chunks recém-gerados que nunca passaram por provideChunk, não só para o início do servidor.
- BlockEvent.BreakEvent faz cast (EntityPlayerMP) player no construtor: um mod que crie o evento com um EntityPlayer que não seja MP causa ClassCastException.
- TickLimiter: o relógio é conferido a cada cerca de 333 itens, não 300, porque um em cada dez itens pula a chamada a shouldContinue.
