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
package hoshiestar.teste.parada;

import hoshiestar.parada.MaquinaDeParada;
import hoshiestar.teste.Verifica;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A parada numa JVM de verdade: cada cenario roda numa JVM filha que termina com System.exit, com
 * um gancho de desligamento real ({@code Runtime.addShutdownHook}) usando a {@link MaquinaDeParada}
 * com a mesma cola dos patches. Prova o que o servidor de mentira nao prova: que a JVM termina (nao
 * fica presa), com o codigo de saida certo, e que a maquina reconhece a thread do servidor parada
 * dentro do System.exit.
 *
 * <p>Sem argumentos: e o teste (dispara as JVMs filhas). Com um argumento: e a JVM filha, no modo
 * {@code sigterm} (outra thread chama System.exit(143), o mesmo caminho do SIGTERM na JVM),
 * {@code stop} (parada normal), {@code exit-na-principal} (a thread do servidor chama
 * System.exit(7) no meio de um tick, como um mod ou plugin faria), {@code vigia} (a thread do
 * servidor esta travada e quem chama System.exit(9) e uma thread "vigia", como o watchdog do
 * Spigot), {@code outro-exit} (controle do vigia: quem chama a saida e uma thread qualquer) ou
 * {@code t2} (o salvamento da thread do servidor passa de T2: o gancho desiste e a JVM corta o
 * salvamento; e o achado I2 da revisao, comportamento documentado).
 */
