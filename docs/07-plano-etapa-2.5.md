# Plano da Etapa 2.5: orçamento de tick por região (Degrau A)

> 2026-11-25. Decisão do admin no portão (decisão 36): vai, Degrau A primeiro. Uma thread só. A implementação começa
> logo depois deste plano. Pontos de patch conferidos no fonte decompilado do workspace (branch `hoshiestar`,
> `eclipse/cauldron/src/main/java/...`); linhas são do workspace de hoje e podem andar.

Termos: **seção** = 16×16 chunks; **região** = seções com chunk carregado a até 3 seções uma da outra (Folia);
**rodada** = um tick em que a região roda; **N** = a região roda 1 vez a cada N ticks do servidor; **corte** = N > 1.

## 1. Regionizador numa thread só

- **Código:** a junção já existe e funcionou no observador (`observador.Regioes`: união-busca sobre `MapaSecoes`, raio
  3 em Chebyshev; `FotoMundo` montada por `Jogo.fotografar()` com `theChunkProviderServer.loadedChunks`). O passo 1
  muda as três peças para o pacote público `hoshiestar.regioes`; o observador passa a usá-lo, testes dele sem mudança.
- **Mapa por mundo:** seção → id de região, chave `long`, tabela aberta sem objeto; busca por `(cx>>4, cz>>4)`.
  Chunk cuja seção não está no mapa = **sem região = nunca cortado** (padrão seguro).
- **Quando recalcula:** no início do tick do servidor (`MinecraftServer.tick`, junto dos ganchos do medidor e do
  observador, linhas ~867-870), a cada `recalculo_ticks` (100) e mais cedo (no mínimo 20 ticks depois do último)
  quando alguma busca achou tile, entidade ou tick agendado numa seção ocupada que não está no mapa. Assim cobrimos
  todo caminho de carga de chunk, inclusive o do disco que o gancho do observador não vê (D-41), sem gancho no carregador.
  Custo medido no observador: primeira foto 41 a 85 ms (código frio, uma vez por boot), depois ~2,5 ms (indicação).
- **Mapa velho:** seção nova só pode **juntar** regiões. Até o recálculo, toda região a até 3 seções de uma seção
  desconhecida fica **suspensa** (roda todo tick). Chunk descarregado só pode dividir; mapa mais grosso que o real
  corta mais junto, nunca pela metade.
- **Estado entre recálculos:** os ids mudam a cada recálculo. O estado (N, médias, alerta) fica preso às seções:
  região nova herda o estado das regiões velhas que tinha dentro; na junção vale o **maior N** e a maior média; na
  divisão cada parte herda o estado e a histerese tira o corte de quem não pesa.
- `sempre_junto_dimensoes`: a dimensão inteira vira uma região.

## 2. Orçamento

- **Tempo por região, barato:** as buscas já acontecem nos laços (decidir se roda). O tempo só é lido com
  `System.nanoTime()` quando o laço **troca de região** (tile, entidade, entrada agendada, chunk de tick aleatório);
  o intervalo vai para a região que estava rodando. Pior caso: 1 leitura por objeto (20 mil objetos ≈ 0,4 ms, estimativa,
  medir com JFR no teste). O custo de pular entradas de uma região cortada conta para ela. Tempo do servidor: ganchos
  próprios no início e no fim de `MinecraftServer.tick` (não depende do vigia).
- **Números (config, padrões):** `alvo_tick_ms` 45 (média do servidor em 100 ticks); `orcamento_regiao_ms` 10 (média
  por tick real que uma região pode usar); `pico_ms` 40 e `pico_fracao_pct` 4; `n_maximo` 40.
- **Quando estoura:** média do servidor > `alvo_tick_ms` por 100 ticks seguidos **e** custo da região por tick real
  (custo por rodada ÷ N) > `orcamento_regiao_ms` no mesmo período. Só regiões acima do orçamento são cortadas, da mais
  pesada para a mais leve; região leve nunca é cortada para "abrir espaço".
