#!/usr/bin/env python3
"""Hoshiestar - comandos de console para os testes do medidor no servidor de TESTE.

Uso: python3 gerar-comandos.py <pasta de saida>
Grava:
  t2.txt    104 funis (setblock ... hopper): 40 no chunk (4,18) [x 64..71, z 288..292] e 64 no chunk (4,15)
            [x 64..71, z 240..247], y 100 (o teste T2: contagem exata funis x ticks por chunk);
  t6.txt    2.048 funis em x 100..131, z 300..363, y 110 (carga do T5);
  porcos.txt 400 'summon Pig' em x 40..59, z 300..319, y 70 (carga do T5).
Mande para o console com: cat <arquivo> >> <copia>/console.in
"""
import os
import sys

saida = sys.argv[1] if len(sys.argv) > 1 else "."
os.makedirs(saida, exist_ok=True)
with open(os.path.join(saida, "t2.txt"), "w") as f:
    for x in range(64, 72):
        for z in list(range(288, 293)) + list(range(240, 248)):
            f.write("setblock %d 100 %d hopper\n" % (x, z))
with open(os.path.join(saida, "t6.txt"), "w") as f:
    for x in range(100, 132):
        for z in range(300, 364):
            f.write("setblock %d 110 %d hopper\n" % (x, z))
with open(os.path.join(saida, "porcos.txt"), "w") as f:
    for i in range(400):
        f.write("summon Pig %d 70 %d\n" % (40 + i % 20, 300 + i // 20))
