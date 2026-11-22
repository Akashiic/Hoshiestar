# Crucible: história, contexto e vitórias de um híbrido Spigot + Forge

Projeto Hoshiestar. Base estudada: Crucible `staging`, commit `67b53034` (produção), clone local até o commit `cd1de3d` (2026-11-01).
Caminho do clone: `src/Crucible-staging` (abreviado abaixo como `CLONE/`).

Resposta curta ao pedido do admin ("estude o repositório, contexto, histórico, vitórias para um híbrido de qualidade"): o Crucible é a quinta geração de uma linhagem de 1.7.10 que juntou Bukkit/Spigot e Forge. Quase todo o "milagre" do híbrido (plugins rodando sobre um Minecraft com nomes diferentes, blocos de mod virando Material do Bukkit, dimensões de mod virando mundos Bukkit) foi resolvido pelas gerações anteriores e continua dentro do código. O Crucible acrescentou modernização (Java 8 a 21), correções e desempenho. Para o Hoshiestar, o risco principal é mexer no tick e quebrar essas costuras sem perceber.

Legenda de confiança: [V] verificado no clone ou em página aberta nesta sessão; [S] fonte secundária (fórum, README de terceiros); [NV] não verificado.

Aviso importante sobre a evidência: o clone é **raso**. `git rev-parse --is-shallow-repository` retorna `true`, e `git log` mostra só 129 commits, de 2024-03-19 a 2026-11-01. A história anterior (Thermos e antes) não está no clone. O que sei dela vem da web e é de qualidade média.

---

## 1. Linhagem: MCPC+ -> Cauldron -> KCauldron -> Thermos -> Crucible

Termos: **híbrido** = um servidor que roda mods Forge e plugins Bukkit ao mesmo tempo. **NMS** = as classes do Minecraft com nome ofuscado (a, b, c) que plugins antigos usam. **CraftBukkit** = a camada que implementa a API Bukkit em cima do Minecraft.

| Geração | Quem | Quando | Problema que resolveu | Por que acabou |
|---|---|---|---|---|
| MCPC / MCPC+ | Equipe "Minecraft Port Central" (nome do grupo em `github.com/MinecraftPortCentral`, página hoje responde HTTP 451, indisponível por motivo legal) | início dos anos 2010, datas [NV] | Juntar CraftBukkit e Forge para plugins e mods coexistirem | Virou Cauldron (renomeado), ver abaixo. Autor individual: [NV] |
| Cauldron | mesma equipe; código antigo guardado em `github.com/Techcable/Cauldron-Old` ("The old cauldron source code", GPL-3.0, README sem lista de autores) [V] | cerca de 2013 a 2014, datas exatas [NV] | Mesmo objetivo, com Spigot em vez de Bukkit puro | Parou em setembro de 2014, ver DMCA |
| KCauldron | continuação comunitária do Cauldron; autores [NV] | 2014 em diante [NV] | Manter 1.7.10 vivo | Segundo o README do Thermos, teve "períodos de inatividade" no GitLab, e por isso nasceu o Thermos [V] |
| Thermos | organização `CyberdyneCC`; README cita "Robotia" só na seção de doações [V] | repositório arquivado em **2019-01-26** [V] | Corrigir KCauldron mais rápido: menos lag, proteção de mundo contra Forge burlando plugins, patches do Spigot que faltavam, dupes (duplicação de itens) [V] | README: "THERMOS IS NO LONGER BEING DEVELOPED" [V] |
| Crucible | organização `CrucibleMC` | primeiro commit visível no clone: 2024-03-19 (a fork é anterior, data real [NV]) | Fechar as lacunas do Thermos, suportar mods e plugins que quebravam, Java moderno | Ativo (item 4) |

