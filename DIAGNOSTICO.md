# DIAGNÓSTICO: o que foi medido e descoberto

Cada achado tem a evidência (comando, arquivo ou fonte). "Não verificado" quer
dizer exatamente isso. Ambiente de trabalho: 4 núcleos, 15 GB de RAM, Linux,
OpenJDK 21.0.13. Data: 2026-11-21.

## 1. O pacote do servidor

| Item | Valor | Evidência |
|---|---|---|
| Pacote | 939 MB compactado, 983 MB extraído, 3.532 arquivos | `du`, `find` |
| Conteúdo | `server.jar`, `mods/`, `plugins/`, `config/`, `scripts/`, `java9args.txt`, 3 jars `-agent`, 1 arquivo `.policy` | listagem |
| **Não vem no pacote** | mundo, `server.properties`, `bukkit.yml`, `spigot.yml`, `cauldron.yml`, `Crucible.yml`, script de inicialização, `libraries/` | listagem |
| Base | **Crucible** `1.7.10-staging-67b53034`, Forge 10.13.4.1614, Bukkit `1.7.10-R0.1-SNAPSHOT` | `META-INF/MANIFEST.MF` do `server.jar` |
| Java | feito para Java moderno: `-Dcrucible.weAreJava9=true`, `--add-opens`, lwjgl3ify 3.0.31, RetroFuturaBootstrap 1.1.0 | `java9args.txt`, manifest, `mods/` |
| Mods | 227 jars, 107.806 classes; o Forge carrega **292 mods** | `tools/static_inventory.py`, log de boot |
| Coremods | 89 jars com `FMLCorePlugin`, 43 com `TweakClass`, 54 com arquivo de mixin | `docs/dados/inventario-mods.csv` |
| Plugins | 65 jars | listagem |
| Cliente (TEOF 6.3) | 217 jars de mod; 189 iguais aos do servidor, 38 só no servidor, 28 só no cliente | comparação de nomes de arquivo |

### Achados

**D-01. A produção já roda Crucible.** A escolha da base não é uma troca: é continuar
no que já funciona. O commit `67b53034` é de 2026-09-29 e está cerca de 21 commits
atrás da ponta da `staging` (`docs/pesquisa/avaliacao-base.md` §1.2).

**D-02. Mixin sobre o Crucible já funciona em produção.** UniMixins 0.3.1 está
instalado e os mods próprios têm pacotes `mixins/early` e `mixins/late`
(`Akashic-TickRelief`, `Akashic-SaveRelief`, `Akashic-PlayerChunkAsync`).
Evidência: listagem de classes dos jars. Isso responde na prática a dúvida "mixin
alcança classes do Minecraft já alteradas pelo Crucible?": alcança.

**D-03. Parte da Etapa 1 já existe em mods próprios.**

| Mod | O que diz fazer (`mcmod.info`) |
|---|---|
| `Akashic-SaveRelief` 1.0.1 | autosave sem parar o mundo: `level.dat`, `data/*.dat` e estado do ChromatiCraft em thread de I/O |
| `Akashic-PlayerChunkAsync` 1.0.1 | sem descrição; pelo nome, carga de chunk do jogador fora da thread principal (não verificado) |
| `Akashic-TickRelief` 1.2.0 | Matter Overdrive não varre todas as tile entities duas vezes por tick; updates de vizinho não carregam chunk; OmniPipes limitado por tick |
| `Akashic-MekThrottle` 1.1.0 | sem descrição; pelo nome, limitador do Mekanism (não verificado) |
| `Akashic-BQRelief` 2.0.0 | caches para o BetterQuesting |

O Crucible também tem uma thread de iluminação por mundo (estilo PaperSpigot), que vem
**desligada** por padrão (`use-async-lighting: false`), e o `ChunkIOExecutor` do Forge (`docs/pesquisa/avaliacao-base.md` §1.7).
**A Etapa 1 começa por auditar isso, não por reescrever.**

**D-04. O pacote tem segredos.** Arquivos de config com credenciais de banco,
token de bot, chave de voz e de pagamento (por exemplo em `plugins/LuckPerms`,
`plugins/DiscordSRV`, `plugins/LiteBans`, `plugins/PixCraft`, `config/akashicvoice.cfg`).
Nenhum valor foi copiado para o repositório. Recomendação ao admin: o link do pacote
dá acesso a tudo isso para quem o tiver; vale desativar o link depois e trocar as
senhas se ele tiver circulado.

## 2. Inventário estático dos mods

Busca de texto no bytecode (não executa nada). São **indícios**, não prova; a prova
vem do modo observador (Etapa 2). Dados completos em `docs/dados/inventario-mods.csv`.

| Sinal | Jars | Classes | Por que importa |
|---|---|---|---|
| Tile entity que tica (`updateEntity`) | 97 | 931 | é a carga que as regiões dividem |
| `ServerTickEvent` | 55 | 103 | código "uma vez por tick do servidor": não tem região |
| `WorldTickEvent` | 41 | 76 | código "uma vez por tick do mundo": não tem região |
| `WorldSavedData` | 34 | 121 | dados por mundo, compartilhados entre regiões |
| Cria ou usa `Thread` | 94 | 778 | já há threads próprias de mods |
| `FakePlayer` | 67 | 319 | jogador falso com cache global do Forge |
| `ForgeChunkManager` | 26 | 78 | chunk loaders |
| API do Bukkit dentro de mod | 10 | 75 | mods que falam com plugins |

