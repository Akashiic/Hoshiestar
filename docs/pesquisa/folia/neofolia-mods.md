# NeoFolia: o que mudou para conviver com mods

- Projeto lido: NeoFolia, clone raso em `estudo/folia/NeoFolia`
- Commit lido: `fa76006de0c01f5cd0c3290f0994898c39e8441f` (2026-06-04 18:04:56 +0300, "Update README.md")
- Alvo do projeto: Minecraft 26.1.2, Java 25, fork do NeoForge (carregador de mods moderno), licença LGPL 2.1 herdada do NeoForge.
- Histórico (de `neofolia-hist/hist.git`, só mensagens): 22 commits entre 2026-06-03 e 2026-06-04. O código entrou em "First release" (b10c794) e "Folia patch porting and bug fixes" (d1ff8c7). O resto são edições do README. É um projeto de **dois dias de vida**, sem testes de servidor real à vista.
- Forma de leitura: só código e testes de unidade, nada compilado, nenhum servidor ligado. Linhas citadas como "linha N do .patch" são linhas do arquivo de patch, não do fonte Java final.

Termos usados (primeira vez):
- **região**: conjunto de chunks vizinhos que tica junto, numa thread só.
- **worker de região**: thread do grupo de threads que executa as tarefas de uma região de cada vez.
- **thread principal** (ou "global"): a thread original do servidor, que roda `MinecraftServer.tickServer`.
- **fila de região**: lista de tarefas que só podem rodar na região dona.
- **evento de tick**: aviso que o carregador manda aos mods a cada tick (ex.: `ServerTickEvent`, `LevelTickEvent`, `EntityTickEvent`).
- **capability**: interface padrão do NeoForge para "este bloco/entidade guarda itens/fluido/energia"; é por ela que canos e cabos de mods conversam.
- **attachment**: dado extra que um mod pendura num bloco, entidade, chunk ou mundo.
- **payload**: pacote de rede customizado de um mod.

---

## 1. Resumo (10 linhas)

1. O NeoFolia **não mexe no barramento de eventos** e **não põe trava** em volta de handlers de mods: nenhum dos 260 pontos de `EVENT_BUS.post` está protegido.
2. `ServerTickEvent` e `LevelTickEvent` disparam na thread principal; `EntityTickEvent`, `PlayerTickEvent`, ticks de block entity, ticks agendados, ticks de chunk e spawners customizados de mods rodam nos workers de região, em paralelo.
3. **Não há barreira no fim do tick**: `executeAll`/`executeCoalescedAll` só enfileiram e voltam. A thread principal segue (dispara `LevelTickEvent.Post`, `ServerTickEvent.Post`, o próximo tick) enquanto as regiões ainda ticam. Há um teste de unidade que confirma isso de propósito.
4. Proteção existe só **dentro dos workers**: `setBlock`/`setBlockEntity` fora da região vão para a fila da região dona (e devolvem `false`); `getBlockEntity`, `getChunk`, `getEntity`, listas de jogadores e caixas de busca de entidades devolvem `null`/vazio/recortado. A thread principal **não é checada**.
5. Itens, fluidos e energia por capability ganham embrulhos (`Regionized*Handler`) que levam a chamada à região dona. Chamada de um worker para outra região **lança exceção** (não espera, para não travar).
6. Regiões são uma grade fixa de 16x16 chunks, unidas por proximidade (raio 3 regiões = 48 chunks) e recalculadas **a cada tick**; na prática, tudo que está carregado e perto vira uma região só. Isso protege redes vizinhas, não ligações de longa distância.
7. `SavedData` só ganhou mapa concorrente e `setDirty` sincronizado; o conteúdo dos dados dos mods e os attachments de mundo **não são protegidos**.
8. Payloads de mods marcados "thread principal" (o padrão) passam a rodar **na região do jogador**, não na thread principal. Comandos rodam sempre na thread principal.
9. Não há lista de compatibilidade, lista de mods em série, "modo seguro" ou API de agendador em interface; há só métodos públicos concretos (`submitRegionTask`, `RegionizedWorldGuard.*`, `regionizedTaskDispatcher()`). O README promete portar mods "quando o núcleo estiver estável".
10. Não há chave para desligar tudo: o despachante sempre liga com pelo menos 1 worker; os 4 interruptores do `neofolia.toml` só desligam categorias de tick. Issues no GitHub: não verificado (acesso bloqueado).

