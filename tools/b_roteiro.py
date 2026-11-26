#!/usr/bin/env python3
"""Hoshiestar - roteiro de UMA execucao dos testes B1 (conservacao) e B2 (diferencial) da Etapa 2.5.

Para uma COPIA DE TESTE ja montada com tools/b_monta_mundo.py (e parada), este script: liga a copia (jar atual,
orcamento ligado ou desligado), poe o bot CargaA na base A, tira "fotos" do mundo a cada 2 minutos (save-all e copia dos
arquivos .mca e do level.dat; ao mesmo tempo, o livro-caixa 'hoshie mekanism ... mais ...' e, com o orcamento
ligado, 'hoshie regioes contar'), opcionalmente forca a regiao A a estourar (150 ms por rodada) e a deixa em corte,
tira uma foto "calibrada" perto de um tick do mundo T* (para o diferencial) e, no fim, para o servidor com 'stop'.
Nada de kill. Saidas em <saida>/<rotulo>/ (fotos, contagens offline, contagens do jogo, livro-caixa, log).

Uso:
  b_roteiro.py rodar  <copia> <saida> <rotulo> --ligado sim|nao --modo medir|cortar --forcar sim|nao
                      [--minutos 10] [--tstar TICK] [--teste-prop sim|nao]
  b_roteiro.py reabrir <copia> <saida> <rotulo> --ligado sim|nao --modo medir|cortar   (depois de um 'rodar')
Regras: um servidor de teste por vez; so em copia de teste. Numero de carga sintetica e indicacao, nao medicao.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import carga_sintetica as cs  # noqa: E402
import mundo_nbt as N  # noqa: E402
import mundo_contar as MC  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.dirname(HERE)
AX, AZ = 3003, 3003
CAIXA_A = (2985, 2985, 3025, 3025)
CAIXA_S = (1405, 130, 1450, 180)   # base S2 (dinamica, fora da regiao cortada), em torno de (1420, 160)
SX_, SZ_ = 1420, 150
LIVRO = "hoshie mekanism 3010 103 3013 0 mais 3009 103 3013 3009 104 3013"


class Copia:
    def __init__(self, d, saida):
        self.d = os.path.abspath(d)
        self.con = cs.Console(self.d)
        self.saida = saida
        os.makedirs(saida, exist_ok=True)
        self.t0 = time.time()
        self.eventos = []

    def log(self, s):
        l = "%s %s" % (time.strftime("%H:%M:%S"), s)
        print(l, flush=True)
        self.eventos.append(l)

    # ---------------------------------------------------------------- console
    def linhas_novas(self, desde):
        with open(self.con.log, errors="replace") as f:
            return [l for i, l in enumerate(f) if i >= desde]

    def cmd(self, c, espera=4.0, todas=False):
        """Manda um comando e devolve as linhas de resposta (sendRawMessage), sem prefixo."""
        n = self.con.n_linhas()
        self.con.enviar(c)
        time.sleep(espera)
        out = []
        for l in self.linhas_novas(n):
            if "sendRawMessage" in l or "sendMessage" in l:
                out.append(re.sub(r"^.*send(?:Raw)?Message:\d+\]: ", "", l.rstrip("\n")))
            elif todas:
                out.append(l.rstrip("\n"))
        return out

    def lote(self, cmds, espera):
        """Manda varios comandos de uma vez (a mesma escrita: o servidor os pega no mesmo tick) e junta as respostas."""
        n = self.con.n_linhas()
        self.con.enviar(*cmds)
        time.sleep(espera)
        return [l.rstrip("\n") for l in self.linhas_novas(n)]

    # ---------------------------------------------------------------- servidor
    def ligar(self, ligado, modo, teste):
        self.config(ligado, modo)
        env = dict(os.environ)
        env.pop("JAVA_TOOL_OPTIONS", None)
        if teste:
            env["JAVA_TOOL_OPTIONS"] = "-Dhoshiestar.orcamento.teste=true"
        try:
            os.remove(self.con.log)
        except OSError:
            pass
        subprocess.Popen([os.path.join(RAIZ, "scripts/run-test-server.sh"), self.d, "8G"], env=env,
                         stdout=open(os.path.join(self.d, "run.log"), "w"), stderr=subprocess.STDOUT, start_new_session=True)
        t = time.time()
        while time.time() - t < 900:
            time.sleep(10)
            try:
                if "Done (" in open(self.con.log, errors="replace").read():
                    break
            except OSError:
                pass
        else:
            raise SystemExit("sem 'Done' em 15 min (copia travada: 2 thread dumps antes de qualquer kill)")
        self.log("servidor pronto em %.0f s" % (time.time() - t))
        time.sleep(15)

    def config(self, ligado, modo):
        """Edita so as chaves 'ligado' e 'modo' da secao orcamento do hoshiestar.yml da copia (cria no 1o boot)."""
        p = os.path.join(self.d, "hoshiestar.yml")
        s = open(p).read().split("\n")
        dentro = False
        for i, l in enumerate(s):
            if re.match(r"^[A-Za-z_]+:\s*$", l):
                dentro = l.startswith("orcamento:")
            elif dentro and re.match(r"^  ligado:", l):
                s[i] = "  ligado: %s" % ("true" if ligado else "false")
            elif dentro and re.match(r"^  modo:", l):
                s[i] = "  modo: %s" % modo
        open(p, "w").write("\n".join(s))

    def parar(self):
        self.con.enviar("save-all")
        time.sleep(8)
        self.con.enviar("stop")
        t = time.time()
        while time.time() - t < 300:
            time.sleep(3)
            if subprocess.call(["pgrep", "-f", "server.jar nogui"], stdout=subprocess.DEVNULL) != 0:
                break
        else:
            raise SystemExit("o servidor ignorou o stop por 5 min: 2 thread dumps antes de kill (CLAUDE.md)")
        crash = os.path.isdir(os.path.join(self.d, "crash-reports"))
        self.log("servidor parado com stop em %.0f s; pasta crash-reports: %s" % (time.time() - t, "EXISTE" if crash else "nao existe"))
        linhas = [l for l in open(self.con.log, errors="replace") if re.search(r"Hoshiestar.*(parad|parada)|Stopping server|Saving worlds", l)]
        for l in linhas[-3:]:
            self.log("log: " + l.strip()[:200])

    # ---------------------------------------------------------------- bot
    def bot_s(self):
        """Bot parado perto da base S2 (mantem os chunks dela carregados, fora da regiao A)."""
        cmds = os.path.join(self.d, "carga-bots2.cmds")
        logb = os.path.join(self.d, "carga-bots2.log")
        open(cmds, "w").close()
        open(logb, "w").close()
        for _ in range(3):
            subprocess.Popen([sys.executable, "-I", os.path.join(HERE, "bot_anda.py"), "--nomes", "CargaS", "--rota", "parado",
                              "--tempo", "0", "--cmds", cmds, "--log", logb, "--espera-entrada", "60"],
                             stdout=open(os.path.join(self.d, "carga-bots2.out"), "a"), stderr=subprocess.STDOUT,
                             start_new_session=True)
            t = time.time()
            while time.time() - t < 70 and "[CargaS] join game" not in open(logb, errors="replace").read():
                time.sleep(1)
            if "[CargaS] join game" in open(logb, errors="replace").read():
                break
        else:
            raise SystemExit("bot S nao entrou")
        time.sleep(3)
        for _ in range(6):
            self.con.enviar("gamemode 1 CargaS")
            self.con.enviar("tp CargaS %d 5 %d" % (SX_, SZ_))
            time.sleep(20)
            pos = re.findall(r"\[CargaS\] pos (-?[\d.]+) ", open(logb, errors="replace").read())
            if pos and abs(float(pos[-1]) - SX_) < 10:
                break
        self.log("bot S na base S2")

    def bot(self):
        self.bot_s()
        cmds = os.path.join(self.d, "carga-bots.cmds")
        logb = os.path.join(self.d, "carga-bots.log")
        open(cmds, "w").close()
        open(logb, "w").close()
        for _ in range(3):
            subprocess.Popen([sys.executable, "-I", os.path.join(HERE, "bot_anda.py"), "--nomes", "CargaA", "--rota", "parado",
                              "--tempo", "0", "--cmds", cmds, "--log", logb, "--espera-entrada", "60"],
                             stdout=open(os.path.join(self.d, "carga-bots.out"), "a"), stderr=subprocess.STDOUT,
                             start_new_session=True)
            t = time.time()
            while time.time() - t < 70 and "[CargaA] join game" not in open(logb, errors="replace").read():
                time.sleep(1)
            if "[CargaA] join game" in open(logb, errors="replace").read():
                break
        else:
            raise SystemExit("bot nao entrou")
        time.sleep(3)
        self.con.enviar("gamemode 1 CargaA")
        for _ in range(8):   # logo apos o login algo devolve o bot ao spawn (~4 s depois): repete ate a posicao ficar
            self.con.enviar("tp CargaA %d 5 %d" % (AX, AZ))
            time.sleep(20)
            pos = re.findall(r"\[CargaA\] pos (-?[\d.]+) ", open(logb, errors="replace").read())
            if pos and abs(float(pos[-1]) - AX) < 10:
                break
        t = time.time()
        while time.time() - t < 180:
            l = self.con.pergunta("setblock %d 0 %d minecraft:bedrock 0" % (AX + 12, AZ + 12), r"couldn't be placed|Block placed|outside of the world", 6)
            if l and "outside of the world" not in l:
                break
            time.sleep(4)
        self.log("bot na base A; chunks da base carregados em %.0f s" % (time.time() - t))

    def bot_sair(self):
        for n in ("carga-bots.cmds", "carga-bots2.cmds"):
            open(os.path.join(self.d, n), "a").write("SAIR\n")
        time.sleep(6)

    # ---------------------------------------------------------------- fotos e contagens
    def foto(self, rot, ligado, id_a=None):
        """save-all + livro-caixa (+ contar) no mesmo tick; copia o mundo; conta offline. Devolve dict."""
        d = os.path.join(self.saida, "snap-" + rot)
        os.makedirs(d, exist_ok=True)
        cmds = ["save-all", LIVRO]
        arq = "contar-%s-%s.txt" % (os.path.basename(self.saida.rstrip("/")), rot)
        if ligado and id_a is not None:
            cmds.append("hoshie regioes contar %d %s" % (id_a, arq))
        n = self.con.n_linhas()
        self.con.enviar(*cmds)
        quando = time.time()   # o save-all roda no inicio do proximo tick e o level.dat leva o tick desse instante
        t = quando
        while time.time() - t < 90:
            time.sleep(0.5)
            txt = "".join(self.linhas_novas(n))
            if "Save complete" in txt:
                break
        time.sleep(3)
        txt = "".join(self.linhas_novas(n))
        m = re.search(r"livro-caixa \(soma direta \+ extras\): ([0-9.E+-]+) J", txt)
        livro = float(m.group(1)) if m else None
        open(os.path.join(d, "livro.txt"), "w").write(txt_so(txt, "matriz|extra|livro|soma direta|leitura normal"))
        wt = N.tempo_do_mundo(os.path.join(self.d, "world-test"))
        shutil.copytree(os.path.join(self.d, "world-test", "region"), os.path.join(d, "region"), dirs_exist_ok=True)
        shutil.copy(os.path.join(self.d, "world-test", "level.dat"), os.path.join(d, "level.dat"))
        itens, energia, tiles, chao, chunks = MC.contar(d, CAIXA_A)
        with open(os.path.join(d, "offline.txt"), "w") as f:
            MC.escrever(f, itens, energia, tiles, chao, chunks, N.mapa_itens(d))
        iS, eS, tS, cS, _ = MC.contar(d, CAIXA_S)
        with open(os.path.join(d, "offline-S.txt"), "w") as f:
            MC.escrever(f, iS, eS, tS, cS, 0, N.mapa_itens(d))
        jogo = None
        if ligado and id_a is not None:
            p = os.path.join(self.d, "hoshiestar", arq)
            if os.path.exists(p):
                shutil.copy(p, os.path.join(d, "contar-jogo.txt"))
                jogo = open(p).read().strip().split("\n")[-1]
        r = {"rot": rot, "tick_mundo": wt, "quando": quando, "livro": livro, "itens": sum(itens.values()), "tipos": len(itens),
             "energia_nbt": sum(energia.values()), "itens_S": sum(iS.values()), "tiles": tiles, "chao": chao, "jogo": jogo}
        self.log("foto %s: tick do mundo %d; livro-caixa %s J; offline itens=%d tipos=%d energia_nbt=%.3f tiles=%d; jogo: %s"
                 % (rot, wt, livro, r["itens"], r["tipos"], r["energia_nbt"], tiles, jogo))
        return r

    def id_a(self):
        for l in self.cmd("hoshie regioes", 5):
            m = re.search(r"#(\d+) dim 0 x (-?\d+)\.\.(-?\d+) z (-?\d+)\.\.(-?\d+)", l)
            if m and int(m.group(2)) <= AX <= int(m.group(3)) and int(m.group(4)) <= AZ <= int(m.group(5)):
                return int(m.group(1)), l.strip()
        return None, None


def txt_so(txt, rx):
    return "\n".join(l for l in txt.split("\n") if re.search(rx, l) and "STDOUT" not in l) + "\n"


def rodar(a):
    ligado = a.ligado == "sim"
    teste = ligado if a.teste_prop is None else a.teste_prop == "sim"
    saida = os.path.join(os.path.abspath(a.saida), a.rotulo)
    c = Copia(a.copia, saida)
    c.log("== rodar %s: ligado=%s modo=%s forcar=%s minutos=%d tstar=%s" % (a.rotulo, ligado, a.modo, a.forcar, a.minutos, a.tstar))
    c.ligar(ligado, "medir" if a.forcar == "sim" else a.modo, teste)
    wt0 = N.tempo_do_mundo(os.path.join(c.d, "world-test"))
    c.log("tick do mundo no level.dat (gravado na parada anterior): %d" % wt0)
    c.bot()
    time.sleep(45)   # matriz forma, aneis giram
    fotos = []
    fotos.append(c.foto("t0", ligado, c.id_a()[0] if ligado else None))
    fotos.append(c.foto("t0b", ligado, c.id_a()[0] if ligado else None))   # 2a foto logo depois: calibra o relogio
    ida = None
    if ligado:
        ida, l = c.id_a()
        c.log("regiao de A: id %s: %s" % (ida, (l or "")[:200]))
        if a.tstar:
            ex = "%d" % ida
            for l2 in c.cmd("hoshie regioes foto foto-%s.txt %d %s" % (a.rotulo, a.tstar, ex), 3):
                c.log("pedido de foto exata: " + l2)
        c.cmd("hoshie regioes registro %d" % (a.minutos * 60 + 240), 3)
        if a.forcar == "sim":
            for l2 in c.cmd("hoshie regioes teste %d 150" % ida, 3):
                c.log("teste: " + l2)
            for l2 in c.cmd("hoshie regioes modo cortar", 3):
                c.log("modo: " + l2)
            time.sleep(25)
            for l2 in c.cmd("hoshie regioes %d" % ida, 5):
                c.log("regiao A (25 s depois): " + l2.strip()[:230])
        t_cut = time.time()
    inicio = time.time()
    pend_tstar = bool(a.tstar)
    prox = 120.0
    while True:
        # relogio do mundo estimado pelas duas ultimas fotos
        f1, f2 = fotos[-2], fotos[-1]
        taxa = (f2["tick_mundo"] - f1["tick_mundo"]) / max(1e-6, f2["quando"] - f1["quando"]) if f2["quando"] > f1["quando"] else 20.0
        taxa = min(max(taxa, 5.0), 20.5)
        agora = time.time()
        decorrido = agora - inicio
        alvos = [(prox, "ponto")]
        if pend_tstar:
            due = f2["quando"] + (a.tstar - f2["tick_mundo"]) / taxa - 0.2   # 0,2 s de folga: o comando entra no inicio do proximo tick
            alvos.append((due - inicio, "tstar"))
        t_alvo, que = min(alvos)
        if decorrido < t_alvo:
            time.sleep(min(t_alvo - decorrido, 10.0))
            continue
        if que == "tstar":
            pend_tstar = False
            n = c.con.n_linhas()
            f = c.foto("tstar", ligado, ida)
            fotos.append(f)
            c.log("foto calibrada: tick pedido %d, tick gravado %d (diferenca %+d)" % (a.tstar, f["tick_mundo"], f["tick_mundo"] - a.tstar))
            continue
        # ponto de 2 em 2 minutos
        if int(prox) > a.minutos * 60:
            break
        fotos.append(c.foto("m%02d" % (int(prox) // 60), ligado, ida))
        if ligado:
            for l2 in c.cmd("hoshie regioes %d" % ida, 4)[:2]:
                c.log("regiao A: " + l2.strip()[:230])
        for l2 in c.cmd("forge tps", 3):
            if "Overall" in l2:
                c.log("forge tps: " + l2.strip()[:120])
        prox += 120.0
    if ligado and a.forcar == "sim":
        c.cmd("hoshie regioes teste parar", 3)
        c.log("carga forcada retirada")
        t = time.time()
        while time.time() - t < 180:
            l = [x for x in c.cmd("hoshie regioes %d" % ida, 4) if "#%d " % ida in x]
            if l and re.search(r"\| N 1 \|", l[0]) and "normal" in l[0]:
                break
            time.sleep(8)
        c.log("regiao A apos retirar a carga (%.0f s): %s" % (time.time() - t, (l[0].strip() if l else "?")[:230]))
    fotos.append(c.foto("depois", ligado, ida))
    time.sleep(60)
    fotos.append(c.foto("depois60", ligado, ida))
    if ligado:
        for p in os.listdir(os.path.join(c.d, "hoshiestar")):
            if p.startswith("orcamento-registro-") or p.startswith("foto-"):
                shutil.copy(os.path.join(c.d, "hoshiestar", p), os.path.join(saida, p))
    c.bot_sair()
    c.parar()
    # log do servidor: so o que importa (orcamento, erros nossos), sem o ruido de STDOUT
    with open(os.path.join(saida, "log-servidor.txt"), "w") as f:
        for l in open(c.con.log, errors="replace"):
            if re.search(r"orcamento|Hoshiestar|Exception in|crash|FATAL|\[ERROR\]", l) and "STDOUT" not in l and "LegacyAgent" not in l:
                f.write(l[:400] if l.endswith("\n") else l[:400] + "\n")
    # mundo final (apos o stop) para o diferencial
    fin = os.path.join(saida, "final")
    os.makedirs(fin, exist_ok=True)
    shutil.copytree(os.path.join(c.d, "world-test", "region"), os.path.join(fin, "region"), dirs_exist_ok=True)
    shutil.copy(os.path.join(c.d, "world-test", "level.dat"), os.path.join(fin, "level.dat"))
    json.dump({"fotos": fotos, "wt0": wt0, "eventos": c.eventos}, open(os.path.join(saida, "resumo.json"), "w"), indent=1)
    c.log("== fim de %s" % a.rotulo)
    open(os.path.join(saida, "eventos.txt"), "w").write("\n".join(c.eventos) + "\n")


def reabrir(a):
    ligado = a.ligado == "sim"
    saida = os.path.join(os.path.abspath(a.saida), a.rotulo)
    c = Copia(a.copia, saida)
    c.log("== reabrir %s: ligado=%s modo=%s" % (a.rotulo, ligado, a.modo))
    c.ligar(ligado, a.modo, ligado)
    fotos = []
    # contagem logo depois de religar, ANTES de a base A voltar a ticar (chunks da base descarregados): so offline
    d = os.path.join(saida, "snap-ao-ligar")
    os.makedirs(d, exist_ok=True)
    c.con.enviar("save-all")
    time.sleep(10)
    shutil.copytree(os.path.join(c.d, "world-test", "region"), os.path.join(d, "region"), dirs_exist_ok=True)
    shutil.copy(os.path.join(c.d, "world-test", "level.dat"), os.path.join(d, "level.dat"))
    itens, energia, tiles, chao, chunks = MC.contar(d, CAIXA_A)
    with open(os.path.join(d, "offline.txt"), "w") as f:
        MC.escrever(f, itens, energia, tiles, chao, chunks, N.mapa_itens(d))
    c.log("ao ligar (base A ainda sem chunk carregado): offline itens=%d tipos=%d energia_nbt=%.3f" % (sum(itens.values()), len(itens), sum(energia.values())))
    c.bot()
    time.sleep(8)   # o mapa de regioes se recalcula a cada 100 ticks
    ida = c.id_a()[0] if ligado else None
    fotos.append(c.foto("reaberto0", ligado, ida))
    time.sleep(60)
    fotos.append(c.foto("reaberto60", ligado, ida))
    c.bot_sair()
    c.parar()
    json.dump({"fotos": fotos, "eventos": c.eventos}, open(os.path.join(saida, "resumo.json"), "w"), indent=1)
    open(os.path.join(saida, "eventos.txt"), "w").write("\n".join(c.eventos) + "\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("modo_execucao", choices=["rodar", "reabrir"])
    ap.add_argument("copia")
    ap.add_argument("saida")
    ap.add_argument("rotulo")
    ap.add_argument("--ligado", default="nao")
    ap.add_argument("--modo", default="medir")
    ap.add_argument("--forcar", default="nao")
    ap.add_argument("--minutos", type=int, default=10)
    ap.add_argument("--tstar", type=int, default=0)
    ap.add_argument("--teste-prop", default=None)
    a = ap.parse_args()
    if a.modo_execucao == "rodar":
        rodar(a)
    else:
        reabrir(a)


if __name__ == "__main__":
    main()
