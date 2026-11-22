# NeoFolia: o que é, como é montado e o que mudou em relação ao Folia

- **Projeto lido:** NeoFolia, https://github.com/NeoFolia/NeoFolia
- **Commit lido: `fa76006de0c01f5cd0c3290f0994898c39e8441f`** (2026-06-04 18:04:56 +0300, "Update README.md"). Clone raso em `.../estudo/folia/NeoFolia`.
- Histórico: clone separado, sem conteúdo de arquivos (`git clone --bare --filter=blob:none`), em `.../estudo/folia/neofolia-hist/hist.git`, só para contar commits e autores.
- **Folia, para comparar:** commit `eca40f918928e78c2c5f9c944cfb083d7d9851d4` (clone em `.../estudo/folia/Folia`). Para o lado do Folia uso os patches direto e os dois relatórios irmãos desta pasta (`folia-regioes.md`, `folia-global-correio.md`), que citam linha por linha.
- **Método:** só li código e patches. Nada compilado, nenhum servidor ligado, nenhum commit no Hoshiestar.
- "Linha N" de um `.patch` = linha dentro do arquivo `.patch` (código novo = linhas com `+`). Os caminhos são relativos a `NeoFolia/` (ou `Folia/`).
- Abreviações: **NF-region** = `NeoFolia/src/main/java/net/neoforged/neoforge/server/region/`. **A4** = `Folia/folia-server/minecraft-patches/features/0004-Region-Threading-Base.patch`. **A7** = `Folia/.../features/0007-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch`.

---

## 1. Resumo (10 linhas)

1. O NeoFolia é um **fork do NeoForge** (carregador de mods) para **Minecraft 26.1.2 / Java 25**, com licença **LGPL 2.1** herdada do NeoForge. Não é fork do Folia e não tem código do Folia copiado (nenhum `// Folia` nem classe do Folia no repositório).
2. O projeto é **muito novo e pequeno**: 22 commits, todos em 3 e 4 de junho de 2026, 2 autores (conta "NeoFolia" e "kitovoi111"), 0 issues abertas. O código entrou num commit só ("First release", 6.698 arquivos). As notas de versão dizem "Mod Compatibility = 0" (não testado).
3. A parte nova mora em 9 classes (`NF-region`, 3.742 linhas) mais ~620 patches de classes do Minecraft e ~48 arquivos do NeoForge que chamam essas classes.
4. **Regionizador:** grade fixa de 16×16 chunks (`shift 4`). A cada tick a thread principal recalcula do zero quais quadrados se juntam (união por vizinhança, raio 3). A distância de junção é a mesma do Folia (os testes citam "Folia empty bridge distance"), mas o mecanismo é outro: não tem estados, nem junção adiada, nem divisão.
5. **Não há relógio por região.** A thread principal ("Server thread") continua dona do laço de tick a 20 TPS. Em cada tick ela **posta tarefas** por região (ticks de entidade, de tile entity, de chunk, ticks agendados) num pool de threads, e **não espera** elas terminarem.
6. **"Região global" = a thread principal.** Ela drena uma fila global (`drainGlobalTasks`) dentro do `tickChildren`, com limite de 1.024 tarefas e 10 ms por tick. No Folia não existe thread principal: a global é só mais uma tarefa no pool.
7. **Correio entre regiões:** uma fila FIFO por região (`RegionQueue`). Quem posta para outra região não espera. Para mexer em duas regiões ao mesmo tempo existe `awaitRegions`, que **trava as threads das duas regiões** até a tarefa acabar. Uma thread de região **não pode** esperar outra região: isso lança exceção.
8. **Mods:** os eventos `ServerTickEvent` e `LevelTickEvent` continuam na thread principal. `EntityTickEvent`, `PlayerTickEvent`, tick de tile entity, tick aleatório e ticks agendados passam a rodar nas threads de região, **ao mesmo tempo** que a thread principal. O barramento de eventos não foi mudado.
9. As "capabilities" do NeoForge (itens, fluidos, energia de bloco/entidade) são embrulhadas para rodar na região dona. Pacotes de mod (`enqueueWork`) vão para a região do jogador. Acesso a capability de outra região, vindo de uma thread de região, lança exceção.
10. Exceção dentro de tarefa de região é **só registrada no log** e o servidor segue. No Folia, a mesma falha para o servidor inteiro. Não achei vigia (watchdog) para as threads de região.

---

## 2. Termos (primeira vez)

- **chunk:** pedaço de 16×16 blocos do mundo.
- **região:** conjunto de chunks vizinhos que tica junto, numa thread só por vez.
- **regionizador:** o código que decide a qual região cada chunk pertence e quando regiões se juntam ou se dividem.
- **"fixed region" / quadrado fixo (NeoFolia):** quadrado de 16×16 chunks numa grade fixa. É a unidade do NeoFolia. Equivale à "seção" do Folia.
- **dono (owner):** no NeoFolia, o quadrado fixo que dá nome a um grupo de quadrados que se juntaram. A fila de tarefas do grupo usa a chave do dono.
- **tarefa (task):** um pedaço de código posto numa fila para outra thread rodar.
- **fila global:** fila de tarefas que não pertencem a nenhuma região (clima, salvamento, dificuldade).
- **"lane" (raia):** rótulo de texto de uma tarefa no NeoFolia ("entity-ticks", "chunk-ticks"...). Serve para medir e para juntar tarefas repetidas.
- **coalescer:** não pôr na fila uma tarefa igual a outra que ainda está esperando. Se já tem uma pendente, a nova é descartada.
- **capability:** no NeoForge, a forma de um mod pedir "o inventário / tanque / energia" de um bloco ou entidade de outro mod.
- **pool de threads:** um grupo fixo de threads que pega trabalho de uma fila.
- **union-find:** algoritmo simples para juntar elementos em grupos ("une estes dois", "a qual grupo pertence este").

---

## 3. O que é o projeto

### 3.1 Base, versão, carregador

