package hoshiestar.teste.chunkmap;

import com.koloboke.collect.map.LongObjMap;
import hoshiestar.api.ThreadServidor;
import hoshiestar.chunkmap.ChunkMap;
import hoshiestar.teste.Verifica;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Diferencial aleatorio: a classe ORIGINAL do Crucible (copia do upstream em
 * hoshiestar.teste.chunkmap.original) contra a classe NOVA do fork (thermos.wrapper, com o patch
 * do chunkmap), recebendo exatamente as mesmas operacoes.
 *
 * <p>Uso: {@code TesteChunkmapDiferencial <desligado|ligado|conferir> [operacoes por rodada] [sementes]}.
 * O interruptor e um static final lido quando a classe carrega, entao cada modo roda numa JVM.
 *
 * <p>Regras conferidas:
 * <ul>
 * <li>desligado: TUDO identico ao original (get pelo mesmo objeto, size, bulkCheck, raw, excecoes),
 * inclusive depois de put duplo + remove;</li>
 * <li>ligado/conferir: put/remove/size/bulkCheck/raw/excecoes identicos; get devolve SEMPRE o que o
 * mapa guarda (modelo de referencia); e get e identico ao original, menos quando o original devolve
 * um chunk que o mapa NAO guarda mais (o defeito do cache de 4 entradas, so possivel numa posicao
 * que ja recebeu put sobre posicao ocupada). Essas diferencas sao contadas, e qualquer outra e falha.
 * Nas rodadas sem put duplo: zero diferencas.</li>
 * </ul>
 */
public final class TesteChunkmapDiferencial {

    private static final int DOMINIO_PEQUENO = 0;
    private static final int DOMINIO_GRANDE = 1;
    private static final int DOMINIO_COLISAO = 2;
    private static final String[] NOME_DOMINIO = {"pequeno", "grande+negativos+extremos", "colisao de posicao"};

    private static ExecutorService fora;

    public static void main(String[] args) throws Exception {
        String modo = args.length > 0 ? args[0] : "ligado";
        int ops = args.length > 1 ? Integer.parseInt(args[1]) : 1000000;
        int sementes = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        boolean ligado = !modo.equals("desligado");
        boolean conferir = modo.equals("conferir");
        ChunkMap.configurarParaTeste(ligado, 6, conferir);
        ThreadServidor.definir(Thread.currentThread());
        fora = Executors.newSingleThreadExecutor();

        Verifica v = new Verifica("TesteChunkmapDiferencial modo=" + modo + " ops/rodada=" + ops + " sementes=" + sementes);
        thermos.wrapper.ChunkBlockHashMap teste = new thermos.wrapper.ChunkBlockHashMap();
        v.igual(ligado, Apoio.estatico(thermos.wrapper.ChunkBlockHashMap.class, "HOSHIE_CHUNKMAP"), "interruptor lido pelo patch");
        v.sim(ligado == (Apoio.cache(teste) != null), "cache existe so com o interruptor ligado");

        long totalOps = 0;
        long totalGets = 0;
        long totalConhecidas = 0;
        long totalPutDuplo = 0;
        long totalForaDoMapa = 0;
        int[] bitsTestados = ligado ? new int[]{4, 6, 7} : new int[]{6};
        for (int bits : bitsTestados) {
            for (int s = 1; s <= sementes; s++) {
                for (int dom = 0; dom < 3; dom++) {
                    for (int dup = 0; dup < 2; dup++) {
                        ChunkMap.configurarParaTeste(ligado, bits, conferir); // bits vale para mapas novos
                        long semente = 1000L * s + 10 * dom + dup + 100000L * bits;
                        Rodada r = new Rodada(ligado, dup == 1, dom, bits, semente);
                        r.rodar(ops);
                        totalOps += r.ops;
                        totalGets += r.gets;
                        totalConhecidas += r.conhecidas;
                        totalPutDuplo += r.putsDuplos;
                        totalForaDoMapa += r.originalForaDoMapa;
                        String nome = "bits=" + bits + " semente=" + semente + " dominio=" + NOME_DOMINIO[dom]
                                + (dup == 1 ? " COM put duplo" : " sem put duplo");
                        v.sim(r.falhas == 0, nome + ": " + r.resumo());
                        if (r.falhas > 0) {
                            for (String f : r.primeiras) {
                                System.out.println("      " + f);
                            }
                        }
                        if (!ligado || dup == 0) {
                            v.igual(0L, r.conhecidas, nome + ": nenhuma diferenca de get com o original");
                        }
                        if (ligado) {
                            v.sim(r.cacheAtivoNoFim, nome + ": cache continua ativo no fim (nada o desligou)");
                        }
                    }
                }
            }
        }
        v.sim(totalForaDoMapa > 0, "o caminho do defeito do original (put duplo + remove) foi exercitado: o original devolveu chunk"
                + " fora do mapa " + totalForaDoMapa + " vez(es)" + (ligado ? "" : ", e o novo DESLIGADO devolveu exatamente o mesmo objeto em todas"));
        if (ligado) {
            v.sim(totalConhecidas > 0, "o defeito conhecido do original apareceu e foi contado (" + totalConhecidas
                    + " gets em que o original devolveu chunk fora do mapa; o novo devolveu o do mapa)");
        }
        System.out.println("  total: " + totalOps + " operacoes, " + totalGets + " gets, " + totalPutDuplo
                + " puts sobre posicao ocupada, " + totalConhecidas + " diferencas conhecidas (so do defeito do original)");
        if (conferir) {
            System.out.println("  status: " + ChunkMap.linhaDeEstado());
            v.sim(ChunkMap.linhaDeEstado().contains("divergencias 0"), "modo conferir: 0 divergencias entre cache e mapa");
        }
        fora.shutdownNow();
        v.fim();
    }

