#!/usr/bin/env bash
# Hoshiestar - liga o servidor de TESTE (pasta criada por prepare-test-env.sh).
#
# Uso:
#   scripts/run-test-server.sh <pasta-teste> [memoria-maxima, ex. 9G]
#
# Console: escreva comandos no arquivo <pasta-teste>/console.in, um por linha:
#   echo "forge tps" >> <pasta-teste>/console.in
#   echo "stop"      >> <pasta-teste>/console.in
# Saida do servidor: <pasta-teste>/console.log
#
# Sempre pare com "stop". Matar o processo (SIGTERM/kill) derruba o laco de tick
# com erro neste Crucible (ver DIAGNOSTICO.md, achado D-07).
set -euo pipefail

DIR="${1:?informe a pasta do servidor de teste}"
XMX="${2:-9G}"
cd "$DIR"

if [[ ! -f HOSHIESTAR-TEST-ENV.txt ]]; then
  echo "ERRO: '$DIR' nao foi criada por prepare-test-env.sh. Recuso ligar um servidor que nao e de teste." >&2
  exit 1
fi
if ! grep -q '^server-ip=127.0.0.1' server.properties; then
  echo "ERRO: server.properties nao esta preso em 127.0.0.1." >&2
  exit 1
fi

# O Crucible baixa 73 bibliotecas no primeiro boot. Tres delas vem de enderecos que
# podem falhar atras de proxy; aqui buscamos de enderecos equivalentes e gravamos o
# arquivo .md5 que o Crucible usa para aceitar a biblioteca sem baixar de novo.
fetch_lib() {
  local rel="$1" url="$2" dest="libraries/$1"
  if [[ -s "$dest" && -s "$dest.md5" ]]; then return 0; fi
  mkdir -p "$(dirname "$dest")"
  echo "[run-test-server] baixando $(basename "$dest")"
  curl -fsSL --retry 3 -o "$dest" "$url"
  md5sum "$dest" | cut -d' ' -f1 | tr -d '\n' > "$dest.md5"
}
MVN="https://raw.githubusercontent.com/juanmuscaria/maven/master"
fetch_lib "io/github/cruciblemc/launchwrapper/1.13/launchwrapper-1.13.jar" \
          "$MVN/io/github/cruciblemc/launchwrapper/1.13/launchwrapper-1.13.jar"
fetch_lib "pw/prok/KImagine/0.2.0/KImagine-0.2.0.jar" \
          "$MVN/ThermosLibs/pw/prok/KImagine/0.2.0/KImagine-0.2.0.jar"
# Jar oficial do servidor 1.7.10, direto da Mojang (nao fica no repositorio).
fetch_lib "net/minecraft/server/1.7.10/server-1.7.10.jar" \
          "https://piston-data.mojang.com/v1/objects/952438ac4e01b4d115c5fc38f891710c4941df29/server.jar"
echo "952438ac4e01b4d115c5fc38f891710c4941df29  libraries/net/minecraft/server/1.7.10/server-1.7.10.jar" | sha1sum -c --quiet -

# No primeiro boot o Crucible so instala as bibliotecas e pede para ligar de novo.
for attempt in 1 2; do
  : > console.in
  echo "[run-test-server] ligando (Xmx=$XMX, tentativa $attempt). Log: $DIR/console.log"
  tail -f console.in | java @java9args.txt -Xms4G "-Xmx$XMX" -jar server.jar nogui > console.log 2>&1 || true
  pkill -f "tail -f console.in" 2>/dev/null || true
  grep -q "A restart is required to be able to boot" console.log || break
  echo "[run-test-server] bibliotecas instaladas; ligando de novo."
done
echo "[run-test-server] servidor encerrado."
