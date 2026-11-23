#!/usr/bin/env bash
# Hoshiestar - teste isolado dos atalhos do Ars Magica 2 (recurso 1.F) com as classes REAIS: o
# am2.AMEventHandler do jar do AM2 (pasta mods/, so pelo classpath), Minecraft vanilla (pasta libraries/) e as
# NOSSAS classes tiradas do server.jar construido (os bytes que vao para o servidor), com os nomes do Minecraft
# traduzidos como o FML faz ao carregar (mapa do proprio jar).
#  - parte "verificado" (verificador da JVM LIGADO): unidades, transformador sobre a classe real (CheckClassAdapter e
#    o verificador da JVM), subcomando e ordem dos transformadores do AM2/AM2PG;
#  - parte "harness": a classe real reduzida aos dois metodos alvo, com Minecraft/Forge de mentira
#    (src/test/mods/am2/stubs, so os membros que os dois metodos usam, nomes SRG), ganchos do jar construido; hash do
#    estado identico entre original, ligado, desligado e trocado em execucao; casos; controle negativo. Roda com
#    -XX:-BytecodeVerificationRemote porque a classe reduzida cita centenas de classes que nao ha como simular
#    (a verificacao da classe inteira e da parte "verificado");
#  - mutacao do fonte (so no primeiro JDK): cada protecao tirada de Atalhos.java, compilada a parte, tem de ser acusada.
#
# Chamado pelo servidor/test.sh quando HOSHIE_MODS esta definido:
#   HOSHIE_LIBS=<libraries/> HOSHIE_MODS=<mods/> servidor/test.sh <workspace>
# Uso direto (as mesmas variaveis):
#   src/test/mods/arsmagica.bash <workspace> <pasta de saida> <classpath base do test.sh> <java> [<java>...]
# Precisa do jar construido com --mods ANTES (servidor/build.sh --rapido <workspace> --mods <mods/>):
# se alguma fonte nossa for mais nova que o jar, o teste para (testaria bytes velhos).
# AM_RAPIDO=1: menos iteracoes no harness (para iterar). AM_SEM_MUTACAO=1: pula a mutacao do fonte.
set -euo pipefail

W="$(cd "$1" && pwd)"
OUT="$2/mods-arsmagica"
CPB="$3"
shift 3
JAVAS=("$@")
J8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
: "${HOSHIE_MODS:?defina HOSHIE_MODS=<pasta mods/ de um servidor de teste>}"
: "${HOSHIE_LIBS:?defina HOSHIE_LIBS=<pasta libraries/ de um servidor>}"

um() { # primeiro arquivo que casa; erro se nenhum
  local achado
  achado="$(find "$1" -name "$2" -type f 2>/dev/null | sort | head -1 || true)"
  [[ -n "$achado" ]] || { echo "[arsmagica] ERRO: $2 nao encontrado em $1" >&2; exit 2; }
  echo "$achado"
}

JAR="$(ls -t "$W"/build/distributions/Hoshiestar-*-server.jar 2>/dev/null | head -1 || true)"
[[ -n "$JAR" ]] || { echo "[arsmagica] ERRO: jar construido nao encontrado; rode servidor/build.sh --rapido $W --mods <mods/>" >&2; exit 2; }
NOVOS="$(find "$W/src/main/java/hoshiestar" "$W/src/mods/java" -name '*.java' -newer "$JAR" 2>/dev/null | head -3 || true)"
[[ -z "$NOVOS" ]] || { echo "[arsmagica] ERRO: o jar $JAR e mais velho que fontes nossas (ex.: $NOVOS); construa de novo" >&2; exit 2; }
AM2="$(um "$HOSHIE_MODS" 'AM2.5-LTS-1.6.11-LE-CR40.jar')"
AM2PG="$(um "$HOSHIE_MODS" 'AM2PlayGround-Beta-0.12.1-CR2.jar')"
VAN="$(find "$HOSHIE_LIBS" -path '*net/minecraft/server/1.7.10/server-1.7.10.jar' -type f | head -1)"
[[ -n "$VAN" ]] || { echo "[arsmagica] ERRO: server-1.7.10.jar (vanilla) nao encontrado em $HOSHIE_LIBS" >&2; exit 2; }
LZMA="$(um "$HOSHIE_LIBS" 'lzma-0.0.1.jar')"
ASMC="$(um "$HOSHIE_LIBS" 'asm-commons-9.9.1.jar')"

echo "[arsmagica] jar construido: $JAR"
echo "[arsmagica] mods: $(basename "$AM2"), $(basename "$AM2PG"); vanilla: $VAN"
rm -rf "$OUT"
mkdir -p "$OUT/ferramenta" "$OUT/srg" "$OUT/stubs" "$OUT/classes"

# 1. tradutor notch -> SRG (o mesmo mapa que o FML usa, de dentro do jar construido)
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CPB:$ASMC" -d "$OUT/ferramenta" \
  "$W/src/test/mods/java/hoshiestar/teste/mods/RemapeadorSrg.java"
"$J8/bin/java" -cp "$OUT/ferramenta:$CPB:$ASMC:$LZMA" hoshiestar.teste.mods.RemapeadorSrg "$JAR" "$OUT/srg" "$JAR" "$VAN"
SRV_SRG="$OUT/srg/$(basename "${JAR%.jar}")-srg.jar"
MC_SRG="$OUT/srg/$(basename "${VAN%.jar}")-srg.jar"

# 2. stubs do Minecraft/Forge/AM2 (so para o harness)
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -d "$OUT/stubs" \
  $(find "$W/src/test/mods/am2/stubs" -name '*.java' | sort)

