#!/usr/bin/env python3
"""Hoshiestar - resume uma execucao de tools/b_roteiro.py (teste B1, conservacao): tabela das fotos e diferenca de
cada foto contra a primeira (t0), tipo a tipo, mais os invariantes de fornalha (minerio + lingote de ferro).

Uso: b_resumo.py <pasta-da-execucao> [<pasta-da-execucao> ...]
So le arquivos. Numero de carga sintetica e indicacao, nao medicao de producao. Nao conclui nada.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mundo_contar as MC  # noqa: E402


def resumo(pasta):
    r = json.load(open(os.path.join(pasta, "resumo.json")))
    fotos = r["fotos"]
    base = None
    print("### %s" % os.path.basename(pasta.rstrip("/")))
    print("| foto | tick do mundo | livro-caixa (J) | dif livro vs t0 (J) | itens (soma) | tipos | ferro: minerio+lingote | carvao | electricityStored NBT (J, fechada) | energy+Energy NBT (J, motores/cubos IC2-CoFH: nao fechada) |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for f in fotos:
        off = os.path.join(pasta, "snap-" + f["rot"], "offline.txt")
        if not os.path.exists(off):
            continue
        it, en = MC.ler_saida(off)
        if base is None:
            base = (f, it, en)
        ferro = it.get("15:0", 0) + it.get("265:0", 0)
        livro = f["livro"]
        dl = (livro - base[0]["livro"]) if (livro is not None and base[0]["livro"] is not None) else None
        print("| %s | %d | %s | %s | %d | %d | %d | %d | %.0f | %.0f |" % (
            f["rot"], f["tick_mundo"], "%.1f" % livro if livro is not None else "-", ("%+.1f" % dl) if dl is not None else "-",
            sum(it.values()), len(it), ferro, it.get("263:0", 0), en.get("electricityStored", 0.0),
            en.get("energy", 0.0) + en.get("Energy", 0.0)))
    print()
    if base:
        print("diferenca de itens por tipo contra t0 (so tipos que mudaram; '15:0' minerio de ferro, '265:0' lingote, '263:0' carvao):")
        for f in fotos[1:]:
            off = os.path.join(pasta, "snap-" + f["rot"], "offline.txt")
            if not os.path.exists(off):
                continue
            it, en = MC.ler_saida(off)
            dif = ["%s %+d" % (k, it.get(k, 0) - base[1].get(k, 0)) for k in sorted(set(it) | set(base[1]), key=lambda s: int(s.split(":")[0]))
                   if it.get(k, 0) != base[1].get(k, 0)]
            print("- %s: %s" % (f["rot"], ", ".join(dif) if dif else "NENHUMA (todos os tipos iguais ao t0)"))
    print()


if __name__ == "__main__":
    for p in sys.argv[1:]:
        resumo(p)
