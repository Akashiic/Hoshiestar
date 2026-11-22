# Conferência: "Folia: thread global, correio entre regiões e checagens de dono"

- Relatório conferido: `folia-global-correio.md` (mesma pasta).
- Conferente: agente diferente do autor. Só leitura de código. Nada compilado, nenhum servidor ligado, nenhum commit.
- **Commit do clone conferido: `eca40f918928e78c2c5f9c944cfb083d7d9851d4`** (Folia, 2026-11-18). Mesmo commit do relatório.
- Abreviações iguais às do relatório: **A4** = `folia-server/minecraft-patches/features/0004-Region-Threading-Base.patch`, **B2** = `folia-server/paper-patches/features/0002-Region-Threading-Base.patch`, **A7/A12/A13** = patches 0007/0012/0013 da mesma pasta de A4. "Linha N" = linha dentro do arquivo `.patch`.

Termos usados aqui (primeira vez):
- **Ticket**: pedido registrado no sistema de chunks para manter um chunk (ou só o "suporte" dele, o chunk holder) vivo.
- **Região em estado TRANSIENT**: região que está esperando juntar com outra e por isso não tica.
- **Thread de tick**: thread do pool que tica regiões (classe `TickThread`); "thread assíncrona" é qualquer outra.

## Resultado em uma frase

O relatório é bom e honesto: a maior parte confere linha a linha. Achei 1 afirmação errada (o "empurrão de pistão" não é pistão), 10 correções (contagens, raio da parede invisível, carga síncrona de chunk, dono de entidade nova, ordem dentro do tick, thread principal, exceções que não derrubam o servidor) e 13 fatos que faltaram. Dois deles pesam no nosso desenho: o próprio Folia usa tickets para **forçar duas regiões a se juntar** (A12), e o teleporte entre regiões **troca o objeto da entidade por uma cópia** (A4 15175-15184).

---

## 1. Tabela: afirmação -> veredito -> evidência

Legenda: **C** = confirmada; **Cor** = corrigida; **R** = refutada; **NV** = não verificável no clone.

