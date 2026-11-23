#!/usr/bin/env python3
"""Carga sintetica para o servidor de TESTE do Hoshiestar (so em copia de teste, so 127.0.0.1).

O mundo de producao nao pode ser copiado e nao ha jogadores no teste. Este script monta "bases"
(maquinas de varios mods que ticam de verdade) por comandos de console (setblock), em um mundo
plano, e mantem os chunks carregados com bots parados (um por base). Os numeros que saem daqui
sao CARGA SINTETICA: indicacao do comportamento do medidor e do tick, NAO medicao de producao.

Subcomandos:
  gerar    --layout a|b|c [--bases N] [--tamanho K] --saida plano.json   (so gera; nao liga nada)
  aplicar  --copia DIR --layout a|b|c [--bases N] [--tamanho K]          (copia ja ligada)
  conferir --plano plano.json --medicao medicao-*.json                   (as bases aparecem no medidor?)
  catalogo                                                               (o que entra em cada base)

Layouts:
  a  bases DISTANTES (1280 blocos = 80 chunks entre bases vizinhas; todas > 64 chunks)
  b  bases VIZINHAS (480 blocos = 30 chunks entre bases vizinhas; todas a menos de 48 chunks da proxima)
  c  UMA base com cano (BuildCraft) e cabo (IC2) cruzando a borda de uma secao de 16x16 chunks
     (a borda x = 256 e a borda z = 256 de blocos; o medidor usa essas secoes)

Tamanho (--tamanho K) = quantos "kits" por base. Um kit tem 1 coluna vanilla (bau, funil, fornalha,
funil, bau), 1 linha de maquinas de cada mod (Mekanism, IC2, Thermal, EnderIO, AE2, BuildCraft,
Forestry), 1 linha de cano BuildCraft e 1 de cabo IC2. K=1 tem ~70 blocos de maquina.
"""
import argparse
import json
import math
import os
import re
import subprocess
import sys
import time

Y0 = 4  # superficie do mundo plano padrao e y=3 (grama); a construcao comeca em y=4

# ---------------------------------------------------------------------------------------------
# Catalogo. Nomes e metas conferidos com UM setblock de cada tipo no jar 1.0.0-dev (ver CARGA-SINTETICA.md):
# todos deram "Block placed" e as classes de tile entity apareceram com tick no medidor.
# ---------------------------------------------------------------------------------------------
MEK = [("Mekanism:EnergyCube", 0), ("Mekanism:MachineBlock", 0), ("Mekanism:MachineBlock", 1),
       ("Mekanism:MachineBlock", 2), ("Mekanism:MachineBlock", 3), ("Mekanism:MachineBlock", 5),
       ("Mekanism:MachineBlock2", 0), ("MekanismGenerators:Generator", 0)]
IC2 = [("IC2:blockGenerator", 0), ("IC2:blockGenerator", 1), ("IC2:blockMachine", 1),
       ("IC2:blockMachine", 3), ("IC2:blockMachine", 5), ("IC2:blockMachine", 7),
       ("IC2:blockElectric", 0), ("IC2:blockHeatGenerator", 0)]
TE = [("ThermalExpansion:Cell", 1), ("ThermalExpansion:Machine", 0), ("ThermalExpansion:Machine", 1),
      ("ThermalExpansion:Machine", 2), ("ThermalExpansion:Machine", 3), ("ThermalExpansion:Dynamo", 0),
      ("ThermalExpansion:Dynamo", 1), ("ThermalExpansion:Tank", 0)]
EIO = [("EnderIO:blockCapBank", 0), ("EnderIO:blockAlloySmelter", 0), ("EnderIO:blockSagMill", 0),
       ("EnderIO:blockStirlingGenerator", 0), ("EnderIO:blockSolarPanel", 0), ("EnderIO:blockVat", 0),
       ("EnderIO:blockFarmStation", 0), ("EnderIO:blockPowerMonitor", 0)]
AE2 = [("appliedenergistics2:tile.BlockController", 0), ("appliedenergistics2:tile.BlockCreativeEnergyCell", 0),
       ("appliedenergistics2:tile.BlockInterface", 0), ("appliedenergistics2:tile.BlockDrive", 0),
       ("appliedenergistics2:tile.BlockCharger", 0), ("appliedenergistics2:tile.BlockInscriber", 0),
       ("appliedenergistics2:tile.BlockIOPort", 0), ("appliedenergistics2:tile.BlockEnergyCell", 0)]
