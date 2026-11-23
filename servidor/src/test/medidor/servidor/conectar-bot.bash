#!/bin/bash
# uso: conectar-bot.bash <saida> <test_bot.py> <modlist> [porta] : conecta o bot de teste (ate 4 tentativas; a 1a costuma
# cair logo depois do boot) e espera o "join game". So para o servidor de TESTE local (offline).
# Entre tentativas mata so o bot que ESTE script lancou (o PID gravado em <saida>/bot.pid), nunca o de outra faixa.

B=$1; BOT=$2; ML=$3; P=${4:-25565}; mkdir -p "$B"; : > "$B/cmds.txt"
for t in 1 2 3 4; do
  nohup python3 -I "$BOT" "$P" HoshieBot "$ML" "$B/cmds.txt" "$B/bot.log" > "$B/bot.out" 2>&1 &
  echo $! > "$B/bot.pid"; disown
  for i in $(seq 1 20); do grep -q "join game" "$B/bot.log" 2>/dev/null && { echo "bot conectado (tentativa $t, pid $(cat "$B/bot.pid"))"; exit 0; }; sleep 1; done
  kill "$(cat "$B/bot.pid")" 2>/dev/null; sleep 5; mv "$B/bot.log" "$B/bot-tent$t.log" 2>/dev/null
done
echo "BOT NAO CONECTOU"; exit 1
