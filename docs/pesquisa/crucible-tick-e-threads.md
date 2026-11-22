# Crucible staging: anatomia do tick e das threads

Documento âncora para o desenho de threads por região do Hoshiestar.
Base estudada: clone do Crucible em `src/Crucible-staging` (ponta `cd1de3d`), comparado com `67b53034` (o build de produção).
Data: 2026-11-21.

## 0. Como ler as referências

- **Tick**: um passo do jogo, 50 ms alvo (20 por segundo). **TPS**: ticks por segundo.
- **Thread do tick** (ou "thread principal"): a thread chamada `Server thread`, criada em `MinecraftServer` (patch `MinecraftServer.java.patch:219`, campo `primaryThread`).
- `patch:N` = linha N dentro do arquivo `.patch` em `patches/...`.
- `MinecraftServer.java:N` = linha no fonte final compilado. Calculei somando os cabeçalhos `@@` do patch (script awk sobre o diff) e conferi com pilhas reais de produção: `func_71217_p(MinecraftServer.java:842)`, `mainThreadHeartbeat` em `:905` (`boot3.log:24202-24205`), `networkTick` em `:1023` (`anexos/stall/stall.txt:29`), `run` em `:692`. Os quatro batem.
- O `MinecraftServer.java.patch` de `67b53034` tinha cabeçalhos `@@` desatualizados (+3 linhas), corrigidos no commit `667a28e` ("fix stale hunk headers"). O conteúdo das linhas é idêntico entre `67b53034` e `cd1de3d` (o `git diff` só muda linhas `@@`), por isso os números de linha valem para produção.
- Nomes SRG conferidos em `forge/fml/conf/fields.csv` e `methods.csv`.

## 1. O tick, em ordem

### 1.1 Laço externo (`MinecraftServer.run`, `MinecraftServer.java:649-698`, patch:497-566)

1. `curTime = System.nanoTime()`; `wait = getTickTime() - (curTime - lastTick) - catchupTime` (`:669-671`). `getTickTime()` = `serverTickTime / serverTickRate` = 1e9/20 = 50 ms (`CrucibleConfigs.java:203-206,277-279`; cópia de teste `server-test3/Crucible.yml:144-146`).
2. Se `wait > 0`: `Thread.sleep(wait/1e6)`, zera `catchupTime` e volta ao início sem ticar (`:672-677`).
3. Senão: `catchupTime = min(1 s, |wait|)` (`:680`). Efeito: atrasado, o servidor tica de novo sem dormir; o "crédito" de atraso aceito é no máximo 1 s, o resto é perdoado. Não existe mais o aviso vanilla "Can't keep up" (removido, patch:524).
4. TPS (`:683-689`): a cada 100 ticks (`currentTick++ % 100 == 0`) calcula `currentTps = 1e9/(curTime - lastTick)` e médias exponenciais de 1, 5 e 15 min (`calcTps`, `:631-634`). **Atenção**: a amostra é o intervalo de UM tick a cada 100, não a média dos 100. O `recentTps` é ruidoso; usar spark ou `worldTickTimes` para medir.
5. `lastTick = curTime; this.tick()` (`:691-692`).
6. Afinidade de CPU opcional (`AffinityLock`, `:653-664`), desligada por padrão (`CrucibleConfigs.java:66`).

### 1.2 `MinecraftServer.tick()` (`:822-898`)

| # | Fase | Linha final | Origem / Timings |
|---|---|---|---|
| 1 | `TimingsManager.FULL_SERVER_TICK.startTiming()` | 824 | Crucible (patch:630). Envolve todo o `tick()`, não o sono |
| 2 | `WatchdogThread.tick()` grava `lastTick` volátil | 825 | Thermos (patch:631) |
| 3 | `callingForgeTick = true`; FML `onPreServerTick` (`ServerTickEvent` START); `callingForgeTick = false` | 827-831 | Forge + Cauldron. Timer `CrucibleTimings.forgePreTick` (patch:634-636). Com a flag ligada e `loadChunkOnForgeTick=true`, `provideChunk` carrega chunks (ChunkProviderServer.patch:577-582) |
| 4 | `++tickCounter` | 832 | vanilla |
| 5 | `updateTimeLightAndEntities()` (via `DedicatedServer.func_71190_q`, `DedicatedServer.java:414`) | 842 | ver 1.3 |
| 6 | Autosave: se `autosavePeriod > 0 && tickCounter % autosavePeriod == 0`: `saveAllPlayerData()` + `saveAllWorlds(true)` | 860-873 | CraftBukkit (patch:646). `bukkit.yml ticks-per.autosave: 6000` na cópia de teste (`server-test3/bukkit.yml:40`), ou seja 5 min. Por mundo, timer `worldserver.timings.worldSave` (patch, `stopTiming` em `:553`) |
| 7 | "tallying" (grava `tickTimeArray`, usado por `/forge tps`) e snooper | ~875-888 | vanilla; snooper condicionado por Spigot (patch:667,673) |
| 8 | FML `onPostServerTick` (`ServerTickEvent` END), com `callingForgeTick` | 892-896 | timer `forgePostTick` (patch:681-683) |
| 9 | `FULL_SERVER_TICK.stopTiming()` | 897 | |

