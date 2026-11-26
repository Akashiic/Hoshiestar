#!/usr/bin/env python3
"""Hoshiestar - monta, numa COPIA DE TESTE ligada (mundo plano), o mundo dos testes B1/B2 da Etapa 2.5.

Duas construcoes:
  * base A (regiao que sera cortada), em torno de (3000, 3000), uma base FECHADA com itens e energia contaveis:
      - 2 aneis de funis (8 funis cada) com itens circulando para sempre (itens "em transito");
      - 3 colunas bau -> funil -> fornalha -> funil -> bau (duas com carvao, uma sem combustivel: minerio parado);
      - 2 baus soltos com itens variados;
      - 1 linha de cano BuildCraft (bau de pedregulho -> cano de madeira + motor -> 12 canos -> bau);
      - 1 matriz de inducao do Mekanism 7x7x7 (124 celulas de 4,0e8 J) com circuito de dois cubos (docs/mekanism.md
        do fork, Preparacao 3 e 4), para o livro-caixa 'hoshie mekanism ... mais ...';
  * base S, perto do spawn (chunks do spawn carregam no boot, sem jogador): 1 kit da carga sintetica
    (tools/carga_sintetica.py), parte de fora da regiao cortada, para o teste diferencial.
Um bot parado (CargaA) mantem os chunks da base A carregados.

Uso: b_monta_mundo.py <copia> [--so-comandos arquivo.json]
A copia tem de estar ligada (scripts/aplicar-carga-sintetica.sh <copia> a --so-ligar). Numero de carga sintetica
e indicacao, nao medicao de producao. So em copia de teste.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import carga_sintetica as cs  # noqa: E402

AX, AZ = 3000, 3000          # centro da base A
MX, MY, MZ, MN = 3010, 100, 3010, 7   # matriz do Mekanism (canto minimo e lado)
SX, SZ = 66, 294             # centro da base S (dentro dos chunks do spawn deste mundo: spawn em 66, 294)
Y0 = cs.Y0


# O /setblock do 1.7.10 so aceita o id do item como NUMERO (id:15s); id:"minecraft:iron_ore" e ignorado sem aviso
# (achado deste teste: as bases de tools/carga_sintetica.py nasceram com baus e fornalhas SEM item). Vale so
# para itens do jogo base; ids fixos do 1.7.10.
ID = {"minecraft:iron_ore": 15, "minecraft:gold_ore": 14, "minecraft:cobblestone": 4, "minecraft:redstone": 331,
      "minecraft:coal": 263, "minecraft:sand": 12, "minecraft:gravel": 13, "minecraft:diamond": 264,
      "minecraft:gold_ingot": 266, "minecraft:iron_ingot": 265, "minecraft:emerald": 388,
      "minecraft:glowstone_dust": 348}


def numerico(nbt):
    """troca id:"minecraft:xxx" por id:Ns no texto de um NBT."""
    return re.sub(r'id:"(minecraft:[a-z_]+)"', lambda m: "id:%ds" % ID[m.group(1)], nbt)


def itens(*pares):
    s = ",".join("{Slot:%db,id:%ds,Count:%db,Damage:0s}" % (i, ID[n], q) for i, (n, q) in enumerate(pares))
    return "{Items:[%s]}" % s


def anel(b, ox, oz, nbt_primeiro):
    """Anel de 8 funis no perimetro de 3x3, cada um apontando para o proximo (sentido horario visto de cima)."""
    caminho = [(0, 0, 5), (1, 0, 5), (2, 0, 3), (2, 1, 3), (2, 2, 4), (1, 2, 4), (0, 2, 2), (0, 1, 2)]
    for i, (dx, dz, meta) in enumerate(caminho):
        b.set(ox + dx, Y0, oz + dz, "minecraft:hopper", meta, nbt_primeiro if i == 0 else None)


def base_a(ids):
    b = cs.Base("A", AX, AZ, 1, ids)
    anel(b, AX - 10, AZ - 10, itens(("minecraft:iron_ore", 64), ("minecraft:gold_ore", 64), ("minecraft:cobblestone", 64),
                                    ("minecraft:redstone", 40), ("minecraft:coal", 13)))
    anel(b, AX - 5, AZ - 10, itens(("minecraft:sand", 64), ("minecraft:gravel", 64), ("minecraft:diamond", 5),
                                   ("minecraft:gold_ingot", 33), ("minecraft:iron_ingot", 17)))
    minerio = itens(("minecraft:iron_ore", 64), ("minecraft:iron_ore", 64), ("minecraft:iron_ore", 64),
                    ("minecraft:iron_ore", 20))
    carvao = numerico('{BurnTime:1600s,CookTime:0s,Items:[{Slot:1b,id:"minecraft:coal",Count:64b,Damage:0s}]}')
    for k, (x, com_fogo) in enumerate([(AX, True), (AX + 3, True), (AX + 6, False)]):
        z = AZ - 10
        b.set(x, Y0 + 4, z + 6, "minecraft:chest", 2, minerio)
        b.set(x, Y0 + 3, z + 6, "minecraft:hopper", 0)
        b.set(x, Y0 + 2, z + 6, "minecraft:furnace", 2, carvao if com_fogo else None)
        b.set(x, Y0 + 1, z + 6, "minecraft:hopper", 0)
        b.set(x, Y0, z + 6, "minecraft:chest", 2)
    b.set(AX - 10, Y0, AZ - 4, "minecraft:chest", 2, itens(("minecraft:emerald", 9), ("minecraft:diamond", 3),
                                                            ("minecraft:iron_ingot", 50), ("minecraft:coal", 64)))
    b.set(AX - 8, Y0, AZ - 4, "minecraft:chest", 2, itens(("minecraft:glowstone_dust", 64), ("minecraft:redstone", 64),
                                                           ("minecraft:cobblestone", 64), ("minecraft:cobblestone", 17)))
    b.linha_cano(AX - 10, AZ - 2, Y0)
    # matriz de inducao (docs/mekanism.md do fork): moldura meta 1, provedor meta 4 {tier:3}, celulas meta 3
    for x in range(MN):
        for y in range(MN):
            for z in range(MN):
                X, Y, Z = MX + x, MY + y, MZ + z
                if x in (0, MN - 1) or y in (0, MN - 1) or z in (0, MN - 1):
                    b.set(X, Y, Z, "Mekanism:BasicBlock2", 1)
                elif (x, y, z) == (3, 3, 3):
                    b.set(X, Y, Z, "Mekanism:BasicBlock2", 4, "{tier:3}")
                else:
                    b.set(X, Y, Z, "Mekanism:BasicBlock2", 3, "{tier:0,electricityStored:400000000.0d}")
    pz = MZ + 3
    b.set(MX, MY + 3, pz, "Mekanism:BasicBlock2", 2, "{mode:1b}")   # porta de SAIDA (face oeste)
    b.set(MX, MY + 4, pz, "Mekanism:BasicBlock2", 2)               # porta de ENTRADA
    b.set(MX - 1, MY + 3, pz, "Mekanism:EnergyCube", 0, "{facing:1}")   # cubo A
    b.set(MX - 1, MY + 4, pz, "Mekanism:EnergyCube", 0, "{facing:5}")   # cubo B
    return b


def base_s(ids, sx=None, sz=None):
    # as constantes da carga sintetica usam id por nome (ignorado pelo jogo): troca por numero antes de montar
    for nome in ("ITENS_BAU", "FORNALHA_NBT", "ITENS_BAU_PIPE"):
        setattr(cs, nome, numerico(getattr(cs, nome)))
    return cs.montar_base("S", sx if sx is not None else SX, sz if sz is not None else SZ, 1, ids)


def extras_s(b, ox, oz):
    """Complementos da base S2 (testes B2): colunas que se ASSENTAM sozinhas (funis em fila -> bau final; estado final
    igual em qualquer tick depois de poucos segundos), fornalhas com minerio e sem combustivel (paradas), baus, e UM anel
    de funis (dinamico: o estado depende do tick exato; serve de medida do ruido de folga entre execucoes)."""
    minerio = itens(("minecraft:iron_ore", 64), ("minecraft:gold_ore", 64), ("minecraft:iron_ore", 33))
    for k in range(4):
        x = ox + 2 * k
        b.set(x, Y0 + 4, oz, "minecraft:chest", 2, minerio)
        b.set(x, Y0 + 3, oz, "minecraft:hopper", 0)
        b.set(x, Y0 + 2, oz, "minecraft:hopper", 0)
        b.set(x, Y0 + 1, oz, "minecraft:hopper", 0)
        b.set(x, Y0, oz, "minecraft:chest", 2)
    for k in range(3):
        x = ox + 2 * k
        b.set(x, Y0 + 3, oz + 3, "minecraft:furnace", 2, itens(("minecraft:iron_ore", 30), ("minecraft:gold_ore", 5)))
    b.set(ox, Y0, oz + 6, "minecraft:chest", 2, itens(("minecraft:diamond", 7), ("minecraft:emerald", 5), ("minecraft:redstone", 64)))
    anel(b, ox, oz + 8, itens(("minecraft:sand", 64), ("minecraft:gravel", 64), ("minecraft:cobblestone", 11)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("copia")
    ap.add_argument("--so-comandos")
    ap.add_argument("--so-s", help="x,z: so monta um kit S2 nesse centro (chunks do spawn; sem bot, sem base A)")
    a = ap.parse_args()
    copia = os.path.abspath(a.copia)
    if not os.path.exists(os.path.join(copia, "HOSHIESTAR-TEST-ENV.txt")):
        raise SystemExit("nao e uma copia de teste")
    con = cs.Console(copia)
    ids, origem = cs.ler_ids_pipes(copia, con)
    print("ids dos canos (%s): %s" % (origem, ids))
    if a.so_s:
        sx, sz = [int(v) for v in a.so_s.split(",")]
        S = base_s(ids, sx, sz)
        extras_s(S, sx + 18, sz - 8)
        for i in range(0, len(S.cmds), 40):
            con.enviar(*S.cmds[i:i + 40])
            time.sleep(1.0)
        print("S2: %d comandos; caixa x%d..%d z%d..%d" % (len(S.cmds), S.minx, S.maxx, S.minz, S.maxz))
        return
    S = base_s(ids)   # primeiro: troca as constantes por ids numericos
    A = base_a(ids)
    if a.so_comandos:
        json.dump({"A": A.cmds, "S": S.cmds, "caixa_A": [A.minx, A.minz, A.maxx, A.maxz], "caixa_S": [S.minx, S.minz, S.maxx, S.maxz]},
                  open(a.so_comandos, "w"))
        print("A: %d comandos, S: %d comandos" % (len(A.cmds), len(S.cmds)))
        return
    bot = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bot_anda.py")
    cmds = os.path.join(copia, "carga-bots.cmds")
    open(cmds, "w").close()
    logb = os.path.join(copia, "carga-bots.log")
    open(logb, "w").close()
    for tentativa in (1, 2, 3):
        subprocess.Popen([sys.executable, "-I", bot, "--nomes", "CargaA", "--rota", "parado", "--tempo", "0", "--cmds", cmds,
                          "--log", logb, "--espera-entrada", "60"], stdout=open(os.path.join(copia, "carga-bots.out"), "a"),
                         stderr=subprocess.STDOUT, start_new_session=True)
        t0 = time.time()
        while time.time() - t0 < 70 and "[CargaA] join game" not in open(logb, errors="replace").read():
            time.sleep(1)
        if "[CargaA] join game" in open(logb, errors="replace").read():
            break
    else:
        raise SystemExit("bot nao entrou")
    time.sleep(3)

    def esperar_chunk(x, z):
        t0 = time.time()
        while time.time() - t0 < 180:
            time.sleep(4)
            l = con.pergunta("setblock %d 0 %d minecraft:bedrock 0" % (x, z), r"couldn't be placed|Block placed|outside of the world", 6)
            if l and "outside of the world" not in l:
                return True
        return False

    con.enviar("gamemode 1 CargaA")
    for _ in range(8):   # logo apos o login algo devolve o bot ao spawn (~4 s depois): repete ate a posicao ficar
        con.enviar("tp CargaA %d %d %d" % (AX + 3, Y0 + 1, AZ + 3))
        time.sleep(20)
        pos = re.findall(r"\[CargaA\] pos (-?[\d.]+) ", open(logb, errors="replace").read())
        if pos and abs(float(pos[-1]) - (AX + 3)) < 10:
            break
    print("chunks de A:", esperar_chunk(MX + MN, MZ + MN) and esperar_chunk(AX - 11, AZ - 11))
    for nome, base in (("S", S), ("A", A)):
        t0 = time.time()
        for i in range(0, len(base.cmds), 40):
            con.enviar(*base.cmds[i:i + 40])
            time.sleep(1.0)
        print("%s: %d comandos em %.0fs" % (nome, len(base.cmds), time.time() - t0))
        time.sleep(3)
    print("caixa A (blocos) x%d..%d z%d..%d ; caixa S x%d..%d z%d..%d" % (A.minx, A.maxx, A.minz, A.maxz, S.minx, S.maxx, S.minz, S.maxz))
    print("chunks/TE/entidades:", con.chunks())


if __name__ == "__main__":
    main()
