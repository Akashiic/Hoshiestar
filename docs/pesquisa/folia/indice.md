# Indice de localizacao: Folia e NeoFolia

So localizacao. Nada aqui foi lido a fundo nem testado. Quem for escrever o estudo deve abrir cada trecho e conferir.
Nenhum projeto foi compilado. Nenhum servidor foi ligado.

Pasta: /tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad/estudo/folia
Clones rasos (depth 1): `Folia/` e `NeoFolia/`. Por serem rasos, so existe um commit em cada; nao da para ver historico.

Termos (primeira vez):
- patch (.patch): arquivo que descreve mudancas em outro codigo (linhas com `+` entram, `-` saem). Ler o patch e ler o codigo novo.
- regiao (Folia): conjunto de chunks vizinhos que tica junto, numa thread so por vez.
- regionizador: a classe que decide quais chunks pertencem a qual regiao e junta/divide regioes.
- thread global: thread que cuida do que nao pertence a nenhuma regiao (relogio do servidor, tempo, etc.).
- thread dona: a thread que, naquele momento, e a unica autorizada a mexer num chunk, bloco ou entidade.

Numeracao: "linha N" em arquivo .patch e a linha dentro do proprio .patch (o codigo da classe comeca nessa linha, em linhas que comecam com `+`). Linha de cabecalho `+++ b/...` esta indicada quando util.

---

## 1. Folia

- Repositorio: https://github.com/papermc/folia (clone de `git clone --depth 1`)
- Commit: `eca40f918928e78c2c5f9c944cfb083d7d9851d4`
- Data do ultimo commit: 2026-11-18 21:24:54 -0700. Titulo: "Use thread-safe random sources"
- Minecraft alvo: `mcVersion=26.3` (Folia/gradle.properties linha 2). `apiVersion=26.3`. Referencia do Paper: `paperRef=5184e9c08009ddedc23f0dabfe144139bfb871ce` (linha 7). O Paper em si NAO esta no clone: o Folia e uma pilha de patches sobre o Paper e o Minecraft. Tudo que e codigo do Paper, nao patch do Folia, esta fora do clone (nao verificado aqui).
- Licenca: `PATCHES-LICENSE` e o texto da GPL v3 (Folia/PATCHES-LICENSE, linhas 1-2). Nao ha outro arquivo LICENSE na raiz. Nao conferi a licenca por pasta.
- Tamanho: 2,3 MB de arquivos de trabalho + 1,1 MB de .git (raso).
- Como o codigo e guardado: patches em pastas `folia-server/minecraft-patches/features/`, `folia-server/paper-patches/features/` e `folia-api/paper-patches/features/`. Nao ha submodulos (nao existe .gitmodules). Fica em `folia-server/src/main/resources/` so o logo. O Paper e baixado e os patches sao aplicados na hora da build (isso vem do `build.gradle.kts` e do `paperRef`; nao conferi o mecanismo de build).
- Documentacao do projeto: `README.md` (239 linhas), `PROJECT_DESCRIPTION.md` e `REGION_LOGIC.md` (cada um so tem uma frase dizendo que a pagina foi movida). Os textos reais estao em https://docs.papermc.io/folia/reference/overview e https://docs.papermc.io/folia/reference/region-logic (responderam HTTP 200; ainda nao lidos). Fonte secundaria: marcar como "da documentacao, nao conferido no codigo".

### 1.1 Patches, com tamanho em bytes

Pasta A = `Folia/folia-server/minecraft-patches/features/` (codigo do Minecraft)