    /** Uma rodada: mesma sequencia aleatoria nas duas classes. */
    static final class Rodada {
        final boolean ligado;
        final boolean dup;
        final int dominio;
        final int bits;
        final Random r;
        final hoshiestar.teste.chunkmap.original.ChunkBlockHashMap o = new hoshiestar.teste.chunkmap.original.ChunkBlockHashMap();
        final thermos.wrapper.ChunkBlockHashMap n = new thermos.wrapper.ChunkBlockHashMap();
        final Map<Long, Chunk> ref = new HashMap<Long, Chunk>();
        final Set<Long> historicoDup = new HashSet<Long>();
        final Chunk[] antigos = new Chunk[4096];
        int nAntigos;
        long ops;
        long gets;
        long conhecidas;
        long putsDuplos;
        long falhas;
        long getsFora;
        long originalForaDoMapa;
        int[] foco;
        int focoRestante;
        long npe;
        long bulk;
        long raws;
        boolean cacheAtivoNoFim;
        final List<String> primeiras = new ArrayList<String>();
        final int[] bases;

        Rodada(boolean ligado, boolean dup, int dominio, int bits, long semente) {
            this.ligado = ligado;
            this.dup = dup;
            this.dominio = dominio;
            this.bits = bits;
            this.r = new Random(semente);
            this.bases = new int[]{r.nextInt(2000) - 1000, -1875000 + r.nextInt(100), Integer.MIN_VALUE + 1000, Integer.MAX_VALUE - 1000 * 64};
        }

        void falha(String s) {
            falhas++;
            if (primeiras.size() < 10) {
                primeiras.add("op " + ops + ": " + s);
            }
        }

        int[] posicao() {
            if (focoRestante > 0) {
                // logo depois de um put duplo: as proximas operacoes caem na mesma posicao (remove e get
                // logo em seguida e o caminho em que o cache de 4 entradas do original devolve chunk velho)
                focoRestante--;
                return foco;
            }
            return sortear();
        }

