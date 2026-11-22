#!/usr/bin/env python3
"""Bot minimo 1.7.10 + FML (SO PARA O SERVIDOR DE TESTE local, offline-mode).

Uso: bot.py <porta> <nome> <arquivo-modlist> <arquivo-comandos> <log>
 - arquivo-modlist: linhas "modid versao" (gerado a partir da rejeicao do 1o contato)
   se nao existir ou estiver vazio, manda lista vazia e grava a mensagem de rejeicao no log.
 - arquivo-comandos: cada linha nova vira mensagem de chat (ex.: "/hoshie status").
Fica conectado mandando C03 (20/s) ate o arquivo-comandos receber a linha "SAIR".
"""
import os
import socket
import struct
import sys
import threading
import time

PORT = int(sys.argv[1])
NAME = sys.argv[2]
MODLIST = sys.argv[3]
CMDS = sys.argv[4]
LOG = open(sys.argv[5], "a", buffering=1)


def log(*a):
    LOG.write(time.strftime("%H:%M:%S ") + " ".join(str(x) for x in a) + "\n")


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

    def sbyte(self):
        v = struct.unpack_from(">b", self.d, self.i)[0]
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


sock = socket.create_connection(("127.0.0.1", PORT))
lock = threading.Lock()


def send(pid, payload=b""):
    body = varint(pid) + payload
    with lock:
        sock.sendall(varint(len(body)) + body)


def recv_exact(n):
    out = b""
    while len(out) < n:
        c = sock.recv(n - len(out))
        if not c:
            raise EOFError("conexao fechada")
        out += c
    return out


def recv_packet():
    n = 0
    s = 0
    while True:
        b = recv_exact(1)[0]
        n |= (b & 0x7F) << s
        s += 7
        if not b & 0x80:
            break
    data = recv_exact(n)
    b = Buf(data)
    return b.varint(), b


def plugin(channel, data):
    send(0x17, string(channel) + struct.pack(">h", len(data)) + data)


mods = []
if os.path.exists(MODLIST):
    for line in open(MODLIST):
        p = line.strip().split(" ", 1)
        if len(p) == 2:
            mods.append((p[0], p[1]))

# handshake + login
send(0x00, varint(5) + string("127.0.0.1\0FML\0") + struct.pack(">H", PORT) + varint(2))
send(0x00, string(NAME))
state = "login"
joined = False
acks = 0
pos = None
stop = False


def ticker():
    last_cmd_size = 0
    if os.path.exists(CMDS):
        last_cmd_size = os.path.getsize(CMDS)
    global stop
    while not stop:
        time.sleep(0.05)
        if not joined:
            continue
        try:
            send(0x03, b"\x01")  # C03 Player (onGround)
            if os.path.exists(CMDS) and os.path.getsize(CMDS) > last_cmd_size:
                with open(CMDS) as f:
                    f.seek(last_cmd_size)
                    novo = f.read()
                    last_cmd_size = f.tell()
                for line in novo.splitlines():
                    line = line.strip()
                    if line == "SAIR":
                        log("saindo a pedido")
                        stop = True
                        sock.close()
                        return
                    if line:
                        log("chat>", line)
                        send(0x01, string(line))
        except Exception as e:
            log("ticker erro", e)
            stop = True
            return


threading.Thread(target=ticker, daemon=True).start()

try:
    while not stop:
        pid, b = recv_packet()
        if state == "login":
            if pid == 0x00:
                log("LOGIN DISCONNECT:", b.string())
                break
            if pid == 0x02:
                log("login ok", b.string(), b.string())
                state = "play"
            continue
        # play
        if pid == 0x00:
            (kid,) = b.unpack(">i")
            send(0x00, struct.pack(">i", kid))
        elif pid == 0x01:
            log("join game")
            send(0x15, string("pt_BR") + bytes([2, 0, 1, 1]) + b"\x01")  # client settings
        elif pid == 0x02:
            log("chat<", b.string())
        elif pid == 0x08:
            x, y, z, yaw, pitch = b.unpack(">dddff")
            feet = y - 1.62
            pos = (x, feet, z)
            send(0x06, struct.pack(">ddddff?", x, feet, y, z, yaw, pitch, True))
            log("pos", round(x, 2), round(feet, 2), round(z, 2))
            joined = True
        elif pid == 0x3F:
            ch = b.string()
            (n,) = b.unpack(">h")
            data = b.rest()[:n]
            if ch == "FML|HS":
                disc = struct.unpack_from(">b", data, 0)[0]
                if disc == 0:
                    log("FML ServerHello")
                    plugin("REGISTER", b"FML|HS\0FML\0FML|MP\0FORGE")
                    plugin("FML|HS", bytes([1, 2]))
                    ml = bytes([2]) + varint(len(mods)) + b"".join(string(m) + string(v) for m, v in mods)
                    plugin("FML|HS", ml)
                    log("modlist enviada:", len(mods), "mods")
                elif disc == 2:
                    log("FML ModList do servidor recebida")
                    plugin("FML|HS", bytes([0xFF, 2]))
                elif disc == 3:
                    log("FML ModIdData recebida")
                elif disc == -1:
                    acks += 1
                    log("FML ack do servidor", data[1] if len(data) > 1 else None)
                    plugin("FML|HS", bytes([0xFF, 2 + acks]))
        elif pid == 0x40:
            log("DISCONNECT:", b.string())
            break
except Exception as e:
    log("fim:", repr(e))
stop = True
log("encerrado")