- **De quanto em quanto:** `N = ceil(custo_por_rodada / orcamento_regiao_ms)`, limitado a 2..`n_maximo`. Se a rodada
  passa de `pico_ms`, N ≥ 100/`pico_fracao_pct` = 25: a rodada pesada cai em no máximo 4% dos ticks, e o p95 do servidor
  não a vê. N sobe no máximo ao dobro a cada 100 ticks.
- **Sem relógio fixo:** a região não roda em `t % N == 0`. Máquina que age em `tempoDoMundo % 20 == 0` nunca agiria
  com N par. Usamos um acumulador com taxa irracional, `1/(N + 0,0618…)`: os intervalos são N ou N+1 e as rodadas caem
  em todos os restos de qualquer módulo (sequência de Beatty). Teste isolado confere os restos de 2, 4, 5, 10, 20, 40, 100.
- **Volta ao normal:** média do servidor < 80% do alvo por 200 ticks → N cai à metade (até 1), passo a passo. Também
  cai quando `ceil(custo/orçamento)` < N por 200 ticks (a base desligou máquinas). Servidor folgado: **ninguém novo
  entra em corte**, e os cortados descem.
- **Nunca entra no corte:**
  - região do spawn da dimensão 0 (`worldInfo` spawn X/Z) e `nunca_cortar_areas` (área de eventos);
    `nunca_cortar_dimensoes`; região com tile de mod em `nunca_cortar_mods`; região suspensa por mapa velho;
  - **jogadores** (`EntityPlayer`, inclusive o que vestem e carregam, que roda no tick deles) e toda entidade na
    cadeia de montaria de um jogador (montaria e o que ele carrega), senão o jogador ficaria preso no cavalo;
  - raios e efeitos de clima (`weatherEffects`, globais); handlers globais de mods; eventos de bloco (seção 3).
- **Entram no corte (decidido):** itens no chão e projéteis vão junto com a região. Motivo: a coleta pelo jogador roda
  no tick do jogador (sempre), a idade do item só anda quando ele tica (some mais tarde, nunca mais cedo), e a física é
  por tick (o mesmo passo, só mais devagar; não atravessa bloco). Entidade em travessia: vale o chunk dela no começo do
  tick; não há estrutura de várias entidades nem rede atravessando a borda (seção 4), então não precisa de exceção.
  `cortar_com_jogador` (true): região com jogador dentro também é cortada (é o caso da base do dono online).

## 3. O que é cortado junto

Num tick em que a região não roda, ela pula **tudo** o que é dela no mesmo tick: tile entities, entidades, ticks de
bloco agendados e ticks aleatórios. Num tick em que roda, roda tudo. Pontos de patch (`// Hoshiestar`, atrás de
`if (Orcamento.LIGADO)`, `static final` lido no boot):

| Onde | Método | O quê |
|---|---|---|
| `World.java` | `updateEntities()`, laço de entidades (~2430-2490) | antes de `this.updateEntity(entity)`: pula se a região não roda; a remoção de morto continua igual |
| `World.java` | `updateEntities()`, laço de tiles (~2534-2590) | depois do teste de nulo e de `isInvalid()` (limpeza de chunk descarregado segue igual), antes de `canTileEntityTick` |
| `WorldServer.java` | `tickUpdates(boolean)` (~711-810) | puxada da fila: ver abaixo; com `p_72955_1_ = true` (forçar) não corta |
| `WorldServer.java` | `func_147456_g()` (~438-580) | por chunk, depois do teste de descarga: pula o chunk da região que não roda |
| `MinecraftServer.java` | `tick()` (~867 e ~951) | início/fim: recálculo, decisão de quem roda, tempo do servidor, alerta |

