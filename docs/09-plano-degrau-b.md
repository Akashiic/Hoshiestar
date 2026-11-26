# Plano do Degrau B: fase paralela sincronizada com barreira

Data: 2026-11-26. Autorizado pelo admin (decisão 37). É só o plano: nenhum código de produção escrito, nenhum servidor
ligado. Junta dois desenhos independentes (`desenho-minimo.md`, `desenho-seguro.md`) e três levantamentos (laço, estado
global, Folia), da pasta de trabalho `scratchpad/degrauB`. Onde os desenhos discordavam, a escolha e o motivo estão na §14.
**Revisado no mesmo dia** depois de três revisões adversárias (perda ou duplicação, corrida e deadlock, mods e plugins
reais): o que cada achado mudou está na §16.

Linhas conferidas no fork (`scratchpad/fork/Crucible`, branch `hoshiestar`, `c2558bc2`). Siglas: **W** `World.java`, **WS**
`WorldServer.java`, **CPS** `ChunkProviderServer.java`, **MS** `MinecraftServer.java`, **TE/TEH/TEEC** `TileEntity{,Hopper,EnderChest}.java`,
**CBHM** `thermos/wrapper/ChunkBlockHashMap.java`, **TR** `thermos/thermite/ThermiteRandom.java`, **SPM** `SimplePluginManager.java`,
**EB** `EventBus.java` (FML), **FMLCH** `FMLCommonHandler.java`, **NHPS** `NetHandlerPlayServer.java`, **CW** `CraftWorld.java`.
Códigos M/H/S/K são de `docs/pesquisa/estruturas-hostis.md`; D-xx é do `DIAGNOSTICO.md`.

Termos: **tile** = tile entity (bloco com dados e tick próprio: baú, funil, máquina). **Fase paralela** = trecho do tick em
que várias threads ticam regiões ao mesmo tempo. **Barreira** = a thread do servidor espera todas acabarem. **Junção** =
trecho em série logo depois da barreira, onde o que foi adiado é aplicado. **Lote** = os tiles de uma região neste tick, na
ordem original. **Trabalhador** = thread de região. **Dono** = o lote autorizado a mexer num chunk naquele instante.
**Liberada** = classe revisada, que pode rodar na fase. **Elegível** = região que passa em todas as condições neste tick.
**Guarda** = checagem num ponto de entrada do mundo. **Violação** = guarda que pegou algo proibido. **Pedido delegado** = a
operação proibida que o trabalhador entrega para a thread do servidor rodar no lugar dele. **Sinal** = condição de risco sem
dano (a dimensão volta para série, sem parar o servidor).

## 1. Objetivo e limites

Objetivo da primeira entrega: **provar a máquina** (barreira, dono, adiamentos, detecção e prova), não acelerar a produção.
Os tiles de classe vanilla somam 0,08% da thread no perfil de produção (levantamento Folia §6): o ganho em produção será
perto de zero. O ganho vem depois, liberando tiles de mod um a um com o mesmo protocolo. **Entra:** uma dimensão; 3 threads
de região; em paralelo só tiles de classes liberadas, em regiões inteiras elegíveis; todo o resto em série como hoje;
guardas de dono; três modos de prova antes do paralelo; desligado por padrão.

**Classes da primeira entrega (onda 1, obrigatória):** mesa de encantamento, baú (e baú-armadilha) e baú de ender.
**O funil saiu da lista obrigatória** (§16): vira a onda 2, condicional. Ele é a única das quatro classes que move item, que
tem código de mod dentro do próprio tick (hodgepodge, EtFuturum) e que em produção fica inelegível por causa de três
plugins. Só entra se o passo 0 der veredito "seguro" para cada trecho de terceiros no caminho dele; se não der, a primeira
entrega fecha com a onda 1 e o funil passa para a entrega seguinte.

**Não entra:** entidades e jogadores na fase; ticks de bloco (agendados, aleatórios, eventos de bloco: ficam em
`WorldServer.tick`, decisão 15); tile de mod; fornalha, alambique, sensor de luz, farol, gerador e pistão; ouvinte de plugin
ou de mod rodando em thread de região; carga de chunk na fase (a porta única de carga, D-41, fica para depois); mudança em
`isPrimaryThread()`; sorteio por lote (nenhuma classe da entrega sorteia); mais de uma dimensão; liberação por tile;
Degrau C, correio entre regiões, adapter de plugins.

**Código de terceiros no trabalhador.** Classe vanilla não quer dizer "sem código de mod": vários mods injetam código nos
métodos do mundo que todo tile chama (`getBlock`, `provideChunk`, `getTileEntity`). A regra é: no trabalhador só roda código
de terceiros que o passo 0 listou, revisou e travou por SHA-256 do jar. Sem veredito, o recurso não liga.

## 2. Modelo de fases e threads

```
MinecraftServer.tick                                                              [série]
  MS:871   GanchosOrcamento.inicioTick: mapa de regiões do tick (imutável) e quem está em corte
  ...      WorldServer.tick: ticks agendados, aleatórios, eventos de bloco, descarga de chunk (WS:261)
  MS:1046  World.updateEntities do mundo escolhido:
    até W:2499   clima, remoção, laço de entidades                                [série, como hoje]
    W:2504-2531  flag do laço ligada; limpeza de field_147483_b                   [série, como hoje]
    NOVO W:2533  Paralelo.abrir: portões, classificação das regiões (só leitura)  [fase MONTANDO]
    W:2536-2594  laço original, com UMA linha nova antes do gancho do orçamento (W:2563):
                   tile de região elegível -> entra no lote dela; continue
                   qualquer outro tile     -> tica ali mesmo, como hoje
    NOVO W:2594  Paralelo.rodarFase: fecha a vez do orçamento, tira as fotos dos invariantes,
                 publica os lotes; trabalhadores ticam                            [fase RODANDO, PARALELO]
                 barreira: a thread do servidor espera e atende pedidos delegados [série, esperando]
                 junção: lotes sujos em série, adiados em ordem fixa, tempos, invariantes, falhas [fase JUNÇÃO]
    W:2595-2647  fim do laço, flag desligada, tiles pendentes                     [série, como hoje]
  rastreador, rede, plugins, salvamento                                           [série, como hoje]
```

- **Estados da fase:** FECHADA → MONTANDO (`abrir`; só a thread do servidor, nenhum trabalhador sabe dela) → RODANDO (lotes
  publicados) → JUNÇÃO → FECHADA. Se um tile em série lança exceção no meio do laço (W:2588), o fluxo sai sem passar por
  `rodarFase`: a fase fica em MONTANDO, ninguém espera por ela, e os lotes são descartados no próximo `abrir` ou na parada
  (os tiles deles não ticam, como os tiles depois do erro hoje). Não usamos `try/finally` dentro de `updateEntities`, por
  causa das regras de bytecode abaixo.
- **O despacho fica dentro do laço original.** Cursor, limitador do Spigot, tile nulo, e o trecho de W:2551 a W:2561 (tile
  inválido, `CauldronHooks.canTileEntityTick`, `blockExists`) rodam uma vez por tile, em série, na ordem de hoje. O patch
  `TileUpdate` do DragonAPI, quando ativo (é o padrão dele; na cópia, não verificado), troca esse trecho inteiro por um
  evento próprio (`TileUpdateEvent.fire`); como a nossa linha vem depois, ele continua valendo para todo tile. A linha
  nova fica onde o gancho do orçamento já convive com DragonAPI, firewall e Opis.
- **Portão de tiles do Akashic-World-Firewall.** O firewall troca a chamada `updateEntity` do laço por uma chamada dele,
  que só tica o tile se a política do mundo deixar (`WorldMutationGuard.allowTileEntityTick(te, world)`, conferido no
  bytecode). O lote não passa por essa chamada. Por isso, em `sombra_ordenada` e `paralelo`, o despacho pergunta ao firewall
  em série, por reflexão, antes de pôr o tile no lote; se a resposta for "não", o tile não tica (igual ao original).
- **Reconferência na hora do tick.** Entre o despacho e o tick passa tempo (o resto do laço em série). Antes de **cada**
  `updateEntity` de lote (trabalhador, `sombra_ordenada` e lote sujo na junção) conferimos de novo `isInvalid()` e
  `hasWorldObj()`. No lote sujo, que roda em série, também `worldObj.getTileEntity(x, y, z) == tile`. Falhou: pula sem
  ticar; a remoção da lista fica para o laço do tick seguinte, como hoje. Sem isso, um funil quebrado depois do despacho
  ainda puxaria um item do baú de cima para dentro de um tile que já saiu do mundo (item perdido).
