#!/usr/bin/env python3
# Hoshiestar - mutacoes do nucleo de concorrencia do paralelo (Degrau B, passo 5): a prova dos testes.
#
# Uso:
#   HOSHIE_LIBS=<pasta libraries/ de um servidor> python3 <workspace>/src/test/paralelo/mutacoes-nucleo.py <workspace> [--sonda] [nome...]
#
# Para cada defeito da tabela M (ou so para os nomes dados), copia as fontes do nucleo para uma pasta de trabalho, aplica
# UM defeito, compila com os testes e roda TesteNucleoCasos e TesteNucleoCarga na dose rapida, no JDK 8; se nenhum dos dois
# pegar, roda tambem a sonda de instante sorteado (TesteNucleoSonda). Cada defeito tem de ser PEGO: algum teste falha, ou
# trava e o vigia do teste derruba a JVM. "SOBREVIVEU" quer dizer que falta teste.
# Com --sonda roda SO a TesteNucleoSonda contra cada defeito: mede o dente da sonda sozinha. Ela nao tem de pegar todos
# (nao confere ordem de atendimento nem o portao simples, e depende do instante sorteado); o que ela nao pega sai como
# "NAO PEGA (so a sonda)".
# Nada no workspace e alterado. Leva de 20 s a alguns minutos por defeito (os que travam esperam o vigia).
# Saida 0 = todos pegos. Nao roda dentro do servidor/test.sh (e lento); rode quando mexer no nucleo ou nos testes dele.
#
# Variaveis: JAVA8_HOME (padrao /usr/lib/jvm/java-8-openjdk-amd64), HOSHIE_LIBS, HOSHIE_SAIDA (pasta de trabalho; padrao:
# uma pasta temporaria nova).
import glob
import os
import shutil
import subprocess
import sys
import tempfile

NUCLEO = ['Trabalho', 'Operacao', 'Ambiente', 'Andamento', 'Fase', 'Pedido', 'Preso', 'Despejo', 'ThreadRegiao', 'Trabalhador', 'Barreira']
TESTES = ['ApoioNucleo', 'TesteNucleoCasos', 'TesteNucleoCarga', 'TesteNucleoSonda']
SONDA = 'TesteNucleoSonda'

