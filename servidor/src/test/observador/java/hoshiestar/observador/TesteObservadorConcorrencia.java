package hoshiestar.observador;

import hoshiestar.api.ThreadServidor;
import hoshiestar.teste.Verifica;
import teste.moda.Chamador;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A unica parte do observador que outras threads tocam: T4 (acessos de outra thread) e o pedido do comando (RCON).
 * (1) 4 threads x 200 mil acessos de outra thread enquanto a thread do servidor registra os dela: as contas fecham;
 * (2) uma thread "RCON" manda status/parar/observar sem parar enquanto a thread do servidor roda ticks: nada lanca,
 * nada trava, todo relatorio gravado e um JSON valido.
 */
public final class TesteObservadorConcorrencia {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteObservadorConcorrencia");
        File base = new File(args[0]);
        base.mkdirs();
        int segundos = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        try {
            t4(v);
            rcon(v, base, segundos);
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    static void t4(Verifica v) throws Exception {
        final Nucleo n = TesteObservadorNucleo.novo(1000000);
        final int fios = 4;
        final int cada = 200000;
        Thread[] ts = new Thread[fios];
        for (int i = 0; i < fios; i++) {
            ts[i] = new Thread("fio-" + i) {
                @Override
                public void run() {
                    for (int k = 0; k < cada; k++) {
                        Chamador.outraThread(n, k % 3 == 0 ? Nucleo.A_LER_BLOCO : Nucleo.A_ESCREVER_BLOCO);
                    }
                }
            };
        }
        for (Thread t : ts) {
            t.start();
        }
        // a thread "do servidor" continua registrando as dela ao mesmo tempo
        n.inicioTick(0);
        n.entrar(Nucleo.C_TILE, TesteObservadorNucleo.M0, 0, 0, 0, TesteObservadorNucleo.ModAMaquina.class);
        for (int k = 0; k < 100000; k++) {
            n.acesso(Nucleo.A_ESCREVER_BLOCO, TesteObservadorNucleo.M0, 0, 20, 0);
        }
        for (Thread t : ts) {
            t.join();
        }
        v.igual((long) fios * cada, n.totalOutraThread(), "T4: total de acessos de outra thread = 800000 exato");
        long soma = 0;
        long ler = 0;
        long comAmostra = 0;
        for (int i = 0; i < n.t4.length(); i++) {
            soma += n.t4.get(i);
            if (i / Mods.PASSO == Nucleo.A_LER_BLOCO) {
                ler += n.t4.get(i);
            }
            if (i % Mods.PASSO != 0) {
                comAmostra += n.t4.get(i);
            }
        }
        v.igual((long) fios * cada, soma, "T4: a soma da tabela fecha com o total");
        long esperadoLer = 0;
        for (int k = 0; k < cada; k++) {
            if (k % 3 == 0) {
                esperadoLer++;
            }
        }
        v.igual(esperadoLer * fios, ler, "T4: contagem por tipo de acesso exata");
        v.sim(comAmostra > 0 && comAmostra <= (long) fios * cada / 60 + fios, "T4: amostra de pilha 1 em 64 (" + comAmostra + " amostras)");
        v.igual(100000L, n.acessosCompletos(), "T4: o que a thread do servidor registrou ao mesmo tempo nao se perdeu");
        boolean achou = false;
        for (int i = 0; i < n.t4.length(); i++) {
            if (i % Mods.PASSO == n.mods.id("moda.jar") + 1 && n.t4.get(i) > 0) {
                achou = true;
            }
        }
        v.sim(achou, "T4: o mod da pilha (teste.moda) aparece nas amostras");
    }

    static void rcon(Verifica v, File base, int segundos) throws Exception {
        final File saida = new File(base, "saida");
        TesteObservadorConfig.JogoFalso jogo = new TesteObservadorConfig.JogoFalso(new File(saida, "hoshiestar"));
        Observador.reiniciarParaTeste();
        Observador.configurar(true, 4, 50.0);
        Observador.definirJogo(jogo);
        Observador.definirDonos(TesteObservadorNucleo.DONOS);
        ThreadServidor.definir(Thread.currentThread());
        final AtomicBoolean fim = new AtomicBoolean();
        final AtomicReference<Throwable> erro = new AtomicReference<Throwable>();
        final AtomicLong comandos = new AtomicLong();
        Thread rcon = new Thread("rcon-falso") {
            @Override
            public void run() {
                try {
                    String[][] cmds = {{"status"}, {"parar"}, {"5"}, {"10"}, {"status"}, {"parar"}, {"abc"}};
                    int i = 0;
                    while (!fim.get()) {
                        List<String> r = Observador.comando(cmds[i++ % cmds.length]);
                        if (r == null || r.isEmpty()) {
                            throw new IllegalStateException("comando sem resposta");
                        }
                        comandos.incrementAndGet();
                        Thread.sleep(1);
                    }
                } catch (Throwable t) {
                    erro.set(t);
                }
            }
        };
        rcon.start();
        long fimMs = System.currentTimeMillis() + segundos * 1000L;
        long ticks = 0;
        long ativos = 0;
        while (System.currentTimeMillis() < fimMs) {
            Observador.inicioTickServidor();
            Nucleo n = Observador.N;
            if (Observador.ATIVO && n != null) {
                ativos++;
                n.entrar(Nucleo.C_TILE, TesteObservadorNucleo.M0, 0, 5, 5, TesteObservadorNucleo.ModAMaquina.class);
                for (int i = 0; i < 200; i++) {
                    Chamador.escrever(n, TesteObservadorNucleo.M0, 0, i % 2 == 0 ? 20 : 200, 5);
                }
                n.sair(Nucleo.C_TILE);
            }
            Observador.fimTickServidor();
            ticks++;
            Thread.sleep(0, 200000);
        }
        fim.set(true);
        rcon.join(5000);
        v.sim(!rcon.isAlive(), "rcon: a thread terminou (sem travar)");
        v.sim(erro.get() == null, "rcon: nenhuma excecao na thread do comando " + erro.get());
        v.sim(comandos.get() > 100, "rcon: mandou " + comandos.get() + " comandos");
        v.sim(ativos > 0, "servidor: houve ticks com observacao ativa (" + ativos + " de " + ticks + ")");
        Observador.comando(new String[] {"parar"});
        Observador.inicioTickServidor();
        Observador.encerrarNoDesligamento();
        v.sim(!Observador.ATIVO, "no fim: inativo");
        File[] l = new File(saida, "hoshiestar").listFiles();
        int jsons = 0;
        int txts = 0;
        int invalidos = 0;
        if (l != null) {
            for (File f : l) {
                if (f.getName().endsWith(".json")) {
                    jsons++;
                    try {
                        JsonLeitor.ler(new String(Files.readAllBytes(f.toPath()), UTF8));
                    } catch (Throwable t) {
                        invalidos++;
                    }
                } else if (f.getName().endsWith(".txt")) {
                    txts++;
                }
            }
        }
        v.sim(jsons >= 1 && jsons == txts, "rcon: relatorios gravados (" + jsons + " .json e " + txts + " .txt)");
        v.igual(0, invalidos, "rcon: todo .json gravado e valido");
        ThreadServidor.definir(null);
        Observador.reiniciarParaTeste();
        TesteObservadorConfig.limpar(saida);
    }
}
