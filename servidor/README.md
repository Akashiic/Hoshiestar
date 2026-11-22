# servidor/ — o fork do Crucible que vira o `server.jar` do Hoshiestar

O Hoshiestar é um `server.jar` próprio: fork do Crucible (`staging@67b53034`, o mesmo da produção).
O admin troca o `server.jar` e pronto; **nenhum jar nosso vai em `mods/` nem em `plugins/`**.

Esta pasta guarda o fork de um jeito pequeno e fácil de revisar (decisão 24 do `PROGRESSO.md`):
o commit do upstream + as nossas mudanças, nunca uma cópia da árvore do Crucible.

| Arquivo | O que é |
|---|---|
| `UPSTREAM` | endereço, branch e commit do Crucible de onde o fork parte |
| `patches/` | série `git format-patch` só com mudanças em arquivos que **já existem** no upstream (inclusive os `.patch` do Minecraft/Forge, gerados pelo `genPatches`) |
| `src/` | arquivos **novos** nossos, nos mesmos caminhos em que entram na árvore do fork (`src/main/java/hoshiestar/...`, `src/test/...`) |
| `build.sh` | constrói o jar a partir de um clone limpo do upstream + esta pasta (ou num workspace já pronto) |
| `exportar.sh` | copia os commits de um workspace para `patches/` e `src/` (determinístico) |
| `gerar-patches.sh` | roda o `genPatches` do upstream e desfaz o ruído de cabeçalho |
| `test.sh` | testes isolados (fora do jogo) |
| `ferramentas/github-raw.init.gradle` | contorno opcional para proxy que recusa `github.com/.../raw/...` |

Termos: **workspace** = a árvore do Crucible com o `servidor/` aplicado, onde se compila e edita.
**Patch** = arquivo de diferenças. **Transformador** = código que altera o bytecode (as instruções
compiladas) de uma classe quando ela é carregada. **Trava de versão** = SHA-256 (impressão digital)
dos bytes originais da classe de um mod; a correção só entra se bater.

---

## 1. Construir

Precisa: `git`, JDK 8 e JDK 17 instalados (o `buildSrc` do Crucible procura um JDK 17 sozinho),
rede para os repositórios maven do Crucible. Receita completa e motivos em
`docs/pesquisa/crucible-build.md`.

```bash
# build completo, do zero (clona o upstream, aplica patches/ e src/, setupCrucible, buildPackages, test.sh)
servidor/build.sh --upstream https://github.com/CrucibleMC/Crucible --work /tmp/hoshie --github-raw
# saida: /tmp/hoshie/Crucible/build/distributions/Hoshiestar-1.7.10-1.0.0-dev-server.jar

# build rapido num workspace ja preparado (o /tmp/hoshie/Crucible acima, por exemplo)
servidor/build.sh --rapido /tmp/hoshie/Crucible --sem-testes
servidor/build.sh --rapido /tmp/hoshie/Crucible --limpo     # recompila tudo: saida igual a do completo

# quando houver ganchos em src/mods/java (compilam contra os jars dos mods, que nao vao para o Git):
servidor/build.sh ... --mods <pasta mods/ de um servidor de teste>
```

- `--upstream` aceita URL ou caminho local de um clone. `--github-raw` só é preciso atrás de proxy
  que bloqueia `github.com/.../raw/...` (o caso deste ambiente).
- Nome do jar: `Hoshiestar-1.7.10-<versao>-server.jar`. A versão mora **só** em
  `src/main/java/hoshiestar/Hoshiestar.java` (`VERSAO`); o `build.gradle.kts` lê de lá.
- Para mods e plugins o jar continua sendo "Crucible 1.7.10-staging-67b53034" (manifesto,
  `Implementation-Version`, `Thermos-Git-*`). O manifesto ganha só `Hoshiestar-Version`.
- O jar usa exatamente as mesmas bibliotecas do Crucible de produção (mesmo `Class-Path` e
  `Crucible-Libs`), então a pasta `libraries/` de um servidor existente serve.
