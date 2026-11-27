/*
 * Hoshiestar: servidor hibrido Forge + Bukkit para Minecraft 1.7.10, fork do Crucible.
 * Copyright (C) 2026 Projeto Hoshiestar
 *
 * Este programa e software livre: voce pode redistribui-lo e/ou modifica-lo sob os termos da
 * GNU General Public License publicada pela Free Software Foundation, versao 3 da Licenca ou
 * (a seu criterio) qualquer versao posterior. Distribuido SEM QUALQUER GARANTIA.
 * Veja <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package hoshiestar.paralelo;

import hoshiestar.paralelo.ApoioNucleo.Vigia;
import hoshiestar.teste.Verifica;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static hoshiestar.paralelo.ApoioNucleo.COMPLETO;
import static hoshiestar.paralelo.ApoioNucleo.MS;
import static hoshiestar.paralelo.ApoioNucleo.SEG;
import static hoshiestar.paralelo.ApoioNucleo.gastar;

/**
 * Sonda de instante sorteado do nucleo de concorrencia (passo 5 do plano do Degrau B). Veio da revisao adversaria do passo
 * (la se chamava RevCaos) e entrou na suite a pedido dela: os casos montados de {@link TesteNucleoCasos} pegam um defeito
 * na intercalacao que alguem pensou; esta sonda sorteia a intercalacao.
 *
 * <p>Cada repeticao cria uma barreira nova (1 a 8 threads), roda de 1 a 25 fases seguidas e, num instante sorteado, deixa
 * cair um evento: {@code abortar}, {@code encerrar}, morte de uma thread de regiao (pelo relogio), interrupcoes na thread
 * do servidor, ou um item preso com {@code abortar} de prazo curto; morte e interrupcoes tambem caem JUNTO com os outros.
 * Durante tudo isso: pedidos delegados (com e sem monitor, aninhados, dois no mesmo item, sujando lotes), operacoes que
 * levam de 0,1 a 1 ms (para o evento cair no meio delas), erros de item e ate 3 threads estranhas no portao (sem monitor,
 * com o monitor do pedido, com outro monitor, com duas guardas na mesma escrita). No fim de cada repeticao, {@code encerrar};
 * uma vez em quatro, com a thread que chama ja interrompida.
 *
 * <p>O oraculo nao usa estado interno do nucleo. Cada item conta entrada e saida num contador do teste; cada operacao
 * confere "ninguem dentro de item" no comeco e no fim e "nenhum item comecou" durante; cada item confere, ao comecar: fase
 * ainda aberta, nenhuma operacao em curso, nenhuma escrita pela excecao do monitor em curso, {@code abortar} ainda nao
 * voltou vazio, lote nao sujo por pedido ja atendido, item 0 nao depois de uma operacao. Depois de cada {@code rodar}: todo
 * lote com fecho e as contagens por item batendo com o fim. Fatia de 5 s: qualquer despejo conta como wakeup perdido.
 *
 * <p>Modo rapido (padrao): 4 mil repeticoes. Com {@code -Dhoshiestar.teste.completo=true}: 40 mil.
 * {@code TesteNucleoSonda [repeticoes] [semente] [maximo de threads]}.
 */
public final class TesteNucleoSonda {


    static final AtomicInteger nProbs = new AtomicInteger();
    static final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
    static volatile String oque = "";

    private TesteNucleoSonda() {
    }

    static void prob(String s) {
        if (nProbs.incrementAndGet() <= 40) {
            probs.add(oque + ": " + s);
        }
    }

    static final class Morte extends Error {
        private static final long serialVersionUID = 1L;

        Morte() {
            super("morte injetada pela sonda");
        }
    }

    static final class ErroDeItem extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static final class Amb implements Ambiente {
        final AtomicLong linhas = new AtomicLong();
        final AtomicInteger matarEm = new AtomicInteger(-1);
        volatile String ultima = "";

        @Override
        public void log(String linha) {
            linhas.incrementAndGet();
            ultima = linha;
        }