# nome -> (arquivo, trecho original, trecho mutado, o que o defeito e)
M = {
 'atende_sem_parar': ('Barreira', "if (!pendentes.isEmpty() && !algumEm(Trabalhador.RODANDO, -1)) {", "if (!pendentes.isEmpty()) {",
     'a thread do servidor roda a operacao sem esperar os trabalhadores pararem'),
 'pedinte_volta_cedo': ('Barreira', """            while ((sinais & SINAL_PAUSA) != 0) {
                condRetomar.awaitUninterruptibly();
            }
            w.estado = Trabalhador.RODANDO;
        } finally {
            trava.unlock();
            w.nsParado""", """            w.estado = Trabalhador.RODANDO;
        } finally {
            trava.unlock();
            w.nsParado""", 'quem pediu volta a rodar com outro pedido ainda na fila'),
 'ordem_de_chegada': ('Barreira', "if (q.chave < p.chave || (q.chave == p.chave && q.seq < p.seq)) {", "if (q.seq < p.seq) {",
     'pedidos atendidos por ordem de chegada, nao por chave'),
 'sem_semnovos_no_pedido': ('Barreira', "        f.semNovos = true;\n        f.pedidos.add(p);", "        f.pedidos.add(p);",
     'depois da primeira violacao os trabalhadores continuam pegando lotes'),
 'sem_sobrou_no_item0': ('Trabalhador', "            if (i == 0 && f.semNovos) {", "            if (false) {",
     'lote ja pego comeca depois da violacao'),
 'nao_suja': ('Barreira', "                    alvo.sujo = true;", "                    alvo.sujo = false;", 'o pedido nao suja o lote alvo'),
 'sujo_ignorado': ('Barreira', "        return a.sujo ? PARA_SUJO : SEGUE;", "        return SEGUE;", 'o dono do lote sujo continua ticando'),
 'barreira_sai_cedo': ('Barreira', "if (f.fechados.get() >= n && pendentes.isEmpty()) {", "if (f.fechados.get() >= n - 1 && pendentes.isEmpty()) {",
     'a barreira passa faltando um lote'),
 'conta_antes_de_fechar': ('Fase', """        if (a.fechar(fim, feitos, erro, ns, thread)) {
            fechados.incrementAndGet();
            return true;
        }
        return false;""", """        fechados.incrementAndGet();
        return a.fechar(fim, feitos, erro, ns, thread);""", 'a contagem sobe antes da ultima escrita do lote (e ate sem fechar)'),
 'pega_duas_vezes': ('Trabalhador', "                int j = f.proximo.getAndIncrement();", "                int j = f.proximo.get(); Thread.yield(); f.proximo.set(j + 1);",
     'a pega do lote deixa de ser atomica'),
 'sem_excecao_do_monitor': ('Barreira', """            if (p.monitor != null) {
            monitorCedido = p.monitor;
            condPortao.signalAll();
        }""".replace("            if (p.monitor", "        if (p.monitor"), "", 'thread estranha com o monitor nao e atendida antes: deadlock'),
 'monitor_sem_sincronizar': ('Barreira', """                synchronized (m) {
                    monitorCedido = null;
                    p.resultado = p.op.rodar();
                }""", """                p.resultado = p.op.rodar();
                monitorCedido = null;""", 'a operacao com monitor declarado roda sem a thread do servidor tomar o monitor antes'),
 'portao_aberto': ('Barreira', """        if (estado != Fase.Estado.RODANDO) {
            return PORTAO_DIRETO;
        }
        Thread eu = Thread.currentThread();
        if (eu == servidor || meu(eu) != null) {""", """        if (true) {
            return PORTAO_DIRETO;
        }
        Thread eu = Thread.currentThread();
        if (eu == servidor || meu(eu) != null) {""", 'o portao nao segura ninguem'),
 'sem_confirmacao': ('Barreira', "            interrompida = esperarConfirmacoes();", "            interrompida = false; confirmacoesPendentes = 0;",
     'a fase nova nao espera quem foi liberado no portao'),
 'portao_passa_qualquer_monitor': ('Barreira', "                if (meuMonitor != null && monitorCedido == meuMonitor) {\n                    cPortaoMonitor.incrementAndGet();\n                    return PORTAO_MONITOR;\n                }\n                long resta",
     "                if (meuMonitor != null && monitorCedido != null) {\n                    cPortaoMonitor.incrementAndGet();\n                    return PORTAO_MONITOR;\n                }\n                long resta",
     'thread estranha com OUTRO monitor passa pela excecao'),
 'aborto_sem_parar': ('Barreira', """                if (abortada) {
                    w.estado = Trabalhador.PARADO;
                    condServidor.signal();
                    condParados.signalAll();
                    return PARA_ABORTO;
                }""", "", 'depois do aborto os trabalhadores continuam ticando'),
 'aborto_sem_prazo': ('Barreira', """                    long resta = limite - System.nanoTime();
                    if (resta <= 0L) {
                        break;
                    }
                    try {
                        condParados.awaitNanos(resta);""", """                    long resta = 3600000000000L;
                    try {
                        condParados.awaitNanos(resta);""", 'abortar espera sem prazo'),
 'aborto_nao_abre_portao': ('Barreira', "            portaoAberto = true;\n            condPortao.signalAll();", "", 'o portao nao acaba junto com a fase abortada'),
 'aborto_abandona_pedido': ('Barreira', "            if (!pendentes.isEmpty() && !algumEm(Trabalhador.RODANDO, -1)) {", "            if (!pendentes.isEmpty() && !algumEm(Trabalhador.RODANDO, -1) && !abortada) {",
     'pedido pendente nao e atendido depois do aborto'),
 'morte_nao_vista': ('Barreira', """                if (a != null) {
                    f.fechar(a, Andamento.Fim.MORREU, a.feitos, w.causaDaMorte, 0L, w.nome);
                }""", "", 'o lote aberto da thread morta nunca e fechado'),
 'erro_engolido': ('Trabalhador', "                erro = t;\n                fim = Andamento.Fim.ERRO;\n                f.semNovos = true;\n                break;", "                erro = t;", 'o erro de um item nao para o lote'),
 'erro_nao_para_os_outros': ('Trabalhador', "                fim = Andamento.Fim.ERRO;\n                f.semNovos = true;", "                fim = Andamento.Fim.ERRO;", 'depois do erro os outros continuam pegando lotes'),
 'sem_despejo': ('Barreira', "            if (resta <= 0L) {\n                List<Foto> fotos = fotografar(false);", "            if (false) {\n                List<Foto> fotos = fotografar(false);", 'a espera da barreira nao despeja pilhas'),
 'interrompe_atrasado': ('Barreira', "                List<Foto> fotos = fotografar(false);", "                List<Foto> fotos = fotografar(false); for (Foto ft : fotos) { ft.w.thread.interrupt(); }", 'a barreira interrompe os atrasados'),
 'acorda_na_fase_vazia': ('Barreira', "                cFasesVazias.incrementAndGet();\n                estado = Fase.Estado.JUNCAO;\n                return f;", "                cFasesVazias.incrementAndGet();\n                fase = f; condTrabalho.signalAll();\n                estado = Fase.Estado.JUNCAO;\n                return f;", 'fase sem lote acorda os trabalhadores'),
 'ordem_de_custo': ('Barreira', "            Arrays.sort(as, Andamento.MAIS_CARO_PRIMEIRO);", "            Arrays.sort(as, Andamento.POR_CHAVE);", 'lotes nao sao pegos do mais caro para o mais barato'),
 'aninhado_enfileira': ('Barreira', "        Andamento a = w == null ? null : w.daVez;\n        if (a == null) {\n            if (eu == servidor && emServico != null) {", "        Andamento a = w == null ? null : w.daVez;\n        if (a == null && !(eu == servidor && emServico != null)) {\n            if (eu == servidor && emServico != null) {", 'o pedido aninhado nao passa direto (NullPointerException ou espera por si mesmo)'),
 'operacao_no_trabalhador': ('Barreira', "        Fase f = w.vista;\n        int item = a.feitos;", "        if (true) { cPedidos.incrementAndGet(); return direto(op); }\n        Fase f = w.vista;\n        int item = a.feitos;", 'a operacao proibida roda na propria thread de regiao'),
 'encerrar_larga_na_interrupcao': ('Barreira', "                while (w.thread.isAlive()) {\n                    long resta = limite - System.nanoTime();",
     "                for (int vez = 0; vez < 1 && w.thread.isAlive(); vez++) {\n                    long resta = limite - System.nanoTime();",
     'encerrar larga o join da thread de regiao quando quem chama esta interrompido (revisao do passo 5, I1)'),
 'monitor_cedido_nao_zera_dentro': ('Barreira', "                synchronized (m) {\n                    monitorCedido = null;\n", "                synchronized (m) {\n",
     'a excecao do monitor continua armada depois de a thread do servidor entrar no monitor (revisao do passo 5, I2a)'),
 'retoma_no_aborto_durante_op': ('Barreira', "                    w.nsParado += System.nanoTime() - t0;\n                    if (!abortada) {\n                        w.estado = Trabalhador.RODANDO;\n                    }\n                }\n                if (abortada) {",
     "                    w.nsParado += System.nanoTime() - t0;\n                    w.estado = Trabalhador.RODANDO;\n                    if (abortada) { return a.sujo ? PARA_SUJO : SEGUE; }\n                }\n                if (abortada) {",
     'abortar durante uma operacao delegada solta quem estava parado na pausa: ele tica mais um item junto com a operacao (I2b)'),
 'aborto_nao_espera_pedindo': ('Barreira', "                while (algumEm(Trabalhador.RODANDO, Trabalhador.PEDINDO)) {", "                while (algumEm(Trabalhador.RODANDO, -1)) {",
     'abortar volta sem esperar o trabalhador que esta no meio de um pedido (I2c)'),
}