- **Ganchos de mods (`src/mods/java`)**: escolhemos *parar com mensagem clara* quando há fontes ali e
  não foi dada a pasta de mods (`--mods`, ou `-Phoshiestar.mods=` no Gradle). Pular em silêncio
  geraria um jar com correções que nunca se aplicam. Os jars entram como `compileOnly` (só
  compilação, nunca dentro do jar). Hoje `src/mods/java` está vazio: nada exige mods para compilar.

Tempos medidos nesta máquina (4 núcleos): ver seção 8.

## 2. Testes isolados

```bash
HOSHIE_LIBS=<pasta libraries/ de um servidor> servidor/test.sh <workspace>
```

Compila a classe de exemplo `src/test/amostras/amostra/Alvo.java` para **Java 6, 7 e 8**, aplica cada
formato de correção, passa o resultado pelo `CheckClassAdapter` do ASM e depois **carrega e executa**
a classe num class loader de teste (o verificador da própria JVM confere os quadros de pilha). Roda
tudo no JDK 8 e no JDK 21. Testes: `TesteAsm` (formatos 1 a 3 e falha fechada), `TesteTrava`
(trava, registro de correções, transformador), `TesteCampoPrivado` (formato 4), `TesteLimitador`,
`TesteConfig` (hoshiestar.yml ausente, válido, chave faltando, valor inválido, lixo). `HOSHIE_LIBS`
serve para usar a snakeyaml 1.9, a mesma do servidor (sem ela o teste avisa e usa a 1.21 do cache do
Gradle).

## 3. Fluxo de trabalho e exportação

1. Workspace: o `--work` de um build completo (ele já é um repositório git, branch `hoshiestar`).
2. Edite, compile (`build.sh --rapido`), teste. **Um commit por mudança** no workspace.
3. `servidor/exportar.sh <workspace>` reescreve `servidor/patches/` e `servidor/src/` a partir dos
   commits (exige árvore limpa; arquivo novo tem de ficar em `src/`). Rodar duas vezes não muda nada.
4. Confira com `build.sh` a partir do clone limpo que o jar sai igual (seção 8).

## 4. Como escrever um patch em classe do servidor

**(a1) Arquivo do Crucible/Thermos/CraftBukkit que mora em `src/main/java`** (ex.:
`io/github/crucible/CrucibleModContainer.java`, `thermos/wrapper/ChunkBlockHashMap.java`): edite
direto no workspace. Marque cada linha com `// Hoshiestar` ou um bloco
`// Hoshiestar start` / `// Hoshiestar end`. Commit. O `exportar.sh` vira isso um patch git.

**(a2) Classe do Minecraft ou do Forge/FML** (ex.: `net/minecraft/server/MinecraftServer.java`): o
upstream guarda essas mudanças como `.patch` em `patches/` e o código editável fica em
`eclipse/cauldron/src/main/java/` (gerado pelo `setupCrucible`). Nunca edite `.patch` à mão:

```bash
# 1. edite eclipse/cauldron/src/main/java/net/minecraft/server/MinecraftServer.java, com // Hoshiestar
# 2. gere o patch com a tarefa do upstream (genPatches) e desfaca o ruido de cabecalho:
servidor/gerar-patches.sh <workspace> --github-raw
# 3. commit do patches/net/minecraft/server/MinecraftServer.java.patch
```

Exemplo real (o relógio de ticks): primeira linha de `MinecraftServer.tick()` ganhou
`++hoshiestar.api.Relogio.ticks; // Hoshiestar ...`. O `genPatches` recalcula também os cabeçalhos
de bloco (`@@ ... @@`) de outros trechos do mesmo arquivo (o upstream tem contagens editadas à mão);
isso é esperado. Arquivo que muda **só** cabeçalho (hoje o `RegionFileCache.java.patch`) é desfeito
pelo `gerar-patches.sh`.

Regras para código nosso chamado de classe do servidor: pode chamar `hoshiestar.*` direto (as classes
do servidor, como as nossas, ficam no LaunchClassLoader). Exceção: o pacote
`io.github.crucible.bootstrap` é carregado pelo class loader da aplicação; dali só por reflexão
(veja `CrucibleCoremodHook`).

