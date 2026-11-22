# Hoshiestar: desenho da arquitetura

Estado: base e ordem em degraus **aprovadas pelo admin** (2026-11-21). Revisado em 2026-11-22 com o perfil
de produção e o estudo do código do Crucible. Nenhum código de região foi escrito ainda.
Termos técnicos são explicados na primeira vez que aparecem.

## 1. O problema, em uma frase

Hoje o servidor faz tudo num "tick" (um passo de 50 ms do jogo) numa thread só.
Se uma base gasta 150 ms, o tick inteiro gasta 150 ms e todo mundo laga.
Queremos que a base pesada pague sozinha pelo próprio peso.

## 2. O que a evidência diz antes de desenhar

| Fato | Fonte | Consequência |
|---|---|---|
| Folia só funciona porque **proíbe** plugins não adaptados e reescreveu o sistema de chunks | docs oficiais do Folia (`docs/pesquisa/arte-previa.md` §1) | Não dá para copiar o Folia e manter "nenhum mod/plugin reescrito". Precisamos de um modo de compatibilidade que ele não tem. |
| TickThreading (Forge 1.4–1.6) fez tick paralelo e foi abandonado pelo autor por exigir **patch manual por mod** | README do projeto (§2.1) | Estratégia "um patch por mod" não se sustenta. O padrão tem que ser seguro sem conhecer o mod. |
| MCMT (1.15+) paralelizou por tipo de tick e **perdeu 20–50% de TPS** | README do projeto (§2.2) | Trocar listas por versões concorrentes não resolve; precisa de dono por região. |
| AE2 quebra até com uma thread por dimensão | DimensionalThreading (§2.3) | Redes de mod são o caso difícil. O pack tem AE2, IC2, EnderIO, Mekanism, RotaryCraft. |
| 55 jars usam `ServerTickEvent`, 41 `WorldTickEvent`, 34 `WorldSavedData` | inventário estático (`docs/dados/inventario-mods.csv`) | Muito código de mod roda "uma vez por tick do servidor/mundo" e guarda estado global. Esse código não tem região. |
| Produção, 8 jogadores: 11,8 TPS, 84 ms por tick, thread principal sem nunca dormir, processo usando menos de 3 dos 7 núcleos | perfil do spark (`DIAGNOSTICO.md` §6) | A premissa está confirmada por medida: o gargalo é uma thread e sobram núcleos. |
| Do tick de produção, 67% é trabalho local de região, 18% handlers globais de mods, 10% rede, 5% plugins | idem | Teto de ganho: o paralelismo leva de 84 ms para perto de 50 ms com 4 threads. Chega a 20 TPS, sem folga. |
| Um único clique (habilidade do ChromatiCraft sem limite) travou o servidor 30 s, dentro da fase de rede | stall report (`DIAGNOSTICO.md` §7) | Orçamento e fase paralela não evitam isso. Só regiões independentes isolam; antes delas, limites pontuais. |
| Eventos de plugin já passam por uma trava global; 63 jars de mod leem listas do mundo por campo público | estudo do Crucible (`docs/04-estudo-crucible.md`) | Plugins ficam em fila sozinhos; interceptar métodos não basta. |
| A base não tem checagem de thread (`AsyncCatcher` ausente) | avaliação da base §1.7 | Precisamos criar as checagens de dono. |
| Mixins early/late sobre o Crucible já rodam em produção (mods `Akashic-*`) | jars do servidor | O mecanismo de interceptação está provado neste ambiente. |

Conclusão honesta: "cada região em sua thread, com todos os mods, sem reescrever nada"
não é alcançável de uma vez, e para alguns mods talvez nunca. O desenho abaixo chega
ao objetivo nº 1 (lag isolado) cedo e por um caminho seguro, e trata o paralelismo
como ganho progressivo, liberado mod a mod e dimensão a dimensão.

## 3. Ideia central: degraus, todos com interruptor

```
Degrau 0  Ganhos baratos medidos             (1 thread)            -> ~20% do tick, sem risco de concorrência
Degrau A  Orçamento de tick por região       (1 thread)            -> isola a base pesada
Degrau B  Fase paralela sincronizada         (3-4 threads, em passo) -> usa os núcleos
Degrau C  Regiões independentes estilo Folia (3-4 threads, soltas)  -> isola e escala
```