---

## 2. Respostas por pergunta

### 2.1 Barramento de eventos: em que thread dispara cada evento de tick

**Resposta curta.** O barramento é o de sempre; o que muda é em que thread o código que chama `post` está rodando.

| Evento | Onde é chamado | Thread quando a regionalização está ligada | Evidência |
|---|---|---|---|
| `ServerTickEvent.Pre` / `.Post` | `MinecraftServer.tickServer` | Thread principal | `patches/net/minecraft/server/MinecraftServer.java.patch` linhas 188 e 217 do .patch |
| `LevelTickEvent.Pre` / `.Post` | `MinecraftServer.tickChildren`, em volta de `level.tick(haveTime)` | Thread principal | mesmo patch, linhas 278 e 286 |
| `EntityTickEvent.Pre` / `.Post` | `ServerLevel.tickNonPassenger` | Worker de região (o tick de entidades é enfileirado por região) | `ServerLevel.java.patch` linha 604 (evento) e 245-254 (`tickEntitiesRegionized` → `executeCoalescedAll("entity-ticks", ...)`) |
| `PlayerTickEvent.Pre` / `.Post` | início e fim de `Player.tick()` | Worker de região (ver nota) | `patches/net/minecraft/world/entity/player/Player.java.patch` linhas 49 e 66 |
| Tick de block entity (sem evento próprio) | `Level.tickBlockEntitiesForRegionData` | Worker de região | `Level.java.patch` linhas 393-449; checagem `ensureRegionThreadFor(ticker.getPos(), "block entity tick")` linha 442 |
| `BlockEntity.onLoad()` | `Level.tickBlockEntities` (lista `freshBlockEntities`) | **Thread principal** (antes de mandar os ticks para as regiões) | `Level.java.patch` linhas 343-347 |
| `ChunkDataEvent.Load` | `ChunkMap`, ao terminar a leitura do chunk | Executor da região do chunk (`chunkRegionExecutor`) | `ChunkMap.java.patch` linhas 752 e 758-760; `chunkRegionExecutor` linhas 875-881 |
| `CommandEvent` | `Commands.performCommand` | Thread principal (se chamado de worker, é reenviado à fila global) | `patches/net/minecraft/commands/Commands.java.patch` linhas 25-33 |
| `PermissionsChangedEvent` | `EventHooks` | Região do jogador, esperando a resposta | `src/main/java/net/neoforged/neoforge/event/EventHooks.java` linha 904 (`callOnPlayerRegionAndWait`) |

Nota sobre `PlayerTickEvent`: o código da vanilla que chama `Player.tick()` não está no clone (só os patches). Os dois caminhos possíveis já são regionalizados: o tick de entidades (acima) e o tick da conexão do jogador, que foi movido para a região do jogador (`ServerConnectionListener.java.patch` linhas 155-171, `ServerGamePacketListenerImpl.java.patch` linhas 154-194, `ensureRegionizedConnectionTicker`). Por isso digo "worker de região", mas a cadeia exata de chamadas é **não verificada**.

**Não há barreira no fim do tick.** `executeAll` e `executeCoalescedAll` só enfileiram e devolvem quantas tarefas foram aceitas:

```java
// RegionizedTaskDispatcher.java, executeAll (linhas 130-146)
for (Map.Entry<RegionKey, Runnable> entry : canonicalTasks.entrySet()) {
    if (!this.tryExecuteTask(entry.getKey(), new RunnableRegionTask(...))) { throw ... }
    submitted++;
}
return submitted;
```

O teste `executeAllDoesNotWaitForBlockedRegion` (`tests/src/junit/java/net/neoforged/neoforge/unittest/server/region/RegionizedTaskDispatcherTest.java` linha 62) prova que é intencional: uma região travada não segura o `executeAll`. Consequência: `LevelTickEvent.Post` e `ServerTickEvent.Post` disparam **enquanto os workers ainda ticam entidades e block entities daquele mesmo mundo**. Um mod que usa `ServerTickEvent` para "fechar a conta" do tick vê o mundo pela metade.