| Patch | Bytes |
|---|---|
| 0001-Paper-map-PR-file-patch-changes.patch | 6233 |
| 0002-Rework-map-holder-tracking.patch | 30533 |
| 0003-Unload-unused-map-data.patch | 18533 |
| **0004-Region-Threading-Base.patch** | **1102013** (20470 linhas; 208 arquivos tocados; lista em `../folia_0004_files.txt`) |
| 0005-Max-pending-logins.patch | 2505 |
| 0006-Add-chunk-system-throughput-counters-to-tps.patch | 4830 |
| 0007-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch | 10110 |
| 0008-Block-reading-in-world-tile-entities-on-worldgen-thr.patch | 1272 |
| 0009-Sync-vehicle-position-to-player-position-on-player-d.patch | 2292 |
| 0010-Region-profiler.patch | 85092 |
| 0011-Add-watchdog-thread.patch | 7950 |
| 0012-Teleport-desynced-passengers-to-root-vehicle.patch | 6347 |
| 0013-Do-not-allow-out-of-region-teleport-accept.patch | 1773 |
| 0014-Use-Folia-logo.patch | 1140 |

Pasta B = `Folia/folia-server/paper-patches/features/` (codigo do Paper / CraftBukkit)

| Patch | Bytes |
|---|---|
| 0001-Paper-map-PR-source-changes.patch | 4499 |
| **0002-Region-Threading-Base.patch** | **255988** |
| 0003-Build-changes.patch | 6525 |
| 0004-Fix-tests-by-removing-them.patch | 1000 |
| 0005-Region-profiler.patch | 4248 |
| 0006-Add-watchdog-thread.patch | 1078 |
| 0007-Add-TPS-From-Region.patch | 3952 |
| files/.checkstyle/checkstyle.xml.patch | 640 |

Pasta C = `Folia/folia-api/paper-patches/features/` (API para plugins)

| Patch | Bytes |
|---|---|
| 0001-Force-disable-timings.patch | 1021 |
| 0002-Region-scheduler-API.patch | 2346 |
| 0003-Require-plugins-to-be-explicitly-marked-as-Folia-sup.patch | 3652 |
| 0004-Add-TPS-From-Region.patch | 4031 |

Tambem: `folia-server/build.gradle.kts.patch` (4028) e `folia-api/build.gradle.kts.patch` (2441).

Abreviacoes abaixo: **A4** = pasta A, arquivo 0004. **B2** = pasta B, arquivo 0002. **C3** = pasta C, arquivo 0003.
Caminhos completos:
- A4 = `Folia/folia-server/minecraft-patches/features/0004-Region-Threading-Base.patch`
- B2 = `Folia/folia-server/paper-patches/features/0002-Region-Threading-Base.patch`
- C3 = `Folia/folia-api/paper-patches/features/0003-Require-plugins-to-be-explicitly-marked-as-Folia-sup.patch`

### 1.2 Onde esta cada assunto (Folia)

Todos os pacotes `io.papermc.paper.threadedregions.*` sao arquivos novos criados dentro de A4.

