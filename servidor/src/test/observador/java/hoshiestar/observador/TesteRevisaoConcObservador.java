// Hoshiestar
package hoshiestar.observador;

import hoshiestar.api.ThreadServidor;
import hoshiestar.teste.Verifica;

import java.io.File;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Testes dos achados da revisao de concorrencia da Etapa 2 (I1 e m1 a m8). Cada verificacao afirma o comportamento
 * correto e falha no codigo de antes da correcao. Uso: java -cp ... hoshiestar.observador.TesteRevisaoConcObservador <pasta>
 */
public final class TesteRevisaoConcObservador {

    private static Verifica v;

    static final Pilha.Donos DONOS = new Pilha.Donos() {
        @Override
        public String dono(String classe) {
            return classe.startsWith("modx.") ? "modx.jar" : null;
        }
    };

    public static void main(String[] args) throws Exception {
        File tmp = new File(args.length > 0 ? args[0] : "revconc-tmp");
        tmp.mkdirs();
        v = new Verifica("TesteRevisaoConcObservador");
        ThreadServidor.definir(Thread.currentThread());
        i1AmostraDeOutraThreadComTeto();
        m1PedidoLidoEZeradoDeUmaVez(tmp);
        m4FalhaDeOutraThreadNaoEscreveAtivo(tmp);
        m2FalhaSabeDeQualObservacao(tmp);
        m3ComandoConfereEAgeSobTrava(tmp);
        m8IndiceTravadoTemPrazo(tmp);
        Observador.reiniciarParaTeste();
        v.fim();
    }

    static void emOutraThread(String nome, Runnable r) throws Exception {
        Thread t = new Thread(r, nome);
        t.start();
        t.join();
    }

    /** Soma dos espacos "com amostra de pilha" (espaco diferente de 0) de uma tabela de outra thread. */
    static long amostradas(AtomicLongArray a) {
        long s = 0;
        for (int i = 0; i < a.length(); i++) {
            if (i % Mods.PASSO != 0) {
                s += a.get(i);
            }
        }
        return s;
    }

    static Jogo jogoFalso(final File pasta) {
        return new Jogo() {
            @Override
            public FotoMundo[] fotografar() {
                return new FotoMundo[0];
            }

            @Override
            public void conferirListas(ContagemLista c) {
            }

            @Override
            public File pastaDeSaida() {
                return pasta;
            }
        };
    }

    static void preparar(File pasta) {
        Observador.reiniciarParaTeste();
        ThreadServidor.definir(Thread.currentThread());
        Observador.configurar(true, 256, 50.0);
        Observador.definirDonos(DONOS);
        Observador.definirJogo(jogoFalso(pasta));
    }