**Ticks podem ser pulados por região.** `executeCoalescedAll` usa `tryExecuteCoalesced`, que recusa a tarefa se a mesma pista (`lane`) da mesma região ainda está na fila (`RegionizedTaskDispatcher.java` linhas 152-176: `coalescedRegionTasks.putIfAbsent(...) != null → return false`). Região lenta perde ticks de entidade/block entity em vez de acumular. Isso também significa que um mod não pode contar com "um tick de entidade por tick do servidor".

**Erros não derrubam o servidor.** O worker roda cada tarefa dentro de `runSafely`, que registra o erro e segue (`RegionizedTaskDispatcher.java` linhas 975-983 e 1468-1510; laço dos workers em `workerLoop`, linha 961). Se uma entidade de mod lança exceção, o resto do lote daquela região naquele tick não roda (o laço é interrompido pela exceção), e o erro só aparece no log.

### 2.2 Eventos globais que os mods assinam, e trava em volta de handlers

**Resposta: o projeto não trata.** Não há serialização (trava) em volta de handlers.

- Barramento: vem de biblioteca externa `net.neoforged:bus:8.0.5` (`gradle.properties` linha 36, `projects/neoforge/build.gradle` linha 128). Nenhuma classe de barramento no repositório. Se o barramento em si aguenta `post` concorrente: **não verificado** (código fora do clone).
- Busquei `ReentrantLock`, `ReadWriteLock`, `StampedLock`, `GLOBAL_LOCK`, `synchronized (NeoForge`, `synchronized (.*EVENT_BUS` em `src/main/java` e `patches`: **zero** resultados. Há 260 chamadas `EVENT_BUS.post` no repositório; nenhuma embrulhada.
- Efeito: um handler de mod para `EntityTickEvent` pode ser chamado ao mesmo tempo por vários workers; um handler de `ServerTickEvent` roda ao mesmo tempo que os workers.

### 2.3 Dados por mundo que mods guardam (saved data, capabilities, attachments)

- **SavedData** (dado salvo por mundo, ex.: rede de energia global de um mod): só a infraestrutura ficou segura. `SavedData.setDirty/isDirty` viraram `synchronized` (`patches/net/minecraft/world/level/saveddata/SavedData.java.patch`) e o cache de `SavedDataStorage` virou `ConcurrentHashMap` com `computeIfAbsent` (`patches/net/minecraft/world/level/storage/SavedDataStorage.java.patch`, primeiras ~110 linhas). Os **campos internos** do SavedData de cada mod não são protegidos. Não há SavedData "por região".
- **Dados por região que o NeoFolia criou**: só para a vanilla. `RegionizedWorldData.java` (88 linhas) guarda temporizadores de spawners (gato, patrulha, fantasma), cerco de aldeia, mercador ambulante e a lista de tickers de block entity. Mapas estáticos em `RegionizedWorldGuard.java` linhas 52-53 (`REGION_WORLD_DATA`, `GLOBAL_WORLD_DATA`). Nada para mods.
- **Attachments**: o armazenamento em si (`attachment/AttachmentHolder.java` linha 41, `IdentityHashMap`) não ganhou trava nem cópia. O que mudou foi só o **envio** da sincronização aos clientes, que vai para a região dona (bloco/chunk/entidade) ou para a fila global (mundo): `attachment/AttachmentSync.java` linhas 122-131, 149-156, 167, 188. Attachment pendurado no **mundo** pode ser lido e escrito por vários workers ao mesmo tempo sem proteção.
- **Capabilities**: ver 2.6. Ganharam embrulho para acesso entre regiões, mas só para itens, fluidos e energia.

### 2.4 Tile entities / block entities e entidades de mods