| Assunto | Onde | Linha (classe comeca) |
|---|---|---|
| Regionizador `ThreadedRegionizer` | A4, arquivo `io/papermc/paper/threadedregions/ThreadedRegionizer.java` (cabecalho linha 3758) | classe linha 3788 |
| Regiao `ThreadedRegion` (classe interna) | A4 | 4433 |
| Secao `ThreadedRegionSection` | A4 | 4782 |
| Interface de callbacks de criar/juntar/dividir regiao `RegionCallbacks` | A4 | 5042 |
| Operacoes de juntar/dividir: `mergeInto` (4605), `mergeIntoLater` (4654), `onRegionCreate` (3917), `addChunk` (4035), `getRegionAtUnsynchronised` (3989), `getRegionAtSynchronised` (3999) | A4 | linhas indicadas |
| Agendador de tick das regioes `TickRegionScheduler` | A4, arquivo `.../TickRegionScheduler.java` (cabecalho 5170) | classe 5200 |
| Metodos dele: `setThreads` 5264, `getCurrentRegionizedWorldData` 5346, `getCurrentTickingTask` 5358, `scheduleRegion` 5370, `RegionScheduleHandle` 5444, `TickThreadRunner` (thread de trabalho) 5433, `markNonSchedulable` 5489, `tickRegion` abstrato 5501 | A4 | linhas indicadas |
| `TickRegions` (dados de cada regiao, callbacks) | A4, arquivo `.../TickRegions.java` (cabecalho 5738). Atencao: o patch troca uma classe vazia do Paper ("placeholder class for Folia", linha 5744) pela classe real | classe 5768 |
| Dados da regiao: `TickRegionData` | A4 | 5910 |
| Estatisticas por regiao `RegionStats` | A4 | 5881 |
| Dados por regiao `RegionizedData<T>` | A4, arquivo `.../RegionizedData.java` (cabecalho 1325) | classe 1423 |
| Dados do mundo por regiao `RegionizedWorldData` | A4, arquivo `.../RegionizedWorldData.java` (cabecalho 2875) | classe 2945 |
| Thread/regiao global `RegionizedServer` | A4, arquivo `.../RegionizedServer.java` (cabecalho 1567) | classe 1602 |
| Fila de tarefas global `globalTickQueue` | A4 | campo 1613; `addTask` 1653; `addTaskWithoutNotify` 1649 |
| Tick da thread global: `GlobalTickTickHandle` 1719, `tickRegion` da global 1752, `globalTick(long)` 1803, `globalTick(ServerLevel,long)` 1910 | A4 | linhas indicadas |
| Teste "sou a thread global?" `isGlobalTickThread()` | A4 | 1705 |
| Fila de tarefas entre regioes `RegionizedTaskQueue` | A4, arquivo `.../RegionizedTaskQueue.java` (cabecalho 2005) | classe 2029 |
| Dados da fila por mundo `WorldRegionTaskData` | A4 | 2096 |
| Dados da fila por regiao `RegionTaskQueueData` | A4 | 2254 |
| Fila de tarefas de chunk por regiao: `queueChunkTask` (2053), tarefas globais de chunk `pushGlobalChunkTask` / `executeGlobalChunkTask` / `drainGlobalChunkTasks` (usos nas linhas 823, 1780, 1924) | A4 | linhas indicadas |
| Agendador de chunks do Moonrise alterado: `ChunkTaskScheduler.java` | A4 (cabecalho 723; chamadas ao `RegionizedServer...taskQueue` nas linhas 809 e 823) | 723 |
| Carregador de chunks por jogador `RegionizedPlayerChunkLoader` (so uma alteracao pequena) | A4 | 260 |
| Parada por thread propria `RegionShutdownThread` | A4 | 1107 (cabecalho 1089) |
| Agendador de regiao para plugins `FoliaRegionScheduler` (implementacao) | A4 | 6752 (cabecalho 6722) |
| Agendador de entidade `FoliaEntityScheduler` e `EntityScheduler` (so ajustes) | B2 | 767 e 786 |
| Agendador global `FoliaGlobalRegionScheduler` (so ajuste) | B2 | 799 |
| Interfaces de API `RegionScheduler`, `AsyncScheduler`, `EntityScheduler`, `GlobalRegionScheduler` | NAO estao no Folia. So aparecem em links de javadoc no patch C (`0002-Region-scheduler-API.patch`, linhas 31-32 do trecho do BukkitScheduler). As interfaces vem do Paper (fora do clone): nao verificado | - |
| Marca `BukkitScheduler` como `@Deprecated` e troca `cancelTasks` | C, `0002-Region-scheduler-API.patch` | trecho `SimplePluginManager` e `BukkitScheduler` |
| Checagem de thread dona `TickThread` | B2, arquivo `ca/spottedleaf/moonrise/common/util/TickThread.java` (so o diff; a classe base e do Paper/Moonrise e nao esta no clone) | cabecalho linha 7; `isShutdownThread` 54; `isTickThreadFor(Level,BlockPos)` 59; `isTickThreadFor(Level,BlockPos,int)` 64; `isTickThreadFor(Level,ChunkPos)` 73; `isTickThreadFor(Level,Vec3)` 78 |
| Usos de `isTickThreadFor` e `ensureTickThread` espalhados no codigo do Minecraft | A4 (ex.: linhas 41, 563, 863, 978, 1061, 3468, 5966, 7947, 8401) | - |
| Teleporte assincrono: `Entity.teleportAsync(...)` | A4, arquivo `net/minecraft/world/entity/Entity.java` (cabecalho 14652): `teleportAsync` 15188 e 15198, `placeInAsync` 15037, `teleportSyncSameRegion` 15124, `EntityTreeNode` 14856 | linhas indicadas |
| Utilitario de teleporte `TeleportUtils` | A4 | 3681 (cabecalho 3669) |
| Comando `/tp` usando teleporte assincrono | A4, `net/minecraft/server/commands/TeleportCommand.java` | cabecalho 10066; uso 10098 |
| Excecao "Must use teleportAsync while in region threading" (teleporte sincrono proibido) | A4 (linhas 12936, 14136, 15638); B2 (2205, 3142) | - |
| Teleporte via API (`CraftEntity.teleportAsync`) | B2 | uso 2272 |
| Plugins: exigencia de `folia-supported: true` | C3 (chave `FOLIA_SUPPORTED_KEY` linha 45; `isFoliaSupported` linhas 32 e 52; leitura do yml linhas 64-65). No servidor: B2 linhas 709-720 (`PaperPluginMeta`), 730-760 (fabricas de plugin Paper e Spigot que lancam `RuntimeException` se nao marcado), 4127 (`CraftMagicNumbers`, lanca `InvalidPluginException`) | linhas indicadas |
| Profiler por regiao | A (0010, 85092 bytes) e B (0005, 4248 bytes) | arquivos inteiros |
| Vigia (watchdog) | A (0011) e B (0006) | arquivos inteiros |
| TPS por regiao | C (0004 TPS-From-Region, 4031 bytes), B (0007, 3952 bytes), A (0006, contadores do sistema de chunk) | arquivos inteiros |
| Comando de saude do servidor `CommandServerHealth` | A4 | cabecalho 6233 |
| Limite de logins pendentes | A (0005, 2505 bytes) | arquivo inteiro |
| Bloqueio de atualizacao de bloco em chunk nao dono | A (0007, 10110 bytes) | arquivo inteiro |
| Aleatorio seguro entre threads: `ThreadLocalRandomSource`, `SimpleThreadLocalRandomSource` | A4 | cabecalhos 7242 e 7156 |
| Documentacao do modelo | `Folia/README.md` linhas 11-30 (visao geral); resto e link para docs.papermc.io | - |

