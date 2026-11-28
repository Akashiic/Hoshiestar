#!/usr/bin/env python3
"""Hoshiestar - monta, numa COPIA DE TESTE ligada (mundo plano), as montagens deterministicas do Degrau B (passo 2).

O que monta (perto do spawn, dentro dos chunks do spawn, que ficam carregados sem jogador):
  * GRADE de N mini-bases (--bases, 16 a 32; padrao 16), espacadas de --espaco blocos (padrao 64 = 4 secoes: duas bases
    nunca caem na mesma regiao). Cada mini-base, num piso de pedra 7x5:
      - 1 bau simples com itens fixos (quantidades dependem do numero da base, para achar troca de conteudo);
      - 1 bau duplo (dois baus lado a lado) com itens fixos;
      - 1 bau-armadilha com itens fixos;
      - 1 bau de ender;
      - 1 mesa de encantamento.
    Sem comparador, sem funil, sem mob, sem nada que sorteie: o mundo da base so muda pelo tick dos tiles da onda 1.
  * MONTAGEM HOSTIL (--hostil, padrao ligado), numa base a parte:
      - composter do EtFuturum EM CIMA de um funil e outro NA FRENTE dele (funil apontando para +x); funil VAZIO
        (com item, o composter sorteia ao compostar e duas execucoes sairiam diferentes);
      - barril do EtFuturum (bloco de mod com inventario) encostado num bau com itens, e outro em cima do bau.
    Fica no centro da grade. A montagem hostil e para o classificador (passo 3) dizer "inelegivel"; aqui so se confere que os blocos existem.
  * NAO monta os aneis de funis da onda 2 (o funil ficou fora da primeira entrega, decisao 39a).

Uso:
  b_monta_paralelo.py <copia> [--bases 16] [--espaco 64] [--hostil sim|nao] [--so-comandos arq]
A copia tem de estar ligada e ser copia de teste (HOSHIESTAR-TEST-ENV.txt, server-ip=127.0.0.1). Envia comandos pelo
console.in e le as respostas no console.log. No fim confere bloco a bloco com 'testforblock' e imprime o resumo.
Saida 0 = todos os blocos conferidos. NAO imprime nada de configuracao da copia.

Detalhes do 1.7.10 (achados da Etapa 2.5, D-59): o /setblock so aceita id NUMERICO de item no NBT; superficie do mundo
plano em y=3 (piso de pedra em y=3, objetos em y=4).
"""
import argparse
import gzip
import io
import os
import struct
import sys
import time

Y_PISO = 3
Y = 4
ITEM = {"cobblestone": 4, "iron_ingot": 265, "gold_ingot": 266, "diamond": 264, "redstone": 331, "coal": 263,
        "emerald": 388, "sand": 12, "gravel": 13, "glowstone_dust": 348}


