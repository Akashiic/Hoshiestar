# servidor/ — o fork do Crucible que vira o `server.jar` do Hoshiestar

O Hoshiestar é um `server.jar` próprio: fork do Crucible (`staging@67b53034`, o mesmo da produção).
O admin troca o `server.jar` e pronto; **nenhum jar nosso vai em `mods/` nem em `plugins/`**.

Esta pasta guarda o fork de um jeito pequeno e fácil de revisar (decisão 24 do `PROGRESSO.md`):
o commit do upstream + as nossas mudanças, nunca uma cópia da árvore do Crucible.

| Arquivo | O que é |
|---|---|
| `UPSTREAM` | endereço, branch e commit do Crucible de onde o fork parte |
| `patches/` | série `git format-patch` só com mudanças em arquivos que **já existem** no upstream (inclusive os `.patch` do Minecraft/Forge, gerados pelo `genPatches`) |
| `src/` | arquivos **novos** nossos, nos mesmos caminhos em que entram na árvore do fork (`src/main/java/hoshiestar/...`, `src/mods/java/...`, `src/test/...`, `src/docs/` com o doc de cada recurso) |
| `build.sh` | constrói o jar a partir de um clone limpo do upstream + esta pasta (ou num workspace já pronto) |
| `exportar.sh` | copia os commits de um workspace para `patches/` e `src/` (determinístico) |
| `gerar-patches.sh` | roda o `genPatches` do upstream e desfaz o ruído de cabeçalho (`--mods <pasta mods/>` repassa a pasta de mods ao Gradle; também lê `HOSHIE_MODS`) |
| `test.sh` | todos os testes isolados (fora do jogo): os gerais, os de cada recurso e os com mods |
| `ferramentas/github-raw.init.gradle` | contorno opcional para proxy que recusa `github.com/.../raw/...` |

Termos: **workspace** = a árvore do Crucible com o `servidor/` aplicado, onde se compila e edita.
**Patch** = arquivo de diferenças. **Transformador** = código que altera o bytecode (as instruções
compiladas) de uma classe quando ela é carregada. **Trava de versão** = SHA-256 (impressão digital)
dos bytes originais da classe de um mod; a correção só entra se bater.

## 0. Recursos do jar

Cada recurso tem um interruptor no `hoshiestar.yml` (seção com o nome do recurso) e um doc em
`src/docs/` (no repositório: `servidor/src/docs/`) com o que faz, as chaves, a segurança, os testes e o
que não foi verificado. Desligado = comportamento original. Padrão: tudo desligado, menos o vigia; o
medidor vem `habilitado: true`, mas só mede quando o admin manda (`hoshie medir`).