Fontes web desta seção:
- Thermos fork de KCauldron, GPL-3.0, arquivado 2019-01-26, 837 commits: https://github.com/CyberdyneCC/Thermos [V]
- Cauldron-Old (README mínimo): https://github.com/Techcable/Cauldron-Old [V]
- Cadeia "Kettle > Contigo > Thermos > KCauldron > Cauldron > MCPC+" e Kettle arquivado em 2021-03-23: https://github.com/KettleFoundation/Kettle [V para o README, a ordem é a que o README afirma]
- Lista de híbridos (Cauldron "was MCPC+", Thermos "fork of kCauldron, source unknown", Contigo fork do Thermos): https://github.com/birbe/hybrid-minecraft-servers [S]
- Outros descendentes citados nas buscas: Contigo (`gcallant/Contigo`), Kettle, Mohist (1.7.10 marcado como fim de vida no próprio repositório), ThermosX [S]

### O DMCA de 2014 e o efeito no Cauldron
- Fato bem sustentado: em setembro de 2014 o repositório do CraftBukkit foi derrubado e "Cauldron too". A página de história do Sponge diz que a API nova era necessária "as the CraftBukkit repository (Cauldron too) was taken down", lista "Protection against DMCA takedowns" como meta, e data o nascimento do Sponge em 6 a 8 de setembro de 2014. Fonte: https://cdn.jsdelivr.net/gh/Spongy/SpongeDocs-PRs@c539d17/about/history.html [V]
- Quem enviou o aviso e se ele foi a causa real do fim do Cauldron: fóruns discordam. Um relato diz que Wesley Wolfe (Wolvereness) iniciou o aviso contra CraftBukkit, Spigot, Cauldron e MCPC-Plus-Legacy; outro diz que o aviso não veio da Mojang; outro diz que Bukkit e MCPC+ "morreram antes de qualquer aviso" (resultados de busca, fórum Cuberite e outros). Tudo [S], contraditório, **não usar como fato**.
- Efeito prático seguro de afirmar: o código-base legado (Bukkit/CraftBukkit) ficou sob dúvida legal, o Cauldron original parou, e a continuação passou a ser feita por forks comunitários (KCauldron, Thermos). Por isso hoje o Crucible guarda `bukkit/` e `forge/` como subprojetos e distribui como **patches** sobre o código base (`CLONE/patches`, 406 arquivos `.patch` contados com `find patches -name '*.patch' | wc -l`) [V]. Que isso seja uma escolha por causa do DMCA: [NV], é inferência minha.

---

## 2. O que torna um híbrido Forge + Bukkit difícil, e como a linhagem venceu cada ponto ("vitórias")

Tabela: problema, solução, onde está hoje no código (caminho relativo a `CLONE/`).

