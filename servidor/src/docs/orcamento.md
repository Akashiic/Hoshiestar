# Orçamento de tick por região (`orcamento`) — Etapa 2.5, Degrau A

Plano: `docs/07-plano-etapa-2.5.md` (no repositório do projeto). O recurso divide o mundo em regiões, mede quanto cada uma
custa por tick, avisa o admin quando uma começa a pesar e, no modo `cortar`, faz a região que estoura o orçamento **ticar
1 vez a cada N ticks, inteira** (tiles, entidades, ticks de bloco agendados e ticks aleatórios no mesmo tick), para isolar
o lag dela do resto do servidor. Tudo numa thread só (a do servidor). Desligado (`orcamento.ligado: false`, o padrão) o
jar se comporta como o original: cada gancho é um teste de um campo `static final`. Todo número de simulação deste
documento é de relógio falso ou de carga sintética: **indicação, não medição de produção**.

## 1. O que faz (em linguagem simples)

- **Região**: o mapa é dividido em seções de 16 × 16 chunks (256 × 256 blocos). Seções com chunk carregado a até 3 seções
  uma da outra (distância de Chebyshev: o maior dos dois eixos) ficam na mesma região, e a junção é transitiva (uma
  cadeia de seções vira uma região só). É a regra do estudo Folia que o observador já usou; o código mora em
  `hoshiestar.regioes` (`Regionizador`, `Mapa`, `MapaSecoes`, `FotoMundo`). O observador passou a usar `MapaSecoes` e
  `FotoMundo` de lá; o cálculo do relatório dele (`observador.Regioes`, que também monta o resumo por dimensão) ficou com a
  cópia própria da regra, e o teste `TesteRegioesObservador` garante que as duas dão a mesma partição.
- **Mapa recalculado** no início do tick do servidor a cada `recalculo_ticks` (100) e, antes, no mínimo 20 ticks depois do
  último, quando uma busca acha tile, entidade ou tick agendado numa seção que não está no mapa (isso também cobre chunk
  carregado do disco, achado D-41). Chunk que não está no mapa = **sem região = nunca é cortado**.
- **Tempo por região**: o relógio (`System.nanoTime`) só é lido quando o laço **troca de região** (tile, entidade, tick
  agendado, tick aleatório de chunk) ou no fim do laço; o intervalo vai para a região que estava rodando. Mil tiles
  seguidas na mesma região = nenhuma leitura. (Indicação deste computador, sem Minecraft: 3 a 4 ns por chamada na mesma
  seção, ~37 ns trocando de região, a maior parte é o `nanoTime`.)
- **Servidor**: tempo de cada tick (do começo ao fim de `MinecraftServer.tick`), TPS em 20 s, média de 100 ticks, p95, p99 e
  máximo dos últimos 1200 ticks.
- **Por região**: custo por rodada (média do período de 100 ticks, e p95 das últimas 100 rodadas), custo por tick real
  (inclui o custo de pular entradas), rodadas por segundo de relógio em 20 s (o "TPS da região"), N, estado.
- **Estourou / voltou** (a cada 100 ticks), com histerese:
  - só age se a média do servidor passou de `alvo_tick_ms` (45) nos últimos 100 ticks;
  - a região acima de `regiao_ms` (10 ms por tick) passa a **N = ceil(custo por rodada ÷ 10)**, entre 2 e `n_maximo` (40);
    rodada acima de `pico_ms` (40) obriga N ≥ 100 ÷ `pico_fracao_pct` = 25 (a rodada pesada cai em no máximo 4% dos
    ticks e o p95 do servidor não a vê); fora do pico, N sobe no máximo ao dobro por avaliação (1 → 2 → 4...);
  - só regiões acima do orçamento são cortadas; região leve nunca é cortada "para abrir espaço"; servidor folgado: ninguém
    novo entra em corte;
  - **volta ao normal**: se o custo por rodada pede menos que N por 200 ticks, N cai à metade, passo a passo (25, 12, 6, 3,
    1); o mesmo vale se o servidor ficar abaixo de 80% do alvo por 200 ticks (até 1), **desde que a previsão do servidor
    com o N novo (média + custo por rodada × (1/N novo − 1/N)) continue abaixo de 80% do alvo** e nunca abaixo do N que a
    regra do pico obriga (25 para rodada acima de 40 ms). Sem caber, desce só até o N que o custo da região pede. Assim a
    região volta ao normal quando o servidor aguenta, sem oscilar (uma região de 150 ms não é solta pela folga; há teste
    para os dois casos: `TesteOrcamento.regiaoPesadaNaoOscila` e `TesteAdversario.nuncaVolta`, achado 1 da revisão);
  - entre 80% e 100% do alvo nada muda (faixa de histerese).
