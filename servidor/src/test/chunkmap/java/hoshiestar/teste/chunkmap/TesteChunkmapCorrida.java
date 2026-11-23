package hoshiestar.teste.chunkmap;

import com.koloboke.collect.map.LongObjMap;
import hoshiestar.api.ThreadServidor;
import hoshiestar.chunkmap.ChunkMap;
import hoshiestar.teste.Verifica;
import net.minecraft.world.chunk.Chunk;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Corridas e caminhos de seguranca do chunkmap (interruptor LIGADO; a thread "main" faz o papel da
 * thread do servidor). Uso: {@code TesteChunkmapCorrida [iteracoes do estresse]}.
 *
 * <ol>
 * <li>Replay deterministico da corrida do conferente (RaceDemo/RaceOrig do relatorio
 * E-chunkmap-conferencia.md, secao 3.1, "Problema A"): um leitor de outra thread acha o chunk C no
 * mapa, a thread do servidor descarrega C, e depois a thread do servidor consulta a posicao 1 milhao
 * de vezes. Na classe nova: 0 vezes C (o leitor de fora nao grava a tabela). Para comparar, o mesmo
 * replay no MODELO sem a protecao (o desenho E1 original, reproduzido aqui) da 1000000, e na classe
 * original da 3.</li>
 * <li>Estresse com threads de verdade: a thread do servidor poe e tira chunks e confere cada
 * resultado; 3 leitores de outras threads consultam sem parar a mesma area. Tem de dar 0 chunk preso,
 * 0 resultado errado na thread do servidor, tabela coerente com o mapa no fim e cache ainda ativo. O
 * mesmo estresse no modelo sem protecao serve de controle (mostra que o estresse acha o defeito).</li>
 * <li>put/remove de outra thread: o cache daquele mapa se desliga ANTES da mudanca e o mapa segue o
 * codigo original, com resultados certos.</li>
 * <li>Erro dentro do cache: cai no resultado certo, desliga o cache, segue o codigo original.</li>
 * <li>Modo conferir: um acerto errado (posto a forca) e detectado, contado e corrigido.</li>
 * <li>Mapa criado sem a thread do servidor definida: sem cache (codigo original).</li>
 * </ol>
 */
public final class TesteChunkmapCorrida {

    public static void main(String[] args) throws Exception {
        int iter = args.length > 0 ? Integer.parseInt(args[0]) : 2000000;
        ChunkMap.configurarParaTeste(true, 6, false);
        ThreadServidor.definir(Thread.currentThread());
        Verifica v = new Verifica("TesteChunkmapCorrida (iteracoes do estresse: " + iter + ")");

        replay(v);
        estresse(v, iter);
        mudancaDeFora(v);
        erroInterno(v);
        conferir(v);
        semThread(v);
        System.out.println("  status: " + ChunkMap.linhaDeEstado());
        v.fim();
    }

    // ------------------------------------------------------------------ 1. replay deterministico

