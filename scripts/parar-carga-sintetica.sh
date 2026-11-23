#!/usr/bin/env bash
# Para os bots da carga sintetica e o servidor da copia de teste, com "stop" (nunca kill).
# Uso: scripts/parar-carga-sintetica.sh <pasta-da-copia>
set -euo pipefail
COPIA="$(cd "${1:?informe a pasta da copia}" && pwd)"
[[ -f "$COPIA/HOSHIESTAR-TEST-ENV.txt" ]] || { echo "ERRO: nao e copia de teste" >&2; exit 1; }
echo SAIR >> "$COPIA/carga-bots.cmds" 2>/dev/null || true
sleep 3
echo stop >> "$COPIA/console.in"
for _ in $(seq 1 100); do
  achou=false
  for p in $(pgrep -f 'server.jar nogui' || true); do
    [[ "$(readlink "/proc/$p/cwd" 2>/dev/null)" == "$COPIA" ]] && achou=true
  done
  $achou || break
  sleep 3
done
$achou && { echo "AINDA LIGADO apos 5 min: nao use kill sem os 2 thread dumps (CLAUDE.md)"; exit 1; }
echo "copia parada."