### 1.3 O que procurei no Folia e NAO achei

- Nenhum arquivo `.java` normal para o regionizador, agendador ou fila: tudo esta dentro dos patches (esperado).
- `TickThread` completo (a classe inteira): so o diff em B2. A classe original e do Paper/Moonrise, fora do clone.
- Interfaces `RegionScheduler`, `AsyncScheduler`, `GlobalRegionScheduler` (API): nao estao no clone.
- Interface Bukkit `Entity#teleportAsync` (API publica): nao achei a declaracao no clone; a chamada na implementacao existe (B2 linha 2272).
- Pasta `docs/` no repositorio: nao existe. O texto longo sobre regioes esta so no site.
- Pasta de testes do Folia: o patch B 0004 se chama "Fix tests by removing them"; nao procurei mais.
- Submodulos: nao ha.
- Nao procurei `Thread`/mensagens entre regioes alem de `RegionizedTaskQueue` e `globalTickQueue` (fila de recados). Pode existir outro caminho (ex.: tarefas por entidade em `Entity.java`). Isso fica para o estudo.

---

## 2. NeoFolia

- Repositorio: https://github.com/NeoFolia/NeoFolia (clone `--depth 1`; o repositorio existe e e publico)
- Commit: `fa76006de0c01f5cd0c3290f0994898c39e8441f`
- Data do ultimo commit: 2026-06-04 18:04:56 +0300. Titulo: "Update README.md"
- Minecraft alvo: `minecraft_version=26.1.2`, `java_version=25` (NeoFolia/gradle.properties). Observacao: e diferente do Folia atual (26.3); versoes distantes entre os dois.
- Licenca: `LICENSE.txt` (LGPL 2.1) e `README-LICENSE.md` ("NeoForge ... licensed under LGPL v2.1", SPDX `LGPL-2.1-only`). Cabecalhos dos arquivos novos: "Copyright (c) Forge Development LLC and contributors / LGPL-2.1-only". Isso e a licenca do NeoForge herdada; nao ha licenca propria do NeoFolia (nao verificado se existe outra).
- Tamanho: 34 MB de arquivos de trabalho (patches 9,8 MB, src 14 MB) + 5,3 MB de .git raso. 4858 arquivos versionados.
- Como o codigo e guardado: NAO e uma pilha de patches do Folia. E um fork do NeoForge no formato do NeoForge: `patches/` (1511 arquivos `.patch`, um por classe do Minecraft, em `patches/net/minecraft/...` e `patches/com/mojang/...`, formato `@@ -987,11 +_,20 @@`) mais codigo proprio e do NeoForge em `src/main/java`. Pastas `projects/base` e `projects/neoforge` so tem `build.gradle` (48 KB). `coremods/` e do proprio NeoForge (conferi que nao tem nada de regiao). Pastas `rejects_*` guardam patches rejeitados em portes (nao abertas). Sem submodulos (nao existe .gitmodules).
- Detalhe importante: o codigo de regiao do NeoFolia NAO usa os nomes do Folia. `ThreadedRegionizer`, `TickRegionScheduler`, `TickRegions`, `RegionizedData` (classe), `RegionizedTaskQueue`, `TickThread`, `FoliaRegionScheduler`, `teleportAsync`, `folia-supported`: 0 arquivos com esses nomes (conferido com `git grep`, fora a mencao em README). So `RegionizedWorldData` existe com o mesmo nome, mas com conteudo bem menor (88 linhas). O README diz "fork porting Folia's ... model", mas o codigo e outro: e um desenho proprio. Quem escrever o estudo deve partir disso.
- Documentacao do projeto: `README.md` (curto; seguem opcoes de `config/neofolia.toml`, comando `/tps`, secao "Mod Compatibility" na linha 32). A pasta `docs/` e do NeoForge (CONTRIBUTING, PORTING etc.); busca por "folia" ou "region" em `docs/` nao achou nada.