# 3. testes: compilados contra os stubs (na frente), o servidor SRG e o AM2; a infraestrutura comum dos testes
#    com mods (CarregadorMods, RemapeadorSrg) e o Verifica vem dos mesmos fontes dos outros testes com mods
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 \
  -cp "$OUT/stubs:$SRV_SRG:$AM2:$CPB:$ASMC:$LZMA" -d "$OUT/classes" \
  $(find "$W/src/test/mods/am2/java" -name '*.java' | sort) \
  "$W/src/test/mods/java/hoshiestar/teste/mods/CarregadorMods.java" \
  "$W/src/test/mods/java/hoshiestar/teste/mods/RemapeadorSrg.java" \
  "$W/src/test/java/hoshiestar/teste/Verifica.java"

ARGS=("$JAR" "$OUT/srg" "$AM2" "$AM2PG" "$VAN" "$OUT/classes" "$OUT/stubs")
RAP=()
[[ -n "${AM_RAPIDO:-}" ]] && RAP=(rapido)

# 4. roda em cada JDK: a parte verificada (com o verificador da JVM) e o harness
falhas=0
primeiro=1
for java in "${JAVAS[@]}"; do
  if "$java" -cp "$OUT/classes:$SRV_SRG:$CPB:$ASMC:$LZMA" hoshiestar.teste.arsmagica.TesteArsMagica "${ARGS[@]}" parte=verificado "${RAP[@]}"; then :; else
    falhas=$((falhas + 1)); echo "[arsmagica] FALHOU (parte verificada) em $java"
  fi
  if "$java" -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -cp "$OUT/classes:$SRV_SRG:$CPB:$ASMC:$LZMA" hoshiestar.teste.arsmagica.TesteArsMagica "${ARGS[@]}" parte=harness "${RAP[@]}"; then :; else
    falhas=$((falhas + 1)); echo "[arsmagica] FALHOU (harness) em $java"
  fi
done

# 5. mutacao do fonte (no primeiro JDK): cada protecao tirada de Atalhos.java tem de fazer o harness FALHAR
if [[ -z "${AM_SEM_MUTACAO:-}" ]]; then
  FONTE="$W/src/main/java/hoshiestar/arsmagica/Atalhos.java"
  java="${JAVAS[0]}"
  mutar() { # numero, descricao, comando sed (aplicado ao fonte copiado)
    local n="$1" desc="$2" ; shift 2
    local d="$OUT/mut/$n"
    rm -rf "$d"; mkdir -p "$d/src/hoshiestar/arsmagica" "$d/classes"
    sed "$@" "$FONTE" > "$d/src/hoshiestar/arsmagica/Atalhos.java"
    if cmp -s "$FONTE" "$d/src/hoshiestar/arsmagica/Atalhos.java"; then
      echo "[arsmagica] ERRO: a mutacao M$n ($desc) nao mudou o fonte (o padrao sumiu?)" >&2; falhas=$((falhas + 1)); return
    fi
    "$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$SRV_SRG:$CPB" -d "$d/classes" "$d/src/hoshiestar/arsmagica/Atalhos.java"
    if "$java" -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -cp "$d/classes:$OUT/classes:$SRV_SRG:$CPB:$ASMC:$LZMA" hoshiestar.teste.arsmagica.TesteArsMagica \
        "${ARGS[@]}" parte=diferencial rapido > "$d/saida.txt" 2>&1; then
      echo "[arsmagica] FALHOU: a mutacao M$n ($desc) NAO foi acusada pelo harness" >&2; falhas=$((falhas + 1))
    else
      echo "[arsmagica] mutacao M$n acusada ($desc): $(grep -c 'FALHOU\|ERRO' "$d/saida.txt" || true) verificacoes falharam"
    fi
  }
  mutar 1 "F1 pula quando o mapa NAO esta vazio" -e 's/if (varredura && aceleradas != null && aceleradas.isEmpty()) {/if (varredura \&\& aceleradas != null \&\& !aceleradas.isEmpty()) {/'
  mutar 2 "F1 ignora o interruptor" -e 's/if (varredura && aceleradas != null && aceleradas.isEmpty()) {/if (aceleradas != null \&\& aceleradas.isEmpty()) {/'
  mutar 3 "F1 pula sempre que ligado (sem olhar o mapa)" -e 's/if (varredura && aceleradas != null && aceleradas.isEmpty()) {/if (varredura) {/'
  mutar 4 "F2 (isSkillDisabled) pula quando o mapa NAO esta vazio" -e 's/if (diluicao && retardadas != null && retardadas.isEmpty()) {/if (diluicao \&\& retardadas != null \&\& !retardadas.isEmpty()) {/'
  mutar 5 "F2 (getSkill) pula sempre que ligado (sem olhar o mapa)" -e 's/return !(diluicao && retardadas != null && retardadas.isEmpty());/return !diluicao;/'
  mutar 6 "F1 sem a regra de outra thread" -e '0,/if (!ThreadServidor.souAThreadDoServidor()) {/s//if (false) {/'
  mutar 7 "F2 nao conta as puladas" -e 's/contagem\[DILUIR_PULOU\]++;//'
  mutar 8 "F1 conta a pulada como original" -e 's/contagem\[VARRER_PULOU\]++;/contagem[VARRER_ORIGINAL]++;/'
  mutar 9 "F1 pula quando da erro nosso" -e '/erro(true, "varrerMundos", t);/{n;s/return true;/return false;/}'
  mutar 10 "F2 (getSkill) decidido pelo interruptor da varredura (revisao A1)" -e 's/return !(diluicao && retardadas != null && retardadas.isEmpty());/return !(varredura \&\& retardadas != null \&\& retardadas.isEmpty());/'
fi

[[ $falhas -eq 0 ]]
