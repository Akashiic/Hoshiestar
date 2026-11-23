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
package hoshiestar.parada;

import hoshiestar.api.Limitador;
import hoshiestar.api.Log;
import hoshiestar.api.Recursos;
import hoshiestar.api.ThreadServidor;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recurso "parada" (item 1.G, achado D-24): parada por SIGTERM sem crash e sem salvamento duplo.
 * Esta classe e a fachada que os patches chamam: guarda o interruptor, os tres tempos do
 * hoshiestar.yml e a unica {@link MaquinaDeParada} do processo. A logica fica toda na maquina.
 *
 * <p>Quem chama o que (patches "// Hoshiestar" no fork):
 * <ul>
 * <li>{@code MinecraftServer.stopServer()}: {@link #entrar} no comeco e {@link #sair} num finally
 * (a fachada diz a maquina se quem chama e a thread do servidor, por {@link ThreadServidor});</li>
 * <li>{@code DedicatedServer.systemExitNow()}: {@link #saindo} antes do System.exit;</li>
 * <li>{@code ServerShutdownThread.run()} (o gancho de desligamento da JVM): {@link #ganchoAntes}
 * antes do stopServer() dele e {@link #ganchoDepois} depois.</li>
 * </ul>
 * Com o interruptor desligado nenhum desses metodos faz nada e o codigo original roda como hoje.
 *
 * <p>Roda cedo (registro na fase de coremod): so JDK, config e hoshiestar.api; nada do Minecraft.
 */
public final class Parada {

    public static final String ID = "parada";

    static final Log LOG = Log.de(ID);

    /** No maximo 3 avisos de erro interno por minuto. */
    static final Limitador LIMITE = new Limitador(3, 60000L);

    static final AtomicInteger FALHAS = new AtomicInteger();

    public static final int T1_PADRAO_S = 60;
    public static final int T2_PADRAO_S = 600;
    public static final int FOLGA_PADRAO_S = 30;

    private static volatile boolean lido;
    private static boolean ligado;
    private static volatile MaquinaDeParada maquina;

    /** Verdadeiro desde que o gancho de desligamento comecou: a JVM esta saindo e o log pode ja ter fechado. */
    private static volatile boolean jvmSaindo;

    private Parada() {
    }

    /** Chamado por hoshiestar.Catalogo na fase de coremod. */
    public static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
        fixar(cfg);
        Recursos.registrar(ID, "parada por SIGTERM sem crash e sem salvamento duplo (D-24)", ligado)
                .estado(new Recursos.Estado() {
                    @Override
                    public String linha() {
                        return linhaDeEstado();
                    }
                });
    }

    private static synchronized void fixar(HoshiestarConfig cfg) {
        if (lido) {
            return;
        }
        int t1 = segundosValidos("parada.espera_laco_segundos", cfg.parada_esperaLacoSegundos, 1, 3600, T1_PADRAO_S);
        int t2 = segundosValidos("parada.espera_salvar_segundos", cfg.parada_esperaSalvarSegundos, 1, 7200, T2_PADRAO_S);
        int folga = segundosValidos("parada.folga_saida_segundos", cfg.parada_folgaSaidaSegundos, 0, 600, FOLGA_PADRAO_S);
        maquina = new MaquinaDeParada(t1 * 1000L, t2 * 1000L, folga * 1000L, new AvisosNoLog());
        ligado = cfg.parada_ligado;
        lido = true;
    }

    /** O valor se estiver em {@code min..max}; senao avisa e devolve o padrao. */
    public static int segundosValidos(String chave, int valor, int min, int max, int padrao) {
        if (valor < min || valor > max) {
            try {
                LOG.a(chave + " = " + valor + " fora de " + min + ".." + max + "; usando " + padrao);
            } catch (Throwable ignorado) {
                // log nunca derruba o boot
            }
            return padrao;
        }
        return valor;
    }

    /** O interruptor. Se o registro ainda nao rodou, le o config atual. */
    public static boolean ligado() {
        if (!lido) {
            fixar(HoshiestarConfig.atual());
        }
        return ligado;
    }

    // Os cinco metodos abaixo sao chamados pelos patches. Nenhum deixa escapar excecao: uma falha
    // nossa vira um aviso (no maximo 3 por minuto) e o codigo original segue. Os patches ainda tem
    // o proprio try/catch, para o caso de esta classe nem carregar.

    /** Comeco de stopServer(). Devolve MaquinaDeParada.ORIGINAL (0), DONO (1) ou PULAR (2). */
    public static int entrar(boolean vaiSalvar) {
        try {
            return ligado() ? maquina.entrar(vaiSalvar, ThreadServidor.souAThreadDoServidor()) : MaquinaDeParada.ORIGINAL;
        } catch (Throwable t) {
            falhou("entrar", t);
            return MaquinaDeParada.ORIGINAL;
        }
    }

    /** Fim de stopServer(), so para quem recebeu DONO. */
    public static void sair(boolean ok) {
        try {
            if (ligado()) {
                maquina.sair(ok);
            }
        } catch (Throwable t) {
            falhou("sair", t);
        }
    }

    /** DedicatedServer.systemExitNow(), antes do System.exit. */
    public static void saindo() {
        try {
            if (ligado()) {
                maquina.saindo();
            }
        } catch (Throwable t) {
            falhou("saindo", t);
        }
    }

    /**
     * Gancho de desligamento, antes do stopServer() dele. {@code iniciou} = o servidor chegou ao
     * estado SERVER_STARTED do FML (a mesma pergunta que o stopServer() faz antes de salvar).
     */
    public static void ganchoAntes(Thread principal, Runnable pedirParada, boolean iniciou) {
        try {
            if (ligado()) {
                jvmSaindo = true;
                maquina.ganchoAntes(principal, pedirParada, iniciou);
            }
        } catch (Throwable t) {
            falhou("ganchoAntes", t);
        }
    }

    /** Gancho de desligamento, depois do stopServer() dele. */
    public static void ganchoDepois(Thread principal) {
        try {
            if (ligado()) {
                maquina.ganchoDepois(principal);
            }
        } catch (Throwable t) {
            falhou("ganchoDepois", t);
        }
    }

    private static void falhou(String onde, Throwable t) {
        FALHAS.incrementAndGet();
        try {
            if (LIMITE.permitir(onde)) {
                LOG.e("erro interno em " + onde + " (segue o codigo original)", t);
            }
        } catch (Throwable ignorado) {
            // log nunca atrapalha a parada
        }
    }

    /** Linha do "hoshie status" e do fim do boot. */
    public static String linhaDeEstado() {
        MaquinaDeParada m = maquina;
        int f = FALHAS.get();
        return (m == null ? "nao iniciada" : m.linha()) + (f > 0 ? "; ERROS INTERNOS: " + f + " (ver o log)" : "");
    }

    /** Uso dos testes isolados: troca a maquina e o interruptor sem config. */
    public static synchronized void configurarParaTeste(boolean ligadoTeste, MaquinaDeParada maquinaTeste) {
        maquina = maquinaTeste;
        ligado = ligadoTeste;
        lido = true;
    }

    /**
     * Manda os avisos da maquina para o log do servidor; nos avisos de desistencia, com a pilha da
     * thread do servidor. Com a JVM saindo, o sistema de log (log4j) tem o proprio gancho de
     * desligamento e pode parar de escrever no console a qualquer momento; por isso, dai em diante,
     * cada linha vai TAMBEM direto para a saida de erro do processo (o console de verdade).
     */
    private static final class AvisosNoLog implements MaquinaDeParada.Avisos {

        private final PrintStream cru = new PrintStream(new FileOutputStream(FileDescriptor.err), true);

        @Override
        public void info(String msg) {
            direto("INFO", msg);
            LOG.i(msg);
        }

        @Override
        public void aviso(String msg, Thread principal) {
            StringBuilder sb = new StringBuilder(msg);
            if (principal != null) {
                StackTraceElement[] pilha = principal.getStackTrace();
                sb.append(" | thread do servidor (").append(principal.getName()).append(", ").append(principal.getState()).append(") em:");
                for (int i = 0; i < pilha.length && i < 25; i++) {
                    sb.append("\n    at ").append(pilha[i]);
                }
            }
            direto("AVISO", sb.toString());
            LOG.a(sb.toString());
        }

        private void direto(String nivel, String msg) {
            if (!jvmSaindo) {
                return;
            }
            try {
                cru.println("[Hoshiestar] [parada] [direto/" + nivel + "] [" + Thread.currentThread().getName() + "] " + msg);
            } catch (Throwable ignorado) {
                // o aviso direto nunca atrapalha a parada
            }
        }
    }
}