### 2.1 Codigo proprio de regioes (pacote `net.neoforged.neoforge.server.region`)

Pasta: `NeoFolia/src/main/java/net/neoforged/neoforge/server/region/` (9 arquivos, 3742 linhas)

| Arquivo | Linhas | O que parece ser (so pelo cabecalho/nomes; nao lido a fundo) |
|---|---|---|
| RegionKey.java | 28 | Registro (dimensao, regionX, regionZ). Regiao = chunk >> shift. Linha 11-ish; construtores `fromChunk`/`fromBlock` |
| RegionizedTickContext.java | 54 | `ThreadLocal` que diz qual regiao a thread esta processando (`currentRegion`, `isRegionThread`, `runInRegion`) |
| RegionizedWorldData.java | 88 | Estado minimo por regiao (spawners, cerco de aldeia, lista de tickers de block entity, contadores) |
| RegionizedWorldGuard.java | 1230 | Funcoes estaticas: dono da regiao (`regionFor` 58), `isRegionThreadFor` 226-243, `isTickThread` 251, `ensureRegionThreadFor` 454-470, agendar na regiao dona (`scheduleEntityIfRegionized` 338, `scheduleAtRegionIfRegionized` 399, `scheduleGlobalIfRegionized` 440), executar na regiao do jogador (`executeOnPlayerRegion` 504, `...AndWait` 531-617), envio de pacote (`sendToPlayer` 1174), uniao de regioes por jogadores (`PlayerRegionGroups` 871, `union` 1099, `canonicalOwner` 1122), `DEFAULT_REGION_CHUNK_SHIFT = 4` (linha 45) |
| RegionizedTaskDispatcher.java | 1571 | Despachante: pool de threads, uma fila por regiao (`RegionQueue` 1451, `RegionThreadFactory` 1561, `workerLoop` 961), `execute` 97, `submit` 213, `executeAll` 130, `awaitRegions` 292, `awaitRegionsPaused` 391, fila global (`globalTasks` 57, `executeGlobal` 588, `drainGlobalTasks` 672), `beginServerTick` 770, `start(int)` 82, `defaultThreadCount` 78, estatisticas |
| RegionizedChunkExecutors.java | 152 | Executores separados para chunk IO, parse e geracao (linhas 22-24) |
| RegionizedLoginLimiter.java | 85 | Limite de logins (linha 37 le `maxPendingLogins`) |
| RegionizedServerSettings.java | 500 | Leitura de `config/neofolia.toml` (chave `region_threads` linha 25; modelo do arquivo linhas ~311 e ~460) |
| RollingRateCounter.java | 34 | contador de taxa |