- **Regras de bytecode de `World.updateEntities`** (três transformadores de terceiros reescrevem esse método; conferido no
  bytecode de cada um): (a) a variável `tileentity` continua no slot 9: nenhuma variável local nova antes do laço, todo o
  estado novo fica nas nossas classes (o Opis usa o slot 9 fixo); (b) o método tem **uma só** chamada
  `TileEntity.updateEntity` (o firewall desiste do patch dele, para todos os tiles, se contar outro número; o Opis também
  procura essa chamada): os ticks de lote ficam nas classes `hoshiestar.paralelo`, nunca escritos dentro do método; (c)
  nenhuma chamada `isInvalid` nem `blockExists` antes de W:2551 (o DragonAPI corta do primeiro `isInvalid` ao primeiro
  `blockExists`). As três regras viram teste de bytecode com `javap` na classe final.
- **Threads:** `ThreadRegiao` (3 por padrão, `daemon`, nome "Hoshiestar Regiao #i"), estacionadas fora da fase. **A thread
  do servidor não pega lote**: espera a barreira e atende pedidos delegados (§3). Fora isso, nenhum código de jogo roda
  nela durante a fase (nem rede, agendador ou comandos).
- **Barreira** (requisitos; as classes exatas ficam com quem escrever a concorrência): a fase é um objeto imutável,
  publicado por escrita `volatile` depois de pronto; cada lote é pego por exatamente um trabalhador (contador atômico);
  lotes mais caros primeiro (tempo do orçamento); a fase termina quando a contagem de lotes feitos bate; cada trabalhador
  só soma na contagem **depois** da última escrita do lote (adiados, tempo, exceção), e ler essa contagem dá à junção a
  visão de tudo o que os trabalhadores escreveram; fase sem lote não acorda ninguém. A espera é em fatias de 5 s: a cada
  fatia vencida, vai ao log a pilha e o tile da vez de cada trabalhador atrasado, mais `findDeadlockedThreads`. **Nunca** se
  interrompe uma thread de região (estado pela metade é a origem de duplicação).
- **Três modos antes do paralelo**, com o mesmo código de despacho: `sombra` (tudo em série na ordem original, pela chamada
  original; as guardas só contam "isto seria ilegal na fase"); `sombra_ordenada` (a thread do servidor roda os lotes
  sozinha, com adiamentos: prova a ordem e a junção sem concorrência); `paralelo` (trabalhadores de verdade).

## 3. Regra de dono e checagem

**Dono** = a região do lote que a thread está rodando (ideia do Folia: o dono é a tarefa, não a thread). O mapa é o do
orçamento (`hoshiestar/regioes/Mapa.java`, imutável, trocado só em MS:871). Como a região só é elegível se os 3×3 chunks em
volta de cada tile estão carregados e são dela (§6, G1), tudo o que um tile liberado alcança (até ~5 blocos) é da região.

Checagem em tempo constante: fase RODANDO? → a thread é `ThreadRegiao`? → seção do chunk (`MapaSecoes.chave`) →
`Mapa.regiaoDaChave` (Mapa.java:66) igual à região do lote, com cache da última seção. Com o recurso desligado o gancho
fica atrás de `static final` e o JIT o apaga (mesmo teste de bytecode dos outros recursos).

| Quem chega à guarda durante a fase | O que acontece |
|---|---|
| Trabalhador, operação permitida no próprio dono | segue |
| Trabalhador, operação proibida ou fora do dono | **pedido delegado**: o trabalhador não roda a operação. Ele entrega o pedido à thread do servidor e espera, sem segurar monitor. A thread do servidor espera os outros trabalhadores pararem (no fim do tile corrente, ou no começo de um pedido próprio, antes de qualquer efeito), roda a operação pelo método original e devolve o resultado ou a exceção. A violação é registrada com pilha; a política da §9 vale na junção |
| Thread do servidor atendendo um pedido | passa direto por todas as guardas seguintes (a carga de um chunk passa por várias, umas dentro das outras) e conta a violação uma vez por pedido |
| Thread estranha (tarefa assíncrona de plugin) em escrita, carga de chunk ou salvamento (`saveAllChunks`, `saveAllWorlds`; `CraftWorld.save` não confere a thread, CW:844-856) | **portão**: espera a barreira e segue, com contador e pilha. É a corrida que hoje já existe contra a thread do servidor |

Regras do pedido delegado (cada uma é requisito e caso de teste do passo 5):
- **Por que na thread do servidor.** Não mentimos para o código (nada de exceção como o Folia, nada de `null`/ar como o
  NeoFolia), e a operação roda com a identidade que o código espera: `isPrimaryThread()` verdadeiro, Timings e lado do FML
  como em série. Um ouvinte que faz "se não sou a principal, peço à principal e espero" (padrão conferido no Skript,
  `Task.callSync`) travaria o servidor para sempre se rodasse no trabalhador; na thread do servidor ele segue direto.
- **Prazo vencido nunca libera a operação.** A espera é em fatias de 5 s com despejo de pilhas, como a barreira. Se um
  trabalhador não para porque está preso em código de jogo, é o stall de hoje (D-21) e vale a parada da §7.
- **Dois pedidos ao mesmo tempo:** a thread do servidor atende um por vez, o do lote de menor chave primeiro; o outro fica
  parado no começo do próprio pedido.
- **O trabalhador não segura monitor ao pedir** (as guardas ficam na entrada dos métodos, antes de qualquer
  `synchronized`). Em teste, conferido pelo `ThreadMXBean`.
- **Escrita delegada em outra região** suja o lote dela: o trabalhador desse lote para no fim do tile corrente e o resto
  do lote roda em série na junção.
- **Depois da primeira violação do tick**, nenhum trabalhador pega lote novo: os lotes que sobraram rodam em série na
  junção, na ordem da chave.

Regras do portão: a thread estranha não tem prazo que a libere com a fase aberta. Exceção única, para não criar espera sem
fim: se ela segura o monitor do `SimplePluginManager` e um pedido delegado precisa desse monitor, a escrita dela é atendida
antes, com todos os trabalhadores parados. Na parada do servidor o portão acaba junto com a fase (§7).

Escritas e carga de chunk são conferidas sempre. Leitura (`getBlock` W:666, `getTileEntity` W:3188): todas em `sombra`,
`sombra_ordenada` e teste; 1 a cada N em `paralelo` (é o caminho mais quente do jogo, D-16).

**Validade da classificação.** Entre `abrir` e `rodarFase` o laço tica em série os tiles das outras regiões. Se uma guarda
de escrita (bloco, tile, entidade) for chamada nesse trecho numa região elegível, o lote fica **sujo** e roda em série na
junção, na mesma ordem e com a reconferência da §2. A fase nunca roda com classificação velha.

**Invariantes por tick** (baratos, sempre ligados com o recurso). As fotos são tiradas **no começo de `rodarFase`**, depois
do laço em série, e conferidas na barreira e no fim da junção. (Entre `abrir` e `rodarFase` o próprio laço original muda as
listas de forma legítima: tira tile nulo e inválido, tiles em série criam tile pendente e entidade. Foto tirada em `abrir`
pararia o servidor sempre que um jogador quebrasse um baú.) Conferidos: mesmo objeto e tamanho de `loadedTileEntityList`,
`addedTileEntityList` e `loadedEntityList`; versão do mapa de chunks igual; nenhuma entrada nova nas filas de tick agendado
do mundo; mesma referência do `Mapa`; captura de blocos desligada; cada tile em no máximo um lote. Quebrou = violação (um
pedido delegado que mexe nessas listas já conta como a mesma violação, não como duas). Em teste, mais: itens marcadores
contados por região antes e depois da fase, e nenhum `ItemStack` em dois slots.

## 4. Estado compartilhado

**P** proibido na fase (guarda = violação, com pedido delegado); **A** adiado no lote e aplicado na junção pelo método
original; **C** corrigido no fork; **S** seguro como está.

