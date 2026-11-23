#!/usr/bin/env bash
# Hoshiestar - testes isolados dos limites do "mover area" do ChromatiCraft (recurso 1.B):
#  1. TesteShiftDecisao: a decisao pura (DecisaoShift) do jar CONSTRUIDO, com casos de borda (volume, distancia,
#     altura do destino nas seis direcoes, intervalo minimo, limpeza do mapa de jogadores, chunks, ordem, threads).
#     Nao precisa de mod nem de Minecraft de verdade: roda em cada JDK;
#  2. TesteChromatiCraft: o transformador sobre a classe REAL AbilityCalls (jar "ChromatiCraft 1.7.10 V33a.jar", so pelo
#     classpath), com DragonAPI, Minecraft vanilla (pasta libraries/) e as NOSSAS classes tiradas do server.jar
#     construido, com os nomes do Minecraft traduzidos como o FML faz ao carregar: passa no CheckClassAdapter e no
#     verificador da JVM, o gancho e a primeira coisa do metodo, o corpo original fica identico, recusas, execucao;
#  3. mutacao do fonte (so no primeiro JDK): cada protecao tirada de DecisaoShift.java, compilada a parte, tem de ser
#     acusada pelo TesteShiftDecisao;
#  4. mutacao do BYTECODE da cola (so no primeiro JDK): cada um dos nove inteiros posicionais que GanchoShift.recusar passa
#     a DecisaoShift.decidir, trocado por outro, mais o retorno depois da recusa e o interruptor em execucao, tem de ser
#     acusado pelo TesteChromatiCraft (a decisao pura nao enxerga a cola).
#
# Chamado pelo servidor/test.sh quando HOSHIE_MODS esta definido:
#   HOSHIE_LIBS=<libraries/> HOSHIE_MODS=<mods/> servidor/test.sh <workspace>
# Uso direto (as mesmas variaveis):
#   src/test/mods/chromaticraft.bash <workspace> <pasta de saida> <classpath base do test.sh> <java> [<java>...]
# Precisa do jar construido com --mods ANTES (servidor/build.sh --rapido <workspace> --mods <mods/>):
# se alguma fonte nossa for mais nova que o jar, o teste para (testaria bytes velhos).
# CC_SEM_MUTACAO=1: pula a mutacao do fonte.
set -euo pipefail

W="$(cd "$1" && pwd)"
OUT="$2/mods-chromaticraft"
CPB="$3"
shift 3
JAVAS=("$@")
J8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
: "${HOSHIE_MODS:?defina HOSHIE_MODS=<pasta mods/ de um servidor de teste>}"
: "${HOSHIE_LIBS:?defina HOSHIE_LIBS=<pasta libraries/ de um servidor>}"

um() { # primeiro arquivo que casa; erro se nenhum
  local achado
  achado="$(find "$1" -name "$2" -type f 2>/dev/null | sort | head -1 || true)"
  [[ -n "$achado" ]] || { echo "[chromaticraft] ERRO: $2 nao encontrado em $1" >&2; exit 2; }
  echo "$achado"
}

JAR="$(ls -t "$W"/build/distributions/Hoshiestar-*-server.jar 2>/dev/null | head -1 || true)"
[[ -n "$JAR" ]] || { echo "[chromaticraft] ERRO: jar construido nao encontrado; rode servidor/build.sh --rapido $W --mods <mods/>" >&2; exit 2; }
NOVOS="$(find "$W/src/main/java/hoshiestar" "$W/src/mods/java" -name '*.java' -newer "$JAR" 2>/dev/null | head -3 || true)"
[[ -z "$NOVOS" ]] || { echo "[chromaticraft] ERRO: o jar $JAR e mais velho que fontes nossas (ex.: $NOVOS); construa de novo" >&2; exit 2; }
CC="$(um "$HOSHIE_MODS" 'ChromatiCraft 1.7.10 V33a.jar')"
DA="$(um "$HOSHIE_MODS" 'DragonAPI 1.7.10 V33b-ForbiddenFix1.jar')"
VAN="$(find "$HOSHIE_LIBS" -path '*net/minecraft/server/1.7.10/server-1.7.10.jar' -type f | head -1)"
[[ -n "$VAN" ]] || { echo "[chromaticraft] ERRO: server-1.7.10.jar (vanilla) nao encontrado em $HOSHIE_LIBS" >&2; exit 2; }
LZMA="$(um "$HOSHIE_LIBS" 'lzma-0.0.1.jar')"
ASMC="$(um "$HOSHIE_LIBS" 'asm-commons-9.9.1.jar')"