### 2.2 Onde esta cada assunto (NeoFolia)

| Assunto pedido | Onde no NeoFolia |
|---|---|
| Regionizador (juntar/dividir regioes) | NAO existe `ThreadedRegionizer`. O mais proximo e: grade fixa `RegionKey` (shift 4 = 16x16 chunks, `RegionizedWorldGuard.java` linha 45) e uniao de regioes vizinhas com jogadores em grupos ("dono"), `RegionizedWorldGuard.java` linhas 871-1135 (`PlayerRegionGroups`, `union`, `find`, `canonicalOwner`) e `ownerForFixedRegion` linha 68. Nao conferi como a regra de uniao funciona |
| Agendador de tick das regioes | `RegionizedTaskDispatcher.java` inteiro (acima). Chamado em `patches/net/minecraft/server/MinecraftServer.java.patch` linhas 24-25 (campo), 124 (`start`), 184 (`beginServerTick`), 264 (`drainGlobalTasks`), 335 (acessor) e em `patches/net/minecraft/server/level/ServerLevel.java.patch` linha 358 (`executeAll` por regiao para ticks agendados) |
| Dados por regiao | `RegionizedWorldData.java` (88 linhas). Mapas em `RegionizedWorldGuard.java` linhas 49-53 (`REGION_WORLD_DATA`, `GLOBAL_WORLD_DATA`, buckets de entidades) |
| Regiao/thread global | Nao ha thread global propria. A "global" e a thread principal do servidor: `MinecraftServer.java.patch` linha 264 chama `regionizedTaskDispatcher.drainGlobalTasks()` dentro do tick (dentro do trecho que tambem dispara `fireServerTickPre`, linha 184, e `fireServerTickPost`, linha 217). Fila global: `RegionizedTaskDispatcher.java` linhas 57, 588-672. Limites por tick vem de `RegionizedServerSettings.globalTaskMaxTasksPerTick` e `globalTaskMaxTimeMs` (usados em `RegionizedTaskDispatcher.java` linhas 673-674). Se a global roda mesmo na thread principal: inferencia, nao verificado |
| Filas de tarefas entre regioes | `RegionizedTaskDispatcher.java`: `execute(RegionKey,Runnable)` 97, `tryExecuteCoalesced` 148-152, `tryExecuteNextTick` 178, `submit` 213-221, `RegionQueue` 1451. Do lado de quem usa: `RegionizedWorldGuard.java` linhas 314-452 (`scheduleEntityIfRegionized*`, `scheduleAtRegionIfRegionized*`, `scheduleGlobalIfRegionized*`) |
| Agendadores da API para plugins | Nao ha. NeoFolia nao e Bukkit (README linha 9). Ponto de entrada para mods: `RegionizedWorldGuard` (publico, estatico). `RegionizedTaskDispatcher` e acessado por `MinecraftServer#regionizedTaskDispatcher()` (patch linha 335). Nao achei nenhuma interface de agendador "para mods" separada: nao encontrada |
| Checagem de thread dona | `RegionizedWorldGuard.isRegionThreadFor(...)` 226-243, `ensureRegionThreadFor(...)` 454-482, `isTickThread(MinecraftServer)` 251, `RegionizedTickContext.isRegionThread()` / `currentRegion()` |
| Teleporte assincrono | Nao ha `teleportAsync`. Teleporte de portal vai para a fila global: `patches/net/minecraft/world/entity/Entity.java.patch` linhas 894 e 910-917 (`completePortalTeleport`). Comando `/tp`: `patches/net/minecraft/server/commands/TeleportCommand.java.patch` linhas 152-186, 310-316 (`awaitRegions` com a regiao da vitima e a do destino). Auxiliar de comando: `patches/net/minecraft/server/commands/RegionizedCommandHelper.java.patch`. Efeito de teleporte aleatorio: `patches/net/minecraft/world/item/consume_effects/TeleportRandomlyConsumeEffect.java.patch`. Tag de dados: `src/generated/resources/data/c/tags/entity_type/teleporting_not_supported.json` |
| Tratamento de plugins (`folia-supported`) | Nao existe (nao e Bukkit). Para mods: nenhuma lista de mods "suportados" encontrada. Busca por `supported`/`folia` em `src/main/java` fora do pacote region: nao achei |
| Documentacao | `README.md` (opcoes de config, `/tps`, "Mod Compatibility" linha 32). Nada em `docs/` |