Quem mais aparece:
- Tile entities que ticam: Thaumcraft (56), Galacticraft (53), OpenComputers (51), ImmersivePetroleum (44), Botania (39), AM2 (36), OpenBlocks (36), ChromatiCraft (35), ImmersiveEngineering (35), BuildCraft (32), Draconic Evolution (31), Thermal Expansion (24).
- `ServerTickEvent`: OpenComputers (8), Eln (6), EnderIO (6), kunftig (6), CoroUtil (4).
- `WorldTickEvent`: WarpTheory (17), MatterOverdrive (4), MrTJPCore (4), Botania (3), BuildCraft (3).
- `WorldSavedData`: ImmersivePetroleum (11), Forestry (10), Galacticraft (10), Eln (8), BloodMagic (7), ChromatiCraft (7).

**D-05. Estado global é a regra, não a exceção.** Mais de um terço dos jars com
lógica tem handler de tick global ou dado por mundo. Esses mods não podem ser
divididos por região sem uma política explícita (ver `docs/02-arquitetura.md` §3).

## 3. O servidor liga no ambiente de trabalho

**D-06. O servidor de teste sobe aqui com o modpack real.**

| Medida | Valor | Evidência |
|---|---|---|
| Boot, mundo novo | ~2 min 50 s até "Done" | log, 1ª execução |
| Boot, mundo já criado | ~2 min 05 s até "Done" | log, 2ª execução |
| Carga dos 292 mods | ~75 a 90 s | linhas `Forge Mod Loader has identified/successfully loaded` |
| Dimensões carregadas no boot | **61** | `/forge tps` |
| Threads do processo, ocioso | 317 | `/proc/<pid>/task` |
| Memória residente, ocioso | 5,6 a 5,8 GB (`-Xms4G -Xmx9G`) | `/proc/<pid>/status` |
| Heap em uso, ocioso | 3,3 GB de 9 GB | `spark health` |
| Linhas de log no boot | ~22.600 INFO, 591 WARN, 62 ERROR, 15 FATAL | contagem no log |
| Plugins que falharam ao ligar | nenhum | busca no log |

Os 15 FATAL são um aviso do FalsePatternLib ("Thermos não é suportado"), não um erro.
Os ERROR são de ForgeMicroblock (19), am2pg (11), FML (9) e avulsos; nenhum impede o boot.

O que precisou ser feito para subir:
1. Plugins ligados a serviços reais foram desligados (`scripts/prepare-test-env.sh`).
2. Das 73 bibliotecas que o Crucible baixa no primeiro boot, 3 falharam atrás do proxy deste ambiente e foram buscadas de endereços equivalentes, com o arquivo `.md5` que o Crucible exige (`scripts/run-test-server.sh`). O jar do Minecraft veio da Mojang (SHA-1 conferido).
3. Nenhum `-javaagent` foi carregado (o script de inicialização real não veio no pacote).

**D-07. Encerrar com SIGTERM quebra o laço de tick.** Na primeira execução, o
processo recebeu `kill -TERM` e gerou crash report:
`NullPointerException ... Hashtable.get(Object) is null` em
`MinecraftServer.func_71190_q` (`MinecraftServer.java:1016`). A thread do gancho de
desligamento mexe nas dimensões enquanto a thread do servidor ainda tica.
É um erro de concorrência **que já existe na base**, sem nenhuma mudança nossa.
Com `stop` pelo console o servidor salvou as 61 dimensões e saiu com código 0.
Regra de operação: sempre `stop`. Candidato a correção e a caso de teste.

**D-08. Em teste, mods tentam falar com a infraestrutura real.** Vistos no log:
`akashicvoice` tentando conectar no gateway de voz de produção (recusado),
`akashicfm` baixando o `yt-dlp`, checagem de versão do ForgottenRelics. O script de
preparo agora aponta o gateway de voz para `127.0.0.1` e desliga a instalação
automática de ferramentas.

## 4. Linha de base ociosa (0 jogadores, mundo novo)

| Medida | Valor | Evidência |
|---|---|---|
| TPS | 19,9 a 20,0 | `spark tps` |
| Tick médio do servidor, logo após o boot | 12,7 ms | `/forge tps` (aquecimento) |
| Tick médio do servidor, estabilizado | **3,1 a 3,2 ms** | `/forge tps`, duas leituras |
| Soma do tick das 61 dimensões | 2,6 ms | `/forge tps` |
| Tick mediano / p95 / máximo (10 s) | 2,9 / 5,0 / 38,9 ms | `spark tps` |
| Dimensão mais cara | Dim 0, 0,15 ms | `/forge tps` |

**D-09. Sem carga, não há gargalo; e 80% do tick ocioso é tick de mundo.**
Dos 3,1 ms, 2,6 ms são as dimensões e ~0,5 ms é o resto (handlers globais,
plugins, rede). Isso **não diz nada** sobre produção: com 100 players a proporção
pode ser outra. A medida que decide o teto de ganho das regiões é essa proporção
**sob carga real**, e ela só pode vir do servidor de produção ou de uma cópia do
mundo com bots (ver `docs/03-plano-etapa-0-1.md`, tarefa 0.4).

**D-10. Mundo novo contamina a medição.** Perfil de 90 s com Java Flight Recorder
(local, nada enviado para fora): 96,8% das amostras de CPU estão na thread do
servidor, e 66% delas em geração de ruído de terreno chamada pelo Galacticraft
(`MapGen.biomeMapOneChunk`, a partir de `TickHandlerServer.onServerTick`), que
desenha o mapa do planeta quando o mundo é novo. Não é o perfil de produção.
Consequência: medições de linha de base precisam de uma **cópia do mundo real**
ou de esperar esse trabalho terminar.

**D-11. O profiler do spark envia o resultado para um site externo.**
`spark profiler start --timeout` publica o perfil em serviço de terceiros ao terminar.
Foi cancelado antes de enviar. No projeto, o perfil é feito com Java Flight Recorder
(`jcmd <pid> JFR.start`), que grava em arquivo local. Para o admin, usar o spark em
produção é escolha dele (o roteiro avisa).