    static void area(Mapa m, Chunk alvo) {
        m.put(alvo);
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                if (!(x == alvo.xPosition && z == alvo.zPosition)) {
                    m.put(new Chunk(x, z));
                }
            }
        }
    }

    static int presos(Mapa m, Chunk c) {
        int presos = 0;
        for (int k = 0; k < 1000000; k++) {
            int x = (k % 21) - 10;
            int z = ((k / 21) % 21) - 10;
            m.get(x, z);
            if (m.get(c.xPosition, c.zPosition) == c) {
                presos++;
            }
        }
        return presos;
    }

    static void replay(Verifica v) throws Exception {
        // classe nova: o leitor de fora acha C com a casa da tabela ocupada por outro chunk e com ela vazia
        for (int vazia = 0; vazia < 2; vazia++) {
            final Mapa m = Mapa.novo();
            final Chunk c = new Chunk(5, 7);
            area(m, c);
            m.get(5, 7);
            Chunk outro = new Chunk(5 + 64, 7); // mesma casa da tabela que (5,7)
            m.put(outro);                        // a casa passa a ter o "outro"
            if (vazia == 1) {
                m.remove(outro);                 // casa vazia
            }
            final Chunk[] visto = new Chunk[1];
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    visto[0] = m.get(5, 7);
                }
            }, "leitor-de-fora");
            t.start();
            t.join();
            Chunk casa = Apoio.slots(m.novo)[(5 & 63) | ((7 & 63) << 6)];
            v.sim(visto[0] == c, "replay (casa " + (vazia == 1 ? "vazia" : "ocupada") + "): o leitor de fora acha C no mapa");
            v.sim(casa == (vazia == 1 ? null : outro), "replay: o leitor de fora NAO gravou C na tabela (casa = " + casa + ")");
            m.remove(c);
            int p = presos(m, c);
            v.igual(0, p, "replay: depois de descarregar C, get(5,7) na thread do servidor devolveu C (chunk preso) em quantas de 1000000");
        }
        // controle: o modelo sem protecao (desenho E1 antes da correcao) e com protecao, e o original
        for (boolean protegido : new boolean[]{false, true}) {
            ModeloE1 m = new ModeloE1(Thread.currentThread(), protegido);
            Chunk c = new Chunk(5, 7);
            m.put(c);
            m.get(5, 7);
            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    if (!(x == 5 && z == 7)) {
                        m.put(new Chunk(x, z));
                    }
                }
            }
            m.slots[ModeloE1.idx(5, 7)] = null;
            final ModeloE1 mm = m;
            final Chunk[] visto = new Chunk[1];
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    visto[0] = mm.buscar(5, 7);
                }
            });
            t.start();
            t.join();
            m.remove(c);
            final Chunk vv = visto[0];
            Thread t2 = new Thread(new Runnable() {
                @Override
                public void run() {
                    mm.preencher(5, 7, vv);
                }
            });
            t2.start();
            t2.join();
            int presos = 0;
            for (int k = 0; k < 1000000; k++) {
                int x = (k % 21) - 10;
                int z = ((k / 21) % 21) - 10;
                m.get(x, z);
                if (m.get(5, 7) == c) {
                    presos++;
                }
            }
            System.out.println("  (controle) modelo E1 " + (protegido ? "COM" : "SEM") + " a protecao: chunk preso em " + presos + " de 1000000");
            if (!protegido) {
                v.sim(presos == 1000000, "controle: o modelo sem protecao prende o chunk (mostra que o replay acha o defeito)");
            } else {
                v.igual(0, presos, "controle: o modelo com protecao nao prende");
            }
        }
        hoshiestar.teste.chunkmap.original.ChunkBlockHashMap o = new hoshiestar.teste.chunkmap.original.ChunkBlockHashMap();
        Chunk c = new Chunk(5, 7);
        o.put(c);
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                if (!(x == 5 && z == 7)) {
                    o.put(new Chunk(x, z));
                }
            }
        }
        o.remove(c);
        Apoio.definirCampo(o, "last1", c); // o estado que a mesma corrida deixa no original
        int presos = 0;
        int primeiroLivre = -1;
        for (int k = 0; k < 1000000; k++) {
            int x = (k % 21) - 10;
            int z = ((k / 21) % 21) - 10;
            o.get(x, z);
            if (o.get(5, 7) == c) {
                presos++;
            } else if (primeiroLivre < 0) {
                primeiroLivre = k;
            }
        }
        System.out.println("  (controle) original: chunk preso em " + presos + " de 1000000 (some na iteracao " + primeiroLivre + ")");
    }

    // ------------------------------------------------------------------ 2. estresse com threads

    static void estresse(Verifica v, int iter) throws Exception {
        for (int tipo = 0; tipo < 2; tipo++) {
            final boolean modelo = tipo == 1;
            final Mapa m = modelo ? Mapa.modelo(new ModeloE1(Thread.currentThread(), false)) : Mapa.novo();
            // area pequena (8x8) mais as posicoes da mesma casa da tabela (+64): leitores e servidor
            // brigam pelas mesmas posicoes. Os grupos de 16x16 do mapa sao criados antes, para o mapa
            // koloboke nao crescer durante a corrida (o original tambem nao e seguro para isso).
            final int[][] pos = new int[128][];
            for (int i = 0; i < 64; i++) {
                pos[i] = new int[]{i % 8, i / 8};
                pos[64 + i] = new int[]{i % 8 + 64, i / 8};
            }
            for (int[] p : pos) {
                Chunk c = new Chunk(p[0], p[1]);
                m.put(c);
                m.remove(c);
            }
            final Map<Long, Chunk> ref = new HashMap<Long, Chunk>();
            final AtomicBoolean parar = new AtomicBoolean();
            final AtomicLong leituras = new AtomicLong();
            final AtomicLong coordErradas = new AtomicLong();
            final AtomicLong excecoesLeitor = new AtomicLong();
            Thread[] leitores = new Thread[3];
            for (int t = 0; t < leitores.length; t++) {
                final int semente = t;
                leitores[t] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Random r = new Random(semente);
                        long n = 0;
                        while (!parar.get()) {
                            int[] p = pos[r.nextInt(pos.length)];
                            try {
                                Chunk c = m.get(p[0], p[1]);
                                if (c != null && (c.xPosition != p[0] || c.zPosition != p[1])) {
                                    coordErradas.incrementAndGet();
                                }
                            } catch (Throwable e) {
                                excecoesLeitor.incrementAndGet();
                            }
                            n++;
                        }
                        leituras.addAndGet(n);
                    }
                }, "leitor-" + t);
                leitores[t].start();
            }
            Random r = new Random(42);
            long presos = 0;
            long errados = 0;
            long removes = 0;
            for (int i = 0; i < iter; i++) {
                int[] p = pos[r.nextInt(pos.length)];
                long k = Apoio.chave(p[0], p[1]);
                Chunk atual = ref.get(k);
                if (atual != null) {
                    m.remove(atual);
                    ref.remove(k);
                    removes++;
                    if (m.get(p[0], p[1]) != null) {
                        presos++;
                    }
                } else {
                    Chunk c = new Chunk(p[0], p[1]);
                    m.put(c);
                    ref.put(k, c);
                    if (m.get(p[0], p[1]) != c) {
                        errados++;
                    }
                }
                // mais algumas consultas da thread do servidor, comparadas com a referencia
                for (int j = 0; j < 3; j++) {
                    int[] q = pos[r.nextInt(pos.length)];
                    if (m.get(q[0], q[1]) != ref.get(Apoio.chave(q[0], q[1]))) {
                        if (ref.get(Apoio.chave(q[0], q[1])) == null) {
                            presos++;
                        } else {
                            errados++;
                        }
                    }
                }
            }
            parar.set(true);
            for (Thread t : leitores) {
                t.join();
            }
            // varredura final
            long finalErrado = 0;
            for (int[] p : pos) {
                if (m.get(p[0], p[1]) != ref.get(Apoio.chave(p[0], p[1]))) {
                    finalErrado++;
                }
            }
            String nome = modelo ? "(controle) modelo E1 SEM protecao" : "classe nova";
            System.out.println("  " + nome + ": " + iter + " iteracoes, " + removes + " descargas, " + leituras.get()
                    + " leituras de outras threads; presos " + presos + ", errados " + errados + ", varredura final errada "
                    + finalErrado + ", coordenadas erradas nos leitores " + coordErradas.get() + ", excecoes nos leitores "
                    + excecoesLeitor.get());
            if (!modelo) {
                v.igual(0L, presos, "estresse: chunk preso (thread do servidor viu chunk descarregado)");
                v.igual(0L, errados, "estresse: resultado errado na thread do servidor");
                v.igual(0L, finalErrado, "estresse: varredura final contra a referencia");
                v.igual(0L, coordErradas.get(), "estresse: leitor de fora nunca recebe chunk de outra posicao");
                v.sim(leituras.get() > iter, "estresse: os leitores de fora rodaram de verdade (" + leituras.get() + " leituras)");
                v.sim(Apoio.cacheAtivo(m.novo), "estresse: o cache continua ativo (leitores nao o desligam)");
                long incoerente = 0;
                Chunk[] s = Apoio.slots(m.novo);
                @SuppressWarnings("unchecked")
                LongObjMap<Chunk[][]> raw = m.novo.raw();
                for (Chunk c : s) {
                    if (c != null && Apoio.estrutura(raw, c.xPosition, c.zPosition) != c) {
                        incoerente++;
                    }
                }
                v.igual(0L, incoerente, "estresse: toda casa da tabela tem null ou o chunk que o mapa guarda");
            } else {
                System.out.println("  (controle) o mesmo estresse no modelo sem protecao achou " + (presos + finalErrado)
                        + " chunk(s) preso(s) (numero depende do agendador; o replay deterministico acima e a prova)");
            }
        }
    }

    // ------------------------------------------------------------------ 3. put/remove de outra thread

    static void mudancaDeFora(Verifica v) throws Exception {
        int antes = desligados();
        final thermos.wrapper.ChunkBlockHashMap m = new thermos.wrapper.ChunkBlockHashMap();
        final Map<Long, Chunk> ref = new HashMap<Long, Chunk>();
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                Chunk c = new Chunk(x, z);
                m.put(c);
                ref.put(Apoio.chave(x, z), c);
                m.get(x, z);
            }
        }
        final Chunk alvo = ref.get(Apoio.chave(2, 3));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                m.remove(alvo);
            }
        }, "descarregador-de-fora");
        t.start();
        t.join();
        ref.remove(Apoio.chave(2, 3));
        v.sim(!Apoio.cacheAtivo(m), "remove de outra thread: cache deste mapa desligado");
        v.igual(antes + 1, desligados(), "remove de outra thread: contado no status");
        v.sim(m.get(2, 3) == null, "remove de outra thread: get devolve null (nao o chunk da tabela)");
        boolean certo = true;
        Random r = new Random(5);
        for (int i = 0; i < 200000; i++) {
            int x = r.nextInt(11) - 5;
            int z = r.nextInt(11) - 5;
            long k = Apoio.chave(x, z);
            int op = r.nextInt(10);
            if (op == 0) {
                Chunk c = ref.remove(k);
                if (c != null) {
                    m.remove(c);
                }
            } else if (op == 1 && !ref.containsKey(k)) {
                Chunk c = new Chunk(x, z);
                m.put(c);
                ref.put(k, c);
            } else if (m.get(x, z) != ref.get(k)) {
                certo = false;
            }
        }
        v.sim(certo, "depois de desligado: 200000 operacoes com o codigo original, todas certas");

        // put de outra thread tambem desliga
        final thermos.wrapper.ChunkBlockHashMap m2 = new thermos.wrapper.ChunkBlockHashMap();
        m2.put(new Chunk(1, 1));
        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                m2.put(new Chunk(2, 2));
                m2.put(null);
            }
        }, "carregador-de-fora");
        t2.start();
        t2.join();
        v.sim(!Apoio.cacheAtivo(m2) && m2.get(2, 2) != null && m2.get(1, 1) != null, "put de outra thread: cache desligado, resultados certos");
        // put(null) de outra thread NAO desliga (nao muda nada)
        final thermos.wrapper.ChunkBlockHashMap m3 = new thermos.wrapper.ChunkBlockHashMap();
        Thread t3 = new Thread(new Runnable() {
            @Override
            public void run() {
                m3.put(null);
            }
        });
        t3.start();
        t3.join();
        v.sim(Apoio.cacheAtivo(m3), "put(null) de outra thread: cache continua ativo");
    }

    static int desligados() {
        String s = ChunkMap.linhaDeEstado();
        int i = s.indexOf("desligados por seguranca: ") + "desligados por seguranca: ".length();
        int j = i;
        while (j < s.length() && Character.isDigit(s.charAt(j))) {
            j++;
        }
        return Integer.parseInt(s.substring(i, j));
    }

    // ------------------------------------------------------------------ 4. erro dentro do cache

    static void erroInterno(Verifica v) {
        thermos.wrapper.ChunkBlockHashMap m = new thermos.wrapper.ChunkBlockHashMap();
        Chunk c = new Chunk(3, -4);
        m.put(c);
        Apoio.definirCampo(Apoio.cache(m), "slots", null); // forca NullPointerException dentro do cache
        Chunk visto = m.get(3, -4);
        v.sim(visto == c, "erro no cache: a consulta ainda devolve o certo");
        v.sim(!Apoio.cacheAtivo(m), "erro no cache: cache desligado");
        m.remove(c);
        v.sim(m.get(3, -4) == null, "erro no cache: depois, remove/get pelo codigo original, certos");
        v.sim(ChunkMap.linhaDeEstado().contains("desligados por seguranca"), "erro no cache: aparece no status");
    }

    // ------------------------------------------------------------------ 5. modo conferir

    static void conferir(Verifica v) {
        ChunkMap.configurarParaTeste(true, 6, true); // vale para mapas novos
        thermos.wrapper.ChunkBlockHashMap m = new thermos.wrapper.ChunkBlockHashMap();
        Chunk c = new Chunk(10, 20);
        m.put(c);
        v.sim(m.get(10, 20) == c, "conferir: acerto normal");
        Chunk falso = new Chunk(10, 20); // mesmas coordenadas, mas o mapa nao o guarda
        Apoio.slots(m)[(10 & 63) | ((20 & 63) << 6)] = falso;
        Chunk visto = m.get(10, 20);
        v.sim(visto == c, "conferir: acerto errado posto a forca e corrigido (devolve o do mapa)");
        v.sim(ChunkMap.linhaDeEstado().contains("divergencias 1"), "conferir: divergencia contada (" + ChunkMap.linhaDeEstado() + ")");
        v.sim(Apoio.slots(m)[(10 & 63) | ((20 & 63) << 6)] == c, "conferir: a casa da tabela foi corrigida");
        ChunkMap.configurarParaTeste(true, 6, false);
    }

    // ------------------------------------------------------------------ 6. sem thread do servidor

    static void semThread(Verifica v) {
        Thread dono = ThreadServidor.thread();
        ThreadServidor.definir(null);
        thermos.wrapper.ChunkBlockHashMap m = new thermos.wrapper.ChunkBlockHashMap();
        ThreadServidor.definir(dono);
        v.sim(Apoio.cache(m) == null, "mapa criado sem a thread do servidor: sem cache (codigo original)");
        Chunk c = new Chunk(1, 2);
        m.put(c);
        v.sim(m.get(1, 2) == c, "mapa sem cache funciona");
        v.sim(ChunkMap.linhaDeEstado().contains("mapas criados antes da thread do servidor (sem cache): 1"), "aparece no status");
    }

    // ------------------------------------------------------------------ apoio

    /** Uma interface minima para rodar o mesmo estresse na classe nova e no modelo. */
    abstract static class Mapa {
        thermos.wrapper.ChunkBlockHashMap novo;

        abstract Chunk get(int x, int z);

        abstract void put(Chunk c);

        abstract void remove(Chunk c);

        static Mapa novo() {
            final thermos.wrapper.ChunkBlockHashMap n = new thermos.wrapper.ChunkBlockHashMap();
            Mapa m = new Mapa() {
                @Override
                Chunk get(int x, int z) {
                    return n.get(x, z);
                }

                @Override
                void put(Chunk c) {
                    n.put(c);
                }

                @Override
                void remove(Chunk c) {
                    n.remove(c);
                }
            };
            m.novo = n;
            return m;
        }

        static Mapa modelo(final ModeloE1 e) {
            return new Mapa() {
                @Override
                Chunk get(int x, int z) {
                    return e.get(x, z);
                }

                @Override
                void put(Chunk c) {
                    e.put(c);
                }

                @Override
                void remove(Chunk c) {
                    e.remove(c);
                }
            };
        }
    }

    /**
     * O modelo do conferente (RaceDemo.M): o desenho E1 sobre a classe ORIGINAL, com a gravacao da
     * casa separada da busca. {@code protegido=false} = o desenho antes da correcao (qualquer thread
     * grava); true = so a thread dona grava.
     */
    static final class ModeloE1 extends hoshiestar.teste.chunkmap.original.ChunkBlockHashMap {
        final Chunk[] slots = new Chunk[4096];
        final Thread dono;
        final boolean protegido;

        ModeloE1(Thread dono, boolean protegido) {
            this.dono = dono;
            this.protegido = protegido;
        }

        static int idx(int x, int z) {
            return (x & 63) | ((z & 63) << 6);
        }

        @SuppressWarnings("unchecked")
        Chunk buscar(int x, int z) {
            return Apoio.estrutura(raw(), x, z);
        }

        void preencher(int x, int z, Chunk c) {
            if (c != null && (!protegido || Thread.currentThread() == dono)) {
                slots[idx(x, z)] = c;
            }
        }

        @Override
        public Chunk get(int x, int z) {
            Chunk c = slots[idx(x, z)];
            if (c != null && c.xPosition == x && c.zPosition == z) {
                return c;
            }
            c = buscar(x, z);
            preencher(x, z, c);
            return c;
        }

        @Override
        public void put(Chunk c) {
            super.put(c);
            if (c != null) {
                slots[idx(c.xPosition, c.zPosition)] = c;
            }
        }

        @Override
        public void remove(Chunk c) {
            super.remove(c);
            int i = idx(c.xPosition, c.zPosition);
            Chunk o = slots[i];
            if (o != null && o.xPosition == c.xPosition && o.zPosition == c.zPosition) {
                slots[i] = null;
            }
        }
    }
}
