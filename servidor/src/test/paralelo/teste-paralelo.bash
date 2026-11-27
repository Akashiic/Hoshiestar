#!/usr/bin/env bash
# Hoshiestar - testes isolados do recurso paralelo (Degrau B, passo 1: pre-requisitos sem fase; passo 5: nucleo de
# concorrencia sem Minecraft).
#
# Uso (um comando):
#   HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/paralelo/teste-paralelo.bash <workspace> [--rapido]
#
#   --rapido: os testes do nucleo (4 a 6 abaixo) rodam na dose curta (o servidor/test.sh normal passa esta opcao). Sem ela,
#   dose cheia: os testes recebem -Dhoshiestar.teste.completo=true (1 milhao de ticks por numero de threads, fatias reais
#   de 5 s, lote que dorme 20 s, 40 mil repeticoes da sonda); leva dezenas de minutos por JDK.
#
# Por que um script separado do servidor/test.sh: o test.sh so compila uma lista fixa de pacotes de src/main/java, e o
# pacote hoshiestar/paralelo nao esta nela (o mesmo caso do vigia, do medidor e do orcamento). Extensao .bash porque o
# .gitignore do upstream ignora *.sh.
#
# O que roda (JDK 8 e JDK 21), sem Minecraft:
#   1. TesteRecusas: cada recusa da secao 10 e da secao 7 do plano como funcao pura (orcamento desligado, parada, H01 no modo
#      paralelo, dimensao, classe fora da lista, world.rand, distancia de simulacao, observador, firewall, sem veredito),
#      as faixas dos valores, a ordem fixa dos motivos, o desligado;
#   2. TesteParaleloConfig (duas vezes, ligado true e false): leitura do hoshiestar.yml (ausente, valores validos, lixo,
#      chave desconhecida, secao errada, arquivo ilegivel), registro, Paralelo.LIGADO static final, criterio de nome da
#      thread de regiao, texto do comando 'hoshie paralelo';
#   3. TesteParaleloBytecode: Paralelo.LIGADO e 'static final boolean', classes do paralelo sem Minecraft e, se o build
#      rapido ja foi feito, FMLCommonHandler.getEffectiveSide com uma chamada ao paralelo e nenhum outro gancho no jogo.
#   4. TesteNucleoCasos: casos montados do nucleo com regioes falsas (estados da fase, erro injetado, lote que dorme com
#      despejo por fatia e sem interrupcao, thread que morre, pedido delegado em todas as variantes, thread estranha no
#      portao com e sem o monitor, abortar com prazo, encerrar, e os casos da revisao do passo 5: encerrar chamado por
#      thread interrompida, portao logo depois de a thread do servidor sair do monitor, abortar no meio de uma operacao);
#   5. TesteNucleoCarga: ticks seguidos com 1 a 8 threads (cada lote exatamente uma vez, ordem interna, fase so termina com
#      todos) e caos (pedidos, erros, lotes sujos e threads estranhas ao mesmo tempo, com juncao falsa).
#   6. TesteNucleoSonda: instante sorteado com oraculo proprio (veio da revisao do passo 5): abortar, encerrar, morte de
#      thread, interrupcoes e item preso caindo no meio de pedidos, operacoes demoradas e threads estranhas no portao.
# Saida 0 = tudo passou.
#
# A prova dos testes 4 a 6 (defeitos postos de proposito no nucleo, um de cada vez) fica em mutacoes-nucleo.py, na mesma
# pasta; e lenta e nao roda aqui.
set -euo pipefail

W="$(cd "${1:?informe o workspace do fork}" && pwd)"
shift
COMPLETO=true
for a in "$@"; do
  case "$a" in
    --rapido) COMPLETO=false ;;
    *) echo "opcao desconhecida: $a (use --rapido ou nada)" >&2; exit 2 ;;
  esac
done
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

echo "[paralelo] workspace: $W"
echo "[paralelo] saida: $OUT"

rm -rf "$OUT/paralelo-classes"; mkdir -p "$OUT/paralelo-classes"
# so o que NAO cita o Minecraft nem o Bukkit (ComandoParaleloJogo fica de fora: o adaptador do Bukkit)
FONTES=$( (cd "$W/src/main/java" && find io/github/crucible/util/config -name '*.java'
          ls hoshiestar/Hoshiestar.java hoshiestar/api/*.java hoshiestar/config/*.java \
             hoshiestar/embutidos/*.java hoshiestar/embutidos/asm/*.java
          ls hoshiestar/paralelo/*.java | grep -v -e ComandoParaleloJogo) \
          | sort | sed "s|^|$W/src/main/java/|")
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CP" -d "$OUT/paralelo-classes" \
  $FONTES "$W/src/test/java/hoshiestar/teste/Verifica.java" $(find "$W/src/test/paralelo/java" -name '*.java' | sort)

falhas=0
rodar() {
  local java="$1" classe="$2" t0=$SECONDS; shift 2
  if "$java" ${JOPT:-} -cp "$OUT/paralelo-classes:$CP" "$classe" "$@"; then :; else falhas=$((falhas + 1)); echo "[paralelo] FALHOU: $classe ($java)"; fi
  echo "[paralelo] $classe ($java): $((SECONDS - t0)) s"
}
JDKS=("$J8/bin/java")
if [[ -x "$J21/bin/java" ]]; then JDKS+=("$J21/bin/java"); else echo "[paralelo] AVISO: JDK 21 nao encontrado em $J21; rodando so no 8"; fi
CLASSES_BUILD="$W/eclipse/cauldron/build/classes/java/main"
for java in "${JDKS[@]}"; do
  rodar "$java" hoshiestar.paralelo.TesteRecusas
  for lig in true false; do
    rm -rf "$OUT/paralelo-config-$lig"; mkdir -p "$OUT/paralelo-config-$lig"
    pushd "$OUT/paralelo-config-$lig" > /dev/null
    rodar "$java" hoshiestar.paralelo.TesteParaleloConfig "$OUT/paralelo-config-$lig" "$lig"
    popd > /dev/null
  done
  if [[ -f "$CLASSES_BUILD/cpw/mods/fml/common/FMLCommonHandler.class" && -f "$CLASSES_BUILD/hoshiestar/paralelo/Paralelo.class" ]]; then
    rodar "$java" hoshiestar.paralelo.TesteParaleloBytecode "$OUT/paralelo-classes" "$CLASSES_BUILD"
  else
    echo "[paralelo] AVISO: parte do jogo do TesteParaleloBytecode PULADA ($CLASSES_BUILD nao tem as classes: rode o build rapido antes)"
    rodar "$java" hoshiestar.paralelo.TesteParaleloBytecode "$OUT/paralelo-classes"
  fi
  JOPT="${JOPT:-} -Dhoshiestar.teste.completo=$COMPLETO" rodar "$java" hoshiestar.paralelo.TesteNucleoCasos
  JOPT="${JOPT:-} -Dhoshiestar.teste.completo=$COMPLETO" rodar "$java" hoshiestar.paralelo.TesteNucleoCarga
  JOPT="${JOPT:-} -Dhoshiestar.teste.completo=$COMPLETO" rodar "$java" hoshiestar.paralelo.TesteNucleoSonda
done
if [[ $falhas -eq 0 ]]; then echo "[paralelo] TODOS OS TESTES PASSARAM"; else echo "[paralelo] $falhas teste(s) falharam"; exit 1; fi