- **Carregador de mods:** NeoForge/FML. README linha 9: "This is not a Paper/Bukkit plugin. The runtime stays NeoForge/FML — scheduling and ownership are exposed through native NeoForge API interfaces, not Bukkit or Paper APIs." (da documentação; no código confirmei que a classe de partida é `net.neoforged.fml.startup.Server`, `server_files/args.txt`.)
- **Versão:** `minecraft_version=26.1.2`, `java_version=25` (`gradle.properties`). O Folia lido está em 26.3. Nenhum dos dois é 1.7.10.
- **De onde parte:** é **fork do NeoForge**, no formato de repositório do NeoForge (`patches/` com um `.patch` por classe do Minecraft, 1.511 arquivos, mais `src/main/java`). Não é a pilha de patches do Folia e não aplica patches do Folia.
  - Evidência: busca por `folia`, `spottedleaf`, `moonrise` em todo o repositório (fora do README) só achou caminhos de uma pasta local do autor em `.vscode/launch.json` ("C:\FoliaNeoForge\NeoFolia\...") e nomes de teste. Nenhuma classe do Folia (`ThreadedRegionizer`, `TickRegionScheduler`, `RegionizedTaskQueue`, `TickThread`...) existe (índice, seção 2.5, conferido de novo com `git grep`).
  - Mas há **ideias do Folia reimplementadas**, com nomes próprios:
    - `RegionizedWorldData` (NF-region, 88 linhas) usa um subconjunto dos nomes de campo da classe de mesmo nome do Folia (A4 linha 2945 em diante): `catSpawnerNextTick`, `patrolSpawnerNextTick`, `phantomSpawnerNextTick`, `VillageSiegeState`, `wanderingTraderData`, `lastSpawnState`, `pendingBlockEntityTickers`, `blockEntityTickers`, `tickingBlockEntities`. A do Folia tem também listas de entidades, chunks, conexões, ticks agendados, `redstoneTime` etc. (A4 2951-3318).
    - O commit `d1ff8c7` ("Folia patch porting and bug fixes") mexe exatamente nos arquivos do patch A7 do Folia: `Level`, `DetectorRailBlock`, `PoweredRailBlock`, `RedStoneWireBlock`, `TripWireBlock`, `TripWireHookBlock`, `CollectingNeighborUpdater`. Ver seção 7.4.
    - `RegionizedLoginLimiter` (`max_pending_logins`) lembra o patch 0005 "Max pending logins" do Folia. Contadores de carga e geração de chunk no `/tps` (`ServerChunkCache.java.patch`, `chunkLoadRatePerSecond`) lembram o 0006 "Add chunk system throughput counters to /tps". Isso é semelhança de nome e assunto; não comparei linha a linha.
  - **Versão exata do NeoForge de base: não verificado.** O histórico do NeoFolia não traz os commits do NeoForge (o código chegou de uma vez só no commit `b10c794`), e não cruzei com o repositório do NeoForge.

### 3.2 Manutenção

Fonte: clone de histórico (`git rev-list`, `git shortlog`) e páginas públicas do GitHub (lidas em 2026-11-22).

| Item | Valor | Fonte |
|---|---|---|
| Último commit | 2026-06-04, "Update README.md" | `git log` |
| Total de commits | 22 | `git rev-list --count HEAD` |
| Período | 2026-06-03 a 2026-06-04 (dois dias) | `git log` |
| Autores | "NeoFolia" (18 commits, quase todos "Update README.md") e "kitovoi111" (4 commits de código) | `git shortlog -sne` |
| Commit de código grande | `b10c794` "First release" (6.698 arquivos adicionados); `1b4210c` "Delete others files" (1.852 removidos); `d1ff8c7` "Folia patch porting and bug fixes" (82 arquivos) | `git show --name-status` |
| Tags | `neofolia`, `1.0.1`, `1.0.2` (mesmo commit `3c0c161`), `1.0.3`, `1.0.4` | `git ls-remote` |
| Issues abertas | 0 (o número de fechadas não aparece) | página /issues (da documentação, não conferido no código) |
| Notas de versão | 1.0.0 "first alpha"; 1.0.3 "added region merging"; 1.0.4 "Bug fixes and improved server diagnostics"; todas com "Mod Compatibility = 0" | página /releases (fonte secundária) |

Observação: a junção de regiões (`union`, `PlayerRegionGroups`) **não existe** em `b10c794` nem em `6ea7b04` (tag 1.0.3): `RegionizedWorldGuard.java` tem 570 linhas e zero `union(`. Ela aparece em `d1ff8c7` (tag 1.0.4), com 1.230 linhas. A nota de versão diz que foi na 1.0.3. Pode ser só diferença entre tag e release; não verificado.

Leitura minha: projeto de uma pessoa, em estágio alfa, sem histórico de manutenção. Não serve de base para copiar; serve como exemplo de **onde** é preciso mexer.

### 3.3 Licença

- `LICENSE.txt` = LGPL 2.1; `README-LICENSE.md` = NeoForge sob LGPL-2.1-only. As classes novas trazem o cabeçalho "Copyright (c) Forge Development LLC and contributors / SPDX-License-Identifier: LGPL-2.1-only" (ex.: NF-region/`RegionKey.java` linhas 1-4).
- Não achei licença própria do NeoFolia. O Folia é GPL v3 (relatório irmão).

---

## 4. Como o repositório é organizado e como o build junta as partes

- `patches/net/minecraft/**.java.patch` e `patches/com/mojang/**`: um patch por classe do Minecraft, no formato do NeoForge (`@@ -987,11 +_,20 @@`). 622 deles mencionam "region" (índice, seção 2.4). Os mais carregados: `ChunkMap` (165 ocorrências), `ServerGamePacketListenerImpl` (146), `ServerLevel` (127), `ServerPlayer` (66), `PlayerList` (61), `ServerChunkCache` (59), `Level` (53), `MinecraftServer` (38).
- `src/main/java/net/neoforged/neoforge/...`: código do NeoForge. O pacote novo é `server/region/` (9 arquivos). Outros 39 arquivos do NeoForge passaram a usar `Regionized*` (capabilities, transfer, network, attachment, comandos; lista no índice, seção 2.3).
- `tests/src/junit/.../server/region/`: 4 testes (1.032 linhas). Não rodei.
- `projects/base`, `projects/neoforge`: só `build.gradle`. `coremods/`, `testframework/`: do NeoForge. `rejects_*`: patches rejeitados em portes (não abri).
- **Build:** é o build normal do NeoForge (NeoDev / ModDevGradle `2.0.140`, `settings.gradle`; `projects/neoforge/build.gradle` linhas 83-103 puxam o NeoForm `26.1.2-1`, que descompila o Minecraft; os patches são aplicados por classe e compilados junto com `src/main/java`; saem um jar "universal" e um instalador, linhas 414-530). Não executei o build.
- Configuração do servidor: `config/neofolia.toml`, lida por `RegionizedServerSettings` (NF-region, linha 24).