| Recurso (id) | O que faz | Chave principal (padrão) | Outras chaves | Doc |
|---|---|---|---|---|
| `chunkmap` | cache de última consulta do mapa de chunks (patch em `thermos.wrapper.ChunkBlockHashMap`) | `chunkmap.ligado` (false) | `bits`, `conferir` | `src/docs/chunkmap.md` |
| `parada` | parada por SIGTERM sem crash e sem salvamento duplo (D-24; patches em MinecraftServer, DedicatedServer, ServerShutdownThread) | `parada.ligado` (false) | `espera_laco_segundos`, `espera_salvar_segundos`, `folga_saida_segundos` | `src/docs/parada.md` |
| `vigia` | vigia de tick lento: relatório de quem, onde e qual mod quando um tick passa do limite | `vigia.ligado` (**true**) | `limite_ms`, `amostras`, `intervalo_amostras_ms`, `intervalo_minimo_segundos`, `max_relatorios`, `janela_minutos`, `quadros`, `nome_do_jogador`, `max_arquivos`, `carencia_segundos` | `src/docs/vigia.md` |
| `medidor` | medidor de carga por região (`hoshie medir <s>` / `hoshie parar`; patches em World, EntityPlayerMP, MinecraftServer) | `medidor.habilitado` (true; só mede sob comando) | `margem_custo` | `src/docs/medidor.md` e o roteiro do admin `src/docs/ROTEIRO-MEDIDOR.md` |
| `mekanism` | matriz de indução do Mekanism: energia sem refazer a busca de cada célula (correção embutida) | `mekanism.modo` (`desligado`; ou `verificar`, `ligado`) | `intervalo` | `src/docs/mekanism.md` |
| `betterquesting` | BetterQuesting: pula as checagens de inventário que comprovadamente não mudam nada (correção embutida) | `betterquesting.modo` (`desligado`; ou `verificar`, `ligado`) | `relatorio` | `src/docs/betterquesting.md` |
| `arsmagica` | Ars Magica 2: pula a varredura de mundos e a checagem do DiluteTime quando os mapas do AM2 estão vazios (correção embutida) | `arsmagica.varredura` e `arsmagica.diluicao` (false) | `estatistica` | `src/docs/arsmagica.md` |
| `chromaticraft` | ChromatiCraft: limites na habilidade de mover área (correção embutida) | `chromaticraft.ligado` (false) | `intervalo`, `distancia`, `volume`, `altura`, `chunks`, `mensagem`, `loglento`, `logsegundos` | `src/docs/chromaticraft.md` |
| `observador` | observador de acessos entre regiões (`hoshie observar <s>` / `parar` / `status`; só anota quando o admin manda; patches em World, WorldServer, Chunk, ChunkProviderServer, TileEntityHopper, EntityPlayerMP, MinecraftServer): quem toca onde entre regiões do mapa, para a decisão da Etapa 2; protótipo opcional da lista de tiles | `observador.ligado` (false) | `amostra_pilha`, `limite_custo_pct`, `lista` | `src/docs/observador.md` |
| `autoteste` | autoteste das correções embutidas (OpenBlocks) | `autoteste.ligado` (false) | | seção 5 abaixo |

O `hoshie status` lista os recursos (o `mekanism` só aparece com o Mekanism presente) e o estado de cada
correção. Os recursos de mods só se aplicam se a trava de versão bater; senão ficam desligados e avisam.

---

## 1. Construir

Precisa: `git`, JDK 8 e JDK 17 instalados (o `buildSrc` do Crucible procura um JDK 17 sozinho),
rede para os repositórios maven do Crucible. Receita completa e motivos em
`docs/pesquisa/crucible-build.md`.

**Todo build exige `--mods <pasta mods/ de um servidor de teste>`**: os ganchos de `src/mods/java`
(mekanism, betterquesting, arsmagica, chromaticraft) compilam contra os jars dos mods, que não vão para
o Git nem para dentro do jar.

```bash
# build completo, do zero (clona o upstream, aplica patches/ e src/, setupCrucible, buildPackages, test.sh)
servidor/build.sh --upstream https://github.com/CrucibleMC/Crucible --work /tmp/hoshie --github-raw \
    --mods <pasta mods/>
# saida: /tmp/hoshie/Crucible/build/distributions/Hoshiestar-1.7.10-1.0.0-dev-server.jar

# build rapido num workspace ja preparado (o /tmp/hoshie/Crucible acima, por exemplo)
servidor/build.sh --rapido /tmp/hoshie/Crucible --sem-testes --mods <pasta mods/>
servidor/build.sh --rapido /tmp/hoshie/Crucible --limpo --mods <pasta mods/>  # recompila tudo: saida igual a do completo
```

O `--mods` também vira `HOSHIE_MODS` do `test.sh` que o build roda no fim (se `HOSHIE_MODS` não estiver
definido); os testes com mods precisam ainda de `HOSHIE_LIBS` (seção 2).

- `--upstream` aceita URL ou caminho local de um clone. `--github-raw` só é preciso atrás de proxy
  que bloqueia `github.com/.../raw/...` (o caso deste ambiente).
- Nome do jar: `Hoshiestar-1.7.10-<versao>-server.jar`. A versão mora **só** em
  `src/main/java/hoshiestar/Hoshiestar.java` (`VERSAO`); o `build.gradle.kts` lê de lá.
