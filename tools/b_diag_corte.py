#!/usr/bin/env python3
"""Hoshiestar - diagnostico curto da Etapa 2.5 (teste B1): com a regiao A em corte, quantos ticks cada CLASSE de tile
recebe (medidor 'hoshie medir') comparado com o mesmo servidor sem corte. Liga a copia (restaure o mundo-base antes),
mede 40 s sem corte, forca a regiao A a estourar (150 ms), espera o corte, mede 40 s de novo, para com stop.
Uso: b_diag_corte.py <copia> <saida>. Saida: medicao-sem-corte.json, medicao-com-corte.json, eventos.txt.
Numero de carga sintetica e indicacao, nao medicao de producao."""
import os
import shutil
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import b_roteiro as R  # noqa: E402


def main():
    d, saida = sys.argv[1], os.path.abspath(sys.argv[2])
    c = R.Copia(d, saida)
    c.ligar(True, "medir", True)
    c.bot()
    time.sleep(45)
    ida, l = c.id_a()
    c.log("regiao A: %s %s" % (ida, l))

    def medir(nome):
        antes = set(os.listdir(os.path.join(c.d, "hoshiestar")))
        for x in c.cmd("hoshie medir 40", 3):
            c.log("medir: " + x[:200])
        time.sleep(55)
        novos = [p for p in os.listdir(os.path.join(c.d, "hoshiestar")) if p.startswith("medicao-") and p not in antes]
        for p in novos:
            shutil.copy(os.path.join(c.d, "hoshiestar", p), os.path.join(saida, nome))
        c.log("%s: %s" % (nome, novos))
    medir("medicao-sem-corte.json")
    c.cmd("hoshie regioes teste %d 150" % ida, 3)
    c.cmd("hoshie regioes modo cortar", 3)
    time.sleep(40)
    for x in c.cmd("hoshie regioes %d" % ida, 4)[:2]:
        c.log("regiao A: " + x.strip()[:200])
    medir("medicao-com-corte.json")
    c.cmd("hoshie regioes teste parar", 3)
    c.bot_sair()
    c.parar()
    open(os.path.join(saida, "eventos.txt"), "w").write("\n".join(c.eventos) + "\n")


if __name__ == "__main__":
    main()