- **Tickam na thread da região, sem mudança no código do mod.** Block entities: `Level.tickBlockEntitiesRegionized` (linhas 393-411 do .patch) move os tickers para `RegionizedWorldData` da região do bloco e manda um lote por região. Entidades: `ServerLevel.tickEntitiesRegionized` (linhas 245-255).
- **Não há lista de mods ou classes que rodem em série ou na thread global.** Procurei `regionsafe`, `threadsafe`, `serial`, `exclude`, `blacklist`, `whitelist`, `folia-supported`, `unsafe_mods`: nada (só nomes internos de vanilla como `regionSafeEntity`). `RegionizedServerSettings.java` (chaves nas linhas 25-47) não tem nenhuma opção por mod.
- **Lista pública do mundo esvaziada.** Em modo regionalizado, `Level.blockEntityTickers` é esvaziada a cada tick: `migrateBlockEntityTickersToRegionData` remove cada ticker e o põe no dado da região (linhas 413-422). Um mod que lê essa lista direto vê uma lista vazia. (É o equivalente moderno do nosso `world.loadedTileEntityList`.)
- **Adições vindas de worker**: block entities novas e tickers novos criados num worker vão para filas concorrentes (`pendingOffThreadBlockEntityTickers`, `pendingOffThreadFreshBlockEntities`, linhas 50, 65, 310-311, 451-460) e são drenados na thread principal.
- **Spawners customizados de mods** (registrados por `ModifyCustomSpawnersEvent`, `EventHooks.java` linha 1161) são chamados **uma vez por região, em paralelo**: `ServerChunkCache.tickCustomSpawnersRegionized` (`ServerChunkCache.java.patch` linhas 259-270). Os spawners da vanilla foram adaptados para guardar o estado por região (ex.: `PatrolSpawner.java.patch` linha 30, `RegionizedWorldGuard.currentWorldData`). Um spawner de mod com estado interno passa a ser chamado de várias threads ao mesmo tempo.

### 2.5 Redes de mods que cruzam chunks (energia, itens, fluidos)

**Tratamento em duas camadas.**

1. **União de regiões por proximidade (protege redes vizinhas).** `RegionizedWorldGuard.GroupSnapshot.build` (linhas 1026-1093): toda região fixa (16x16 chunks, `DEFAULT_REGION_CHUNK_SHIFT = 4`, linha 45) que tem chunk carregado ou jogador é "ativa"; regiões ativas a até 3 regiões de distância (`PLAYER_REGION_MERGE_SEARCH_RADIUS = 1*2+1`, linha 48) são unidas (union-find, linhas 1099-1132); cada região ativa ainda cobre uma borda de 1 região vazia ao redor (linhas 1080-1087). A contagem de chunks carregados vem de `ChunkMap.updateRegionizedChunkHolder` (linhas 1740-1755 do .patch), chamado quando um `ChunkHolder` nasce ou é descarregado (linhas 325 e 603). Resultado prático: dois blocos vizinhos carregados quase sempre ficam na mesma região. Um cano ou cabo comum não cruza fronteira.
2. **Embrulho de capability (para o que cruza mesmo assim).** `BlockCapability.getCapability` embrulha o resultado (`capabilities/BlockCapability.java` linhas 222-240): itens e fluidos em `RegionizedBlockResourceHandler`, energia em `RegionizedBlockEnergyHandler`. Idem para entidades (`EntityCapability.java` linhas 108-120) e inventário de jogador (`CapabilityHooks.java` linhas 145 e 193). Cada método do embrulho chama `RegionizedWorldGuard.callOnRegionAndWait` (`transfer/RegionizedBlockResourceHandler.java` linhas 40-85). Movimentos entre dois handlers (`ResourceHandlerUtil.moveInternal`, linhas 318-344) usam `awaitRegions`, que reserva as duas regiões e roda a tarefa na principal (`RegionizedTaskDispatcher.java` linhas 292-388). Energia: `transfer/energy/EnergyHandlerUtil.java` linha 178. Drops de bloco quebrado: `common/CommonHooks.java` linha 607 (`awaitRegionsPaused`).

**Limites que o código mostra:**
- De dentro de um worker, esperar outra região é proibido e lança `IllegalStateException`: `callOnRegionAndWait` linhas 632-634 ("Cannot wait for ... from another region worker"); `ensureSafeBlockingAwait` linhas 569-584. Teste: `rejectsBlockingAwaitForOtherRegionFromWorker` (linha 136 do teste). Ou seja, um cano no worker A que tenta inserir num bloco da região B **falha com exceção**; a exceção é capturada por `runSafely` e só vai para o log.
- `awaitRegions` com mais regiões do que workers lança exceção, salvo com 1 worker, quando serializa (linhas 320-337).
- Só itens, fluidos e energia por capability. Qualquer rede que o mod mantém em estrutura própria (lista estática, grafo em SavedData, "rede sem fio" por frequência, buraco de minhoca, quarry remota) **não é tratada**.
- As regiões são recalculadas no começo de cada tick do servidor (`MinecraftServer.java.patch` linhas 184-186, `refreshRegionOwnership`), e como não há barreira, isso pode acontecer enquanto workers ainda rodam o tick anterior. As checagens são feitas chamada a chamada (`isRegionThreadFor` compara com o dono atual), e a fila de uma região que deixou de ser dona é redirecionada (`RegionQueue.runAvailableTasks`, linhas 1468-1473, `redirectPendingTasks`). Mas não achei nada que impeça dois workers de mexer nos mesmos chunks durante uma união de regiões que acontece no meio de uma tarefa. **Inferência minha a partir do código, não testada.**