## 5. Como escrever uma correção embutida em mod (passo a passo, com o autoteste)

Correção em classe de **mod de terceiros**: o jar traz a correção e o próprio servidor a aplica, por
um transformador ASM, só se (a) o interruptor do recurso está ligado, (b) a classe aparece (mod
presente) e (c) a trava confere. Arquivos de exemplo: `src/main/java/hoshiestar/embutidos/autoteste/`.

1. **Hash da classe original.** Com o servidor ligado: `hoshie hash openblocks.common.ServerTickHandler`
   (ou `unzip -p mod.jar caminho/Classe.class | sha256sum`). Anote o SHA-256.
2. **Leia o bytecode** com `javap -c -p` (método, descritor, chamadas). Escolha o formato:
   - **1. `Asm.ganchoNoInicio`**: chama um gancho na entrada do método, com retorno antecipado
     opcional (métodos de instância e estáticos; retorno void, boolean, double, objeto...). Ex.:
     Mekanism `getEnergy`, ChromatiCraft `shiftArea`.
   - **2. `Asm.trocarChamada`**: troca **uma** chamada (dono + nome + descritor + ordinal, e o total
     esperado no método) por um gancho estático que recebe receptor e argumentos. Ex.: BetterQuesting.
   - **3. `Asm.envolverChamada`**: um gancho decide se a chamada original acontece (ela fica dentro
     da classe do mod, num método-ponte sintético); se não, outro gancho dá o valor. Ex.: Ars Magica 2.
   - **4. `CampoPrivado`**: ler/escrever campo privado do mod pelo gancho, via `MethodHandle`
     guardado em `static final` (nada muda na classe do mod). Ex.: `OreIngredient.ores` do BQ.
3. **Ganchos**: métodos `public static` numa classe `public` nossa. Receptor (método/chamada de
   instância) primeiro, com o tipo exato ou `Object`; os demais parâmetros e o retorno com os tipos
   exatos. Gancho que só usa tipos do JDK/Forge/Minecraft (receptor como `Object`) fica em
   `src/main/java`. Gancho que precisa das classes do mod fica em **`src/mods/java`** (compila com
   `--mods`). O utilitário confere o gancho lendo o `.class` dele antes de aplicar.
4. **Registro** (só JDK + `hoshiestar.*`; nada do Minecraft: roda na fase de coremod):

   ```java
   public static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
       boolean ligado = cfg.autoteste_ligado;                       // passo 5
       Recursos.registrar("autoteste", "autoteste das correcoes embutidas", ligado)
               .estado(() -> "chamadas: " + GanchoAutoteste.chamadas());   // linha do 'hoshie status'
       correcoes.adicionar(Correcao.nova("autoteste", "contador no inicio de onServerTick",
               Trava.de("openblocks.common.ServerTickHandler", "c8114b87...c1b1f3"), ligado,
               (classe, ganchos) -> Asm.ganchoNoInicio(classe, "onServerTick",
                       "(Lcpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent;)V",
                       Gancho.de("hoshiestar/embutidos/autoteste/GanchoAutoteste", "inicioServerTick"),
                       null, true, ganchos)));
   }
   ```
   e uma linha em `hoshiestar/Catalogo.java`: `Autoteste.registrar(cfg, correcoes);`
5. **Interruptor**: um campo em `hoshiestar/config/HoshiestarConfig.java`, padrão desligado, com
   comentário em português: `@Comments({...}) public boolean autoteste_ligado = false;` (vira
   `autoteste: ligado: false` no `hoshiestar.yml`).
6. **Teste isolado** em `src/test/java` (copie o padrão de `TesteAsm`: transformar, verificar,
   carregar, executar) e **teste no servidor**: ligue no `hoshiestar.yml`, reinicie, procure no log
   `[Hoshiestar] [embutidos] <recurso>: ... aplicada` e use `hoshie status`.