Comparação: o Folia guarda tudo como poucos patches enormes sobre o Paper (A4 tem 20.470 linhas e 208 arquivos). O NeoFolia espalha a mudança por centenas de patches, um por classe, mais um pacote Java próprio.

---

## 5. Regionizador: mantido, adaptado ou reescrito?

**Resposta: reescrito.** A geometria (tamanho do quadrado, distância de junção) foi copiada do Folia. O mecanismo é outro e muito mais simples.

### 5.1 Como o NeoFolia divide o mundo

- Chave de região: `record RegionKey(ResourceKey<Level> level, int regionX, int regionZ)`, com `regionX = chunkX >> shift` (NF-region/`RegionKey.java` linhas 16-19). Cada dimensão tem suas regiões.
- `shift` fixo = 4, ou seja 16×16 chunks: `DEFAULT_REGION_CHUNK_SHIFT = 4` (`RegionizedWorldGuard.java` linha 45). Não é configurável.
- Quais quadrados estão "ativos": os que têm pelo menos um **chunk holder** (o objeto que o servidor cria quando um chunk ganha ticket) **ou** um jogador.
  - Contagem de chunk holders por quadrado: `ChunkMap.java.patch` linhas 1740-1755 (`updateRegionizedChunkHolder`), chamada ao criar o holder (linha 325) e ao descarregar (linha 603). É o mesmo gatilho do Folia (criação/remoção de chunk holder, `folia-regioes.md` item 3).
  - Jogadores entram como ativos mesmo antes do holder existir: `GroupSnapshot.build` linhas 1012-1024 e 1044-1053; teste `treatsPlayerSectionsAsActiveBeforeHolderSnapshotCatchesUp`.
- Regra de junção (`GroupSnapshot.build`, `RegionizedWorldGuard.java` linhas 1026-1093):
  1. Todo quadrado ativo começa como grupo próprio.
  2. Para cada par de quadrados ativos a até `PLAYER_REGION_MERGE_SEARCH_RADIUS = EMPTY_REGION_CREATE_RADIUS*2 + REGION_MERGE_RADIUS = 1*2+1 = 3` quadrados de distância (nas duas direções, distância de Chebyshev), faz `union` (linhas 1060-1073; constantes nas linhas 46-48).
  3. O dono do grupo é o quadrado com menor X (empate: menor Z) (`canonicalOwner`, linhas 1122-1132).
  4. Cada quadrado ativo "cobre" os vizinhos num raio de 1 (margem vazia); esses vizinhos passam a ter o mesmo dono (linhas 1078-1088).
  5. Um quadrado fora de qualquer cobertura não tem dono: `resolve` devolve o próprio quadrado (linha 924).
- Os testes confirmam a distância: quadrados 0 e 3 se juntam, 0 e 4 não (`RegionizedWorldGuardTest.java` linhas 24-43, métodos `mergesActiveSectionsAcrossFoliaEmptyBridgeDistance` e `keepsActiveSectionsSeparateBeyondFoliaEmptyBridgeDistance`).

```java
// RegionizedWorldGuard.java linhas 46-48
private static final int EMPTY_REGION_CREATE_RADIUS = 1;
private static final int REGION_MERGE_RADIUS = 1;
private static final int PLAYER_REGION_MERGE_SEARCH_RADIUS = EMPTY_REGION_CREATE_RADIUS * 2 + REGION_MERGE_RADIUS;
```

- Quando é recalculado: **todo tick**, do zero.
  - `MinecraftServer.tickServer` chama `refreshRegionOwnership()` logo depois de `tickCount++` (`MinecraftServer.java.patch` linhas 183-187), que chama `RegionizedWorldGuard.refreshChunkRegions` para cada mundo (linhas 339-346).
  - Além disso, qualquer consulta (`resolve`, `boundsFor`...) refaz o retrato se o número do tick mudou (`CachedLevelGroups.refreshIfNeeded`, linhas 947-960, com `synchronized`). Pode rodar em qualquer thread.
  - O resultado é guardado como mapas imutáveis em campos `volatile` (linhas 911-916, 976-983). Quem lê vê o retrato velho ou o novo, nunca a metade.

### 5.2 Diferenças em relação ao Folia (com evidência)

| Ponto | Folia (`eca40f91`) | NeoFolia (`fa76006d`) |
|---|---|---|
| Unidade | seção de 2^`gridExponent` chunks, padrão 4 = 16×16, configurável (B2 linha 437; A4 5811) | quadrado fixo de 16×16 chunks, shift 4 fixo no código (`RegionizedWorldGuard` 45) |
| Distância de junção | margem 1 seção + raio de junção 1; ativas a até 3 seções se juntam (A4 4066-4068; `folia-regioes.md` §3.2) | raio de busca 3 entre quadrados ativos (linhas 46-48, 1060-1073); mesmo resultado, conferido pelos testes |
| Mecanismo | `ThreadedRegionizer` incremental: reage a cada chunk holder criado/removido, com estados transiente/pronta/ticando/morta (A4 3788, 4437-4440) | retrato refeito do zero a cada tick, com union-find; não há estados |
| Junção com região ticando | adiada para o fim do tick da região (A4 `onRegionRelease` 4258) | não existe esse cuidado: o dono muda no próximo retrato, com ou sem tarefas rodando (ver 7.3) |
| Divisão | só no fim do tick, por busca em largura das seções vivas (A4 4290-4377) | implícita: no próximo retrato os grupos simplesmente saem diferentes |
| Dados por região na junção/divisão | `RegionizedData` com funções `merge`/`split`; prazos ajustados por deslocamento de tick (A4 1423-1561, 1495-1504) | não há merge/split. `RegionizedWorldData` fica num mapa estático por chave de dono (`REGION_WORLD_DATA`, linha 52); tickers de tile entity são migrados um a um quando o dono muda (`Level.java.patch` linhas 424-445) |
| Entidades por região | listas dentro de `RegionizedWorldData` do Folia | mapa estático global por quadrado fixo, com `synchronized` (`RegionEntityBuckets`/`DimensionEntityBuckets`, linhas 737-869) |

