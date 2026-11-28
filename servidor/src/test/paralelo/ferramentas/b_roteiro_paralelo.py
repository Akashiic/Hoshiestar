#!/usr/bin/env python3
"""Hoshiestar - roteiro dos boots de teste do Degrau B (passo 2): liga uma COPIA DE TESTE num mundo salvo, congela os
mundos num tick exato ('hoshie paralelo congelar', so com -Dhoshiestar.paralelo.teste=true), para com 'stop' e guarda o
mundo; ou mede a mediana do tick. Um servidor de teste por vez. Nunca usa kill.

Subcomandos:
  base      <copia> <saida-mundo> [--bases 16] [--espaco 64] [--hostil sim|nao] [--jar J] [--prop=-Dx=y ...]
            liga a copia (mundo plano novo se nao houver mundo), regras fixas (sem mob, sem fogo, sem ciclo do dia, tempo
            limpo), monta a grade com b_monta_paralelo.py, 'save-all', para e copia o mundo para <saida-mundo>.
  congelado <copia> <mundo-base> <saida-mundo> [--ticks 1200] [--jar J] [--prop ...] [--medir N]
            restaura <mundo-base>, liga (com -Dhoshiestar.paralelo.teste=true), opcionalmente mede N leituras do tick,
            pede 'congelar' no tick (Time do level.dat da base + --ticks), espera o congelamento, para e copia o mundo.
  medir     <copia> <mundo-base> <saida.txt> [--jar J] [--prop ...] [--leituras 15]
            restaura <mundo-base>, liga, espera 60 s, le 'forge tps' a cada 20 s (15 leituras = 5 min), para.
            Imprime a mediana do "Mean tick time" geral.
  parar     <copia>

Depois: mundo_comparar.py mca <saida1> <saida2> (tools/ do repositorio) deve dar 0 diferencas para duas execucoes
desligadas congeladas no mesmo tick (o "pronto quando" do passo 2).

Opcoes comuns: --raiz <repositorio Hoshiestar> (padrao: variavel HOSHIE_RAIZ, ou /home/user/Hoshiestar) para achar
scripts/run-test-server.sh. --prop acrescenta propriedades da JVM via JAVA_TOOL_OPTIONS (o run-test-server.sh nao tem
outra entrada); escreva --prop=-Dx=y (com "="), porque o valor comeca com "-". Numeros daqui sao de carga sintetica: indicacao, nao medicao de producao.
"""
import argparse
import glob
import os
import re
import shutil
import statistics
import subprocess
import sys
import time

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
import b_monta_paralelo as monta  # noqa: E402

MUNDO = "world-test"


def log(s):
    print("[roteiro %s] %s" % (time.strftime("%H:%M:%S"), s), flush=True)


def pids_da(copia):
    out = []
    r = subprocess.run(["pgrep", "-f", "server.jar nogui"], stdout=subprocess.PIPE, universal_newlines=True)
    for p in r.stdout.split():
        try:
            if os.readlink("/proc/%s/cwd" % p) == copia:
                out.append(int(p))
        except OSError:
            pass
    return out


def algum_servidor():
    r = subprocess.run(["pgrep", "-f", "server.jar nogui"], stdout=subprocess.PIPE, universal_newlines=True)
    return r.stdout.split()


def tamanho(arq):
    try:
        return os.path.getsize(arq)
    except OSError:
        return 0


def esperar_texto(arq, texto, desde=0, limite=900, passo=5):
    fim = time.time() + limite
    while time.time() < fim:
        if os.path.isfile(arq):
            with open(arq, errors="replace") as f:
                f.seek(desde)
                if texto in f.read():
                    return True
        time.sleep(passo)
    return False


def ligar(a, copia, props):
    if algum_servidor():
        sys.exit("ERRO: ha um servidor de teste ligado (um por vez)")
    monta.checar_copia(copia)
    if a.jar:
        shutil.copyfile(a.jar, os.path.join(copia, "server.jar"))
    sha = subprocess.run(["sha256sum", os.path.join(copia, "server.jar")], stdout=subprocess.PIPE,
                         universal_newlines=True).stdout.split()[0]
    env = dict(os.environ)
    if props:
        env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " " + " ".join(props)).strip()
    cl = os.path.join(copia, "console.log")
    if os.path.exists(cl):
        os.remove(cl)
    with open(os.path.join(copia, "run.log"), "w") as rl:
        subprocess.Popen(["nohup", os.path.join(a.raiz, "scripts", "run-test-server.sh"), copia, a.memoria],
                         stdout=rl, stderr=subprocess.STDOUT, env=env, start_new_session=True)
    log("ligando (jar %s..., propriedades %s); esperando o 'Done' (ate 15 min)" % (sha[:16], props or "nenhuma"))
    t0 = time.time()
    if not esperar_texto(cl, "Done (", limite=900):
        log("ERRO: sem 'Done' em 15 min. Copia travada? Regra do CLAUDE.md: dois thread dumps antes de qualquer kill.")
        sys.exit(3)
    log("Done em %.0f s" % (time.time() - t0))
    return sha