### 2.6 Acesso de mod a bloco/entidade fora da região

Depende de **qual thread** faz o acesso.

**De um worker de região** (há proteção, quase sempre silenciosa):

| Operação | Comportamento fora da região | Evidência |
|---|---|---|
| `setBlock`, `removeBlock`, `destroyBlock` | Enfileira na região dona e devolve `false` na hora | `Level.java.patch` linhas 104-108, 231, 243 |
| `setBlockEntity`, `removeBlockEntity` | Enfileira na região dona | `Level.java.patch` linhas 495-499, 505-511 |
| `getBlockEntity` | Devolve `null` | `Level.java.patch` linha 486 |
| `getBlockState`, `getFluidState` | Devolve `VOID_AIR` / fluido vazio | `Level.java.patch` linhas 280-297 |
| `getChunk(..., load=true)` | Lança `IllegalStateException` | `Level.java.patch` linhas 85-91; `RegionizedWorldGuard.ensureChunkLoadIfRegionized` linhas 482-492 |
| `hasChunk` | `false` | `Level.java.patch` linha 75 |
| `getEntity(id)`, `getEntityInAnyDimension`, `getPlayerInAnyDimension` | `null` | `ServerLevel.java.patch` linhas 1350-1377; `restrictEntityToCurrentRegion` (RegionizedWorldGuard linhas 293-307) |
| `getEntities(AABB, ...)` | Caixa recortada ao limite da região | `Level.java.patch` linhas 544, 572, 609; `restrictToCurrentRegion` linhas 263-291 |
| `players()`, `PlayerList.getPlayers()`, `getPlayer(uuid)` | Só os jogadores da região | `ServerLevel.java.patch` linhas 1529-1539; `PlayerList.java.patch` linhas 990-1040 |
| Vizinhança, sons, partículas, eventos de bloco | Enfileirados na região dona | `ServerLevel.java.patch` linhas 929-1232 (`scheduleIfRegionizedOutsideRegion`) |

Para um mod de 1.7.10 isso é importante: o mod não recebe erro, recebe "não existe" (`null`, ar, lista vazia) ou "não consegui" (`false`). Mod que assume que `setBlock` funcionou, ou que `null` quer dizer "bloco removido", pode corromper o próprio estado.

**Da thread principal** (sem proteção): `scheduleIfRegionizedOutsideRegion` e `ensureRegionThreadIfRegionized` retornam logo se a thread atual não é worker (`RegionizedWorldGuard.java` linhas 494-501 e 470-473). `getBlockEntity` também deixa passar a thread do mundo (`Thread.currentThread() != this.thread && ...`, `Level.java.patch` linha 486). Assim, um handler de `ServerTickEvent`/`LevelTickEvent`, um comando de mod, ou uma tarefa global mexe direto em blocos e entidades **ao mesmo tempo** que os workers ticam esses chunks. Não achei checagem para esse caso.

### 2.7 Rede: pacotes customizados de mods (payloads)