| # | Problema | Vitória da linhagem | Evidência no clone |
|---|---|---|---|
| 1 | Plugins são compilados contra nomes ofuscados do NMS e do CraftBukkit (`net.minecraft.server.v1_7_R4.*`), mas o servidor roda com nomes SRG/MCP do Forge | Ao carregar cada plugin, o ClassLoader reescreve as classes (remapping) com **SpecialSource** (biblioteca do md_5 para remapear jars). Também remapeia chamadas de reflexão | `patches/org/bukkit/plugin/java/PluginClassLoader.java.patch:86` (cache `jarMappings`), `:198` (`getJarMapping(flags)`), `:210` (`new thermos.ThermosRemapper(jarMapping)`); `patches/org/bukkit/plugin/java/JavaPluginLoader.java.patch:199` (`new JarMapping()`); `src/main/java/thermos/ThermosRemapper.java` (engole erro de assinatura e devolve a original) |
| 2 | Blocos e itens de mod não existem no enum `Material` do Bukkit, que é fixo em tempo de compilação | **Injeção de Material em tempo de execução**: `EnumHelper.addEnum` cria constantes novas no enum | `patches/org/bukkit/Material.java.patch:119-127` (`addMaterial(int id, String name, boolean isBlock)` chama `EnumHelper.addEnum(Material.class, ...)`); `patches/cpw/mods/fml/common/registry/GameData.java.patch` liga o registro de mods ao Bukkit; opção de despejar tudo em `Crucible.yml` (`cauldron_settings_dumpMaterials`, `src/main/java/io/github/crucible/CrucibleConfigs.java:16`) |
| 3 | Tipos de entidade de mod também não existem em `EntityType` | Mesmo truque para entidades, com classe de envoltório `CraftCustomEntity` | `src/main/java/org/bukkit/craftbukkit/v1_7_R4/entity/CraftEntity.java:501-504` (`BukkitEnumHelper.addBukkitEntityType`), `src/main/java/io/github/crucible/entity/CraftCustomEntity.java`, `src/main/java/io/github/crucible/util/BukkitEnumHelper.java`. Commit `d2feb1f` (2026-10-09) reaproveita o `EntityType` quando o mapeamento roda de novo |
| 4 | Dimensões de mod não são "mundos" Bukkit (Bukkit supõe overworld, nether, end) | Cada dimensão do Forge vira um `World` Bukkit; `DimensionManager` ganhou `initDimension(WorldCreator, ...)`. Plugins como Multiverse conseguem criar mundos | `patches/net/minecraftforge/common/DimensionManager.java.patch:29` (import de `WorldCreator`), `:235` (`initDimension`), `:278` (`registerDimension`), `:295` (`addWorldAccess`) |
| 5 | Inventários de mod (tile entities com `IInventory`) não são `Inventory` do Bukkit | Envoltórios (wrappers) que implementam a API Bukkit por cima da interface do Minecraft, e um tipo de inventário customizado | `src/main/java/io/github/crucible/wrapper/ModInventoryWrapper.java:15` (`extends CraftInventory`, `WrappedInventory implements IInventory, InventoryHolder` em `:21`), `src/main/java/net/minecraftforge/cauldron/inventory/CraftCustomInventory.java`, `.../block/CraftCustomContainer.java`. Commit `49ccf04` (2026-10-09, KAMKEEL): registrar tipos de inventário de TE uma vez, não por mundo |
| 6 | Mods mexem em blocos direto, sem passar pelos eventos que plugins de proteção (WorldGuard, GriefPrevention) escutam | **Ponte de eventos**: ações do Forge disparam eventos Bukkit (`BlockPlaceEvent`, `BlockBreakEvent`, `EntityExplodeEvent`...). Máquinas de mod usam **FakePlayer** (jogador de mentira); o híbrido precisa tratá-lo, senão proteção e permissões falham | `patches/net/minecraft/world/Explosion.java.patch:19,101` (`EntityExplodeEvent`), `src/main/java/org/bukkit/craftbukkit/v1_7_R4/event/CraftEventFactory.java:15` (import de `FakePlayer`) e `:978` (`if (!(playermp instanceof FakePlayer))`), `patches/net/minecraftforge/common/util/FakePlayerFactory.java.patch:8` (mapa `fakePlayers` virou público), `patches/net/minecraftforge/common/ForgeHooks.java.patch` |
| 7 | Captura de blocos: Bukkit precisa saber "quais blocos mudariam" antes de decidir (crescimento de árvore, estruturas) | `captureBlockStates` no `World` e, no Crucible, um sistema novo de snapshot que também cobre tile entities | `patches/net/minecraft/world/World.java.patch:636` (comentário "Cauldron add !this.captureBlockStates"), `src/main/java/io/github/crucible/util/WorldChangeHolder.java`, commits `77c062a` e `0b2a9a9` (ver item 3) |
| 8 | Comandos: mods registram comandos no Forge, plugins no Bukkit | Comandos do Forge também entram no mapa do Bukkit; o Crucible filtra e namespaceia | `src/main/java/io/github/crucible/command/CommandRegistrationFilter.java`, `ModCommandNamespace.java`; commits `cb3886e`, `3d77d95`, `16bce30` (2026-07-29) |
| 9 | Permissões: mods usam op/nível de Forge, plugins usam nós Bukkit | Camada de permissões do CraftBukkit continua dona dos jogadores; comandos de mod passam pelo wrapper de comando do Bukkit | `src/main/java/org/spigotmc/VanillaCommandWrapper.java`; detalhe do mapeamento nó a nó: [NV] (não li essa parte) |
| 10 | Mods pesados: cada tile entity e entidade tica todo tick, mesmo as inúteis | **Configuração por tipo**: `tileentities.yml` (por classe de TE) e `entities.yml` (por classe de entidade; no código chama-se `Sushchestvo`, russo para "criatura", herança do KCauldron) | `patches/net/minecraft/server/MinecraftServer.java.patch:160-162` (`cauldron.yml`, `tileentities.yml`, `entities.yml`, comandos `cauldron`, `cauldron_te`, `cauldron_e`), `src/main/java/net/minecraftforge/cauldron/configuration/TileEntityConfig.java`, `SushchestvoConfig.java`, `TileEntityCache.java`, `SushchestvoCache.java` |
| 11 | Desempenho vanilla fraco em 1.7.10 | Patches do Spigot trazidos: `ActivationRange` (entidades longe do jogador ticam menos), `TrackingRange`, `AntiXray`, `TickLimiter`, `WatchdogThread`, `SpigotWorldConfig` | `src/main/java/org/spigotmc/*.java`; `patches/net/minecraft/world/World.java.patch` e `.../entity/Entity.java.patch` chamam `ActivationRange` |
| 12 | Mapas de chunk lentos | Estruturas próprias do Thermos (mapas de chunk em Trove/fastutil, "Thermite") | `src/main/java/thermos/wrapper/ChunkBlockHashMap.java`, `LongHashMapTrove.java`, `VanillaChunkHashMap.java`, `thermos/thermite/ThermiteTeleportationHandler.java` |
| 13 | Mods e plugins que dependem do classe-loader do outro | Barramento de eventos do Crucible: plugin registra handler de evento Forge sem criar "mod fantasma" | `src/main/java/io/github/crucible/api/CrucibleEventBus.java`, `.../eventfactory/PluginClassLoaderFactory.java`; commit `fdd020e` (2024-10-04) |