O que o perfil de produção diz sobre cada um (contas em `DIAGNOSTICO.md`, D-14 a D-16):

| Degrau | Tick estimado (hoje: 84 ms) | Chega a 50 ms? |
|---|---|---|
| 0 sozinho, no melhor caso (os quatro pontos eliminados por inteiro) | ~67 ms | não |
| A sozinho, se uma base tiver metade da carga | 56 a 70 ms | não |
| B com 4 threads, carga espalhada | 42 a 53 ms | no limite |
| 0 + B, no melhor caso do Degrau 0 | ~37 a 45 ms | sim, com alguma folga |
| 0 + A + B, com uma base pesada | depende de como a carga se distribui | **falta medir** |

Todas as linhas são estimativas feitas sobre um perfil de 8 jogadores, não medidas.
Nenhum degrau resolve sozinho. A combinação resolve. E a medida que falta é a
distribuição da carga pelo mapa.

### Degrau 0: ganhos baratos medidos (novo)
Quatro pontos somam cerca de 20% do tick (é o teto; o ganho real depende de quanto de cada um dá para cortar) e não precisam de threads: a leitura de
energia da matriz de indução do Mekanism (8,3%), o BetterQuesting (4,6%), o mapa de
chunks do Crucible (3,6%) e o handler do Ars Magica 2 (3,6%). Mais duas correções
pontuais: a opção do Hodgepodge que queima um núcleo (D-18, verificada) e um teto
na habilidade do ChromatiCraft que causou o stall de 30 s (D-21). São mudanças
pequenas, do mesmo tipo dos mods `Akashic-*Relief` que a rede já mantém.

### Degrau A: orçamento de tick por região (Plano B do prompt)
O servidor continua com uma thread. O mundo já é dividido em regiões (mesma
estrutura dos degraus seguintes), e cada região tem o tempo de tick medido.
Região que estoura o orçamento passa a ticar 1 vez a cada 2, 3, 4 ticks:
as máquinas daquela base andam mais devagar, o resto do servidor fica a 20 TPS.

- Entrega "base pesada laga só ela" sem nenhum risco de concorrência.
- É o mesmo efeito do lag de hoje, só que restrito a quem causa.
- Já dá o alerta ao admin ("região X, base em tal coordenada, 140 ms").
- Limite: não usa os núcleos sobrando. A soma de todas as bases ainda cabe em uma thread.
- **Limite medido**: neste perfil, tirar do tick uma base com metade da carga ainda deixa 56 ms. O Degrau A isola a base pesada, mas não leva o servidor a 20 TPS sozinho.
- O Crucible já tem um mecanismo parecido por **tipo** de máquina (`tileentities.yml`), que não serve para isolar uma base (D-25). O Degrau A é o mesmo efeito por **lugar**, escalonado para não fazer pico.
- Não protege contra uma chamada única muito longa (o caso do stall): orçamento age entre ticks, não interrompe.

### Degrau B: fase paralela sincronizada
Cada tick do servidor vira três fases:

```
[fase global, em série]   eventos de início de tick, agendador de plugins, rede
[fase paralela]           cada região tica suas tile entities, entidades e blocos
                          na thread dela; a fase só termina quando todas acabam
[fase global, em série]   eventos de fim de tick, redes de mod não liberadas, salvamento
```

- Mods e plugins que rodam "uma vez por tick" continuam em série, como hoje. Compatibilidade alta.
- Usa os núcleos. Combinado com o Degrau A, a região pesada não segura as outras (ela pula ticks).
- Limite: o tick ainda é comum. O teto é a fase em série mais a região mais lenta permitida pelo orçamento.
- Threads: um grupo de **3 a 4** atendendo uma fila de regiões, não uma thread por região. A máquina tem 7 vCores e a thread principal, a coleta de lixo, a rede e o I/O já usam parte.
- Eventos de plugin disparados na fase paralela caem na trava global do Bukkit (D-26): seguro, mas é ponto de espera.
- As listas públicas do mundo não podem mudar de tamanho durante a fase paralela: adições e remoções ficam para a fase em série, como o jogo já faz com tile entities novas.

