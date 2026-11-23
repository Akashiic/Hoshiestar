# Relatório do observador (Etapa 2) e portão "vai / não vai"

> 2026-11-23. Juiz: agente diferente dos que construíram e testaram. Nenhum servidor foi ligado para este relatório;
> tudo vem dos arquivos do teste (`etapa2/teste/`), do fechamento das pendências (`etapa2/pendentes/relatorio.md`),
> da revisão independente (`etapa2/rev/revisao.md`), do fonte do fork (branch `hoshiestar`, até `ed357cc7`) e de
> `javap` nos jars dos mods. **A decisão do portão é do admin**; aqui está só a recomendação.

> **Atualização 2026-11-23 (noite), mesmo juiz:** depois deste relatório entraram as correções do observador
> (contexto velho D-39 `e7953b04`, válvula D-44 `bd9e9e62`, parar durante o índice `27aeb38e`, doc `9b59ae56`), um
> jar novo feito do zero (sha256 `7161f795…a9668`, testes do build 126 + 24 + 56, 0 falhas), um boot de confirmação
> (`etapa2/confirmacao/RESULTADO.md`), a revisão de concorrência das correções (`etapa2/rev-conc/revisao.md`) e a
> varredura estática dos jars (`etapa2/varredura/`). As seções 1, 2, 3, 4, 7 e 8 foram atualizadas; a nova §2.4 lê a
> varredura. Nenhum servidor foi ligado para esta atualização; os casos da varredura foram conferidos com `javap`.

**Aviso que vale para todo número daqui:** é **carga sintética**, uma **indicação**, não medição de produção.
Máquina de 4 núcleos, mundo plano, bases postas por comando, bots parados e andarilhos em vez de jogadores, uma
rodada por condição. A pergunta P3 (distância real entre as bases) **continua sem dado real**.

Termos usados: **seção** = quadrado de 16×16 chunks; **região** = grupo de seções com chunk carregado a até 3 seções
uma da outra (regra copiada do Folia); **contexto** = "quem está executando agora" (um tile, uma entidade, um tick
agendado, um handler global...); **cruzar seção/região** = o código de um contexto mexeu numa posição de outra
seção/região; **artefato** = número que aparece por defeito da medição, não por comportamento do jogo.

## 1. O que rodou

- Jar `Hoshiestar-1.7.10-1.0.0-dev-server.jar`, sha256 `7d8de640…c89ef`, construído às 14:34, depois dos commits
  que corrigiram os 4 achados importantes da revisão (13:54 a 13:57). Que o jar contém as correções foi conferido
  pela hora do build, não pelo conteúdo.
- 292 mods, nenhum jar nosso em `mods/`/`plugins/`, boot em 34 a 36 s, todas as paradas com `stop`, nenhum `kill`,
  nenhum crash-report.
- Carga: layout a (4 bases a 80 chunks uma da outra), layout b (4 bases a 30 chunks), layout c (1 base com cano
  BuildCraft e cabo IC2 cruzando a borda de seção), cerca de 2.000 tile entities em dim 0, 9 bots parados e
  3 andarilhos. Rodadas: T1 (desligado, jar novo × 1.0.0-dev), T2 (observador, 584 s), T3 (observador + lista),
  T4 (os 8 recursos da Etapa 1 + observador + lista).
- **Confirmação (depois das correções):** jar `7161f795…a9668`, feito do zero; `javap` confirma no jar os campos e
  métodos das correções (`pctAquecimento`, `cancelarIndice`, `TRAVA_INDICE`; `fimTick*`/`fimTile`/`fimEntidade` no
  `WorldServer` e no `World`). Cópia nova, os 3 layouts juntos, 9 bots e 3 andarilhos, observador + lista ligados,
  `hoshie observar 600` **logo depois do Done** (36,2 s), uma rodada, `stop` limpo em 6 s, sem `kill`.

## 2. Quais mods cruzam seção e região na carga sintética

Contagens em 584 s de observação (cerca de 11.300 ticks), nas três rodadas completas T2c / T3 / T4, e na coluna
"Conf." a confirmação depois das correções (600 s, 11.157 ticks, observação desde o Done, então pega o login dos
bots e a geração de chunks dos andarilhos; não é a mesma janela das outras rodadas).