- Para mods e plugins o jar continua sendo "Crucible 1.7.10-staging-67b53034" (manifesto,
  `Implementation-Version`, `Thermos-Git-*`). O manifesto ganha só `Hoshiestar-Version`.
- O jar usa exatamente as mesmas bibliotecas do Crucible de produção (mesmo `Class-Path` e
  `Crucible-Libs`), então a pasta `libraries/` de um servidor existente serve.
- **Ganchos de mods (`src/mods/java`)**: escolhemos *parar com mensagem clara* quando há fontes ali e
  não foi dada a pasta de mods (`--mods`, ou `-Phoshiestar.mods=` no Gradle; a mensagem cita os dois).
  Pular em silêncio
  geraria um jar com correções que nunca se aplicam. Os jars entram como `compileOnly` (só
  compilação, nunca dentro do jar). Hoje `src/mods/java` tem os ganchos de quatro recursos, então todo
  build exige `--mods`.

Tempos medidos nesta máquina (4 núcleos, cache do Gradle e do Minecraft já baixados): build completo
do zero (clone + `setupCrucible` + `buildPackages`) 125 a 156 s (último: 126 s), mais uns 15 s do
`test.sh`; build rápido (`--rapido`, mudança em arquivo nosso) 33 a 51 s. Um primeiro build numa máquina sem cache
baixa o Minecraft e as dependências e demora mais (não medido aqui).

## 2. Testes isolados

Todos os testes, de uma vez (depois de um build com `--mods`, porque os testes com mods e o de bytecode
do medidor leem o jar e as classes construídas):

```bash
HOSHIE_LIBS=<pasta libraries/ de um servidor> HOSHIE_MODS=<pasta mods/ de um servidor> \
    servidor/test.sh <workspace>              # modo rapido (padrao)
HOSHIE_LIBS=... HOSHIE_MODS=... servidor/test.sh <workspace> --completo   # dose cheia (mais repeticoes)
```

O `test.sh` roda, nesta ordem, e conta as falhas (saída 0 = tudo passou; no fim, `N teste(s) falharam`):
1. os testes gerais (abaixo), no JDK 8 e no 21;
2. com `HOSHIE_MODS`: `src/test/mods/*.bash` (mekanism, betterquesting, arsmagica, chromaticraft), com as
   classes reais dos mods; sem `HOSHIE_MODS` eles são pulados com aviso no resumo;
3. o script de cada recurso, `src/test/<recurso>/teste-*.bash` (chunkmap, parada, vigia, medidor e os
   que vierem). No modo rápido quem aceita `--rapido` recebe a opção (menos repetições: chunkmap e
   parada); `--completo` roda tudo cheio. Cada script também roda sozinho:
   `HOSHIE_LIBS=... bash <workspace>/src/test/<recurso>/teste-<recurso>.bash <workspace> [--rapido]`.

Script de teste com extensão `.sh` é falha: o `.gitignore` do upstream ignora `*.sh` e o script sumiria
na exportação (use `.bash`). `HOSHIE_SAIDA=<pasta>` guarda a saída (padrão: pasta temporária nova).

