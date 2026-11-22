#!/usr/bin/env bash
# Hoshiestar - monta a COPIA DE TESTE do servidor a partir do pacote de producao.
#
# Regra maxima do projeto: nunca mexer no servidor real. Este script so le o
# pacote original e escreve numa pasta nova. Nada e apagado: plugins retirados
# vao para plugins/!DISABLED-HOSHIESTAR/ e podem ser devolvidos a mao.
#
# Uso:
#   scripts/prepare-test-env.sh <pacote.tar.gz | pasta-do-servidor> <pasta-destino> [--keep-noise]
#
#   --keep-noise   mantem os plugins da lista "RUIDO" (ClearLag, anuncios...).
#                  Use para medir um perfil mais parecido com producao.
#
# Variaveis:
#   HOSHIE_ACCEPT_EULA=true   escreve eula.txt (voce declara que aceita a EULA da Mojang).
#   HOSHIE_PORT=25565         porta do servidor de teste (sempre preso a 127.0.0.1).
set -euo pipefail

SRC="${1:?informe o pacote .tar.gz ou a pasta do servidor}"
DEST="${2:?informe a pasta de destino (precisa nao existir ou estar vazia)}"
KEEP_NOISE="false"
[[ "${3:-}" == "--keep-noise" ]] && KEEP_NOISE="true"
PORT="${HOSHIE_PORT:-25565}"

# --- Plugins que SEMPRE saem: falam com servicos reais ou barram conexao local.
# formato: "trecho-do-nome-do-jar|motivo"
ALWAYS=(
  "CastleOfStone|anti-cheat; expulsa clientes/bots de teste (pedido do admin)"
  "BungeeGuard|exige token do proxy; barra conexao direta (pedido do admin)"
  "ProtocolObelisk|rota de protocolo ligada ao proxy Velocity"
  "DiscordSRV|usa o bot real do Discord (leva junto o Akashic-DiscordSRV-Legacy-NMS-Fix)"
  "LiteBans|banco e webhooks de punicao de producao"
  "NuVotifier|abre porta de votos"
  "PixCraft|pagamentos PIX reais"
  "PixelVip|ativacao de VIP ligada a pagamento"
  "mRedeem|resgate de codigos ligado a loja"
  "StatsAPI|banco externo de estatisticas"
  "pvpstats|configurado para MySQL de producao"
  "FinalConsoleFilter|esconde linhas do console que precisamos ver nos testes"
)

# --- Plugins de RUIDO: nao sao perigosos, mas mudam o resultado dos testes.
NOISE=(
  "ClearLagg|apaga entidades/itens; quebra teste de conservacao de itens"
  "Broadcast|mensagens automaticas no chat"
  "LiteAnnouncer|mensagens automaticas no chat"
)

log() { printf '[prepare-test-env] %s\n' "$*"; }

if [[ -e "$DEST" && -n "$(ls -A "$DEST" 2>/dev/null)" ]]; then
  echo "ERRO: destino '$DEST' ja existe e nao esta vazio. Escolha outra pasta." >&2
  exit 1
fi
mkdir -p "$DEST"

if [[ -d "$SRC" ]]; then
  log "copiando pasta $SRC -> $DEST"
  cp -a "$SRC"/. "$DEST"/
else
  log "extraindo $SRC -> $DEST"
  tar --no-same-owner -xzf "$SRC" -C "$DEST"
fi

if [[ ! -f "$DEST/server.jar" || ! -d "$DEST/mods" || ! -d "$DEST/plugins" ]]; then
  echo "ERRO: '$DEST' nao parece um servidor (faltam server.jar, mods/ ou plugins/)." >&2
  exit 1
fi

OFF="$DEST/plugins/!DISABLED-HOSHIESTAR"
mkdir -p "$OFF"
REPORT="$DEST/HOSHIESTAR-TEST-ENV.txt"
{
  echo "Copia de teste Hoshiestar - gerada em $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Origem: $(basename "$SRC")"
  echo
  echo "Plugins desligados (movidos para plugins/!DISABLED-HOSHIESTAR/):"
} > "$REPORT"

