# Folia: thread global, correio entre regiões e checagens de dono

- Projeto lido: Folia, clone raso em `.../estudo/folia/Folia`
- **Commit lido: `eca40f918928e78c2c5f9c944cfb083d7d9851d4`** (2026-11-18 21:24:54 -0700, "Use thread-safe random sources")
- Minecraft alvo do Folia: 26.3 (`gradle.properties`). Paper de base: `paperRef=5184e9c0...` (o Paper **não** está no clone).
- Método: só leitura dos arquivos `.patch`. Nada compilado, nenhum servidor ligado, nada commitado.
- Fonte secundária: páginas https://docs.papermc.io/folia/reference/overview (lida em 2026-11-22). O que vem de lá está marcado "da documentação, não conferido no código".

Abreviações de arquivo (todas relativas a `Folia/`):

- **A4** = `folia-server/minecraft-patches/features/0004-Region-Threading-Base.patch` (código do Minecraft alterado; 20.470 linhas)
- **B2** = `folia-server/paper-patches/features/0002-Region-Threading-Base.patch` (código do Paper/CraftBukkit alterado)
- **A5, A7, A12, A13** = `folia-server/minecraft-patches/features/0005-...`, `0007-...`, `0012-...`, `0013-...`
- **C2, C3** = `folia-api/paper-patches/features/0002-Region-scheduler-API.patch`, `0003-Require-plugins-...patch`

"Linha N" = linha dentro do arquivo `.patch` (o código novo está nas linhas que começam com `+`).

---

## 1. Resumo (10 linhas)

1. Não existe thread principal. Cada região (grupo de chunks vizinhos) tica sozinha, num pool de threads; a "região global" é só mais uma tarefa agendada nesse mesmo pool, a 20 TPS, sem chunks próprios (A4 1602-1806).
2. A global cuida de: fila de tarefas global, pacotes de login/configuração, console, relógio/tempo do mundo, clima, borda do mundo, sono, raids (parte global), salvamento de mapas, status do servidor e `PlayerList.tick()` (A4 1803-1949).
3. A global **não** mexe em chunk, bloco, entidade nem jogador em jogo: `isTickThreadFor(world, chunk)` devolve falso nela (B2 diff de `TickThread`, linhas 83-90).
4. Correio entre regiões = `RegionizedTaskQueue`: uma fila por região, indexada por posição de chunk. Quem posta põe um "ticket" que obriga a região dona a existir; a tarefa roda no próximo tick dela (A4 2029-2860).
5. Ordem: FIFO (primeiro a entrar, primeiro a sair) dentro da mesma prioridade, mantida quando regiões se juntam ou se dividem. Não há garantia de ordem entre filas diferentes (global x região x entidade).
6. Postar uma tarefa **não carrega o chunk**. Quem precisa do chunk carrega de forma assíncrona antes (ex.: teleporte com `TELEPORT_FLAG_LOAD_CHUNK`, login com `scheduleTickingState`).
7. Teleporte entre regiões/dimensões é sempre assíncrono: tira a entidade do mundo, copia, e posta a entrada na fila da região destino. `Entity.teleport` síncrono lança `UnsupportedOperationException("Must use teleportAsync...")` (A4 15188-15300, 15638).
8. Bordas: o desenho conta com uma margem de chunks vazios em volta de cada região; o que passa da margem é **cortado em silêncio** (redstone, vizinhos, trilhos, tripwire, eventos de jogo) ou vira **parede invisível** (colisão de entidade) (A7; A4 863, 1061).
9. Checagens de dono: `TickThread.ensureTickThread(...)` em ~245 pontos (216 em B2, 29 em A4), mais `IllegalArgumentException` explícitas nas listas da região. Exceção dentro de um tick derruba o servidor de forma organizada (`regionFailed`, A4 5416).
10. Muita coisa foi desligada: agendador Bukkit (lança exceção), placares/times, funções de datapack, ~20 comandos vanilla, `/reload`, chat síncrono, conversas, `MinecraftServer.execute/submit`, plugins sem `folia-supported: true`.

---

## 2. Termos usados (primeira vez)

- **Região**: conjunto de chunks vizinhos que tica junto, numa thread só por vez.
- **Seção de região**: quadrado fixo de chunks (padrão 16x16 chunks, `gridExponent = 4`, B2 linha 437). A região é formada por seções inteiras.
- **Regionizador** (`ThreadedRegionizer`): classe que decide qual seção pertence a qual região e junta/divide regiões.
- **Tick**: uma "volta" da simulação (alvo: 20 por segundo, 50 ms cada).
- **Thread dona**: a thread que está ticando a região que contém aquele chunk/entidade naquele momento. Só ela pode mexer ali.
- **Ticket**: pedido registrado no sistema de chunks para manter um chunk (ou só o "suporte" dele) vivo.
- **Fila FIFO**: fila em que o primeiro a entrar é o primeiro a sair.
- **Pool de threads**: grupo fixo de threads que pegam tarefas de uma lista e executam.
- **EDF** (earliest deadline first): regra de agendamento que roda primeiro quem tem o prazo mais próximo.

---

## 3. Respostas, uma por pergunta

### 3.1 O que roda na região/thread global e o que não roda

