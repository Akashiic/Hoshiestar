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
package hoshiestar.vigia;

import hoshiestar.api.Limitador;
import hoshiestar.api.Log;
import hoshiestar.api.Recursos;
import hoshiestar.api.Relogio;
import hoshiestar.api.ThreadServidor;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Recurso "vigia" (item 1.H): vigia de tick lento. Fachada: registro (fase de coremod), ajustes do
 * hoshiestar.yml, partida da thread (servidor de pe) e parada (inicio de stopServer).
 *
 * <p>Quem chama o que:
 * <ul>
 * <li>{@code hoshiestar.Catalogo}: {@link #registrar} (so JDK, config e hoshiestar.api);</li>
 * <li>{@code hoshiestar.Servidor.pronto()} (FMLServerStartedEvent): {@link #iniciar} com o contexto
 * do Minecraft ({@code ContextoMinecraft});</li>
 * <li>{@code MinecraftServer.stopServer()} (patch "// Hoshiestar"): {@link #parar()}, que nunca espera
 * mais que 0,8 s;</li>
 * <li>as marcacoes do caminho quente ficam em {@link Marcas}.</li>
 * </ul>
 */
public final class Vigia {

    public static final String ID = "vigia";

    static final Log LOG = Log.de(ID);

    private static volatile boolean ligado;
    private static volatile NucleoVigia.Ajustes ajustes;
    private static volatile int intervaloMinimoS = 60;
    private static volatile int maxRelatorios = 10;
    private static volatile int janelaMin = 60;
    private static volatile int maxArquivos = 200;
    private static volatile NucleoVigia nucleo;
    private static volatile DonosPorIndice donos;
    private static volatile boolean parado;

    private Vigia() {
    }

    /** Chamado por hoshiestar.Catalogo na fase de coremod. */
    public static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
        ligado = cfg.vigia_ligado;
        NucleoVigia.Ajustes a = new NucleoVigia.Ajustes();
        a.limiteMs = valido("vigia.limite_ms", cfg.vigia_limiteMs, 500, 600000, 2000);
        a.amostras = valido("vigia.amostras", cfg.vigia_amostras, 1, 20, 5);
        a.intervaloAmostrasMs = valido("vigia.intervalo_amostras_ms", cfg.vigia_intervaloAmostrasMs, 50, 10000, 500);
        a.quadros = valido("vigia.quadros", cfg.vigia_quadros, 5, 200, 40);
        a.nomeDoJogador = cfg.vigia_nomeDoJogador;
        a.carenciaMs = valido("vigia.carencia_segundos", cfg.vigia_carenciaSegundos, 0, 3600, 30) * 1000L;
        ajustes = a;
        intervaloMinimoS = valido("vigia.intervalo_minimo_segundos", cfg.vigia_intervaloMinimoSegundos, 1, 3600, 60);
        maxRelatorios = valido("vigia.max_relatorios", cfg.vigia_maxRelatorios, 1, 1000, 10);
        janelaMin = valido("vigia.janela_minutos", cfg.vigia_janelaMinutos, 1, 1440, 60);
        maxArquivos = valido("vigia.max_arquivos", cfg.vigia_maxArquivos, 10, 10000, 200);
        Marcas.anotarTicksAcimaDe(a.limiteMs);
        Recursos.registrar(ID, "vigia de tick lento: grava quem, onde e qual mod quando um tick passa do limite", ligado)
                .estado(new Recursos.Estado() {
                    @Override
                    public String linha() {
                        return linhaDeEstado();
                    }
                });
    }

    /** O valor se estiver em min..max; senao avisa e devolve o padrao. */
    static int valido(String chave, int v, int min, int max, int padrao) {
        if (v < min || v > max) {
            try {
                LOG.a(chave + " = " + v + " fora de " + min + ".." + max + "; usando " + padrao);
            } catch (Throwable ignorado) {
                // log nunca derruba o boot
            }
            return padrao;
        }
        return v;
    }

    public static boolean ligado() {
        return ligado;
    }

    /** Ajustes conferidos no registro (para os testes isolados). */
    public static NucleoVigia.Ajustes ajustesAtuais() {
        return ajustes;
    }

    /**
     * Inicia a thread do vigia (servidor de pe, FMLServerStartedEvent). Desligado no hoshiestar.yml, ou
     * com o servidor ja parando: nada. Nunca lanca.
     */
    public static synchronized void iniciar(NucleoVigia.Contexto contexto) {
        try {
            if (!ligado || nucleo != null || parado) {
                return;
            }
            if (!Marcas.LIGADO) {
                LOG.a("as marcacoes do tick estao desligadas (config ilegivel quando carregaram?); a thread do vigia nao foi iniciada");
                return;
            }
            NucleoVigia.Ajustes a = ajustes != null ? ajustes : new NucleoVigia.Ajustes();
            List<File> pastas = new ArrayList<File>();
            pastas.add(new File("mods"));
            pastas.add(new File("plugins"));
            DonosPorIndice d = new DonosPorIndice(pastas, jarsDoServidor());
            donos = d;
            // reserva dentro do servidor, nao em /tmp (revisao M3: pasta comum a todos os usuarios da maquina)
            NucleoVigia n = new NucleoVigia(a, new FonteServidor(), contexto, new AmostradorJvm(),
                    new SaidaEmArquivo(new File("hoshiestar"), new File("logs", "hoshiestar-vigia"), maxArquivos),
                    FonteGcJvm.criar(), d,
                    new LimiteDeRelatorios(intervaloMinimoS * 1000L, maxRelatorios, janelaMin * 60000L), relogioDoSistema());
            nucleo = n;
            n.iniciar();
            LOG.i("vigia ligado: limite " + a.limiteMs + " ms, " + a.amostras + " amostra(s) a cada " + a.intervaloAmostrasMs
                    + " ms, no maximo " + maxRelatorios + " relatorio(s) por " + janelaMin + " min (intervalo minimo "
                    + intervaloMinimoS + " s), carencia de " + (a.carenciaMs / 1000L) + " s depois do boot; relatorios no log e em hoshiestar/vigia-*.txt");
        } catch (Throwable t) {
            LOG.e("nao consegui iniciar o vigia (o jogo segue normal, sem vigia)", t);
        }
    }

    /** Inicio de stopServer() (qualquer thread). Nunca espera mais que 0,8 s; nunca lanca. */
    public static void parar() {
        try {
            NucleoVigia n;
            synchronized (Vigia.class) { // mesma trava de iniciar(): depois daqui nenhuma thread nova comeca
                parado = true;
                n = nucleo;
            }
            if (n != null && !n.parando()) {
                n.parar();
            }
        } catch (Throwable ignorado) {
            // a parada do servidor nunca depende do vigia
        }
    }

    /** Linha do "hoshie status" e do fim do boot. */
    public static String linhaDeEstado() {
        NucleoVigia n = nucleo;
        if (n == null) {
            return parado ? "parado" : "thread ainda nao iniciada (inicia quando o servidor termina de ligar)";
        }
        DonosPorIndice d = donos;
        String r = d != null ? d.resumo() : null;
        return n.linha() + (r != null ? "; " + r : "; indice de jars: monta no primeiro relatorio");
    }

    /** O nucleo em uso (para o subcomando de teste e o status), ou null. */
    public static NucleoVigia nucleo() {
        return nucleo;
    }

    /** O server.jar (pelo java.class.path) e a pasta libraries/: jars do servidor, neutros no indice. */
    static List<File> jarsDoServidor() {
        List<File> l = new ArrayList<File>();
        try {
            String cp = System.getProperty("java.class.path", "");
            for (String e : cp.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (e.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                    l.add(new File(e));
                }
            }
        } catch (Throwable ignorado) {
            // sem o server.jar no indice: so texto
        }
        l.add(new File("libraries"));
        return l;
    }

    static Limitador.Tempo relogioDoSistema() {
        return new Limitador.Tempo() {
            @Override
            public long nanos() {
                return System.nanoTime();
            }
        };
    }

    // ------------------------------------------------------------------ implementacoes de servidor

    /** Le o estado do tick escrito pelos patches (Relogio, Marcas, ThreadServidor). */
    public static final class FonteServidor implements NucleoVigia.FonteDeTick {
        @Override
        public long seq() {
            return Relogio.ticks;
        }

        @Override
        public long inicioNanos() {
            return Marcas.inicioTick;
        }

        @Override
        public long seqFim() {
            return Marcas.seqFim;
        }

        @Override
        public long duracaoFim() {
            return Marcas.duracaoFim;
        }

        @Override
        public Thread thread() {
            return ThreadServidor.thread();
        }

        @Override
        public Object entidade() {
            return Marcas.ENTIDADE.get();
        }

        @Override
        public Object tile() {
            return Marcas.TILE.get();
        }
    }

    /**
     * Thread.getStackTrace de outra thread: no Java 21 a JVM para so a thread alvo por um instante
     * (handshake); no Java 8 e uma parada geral curta de todas as threads (safepoint). So acontece com o
     * tick ja acima do limite e no maximo vigia.amostras vezes por relatorio.
     */
    public static final class AmostradorJvm implements NucleoVigia.Amostrador {
        @Override
        public StackTraceElement[] pilha(Thread t) {
            return t.getStackTrace();
        }
    }

    /** Coletores de lixo da JVM pelos MXBeans (tempo acumulado e numero de coletas de cada um). */
    public static final class FonteGcJvm implements NucleoVigia.FonteGc {
        private final GarbageCollectorMXBean[] beans;

        private FonteGcJvm(GarbageCollectorMXBean[] beans) {
            this.beans = beans;
        }

        public static FonteGcJvm criar() {
            try {
                List<GarbageCollectorMXBean> l = ManagementFactory.getGarbageCollectorMXBeans();
                return new FonteGcJvm(l.toArray(new GarbageCollectorMXBean[0]));
            } catch (Throwable t) {
                return new FonteGcJvm(new GarbageCollectorMXBean[0]);
            }
        }

        @Override
        public int quantos() {
            return beans.length;
        }

        @Override
        public String nome(int i) {
            return beans[i].getName();
        }

        @Override
        public long tempoMs(int i) {
            return Math.max(0L, beans[i].getCollectionTime());
        }

        @Override
        public long coletas(int i) {
            return Math.max(0L, beans[i].getCollectionCount());
        }
    }

    /** Donos por {@link IndiceDeJars}, montado pela thread do vigia na primeira consulta. */
    public static final class DonosPorIndice implements NucleoVigia.Donos {
        private final List<File> pastas;
        private final List<File> doServidor;
        private volatile IndiceDeJars indice;

        public DonosPorIndice(List<File> pastas) {
            this(pastas, null);
        }

        /** {@code doServidor}: jars e pastas do servidor (pacotes neutros; libraries/ ate 12 niveis). */
        public DonosPorIndice(List<File> pastas, List<File> doServidor) {
            this.pastas = pastas;
            this.doServidor = doServidor;
        }

        @Override
        public String dono(String classe) {
            IndiceDeJars i = indice;
            if (i == null) {
                i = IndiceDeJars.montar(pastas, 2, doServidor, 12);
                indice = i;
            }
            return i.dono(classe);
        }

        @Override
        public String resumo() {
            IndiceDeJars i = indice;
            return i == null ? null : "indice de jars: " + i.jars() + " jars, " + i.pacotes() + " pacotes ("
                    + i.jarsDoServidor() + " jars do servidor, neutros), montado em " + i.msParaMontar() + " ms" + (i.erros() > 0 ? ", " + i.erros() + " jar(s) ilegiveis" : "");
        }
    }
}
