#!/usr/bin/env bash
# Hoshiestar - testes isolados do recurso parada (parada por SIGTERM sem crash e sem salvamento duplo, D-24).
#
# Uso (um comando):
#   HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/parada/teste-parada.bash <workspace> [--rapido]
#
# Por que um script separado do servidor/test.sh: o test.sh so compila uma lista fixa de pacotes de
# src/main/java, e o pacote hoshiestar/parada nao esta nela. Extensao .bash porque o .gitignore do
# upstream ignora *.sh.
#
# O que roda (JDK 8 e JDK 21):
#   1. TesteParadaConfig: chaves parada.* no hoshiestar.yml e registro;
#   2. TesteParadaMaquina: cenarios com threads de verdade (stop normal, SIGTERM saudavel, controle com o
#      interruptor desligado, servidor travado, excecao no salvamento cedo e tarde, outra thread salvando,
#      gancho duas vezes, limites T2 e folga, avisos que acordam quem espera, SIGTERM no boot, reentrada,
#      interrupcao, fachada desligada);
#   3. TesteParadaJvm: JVMs filhas de verdade, com gancho de desligamento real e System.exit (SIGTERM
#      equivalente, stop normal, System.exit chamado pela propria thread do servidor, saida pedida pelo
#      vigia de travamento com o servidor travado, e salvamento passando de T2);
#   4. TesteParadaEstresse: "stop" e SIGTERM ao mesmo tempo, 4000 repeticoes com atrasos aleatorios
#      (--rapido: 600);
#   5. TesteParadaAdversario (do revisor): o mesmo com uma terceira thread, um segundo gancho e excecoes,
#      1500 repeticoes por rodada (--rapido: 300).
# Saida 0 = tudo passou.
set -euo pipefail

W="$(cd "${1:?informe o workspace do fork}" && pwd)"
shift
REPS=4000
REPS_ADV=1500
for a in "$@"; do
  case "$a" in
    --rapido) REPS=600; REPS_ADV=300 ;;
    *) echo "opcao desconhecida: $a" >&2; exit 2 ;;
  esac
done
J8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
J21="${JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
OUT="${HOSHIE_SAIDA:-$(mktemp -d)}"
mkdir -p "$OUT"
export LC_ALL=C.UTF-8

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
         log4j-api-2.0-beta9.jar log4j-core-2.0-beta9.jar RetroFuturaBootstrap-1.1.0.jar; do
  CP="$CP:$(jar_de "$j")"
done
if YAML="$(jar_de snakeyaml-1.9.jar 2>/dev/null)"; then :; else YAML="$(jar_de snakeyaml-1.21.jar)"; fi
CP="${CP#:}:$YAML"

echo "[parada] workspace: $W"
echo "[parada] saida: $OUT"

# compila: as mesmas fontes do test.sh + as duas classes do recurso que nao dependem do Minecraft + os testes
rm -rf "$OUT/parada-classes"; mkdir -p "$OUT/parada-classes"
FONTES=$( (cd "$W/src/main/java" && find io/github/crucible/util/config -name '*.java'
          ls hoshiestar/Hoshiestar.java hoshiestar/api/*.java hoshiestar/config/*.java \
             hoshiestar/embutidos/*.java hoshiestar/embutidos/asm/*.java \
             hoshiestar/parada/MaquinaDeParada.java hoshiestar/parada/Parada.java) | sort | sed "s|^|$W/src/main/java/|")
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CP" -d "$OUT/parada-classes" \
  $FONTES "$W/src/test/java/hoshiestar/teste/Verifica.java" $(find "$W/src/test/parada/java" -name '*.java' | sort)

falhas=0
rodar() {
  local java="$1" classe="$2"; shift 2
  if "$java" -cp "$OUT/parada-classes:$CP" "$classe" "$@"; then :; else falhas=$((falhas + 1)); echo "[parada] FALHOU: $classe ($java)"; fi
}
JDKS=("$J8/bin/java")
if [[ -x "$J21/bin/java" ]]; then JDKS+=("$J21/bin/java"); else echo "[parada] AVISO: JDK 21 nao encontrado em $J21; rodando so no 8"; fi
for java in "${JDKS[@]}"; do
  rm -rf "$OUT/parada-config"; rodar "$java" hoshiestar.teste.parada.TesteParadaConfig "$OUT/parada-config"
  rodar "$java" hoshiestar.teste.parada.TesteParadaMaquina
  rodar "$java" hoshiestar.teste.parada.TesteParadaJvm
  rodar "$java" hoshiestar.teste.parada.TesteParadaEstresse "$REPS"
  rodar "$java" hoshiestar.teste.parada.TesteParadaAdversario "$REPS_ADV"
done
if [[ $falhas -eq 0 ]]; then echo "[parada] TODOS OS TESTES PASSARAM"; else echo "[parada] $falhas teste(s) falharam"; exit 1; fi
