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
package hoshiestar.orcamento;

import hoshiestar.regioes.FotoMundo;
import hoshiestar.regioes.Mapa;
import hoshiestar.regioes.MapaSecoes;
import hoshiestar.regioes.Regionizador;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * O nucleo do orcamento de tick por regiao (Etapa 2.5, Degrau A): regionizador de uma thread, tempo por regiao, TPS e p95,
 * deteccao de "estourou/voltou" com histerese e alertas. Nada do Minecraft aparece aqui (o jogo e o {@link Jogo}; os testes
 * entregam um falso e um relogio falso).
 *
 * <p><b>Threads:</b> tudo que muda estado roda na thread do servidor (os ganchos de tick, de tile, de entidade...). Outras
 * threads (comando por RCON) so leem o {@link Retrato} (volatile, imutavel) e pedem coisas por uma fila que a thread do
 * servidor esvazia no inicio do tick. Nenhuma trava.
 *
 * <p><b>O corte:</b> este nucleo DECIDE no inicio de cada tick quem roda ({@link #rodaEm}, {@link #rodaNesteTick}, e o
 * retorno de {@link #tile}, {@link #entidade}, {@link #agendado}, {@link #chunkAleatorio}); a decisao vale para o tick
 * inteiro (uma regiao nunca roda metade do tick). Os ganchos dos patches pulam o que a decisao manda pular. Em modo medir
 * a decisao e sempre "roda". Erro nosso num gancho: a regiao do ponto volta a ticar normal ({@link #falhaNoPonto}); erro
 * no inicio/fim do tick ou erros demais: o orcamento inteiro desliga ({@link #falha}) e tudo roda.
 */
public final class Nucleo {

    public static final int ALERTA_PESADA = 1, ALERTA_NORMAL = 2, ALERTA_CORTE = 3, ALERTA_FIM_CORTE = 4, ALERTA_ERRO = 5;
    /** Erros de gancho tolerados (cada um protege a regiao do ponto); passou disto, o orcamento inteiro desliga. */
    static final int LIMITE_ERROS_GANCHO = 20;
    private static final int TICKS_ANEL = 1200;
    private static final long JANELA_TPS_MS = 20000L;
    private static final int TOPO_CLASSES = 5;

    private final Parametros p;
    private final Jogo jogo;
    private final Tempo tempo;
    private final boolean ferramentasTeste;
    private final Regionizador regionizador = new Regionizador();
    private final LimiteAlertas limite;
    private final ConjuntoLong desconhecidas = new ConjuntoLong(4096);
    /** Ids das regioes que tiveram erro num gancho: ficam protegidas (rodam todo tick). */
    private final ConjuntoLong comErro = new ConjuntoLong(64);
    private int errosGancho;
    private final ConcurrentLinkedQueue<Runnable> pedidos = new ConcurrentLinkedQueue<Runnable>();
    private final Map<String, String> modDaClasse = new HashMap<String, String>();

    private volatile boolean ativo = true;
    private volatile boolean cortar;
    private volatile Retrato retrato;

    // ---- so a thread do servidor daqui para baixo
    private long tick;
    private Mapa mapa;
    private Estado[] est = new Estado[0];
    private boolean[] decisao = new boolean[0];
    /** A regiao esta em corte (N &gt; 1, sem protecao) neste tick: na rodada dela, os agendados dela usam a sobra do teto. */
    private boolean[] emCorte = new boolean[0];
    /** Copia de emCorte e decisao tirada no comeco de cada selecao dos agendados (o erro num gancho nao a muda). */
    private boolean[] acumuloNaSelecao = new boolean[0];
    /** Alguma regiao nao roda neste tick? (sem corte nenhum, a puxada dos ticks agendados usa o laco original) */
    private boolean algumCortado;
    /** Selecao da puxada dos ticks agendados (vive so durante a puxada de um mundo). */
    private final ArrayList<Object> selecionadas = new ArrayList<Object>();
    private int posSelecionada;
    private String[][] classesTop = new String[0][];
    private long proximoId = 1;
    private long ultimoRecalcTick = Long.MIN_VALUE / 2;
    private boolean pedidoRecalculo;
    private boolean suspenderTodas;
    private int recalculos;
    private long ultimoRecalcNs;
    private int desconhecidasVistas;
    // tempo
    private int atual = -1;
    private long iniAtualNs;
    private long tickIniNs;
    private long chaveCache;
    private int idxCache = -1;
    private boolean cacheOk;
    // servidor
    private final double[] sMs = new double[TICKS_ANEL];
    private final long[] sCarimbo = new long[TICKS_ANEL];
    private int sN, sPos;
    private long primeiroTickMs = -1;
    private int folgaTicks;
    // registro de teste
    private RegistroCsv registro;

    public Nucleo(Parametros p, Jogo jogo, Tempo tempo, boolean cortarInicial, boolean ferramentasTeste) {
        this.p = p;
        this.jogo = jogo;
        this.tempo = tempo;
        this.cortar = cortarInicial;
        this.ferramentasTeste = ferramentasTeste;
        this.limite = new LimiteAlertas(p.alertaIntervaloMin * 60000L, Parametros.ALERTAS_POR_MINUTO);
    }

    public boolean ativo() {
        return ativo;
    }

    public boolean cortar() {
        return cortar;
    }

    public Retrato retrato() {
        return retrato;
    }

    /** Quantas vezes o mapa foi recalculado (para os testes). */
    int recalculos() {
        return recalculos;
    }

    /** Quantas secoes desconhecidas os ganchos viram desde o boot (para os testes). */
    int desconhecidasVistas() {
        return desconhecidasVistas;
    }

    /** O tick do orcamento (para os testes). */
    long tick() {
        return tick;
    }

    public Parametros parametros() {
        return p;
    }

    public boolean ferramentasTeste() {
        return ferramentasTeste;
    }

    /** Qualquer falha nossa desliga o orcamento e deixa o servidor seguir (nada fica cortado: tudo roda). */
    public void falha(Throwable t) {
        if (ativo) {
            ativo = false;
            pedidos.clear(); // ninguem mais esvazia a fila
            try {
                jogo.log("orcamento DESLIGADO por erro interno (o servidor segue como o original): " + t);
            } catch (Throwable ignorar) {
                // nada
            }
        }
    }

    /**
     * Erro nosso num gancho (tile, entidade, tick agendado, tick aleatorio, puxada): a regiao do ponto passa a ser protegida
     * (roda todo tick, desde ja) e o erro vai para o log com limitador (1 por regiao a cada alerta_intervalo_min, no maximo
     * 10 por minuto). Sem regiao achada, erro no proprio tratamento ou mais de {@link #LIMITE_ERROS_GANCHO} erros: desliga
     * o orcamento inteiro ({@link #falha}). Nunca lanca.
     */
    public void falhaNoPonto(int dim, int bx, int bz, Throwable t) {
        if (!ativo) {
            return;
        }
        try {
            errosGancho++;
            Mapa m = mapa;
            int r = m == null ? -1 : m.regiaoNoBloco(dim, bx, bz);
            if (errosGancho > LIMITE_ERROS_GANCHO || r < 0 || r >= est.length) {
                falha(t);
                return;
            }
            Estado e = est[r];
            e.protecao = "erro interno";
            e.n = 1;
            e.acc = 0;
            e.ticksMenor = 0;
            decisao[r] = true;
            emCorte[r] = false;
            comErro.adicionar(e.id);
            if (limite.permitir(e.id, ALERTA_ERRO, tempo.milis())) {
                jogo.log("orcamento: erro interno no gancho da regiao " + e.id + " (dim " + m.dim[r]
                        + "); ela volta a ticar todo tick: " + t);
            }
        } catch (Throwable t2) {
            falha(t2);
        }
    }

    /** Erros de gancho vistos desde o boot (para os testes). */
    int errosGancho() {
        return errosGancho;
    }

    // =====================================================================================================
    // Pedidos de outras threads (comando)
    // =====================================================================================================

    /**
     * Poe o pedido na fila que a thread do servidor esvazia no inicio do tick. Com o nucleo desligado por erro ninguem
     * esvazia a fila: o pedido e descartado (achado 5 da revisao: antes a fila crescia sem limite).
     */
    private void pedir(Runnable r) {
        if (!ativo) {
            return;
        }
        pedidos.add(r);
        if (!ativo) {
            pedidos.clear(); // desligou entre o teste e o add
        }
    }

    /** Muda o modo (vale no proximo tick). */
    public void pedirModo(final boolean cortarAgora) {
        pedir(new Runnable() {
            @Override
            public void run() {
                cortar = cortarAgora;
            }
        });
    }

    /** Ferramenta de teste: a primeira tile da regiao gasta {@code ms} em cada rodada ({@code ms} 0 = para). id &lt; 0: todas. */
    public void pedirEspera(final long id, final double ms) {
        pedir(new Runnable() {
            @Override
            public void run() {
                for (Estado e : est) {
                    if (id < 0 || e.id == id) {
                        e.esperaMs = ms;
                    }
                }
            }
        });
    }

    /** Ferramenta de teste: grava o registro por tick por {@code segundos}. Devolve o arquivo (ou null se ja ha um). */
    public void pedirRegistro(final int segundos, final String nomeDoArquivo) {
        pedir(new Runnable() {
            @Override
            public void run() {
                try {
                    if (registro != null && registro.aberto()) {
                        jogo.log("orcamento: ja ha um registro em andamento");
                        return;
                    }
                    File dir = jogo.pastaDeSaida();
                    registro = new RegistroCsv(new File(dir, nomeDoArquivo), tempo.milis() + segundos * 1000L);
                    jogo.log("orcamento: registro por tick em " + registro.arquivo.getName() + " por " + segundos + " s");
                } catch (Throwable t) {
                    jogo.log("orcamento: nao consegui abrir o registro: " + t);
                }
            }
        });
    }

    // =====================================================================================================
    // Tick do servidor
    // =====================================================================================================

    /** Primeira coisa do tick do servidor. */
    public void inicioTick() {
        if (!ativo) {
            return;
        }
        try {
            tickIniNs = tempo.nano();
            tick++;
            Runnable r;
            while ((r = pedidos.poll()) != null) {
                r.run();
            }
            if (mapa == null || precisaRecalcular()) {
                recalcular();
            }
            boolean c = cortar;
            boolean algum = false;
            for (int i = 0; i < est.length; i++) {
                Estado e = est[i];
                boolean roda = true;
                boolean corte = c && e.n > 1 && e.protecao == null && !e.suspensa && !suspenderTodas;
                if (corte) {
                    roda = Cadencia.tica(e.acc, e.n);
                }
                emCorte[i] = corte;
                // o acumulador anda tambem no modo medir e fica parado com N = 1 (nao zera): regioes que entram juntas
                // nao ficam com a mesma fase (achado 2 da revisao); a subida de N = 1 poe a fase da regiao (avaliar)
                if (e.protecao == null && e.n > 1) {
                    e.acc = Cadencia.depois(e.acc, e.n);
                }
                decisao[i] = roda;
                algum |= corte; // inclusive na rodada dela: a puxada com reserva do teto (achado 3 da revisao)
                e.nsTick = 0;
                e.esperouNaRodada = false;
            }
            algumCortado = algum;
            cacheOk = false;
            atual = -1;
        } catch (Throwable t) {
            falha(t);
        }
    }

    /**
     * Alguma regiao esta em corte neste tick (sem rodar, ou na rodada dela)? Falso: os ganchos nao pulam nada e a puxada
     * dos ticks agendados usa o laco original do jogo. Na rodada de uma regiao cortada a puxada tambem passa pela selecao,
     * para o acumulo dela usar so a sobra do teto ({@link PuxadaAgendada#selecionar}).
     */
    public boolean cortaNesteTick() {
        return ativo && mapa != null && algumCortado;
    }

    /**
     * Objeto isento do corte (jogador e a cadeia de montaria dele): fecha o tempo da regiao da vez, para que o custo dele
     * nao entre na conta de nenhuma regiao (a regiao nao tem como reduzi-lo). Nunca lanca.
     */
    public void isento() {
        if (ativo && atual >= 0) {
            fechar(tempo.nano());
            atual = -1;
        }
    }

    /** Ultima coisa do tick do servidor. */
    public void fimTick() {
        if (!ativo || mapa == null) {
            return;
        }
        try {
            long agora = tempo.nano();
            fechar(agora);
            atual = -1;
            long agoraMs = tempo.milis();
            double ms = (agora - tickIniNs) / 1e6;
            if (primeiroTickMs < 0) {
                primeiroTickMs = agoraMs;
            }
            sMs[sPos] = ms;
            sCarimbo[sPos] = agoraMs;
            sPos = (sPos + 1) % TICKS_ANEL;
            if (sN < TICKS_ANEL) {
                sN++;
            }
            RegistroCsv rg = registro;
            if (rg != null && rg.aberto() && agoraMs >= rg.fimMs) {
                rg.fechar();
                jogo.log("orcamento: registro fechado (" + rg.arquivo.getName() + ")");
                rg = null;
            }
            if (rg != null && rg.aberto()) {
                rg.linha(tick, -1, true, ms);
            }
            for (int i = 0; i < est.length; i++) {
                Estado e = est[i];
                e.fimTick(decisao[i], agoraMs);
                if (rg != null && rg.aberto()) {
                    rg.linha(tick, e.id, decisao[i], e.nsTick / 1e6);
                }
            }
            if (tick % Parametros.JANELA_TICKS == 0) {
                avaliar(agoraMs);
            }
            try {
                jogo.aoFimDoTick(tick, retrato);
            } catch (Throwable t) {
                jogo.log("orcamento: erro na ferramenta de teste: " + t);
            }
        } catch (Throwable t) {
            falha(t);
        }
    }

    // =====================================================================================================
    // Ganchos dos lacos (tile, entidade, tick agendado, tick aleatorio)
    // =====================================================================================================

    /** Antes do update de uma tile entity: troca a regiao da vez. Devolve "a regiao roda neste tick?". */
    public boolean tile(int dim, int bx, int bz) {
        return trocar(dim, bx, bz, true);
    }

    /** Antes do tick de uma entidade (posicao em blocos). */
    public boolean entidade(int dim, int bx, int bz) {
        return trocar(dim, bx, bz, false);
    }

    /**
     * A decisao do laco de entidades (o que o gancho do World.updateEntities pergunta): entidade morta sempre "roda" (o laco
     * so a remove, como no original); entidade isenta (jogador e a cadeia de montaria dele) sempre roda e o custo dela nao
     * entra em regiao nenhuma; as outras seguem a regiao do ponto.
     */
    public boolean decidirEntidade(int dim, int bx, int bz, boolean morta, boolean isenta) {
        if (morta) {
            return true;
        }
        if (isenta) {
            isento();
            return true;
        }
        return trocar(dim, bx, bz, false);
    }

    /**
     * A selecao da puxada da fila de ticks agendados (o que o gancho do WorldServer.tickUpdates pede, so com forcar =
     * false). Sem corte neste tick devolve false sem ler nada (o jogo roda o laco original). Com corte, seleciona pela
     * {@link PuxadaAgendada#selecionar} (entrada de regiao que nao roda fica de fora; a fila nao muda) e devolve true; o
     * patch entao tira as selecionadas com {@link #proximaAgendada}, uma a uma, com as chamadas do original. A decisao de
     * cada entrada nunca lanca (erro = a regiao do ponto volta a rodar, {@link #falhaNoPonto}); qualquer outro erro desliga
     * o orcamento e devolve false (o laco original roda; nada foi tirado da fila).
     */
    public <T> boolean selecionarAgendados(final int dim, java.util.TreeSet fila, int teto, long agora,
            PuxadaAgendada.Acesso<T> acesso) {
        selecionadas.clear();
        posSelecionada = 0;
        try {
            if (!cortaNesteTick()) {
                return false;
            }
            // a classificacao "acumulo" de cada regiao vale para a selecao inteira (segunda revisao, achado 2: antes, erro
            // num gancho no meio da selecao tirava a regiao do corte e as entradas dela depois do erro passavam a contar como
            // normais, trocando a ordem dos agendados dela; e a entrada do erro era classificada pela regiao velha da vez)
            if (acumuloNaSelecao.length != emCorte.length) {
                acumuloNaSelecao = new boolean[emCorte.length];
            }
            for (int i = 0; i < emCorte.length; i++) {
                acumuloNaSelecao[i] = emCorte[i] && decisao[i];
            }
            PuxadaAgendada.selecionar(fila, teto, agora, acesso, new PuxadaAgendada.Decisor() {
                private boolean ultimaAcumulada;

                @Override
                public boolean roda(int x, int z) {
                    try {
                        boolean r = trocar(dim, x, z, false);
                        int a = atual; // logo depois do trocar: a regiao da vez e a do ponto
                        ultimaAcumulada = r && a >= 0 && a < acumuloNaSelecao.length && acumuloNaSelecao[a];
                        return r;
                    } catch (Throwable t) {
                        ultimaAcumulada = acumuladaNoPonto(dim, x, z);
                        falhaNoPonto(dim, x, z, t);
                        return true;
                    }
                }

                @Override
                public boolean acumulada(int x, int z) {
                    return ultimaAcumulada; // so e chamada logo depois de roda(x, z)
                }
            }, selecionadas);
            return true;
        } catch (Throwable t) {
            selecionadas.clear();
            falha(t);
            return false;
        }
    }

    /**
     * A proxima entrada selecionada por {@link #selecionarAgendados}, ou null no fim (e a lista e esvaziada: nenhuma
     * referencia a objeto do jogo fica guardada aqui). Nunca lanca.
     */
    public Object proximaAgendada() {
        if (posSelecionada < selecionadas.size()) {
            return selecionadas.get(posSelecionada++);
        }
        selecionadas.clear();
        posSelecionada = 0;
        return null;
    }

    /** A classificacao "acumulo" da regiao do ponto na selecao atual, sem o trocar (depois de um erro nele). Nunca lanca. */
    private boolean acumuladaNoPonto(int dim, int x, int z) {
        try {
            Mapa m = mapa;
            int r = m == null ? -1 : m.regiaoNoBloco(dim, x, z);
            return r >= 0 && r < acumuloNaSelecao.length && acumuloNaSelecao[r];
        } catch (Throwable t) {
            return false;
        }
    }

    /** Quantas entradas ainda estao na selecao (para os testes: 0 depois do fim da puxada). */
    int selecaoPendente() {
        return selecionadas.size() - posSelecionada;
    }

    /** Antes de uma entrada de tick agendado (posicao em blocos). */
    public boolean agendado(int dim, int bx, int bz) {
        return trocar(dim, bx, bz, false);
    }

    /** Antes do tick aleatorio de um chunk. */
    public boolean chunkAleatorio(int dim, int cx, int cz) {
        return trocar(dim, cx << 4, cz << 4, false);
    }

    /** Fim de um laco (tiles, entidades, ticks agendados, ticks aleatorios): o tempo que sobra e da regiao da vez. */
    public void fimLaco() {
        if (atual >= 0) {
            fechar(tempo.nano());
            atual = -1;
        }
    }

    /**
     * A decisao pura da regiao do ponto: roda neste tick? Sem trocar a regiao da vez e sem ler o relogio. Ponto fora de
     * qualquer regiao: sempre roda.
     */
    public boolean rodaEm(int dim, int bx, int bz) {
        Mapa m = mapa;
        if (m == null || !ativo) {
            return true;
        }
        int r = m.regiaoNoBloco(dim, bx, bz);
        return r < 0 || decisao[r];
    }

    /** A decisao da regiao de indice {@code idx} do mapa atual neste tick. */
    public boolean rodaNesteTick(int idx) {
        return idx < 0 || idx >= decisao.length || decisao[idx];
    }

    private boolean trocar(int dim, int bx, int bz, boolean ehTile) {
        Mapa m = mapa;
        if (m == null || !ativo) {
            return true;
        }
        int idx;
        int di = m.indiceDaDim(dim);
        if (di < 0) {
            idx = -1;
            if (!pedidoRecalculo) {
                pedidoRecalculo = true; // dimensao nova: nao ha regiao para juntar, so falta o mapa
            }
        } else {
            long ch = MapaSecoes.chave(di, bx >> 8, bz >> 8);
            if (cacheOk && ch == chaveCache) {
                idx = idxCache;
            } else {
                idx = m.regiaoDaChave(ch);
                chaveCache = ch;
                idxCache = idx;
                cacheOk = true;
                if (idx < 0) {
                    desconhecida(m, di, ch, bx >> 8, bz >> 8);
                }
            }
        }
        if (idx != atual) {
            long agora = tempo.nano();
            fechar(agora);
            atual = idx;
            iniAtualNs = agora;
        }
        if (idx < 0) {
            return true;
        }
        Estado e = est[idx];
        boolean roda = decisao[idx];
        if (ehTile && ferramentasTeste && roda && e.esperaMs > 0 && !e.esperouNaRodada) {
            e.esperouNaRodada = true;
            gastar(e.esperaMs);
        }
        return roda;
    }

    private void fechar(long agora) {
        if (atual >= 0) {
            est[atual].nsTick += agora - iniAtualNs;
            iniAtualNs = agora;
        }
    }

    private void gastar(double ms) {
        long fim = tempo.nano() + (long) (ms * 1e6);
        while (tempo.nano() < fim) {
            // espera ocupada (conta como custo da regiao)
        }
    }

    /** Secao ocupada que nao esta no mapa: pede recalculo e suspende as regioes que ela pode juntar. */
    private void desconhecida(Mapa m, int di, long ch, int sx, int sz) {
        if (desconhecidas.contem(ch)) {
            return;
        }
        pedidoRecalculo = true;
        desconhecidasVistas++;
        if (!desconhecidas.adicionar(ch)) {
            suspenderTodas = true; // tabela cheia: nao da para lembrar de todas; seguranca primeiro
            return;
        }
        int[] rs = m.regioesPerto(di, sx, sz, Regionizador.RAIO_JUNCAO);
        for (int r : rs) {
            est[r].suspensa = true;
        }
    }

    // =====================================================================================================
    // Recalculo do mapa
    // =====================================================================================================

    private boolean precisaRecalcular() {
        long desde = tick - ultimoRecalcTick;
        if (desde >= p.recalculoTicks) {
            return true;
        }
        return pedidoRecalculo && desde >= Parametros.RECALCULO_MINIMO_TICKS;
    }

    private void recalcular() {
        long t0 = tempo.nano();
        long agoraMs = tempo.milis();
        FotoMundo[] f = jogo.fotografar();
        Mapa novo = regionizador.calcular(f, p.sempreJuntoDimensoes);
        // classes de tile por regiao
        List<Map<String, int[]>> hist = new ArrayList<Map<String, int[]>>();
        for (int i = 0; i < novo.nRegioes; i++) {
            hist.add(null);
        }
        for (FotoMundo w : f) {
            if (w.tilesClasse == null) {
                continue;
            }
            for (int i = 0; i < w.nTiles && i < w.tilesClasse.length; i++) {
                String c = w.tilesClasse[i];
                if (c == null) {
                    continue;
                }
                int r = novo.regiaoNoBloco(w.dim, w.tilesX[i], w.tilesZ[i]);
                if (r < 0) {
                    continue;
                }
                Map<String, int[]> h = hist.get(r);
                if (h == null) {
                    h = new HashMap<String, int[]>();
                    hist.set(r, h);
                }
                int[] n = h.get(c);
                if (n == null) {
                    h.put(c, new int[] {1});
                } else {
                    n[0]++;
                }
            }
        }
        Heranca h = new Heranca(proximoId);
        Estado[] ne = h.aplicar(mapa, est, novo, agoraMs);
        proximoId = h.proximoId;
        for (Estado e : ne) {
            e.suspensa = false;
            e.protecao = null;
        }
        proteger(novo, ne, f, hist);
        String[][] top = new String[novo.nRegioes][];
        for (int i = 0; i < novo.nRegioes; i++) {
            top[i] = topClasses(hist.get(i));
        }
        mapa = novo;
        est = ne;
        decisao = new boolean[ne.length];
        Arrays.fill(decisao, true);
        emCorte = new boolean[ne.length];
        classesTop = top;
        desconhecidas.limpar();
        pedidoRecalculo = false;
        suspenderTodas = false;
        ultimoRecalcTick = tick;
        recalculos++;
        cacheOk = false;
        ultimoRecalcNs = tempo.nano() - t0;
        if (h.juncoes > 0 || h.divisoes > 0) {
            jogo.log("orcamento: mapa recalculado: " + novo.nRegioes + " regiao(oes); juntou " + h.juncoes + ", dividiu "
                    + h.divisoes);
        }
        publicar(agoraMs); // sempre: o retrato tem de mostrar protecao e regioes do mapa novo
    }

    private static String[] topClasses(Map<String, int[]> h) {
        if (h == null) {
            return new String[0];
        }
        List<Map.Entry<String, int[]>> l = new ArrayList<Map.Entry<String, int[]>>(h.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, int[]>>() {
            @Override
            public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                int c = Integer.compare(b.getValue()[0], a.getValue()[0]);
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            }
        });
        String[] r = new String[Math.min(TOPO_CLASSES, l.size())];
        for (int i = 0; i < r.length; i++) {
            r[i] = l.get(i).getKey() + "=" + l.get(i).getValue()[0];
        }
        return r;
    }

    /** Marca as regioes que nunca entram no corte (e tira o corte que elas tinham). */
    private void proteger(Mapa m, Estado[] e, FotoMundo[] f, List<Map<String, int[]>> hist) {
        for (int r = 0; r < m.nRegioes; r++) {
            for (int d : p.nuncaDimensoes) {
                if (m.dim[r] == d) {
                    e[r].protecao = "dimensao protegida";
                }
            }
        }
        if (p.protegerSpawn) {
            for (int[] s : jogo.spawns()) {
                if (s[0] == 0) {
                    int r = m.regiaoNoBloco(0, s[1], s[2]);
                    if (r >= 0 && e[r].protecao == null) {
                        e[r].protecao = "spawn";
                    }
                }
            }
        }
        if (!p.nuncaAreas.isEmpty()) {
            MapaSecoes sc = m.secoes;
            for (int w = 0; w < sc.tamanho(); w++) {
                long c = sc.chaveDaVaga(w);
                int r = m.regiaoDaVaga[w];
                if (e[r].protecao != null) {
                    continue;
                }
                int dim = m.dim[r];
                int x0 = MapaSecoes.xDe(c) << 8, z0 = MapaSecoes.zDe(c) << 8;
                int x1 = x0 + 255, z1 = z0 + 255;
                for (Parametros.Area a : p.nuncaAreas) {
                    if (a.dim == dim && a.x1 <= x1 && a.x2 >= x0 && a.z1 <= z1 && a.z2 >= z0) {
                        e[r].protecao = "area protegida";
                        break;
                    }
                }
            }
        }
        if (!p.cortarComJogador) {
            for (int[] j : jogo.jogadores()) {
                int r = m.regiaoNoBloco(j[0], j[1], j[2]);
                if (r >= 0 && e[r].protecao == null) {
                    e[r].protecao = "jogador";
                }
            }
        }
        if (comErro.tamanho() > 0) {
            for (Estado s : e) {
                if (s.protecao == null && comErro.contem(s.id)) {
                    s.protecao = "erro interno";
                }
            }
        }
        if (p.nuncaMods.length > 0) {
            for (int r = 0; r < m.nRegioes; r++) {
                Map<String, int[]> h = hist.get(r);
                if (h == null || e[r].protecao != null) {
                    continue;
                }
                for (String classe : h.keySet()) {
                    String mod = modDa(classe);
                    if (mod == null) {
                        continue;
                    }
                    for (String n : p.nuncaMods) {
                        if (mod.contains(n)) {
                            e[r].protecao = "mod " + n;
                            break;
                        }
                    }
                    if (e[r].protecao != null) {
                        break;
                    }
                }
            }
        }
        for (Estado s : e) {
            if (s.protecao != null) {
                s.n = 1;
                s.acc = 0;
                s.ticksMenor = 0;
            }
        }
    }

    private String modDa(String classe) {
        String m = modDaClasse.get(classe);
        if (m == null) {
            String r = jogo.modDaClasse(classe);
            m = r == null ? "" : r.toLowerCase(Locale.ROOT);
            modDaClasse.put(classe, m);
        }
        return m.isEmpty() ? null : m;
    }

    // =====================================================================================================
    // Avaliacao (a cada JANELA_TICKS): estourou, voltou, histerese, alertas
    // =====================================================================================================

    private void avaliar(long agoraMs) {
        double media = mediaUltimos(Parametros.JANELA_TICKS);
        boolean estourou = media > p.alvoTickMs;
        boolean folgado = media < p.alvoTickMs * Parametros.FOLGA_FRACAO;
        folgaTicks = folgado ? folgaTicks + Parametros.JANELA_TICKS : 0;
        boolean descer = folgaTicks >= Parametros.FOLGA_TICKS;
        // previsao do servidor (ms por tick) com as descidas ja decididas nesta avaliacao
        double previsao = media;
        boolean cortarAgora = cortar;
        for (int i = 0; i < est.length; i++) {
            Estado e = est[i];
            e.fecharPeriodo();
            int antes = e.n;
            if (e.protecao == null && e.temMedida) {
                int desejado = Cadencia.nParaCusto(e.custoRodadaMs, p.regiaoMs, p.picoMs, p.picoFracaoPct, p.nMaximo);
                boolean pico = e.custoRodadaMs > p.picoMs;
                boolean subiu = false;
                if (estourou && desejado > e.n) {
                    int teto = pico ? desejado : Math.min(desejado, Math.max(2, 2 * e.n));
                    if (teto > e.n) {
                        e.n = teto;
                        subiu = true;
                    }
                    e.ticksMenor = 0;
                } else if (desejado < e.n) {
                    e.ticksMenor += Parametros.JANELA_TICKS;
                    if (e.ticksMenor >= Parametros.FOLGA_TICKS) {
                        e.n = metade(e.n, desejado);
                        e.ticksMenor = 0;
                    }
                } else {
                    e.ticksMenor = 0;
                }
                // servidor folgado por 200 ticks: N cai a metade (plano 2: "os cortados descem, ate 1") se a previsao do
                // servidor com o N novo continua abaixo de 80% do alvo (senao o corte oscilaria: a regiao voltaria a rodar
                // todo tick, o servidor estouraria e ela seria cortada de novo); nunca abaixo do N que a regra do pico
                // obriga. Sem caber, desce so ate o N que o custo da regiao pede.
                // So muda a previsao a regiao que esta de fato cortada (modo cortar, sem suspensao): no modo medir, ou
                // suspensa, ela ja roda todo tick e a media ja inclui o custo dela (segunda revisao, achado 1: antes, no modo
                // medir, a previsao somava o custo de novo e a regiao ficava presa em "estouraria" para sempre).
                if (descer && !subiu && e.n > 1 && e.n == antes) {
                    boolean cortadaDeFato = cortarAgora && !e.suspensa && !suspenderTodas;
                    int piso = pico ? p.nDoPico() : 1;
                    int novo = Math.max(piso, metade(e.n, 1));
                    double delta = cortadaDeFato ? e.custoRodadaMs * (1.0 / novo - 1.0 / e.n) : 0;
                    if (novo < e.n && previsao + delta < p.alvoTickMs * Parametros.FOLGA_FRACAO) {
                        e.n = novo;
                        previsao += delta;
                    } else if (desejado < e.n) {
                        int m = metade(e.n, desejado);
                        previsao += cortadaDeFato ? e.custoRodadaMs * (1.0 / m - 1.0 / e.n) : 0;
                        e.n = m;
                    }
                }
            }
            // alerta de peso (custo por rodada: o que a regiao custaria por tick sem corte)
            // o estado (pesada) muda com a histerese; o aviso (avisadaPesada) so muda quando o alerta sai: alerta segurado
            // pelo limitador e tentado de novo na proxima avaliacao (achado 4 da revisao) e o comando mostra PESADA mesmo
            // com o alerta segurado (achado 3 da segunda revisao: antes 'pesada' so virava true quando o alerta saia)
            if (e.temMedida) {
                if (e.custoRodadaMs > p.alertaMs) {
                    e.periodosLeves = 0;
                    e.pesada = true;
                } else if (e.pesada && e.custoRodadaMs < p.alertaMs * Parametros.FOLGA_FRACAO) {
                    if (++e.periodosLeves >= 2) {
                        e.pesada = false;
                        e.periodosLeves = 0;
                    }
                } else {
                    e.periodosLeves = 0;
                }
                if (e.pesada != e.avisadaPesada && alertar(e.pesada ? ALERTA_PESADA : ALERTA_NORMAL, i, media)) {
                    e.avisadaPesada = e.pesada;
                }
            }
            if (antes == 1 && e.n > 1) {
                e.acc = Cadencia.faseInicial(e.id); // espalha as regioes que sobem juntas (nao rodam todas no mesmo tick)
            }
            boolean emCorteAgora = e.n > 1;
            if (emCorteAgora != e.avisadoCorte
                    && alertar(emCorteAgora ? ALERTA_CORTE : ALERTA_FIM_CORTE, i, media)) {
                e.avisadoCorte = emCorteAgora;
            }
        }
        if (descer) {
            folgaTicks = 0;
        }
        publicar(agoraMs);
    }

    /** A metade de n (passo a passo), nunca abaixo de {@code piso}; menos que 2 vira 1 (sem corte). */
    private static int metade(int n, int piso) {
        int m = Math.max(piso, n / 2);
        return m < 2 ? 1 : m;
    }

    /** Manda o alerta se o limitador deixa. Devolve se saiu. */
    private boolean alertar(int tipo, int idx, double servidorMs) {
        Estado e = est[idx];
        long agoraMs = tempo.milis();
        if (!limite.permitir(e.id, tipo, agoraMs)) {
            return false;
        }
        String msg = texto(tipo, idx, servidorMs);
        try {
            jogo.log(msg);
            if (p.alertaOps) {
                jogo.avisarOperadores(msg);
            }
        } catch (Throwable t) {
            // o alerta nunca derruba o tick
        }
        return true;
    }

    private String texto(int tipo, int idx, double servidorMs) {
        Estado e = est[idx];
        int[] c = mapa.caixaEmBlocos(idx);
        String onde = "regiao " + e.id + " (dim " + mapa.dim[idx] + ", x " + c[0] + " a " + c[2] + ", z " + c[1] + " a "
                + c[3] + ", centro " + (c[0] + c[2]) / 2 + "," + (c[1] + c[3]) / 2 + "; " + mapa.chunks[idx] + " chunks, "
                + mapa.tiles[idx] + " tiles, " + mapa.ents[idx] + " entidades)";
        String custo = String.format(Locale.ROOT, "%.1f ms por rodada; servidor %.1f ms por tick", e.custoRodadaMs,
                servidorMs);
        boolean c1 = cortar;
        String acao;
        if (e.protecao != null) {
            acao = "protegida (" + e.protecao + "), nunca e cortada";
        } else if (e.n > 1) {
            acao = c1 ? "esta ticando 1 a cada " + e.n : "so medindo: ticaria 1 a cada " + e.n;
        } else {
            acao = c1 ? "sem corte agora (servidor dentro do alvo)" : "so medindo (modo medir)";
        }
        switch (tipo) {
            case ALERTA_PESADA:
                return "[orcamento] " + onde + " comecou a pesar: " + custo + "; " + acao;
            case ALERTA_NORMAL:
                return "[orcamento] " + onde + " voltou ao normal: " + custo + "; " + acao;
            case ALERTA_CORTE:
                return "[orcamento] " + onde + (c1 ? " passou a ticar 1 a cada " : " passaria a ticar 1 a cada ") + e.n
                        + ": " + custo;
            default:
                return "[orcamento] " + onde + " voltou a ticar todo tick: " + custo;
        }
    }

    // =====================================================================================================
    // Estatistica do servidor e foto
    // =====================================================================================================

    private double mediaUltimos(int k) {
        int n = Math.min(k, sN);
        if (n == 0) {
            return 0;
        }
        double s = 0;
        for (int i = 0; i < n; i++) {
            s += sMs[(sPos - 1 - i + TICKS_ANEL * 2) % TICKS_ANEL];
        }
        return s / n;
    }

    private static double percentil(double[] ordenado, int n, double q) {
        if (n == 0) {
            return 0;
        }
        int i = (int) Math.ceil(q * n) - 1;
        return ordenado[Math.max(0, Math.min(i, n - 1))];
    }

    private void publicar(long agoraMs) {
        double[] ms = Arrays.copyOf(sMs, sN);
        Arrays.sort(ms);
        int dentro = 0;
        for (int i = 0; i < sN; i++) {
            if (agoraMs - sCarimbo[i] <= JANELA_TPS_MS) {
                dentro++;
            }
        }
        double janela = primeiroTickMs < 0 ? 1.0
                : Math.min(JANELA_TPS_MS, Math.max(1000L, agoraMs - primeiroTickMs)) / 1000.0;
        double tps = dentro / janela;
        Retrato.Regiao[] rs = new Retrato.Regiao[est.length];
        boolean c = cortar;
        for (int i = 0; i < est.length; i++) {
            Estado e = est[i];
            String estado;
            if (e.protecao != null) {
                estado = "protegida";
            } else if (e.suspensa || suspenderTodas) {
                estado = "suspensa";
            } else if (e.n > 1) {
                estado = c ? "cortada" : "estouraria";
            } else {
                estado = "normal";
            }
            rs[i] = new Retrato.Regiao(e.id, mapa.dim[i], mapa.caixaEmBlocos(i), mapa.nSecoes[i], mapa.chunks[i],
                    mapa.tiles[i], mapa.ents[i], e.n, estado, e.protecao, e.tps(agoraMs), e.custoRodadaMs,
                    e.custoRealMs, e.p95RodadaMs(), e.maxRodadaMs, e.pesada, classesTop[i], e.esperaMs);
        }
        retrato = new Retrato(true, c, tick, tps, mediaUltimos(Parametros.JANELA_TICKS), percentil(ms, sN, 0.95),
                percentil(ms, sN, 0.99), sN == 0 ? 0 : ms[sN - 1], sN, recalculos, mapa, rs);
    }

    /** Uma linha para o "hoshie status". */
    public String linhaDeEstado() {
        Retrato r = retrato;
        if (!ativo) {
            return "DESLIGADO por erro interno";
        }
        if (r == null) {
            return "ligado, modo " + (cortar ? "cortar" : "medir") + "; ainda sem tick";
        }
        return String.format(Locale.ROOT, "modo %s; %d regiao(oes), %d com corte; servidor %.1f ms (media 100 ticks); mapa recalculado %d vez(es), ultimo %.1f ms",
                cortar ? "cortar" : "medir", r.regioes.length, r.contar("cortada") + r.contar("estouraria"),
                r.servidorMediaMs, recalculos, ultimoRecalcNs / 1e6);
    }
}