def cmd(copia, c):
    with open(os.path.join(copia, "console.in"), "a") as f:
        f.write(c + "\n")


def resposta(copia, c, espera=5):
    cl = os.path.join(copia, "console.log")
    n = tamanho(cl)
    cmd(copia, c)
    time.sleep(espera)
    with open(cl, errors="replace") as f:
        f.seek(n)
        return f.read()


def parar(copia, dumps_em=None):
    if not pids_da(copia):
        log("copia ja parada")
        return True
    cmd(copia, "stop")
    fim = time.time() + 300
    while time.time() < fim:
        if not pids_da(copia):
            log("copia parada com 'stop'")
            return True
        time.sleep(3)
    log("AINDA LIGADA 5 min depois do 'stop'. NAO usei kill. Guardando dois thread dumps (CLAUDE.md).")
    for k in (1, 2):
        for p in pids_da(copia):
            destino = os.path.join(dumps_em or copia, "threaddump-%d-%d.txt" % (p, k))
            with open(destino, "w") as f:
                subprocess.run(["jcmd", str(p), "Thread.print"], stdout=f, stderr=subprocess.STDOUT)
        time.sleep(10)
    return False


def restaurar(copia, base):
    for d in glob.glob(os.path.join(copia, MUNDO + "*")):
        shutil.rmtree(d)
    shutil.copytree(base, os.path.join(copia, MUNDO), symlinks=True)


def guardar(copia, saida):
    if os.path.exists(saida):
        shutil.rmtree(saida)
    shutil.copytree(os.path.join(copia, MUNDO), saida, symlinks=True)


def regras(copia):
    for c in ("gamerule doMobSpawning false", "gamerule doFireTick false", "gamerule doDaylightCycle false",
              "weather clear 1000000", "difficulty 0"):
        cmd(copia, c)
    time.sleep(3)


def mediana_tick(arq):
    v = []
    for l in open(arq, errors="replace"):
        m = re.search(r"Overall : Mean tick time: ([0-9.]+) ms", l)
        if m:
            v.append(float(m.group(1)))
    return (statistics.median(v) if v else None), v


def medir(copia, saida, leituras):
    cl = os.path.join(copia, "console.log")
    with open(saida, "w") as out:
        for i in range(leituras):
            n = tamanho(cl)
            cmd(copia, "forge tps")
            time.sleep(4)
            with open(cl, errors="replace") as f:
                f.seek(n)
                linhas = [l for l in f.read().splitlines() if "Overall" in l]
            out.write("%s %s\n" % (time.strftime("%H:%M:%S"), linhas[-1] if linhas else "(sem resposta)"))
            out.flush()
            if i < leituras - 1:
                time.sleep(16)
    med, v = mediana_tick(saida)
    log("mediana do tick (%d leituras): %s ms" % (len(v), med))
    return med


def sub_base(a):
    copia = os.path.abspath(a.copia)
    monta.checar_copia(copia)
    if not os.path.isdir(os.path.join(copia, MUNDO)):
        # mundo plano novo (mesmos valores do scripts/aplicar-carga-sintetica.sh)
        p = os.path.join(copia, "server.properties")
        s = open(p).read()
        for k, v in (("level-type", "FLAT"), ("generator-settings", "2;7,2x3,2;1;"), ("spawn-monsters", "false"),
                     ("spawn-animals", "false"), ("spawn-npcs", "false"), ("allow-flight", "true")):
            if re.search(r"(?m)^%s=" % re.escape(k), s):
                s = re.sub(r"(?m)^%s=.*$" % re.escape(k), "%s=%s" % (k, v), s)
            else:
                s += "%s=%s\n" % (k, v)
        open(p, "w").write(s)
        log("server.properties: mundo plano, sem mobs (so as chaves de mundo; nada impresso)")
    ligar(a, copia, a.prop)
    time.sleep(20)
    regras(copia)
    r = subprocess.run([sys.executable, "-I", os.path.join(AQUI, "b_monta_paralelo.py"), copia, "--bases", str(a.bases),
                        "--espaco", str(a.espaco), "--hostil", a.hostil], stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, universal_newlines=True)
    print(r.stdout)
    if a.esperar_s:
        log("esperando %d s com a montagem ligada (pedido do roteiro)" % a.esperar_s)
        time.sleep(a.esperar_s)
    cl = os.path.join(copia, "console.log")
    n = tamanho(cl)
    cmd(copia, "save-all")
    esperar_texto(cl, "Save complete", desde=n, limite=120, passo=2)   # resposta do save-all do Bukkit
    ok = parar(copia)
    guardar(copia, os.path.abspath(a.saida))
    log("mundo base guardado em %s (montagem rc=%d)" % (a.saida, r.returncode))
    return 0 if ok and r.returncode == 0 else 1