**Não há uma thread global dedicada.** A global é um objeto `GlobalTickTickHandle` agendado no mesmo agendador das regiões:

- A4 1635-1643, `RegionizedServer.init()`: `TickRegions.getScheduler().scheduleRegion(this.tickHandle);`
- A4 1719-1724: `GlobalTickTickHandle extends TickRegionScheduler.RegionScheduleHandle`, construído com `super(null, ...)` — região nula, ou seja, não possui chunks.
- A4 1705-1707: "sou a global?" = `INSTANCE.tickHandle == TickRegionScheduler.getCurrentTickingTask()`. É uma pergunta sobre a *tarefa*, não sobre a *thread*: qualquer thread do pool pode ser a global num tick e uma região no outro.
- O pool tem threads `TickThreadRunner` chamadas "Folia Region Scheduler Thread #N" (A4 5224-5238). Tamanho padrão: `threads = -1` → metade dos núcleos dividida por 4, ou 1 thread se a máquina for pequena (A4 5786-5800; B2 436).

**O que roda na global**, em ordem (A4 `RegionizedServer`):

1. Antes do tick: esvazia a fila global e os pacotes de quem está em login/configuração (`drainTasks`, A4 1752-1766):

   ```java
   protected void tickRegion(final long tickCount, ...) {
       this.drainTasks();
       this.server.globalTick(tickCount);
   }
   private void drainTasks() {
       while (this.runOneTask() || this.runOnePacketTask());
   }
   ```
2. `globalTick(long)` (A4 1803-1838): callbacks de clique de chat expirados; agendador global de plugins (`FoliaGlobalRegionScheduler.tick()`); relógios (`tickClocks`); **comandos do console** (`handleConsoleInputs`); amostra de jogadores/status do servidor; tick global de cada mundo; salvamento de mapas (`autoSaveMaps`); conexões que ainda são da global; `PlayerList.tick()`.
3. `globalTick(ServerLevel, long)` por mundo (A4 1910-1949): atributos de ambiente; tarefas globais de chunk (`drainGlobalChunkTasks`); **borda do mundo**; **ciclo do clima**; **pular a noite (sono)**; `getRaids().globalTick()`; **brilho do céu**; **tempo de jogo** (`setGameTime`); `updateTickData`; `processTicketUpdates` do sistema de chunks.
4. Entre ticks, se sobra tempo: mais tarefas globais, pacotes e tarefas globais de chunk (`runRegionTasks`, A4 1786-1793).

**O que NÃO roda na global** (roda na região dona):

- Mundo em si: chunks, blocos, entidades, block entities (tile entities), ticks agendados de bloco/fluido, eventos de bloco, redstone, spawn de mobs, rastreamento de entidades. Tudo isso fica em `RegionizedWorldData`, um objeto por região (A4 2945-3660; campos em 3215-3315).
- Jogador em jogo: os pacotes de jogo e a conexão (`RegionizedWorldData.tickConnections`, A4 3394-3426).
- Agendadores de plugin por posição e por entidade (A4 `MinecraftServer.tickServer`, linhas 8396-8412).
- Salvamento incremental de chunks e de jogadores: dentro de `tickServer` de cada região (A4 8430-8450); `PlayerList.saveAll` pula jogador que não é da região (A4 14251-14253).
- **Prova de que a global não toca o mundo**: em `TickThread.isTickThreadFor(world, chunkX, chunkZ)` a região atual é nula na global, e aí a resposta é `isShutdownThread()`, ou seja, falso (B2, diff de `TickThread`, trecho de `isTickThreadFor(final Level world, final int chunkX, final int chunkZ)`):

  ```java
  final ThreadedRegion<...> region = TickRegionScheduler.getCurrentRegion();
  if (region == null) {
      return isShutdownThread();
  }
  return ((ServerLevel)world).regioniser.getRegionAtUnsynchronised(chunkX, chunkZ) == region;
  ```
- Para entidades, a global só "possui" um `ServerPlayer` que ainda está na fase de configuração/login (B2, `isTickThreadFor(Entity)`, ramo `RegionizedServer.isGlobalTickThread()`).

**Tempo**: cada região tem seu próprio contador de tick e seu próprio "tempo de redstone" (`redstoneTime`, A4 3240-3250). O tempo de jogo global e o horário do dia são da global; a região copia uma fotografia no começo do tick (`updateTickData`, A4 3350-3357). Da documentação, não conferido em detalhe no código: "Global Game Time and Daylight Time are maintained by the global region... copied at the start of each region tick".

**Salvamento**: `level.dat` e dados globais só aparecem salvos no `RegionShutdownThread` (A4 1145-1147, 1301, 1309). No tick de região a condição é `if (region == null && fullSave)`, que nunca é verdadeira numa região (A4 8438-8446). Não achei outro salvamento periódico de `level.dat` (ver "não verificado").

### 3.2 Correio: como uma região pede algo a outra

Há quatro caminhos, todos verificados no código:

| Caminho | Para quê | Onde roda | Evidência |
|---|---|---|---|
| Fila global (`RegionizedServer.addTask`) | mexer no que é da global (tempo, clima, console, regras) | na global, antes do tick ou entre ticks | A4 1649-1656, 1752-1793 |
| `RegionizedTaskQueue` (fila por posição) | ler/escrever bloco, chunk, entidade fora da região atual | na região dona da posição | A4 2029-2860 |
| Agendador de entidade (`EntityScheduler` / `taskScheduler`) | rodar código onde a entidade estiver, mesmo que ela mude de região | na região dona da entidade | A4 8404-8411; uso em `TeleportUtils` A4 3681-3750 |
| Agendador de região para plugins (`FoliaRegionScheduler`) | API de plugin por posição, com atraso em ticks | na região dona | A4 6752-7000 |

**`RegionizedTaskQueue` em detalhe** (A4 2029-2860):

- Duas filas por região: `tickTaskQueue` e `chunkQueue` (A4 2254-2260, `RegionTaskQueueData`). Mais uma fila global de tarefas de chunk por mundo (`globalChunkTask`, A4 2098-2125), que qualquer região do mundo ou a global pode esvaziar.
- `queueTickTaskQueue(world, chunkX, chunkZ, run)` cria a tarefa e chama `queue()` (A4 2064-2076).
- `queueOrExecuteTickTask`: se a thread atual já é a dona, roda na hora; senão, enfileira (A4 2078-2090).
- `queue()` (A4 2607-2660):
  1. Incrementa um contador por seção e **adiciona um ticket** `TASK_QUEUE_TICKET` no nível `MAX_TICKET_LEVEL` no chunk do canto da seção (A4 2133-2146, 2189-2212). Comentário do código em teleporte (A4 15040-15041): o ticket serve para "ensure the region is always ticking".
  2. Procura a fila da região dona; se não acha sem trava, tenta de novo com trava (`getRegionAtSynchronised`).
  3. Se a fila foi "destruída" por uma junção/divisão em andamento, tenta de novo (`tryPush` devolve `FALSE`).
  4. Marca que a região tem tarefas (`setHasTasks`) para o agendador acordá-la.
- Quando a tarefa roda (ou é cancelada), o contador cai; no zero, o ticket sai (A4 2164-2187, `decrementReference`; chamado no `finally` de `executeTask`, A4 2452-2480).

**O que acontece se a região alvo não existe**: o ticket cria o "suporte" do chunk (chunk holder), e criar um chunk holder chama `regioniser.addChunk` (A4 64, em `BaseChunkSystemHooks.onChunkHolderCreate`), o que cria ou junta uma região. Ou seja: **a região passa a existir por causa da tarefa**. A documentação diz o mesmo: "executed on the next tick of a region that owns a specific location, or creating such region if it does not exist" (da documentação). O elo "ticket em `MAX_TICKET_LEVEL` cria chunk holder" é do sistema de chunks Moonrise, que não está no clone (ver "não verificado"). Se mesmo com trava a fila não aparece e a tarefa não foi cancelada, o código lança `IllegalStateException("Expected null ref count when queue does not exist")` (A4 2632-2635).

**O que acontece se o chunk não está carregado**: o ticket é de nível `MAX_TICKET_LEVEL`, que no Moonrise é o nível mais fraco (não verifiquei que ele não carrega o chunk; ver "não verificado"). No código, quem precisa do chunk pronto faz o carregamento assíncrono antes de postar:

- teleporte: `loadChunksForMoveAsync(...)` e só depois `scheduleEntityJoin` (A4 15091-15107);
- login: `scheduleTickingState(..., ENTITY_TICKING, ...)` e só no callback posta `spawnPlayer` na fila (A4, `ServerConfigurationPacketListenerImpl`, linhas 13238-13262; chamada em 13247);
- portal: `moonrise$loadChunksAsync(...)` em várias etapas (A4 15342-15500).
- Carregar chunk de forma síncrona fora da região dona lança erro: `"Cannot asynchronously load chunks"` (A4 11026, `ServerChunkCache.syncLoad`), `"May not sync load chunks asynchronously"` (B2 1033, `CraftWorld.loadChunk`).

**Garantias de ordem** (A4 `PrioritisedQueue`, 2316-2480):

- Uma `ArrayDeque` por prioridade; entra com `addLast`, sai com `pollFirst` → FIFO dentro da mesma prioridade.
- Prioridade mais alta sai primeiro (laço `for i = 0..IDLE`, A4 2452-2470). Mudar a prioridade de uma tarefa reenfileira (`updatePriorityInQueue`, A4 2694-2735).
- Junção de regiões mantém a ordem: as tarefas da região que some vão para a **frente** da fila da outra, usando iterador reverso (A4 2355-2382, comentário "we need to ensure order is maintained"). Divisão também preserva a ordem por região destino (A4 2384-2432).
- Não achei nenhuma garantia de ordem **entre** filas diferentes (global x `RegionizedTaskQueue` x agendador de entidade).

**Garantias de atraso**:

- No começo de cada tick de região: `region.getTaskQueueData().drainTasks()` (A4 8397). `drainTasks` roda no máximo as tarefas que já estavam na fila no início (`allowedTickTasks = getScheduledTasks()`, A4 2288-2306), para não entrar em laço infinito com tarefas que postam tarefas.
- Entre ticks, se sobra tempo, roda mais (`ConcreteRegionTickHandle.runRegionTasks`, A4 6201-6222).
- Logo: a tarefa roda **no próximo tick da região dona ou antes**. Não há prazo em milissegundos: se a região alvo atrasa, a tarefa atrasa junto.

