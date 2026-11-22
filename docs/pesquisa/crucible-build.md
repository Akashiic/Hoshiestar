# Reconstruir o jar de producao do Crucible a partir do codigo (tarefa 0.7)

Resultado em uma frase: sim, o jar de producao pode ser reconstruido. O jar novo tem o mesmo tamanho em bytes
(11188232), o mesmo manifesto (identico, linha a linha) e 5318 entradas com os mesmos nomes. Das 5318 entradas, 5317 tem
o mesmo CRC (soma de verificacao de cada arquivo interno). A unica diferente e `binpatches.pack.lzma`, que muda a
cada build mesmo com o mesmo codigo (provado abaixo). O sha256 do jar inteiro NAO e igual ao de producao e nao
deve ser esperado igual.

Commit construido: `67b53034` (confirmado com `git log -1`: "teleport: fire a single PlayerTeleportEvent on a world change").
Maquina: 4 CPUs visiveis, 15 GB de RAM (nproc, free -g), Gradle limitado a 4 GB (-Xmx4g).

## 1. Pre-requisitos

| Item | Valor | Evidencia |
|---|---|---|
| JDK 8 (executa o Gradle e compila o jogo) | OpenJDK 8u514, via apt | `apt-get install -y openjdk-8-jdk-headless` funcionou em 10 s (apt-get update antes) |
| JDK 17 (NAO esta no README) | OpenJDK 17.0.21, via apt | `buildSrc/build.gradle.kts` linhas 57-62 exigem toolchain (JDK que o Gradle procura sozinho) versao 17; sem ele: "Cannot find a Java installation ... languageVersion=17". `apt-get install -y openjdk-17-jdk-headless` (8 s) resolveu |
| Gradle | 8.14.3, baixado pelo `./gradlew` | `gradle/wrapper/gradle-wrapper.properties` |
| git | qualquer; precisa de `.git` | `build.gradle.kts` linha ~260 le branch e hash por `git log`/`git rev-parse` |
| Rede | repositorios maven abaixo | ver workaround 1 |
| Codificacao UTF-8 | `LC_ALL=C.UTF-8` e `-Dfile.encoding=UTF-8` | ver workaround 3 (importante) |

O JDK 21 da maquina continua sendo o `java` padrao (apt nao trocou). O Gradle usa o JDK 8 por `JAVA_HOME`.
Submodulos git: o repositorio nao tem (`.gitmodules` nao existe).
O CI (`.github/workflows/staging-build.yml`) usa Temurin 8 e roda `./gradlew cleanCache clean setupCrucible --refresh-dependencies`
e depois `./gradlew buildPackages`. No GitHub o JDK 17 ja vem na imagem do runner, por isso o CI nao o instala.

## 2. Comandos exatos, em ordem

Variavel `S` abaixo e a pasta de trabalho
`<trabalho>/`.

```bash
# 2.1 clone local e checkout (0,4 s). Branch "staging" e core.abbrev=8 reproduzem o nome/hash do manifesto de producao
git clone $S/src/Crucible-staging $S/build/Crucible
cd $S/build/Crucible
git checkout -B staging 67b53034
git config core.abbrev 8

# 2.2 JDKs (10 s + 8 s)
apt-get update -qq && apt-get install -y openjdk-8-jdk-headless openjdk-17-jdk-headless

# 2.3 ambiente da build
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export LC_ALL=C.UTF-8 LANG=C.UTF-8
unset _JAVA_OPTIONS                       # valor feito para JDK 21; removido por prudencia
export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dfile.encoding=UTF-8"   # mantem proxy e truststore

# 2.4 preparar o workspace (baixa Minecraft, aplica patches do Forge/Cauldron): 3 min 49 s
./gradlew -I $S/build/rewrite-github-raw.init.gradle -Dorg.gradle.jvmargs=-Xmx4g --no-daemon \
    cleanCache clean setupCrucible --refresh-dependencies

# 2.5 gerar os jars: 40 s (com o workspace ja pronto e compilado em cache)
./gradlew -I $S/build/rewrite-github-raw.init.gradle -Dorg.gradle.jvmargs=-Xmx4g --no-daemon buildPackages
# saida: build/distributions/Crucible-1.7.10-staging-67b53034-server.jar
```

Tempos medidos (comando `time`):

| Passo | Tempo |
|---|---|
| `./gradlew --version` (baixa Gradle 8.14.3) | 14,6 s |
| setupCrucible, 1a vez (cache frio) | 2 min 05 s |
| buildPackages, 1a vez (compila tudo) | 2 min 34 s |
| setupCrucible, 2a vez, com `clean` e `--refresh-dependencies` | 3 min 49 s |
| buildPackages, 2a vez | 40 s |
| Total do caminho limpo (2.4 + 2.5) | cerca de 4 min 30 s |

Uso de disco: `~/.gradle` 889 MB, clone com build 333 MB.

O arquivo `$S/build/rewrite-github-raw.init.gradle` (init script, um arquivo que o Gradle le antes do build) e o
workaround 1. Conteudo resumido: percorre `pluginManagement`, `buildscript` e `repositories` de todos os projetos e troca
a URL.