| Item | Tratamento na primeira entrega | Onde (patch) |
|---|---|---|
| `loadedTileEntityList`, cursor, limitador (M03) | S: só o laço em série lê e remove; o trabalhador recebe o lote pronto | W:2536-2594 |
| `addedTileEntityList` (M04, M05) | P: ninguém grava na fase; trabalhadores só leem (W:3206-3242). A classificação recusa região onde uma leitura criaria tile. Buffer por lote só entra com a fornalha | `setTileEntity` W:3253, `removeTileEntity` W:3296, `addTileEntity` W:4772, `func_147448_a` W:2657 |
| Listas de entidade, `entityIdMap`, rastreador (M02, M18, H26) | P | `addEntity` W:1739, `removeEntity` W:1872 |
| Bloco, metadado, luz (M15), flag estática da fornalha (S02) | P: nenhum tile liberado escreve bloco. Luz e flag por thread entram com a fornalha | `setBlock` W:784, `setBlockMetadataWithNotify` W:918, `notifyBlockOfNeighborChange` W:1091 |
| Vizinhos do `markDirty` (TE:160-170) | guarda de dono; o vizinho tem de ser bloco neutro (§6) | `func_147453_f` W:4705, `markTileEntityChunkModified` W:4064 |
| Ticks agendados, id estático (M09, M10) | A: o trabalhador anota os argumentos; a junção chama o original e o id nasce em série. Contador (esperado 0: sem comparador perto) | WS:596, WS:643 |
| Eventos de bloco (M11; baú de ender a cada 20 ticks, TEEC:18-20) | A | `addBlockEvent` WS:1256 |
| Sons, partículas e marcas para o cliente (`worldAccesses`, M35); pacotes (H37) | A: os 12 métodos `void` do `World` que percorrem `worldAccesses`. A junção repete a chamada do **método original inteiro** (ex.: `playSoundEffect`), não só o laço de `worldAccesses`, para que os mixins de terceiros nesses métodos (akashicmixer) vejam a pilha de sempre. Pacote enviado por trabalhador = violação | W:999 a W:4678; NHPS:977 |
| `world.rand` (`ThermiteRandom`, sem proteção, M17) | P nesta entrega: nenhuma classe liberada sorteia nas condições de elegibilidade (baú e baú de ender só ao abrir e fechar; a mesa usa um `Random` próprio). Extração por trabalhador = violação com contador (esperado 0). O sorteio por lote entra com a primeira classe que precisar | guarda em `next` TR:120, só para o `rand` de um mundo; campo em W:146 |
| Carga de chunk (H07, D-41) | P: chunk fora do mapa pedido por trabalhador | CPS:174; ramo de falta em CPS:310 (inclusive o que devolve chunk vazio); CPS:215 |
| Fila de descarga `chunksToUnload` (K30: evento Bukkit montado com `CW.getBlockAt`, CW:114 e :151) | A: chunk presente → devolve sem gravar e anota "não descarregar"; a junção aplica. Das classes da entrega, só o funil chega aqui (`getOwner` → `CW.getBlockAt` → `loadChunk`); `provideChunk` com o chunk presente não mexe na fila (CPS:305-310) | CPS:174-176 |
| Cache de 4 chunks do mapa (H01, D-32) | C, em três partes: (1) `get` lê cada `lastN` uma vez para variável local (o chunk tem coordenada `final`); (2) na falta, thread que não é a do servidor **não grava** `last1..4`: vai ao mapa e devolve (como o `CacheDeChunks.get` já faz). Duas threads gravando juntas deixam o mesmo chunk em duas casas, e o `remove` de hoje só limpa uma: o chunk descarregado ficaria vivo no cache (item posto nele se perde, item tirado duplica); (3) `remove` limpa **todas** as casas que batem. Vale com o `chunkmap` ligado ou desligado. Contador de versão do mapa | CBHM:149-174; CBHM:270-290; CBHM:178 e :239 |
| Eventos Bukkit (D-26, K01) | nenhum ouvinte roda em trabalhador: região com ouvinte para evento que seus tiles disparam não é elegível. `callEvent` de trabalhador com vetor vazio volta sem entrar no `synchronized` (efeito igual, SPM:498-502); com ouvinte = violação, e o evento roda na thread do servidor (pedido delegado) | SPM:482-496 |
| Eventos Forge (S04) | P: qualquer `post` vindo de trabalhador | EB:151 |
| `ChunkRequestEvent` do DragonAPI (não é evento Forge: é chamado no começo de `provideChunk`, por onde passa todo `getBlock`, W:779-781; grava 4 campos estáticos sem trava e chama observadores) | condição de boot: a lista de observadores (lida por reflexão) só tem classes revisadas no passo 0, senão recusa. O observador conhecido (`WorldGenInterceptionRegistry`) volta na hora quando o profiler de geração está desligado. Profiler ligado (`WorldgenProfiler.profilingEnabled()`, conferido no bytecode) = sinal, visto em `rodarFase` | sem patch (jar de terceiros); checagem em `Paralelo` |
| Lado do FML (S30) | C: `getEffectiveSide` devolve servidor também para `ThreadRegiao` (hoje compara o nome da thread) | FMLCH:156-165 |
| `isPrimaryThread()` (K09), Timings | S: não muda; o trabalhador não é "primário" e não chama `tickTimer` (some do relatório de Timings); `tilesTicked` é somado na junção. O que precisa ser "primário" roda por pedido delegado | W:2568-2571 |
| Captura de blocos e de drops (M20-M22) | S + portão: desligada na abertura, senão sinal | `Paralelo.abrir` |
| Estáticos só escritos em série (`currentTick`, `Relogio`, `ActivationRange`, caches do `CauldronHooks`) | S | — |

## 5. Bordas

| Caso | O que acontece |
|---|---|
| Funil empurrando para outra região | impossível em região elegível: o alvo está a 1 bloco e os 3×3 chunks são da mesma região; senão a região roda em série. As guardas de W:4064 e W:4705 pegam o que escapar |
| Cano ou cabo de mod | se tem tick, é tile de mod na lista e a região dele roda em série (G3). Se não tem tick (`canUpdate()` falso), não aparece na lista; aí quem protege é a regra de vizinho neutro e de alvo em lista fechada (§6): bloco de mod encostado em tile liberado tira a região da fase. Redes globais de mod (D-49) continuam nos handlers em série |
| Tile na borda da área carregada | não elegível (G1); o contador de inelegibilidade sobe e as cargas na fase ficam em 0 |
| Entidade cruzando região | entidades ticam antes, em série; durante a fase estão paradas. Entidade com inventário na caixa de um funil → região em série; se aparecer na fase, violação (TEH:780-785) |
| Jogador | série (rede e laço de entidades). Baú ou funil com a tela aberta → região em série naquele tick |
| Chunk carregando ou descarregando | só em série (MS:975, WS:261). Seção nova fica sem região até o recálculo do orçamento: tiles dela em série |
| Mapa de regiões mudando; corte do orçamento | o mapa só muda em MS:871. Região suspensa ou em corte não é elegível (a em corte é pulada pelo gancho do orçamento, como hoje); com todas suspensas a fase não abre (sinal) |
| Ouvinte de plugin registrado no meio do tick | o vetor de ouvintes é conferido de novo no `callEvent`; registro detectado = sinal |
| Bloco com tile quebrado depois do despacho | lote sujo; o tile inválido é pulado pela reconferência (§2) |

## 6. Liberação de classes

A lista é **embutida no jar**; `paralelo.classes` só escolhe um subconjunto. Liberar classe nova exige revisão e build.
Comparação por classe exata (`getClass() ==`): tile de mod que herda de baú não entra.

| Onda | Classe | Condições extras, avaliadas a cada tick |
|---|---|---|
| 1 | Mesa de encantamento | — |
| 1 | Baú e baú-armadilha | fechado: `numPlayersUsing == 0` e tampa em 0 (aberto ou fechando, toca som e sorteia, TEC:388/426); cada vizinho baú tem tile no chunk |
| 1 | Baú de ender | fechado: ninguém usando e tampa em 0 (TEEC:31/58) |
| 2 (condicional) | Funil | alvos de cima e da frente: tile de classe exata baú ou funil, ou **bloco de lista fechada** (abaixo), sem entidade com inventário na caixa; parceiro de baú duplo com tile; vizinhos neutros em volta de cada ponto que chama `markDirty`; ninguém com a tela do funil aberta; todos os itens no caminho são vanilla; nenhum ouvinte de `InventoryMoveItemEvent` nem de `InventoryPickupItemEvent` |

Condições gerais (falhou um tile, a região inteira vai para série, com o primeiro motivo anotado):
- **G1** os 3×3 chunks em volta do tile estão carregados e têm a mesma região no mapa.
- **G2** a região não está em corte nem suspensa.
- **G3 região inteira:** todo tile **válido** da região na lista de tiles é de classe liberada. A ordem dentro da região
  fica igual à do desligado, e tile paralelo nunca encosta em tile em série. Tile inválido esquecido na lista não conta
  (com o patch `TileUpdate` do DragonAPI ativo, o laço não remove mais os inválidos) e nunca entra em lote.
