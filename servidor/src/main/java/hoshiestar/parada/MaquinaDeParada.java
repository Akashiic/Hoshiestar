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

/**
 * A maquina de estados da parada do servidor (recurso "parada", item 1.G, achado D-24). Nao usa
 * nenhuma classe do Minecraft, do Forge nem do Bukkit: os patches em MinecraftServer,
 * DedicatedServer e ServerShutdownThread so chamam os metodos daqui (pela fachada {@link Parada}),
 * e o teste isolado exercita esta mesma classe com threads de verdade.
 *
 * <p><b>O problema (D-24).</b> Quando a JVM recebe SIGTERM, o "gancho de desligamento" (uma thread
 * que a JVM dispara ao sair, aqui o ServerShutdownThread) chamava {@code stopServer()} direto,
 * com o laco de tick ainda rodando na thread do servidor: mundos eram descarregados no meio de um
 * tick (erro e crash-report) e o salvamento podia rodar duas vezes ao mesmo tempo.
 *
 * <p><b>Estados</b> (campo {@code estado}, so muda com a {@code trava} na mao):
 * <ul>
 * <li>OCIOSO: nenhum salvamento de parada comecou (ou o ultimo saiu com excecao FORA da thread do
 * servidor: outra thread pode tentar de novo, uma de cada vez);</li>
 * <li>SALVANDO: uma thread (o {@code dono}) esta dentro do corpo de {@code stopServer()};</li>
 * <li>FEITO: um salvamento de parada terminou sem excecao. Estado final.</li>
 * <li>FALHOU: o salvamento da PROPRIA thread do servidor saiu com excecao. Ela segue para o
 * fechamento (avisos de "servidor parado") e para a saida, entao nenhuma OUTRA thread salva: um
 * segundo salvamento rodaria junto com esse fechamento. E o que o comando "stop" ja faz hoje. So
 * a propria thread do servidor pode tentar de novo (se chamar stopServer outra vez).</li>
 * </ul>
 * Transicoes: OCIOSO -> SALVANDO em {@link #entrar} (qualquer thread que chame stopServer; vira o
 * dono); FALHOU -> SALVANDO em {@link #entrar} (so a thread do servidor); SALVANDO -> FEITO,
 * SALVANDO -> FALHOU ou SALVANDO -> OCIOSO em {@link #sair} (so o dono).
 * Alem do estado ha a bandeira {@code saida} (falso -> verdadeiro, uma vez, pela thread do servidor
 * em {@link #saindo}, logo antes do System.exit) e a bandeira {@code desistiu} (so informativa).
 *
 * <p><b>Esperas e seus limites</b> (nenhuma e sem limite; todas usam {@code trava.wait(ms)} com
 * prazo calculado com System.nanoTime):
 * <ol>
 * <li>{@link #entrar}: quem chega com outro dono salvando espera ate {@code inicio do salvamento
 * + T2}; depois disso volta sem salvar (nunca dois salvamentos ao mesmo tempo);</li>
 * <li>{@link #ganchoAntes}: (so com o servidor ja iniciado; no boot nao ha espera nenhuma) o
 * gancho espera a thread do servidor chegar na saida. O prazo depende
 * so do estado: OCIOSO, ate {@code pedido de parada + T1}; SALVANDO, ate {@code inicio do
 * salvamento + T2}; FEITO, ate {@code fim do salvamento + folga}; FALHOU, ate o maior entre
 * {@code pedido + T1} e {@code fim do salvamento + folga}. Em qualquer caso nunca
 * passa de {@code T1 + T2 + folga} desde o pedido (limite duro). Se a propria thread do servidor
 * esta parada dentro do System.exit (um mod ou plugin chamou no meio do tick), ou se quem pediu a
 * saida foi o vigia de travamento (watchdog do Spigot: a thread do servidor ja esta travada ha mais
 * que o limite dele), nao ha espera: ver {@link #G_SERVIDOR_NO_EXIT} e {@link #G_VIGIA};</li>
 * <li>{@link #ganchoDepois}: depois do stopServer() do proprio gancho. Se outra thread esta
 * SALVANDO (ex.: o salvamento do gancho saiu com excecao e a thread do servidor assumiu), espera ate
 * {@code inicio desse salvamento + T2}, como em {@link #entrar}. Se o estado e FEITO ou FALHOU e a
 * thread do servidor JA PASSOU pelo stopServer() (saiu do laco de tick e esta no fechamento) mas
 * ainda nao chegou na saida, espera ate {@code fim do salvamento + folga} (OCIOSO com ela ja no
 * stopServer(): ate {@code agora + folga}, ela esta para assumir). O estado e reavaliado a cada
 * volta; limite duro {@code T2 + 2 x folga}. Se ela nunca passou pelo stopServer() (continua travada
 * num tick) e ninguem esta salvando, nao espera: a JVM sai logo depois do salvamento do gancho,
 * como no codigo original.</li>
 * </ol>
 *
 * <p><b>Passou de T2 com outra thread ainda salvando:</b> quem esperava desiste SEM salvar junto. Se
 * quem desistiu foi o gancho, a JVM sai e o salvamento em curso e CORTADO no meio (nao ha como
 * esperar sem limite); por isso T2 tem de ficar bem acima do salvamento de parada mais demorado.
 *
 * <p><b>Sem ciclo de travas.</b> So existe UMA trava ({@code trava}). Com ela na mao o codigo so
 * le e escreve campos desta classe, le o relogio, chama {@code Thread.isAlive()} e
 * {@code wait/notifyAll} da propria trava: nunca chama o corpo de stopServer, o pedido de parada,
 * o log, o exame de pilhas nem codigo de fora. Quem espera usa {@code wait}, que solta a trava. Logo nenhuma thread
 * segura esta trava enquanto pede outra, e nao ha como formar ciclo.
 */
