#!/usr/bin/env python3
"""Bots que ANDAM, para o servidor de TESTE local (1.7.10 + FML, offline-mode).

SO conecta em 127.0.0.1. Varios bots no mesmo processo, cada um com seu nome.
Serve para o servidor carregar chunks (quem anda leva o raio de visao junto) e para
manter chunks carregados (bot parado).

Exemplos:
  # 3 bots andando em linha (vai e volta) por 120 s, a 8 blocos/s
  bot_anda.py --porta 25565 --prefixo Andarilho --n 3 --rota linha --tamanho 600 --tempo 120 --log bots.log
  # 1 bot parado em (1000, 1000) depois de ser teleportado pelo console
  bot_anda.py --nomes CargaA --rota parado --tempo 0 --log bots.log
  # bots em posicoes diferentes: --centros 0,0;5000,0;0,5000  (um centro por bot, na ordem)

Rotas: parado | linha (vai e volta no eixo X) | circuito (quadrado fechado) | anel (idem, mas gira
lentamente em torno do centro). O bot parte da posicao que o servidor lhe deu (ou do centro, se
--centros for dado: ai ele manda a propria posicao e o servidor aceita; use /tp no console para
mover o bot de forma garantida).
--tempo 0 = ate receber a linha SAIR no arquivo --cmds ou ate o processo morrer.
Cada linha nova do --cmds que nao seja SAIR vira mensagem de chat do bot (ex.: "/hoshie status").
A lista de mods e descoberta sozinha pelo "ping" do servidor (--modlist so como reserva).
"""
import argparse
import json
import math
import os
import socket
import struct
import sys
import threading
import time

LOCK_LOG = threading.Lock()


def varint(n):
    out = b""
    n &= 0xFFFFFFFF
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def string(s):
    b = s.encode("utf-8")
    return varint(len(b)) + b


class Buf:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def byte(self):
        v = self.d[self.i]
        self.i += 1
        return v

    def varint(self):
        n = 0
        s = 0
        while True:
            b = self.byte()
            n |= (b & 0x7F) << s
            s += 7
            if not b & 0x80:
                break
        if n & 0x80000000:
            n -= 1 << 32
        return n

    def string(self):
        n = self.varint()
        v = self.d[self.i:self.i + n].decode("utf-8", "replace")
        self.i += n
        return v

    def unpack(self, fmt):
        v = struct.unpack_from(fmt, self.d, self.i)
        self.i += struct.calcsize(fmt)
        return v

    def rest(self):
        return self.d[self.i:]


def conectar(porta):
    # trava de seguranca: so a propria maquina
    return socket.create_connection(("127.0.0.1", porta), timeout=60)


def ler_modlist_arquivo(caminho):
    mods = []
    if caminho and os.path.exists(caminho):
        for line in open(caminho):
            p = line.strip().split(" ", 1)
            if len(p) == 2:
                mods.append((p[0], p[1]))
    return mods


def descobrir_modlist(porta):
    """Pergunta ao servidor (ping de lista de servidores) quais mods ele tem."""
    s = conectar(porta)
    try:
        def send(pid, payload=b""):
            body = varint(pid) + payload
            s.sendall(varint(len(body)) + body)

        send(0x00, varint(5) + string("127.0.0.1") + struct.pack(">H", porta) + varint(1))
        send(0x00)
        n = 0
        sh = 0
        while True:
            b = s.recv(1)[0]
            n |= (b & 0x7F) << sh
            sh += 7
            if not b & 0x80:
                break
        data = b""
        while len(data) < n:
            c = s.recv(n - len(data))
            if not c:
                break
            data += c
        b = Buf(data)
        b.varint()
        j = json.loads(b.string())
        ml = j.get("modinfo", {}).get("modList", [])
        return [(m["modid"], m["version"]) for m in ml]
    finally:
        s.close()