### Degrau C: regiões independentes (estilo Folia)
Cada região tem o próprio relógio de tick. Só para dimensões e mods **liberados**
depois de passarem pelo observador (Etapa 2) e pelos testes. O resto fica no Degrau B.

- É o que escala para 100–200 players espalhados.
- É onde moram os riscos: eventos globais disparados em paralelo, plugins, redes de mod.
- Pode nunca cobrir 100% do pack. Tudo bem: cada dimensão usa o degrau mais alto que aguenta.
- É o único degrau que teria isolado o stall de 30 s, e só com os pacotes tratados na região do jogador.

Mapa para as etapas do prompt: Degrau 0 = Etapa 1 (revista); Degrau A = Etapa 2.5; Degrau B = primeira metade
da Etapa 3; Degrau C = segunda metade da Etapa 3 mais Etapas 4 e 5.

## 4. Componentes

```
+--------------------------------------------------------------------+
|  Plugins Bukkit            |  Mods Forge                           |
+----------------------------+---------------------------------------+
|  (7) Adapter de plugins    |  (4) Módulos por mod (AE2, IC2, ...)  |
+----------------------------+---------------------------------------+
|  (3) Núcleo de roteamento: mixins early/late + checagem de dono    |
+--------------------------------------------------------------------+
|  (2) Regionizador   (5) Thread global   (6) Correio   (9) Vigia    |
+--------------------------------------------------------------------+
|  (1) Base: fork do Crucible staging   +   (10) Medição por região  |
+--------------------------------------------------------------------+
```

### (1) Base
Fork do Crucible `staging` a partir de `67b53034` (igual à produção). Detalhes em
`docs/01-avaliacao-base.md`. As Etapas 0 a 2 **não precisam do fork**: saem como
um mod comum com mixins, que roda no `server.jar` atual. O fork só é necessário
quando mexermos no laço de tick (Degrau B em diante). Isso adia a decisão cara.

### (2) Regionizador
Estrutura que diz "este chunk pertence a esta região".

- Unidade: seção de 16×16 chunks (256×256 blocos). Valor inicial, ajustável no config.
- Região = conjunto de seções vizinhas com chunks carregados, mais as seções dentro do raio de junção.
- Regras fixas (copiadas do Folia, verificadas na doc oficial): um chunk tem um dono só; região que está ticando não cresce; junções e divisões só no fim do tick.
- Junção forçada: se uma rede de mod liga duas regiões, elas viram uma (ver (6)).
- Cada dimensão tem o próprio regionizador.

### (3) Núcleo de roteamento
Mixins nos pontos por onde todo mod passa: `World.setBlock`, `getTileEntity`,
adicionar/remover entidade e tile entity, acesso a inventário via `IInventory`,
agendamento de tick de bloco, carregamento de chunk.

Três modos, trocados no config:
1. **Observador** (Etapa 2): só anota "o mod X, a partir da região A, tocou a posição P da região B". Não muda nada.
2. **Checagem**: além de anotar, avisa ou barra o acesso fora do dono.
3. **Roteamento**: o acesso fora do dono vira tarefa na fila da região dona.

Ponto técnico importante: em 1.7.10 muitos mods leem os campos públicos
`world.loadedTileEntityList` e `world.loadedEntityList` direto, sem passar por método.
Mixin não intercepta leitura de campo de fora. A saída prevista é trocar o **objeto**
da lista por uma lista própria que entrega a visão da região da thread atual.
A confirmar em protótipo na Etapa 2 (não verificado).

### (4) Módulos
Um módulo por mod difícil. Ele declara: como o mod forma redes, o que tolera
atraso, o que precisa de junção, quais handlers globais são seguros em paralelo.
Sem módulo, o mod roda na regra segura (em série / junção). Nada de patch
obrigatório por mod: essa foi a armadilha do TickThreading.

### (5) Thread global
Relógio do mundo, clima, console, lista de jogadores, economia, chat, ranking.
Banco de dados e disco ficam em threads de I/O. Efeito no mundo (entregar item,
teleportar) volta como tarefa para a região do jogador.

### (6) Correio entre regiões
Fila de mensagens entregues no começo do próximo tick da região destino.
Regra: **o padrão é juntar as regiões, não mandar correio.** Correio com atraso
de 1 tick só para mod com módulo que prove que aguenta. Motivo: atraso em rede
de energia ou de itens muda comportamento e pode duplicar ou perder recurso.
Não toleram atraso (hipótese a confirmar por mod): cálculo de crafting do AE2,
redstone sensível a tempo, canais P2P/Quantum, eixos do RotaryCraft.