public final class MaquinaDeParada {

    // ---- estados
    public static final int OCIOSO = 0;
    public static final int SALVANDO = 1;
    public static final int FEITO = 2;
    public static final int FALHOU = 3;

    // ---- respostas de entrar()
    /** Siga o codigo original e NAO chame sair(). */
    public static final int ORIGINAL = 0;
    /** Voce e o dono do salvamento: rode o corpo original e chame sair(ok) num finally. */
    public static final int DONO = 1;
    /**
     * Nao rode o corpo: outro ja salvou; ou o salvamento da thread do servidor falhou e quem chama
     * e outra thread; ou outro esta salvando ha mais de T2.
     */
    public static final int PULAR = 2;

    // ---- respostas de ganchoAntes()
    /** Nao ha thread do servidor viva para esperar (ou o gancho roda nela): caminho original. */
    public static final int G_SEM_THREAD = 0;
    /** A thread do servidor ja tinha chegado na saida (parada normal por "stop"). */
    public static final int G_JA_SAIU = 1;
    /** A thread do servidor chegou na saida (ou terminou) durante a espera. */
    public static final int G_SAIU = 2;
    /** Passou T1 e o laco de tick nao parou: o gancho desiste e salva ele mesmo (caminho original). */
    public static final int G_ESGOTOU_LACO = 3;
    /**
     * A thread do servidor esta salvando ha mais de T2: o gancho para de esperar e NAO salva. A JVM
     * sai em seguida e esse salvamento e cortado no meio.
     */
    public static final int G_ESGOTOU_SALVAR = 4;
    /** Salvou (ou o salvamento dela falhou), mas nao chegou na saida dentro da folga: o gancho para de esperar. */
    public static final int G_ESGOTOU_FOLGA = 5;
    /** O servidor ainda esta no boot (nada a salvar): o gancho nao pede nada nem espera; caminho original. */
    public static final int G_NO_BOOT = 6;
    /**
     * Foi a propria thread do servidor que chamou System.exit (um mod ou plugin, no meio de um
     * tick): ela fica parada ali ate os ganchos terminarem, entao esperar por ela e inutil. O
     * gancho nao espera e salva ele mesmo, como no codigo original.
     */
    public static final int G_SERVIDOR_NO_EXIT = 7;
    /**
     * Quem pediu a saida da JVM foi o vigia de travamento (o watchdog do Spigot, que so age quando a
     * thread do servidor ficou sem ticar por mais que o limite dele): esperar mais T1 por ela so
     * atrasaria o salvamento. O gancho nao espera e salva ele mesmo, como no codigo original.
     */
    public static final int G_VIGIA = 8;