O que o servidor garante (falha **fechada**): trava errada, padrão não encontrado exatamente como
esperado, gancho ausente ou qualquer exceção ⇒ a classe fica com os bytes originais, o estado da
correção diz o porquê (no log do boot e no `hoshie status`), e o hash visto aparece para ser mandado
ao projeto. Interruptor desligado ⇒ a correção nem entra no mapa do transformador; se nenhuma está
ligada, o transformador nem é registrado.

## 6. Config, comando e serviços

- **`hoshiestar.yml`** (pasta do servidor, ao lado do `Crucible.yml`), classe
  `hoshiestar.config.HoshiestarConfig`, mecanismo `YamlConfig` do Crucible. Lido uma vez, na fase de
  coremod (o próprio Crucible lê o `Crucible.yml` com o mesmo mecanismo nesse ponto, então ele funciona
  tão cedo). Criado com comentários se não existe; valores do admin preservados; valor inválido usa o
  padrão, avisa e **não** regrava o arquivo; arquivo ilegível usa os padrões e não é tocado; nunca
  derruba o servidor. Hoje só tem `autoteste.ligado` (padrão `false`).
- **Comando `hoshie`** (console, RCON, operador nível 4): `hoshie status`, `hoshie hash <classe>`.
  Registrado como o `crucible` (comando Bukkit, no `FMLServerStartingEvent` do
  `CrucibleModContainer`). Subcomandos novos: `ComandoHoshie.registrar(new Subcomando(){...})`.
- **`hoshiestar.api`**:
  - `Relogio.ticks` (campo `volatile long`): +1 na primeira linha de `MinecraftServer.tick()`.
  - `ThreadServidor.souAThreadDoServidor()`: uma leitura volatile e uma comparação. Vale desde a
    primeira linha de `MinecraftServer.run()` (antes de mods, mundos e plugins carregarem); antes
    disso devolve `false`.
  - `Log` (prefixo `[Hoshiestar] `, `Log.de("id")` para `[Hoshiestar] [id] `) e `Limitador`
    (N mensagens por janela, por chave).
  - `Trava` e `HashClasse` (SHA-256 dos bytes originais lidos como recurso, sem carregar a classe).
  - `Recursos`: lista de recursos (id, ligado, linha de estado) para o status e o log do boot.
- **Linhas de log**: no boot `[Hoshiestar] 1.0.0-dev sobre Crucible 1.7.10-staging-67b53034` e
  `recursos: ...`; quando o servidor termina de ligar (FMLServerStartedEvent), uma linha por recurso
  e por correção (desligada / aplicada / NÃO aplicada e por quê: mod ausente, classe ainda não
  carregada, trava com o hash visto, padrão não encontrado) e a posição do transformador na cadeia.

## 7. Class loaders, fases e o lugar do transformador

Descobertas (código lido e provado no modpack real, Java 21):

- O Crucible **sempre** sobe pelo RetroFuturaBootstrap (o `ServerLaunchWrapper` do Crucible chama
  `com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread`), e o `LaunchClassLoader` é o do RFB.
- Exclusões do `LaunchClassLoader` (vão para o class loader da aplicação): `java.`, `javax.imageio/
  sound/swing.`, `sun.`, `org.lwjgl.`, `org.apache.logging.`, `org.objectweb.asm.`,
  `net.minecraft.launchwrapper.`, `org.slf4j.`, `com.gtnewhorizons.retrofuturabootstrap.` (RFB);
  `org.apache.`, `com.google.common.`, `LZMA.` (FMLTweaker); `cpw.mods.fml.relauncher.`,
  `net.minecraftforge.classloading.` (FML); `io.github.crucible.bootstrap.` (Crucible). **`hoshiestar.`
  não é excluído**: nossas classes são carregadas pelo LaunchClassLoader, o mesmo dos mods. Prova no
  servidor: o gancho do autoteste e a classe do OpenBlocks têm o mesmo class loader e o nome da classe
  do mod resolve para a mesma classe (`hoshie status`).
- O `CrucibleCoremodHook` é do pacote excluído: chama `hoshiestar.Inicio` por reflexão através do
  LaunchClassLoader (como o Crucible faz com o `CrucibleConfigs`). Referência direta carregaria uma
  segunda cópia das nossas classes no class loader errado.