### (7) Adapter de plugins
- Nos Degraus A e B, plugins rodam exatamente como hoje (fase em série). Compatibilidade total.
- No Degrau C, código de plugin roda um de cada vez (uma trava para todos os plugins) e só entra em paralelo o plugin da lista de liberados.
- `Bukkit.getScheduler()` é interceptado: tarefa com alvo conhecido (jogador, local) vai para a região dele; o resto vai para a thread global.
- **Problema em aberto**: um handler de plugin numa região que lê bloco de outra região (WorldGuard, GriefPrevention, PlotMe fazem isso). Ainda não há solução geral segura. Enquanto não houver, dimensões com muito plugin (spawn, plots) ficam no Degrau B.

### (8) Extras
- Spawn: nunca entra no corte de orçamento; no Degrau C ganha thread com prioridade.
- Alerta: região acima do limite gera aviso no console e para o admin, com dimensão, coordenada, dono do claim (GriefPreventionPlus) e as tile entities que mais pesam.

### (9) Vigia e modo seguro
- Detector de deadlock (threads travadas uma esperando a outra) e de tick preso.
- Violação de dono em produção: registra e, se configurado, rebaixa a dimensão para o degrau anterior sem derrubar o servidor.
- Tudo que não foi classificado fica no degrau mais seguro. Lista "só em série" por mod e por dimensão no config.

### (10) Medição
TPS e tempo de tick por região e por dimensão, comando `/hoshie` para o admin,
perfil por thread no spark. `/forge tps` e Timings assumem tick único e passam a
mostrar só parte da história.

## 5. Casos de borda que o desenho precisa cobrir

| Caso | Tratamento previsto |
|---|---|
| Jogador ou entidade cruza a borda | Regiões vizinhas dentro do raio já são a mesma região; só há travessia real em teleporte. |
| Teleporte e portal | Sai da região de origem no fim do tick, entra na de destino como tarefa. Enquanto isso a entidade não tica. |
| Pistão, fluido, explosão, redstone na borda | Cobertos pelo raio de junção (a borda de uma região nunca encosta em chunk ativo de outra). |
| Chunk loader longe do dono | É uma região como outra qualquer; entra no orçamento. |
| FakePlayer (quarry, autonomous activator) | Um por região, não o cache global do Forge. A confirmar. |
| ~60 dimensões | Cada dimensão é pelo menos uma região. "Uma thread por dimensão" sai de graça no Degrau B. |
| Rede AE2 ligando dimensões (Quantum Bridge) | Junção entre dimensões ou AE2 inteiro em série. Decidir com dados da Etapa 2. |
| Geração de mundo | **Corrigido pelo estudo 0.8:** pré-gerar o mapa não basta. Carregar um chunk já gerado ainda roda geradores de estrutura, e tick aleatório de bloco pode carregar chunk. É preciso uma "porta única" de carga de chunk por mundo (uma thread por vez entra), que a região usa sem esperar a thread global. |

## 5b. O que o estudo das estruturas hostis mudou (tarefa 0.8)

Estudo completo, com arquivo e linha de cada item e conferência independente, em
`docs/pesquisa/estruturas-hostis.md`. Foram 138 estruturas distintas; 41 são de risco
alto já na fase paralela sincronizada (Degrau B). Tudo por leitura de código: nada foi
executado. O que entra no desenho:

1. **Ler bloco já escreve.** O mapa de chunks do Crucible (`ChunkBlockHashMap`) guarda os
   4 últimos chunks lidos em campos comuns. Duas threads lendo blocos ao mesmo tempo
   podem receber o chunk errado. É o primeiro item a tratar (cache por thread), e é o
   mesmo código que custa 3,6% do tick em produção (D-16): o conserto e o ganho andam juntos.
2. **Carga de chunk dentro do tick** precisa da porta única descrita na tabela acima.
3. **Saem da fase paralela para a fase em série:** o envio de chunks ao jogador (hoje roda
   dentro do tick do jogador e mexe em chunks de outras regiões) e o registro de entidades
   no rastreador (quem avisa os clientes).