Observação sobre a ausência de checagem de thread: `grep -rln "AsyncCatcher"` em `src` e `patches` não acha nada (as duas ocorrências de `IllegalStateException("Asynchronous` estão em `CraftScoreboardManager.java` e `CraftPlayer.java`) [V]. Bate com a avaliação do Hoshiestar de que a base não verifica o dono da thread.

---

## 3. O que o Crucible acrescentou sobre o Thermos

### 3.1 Contagem honesta
- 129 commits visíveis, 99 sem merges, 30 merges (23 números de PR distintos) [V: `git log --merges`].
- Autores (por commit): Pétrus Pradella 67 (55 + 12 com outra grafia do nome), Gravita 37 (36 + 1 com grafia "Gravit"), Juan Patricio 13, RealSilverMoon 5, KAMKEEL 3, e um commit cada de brokestar233, RubilaxXxx, George Paton, Bruno Marques Caroba (via merge) [V].
- Por ano de data de commit: 2024: 24, 2025: 25, 2026: 80 [V]. A atividade acelerou muito em 2026.
- README (`CLONE/README.md`) declara as vantagens sobre o Thermos: build estável, correções, desempenho, bibliotecas atualizadas, **TimingsV2**, **Java 8 a 21 via lwjgl3ify integrado**, APIs do Bukkit portadas (algumas exigem o mod companheiro NecroTempus) [V].

### 3.2 Commits agrupados por tema (contagens aproximadas por palavra-chave, sem merges, categorias se sobrepõem)

