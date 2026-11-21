# Hoshiestar: avaliação de bases para o fork (1.7.10 híbrido Forge+Bukkit)

Data da pesquisa: 2026-11-21. Método: clone raso do repositório Crucible (branch `staging`, é a HEAD padrão) em
uma pasta temporária + leitura de páginas GitHub via WebFetch. A API do GitHub (gh) estava bloqueada na sessão,
então contagens de issues/PRs vêm das páginas web. Itens sem fonte direta estão marcados "não verificado".
Nada foi compilado (a build do Crucible não foi executada, por instrução).

## 1. Crucible (https://github.com/CrucibleMC/Crucible)

### 1.1 Licença e obrigações
- Arquivo `LICENSE` da raiz: "GNU GENERAL PUBLIC LICENSE, Version 3, 29 June 2007" (GPLv3, texto padrão; verificado no clone).
  O subdiretório `bukkit/` traz `LICENCE.txt` também GPLv3 (verificado no clone).
  Fonte: https://github.com/CrucibleMC/Crucible/blob/staging/LICENSE
- Obrigações para derivado (leitura padrão da GPLv3, não é parecer jurídico): o Hoshiestar deve ser GPLv3, o código-fonte
  correspondente deve ser oferecido a quem receber o binário, avisos de copyright/licença preservados, modificações marcadas.
  Rodar o servidor internamente sem distribuir não dispara a obrigação de publicar; distribuir o jar a terceiros dispara.
- Distribuição e código da Mojang: o repositório NÃO contém fontes da Mojang; contém ~406 arquivos `.patch`
  (`find patches -name '*.patch'`) cujos cabeçalhos apontam para `../src-base/minecraft/...` e `../src-work/minecraft/...`
  (verificado), ou seja, modelo Cauldron: o ForgeGradle baixa/decompila o Minecraft na hora do `setupCrucible` e aplica os patches.
  Os releases publicados no GitHub são jars compilados (4 assets por release, ver 1.2), que por construção contêm classes
  Minecraft modificadas (inferência; não verificado o conteúdo do jar). Consequência para nós: a forma mais segura
  de distribuir o Hoshiestar é manter o repositório só com patches + código próprio e deixar cada usuário construir,
  evitando redistribuir o jar do Minecraft. Posição legal/EULA da Mojang: não verificado.

### 1.2 Manutenção
- Último commit em `staging`: 2026-11-01, "Merge pull request #219 from CrucibleMC/fix/library-root-jar-dir" (cd1de3d). Verificado no clone.
- 65 commits em `staging` desde 2026-06-01; autor principal recente: Pétrus Pradella (37+12 commits em 2026), depois Gravita (17), Juan Patricio, RealSilverMoon (5 cada).
  (contagem via `git log` no clone raso; é indicativo.)
- Branches remotas (`git ls-remote`): `staging` (padrão), `master`, `old-crucible`, `old-dev`, `reflux`, e cinco `fix/*`
  (chunks-to-unload-set, fml-handshake-concurrent-logins, library-root-jar-dir, nashorn-per-jvm-and-libraries-zip, tab-ping-updates).
- `master` está parada: último commit "Release v5.4" em 2022-09-23. O `build.gradle.kts` ainda fixa `crucibleVersion = "5.4"`
  e só usa esse número quando a branch é `master`; qualquer outra branch gera `1.7.10-<branch>-<hash>`.
- Releases: página https://github.com/CrucibleMC/Crucible/releases lista só pré-releases automáticos do CI:
  `staging-cd1de3d` (01 Nov, dev-283), `staging-667a28e` (18 Oct, dev-280), `staging-25a589c` (18 Oct, dev-279); sem changelog.
  Release "estável" mais recente: não verificado (a badge do README aponta releases, mas a página mostrou só pré-releases).
- Issues/PRs: página mostra 39 issues e 4 PRs (a página não diz "abertos" explicitamente; WebFetch).
- Workflow `staging-build.yml`: a cada push em `staging` gera pré-release `Crucible-1.7.10-staging-<sha7>-dev-<contagem>-server.jar`.
- Commit `67b53034`: existe em `staging`. `67b530340b080f94079793a15e5588c2dd6fc94b`, 2026-09-29, Pétrus Pradella,
  "teleport: fire a single PlayerTeleportEvent on a world change". NÃO está em `master`. Está ~21 commits atrás da ponta atual
  da staging (posição 22 no `git log --oneline`). Então a produção está numa staging de fim de setembro/2026, não numa release estável.
  Commits posteriores relevantes: spectator GameMode, ping de tab list, "fast chunk copy" desligada quando mixins tocam chunks,
  PreCreatureSpawnEvent, handshake FML concorrente, "let the player list be iterated off the main thread" (2026-10-13).

