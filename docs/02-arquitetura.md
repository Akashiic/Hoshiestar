# Hoshiestar: desenho da arquitetura

Estado: proposta para aprovação. Nenhum código de região foi escrito ainda.
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
| Servidor ocioso (0 players, 61 dimensões): tick médio 3,1 ms, dos quais 2,6 ms são o tick das dimensões | `/forge tps` no ambiente de teste (`DIAGNOSTICO.md`) | Sem carga não há gargalo. Quanto do tick de produção é "de mundo" (paralelizável) e quanto é global (handlers, plugins, rede) **só se sabe com perfil sob carga real**. É a medida mais importante da Etapa 0. |
| A base não tem checagem de thread (`AsyncCatcher` ausente) | avaliação da base §1.7 | Precisamos criar as checagens de dono. |
| Mixins early/late sobre o Crucible já rodam em produção (mods `Akashic-*`) | jars do servidor | O mecanismo de interceptação está provado neste ambiente. |

Conclusão honesta: "cada região em sua thread, com todos os mods, sem reescrever nada"
não é alcançável de uma vez, e para alguns mods talvez nunca. O desenho abaixo chega
ao objetivo nº 1 (lag isolado) cedo e por um caminho seguro, e trata o paralelismo
como ganho progressivo, liberado mod a mod e dimensão a dimensão.

## 3. Ideia central: três degraus, todos com interruptor

```
Degrau A  Orçamento de tick por região      (1 thread)          -> isola o lag
Degrau B  Fase paralela sincronizada        (N threads, em passo) -> usa os núcleos
Degrau C  Regiões independentes estilo Folia (N threads, soltas)  -> isola e escala
```

### Degrau A: orçamento de tick por região (Plano B do prompt)
O servidor continua com uma thread. O mundo já é dividido em regiões (mesma
estrutura dos degraus seguintes), e cada região tem o tempo de tick medido.
Região que estoura o orçamento passa a ticar 1 vez a cada 2, 3, 4 ticks:
as máquinas daquela base andam mais devagar, o resto do servidor fica a 20 TPS.

- Entrega "base pesada laga só ela" sem nenhum risco de concorrência.
- É o mesmo efeito do lag de hoje, só que restrito a quem causa.
- Já dá o alerta ao admin ("região X, base em tal coordenada, 140 ms").
- Limite: não usa os núcleos sobrando. A soma de todas as bases ainda cabe em uma thread.

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

### Degrau C: regiões independentes (estilo Folia)
Cada região tem o próprio relógio de tick. Só para dimensões e mods **liberados**
depois de passarem pelo observador (Etapa 2) e pelos testes. O resto fica no Degrau B.

- É o que escala para 100–200 players espalhados.
- É onde moram os riscos: eventos globais disparados em paralelo, plugins, redes de mod.
- Pode nunca cobrir 100% do pack. Tudo bem: cada dimensão usa o degrau mais alto que aguenta.

Mapa para as etapas do prompt: Degrau A = Etapa 2.5; Degrau B = primeira metade
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
| Geração de mundo | Fora do escopo das regiões; tratada na Etapa 1 (pré-geração e limite por tick). |

## 6. Riscos e o que não dá para prometer

1. **Mods com estado global** podem obrigar dimensões inteiras a ficar no Degrau B. O ganho real só aparece com os dados da Etapa 2.
2. **Parte do tick não é de mundo** (handlers globais, plugins, rede). Essa parte não paraleliza com regiões; se ela for grande em produção, o teto de ganho é baixo. Medir na Etapa 0 com carga real.
3. **Plugins** no Degrau C são o problema menos resolvido. Folia resolveu proibindo.
4. **Erros de concorrência são raros e silenciosos**: um dupe pode aparecer uma vez por semana. Por isso o teste de conservação e o teste de 24 h são obrigatórios, e por isso o padrão é sempre o lado seguro.
5. **Manutenção**: fork de uma base com poucos mantenedores e build frágil. Mitigar com espelho das dependências e build automatizado.
6. **Máquina de teste**: aqui há 4 núcleos. Ganho de escala só se mede na máquina real.
7. Não existe "zero lag". Uma base que gasta 300 ms continuará lenta para o dono dela.

## 7. Decisões que precisam do admin

1. Aprovar o Crucible `staging` em `67b53034` como base do fork.
2. Aprovar a ordem em degraus (A antes de B antes de C), com o portão "vai / não vai" depois da Etapa 2.
3. Aceitar que base pesada tenha máquinas mais lentas (Degrau A) como forma de isolar o lag.
4. Licença GPLv3 e repositório só com patches e código próprio.