| Tema | Aprox. | Exemplos (hash, data) |
|---|---|---|
| Java 9+ / lwjgl3ify / RetroFuturaBootstrap (RFB) / ASM | 14 | `47a6202` 2026-05-03 enums cedo no lwjgl3ify; `88c1df1` 2026-05-04 sobe RFB e ASM; `af8df32` 2025-09-19 ASM 9.8 (suporte a Java 25); `897126a` 2025-04-05 RFB 1.0.10; `8026d12` 2026-08-22 compatibilidade jopt-simple 4.x |
| Build, Gradle, CI, release | 15 | `472cfa9` 2025-09-19 Gradle 8.14.3; `7f44245` 2025-09-24 script Kotlin; `6a06ff2` 2026-02-21 `java9args.txt` nos artefatos; `684d4d6` 2025-12-29 versão dev no nome do jar; `667a28e` 2026-10-18 patches com cabeçalho velho |
| Rede, chunks, teleporte, salvamento, desempenho | 22 | `a08bbe3` 2026-08-28 fila de chunks em vários pacotes por tick; `2e33e13`/`24b5acf` 2026-09-01 buffer único exato; `6e3e5ae` nível de deflate configurável (padrão 2); `d717530` 2026-09-06 orçamento de 20 chunks por tick; `a3fd0d0` 2026-08-03 AutoSave sem bloquear; `be49297` LRU O(1); `6ff61ca` 2026-09-29 teleporte |
| Backport de API Bukkit | 4 | `9f9bc6f`, `18092d5`, `bcde836` 2025-06-10 (BoundingBox, RayTraceResult, `world.getNearbyEntities()`); `e22c509` 2026-10-03 `PreCreatureSpawnEvent` |
| Timings v2 | 0 commits novos na janela | `co.aikar.timings` já está no código; `src/main/java/io/github/crucible/CrucibleTimings.java` importa `co.aikar.timings.Timing/Timings`; config `timings_historyLength`, `timings_serverName` em `CrucibleConfigs.java`. Quando entrou: [NV] (anterior ao primeiro commit visível) |
| Dupe / exploit | 0 commits explícitos | `git log -i --grep='dupe\|exploit\|duplicat'` não retorna nada nos 129. O Thermos já listava correções de dupe no README [V]; o Crucible herdou. Não confundir com ausência de trabalho: a história anterior não está no clone |
| Correções de compatibilidade com mods e plugins | 23 ("fix") | `c634b0c` 2025-04-14 MapGenStructure salvava dado global em vez de por mundo (estruturas no mundo errado); `25b26c8` 2025-03-14 crash do HEE; `8915456` 2024-12-18 Minechem; `9d2a3f0` 2026-06-22 RecurrentComplex; `48e59e8` EndlessIDs; `789a188` 2026-10-01 NotEnoughIDs |
| Comandos | 5 | `cb3886e`, `3d77d95`, `16bce30` (2026-07-29), `b4eb26f` RCON não executava comando de mod |

### 3.3 As 10 mudanças mais significativas (critério: tamanho do impacto no servidor ou na arquitetura)
1. `fdd020e` 2024-10-04: `CrucibleEventBus` (plugin escuta evento Forge sem mod fantasma; +321 linhas em 5 arquivos).
2. `aa41da5` 2025-02-21: API `CruciblePluginTransform` (plugins podem transformar bytecode de plugins no carregamento).
3. `77c062a` (data de autoria 2025-03-17, aplicado em 2026-07) e `0b2a9a9` 2026-07-25: novo sistema de captura/snapshot de blocos e tile entities (17 arquivos, +727 -301 no segundo).
4. `2a74d14`/`5881f7e` 2025-12-26 e `6303bee` 2026-06-21: integração do RFB (RetroFuturaBootstrap) e lwjgl3ify, base do suporte a Java 9 até 25 (`java9args.txt` do servidor vem daqui).
5. `a08bbe3` 2026-08-28 (+ `2e33e13`, `24b5acf`, `d717530`): envio de chunks em vários pacotes por tick, buffer único, deflate configurável. Mexe em `EntityPlayerMP.java.patch` (124 linhas).
6. `a3fd0d0` 2026-08-03 + `d882025` 2026-08-08 (RealSilverMoon): AutoSave sem bloquear o tick, LRU O(1), remoção de flush.
7. `6ff61ca` 2026-09-29 e `67b5303`/`f76f910`: teleporte com gestão de chunks melhor, um único `PlayerTeleportEvent` na troca de mundo. **Esta é a base exata de produção** (`67b5303`).
8. `bcde836`, `9f9bc6f`, `18092d5` 2025-06-10 e `e22c509` 2026-10-03: backport de APIs Bukkit (BoundingBox, RayTrace, getNearbyEntities, PreCreatureSpawnEvent).
9. `6cdfc96` 2026-10-13 e `ac070ce` 2026-10-13: handshake FML concorrente e lista de jogadores iterável fora da thread principal. É o primeiro sinal explícito de preocupação com threads no histórico visível.
10. `c634b0c` 2025-04-14: correção do `MapGenStructure` salvando estado global (bug de multi-dimensão, relevante para um servidor de cerca de 60 dimensões).

