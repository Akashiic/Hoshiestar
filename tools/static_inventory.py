#!/usr/bin/env python3
"""Hoshiestar - inventario estatico do modpack (Etapa 0, parte que nao precisa do jogo).

Le os .jar de mods/ sem executar nada e conta sinais que importam para dividir o
mundo em regioes: quem tica (TileEntity.updateEntity), quem usa eventos de tick
globais (ServerTickEvent / WorldTickEvent), quem guarda dados por mundo
(WorldSavedData), quem ja cria threads e quem e coremod/mixin.

Os numeros sao INDICIOS (busca de texto no bytecode), nao prova. A prova vem do
modo observador da Etapa 2.

Uso: python3 -I tools/static_inventory.py <pasta-mods> <saida.csv>
"""
import csv, io, json, re, sys, zipfile
from pathlib import Path

SIGNALS = {
    "te_tick": b"func_145845_h",                 # TileEntity.updateEntity (SRG)
    "server_tick_evt": b"TickEvent$ServerTickEvent",
    "world_tick_evt": b"TickEvent$WorldTickEvent",
    "player_tick_evt": b"TickEvent$PlayerTickEvent",
    "world_saved_data": b"WorldSavedData",
    "chunk_evt": b"ChunkEvent$",
    "threads": b"java/lang/Thread",
    "juc": b"java/util/concurrent/",
    "fakeplayer": b"FakePlayer",
    "forge_chunkmgr": b"ForgeChunkManager",
    "bukkit_api": b"org/bukkit/",
}

def manifest(zf):
    try:
        raw = zf.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    except KeyError:
        return {}
    raw = raw.replace("\r\n", "\n").replace("\n ", "")
    out = {}
    for line in raw.split("\n"):
        if ":" in line:
            k, v = line.split(":", 1)
            out[k.strip()] = v.strip()
    return out

def mcmod(zf):
    try:
        txt = zf.read("mcmod.info").decode("utf-8", "replace")
    except KeyError:
        return "", ""
    ids = re.findall(r'"modid"\s*:\s*"([^"]*)"', txt)
    vers = re.findall(r'"version"\s*:\s*"([^"]*)"', txt)
    return ";".join(ids[:4]), (vers[0] if vers else "")

def scan(jar: Path):
    row = {"jar": jar.name, "size_kb": jar.stat().st_size // 1024}
    counts = {k: 0 for k in SIGNALS}
    classes = 0
    try:
        with zipfile.ZipFile(jar) as zf:
            mf = manifest(zf)
            row["modid"], row["version"] = mcmod(zf)
            row["coremod"] = mf.get("FMLCorePlugin", "")
            row["tweaker"] = mf.get("TweakClass", "")
            row["mixin_cfg"] = mf.get("MixinConfigs", "")
            row["agent"] = mf.get("Premain-Class", "") or mf.get("Launcher-Agent-Class", "")
            names = zf.namelist()
            row["mixin_json"] = sum(1 for n in names if re.search(r"mixins?[^/]*\.json$", n))
            for n in names:
                if not n.endswith(".class"):
                    continue
                classes += 1
                data = zf.read(n)
                for key, needle in SIGNALS.items():
                    if needle in data:
                        counts[key] += 1
    except zipfile.BadZipFile:
        row["modid"] = "ERRO: zip invalido"
    row["classes"] = classes
    row.update(counts)
    return row

def main():
    mods_dir, out = Path(sys.argv[1]), Path(sys.argv[2])
    jars = sorted(p for p in mods_dir.rglob("*.jar"))
    rows = [scan(j) for j in jars]
    cols = ["jar", "modid", "version", "size_kb", "classes", "coremod", "tweaker",
            "mixin_cfg", "mixin_json", "agent"] + list(SIGNALS)
    with out.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow({c: r.get(c, "") for c in cols})
    print(f"{len(rows)} jars -> {out}")

if __name__ == "__main__":
    main()
