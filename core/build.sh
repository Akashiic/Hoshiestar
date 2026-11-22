#!/usr/bin/env bash
# Hoshiestar-Core - compila o jar do mod pela rota "SRG direto" (a mesma do medidor).
# Licenca: GPLv3 ou posterior (SPDX-License-Identifier: GPL-3.0-or-later).
#
# Uso:
#   core/build.sh <pasta-do-servidor> <pasta-de-saida>
#
# <pasta-do-servidor> precisa ter:
#   server.jar                                                (Crucible 1.7.10)
#   libraries/net/minecraft/server/1.7.10/server-1.7.10.jar   (instalado no 1o boot)
#   libraries/... SpecialSource, asm, guava, jopt-simple, opencsv, commons-lang3, lzma, launchwrapper
#   mods/  com UniMixins, Mekanism, BetterQuesting (com bq_standard), Ars Magica 2, ChromatiCraft e
#          DragonAPI (os recursos compilam contra as classes desses mods)
#
# O que faz (nada e baixado da rede):
#   1. tira do server.jar o mapeamento notch->SRG que o proprio FML usa em tempo de execucao;
#   2. aplica TODOS os binpatches do server.jar nas classes do jar vanilla (ferramentas/
#      AplicarBinpatch.java, com o ClassPatchManager do proprio FML). Assim a visao de compilacao
#      tem os membros que o Forge/CraftBukkit/Crucible acrescentam (ex.: MinecraftServer.primaryThread);
#   3. junta vanilla + classes com patch + server.jar e renomeia tudo para SRG com o SpecialSource;
#      o resultado (a "visao SRG") fica em cache em <pasta-de-saida>/cache/ e so e refeito quando o
#      server.jar, o jar vanilla, o SpecialSource ou a ferramenta do passo 2 mudam;
#   4. compila com javac do JDK 8 (bytecode 52) contra a visao SRG + jars dos mods. O codigo escreve
#      todo alvo de mixin e todo membro do Minecraft com nome SRG e remap = false: sem refmap;
#   5. empacota Hoshiestar-Core-<versao>-serveronly.jar (so hoshiestar/core/** e recursos do mod);
#   6. se existir src/test/java com testes, compila e roda os testes isolados (harness), com o
#      diretorio corrente em <pasta-de-saida>/teste.
#
# Variaveis opcionais:
#   JAVA8_HOME     JDK 8 (padrao: /usr/lib/jvm/java-8-openjdk-amd64)
#   HOSHIE_TESTES  0 = nao roda os testes isolados (padrao 1)
set -euo pipefail

SERVER="${1:?informe a pasta do servidor (com server.jar, libraries/ e mods/)}"
OUT="${2:?informe a pasta de saida do jar}"
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER="$(cd "$SERVER" && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
case "$OUT/" in
  "$AQUI/"*|"$(dirname "$AQUI")/"*) echo "ERRO: a pasta de saida nao pode ficar dentro do repositorio ($OUT)" >&2; exit 1 ;;
esac

