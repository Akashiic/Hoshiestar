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

O Crucible também já tem thread de iluminação por mundo (estilo PaperSpigot) e o
`ChunkIOExecutor` do Forge (`docs/pesquisa/avaliacao-base.md` §1.7).
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

## 6. O que NÃO foi medido

- Perfil sob carga real (com jogadores e bases). Depende do admin.
- TPS com 100–200 players. Depende de bots (a validar) e da máquina real.
- Compilar o Crucible do zero. Planejado na Etapa 0 (precisa de JDK 8).
- Comportamento com os `-javaagent` de produção. Falta o script de inicialização.