| Mod (contexto, acesso) | Cruza seção (T2c / T3 / T4) | Conf. | Por tick | Cruza região (T2c / T3 / T4 → Conf.) | Distância real (Conf.: máx.) | Leitura |
|---|---|---|---|---|---|---|
| ChromatiCraft (tile, lê bloco vizinho) | 28.959 / 29.319 / 29.043 | 25.273 | ~2,3 a 2,6 | 0 → 0 | 1 chunk (2) | vizinho na borda; fica dentro da região |
| Forestry (tile, lê bloco) | 18.048 / 18.095 / 18.217 | 17.041 | ~1,5 | 0 → 0 | 1 chunk (1) | idem |
| EnderIO (tile, lê tile/inventário vizinho) | 11.281 / 11.309 / 11.387 | 10.720 | ~1,0 | 0 → 0 | 1 chunk (1) | uma máquina na borda, uma vez por tick |
| Mekanism (tile, lê bloco) | 11.281 / 11.309 / 11.387 | 10.709 | ~1,0 | 0 → 0 | 1 chunk (1) | idem |
| gadomancy (tile, lê tile) | 7.110 / 7.047 / 7.036 | 5.713 | ~0,5 | 0 → 0 | 1 chunk (1) | idem |
| CoFHCore (tick agendado, lê bloco) | 63.694 / 2.675 / 600 | 207.567 | 0,05 a 18,6 | 40 / 2 / 0 → **0** | **1 chunk (1)** | varia muito entre rodadas (provável: blocos de mundo novo nos andarilhos; não verificado); o "381" sumiu |
| DragonAPI (só na amostra de pilha) | 331 / 98 / 86 | 1.686 | — | 0 → 0 | 1 chunk (1) | biblioteca chamada por outros mods |
| Vanilla "(nenhum)" (tick aleatório/agendado) | 36.202 / 27.453 / 25.877 | 263.736 (todos os contextos sem mod) | — | 2.757 / 1.590 / 1.030 → **0** de tick | **1 a 2 (2)** | os "481-527" sumiram |
| Vanilla "(nenhum)", contexto `jogador_rede` | — | 124 a 64+ chunks | — | → **74** | até 473 | **novo:** pacote de jogador/bot; origem não investigada (provável teleporte dos andarilhos) |
| AkashicFossils, HarvestCraft, etfuturum, Thaumcraft, DimensionalDoors (tick aleatório) | dezenas a centenas | 560 / 54 / 2 / 0 / 0 | <0,05 | 0 a 64 → 0 | 1 chunk (1) | os "241-481" sumiram |
| BuildCraft (cano na borda) | 0 / 0 / 0 (106 só na T2, que começou no boot) | 290 | ~0,03 | 0 → 0 | 1 chunk (1) | apareceu porque a observação começou no boot (cache do `TileBuffer` montado nela, §2.2) |
| IC2 (cabo na borda) | 0 em todas | 0 | 0 | 0 → 0 | — | **invisível** (§2.2, §2.4) |
| ThermalExpansion (406 mil acessos) | 0 | 4 | 0 | 0 → 0 | 0 a 1 | só a própria seção |

**Os artefatos sumiram.** Na confirmação não há mais leitura longa em contexto de tick agendado, tick aleatório,
tile ou entidade: todo acesso de mod fica a no máximo 2 chunks. Na faixa de 64+ chunks restam 124 acessos (de
7,2 milhões), todos do contexto `jogador_rede`, sem mod. Pares entre bases distantes: 1 acesso (CargaB → CargaI),
antes 3 pares de 12 a 18; os outros 9 pares entre regiões vão de base para seção de andarilho (o maior, 30 + 30
acessos para a seção (1,13)). Os acessos "fora da foto" que sobram (178 mil vanilla e 86 mil CoFHCore, tick agendado)
estão a 1-2 chunks: o vizinho caiu numa seção que não estava na última foto (feita a cada 30 s), não longe.

**Resumo:** nenhum código de mod foi visto tocando **outra região**. Todo acesso de mod que cruza seção é a um
vizinho a 1 chunk, e a regra de junção (até 3 seções viram uma região) já cobre isso. Alcance da atualização de
vizinho (N9 do estudo): 1 chunk; a margem de 1 seção basta **nesta carga**. Os ticks de bloco agendados e aleatórios
somam pouco (centenas de milhares de acessos contra 3,7 milhões de tiles).

De onde vêm os tiles do ChromatiCraft e do gadomancy (não estão entre os 7 mods da carga): não verificado
(provavelmente geração do mundo nas áreas dos andarilhos).

### 2.1 Artefato: contexto velho (achado novo deste relatório)

As leituras a 241 a 527 chunks e os "pares entre bases distantes" (CargaD→CargaC 18 acessos etc.) vêm de dois
pontos do `WorldServer` em que o jogo lê bloco **antes** de o gancho trocar o contexto, então a leitura é anotada no
contexto anterior:

- `tickUpdates` (linhas 770-774 do fonte decompilado): `this.getBlock(próxima entrada)` roda antes de
  `GanchosObservador.tickAgendado`, ainda no contexto da entrada anterior, que pode estar em qualquer lugar do mapa.
- `func_147456_g` (linhas 446-468 e 564): o contexto de tick aleatório é trocado só antes do `updateTick` do bloco
  sorteado e encerrado só no fim do laço; o `func_147467_a` e o `chunk.func_150804_b(false)` (checagem de luz, lê
  blocos pelo `World`) do chunk seguinte rodam no contexto do último bloco do chunk anterior. A ordem dos chunks é a
  do mapa de hash (`activeChunkSet_CB`), ou seja, aleatória no mapa.