### 1.3 `updateTimeLightAndEntities()` (`:900-1039`)

| # | Fase | Linha final | Timings |
|---|---|---|---|
| a | Agendador Bukkit: `getScheduler().mainThreadHeartbeat(tickCounter)` | 904-906 | `bukkitSchedulerTimer` |
| b | `processQueue`: roda todos os `Runnable` enfileirados por outras threads (`ConcurrentLinkedQueue`, patch:124) | 909-914 | `processQueueTimer` |
| c | `ChunkIOExecutor.tick()`: termina cargas assíncronas de chunk (fase 2 e callbacks) | 916-918 | `chunkIOTickTimer` |
| d | A cada 20 ticks, pacote de hora para cada jogador de `playerEntityList` | 920-930 | `timeUpdateTimer` |
| e | `ids = DimensionManager.getIDs(tickCounter % 200 == 0)`: cópia das chaves do `Hashtable worlds` | 934 | nenhum |
| f | Para cada `id`: `j = nanoTime()`; `worldserver = DimensionManager.getWorld(id)` | 937-943 | |
| f1 | FML `onPreWorldTick` (`WorldTickEvent` START) | 957 | sem timer próprio |
| f2 | `worldserver.tick()` (ver 1.4) | 962-964 | `timings.doTick` |
| f3 | `worldserver.updateEntities()` (ver 1.5) | 985-987 | `timings.tickEntities` |
| f4 | FML `onPostWorldTick` (`WorldTickEvent` END) | 1006 | sem timer próprio |
| f5 | `getEntityTracker().updateTrackedEntities()` | 1009-1011 | `timings.tracker1` |
| f6 | `worldTickTimes.get(id)[tickCounter % 100] = nanoTime() - j` | 1016 | é o que `/forge tps` mostra por dimensão |
| g | `DimensionManager.unloadWorlds(worldTickTimes)`: descarrega mundos da `unloadQueue` | 1020 | nenhum |
| h | `networkTick()`: processa pacotes recebidos de TODAS as conexões | 1022-1024 | `connectionTimer` |
| i | `sendPlayerInfoToAllPlayers()` (ping da tab) | 1026-1028 | `playerListTimer` |
| j | `tickables` (`IUpdatePlayerListBox`) | 1031-1036 | `tickablesTimer` |
| k | Comandos de console pendentes (`executePendingCommands`, DedicatedServer.patch:242-255) | DedicatedServer | ordem exata em relação ao `super` **nao verificado** (fonte vanilla não está no repo) |

O `if (id == 0 || getAllowNether())` vanilla foi comentado (patch:728-730): todas as dimensões carregadas ticam.

### 1.4 Dentro de `WorldServer.tick()` (WorldServer.java.patch:162-234)

`super.tick()` (World: clima) e lógica de dormir; depois, nesta ordem:
1. **mobSpawner**: só se há jogadores no mundo e `ticks-per` permite (patch:168-181, CraftBukkit). Timer `mobSpawn`.
2. **chunkSource**: `chunkProvider.unloadQueuedChunks()` (patch:186). O descarregamento de chunks fica DENTRO do tick de cada mundo, não no tick global. Até 100 chunks por tick (`ChunkProviderServer.java.patch:483`), cada um dispara `ChunkUnloadEvent` Bukkit (patch:515), salva e pode mandar a dimensão para `DimensionManager.unloadWorld` (patch:546). Também avança hora do mundo. Timer `doChunkUnload` (patch:184-200).
3. **tickPending**: `tickUpdates(false)` (atualizações agendadas de bloco). Timer `chunkTicksBlocks` (patch:202-204).
4. **tickBlocks**: `func_147456_g()` (ticks aleatórios, gelo, neve, raio). Mesmo timer `chunkTicksBlocks` (patch:206-208); o Timings não separa 3 de 4.
5. **chunkMap**: `thePlayerManager.updatePlayerInstances()` (envio de chunks a jogadores). Timer `doChunkMap`.
6. **village**, **portalForcer** (inclui teleporters de mods), **sounds/block events** (`func_147488_Z`), **chunk GC** Bukkit (`processChunkGC`), cada um com timer próprio (patch:213-233).

### 1.5 Dentro de `World.updateEntities()` (World.java.patch:~965-1162)