### 1.3 Sistema de build
- Wrapper: Gradle 8.14.3 (`gradle/wrapper/gradle-wrapper.properties`). Script principal `build.gradle.kts` (+ `build.gradle.bak` legado, `settings.gradle`).
- ForgeGradle: fork `com.anatawa12.forge:ForgeGradle:1.2-1.1.0` no buildscript; plugin próprio `id("crucible")` em `buildSrc/` (plugin Gradle incluído no repo;
  `DevExtension`: `version=1.7.10`, `mcpVersion=9.08`, `installerVersion=1.4`, Forge 10.13.4.1614 / build 1614).
- JDK para BUILD: README diz "Java 8 JDK + JAVA_HOME"; CI (`staging-build.yml`, `verify-build.yml`) usa Temurin 8. Rodar a build em 17/21: não verificado.
- Comandos (README): `./gradlew setupCrucible` (cria workspace e aplica patches), `./gradlew genPatches` (regenera patches das edições),
  `./gradlew buildPackages` (saída em `build/distributions`), `./gradlew clean setupCrucible` para refazer. CI usa `cleanCache clean setupCrucible --refresh-dependencies`.
- Organização: `patches/{cpw,net,org}` contra `src-base` (fontes Minecraft+Forge decompilados), mais código novo em `src/main/java`
  (`org.spigotmc`, `org.bukkit.craftbukkit.v1_7_R4`, `net.minecraftforge.cauldron`, `io.github.crucible.bootstrap`), diretório `bukkit/` (API Bukkit, `pom.xml` versão
  `1.7.9-R0.3-SNAPSHOT`) e `forge/` (arquivos do repositório Forge; conteúdo não inspecionado).
- Repositórios Maven exigidos (verificado no `build.gradle.kts`): `https://github.com/juanmuscaria/maven/raw/master` (+ `/ThermosLibs`),
  `maven.minecraftforge.net`, `libraries.minecraft.net`, `nexus.gtnewhorizons.com/repository/public/`, `oss.sonatype.org` snapshots.
- Armadilhas conhecidas (derivadas do repositório; não reproduzidas): (a) dependência de um "maven" hospedado como raw do GitHub de um indivíduo = ponto único de falha;
  (b) patches editados à mão quebram hunks ("build: fix stale hunk headers in two hand-edited patches", 2026-10-18) e há commit "check out patches with LF line endings",
  então em Windows/CRLF o `setupCrucible` falha; (c) a build baixa Minecraft de servidores da Mojang e Forge do maven do Forge (disponibilidade externa).

