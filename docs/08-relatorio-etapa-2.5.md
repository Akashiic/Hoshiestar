# Relatório da Etapa 2.5: orçamento de tick por região (Degrau A)

> 2026-11-26. Plano: `docs/07-plano-etapa-2.5.md`. Doc do recurso no fork: `src/docs/orcamento.md`.
> **Todo número de servidor abaixo é indicação de carga sintética (mundo plano, bases por comando, bots, 4 núcleos,
> 8 GB), não medição de produção.** Achados numerados: `DIAGNOSTICO.md` §8e (D-54 a D-63).

**Jar:** `Hoshiestar-1.7.10-1.0.0-dev-server.jar`, sha256 `c8125bf60bfc4745ef0f0072d1dd25a351802a3b60e749ba1f230e97f06701e5`
(build do zero a partir dos 24 patches exportados do fork, branch `hoshiestar`, último commit `c2558bc2`; já com as
correções das duas revisões). Os testes de conservação, diferencial, bordas e sem-regressão (§3 a §6) foram feitos no jar
anterior, `fc377b79...eb622` (último commit `f422aad4`), e **não foram repetidos** no jar novo; só o reteste curto da §1. Recurso `orcamento` desligado por padrão (`ligado: false`).

**Em uma frase:** com uma base forçada a 150 ms por rodada, o orçamento a cortou para 1 tick a cada 25 e o resto do
servidor ficou a 19,3 a 19,8 TPS com p95 de 21,3 ms (sem o corte: 6,3 TPS e 159 ms); nenhuma perda de item ou energia;
o preço é a base cortada andar ~25 vezes mais devagar e um soluço de ~150 ms no servidor inteiro em 4% dos ticks.

## 1. O que isolou e quanto (meta do PROMPT.md): PASSOU (indicação)

Layout a (4 bases a 80 chunks + spawn + 4 bots), região A forçada com a ferramenta de teste, 10 min de registro por tick
(`etapa25/teste/RESULTADO-A.md` §A2, `a2-cut-csv.txt`):

| | sem corte (modo medir) | com corte |
|---|---|---|
| A, ms por rodada | 150,7 | 150,7 a 151,2 |
| A, de quanto em quanto | todo tick | 1 a cada 25 (CSV: 4,2% dos ticks) |
| outras regiões, TPS | 6,3 | 19,3 a 19,8 (p95 por tick até 1,4 ms) |
| servidor inteiro (estrito, inclui os ticks em que A roda) | 159 ms médio | média 16,6; p50 10,3; **p95 21,3**; p99 164; máx 575,9 ms |

Critério (A ≥ 150 ms; demais ≥ 19 TPS; p95 ≤ 50 ms): cumprido. Entrada no corte em ~3 s depois de ligar o modo
`cortar`; volta a N = 1 em ~30 s depois de tirar a carga. O alerta no log diz dimensão, caixa, centro, chunks, tiles,
entidades, ms e a ação. O máximo de 575,9 ms caiu num tick em que A **não** rodou e nenhuma região passou de 40 ms:
fora do orçamento, causa não investigada (D-60).

**Reteste curto no jar novo `c8125bf6`** (cópia nova, mesma carga, `etapa25/teste/RESULTADO-C.md`; a região A é a #13; os números
acima são do jar `fc377b79`): (a) modo medir, A a 150 ms, 3 min: A em N 25 "estouraria | PESADA", 0 cortadas, servidor 160,5 ms;
(b) modo cortar, 5 min: A cortada N 25 (1 a cada 23,7 no CSV), outras regiões 19,6 a 19,7 TPS, servidor inteiro média 16,7,
**p95 19,7** (≤ 50), p99 163,8, máx 257,4 ms; (c) em medir, A de 150 para 20 ms: N 25 → 12 → 3 → **1 em ~80 s** e, em cortar,
A **não** cortada (0 regiões cortadas em ~2,5 min; era o achado 1); (d) PESADA já na primeira leitura, 11 s depois de ligar a
carga (achado 3). Parada com `stop`, sem crash-report, sem exceção do hoshiestar; sem `kill`. Indicação de carga sintética.

## 2. O que custou para a base cortada

- **Ritmo:** 1 tick a cada 25 (TPS da região 0,7 a 0,8). Tiles, entidades, ticks agendados e aleatórios pulam juntos:
  o medidor mostra as 4 seções da base com a mesma razão (25,6) e as seções fora com 1,0.
- **Efeito para quem está lá** (lido no fonte; sem jogador humano): máquinas, funis, plantações, mobs e projéteis andam
  ~25× mais devagar; item jogado no chão só pode ser pego de volta depois de ~50 s; animal na corda não acompanha
  (a corda arrebenta, o item cai); a tela de máquina atualiza devagar. O jogador em si anda normal.
- **Máquina que conta o tempo pelo relógio não fica mais lenta:** a fornalha vanilla do Crucible (patch do CraftBukkit,
  `elapsedTicks` por `MinecraftServer.currentTick`) fez ~10 lingotes por mil ticks com e sem corte (D-55). Sem perda.
