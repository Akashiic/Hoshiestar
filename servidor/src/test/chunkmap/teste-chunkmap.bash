#!/usr/bin/env bash
# Hoshiestar - testes isolados do recurso chunkmap (cache do thermos.wrapper.ChunkBlockHashMap).
#
# Uso (um comando):
#   HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/chunkmap/teste-chunkmap.bash <workspace> [--rapido] [--bench]
#
# Por que um script separado do servidor/test.sh: estes testes compilam a classe REAL do fork
# (src/main/java/thermos/wrapper/ChunkBlockHashMap.java, com o patch) e uma copia da ORIGINAL do
# upstream, e as duas precisam do koloboke (biblioteca do servidor) e da classe Chunk (aqui um
# substituto minimo em stubs/). O test.sh compila tudo de src/test/java sem essas bibliotecas, entao
# estes fontes ficam em src/test/chunkmap/.
#
# O que roda (JDK 8 e JDK 21):
#   0. confere que a copia da original e o arquivo do upstream (commit do UPSTREAM) menos a linha do pacote;
#   1. TesteChunkmapConfig: chaves chunkmap.* no hoshiestar.yml e registro;
#   2. TesteChunkmapDiferencial desligado | ligado | conferir: original x nova, milhoes de operacoes;
#   3. TesteChunkmapCorrida: replay da corrida do conferente, estresse com leitores de outras threads,
#      desligamento por seguranca, erro interno, modo conferir, mapa sem thread do servidor;
#   4. TesteChunkmapAdversario (testes da revisao independente: retencao depois de desligar, cache de
#      4 entradas depois de desligar, leitor de fora com casa envenenada, estouro de pilha), no modo
#      padrao da JVM e com -XX:-TieredCompilation (o modo em que o estouro de pilha mostrava o defeito).
#   --rapido: menos operacoes (para conferir rapido); --bench: microbenchmark no JDK 21 (indicacao).
# Saida 0 = tudo passou.
set -euo pipefail

W="$(cd "${1:?informe o workspace do fork}" && pwd)"
shift
RAPIDO=0
BENCH=0
for a in "$@"; do
  case "$a" in
    --rapido) RAPIDO=1 ;;
    --bench) BENCH=1 ;;
    *) echo "opcao desconhecida: $a" >&2; exit 2 ;;
  esac
done
J8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
J21="${JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
OUT="${HOSHIE_SAIDA:-$(mktemp -d)}"
mkdir -p "$OUT"
export LC_ALL=C.UTF-8
T="$W/src/test/chunkmap"

jar_de() {
  local nome="$1" achado=""
  if [[ -n "${HOSHIE_LIBS:-}" && -d "${HOSHIE_LIBS}" ]]; then
    achado="$(find "$HOSHIE_LIBS" -name "$nome" -type f 2>/dev/null | head -1 || true)"
  fi
  if [[ -z "$achado" && -d "$HOME/.gradle/caches/modules-2/files-2.1" ]]; then
    achado="$(find "$HOME/.gradle/caches/modules-2/files-2.1" -name "$nome" -type f 2>/dev/null | head -1 || true)"
  fi
  [[ -n "$achado" ]] || { echo "ERRO: jar $nome nao encontrado (defina HOSHIE_LIBS=<pasta libraries/ de um servidor>)" >&2; exit 2; }
  echo "$achado"
}
CP=""
for j in asm-9.9.1.jar asm-tree-9.9.1.jar asm-analysis-9.9.1.jar asm-util-9.9.1.jar \
         log4j-api-2.0-beta9.jar log4j-core-2.0-beta9.jar RetroFuturaBootstrap-1.1.0.jar \
         koloboke-api-jdk8-1.0.0.jar koloboke-impl-jdk8-1.0.0.jar koloboke-impl-common-jdk8-1.0.0.jar; do
  CP="$CP:$(jar_de "$j")"
done
if YAML="$(jar_de snakeyaml-1.9.jar 2>/dev/null)"; then :; else YAML="$(jar_de snakeyaml-1.21.jar)"; fi
CP="${CP#:}:$YAML"

echo "[chunkmap] workspace: $W"
echo "[chunkmap] saida: $OUT"