**Agendador de região para plugins** (`FoliaRegionScheduler`, A4 6752-7000): se o chamador já é dono, guarda direto; senão, usa `queueTickTaskQueue` para chegar à região certa e só lá conta o atraso (A4 6771-6800). Enquanto houver tarefa pendente numa seção, põe ticket `REGION_SCHEDULER_API_HOLD` (A4 6920-6942). Os dados são por região (`RegionizedData`) e juntam/dividem com o mundo (A4 6874-6915). Exceções de tarefa de plugin são só registradas no log (`wrap`, A4 6754-6763).

**Agendador de entidade**: a classe base `EntityScheduler` é do Paper e **não está no clone**. O que dá para ver: cada região guarda a lista de agendadores das suas entidades (`entitySchedulerTickList`, A4 3221) e move a lista na junção/divisão (A4 2969, 3079); no tick, só executa se `isTickThreadFor(handle)` e se o agendador não está "aposentado" (A4 8404-8411). `schedule(tarefa, aoAposentar, atraso)` devolve booleano; o segundo argumento é chamado se a entidade sumir (uso em `TeleportUtils`, A4 3693-3742).

**Falha numa tarefa**: na fila global há um `// TODO try catch?` antes de `run.run()` (A4 1762-1768). Uma exceção que escapa do tick cai em `regionFailed`, que para o agendador e chama `stopServer()` (A4 5416-5428, 5622-5627). Ou seja: exceção não tratada em qualquer região ou na global **desliga o servidor**.

### 3.3 Teleporte e troca de dimensão

**Regra geral**: teleporte síncrono foi proibido.

- `Entity.teleport(TeleportTransition)`: `throw new UnsupportedOperationException("Must use teleportAsync while in region threading")` (A4 15636-15639).
- Igual em `ServerPlayer.teleport` (A4 12934-12937) e `PlayerList.respawn` (A4 14134-14137).
- `/tp` passa a usar `TeleportUtils.teleport` ou `taskScheduler.schedule(... teleportAsync ...)` (A4 10066-10110).

**`Entity.teleportAsync(destino, pos, ...)`** (A4 15198-15295), etapas:

1. Tem que ser chamado pela dona da entidade: `ensureTickThread(this, "Cannot teleport entity async")`.
2. Recusa (devolve `false`) se: posição fora do mundo; entidade removida/morta/dormindo; passageiros que não podem; regras de veículo (só a raiz do veículo pode teleportar com `TELEPORT_FLAG_TELEPORT_PASSENGERS`; sem a flag, não pode ter veículo nem passageiro).
3. **Mesma região**: se o destino é o mesmo mundo, é dono num raio de 8 chunks e (com a flag de carregar) os chunks já estão prontos → teleporte imediato (`teleportSyncSameRegion`), ajustando o rastreador dos passageiros (A4 15236-15272).
4. **Outra região ou dimensão**:
   - separa a árvore de passageiros (`detachPassengers`);
   - `preChangeDimension` (solta a guia/leash);
   - `transformForAsyncTeleport`: remove a entidade do mundo e cria uma **cópia nova** no destino (`getType().create(...)` + `restoreFrom`). Para jogador, em vez de copiar, tira a conexão da lista da região e muda o mundo do objeto (A4 12784-12793);
   - `placeInAsync`: põe ticket `TELEPORT_HOLD_TICKET` e registra um "teleporte pendente" no mundo destino; se pedido, carrega os chunks assíncronos; depois posta, via `queueTickTaskQueue`, a tarefa que adiciona a entidade e os passageiros no destino e chama o callback (A4 15037-15101).
5. Durante a viagem a entidade não está em mundo nenhum. Na parada do servidor, o `RegionShutdownThread` termina os teleportes pendentes (A4 1153-1180; log na 1162); a documentação explica: teleporte simples é concluído no destino, portal volta à origem (da documentação).

**Portal** (A4 15302-15630): `netherPortalLogicAsync` / `endPortalLogicAsync` → `portalToAsync`. Remove a entidade, põe ticket de espera na origem, busca/cria o portal de saída com carregamentos assíncronos (`findOrCreatePortalAsync`, com uma segunda busca para não criar portal duplo quando duas entidades chegam juntas, A4 15489-15497), e só então chama `placeInAsync`. Mudança de comportamento: **mobs também criam portal de saída** (comentário em A4 15329-15334; documentação confirma). `Entity.handlePortal` agora chama `portalProcess.portalAsync` (A4 14809-14824). End gateway: recusa se a entidade ou o bloco não são da região atual (A4 18368-18376).

**Respawn**: `ServerPlayer.respawn(callback, motivo)` assíncrono (A4 12638-12685): remove do mundo, tira a conexão da lista da região, calcula o ponto de respawn de forma assíncrona e reaproveita `placeInAsync`.

**Utilitário `TeleportUtils.teleport(de, ..., para, ...)`** (A4 3681-3750): primeiro agenda no dono do **alvo** para ler a posição; depois agenda no dono de **quem vai** para chamar `teleportAsync`. Se qualquer um sumir, o callback recebe `null`.