1. Efeitos de clima (raios), com proteção contra `null` (CraftBukkit).
2. Remoção de `unloadedEntityList`.
3. `ActivationRange.activateEntities(this)` (Spigot, patch:987): marca entidades fora do alcance como inativas.
4. Laço de entidades com `TickLimiter` e cursor `tickPosition` que continua no tick seguinte (patch:996). Timer `entityTick`; por entidade `entity.tickTimer` (patch:1233). Limite configurado `max-tick-time.entity: 1000` ms na cópia de teste (`spigot.yml:79-81`), ou seja na prática sem limite.
5. Limpeza de TEs (tile entities: blocos com lógica, como máquinas) marcadas para remoção, laço de TEs com `tileLimiter` e cursor `tileTickPosition` (patch:1064). Timer `tileEntityTick`; por TE `tileentity.tickTimer` (patch:1092). `CauldronHooks.canTileEntityTick` pode pular TEs.
6. TEs pendentes (`addedTileEntityList` -> `loadedTileEntityList`), timer `tileEntityPending` (patch:1125-1161).

## 2. Threads e executores que já existem na base

| Thread | Onde nasce | Tamanho | O que toca | Onde sincroniza com o tick |
|---|---|---|---|---|
| `Server thread` | `MinecraftServer` (patch:219), iniciada em `:1750` | 1 | tudo | é o tick |
| `Chunk I/O Executor Thread-N` | `ChunkIOExecutor` sobre `AsynchronousExecutor` (Forge `util/AsynchronousExecutor.java:234`) | `max(1, ceil(jogadores/50))` (`ChunkIOExecutor.java:6-7,24-27`), ajustado em login/logout (`ServerConfigurationManager.java.patch:234,319`). A fila é ilimitada, então nunca passa do núcleo | fase 1: lê e decodifica o NBT do arquivo de região (`ChunkIOProvider.callStage1`, `loadChunk__Async`) | fase 2 e callbacks rodam no tick em `ChunkIOExecutor.tick()` (`MinecraftServer.java:917`, `finishActive`): `loadEntities`, `ChunkDataEvent.Load`, inserção em `loadedChunkHashMap_KC` e `loadedChunks`, `onChunkLoad`, `recreateStructures`, `ChunkLoadEvent` Bukkit, vizinhos, `populateChunk` (`ChunkIOProvider.java` após patch, `callStage2`) |
| `File IO Thread` | vanilla `ThreadedFileIOBase` | 1 | escreve chunks salvos em disco | fila de `AnvilChunkLoader` protegida por `syncLockObject` (`AnvilChunkLoader.java.patch:102`); `RegionFileCache` e `RegionFile` com métodos `synchronized` (patches:31-199 e 208-351). Spigot tirou o `sleep` entre chunks (`ThreadedFileIOBase.java.patch`) |
| `Netty IO #N` | `NetworkSystem` (vanilla) | 8 em produção (stall) | decodifica pacotes, aceita conexões; executa direto pacotes com `hasPriority()` | fila `receivedPacketsQueue` (`ConcurrentLinkedQueue`, `NetworkManager.java.patch:33`), esvaziada no tick até 1000 por conexão (patch:103) dentro de `networkTick`. A lista `networkManagers` é `synchronizedList` e o tick segura o lock dela durante TODO o processamento de pacotes (`NetworkSystem.java.patch:52-69`). O flag `processing` (patch:17) não é `volatile` |
| Chat assíncrono | roda NA thread Netty | n/a | `C01PacketChatMessage.hasPriority()` = mensagem sem "/" (`C01PacketChatMessage.java.patch:9-11`); `chat(s, true)` dispara o `ServerChatEvent` do Forge sob `synchronized(ServerChatEvent.class)` (`ForgeHooks.java.patch:33`), `AsyncPlayerChatEvent`, e itera `playerEntityList` (NetHandlerPlayServer.patch ~758-771) | kicks e `PlayerChatEvent` síncrono voltam por `processQueue` + `Waitable` (patch:676-684, 785-793, 857-891). Que pacotes de prioridade rodam direto no Netty é comportamento vanilla de `NetworkManager.channelRead0`: **nao verificado** no fonte deste repo |
| `User Authenticator #N` (`ThreadPlayerLookupUUID`) | uma thread nova por login (`NetHandlerLoginServer.java.patch:163,228`) | 1 por login | autenticação Mojang, `AsyncPlayerPreLoginEvent` | `PlayerPreLoginEvent` via `Waitable` no `processQueue` (`ThreadPlayerLookupUUID.java:87-97`); depois só muda o estado para `READY_TO_ACCEPT` (`:109`) e o login segue no tick |
| `Craft Scheduler Thread - N` | `CraftScheduler.java:67`, pool em cache | sob demanda (2 no stall) | tarefas assíncronas de plugins, sem nenhuma checagem | `mainThreadHeartbeat` (`CraftScheduler.java:343-384`) entrega tarefas assíncronas ao pool (`:371`) e roda as síncronas; tarefas novas chegam por lista sem lock (`tail`, `:75`). Não existe `AsyncCatcher` no repo (grep vazio) |
| `PaperSpigot - Lighting Thread` | campo de CADA `World` (`World.java.patch:1681`) | 1 por mundo, criada só no primeiro uso | recalcula luz | só usada com `use-async-lighting: true` (padrão `false`, `SpigotWorldConfig.java:276`; cópia de teste `spigot.yml:95` = false). Usa `Bukkit.isPrimaryThread()` (patch:1714). A condição está invertida em relação ao comentário (patch:1692-1694 manda para a thread só mundos `isModded`; `ChunkProviderServer.java.patch:118-120` usa o oposto). Nenhuma no stall |
| `Spigot Watchdog Thread` | `SpigotConfig.java:180` | 1 | só lê `lastTick` e o `ThreadMXBean` | `lastTick` volátil escrito no início do tick (`WatchdogThread.java:30,48-49`). Acorda a cada 10 s (`:206`); passado `timeout-time` (90 s na cópia de teste, `spigot.yml:15`) despeja a pilha da `primaryThread` (`:164`) e reinicia se `restart-on-crash` (`:174-175`). Só conhece UMA thread de tick |
| `Server console handler` | `DedicatedServer.java.patch:87` | 1 | lê o stdin | `pendingCommandList` (`synchronizedList`, patch:55), executada no tick (patch:242-255) |
| `Server Infinisleeper` | `DedicatedServer.java.patch:76` | 1 | nada (dorme para sempre) | nenhuma |
| `Snooper Timer`, `Spigot Metrics Thread` | vanilla / `org/spigotmc/Metrics.java` | 1 cada | estatísticas | snooper só com `isSnooperEnabled()` (patch:667,673) |
| Gancho de desligamento | `ServerShutdownThread`, registrado no construtor (patch:218) | 1, só no `SIGTERM`/`exit` | chama `server.stopServer()` direto (`ServerShutdownThread.java:16`) | **nenhuma**: não para o laço do tick (ver D-07). O gancho vanilla foi comentado (patch:958-984) |
| Autosave | não é thread | n/a | roda no tick (`:860-873`) | as escritas vão para a `File IO Thread` |

