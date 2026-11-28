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
import hoshiestar.paralelo.ApoioNucleo.Item;
import hoshiestar.paralelo.ApoioNucleo.Lote;
import hoshiestar.paralelo.ApoioNucleo.MorteInjetada;
import hoshiestar.paralelo.ApoioNucleo.Vigia;
import hoshiestar.teste.Verifica;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static hoshiestar.paralelo.ApoioNucleo.COMPLETO;
import static hoshiestar.paralelo.ApoioNucleo.MS;
import static hoshiestar.paralelo.ApoioNucleo.SEG;
import static hoshiestar.paralelo.ApoioNucleo.dormir;
import static hoshiestar.paralelo.ApoioNucleo.esperar;
import static hoshiestar.paralelo.ApoioNucleo.gastar;
import static hoshiestar.paralelo.ApoioNucleo.seg;

/**
 * Casos montados do nucleo de concorrencia (linha 5 da secao 11 do plano do Degrau B), com regioes falsas: estados da
 * fase, erro injetado, lote que dorme (despejos sem interrupcao), thread que morre, pedido delegado em todas as variantes,
 * thread estranha no portao, abortar e encerrar. A thread do teste faz o papel da thread do servidor.
 *
 * <p>Modo rapido (padrao): fatias de 100 ms. Com {@code -Dhoshiestar.teste.completo=true}: fatias reais de 5 s e o lote
 * que dorme 20 s.
 */
public final class TesteNucleoCasos {

    /** Fatia das esperas nos casos em que o despejo NAO e o assunto: grande, para nao sair despejo. */
    private static final long FATIA_LONGA = 5L * SEG;
    /** Fatia nos casos em que o despejo e o assunto. */
    private static final long FATIA = COMPLETO ? 5L * SEG : 100L * MS;

    private static Verifica v;

    private TesteNucleoCasos() {
    }

