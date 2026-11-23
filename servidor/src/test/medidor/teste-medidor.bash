#!/usr/bin/env bash
# Hoshiestar - testes isolados do recurso medidor (tarefa 0.9: medidor de carga por regiao, no server.jar).
#
# Uso (um comando):
#   HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/medidor/teste-medidor.bash <workspace>
#
# Por que um script separado do servidor/test.sh: o test.sh so compila uma lista fixa de pacotes de
# src/main/java, e o pacote hoshiestar/medidor nao esta nela (o mesmo caso do vigia). Extensao .bash porque o
# .gitignore do upstream ignora *.sh.
#
# O que roda (JDK 8 e JDK 21), sempre com o MESMO codigo que roda no servidor (Medidor, Coleta, TabelaLong,
# Relatorio, Jogo, RegistroMedidor, vigia.Marcas), sem Minecraft:
#   1. TesteMedidorConfig: chave medidor.habilitado (padrao true; false recusa o comando), registro e status;
#   2. TesteMedidor: TabelaLong, chaves, Coleta, argumentos do 'hoshie medir', contagem exata N x ticks por
#      chunk, aninhamento e excecao, pilha cheia, autoteste (ocioso, NAO_LIGADO, dimensao sem laco),
#      robustez (duas vezes, parar no meio, sem jogo), pedidos de outra thread, valvula (tempo e custo),
#      pasta reserva (correcoes 8b), privacidade (sem nome/UUID/IP), falha interna;
#   2b. TesteMedidorMarcas: relogio de verdade pelo caminho dos patches (Marcas do vigia + Medidor), estresse com
#      uma thread "RCON" mandando medir/parar/status (HOSHIE_ESTRESSE_S segundos, padrao 10), habilitado=false e
#      calibracao fora do 1o tick (vieram dos testes adversarios da revisao);
#   3. TesteMedidorBytecode (so se o workspace ja foi compilado pelo build rapido): os patches no bytecode
#      real de World/EntityPlayerMP/MinecraftServer (teste de campo estatico por gancho; padroes do Opis e do
#      firewall intactos; nenhuma variavel local nova);
#   4. BenchMedidor: custo do caminho ligado em ns por tile entity (indicacao, nao medicao de servidor).
# Saida 0 = tudo passou.
set -euo pipefail

W="$(cd "${1:?informe o workspace do fork}" && pwd)"
J8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
J21="${JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
OUT="${HOSHIE_SAIDA:-$(mktemp -d)}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
export LC_ALL=C.UTF-8
# umask aberto, como o de um servidor comum: o teste da pasta reserva compara "nasce fechada" com o padrao
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

echo "[medidor] workspace: $W"
echo "[medidor] saida: $OUT"

rm -rf "$OUT/medidor-classes"; mkdir -p "$OUT/medidor-classes"
FONTES=$( (cd "$W/src/main/java" && find io/github/crucible/util/config -name '*.java'
          ls hoshiestar/Hoshiestar.java hoshiestar/api/*.java hoshiestar/config/*.java \
             hoshiestar/embutidos/*.java hoshiestar/embutidos/asm/*.java hoshiestar/vigia/Marcas.java \
             hoshiestar/medidor/Medidor.java hoshiestar/medidor/Coleta.java hoshiestar/medidor/TabelaLong.java \
             hoshiestar/medidor/Relatorio.java hoshiestar/medidor/Jogo.java hoshiestar/medidor/RegistroMedidor.java) \
          | sort | sed "s|^|$W/src/main/java/|")
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CP" -d "$OUT/medidor-classes" \
  $FONTES "$W/src/test/java/hoshiestar/teste/Verifica.java" $(find "$W/src/test/medidor/java" -name '*.java' | sort)

falhas=0
rodar() {
  local java="$1" classe="$2"; shift 2
  if "$java" -cp "$OUT/medidor-classes:$CP" "$classe" "$@"; then :; else falhas=$((falhas + 1)); echo "[medidor] FALHOU: $classe ($java)"; fi
}
JDKS=("$J8/bin/java")
if [[ -x "$J21/bin/java" ]]; then JDKS+=("$J21/bin/java"); else echo "[medidor] AVISO: JDK 21 nao encontrado em $J21; rodando so no 8"; fi
CLASSES_BUILD="$W/eclipse/cauldron/build/classes/java/main"
for java in "${JDKS[@]}"; do
  # dentro da pasta de trabalho: o registro procura o jar do mod antigo em ./mods, como o servidor faz
  rm -rf "$OUT/medidor-config"; mkdir -p "$OUT/medidor-config"
  pushd "$OUT/medidor-config" > /dev/null; rodar "$java" hoshiestar.medidor.TesteMedidorConfig "$OUT/medidor-config"; popd > /dev/null
  rm -rf "$OUT/medidor-nucleo"; rodar "$java" hoshiestar.medidor.TesteMedidor "$OUT/medidor-nucleo"
  rm -rf "$OUT/medidor-marcas"; rodar "$java" hoshiestar.medidor.TesteMedidorMarcas "$OUT/medidor-marcas" "${HOSHIE_ESTRESSE_S:-10}"
  if [[ -f "$CLASSES_BUILD/net/minecraft/world/World.class" ]]; then
    rodar "$java" hoshiestar.medidor.TesteMedidorBytecode "$CLASSES_BUILD"
  else
    echo "[medidor] AVISO: TesteMedidorBytecode PULADO ($CLASSES_BUILD nao existe: rode o build rapido antes)"
  fi
  rm -rf "$OUT/medidor-bench"; rodar "$java" hoshiestar.medidor.BenchMedidor "$OUT/medidor-bench"
done
if [[ $falhas -eq 0 ]]; then echo "[medidor] TODOS OS TESTES PASSARAM"; else echo "[medidor] $falhas teste(s) falharam"; exit 1; fi