- Por padrão, o handler de payload de mod é registrado como `HandlerThread.MAIN` (`network/registration/PayloadRegistrar.java` linhas 29 e 224-229) e embrulhado em `MainThreadPayloadHandler`, que chama `context.enqueueWork(...)` (`network/handling/MainThreadPayloadHandler.java`).
- No servidor, para conexão de jogador, `ServerPayloadContext.enqueueWork` foi trocado por `enqueueGameplayWork`, que roda a tarefa com `RegionizedWorldGuard.executeOnPlayerRegion` (`network/handling/ServerPayloadContext.java` linhas 49-85, `enqueueGameplayWork` linha 61, ponto-chave linha 66). Ou seja: **"thread principal" para o mod agora quer dizer "worker da região do jogador"**. O mod não é avisado.
- Pacotes vanilla de jogo seguem o mesmo caminho: `PacketUtils.ensureRunningOnSameThread` usa o processador de pacotes do jogador (`patches/net/minecraft/network/protocol/PacketUtils.java.patch`), que só considera "mesma thread" quando está na região do jogador (`ServerGamePacketListenerImpl.java.patch` linhas 135-141).
- Envio de payload ao cliente: `PacketDistributor` e `RegionizedWorldGuard.sendToPlayer` levam o envio para a região do jogador (`network/PacketDistributor.java` linhas 40, 81, 100, 117-126; `RegionizedWorldGuard.java` linhas 1174-1207). Sincronia de config vai para a fila global (`network/ConfigSync.java` linhas 106-107).
- Handler registrado como `HandlerThread.NETWORK` continua na thread de rede (Netty). Não achei mudança nesse caminho.

### 2.8 Geração de mundo de mods

- O NeoFolia **não muda a lógica** de geração para mods. Troca os executores: geração, leitura/parse e I/O de chunk passam para grupos de threads próprios (`server/region/RegionizedChunkExecutors.java` linhas 22-24; uso em `ChunkMap.java.patch` linha 103 e `NoiseBasedChunkGenerator.java.patch` linhas 24 e 33).
- Em 26.1.2 a geração já é feita fora da thread principal pela vanilla (por isso as "features" de mods já precisam ser seguras para threads). Isso é base da vanilla moderna, não mudança do NeoFolia; e é a maior diferença para 1.7.10, onde `populate` roda na thread principal e pode carregar chunks vizinhos. Esta frase sobre a vanilla é de memória: **não conferida no clone** (o código vanilla não está nele).
- Etapas de fim de carregamento (`postProcessGeneration`, aviso de chunk pronto) passam para o executor da região do chunk (`ChunkMap.java.patch` linhas 858-872).
- Busquei patches de `levelgen` com `Regionized`: só `StructureTemplate`, `PatrolSpawner`, `PhantomSpawner` e `NoiseBasedChunkGenerator`. Nenhum tratamento para gerador de mod.

### 2.9 Comandos de mods

- Todo comando passa por `Commands.performCommand`. Se chamado de um worker, é reenviado à fila global (`Commands.java.patch` linhas 25-31). Na prática, comandos rodam na thread principal.
- 90 dos 91 patches em `patches/net/minecraft/server/commands/` foram adaptados para "pular" até a região do alvo com `RegionizedCommandHelper.callOnPlayerRegion`, `callOnEntityRegion`, `callOnRegion` (arquivo novo `RegionizedCommandHelper.java.patch`, linhas 47-80), que esperam a resposta. Exemplo: `/tp` reserva a região da vítima e a do destino (`TeleportCommand.java.patch` linha 314, `awaitRegions`).
- Comandos de mods **não** são adaptados. Eles rodam na thread principal e, pelo item 2.6, mexem no mundo sem checagem, ao mesmo tempo que os workers. Os helpers são públicos (`callOnPlayerRegion` etc.), então um mod pode usá-los, mas precisa ser reescrito.
- Comandos do próprio NeoForge adaptados: `server/command/TPSCommand.java`, `TrackCommand`, `GenerateCommand`, `EntityCommand`, `TimeSpeedCommand`, `CommandUtils` (contagem de usos na seção de índice).

### 2.10 Lista de compatibilidade, mods que quebram, issues

- **Lista de compatibilidade: não existe.** README, seção "Mod Compatibility" (README.md linha 32): "Once the core becomes more or less stable, we'll port many popular mods to NeoFolia ourselves and give you access to the source code and builds." (Da documentação.) Não achei pasta ou arquivo de mods portados no repositório.
- **Mods conhecidos que quebram: nenhum citado** no código ou README. Busquei `incompatible`, `known issue`, `broken`, `crash` em README e `docs/`: nada sobre mods (a pasta `docs/` é do NeoForge).
- **Issues relatando corrupção ou crash: não verificado.** A API e as páginas do GitHub responderam HTTP 403 nesta sessão (acesso só a `git clone`). O histórico mostra 2 dias de commits, nenhum com mensagem de correção de mod.
- Testes de região que existem são só de unidade, sem mod: `tests/src/junit/java/net/neoforged/neoforge/unittest/server/region/` (4 arquivos; ex.: ordem dentro da região, proibição de espera entre regiões, fila global, fechamento). Não executei.