        @Override
        public void erro(String linha, Throwable causa) {
            linhas.incrementAndGet();
        }

        @Override
        public long agora() {
            if (Thread.currentThread() instanceof ThreadRegiao && matarEm.get() >= 0 && matarEm.decrementAndGet() == 0) {
                throw new Morte();
            }
            return System.nanoTime();
        }
    }

    /** O estado de uma repeticao, visto so pelo oraculo. */
    static final class Mundo {
        Barreira b;
        final Object monitor = new Object();
        final Object outro = new Object();
        final AtomicInteger dentro = new AtomicInteger();
        final AtomicInteger opsDentro = new AtomicInteger();
        final AtomicLong inicios = new AtomicLong();
        volatile boolean rodando;
        volatile boolean abortoChamado;
        volatile boolean abortoLimpo;
        volatile int opsNaFase;
        volatile boolean escritaMonitor;
        volatile boolean fim;
        volatile Thread servidor;
        volatile List<L> lotesDaFase = new ArrayList<L>();
        final CountDownLatch soltar = new CountDownLatch(1);
        volatile boolean presoDentro;
        volatile long presoChave = -1L;
        volatile int presoItem = -1;
        volatile boolean erroNaFase;
    }

    static final int NADA = 0;
    static final int PEDE = 1;
    static final int PEDE_MONITOR = 2;
    static final int PEDE_SUJA = 3;
    static final int PEDE_ANINHADO = 4;
    static final int ERRO = 5;
    static final int PEDE_DUAS = 6;

    static final class L implements Trabalho {
        final Mundo m;
        final long chave;
        final int n;
        final long custo;
        final int[] plano;
        final long[][] sujar;
        final int[] giros;
        final int[] vezes;
        int ultimo = -1;
        Thread quem;
        volatile boolean sujoMarcado;
        int travaEm = -1;

        L(Mundo m, long chave, int n, long custo, Random r, int pPede, int pErro, int totalDeLotes) {
            this.m = m;
            this.chave = chave;
            this.n = n;
            this.custo = custo;
            this.plano = new int[n];
            this.sujar = new long[n][];
            this.giros = new int[n];
            this.vezes = new int[n];
            for (int i = 0; i < n; i++) {
                giros[i] = r.nextInt(4) == 0 ? r.nextInt(3000) : r.nextInt(60);
                int x = r.nextInt(1000);
                if (x < pPede) {
                    int q = r.nextInt(6);
                    plano[i] = q == 0 ? PEDE_MONITOR : q == 1 ? PEDE_SUJA : q == 2 ? PEDE_ANINHADO : q == 3 ? PEDE_DUAS : PEDE;
                    if (plano[i] == PEDE_SUJA || r.nextInt(4) == 0) {
                        int k = 1 + r.nextInt(3);
                        sujar[i] = new long[k];
                        for (int j = 0; j < k; j++) {
                            sujar[i][j] = r.nextInt(totalDeLotes + 1);
                        }
                    }
                } else if (x < pPede + pErro) {
                    plano[i] = ERRO;
                }
            }
        }

        @Override
        public long chave() {
            return chave;
        }

        @Override
        public int tamanho() {
            return n;
        }

        @Override
        public long custo() {
            return custo;
        }

        @Override
        public String descrever(int i) {
            return "sonda(" + chave + ":" + i + ")";
        }