echo "[chromaticraft] jar construido: $JAR"
echo "[chromaticraft] mods: $(basename "$CC"), $(basename "$DA"); vanilla: $VAN"
rm -rf "$OUT"
mkdir -p "$OUT/ferramenta" "$OUT/srg" "$OUT/colas" "$OUT/classes"

# 1. tradutor notch -> SRG (o mesmo mapa que o FML usa, de dentro do jar construido)
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$CPB:$ASMC" -d "$OUT/ferramenta" \
  "$W/src/test/mods/java/hoshiestar/teste/mods/RemapeadorSrg.java" "$W/src/test/mods/java/hoshiestar/teste/mods/MutarBytecode.java"
"$J8/bin/java" -cp "$OUT/ferramenta:$CPB:$ASMC:$LZMA" hoshiestar.teste.mods.RemapeadorSrg "$JAR" "$OUT/srg" "$JAR" "$VAN"
SRV_SRG="$OUT/srg/$(basename "${JAR%.jar}")-srg.jar"
MC_SRG="$OUT/srg/$(basename "${VAN%.jar}")-srg.jar"

# 2. colas de teste (so o que o FML faria sumir no servidor real)
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -d "$OUT/colas" \
  $(find "$W/src/test/mods/cc/stubs" -name '*.java' | sort)

# 3. testes: compilados contra o servidor SRG (as nossas classes do jar), o Minecraft SRG e os mods; a infraestrutura
#    comum dos testes com mods (CarregadorMods, RemapeadorSrg) e o Verifica vem dos mesmos fontes dos outros testes com mods
"$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 \
  -cp "$MC_SRG:$SRV_SRG:$CC:$DA:$VAN:$CPB:$ASMC:$LZMA" -d "$OUT/classes" \
  $(find "$W/src/test/mods/cc/java" -name '*.java' | sort) \
  "$W/src/test/mods/java/hoshiestar/teste/mods/CarregadorMods.java" \
  "$W/src/test/mods/java/hoshiestar/teste/mods/RemapeadorSrg.java" \
  "$W/src/test/java/hoshiestar/teste/Verifica.java"

# 4. roda em cada JDK
falhas=0
for java in "${JAVAS[@]}"; do
  if "$java" -cp "$OUT/classes:$SRV_SRG:$CPB" hoshiestar.teste.chromaticraft.TesteShiftDecisao; then :; else
    falhas=$((falhas + 1)); echo "[chromaticraft] FALHOU (decisao) em $java"
  fi
  if "$java" -cp "$OUT/classes:$SRV_SRG:$CPB:$ASMC:$LZMA" hoshiestar.teste.chromaticraft.TesteChromatiCraft \
      "$JAR" "$OUT/srg" "$CC" "$DA" "$VAN" "$OUT/classes" "$OUT/colas"; then :; else
    falhas=$((falhas + 1)); echo "[chromaticraft] FALHOU (transformador na classe real) em $java"
  fi
done