def jar_de(nome):
    libs = os.environ.get('HOSHIE_LIBS', '')
    for raiz in (libs, os.path.expanduser('~/.gradle/caches/modules-2/files-2.1')):
        if raiz and os.path.isdir(raiz):
            achados = glob.glob(os.path.join(raiz, '**', nome), recursive=True)
            if achados:
                return achados[0]
    sys.exit('ERRO: jar %s nao encontrado (defina HOSHIE_LIBS=<pasta libraries/ de um servidor>)' % nome)


def rodar(w, saida, j8, cp, nome, so_sonda):
    arq, de, para, oque = M[nome]
    src = os.path.join(w, 'src/main/java/hoshiestar/paralelo')
    tst = os.path.join(w, 'src/test/paralelo/java/hoshiestar/paralelo')
    d = os.path.join(saida, nome)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(os.path.join(d, 'src/hoshiestar/paralelo'))
    os.makedirs(os.path.join(d, 'cls'))
    for n in NUCLEO:
        with open(os.path.join(src, n + '.java'), encoding='utf-8') as f:
            s = f.read()
        if n == arq:
            if s.count(de) != 1:
                return 'NAO APLICOU (o trecho aparece %d vezes; atualize a tabela)' % s.count(de)
            s = s.replace(de, para)
        with open(os.path.join(d, 'src/hoshiestar/paralelo', n + '.java'), 'w', encoding='utf-8') as f:
            f.write(s)
    fontes = [os.path.join(d, 'src/hoshiestar/paralelo', n + '.java') for n in NUCLEO]
    fontes += [os.path.join(w, 'src/main/java/hoshiestar/paralelo/Paralelo.java'), os.path.join(w, 'src/test/java/hoshiestar/teste/Verifica.java')]
    fontes += [os.path.join(tst, t + '.java') for t in TESTES]
    env = dict(os.environ)
    env.pop('JAVA_TOOL_OPTIONS', None)
    env.pop('_JAVA_OPTIONS', None)
    c = subprocess.run([os.path.join(j8, 'bin/javac'), '-nowarn', '-encoding', 'UTF-8', '-source', '1.8', '-target', '1.8', '-d', os.path.join(d, 'cls'),
                        '-sourcepath', os.path.join(w, 'src/main/java'), '-cp', cp] + fontes, capture_output=True, text=True, env=env)
    if c.returncode != 0:
        return 'NAO COMPILOU: ' + c.stderr[-400:]
    pegou = []
    for t in ([SONDA] if so_sonda else TESTES[1:]):
        if t == SONDA and pegou:
            break
        try:
            r = subprocess.run([os.path.join(j8, 'bin/java'), '-cp', os.path.join(d, 'cls'), 'hoshiestar.paralelo.' + t],
                               capture_output=True, text=True, env=env, timeout=900)
            falhas = [l.strip() for l in r.stdout.splitlines() if l.strip().startswith('FALHA')]
            if r.returncode != 0:
                pegou.append('%s: saida %d, %d FALHA; primeira: %s' % (t, r.returncode, len(falhas), falhas[0][:160] if falhas else r.stdout[-160:].replace('\n', ' ')))
        except subprocess.TimeoutExpired:
            pegou.append(t + ': passou de 900 s')
    if pegou:
        return 'PEGA: ' + ' | '.join(pegou)
    return 'NAO PEGA (so a sonda)' if so_sonda else 'SOBREVIVEU'


