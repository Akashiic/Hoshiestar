# Tarefa 0.8, área "chunks": conferência cética de `chunks.md`

Conferente: outro agente, que não escreveu o relatório. Só leitura: nada foi compilado nem executado, e o
arquivo do mapeador não foi alterado. Abri cada `arquivo:linha` citado abaixo no código-fonte.

Prefixos (os mesmos do relatório conferido):
- `M/` = `.../scratchpad/build/Crucible/eclipse/cauldron/src/main/java/` (Minecraft + Forge com os patches do Crucible, nomes MCP)
- `C/` = `.../scratchpad/build/Crucible/src/main/java/` (CraftBukkit, Thermos, Crucible)
- Abreviações: `CPS` ChunkProviderServer, `CBHM` thermos ChunkBlockHashMap, `VCHM` VanillaChunkHashMap, `ACL` AnvilChunkLoader,
  `AE` Forge AsynchronousExecutor, `CIOP` ChunkIOProvider, `PM` PlayerManager, `SCM` ServerConfigurationManager,
  `ET` EntityTracker, `ETE` EntityTrackerEntry, `EPMP` EntityPlayerMP, `NHPS` NetHandlerPlayServer, `FCM` ForgeChunkManager,
  `EBS` ExtendedBlockStorage, `WS` WorldServer.
- B = fase paralela sincronizada (Degrau B). C = regiões independentes (Degrau C).

Termos usados:
- **condição de corrida**: duas threads mexem no mesmo dado ao mesmo tempo, e o resultado depende de quem chega primeiro.
- **leitura que escreve**: um método que parece só ler, mas grava em campo compartilhado (cache, conversão preguiçosa).
- **trava (lock)**: mecanismo que deixa só uma thread por vez entrar num trecho.
- **inversão de ordem de travas**: a thread 1 segura a trava A e pede a B, enquanto a thread 2 segura a B e pede a A.
  As duas ficam paradas para sempre (deadlock).
- **barreira de memória**: garantia de que o que uma thread gravou fica visível para outra.

## 1. Resumo

| Resultado | Quantidade |
|---|---|
| Itens conferidos | 42 |
| Confirmados | 28 |
| Corrigidos (linha, descrição, modo de falha, gravidade ou tratamento errado ou incompleto) | 14 |
| Refutados | 0 |
| Itens novos (o mapeador não viu) | 9 (N01 a N09) |

O relatório é sólido. Todas as estruturas citadas existem e são tocadas na hora do tick. As linhas batem, com
desvios de 1 a 7 linhas em alguns pontos. Nenhum item caiu.

O que mais muda o plano:
1. **N01**: carregar um chunk que **já foi gerado** também roda os geradores de estrutura (Random compartilhado, `HashMap` de
   estruturas, `IntCache`, `BiomeCache`). Então pré-gerar o mapa **não** tira o estado de geração do caminho de carga.
   O tratamento de H13 precisa mudar.
2. **N03 / H17**: os getters de `ExtendedBlockStorage` (`getSkylightArray` e afins) **gravam**. Eles convertem o array
   "trivial" em array cheio. Quem chama é o envio de chunk ao jogador, que roda na thread da região do jogador e lê
   chunks de outras regiões. Portanto até "só ler" um chunk vizinho escreve nele.
3. **H26**: o conjunto `trackedEntities` está de fato protegido pela trava do Thermos (`lock()` bloqueia de verdade).
   O defeito real está no `trackedEntityIDs`, na espera de 100 ms e no caminho reentrante. **H27/H28**: o rastreador
   também muda `trackingPlayers` e `destroyedItemsNetCache` **no spawn**, na thread da região que criou a entidade,
   e não só na fase serial.
4. **Tratamentos**: a opção (a) de H07 (devolver chunk vazio) perde itens, e a opção (b) tem risco de deadlock contra
   a trava global do Bukkit. "Nenhuma escrita no mapa na fase paralela" (H02) não combina com carga na fase paralela.
   O balde do `ChunkBlockHashMap` tem exatamente o tamanho da seção do desenho (16x16 chunks), e isso abre uma solução
   barata (seção 5).

## 2. Tabela item a item