- **Custo para todos:** a rodada da base cortada continua custando ~151 ms. No tick em que ela roda (4% dos ticks, ~1 vez
  a cada 1,2 s) o servidor inteiro tem esse tick longo: é o p99 de 164 ms. Numa thread só, o Degrau A não divide a rodada.

## 3. Conservação: PASSOU

Base fechada (anéis de funis, fornalhas, baús, cano BuildCraft, matriz Mekanism com 2 cubos), ~12,5 min com corte N 25,
contra dois controles desligados e um em modo medir (`RESULTADO-B.md` §B1). Livro-caixa 4,96E10 J com diferença
**zero** em todas as fotos, inclusive depois de `stop` e religar; os 9 tipos de item que não se transformam com
diferença **zero**; minério + lingote somam 767 em toda foto; o carvão queimado bate com os controles.

## 4. Diferencial (desligado × ligado, fora da região cortada): PASSOU com método aproximado

A ferramenta de foto no tick exato quebrou no modpack (EndlessIDs, D-58); a comparação foi feita lendo os `.mca` de
`save-all` no mesmo ponto (folga de 30 a 40 ticks). Fora de A: ~1,87 milhão de blocos, ~397 tiles, 169 ticks agendados.
Desligado × cortado: 77 diferenças; medir × cortado: 50; **duas execuções sem corte entre si: 73**. Tudo o que difere é
estado que depende do tick exato ou de sorteio (anel de funis, progresso de motor, cristais do ChromatiCraft,
crescimento), e aparece também entre as execuções sem corte. Baús, colunas de funis assentadas, fornalhas paradas e
blocos: iguais. Sobram 15 linhas só da comparação com o corte, todas desses tipos; sem foto exata, não dá para provar que
são só folga e sorteio.

## 5. Bordas: PASSOU

- **Cabo/cano cruzando seções:** a base de 4 seções virou **uma** região e foi cortada inteira (razão 25,6 nas quatro).
  A junção de duas regiões levou ~5 s e a divisão voltou quando o bot saiu.
- **Jogador entrando e saindo:** bot andando a 30 blocos/s pela região cortada, sem erro e sem correção de posição; a
  região seguiu cortada e o resto a 19,4 a 19,8 TPS. **Achado:** quando a região cortada se une à região do spawn
  (protegida), ela deixa de ser cortada e o servidor cai a 5,4 a 6,8 TPS enquanto durar a união (D-56).
- **Chunk descarregando em corte:** 882 tiles antes = 882 depois da recarga, nenhum erro do orçamento.

## 6. Sem regressão (tudo desligado): PASSOU dentro do ruído

Mediana do tick em 5 min, jar novo × anterior (`7161f795…`): 9,98 × 11,03 ms; 10,25 × 9,46; 6,99 × 7,03. Nos dois
primeiros a diferença acompanha a **ordem** das medidas (o segundo medido sai menor), não o jar; ruído visto antes ±8%.
Teste de bytecode (todo gancho atrás de `static final`) verde. Não feitos: as 3 janelas de 60 s e o JFR do plano.

## 7. Custo do recurso ligado

Não medido no modpack com o recurso ligado e sem corte. Indicações sem Minecraft: 3 a 4 ns por decisão na mesma seção,
~37 ns ao trocar de região; varrer 20 mil ticks agendados guardados ~0,4 ms por tick; recálculo do mapa ~2,5 ms a cada
100 ticks (medido no observador). Testes isolados (JDK 8 e 21): todos verdes, inclusive os 21 casos adversários da revisão.

## 8. Revisão

**Primeira revisão** (agente diferente): 4 achados importantes (região que não voltava a N = 1; regiões cortadas juntas
rodando no mesmo tick; teto dos ticks agendados atrasando região não cortada; alerta perdido) e 5 menores. Nenhuma perda ou
duplicação. Todos corrigidos em 6 commits, cada um com teste que falhava antes.

**Segunda revisão** (agente diferente do que escreveu e do primeiro revisor): aprovou com **1 achado importante e 3 menores**,
corrigidos em commits próprios (`f82e6306` modo medir não soma o próprio custo de novo na descida; `6d426c37` classificação
de "acúmulo" fixada no começo da seleção; `6441a9e1` `pesada` separada de `avisadaPesada`; `c2558bc2` fase própria para cada
parte nova na divisão), cada um com teste, e **revistos por um terceiro agente**. Resultado da terceira revisão:

- Resumo: nenhum achado sério nem importante; 3 menores confirmados por experimento e 1 menor só por leitura. As 4 correções
  fazem o que dizem.
- **Perda/duplicação de tick agendado: não há.** A seleção só lê a `TreeSet`; cada entrada vai uma única vez para a lista de
  saída ou para o acúmulo, e quem tira da `TreeSet`/`HashSet` continua sendo o patch, com as três chamadas do original. No
  experimento com erro na rodada e sobra no teto: saída `[A1,B1,A2,B2,A3,B3,A4,B4,B5,B6]`, 0 duplicadas. Sem erro o caminho é
  igual ao de antes (a cópia tirada no começo da seleção tem os mesmos valores de `emCorte` e `decisao`, que só mudam dentro da
  seleção por `falhaNoPonto`).