Observação minha (não testada): `REGION_WORLD_DATA` e `regionQueues` só ganham entradas (`computeIfAbsent`); só são limpos ao desligar (`RegionizedTaskDispatcher.close`, linha 952). Busquei `REGION_WORLD_DATA.remove` e `regionQueues.remove`: nada. Num servidor que roda muito tempo e explora muito, os mapas podem crescer sem limite.

---

## 6. Agendador de regiões: mantido, adaptado ou reescrito?

**Resposta: reescrito, com outro modelo.** No Folia, cada região é um objeto com agenda própria. No NeoFolia, a "região" é só a chave de uma fila de tarefas; quem marca o ritmo é a thread principal.

### 6.1 Como funciona no NeoFolia

- `RegionizedTaskDispatcher` (NF-region, 1.571 linhas). Começa em `MinecraftServer.runServer`, depois de `handleServerStarted` (`MinecraftServer.java.patch` linhas 123-124). Para em `close()` no `finally` (linha 150).
- Pool fixo: `Executors.newFixedThreadPool(n)`, cada thread roda `workerLoop` (linhas 82-95, 961-973). Threads daemon chamadas "NeoForge Region Worker #N" (linhas 1561-1570).
- Número de threads: `region_threads` do toml. `0` = automático = 90% dos núcleos menos 1 (thread do servidor) menos as threads de chunk, IO, parse e netty (`RegionizedServerSettings.java` linhas 168-177).
- Uma fila por região (`RegionQueue`: `ConcurrentLinkedQueue` + flag `scheduled`, linhas 1451-1559). Quando a fila recebe a primeira tarefa, ela entra numa fila de "regiões prontas" (`readyRegions`, `LinkedBlockingQueue`, linhas 56 e 1461-1466). Uma thread livre pega a região e roda até 256 tarefas ou 5 ms (`region_task_max_tasks_per_run`, `region_task_max_time_ms`; linhas 1475-1507). Sobrou tarefa? A região volta para o fim da fila de prontas (linhas 1508-1513).
- Só uma thread por vez roda a fila de uma mesma chave (flag `scheduled`). Isso dá ordem FIFO dentro da região.
- Antes de rodar, a fila confere se a chave ainda é dona. Se não for, repassa as tarefas para o dono atual (`runAvailableTasks` linhas 1468-1473; `redirectPendingTasks` 1516-1531).
- Cada tarefa roda dentro de `RegionizedTickContext.runInRegion`, que guarda num `ThreadLocal` a região atual e um `RandomSource` próprio da região (NF-region/`RegionizedTickContext.java` linhas 15-16, 33-54).

### 6.2 Quem gera as tarefas de tick: a thread principal, a cada tick

A thread principal continua no laço vanilla (`while (this.running)`, `MinecraftServer.java.patch` linha 130) e em `tickServer`/`tickChildren`/`ServerLevel.tick`. Onde o vanilla faria o trabalho direto, ele agora agrupa por região e posta:

| Trabalho | Onde | Como posta |
|---|---|---|
| Ticks de entidade | `ServerLevel.java.patch` linhas 225-255 (`tickEntitiesRegionized`) | `executeCoalescedAll("entity-ticks", ...)` |
| Tile entities (block entities) | `Level.java.patch` linhas 393-411 | `executeCoalescedAll("block-entity-ticks", ...)` |
| Chunk tick (tick aleatório, neve, raio) | `ServerChunkCache.java.patch` linhas 333-342; `ServerLevel.tickChunkRegionized` (linhas 365-410 do patch) | `executeCoalescedAll("chunk-ticks", ...)` |
| Spawn natural e spawners especiais | `ServerChunkCache.java.patch` linhas 262-330 | `executeCoalescedAll("spawning-chunk-ticks" / "custom-spawners", ...)` |
| Envio de mudanças de chunk | `ServerChunkCache.java.patch` (`broadcastChangedChunks`) | `executeCoalescedAll("chunk-broadcasts", ...)` |
| Rastreador de entidades, descarga de chunk | `ChunkMap.java.patch` linhas 220, 393, 1550 | `executeCoalescedAll(...)` |
| Ticks agendados de bloco e fluido | `ServerLevel.java.patch` linhas 309-363 | `executeAll(...)` (não coalescido) |
| Tick da conexão (e do jogador) | `ServerConnectionListener.java.patch` linhas 154-171; `ServerGamePacketListenerImpl.java.patch` linha 154 (`ensureRegionizedConnectionTicker`) | posta na região do jogador e se repõe sozinho |

- **Não há barreira por tick.** `executeCoalescedAll` e `executeAll` só põem na fila e voltam (`RegionizedTaskDispatcher.java` linhas 111-146). O método que espera tudo (`awaitAll`, linhas 257-290) **não é usado** em lugar nenhum fora da própria classe (conferido com `grep` em `patches/` e `src/main/java`). Então o tick N+1 da thread principal pode começar com tarefas do tick N ainda rodando.
- **Região atrasada pula ticks.** Na versão coalescida, se já existe uma tarefa da mesma raia esperando para aquela região, a nova é descartada (`tryExecuteCoalesced`, linhas 152-176: `putIfAbsent` devolve não nulo → `return false`). Leitura minha: uma região lenta perde ticks de entidade/tile entity em vez de acumular. O Folia também não recupera ticks perdidos (`folia-regioes.md` item 9), mas lá cada região tem a sua própria agenda.
- **Não há TPS por região nem relógio por região.** O "tick" de todas é o `tickCount` da thread principal (`beginServerTick(this.tickCount)`, linha 184 do patch; `currentServerTick`, linha 59 do despachante). Os ticks agendados usam `this.getGameTime()` do mundo (`ServerLevel.java.patch` linha 175). O `/tps` mostra tempos de tarefa por região (`RegionTiming`, `regionPerformanceSnapshots`, linhas 855-880), não um tick próprio da região.