def log(nome, logf, *a):
    with LOCK_LOG:
        logf.write(time.strftime("%H:%M:%S ") + "[" + nome + "] " + " ".join(str(x) for x in a) + "\n")
        logf.flush()


class Bot(threading.Thread):
    def __init__(self, nome, args, mods, centro, indice, logf, parar):
        super().__init__(daemon=True, name="bot-" + nome)
        self.nome, self.args, self.mods, self.centro, self.indice = nome, args, mods, centro, indice
        self.logf, self.parar_evt = logf, parar
        self.sock = None
        self.lock = threading.Lock()
        self.joined = False
        self.pos = None  # (x, pes_y, z)
        self.chunks_recebidos = 0
        self.pacotes_pos_enviados = 0
        self.fim = False
        self.erro = None
        self.t_ini = None
        self.t_conn = time.time()

    def L(self, *a):
        log(self.nome, self.logf, *a)

    def send(self, pid, payload=b""):
        body = varint(pid) + payload
        with self.lock:
            self.sock.sendall(varint(len(body)) + body)

    def recv_exact(self, n):
        out = b""
        while len(out) < n:
            c = self.sock.recv(n - len(out))
            if not c:
                raise EOFError("conexao fechada")
            out += c
        return out

    def recv_packet(self):
        n = 0
        s = 0
        while True:
            b = self.recv_exact(1)[0]
            n |= (b & 0x7F) << s
            s += 7
            if not b & 0x80:
                break
        b = Buf(self.recv_exact(n))
        return b.varint(), b

    def plugin(self, channel, data):
        self.send(0x17, string(channel) + struct.pack(">h", len(data)) + data)

    def rota_passo(self, t):
        """Posicao-alvo (x, z) no instante t (segundos desde o inicio), ou None se parado."""
        a = self.args
        cx, cz = self.centro
        v = a.velocidade
        L = a.tamanho
        if a.rota == "parado":
            return None
        d = v * t
        if a.rota == "linha":
            # triangular: 0 -> L -> 0 ... no eixo X, a partir do centro; cada bot numa faixa Z propria
            ciclo = 2 * L
            m = d % ciclo
            x = m if m <= L else ciclo - m
            return cx + x, cz + self.indice * 16.0
        if a.rota in ("circuito", "anel"):
            per = 4 * L
            m = d % per
            lado = int(m // L)
            r = m - lado * L
            pts = [(0, 0), (L, 0), (L, L), (0, L)]
            (x0, z0) = pts[lado]
            (x1, z1) = pts[(lado + 1) % 4]
            x = x0 + (x1 - x0) * r / L
            z = z0 + (z1 - z0) * r / L
            if a.rota == "anel":
                ang = 0.002 * t
                x, z = x * math.cos(ang) - z * math.sin(ang), x * math.sin(ang) + z * math.cos(ang)
            return cx + x, cz + z + self.indice * 16.0
        return None

    def ticker(self):
        a = self.args
        last_cmd = os.path.getsize(a.cmds) if a.cmds and os.path.exists(a.cmds) else 0
        prox = time.time()
        ultimo_log = 0
        yaw = 0.0
        while not self.fim and not self.parar_evt.is_set():
            prox += 0.05
            esp = prox - time.time()
            if esp > 0:
                time.sleep(esp)
            if not self.joined or self.pos is None:
                if time.time() - self.t_conn > self.args.espera_entrada:
                    self.L("NAO ENTROU em %ds (login preso no servidor?); encerrando" % self.args.espera_entrada)
                    self.erro = "nao entrou"
                    break
                continue
            try:
                t = time.time() - self.t_ini
                if a.tempo and t >= a.tempo:
                    self.L("tempo esgotado")
                    break
                alvo = self.rota_passo(t)
                if alvo is None or self.teleportado_agora:
                    self.send(0x06, struct.pack(">ddddff?", self.pos[0], self.pos[1], self.pos[1] + 1.62,
                                                self.pos[2], yaw, 0.0, True))
                else:
                    x, y, z = self.pos
                    tx, tz = alvo
                    dx, dz = tx - x, tz - z
                    dist = math.hypot(dx, dz)
                    passo = a.velocidade / 20.0
                    if dist > 40:  # longe demais do alvo (ex.: apos /tp): so anda, nao salta
                        dx, dz, dist = dx / dist * passo, dz / dist * passo, passo
                    elif dist > passo:
                        dx, dz = dx / dist * passo, dz / dist * passo
                    x, z = x + dx, z + dz
                    if dist > 1e-6:
                        yaw = math.degrees(math.atan2(-dx, dz))
                    self.pos = (x, y, z)
                    self.send(0x06, struct.pack(">ddddff?", x, y, y + 1.62, z, yaw, 0.0, True))
                self.pacotes_pos_enviados += 1
                if time.time() - ultimo_log > 15:
                    ultimo_log = time.time()
                    self.L("pos", round(self.pos[0], 1), round(self.pos[1], 1), round(self.pos[2], 1),
                           "chunks_recebidos", self.chunks_recebidos)
                if a.cmds and os.path.exists(a.cmds) and os.path.getsize(a.cmds) > last_cmd:
                    with open(a.cmds) as f:
                        f.seek(last_cmd)
                        novo = f.read()
                        last_cmd = f.tell()
                    for line in novo.splitlines():
                        line = line.strip()
                        if line == "SAIR":
                            self.L("saindo a pedido")
                            self.fim = True
                            return
                        if line:
                            self.L("chat>", line)
                            self.send(0x01, string(line))
            except Exception as e:  # noqa: BLE001
                self.erro = repr(e)
                self.L("ticker erro", e)
                break
        self.fim = True
        try:
            self.sock.close()
        except Exception:  # noqa: BLE001
            pass

    teleportado_agora = False

    def run(self):
        a = self.args
        try:
            self.sock = conectar(a.porta)
            self.sock.settimeout(120)
            self.send(0x00, varint(5) + string("127.0.0.1\0FML\0") + struct.pack(">H", a.porta) + varint(2))
            self.send(0x00, string(self.nome))
            state = "login"
            acks = 0
            threading.Thread(target=self.ticker, daemon=True).start()
            while not self.fim and not self.parar_evt.is_set():
                pid, b = self.recv_packet()
                if state == "login":
                    if pid == 0x00:
                        self.L("LOGIN DISCONNECT:", b.string()[:300])
                        break
                    if pid == 0x02:
                        self.L("login ok")
                        state = "play"
                    continue
                if pid == 0x00:
                    (kid,) = b.unpack(">i")
                    self.send(0x00, struct.pack(">i", kid))
                elif pid == 0x01:
                    self.L("join game")
                    self.send(0x15, string("pt_BR") + bytes([a.visao, 0, 1, 1]) + b"\x01")
                elif pid == 0x08:
                    x, y, z, yaw, pitch = b.unpack(">dddff")
                    feet = y - 1.62
                    self.pos = (x, feet, z)
                    self.send(0x06, struct.pack(">ddddff?", x, feet, y, z, yaw, pitch, True))
                    if not self.joined:
                        self.t_ini = time.time()
                        if self.centro is None:
                            self.centro = (x, z)
                        self.L("pos inicial", round(x, 1), round(feet, 1), round(z, 1))
                    else:
                        self.L("teleportado para", round(x, 1), round(feet, 1), round(z, 1))
                        # a rota recomeca do ponto novo: o centro passa a ser onde o servidor nos pos
                        if a.rota != "parado" and a.seguir_tp:
                            self.centro = (x, z)
                            self.t_ini = time.time()
                    self.joined = True
                elif pid == 0x21:
                    self.chunks_recebidos += 1
                elif pid == 0x26:
                    (n,) = b.unpack(">h")
                    self.chunks_recebidos += n
                elif pid == 0x3F:
                    ch = b.string()
                    (n,) = b.unpack(">h")
                    data = b.rest()[:n]
                    if ch == "FML|HS":
                        disc = struct.unpack_from(">b", data, 0)[0]
                        if disc == 0:
                            self.plugin("REGISTER", b"FML|HS\0FML\0FML|MP\0FORGE")
                            self.plugin("FML|HS", bytes([1, 2]))
                            ml = bytes([2]) + varint(len(self.mods)) + b"".join(
                                string(m) + string(v) for m, v in self.mods)
                            self.plugin("FML|HS", ml)
                        elif disc == 2:
                            self.plugin("FML|HS", bytes([0xFF, 2]))
                        elif disc == -1:
                            acks += 1
                            self.plugin("FML|HS", bytes([0xFF, 2 + acks]))
                elif pid == 0x40:
                    self.L("DISCONNECT:", b.string()[:300])
                    break
        except Exception as e:  # noqa: BLE001
            if not self.fim:
                self.erro = repr(e)
                self.L("fim:", repr(e))
        self.fim = True
        self.L("encerrado; chunks_recebidos", self.chunks_recebidos)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--porta", type=int, default=25565)
    ap.add_argument("--prefixo", default="Andarilho")
    ap.add_argument("--n", type=int, default=1)
    ap.add_argument("--nomes", help="nomes separados por virgula (substitui --prefixo/--n)")
    ap.add_argument("--rota", choices=["parado", "linha", "circuito", "anel"], default="linha")
    ap.add_argument("--tamanho", type=float, default=400.0, help="comprimento da linha ou lado do circuito, em blocos")
    ap.add_argument("--velocidade", type=float, default=8.0, help="blocos por segundo (maximo aceito pelo servidor: ~190)")
    ap.add_argument("--centros", help="x,z;x,z;... um por bot (centro da rota)")
    ap.add_argument("--tempo", type=float, default=60.0, help="segundos; 0 = ate SAIR")
    ap.add_argument("--visao", type=int, default=8, help="raio de visao pedido ao servidor (chunks)")
    ap.add_argument("--modlist", help="arquivo 'modid versao' (reserva se o ping falhar)")
    ap.add_argument("--cmds", help="arquivo de comandos; linha SAIR encerra")
    ap.add_argument("--seguir-tp", action="store_true", help="apos /tp, a rota recomeca do ponto novo")
    ap.add_argument("--espaco", type=float, default=1.0, help="segundos entre a entrada de um bot e a do proximo")
    ap.add_argument("--espera-entrada", type=int, default=90, help="segundos ate desistir se o bot nao entrar no jogo")
    ap.add_argument("--log", default="bots.log")
    args = ap.parse_args()

    nomes = args.nomes.split(",") if args.nomes else ["%s%d" % (args.prefixo, i + 1) for i in range(args.n)]
    centros = []
    if args.centros:
        for c in args.centros.split(";"):
            x, z = c.split(",")
            centros.append((float(x), float(z)))
    try:
        mods = descobrir_modlist(args.porta)
    except Exception as e:  # noqa: BLE001
        print("ping falhou (%r); usando --modlist" % (e,), file=sys.stderr)
        mods = []
    if not mods:
        mods = ler_modlist_arquivo(args.modlist)
    logf = open(args.log, "a", buffering=1)
    parar = threading.Event()
    bots = []
    for i, nome in enumerate(nomes):
        centro = centros[i] if i < len(centros) else None
        b = Bot(nome, args, mods, centro, i, logf, parar)
        bots.append(b)
        b.start()
        time.sleep(args.espaco)
    print("bots:", ",".join(nomes), "mods:", len(mods), flush=True)
    try:
        while any(b.is_alive() for b in bots):
            time.sleep(0.5)
    except KeyboardInterrupt:
        parar.set()
    log("main", logf, "todos encerrados; erros:", [b.erro for b in bots if b.erro])


if __name__ == "__main__":
    main()