BC = [("BuildCraft|Core:engineBlock", 0), ("BuildCraft|Core:engineBlock", 1), ("BuildCraft|Core:engineBlock", 2),
      ("BuildCraft|Factory:tankBlock", 0), ("BuildCraft|Factory:pumpBlock", 0),
      ("BuildCraft|Factory:autoWorkbenchBlock", 0), ("BuildCraft|Factory:refineryBlock", 0),
      ("BuildCraft|Factory:miningWellBlock", 0)]
FOR = [("Forestry:engine", 0), ("Forestry:engine", 1), ("Forestry:engine", 2), ("Forestry:factory", 0),
       ("Forestry:factory", 1), ("Forestry:factory", 2), ("Forestry:apiculture", 0), ("Forestry:factory2", 0)]
GRUPOS = [("mekanism", MEK), ("ic2", IC2), ("thermal", TE), ("enderio", EIO), ("ae2", AE2),
          ("buildcraft", BC), ("forestry", FOR)]

# Ids numericos dos canos do BuildCraft (item = pipeId). Valem para a copia onde foram lidos (level.dat do
# mundo gerado pelo jar 1.0.0-dev com o pacote de teste); aplicar() le de novo da copia (save-all + level.dat).
PIPE_ID_PADRAO = {"madeira": 7416, "pedra_cobble": 7418}

ITENS_BAU = ('{Items:[{Slot:0b,id:"minecraft:iron_ore",Count:64b,Damage:0s},'
             '{Slot:1b,id:"minecraft:iron_ore",Count:64b,Damage:0s},'
             '{Slot:2b,id:"minecraft:iron_ore",Count:64b,Damage:0s},'
             '{Slot:3b,id:"minecraft:iron_ore",Count:64b,Damage:0s},'
             '{Slot:4b,id:"minecraft:iron_ore",Count:64b,Damage:0s},'
             '{Slot:5b,id:"minecraft:iron_ore",Count:64b,Damage:0s}]}')
FORNALHA_NBT = ('{BurnTime:1600s,CookTime:0s,Items:[{Slot:1b,id:"minecraft:coal",Count:64b,Damage:0s}]}')
ITENS_BAU_PIPE = ('{Items:[{Slot:0b,id:"minecraft:cobblestone",Count:64b,Damage:0s},'
                  '{Slot:1b,id:"minecraft:cobblestone",Count:64b,Damage:0s},'
                  '{Slot:2b,id:"minecraft:cobblestone",Count:64b,Damage:0s},'
                  '{Slot:3b,id:"minecraft:cobblestone",Count:64b,Damage:0s}]}')