| # | Afirmação do relatório | Veredito | Evidência / versão certa |
|---|---|---|---|
| 1 | A global é uma tarefa (`GlobalTickTickHandle`) agendada no mesmo pool das regiões, com região nula | C | A4 1635-1643 (`scheduleRegion(this.tickHandle)`), A4 1719-1724 (`super(null, ...)`) |
| 2 | "Sou a global?" pergunta pela tarefa atual, não pela thread | C | A4 1705-1707: `INSTANCE.tickHandle == TickRegionScheduler.getCurrentTickingTask()` |
| 3 | "Não existe thread principal" (resumo, item 1) | Cor | A thread principal **existe**: carrega os mundos, chama `RegionizedServer.init()` e depois **dorme para sempre** (`Thread.sleep(Long.MAX_VALUE)` num laço, A4 8267-8275). Ela só não tica nada. A parada roda numa thread própria, `RegionShutdownThread` (A4 8179-8203). |
| 4 | Pool "Folia Region Scheduler Thread #N" | C | A4 5224-5238 |
| 5 | Tamanho padrão do pool: metade dos núcleos dividida por 4, ou 1 em máquina pequena | C (com número exato) | A4 5786-5800: `cores/2`; se isso der `<= 4`, usa 1; senão divide por 4. Ou seja: até 9 núcleos -> 1 thread; 16 núcleos -> 2; 32 -> 4. `threads = -1` em B2 436 |
| 6 | Seção padrão 16x16 chunks (`gridExponent = 4`) | C | B2 437; A4 5814-5818 (`regionShift = gridExponent`, limitado entre 0 e 31) |
| 7 | Global, antes do tick: esvazia fila global e pacotes de login/configuração | C | A4 1752-1766. Detalhe que faltou: na global o esvaziamento **não tem limite** (ver "o que faltou", item 3) |
| 8 | `globalTick(long)` faz: callbacks de clique, agendador global de plugins, relógios, console, status, tick global de cada mundo, mapas, conexões da global, `PlayerList.tick()` | C | A4 1803-1838 |
| 9 | `globalTick(mundo)` faz: atributos de ambiente, tarefas globais de chunk, borda, clima, sono, raids, brilho do céu, tempo de jogo, `updateTickData`, `processTicketUpdates` | C | A4 1910-1949, corpos em 1951-1985 |
| 10 | A global não mexe em chunk/bloco/entidade: `isTickThreadFor(mundo, chunk)` devolve `isShutdownThread()` quando a região é nula | C | B2 diff de `TickThread`, `isTickThreadFor(Level,int,int)` (~linha 89-95 do patch) |
| 11 | Na global, só `ServerPlayer` em login/configuração é "possuído" | C (detalhe) | B2 `isTickThreadFor(Entity)`: na global devolve `true` também quando `serverPlayer.connection == null`, e quando o jogador está voltando para configuração (`waitingForSwitchToConfig`). Qualquer outra entidade: `false` |
| 12 | Salvamento incremental de chunks/jogadores roda no tick de cada região; `level.dat` só na parada | C (reforçada) | A4 8430-8450 (`region == null && fullSave` nunca é verdadeiro numa região); `PlayerList.saveAll` pula jogador de outra região (A4 14246-14250); `saveLevelData` só em `RegionShutdownThread` (A4 1145-1151, 1301, 1309). **Novo:** o comando `/save-all` nem é registrado (A4 7437, `//SaveAllCommand.register ... TODO later`), o que fecha um "não verificado" do relatório |
| 13 | `RegionizedTaskQueue`: duas filas por região (`tickTaskQueue`, `chunkQueue`) mais fila global de tarefas de chunk por mundo | C | A4 2254-2260; 2098-2125 |
| 14 | `queueOrExecuteTickTask`: dono roda na hora, senão enfileira | C | A4 2078-2093 |
| 15 | `queue()` põe ticket `TASK_QUEUE_TICKET` em `MAX_TICKET_LEVEL` no chunk do canto da seção, procura a fila sem trava e depois com trava, refaz se a fila foi destruída, marca `setHasTasks` | C | A4 2607-2660; canto da seção = `sectionLowerLeftCoord` (A4 2517); ticket A4 2137-2147. Faltou dizer que o ticket é **processado na hora** (ver "o que faltou", item 1) |
| 16 | Contador por seção; no zero, o ticket sai | C | A4 2164-2187 (`decrementReference`), chamado no `finally` de `executeTask` (A4 2477-2481) e em `tryComplete` (A4 2586-2589) |
| 17 | Se mesmo com trava a fila não existe e a tarefa não foi cancelada: `IllegalStateException("Expected null ref count...")` | C | A4 2632-2635 |
| 18 | "A região passa a existir por causa da tarefa" (ticket cria chunk holder -> `regioniser.addChunk`) | C em parte / NV no elo final | `onChunkHolderCreate -> regioniser.addChunk` está em A4 61-64. Que um ticket em `MAX_TICKET_LEVEL` crie o chunk holder é regra do Moonrise, fora do clone. A forma do código (adiciona ticket, processa na hora, depois procura a fila e lança erro se não achar) só faz sentido se o holder for criado, mas isso continua sendo inferência |
| 19 | Resumo item 6: "Postar uma tarefa **não carrega** o chunk" | NV (escrito como fato no resumo) | O corpo do relatório já marca como não verificado, mas o resumo afirma. Indício a favor: quem precisa do chunk pronto usa outro ticket, `FULL_LOADED_TICKET_LEVEL` (A4 15093-15097). Indício contra (ambíguo): o comentário do `DISCONNECT_TICKET`, também em `MAX_TICKET_LEVEL`, diz "force chunk to be loaded so that the region is not lost" (A4 13540). Deixar o resumo como "não verificado" |
| 20 | Ordem FIFO dentro da mesma prioridade; prioridade mais alta sai primeiro | C | A4 2459-2470 (`addLast` em `tryPush`, `pollFirst` em `executeTask`, laço `0..IDLE`) |
| 21 | Junção põe as tarefas da região que some na **frente** da outra, com iterador reverso; divisão preserva ordem | C | A4 2355-2382 (comentário "we need to ensure order is maintained"); 2384-2432 |
| 22 | "Não achei garantia de ordem **entre** filas diferentes" | Cor (em parte) | Entre duas postagens em filas diferentes, de fato não há garantia. Mas **dentro de um tick de região** a ordem é fixa: 1) `RegionizedTaskQueue.drainTasks`, 2) pacotes dos jogadores, 3) `FoliaRegionScheduler.tick`, 4) agendadores de entidade, 5) `tickChildren` (mundo) — A4 8394-8414 |
| 23 | `drainTasks` roda no máximo as tarefas que já estavam na fila no início | C (tick task e chunk task) | A4 2288-2306. Exceção: as tarefas **globais de chunk** do mundo são drenadas sem limite no mesmo laço (`executeGlobalTasks`, A4 2296-2302) |
| 24 | Entre ticks roda mais (`runRegionTasks`) | C | A4 6201-6222 |
| 25 | `FoliaRegionScheduler`: dono guarda direto, senão vai pela fila; ticket `REGION_SCHEDULER_API_HOLD`; exceção de plugin só vai ao log | C (detalhe) | A4 6754-6763 (`wrap`), 6816-6820 (`isOwnedByCurrentRegion`), 6920-6942 (ticket). Detalhe: `execute(...)` (sem atraso) **sempre** enfileira, mesmo se o chamador já é dono (A4 6784-6792) |
| 26 | Agendador de entidade: base no Paper, fora do clone; tick só se `isTickThreadFor(handle)` e não aposentado | C | A4 8404-8411; B2 767-800 só tem ajustes (`isRetiredOffThread`, `getVolatile`) |
| 27 | Fila global tem `// TODO try catch?`; exceção que escapa do tick -> `regionFailed` -> `stopServer()` | C | A4 1767; A4 5416-5428; chamadas em 5543 e 5624 |
| 28 | Resumo item 9 / 3.5: "Exceção dentro de um tick derruba o servidor" | Cor (escopo) | Vale para exceção que **escapa** do tick. Mas: exceção ao ticar uma conexão (pacotes) é pega, registrada e o jogador é **expulso** com "Internal server error" (A4 1882-1898 na global; A4 3407-3423 na região); tarefa de plugin pelo `FoliaRegionScheduler` é pega e registrada (A4 6754-6763). Exceção em tarefa crua da `RegionizedTaskQueue` não é pega (A4 2549-2555, `executeInternal` só tem `finally`) |
| 29 | `Entity.teleport`, `ServerPlayer.teleport`, `PlayerList.respawn` síncronos lançam `UnsupportedOperationException("Must use teleportAsync...")` | C | A4 15636-15639, 12934-12937, 14134-14137 (todos com `if (true)`) |
| 30 | Etapas do `teleportAsync`: checa dono, recusa fora do mundo/entidade inválida/regras de veículo; mesma região (raio 8 chunks, chunks prontos) -> imediato; senão separa passageiros, `preChangeDimension`, `transformForAsyncTeleport`, `placeInAsync` | C | A4 15198-15283. Faltou a flag `TELEPORT_FLAG_UNMOUNT`, que desmonta a entidade em vez de recusar (A4 15210-15218) |
| 31 | `transformForAsyncTeleport` remove e cria cópia (`getType().create` + `restoreFrom`); jogador não é copiado | C | A4 15175-15184; jogador A4 12784-12793. Consequência que faltou: ver "o que faltou", item 5 |
| 32 | `placeInAsync`: ticket `TELEPORT_HOLD_TICKET`, teleporte pendente, carga assíncrona opcional, depois `queueTickTaskQueue` | C | A4 15037-15101. Linha do comentário citado ("ensure the region is always ticking in case of a shutdown") é A4 15045-15046, não 15040-15041, e fala do ticket de teleporte, não do ticket da fila |
| 33 | Na parada, `RegionShutdownThread` termina os teleportes pendentes | C | A4 1153-1180. "Portal volta à origem" é da documentação; no código, `finishTeleportations` põe a entidade no mundo da região que está parando (`node.root.setLevel(world)`, com comentário sobre portal), A4 1166-1170. Não conferi se isso é "origem" no caso de portal: NV |
| 34 | Portais assíncronos; mobs também criam portal de saída; segunda busca para não criar portal duplo | C | A4 15302-15323, comentário 15329-15334, segunda busca 15489-15497; `handlePortal -> portalAsync` A4 14805-14824 |
| 35 | End gateway recusa se entidade ou bloco não são da região | C | A4 18366-18376 (`EndGatewayBlock.portalAsync`) |
| 36 | `TeleportUtils`: agenda no dono do alvo, depois no dono de quem vai; `null` se algum sumir | C | A4 3681-3750 |
| 37 | `/tp` usa `TeleportUtils` ou `taskScheduler.schedule(... teleportAsync ...)` | C | A4 10066-10110 |
| 38 | Animal domesticado adia teleporte e usa `teleportAsync` | C | A4 16110-16140 (`TamableAnimal`) |
| 39 | Margem: raio de criação `max(1, 8/16) = 1` seção, raio de junção 1; região que tica não cresce, junção fica para depois | C | A4 11510-11518 (construtor do regionizador; também `minSectionRecalcCount = 8` e `maxDeadRegionPercent = 1/6` com shift 4); A4 4035-4110 (`addChunk`); laço que pula `isTicking()` A4 4130-4134 |
| 40 | Vizinhos (redstone/pistão/fluido) cortados num raio de 25 blocos ou chunk não carregado | C | A7 linhas 20, 34, 49, 65, 102, 119, 131 (`isTickThreadFor(..., 25)`) |
| 41 | Redstone "turbo" pula nós fora da região (raio 24) | C | A4 1061-1065 |
| 42 | Tripwire/gancho param ao sair da região | C (raio) | A7 linhas 77 e 89: raio de **4 blocos** |
| 43 | Eventos de jogo pulam chunks fora da região | C (raio) | A4 19674-19678: raio de 1 chunk |
| 44 | Agendar tick de bloco/fluido em outra região: `ensureTickThread("Cannot schedule tick for another region!")` | C | A4 20437 (`LevelTicks.schedule`) |
| 45 | Pistão: só troca para `getRedstoneGameTime` e ajusta `lastTicked` na junção | C | A4 19353-19420 |
| 46 | Explosão: só o cache de densidade virou por região; API de explosão exige dono; `Level.setBlock` exige dono | C | A4 18195-18210; B2 1123 e 1131; A4 17859 |
| 47 | Colisão: "chunk que não é da região vira caixa sólida (raio 4 chunks)" | Cor | O teste é `isTickThreadFor(world, chunkX, chunkZ, 4)` para **cada** chunk percorrido (A4 863): o chunk vira caixa sólida se ele **ou qualquer chunk a até 4 chunks dele** não for da região. Na prática, a parede aparece até 4 chunks **antes** da borda da região, não na borda |
| 48 | `preventMovingIntoUnloadedChunks = true` forçado | C | B2 ~463 (`WorldConfiguration.Chunks.postProcess`) |
| 49 | Projéteis param de ticar se o próximo passo sai da região | C | A4 16901, 16951, 16967, 16983 (buffer de 1 chunk) |
| 50 | "Empurrão de pistão em entidade: para se o bloco cruzado não é da região (raio 16), A4 14756-14760" | **R** | O trecho não é de pistão. Está em `Entity`, no laço que percorre os blocos que a entidade atravessa durante o movimento (`iterations.set(iteration); getBlockState(blockIntersection); if (state.isAir())`), hunk `@@ -1893` de `Entity.java` (A4 14751-14760). Pelo formato, é a checagem de "blocos por dentro" do movimento (placa de pressão, teia, portal etc.); o nome do método não aparece no patch, então o nome exato fica não verificado. O código de pistão em `Entity` (A4 14736-14748) só troca o relógio |
| 51 | Jogador quebrando/interagindo fora da região (raio 8 chunks): recusa | C | A4 13504, 13513 |
| 52 | Passageiro a mais de 1 chunk: teleporta a árvore para a raiz, com aviso | C (faltou detalhe) | A12 linhas 49-121. Antes de teleportar, põe tickets `MAX_TICKET_LEVEL` num caminho em L entre veículo e passageiros para "force connect the chunks" (A12 84-113). Ver "o que faltou", item 4 |
| 53 | Confirmação de teleporte fora da região -> desconecta ("invalid_player_movement") | C | A13 linhas 16-27 (raio 1 chunk) |
| 54 | Raids e cerco de vila só agem com centro/jogador na região | C | A4 17349 (raio 8), 17180-17186 (`ownsRaid`), 16332-16340 (cerco é **reiniciado** se o ponto sai da região) |
| 55 | Divisão descarta eventos de bloco e block entities de chunk sem seção | C | A4 3085-3092 ("I guess it's just lost"), 3115-3134 |
| 56 | Corpo de `isTickThreadFor` trocado; versões com raio/caixa conferem todas as seções | C | B2 diff `TickThread` (~linhas 59-150 do patch), checa o chunk do canto de cada seção |
| 57 | Entidade não adicionada ao mundo "pode ser possuída por quem a criou (`hasNullCallback() && !isRemoved()`)" | Cor | Essa regra vale para thread **assíncrona** (nem região, nem global, nem parada): ela "possui" qualquer entidade ainda não adicionada. Numa **thread de região**, a regra é `(hasNullCallback() \|\| isRemoved()) && isTickThreadFor(mundo, chunk da entidade)`. Jogador fora de região/global: sempre `false`. B2 diff `isTickThreadFor(Entity)`, ~linhas 182-218 |
| 58 | Na `RegionShutdownThread` "tudo passa" | C (com ressalva do próprio código) | Comentário A4 1140-1143: passa nas checagens, mas "will not let us perform arbitrary sync loads, arbitrary world state lookups" |
| 59 | Corpo de `ensureTickThread` fora do clone | NV | Confirmado que não há definição em nenhum `.patch`; só usos |
| 60 | `IllegalArgumentException("... is not under this region's control")` nas listas da região | C | A4 3469, 3481, 3500, 3517, 3530, 3537 |
| 61 | `ensureGlobalTickThread` lança `IllegalStateException` | C | A4 1709-1713 |
| 62 | `Level.getCurrentWorldData()` lança "World mismatch" se a região atual é de outro mundo; devolve `null` fora do pool | C | A4 17815-17824 |
| 63 | "~245 pontos de `ensureTickThread` (216 em B2, 29 em A4)" | Cor | Contando linhas `+`: `ensureTickThread` = **196 em B2 + 28 em A4 = 224**; `ensureGlobalTickThread` = **20 em B2 + 1 em A4 = 21**. O total 245 bate, mas soma as duas funções sob um nome só |
| 64 | 168 x "Accessing entity state off owning region's thread", 14 x "Cannot read world asynchronously", 3 x "Cannot modify world asynchronously", 2 x "Updating block asynchronously" | C | Contagem refeita: 168/14/3 em B2, 2 em A4 |
| 65 | Global: weather 5, time 2, server settings 5, metadata 2, força de chunk | C | Contagem refeita em B2 (weather 5, time 2, settings 5, metadata 2, force-loaded 3) |
| 66 | "Tile entity must be owned by current region", A4 3591 | C (linha) | Está em A4 3593 |
| 67 | `Level.getBlockState` não chama `ensureTickThread`; fora do pool daria `NullPointerException` | C (leitura) / NV (execução) | A4 17963-17968: usa `getCurrentWorldData().captureTreeGeneration` sem checar nulo. Não testado; também não conferi se alguma subclasse sobrescreve |
| 68 | Agendador Bukkit desligado (`CraftScheduler.handle` lança) | C | B2 4065-4066 |
| 69 | `MinecraftServer.submit/execute/...`, `executeIfPossible`, `getTickCount` lançam | C | A4 8063-8098, 8750, 8726 |
| 70 | Placares/times desligados (`getTeam` nulo, `CraftScoreboard*` lança, `updateEntireScoreboard` comentado, regra de colisão por time desligada) | C | A4 14842-14847, 14019, 14047 e 14065; B2 4078, 4086, 4094, 4106, 4114 |
| 71 | Funções de datapack não ticam | C | A4 8593 |
| 72 | "~20 comandos vanilla não registrados" (lista de 21, "parcial") | Cor | São **25**: bossbar, clone, compute, data, datapack, debug, dialog, function, item, loot, reload, return, schedule, scoreboard, spectate, spreadplayers, tag, team, teammsg, test, tick, **trigger, waypoint, perf, save-all** (A4 7334-7440) |
| 73 | `/time`, `/weather` postam na global e devolvem 0; console só pela global; comando de entidade só pela dona | C | A4 10114-10160; B2 875-879 |
| 74 | Chat síncrono desligado; conversas lançam | C | B2 289 e 298; A4 13557 |
| 75 | Pausa com servidor vazio, spark embutido, `tickables`, `processQueue` desligados | C | A4 8365; B2 267; A4 8666 e 8614 |
| 76 | Plugins sem `folia-supported` não carregam | C | B2 736, 752 (`RuntimeException`), 4127-4128 (`InvalidPluginException`) |
| 77 | `ServerTickStartEvent/EndEvent` uma vez por região por tick | C | A4 8394, 8461 |
| 78 | Tempo para o cliente espalhado por jogador; `SHARED_RANDOM` por thread | C | A4 8696; A4 14659-14660 |
| 79 | Pacote fora de thread de tick: jogo -> fila do jogador e avisa última região; login/configuração -> fila da global | C | A4 7944-7960; B2 3080-3106 |
| 80 | Região processa pacotes no começo do tick e entre ticks, conferindo `isTickThreadFor(player)` | C | A4 8399-8402; A4 5953-5987 |
| 81 | Nova conexão entra na lista da global; handshake/login/configuração na global | C (conexão) / NV (handshake) | `addConnection` A4 13285; global tica conexões A4 1869-1905. O pacote de **handshake** não passa por `ensureRunningOnSameThread` (o código só trata game, configuração e login, A4 7947-7954); em que thread ele roda não está no clone: NV |
| 82 | Entrada no mundo: `switchToMain`, `scheduleTickingState(ENTITY_TICKING)`, ticket `PLAYER_SPAWN`, `spawnPlayer` na fila da região | C | A4 13238-13262 |
| 83 | Login duplicado com trava própria; A5 limita logins pendentes | C | A4 13952-13980; A5 linhas 29-39 (`maxJoinsPerTick`) |
| 84 | Logout pela região dona; `handleDisconnection` com `compareAndSet`; `disconnectSafely` para evitar deadlock | C | A4 3394-3406, 3363-3373; A4 7834; A4 13209 |
| 85 | README: ~4 threads de Netty por 200-300 jogadores | C (documentação) | README linhas 52-55 |

