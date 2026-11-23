package hoshiestar.teste.vigia;

import hoshiestar.api.Limitador;
import hoshiestar.api.Relogio;
import hoshiestar.api.ThreadServidor;
import hoshiestar.vigia.LimiteDeRelatorios;
import hoshiestar.vigia.Marcas;
import hoshiestar.vigia.NucleoVigia;
import hoshiestar.vigia.Vigia;
import modfalso.lento.Trabalho;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Pecas dos testes do vigia: thread do servidor falsa (de verdade), contexto, saida e GC falsos. */
final class Apoio {

    private Apoio() {
    }

    /** "Pacote de rede" de teste: tem texto secreto (nunca pode aparecer) e campos numericos. */
    static final class PacoteTeste {
        String texto = "SEGREDO-123";
        char[] letras = {'S', 'E', 'G'};
        Object objeto = "SEGREDO-OBJ";
        int x = 42;
        boolean b = true;
        double d = 1.5;
    }

    static final class EntidadeFalsa {
    }

    static final class JogadorFalso {
        final String nome = "JogadorDeTeste";
    }

    /** Contexto falso (o que no servidor e o ContextoMinecraft). */
    static final class ContextoFalso implements NucleoVigia.Contexto {
        volatile Object pacote = new PacoteTeste();
        volatile Object jogador = new JogadorFalso();

        @Override
        public Object ultimoPacote() {
            return pacote;
        }

        @Override
        public Object jogadorDaRede() {
            return jogador;
        }

        @Override
        public String entidade(Object e, boolean nome) {
            return "EntidadeFalsa id 7, dim 0, pos 1.0 2.0 3.0";
        }

        @Override
        public String tile(Object t) {
            return "TileFalso em 1 2 3, dim 0";
        }

        @Override
        public String jogador(Object p, boolean nome) {
            return nome ? ((JogadorFalso) p).nome : "(nome oculto)";
        }
    }

    /** Relatorio recebido pela saida falsa. */
    static final class Rel {
        final long nanos;
        final long seq;
        final String texto;

        Rel(long nanos, long seq, String texto) {
            this.nanos = nanos;
            this.seq = seq;
            this.texto = texto;
        }
    }

    static final class SaidaFalsa implements NucleoVigia.Saida {
        final List<Rel> rels = Collections.synchronizedList(new ArrayList<Rel>());
        final List<String> linhas = Collections.synchronizedList(new ArrayList<String>());
        final List<String> erros = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void relatorio(long seq, String texto) {
            rels.add(new Rel(System.nanoTime(), seq, texto));
        }

        @Override
        public void linha(String texto) {
            linhas.add(texto);
        }

        @Override
        public void erro(String texto, Throwable t) {
            erros.add(texto + " " + t);
        }
    }

    /** Amostrador real (getStackTrace) que conta as amostras e avisa na primeira. */
    static final class AmostradorContador implements NucleoVigia.Amostrador {
        final AtomicInteger n = new AtomicInteger();
        final CountDownLatch primeira = new CountDownLatch(1);

        @Override
        public StackTraceElement[] pilha(Thread t) {
            StackTraceElement[] p = t.getStackTrace();
            n.incrementAndGet();
            primeira.countDown();
            return p;
        }
    }

    /** GC falso: dois coletores; o teste soma tempo neles. */
    static final class GcFalso implements NucleoVigia.FonteGc {
        volatile long jovem;
        volatile long concorrente;

        @Override
        public int quantos() {
            return 2;
        }

        @Override
        public String nome(int i) {
            return i == 0 ? "G1 Young Generation" : "G1 Concurrent GC";
        }

        @Override
        public long tempoMs(int i) {
            return i == 0 ? jovem : concorrente;
        }

        @Override
        public long coletas(int i) {
            return i == 0 ? jovem / 10 : concorrente / 10;
        }
    }

    /** Um tick lento pedido a thread falsa. */
    static final class TickLento {
        final long ms;
        final long gcJovemMs;
        final long gcConcMs;
        /** Entidade marcada so depois destes ms (-1 = desde o comeco). */
        long entidadeDepoisMs = -1;

        TickLento(long ms, long gcJovemMs, long gcConcMs) {
            this.ms = ms;
            this.gcJovemMs = gcJovemMs;
            this.gcConcMs = gcConcMs;
        }
    }