def sub_congelado(a):
    copia = os.path.abspath(a.copia)
    base = os.path.abspath(a.base)
    t_base = monta.ler_level_dat(os.path.join(base, "level.dat"))["Time"]
    alvo = t_base + a.ticks
    restaurar(copia, base)
    props = list(a.prop)
    if "-Dhoshiestar.paralelo.teste=true" not in props:
        props.append("-Dhoshiestar.paralelo.teste=true")
    ligar(a, copia, props)
    if a.medir:
        time.sleep(60)
        medir(copia, os.path.abspath(a.saida) + ".tps.txt", a.medir)
    cl = os.path.join(copia, "console.log")
    n = tamanho(cl)
    txt = resposta(copia, "hoshie paralelo congelar %d" % alvo, 5)
    linhas = [l for l in txt.splitlines() if "congelar" in l.lower() or "tick" in l.lower()]
    for l in linhas[-3:]:
        log("resposta: " + l[-200:])
    if not esperar_texto(cl, "mundos congelados no tick", desde=n, limite=max(120, a.ticks // 10 + 300), passo=2):
        log("ERRO: nao congelou (veja o console.log); parando assim mesmo")
        parar(copia)
        return 1
    with open(cl, errors="replace") as f:
        f.seek(n)
        for l in f.read().splitlines():
            if "mundos congelados no tick" in l:
                log(l[-200:])
    time.sleep(15)   # deixa a fila de gravacao dos chunks andar; o 'stop' salva e espera a fila de novo
    ok = parar(copia)
    guardar(copia, os.path.abspath(a.saida))
    log("mundo congelado no tick %d guardado em %s" % (alvo, a.saida))
    return 0 if ok else 1


def sub_medir(a):
    copia = os.path.abspath(a.copia)
    restaurar(copia, os.path.abspath(a.base))
    ligar(a, copia, a.prop)
    time.sleep(60)
    med = medir(copia, os.path.abspath(a.saida), a.leituras)
    ok = parar(copia)
    return 0 if ok and med is not None else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raiz", default=os.environ.get("HOSHIE_RAIZ", "/home/user/Hoshiestar"))
    ap.add_argument("--memoria", default="8G")
    sp = ap.add_subparsers(dest="sub")
    p = sp.add_parser("base")
    p.add_argument("copia")
    p.add_argument("saida")
    p.add_argument("--bases", type=int, default=16)
    p.add_argument("--espaco", type=int, default=64)
    p.add_argument("--hostil", choices=["sim", "nao"], default="sim")
    p.add_argument("--esperar-s", type=int, default=0)
    p.add_argument("--jar")
    p.add_argument("--prop", action="append", default=[])
    p = sp.add_parser("congelado")
    p.add_argument("copia")
    p.add_argument("base")
    p.add_argument("saida")
    p.add_argument("--ticks", type=int, default=1200)
    p.add_argument("--medir", type=int, default=0)
    p.add_argument("--jar")
    p.add_argument("--prop", action="append", default=[])
    p = sp.add_parser("medir")
    p.add_argument("copia")
    p.add_argument("base")
    p.add_argument("saida")
    p.add_argument("--leituras", type=int, default=15)
    p.add_argument("--jar")
    p.add_argument("--prop", action="append", default=[])
    p = sp.add_parser("parar")
    p.add_argument("copia")
    a = ap.parse_args()
    if a.sub == "base":
        return sub_base(a)
    if a.sub == "congelado":
        return sub_congelado(a)
    if a.sub == "medir":
        return sub_medir(a)
    if a.sub == "parar":
        return 0 if parar(os.path.abspath(a.copia)) else 1
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
