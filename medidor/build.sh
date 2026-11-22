#!/usr/bin/env bash
# Hoshiestar-Medidor - compila o jar do mod pela rota "SRG direto".
# Licenca: GPLv3 ou posterior (SPDX-License-Identifier: GPL-3.0-or-later).
#
# Uso:
#   medidor/build.sh <pasta-do-servidor> <pasta-de-saida>
#
# <pasta-do-servidor> precisa ter:
#   server.jar                                           (Crucible 1.7.10)
#   libraries/net/minecraft/server/1.7.10/server-1.7.10.jar   (instalado no 1o boot)
#   libraries/net/md-5/SpecialSource/1.10.0/...          (idem, com asm, guava, jopt-simple)
#   libraries/io/github/cruciblemc/launchwrapper/...
#   mods/+unimixins-all-1.7.10-*.jar                     (API do Mixin, MixinExtras e GTNHMixins)
#
# O que faz (nada e baixado da rede):
#   1. tira do server.jar o mapeamento notch->SRG que o proprio FML usa em tempo de
#      execucao (deobfuscation_data-1.7.10.lzma);
#   2. junta as classes do Minecraft (jar vanilla) com o server.jar e renomeia tudo para
#      nomes SRG com o SpecialSource (que ja vem nas libraries do servidor);
#   3. compila com javac do JDK 8 (bytecode versao 52) contra essa visao SRG. O codigo do
#      mod escreve todo alvo de mixin e todo membro do Minecraft com nome SRG e
#      remap = false; por isso nao ha refmap nem reobfuscacao;
#   4. empacota o jar e confere a versao do bytecode.
#
# Variaveis opcionais:
#   JAVA8_HOME   JDK 8 (padrao: /usr/lib/jvm/java-8-openjdk-amd64)
set -euo pipefail

SERVER="${1:?informe a pasta do servidor (com server.jar, libraries/ e mods/)}"
OUT="${2:?informe a pasta de saida do jar}"
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER="$(cd "$SERVER" && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

VERSAO="$(sed -n 's/.*String VERSAO = "\([^"]*\)".*/\1/p' "$AQUI/src/main/java/hoshiestar/medidor/Medidor.java")"
[[ -n "$VERSAO" ]] || { echo "ERRO: nao achei VERSAO em Medidor.java" >&2; exit 1; }

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
command -v xz >/dev/null || { echo "ERRO: falta o programa xz (para ler o .lzma)" >&2; exit 1; }
command -v unzip >/dev/null || { echo "ERRO: falta o programa unzip" >&2; exit 1; }

L="$SERVER/libraries"
achar() { # achar <padrao-glob> : primeiro arquivo que casar, ou erro
  local f
  for f in $1; do [[ -f "$f" ]] && { echo "$f"; return 0; }; done
  echo "ERRO: nao achei $1" >&2; return 1
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
LAUNCHWRAPPER="$(achar "$L/io/github/cruciblemc/launchwrapper/*/launchwrapper-*.jar")"
UNIMIXINS="$(achar "$SERVER/mods/+unimixins-all-1.7.10-*.jar")"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/medidor-build.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
echo "[build] pasta temporaria: $WORK"

# 1. mapeamento notch -> SRG (o mesmo que o FML usa no boot)
unzip -p "$SERVER_JAR" deobfuscation_data-1.7.10.lzma | xz -d --format=lzma > "$WORK/notch-srg-full.srg"
grep -v '^PK:' "$WORK/notch-srg-full.srg" > "$WORK/notch-srg.srg"
echo "[build] mapeamento: $(grep -c '^CL:' "$WORK/notch-srg.srg") classes, $(grep -c '^MD:' "$WORK/notch-srg.srg") metodos"

# 2. classes do Minecraft (vanilla, nomes notch) + server.jar (Forge/FML/Crucible), renomeadas para SRG
mkdir -p "$WORK/junto"
( cd "$WORK/junto"
  # vanilla: so as classes do jogo (raiz e net/minecraft), sem as bibliotecas embutidas
  unzip -q "$VANILLA" '*.class' -x 'com/*' 'io/*' 'org/*' 'gnu/*' 'javax/*'
  unzip -qo "$SERVER_JAR" '*.class' )
"${J8[@]}" "$JAR" cf "$WORK/junto.jar" -C "$WORK/junto" .
SS_CP="$SS:$ASM:$ASM_COMMONS:$ASM_TREE:$ASM_ANALYSIS:$JOPT:$GUAVA:$OPENCSV:$LANG3"
"${J8[@]}" "$JAVA" -cp "$SS_CP" net.md_5.specialsource.SpecialSource \
  --in-jar "$WORK/junto.jar" --out-jar "$WORK/servidor-srg.jar" --srg-in "$WORK/notch-srg.srg" -q
unzip -l "$WORK/servidor-srg.jar" > "$WORK/servidor-srg.lista"
grep -q 'net/minecraft/world/World.class' "$WORK/servidor-srg.lista" \
  || { echo "ERRO: a visao SRG nao tem net/minecraft/world/World" >&2; exit 1; }

# 3. compilar (JDK 8, -proc:none: o jar do UniMixins traz processadores de anotacao que nao queremos)
mkdir -p "$WORK/classes"
find "$AQUI/src/main/java" -name '*.java' | sort > "$WORK/fontes.txt"
CP="$WORK/servidor-srg.jar:$LAUNCHWRAPPER:$UNIMIXINS:$VANILLA"
"${J8[@]}" "$JAVAC" -encoding UTF-8 -source 1.8 -target 1.8 -proc:none -Xlint:-options -nowarn \
  -cp "$CP" -d "$WORK/classes" @"$WORK/fontes.txt"
cp "$AQUI/src/main/resources/"* "$WORK/classes/"

# 4. empacotar
cat > "$WORK/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
FMLCorePlugin: hoshiestar.medidor.core.MedidorCore
FMLCorePluginContainsFMLMod: true
Implementation-Title: Hoshiestar-Medidor
Implementation-Version: $VERSAO
EOF
NOME="Hoshiestar-Medidor-$VERSAO-serveronly.jar"
rm -f "$OUT/$NOME"
"${J8[@]}" "$JAR" cfm "$OUT/$NOME" "$WORK/MANIFEST.MF" -C "$WORK/classes" .

# conferencia: todo .class com versao 52 (Java 8)
( cd "$WORK/classes"
  ruins=0
  while IFS= read -r c; do
    v="$("${J8[@]}" "$JAVAP" -v "$c" | sed -n 's/.*major version: \([0-9]*\).*/\1/p')"
    [[ "$v" == "52" ]] || { echo "ERRO: $c tem versao $v" >&2; ruins=1; }
  done < <(find . -name '*.class')
  exit $ruins )
echo "[build] classes: $(find "$WORK/classes" -name '*.class' | wc -l), todas com bytecode versao 52 (Java 8)"
echo "[build] jar: $OUT/$NOME"
sha256sum "$OUT/$NOME"
