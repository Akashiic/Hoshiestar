#!/usr/bin/env bash
# Hoshiestar - testes isolados do recurso orcamento (Etapa 2.5, Degrau A: orcamento de tick por regiao).
#
# Uso (um comando):
#   HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/orcamento/teste-orcamento.bash <workspace>
#
# Por que um script separado do servidor/test.sh: o test.sh so compila uma lista fixa de pacotes de src/main/java, e os
# pacotes hoshiestar/regioes e hoshiestar/orcamento nao estao nela (o mesmo caso do vigia, do medidor e do observador).
# Extensao .bash porque o .gitignore do upstream ignora *.sh.
#
# O que roda (JDK 8 e JDK 21), sempre com o MESMO codigo que roda no servidor (Regionizador, Nucleo, Cadencia, Heranca...),
# sem Minecraft, com jogo falso e relogio falso (numero de simulacao; NAO e medicao de servidor):
#   1. TesteRegioes: juncao (raio de 3 secoes), cadeia, dimensoes, divisao, bordas das secoes e das coordenadas, mais de 256
#      dimensoes, sempre-junto, ordem dos chunks;
#   1b. TesteRegioesObservador: diferencial contra a regra do observador (mesma particao em 3000 fotos aleatorias);
#   2. TesteCadencia: restos da sequencia de Beatty (modulos 2 a 100), intervalos N e N+1, N por custo (pico e teto), limitador;
#   3. TesteOrcamento: tempo por regiao, estoura/volta com histerese, isentos (spawn, area, dimensao, mod, jogador), modo
#      medir, heranca na juncao e na divisao, mapa velho e suspensao, alertas, ferramentas de teste, falha, comando;
#   3b. TesteCorte: o corte do tick num mundo falso com os quatro lacos dos patches (regiao cortada pula tudo junto, isentos,
#      entidade que muda de regiao, puxada dos agendados contra modelo em 1 milhao de operacoes, diferencial contra o jogo sem
#      corte depois de alcancar, chunk descarregando, desligado = original, erro num gancho, ponte entre regioes);
#   3c. TesteAdversario: casos da revisao independente (volta ao normal, fases, teto dos agendados, alertas, fila);
#   4. TesteOrcamentoConfig (duas vezes, ligado true e false): chaves do hoshiestar.yml, faixas, registro, static final;
#   5. TesteOrcamentoBytecode (so se o workspace ja foi compilado pelo build rapido): os patches no bytecode real (guarda,
#      forma do continue, puxada, gancho do tile fora do trecho do DragonAPI, nenhuma variavel local nova);
#   6. BenchOrcamento: custo do caminho quente em ns (indicacao, nao medicao de servidor).
# Saida 0 = tudo passou.
set -euo pipefail

W="$(cd "${1:?informe o workspace do fork}" && pwd)"
J8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
J21="${JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
OUT="${HOSHIE_SAIDA:-$(mktemp -d)}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
export LC_ALL=C.UTF-8
umask 022

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

echo "[orcamento] workspace: $W"
echo "[orcamento] saida: $OUT"

rm -rf "$OUT/orcamento-classes"; mkdir -p "$OUT/orcamento-classes"
# so o que NAO cita o Minecraft nem o Bukkit (GanchosOrcamento, ComandoOrcamento e FerramentasMinecraft ficam de fora: o
# teste de bytecode os confere). O pacote do observador entra para o teste diferencial da regra de juncao.
FONTES=$( (cd "$W/src/main/java" && find io/github/crucible/util/config -name '*.java'
          ls io/github/crucible/util/HashedArrayList.java
          ls hoshiestar/Hoshiestar.java hoshiestar/api/*.java hoshiestar/config/*.java \
             hoshiestar/embutidos/*.java hoshiestar/embutidos/asm/*.java hoshiestar/vigia/IndiceDeJars.java
          ls hoshiestar/regioes/*.java
          ls hoshiestar/observador/*.java | grep -v -e GanchosObservador -e ComandoObservador
          ls hoshiestar/orcamento/*.java | grep -v -e GanchosOrcamento -e ComandoOrcamento -e FerramentasMinecraft) \
          | sort | sed "s|^|$W/src/main/java/|")
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CP" -d "$OUT/orcamento-classes" \
  $FONTES "$W/src/test/java/hoshiestar/teste/Verifica.java" $(find "$W/src/test/orcamento/java" -name '*.java' | sort) \
  $(find "$W/src/test/observador/java/teste" -name '*.java' | sort)

falhas=0
rodar() {
  local java="$1" classe="$2"; shift 2
  if "$java" ${JOPT:-} -cp "$OUT/orcamento-classes:$CP" "$classe" "$@"; then :; else falhas=$((falhas + 1)); echo "[orcamento] FALHOU: $classe ($java)"; fi
}
JDKS=("$J8/bin/java")
if [[ -x "$J21/bin/java" ]]; then JDKS+=("$J21/bin/java"); else echo "[orcamento] AVISO: JDK 21 nao encontrado em $J21; rodando so no 8"; fi
CLASSES_BUILD="$W/eclipse/cauldron/build/classes/java/main"
for java in "${JDKS[@]}"; do
  rodar "$java" hoshiestar.orcamento.TesteRegioes
  rodar "$java" hoshiestar.observador.TesteRegioesObservador
  rodar "$java" hoshiestar.orcamento.TesteCadencia
  rm -rf "$OUT/orcamento-nucleo"; mkdir -p "$OUT/orcamento-nucleo"
  rodar "$java" hoshiestar.orcamento.TesteOrcamento "$OUT/orcamento-nucleo"
  rodar "$java" hoshiestar.orcamento.TesteCorte
  rodar "$java" hoshiestar.orcamento.TesteAdversario
  for lig in true false; do
    rm -rf "$OUT/orcamento-config-$lig"; mkdir -p "$OUT/orcamento-config-$lig"
    pushd "$OUT/orcamento-config-$lig" > /dev/null
    rodar "$java" hoshiestar.orcamento.TesteOrcamentoConfig "$OUT/orcamento-config-$lig" "$lig"
    popd > /dev/null
  done
  if [[ -f "$CLASSES_BUILD/net/minecraft/world/World.class" && -f "$CLASSES_BUILD/hoshiestar/orcamento/Orcamento.class" ]]; then
    rodar "$java" hoshiestar.orcamento.TesteOrcamentoBytecode "$CLASSES_BUILD"
  else
    echo "[orcamento] AVISO: TesteOrcamentoBytecode PULADO ($CLASSES_BUILD nao tem as classes: rode o build rapido antes)"
  fi
  rodar "$java" hoshiestar.orcamento.BenchOrcamento
done
if [[ $falhas -eq 0 ]]; then echo "[orcamento] TODOS OS TESTES PASSARAM"; else echo "[orcamento] $falhas teste(s) falharam"; exit 1; fi
