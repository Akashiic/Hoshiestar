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

import hoshiestar.regioes.FotoMundo;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * O nucleo do observador (Etapa 2): so LE e ANOTA. Recebe "o contexto de quem executa" e "o acesso a uma posicao" e
 * agrega tudo em tabelas de contagem com teto de tamanho. Nao conhece o Minecraft (os ganchos passam numeros e
 * objetos opacos), por isso os testes isolados exercitam este mesmo codigo com um mundo falso.
 *
 * <p><b>Threads:</b> tudo aqui e da thread do servidor, menos {@link #outraThread(int)} (tabela T4), que usa so contadores
 * atomicos e o registro de mods, seguros entre threads. Depois que o observador para, a thread de gravacao le as
 * tabelas (a ordem vem do Thread.start).
 *
 * <p><b>Tabelas</b> (cada uma com teto; o que passa do teto vai para o balde "outros" e o relatorio avisa):
 * T1 acessos por (mod do contexto, mod da pilha, tipo de contexto, tipo de acesso, faixa de distancia, mesma secao,
 * relacao de regiao, cruzou dimensao); T2 pares (dimensao, secao de origem, secao de destino) que cruzam regiao ou
 * dimensao; T3 cargas de chunk por (contexto, mod, onde caiu); T4 acessos de outra thread; T5 as fotos de regioes
 * ({@link Foto.Resumo}).
 */
public final class Nucleo {

    /** Relogio injetavel (os testes usam um falso). */
    public interface Relogio {
        long nano();
    }

    static final Relogio SISTEMA = new Relogio() {
        @Override
        public long nano() {
            return System.nanoTime();
        }
    };

    // ---- tipos de contexto (4 bits na chave) ----
    public static final int C_OUTRO = 0;
    public static final int C_TILE = 1;
    public static final int C_ENTIDADE = 2;
    public static final int C_JOGADOR_REDE = 3;
    public static final int C_TICK_AGENDADO = 4;
    public static final int C_TICK_ALEATORIO = 5;
    public static final int C_EVENTO_BLOCO = 6;
    public static final int C_GLOBAL_PRE = 7;
    public static final int C_GLOBAL_POS = 8;
    public static final int C_MUNDO_PRE = 9;
    public static final int C_MUNDO_POS = 10;
    public static final int C_REDE = 11;
    public static final int C_AGENDADOR = 12;
    static final String[] NOME_CTX = {"outro", "tile", "entidade", "jogador_rede", "tick_agendado", "tick_aleatorio",
        "evento_de_bloco", "global_antes_do_tick", "global_depois_do_tick", "mundo_antes_do_tick",
        "mundo_depois_do_tick", "rede", "agendador_de_plugins", "?", "?", "?"};

    // ---- tipos de acesso (4 bits na chave) ----
    public static final int A_LER_BLOCO = 1;
    public static final int A_ESCREVER_BLOCO = 2;
    public static final int A_ESCREVER_META = 3;
    public static final int A_LER_TILE = 4;
    public static final int A_LER_TILE_INVENTARIO = 5;
    public static final int A_POR_TILE = 6;
    public static final int A_REMOVER_TILE = 7;
    public static final int A_ADICIONAR_TILE = 8;
    public static final int A_ADICIONAR_ENTIDADE = 9;
    public static final int A_REMOVER_ENTIDADE = 10;
    public static final int A_AVISAR_VIZINHO = 11;
    public static final int A_AGENDAR_TICK = 12;
    public static final int A_EVENTO_BLOCO = 13;
    public static final int A_ESCRITA_CHUNK = 14;
    public static final int A_INVENTARIO_FUNIL = 15;
    static final String[] NOME_ACESSO = {"?", "ler_bloco", "escrever_bloco", "escrever_metadado", "ler_tile",
        "ler_tile_provavel_inventario", "por_tile", "remover_tile", "adicionar_tile", "adicionar_entidade",
        "remover_entidade", "avisar_vizinho", "agendar_tick", "evento_de_bloco", "escrita_direta_no_chunk",
        "inventario_pelo_funil"};

    /** Sem chunk de origem (contexto que nao diz onde esta). */
    public static final int SEM_ORIGEM = Integer.MIN_VALUE;

    // ---- faixas de distancia em chunks (3 bits) ----
    static final String[] NOME_FAIXA = {"0", "1-2", "3-8", "9-16", "17-48", "49-63", "64+", "n/a"};

    // ---- relacao de regiao (2 bits) ----
    static final String[] NOME_REGIAO = {"n/a (sem origem)", "mesma regiao", "outra regiao", "fora da foto"};

    // ---- onde caiu a carga de chunk (T3) ----
    static final String[] NOME_ONDE = {"mesma secao da origem", "outra secao da mesma regiao", "secao vazia na foto",
        "secao de outra regiao", "contexto sem origem", "outra dimensao", "sem foto da origem"};

    // ---- limites ----
    static final int TETO_T1 = 20000;
    static final int TETO_T2 = 5000;
    static final int TETO_T3 = 5000;
    static final int MAX_AMOSTRAS_TICK = 20;
    /** Teto de amostras de pilha tiradas por OUTRAS threads (T4 e lista, somadas) entre dois inicios de tick. */
    static final int MAX_AMOSTRAS_OUTRA_TICK = 20;
    /** Custo estimado (ns) de uma chamada contada em outra thread (tres contadores atomicos), para a valvula. */
    static final long CUSTO_OUTRA_NS = 25;
    static final int MAX_FOTOS = 40;
    static final int PROF_MAX = 8;
    static final long INTERVALO_FOTO_NS = 30_000_000_000L;
    /** Custo estimado (ns) de uma leitura rapida, de uma troca de contexto e de um acesso completo ate a 1a amostra. */
    static final long CUSTO_RAPIDA_NS = 8;
    static final long CUSTO_ENTRADA_NS = 12;
    static final long CUSTO_COMPLETA_INICIAL_NS = 200;
    /** A valvula olha janelas deste numero de ticks. */
    static final int JANELA_TICKS = 100;
    /** Abaixo disto (ns por tick, na janela) a valvula nunca para: nao vale parar por uma folga minuscula. */
    static final long CUSTO_MINIMO_NS = 250_000L;

    private final Pilha.Donos donos;
    private final Relogio rel;
    final int amostraPilha;
    final double limiteCustoPct;
    final Mods mods = new Mods();

    // ---- contexto (so a thread do servidor) ----
    private int prof;
    private final int[] ctxTipo = new int[PROF_MAX];
    private final Object[] ctxMundo = new Object[PROF_MAX];
    private final int[] ctxDim = new int[PROF_MAX];
    private final int[] ctxCx = new int[PROF_MAX];
    private final int[] ctxCz = new int[PROF_MAX];
    private final Class<?>[] ctxClasse = new Class<?>[PROF_MAX];
    private final int[] ctxMod = new int[PROF_MAX];
    private final IdentityHashMap<Class<?>, Integer> modDaClasse = new IdentityHashMap<Class<?>, Integer>();

    // ---- tabelas ----
    final TabelaContagem t1 = new TabelaContagem(1024, TETO_T1);
    final TabelaContagem t2 = new TabelaContagem(256, TETO_T2);
    final TabelaContagem t3 = new TabelaContagem(256, TETO_T3);
    /** T4: [tipo de acesso * Mods.PASSO + espaco]; espaco 0 = sem amostra de pilha, 1 + id do mod = amostrado. */
    final AtomicLongArray t4 = new AtomicLongArray(16 * Mods.PASSO);
    final AtomicLong t4Total = new AtomicLong();
    private final AtomicLong t4Sorteio = new AtomicLong();
    // Hoshiestar: orcamento das amostras de pilha de outras threads (revisao de concorrencia, I1). O inicio do tick zera;
    // se o tick parar (por exemplo esperando uma trava que a outra thread segura), o orcamento nao volta e a outra
    // thread deixa de pagar pilha. O tempo gasto e as chamadas entram na conta da valvula (fimTick).
    private final AtomicInteger outraAmostrasTick = new AtomicInteger();
    final AtomicLong outraAmostras = new AtomicLong();
    final AtomicLong outraPilhaNs = new AtomicLong();
    private long outraChamadasContadas;
    private long outraPilhaNsContado;
    final List<Foto.Resumo> fotos = new ArrayList<Foto.Resumo>();
    /** Prototipo da lista de tiles (observador.lista): quem usa loadedTileEntityList de fora do World. */
    final ContagemLista lista = new ContagemLista();
    long fotosTiradas;
    long t2ForaDaFaixa;

    // ---- contagens ----
    long rapidas;
    final long[] semOrigemLeituras = new long[16];
    long completas;
    final long[] porAcesso = new long[16];
    long cargas;
    long entradasTotal;
    long transbordosPilha;
    long elegiveis;
    long amostras;
    long pilhaNsTotal;

    // ---- custo e valvula ----
    private long rapidasTick;
    private long entradasTick;
    private long completasTick;
    private long pilhaNsTick;
    private long fotoNsTick;
    private int amostrasTick;
    private long tickIni;
    private long amostradas;
    private long somaAmostradaNs;
    private long janTicks;
    private long janCusto;
    private long janDur;
    long ticks;
    long custoTotalNs;
    long tickTotalNs;
    long fotoNsTotal;
    double pctMaxJanela;
    /** Custo estimado da primeira janela (aquecimento: foto fria e primeiras amostras de pilha). Nao conta para a valvula. */
    double pctAquecimento;
    private boolean aquecendo = true;
    String motivoValvula;

    // ---- fotos e dimensoes ----
    private Foto foto;
    private long ultimaFotoNs;
    private final long inicioNs;
    private final int[] dims = new int[256];
    private int nDims;

    // ---- escrita via World e escrita direta no chunk ----
    private boolean ultEscritaValida;
    private int ultEscritaCx;
    private int ultEscritaCz;

    public Nucleo(Pilha.Donos donos, int amostraPilha, double limiteCustoPct, Relogio rel) {
        this.donos = donos;
        this.rel = rel != null ? rel : SISTEMA;
        this.amostraPilha = Math.max(1, amostraPilha);
        this.limiteCustoPct = limiteCustoPct;
        this.inicioNs = this.rel.nano();
        this.ultimaFotoNs = Long.MIN_VALUE;
    }

    // =====================================================================================================
    // Contexto
    // =====================================================================================================

    /**
     * Entra num contexto. Se o topo ja e do mesmo tipo (sobra de um tick que lancou excecao antes do {@link #sair}),
     * ele e substituido, nao empilhado. {@code cx/cz} = chunk de origem ou {@link #SEM_ORIGEM}.
     */
    public void entrar(int tipo, Object mundo, int dim, int cx, int cz, Class<?> classe) {
        if (prof > 0 && ctxTipo[prof - 1] == tipo) {
            prof--;
        }
        if (prof >= PROF_MAX) {
            transbordosPilha++;
            prof = PROF_MAX - 1;
        }
        int i = prof++;
        ctxTipo[i] = tipo;
        ctxMundo[i] = cx == SEM_ORIGEM ? null : mundo;
        ctxDim[i] = dim;
        ctxCx[i] = cx;
        ctxCz[i] = cz;
        ctxClasse[i] = classe;
        ctxMod[i] = -1;
        entradasTick++;
    }

    /** Sai do contexto {@code tipo} (e de qualquer um que sobrou em cima dele). Se o tipo nao esta na pilha, nao faz nada. */
    public void sair(int tipo) {
        int j = prof - 1;
        while (j >= 0 && ctxTipo[j] != tipo) {
            j--;
        }
        if (j >= 0) {
            for (int k = j; k < prof; k++) {
                ctxMundo[k] = null; // Hoshiestar: nao prender o mundo (WorldServer) depois do contexto
                ctxClasse[k] = null;
            }
            prof = j;
        }
    }

    /** Solta as referencias a mundos e classes dos contextos (fim da observacao ou inicio de tick). */
    public void liberarContextos() {
        for (int k = 0; k < PROF_MAX; k++) {
            ctxMundo[k] = null;
            ctxClasse[k] = null;
        }
        prof = 0;
    }

    /** Tipo do contexto de cima, ou {@link #C_OUTRO} (para os testes e o status). */
    public int contextoAtual() {
        return prof > 0 ? ctxTipo[prof - 1] : C_OUTRO;
    }

    public int profundidade() {
        return prof;
    }

    /** Mod do contexto de cima (0 = sem contexto ou sem mod). Thread do servidor. */
    int modDoContextoAtual() {
        return prof > 0 ? modDoContexto(prof - 1) : 0;
    }

    /** O mod do primeiro quadro da pilha que nao e do servidor (0 = nenhum). Seguro entre threads. */
    int modDaPilha(StackTraceElement[] p) {
        return mods.id(Pilha.achar(p, donos));
    }

    private int modDoContexto(int i) {
        int m = ctxMod[i];
        if (m >= 0) {
            return m;
        }
        Class<?> c = ctxClasse[i];
        if (c == null) {
            m = 0;
        } else {
            Integer r = modDaClasse.get(c);
            if (r == null) {
                m = mods.id(donos.dono(c.getName()));
                if (modDaClasse.size() < 20000) {
                    modDaClasse.put(c, m);
                }
            } else {
                m = r;
            }
        }
        ctxMod[i] = m;
        return m;
    }

    // =====================================================================================================
    // Acessos
    // =====================================================================================================

    /**
     * Caminho mais quente: leitura de bloco. Se o contexto tem origem e o acesso cai na mesma secao do mesmo mundo,
     * so conta; sem origem so conta por tipo de contexto. Devolve true se tratou (o gancho nao precisa chamar mais nada).
     */
    public boolean leituraRapida(Object mundo, int cx, int cz) {
        int top = prof - 1;
        if (top < 0) {
            semOrigemLeituras[C_OUTRO]++;
            return true;
        }
        if (ctxCx[top] == SEM_ORIGEM) {
            semOrigemLeituras[ctxTipo[top]]++;
            return true;
        }
        if (mundo == ctxMundo[top] && (cx >> 4) == (ctxCx[top] >> 4) && (cz >> 4) == (ctxCz[top] >> 4)) {
            rapidas++;
            return true;
        }
        return false;
    }

    /** Escrita de bloco vinda do World: lembra o chunk para a escrita direta no chunk nao contar em dobro. */
    public void escritaViaMundo(int cx, int cz) {
        ultEscritaValida = true;
        ultEscritaCx = cx;
        ultEscritaCz = cz;
    }

    /** Escrita direta no chunk (Chunk.func_150807_a): nao conta se acabou de vir de uma escrita do World no mesmo chunk. */
    public boolean escritaDiretaNova(int cx, int cz) {
        if (ultEscritaValida && ultEscritaCx == cx && ultEscritaCz == cz) {
            ultEscritaValida = false;
            return false;
        }
        return true;
    }

    /**
     * Registra um acesso a um chunk (cx, cz) de um mundo. {@code mundo} so e comparado por identidade; {@code dim} e o
     * numero da dimensao dele.
     */
    public void acesso(int tipo, Object mundo, int dim, int cx, int cz) {
        final int top = prof - 1;
        final int ct = top >= 0 ? ctxTipo[top] : C_OUTRO;
        final boolean origem = top >= 0 && ctxCx[top] != SEM_ORIGEM;
        if (tipo == A_LER_BLOCO && leituraRapida(mundo, cx, cz)) {
            return;
        }
        long t0 = (completas & 63) == 0 ? rel.nano() : 0L;
        completas++;
        completasTick++;
        porAcesso[tipo & 15]++;
        boolean cruzouDim = false;
        boolean mesmaSec = false;
        int dist = 0;
        int faixa = 7;
        int reg = 0;
        int dimO = 0;
        int osx = 0, osz = 0, dsx = cx >> 4, dsz = cz >> 4;
        if (origem) {
            int od = ctxDim[top];
            dimO = dimIdx(od);
            osx = ctxCx[top] >> 4;
            osz = ctxCz[top] >> 4;
            if (od != dim) {
                cruzouDim = true;
                reg = 2;
            } else {
                dist = Math.max(Math.abs(cx - ctxCx[top]), Math.abs(cz - ctxCz[top]));
                faixa = faixa(dist);
                mesmaSec = osx == dsx && osz == dsz;
                reg = mesmaSec ? 1 : relacaoRegiao(dimO, osx, osz, dsx, dsz);
            }
        }
        int mc = top >= 0 ? modDoContexto(top) : 0;
        int mp = 0;
        boolean cruzaSecao = origem && !mesmaSec;
        if (cruzaSecao || (!origem && mc == 0)) {
            elegiveis++;
            if (elegiveis % amostraPilha == 0 && amostrasTick < MAX_AMOSTRAS_TICK) {
                mp = amostrarPilha();
                t0 = 0L; // a chamada ficou cara por causa da amostra: nao entra na media
            }
        }
        t1.registrar(chaveT1(mc, mp, ct, tipo, faixa, mesmaSec ? 1 : 0, reg, cruzouDim ? 1 : 0), dist);
        if (origem && !mesmaSec && (cruzouDim || reg == 2)) {
            int dimD = cruzouDim ? dimIdx(dim) : dimO;
            int ox = osx + 8192, oz = osz + 8192, dx = dsx + 8192, dz = dsz + 8192;
            if (ox < 0 || oz < 0 || dx < 0 || dz < 0 || ox > 16383 || oz > 16383 || dx > 16383 || dz > 16383) {
                t2ForaDaFaixa++;
            } else {
                long k = (long) Math.min(dimO, 15) | ((long) Math.min(dimD, 15) << 4) | ((long) ox << 8)
                        | ((long) oz << 22) | ((long) dx << 36) | ((long) dz << 50);
                t2.somar(k);
            }
        }
        if (t0 != 0L) {
            amostradas++;
            somaAmostradaNs += rel.nano() - t0;
        }
    }

    /** Chave de T1: mod do contexto (12 bits), mod da pilha (12), contexto (4), acesso (4), faixa (3), mesma secao (1), regiao (2), dimensao (1). */
    static long chaveT1(int mc, int mp, int ct, int tipo, int faixa, int mesmaSec, int reg, int cruz) {
        return (long) mc | ((long) mp << 12) | ((long) ct << 24) | ((long) (tipo & 15) << 28) | ((long) faixa << 32)
                | ((long) mesmaSec << 35) | ((long) reg << 36) | ((long) cruz << 38);
    }

    /** Chave de T3: contexto (4 bits), mod do contexto (12), mod da pilha (12), onde caiu (4). */
    static long chaveT3(int ct, int mc, int mp, int onde) {
        return (long) ct | ((long) mc << 4) | ((long) mp << 16) | ((long) onde << 28);
    }

    private int relacaoRegiao(int dimIdx, int osx, int osz, int dsx, int dsz) {
        Foto f = foto;
        if (f == null) {
            return 3;
        }
        int ro = f.regiaoDe(dimIdx, osx, osz);
        int rd = f.regiaoDe(dimIdx, dsx, dsz);
        if (ro < 0 || rd < 0) {
            return 3;
        }
        return ro == rd ? 1 : 2;
    }

    static int faixa(int dist) {
        if (dist <= 0) {
            return 0;
        }
        if (dist <= 2) {
            return 1;
        }
        if (dist <= 8) {
            return 2;
        }
        if (dist <= 16) {
            return 3;
        }
        if (dist <= 48) {
            return 4;
        }
        if (dist <= 63) {
            return 5;
        }
        return 6;
    }

    /** Um chunk foi posto no mapa de chunks carregados (T3). */
    public void cargaChunk(Object mundo, int dim, int cx, int cz) {
        cargas++;
        final int top = prof - 1;
        final int ct = top >= 0 ? ctxTipo[top] : C_OUTRO;
        final boolean origem = top >= 0 && ctxCx[top] != SEM_ORIGEM;
        int onde;
        if (!origem) {
            onde = 4;
        } else if (ctxDim[top] != dim) {
            onde = 5;
        } else {
            int dimI = dimIdx(dim);
            int osx = ctxCx[top] >> 4, osz = ctxCz[top] >> 4, dsx = cx >> 4, dsz = cz >> 4;
            if (osx == dsx && osz == dsz) {
                onde = 0;
            } else if (foto == null) {
                onde = 6;
            } else {
                int ro = foto.regiaoDe(dimI, osx, osz);
                int rd = foto.regiaoDe(dimI, dsx, dsz);
                if (rd < 0) {
                    onde = 2;
                } else if (ro < 0) {
                    onde = 6;
                } else {
                    onde = ro == rd ? 1 : 3;
                }
            }
        }
        int mc = top >= 0 ? modDoContexto(top) : 0;
        int mp = amostrasTick < MAX_AMOSTRAS_TICK ? amostrarPilha() : 0;
        t3.somar(chaveT3(ct, mc, mp, onde));
    }

    /** Acesso vindo de outra thread que nao a do servidor (T4). Seguro entre threads. */
    public void outraThread(int tipo) {
        t4Total.incrementAndGet();
        int espaco = 0;
        if ((t4Sorteio.incrementAndGet() & 63) == 0) {
            espaco = amostraDeOutraThread();
        }
        t4.incrementAndGet((tipo & 15) * Mods.PASSO + espaco);
    }

    /**
     * Amostra de pilha pedida por outra thread (T4 ou lista): 1 + id do mod, ou 0 se o orcamento do tick acabou (a
     * chamada conta como "sem amostra"). Seguro entre threads; o contador passa do teto no maximo pelo numero de threads.
     */
    int amostraDeOutraThread() {
        if (outraAmostrasTick.get() >= MAX_AMOSTRAS_OUTRA_TICK || outraAmostrasTick.incrementAndGet() > MAX_AMOSTRAS_OUTRA_TICK) {
            return 0;
        }
        long a = System.nanoTime();
        int id = mods.id(Pilha.achar(new Throwable().getStackTrace(), donos));
        outraPilhaNs.addAndGet(System.nanoTime() - a);
        outraAmostras.incrementAndGet();
        return 1 + id;
    }

    private int amostrarPilha() {
        long a = rel.nano();
        int id = mods.id(Pilha.achar(new Throwable().getStackTrace(), donos));
        long d = rel.nano() - a;
        pilhaNsTick += d;
        pilhaNsTotal += d;
        amostrasTick++;
        amostras++;
        return id;
    }

    // =====================================================================================================
    // Dimensoes, fotos
    // =====================================================================================================

    int dimIdx(int dim) {
        for (int i = 0; i < nDims; i++) {
            if (dims[i] == dim) {
                return i;
            }
        }
        if (nDims < dims.length) {
            dims[nDims] = dim;
            return nDims++;
        }
        return dims.length - 1;
    }

    /** O numero da dimensao de um indice (ou Integer.MIN_VALUE se o indice nao existe). */
    int dimDoIdx(int idx) {
        return idx >= 0 && idx < nDims ? dims[idx] : Integer.MIN_VALUE;
    }

    public boolean fotoDevida(long agora) {
        return foto == null || agora - ultimaFotoNs >= INTERVALO_FOTO_NS;
    }

    /** Tira a foto das regioes (fase serial, fim do tick). O custo entra na conta da valvula. */
    public void tirarFoto(FotoMundo[] mundos) {
        long t0 = rel.nano();
        int[] idx = new int[mundos.length];
        for (int i = 0; i < mundos.length; i++) {
            idx[i] = dimIdx(mundos[i].dim);
        }
        long agora = rel.nano();
        Foto f = Regioes.calcular(mundos, idx, (agora - inicioNs) / 1000000000L, ticks, 0);
        long custo = rel.nano() - t0;
        foto = new Foto(f.mapa, f.regiaoDaVaga, f.nRegioes,
                new Foto.Resumo(f.resumo.segundos, f.resumo.ticks, f.resumo.dims, custo));
        ultimaFotoNs = agora;
        fotosTiradas++;
        fotoNsTick += custo;
        fotoNsTotal += custo;
        if (fotos.size() >= MAX_FOTOS) {
            fotos.remove(MAX_FOTOS / 2); // mantem a primeira metade e a mais recente: some uma do meio
        }
        fotos.add(foto.resumo);
    }

    // =====================================================================================================
    // Ticks e valvula
    // =====================================================================================================

    public void inicioTick(long agora) {
        liberarContextos();
        tickIni = agora;
        rapidasTick = 0;
        entradasTick = 0;
        completasTick = 0;
        pilhaNsTick = 0;
        fotoNsTick = 0;
        amostrasTick = 0;
        outraAmostrasTick.set(0);
    }

    /** Fim do tick. Devolve true se a valvula mandou parar (o motivo fica em {@link #motivoValvula}). */
    public boolean fimTick(long agora) {
        long dur = Math.max(0L, agora - tickIni);
        // as leituras rapidas deste tick: o contador global menos o que ja foi somado
        long rapidasDesde = rapidas - rapidasContadas;
        rapidasContadas = rapidas;
        long semOrigem = 0;
        for (int i = 0; i < 16; i++) {
            semOrigem += semOrigemLeituras[i];
        }
        long semDesde = semOrigem - semOrigemContadas;
        semOrigemContadas = semOrigem;
        rapidasTick = rapidasDesde + semDesde;
        entradasTotal += entradasTick;
        ticks++;
        long media = amostradas > 0 ? somaAmostradaNs / amostradas : CUSTO_COMPLETA_INICIAL_NS;
        long custo = rapidasTick * CUSTO_RAPIDA_NS + entradasTick * CUSTO_ENTRADA_NS + completasTick * media
                + pilhaNsTick + fotoNsTick + lista.custoTick();
        // Hoshiestar: o que as outras threads pagaram desde o ultimo fim de tick (I1). Nao e tempo da thread do servidor,
        // mas e custo do observador no processo (e a outra thread pode segurar uma trava que o tick espera).
        long outraChamadas = t4Total.get() + lista.outraTotal.get();
        long outraPilha = outraPilhaNs.get();
        custo += (outraChamadas - outraChamadasContadas) * CUSTO_OUTRA_NS + (outraPilha - outraPilhaNsContado);
        outraChamadasContadas = outraChamadas;
        outraPilhaNsContado = outraPilha;
        custoTotalNs += custo;
        tickTotalNs += dur;
        janTicks++;
        janCusto += custo;
        janDur += dur;
        if (janTicks >= JANELA_TICKS) {
            double pct = janDur > 0 ? 100.0 * janCusto / janDur : 0.0;
            boolean acima = false;
            if (aquecendo) {
                // Hoshiestar: a primeira janela de cada observacao paga a primeira foto das regioes (41 a 73 ms com o codigo
                // frio, D-44) e as primeiras amostras de pilha (tambem frias): isso nao e custo de regime e nao para a
                // observacao. Ela entra nos totais; a valvula so olha da segunda janela em diante.
                aquecendo = false;
                pctAquecimento = pct;
            } else {
                if (pct > pctMaxJanela) {
                    pctMaxJanela = pct;
                }
                acima = pct > limiteCustoPct && janCusto / janTicks > CUSTO_MINIMO_NS;
            }
            janTicks = 0;
            janCusto = 0;
            janDur = 0;
            if (acima) {
                motivoValvula = String.format(java.util.Locale.ROOT,
                        "valvula: o custo estimado do observador passou de %.1f%% do tempo do tick (limite %.1f%%)",
                        pct, limiteCustoPct);
                return true;
            }
        }
        return false;
    }

    private long rapidasContadas;
    private long semOrigemContadas;

    /** Custo estimado medio, em % do tempo de tick (desde o inicio). */
    public double custoMedioPct() {
        return tickTotalNs > 0 ? 100.0 * custoTotalNs / tickTotalNs : 0.0;
    }

    public long ticks() {
        return ticks;
    }

    public long acessosCompletos() {
        return completas;
    }

    public long leiturasRapidas() {
        return rapidas;
    }

    public long cargasDeChunk() {
        return cargas;
    }

    public long amostrasDePilha() {
        return amostras;
    }

    public long totalOutraThread() {
        return t4Total.get();
    }

    public int linhasT1() {
        return t1.tamanho();
    }

    public int linhasT2() {
        return t2.tamanho();
    }

    public int linhasT3() {
        return t3.tamanho();
    }

    /** A foto de regioes atual (so para testes e relatorio). */
    Foto foto() {
        return foto;
    }
}
