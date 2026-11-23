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
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Verdadeiro so enquanto observa. Escrito so pela thread do servidor. Nao e volatil de proposito (o teste dos ganchos
     * fica o mais barato possivel): outra thread pode ver a mudanca com atraso, o que so muda a contagem dela (m7 da
     * revisao de concorrencia). Quem decide algo fora da thread do servidor usa {@link #N}.
     */
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
    /**
     * Cobre "montandoIndice" e "cancelarIndice" (o 'parar' e o fim da montagem nao podem se cruzar sem um ver o outro) e,
     * desde a revisao de concorrencia (m3), todo "conferir e agir" do pedido: o corpo do comando e a aplicacao do pedido no
     * inicio do tick. Dentro dela so ha escrita de campo, criar thread e (na thread do servidor) iniciar/finalizar; ninguem
     * espera nada com ela na mao. O tick so a pega quando ha pedido (um teste de campo atomico antes).
     */
    private static final Object TRAVA_INDICE = new Object();
    /** 'hoshie observar parar' chegou enquanto o indice era montado: a observacao nao comeca (so sob TRAVA_INDICE). */
    private static boolean cancelarIndice;
    /**
     * Hoshiestar (m8 da revisao de concorrencia): prazo da montagem do indice de jars. Se a thread do indice travar (a
     * leitura de um jar que nao volta), um novo 'observar' depois deste prazo abandona a montagem velha em vez de o
     * observador recusar pedidos ate reiniciar. Cada montagem tem um numero; a abandonada nao pede nem cancela nada.
     * Os dois campos so sob TRAVA_INDICE.
     */
    static final long PRAZO_INDICE_NS = 120L * 1000000000L;
    private static int geracaoIndice;
    private static long indiceDesdeNs;
    /** Para os testes: roda na thread do indice, depois de montado e antes de pedir a observacao. */
    static volatile Runnable antesDePedirDoIndice;
    private static volatile String ultimoResultado;
    private static volatile Thread gravacao;
    /** A thread que iniciou a observacao (a do servidor: so ela roda inicioTickServidor); so ela finaliza. */
    private static volatile Thread threadDaObservacao;
    /**
     * Pedido de parada feito por outra thread (falha interna), com o nucleo da observacao que falhou: o inicio do tick so
     * para se a observacao de pe ainda for essa (m2 da revisao de concorrencia). Nao passa por {@link #pedido}: assim um
     * gancho antigo nao para a observacao seguinte nem apaga um pedido de inicio.
     */
    private static final AtomicReference<PedidoDeFalha> falhaPedida = new AtomicReference<PedidoDeFalha>();

    private static final class PedidoDeFalha {
        final Nucleo n;
        final String motivo;

        PedidoDeFalha(Nucleo n, String motivo) {
            this.n = n;
            this.motivo = motivo;
        }
    }
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
        synchronized (TRAVA_INDICE) {
            montandoIndice = false;
            cancelarIndice = false;
            geracaoIndice++; // uma montagem que sobrou de outro teste nao pede nada
        }
        antesDePedirDoIndice = null;
        ultimoResultado = null;
        threadDaObservacao = null;
        falhaPedida.set(null);
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
            synchronized (TRAVA_INDICE) {
                if (montandoIndice) {
                    cancelarIndice = true; // a thread do indice ainda vai pedir a observacao: avisa que nao
                    r.add("Pedido cancelado: a observacao nao vai comecar (o indice de jars ainda estava sendo montado).");
                    return r;
                }
                // Hoshiestar (m3): N e volatil (ATIVO nao e); conferir e pedir sob a mesma trava que o inicio do tick usa
                if (N == null && pedido.get() <= 0) {
                    r.add("Nao ha observacao em andamento.");
                    return r;
                }
                pedido.set(-1);
            }
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
        // Hoshiestar (m3): conferir e agir sob a trava; dois 'observar' juntos (console e RCON) nao passam os dois
        synchronized (TRAVA_INDICE) {
            boolean indiceTravado = montandoIndice && relogio.nano() - indiceDesdeNs > PRAZO_INDICE_NS;
            if (N != null || pedido.get() > 0 || (montandoIndice && !indiceTravado)) {
                r.add("Ja ha uma observacao em andamento ou pedida. Use 'hoshie observar status' ou 'hoshie observar parar'.");
                return r;
            }
            if (indiceTravado) {
                geracaoIndice++; // a montagem velha, se um dia terminar, nao pede nem cancela nada
                montandoIndice = false;
                r.add("A montagem anterior do indice de jars nao terminou em " + PRAZO_INDICE_NS / 1000000000L
                        + " s e foi abandonada (veja o log; a thread dela pode ter travado lendo um jar).");
            }
            if (donos == null && usarIndiceDeJars) {
                montarIndiceEPedir(s);
                r.add("Montando o indice de jars (alguns segundos, em outra thread); a observacao de " + s
                        + " s comeca sozinha no tick seguinte.");
            } else {
                pedido.set(s);
                r.add("Observacao de " + s + " s pedida: comeca no proximo tick.");
            }
        }
        return r;
    }

    private static void montarIndiceEPedir(final int segundos) {
        final int minha;
        synchronized (TRAVA_INDICE) {
            montandoIndice = true;
            cancelarIndice = false;
            minha = ++geracaoIndice;
            indiceDesdeNs = relogio.nano();
        }
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
                    Runnable gancho = antesDePedirDoIndice;
                    if (gancho != null) {
                        gancho.run();
                    }
                    synchronized (TRAVA_INDICE) {
                        if (minha != geracaoIndice) {
                            return; // abandonada pelo prazo (m8): o indice fica, o pedido nao
                        }
                        if (cancelarIndice) {
                            ultimoResultado = "cancelado: 'hoshie observar parar' chegou durante a montagem do indice de jars";
                        } else {
                            pedido.set(segundos);
                        }
                        montandoIndice = false;
                    }
                } catch (Throwable e) {
                    Log.de(ID).e("nao consegui montar o indice de jars; a observacao nao comecou", e);
                    synchronized (TRAVA_INDICE) {
                        if (minha == geracaoIndice) {
                            ultimoResultado = "nao comecou: erro ao montar o indice de jars (" + e.getClass().getSimpleName() + ")";
                        }
                    }
                } finally {
                    synchronized (TRAVA_INDICE) {
                        if (minha == geracaoIndice) {
                            montandoIndice = false;
                        }
                    }
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
        if (n != null) { // Hoshiestar (m3): N e volatil; ATIVO nao e (o comando pode vir de outra thread)
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
        if (n != null) { // Hoshiestar (m3): N e volatil; ATIVO nao e (o comando pode vir de outra thread)
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
        if (falhaPedida.get() != null) {
            PedidoDeFalha f = falhaPedida.getAndSet(null); // sempre solta o nucleo guardado
            Nucleo atual = N;
            if (f != null && atual != null && f.n == atual) {
                finalizar(f.motivo, false);
            }
        }
        if (pedido.get() != 0) {
            // Hoshiestar: le e zera de uma vez so (m1 da revisao de concorrencia). Com get() e depois set(0), um 'parar' que
            // chegasse entre os dois era apagado e o admin lia "enviado" com a observacao seguindo.
            // sob a trava do comando (m3): entre zerar o pedido e publicar N, nenhum comando confere o estado
            synchronized (TRAVA_INDICE) {
                int p = pedido.getAndSet(0);
                if (p > 0) {
                    iniciar(p);
                } else if (p < 0 && ATIVO) {
                    finalizar("parado pelo comando", false);
                }
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
                falha(n, "a foto das regioes falhou", t);
                return;
            }
        }
        if (LISTA && j != null) {
            try {
                j.conferirListas(n.lista);
            } catch (Throwable t) {
                falha(n, "a conferencia das listas de tiles falhou", t);
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

    /**
     * Parada normal do servidor: grava o que ja tem e espera a gravacao terminar (ate 10 s). Roda antes do stopServer():
     * com parada.ligado, parada.espera_laco_segundos precisa cobrir essa espera (m5; ver src/docs/observador.md, 8.1).
     * Nao roda se o laco de tick cair por excecao (m6).
     */
    public static void encerrarNoDesligamento() {
        if (ATIVO) {
            finalizar("o servidor foi parado", true);
        }
        aguardarGravacao(10000);
    }

    /**
     * Erro interno do observador: desliga e grava o que tem (nunca derruba o tick). So a thread do servidor finaliza
     * (as tabelas T1/T2 sao dela); vindo de outra thread, so registra no log e pede a parada para o proximo tick.
     * {@code n} = o nucleo que o gancho tinha na mao (null = nao chegou a ler): outra thread so pede a parada da
     * observacao desse nucleo, e so se ela ainda for a que esta de pe.
     */
    static void falha(Nucleo n, String quando, Throwable t) {
        // Hoshiestar (m4 da revisao de concorrencia): o log e a montagem do motivo ficam num try proprio. Se eles lancarem
        // em outra thread, o pedido de parada sai assim mesmo e ATIVO nao e tocado: so a thread do servidor escreve ATIVO
        // (antes, um erro aqui escrevia ATIVO = false de qualquer thread e a observacao nunca era finalizada nem gravada).
        final boolean daObservacao = Thread.currentThread() == threadDaObservacao;
        String motivo = "erro interno: " + quando;
        try {
            motivo = motivo + " (" + t.getClass().getSimpleName() + ")";
            Log.de(ID).e("erro no observador (" + quando + "); parando e gravando o que ja tem", t);
        } catch (Throwable ignorado) {
            // segue: parar nao depende do log
        }
        try {
            if (!daObservacao) {
                if (n != null && n == N) {
                    falhaPedida.compareAndSet(null, new PedidoDeFalha(n, motivo)); // a primeira falha fica
                }
                return;
            }
            if (ATIVO && (n == null || n == N)) {
                finalizar(motivo, false);
            }
        } catch (Throwable ignorado) {
            if (daObservacao) {
                ATIVO = false;
            }
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