public final class TesteParadaJvm {

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            filha(args[0]);
            return;
        }
        Verifica v = new Verifica("TesteParadaJvm");
        try {
            String[] r = rodar("sigterm");
            v.igual("143", r[0], "sigterm: a JVM terminou com o codigo do SIGTERM");
            v.sim(r[1].contains("motivo=" + MaquinaDeParada.G_SAIU + " ") && r[1].contains("saves=1 ") && r[1].contains("quem=Server thread ")
                    && r[1].contains("max=1 "), "sigterm: um salvamento, na thread do servidor, e o gancho esperou a saida: " + r[1]);
            v.sim(Long.parseLong(r[2]) < 6000, "sigterm: a JVM nao ficou presa (" + r[2] + " ms)");

            r = rodar("stop");
            v.igual("0", r[0], "stop: codigo de saida 0");
            v.sim(r[1].contains("motivo=" + MaquinaDeParada.G_JA_SAIU + " ") && r[1].contains("saves=1 ") && r[1].contains("quem=Server thread "),
                    "stop: um salvamento, na thread do servidor; o gancho nao salvou nem esperou: " + r[1]);

            r = rodar("exit-na-principal");
            v.igual("7", r[0], "exit na thread do servidor: codigo de saida do chamador");
            v.sim(r[1].contains("motivo=" + MaquinaDeParada.G_SERVIDOR_NO_EXIT + " "), "exit na thread do servidor: a maquina reconheceu (pilha com Runtime.exit): " + r[1]);
            v.sim(r[1].contains("saves=1 ") && r[1].contains("quem=Gancho "), "exit na thread do servidor: o gancho salvou, como no codigo original");
            long ms = r[1].contains(" ganchoMs=") ? Long.parseLong(r[1].replaceAll(".* ganchoMs=(\\d+).*", "$1")) : -1;
            v.sim(ms >= 0 && ms < 2500, "exit na thread do servidor: sem esperar T1 (4 s) nem a folga (" + ms + " ms no gancho)");

            r = rodar("vigia");
            v.igual("9", r[0], "vigia: codigo de saida de quem chamou");
            v.sim(r[1].contains("motivo=" + MaquinaDeParada.G_VIGIA + " "), "vigia: a maquina reconheceu que o vigia pediu a saida (pilha): " + r[1]);
            v.sim(r[1].contains("saves=1 ") && r[1].contains("quem=Gancho "), "vigia: o gancho salvou na hora, como no codigo original");
            ms = r[1].contains(" ganchoMs=") ? Long.parseLong(r[1].replaceAll(".* ganchoMs=(\\d+).*", "$1")) : -1;
            v.sim(ms >= 300 && ms < 1500, "vigia: gancho = so o salvamento, sem esperar T1 = 4 s antes nem a folga de 1,5 s depois (" + ms + " ms, de 300 a 1500)");

            r = rodar("outro-exit");
            v.igual("9", r[0], "controle do vigia: codigo de saida");
            v.sim(r[1].contains("motivo=" + MaquinaDeParada.G_ESGOTOU_LACO + " "),
                    "controle do vigia: saida pedida por uma thread qualquer com o servidor travado = espera T1 e so entao salva: " + r[1]);
            ms = r[1].contains(" ganchoMs=") ? Long.parseLong(r[1].replaceAll(".* ganchoMs=(\\d+).*", "$1")) : -1;
            v.sim(ms >= 4300 && ms < 5500, "controle do vigia: o gancho esperou T1 = 4 s, salvou e nao esperou a folga (" + ms + " ms, de 4300 a 5500)");

            r = rodar("t2");
            v.igual("143", r[0], "T2 estourado: a JVM terminou");
            v.sim(r[1].contains("motivo=" + MaquinaDeParada.G_ESGOTOU_SALVAR + " ") && r[1].contains("saves=1 ") && r[1].contains("max=1 "),
                    "T2 estourado: o gancho desistiu por T2 e nao salvou junto: " + r[1]);
            ms = r[1].contains(" ganchoMs=") ? Long.parseLong(r[1].replaceAll(".* ganchoMs=(\\d+).*", "$1")) : -1;
            v.sim(ms >= 590 && ms < 1100, "T2 estourado: o gancho levou ~T2 = 600 ms uma vez so, nao duas (" + ms + " ms, de 590 a 1100)");
            v.sim(!r[3].contains("SAVE-FIM"), "T2 estourado: a JVM saiu e CORTOU o salvamento em curso (comportamento documentado; T2 tem de ser folgado)");
        } catch (Throwable t) {
            v.erro("excecao inesperada no teste", t);
        }
        v.fim();
    }

    /** Roda a JVM filha. Devolve {codigo de saida, linha "GANCHO ...", duracao em ms, linhas "SAVE..."}. */
    static String[] rodar(String modo) throws Exception {
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(new File(System.getProperty("java.home"), "bin"), "java").getPath());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(TesteParadaJvm.class.getName());
        cmd.add(modo);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        long t0 = System.nanoTime();
        Process p = pb.start();
        String gancho = "(sem linha GANCHO)";
        StringBuilder saves = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        for (String l = br.readLine(); l != null; l = br.readLine()) {
            if (l.startsWith("GANCHO ")) {
                gancho = l;
            } else if (l.startsWith("SAVE")) {
                saves.append(l).append(' ');
            }
        }
        int codigo = p.waitFor();
        return new String[] {String.valueOf(codigo), gancho, String.valueOf((System.nanoTime() - t0) / 1000000L), saves.toString()};
    }

    // ------------------------------------------------------------------ a JVM filha

    static volatile boolean rodando = true;
    static boolean serverStopped;
    static final AtomicInteger SALVANDO = new AtomicInteger();
    static final AtomicInteger MAX = new AtomicInteger();
    static final AtomicInteger SAVES = new AtomicInteger();
    static volatile String quem = "";
    static volatile long saveMs = 300;

    /** O "vigia de travamento" de mentira: a maquina o reconhece pelo nome desta classe na pilha de quem chamou a saida. */
    static final class VigiaFalso implements Runnable {
        @Override
        public void run() {
            ServidorFalso.dormir(200);
            System.exit(9);
        }
    }

    static void filha(final String modo) {
        final boolean travada = modo.equals("vigia") || modo.equals("outro-exit");
        if (modo.equals("t2")) {
            saveMs = 3000; // bem acima de T2 = 600 ms
        }
        final MaquinaDeParada m = new MaquinaDeParada(4000, modo.equals("t2") ? 600 : 4000, 1500, null, 50, VigiaFalso.class.getName());
        final Thread principal = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int n = 0;
                    if (travada) {
                        ServidorFalso.dormir(60000); // um tick que nao acaba
                    }
                    while (rodando) {
                        ServidorFalso.dormir(5);
                        if (++n == 20 && modo.equals("exit-na-principal")) {
                            System.exit(7); // fica parada aqui ate os ganchos terminarem
                        }
                    }
                } finally {
                    stopServer(m);
                    serverStopped = true;
                    m.saindo();
                    System.exit(0);
                }
            }
        }, "Server thread");
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = System.nanoTime();
                int motivo = m.ganchoAntes(principal, new Runnable() {
                    @Override
                    public void run() {
                        rodando = false;
                    }
                }, true);
                stopServer(m);
                boolean depois = m.ganchoDepois(principal);
                System.out.println("GANCHO motivo=" + motivo + " saves=" + SAVES.get() + " quem=" + quem + " max=" + MAX.get() + " depois=" + depois
                        + " ganchoMs=" + (System.nanoTime() - t0) / 1000000L);
                System.out.flush();
            }
        }, "Gancho"));
        principal.start();
        if (modo.equals("vigia")) {
            new Thread(new VigiaFalso(), "Vigia").start();
        } else if (modo.equals("outro-exit")) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ServidorFalso.dormir(200);
                    System.exit(9);
                }
            }, "Outro").start();
        } else if (modo.equals("sigterm") || modo.equals("t2")) {
            ServidorFalso.dormir(100);
            System.exit(143);
        } else if (modo.equals("stop")) {
            ServidorFalso.dormir(100);
            rodando = false;
        }
    }

    /** A mesma cola do patch de MinecraftServer.stopServer(). */
    static void stopServer(MaquinaDeParada m) {
        int h = m.entrar(!serverStopped, "Server thread".equals(Thread.currentThread().getName()));
        if (h == 2) {
            return;
        }
        boolean ok = false;
        try {
            if (!serverStopped) {
                int c = SALVANDO.incrementAndGet();
                if (c > MAX.get()) {
                    MAX.set(c);
                }
                SAVES.incrementAndGet();
                quem = Thread.currentThread().getName();
                System.out.println("SAVE-INICIO na " + quem);
                ServidorFalso.dormir(saveMs);
                System.out.println("SAVE-FIM");
                SALVANDO.decrementAndGet();
            }
            ok = true;
        } finally {
            if (h == 1) {
                m.sair(ok);
            }
        }
    }
}
