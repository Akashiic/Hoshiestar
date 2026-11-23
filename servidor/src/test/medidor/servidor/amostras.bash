#!/bin/bash
# uso: amostras.bash <pasta da copia> <n> <intervalo_s> <rotulo> : n leituras do 'forge tps' (media dos ultimos 100 ticks)
D=$1; N=$2; I=$3; R=$4; L=$D/logs/latest.log
for i in $(seq 1 $N); do
  c=$(grep -a -c "Overall" $L)
  echo "forge tps" >> $D/console.in
  until [ $(grep -a -c "Overall" $L) -gt $c ]; do sleep 0.5; done
  echo "$R $(date +%T) $(grep -a "Overall" $L | tail -1 | sed 's/.*Overall *: *//')"
  [ $i -lt $N ] && sleep $I
done
