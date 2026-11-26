#!/usr/bin/env python3
"""Hoshiestar - conta itens e energia de um mundo SALVO (arquivos .mca), numa caixa de blocos.

Faz offline o que 'hoshie regioes contar' faz no jogo (so existe com o orcamento ligado): soma, por tipo (id:dano), os
itens que aparecem no NBT das tile entities (baus, funis, fornalhas, canos com item em transito...) e dos itens no
chao (entidade 'Item'); e soma as chaves de energia do NBT das tiles (electricityStored, energy, Energy; troque com
--chaves). Serve de controle com o recurso DESLIGADO.

Uso:
  mundo_contar.py <pasta-do-mundo> [--caixa x0,z0,x1,z1] [--dim 0] [--chaves a,b] [--saida arquivo]
  mundo_contar.py diferenca <arq-antes> <arq-depois>      # compara duas saidas; imprime tipo a tipo; codigo 0 se zero

Cuidados: so le; rode depois de 'save-all' (espera a gravacao) ou com o servidor parado. A energia gravada de uma
celula da matriz de inducao do Mekanism pode estar velha (a celula nao marca o chunk como modificado, DIAGNOSTICO D do
Mekanism): para a matriz use o livro-caixa ('hoshie mekanism ... mais ...'). Numero de carga sintetica e indicacao.
"""
import argparse
import math
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mundo_nbt as N  # noqa: E402

CHAVES = "electricityStored,energy,Energy"


def varrer(v, itens, energia, chaves):
    if isinstance(v, dict):
        if "id" in v and "Count" in v and isinstance(v["Count"], int):
            i = v["id"]
            chave = "%s:%s" % (i, v.get("Damage", 0))
            itens[chave] = itens.get(chave, 0) + v["Count"]
        for k, f in v.items():
            if k in chaves and isinstance(f, (int, float)) and not isinstance(f, bool):
                energia[k] = energia.get(k, 0.0) + float(f)
            elif isinstance(f, (dict, list)):
                varrer(f, itens, energia, chaves)
    elif isinstance(v, list):
        for x in v:
            if isinstance(x, (dict, list)):
                varrer(x, itens, energia, chaves)


def contar(mundo, caixa=None, dim=0, chaves=CHAVES):
    chaves = set(c.strip() for c in chaves.split(",") if c.strip())
    itens, energia = {}, {}
    tiles = chao = chunks = 0

    def dentro(x, z):
        return caixa is None or (caixa[0] <= x <= caixa[2] and caixa[1] <= z <= caixa[3])

    def chunk_toca(cx, cz):
        return caixa is None or not (cx * 16 + 15 < caixa[0] or cx * 16 > caixa[2] or cz * 16 + 15 < caixa[1] or cz * 16 > caixa[3])

    for cx, cz, lv in N.todos_os_chunks(mundo, dim, chunk_toca):
        chunks += 1
        for t in lv.get("TileEntities", []):
            if dentro(t.get("x", 0), t.get("z", 0)):
                varrer(t, itens, energia, chaves)
                tiles += 1
        for e in lv.get("Entities", []):
            if e.get("id") == "Item" and "Item" in e:
                p = e.get("Pos", [0, 0, 0])
                if dentro(math.floor(p[0]), math.floor(p[2])):
                    varrer(e["Item"], itens, energia, chaves)
                    chao += 1
    return itens, energia, tiles, chao, chunks


def escrever(f, itens, energia, tiles, chao, chunks, nomes):
    f.write("offline chunks %d tiles %d itens_no_chao %d\n" % (chunks, tiles, chao))
    for k in sorted(itens, key=lambda s: (int(s.split(":")[0]) if s.split(":")[0].lstrip("-").isdigit() else 0, s)):
        i = k.split(":")[0]
        nome = nomes.get(int(i), "") if i.lstrip("-").isdigit() else ""
        f.write("item %s %d %s\n" % (k, itens[k], nome))
    for k in sorted(energia):
        f.write("energia %s %.3f\n" % (k, energia[k]))
    f.write("totais itens=%d energia=%.3f\n" % (sum(itens.values()), sum(energia.values())))


def ler_saida(caminho):
    itens, energia = {}, {}
    for l in open(caminho):
        p = l.split()
        if p and p[0] == "item":
            itens[p[1]] = int(p[2])
        elif p and p[0] == "energia":
            energia[p[1]] = float(p[2])
    return itens, energia


def diferenca(a, b, rel=1e-9):
    ia, ea = ler_saida(a)
    ib, eb = ler_saida(b)
    dif = 0
    for k in sorted(set(ia) | set(ib)):
        d = ib.get(k, 0) - ia.get(k, 0)
        if d:
            dif += 1
            print("item %s antes %d depois %d dif %+d" % (k, ia.get(k, 0), ib.get(k, 0), d))
    for k in sorted(set(ea) | set(eb)):
        x, y = ea.get(k, 0.0), eb.get(k, 0.0)
        if abs(y - x) > rel * max(abs(x), abs(y), 1.0):
            dif += 1
            print("energia %s antes %.3f depois %.3f dif %+.3f" % (k, x, y, y - x))
    print("tipos de item: antes %d depois %d; soma antes %d depois %d; energia antes %.3f depois %.3f; tipos com diferenca: %d"
          % (len(ia), len(ib), sum(ia.values()), sum(ib.values()), sum(ea.values()), sum(eb.values()), dif))
    return dif


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "diferenca":
        sys.exit(1 if diferenca(sys.argv[2], sys.argv[3]) else 0)
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mundo")
    ap.add_argument("--caixa")
    ap.add_argument("--dim", type=int, default=0)
    ap.add_argument("--chaves", default=CHAVES)
    ap.add_argument("--saida")
    a = ap.parse_args()
    caixa = N.caixa_de_texto(a.caixa) if a.caixa else None
    itens, energia, tiles, chao, chunks = contar(a.mundo, caixa, a.dim, a.chaves)
    nomes = N.mapa_itens(a.mundo)
    f = open(a.saida, "w") if a.saida else sys.stdout
    escrever(f, itens, energia, tiles, chao, chunks, nomes)
    if a.saida:
        f.close()


if __name__ == "__main__":
    main()
