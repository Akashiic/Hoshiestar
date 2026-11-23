#!/usr/bin/env bash
# Hoshiestar - testes isolados do recurso observador (Etapa 2: o observador de acessos entre regioes).
#
# Uso (um comando):
#   HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/observador/teste-observador.bash <workspace>
#
# Por que um script separado do servidor/test.sh: o test.sh so compila uma lista fixa de pacotes de src/main/java,
# e o pacote hoshiestar/observador nao esta nela (o mesmo caso do vigia e do medidor). Extensao .bash porque o
# .gitignore do upstream ignora *.sh.
#
# O que roda (JDK 8 e JDK 21), sempre com o MESMO codigo que roda no servidor (Nucleo, Observador, Regioes, Relatorio,
# TabelaContagem...), sem Minecraft e com um mundo falso:
#   1. TesteObservadorNucleo: acesso dentro da regiao, no vizinho, distante, em outra dimensao; sem origem; agregacao
#      exata; tetos de tamanho; amostra de pilha (1 em N, no maximo 20 por tick); pilha de contextos; regra de juncao
#      das regioes (3 secoes, cadeia, dimensoes, recalculo com secao 8x8); carga de chunk (T3); valvula de custo;
#   2. TesteObservadorConfig: chaves do hoshiestar.yml, registro, comando (argumentos), ciclo de vida completo com um
#      jogo falso e relogio falso, relatorio .json/.txt sem dado pessoal, parada no meio, parada do servidor;
#   3. TesteObservadorConcorrencia: outras threads (T4) e comando de outra thread (RCON) durante a observacao;
#   3b. TesteRevisaoConcObservador: achados da revisao de concorrencia (teto e valvula da amostra de pilha em outra
#      thread, pedido de parada que nao se perde, falha() de outra thread, comando sob trava, prazo do indice de jars);
#   4. TesteObservadorBytecode (so se o workspace ja foi compilado pelo build rapido): os patches no bytecode real
#      (teste de campo estatico por gancho, padroes do Opis/firewall/medidor/vigia intactos, nenhuma variavel local nova);
#   5. BenchObservador: custo do caminho quente em ns (indicacao, nao medicao de servidor).
#   6. TesteListaObservada: prototipo da lista de tiles (observador.lista). Diferencial: ListaObservadaArray x ArrayList e
#      ListaObservadaHashed x HashedArrayList em milhoes de operacoes aleatorias (HOSHIE_LISTA_OPS por par e modo, padrao
#      250000; 12 combinacoes de par e modo), resultado e estado identicos inclusive excecoes; contagens, marcas do World,
#      troca do objeto (embrulhar), criterio da secao 4 do plano e relatorio.
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

echo "[observador] workspace: $W"
echo "[observador] saida: $OUT"

rm -rf "$OUT/observador-classes"; mkdir -p "$OUT/observador-classes"
# so o que NAO cita o Minecraft nem o Bukkit (GanchosObservador e ComandoObservador ficam de fora: o teste de bytecode os confere)
FONTES=$( (cd "$W/src/main/java" && find io/github/crucible/util/config -name '*.java'
          ls io/github/crucible/util/HashedArrayList.java
          ls hoshiestar/Hoshiestar.java hoshiestar/api/*.java hoshiestar/config/*.java \
             hoshiestar/embutidos/*.java hoshiestar/embutidos/asm/*.java hoshiestar/vigia/IndiceDeJars.java
          ls hoshiestar/observador/*.java | grep -v -e GanchosObservador -e ComandoObservador) \
          | sort | sed "s|^|$W/src/main/java/|")
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CP" -d "$OUT/observador-classes" \
  $FONTES "$W/src/test/java/hoshiestar/teste/Verifica.java" $(find "$W/src/test/observador/java" -name '*.java' | sort)

falhas=0
rodar() {
  local java="$1" classe="$2"; shift 2
  if "$java" ${JOPT:-} -cp "$OUT/observador-classes:$CP" "$classe" "$@"; then :; else falhas=$((falhas + 1)); echo "[observador] FALHOU: $classe ($java)"; fi
}
JDKS=("$J8/bin/java")
if [[ -x "$J21/bin/java" ]]; then JDKS+=("$J21/bin/java"); else echo "[observador] AVISO: JDK 21 nao encontrado em $J21; rodando so no 8"; fi
CLASSES_BUILD="$W/eclipse/cauldron/build/classes/java/main"
for java in "${JDKS[@]}"; do
  rodar "$java" hoshiestar.observador.TesteObservadorNucleo
  # -XX:-OmitStackTraceInFastThrow: sem ele, a JVM troca a excecao implicita repetida (ex.: AIOOBE de get(-2)) por uma
  # pre-alocada SEM mensagem, em momentos diferentes nas duas listas, e a comparacao exata de mensagem falharia por isso
  JOPT=-XX:-OmitStackTraceInFastThrow rodar "$java" hoshiestar.observador.TesteListaObservada "${HOSHIE_LISTA_OPS:-250000}"
  rm -rf "$OUT/observador-config"; mkdir -p "$OUT/observador-config"
  pushd "$OUT/observador-config" > /dev/null; rodar "$java" hoshiestar.observador.TesteObservadorConfig "$OUT/observador-config"; popd > /dev/null
  rm -rf "$OUT/observador-correcoes"; mkdir -p "$OUT/observador-correcoes"
  rodar "$java" hoshiestar.observador.TesteCorrecoesObservador "$OUT/observador-correcoes"
  rm -rf "$OUT/observador-revconc"; mkdir -p "$OUT/observador-revconc"
  rodar "$java" hoshiestar.observador.TesteRevisaoConcObservador "$OUT/observador-revconc"
  rm -rf "$OUT/observador-conc"; rodar "$java" hoshiestar.observador.TesteObservadorConcorrencia "$OUT/observador-conc" "${HOSHIE_ESTRESSE_S:-5}"
  if [[ -f "$CLASSES_BUILD/net/minecraft/world/World.class" ]]; then
    rodar "$java" hoshiestar.observador.TesteObservadorBytecode "$CLASSES_BUILD"
  else
    echo "[observador] AVISO: TesteObservadorBytecode PULADO ($CLASSES_BUILD nao existe: rode o build rapido antes)"
  fi
  rodar "$java" hoshiestar.observador.BenchObservador
done
if [[ $falhas -eq 0 ]]; then echo "[observador] TODOS OS TESTES PASSARAM"; else echo "[observador] $falhas teste(s) falharam"; exit 1; fi
