package hoshiestar.teste.chunkmap;

import hoshiestar.api.ThreadServidor;
import hoshiestar.chunkmap.ChunkMap;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * Microbenchmark do get (SO INDICACAO, nao e medicao de servidor). Adaptado do Bench.java do
 * relatorio E-chunkmap.md: 8 areas de jogador de 21x21 chunks + 500 chunks soltos (perto dos ~4100 a
 * 4800 chunks da producao), 400 "ancoras" (tile entities / entidades) e quatro padroes de consulta.
 * Antes de cada bloco de 256 consultas o benchmark suja o cache do processador (8 MB), como o tick real
 * faz entre uma consulta e outra.
 *
 * <p>Uso: {@code BenchChunkmap <original|desligado|ligado|cache4>} (um por JVM: o interruptor e static final).
 * Padroes: 1 = mesmo chunk 4x e um vizinho; 2 = ancora +-1 chunk, 6 consultas; 3 = chunk carregado
 * qualquer (aleatorio); 4 = como 2, com 20% das consultas na borda da area (fora do mapa as vezes).
 */
public final class BenchChunkmap {

    static int sink;

    interface Get {
        Chunk get(int x, int z);

        void put(Chunk c);
    }

    public static void main(String[] a) {
        final String impl = a.length > 0 ? a[0] : "ligado";
        ChunkMap.configurarParaTeste(impl.equals("ligado"), 6, false);
        ChunkMap.configurarCache4ParaTeste(impl.equals("cache4")); // cache4 = chunkmap desligado + chave chunkmap.cache4Seguro (H01)
        ThreadServidor.definir(Thread.currentThread());
        for (int pat = 1; pat <= 4; pat++) {
            Get m;
            if (impl.equals("original")) {
                final hoshiestar.teste.chunkmap.original.ChunkBlockHashMap o = new hoshiestar.teste.chunkmap.original.ChunkBlockHashMap();
                m = new Get() {
                    public Chunk get(int x, int z) {
                        return o.get(x, z);
                    }

                    public void put(Chunk c) {
                        o.put(c);
                    }
                };
            } else {
                final thermos.wrapper.ChunkBlockHashMap n = new thermos.wrapper.ChunkBlockHashMap();
                m = new Get() {
                    public Chunk get(int x, int z) {
                        return n.get(x, z);
                    }

                    public void put(Chunk c) {
                        n.put(c);
                    }
                };
            }
            rodar(impl, m, pat);
        }
    }

    static void rodar(String impl, Get m, int pat) {
        Random r = new Random(7);
        List<int[]> loaded = new ArrayList<int[]>();
        HashSet<Long> has = new HashSet<Long>();
        int[][] centers = new int[8][];
        for (int p = 0; p < 8; p++) {
            int cx = r.nextInt(6000) - 3000;
            int cz = r.nextInt(6000) - 3000;
            centers[p] = new int[]{cx, cz};
            for (int dx = -10; dx <= 10; dx++) {
                for (int dz = -10; dz <= 10; dz++) {
                    add(m, loaded, has, cx + dx, cz + dz);
                }
            }
        }
        for (int i = 0; i < 500; i++) {
            add(m, loaded, has, r.nextInt(20000) - 10000, r.nextInt(20000) - 10000);
        }
        int[][] anch = new int[400][];
        for (int i = 0; i < anch.length; i++) {
            int[] c = centers[r.nextInt(8)];
            anch[i] = new int[]{c[0] + r.nextInt(15) - 7, c[1] + r.nextInt(15) - 7};
        }
        int n = 1 << 22;
        int[] qx = new int[n];
        int[] qz = new int[n];
        int i = 0;
        while (i < n) {
            if (pat == 1) {
                int[] an = anch[r.nextInt(anch.length)];
                for (int k = 0; k < 4 && i < n; k++, i++) {
                    qx[i] = an[0];
                    qz[i] = an[1];
                }
                if (i < n) {
                    qx[i] = an[0] + r.nextInt(3) - 1;
                    qz[i] = an[1] + r.nextInt(3) - 1;
                    i++;
                }
            } else if (pat == 2 || pat == 4) {
                int[] an = anch[r.nextInt(anch.length)];
                for (int k = 0; k < 6 && i < n; k++, i++) {
                    if (pat == 4 && r.nextInt(5) == 0) {
                        int[] c = centers[r.nextInt(8)];
                        qx[i] = c[0] + 11 + r.nextInt(3);
                        qz[i] = c[1] + r.nextInt(21) - 10;
                    } else {
                        qx[i] = an[0] + r.nextInt(3) - 1;
                        qz[i] = an[1] + r.nextInt(3) - 1;
                    }
                }
            } else {
                int[] c = loaded.get(r.nextInt(loaded.size()));
                qx[i] = c[0];
                qz[i] = c[1];
                i++;
            }
        }
        byte[] junk = new byte[8 << 20];
        double[] res = new double[7];
        for (int rep = 0; rep < res.length; rep++) {
            long t = 0;
            int s = 0;
            for (int b = 0; b < n; b += 256) {
                for (int j = (b & 63); j < junk.length; j += 64) {
                    junk[j]++;
                }
                long t0 = System.nanoTime();
                for (int j = b; j < b + 256; j++) {
                    Chunk c = m.get(qx[j], qz[j]);
                    if (c != null) {
                        s += c.xPosition;
                    }
                }
                t += System.nanoTime() - t0;
            }
            sink += s;
            res[rep] = (double) t / n;
        }
        Arrays.sort(res);
        System.out.printf("  %-9s padrao=%d  ns/get mediana=%.2f  min=%.2f  (Java %s)%n", impl, pat, res[3], res[0],
                System.getProperty("java.version"));
    }

    static void add(Get m, List<int[]> l, HashSet<Long> h, int x, int z) {
        if (h.add(Apoio.chave(x, z))) {
            m.put(new Chunk(x, z));
            l.add(new int[]{x, z});
        }
    }
}