4. **Listas do mundo** (entidades e tile entities) não mudam de tamanho na fase paralela:
   cada região anota o que entrou e saiu e a thread global aplica na junção. Confirma o
   que já estava previsto.
5. **Rascunhos compartilhados viram "um por thread":** lista de colisão, fila de luz,
   captura de blocos e de drops (usada pelos eventos de plugin), estáticos de dano do
   CraftBukkit, flags de blocos como fornalha e fio de redstone.
6. **Identidade da thread.** O Forge decide "sou servidor ou cliente" pelo nome da thread,
   e o Bukkit compara com a "thread principal". As threads de região precisam se
   apresentar como thread de servidor, senão mods e plugins tomam o caminho errado.
7. **Regra de ordem das travas** (trava = só uma thread entra por vez), para não haver
   deadlock (duas threads paradas, uma esperando a outra): nenhuma trava nossa fica presa
   enquanto um evento de plugin ou de mod é disparado; código de plugin nunca espera por
   recurso de região; os ouvintes de mod não liberados passam por uma única trava "serial".
8. **Teleporte imediato** (pérola de ender, teleporte de plugin) não estava coberto: o
   desenho só adiava portal e troca de dimensão. Entra na lista de casos de borda.
9. **Decisão tomada (reversível): o Degrau B começa só com tile entities e entidades.**
   Ticks agendados, ticks aleatórios e eventos de bloco rodam em outro ponto do tick
   (`WorldServer.tick`, antes de `updateEntities`) e ficam em série no começo. Motivo: é
   onde está o maior custo (47% do tick contra 14%) e deixa de fora dez das estruturas de
   risco. Efeito na estimativa (conta de Amdahl, não medição): só com essa parte e
   4 threads, 84 ms cairiam para cerca de 54 a 57 ms, contra 48 a 53 ms com os ticks de
   bloco incluídos. Sozinho não chega a 20 TPS; depende do Degrau 0. Os ticks de bloco
   entram depois, se o observador da Etapa 2 mostrar que compensa.

Lacunas que o estudo deixou: nenhum cenário de falha foi reproduzido; os jars dos mods
não foram varridos atrás dos mesmos padrões; os singletons dos mods grandes (IC2, AE2,
EnderIO, CoFH, BuildCraft) não foram estudados. Ficam para o observador da Etapa 2.

## 6. Riscos e o que não dá para prometer

1. **Mods com estado global** podem obrigar dimensões inteiras a ficar no Degrau B. O ganho real só aparece com os dados da Etapa 2.
2. **Um terço do tick não é de região** (handlers globais 18%, rede 10%, plugins 5%). Medido em produção. É o teto do paralelismo; o Degrau 0 ataca justamente parte desse terço.
3. **Plugins** no Degrau C são o problema menos resolvido. Folia resolveu proibindo.
4. **Erros de concorrência são raros e silenciosos**: um dupe pode aparecer uma vez por semana. Por isso o teste de conservação e o teste de 24 h são obrigatórios, e por isso o padrão é sempre o lado seguro.
5. **Manutenção**: fork de uma base com poucos mantenedores e build frágil. Mitigar com espelho das dependências e build automatizado.
6. **Máquina de teste**: aqui há 4 núcleos. Ganho de escala só se mede na máquina real.
7. Não existe "zero lag". Uma base que gasta 300 ms continuará lenta para o dono dela.

## 7. Decisões

Aprovadas pelo admin em 2026-11-21:
1. Base: Crucible `staging` em `67b53034`.
2. Ordem em degraus.

Tomadas pelo coordenador em 2026-11-22 (reversíveis; ver `PROGRESSO.md`):
3. Incluir o Degrau 0 e trocar o foco da Etapa 1: de "tirar I/O da thread principal" (vale 2,9% do tick) para "ganhos baratos medidos" (~20%).
4. Grupo de 3 a 4 threads de região, não uma thread por região.

5b. O Degrau B começa só com tile entities e entidades; ticks de bloco ficam em série no início (seção 5b, item 9).

Ainda dependem do admin:
5. Aceitar que base pesada tenha máquinas mais lentas (Degrau A) como forma de isolar o lag.
6. Rodar em produção um medidor de carga por região (só leitura), já que o mundo não pode ser copiado.
