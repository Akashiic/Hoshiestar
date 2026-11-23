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
package hoshiestar.medidor;

import java.util.HashMap;
import java.util.IdentityHashMap;

/**
 * Os numeros de UMA medicao. Sem nenhum tipo do Minecraft (o mundo chega como Object e a dimensao como
 * numero, dados pela camada {@link GanchosMedidor}), para os testes isolados exercitarem esta mesma classe.
 *
 * Escrita so pela thread principal enquanto a medicao corre;
 * no fim, o objeto inteiro e entregue a thread de gravacao e nunca mais tocado pela
 * thread principal.
 *
 * Memoria limitada. Com limiar de carga 0,5, as capacidades maximas sao 524.288 posicoes
 * (chunks e secao/classe) e 262.144 (secoes), a 41 bytes por posicao (chave 8 + usado 1 +
 * 4 contadores x 8): pior caso por volta de 54 MB fixos (21,5 + 21,5 + 10,7 MB), so durante a
 * medicao. Na ultima expansao de uma tabela grande, a thread do tick aloca de uma vez a tabela
 * nova (21,5 MB) com a velha (10,7 MB) ainda viva, ~65 MB no pico, e reespalha 131 mil chaves
 * (uma pausa unica de poucos ms). Numa medicao normal (producao: ~4.500 chunks), poucos MB.
 */
public final class Coleta {

    static final int TE = TabelaLong.TE;
    static final int ENT = TabelaLong.ENT;

    static final int MAX_DIMS = 1024;          // 10 bits na chave
    static final int MAX_CHUNKS = 200000;
    static final int MAX_SECOES = 100000;
    static final int MAX_SECAO_CLASSE = 200000;
    static final int MAX_CLASSES = 8000;       // cabe em 14 bits; o indice 0 e "outros"

    public static final int DIM_DESCONHECIDA = Integer.MIN_VALUE;

    private static final int LIM27 = 1 << 26;  // coordenada de chunk/secao em 27 bits com sinal
    private static final int LIM20 = 1 << 19;  // coordenada de secao em 20 bits com sinal

    // ---- metadados ----
    final int segundosPedidos;
    long inicioMs;
    long fimMs;
    long inicioNano;
    long fimNano;
    String motivoParada = "?";

    long ticksServidor;
    long nsTickServidor;

    // calibracao (feita no inicio, na propria maquina) e valvula de sobrecarga
    double calibNanoNs;        // custo de uma chamada de System.nanoTime
    double calibQuadroNs;      // custo total de um quadro vazio (abrir, relogio, fechar, somar)
    double calibArtefatoNs;    // parte desse custo que cai dentro do tempo medido de cada quadro
    boolean forcado;           // nanoTime acima do limite e o admin pediu "forcar"
    int refAmostras;           // ticks sem medicao usados na referencia
    long refMedianaNs;         // mediana do tick do servidor antes de ligar (0 = sem referencia)
    /** Margem da regra de custo da valvula nesta medicao (medidor.margem_custo). */
    double margemCusto = Medidor.VALVULA_CUSTO_FATOR_PADRAO;
    long limiteCustoNs;        // limite da regra de custo da valvula nesta medicao (ns por tick)
    long janelas;
    int janelasAcima;
    long ultimaMedianaJanelaNs;
    long ultimoCustoEstNsPorTick;
    long maiorMedianaJanelaNs;
    long maiorCustoEstNsPorTick;
    long quadrosAteUltimaJanela;
    boolean valvulaDisparou;
    String motivoValvula;

    long teNsTotal;
    long entNsTotal;
    long teTicksTotal;
    long entTicksTotal;

    /** Que chamada de update de tile entity os ganchos envolveram: "firewall", "vanilla" ou "desconhecida". */
    String chamadaTe = "desconhecida";
    long quadrosDescartados;   // quadros abertos que nunca fecharam (excecao no tick original)
    long quadrosPulados;       // aninhamento alem do limite da pilha
    long posicoesForaDoLimite;

