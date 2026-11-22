#!/usr/bin/env bash
# Hoshiestar - gera os .patch das classes do Minecraft/Forge com a tarefa do upstream (genPatches).
#
# Uso:
#   servidor/gerar-patches.sh <workspace-do-fork> [--github-raw]
#
# Fluxo do upstream: edite o fonte decompilado em eclipse/cauldron/src/main/java/... (marcando as
# linhas com "// Hoshiestar") e rode este script. Ele roda "./gradlew genPatches", que reescreve
# patches/**/*.patch comparando eclipse/Clean (o original) com eclipse/cauldron (o editado).
#
# Detalhe: alguns .patch do upstream tem cabecalhos de bloco ("@@ -a,b +c,d @@") com contagens que o
# genPatches recalcula; ele reescreve esses arquivos mesmo sem mudanca de conteudo (hoje:
# RegionFileCache.java.patch). Este script desfaz (git checkout) os .patch cuja diferenca e SO de
# cabecalho de bloco, para o commit conter apenas os patches que voce mudou. Nada e editado a mao.
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
W="$(cd "${1:?informe o workspace do fork}" && pwd)"
INIT=()
[[ "${2:-}" == "--github-raw" ]] && INIT=(-I "$AQUI/ferramentas/github-raw.init.gradle")
export JAVA_HOME="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
export LC_ALL=C.UTF-8 LANG=C.UTF-8
unset _JAVA_OPTIONS
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dfile.encoding=UTF-8"
cd "$W"
./gradlew "${INIT[@]}" -Dorg.gradle.jvmargs=-Xmx4g --no-daemon genPatches

for f in $(git diff --name-only -- patches/); do
  # linhas mudadas que nao sao cabecalho de bloco (grep -c le tudo: sem SIGPIPE com pipefail)
  n="$(git diff -U0 -- "$f" | grep '^[-+]' | grep -v '^+++ \|^--- ' | grep -cv '^[-+]@@' || true)"
  if [[ "${n:-0}" -gt 0 ]]; then
    echo "[gerar-patches] mudou: $f"
  else
    git checkout -q -- "$f"
    echo "[gerar-patches] so cabecalho (desfeito): $f"
  fi
done