### 2.11 API nova oferecida aos mods

Há métodos públicos, mas **não há interfaces de agendador** como o README sugere ("scheduling and ownership are exposed through native NeoForge API interfaces", README.md linha 9; procurei `interface .*Region` e `IRegion` em `src/main/java`: só a interface privada `RegionTask`). O que um mod pode chamar:

- `ServerLevel.submitRegionTask(chunkX, chunkZ, Runnable|Supplier)` → `CompletableFuture` (`ServerLevel.java.patch` linhas 1427-1433); `MinecraftServer.submitRegionTask(level, chunkX, chunkZ, ...)` (`MinecraftServer.java.patch` linhas 367-373).
- `MinecraftServer.regionizedTaskDispatcher()` e, nele, `execute`, `tryExecute`, `submit`, `tryExecuteNextTick`, `executeGlobal`, `submitGlobal`, `awaitRegions`, `awaitRegionsPaused` (`RegionizedTaskDispatcher.java` linhas 97-300, 588-670).
- `RegionizedWorldGuard` (estático): `regionFor`, `isRegionThreadFor`, `ensureRegionThreadFor`, `scheduleAtRegionIfRegionized`, `scheduleEntityIfRegionized`, `scheduleGlobalIfRegionized`, `executeOnPlayerRegion`, `callOnPlayerRegionAndWait`, `callOnEntityRegionAndWait`, `callOnRegionAndWait`, `sendToPlayer`, `sendPayloadToPlayer` (linhas 58-640, 1174-1207).
- `RegionizedTickContext.currentRegion()` / `isRegionThread()` (`RegionizedTickContext.java`, `ThreadLocal`).
- Nenhuma dessas classes tem marca `@ApiStatus` (busquei no pacote `server/region`). Não há agendador por entidade (o Folia tem; ver relatórios do Folia nesta pasta).

### 2.12 "Modo seguro" para mod não adaptado

**O projeto não trata.**

- Não há opção por mod, nem lista "só em série" (2.4).
- Não há chave "desligar tudo": `MinecraftServer` sempre chama `regionizedTaskDispatcher.start(defaultThreadCount())` (`MinecraftServer.java.patch` linha 124) e `start` usa `Math.max(1, threadCount)` (`RegionizedTaskDispatcher.java` linha 87). `region_threads = 0` quer dizer "automático" (`RegionizedServerSettings.java` linhas 72-78), não "desligado".
- Os 4 interruptores (`regionized_chunk_ticks`, `regionized_scheduled_ticks`, `regionized_block_entity_ticks`, `regionized_entity_ticks`, linhas 26-29, todos `true` por padrão, linhas 258-261) devolvem cada categoria à thread principal. Mas conexões, pacotes e payloads de jogador continuam indo para workers, porque só testam `isRunning()` (`ServerGamePacketListenerImpl.java.patch` linhas 202-207).
- Com `region_threads = 1` todas as regiões rodam em série num worker, mas esse worker ainda roda **ao mesmo tempo que a thread principal** (sem barreira, 2.1). Não é um modo seguro.
- Há tempo limite para esperas entre regiões (`region_task_timeout_seconds`, padrão 60; `RegionizedWorldGuard.java` linhas 659-672): a espera falha com exceção em vez de travar para sempre. É a única rede de segurança contra trava.

---

## 3. O que isso sugere para um servidor 1.7.10 com mods

**Opinião minha, não fato dos projetos.** Serve como entrada para o desenho das Etapas 2 e 3 em `docs/02-arquitetura.md`.