        @Override
        public void ticar(final int i) throws Throwable {
            final Mundo w = m;
            w.inicios.incrementAndGet();
            if (!w.rodando) {
                prob("item " + chave + ":" + i + " comecou com rodar() ja de volta (fase terminou com trabalhador ativo)");
            }
            if (w.opsDentro.get() != 0) {
                prob("item " + chave + ":" + i + " comecou com operacao delegada em curso");
            }
            if (w.escritaMonitor) {
                prob("item " + chave + ":" + i + " comecou durante a escrita de thread estranha pela excecao do monitor");
            }
            if (w.abortoLimpo) {
                prob("item " + chave + ":" + i + " comecou depois de abortar() voltar sem presos");
            }
            if (sujoMarcado) {
                prob("item " + chave + ":" + i + " comecou com o lote ja sujo por um pedido atendido");
            }
            if (i == 0 && w.opsNaFase > 0) {
                prob("item 0 do lote " + chave + " comecou depois de uma operacao delegada (lote novo depois da violacao)");
            }
            if (i != ultimo + 1) {
                prob("lote " + chave + " fora de ordem: item " + i + " depois de " + ultimo);
            }
            ultimo = i;
            vezes[i]++;
            Thread eu = Thread.currentThread();
            if (quem == null) {
                quem = eu;
            } else if (quem != eu) {
                prob("lote " + chave + " ticado por duas threads");
            }
            if (!(eu instanceof ThreadRegiao)) {
                prob("lote " + chave + " ticado fora de thread de regiao");
            }
            w.dentro.incrementAndGet();
            try {
                gastar(chave * 31 + i, giros[i]);
                if (i == travaEm) {
                    w.presoChave = chave;
                    w.presoItem = i;
                    w.presoDentro = true;
                    boolean foi = false;
                    while (!foi) {
                        try {
                            w.soltar.await();
                            foi = true;
                        } catch (InterruptedException e) {
                            prob("thread de regiao interrompida");
                        }
                    }
                }
                int p = plano[i];
                if (p == ERRO) {
                    throw new ErroDeItem();
                }
                if (p != NADA) {
                    pedir(w, i, p);
                    if (p == PEDE_DUAS) {
                        gastar(i, 50);
                        pedir(w, i, PEDE);
                    }
                    gastar(chave + i, giros[i] / 2);
                }
            } finally {
                w.dentro.decrementAndGet();
            }
        }

        private void pedir(final Mundo w, final int i, final int p) {
            final Thread pedinte = Thread.currentThread();
            final long[] chaves = sujar[i];
            Operacao<Integer> op = new Operacao<Integer>() {
                @Override
                public Integer rodar() {
                    return operacao(w, pedinte, chaves, p, 0);
                }
            };
            w.dentro.decrementAndGet();
            int r;
            try {
                r = w.b.pedir(op, p == PEDE_MONITOR ? w.monitor : null, chaves);
            } finally {
                w.dentro.incrementAndGet();
            }
            if (r != 7) {
                prob("resultado do pedido errado: " + r);
            }
            if (w.opsDentro.get() != 0) {
                prob("quem pediu voltou com operacao ainda em curso");
            }
        }
    }

    static int operacao(final Mundo w, Thread pedinte, long[] chaves, final int p, final int nivel) {
        int d = w.opsDentro.incrementAndGet();
        try {
            if (nivel == 0) {
                w.opsNaFase = w.opsNaFase + 1;
                if (d != 1) {
                    prob("duas operacoes delegadas ao mesmo tempo");
                }
            }
            if (Thread.currentThread() != w.servidor) {
                prob("operacao fora da thread do servidor: " + Thread.currentThread().getName());
            }
            if (w.b.papel() != Barreira.ATENDENDO) {
                prob("papel durante a operacao: " + w.b.papel());
            }
            if (w.escritaMonitor) {
                prob("operacao comecou com a thread estranha ainda escrevendo pela excecao do monitor");
            }
            long antes = w.inicios.get();
            if (w.dentro.get() != 0) {
                prob("operacao comecou com " + w.dentro.get() + " item(ns) em curso em outro trabalhador");
            }
            if (chaves != null && nivel == 0) {
                for (L l : w.lotesDaFase) {
                    for (long k : chaves) {
                        if (l.chave == k) {
                            l.sujoMarcado = true;
                        }
                    }
                }
            }
            if (p == PEDE_MONITOR) {
                synchronized (w.monitor) {
                    gastar(antes, 200);
                }
            }
            gastar(antes, 400);
            if (nivel == 0 && (antes & 7L) == 0L) {
                // operacao demorada (0,1 a 1 ms): da tempo de um abortar, uma morte ou uma thread estranha cairem no meio dela
                gastar(antes, 100000 + (int) (antes % 9L) * 100000);
            }
            if (w.b.portao(w.monitor) != Barreira.PORTAO_DIRETO) {
                prob("o portao segurou a thread do servidor");
            }
            if (p == PEDE_ANINHADO && nivel < 2) {
                final Thread eu = Thread.currentThread();
                int r = w.b.pedir(new Operacao<Integer>() {
                    @Override
                    public Integer rodar() {
                        return operacao(w, eu, null, p, nivel + 1);
                    }
                }, nivel == 0 ? w.monitor : null, new long[] {1L});
                if (r != 7) {
                    prob("aninhado devolveu " + r);
                }
            }
            if (w.dentro.get() != 0 || w.inicios.get() != antes) {
                prob("item em curso ou iniciado DURANTE a operacao delegada (dentro=" + w.dentro.get() + ", inicios +" + (w.inicios.get() - antes) + ")");
            }
            return 7;
        } finally {
            w.opsDentro.decrementAndGet();
        }
    }