    /** A classe do vigia de travamento (watchdog do Spigot), reconhecida pela pilha de quem chamou a saida. */
    public static final String CLASSE_DO_VIGIA = "org.spigotmc.WatchdogThread";

    private static final String[] NOME_ESTADO = {"ocioso", "salvando", "feito", "falhou na thread do servidor"};
    private static final String[] NOME_GANCHO = {"sem thread do servidor para esperar", "o servidor ja tinha saido",
        "o servidor salvou e chegou na saida", "desistiu: o laco de tick nao parou em T1",
        "desistiu: salvamento passou de T2", "desistiu: salvou mas nao saiu dentro da folga",
        "servidor ainda no boot: sem espera", "a thread do servidor chamou System.exit: sem espera",
        "o vigia de travamento (watchdog) pediu a saida: sem espera"};

    /** Recebe os avisos. Sempre chamado FORA da trava. {@code principal} pode ser null. */
    public interface Avisos {
        void info(String msg);

        void aviso(String msg, Thread principal);
    }

    /** Passo maximo de cada wait: tambem e de quanto em quanto tempo o gancho olha se a thread do servidor morreu. */
    private static final long PASSO_PADRAO_MS = 50L;

    private final Object trava = new Object();
    private final long t1Ns;
    private final long t2Ns;
    private final long folgaNs;
    private final long passoNs;
    private final Avisos avisos;
    private final String classeDoVigia;

    // escritos so com a trava na mao; volatile para o "hoshie status" e os atalhos lerem sem trava
    private volatile int estado = OCIOSO;
    private volatile boolean saida;
    private volatile boolean desistiu;
    // lidos e escritos so com a trava na mao
    private Thread dono;
    private boolean donoNaThreadDoServidor;
    /** A thread do servidor ja chamou stopServer() (logo, ja saiu do laco de tick). */
    private boolean servidorNaParada;
    private long inicioSalvar;
    private long fimSalvar;

    // contadores (escritos com a trava na mao; lidos sem trava so para o status e os testes)
    private volatile int salvamentos;
    private volatile int falhas;
    private volatile int pulos;
    private volatile int pulosSemEsperarMais;
    private volatile int reentradas;
    private volatile int ganchos;
    private volatile int ultimoGancho = -1;
    private volatile long ultimaEsperaDoGanchoMs = -1;
    private volatile String donoDoUltimo;

    public MaquinaDeParada(long t1Ms, long t2Ms, long folgaMs, Avisos avisos) {
        this(t1Ms, t2Ms, folgaMs, avisos, PASSO_PADRAO_MS, CLASSE_DO_VIGIA);
    }

    /**
     * So para os testes: escolhe o passo das esperas (com um passo enorme, quem espera so acorda se
     * for avisado por {@code notifyAll}: prova que os avisos existem) e a classe tratada como vigia.
     */
    public MaquinaDeParada(long t1Ms, long t2Ms, long folgaMs, Avisos avisos, long passoMs, String classeDoVigia) {
        this.t1Ns = Math.max(0L, t1Ms) * 1000000L;
        this.t2Ns = Math.max(0L, t2Ms) * 1000000L;
        this.folgaNs = Math.max(0L, folgaMs) * 1000000L;
        this.passoNs = Math.max(1L, passoMs) * 1000000L;
        this.avisos = avisos;
        this.classeDoVigia = classeDoVigia;
    }

    // ------------------------------------------------------------------ stopServer()

