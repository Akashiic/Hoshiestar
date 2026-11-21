# Hoshiestar — pesquisa de estado da arte (Folia, multithreading em Minecraft modado, Mixin 1.7.10, pontos hostis a threads, ferramentas)

Data da pesquisa: 2026-11-21. Metodologia: apenas web. Convenções:
- **[V]** = verificado em fonte citada (URL ao lado).
- **[CG]** = conhecimento geral meu, sem fonte web verificada nesta pesquisa. Tratar como hipótese a confirmar no código decompilado.
- **não verificado** = tentei e não consegui confirmar.
Limitação: a ferramenta de busca devolveu pouco material técnico profundo sobre 1.7.10; várias páginas (GitHub wiki do TickThreading, docs do UniMixins, wiki.vg) deram 404/503.

---------------------------------------------------------------------
## 1. FOLIA — como funciona de fato

### 1.1 Formação de regiões
- [V] Folia é fork do Paper que adiciona multithreading regionalizado; chunks carregados próximos formam "regiões independentes", cada uma com seu tick loop a 20 TPS num pool de threads. "Não há mais main thread; cada região tem efetivamente a sua própria 'main thread'." Não será mesclado ao Paper. https://github.com/PaperMC/Folia
- [V] O "regionizer" agrupa chunks em "region sections" NxN, com N potência de dois (ex.: N=16 → seção (0,0) cobre chunks 0..15; coordenada da seção = coordenada do chunk >> log2(N)). O doc NÃO dá o valor de N. https://docs.papermc.io/folia/reference/region-logic/
- [não verificado oficialmente] Fonte de terceiros (docs do fork Luminol) lista `threaded-regions.grid-exponent: 4` (→ 16x16 chunks), `scheduler: EDF`, `threads: -1` em `config/paper-global.yml`. https://luminol-docs-neo.pages.dev/en/guide/folia/configuration — conferir no código do Folia antes de depender.
- [V] Invariantes (region-logic):
  1. Nenhum par de regiões não-mortas possui a mesma posição de chunk; cada chunk-holder tem exatamente uma região.
  2. Para cada chunk-holder existente, toda posição dentro do **merge radius** deve pertencer à mesma região (o "empty section creation radius" cria seções vazias ao redor para garantir isso). Valores numéricos NÃO documentados.
  3. Uma região em estado *ticking* não pode ganhar novas posições de chunk.
- [V] Estados da região: transient, ready, ticking, dead. `tryMarkTicking` (ready→ticking), `markNotTicking`.
- [V] Merge: região morta x é absorvida por região transient/ready y. "Merge later": região não-ticking x pode enfileirar merge em região ticking y; esta executa os merges ao fim do seu tick. Em `addChunk` com várias regiões vizinhas: mescla as não-ticking; se todas ticking, usa merge-later.
- [V] Split: ao fim do tick, região sem merges pendentes remove seções mortas e tenta dividir; só tenta se houver mínimo de seções (recalculation count) e percentual mínimo de seções mortas (max dead section percent) — valores não documentados.
- [V] Visão geral: regiões adjacentes não podem tickar ao mesmo tempo; região ticking não cresce; nova região começa com buffer de chunks e não pode começar a tickar ao lado de outra. https://docs.papermc.io/folia/reference/overview/

### 1.2 Propriedade (ownership)
- [V] Cada região tem objeto de dados próprio, acessível só pela thread que a tica (`RegionizedData`, existe só em NMS, não na API). https://docs.papermc.io/folia/reference/overview/
- [V] README: "uma região possui os dados de chunk em aproximadamente 8 chunks a partir da fonte de um evento" (explicitamente não garantido). https://github.com/PaperMC/Folia (README)
- [V] Regiões tickam "em paralelo, não concorrentemente": não devem compartilhar dados.
- [V] API: `Bukkit#isOwnedByCurrentRegion`; comandos/eventos de entidade rodam na região dona; comandos de console rodam na global region; modificador `async` de evento deprecado. https://github.com/PaperMC/Folia/wiki/Plugin-Compatibility