Testes gerais: compila a classe de exemplo `src/test/amostras/amostra/Alvo.java` para **Java 6, 7 e 8**, aplica cada
formato de correção, passa o resultado pelo `CheckClassAdapter` do ASM e depois **carrega e executa**
a classe num class loader de teste (o verificador da própria JVM confere os quadros de pilha). Roda
tudo no JDK 8 e no JDK 21. Testes: `TesteAsm` (formatos 1 a 3, as variantes protegidas dos formatos 1
e 3, e falha fechada), `TesteTrava`
(trava, registro de correções, transformador, `HashClasse`), `TesteCampoPrivado` (formato 4),
`TesteLimitador`, `TesteConfig` (hoshiestar.yml ausente, válido e completo sem regravar, item
faltando, chave desconhecida, erro de digitação, seção no formato errado, valor inválido, lixo). `HOSHIE_LIBS`
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
servidor/gerar-patches.sh <workspace> --github-raw --mods <pasta mods/>
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
     Mekanism `getEnergy` (o ChromatiCraft usa a variante protegida, abaixo).
   - **2. `Asm.trocarChamada`**: troca **uma** chamada (dono + nome + descritor + ordinal, e o total
     esperado no método) por um gancho estático que recebe receptor e argumentos. Ex.: BetterQuesting.
   - **3. `Asm.envolverChamada`**: um gancho decide se a chamada original acontece (ela fica dentro
     da classe do mod, num método-ponte sintético); se não, outro gancho dá o valor. Ex.: Ars Magica 2
     e ChromatiCraft (na variante protegida).
   - **4. `CampoPrivado`**: ler/escrever campo privado do mod pelo gancho, via `MethodHandle`
     guardado em `static final` (nada muda na classe do mod). Ex.: `OreIngredient.ores` do BQ.
   - **Formatos protegidos (1 e 3)**: `Asm.ganchoNoInicioProtegido` e `Asm.envolverChamadaProtegida`
     recebem um gancho a mais, `aoFalhar` (descritor `(Ljava/lang/Throwable;)V`, público, estático, que
     nunca lança). Se um gancho falhar (a classe dele não liga, o método não existe com aquele descritor,
     ou ele lança qualquer `Throwable`), o método do mod segue pelo corpo original (formato 1) ou pela
     chamada original (formato 3), em vez de o erro sair de dentro do método do mod e derrubar o servidor.
     O código original fica fora do `try`: o que ele lançar sai como sairia sem o gancho. O `aoFalhar`
     tem de ficar numa classe do jar sem tipos do Minecraft nem do mod (precisa ligar quando os outros
     não ligam). Sem falha, o caminho é o mesmo do formato comum. Ex.: ChromatiCraft (1 e 3), Ars
     Magica 2 (3). Prefira o protegido quando o gancho usa classes de mod (`src/mods/java`).
3. **Ganchos**: métodos `public static` numa classe `public` nossa. Receptor (método/chamada de
   instância) primeiro, com o tipo exato ou `Object`; os demais parâmetros e o retorno com os tipos
   exatos. Gancho que só usa tipos do JDK/Forge/Minecraft (receptor como `Object`) fica em
   `src/main/java`. Gancho que precisa das classes do mod fica em **`src/mods/java`** (compila com
   `--mods`). O utilitário confere o gancho lendo o `.class` dele antes de aplicar.
   Tipos do Minecraft no gancho (ex.: `EntityPlayerMP`) funcionam: o build reofusca as nossas
   classes (no arquivo o gancho fica `(Lmw;)Z`), então o conferidor (`ResolvedorDeGanchos`) traduz o
   descritor do arquivo com o mesmo mapa do FML (`RemapeadorFml` → `FMLDeobfuscatingRemapper`) antes
   de comparar com a classe do mod. Na classe do mod e nos textos da correção use sempre os nomes de
   tempo de execução (como o `javap -c` do jar do mod mostra: `net/minecraft/...`, métodos
   `func_NNN_x`).
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

   O autoteste real tem três correções: a de cima (formato 1); uma segunda na **mesma** classe e com
   a mesma trava, no formato 3 (`Asm.envolverChamada` da chamada
   `FlimFlamEnchantmentsHandler.deliverKarma(EntityPlayerMP)` dentro de `onPlayerTick`, com o gancho
   `deveEntregarKarma(EntityPlayerMP)`: tipo do Minecraft, só roda com jogador online); e uma com
   trava errada de propósito em `bq_standard.handlers.EventHandler`, que tem de ser recusada.
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
  tão cedo). Regras:
  - não existe: é criado com os padrões e os comentários;
  - válido e completo: os valores do admin valem e o arquivo **não é regravado** (comentários do
    admin, data e permissão ficam);
  - válido mas falta item (campo novo de uma versão nova, ou `ligado:` vazio): o item usa o padrão e
    o arquivo é regravado com ele (comentários próprios do admin se perdem, como no `Crucible.yml`);
  - algum problema (valor inválido como `ligado: sim`; seção no formato errado como `autoteste: true`;
    chave desconhecida, como erro de digitação `ligad:`): aquele item usa o padrão, sai um aviso por
    problema e o arquivo **não** é regravado;
  - ilegível (não é YAML ou não é mapa): todos os padrões, aviso, arquivo intocado;
  - nunca derruba o servidor. Os avisos saem no log do boot (`[Hoshiestar] config: ...`) e o
    `hoshie status` diz quantos houve.
  As seções e chaves de cada recurso estão na seção 0. Um campo novo de uma versão nova entra
  sozinho no arquivo do admin no primeiro boot dela (o arquivo é regravado com o padrão).
