# Analise do Stall Report de 2026-11-09 13:36:21 (-0300)

Fonte: `<trabalho>/anexos/stall/stall.txt` (5167 linhas), gerado pelo mod "sampler 1.60.1".
Termos explicados na primeira vez. "Stall" = o servidor ficou pelo menos 30 s sem terminar um tick
(tick = passo de 50 ms do jogo). "Thread dump" = foto de todas as threads (linhas de execucao) naquele instante.

Cabecalho (stall.txt:1-3): 411 threads, parado por no minimo 30000 ms, heap 6459 de 15032 MB usados.
Um dump so e uma foto. Ele prova onde a thread estava naquele instante, nao prova que ficou ali os 30 s inteiros
(ver "Limites" no fim).

## 1. O que a "Server thread" estava fazendo

Thread `Server thread` #43, RUNNABLE (estava executando, nao esperando), stall.txt:5-27. De baixo para cima:

| Frame (resumido) | O que significa |
|---|---|
| `MinecraftServer.run` / `func_71217_p` / `DedicatedServer.func_71190_q` / `MinecraftServer.func_71190_q` (:1023) | Laco principal; a funcao que faz "um tick". |
| `NetworkSystem.func_151269_c` (:181), com `- locked Collections$SynchronizedRandomAccessList@3013fac6` | Fase de rede do tick: percorre as conexoes e processa os pacotes que chegaram. Segura a trava da lista de conexoes enquanto faz isso. |
| `NetworkManager.func_74428_b` (:258) e `C08PacketPlayerBlockPlacement.func_148833_a` | Entrega o pacote C08 (jogador clicou com botao direito num bloco) ao tratador. |
| `NetHandlerPlayServer.func_147346_a` (:870) e `ItemInWorldManager.func_73078_a` (:425) | Logica vanilla de "usar item/bloco". |
| `ForgeEventFactory.onPlayerBukkitInteract` (:318) e `EventBus.post` | Ponte Crucible: dispara o evento Forge `PlayerInteractEvent` para todos os mods que escutam. |
| `ASMEventHandler_1394_AbilityHelper_clickBlock_PlayerInteractEvent` | O ouvinte do ChromatiCraft. |
| `AbilityHelper.clickBlock` (:327), `addPlayerClick` (:304), `playerMakeBox` (:320) | Habilidade de jogador do ChromatiCraft: o clique vira o canto de uma caixa. |
| `AbilityCalls.shiftArea` (:553) | Habilidade "Shift" (World Shift): copia o conteudo de uma caixa de blocos para outro lugar. |
| `FilledBlockArray.setBlock` (:88, :115), `HashMap.put`, `HashMap$TreeNode.putTreeVal` | Guarda cada bloco da caixa numa tabela (mapa) do DragonAPI. Topo da pilha: inserindo no mapa. |

Em palavras: um unico clique direito de um jogador, tratado no meio da fase de rede do tick, fez o ChromatiCraft
varrer uma caixa de blocos, bloco por bloco, e copiar cada um para um mapa em memoria. Tudo na thread do servidor.

### Que tipo de operacao e, e por que pode durar 30 s
Evidencia: `javap -p -c` sobre `ChromatiCraft 1.7.10 V33a.jar` e `DragonAPI 1.7.10 V33b-ForbiddenFix1.jar`
(extraidos em `<trabalho>/estudo/cc-x`, nada foi executado).

- `AbilityHelper.addPlayerClick` guarda o 1o clique do jogador (WorldLocation) em `playerClicks`. No 2o clique cria
  `new BlockBox(clique1, clique2)` e chama `playerMakeBox`, que chama `AbilityCalls.shiftArea` com a direcao e a
  distancia da habilidade (ScaledDirection).
- `shiftArea` (bytecode, offsets 26 a 272) tem tres lacos aninhados, um por eixo, cada um indo de 0 ate
  `getSizeX/Y/Z()` inclusive (a comparacao e `if_icmpgt`). Logo faz (sx+1)*(sy+1)*(sz+1) iteracoes.
- Em cada iteracao: `getBlock`, `getBlockMetadata`, checagens de bloco inquebravel, e `FilledBlockArray.setBlock`.
  Esse `setBlock` faz (a) `addBlockCoordinate`: testa `hasBlock`, `isBlockInside`, cria um `Coordinate`, insere numa
  `ArrayList` e num `HashSet`, atualiza limites; (b) um `HashMap.put(new Coordinate, BlockKey)`. Ou seja, 3 estruturas e
  varios objetos novos por bloco.
