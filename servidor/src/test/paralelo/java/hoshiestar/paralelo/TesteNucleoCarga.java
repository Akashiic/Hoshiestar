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

import hoshiestar.paralelo.ApoioNucleo.Amb;
import hoshiestar.paralelo.ApoioNucleo.Lote;
import hoshiestar.paralelo.ApoioNucleo.Vigia;
import hoshiestar.teste.Verifica;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static hoshiestar.paralelo.ApoioNucleo.COMPLETO;
import static hoshiestar.paralelo.ApoioNucleo.SEG;
import static hoshiestar.paralelo.ApoioNucleo.girar;
import static hoshiestar.paralelo.ApoioNucleo.seg;

/**
 * Carga do nucleo de concorrencia (linha 5 da secao 11 do plano do Degrau B), com regioes falsas de custo sorteado:
 * <ol>
 * <li><b>ticks</b>: muitos ticks seguidos com 1 a 8 threads de regiao; a cada tick confere que cada lote rodou exatamente
 * uma vez, com os itens em ordem, numa thread de regiao so, e que a fase so terminou com todos;</li>
 * <li><b>caos</b>: o mesmo com pedidos delegados (com e sem monitor, aninhados, com excecao, sujando outro lote), erros de
 * item e duas threads estranhas batendo no portao; confere "a operacao nunca roda junto com outro trabalhador", as regras
 * de parada e, com o resto rodado em serie numa juncao falsa, "cada item exatamente uma vez".</li>
 * </ol>
 *
 * <p>Modo rapido (padrao): 15 mil ticks por numero de threads e 4 mil ticks de caos por configuracao. Com
 * {@code -Dhoshiestar.teste.completo=true}: 1 milhao de ticks por numero de threads (1 a 8) e 150 mil de caos.
 * {@code TesteNucleoCarga [semente]}.
 */
public final class TesteNucleoCarga {

    private static final int TICKS = COMPLETO ? 1000000 : 15000;
    private static final int TICKS_CAOS = COMPLETO ? 150000 : 4000;
    private static final long FATIA = 5L * SEG;

    private static Verifica v;

    private TesteNucleoCarga() {
    }