- O mesmo vale para o achado 7 da revisão (sem `fimEntidade` no fim do laço de entidades), que explica o
  "ChromatiCraft 376 chunks".

Conferido no fonte; não reproduzido com um teste. Correção pequena e da própria Etapa 2: encerrar o contexto antes
da leitura (ou no começo de cada chunk/entrada), sem mexer na chamada do bloco.

**Corrigido (commit `e7953b04`):** fim do contexto antes do `getBlock` da próxima entrada, no fim de cada chunk do
tick aleatório, no `catch` do tile e da entidade e no fim do laço de entidades; teste de bytecode que falhava no jar
antigo. Na confirmação as leituras longas desses contextos sumiram (tabela acima).

### 2.2 O que o observador não vê (achado novo)

O observador só vê acesso que passa por um método do `World`/`Chunk`. Redes que guardam **referência** ao vizinho ou
ao tile não passam por ali:

- BuildCraft 7.1.63: `buildcraft.core.lib.TileBuffer` guarda o tile vizinho e só chama `World.getTileEntity`
  (`func_147438_o`) quando o tile guardado ficou inválido (javap). Por isso o cano apareceu só na T2, cuja
  observação começou no boot, quando o cache é montado.
- IC2 2.2.828a: `ic2.core.energy.EnergyNetLocal` não tem nenhuma chamada a `getTileEntity` (javap: 0); a energia
  anda pela rede montada na memória, num handler de fim de tick de mundo (`EnergyNetGlobal.onTickEnd(World)`).

Consequência: **"o observador não viu cruzar" não quer dizer "não acopla"**. Redes de mod (IC2, AE2, EnderIO,
Mekanism, BuildCraft) precisam de conhecimento do mod (módulo), não de medição por gancho. O perfil de produção já
punha essas redes nos 18% de handlers globais (D-13). A varredura estática (§2.4) mostra o mesmo desenho em oito mods.

### 2.3 Outras lacunas dos dados

- **Leituras sem origem não são atribuídas.** Em 584 s: 6,76 milhões de leituras de bloco em "mundo depois do
  tick", 2,51 milhões em "outro" e 1,43 milhão em "rede" ficaram só como contagem, sem mod e sem seção de destino
  (a amostra de pilha só roda para acesso que cruza seção). As escritas atribuídas a handlers globais são poucas
  (125 escritas de bloco, 503 leituras de tile, 192 "provável inventário" em "mundo depois do tick").
- **T3 = 0 cargas de chunk em todas as rodadas.** O gancho está em `originalLoadChunk`, que só roda para chunk
  **novo** (gerado). Chunk que já existe no disco entra por `ChunkIOExecutor.syncChunkLoad`/`queueChunkLoad`
  (`ChunkProviderServer.java:188-199`) e não passa por ali; a geração dos andarilhos aconteceu antes de cada
  observação começar. A frequência de carga dentro do tick (pendência 9) continua sem dado.
  **Confirmação:** com a observação desde o Done, a T3 contou **3.813 cargas** (chunks gerados pelos andarilhos):
  3.372 com pilha do hodgepodge e quase todas em contexto "outro" (sem origem); 74 em `jogador_rede`; 0 em contexto de
  tile ou entidade. Ainda só chunk **novo**: carga do disco segue fora (D-41, sem correção; limite documentado no
  commit `9b59ae56`). Leituras sem origem na confirmação: 13,3 milhões em "mundo depois do tick", 84,9 milhões em
  "outro", 1,3 milhão em "rede" (600 s): o D-46 continua.
- **Pares T2 entre regiões (112 na T2c):** quase todos de seções de base para seções de andarilho, distância de 15 a
  30 seções: mesmo artefato do §2.1.

### 2.4 Varredura estática dos jars (passo 7 do plano)

Coleta mecânica em `etapa2/varredura/` (378 jars de `mods/` e `plugins/`, 182.402 classes, 0 erros de leitura),
sem interpretação. As cinco perguntas e o tamanho bruto:

| Pergunta | Linhas | Jars |
|---|---|---|
| Q1 campo `static` de coleção em classe que referencia mundo/tile/chunk/entidade | 2.796 (756 não `final`; 1.184 só do PlayerAPI) | 139 |
| Q2 mod lê campo de lista do `World` direto (`loadedEntityList`, `playerEntities`...) | 397 | 75 |
| Q3 classe guarda `TileEntity` em campo, ou coleção em classe com nome de rede | 2.508 | 134 |
| Q4 handler de `ServerTickEvent` (94) ou `WorldTickEvent` (72) | 166 | 54 / 39 |
| Q5 `Thread`/`Executor`/`synchronized` em classe com mundo | 885 | 70 |

É uma lista de candidatos, não de problemas: a maior parte do Q1 é tabela de receita, renderização (cliente) ou
cache imutável. O que ela muda para o portão é mostrar **o desenho** das redes dos mods grandes. Conferido com `javap`
nos jars da cópia de teste, 8 casos:

| Mod | O que guarda (javap) | Onde roda | O observador vê? |
|---|---|---|---|
| IC2 2.2.828a | `WorldData.mapping` estático (`World` → `WorldData`); cada mundo tem um `EnergyNetLocal` com `Set<Grid>` e `Map<ChunkCoordinates, Tile>`; contadores estáticos `nextGridUid`/`nextNodeUid` | `TickHandler.onWorldTick` → `EnergyNetGlobal.onTickEnd(World)` (handler de mundo) | não (0 chamadas a `getTileEntity`, D-40) |
| Mekanism 9.10.48 | `TransmitterNetworkRegistry`: **um** objeto para todas as dimensões (`INSTANCE` estático) com `HashSet` de redes, redes a mudar, transmissores inválidos e órfãos; `TransporterManager.flowingStacks` estático e público (`Map<Coord4D, Set>`, chave com dimensão); `FrequencyManager.managers` e `MultiblockManager.managers` estáticos | `TransmitterNetworkRegistry.onTick` no fim do `ServerTickEvent` (uma vez por tick do servidor, todas as dimensões juntas) | não (estado em memória) |
| EnderIO 2.10.39 | `ConduitNetworkTickHandler` (um por `CommonProxy`) com `IdentityHashMap` de todas as redes de conduíte e lista de ouvintes; a rede se registra nele (`AbstractConduitNetwork` → `registerNetwork`) | `onServerTick` (início e fim do tick do servidor) | só o acesso pelo `World` |
| AE2 rv3-1093 | `TickHandler.INSTANCE` estático com fila global e `WeakHashMap<World, Queue>` de tarefas; `CraftingGridCache.CRAFTING_POOL`, `ExecutorService` estático (`newCachedThreadPool`) para cálculo de crafting | `onTick(TickEvent)` com o tipo **genérico**, por isso o Q4 dá 0 para o AE2 | as tarefas sim; o cálculo em outra thread, se tocar o mundo, cairia na T4 (0 na confirmação) |
| BuildCraft 7.1.63 | `TileBuffer` guarda o tile vizinho (D-40) | no tick do cano | só no primeiro acesso depois de montar o cache |
| Galacticraft 4.0 | `TickHandlerServer`: listas estáticas e não `final` de redes de energia (`networkTicks`) e de condutores a atualizar (energia, oxigênio, hidrogênio) | `onServerTick`/`onWorldTick` | não |
| MineFactoryReloaded 2.8.1 | `GridTickHandler` com três instâncias estáticas (`energy`, `redstone`, `fluid`), cada uma com `LinkedHashSet` de grades e conduítes | `ServerTickEvent` | não |
| Eln 1.24.8 | `Simulator` com listas de processos elétricos e térmicos de todo o servidor | `ServerTickEvent` | não |

**O que isso muda** (D-49): nos oito, a rede de energia, item ou fluido é um objeto em memória, **um por servidor**
(Mekanism, EnderIO, MFR, Eln, AE2) ou por mundo (IC2), andado num handler global e não no tick do tile. Nada disso
passa pelo `World`, então o observador não vê, e "0 acesso entre regiões" (D-38) não fala dessas redes. Para o
**Degrau A** isso não muda nada: é uma thread só, a rede continua ticando no handler. Para o **Degrau B**, cada
uma delas é estado compartilhado entre regiões: os handlers ficam na fase serial (já previsto), mas qualquer código
de tile que, na fase paralela, registre/invalide transmissor ou rede (Mekanism `registerOrphanTransmitter`/
`invalidateTransmitter`, EnderIO `registerNetwork`, IC2 `pendingAdds`) escreve num `HashSet`/`HashMap` comum sem trava.
Quais métodos de tile chamam esses registros, e em que fase: **não verificado** (precisa de busca de chamadores por mod).
Limites da varredura: o Q4 só pega handler com o tipo exato do evento (o AE2 escapa); nomes SRG/MCP; a hierarquia de
`TileEntity` só entre os jars varridos.

**Novo na lista de tiles, explicado pelo javap:** o IC2 `TileEntityBlock$1.tickCallback` faz
`world.loadedTileEntityList.add(this)` direto (campo `field_147482_g`), quando o tile termina de carregar; roda
na fila de callbacks do IC2, em contexto "mundo antes do tick" (serial). Por que apareceu só agora: provável que a
observação desde o Done pegou a carga das bases (não verificado).

## 3. Protótipo da lista de tiles (§4 do plano)

T3 (e igual na T4): 71,4 milhões de chamadas feitas pelo próprio `World`; **2,11 milhões de chamadas de fora**, todas
em contexto serial (antes/depois do tick de mundo e depois do tick do servidor); 0 em contexto de região; 0 sem
contexto conhecido (depois da correção do achado 4 da revisão, esse grupo é contado à parte); **0 modificações de
fora**; 0 trocas do campo feitas de fora; 0 chamadas de outra thread; 0 chamadas em lista aposentada.