    /**
     * Chamado no comeco de {@code stopServer()}, por qualquer thread.
     *
     * @param vaiSalvar a mesma condicao do {@code if} do corpo original (nao esta apagando o
     *        mundo, o servidor chegou a iniciar e ainda nao foi marcado como parado). Falso: o
     *        corpo original nao faz nada, entao a maquina nao se mete (ORIGINAL).
     * @param naThreadDoServidor quem chama e a thread do servidor (a do laco de tick)? Se o
     *        salvamento DELA sair com excecao, o estado vira FALHOU em vez de OCIOSO, e dai em
     *        diante so ela pode tentar de novo. Falso quando nao se sabe.
     * @return ORIGINAL, DONO ou PULAR
     */
    public int entrar(boolean vaiSalvar, boolean naThreadDoServidor) {
        if (!vaiSalvar) {
            return ORIGINAL;
        }
        Thread eu = Thread.currentThread();
        boolean interrompida = false;
        boolean esgotou = false;
        int resposta;
        synchronized (trava) {
            if (naThreadDoServidor) {
                servidorNaParada = true;
            }
            if (estado == SALVANDO && dono == eu) {
                // stopServer() chamado de dentro do proprio stopServer(), na mesma thread: como no
                // original, o corpo roda de novo; o estado continua com a chamada de fora
                reentradas++;
                return ORIGINAL;
            }
            // espera 1: outro dono salvando. Prazo: inicio DO SALVAMENTO + T2 (nao a minha chegada),
            // para que quem ja esperou T2 por este mesmo salvamento nao espere outra vez
            while (estado == SALVANDO) {
                long falta = inicioSalvar + t2Ns - System.nanoTime();
                if (falta <= 0L) {
                    break;
                }
                try {
                    esperar(falta);
                } catch (InterruptedException e) {
                    interrompida = true; // guarda e continua: o prazo acima ainda vale
                }
            }
            if (estado == FEITO || (estado == FALHOU && !naThreadDoServidor)) {
                // FALHOU: a thread do servidor esta no fechamento; outra thread salvando agora rodaria junto com ele
                pulos++;
                resposta = PULAR;
            } else if (estado == SALVANDO) {
                // o primeiro travou: nunca dois salvamentos ao mesmo tempo
                pulos++;
                pulosSemEsperarMais++;
                esgotou = true;
                resposta = PULAR;
            } else {
                inicioSalvar = System.nanoTime();
                dono = eu;
                donoNaThreadDoServidor = naThreadDoServidor;
                donoDoUltimo = eu.getName();
                salvamentos++;
                estado = SALVANDO; // por ultimo: nada depois desta linha pode falhar
                resposta = DONO;
            }
        }
        if (interrompida) {
            eu.interrupt();
        }
        if (resposta == DONO) {
            info("salvamento de parada comecou na thread " + eu.getName());
        }
        if (esgotou) {
            avisar("o salvamento de parada de outra thread passou do limite (parada.espera_salvar_segundos); "
                    + "a thread " + eu.getName() + " NAO vai salvar de novo ao mesmo tempo e segue sem esperar mais", null);
        }
        return resposta;
    }

    /** Chamado pelo dono num finally, depois do corpo de stopServer(). {@code ok} = terminou sem excecao. */
    public void sair(boolean ok) {
        long ms;
        boolean semNovaTentativa;
        synchronized (trava) {
            if (estado != SALVANDO || dono != Thread.currentThread()) {
                return;
            }
            fimSalvar = System.nanoTime();
            ms = (fimSalvar - inicioSalvar) / 1000000L;
            dono = null;
            if (!ok) {
                falhas++;
            }
            // falha na thread do servidor: ela segue para o fechamento e nenhuma outra thread salva junto
            semNovaTentativa = ok || donoNaThreadDoServidor;
            estado = ok ? FEITO : donoNaThreadDoServidor ? FALHOU : OCIOSO;
            trava.notifyAll();
        }
        if (ok) {
            info("salvamento de parada terminou em " + ms + " ms na thread " + Thread.currentThread().getName());
        } else if (semNovaTentativa) {
            avisar("salvamento de parada na thread do servidor (" + Thread.currentThread().getName() + ") saiu com excecao depois de " + ms
                    + " ms; ela segue para o fechamento e nenhuma outra thread salva de novo (como no comando 'stop' de hoje); "
                    + "o gancho de desligamento so espera a saida", null);
        } else {
            avisar("salvamento de parada na thread " + Thread.currentThread().getName() + " saiu com excecao depois de " + ms
                    + " ms; o estado volta a ocioso (outra thread pode tentar de novo, uma de cada vez)", null);
        }
    }

    // ------------------------------------------------------------------ systemExitNow()

    /** Chamado pela thread do servidor logo antes do System.exit: daqui em diante nada mais roda nela. */
    public void saindo() {
        synchronized (trava) {
            saida = true;
            trava.notifyAll();
        }
    }