    // autoteste
    long lacosMundo;          // vezes que o laco de World.func_72939_s rodou de verdade
    long teNasListas;         // soma do tamanho de loadedTileEntityList no inicio desses lacos
    long entNasListas;        // idem para loadedEntityList
    long ticksJogadorRede;    // ticks de jogador medidos pelo caminho da rede (func_71127_g)
    long tickSeq;             // ticks do servidor iniciados durante a medicao
    long ticksComLaco;        // desses, quantos tiveram pelo menos um laco de mundo rodando
    long ultimoTickComLaco = -1L;
    /** Autoteste tirado no tick {@link Medidor#TICKS_AUTOTESTE} (pode ser null se a medicao foi mais curta). */
    Autoteste autotesteInicial;
    /** Autoteste refeito no fim, com os dados da medicao inteira: e o que vale para o relatorio. */
    Autoteste autotesteFinal;

    /** Resultado de um autoteste (foto num momento da medicao). */
    static final class Autoteste {
        String te = "pendente";
        String ent = "pendente";
        long noTick;
        int teCarregados = -1;
        int entCarregadas = -1;
        long lacos;
        long teTicks;
        long entTicks;
        long ticksComLaco;
        long tickSeq;
        /** Algum NAO_LIGADO ou dimensao com problema. */
        boolean problema;
        /** Avisos por dimensao (dimensao que devia tickar e nao foi medida); vazio se nada. */
        final java.util.List<String> dimsSemMedicao = new java.util.ArrayList<String>();
    }

    // ---- dimensoes ----
    final int[] dimId = new int[MAX_DIMS];
    final long[] dimTeNs = new long[MAX_DIMS];
    final long[] dimEntNs = new long[MAX_DIMS];
    final long[] dimTeTicks = new long[MAX_DIMS];
    final long[] dimEntTicks = new long[MAX_DIMS];
    final long[] dimLacos = new long[MAX_DIMS];        // lacos de World.func_72939_s que rodaram
    final long[] dimTeNasListas = new long[MAX_DIMS];  // soma de loadedTileEntityList.size() nesses lacos
    final long[] dimEntNasListas = new long[MAX_DIMS]; // soma de loadedEntityList.size() nesses lacos
    int nDims;
    private final int[] cacheDim = new int[4096];  // ids de -1024 a 3071 -> indice + 1
    private final HashMap<Integer, Integer> dimRaras = new HashMap<Integer, Integer>();
    private Object ultimoMundo;
    private int ultimoIdx;

    // ---- tabelas ----
    final TabelaLong chunks = new TabelaLong(4096, MAX_CHUNKS);
    final TabelaLong secoes = new TabelaLong(1024, MAX_SECOES);
    final TabelaLong secaoClasse = new TabelaLong(4096, MAX_SECAO_CLASSE);

    // ---- classes ----
    static final class Classe {
        final String nome;
        final int idx;
        final boolean tileEntity;
        long teNs;
        long entNs;
        long teTicks;
        long entTicks;

        Classe(String nome, int idx, boolean tileEntity) {
            this.nome = nome;
            this.idx = idx;
            this.tileEntity = tileEntity;
        }

        long ns() {
            return teNs + entNs;
        }
    }

    private final IdentityHashMap<Class<?>, Classe> classes = new IdentityHashMap<Class<?>, Classe>();
    /** Classes do caminho de rede do jogador (EntityPlayerMP.func_71127_g), separadas do tick pelo laco do mundo. */
    private final IdentityHashMap<Class<?>, Classe> classesRede = new IdentityHashMap<Class<?>, Classe>();
    static final String SUFIXO_REDE = " (tick pela rede)";
    final Classe[] classePorIdx = new Classe[MAX_CLASSES + 1];
    int nClasses;
    long classesRecusadas;

    Coleta(int segundosPedidos) {
        this.segundosPedidos = segundosPedidos;
        classePorIdx[0] = new Classe("(outros: limite de classes atingido)", 0, false);
    }

    /**
     * Indice interno da dimensao do mundo {@code w}, cujo numero de dimensao e {@code id}
     * ({@link #DIM_DESCONHECIDA} se nao se sabe). Rapido quando o mundo e o mesmo da ultima chamada.
     */
    int indiceDim(Object w, int id) {
        if (w == ultimoMundo && w != null) {
            return ultimoIdx;
        }
        int idx = indicePorId(id);
        ultimoMundo = w;
        ultimoIdx = idx;
        return idx;
    }