### 2.3 Itens especificos de mods (NeoFolia)

| Assunto | Onde |
|---|---|
| Barramento de eventos (event bus) | Nao achei alteracao no barramento. O barramento fica em biblioteca externa ao repositorio; no repositorio so aparecem usos `NeoForge.EVENT_BUS`. Chamadas de evento dentro de regioes: `EventHooks.java` linha 904 (`PermissionsChangedEvent` roda via `callOnPlayerRegionAndWait`); `NeoForgeEventHandler.java` linhas 65, 123, 150 |
| Eventos de tick | `EventHooks.fireServerTickPre/Post` chamados em `MinecraftServer.java.patch` linhas 184 e 217; `fireLevelTickPre/Post` em linhas 278 e 286 do mesmo patch (`EventHooks.java` linha 997 define `fireLevelTickPre`); `fireEntityTickPre` (definido em `EventHooks.java` linha 960) chamado em `ServerLevel.java.patch` linha 604 e `Entity.java.patch` linha 628. Em qual thread cada um roda: nao verificado |
| Capabilities (capacidades) | `capabilities/BlockCapability.java` linhas 24, 222-228 (`wrapRegionizedBlockCapability`); `capabilities/EntityCapability.java` linhas 19, 108-114 (`wrapRegionizedEntityCapability`); `capabilities/CapabilityHooks.java` linhas 145 e 193 (`RegionizedPlayerResourceHandler.of`). Embrulhos novos: `transfer/RegionizedBlockResourceHandler.java` (classe linha 19), `RegionizedEntityResourceHandler.java`, `RegionizedPlayerResourceHandler.java`, `transfer/energy/RegionizedBlockEnergyHandler.java`, `RegionizedEntityEnergyHandler.java`, `transfer/access/RegionizedPlayerItemAccess.java`. Uso: `transfer/ResourceHandlerUtil.java` (linhas 159-224), `transfer/energy/EnergyHandlerUtil.java`, `transfer/item/VanillaInventoryCodeHooks.java`, `items/ItemHandlerHelper.java`, `items/ItemResourceHandlerAdapter.java`, `fluids/capability/FluidResourceHandlerAdapter.java`, `energy/EnergyHandlerAdapter.java` |
| Attachments (dados anexados) | `attachment/AttachmentSync.java` linhas 118, 123, 149 (envio e agendamento na regiao). Nao procurei o armazenamento em si |
| Rede de mods (payloads/pacotes) | `network/PacketDistributor.java` linhas 40, 81; `network/handling/ServerPayloadContext.java` linha 66 (`executeOnPlayerRegion`); `network/ConfigSync.java` linhas 106 e 133; `common/CommonHooks.java` linhas 259 e 1826 (`sendToPlayer`, `sendPayloadToPlayer`); `RegionizedWorldGuard.java` linhas 1174-1209 (envio de pacote/payload) |
| Chunks forcados / tickets de mods | `common/world/chunk/ForcedChunkManager.java` (2 usos); teste `tests/src/junit/.../TicketStorageRegionSafetyTest.java` |
| Comandos NeoForge | `server/command/TPSCommand.java` (usa `RegionPerformanceSnapshot`/`RegionStatsSnapshot`), `TrackCommand`, `GenerateCommand`, `EntityCommand`, `TimeSpeedCommand` |
| Item/bloco quebrado por mod | `common/CommonHooks.java` linhas 456, 580-631 (drops e entidades criadas via `scheduleEntityIfRegionized`/`scheduleAtRegionIfRegionized`), `common/IShearable.java` |
| Testes de regiao | `tests/src/junit/java/net/neoforged/neoforge/unittest/server/region/` (4 arquivos: `ChunkTaskDispatcherParallelismTest`, `RegionizedTaskDispatcherTest`, `RegionizedWorldGuardTest`, `TicketStorageRegionSafetyTest`). Nao executei |