    // ------------------------------------------------------------------ gancho (ServerShutdownThread)

    /**
     * Chamado pelo gancho de desligamento ANTES do {@code stopServer()} dele. Pede ao laco de tick
     * que pare ({@code pedirParada} = initiateShutdown) e espera a thread do servidor salvar e
     * chegar na saida, dentro dos prazos do javadoc da classe.
     *
     * @param iniciou a mesma pergunta do corpo de stopServer(): o servidor chegou a iniciar
     *        (estado SERVER_STARTED do FML)? Falso com a maquina OCIOSA = ainda no boot: o stopServer() do gancho nao
     *        salvaria nada, entao o gancho nao pede a parada nem espera (a JVM sai na hora, como no
     *        codigo original). Continuar o boot com a JVM ja saindo quebra mods (visto no teste
     *        real: Runtime.addShutdownHook lanca "Shutdown in progress" e sai um crash-report).
     * @return uma das constantes G_*
     */
    public int ganchoAntes(Thread principal, Runnable pedirParada, boolean iniciou) {
        Thread eu = Thread.currentThread();
        if (principal == null || principal == eu || !principal.isAlive()) {
            return fimDoGancho(G_SEM_THREAD, 0L, principal);
        }
        if (saida) {
            return fimDoGancho(G_JA_SAIU, 0L, principal);
        }
        if (!iniciou && estado == OCIOSO) {
            // "nao iniciou" tambem e o que o FML responde DEPOIS de o servidor parar; por isso a
            // saida vem antes, e um salvamento em curso, feito ou falho na thread do servidor prova
            // que o servidor tinha iniciado
            return fimDoGancho(G_NO_BOOT, 0L, principal);
        }
        if (dentroDoExit(principal)) {
            return fimDoGancho(G_SERVIDOR_NO_EXIT, 0L, principal);
        }
        if (estado == OCIOSO && saidaPedidaPor(classeDoVigia)) {
            // so com a maquina OCIOSA: se ja ha salvamento em curso ou terminado, vale a espera normal
            return fimDoGancho(G_VIGIA, 0L, principal);
        }
        long inicio = System.nanoTime();
        try {
            pedirParada.run();
        } catch (Throwable t) {
            avisar("o pedido de parada do laco de tick falhou (" + t + "); espero assim mesmo", null);
        }
        info("JVM saindo com o servidor de pe (SIGTERM?): pedi a parada do laco de tick e espero a thread do servidor salvar");
        long limiteDuro = inicio + t1Ns + t2Ns + folgaNs;
        int motivo;
        boolean interrompida = false;
        synchronized (trava) {
            for (;;) {
                if (saida || !principal.isAlive()) {
                    motivo = G_SAIU;
                    break;
                }
                int e = estado;
                long limite;
                if (e == SALVANDO) {
                    // fase 2: um salvamento esta em curso; o prazo passa a ser o do salvamento
                    limite = inicioSalvar + t2Ns;
                } else if (e == FEITO) {
                    // fase 3: ja salvou; so uma folga curta para a thread do servidor chegar na saida
                    limite = fimSalvar + folgaNs;
                } else if (e == FALHOU) {
                    // o salvamento da thread do servidor falhou: ou ela esta no fechamento (folga), ou a
                    // falha foi antiga, no meio de um tick, e o laco ainda vai parar (T1): vale o maior
                    limite = fimSalvar + folgaNs;
                    if (limite - (inicio + t1Ns) < 0L) {
                        limite = inicio + t1Ns;
                    }
                } else {
                    // fase 1: o laco de tick ainda nao parou
                    limite = inicio + t1Ns;
                }
                if (limite - limiteDuro > 0L) {
                    limite = limiteDuro;
                }
                long falta = limite - System.nanoTime();
                if (falta <= 0L) {
                    motivo = e == SALVANDO ? G_ESGOTOU_SALVAR : e == OCIOSO ? G_ESGOTOU_LACO : G_ESGOTOU_FOLGA;
                    break;
                }
                try {
                    esperar(falta);
                } catch (InterruptedException ex) {
                    interrompida = true;
                }
            }
            if (motivo == G_ESGOTOU_LACO) {
                desistiu = true;
            }
        }
        if (interrompida) {
            eu.interrupt();
        }
        return fimDoGancho(motivo, (System.nanoTime() - inicio) / 1000000L, principal);
    }