**Outros ajustes**: animal domesticado que teleporta para o dono adia para o próximo tick e usa `teleportAsync` "so that crossing region boundaries will not blow up" (A4 16127-16140); recusa se o dono está em outro mundo (A4 16113-16117).

### 3.4 Coisas que cruzam a borda

Ponto de partida: **a margem**. Ao entrar um chunk, o regionizador cria seções vazias em volta num raio `emptySectionCreateRadius` e junta regiões dentro de `create + merge` seções (A4 4035-4110, `addChunk`). Com os valores padrão (seção de 16 chunks; raio de criação `max(1, 8/16) = 1` seção; raio de junção 1; A4 11510-11518) cada chunk carregado fica com pelo menos uma seção de folga em volta dentro da própria região. A documentação: "Any ticking region must initially own a small buffer of chunks outside its perimeter... Adjacent regions must eventually merge" (da documentação). Região que está ticando não cresce; a junção fica para depois (`mergeIntoLater`, A4 4654; laço que pula `region.isTicking()`, A4 4130-4134).

Com isso, a maior parte das interações locais nunca toca outra região. Para as que tocam, o Folia **corta**:

| Mecânica | O que o Folia faz | Evidência |
|---|---|---|
| Atualização de vizinhos (base de redstone, pistão, fluido) | Ignora vizinho fora da região num raio de 25 blocos ou com chunk não carregado | A7, `CollectingNeighborUpdater` (`runNext` de 3 tipos de atualização) |
| Fio de redstone (algoritmo "turbo") | Pula nós fora da região (raio 24) | A4 1061-1065, `RedstoneWireTurbo` |
| Fio de redstone vanilla, comparador, trilhos detector/energizado | Lê vizinho só se dono e carregado; senão trata como ausente | A7 (`RedstoneWireBlock`, `Level.updateNeighbourForOutputSignal`, `DetectorRailBlock`, `PoweredRailBlock`) |
| Tripwire / gancho | Para a busca ao sair da região | A7 (`TripWireBlock`, `TripWireHookBlock`) |
| Eventos de jogo (vibração, sculk) | Pula chunks fora da região | A4 19674-19678, `GameEventDispatcher` |
| Agendar tick de bloco/fluido em outra região | Erro: `ensureTickThread(..., "Cannot schedule tick for another region!")` | A4 20437, `LevelTicks.schedule` |
| Pistão | Não achei tratamento próprio de borda. Só troca `getGameTime()` por `getRedstoneGameTime()` e ajusta `lastTicked` na junção | A4 19353-19420 |
| Fluido | Não achei tratamento próprio além do corte nos vizinhos e da checagem em `LevelTicks.schedule` | busca por `fluid`/`liquid` na lista de arquivos de A4: só `LevelTicks` |
| Explosão | Não achei tratamento de borda no `ServerExplosion` (só o cache de densidade virou por região, A4 18195-18210). Pela API, criar explosão fora do dono dá erro (B2 1123, 1131, `CraftWorld.createExplosion`). Escrever bloco fora do dono dá erro em `Level.setBlock` ("Updating block asynchronously") | A4 17859 (`Level.setBlock`) |
| Entidade andando | Chunk que não é da região vira **caixa sólida** na colisão: a entidade bate numa parede invisível (raio 4 chunks) | A4 863-872, `CollisionUtil` |
| Mover para chunk não carregado | Forçado como proibido: `preventMovingIntoUnloadedChunks = true` | B2 463 (`WorldConfiguration`) |
| Projéteis (cuspe de lhama, bolas de fogo etc.) | Param de ticar se o próximo passo sai da região | A4 16901, 16951, 16967, 16983 |
| Empurrão de pistão em entidade | Para se o bloco cruzado não é da região (raio 16) | A4 14756-14760 |
| Jogador quebrando/interagindo com bloco | Recusa fora da região (raio 8 chunks) | A4 13504, 13513 |
| Veículo e passageiros | Teleporte leva a árvore inteira. Todo tick, se passageiro se afasta mais de 1 chunk da raiz, é teleportado de volta, com aviso no log | A12 (`fixPassengerDesync`); A4 15198-15295 |
| Confirmação de teleporte vinda do cliente fora da região | Jogador é desconectado ("invalid_player_movement") | A13 |
| Portais | Sempre assíncronos (ver 3.3) | A4 15302-15630 |
| Raids, cerco de vila | Só começam/agem se o centro e o jogador são da região | A4 17349, 16336, 17183 |

O texto do próprio patch A7 assume o custo: "While this does add explicit block update suppression techniques, it's better than the server crashing." Ou seja, **o comportamento na borda muda de propósito** (atualizações se perdem).

Na divisão de região, eventos de bloco e block entities de chunk já descarregado **são descartados** (A4 3085-3092, 3115-3134; comentário "So if it unloads, I guess it's just lost").

### 3.5 Checagens de dono

**Onde ficam as funções**: `ca.spottedleaf.moonrise.common.util.TickThread`. O clone só tem o **diff** dela (B2, linhas 7-200). O diff troca o corpo das várias `isTickThreadFor(...)`, que no Paper eram só `return isTickThread();`, pela consulta ao regionizador:

- `isTickThreadFor(world, chunkX, chunkZ)`: região atual == região do chunk.
- `isTickThreadFor(world, pos, raio)` e variantes com caixa (`AABB`) ou movimento: confere **todas** as seções cobertas; uma só fora já dá falso.
- `isTickThreadFor(entity)`: entidade está na lista da região atual (`worldData.hasEntity`), ou, para jogador, a conexão está na lista da região; entidade ainda não adicionada ao mundo pode ser "possuída" por quem a criou (`hasNullCallback() && !isRemoved()`).
- Na thread de parada (`RegionShutdownThread`), tudo passa (comentário A4 1140: "bypasses all tick thread checks").

**O que disparam**:

- O corpo de `ensureTickThread(...)` **não está no clone** (é do Paper). Pelo nome, pelo uso com mensagem de erro e pelo campo `LOGGER` e `getThreadContext()` no diff (que monta texto com thread e região), parece lançar exceção com essa descrição. **Não verificado.**
- Onde o Folia escreveu a reação explícita, é exceção:
  - `IllegalArgumentException("Entity ... is not under this region's control")` em `RegionizedWorldData.addEntity/removeEntity/addEntityTickingEntity/...` (A4 3466-3530; ex.: 3469, 3481, 3500);
  - `IllegalStateException(reason)` em `RegionizedServer.ensureGlobalTickThread` (A4 1709-1713);
  - `IllegalStateException("World mismatch...")` em `Level.getCurrentWorldData()` quando a região atual é de outro mundo (A4 17815-17824; mensagem na 17822).
- Exceção que escapa do tick → `regionFailed` → servidor para (A4 5416-5428).
- Onde o Folia quer tolerar, usa `isTickThreadFor` e **pula em silêncio** (ver tabela de bordas).

**Quantas e em quê** (contagem das mensagens em A4 e B2, `grep` em linhas `+`):

- 168 x "Accessing entity state off owning region's thread" — quase tudo na API Bukkit de entidade (`CraftEntity` e subclasses, B2).
- 14 x "Cannot read world asynchronously" — leitura de bloco pela API (`CraftBlock`, B2 1305-1495).
- 3 x "Cannot modify world asynchronously" (B2 `CraftBlock`, `CraftBlockState`), 2 x "Updating block asynchronously" (`Level.setBlock`, `LevelChunk`).
- Global: "Cannot modify weather/time/server settings/metadata off of the global region" (5+2+5+2+2), "Cannot read/modify force-loaded chunk(s) off global region".
- Outras: carregar chunk, explosão, efeitos de poção, portal, teleporte, respawn, passageiros, ticks agendados, eventos de bloco, block entity ("Tile entity must be owned by current region", A4 3591), mapas, comandos ("Dispatching command async").

**Ponto de atenção — leitura NMS sem checagem**: `Level.getBlockState` não chama `ensureTickThread`. Ele chama `this.getCurrentWorldData().captureTreeGeneration` (A4 17966). Lendo o código: numa thread fora do pool, `getCurrentWorldData()` devolve `null` (A4 17816-17818), então a leitura daria `NullPointerException` (inferência minha pelo código, não testado); numa região de outro mundo, dá "World mismatch"; numa região do **mesmo** mundo mas outra posição, **não há checagem** e a leitura acontece sem proteção.

### 3.6 O que foi desligado ou mudou de comportamento

Desligado no código (lança exceção ou foi comentado):

- **Agendador Bukkit clássico**: `CraftScheduler.handle` → `if (true) throw new UnsupportedOperationException()` (B2 4065-4066). A interface `BukkitScheduler` virou `@Deprecated` apontando para os novos agendadores (C2).
- **Fila de tarefas da thread principal**: `MinecraftServer.submit/schedule/execute/executeBlocking` lançam `UnsupportedOperationException` (A4 8060-8100); `executeIfPossible` também (A4 8750); `getTickCount()` também (A4 8726). `MCUtil.ensureMain` fora da main lança (B2 815-818).
- **Placares/times**: `Entity.getTeam()` sempre `null` (A4 14842-14847); `CraftScoreboard.registerNewObjective/registerNewTeam/clearSlot`, `CraftScoreboardManager.getNewScoreboard/setPlayerBoard` lançam "not supported yet" (B2 4078, 4086, 4094, 4106, 4114); `updateEntireScoreboard` no login comentado (A4 14019); regra de colisão por time desligada (`if (false && collideRuleTeamName...)`, PlayerList).
- **Funções de datapack**: `this.getFunctions().tick()` comentado ("TODO Purge functions", A4 8593).
- **Comandos vanilla não registrados** (A4 7334-7410): `bossbar`, `clone`, `compute`, `data`, `datapack`, `debug`, `dialog`, `function`, `item`, `loot`, `reload`, `return`, `schedule`, `scoreboard`, `spectate`, `spreadplayers`, `tag`, `team`, `teammsg`, `test`, `tick` (lista parcial: só conferi o trecho 7334-7410).
- **Comandos que mudaram de thread**: `/time`, `/weather` etc. agora postam na fila global e devolvem 0 (A4 10114-10160). Console: só pela global (`ensureGlobalTickThread("Dispatching command async")`, B2 879). Comando de entidade: só pela dona (B2 875).
- **Chat síncrono**: `PlayerChatEvent`/`ChatEvent` síncronos desligados (`if (false && listenersOnSyncEvent)`, B2 289 e 298, `ChatProcessor`); conversas (`isConversing`) lançam exceção (A4 13556-13557).
- **Pausa com servidor vazio** desligada (`if (false && emptyTickThreshold > 0)`, A4 8365).
- **Profiler spark embutido** desligado (B2 267, `SparksFly`, `if (false)`).
- **`tickables`** (atualização da GUI do servidor) e `processQueue` do CraftBukkit desligados (`if (false)`, A4 8666 e 8614).
- **Plugins sem `folia-supported: true`** não carregam: `RuntimeException` nas fábricas de plugin Paper e Spigot (B2 736 e 752), `InvalidPluginException` em `CraftMagicNumbers` (B2 4128). A chave vem do `plugin.yml` (C3).
- `ChunkTaskScheduler.executeMainThreadTask` e afins: "Use regionised ticking hooks" (A4 746, 758, 791).