    /** As chamadas a metodos de AtomicInteger dentro de um metodo de Observador, na ordem (lido do .class). */
    static java.util.List<String> chamadasAtomicas(final String metodo) throws Exception {
        final java.util.List<String> r = new java.util.ArrayList<String>();
        java.io.InputStream is = Observador.class.getResourceAsStream("Observador.class");
        try {
            new org.objectweb.asm.ClassReader(is).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM5) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int acc, String nome, String desc, String sig, String[] exc) {
                    if (!nome.equals(metodo)) {
                        return null;
                    }
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM5) {
                        @Override
                        public void visitMethodInsn(int op, String dono, String n, String d, boolean itf) {
                            if (dono.equals("java/util/concurrent/atomic/AtomicInteger")) {
                                r.add(n);
                            }
                        }
                    };
                }
            }, 0);
        } finally {
            is.close();
        }
        return r;
    }

    /**
     * m1: o inicio do tick le e zera o pedido numa operacao so. A corrida de verdade tem janela de nanossegundos (o
     * revisor a forcou 2 vezes em 9000 tentativas), entao a verificacao que sempre falha no codigo antigo e a do bytecode;
     * a de carga confere o comportamento.
     */
    private static void m1PedidoLidoEZeradoDeUmaVez(File tmp) throws Exception {
        java.util.List<String> c = chamadasAtomicas("inicioTickServidor");
        v.sim(c.contains("getAndSet") && !c.contains("set"), "m1: inicioTickServidor usa getAndSet e nenhum set solto no pedido (chamadas: " + c + ")");
        preparar(new File(tmp, "m1"));
        int enviadas = 0, perdidas = 0;
        for (int i = 0; i < 300; i++) {
            Observador.comando(new String[] {"5"});
            final java.util.concurrent.CountDownLatch pronto = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicBoolean vai = new java.util.concurrent.atomic.AtomicBoolean();
            final String[] resp = new String[1];
            Thread rcon = new Thread("rcon") {
                @Override
                public void run() {
                    pronto.countDown();
                    while (!vai.get()) {
                        // gira
                    }
                    resp[0] = Observador.comando(new String[] {"parar"}).get(0);
                }
            };
            rcon.start();
            pronto.await();
            vai.set(true);
            Observador.inicioTickServidor(); // aplica o pedido, cruzando com o 'parar'
            rcon.join();
            Observador.fimTickServidor();
            Observador.inicioTickServidor(); // um 'parar' que chegou depois e aplicado aqui
            if (resp[0].startsWith("Pedido de parada enviado")) {
                enviadas++;
                if (Observador.N != null) {
                    perdidas++;
                }
            }
            if (Observador.N != null) {
                Observador.comando(new String[] {"parar"});
                Observador.inicioTickServidor();
            }
            Observador.aguardarGravacao(5000);
        }
        v.sim(perdidas == 0, "m1: 300 corridas 'parar' x inicio do tick: nenhuma parada dita 'enviada' se perdeu (enviadas " + enviadas + ", perdidas " + perdidas + ")");
    }

    /** m4: se o tratamento da falha lancar em outra thread, ATIVO nao muda e a observacao ainda e finalizada e gravada. */
    private static void m4FalhaDeOutraThreadNaoEscreveAtivo(File tmp) throws Exception {
        preparar(new File(tmp, "m4"));
        Observador.comando(new String[] {"30"});
        Observador.inicioTickServidor();
        emOutraThread("outra", new Runnable() {
            @Override
            public void run() {
                Observador.falha(Observador.N, "teste m4", null); // erro nulo: o proprio tratamento lanca (NullPointerException)
            }
        });
        v.sim(Observador.ATIVO && Observador.N != null, "m4: falha() que lanca em outra thread nao escreve ATIVO (a thread do servidor segue chamando os ganchos)");
        Observador.fimTickServidor();
        Observador.inicioTickServidor();
        Observador.aguardarGravacao(10000);
        String u = Observador.ultimoResultado();
        v.sim(!Observador.ATIVO && Observador.N == null && u != null && u.startsWith("gravado") && u.contains("erro interno: teste m4"),
                "m4: no tick seguinte a observacao e finalizada e gravada, com o motivo: " + u);
    }

    static void falhaEmOutraThread(final Nucleo n, final String quando) throws Exception {
        emOutraThread("outra", new Runnable() {
            @Override
            public void run() {
                Observador.falha(n, quando, new RuntimeException("simulada"));
            }
        });
    }

    /**
     * m2: a falha de outra thread leva o nucleo da observacao dela. Um gancho antigo (da observacao A) nao para a B nem
     * apaga um pedido de inicio. (No codigo antigo falha() nao recebia o nucleo: o caso foi reproduzido pelo revisor, teste C3.)
     */
    private static void m2FalhaSabeDeQualObservacao(File tmp) throws Exception {
        preparar(new File(tmp, "m2"));
        Observador.comando(new String[] {"30"});
        Observador.inicioTickServidor();
        Nucleo a = Observador.N;
        Observador.comando(new String[] {"parar"});
        Observador.inicioTickServidor();
        Observador.aguardarGravacao(10000);
        // pedido de inicio ja aceito + falha de um gancho antigo antes do tick: a observacao B comeca assim mesmo
        Observador.comando(new String[] {"30"});
        falhaEmOutraThread(a, "gancho antigo da observacao A");
        Observador.inicioTickServidor();
        Nucleo b = Observador.N;
        v.sim(b != null && b != a && Observador.ATIVO, "m2: falha de gancho antigo nao apaga o pedido de inicio (B comecou)");
        // B de pe + falha de um gancho antigo: B segue
        falhaEmOutraThread(a, "gancho antigo da observacao A");
        Observador.fimTickServidor();
        Observador.inicioTickServidor();
        v.sim(Observador.N == b && Observador.ATIVO, "m2: falha de gancho antigo (observacao A) nao para a observacao B");
        // a falha da propria B para a B, com o motivo, e nao deixa sobra para a proxima
        falhaEmOutraThread(b, "gancho da B");
        falhaEmOutraThread(b, "segunda falha da B");
        v.sim(Observador.N == b && Observador.ATIVO, "m2: a falha da B em outra thread so pede a parada (B de pe ate o tick)");
        Observador.fimTickServidor();
        Observador.inicioTickServidor();
        Observador.aguardarGravacao(10000);
        String u = Observador.ultimoResultado();
        v.sim(Observador.N == null && !Observador.ATIVO && u != null && u.contains("erro interno: gancho da B"),
                "m2: no tick seguinte a B para, com o motivo da primeira falha: " + u);
        Thread.sleep(1100); // o nome do arquivo tem resolucao de 1 s
        Observador.comando(new String[] {"30"});
        Observador.inicioTickServidor();
        Observador.fimTickServidor();
        Observador.inicioTickServidor();
        v.sim(Observador.N != null && Observador.N != b, "m2: a observacao seguinte nao herda o pedido de parada da B");
        Observador.comando(new String[] {"parar"});
        Observador.inicioTickServidor();
        Observador.aguardarGravacao(10000);
    }

    /** m3: dois 'observar' ao mesmo tempo (console e RCON): so um e aceito; 'parar' enxerga a observacao pelo N. */
    private static void m3ComandoConfereEAgeSobTrava(File tmp) throws Exception {
        preparar(new File(tmp, "m3"));
        final int rodadas = 30000;
        final java.util.concurrent.atomic.AtomicInteger rodada = new java.util.concurrent.atomic.AtomicInteger(-1);
        final java.util.concurrent.atomic.AtomicInteger feitos = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger aceitos = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] ts = new Thread[2];
        for (int f = 0; f < 2; f++) {
            ts[f] = new Thread("comando-" + f) {
                @Override
                public void run() {
                    for (int r = 0; r < rodadas; r++) {
                        while (rodada.get() < r) {
                            // gira ate a rodada abrir (as duas threads saem juntas)
                        }
                        if (Observador.comando(new String[] {"5"}).get(0).startsWith("Observacao de 5 s pedida")) {
                            aceitos.incrementAndGet();
                        }
                        feitos.incrementAndGet();
                    }
                }
            };
            ts[f].start();
        }
        int duplas = 0, nenhum = 0;
        for (int r = 0; r < rodadas; r++) {
            aceitos.set(0);
            rodada.set(r);
            while (feitos.get() < 2 * (r + 1)) {
                // gira
            }
            if (aceitos.get() == 2) {
                duplas++;
            } else if (aceitos.get() == 0) {
                nenhum++;
            }
            Observador.comando(new String[] {"parar"}); // cancela o pedido (nenhuma observacao chega a comecar)
            Observador.inicioTickServidor();
        }
        for (Thread t : ts) {
            t.join();
        }
        v.sim(duplas == 0 && nenhum == 0, "m3: " + rodadas + " rodadas de dois 'observar 5' juntos: sempre exatamente um aceito (dois aceitos em "
                + duplas + ", nenhum em " + nenhum + ")");
        v.sim(Observador.N == null && !Observador.ATIVO, "m3: pedido cancelado antes do tick nao inicia observacao");
        // 'parar' vindo de outra thread enxerga a observacao de pe pelo N (volatil), mesmo se ATIVO estiver atrasado para ela
        Observador.comando(new String[] {"30"});
        Observador.inicioTickServidor();
        Observador.ATIVO = false; // simula a leitura atrasada de ATIVO em outra thread
        final String[] resp = new String[2];
        emOutraThread("rcon", new Runnable() {
            @Override
            public void run() {
                resp[0] = Observador.comando(new String[] {"status"}).get(0);
                resp[1] = Observador.comando(new String[] {"parar"}).get(0);
            }
        });
        Observador.ATIVO = true;
        v.sim(resp[0].startsWith("Observando") && resp[1].startsWith("Pedido de parada enviado"), "m3: 'status' e 'parar' usam N, nao ATIVO: " + resp[0] + " / " + resp[1]);
        Observador.inicioTickServidor();
        Observador.aguardarGravacao(10000);
        v.sim(Observador.N == null, "m3: a parada pedida foi aplicada no tick");
    }

    static boolean threadDoIndiceViva() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("Hoshiestar observador indice") && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /** m8: se a thread do indice de jars travar, um 'observar' depois do prazo abandona a montagem velha. */
    private static void m8IndiceTravadoTemPrazo(File tmp) throws Exception {
        Observador.reiniciarParaTeste(); // donos = null: o comando monta o indice em outra thread
        ThreadServidor.definir(Thread.currentThread());
        Observador.configurar(true, 256, 50.0);
        Observador.definirJogo(jogoFalso(new File(tmp, "m8")));
        final long[] agora = {1000L};
        Observador.definirRelogio(new Nucleo.Relogio() {
            @Override
            public long nano() {
                return agora[0];
            }
        });
        final java.util.concurrent.CountDownLatch entrou = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch soltar = new java.util.concurrent.CountDownLatch(1);
        Observador.antesDePedirDoIndice = new Runnable() {
            @Override
            public void run() {
                entrou.countDown();
                try {
                    soltar.await(); // a thread do indice "trava" aqui
                } catch (InterruptedException e) {
                    // segue
                }
            }
        };
        java.util.List<String> r1 = Observador.comando(new String[] {"7"});
        v.sim(r1.get(0).startsWith("Montando o indice"), "m8: o primeiro pedido monta o indice: " + r1.get(0));
        v.sim(entrou.await(60, java.util.concurrent.TimeUnit.SECONDS), "m8: a thread do indice chegou ao ponto em que trava");
        agora[0] += 60L * 1000000000L;
        java.util.List<String> r2 = Observador.comando(new String[] {"5"});
        v.sim(r2.get(0).startsWith("Ja ha uma observacao"), "m8: dentro do prazo o segundo pedido e recusado: " + r2.get(0));
        agora[0] += 61L * 1000000000L;
        Observador.antesDePedirDoIndice = null;
        java.util.List<String> r3 = Observador.comando(new String[] {"5"});
        v.sim(r3.size() == 2 && r3.get(0).contains("abandonada") && (r3.get(1).startsWith("Observacao de 5 s pedida") || r3.get(1).startsWith("Montando")),
                "m8: depois do prazo (120 s) o pedido novo abandona a montagem travada e e aceito: " + r3);
        for (int i = 0; i < 600 && Observador.N == null; i++) { // se montou o indice de novo, espera o pedido chegar
            Observador.inicioTickServidor();
            Thread.sleep(50);
        }
        v.sim(Observador.N != null, "m8: a observacao do pedido novo comecou");
        Observador.comando(new String[] {"parar"});
        Observador.inicioTickServidor();
        Observador.aguardarGravacao(10000);
        v.sim(Observador.N == null, "m8: e parou pelo comando");
        soltar.countDown(); // a thread travada volta a andar
        for (int i = 0; i < 200 && threadDoIndiceViva(); i++) {
            Thread.sleep(50);
        }
        Observador.inicioTickServidor();
        String st = Observador.comando(new String[] {"status"}).get(0);
        v.sim(!threadDoIndiceViva() && Observador.N == null && st.startsWith("Parado"), "m8: a montagem abandonada, quando termina, nao pede observacao nem prende o estado: " + st);
        Observador.definirRelogio(null);
    }

    /** I1: a amostra de pilha em outra thread tem teto por tick (T4 e lista somadas) e o custo entra na valvula. */
    private static void i1AmostraDeOutraThreadComTeto() throws Exception {
        final Nucleo n = new Nucleo(DONOS, 256, 5.0, null);
        n.inicioTick(0);
        emOutraThread("outra", new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 64 * 1000; i++) {
                    n.outraThread(Nucleo.A_LER_BLOCO);
                }
                for (int i = 0; i < 64 * 1000; i++) {
                    n.lista.contarOutraThread(n, 0);
                }
            }
        });
        long a1 = amostradas(n.t4) + amostradas(n.lista.outra);
        v.igual(128000L, n.t4Total.get() + n.lista.outraTotal.get(), "I1: todas as chamadas de outra thread continuam contadas");
        v.sim(a1 > 0 && a1 <= 20, "I1: 128000 chamadas de outra thread dentro de um tick: no maximo 20 amostras de pilha (foram " + a1 + ")");
        n.fimTick(1000000);
        n.inicioTick(1000000);
        emOutraThread("outra", new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 64 * 1000; i++) {
                    n.outraThread(Nucleo.A_LER_BLOCO);
                }
            }
        });
        long a2 = amostradas(n.t4) + amostradas(n.lista.outra);
        v.sim(a2 > a1 && a2 <= 40, "I1: o inicio do tick devolve o orcamento (total agora " + a2 + ")");
        // 4 threads juntas, sem tick novo: o teto vale para a soma (passa no maximo pelo numero de threads)
        final Nucleo q = new Nucleo(DONOS, 256, 5.0, null);
        q.inicioTick(0);
        Thread[] ts = new Thread[4];
        for (int f = 0; f < ts.length; f++) {
            ts[f] = new Thread("outra-" + f) {
                @Override
                public void run() {
                    for (int i = 0; i < 200000; i++) {
                        q.outraThread(Nucleo.A_LER_TILE);
                    }
                }
            };
            ts[f].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        v.igual(800000L, q.t4Total.get(), "I1: 4 threads juntas: nenhuma contagem perdida");
        v.sim(amostradas(q.t4) <= 20, "I1: 4 threads juntas num tick: no maximo 20 amostras (foram " + amostradas(q.t4) + ")");
        // valvula: 20000 chamadas de outra thread por tick de 1 ms passam do limite de 5% e param na segunda janela
        final Nucleo k = new Nucleo(DONOS, 256, 5.0, null);
        boolean parou = false;
        int ticks = 0;
        for (; ticks < 500 && !parou; ticks++) {
            k.inicioTick(ticks * 1000000L);
            emOutraThread("outra", new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 20000; i++) {
                        k.outraThread(Nucleo.A_LER_BLOCO);
                    }
                }
            });
            parou = k.fimTick(ticks * 1000000L + 1000000L);
        }
        v.sim(parou && ticks == 2 * Nucleo.JANELA_TICKS, "I1: o custo das outras threads entra na valvula (parou em " + ticks + " ticks: "
                + k.motivoValvula + ")");
    }
}
