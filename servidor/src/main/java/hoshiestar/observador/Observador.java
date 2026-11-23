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
package hoshiestar.observador;

import hoshiestar.Hoshiestar;
import hoshiestar.api.Log;
import hoshiestar.vigia.IndiceDeJars;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * O observador da Etapa 2 (recurso "observador"): quando o admin manda ({@code hoshie observar <segundos>}), anota, em
 * tabelas de contagem, "o mod X, executando a partir da regiao A, tocou a posicao P da regiao B". So LE e ANOTA: nao
 * barra nada, nao roteia nada, nao muda o que nenhum metodo devolve.
 *
 * <p>Os patches no jogo so chamam {@link GanchosObservador} quando {@link #ATIVO} e verdadeiro (um teste de campo
 * estatico; desligado nada mais roda). {@link #ATIVO} so e escrito pela thread do servidor. Os pedidos do comando (que
 * pode vir de outra thread, como o RCON) so deixam um numero em {@link #pedido}; quem inicia e para e o inicio do tick.
 *
 * <p>Esta classe nao cita o Minecraft (os testes isolados a exercitam inteira).
 */
public final class Observador {

    public static final String ID = "observador";
    public static final int SEG_MIN = 5;
    public static final int SEG_MAX = 3600;
    public static final int SEG_PADRAO = 300;

    /** Verdadeiro so enquanto observa. Escrito so pela thread do servidor. */
    public static boolean ATIVO;

    /**
     * Prototipo da lista de tiles (plano, secao 4): {@code observador.ligado} E {@code observador.lista}. Lido so no boot
     * (fase de coremod, antes de existir mundo); com ele, cada mundo cria {@code loadedTileEntityList} como
     * {@link ListaObservadaHashed}. Falso = nenhuma troca de objeto (o comportamento original).
     */
    public static boolean LISTA;

    /** O nucleo da observacao em andamento (null fora dela). Quem le de outra thread usa so os contadores atomicos. */
    static volatile Nucleo N;

    // ---- config (fase de coremod) ----
    private static volatile boolean habilitado;
    private static volatile int amostraPilha = 256;
    private static volatile double limiteCustoPct = 5.0;

    // ---- dependencias trocaveis (os testes trocam) ----
    private static volatile Jogo jogo;
    private static volatile Pilha.Donos donos;
    private static volatile Nucleo.Relogio relogio = Nucleo.SISTEMA;
    private static volatile boolean usarIndiceDeJars = true;

    // ---- estado ----
    private static final AtomicInteger pedido = new AtomicInteger();
    private static volatile boolean montandoIndice;
    private static volatile String ultimoResultado;
    private static volatile Thread gravacao;
    /** A thread que iniciou a observacao (a do servidor: so ela roda inicioTickServidor); so ela finaliza. */
    private static volatile Thread threadDaObservacao;
    /** Motivo do pedido de parada feito por outra thread (falha interna); lido pelo inicio do tick. */
    private static volatile String motivoDoPedido;
    private static long prazoNs;
    private static long inicioNs;
    private static int segundosPedidos;
    private static String inicioTexto;
    private static long ticksNoInicio;

    private Observador() {
    }

    // =====================================================================================================
    // Configuracao e instalacao
    // =====================================================================================================

    /** Chamado pelo registro (fase de coremod). Valores fora da faixa viram o padrao. */
    public static void configurar(boolean ligado, int amostra, double limitePct) {
        habilitado = ligado;
        amostraPilha = amostra >= 1 && amostra <= 1000000 ? amostra : 256;
        limiteCustoPct = limitePct >= 0.5 && limitePct <= 50.0 ? limitePct : 5.0;
    }

    /** Chamado pelo registro (fase de coremod), antes de existir mundo. */
    public static void configurarLista(boolean ligada) {
        LISTA = ligada;
    }

    public static boolean habilitado() {
        return habilitado;
    }

    /** Servidor de pe: entrega o jogo (fotos e pasta de saida). */
    public static void definirJogo(Jogo j) {
        jogo = j;
    }

    /** Para os testes. */
    static void definirDonos(Pilha.Donos d) {
        donos = d;
        usarIndiceDeJars = d == null;
    }

    static void definirRelogio(Nucleo.Relogio r) {
        relogio = r != null ? r : Nucleo.SISTEMA;
    }

    /** Para os testes: volta tudo ao estado de antes do boot. */
    static void reiniciarParaTeste() {
        ATIVO = false;
        N = null;
        LISTA = false;
        pedido.set(0);
        habilitado = false;
        amostraPilha = 256;
        limiteCustoPct = 5.0;
        jogo = null;
        donos = null;
        usarIndiceDeJars = true;
        relogio = Nucleo.SISTEMA;
        montandoIndice = false;
        ultimoResultado = null;
        threadDaObservacao = null;
        motivoDoPedido = null;
        aguardarGravacao(5000);
    }

    // =====================================================================================================
    // Comando
    // =====================================================================================================

    /** Texto de ajuda do subcomando. */
    public static String ajuda() {
        return "observar <segundos " + SEG_MIN + "-" + SEG_MAX + "> | observar parar | observar status - anota quem toca onde,"
                + " entre regioes do mapa (grava hoshiestar/observador-*.json e .txt)";
    }

    /** Executa {@code hoshie observar ...} e devolve as linhas a mostrar. Pode ser chamado de qualquer thread. */
    public static List<String> comando(String[] args) {
        List<String> r = new ArrayList<String>();
        if (!habilitado) {
            r.add("O observador esta desligado no hoshiestar.yml (observador.ligado: false). Ligue, reinicie o servidor e tente de novo.");
            return r;
        }
        if (args.length == 0) {
            r.add("Uso: hoshie " + ajuda());
            return r;
        }
        String a = args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1) {
            r.add("hoshie observar aceita um argumento so (recebeu: '" + String.join(" ", args) + "').");
            return r;
        }
        if (a.equals("status")) {
            r.addAll(status());
            return r;
        }
        if (a.equals("parar")) {
            if (!ATIVO && pedido.get() <= 0 && !montandoIndice) {
                r.add("Nao ha observacao em andamento.");
                return r;
            }
            pedido.set(-1);
            r.add("Pedido de parada enviado: o relatorio sai no proximo tick.");
            return r;
        }
        int s;
        try {
            s = Integer.parseInt(a);
        } catch (NumberFormatException e) {
            r.add("Argumento invalido: '" + args[0] + "'. Uso: hoshie " + ajuda());
            return r;
        }
        if (s < SEG_MIN || s > SEG_MAX) {
            r.add("Segundos fora da faixa " + SEG_MIN + "-" + SEG_MAX + ": " + s);
            return r;
        }
        if (ATIVO || pedido.get() > 0 || montandoIndice) {
            r.add("Ja ha uma observacao em andamento ou pedida. Use 'hoshie observar status' ou 'hoshie observar parar'.");
            return r;
        }
        if (donos == null && usarIndiceDeJars) {
            montarIndiceEPedir(s);
            r.add("Montando o indice de jars (alguns segundos, em outra thread); a observacao de " + s
                    + " s comeca sozinha no tick seguinte.");
        } else {
            pedido.set(s);
            r.add("Observacao de " + s + " s pedida: comeca no proximo tick.");
        }
        return r;
    }

    private static void montarIndiceEPedir(final int segundos) {
        montandoIndice = true;
        Thread t = new Thread("Hoshiestar observador indice") {
            @Override
            public void run() {
                try {
                    List<File> pastas = new ArrayList<File>();
                    pastas.add(new File("mods"));
                    pastas.add(new File("plugins"));
                    List<File> doServidor = new ArrayList<File>();
                    String cp = System.getProperty("java.class.path", "");
                    for (String e : cp.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                        if (e.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                            doServidor.add(new File(e));
                        }
                    }
                    doServidor.add(new File("libraries"));
                    final IndiceDeJars ind = IndiceDeJars.montar(pastas, 2, doServidor, 12);
                    donos = new Pilha.Donos() {
                        @Override
                        public String dono(String classe) {
                            return ind.dono(classe);
                        }
                    };
                    Log.de(ID).i("indice de jars montado: " + ind.jars() + " jars, " + ind.pacotes() + " pacotes em "
                            + ind.msParaMontar() + " ms");
                    pedido.set(segundos);
                } catch (Throwable e) {
                    Log.de(ID).e("nao consegui montar o indice de jars; a observacao nao comecou", e);
                    ultimoResultado = "nao comecou: erro ao montar o indice de jars (" + e.getClass().getSimpleName() + ")";
                } finally {
                    montandoIndice = false;
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /** Linhas do {@code hoshie observar status}. */
    public static List<String> status() {
        List<String> r = new ArrayList<String>();
        Nucleo n = N;
        if (ATIVO && n != null) {
            long restanteNs = prazoNs - relogio.nano();
            r.add("Observando: " + n.ticks() + " ticks, " + n.acessosCompletos() + " acessos registrados, "
                    + n.leiturasRapidas() + " leituras na mesma secao, " + n.cargasDeChunk() + " cargas de chunk, "
                    + n.totalOutraThread() + " acessos de outra thread; custo estimado "
                    + String.format(Locale.ROOT, "%.2f", n.custoMedioPct()) + "% do tick (limite " + limiteCustoPct
                    + "%); faltam ~" + Math.max(0, restanteNs / 1000000000L) + " s.");
        } else if (montandoIndice) {
            r.add("Montando o indice de jars; a observacao comeca em seguida.");
        } else if (pedido.get() > 0) {
            r.add("Observacao pedida; comeca no proximo tick.");
        } else {
            r.add("Parado.");
        }
        String u = ultimoResultado;
        if (u != null) {
            r.add("Ultimo resultado: " + u);
        }
        return r;
    }

    /** A linha de estado do recurso (status e boot). */
    public static String linhaDeEstado() {
        Nucleo n = N;
        if (ATIVO && n != null) {
            return "observando (" + n.ticks() + " ticks, custo estimado " + String.format(Locale.ROOT, "%.2f", n.custoMedioPct()) + "%)";
        }
        return "parado (use hoshie observar <segundos>); amostra de pilha 1 em " + amostraPilha + ", limite de custo "
                + limiteCustoPct + "%" + (LISTA ? "; lista de tiles observada (prototipo)" : "")
                + (ultimoResultado != null ? "; ultimo: " + ultimoResultado : "");
    }

    // =====================================================================================================
    // Ticks (thread do servidor)
    // =====================================================================================================

    /** Primeira coisa de MinecraftServer.tick (depois dos relogios): aplica pedidos e abre o tick do observador. */
    public static void inicioTickServidor() {
        int p = pedido.get();
        if (p != 0) {
            pedido.set(0);
            if (p > 0) {
                iniciar(p);
            } else if (ATIVO) {
                String m = motivoDoPedido;
                motivoDoPedido = null;
                finalizar(m != null ? m : "parado pelo comando", false);
            }
        }
        if (ATIVO) {
            Nucleo n = N;
            if (n != null) {
                n.inicioTick(relogio.nano());
            }
        }
        if (LISTA) {
            ListaObservada.inicioTick();
        }
    }

    /** Ultima coisa de MinecraftServer.tick: foto de regioes (a cada 30 s), valvula e fim por tempo. */
    public static void fimTickServidor() {
        if (!ATIVO) {
            return;
        }
        Nucleo n = N;
        if (n == null) {
            ATIVO = false;
            return;
        }
        long agora = relogio.nano();
        Jogo j = jogo;
        if (j != null && n.fotoDevida(agora)) {
            try {
                n.tirarFoto(j.fotografar());
            } catch (Throwable t) {
                falha("a foto das regioes falhou", t);
                return;
            }
        }
        if (LISTA && j != null) {
            try {
                j.conferirListas(n.lista);
            } catch (Throwable t) {
                falha("a conferencia das listas de tiles falhou", t);
                return;
            }
        }
        boolean parar = n.fimTick(relogio.nano());
        if (parar) {
            finalizar(n.motivoValvula, false);
        } else if (agora >= prazoNs) {
            finalizar("o tempo pedido acabou", false);
        }
    }

    /** Parada normal do servidor: grava o que ja tem e espera a gravacao terminar (ate 10 s). */
    public static void encerrarNoDesligamento() {
        if (ATIVO) {
            finalizar("o servidor foi parado", true);
        }
        aguardarGravacao(10000);
    }

    /**
     * Erro interno do observador: desliga e grava o que tem (nunca derruba o tick). So a thread do servidor finaliza
     * (as tabelas T1/T2 sao dela); vindo de outra thread, so registra no log e pede a parada para o proximo tick.
     */
    static void falha(String quando, Throwable t) {
        try {
            Log.de(ID).e("erro no observador (" + quando + "); parando e gravando o que ja tem", t);
            String motivo = "erro interno: " + quando + " (" + t.getClass().getSimpleName() + ")";
            if (Thread.currentThread() != threadDaObservacao) {
                if (ATIVO) {
                    motivoDoPedido = motivo;
                    pedido.set(-1);
                }
                return;
            }
            if (ATIVO) {
                finalizar(motivo, false);
            }
        } catch (Throwable ignorado) {
            ATIVO = false;
        }
    }

    private static void iniciar(int segundos) {
        if (ATIVO) {
            return;
        }
        Pilha.Donos d = donos;
        if (d == null) {
            d = new Pilha.Donos() {
                @Override
                public String dono(String classe) {
                    return null;
                }
            };
        }
        long agora = relogio.nano();
        Nucleo n = new Nucleo(d, amostraPilha, limiteCustoPct, relogio);
        segundosPedidos = segundos;
        inicioNs = agora;
        prazoNs = agora + segundos * 1000000000L;
        inicioTexto = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        N = n;
        threadDaObservacao = Thread.currentThread();
        motivoDoPedido = null;
        ATIVO = true;
        Log.de(ID).i("observacao iniciada por " + segundos + " s (amostra de pilha 1 em " + amostraPilha + ", limite de custo "
                + limiteCustoPct + "%)");
    }

    private static void finalizar(final String motivo, boolean noDesligamento) {
        final Nucleo n = N;
        ATIVO = false;
        N = null;
        if (n == null) {
            return;
        }
        n.liberarContextos(); // a thread de gravacao guarda n; nao pode prender mundos
        final Relatorio.Meta m = new Relatorio.Meta();
        m.versao = Hoshiestar.VERSAO;
        m.inicio = inicioTexto;
        m.segundosPedidos = segundosPedidos;
        m.duracaoSegundos = (relogio.nano() - inicioNs) / 1e9;
        m.motivoFim = motivo;
        m.pararNoMeio = noDesligamento || m.duracaoSegundos + 1.0 < segundosPedidos;
        final Jogo j = jogo;
        final String nome = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        Thread t = new Thread("Hoshiestar observador gravacao") {
            @Override
            public void run() {
                try {
                    File pasta = j != null ? j.pastaDeSaida() : null;
                    if (pasta == null) {
                        pasta = new File("hoshiestar");
                    }
                    File[] f = Relatorio.gravar(n, m, pasta, nome);
                    ultimoResultado = "gravado " + f[0].getName() + " e " + f[1].getName() + " (" + motivo + ")";
                    Log.de(ID).i("observacao terminou (" + motivo + "): " + n.ticks() + " ticks, " + n.acessosCompletos()
                            + " acessos registrados; relatorio em " + pasta.getPath() + "/" + f[0].getName() + " e .txt");
                } catch (Throwable e) {
                    ultimoResultado = "terminou (" + motivo + ") mas o relatorio NAO foi gravado: " + e.getClass().getSimpleName();
                    Log.de(ID).e("nao consegui gravar o relatorio do observador", e);
                } finally {
                    if (gravacao == Thread.currentThread()) {
                        gravacao = null; // a thread guarda o nucleo (val$n); soltar para o GC
                    }
                }
            }
        };
        t.setDaemon(false);
        gravacao = t;
        t.start();
    }

    /** Espera a thread de gravacao (ate {@code ms}). */
    static void aguardarGravacao(long ms) {
        Thread t = gravacao;
        if (t != null) {
            try {
                t.join(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Para os testes: o texto do ultimo resultado. */
    static String ultimoResultado() {
        return ultimoResultado;
    }
}