Quem usa de fora: `Akashic-TickRelief` (`MOTickRegistry.rescan`: `get` e `size`, antes e depois do tick de mundo) e
`Mirai` (`ForgeServerProxy.diagnosticSnapshot`: `size`, depois do tick do servidor). O próprio Crucible troca o
objeto da lista 2.594 vezes em 584 s (cerca de 4 por segundo, `World.java:2510`), e o protótipo segue funcionando.

**Confirmação:** 67,6 milhões de chamadas do `World`; 1,48 milhão de fora (1.475.410 seriais, 0 em região, 122 sem
contexto); **282 modificações de fora** (`add` do IC2, §2.4), todas em contexto serial, 0 em região; 0 trocas do
campo de fora (1.596 pelo `World`); 0 de outra thread. Usuários: Akashic-TickRelief (`get`), IC2 (`add`, novo) e
Mirai (`size`). O critério 1 (descarta) pede modificação de fora **em contexto de tile/entidade**: continua 0. O
veredito se mantém.

| Critério do plano | Resultado | Vale? |
|---|---|---|
| 1. Descarta (modificação de fora em contexto de tile/entidade, ou troca do campo por mod) | 0 e 0 | não |
| 2. Desnecessária no Degrau B (≥ 99% de fora em contexto serial) | 100% | **sim** |
| 3. Prova a hipótese (leitura de fora em contexto de região, por até ~5 mods) | não há acesso em contexto de região | não se aplica |

**Veredito: critério 2. A lista por região é desnecessária no Degrau B** nesta carga: basta a regra já prevista
("a lista não muda de tamanho na fase paralela"). A hipótese "trocar o objeto da lista" não foi provada nem
descartada; ela só faria falta no Degrau C, e os dois usuários conhecidos entram na lista de handlers da dimensão
(lacuna 2). Ressalvas abertas da revisão: uso de dentro de `forEach`/`sort` não é contado (achado 6, subconta); o
relatório não mede quantas vezes a lista mudaria de tamanho na fase paralela (achado 12); mods que leem a lista só
quando um jogador faz algo (tela, comando) não foram exercitados.

## 4. Custo, contra a meta da §5 do plano

| Medida | Meta | Resultado (indicação) | Dentro? |
|---|---|---|---|
| Desligado: mediana do tick × jar 1.0.0-dev | diferença ≤ 1% | 24,6 × 24,5 ms (t 150-300 s, n=10, uma rodada; ruído entre janelas de ±8%) | sim, dentro do ruído; o ruído é maior que a meta |
| Desligado: JFR sem amostra em `hoshiestar.observador` | nenhuma | 1 de 10.418 (topo `Observador.fimTickServidor`); 0 alocação | quase: a chamada a `inicioTickServidor`/`fimTickServidor` fica sem guarda e volta na primeira linha |
| Ligado: custo | ≤ 5% do tick | estimativa do jar 0,56% (T2c), 1,11% (T3), 1,08% (T4); pior trecho 1,3 a 2,4% | sim |
| Ligado: JFR | — | 0,7 a 0,9% de **todas** as amostras; **1,9% (T2c), 2,4% (T3), 2,1% (T4) das amostras da thread do servidor** (contado aqui com `jfr print`) | sim, mas o custo real é cerca de 2× a estimativa do jar |
| Ligado: tick | — | parado × ligado: 22,8 × 24,6 (T2c), 21,2 × 25,0 (T3), 23,7 × 22,3 (T4) | o tempo de tick não separa o custo do ruído |

**Válvula (achado novo):** a primeira observação depois de cada boot parou sozinha nos primeiros 100 ticks (7,3 a
33,1% estimado). Causa provável, pelos números do próprio relatório: a primeira foto das regiões custa 41 a 73 ms
com o código ainda frio (as seguintes, cerca de 2,5 ms cada) e cai inteira na primeira janela de 100 ticks, junto com
as primeiras amostras de custo, também frias. A segunda observação no mesmo boot rodou 584 s a 0,56-1,11%. Não é
custo de verdade, mas é defeito de uso: o primeiro `hoshie observar` em produção pararia sozinho. Correção pequena:
não contar a primeira foto (ou a primeira janela) na válvula.

**Corrigido (commit `bd9e9e62`)** e confirmado: a primeira janela de 100 ticks é aquecimento e não conta para a
válvula (o custo dela vai para o relatório). Na confirmação, `hoshie observar 600` logo depois do Done rodou os 600 s
inteiros (11.157 ticks, 0 avisos); a primeira foto custou 85 ms.