Mudou de comportamento:

- Mobs criam portal de saída igual a jogador (3.3).
- Atualizações de bloco na borda se perdem (3.4); entidades batem em "paredes" na borda.
- Evento `ServerTickStartEvent`/`ServerTickEndEvent` agora dispara **uma vez por região por tick**, com o tick da região (A4 8394, 8461). Para o nosso caso, isso é o equivalente ao `ServerTickEvent` do Forge sendo chamado N vezes.
- Sincronização de tempo para o cliente: por jogador, espalhada pelos ticks (`(tick + player.getId()) % 20 == 0`), A4 8696.
- `Entity.SHARED_RANDOM` virou aleatório por thread (A4 14659-14660; classes `ThreadLocalRandomSource`/`SimpleThreadLocalRandomSource` em A4 7156-7242).
- Agendamento: cada região tem seu prazo; uma região lenta não atrasa as outras (A4 5590-5640; documentação dá exemplo com regiões A e B).

### 3.7 Login, logout e rede

- **Nova conexão**: assim que o canal abre, a conexão entra na lista da global (`RegionizedServer.addConnection`, A4 13285, em `ServerConnectionListener`).
- **Handshake, login e configuração**: na global. `GlobalTickTickHandle` tica essas conexões (`tickConnections`, A4 1869-1905) e pula as que já são de região (`isNotOwnedByGlobalRegion`, A4 1855-1867).
- **Pacote fora de hora**: `PacketUtils.ensureRunningOnSameThread` (A4 7944-7960). Se a thread atual não é de tick:
  - pacote de jogo → fila de pacotes **do jogador** (`CraftPlayer.packetProcessor`, B2 3080-3106), e avisa a última região conhecida (`setHasPackets`);
  - pacote de login/configuração → fila de pacotes **da global** (`RegionizedServer.schedulePacket`, A4 1671-1675).
- **Quem processa os pacotes de jogo**: a região dona do jogador, no começo do tick (A4 8399-8402) e entre ticks (`drainOnePacket`, A4 5953-5987), sempre conferindo `isTickThreadFor(player)`.
- **Entrar no mundo**: no fim da configuração, o servidor cria o `ServerPlayer`, marca `switchToMain`, pede o chunk de spawn em `ENTITY_TICKING` de forma assíncrona, põe ticket `PLAYER_SPAWN`, e posta `spawnPlayer` na fila da região do spawn (A4 13232-13262; `switchToMain` na 13243). Lá, `placeNewPlayer` adiciona a conexão à lista da região (`level.getCurrentWorldData().addConnection(player)`, A4 14009). A partir daí a região é dona da conexão.
- **Login duplicado e limite**: `PlayerList.pushPendingJoin` usa trava própria e mapas de conexões por nome e UUID (A4 13952-13980). A5 limita logins pendentes por `maxJoinsPerTick`.
- **Logout**: a região dona percebe `!conn.isConnected()`, chama `handleDisconnection`, tira da global e da região e remove o ticket `DISCONNECT_TICKET` (A4 3394-3406, 3363-3373). `handleDisconnection` virou à prova de chamada dupla com `compareAndSet` (A4 7834) e remove o nome/UUID da tabela de conexões (A4 7848-7862).
- **Desconectar de outra thread**: em vez de esperar a thread principal, chama `disconnectSafely` ("it HAS to be delayed/async to avoid deadlock if we try to wait for another region", A4 13175-13211; frase na 13209).
- **Durante teleporte entre regiões**: a conexão sai da lista da região de origem (A4 12786) e só entra na de destino em `placeSingleSync` (A4 12802-12810). Inferência minha: nesse intervalo ninguém tica a conexão e os pacotes esperam na fila do jogador (não testado).
- Netty (threads de rede de entrada/saída) continua com threads próprias; o README recomenda reservar ~4 threads por 200-300 jogadores (README linhas 52-55; documentação, não conferido no código).

---

## 4. O que isso sugere para um servidor 1.7.10 com mods

**Opinião minha, não fato dos projetos.** Serve de entrada para o desenho das Etapas 2 e 3.