- O custo em elementos do jogador (`playerHas`) so e calculado DEPOIS dos lacos (offsets 276 a 340). Quem nao tem
  elementos suficientes paga a varredura inteira e so entao ouve o som de erro. Depois ainda ha `playerCanBreakAt`
  duas vezes (consulta de protecao, possivelmente passando por plugins; nao verificado), `clearArea` e `place`.
- `Coordinate.hashCode` = `coordHash(x,y,z)` = polinomio base 31 (`((31+x)*31+y)*31+z`), offsets em
  `Coordinate.class`. Esse hash colide muito em caixas grandes (ex.: (x,y+1,z-31) tem o mesmo hash de (x,y,z)).
  O `TreeNode.putTreeVal` no topo da pilha confirma que o mapa ja tinha baldes convertidos em arvore, sinal de colisao
  pesada. `Coordinate` implementa `Comparable`, entao o JDK mantem a arvore balanceada: custo O(log n) por insercao,
  lento mas nao quadratico (nao li o `compareTo`, nao verificado).

### Tem limite?
- No codigo lido (`addPlayerClick`, `playerMakeBox`, `shiftArea`) nao ha teto de tamanho da caixa antes dos lacos.
  A unica trava e o custo em elementos, aplicada depois. As constantes `bipush`/`sipush` do metodo nao mostram tamanho maximo.
- Opcoes de config relacionadas em `ChromaOptions`: so `SHIFTTILES` ("World Shift Can Move TileEntities", booleana) e
  `COPYSIZE` (outra habilidade). Nao achei opcao de tamanho do Shift (busca por strings no `ChromaOptions.class`).
- O limite pratico e quantos blocos cabem entre dois cliques que o jogador consegue dar (um canto, anda ou voa, o
  outro canto) e a memoria. Nao sei se `BlockBox` limita Y a 0-255 (nao verificado). Os jars `chromatifixes-1.14.5` e
  os `Akashic-Chromati*` nao tocam nessa habilidade (listagem com `unzip -l`; so ha `ShiftMazeGeneratorMixin`, que e outra coisa).
- Conclusao: existe limite teorico (memoria e ints), mas nao um limite de tempo. Uma caixa de, por exemplo, 1000x255x1000
  teria cerca de 255 milhoes de iteracoes, o que explica minutos e provavel falta de memoria. Esse numero e um exemplo
  de aritmetica, nao uma medida do incidente: o tamanho real da caixa NAO esta no dump (nao verificado).

## 2. Censo de threads (411)

Contagem feita por script sobre o stall.txt (nomes de thread; "dono" inferido pelo nome e pelos frames).