**Custo na confirmação** (observador + lista, uma rodada, indicação): estimativa do jar **1,42%** médio, **2,76%** no
pior trecho, 3,08% na janela de aquecimento (meta 5%); JFR de 120 s: 80 de 10.499 amostras de CPU (0,76% de todas)
com quadro do observador, alocação 5 de 18.693. Tick (`forge tps`) depois de t=150 s: mediana 22,6 ms, p95 41,8, TPS
20. Não há janela "parado" nesse boot, então não há comparação ligado × parado. O custo subiu em relação à T3
(1,11% / 1,79%), provável por pegar login e geração de chunks; com a regra de ~2× entre JFR da thread do servidor e a
estimativa (D-43), o custo real ficaria perto de 3% médio: dentro da meta, sem folga grande.

**Revisão de concorrência (I1, importante):** a amostra de pilha de acesso vindo de **outra thread**
(`Nucleo.outraThread`, `ContagemLista.contarOutraThread`) não tinha teto por tick e ficava fora da válvula; teste
sintético no JDK 8: 166 a 242 ns por chamada (pilha de 20) e 763 a 860 ns (pilha de 120), contra 1 a 3 ns sem
gancho. Na carga sintética a T4 deu 0, então não pesou. **Corrigido no fork** (commit `a279c10e`: teto de 20 amostras
por tick somando T4 e lista, custo das outras threads dentro da válvula), junto com m1 (`3c11c356`), m4
(`2d1e8969`), m2 (`8db785ed`), m3 (`66f8d48a`) e m8 (`709ed6c2`); m5, m6, m7 e m9 ficaram como limite na doc
(`49020c28`). O jar da confirmação foi construído depois desses commits e contém a correção do I1 (`javap`:
`Nucleo.MAX_AMOSTRAS_OUTRA_TICK = 20`, `outraPilhaNs`; `Observador.falhaPedida`, `geracaoIndice`). **Essas seis
correções de concorrência não passaram por revisão de outro agente** (a revisão `rev-conc` é de antes delas), e o
teto do I1 não foi exercitado no boot (T4 = 0).

## 5. O que a carga sintética NÃO representa

- **Não é produção:** o tick ficou em 22 a 25 ms (servidor folgado, 20 TPS); produção está em 84 ms com 8 jogadores.
  Nenhum ganho de degrau pode ser medido aqui.
- **Bases não interagem entre si:** nada liga uma base à outra (sem cabo, cano, AE2 entre bases, sem jogador
  levando item). Por isso "0 acesso entre regiões" é o esperado, não uma prova.
- **Redes de mod estáticas:** só o cano BC e o cabo IC2 cruzam a borda, e os dois são invisíveis ao gancho (§2.2).
  Se itens e energia fluíram de fato: não verificado.
- **Sem jogador humano:** ninguém abre tela, usa habilidade, quebra ou põe máquina; os bots não fazem o que um
  jogador faz.
- **Uma dimensão só com carga** (dim 0). Nada sobre as outras ~60.
- **P3 sem dado real:** a carga mostrou só a geometria (§6, D-45 do diagnóstico): 4 bases a 30 chunks viraram
  **uma** região com 41% dos tiles; 4 bases a 80 chunks viraram 4 regiões; menor distância entre regiões 49 a
  79 chunks. Quantas regiões a produção teria continua desconhecido.
- **Uma rodada por condição:** o ruído entre rodadas não foi medido.
- **Entidades crescendo entre rodadas** (104 → 1.067 na foto A, mesmo com o mundo restaurado; na última foto, cerca
  de 390 em cada uma de duas bases do layout a): não explicado.

## 6. As três lacunas da pendência 14, à luz dos dados

1. **Dono de chunk carregado na fase paralela.** Sem dado: T3 ficou em 0 porque o gancho não vê carga de chunk que
   já existe no disco (§2.3). O dado muda o desenho num ponto: a "porta única de carga" tem de cobrir
   `loadChunk` → `ChunkIOExecutor` (síncrono e assíncrono), não só `originalLoadChunk`. A proposta do plano
   (o chunk fica com a região que o carregou e entra no mapa na junção; se cair em outra região, vira tarefa
   serial) continua de pé, sem confirmação.
2. **"Fase serial" no Degrau C.** Dado parcial: os usuários de fora da lista estão em contexto serial (Akashic-
   TickRelief, Mirai), e as escritas atribuídas a handlers globais são poucas. Mas 6,76 milhões de leituras por
   handler de mundo ficaram sem mod (§2.3), então **a lista P1 por dimensão não sai deste relatório**. A proposta
   (pausa por dimensão, só antes de handler não liberado que mexe nela) continua de pé; para produzir a lista P1, o
   observador precisaria amostrar a pilha também nas leituras sem origem dos contextos globais.
3. **Liberar ou isolar a região que falhou.** Nenhum erro de tick de tile/entidade apareceu no log em nenhuma
   rodada; o observador não conta erros (não há tabela para isso). Nenhum acesso real de mod cai em outra região,
   então, nesta carga, isolar uma região não prenderia ninguém, salvo as redes invisíveis do §2.2. O modo continua
   desligado pela decisão 33; nada a mudar agora.