- **Comando `hoshie`**: `hoshie status`, `hoshie hash <classe>`. Registrado como o `crucible`
  (comando Bukkit, no `FMLServerStartingEvent` do `CrucibleModContainer`; prefixo reserva
  `hoshiestar:`). Quem pode: console e RCON sempre; jogador só se for operador de nível 4 **e** tiver o
  nó de permissão `hoshiestar.admin` (registrado no Bukkit com padrão "op": todo operador tem, a menos
  que um plugin de permissão o negue). O nó esconde o comando do tab e do `/help` de quem não tem e
  deixa o LuckPerms tirar o comando de um operador; ele **não** libera o comando para quem não é
  operador de nível 4. Jogador sem acesso recebe a mesma resposta de um comando que não existe
  (`Unknown command...`). No chat, `hoshie hash` mostra só o nome do jar de origem (no console, o
  caminho completo). Subcomandos novos: `ComandoHoshie.registrar(new Subcomando(){...})`; a permissão
  já foi conferida quando o subcomando é chamado.
- **`hoshiestar.api`**:
  - `Relogio.ticks` (campo `volatile long`): +1 na primeira linha de `MinecraftServer.tick()`.
  - `ThreadServidor.souAThreadDoServidor()`: uma leitura volatile e uma comparação. Vale desde a
    primeira linha de `MinecraftServer.run()` (antes de mods, mundos e plugins carregarem); antes
    disso devolve `false`.
  - `Log` (prefixo `[Hoshiestar] `, `Log.de("id")` para `[Hoshiestar] [id] `) e `Limitador`
    (N mensagens por janela, por chave). No console a linha sai como `[Hoshiestar]: [Hoshiestar] ...`
    (o console imprime o nome do logger; o `logs/latest.log` não, por isso o prefixo fica no texto).
  - `Trava` e `HashClasse` (SHA-256 dos bytes originais lidos como recurso, sem carregar a classe;
    `Resultado.descrever(false)` mostra só o nome do jar, para jogador).
  - `Recursos`: lista de recursos (id, ligado, linha de estado) para o status e o log do boot.
- **Chamadas a partir de classes do upstream**: sempre dentro de `try { ... } catch (Throwable t)` no
  ponto de chamada (como em `CrucibleCoremodHook` e `CrucibleModContainer`), para que uma falha do
  Hoshiestar, até ao carregar a classe, não corte o que o upstream faz em seguida.
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
- **Nomes reofuscados nos ganchos**: o `.class` dos ganchos dentro do jar tem os tipos do Minecraft
  com nome "notch" (`EntityPlayerMP` vira `mw`); a classe do mod chega ao transformador com os nomes
  de tempo de execução. O `ResolvedorDeGanchos` traduz o descritor do arquivo com o
  `FMLDeobfuscatingRemapper` (por reflexão, em `RemapeadorFml`, só quando uma classe alvo aparece)
  antes de comparar; a instrução gerada usa os nomes de tempo de execução, que é como a classe do
  gancho existe depois que o DeobfuscationTransformer a carrega. Provado no boot: a correção do
  autoteste com gancho `deveEntregarKarma(EntityPlayerMP)` foi aplicada e rodou com um jogador online.