Outras proteções pontuais existentes: `EntityTracker.addEntityToTracker` é `synchronized` (`EntityTracker.java.patch:365`); `pendingTickListEntriesHashSet` vira `synchronizedSet` (`WorldServer.java.patch:75,112,636`); `loadedEntityList` e `loadedTileEntityList` são `HashedArrayList`, um `ArrayList` com um `synchronizedSet` só para `contains` (`HashedArrayList.java:5,7`; `World.java.patch:125-128`). As listas em si não são seguras para threads.

Correção posterior que produção NÃO tem: `ac070ce` (2026-10-13, depois de `67b53034`) troca `playerEntityList` por `SnapshotIteratingArrayList`, porque o chat assíncrono iterando a lista enquanto um login adicionava causava `ConcurrentModificationException`.

## 3. D-07: causa raiz do NPE em `MinecraftServer.java:1016`

**A linha**: `MinecraftServer.java:1016` é `worldTickTimes.get(id)[this.tickCounter % 100] = System.nanoTime() - j;` (patch:809). `worldTickTimes` é `public Hashtable<Integer,long[]>` criado pelo Forge (`forge/patches/minecraft/.../MinecraftServer.java.patch:37`). `get(id)` devolve `null` e a escrita no array dá NPE, exatamente a mensagem do D-07 (`DIAGNOSTICO.md:104-111`).

**Quem remove**: `DimensionManager.setWorld(id, null)` faz `worlds.remove(id)` e `MinecraftServer.getServer().worldTickTimes.remove(id)` (fonte Forge + patch Crucible, linhas 244-248 do arquivo resultante; `setWorld` começa na linha 224). Quem chama com `null`:
1. `MinecraftServer.stopServer()`: depois de salvar, dispara `WorldEvent.Unload` e chama `setWorld(..., null)` para cada mundo (`forge/patches/minecraft/net/minecraft/server/MinecraftServer.java.patch:116-123`; no fonte final fica entre `:599` e `:626`).
2. `CraftServer.unloadWorld` (`CraftServer.java:1004-1006`), chamado por `unloadWorlds` no tick (`:1020`) e por plugins.