---

## 2. O que faltou (fatos que o leitor não viu)

1. **O ticket da fila é processado na hora.** `ensureTicketAdded` adiciona o ticket e chama `processTicketUpdates(coord)` no mesmo instante, antes de procurar a fila (A4 2155-2162). É isso que permite a `queue()` esperar que a região já exista logo depois. Para copiar o "correio com ticket" em 1.7.10, este passo síncrono é a parte essencial.

2. **Região esperando junção não tica.** Quando uma região precisa juntar com outra que está ticando, ela fica no estado TRANSIENT e sai do agendador (`onRegionInactive`) até a junção terminar (A4 4175-4200; estados em A4 4437). Por isso a "margem" vale para toda região **que está ticando**.

3. **A fila global não tem limite por tick.** Na global, `drainTasks` é `while (runOneTask() || runOnePacketTask())` (A4 1756-1758): uma tarefa global que se repõe na fila prende a global para sempre. Nas regiões há limite (item 23 da tabela).

4. **O Folia já força regiões a se juntarem com tickets.** Em `fixPassengerDesync` (A12 84-113), antes de teleportar, o código põe tickets `MAX_TICKET_LEVEL` em todos os chunks de um caminho em L entre o veículo e cada passageiro, para "force connect the chunks". Como cada chunk holder vira seção no regionizador, isso faz as regiões dos dois pontos virarem uma só. É exatamente a técnica de que precisaríamos para "juntar regiões ligadas por cabo" (item 4 da seção de opinião do relatório). Isto é leitura do código; o efeito de junção depende do elo "ticket cria holder", que é do Moonrise (não verificado).