- **G4** nenhum tile válido aparece duas vezes na lista (o IC2 escreve direto nela, D-52).
- **G5** portões da dimensão: aquecimento vencido, medidor e observador parados, captura desligada, limitador do Spigot
  sem cortar, mapa presente, profiler de geração do DragonAPI desligado.

**Bloco neutro** (vizinho de quem chama `markDirty`) = classe do pacote `net.minecraft.block` cujos `onNeighborBlockChange`,
`onNeighborChange` e `getWeakChanges` são os do próprio `Block` (reflexão no boot, guardado por bloco), ou bloco de uma
lista curta revisada (baú, funil, baú de ender, mesa). Comparador, repetidor, fio e todo bloco de mod não são neutros.

**Bloco de lista fechada** (alvo do funil que não é inventário) = ar e blocos vanilla simples revisados, comparados por
identidade, e só se a classe e todas as interfaces dela forem `net.minecraft.*` (conferido no boot). **Nunca "posição sem
tile"**: o EtFuturum faz o funil tratar o composter (bloco de mod sem tile) como inventário, e esse código escreve
metadado de bloco, agenda tick e toca som (conferido no bytecode do mixin `blockinventories/MixinTileEntityHopper`).

**Travas de versão.** Cada classe liberada guarda quais jars de terceiros a transformam ou injetam código nos métodos que
ela alcança, e o SHA-256 revisado de cada um; jar com outro SHA no boot tira a classe da lista, com aviso. Lista de partida
(o passo 0 fecha): para todas as classes, DragonAPI (`TileUpdate`, `ChunkRequestEvent`), Akashic-World-Firewall, Opis,
hodgepodge (`MixinWorldGetBlock`, `MixinWorld_SimulationDistance`), BugTorched, Akashic-TickRelief, AkashicBellow
(`MixinWorld_Deep`), akashicmixer, sampler, praesidium; para o funil, mais hodgepodge `MixinTileEntityHopper` (troca a
puxada do funil por código do mod) e EtFuturum. O passo 0 dá um veredito por injeção: "só medição", "seguro em
trabalhador", "tratado por regra do plano" (firewall: pergunta no despacho) ou "o recurso recusa ligar com isto ativo".
Sem veredito, não liga.

**Promoção de uma classe** (vale para toda classe futura, inclusive de mod): `sombra` com contadores "seria ilegal" em
zero → `sombra_ordenada` com diferença zero → `paralelo` com 1 a 4 threads igual a `sombra_ordenada` → conservação →
estresse → 24 h. **Ondas seguintes**, cada uma com passo e teste próprios: fornalha (pendentes por lote, flag e luz por
thread, sorteio por lote, regra para `BlockPhysicsEvent`, revisão dos 3 ouvintes de `FuelBurnTimeEvent` e dos
`IFuelHandler` de 21 jars); alambique, sensor de luz, farol; gerador e pistão só depois do spawn adiado.

## 7. Convivência com o Degrau A e os demais recursos

| Recurso | Regra |
|---|---|
| Orçamento | pré-condição: `orcamento.ligado: true` (modo `medir` basta); é ele que mantém o mapa. Tile de lote não passa pelo gancho do orçamento. `rodarFase` começa fechando a vez do orçamento (`fimLaco()`): sem isso a espera da barreira cairia na conta da região do último tile em série, que poderia ser cortada sem culpa (`Nucleo.trocar`/`fechar`, Nucleo.java:534-583). Depois, o tempo de cada lote, medido no trabalhador, é entregue na junção por um método novo, só da thread do servidor. Corte, alerta e TPS por região continuam valendo. A espera de teste por região (`hoshie regioes teste`) é paga uma vez por lote |
| Vigia | antes da barreira a thread do servidor marca "fase paralela, N lotes" (`Marcas.tile` aceita qualquer objeto, Marcas.java:160). Trabalhador não chama `Marcas`; cada um guarda o próprio tile da vez. O despejo a cada 5 s sai da espera da barreira |
| Medidor e observador | contexto de uma thread só. Com qualquer um medindo, a dimensão roda em `sombra` (sinal). O observador serve de filtro antes de liberar classe nova |
| Chunkmap | funciona ligado ou desligado depois da correção H01. Cache desligado por `put` de outra thread = sinal |
| Parada (D-24) | ver as regras abaixo |
| Correções embutidas em mods | código de mod: não entra em região elegível nesta entrega |

**Parada com a fase aberta.** O perigo: salvar o mundo enquanto trabalhadores mexem em inventários grava estado pela
metade (item em dois lugares, ou em nenhum).
1. **Recusa no boot:** `modo: sombra_ordenada` ou `paralelo` exige `parada.ligado: true` (hoje o padrão é `false`,
   HoshiestarConfig.java:107). Sem o recurso de parada, um SIGTERM chama `stopServer()` direto da thread do gancho, com o
   tick correndo.
2. **`stop` no console:** lido em série; nada muda.
3. **SIGTERM com a parada ligada (caso normal):** o gancho pede ao laço que pare e espera; o laço termina o tick com a fase
   inteira e a própria thread do servidor salva.
4. **`stopServer` na própria thread do servidor** (crash-report, `stop`): nunca encontra fase RODANDO. Fase esquecida em
   MONTANDO é descartada, sem esperar ninguém.
5. **`stopServer` vindo de outra thread com fase RODANDO** (o gancho desistiu de esperar o laço, `G_ESGOTOU_LACO`; o vigia
   do Spigot pediu a saída, `G_VIGIA`, porque ele só olha a thread principal, que está parada na barreira; ou
   `G_SERVIDOR_NO_EXIT`): antes de qualquer outra coisa (o `stopServer` desliga plugins e salva jogadores antes dos
   mundos, MS:561-620) levanta a bandeira **fase abortada**: nenhum trabalhador pega outro tile, nenhuma fase nova abre,
   os pedidos delegados pendentes ainda são atendidos. Espera os trabalhadores pararem por um prazo curto e fixo (10 s,
   valor a confirmar no passo 6). Depois segue e salva. Se algum trabalhador não parou (preso num tile), o log lista as
   regiões e os tiles com trabalhador vivo: **conservação suspeita** ali. É o mesmo risco de hoje quando a thread principal
   está presa num tile e o gancho salva; limitado a um tile por trabalhador preso. **Nenhuma espera é sem limite** (regra
   da `MaquinaDeParada`); o limite duro da parada cresce 10 s, e isso entra no comentário do prazo do painel.
6. **"Não salvar" foi recusado** como saída para trabalhador preso: o `stopServer` já salvou os jogadores antes dos
   mundos, e os plugins guardam dados próprios; mundo antigo com jogador novo duplica item.

## 8. Determinismo

| O que muda contra o tick em série | Por que é aceitável |
|---|---|
| Ordem **entre** regiões (as elegíveis ticam depois das em série, em qualquer ordem entre si) | regiões distintas não se tocam (junção a 3 seções, G1, região inteira). Dentro da região a ordem é a de hoje |
| Adiados aplicados na junção, por chave estável do lote (menor chave de seção entre os tiles dele) e, no lote, na ordem de anotação | não depende de qual thread rodou o quê; os métodos adiados são `void` e só têm efeito no tick seguinte |
| Fila "não descarregar" adiada (só com o funil) | a descarga só roda no `WorldServer.tick` seguinte |
| Timings e Opis não medem os tiles paralelos | só medição |

Viram teste: `sombra` ≡ desligado; `sombra_ordenada` × desligado = zero; `sombra_ordenada` ≡ `paralelo` com 1 a 4 threads
(diferença aqui é erro de concorrência, achado sem depender de sorte). Nenhuma classe da entrega sorteia, então não há
montagem "com sorteio" nesta entrega.

## 9. Falhas e modo seguro

- **Exceção num tile.** O trabalhador pega o `Throwable` (escopo do `catch` de W:2575), guarda no lote e não tica o resto
  dele. Os outros terminam o lote corrente e não pegam outro. A junção aplica os adiados de tudo o que rodou. `ao_falhar:
  parar` (padrão, decisão 33): lança o `ReportedException` na thread do servidor, pelo caminho que já existe (crash-report
  e `stopServer` que salva, MS:769-797). `rebaixar`: registra a posição, a classe e o conteúdo do tile que falhou (e do
  inventário de origem ou destino, se houver); a dimensão fica em série **até o próximo boot**. Uma exceção no meio de uma
  transferência pode já ter perdido o item (como no desligado): antes de religar o paralelo, conferir a conservação.
