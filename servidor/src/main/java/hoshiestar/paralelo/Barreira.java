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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * O nucleo de concorrencia do Degrau B (plano, secoes 2, 3, 7 e 9), sem Minecraft: as threads de regiao, a barreira, o
 * pedido delegado, o portao das threads estranhas, o aborto e o encerramento. Nada aqui esta ligado ao jogo (isso e o
 * passo 6 do plano): quem usa entrega {@link Trabalho}s e um {@link Ambiente}.
 *
 * <h3>Como se usa (thread do servidor)</h3>
 * {@link #abrir} (FECHADA -&gt; MONTANDO), {@link #rodar} (MONTANDO -&gt; RODANDO -&gt; barreira -&gt; JUNCAO),
 * {@link #fechar} (JUNCAO -&gt; FECHADA). A thread que chama {@code abrir} pela primeira vez passa a ser "a thread do
 * servidor" desta barreira. Ela nao pega lote: em {@code rodar} ela espera e atende pedidos delegados.
 *
 * <h3>Invariantes</h3>
 * <ol>
 * <li><b>Cada lote exatamente uma vez.</b> Um lote so e ticado por quem o pegou no contador atomico da {@link Fase}, e
 * so e fechado uma vez (troca atomica em {@link Andamento}).</li>
 * <li><b>A barreira so passa com todos.</b> {@code rodar} so sai de RODANDO quando todo lote esta fechado; quem fecha so
 * conta depois da ultima escrita do lote, entao a juncao enxerga tudo e nenhum item esta rodando.</li>
 * <li><b>A operacao de um pedido delegado roda sozinha e na thread do servidor.</b> Ela so comeca quando, com a trava,
 * nenhum trabalhador esta em RODANDO, e um trabalhador so entra em RODANDO com a trava e com a pausa desligada. A pausa
 * fica ligada exatamente enquanto ha pedido na fila ou em atendimento. Nenhum prazo libera a operacao.</li>
 * <li><b>Um pedido por vez, o do lote de menor chave primeiro.</b> A escolha e feita depois de todos pararem, quando
 * ja nao pode chegar pedido novo.</li>
 * <li><b>Depois do primeiro pedido, erro, aborto ou morte, nenhum lote novo comeca</b> ({@link Fase#semNovos}).</li>
 * <li><b>Thread estranha nao passa pelo portao com a fase em RODANDO</b>, a nao ser: (a) se segura o monitor indicado e a
 * thread do servidor esta para entrar nesse mesmo monitor para atender um pedido (todos os trabalhadores estao parados, e
 * a thread do servidor fica presa no monitor ate a thread estranha solta-lo); (b) depois de {@code abortar}. Quem
 * esperava no portao quando a barreira passou e liberado, e a fase seguinte so e publicada depois de ele confirmar.</li>
 * <li><b>Ninguem e interrompido e nenhuma espera do servidor e sem aviso.</b> As esperas sao em fatias; a cada fatia
 * vencida sai um despejo de pilhas. {@code abortar} e {@code encerrar} tem prazo e devolvem quem ficou.</li>
 * <li><b>Sem trava nossa em codigo de fora.</b> {@link Trabalho#ticar}, {@link Operacao#rodar}, {@link Trabalho#descrever}
 * e o {@link Ambiente} nunca sao chamados com a trava na mao.</li>
 * </ol>
 *
 * <h3>Quem escreve cada campo</h3>
 * Tudo o que nao e {@code final} e escrito COM a trava {@link #trava}; os campos {@code volatile} tambem sao lidos sem
 * ela, nos caminhos rapidos:
 * <ul>
 * <li>{@link #estado}, {@link #fase}, {@link #servidor}, {@link #emServico}, {@link #numero}: so a thread do servidor.</li>
 * <li>{@link #sinais}: bit de pausa ligado pelo trabalhador que pede e desligado pela thread do servidor; bit de aborto
 * por quem chama {@link #abortar}.</li>
 * <li>{@link #monitorCedido}: a thread do servidor liga com a trava e desliga DENTRO do monitor cedido (e de novo com a
 * trava, no fim do atendimento). A thread estranha le segurando esse monitor: se le o valor ligado, a thread do servidor
 * ainda nao entrou no monitor.</li>
 * <li>{@link #abortada}, {@link #portaoAberto}, {@link #encerrada}: quem chama {@code abortar}/{@code encerrar}; so de
 * falso para verdadeiro.</li>
 * <li>{@code pendentes}, {@code noPortao}, {@code confirmacoesPendentes}, {@code vivos}, {@code motivoDaRecusa}, o estado
 * de cada {@link Trabalhador} e o {@code pronto} de cada {@link Pedido}: so com a trava (lidos tambem so com ela).</li>
 * <li>Os contadores sao {@link AtomicLong}.</li>
 * </ul>
 */
public final class Barreira {

    /** {@link #papel}: a fase nao esta em RODANDO (nenhuma regra de dono vale). */
    public static final int FORA = 0;
    /** {@link #papel}: thread de regiao desta barreira. */
    public static final int TRABALHADOR = 1;
    /** {@link #papel}: thread do servidor atendendo um pedido delegado (passa direto pelas guardas). */
    public static final int ATENDENDO = 2;
    /** {@link #papel}: thread do servidor na barreira, sem pedido em atendimento. */
    public static final int SERVIDOR = 3;
    /** {@link #papel}: qualquer outra thread, com a fase em RODANDO (vai para o {@link #portao}). */
    public static final int ESTRANHA = 4;

    /** {@link #portao}: nao esperou (fase fora de RODANDO, ou a thread nao e estranha). */
    public static final int PORTAO_DIRETO = 0;
    /** {@link #portao}: esperou a barreira passar. */
    public static final int PORTAO_ESPEROU = 1;
    /** {@link #portao}: passou com a fase em RODANDO pela excecao do monitor (todos os trabalhadores parados). */
    public static final int PORTAO_MONITOR = 2;
    /** {@link #portao}: passou porque a fase foi abortada (parada do servidor). */
    public static final int PORTAO_ABERTO = 3;

    static final int SINAL_PAUSA = 1;
    static final int SINAL_ABORTO = 2;

    static final int SEGUE = 0;
    static final int PARA_SUJO = 1;
    static final int PARA_ABORTO = 2;

    /** Uma thread estranha esperando no portao. {@code liberada}: so com a trava. */
    private static final class EsperaPortao {
        final Thread thread;
        final Object monitor;
        final StackTraceElement[] pilha;
        boolean liberada;

        EsperaPortao(Thread thread, Object monitor, StackTraceElement[] pilha) {
            this.thread = thread;
            this.monitor = monitor;
            this.pilha = pilha;
        }
    }

    /** Foto de um trabalhador tirada com a trava, para montar texto sem ela. */
    private static final class Foto {
        final Trabalhador w;
        final int estado;
        final Andamento a;

        Foto(Trabalhador w, int estado, Andamento a) {
            this.w = w;
            this.estado = estado;
            this.a = a;
        }
    }

    final Ambiente ambiente;
    private final long fatiaNs;
    private final Trabalhador[] trabalhadores;

    private final ReentrantLock trava = new ReentrantLock();
    /** Quem espera: a thread do servidor (barreira, todos parados, confirmacoes do portao). */
    private final Condition condServidor = trava.newCondition();
    /** Quem espera: trabalhadores ociosos, por uma fase nova. */
    private final Condition condTrabalho = trava.newCondition();
    /** Quem espera: trabalhadores, pelo fim da pausa. */
    private final Condition condRetomar = trava.newCondition();
    /** Quem espera: trabalhadores, pela resposta do proprio pedido. */
    private final Condition condPedidos = trava.newCondition();
    /** Quem espera: threads estranhas, no portao. */
    private final Condition condPortao = trava.newCondition();
    /** Quem espera: quem chamou abortar, pelos trabalhadores no meio de um item. */
    private final Condition condParados = trava.newCondition();

    private volatile Fase.Estado estado = Fase.Estado.FECHADA;
    private volatile Fase fase;
    private volatile Thread servidor;
    private volatile Pedido emServico;
    volatile int sinais;
    private volatile Object monitorCedido;
    private volatile boolean abortada;
    private volatile boolean portaoAberto;
    private volatile boolean encerrada;

    private final ArrayList<Pedido> pendentes = new ArrayList<Pedido>();
    private final ArrayList<EsperaPortao> noPortao = new ArrayList<EsperaPortao>();
    private int confirmacoesPendentes;
    private int vivos;
    private long numero;
    private long seqPedido;
    private String motivoDaRecusa;

    private final AtomicLong cFases = new AtomicLong();
    private final AtomicLong cFasesVazias = new AtomicLong();
    private final AtomicLong cEsquecidas = new AtomicLong();
    private final AtomicLong cLotes = new AtomicLong();
    private final AtomicLong cDespertares = new AtomicLong();
    private final AtomicLong cAcordados = new AtomicLong();
    private final AtomicLong cPedidos = new AtomicLong();
    private final AtomicLong cAninhados = new AtomicLong();
    private final AtomicLong cForaDeLugar = new AtomicLong();
    private final AtomicLong cPortaoEsperas = new AtomicLong();
    private final AtomicLong cPortaoMonitor = new AtomicLong();
    private final AtomicLong cDespejos = new AtomicLong();
    private final AtomicLong cMortos = new AtomicLong();

    private Barreira(int threads, Ambiente ambiente, long fatiaNs, long tamanhoDaPilha) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads < 1: " + threads);
        }
        if (fatiaNs <= 0L) {
            throw new IllegalArgumentException("fatia <= 0: " + fatiaNs);
        }
        if (ambiente == null) {
            throw new NullPointerException("ambiente");
        }
        this.ambiente = ambiente;
        this.fatiaNs = fatiaNs;
        this.trabalhadores = new Trabalhador[threads];
        for (int i = 0; i < threads; i++) {
            trabalhadores[i] = new Trabalhador(this, i, Paralelo.PREFIXO_THREAD_REGIAO + (i + 1), tamanhoDaPilha);
        }
        this.vivos = threads;
    }

    /**
     * Cria a barreira e poe as threads de regiao para rodar (elas ficam estacionadas ate a primeira fase com lote).
     *
     * @param threads quantas threads de regiao (a thread do servidor nao conta: ela nao pega lote)
     * @param fatiaNs fatia das esperas ({@code paralelo.aviso_espera_s}): a cada fatia vencida sai um aviso; nunca libera nada
     * @param tamanhoDaPilha pilha de cada thread em bytes; 0 = o padrao da JVM
     */
    public static Barreira criar(int threads, Ambiente ambiente, long fatiaNs, long tamanhoDaPilha) {
        Barreira b = new Barreira(threads, ambiente, fatiaNs, tamanhoDaPilha);
        for (Trabalhador w : b.trabalhadores) {
            w.thread.start();
        }
        return b;
    }

    // ------------------------------------------------------------------------------------------------------------
    // thread do servidor: abrir, rodar, fechar
    // ------------------------------------------------------------------------------------------------------------

    /**
     * FECHADA -&gt; MONTANDO. Uma fase esquecida em MONTANDO (um tile em serie lancou antes de {@link #rodar}) e descartada
     * aqui, sem esperar ninguem: nenhum trabalhador soube dela. Devolve false, com {@link #motivoDaRecusa}, se a barreira
     * nao pode abrir fase: abortada, encerrada, thread de regiao morta, ou fase anterior ainda em RODANDO ou JUNCAO.
     */
    public boolean abrir() {
        Thread eu = Thread.currentThread();
        trava.lock();
        try {
            Thread s = servidor;
            if (s == null) {
                servidor = eu;
            } else if (s != eu) {
                throw new IllegalStateException("abrir chamado por " + eu.getName() + "; a thread do servidor desta barreira e " + s.getName());
            }
            String r = recusa();
            if (r != null) {
                motivoDaRecusa = r;
                return false;
            }
            if (estado == Fase.Estado.MONTANDO) {
                cEsquecidas.incrementAndGet();
            }
            estado = Fase.Estado.MONTANDO;
            return true;
        } finally {
            trava.unlock();
        }
    }

    /** Com a trava. */
    private String recusa() {
        if (encerrada) {
            return "barreira encerrada";
        }
        if (abortada) {
            return "fase abortada (parada do servidor)";
        }
        for (Trabalhador w : trabalhadores) {
            if (w.estado == Trabalhador.MORTO || (w.estado != Trabalhador.FIM && !w.thread.isAlive())) {
                return "thread de regiao morta: " + w.nome;
            }
        }
        if (estado == Fase.Estado.RODANDO) {
            return "fase anterior ainda rodando";
        }
        if (estado == Fase.Estado.JUNCAO) {
            return "a juncao da fase anterior nao foi fechada";
        }
        return null;
    }

    /**
     * MONTANDO -&gt; RODANDO -&gt; (barreira) -&gt; JUNCAO. Publica os lotes (os mais caros primeiro), espera todos fecharem e,
     * enquanto espera, atende os pedidos delegados. So volta com todos os lotes fechados: prazo nenhum faz voltar antes.
     * Lista vazia nao acorda ninguem. Com a barreira abortada ou sem thread de regiao viva, nenhum lote roda: todos voltam
     * como {@code SOBROU}.
     *
     * @return a fase, ja na juncao: o {@link Andamento} de cada lote e os pedidos
     */
    public Fase rodar(List<? extends Trabalho> lotes) {
        Thread eu = Thread.currentThread();
        if (eu != servidor) {
            throw new IllegalStateException("rodar fora da thread do servidor desta barreira: " + eu.getName());
        }
        if (estado != Fase.Estado.MONTANDO) {
            throw new IllegalStateException("rodar sem abrir: estado " + estado);
        }
        final int n = lotes.size();
        Andamento[] as = new Andamento[n];
        for (int i = 0; i < n; i++) {
            as[i] = new Andamento(lotes.get(i));
        }
        if (n > 1) {
            Arrays.sort(as, Andamento.MAIS_CARO_PRIMEIRO);
        }
        Fase f = new Fase(++numero, as);
        cFases.incrementAndGet();
        cLotes.addAndGet(n);
        final long t0 = System.nanoTime();
        boolean interrompida = false;
        trava.lock();
        try {
            interrompida = esperarConfirmacoes();
            if (n == 0) {
                cFasesVazias.incrementAndGet();
                estado = Fase.Estado.JUNCAO;
                return f;
            }
            recolherMortos(f);
            if (abortada || encerrada || vivos == 0 || f.semNovos) {
                f.semNovos = true;
                f.abortada = abortada;
                f.drenar(eu.getName());
                estado = Fase.Estado.JUNCAO;
                return f;
            }
            fase = f;
            estado = Fase.Estado.RODANDO;
            for (int k = Math.min(n, vivos); k > 0; k--) {
                condTrabalho.signal();
            }
            interrompida |= barreira(f, t0);
            estado = Fase.Estado.JUNCAO;
            liberarPortao();
            return f;
        } finally {
            f.nsBarreira = System.nanoTime() - t0;
            trava.unlock();
            if (interrompida) {
                eu.interrupt();
            }
        }
    }

    /** JUNCAO -&gt; FECHADA (ou MONTANDO -&gt; FECHADA, descartando a fase que nao rodou). */
    public void fechar() {
        Thread eu = Thread.currentThread();
        trava.lock();
        try {
            if (eu != servidor) {
                throw new IllegalStateException("fechar fora da thread do servidor desta barreira: " + eu.getName());
            }
            if (estado == Fase.Estado.RODANDO) {
                throw new IllegalStateException("fechar com a fase rodando");
            }
            estado = Fase.Estado.FECHADA;
        } finally {
            trava.unlock();
        }
    }

    /** Com a trava; so a thread do servidor. Devolve true se a thread foi interrompida durante a espera. */
    private boolean esperarConfirmacoes() {
        boolean interrompida = false;
        long proximoAviso = System.nanoTime() + fatiaNs;
        while (confirmacoesPendentes > 0) {
            long resta = proximoAviso - System.nanoTime();
            if (resta <= 0L) {
                int quantas = confirmacoesPendentes;
                trava.unlock();
                try {
                    logar("[paralelo] portao: " + quantas + " thread(s) liberada(s) na barreira anterior ainda nao seguiram; a fase nova espera por elas");
                } finally {
                    trava.lock();
                }
                proximoAviso = System.nanoTime() + fatiaNs;
                continue;
            }
            try {
                condServidor.awaitNanos(resta);
            } catch (InterruptedException e) {
                interrompida = true;
            }
        }
        return interrompida;
    }

    /**
     * A espera da barreira. Com a trava na entrada e na saida; so a thread do servidor. Sai quando todo lote esta fechado.
     * Devolve true se a thread foi interrompida durante a espera (a espera continua; quem chamou repoe o sinal no fim).
     */
    private boolean barreira(Fase f, long t0) {
        final int n = f.total();
        boolean interrompida = false;
        long proximoDespejo = t0 + fatiaNs;
        for (;;) {
            recolherMortos(f);
            if (f.semNovos) {
                f.drenar(servidor.getName());
            }
            if (f.fechados.get() >= n && pendentes.isEmpty()) {
                return interrompida;
            }
            if (!pendentes.isEmpty() && !algumEm(Trabalhador.RODANDO, -1)) {
                atender(f);
                continue;
            }
            long resta = proximoDespejo - System.nanoTime();
            if (resta <= 0L) {
                List<Foto> fotos = fotografar(false);
                int naFila = pendentes.size();
                int portao = noPortao.size();
                f.despejos = f.despejos + 1;
                cDespejos.incrementAndGet();
                trava.unlock();
                try {
                    despejar(f, t0, fotos, naFila, portao);
                } finally {
                    trava.lock();
                }
                proximoDespejo = System.nanoTime() + fatiaNs;
                continue;
            }
            try {
                condServidor.awaitNanos(resta);
            } catch (InterruptedException e) {
                interrompida = true;
            }
        }
    }

    /**
     * Com a trava; so a thread do servidor, dentro da barreira. Conta as threads de regiao que acabaram sem ser por
     * {@code encerrar}, fecha o lote aberto de cada uma como MORREU e tira da fila os pedidos delas.
     */
    private void recolherMortos(Fase f) {
        for (Trabalhador w : trabalhadores) {
            if (w.estado != Trabalhador.FIM && w.estado != Trabalhador.MORTO && !w.thread.isAlive()) {
                marcarSaida(w, false, null);
            }
            if (w.estado == Trabalhador.MORTO && !w.contado) {
                w.contado = true;
                cMortos.incrementAndGet();
                f.mortos = f.mortos + 1;
                f.semNovos = true;
                Andamento a = w.daVez;
                if (a != null) {
                    f.fechar(a, Andamento.Fim.MORREU, a.feitos, w.causaDaMorte, 0L, w.nome);
                }
                for (int i = pendentes.size() - 1; i >= 0; i--) {
                    if (pendentes.get(i).quem == w) {
                        pendentes.remove(i);
                    }
                }
            }
        }
        if (pendentes.isEmpty() && emServico == null && (sinais & SINAL_PAUSA) != 0) {
            sinais &= ~SINAL_PAUSA;
            condRetomar.signalAll();
        }
    }

    /**
     * Atende UM pedido: o do lote de menor chave. Com a trava na entrada e na saida; solta a trava para rodar a operacao.
     * So e chamado com nenhum trabalhador em RODANDO: todos estao ociosos, parados entre dois itens ou no comeco do
     * proprio pedido, e nenhum volta a rodar enquanto o bit de pausa estiver ligado.
     */
    private void atender(Fase f) {
        Pedido p = pendentes.get(0);
        for (int i = 1; i < pendentes.size(); i++) {
            Pedido q = pendentes.get(i);
            if (q.chave < p.chave || (q.chave == p.chave && q.seq < p.seq)) {
                p = q;
            }
        }
        pendentes.remove(p);
        if (p.sujar != null) {
            for (long k : p.sujar) {
                Andamento alvo = f.daChave(k);
                if (alvo != null) {
                    alvo.sujo = true;
                }
            }
        }
        emServico = p;
        if (p.monitor != null) {
            monitorCedido = p.monitor;
            condPortao.signalAll();
        }
        trava.unlock();
        try {
            executar(p);
        } finally {
            trava.lock();
            monitorCedido = null;
            emServico = null;
            p.pronto = true;
            condPedidos.signalAll();
            if (pendentes.isEmpty()) {
                sinais &= ~SINAL_PAUSA;
                condRetomar.signalAll();
            }
        }
    }

    /** Sem a trava; thread do servidor. O que a operacao lancar fica no pedido e volta para quem pediu. */
    private void executar(Pedido p) {
        try {
            Object m = p.monitor;
            if (m != null) {
                // quem declarou o monitor garante que a operacao o toma logo na entrada; tomar aqui (o monitor e reentrante)
                // deixa a thread do servidor presa enquanto uma thread estranha, liberada no portao, ainda o segura
                synchronized (m) {
                    monitorCedido = null;
                    p.resultado = p.op.rodar();
                }
            } else {
                p.resultado = p.op.rodar();
            }
        } catch (Throwable t) {
            p.erro = t;
        }
    }

    /** Com a trava: na passagem RODANDO -&gt; JUNCAO, libera quem espera no portao e passa a esperar a confirmacao de cada um. */
    private void liberarPortao() {
        if (noPortao.isEmpty()) {
            return;
        }
        for (EsperaPortao e : noPortao) {
            if (!e.liberada) {
                e.liberada = true;
                confirmacoesPendentes++;
            }
        }
        condPortao.signalAll();
    }

    // ------------------------------------------------------------------------------------------------------------
    // trabalhadores (chamados so pela propria thread de regiao)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Estaciona o trabalhador ate haver uma fase nova com lote para pegar. Devolve a fase, ja com o trabalhador em
     * RODANDO, ou null quando a barreira foi encerrada.
     */
    Fase esperarFase(Trabalhador w) {
        trava.lock();
        try {
            w.estado = Trabalhador.OCIOSO;
            if ((sinais & SINAL_PAUSA) != 0) {
                condServidor.signal();
            }
            if (abortada) {
                condParados.signalAll();
            }
            for (;;) {
                if (encerrada) {
                    return null;
                }
                Fase f = fase;
                if (f != null && f != w.vista) {
                    if (f.semNovos || f.proximo.get() >= f.total()) {
                        w.vista = f;
                        continue;
                    }
                    if ((sinais & SINAL_PAUSA) != 0) {
                        condRetomar.awaitUninterruptibly();
                        continue;
                    }
                    w.vista = f;
                    w.estado = Trabalhador.RODANDO;
                    cAcordados.incrementAndGet();
                    return f;
                }
                condTrabalho.awaitUninterruptibly();
                cDespertares.incrementAndGet();
            }
        } finally {
            trava.unlock();
        }
    }

    /**
     * O caminho lento do ponto seguro (entre dois itens): estaciona durante a pausa e diz se o lote continua. Na pausa o
     * trabalhador fica em PARADO e so volta a RODANDO com a pausa desligada; no aborto fica em PARADO e nao tica mais.
     */
    int pontoSeguro(Trabalhador w, Andamento a) {
        if ((sinais & (SINAL_PAUSA | SINAL_ABORTO)) != 0) {
            trava.lock();
            try {
                if ((sinais & SINAL_PAUSA) != 0 && !abortada) {
                    w.estado = Trabalhador.PARADO;
                    condServidor.signal();
                    final long t0 = System.nanoTime();
                    while ((sinais & SINAL_PAUSA) != 0 && !abortada) {
                        condRetomar.awaitUninterruptibly();
                    }
                    w.nsParado += System.nanoTime() - t0;
                    if (!abortada) {
                        w.estado = Trabalhador.RODANDO;
                    }
                }
                if (abortada) {
                    w.estado = Trabalhador.PARADO;
                    condServidor.signal();
                    condParados.signalAll();
                    return PARA_ABORTO;
                }
            } finally {
                trava.unlock();
            }
        }
        return a.sujo ? PARA_SUJO : SEGUE;
    }

    /** Depois de fechar um lote: acorda a thread do servidor se a barreira pode passar ou se ha lotes para ela drenar. */
    void loteFechado(Fase f) {
        if (f.fechados.get() >= f.total() || f.semNovos) {
            trava.lock();
            try {
                condServidor.signal();
            } finally {
                trava.unlock();
            }
        }
    }

    /** O fim da thread de regiao (no {@code finally} dela). {@code normal}: saiu por {@code encerrar}. */
    void saiu(Trabalhador w, boolean normal, Throwable causa) {
        trava.lock();
        try {
            marcarSaida(w, normal, causa);
        } finally {
            trava.unlock();
        }
        if (!normal) {
            try {
                ambiente.erro("[paralelo] thread de regiao " + w.nome + " morreu"
                        + (w.daVez != null ? " com o lote " + w.daVez.chave + " aberto" : "") + "; a fase trata como falha", causa);
            } catch (Throwable ignorado) {
                // o log nao pode impedir o fim da thread
            }
        }
    }

    /** Com a trava. */
    private void marcarSaida(Trabalhador w, boolean normal, Throwable causa) {
        if (w.estado == Trabalhador.FIM || w.estado == Trabalhador.MORTO) {
            return;
        }
        w.estado = normal ? Trabalhador.FIM : Trabalhador.MORTO;
        w.causaDaMorte = causa;
        vivos--;
        condServidor.signal();
        condParados.signalAll();
    }

    // ------------------------------------------------------------------------------------------------------------
    // guardas: papel, pedido delegado, portao
    // ------------------------------------------------------------------------------------------------------------

    /** Quem e a thread atual para a fase: {@link #FORA}, {@link #TRABALHADOR}, {@link #ATENDENDO}, {@link #SERVIDOR} ou {@link #ESTRANHA}. */
    public int papel() {
        if (estado != Fase.Estado.RODANDO) {
            return FORA;
        }
        Thread eu = Thread.currentThread();
        if (meu(eu) != null) {
            return TRABALHADOR;
        }
        if (eu == servidor) {
            return emServico != null ? ATENDENDO : SERVIDOR;
        }
        return ESTRANHA;
    }

    private Trabalhador meu(Thread t) {
        if (t instanceof ThreadRegiao) {
            Trabalhador w = ((ThreadRegiao) t).trabalhador;
            if (w.barreira == this) {
                return w;
            }
        }
        return null;
    }

    /**
     * Pedido delegado (plano, secao 3). Chamado pela guarda, na thread de regiao, ANTES de qualquer efeito da operacao
     * proibida e sem monitor na mao. O trabalhador entrega a operacao e espera; a thread do servidor espera os outros
     * trabalhadores pararem (no fim do item corrente, ou no comeco do proprio pedido), roda a operacao e devolve o
     * resultado. O que a operacao lancar e relancado aqui, o mesmo objeto. Nenhum prazo libera a operacao: a cada fatia
     * vencida sai um aviso com a pilha da thread do servidor, e a espera continua.
     *
     * <p>Com o pedido, a fase para de entregar lotes novos. Quem pediu so volta a rodar depois de todos os pedidos da
     * fila serem atendidos.
     *
     * <p>Chamado pela thread do servidor enquanto ela atende um pedido (pedido aninhado), ou por qualquer thread que nao
     * seja um trabalhador com lote aberto, roda a operacao ali mesmo e so conta.
     *
     * @param monitor monitor que a operacao toma logo na entrada (o do gerenciador de plugins, para um evento sincrono),
     *        ou null. Com ele, a operacao roda dentro de {@code synchronized (monitor)}, e uma thread estranha que
     *        espera no {@link #portao} segurando esse monitor passa antes, com todos os trabalhadores parados.
     * @param sujar chaves dos lotes em que a operacao escreve (ou null): cada um para no fim do item corrente e o resto
     *        dele fica para a juncao
     */
    public <T> T pedir(Operacao<T> op, Object monitor, long[] sujar) {
        Thread eu = Thread.currentThread();
        Trabalhador w = meu(eu);
        Andamento a = w == null ? null : w.daVez;
        if (a == null) {
            if (eu == servidor && emServico != null) {
                cAninhados.incrementAndGet();
            } else {
                cForaDeLugar.incrementAndGet();
            }
            return direto(op);
        }
        Fase f = w.vista;
        int item = a.feitos;
        Pedido p = new Pedido(op, monitor, sujar == null ? null : sujar.clone(), w, a, item, descrever(a, item),
                eu.getName(), eu.getStackTrace());
        f.semNovos = true;
        f.pedidos.add(p);
        cPedidos.incrementAndGet();
        boolean interrompida = false;
        final long t0 = System.nanoTime();
        trava.lock();
        try {
            p.seq = ++seqPedido;
            pendentes.add(p);
            w.estado = Trabalhador.PEDINDO;
            sinais |= SINAL_PAUSA;
            condServidor.signal();
            long proximoAviso = t0 + fatiaNs;
            while (!p.pronto) {
                long resta = proximoAviso - System.nanoTime();
                if (resta <= 0L) {
                    trava.unlock();
                    try {
                        avisarEsperaDoPedido(p, t0);
                    } finally {
                        trava.lock();
                    }
                    proximoAviso = System.nanoTime() + fatiaNs;
                    continue;
                }
                try {
                    condPedidos.awaitNanos(resta);
                } catch (InterruptedException e) {
                    interrompida = true;
                }
            }
            // respondido; mas enquanto houver outro pedido na fila ou em atendimento, este trabalhador continua parado
            while ((sinais & SINAL_PAUSA) != 0) {
                condRetomar.awaitUninterruptibly();
            }
            w.estado = Trabalhador.RODANDO;
        } finally {
            trava.unlock();
            w.nsParado += System.nanoTime() - t0;
            if (interrompida) {
                eu.interrupt();
            }
        }
        if (p.erro != null) {
            throw Barreira.<RuntimeException>relancar(p.erro);
        }
        @SuppressWarnings("unchecked")
        T r = (T) p.resultado;
        return r;
    }

    private static <T> T direto(Operacao<T> op) {
        try {
            return op.rodar();
        } catch (Throwable t) {
            throw Barreira.<RuntimeException>relancar(t);
        }
    }

    /** Relanca o mesmo objeto, qualquer que seja o tipo (a operacao e o metodo original: a excecao dele nao muda). */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException relancar(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * Portao da thread estranha (plano, secao 3): chamado pela guarda de escrita, de carga de chunk ou de salvamento
     * quando a thread nao e a do servidor nem uma thread de regiao. Com a fase em RODANDO, a thread espera aqui a
     * barreira passar e so entao segue; nenhum prazo a libera (a cada fatia vencida sai um aviso). Fora de RODANDO volta
     * na hora.
     *
     * <p>Limite conhecido: o portao so segura a ENTRADA. Uma escrita que passou aqui fora da fase e ainda esta em curso
     * quando a fase seguinte abre corre contra os trabalhadores, como hoje corre contra a thread do servidor.
     *
     * @param monitorIndicado o monitor da excecao (o do gerenciador de plugins), ou null. Se a thread o segura e a thread
     *        do servidor esta para entrar nele para atender um pedido delegado, a thread passa (ver {@link #pedir}).
     * @return {@link #PORTAO_DIRETO}, {@link #PORTAO_ESPEROU}, {@link #PORTAO_MONITOR} ou {@link #PORTAO_ABERTO}
     */
    public int portao(Object monitorIndicado) {
        if (estado != Fase.Estado.RODANDO) {
            return PORTAO_DIRETO;
        }
        Thread eu = Thread.currentThread();
        if (eu == servidor || meu(eu) != null) {
            return PORTAO_DIRETO;
        }
        Object meuMonitor = monitorIndicado != null && Thread.holdsLock(monitorIndicado) ? monitorIndicado : null;
        EsperaPortao e = null;
        boolean interrompida = false;
        final long t0 = System.nanoTime();
        trava.lock();
        try {
            if (estado != Fase.Estado.RODANDO) {
                return PORTAO_DIRETO;
            }
            if (portaoAberto) {
                return PORTAO_ABERTO;
            }
            if (meuMonitor != null && monitorCedido == meuMonitor) {
                cPortaoMonitor.incrementAndGet();
                return PORTAO_MONITOR;
            }
            e = new EsperaPortao(eu, meuMonitor, eu.getStackTrace());
            noPortao.add(e);
            cPortaoEsperas.incrementAndGet();
            long proximoAviso = t0 + fatiaNs;
            for (;;) {
                if (e.liberada) {
                    return PORTAO_ESPEROU;
                }
                if (portaoAberto) {
                    return PORTAO_ABERTO;
                }
                if (estado != Fase.Estado.RODANDO) {
                    return PORTAO_ESPEROU;
                }
                if (meuMonitor != null && monitorCedido == meuMonitor) {
                    cPortaoMonitor.incrementAndGet();
                    return PORTAO_MONITOR;
                }
                long resta = proximoAviso - System.nanoTime();
                if (resta <= 0L) {
                    trava.unlock();
                    try {
                        avisarEsperaNoPortao(e, t0);
                    } finally {
                        trava.lock();
                    }
                    proximoAviso = System.nanoTime() + fatiaNs;
                    continue;
                }
                try {
                    condPortao.awaitNanos(resta);
                } catch (InterruptedException ex) {
                    interrompida = true;
                }
            }
        } finally {
            if (e != null) {
                noPortao.remove(e);
                if (e.liberada) {
                    confirmacoesPendentes--;
                    condServidor.signal();
                }
            }
            trava.unlock();
            if (interrompida) {
                eu.interrupt();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // abortar e encerrar (qualquer thread)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Fase abortada (plano, secao 7, regra 5: a parada do servidor veio de outra thread com a fase rodando). A partir
     * daqui nenhum trabalhador pega outro item, nenhuma fase nova abre e os pedidos delegados pendentes ainda sao
     * atendidos pela thread do servidor. A chamada espera, no maximo {@code prazoNs}, os trabalhadores sairem do meio de
     * um item; depois abre o portao e volta. Nao tem volta: a barreira fica abortada.
     *
     * <p>Chamada pela propria thread do servidor ou por uma thread de regiao, nao espera (esperaria por si mesma).
     *
     * @return quem ainda estava no meio de um item no fim do prazo (vazia = todos pararam): conservacao suspeita no lote
     *         de cada um
     */
    public List<Preso> abortar(long prazoNs) {
        List<Foto> fotos = abortarEEsperar(prazoNs, false);
        List<Preso> presos = new ArrayList<Preso>();
        for (Foto ft : fotos) {
            presos.add(preso(ft));
        }
        if (!presos.isEmpty()) {
            StringBuilder sb = new StringBuilder("[paralelo] fase abortada com ").append(presos.size())
                    .append(" trabalhador(es) no meio de um item depois do prazo de ").append(seg(prazoNs))
                    .append(": conservacao suspeita nestes lotes\n");
            for (Preso p : presos) {
                sb.append("  ").append(p).append('\n');
                Despejo.quadros(sb, p.pilha);
            }
            logar(sb.toString());
        }
        return presos;
    }

    /**
     * Liga o aborto (e, com {@code encerrando}, o encerramento, na mesma secao da trava), espera ate {@code prazoNs} os
     * trabalhadores sairem do meio de um item e abre o portao. Devolve a foto de quem ainda estava no meio de um item.
     */
    private List<Foto> abortarEEsperar(long prazoNs, boolean encerrando) {
        Thread eu = Thread.currentThread();
        boolean interrompida = false;
        trava.lock();
        try {
            abortada = true;
            sinais |= SINAL_ABORTO;
            Fase f = fase;
            if (f != null && estado == Fase.Estado.RODANDO) {
                f.semNovos = true;
                f.abortada = true;
            }
            if (encerrando) {
                encerrada = true;
                if (eu == servidor && estado == Fase.Estado.MONTANDO) {
                    estado = Fase.Estado.FECHADA;
                }
                condTrabalho.signalAll();
            }
            condRetomar.signalAll();
            condServidor.signal();
            if (eu != servidor && meu(eu) == null) {
                final long limite = System.nanoTime() + prazoNs;
                while (algumEm(Trabalhador.RODANDO, Trabalhador.PEDINDO)) {
                    long resta = limite - System.nanoTime();
                    if (resta <= 0L) {
                        break;
                    }
                    try {
                        condParados.awaitNanos(resta);
                    } catch (InterruptedException e) {
                        interrompida = true;
                    }
                }
            }
            portaoAberto = true;
            condPortao.signalAll();
            return fotografar(true);
        } finally {
            trava.unlock();
            if (interrompida) {
                eu.interrupt();
            }
        }
    }

    /**
     * Encerra as threads de regiao (parada do servidor): aborta a fase, se houver uma rodando, manda as threads
     * acabarem e espera por elas. Tudo dentro de {@code prazoNs}; quem esperava no portao e liberado. Chamado pela thread
     * do servidor com a fase esquecida em MONTANDO, descarta a fase. Nao tem volta. Chamado por uma thread de regiao, ou
     * pela thread do servidor enquanto atende um pedido, so sinaliza (nao espera por si mesma).
     *
     * <p>Como as outras esperas do nucleo, esta nao e largada por interrupcao: se a thread que chama for interrompida (ou ja
     * chegar com o sinal ligado), a espera continua ate o prazo e o sinal e reposto na saida.
     *
     * @return as threads de regiao que nao acabaram no prazo (vazia = todas acabaram)
     */
    public List<Preso> encerrar(long prazoNs) {
        Thread eu = Thread.currentThread();
        final long limite = System.nanoTime() + prazoNs;
        abortarEEsperar(prazoNs, true);
        boolean interrompida = false;
        // uma thread de regiao, ou a thread do servidor no meio de um atendimento, esperaria por si mesma
        if (meu(eu) == null && !(eu == servidor && emServico != null)) {
            for (Trabalhador w : trabalhadores) {
                // interrompida, a thread que chama nao larga a espera: repete o join da MESMA thread ate ela acabar ou o
                // prazo vencer (o join interrompido lanca na hora, com a thread de regiao ainda viva)
                while (w.thread.isAlive()) {
                    long resta = limite - System.nanoTime();
                    if (resta <= 0L) {
                        break;
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedJoin(w.thread, resta);
                    } catch (InterruptedException e) {
                        interrompida = true;
                    }
                }
            }
        }
        if (interrompida) {
            eu.interrupt();
        }
        List<Foto> fotos = new ArrayList<Foto>();
        trava.lock();
        try {
            for (Trabalhador w : trabalhadores) {
                if (w.thread.isAlive()) {
                    fotos.add(new Foto(w, w.estado, w.daVez));
                }
            }
        } finally {
            trava.unlock();
        }
        List<Preso> presos = new ArrayList<Preso>();
        for (Foto ft : fotos) {
            presos.add(preso(ft));
        }
        if (!presos.isEmpty()) {
            StringBuilder sb = new StringBuilder("[paralelo] encerrar: ").append(presos.size())
                    .append(" thread(s) de regiao nao acabaram no prazo de ").append(seg(prazoNs)).append('\n');
            for (Preso p : presos) {
                sb.append("  ").append(p).append('\n');
                Despejo.quadros(sb, p.pilha);
            }
            logar(sb.toString());
        }
        return presos;
    }

    // ------------------------------------------------------------------------------------------------------------
    // apoio
    // ------------------------------------------------------------------------------------------------------------

    /** Com a trava: algum trabalhador esta no estado {@code a} ou {@code b}? */
    private boolean algumEm(int a, int b) {
        for (Trabalhador w : trabalhadores) {
            if (w.estado == a || w.estado == b) {
                return true;
            }
        }
        return false;
    }

    /** Com a trava. {@code soNoMeio}: so quem esta no meio de um item (rodando ou pedindo); senao, todos os que tem lote aberto. */
    private List<Foto> fotografar(boolean soNoMeio) {
        List<Foto> fotos = new ArrayList<Foto>();
        for (Trabalhador w : trabalhadores) {
            int e = w.estado;
            boolean noMeio = e == Trabalhador.RODANDO || e == Trabalhador.PEDINDO;
            if (noMeio || (!soNoMeio && e == Trabalhador.PARADO)) {
                fotos.add(new Foto(w, e, w.daVez));
            }
        }
        return fotos;
    }

    private Preso preso(Foto ft) {
        Andamento a = ft.a;
        StackTraceElement[] pilha;
        try {
            pilha = ft.w.thread.getStackTrace();
        } catch (Throwable t) {
            pilha = new StackTraceElement[0];
        }
        if (a == null) {
            return new Preso(ft.w.nome, Trabalhador.nomeDoEstado(ft.estado), false, 0L, 0, 0, "", pilha);
        }
        int item = a.feitos;
        return new Preso(ft.w.nome, Trabalhador.nomeDoEstado(ft.estado), true, a.chave, item, a.tamanho, descrever(a, item), pilha);
    }

    private static String descrever(Andamento a, int item) {
        if (item >= a.tamanho) {
            return "(depois do ultimo item)";
        }
        try {
            return String.valueOf(a.trabalho.descrever(item));
        } catch (Throwable t) {
            return "(descricao falhou: " + t + ")";
        }
    }

    private static String seg(long ns) {
        long dec = ns / 100000000L;
        return (dec / 10L) + "." + (dec % 10L) + " s";
    }

    private void logar(String linha) {
        try {
            ambiente.log(linha);
        } catch (Throwable ignorado) {
            // o log nao pode derrubar a barreira
        }
    }

    /** Sem a trava; thread do servidor. */
    private void despejar(Fase f, long t0, List<Foto> fotos, int naFila, int portao) {
        StringBuilder sb = new StringBuilder("[paralelo] barreira: fase ").append(f.numero).append(" esperando ha ")
                .append(seg(System.nanoTime() - t0)).append(" (").append(f.fechados.get()).append('/').append(f.total())
                .append(" lotes fechados, ").append(naFila).append(" pedido(s) na fila, ").append(portao)
                .append(" thread(s) no portao). Ninguem e interrompido; trabalhadores com lote aberto: ").append(fotos.size()).append('\n');
        for (Foto ft : fotos) {
            Andamento a = ft.a;
            sb.append("  ").append(ft.w.nome).append(' ').append(Trabalhador.nomeDoEstado(ft.estado));
            if (a != null) {
                int item = a.feitos;
                sb.append(", lote=").append(a.chave).append(" item ").append(item).append(" de ").append(a.tamanho).append(": ")
                        .append(descrever(a, item));
            }
            sb.append('\n');
            Despejo.pilha(sb, ft.w.thread);
        }
        Despejo.emDeadlock(sb);
        logar(sb.toString());
    }

    /** Sem a trava; thread de regiao que pediu. */
    private void avisarEsperaDoPedido(Pedido p, long t0) {
        StringBuilder sb = new StringBuilder("[paralelo] ").append(p).append(" esperando ha ").append(seg(System.nanoTime() - t0))
                .append("; nenhum prazo libera a operacao. Trabalhadores: ").append(estados()).append(". Thread do servidor:\n");
        Despejo.pilha(sb, servidor);
        Despejo.emDeadlock(sb);
        logar(sb.toString());
    }

    /** Sem a trava; thread estranha. */
    private void avisarEsperaNoPortao(EsperaPortao e, long t0) {
        StringBuilder sb = new StringBuilder("[paralelo] portao: thread \"").append(e.thread.getName()).append("\" esperando ha ")
                .append(seg(System.nanoTime() - t0)).append(" a barreira passar")
                .append(e.monitor != null ? " (segura o monitor indicado)" : "").append("; nenhum prazo a libera. Chegou por:\n");
        Despejo.quadros(sb, e.pilha);
        logar(sb.toString());
    }

    // ------------------------------------------------------------------------------------------------------------
    // leitura (qualquer thread)
    // ------------------------------------------------------------------------------------------------------------

    public Fase.Estado estado() {
        return estado;
    }

    /** A ultima fase publicada para os trabalhadores (a que esta rodando, se o estado e RODANDO), ou null. */
    public Fase faseAtual() {
        return fase;
    }

    public boolean abortada() {
        return abortada;
    }

    public boolean encerrada() {
        return encerrada;
    }

    public int threads() {
        return trabalhadores.length;
    }

    /** A thread de regiao {@code i} (0..threads-1). */
    public ThreadRegiao thread(int i) {
        return trabalhadores[i].thread;
    }

    /** Por que o ultimo {@link #abrir} devolveu false (ou null). */
    public String motivoDaRecusa() {
        trava.lock();
        try {
            return motivoDaRecusa;
        } finally {
            trava.unlock();
        }
    }

    /** O estado de cada trabalhador, na ordem das threads: "ocioso", "rodando", "parado", "pedindo", "fim" ou "morto". */
    public String estados() {
        StringBuilder sb = new StringBuilder();
        trava.lock();
        try {
            for (Trabalhador w : trabalhadores) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(Trabalhador.nomeDoEstado(w.estado));
            }
        } finally {
            trava.unlock();
        }
        return sb.toString();
    }

    /** Pedidos delegados na fila (sem contar o que esta em atendimento). */
    public int pedidosNaFila() {
        trava.lock();
        try {
            return pendentes.size();
        } finally {
            trava.unlock();
        }
    }

    /** Threads estranhas esperando no portao. */
    public int esperandoNoPortao() {
        trava.lock();
        try {
            return noPortao.size();
        } finally {
            trava.unlock();
        }
    }

    public long fases() {
        return cFases.get();
    }

    public long fasesVazias() {
        return cFasesVazias.get();
    }

    /** Fases que ficaram em MONTANDO e foram descartadas no {@code abrir} seguinte. */
    public long fasesEsquecidas() {
        return cEsquecidas.get();
    }

    public long lotes() {
        return cLotes.get();
    }

    /** Quantas vezes uma thread de regiao foi tirada do estacionamento (por uma fase nova ou pelo encerramento). */
    public long despertares() {
        return cDespertares.get();
    }

    /** Quantas vezes uma thread de regiao entrou numa fase para pegar lote. */
    public long acordados() {
        return cAcordados.get();
    }

    /** Pedidos delegados (violacoes), um por pedido de trabalhador. */
    public long pedidos() {
        return cPedidos.get();
    }

    /** Pedidos feitos pela thread do servidor enquanto atendia outro (rodaram ali mesmo; nao contam como violacao nova). */
    public long pedidosAninhados() {
        return cAninhados.get();
    }

    /** Pedidos de quem nao era um trabalhador com lote aberto (rodaram ali mesmo). */
    public long pedidosForaDeLugar() {
        return cForaDeLugar.get();
    }

    /** Vezes em que uma thread estranha teve de esperar no portao. */
    public long esperasNoPortao() {
        return cPortaoEsperas.get();
    }

    /** Vezes em que uma thread estranha passou pela excecao do monitor. */
    public long passagensPeloMonitor() {
        return cPortaoMonitor.get();
    }

    public long despejos() {
        return cDespejos.get();
    }

    public long mortos() {
        return cMortos.get();
    }
}