5. **Teleporte entre regiões troca o objeto da entidade.** Para entidade que não é jogador, mesmo no mesmo mundo, `transformForAsyncTeleport` remove a original e cria outra com `getType().create(...)` + `restoreFrom` (A4 15175-15184). Quem guardou referência à entidade antiga fica com um objeto morto. Em 1.7.10 muitos mods guardam `Entity` em campo (alvo, dono, montaria); isso precisa entrar na lista de riscos.

6. **Muitos comandos viraram "dispara e esquece".** Não só `/time` e `/weather`: `/fill` carrega os chunks de forma assíncrona com folga de 32 blocos ("physics may spill into neighbour chunks") e devolve 0 (A4 9415-9465); `/setblock` usa `queueOrExecuteTickTask` e devolve 1 (A4 9968-10013); `/give` roda pelo agendador da entidade (A4 9580-9631). O valor de retorno do comando deixa de refletir o resultado. Há 26 arquivos de comando alterados em A4 (lista em `folia_0004_files.txt`, linhas 39-65).

7. **Outras coisas desligadas que o relatório não listou:** `createWorld` e `unloadWorld` da API lançam "not implemented properly yet" (B2 892 e 900); `PlayerPreLoginEvent` síncrono desligado (A4 13724); baú bônus de spawn desligado (A4 8136); `tickRateManager.tick()` comentado no tick de região (A4 8415-8416, junto com o `/tick` não registrado); `ServerChunkCache.getChunkFuture` lança (A4 11045); `adjustSpawnLocation` lança (A4 12540).