### 6.3 Diferenças em relação ao Folia

| Ponto | Folia | NeoFolia |
|---|---|---|
| Quem dá o ritmo | cada região tem agenda própria (`TickRegionScheduler`, A4 5200; escalonador EDF ou roubo de trabalho, A4 5219-5222) | a thread principal, uma vez por tick, posta tarefas para as regiões |
| Thread principal | não existe (`folia-global-correio.md` item 1; A4 1602-1806) | existe e é a dona do laço |
| Unidade de trabalho | "tick da região" inteiro + tarefas entre ticks | tarefas soltas por raia; o tick de uma região é a soma das tarefas daquele tick |
| Região atrasada | tica uma vez e a agenda pula para frente (A4 5631-5635) | tarefa coalescida descartada; ticks agendados (não coalescidos) acumulam |
| Falha | `regionFailed` para o servidor (A4 5416-5429) | `runSafely` registra no log e segue (`RegionizedTaskDispatcher.java` linhas 975-983, usado na 1488) |

---

## 7. Thread global e filas entre regiões

### 7.1 A "global" do NeoFolia

- É a **thread principal**. Fila: `globalTasks` (`ConcurrentLinkedQueue`, linha 57). Postar: `executeGlobal`/`tryExecuteGlobal`/`submitGlobal`/`tryExecuteGlobalCoalesced` (linhas 588-670).
- Drenada em `tickChildren`, logo no começo, antes de `commandFunctions` e dos mundos (`MinecraftServer.java.patch` linhas 263-265), com limite de 1.024 tarefas e 10 ms por tick (`drainGlobalTasks` linhas 672-709; padrões em `RegionizedServerSettings.java`, `DEFAULT_GLOBAL_TASK_MAX_TASKS_PER_TICK = 1024`, `DEFAULT_GLOBAL_TASK_MAX_TIME_MS = 10`).
- Além da fila, a thread principal continua fazendo tudo que não foi regionizado: o resto de `ServerLevel.tick` (tempo, clima, sono, raides, ver `ServerLevel.java.patch` linhas 151-190), `commandFunctions`, `playerList.tick()`, salvamento automático, status do servidor, e os eventos de tick do servidor e do mundo (seção 8).
- O que vai para a fila global quando é chamado de uma thread de região: salvar (`saveAllChunks`, `saveEverything`), `halt`, `setDifficulty` (`MinecraftServer.java.patch` linhas 81-115 e 413-417), lista de quem dorme e clima (`ServerLevel.java.patch` 483-505, 582), chunk forçado (1412), teleporte por portal (`Entity.java.patch` linhas 894-917), sincronização de attachment do mundo (`AttachmentSync.java` linha 188).

Comparação: no Folia a global é uma tarefa no mesmo pool das regiões, a 20 TPS, e **não pode** tocar em chunk, bloco nem entidade (`folia-global-correio.md` itens 1-3). No NeoFolia a thread principal **não é impedida** de tocar no mundo. Exemplo: o teleporte por portal roda o `teleport` vanilla na thread principal (`completePortalTeleport`, `Entity.java.patch` linhas 903-925) enquanto as threads de região podem estar rodando. Não achei trava que impeça isso. Se há corrida de dados aí: inferência minha, não testada.

### 7.2 Correio entre regiões (postar sem esperar)

- `execute`/`tryExecute(RegionKey, Runnable)` (linhas 97-109), `submit` → `CompletableFuture` (213-240), `tryExecuteNextTick` (178-192, segura até o próximo `beginServerTick`, linhas 770-795).
- Ajudantes usados pelos patches (`RegionizedWorldGuard.java`):
  - `scheduleEntityIfRegionized*` (linhas 314-378): se a entidade não está na região atual, posta na região dela. Ao rodar, confere de novo e, se a entidade mudou de região, reposta (linhas 369-377).
  - `scheduleAtRegionIfRegionized*` (399-438): mesmo esquema por posição de bloco.
  - `scheduleGlobalIfRegionized*` (380-397, 440-452).
  - `executeOnPlayerRegion` (504-523, 641-657).
- Ordem: FIFO dentro da mesma chave de região. Entre regiões diferentes e entre região e global não há ordem garantida (não há nada no código que imponha isso).
- Postar **não carrega chunk** e **não cria ticket** (diferente do Folia, que põe um ticket para a região dona existir; `folia-global-correio.md` item 4). Se a posição não tem dono, a tarefa cai numa fila com a chave do próprio quadrado fixo e roda numa thread do pool assim mesmo.

### 7.3 Mexer em duas regiões de uma vez: `awaitRegions`

- `awaitRegions(descrição, regiões, primária, tarefa)` (linhas 292-389): posta uma tarefa em **cada** região. Cada tarefa avisa que chegou e **fica bloqueando a thread** até todas chegarem. Aí a tarefa roda na região primária, e as outras threads ficam presas esperando ela acabar. É uma "trava de várias regiões" feita com threads paradas.
- `awaitRegionsPaused` (391-478): igual, mas a tarefa roda na thread que chamou, com as regiões paradas.
- Limites:
  - Se forem mais regiões que threads no pool → `IllegalStateException` (linhas 320-336). Com 1 thread só, roda só na primária.
  - Chamar de dentro de uma thread de região, para outra região → `IllegalStateException("Region worker ... cannot block waiting for ...")` (`ensureSafeBlockingAwait`, linhas 573-586).
  - Tempo máximo: `region_task_timeout_seconds` (padrão 60). Passou → exceção (linhas 372-388).