- **Quadros de pilha**: a classe é lida com `EXPAND_FRAMES` e gravada só com `COMPUTE_MAXS`; os
  utilitários acrescentam quadros explícitos apenas nos desvios que criam, sempre num ponto de estado
  conhecido (entrada do método). Nada de `COMPUTE_FRAMES`, que exigiria carregar classes para achar
  superclasses comuns. Testado com Java 6, 7 e 8; um teste de controle sem os quadros falha no
  verificador da JVM (Java 7/8), o que mostra que o teste pega esse erro.

## 8. O que foi verificado e o que não foi

**Junção das duas faixas (2026-11-23; os oito recursos num jar só).** Os commits da faixa de mods
(mekanism, betterquesting, arsmagica, chromaticraft) entraram por cherry-pick no workspace da faixa do
servidor (chunkmap, parada, vigia, medidor), um commit por commit original; conflitos só em
`HoshiestarConfig`, `Catalogo`, `Servidor` e `TesteConfig`, resolvidos mantendo os dois lados (nos três
primeiros só houve acréscimos). Depois: `teste-chunkmap.sh` virou `.bash`; dois ajustes de teste do
chunkmap (arquivo "completo" do `TesteChunkmapConfig` sai do gerado; a rodada sem compilação em camadas
usa sempre 3 rodadas do estouro de pilha); e o `build.gradle.kts` deixou de configurar o source set dos
ganchos de mods antes do `setupCrucible` (o build do zero com `--mods` falhava). Resultado:
- `exportar.sh` duas vezes: `diff -r` vazio (15 patches, 192 arquivos novos).
- `build.sh` do zero (clone limpo + `servidor/`, `--mods`): 165 s; 5472 entradas, todas com o mesmo CRC
  do build rápido `--limpo`, menos `binpatches.pack.lzma`.
- Contra o jar de produção: mudam `MANIFEST.MF`, `binpatches.pack.lzma` (patches em MinecraftServer,
  World, EntityPlayerMP, DedicatedServer), `CrucibleModContainer` (+`$1`), `CrucibleCoremodHook`,
  `ServerShutdownThread` (parada), `thermos/wrapper/ChunkBlockHashMap` (chunkmap), e `MinecraftServer$7`,
  `ahb$5`, `ahb$6` (classes internas de MinecraftServer e World: `javap -c` igual, só a tabela de
  linhas); entram `ServerShutdownThread$1` e 153 classes `hoshiestar/`. Nenhuma classe de mod no jar.
- `test.sh` com `HOSHIE_LIBS` e `HOSHIE_MODS`, modo rápido, JDK 8 e 21: tudo passou (o chunkmap passou
  na segunda rodada, depois dos dois ajustes acima; os outros na primeira).
- Boot de fumaça no modpack real (cópia nova, tudo no padrão): "Done" com 292 mods, `recursos:` com os
  oito + autoteste (só vigia e medidor ligados; transformador não registrado), `hoshie status` lista
  todos, `hoshiestar.yml` criado com as nove seções, `stop` limpo, sem crash-report, nenhum aviso ou
  erro do Hoshiestar.

Verificado na base (logs no scratchpad da tarefa; números do último ciclo, depois da revisão independente):
- `build.sh` a partir de um clone limpo do upstream + `servidor/` (7 patches, 33 arquivos novos)
  reconstrói o jar em 126 s: 5362 entradas, todas com o mesmo CRC do jar do workspace, menos
  `binpatches.pack.lzma` (muda a cada build, ver crucible-build.md; a revisão desempacotou e as 1470
  entradas internas batem). Contra o jar de produção (conferido pela revisão): mudam só
  `MANIFEST.MF` (ganha a linha `Hoshiestar-Version`), `binpatches.pack.lzma`, `CrucibleModContainer`
  (+`$1`), `CrucibleCoremodHook` e `MinecraftServer$7` (números de linha), e entram as classes
  `hoshiestar/*`.
