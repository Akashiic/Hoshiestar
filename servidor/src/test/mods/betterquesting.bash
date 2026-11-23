#!/usr/bin/env bash
# Hoshiestar - teste isolado da correcao do BetterQuesting (recurso 1.D, filtro de inventario) com as
# classes REAIS do BetterQuesting (jar da pasta mods/, so pelo classpath) e as NOSSAS classes tiradas do
# server.jar construido (os bytes que vao para o servidor), com os nomes do Minecraft traduzidos como o
# FML faz ao carregar (mapa do proprio jar).
#  - transformador: classe real bq_standard.handlers.EventHandler + Minecraft vanilla (pasta libraries/);
#  - diferencial, casos e mutacao: classes reais do BQ + Minecraft/Forge de mentira (src/test/mods/bq/stubs,
#    so os membros que o BQ e o filtro usam, com nomes SRG), como no harness da conferencia 1.D.
#
# Chamado pelo servidor/test.sh quando HOSHIE_MODS esta definido:
#   HOSHIE_LIBS=<libraries/> HOSHIE_MODS=<mods/> servidor/test.sh <workspace>
# Uso direto (as mesmas variaveis):
#   src/test/mods/betterquesting.bash <workspace> <pasta de saida> <classpath base do test.sh> <java> [<java>...]
# Precisa do jar construido com --mods ANTES (servidor/build.sh --rapido <workspace> --mods <mods/>):
# se alguma fonte nossa for mais nova que o jar, o teste para (testaria bytes velhos).
# BQ_RAPIDO=1: 20 mil cenarios no lugar de 240 mil (para iterar). BQ_SEM_MUTACAO=1: pula a mutacao.
set -euo pipefail

W="$(cd "$1" && pwd)"
OUT="$2/mods-betterquesting"
CPB="$3"
shift 3
JAVAS=("$@")
J8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
: "${HOSHIE_MODS:?defina HOSHIE_MODS=<pasta mods/ de um servidor de teste>}"
: "${HOSHIE_LIBS:?defina HOSHIE_LIBS=<pasta libraries/ de um servidor>}"

um() { # primeiro arquivo que casa; erro se nenhum
  local achado
  achado="$(find "$1" -name "$2" -type f 2>/dev/null | sort | head -1 || true)"
  [[ -n "$achado" ]] || { echo "[betterquesting] ERRO: $2 nao encontrado em $1" >&2; exit 2; }
  echo "$achado"
}

JAR="$(ls -t "$W"/build/distributions/Hoshiestar-*-server.jar 2>/dev/null | head -1 || true)"
[[ -n "$JAR" ]] || { echo "[betterquesting] ERRO: jar construido nao encontrado; rode servidor/build.sh --rapido $W --mods <mods/>" >&2; exit 2; }
NOVOS="$(find "$W/src/main/java/hoshiestar" "$W/src/mods/java" -name '*.java' -newer "$JAR" 2>/dev/null | head -3 || true)"
[[ -z "$NOVOS" ]] || { echo "[betterquesting] ERRO: o jar $JAR e mais velho que fontes nossas (ex.: $NOVOS); construa de novo" >&2; exit 2; }
BQ="$(um "$HOSHIE_MODS" 'BetterQuesting-3.8.79-GTNH.jar')"
VAN="$(find "$HOSHIE_LIBS" -path '*net/minecraft/server/1.7.10/server-1.7.10.jar' -type f | head -1)"
[[ -n "$VAN" ]] || { echo "[betterquesting] ERRO: server-1.7.10.jar (vanilla) nao encontrado em $HOSHIE_LIBS" >&2; exit 2; }
LZMA="$(um "$HOSHIE_LIBS" 'lzma-0.0.1.jar')"
ASMC="$(um "$HOSHIE_LIBS" 'asm-commons-9.9.1.jar')"

echo "[betterquesting] jar construido: $JAR"
echo "[betterquesting] mod: $(basename "$BQ"); vanilla: $VAN"
rm -rf "$OUT"
mkdir -p "$OUT/ferramenta" "$OUT/srg" "$OUT/stubs" "$OUT/classes"

# 1. tradutor notch -> SRG (o mesmo mapa que o FML usa, de dentro do jar construido)
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CPB:$ASMC" -d "$OUT/ferramenta" \
  "$W/src/test/mods/java/hoshiestar/teste/mods/RemapeadorSrg.java"
"$J8/bin/java" -cp "$OUT/ferramenta:$CPB:$ASMC:$LZMA" hoshiestar.teste.mods.RemapeadorSrg "$JAR" "$OUT/srg" "$JAR" "$VAN"
SRV_SRG="$OUT/srg/$(basename "${JAR%.jar}")-srg.jar"
MC_SRG="$OUT/srg/$(basename "${VAN%.jar}")-srg.jar"

# 2. stubs do Minecraft/Forge (so para o diferencial; ficam na frente do servidor SRG no classpath)
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -d "$OUT/stubs" \
  $(find "$W/src/test/mods/bq/stubs" -name '*.java' | sort)

# 3. testes: compilados contra os stubs (na frente), o servidor SRG e o BQ; a infraestrutura comum dos
#    testes com mods (CarregadorMods, RemapeadorSrg) e o Verifica vem dos mesmos fontes do teste do Mekanism
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 \
  -cp "$OUT/stubs:$SRV_SRG:$BQ:$CPB:$ASMC:$LZMA" -d "$OUT/classes" \
  $(find "$W/src/test/mods/bq/java" -name '*.java' | sort) \
  "$W/src/test/mods/java/hoshiestar/teste/mods/CarregadorMods.java" \
  "$W/src/test/mods/java/hoshiestar/teste/mods/RemapeadorSrg.java" \
  "$W/src/test/java/hoshiestar/teste/Verifica.java"

# 4. roda em cada JDK; no primeiro (o JDK 8), tambem a mutacao no codigo-fonte do filtro
falhas=0
primeiro=1
for java in "${JAVAS[@]}"; do
  MUT=()
  if [[ $primeiro -eq 1 && -z "${BQ_SEM_MUTACAO:-}" ]]; then
    MUT=("fonte=$W/src/mods/java/hoshiestar/betterquesting/filtro" "cpmut=$MC_SRG:$SRV_SRG:$BQ:$VAN:$CPB:$ASMC")
  fi
  primeiro=0
  if "$java" -cp "$OUT/classes:$SRV_SRG:$CPB:$ASMC:$LZMA" hoshiestar.teste.betterquesting.TesteBetterQuesting \
      "$JAR" "$OUT/srg" "$BQ" "$VAN" "$OUT/classes" "$OUT/stubs" ${BQ_RAPIDO:+rapido} "${MUT[@]}"; then :; else
    falhas=$((falhas + 1)); echo "[betterquesting] FALHOU em $java"
  fi
done
[[ $falhas -eq 0 ]]