8. **Contador de tick global sumiu.** `MinecraftServer.currentTick` deixou de ser incrementado (A4 8285, `//++MinecraftServer.currentTick`); os usos viraram `RegionizedServer.getCurrentTick()`, que é o tick **da região** e lança `IllegalStateException("No currently ticking region")` fora de região (exceto na parada) (A4 1689-1702). Para nós: mods 1.7.10 que leem um contador global de ticks teriam valores diferentes por região.

9. **O agendador é configurável.** `scheduler = EDF` por padrão, alternativa `WORK_STEALING` (B2 438; A4 5220-5250). O relatório cita EDF só como termo.

10. **Carga síncrona de chunk por thread assíncrona não é barrada em `syncLoad`.** Ver correção 5 abaixo (lista "corrigidas").

11. **Tempo de jogo avança por `tickCount`, não por 1.** `tickTime` faz `setGameTime(getGameTime() + tickCount)` (A4 1981-1985). Não conferi se `tickCount` pode ser maior que 1 (recuperação de ticks atrasados): não verificado.

12. **Block entities ticam em listas por região.** `blockEntityTickers` e `pendingBlockEntityTickers` ficam em `RegionizedWorldData` e são repartidas na divisão (A4 3236-3237, 3111-3134). É o equivalente do nosso `world.loadedTileEntityList`: no Folia ele deixou de ser uma lista do mundo.

