#!/usr/bin/env python3
"""Hoshiestar - le um relatorio do spark (JSON exportado) sem enviar nada para fora.

O relatorio do spark e uma arvore de chamadas por thread, com o tempo (ms) gasto
em cada metodo. Este script responde, para uma thread (padrao: "Server thread"):

  resumo   cabecalho do relatorio (TPS, MSPT, jogadores, chunks, JVM)
  arvore   a arvore de chamadas, cortada por profundidade e por % minima
  donos    tempo proprio agrupado por "dono" (pacote do mod/plugin que estava executando)
  tiles    tempo por classe de TileEntity (quem custa mais no tick de tile entities)
  ents     tempo por classe de Entity
  eventos  tempo por handler de evento do Forge (ASMEventHandler_..._Classe_metodo_Evento)
  busca    soma o tempo de todos os nos cujo "classe.metodo" contem um texto

Uso:
  python3 -I tools/spark_report.py <relatorio.json> resumo
  python3 -I tools/spark_report.py <relatorio.json> arvore [--prof 14] [--min 0.5] [--raiz TEXTO]
  python3 -I tools/spark_report.py <relatorio.json> donos  [--top 40] [--raiz TEXTO]
  python3 -I tools/spark_report.py <relatorio.json> tiles  [--top 40]
  python3 -I tools/spark_report.py <relatorio.json> busca TEXTO
  Opcao comum: --thread "Server thread"

Porcentagens sao sempre relativas ao tempo total da thread escolhida.
"""
import argparse, collections, json, re, sys

sys.setrecursionlimit(100000)

NEUTRAL = (
    "java.", "javax.", "jdk.", "sun.", "com.sun.", "it.unimi.", "gnu.trove.", "com.google.",
    "org.apache.", "io.netty.", "scala.", "kotlin.", "org.objectweb.", "com.typesafe.",
    "net.minecraft.", "net.minecraftforge.", "cpw.mods.", "org.bukkit.", "org.spigotmc.",
    "io.github.crucible.", "co.aikar.", "net.md_5.", "com.mojang.", "org.spongepowered.",
    "LZMA.", "paulscode.", "joptsimple.", "org.yaml.", "org.json.", "com.gtnewhorizons.retrofuturabootstrap",
)


class Report:
    def __init__(self, path):
        with open(path, encoding="utf-8") as fh:
            self.d = json.load(fh)
        self.meta = self.d["metadata"]

    def thread(self, name):
        for t in self.d["threads"]:
            if t["name"] == name:
                return Thread(t)
        names = ", ".join(sorted(t["name"] for t in self.d["threads"])[:60])
        raise SystemExit(f"thread '{name}' nao existe. Algumas: {names}")


class Thread:
    def __init__(self, t):
        self.t = t
        self.nodes = t["children"]
        self.roots = t["childrenRefs"]
        self.total = float(sum(t["times"])) or 1.0
        self._incl = [float(sum(n["times"])) for n in self.nodes]

    def label(self, i):
        n = self.nodes[i]
        return f'{n["className"]}.{n["methodName"]}'

    def incl(self, i):
        return self._incl[i]

    def kids(self, i):
        return self.nodes[i]["childrenRefs"]

    def self_time(self, i):
        return max(0.0, self._incl[i] - sum(self._incl[c] for c in self.kids(i)))

    def pct(self, ms):
        return 100.0 * ms / self.total

    def find_roots(self, text):
        """Nos mais altos cujo rotulo contem `text` (sem descer dentro de um ja achado)."""
        out, stack = [], list(self.roots)
        while stack:
            i = stack.pop()
            if text in self.label(i):
                out.append(i)
            else:
                stack.extend(self.kids(i))
        return out


def owner_of(path_labels):
    """Dono = pacote do codigo de mod/plugin mais proximo da folha; senao 'vanilla/forge/bukkit'."""
    for lab in reversed(path_labels):
        if not lab.startswith(NEUTRAL):
            cls = lab.rsplit(".", 1)[0]
            parts = cls.split(".")
            return ".".join(parts[:3]) if len(parts) > 3 else ".".join(parts[:2]) if len(parts) > 2 else cls
    return "(vanilla/forge/bukkit)"


def cmd_resumo(rep, args):
    m = rep.meta
    ps, ss = m["platformStatistics"], m["systemStatistics"]
    dur = (m["endTime"] - m["startTime"]) / 1000.0
    print(f'duracao: {dur:.0f} s | intervalo de amostra: {m["interval"]/1000:.0f} ms | ticks no periodo: {m["numberOfTicks"]}')
    print(f'TPS medio no periodo: {m["numberOfTicks"]/dur:.2f}')
    print(f'jogadores: {ps.get("playerCount")} | uptime: {ps.get("uptime",0)/3600000:.1f} h')
    print(f'TPS 1m/5m/15m: {ps["tps"]["last1m"]:.2f} / {ps["tps"]["last5m"]:.2f} / {ps["tps"]["last15m"]:.2f}')
    for k in ("last1m", "last5m"):
        x = ps["mspt"].get(k)
        if x:
            print(f'MSPT {k}: media {x["mean"]:.1f} | mediana {x["median"]:.1f} | p95 {x["percentile95"]:.1f} | max {x["max"]:.0f}')
    heap = ps["memory"]["heap"]
    print(f'heap: {heap["used"]/2**30:.1f} / {heap["max"]/2**30:.1f} GB')
    for g, v in ss["gc"].items():
        print(f'GC {g}: {v["total"]} coletas, media {v["avgTime"]:.1f} ms, uma a cada {v["avgFrequency"]/1000:.1f} s')
    print(f'CPU: {ss["cpu"]["threads"]} threads de hardware | uso do processo 1m: {ss["cpu"]["processUsage"]["last1m"]*100:.0f}%')
    print(f'java: {ss["java"]["vendorVersion"]} | SO: {ss["os"]["name"]}')
    w = ps.get("world") or {}
    if w:
        print(f'mundo: entidades {w.get("totalEntities")} | mundos {len(w.get("worlds", []))}')
    print("janelas (1 min cada): tps | mspt mediana | mspt max | entidades | chunks")
    for k, v in sorted(rep.d.get("timeWindowStatistics", {}).items()):
        print(f'  {v["tps"]:5.1f} | {v["msptMedian"]:6.1f} | {v["msptMax"]:7.0f} | {v["entities"]:5d} | {v["chunks"]:5d}')


