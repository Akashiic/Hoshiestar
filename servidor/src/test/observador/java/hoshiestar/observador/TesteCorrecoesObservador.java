// Hoshiestar
package hoshiestar.observador;

import hoshiestar.api.ThreadServidor;
import hoshiestar.teste.Verifica;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Testes dos defeitos achados na revisao independente da Etapa 2 (observador e prototipo da lista de tiles). Cada
 * verificacao afirma o comportamento correto. Uso: java -cp ... hoshiestar.observador.TesteCorrecoesObservador <pasta temporaria>
 */
public final class TesteCorrecoesObservador {

    private static Verifica v;

    public static void main(String[] args) throws Exception {
        File tmp = new File(args.length > 0 ? args[0] : "correcoes-tmp");
        tmp.mkdirs();
        v = new Verifica("TesteCorrecoesObservador");
        ThreadServidor.definir(Thread.currentThread());
        mundoLiberadoDepoisDaObservacao(tmp);
        t4ModAcimaDe255();
        falhaDeOutraThread(tmp);
        contextoDesconhecidoNaoESerial();
        pararDuranteIndice(tmp);
        Observador.reiniciarParaTeste();
        v.fim();
    }

    private static void apagar(File f) {
        File[] c = f.listFiles();
        if (c != null) {
            for (File x : c) {
                apagar(x);
            }
        }
        f.delete();
    }

    private static Pilha.Donos semDonos() {
        return new Pilha.Donos() {
            @Override
            public String dono(String c) {
                return null;
            }
        };
    }

    private static Nucleo novo(Pilha.Donos d) {
        Observador.ATIVO = false;
        Observador.N = null;
        Observador.configurarLista(true);
        Nucleo n = new Nucleo(d != null ? d : semDonos(), 1000000, 5.0, null);
        Observador.N = n;
        Observador.ATIVO = true;
        ListaObservada.inicioTick();
        return n;
    }

    private static void parar() {
        Observador.ATIVO = false;
        Observador.N = null;
        ListaObservada.inicioTick();
    }