- **Ticks agendados sem perder nem disparar em rajada:** com o recurso ligado, a puxada usa um iterador da `TreeSet` no
  lugar de `first()`: entrada vencida de região cortada **fica onde está** (na `TreeSet` e no `HashSet`); só as outras
  saem. Assim `scheduleBlockUpdate` continua sem duplicar, e `getPendingBlockUpdates` (gravação do chunk,
  `AnvilChunkLoader:404`) continua vendo e gravando as entradas guardadas. O teto do CraftBukkit (1000 ou tamanho/20)
  conta só as que rodam. Rajada: cadeia que se reagenda (relógio de redstone) reagenda a partir de "agora", então roda
  no máximo 1 vez por rodada; entrada única espera no máximo N+1 ticks. O que se acumula é o que venceu entre duas
  rodadas, a mesma conta que o jogo faria em N ticks. Desligado: o laço original, sem mudança.
- **Eventos de bloco (`func_147488_Z`) não são cortados:** quase todos nascem do trabalho da própria região no mesmo
  tick (então já andam em passo com ela); os outros vêm de jogador ou vizinho e rodam como no jogo. Adiar exigiria
  guardar fora das duas listas do jogo e mexe no tempo do pistão (risco de duplicação). Custo baixo.
- **Relógio do mundo:** `getTotalWorldTime` anda todo tick, para todos. Consequências: máquina que conta pelo próprio
  `updateEntity` fica N vezes mais lenta (o esperado); máquina que calcula "quanto tempo passou" pelo relógio do mundo
  **compensa** na rodada (não fica mais lenta e faz pico): essa região continua pesada, sobe a N máximo e o alerta diz;
  `tileentities.yml`/`entities.yml` do Crucible (`getWorldTotalTime() % intervalo`, D-25) continuam valendo por cima.
- Limitador do Spigot (`max-tick-time`, padrão 1000 ms neste Crucible): se algum mundo tiver valor menor, o laço pode
  parar no meio de uma rodada; o recurso avisa no boot. Nada muda nele.

## 4. Redes de mods que cruzam regiões

- **Rede física não atravessa borda de região, por construção:** um cabo/cano liga chunks carregados vizinhos, as seções
  deles ficam a 1 seção uma da outra e a junção (raio 3) as põe na mesma região. Rede que passa por chunk descarregado
  já está quebrada no jogo original. A única janela é o mapa velho (seção 1), coberta pela suspensão. Os dados da
  Etapa 2 batem com isso: todo acesso de mod que cruza seção é a 1-2 chunks (D-38, D-50).
- **Ligação sem fio ou entre dimensões** (teletransportador, entangloporter, transceptor, ponte quântica, baú do ender):
  as duas pontas podem estar em regiões diferentes. O estado delas é um objeto comum, e cada ponta lê/escreve nele no
  próprio tick: a ponta cortada só anda mais devagar. Para quem quiser as duas pontas no mesmo ritmo: `nunca_cortar_mods`
  e `sempre_junto_dimensoes` (padrões vazios; lista sugerida vai no doc do recurso).
- **Redes globais (D-49: IC2, Mekanism, EnderIO, AE2, MFR, Eln, Galacticraft) continuam ticando todo tick**, porque
  rodam em handler de tick e não no laço de região. Consequência: a rede entrega e retira a ritmo cheio de tiles que
  só tickam 1/N; o buffer do tile enche e a demanda cai. Regra segura: o Degrau A **nunca** pula handler; a entrega em
  tile parado é o mesmo caso do `tileentities.yml` e do limitador do Spigot, que a produção já usa. Tile novo que só
  entra na rede no primeiro tick (IC2 `EnergyTileLoadEvent`) entra até N ticks depois. O teste de conservação cobre isso.

## 5. Bordas

- **Cabo/cano cruzando a borda:** não existe entre duas regiões (§4); o teste prova a junção e a suspensão.
- **Jogador entrando e saindo:** o jogador roda sempre; em volta dele, na região cortada, tudo anda a 1/N. Tela de
  máquina atualiza devagar; clique em slot é tratado pela rede, com o tile parado e inteiro (sem meio-tick).