    /**
     * Chamado pelo gancho DEPOIS do {@code stopServer()} dele. Espera, com prazo, o que ainda esta
     * em curso antes de a JVM sair:
     * <ul>
     * <li>SALVANDO (outra thread e dona; o caso tipico: o salvamento do gancho saiu com excecao, o
     * estado voltou a OCIOSO e a thread do servidor, que esperava em {@link #entrar}, assumiu):
     * espera ate {@code inicio desse salvamento + T2}, o mesmo prazo de quem espera em
     * {@link #entrar}. Sem isso a JVM sairia no meio desse salvamento (achado P1 da revisao de
     * concorrencia);</li>
     * <li>OCIOSO com a thread do servidor ja dentro do stopServer(): ela esta para virar dona (acabou
     * de acordar em {@link #entrar}); espera ate {@code agora + folga} ela assumir ou sair;</li>
     * <li>FEITO ou FALHOU com a thread do servidor ja dentro do stopServer() (saiu do laco de tick e
     * esta no fechamento) mas ainda sem chegar na saida: espera ate
     * {@code fim do salvamento + folga}.</li>
     * </ul>
     * O estado e reavaliado a cada volta (FALHOU -> SALVANDO quando a thread do servidor tenta de
     * novo volta a valer o prazo de T2). Limite duro: {@code T2 + 2 x folga} desde a chamada.
     *
     * <p>Se a thread do servidor NAO passou pelo stopServer(), ela continua dentro de um tick
     * (travada): fora o caso SALVANDO, o gancho nao espera. Esperar so daria tempo de ela destravar e terminar o tick em
     * cima dos mundos que o gancho acabou de salvar e descarregar (visto no teste real: erro no
     * tick e crash-report, com tudo ja salvo). Sem a espera a JVM sai logo depois do salvamento do
     * gancho, exatamente como no codigo original.
     *
     * @return verdadeiro se nao ha mais o que esperar (a thread do servidor saiu ou morreu, ou
     *         nao havia nada em curso); falso se um prazo acabou (folga ou T2)
     */
    public boolean ganchoDepois(Thread principal) {
        Thread eu = Thread.currentThread();
        if (principal == null || principal == eu) {
            return true;
        }
        if (saida || dentroDoExit(principal)) {
            return true; // nada a esperar (o exame da pilha fica fora da trava)
        }
        int e0 = estado;
        long inicio = System.nanoTime();
        long limiteDuro = inicio + t2Ns + folgaNs + folgaNs;
        boolean interrompida = false;
        boolean saiu = true;
        boolean noTick = false;
        boolean esgotouSalvar = false;
        boolean esperou = false;
        String outroDono = null;
        synchronized (trava) {
            for (;;) {
                if (saida || !principal.isAlive()) {
                    break;
                }
                int e = estado;
                long limite;
                if (e == SALVANDO) {
                    // outra thread salvando (o gancho nunca e dono aqui: o sair() dele ja rodou no finally)
                    limite = inicioSalvar + t2Ns;
                    outroDono = donoDoUltimo;
                } else if (!servidorNaParada) {
                    // a thread do servidor nunca chegou ao stopServer(): continua num tick (ou nem ha o que esperar)
                    noTick = e == FEITO || e == FALHOU;
                    break;
                } else if (e == OCIOSO) {
                    // o salvamento do gancho saiu com excecao; a thread do servidor acordou em entrar() e vai assumir
                    limite = inicio + folgaNs;
                } else {
                    limite = fimSalvar + folgaNs; // FEITO ou FALHOU: o fechamento dela
                }
                if (limite - limiteDuro > 0L) {
                    limite = limiteDuro;
                }
                long falta = limite - System.nanoTime();
                if (falta <= 0L) {
                    saiu = false;
                    esgotouSalvar = e == SALVANDO;
                    break;
                }
                esperou = true;
                try {
                    esperar(falta);
                } catch (InterruptedException ex) {
                    interrompida = true;
                }
            }
        }
        if (interrompida) {
            eu.interrupt();
        }
        if (noTick) {
            avisar("o salvamento de parada terminou e a thread do servidor continua dentro de um tick (nao chegou ao stopServer): "
                    + "a JVM sai sem esperar a folga, como no codigo original", principal);
        } else if (esgotouSalvar) {
            avisar("depois do stopServer() do gancho, a thread " + outroDono + " continua salvando e passou do limite "
                    + "(parada.espera_salvar_segundos); a JVM vai sair e o salvamento em curso sera CORTADO no meio", principal);
        } else if (!saiu) {
            avisar("o salvamento de parada terminou, mas a thread do servidor nao chegou na saida dentro da folga "
                    + "(parada.folga_saida_segundos); a JVM vai sair assim mesmo", principal);
        } else if (esperou || e0 != OCIOSO) {
            info("depois do salvamento, a thread do servidor terminou o fechamento em " + (System.nanoTime() - inicio) / 1000000L + " ms"
                    + (outroDono != null ? " (o gancho esperou tambem o salvamento da thread " + outroDono + ", que assumiu depois dele)" : ""));
        }
        return saiu;
    }