- Nossas classes, como as do Crucible, são reofuscadas pelo build (nomes do Minecraft "notch") e o
  `DeobfuscationTransformer` do FML as traduz na carga. Ele só entra na cadeia depois da fase de
  coremod. Por isso: **o que roda cedo (`Inicio`, config, `Catalogo`, registros, transformador,
  `hoshiestar.api`) não pode tocar em classe do Minecraft**; o que toca (`Servidor`, comando,
  ganchos) só é carregado com o servidor de pé. Nenhuma exclusão de transformador para `hoshiestar.`.
- **Lugar do transformador**: `Inicio.coremod` registra `hoshiestar.embutidos.TransformadorHoshiestar`
  dentro de `CrucibleCoremodHook.coremodHandleLaunch`, que o `CoreModManager.handleLaunch` chama logo
  depois de achar os plugins fundamentais do FML e **antes** de procurar coremods e tweakers dos mods
  (onde entram UniMixins/Mixin). O LaunchClassLoader do RFB roda os `IClassTransformer` na ordem de
  registro e só depois os transformadores de plugins RFB. Resultado medido no boot:
  `o do Hoshiestar esta na posicao 2 de 133; Mixin na posicao 6; antes dele:
  cpw.mods.fml.common.asm.transformers.PatchingTransformer` (que só aplica binpatches em classes do
  Minecraft). E a correção de trava errada em `bq_standard.handlers.EventHandler` (classe que recebe 8
  métodos de mixin do Akashic-BQRelief) viu bytes **iguais aos do arquivo do jar**. O mesmo caminho
  (`CoreModManager` + RFB) é usado no Java 8, mas o boot em Java 8 não foi testado.
- **Quadros de pilha**: a classe é lida com `EXPAND_FRAMES` e gravada só com `COMPUTE_MAXS`; os
  utilitários acrescentam quadros explícitos apenas nos desvios que criam, sempre num ponto de estado
  conhecido (entrada do método). Nada de `COMPUTE_FRAMES`, que exigiria carregar classes para achar
  superclasses comuns. Testado com Java 6, 7 e 8; um teste de controle sem os quadros falha no
  verificador da JVM (Java 7/8), o que mostra que o teste pega esse erro.

## 8. O que foi verificado e o que não foi

Verificado (comandos e saídas no relatório da tarefa):
- Build do workspace sem mudanças: 5079 entradas, CRC igual ao jar de produção em todas menos
  `MANIFEST.MF` (nome da branch) e `binpatches.pack.lzma` (muda a cada build, ver crucible-build.md).
- Testes isolados: todos passam no JDK 8 e no JDK 21.
- Modpack real (292 mods para o Forge; 285 na lista do handshake), sem nenhum jar nosso em `mods/`
  e `plugins/`: (1) tudo no padrão: "Done", linha de boot, `hoshiestar.yml` criado com comentários,
  transformador não registrado, `hoshie status`/`hash` respondem, `stop` limpo, sem crash-report;
  (2) autoteste ligado: correção aplicada no OpenBlocks (contador sobe 2 por tick), trava errada
  recusada com o hash no log, class loader do gancho = do mod, `stop` limpo. Nenhum erro novo no log
  comparado a um boot do jar de produção na mesma cópia.
- `exportar.sh` determinístico; `build.sh` a partir de clone limpo reconstrói o mesmo jar (entradas e
  CRCs, fora o que muda a cada build).

Não verificado:
- Boot do jar em Java 8 (a produção usa Java 21).
- Formato 3 com método protegido herdado de classe de outro pacote (o verificador pode recusar).
- Um gancho real em `src/mods/java` compilado com `--mods` (o mecanismo existe; nenhum gancho real
  ainda precisa dele).
- Custo do transformador medido em servidor (por desenho: uma busca em HashMap por classe carregada,
  e só quando há correção ligada).
- Comportamento com plugins que filtram comandos além dos do pacote de teste (o `hoshie` respondeu
  no console com os 65 plugins do pacote; jogador op 4 não foi testado, só o console).