## 3. Workarounds (todos necessarios ou relevantes)

1. Repositorio maven do juanmuscaria. O build usa `https://github.com/juanmuscaria/maven/raw/master` (build.gradle.kts linhas 15-18,
   31-38 e a lista `repos` linhas ~52-57). O proxy daqui responde 403 para esse formato. O init script reescreve para
   `https://raw.githubusercontent.com/juanmuscaria/maven/master`. Nenhum arquivo versionado foi alterado: `git status --short`
   voltou vazio depois das builds. O log mostra 3 linhas `[init] repo ...: ... -> ...` (juanmuscaria 2x, thermos 1x), ou seja
   a troca de fato ocorreu (`$S/build/setup.log` linhas 18-22). Em rede normal (CI) o init script nao e necessario.
2. JDK 17 alem do 8: ver tabela de pre-requisitos. Primeira falha real: `:buildSrc:compileJava FAILED`.
3. Codificacao. Primeira build (shell em locale POSIX, sem `file.encoding`) gerou um jar com 3 entradas erradas: o
   Java compilou textos com acento/caracteres especiais usando ASCII e gravou `?`. Prova: `javap` de
   `cpw/mods/fml/client/FMLClientHandler.class` mostra a string `:NOFML??r` no meu jar contra `:NOFML?r` em producao, e
   `io/github/crucible/patches/StreamsTransformer.class` ("patching???" contra "patching?", 3223 contra 3217 bytes).
   Com `LC_ALL=C.UTF-8` e `-Dfile.encoding=UTF-8` essas duas classes ficaram com CRC igual ao de producao.
   O `gradle.properties` do projeto tem `file.encoding=UTF-8` mas so vale para o daemon (processo residente do Gradle), e aqui
   usamos `--no-daemon`, por isso o ambiente precisa ser ajustado.
4. Nome da versao. Com checkout "solto" (detached HEAD) o nome sai `1.7.10-HEAD-67b5303` (7 caracteres de hash).
   Producao tem `1.7.10-staging-67b53034`. Para igualar: branch chamada `staging` e `git config core.abbrev 8`
   (a build le `git log --format=%h` e `git rev-parse --abbrev-ref HEAD`, build.gradle.kts ~linhas 262-268).
5. `--no-daemon` e `-Dorg.gradle.jvmargs=-Xmx4g`: o `gradle.properties` traz `org.gradle.jvmargs=-Xmn4G` (so tamanho da
   geracao jovem), entao o limite de 4 GB de heap foi passado na linha de comando.
6. Aviso inofensivo na saida: `[Fatal Error] argo-2.25.pom:3:10: Already seen doctype.` (ocorre em `packageServer`, o
   build termina com sucesso).

## 4. Comparacao com o jar de producao

Producao: `$S/server-orig/server.jar`. Novo: `$S/build/Crucible/build/distributions/Crucible-1.7.10-staging-67b53034-server.jar`
(copias das execucoes: `$S/build/run1-HEAD-server.jar`, `run2-staging-server.jar`, `run3-staging-server.jar`).

| Campo | Producao | Reconstruido (execucao 2, UTF-8, branch staging) |
|---|---|---|
| Tamanho do jar | 11 188 232 bytes | 11 188 169 bytes (execucao 2); 11 188 232 bytes (execucoes 3 e 4, apos `--rerun`) |
| sha256 | cb79a231de9208f3... | d4ae1f3185065d58... (exec. 2), e5d882d5df21a80054... (exec. 3), b875d764c71b8e33... (exec. 4) |
| Entradas (`unzip -l`) | 5318 | 5318 |
| Nomes diferentes | - | 0 (`comm` das listas ordenadas: 0 so em um lado, 0 so no outro) |
| Manifesto (`META-INF/MANIFEST.MF`) | Implementation-Version 1.7.10-staging-67b53034 | identico, `diff` vazio (inclui Thermos-Git-Hash 67b530340b080f94..., Thermos-Git-Branch staging, Class-Path, Crucible-Libs) |
| CRC por entrada | - | 5317 de 5318 iguais; difere so `binpatches.pack.lzma` |
| Entradas com CRC diferente na execucao 1 (sem UTF-8, HEAD) | - | 4: MANIFEST.MF (nome da versao), binpatches, FMLClientHandler.class, StreamsTransformer.class |

Sobre `binpatches.pack.lzma` (patches binarios que o cliente Forge usa; para servidor dedicado nao participa da logica de jogo,
nao verificado em execucao): 4 builds do mesmo commit deram 4 CRCs diferentes (daca4511, cf8441de e outros), com tamanho
comprimido 1 421 692 (exec. 2) ou 1 421 755 (exec. 3 e 4, igual ao de producao). Descomprimido (python `lzma`,
formato ALONE): producao 7 552 578 bytes, exec. 4 7 552 578 bytes, exec. 2 7 554 048 bytes; sha256 diferente nos tres.
Conclusao medida: esse arquivo nao e deterministico entre execucoes, entao jar byte a byte identico nao e alcancavel
neste processo. Nao afirmo identidade byte a byte.

