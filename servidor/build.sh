#!/usr/bin/env bash
# Hoshiestar - constroi o server.jar do Hoshiestar.
#
# Uso:
#   servidor/build.sh [opcoes]                   build COMPLETO: clona o upstream num workspace novo,
#                                                aplica servidor/ (patches + arquivos novos) e compila
#   servidor/build.sh --rapido <workspace> [...] build INCREMENTAL num workspace ja preparado
#
# Opcoes:
#   --upstream <url|caminho>  de onde clonar o Crucible (padrao: a url de servidor/UPSTREAM)
#   --work <pasta>            onde montar o workspace do build completo (padrao: ./hoshiestar-work);
#                             a pasta <work>/Crucible nao pode existir
#   --mods <pasta>            pasta com os jars dos mods, so para COMPILAR os ganchos de src/mods/java
#                             (ex.: a pasta mods/ de um servidor de teste). Obrigatoria se houver
#                             alguma fonte em src/mods/java (hoje ha: mekanism, betterquesting,
#                             arsmagica, chromaticraft): sem ela o build para com uma mensagem.
#                             Tambem vira HOSHIE_MODS do test.sh (se nao definido), para os testes com mods.
#   --github-raw              usa servidor/ferramentas/github-raw.init.gradle (proxy que recusa
#                             github.com/.../raw/...)
#   --limpo                   com --rapido: recompila tudo do projeto do servidor (saida igual a de um
#                             build completo; o incremental pode mudar nomes de depuracao)
#   --sem-testes              nao roda servidor/test.sh no fim
#
# Precisa de: git, JDK 8 (JAVA8_HOME, padrao /usr/lib/jvm/java-8-openjdk-amd64) e JDK 17 instalado
# (o buildSrc do Crucible procura um JDK 17 sozinho), rede para os repositorios maven do Crucible.
# Nada e editado a mao: os patches entram com "git am" e os arquivos novos sao copiados de servidor/src.
# Saida: build/distributions/Hoshiestar-1.7.10-<versao>-server.jar dentro do workspace.
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1090
UP_URL="$(sed -n 's/^url=//p' "$AQUI/UPSTREAM")"
UP_COMMIT="$(sed -n 's/^commit=//p' "$AQUI/UPSTREAM")"
UP_BRANCH="$(sed -n 's/^branch=//p' "$AQUI/UPSTREAM")"

UPSTREAM="$UP_URL"
WORK="$PWD/hoshiestar-work"
MODS=""
RAPIDO=""
INIT=""
LIMPO=0
TESTES=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --upstream) UPSTREAM="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --mods) MODS="$(cd "$2" && pwd)"; shift 2 ;;
    --rapido) RAPIDO="$(cd "$2" && pwd)"; shift 2 ;;
    --github-raw) INIT="$AQUI/ferramentas/github-raw.init.gradle"; shift ;;
    --limpo) LIMPO=1; shift ;;
    --sem-testes) TESTES=0; shift ;;
    -h|--help) sed -n '2,27p' "$0"; exit 0 ;;
    *) echo "opcao desconhecida: $1 (veja --help)" >&2; exit 2 ;;
  esac
done

J8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
[[ -x "$J8/bin/java" ]] || { echo "ERRO: JDK 8 nao encontrado em $J8 (defina JAVA8_HOME)" >&2; exit 2; }
export JAVA_HOME="$J8"
export LC_ALL=C.UTF-8 LANG=C.UTF-8
unset _JAVA_OPTIONS
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dfile.encoding=UTF-8"

gradle() {
  local args=(-Dorg.gradle.jvmargs=-Xmx4g --no-daemon)
  [[ -n "$INIT" ]] && args=(-I "$INIT" "${args[@]}")
  [[ -n "$MODS" ]] && args+=("-Phoshiestar.mods=$MODS")
  ./gradlew "${args[@]}" "$@"
}

if [[ -n "$RAPIDO" ]]; then
  W="$RAPIDO"
  [[ -f "$W/build.gradle.kts" && -d "$W/eclipse/cauldron" ]] || { echo "ERRO: $W nao e um workspace preparado (falta setupCrucible)" >&2; exit 2; }
  cd "$W"
  inicio=$(date +%s)
  if [[ $LIMPO -eq 1 ]]; then
    gradle :eclipse:cauldron:clean buildPackages
  else
    gradle buildPackages
  fi
  echo "[build] tempo do build rapido: $(( $(date +%s) - inicio )) s"
else
  [[ ! -e "$WORK/Crucible" ]] || { echo "ERRO: $WORK/Crucible ja existe; use outra --work ou --rapido" >&2; exit 2; }
  mkdir -p "$WORK"
  W="$WORK/Crucible"
  inicio=$(date +%s)
  echo "[build] clonando $UPSTREAM"
  git clone -q "$UPSTREAM" "$W"
  cd "$W"
  git checkout -q -B hoshiestar "$UP_COMMIT"
  git config core.abbrev 8
  git config user.name "Hoshiestar build"
  git config user.email "hoshiestar@localhost"
  echo "[build] aplicando $(ls "$AQUI"/patches/*.patch 2>/dev/null | wc -l) patch(es) de servidor/patches"
  if ls "$AQUI"/patches/*.patch >/dev/null 2>&1; then
    git am -q --committer-date-is-author-date "$AQUI"/patches/*.patch
  fi
  echo "[build] copiando os arquivos novos de servidor/src"
  cp -a "$AQUI/src/." "$W/src/"
  git add -A src
  GIT_AUTHOR_DATE="2026-01-01T00:00:00Z" GIT_COMMITTER_DATE="2026-01-01T00:00:00Z" \
    git commit -q -m "arquivos novos do Hoshiestar (servidor/src)"
  echo "[build] preparando o workspace (setupCrucible: baixa o Minecraft e aplica os patches do Forge/Crucible)"
  gradle setupCrucible
  gradle buildPackages
  echo "[build] tempo do build completo: $(( $(date +%s) - inicio )) s"
fi

JAR="$(ls "$W"/build/distributions/Hoshiestar-1.7.10-*-server.jar | head -1)"
echo "[build] jar: $JAR"
sha256sum "$JAR"
if [[ $TESTES -eq 1 ]]; then
  if [[ -n "$MODS" && -z "${HOSHIE_MODS:-}" ]]; then export HOSHIE_MODS="$MODS"; fi
  "$AQUI/test.sh" "$W"
fi