## 7. Não verificado e não verificável aqui

**Fechado depois do relatório:** artefato do §2.1 (corrigido e as leituras longas sumiram na confirmação); válvula
na primeira janela (corrigida, primeira observação rodou 600 s); parar durante o índice (achado 5, `27aeb38e`, teste
com latch); jar testado contém as correções (agora por `javap` no conteúdo, não pela hora); varredura estática feita
(§2.4); achado 7 (contexto velho de entidade/tile, junto com o §2.1).

**Não verificado:**
- Origem dos 124 acessos de `jogador_rede` a 64+ chunks (até 473) na confirmação; por que o CoFHCore varia de 600 a
  207 mil cruzamentos de seção entre rodadas.
- Por que o `add` do IC2 na lista de tiles apareceu só na confirmação (o que ele faz está no §2.4; o motivo de antes
  dar 0 é provável, não conferido).
- Quais métodos de tile chamam os registros globais de rede da §2.4, e em que fase do tick (precisa de busca de
  chamadores por mod); os mods fora dos 8 conferidos.
- As seis correções de concorrência posteriores à revisão (`a279c10e` a `709ed6c2`): sem revisão de outro agente; o
  teto do I1 não foi exercitado com acesso real de outra thread.
- De onde vêm os tiles do ChromatiCraft e do gadomancy; por que as entidades crescem entre rodadas.
- Se itens/energia fluem pelo cano BC e pelo cabo IC2 da carga.
- Ruído entre rodadas (a confirmação também foi rodada única, sem janela "parado"); parada por SIGTERM com o
  observador gravando (item 1.G, m5); carga de chunk vinda do disco (T3 só vê chunk novo, D-41).
- Conteúdo do World patchado dentro de `binpatches.pack.lzma` (achado 13 da revisão; ficou como nota).
- Achados menores da revisão geral sem correção, com motivo na doc: 6 (uso dentro de `forEach`), 8 (montaria), 9 e
  10 (T3 e escrita direta), 11 (cópia extra no `embrulhar`), 12.

**Não verificável aqui:**
- Jogador humano real, bases reais, distância real entre bases (P3), carga de produção, comportamento com 30 a 200
  jogadores, ganho real de qualquer degrau na máquina de produção (7 vCores).
- Habilidade do ChromatiCraft pelo pacote de um cliente real; grupo do BetterQuesting criado pela tela do jogo.

**Fechado nesta etapa (pendência 16):** o ganho de energia do Mekanism depois de um stop é do próprio Mekanism
(aparece com o recurso desligado; a célula não marca o chunk ao mudar de energia, javap); ChromatiCraft recusa
destino fora da altura e respeita a proteção de spawn para quem não é op; BetterQuesting com dois bots: 2,28 milhões
de comparações, 0 divergências, pasta idêntica byte a byte.

## 8. Portão "vai / não vai"

### 8.1 As opções

| Opção | O que é | Trabalho | Risco | O que precisa existir antes | O que os dados desta etapa dizem |
|---|---|---|---|---|---|
| **Degrau A** (Etapa 2.5): orçamento de tick por região | uma thread; região que estoura o tempo passa a ticar 1 vez a cada 2, 3, 4 ticks | médio: regionizador (as fotos de região do observador já existem e funcionaram), tempo por região (o medidor já mede por seção), corte do tick por região no laço de tiles e entidades, alerta, interruptor | baixo: sem concorrência; o efeito é o mesmo do lag de hoje, só na base que pesa; pode mudar o ritmo de redes que atravessam duas regiões (a testar) | **aceite do admin de que base pesada tenha máquinas mais lentas (pendência 5, ainda aberta)** | a geometria funciona como o estudo previu (§5); o acoplamento entre regiões por código de mod foi nulo nesta carga; nada nos dados impede. Sozinho não chega a 20 TPS no perfil de produção (D-15) |
| **Degrau B**: fase paralela sincronizada (leitura principal do admin de "sincronização em tempo real", decisão 35) | 3 a 4 threads ticam as regiões em passo, com barreira; o resto em série | alto: rascunhos por thread para as 41 estruturas de risco alto (D-31), cache do mapa de chunks por thread (D-32), identidade de thread, listas que não mudam de tamanho, envio de chunks e rastreador fora da fase paralela (D-34), porta única de carga de chunk, vigia de deadlock, checagem de dono, teste de conservação e de 24 h, revisão de concorrência | alto: erro de concorrência é raro e silencioso (dupe); o ganho no pack real depende de quantas regiões existem (P3) | regionizador e tempo por região (os mesmos do A); varredura estática dos singletons (passo 7); correções do observador (§2.1, §2.3, §4) para que ele sirva de checagem | **a favor:** a lista por região é desnecessária no B (§3), um item de risco a menos; nenhum mod tocou outra região nesta carga. **Contra/aberto:** as redes de mod são invisíveis ao gancho (§2.2); bases a até 48 chunks viram uma região só (no layout b, 41% dos tiles numa região), o que limita o paralelismo se a produção for assim; nenhum ganho medido |
| **Misto por dimensão** (C onde liberado, B no resto; decisão 32) | cada dimensão no degrau mais alto que aguenta | o do B mais o do C (relógio por região, correio, pausa por dimensão, adapter de plugins) | o mais alto | B funcionando; lista P1 por dimensão | **nada para decidir ainda:** só dim 0 teve carga; a lista P1 não sai deste relatório (§6, lacuna 2). É um destino depois do B, não uma alternativa agora |
| **Parar no Degrau 0** | ficar nos 8 recursos da Etapa 1 | nenhum novo | nenhum novo | — | melhor caso do Degrau 0 sozinho: ~67 ms contra 84 (D-14/D-16, conta, não medida); o ganho da Etapa 1 em produção não foi medido. Não chega a 20 TPS |

