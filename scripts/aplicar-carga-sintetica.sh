#!/usr/bin/env bash
# Hoshiestar - aplica a CARGA SINTETICA numa COPIA DE TESTE (nunca no servidor real).
#
# Uso:
#   scripts/aplicar-carga-sintetica.sh <pasta-da-copia> <layout a|b|c> [opcoes]
# Opcoes:
#   --bases N       numero de bases nos layouts a e b (padrao 4; o layout c sempre tem 1)
#   --kits K        tamanho: kits por base (padrao 2; ver tools/carga_sintetica.py)
#   --mundo-novo    apaga o mundo da copia (so se o servidor estiver parado) e liga com mundo PLANO
#   --so-ligar      so liga a copia (mundo plano) e sai, sem montar bases (para medir o servidor vazio)
#   --so-bots       so coloca os bots parados onde ficariam as bases (controle: custo de manter os chunks)
#   --medir SEG     depois de montar, espera os chunks estabilizarem e roda "hoshie medir SEG"
# Se a copia estiver desligada, liga (mundo plano, sem monstros/animais, allow-flight) e espera o "Done".
# Se estiver ligada, usa como esta. Deixa o servidor e os bots LIGADOS: pare com
#   scripts/parar-carga-sintetica.sh <pasta-da-copia>
# Regra do projeto: no maximo UM servidor de teste por vez nesta maquina.
set -euo pipefail
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAIZ="$(dirname "$AQUI")"
COPIA="${1:?informe a pasta da copia de teste}"
LAYOUT="${2:?informe o layout: a, b ou c}"
shift 2
BASES=4; KITS=2; MUNDO_NOVO=false; MEDIR=0; SO_LIGAR=false; SO_BOTS=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --bases) BASES="$2"; shift 2;;
    --kits) KITS="$2"; shift 2;;
    --mundo-novo) MUNDO_NOVO=true; shift;;
    --so-ligar) SO_LIGAR=true; shift;;
    --so-bots) SO_BOTS='--so-bots'; shift;;
    --medir) MEDIR="$2"; shift 2;;
    *) echo "opcao desconhecida: $1" >&2; exit 2;;
  esac
done
COPIA="$(cd "$COPIA" && pwd)"
[[ -f "$COPIA/HOSHIESTAR-TEST-ENV.txt" ]] || { echo "ERRO: nao e copia de teste" >&2; exit 1; }
grep -q '^server-ip=127.0.0.1' "$COPIA/server.properties" || { echo "ERRO: server.properties fora de 127.0.0.1" >&2; exit 1; }

rodando_aqui() {   # ha java desta copia?
  local p
  for p in $(pgrep -f 'server.jar nogui' || true); do
    [[ "$(readlink "/proc/$p/cwd" 2>/dev/null)" == "$COPIA" ]] && return 0
  done
  return 1
}

if ! rodando_aqui; then
  if pgrep -f 'server.jar nogui' >/dev/null; then
    echo "ERRO: ha OUTRO servidor de teste ligado. Espere (um por vez)." >&2; exit 1
  fi
  if $MUNDO_NOVO; then rm -rf "$COPIA/world-test" "$COPIA/hoshiestar"/medicao-* "$COPIA"/carga-*; fi
  if [[ ! -d "$COPIA/world-test" ]]; then
    # mundo plano: construcao previsivel (superficie y=3), geracao barata, sem monstros que sujem a medida
    sed -i -e 's/^level-type=.*/level-type=FLAT/' -e 's/^generator-settings=.*/generator-settings=2;7,2x3,2;1;/' \
           -e 's/^spawn-monsters=.*/spawn-monsters=false/' -e 's/^spawn-animals=.*/spawn-animals=false/' \
           -e 's/^spawn-npcs=.*/spawn-npcs=false/' -e 's/^allow-flight=.*/allow-flight=true/' "$COPIA/server.properties"
  fi
  rm -f "$COPIA/console.log"
  (nohup "$RAIZ/scripts/run-test-server.sh" "$COPIA" 8G > "$COPIA/run.log" 2>&1 &)
  echo "ligando a copia; esperando o 'Done' (ate 15 min)..."
  for _ in $(seq 1 90); do
    grep -q "Done (" "$COPIA/console.log" 2>/dev/null && break
    sleep 10
  done
  grep -q "Done (" "$COPIA/console.log" || { echo "ERRO: sem 'Done' em 15 min (veja o CLAUDE.md sobre copia travada)" >&2; exit 1; }
  grep -q '^level-type=FLAT' "$COPIA/server.properties" || echo "AVISO: o mundo NAO ficou plano (o 1o boot de uma copia nova pode regravar o server.properties); pare, rode de novo com --mundo-novo." >&2
  sleep 20   # deixa o pos-boot (reconstrucao de receitas de mods) terminar
fi

$SO_LIGAR && { echo "copia ligada (vazia). Pronto."; exit 0; }
python3 -I "$RAIZ/tools/carga_sintetica.py" aplicar --copia "$COPIA" --layout "$LAYOUT" --bases "$BASES" --kits "$KITS" $SO_BOTS

if [[ "$MEDIR" -gt 0 ]]; then
  sleep 30
  echo "hoshie medir $MEDIR" >> "$COPIA/console.in"
  sleep $((MEDIR + 10))
  ULT="$(ls -t "$COPIA"/hoshiestar/medicao-*.json | head -1)"
  echo "medicao: $ULT"
  if [[ -z "$SO_BOTS" ]]; then
    python3 -I "$RAIZ/tools/carga_sintetica.py" conferir --plano "$COPIA/carga-plano.json" --medicao "$ULT" || true
  fi
fi
echo "pronto. Servidor e bots seguem ligados; para parar: $AQUI/parar-carga-sintetica.sh $COPIA"