- **Violação.** Mesmo caminho, chave `ao_violar` (padrão `parar`). A operação proibida já rodou, sozinha, na thread do
  servidor. Depois dela, conferir a conservação antes de religar; com `rebaixar`, série até o próximo boot.
- **Sinais** (a dimensão fica em `sombra` por `sinal_ticks`, com motivo no estado e no log): medidor ou observador ativos,
  aquecimento, mapa ausente ou todo suspenso, captura ligada, limitador do Spigot configurado para cortar, cache do chunkmap
  desligado, ouvinte registrado no tick, profiler de geração do DragonAPI ligado, espera da barreira acima do aviso.
- **Deadlock.** Trabalhador não segura trava nossa ao chamar código de jogo e nunca espera outro trabalhador. Ele só
  espera a thread do servidor no pedido delegado, e nesse momento ela não espera por ele: está atendendo. Nenhum código de
  terceiros que possa esperar a thread principal roda em trabalhador (é o que o pedido delegado garante). A thread do
  servidor não segura nada na barreira. Thread presa em código de jogo não tem saída limpa (como o stall de hoje, D-21): o
  despejo a cada 5 s mostra onde, o vigia do Spigot ou o SIGTERM levam à regra 5 da §7, e vale a regra do `CLAUDE.md` para
  cópia travada. Thread que morre com lote aberto: a espera vê e trata como falha.
- **Erro no nosso código em série** (classificação, despacho): registra e a dimensão roda em série naquele tick. **Chave
  geral:** `paralelo.ligado: false` = comportamento original; em execução, `hoshie paralelo modo` só vai para modo mais seguro.

## 10. Chaves do `hoshiestar.yml` e comando

```yaml
paralelo:
  ligado: false          # chave do recurso. false = nenhum gancho roda (comportamento original)
  modo: sombra           # sombra | sombra_ordenada | paralelo
  dimensao: 0            # uma dimensão só
  threads: 3             # threads de região (1 a 6); a thread do servidor não pega lote
  classes: ""            # subconjunto de: mesa, bau, bau_ender (e funil, se a onda 2 entrar). Vazio = fase vazia
  ao_falhar: parar       # exceção num tile de região: parar | rebaixar (série até o próximo boot)
  ao_violar: parar       # violação de dono ou invariante quebrado: parar | rebaixar
  leitura_amostra: 64    # em paralelo, confere 1 a cada N leituras (0 = não confere)
  aviso_espera_s: 5      # fatia da espera na barreira, no pedido delegado e no portão (só avisa; nunca libera)
  sinal_ticks: 1200      # depois de um sinal, ticks em sombra
  aquecimento_ticks: 600 # depois do boot, ticks em sombra antes de abrir a fase
```

Recusas no boot (motivo no log e no estado; o recurso fica desligado): orçamento desligado; `modo` diferente de `sombra`
com `parada.ligado: false`; dimensão inexistente; classe fora da lista; `world.rand` trocado por mod (BugTorched
`replaceRandomInWorld`; conferido pela classe do objeto); distância de simulação do hodgepodge ligada
(`addSimulationDistance_WIP`: ela envolve a mesma chamada `updateEntity` e o lote passaria por fora); observador não
revisado no `ChunkRequestEvent` do DragonAPI; firewall presente sem o método `allowTileEntityTick` alcançável; transformador
ou injeção sem veredito do passo 0. Jar de terceiros com SHA diferente tira só a classe afetada. Comando `hoshie paralelo`:
`estado` (modo efetivo e motivo, regiões elegíveis/total, tiles na fase/total, tempo da fase, espera por thread, todos os
contadores); `motivos [n]` (motivos de inelegibilidade mais comuns, com uma posição de exemplo, sem nome de jogador);
`modo <m>` (só para mais seguro). Só com `-Dhoshiestar.paralelo.teste=true`: `congelar <tick>` e `injetar
escrever|carregar|erro|dormir|quebrar|erro_serie` (`quebrar` = em série, quebra um tile já despachado; `erro_serie` =
exceção num tile em série com lotes já montados).

## 11. Passos de implementação

Regras: cada passo passa nos testes dele antes do próximo; testes isolados nos JDKs 8 e 21; servidor só em cópia de
`scripts/prepare-test-env.sh`, parada com `stop`; **código de concorrência é sempre revisado por agente diferente do que
escreveu**. Arquivos novos em `servidor/src/main/java/hoshiestar/paralelo/`, testes em `servidor/src/test/paralelo/`,
patches em `servidor/patches/`, doc em `servidor/src/docs/paralelo.md`. Modelos e esforço conforme a decisão 31.