- Usado em: ataque e interação jogador→entidade, câmera e teleporte de espectador (`ServerGamePacketListenerImpl.java.patch` linhas 1754, 2227, 2349, 2437, 2493), `/tp`, `/spectate`, `/spreadplayers` (`TeleportCommand.java.patch` 314, `SpectateCommand.java.patch` 85, `SpreadPlayersCommand.java.patch` 183), rastreio de entidade (`ServerEntity.java.patch` 200), drops de bloco (`CommonHooks.java` 607) e transferência entre dois handlers (`ResourceHandlerUtil.java` 688, `EnergyHandlerUtil.java` 178).
- Esperar por uma região (uma só): `callOnPlayerRegionAndWait`, `callOnEntityRegionAndWait`, `callOnRegionAndWait` (`RegionizedWorldGuard.java` linhas 539-639). Todos lançam exceção se chamados de outra thread de região (linhas 548-550, 591-593, 632-634).

Comparação: o Folia não tem trava de várias regiões. Teleporte entre regiões é sempre assíncrono (tira a entidade, copia, posta na região destino; `folia-global-correio.md` item 7). O NeoFolia usa espera bloqueante, o que só é seguro porque proíbe a espera a partir de outra thread de região.

Corrida que eu vejo no código (inferência, não testada): a checagem de dono é feita só no começo de `runAvailableTasks` (linhas 1469-1473). Se o retrato muda no meio (a região A passa a pertencer ao dono B) enquanto a fila A está rodando numa thread, a fila B pode rodar ao mesmo tempo em outra thread. Durante esse lote, duas threads mexeriam no que agora é uma região só. As tarefas "conferem de novo e repostam" (linhas 369-377, 419-426), mas isso não cobre código que já começou. O Folia evita isso porque não junta região que está ticando (A4 `mergeIntoLater`).

### 7.4 Bordas

- Consulta de entidades a partir de uma thread de região é **cortada** à área da região atual (`restrictToCurrentRegion`, linhas 263-291) ou devolve `null` se a entidade é de outra região (`restrictEntityToCurrentRegion`, 293-307). Usado em `ServerLevel.java.patch` linhas 635, 1350-1404, 1529-1539. É corte silencioso, como no Folia.
- Atualização de vizinho, redstone, trilho e tripwire: o NeoFolia pôs `isRegionOwnedAndLoaded` antes de ler o vizinho (ex.: `RedStoneWireBlock.java.patch` linhas 213-241; `RegionizedWorldGuard.isRegionOwnedAndLoaded` 255-261). Vizinho de outra região é tratado como ar ou ignorado. É a mesma ideia do A7 do Folia, que faz `TickThread.isTickThreadFor(level, pos, 25)` antes (A7 linhas 11-110), mas reescrita com as funções do NeoFolia.
- Muitos métodos de `ServerLevel` que mexem em outra posição (vizinhos, eventos de bloco, partículas, som, explosão) reagendam para a região dona em vez de rodar direto: `ServerLevel.java.patch` linhas 929-1233 (`scheduleIfRegionizedOutsideRegion`...).
- Explosão que passa da região: resultado coletado de forma assíncrona (`ServerLevel.java.patch` linhas 1173-1191, `regionSpilloverResult`). Não li o `ServerExplosion.java.patch` a fundo.

Leitura minha sobre geometria: como quadrados ativos a até 3 de distância se juntam e cada um cobre raio 1, dois donos diferentes ficam separados por pelo menos 2 quadrados (32 chunks) sem chunk holder. Na prática, um vizinho de bloco carregado é sempre do mesmo dono, salvo no momento em que o retrato muda.

---

## 8. Onde o tick do carregador de mods foi encaixado

| Gancho do NeoForge | Chamado em | Thread (quando o regionizado está ligado) |
|---|---|---|
| `ServerTickEvent.Pre` / `Post` | `MinecraftServer.java.patch` linhas 188 e 217 (dentro de `tickServer`) | thread principal |
| `LevelTickEvent.Pre` / `Post` | `MinecraftServer.java.patch` linhas 278 e 286 (laço dos mundos em `tickChildren`) | thread principal |
| `EntityTickEvent.Pre` / `Post` | `ServerLevel.java.patch` linhas 604-606 (`tickNonPassenger`); `Entity.java.patch` 628-630 (passageiros) | thread da região (dentro da tarefa "entity-ticks") |
| `PlayerTickEvent.Pre` / `Post` | `Player.java.patch` linhas 49 e 66 | thread da região do jogador (tick da conexão e da entidade vão para a região; seção 6.2) |
| Tick de tile entity (`BlockEntity` com ticker) | `Level.java.patch` linhas 424-445 (`ticker.tick()` depois de `ensureRegionThreadFor`) | thread da região |
| `BlockEntity.onLoad()` | `Level.tickBlockEntities` (`Level.java.patch` linhas 340-355) | thread principal (antes de distribuir) |
| Tick aleatório e agendado de bloco/fluido | `ServerLevel.java.patch` 365-410, 327-363, 590-594 | thread da região |
| `enqueueWork` de pacote de mod | `ServerPayloadContext.java` linhas 34-80 (`enqueueGameplayWork` → `executeOnPlayerRegion`) | thread da região do jogador |
| `PermissionsChangedEvent` | `EventHooks.java` linha 904 (`callOnPlayerRegionAndWait`) | região do jogador |

- As funções `fire*` de `EventHooks.java` (linhas 960-1030) estão iguais às do NeoForge: só fazem `NeoForge.EVENT_BUS.post(...)`. O barramento (`eventbus_version=8.0.5`) é biblioteca externa e não foi mudado neste repositório (índice §2.3; conferido que não há patch nele).
- Consequência (inferência minha a partir da tabela): um mod que guarda estado num campo estático e mexe nele em `EntityTickEvent` passa a ser chamado por várias threads ao mesmo tempo. Um mod que, em `LevelTickEvent`, percorre as entidades do mundo roda na thread principal enquanto as threads de região alteram essas entidades. O NeoFolia não protege nenhum dos dois casos. O README não avisa sobre isso.
- Interruptores: `regionized_chunk_ticks`, `regionized_scheduled_ticks`, `regionized_block_entity_ticks`, `regionized_entity_ticks`, todos `true` por padrão (`RegionizedServerSettings.java` linhas 25-29, 317-335, 464-467). Com um deles `false`, aquela parte volta a rodar na thread principal (ex.: `shouldTickEntitiesRegionized`, `ServerLevel.java.patch` linhas 237-239).
- **Não há interruptor geral.** O despachante sempre liga (`MinecraftServer.java.patch` linha 124, sem condição). Mesmo com os quatro `false`, pacotes e tick da conexão ainda vão para a região do jogador (`PacketUtils.java.patch` linha 23; `ServerConnectionListener.java.patch` 163-171). Busquei "enable" e "disable" em `RegionizedServerSettings.java`: só os quatro acima.