class Base:
    """Acumula comandos setblock de uma base. Coordenadas absolutas de bloco."""

    def __init__(self, nome, cx, cz, kits, pipe_ids, deslocar_x=0):
        self.nome, self.cx, self.cz, self.kits = nome, cx, cz, kits
        self.pipe = pipe_ids
        self.cmds = []
        self.tes = 0  # blocos com tile entity (estimativa: todo bloco de mod conta)
        self.pontos = []  # coordenadas de blocos para conferir depois (conferencia)
        self.minx = self.minz = 10 ** 9
        self.maxx = self.maxz = -10 ** 9
        self.cruza_x = []  # linhas que cruzam borda de secao
        self.cruza_z = []
        self.deslocar_x = deslocar_x

    def set(self, x, y, z, bloco, meta=0, nbt=None, te=True, ponto=False):
        c = "setblock %d %d %d %s %d" % (x, y, z, bloco, meta)
        if nbt:
            c += " replace " + nbt
        self.cmds.append(c)
        if te:
            self.tes += 1
        self.minx, self.maxx = min(self.minx, x), max(self.maxx, x)
        self.minz, self.maxz = min(self.minz, z), max(self.maxz, z)
        if ponto:
            self.pontos.append((x, y, z, bloco, meta))

    def kit(self, ox, oz):
        """Um kit com canto minimo em (ox, oz)."""
        y = Y0
        # coluna vanilla: bau(ore) -> funil -> fornalha acesa -> funil -> bau
        self.set(ox, y + 4, oz, "minecraft:chest", 2, ITENS_BAU, ponto=True)
        self.set(ox, y + 3, oz, "minecraft:hopper", 0)
        self.set(ox, y + 2, oz, "minecraft:furnace", 2, FORNALHA_NBT, ponto=True)
        self.set(ox, y + 1, oz, "minecraft:hopper", 0)
        self.set(ox, y, oz, "minecraft:chest", 2)
        # segunda coluna: so funis e fornalhas (mais pesada em vanilla)
        for k in range(1, 3):
            xx = ox + 2 * k
            self.set(xx, y + 4, oz, "minecraft:chest", 2, ITENS_BAU)
            self.set(xx, y + 3, oz, "minecraft:hopper", 0)
            self.set(xx, y + 2, oz, "minecraft:furnace", 3, FORNALHA_NBT)
            self.set(xx, y + 1, oz, "minecraft:hopper", 0)
            self.set(xx, y, oz, "minecraft:chest", 3)
        # uma linha de maquinas por mod, 2 blocos de folga entre linhas
        z = oz + 2
        for nome, maquinas in GRUPOS:
            for i, (b, m) in enumerate(maquinas):
                self.set(ox + i, y, z, b, m, ponto=(i == 0))
            z += 2
        # linha de cano BuildCraft: bau -> cano de madeira (puxa, com motor de madeira + bloco de redstone)
        # -> canos de pedra -> bau. A linha tem 12 blocos de cano e cruza a borda quando o layout manda.
        self.linha_cano(ox, z, y)
        z += 3
        self.linha_cabo(ox, z, y)

    def linha_cano(self, ox, z, y):
        self.set(ox, y, z, "minecraft:chest", 5, ITENS_BAU_PIPE)
        self.set(ox + 1, y, z, "BuildCraft|Transport:pipeBlock", 0, "{pipeId:%d}" % self.pipe["madeira"])
        self.set(ox + 1, y + 1, z, "BuildCraft|Core:engineBlock", 0)
        self.set(ox + 1, y + 2, z, "minecraft:redstone_block", 0, te=False)
        for i in range(2, 14):
            self.set(ox + i, y, z, "BuildCraft|Transport:pipeBlock", 0, "{pipeId:%d}" % self.pipe["pedra_cobble"],
                     ponto=(i == 13))
        self.set(ox + 14, y, z, "minecraft:chest", 4)
        self.cruza_x.append((ox, ox + 14, z))

    def linha_cano_z(self, x, oz, y):
        self.set(x, y, oz, "minecraft:chest", 3, ITENS_BAU_PIPE)
        self.set(x, y, oz + 1, "BuildCraft|Transport:pipeBlock", 0, "{pipeId:%d}" % self.pipe["madeira"])
        self.set(x, y + 1, oz + 1, "BuildCraft|Core:engineBlock", 0)
        self.set(x, y + 2, oz + 1, "minecraft:redstone_block", 0, te=False)
        for i in range(2, 14):
            self.set(x, y, oz + i, "BuildCraft|Transport:pipeBlock", 0, "{pipeId:%d}" % self.pipe["pedra_cobble"],
                     ponto=(i == 13))
        self.set(x, y, oz + 14, "minecraft:chest", 2)
        self.cruza_z.append((oz, oz + 14, x))

    def linha_cabo(self, ox, z, y):
        self.set(ox, y, z, "IC2:blockGenerator", 0)
        self.set(ox, y + 1, z, "IC2:blockGenerator", 0)
        for i in range(1, 13):
            self.set(ox + i, y, z, "IC2:blockCable", 0, ponto=(i == 12))
        self.set(ox + 13, y, z, "IC2:blockMachine", 3)
        self.set(ox + 13, y + 1, z, "IC2:blockMachine", 1)
        self.cruza_x.append((ox, ox + 13, z))