    private static Jogo jogoFalso(final File pasta) {
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

    /** Achado 1: depois do relatorio gravado, o objeto de mundo do contexto tem de poder ser coletado pelo GC. */
    private static void mundoLiberadoDepoisDaObservacao(File tmp) throws Exception {
        Observador.reiniciarParaTeste();
        ThreadServidor.definir(Thread.currentThread());
        Observador.configurar(true, 256, 5.0);
        Observador.definirDonos(semDonos());
        Observador.definirJogo(jogoFalso(new File(tmp, "mundo")));
        Observador.comando(new String[] {"30"});
        Observador.inicioTickServidor();
        Object mundo = new Object[1 << 20];
        WeakReference<Object> ref = new WeakReference<Object>(mundo);
        // contexto que NAO sai (como depois de uma excecao): o vetor de contextos seguraria o mundo
        Observador.N.entrar(Nucleo.C_TILE, mundo, 0, 3, 3, String.class);
        Observador.fimTickServidor();
        Observador.comando(new String[] {"parar"});
        Observador.inicioTickServidor();
        Observador.aguardarGravacao(10000);
        mundo = null;
        for (int i = 0; i < 10 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        v.sim(ref.get() == null, "observacao terminada e relatorio gravado: o objeto de mundo do contexto foi liberado");
        // sair() tambem solta o mundo do contexto que saiu
        Nucleo n = novo(null);
        Object m2 = new Object[1 << 20];
        WeakReference<Object> ref2 = new WeakReference<Object>(m2);
        n.entrar(Nucleo.C_TILE, m2, 0, 3, 3, String.class);
        n.sair(Nucleo.C_TILE);
        m2 = null;
        for (int i = 0; i < 10 && ref2.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        v.sim(ref2.get() == null, "sair() solta o mundo do contexto");
        parar();
    }

    /** Achado 2: mod com id > 255 na T4 e na lista de outra thread sai com o proprio nome (e "sem amostra" != "sem mod"). */
    private static void t4ModAcimaDe255() throws Exception {
        final Nucleo n = novo(new Pilha.Donos() {
            @Override
            public String dono(String c) {
                return c.startsWith("teste.obs.") ? "mod-alvo.jar" : null;
            }
        });
        for (int i = 0; i < 300; i++) {
            n.mods.id("mod-ocupante-" + i + ".jar");
        }
        Thread t = new Thread() {
            @Override
            public void run() {
                teste.obs.Chamador.rodar(new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < 64; i++) {
                            n.outraThread(Nucleo.A_LER_TILE);
                        }
                    }
                });
            }
        };
        t.start();
        t.join();
        String nomeNaTabela = null;
        long semAmostra = 0;
        for (int i = 0; i < n.t4.length(); i++) {
            if (n.t4.get(i) > 0) {
                if (i % Mods.PASSO == 0) {
                    semAmostra += n.t4.get(i);
                } else {
                    nomeNaTabela = n.mods.nomeDoEspaco(i % Mods.PASSO);
                }
            }
        }
        v.sim(n.mods.id("mod-alvo.jar") > 255, "o mod-alvo tem id acima de 255 (" + n.mods.id("mod-alvo.jar") + ")");
        v.igual("mod-alvo.jar", nomeNaTabela, "T4: o mod amostrado na pilha aparece com o proprio nome");
        v.igual(63L, semAmostra, "T4: as 63 chamadas nao amostradas ficam em '(sem amostra)'");
        parar();

        // amostrado mas sem mod no codigo da pilha: "(nenhum)", nao "(sem amostra)"
        final Nucleo n2 = novo(null);
        Thread t2 = new Thread() {
            @Override
            public void run() {
                for (int i = 0; i < 64; i++) {
                    n2.outraThread(Nucleo.A_LER_TILE);
                }
            }
        };
        t2.start();
        t2.join();
        boolean achouNenhum = false;
        for (int i = 0; i < n2.t4.length(); i++) {
            if (n2.t4.get(i) > 0 && i % Mods.PASSO != 0) {
                achouNenhum = n2.mods.nomeDoEspaco(i % Mods.PASSO).equals("(nenhum)");
            }
        }
        v.sim(achouNenhum, "T4: amostrado sem mod aparece como '(nenhum)', separado de '(sem amostra)'");
        parar();

        // a lista (outra thread) usa o mesmo desenho
        final Nucleo n3 = novo(new Pilha.Donos() {
            @Override
            public String dono(String c) {
                return c.startsWith("teste.obs.") ? "mod-alvo.jar" : null;
            }
        });
        for (int i = 0; i < 300; i++) {
            n3.mods.id("mod-ocupante-" + i + ".jar");
        }
        final List l = new ListaObservadaHashed();
        Thread t3 = new Thread() {
            @Override
            public void run() {
                teste.obs.Chamador.rodar(new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < 64; i++) {
                            l.size();
                        }
                    }
                });
            }
        };
        t3.start();
        t3.join();
        String nomeLista = null;
        ContagemLista c = n3.lista;
        for (int i = 0; i < c.outra.length(); i++) {
            if (c.outra.get(i) > 0 && i % Mods.PASSO != 0) {
                nomeLista = n3.mods.nomeDoEspaco(i % Mods.PASSO);
            }
        }
        v.igual("mod-alvo.jar", nomeLista, "lista, outra thread: o mod com id > 255 aparece com o proprio nome");
        parar();
    }

    /** Achado 3: falha() vinda de outra thread so registra e pede a parada; quem finaliza e grava e a thread do servidor. */
    private static void falhaDeOutraThread(File tmp) throws Exception {
        Observador.reiniciarParaTeste();
        ThreadServidor.definir(Thread.currentThread());
        Observador.configurar(true, 256, 5.0);
        Observador.definirDonos(semDonos());
        final File pasta = new File(tmp, "falha");
        apagar(pasta);
        Observador.definirJogo(jogoFalso(pasta));
        Observador.comando(new String[] {"30"});
        Observador.inicioTickServidor();
        v.sim(Observador.ATIVO, "observacao iniciada na thread do servidor");
        Thread outra = new Thread("outra thread") {
            @Override
            public void run() {
                Observador.falha(Observador.N, "leitura de bloco", new RuntimeException("simulada"));
            }
        };
        outra.start();
        outra.join();
        Observador.aguardarGravacao(2000);
        File[] f = pasta.listFiles();
        v.sim(Observador.ATIVO, "falha de outra thread: a observacao continua ate o proximo tick do servidor");
        v.sim(f == null || f.length == 0, "falha de outra thread: nada foi gravado fora da thread do servidor");
        Observador.inicioTickServidor();
        Observador.aguardarGravacao(10000);
        f = pasta.listFiles();
        v.sim(!Observador.ATIVO, "no proximo tick a thread do servidor finaliza");
        v.sim(f != null && f.length == 2, "e grava um relatorio so (.json e .txt): " + (f == null ? 0 : f.length) + " arquivos");
        v.sim(Observador.ultimoResultado() != null && Observador.ultimoResultado().contains("erro interno: leitura de bloco"),
                "o motivo guardado e o da falha: " + Observador.ultimoResultado());
        // duas falhas de outras threads ao mesmo tempo: ainda um relatorio so
        Thread.sleep(1100); // o nome do arquivo tem resolucao de 1 s
        Observador.comando(new String[] {"30"});
        Observador.inicioTickServidor();
        Thread[] ts = new Thread[2];
        for (int i = 0; i < 2; i++) {
            ts[i] = new Thread() {
                @Override
                public void run() {
                    Observador.falha(Observador.N, "acesso", new RuntimeException("simulada"));
                }
            };
            ts[i].start();
        }
        ts[0].join();
        ts[1].join();
        Observador.inicioTickServidor();
        Observador.aguardarGravacao(10000);
        File[] g = pasta.listFiles();
        v.sim(g != null && g.length == 4, "duas falhas de outras threads: um relatorio novo (4 arquivos no total): " + (g == null ? 0 : g.length));
    }

    /** Achado 4: chamadas de fora sem contexto conhecido (C_OUTRO) nao contam como seriais no criterio 2. */
    private static void contextoDesconhecidoNaoESerial() {
        Nucleo n = novo(null);
        List l = new ListaObservadaHashed();
        for (int i = 0; i < 100; i++) {
            l.add(i); // sem contexto, modificacao de fora
        }
        ContagemLista c = n.lista;
        long[] x = c.indicadores();
        v.igual(100L, x[0], "indicadores: 100 chamadas de fora");
        v.igual(0L, x[1], "indicadores: nenhuma conta como serial");
        v.igual(100L, x[6], "indicadores: 100 sem contexto conhecido");
        String ind = c.indicacao(n);
        v.sim(ind.startsWith("inconclusivo") && ind.contains("100 de 100"), "so sem contexto: inconclusivo e mostra a parte: " + ind);
        // 10 sem contexto + 990 em contexto serial (rede): ainda criterio 2, mas mostrando a parte sem contexto
        c.fora[ListaObservada.ADD * 16 + Nucleo.C_OUTRO] = 10;
        c.fora[ListaObservada.ADD * 16 + Nucleo.C_REDE] = 990;
        for (int i = 0; i < ListaObservada.NOPS * 16; i++) {
            if (i != ListaObservada.ADD * 16 + Nucleo.C_OUTRO && i != ListaObservada.ADD * 16 + Nucleo.C_REDE) {
                c.fora[i] = 0;
            }
        }
        ind = c.indicacao(n);
        v.sim(ind.startsWith("criterio 2") && ind.contains("10 de 1000") && ind.contains("sem contexto conhecido"),
                "10 sem contexto + 990 seriais (rede): criterio 2 sobre os conhecidos, com a parte sem contexto: " + ind);
        // 600 sem contexto + 400 de regiao
        for (int i = 0; i < ListaObservada.NOPS * 16; i++) {
            c.fora[i] = 0;
        }
        c.fora[ListaObservada.SIZE * 16 + Nucleo.C_OUTRO] = 600;
        c.fora[ListaObservada.SIZE * 16 + Nucleo.C_TILE] = 400;
        v.sim(c.indicacao(n).startsWith("inconclusivo"), "mais da metade sem contexto: inconclusivo: " + c.indicacao(n));
        parar();
    }

    /** Achado 5: 'hoshie observar parar' durante a montagem do indice de jars nao pode se perder. */
    private static void pararDuranteIndice(File tmp) throws Exception {
        Observador.reiniciarParaTeste(); // donos = null: o comando monta o indice em outra thread
        Observador.configurar(true, 256, 5.0);
        final File pasta = new File(tmp, "indice");
        Observador.definirJogo(new Jogo() {
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
        });
        final java.util.concurrent.CountDownLatch montado = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch liberar = new java.util.concurrent.CountDownLatch(1);
        Observador.antesDePedirDoIndice = new Runnable() {
            @Override
            public void run() {
                montado.countDown();
                try {
                    liberar.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        List<String> r1 = Observador.comando(new String[] {"30"});
        v.sim(montado.await(60, java.util.concurrent.TimeUnit.SECONDS), "indice montado (a thread espera o teste): " + r1);
        List<String> r2 = Observador.comando(new String[] {"parar"}); // o indice ja esta pronto mas a thread ainda nao pediu
        Observador.inicioTickServidor(); // um tick passa: o pedido de parada nao pode ser gasto aqui
        liberar.countDown();
        long fim = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < fim && Observador.status().get(0).startsWith("Montando")) {
            Thread.sleep(20);
        }
        Observador.inicioTickServidor();
        boolean ativo = Observador.ATIVO;
        v.sim(!ativo, "parar durante a montagem do indice: a observacao NAO comeca depois (" + r1 + " / " + r2 + ")");
        v.sim(r2.get(0).contains("cancelado") && Observador.status().get(0).equals("Parado.")
                && Observador.status().get(1).contains("cancelado"), "parar durante o indice: diz que cancelou e o status volta a 'Parado': " + Observador.status());
        if (ativo) {
            Observador.comando(new String[] {"parar"});
            Observador.inicioTickServidor();
            Observador.aguardarGravacao(10000);
        }
        // sem parar, o caminho de sempre continua: pede e comeca
        Observador.antesDePedirDoIndice = null;
        Observador.comando(new String[] {"30"});
        fim = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < fim && Observador.status().get(0).startsWith("Montando")) {
            Thread.sleep(20);
        }
        Observador.inicioTickServidor();
        v.sim(Observador.ATIVO, "sem parar: a observacao comeca depois do indice");
        Observador.encerrarNoDesligamento();
        apagar(pasta);
    }

    private TesteCorrecoesObservador() {
    }
}