---

## 9. Pontos específicos de mods

- **Capabilities de bloco:** `BlockCapability.getCapability` embrulha o resultado para itens, fluidos e energia (`BlockCapability.java` linhas 222-241). O embrulho (`RegionizedBlockResourceHandler`, `RegionizedBlockEnergyHandler`) manda cada leitura e escrita para a região dona, com `callOnRegionAndWait` (ex.: `RegionizedBlockEnergyHandler.java` linhas 37-59). Na região dona, roda direto. Na thread principal ou de rede, espera. **Numa thread de outra região, lança exceção** (`RegionizedWorldGuard.java` linhas 632-634).
- **Capabilities de entidade e jogador:** `EntityCapability.java` linhas 108-114; `CapabilityHooks.java` 145 e 193; classes `RegionizedEntityResourceHandler`, `RegionizedPlayerResourceHandler`, `RegionizedEntityEnergyHandler`, `RegionizedPlayerItemAccess`.
- **Transferência entre dois handlers** (`ResourceHandlerUtil`, `EnergyHandlerUtil`): desembrulha os dois e roda com `awaitRegions` nas duas regiões (`ResourceHandlerUtil.java` 653-690; `EnergyHandlerUtil.java` 154-180).
- Capabilities de outro tipo (de mods, com chave própria) **não** são embrulhadas: o `if` só cobre `Capabilities.Item.BLOCK`, `Fluid.BLOCK`, `Energy.BLOCK` (`BlockCapability.java` linhas 232-240).
- **Rede de mods:** `PacketDistributor.sendToPlayer` → `RegionizedWorldGuard.sendToPlayer` → roda na região do jogador (`PacketDistributor.java` linha 40; `RegionizedWorldGuard.java` 1174-1207). Envio para quem rastreia uma entidade é reagendado na região da entidade (`PacketDistributor.java` 78-110).
- **Attachments (dados anexados):** sincronização de bloco, chunk e entidade vai para a região dona; a do mundo vai para a global (`AttachmentSync.java` 116-188).
- **Chunks forçados de mod:** `ForcedChunkManager` (2 usos) e teste `TicketStorageRegionSafetyTest`. Não li a fundo.
- **Listas públicas do mundo:** os tickers de tile entity saem da lista do `Level` e vão para `RegionizedWorldData` a cada tick (`migrateBlockEntityTickersToRegionData`, `Level.java.patch` 413-422). Leitura minha: um mod que lê a lista do `Level` direto (por mixin ou access transformer) a veria vazia. Não verificado.
- **Lista de mods suportados / modo de compatibilidade:** não existe (busca por `supported`, `folia`, `compat` no pacote; README linha 34 só promete portar mods "once the core becomes more or less stable").

---

## 10. Erros, tempo limite e vigia

- Exceção numa tarefa de região: `runSafely` registra "Failed to run region task ..." e conta como falha. O servidor continua (`RegionizedTaskDispatcher.java` 975-983, 1488-1501). O resto do lote daquela tarefa (ex.: as tile entities seguintes da mesma região naquele tick) não roda, porque a exceção sai do laço. Inferência pela leitura do laço em `Level.java.patch` 424-445.
- Exceção na fila global: mesmo tratamento (linha 685).
- `region_task_timeout_seconds` (60): só vale para **esperas bloqueantes** (`awaitRegions`, `callOn...AndWait`). Estourou → `IllegalStateException` na thread que esperava (linhas 273-289, 997-1008; `RegionizedWorldGuard` 659-720). O README descreve como "Error message server shutdown timeout". No código não achei desligamento ligado a esse tempo.
- Vigia: o `ServerWatchdog` (patch) só olha a thread principal (`this.server.getRunningThread()`, `ServerWatchdog.java.patch` linhas 46-56). Não achei vigia para as threads de região. O Folia tem patch próprio de vigia (`0011-Add-watchdog-thread.patch`).

---

## 11. Como o projeto se descreve

Tudo desta seção é da documentação (README e página de releases), não conferido no código, salvo onde digo.

- **Promete:** "brings Folia's regionalized, multi-threaded server model to the NeoForge modding ecosystem"; "distribute world tick processing across multiple threads organized by region" e "significantly improve server performance when many players are spread across a world" (README linhas 3-7). Nenhuma medição de desempenho no repositório.
- **Config:** tabela de 7 opções no README (linhas 21-29). No código há bem mais chaves (threads de chunk/IO/parse, logins pendentes, limites de pacote, limites da global; `RegionizedServerSettings.java` linhas 25-48).
- **Diagnóstico:** `/tps` com contagem de regiões, threads e tempos (README linhas 36-42; código em `server/command/TPSCommand.java`).
- **Avisa:** só que mods ainda não foram portados ("Mod Compatibility", README linha 34) e, nas releases, "Mod Compatibility = 0" e "first alpha". Não há lista de limitações, de coisas que quebram nem de regras para autores de mod.
- **Repositório não é esqueleto:** tem código real e extenso (3.742 linhas no pacote novo, ~620 patches mexidos, 4 testes). Mas é alfa, de dois dias de histórico, sem issues nem documentação de desenho.

---

## 12. Tabela-resumo: Folia x NeoFolia