- `exportar.sh` rodado duas vezes seguidas: `diff -r` vazio.
- Testes isolados: todos passam no JDK 8 e no JDK 21 (TesteAsm 224, TesteTrava 35, TesteLimitador 13,
  TesteCampoPrivado 8, TesteConfig 35), inclusive o controle negativo dos quadros (tirando o quadro
  que o utilitário acrescenta, a JVM recusa a classe Java 7/8 com VerifyError) e a tradução de nomes
  reofuscados no conferidor de ganchos. Fora da suíte: `hoshiestar.yml` válido com o arquivo
  imutável (`chattr +i`) lido sem nenhum aviso; os 10 casos extras da revisão (`sim`, `"true"`, tag
  Java, BOM, tab...) dão aviso e não regravam.
- `--mods`: com uma fonte de teste em `src/mods/java` (gancho que recebe uma classe do OpenBlocks e um
  `EntityPlayerMP`) o build sem `--mods` para com a mensagem; com `--mods <mods/ da cópia>` compila, a
  classe entra no jar (tipo do mod intacto, tipo do Minecraft reofuscado) e nenhum jar de mod entra.
  A fonte de teste não ficou no repositório.
- Modpack real (cópia nova `forkfix`, 292 mods carregados pelo Forge, 52 plugins na lista do comando
  `plugins`), sem nenhum jar nosso em `mods/` e `plugins/`, dois boots, ambos com `stop` limpo e sem
  crash-report:
  1. Tudo no padrão (mundo novo, sem `hoshiestar.yml`): "Done", linha de boot, `recursos:
     autoteste=desligado`, transformador não registrado, `hoshiestar.yml` criado com comentários,
     uma linha por correção ("desligada") depois do "Done"; no console `hoshie status`, `hoshie hash`
     de classe existente e inexistente, `hoshiestar:hoshie status`.
  2. `autoteste.ligado: true` escrito à mão, mais um comentário do admin no topo: o arquivo **não**
     foi regravado (mesmo SHA-256 e mesma data depois do boot). As duas correções do OpenBlocks
     aplicadas (contador sobe 2 por tick; o gancho com tipo do Minecraft rodou 1947 vezes com o bot
     conectado), trava errada recusada com o hash visto "igual ao do arquivo do jar" (a classe do BQ
     recebe 8 métodos de mixin), class loader do gancho = do mod, transformador na posição 2 de 133
     (Mixin na 6). Comando por jogador (bot), com o LuckPerms do pacote ligado: sem op, `/hoshie
     status` e `/hoshiestar:hoshie hash ...` respondem `Unknown command...`, igual a um comando que não
     existe; com `op` (nível 4) status e hash respondem no chat, e o hash mostra só
     `OpenBlocks-1.12.20-GTNH.jar` (nenhum caminho de pasta no chat); operador com o nó negado no
     LuckPerms (`lp user ... permission set hoshiestar.admin false`): `Unknown command`; nó desfeito:
     volta a responder; sem op e com o nó dado pelo LuckPerms: `Unknown command` (o nó não libera).
  Mesmo número de mods, de erros de classe (VerifyError/NoSuchMethodError...) e de avisos de mixin
  que o boot do jar de produção na mesma máquina; as únicas linhas novas de aviso do Hoshiestar são as
  duas esperadas da trava errada.

Não verificado:
- Depois da junção: os recursos ligados no modpack real juntos (cada faixa testou os seus no servidor
  separadamente; o boot de fumaça da junção foi só com o padrão).
- O controle do estouro de pilha do chunkmap (T2 do `TesteChunkmapAdversario`) depende do JIT: no JDK 21
  sem compilação em camadas, com 3 rodadas, caíram só 5/2/1 estouros em remove/put/get; pode falhar por
  acaso (falso alarme do teste, não do código).
- Boot do jar em Java 8 (a produção usa Java 21).
- Formato 3 com método protegido herdado de classe de outro pacote (o verificador pode recusar).
- Custo do transformador medido em servidor (por desenho: uma busca em HashMap por classe carregada,
  e só quando há correção ligada).
- Plugins de filtro de comando desligados na cópia de teste (`!DISABLED-HOSHIESTAR`); o tab-complete
  e o `/help` por jogador (o código do Bukkit esconde comandos sem permissão nos dois, mas o bot não
  testa tab nem `/help`).