# 0. a copia da original e o upstream (so a linha do pacote muda)
COPIA="$T/java/hoshiestar/teste/chunkmap/original/ChunkBlockHashMap.java"
COMMIT_UPSTREAM=67b530340b080f94079793a15e5588c2dd6fc94b
if git -C "$W" cat-file -e "$COMMIT_UPSTREAM:src/main/java/thermos/wrapper/ChunkBlockHashMap.java" 2>/dev/null; then
  if diff <(git -C "$W" show "$COMMIT_UPSTREAM:src/main/java/thermos/wrapper/ChunkBlockHashMap.java" | tail -n +2) <(tail -n +2 "$COPIA") >/dev/null; then
    echo "[chunkmap] ok: a copia da original e identica ao upstream $COMMIT_UPSTREAM (menos a linha do pacote)"
  else
    echo "[chunkmap] FALHOU: a copia da original difere do upstream"; exit 1
  fi
else
  echo "[chunkmap] AVISO: commit do upstream nao esta no git do workspace; copia da original nao conferida"
fi

# compila: a classe REAL do fork + o que ela usa do Hoshiestar + a original + o substituto do Chunk + testes
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
FONTES=$( (cd "$W/src/main/java" && find io/github/crucible/util/config -name '*.java'
          ls thermos/wrapper/ChunkBlockHashMap.java hoshiestar/Hoshiestar.java hoshiestar/api/*.java hoshiestar/config/*.java \
             hoshiestar/embutidos/*.java hoshiestar/embutidos/asm/*.java hoshiestar/chunkmap/*.java) | sort | sed "s|^|$W/src/main/java/|")
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CP" -d "$OUT/classes" \
  $FONTES "$W/src/test/java/hoshiestar/teste/Verifica.java" \
  $(find "$T/stubs" "$T/java" -name '*.java' | sort)

if [[ $RAPIDO -eq 1 ]]; then OPS=200000; SEM=1; ITER=300000; ROD=1; else OPS=1000000; SEM=4; ITER=3000000; ROD=3; fi

falhas=0
rodar() {
  local java="$1" opc=""; shift
  if [[ "$1" == -XX:* ]]; then opc="$1"; shift; fi
  local classe="$1"; shift
  if "$java" $opc -cp "$OUT/classes:$CP" "$classe" "$@"; then :; else falhas=$((falhas + 1)); echo "[chunkmap] FALHOU: $opc $classe $* ($java)"; fi
}
JDKS=("$J8/bin/java")
if [[ -x "$J21/bin/java" ]]; then JDKS+=("$J21/bin/java"); else echo "[chunkmap] AVISO: JDK 21 nao encontrado; rodando so no 8"; fi
for java in "${JDKS[@]}"; do
  rm -rf "$OUT/config"; rodar "$java" hoshiestar.teste.chunkmap.TesteChunkmapConfig "$OUT/config"
  for modo in desligado ligado conferir; do
    rodar "$java" hoshiestar.teste.chunkmap.TesteChunkmapDiferencial "$modo" "$OPS" "$SEM"
  done
  rodar "$java" hoshiestar.teste.chunkmap.TesteChunkmapCorrida "$ITER"
  rodar "$java" hoshiestar.teste.chunkmap.TesteChunkmapAdversario "$ROD"
  # sem compilacao em camadas, sempre 3 rodadas do estouro de pilha, tambem no --rapido: com 1 rodada o JIT pode mudar o
  # tamanho dos quadros depois da calibracao e nenhum estouro cair dentro de remove/put/get (visto no JDK 21 na juncao)
  rodar "$java" -XX:-TieredCompilation hoshiestar.teste.chunkmap.TesteChunkmapAdversario 3
done
if [[ $BENCH -eq 1 && -x "$J21/bin/java" ]]; then
  echo "[chunkmap] microbenchmark (JDK 21; so indicacao, nao e medicao de servidor)"
  for impl in original desligado ligado; do
    "$J21/bin/java" -Xms1g -Xmx1g -cp "$OUT/classes:$CP" hoshiestar.teste.chunkmap.BenchChunkmap "$impl"
  done
fi
if [[ $falhas -eq 0 ]]; then echo "[chunkmap] TODOS OS TESTES PASSARAM"; else echo "[chunkmap] $falhas teste(s) falharam"; exit 1; fi