- **Sem fase fixa** (`Cadencia`): a região não roda em `tick % N == 0`. Um acumulador soma `1 ÷ (N + 0,0618…)`; os
  intervalos entre rodadas são N ou N+1 e as rodadas caem em todos os restos de qualquer módulo (sequência de Beatty).
  Máquina que age em `tempo % 20 == 0` não para de vez. A fase de cada região é espalhada (regiões diferentes não
  rodam todas no mesmo tick): ao passar de N = 1 para N > 1 a região recebe `Cadencia.faseInicial(id)`; o acumulador anda
  também no modo medir e não é zerado com N = 1 (antes, regiões que entravam em corte juntas ficavam com a mesma fase e
  rodavam sempre no mesmo tick; achado 2 da revisão, `TesteAdversario.fasesAlinhadas`). Funções puras: `Cadencia.tica(acc, n)` e `Cadencia.depois(acc, n)`.
- **Nunca entram no corte** (a região fica `protegida`, N = 1, e o motivo aparece no comando): região do spawn da
  dimensão 0 (`proteger_spawn`), áreas `nunca_cortar_areas`, dimensões `nunca_cortar_dimensoes`, região com tile de mod em
  `nunca_cortar_mods` (pedaço do nome do jar), região com jogador se `cortar_com_jogador: false`, e região **suspensa**
  (mapa velho, abaixo), e região que teve **erro nosso** num gancho (`erro interno`). Dentro de qualquer região, nunca
  cortados: **o jogador** e toda entidade na **cadeia de montaria** dele (a montaria, a montaria da montaria, quem ele
  carrega), entidade **morta** (o laço só a remove, como no original), raios e clima (`weatherEffects`), handlers de tick
  dos mods (redes globais: IC2, Mekanism, AE2...) e **eventos de bloco** (`func_147488_Z`; ver §1b).
- **Mapa velho**: uma seção ocupada que não está no mapa só pode **juntar** regiões. Até o recálculo, as regiões a até 3
  seções dela ficam **suspensas** (rodam todo tick; vale a partir do tick seguinte, para a região não ficar com metade do
  tick cortado). Tabela de seções desconhecidas cheia (4096): suspende tudo. Chunk descarregado só pode dividir.
- **Estado entre recálculos** (`Heranca`): os números das regiões mudam a cada mapa, então o estado fica preso às seções.
  Região idêntica à velha mantém o objeto (id e estatística); na **junção** vale o maior N e as maiores medidas e o menor id;
  na **divisão** a parte com mais seções fica com o id, as outras recebem cópia com id novo e o mesmo N (a histerese tira o
  corte de quem não pesa); a estatística recomeça em qualquer mudança de forma.
