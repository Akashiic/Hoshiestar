#!/usr/bin/env bash
# Hoshiestar - testes isolados (rodam fora do jogo, sem servidor).
#
# Uso:
#   servidor/test.sh <workspace-do-fork>
#
# <workspace-do-fork> e a arvore do Crucible com o servidor/ aplicado (a que o build.sh prepara).
# As fontes vem dela: src/main/java (so as partes do Hoshiestar que nao dependem do Minecraft, mais o
# pacote de config do Crucible), src/test/java e src/test/amostras.
#
# O que roda:
#   1. compila a classe de exemplo (src/test/amostras) tres vezes: Java 6, 7 e 8;
#   2. compila as fontes e os testes com o JDK 8;
#   3. roda cada teste (classe hoshiestar.teste.Teste*) no JDK 8 e no JDK 21.
# Saida 0 = tudo passou.
#
# Variaveis: JAVA8_HOME (padrao /usr/lib/jvm/java-8-openjdk-amd64), JAVA21_HOME (padrao
# /usr/lib/jvm/java-21-openjdk-amd64), HOSHIE_LIBS (pasta libraries/ de um servidor, onde procurar os
# jars antes do cache do Gradle), HOSHIE_SAIDA (pasta de trabalho; padrao: uma pasta temporaria nova).
set -euo pipefail

W="$(cd "${1:?informe o workspace do fork (arvore do Crucible com o servidor/ aplicado)}" && pwd)"
J8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
J21="${JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
OUT="${HOSHIE_SAIDA:-$(mktemp -d)}"
mkdir -p "$OUT"
export LC_ALL=C.UTF-8

[[ -x "$J8/bin/javac" ]] || { echo "ERRO: JDK 8 nao encontrado em $J8 (defina JAVA8_HOME)" >&2; exit 2; }
[[ -d "$W/src/main/java/hoshiestar" ]] || { echo "ERRO: $W nao tem src/main/java/hoshiestar" >&2; exit 2; }

# acha um jar pelo nome de arquivo: primeiro em HOSHIE_LIBS, depois no cache do Gradle
jar_de() {
  local nome="$1" achado=""
  if [[ -n "${HOSHIE_LIBS:-}" && -d "${HOSHIE_LIBS}" ]]; then
    achado="$(find "$HOSHIE_LIBS" -name "$nome" -type f 2>/dev/null | head -1 || true)"
  fi
  if [[ -z "$achado" && -d "$HOME/.gradle/caches/modules-2/files-2.1" ]]; then
    achado="$(find "$HOME/.gradle/caches/modules-2/files-2.1" -name "$nome" -type f 2>/dev/null | head -1 || true)"
  fi
  [[ -n "$achado" ]] || { echo "ERRO: jar $nome nao encontrado (rode o build.sh antes, ou defina HOSHIE_LIBS)" >&2; exit 2; }
  echo "$achado"
}
CP=""
for j in asm-9.9.1.jar asm-tree-9.9.1.jar asm-analysis-9.9.1.jar asm-util-9.9.1.jar \
         log4j-api-2.0-beta9.jar log4j-core-2.0-beta9.jar RetroFuturaBootstrap-1.1.0.jar; do
  CP="$CP:$(jar_de "$j")"
done
# snakeyaml: o servidor roda com a 1.9 (Class-Path do jar); o cache do Gradle so costuma ter a 1.21
# (a que o projeto do Crucible usa para compilar). Prefira HOSHIE_LIBS=<pasta libraries/ de um servidor>.
if YAML="$(jar_de snakeyaml-1.9.jar 2>/dev/null)"; then :; else
  YAML="$(jar_de snakeyaml-1.21.jar)"
  echo "[test] AVISO: snakeyaml 1.9 (a do servidor) nao encontrada; usando $(basename "$YAML"). Defina HOSHIE_LIBS."
fi
CP="${CP#:}:$YAML"

echo "[test] workspace: $W"
echo "[test] saida: $OUT"

# 1. amostras para Java 6, 7 e 8
for v in 6 7 8; do
  rm -rf "$OUT/amostras-$v"; mkdir -p "$OUT/amostras-$v"
  "$J8/bin/javac" -nowarn -Xlint:-options -source 1.$v -target 1.$v -encoding UTF-8 -d "$OUT/amostras-$v" \
    $(find "$W/src/test/amostras" -name '*.java' | sort)
done

# 2. fontes (sem o que depende do Minecraft/Forge/Bukkit) e testes
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
FONTES=$( (cd "$W/src/main/java" && find io/github/crucible/util/config -name '*.java'
          ls hoshiestar/Hoshiestar.java hoshiestar/api/*.java hoshiestar/config/*.java \
             hoshiestar/embutidos/*.java hoshiestar/embutidos/asm/*.java) | sort | sed "s|^|$W/src/main/java/|")
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CP:$OUT/amostras-8" -d "$OUT/classes" \
  $FONTES $(find "$W/src/test/java" -name '*.java' | sort)

# 3. roda
falhas=0
rodar() {
  local java="$1" classe="$2"; shift 2
  if "$java" -cp "$OUT/classes:$CP" "$classe" "$@"; then :; else falhas=$((falhas + 1)); echo "[test] FALHOU: $classe ($java)"; fi
}
JDKS=("$J8/bin/java")
if [[ -x "$J21/bin/java" ]]; then JDKS+=("$J21/bin/java"); else echo "[test] AVISO: JDK 21 nao encontrado em $J21; rodando so no 8"; fi
for java in "${JDKS[@]}"; do
  rodar "$java" hoshiestar.teste.TesteAsm 6="$OUT/amostras-6" 7="$OUT/amostras-7" 8="$OUT/amostras-8"
  rodar "$java" hoshiestar.teste.TesteTrava "$OUT/amostras-8"
  rodar "$java" hoshiestar.teste.TesteLimitador
  rodar "$java" hoshiestar.teste.TesteCampoPrivado
  rm -rf "$OUT/config"; rodar "$java" hoshiestar.teste.TesteConfig "$OUT/config"
done
if [[ $falhas -eq 0 ]]; then echo "[test] TODOS OS TESTES PASSARAM"; else echo "[test] $falhas teste(s) falharam"; exit 1; fi