- **Chunk descarregando com a região cortada:** a limpeza de tiles (`field_147483_b`/`isInvalid`) e de entidades
  (`unloadedEntityList`) roda antes do nosso teste; as entradas agendadas guardadas seguem na fila e são gravadas.
- **Entidade cruzando:** segue o ritmo da região onde está. **Funil/duto empurrando para tile cortado:** só com o
  empurrador fora do mapa (mapa velho) ou vindo de handler global; inserir em inventário não exige tick do tile
  (já acontece hoje com `tileentities.yml`): seguro.

## 6. Alerta, medição, comando e chaves

- **Alerta** (log + operadores online, com limitador de 1 por região a cada `alerta_intervalo_min` = 5 e no máximo 10
  por minuto): quando o custo por tick real de uma região passa de `alerta_ms` (25) por 100 ticks, e quando ela volta.
  Texto: dimensão, caixa e centro em blocos, chunks/tiles/entidades, ms por rodada, ms do servidor, e a ação ("passou a
  ticar 1 a cada 25" ou "só medindo"). Nunca nome de jogador nem IP.
- **Por região:** TPS (rodadas por segundo de relógio, 20 s), ms por rodada (média e p95 de 100 rodadas), ms por tick
  real, N, estado (normal, cortada, protegida, suspensa). **Comando** (na thread do servidor): `hoshie regioes` (modo,
  TPS e p95 do servidor, 10 mais pesadas), `hoshie regioes <id>` (detalhe + 5 classes de tile mais comuns),
  `hoshie regioes aqui`, `hoshie regioes modo medir|cortar`.
- **Chaves** (`hoshiestar.yml`, seção `orcamento`): `ligado` (**false** = nenhum gancho faz nada; o código fica atrás de
  `static final`), `modo` (`medir` padrão: calcula e alerta sem cortar; `cortar`), as de §2, `recalculo_ticks`,
  `proteger_spawn` (true), `nunca_cortar_dimensoes`, `nunca_cortar_areas` (`dim:x1,z1,x2,z2;...`), `nunca_cortar_mods`,
  `sempre_junto_dimensoes`, `cortar_com_jogador`, `alerta_ms`, `alerta_intervalo_min`, `alerta_ops`. Listas em texto
  separado por vírgula (o leitor de config não tem lista).

## 7. Ferramenta de teste

Só com `-Dhoshiestar.orcamento.teste=true` (fora disso os subcomandos nem existem): `hoshie regioes teste
<id|aqui|x z> <ms>|parar` gasta `<ms>` em espera ocupada na primeira tile da região em cada rodada (conta como custo
dela e some quando ela é cortada; carga real da carga sintética é a segunda prova); `registro <s>` grava CSV por tick
(ms do servidor; por região: rodou?, ms); `contar <id> <arq>` soma num ponto serial itens (id:meta, varrendo o NBT de
tiles e itens no chão, inclusive item em cano) e energia (chaves de NBT do Mekanism, IC2, CoFH); `foto <arq> <tick>
[excluir ids]` grava NBT de tiles, blocos e inventários no tick exato do mundo.

## 8. Testes obrigatórios (na cópia de teste, modpack de 292 mods, carga sintética)

Todo número é **indicação de carga sintética, não medição de produção**. Uma cópia por vez, parada com `stop`, backup do
mundo antes dos testes que escrevem, um boot serve a vários casos.
1. **Meta:** layout a (4 bases a 80 chunks = 4 regiões) + spawn + bots. `teste <A> 150`, modo `cortar`, 10 min com
   `registro`. Passa: A ≥ 150 ms por rodada; cada outra região e o spawn com ≥ 19 rodadas/s; p95 do tick do
   **servidor inteiro** ≤ 50 ms (definição estrita: inclui os ticks em que A roda). Relata também p99, máximo (o tick em
   que A roda continua longo: custo do degrau) e o TPS de A. Controle no mesmo boot: modo `medir` (sem corte).
2. **Conservação:** base com laços de funis entre baús (itens circulando), cano BC, e cubo Mekanism → cabo → cubo (energia
   em sistema fechado). Sob corte forçado por 10 min, `contar` a cada 2 min e depois de descarregar/recarregar os chunks.
   Diferença zero em itens e em energia. Controle com o recurso desligado, para separar defeito do mod (D-48).
3. **Diferencial:** dois boots do mesmo backup, mesma carga, desligado × ligado com A cortada; `foto` no mesmo tick do
   mundo (início + 6.000); blocos, tiles e inventários **fora de A** iguais. Mundo plano sem mobs; o que depende de
   sorteio fica fora e é listado.
4. **Bordas:** duas bases ligadas por linha de cabo viram uma região e são cortadas juntas; bot carrega a seção que liga
   A a outra região com A cortada (suspensão, depois junção); bot anda para dentro e para fora de A (posição atualizada
   todo tick); A cortada com os chunks descarregando (entradas agendadas e NBT antes = depois da recarga, sem erro no log).
   Montaria com jogador: teste isolado (o bot não monta).
5. **Sem regressão:** tudo desligado, jar novo × jar `7161f795…`: mediana do tick em 3 janelas de 60 s dentro do ruído
   (meta do prompt 5%; ruído visto ±8%); JFR sem quadro de `hoshiestar.orcamento`; teste de bytecode de que todo gancho
   está atrás da guarda.
Testes isolados (JDK 8 e 21): histerese e N com séries sintéticas; restos da sequência de Beatty; herança de estado na
junção/divisão; proteções; a puxada por iterador contra a original em 1 milhão de operações aleatórias (mesmas entradas
rodam fora do corte, as cortadas ficam, `TreeSet`/`HashSet` do mesmo tamanho, `getPendingBlockUpdates` vê as guardadas).

## 9. Passos, agentes e pronto

| Passo | O quê | Agente (decisão 31) |
|---|---|---|
| 1 | extrair `hoshiestar.regioes` do observador, testes do observador verdes | sonnet / médio |
| 2 | núcleo `hoshiestar.orcamento` (estado, N, histerese, Beatty, herança, proteções, alerta) + testes isolados | opus / médio |
| 3 | patches em World, WorldServer, MinecraftServer + puxada por iterador + teste de bytecode | opus / alto (risco de perda) |
| 4 | config, comando, subcomandos de teste, doc `src/docs/orcamento.md` | sonnet / médio |
| 5 | revisão que tenta quebrar os passos 2 a 4 (outro agente) | opus / alto |
| 6 | testes integrados da §8 no modpack | sonnet / médio; julgar resultado estranho: opus |
| 7 | relatório curto, jar com sha256 do zero, PROGRESSO/DIAGNOSTICO | coordenador (sonnet / médio para o texto) |

Sem código entre threads nesta etapa: se aparecer (ex.: gravação do CSV em outra thread), revisão de concorrência fable.
Um commit por mudança no workspace; build sempre com `--mods`.

**Pronto quando:** os cinco testes da §8 passam (com "não verificado" declarado onde couber); testes isolados verdes nos
dois JDKs; revisão sem achado importante aberto; jar feito do zero com sha256; parada limpa em todos os boots; desligado =
original; relatório com o que isolou, quanto, o custo para a base cortada e o que não foi verificado.

**Fica pronto para o Degrau B:** regionizador com mapa por mundo e herança de estado; tempo por região; os pontos dos
laços onde a região de cada objeto é decidida (são os mesmos onde o B vai despachar para as threads); comando e alerta.
Fora daqui: threads, fase paralela, correio, adapter de plugins.