| Item | Veredito | Nota |
|---|---|---|
| H01 cache MRU do CBHM | confirmado | `CBHM:40,105-133`. `return last1` relê o campo (`:106-107`). Há uma correção mínima mais barata que ThreadLocal (seção 5). |
| H02 mapa Koloboke e `size` | confirmado | `CBHM:12-13,135-164,166-203`; `size++` ao sobrescrever (`:138`); descarga da dimensão em `CPS:571`. O tratamento B não fecha (seção 5). |
| H03 par VCHM | **corrigido** | A janela de inconsistência é ao contrário do que foi escrito. Ver 3.1. |
| H04 `loadedChunks` | confirmado | `CPS:82,262,568,471`; `CIOP:47`; `CraftWorld.java:302`. 9 jars (tabela de `crucible-tick-e-threads.md` §4, linha 153). |
| H05 `chunksToUnload` | **corrigido** | Faltam um leitor e escritores. O modo de falha pode ser laço infinito. Ver 3.2. |
| H06 `loadingChunks` / carga dupla | confirmado | `CPS:84,224-228,263`. `CIOP.callStage2` também insere sem conferir o mapa (`CIOP:46-48`). |
| H07 carga síncrona dentro do tick | confirmado | `World:682,777-780`; `CPS:304-342,169-213,215-302`. Ver N01 e N02, que somam caminhos. |
| H08 `AE.tasks` (HashMap) | confirmado | `AE:220,242-249,266-282,338-346,199,209`. O `get()` duplo é pior do que o descrito: ver N05. |
| H09 callbacks do estágio 3 | confirmado | `AE:189-197`; `CIOP:79-81`; `PM:396-402,434-440`. Com o chunk já carregado, `CPS:207-210` também roda o callback na hora. |
| H10 `ChunkIOExecutor.tick` | confirmado | `MinecraftServer.java:917`; `AE:351-356`. |
| H11 pipeline de descarga | **corrigido** | A parte do cache dormente está desligada por padrão. Ver 3.3. |
| H12 flags de modo | **corrigido** | (c) descreve errado o efeito sobre a geração. Ver 3.4. |
| H13 estado de geração | **corrigido** | O tratamento "pré-gerar" não basta. Ver 3.5 e N01. |
| H14 `BiomeCache` | confirmado | `BiomeCache.java:21-37,44-66`; `World:242-271`. Também é atingido por toda carga, via `MapGenScatteredFeature.java:77` (N01). |
| H15 `chunkTileEntityMap` | confirmado | `Chunk:60,932-958,973-1016,1018-1031,1647-1680`. Criado em `Chunk:114`, não `:116`. |
| H16 `entityLists` (UnsafeList) | **corrigido** | Detalhes do modo de falha. Ver 3.6. |
| H17 `ExtendedBlockStorage` / `NibbleArray` | **corrigido** | Gravidade subestimada: leitores também gravam. Ver 3.7 e N03. |
| H18 mapa de altura e luz | confirmado | `Chunk:274-345,353-408,424-435,437-546,1207-1236`. Ver N04. |
| H19 `neighbors` | confirmado | O campo está em `Chunk:81`, e `set*` em `:99-105` (o relatório diz 80 e 101-107). Usado em `World:2657,4917`. |
| H20 flags de salvamento | confirmado | `Chunk:65-67,77,1155-1170`; `CPS:359,401,487`; `CIOP:45`; `PM:505-509`. Em B os valores gravados são idempotentes. |
| H21 `entityCount` (Trove) | confirmado | `Chunk:72,881,921`. Nenhum leitor em `M/` ou `C/` (grep). |
| H22 efeitos colaterais de `loadEntities` | confirmado | `ACL:51,530-548`. `BukkitEnumHelper.addInventoryType` sem `synchronized` neste build (`C/io/github/crucible/util/BukkitEnumHelper.java:55`). |
| H23 lista de pendentes do ACL | confirmado | Tudo dentro de `syncLockObject` (`ACL:63-77,107-122,233-250,258-267`). |
| H24 `ThreadedFileIOBase` | confirmado | `ThreadedFileIOBase.java:65-72,74-84`. Um incremento perdido faz `waitForFinish` girar para sempre. |
| H25 `RegionFileCache` | **corrigido** | Subestimado: também perde **salvamentos**, não só leituras. Ver 3.8. |
| H26 `EntityTracker` e trava do Thermos | **corrigido** | O que está protegido e o que não está foi descrito errado. Ver 3.9. |
| H27 `trackingPlayers` | **corrigido** | Também é alterado no spawn, em thread de região, fora da trava. Ver 3.10. |
| H28 `destroyedItemsNetCache` | **corrigido** | Falta um segundo escritor (spawn). A gravidade alta se mantém. Ver 3.10. |
| H29 `EPMP.loadedChunks` | confirmado | `EPMP:135`; `PM:183-235,430,438,479,318,327,546`. |
| H30 envio de chunk dentro do laço de entidades | **corrigido** | Linhas e alcance errados; o custo do tratamento é menor do que parece. Ver 3.11. |
| H31 mapas do `PlayerManager` | confirmado | `PM:30-35,114-127,143-153`; `WorldManager.java:48-51`. |
| H32 buffer de mudanças do `PlayerInstance` | confirmado | `PM:390-393,511-538,554-623`. |
| H33 observadores do `PlayerInstance` | confirmado | `PM:388,394-395,411-498`. Detalhe: `removePlayer` chama `getChunkFromChunkCoords` (`PM:470`) e pode **carregar** o chunk que está sendo largado. |
| H34 `playerEntityList` e logout via kick | confirmado | `NHPS:260-291,940-975`; `SCM:432-472`. Mais: `processedDisconnect` não é volátil (N08). |
| H35 troca de mundo a partir do tick | confirmado | `Entity.java:443-462,2469-2550`; `SCM:955-1042,1195-1237`; `MinecraftServer.java:1069-1085`. Mais em 3.12. |
| H36 estatísticas do jogador | confirmado | `SCM:117,142,1725-1752`; `StatisticsFile.java:34,77-92`; `StatFileWriter.java:14` é concorrente. |
| H37 envio de pacotes (ordem) | confirmado | `NetworkManager.java:157-219`; FIFO por ordem de chegada ao event loop do Netty. |
| H38 fila de recebidos | confirmado | `NetworkManager.java:62,145,221-260`. |
| H39 estado estático do `ForgeChunkManager` | **corrigido** | Linhas dos eventos erradas; o tratamento tem risco de deadlock. Ver 3.13. |
| H40 consultas AABB entre chunks | confirmado | `World:3938-3985`; `WS:913-950` (pode carregar chunk em `:923`). |
| H41 eventos Forge de chunk | confirmado | `Chunk:859,1050,1083`; `ACL:94,220`; `CIOP:44`; `EPMP:404`; `PM:481`. Eventos do FCM em `:743` e `:784`. |
| H42 API de chunk do CraftBukkit | **corrigido** | O caminho padrão não é um "segundo caminho". Ver 3.14. |

Lista "inofensivos" (§2 do relatório): três linhas estão erradas. Ver 3.15.

## 3. Correções por extenso

### 3.1 H03: a janela entre as duas faces do mapa é ao contrário