- **Estado entre threads: correto.** O campo novo `avisadaPesada` e as mudanças em `pesada` ficam só no `Estado`, lido e escrito
  só pela thread do servidor. O `Retrato` continua imutável (campos `final`), montado inteiro em `publicar()` e publicado por um
  único campo `volatile`; `cortar` é `volatile` e só muda pela fila de pedidos na thread do servidor. `acumuloNaSelecao` é
  campo comum usado só na thread do servidor.
- **Quatro menores, abertos de propósito** (decisão 28: só achado menor não abre nova rodada; nenhum perde nada; pendência 21
  do `PROGRESSO.md`): (1) `Nucleo.java:848`: uma suspensão curta que cai no tick da avaliação faz o N descer sem a previsão
  (simulação: N 4 → 2 e o servidor a 40 ms, acima dos 36 ms que a regra promete; no pior caso um ciclo de corta/solta por
  evento, não contínuo); (2) `Nucleo.java:441-453`: com erro interno nosso num gancho **fora** da rodada da região, a ordem
  dos ticks agendados dela ainda pode trocar naquele tick; (3) `Estado.java:176`: a parte nova de uma região dividida pode
  esperar até 2N+1 ticks uma vez (N 25 → 51 ticks) antes de voltar a N ou N+1; (4) `Estado.java:158` (só leitura): na junção
  de uma região pesada com alerta segurado com outra que tinha "voltou ao normal" segurado, o alerta "começou a pesar" pode
  não sair.

O reteste do jar novo (§1) confirma as correções 1 e 3 no servidor real de teste.

## 9. Não verificado / não verificável aqui

**Não verificado:** foto no tick exato (ferramenta quebrada); fluxo real de item/energia por cano ou cabo de mod
**atravessando** a borda (na conservação tudo ficou dentro de uma região); descarregar e recarregar os chunks com a
região ainda em corte contando ticks agendados (só tiles contados); energia IC2/Thermal/EnderIO em rede fechada;
quais outras máquinas compensam pelo relógio como a fornalha; custo do recurso ligado sem corte (JFR); causa dos ticks
isolados longos (D-60); pistão sob corte.

**Não verificável aqui (precisa de jogador humano ou de produção):** alerta no chat de operador; jogador montado
entrando na região cortada; coleta de item, corda e tela de máquina na base cortada; qualquer número de produção
(o mundo de produção não é copiado; o ganho real depende de como as bases estão distribuídas, P3).

## 10. O que fica pronto para o Degrau B

Regionizador numa thread com mapa por mundo, junção a 3 seções, suspensão por mapa velho e herança de estado na junção e
na divisão; tempo por região e TPS por região (`hoshie regioes`); os pontos dos laços onde cada tile, entidade, tick
agendado e chunk de tick aleatório é atribuído a uma região (os mesmos onde o B vai despachar para as threads); alerta
com limitador; a ferramenta de teste de carga por região, o registro por tick e o método de conservação/diferencial
offline (`tools/mundo_*.py`).

Antes de ligar em produção (decisões do coordenador ou do admin): proteção do spawn por área e não pela região inteira
(D-56); corrigir a foto da ferramenta de teste (D-58). (D-61, revisão das correções pós-revisão, está cumprido: ver §8; restam os
quatro menores abertos.)

## Plano do Degrau B em poucas linhas

- **Passos:** (1) porta única de carga de chunk, inclusive a do disco (D-41), e mapa de regiões que só muda na fase em
  série; (2) identidade de thread e checagem de dono que registra e rebaixa para série (D-36), cache do mapa de chunks
  por thread (D-32); (3) envio de chunks e rastreador de entidades fora da fase paralela (D-34), listas do mundo sem
  mudar de tamanho nela; (4) módulo por rede de mod (D-49): o que registra ou invalida rede a partir do tick do tile vai
  para uma fila aplicada em série; (5) fase paralela com barreira, 3 a 4 threads, só tile entities e entidades (ticks de
  bloco em série, decisão 5b); (6) vigia de deadlock e modo seguro; conservação, diferencial, 24 h e revisão de concorrência.
- **Pré-requisitos:** o Degrau A deste relatório; as 41 estruturas de risco alto (D-31) tratadas ou isoladas; para cada
  rede do D-49, a lista dos métodos que a mudam a partir do tick do tile (hoje "não verificado").
- **Riscos principais:** erro de concorrência é raro e silencioso (duplicação) nas 41 estruturas de risco alto (D-31);
  as redes globais sem trava (Mekanism, EnderIO, IC2, AE2, MFR, Eln, Galacticraft, D-49) são invisíveis ao observador;
  bases próximas viram uma região só (no layout b, 41% dos tiles numa região), o que limita o ganho; ganho não medido.
- **Primeira entrega:** a fase paralela com barreira, desligada por padrão, numa dimensão, rodando em paralelo só tiles
  de classes liberadas (vanilla primeiro) e todo o resto em série, com checagem de dono; provada por diferencial
  ligado × desligado e conservação no modpack.