def cmd_arvore(rep, args):
    th = rep.thread(args.thread)
    starts = th.find_roots(args.raiz) if args.raiz else th.roots
    print(f'thread "{args.thread}": total {th.total/1000:.1f} s. Colunas: % do total | ms | metodo')

    def walk(i, depth):
        p = th.pct(th.incl(i))
        if p < args.min or depth > args.prof:
            return
        s = th.pct(th.self_time(i))
        mark = f"  [proprio {s:.1f}%]" if s >= args.min else ""
        print(f'{p:6.2f}% {th.incl(i):9.0f}  {"  " * depth}{th.label(i)}{mark}')
        for c in sorted(th.kids(i), key=th.incl, reverse=True):
            walk(c, depth + 1)

    for r in sorted(starts, key=th.incl, reverse=True):
        walk(r, 0)


def cmd_donos(rep, args):
    th = rep.thread(args.thread)
    acc = collections.Counter()
    starts = th.find_roots(args.raiz) if args.raiz else th.roots
    base = sum(th.incl(r) for r in starts)

    def walk(i, path):
        path.append(th.label(i))
        st = th.self_time(i)
        if st > 0:
            acc[owner_of(path)] += st
        for c in th.kids(i):
            walk(c, path)
        path.pop()

    for r in starts:
        walk(r, [])
    print(f'thread "{args.thread}"' + (f', sob "{args.raiz}"' if args.raiz else "") +
          f': {base/1000:.1f} s ({th.pct(base):.1f}% da thread). Tempo proprio por dono:')
    for k, v in acc.most_common(args.top):
        print(f'{th.pct(v):6.2f}%  {v:9.0f} ms  {k}')


def _by_callee_class(rep, args, method_names, title):
    th = rep.thread(args.thread)
    acc = collections.Counter()
    seen_total = 0.0
    stack = [(r, False) for r in th.roots]
    while stack:
        i, inside = stack.pop()
        n = th.nodes[i]
        if not inside and n["methodName"] in method_names and not n["className"].startswith(("net.minecraft.world.", "net.minecraft.server.")):
            acc[n["className"]] += th.incl(i)
            seen_total += th.incl(i)
            continue  # nao desce: evita contar duas vezes chamadas aninhadas
        stack.extend((c, inside) for c in th.kids(i))
    print(f'{title}: {seen_total/1000:.1f} s ({th.pct(seen_total):.1f}% da thread), {len(acc)} classes')
    for k, v in acc.most_common(args.top):
        print(f'{th.pct(v):6.2f}%  {v:9.0f} ms  {k}')


def cmd_tiles(rep, args):
    _by_callee_class(rep, args, {"func_145845_h"}, "tick de TileEntity por classe (updateEntity, nome SRG)")


def cmd_ents(rep, args):
    _by_callee_class(rep, args, {"func_70071_h_"}, "tick de Entity por classe (onUpdate, nome SRG)")


def cmd_eventos(rep, args):
    th = rep.thread(args.thread)
    acc = collections.Counter()
    rx = re.compile(r"ASMEventHandler_\d+_(.+)")
    stack = list(th.roots)
    while stack:
        i = stack.pop()
        m = rx.search(th.nodes[i]["className"])
        if m and th.nodes[i]["methodName"] == "invoke":
            acc[m.group(1)] += th.incl(i)
            continue
        stack.extend(th.kids(i))
    tot = sum(acc.values())
    print(f'handlers de evento do Forge (nivel mais alto): {tot/1000:.1f} s ({th.pct(tot):.1f}% da thread)')
    for k, v in acc.most_common(args.top):
        print(f'{th.pct(v):6.2f}%  {v:9.0f} ms  {k}')


def cmd_busca(rep, args):
    th = rep.thread(args.thread)
    hits = th.find_roots(args.texto)
    tot = sum(th.incl(i) for i in hits)
    print(f'"{args.texto}": {len(hits)} ocorrencias de nivel mais alto, {tot/1000:.1f} s ({th.pct(tot):.2f}% da thread)')
    acc = collections.Counter()
    for i in hits:
        acc[th.label(i)] += th.incl(i)
    for k, v in acc.most_common(args.top):
        print(f'{th.pct(v):6.2f}%  {v:9.0f} ms  {k}')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("relatorio")
    ap.add_argument("comando", choices=["resumo", "arvore", "donos", "tiles", "ents", "eventos", "busca"])
    ap.add_argument("texto", nargs="?", default="")
    ap.add_argument("--thread", default="Server thread")
    ap.add_argument("--prof", type=int, default=14)
    ap.add_argument("--min", type=float, default=0.5)
    ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--raiz", default="")
    args = ap.parse_args()
    rep = Report(args.relatorio)
    {"resumo": cmd_resumo, "arvore": cmd_arvore, "donos": cmd_donos, "tiles": cmd_tiles,
     "ents": cmd_ents, "eventos": cmd_eventos, "busca": cmd_busca}[args.comando](rep, args)


if __name__ == "__main__":
    main()
