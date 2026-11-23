#!/usr/bin/env bash
# Hoshiestar - gera os .patch das classes do Minecraft/Forge com a tarefa do upstream (genPatches).
#
# Uso:
#   servidor/gerar-patches.sh <workspace-do-fork> [--github-raw] [--mods <pasta mods/>]
#
# --mods: a pasta mods/ de um servidor de teste. O build do Crucible compila os ganchos de src/mods/java contra
# esses jars e para sem ela; o script a repassa ao Gradle como -Phoshiestar.mods (sem precisar de GRADLE_OPTS).
# Sem a opcao, usa HOSHIE_MODS se estiver definido.
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
shift
INIT=()
MODS="${HOSHIE_MODS:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --github-raw) INIT=(-I "$AQUI/ferramentas/github-raw.init.gradle"); shift ;;
    --mods) [[ -d "${2:-}" ]] || { echo "ERRO: --mods precisa de uma pasta (recebeu: '${2:-}')" >&2; exit 2; }
            MODS="$(cd "$2" && pwd)"; shift 2 ;;
    *) echo "opcao desconhecida: $1 (use --github-raw e/ou --mods <pasta mods/>)" >&2; exit 2 ;;
  esac
done
MODSARG=()
if [[ -n "$MODS" ]]; then
  [[ -d "$MODS" ]] || { echo "ERRO: pasta de mods nao existe: $MODS" >&2; exit 2; }
  MODSARG=(-Phoshiestar.mods="$MODS")
fi
export JAVA_HOME="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
export LC_ALL=C.UTF-8 LANG=C.UTF-8
unset _JAVA_OPTIONS
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dfile.encoding=UTF-8"
cd "$W"
./gradlew "${INIT[@]}" "${MODSARG[@]}" -Dorg.gradle.jvmargs=-Xmx4g --no-daemon genPatches

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