# 5. mutacao do fonte (no primeiro JDK): cada protecao tirada de DecisaoShift.java tem de fazer o TesteShiftDecisao FALHAR
if [[ -z "${CC_SEM_MUTACAO:-}" ]]; then
  FONTE="$W/src/main/java/hoshiestar/chromaticraft/DecisaoShift.java"
  java="${JAVAS[0]}"
  mutar() { # numero, descricao, comando sed (aplicado ao fonte copiado)
    local n="$1" desc="$2" ; shift 2
    local d="$OUT/mut/$n"
    rm -rf "$d"; mkdir -p "$d/src/hoshiestar/chromaticraft" "$d/classes"
    sed "$@" "$FONTE" > "$d/src/hoshiestar/chromaticraft/DecisaoShift.java"
    if cmp -s "$FONTE" "$d/src/hoshiestar/chromaticraft/DecisaoShift.java"; then
      echo "[chromaticraft] ERRO: a mutacao M$n ($desc) nao mudou o fonte (o padrao sumiu?)" >&2; falhas=$((falhas + 1)); return
    fi
    "$J8/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$SRV_SRG:$CPB" -d "$d/classes" "$d/src/hoshiestar/chromaticraft/DecisaoShift.java"
    # timeout: uma mutacao que tira o teto de consultas faz o laco de chunks nao terminar (mundo de mentira todo carregado);
    # o teste nao terminar tambem e acusar a mutacao (codigo 124)
    local rc=0
    timeout 120 "$java" -cp "$d/classes:$OUT/classes:$SRV_SRG:$CPB" hoshiestar.teste.chromaticraft.TesteShiftDecisao > "$d/saida.txt" 2>&1 || rc=$?
    if [[ $rc -eq 0 ]]; then
      echo "[chromaticraft] FALHOU: a mutacao M$n ($desc) NAO foi acusada pelo teste" >&2; falhas=$((falhas + 1))
    elif [[ $rc -eq 124 ]]; then
      echo "[chromaticraft] mutacao M$n acusada ($desc): o teste nao terminou em 120 s (laco sem fim)"
    else
      echo "[chromaticraft] mutacao M$n acusada ($desc): $(grep -c 'FALHA\|Exception' "$d/saida.txt" || true) verificacoes falharam"
    fi
  }
  mutar 1 "intervalo: nao confere" -e '0,/if (intervaloNanos > 0) {/s//if (false) {/'
  mutar 2 "intervalo: o limite vira <= (recusa tambem no instante exato)" -e 's/if (e != null && agora - e\[0\] < intervaloNanos) {/if (e != null \&\& agora - e[0] <= intervaloNanos) {/'
  mutar 3 "intervalo: recusa renova a hora" -e 's/^            recusas\[m.ordinal()\]++;/            recusas[m.ordinal()]++; guardar(jogador, agora);/'
  mutar 4 "distancia: aceita acima do maximo" -e 's/(dist < 0 || dist > potenciaMaxima)/(dist < 0)/'
  mutar 5 "distancia: aceita negativa" -e 's/(dist < 0 || dist > potenciaMaxima)/(dist > potenciaMaxima)/'
  mutar 6 "distancia: maximo exclusivo (24 recusado)" -e 's/dist > potenciaMaxima/dist >= potenciaMaxima/'
  mutar 7 "volume: limite exclusivo (4096 recusado)" -e 's/vol > volumeMaximo/vol >= volumeMaximo/'
  mutar 8 "volume: sem o +1 das voltas (conta tamanho, nao posicoes)" -e 's/return tamanho < 0 ? 0L : tamanho + 1L;/return tamanho < 0 ? 0L : (long) tamanho;/'
  mutar 9 "volume: conta em int (estoura)" -e 's/return multiplicar(multiplicar(voltas(maxX - minX), voltas(maxY - minY)), voltas(maxZ - minZ));/return (int) voltas(maxX - minX) * (int) voltas(maxY - minY) * (int) voltas(maxZ - minZ);/'
  mutar 10 "volume: multiplicacao sem saturar" -e 's|if (a > Long.MAX_VALUE / b) {|if (false) {|'
  mutar 11 "altura: so confere o minimo" -e 's/(minY + oy < 0 || maxY + oy > ALTURA_MAXIMA)/(minY + oy < 0)/'
  mutar 12 "altura: so confere o maximo" -e 's/(minY + oy < 0 || maxY + oy > ALTURA_MAXIMA)/(maxY + oy > ALTURA_MAXIMA)/'
  mutar 13 "altura: 255 recusado (>=)" -e 's/maxY + oy > ALTURA_MAXIMA/maxY + oy >= ALTURA_MAXIMA/'
  mutar 14 "altura: mede a origem, nao o destino" -e 's/long oy = (long) dy \* dist;/long oy = 0;/'
  mutar 15 "altura: deslocamento em int (estoura com distancia absurda)" -e 's/long oy = (long) dy \* dist;/long oy = dy * dist;/'
  mutar 16 "chunks: nao alarga a caixa em 1 bloco" -e 's/long cx0 = (x0 - 1) >> 4;/long cx0 = x0 >> 4;/'
  mutar 17 "chunks: nao alarga o lado maximo" -e 's/long cz1 = (z1 + 1) >> 4;/long cz1 = z1 >> 4;/'
  mutar 18 "chunks: nao confere o destino" -e 's/|| !chunksCarregados(minX + ox, maxX + ox, minZ + oz, maxZ + oz, chunks)//'
  mutar 19 "chunks: sem o teto de consultas" -e 's/if (total > LIMITE_CHUNKS) {/if (false) {/'
  mutar 20 "chunks: destino sem o deslocamento em z" -e 's/minZ + oz, maxZ + oz, chunks/minZ, maxZ, chunks/'
  mutar 21 "distancia: nunca confere" -e 's/if (validarDistancia && (dist < 0 || dist > potenciaMaxima)) {/if (validarDistancia \&\& false) {/'
  mutar 22 "limpeza: apaga tambem registros ainda validos" -e 's/if (agora - it.next().getValue()\[0\] >= intervaloNanos) {/if (true) {/'
  mutar 23 "limpeza: nunca limpa" -e 's/if (ultimos.size() >= LIMPAR_COM) {/if (false) {/'
  mutar 24 "limpeza: sem o teto absoluto" -e 's/if (ultimos.size() >= MAXIMO_ABSOLUTO) {/if (false) {/'
  mutar 25 "zero posicoes: nao pula altura e chunks" -e 's/if (vol == 0) {/if (false) {/'
  mutar 26 "so conta o aceito no mapa se o intervalo esta ligado (guarda sempre)" -e 's/if (intervaloNanos <= 0) {/if (false) {/'