    /**
     * A "thread do servidor": faz ticks como o MinecraftServer com os patches (Relogio.ticks++,
     * Marcas.inicioTick, trabalho com Marcas.entidade, Marcas.fimTick). Tick normal: ~2 ms de trabalho e
     * espera ate completar 50 ms. Tick lento: tirado da fila.
     */
    static final class ServidorFalso extends Thread {
        final ConcurrentLinkedQueue<TickLento> fila = new ConcurrentLinkedQueue<TickLento>();
        final GcFalso gc;
        volatile boolean fim;
        volatile int lentosFeitos;
        final Object entidade = new EntidadeFalsa();

        ServidorFalso(GcFalso gc) {
            super("Server thread (falsa)");
            this.gc = gc;
            setDaemon(true);
        }

        @Override
        public void run() {
            ThreadServidor.definir(this);
            while (!fim) {
                long t0 = System.nanoTime();
                ++Relogio.ticks;
                Marcas.inicioTick();
                TickLento l = fila.poll();
                Marcas.entidade(entidade);
                if (l != null) {
                    if (gc != null) {
                        gc.jovem += l.gcJovemMs;
                        gc.concorrente += l.gcConcMs;
                    }
                    if (l.entidadeDepoisMs >= 0) {
                        Marcas.entidade(null);
                        Trabalho.metodoLentoDoMod(l.entidadeDepoisMs);
                        Marcas.entidade(entidade);
                        Trabalho.metodoLentoDoMod(l.ms - l.entidadeDepoisMs);
                    } else {
                        Trabalho.metodoLentoDoMod(l.ms);
                    }
                    lentosFeitos++;
                } else {
                    Trabalho.metodoLentoDoMod(2);
                }
                Marcas.entidade(null);
                Marcas.fimTick();
                long resto = 50L - (System.nanoTime() - t0) / 1000000L;
                if (resto > 0) {
                    try {
                        Thread.sleep(resto);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }

        void lento(long ms) {
            fila.add(new TickLento(ms, 0, 0));
        }

        void parar() throws InterruptedException {
            fim = true;
            join(20000);
        }
    }

    static NucleoVigia.Ajustes ajustes(long limiteMs, int amostras, long intervaloMs, long esperaFimMs) {
        NucleoVigia.Ajustes a = new NucleoVigia.Ajustes();
        Marcas.anotarTicksAcimaDe(limiteMs); // como o Vigia.registrar faz com vigia.limite_ms
        a.limiteMs = limiteMs;
        a.amostras = amostras;
        a.intervaloAmostrasMs = intervaloMs;
        a.esperaFimMs = esperaFimMs;
        a.quadros = 40;
        a.pollMs = 100;
        a.esperaPararMs = 800;
        return a;
    }

    static Limitador.Tempo relogio() {
        return new Limitador.Tempo() {
            @Override
            public long nanos() {
                return System.nanoTime();
            }
        };
    }

    static NucleoVigia nucleo(NucleoVigia.Ajustes a, NucleoVigia.Contexto ctx, NucleoVigia.Amostrador am, NucleoVigia.Saida s,
            NucleoVigia.FonteGc gc, NucleoVigia.Donos donos, LimiteDeRelatorios lim) {
        return new NucleoVigia(a, new Vigia.FonteServidor(), ctx, am, s, gc, donos, lim, relogio());
    }

    /** Cria um jar de teste com as entradas dadas (bytes vazios: o indice so le os nomes). */
    static File jar(File pasta, String nome, String... entradas) throws Exception {
        pasta.mkdirs();
        File f = new File(pasta, nome);
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
        try {
            for (String e : entradas) {
                z.putNextEntry(new ZipEntry(e));
                z.write(new byte[] {1, 2, 3});
                z.closeEntry();
            }
        } finally {
            z.close();
        }
        return f;
    }

    static void esperar(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /** Espera ate a condicao ficar verdadeira ou o prazo passar. */
    interface Cond {
        boolean ok();
    }

    static boolean ate(long prazoMs, Cond c) {
        long fim = System.nanoTime() + prazoMs * 1000000L;
        while (System.nanoTime() - fim < 0) {
            if (c.ok()) {
                return true;
            }
            esperar(20);
        }
        return c.ok();
    }
}