| Peça | Folia | NeoFolia | Veredito |
|---|---|---|---|
| Base | patches sobre Paper (Bukkit) | fork do NeoForge (FML) | outra coisa |
| Regionizador | `ThreadedRegionizer`, incremental, 4 estados | union-find refeito todo tick sobre grade fixa | reescrito (mesma distância de junção) |
| Agendador | por região, EDF, TPS por região | thread principal posta tarefas por raia num pool fixo | reescrito, modelo diferente |
| Global | tarefa no pool, sem tocar no mundo | a própria thread principal, toca no mundo | reescrito |
| Correio | `RegionizedTaskQueue` com ticket, FIFO por prioridade | `RegionQueue` FIFO sem ticket; repassa se o dono mudou | reescrito |
| Multi-região | não tem (tudo assíncrono) | `awaitRegions` com threads bloqueadas | novo |
| Dados por região | `RegionizedData` com merge/split | `RegionizedWorldData` pequeno em mapa estático | adaptado (nomes) |
| Checagem de dono | `TickThread.ensureTickThread` (~245 pontos) | `ensureRegionThreadFor` + corte de consultas | adaptado |
| Bordas (redstone, trilho...) | A7 | mesmos arquivos, outra função | portado com reescrita |
| Falha | derruba o servidor | registra e segue | oposto |
| Teleporte | `teleportAsync` obrigatório | portal via global; `/tp` via `awaitRegions` | diferente |
| Plugins / mods | `folia-supported` obrigatório no plugin | sem lista; capabilities, rede e attachments embrulhados | diferente |

---

## 13. O que isso sugere para um servidor 1.7.10 com mods

**Opinião minha, não fato dos projetos.**

1. **O modelo "thread principal + tarefas por região" do NeoFolia encaixa melhor no Crucible do que o modelo sem thread principal do Folia.** O laço de tick, o `ServerTickEvent` (FML) e o tick dos plugins podem ficar onde estão. Só os pedaços de mundo vão para fora. Mas o NeoFolia mostra o preço: sem barreira por tick, a thread principal e as regiões rodam ao mesmo tempo sem proteção. Para nós, eu poria uma **barreira por tick** (a principal espera as regiões antes de seguir para `WorldTickEvent.END` e para os plugins). Isso é mais lento, mas mantém o contrato que os mods 1.7.10 esperam.
2. **A geometria pode ser copiada como está:** grade de 16×16 chunks, margem 1, junção a até 3 quadrados, gatilho na criação e remoção de chunk. Os dois projetos concordam, e o NeoFolia mostra que um retrato refeito por tick é simples de escrever. Antes, é preciso resolver a corrida da seção 7.3: não trocar de dono uma região com tarefa rodando. O Folia faz isso; o NeoFolia não.
3. **Embrulhar o acesso entre mods é o ponto mais útil do NeoFolia, e em 1.7.10 não há onde embrulhar.** Lá existe uma porta única (capabilities). Em 1.7.10, os mods fazem `world.getTileEntity(x,y,z)` e chamam `IInventory`, `IFluidHandler`, `IEnergyHandler` (CoFH) direto. O equivalente seria interceptar `World.getTileEntity` vindo de uma thread de região para outra região. Isso é bem mais invasivo e precisa de lista por mod.
4. **"Exceção se uma região espera outra" é uma regra boa para copiar.** Ela evita travamento circular (deadlock). Mas com redes de energia e itens que cruzam chunks (Mekanism, AE2, BuildCraft), a regra vai disparar. O mais seguro para nós: deixar na mesma região tudo que uma rede conhecida alcança (juntar regiões por rede), em vez de deixar falhar.
5. **Não copiar o "registra e segue".** Num mundo com mods, uma tarefa que morre no meio deixa estado pela metade (item duplicado ou perdido). Segurança antes de desempenho: falha em região deve parar o servidor de forma limpa, como no Folia, ou pelo menos parar só aquela região e avisar.
6. **Copiar a ideia de interruptor por parte** (entidades, tile entities, chunks, ticks agendados), mas **com um interruptor geral** que desliga tudo, inclusive o roteamento de pacotes. O NeoFolia não tem; nós precisamos (regra do projeto: desligado = comportamento original).
7. **Vigia por região:** o NeoFolia não tem, o Folia tem. Nosso vigia de tick lento da Etapa 1 precisaria olhar também as threads de região.
8. **Um aviso de escala:** o NeoFolia mexeu em ~620 classes e ~48 arquivos do NeoForge, para um Minecraft que já tem muita coisa pensada para threads. Em 1.7.10 com Crucible (Forge + Bukkit + 292 mods), o trabalho tende a ser maior, e boa parte fica nos mods, não no servidor.

---

## 14. Não verificado

- **Comportamento em execução:** nada foi compilado nem rodado. Não sei se o NeoFolia liga, se aguenta jogadores, nem quanto ganha. Não há medição no repositório.
- **Versão exata do NeoForge de base** e o diff contra o NeoForge original: o histórico não traz os commits do NeoForge e não cruzei com o repositório dele.
- **Número de issues fechadas, estrelas e forks:** a página não mostra; a API do GitHub não estava liberada nesta sessão.
- **Diferença entre a tag 1.0.3 e a nota de versão** que diz que a junção entrou na 1.0.3 (no código, só aparece em `d1ff8c7`, tag 1.0.4).
- **Corrida na troca de dono** (seção 7.3) e **corrida entre thread principal e regiões** (seções 7.1 e 8): deduzidas da leitura; não testei nem achei teste que cubra.
- **Crescimento sem limite** de `REGION_WORLD_DATA` e `regionQueues`: deduzido da ausência de `remove`; não medido.
- **Mods que leem listas do `Level` direto** veriam a lista de tile entities vazia: dedução; não conferido.
- **`ServerExplosion.java.patch`, `ChunkMap.java.patch` (partes de geração e descarga), `ForcedChunkManager`, `RegionizedChunkExecutors`, `RegionizedLoginLimiter`:** só localizados, não lidos a fundo.
- **Semelhança dos patches 0005/0006 do Folia com `RegionizedLoginLimiter` e os contadores do `/tps`:** só por nome e assunto, sem comparar linha a linha.
- **O que é o "server part" que as releases pedem para instalar:** a página não diz e não está no repositório.
- **Pastas `rejects_*`:** não abertas.
- **Testes JUnit:** lidos só os nomes (e 4 casos de `RegionizedWorldGuardTest`); não executados.