### 1.4 Suporte a runtime Java
- README: "Java 8–21 supported (using an integrated version of lwjgl3ify)". Verificado no README (https://github.com/CrucibleMC/Crucible/blob/staging/README.md).
- Código: `src/main/java/io/github/crucible/bootstrap/` (Lwjgl3ifyGlue, Lwjgl3ifyIntegration, DeferredLibraries, LibraryManager, pacote `rfb`);
  `Lwjgl3ifyGlue` lê a propriedade `crucible.weAreJava9` (pula o ramo "Java 8" quando `true`); `java9args.txt` traz
  `-Dcrucible.weAreJava9=true`, `--add-opens java.base/{jdk.internal.loader,java.net,java.nio}=ALL-UNNAMED`, `-Djava.security.manager=allow`, `--illegal-access=warn`.
  Dependência `RetroFuturaBootstrap:1.1.0` em `libraries` (igual ao que a produção usa). Nashorn 15.4 é "deferred lib" só para Java >= 11.
  Isso confirma o relato da produção (17/21 com a flag).
- Bibliotecas de runtime são baixadas na primeira execução (manifest `Crucible-Libs`; `libraries.zip` opcional para offline).

### 1.5 Mixin no servidor
- O Crucible NÃO embute Mixin: nenhuma dependência `mixin`/`spongepowered` em `build.gradle.kts` e nenhuma classe Mixin no `src/`
  (verificado por grep). Mixin chega por mods (UniMixins 0.3.1 na produção, via RetroFuturaBootstrap/FML tweaker).
- O Crucible *se adapta* a mixins de mods: os commits "net: skip the fast chunk copy when a mixin touches chunk data" e "log once which mixins turn the fast chunk copy off"
  adicionam em `S21PacketChunkData`/`S26PacketMapChunkBulk` detecção de mixin mesclado em tipos de chunk. Relação formal com UniMixins/GTNHMixins/SpongeMixins
  (versões testadas): não verificado.

### 1.6 Bukkit API, Timings, performance
- Manifest: `Specification-Version: 1.7.10-R0.1-SNAPSHOT`, `Thermos-Legacy: true`, `Implementation-Title: Crucible`; implementação CraftBukkit `v1_7R4`
  (`org.bukkit.craftbukkit.v1_7_R4`); API em `bukkit/` com `pom.xml` 1.7.9-R0.3-SNAPSHOT (herdado do Thermos).
- Backports: README cita "Backported Bukkit APIs" (algumas exigem o mod NecroTempus); commits recentes backportam `PreCreatureSpawnEvent` e `GameMode.SPECTATOR`.
- Timings: README diz "Implemented TimingsV2"; há referências a `co.aikar.timings`/TimingsV2 em `CraftScheduler`, `CraftTask`, `CustomTimingsHandler`
  (`org.spigotmc`), `ActivationRange`, `AntiXray` (verificado por grep; nível de completude/relatório web: não verificado).
- Recursos Spigot/Paper presentes: `org.spigotmc.{ActivationRange,TrackingRange,TickLimiter,WatchdogThread,AntiXray,SpigotWorldConfig}`;
  84 patches mencionam "spigot"; iluminação assíncrona estilo PaperSpigot em `World.java.patch` (`"PaperSpigot - Lighting Thread"`); "fast chunk copy" para pacotes de chunk.

### 1.7 Código de threading já existente
- Chunk IO assíncrono: é o `net.minecraftforge.common.chunkio.ChunkIOExecutor` do próprio Forge (o patch do Crucible só corrige um cast/divisão); sem reescrita estilo Paper `Asynchronous chunk IO`.
- Thread de iluminação única (`Executors.newSingleThreadExecutor`) em cada `World` (campo `lightingExecutor`): trabalho fora da main thread que mexe em chunks. Risco direto para região-por-thread.
- Checagens de thread: `MinecraftServer.primaryThread` (thread "Server thread") e `Bukkit.isPrimaryThread()` em `World.java.patch`.
  `AsyncCatcher` (Spigot): AUSENTE (grep em `patches/` e `src/` sem resultados). Nós teremos que criar nossas próprias asserções de thread.
- `ServerConfigurationManager.playerEntityList` virou `SnapshotIteratingArrayList` (comentário: chat assíncrono e plugins iteram fora da main thread).
- Handshake FML concorrente por conexão (PR #214). `ThreadPlayerLookupUUID` presente.

## 2. Thermos (https://github.com/CyberdyneCC/Thermos e forks)
- Licença: GPL-3.0 (página do repo mostra "GPL-3.0 license", arquivo LICENSE.txt). Mesmas obrigações do item 1.1.
- Estado: ARQUIVADO pelo dono em 2019-01-26 (somente leitura); último commit exibido 2017-01-25 ("Update README.md"); 89 issues; README diz que não é mais desenvolvido e issues serão fechadas sem resolução; sem releases na página.
  Fontes: https://github.com/CyberdyneCC/Thermos, https://github.com/CyberdyneCC/Thermos/commits/master
- Build: Java 8u101+ JDK; `git submodule update --init --recursive` e `./gradlew setupCauldron jar` (saída em `build/distributions`); baixa Minecraft e aplica patches (modelo Cauldron).
  Versões de Gradle/ForgeGradle: não verificado. Java 9+: não mencionado/não verificado. Mixin, Timings v2, backports: não verificado.
- Alvo: Forge 10.13.4.1614 + snapshot Spigot 1.7.10; Spigot patches e correções de dupes (README).
- Forks vivos: o Crucible é o fork ativo conhecido; "jefgen/Thermos" apareceu na busca (estado não verificado).
  O README do Crucible lista Thermos como projeto original.

## 3. Candidatos secundários
- KCauldron / Cauldron (origem): URL tentada `github.com/KCauldron/KCauldron` retornou 404; estado não verificado. Pelo README do Thermos, ele é derivado do KCauldron (cadeia Cauldron > KCauldron > Thermos > Crucible; busca web também confirma que Thermos é fork do KCauldron).
- Ultramine Core: não verificado. Dois URLs tentados (`ultramine/ultramine_core` = repositório vazio; `UltraMine-Minecraft/UltraMine_Core` = 404) e a busca web não retornou o projeto.
  Como arte prévia de chunk IO assíncrono: não verificado, não usar como base sem pesquisa adicional.
- Uranium (https://github.com/UraniumMC/Uranium): fork do KCauldron, "continuation"; página mostra LGPL-3.0, 587 commits na `dev`, 14 issues + 2 PRs, sem releases (downloads via Baidu), Java 8u101+; data do último commit não verificada, sem atividade em 2025 achada na busca.
- Contigo (OnlineMC/Contigo): arquivado em 2019-02-27 (snippet de busca), Java 8u101+. Descartado.
- Mohist: lista 1.7.10 como depreciado (snippet de busca); fora de cogitação.
- Outras fontes de contexto: https://github.com/Doenerstyle/1.7.10-modded-bukkit-servers (lista de servidores), busca web indicou Crucible como a opção mais ativa em 2025.

## 4. Tabela de decisão

| Critério | Crucible (staging) | Thermos | KCauldron/Cauldron | Ultramine | Uranium |
|---|---|---|---|---|---|
| Licença | GPLv3 (verif.) | GPL-3.0 (página) | não verif. | não verif. | LGPL-3.0 (página) |
| Manutenção | ativa, commit 2026-11-01 | arquivado 2019 | não verif. | não verif. | sem sinal recente |
| Roda em Java 17/21 | sim (lwjgl3ify+RFB, flag `weAreJava9`) | não verif. (README: 8u101+) | não verif. | não verif. | não verif. (8u101+) |
| Build | Gradle 8.14.3, FG fork, JDK 8, patches | `setupCauldron`, versões não verif. | não verif. | não verif. | não verif. |
| Compatível com stack da produção | é a produção | não | não | não | não |
| Mixin/UniMixins | convive, detecta mixins | não verif. | não verif. | não verif. | não verif. |
| Timings v2 / Paper-isms | sim (parcial) | não verif. | não verif. | n/a | não verif. |
| AsyncCatcher / checagem de thread | não (só isPrimaryThread) | não verif. | não verif. | não verif. | não verif. |
| Risco de regressão vs produção | mínimo (mesma base) | alto | alto | alto | alto |

## 5. Recomendação
**Base: Crucible, branch `staging`.** Justificativa: (1) é exatamente o que a produção roda, então o diff de comportamento
é controlável; (2) é o único candidato com manutenção verificada em 2026 e com suporte a Java 17/21 verificado no código; (3) GPLv3
é compatível com um fork aberto e o modelo de patches mantém código da Mojang fora do repositório; (4) já traz Spigot/Paper-isms
(activation range, timings, thread de iluminação) que são o ponto de partida natural para o modelo região-por-thread;
(5) os demais ou estão arquivados ou sem evidência verificável.
Procedimento sugerido: criar o fork a partir de `67b53034` (para paridade com a produção), validar, e só então fazer merge
da ponta da staging (cd1de3d, ~21 commits à frente, incluindo correções de handshake concorrente e iteração fora da main thread).

## 6. Principais riscos de forkar o Crucible
1. Forge e a maioria dos mods (GTNH incluídos) assumem thread única: estado estático, `World`, listas globais, eventos. Região-por-thread exige auditoria enorme; nada disso existe na base.
2. Sem `AsyncCatcher`: precisamos criar asserções de dono-de-região (e há `World.lightingExecutor` e `ChunkIOExecutor` já operando fora da main thread, a serem reescritos).
3. Build frágil: fork de ForgeGradle, plugin próprio em `buildSrc`, repositório Maven pessoal em raw GitHub, JDK 8 para construir, patches editados à mão sujeitos a hunks quebrados. Mitigar com espelho de dependências e CI próprio.
4. Poucos mantenedores (um autor concentra a maior parte dos commits recentes) e sem releases estáveis desde 2022 (`master` v5.4); divergência futura do upstream torna merges caros.
5. Mixins de mods mexem em classes de chunk/rede; o Crucible já precisou de desvios (fast chunk copy). Mudanças nossas em chunk/world interagem com UniMixins e GTNHMixins.
6. Obrigações GPLv3 e da Mojang: publicar fontes de qualquer binário distribuído; não redistribuir o jar do Minecraft (modelo só-patches). Parecer jurídico: não verificado.
7. Bukkit API 1.7.10 (CraftBukkit `v1_7R4`) com scheduler e eventos síncronos por contrato: plugins existentes quebram em modelo multi-thread; precisará de camada de compatibilidade ou marcação de plugins thread-safe.
8. Tamanho da superfície: ~406 patches e dezenas de pacotes Spigot/Cauldron para conferir ao introduzir threads.