1. **O NeoFolia confirma o nosso Degrau B, não o contradiz.** Ele escolheu não ter barreira no fim do tick, e por isso `ServerTickEvent`/`WorldTickEvent` rodam junto com as regiões. Para 1.7.10, onde muitos mods fazem a "conta do tick" em `ServerTickEvent` e leem `world.loadedTileEntityList`, a fase paralela **com barreira** (Degrau B) é o que preserva esses mods. Copiar o modelo sem barreira seria trocar compatibilidade por desempenho sem medir.
2. **Checar também a thread principal.** O buraco mais claro do NeoFolia é que a thread principal escreve no mundo sem checagem enquanto os workers rodam. Com barreira o problema some (a thread principal só roda quando as regiões pararam). Sem barreira, toda escrita fora da região precisa ser checada, venha de onde vier.
3. **Não devolver "nada" em silêncio para mods antigos.** O NeoFolia responde acesso fora da região com `null`, ar, `false` ou lista recortada. Mods 1.7.10 não esperam isso. Para nós, o observador (Etapa 2) deve **registrar** essas tentativas por mod/classe antes de qualquer bloqueio, e a resposta padrão em produção deve ser rebaixar para o degrau seguro, não mentir para o mod.
4. **Unir regiões por proximidade resolve a maioria das redes de mods.** A regra simples dele (grade 16x16, união a até 3 regiões, borda de 1) já põe blocos vizinhos carregados na mesma região. Para nós, o raio deveria ser configurável e cobrir o alcance real de máquinas comuns. O que sobra são ligações de longa distância (Tesseract, quantum bridge, energia sem fio, quarry remota, carregadores de chunk), que precisam de lista "só em série" ou de ficar na thread global.
5. **Recalcular regiões só com tudo parado.** Ele recalcula a cada tick sem barreira; vimos uma janela possível de dois workers nos mesmos chunks (inferência). No nosso desenho, união/divisão de regiões deve acontecer só na fase em série.
6. **Embrulhar a porta de troca de itens/energia vale a ideia, não o código.** Em 1.7.10 não há capability padrão: há `IInventory`, `ISidedInventory`, `IFluidHandler`, `IEnergyHandler` (CoFH) e APIs próprias. Interceptar todas é muito mais caro. Com barreira e união por proximidade, talvez nem precise; vale medir no observador quantas chamadas cruzam região.
7. **Spawners e handlers chamados "uma vez por mundo" não podem virar "uma vez por região".** O NeoFolia chama spawners de mods uma vez por região, em paralelo. Para nós, tudo que é por mundo deve ficar na fase em série.
8. **Payloads de mods: não trocar a thread em silêncio.** Ele manda o "thread principal" do mod para a região do jogador. Em 1.7.10 os mods tratam pacote na thread de rede ou enfileiram por conta própria (FML/SimpleNetworkWrapper). Manter isso na fase em série é o caminho seguro; mover para a região só para mods liberados.
9. **Precisamos de "modo seguro" e de chave geral, que ele não tem.** Interruptor geral que volta ao comportamento original, lista por mod e por dimensão, e rebaixamento automático quando o vigia vê violação. Isso já está no nosso desenho (seção 4, item 9) e o NeoFolia mostra que não dá para pular.
10. **Erro em tarefa de região não deve sumir no log.** Ele registra e segue, e o resto do lote não roda. Para nós, segurança antes de desempenho: erro em região deve rebaixar a região/dimensão e avisar o admin.

---

## 4. Não verificado

- Se o barramento de eventos (`net.neoforged:bus:8.0.5`) aguenta `post` concorrente: biblioteca fora do clone.
- Cadeia exata de chamadas que leva a `Player.tick()` (conexão ou tick de entidade): código vanilla fora do clone. Os dois caminhos estão regionalizados.
- Que a geração de mundo da vanilla 26.1.2 já roda fora da thread principal: de memória, código vanilla fora do clone.
- Em que thread dispara `ChunkDataEvent.Save` e `ChunkEvent.Unload` em modo regionalizado: vi que o descarregamento é enfileirado por região (`ChunkMap.java.patch` linha 393, `"chunk-unloads"`), mas não segui a cadeia até o `post`.
- Janela de corrida durante união de regiões no meio de uma tarefa: inferência pela leitura de `refreshRegionOwnership`, `RegionQueue.runAvailableTasks` e da falta de barreira; não testado.
- Issues, PRs e relatos de corrupção/crash no GitHub: HTTP 403 nesta sessão.
- Se existe licença própria do NeoFolia além da LGPL herdada do NeoForge.
- Pastas `rejects_*` (patches rejeitados em portes) e `coremods/`: não abertas para este tema.
- Comportamento real com mods: nada foi compilado ou executado; os testes de unidade não foram rodados.
- O handler de payload `HandlerThread.NETWORK`: não achei mudança, mas não li todo o caminho de `NetworkRegistry`.