### 8.2 Recomendação

**Vai, com o Degrau A (Etapa 2.5) como próximo passo, construído como alicerce do Degrau B; o Degrau B só depois,
com os pré-requisitos da tabela.** Antes ou junto do A, fechar a Etapa 2 com três correções pequenas do observador
(contexto velho do §2.1, válvula da primeira janela do §4, cargas de chunk vindas do disco do §2.3) e com a varredura
estática do passo 7 que ficou sem fazer.

Motivos:
1. É a ordem já aprovada pelo admin (degraus 0 → A → B → C), e os dados não deram motivo para mudar: nada na carga
   desaconselha o A, e o B continua dependendo de coisas que esta etapa não mediu (redes de mod, singletons,
   distribuição real das bases).
2. O A não tem risco de concorrência e constrói o que o B precisa de qualquer jeito: regionizador, tempo por região,
   alerta ao admin. Nada dele é jogado fora no caminho para o B.
3. O A funciona **sem saber** a distribuição das bases (ele reage à carga em execução). Como P3 continua sem dado
   real e o admin não executa medições, isso pesa a favor de começar por ele.
4. O B tem a maior promessa (perto de 50 ms com 4 threads, D-14) e é a leitura principal do admin, mas os dados desta
   etapa só tiram **um** item da lista de risco (a lista por região); os outros 41 de risco alto continuam.

Se o admin **não** aceitar máquinas mais lentas na base pesada (pendência 5), a recomendação vira: ir para o B,
começando pela parte comum (regionizador + tempo por região, sem cortar tick), e só depois a fase paralela.

### 8.4 Depois da confirmação e da varredura: a recomendação se mantém

**Recomendação (mantida): vai, com o Degrau A (Etapa 2.5) como próximo passo; o B só depois.** Motivos novos:

1. Os artefatos sumiram e o observador agora serve de checagem: na confirmação, nenhum acesso de mod passou de
   2 chunks e nenhum cruzou região; a primeira observação do boot rodou inteira; custo estimado 1,42% médio /
   2,76% pior trecho (JFR 0,76% de todas as amostras), dentro da meta. Isso confirma o D-38 sem o ruído do D-39.
2. A varredura **reforça** a ordem. Os oito mods de rede conferidos guardam a rede num objeto global (um por servidor
   ou por mundo) andado em handler de tick (§2.4, D-49). O A não sente isso (uma thread). O B precisa, para cada rede,
   saber quem a registra a partir do tick do tile, porque esses registros são `HashSet`/`HashMap` sem trava: é mais
   trabalho de módulo por mod no B, não menos. Junto com P3 sem dado e o ganho do B não medido, nada aqui puxa o B
   para antes do A.
3. A lista de tiles continua desnecessária por região no B (critério 2): as 282 modificações novas de fora (IC2) são
   em contexto serial.

**A etapa está fechada?** Quase. Os três itens do §8.2 (contexto velho, válvula, varredura) estão feitos; a carga de
chunk do disco (D-41) ficou como limite documentado, sem correção, e não impede o A (ele não precisa da T3). Falta
**uma** coisa pela regra do projeto: as seis correções de concorrência feitas depois da revisão (`a279c10e` a
`709ed6c2`, já no jar testado) precisam de revisão por outro agente, curta e só nelas. Sem ela, a recomendação é não
ligar o observador em produção. O resto da lista "não verificado" (§7) não bloqueia o portão: ou depende de jogador
real, ou é trabalho do B.

A decisão é do admin; aqui só a recomendação.

### 8.3 Item a avaliar (decisão 35): ajuste automático em execução

Registrado, sem desenho: ajustar com o servidor rodando o número de threads, juntar regiões ou desacelerar regiões
conforme a carga. Observações para quando for avaliado: o Degrau A já é uma forma de "desacelerar conforme a
carga"; no B, a pergunta seria distribuir regiões entre threads conforme o tempo medido de cada uma. Nenhuma linha de
código disso nesta etapa nem na 2.5 sem decisão.
