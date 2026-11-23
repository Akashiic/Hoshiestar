# Relatório do observador (Etapa 2) e portão "vai / não vai"

> 2026-11-23. Juiz: agente diferente dos que construíram e testaram. Nenhum servidor foi ligado para este relatório;
> tudo vem dos arquivos do teste (`etapa2/teste/`), do fechamento das pendências (`etapa2/pendentes/relatorio.md`),
> da revisão independente (`etapa2/rev/revisao.md`), do fonte do fork (branch `hoshiestar`, até `ed357cc7`) e de
> `javap` nos jars dos mods. **A decisão do portão é do admin**; aqui está só a recomendação.

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

## 2. Quais mods cruzam seção e região na carga sintética

Contagens em 584 s de observação (cerca de 11.300 ticks), nas três rodadas completas T2c / T3 / T4.

| Mod (contexto, acesso) | Cruza seção (T2c / T3 / T4) | Por tick | Cruza região | Distância real | Leitura |
|---|---|---|---|---|---|
| ChromatiCraft (tile, lê bloco vizinho) | 28.959 / 29.319 / 29.043 | ~2,6 | 0 | 1 chunk | vizinho na borda; fica dentro da região |
| Forestry (tile, lê bloco) | 18.048 / 18.095 / 18.217 | ~1,6 | 0 | 1 chunk | idem |
| EnderIO (tile, lê tile/inventário vizinho) | 11.281 / 11.309 / 11.387 | 1,0 exato | 0 | 1 chunk | uma máquina na borda, uma vez por tick |
| Mekanism (tile, lê bloco) | 11.281 / 11.309 / 11.387 | 1,0 exato | 0 | 1 chunk | idem |
| gadomancy (tile, lê tile) | 7.110 / 7.047 / 7.036 | ~0,6 | 0 | 1 chunk | idem |
| CoFHCore (tick agendado, lê bloco) | 63.694 / 2.675 / 600 | 5,6 a 0,05 | 40 / 2 / 0 | 1 chunk; "381" é artefato | varia 100× entre rodadas; parte é artefato (§2.1) |
| DragonAPI (só na amostra de pilha) | 331 / 98 / 86 | — | 0 | 1 chunk | biblioteca chamada por outros mods |
| Vanilla "(nenhum)" (tick aleatório/agendado) | 36.202 / 27.453 / 25.877 | ~2,5 | 2.757 / 1.590 / 1.030 | 1 a 2; "481-527" é artefato | relight e leitura da próxima entrada (§2.1) |
| AkashicFossils, HarvestCraft, etfuturum, Thaumcraft, DimensionalDoors (tick aleatório) | dezenas a centenas | <0,05 | 0 a 64 | 1 chunk; "241-481" é artefato | §2.1 |
| BuildCraft (cano na borda) | 0 / 0 / 0 (106 só na T2, que começou no boot) | 0 | 0 | 1 chunk | **invisível depois do primeiro tick** (§2.2) |
| IC2 (cabo na borda) | 0 em todas | 0 | 0 | — | **invisível** (§2.2) |
| ThermalExpansion (406 mil acessos) | 0 | 0 | 0 | 0 | só a própria seção |

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
punha essas redes nos 18% de handlers globais (D-13).

### 2.3 Outras lacunas dos dados

- **Leituras sem origem não são atribuídas.** Em 584 s: 6,76 milhões de leituras de bloco em "mundo depois do
  tick", 2,51 milhões em "outro" e 1,43 milhão em "rede" ficaram só como contagem, sem mod e sem seção de destino
  (a amostra de pilha só roda para acesso que cruza seção). As escritas atribuídas a handlers globais são poucas
  (125 escritas de bloco, 503 leituras de tile, 192 "provável inventário" em "mundo depois do tick").
- **T3 = 0 cargas de chunk em todas as rodadas.** O gancho está em `originalLoadChunk`, que só roda para chunk
  **novo** (gerado). Chunk que já existe no disco entra por `ChunkIOExecutor.syncChunkLoad`/`queueChunkLoad`
  (`ChunkProviderServer.java:188-199`) e não passa por ali; a geração dos andarilhos aconteceu antes de cada
  observação começar. A frequência de carga dentro do tick (pendência 9) continua sem dado.
- **Pares T2 entre regiões (112 na T2c):** quase todos de seções de base para seções de andarilho, distância de 15 a
  30 seções: mesmo artefato do §2.1.

## 3. Protótipo da lista de tiles (§4 do plano)

T3 (e igual na T4): 71,4 milhões de chamadas feitas pelo próprio `World`; **2,11 milhões de chamadas de fora**, todas
em contexto serial (antes/depois do tick de mundo e depois do tick do servidor); 0 em contexto de região; 0 sem
contexto conhecido (depois da correção do achado 4 da revisão, esse grupo é contado à parte); **0 modificações de
fora**; 0 trocas do campo feitas de fora; 0 chamadas de outra thread; 0 chamadas em lista aposentada.

Quem usa de fora: `Akashic-TickRelief` (`MOTickRegistry.rescan`: `get` e `size`, antes e depois do tick de mundo) e
`Mirai` (`ForgeServerProxy.diagnosticSnapshot`: `size`, depois do tick do servidor). O próprio Crucible troca o
objeto da lista 2.594 vezes em 584 s (cerca de 4 por segundo, `World.java:2510`), e o protótipo segue funcionando.

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

**Não verificado:**
- Que o artefato do §2.1 explique **todas** as leituras longas (conferido no fonte, sem teste que reproduza);
  quanto do CoFHCore é artefato.
- De onde vêm os tiles do ChromatiCraft e do gadomancy; por que as entidades crescem entre rodadas.
- Se itens/energia fluem pelo cano BC e pelo cabo IC2 da carga.
- Ruído entre rodadas; parada por SIGTERM (item 1.G); carga de chunk dentro do tick (T3 cego).
- Conteúdo do World patchado dentro de `binpatches.pack.lzma` (achado 13 da revisão); que o jar testado contém as
  correções (só pela hora do build).
- Varredura estática dos jars (passo 7 do plano, pendência 9: singletons de IC2, AE2, EnderIO, CoFH, BuildCraft):
  não aparece nos resultados desta etapa.
- Achados menores da revisão ainda abertos: 5 (parar durante o índice), 6 (uso dentro de `forEach`), 7 (contexto
  velho de entidade/tile), 8 (montaria), 9 e 10 (T3 e escrita direta), 11 (cópia extra no `embrulhar`), 12, 13.

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

### 8.3 Item a avaliar (decisão 35): ajuste automático em execução

Registrado, sem desenho: ajustar com o servidor rodando o número de threads, juntar regiões ou desacelerar regiões
conforme a carga. Observações para quando for avaliado: o Degrau A já é uma forma de "desacelerar conforme a
carga"; no B, a pergunta seria distribuir regiões entre threads conforme o tempo medido de cada uma. Nenhuma linha de
código disso nesta etapa nem na 2.5 sem decisão.