`VCHM.add` grava primeiro na face Thermos e depois na vanilla (`VCHM:55-56`). `VCHM.remove` apaga primeiro da vanilla
e depois da Thermos (`VCHM:73-77`). Nos dois casos o estado intermediário é "a face Thermos tem o chunk e a vanilla não".
Logo `chunkExists` dá **true** enquanto `getValueByKey` dá **null**. O estado descrito no relatório ("`chunkExists` false
enquanto `getValueByKey` devolve o chunk") nunca acontece. A gravidade (B baixo, C médio) e o tratamento continuam valendo.

### 3.2 H05: faltam um leitor e escritores, e há um modo de falha pior

- Leitor esquecido, na fase de ticks aleatórios: `WS:453` faz `chunksToUnload.contains` para **cada chunk ativo**, todo
  tick. A tabela de `crucible-tick-e-threads.md` §5 marca essa fase como "precisa mudança", ou seja, candidata à fase paralela.
- Escritores esquecidos: plugins. `CraftWorld.unloadChunk` e `unloadChunkRequest` (`C/.../CraftWorld.java:196-230`)
  chamam `contains` e depois `unloadChunksIfNotNearSpawn`, que faz `add`. Isso roda em handlers de plugin disparados
  dentro da região. `CPS.loadChunk` faz `remove` em **toda** chamada (`CPS:176`), mesmo com o chunk já carregado. Mods
  costumam chamar `loadChunk` "para garantir que está carregado".
- Modo de falha: `LongHashSet` usa endereçamento aberto. Os laços de busca (`LongHashSet.java:69,92,138`) só param quando
  acham uma célula `FREE`. `freeEntries--` e `elements++/--` (`:111`, `:115`, `:150`) não são atômicos. Se uma
  atualização se perde, `freeEntries` fica maior do que o real. Aí `rehash()` (`:119`) não dispara, a tabela pode encher,
  e uma busca por chave ausente **gira para sempre** (o servidor trava, não é só "chunk descarregado"). Isso é
  **plausível pelo código**, mas não foi demonstrado.
- Gravidade: B médio se mantém, desde que a fase de ticks aleatórios fique serial. Se essa fase for para a paralela,
  sobe para **alto**.

### 3.3 H11: o cache dormente está desligado por padrão

`FCM.fetchDormantChunk` (`FCM:908-927`) só devolve chunk se o cache tiver tamanho maior que 0. O padrão é 0
(`FCM:991-996`), e a cópia de teste também usa 0 (`server-test3/config/forgeChunkLoading.cfg:62`). Então "a cópia dormente
volta com lista de entidades velha" não acontece na configuração atual. Detalhe que vale se alguém ligar o cache:
`fetchDormantChunk` **não remove** a entrada do cache. Duas cargas seguidas (ou duas threads) recebem o **mesmo objeto**
`Chunk` e rodam `onChunkLoad` duas vezes, o que duplica as entidades na lista do mundo. O resto de H11 se confirma.

### 3.4 H12 (c): `fallInstantly` não gera entidade "em vizinho não gerado"

`CPS:431/456` gravam `BlockSand.fallInstantly`, que é o mesmo campo estático de `BlockFalling.java:12` (`BlockSand extends
BlockFalling`, `BlockSand.java:13`). Em `BlockFalling.java:50`, a entidade `EntityFallingBlock` só é criada se
`checkChunksExist(±32)` passar. Sem vizinhos carregados, o bloco cai na hora. O efeito real, quando outra thread zera o
flag no meio da geração, é criar entidades de bloco caindo **durante a geração**, onde os vizinhos existem. Isso puxa
spawn, rastreador e listas do mundo, algo que o jogo evita ali. Não é "entidade em vizinho não gerado". Gravidade igual.

### 3.5 H13: pré-gerar o mapa não tira o estado de geração do caminho de carga

O relatório diz que o tratamento é "manter a geração numa thread e pré-gerar". Mas todo chunk **já gerado** que carrega
chama `recreateStructures`: `CPS:364` (carga síncrona) e `CIOP:52` (carga pela fila). No overworld isso roda 4 geradores de
estrutura sobre 17x17 posições cada (`ChunkProviderGenerate.java:496-505`, `MapGenBase.java:15-33`). Eles usam:
- o `Random` compartilhado do gerador (`MapGenBase.java:11`, `setSeed` em `:19,28`);
- `MapGenStructure.structureMap` (`HashMap`, `MapGenStructure.java:23`, `put` em `:41` e `:280`) e
  `MapGenStructureData` dentro de `perWorldStorage` (`:255-285`);
- `IntCache` via `areBiomesViable` (`MapGenVillage.java:78`) e `BiomeCache` via `getBiomeGenAt` (`MapGenScatteredFeature.java:77`).

Então duas regiões do mesmo mundo carregando chunks (já gerados) ao mesmo tempo corrompem esse estado. Tratamento
correto: a carga inteira, e não só a geração, fica serializada por mundo (a mesma porta única de H07). Item completo em N01.

### 3.6 H16: detalhes do modo de falha do `UnsafeList`

- `UnsafeList.get` confere o índice (`UnsafeList.java:40-44`). Um leitor com tamanho velho recebe na maior parte das vezes
  `IndexOutOfBoundsException`, e não `null`. O `NullPointerException` em `Chunk:1110` só acontece se o leitor passar pela
  conferência antes de o `remove` zerar a célula (`:79`). Os dois desfechos derrubam o tick.
- O compartilhamento de iterador do pool (`:151-176`) só ocorre entre threads que iteram **a mesma lista** (a mesma fatia
  vertical do mesmo chunk). Continua real.
- Linhas: o preenchimento com `UnsafeList` fica em `Chunk:116-125`, não em `:114-124`.
- Gravidade igual.

### 3.7 H17: leitores também gravam

O relatório trata o risco como "dois escritores na mesma seção". Faltam três pontos.
1. **Getters que gravam**: `EBS.getBlockMSBArray/getMetadataArray/getBlocklightArray/getSkylightArray` (`EBS:309-343`)
   chamam `forceToNonTrivialArray` (`NibbleArray.java:50-60`). Esse método troca `data == null` (array "trivial", que o
   Spigot guarda como um único valor) por um array novo e depois o preenche. Quem chama: o envio de chunk ao jogador
   (`S21PacketChunkData.layoutOf/writeChunkData`, `S21PacketChunkData.java:194-300`, chamado pelo construtor de `S26` em
   `S26PacketMapChunkBulk.java:58-62`), que roda dentro de `EPMP.onUpdate` na thread da região do jogador. Também chamam
   os mods que pegam os arrays crus (26 jars). Corridas possíveis:
   - o dono chama `NibbleArray.set` no mesmo instante e também aloca (`NibbleArray.java:184-199`); um dos dois arrays some, e com ele as escritas;
   - outra thread lê `data` já não nulo mas ainda zerado, antes do `Arrays.fill`, e vê luz 0 onde deveria ser 15.
2. **Alocação preguiçosa do array MSB** (os 4 bits altos do id) em `EBS:111-114`. Duas threads alocam, uma escrita de bloco
   com id acima de 255 cai no array perdido, e o bloco vira **outro id** (id & 255).
3. **Leitura rasgada**: `getBlockByExtId` lê os 8 bits baixos e depois os 4 altos em passos separados (`EBS:60-65`). Um
   leitor de outra região (cano, cabo, IA de mob) pode ver por uma chamada um bloco que nunca existiu.

Gravidade em B: sobe de médio para **alto**, enquanto o envio de chunk (H30) rodar dentro da fase paralela. Com H30 movido
para a fase serial, volta a médio.

### 3.8 H25: além de leituras falhas, salvamentos perdidos

A `File IO Thread` grava por `RegionFileCache.getChunkOutputStream` sem `synchronized` (`RegionFileCache.java:134-138`,
chamado em `ACL:286`). O `RegionFile` é obtido e depois usado fora da trava da classe. Se outra thread despejar e fechar
esse arquivo nesse intervalo (`RegionFileCache.java:57-75`), `RegionFile.write` pega o `IOException` e só imprime
(`RegionFile.java:186,267`). A entrada já saiu da lista de pendentes (`ACL:265-266`), então **o salvamento do chunk se
perde em silêncio** (rollback no próximo carregamento). O problema já existe hoje entre a thread de tick e a `File IO
Thread`. Mais threads abrindo arquivos de região deixam isso mais provável. Gravidade em C: médio para **alto**,
porque é perda de dados.

### 3.9 H26: o que a trava do Thermos protege e o que não protege

- **`trackedEntities` está protegido.** Todo acesso passa por `lock()` (`ET:107,242-244,295,308-310,317,335,370,381`), e
  `ReentrantLock.lock()` bloqueia de verdade. A exclusão mútua do `HashSet` vale. `waitForLock` só soma latência (dorme
  100 ms enquanto **qualquer** thread segura a trava, `ET:66-77`). O relatório lista esse conjunto junto dos itens quebrados.
- **Não protegido**: `trackedEntityIDs` (`IntHashMap`). Ele é escrito em `addKey` dentro de `synchronized(this)` (`ET:222,246`)
  e em `removeObject` sem trava nenhuma (`ET:304`), e é lido sem trava (`ET:350,360,402`). Modo de falha concreto: no
  crescimento, `IntHashMap.copyTo` **zera o balde antigo antes de mover** (`IntHashMap.java:99-121`, `aentry[j] = null`).
  Uma busca concorrente devolve `null`, e o pacote de animação ou status é descartado, ou a entidade não é encontrada
  para remoção (fantasma no cliente).
- **Caminho reentrante**: depois de cerca de 10 s, `unlock()` (`ET:73`) **solta a posse da própria thread** (não "rouba").
  Ela volta a pegar a trava em `lock()`. O `unlock()` de fora pode então ver a trava presa por **outra** thread
  (`isLocked()` true) e lançar `IllegalMonitorStateException`, que não é capturado nos chamadores. O tick cai.
- `SCM:725` é `respawnPlayer` (morte ou saída do End), não logout. O logout passa por `SCM.disconnect`, `World.removeEntity`,
  `onEntityRemoved`, `WorldManager.onEntityDestroy` e `ET.removeEntityFromAllTrackingPlayers`.
- Gravidade (B alto) e tratamento: iguais.

### 3.10 H27 e H28: o rastreador também escreve no spawn, fora da fase serial

Todo `addEntityToTracker` termina em `sendEventsToPlayers(world.playerEntities)` (`ET:247`, `ETE:551-557`), que chama
`tryStartWachingThis` para cada jogador do mundo. Isso roda na thread da região que **criou** a entidade (item dropado,
flecha, mob de spawner), dentro de `synchronized(this)` mas **fora** de `trackerLock`, e faz duas escritas:
- `p.destroyedItemsNetCache.remove(id)` (`ETE:430`) na lista `LinkedList` de um jogador que pode estar em outra região
  e drenar essa lista naquele momento (`EPMP:326-339`). Isso é um segundo escritor de H28 vindo de região, além da morte.
  Acontece a cada spawn dentro do alcance de rastreamento de um jogador de outra região.
- `trackingPlayers.add` (`ETE:433`) e o envio de pacotes de spawn para esse jogador.

H27 diz "as modificações ficam quase todas na fase serial". Errado: também ocorrem no spawn (`ET:247`), no login do
jogador (`ET:113`) e no envio de chunk (`ET:386`, a partir de `EPMP:403`). Gravidade de H27 em B: médio se mantém, porque o
`trackingPlayers` de uma entidade recém-criada quase nunca é iterado por outra thread. H28: alto, confirmado. O
tratamento "adiar a remoção do rastreador" **só cobre H28 se a adição também for adiada**.

### 3.11 H30: linhas, alcance e custo

- Linhas reais: o bloco de envio é `EPMP:342-407`. `S26PacketMapChunkBulk` em `:392`, pacotes de descrição de TE em
  `:395-398` (`func_147097_b`), `func_85172_a` em `:403`, `ChunkWatchEvent.Watch` em `:404`. O relatório cita 388-391, 396 e 397.
- Alcance: não percorre "até a distância de visão". Para depois de `crucible_optimization_maxChunkSendsPerTick` chunks
  enviáveis (`EPMP:347,353`; padrão 20 em `C/io/github/crucible/CrucibleConfigs.java:154`). A fila só tem chunks
  dentro da distância de visão, então o efeito entre regiões continua real.
- Custo do tratamento: a compressão do `S26` roda em `writePacketData` na thread do Netty (`S26PacketMapChunkBulk.java:205`
  chama `compress()`), e não no construtor. Levar o bloco para a fase serial custa só a cópia dos arrays, os pacotes de TE
  e o rastreador. É mais barato do que parece. Medir antes de decidir (não medido).
- Soma-se N03/3.7: o construtor do pacote **grava** nos `NibbleArray` do chunk estrangeiro.

### 3.12 H35: mais dois caminhos

- Plugins: `CraftPlayer.teleport` para outro mundo chama `ThermiteTeleportationHandler.transferPlayerToDimension`
  (`C/.../entity/CraftPlayer.java:578-585`; `C/thermos/thermite/ThermiteTeleportationHandler.java:42-69`), que mexe no
  `PlayerManager` dos dois mundos (`:69`). Isso vale para qualquer handler de plugin disparado dentro da região.
- `SCM.repositionEntity` chama `worldserver.updateEntityWithOptionalForce(entity,false)` **no mundo de origem, já com as
  coordenadas de destino** (`SCM:1201-1205`). Isso põe a entidade num chunk do mundo de origem naquelas coordenadas
  (`World:2741-2756`), que pode ser de outra região. É comportamento antigo, mas cruza região.

### 3.13 H39: linhas e risco do tratamento

O evento de `forceChunk` é postado em `FCM:743`, e não `:739` (`:739` é o `FMLLog.severe`). O de `unforceChunk` fica em
`FCM:784`, e não `:783`. O resto confere (`FCM:745-746`, `:785-788`, `:351-356`, `:363-368`). Sobre o tratamento, ver 5.7.

### 3.14 H42: o caminho padrão do CraftWorld é o normal

`CraftWorld.loadChunk(x,z)` usa `generate=true` e cai em `CPS.loadChunk` (`CraftWorld.java:176-177,280-283`), o caminho
normal. Só `loadChunk(x,z,false)` (`:285-296`) e `regenerateChunk` (`:232-250`) passam por `safeLoadChunk` ou pelo gerador
mais `chunkLoadPostProcess` (`:299-321`), sem `loadingChunks`, sem `ChunkIOExecutor`, **sem atualizar o bitmask de
vizinhos** e sem `ChunkLoadEvent`. A conclusão "toda via de carga precisa da mesma porta" continua certa. A descrição
"é um segundo caminho" só vale para essas duas variantes. Somar como escritores de H05 os pedidos de descarga de plugin
(3.2).

### 3.15 Lista de "inofensivos" (§2 do relatório): três linhas erradas

- "`NetHandlerPlayServer` movement fields: only network phase": **errado**. A pérola do End, ao bater
  (`EntityEnderPearl.java:79`), chama `NHPS.teleport` (`NHPS:616-660`) do jogador que a jogou. Isso grava `lastPosX/Y/Z`,
  `justTeleported` e a posição do jogador a partir da thread da região da pérola. Ver N07.
- "`ETE` position cache fields: written only by `sendLocationToAllClients` in the serial tracker phase": **errado**.
  `tryStartWachingThis` grava `motionX/Y/Z` (`ETE:460-462`), e `func_151260_c` grava `lastHeadMotion` (`ETE:712`). Os dois
  rodam no spawn (`ET:247`) e no envio de chunk (`ET:386`), em threads de região. O efeito é só visual (velocidade velha).
- "`EPMP.managedPosX/Z`: written by PlayerManager (serial phases)": vale só se os teleportes de H35 forem adiados.
  `PM.addPlayer` grava esses campos (`PM:159-160`) e é chamado por `SCM.func_72375_a` (`SCM:306`) a partir de
  teleportes de mod ou plugin.

## 4. Itens novos (o mapeador não viu)

Método: fiz minha própria lista de campos de `ChunkProviderServer`, `Chunk`, `EmptyChunk`, `ExtendedBlockStorage`,
`NibbleArray`, `AnvilChunkLoader`, `PlayerManager`/`PlayerInstance`, `EntityTracker`, `EntityTrackerEntry`, `EntityPlayerMP`,
`NetHandlerPlayServer`, `ForgeChunkManager`, `AsynchronousExecutor` e `LongHashSet` (coleções, arrays, mapas, contadores,
flags). Depois segui dois caminhos de tick do começo ao fim (seção 6). Itens que só acrescentam detalhe a um item antigo
ficaram nas correções. Os itens abaixo são mecanismos separados.

### N01. Carregar chunk já gerado roda os geradores de estrutura (`recreateStructures`)

- **Onde**: `CPS:364` (`safeLoadChunk`) e `CIOP:52` (estágio 2), que chamam `currentChunkProvider.recreateStructures`. No
  overworld: `ChunkProviderGenerate.java:496-505` (mineshaft, village, stronghold, scattered feature), `MapGenBase.java:15-33`
  (laço de 17x17 posições, `rand.setSeed` em `:19,28`), `MapGenStructure.java:28-45` (`structureMap.containsKey/put`,
  `rand.nextInt`), `:255-285` (lê `MapGenStructureData` de `perWorldStorage`), `MapGenVillage.java:78` (`areBiomesViable`,
  que usa `IntCache`) e `MapGenScatteredFeature.java:77` (`getBiomeGenAt`, que usa `BiomeCache`).
- **Tipo**: estado do gerador, um por mundo (`Random`, `HashMap`) e estático (`IntCache`).
- **Quem toca**: toda carga de chunk, inclusive de chunk já gerado e salvo, em qualquer thread que dispare a carga (H07).
- **O que quebra**: duas regiões do mesmo mundo carregando chunks ao mesmo tempo misturam o `Random` do gerador. A
  decisão "aqui começa uma estrutura" sai errada: aparece estrutura onde não devia (os blocos dela vêm depois, no
  `populate` de chunks novos) ou some uma que devia existir. Há corrupção do `HashMap structureMap` (laço infinito ou
  entrada perdida) e os problemas de `IntCache`/`BiomeCache` de H13/H14. Provedores de dimensão de mod têm o próprio
  `recreateStructures` (não verificado).
- **Gravidade**: B **alto** se houver carga na fase paralela; C **alto**.
- **Tratamento**: a porta única de carga por mundo (H07) cobre a carga inteira, com `recreateStructures` incluído. Pré-gerar
  não resolve.

### N02. Ticks aleatórios carregam chunk através da população de luz

- **Onde**: `WS:465-468` chama, para cada chunk ativo, `Chunk.func_150804_b` (`Chunk:1238-1251`). Se `!isLightPopulated &&
  isTerrainPopulated`, segue para `func_150809_p` (`Chunk:1524-1562`). A conferência `checkChunksExist` (`:1531`) cobre só os
  chunks X-1..X e Z-1..Z (blocos `X*16-1` a `X*16+1`). Mesmo assim, `:1549` e `:1553` chamam `getChunkFromBlockCoords(X*16+16, ...)`
  e `(..., Z*16+16)`, ou seja, os chunks X+1 e Z+1, **sem conferir**. Eles passam por `provideChunk` e, com
  `loadChunkOnRequest: true`, carregam ou geram (H07).
- **Tipo**: carga reentrante a partir de uma fase que o desenho pensa em paralelizar (`crucible-tick-e-threads.md` §5,
  "tickBlocks: precisa mudança").
- **O que quebra**: tudo de H07, agora a partir dos ticks aleatórios, e não só de TE ou entidade. Ocorre uma vez por
  chunk com a flag de luz pendente (chunk recém-gerado ou de mundo antigo). Frequência não medida.
- **Gravidade**: B **médio** (só se os ticks aleatórios forem para a fase paralela); C **alto**.
- **Tratamento**: a mesma porta de H07, ou rodar a população de luz só na fase serial.

### N03. Getters de `ExtendedBlockStorage` que gravam (leitura que escreve)

- **Onde**: `EBS:309-343` chama `NibbleArray.forceToNonTrivialArray` (`NibbleArray.java:50-60`). Quem chama:
  `S21PacketChunkData.layoutOf` e `writeChunkData` (`S21PacketChunkData.java:194-300`), usados pelo construtor de
  `S26PacketMapChunkBulk` (`:46-75`) dentro de `EPMP.onUpdate` (`EPMP:392`) e por `PlayerInstance.sendChunkUpdate` (`PM:582,601`,
  serial); o salvamento (`ACL:330-344`, serial); e mods que usam `getBlockStorageArray` (26 jars).
- **Tipo**: por chunk.
- **O que quebra**: ver 3.7. Um jogador da região A recebendo um chunk da região B converte os arrays triviais de B
  enquanto B escreve na mesma seção: escrita de luz ou metadado perdida, ou leitura de 0 no lugar do valor trivial.
- **Gravidade**: B **médio** (precisa de escrita na mesma seção no mesmo instante; cai para baixo se H30 for para a fase
  serial); C **alto**.
- **Tratamento**: tirar o envio de chunk da fase paralela (H30). Em C, fazer a conversão só na thread dona, ou deixar os
  getters sem efeito colateral (copiar em vez de converter).

### N04. `Chunk.setLightValue` numa seção que não existe recalcula a luz do céu do chunk inteiro

- **Onde**: `Chunk:769-777`. Com a seção nula, cria a seção (`:775`) e chama `generateSkylightMap()` (`:776`, corpo em
  `:274-345`), que reescreve `heightMap`, `precipitationHeightMap`, `heightMapMinimum` e a luz do céu de **todas** as
  colunas do chunk. Quem chama é a propagação de luz do mundo, que passa a fronteira de chunk (H18).
- **Tipo**: por chunk, escrita vinda de vizinho.
- **O que quebra**: uma região propagando luz para o chunk vizinho (de outra região) reescreve o mapa de altura inteiro
  dele enquanto o dono escreve: altura e luz erradas, chuva e neve na altura errada. É bem maior que o "nibble perdido" de H17.
- **Gravidade**: B **médio**; C **alto**.
- **Tratamento**: raio de junção de pelo menos 2 chunks (o mesmo de H17/H18). Em C, adiar a propagação de luz para fora da região.

### N05. `AsynchronousExecutor`: um segundo `get()` simultâneo roda o estágio 2 com chunk nulo

- **Onde**: `AE:122-126`. `initSync` passa o estado para `STAGE_1_COMPLETE` **antes** de rodar `init()` (o estágio 1).
  Uma segunda thread em `task.get()` (`AE:158-167`, por `skipQueue` em `AE:339-341`) não espera: entra em `finish()`
  (`AE:169-189`), vê `STAGE_1_COMPLETE` e chama `callStage2(parameter, object)` com `object == null`. `CIOP.callStage2(null)`
  cai em `originalLoadChunk` (`CIOP:37-40`). A primeira thread, ao terminar, também roda o estágio 2. `Task.callbacks`
  (`LinkedList`, `AE:78`) é lido e escrito pelas duas.
- **O que quebra**: o mesmo chunk inserido duas vezes por dois caminhos diferentes, o que duplica entidades e TEs (dupe).
  Isso já acontece com duas threads e uma tarefa na fila; a corrida de `HashMap` de H08 não é necessária.
- **Gravidade**: B **alto** (com cargas paralelas); C **alto**.
- **Tratamento**: porta única por chave (H06/H07). A trava precisa cobrir também o `skipQueue`.

### N06. `ChunkBlockHashMap`: o balde coincide com a seção do desenho (fato útil, não é risco novo)

- **Onde**: a chave do balde é `((x>>4)<<32) ^ (z>>4)` (`CBHM:119,141,168`), e cada balde guarda `Chunk[16][16]` (`CBHM:154`).
- **Observação**: um balde é exatamente uma seção de 16x16 chunks alinhada a múltiplos de 16, a mesma unidade do
  regionizador (`docs/02-arquitetura.md` §4 (2)). Com o balde já existindo, inserir um chunk é uma única gravação de
  referência num array. Só **criar** o balde mexe na estrutura do Koloboke (`CBHM:154-156`). Uso em 5.2.

### N07. Teleporte longo no mesmo mundo a partir de thread de região

- **Onde**: `EntityEnderPearl.java:79` chama `NHPS.teleport` (`NHPS:616-660`), que grava a posição do jogador, `lastPosX/Y/Z`
  e `justTeleported`, e envia `S08`. Plugins: `CraftPlayer.teleport` no mesmo mundo (`C/.../CraftPlayer.java:578-580`). O
  jogador passa a estar num chunk de outra região. O próximo tick dele faz a travessia de chunk comum (`World:2741-2756`) e
  grava `entityLists` de um chunk estrangeiro (H16).
- **Tipo**: suposição de ordem (a tabela do desenho, §5, trata teleporte como "sai no fim do tick", mas este caminho é
  imediato).
- **O que quebra**: campos do jogador e do `NetHandlerPlayServer` escritos por duas threads (corrida com a fase de rede,
  que em B é serial, então o risco em B é só com a região do próprio jogador); entidade entrando em chunk de outra região
  no meio da fase paralela.
- **Gravidade**: B **médio**; C **alto**.
- **Tratamento**: interceptar `NHPS.teleport`/`setPlayerLocation` chamados de thread de região e adiar para o ponto de junção,
  como os teleportes entre mundos (H35).

### N08. `NetHandlerPlayServer.processedDisconnect` não é volátil (logout em dobro)

- **Onde**: `NHPS:174` (campo), `:943-950` (confere e depois grava, sem atomicidade).
- **O que quebra**: duas threads expulsando o mesmo jogador (mod e plugin, ou duas regiões) passam as duas pela conferência
  e rodam `SCM.disconnect` duas vezes (`NHPS:962`): `PlayerQuitEvent` em dobro, `writePlayerData` em dobro, dois `remove`.
- **Gravidade**: B **baixo**; C **médio**.
- **Tratamento**: coberto pelo adiamento de kick/disconnect de H34. Se ficar em linha, usar `AtomicBoolean`.

### N09. `ETE.tryStartWachingThis` lê estado de entidade estrangeira na thread do jogador

- **Onde**: `EPMP:403` chama `ET.func_85172_a` (`ET:379-390`), que roda `tryStartWachingThis` (`ETE:409-500`) para as entidades
  do chunk que está sendo enviado. Ele lê posição, `motionX/Y/Z`, montaria, guia e equipamento (`ETE:460-499`) dessas
  entidades, que pertencem à região do chunk. O `DataWatcher` está protegido: tem `ReentrantReadWriteLock`
  (`DataWatcher.java:30,113-128`).
- **O que quebra**: só valores velhos ou rasgados (por exemplo `double` de 64 bits sem `volatile`) nos pacotes de spawn.
  É visual.
- **Gravidade**: B **baixo**; C **baixo**.
- **Tratamento**: some junto com H30 (envio de chunk na fase serial).

## 5. Os tratamentos propostos são seguros e baratos o bastante para a fase B?

### 5.1 H01: ThreadLocal funciona, mas existe um conserto mínimo mais barato

`xPosition` e `zPosition` são `final` (`Chunk:57-58`). Para B, basta ler cada `lastN` **uma vez** numa variável local e
conferir as coordenadas nessa cópia. O `get` passa a devolver o chunk certo ou errar o cache, nunca um chunk trocado. As
escritas concorrentes no cache só pioram a taxa de acerto. O que sobra é desempenho: com 4 núcleos gravando os mesmos 4
campos a cada falha de cache, a linha de cache do processador fica pulando entre núcleos. Cache por thread resolve isso.
Um campo na nossa classe de thread de região é mais barato que `ThreadLocal.get()` no caminho mais quente do servidor.
Em C o conserto mínimo não basta: um `put` atrasado pode devolver ao cache um chunk já descarregado.

### 5.2 H02: "nenhuma escrita na fase paralela" não fecha com carga na fase paralela

Se alguma carga acontecer na fase paralela (as duas opções de H07 inserem o chunk no mapa na hora, porque quem chamou
precisa dele), uma só thread escrevendo com as outras lendo já é inseguro no Koloboke (busca durante `put` ou rehash).
Pôr trava de leitura em todo `get` custa caro demais nesse caminho. Saída barata (N06): criar os baldes de todas as seções
da região na thread global, no ponto de junção. Na fase paralela, a carga só grava `bucket[x][z] = chunk`. O `size`
vira `AtomicInteger`. Falta uma barreira de memória na publicação: sem ela, outra thread pode ver `null` e tentar carregar
de novo, o que a porta única de H06 precisa impedir. Isso precisa de teste (não verificado).

### 5.3 H07: opção (a) perde itens; opção (b) tem risco de deadlock

- (a) "quem pediu recebe chunk vazio": `EmptyChunk.func_150807_a` devolve **`true`** sem guardar nada
  (`EmptyChunk.java:50-53`). O `setBlock` diz que deu certo e o bloco não existe, então o mod gasta o item e o bloco
  some. `spawnEntityInWorld` em chunk que não existe marca a entidade como morta e devolve `false` (`World:1824-1827`),
  então itens dropados ali **somem**. `getTileEntity` devolve `null`. Construtores, quarries e movedores de bloco perdem
  itens. Isso **não é seguro como padrão** para o teste de conservação. Só serve por mod, depois do observador.
- (b) "estágio 1 no lugar, efeitos colaterais adiados" é viável com três condições que o relatório não lista:
  1. Ordem de travas: a carga dispara `ChunkLoadEvent` (`CPS:279`, `CIOP:58`) sob `synchronized(pluginManager)`, e um handler
     de plugin que já segura essa trava pode pedir um chunk. Se a porta de carga for pega antes, há **inversão de ordem de
     travas e deadlock**. Todos os eventos (Bukkit e Forge, inclusive `ChunkDataEvent.Load`, que roda no estágio 2,
     `ACL:94`/`CIOP:44`) e o rastreador (`waitForLock`) precisam ser chamados **depois** de soltar a porta, ou a ordem
     precisa ser fixa (trava do Bukkit sempre primeiro).
  2. A porta cobre também a geração e `recreateStructures` (N01), com uma trava por mundo. A geração não pode ser adiada,
     porque quem chamou precisa dos blocos.
  3. Publicar no mapa na fase paralela exige 5.2.

### 5.4 H13: insuficiente

Ver 3.5 e N01.

### 5.5 H26/H28: seguro, com efeitos laterais

- Adiar adição e remoção no rastreador para o ponto de junção é seguro. Efeito visível: pacotes enviados por
  `func_151247_a`/`func_151248_b` (`ET:348-366`) sobre uma entidade criada no mesmo tick somem, porque a busca dá `null`
  até a junção. É só visual. Manter a ordem adicionar-depois-remover por entidade (entidade que nasce e morre no mesmo tick).
- H28 só fica coberto se a **adição** também for adiada (3.10).
- O mesmo adiamento vale para `WS.entityIdMap` (`WS:1131-1143`, `mundo.md` M18), que nasce do mesmo `onEntityAdded`.

### 5.6 H30: seguro e mais barato do que parece

A compressão roda no Netty (3.11). O custo serial é cópia de arrays mais pacotes de TE. Medir no observador.

### 5.7 H39: trava global em volta de quem posta evento é risco de deadlock

`forceChunk` e `unforceChunk` postam evento no `EVENT_BUS` no meio da operação (`FCM:743`, `:784`). Com a trava segura,
um ouvinte de mod que carregue chunk precisa da porta de H07. Ao mesmo tempo, uma thread dentro da porta pode chamar
`forceChunk`, porque carregadores de chunk costumam forçar o chunk ao serem criados ou validados. São duas travas em ordens
opostas. Usar **a mesma trava reentrante** da porta de carga para o FCM, ou postar os eventos depois de soltar a trava.

### 5.8 H34: lista de snapshot não conserta os laços por índice

`sendToAllNearExcept` itera por índice (`SCM:1510-1512`), como outros métodos. Uma lista com iteração em snapshot
(`ac070ce`) só protege o `Iterator`. Em B, se login, logout e kick forem adiados para a fase serial, a lista não muda
durante a fase paralela e nem precisa de snapshot.

### 5.9 H41: uma "trava de evento de mod" para `EnteringChunk` disputa muito

Esse evento dispara a cada travessia de chunk de qualquer entidade (`Chunk:859`). Uma trava única vira ponto de espera
constante e mais um par de travas para inverter com as de 5.3 e 5.7. Adiar muda o significado para mods que seguem
entidades por chunk. Primeiro listar os ouvintes reais (não verificado).

### 5.10 H05: "só a thread global mexe"

Exige interceptar o `remove` em `CPS:176`, `CPS:217` e `CraftWorld.java:285`, que hoje rodam em toda carga, e os pedidos de
descarga de plugin (3.2). Se os ticks aleatórios forem para a fase paralela, o leitor `WS:453` concorre com qualquer escritor
que sobrar.

### Sem objeção

H04, H06, H08, H09, H10, H11, H15, H16, H17 (com 3.7), H18, H19, H20, H22, H24, H25, H27, H29, H31-H33, H35-H38, H40, H42.

## 6. Os dois caminhos de tick seguidos

### 6.1 TE que move itens para o inventário vizinho e avisa os vizinhos (funil vanilla)

`TileEntityHopper.func_145893_b` (`TileEntityHopper.java:754-788`):
- `World.getTileEntity` (`World:3183-3187`): `provideChunk`, depois `CBHM.get` (H01). Pode **carregar** o chunk se o vizinho
  estiver descarregado (H07, N01). `Chunk.func_150806_e` do chunk vizinho remove entrada inválida ou **cria TE** (H15).
- Sem inventário de bloco: `getEntitiesWithinAABBExcludingEntity` (`:779`, `World:3938-3958`) varre `entityLists` dos chunks
  vizinhos (H16, H40). O sorteio usa `World.rand` (`mundo.md` M17).
- Durante a transferência: `InventoryMoveItemEvent` do Bukkit (`:371-372`, `:563-564`), que pega a trava global do Bukkit
  (área bukkit).
- Depois: `TileEntity.markDirty` (`TileEntity.java:160-172`) chama `markTileEntityChunkModified` (`World:4020-4026`), que faz
  `blockExists` (H01) e `isModified` do chunk alvo (H20, idempotente em B), e `World.func_147453_f` (`World:4661-4684`), que
  faz até 12 `getBlock` (H01; pode carregar chunk a 2 blocos, H07) e chama `onNeighborChange` de mod.
- Mudança de metadado do funil: `Chunk.setBlockMetadata` (`Chunk:726-761`) grava nibble (H17), depois `markBlockForUpdate`,
  `PM.markBlockForUpdate` (`LongHashMap` lido, H31) e `flagChunkForUpdate` (H32).
- Pools de objeto: **não existem em 1.7.10**. `AxisAlignedBB.getBoundingBox` faz `new` (`AxisAlignedBB.java:13-16`), e
  `Vec3.createVectorHelper` também (`Vec3.java:10-13`).
- Estado novo da área achado neste caminho: nenhum além de N01 (via carga).

### 6.2 Entidade que cruza borda de chunk e é rastreada por um jogador

`World.updateEntityWithOptionalForce` (`World:2647-2757`):
- `activity` (`ConcurrentHashMap`, seguro) e `getPersistentChunks()` (`World:4784-4787`, depois `FCM:817-820`): mapa
  concorrente com valor imutável, leitura segura.
- `getChunkIfLoaded` (H01) e `areNeighborsLoaded(2)` (H19).
- `CauldronHooks.canSushchestvoTick` e `ActivationRange.checkIfActive` (área estático).
- `onUpdate` faz `moveEntity`, que usa `World.collidingBoundingBoxes` (`mundo.md` M01).
- Travessia: `removeEntityAtIndex` no chunk antigo e `addEntity` no novo (`:2741-2756`). Atinge `entityLists` (H16),
  `entityCount` (H21), `hasEntities` (H20) e `EnteringChunk` (H41). Se o chunk novo não existir, a entidade fica com
  `addedToChunk = false` e **não** dispara carga. A travessia em si não carrega chunk.
- Rastreamento: nada no tick da entidade. `ET.updateTrackedEntities` roda depois, em série.
- Lado do jogador: `EPMP.onUpdate` (H28, H29, H30, N03, N09).
- Estado novo da área achado neste caminho: nenhum. N07 apareceu ao procurar quem escreve posição de jogador.

## 7. Conferência do veredito do mapeador sobre `arte-previa.md` §4

| Item | Veredito do mapeador | Minha conferência |
|---|---|---|
| 2 (mapas do `ChunkProviderServer`) | confirmado na substância, errado nos detalhes | **concordo** (`CPS:76,82,84-86`; `CBHM:12,40`; `VCHM:12`) |
| 3 (`Chunk`: mapa de TE, `entityLists`, EBS) | confirmado com correções | **concordo**; somar 3.7 (getters que gravam, MSB preguiçoso, leitura rasgada) |
| 5 (luz) | confirmado na parte de `Chunk` | **concordo**; somar N04 |
| 6 (`IntCache`) | errado no detalhe, certo na conclusão | **concordo** (`IntCache.java:15,57-73`); somar que a carga de chunk também usa `IntCache` (N01) |
| 8 (`PlayerManager`/`EntityTracker`) | confirmado, com a trava quebrada | **concordo com ressalva**: o `HashSet` está protegido; o problema é `trackedEntityIDs` e a latência (3.9) |
| 9 (`playerEntityList`) | confirmado | **concordo** |
| 13 (`ForgeChunkManager`) | confirmado, com perda de atualização | **concordo** (`FCM:745-746,785-788`) |
| 14 (`ChunkIOExecutor`) | confirmado, com o tamanho corrigido | **concordo** (`ChunkIOExecutor.java:24-27`; `AE:234`); somar N05 |
| Pools de objeto (a tarefa cita que podem não existir em 1.7.10) | não tratado | **não existem em 1.7.10** (6.1) |

## 8. Lacunas que continuam abertas

- Frequência, neste pack, de cargas de chunk disparadas por TE, entidade e ticks aleatórios (N02), de teleportes por pérola
  e mod (N07) e de `forceChunk` dentro de tick: **não medido** (observador da Etapa 2).
- `recreateStructures` dos provedores de dimensão de mod: **não verificado** (N01).
- O laço infinito do `LongHashSet` (3.2): **plausível pelo código, não demonstrado**.
- Se o JIT junta as duas leituras de `last1` (H01): **não verificado**. O conserto de 5.1 independe disso.
- Barreira de memória e versão do Java para a publicação de chunk sem trava (5.2): **não verificado**.
- Mods que escrevem no inventário do jogador a partir de TE de outra região (carregadores sem fio, terminais): estado por
  jogador fora desta área; **não verificado**.
- `MapGenStronghold` calcula as posições de forma preguiçosa na primeira chamada; corrida nessa primeira chamada **não verificada**.
- Ouvintes de mod de `EnteringChunk`, `ChunkEvent`, `ChunkDataEvent`, `ChunkWatchEvent` e `ForceChunkEvent`: **não verificados**
  (mesma lacuna do mapeador).
- Configs de produção (`Crucible.yml`, `forgeChunkLoading.cfg`): só a cópia de teste foi lida (`loadChunkOnRequest: true`,
  `dormantChunkCacheSize=0`).