    public static void main(String[] args) {
        v = new Verifica("TesteNucleoCarga (" + (COMPLETO ? "completo" : "rapido") + ": " + TICKS + " ticks por numero de threads, "
                + TICKS_CAOS + " de caos)");
        long semente = args.length > 0 ? Long.parseLong(args[0]) : 20261126L;
        Vigia.ligar();
        try {
            for (int threads = 1; threads <= 8; threads++) {
                Vigia.prazo("ticks com " + threads + " thread(s)", COMPLETO ? 3600 : 300);
                ticks(threads, semente + threads);
            }
            for (int threads : new int[] {1, 2, 3, 4, 6, 8}) {
                Vigia.prazo("caos com " + threads + " thread(s)", COMPLETO ? 3600 : 300);
                caos(threads, semente * 31 + threads);
            }
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    // ------------------------------------------------------------------------------------------------------------
    // 1. ticks
    // ------------------------------------------------------------------------------------------------------------

    /** O que os itens de um tick enxergam. {@code aberta}: a thread do teste liga antes de rodar e desliga depois. */
    private static final class Tick {
        volatile boolean aberta;
        final AtomicInteger foraDaFase = new AtomicInteger();
        /** Ordem em que os lotes comecaram (so com 1 thread). */
        final AtomicInteger seq = new AtomicInteger();
    }

    static void ticks(int threads, long semente) {
        Amb amb = new Amb();
        Barreira b = Barreira.criar(threads, amb, FATIA, 0L);
        Random rnd = new Random(semente);
        final Tick tk = new Tick();
        final long[] poco = new long[1];
        long lotes = 0;
        long itens = 0;
        long falhas = 0;
        String primeira = null;
        long t0 = System.nanoTime();
        List<Trabalho> ls = new ArrayList<Trabalho>();
        for (int tick = 0; tick < TICKS; tick++) {
            ls.clear();
            int n = rnd.nextInt(10) == 0 ? 0 : 1 + rnd.nextInt(12);
            for (int k = 0; k < n; k++) {
                final int giros = rnd.nextInt(8) == 0 ? rnd.nextInt(3000) : rnd.nextInt(60);
                final boolean cede = rnd.nextInt(64) == 0;
                ls.add(new LoteDeTick(k, rnd.nextInt(8), rnd.nextInt(1000), tk, giros, cede, poco));
            }
            tk.aberta = true;
            b.abrir();
            Fase f = b.rodar(ls);
            tk.aberta = false;
            b.fechar();
            // conferencia do tick
            String erro = null;
            if (f.total() != n || f.fechados() != n || f.semNovos() || f.despejos() != 0) {
                erro = "fase: " + f;
            }
            long custoAnterior = Long.MAX_VALUE;
            for (int j = 0; j < f.total() && erro == null; j++) {
                Andamento a = f.lote(j);
                LoteDeTick l = (LoteDeTick) a.trabalho;
                Andamento.Fecho fe = a.fecho();
                if (fe == null || fe.fim != Andamento.Fim.COMPLETO || fe.feitos != l.n || a.feitos() != l.n) {
                    erro = "lote nao completo: " + a;
                } else if (!l.todos(0, l.n, 1)) {
                    erro = "item nao rodou exatamente uma vez: " + a;
                } else if (l.foraDeOrdem || l.maisDeUmaThread || l.foraDeThreadDeRegiao) {
                    erro = "ordem ou thread: " + a + " foraDeOrdem=" + l.foraDeOrdem + " maisDeUmaThread=" + l.maisDeUmaThread
                            + " foraDeThreadDeRegiao=" + l.foraDeThreadDeRegiao;
                } else if (a.custo > custoAnterior) {
                    erro = "fase fora da ordem de custo";
                } else if (threads == 1 && l.n > 0 && l.ordemDeInicio != esperadoComUmaThread(f, j)) {
                    erro = "com 1 thread o lote " + j + " devia ser o " + esperadoComUmaThread(f, j) + "o a comecar, foi o " + l.ordemDeInicio;
                }
                custoAnterior = a.custo;
                lotes++;
                itens += l.n;
            }
            if (erro == null && tk.foraDaFase.get() != 0) {
                erro = "item rodando com a fase fechada";
            }
            if (erro != null) {
                falhas++;
                if (primeira == null) {
                    primeira = "tick " + tick + ": " + erro;
                }
            }
            tk.seq.set(0);
        }
        long dt = System.nanoTime() - t0;
        boolean encerrou = b.encerrar(10L * SEG).isEmpty();
        v.sim(falhas == 0 && encerrou, threads + " thread(s): " + TICKS + " ticks, " + lotes + " lotes, " + itens + " itens: cada lote uma vez, itens em ordem, "
                + "fase so termina com todos (" + seg(dt) + ", " + (TICKS * SEG / Math.max(1L, dt)) + " ticks/s)"
                + (falhas == 0 ? "" : " FALHAS=" + falhas + " primeira: " + primeira));
        v.sim(b.fases() == TICKS && b.lotes() == lotes && b.pedidos() == 0 && b.mortos() == 0 && b.despejos() == 0 && amb.linhas.isEmpty(),
                threads + " thread(s): contadores batem e nada foi ao log");
        if (poco[0] == 42L) {
            System.out.println("  (poco)");
        }
    }

    /** Com 1 thread, o lote {@code j} da ordem de pega e o (numero de lotes nao vazios antes dele + 1)-esimo a comecar. */
    private static int esperadoComUmaThread(Fase f, int j) {
        int antes = 0;
        for (int i = 0; i < j; i++) {
            if (f.lote(i).tamanho > 0) {
                antes++;
            }
        }
        return antes + 1;
    }

    private static final class LoteDeTick extends LoteBase {
        final Tick tk;
        final int giros;
        final boolean cede;
        final long[] poco;
        int ordemDeInicio;

        LoteDeTick(long chave, int n, long custo, Tick tk, int giros, boolean cede, long[] poco) {
            super(chave, n, custo);
            this.tk = tk;
            this.giros = giros;
            this.cede = cede;
            this.poco = poco;
        }

        @Override
        void item(int i) {
            if (!tk.aberta) {
                tk.foraDaFase.incrementAndGet();
            }
            if (i == 0) {
                ordemDeInicio = tk.seq.incrementAndGet();
            }
            long x = girar(chave + i, giros);
            if (cede && i == 1) {
                Thread.yield();
            }
            if (x == 42L) {
                poco[0] = x;
            }
            if (!tk.aberta) {
                tk.foraDaFase.incrementAndGet();
            }
        }
    }

    /** Regiao falsa enxuta para a carga (mesmas conferencias de {@link Lote}, sem o objeto Item por lote). */
    private abstract static class LoteBase implements Trabalho {
        final long chave;
        final int n;
        final long custo;
        final int[] vezes;
        int ultimo = -1;
        boolean foraDeOrdem;
        Thread quem;
        boolean maisDeUmaThread;
        boolean foraDeThreadDeRegiao;
        boolean emSerie;

        LoteBase(long chave, int n, long custo) {
            this.chave = chave;
            this.n = n;
            this.custo = custo;
            this.vezes = new int[n];
        }

        abstract void item(int i) throws Throwable;

        @Override
        public final long chave() {
            return chave;
        }

        @Override
        public final int tamanho() {
            return n;
        }

        @Override
        public final long custo() {
            return custo;
        }

        @Override
        public final void ticar(int i) throws Throwable {
            if (!emSerie) {
                Thread eu = Thread.currentThread();
                if (quem == null) {
                    quem = eu;
                    if (!(eu instanceof ThreadRegiao)) {
                        foraDeThreadDeRegiao = true;
                    }
                } else if (quem != eu) {
                    maisDeUmaThread = true;
                }
            }
            if (i != ultimo + 1) {
                foraDeOrdem = true;
            }
            ultimo = i;
            vezes[i]++;
            item(i);
        }

        @Override
        public final String descrever(int i) {
            return "carga(" + chave + ":" + i + ")";
        }

        final boolean todos(int de, int ate, int k) {
            for (int i = de; i < ate; i++) {
                if (vezes[i] != k) {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // 2. caos
    // ------------------------------------------------------------------------------------------------------------

    /** O estado de um tick do caos, visto pelos itens, pelas operacoes e pelas threads estranhas. */
    private static final class Caos {
        final Barreira b;
        final Thread servidor;
        final Object monitor = new Object();
        final AtomicLong problemas = new AtomicLong();
        final AtomicLong operacoes = new AtomicLong();
        final AtomicLong comMonitor = new AtomicLong();
        final AtomicLong aninhadas = new AtomicLong();
        final AtomicLong errosDeItem = new AtomicLong();
        volatile String primeiro;
        /** Os lotes do tick corrente (vetor novo por tick; a thread do teste troca antes de rodar). */
        volatile LoteCaos[] lotes = new LoteCaos[0];
        /** Uma operacao de pedido esta rodando agora. */
        volatile boolean opEmCurso;
        /** Ja houve um pedido atendido neste tick. */
        volatile boolean violou;
        volatile boolean acabou;

        Caos(Barreira b, Thread servidor) {
            this.b = b;
            this.servidor = servidor;
        }

        void problema(String s) {
            problemas.incrementAndGet();
            if (primeiro == null) {
                primeiro = s;
            }
        }
    }

    private static final class ErroDeItem extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ErroDeItem() {
            super("erro de item sorteado", null, false, false);
        }
    }

    private static final class LoteCaos extends LoteBase {
        final Caos c;
        final Random rnd;
        volatile boolean dentro;
        volatile boolean pedindo;
        /** Uma operacao sujou este lote (escrito pela thread do servidor, dentro da operacao). */
        volatile boolean sujoMarcado;
        /** Indice do item que lancou, ou -1. */
        int lancouEm = -1;
        /** Recebe o resultado das contas, para o JIT nao apaga-las. */
        long lixo;
        /** O mesmo, escrito pela thread do servidor dentro da operacao. */
        volatile long lixoDaOp;

        LoteCaos(long chave, int n, long custo, Caos c, long semente) {
            super(chave, n, custo);
            this.c = c;
            this.rnd = new Random(semente);
        }

        @Override
        void item(final int i) {
            if (emSerie) {
                return;
            }
            dentro = true;
            if (c.opEmCurso) {
                c.problema("item " + chave + ":" + i + " comecou com uma operacao em curso");
            }
            if (i == 0 && c.violou) {
                c.problema("lote " + chave + " comecou depois da primeira violacao do tick");
            }
            if (sujoMarcado) {
                c.problema("item " + chave + ":" + i + " comecou com o lote ja sujo");
            }
            lixo += girar(chave + i, rnd.nextInt(300));
            int sorte = rnd.nextInt(1000);
            if (sorte < 25) {
                pedir(i);
            } else if (sorte < 27) {
                lancouEm = i;
                c.errosDeItem.incrementAndGet();
                dentro = false;
                throw new ErroDeItem();
            } else if (sorte < 60) {
                Thread.yield();
            }
            lixo += girar(chave - i, rnd.nextInt(300));
            dentro = false;
        }

        private void pedir(final int i) {
            final LoteCaos[] todos = c.lotes;
            final boolean usaMonitor = rnd.nextInt(3) == 0;
            final boolean aninha = rnd.nextInt(4) == 0;
            final boolean lanca = rnd.nextInt(8) == 0;
            final long[] sujar = rnd.nextBoolean() ? new long[] {todos[rnd.nextInt(todos.length)].chave} : null;
            final IllegalArgumentException daOp = lanca ? new IllegalArgumentException("da operacao") : null;
            final LoteCaos eu = this;
            Operacao<Integer> op = new Operacao<Integer>() {
                @Override
                public Integer rodar() {
                    c.opEmCurso = true;
                    c.operacoes.incrementAndGet();
                    if (Thread.currentThread() != c.servidor) {
                        c.problema("operacao fora da thread do servidor");
                    }
                    if (c.b.papel() != Barreira.ATENDENDO) {
                        c.problema("papel na operacao: " + c.b.papel());
                    }
                    for (LoteCaos o : todos) {
                        if (o.dentro && !o.pedindo) {
                            c.problema("lote " + o.chave + " dentro de um item, sem pedir, durante a operacao do lote " + eu.chave);
                        }
                    }
                    if (!eu.pedindo) {
                        c.problema("quem pediu nao esta em pedir");
                    }
                    if (sujar != null) {
                        for (LoteCaos o : todos) {
                            if (o.chave == sujar[0] && o != eu) {
                                o.sujoMarcado = true;
                            }
                        }
                    }
                    int r = 1;
                    if (usaMonitor) {
                        synchronized (c.monitor) {
                            c.comMonitor.incrementAndGet();
                        }
                    }
                    if (aninha) {
                        r += c.b.pedir(new Operacao<Integer>() {
                            @Override
                            public Integer rodar() {
                                c.aninhadas.incrementAndGet();
                                if (Thread.currentThread() != c.servidor) {
                                    c.problema("operacao aninhada fora da thread do servidor");
                                }
                                return 1;
                            }
                        }, null, null);
                    }
                    eu.lixoDaOp += girar(i, 200);
                    for (LoteCaos o : todos) {
                        if (o.dentro && !o.pedindo) {
                            c.problema("lote " + o.chave + " entrou num item durante a operacao");
                        }
                    }
                    c.violou = true;
                    c.opEmCurso = false;
                    if (daOp != null) {
                        throw daOp;
                    }
                    return r;
                }
            };
            pedindo = true;
            try {
                int r = c.b.pedir(op, usaMonitor ? c.monitor : null, sujar);
                if (r != (aninha ? 2 : 1) || lanca) {
                    c.problema("resultado errado do pedido: " + r);
                }
            } catch (IllegalArgumentException e) {
                if (e != daOp) {
                    c.problema("excecao errada do pedido: " + e);
                }
            }
            pedindo = false;
            if (c.opEmCurso) {
                c.problema("quem pediu voltou a rodar com outra operacao em curso");
            }
        }
    }

    static void caos(int threads, long semente) throws Throwable {
        Amb amb = new Amb();
        final Barreira b = Barreira.criar(threads, amb, FATIA, 0L);
        final Caos c = new Caos(b, Thread.currentThread());
        Random rnd = new Random(semente);
        final long[] passagens = new long[8];
        // duas threads estranhas: uma sem monitor, outra segurando o monitor que parte dos pedidos usa
        Thread e1 = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!c.acabou) {
                    int r = b.portao(null);
                    synchronized (passagens) {
                        passagens[r]++;
                    }
                    LockSupport.parkNanos(30000L);
                }
            }
        }, "estranha sem monitor");
        Thread e2 = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!c.acabou) {
                    synchronized (c.monitor) {
                        int r = b.portao(c.monitor);
                        if (r == Barreira.PORTAO_MONITOR) {
                            // passou com a fase rodando: enquanto segurar o monitor, nenhum trabalhador pode estar num item
                            for (LoteCaos o : c.lotes) {
                                if (o.dentro && !o.pedindo) {
                                    c.problema("lote " + o.chave + " num item enquanto a thread estranha escrevia pela excecao do monitor");
                                }
                            }
                            if (b.estado() != Fase.Estado.RODANDO) {
                                c.problema("excecao do monitor fora de RODANDO");
                            }
                        }
                        synchronized (passagens) {
                            passagens[4 + r]++;
                        }
                    }
                    LockSupport.parkNanos(30000L);
                }
            }
        }, "estranha com monitor");
        e1.setDaemon(true);
        e2.setDaemon(true);
        e1.start();
        e2.start();
        long itensTotal = 0;
        long falhas = 0;
        String primeira = null;
        long[] fins = new long[Andamento.Fim.values().length];
        long t0 = System.nanoTime();
        for (int tick = 0; tick < TICKS_CAOS; tick++) {
            int n = 1 + rnd.nextInt(10);
            LoteCaos[] ls = new LoteCaos[n];
            List<Trabalho> lista = new ArrayList<Trabalho>(n);
            for (int k = 0; k < n; k++) {
                ls[k] = new LoteCaos(100 + k, 1 + rnd.nextInt(8), rnd.nextInt(50), c, rnd.nextLong());
                lista.add(ls[k]);
            }
            c.violou = false;
            c.lotes = ls;
            b.abrir();
            Fase f = b.rodar(lista);
            String erro = null;
            if (f.fechados() != n || c.opEmCurso) {
                erro = "fase: " + f;
            }
            // a juncao falsa: o resto dos lotes sujos e dos que sobraram roda em serie, na ordem da chave
            Andamento[] pc = f.porChave();
            for (Andamento a : pc) {
                LoteCaos l = (LoteCaos) a.trabalho;
                Andamento.Fecho fe = a.fecho();
                fins[fe.fim.ordinal()]++;
                if (fe.feitos != a.feitos() || (fe.fim != Andamento.Fim.ERRO && l.ultimo + 1 != fe.feitos) || l.foraDeOrdem
                        || l.maisDeUmaThread || l.foraDeThreadDeRegiao) {
                    erro = "andamento nao bate com o que rodou: " + a + " ultimo=" + l.ultimo;
                }
                switch (fe.fim) {
                    case COMPLETO:
                        break;
                    case SUJO:
                        if (!a.sujo() || fe.feitos == 0 || fe.feitos >= l.n) {
                            erro = "SUJO sem estar sujo, sem ter comecado ou ja completo: " + a;
                        }
                        l.emSerie = true;
                        for (int i = fe.feitos; i < l.n; i++) {
                            l.ticar(i);
                        }
                        break;
                    case SOBROU:
                        if (fe.feitos != 0 || !f.semNovos()) {
                            erro = "SOBROU com item feito ou sem a fase ter parado: " + a;
                        }
                        l.emSerie = true;
                        for (int i = 0; i < l.n; i++) {
                            l.ticar(i);
                        }
                        break;
                    case ERRO:
                        if (!(fe.erro instanceof ErroDeItem) || l.lancouEm != fe.feitos || !l.todos(0, fe.feitos + 1, 1)
                                || !l.todos(fe.feitos + 1, l.n, 0)) {
                            erro = "ERRO que nao bate: " + a + " lancouEm=" + l.lancouEm;
                        }
                        break;
                    default:
                        erro = "fim inesperado no caos: " + a;
                }
                if (fe.fim != Andamento.Fim.ERRO && !l.todos(0, l.n, 1)) {
                    erro = "depois da juncao falsa, item que nao rodou exatamente uma vez: " + a;
                }
                if (l.foraDeOrdem) {
                    erro = "item fora de ordem: " + a;
                }
                itensTotal += l.n;
            }
            b.fechar();
            if (erro != null) {
                falhas++;
                if (primeira == null) {
                    primeira = "tick " + tick + ": " + erro;
                }
            }
        }
        long dt = System.nanoTime() - t0;
        c.acabou = true;
        e1.join(10000L);
        e2.join(10000L);
        boolean encerrou = b.encerrar(10L * SEG).isEmpty() && !e1.isAlive() && !e2.isAlive();
        StringBuilder sf = new StringBuilder();
        for (Andamento.Fim fi : Andamento.Fim.values()) {
            if (fins[fi.ordinal()] != 0) {
                sf.append(' ').append(fi).append('=').append(fins[fi.ordinal()]);
            }
        }
        v.sim(falhas == 0 && encerrou, threads + " thread(s), caos: " + TICKS_CAOS + " ticks, " + itensTotal + " itens; fins:" + sf + " ("
                + seg(dt) + ")" + (falhas == 0 ? "" : " FALHAS=" + falhas + " primeira: " + primeira));
        v.sim(c.problemas.get() == 0, threads + " thread(s), caos: " + c.operacoes.get() + " operacoes de pedido (" + c.comMonitor.get()
                + " com monitor, " + c.aninhadas.get() + " aninhadas), nenhuma junto com outro trabalhador; regras de parada respeitadas"
                + (c.problemas.get() == 0 ? "" : " PROBLEMAS=" + c.problemas.get() + " primeiro: " + c.primeiro));
        v.sim(b.pedidos() == c.operacoes.get() && b.pedidosAninhados() == c.aninhadas.get() && b.pedidosForaDeLugar() == 0 && b.mortos() == 0
                && c.operacoes.get() > 0 && c.errosDeItem.get() > 0,
                threads + " thread(s), caos: cada pedido rodou exatamente uma vez (" + b.pedidos() + "); " + c.errosDeItem.get() + " erros de item");
        long esperou;
        long porMonitor;
        long total = 0;
        synchronized (passagens) {
            esperou = passagens[Barreira.PORTAO_ESPEROU] + passagens[4 + Barreira.PORTAO_ESPEROU];
            porMonitor = passagens[4 + Barreira.PORTAO_MONITOR];
            for (long p : passagens) {
                total += p;
            }
        }
        v.sim(total > 0 && b.esperasNoPortao() >= esperou && b.passagensPeloMonitor() == porMonitor && passagens[Barreira.PORTAO_MONITOR] == 0
                && passagens[Barreira.PORTAO_ABERTO] == 0 && passagens[4 + Barreira.PORTAO_ABERTO] == 0,
                threads + " thread(s), caos: threads estranhas passaram " + total + " vezes pelo portao (" + esperou + " esperando a barreira, "
                        + porMonitor + " pela excecao do monitor), sem travar");
    }
}