### 1.3 Schedulers
- [V] `GlobalRegionScheduler` (`server.getGlobalRegionScheduler()`): tarefas sem região. `RegionScheduler` (`server.getRegionScheduler()`, `execute(plugin, location, runnable)`): roda na região dona da location; não usar para entidades. `AsyncScheduler` (`server.getAsyncScheduler()`): fora do tick. `EntityScheduler` (`entity.getScheduler()`): segue a entidade entre regiões. https://docs.papermc.io/paper/dev/folia-support/
- [V] No Paper "comum" esses schedulers são tratados internamente com mesmo comportamento (portabilidade). Mesma página.
- [V] Internamente: `RegionizedTaskQueue` (executa no próximo tick da região dona de uma location, criando-a se preciso), fila da global region; ticks agendados por `SchedulerThreadPool` ordenado por deadline (EDF efetivo; deadline = início + 50ms). https://docs.papermc.io/folia/reference/overview/

### 1.4 Global region
- [V] Região única, sempre a 20 TPS, nunca dividida/mesclada. Cuida de: game rules, game time global, daylight time, comandos de console, world border, weather. https://docs.papermc.io/folia/reference/overview/
- [V] Contadores: tick atual e redstone time são por região; game time e daylight vêm da global e são copiados no início do tick de cada região. No merge, deadlines são deslocados; no split, copiam-se os números do pai.

### 1.5 Operações entre regiões
- [V] Teleporte simples = passo de transformação + colocação assíncrona; teleporte por portal adiciona busca/criação assíncrona do portal de saída. Entidade com teleporte assíncrono pendente não tica. `Entity#teleport` síncrono quebrado → `teleportAsync`. https://docs.papermc.io/folia/reference/overview/ e https://github.com/PaperMC/Folia/wiki/Plugin-Compatibility
- [V] Sync teleport lança `UnsupportedOperationException: Must use teleportAsync while in region threading` (relato em issue do CombatLogX, via busca).
- [V] Shutdown: thread separada para parar regiões, desabilitar plugins, desconectar jogadores, finalizar teleportes pendentes (portais voltam à origem), salvar mundo e depois jogadores.
- [não verificado] Mecanismo exato de uma entidade cruzando a borda de região (o doc oficial lido não detalha; a região-lógica sugere que o buffer/merge radius evita que entidades vivam fora da região dona).

### 1.6 Por que `folia-supported`
- [V] Só plugins com `folia-supported: true` em plugin.yml/paper-plugin.yml carregam; a flag sozinha não basta, o plugin precisa ser desenvolvido para Folia. README: "tenha suas expectativas de compatibilidade em 0". Sem main thread, `BukkitScheduler` quebra. https://github.com/PaperMC/Folia ; https://docs.papermc.io/paper/dev/folia-support/ ; wiki Plugin-Compatibility.

### 1.7 Limitações documentadas
- [V] API quebrada: portais, respawn, parte do login, TODO scoreboard, carregar/descarregar mundos, `Entity#teleport`. Planejado: eventos assíncronos corretos, load/unload de mundos, checagens de thread mais estritas. (Plugin-Compatibility)
- [V] Comandos desabilitados: Bossbar, Clone, Data, Datapack, Debug, Function, Item, Loot, Reload, Return, Ride, Rotate, Schedule, Scoreboard, Spectate, SpreadPlayers, Tag, Team, TeamMsg, Tick, Trigger, Perf, SaveAll, Restart. https://docs.papermc.io/folia/faq/
- [V] Sem afinidade NUMA/núcleo; temporização degrada com pool saturado. Recomendado ≥16 núcleos físicos, alocar até 80% de CPUs somando netty, chunk IO, workers, GC e tick threads. Indicado para servidores com jogadores espalhados (skyblock/SMP). (FAQ + overview)

### 1.8 O que depende do chunk system moderno do Paper (ausente no 1.7.10)
- [CG, coerente com o doc de region-logic] O regionizer opera sobre "chunk holders" do chunk system reescrito do Paper (estados de carregamento por chunk, tickets, geração/IO em workers próprios). O 1.7.10 tem `ChunkProviderServer` + `LongHashMap` + `ChunkIOExecutor` (Forge) sem holders/tickets; teremos que construir um equivalente (mapa concorrente de chunk → região, tickets/lifecycle, IO assíncrono).
- [CG] Folia também depende de reescrita de teleporte/portais, entity scheduler e checagens de thread no próprio NMS (patches enormes); nada disso existe no CraftBukkit 1.7.10/Crucible.