- **Alerta** (log + operadores online, `alerta_ops`): quando o custo por rodada passa de `alerta_ms` (25) e quando a
  região volta (duas avaliações abaixo de 80%); também quando começa e quando termina um corte. Texto: dimensão, caixa
  e centro em blocos, chunks/tiles/entidades, ms por rodada, ms do servidor e a ação ("esta ticando 1 a cada N", "só
  medindo: ticaria 1 a cada N", "protegida (...)"). Limitador: 1 por (região, tipo) a cada `alerta_intervalo_min` (5) e
  no máximo 10 por minuto no total. Alerta segurado pelo limitador **não se perde**: o estado (pesada, em corte) só muda
  quando o alerta sai, então ele é tentado de novo a cada avaliação (100 ticks) até sair, se ainda vale (achado 4 da
  revisão; `TesteAdversario.alertaPerdido`: 12 regiões pesadas juntas, antes só 5 recebiam, agora as 12, no 2º minuto).
  Nunca nome de jogador nem IP. (Diferença do plano: o custo do alerta é por **rodada**,
  o que a região custaria por tick sem corte; com a região cortada o custo por tick real cai e o alerta sumiria.)

## 1b. O corte (modo `cortar`)

**Quando:** só com `modo: cortar`, só com o servidor acima de `alvo_tick_ms` e só para a região acima de `regiao_ms` por
tick real (regras da §1). A decisão "roda neste tick?" de cada região é feita **uma vez, no início do tick do servidor**
(`Nucleo.inicioTick`), e vale para o tick inteiro: uma região nunca roda metade de um tick. Num tick em que a região não
roda, ela pula **tudo** o que é dela; num tick em que roda, roda tudo:

| Laço (patch `// Hoshiestar - orcamento`) | O que pula na região que não roda | Onde exatamente |
|---|---|---|
| `World.updateEntities`, entidades | o tick da entidade (`continue` antes do teste de montaria e do update) | depois da marca do vigia; entidade morta, jogador e cadeia de montaria sempre rodam |
| `World.updateEntities`, tiles | o update da tile | depois do teste de nulo, de `isInvalid()` (limpeza de tile de chunk descarregado segue igual), de `canTileEntityTick` (o `tileentities.yml` do Crucible continua valendo por cima) e de `blockExists` — **fora do trecho que o DragonAPI reescreve** (do 1º `isInvalid` ao 1º `blockExists`) — e antes dos ganchos do medidor e do observador |
| `WorldServer.func_147456_g` | o chunk inteiro: luz, clima (gelo, neve), raio e ticks aleatórios | depois do teste de descarga do chunk |
| `WorldServer.tickUpdates` | as entradas de tick agendado vencidas da região | ver abaixo |

**Ticks agendados, sem perder e sem rajada.** Com corte neste tick (e só com `forcar = false`), a puxada da fila tem
duas partes: (1) `PuxadaAgendada.selecionar` anda pela `TreeSet` em ordem, **sem mexer nela**, e seleciona as vencidas
das regiões que rodam, até o teto do CraftBukkit (1000, ou tamanho/20 acima de 20 mil; conta só as que saem); as vencidas
de região cortada são puladas e **ficam onde estão** (na `TreeSet` e no `HashSet`); (2) o próprio patch tira cada
selecionada com as **mesmas três chamadas do original** (`TreeSet.remove`, `HashSet.remove`, `List.add`). Por que assim:

- `scheduleBlockUpdate` continua sem duplicar (o `HashSet` ainda tem a entrada guardada);
- o salvamento do chunk (`getPendingBlockUpdates`, `AnvilChunkLoader:404`) continua vendo e gravando as guardadas;
- o **Hodgepodge** (`speedupPendingTickLookup`, ligado por padrão e no modpack) mantém um índice de ticks por chunk com um
  `@Redirect` em todo `TreeSet.remove` de `tickUpdates` e um `@Overwrite` de `getPendingBlockUpdates`; tirar a entrada
  por um iterador nosso deixaria o índice velho (entrada já rodada gravada de novo no chunk). Com as chamadas do original
  dentro de `tickUpdates`, o redirect dele as vê. O teste isolado tem um índice que só vê `TreeSet.add/remove` e confere
  que ele fica igual à fila em todo tick;
- ordem: quando a região volta a rodar, as entradas dela são as primeiras da fila e saem na ordem da `TreeSet` (tempo,
  prioridade, número de criação), a mesma do jogo; no máximo o teto por tick. Uma cadeia que se reagenda (relógio de
  redstone) reagenda a partir de "agora": roda no máximo 1 vez por rodada (não acumula rodadas atrasadas);
- **teto reservado** (achado 3 da revisão): o teto é primeiro das regiões que rodam normal (e dos pontos fora de região);
  na rodada de uma região cortada, o acúmulo dela (as entradas mais velhas da fila) só usa a **sobra** do teto, as mais
  velhas primeiro, e a saída fica na ordem da fila. Antes, o acúmulo ocupava o teto inteiro e atrasava os ticks agendados
  de regiões **não** cortadas (`TesteAdversario.tetoFome`: 100 faltas em 2500 ticks, agora 0). O que não cabe fica na
  fila (nada se perde) e sai nas rodadas seguintes. Por isso a seleção roda também no tick da rodada de uma região
  cortada (ninguém pulado, só a reserva). Limite: se só as regiões normais já enchem o teto todo tick (servidor muito
  atrasado), o acúmulo da cortada espera até sobrar (o teto sobe para tamanho/20 acima de 20 mil entradas);
- sem corte neste tick (modo medir, servidor folgado, ninguém em corte), com `forcar = true` ou desligado: **o laço
  original**, sem mudança.

**Entidade ou tile que muda de região no meio do tick:** cada objeto é decidido pela posição dele quando o laço chega
nele; como a decisão de cada região é fixa no tick e cada laço visita cada objeto uma vez, ninguém tica duas vezes no
mesmo tick; no tick seguinte vale a região nova.

**Chunk descarregando com a região em corte:** a limpeza de tiles (`field_147483_b`/`isInvalid`) e de entidades
(`unloadedEntityList`) roda antes dos nossos testes, como no original; as entradas agendadas guardadas continuam na fila e
são gravadas com o chunk (como as pendentes no jogo). Quando a região roda com o chunk ainda fora, o jogo as descarta
(chunk não existe: `scheduleBlockUpdate` recusa), e a recarga do chunk as traz de volta do NBT: rodam uma vez só. As
nossas estruturas só guardam coordenadas e estado de região; a seleção da puxada é esvaziada no fim de cada puxada.
Nada fica órfão.

**Erro nosso num gancho** (`Nucleo.falhaNoPonto`): a região do ponto vira `protegida (erro interno)` e roda todo tick
desde aquele instante (inclusive no resto do tick: naquele tick a região pode rodar só a parte depois do erro, um
"meio-tick" de região; cada objeto roda inteiro ou não roda, então não há perda nem duplicação; escolha mantida de
propósito, coberta por teste, achado 9 da revisão), e continua protegida nos recálculos seguintes; o erro vai para o log
com limitador (1 por região a cada `alerta_intervalo_min`, no máximo 10 por minuto). Mais de 20 erros, erro sem região
achada, erro no início/fim do tick ou na seleção da puxada: o orçamento inteiro desliga e tudo roda (o que já tinha sido
pulado naquele tick roda no tick seguinte).

**Aviso de boot:** se algum mundo tiver `max-tick-time` do Spigot abaixo de 1000 ms, sai um aviso (o limitador pode parar o
laço no meio de uma rodada; o resto da região roda na rodada seguinte).

### Argumento de segurança (sem perda nem duplicação de item, energia ou bloco)

1. **Pular o tick não cria nem destrói nada**: a tile ou entidade pulada fica exatamente como estava (nenhum meio-tick:
   pulamos antes de chamar o update). É o mesmo efeito que o `tileentities.yml`/`entities.yml` do Crucible e o limitador do
   Spigot já produzem em produção, só que para a região inteira de uma vez.
2. **A região é pulada inteira no mesmo tick**: máquina e tick agendado não saem de passo um do outro (uma prensa e o
   relógio de redstone que a aciona andam juntos). Rede física não cruza borda de região: chunks vizinhos carregados
   ficam a menos de 3 seções e a junção os põe na mesma região (plano §4); a única janela é o mapa velho, coberta pela
   suspensão (a região a até 3 seções de uma seção desconhecida roda todo tick até o recálculo).
3. **Ticks agendados**: nenhuma entrada sai da fila sem rodar e nenhuma roda sem sair; as guardadas ficam nas duas coleções
   do jogo (e no índice do Hodgepodge), então gravação, recarga e deduplicação são as do jogo. Provado em 1 milhão de
   operações aleatórias contra um modelo e no diferencial "alcançar" (§5).
4. **Inserir em inventário de tile parada é seguro**: funil, cano, rede global e jogador inserem sem precisar do tick da
   tile (já acontece com o `tileentities.yml`). O jogador mexe na tela de uma máquina parada pelo pacote de rede, com a tile
   inteira.
5. **Desligado = original**: cada patch é `Orcamento.LIGADO && ...` com `LIGADO` `static final`; com ele falso, o salto cai
   no código original (conferido no bytecode, §5). Modo medir ou sem corte no tick: todos os ganchos devolvem "roda" e a
   puxada usa o laço original (conferido no diferencial do mundo falso).
6. **Erro nosso não corta nada**: devolve "roda".

### Consequências para o jogador da base cortada

- Tudo da base anda N vezes mais devagar (com N até `n_maximo`, 40): máquinas, fornalhas, plantações por tick aleatório,
  mobs e itens no chão (que somem mais tarde, nunca mais cedo), projéteis, funis, relógios de redstone. O jogador em si
  anda, pega item e abre telas normalmente; a tela da máquina atualiza devagar.
- Máquina que calcula "quanto tempo passou" pelo relógio do mundo (`getTotalWorldTime`, que anda para todos) compensa na
  rodada: não fica mais lenta e pesa na rodada; a região continua pesada e sobe ao N máximo, e o alerta diz onde é.
- Redes globais (energia/itens em handler de tick: IC2, Mekanism, EnderIO, AE2...) continuam a ritmo cheio; a tile parada
  enche o buffer e a demanda cai. Tile nova que só entra na rede no primeiro tick dela entra até N ticks depois.
- Pistão: o evento de bloco (empurrar/recolher) não é cortado, a tile do bloco em movimento sim; um pistão que liga e
  desliga dentro de uma rodada é tratado pelo jogo como pulso rápido (o caminho do jogo para pistão de pulso curto). **Não
  verificado** no modpack.
- Item que o jogador joga no chão da base cortada: o atraso de coleta (`delayBeforeCanPickup`, 40) só anda no tick do
  item, então com N = 25 ele só pode ser pego de volta depois de ~50 s (40 rodadas). Item que cai de máquina ou de bloco
  quebrado segue a mesma regra. Nada se perde (lido no fonte; não verificado no jogo).
- Animal na corda: o estado da corda (`updateLeashedState`) roda no tick do animal; o animal não acompanha o jogador e a
  corda arrebenta ao passar de 10 blocos (o item da corda cai no chão, sem perda). Lido no fonte; não verificado no jogo.
- Flecha, boia de pesca e outros projéteis param no ar entre rodadas e andam um passo por rodada (a boia do jogador em
  pesca na base cortada fica lenta). Lido no fonte; não verificado no jogo.
- Quem quiser duas pontas de uma ligação sem fio no mesmo ritmo: `nunca_cortar_mods` (ex.: teletransportador, entangloporter,
  transceptor, ponte quântica) ou `sempre_junto_dimensoes`.

## 2. Chaves do `hoshiestar.yml` (seção `orcamento`)

| Chave | Padrão | O que é |
|---|---|---|
| `ligado` | `false` | liga os ganchos (lido só no boot; vira `static final`). Desligado = comportamento original |
| `modo` | `medir` | `medir`: calcula e avisa sem cortar. `cortar`: aplica o corte (§1b) |
| `alvo_tick_ms` | 45 | média do tick do servidor (100 ticks) acima da qual o orçamento age (10 a 50) |
| `regiao_ms` | 10 | quanto uma região pode usar por tick real (0,5 a 50) |
| `pico_ms` / `pico_fracao_pct` | 40 / 4 | rodada acima disto obriga N ≥ 100 ÷ fração |
| `n_maximo` | 40 | maior N (2 a 200) |
| `recalculo_ticks` | 100 | recálculo periódico do mapa (40 a 2400) |
| `proteger_spawn` | `true` | a região do spawn da dimensão 0 nunca é cortada |
| `nunca_cortar_dimensoes` | vazio | números separados por vírgula |
| `nunca_cortar_areas` | vazio | `dim:x1,z1,x2,z2` em blocos, separadas por `;` |
| `nunca_cortar_mods` | vazio | pedaços do nome do jar, separados por vírgula (ligações sem fio: teletransportador, entangloporter...) |
| `sempre_junto_dimensoes` | vazio | dimensões que viram uma região só |
| `cortar_com_jogador` | `true` | região com jogador pode ser cortada (o jogador em si nunca é cortado) |
| `alerta_ms` / `alerta_intervalo_min` / `alerta_ops` | 25 / 5 / `true` | alerta |

Valor fora da faixa vira o padrão e sai um aviso no log (só com `ligado: true`). Listas em texto, porque o leitor de
config não tem lista. A chave do plano `orcamento_regiao_ms` ficou `regiao_ms` (já está dentro da seção `orcamento`).

## 3. Comando `hoshie regioes`

Console, RCON ou operador de nível 4 (a mesma regra do `hoshie`). Lê só o retrato publicado a cada 100 ticks
(até ~5 s de atraso); mudanças vão por fila e valem no tick seguinte.

- `hoshie regioes`: modo, TPS e p95 do servidor, contagens, as 10 regiões mais pesadas;
- `hoshie regioes <id>` e `hoshie regioes aqui`: detalhe e as 5 classes de tile mais comuns;
- `hoshie regioes modo medir|cortar`: troca o modo (vale no tick seguinte).

**Ferramentas de teste** (só com `-Dhoshiestar.orcamento.teste=true`; sem a propriedade os subcomandos nem existem):
`teste <id|aqui|x z> <ms>|parar` (espera ocupada de `ms` na primeira tile da região, uma vez por rodada; conta como custo
dela e some quando ela é cortada), `registro <s>` (CSV por tick em `hoshiestar/orcamento-registro-*.csv`, colunas
`tick,regiao,rodou,ms`; região -1 = o tick do servidor inteiro), `contar <id> <arquivo>` (soma itens `id:meta` e energia
varrendo o NBT das tiles e dos itens no chão da região) e `foto <arquivo> <tick do mundo> [ids a excluir]` (NBT das tiles e
soma dos blocos de cada chunk no tick exato do mundo, fora das regiões excluídas). Arquivos só em `hoshiestar/`, nome
sem `/` nem `..`.

## 4. Onde está o código

| Parte | Onde |
|---|---|
| Regionizador (uma thread) | `hoshiestar.regioes`: `Regionizador`, `Mapa`, `MapaSecoes`, `FotoMundo` |
| Núcleo sem Minecraft | `hoshiestar.orcamento`: `Nucleo`, `Cadencia`, `Estado`, `Heranca`, `Retrato`, `Parametros`, `LimiteAlertas`, `ComandoRegioes`, `RegistroCsv`, `PuxadaAgendada` (seleção dos agendados), `Isencao` (cadeia de montaria) |
| Ponte com o jogo | `GanchosOrcamento` (ganchos e `JogoMinecraft`), `ComandoOrcamento`, `FerramentasMinecraft` |
| Porta dos patches | `Orcamento` (`LIGADO` é `static final`) |
| Patches `// Hoshiestar - orcamento` | `MinecraftServer.tick` (início e fim), `World.updateEntities` (entidade com `continue` e fim; tile com `continue` e fim), `WorldServer.tickUpdates` (seleção + laço do `else`, entrada e fim), `WorldServer.func_147456_g` (chunk com `continue` e fim) |

Os ganchos de corte têm a forma `if (Orcamento.LIGADO && !GanchosOrcamento.x(...)) continue;`; os outros,
`if (Orcamento.LIGADO) GanchosOrcamento.x(...)`. Sem `try/catch` no ponto de chamada (o `try/catch` fica dentro; um quadro
de pilha a mais em `World.updateEntities` quebraria o padrão que o Opis procura) e **nenhuma variável local nova**
(`maxLocals` igual: 14 em `updateEntities`, 12 em `tickUpdates`, 23 em `func_147456_g`; o Opis injeta com os índices 2 e
9). O gancho do tile fica numa linha própria, longe da chamada de `TileEntity.updateEntity()`, que continua uma só e com o
`ALOAD` logo antes (o transformador do Akashic-WorldFirewall troca essa chamada). Convivência conferida por varredura
estática dos jars do modpack (não em boot): DragonAPI (`TileUpdate`, e `BlockTickEvent`, que só troca as chamadas de
`Block.updateTick`), Hodgepodge (`PendingTickIndex`, ativo; `SimulationDistance`, desligado no config do modpack:
`addSimulationDistance_WIP=false`, **não verificado ligado**), Akashic Bellow (`DeepTicks`), Et Futurum (`randomTickSpeed`,
`@ModifyConstant`), Opis e o firewall: nenhum casa por posição ou por variável local que os patches mudem.

**Threads:** só a thread do servidor muda estado. O comando (inclusive RCON) lê o `Retrato`, imutável e publicado por um
campo `volatile`, e pede mudanças por uma `ConcurrentLinkedQueue` que a thread do servidor esvazia no início do tick.
Nenhuma trava. Não há código de threads de região (isso é o Degrau B). Mesmo assim é o único ponto entre threads desta
base e deve ser lido pela revisão de concorrência.

## 5. Testes (`src/test/orcamento/teste-orcamento.bash`, JDK 8 e 21)

`TesteRegioes` (junção, cadeia, divisão, bordas de seção e de coordenada, mais de 256 dimensões, sempre-junto, ordem dos
chunks), `TesteRegioesObservador` (diferencial contra a regra do observador em 3000 fotos aleatórias), `TesteCadencia`
(restos da sequência de Beatty para N de 2 a 40 e módulos de 2 a 100, intervalos N e N+1, N por custo, limitador),
`TesteOrcamento` (relógio falso e jogo falso: tempo por região, estoura, volta, histerese, região pesada que não oscila,
isentos, modo medir, herança na junção e na divisão, mapa velho e suspensão, alertas, ferramentas de teste, falha, texto do
comando), `TesteOrcamentoConfig` (com `ligado` true e false: chaves, faixas, registro, `static final`),
`TesteOrcamentoBytecode` (os 12 ganchos no bytecode real, todos atrás de `Orcamento.LIGADO`; a forma `continue` dos três
ganchos de corte, com o salto caindo no passo do laço; o gancho do tile depois do `blockExists` e antes do medidor; a
puxada só com `forcar = false`, o laço original quando ela devolve falso, o `else` com `TreeSet.remove`/`Set.remove`/
`List.add`; `maxLocals` iguais; o padrão do Opis e do firewall intacto) e `BenchOrcamento`.

`TesteCorte` (mundo falso que roda os quatro laços na ordem e na forma dos patches, chamando o mesmo `Nucleo` e a mesma
`PuxadaAgendada`): região cortada pula tile, entidade, agendado e aleatório no mesmo tick (1000 ticks, ~40 rodadas a N=25)
e as outras nunca perdem nada; jogador montado, a montaria e quem ele carrega rodam todo tick; entidade morta em região
cortada é removida no mesmo tick; entidade que muda de região no meio não tica duas vezes e passa ao ritmo da região nova;
o relógio agendado da região cortada roda 1 vez por rodada (sem rajada); TreeSet = HashSet e um índice estilo Hodgepodge =
fila em todo tick; modo medir e modo cortar sem estouro dão o **mesmo registro de execução** do mundo sem ganchos; a
seleção contra um modelo independente em **1 milhão de operações aleatórias** (mesma ordem, guardadas nas duas coleções,
nunca acima do teto, igual ao laço original sem corte); **diferencial "alcançar"** (com corte por 2000 ticks e depois sem,
contra o jogo sem corte: as mesmas 4935 entradas rodam uma vez, mesma ordem por região, mesmo tick fora da região
cortada; com rajadas de 3000 entradas: nada perdido, no máximo 1000 por tick); **chunk descarregando** com a região
cortada (as guardadas são gravadas, descartadas com o chunk fora e rodam uma vez depois da recarga; a tile do chunk sai na
limpeza); erro num gancho (só aquela região volta, também no resto do tick e depois de recálculos; 1 linha de log em 5
erros; mais de 20 desligam tudo); ponte de chunks entre duas bases (suspensão até o recálculo, depois uma região só com o
maior N, rodando e pulando juntas); cadeia de montaria (inclusive ciclo corrompido).

Mutações à mão (cada uma derruba pelo menos um teste): na base, tirar a regra do pico, a proteção do spawn, o dobro por
avaliação, a suspensão, a descida por custo, o fechamento do laço; no corte, a seleção ignorar o corte, não tirar do
`HashSet`, passar do teto, ignorar a isenção, a entidade morta seguir a região, o erro não liberar a região no tick, o erro
sumir no recálculo, a puxada nunca usar a seleção, ignorar a suspensão, a isenção não subir a cadeia, decisão inconsistente
dentro do tick. Duas mutações não mudam o resultado e são equivalentes (a seleção continuar depois da primeira entrada não
vencida, já que a fila é ordenada pelo tempo; e usar a seleção também sem corte, que só falha no teste que exige o laço
original).

### Plano de teste no modpack (outro agente; ferramenta de teste com `-Dhoshiestar.orcamento.teste=true`)

Cópia de teste, uma por vez, carga sintética (`tools/CARGA-SINTETICA.md`, layout a: 4 bases a 80 chunks + spawn) e bots;
parada com `stop`; backup do mundo antes dos testes que escrevem. Número de carga sintética é **indicação, não medição de
produção**.
1. **Meta:** `hoshie regioes` para achar os ids; `hoshie regioes teste <A> 150`; `hoshie regioes modo cortar`;
   `hoshie regioes registro 600`. Do CSV: ms por rodada de A ≥ 150; rodadas por segundo de cada outra região e do spawn
   ≥ 19; p95 do tick do servidor inteiro ≤ 50 ms (com os ticks em que A roda); relatar p99, máximo e o TPS de A. Controle no
   mesmo boot com `modo medir`.
2. **Conservação:** base com laços de funis entre baús, cano BC e cubo Mekanism → cabo → cubo; `teste <base> 150` e modo
   cortar por 10 min; `hoshie regioes contar <id> <arq>` a cada 2 min e depois de descarregar/recarregar os chunks (bot
   sai e volta); diferença zero em itens e energia; controle com o recurso desligado (separa defeito de mod, D-48).
3. **Diferencial:** dois boots do mesmo backup (desligado × ligado com A cortada), mesma carga;
   `hoshie regioes foto <arq> <tick inicial + 6000> <id de A>`; blocos, tiles e inventários fora de A iguais (mundo plano
   sem mobs; o que depende de sorteio fica fora e é listado).
4. **Bordas:** duas bases ligadas por linha de cabo viram uma região (comando mostra uma só) e são cortadas juntas; bot
   carrega a seção que liga A a outra região com A cortada (log: suspensão e "mapa recalculado... juntou"); bot entra e sai
   de A (posição dele todo tick no registro); A cortada com os chunks descarregando (contar e foto antes = depois da
   recarga, sem erro no log). Montaria com jogador: só no teste isolado (o bot não monta).
5. **Sem regressão:** tudo desligado, jar novo × jar anterior: mediana do tick em 3 janelas de 60 s dentro do ruído; JFR
   sem quadro de `hoshiestar.orcamento`.

## 6. O que NÃO foi verificado

- Nada rodou num servidor de verdade ainda (o corte e a base foram feitos sem ligar servidor): o corte no jogo real, o
  recálculo real do mapa, o custo dos ganchos no tick, o comando e o alerta aos operadores no jogo, e os subcomandos
  `contar` e `foto` (só compilados) ficam para os testes integrados (plano acima). As chaves de NBT de energia do `contar` (`electricityStored`, `energy`, `Energy`) vêm de
  memória dos mods; o relatório lista a soma por chave e `-Dhoshiestar.orcamento.chaves_energia` troca a lista.
- Custo no tick: só a indicação de microbenchmark sem Minecraft (acima). O plano manda medir com JFR no teste integrado.
- O corte foi provado só no mundo falso e no bytecode. Não verificado no jogo: a convivência em boot com Opis, DragonAPI,
  firewall, Hodgepodge e os outros transformadores (só varredura estática); o Hodgepodge com `addSimulationDistance_WIP`
  ligado (os redirects dele em `tickUpdates` não veem a seleção; desligado no modpack); pistões, trilhos e mods com
  estado entre tiles numa região cortada; máquinas que contam pelo relógio do mundo; o efeito em mods de energia (o teste
  de conservação do plano é que diz).
- Não verificável aqui (exige jogador humano): jogador montado entrando numa região cortada, a sensação de usar a tela de
  uma máquina parada, e a resposta do comando no chat de um operador.
- O custo de varrer as entradas agendadas vencidas guardadas (a seleção passa por elas a cada tick até a região rodar, sem
  limite) conta para a região; `BenchOrcamento` dá ~0,4 ms por tick com 20 mil guardadas (decisor simples, sem
  Minecraft; indicação, não medição de servidor; o decisor do núcleo custa mais por entrada). Medir com JFR no teste
  integrado; se pesar, guardar o ponto de corte por região (achado 7 da revisão).
- **Não cortado**: o AkashicBellow (`MixinWorldServer_DeepTicks`) faz os ticks aleatórios das seções fundas (y < 0) num
  `@Inject` logo depois de `func_147456_g`, fora do nosso laço (conferido com javap): esses ticks aleatórios rodam todo
  tick mesmo na região cortada. Sem risco de perda (só não corta); a parte deles no custo fica fora da região (achado 6).
- `nunca_cortar_mods` usa o nome do jar da classe da tile (via `CodeSource`): não verificado com os jars reais.
- O reflexo de uma dimensão nova carregada no meio do tick e a coleta de tile em chunk descarregando só são cobertos pelos
  testes de simulação.