## 5. Nao verificado

- Se o conteudo de `binpatches.pack.lzma` e semanticamente equivalente ao de producao (so medi tamanho e hash).
  Causa da variacao (ordem de HashMap, relogio etc.) nao investigada.
- Se o jar reconstruido sobe e funciona no servidor de producao: o servidor Minecraft NAO foi iniciado (regra do projeto).
- Build com Temurin 8 (como o CI); aqui usei OpenJDK 8u514 do Ubuntu 24.04. Classes identicas por CRC sugerem pouca
  diferenca, mas nao comparei fornecedores.
- Build sem o init script em rede sem restricao do proxy.
- Reprodutibilidade em outra maquina: o resultado depende do cache de `~/.gradle` (889 MB) e de downloads de
  `maven.minecraftforge.net`, `libraries.minecraft.net` e `raw.githubusercontent.com`; versoes de dependencias estao fixas no
  `build.gradle.kts`, mas nao conferi checksums de cada uma.
- `git status` apos as builds estava limpo (nenhum arquivo versionado alterado), mas os arquivos gerados em `build/`,
  `eclipse/` etc. nao foram inspecionados um a um.
- A ultima build do diretorio ficou apos `--rerun` de `genBinPatches`/`packageServer` (execucao 4), nao apos um `clean` novo.

## 6. Glossario rapido

- Gradle: ferramenta que compila o projeto. `./gradlew` e o executavel que acompanha o repositorio.
- toolchain: JDK que o Gradle procura sozinho para uma etapa especifica (aqui, JDK 17 para o `buildSrc`).
- CRC: numero de 32 bits calculado sobre o conteudo de cada arquivo dentro do jar; iguais significam conteudo igual (com
  chance minima de colisao).
- init script: script que o Gradle executa antes de ler o projeto; usado aqui para trocar a URL do repositorio sem mexer no codigo.

---

## Revisão independente (outro agente, instruído a tentar refutar)

Resultado: confirmado: 9, impreciso: 1.

O relatorio resiste bem: jar, manifesto, 5318 entradas, 5317 CRCs, 5028 classes identicas (amostra de 200: 200 iguais) e nao-determinismo do binpatches foram todos reconfirmados por mim. Ponto fraco: a falha original do JDK 17 e os tempos do apt nao tem log guardado (citado como setup.log, que nao contem isso). Boot test que o relatorio nao fez: Done em 57,2 s, 292 mods, Overall mean tick 0.000 ms (TPS 20, ocioso), sem crash-reports, stop encerrou o processo java. Nao encontrei correcao material nas afirmacoes verificaveis.

### Afirmações corrigidas

- **impreciso**: 3. Build exige JDK 17 como toolchain do buildSrc alem do JDK 8
  - Correção: buildSrc/build.gradle.kts linhas 55-62 de fato usam javaToolchains.compilerFor languageVersion 17 (com release 8), e /usr/lib/jvm tem java-17. Mas a mensagem de erro ':buildSrc:compileJava FAILED ... languageVersion=17' NAO esta em nenhum log guardado (setup.log nao tem FAILED nem 'Cannot find'; so aparece no proprio relatorio), entao a falha original e o tempo de 8 s do apt nao sao verificaveis. O relatorio diz 'evidencia: setup.log', o que e incorreto. A exigencia e plausivel pelo codigo, mas nao comprovada por teste negativo.

### O que o relatório deixou de fora ou errou

- O relatorio diz na secao 5 que o servidor NAO foi iniciado e que nao se sabe se o jar reconstruido sobe; o boot test agora prova que sobe (Done em 57 s, 292 mods, 20 TPS ocioso, sem crash-reports, parada limpa). Esse e o dado mais importante e esta ausente do relatorio.
- A evidencia do erro ':buildSrc:compileJava FAILED ... languageVersion=17' e dos tempos de apt-get nao esta em nenhum log guardado; o relatorio cita setup.log como prova, mas esse log nao contem a falha. Falta guardar o log do teste negativo.
- O relatorio compara a producao com o jar da execucao 2 (11188169 bytes) em varios pontos; o jar final deixado em build/distributions e da execucao 4 (11188232 bytes, sha b875d764...), e o relatorio mistura os dois, o que pode confundir quem for reutilizar os arquivos m/*.crc.
- Maquina de build descrita como 4 CPUs/15 GB (confirmado por nproc e free), diferente da producao de 7 vCores/17 GB; nao afeta o resultado mas o jar foi construido com OpenJDK 8u514 do Ubuntu e nao Temurin 8 como no CI (o relatorio ja admite como nao verificado).
- Nao foi comparado o conteudo semantico dos binpatches nem se a variacao vem de ordenacao/tempo; como 5317 de 5318 entradas e todas as 5028 classes sao identicas e o servidor sobe com 292 mods, o risco pratico e baixo, mas continua nao provado.
