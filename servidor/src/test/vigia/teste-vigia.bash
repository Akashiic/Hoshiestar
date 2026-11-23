#!/usr/bin/env bash
# Hoshiestar - testes isolados do recurso vigia (item 1.H: vigia de tick lento).
#
# Uso (um comando):
#   HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/vigia/teste-vigia.bash <workspace>
#
# Por que um script separado do servidor/test.sh: o test.sh so compila uma lista fixa de pacotes de
# src/main/java, e o pacote hoshiestar/vigia nao esta nela. Extensao .bash porque o .gitignore do
# upstream ignora *.sh.
#
# O que roda (JDK 8 e JDK 21), sempre com o MESMO codigo que roda no servidor (Marcas, NucleoVigia,
# LimiteDeRelatorios, IndiceDeJars, CamposNumericos, SaidaEmArquivo, Vigia), sem Minecraft, com uma
# "thread do servidor" falsa de verdade que faz ticks (Relogio.ticks++, Marcas.inicioTick/fimTick):
#   1. TesteVigiaConfig: chaves vigia.* no hoshiestar.yml, registro, valores fora da faixa, e uma JVM
#      filha com vigia.ligado: false (as marcacoes nao escrevem nada);
#   2. TesteVigiaIndice: indice "pacote -> jar" com jars de teste (prefixo mais longo, pacote repetido);
#   3. TesteVigiaNucleo: tick normal sem relatorio; tick de 2,5 s com um relatorio e a pilha certa;
#      travamento longo com relatorios espacados pelo limitador; limitador consultado antes de amostrar;
#      parada durante um tick lento em menos de 1 s; privacidade (pacote com String); GC; arquivo e
#      pasta de reserva; dono por indice e por nome de metodo de mixin;
#   4. BenchVigia: custo do caminho quente em ns por marcacao (indicacao, nao medicao de servidor).
# Saida 0 = tudo passou.
set -euo pipefail

W="$(cd "${1:?informe o workspace do fork}" && pwd)"
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

echo "[vigia] workspace: $W"
echo "[vigia] saida: $OUT"

rm -rf "$OUT/vigia-classes"; mkdir -p "$OUT/vigia-classes"
FONTES=$( (cd "$W/src/main/java" && find io/github/crucible/util/config -name '*.java'
          ls hoshiestar/Hoshiestar.java hoshiestar/api/*.java hoshiestar/config/*.java \
             hoshiestar/embutidos/*.java hoshiestar/embutidos/asm/*.java \
             hoshiestar/vigia/Marcas.java hoshiestar/vigia/NucleoVigia.java hoshiestar/vigia/LimiteDeRelatorios.java \
             hoshiestar/vigia/IndiceDeJars.java hoshiestar/vigia/CamposNumericos.java hoshiestar/vigia/SaidaEmArquivo.java \
             hoshiestar/vigia/Vigia.java) | sort | sed "s|^|$W/src/main/java/|")
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CP" -d "$OUT/vigia-classes" \
  $FONTES "$W/src/test/java/hoshiestar/teste/Verifica.java" $(find "$W/src/test/vigia/java" -name '*.java' | sort)

falhas=0
rodar() {
  local java="$1" classe="$2"; shift 2
  if "$java" -cp "$OUT/vigia-classes:$CP" "$classe" "$@"; then :; else falhas=$((falhas + 1)); echo "[vigia] FALHOU: $classe ($java)"; fi
}
JDKS=("$J8/bin/java")
if [[ -x "$J21/bin/java" ]]; then JDKS+=("$J21/bin/java"); else echo "[vigia] AVISO: JDK 21 nao encontrado em $J21; rodando so no 8"; fi
for java in "${JDKS[@]}"; do
  rm -rf "$OUT/vigia-config"; rodar "$java" hoshiestar.teste.vigia.TesteVigiaConfig "$OUT/vigia-config" "$OUT/vigia-classes:$CP"
  rm -rf "$OUT/vigia-indice"; rodar "$java" hoshiestar.teste.vigia.TesteVigiaIndice "$OUT/vigia-indice"
  rm -rf "$OUT/vigia-nucleo"; rodar "$java" hoshiestar.teste.vigia.TesteVigiaNucleo "$OUT/vigia-nucleo"
  rodar "$java" hoshiestar.teste.vigia.BenchVigia
done
if [[ $falhas -eq 0 ]]; then echo "[vigia] TODOS OS TESTES PASSARAM"; else echo "[vigia] $falhas teste(s) falharam"; exit 1; fi
