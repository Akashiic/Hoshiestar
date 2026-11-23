#!/usr/bin/env bash
# Hoshiestar - teste isolado da correcao do Mekanism (recurso 1.C, matriz de inducao) com as classes
# REAIS: Mekanism e IC2 do pacote (pasta mods/, so pelo classpath), Minecraft vanilla (pasta
# libraries/) e as NOSSAS classes tiradas do server.jar construido (os bytes que vao para o servidor),
# com os nomes do Minecraft traduzidos como o FML faz ao carregar (mapa do proprio jar).
#
# Chamado pelo servidor/test.sh quando HOSHIE_MODS esta definido:
#   HOSHIE_LIBS=<libraries/> HOSHIE_MODS=<mods/> servidor/test.sh <workspace>
# Uso direto (as mesmas variaveis):
#   src/test/mods/mekanism.bash <workspace> <pasta de saida> <classpath base do test.sh> <java> [<java>...]
# Precisa do jar construido com --mods ANTES (servidor/build.sh --rapido <workspace> --mods <mods/>):
# se alguma fonte nossa for mais nova que o jar, o teste para (testaria bytes velhos).
# MEK_RAPIDO=1: menos sementes no diferencial (para iterar). MEK_SEM_MUTACAO=1: pula a mutacao.
set -euo pipefail

W="$(cd "$1" && pwd)"
OUT="$2/mods-mekanism"
CPB="$3"
shift 3
JAVAS=("$@")
J8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
: "${HOSHIE_MODS:?defina HOSHIE_MODS=<pasta mods/ de um servidor de teste>}"
: "${HOSHIE_LIBS:?defina HOSHIE_LIBS=<pasta libraries/ de um servidor>}"

um() { # primeiro arquivo que casa; erro se nenhum
  local achado
  achado="$(find "$1" -name "$2" -type f 2>/dev/null | sort | head -1 || true)"
  [[ -n "$achado" ]] || { echo "[mekanism] ERRO: $2 nao encontrado em $1" >&2; exit 2; }
  echo "$achado"
}

JAR="$(ls -t "$W"/build/distributions/Hoshiestar-*-server.jar 2>/dev/null | head -1 || true)"
[[ -n "$JAR" ]] || { echo "[mekanism] ERRO: jar construido nao encontrado; rode servidor/build.sh --rapido $W --mods <mods/>" >&2; exit 2; }
NOVOS="$(find "$W/src/main/java/hoshiestar" "$W/src/mods/java" -name '*.java' -newer "$JAR" 2>/dev/null | head -3 || true)"
[[ -z "$NOVOS" ]] || { echo "[mekanism] ERRO: o jar $JAR e mais velho que fontes nossas (ex.: $NOVOS); construa de novo" >&2; exit 2; }
MEK="$(um "$HOSHIE_MODS" 'Mekanism-1.7.10-*.jar')"
IC2="$(um "$HOSHIE_MODS" 'industrialcraft-2-*.jar')"
VAN="$(find "$HOSHIE_LIBS" -path '*net/minecraft/server/1.7.10/server-1.7.10.jar' -type f | head -1)"
[[ -n "$VAN" ]] || { echo "[mekanism] ERRO: server-1.7.10.jar (vanilla) nao encontrado em $HOSHIE_LIBS" >&2; exit 2; }
LZMA="$(um "$HOSHIE_LIBS" 'lzma-0.0.1.jar')"
ASMC="$(um "$HOSHIE_LIBS" 'asm-commons-9.9.1.jar')"

echo "[mekanism] jar construido: $JAR"
echo "[mekanism] mods: $(basename "$MEK"), $(basename "$IC2"); vanilla: $VAN"
rm -rf "$OUT"
mkdir -p "$OUT/ferramenta" "$OUT/srg" "$OUT/classes"

# 1. tradutor notch -> SRG (o mesmo mapa que o FML usa, de dentro do jar construido)
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CPB:$ASMC" -d "$OUT/ferramenta" \
  "$W/src/test/mods/java/hoshiestar/teste/mods/RemapeadorSrg.java"
"$J8/bin/java" -cp "$OUT/ferramenta:$CPB:$ASMC:$LZMA" hoshiestar.teste.mods.RemapeadorSrg "$JAR" "$OUT/srg" "$JAR" "$VAN"
SRV_SRG="$OUT/srg/$(basename "${JAR%.jar}")-srg.jar"
MC_SRG="$OUT/srg/$(basename "${VAN%.jar}")-srg.jar"

# 2. testes (compilados contra o Minecraft SRG, o Mekanism e as nossas classes do jar)
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 \
  -cp "$MC_SRG:$SRV_SRG:$MEK:$IC2:$VAN:$CPB:$ASMC:$LZMA" -d "$OUT/classes" \
  $(find "$W/src/test/mods/java" -name '*.java' | sort) "$W/src/test/java/hoshiestar/teste/Verifica.java"

# 3. roda em cada JDK; no primeiro (o JDK 8), tambem a mutacao no codigo-fonte da logica (TesteMutacao:
#    cada protecao tirada do MatrizInducao.java do workspace, compilada com os nomes de tempo de execucao)
falhas=0
primeiro=1
for java in "${JAVAS[@]}"; do
  MUT=()
  if [[ $primeiro -eq 1 && -z "${MEK_SEM_MUTACAO:-}" ]]; then
    MUT=("fonte=$W/src/mods/java/hoshiestar/mekanism/matriz/MatrizInducao.java" "cpmut=$MC_SRG:$SRV_SRG:$MEK:$IC2:$VAN:$CPB:$ASMC")
  fi
  primeiro=0
  if "$java" -cp "$OUT/classes:$SRV_SRG:$CPB:$ASMC:$LZMA" hoshiestar.teste.mekanism.TesteMekanism \
      "$JAR" "$OUT/srg" "$MEK" "$IC2" "$VAN" "$OUT/classes" ${MEK_RAPIDO:+rapido} "${MUT[@]}"; then :; else
    falhas=$((falhas + 1)); echo "[mekanism] FALHOU em $java"
  fi
done
[[ $falhas -eq 0 ]]