| # | O que muda e arquivos | Teste isolado | Pronto quando | Quem (esforço) → quem revisa |
|---|---|---|---|---|
| 0 | **Levantamento que falta, sem código.** (a) Bytes finais, depois de todos os transformadores, de `World.updateEntities`, das classes da lista e de **todos os métodos que elas alcançam**: `getBlock`, `getTileEntity`, `provideChunk`, `chunkExists`, `func_147453_f`, `markTileEntityChunkModified`, `getEntitiesWithinAABB`/`selectEntitiesWithinAABB`, `getClosestPlayer`, `playSoundEffect`, `addBlockEvent`; para o funil, mais `CraftWorld.getBlockAt`, `CraftBlock.getState`, `CraftBlockState.<init>`, `TileEntity.writeToNBT` e os métodos de `ItemStack` que ele chama. (b) Um veredito por injeção (lista de partida na §6). (c) Observadores registrados no `ChunkRequestEvent`; se `allowTileEntityTick` do firewall tem efeito além da resposta; se o patch `TileUpdate` do DragonAPI está ativo nos bytes finais. (d) Por script que **não imprime config**, só "ligado/desligado": hodgepodge `addSimulationDistance_WIP` e `fixHopperVoidingItems`, BugTorched `replaceRandomInWorld`, EtFuturum `blockHopperInteraction`, limitador do Spigot, `skip-tileentity-ticks`, `loadChunkOnRequest`. (e) Ouvintes Bukkit e Forge alcançáveis pelas classes; SHA-256 dos jars; se a leitura do mapa Koloboke (CBHM:162) não escreve; se o `Nucleo` supõe "soma das regiões ≤ tick"; algum plugin salva o mundo por tarefa assíncrona? | — | tabela com um veredito por transformador, por injeção e por ouvinte; lista de classes fechada (com ou sem funil); cada item "verificado" ou "não verificado" | haiku coleta (baixo); opus conclui (alto) → coordenador |
| 1 | **Pré-requisitos sem fase.** H01 em três partes (CBHM:149-174 e :270-290, §4); contador de versão do mapa (CBHM:178, :239); `getEffectiveSide` (FMLCH:156-165); chaves `paralelo.*` com as recusas (`HoshiestarConfig.java`); esqueleto do comando | N threads lendo enquanto uma mexe no cache nunca recebem chunk de outra coordenada; **depois de `remove(c)` nenhum `get` devolve `c`**, com N threads lendo antes; thread de fora nunca muda `last1..4`; testes do chunkmap verdes (cuidado do D-63); leitura do yml; cada recusa; bytecode `static final` | desligado igual ao jar anterior (3 medianas, dentro do ruído do D-62) | fable o H01 (médio), sonnet o resto (médio) → outro fable (H01), opus (resto) |
| 2 | **Ferramentas de teste, só com `-D`.** `congelar <tick>` (para de ticar mundos no tick T e salva; troca a foto que quebra com o EndlessIDs, D-58); montagens determinísticas em `tools/b_monta_mundo.py` (baús, baús duplos, baús de ender, mesas; para a onda 2, anéis de funis com itens fixos e colunas baú → funil → baú; sem comparador e sem mob); **montagens hostis** (composter do EtFuturum em cima e na frente de funil; bloco de mod encostado em baú); grade de 16 a 32 mini-bases; opções em `tools/b_roteiro.py` | — | duas execuções desligadas, congeladas no mesmo tick, dão diferença zero em `tools/mundo_comparar.py` (prova a ferramenta) | sonnet (médio) → opus |
| 3 | **Classificador, guardas e `sombra`.** `Paralelo`, `Classificador`, `Guardas`, `Invariantes`; linha no laço (em `sombra` não desvia: só abre o contexto "lote R"); guardas em modo "contar" em todos os pontos da §4; `estado` e `motivos`. Vale aqui a lista de regras de bytecode da §2 | tabela de casos da classificação (comparador ou bloco de mod vizinho, baú aberto ou fechando, entidade com inventário, alvo fora da lista fechada, composter, 3×3 faltando, região sem dono, suspensa, em corte, ouvinte presente, item de mod, tile duplicado, tile inválido esquecido, firewall dizendo "não") e das guardas (mesma seção, outra região, sem região, thread estranha, fase fechada); **teste de bytecode com `javap` das regras (a), (b) e (c)** na classe final | no modpack: desligado × `sombra` = zero; contadores "seria ilegal" = 0 para as classes da onda 1 em 2 h de carga sintética; no log do boot, as três mensagens: Opis ("Found World.updateEntities()"), firewall ("Patched TileEntity tick gate at World#updateEntities") e o patch do DragonAPI; **profiler do Opis ligado uma vez** durante a sombra, sem exceção; quebrar um baú não gera violação; custo da sombra medido (JFR) | opus desenha a classificação (alto: é o núcleo da segurança), sonnet implementa (médio) → outro opus |
| 4 | **Adiados, junção e `sombra_ordenada`.** `Lote` (filas de evento de bloco, tick agendado, avisos ao cliente, "não descarregar"; tempo), `Juncao`, lote sujo, reconferência antes de cada tick, pergunta ao firewall no despacho; patches em WS:596, WS:643, WS:1256, W:999 e família, TR:120 (só guarda), CPS:174, SPM:482, EB:151 | a mescla mantém a deduplicação do original; nada perdido nem duplicado com erro no meio (modelo dos 21 casos adversários do orçamento); ordem independente da thread; tile invalidado depois do despacho é pulado | desligado × `sombra_ordenada` = zero na montagem determinística; conservação zero com a base dividida em regiões, inclusive depois de `stop` e religar; `injetar quebrar` num tile já despachado: o tile não tica e a conservação fecha (na onda 2: funil despachado com baú cheio em cima) | opus a junção (alto), sonnet o resto (médio) → fable (ordem e visibilidade) |
| 5 | **Núcleo de concorrência, sem Minecraft** (pode andar junto com 3 e 4). `ThreadRegiao`, `Fase` (estados), `Trabalhador`, barreira com espera em fatias, pedido delegado, portão, abortar, encerrar | regiões falsas com custo sorteado, 10⁶ ticks, 1 a 8 threads: cada lote exatamente uma vez, ordem interna mantida, fase só termina com todos; erro injetado; lote que dorme 20 s (despejo de 5 em 5 s, sem interrupção); thread que morre; **pedido delegado**: a operação roda na thread do servidor e nunca junto com outro trabalhador; dois pedidos juntos (menor chave primeiro); pedido aninhado; operação que só termina se estiver na thread do servidor; espera longa nunca libera a operação; pedido que suja outro lote; trabalhador sem monitor ao pedir; **thread estranha** no portão, com e sem o monitor, e com o monitor junto de um pedido delegado; **abortar** vindo de outra thread: ninguém pega outro tile, a espera tem prazo, e com um trabalhador preso a chamada volta no prazo dizendo quem ficou | tudo verde nos dois JDKs; revisão sem achado importante aberto | fable (alto) → outro fable |
| 6 | **Ligação e modo `paralelo`.** `rodarFase` em W:2594 (com `fimLaco()` antes), guardas em modo ativo, tempo para o `Nucleo`, marca do vigia, regra de parada em MS:561 (§7), portão de salvamento, comandos `injetar` | os do passo 5, repetidos com as classes ligadas ao jogo por interfaces (estilo `orcamento/Jogo.java`) | fase vazia no modpack com contadores em zero e custo medido; `injetar erro` → crash-report, parada limpa, mundo religa; `injetar erro_serie` → parada limpa (a fase em MONTANDO não segura a parada); `injetar escrever` e `carregar` → violação com as duas regiões e a pilha, operação rodada na thread do servidor (`isPrimaryThread()` verdadeiro), parada limpa; `injetar dormir 20000` → despejos; `stop` e SIGTERM com a fase rodando; os casos de parada do teste 3 da §12 | fable (alto) → outro fable (concorrência) e opus (mundo) |
| 7 | **Onda 1 em `paralelo`** (mesa, baú, baú de ender): só config e testes | — | testes 1 a 5 da §12 | sonnet com roteiro (médio); resultado estranho → opus (causa raiz) |
| 8 | **Onda 2, condicional** (funil). Só começa se o passo 0 deu veredito "seguro" para tudo no caminho do funil; senão fica para a entrega seguinte, com registro. ChestShop, WorldGuard e GriefPreventionPlus ouvem os eventos do funil: o teste roda numa cópia com os três em `!DISABLED-HOSHIESTAR`, **marcada como desvio da produção**; na cópia normal confere-se que o funil fica inelegível com o motivo certo. O hodgepodge já tira o evento Bukkit da puxada: testes e contagens de evento olham só o empurrão (TEH:371-372) e a coleta de item no chão (TEH:619-620) | — | testes 1 a 5 da §12 com anéis de funis; montagens hostis dão "inelegível, alvo não liberado" | opus revisa terceiros (alto), sonnet testa (médio) → opus |
| 9 | **24 h, sem regressão, ganho e relatório** (testes 6 a 8 da §12); build do zero; `src/docs/paralelo.md` | — | §13 | sonnet executa (médio), opus julga e escreve (alto) → fable, agente novo, faz a revisão final de concorrência do conjunto |

## 12. Testes obrigatórios no modpack

Todo número daqui é **indicação de carga sintética**, não medição de produção (decisão 30).

1. **Diferencial.** Mesmo mundo salvo; D1 e D2 desligados, S `sombra`, O `sombra_ordenada`, P1 a P4 `paralelo` com 1 a 4
   threads; todos congelados no mesmo tick; `mundo_comparar.py mca` com `--baseline D2`. Esperado: S = D1; O = D1; P1 a
   P4 = O.
2. **Conservação.** Método da Etapa 2.5 (`b_roteiro.py`, `mundo_contar.py`, livro-caixa do Mekanism), fotos a cada 2 min,
   cada mini-base numa região e as bases de mod como controle em série. Diferença de itens e de energia = 0, inclusive
   depois de `stop` e religar. Em teste, também a contagem de marcadores por região a cada tick. **Limite honesto:** com a
   onda 1 nenhum item se move dentro da fase; este teste prova que a máquina não estraga o que roda em série ao lado dela.
   A prova de conservação com item se movendo na fase só existe com o funil (onda 2).
3. **Bordas e parada.** Mini-bases a 4 seções (nunca se juntam) e a 49-63 chunks (juntam conforme a grade); 3 a 8
   andarilhos (`tools/bot_anda.py`) carregando chunks entre elas; jogador abrindo e quebrando baú; região entrando e saindo
   de corte; funil apontando para chunk descarregado (onda 2). Parada, sempre com a fase rodando: `stop`; SIGTERM com a
   parada ligada; boot com `parada.ligado: false` e `modo: paralelo` (esperado: recusa); SIGTERM com `injetar dormir` maior
   que `parada.espera_laco_segundos` (na cópia de teste, com esse prazo reduzido): o gancho aborta a fase, espera o prazo
   fixo, salva e o log lista o trabalhador vivo; o caso do vigia do Spigot (prazo dele reduzido na cópia). Em todos: a JVM
   sai, o mundo religa e a conservação fecha fora das regiões listadas como suspeitas. SIGTERM aqui é o caso (a) do
   `CLAUDE.md`; cada uso é registrado.
4. **Corrida e estresse.** 16 a 32 mini-bases, 5 repetições com 1 a 4 threads. Contadores que têm de dar 0: violações,
   pedidos delegados, espera de portão acima do aviso, cargas na fase, mudança do mapa de chunks e das listas na fase,
   sorteio e tick agendado na fase, lote sujo sem causa conhecida.
5. **Mutação.** Os seis `injetar` são detectados e levam à parada limpa (ou ao rebaixamento, com `rebaixar`); o mundo
   religa e a conservação fecha.
6. **Sem regressão.** Tudo desligado: 3 medianas de 5 min contra o jar anterior, dentro do ruído (D-62); diferencial
   desligado × jar anterior no mesmo nível de D1 × D2.