def montar_base(nome, cx, cz, kits, pipe_ids, borda=False):
    """Base centrada (aprox.) em (cx, cz). Com borda=True o centro da linha de cano fica exatamente na borda."""
    b = Base(nome, cx, cz, kits, pipe_ids)
    cols = max(1, math.ceil(math.sqrt(kits)))
    larg_kit = 16
    alt_kit = 2 + 2 * len(GRUPOS) + 3 + 3 + 3  # z de um kit
    linhas = math.ceil(kits / cols)
    larg_total, alt_total = cols * larg_kit, linhas * (alt_kit + 2)
    ox0 = cx - larg_total // 2
    oz0 = cz - alt_total // 2
    if borda:
        # o primeiro kit comeca em x=249: canos em 250..263 e cabo em 249..262 cruzam a borda x=255|256
        ox0, oz0 = 249, 256 - alt_total // 2
    for k in range(kits):
        ox = ox0 + (k % cols) * larg_kit
        oz = oz0 + (k // cols) * (alt_kit + 2)
        b.kit(ox, oz)
    if borda:
        b.linha_cano_z(ox0 - 4, 249, Y0)  # cano ao longo de z, de 250 a 263: cruza a borda z=255|256
    return b


def posicoes(layout, nbases):
    if layout == "a":
        esp = 1280  # 80 chunks
        return [((i % 2) * esp + 128, (i // 2) * esp + 128) for i in range(nbases)]
    if layout == "b":
        esp = 480  # 30 chunks
        return [((i % 2) * esp + 128, (i // 2) * esp + 128) for i in range(nbases)]
    if layout == "c":
        return [(256, 256)]  # centro do kit exatamente no canto de quatro secoes
    raise SystemExit("layout invalido: " + layout)


NOMES = ["CargaA", "CargaB", "CargaC", "CargaD", "CargaE", "CargaF", "CargaG", "CargaH"]


def gerar_plano(layout, nbases, kits, pipe_ids):
    if layout == "c":
        nbases = 1
    if nbases > len(NOMES):
        raise SystemExit("no maximo %d bases" % len(NOMES))
    bases = []
    for i, (cx, cz) in enumerate(posicoes(layout, nbases)):
        b = montar_base(NOMES[i], cx, cz, kits, pipe_ids, borda=(layout == "c"))
        bases.append(b)
    plano = {"layout": layout, "kits": kits, "bases": []}
    for b in bases:
        secoes = sorted({(x // 256, z // 256) for x in (b.minx, b.maxx) for z in (b.minz, b.maxz)})
        cruz = []
        for (xa, xb, z) in b.cruza_x:
            if xa // 256 != xb // 256:
                cruz.append({"eixo": "x", "de": [xa, z], "ate": [xb, z]})
        for (za, zb, x) in b.cruza_z:
            if za // 256 != zb // 256:
                cruz.append({"eixo": "z", "de": [x, za], "ate": [x, zb]})
        plano["bases"].append({
            "nome": b.nome, "centro": [b.cx, b.cz],
            "bloco_min": [b.minx, b.minz], "bloco_max": [b.maxx, b.maxz],
            "chunk_min": [b.minx // 16, b.minz // 16], "chunk_max": [b.maxx // 16, b.maxz // 16],
            "secoes": [list(s) for s in secoes],
            "blocos_com_te_estimados": b.tes, "linhas_que_cruzam_borda_de_secao": cruz,
            "pontos": [list(p) for p in b.pontos], "comandos": b.cmds})
    return plano


# ---------------------------------------------------------------------------------------------
# Console da copia
# ---------------------------------------------------------------------------------------------
class Console:
    def __init__(self, copia):
        self.d = copia
        self.log = os.path.join(copia, "console.log")
        self.inp = os.path.join(copia, "console.in")

    def n_linhas(self):
        with open(self.log, errors="replace") as f:
            return sum(1 for _ in f)

    def enviar(self, *cmds):
        with open(self.inp, "a") as f:
            for c in cmds:
                f.write(c + "\n")

    def novas(self, desde):
        with open(self.log, errors="replace") as f:
            return [l for i, l in enumerate(f) if i >= desde]

    def pergunta(self, cmd, regex, timeout=15):
        """Manda cmd e devolve a primeira linha NOVA que casa com regex (ou None)."""
        n = self.n_linhas()
        self.enviar(cmd)
        rx = re.compile(regex)
        t0 = time.time()
        while time.time() - t0 < timeout:
            time.sleep(0.3)
            for l in self.novas(n):
                if rx.search(l) and "Overlay" not in l:
                    return l.rstrip()
        return None

    def chunks(self, dim=0):
        """(chunks carregados, tile entities, entidades) da dimensao, por 'crucible chunks'."""
        n = self.n_linhas()
        self.enviar("crucible chunks")
        time.sleep(2.5)
        rx = re.compile(r"Dimension: %d Loaded Chunks: (\d+) Active Chunks: (\d+) Entities: (\d+) Tile Entities: (\d+)" % dim)
        for l in self.novas(n):
            m = rx.search(l)
            if m:
                return int(m.group(1)), int(m.group(4)), int(m.group(3))
        return None

    def tps(self):
        n = self.n_linhas()
        self.enviar("forge tps")
        time.sleep(2.5)
        for l in self.novas(n):
            m = re.search(r"Overall : Mean tick time: ([\d.]+) ms. Mean TPS: ([\d.]+)", l)
            if m:
                return float(m.group(1)), float(m.group(2))
        return None


def ler_ids_pipes(copia, con):
    """Le o id numerico dos canos do BuildCraft do level.dat da copia (apos save-all)."""
    import gzip
    import struct
    con.enviar("save-all")
    lvl = None
    t0 = time.time()
    while time.time() - t0 < 90 and not lvl:  # o level.dat so aparece quando o save-all termina
        time.sleep(3)
        for nivel in ("world-test", "world"):
            p = os.path.join(copia, nivel, "level.dat")
            if os.path.exists(p):
                lvl = p
    time.sleep(2)
    if not lvl:
        return dict(PIPE_ID_PADRAO), "padrao (level.dat nao achado)"
    d = gzip.open(lvl).read()
    out = {}
    for m in re.finditer(rb"\x03\x00\x01V(....)\x08\x00\x01K(..)\x02", d, re.S):
        ln = struct.unpack(">H", m.group(2))[0]
        nm = d[m.end():m.end() + ln - 1].decode("latin1")
        out[nm] = struct.unpack(">i", m.group(1))[0]
    a = out.get("BuildCraft|Transport:item.buildcraftPipe.pipeitemswood")
    b = out.get("BuildCraft|Transport:item.buildcraftPipe.pipeitemscobblestone")
    if a and b:
        return {"madeira": a, "pedra_cobble": b}, "lidos do level.dat da copia"
    return dict(PIPE_ID_PADRAO), "padrao (nomes nao achados)"


def aplicar(args):
    copia = os.path.abspath(args.copia)
    con = Console(copia)
    if not os.path.exists(os.path.join(copia, "HOSHIESTAR-TEST-ENV.txt")):
        raise SystemExit("nao e uma copia de teste (falta HOSHIESTAR-TEST-ENV.txt)")
    ids, origem = ler_ids_pipes(copia, con)
    print("ids dos canos (%s): %s" % (origem, ids))
    plano = gerar_plano(args.layout, args.bases, args.kits, ids)
    with open(os.path.join(copia, "carga-plano.json"), "w") as f:
        json.dump(plano, f)
    nomes = [b["nome"] for b in plano["bases"]]
    cmds = os.path.join(copia, "carga-bots.cmds")
    open(cmds, "w").close()
    base_antes = con.chunks()
    print("antes das bases: chunks/TE/entidades da dim 0 =", base_antes)
    # bots parados, um por base (um processo por bot); entram no spawn e depois recebem /tp.
    here = os.path.dirname(os.path.abspath(__file__))
    bot = os.path.join(here, "bot_anda.py")
    logb = os.path.join(copia, "carga-bots.log")
    open(logb, "w").close()

    def entrou(nome):
        return ("[%s] join game" % nome) in open(logb, errors="replace").read()

    for n in nomes:
        for tentativa in (1, 2, 3):
            subprocess.Popen([sys.executable, "-I", bot, "--nomes", n, "--rota", "parado", "--tempo", "0",
                              "--cmds", cmds, "--log", logb, "--espera-entrada", "60"],
                             stdout=open(os.path.join(copia, "carga-bots.out"), "a"), stderr=subprocess.STDOUT,
                             start_new_session=True)
            t0 = time.time()
            while time.time() - t0 < 70 and not entrou(n):
                time.sleep(1)
            if entrou(n):
                break
            print("%s: nao entrou (tentativa %d); tentando de novo" % (n, tentativa))
        else:
            raise SystemExit("bot %s nao conseguiu entrar" % n)
        time.sleep(3)
    for b in plano["bases"]:
        n = b["nome"]
        cx, cz = b["centro"]
        con.enviar("gamemode 1 " + n)
        con.enviar("tp %s %d %d %d" % (n, b["bloco_min"][0] - 4, Y0 + 1, cz))
        # espera o chunk do canto mais distante carregar: testforblock no leito de rocha (y=0)
        ok = False
        t0 = time.time()
        while time.time() - t0 < 180 and not ok:
            time.sleep(4)
            x2, z2 = b["bloco_max"]
            # setblock de rocha-mae em y=0 (ja e rocha-mae no mundo plano): inofensivo. Chunk descarregado responde
            # "outside of the world"; carregado responde "Block placed" ou "couldn't be placed".
            # (testforblock nao escreve resposta no console deste servidor.)
            l = con.pergunta("setblock %d 0 %d minecraft:bedrock 0" % (x2, z2), r"couldn't be placed|Block placed|outside of the world", 6)
            ok = bool(l and not re.search(r"outside of the world", l))
        print("%s: chunks do canto %s" % (n, "carregados em %.0fs" % (time.time() - t0) if ok else "NAO CARREGARAM"))
        t0 = time.time()
        cs = [] if args.so_bots else b["comandos"]
        for i in range(0, len(cs), 40):
            con.enviar(*cs[i:i + 40])
            time.sleep(1.0)
        print("%s: %d comandos enviados em %.0fs" % (n, len(cs), time.time() - t0))
        time.sleep(3)
        print("%s: apos a base, dim 0 (chunks, tile entities, entidades) = %s" % (n, con.chunks()))
    time.sleep(5)
    depois = con.chunks()
    print("depois das bases: chunks/TE/entidades da dim 0 =", depois)
    esperados = 0 if args.so_bots else sum(b["blocos_com_te_estimados"] for b in plano["bases"])
    time.sleep(30)
    for i in range(3):
        print("forge tps (media dos ultimos 100 ticks, ms; TPS) apos montar, leitura %d: %s" % (i + 1, con.tps()))
        time.sleep(15)
    print("blocos com TE enviados (estimativa) = %d" % esperados)


def conferir(args):
    plano = json.load(open(args.plano))
    med = json.load(open(args.medicao))
    sec = {(s["dim"], s["secao_x"], s["secao_z"]): s for s in med["secoes"]}
    chunks = {(c["dim"], c["chunk_x"], c["chunk_z"]): c for c in med["chunks"]}
    ok = True
    print("%-8s %-22s %-28s %12s %10s" % ("base", "secoes esperadas", "te_ticks por secao (dim 0)", "te_ticks_base", "chunks"))
    for b in plano["bases"]:
        cmin, cmax = b["chunk_min"], b["chunk_max"]
        te_base = 0
        n_chunks = 0
        for (d, cx, cz), c in chunks.items():
            if d == 0 and cmin[0] <= cx <= cmax[0] and cmin[1] <= cz <= cmax[1]:
                te_base += c["te_ticks"]
                n_chunks += 1 if c["te_ticks"] > 0 else 0
        por_sec = [(tuple(s), sec.get((0, s[0], s[1]), {}).get("te_ticks", 0)) for s in b["secoes"]]
        print("%-8s %-22s %-28s %12d %10d" % (b["nome"], b["secoes"], por_sec, te_base, n_chunks))
        if te_base == 0:
            ok = False
    total_te = sum(c["te_ticks"] for c in med["chunks"] if c["dim"] == 0)
    fora = total_te - sum(
        c["te_ticks"] for b in plano["bases"] for c in med["chunks"]
        if c["dim"] == 0 and b["chunk_min"][0] <= c["chunk_x"] <= b["chunk_max"][0]
        and b["chunk_min"][1] <= c["chunk_z"] <= b["chunk_max"][1])
    print("te_ticks fora das bases (dim 0):", fora, "de", total_te)
    print("RESULTADO:", "todas as bases aparecem no medidor" if ok else "ALGUMA BASE SEM te_ticks")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for nome in ("gerar", "aplicar"):
        p = sub.add_parser(nome)
        p.add_argument("--layout", choices=["a", "b", "c"], required=True)
        p.add_argument("--bases", type=int, default=4)
        p.add_argument("--kits", "--tamanho", dest="kits", type=int, default=2)
        if nome == "gerar":
            p.add_argument("--saida", required=True)
        else:
            p.add_argument("--copia", required=True)
            p.add_argument("--so-bots", action="store_true",
                           help="so poe os bots parados nos lugares das bases, sem construir (controle: custo dos chunks)")
    p = sub.add_parser("conferir")
    p.add_argument("--plano", required=True)
    p.add_argument("--medicao", required=True)
    sub.add_parser("catalogo")
    a = ap.parse_args()
    if a.cmd == "gerar":
        plano = gerar_plano(a.layout, a.bases, a.kits, PIPE_ID_PADRAO)
        json.dump(plano, open(a.saida, "w"))
        for b in plano["bases"]:
            print(b["nome"], "centro", b["centro"], "chunks", b["chunk_min"], b["chunk_max"], "secoes", b["secoes"],
                  "TE", b["blocos_com_te_estimados"], "comandos", len(b["comandos"]),
                  "cruza", b["linhas_que_cruzam_borda_de_secao"])
    elif a.cmd == "aplicar":
        aplicar(a)
    elif a.cmd == "conferir":
        sys.exit(conferir(a))
    else:
        for nome, m in GRUPOS:
            print(nome, m)


if __name__ == "__main__":
    main()