---------------------------------------------------------------------
## 2. ARTE PRÉVIA: multithreading em servidores Minecraft modados

### 2.1 nallar TickThreading (Forge 1.4.7–1.6.4)
- [V] "Servidor Minecraft modado multithread histórico", conjunto de patches sobre Forge ou Forge+CraftBukkit; versões funcionais 1.4.7–1.6.4; maior pack que funcionou bem: FTB Ultimate original 1.4.7. Lema do README: "Performance over correctness". Branch 1.12 inacabado. https://github.com/nallar/TickThreading
- [V] Post original: ticka múltiplos mundos em threads próprias; ticka entidades/tile entities de cada mundo concorrentemente; movimento de jogador em threads de rede; carga/geração de chunks assíncrona; spawn de mobs reescrito; ferramentas de profiling. Build 1.6.4 era "muito instável/teste", 1.4.7 estável. https://forum.feed-the-beast.com/threads/tickthreading-concurrent-entity-tile-entity-ticks-and-other-optimisations.6498
- [V] Por que abandonado (palavras do autor, README): "enorme carga de manutenção", exigia relatórios de issue constantes e **patches manuais por mod**; sem forma de achar problemas estaticamente, a abordagem era iterativa demais para hobby. Branch 1.12 "não é implementação completa". https://github.com/MinimallyCorrect/TickThreading/blob/17a1fc4d4d151e9720961bea3cac30aa6017d07e/README.md
- [V parcial, relatos de usuários] vazamentos de memória (mobs), lag de geração de chunks em 1.6.4, OOM/exceções de tick. Fórum acima.
- [não verificado] Detalhes de agrupamento por região de tile entities (páginas wiki "Mod Compatibility" e "System Properties" não carregaram).
- Lição: threads por mundo/grupo de TEs com correções manuais por mod não escala em manutenção; precisamos de um modelo de propriedade que não exija patch por mod, e de **modo de segurança/fallback** (mods problemáticos forçados a rodar serializados).

### 2.2 MCMT (jediminer543, Forge 1.15.2/1.16.x)
- [V] Mod Forge que paraleliza ticks de mundos, "chunk environment", entidades e tile entities (cada um com toggle); patcheia código para evitar acesso concorrente a objetos não thread-safe ou os troca por coleções concorrentes; adiciona cache próprio de chunks. Maior ganho vindo de entidades. https://github.com/jediminer543/JMT-MCMT
- [V] Problemas: em singleplayer "queda nominal de 20–50% de TPS" ligado vs desligado; "quase impossível testar todas as interações"; pode quebrar com outros mods e interações vanilla; autor pede para não reportar erros a ele salvo se o dev do mod concordou em suportar; marcado experimental; 84 issues abertas. Mesma fonte.
- Lição: paralelismo "fatiado por tipo de tick" com coleções concorrentes dá pouco ganho e muita quebra; sem noção de região/propriedade, o ganho some em sincronização.

### 2.3 DimensionalThreading (Fabric) e forks
- [V] Uma thread por dimensão; não adianta ter mais threads que dimensões; dimensões ficam em sincronia, então o MSPT é o da mais lenta. Mods de terceiros que não são thread-safe dão problemas; exemplos citados: AE2, Create, Bigger Reactors, Chunky Pregenerator. https://www.github.com/ynf-mc/DimensionalThreading ; https://modrinth.com/mod/dimthread
- Lição: AE2 aparece como mod problemático mesmo no modelo mais grosso (por dimensão); no nosso caso AE2 (fork GTNH) e redes globais exigem tratamento explícito (ver §4).