    public static void main(String[] args) {
        v = new Verifica("TesteNucleoCasos (" + (COMPLETO ? "completo: fatia de 5 s" : "rapido: fatia de 100 ms") + ")");
        Vigia.ligar();
        try {
            caso("estados da fase", 30, TesteNucleoCasos::estados);
            caso("ordem de pega", 30, TesteNucleoCasos::ordemDePega);
            caso("erro injetado", 30, TesteNucleoCasos::erroInjetado);
            caso("lote que dorme", 90, TesteNucleoCasos::dorme);
            caso("thread que morre (a unica)", 30, TesteNucleoCasos::morreAUnica);
            caso("thread que morre (uma de tres)", 30, TesteNucleoCasos::morreUmaDeTres);
            caso("pedido delegado: basico", 30, TesteNucleoCasos::pedidoBasico);
            caso("pedido delegado: excecao", 30, TesteNucleoCasos::pedidoExcecao);
            caso("pedido delegado: dois juntos", 30, TesteNucleoCasos::doisPedidos);
            caso("pedido delegado: aninhado", 30, TesteNucleoCasos::aninhado);
            caso("pedido delegado: so na thread do servidor", 30, TesteNucleoCasos::soNaThreadDoServidor);
            caso("pedido delegado: espera longa", 90, TesteNucleoCasos::esperaLonga);
            caso("pedido delegado: suja outro lote", 30, TesteNucleoCasos::suja);
            caso("pedido delegado: sem monitor", 30, TesteNucleoCasos::semMonitor);
            caso("portao: sem monitor", 60, () -> portaoSimples(false));
            caso("portao: com monitor, sem pedido", 60, () -> portaoSimples(true));
            caso("portao: com monitor e pedido", 30, () -> portaoComMonitorEPedido(true));
            caso("portao: com monitor e pedido (operacao que nao toma o monitor sozinha)", 30, () -> portaoComMonitorEPedido(false));
            caso("portao: com o monitor, depois de a thread do servidor ja ter saido dele", 30, TesteNucleoCasos::portaoMonitorJaUsado);
            caso("portao: confirmacao antes da fase seguinte", 60, TesteNucleoCasos::portaoConfirmacao);
            caso("portao: outro monitor", 30, TesteNucleoCasos::portaoOutroMonitor);
            caso("portao: fases coladas", 60, TesteNucleoCasos::portaoFasesColadas);
            caso("portao: threads estranhas com interrupcao pendente", 30, TesteNucleoCasos::portaoInterrompido);
            caso("abortar: todos param", 30, TesteNucleoCasos::abortarLivre);
            caso("abortar: um preso", 30, TesteNucleoCasos::abortarPreso);
            caso("abortar: com pedido pendente", 30, TesteNucleoCasos::abortarComPedido);
            caso("abortar: durante uma operacao, com outro trabalhador parado na pausa", 30, TesteNucleoCasos::abortarComParadoNaPausa);
            caso("abortar: espera quem esta no meio de um pedido", 30, TesteNucleoCasos::abortarEsperaQuemPede);
            caso("abortar: com interrupcao pendente em quem chama", 30, () -> abortarInterrompido(false));
            caso("abortar: com interrupcao pendente e tempestade de interrupcoes em quem chama", 30, () -> abortarInterrompido(true));
            caso("abortar: o portao so abre depois de os trabalhadores sairem do item", 30, () -> abortarAbreOPortaoDepois(false));
            caso("abortar: com o trabalhador no ultimo item do lote", 30, () -> abortarNoUltimoItem(false));
            caso("abortar: com a thread de regiao morrendo logo depois do ultimo item", 30, () -> abortarNoUltimoItem(true));
            caso("abortar: pela thread do servidor", 30, TesteNucleoCasos::abortarDoServidor);
            caso("abortar: em MONTANDO", 30, TesteNucleoCasos::abortarEmMontando);
            caso("encerrar", 30, TesteNucleoCasos::encerrar);
            caso("encerrar: um preso", 30, () -> encerrarPreso(false));
            caso("encerrar: um preso, com interrupcao pendente", 30, () -> encerrarPreso(true));
            caso("encerrar: com interrupcao pendente (thread ociosa)", 60, TesteNucleoCasos::encerrarInterrompidoOcioso);
            caso("encerrar: com interrupcao pendente (tres threads no meio de um item)", 30, TesteNucleoCasos::encerrarInterrompidoNoItem);
            caso("encerrar: o portao so abre depois de os trabalhadores sairem do item", 30, () -> abortarAbreOPortaoDepois(true));
            caso("interrupcao da thread do servidor", 30, TesteNucleoCasos::interrupcaoDoServidor);
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    private interface Caso {
        void rodar() throws Throwable;
    }

    private static void caso(String nome, long segundos, Caso c) {
        System.out.println("-- " + nome);
        Vigia.prazo(nome, segundos);
        long t0 = System.nanoTime();
        try {
            c.rodar();
        } catch (Throwable t) {
            v.erro(nome, t);
        }
        Thread.interrupted();
        System.out.println("   (" + seg(System.nanoTime() - t0) + ")");
    }

    private static Barreira nova(int threads, Amb amb, long fatia) {
        return Barreira.criar(threads, amb, fatia, 0L);
    }

    private static void fim(Barreira b) {
        v.sim(b.encerrar(5L * SEG).isEmpty(), "encerrar no fim do caso: nenhuma thread de regiao sobrou");
    }

    private static Lote lote(long chave, int n, long custo, Item item) {
        return new Lote(chave, n, custo, item);
    }

    /** Item que gasta um pouco de CPU e marca "dentro" (para os testes de "nunca junto"). */
    private static Item girador(final int giros) {
        return new Item() {
            @Override
            public void rodar(Lote l, int i) {
                l.dentro = true;
                gastar(l.chave + i, giros);
                l.dentro = false;
            }
        };
    }

    private static Andamento.Fim fimDe(Fase f, Lote l) {
        for (int i = 0; i < f.total(); i++) {
            if (f.lote(i).trabalho == l) {
                return f.lote(i).fecho().fim;
            }
        }
        return null;
    }

    private static Andamento andamento(Fase f, Lote l) {
        for (int i = 0; i < f.total(); i++) {
            if (f.lote(i).trabalho == l) {
                return f.lote(i);
            }
        }
        return null;
    }

    private static Thread iniciar(String nome, final Caso c, final ConcurrentLinkedQueue<String> probs) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    c.rodar();
                } catch (Throwable e) {
                    probs.add(nome + ": " + e);
                }
            }
        }, nome);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void juntar(Thread t) throws InterruptedException {
        t.join(10000L);
        v.sim(!t.isAlive(), "thread auxiliar '" + t.getName() + "' terminou");
    }

    private static boolean pilhaTem(StackTraceElement[] p, String trecho) {
        for (StackTraceElement q : p) {
            if (q.toString().contains(trecho)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------

    static void estados() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(3, amb, FATIA_LONGA);
        v.igual(Fase.Estado.FECHADA, b.estado(), "comeca FECHADA");
        v.igual(Barreira.FORA, b.papel(), "fora da fase o papel e FORA");
        v.igual(Barreira.PORTAO_DIRETO, b.portao(null), "fora da fase o portao nao segura");
        for (int i = 0; i < 3; i++) {
            ThreadRegiao t = b.thread(i);
            v.sim(t.getName().equals("Hoshiestar Regiao #" + (i + 1)) && t.isDaemon() && t.getName().startsWith(Paralelo.PREFIXO_THREAD_REGIAO)
                    && t.barreira() == b && t.loteDaVez() == null, "thread " + (i + 1) + ": nome do plano, daemon, sem lote");
        }
        v.sim(b.abrir(), "abrir: FECHADA -> MONTANDO");
        v.igual(Fase.Estado.MONTANDO, b.estado(), "estado MONTANDO");
        v.igual(Barreira.FORA, b.papel(), "em MONTANDO o papel ainda e FORA (nenhum trabalhador sabe da fase)");
        v.sim(b.abrir(), "abrir de novo com a fase esquecida em MONTANDO: descarta e abre");
        v.igual(1L, b.fasesEsquecidas(), "a fase esquecida foi contada");
        Fase vazia = b.rodar(Collections.<Trabalho>emptyList());
        v.sim(b.estado() == Fase.Estado.JUNCAO && vazia.total() == 0 && vazia.fechados() == 0, "fase vazia vai direto para JUNCAO");
        v.sim(!b.abrir() && b.motivoDaRecusa().contains("juncao"), "abrir com a juncao aberta e recusado, com motivo");
        b.fechar();
        v.igual(Fase.Estado.FECHADA, b.estado(), "fechar: JUNCAO -> FECHADA");
        for (int k = 0; k < 100; k++) {
            b.abrir();
            b.rodar(new ArrayList<Trabalho>());
            b.fechar();
        }
        // uma thread acordada por engano so soma no contador depois de retomar a trava: da tempo a ela (com a maquina ocupada
        // isso leva milissegundos) antes de ler; sem defeito o contador nunca sai de zero e a espera custa os 500 ms inteiros
        boolean acordou = esperar(() -> b.despertares() != 0L || b.acordados() != 0L, 500L);
        v.sim(!acordou && b.acordados() == 0L && b.despertares() == 0L,
                "101 fases sem lote nao acordaram nenhuma thread de regiao, olhando por 500 ms (despertares: " + b.despertares() + ")");
        v.igual(101L, b.fasesVazias(), "101 fases vazias contadas");
        boolean ise = false;
        try {
            b.rodar(new ArrayList<Trabalho>());
        } catch (IllegalStateException e) {
            ise = true;
        }
        v.sim(ise, "rodar sem abrir lanca IllegalStateException");
        final AtomicReference<Throwable> outro = new AtomicReference<Throwable>();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    b.abrir();
                } catch (Throwable e) {
                    outro.set(e);
                }
            }
        });
        t.start();
        t.join();
        v.sim(outro.get() instanceof IllegalStateException, "abrir por outra thread (nao a do servidor) lanca IllegalStateException");

        List<Lote> ls = new ArrayList<Lote>();
        for (int k = 0; k < 5; k++) {
            ls.add(lote(10 + k, 4, k, null));
        }
        b.abrir();
        Fase f = b.rodar(ls);
        boolean ok = f.total() == 5 && f.fechados() == 5 && b.estado() == Fase.Estado.JUNCAO && !f.semNovos();
        for (Lote l : ls) {
            Andamento a = andamento(f, l);
            ok &= a.fecho().fim == Andamento.Fim.COMPLETO && a.fecho().feitos == 4 && a.feitos() == 4 && l.todos(0, 4, 1)
                    && !l.foraDeOrdem && !l.maisDeUmaThread && !l.foraDeThreadDeRegiao && a.nomeDoDono() != null
                    && a.fecho().ns >= 0L && a.fecho().thread.equals(a.nomeDoDono());
        }
        v.sim(ok, "5 lotes de 4 itens: todos COMPLETO, cada item uma vez, em ordem, em thread de regiao");
        v.sim(b.acordados() >= 1 && b.acordados() <= 3, "a fase com lote acordou de 1 a 3 threads (" + b.acordados() + ")");
        Andamento[] pc = f.porChave();
        v.sim(pc.length == 5 && pc[0].chave == 10 && pc[4].chave == 14, "porChave: ordem da juncao, chave menor primeiro");
        b.fechar();
        b.abrir();
        b.fechar();
        v.igual(Fase.Estado.FECHADA, b.estado(), "fechar em MONTANDO descarta a fase");
        fim(b);
    }

    static void ordemDePega() throws Throwable {
        Amb amb = new Amb();
        Barreira b = nova(1, amb, FATIA_LONGA);
        final List<Long> ordem = Collections.synchronizedList(new ArrayList<Long>());
        Item anota = new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 0) {
                    ordem.add(l.chave);
                }
            }
        };
        List<Lote> ls = Arrays.asList(lote(5, 2, 10, anota), lote(3, 2, 70, anota), lote(9, 2, 70, anota), lote(1, 2, 0, anota),
                lote(7, 2, 500, anota));
        b.abrir();
        b.rodar(ls);
        b.fechar();
        v.igual(Arrays.asList(7L, 3L, 9L, 5L, 1L), ordem, "com 1 thread: lotes mais caros primeiro; no empate, chave menor");
        fim(b);
    }

    static void erroInjetado() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(3, amb, FATIA_LONGA);
        final CountDownLatch comecaram = new CountDownLatch(2);
        final CountDownLatch soltar = new CountDownLatch(1);
        final IllegalStateException bomba = new IllegalStateException("erro injetado");
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        Item espera = new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 0) {
                    comecaram.countDown();
                    soltar.await();
                }
            }
        };
        final Lote x = lote(1, 5, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 2) {
                    comecaram.await();
                    throw bomba;
                }
            }
        });
        Lote a = lote(2, 4, 90, espera);
        Lote c = lote(3, 4, 80, espera);
        Lote d = lote(4, 3, 3, null);
        Lote e = lote(5, 3, 2, null);
        Lote g = lote(6, 3, 1, null);
        Thread aux = iniciar("solta depois do erro", new Caso() {
            @Override
            public void rodar() {
                // os outros dois trabalhadores so seguem depois de a fase ter parado de entregar lotes
                if (!esperar(() -> b.faseAtual() != null && b.faseAtual().semNovos(), 10000L)) {
                    probs.add("a fase nao marcou semNovos depois do erro");
                }
                soltar.countDown();
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Arrays.asList(d, a, x, g, c, e));
        juntar(aux);
        Andamento ax = andamento(f, x);
        v.sim(ax.fecho().fim == Andamento.Fim.ERRO && ax.fecho().erro == bomba && ax.fecho().feitos == 2,
                "o lote com erro fecha como ERRO, com o mesmo Throwable e o indice do item (" + ax.fecho() + ")");
        v.sim(x.todos(0, 3, 1) && x.todos(3, 5, 0), "o resto do lote com erro nao rodou");
        v.sim(fimDe(f, a) == Andamento.Fim.COMPLETO && a.todos(0, 4, 1) && fimDe(f, c) == Andamento.Fim.COMPLETO && c.todos(0, 4, 1),
                "os outros dois trabalhadores terminaram o lote corrente");
        boolean sobrou = true;
        for (Lote l : Arrays.asList(d, e, g)) {
            Andamento al = andamento(f, l);
            sobrou &= al.fecho().fim == Andamento.Fim.SOBROU && al.fecho().feitos == 0 && l.todos(0, 3, 0);
        }
        v.sim(sobrou, "depois do erro ninguem pegou lote novo: os tres que restavam voltaram como SOBROU, sem ticar");
        v.sim(f.semNovos() && f.fechados() == 6 && b.estado() == Fase.Estado.JUNCAO, "a barreira passou com os 6 lotes fechados");
        v.sim(probs.isEmpty(), "sem problema nas threads auxiliares " + probs);
        b.fechar();
        Lote depois = lote(1, 3, 0, null);
        v.sim(b.abrir(), "o erro de um item nao quebra a barreira: a fase seguinte abre");
        Fase f2 = b.rodar(Collections.singletonList(depois));
        v.sim(fimDe(f2, depois) == Andamento.Fim.COMPLETO, "e roda");
        b.fechar();
        fim(b);
    }

    static void dorme() throws Throwable {
        Amb amb = new Amb();
        Barreira b = nova(3, amb, FATIA);
        final long sonoMs = COMPLETO ? 20000L : 440L;
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final AtomicReference<String> quem = new AtomicReference<String>();
        Lote dorminhoco = lote(77, 3, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 1) {
                    quem.set(Thread.currentThread().getName());
                    try {
                        Thread.sleep(sonoMs);
                    } catch (InterruptedException e) {
                        probs.add("o lote que dormia foi interrompido");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        probs.add("o lote que dormia ficou com o sinal de interrupcao");
                    }
                }
            }
        });
        Lote r1 = lote(78, 5, 10, null);
        Lote r2 = lote(79, 5, 10, null);
        b.abrir();
        long t0 = System.nanoTime();
        Fase f = b.rodar(Arrays.asList(r1, dorminhoco, r2));
        long dt = System.nanoTime() - t0;
        b.fechar();
        v.sim(dt >= sonoMs * MS, "a barreira esperou o lote que dormia (" + seg(dt) + ")");
        int min = 3;
        int max = COMPLETO ? 4 : 5;
        v.sim(f.despejos() >= min && f.despejos() <= max, "um despejo por fatia vencida: " + f.despejos() + " (esperado de " + min + " a " + max + ")");
        v.igual(f.despejos(), amb.conta("barreira: fase " + f.numero + " esperando"), "cada despejo foi ao log");
        String d = amb.linhaCom("barreira: fase");
        v.sim(d != null && d.contains(quem.get() + " rodando, lote=77 item 1 de 3: falso(77:1)"),
                "o despejo diz a thread atrasada, o lote e o item da vez");
        v.sim(d != null && d.contains("java.lang.Thread.sleep") && d.contains("TesteNucleoCasos"), "o despejo traz a pilha da thread atrasada");
        v.sim(d != null && d.contains("findDeadlockedThreads"), "o despejo traz a consulta de deadlock");
        v.sim(d != null && !d.contains("lote=78") && !d.contains("lote=79"), "os lotes que ja terminaram nao aparecem no despejo");
        v.sim(probs.isEmpty(), "ninguem foi interrompido " + probs);
        v.sim(fimDe(f, dorminhoco) == Andamento.Fim.COMPLETO && dorminhoco.todos(0, 3, 1) && fimDe(f, r1) == Andamento.Fim.COMPLETO
                && fimDe(f, r2) == Andamento.Fim.COMPLETO, "todos os lotes terminaram COMPLETO");
        v.igual((long) f.despejos(), b.despejos(), "contador de despejos da barreira");
        fim(b);
    }

    static void morreAUnica() throws Throwable {
        final Amb amb = new Amb();
        Barreira b = nova(1, amb, FATIA_LONGA);
        Lote a = lote(1, 3, 100, null);
        Lote c = lote(2, 3, 50, null);
        Lote d = lote(3, 3, 10, null);
        b.abrir();
        amb.matarProxima = true;
        long t0 = System.nanoTime();
        Fase f = b.rodar(Arrays.asList(c, a, d));
        long dt = System.nanoTime() - t0;
        Andamento aa = andamento(f, a);
        v.sim(aa.fecho().fim == Andamento.Fim.MORREU && aa.fecho().feitos == 0 && aa.fecho().erro instanceof MorteInjetada,
                "a thread morreu com o lote aberto: o lote volta como MORREU, com a causa (" + aa.fecho() + ")");
        v.sim(fimDe(f, c) == Andamento.Fim.SOBROU && fimDe(f, d) == Andamento.Fim.SOBROU && c.todos(0, 3, 0) && d.todos(0, 3, 0),
                "sem thread viva, os outros lotes voltam como SOBROU");
        v.sim(dt < 2L * SEG, "a barreira viu a morte na hora, sem esperar a fatia de 5 s (" + seg(dt) + ")");
        v.sim(f.mortos() == 1 && b.mortos() == 1 && f.semNovos() && f.fechados() == 3, "a morte foi contada na fase e na barreira");
        v.sim(esperar(() -> amb.conta("morreu com o lote 1 aberto") == 1, 5000L) && amb.causas.peek() instanceof MorteInjetada,
                "a morte foi ao log com a causa");
        v.igual("morto", b.estados(), "estado do trabalhador");
        b.fechar();
        v.sim(!b.abrir() && b.motivoDaRecusa().contains("morta"), "com thread de regiao morta nenhuma fase nova abre");
        fim(b);
    }

    static void morreUmaDeTres() throws Throwable {
        final Amb amb = new Amb();
        Barreira b = nova(3, amb, FATIA_LONGA);
        Lote x = lote(1, 5, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 4) {
                    amb.matar = Thread.currentThread();
                }
            }
        });
        List<Lote> ls = new ArrayList<Lote>();
        ls.add(x);
        for (int k = 0; k < 8; k++) {
            ls.add(lote(10 + k, 50, 50 - k, girador(200)));
        }
        b.abrir();
        Fase f = b.rodar(ls);
        Andamento ax = andamento(f, x);
        v.sim(ax.fecho().fim == Andamento.Fim.MORREU && ax.fecho().feitos == 5 && x.todos(0, 5, 1),
                "morte depois do ultimo item, antes de fechar: MORREU com os 5 itens feitos (" + ax.fecho() + ")");
        boolean inteiros = true;
        int completos = 0;
        for (int k = 1; k < ls.size(); k++) {
            Lote l = ls.get(k);
            Andamento.Fecho fe = andamento(f, l).fecho();
            boolean completo = fe.fim == Andamento.Fim.COMPLETO && fe.feitos == l.n && l.todos(0, l.n, 1);
            boolean sobrou = fe.fim == Andamento.Fim.SOBROU && fe.feitos == 0 && l.todos(0, l.n, 0);
            inteiros &= completo || sobrou;
            completos += completo ? 1 : 0;
        }
        v.sim(inteiros, "os outros lotes ou rodaram inteiros ou sobraram inteiros (" + completos + " completos de 8)");
        v.sim(f.mortos() == 1 && f.fechados() == ls.size() && b.estados().contains("morto"), "uma morte contada; a barreira passou");
        b.fechar();
        v.sim(!b.abrir(), "nenhuma fase nova depois da morte");
        v.sim(b.encerrar(5L * SEG).isEmpty(), "encerrar com uma thread morta e duas vivas: nenhuma sobra");
    }

    static void pedidoBasico() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(3, amb, FATIA_LONGA);
        final Thread servidor = Thread.currentThread();
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch comecaram = new CountDownLatch(2);
        final AtomicInteger vezesOp = new AtomicInteger();
        final AtomicReference<Object> resposta = new AtomicReference<Object>();
        final List<Lote> todos = new ArrayList<Lote>();
        final List<Lote> comecadosNaOp = Collections.synchronizedList(new ArrayList<Lote>());
        final boolean[] opEmCurso = new boolean[1];
        final boolean[] vaiPedir = new boolean[1];
        Item longo = new Item() {
            @Override
            public void rodar(Lote l, int i) {
                l.dentro = true;
                if (i == 0) {
                    comecaram.countDown();
                }
                if (i == 1) {
                    // fica dentro do item ate quem pede estar para pedir: assim, mesmo com a maquina carregada, este
                    // lote esta no meio quando a violacao acontece, e os tres lotes pequenos ainda nao foram pegos
                    if (!esperar(() -> {
                        synchronized (vaiPedir) {
                            return vaiPedir[0];
                        }
                    }, 10000L)) {
                        probs.add("o lote " + l.chave + " nao viu o pedido chegar");
                    }
                }
                synchronized (opEmCurso) {
                    if (opEmCurso[0]) {
                        probs.add("item do lote " + l.chave + " comecou com a operacao em curso");
                    }
                }
                gastar(i, 20000);
                l.dentro = false;
            }
        };
        final Lote la = lote(5, 4, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i != 1) {
                    return;
                }
                comecaram.await();
                l.pedindo = true;
                synchronized (vaiPedir) {
                    vaiPedir[0] = true;
                }
                Object r = b.pedir(new Operacao<Object>() {
                    @Override
                    public Object rodar() {
                        synchronized (opEmCurso) {
                            opEmCurso[0] = true;
                        }
                        vezesOp.incrementAndGet();
                        if (Thread.currentThread() != servidor) {
                            probs.add("a operacao rodou em " + Thread.currentThread().getName());
                        }
                        if (b.papel() != Barreira.ATENDENDO) {
                            probs.add("papel durante a operacao: " + b.papel());
                        }
                        if (b.estados().contains("rodando")) {
                            probs.add("trabalhador rodando durante a operacao: " + b.estados());
                        }
                        for (Lote o : todos) {
                            if (o.dentro && !o.pedindo) {
                                probs.add("lote " + o.chave + " dentro de um item durante a operacao");
                            }
                            if (o.ultimo >= 0) {
                                comecadosNaOp.add(o);
                            }
                        }
                        dormir(30L);
                        synchronized (opEmCurso) {
                            opEmCurso[0] = false;
                        }
                        return "resposta";
                    }
                }, null, null);
                l.pedindo = false;
                resposta.set(r);
            }
        });
        Lote lb = lote(6, 4000, 90, longo);
        Lote lc = lote(7, 4000, 80, longo);
        todos.addAll(Arrays.asList(la, lb, lc, lote(8, 3, 3, null), lote(9, 3, 2, null), lote(10, 3, 1, null)));
        b.abrir();
        Fase f = b.rodar(todos);
        v.igual(1, vezesOp.get(), "a operacao rodou uma vez");
        v.igual("resposta", resposta.get(), "o resultado voltou para o trabalhador que pediu");
        v.sim(probs.isEmpty(), "a operacao rodou na thread do servidor, com papel ATENDENDO e nenhum outro trabalhador rodando " + probs);
        v.sim(fimDe(f, la) == Andamento.Fim.COMPLETO && la.todos(0, 4, 1), "quem pediu terminou o item e o lote depois da resposta");
        boolean regra = true;
        for (Lote l : todos) {
            Andamento.Fecho fe = andamento(f, l).fecho();
            if (comecadosNaOp.contains(l)) {
                regra &= fe.fim == Andamento.Fim.COMPLETO && l.todos(0, l.n, 1);
            } else {
                regra &= fe.fim == Andamento.Fim.SOBROU && fe.feitos == 0 && l.todos(0, l.n, 0);
            }
        }
        v.sim(regra && comecadosNaOp.size() == 3,
                "depois da primeira violacao nenhum lote novo comecou: os " + comecadosNaOp.size() + " ja comecados terminaram, os outros SOBROU");
        List<Pedido> ps = f.pedidos();
        v.sim(ps.size() == 1 && b.pedidos() == 1 && ps.get(0).chave == 5 && ps.get(0).item == 1 && ps.get(0).lote == andamento(f, la)
                && ps.get(0).descricao.equals("falso(5:1)") && ps.get(0).thread.startsWith(Paralelo.PREFIXO_THREAD_REGIAO),
                "a violacao ficou registrada: lote, item, descricao e thread");
        v.sim(ps.size() == 1 && pilhaTem(ps.get(0).pilha, "TesteNucleoCasos") && pilhaTem(ps.get(0).pilha, "Barreira.pedir"),
                "com a pilha de quem pediu");
        v.sim(f.semNovos(), "a fase parou de entregar lotes");
        b.fechar();
        fim(b);
    }

    static void pedidoExcecao() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final IOException lancada = new IOException("excecao verificada da operacao");
        final AtomicReference<Throwable> pega = new AtomicReference<Throwable>();
        final Operacao<Void> op = new Operacao<Void>() {
            @Override
            public Void rodar() throws IOException {
                throw lancada;
            }
        };
        Lote pegaELote = lote(1, 3, 10, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 1) {
                    try {
                        b.pedir(op, null, null);
                    } catch (Throwable t) {
                        pega.set(t);
                    }
                }
            }
        });
        b.abrir();
        Fase f = b.rodar(Collections.singletonList(pegaELote));
        b.fechar();
        v.sim(pega.get() == lancada, "a excecao da operacao (ate verificada) e relancada em quem pediu: o mesmo objeto");
        v.sim(fimDe(f, pegaELote) == Andamento.Fim.COMPLETO, "se o item trata a excecao, o lote segue");
        Lote naoPega = lote(2, 3, 10, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 1) {
                    b.pedir(op, null, null);
                }
            }
        });
        b.abrir();
        Fase f2 = b.rodar(Collections.singletonList(naoPega));
        b.fechar();
        Andamento.Fecho fe = andamento(f2, naoPega).fecho();
        v.sim(fe.fim == Andamento.Fim.ERRO && fe.erro == lancada && fe.feitos == 1, "se o item nao trata, vira erro do item (" + fe + ")");
        fim(b);
    }

    static void doisPedidos() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final List<Integer> ordem = Collections.synchronizedList(new ArrayList<Integer>());
        final boolean[] voltou = new boolean[2];
        final Object olho = new Object();
        final CountDownLatch o10Comecou = new CountDownLatch(1);
        // o lote 20 pede PRIMEIRO (mas so depois de o lote 10 ter comecado: senao, com a maquina carregada, o 10 nem seria
        // pego depois da primeira violacao); o lote 10 so pede depois de ver o pedido do 20 na fila. A ordem de atendimento
        // tem de ser 10 e depois 20: a escolha e pela chave, feita depois de todos pararem.
        Lote l20 = lote(20, 2, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 0) {
                    o10Comecou.await();
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() {
                            ordem.add(20);
                            // o 10 ja foi respondido, mas tem de continuar parado enquanto esta operacao roda
                            dormir(30L);
                            synchronized (olho) {
                                if (voltou[1]) {
                                    probs.add("o lote 10 voltou a rodar com o pedido do 20 em atendimento");
                                }
                            }
                            return null;
                        }
                    }, null, null);
                    synchronized (olho) {
                        voltou[0] = true;
                    }
                }
            }
        });
        Lote l10 = lote(10, 2, 90, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 0) {
                    o10Comecou.countDown();
                    if (!esperar(() -> b.pedidosNaFila() == 1, 10000L)) {
                        probs.add("o pedido do lote 20 nao chegou a fila");
                    }
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() {
                            ordem.add(10);
                            synchronized (olho) {
                                if (voltou[0]) {
                                    probs.add("o lote 20 voltou antes de ser atendido");
                                }
                            }
                            if (!b.estados().equals("pedindo,pedindo")) {
                                probs.add("durante a operacao do 10 os dois deviam estar pedindo: " + b.estados());
                            }
                            dormir(20L);
                            return null;
                        }
                    }, null, null);
                    synchronized (olho) {
                        voltou[1] = true;
                    }
                }
            }
        });
        b.abrir();
        Fase f = b.rodar(Arrays.asList(l20, l10));
        b.fechar();
        v.igual(Arrays.asList(10, 20), ordem, "dois pedidos juntos: um por vez, o do lote de menor chave primeiro (mesmo tendo chegado depois)");
        v.sim(probs.isEmpty(), "o outro ficou parado no comeco do proprio pedido " + probs);
        List<Pedido> ps = f.pedidos();
        v.sim(ps.size() == 2 && ps.get(0).chave == 20 && ps.get(1).chave == 10 && b.pedidos() == 2, "os dois registrados, na ordem de chegada");
        v.sim(fimDe(f, l20) == Andamento.Fim.COMPLETO && fimDe(f, l10) == Andamento.Fim.COMPLETO && l20.todos(0, 2, 1) && l10.todos(0, 2, 1),
                "os dois lotes terminaram depois das respostas");
        fim(b);
    }

    static void aninhado() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final Thread servidor = Thread.currentThread();
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final AtomicInteger resultado = new AtomicInteger();
        Lote l = lote(1, 2, 0, new Item() {
            @Override
            public void rodar(Lote lo, int i) {
                if (i == 0) {
                    resultado.set(b.pedir(new Operacao<Integer>() {
                        @Override
                        public Integer rodar() {
                            // a carga de um chunk passa por varias guardas, umas dentro das outras
                            if (b.papel() != Barreira.ATENDENDO) {
                                probs.add("papel no primeiro nivel: " + b.papel());
                            }
                            int r = b.pedir(new Operacao<Integer>() {
                                @Override
                                public Integer rodar() {
                                    if (Thread.currentThread() != servidor || b.papel() != Barreira.ATENDENDO) {
                                        probs.add("segundo nivel fora da thread do servidor ou sem ATENDENDO");
                                    }
                                    return b.pedir(new Operacao<Integer>() {
                                        @Override
                                        public Integer rodar() {
                                            if (Thread.currentThread() != servidor) {
                                                probs.add("terceiro nivel fora da thread do servidor");
                                            }
                                            return 3;
                                        }
                                    }, new Object(), new long[] {1L}) + 1;
                                }
                            }, null, null);
                            if (b.portao(null) != Barreira.PORTAO_DIRETO) {
                                probs.add("o portao segurou a thread do servidor");
                            }
                            return r + 1;
                        }
                    }, null, null));
                }
            }
        });
        b.abrir();
        Fase f = b.rodar(Collections.singletonList(l));
        b.fechar();
        v.igual(5, resultado.get(), "pedido aninhado em tres niveis devolve o resultado");
        v.sim(probs.isEmpty(), "os niveis de dentro rodaram direto na thread do servidor " + probs);
        v.sim(b.pedidos() == 1 && f.pedidos().size() == 1 && b.pedidosAninhados() == 2,
                "a violacao conta uma vez por pedido (1), os aninhados so passam (2)");
        v.sim(fimDe(f, l) == Andamento.Fim.COMPLETO && !andamento(f, l).sujo(), "o pedido aninhado nao suja nada por conta propria");
        fim(b);
    }

    /** Agendador falso com o padrao do Skript (Task.callSync): quem nao e a thread principal pede a ela e espera. */
    private static final class AgendadorFalso {
        final Thread principal;
        final ConcurrentLinkedQueue<Callable<?>> fila = new ConcurrentLinkedQueue<Callable<?>>();

        AgendadorFalso(Thread principal) {
            this.principal = principal;
        }

        <T> T callSync(Callable<T> c, long prazoMs) throws Exception {
            if (Thread.currentThread() == principal) {
                return c.call();
            }
            // a fila so e atendida no batimento da thread principal, que nao roda durante a barreira
            fila.add(c);
            Thread.sleep(prazoMs);
            throw new TimeoutException("travaria para sempre: a thread principal esta na barreira");
        }
    }

    static void soNaThreadDoServidor() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final AgendadorFalso ag = new AgendadorFalso(Thread.currentThread());
        final AtomicReference<Throwable> controle = new AtomicReference<Throwable>();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ag.callSync(() -> 7, 200L);
                } catch (Throwable e) {
                    controle.set(e);
                }
            }
        });
        t.start();
        t.join();
        v.sim(controle.get() instanceof TimeoutException, "controle: fora da thread principal a operacao nao termina");
        final AtomicInteger r = new AtomicInteger();
        Lote l = lote(1, 1, 0, new Item() {
            @Override
            public void rodar(Lote lo, int i) {
                r.set(b.pedir(new Operacao<Integer>() {
                    @Override
                    public Integer rodar() throws Exception {
                        return ag.callSync(() -> 7, 5000L);
                    }
                }, null, null));
            }
        });
        b.abrir();
        long t0 = System.nanoTime();
        Fase f = b.rodar(Collections.singletonList(l));
        long dt = System.nanoTime() - t0;
        b.fechar();
        v.sim(r.get() == 7 && dt < 2L * SEG && fimDe(f, l) == Andamento.Fim.COMPLETO,
                "a operacao que so termina na thread do servidor termina, e na hora (" + seg(dt) + ")");
        fim(b);
    }

    static void esperaLonga() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA);
        final long sonoMs = COMPLETO ? 12000L : 350L;
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch dormindo = new CountDownLatch(1);
        final long[] fimDoSono = new long[1];
        final long[] inicioDaOp = new long[1];
        final Object olho = new Object();
        Lote lento = lote(2, 2, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 0) {
                    dormindo.countDown();
                    try {
                        Thread.sleep(sonoMs);
                    } catch (InterruptedException e) {
                        probs.add("o item lento foi interrompido");
                    }
                    synchronized (olho) {
                        fimDoSono[0] = System.nanoTime();
                    }
                }
            }
        });
        Lote pede = lote(1, 2, 90, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 0) {
                    dormindo.await();
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() {
                            synchronized (olho) {
                                inicioDaOp[0] = System.nanoTime();
                                if (fimDoSono[0] == 0L) {
                                    probs.add("a operacao comecou com o outro trabalhador ainda dentro do item");
                                }
                            }
                            return null;
                        }
                    }, null, null);
                }
            }
        });
        b.abrir();
        Fase f = b.rodar(Arrays.asList(lento, pede));
        b.fechar();
        v.sim(probs.isEmpty() && inicioDaOp[0] != 0L && inicioDaOp[0] >= fimDoSono[0],
                "a espera passou de varias fatias e a operacao so rodou depois de o outro trabalhador parar " + probs);
        v.sim(f.despejos() >= 2, "a cada fatia vencida um despejo: " + f.despejos());
        String d = amb.linhaComTodos("barreira: fase", "1 pedido(s) na fila", " pedindo, lote=1 item 0", " rodando, lote=2 item 0",
                "java.lang.Thread.sleep");
        v.sim(d != null, "o despejo mostra o pedido na fila, quem pede e quem atrasa (com a pilha)");
        String a = amb.linhaCom("nenhum prazo libera a operacao");
        v.sim(a != null && a.contains("pedido de Hoshiestar Regiao #") && a.contains("Thread do servidor") && a.contains("Barreira.rodar"),
                "quem pede tambem avisa a cada fatia, com a pilha da thread do servidor");
        v.sim(fimDe(f, lento) == Andamento.Fim.COMPLETO && fimDe(f, pede) == Andamento.Fim.COMPLETO, "os dois lotes terminaram");
        fim(b);
    }

    static void suja() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch bNoItem3 = new CountDownLatch(1);
        final CountDownLatch soltarB = new CountDownLatch(1);
        final boolean[] opRodou = new boolean[1];
        final Lote lb = lote(2, 50, 90, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 3) {
                    bNoItem3.countDown();
                    soltarB.await();
                }
            }
        });
        Lote la = lote(1, 3, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 1) {
                    bNoItem3.await();
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() {
                            synchronized (opRodou) {
                                opRodou[0] = true;
                            }
                            return null;
                        }
                    }, null, new long[] {2L, 3L, 999L});
                }
            }
        });
        Lote ld = lote(3, 4, 1, null);
        Thread aux = iniciar("solta o lote 2", new Caso() {
            @Override
            public void rodar() {
                if (!esperar(() -> b.pedidosNaFila() == 1, 10000L)) {
                    probs.add("o pedido nao chegou a fila");
                }
                dormir(40L);
                synchronized (opRodou) {
                    if (opRodou[0]) {
                        probs.add("a operacao rodou com o lote 2 ainda dentro do item 3");
                    }
                }
                soltarB.countDown();
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Arrays.asList(la, lb, ld));
        juntar(aux);
        Andamento ab = andamento(f, lb);
        Andamento ad = andamento(f, ld);
        Andamento aa = andamento(f, la);
        v.sim(probs.isEmpty() && opRodou[0], "a operacao esperou o lote 2 sair do item " + probs);
        v.sim(ab.fecho().fim == Andamento.Fim.SUJO && ab.fecho().feitos == 4 && ab.sujo() && lb.todos(0, 4, 1) && lb.todos(4, 50, 0),
                "o lote sujo parou no fim do item corrente: 4 de 50 (" + ab.fecho() + ")");
        v.sim(ad.fecho().fim == Andamento.Fim.SOBROU && ad.sujo() && ld.todos(0, 4, 0), "o lote sujo que ninguem tinha pego volta inteiro, marcado sujo");
        v.sim(aa.fecho().fim == Andamento.Fim.COMPLETO && !aa.sujo() && la.todos(0, 3, 1), "o lote de quem pediu nao foi sujo e terminou");
        v.sim(Arrays.equals(new long[] {2L, 3L, 999L}, f.pedidos().get(0).sujou()), "o pedido guarda as chaves que sujou (chave que nao existe e ignorada)");
        // a juncao falsa: o resto roda em serie, na ordem da chave
        for (Andamento a : f.porChave()) {
            Andamento.Fim fi = a.fecho().fim;
            if (fi == Andamento.Fim.SUJO || fi == Andamento.Fim.SOBROU) {
                Lote l = (Lote) a.trabalho;
                l.emSerie = true;
                for (int i = a.fecho().feitos; i < l.n; i++) {
                    l.ticar(i);
                }
            }
        }
        v.sim(lb.todos(0, 50, 1) && ld.todos(0, 4, 1) && !lb.foraDeOrdem && !ld.foraDeOrdem,
                "com o resto rodado em serie na juncao, cada item rodou exatamente uma vez e em ordem");
        b.fechar();
        fim(b);
    }

    static void semMonitor() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(3, amb, FATIA_LONGA);
        final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        v.sim(mx.isObjectMonitorUsageSupported() && mx.isSynchronizerUsageSupported(), "a JVM informa monitores e travas por thread");
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch comecaram = new CountDownLatch(2);
        final int[] monitoresNoControle = {-1};
        final Object x = new Object();
        final long[] ids = new long[3];
        for (int i = 0; i < 3; i++) {
            ids[i] = b.thread(i).getId();
        }
        final Operacao<Void> confere = new Operacao<Void>() {
            @Override
            public Void rodar() {
                // a thread do servidor comeca a operacao quando os outros ja se declararam parados (com a trava); o
                // estacionamento de verdade vem um instante depois. Espera esse instante antes de olhar.
                esperar(() -> {
                    for (ThreadInfo ti : mx.getThreadInfo(ids)) {
                        if (ti != null && ti.getThreadState() != Thread.State.WAITING && ti.getThreadState() != Thread.State.TIMED_WAITING) {
                            return false;
                        }
                    }
                    return true;
                }, 5000L);
                ThreadInfo[] tis = mx.getThreadInfo(ids, true, true);
                int vistos = 0;
                for (ThreadInfo ti : tis) {
                    if (ti == null) {
                        continue;
                    }
                    vistos++;
                    if (ti.getLockedMonitors().length != 0 || ti.getLockedSynchronizers().length != 0) {
                        probs.add(ti.getThreadName() + " segura monitor ou trava durante a operacao");
                    }
                    if (ti.getThreadState() != Thread.State.WAITING && ti.getThreadState() != Thread.State.TIMED_WAITING) {
                        probs.add(ti.getThreadName() + " nao esta esperando: " + ti.getThreadState());
                    }
                }
                if (vistos != 3) {
                    probs.add("ThreadMXBean viu " + vistos + " threads de regiao");
                }
                return null;
            }
        };
        Item longo = new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 0) {
                    comecaram.countDown();
                }
                gastar(i, 20000);
            }
        };
        Lote pede = lote(1, 2, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 0) {
                    comecaram.await();
                    b.pedir(confere, null, null);
                }
            }
        });
        b.abrir();
        b.rodar(Arrays.asList(pede, lote(2, 3000, 90, longo), lote(3, 3000, 80, longo)));
        b.fechar();
        v.sim(probs.isEmpty(), "ThreadMXBean: quem pede e quem esta parado no ponto seguro esperam sem monitor e sem trava " + probs);
        // controle: se o item pedisse com um monitor na mao, a mesma consulta mostraria
        Lote pedeComMonitor = lote(1, 1, 0, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                final long id = Thread.currentThread().getId();
                synchronized (x) {
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() {
                            monitoresNoControle[0] = mx.getThreadInfo(new long[] {id}, true, true)[0].getLockedMonitors().length;
                            return null;
                        }
                    }, null, null);
                }
            }
        });
        b.abrir();
        b.rodar(Collections.singletonList(pedeComMonitor));
        b.fechar();
        v.igual(1, monitoresNoControle[0], "controle: um pedido feito com um monitor na mao aparece na mesma consulta");
        fim(b);
    }

    static void portaoSimples(final boolean comMonitor) throws Throwable {
        String s = comMonitor ? "com o monitor, sem pedido: " : "sem monitor: ";
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch noItem = new CountDownLatch(1);
        final CountDownLatch segura = new CountDownLatch(1);
        final Object m = new Object();
        final int[] r = {-1, -1};
        final boolean[] voltou = new boolean[1];
        final long seguraMs = COMPLETO ? 5500L : 250L;
        Lote l = lote(1, 2, 0, new Item() {
            @Override
            public void rodar(Lote lo, int i) throws Throwable {
                if (i == 0) {
                    noItem.countDown();
                    segura.await();
                }
            }
        });
        Thread estranha = iniciar("thread estranha", new Caso() {
            @Override
            public void rodar() throws Throwable {
                noItem.await();
                r[0] = b.papel();
                if (comMonitor) {
                    synchronized (m) {
                        r[1] = b.portao(m);
                    }
                } else {
                    r[1] = b.portao(null);
                }
                synchronized (voltou) {
                    voltou[0] = true;
                }
            }
        }, probs);
        Thread aux = iniciar("solta o lote", new Caso() {
            @Override
            public void rodar() {
                if (!esperar(() -> b.esperandoNoPortao() == 1, 10000L)) {
                    probs.add("a thread estranha nao chegou ao portao");
                }
                dormir(seguraMs);
                synchronized (voltou) {
                    if (voltou[0]) {
                        probs.add("a thread estranha passou com a fase rodando");
                    }
                }
                segura.countDown();
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Collections.singletonList(l));
        juntar(aux);
        juntar(estranha);
        v.sim(probs.isEmpty(), s + "a thread estranha ficou no portao enquanto a fase rodava " + probs);
        v.igual(Barreira.ESTRANHA, r[0], s + "papel da thread estranha durante a fase");
        v.igual(Barreira.PORTAO_ESPEROU, r[1], s + "passou quando a barreira passou");
        v.sim(b.esperasNoPortao() == 1 && b.passagensPeloMonitor() == 0 && b.esperandoNoPortao() == 0, s + "a espera foi contada");
        String aviso = amb.linhaCom("portao: thread \"thread estranha\" esperando");
        v.sim(aviso != null && aviso.contains("nenhum prazo a libera") && aviso.contains("Barreira.portao") && aviso.contains("TesteNucleoCasos")
                && aviso.contains("segura o monitor indicado") == comMonitor, s + "a fatia vencida avisa no log, com a pilha de quem chegou");
        v.sim(fimDe(f, l) == Andamento.Fim.COMPLETO, s + "o lote terminou");
        b.fechar();
        v.sim(b.abrir(), s + "a fase seguinte abre");
        Fase f2 = b.rodar(Collections.singletonList(lote(1, 2, 0, null)));
        v.sim(f2.fechados() == 1, s + "e roda (a confirmacao do portao foi dada)");
        b.fechar();
        fim(b);
    }

    /**
     * {@code opSincroniza}: a operacao toma o monitor ela mesma (como o evento sincrono do gerenciador de plugins). Com
     * false, so a declaracao do pedido diz que ela precisa do monitor: a thread do servidor tem de toma-lo mesmo assim,
     * senao os trabalhadores voltariam a rodar com a thread estranha ainda escrevendo.
     */
    static void portaoComMonitorEPedido(final boolean opSincroniza) throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(3, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final List<String> ordem = Collections.synchronizedList(new ArrayList<String>());
        final Object m = new Object();
        final CountDownLatch comecou = new CountDownLatch(1);
        final int[] r = {-1};
        final Lote longo = lote(3, 30000, 50, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                l.dentro = true;
                if (i == 0) {
                    comecou.countDown();
                }
                gastar(i, 20000);
                l.dentro = false;
            }
        });
        // a thread estranha segura o monitor M (o do gerenciador de plugins) e chega ao portao com a fase rodando
        Thread estranha = iniciar("thread estranha com monitor", new Caso() {
            @Override
            public void rodar() throws Throwable {
                comecou.await();
                synchronized (m) {
                    r[0] = b.portao(m);
                    // a escrita da thread estranha, ainda com o monitor
                    ordem.add("escrita");
                    if (b.estado() != Fase.Estado.RODANDO) {
                        probs.add("a fase nao estava rodando na escrita: " + b.estado());
                    }
                    if (b.estados().contains("rodando")) {
                        probs.add("trabalhador rodando durante a escrita da thread estranha: " + b.estados());
                    }
                    dormir(40L);
                    if (longo.dentro) {
                        probs.add("o lote longo ticou durante a escrita da thread estranha");
                    }
                    if (b.estados().contains("rodando")) {
                        probs.add("trabalhador voltou a rodar com a thread estranha ainda no monitor: " + b.estados());
                    }
                }
            }
        }, probs);
        // o trabalhador pede uma operacao que precisa do mesmo monitor (um evento sincrono)
        Lote pede = lote(1, 2, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 0) {
                    if (!esperar(() -> b.esperandoNoPortao() == 1, 10000L)) {
                        probs.add("a thread estranha nao chegou ao portao");
                    }
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() {
                            if (opSincroniza) {
                                synchronized (m) {
                                    ordem.add("operacao");
                                }
                            } else {
                                ordem.add("operacao");
                            }
                            return null;
                        }
                    }, m, null);
                }
            }
        });
        b.abrir();
        Fase f = b.rodar(Arrays.asList(pede, longo));
        juntar(estranha);
        b.fechar();
        v.igual(Arrays.asList("escrita", "operacao"), ordem, "sem deadlock: a escrita da thread estranha foi atendida antes da operacao");
        v.igual(Barreira.PORTAO_MONITOR, r[0], "a thread estranha passou pela excecao do monitor");
        v.sim(probs.isEmpty(), "com todos os trabalhadores parados ate ela soltar o monitor " + probs);
        v.sim(b.passagensPeloMonitor() == 1 && b.esperasNoPortao() == 1, "contadores do portao");
        v.sim(fimDe(f, pede) == Andamento.Fim.COMPLETO, "quem pediu terminou");
        fim(b);
    }

    /** A trava interna da barreira, por reflexao: so para montar a intercalacao de {@link #portaoMonitorJaUsado}. */
    private static ReentrantLock travaDe(Barreira b) throws Exception {
        Field c = Barreira.class.getDeclaredField("trava");
        c.setAccessible(true);
        return (ReentrantLock) c.get(b);
    }

    /**
     * Revisao do passo 5, achado I2a. A excecao do monitor so vale enquanto a thread do servidor ainda nao entrou no
     * monitor. A intercalacao montada: a operacao com monitor termina, a thread do servidor sai do monitor e, antes de
     * ela retomar a trava do nucleo, uma thread estranha toma o monitor e chega ao portao. Ela tem de ESPERAR a barreira:
     * se passasse "pela excecao", escreveria com os trabalhadores voltando a ticar.
     *
     * <p>Para a thread estranha chegar a trava antes da thread do servidor, o teste a faz tomar a trava do nucleo (por
     * reflexao) enquanto a operacao ainda roda; a trava e reentrante, entao o {@code portao} dela entra direto, com a
     * thread do servidor ja na fila.
     */
    static void portaoMonitorJaUsado() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final ReentrantLock trava = travaDe(b);
        final Thread servidor = Thread.currentThread();
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final Object m = new Object();
        final CountDownLatch longoComecou = new CountDownLatch(1);
        final CountDownLatch opDentro = new CountDownLatch(1);
        final CountDownLatch travaTomada = new CountDownLatch(1);
        final AtomicInteger inicios = new AtomicInteger();
        final int[] r = {-1};
        final Lote longo = lote(2, 2000, 50, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                inicios.incrementAndGet();
                if (i == 0) {
                    longoComecou.countDown();
                }
                gastar(i, 20000);
            }
        });
        Lote pede = lote(1, 2, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 0) {
                    longoComecou.await();
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() throws Throwable {
                            if (!Thread.holdsLock(m)) {
                                probs.add("a operacao devia rodar com o monitor declarado na mao");
                            }
                            opDentro.countDown();
                            // a operacao so termina com a trava do nucleo na mao da thread estranha
                            if (!travaTomada.await(10L, TimeUnit.SECONDS)) {
                                probs.add("a thread estranha nao tomou a trava");
                            }
                            return null;
                        }
                    }, m, null);
                }
            }
        });
        Thread estranha = iniciar("thread estranha que chega depois", new Caso() {
            @Override
            public void rodar() throws Throwable {
                opDentro.await();
                boolean comATrava = true;
                trava.lock();
                try {
                    travaTomada.countDown();
                    // a thread do servidor terminou a operacao, saiu do monitor e esta na fila da trava
                    if (!esperar(() -> trava.hasQueuedThread(servidor), 10000L)) {
                        probs.add("a thread do servidor nao chegou a fila da trava depois da operacao");
                    }
                    synchronized (m) {
                        final int antes = inicios.get();
                        r[0] = b.portao(m);
                        if (r[0] == Barreira.PORTAO_MONITOR) {
                            // passou "pela excecao": a escrita dela, ainda com o monitor, corre contra os trabalhadores
                            trava.unlock();
                            comATrava = false;
                            if (esperar(() -> inicios.get() != antes, 2000L)) {
                                probs.add("passou pela excecao do monitor e os trabalhadores voltaram a ticar com ela ainda no monitor");
                            }
                        }
                    }
                } finally {
                    if (comATrava) {
                        trava.unlock();
                    }
                }
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Arrays.asList(pede, longo));
        juntar(estranha);
        b.fechar();
        v.igual(Barreira.PORTAO_ESPEROU, r[0], "thread estranha que toma o monitor depois de a thread do servidor ter entrado e saido dele nao passa pela excecao: espera a barreira");
        v.sim(probs.isEmpty(), "e nenhum trabalhador ticou junto com a escrita dela " + probs);
        v.sim(b.passagensPeloMonitor() == 0 && b.esperasNoPortao() == 1, "contadores do portao: nenhuma passagem pelo monitor, uma espera");
        v.sim(fimDe(f, pede) == Andamento.Fim.COMPLETO && fimDe(f, longo) == Andamento.Fim.COMPLETO && longo.todos(0, 2000, 1),
                "a fase terminou inteira: quem pediu e o outro lote completos");
        fim(b);
    }

    static void portaoOutroMonitor() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final Object m = new Object();
        final Object outro = new Object();
        final CountDownLatch comecou = new CountDownLatch(1);
        final CountDownLatch terceiroTemM = new CountDownLatch(1);
        final CountDownLatch soltarM = new CountDownLatch(1);
        final CountDownLatch pediu = new CountDownLatch(1);
        final int[] r = {-1};
        final boolean[] voltou = new boolean[1];
        final boolean[] opRodou = new boolean[1];
        // a thread estranha segura OUTRO monitor; um terceiro segura por um tempo o monitor M que o pedido declara, para
        // a thread do servidor ficar parada na entrada de M com a excecao armada para M
        Thread estranha = iniciar("thread estranha com outro monitor", new Caso() {
            @Override
            public void rodar() throws Throwable {
                comecou.await();
                synchronized (outro) {
                    r[0] = b.portao(outro);
                }
                synchronized (voltou) {
                    voltou[0] = true;
                }
            }
        }, probs);
        Thread terceiro = iniciar("terceiro com o monitor do pedido", new Caso() {
            @Override
            public void rodar() throws Throwable {
                synchronized (m) {
                    terceiroTemM.countDown();
                    soltarM.await();
                }
            }
        }, probs);
        Thread aux = iniciar("solta o monitor do pedido", new Caso() {
            @Override
            public void rodar() throws Throwable {
                pediu.await();
                if (!esperar(() -> b.pedidosNaFila() == 0, 10000L)) {
                    probs.add("o pedido nao saiu da fila");
                }
                dormir(80L);
                synchronized (voltou) {
                    if (voltou[0]) {
                        probs.add("a thread estranha passou com OUTRO monitor enquanto a excecao estava armada");
                    }
                }
                if (b.esperandoNoPortao() != 1 || opRodou[0]) {
                    probs.add("esperava a thread estranha no portao e a operacao parada no monitor: portao=" + b.esperandoNoPortao());
                }
                soltarM.countDown();
            }
        }, probs);
        Lote pede = lote(1, 2, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 0) {
                    comecou.countDown();
                    terceiroTemM.await();
                    if (!esperar(() -> b.esperandoNoPortao() == 1, 10000L)) {
                        probs.add("a thread estranha nao chegou ao portao");
                    }
                    pediu.countDown();
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() {
                            synchronized (voltou) {
                                if (voltou[0]) {
                                    probs.add("a thread estranha passou com outro monitor");
                                }
                            }
                            opRodou[0] = true;
                            return null;
                        }
                    }, m, null);
                }
            }
        });
        b.abrir();
        b.rodar(Collections.singletonList(pede));
        juntar(aux);
        juntar(terceiro);
        juntar(estranha);
        b.fechar();
        v.sim(probs.isEmpty() && opRodou[0], "pedido que precisa de OUTRO monitor nao libera a thread estranha " + probs);
        v.sim(r[0] == Barreira.PORTAO_ESPEROU && b.passagensPeloMonitor() == 0, "ela so passou com a barreira");
        fim(b);
    }

    /**
     * Quem esperava no portao quando a barreira passou tem de seguir ANTES de a fase seguinte ser publicada. 64 threads
     * estranhas sao liberadas de uma vez (levam um tempo para acordar) e a thread do servidor publica a fase seguinte em
     * seguida. Cada thread estranha olha, logo que sai do portao, qual e a fase publicada: tem de ser ainda a fase em que
     * ela esperou. So a ultima a sair pode, por corrida do proprio teste, ja ver a seguinte; por isso a conta e "quase
     * todas" (3 em cada 4), e nao "todas". Sem a confirmacao, quase nenhuma ve a fase certa.
     */
    static void portaoConfirmacao() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final int estranhas = 64;
        final int rodadas = COMPLETO ? 300 : 30;
        final java.util.concurrent.Semaphore vez = new java.util.concurrent.Semaphore(0);
        final AtomicInteger esperaram = new AtomicInteger();
        final AtomicInteger naFaseCerta = new AtomicInteger();
        final AtomicInteger aindaNoPortao = new AtomicInteger();
        List<Thread> ts = new ArrayList<Thread>();
        for (int k = 0; k < estranhas; k++) {
            ts.add(iniciar("estranha " + k, new Caso() {
                @Override
                public void rodar() throws Throwable {
                    for (int rd = 0; rd < rodadas; rd++) {
                        vez.acquire();
                        if (!esperar(() -> b.estado() == Fase.Estado.RODANDO, 20000L)) {
                            probs.add("a fase nao comecou");
                        }
                        // a fase fica rodando ate as 64 chegarem ao portao: este numero e o da fase em que esta thread espera
                        long minha = b.faseAtual().numero;
                        if (b.portao(null) == Barreira.PORTAO_ESPEROU) {
                            esperaram.incrementAndGet();
                        }
                        if (b.faseAtual().numero == minha) {
                            naFaseCerta.incrementAndGet();
                        }
                    }
                }
            }, probs));
        }
        Item seguraAteTodosChegarem = new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (!esperar(() -> b.esperandoNoPortao() == estranhas, 20000L)) {
                    probs.add("so " + b.esperandoNoPortao() + " threads estranhas chegaram ao portao");
                }
            }
        };
        Item contaQuemFicou = new Item() {
            @Override
            public void rodar(Lote l, int i) {
                aindaNoPortao.addAndGet(b.esperandoNoPortao());
            }
        };
        for (int rd = 0; rd < rodadas; rd++) {
            vez.release(estranhas);
            b.abrir();
            b.rodar(Collections.singletonList(lote(1, 1, 0, seguraAteTodosChegarem)));
            b.fechar();
            // nenhuma thread estranha tem vez agora: quem aparecer no portao e resto da fase anterior
            b.abrir();
            b.rodar(Collections.singletonList(lote(2, 1, 0, contaQuemFicou)));
            b.fechar();
        }
        for (Thread t : ts) {
            t.join(20000L);
        }
        int total = estranhas * rodadas;
        v.sim(probs.isEmpty() && esperaram.get() == total,
                rodadas + " rodadas com " + estranhas + " threads estranhas no portao: todas esperaram a barreira e passaram " + probs);
        v.sim(naFaseCerta.get() >= total * 3 / 4, "a fase seguinte so foi publicada depois de elas seguirem: " + naFaseCerta.get() + " de " + total
                + " sairam do portao ainda com a fase em que esperaram publicada");
        v.igual(0, aindaNoPortao.get(), "e o primeiro item da fase seguinte nao viu nenhuma no portao");
        fim(b);
    }

    static void portaoFasesColadas() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final int esperas = COMPLETO ? 20000 : 1000;
        final int[] conta = new int[4];
        final boolean[] acabou = new boolean[1];
        // a thread estranha bate no portao ate ter ESPERADO a barreira 'esperas' vezes; a thread do servidor publica uma
        // fase logo depois da outra. Sem a confirmacao, a thread estranha poderia acordar ja com a fase seguinte rodando,
        // vez apos vez, e nunca passar.
        Thread estranha = iniciar("thread estranha em laco", new Caso() {
            @Override
            public void rodar() {
                while (conta[Barreira.PORTAO_ESPEROU] < esperas) {
                    conta[b.portao(null)]++;
                }
                synchronized (acabou) {
                    acabou[0] = true;
                }
            }
        }, probs);
        long fases = 0;
        for (;;) {
            synchronized (acabou) {
                if (acabou[0]) {
                    break;
                }
            }
            b.abrir();
            b.rodar(Arrays.asList(lote(1, 3, 0, girador(300)), lote(2, 3, 0, girador(300))));
            b.fechar();
            fases++;
        }
        juntar(estranha);
        v.sim(probs.isEmpty() && conta[Barreira.PORTAO_ESPEROU] == esperas && conta[Barreira.PORTAO_MONITOR] == 0 && conta[Barreira.PORTAO_ABERTO] == 0,
                "com fases coladas uma na outra, a thread estranha esperou a barreira " + esperas + " vezes e passou em todas (" + fases
                        + " fases; " + conta[Barreira.PORTAO_DIRETO] + " chegadas fora da fase)");
        v.sim(b.esperasNoPortao() == esperas, "todas as esperas foram contadas (o numero de fases depende so de quanto processador a thread estranha recebeu)");
        fim(b);
    }

    static void abortarLivre() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(3, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch comecaram = new CountDownLatch(3);
        final boolean[] voltou = new boolean[1];
        final AtomicInteger depoisDeVoltar = new AtomicInteger();
        final AtomicInteger comAbortoLigado = new AtomicInteger();
        final AtomicReference<List<Preso>> presos = new AtomicReference<List<Preso>>();
        final long[] dt = new long[1];
        Item longo = new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 0) {
                    comecaram.countDown();
                }
                if (b.abortada()) {
                    comAbortoLigado.incrementAndGet();
                }
                synchronized (voltou) {
                    if (voltou[0]) {
                        depoisDeVoltar.incrementAndGet();
                    }
                }
                gastar(i, 20000);
            }
        };
        List<Lote> ls = new ArrayList<Lote>();
        for (int k = 0; k < 3; k++) {
            ls.add(lote(k, 300000, 100, longo));
        }
        for (int k = 3; k < 6; k++) {
            ls.add(lote(k, 3, 1, null));
        }
        Thread gancho = iniciar("gancho de parada", new Caso() {
            @Override
            public void rodar() throws Throwable {
                comecaram.await();
                dormir(20L);
                long t0 = System.nanoTime();
                List<Preso> p = b.abortar(5L * SEG);
                synchronized (voltou) {
                    voltou[0] = true;
                }
                dt[0] = System.nanoTime() - t0;
                presos.set(p);
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(ls);
        juntar(gancho);
        v.sim(probs.isEmpty() && presos.get() != null && presos.get().isEmpty(), "abortar vindo de outra thread: todos pararam, ninguem preso " + probs);
        v.sim(dt[0] < 2L * SEG, "voltou bem antes do prazo de 5 s (" + seg(dt[0]) + ")");
        v.igual(0, depoisDeVoltar.get(), "depois de abortar voltar, nenhum item comecou");
        v.sim(comAbortoLigado.get() <= 3, "com o aborto ja ligado, no maximo o item que cada trabalhador ja ia comecar (" + comAbortoLigado.get() + ")");
        boolean ok = f.abortada() && f.semNovos() && b.abortada();
        for (int k = 0; k < 3; k++) {
            Andamento.Fecho fe = andamento(f, ls.get(k)).fecho();
            ok &= fe.fim == Andamento.Fim.ABORTADO && fe.feitos > 0 && fe.feitos < 300000 && ls.get(k).ultimo + 1 == fe.feitos;
        }
        for (int k = 3; k < 6; k++) {
            ok &= fimDe(f, ls.get(k)) == Andamento.Fim.SOBROU && ls.get(k).todos(0, 3, 0);
        }
        v.sim(ok, "os lotes em curso fecharam ABORTADO entre dois itens; os outros SOBROU; a barreira passou");
        b.fechar();
        v.sim(!b.abrir() && b.motivoDaRecusa().contains("abortada"), "nenhuma fase nova abre depois do aborto");
        v.sim(b.abortar(SEG).isEmpty(), "abortar de novo, sem fase: volta vazio");
        fim(b);
    }

    static void abortarPreso() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(3, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch presoNoItem = new CountDownLatch(1);
        final CountDownLatch soltar = new CountDownLatch(1);
        final AtomicReference<List<Preso>> presos = new AtomicReference<List<Preso>>();
        final AtomicReference<String> nomeDoPreso = new AtomicReference<String>();
        final long[] dt = new long[1];
        final int[] rPortao = {-1, -1};
        final long prazoMs = 300L;
        final Lote lp = lote(1, 6, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 2) {
                    nomeDoPreso.set(Thread.currentThread().getName());
                    presoNoItem.countDown();
                    soltar.await();
                }
            }
        });
        final CountDownLatch outrosComecaram = new CountDownLatch(2);
        Item longo = new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 0) {
                    outrosComecaram.countDown();
                }
                gastar(i, 20000);
            }
        };
        final Lote lq = lote(2, 300000, 90, longo);
        final Lote lr = lote(3, 300000, 80, longo);
        Thread estranha = iniciar("thread estranha", new Caso() {
            @Override
            public void rodar() throws Throwable {
                presoNoItem.await();
                rPortao[0] = b.portao(null);
            }
        }, probs);
        Thread gancho = iniciar("gancho de parada", new Caso() {
            @Override
            public void rodar() throws Throwable {
                presoNoItem.await();
                outrosComecaram.await();
                if (!esperar(() -> b.esperandoNoPortao() == 1, 10000L)) {
                    probs.add("a thread estranha nao chegou ao portao");
                }
                long t0 = System.nanoTime();
                List<Preso> p = b.abortar(prazoMs * MS);
                dt[0] = System.nanoTime() - t0;
                presos.set(p);
                // com um trabalhador preso a fase continua RODANDO, mas o portao ja abriu (a parada segue e salva)
                if (b.estado() != Fase.Estado.RODANDO) {
                    probs.add("a fase devia continuar rodando com o trabalhador preso: " + b.estado());
                }
                rPortao[1] = b.portao(null);
                dormir(50L);
                String[] es = b.estados().split(",");
                Arrays.sort(es);
                if (!Arrays.equals(new String[] {"ocioso", "ocioso", "rodando"}, es)) {
                    probs.add("devia haver dois trabalhadores ociosos e o preso rodando: " + b.estados());
                }
                soltar.countDown();
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Arrays.asList(lp, lq, lr));
        juntar(gancho);
        juntar(estranha);
        List<Preso> ps = presos.get();
        v.sim(probs.isEmpty(), "sem problema nas threads auxiliares " + probs);
        v.sim(dt[0] >= prazoMs * MS && dt[0] < (prazoMs + 1500L) * MS, "com um trabalhador preso, abortar volta no prazo de " + prazoMs + " ms (" + dt[0] / MS + " ms)");
        v.sim(ps != null && ps.size() == 1, "e diz quem ficou: 1 preso");
        if (ps != null && ps.size() == 1) {
            Preso p = ps.get(0);
            v.sim(p.thread.equals(nomeDoPreso.get()) && p.estado.equals("rodando") && p.temLote && p.chave == 1 && p.item == 2 && p.tamanho == 6
                    && p.descricao.equals("falso(1:2)"), "thread, lote e item do preso: " + p);
            v.sim(pilhaTem(p.pilha, "TesteNucleoCasos") && pilhaTem(p.pilha, "CountDownLatch.await"), "com a pilha dele");
        }
        v.sim(amb.conta("conservacao suspeita") == 1, "o log lista os lotes com trabalhador vivo (conservacao suspeita)");
        v.sim(rPortao[0] == Barreira.PORTAO_ABERTO && rPortao[1] == Barreira.PORTAO_ABERTO,
                "o portao acaba junto com a fase abortada: quem esperava passou, quem chega passa");
        Andamento.Fecho fp = andamento(f, lp).fecho();
        v.sim(fp.fim == Andamento.Fim.ABORTADO && fp.feitos == 3 && lp.todos(0, 3, 1) && lp.todos(3, 6, 0),
                "solto, o preso terminou o item em que estava e nao pegou outro (" + fp + ")");
        v.sim(fimDe(f, lq) == Andamento.Fim.ABORTADO && fimDe(f, lr) == Andamento.Fim.ABORTADO && f.fechados() == 3 && f.abortada(),
                "os outros pararam entre dois itens; a barreira passou depois de o preso sair");
        b.fechar();
        fim(b);
    }

    static void abortarComPedido() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final Thread servidor = Thread.currentThread();
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch noItem = new CountDownLatch(1);
        final CountDownLatch soltar = new CountDownLatch(1);
        final AtomicReference<List<Preso>> presos = new AtomicReference<List<Preso>>();
        final boolean[] opRodou = new boolean[1];
        Lote lw = lote(1, 3, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 0) {
                    noItem.countDown();
                    soltar.await();
                }
            }
        });
        Lote la = lote(2, 3, 90, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 0) {
                    noItem.await();
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() {
                            if (Thread.currentThread() != servidor) {
                                probs.add("operacao fora da thread do servidor");
                            }
                            if (!b.abortada()) {
                                probs.add("a operacao devia rodar depois do aborto");
                            }
                            opRodou[0] = true;
                            return null;
                        }
                    }, null, null);
                }
            }
        });
        Thread gancho = iniciar("gancho de parada", new Caso() {
            @Override
            public void rodar() {
                if (!esperar(() -> b.pedidosNaFila() == 1, 10000L)) {
                    probs.add("o pedido nao chegou a fila");
                }
                presos.set(b.abortar(200L * MS));
                soltar.countDown();
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Arrays.asList(lw, la));
        juntar(gancho);
        List<Preso> ps = presos.get();
        boolean doisPresos = ps != null && ps.size() == 2;
        if (doisPresos) {
            String e = ps.get(0).estado + "," + ps.get(1).estado;
            doisPresos = e.equals("rodando,pedindo") || e.equals("pedindo,rodando");
        }
        v.sim(doisPresos, "abortar com um item preso e um pedido na fila: os dois aparecem como presos (rodando e pedindo): " + ps);
        v.sim(probs.isEmpty() && opRodou[0], "o pedido pendente ainda foi atendido depois do aborto, na thread do servidor " + probs);
        Andamento.Fecho fa = andamento(f, la).fecho();
        Andamento.Fecho fw = andamento(f, lw).fecho();
        v.sim(fa.fim == Andamento.Fim.ABORTADO && fa.feitos == 1 && la.todos(0, 1, 1) && la.todos(1, 3, 0),
                "quem pediu terminou o item do pedido e nao pegou outro (" + fa + ")");
        v.sim(fw.fim == Andamento.Fim.ABORTADO && fw.feitos == 1, "o outro tambem parou depois do item em que estava (" + fw + ")");
        b.fechar();
        fim(b);
    }

    /**
     * Revisao do passo 5, achado I2b. Abortar chega no meio de uma operacao delegada, com o outro trabalhador PARADO na
     * pausa (entre dois itens). O aviso do aborto acorda o parado; ele tem de ir direto para o fim do lote, sem ticar mais
     * nenhum item: a operacao ainda esta rodando.
     */
    static void abortarComParadoNaPausa() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final Thread servidor = Thread.currentThread();
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch outroComecou = new CountDownLatch(1);
        final CountDownLatch opDentro = new CountDownLatch(1);
        final AtomicInteger inicios = new AtomicInteger();
        final AtomicReference<List<Preso>> presos = new AtomicReference<List<Preso>>();
        final int[] noComecoDaOp = {-1};
        final boolean[] opTerminou = new boolean[1];
        final boolean[] opTerminouNaVolta = new boolean[1];
        final int[] iniciosNaVolta = {-1};
        final long[] dt = new long[1];
        final Lote lw = lote(2, 300000, 90, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                l.dentro = true;
                inicios.incrementAndGet();
                if (i == 0) {
                    outroComecou.countDown();
                }
                gastar(i, 20000);
                l.dentro = false;
            }
        });
        Lote la = lote(1, 3, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 0) {
                    outroComecou.await();
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() {
                            if (Thread.currentThread() != servidor) {
                                probs.add("operacao fora da thread do servidor");
                            }
                            String[] es = b.estados().split(",");
                            Arrays.sort(es);
                            if (!Arrays.equals(new String[] {"parado", "pedindo"}, es)) {
                                probs.add("a operacao devia comecar com o outro trabalhador parado na pausa: " + b.estados());
                            }
                            final int antes = inicios.get();
                            noComecoDaOp[0] = antes;
                            opDentro.countDown();
                            if (!esperar(() -> b.abortada(), 10000L)) {
                                probs.add("abortar nao chegou durante a operacao");
                            }
                            // o aviso do aborto ja acordou o parado: se ele fosse ticar, seria agora
                            boolean dentro = false;
                            long limite = System.nanoTime() + 150L * MS;
                            while (System.nanoTime() < limite) {
                                dentro |= lw.dentro;
                                dormir(1L);
                            }
                            if (dentro || inicios.get() != antes) {
                                probs.add("o trabalhador parado na pausa ticou " + (inicios.get() - antes)
                                        + " item(ns) junto com a operacao delegada, depois do aborto");
                            }
                            synchronized (opTerminou) {
                                opTerminou[0] = true;
                            }
                            return null;
                        }
                    }, null, null);
                }
            }
        });
        Thread gancho = iniciar("gancho de parada", new Caso() {
            @Override
            public void rodar() throws Throwable {
                opDentro.await();
                long t0 = System.nanoTime();
                List<Preso> p = b.abortar(5L * SEG);
                dt[0] = System.nanoTime() - t0;
                synchronized (opTerminou) {
                    opTerminouNaVolta[0] = opTerminou[0];
                }
                iniciosNaVolta[0] = inicios.get();
                presos.set(p);
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Arrays.asList(la, lw));
        juntar(gancho);
        v.sim(probs.isEmpty(), "abortar no meio de uma operacao delegada: o trabalhador parado na pausa nao tica mais nenhum item " + probs);
        v.sim(presos.get() != null && presos.get().isEmpty() && opTerminouNaVolta[0] && dt[0] < 3L * SEG,
                "abortar esperou a operacao e o resto do item de quem pediu, e voltou sem presos (" + dt[0] / MS + " ms): " + presos.get());
        v.sim(iniciosNaVolta[0] == noComecoDaOp[0] && inicios.get() == noComecoDaOp[0],
                "nenhum item do outro lote comecou depois do comeco da operacao (" + noComecoDaOp[0] + " antes, " + inicios.get() + " no fim)");
        Andamento.Fecho fw = andamento(f, lw).fecho();
        Andamento.Fecho fa = andamento(f, la).fecho();
        v.sim(fw.fim == Andamento.Fim.ABORTADO && fw.feitos == noComecoDaOp[0] && lw.todos(0, fw.feitos, 1) && lw.todos(fw.feitos, fw.feitos + 2, 0),
                "o lote do parado fechou ABORTADO no item em que tinha parado (" + fw + ")");
        v.sim(fa.fim == Andamento.Fim.ABORTADO && fa.feitos == 1 && la.todos(0, 1, 1) && la.todos(1, 3, 0),
                "quem pediu terminou o item do pedido e nao pegou outro (" + fa + ")");
        b.fechar();
        fim(b);
    }

    /**
     * Revisao do passo 5, achado I2c. Abortar chega com um trabalhador no meio de um pedido (PEDINDO) e os outros ociosos.
     * A operacao leva 150 ms: cabe no prazo de 10 s, entao abortar tem de esperar a operacao e o resto do item e voltar
     * vazio. Voltar na hora, com o trabalhador na lista, faria a parada salvar com os dois ainda correndo.
     */
    static void abortarEsperaQuemPede() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch opDentro = new CountDownLatch(1);
        final AtomicReference<List<Preso>> presos = new AtomicReference<List<Preso>>();
        // [0] a operacao terminou; [1] o item do pedido terminou; so com o monitor de "feito"
        final boolean[] feito = new boolean[2];
        final boolean[] naVolta = new boolean[2];
        final String[] estadosAntes = new String[1];
        final long[] dt = new long[1];
        final long opMs = 150L;
        Lote la = lote(1, 3, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 0) {
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() {
                            opDentro.countDown();
                            if (!esperar(() -> b.abortada(), 10000L)) {
                                probs.add("abortar nao chegou durante a operacao");
                            }
                            dormir(opMs);
                            synchronized (feito) {
                                feito[0] = true;
                            }
                            return null;
                        }
                    }, null, null);
                    // o resto do item, depois do pedido
                    gastar(i, 200000);
                    synchronized (feito) {
                        feito[1] = true;
                    }
                }
            }
        });
        Thread gancho = iniciar("gancho de parada", new Caso() {
            @Override
            public void rodar() throws Throwable {
                opDentro.await();
                estadosAntes[0] = b.estados();
                long t0 = System.nanoTime();
                List<Preso> p = b.abortar(10L * SEG);
                dt[0] = System.nanoTime() - t0;
                synchronized (feito) {
                    naVolta[0] = feito[0];
                    naVolta[1] = feito[1];
                }
                presos.set(p);
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Collections.singletonList(la));
        juntar(gancho);
        String[] es = String.valueOf(estadosAntes[0]).split(",");
        Arrays.sort(es);
        v.sim(probs.isEmpty() && Arrays.equals(new String[] {"ocioso", "pedindo"}, es),
                "na chamada de abortar: um trabalhador no meio de um pedido, o outro ocioso (" + estadosAntes[0] + ") " + probs);
        v.sim(presos.get() != null && presos.get().isEmpty(), "abortar(10 s) com um pedido de " + opMs + " ms em atendimento volta sem presos: " + presos.get());
        v.sim(naVolta[0] && naVolta[1], "e so volta depois de a operacao (" + naVolta[0] + ") e o resto do item de quem pediu (" + naVolta[1] + ") terminarem");
        v.sim(dt[0] >= opMs * MS && dt[0] < 5L * SEG, "esperou a operacao, bem antes do prazo (" + dt[0] / MS + " ms)");
        Andamento.Fecho fa = andamento(f, la).fecho();
        v.sim(fa.fim == Andamento.Fim.ABORTADO && fa.feitos == 1 && la.todos(0, 1, 1) && la.todos(1, 3, 0),
                "quem pediu terminou o item do pedido e nao pegou outro (" + fa + ")");
        b.fechar();
        fim(b);
    }

    static void abortarDoServidor() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final AtomicReference<List<Preso>> presos = new AtomicReference<List<Preso>>();
        final long[] dt = new long[1];
        Lote l = lote(1, 3, 0, new Item() {
            @Override
            public void rodar(Lote lo, int i) {
                if (i == 0) {
                    b.pedir(new Operacao<Void>() {
                        @Override
                        public Void rodar() {
                            long t0 = System.nanoTime();
                            presos.set(b.abortar(10L * SEG));
                            dt[0] = System.nanoTime() - t0;
                            return null;
                        }
                    }, null, null);
                }
            }
        });
        b.abrir();
        Fase f = b.rodar(Collections.singletonList(l));
        b.fechar();
        List<Preso> ps = presos.get();
        v.sim(dt[0] < 2L * SEG, "abortar chamado pela propria thread do servidor (dentro de uma operacao) nao espera por si mesma (" + seg(dt[0]) + ")");
        v.sim(ps != null && ps.size() == 1 && ps.get(0).estado.equals("pedindo") && ps.get(0).chave == 1, "e devolve quem estava no meio de um item: " + ps);
        v.sim(f.abortada() && fimDe(f, l) == Andamento.Fim.ABORTADO && andamento(f, l).fecho().feitos == 1, "a fase termina abortada");
        fim(b);
    }

    static void abortarEmMontando() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final AtomicReference<List<Preso>> presos = new AtomicReference<List<Preso>>();
        b.abrir();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                presos.set(b.abortar(5L * SEG));
            }
        });
        t.start();
        t.join();
        v.sim(presos.get() != null && presos.get().isEmpty(), "abortar com a fase em MONTANDO volta na hora, vazio");
        Lote l1 = lote(1, 3, 0, null);
        Lote l2 = lote(2, 3, 0, null);
        Fase f = b.rodar(Arrays.asList(l1, l2));
        v.sim(b.estado() == Fase.Estado.JUNCAO && f.abortada() && fimDe(f, l1) == Andamento.Fim.SOBROU && fimDe(f, l2) == Andamento.Fim.SOBROU
                && l1.todos(0, 3, 0) && l2.todos(0, 3, 0) && b.acordados() == 0,
                "rodar depois do aborto nao publica nada: os lotes voltam como SOBROU e ninguem e acordado");
        b.fechar();
        fim(b);
    }

    static void encerrar() throws Throwable {
        Amb amb = new Amb();
        Barreira b = nova(3, amb, FATIA_LONGA);
        for (int k = 0; k < 20; k++) {
            b.abrir();
            b.rodar(Arrays.asList(lote(1, 5, 0, null), lote(2, 5, 0, null), lote(3, 5, 0, null), lote(4, 5, 0, null)));
            b.fechar();
        }
        b.abrir();
        long t0 = System.nanoTime();
        List<Preso> sobra = b.encerrar(5L * SEG);
        long dt = System.nanoTime() - t0;
        boolean mortas = true;
        for (int i = 0; i < 3; i++) {
            mortas &= !b.thread(i).isAlive();
        }
        v.sim(sobra.isEmpty() && mortas && dt < 2L * SEG, "encerrar: as tres threads de regiao acabaram, bem antes do prazo (" + seg(dt) + ")");
        v.igual("fim,fim,fim", b.estados(), "estado dos trabalhadores");
        v.sim(b.estado() == Fase.Estado.FECHADA && b.encerrada(), "a fase esquecida em MONTANDO foi descartada sem esperar ninguem");
        v.sim(b.mortos() == 0, "encerrar nao conta como morte");
        v.sim(!b.abrir() && b.motivoDaRecusa().contains("encerrada"), "depois de encerrar nenhuma fase abre");
        v.sim(b.portao(null) == Barreira.PORTAO_DIRETO && b.encerrar(SEG).isEmpty(), "portao livre; encerrar de novo nao faz nada");
    }

    /** {@code interrompido}: quem chama chega com o sinal de interrupcao ligado; o prazo continua valendo (nem menos, nem mais). */
    static void encerrarPreso(boolean interrompido) throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(3, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch noItem = new CountDownLatch(1);
        final CountDownLatch soltar = new CountDownLatch(1);
        final AtomicReference<Fase> fase = new AtomicReference<Fase>();
        final Lote lp = lote(1, 4, 100, new Item() {
            @Override
            public void rodar(Lote l, int i) throws Throwable {
                if (i == 1) {
                    noItem.countDown();
                    soltar.await();
                }
            }
        });
        // aqui a thread do servidor e uma thread auxiliar: e a thread do teste que chama encerrar "de fora"
        Thread servidor = iniciar("servidor falso", new Caso() {
            @Override
            public void rodar() {
                b.abrir();
                fase.set(b.rodar(Arrays.asList(lp, lote(2, 5, 1, null), lote(3, 5, 1, null))));
            }
        }, probs);
        noItem.await();
        if (interrompido) {
            Thread.currentThread().interrupt();
        }
        long t0 = System.nanoTime();
        List<Preso> sobra = b.encerrar(300L * MS);
        long dt = System.nanoTime() - t0;
        if (interrompido) {
            v.sim(Thread.interrupted(), "encerrar interrompido sai com o sinal de interrupcao reposto");
        }
        v.sim(dt >= 300L * MS && dt < 1800L * MS, "encerrar com um trabalhador preso volta no prazo de 300 ms (" + dt / MS + " ms)");
        v.sim(sobra.size() == 1 && sobra.get(0).temLote && sobra.get(0).chave == 1 && sobra.get(0).item == 1, "e diz qual thread nao acabou: " + sobra);
        int vivas = 0;
        for (int i = 0; i < 3; i++) {
            vivas += b.thread(i).isAlive() ? 1 : 0;
        }
        v.igual(1, vivas, "as outras duas threads acabaram");
        soltar.countDown();
        juntar(servidor);
        v.sim(esperar(() -> !b.thread(0).isAlive() && !b.thread(1).isAlive() && !b.thread(2).isAlive(), 5000L),
                "solto, o preso fecha o lote e a thread acaba");
        Fase f = fase.get();
        v.sim(probs.isEmpty() && f != null && fimDe(f, lp) == Andamento.Fim.ABORTADO && andamento(f, lp).fecho().feitos == 2 && f.fechados() == 3,
                "a barreira da thread do servidor passou; o lote do preso fechou ABORTADO depois do item em que estava " + probs);
    }

    /**
     * Revisao do passo 5, achado I1: quem chama encerrar com o sinal de interrupcao ja ligado nao larga a espera. Uma thread
     * de regiao ociosa leva alguns microssegundos para acordar e acabar; se o join for largado, ela aparece como "presa".
     * Nao da para segurar uma thread ociosa de proposito, entao o caso repete 200 vezes (o defeito aparecia em 4 de cada 10).
     */
    static void encerrarInterrompidoOcioso() throws Throwable {
        final int vezes = 200;
        int falsos = 0;
        int vivas = 0;
        int semSinal = 0;
        int logs = 0;
        long pior = 0L;
        String exemplo = "";
        for (int k = 0; k < vezes; k++) {
            Amb amb = new Amb();
            Barreira b = nova(1, amb, FATIA_LONGA);
            b.abrir();
            b.rodar(Collections.singletonList(lote(1, 2, 0, null)));
            b.fechar();
            Thread.currentThread().interrupt();
            long t0 = System.nanoTime();
            List<Preso> sobra = b.encerrar(5L * SEG);
            pior = Math.max(pior, System.nanoTime() - t0);
            boolean viva = b.thread(0).isAlive();
            boolean sinal = Thread.interrupted();
            if (!sobra.isEmpty()) {
                falsos++;
                exemplo = sobra.toString();
            }
            vivas += viva ? 1 : 0;
            semSinal += sinal ? 0 : 1;
            logs += amb.conta("nao acabaram no prazo");
            b.thread(0).join(5000L);
        }
        v.igual(0, falsos, "encerrar(5 s) com interrupcao pendente em quem chama, 1 thread ociosa, " + vezes + " vezes: nunca devolve a thread como presa " + exemplo);
        v.igual(0, vivas, "e a thread de regiao ja acabou quando a chamada volta");
        v.igual(0, logs, "nenhuma linha 'nao acabaram no prazo' no log");
        v.igual(0, semSinal, "o sinal de interrupcao e reposto na saida, todas as vezes");
        v.sim(pior < 2L * SEG, "bem antes do prazo (pior: " + pior / MS + " ms)");
    }

    /**
     * Revisao do passo 5, achado I1, montado: as tres threads estao no meio de um item quando encerrar chega, vindo de uma
     * thread com interrupcao pendente; a thread #1 (a primeira que encerrar espera) demora 300 ms para acabar depois de
     * sair do item. A chamada tem de esperar por ela (esta dentro do prazo) e voltar vazia, com o sinal reposto.
     */
    static void encerrarInterrompidoNoItem() throws Throwable {
        final Amb amb = new Amb();
        final Barreira b = nova(3, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch noItem = new CountDownLatch(3);
        final AtomicReference<Fase> fase = new AtomicReference<Fase>();
        final long atrasoMs = 300L;
        Item espera = new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 0) {
                    noItem.countDown();
                    // o item so termina depois de encerrar chegar: o trabalhador esta no meio de um item na chamada
                    if (!esperar(() -> b.encerrada(), 10000L)) {
                        probs.add("encerrar nao chegou");
                    }
                }
            }
        };
        final Lote l1 = lote(1, 3, 3, espera);
        final Lote l2 = lote(2, 3, 2, espera);
        final Lote l3 = lote(3, 3, 1, espera);
        Thread servidor = iniciar("servidor falso", new Caso() {
            @Override
            public void rodar() {
                b.abrir();
                fase.set(b.rodar(Arrays.asList(l1, l2, l3)));
            }
        }, probs);
        noItem.await();
        // a thread #1 dorme no relogio do fim do lote: fica viva 300 ms depois de sair do item
        amb.atrasoMs = atrasoMs;
        amb.atrasar = b.thread(0);
        Thread.currentThread().interrupt();
        long t0 = System.nanoTime();
        List<Preso> sobra = b.encerrar(5L * SEG);
        long dt = System.nanoTime() - t0;
        int vivas = 0;
        for (int i = 0; i < 3; i++) {
            vivas += b.thread(i).isAlive() ? 1 : 0;
        }
        boolean sinal = Thread.interrupted();
        v.sim(sobra.isEmpty(), "encerrar(5 s) com interrupcao pendente e uma thread que leva " + atrasoMs + " ms para acabar: volta sem presos " + sobra);
        v.igual(0, vivas, "as tres threads de regiao ja acabaram quando a chamada volta");
        v.sim(dt >= atrasoMs * MS && dt < 3L * SEG, "a chamada esperou pela thread atrasada, dentro do prazo (" + dt / MS + " ms)");
        v.sim(sinal, "e sai com o sinal de interrupcao reposto");
        v.igual(0, amb.conta("nao acabaram no prazo"), "nenhuma linha 'nao acabaram no prazo' no log");
        juntar(servidor);
        Fase f = fase.get();
        boolean ok = probs.isEmpty() && f != null && f.fechados() == 3;
        if (f != null) {
            for (Lote l : new Lote[] {l1, l2, l3}) {
                Andamento.Fecho fe = andamento(f, l).fecho();
                ok &= fe.fim == Andamento.Fim.ABORTADO && fe.feitos == 1 && l.todos(0, 1, 1) && l.todos(1, 3, 0);
            }
        }
        v.sim(ok, "os tres lotes fecharam ABORTADO depois do item em que estavam; a barreira da thread do servidor passou " + probs);
    }

    /** Interrompe {@code alvo} a cada 20 microssegundos, mais ou menos, ate {@code parar} ligar. */
    private static Thread tempestade(final Thread alvo, final AtomicBoolean parar) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!parar.get()) {
                    alvo.interrupt();
                    long fim = System.nanoTime() + 20000L;
                    while (System.nanoTime() < fim) {
                        // gira uns 20 microssegundos
                    }
                }
            }
        }, "tempestade de interrupcoes");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** join que nao larga por interrupcao (quem chama pode estar debaixo de uma {@link #tempestade}); devolve se a thread acabou. */
    private static boolean juntarSemLargar(Thread t, long ms) {
        long fim = System.nanoTime() + ms * MS;
        long resta;
        while (t.isAlive() && (resta = fim - System.nanoTime()) > 0L) {
            try {
                t.join(resta / MS + 1L);
            } catch (InterruptedException e) {
                // segue esperando
            }
        }
        return !t.isAlive();
    }

    /**
     * Segunda revisao do passo 5, I3a (o gemeo do I1 dentro de {@code abortar}): quem chama {@code abortar} chega com o
     * sinal de interrupcao ligado (a maquina de parada repoe o sinal em quem pediu a parada) e, com {@code comTempestade},
     * continua sendo interrompido durante a espera. A espera nao pode ser largada: os tres trabalhadores estao no meio de um
     * item que acaba 200 ms depois de o aborto chegar.
     */
    static void abortarInterrompido(final boolean comTempestade) throws Throwable {
        String s = comTempestade ? "com interrupcao pendente e tempestade de interrupcoes: " : "com interrupcao pendente: ";
        Amb amb = new Amb();
        final Barreira b = nova(3, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch noItem = new CountDownLatch(3);
        final AtomicInteger acabaram = new AtomicInteger();
        final AtomicReference<List<Preso>> presos = new AtomicReference<List<Preso>>();
        final long[] dt = new long[1];
        final int[] naVolta = {-1};
        final boolean[] sinal = new boolean[1];
        final long itemMs = 200L;
        Item espera = new Item() {
            @Override
            public void rodar(Lote l, int i) {
                if (i == 0) {
                    noItem.countDown();
                    // o item so termina 200 ms depois de abortar chegar: o trabalhador esta no meio de um item na chamada
                    if (!esperar(() -> b.abortada(), 10000L)) {
                        probs.add("abortar nao chegou");
                    }
                    dormir(itemMs);
                    acabaram.incrementAndGet();
                }
            }
        };
        final Lote l1 = lote(1, 3, 3, espera);
        final Lote l2 = lote(2, 3, 2, espera);
        final Lote l3 = lote(3, 3, 1, espera);
        Thread gancho = iniciar("gancho de parada", new Caso() {
            @Override
            public void rodar() throws Throwable {
                noItem.await();
                Thread eu = Thread.currentThread();
                AtomicBoolean parar = new AtomicBoolean();
                Thread t = comTempestade ? tempestade(eu, parar) : null;
                eu.interrupt();
                long t0 = System.nanoTime();
                List<Preso> p = b.abortar(10L * SEG);
                dt[0] = System.nanoTime() - t0;
                naVolta[0] = acabaram.get();
                if (t == null) {
                    sinal[0] = Thread.interrupted();
                } else {
                    // com a tempestade o sinal ligado nao prova nada; a reposicao e conferida na variante sem tempestade
                    parar.set(true);
                    if (!juntarSemLargar(t, 10000L)) {
                        probs.add("a tempestade nao parou");
                    }
                    Thread.interrupted();
                }
                presos.set(p);
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Arrays.asList(l1, l2, l3));
        juntar(gancho);
        v.sim(probs.isEmpty() && presos.get() != null && presos.get().isEmpty(),
                s + "abortar(10 s) com tres trabalhadores num item de " + itemMs + " ms volta sem presos: " + presos.get() + " " + probs);
        v.igual(3, naVolta[0], s + "quando a chamada volta, os tres itens ja terminaram");
        v.sim(dt[0] >= itemMs * MS && dt[0] < 5L * SEG, s + "a chamada esperou os itens, bem antes do prazo (" + dt[0] / MS + " ms)");
        if (!comTempestade) {
            v.sim(sinal[0], s + "e sai com o sinal de interrupcao reposto");
        }
        v.igual(0, amb.conta("conservacao suspeita"), s + "nenhuma linha 'conservacao suspeita' no log");
        boolean ok = f.fechados() == 3 && f.abortada();
        for (Lote l : new Lote[] {l1, l2, l3}) {
            Andamento.Fecho fe = andamento(f, l).fecho();
            ok &= fe.fim == Andamento.Fim.ABORTADO && fe.feitos == 1 && l.todos(0, 1, 1) && l.todos(1, 3, 0);
        }
        v.sim(ok, s + "os tres lotes fecharam ABORTADO depois do item em que estavam");
        b.fechar();
        fim(b);
    }

    /**
     * Segunda revisao do passo 5, I3b: threads estranhas que chegam ao portao com o sinal de interrupcao ligado (tarefa
     * cancelada com {@code Future.cancel(true)}, {@code shutdownNow} de um executor), metade delas ainda interrompida a cada
     * 20 microssegundos enquanto espera. Com a fase rodando, nenhuma pode passar antes de a barreira passar.
     */
    static void portaoInterrompido() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch noItem = new CountDownLatch(1);
        final CountDownLatch segura = new CountDownLatch(1);
        final AtomicBoolean itemAcabou = new AtomicBoolean();
        final AtomicInteger voltaram = new AtomicInteger();
        final AtomicBoolean parar = new AtomicBoolean();
        final Object m = new Object();
        final int n = 4;
        final int[] res = new int[n];
        final boolean[] cedo = new boolean[n];
        final boolean[] sinal = new boolean[n];
        final int[] visto = {-1, -1};
        final Thread[] xs = new Thread[n];
        Lote l = lote(1, 2, 0, new Item() {
            @Override
            public void rodar(Lote lo, int i) throws Throwable {
                if (i == 0) {
                    noItem.countDown();
                    segura.await();
                    itemAcabou.set(true);
                }
            }
        });
        for (int k = 0; k < n; k++) {
            final int kk = k;
            xs[k] = iniciar("estranha-" + k, new Caso() {
                @Override
                public void rodar() throws Throwable {
                    noItem.await();
                    Thread.currentThread().interrupt();
                    if (kk == 0) {
                        synchronized (m) {
                            res[kk] = b.portao(m);
                        }
                    } else {
                        res[kk] = b.portao(null);
                    }
                    cedo[kk] = !itemAcabou.get();
                    voltaram.incrementAndGet();
                    sinal[kk] = Thread.interrupted();
                }
            }, probs);
        }
        Thread aux = iniciar("solta o lote", new Caso() {
            @Override
            public void rodar() throws Throwable {
                noItem.await();
                esperar(() -> b.esperandoNoPortao() == n || voltaram.get() > 0, 10000L);
                visto[0] = b.esperandoNoPortao();
                // a tempestade bate em duas das que esperam
                List<Thread> ts = new ArrayList<Thread>();
                ts.add(tempestade(xs[0], parar));
                ts.add(tempestade(xs[2], parar));
                dormir(80L);
                visto[1] = b.esperandoNoPortao();
                if (voltaram.get() != 0) {
                    probs.add(voltaram.get() + " thread(s) estranha(s) interrompida(s) passaram pelo portao com a fase rodando");
                }
                segura.countDown();
                for (Thread x : xs) {
                    x.join(10000L);
                }
                parar.set(true);
                for (Thread t : ts) {
                    t.join(10000L);
                }
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Collections.singletonList(l));
        juntar(aux);
        boolean todas = true;
        for (int k = 0; k < n; k++) {
            juntar(xs[k]);
            todas &= res[k] == Barreira.PORTAO_ESPEROU && !cedo[k] && sinal[k];
        }
        v.sim(probs.isEmpty(), n + " threads estranhas com interrupcao pendente (uma com o monitor, duas com tempestade): nenhuma passou com a fase rodando " + probs);
        v.sim(visto[0] == n && visto[1] == n, "as " + n + " ficaram no portao, antes e depois de 80 ms de tempestade (" + visto[0] + ", " + visto[1] + ")");
        v.sim(todas, "todas passaram quando a barreira passou, depois do fim do item e com o sinal reposto: resultados " + Arrays.toString(res)
                + ", antes do fim do item " + Arrays.toString(cedo) + ", sinal " + Arrays.toString(sinal));
        v.sim(fimDe(f, l) == Andamento.Fim.COMPLETO && b.esperandoNoPortao() == 0, "o lote terminou e ninguem ficou no portao");
        b.fechar();
        v.sim(b.abrir(), "a fase seguinte abre");
        Fase f2 = b.rodar(Collections.singletonList(lote(1, 2, 0, null)));
        v.sim(f2.fechados() == 1, "e roda (as " + n + " confirmacoes do portao foram dadas)");
        b.fechar();
        fim(b);
    }

    /**
     * Segunda revisao do passo 5, I3c: {@code abortar} (e {@code encerrar}) so abre o portao DEPOIS de esperar os
     * trabalhadores sairem do item. Tres threads estranhas esperam no portao (uma com o monitor), um trabalhador esta num
     * item que acaba 200 ms depois de o aborto chegar: nenhuma thread estranha passa antes do fim do item.
     */
    static void abortarAbreOPortaoDepois(final boolean encerrando) throws Throwable {
        final String s = encerrando ? "encerrar: " : "abortar: ";
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch noItem = new CountDownLatch(1);
        final AtomicBoolean itemAcabou = new AtomicBoolean();
        final AtomicReference<List<Preso>> presos = new AtomicReference<List<Preso>>();
        final Object m = new Object();
        final int n = 3;
        final int[] res = new int[n];
        final boolean[] cedo = new boolean[n];
        final int[] depois = {-1};
        final boolean[] naVolta = new boolean[1];
        final long[] dt = new long[1];
        final long itemMs = 200L;
        final Lote l = lote(1, 3, 0, new Item() {
            @Override
            public void rodar(Lote lo, int i) {
                if (i == 0) {
                    noItem.countDown();
                    if (!esperar(() -> b.abortada(), 10000L)) {
                        probs.add("o aborto nao chegou");
                    }
                    dormir(itemMs);
                    itemAcabou.set(true);
                }
            }
        });
        Thread[] xs = new Thread[n];
        for (int k = 0; k < n; k++) {
            final int kk = k;
            xs[k] = iniciar("estranha-" + k, new Caso() {
                @Override
                public void rodar() throws Throwable {
                    noItem.await();
                    if (kk == 0) {
                        synchronized (m) {
                            res[kk] = b.portao(m);
                            cedo[kk] = !itemAcabou.get();
                        }
                    } else {
                        res[kk] = b.portao(null);
                        cedo[kk] = !itemAcabou.get();
                    }
                }
            }, probs);
        }
        Thread gancho = iniciar("gancho de parada", new Caso() {
            @Override
            public void rodar() throws Throwable {
                noItem.await();
                if (!esperar(() -> b.esperandoNoPortao() == n, 10000L)) {
                    probs.add("as threads estranhas nao chegaram ao portao");
                }
                long t0 = System.nanoTime();
                List<Preso> p = encerrando ? b.encerrar(5L * SEG) : b.abortar(5L * SEG);
                dt[0] = System.nanoTime() - t0;
                naVolta[0] = itemAcabou.get();
                // quem chega depois de a chamada voltar passa na hora (portao aberto, ou fase ja fora de RODANDO)
                depois[0] = b.portao(null);
                presos.set(p);
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Collections.singletonList(l));
        juntar(gancho);
        boolean nenhumaCedo = true;
        boolean resultados = true;
        for (int k = 0; k < n; k++) {
            juntar(xs[k]);
            nenhumaCedo &= !cedo[k];
            resultados &= res[k] == Barreira.PORTAO_ABERTO || res[k] == Barreira.PORTAO_ESPEROU;
        }
        v.sim(probs.isEmpty() && presos.get() != null && presos.get().isEmpty(), s + "com " + n + " threads estranhas no portao e um item de " + itemMs
                + " ms em curso, volta sem presos: " + presos.get() + " " + probs);
        v.sim(nenhumaCedo, s + "nenhuma thread estranha passou pelo portao antes de o item terminar " + Arrays.toString(cedo));
        v.sim(resultados, s + "todas passaram, com o portao aberto ou com a barreira " + Arrays.toString(res));
        v.sim(naVolta[0] && dt[0] >= itemMs * MS && dt[0] < 3L * SEG, s + "a chamada esperou o item, bem antes do prazo de 5 s (" + dt[0] / MS + " ms)");
        v.sim(depois[0] == Barreira.PORTAO_ABERTO || depois[0] == Barreira.PORTAO_DIRETO, s + "quem chega ao portao depois de a chamada voltar passa na hora (" + depois[0] + ")");
        Andamento.Fecho fe = andamento(f, l).fecho();
        v.sim(fe.fim == Andamento.Fim.ABORTADO && fe.feitos == 1 && l.todos(0, 1, 1) && l.todos(1, 3, 0), s + "o lote fechou ABORTADO depois do item em curso (" + fe + ")");
        b.fechar();
        fim(b);
    }

    /**
     * Segunda revisao do passo 5, M1 (wakeup perdido no aborto): o aborto chega com o unico trabalhador no ULTIMO item do
     * lote. Ele nao passa por nenhum ponto seguro depois: sai do item direto para ocioso ou, com {@code morre}, a thread
     * acaba logo depois do item (o relogio do fim do lote lanca). Nos dois caminhos quem chamou {@code abortar} tem de ser
     * avisado; sem o aviso a chamada so volta no fim do prazo, com todo mundo ja parado.
     */
    static void abortarNoUltimoItem(final boolean morre) throws Throwable {
        final String s = morre ? "thread que morre depois do ultimo item: " : "trabalhador que fica ocioso depois do ultimo item: ";
        final Amb amb = new Amb();
        final Barreira b = nova(1, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final CountDownLatch noItem = new CountDownLatch(1);
        final AtomicReference<List<Preso>> presos = new AtomicReference<List<Preso>>();
        final long[] dt = new long[1];
        final long itemMs = 50L;
        final long prazoMs = 3000L;
        final Lote l = lote(1, 1, 0, new Item() {
            @Override
            public void rodar(Lote lo, int i) {
                noItem.countDown();
                if (!esperar(() -> b.abortada(), 10000L)) {
                    probs.add("abortar nao chegou");
                }
                dormir(itemMs);
            }
        });
        Thread gancho = iniciar("gancho de parada", new Caso() {
            @Override
            public void rodar() throws Throwable {
                noItem.await();
                if (morre) {
                    // a proxima leitura do relogio por essa thread e a do fim do lote, ja fora do item
                    amb.matar = b.thread(0);
                }
                long t0 = System.nanoTime();
                List<Preso> p = b.abortar(prazoMs * MS);
                dt[0] = System.nanoTime() - t0;
                presos.set(p);
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Collections.singletonList(l));
        juntar(gancho);
        v.sim(probs.isEmpty() && presos.get() != null && presos.get().isEmpty(), s + "abortar volta sem presos: " + presos.get() + " " + probs);
        v.sim(dt[0] >= itemMs * MS && dt[0] < prazoMs * MS / 2L,
                s + "abortar(" + prazoMs + " ms) volta quando o item de " + itemMs + " ms termina, sem esperar o prazo (" + dt[0] / MS + " ms)");
        Andamento.Fecho fe = andamento(f, l).fecho();
        if (morre) {
            v.sim(fe.fim == Andamento.Fim.MORREU && fe.feitos == 1 && fe.erro instanceof MorteInjetada && f.mortos() == 1 && l.todos(0, 1, 1),
                    s + "o lote fechou MORREU com o item feito (" + fe + ")");
        } else {
            v.sim(fe.fim == Andamento.Fim.COMPLETO && fe.feitos == 1 && l.todos(0, 1, 1), s + "o lote fechou COMPLETO (" + fe + ")");
        }
        b.fechar();
        fim(b);
    }

    static void interrupcaoDoServidor() throws Throwable {
        Amb amb = new Amb();
        final Barreira b = nova(2, amb, FATIA_LONGA);
        final ConcurrentLinkedQueue<String> probs = new ConcurrentLinkedQueue<String>();
        final Thread servidor = Thread.currentThread();
        final CountDownLatch noItem = new CountDownLatch(1);
        final CountDownLatch soltar = new CountDownLatch(1);
        Lote l = lote(1, 3, 0, new Item() {
            @Override
            public void rodar(Lote lo, int i) throws Throwable {
                if (i == 0) {
                    noItem.countDown();
                    soltar.await();
                }
            }
        });
        Thread aux = iniciar("interrompe o servidor", new Caso() {
            @Override
            public void rodar() throws Throwable {
                noItem.await();
                servidor.interrupt();
                dormir(60L);
                if (b.estado() != Fase.Estado.RODANDO) {
                    probs.add("a barreira largou a espera ao ser interrompida");
                }
                soltar.countDown();
            }
        }, probs);
        b.abrir();
        Fase f = b.rodar(Collections.singletonList(l));
        boolean sinal = Thread.interrupted();
        juntar(aux);
        v.sim(probs.isEmpty() && fimDe(f, l) == Andamento.Fim.COMPLETO, "a thread do servidor interrompida na barreira continua esperando ate o lote fechar " + probs);
        v.sim(sinal, "e sai de rodar com o sinal de interrupcao reposto");
        b.fechar();
        fim(b);
    }
}