## 5. Pesquisa (resumo; completo em `docs/pesquisa/`)

- **Base**: Crucible `staging` é a única opção viva; GPLv3; build com Gradle 8.14.3, fork do ForgeGradle e JDK 8; sem checagem de thread (`AsyncCatcher` ausente). Thermos está arquivado desde 2019.
- **Folia**: funciona porque proíbe plugins não adaptados e reescreveu o sistema de chunks do Paper; nada disso existe na 1.7.10.
- **TickThreading** (Forge 1.4–1.6): abandonado pelo autor por exigir patch manual por mod.
- **MCMT** (1.15+): paralelismo por tipo de tick custou 20–50% de TPS.
- **DimensionalThreading**: AE2 quebra até com uma thread por dimensão.
- **Não verificado**: bots com handshake do Forge em 1.7.10; valores numéricos do regionizador do Folia; a lista de estruturas hostis a threads da 1.7.10 (é conhecimento geral, falta conferir no código decompilado); Ultramine.

## 6. Perfil de produção (relatório do spark enviado pelo admin)

Fonte: relatório exportado do spark, 10 minutos, lido offline com `tools/spark_report.py`.
Análise completa e revisão independente em `docs/pesquisa/perfil-producao.md`.
Os números abaixo foram recalculados pelo revisor a partir do arquivo bruto.

| Medida | Valor |
|---|---|
| Duração / ticks | 602 s / 7.113 ticks |
| Jogadores | **8** |
| TPS médio | **11,8** |
| Tempo por tick da thread principal | **84,2 ms** (o alvo é 50) |
| A thread principal dorme? | não, nunca: está sempre no limite |
| Chunks carregados / dimensões | 4.100 a 4.800 / 60 |
| CPU do processo | cerca de 40% de 7 vCores (2,8 núcleos) |
| Heap | 10,3 de 14 GB; pausas de coleta de lixo: ~0,3% do tempo |

### Onde vai o tick

| Fase | % do tick | ms por tick |
|---|---:|---:|
| Tile entities | 26,4 | 22,3 |
| Entidades | 20,7 | 17,4 |
| Tick de mundo (blocos, spawn de mobs, envio de chunks) | 13,8 | 11,6 |
| Rede (pacotes; 7,2 pontos são o tick do próprio jogador) | 10,3 | 8,7 |
| Handlers globais de mods depois do tick do servidor | 7,9 | 6,7 |
| Handlers de fim de tick de mundo | 5,5 | 4,6 |
| Handlers globais de mods antes do tick do servidor | 4,3 | 3,6 |
| Laço das listas, rastreador de entidades, outros | 6,2 | 5,2 |
| Agendador de plugins | 1,9 | 1,6 |
| Salvamento, descarga e fim de carga de chunk | 0,5 | 0,4 |
| Fora do tick (não lido pelo profiler, coleta de lixo) | 1,3 | 1,1 |

### Por tipo de trabalho

| Tipo | % | O que é |
|---|---:|---|
| Local de região, "puro" | 56,7 | entidades, blocos, spawn, tile entities sem rede de mod |
| Local de região, mas toca estado global de mod | 10,6 a 12 | matriz de indução, Entangloporter e cabos do Mekanism, conduítes do EnderIO, EnderStorage |
| Handlers globais (sem região) | 18,4 | BetterQuesting, redes do Mekanism, AE2, EnderIO, IC2/Uncomplication |
| Rede por jogador | 10,3 | no Folia isso roda na região do jogador |
| Plugins Bukkit | ~5 | agendador 1,9; ProtocolLib ~2,2 (dentro do envio de pacotes); listeners 0,65 |
| Disco, carga e geração de chunk, luz | 2,9 | já quase todo fora do tick |

### Achados

**D-12. O gargalo é uma thread só, e sobram núcleos.** Com 8 jogadores o servidor já
está a 11,8 TPS. A thread principal gasta 84 ms por tick sem nunca dormir, enquanto o
processo usa menos de 3 dos 7 núcleos. A premissa do projeto está confirmada por medida.

**D-13. Dois terços do tick são trabalho de região.** É a parte que regiões em paralelo
podem dividir. O outro terço (handlers globais, rede, plugins) é o teto.

**D-14. Estimativa de ganho (conta, não medida).** Lei de Amdahl: tempo = parte em série +
parte paralela ÷ número de threads.

| Cenário | 3 threads | 4 threads | 5 threads | Metade da carga numa base só |
|---|---|---|---|---|
| Conservador (o que toca estado global fica em série) | 52 ms | 48 ms | 46 ms | 60 ms |
| Otimista | 46 ms | 42 ms | 39 ms | 56 ms |
| Com rede por região (Degrau C) | 42 ms | 37 ms | 33 ms | 53 ms |

Com perda de 30% por concorrência (o MCMT perdeu de 20 a 50%), o conservador com 4 threads
vira 53 ms. Ou seja: o paralelismo leva este perfil de 84 ms para perto de 50 ms. **Chega
a 20 TPS, mas sem folga.**

**D-15. O orçamento por região sozinho (Degrau A) não chega a 20 TPS neste perfil.**
Mesmo tirando do tick uma base que concentrasse metade do trabalho de região, sobram
56 ms. Só chegaria a 50 ms se uma única região tivesse 60% ou mais do trabalho.
**O perfil não diz como a carga se distribui pelo mapa.** Essa é a medida que falta.

**D-16. Há cerca de 20% do tick em quatro pontos baratos de atacar, sem threads.**