13. **`MinecraftServer.stopServer()` pode ser chamado de qualquer thread** e só dispara a `RegionShutdownThread` (A4 8194-8203); salvar jogadores saiu do `stopServer` e foi para a thread de parada (A4 8225-8226).

---

## 3. Não verificado (e por quê)

- **Corpo de `TickThread.ensureTickThread(...)` e de `isTickThread()`**: são do Paper/Moonrise, fora do clone. Não dá para dizer se lança e qual exceção (o relatório já marca isso; concordo).
- **Ticket em `MAX_TICKET_LEVEL` cria chunk holder sem carregar o chunk**: regra do Moonrise, fora do clone. Há indício a favor (A4 15093-15097) e um comentário ambíguo contra (A4 13540). O resumo do relatório (item 6) não deveria afirmar isso como fato.
- **Nome exato do método do item 50** (o "não é pistão"): o hunk do patch não mostra a assinatura. Pela forma, é a varredura de blocos atravessados no movimento.
- **Thread em que roda o pacote de handshake**: não passa pelo `ensureRunningOnSameThread` alterado; o resto fica no Paper/vanilla.
- **"Portal volta à origem" na parada**: só documentação. No código, `finishTeleportations` coloca a entidade no mundo da região que está parando (A4 1166-1170); não segui de onde vem esse mundo no caso de portal.
- **`NullPointerException` em `Level.getBlockState` fora do pool**: leitura de código, não testada; não conferi sobrescritas em subclasses.
- **Se `tickCount` em `tickTime` pode passar de 1**: não segui a origem do valor no agendador.
- Nada foi compilado ou executado. Tudo acima é leitura do commit `eca40f91`.

## 4. Comentário sobre a seção de opinião do relatório (seção 4)

A seção está marcada como opinião, corretamente. Dois ajustes à luz do que achei:
- Item 4 ("juntar regiões ligadas por cabo"): o Folia **já tem** o mecanismo (tickets em caminho, A12). Vale citar como base concreta, não só como ideia.
- Item 5 ("exceção derruba o servidor"): o Folia não derruba por tudo; pacote com erro expulsa o jogador, tarefa de plugin com erro só registra. O modelo "conter o erro no menor escopo possível" já existe ali e pode ser seguido.
