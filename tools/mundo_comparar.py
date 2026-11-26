#!/usr/bin/env python3
"""Hoshiestar - compara dois mundos SALVOS (.mca) FORA de uma caixa de chunks: blocos, tile entities (NBT),
entidades e ticks agendados. So le. Para o teste diferencial (recurso desligado x ligado com a regiao A cortada).

Uso:
  mundo_comparar.py mca <mundo-X> <mundo-Y> --excluir-chunks cx0,cz0,cx1,cz1 [--baseline <mundo-Z>] [--saida arq]
                        [--max-linhas 40] [--dim 0]
  mundo_comparar.py foto <foto-X> <foto-Y> [--baseline <foto-Z>] [--saida arq]
      (foto = saida de 'hoshie regioes foto', gravada no tick exato do mundo)

--baseline: um terceiro mundo que deveria ser IGUAL a X (ex.: outra execucao com o mesmo recurso). A diferenca X-Z
mostra o quanto a rodada varia sozinha (aleatoriedade, folga de uns ticks entre execucoes). Cada diferenca X-Y
sai marcada 'tambem-no-baseline' (o mesmo campo, no mesmo lugar, tambem difere em X-Z) ou 'so-X-Y'. A ferramenta
NAO conclui nada: so separa e conta.

Saida: resumo (contagens) e, no --saida, todas as diferencas (uma por linha).
"""
import argparse
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mundo_nbt as N  # noqa: E402


def blocos(lv):
    """{(x,y,z): (id, meta)} dos blocos nao-ar do chunk."""
    cx, cz = lv.get("xPos", 0), lv.get("zPos", 0)
    out = {}
    for s in lv.get("Sections", []):
        y0 = s.get("Y", 0) * 16
        b = s.get("Blocks", b"")
        ad = s.get("Add")
        d = s.get("Data", b"")
        if len(b) != 4096:
            continue
        for i in range(4096):
            idb = b[i]
            if ad:
                n = ad[i >> 1]
                idb |= ((n >> 4) if (i & 1) else (n & 15)) << 8
            m = d[i >> 1] if len(d) == 2048 else 0
            meta = (m >> 4) if (i & 1) else (m & 15)
            if idb or meta:
                out[(cx * 16 + (i & 15), y0 + (i >> 8), cz * 16 + ((i >> 4) & 15))] = (idb, meta)
    return out


def carregar(mundo, caixa, dim):
    """Devolve dict de listas por tipo: chunks, blocos, tiles, entidades, ticks, para os chunks FORA da caixa."""
    cx0, cz0, cx1, cz1 = caixa

    def fora(cx, cz):
        return not (cx0 <= cx <= cx1 and cz0 <= cz <= cz1)

    chunks, bl, tiles, ents, ticks = set(), {}, {}, {}, {}
    for cx, cz, lv in N.todos_os_chunks(mundo, dim, fora):
        chunks.add((cx, cz))
        for k, v in blocos(lv).items():
            bl[k] = v
        for t in lv.get("TileEntities", []):
            tiles[(t.get("x"), t.get("y"), t.get("z"))] = t
        for i, e in enumerate(lv.get("Entities", [])):
            p = e.get("Pos", [0, 0, 0])
            ents[(e.get("id"), round(p[0], 2), round(p[1], 2), round(p[2], 2), i)] = e
        for t in lv.get("TileTicks", []):
            ticks[(t.get("x"), t.get("y"), t.get("z"), t.get("i"))] = t
    return {"chunks": chunks, "blocos": bl, "tiles": tiles, "entidades": ents, "ticks": ticks}


def diff_dicts(a, b, tipo):
    """Lista de (tipo, local, campo, valorA, valorB)."""
    out = []
    for k in sorted(set(a) | set(b), key=str):
        if k not in a:
            out.append((tipo, k, "<so-em-Y>", "", ""))
        elif k not in b:
            out.append((tipo, k, "<so-em-X>", "", ""))
        elif tipo == "blocos":
            if a[k] != b[k]:
                out.append((tipo, k, "id,meta", a[k], b[k]))
        else:
            if N.texto(a[k]) != N.texto(b[k]):
                fa, fb = N.achatar(a[k]), N.achatar(b[k])
                for c in sorted(set(fa) | set(fb)):
                    if fa.get(c) != fb.get(c):
                        out.append((tipo, k, c, fa.get(c, "<ausente>"), fb.get(c, "<ausente>")))
    return out