    static final AtomicLong comPreso = new AtomicLong();
    static final AtomicLong passagens = new AtomicLong();
    static final AtomicLong passagensMonitor = new AtomicLong();
    static final AtomicLong passagensAberto = new AtomicLong();
    static final AtomicLong esperou = new AtomicLong();
    static final AtomicLong escritasDentroDaFase = new AtomicLong();
    static final AtomicLong escritasComItem = new AtomicLong();
    static final AtomicLong escritasDeQuemEsperou = new AtomicLong();

    static Thread estranha(final Mundo w, final long semente, final int idx) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Random r = new Random(semente);
                while (!w.fim) {
                    int modo = r.nextInt(4);
                    int res;
                    if (modo == 0) {
                        synchronized (w.monitor) {
                            res = w.b.portao(w.monitor);
                            if (res == Barreira.PORTAO_MONITOR) {
                                passagensMonitor.incrementAndGet();
                                w.escritaMonitor = true;
                                long antes = w.inicios.get();
                                if (w.dentro.get() != 0 || w.opsDentro.get() != 0) {
                                    prob("excecao do monitor com item em curso (" + w.dentro.get() + ") ou operacao em curso (" + w.opsDentro.get() + ")");
                                }
                                gastar(antes, 100 + r.nextInt(3000));
                                // escrita que passa por outra guarda, ainda com o monitor
                                int de = w.b.portao(w.monitor);
                                if (de != Barreira.PORTAO_MONITOR && de != Barreira.PORTAO_ABERTO) {
                                    prob("segunda guarda da mesma escrita, ainda com o monitor: portao devolveu " + de);
                                }
                                if (w.dentro.get() != 0 || w.opsDentro.get() != 0 || w.inicios.get() != antes) {
                                    prob("item ou operacao durante a escrita pela excecao do monitor");
                                }
                                w.escritaMonitor = false;
                            } else {
                                conta(w, res);
                            }
                        }
                    } else if (modo == 1) {
                        synchronized (w.outro) {
                            res = w.b.portao(w.outro);
                            if (res == Barreira.PORTAO_MONITOR) {
                                prob("thread estranha com OUTRO monitor passou pela excecao");
                            }
                            conta(w, res);
                        }
                    } else {
                        res = w.b.portao(modo == 2 ? null : w.monitor);
                        if (res == Barreira.PORTAO_MONITOR) {
                            prob("thread estranha SEM monitor na mao passou pela excecao");
                        }
                        conta(w, res);
                    }
                    gastar(idx, r.nextInt(2000));
                    if (r.nextInt(8) == 0) {
                        Thread.yield();
                    }
                }
            }
        }, "estranha-" + idx);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Depois de o portao devolver sem ser pela excecao: a "escrita" vem agora. So conta (e o limite aceito pelo plano). */
    static void conta(Mundo w, int res) {
        passagens.incrementAndGet();
        if (res == Barreira.PORTAO_ESPEROU) {
            esperou.incrementAndGet();
        }
        if (res == Barreira.PORTAO_ABERTO) {
            passagensAberto.incrementAndGet();
            if (!w.abortoChamado) {
                prob("PORTAO_ABERTO sem abortar/encerrar");
            }
            return;
        }
        // a escrita da thread estranha: uns microssegundos depois de o portao devolver
        gastar(res, 300);
        if (w.b.estado() == Fase.Estado.RODANDO) {
            escritasDentroDaFase.incrementAndGet();
            if (res == Barreira.PORTAO_ESPEROU) {
                escritasDeQuemEsperou.incrementAndGet();
            }
            if (w.dentro.get() != 0) {
                escritasComItem.incrementAndGet();
            }
        }
    }

    static void juntar(Thread t, long ms) {
        long fim = System.nanoTime() + ms * MS;
        while (t.isAlive() && System.nanoTime() < fim) {
            try {
                t.join(Math.max(1L, (fim - System.nanoTime()) / MS));
            } catch (InterruptedException e) {
                // a propria sonda interrompe a thread do servidor; segue esperando
            }
        }
    }

    public static void main(String[] args) {
        final int reps = args.length > 0 ? Integer.parseInt(args[0]) : COMPLETO ? 40000 : 4000;
        final long semente0 = args.length > 1 ? Long.parseLong(args[1]) : 77L;
        final int maxThreads = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        Verifica v = new Verifica("TesteNucleoSonda (" + (COMPLETO ? "completo" : "rapido") + ": " + reps + " repeticoes, semente " + semente0
                + ", ate " + maxThreads + " threads)");
        Vigia.ligar();

        long fases = 0, lotes = 0, pedidos = 0, mortes = 0, abortos = 0, encerros = 0, interrupcoes = 0, despejos = 0, linhas = 0, comSinal = 0;
        long piorRodar = 0, piorAbortar = 0, lentas = 0;
        long[] fins = new long[Andamento.Fim.values().length];
        final long t00 = System.nanoTime();
        for (int rep = 0; rep < reps; rep++) {
            final long semente = semente0 + rep;
            final Random r = new Random(semente);
            final int threads = 1 + r.nextInt(maxThreads);
            oque = "rep " + rep + " semente " + semente + " threads " + threads;
            Vigia.prazo("sonda, " + oque, 60L);
            final Amb amb = new Amb();
            final Mundo w = new Mundo();
            w.servidor = Thread.currentThread();
            w.b = Barreira.criar(threads, amb, 5L * SEG, 0L);
            final Barreira b = w.b;
            int nEstranhas = r.nextInt(4);
            List<Thread> estranhas = new ArrayList<Thread>();
            for (int i = 0; i < nEstranhas; i++) {
                estranhas.add(estranha(w, semente * 7 + i, i));
            }
            final int evento = r.nextInt(10); // 0,1 abortar; 2 encerrar; 3 morte; 4 interrupcoes; 5 item preso + abortar com prazo curto; resto nada
            final int quando = r.nextInt(3000);
            final int pPede = r.nextInt(3) == 0 ? 0 : r.nextInt(60);
            final int pErro = r.nextInt(4) == 0 ? r.nextInt(10) : 0;
            final long[] durAbortar = new long[1];
            final boolean[] presos = new boolean[1];
            Thread ctl = null;
            Thread interrompe = null;
            if (evento <= 2 || evento == 4 || evento == 5) {
                ctl = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        gastar(quando, quando * 20);
                        if (evento == 4) {
                            for (int k = 0; k < 20 && !w.fim; k++) {
                                w.servidor.interrupt();
                                gastar(k, 3000);
                            }
                            return;
                        }
                        if (evento == 5) {
                            long fim = System.nanoTime() + 300L * MS;
                            while (!w.presoDentro && System.nanoTime() < fim && !w.fim) {
                                Thread.yield();
                            }
                            w.abortoChamado = true;
                            long t0 = System.nanoTime();
                            List<Preso> ps = b.abortar(15L * MS);
                            long dur = System.nanoTime() - t0;
                            long ini = w.inicios.get();
                            if (w.presoDentro) {
                                comPreso.incrementAndGet();
                                boolean achou = false;
                                for (Preso q : ps) {
                                    achou |= q.temLote && q.chave == w.presoChave && q.item == w.presoItem && q.estado.equals("rodando") && q.pilha.length > 0;
                                }
                                if (!achou) {
                                    prob("abortar com trabalhador preso em " + w.presoChave + ":" + w.presoItem + " nao disse quem ficou: " + ps);
                                }
                                if (dur < 15L * MS || dur > 2L * SEG) {
                                    prob("abortar com preso: prazo de 15 ms, voltou em " + dur / MS + " ms");
                                }
                                gastar(1, 2000000);
                                long novos = w.inicios.get() - ini;
                                if (novos > ps.size()) {
                                    prob("depois de abortar voltar com " + ps.size() + " preso(s), " + novos + " itens novos comecaram");
                                }
                            } else if (ps.isEmpty()) {
                                w.abortoLimpo = true;
                            }
                            w.soltar.countDown();
                            return;
                        }
                        w.abortoChamado = true;
                        long t0 = System.nanoTime();
                        List<Preso> ps = evento == 2 ? b.encerrar(10L * SEG) : b.abortar(10L * SEG);
                        durAbortar[0] = System.nanoTime() - t0;
                        if (ps.isEmpty()) {
                            w.abortoLimpo = true;
                        } else {
                            presos[0] = true;
                            prob((evento == 2 ? "encerrar" : "abortar") + " devolveu presos sem nenhum item preso: " + ps);
                        }
                    }
                }, "controle");
                ctl.setDaemon(true);
                ctl.start();
            } else if (evento == 3) {
                amb.matarEm.set(1 + r.nextInt(60));
            }
            // combinacoes: morte e interrupcoes junto com abortar/encerrar/item preso
            if (evento != 3 && r.nextInt(5) == 0) {
                amb.matarEm.set(1 + r.nextInt(120));
            }
            if (evento != 4 && r.nextInt(5) == 0) {
                final int qi = r.nextInt(3000);
                Thread ti = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        gastar(qi, qi * 20);
                        for (int k = 0; k < 10 && !w.fim; k++) {
                            w.servidor.interrupt();
                            gastar(k, 5000);
                        }
                    }
                }, "interrompe");
                ti.setDaemon(true);
                ti.start();
                interrompe = ti;
            }
            int nFases = 1 + r.nextInt(25);
            boolean parou = false;
            for (int k = 0; k < nFases; k++) {
                if (r.nextInt(12) == 0) {
                    b.abrir(); // fase esquecida em MONTANDO
                }
                if (!b.abrir()) {
                    String motivo = b.motivoDaRecusa();
                    boolean ok = (w.abortoChamado && (motivo.contains("abortada") || motivo.contains("encerrada")))
                            || (b.mortos() > 0 || motivo.contains("morta"));
                    if (!ok) {
                        prob("abrir recusou sem motivo valido: " + motivo);
                    }
                    parou = true;
                    break;
                }
                int n = r.nextInt(8) == 0 ? 0 : r.nextInt(14);
                List<L> ls = new ArrayList<L>();
                for (int j = 0; j < n; j++) {
                    ls.add(new L(w, j + 1, r.nextInt(6) == 0 ? 0 : 1 + r.nextInt(24), r.nextInt(100), r, pPede, pErro, n));
                }
                if (evento == 5 && !w.presoDentro && n > 0 && k < 6) {
                    L alvo = ls.get(r.nextInt(n));
                    if (alvo.n > 0) {
                        alvo.travaEm = r.nextInt(alvo.n);
                    }
                }
                w.lotesDaFase = ls;
                w.opsNaFase = 0;
                w.rodando = true;
                long t0 = System.nanoTime();
                Fase f = b.rodar(ls);
                long dt = System.nanoTime() - t0;
                w.rodando = false;
                if (Thread.interrupted()) {
                    interrupcoes++;
                }
                piorRodar = Math.max(piorRodar, dt);
                if (dt > 1L * SEG) {
                    lentas++;
                }
                fases++;
                lotes += n;
                pedidos += f.pedidos().size();
                despejos += f.despejos();
                if (w.dentro.get() != 0 || w.opsDentro.get() != 0) {
                    prob("depois da barreira: dentro=" + w.dentro.get() + " ops=" + w.opsDentro.get());
                }
                if (f.total() != n || f.fechados() != n || b.estado() != Fase.Estado.JUNCAO) {
                    prob("rodar voltou com " + f.fechados() + "/" + f.total() + " (n=" + n + ") estado " + b.estado());
                }
                boolean teveMorte = false;
                for (int j = 0; j < f.total(); j++) {
                    Andamento a = f.lote(j);
                    L l = (L) a.trabalho;
                    Andamento.Fecho fe = a.fecho();
                    if (fe == null) {
                        prob("lote " + l.chave + " sem fecho depois da barreira");
                        continue;
                    }
                    fins[fe.fim.ordinal()]++;
                    int ate;
                    switch (fe.fim) {
                        case COMPLETO:
                            ate = l.n;
                            if (fe.feitos != l.n) {
                                prob("COMPLETO com feitos " + fe.feitos + " de " + l.n);
                            }
                            break;
                        case ERRO:
                            ate = fe.feitos + 1;
                            if (!(fe.erro instanceof ErroDeItem) || l.plano[fe.feitos] != ERRO) {
                                prob("ERRO sem o erro do item ou no item errado: " + fe);
                            }
                            break;
                        case SOBROU:
                            ate = 0;
                            if (fe.feitos != 0) {
                                prob("SOBROU com feitos " + fe.feitos);
                            }
                            break;
                        case MORREU:
                            teveMorte = true;
                            ate = -1;
                            break;
                        default:
                            ate = fe.feitos;
                            break;
                    }
                    for (int i = 0; i < l.n; i++) {
                        if (l.plano[i] == ERRO && l.vezes[i] > 0) {
                            if (!(fe.fim == Andamento.Fim.ERRO && fe.feitos == i) && fe.fim != Andamento.Fim.MORREU) {
                                prob("lote " + l.chave + ": o item " + i + " lancou e o fecho e " + fe + " (erro engolido)");
                            }
                            break;
                        }
                    }
                    if (fe.fim == Andamento.Fim.SUJO && !a.sujo()) {
                        prob("SUJO sem a marca de sujo");
                    }
                    if (fe.fim == Andamento.Fim.ABORTADO && !w.abortoChamado) {
                        prob("ABORTADO sem aborto");
                    }
                    for (int i = 0; i < l.n; i++) {
                        int esperado = ate < 0 ? (i < fe.feitos ? 1 : i == fe.feitos ? -1 : 0) : (i < ate ? 1 : 0);
                        if (esperado >= 0 && l.vezes[i] != esperado) {
                            prob("lote " + l.chave + " " + fe + ": item " + i + " rodou " + l.vezes[i] + " vez(es), esperado " + esperado);
                            break;
                        }
                        if (l.vezes[i] > 1) {
                            prob("item rodou duas vezes");
                        }
                    }
                }
                if (teveMorte != (f.mortos() > 0)) {
                    prob("MORREU e mortos() nao batem: " + f.mortos());
                }
                if (teveMorte) {
                    mortes++;
                }
                b.fechar();
                if (r.nextInt(3) == 0) {
                    gastar(k, r.nextInt(4000));
                }
            }
            if (ctl != null) {
                juntar(ctl, 15000L);
                if (ctl.isAlive()) {
                    prob("abortar/encerrar nao voltou");
                }
                if (evento <= 1) {
                    abortos++;
                } else if (evento == 2) {
                    encerros++;
                }
                piorAbortar = Math.max(piorAbortar, durAbortar[0]);
            }
            w.fim = true;
            w.soltar.countDown();
            if (interrompe != null) {
                juntar(interrompe, 5000L);
            }
            // uma vez em quatro, encerrar e chamado por uma thread ja interrompida (achado I1 da revisao do passo 5); nas
            // outras, o sinal pode estar ligado por acaso (uma interrupcao da propria sonda que chegou depois do ultimo rodar)
            boolean interrompido = r.nextInt(4) == 0;
            if (interrompido) {
                comSinal++;
                Thread.currentThread().interrupt();
            }
            long te = System.nanoTime();
            List<Preso> sobra = b.encerrar(10L * SEG);
            boolean sinal = Thread.interrupted();
            if (!sobra.isEmpty()) {
                prob("encerrar no fim" + (interrompido ? " (com interrupcao pendente)" : "") + " deixou thread: " + sobra);
            }
            if (interrompido && !sinal) {
                prob("encerrar com interrupcao pendente nao repos o sinal");
            }
            for (int i = 0; i < threads; i++) {
                if (b.thread(i).isAlive()) {
                    prob("thread de regiao viva depois de encerrar");
                }
            }
            for (Thread t : estranhas) {
                juntar(t, 10000L);
                if (t.isAlive()) {
                    prob("thread estranha presa depois de encerrar");
                }
            }
            if (System.nanoTime() - te > 3L * SEG) {
                prob("encerrar + estranhas levou " + (System.nanoTime() - te) / MS + " ms");
            }
            linhas += amb.linhas.get();
            if (b.despejos() != 0) {
                prob("despejo de barreira (fatia de 5 s vencida) sem nenhum item lento: " + amb.ultima);
            }
            if (nProbs.get() > 0) {
                break;
            }
        }
        Vigia.prazo("sonda, resumo", 60L);
        StringBuilder sf = new StringBuilder();
        for (Andamento.Fim fim : Andamento.Fim.values()) {
            sf.append(' ').append(fim).append('=').append(fins[fim.ordinal()]);
        }
        System.out.println("  " + fases + " fases, " + lotes + " lotes, " + pedidos + " pedidos, " + abortos + " abortar, " + encerros + " encerrar, "
                + mortes + " fases com morte, " + interrupcoes + " fases com interrupcao, " + comSinal + " encerrar com interrupcao pendente;" + sf);
        System.out.println("  abortar com um trabalhador preso num item (prazo de 15 ms): " + comPreso.get() + " vezes");
        System.out.println("  portao: " + passagens.get() + " passagens comuns (" + esperou.get() + " esperaram), " + passagensMonitor.get()
                + " pela excecao do monitor, " + passagensAberto.get() + " com o portao aberto");
        System.out.println("  limite do portao (aceito no plano): escritas feitas logo depois de o portao devolver que cairam com a fase RODANDO: "
                + escritasDentroDaFase.get() + " (com item em curso: " + escritasComItem.get() + "; de quem esperou no portao e foi liberado com confirmacao: "
                + escritasDeQuemEsperou.get() + ")");
        System.out.println("  pior rodar(): " + piorRodar / MS + " ms; fases acima de 1 s: " + lentas + "; pior abortar/encerrar: " + piorAbortar / MS
                + " ms; despejos: " + despejos + "; linhas de log: " + linhas + "; tempo total " + (System.nanoTime() - t00) / SEG + " s");
        for (String s : probs) {
            v.sim(false, s);
        }
        v.sim(nProbs.get() == 0, "oraculo da sonda: nenhum problema (" + nProbs.get() + ")");
        if (nProbs.get() == 0 && reps >= 2000) {
            v.sim(pedidos > 0 && abortos > 0 && encerros > 0 && mortes > 0 && interrupcoes > 0 && comSinal > 0 && comPreso.get() > 0
                    && passagensMonitor.get() > 0 && passagensAberto.get() > 0 && esperou.get() > 0,
                    "a sonda exercitou tudo: pedidos, abortar, encerrar, morte, interrupcao, item preso, portao (espera, monitor, aberto)");
        }
        v.fim();
    }
}
