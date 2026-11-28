#!/usr/bin/env python3
# Hoshiestar - prova dos testes dos adaptadores do jogo do paralelo (passo 4: OrdenadaJogo, GanchosAdiados,
# GanchosEventos), pedida pela revisao do passo 4 (I1): um defeito posto de proposito por vez, e os testes
# TesteAdiadosJogo (ligado e desligado) e TesteFaseJogo tem de reclamar de cada um.
#
# Uso: python3 mutacoes-jogo.py <workspace> <pasta libraries/ de um servidor> <pasta de saida> [filtro]
#   filtro (opcional): so os defeitos cujo nome comeca com ele (ex.: G21).
#   Precisa do build rapido ja feito no workspace (eclipse/cauldron/build/classes/java/main). Nunca escreve no workspace:
#   cada defeito e compilado numa sobreposicao das classes do build dentro da pasta de saida. Leva uns 10 s por defeito.
# Saida 0 = todos os defeitos foram pegos.
#
# Veio do roteiro do revisor do passo 4 (15 defeitos que escapavam de todos os testes isolados), mais os do que a
# revisao disse que faltava: os dois sons com entidade, evento assincrono e com ouvinte no callEvent, tick agendado em
# chunk ausente, retorno do crash ('parar' engolido, m1) e a reconferencia forte sem olhar o chunk.
import glob, os, shutil, subprocess, sys

W, LIBS, OUT = sys.argv[1], sys.argv[2], os.path.abspath(sys.argv[3])
FILTRO = sys.argv[4] if len(sys.argv) > 4 else ""
B = W + "/eclipse/cauldron/build/classes/java/main"
SRC = W + "/src/main/java/hoshiestar/paralelo/"
J8 = os.environ.get("JAVA8_HOME", "/usr/lib/jvm/java-8-openjdk-amd64") + "/bin/"
LIB = ":".join(sorted(glob.glob(LIBS + "/**/*.jar", recursive=True)))

M = [
 ("G01 despacho nao pergunta ao firewall", "OrdenadaJogo.java", "if (fw != null && !Boolean.TRUE.equals(fw.invoke(null, te, w))) {", "if (false && fw != null && !Boolean.TRUE.equals(fw.invoke(null, te, w))) {"),
 ("G02 firewall diz nao e o tile vai para o lote assim mesmo", "OrdenadaJogo.java", "            GanchosParalelo.PAINEL.barradosNoDespacho++;\n            return true;", "            GanchosParalelo.PAINEL.barradosNoDespacho++;"),
 ("G03 reconferencia sem isInvalid", "OrdenadaJogo.java", "            if (te.isInvalid()) {\n                return false;\n            }\n            World w = te.getWorldObj();", "            World w = te.getWorldObj();"),
 ("G04 reconferencia forte nao olha o mapa do chunk", "OrdenadaJogo.java", "            if (!serie) {\n                return true;\n            }", "            if (true) {\n                return true;\n            }"),
 ("G05 lotes sujos nao sao marcados (rodam como limpos)", "OrdenadaJogo.java", "                if (Registro.sujo(l.regiao)) {", "                if (false && Registro.sujo(l.regiao)) {"),
 ("G06 evento de bloco adiado e perdido", "GanchosAdiados.java", "                w.addBlockEvent(x, y, z, b, evento, dado);", "                // perdido"),
 ("G07 evento de bloco adiado com x e z trocados", "GanchosAdiados.java", "                w.addBlockEvent(x, y, z, b, evento, dado);", "                w.addBlockEvent(z, y, x, b, evento, dado);"),
 ("G08 som adiado aplicado duas vezes", "GanchosAdiados.java", "                w.playSoundEffect(x, y, z, som, vol, tom);", "                w.playSoundEffect(x, y, z, som, vol, tom);\n                w.playSoundEffect(x, y, z, som, vol, tom);"),
 ("G09 tick agendado adiado vira func_147446_b", "GanchosAdiados.java", "                w.scheduleBlockUpdateWithPriority(x, y, z, b, atraso, prioridade);", "                w.func_147446_b(x, y, z, b, atraso, prioridade);"),
 ("G10 adia tambem fora da thread do servidor", "GanchosAdiados.java", "return l != null && Thread.currentThread() == Guardas.servidor ? l : null;", "return l;"),
 ("G11 politica: parar ignorado (nunca devolve o crash)", "OrdenadaJogo.java", "            return crash(res, Registro.violacoesTick, invariante);", "            crash(res, Registro.violacoesTick, invariante);"),
 ("G12 juncao roda com a fase ainda MONTANDO", "OrdenadaJogo.java", "                Guardas.fase = Guardas.FECHADA; // juncao: em serie, como depois da barreira (o portao nao segura mais)\n", ""),
 ("G13 emergencia esquecida no erro nosso do despacho", "OrdenadaJogo.java", "                res = emergencia(lotes, \"erro no nosso codigo no despacho deste tick\");", "                res = new Juncao.Resultado();"),
 ("G14 callEvent sem ouvinte pulado tambem para evento assincrono", "GanchosEventos.java", "            return !e.isAsynchronous();", "            return true;"),
 ("G15 nao descarregar: adiado nao aplica nada", "GanchosAdiados.java", "                cps.loadChunk(x, z);", "                // nada"),
 ("G16 callEvent pulado tambem com ouvinte", "GanchosEventos.java", "        if (!Guardas.loteAberto || quem != Guardas.SERVIDOR || ouvinte) {", "        if (!Guardas.loteAberto || quem != Guardas.SERVIDOR) {"),
 ("G17 tick agendado B adiado vira scheduleBlockUpdateWithPriority", "GanchosAdiados.java", "                w.func_147446_b(x, y, z, b, atraso, prioridade);", "                w.scheduleBlockUpdateWithPriority(x, y, z, b, atraso, prioridade);"),
 ("G18 som na entidade adiado e perdido", "GanchosAdiados.java", "                w.playSoundAtEntity(e, som, vol, tom);", "                // perdido"),
 ("G19 som perto-menos adiado com volume e tom trocados", "GanchosAdiados.java", "                w.playSoundToNearExcept(p, som, vol, tom);", "                w.playSoundToNearExcept(p, som, tom, vol);"),
 ("G20 crash-report que nao monta derruba a politica (m1)", "OrdenadaJogo.java", "            e.addSuppressed(t);\n            return e;", "            throw new IllegalStateException(t);"),
 ("G21 reconferencia forte nao confere se o chunk existe", "OrdenadaJogo.java", "            if (cps == null || !cps.chunkExists(cx, cz)) {", "            if (cps == null) {"),
 ("G22 som adiado nao aplicado na juncao (lote limpo)", "GanchosAdiados.java", "                w.playRecord(disco, x, y, z);", "                // perdido"),
]