    private int indicePorId(int id) {
        int c = id + 1024;
        if (c >= 0 && c < cacheDim.length) {
            int v = cacheDim[c];
            if (v != 0) {
                return v - 1;
            }
            int idx = novaDim(id);
            cacheDim[c] = idx + 1;
            return idx;
        }
        Integer v = dimRaras.get(id);
        if (v != null) {
            return v;
        }
        int idx = novaDim(id);
        dimRaras.put(id, idx);
        return idx;
    }

    private int novaDim(int id) {
        if (nDims >= MAX_DIMS - 1) {
            // a ultima posicao vira "outras dimensoes"
            dimId[MAX_DIMS - 1] = DIM_DESCONHECIDA;
            if (nDims < MAX_DIMS) {
                nDims = MAX_DIMS;
            }
            return MAX_DIMS - 1;
        }
        dimId[nDims] = id;
        return nDims++;
    }

    private Classe classe(Class<?> cls, int tipo, boolean rede) {
        IdentityHashMap<Class<?>, Classe> mapa = rede ? classesRede : classes;
        Classe c = mapa.get(cls);
        if (c != null) {
            return c;
        }
        if (nClasses >= MAX_CLASSES) {
            classesRecusadas++;
            return classePorIdx[0];
        }
        int idx = ++nClasses;
        c = new Classe(rede ? cls.getName() + SUFIXO_REDE : cls.getName(), idx, tipo == TE);
        mapa.put(cls, c);
        classePorIdx[idx] = c;
        return c;
    }

    static long chaveChunk(int dimIdx, int x, int z) {
        return ((long) (dimIdx & 0x3FF) << 54) | ((long) (x & 0x7FFFFFF) << 27) | (long) (z & 0x7FFFFFF);
    }

    static int dimDaChave(long k) {
        return (int) (k >>> 54);
    }

    static int xDaChave(long k) {
        return (int) ((k << 10) >> 37);
    }

    static int zDaChave(long k) {
        return (int) ((k << 37) >> 37);
    }

    static long chaveSecaoClasse(int dimIdx, int sx, int sz, int classeIdx) {
        return ((long) (dimIdx & 0x3FF) << 54) | ((long) (sx & 0xFFFFF) << 34) | ((long) (sz & 0xFFFFF) << 14)
                | (long) (classeIdx & 0x3FFF);
    }

    static int sxDaChaveSC(long k) {
        return (int) ((k << 10) >> 44);
    }

    static int szDaChaveSC(long k) {
        return (int) ((k << 30) >> 44);
    }

    static int classeDaChaveSC(long k) {
        return (int) (k & 0x3FFF);
    }

    /** Soma um quadro fechado. Chamado so pela thread principal. */
    void adicionar(int tipo, int d, int cx, int cz, Class<?> cls, long ns, boolean rede) {
        if (rede) {
            ticksJogadorRede++;
        }
        if (tipo == TE) {
            teNsTotal += ns;
            teTicksTotal++;
            dimTeNs[d] += ns;
            dimTeTicks[d]++;
        } else {
            entNsTotal += ns;
            entTicksTotal++;
            dimEntNs[d] += ns;
            dimEntTicks[d]++;
        }
        int sx = cx >> 4;
        int sz = cz >> 4;
        if (cx >= -LIM27 && cx < LIM27 && cz >= -LIM27 && cz < LIM27) {
            chunks.somar(chaveChunk(d, cx, cz), tipo, ns);
        } else {
            posicoesForaDoLimite++;
            chunks.somarOutros(tipo, ns);
        }
        secoes.somar(chaveChunk(d, sx, sz), tipo, ns);
        Classe c = classe(cls, tipo, rede);
        if (tipo == TE) {
            c.teNs += ns;
            c.teTicks++;
        } else {
            c.entNs += ns;
            c.entTicks++;
        }
        if (sx >= -LIM20 && sx < LIM20 && sz >= -LIM20 && sz < LIM20) {
            secaoClasse.somar(chaveSecaoClasse(d, sx, sz, c.idx), tipo, ns);
        } else {
            secaoClasse.somarOutros(tipo, ns);
        }
    }

    long totalNs() {
        return teNsTotal + entNsTotal;
    }

    /** Solta referencias a objetos do jogo assim que a medicao para. */
    void soltarMundo() {
        ultimoMundo = null;
    }
}