| Dono | Qtd | Estados | Observacao |
|---|---|---|---|
| Distant Horizons (DH-*) | 208 | 200 TIMED_WAITING, 8 WAITING | 64 dimensoes x 3 threads (Request Ticker, Module Ticker, Update Propagate Queue) = 192, mais 4 Chunk Update Queue, 2 LOD Builder, 2 Update Propagator e 8 de servico. Todas ociosas. |
| ForkJoinPool-N-worker (dono nao identificado, so frames do JDK) | 25 | 23 WAITING, 2 TIMED | Inclui 1 commonPool. Ociosas. |
| Discord (DiscordSRV, JDA, OkHttp, Okio) | 19 | 12 TIMED, 5 WAIT, 2 RUN | RUNNABLE = leitura de socket. |
| Akashic (Voice, FM, Web/MediaPort, Mirai) | 16 | 8 TIMED, 6 RUN, 2 WAIT | RUNNABLE = leitura de socket (`Net.poll`). |
| JVM (Reference Handler, Finalizer, Signal Dispatcher, Common-Cleaner, Read/Write-Poller, process reaper, etc.) | 13 | 7 RUN, 4 WAIT, 2 TIMED | RUNNABLE = esperas nativas. |
| LuckPerms | 12 | 9 WAIT, 2 TIMED, 1 RUN | O RUNNABLE le socket (banco). |
| pool-N-thread-N (dono nao identificado) | 12 | 9 WAIT, 3 TIMED | |
| Thread-N (geckolib 3, omniconfig, Skript, ChestShop, CommandNPC, Armourer's) | 10 | 6 WAIT, 4 TIMED | |
| Timer-N | 9 | 8 TIMED, 1 WAIT | |
| Netty IO #0 a #7 (rede do Minecraft) | 8 | 7 RUN (epoll ocioso), 1 BLOCKED | Ver abaixo. |
| TAB | 8 | 5 WAIT, 3 TIMED | |
| Evernifecore (schedulers) | 8 | 7 WAIT, 1 TIMED | |
| IC2 (`icN-poolthread`) | 8 | 8 WAIT | |
| bStats e Spigot Metrics | 7 | 7 TIMED | |
| LiteBans, MVStore, mysql | 6 | 4 TIMED, 2 WAIT | |
| FileSystemWatchService | 5 | 5 RUN | Esperas nativas (`LinuxWatchService.poll`). |
| GriefPreventionPlus (`gpp-asyncsave`) | 5 | 5 WAIT | |
| sampler (o proprio relatorio) | 3 | 2 RUN, 1 TIMED | O "Sampler watchdog" esta em `dumpAllThreads`, ou seja, tirando esta foto. |
| spark | 3 | 2 TIMED, 1 WAIT | |
| Spigot/Bukkit (Spigot Watchdog, 2 Craft Scheduler) | 3 | 3 TIMED | O watchdog dorme (`Thread.sleep`, WatchdogThread.java:206). |
| Skript | 2 | 2 WAIT | |
| Server thread | 1 | RUNNABLE | A unica que trabalhava. |
| Outras com 1 thread cada (21): AE Compass, Armourer's Skin, BuildCraft packet sender, CS-API, Chunk I/O Executor, ClearLag, File IO Thread, Hodgepodge FmlFileAsync, Java2D Disposer, OpenComputers SaveHandler, ProtocolLib StructureCompiler, SaveRelief IO, Server Infinisleeper, Server console handler, Votifier epoll boss, World Simulation Thread (CoroUtil), CastleOfStone, IronDomeListener, LimitWarnThread, SaveConfigThread | 21 | 1 RUN File IO, 1 RUN console, 1 RUN Votifier, resto WAIT/TIMED | |
| Total | 411 | 270 TIMED_WAITING, 106 WAITING, 34 RUNNABLE, 1 BLOCKED | Confere com a soma. |

Estados: RUNNABLE = executando ou em chamada nativa; WAITING/TIMED_WAITING = parada esperando (com ou sem relogio);
BLOCKED = parada esperando uma trava (`synchronized`) que outra thread segura.

### BLOCKED e o dono da trava
Uma unica thread BLOCKED: `Netty IO #3` #81 (stall.txt:3673-3690), esperando
`Collections$SynchronizedRandomAccessList@3013fac6`, em
`com.comphenix.protocol.injector.netty.NettyProtocolInjector$1.initChannel` (:130) dentro de `ChannelInitializer.channelRegistered`.
Ou seja, o ProtocolLib (plugin) tentou registrar uma conexao NOVA e precisa da mesma lista que a Server thread segura
(`NetworkSystem.func_151269_c` faz `- locked` no mesmo identificador `@3013fac6`, stall.txt:26). Dono da trava: a Server thread.
Nenhuma outra thread aparece com `- locked` ou `waiting to lock` sobre ela (busca por `3013fac6`: so 2 ocorrencias, linhas 26 e 3674).

### Alguma outra coisa estava rodando de verdade?
Quase nada. Das 34 RUNNABLE, 33 estao em espera nativa de I/O (poll/epoll/socket/leitura de console/inotify) ou sao
parte do proprio sampler/JVM. Duas observacoes:
- `File IO Thread` #1119 (prio=1) esta RUNNABLE em `Thread.yield` dentro de
  `ThreadedFileIOBase.redirect$bao000$hodgepodge_2$noSleepForTheWicked` (stall.txt:3254-3260): o mixin do Hodgepodge troca a
  espera por `yield`, o que gasta CPU em laco. Quanto de CPU nao esta no dump (nao verificado).
- Nenhuma thread DH estava trabalhando (200 TIMED_WAITING e 8 WAITING), logo Distant Horizons nao e culpado deste stall.
Nao houve deadlock: nenhum ciclo de travas. A Server thread nao esperava ninguem, estava computando.

## 3. Travas envolvendo a Server thread

| Entrada | Quem | Onde |
|---|---|---|
| `- locked Collections$SynchronizedRandomAccessList@3013fac6` | Server thread | `NetworkSystem.func_151269_c` (:181), stall.txt:26 |
| `waiting to lock`/BLOCKED nessa mesma trava | `Netty IO #3` (ProtocolLib `initChannel`) | stall.txt:3674 |

Leitura: a Server thread processa os pacotes de todos os jogadores DENTRO do `synchronized` da lista de conexoes. Enquanto um
pacote demora, ninguem consegue registrar conexao nova nessa lista. Consequencias provaveis (inferencia, nao ha prova no dump):
logins novos ficam pendurados; e o evento loop `Netty IO #3` inteiro fica preso, entao jogadores cujos canais pertencem a ele
tambem nao leem/escrevem ate a trava soltar. Os `synchronizers` que aparecem em varias threads (`N locked synchronizers`) sao
travas `java.util.concurrent` de pools, nao ligadas a Server thread.

## 4. O que este incidente ensina ao projeto

Referencia: `docs/02-arquitetura.md` (secao 3, degraus A, B, C; secao 4 componentes 5 e 7; secao 5).

(a) Com uma thread so: um clique de um jogador congelou todos por 30 s ou mais, inclusive logins (secao 1 do documento: "o tick
inteiro gasta 150 ms e todo mundo laga"). Aqui foram >= 30000 ms, 600 vezes um tick normal. TPS, chat, redstone, mobs, tudo parado.

(b) Degrau A (orcamento de tick por regiao, ainda 1 thread): continuaria congelando todos.
O documento define o orcamento como "regiao que estoura passa a ticar 1 vez a cada 2, 3, 4 ticks" e mede o tempo "de tick" da regiao
(tiles, entidades, blocos). O orcamento age ENTRE ticks de regiao; nao interrompe uma chamada que ja entrou e nao devolve o controle.
Alem disso, este trabalho nao e tick de regiao: e tratamento de pacote na fase de rede, e o documento lista "rede" como fase
global em serie. Uma unica chamada de 30 s dentro de um `invoke` de evento nao e preemptada por nenhum orcamento. Pior caso
identico ao de hoje. O Degrau A so ajuda se o alerta (secao 3, "Ja da o alerta ao admin") apontar o culpado depois.

(c) Degrau B (fase paralela sincronizada, pacotes na fase serial): continuaria congelando todos.
Documento: "[fase global, em serie] eventos de inicio de tick, agendador de plugins, rede" e "a fase so termina quando todas
acabam". O C08 e tratado na fase global em serie, antes da fase paralela. Se ele leva 30 s, a fase paralela nem comeca;
as outras regioes nao tickam. As threads de regiao ficariam ociosas esperando a serial terminar. O Degrau B tambem nao ajudaria
porque o gargalo nao e de mundo (o documento admite na secao 6, risco 2, que a parte nao-mundo "nao paraleliza").

(d) Degrau C (cada regiao com relogio proprio, pacote tratado na regiao do jogador): isolaria.
O documento diz: "Cada regiao tem o proprio relogio de tick" e, na secao 4 item 5, que efeitos de jogador voltam como tarefa para a
regiao do jogador. Com o pacote tratado na thread da regiao desse jogador, os 30 s consomem so aquela thread: os outros jogadores
em outras regioes seguem a 20 TPS. Ressalvas, todas do proprio documento: (1) so vale para dimensoes e mods "liberados";
(2) vizinhos na mesma regiao (mesma base) ainda congelam; (3) o handler `PlayerInteractEvent` do ChromatiCraft le e escreve blocos
numa caixa arbitraria que pode cruzar varias regioes: o desenho preve junta-las ou rotear, e a escolha padrao e "juntar as regioes",
o que na pratica pode fundir regioes grandes e ressuscitar o congelamento global. (4) A trava `NetworkSystem` precisa deixar de
ser tomada durante o processamento de pacotes (hoje bloqueia login, como visto). (5) Em Folia, 30 s numa regiao tambem dispararia o watchdog;
seria preciso um vigia por regiao (componente 9 do documento).

(e) Mitigacoes baratas, sem threads:
1. Teto de tamanho/volume do Shift. Nao ha opcao no ChromatiCraft (item 1, "Tem limite?"). Opcoes: (i) mixin early/late em
   `AbilityCalls.shiftArea` que retorna `false` se `(sx+1)*(sy+1)*(sz+1)` passar de um limite (ex.: 64x64x64, valor a calibrar
   pelo admin); isso e o mesmo mecanismo ja em producao nos mods `Akashic-*` (doc 02, secao 2, ultima linha); (ii) enquanto isso,
   desabilitar a habilidade para jogadores comuns por permissao/config do plugin de grupos (nao verificado qual caminho existe).
2. Orcamento de tempo dentro do laco: no mesmo mixin, checar `System.nanoTime()` a cada N iteracoes e abortar com erro ao jogador
   depois de, digamos, 50 ms; limpar os arrays parciais. Barato, e o que falta ao Degrau A para este caso.
3. Vigia que nomeia o culpado: o `Spigot Watchdog Thread` ja existe, dorme em `Thread.sleep` (:206) e a `Sampler watchdog thread`
   ja mede. Um vigia proprio pode, ao passar de ~2 s no tick, ler a pilha da Server thread, extrair o jogador do pacote em
   processamento (campo do `NetHandlerPlayServer` atual) e logar nome, dimensao, posicao e a classe do mod (`Reika.ChromatiCraft...`).
   Hoje o dump nao diz QUEM clicou nem o tamanho da caixa: essa foi a maior lacuna de diagnostico deste incidente.
4. Medir tempo por evento Forge (por ouvinte) na fase de rede, para alertar "mod X, 800 ms" antes de virar 30 s (item (10) do doc, Medicao).
5. Nao segurar `synchronized` da lista de conexoes durante `func_74428_b`: copiar a lista e processar fora da trava evita bloquear
   o Netty IO e logins. Exige mixin na base; risco baixo, mas nao testado (nao verificado).

## 5. Quantas threads, e o que isso significa em 7 vCores

- O dump lista 411 threads Java (cabecalho). Threads nativas da JVM (coletor G1, compilador JIT, etc.) nao aparecem nessa
  contagem, logo o total real do processo e maior (nao verificado; `ps -L` no host confirmaria).
- Quase todas dormem: 270 TIMED_WAITING e 106 WAITING, so 34 RUNNABLE, e dessas so uma computava. Threads dormindo
  nao disputam CPU; o custo delas e memoria de pilha, nao nucleo.
- Metade (208) e do Distant Horizons: 64 dimensoes x 3 threads de fila. Se o projeto criar "uma thread por regiao" sem
  limite, o numero de threads RUNNABLE ao mesmo tempo (nao o total) e que precisa caber em 7 nucleos, ja dividindo com:
  Server thread (global), 8 Netty IO, o G1 (paralelo e concorrente, conforme a flag `-XX:+UseG1GC` do script do usuario),
  o JIT, os trabalhadores de banco/Discord/Votifier e File IO (que gira em `yield`).
- Regra pratica a testar (hipotese, nao medicao): pool de threads de regiao com no maximo 4 a 5 trabalhadores
  (7 nucleos menos global, rede e GC), e nao uma thread por regiao. Regioes em fila, nao em thread dedicada.
  O documento (secao 6, risco 6) ja avisa que a maquina de teste tem 4 nucleos, entao o ganho em 7 so se mede em producao.
- A heap usava 6459 de 15032 MB; este stall nao parece ter sido de memoria (uma unica foto; GC nao aparece em RUNNABLE nas
  threads Java, mas threads de GC nao entram no dump).

## Limites e itens nao verificados
- Um unico dump: nao se sabe se os 30 s foram uma unica chamada ou varias longas seguidas. O frame no topo bate com o laco
  do `shiftArea`, mas nao ha segunda amostra.
- Tamanho da caixa, jogador, dimensao e coordenada: nao constam no dump.
- Nao li `Coordinate.compareTo` nem o construtor de `BlockBox`, nem testei limites reais de Y.
- Config do Spigot (`timeout-time` do watchdog) nao verificada: o servidor nao foi derrubado, entao ou o limite e maior que o
  stall ou o watchdog e so aviso (nao verificado; o enunciado proibia abrir `config/` e `plugins/` de producao).
- Dono real dos 25 ForkJoinPool, dos 12 `pool-N-thread-N` e dos 9 `Timer-N`: so frames do JDK, nao identificado.
- Impacto na CPU do `File IO Thread` (yield) e total de threads nativas: nao medidos.