**A corrida**:
1. `SIGTERM` -> a JVM roda o gancho `ServerShutdownThread` em outra thread -> `server.stopServer()` (`ServerShutdownThread.java:16`).
2. `stopServer()` não mexe em `serverRunning` (quem zera é `initiateShutdown`, `:627`). A `Server thread` continua ticando.
3. No começo de `updateTimeLightAndEntities` o tick copiou `ids` (`:934`). Enquanto tica um mundo (entre `:943` e `:1016`, que é a parte longa), o gancho chega no laço do Forge e remove aquele `id` de `worldTickTimes`.
4. O tick chega em `:1016`, `get(id)` = `null`, NPE em `func_71190_q`. Variante possível com a mesma causa: `getWorld(id)` já `null` em `:943`, NPE em `:944` (**nao verificado** em execução).
`Hashtable` é sincronizado por chamada, mas a sequência "copiar ids, ticar, gravar tempo" não é atômica. Ao mesmo tempo o gancho também roda `disablePlugins()` e `saveAllWorlds(false)` em paralelo com o tick (`MinecraftServer.java:567-597`): risco de salvar chunk sendo alterado (**nao verificado**).

**Ponta `cd1de3d` ainda tem o bug**: sim. `git diff 67b53034 HEAD` não muda `ServerShutdownThread.java`, `DimensionManager.java.patch`, o fonte Forge do `DimensionManager` nem o patch Forge do `MinecraftServer`; no patch Crucible do `MinecraftServer` só mudam cabeçalhos `@@` (commit `667a28e`).

**Correção mínima (descrição)**:
1. Causa: o gancho não deve desligar o servidor por conta própria. Ele deve só pedir a parada (`initiateShutdown()`, que zera `serverRunning`) e esperar a `Server thread` terminar com `join` e prazo. Assim o próprio `run()` faz `stopServer()` na thread do tick (`:753-754`), sem concorrência. Se a thread do tick já morreu ou não termina no prazo, aí sim chamar `stopServer()` como último recurso.
2. Cuidado: o `finally` de `run()` chama `systemExitNow()` (`:775`). `System.exit` chamado durante os ganchos de desligamento bloqueia para sempre (regra da JVM), por isso o `join` precisa de prazo, ou o `run()` precisa pular o `exit` quando a parada veio do gancho (**nao verificado** em execução).
3. Defesa extra barata: em `:1016` ler `worldTickTimes.get(id)` numa variável e pular se `null`; em `:943` pular se `getWorld(id)` for `null`.
Caso de teste: subir o servidor de teste, mandar `SIGTERM`, exigir saída sem crash report e com os mundos salvos.

## 4. Onde ligar a checagem de dono ("esta thread pode mexer nesta posição?")

**Poucos pontos que cobrem muito**:
1. `ChunkProviderServer.provideChunk`, `loadChunk` e `getChunkIfLoaded` (`ChunkProviderServer.java.patch:290-315, 184, 177`). Toda leitura e escrita via `World.getBlock`, `getBlockMetadata`, `setBlock`, `getTileEntity`, `setTileEntity`, vizinhos e luz passa por `World.getChunkFromChunkCoords` -> `provideChunk` (vanilla; o patch de `getBlock` só adiciona antes a captura de árvores, `World.java.patch:515-526`). É o ponto com melhor cobertura por linha de patch. Custo: está no caminho mais quente do jogo, a checagem tem que ser uma comparação de referência de thread.
2. `Chunk.func_150807_a` (setBlock no chunk) e `Chunk.func_150806_e`/`func_150812_a` (TE no chunk): pega mods que guardam a referência do `Chunk` e pulam o `World`.
3. Entidades: `World.spawnEntityInWorld` (`World.java.patch:725`), remoção, e a troca de chunk em `updateEntityWithOptionalForce` (patch:1188). É também o ponto de passagem de uma entidade para outra região.
4. `WorldServer.func_147454_a` (agendar atualização de bloco) e `World.func_147448_a` (adicionar TEs, patch:1168).
5. Eventos globais disparados fora do tick: `processQueue`, `CraftScheduler`, chat no Netty (seção 2).

Armadilhas já presentes: `Bukkit.isPrimaryThread()` compara com a `primaryThread` (`CraftServer.java:1599-1601`); numa thread de região dá `false`. Consequências: `TimingHandler.startTiming/stopTiming` não medem nada fora da thread principal (`TimingHandler.java:102,114`), então os timings por TE e entidade somem na fase paralela; e `World.updateLightByType` muda de caminho (patch:1714). Fora do Timings há só 2 usos de `isPrimaryThread()` no repo (grep).

**Campos públicos que mods acessam direto** (o gancho em método não vê). Contagem: jars no topo de `server-test3/mods` (224 jars; 3 em subpastas não contados) cujo `.class` contém o nome SRG. É presença de referência, não contagem de acessos:

| Campo (SRG) | Dono | Jars |
|---|---|---|
| `playerEntities` (`field_73010_i`) | `World` | 63 |
| `playerEntityList` (`field_72404_b`) | `ServerConfigurationManager` | 46 |
| `loadedEntityList` (`field_72996_f`) | `World` | 34 |
| `worldServers` (`field_71305_c`) | `MinecraftServer` | 29 |
| `chunkTileEntityMap` (`field_150816_i`) | `Chunk` | 28 |
| `loadedTileEntityList` (`field_147482_g`) | `World` | 26 |
| `getBlockStorageArray()` (`func_76587_i`, dá os arrays crus de blocos) | `Chunk` | 26 |
| `entityLists` (`field_76645_j`) | `Chunk` | 11 |
| `loadedChunks` (`field_73245_g`) | `ChunkProviderServer` | 9 |
| `weatherEffects` (`field_73007_j`) | `World` | 2 |

Conclusão: na fase paralela essas listas são lidas por código de mod sem passar por método nosso. Ou se garante que nenhuma delas muda de tamanho durante a fase paralela (adições e remoções adiadas para a fase serial, como já é feito com `addedTileEntityList`), ou o mod que as lê fica no modo serial.

## 5. Degrau B: o que pode ir para a fase paralela

"Sem mexer" = nenhum código novo além de rodar em outra thread. Na prática quase nada que mexe em mundo cabe aqui.

| Fase (seção 1) | Classe | Motivo |
|---|---|---|
| Watchdog, FML pré-tick (`ServerTickEvent`, 55 jars) | serial | estado global de mods |
| Agendador Bukkit, `processQueue` | serial | plugins assumem a thread principal; `CraftScheduler` diz que só ela controla `pending` (`CraftScheduler.java:20`) |
| `ChunkIOExecutor.tick` (fase 2 de carga) | precisa mudança | mexe no mapa de chunks e dispara eventos; poderia ir para a região dona do chunk |
| Pacote de hora (`:920-930`) | sem mexer, mas não vale | barato; envio de pacote já é fila concorrente |
| `onPreWorldTick`/`onPostWorldTick` (41 jars) | serial | estado global por mundo |
| mobSpawner | precisa mudança | conta entidades do mundo inteiro e cria entidades em qualquer chunk |
| chunkSource (descarregar chunks, hora do mundo) | serial | mexe no mapa de chunks, salva, dispara `ChunkUnloadEvent`, pode descarregar a dimensão |
| tickPending (atualizações agendadas) | precisa mudança | fila única por mundo; dividir por região |
| tickBlocks (ticks aleatórios) | precisa mudança | por chunk, paraleliza bem com checagem de dono nas bordas |
| chunkMap (`PlayerManager`) | precisa mudança | estrutura por jogador, envia chunks |
| village, portalForcer, sounds/block events, chunk GC | serial | estruturas globais do mundo |
| Laço de entidades | precisa mudança | alvo principal; exige dono por região e passagem de entidade na borda; `tickPosition` é um cursor único por mundo |
| Laço de TEs | precisa mudança | alvo principal (máquinas); `tileTickPosition` e remoções no meio da lista precisam virar por região |
| TEs pendentes, remoção de entidades descarregadas, clima | serial | mudam as listas públicas da seção 4 |
| `EntityTracker.updateTrackedEntities` | precisa mudança | lê todas as entidades e muda conjuntos de jogadores; pode rodar depois da fase paralela, por mundo |
| `worldTickTimes`, `unloadWorlds` | serial | ver D-07 |
| `networkTick` | serial | pacotes mudam qualquer mundo e o tick segura o lock da lista de conexões (seção 6) |
| `sendPlayerInfoToAllPlayers`, `tickables`, autosave, FML pós-tick | serial | globais |

## 6. O que o stall de produção mostra

Arquivo: `anexos/stall/stall.txt` (cabeçalho, linhas 1-3): 2026-11-09 13:36:21, **411 threads**, servidor parado há pelo menos 30 000 ms, heap 6459 / 15032 MB, sampler 1.60.1.

**Estado das threads** (contagem pela linha após o nome): 34 RUNNABLE, 1 BLOCKED, 270 TIMED_WAITING, 106 WAITING.

**Por dono** (classificação pelo nome; soma 411):