7. **Ganho (meta de máquina).** 4 regiões elegíveis com espera de teste de 20 ms cada: desligado, ~80 ms no laço de tiles;
   meta com 3 threads: ≤ 45 ms (2 rodadas de 20 ms mais folga). Conferir que o tempo da fase não aparece na conta de
   nenhuma região em série. Medir também o custo da fase vazia e da sombra. Na carga do modpack, registrar regiões
   elegíveis e tiles na fase; ganho esperado ali: nenhum.
8. **24 h.** Mini-bases + carga sintética + 4 andarilhos; conservação e `estado` a cada 2 h; JFR (`jcmd <pid> JFR.start ...
   filename=...`); sem exceção nossa, sem crash, sem deadlock, sem vazamento, contadores em 0.

## 13. Critério de pronto da primeira entrega

Isolados verdes nos JDKs 8 e 21; revisões de concorrência sem achado importante aberto; testes 1 a 6 e 8 da §12 passando
para as três classes da onda 1; teste 7 medido e registrado (a meta de 45 ms é indicação: se não fechar, vai ao relatório
com a causa); nenhum `kill` fora dos casos do `CLAUDE.md`; jar reconstruído do zero; `PROGRESSO.md` e `DIAGNOSTICO.md`
atualizados; relatório ao admin dizendo com clareza que o ganho em produção é perto de zero e o que precisa ser liberado
para haver ganho. **Funil:** se a onda 2 entrou, os mesmos testes com anéis de funis, na cópia com desvio marcado; se não
entrou, o relatório diz qual veredito do passo 0 a segurou.

## 14. Decisões

**Onde os dois desenhos discordavam (escolhido o mais seguro), já com os ajustes da revisão:**

| Ponto | Escolha | Motivo em uma linha |
|---|---|---|
| Onde despachar | dentro do laço original (seguro) | o teste que o DragonAPI reescreve, o cursor e o limitador rodam antes de o tile ticar, como hoje |
| Thread do servidor na fase | não pega lote; espera e atende pedidos delegados (ajustado na revisão) | todo tile paralelo roda com a mesma identidade de thread; o que precisa da thread principal roda nela |
| Reação à violação | pedido delegado, sem prazo que libere (ajustado na revisão) | a operação proibida roda sozinha e na thread que o código espera; nunca junto com outro trabalhador |
| Ouvinte de plugin | nenhum em trabalhador, sem lista no config (seguro) | plugin 1.7.10 não foi feito para outra thread, e o admin não consegue liberar por engano |
| Modos de prova | `sombra`, `sombra_ordenada`, `paralelo` (seguro) | separa erro de classificação, erro de ordem e erro de concorrência |
| Sorteio | proibido na fase nesta entrega; por lote só com a primeira classe que sortear (ajustado na revisão) | nenhuma classe liberada sorteia: seria código de etapa futura |
| Thread estranha | portão sem prazo que libere; exceção só para quem segura o monitor do Bukkit, atendida com tudo parado (ajustado na revisão) | tira a corrida sem criar espera sem fim |
| Leitura | conferida sempre em sombra, por amostra em paralelo (seguro) | pega alcance longo que a leitura de código não viu |
| Fornalha | fora da primeira entrega (mínimo) | é onde está o caminho de duplicação (flag estática, troca de bloco e de tile) |
| Funil | onda 2, condicional ao passo 0 (ajustado na revisão) | tem código de mod dentro do tick e é a única classe que move item |
| Tiles pendentes | proibição com pedido delegado; buffer por lote só com a fornalha (mínimo) | nenhum patch no `getTileEntity`, que é caminho quente, enquanto nenhuma classe cria tile |
| Fila de descarga (K30) | adiar o `remove` para a junção (mínimo) | mantém o efeito original em vez de descartá-lo |
| Itens de mod no funil | sempre em série, sem chave para relaxar (mínimo, mais estrito) | código de `Item` de mod não roda em trabalhador |
| Tile ticado duas vezes | conjunto por identidade na classificação, sem campo novo em `TileEntity` | mesma detecção sem mexer na classe que todos os mods estendem |
| Classificação velha | lote sujo roda em série, com reconferência de cada tile | fecha o intervalo entre classificar e abrir a fase |

**Do coordenador (reversíveis; registrar no `PROGRESSO.md`):** as escolhas acima; a ordem dos passos; a cópia de teste do
funil sem os três plugins ouvintes; com `removeErroringTileEntities` ligado o padrão continua `parar`; 3 threads por padrão;
`rebaixar` passa a valer até o próximo boot (a chave `rebaixar_maximo` saiu); prazo fixo de 10 s na parada forçada; avaliar
depois da entrega se a thread do servidor passa a trabalhar na fase (hoje ela fica parada, um núcleo a menos).

**Para o admin (difíceis de desfazer ou que mudam o rumo):**
1. **Aviso antes de começar:** a primeira entrega não acelera a produção (0,08% do tick); ela prova a máquina. Seguimos
   assim? Estimativa, não medida: liberar depois tiles de mod "locais" (~15% do tick) com 4 threads levaria 84 → ~75 ms;
   somando entidades que não são jogador, ~63 ms. Sem o Degrau 0 não chega a 50 ms.
2. **O funil deixou de ser obrigatório na primeira entrega** (onda 2, condicional). Sem ele, a primeira entrega não tem
   item se movendo dentro da fase: a conservação prova que nada ao lado quebra, não que mover item em paralelo é seguro.
   Aceita fechar a primeira entrega assim se o passo 0 segurar o funil?
3. **Plugins que ouvem funil e física de bloco** (WorldGuard, ChestShop, GriefPreventionPlus, ClearLag, AncientGates):
   enquanto ouvirem, funil (e depois fornalha) ficam em série em produção. Liberar ouvinte exige mexer em
   `isPrimaryThread()`, que 21 plugins consultam: decisão futura do admin, fora desta entrega.
4. **O paralelo passa a exigir `parada.ligado: true`** (o recurso de parada limpa por SIGTERM, hoje desligado por padrão).
   Quem ligar o paralelo em produção liga os dois.
5. **Parada forçada com trabalhador preso** (painel ou vigia matando um servidor travado): o plano **salva assim mesmo** e
   avisa no log as regiões suspeitas, como hoje acontece quando a thread principal está presa. A alternativa (não salvar)
   voltaria os mundos ao último autosave com os jogadores já salvos, o que duplica item. Confirma?
6. **Akashic-World-Firewall, Akashic-TickRelief, AkashicBellow e akashicmixer** (mods da rede) mexem no laço de tiles e nos
   métodos do mundo. Há fonte para revisão? Sem fonte, a revisão é por bytecode; sem veredito seguro, o recurso se recusa a
   ligar com eles presentes.
7. **Ligar em produção**, mesmo em `sombra`, só depois do relatório e com decisão do admin.

No código nada é difícil de desfazer: tudo atrás de `paralelo.ligado: false`, sem mudar formato de mundo nem classe base.

## 15. Não verificado

- Os vereditos do passo 0: o que cada injeção de terceiros faz por dentro (hodgepodge `MixinWorldGetBlock` e a puxada do
  funil, TickRelief, AkashicBellow, akashicmixer, sampler); se `allowTileEntityTick` do firewall tem efeito além da
  resposta; quais observadores existem no `ChunkRequestEvent`; como obter os bytes finais das classes numa cópia de teste.
- Os valores das configs das cópias (de propósito, não lidas): os padrões citados (`blockHopperInteraction`,
  `fixHopperVoidingItems`, `addSimulationDistance_WIP`, `replaceRandomInWorld`, patch `TileUpdate`) foram lidos do bytecode
  dos mods; limitador do Spigot, `skip-tileentity-ticks` e `loadChunkOnRequest` também não foram lidos.
- Com que frequência a corrida do cache de 4 chunks (H01) aconteceria; a simulação do revisor
  (`scratchpad/degrauB/tmp-corrida/h01_sim.py`) não foi rodada de novo aqui, só o código do `get` e do `remove` foi relido.
- Corpo dos ouvintes de plugin além do padrão do Skript; se algum plugin salva o mundo, registra ouvinte ou mexe no mundo
  por thread assíncrona durante o jogo, e com que frequência.
- Que a leitura do mapa Koloboke sem escritor seja segura entre threads; que `BlockChest.func_149951_m` e
  `checkForAdjacentChests` só alcancem 1 bloco; se algum mod lê `addedTileEntityList` direto ou injeta em
  `Block.onNeighborChange`.
- Se o `Nucleo` tem conta que supõe "soma das regiões ≤ tick"; como a regra 5 da §7 se encaixa nos estados da
  `MaquinaDeParada` sem um segundo `stopServer`; o prazo de 10 s; se `save-all` com os mundos congelados grava tudo no tick
  exato.