        int[] sortear() {
            int x;
            int z;
            if (dominio == DOMINIO_PEQUENO) {
                x = r.nextInt(80) - 40;
                z = r.nextInt(80) - 40;
            } else if (dominio == DOMINIO_GRANDE) {
                int sel = r.nextInt(20);
                if (sel < 12) {
                    x = r.nextInt(100) - 50;
                    z = r.nextInt(100) - 50;
                } else if (sel < 17) {
                    x = r.nextInt(40) + 1875000 - 20;
                    z = -1875000 + r.nextInt(40) - 20;
                } else {
                    x = (r.nextBoolean() ? Integer.MIN_VALUE : Integer.MAX_VALUE) - (r.nextInt(40) - 20);
                    z = (r.nextBoolean() ? Integer.MIN_VALUE : Integer.MAX_VALUE) - (r.nextInt(40) - 20);
                }
            } else {
                // varias posicoes que caem na MESMA casa da tabela (passo 2^bits), negativas e positivas
                int passo = 1 << bits;
                int b = bases[r.nextInt(bases.length)];
                x = b + passo * (r.nextInt(9) - 4) + r.nextInt(2);
                z = -b + passo * (r.nextInt(9) - 4) + r.nextInt(2);
            }
            return new int[]{x, z};
        }

        void focar(int[] p) {
            if (r.nextBoolean()) {
                foco = p;
                focoRestante = 2 + r.nextInt(6);
            }
        }

        void lembrar(Chunk c) {
            antigos[nAntigos++ & (antigos.length - 1)] = c;
        }

        Chunk antigo() {
            int n = Math.min(nAntigos, antigos.length);
            return n == 0 ? null : antigos[r.nextInt(n)];
        }

        void rodar(int total) throws Exception {
            for (int i = 0; i < total; i++) {
                ops++;
                int[] p = posicao();
                int x = p[0];
                int z = p[1];
                long k = Apoio.chave(x, z);
                int op = r.nextInt(1000);
                if (op < 120) {
                    // put de um chunk novo (sem put duplo: so em posicao livre)
                    boolean ocupada = ref.containsKey(k);
                    if (ocupada && !dup) {
                        get(x, z, k);
                        continue;
                    }
                    Chunk c = new Chunk(x, z);
                    if (ocupada) {
                        putsDuplos++;
                        historicoDup.add(k);
                        focar(p);
                    }
                    o.put(c);
                    n.put(c);
                    ref.put(k, c);
                    lembrar(c);
                } else if (op < 135 && dup) {
                    // put do MESMO objeto de novo (tambem e put sobre posicao ocupada)
                    Chunk c = ref.get(k);
                    if (c == null) {
                        continue;
                    }
                    putsDuplos++;
                    historicoDup.add(k);
                    focar(p);
                    o.put(c);
                    n.put(c);
                } else if (op < 140) {
                    o.put(null);
                    n.put(null);
                } else if (op < 230) {
                    // remove: o objeto atual, outro objeto com as mesmas coordenadas, um antigo
                    // (que pode estar noutra posicao) ou um que nunca entrou
                    Chunk c;
                    int tipo = r.nextInt(10);
                    if (tipo < 6) {
                        c = ref.get(k);
                        if (c == null) {
                            c = new Chunk(x, z);
                        }
                    } else if (tipo < 8) {
                        c = new Chunk(x, z);
                    } else {
                        c = antigo();
                        if (c == null) {
                            continue;
                        }
                        k = Apoio.chave(c.xPosition, c.zPosition);
                    }
                    o.remove(c);
                    n.remove(c);
                    ref.remove(k);
                } else if (op < 233) {
                    // remove(null): os dois tem de lancar NullPointerException
                    boolean eo = false;
                    boolean en = false;
                    try {
                        o.remove(null);
                    } catch (NullPointerException e) {
                        eo = true;
                    }
                    try {
                        n.remove(null);
                    } catch (NullPointerException e) {
                        en = true;
                    }
                    npe++;
                    if (!eo || !en) {
                        falha("remove(null): original NPE=" + eo + ", novo NPE=" + en);
                    }
                } else if (op < 240) {
                    bulk++;
                    List<int[]> lista = new ArrayList<int[]>();
                    int q = 1 + r.nextInt(6);
                    for (int j = 0; j < q; j++) {
                        int[] pp = r.nextInt(3) == 0 ? sortear() : new int[]{x + r.nextInt(3) - 1, z + r.nextInt(3) - 1};
                        lista.add(pp);
                    }
                    boolean bo = o.bulkCheck(lista);
                    boolean bn = n.bulkCheck(lista);
                    if (bo != bn) {
                        falha("bulkCheck diferente: original " + bo + ", novo " + bn);
                    }
                } else if (op < 243) {
                    getFora(x, z, k);
                } else {
                    get(x, z, k);
                }
                if (o.size() != n.size()) {
                    falha("size diferente: original " + o.size() + ", novo " + n.size());
                }
                if ((ops & 0x3FFFF) == 0) {
                    compararRaw();
                }
            }
            compararRaw();
            // varredura final: toda posicao conhecida
            for (Map.Entry<Long, Chunk> e : new ArrayList<Map.Entry<Long, Chunk>>(ref.entrySet())) {
                long kk = e.getKey();
                get((int) (kk >> 32), (int) kk, kk);
            }
            cacheAtivoNoFim = Apoio.cacheAtivo(n);
        }