| Dono | Threads |
|---|---|
| Distant Horizons (`DH-*`, pacote `com.seibel.distanthorizons`): 3 por dimensão (Update Propagate Queue, Request Module Ticker, DataSource Request Ticker) x 64 rótulos de dimensão = 192, mais 16 auxiliares | 208 |
| Plugins identificáveis pelo nome (LuckPerms 12, DiscordSRV/JDA/OkHttp, TAB 8, bStats 6, LiteBans, GriefPrevention `gpp-asyncsave` 5, EverNifeCore 8, Skript, ProtocolLib, Votifier, ClearLag, MySQL) | 70 |
| Genéricas, dono só pela pilha: `ForkJoinPool-N-worker` 24 (todas do mesmo pool `@2a02b51a`, dono não identificado), `pool-N-thread` 12, `Thread-N` 10 (geckolib 3, Skript, ChestShop, BuildCraft, Armourer's Workshop, omniconfig, LiteAnnouncer, CommandNPC), `Timer-N` 9, `FileSystemWatchService` 5 | 60 |
| Outros mods pelo nome (IC2 8, Akashic*, AE2 Compass, BuildCraft, OpenComputers, CoroUtil `World Simulation Thread`, Hodgepodge, Armourer's Workshop, SaveRelief, Mirai) | 31 |
| **Base Crucible** (Server thread, console, Infinisleeper, Netty IO 8, File IO 1, Chunk I/O 1, Craft Scheduler 2, Watchdog, Spigot Metrics, Snooper) | 18 |
| JVM | 9 |
| spark | 6 |
| Sem dono claro (`Read/Write-Poller/Updater`, `[SaveConfigThread]` etc.) | 9 |

Leituras:
- A base só tem 18 threads; 393 são de mods, plugins e JVM. Com 7 vCores, o orçamento real para threads de região é pequeno (proposta: no máximo 4 a 5 trabalhadoras, **nao verificado** por medida).
- Só 1 `Chunk I/O Executor` (menos de 50 jogadores) e 1 `File IO Thread`. Nenhuma `Lighting Thread`, coerente com `use-async-lighting: false`.
- No instante do dump, a única thread executando código de mundo `net.minecraft` era a `Server thread` (a `File IO Thread` estava em `ThreadedFileIOBase`, com mixin do Hodgepodge, linha 3258). As 208 threads do DH estavam esperando.

**O que a thread do tick fazia** (linhas 5-33): não estava no tick de mundo, e sim em `networkTick` (`MinecraftServer.java:1023` -> `NetworkSystem.java:181`, segurando o lock `SynchronizedRandomAccessList@3013fac6`). Processava um pacote `C08PacketPlayerBlockPlacement` -> `ItemInWorldManager:425` -> `ForgeEventFactory.onPlayerBukkitInteract:318` -> `PlayerInteractEvent` -> ChromatiCraft `AbilityHelper.clickBlock` -> `playerMakeBox` -> `AbilityCalls.shiftArea:553` -> `FilledBlockArray.setBlock` -> `HashMap$TreeNode.putTreeVal`. Ou seja: uma habilidade de jogador montando uma caixa de blocos enorme dentro do tratamento de um clique, por mais de 30 s.

**Efeito colateral**: `Netty IO #3` está BLOCKED (linhas 3674-3686) em `ProtocolLib NettyProtocolInjector$1.initChannel:130`, esperando o MESMO monitor `@3013fac6`. Enquanto o tick processa pacotes, conexões novas nessa thread Netty ficam travadas. O desvio do Thermos (flag `processing`, `NetworkSystem.java.patch:51-69`) não ajuda porque o ProtocolLib sincroniza na lista diretamente.

**Lições para o desenho**:
1. O lag de produção pode vir da fase de rede, que é global e serial no Degrau B. Isolar por região não resolve um tratador de pacote lento; precisa também de orçamento por jogador/pacote ou de mandar o pacote para a região dona.
2. Qualquer lock segurado pelo tick (aqui, a lista de conexões) é sentido por outras threads. Na fase paralela, nada global pode ficar travado durante trabalho longo.
3. O Watchdog só observa a `primaryThread`; com threads de região precisa observar cada uma.

## Nao verificado

- Ordem exata entre `super.updateTimeLightAndEntities()` e `executePendingCommands()` em `DedicatedServer` (fonte vanilla ausente).
- Que `NetworkManager.channelRead0` executa pacotes `hasPriority()` direto na thread Netty (vanilla, fonte ausente).
- Posição exata de "tallying"/`tickTimeArray` (vanilla, só deduzida pelas seções do profiler).
- Variante do D-07 com NPE em `:944`, risco de salvar durante o tick no desligamento, e o bloqueio de `System.exit` dentro do gancho: deduzidos do código, não reproduzidos.
- Dono das 24 threads `ForkJoinPool-N`, 12 `pool-N-thread` e 9 `Timer-N`.
- Número ideal de threads de região para 7 vCores.

---

## Revisão independente (outro agente, instruído a tentar refutar)

Resultado: confirmado: 11, impreciso: 1.

The report holds up on the points that matter. Re-deriving D-07 from the code gives the same root cause. The shutdown hook (ServerShutdownThread.java:16) runs stopServer() on its own thread while the Server thread keeps ticking, because serverRunning is never cleared. The Forge setWorld(id, null) loop then removes the id from worldTickTimes between L943 and L1016, so get(id) is null at L1016. The bug is still present at cd1de3d, and the line mapping and tick-phase order are correct.
Claim 6 is imprecise: the pool is max(1, ceil(p/50)) via a Crucible patch, and synchronous loads do stage 1 on the tick thread.
Main omissions: the double concurrent stopServer() after the NPE, the global lock in Bukkit callEvent, world access from the Netty chat path, and the fact that a blocked Netty loop freezes all of its connections. Claim 7's Netty execution rests on indirect evidence. The D-07 crash report itself was not available to check.

### Afirmações corrigidas

- **impreciso**: 6. ChunkIOExecutor pool has 1 thread plus 1 per 50 players; stage 1 async; stage 2 on the tick thread in ChunkIOExecutor.tick() at :917
  - Correção: Pool size: the forge file alone (ChunkIOExecutor.java:25) has an integer-division bug, `ceil(players / 50)`. Crucible patches it in patches/net/minecraftforge/common/chunkio/ChunkIOExecutor.java.patch to `ceil((double) players / 50)`. The real formula is max(1, ceil(players/50)): 1 to 50 players give 1 thread, 51 to 100 give 2. That is not '1 plus 1 per 50'. The report body states the formula correctly but cites only the forge file, not the Crucible patch. Stages: async stage 1 is true only for queued loads (loadChunk with a Runnable, which in practice means PlayerManager). loadChunk/provideChunk without a callback goes to ChunkIOExecutor.syncChunkLoad -> AsynchronousExecutor.skipQueue (forge util/AsynchronousExecutor.java:342-345). That path runs callStage1 (disk read and NBT decode) AND callStage2 on the calling thread, which is the tick thread for mod and plugin loads. Stage 2 for queued loads does run in finishActive() via ChunkIOExecutor.tick() at L917. The stage 2 contents are confirmed, including the ChunkLoadEvent added by ChunkIOProvider.java.patch.

### O que o relatório deixou de fora ou errou

- D-07 is worse than the report says: the hook's stopServer() never sets serverStopped, it only checks it at patch:445. So when the tick thread then dies from the NPE, run()'s finally block (final L753-755) calls stopServer() a SECOND time on the Server thread while the hook may still be running it. The result is concurrent disablePlugins, terminateEndpoints, saveAllWorlds and setWorld loops, followed by systemExitNow() during shutdown hooks.
- More D-07 variants from the same cause that the report does not list: DimensionManager.getIDs() is `worlds.keySet().toArray(new Integer[worlds.size()])` (forge DimensionManager.java:186). That is not atomic, so a removal between size() and toArray leaves a trailing null and unboxing at L937 throws an NPE. setWorld also rebuilds MinecraftServer.worldServers and removes entries from MinecraftServer.worlds (Crucible DimensionManager.java.patch:141) while the tick thread reads them.
- The actual D-07 crash report is not in the scratchpad (there is no server-test3/crash-reports folder and no log contains the :1016 trace). The runtime trigger is backed only by boot3.log:24591 ('Stopping server' logged from [Thread-7]) and by the code path, not by the original stack trace.
- The stall effect is understated. A Netty event loop thread serves many channels, so with Netty IO #3 BLOCKED, every existing player connection on that loop also stops reading and writing, not only new connections.
- Synchronous chunk loads (loadChunk/provideChunk with no callback, which is what mods and plugins do) run the disk read and NBT decode on the tick thread via ChunkIOExecutor.syncChunkLoad -> skipQueue (AsynchronousExecutor.java:342-345). Only PlayerManager's queued loads are async. This matters for budgeting per-region ticks.
- Bukkit SimplePluginManager.callEvent (bukkit/.../SimplePluginManager.java:476-487) fires every synchronous event inside synchronized(pluginManager) and throws IllegalStateException for async events fired from the primary thread. In the parallel phase (Degrau B), every Bukkit sync event from region threads would serialize on one global lock. That is a key design constraint, and the report omits it.
- Async chat does world work on the Netty thread: the @mention loop calls ep.worldObj.playSoundAtEntity(...) (NetHandlerPlayServer.java.patch:768, also present in 67b53034). That posts Forge PlaySoundAtEntityEvent and reaches world listeners off the tick thread. This is beyond the playerEntityList iteration the report mentions.
- NetworkSystem's Thermos deferral has a lost-connection race: after processing=false it runs networkManagers.addAll(stack) and then stack.clear() (patch:69-71). A NetworkManager that a Netty thread adds to stack between those two calls (possible because the flag is not volatile) is dropped and never ticked.
- Report line references for ServerConfigurationManager.java.patch (234, 319) are from HEAD. At 67b53034 they are 226 and 311, because ac070ce and 61cadb7 changed that patch.
- The tick-order table leaves out the vanilla block between updateTimeLightAndEntities and autosave that refreshes the server-list player sample every 5 s (final ~L843-858). The order of the listed phases is otherwise correct, and I checked it against the computed final numbering (L824-L897, L902-L1039).