### 2.4 Outras tentativas relacionadas
- [V, indireto] TickWeave/Async/HariMultiThread (Forge/Fabric modernos) paralelizam trabalho de entidades; avisam para não combinar entre si. Não suportam 1.7.10 (não verificado se alguma suporta). https://www.curseforge.com/minecraft/mc-mods/tick-weave
- Ultramine Core (1.7.10, Rússia): [V] existe repositório público `TheByKotik/ultramine_core` (licença WTFPL, branch `release`, 716 commits), sem descrição/README visível. https://github.com/TheByKotik/ultramine_core . **não verificado**: quais recursos de multithreading/async tem; tente ler o código-fonte (`src/main`) diretamente antes de citar.
- KCauldron/Thermos/Crucible (híbridos 1.7.10): [V] Crucible é fork do Thermos (que é fork do KCauldron), CraftBukkit+Forge 1.7.10, "Java 8–21" em runtime via lwjgl3ify integrado, TimingsV2 implementado, "Backported Bukkit APIs" (alguns exigem o mod NecroTempus). README não menciona features assíncronas. https://github.com/CrucibleMC/Crucible
  Contigo e Rei também são forks do Thermos (https://github.com/OnlineMC/Contigo, https://github.com/iPwnAge/Rei); **não verificado** se algum adiciona async/multithread.
- GTNH: [V] Hodgepodge ("A Hodgepodge of Patches") — README não descreve nenhuma mudança de threading; só speedups/crash fixes pontuais, exige UniMixins ≥0.1.14 e GTNHLib ≥0.2.2. https://github.com/GTNewHorizons/Hodgepodge . **não verificado**: ArchaicFix, CoreTweaks e qualquer discussão de threading do GTNH (nenhuma encontrada). Alternative Chunkloading (1.7.10) só evita "ghost loading", não threading.
- [V] Fórum Forge: "a maior parte do Minecraft não é thread-safe"; colocar blocos de outra thread causa crashes difíceis de rastrear; workaround: copiar dados na main thread e passar a cópia. https://forums.minecraftforge.net/topic/63286-112-safe-operations-to-run-in-seperate-threads (via resultado de busca; conteúdo não lido na íntegra).
- Akarin/Tuinity: [CG] Tuinity reescreveu o chunk system (origem do chunk system do Paper moderno) e o Akarin tentou paralelizar partes do tick; ambos para versões modernas. **não verificado** nesta pesquisa.

---------------------------------------------------------------------
## 3. MIXIN NO 1.7.10

### 3.1 UniMixins
- [V] "Mixin loader para Minecraft 1.7.10, com suporte parcial a 1.8.9–1.12.2"; cada recurso é um módulo; existe jar combinado `-all`. https://github.com/LegacyModdingMC/UniMixins
- [V] Módulos no `all`: **Mixin (UniMix)** — fork mantido pela equipe, baseado no fork FabricMC; **SpongeMixins** (1.7.10), **MixinBooterLegacy** (1.7.10, inclui MixinExtras relocado), **MixinExtras**, **GasStation** (depreciado 0.1.23, removido 0.2.0), **GTNHMixins** (1.7.10, inclui MixinExtras relocado, depende de SpongeMixins), **Compat** (remapeia referências inválidas ao ASM shadeado do Mixin e adiciona info de mixin a crash reports), **Mixingasm** (melhora compat do Mixin com transformers ASM). Só um módulo "Mixin" ativo por vez.
- [V] Versão do Mixin exata do UniMix incluído em 0.3.1: **não verificado**. Busca indicou que a 0.3.1 corrige `ASMRemapperTransformer` (changelog; fonte indireta).
- [V] Compilar com Java 8. Nome do jar começa com `+` para ordenar primeiro (pode precisar de `!`).
- MixinExtras: [V] disponível (incluído nos módulos). Quais recursos/versão: não verificado.

### 3.2 Como um mod declara mixins
- [V] Mixins "normais" rodam antes do Forge descobrir mods não-core → por isso a fase **late**. SpongeMixins (jeito antigo) adiciona mods ao classpath cedo por nome de arquivo ("hacky"); a página recomenda **GTNHMixins** para mods novos. UniMixins README.
- [V] GTNHMixins (https://github.com/GTNewHorizons/GTNHMixins):
  - Arquivo `mixins.<modid>.json` (campos como `required`, `minVersion`, `package`, `refmap`, `target`, `compatibilityLevel`).
  - **Early** (alvo: Minecraft/Forge/coremods): `IEarlyMixinLoader` implementado na classe `IFMLLoadingPlugin`; config `mixins.<modid>.early.json`. Assinaturas (fonte do .java): `String getMixinConfig()` e `List<String> getMixins(Set<String> loadedCoreMods)`. Pacote `com.gtnewhorizon.gtnhmixins`.
  - **Late** (alvo: outros mods): `ILateMixinLoader` + anotação `@LateMixin` (ambos necessários por limitação do `ASMDataTable` 1.7.10); config `mixins.<modid>.late.json`; assinaturas `String getMixinConfig()` e `List<String> getMixins(Set<String> loadedMods)`.
- Para o Hoshiestar: tudo que mira classes vanilla/Forge/CraftBukkit-do-Crucible → **early**; tudo que mira IC2/AE2/etc. → **late**.

### 3.3 Mixin em servidor híbrido (Crucible/Thermos)
- [V] Crucible roda em Java 8–21 (via lwjgl3ify) e é CraftBukkit+Forge 1.7.10. https://github.com/CrucibleMC/Crucible. README do Crucible não menciona Mixin (só como tópico do repo).
- [V] Existe o coremod **Grimoire** (CrucibleMC/Grimoire-legacy, arquivado 2023) que embute Sponge Mixin para 1.7.10/1.12.2 para patchear mods no servidor; seu README diz que **não é compatível com outros projetos** e recomenda UniMixins. https://github.com/CrucibleMC/Grimoire
  → Risco: se o servidor de produção tiver Grimoire e UniMixins juntos, conflito de dois loaders Mixin (UniMixins só permite um módulo Mixin). Conferir o conjunto de mods.
- **não verificado**: se mixins conseguem mirar classes `net.minecraft.*` já remapeadas/patcheadas pelo Crucible/CraftBukkit (nomes SRG vs. nomes Bukkit/`org.bukkit.craftbukkit.*`), problemas conhecidos de refmap/SRG em híbrido, e se o Mixin consegue transformar classes `org.bukkit.*` (carregadas por classloader diferente no híbrido). Nenhuma fonte encontrada. Precisa de teste empírico no servidor real (classe vanilla, classe CraftBukkit, classe de plugin).
- [CG] Em 1.7.10 mixins sobre classes vanilla usam refmap com nomes SRG (`func_xxx`); em ambiente dev/deobf usa nomes MCP. Em híbrido onde o Crucible já aplicou patches de CraftBukkit na mesma classe, `@Inject`/`@Redirect` precisam casar com os descritores finais; preferir `@Inject` com alvo por nome SRG e `@WrapOperation` (MixinExtras) a `@Overwrite`.

### 3.4 Java Agent
- [V, parcial] UniMixins incluir "Mixingasm" mostra que há conflito conhecido entre Mixin e outros transformers ASM; o servidor de produção já usa vários `-javaagent` (informação do enunciado).
- [CG] Java agent (`Instrumentation.addTransformer`/`retransformClasses`) é viável, vê classes antes do LaunchClassLoader e é independente de mixin; útil para: instrumentar classes já carregadas, medir, ou interceptar `java.lang.*`/classes de bootstrap. Custo: ordem de transformers imprevisível com Mixin e agents existentes; retransform não pode mudar assinaturas de classes. Recomendo: Mixin como mecanismo principal (declarativo, auditável), agent só se necessário.

---------------------------------------------------------------------
## 4. PONTOS HOSTIS A THREADS no 1.7.10 Forge/vanilla

Fonte geral: conhecimento do código deobfuscado 1.7.10 (MCP) [CG], salvo onde indicado. Nenhuma fonte web específica por item foi encontrada nesta pesquisa; validar cada item lendo o fonte decompilado do servidor-alvo.

Estado compartilhado do mundo/servidor
1. `World.loadedEntityList`, `loadedTileEntityList`, `addedTileEntityList`, `field_147483_b` (remoção) — `ArrayList`s iterados em `World.updateEntities()`; TEs adicionados durante o tick vão para `addedTileEntityList` (flag `field_147481_N`) e são mesclados depois. Qualquer mod que chama `world.addTileEntity`/spawn de outra thread → `ConcurrentModificationException` (padrão amplamente relatado nos fóruns Forge 1.7.10; ver https://forums.minecraftforge.net/topic/31226-1710-exception-in-server-tick-loop-javautilconcurrentmodificationexceptio).
2. `ChunkProviderServer`: `loadedChunkHashMap` (`LongHashMap`, não thread-safe) + `loadedChunks` (`ArrayList`) + `chunksToUnload` (`HashSet`) — precisa de substituição por estrutura concorrente com consistência entre os três.
3. `Chunk`: `chunkTileEntityMap` (HashMap), `entityLists` (array de listas) e `ExtendedBlockStorage`; escrita de blocos de uma região em chunk de outra.
4. `WorldServer.pendingTickListEntriesHashSet` + `pendingTickListEntriesTreeSet` (`NextTickListEntry`, ordenado por tempo/`nextTickEntryID` estático `nextTickEntryID` na classe) e `pendingTickListEntriesThisTick`; o contador `nextTickEntryID` é `static long` na classe `NextTickListEntry`.
5. Iluminação: `World.lightUpdateBlockList` (`int[32768]`) e `Chunk` skylight — array por World, reutilizado, não reentrante; relatos de gargalo e acesso cruzado de chunk em updates de luz.
6. `IntCache` (`net.minecraft.world.gen.layer.IntCache`): listas estáticas (`intCacheSize`, `freeSmallArrays` etc.) sem sincronização; usado na geração de biomas. Ao gerar chunks em paralelo → corrupção. Precisa ser `ThreadLocal`.
7. `Entity.nextEntityID` (`private static int`) incrementado no construtor; `static Random`s em vários pontos (ex.: `MathHelper`, `Entity.rand` é por instância mas há estáticos em geradores/estruturas); contar com `Random` compartilhado como não thread-safe (é thread-safe porém contendido; `Random` próprio de worldgen não).
8. `PlayerManager`/`EntityTracker`/`PlayerManager$PlayerInstance` e `WorldServer.theChunkProviderServer`: tracker e watcher por mundo, usados por jogadores que cruzam regiões; `ServerConfigurationManager.playerEntityList` (global).
9. `MinecraftServer` globals: contagem de jogadores, `worldTickTimes`, `tickCounter`, `ServerConfigurationManager`, NetworkSystem (`networkManagers`), `MinecraftServer.getServer()` estático.
10. `DimensionManager` (Forge): mapas estáticos `worlds`, `dimensions`, `unloadQueue`, `weakWorldMap` — carga/descarga de dimensões.
11. FML/Forge: `MinecraftForge.EVENT_BUS` e `FMLCommonHandler.bus()` (`EventBus` com listeners em array; registro dinâmico é thread-safe-ish, mas **listeners de mods assumem thread única**); `TickEvent`s (`ServerTickEvent`, `WorldTickEvent`) — mods guardam estado global nesses handlers. Região precisa decidir em qual thread cada evento é disparado.
12. `FakePlayer`s (Forge `FakePlayerFactory`): cache estático de `FakePlayer` por mundo/GameProfile, usados por BuildCraft/MFR/Thermal/OC… acessados de qualquer thread de região.
13. `ForgeChunkManager`/tickets: mapas estáticos por mundo, `ChunkProviderServer.loadChunk` chamado por mods (chunkloaders).
14. `ChunkIOExecutor` (Forge): pool de 1 thread, fila ilimitada [V para o aspecto 1 thread em 1.12 via ChunkFlow; para 1.7.10 CG].

Singletons de mods (todos [CG] — confirmar no código):
- IC2: `EnergyNet` por mundo (`EnergyNet.instance`/`getForWorld`), com listas de `EnergyTile` e *grids* atravessando chunks/regiões; atualização via tick handler global.
- AE2 (fork GTNH): `TickHandler` estático com `ServerTick`/`WorldTick`, grids (`Grid`, `GridStorage`) cruzando chunks/dimensões (túneis, P2P, Quantum Bridge); filas `callables` por mundo.
- EnderIO: redes de conduítes (`ConduitNetwork`) por mundo, ticadas por handler global.
- CoFH/Thermal: `TickHandlerWorld`/`CoFHCore` handlers, `EnergyHelper`, `FluidHelper`, e *ducts* (rede ocorre por tick handler).
- BuildCraft: `TickHandlerCore`, `BuildCraftAPI`/pipes com fluxo de energia/itens entre TEs vizinhos, `FakePlayer`s e `BlockPos` caches.
- Outros que mexem em estado global: OpenComputers (threads próprias de execução Lua e `Network` global), Thaumcraft (`AuraHandler` por mundo/chunk), Botania, Forestry (`TickHandlers`), Galacticraft (`TickHandlerServer`, listas globais de dimensões espaciais), RotaryCraft/ChromatiCraft (Reika; muitos estáticos e dependência de `World` de qualquer chunk), MFR.
- Observação [V]: exemplos reais de mods que falham até em threading por dimensão: AE2, Create, Bigger Reactors (fonte DimensionalThreading em §2.3).

Fronteiras de região específicas do Minecraft
- Redstone/blocos que atualizam vizinhos em chunk adjacente (`World.notifyBlocksOfNeighborChange`), crescimento/fluídos que se espalham pela borda, explosões, lava, spawn de mobs com `getEntitiesWithinAABB` que cruzam chunks. Por isso o Folia usa buffer de chunks/merge radius (§1.1); precisamos de equivalente e de política para "acesso a chunk fora da região dona".

---------------------------------------------------------------------
## 5. FERRAMENTAS PARA TESTAR SEM JOGADORES REAIS

- **node-minecraft-protocol-forge** (PrismarineJS; npm `minecraft-protocol-forge`): [V] suporta handshake cliente `FML|HS`; uso manual `forgeHandshake(client, { forgeMods: [...] })` com lista de `modid`/`version`, ou automático criando o cliente com `version: false` + `autoVersionForge(client)`; exige node-minecraft-protocol ≥0.17; servidor expulsa clientes sem os mods exigidos. https://github.com/PrismarineJS/node-minecraft-protocol-forge
  - [V] node-minecraft-protocol lista 1.7.10 entre as versões suportadas (via busca; https://unpkg.com/minecraft-protocol@1.66.2/docs/README.md). wiki.vg: protocolo FML cobre 1.7.x–1.12.x (https://wiki.vg/User:Pokechu22/Forge; página estava 503 ao ler, dado vindo da busca).
  - **não verificado**: que o plugin funcione especificamente com FML1 do 1.7.10 e que o mineflayer o integre sozinho (relato de fórum: bot mineflayer expulso por servidor Forge sem o plugin). Teste empírico necessário (mod list do pack é grande; o handshake exige registrar todos os mods/IDs — EndlessIDs/IDs extra podem exigir cuidado).
- **spark** em 1.7.10: [V] comandos gerais do spark (doc oficial): `/spark profiler start [--timeout s] [--thread *] [--alloc] [--only-ticks-over ms]`, `/spark profiler info`, `/spark profiler stop`, `/spark tps`, `/spark tickmonitor [--threshold %] [--threshold-tick ms] [--without-gc]`, `/spark health`, `/spark gc`. https://spark.lucko.me/docs/Command-Usage . **não verificado** que a versão do spark do servidor (já instalado) em 1.7.10 suporte todas essas flags; o spark oficial no CurseForge é só Fabric/Forge/NeoForge modernos — o build 1.7.10 do servidor é presumivelmente um port/fork; confirmar `/spark tickmonitor` e `--only-ticks-over` rodando no servidor real. Como o servidor tem spark, comparar `tps`/`tickmonitor` antes/depois por região é viável, mas o spark mede "a" tick thread; com N threads de região precisaremos de amostragem por thread (`--thread *`).
- **Opis** (1.7.10): [V] build 1.2.5 para 1.7.10 (abril 2015) com comandos `/opis_te`, `/opis_ent`, `/opis_ticks`, `/opis_nent`, `/opis_mods`, `/opis_start`, `/opis_stop`, `/opis_help` (via listagem CurseForge). Fork AlexCatze/Opis com GUI. Sem garantia de funcionar em servidor híbrido (não verificado).
- **`/forge tps`**: [CG] comando do Forge 1.7.10 (`ForgeCommand`) que imprime tempo médio de tick por dimensão e TPS; assume modelo de tick único por mundo (reportará valores estranhos/incompletos com ticks por região) — confirmar.
- **Timings (Aikar) no Crucible**: [V] "TimingsV2 is implemented" no README do Crucible (https://github.com/CrucibleMC/Crucible). Comando/uso (`/timings on|report`): [CG], não verificado; e como assume tick único, os "timings" precisarão ser tornados thread-safe/por região.
- Carga sintética: [CG] script de bots com mineflayer espalhados por regiões distantes + pregen de mundo (para isolar custo de geração) é o análogo do que o FAQ do Folia recomenda (pré-gerar o mundo).

---------------------------------------------------------------------
## Implicações para a arquitetura do Hoshiestar (máx. 12)

1. **Particionar por região com regras de propriedade explícitas** (como Folia): seção NxN de chunks, invariantes "um chunk = uma região", merge radius, região ticking não cresce, merge-later/split no fim do tick. Valores (N, raio) são tunáveis; Folia não publica números oficiais (grid 16 só em fonte de terceiros).
2. **Construir o equivalente do chunk system moderno**: o 1.7.10 não tem chunk holders/tickets; precisamos de mapa concorrente chunk→região, ciclo de vida de chunk e IO assíncrono antes de qualquer regionização.
3. **Região "global" única** para: tempo de mundo, clima, console, world border, `MinecraftServer`/`ServerConfigurationManager` e redes globais; nunca dividida.
4. **Teleporte e travessia de borda assíncronos** (passo de transformação + colocação na região destino; entidade pendente não tica; portais com busca assíncrona; shutdown com política para pendentes).
5. **Compat Bukkit**: não replicar o veto `folia-supported`; ao contrário, tratar plugins como código thread-único por padrão — rodá-los na global region/serializados, com `Bukkit.getScheduler()` roteado e API `isOwnedByCurrentRegion`-like. Folia prova que sem isso o plugin quebra (README: compatibilidade esperada "0").
6. **Mods com estado global (IC2 EnergyNet, AE2 TickHandler/grids, EnderIO, CoFH, BuildCraft, OC, Galacticraft)**: modelo de fallback — redes que cruzam regiões forçam merge das regiões envolvidas ou rodam num executor serializado; AE2 já é problema mesmo em threading por dimensão (DimensionalThreading). Lista de mods "serial-only" configurável.
7. **Evitar a armadilha do TickThreading**: nada de patch manual por mod como estratégia principal (autor abandonou por manutenção). Usar Mixins genéricos nos pontos vanilla/Forge + política de isolamento por mod.
8. **Evitar a armadilha do MCMT**: paralelismo por tipo de tick com coleções concorrentes dá perda de TPS e quebras; só paralelizar sob propriedade de região, não por "tipo de loop".
9. **Substituir/localizar estruturas hostis conhecidas** (lista §4): listas de entidades/TEs, `ChunkProviderServer`, tick lists, `IntCache`→ThreadLocal, `nextEntityID`/`nextTickEntryID` atômicos, light update array por região, FakePlayer cache, `DimensionManager`, EventBus (decidir thread de despacho). Validar cada item no fonte decompilado — aqui são [CG].
10. **Mixin early/late via UniMixins/GTNHMixins** (`IEarlyMixinLoader` para vanilla/Forge/CraftBukkit, `ILateMixinLoader`+`@LateMixin` para mods). Antes de implementar: testar empiricamente mixin sobre classes patcheadas pelo Crucible e checar que não há Grimoire coexistindo com UniMixins. Java agent só se Mixin não bastar (ordem de transformers é risco).
11. **Observabilidade desde o dia 1**: `spark` com `--thread *` e `tickmonitor`, métricas por região (TPS, MSPT), Opis/Timings adaptados a múltiplas threads; `/forge tps` e Timings assumem tick único.
12. **Testes**: bots com handshake FML (node-minecraft-protocol-forge, a validar em 1.7.10), mundo pré-gerado, jogadores espalhados e agrupados (testar merge/split) e cenários de borda (rede AE2/IC2 atravessando regiões, teleporte, portais, chunk loaders).