1. **A "thread global" pode ser uma tarefa, não uma thread.** O Folia agenda a global no mesmo pool. Para nós, isso evita uma thread extra parada, mas exige que "sou a global?" seja uma pergunta sobre a tarefa atual. Em 1.7.10, `ServerTickEvent` e `WorldTickEvent` do Forge teriam de rodar na global (uma vez por tick), não por região, senão mods que contam ticks quebram.
2. **Correio com ticket é a ideia mais útil.** Postar uma tarefa por posição e deixar o ticket criar a região resolve "a região não existe". Para 1.7.10 dá para copiar a forma (fila FIFO por região + contador por seção + junção que preserva ordem) sem o sistema Moonrise.
3. **Teleporte e portal assíncronos são obrigatórios e caros de portar.** Em 1.7.10, `travelToDimension`, `transferPlayerToDimension` e os teleportes de mods (Mystcraft, Thaumcraft, ender IO etc.) são síncronos e chamados de dentro do tick. Ou interceptamos todos e convertemos para o modelo "remover, copiar, postar", ou fazemos teleporte entre regiões passar pela global com tudo parado.
4. **Cortar atualização na borda não serve para mods.** O Folia aceita perder redstone e fluido na borda. Redes de energia/itens de mods (IC2, BuildCraft, AE2, Mekanism) cruzam chunks e quebrariam ou duplicariam itens. Para nós, a margem precisa ser grande ou a regra de junção precisa considerar as redes (juntar regiões ligadas por cabo), e o padrão deve ser "não dividir" quando houver dúvida.
5. **Checagem de dono com exceção derruba o servidor.** No Folia, exceção no tick = servidor para. Com 292 mods que nunca respeitaram dono, isso pararia o servidor a toda hora. Sugiro duas fases: (a) observador que só registra no log quem tocou fora do dono (a Etapa 2 que já planejamos); (b) só depois, e por recurso, transformar em bloqueio.
6. **Leitura sem checagem é o buraco maior.** No Folia, `getBlockState` de outra região do mesmo mundo passa sem aviso. Em 1.7.10 os mods leem `world.loadedTileEntityList` e campos públicos direto, sem passar por método nenhum. Não dá para checar com o mesmo truque; o observador terá de usar outra técnica (ex.: transformação de bytecode nos acessos) — a decidir.
7. **Plugins e agendador Bukkit.** O Folia simplesmente recusa plugin não marcado e desliga o `BukkitScheduler`. Com 65 plugins isso não é opção para nós. Uma saída possível: rodar o agendador Bukkit na global e manter plugins não marcados só na global (com o mundo travado enquanto rodam). Precisa de estudo.
8. **Placares, comandos e funções desligados.** O Folia aceitou perder placares/times e ~20 comandos. Para nós, esses recursos (e os comandos de mods) teriam de ir para a global, com acesso ao mundo por correio.
9. **Contador de tempo por região** (tempo de redstone e tick próprio, com deslocamento na junção) é necessário se as regiões puderem atrasar de forma independente. Em 1.7.10, `world.getTotalWorldTime()` é usado por muitos mods como relógio; manter um único relógio global por tick pode ser mais seguro, ao custo de as regiões andarem juntas.

---

## 5. Não verificado

- **Corpo de `TickThread.ensureTickThread(...)` e de `isTickThread()`**: são do Paper/Moonrise, fora do clone. Só vi o diff (B2 linhas 7-200). Não sei se loga, se lança, nem qual exceção. Busquei `ensureTickThread` em todos os `.patch`: só usos, nenhuma definição.
- **Classe `EntityScheduler` completa** (semântica exata de `schedule`, `retire`, atraso): base no Paper. Só vi ajustes (B2 767-800) e usos.
- **Interfaces de API `RegionScheduler`, `AsyncScheduler`, `GlobalRegionScheduler`, `EntityScheduler`** (plugin): não estão no clone; só aparecem em javadoc em C2.
- **Se um ticket em `MAX_TICKET_LEVEL` cria o chunk holder sem carregar o chunk**: é regra do sistema de chunks Moonrise, fora do clone. Inferi pelo comentário em A4 15040 e por `onChunkHolderCreate → regioniser.addChunk` (A4 64).
- **Ordem entre filas diferentes** (global x região x entidade): não achei garantia; também não achei prova de que não existe.
- **Salvamento periódico de `level.dat`/dados globais fora da parada**: só achei na `RegionShutdownThread` (A4 1145, 1301, 1309). Não conferi o comando `/save-all` nem o vigia (A11).
- **Explosões, pistões e fluidos na borda**: não achei tratamento específico; a conclusão de que dependem da margem é minha. Não li `ServerExplosion` inteiro (só o trecho alterado).
- **Lista completa de comandos desligados**: li só A4 7334-7410.
- **Comportamento da conexão do jogador durante teleporte entre regiões** (keepalive, pacotes): inferência pela leitura de A4 12784-12810 e 1835-1847; não confirmado.
- **NullPointerException ao ler `Level.getBlockState` fora do pool**: inferência pela leitura de A4 17815-17824 e 17966; não testado.
- **Algoritmo de divisão de regiões e "regiões mortas" (`maxDeadRegionPercent`)**: não li a fundo; fora do tema deste relatório.
- **Página `region-logic` da documentação**: baixada, não lida a fundo para este tema.
- Nada foi compilado nem executado; todas as afirmações são de leitura de código no commit `eca40f91`.