Menções: `cb3886e` (comandos de mod fora do mapa Bukkit, 2026-07-29) e `61cadb7` 2026-10-10 (ping da tab list a cada `tabListPingInterval` segundos).

---

## 4. Quem mantém, atividade, releases, comunidade

- Mantenedor principal: Pétrus Pradella (67 dos 129 commits, autor de todos os merges de PR do clone) [V]. Gravita (37 commits) é o principal autor da parte de bootstrap/Java moderno/Gradle. Juan Patricio (juanmuscaria) cuida de captura de blocos e patches estruturais. EverNife aparece como autor de 5 PRs mesclados (envio rápido de chunks e outros) [V: `git log --merges`].
- Contribuição externa é real e aceita: 23 PRs distintos mesclados; autores externos incluem EverNife, RealSilverMoon, KAMKEEL, brokestar233, brunoxkk0, RubilaxXxx, MellowArpeggiation, RetroForge (fork) [V].
- Atividade: 80 commits em 2026 até novembro; última atividade visível 2026-11-01. A página do GitHub mostra 246 estrelas, 83 forks, 39 issues e 4 PRs abertos, licença GPL-3.0 (WebFetch de https://github.com/CrucibleMC/Crucible nesta sessão; o número muda com o tempo) [V]. `gh api` não funcionou aqui (repositório não habilitado na sessão).
- Releases: **só pré-lançamentos automáticos**. O workflow `CLONE/.github/workflows/staging-build.yml` roda a cada push em `staging` (JDK 8 Temurin, `./gradlew cleanCache clean setupCrucible` e `buildPackages`), conta commits e publica "Staging Build `<hash>` | dev-`<n>`" com tag `staging-<hash>`. A página de releases mostra `staging-cd1de3d` = dev-283 (01 nov), `staging-667a28e` = dev-280, etc., todos pré-lançamento publicados pelo bot [V: https://github.com/CrucibleMC/Crucible/releases]. Commits `54b1424` (2025-12-27, mensagem trocada de "staging" para "dev builds" porque "too many people asks what is staging") e `684d4d6` (versão no nome do jar) confirmam [V]. Não há release "estável" separada na página. `.github/workflows/verify-build.yml` roda o mesmo build em todo pull request [V].
- Comunidade: Discord `discord.gg/jWSTJ4d` e documentação em https://cruciblemc.github.io/docs/ (hub "Necronomicon", seção `/docs/crucible/intro`; o conteúdo interno não foi lido) [V para os links, NV para o conteúdo]. Mod companheiro: NecroTempus. Modelos de issue em `.github/ISSUE_TEMPLATE` (bug, feature, perguntas) [V].
- Aberto a contribuições? Na prática sim, mas sem processo formal: **não existe `CONTRIBUTING.md` na raiz** (`ls` confirma) nem modelo de PR. Existem `bukkit/CONTRIBUTING.md` (497 linhas, herdado do Bukkit) e `forge/CONTRIBUTING.md` (10 linhas, herdado do Forge) [V]. As regras do Forge valem como guia de estilo: patches mínimos em classes do Minecraft, preferir classe nova, chaves em linha nova, espaços em vez de tabs, exemplo de mod para novos hooks. Os PRs de terceiros recentes (#195 a #219) foram mesclados em dias ou semanas [V pelas datas dos merges].

---

## 5. Lições para o Hoshiestar

### 5.1 Vitórias que não podemos quebrar ao mexer no loop de tick
Cada item abaixo tem uma razão concreta e um teste mínimo sugerido.

1. **Remapeamento de plugins** (itens 1 da seção 2). Ocorre no carregamento, não no tick, mas qualquer thread nova que carregue classe de plugin precisa usar o mesmo `PluginClassLoader` e o cache `jarMappings` (ConcurrentMap, já seguro). Teste: servidor sobe com os 65 plugins sem `NoSuchMethodError`.
2. **Injeção de Material e EntityType em tempo de execução.** Ocorre no registro (início). Não mover para depois do primeiro tick de região. Teste: `cauldron_settings_dumpMaterials` antes e depois, comparar a lista.
3. **Ponte de eventos Forge para Bukkit com FakePlayer.** Eventos Bukkit são chamados na thread que executa a ação. Plugins Bukkit **não** são thread-safe e o servidor não tem `AsyncCatcher`. Se uma região ticar em outra thread e uma máquina disparar `BlockBreakEvent`, o plugin de proteção rodará fora da thread principal. Regra para os degraus B e C: eventos Bukkit disparados dentro de uma região precisam ser enfileirados para a fase global em série, ou a dimensão fica no degrau A. Teste: máquina com FakePlayer em área protegida, antes e depois.
4. **Captura de blocos** (`World.captureBlockStates`, `WorldChangeHolder`). É estado **por mundo e mutável durante o crescimento de árvore e estruturas**. Mantê-lo por região, nunca estático compartilhado. Teste: crescer sapling em duas dimensões ao mesmo tempo.
5. **Dimensão como mundo Bukkit e comandos** (`DimensionManager.initDimension`). Não trocar a ordem de registro das dimensões; `MapGenStructure` já teve bug de estado global (`c634b0c`).
6. **`tileentities.yml` e `entities.yml`** (`TileEntityCache`, `SushchestvoCache`). Estes arquivos já dizem "esta classe tica a cada N ticks" ou "não tica". O orçamento por região do Degrau A deve **somar-se** a eles, nunca ignorá-los: um TE com intervalo configurado continua com o intervalo dele.
7. **Patches do Spigot**: `ActivationRange` e `TickLimiter` já cortam trabalho. Qualquer medição do Hoshiestar deve ser comparada com eles ligados, senão atribuímos ao nosso trabalho um ganho que já existia.
8. **Envio de chunks e AutoSave** (`a08bbe3`, `a3fd0d0`, `d717530`). São os caminhos que o Crucible acabou de otimizar em 2026; o AutoSave sem bloqueio já mexe em `MinecraftServer.java.patch`. Região paralela que salva chunk precisa respeitar o mesmo mecanismo. Atenção ao orçamento de chunks por tick (20 por padrão) quando houver mais de uma thread de rede.
9. **Handshake FML concorrente e lista de jogadores iterável fora da thread principal** (`6cdfc96`, `ac070ce`): o upstream já assumiu que algumas leituras acontecem fora da thread do tick. Preservar.
10. **Config central `Crucible.yml`**: `crucible_tickHandler_serverTickRate = 20` e `serverTickTime = 1000000000` (`CrucibleConfigs.java:203,206`, `getTickTime()` em `:278`). Isso é o relógio do tick. Qualquer relógio por região do Degrau C deve derivar daqui, não de constantes novas.

### 5.2 Hábitos do upstream que devemos seguir para continuar mesclando
- **Estilo de patch**: o código do Minecraft e do Forge é alterado por arquivos `.patch` em `patches/<caminho do pacote>/<Classe>.java.patch`, gerados por `./gradlew genPatches` e aplicados por `./gradlew setupCrucible`. Código novo próprio vai em classes novas (`src/main/java/io/github/crucible/...`), que é exatamente a regra do `forge/CONTRIBUTING.md` ("patches mínimos, classe nova se for muito"). Fazer o mesmo: o Hoshiestar deve colocar o motor de regiões em `src/main/java/<pacote nosso>` e deixar nos `.patch` só ganchos de 1 a 5 linhas. Isso minimiza conflito em `World.java.patch`, `MinecraftServer.java.patch` e `Entity.java.patch`, que são os mais disputados.
- **Cuidado com cabeçalhos de hunk**: commit `667a28e` (2026-10-18) corrigiu patches editados à mão com cabeçalhos velhos, e `6bfacdf` exige fim de linha LF. Regenerar sempre com `genPatches`, nunca editar `.patch` na mão.
- **Marcação no código**: o Cauldron/Thermos marca alterações com comentários `// Cauldron ...`; seguir o mesmo padrão com `// Hoshiestar ...` facilita ver diferença em revisão e em rebase.
- **Nomes e config**: variáveis de config em `CrucibleConfigs` seguem `secao_subsecao_nome` (`ConfigMode.PATH_BY_UNDERSCORE`, `CrucibleConfigs.java:259`) com `@Comment`. Nossas opções entram como `hoshiestar_...` no mesmo arquivo `Crucible.yml` (ou em arquivo próprio no mesmo formato), com padrão **desligado**, e nunca mudar o padrão de uma opção existente. Comandos seguem `cauldron`, `cauldron_te`, `cauldron_e`; criar `hoshiestar` ao lado.
- **Branch e build**: o upstream integra em `staging` (a `master` está na documentação do README, mas o fluxo real é `staging`) e exige que `./gradlew setupCrucible buildPackages` passe com JDK 8. Nossa fork deve compilar com JDK 8 mesmo rodando em Java 21 (o runtime é Temurin 21 via lwjgl3ify/RFB). Rebase periódico sobre `staging`, um commit por tema com prefixo (`net:`, `bukkit:`, `build:`, `api:`, `teleport:`), como o upstream faz nos commits recentes.
- **Contribuir de volta**: correções que não dependem de região (por exemplo, bugs de estado global por dimensão como `c634b0c`) devem ir como PR pequeno ao upstream, separado do trabalho de regiões. Isso reduz a diferença que teremos de carregar. Não há processo formal; abrir issue antes e usar o Discord é a prática provável [NV].
- **Dependências já em jogo**: Mixins early/late (mods `Akashic-*`) já rodam sobre este Crucible em produção. O próprio bootstrap usa RFB com `CrucibleRfbPlugin` e `JoptSimpleCompatTransformer` (`src/main/java/io/github/crucible/bootstrap/rfb`). Nossos ganchos de região podem usar o mesmo mecanismo, mas preferir patches normais para o que o upstream também toca.

---

## 6. O que não pude verificar (nao verificado)
- Autores individuais, datas de início e razões exatas de fim de MCPC+, Cauldron e KCauldron. A web aberta só confirmou os repositórios Techcable/Cauldron-Old e Thermos; `MinecraftPortCentral/MCPC-Plus` respondeu HTTP 451 e `Cauldron-Legacy` 404.
- Quem enviou o DMCA de 2014 e se ele foi a causa do fim do Cauldron (fontes contraditórias).
- História do Crucible antes de 2024-03-19 e a data em que Timings v2, SpecialSource e `tileentities.yml`/`entities.yml` entraram (clone raso).
- Conteúdo da documentação em `cruciblemc.github.io/docs/crucible/intro` e dos changelogs de release (as páginas de release não carregaram o texto).
- Mapeamento exato de permissões entre nós Bukkit e níveis de op do Forge (linha 9 da tabela).
- Se o upstream aceitaria um PR grande de arquitetura (nenhuma evidência; só PRs pequenos e médios vistos).
- Contagens por tema da seção 3.2 são aproximações por palavra-chave, não classificação manual completa.