    // ------------------------------------------------------------------ apoio

    /**
     * A thread esta parada dentro do System.exit (Runtime.exit)? Quem chama System.exit com a JVM
     * ja saindo fica bloqueado ate os ganchos terminarem; se for a thread do servidor, ela nunca
     * vai parar o laco nem chegar na saida enquanto o gancho espera. Olha a pilha inteira (o
     * numero de quadros acima do Runtime.exit muda com a versao do Java). Chamar FORA da trava.
     */
    static boolean dentroDoExit(Thread t) {
        try {
            return temExit(t.getStackTrace());
        } catch (Throwable ignorado) {
            // sem a pilha: segue o caminho normal (espera com limite)
        }
        return false;
    }

    private static boolean temExit(StackTraceElement[] pilha) {
        for (int i = 0; i < pilha.length; i++) {
            if ("java.lang.Runtime".equals(pilha[i].getClassName()) && "exit".equals(pilha[i].getMethodName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Alguma thread viva esta parada dentro do System.exit E tem na pilha um metodo da classe dada?
     * Com a classe do vigia de travamento (watchdog do Spigot), verdadeiro quer dizer: foi o vigia
     * que pediu a saida, porque a thread do servidor ficou sem ticar por mais que o limite dele. So
     * compara nomes (nao carrega a classe). Roda uma vez por saida da JVM. Chamar FORA da trava.
     */
    static boolean saidaPedidaPor(String classe) {
        if (classe == null) {
            return false;
        }
        try {
            for (StackTraceElement[] pilha : Thread.getAllStackTraces().values()) {
                if (pilha == null || !temExit(pilha)) {
                    continue;
                }
                for (int i = 0; i < pilha.length; i++) {
                    if (classe.equals(pilha[i].getClassName())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignorado) {
            // sem as pilhas: segue o caminho normal (espera com limite)
        }
        return false;
    }

    /** wait com passo curto. So chamar com a trava na mao e {@code faltaNs > 0}. */
    private void esperar(long faltaNs) throws InterruptedException {
        long ns = Math.min(faltaNs, passoNs);
        long ms = ns / 1000000L;
        trava.wait(ms <= 0L ? 1L : ms); // nunca wait(0), que e "para sempre"
    }

    private int fimDoGancho(int motivo, long esperouMs, Thread principal) {
        int e;
        synchronized (trava) {
            ganchos++;
            ultimoGancho = motivo;
            ultimaEsperaDoGanchoMs = esperouMs;
            e = estado;
        }
        if (motivo == G_VIGIA) {
            avisar("quem pediu a saida foi o vigia de travamento (watchdog): a thread do servidor esta travada ha mais que o limite "
                    + "dele, entao o gancho nao espera e salva ele mesmo, como no codigo original; a thread do servidor espera esse "
                    + "salvamento se destravar", principal);
        } else if (motivo == G_SERVIDOR_NO_EXIT) {
            avisar("a propria thread do servidor chamou System.exit (mod ou plugin): ela nao vai parar o laco, entao o gancho "
                    + "nao espera e salva ele mesmo, como no codigo original", principal);
        } else if (motivo == G_NO_BOOT) {
            info("JVM saindo com o servidor ainda no boot: nada a salvar; sem espera (caminho original)");
        } else if (motivo == G_SAIU) {
            info("a thread do servidor chegou na saida em " + esperouMs + " ms (salvamentos de parada: " + salvamentos + ")");
        } else if (motivo == G_ESGOTOU_LACO) {
            avisar("o laco de tick nao parou em " + esperouMs + " ms (parada.espera_laco_segundos): o gancho vai salvar "
                    + "ele mesmo, so agora, como no codigo original; a thread do servidor espera esse salvamento se chegar depois", principal);
        } else if (motivo == G_ESGOTOU_SALVAR) {
            avisar("a thread do servidor esta salvando ha mais que o limite (parada.espera_salvar_segundos); o gancho "
                    + "parou de esperar depois de " + esperouMs + " ms e NAO vai salvar ao mesmo tempo. ATENCAO: a JVM vai sair e "
                    + "esse salvamento sera CORTADO no meio (parte do mundo nova, parte velha); se o salvamento de parada demora "
                    + "tanto, aumente parada.espera_salvar_segundos", principal);
        } else if (motivo == G_ESGOTOU_FOLGA) {
            avisar((e == FALHOU ? "o salvamento da thread do servidor saiu com excecao e ela" : "o mundo foi salvo, mas a thread do servidor")
                    + " nao chegou na saida dentro da folga (parada.folga_saida_segundos); o gancho parou de esperar depois de "
                    + esperouMs + " ms" + (e == FALHOU ? " e NAO salva de novo" : ""), principal);
        }
        return motivo;
    }

    private void info(String msg) {
        try {
            if (avisos != null) {
                avisos.info(msg);
            }
        } catch (Throwable ignorado) {
            // aviso nunca atrapalha a parada
        }
    }

    private void avisar(String msg, Thread principal) {
        try {
            if (avisos != null) {
                avisos.aviso(msg, principal);
            }
        } catch (Throwable ignorado) {
            // aviso nunca atrapalha a parada
        }
    }

    // ------------------------------------------------------------------ leitura (status e testes)

    public int estado() {
        return estado;
    }

    public boolean saida() {
        return saida;
    }

    public boolean desistiu() {
        return desistiu;
    }

    public int salvamentos() {
        return salvamentos;
    }

    public int falhas() {
        return falhas;
    }

    public int pulos() {
        return pulos;
    }

    public int reentradas() {
        return reentradas;
    }

    public int ganchos() {
        return ganchos;
    }

    public int ultimoGancho() {
        return ultimoGancho;
    }

    public long ultimaEsperaDoGanchoMs() {
        return ultimaEsperaDoGanchoMs;
    }

    public static String nomeDoGancho(int motivo) {
        return motivo >= 0 && motivo < NOME_GANCHO.length ? NOME_GANCHO[motivo] : "?";
    }

    /** Uma linha para o "hoshie status". */
    public String linha() {
        StringBuilder sb = new StringBuilder();
        sb.append("estado: ").append(NOME_ESTADO[estado]);
        sb.append("; limites: laco ").append(t1Ns / 1000000000L).append(" s, salvar ").append(t2Ns / 1000000000L)
                .append(" s, folga ").append(folgaNs / 1000000000L).append(" s");
        sb.append("; salvamentos de parada: ").append(salvamentos);
        if (donoDoUltimo != null) {
            sb.append(" (ultimo na thread ").append(donoDoUltimo).append(')');
        }
        sb.append("; chamadas puladas: ").append(pulos);
        if (falhas > 0) {
            sb.append("; salvamentos com excecao: ").append(falhas);
        }
        if (desistiu) {
            sb.append("; o gancho desistiu de esperar o laco de tick");
        }
        if (pulosSemEsperarMais > 0) {
            sb.append("; esperas de salvamento esgotadas: ").append(pulosSemEsperarMais);
        }
        if (ganchos > 0) {
            sb.append("; gancho: ").append(nomeDoGancho(ultimoGancho)).append(" (esperou ").append(ultimaEsperaDoGanchoMs).append(" ms)");
        }
        return sb.toString();
    }
}