def main():
    if len(sys.argv) < 2:
        sys.exit('uso: mutacoes-nucleo.py <workspace> [--sonda] [nome...]')
    w = os.path.abspath(sys.argv[1])
    resto = sys.argv[2:]
    so_sonda = '--sonda' in resto
    nomes = [a for a in resto if a != '--sonda'] or list(M)
    for n in nomes:
        if n not in M:
            sys.exit('mutacao desconhecida: %s (as que existem: %s)' % (n, ', '.join(M)))
    j8 = os.environ.get('JAVA8_HOME', '/usr/lib/jvm/java-8-openjdk-amd64')
    saida = os.environ.get('HOSHIE_SAIDA') or tempfile.mkdtemp(prefix='mutacoes-nucleo-')
    cp = ':'.join(jar_de(j) for j in ('snakeyaml-1.9.jar', 'log4j-api-2.0-beta9.jar'))
    print('[mutacoes] workspace: %s' % w)
    print('[mutacoes] saida: %s' % saida)
    ruins = 0
    for n in nomes:
        res = rodar(w, saida, j8, cp, n, so_sonda)
        if not res.startswith('PEGA'):
            ruins += 1
        print('%-30s %s\n    (%s)' % (n, res, M[n][3]), flush=True)
    print('[mutacoes] %d defeito(s), %d pego(s), %d nao pego(s)' % (len(nomes), len(nomes) - ruins, ruins))
    sys.exit(0 if ruins == 0 else 1)


if __name__ == '__main__':
    main()