| Ponto | % do tick | O que é |
|---|---:|---|
| Leitura de energia da matriz de indução do Mekanism | 8,3 | `SynchronizedMatrixData`: 6,0 vêm das tile entities e 2,2 da rede de energia do Uncomplication lendo a matriz todo tick |
| BetterQuesting | 4,6 | handler de tick do servidor (antes e depois) |
| `ChunkBlockHashMap.get` do Crucible | 3,6 | mapa de chunks herdado do Thermos |
| Ars Magica 2 | 3,6 | handler em toda atualização de entidade viva |

20% de 84 ms são 17 ms. Somado ao paralelismo, é o que dá folga.

**D-17. A Etapa 1 do plano original vale pouco aqui.** Disco, carga, geração, luz e
salvamento somam 2,9% do tick. `SaveRelief`, o Hodgepodge e o Crucible já tiraram quase
tudo da thread principal. Só interessam os picos.

**D-18. A "File IO Thread" queima um núcleo inteiro parada. Verificado e com correção.**
No perfil de produção ela passa 95% do tempo em `Thread.yield` (um laço que gira sem
dormir), por causa da opção `B:dontSleepOnThreadedIO=true` do `hodgepodge.cfg` combinada
com um patch do Crucible. Medido no servidor de teste, ocioso, depois de um `save-all`:

| `dontSleepOnThreadedIO` | CPU da "File IO Thread" |
|---|---|
| `true` (produção hoje) | **101,4% de um núcleo** |
| `false` | 0,1% de um núcleo |

Nos dois casos o servidor salvou e parou limpo. Não muda o TPS de hoje (a thread principal
não disputa esse núcleo), mas devolve 1 dos 7 núcleos, que as threads de região vão precisar.
Ressalva: testado só com servidor ocioso; em produção, aplicar num reinício e observar o salvamento.

**D-19. Os picos.** O pior tick do período (5.219 ms) cai na mesma janela de um minuto em
que há 7,2 s de crafting em massa num terminal do AE2 (`SlotCraftingTerm.craftItem`).
Que seja o mesmo tick não foi verificado: o spark só guarda janelas de um minuto.

**D-20. Plugins custam pouco.** Cerca de 5% do tick, e quase metade é o ProtocolLib
interceptando pacotes enviados.

## 7. O stall de 30 segundos (relatório enviado pelo admin)

Análise completa em `docs/pesquisa/stall-report.md`.

**D-21. Um clique de um jogador parou o servidor inteiro.** A thread principal estava
dentro da habilidade de mover área do ChromatiCraft (`AbilityCalls.shiftArea`), chamada
por um clique direito, durante a fase de rede do tick. A habilidade percorre uma caixa
inteira de blocos e **não tem limite de tamanho nem de tempo**; o custo para o jogador só
é conferido depois do laço. Das 411 threads, só essa estava trabalhando. Uma thread de
rede ficou bloqueada esperando a mesma trava (logins e conexões daquela thread pararam).

O que isso ensina:
- Orçamento por região (A) e fase paralela sincronizada (B) **não teriam evitado**: o pacote é tratado na parte em série e nenhum orçamento interrompe uma chamada em curso.
- Só regiões independentes (C), com o pacote tratado na região do jogador, isolariam.
- Correção barata e imediata: um mixin com teto de volume e de tempo nessa habilidade, e um vigia que diga **quem** clicou e **onde** (o relatório de hoje não diz).

**D-22. Metade das threads é do Distant Horizons.** 208 das 411 (3 por dimensão, 64
dimensões), todas dormindo. Não disputam CPU, mas mostram que "uma thread por dimensão"
não é modelo: o que cabe em 7 núcleos é um grupo de 3 a 4 threads de região atendendo
uma fila.

## 8. A base Crucible (estudo do código)

Síntese em `docs/04-estudo-crucible.md`.

**D-23. A base é reconstruível.** O jar compilado aqui a partir de `67b53034` tem o mesmo
manifesto e as mesmas 5.318 entradas do jar de produção; 5.317 idênticas (a diferença é
um arquivo de patches binários que muda a cada build). O jar reconstruído ligou o servidor
de teste com 292 mods e parou limpo. Precisa de JDK 8 e JDK 17.

**D-24. Causa do D-07 encontrada.** O gancho de desligamento chama `stopServer()` sem parar
o laço de tick; o Forge remove a dimensão de `worldTickTimes` enquanto o tick ainda a usa.
Ainda existe na ponta do upstream. Depois do erro, `stopServer()` roda uma segunda vez em paralelo.

**D-25. `tileentities.yml` e `entities.yml` já desaceleram por tipo, mas não por lugar.**
O cache é por classe e fixa o primeiro mundo; não há granularidade por chunk; todas as
instâncias ticam juntas. Não serve para isolar uma base.

**D-26. Eventos de plugin passam por uma trava global** (`SimplePluginManager.callEvent`).

**D-27. Mods leem campos públicos do mundo direto**: `playerEntities` em 63 jars,
`loadedEntityList` em 34, `loadedTileEntityList` em 26.