### 2.4 Patches do NeoFolia mais carregados de regiao (contagem de ocorrencias de `Regionized`/`RegionKey`)

`patches/net/minecraft/server/level/ChunkMap.java.patch` 165; `.../network/ServerGamePacketListenerImpl.java.patch` 146; `.../level/ServerLevel.java.patch` 127; `.../level/ServerPlayer.java.patch` 66; `.../players/PlayerList.java.patch` 61; `.../level/ServerChunkCache.java.patch` 59; `.../world/level/Level.java.patch` 53; `.../server/MinecraftServer.java.patch` 38; `.../world/entity/LivingEntity.java.patch` 36; `.../world/entity/Entity.java.patch` 33; `.../network/PlayerChunkSender.java.patch` 27; `.../world/level/ServerExplosion.java.patch` 26; `.../server/dedicated/DedicatedServer.java.patch` 25; `.../world/ticks/LevelTicks.java.patch` 19. Ao todo 622 arquivos de patch mencionam "region".

### 2.5 O que procurei no NeoFolia e NAO achei

- `ThreadedRegionizer`, `TickRegionScheduler`, `TickRegions`, `RegionizedData`, `RegionizedTaskQueue`, `TickThread`: nenhum arquivo.
- Agendadores no estilo `RegionScheduler`/`GlobalRegionScheduler`/`EntityScheduler`: nenhum.
- `teleportAsync`: nenhum.
- `folia-supported` ou lista de mods suportados: nenhum.
- Mudancas no barramento de eventos em si: nenhuma neste repositorio.
- Documentacao de desenho (alem do README): nenhuma.
- Historico de commits (clone raso) e issues/PRs: nao consultados.
- Um "regionizador" que junte e divida regioes de forma dinamica como no Folia: nao achei por nome; a regra de uniao existe (union-find sobre grupos de jogadores), nao conferida.

---

## 3. Observacoes para quem for conferir

1. As duas bases estao em versoes muito diferentes do Minecraft (Folia 26.3 contra NeoFolia 26.1.2). Nem uma e 1.7.10. Qualquer comparacao com Forge 1.7.10 so serve como ideia de desenho.
2. O NeoFolia diz no README que "porta" o modelo do Folia, mas os nomes e a estrutura no codigo sao diferentes. Antes de afirmar "o NeoFolia faz como o Folia", conferir classe por classe.
3. Em Folia, A4 (1,1 MB) concentra quase tudo: ler primeiro as classes de `io/papermc/paper/threadedregions/`.
4. `folia_0004_files.txt` (um nivel acima desta pasta, ao lado do indice) lista os 208 arquivos tocados por A4 com a linha do cabecalho.