- Se o pedido delegado cobre todas as guardas com um mecanismo só (os métodos têm retornos diferentes); o custo dele não
  importa (é caminho de falha), o da guarda sim.
- Tamanho de pilha certo para as threads de região; duas threads inicializando classes ao mesmo tempo (o aquecimento em
  `sombra` reduz o risco, não prova).
- Todo custo (classificação por tick sem cache, guardas, barreira, sombra); a meta de 45 ms do teste 7 e as contas de
  ganho futuro são estimativas.
- Citado só pelos levantamentos, não relido aqui: corpo do tick da mesa. Nenhum cenário foi executado: tudo neste plano é
  leitura de código e de bytecode.

## 16. Revisão do plano

Três revisores adversários atacaram a primeira versão (notas completas em `scratchpad/degrauB/ataque-perda.md`,
`ataque-corrida.md` e `ataque-plugins.md`). Cada achado importante foi conferido de novo no fonte do fork ou no bytecode do
jar antes de mudar o plano. Resultado: 22 achados, **todos procedem**; 21 entraram como proposto ou com ajuste, e de um
(parada) uma parte da correção foi recusada.

**Achados importantes**

| # | Achado (lente) | Conferido em | O que mudou |
|---|---|---|---|
| 1 | Tile invalidado depois do despacho ainda ticava; funil quebrado engolia 1 item do baú de cima (perda; o mesmo ponto apareceu na lente de corrida) | `BlockHopper.breakBlock` (137-182) deixa pilhas de tamanho 0; `Chunk.removeTileEntity` (1019-1031) só invalida; TEH:136-160 e :524-600 | §2 "Reconferência na hora do tick"; §3 lote sujo; §5; teste no passo 4 (`injetar quebrar`) |
| 2 | Prazo de 5 s vencido liberava a operação proibida junto com outros trabalhadores (perda) | texto da própria §3 antiga | §3: prazo nunca libera; dois pedidos = um por vez, menor chave primeiro; portão idem. Testes no passo 5 |
| 3 | Operação proibida rodando no trabalhador trava para sempre se o código espera a thread principal (corrida, K10) | `javap` do Skript: `Task.callSync` faz `isPrimaryThread` → `callSyncMethod` → `Future.get` | §3: **pedido delegado**, a operação roda na thread do servidor; §4, §9 e §14 ajustados |
| 4 | SIGTERM, vigia do Spigot e gancho que desiste salvam o mundo com a fase aberta; o plano não dizia o que fazer (perda e corrida, dois achados) | `parada_ligado = false` (HoshiestarConfig.java:107); `ServerShutdownThread.run`; `MaquinaDeParada` (`G_ESGOTOU_LACO`, `G_VIGIA`, `G_SERVIDOR_NO_EXIT`); MS:561-620 | §7 "Parada com a fase aberta" (6 regras); recusa no boot na §10; teste 3 da §12; itens 4 e 5 para o admin |
| 5 | Invariante de tamanho da lista dava falso alarme e parava o servidor quando alguém quebrava um baú (perda) | laço em W:2542-2559 remove tile nulo e inválido; `World.removeTileEntity` (W:3296-3320) não tira da lista | §3: fotos no começo de `rodarFase`. Ao conferir, vi que o mesmo valia para `addedTileEntityList` e `loadedEntityList` (tiles em série criam tile e entidade): corrigido junto |
| 6 | Correção do H01 só cobria a leitura; gravação concorrente duplica um chunk no cache e o `remove` deixa chunk morto (corrida) | CBHM `get` (gravação na falta, 169-174) e `remove` (270-290): com [F, A, A, C], `remove(A)` deixa A na segunda casa | §4 linha H01 em três partes; testes novos no passo 1 |
| 7 | Composter do EtFuturum passava como "posição sem tile" e rodava código de mod que escreve bloco (plugins) | `javap` do `MixinTileEntityHopper` do EtFuturum (`FakeTileEntityProvider`) | §6: alvo = bloco de lista fechada; EtFuturum na trava do funil; montagem hostil no passo 2 |
| 8 | O lote pulava o portão de tiles do firewall; uma segunda chamada `updateEntity` no método desligaria o portão para todos (plugins) | `javap` do `PassiveItemTransformer` (mensagens "Refusing..." e "Patched...") e `WorldMutationGuard.allowTileEntityTick(TileEntity, World)` | §2: pergunta ao firewall no despacho; regra (b) de bytecode; recusa na §10; checagem do log no passo 3 |
| 9 | Regras de bytecode de `updateEntities` não estavam escritas: slot 9 do Opis, chamada única, janela do DragonAPI (plugins) | `javap` do Opis (`bipush 9`), do `TileUpdate` do DragonAPI (primeiro `isInvalid` ao primeiro `blockExists`) e bytecode do fork (`astore 9` antes da chamada) | §2 "Regras de bytecode"; por causa delas, a fase esquecida aberta é resolvida por estados e não por `try/finally` no método; teste com `javap` e profiler do Opis no passo 3 |
| 10 | "Código de mod não roda em trabalhador" era falso: mods injetam em `provideChunk`, `getBlock` e outros (plugins) | `javap` do `ChunkRequestEvent` do DragonAPI (4 estáticos, `fire`) e do `WorldGenInterceptionRegistry.onChunkRequested`; W:779-781 | §1 (regra nova de código de terceiros); §4 (linha do `ChunkRequestEvent`, som pelo método original); §6 (travas); passo 0 alargado; **funil rebaixado para onda 2 condicional** |

**Achados menores (todos incorporados)**

| Achado | O que mudou |
|---|---|
| Salvamento por thread estranha (`CraftWorld.save`) fora do portão | §3: salvamento entra no portão |
| `rebaixar` seguia o jogo depois de exceção no meio de uma transferência | §9: registra conteúdo, série até o próximo boot; a chave `rebaixar_maximo` saiu |
| Guardas aninhadas na carga de chunk (ponto seguro reentrante) | §3: a thread do servidor atendendo um pedido passa direto; teste no passo 5 |
| Exceção de tile em série deixava a fase aberta | §2: estados da fase; `injetar erro_serie` no passo 6 |
| Tempo da barreira caía na conta da última região em série | §7: `fimLaco()` antes de `rodarFase`; conferência no teste 7 |
| Distância de simulação do hodgepodge envolve a mesma chamada (conferido: `hodgepodge$updateTileEntities` embrulha `TileEntity.updateEntity`) | §10: recusa; passo 0 confere por script |
| hodgepodge já tira o evento da puxada do funil | passo 8: contar só empurrão e coleta |
| BugTorched pode trocar `world.rand` | já era recusa; passo 0 confere |
| Sorteio por lote era código de etapa futura | §4, §8, §12: saiu; extração por trabalhador = violação |
| `TileUpdate` do DragonAPI apaga a remoção de tile inválido | §2 e §6: G3 e G4 ignoram tile inválido |
| Notas soltas da lente de perda: funil com a tela aberta; "ejetor ou liberador" que nunca valia; frase errada sobre cano sem tick | §5 e §6 corrigidos |

**Recusado, e por quê**

- **"Se a fase continua aberta no prazo, não salvar os mundos"** (parte da correção do achado 4). Não procede como saída
  mais segura: o `stopServer` salva os jogadores **antes** dos mundos (MS:561-620) e os plugins guardam dados próprios;
  mundo voltando ao autosave com jogador atual duplica item. Ficou a outra proposta: abortar, esperar com prazo, salvar e
  listar as regiões suspeitas. Vai ao admin como item 5 da §14.
- **`try/finally` dentro de `updateEntities`** (forma proposta para a fase esquecida aberta). O problema procede; a forma
  foi trocada por estados da fase, para não mexer na tabela de exceções e nos slots de um método que três transformadores
  reescrevem.
- **Exigir `parada.ligado` em todos os modos.** A recusa vale para `sombra_ordenada` e `paralelo`. Em `sombra` nada muda
  no tick (a chamada original continua), então a exigência não protegeria nada.

**O que a revisão não mudou:** o desenho central (despacho dentro do laço original, região inteira, nenhum ouvinte em
trabalhador, três modos de prova). Os três revisores, cada um pela sua lente, não acharam caminho em que duas threads de
região mexam no mesmo item, inventário ou entidade no caminho normal; os buracos estavam nos caminhos de falha e de
parada, e na conta do que os mods injetam nos métodos vanilla.
