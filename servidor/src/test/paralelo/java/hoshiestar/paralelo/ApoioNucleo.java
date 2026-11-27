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

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/**
 * Apoio dos testes do nucleo de concorrencia (passo 5 do plano do Degrau B): ambiente falso, regiao falsa, vigia de teste
 * travado. Sem Minecraft.
 */
final class ApoioNucleo {

    /** {@code -Dhoshiestar.teste.completo=true}: dose cheia (10^6 ticks por numero de threads, fatias reais de 5 s). */
    static final boolean COMPLETO = Boolean.getBoolean("hoshiestar.teste.completo");

    static final long MS = 1000000L;
    static final long SEG = 1000000000L;

    private ApoioNucleo() {
    }

    /** O erro que o relogio falso lanca para matar uma thread de regiao FORA do catch do item. */
    static final class MorteInjetada extends Error {
        private static final long serialVersionUID = 1L;

        MorteInjetada() {
            super("morte injetada pelo teste");
        }
    }

    /** Ambiente falso: guarda as linhas de log; o relogio pode matar uma thread de regiao (erro no nosso codigo). */
    static final class Amb implements Ambiente {
        final ConcurrentLinkedQueue<String> linhas = new ConcurrentLinkedQueue<String>();
        final ConcurrentLinkedQueue<Throwable> causas = new ConcurrentLinkedQueue<Throwable>();
        /** A proxima chamada de agora() feita por esta thread lanca. */
        volatile Thread matar;
        /** A proxima chamada de agora() feita por qualquer thread de regiao lanca. */
        volatile boolean matarProxima;
        /** As chamadas de agora() feitas por esta thread dormem {@link #atrasoMs} antes de responder (thread de regiao que demora a acabar). */
        volatile Thread atrasar;
        volatile long atrasoMs;

        @Override
        public void log(String linha) {
            linhas.add(linha);
        }

        @Override
        public void erro(String linha, Throwable causa) {
            linhas.add(linha);
            if (causa != null) {
                causas.add(causa);
            }
        }

        @Override
        public long agora() {
            Thread eu = Thread.currentThread();
            if (eu == matar) {
                matar = null;
                throw new MorteInjetada();
            }
            if (matarProxima && eu instanceof ThreadRegiao) {
                matarProxima = false;
                throw new MorteInjetada();
            }
            if (eu == atrasar) {
                long fim = System.nanoTime() + atrasoMs * MS;
                long resta;
                while ((resta = fim - System.nanoTime()) > 0L) {
                    try {
                        Thread.sleep(resta / MS + 1L);
                    } catch (InterruptedException e) {
                        // o nucleo nunca interrompe uma thread de regiao; se acontecer, o atraso continua
                    }
                }
            }
            return System.nanoTime();
        }

        int conta(String trecho) {
            int n = 0;
            for (String l : linhas) {
                if (l.contains(trecho)) {
                    n++;
                }
            }
            return n;
        }

        /** A primeira linha que contem TODOS os trechos, ou null. */
        String linhaComTodos(String... trechos) {
            for (String l : linhas) {
                boolean todos = true;
                for (String t : trechos) {
                    todos &= l.contains(t);
                }
                if (todos) {
                    return l;
                }
            }
            return null;
        }

        /** A primeira linha que contem o trecho, ou null. */
        String linhaCom(String trecho) {
            for (String l : linhas) {
                if (l.contains(trecho)) {
                    return l;
                }
            }
            return null;
        }
    }

    /** O que um item da regiao falsa faz alem de contar. */
    interface Item {
        void rodar(Lote lote, int i) throws Throwable;
    }

    /**
     * Regiao falsa. Os campos sem {@code volatile} sao escritos pela thread que tica e lidos pela thread do teste so
     * depois da barreira: e a propria garantia de visibilidade do nucleo que os torna legiveis.
     */
    static final class Lote implements Trabalho {
        final long chave;
        final int n;
        final long custo;
        final Item item;
        /** Quantas vezes cada item rodou. */
        final int[] vezes;
        /** Indice do ultimo item que comecou. */
        int ultimo = -1;
        boolean foraDeOrdem;
        Thread quem;
        boolean maisDeUmaThread;
        boolean foraDeThreadDeRegiao;
        /** Ligado na juncao falsa (a thread do teste roda o resto em serie): desliga as conferencias de thread. */
        boolean emSerie;
        /** Dentro de um item agora (para os testes de "nunca junto"). */
        volatile boolean dentro;
        /** Dentro de Barreira.pedir agora. */
        volatile boolean pedindo;

        Lote(long chave, int n, long custo, Item item) {
            this.chave = chave;
            this.n = n;
            this.custo = custo;
            this.item = item;
            this.vezes = new int[n];
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
        public void ticar(int i) throws Throwable {
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
            if (item != null) {
                item.rodar(this, i);
            }
        }

        @Override
        public String descrever(int i) {
            return "falso(" + chave + ":" + i + ")";
        }

        /** Todos os itens de {@code de} (inclusive) a {@code ate} (exclusive) rodaram exatamente {@code k} vezes? */
        boolean todos(int de, int ate, int k) {
            for (int i = de; i < ate; i++) {
                if (vezes[i] != k) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Vigia do teste: se o prazo vencer, despeja todas as pilhas e derruba a JVM com codigo 3 (teste travado). */
    static final class Vigia {
        private static volatile long limite = Long.MAX_VALUE;
        private static volatile String oque = "";

        private Vigia() {
        }

        static void ligar() {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (;;) {
                        try {
                            Thread.sleep(200L);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (System.nanoTime() > limite) {
                            System.out.println("  FALHA TESTE TRAVADO: '" + oque + "' passou do prazo. Pilhas:");
                            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                                System.out.println("  thread \"" + e.getKey().getName() + "\" (" + e.getKey().getState() + ")");
                                for (StackTraceElement q : e.getValue()) {
                                    System.out.println("      at " + q);
                                }
                            }
                            System.out.flush();
                            Runtime.getRuntime().halt(3);
                        }
                    }
                }
            }, "vigia do teste");
            t.setDaemon(true);
            t.start();
        }

        /** Da {@code segundos} para o trecho {@code nome} terminar. */
        static void prazo(String nome, long segundos) {
            oque = nome;
            limite = System.nanoTime() + segundos * SEG;
        }
    }

    /** Espera a condicao ficar verdadeira (olhando a cada 1 ms). Devolve false se o prazo vencer. */
    static boolean esperar(BooleanSupplier cond, long ms) {
        long limite = System.nanoTime() + ms * MS;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > limite) {
                return false;
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new IllegalStateException("teste interrompido", e);
        }
    }

    /** Gasta CPU sem dormir (o custo sorteado de um item). */
    static long girar(long semente, int giros) {
        long x = semente;
        for (int k = 0; k < giros; k++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
        }
        return x;
    }

    /** Onde {@link #gastar} joga o resultado, para o JIT nao apagar a conta. */
    static volatile long poco;

    /** Como {@link #girar}, mas com o resultado usado: o tempo gasto e garantido (20000 giros = dezenas de microssegundos). */
    static void gastar(long semente, int giros) {
        poco = girar(semente, giros);
    }

    static String seg(long ns) {
        return (ns / 100000000L) / 10.0 + " s";
    }
}
