#!/usr/bin/env python3
"""Hoshiestar - leitor minimo de NBT e de arquivos de regiao (.mca, formato Anvil do Minecraft 1.7.10).

Usado por tools/mundo_contar.py e tools/mundo_comparar.py (Etapa 2.5, testes B1/B2). Sem dependencias fora da
biblioteca padrao. SO LE arquivos; nunca escreve no mundo. Rode sempre com o servidor PARADO ou logo depois de
'save-all' (o 'save-all' do 1.7.10 espera a gravacao terminar), e de preferencia sobre uma COPIA dos arquivos.
"""
import gzip
import os
import re
import struct
import zlib


class Fim(Exception):
    pass


def _ler(b, p, fmt):
    n = struct.calcsize(fmt)
    if p + n > len(b):
        raise Fim()
    return struct.unpack_from(fmt, b, p)[0], p + n


def _tag(b, p, t):
    if t == 1:
        return _ler(b, p, ">b")
    if t == 2:
        return _ler(b, p, ">h")
    if t == 3:
        return _ler(b, p, ">i")
    if t == 4:
        return _ler(b, p, ">q")
    if t == 5:
        return _ler(b, p, ">f")
    if t == 6:
        return _ler(b, p, ">d")
    if t == 7:
        n, p = _ler(b, p, ">i")
        return bytes(b[p:p + n]), p + n
    if t == 8:
        n, p = _ler(b, p, ">H")
        return b[p:p + n].decode("utf-8", "replace"), p + n
    if t == 9:
        et, p = _ler(b, p, ">b")
        n, p = _ler(b, p, ">i")
        out = []
        for _ in range(n):
            v, p = _tag(b, p, et)
            out.append(v)
        return out, p
    if t == 10:
        d = {}
        while True:
            et, p = _ler(b, p, ">b")
            if et == 0:
                return d, p
            n, p = _ler(b, p, ">H")
            nome = b[p:p + n].decode("utf-8", "replace")
            p += n
            d[nome], p = _tag(b, p, et)
    if t == 11:
        n, p = _ler(b, p, ">i")
        v = struct.unpack_from(">%di" % n, b, p)
        return list(v), p + 4 * n
    raise ValueError("tipo de NBT desconhecido: %d" % t)


def ler_nbt(dados):
    """Devolve o compound raiz (dict) de um NBT ja descomprimido."""
    t, p = _ler(dados, 0, ">b")
    n, p = _ler(dados, p, ">H")
    p += n
    v, _ = _tag(dados, p, t)
    return v


def ler_level_dat(pasta_mundo):
    with gzip.open(os.path.join(pasta_mundo, "level.dat")) as f:
        return ler_nbt(f.read())


def mapa_itens(pasta_mundo):
    """id numerico -> nome (do registro FML do level.dat); {} se nao achar."""
    try:
        lvl = ler_level_dat(pasta_mundo)
        out = {}
        for e in lvl.get("FML", {}).get("ItemData", []):
            k = e.get("K", "")
            out[e["V"]] = k[1:] if k[:1] in ("\x01", "\x02") else k
        return out
    except Exception:
        return {}


def tempo_do_mundo(pasta_mundo):
    """Campo Time (tick do mundo) do level.dat."""
    return ler_level_dat(pasta_mundo)["Data"]["Time"]


def pasta_regiao(pasta_mundo, dim=0):
    return os.path.join(pasta_mundo, "region" if dim == 0 else "DIM%d/region" % dim)


def chunks_da_regiao(caminho):
    """Gera (cx, cz, nbt) de cada chunk do arquivo .mca."""
    m = re.search(r"r\.(-?\d+)\.(-?\d+)\.mca$", caminho)
    if not m:
        return
    rx, rz = int(m.group(1)), int(m.group(2))
    with open(caminho, "rb") as f:
        dados = f.read()
    if len(dados) < 8192:
        return
    for i in range(1024):
        loc = struct.unpack_from(">I", dados, 4 * i)[0]
        off, cnt = loc >> 8, loc & 0xFF
        if off == 0 or cnt == 0:
            continue
        p = off * 4096
        if p + 5 > len(dados):
            continue
        n, comp = struct.unpack_from(">IB", dados, p)
        blob = dados[p + 5:p + 4 + n]
        try:
            raw = zlib.decompress(blob) if comp == 2 else gzip.decompress(blob)
            nbt = ler_nbt(raw)
        except Exception:
            continue
        lv = nbt.get("Level", nbt)
        cx = lv.get("xPos", rx * 32 + (i % 32))
        cz = lv.get("zPos", rz * 32 + (i // 32))
        yield cx, cz, lv


def todos_os_chunks(pasta_mundo, dim=0, so=None):
    """Gera (cx, cz, level) de todos os chunks da dimensao. so(cx, cz) -> bool filtra por chunk (antes de ler o
    arquivo inteiro nao da para pular; o filtro corta so o que e devolvido)."""
    reg = pasta_regiao(pasta_mundo, dim)
    if not os.path.isdir(reg):
        return
    for nome in sorted(os.listdir(reg)):
        if not nome.endswith(".mca"):
            continue
        for cx, cz, lv in chunks_da_regiao(os.path.join(reg, nome)):
            if so is None or so(cx, cz):
                yield cx, cz, lv


def texto(v):
    """NBT em texto canonico (chaves em ordem), para comparar."""
    if isinstance(v, dict):
        return "{" + ",".join("%s:%s" % (k, texto(v[k])) for k in sorted(v)) + "}"
    if isinstance(v, list):
        return "[" + ",".join(texto(x) for x in v) + "]"
    if isinstance(v, bytes):
        return "bytes%d#%08x" % (len(v), zlib.crc32(v) & 0xFFFFFFFF)
    if isinstance(v, float):
        return repr(v)
    return str(v)


def achatar(v, prefixo=""):
    """dict {caminho: valor-em-texto} das folhas (listas indexadas); serve para dizer QUAL campo difere."""
    out = {}
    if isinstance(v, dict):
        for k in v:
            out.update(achatar(v[k], prefixo + "/" + k))
    elif isinstance(v, list):
        for i, x in enumerate(v):
            out.update(achatar(x, prefixo + "[%d]" % i))
        if not v:
            out[prefixo + "[]"] = "vazia"
    else:
        out[prefixo] = texto(v)
    return out


def caixa_de_texto(s):
    a = [int(x) for x in s.split(",")]
    if len(a) != 4:
        raise SystemExit("caixa: x0,z0,x1,z1")
    return min(a[0], a[2]), min(a[1], a[3]), max(a[0], a[2]), max(a[1], a[3])