def comparar_mundos(x, y, caixa, dim):
    dx, dy = carregar(x, caixa, dim), carregar(y, caixa, dim)
    out = []
    for c in sorted(dx["chunks"] ^ dy["chunks"]):
        out.append(("chunks", c, "<so-em-X>" if c in dx["chunks"] else "<so-em-Y>", "", ""))
    comuns = dx["chunks"] & dy["chunks"]

    def so_comuns(d):
        if d is dx["blocos"] or d is dy["blocos"]:
            return {k: v for k, v in d.items() if (k[0] >> 4, k[2] >> 4) in comuns}
        return d
    out += diff_dicts(so_comuns(dx["blocos"]), so_comuns(dy["blocos"]), "blocos")
    for t in ("tiles", "entidades", "ticks"):
        out += diff_dicts(dx[t], dy[t], t)
    resumo = {t: (len(dx[t]), len(dy[t])) for t in ("chunks", "blocos", "tiles", "entidades", "ticks")}
    return out, resumo


def comparar_fotos(x, y):
    def ler(p):
        d = {}
        for l in open(p, errors="replace"):
            if l.startswith("foto "):
                continue
            tipo, resto = l.rstrip("\n").split(" ", 1)
            if tipo == "T":
                dim, px, py, pz, cls, nbt = resto.split(" ", 5)
                d[("tiles", (int(dim), int(px), int(py), int(pz)), cls)] = nbt
            else:
                dim, cx, cz, crc = resto.split(" ")
                d[("chunks-blocos", (int(dim), int(cx), int(cz)), "")] = crc
        return d
    a, b = ler(x), ler(y)
    out = []
    for k in sorted(set(a) | set(b), key=str):
        if k not in a:
            out.append((k[0], k[1], "<so-em-Y>", "", ""))
        elif k not in b:
            out.append((k[0], k[1], "<so-em-X>", "", ""))
        elif a[k] != b[k]:
            if k[0] == "tiles":
                # separa o campo: reaproveita o texto ordenado (chaves:valor) por uma divisao simples de nivel 1
                out.append((k[0], k[1], "NBT(%s)" % k[2], a[k][:200], b[k][:200]))
            else:
                out.append((k[0], k[1], "crc-dos-blocos", a[k], b[k]))
    return out, {"linhas": (len(a), len(b))}


def marcar(out, base):
    ch = set((o[0], o[1], o[2]) for o in base)
    return [(o, (o[0], o[1], o[2]) in ch) for o in out]


def relatorio(titulo, out, resumo, base, max_linhas, f):
    f.write("== %s\n" % titulo)
    f.write("contagens X,Y: %s\n" % resumo)
    f.write("diferencas X-Y: %d\n" % len(out))
    marc = marcar(out, base) if base is not None else [(o, None) for o in out]
    por = collections.Counter()
    for o, tb in marc:
        por[(o[0], "tambem-no-baseline" if tb else ("so-X-Y" if tb is not None else "sem-baseline"))] += 1
    for k in sorted(por):
        f.write("  %-12s %-20s %d\n" % (k[0], k[1], por[k]))
    if base is not None:
        f.write("diferencas do baseline X-Z (a rodada variando sozinha): %d\n" % len(base))
    # resumo por classe/campo para tiles
    campos = collections.Counter()
    for o, tb in marc:
        if o[0] == "tiles" and isinstance(o[2], str):
            campos[o[2].split("[")[0].rsplit("/", 1)[-1] if o[2].startswith("/") else o[2]] += 1
    if campos:
        f.write("campos de tile que mais diferem (nome do campo: quantas):\n")
        for c, n in campos.most_common(15):
            f.write("  %s: %d\n" % (c, n))
    f.write("primeiras %d:\n" % max_linhas)
    for o, tb in marc[:max_linhas]:
        f.write("  [%s] %s %s %s X=%s Y=%s\n" % ("baseline" if tb else ("so-X-Y" if tb is not None else "-"),
                                             o[0], o[1], o[2], str(o[3])[:80], str(o[4])[:80]))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("modo", choices=["mca", "foto"])
    ap.add_argument("x")
    ap.add_argument("y")
    ap.add_argument("--excluir-chunks", default="0,0,-1,-1")
    ap.add_argument("--baseline")
    ap.add_argument("--saida")
    ap.add_argument("--max-linhas", type=int, default=40)
    ap.add_argument("--dim", type=int, default=0)
    a = ap.parse_args()
    f = open(a.saida, "w") if a.saida else sys.stdout
    if a.modo == "mca":
        caixa = N.caixa_de_texto(a.excluir_chunks)
        out, res = comparar_mundos(a.x, a.y, caixa, a.dim)
        base = comparar_mundos(a.x, a.baseline, caixa, a.dim)[0] if a.baseline else None
    else:
        out, res = comparar_fotos(a.x, a.y)
        base = comparar_fotos(a.x, a.baseline)[0] if a.baseline else None
    relatorio("%s: %s x %s" % (a.modo, os.path.basename(a.x.rstrip("/")), os.path.basename(a.y.rstrip("/"))),
              out, res, base, a.max_linhas if not a.saida else 10 ** 9, f)
    if a.saida:
        f.close()
        print("diferencas X-Y: %d (detalhe em %s)" % (len(out), a.saida))


if __name__ == "__main__":
    main()