VERSAO_JAVA="$AQUI/src/main/java/hoshiestar/core/api/Versao.java"
VERSAO="$(sed -n 's/.*String VERSAO = "\([^"]*\)".*/\1/p' "$VERSAO_JAVA")"
[[ -n "$VERSAO" ]] || { echo "ERRO: nao achei VERSAO em $VERSAO_JAVA" >&2; exit 1; }
[[ "$VERSAO" =~ ^[0-9][0-9A-Za-z.+-]*$ ]] || { echo "ERRO: VERSAO com formato estranho: $VERSAO" >&2; exit 1; }

JAVA8_HOME="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
# O ambiente pode exportar opcoes feitas para o JDK 21 que o JDK 8 recusa.
J8=(env -u _JAVA_OPTIONS -u JAVA_TOOL_OPTIONS)
JAVA="$JAVA8_HOME/bin/java"
JAVAC="$JAVA8_HOME/bin/javac"
JAR="$JAVA8_HOME/bin/jar"
JAVAP="$JAVA8_HOME/bin/javap"
for f in "$JAVA" "$JAVAC" "$JAR" "$JAVAP"; do
  [[ -x "$f" ]] || { echo "ERRO: falta $f (defina JAVA8_HOME)" >&2; exit 1; }
done
"${J8[@]}" "$JAVAC" -version 2>&1 | grep -q '1\.8\.' || { echo "ERRO: $JAVAC nao e JDK 8" >&2; exit 1; }
for p in xz unzip sha256sum; do
  command -v "$p" >/dev/null || { echo "ERRO: falta o programa $p" >&2; exit 1; }
done

L="$SERVER/libraries"
achar() { # achar <padrao-glob> : primeiro arquivo que casar, ou erro
  local f
  for f in $1; do [[ -f "$f" ]] && { echo "$f"; return 0; }; done
  echo "ERRO: nao achei $1" >&2; return 1
}
# achar_mod <descricao> <padrao-glob> <classe-marcadora> : primeiro jar do padrao que tem a classe
achar_mod() {
  local f
  shopt -s nullglob
  for f in $2; do
    if [[ -f "$f" ]] && unzip -l "$f" "$3" >/dev/null 2>&1; then echo "$f"; shopt -u nullglob; return 0; fi
  done
  shopt -u nullglob
  echo "ERRO: falta o jar do $1 em $SERVER/mods (padrao '$(basename "$2")', com a classe $3)." >&2
  echo "      Os recursos do Hoshiestar-Core compilam contra esse mod; copie o jar do pacote para mods/." >&2
  return 1
}
SERVER_JAR="$(achar "$SERVER/server.jar")"
VANILLA="$(achar "$L/net/minecraft/server/1.7.10/server-1.7.10.jar")"
SS="$(achar "$L/net/md-5/SpecialSource/*/SpecialSource-*.jar")"
ASM="$(achar "$L/org/ow2/asm/asm/*/asm-*.jar")"
ASM_COMMONS="$(achar "$L/org/ow2/asm/asm-commons/*/asm-commons-*.jar")"
ASM_TREE="$(achar "$L/org/ow2/asm/asm-tree/*/asm-tree-*.jar")"
ASM_ANALYSIS="$(achar "$L/org/ow2/asm/asm-analysis/*/asm-analysis-*.jar")"
JOPT="$(achar "$L/net/sf/jopt-simple/jopt-simple/*/jopt-simple-*.jar")"
GUAVA="$(achar "$L/com/google/guava/guava/*/guava-*.jar")"
OPENCSV="$(achar "$L/com/opencsv/opencsv/*/opencsv-*.jar")"
LANG3="$(achar "$L/org/apache/commons/commons-lang3/*/commons-lang3-*.jar")"
LZMA="$(achar "$L/lzma/lzma/*/lzma-*.jar")"
LAUNCHWRAPPER="$(achar "$L/io/github/cruciblemc/launchwrapper/*/launchwrapper-*.jar")"

M="$SERVER/mods"
UNIMIXINS="$(achar_mod UniMixins "$M/+unimixins-all-1.7.10-*.jar" com/gtnewhorizon/gtnhmixins/ILateMixinLoader.class)"
MEKANISM="$(achar_mod Mekanism "$M/Mekanism-1.7.10-*.jar" mekanism/common/content/matrix/SynchronizedMatrixData.class)"
BETTERQUESTING="$(achar_mod 'BetterQuesting (com bq_standard)' "$M/BetterQuesting-*.jar" bq_standard/handlers/EventHandler.class)"
ARSMAGICA="$(achar_mod 'Ars Magica 2' "$M/AM2*.jar" am2/AMEventHandler.class)"
CHROMATICRAFT="$(achar_mod ChromatiCraft "$M/ChromatiCraft*.jar" Reika/ChromatiCraft/Auxiliary/Ability/AbilityCalls.class)"
DRAGONAPI="$(achar_mod DragonAPI "$M/DragonAPI*.jar" Reika/DragonAPI/DragonAPICore.class)"
echo "[build] versao $VERSAO; mods: $(basename "$UNIMIXINS"), $(basename "$MEKANISM"), $(basename "$BETTERQUESTING"),"
echo "[build]   $(basename "$ARSMAGICA"), $(basename "$CHROMATICRAFT"), $(basename "$DRAGONAPI")"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/hoshiecore-build.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------------------------
# 1 a 3: visao SRG com binpatch (em cache)
# ---------------------------------------------------------------------------------------------
CACHE="$OUT/cache"
mkdir -p "$CACHE"
VISAO="$CACHE/visao-srg.jar"
CHAVE_ARQ="$CACHE/visao-srg.chave"
CHAVE="formato=2 $(sha256sum "$SERVER_JAR" "$VANILLA" "$SS" "$AQUI/ferramentas/AplicarBinpatch.java" | awk '{print $1}' | tr '\n' ' ')"
if [[ -f "$VISAO" && -f "$CHAVE_ARQ" && "$(cat "$CHAVE_ARQ")" == "$CHAVE" ]]; then
  echo "[build] visao SRG com binpatch: cache valido ($VISAO)"
else
  echo "[build] visao SRG com binpatch: refazendo (server.jar ou ferramenta mudou, ou primeira vez)"
  rm -f "$VISAO" "$CHAVE_ARQ"
  # 1. mapeamento notch -> SRG (o mesmo que o FML usa no boot)
  unzip -p "$SERVER_JAR" deobfuscation_data-1.7.10.lzma | xz -d --format=lzma > "$WORK/notch-srg-full.srg"
  grep -v '^PK:' "$WORK/notch-srg-full.srg" > "$WORK/notch-srg.srg"
  echo "[build] mapeamento: $(grep -c '^CL:' "$WORK/notch-srg.srg") classes, $(grep -c '^MD:' "$WORK/notch-srg.srg") metodos"
  # 2. binpatches
  mkdir -p "$WORK/ferr" "$WORK/patched"
  "${J8[@]}" "$JAVAC" -encoding UTF-8 -source 1.8 -target 1.8 -proc:none -nowarn -Xlint:-options \
    -cp "$SERVER_JAR:$GUAVA" -d "$WORK/ferr" "$AQUI/ferramentas/AplicarBinpatch.java"
  ( cd "$WORK" && "${J8[@]}" "$JAVA" -cp "$WORK/ferr:$SERVER_JAR:$LZMA:$GUAVA" AplicarBinpatch "$VANILLA" "$WORK/patched" ) \
    2> "$WORK/binpatch.err" || { cat "$WORK/binpatch.err" >&2; echo "ERRO: falha ao aplicar os binpatches" >&2; exit 1; }
  [[ -f "$WORK/patched/net/minecraft/server/MinecraftServer.class" ]] \
    || { echo "ERRO: os binpatches nao geraram net/minecraft/server/MinecraftServer.class" >&2; exit 1; }
  # 3. vanilla (so classes do jogo) + classes com patch por cima + server.jar, renomeadas para SRG
  mkdir -p "$WORK/junto"
  ( cd "$WORK/junto"
    unzip -q "$VANILLA" '*.class' -x 'com/*' 'io/*' 'org/*' 'gnu/*' 'javax/*'
    cp -R "$WORK/patched/." .
    unzip -qo "$SERVER_JAR" '*.class' )
  "${J8[@]}" "$JAR" cf "$WORK/junto.jar" -C "$WORK/junto" .
  SS_CP="$SS:$ASM:$ASM_COMMONS:$ASM_TREE:$ASM_ANALYSIS:$JOPT:$GUAVA:$OPENCSV:$LANG3"
  ( cd "$WORK" && "${J8[@]}" "$JAVA" -cp "$SS_CP" net.md_5.specialsource.SpecialSource \
      --in-jar "$WORK/junto.jar" --out-jar "$WORK/visao-srg.jar" --srg-in "$WORK/notch-srg.srg" -q )
  # conferencias da visao: classes e membros que so existem com o binpatch / no server.jar
  "${J8[@]}" "$JAVAP" -p -cp "$WORK/visao-srg.jar" net.minecraft.server.MinecraftServer > "$WORK/ms.txt"
  grep -q 'public final java.lang.Thread primaryThread;' "$WORK/ms.txt" \
    || { echo "ERRO: a visao SRG nao tem MinecraftServer.primaryThread (binpatch nao aplicado?)" >&2; exit 1; }
  grep -q 'void func_71260_j() throws net.minecraft.world.MinecraftException' "$WORK/ms.txt" \
    || { echo "ERRO: a visao SRG nao tem 'func_71260_j() throws MinecraftException' (binpatch nao aplicado?)" >&2; exit 1; }
  "${J8[@]}" "$JAVAP" -p -cp "$WORK/visao-srg.jar" thermos.wrapper.ChunkBlockHashMap > "$WORK/cbhm.txt"
  grep -q 'net.minecraft.world.chunk.Chunk get(int, int)' "$WORK/cbhm.txt" \
    || { echo "ERRO: a visao SRG nao tem thermos.wrapper.ChunkBlockHashMap.get(int,int) com nome SRG" >&2; exit 1; }
  mv "$WORK/visao-srg.jar" "$VISAO"
  printf '%s' "$CHAVE" > "$CHAVE_ARQ"
  echo "[build] visao SRG pronta: $(find "$WORK/patched" -name '*.class' | wc -l) classes com binpatch;" \
       "conferido: MinecraftServer.primaryThread, func_71260_j throws MinecraftException, thermos.wrapper.ChunkBlockHashMap"
fi

# ---------------------------------------------------------------------------------------------
# 4. compilar
# ---------------------------------------------------------------------------------------------
mkdir -p "$WORK/classes"
find "$AQUI/src/main/java" -name '*.java' | sort > "$WORK/fontes.txt"
CP="$VISAO:$LAUNCHWRAPPER:$UNIMIXINS:$VANILLA:$GUAVA:$MEKANISM:$BETTERQUESTING:$ARSMAGICA:$CHROMATICRAFT:$DRAGONAPI"
"${J8[@]}" "$JAVAC" -encoding UTF-8 -source 1.8 -target 1.8 -proc:none -Xlint:-options -nowarn \
  -cp "$CP" -d "$WORK/classes" @"$WORK/fontes.txt"
cp "$AQUI/src/main/resources/"* "$WORK/classes/"
# Versao: a unica fonte e hoshiestar.core.api.Versao.VERSAO (que tambem vai no @Mod).
grep -q '"version": "${version}"' "$WORK/classes/mcmod.info" \
  || { echo "ERRO: mcmod.info precisa ter \"version\": \"\${version}\" (a versao vem de Versao.VERSAO)" >&2; exit 1; }
sed -i 's/"version": "${version}"/"version": "'"$VERSAO"'"/' "$WORK/classes/mcmod.info"
[[ "$(grep -c '"version":' "$WORK/classes/mcmod.info")" == "1" ]] \
  && grep -q "\"version\": \"$VERSAO\"" "$WORK/classes/mcmod.info" \
  || { echo "ERRO: a versao do mcmod.info nao ficou igual a $VERSAO" >&2; exit 1; }

# ---------------------------------------------------------------------------------------------
# 5. empacotar
# ---------------------------------------------------------------------------------------------
cat > "$WORK/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
FMLCorePlugin: hoshiestar.core.nucleo.CoreCoremod
FMLCorePluginContainsFMLMod: true
Implementation-Title: Hoshiestar-Core
Implementation-Version: $VERSAO
EOF
NOME="Hoshiestar-Core-$VERSAO-serveronly.jar"
rm -f "$OUT/$NOME"
"${J8[@]}" "$JAR" cfm "$OUT/$NOME" "$WORK/MANIFEST.MF" -C "$WORK/classes" .

# conferencia: o jar so tem classes hoshiestar/core/** e os recursos do mod
estranhos="$(unzip -Z1 "$OUT/$NOME" | grep -vE '^(META-INF/|META-INF/MANIFEST\.MF|hoshiestar/|hoshiestar/core/.*|mcmod\.info|mixins\.hoshiestar_core\.(early|late)\.json)$' || true)"
[[ -z "$estranhos" ]] || { echo "ERRO: o jar tem arquivos fora de hoshiestar/core/**: $estranhos" >&2; exit 1; }
# conferencia: a versao e a mesma no manifesto, no mcmod.info e na constante
unzip -p "$OUT/$NOME" META-INF/MANIFEST.MF | tr -d '\r' | grep -qx "Implementation-Version: $VERSAO" \
  || { echo "ERRO: o manifesto do jar nao diz $VERSAO" >&2; exit 1; }
unzip -p "$OUT/$NOME" mcmod.info | grep -q "\"version\": \"$VERSAO\"" \
  || { echo "ERRO: o mcmod.info do jar nao diz $VERSAO" >&2; exit 1; }
"${J8[@]}" "$JAVAP" -constants -cp "$WORK/classes" hoshiestar.core.api.Versao | grep -q "String VERSAO = \"$VERSAO\"" \
  || { echo "ERRO: Versao.VERSAO compilado nao e $VERSAO" >&2; exit 1; }
# conferencia: todo .class com versao 52 (Java 8)
( cd "$WORK/classes"
  ruins=0
  while IFS= read -r c; do
    v="$("${J8[@]}" "$JAVAP" -v "$c" | sed -n 's/.*major version: \([0-9]*\).*/\1/p')"
    [[ "$v" == "52" ]] || { echo "ERRO: $c tem versao $v" >&2; ruins=1; }
  done < <(find . -name '*.class')
  exit $ruins )
echo "[build] classes: $(find "$WORK/classes" -name '*.class' | wc -l), todas com bytecode versao 52 (Java 8)"

# ---------------------------------------------------------------------------------------------
# 6. testes isolados (harness): cada classe *Teste com main(); codigo de saida 0 = passou
# ---------------------------------------------------------------------------------------------
TESTES_DIR="$AQUI/src/test/java"
if [[ "${HOSHIE_TESTES:-1}" != "0" && -d "$TESTES_DIR" ]] && find "$TESTES_DIR" -name '*.java' | grep -q .; then
  mkdir -p "$WORK/testes"
  find "$TESTES_DIR" -name '*.java' | sort > "$WORK/fontes-teste.txt"
  "${J8[@]}" "$JAVAC" -encoding UTF-8 -source 1.8 -target 1.8 -proc:none -Xlint:-options -nowarn \
    -cp "$WORK/classes:$CP" -d "$WORK/testes" @"$WORK/fontes-teste.txt"
  rm -rf "$OUT/teste"
  mkdir -p "$OUT/teste"
  falhas=0
  total=0
  while IFS= read -r t; do
    classe="${t#./}"; classe="${classe%.class}"; classe="${classe//\//.}"
    total=$((total + 1))
    if ( cd "$OUT/teste" && "${J8[@]}" "$JAVA" -cp "$WORK/testes:$WORK/classes:$CP" "$classe" ); then
      echo "[teste] $classe: passou"
    else
      echo "[teste] $classe: FALHOU" >&2
      falhas=$((falhas + 1))
    fi
  done < <(cd "$WORK/testes" && find . -name '*Teste.class' | sort)
  [[ "$falhas" == "0" ]] || { echo "ERRO: $falhas de $total teste(s) falharam" >&2; exit 1; }
  echo "[teste] $total teste(s) isolado(s) passaram (pasta de trabalho: $OUT/teste)"
fi

echo "[build] jar: $OUT/$NOME"
sha256sum "$OUT/$NOME"