def rodar(cp, classe, arg, cwd):
    try:
        t = subprocess.run([J8 + "java", "-cp", cp, "hoshiestar.paralelo." + classe] + arg, capture_output=True, text=True, timeout=90, cwd=cwd)
        falhas = [l.strip() for l in (t.stdout + t.stderr).splitlines() if l.strip().startswith("FALHA")]
        return t.returncode != 0, falhas
    except subprocess.TimeoutExpired:
        return True, ["travou (90 s)"]


os.makedirs(OUT, exist_ok=True)
tc = OUT + "/testes"
shutil.rmtree(tc, ignore_errors=True)
os.makedirs(tc)
fontes = [W + "/src/test/java/hoshiestar/teste/Verifica.java"] + sorted(glob.glob(W + "/src/test/paralelo/jogo/**/*.java", recursive=True))
c = subprocess.run([J8 + "javac", "-nowarn", "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-cp", B + ":" + LIB, "-d", tc] + fontes, capture_output=True, text=True)
if c.returncode != 0:
    print(c.stderr)
    sys.exit(2)
for d, lig in (("lig", "true"), ("des", "false")):
    os.makedirs(OUT + "/" + d, exist_ok=True)
    open(OUT + "/" + d + "/hoshiestar.yml", "w").write("paralelo:\n  ligado: " + lig + "\n")
escaparam = 0
M = [m for m in M if m[0].startswith(FILTRO)]
for nome, arq, velho, novo in M:
    src = open(SRC + arq).read()
    if src.count(velho) != 1:
        print(nome, "=> NAO APLICOU (%d ocorrencias): o codigo mudou, atualize o defeito" % src.count(velho))
        escaparam += 1
        continue
    ov = OUT + "/ovm"
    shutil.rmtree(ov, ignore_errors=True)
    os.makedirs(ov)
    for d in os.listdir(B):
        if d != "hoshiestar":
            os.symlink(B + "/" + d, ov + "/" + d)
    shutil.copytree(B + "/hoshiestar", ov + "/hoshiestar")
    ms = OUT + "/msrc"
    shutil.rmtree(ms, ignore_errors=True)
    os.makedirs(ms)
    open(ms + "/" + arq, "w").write(src.replace(velho, novo))
    c = subprocess.run([J8 + "javac", "-nowarn", "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-cp", B + ":" + LIB, "-d", ov, ms + "/" + arq], capture_output=True, text=True)
    if c.returncode != 0:
        print(nome, "=> NAO COMPILOU:", c.stderr[-300:])
        escaparam += 1
        continue
    cp = tc + ":" + ov + ":" + LIB
    r = [("Adiados", rodar(cp, "TesteAdiadosJogo", ["true"], OUT + "/lig")),
         ("Adiados desligado", rodar(cp, "TesteAdiadosJogo", ["false"], OUT + "/des")),
         ("Fase", rodar(cp, "TesteFaseJogo", [], OUT + "/lig"))]
    pegos = [(n, f) for n, (p, f) in r if p]
    if pegos:
        print(nome, "=> PEGO por", ", ".join(n for n, _ in pegos), "|", (pegos[0][1][0][:110] if pegos[0][1] else "saida != 0"))
    else:
        escaparam += 1
        print(nome, "=> ESCAPOU")
print("defeitos: %d, escaparam: %d" % (len(M), escaparam))
sys.exit(0 if escaparam == 0 else 1)