        void get(int x, int z, long k) {
            gets++;
            Chunk e = ref.get(k);
            Chunk co = o.get(x, z);
            Chunk cn = n.get(x, z);
            comparar(x, z, k, e, co, cn, "get");
        }

        void getFora(final int x, final int z, long k) throws Exception {
            gets++;
            getsFora++;
            Chunk e = ref.get(k);
            Chunk co = o.get(x, z);
            Chunk cn = fora.submit(new Callable<Chunk>() {
                @Override
                public Chunk call() {
                    return n.get(x, z);
                }
            }).get();
            comparar(x, z, k, e, co, cn, "get de outra thread");
        }

        void comparar(int x, int z, long k, Chunk e, Chunk co, Chunk cn, String o2) {
            if (co != e) {
                originalForaDoMapa++;
            }
            if (!ligado) {
                if (co != cn) {
                    falha(o2 + "(" + x + "," + z + ") desligado: original " + co + ", novo " + cn);
                }
                return;
            }
            if (cn != e) {
                falha(o2 + "(" + x + "," + z + "): novo " + cn + " != mapa " + e);
            }
            if (co != cn) {
                if (co != e && historicoDup.contains(k)) {
                    conhecidas++;
                } else {
                    falha(o2 + "(" + x + "," + z + "): original " + co + " != novo " + cn + " (mapa " + e + ")");
                }
            }
        }

        @SuppressWarnings("unchecked")
        void compararRaw() {
            raws++;
            LongObjMap<Chunk[][]> ro = o.raw();
            LongObjMap<Chunk[][]> rn = n.raw();
            if (ro.size() != rn.size() || !ro.keySet().equals(rn.keySet())) {
                falha("raw(): chaves diferentes");
                return;
            }
            for (long chave : ro.keySet()) {
                Chunk[][] a = ro.get(chave);
                Chunk[][] b = rn.get(chave);
                for (int i = 0; i < 16; i++) {
                    for (int j = 0; j < 16; j++) {
                        if (a[i][j] != b[i][j]) {
                            falha("raw(): conteudo diferente na chave " + chave);
                            return;
                        }
                        // e o modelo de referencia tem de bater com a estrutura
                        Chunk c = a[i][j];
                        if (c != null && ref.get(Apoio.chave(c.xPosition, c.zPosition)) != c) {
                            falha("modelo de referencia nao bate com a estrutura em " + c);
                            return;
                        }
                    }
                }
            }
        }

        String resumo() {
            return ops + " ops, " + gets + " gets (" + getsFora + " de outra thread), " + putsDuplos + " puts duplos, "
                    + npe + " remove(null), " + bulk + " bulkCheck, " + raws + " raw(); original devolveu chunk fora do mapa "
                    + originalForaDoMapa + " vez(es); diferencas conhecidas " + conhecidas
                    + ", falhas " + falhas;
        }
    }
}