# ------------------------------------------------------------------ NBT minimo (so para ler o level.dat)
def _nbt(f, t):
    if t == 1:
        return struct.unpack(">b", f.read(1))[0]
    if t == 2:
        return struct.unpack(">h", f.read(2))[0]
    if t == 3:
        return struct.unpack(">i", f.read(4))[0]
    if t == 4:
        return struct.unpack(">q", f.read(8))[0]
    if t == 5:
        return struct.unpack(">f", f.read(4))[0]
    if t == 6:
        return struct.unpack(">d", f.read(8))[0]
    if t == 7:
        n = struct.unpack(">i", f.read(4))[0]
        return f.read(n)
    if t == 8:
        n = struct.unpack(">H", f.read(2))[0]
        return f.read(n).decode("utf-8", "replace")
    if t == 9:
        et = f.read(1)[0]
        n = struct.unpack(">i", f.read(4))[0]
        return [_nbt(f, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            tt = f.read(1)[0]
            if tt == 0:
                return d
            n = struct.unpack(">H", f.read(2))[0]
            nome = f.read(n).decode("utf-8", "replace")
            d[nome] = _nbt(f, tt)
    if t == 11:
        n = struct.unpack(">i", f.read(4))[0]
        return list(struct.unpack(">%di" % n, f.read(4 * n)))
    raise ValueError("tag NBT %d" % t)


def ler_level_dat(caminho):
    """Devolve o compound Data do level.dat (SpawnX, SpawnZ, Time...)."""
    with gzip.open(caminho, "rb") as g:
        f = io.BytesIO(g.read())
    t = f.read(1)[0]
    n = struct.unpack(">H", f.read(2))[0]
    f.read(n)
    return _nbt(f, t)["Data"]


# ------------------------------------------------------------------ montagem
def itens(*pares):
    s = ",".join("{Slot:%db,id:%ds,Count:%db,Damage:0s}" % (i, ITEM[n], q) for i, (n, q) in enumerate(pares))
    return "{Items:[%s]}" % s


class Montagem:
    def __init__(self):
        self.cmds = []
        self.conferir = []   # (x, y, z, nome)

    def set(self, x, y, z, nome, meta=0, nbt=None, conferir=True):
        c = "setblock %d %d %d %s %d replace" % (x, y, z, nome, meta)
        if nbt:
            c += " " + nbt
        self.cmds.append(c)
        if conferir:
            self.conferir.append((x, y, z, nome))

    def piso(self, ox, oz, lx, lz):
        for dx in range(lx):
            for dz in range(lz):
                self.set(ox + dx, Y_PISO, oz + dz, "minecraft:stone", conferir=False)


def mini_base(m, i, ox, oz):
    """Mini-base i com canto em (ox, oz). Conteudo fixo, dependente de i."""
    m.piso(ox, oz, 7, 5)
    # bau simples (x=1) e bau duplo (x=3,4): um bloco de folga para nao virar triplo; mesma face (meta 3) no duplo
    m.set(ox + 1, Y, oz + 1, "minecraft:chest", 3, itens(("cobblestone", i % 64 + 1), ("iron_ingot", 7), ("diamond", i % 5 + 1)))
    m.set(ox + 3, Y, oz + 1, "minecraft:chest", 3, itens(("gold_ingot", 3), ("redstone", i % 32 + 2)))
    m.set(ox + 4, Y, oz + 1, "minecraft:chest", 3, itens(("coal", 16), ("emerald", i % 3 + 1)))
    # bau-armadilha (bloco diferente: nao junta com o bau ao lado)
    m.set(ox + 1, Y, oz + 3, "minecraft:trapped_chest", 3, itens(("sand", 5), ("gravel", i % 7 + 1)))
    m.set(ox + 3, Y, oz + 3, "minecraft:ender_chest", 3)
    m.set(ox + 5, Y, oz + 3, "minecraft:enchanting_table", 0)


def base_hostil(m, ox, oz):
    m.piso(ox, oz, 9, 5)
    # funil apontando para +x (meta 5), vazio; composter em cima e composter na frente
    m.set(ox + 1, Y, oz + 1, "minecraft:hopper", 5)
    m.set(ox + 1, Y + 1, oz + 1, "etfuturum:composter", 0)
    m.set(ox + 2, Y, oz + 1, "etfuturum:composter", 0)
    # bau com itens, barril encostado do lado e outro em cima
    m.set(ox + 5, Y, oz + 1, "minecraft:chest", 3, itens(("diamond", 9), ("cobblestone", 33)))
    m.set(ox + 6, Y, oz + 1, "etfuturum:barrel", 0)
    m.set(ox + 5, Y + 1, oz + 1, "etfuturum:barrel", 0)


def posicoes_grade(n, espaco, sx, sz):
    lado = 1
    while lado * lado < n:
        lado += 1
    meio = (lado - 1) * espaco / 2.0
    out = []
    for k in range(n):
        gx, gz = k % lado, k // lado
        out.append((int(sx + gx * espaco - meio) - 3, int(sz + gz * espaco - meio) - 2))
    return out


def montar(n, espaco, hostil, sx, sz):
    m = Montagem()
    pos = posicoes_grade(n, espaco, sx, sz)
    for i, (ox, oz) in enumerate(pos):
        mini_base(m, i, ox, oz)
    host = None
    if hostil:
        # no centro da grade (com lado par nao ha mini-base ali; com lado impar, meio espaco ao lado da do centro)
        lado = 1
        while lado * lado < n:
            lado += 1
        host = (int(sx) - 4 + (espaco // 2 if lado % 2 else 0), int(sz) - 2)
        base_hostil(m, host[0], host[1])
    return m, pos, host


# ------------------------------------------------------------------ console
def checar_copia(d):
    if not os.path.isfile(os.path.join(d, "HOSHIESTAR-TEST-ENV.txt")):
        sys.exit("ERRO: %s nao e copia de teste" % d)
    with open(os.path.join(d, "server.properties"), errors="replace") as f:
        if not any(l.strip() == "server-ip=127.0.0.1" for l in f):
            sys.exit("ERRO: server.properties fora de 127.0.0.1")


def enviar(d, cmds, lote=40, pausa=1.0):
    with open(os.path.join(d, "console.in"), "a") as f:
        for k, c in enumerate(cmds):
            f.write(c + "\n")
            if k % lote == lote - 1:
                f.flush()
                time.sleep(pausa)


def esperar_marca(d, marca, desde, limite=300):
    log = os.path.join(d, "console.log")
    fim = time.time() + limite
    while time.time() < fim:
        with open(log, errors="replace") as f:
            f.seek(desde)
            if marca in f.read():
                return True
        time.sleep(2)
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("copia")
    ap.add_argument("--bases", type=int, default=16)
    ap.add_argument("--espaco", type=int, default=64)
    ap.add_argument("--hostil", choices=["sim", "nao"], default="sim")
    ap.add_argument("--mundo", default="world-test")
    ap.add_argument("--so-comandos", help="so grava os comandos neste arquivo (nao precisa de servidor)")
    a = ap.parse_args()
    if not 1 <= a.bases <= 64:
        sys.exit("--bases fora de 1..64 (o plano pede 16 a 32)")
    d = os.path.abspath(a.copia)
    lv = os.path.join(d, a.mundo, "level.dat")
    if not a.so_comandos:
        # o level.dat de um mundo novo so existe depois do primeiro salvamento: salva antes de ler o spawn
        checar_copia(d)
        desde = os.path.getsize(os.path.join(d, "console.log"))
        enviar(d, ["save-all"])
        if not esperar_marca(d, "Save complete", desde, limite=120):   # resposta do save-all do Bukkit no console
            print("ERRO: 'save-all' sem resposta em 2 min")
            return 1
        time.sleep(3)
    if os.path.isfile(lv):
        dat = ler_level_dat(lv)
        sx, sz = dat.get("SpawnX", 0), dat.get("SpawnZ", 0)
    elif a.so_comandos:
        sx, sz = 0, 0
    else:
        print("ERRO: sem %s depois do save-all" % lv)
        return 1
    m, pos, host = montar(a.bases, a.espaco, a.hostil == "sim", sx, sz)
    extensao = max(max(abs(p[0] - sx), abs(p[1] - sz)) for p in pos + ([host] if host else [])) + 10
    resumo = "spawn (%d, %d); %d mini-bases, espaco %d; hostil %s; %d comandos; maior distancia do spawn %d blocos" % (
        sx, sz, a.bases, a.espaco, host, len(m.cmds), extensao)
    if extensao > 128:
        print("AVISO: parte da montagem passa de 128 blocos do spawn: esses chunks podem descarregar sem jogador")
    if a.so_comandos:
        with open(a.so_comandos, "w") as f:
            f.write("# " + resumo + "\n")
            for i, p in enumerate(pos):
                f.write("# base %d canto %s\n" % (i, p))
            f.write("\n".join(m.cmds) + "\n")
        print(resumo)
        return 0
    log = os.path.join(d, "console.log")
    desde = os.path.getsize(log)
    enviar(d, m.cmds + ["say hoshie-monta-fim"])
    if not esperar_marca(d, "hoshie-monta-fim", desde):
        print("ERRO: o servidor nao respondeu a montagem em 5 min")
        return 1
    # confere bloco a bloco
    desde = os.path.getsize(log)
    enviar(d, ["testforblock %d %d %d %s" % c for c in m.conferir] + ["say hoshie-confere-fim"], lote=60, pausa=0.5)
    if not esperar_marca(d, "hoshie-confere-fim", desde):
        print("ERRO: o servidor nao respondeu a conferencia em 5 min")
        return 1
    with open(log, errors="replace") as f:
        f.seek(desde)
        txt = f.read()
    ok = txt.count("Successfully found the block")
    fora = txt.count("outside of the world")
    if fora:
        print("AVISO: %d respostas 'outside of the world' (chunk nao carregado: longe demais do spawn?)" % fora)
    falta = len(m.conferir) - ok
    print(resumo)
    for i, p in enumerate(pos):
        print("  base %2d canto (%d, %d)" % (i, p[0], p[1]))
    if host:
        print("  hostil canto (%d, %d)" % host)
    print("conferencia: %d de %d blocos achados com testforblock" % (ok, len(m.conferir)))
    if falta:
        ruins = [l for l in txt.splitlines() if "block at" in l.lower() or "cannot" in l.lower()]
        for l in ruins[:20]:
            print("  " + l[-160:])
        if len(ruins) > 20:
            print("  ... e mais %d" % (len(ruins) - 20))
    return 0 if falta == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