fi

# 6. mutacao do BYTECODE da cola (no primeiro JDK): estraga GanchoShift.recusar no jar SRG (uma copia por mutacao) e o
#    TesteChromatiCraft tem de FALHAR
if [[ -z "${CC_SEM_MUTACAO:-}" ]]; then
  java="${JAVAS[0]}"
  GANCHO_CLASSE="hoshiestar/chromaticraft/shift/GanchoShift.class"
  BASE_GANCHO="$OUT/GanchoShift-original.class"
  unzip -p "$SRV_SRG" "$GANCHO_CLASSE" > "$BASE_GANCHO"
  SRV_NOME="$(basename "$SRV_SRG")"
  BB="Reika/DragonAPI/Instantiable/Data/Immutable/BlockBox"
  FD="net/minecraftforge/common/util/ForgeDirection"
  mutarCola() { # numero, descricao, tipo e argumentos do MutarBytecode (o metodo e sempre recusar)
    local n="$1" desc="$2"; shift 2
    local d="$OUT/mut-cola/$n"
    rm -rf "$d"; mkdir -p "$d/srg" "$d/x/hoshiestar/chromaticraft/shift"
    cp -l "$OUT"/srg/*.jar "$d/srg/" 2>/dev/null || cp "$OUT"/srg/*.jar "$d/srg/"
    rm -f "$d/srg/$SRV_NOME"; cp "$SRV_SRG" "$d/srg/$SRV_NOME" # copia de verdade: o zip abaixo altera este arquivo
    if ! "$J8/bin/java" -cp "$OUT/ferramenta:$CPB" hoshiestar.teste.mods.MutarBytecode "$BASE_GANCHO" "$d/x/$GANCHO_CLASSE" recusar "$@"; then
      echo "[chromaticraft] ERRO: a mutacao da cola C$n ($desc) nao mudou o bytecode (o padrao sumiu?)" >&2; falhas=$((falhas + 1)); return
    fi
    (cd "$d/x" && zip -q "$d/srg/$SRV_NOME" "$GANCHO_CLASSE")
    local rc=0
    timeout 300 "$java" -cp "$OUT/classes:$d/srg/$SRV_NOME:$CPB:$ASMC:$LZMA" hoshiestar.teste.chromaticraft.TesteChromatiCraft \
        "$JAR" "$d/srg" "$CC" "$DA" "$VAN" "$OUT/classes" "$OUT/colas" > "$d/saida.txt" 2>&1 || rc=$?
    if [[ $rc -eq 0 ]]; then
      echo "[chromaticraft] FALHOU: a mutacao da cola C$n ($desc) NAO foi acusada pelo teste" >&2; falhas=$((falhas + 1))
    else
      echo "[chromaticraft] mutacao da cola C$n acusada ($desc): $(grep -c '^  FALHA' "$d/saida.txt" || true) verificacoes falharam, $(grep -c '^Exception in thread' "$d/saida.txt" || true) excecao(oes) que derrubaram o teste"
    fi
    rm -rf "$d/srg" "$d/x" # so a saida fica
  }
  mutarCola 1 "dz recebe offsetX" campo "$FD" offsetZ offsetX 0
  mutarCola 2 "dx recebe offsetZ" campo "$FD" offsetX offsetZ 0
  mutarCola 3 "dy recebe offsetX" campo "$FD" offsetY offsetX 0
  mutarCola 4 "maxY recebe minY" campo "$BB" maxY minY 0
  mutarCola 5 "minY recebe maxY" campo "$BB" minY maxY 0
  mutarCola 6 "maxZ recebe minZ" campo "$BB" maxZ minZ 0
  mutarCola 7 "minZ recebe maxZ" campo "$BB" minZ maxZ 0
  mutarCola 8 "maxX recebe minX" campo "$BB" maxX minX 0
  mutarCola 9 "minX recebe maxX" campo "$BB" minX maxX 0
  mutarCola 10 "depois de recusar devolve 'siga' (false)" ret1para0
  mutarCola 11 "ignora o interruptor em execucao (ChromatiCraft.ativo)" getstaticbool hoshiestar/chromaticraft/ChromatiCraft ativo 1
fi

[[ $falhas -eq 0 ]]
