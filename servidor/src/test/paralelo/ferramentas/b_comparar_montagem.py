#!/usr/bin/env python3
"""Hoshiestar - compara dois mundos salvos SO na montagem do Degrau B (passo 2): os blocos e as tile entities (NBT inteiro)
em cada posicao que b_monta_paralelo.py colocou (pisos, baus, baus de ender, mesas, funil, composters, barris), e, a
parte, tudo (blocos, tiles, entidades, ticks agendados) dentro dos chunks que contem a montagem.

Por que existe: o mundo plano do modpack tem estruturas de mod geradas no mundo (cristais do ChromatiCraft, que crescem
por sorteio; itens no chao em chunks fora da area do spawn, que carregam por tempos diferentes a cada boot). Duas
execucoes desligadas congeladas no mesmo tick diferem ali (achado do passo 2). O tools/mundo_comparar.py do repositorio
compara o mundo inteiro (e so exclui uma caixa); esta ferramenta reusa as funcoes dele e olha so onde a montagem esta.
Ela NAO substitui a comparacao do mundo inteiro: as duas saem no relatorio.

Uso: b_comparar_montagem.py <mundo-X> <mundo-Y> [--bases 16] [--espaco 64] [--hostil sim|nao] [--ferramentas <tools/>]
     (os mesmos --bases/--espaco/--hostil da montagem; o spawn vem do level.dat de X)
Saida 0 = zero diferencas nas posicoes da montagem.
"""
import argparse
import os
import sys

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
import b_monta_paralelo as monta  # noqa: E402


def posicoes(m):
    out = set()
    for c in m.cmds:
        p = c.split()
        out.add((int(p[1]), int(p[2]), int(p[3])))
    return out


def ler(N, MC, mundo, chunks):
    bl, tiles, ents, ticks = {}, {}, {}, {}
    for cx, cz, lv in N.todos_os_chunks(mundo, 0, lambda a, b: (a, b) in chunks):
        bl.update(MC.blocos(lv))
        for t in lv.get("TileEntities", []):
            tiles[(t.get("x"), t.get("y"), t.get("z"))] = t
        for i, e in enumerate(lv.get("Entities", [])):
            p = e.get("Pos", [0, 0, 0])
            ents[(e.get("id"), round(p[0], 2), round(p[1], 2), round(p[2], 2), i)] = e
        for t in lv.get("TileTicks", []):
            ticks[(t.get("x"), t.get("y"), t.get("z"), t.get("i"))] = t
    return bl, tiles, ents, ticks


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("x")
    ap.add_argument("y")
    ap.add_argument("--bases", type=int, default=16)
    ap.add_argument("--espaco", type=int, default=64)
    ap.add_argument("--hostil", choices=["sim", "nao"], default="sim")
    ap.add_argument("--ferramentas", default=os.environ.get("HOSHIE_TOOLS", "/home/user/Hoshiestar/tools"),
                    help="pasta tools/ do repositorio (mundo_nbt.py e mundo_comparar.py)")
    a = ap.parse_args()
    sys.path.insert(0, a.ferramentas)
    import mundo_nbt as N  # noqa: E402
    import mundo_comparar as MC  # noqa: E402
    dat = monta.ler_level_dat(os.path.join(a.x, "level.dat"))
    m, pos, host = monta.montar(a.bases, a.espaco, a.hostil == "sim", dat["SpawnX"], dat["SpawnZ"])
    P = posicoes(m)
    chunks = set((x >> 4, z >> 4) for x, y, z in P)
    bx, tx, ex, kx = ler(N, MC, a.x, chunks)
    by, ty, ey, ky = ler(N, MC, a.y, chunks)
    # 1) so as posicoes da montagem
    dif = []
    for p in sorted(P):
        if bx.get(p) != by.get(p):
            dif.append(("bloco", p, bx.get(p), by.get(p)))
        ta, tb = tx.get(p), ty.get(p)
        if (ta is None) != (tb is None) or (ta is not None and N.texto(ta) != N.texto(tb)):
            dif.append(("tile", p, ta and N.texto(ta)[:120], tb and N.texto(tb)[:120]))
    tiles_mont = sum(1 for p in P if p in tx)
    print("montagem: %d posicoes (%d com tile em X), %d chunks; diferencas nas posicoes: %d" % (len(P), tiles_mont, len(chunks), len(dif)))
    for d in dif[:40]:
        print("   ", d)
    # 2) tudo dentro dos chunks da montagem (inclui o que nao e da montagem)
    tudo = MC.diff_dicts(bx, by, "blocos") + MC.diff_dicts(tx, ty, "tiles") + MC.diff_dicts(ex, ey, "entidades") \
        + MC.diff_dicts(kx, ky, "ticks")
    print("chunks da montagem, tudo (blocos %d/%d, tiles %d/%d, entidades %d/%d, ticks %d/%d): %d diferencas" % (
        len(bx), len(by), len(tx), len(ty), len(ex), len(ey), len(kx), len(ky), len(tudo)))
    for d in tudo[:40]:
        print("   ", d[:3], str(d[3])[:80], str(d[4])[:80])
    return 0 if not dif else 1


if __name__ == "__main__":
    sys.exit(main())