disable() {
  local entry prefix reason found
  for entry in "$@"; do
    prefix="${entry%%|*}"; reason="${entry#*|}"; found="false"
    while IFS= read -r -d '' jar; do
      mv "$jar" "$OFF/"; found="true"
      log "desligado: $(basename "$jar") ($reason)"
      echo "  - $(basename "$jar") :: $reason" >> "$REPORT"
    done < <(find "$DEST/plugins" -maxdepth 1 -type f -name "*${prefix}*.jar" -print0)
    [[ "$found" == "true" ]] || log "aviso: nenhum jar contendo '$prefix' (ok se ja nao existe)"
  done
}

disable "${ALWAYS[@]}"
if [[ "$KEEP_NOISE" == "false" ]]; then disable "${NOISE[@]}"; fi

# LuckPerms: producao usa MySQL; teste usa o banco local H2 que ja vem no pacote.
LP="$DEST/plugins/LuckPerms/config.yml"
if [[ -f "$LP" ]] && grep -qE '^storage-method:\s*mysql' "$LP"; then
  sed -i -E 's/^storage-method:\s*mysql.*/storage-method: h2/' "$LP"
  log "LuckPerms: storage-method mysql -> h2 (local)"
  echo "Ajuste: LuckPerms storage-method mysql -> h2" >> "$REPORT"
fi

# Mods que ligam para fora: no teste nao podem alcancar a infraestrutura de producao.
VOICE="$DEST/config/akashicvoice.cfg"
if [[ -f "$VOICE" ]] && grep -qE '^\s*S:gatewayHost=' "$VOICE"; then
  sed -i -E 's/^(\s*S:gatewayHost=).*/\1127.0.0.1/' "$VOICE"
  log "akashicvoice: gatewayHost -> 127.0.0.1 (nao conecta no gateway de voz de producao)"
  echo "Ajuste: akashicvoice gatewayHost -> 127.0.0.1" >> "$REPORT"
fi
FM="$DEST/config/akashicfm.cfg"
if [[ -f "$FM" ]] && grep -qE '^\s*B:autoInstallTools=true' "$FM"; then
  sed -i -E 's/^(\s*B:autoInstallTools=).*/\1false/' "$FM"
  log "akashicfm: autoInstallTools -> false (nao baixa binarios durante o teste)"
  echo "Ajuste: akashicfm autoInstallTools -> false" >> "$REPORT"
fi

# Agentes Java ligados ao proxy nao entram no teste (ficam na pasta, so nao sao carregados).
echo "Agentes Java NAO carregados no teste: Akashic-RGB-Transport (depende do proxy Velocity)." >> "$REPORT"

# server.properties de teste: offline (bots), preso ao loopback, mundo separado.
# A semente do mundo e fixa para os boots serem repetiveis: com semente aleatoria,
# um boot ja travou gerando uma arvore do Thaumcraft no spawn (DIAGNOSTICO.md D-29).
# Outra semente: HOSHIE_SEED=<numero>.
if [[ ! -f "$DEST/server.properties" ]]; then
  cat > "$DEST/server.properties" <<EOF
# Gerado por scripts/prepare-test-env.sh - SOMENTE TESTE
server-ip=127.0.0.1
server-port=$PORT
online-mode=false
level-name=world-test
level-seed=${HOSHIE_SEED:--8447269374814014699}
motd=Hoshiestar TEST
max-players=250
view-distance=8
spawn-protection=0
enable-rcon=false
enable-query=false
snooper-enabled=false
white-list=false
max-tick-time=-1
EOF
  log "server.properties de teste criado (127.0.0.1:$PORT, online-mode=false)"
fi

if [[ "${HOSHIE_ACCEPT_EULA:-false}" == "true" ]]; then
  echo "eula=true" > "$DEST/eula.txt"
  log "eula.txt escrito (HOSHIE_ACCEPT_EULA=true)"
fi

log "pronto. Relatorio: $REPORT"
