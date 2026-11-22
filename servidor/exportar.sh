#!/usr/bin/env bash
# Hoshiestar - exporta o workspace do fork para servidor/ (o que vai para o repositorio).
#
# Uso:
#   servidor/exportar.sh <workspace-do-fork>
#
# Le os COMMITS do workspace (git) desde o commit do upstream (servidor/UPSTREAM) e escreve:
#   servidor/patches/  um "git format-patch" so com as mudancas em arquivos que JA existem no upstream
#                      (inclusive os .patch do Crucible gerados com genPatches);
#   servidor/src/      os arquivos NOVOS (que nao existem no upstream), nos mesmos caminhos da arvore.
# Regras:
#   - a arvore do workspace tem de estar limpa (tudo commitado): so commits sao exportados;
#   - todo arquivo novo tem de ficar dentro de src/ (ex.: src/main/java/hoshiestar/...);
#   - arquivo do upstream apagado ou renomeado nao e suportado (falha com mensagem).
# Deterministico: rodar duas vezes sobre os mesmos commits nao muda nada (format-patch com
# --zero-commit e --no-signature; os arquivos sao recriados do zero).
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
W="$(cd "${1:?informe o workspace do fork}" && pwd)"
UP_COMMIT="$(sed -n 's/^commit=//p' "$AQUI/UPSTREAM")"
cd "$W"

git rev-parse --git-dir >/dev/null 2>&1 || { echo "ERRO: $W nao e um repositorio git" >&2; exit 2; }
git merge-base --is-ancestor "$UP_COMMIT" HEAD || { echo "ERRO: HEAD nao descende de $UP_COMMIT" >&2; exit 2; }
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "ERRO: ha mudancas nao commitadas no workspace; faca o commit antes de exportar:" >&2
  git status --short --untracked-files=no >&2
  exit 2
fi

APAGADOS="$(git diff --no-renames --name-only --diff-filter=D "$UP_COMMIT" HEAD)"
[[ -z "$APAGADOS" ]] || { echo "ERRO: arquivos do upstream apagados (nao suportado):" >&2; echo "$APAGADOS" >&2; exit 2; }
NOVOS="$(git diff --no-renames --name-only --diff-filter=A "$UP_COMMIT" HEAD | LC_ALL=C sort)"
FORA="$(echo "$NOVOS" | grep -v '^src/' | grep -v '^$' || true)"
[[ -z "$FORA" ]] || { echo "ERRO: arquivo novo fora de src/ (coloque em src/ ou transforme em patch):" >&2; echo "$FORA" >&2; exit 2; }

# patches: tudo menos os arquivos novos
EXCLUI=()
while IFS= read -r f; do
  [[ -n "$f" ]] && EXCLUI+=(":(exclude,literal)$f")
done <<< "$NOVOS"
rm -rf "$AQUI/patches"
mkdir -p "$AQUI/patches"
git -c core.abbrev=8 format-patch -q --zero-commit --no-signature --no-renames --no-stat \
  -o "$AQUI/patches" "$UP_COMMIT..HEAD" -- . "${EXCLUI[@]}" >/dev/null

# arquivos novos
rm -rf "$AQUI/src"
mkdir -p "$AQUI/src"
n=0
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  mkdir -p "$AQUI/$(dirname "$f")"
  git show "HEAD:$f" > "$AQUI/$f"
  n=$((n + 1))
done <<< "$NOVOS"

echo "[exportar] $(ls "$AQUI/patches" | wc -l) patch(es) em servidor/patches, $n arquivo(s) novo(s) em servidor/src"