**D-28. O agente `Akashic-nChat-ChainColor` se desliga no boot** ("Target SHA mismatch;
disabled"): espera o nChat 2.0.4 e o servidor tem o 2.0.5. Provavelmente igual em produção.

## 8b. Achados dos testes do medidor (2026-11-22)

**D-29. Mundo novo com semente aleatória pode travar o boot para sempre.**
Num dos boots de teste o spawn caiu num bioma Magical Forest e a geração de uma
árvore do Thaumcraft não terminou: a thread do servidor ficou mais de 27 minutos no
mesmo ponto (`WorldGenBigMagicTree.genTreeLayer:185`), parada em "Preparing spawn
area: 81%". Verificado por três dumps de thread; não havia código do medidor na
pilha. Como o servidor ainda não tinha terminado de ligar, o `stop` nunca foi lido.
- Correção no projeto: `scripts/prepare-test-env.sh` agora fixa a semente do mundo de teste (uma semente que já ligou normalmente). `HOSHIE_SEED` troca.
- Não verificado: se o laço é infinito ou só muito longo; se o mesmo defeito pode acontecer em produção ao gerar chunk novo nesse bioma (seria outro tipo de stall como o D-21). A semente que travou se perdeu, porque o mundo nunca chegou a salvar.
- Consequência para as regras: "parar só com `stop`" não cobre servidor que travou antes de ligar. O caso ficou resolvido por um reinício do contêiner; a regra para o próximo caso está nas pendências do `PROGRESSO.md`.

**D-30. Um bot mínimo entra no servidor de teste com o handshake do Forge.**
`tools/test_bot.py` (Python puro, só conecta em `127.0.0.1`) faz o login offline,
o handshake FML com a lista de mods do pacote, entra no mundo e manda comandos de
chat. Verificado no servidor de teste com os 292 mods: o `IronDome` não barrou.
Serve para manter os mundos ticando e testar comandos como jogador. Não anda, não
carrega chunks novos e não simula carga: a tarefa 0.6 (bots de carga) continua aberta.

## 8c. Estruturas hostis a threads (tarefa 0.8, estudo de código)

Completo em `docs/pesquisa/estruturas-hostis.md`. Quatro áreas, cada uma com um
mapeador e um conferente independente: 95 itens confirmados, 40 corrigidos, 1 refutado
e 23 acrescentados pelos conferentes. Verificado por leitura de código com arquivo e
linha; **nada foi executado**.

**D-31. São 138 estruturas distintas; 41 têm risco alto já na fase paralela sincronizada**
(87 em regiões independentes).

**D-32. Ler um bloco grava em estado compartilhado.** `ChunkBlockHashMap.get` guarda os
4 últimos chunks em campos comuns (`thermos/wrapper/ChunkBlockHashMap.java:40,105-133`,
conferido pelo coordenador). Com duas threads, uma leitura pode devolver outro chunk.
Nem uma fase "só de leitura" é segura sem tratar isso.

**D-33. Carregar chunk já gerado ainda roda geradores de estrutura**, e tick aleatório
pode carregar chunk. Pré-gerar o mapa não elimina a geração de dentro do tick.

**D-34. O envio de chunks ao jogador e o registro no rastreador de entidades rodam dentro
do laço de entidades** e mexem em dados de outras regiões. Têm que sair da fase paralela.

**D-35. A ordem real do tick por mundo** é `tick()` (ticks agendados, aleatórios e
eventos de bloco), depois `updateEntities()` (entidades e tile entities), depois o
rastreador (`MinecraftServer.java:963,986,1010`, conferido). Os ticks de bloco não estão
dentro de `updateEntities`.

**D-36. Não existe checagem de "thread errada" na base** (o `AsyncCatcher` do Spigot não
está presente). As checagens de dono de região terão que ser criadas. Os pools de
`AxisAlignedBB` e `Vec3`, citados na pesquisa geral, não existem na 1.7.10.

**D-37. Três defeitos antigos apareceram na leitura** (não verificados em execução):
remoção da entidade errada num caminho de teleporte, um laço que pode não terminar no
`LongHashSet`, e uma correção de cursor de lista que não tem efeito. Candidatos a
correção separada, como o D-07.

## 8d. Observador da Etapa 2 (2026-11-23, carga sintética)

Completo em `docs/06-relatorio-observador.md`. Todo número é de carga sintética (bases por comando, bots, 4 núcleos,
uma rodada por condição): **indicação, não medição de produção**.

**D-38. Nenhum código de mod foi visto tocando outra região.** Os acessos de mod que cruzam seção são a vizinhos a
1 chunk (ChromatiCraft ~2,6 por tick, Forestry ~1,6, EnderIO e Mekanism 1,0, gadomancy ~0,6) e ficam dentro da
região pela regra de junção. Esperado: as bases da carga não se ligam umas às outras.

**D-39. As leituras "longe" (241 a 527 chunks) são artefato do observador: contexto velho.** Em `tickUpdates` o
`getBlock` da próxima entrada roda antes do gancho; no tick aleatório, a checagem de luz do chunk seguinte
(`chunk.func_150804_b`) roda no contexto do bloco anterior; no laço de entidades falta o fim de contexto (achado 7 da
revisão). Conferido no fonte, não reproduzido.

**D-40. O observador não vê redes que guardam referência.** BuildCraft (`TileBuffer` só chama `getTileEntity` quando
o vizinho guardado fica inválido) e IC2 (`EnergyNetLocal` sem nenhuma chamada a `getTileEntity`), conferidos com
javap. "Não cruzou" não quer dizer "não acopla"; redes de mod precisam de módulo.

**D-41. Carga de chunk que já existe no disco não passa por `originalLoadChunk`.** Entra por `ChunkIOExecutor`
(síncrono ou assíncrono, `ChunkProviderServer.java:188-199`). Por isso a T3 deu 0 em todas as rodadas; a porta única
de carga do desenho tem de cobrir esse caminho.

**D-42. A lista de tiles só é usada de fora em contexto serial.** 2,11 milhões de chamadas de fora em 584 s, 100%
seriais, 0 modificações, 0 trocas do campo de fora; usuários: Akashic-TickRelief (`get`/`size`) e Mirai (`size`).
Critério 2 do plano: lista por região desnecessária no Degrau B. O Crucible troca o objeto da lista ~4 vezes por
segundo.

**D-43. Custo do observador.** Ligado: 1,9 a 2,4% das amostras de CPU da thread do servidor (JFR), cerca de 2× a
estimativa do jar (0,56 a 1,11%); dentro da meta de 5%. Desligado: mediana 24,6 × 24,5 ms (ruído maior que a
diferença) e 1 amostra em 10.418.

**D-44. A válvula do observador para na primeira janela de cada boot.** A primeira foto das regiões custa 41 a 73 ms
com o código frio (depois, ~2,5 ms) e cai inteira nos primeiros 100 ticks. Defeito de uso, não custo real.

**D-45. A geometria das regiões se comporta como o estudo previu.** 4 bases a 30 chunks viraram uma região de
16 seções com 41% dos tiles; 4 bases a 80 chunks viraram 4 regiões; menor distância entre regiões 49 a 79 chunks.

**D-46. Leituras de handlers globais ficam sem dono.** 6,76 milhões de leituras de bloco em "mundo depois do tick"
(e 2,5 mi em "outro", 1,4 mi em "rede") em 584 s ficam só como contagem, sem mod nem destino. A lista P1 por dimensão
não sai do relatório atual.

**D-47. Entidades crescem entre rodadas com o mundo restaurado** (104 → 1.067 na mesma foto; ~390 em cada uma de
duas bases do layout a). Não explicado.

**D-48. O ganho de energia do Mekanism depois de um stop é do próprio Mekanism.** Aparece com o recurso desligado
(+1,9952e6 J, igual ao ligado); a célula de indução nunca marca o chunk ao mudar de energia (javap), e `save-all` não
resolve. Marcar o chunk antes do stop evita.

*Depois das correções do observador (jar `7161f795…a9668`, boot de confirmação único, varredura estática; detalhe
em `docs/06-relatorio-observador.md` §2.4, §4 e §8.4):*

**D-49. As redes dos mods grandes são objetos globais andados em handler de tick, não no tick do tile** (varredura
estática de 378 jars, 8 casos conferidos com javap). Um objeto por servidor: Mekanism (`TransmitterNetworkRegistry`,
todas as dimensões, no fim do `ServerTickEvent`; `TransporterManager.flowingStacks` estático), EnderIO
(`ConduitNetworkTickHandler`, `IdentityHashMap` de redes), MFR (`GridTickHandler` × 3 estáticos), Eln (`Simulator`),
AE2 (`TickHandler.INSTANCE`; cálculo de crafting num `ExecutorService` estático), Galacticraft (listas estáticas de
redes no `TickHandlerServer`). Por mundo: IC2 (`WorldData.mapping` estático → `EnergyNetLocal`, mais contadores
estáticos de id). BuildCraft guarda o vizinho (D-40). Nada disso passa pelo `World`: o observador não vê. Para o
Degrau B, quem registra/invalida rede a partir do tick do tile escreve em coleção comum sem trava; quais métodos
fazem isso: não verificado. Brutos: Q1 2.796 campos estáticos de coleção (139 jars), Q3 2.508 (134), Q4 166 handlers
(76), Q5 885 (70). O Q4 não pega handler de `TickEvent` genérico (é o caso do AE2).

**D-50. O contexto velho (D-39) era a causa das leituras longas.** Com a correção, nenhum acesso de tile, entidade,
tick agendado ou aleatório passou de 2 chunks em 600 s; cruzamento de região por esses contextos: 0 (antes 1.030 a
2.757 do vanilla, 40 do CoFHCore). Restam 124 acessos a 64+ chunks (até 473), todos do contexto `jogador_rede`;
origem não investigada.

**D-51. A válvula corrigida (D-44) deixa a primeira observação do boot rodar.** 600 s, 11.157 ticks, 0 avisos; a
primeira foto custou 85 ms e caiu na janela de aquecimento (3,08%), fora da conta. Custo estimado 1,42% médio /
2,76% pior trecho; JFR 0,76% de todas as amostras de CPU (uma rodada, sem janela "parado").

**D-52. O IC2 escreve direto na lista de tiles do `World`.** `TileEntityBlock$1.tickCallback` faz
`loadedTileEntityList.add(this)` (javap) quando o tile termina de carregar, na fila de callbacks do IC2, em contexto
serial (282 vezes na confirmação, 0 em contexto de região). Não muda o D-42: a lista não muda de tamanho na fase
paralela por causa dele.

**D-53. Com a observação desde o Done, a T3 contou 3.813 cargas de chunk novo** (andarilhos), quase todas em contexto
"outro" sem origem (3.372 com pilha do hodgepodge), 74 em `jogador_rede`, 0 em tile/entidade. Carga vinda do disco
continua fora (D-41).

## 8e. Orçamento de tick por região, Etapa 2.5 (2026-11-26, carga sintética)

Completo em `docs/08-relatorio-etapa-2.5.md`. Jar `fc377b79…eb622`. Todo número de servidor é de carga sintética:
**indicação, não medição de produção**.

**D-54. O orçamento isola a base pesada.** Base forçada a 150,7 ms por rodada: cortada para 1 tick a cada 25; demais
regiões 19,3 a 19,8 TPS; servidor inteiro p95 21,3 ms, p99 164, máx 575,9 (sem corte: 6,3 TPS, 159 ms). O p99 é a
rodada da base cortada: numa thread só, ela continua longa em ~4% dos ticks.

**D-55. A fornalha vanilla do Crucible não fica mais lenta sob corte.** O patch do CraftBukkit usa `elapsedTicks =
MinecraftServer.currentTick - lastTick` (`TileEntityFurnace.java:240-264` do fonte decompilado): fornalhas cortadas a
1/25 fizeram ~10 lingotes por mil ticks, igual ao controle. Sem perda (soma minério + lingote constante). Outras máquinas
que contam pelo relógio: não levantadas.

**D-56. Região cortada que se une à região protegida do spawn perde o corte.** A proteção vale para a região inteira:
com um bot ligando a base pesada ao spawn, o servidor caiu a 5,4 a 6,8 TPS por 70 a 90 s. No layout a, o spawn já cai
dentro de uma das 4 bases. Em produção, base pesada perto do spawn (ou ligada a ele por chunks carregados) nunca é cortada.

**D-57. Ao trocar de `medir` para `cortar`, o corte começa sem alerta novo** quando o alerta "passaria a ticar 1 a cada
N" já saiu no modo medir (o estado "corte avisado" não distingue os modos; `Nucleo.avaliar`). Menor.

**D-58. Limites da ferramenta de teste (só com `-D`).** `hoshie regioes foto` quebra no modpack (EndlessIDs recusa
`getBlockLSBArray`); `contar` só soma tiles que ticam (não vê as células da matriz) e conta tiles de estruturas do
mundo; na divisão de região, as duas partes herdam a espera forçada (`Estado.esperaMs`) e passam a pagar 150 ms. Ids de
região mudam a cada recálculo.

**D-59. Defeitos das ferramentas de carga sintética.** No 1.7.10, `/setblock` ignora id de item por nome no NBT: os
baús e fornalhas de `tools/carga_sintetica.py` nasciam vazios (as cargas anteriores só tinham as máquinas trabalhando);
`docs/mekanism.md` do fork usa `electricityStored:4.0E8d`, que o `/setblock` não lê (vale `400000000.0d`). Neste mundo
os chunks do spawn não ficam carregados sem jogador; um plugin leva jogador novo ao spawn dele ~4 s após o login;
`save-all` trava o tick por 1 a 4 s.

**D-60. Ticks isolados longos sem causa achada** (575,9, 356,2, 421,6 e 779,7 ms) em ticks em que a base cortada não
rodou e nenhuma região passou de 40 ms: fora do orçamento; sem JFR nesses testes. Também 4 `NullPointerException` do
WorldGuard em `PlayerTeleportEvent.getTo()` nulo num teleporte do teste (fora do orçamento).

**D-61. Revisão do orçamento: 4 achados importantes e 5 menores, nenhum de perda.** Todos corrigidos antes do jar
testado (`169e0eeb` a `f422aad4`), 21 casos adversários verdes nos JDKs 8 e 21. As correções não tiveram segunda
revisão; `a00aeac6` mexe na fila entre a thread do comando e a do servidor.

**D-62. Desligado, o jar novo fica dentro do ruído.** Medianas 9,98 × 11,03, 10,25 × 9,46 e 6,99 × 7,03 ms (novo ×
anterior); a diferença acompanha a ordem das medidas, não o jar. Ligado sem corte: custo não medido no modpack.

**D-63. Um teste antigo do chunkmap é instável.** `TesteChunkmapAdversario` T2 (JDK 21, `-XX:-TieredCompilation`)
exige estouro de pilha em `remove`, `put` e `get` na mesma rodada; no build do zero só o `get` estourou e ele falhou;
passou ao repetir. Não é do orçamento.

## 8f. Degrau B, passos 0, 1 e 5 (2026-11-26 e 27; leitura de bytecode e testes isolados, sem servidor ligado)

**D-64. Lista fechada da primeira entrega: mesa de encantamento, baú (e baú-armadilha) e baú de ender. O funil fica fora.**
Motivos do funil: bytes finais de `TileEntityHopper` não verificados (hodgepodge, EtFuturum e Cauldron injetam no mesmo
método); mixins em `ItemStack`/`Item`/`CraftBlockState` não verificados; composter do EtFuturum; e ChestShop, WorldGuard e
GriefPreventionPlus ouvem os eventos do funil, o que deixaria a região sempre inelegível em produção.

**D-65. No caminho do tick da onda 1, todas as injeções lidas são só leitura** (hodgepodge, AkashicBellow, EndlessIDs,
EtFuturum; 17 transformadores ASM com veredito). Condições: 3x3 chunks carregados e da mesma região; `ChunkRequestEvent`
do DragonAPI com um só observador (`WorldGenInterceptionRegistry`) e profiler de geração desligado; watcher do
`TileUpdateEvent` só o `EMPTileWatcher`. **Não verificado:** os bytes finais das classes (junção de todos os
transformadores); precisa de um boot com dump de classes, que fica no passo 3.

**D-66. Achados novos de leitura:** `Chunk.func_150806_e` remove do mapa do chunk o tile inválido (A1); `provideChunk`
grava `lastAccessedTick` (A2); a mesa usa um `Random` estático, não o `world.rand` (A3); com o `TileUpdate` do DragonAPI
ativo, `skip-tileentity-ticks` não age no laço (A4); o Opis troca as listas de tiles e entidades no construtor do `World`
(A5). Leitura do mapa Koloboke não escreve. Nenhum plugin salva o mundo fora da thread do servidor. A previsão do
orçamento soma o custo das regiões: com paralelo exagera, erro para o lado seguro.

**D-67. H01 corrigido atrás de `chunkmap.cache4Seguro`.** Limite herdado que continua: uma thread de fora pode receber
`null` ou chunk de outra coordenada enquanto o mapa Koloboke cresce (rehash) por um `put` da thread do servidor. A
segurança depende de não carregar chunk durante a fase paralela e do contador de versão do mapa. Com a chave ligada, quem
não é a thread do servidor confere a coordenada do que recebeu.

**D-68. Núcleo de concorrência (sem Minecraft) revisado duas vezes por agente diferente.** A primeira revisão achou um
defeito (`encerrar` largava a espera com interrupção pendente: 85 de 200 no JDK 8; depois da correção, 0 de 200) e três
defeitos hipotéticos que os testes não pegavam; os dois corrigidos. Testes completos em JDK 8 e 21: 242 casos, carga de
10^6 ticks por número de threads, sonda de 40 mil abortos em instante sorteado, zero problemas. 32 defeitos postos de
propósito (mutações): 31 pegos sempre, 1 (`acorda_na_fase_vazia`) escapou uma vez com a máquina carregada.

**D-69. Segunda revisão: código certo, mas três defeitos hipotéticos ainda passariam nos testes (I3):** `abortar` largar
a espera com interrupção pendente; thread de fora interrompida passar pelo portão; `abortar` abrir o portão antes de os
trabalhadores saírem do item. A fechar com testes antes do passo 6.

**D-70. Limites conhecidos do núcleo (menores, abertos):** `Thread.stop` numa thread de região trava o servidor no Java 8
(nada no fork chama; não suportado); operação delegada que não declara o monitor do gerenciador de plugins pode travar
com thread de fora no portão (regra para o passo 6: toda guarda declara o monitor); operação delegada que espera o
resultado de uma thread parada no portão espera sem fim (se a thread de E/S de chunks alcança alguma guarda: não
verificado); a "confirmação do portão" só garante que a thread acordou. Tempos do núcleo com regiões falsas são
indicação, não medição de servidor.

**D-71. Bytes finais (boot com dump, passo 2) confirmam as regras do `updateEntities`** (slot 9; uma chamada de tick,
a do firewall, entre o início e o fim da medição do Opis; janela do DragonAPI limpa). Mudança de veredito: `getClosestPlayer`
tem uma injeção que o passo 0 não viu, do coremod MysteriumLib do AM2.5-LTS (só lê o NBT da bota do jogador). A mesa
volta com trava de SHA-256 desse jar (`Travas.java`, "só mesa"). Outros fatos: o patch `ChunkRequestEvent` do DragonAPI
e o `EntityGuard_Bukkit` do hodgepodge não estão aplicados; o CoFH `writeWorld` está ativo (só em série); o AM2 troca
`TileEntity.hasWorldObj` (escreve num mapa estático), por isso a reconferência usa `getWorldObj() != null`; `world.rand`
é `ThermiteRandom`; nenhum watcher do `TileUpdateEvent` carregado.

**D-72. Desligado = jar anterior (pendência do passo 1):** medianas novo × antigo (c8125bf6), ordem N A A N N A:
4,281/4,167, 4,832/4,530 e 4,714/4,916 ms; média +1,6%, as duas séries sobem com a ordem. Dentro do ruído do D-62.

**D-73. Ferramenta `congelar` (passo 2):** duas execuções desligadas congeladas no mesmo tick dão 0 diferenças nas 707
posições da montagem, mas 7 no mundo inteiro (cristais do ChromatiCraft que crescem por sorteio e um item num chunk que
fica carregado por tempos diferentes). Duas execuções iguais também diferem entre si nesses pontos: é variação própria do
mundo, não da ferramenta.

**D-74. Modo `sombra` (passo 3), modpack, carga sintética:** desligado × sombra = 0 na montagem e 0 na ilha elegível.
Duas horas, 143.120 ticks, 1,7 milhão de tiles no contexto: "seria ilegal" = 0, invariantes = 0, erros nossos = 0.
Baús quebrados e repostos sem violação; profiler do Opis 90 s sem exceção. Custo (JFR, servidor quase ocioso,
indicação): 0,15 ms por tick na classificação. Revisão: brecha "liberar na dúvida" na trava de versão (classe-sonda
sumida = jar ausente), corrigida e revisada de novo.

**D-75. Modo `sombra_ordenada` (passo 4), modpack:** desligado × sombra_ordenada = 0 nas mini-bases e nos chunks da ilha
em lote; 1 diferença no spawn (tempo de espera de um funil em região em série, que também varia só com o orçamento
ligado; causa não achada). Conservação de itens fechada depois de `stop` e religar, e com `injetar quebrar` num baú já
despachado (o baú não ticou, os itens caíram no chão). Os 6 tiles "a mais" no lote são baús de masmorra da camada funda
do AkashicBellow (y de -70 a -78), com conteúdo idêntico nas quatro execuções. Adiados de som, tick agendado e "não
descarregar" nunca rodaram no jogo (só nos testes isolados, que agora pegam 22 de 22 defeitos postos de propósito).

## 9. A máquina de produção

7 vCores e 17 GB de RAM, com heap de 14 GB (`-Xms14336M -Xmx14336M`). Sobram cerca de 3 GB
para tudo que não é heap (código, pilhas de mais de 400 threads, buffers de rede, sistema).
É apertado, mas o perfil não mostra problema de memória. Para threads: a principal, a
coleta de lixo, a rede e o I/O já ocupam parte dos 7; sobram 3 a 4 para regiões.

## 10. O que NÃO foi medido

- **Como a carga se distribui pelo mapa** (por região e por dimensão). Decide se o Degrau A ajuda. Precisa de um medidor próprio rodando em produção (o mundo de 150 GB não pode ser copiado).
- Pico de jogadores: o perfil é de 8 jogadores. Com 30 ou 100, a parte de rede e de entidades cresce.
- TPS com 100–200 players. Depende de bots (a validar) e da máquina real.
- Valores de produção de `spigot.yml`, `cauldron.yml`, `Crucible.yml`, `tileentities.yml`, `entities.yml`.
- Bots de carga (que andam e carregam chunks). O handshake do Forge já funciona com um bot mínimo (D-30).
