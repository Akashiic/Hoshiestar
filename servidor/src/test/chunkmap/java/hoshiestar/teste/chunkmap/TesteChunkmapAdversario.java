package hoshiestar.teste.chunkmap;

import com.koloboke.collect.map.LongObjMap;
import hoshiestar.api.ThreadServidor;
import hoshiestar.chunkmap.ChunkMap;
import hoshiestar.teste.Verifica;
import net.minecraft.world.chunk.Chunk;
import thermos.wrapper.ChunkBlockHashMap;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Testes adversarios trazidos da revisao independente (fork/rev/chunkmap, T1, T2, T5, T7), com o
 * resultado que a correcao exige. Interruptor LIGADO; a thread "main" faz o papel da thread do
 * servidor. Uso: {@code TesteChunkmapAdversario [rodadas do teste de estouro de pilha]}. O script roda
 * esta classe tambem com {@code -XX:-TieredCompilation}, o modo em que o revisor achou o defeito I1.
 *
 * <ol>
 * <li>T1 (achado I2): depois de um desligamento por seguranca, os chunks descarregados saem da
 * memoria (a tabela morta e solta na chamada seguinte).</li>
 * <li>T5 (achado M1): put duplo + remove, depois desligamento: o codigo original recomeca com o
 * cache de 4 entradas vazio e devolve o que o mapa guarda (null), como a classe original.</li>
 * <li>T7 (achado M3): um leitor de outra thread nunca le a tabela (casa envenenada a forca: o leitor
 * de fora recebe o que o mapa guarda).</li>
 * <li>T2 (achados I1 e M2): estouro de pilha em cada profundidade possivel dentro de put, remove e
 * get. Depois do estouro: cache ativo nunca tem casa diferente do mapa, get nunca devolve chunk fora
 * do mapa, e todo cache desligado pelo estouro aparece no contador do status.</li>
 * <li>T8 (achado C1 da revisao de concorrencia): com o cache ativo o cache de 4 entradas fica
 * vazio, e soltar o cache a partir de outra thread (que leu o campo hoshieCache antigo) nao grava
 * nada no cache de 4 entradas que a thread do servidor ja voltou a usar no codigo original.</li>
 * </ol>
 */
public final class TesteChunkmapAdversario {

    public static void main(String[] args) throws Exception {
        int rodadas = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        ChunkMap.configurarParaTeste(true, 6, false);
        ThreadServidor.definir(Thread.currentThread());
        Verifica v = new Verifica("TesteChunkmapAdversario (rodadas do estouro de pilha: " + rodadas + ")");
        vazamento(v);
        mruDepoisDeDesligar(v);
        leitorDeForaNaoLeTabela(v);
        soltarDeForaNaoMexeNoMru(v);
        estouroDePilha(v, rodadas);
        System.out.println("  status: " + ChunkMap.linhaDeEstado());
        v.fim();
    }

    static Chunk estrutura(ChunkBlockHashMap m, int x, int z) {
        @SuppressWarnings("unchecked")
        LongObjMap<Chunk[][]> raw = m.raw();
        return Apoio.estrutura(raw, x, z);
    }

    static void emOutraThread(Runnable r) throws Exception {
        Thread t = new Thread(r, "fora");
        t.start();
        t.join();
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

    static boolean ativo(ChunkBlockHashMap m) {
        Object c = Apoio.cache(m);
        return c != null && (Boolean) Apoio.campo(c, "ativo");
    }

    // ------------------------------------------------------------------ T1: retencao (I2)

    static void vazamento(Verifica v) throws Exception {
        for (int modo = 0; modo < 3; modo++) {
            final ChunkBlockHashMap m = new ChunkBlockHashMap();
            List<Chunk> fortes = new ArrayList<Chunk>();
            List<WeakReference<Chunk>> fracos = new ArrayList<WeakReference<Chunk>>();
            for (int x = 0; x < 60; x++) {
                for (int z = 0; z < 50; z++) {
                    Chunk c = new Chunk(x, z);
                    m.put(c);
                    fortes.add(c);
                    fracos.add(new WeakReference<Chunk>(c));
                }
            }
            for (Chunk c : fortes) {
                m.get(c.xPosition, c.zPosition); // enche a tabela
            }
            String nome;
            if (modo == 0) {
                nome = "cache ativo (controle)";
            } else if (modo == 1) {
                nome = "desligado por put de outra thread";
                emOutraThread(new Runnable() {
                    @Override
                    public void run() {
                        m.put(new Chunk(1000, 1000));
                    }
                });
            } else {
                nome = "desligado por erro dentro da consulta";
                Object cache = Apoio.cache(m);
                Apoio.definirCampo(cache, "mascara", 0x7fffffff); // ArrayIndexOutOfBounds dentro do cache
                m.get(100000, 100000);
            }
            for (Chunk c : fortes) {
                m.remove(c); // descarrega tudo (thread do servidor)
            }
            boolean solto = modo == 0 || Apoio.cache(m) == null;
            fortes.clear();
            for (int i = 0; i < 6; i++) {
                System.gc();
                Thread.sleep(50);
            }
            int vivos = 0;
            for (WeakReference<Chunk> w : fracos) {
                if (w.get() != null) {
                    vivos++;
                }
            }
            v.sim(solto, "T1 [" + nome + "]: cache solto depois do desligamento");
            v.sim(vivos == 0, "T1 [" + nome + "]: chunks descarregados que continuam na memoria: " + vivos + " de 3000");
        }
    }

    // ------------------------------------------------------------------ T5: MRU depois do desligamento (M1)

    static void mruDepoisDeDesligar(Verifica v) throws Exception {
        final ChunkBlockHashMap n = new ChunkBlockHashMap();
        hoshiestar.teste.chunkmap.original.ChunkBlockHashMap o = new hoshiestar.teste.chunkmap.original.ChunkBlockHashMap();
        for (int i = 0; i < 4; i++) {
            n.put(new Chunk(100 + i, 0));
            o.put(new Chunk(100 + i, 0));
        }
        Chunk a1 = new Chunk(5, 7);
        Chunk b1 = new Chunk(5, 7);
        Chunk a2 = new Chunk(5, 7);
        Chunk b2 = new Chunk(5, 7);
        n.put(a1);
        n.put(b1);
        n.remove(b1);
        o.put(a2);
        o.put(b2);
        o.remove(b2);
        for (int r = 0; r < 3; r++) {
            for (int i = 0; i < 4; i++) {
                n.get(100 + i, 0);
                o.get(100 + i, 0);
            }
        }
        emOutraThread(new Runnable() {
            @Override
            public void run() {
                n.remove(new Chunk(9999, 9999)); // desliga por seguranca
            }
        });
        Chunk depois = n.get(5, 7);
        v.sim(depois == null && estrutura(n, 5, 7) == null, "T5: depois do desligamento get(5,7) devolve o que o mapa guarda (null); visto " + depois);
        v.sim(o.get(5, 7) == null, "T5: a original, mesma sequencia, tambem devolve null");
        v.sim(Apoio.cache(n) == null, "T5: cache solto");
    }

    // ------------------------------------------------------------------ T8: soltar de fora nao mexe no MRU (C1)

    static boolean mruVazio(ChunkBlockHashMap m) {
        return Apoio.campo(m, "last1") == null && Apoio.campo(m, "last2") == null
                && Apoio.campo(m, "last3") == null && Apoio.campo(m, "last4") == null;
    }

    static void soltarDeForaNaoMexeNoMru(Verifica v) throws Exception {
        final ChunkBlockHashMap n = new ChunkBlockHashMap();
        Chunk[] cs = new Chunk[6];
        for (int i = 0; i < cs.length; i++) {
            cs[i] = new Chunk(200 + i, 3);
            n.put(cs[i]);
        }
        v.sim(ativo(n) && mruVazio(n), "T8: cache ativo e cache de 4 entradas vazio depois de 6 puts da thread do servidor");
        for (int i = 0; i < cs.length; i++) {
            n.get(200 + i, 3);
        }
        n.remove(cs[5]);
        v.sim(ativo(n) && mruVazio(n), "T8: continua vazio depois de gets e de um remove da thread do servidor");
        final Object velho = Apoio.cache(n); // o valor que uma thread de fora leu antes do desligamento
        emOutraThread(new Runnable() {
            @Override
            public void run() {
                n.remove(new Chunk(9999, 9999)); // desliga por seguranca
            }
        });
        Chunk primeiro = n.get(200, 3); // thread do servidor: solta e usa o codigo original (enche last1)
        v.sim(primeiro == cs[0] && Apoio.cache(n) == null, "T8: thread do servidor soltou o cache e achou o chunk pelo codigo original");
        v.sim(Apoio.campo(n, "last1") == cs[0], "T8: o codigo original guardou o chunk em last1");
        final java.lang.reflect.Method soltar = ChunkBlockHashMap.class.getDeclaredMethod("hoshieSoltar", velho.getClass());
        soltar.setAccessible(true);
        final Throwable[] erro = new Throwable[1];
        emOutraThread(new Runnable() {
            @Override
            public void run() {
                try {
                    soltar.invoke(n, velho); // a thread de fora que viu hoshieCache antigo solta de novo
                } catch (Throwable t) {
                    erro[0] = t;
                }
            }
        });
        v.sim(erro[0] == null, "T8: soltar de outra thread nao lanca; visto " + erro[0]);
        v.sim(Apoio.campo(n, "last1") == cs[0], "T8: soltar de outra thread nao apagou last1 (antes da correcao: null)");
        v.sim(n.get(200, 3) == cs[0] && n.get(205, 3) == null, "T8: depois disso get segue certo (chunk carregado e chunk removido)");
    }

    // ------------------------------------------------------------------ T7: leitor de fora nao le a tabela (M3)

    static void leitorDeForaNaoLeTabela(Verifica v) throws Exception {
        final ChunkBlockHashMap m = new ChunkBlockHashMap();
        final Chunk um = new Chunk(1, 1);
        m.put(um);
        Chunk veneno = new Chunk(5, 7); // nunca entrou no mapa
        Apoio.slots(m)[(5 & 63) | ((7 & 63) << 6)] = veneno;
        final Chunk[] visto = new Chunk[2];
        emOutraThread(new Runnable() {
            @Override
            public void run() {
                visto[0] = m.get(5, 7);
                visto[1] = m.get(1, 1);
            }
        });
        v.sim(visto[0] == null, "T7: leitor de fora com casa envenenada recebe o que o mapa guarda (null)");
        v.sim(visto[1] == um, "T7: leitor de fora acha o chunk que esta no mapa");
        v.sim(m.get(5, 7) == veneno, "T7: controle: a casa estava mesmo envenenada (a thread do servidor ve o veneno)");
        v.sim(ativo(m), "T7: leitura de fora nao desliga o cache");
    }

    // ------------------------------------------------------------------ T2: estouro de pilha (I1, M2)

    interface Op {
        void run();
    }

    static void descer(int n, Op r) {
        if (n == 0) {
            r.run();
        } else {
            descer(n - 1, r);
        }
    }

    static void descer2(int n, Op r, long p1, long p2, long p3) {
        if (n == 0) {
            r.run();
        } else {
            descer2(n - 1, r, p1 + 1, p2, p3);
        }
    }

    static int maxProf(Op noop) {
        int lo = 100;
        int hi = 2000000;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            try {
                descer(mid, noop);
                lo = mid;
            } catch (StackOverflowError e) {
                hi = mid;
            }
        }
        return lo;
    }

    static void estouroDePilha(Verifica v, final int rodadas) throws Exception {
        final long[] c = new long[10];
        final Throwable[] erro = new Throwable[1];
        Thread t = new Thread(null, new Runnable() {
            @Override
            public void run() {
                try {
                    estouroCorpo(c, rodadas);
                } catch (Throwable e) {
                    erro[0] = e;
                }
            }
        }, "Server thread (teste)", 512 * 1024);
        ThreadServidor.definir(t);
        t.start();
        t.join();
        ThreadServidor.definir(Thread.currentThread());
        System.out.println("  T2: tentativas " + c[0] + "; estouros em remove " + c[1] + ", put " + c[2] + ", get " + c[3]
                + "; caches desligados pelo estouro " + c[6] + "; ativos depois do estouro " + c[9]);
        v.sim(erro[0] == null, "T2: o teste rodou sem erro inesperado " + erro[0]);
        v.sim(c[1] > 0 && c[2] > 0 && c[3] > 0, "T2: houve estouro de pilha dentro de remove, put e get");
        v.sim(c[4] == 0, "T2: cache ativo com casa diferente do mapa depois do estouro: " + c[4]);
        v.sim(c[5] == 0, "T2: get devolvendo chunk fora do mapa depois do estouro: " + c[5]);
        v.sim(c[7] == 0, "T2: caches desligados pelo estouro que nao entraram no status: " + c[7]);
        v.sim(c[8] == 0, "T2: caches desligados que nao foram soltos na chamada seguinte: " + c[8]);
    }

    static void estouroCorpo(long[] cont, int rodadas) {
        Op noop = new Op() {
            @Override
            public void run() {
            }
        };
        for (int rodada = 0; rodada < rodadas; rodada++) { // rodadas: o JIT muda o tamanho dos quadros
            int max = maxProf(noop);
            for (int tipo = 0; tipo < 3; tipo++) {
                for (int d = max - 400; d <= max + 2; d++) {
                    for (int forma = 0; forma < 2; forma++) {
                        final ChunkBlockHashMap m = new ChunkBlockHashMap();
                        final Chunk a = new Chunk(5, 7);
                        final Chunk b = new Chunk(5, 7);
                        m.put(a);
                        m.put(new Chunk(6, 7));
                        m.get(5, 7);
                        int antes = desligados();
                        Op op;
                        if (tipo == 0) {
                            op = new Op() {
                                @Override
                                public void run() {
                                    m.remove(a);
                                }
                            };
                        } else if (tipo == 1) {
                            op = new Op() {
                                @Override
                                public void run() {
                                    m.put(b);
                                }
                            };
                        } else {
                            m.remove(a);
                            m.put(new Chunk(5 + 64, 7));
                            m.put(a);
                            m.put(new Chunk(5 + 64, 7 + 64)); // casa de (5,7) com outro: get erra e grava
                            op = new Op() {
                                @Override
                                public void run() {
                                    m.get(5, 7);
                                }
                            };
                        }
                        cont[0]++;
                        boolean estourou = false;
                        try {
                            if (forma == 0) {
                                descer(d, op);
                            } else {
                                descer2(d * 2 / 3, op, 1, 2, 3);
                            }
                        } catch (StackOverflowError e) {
                            estourou = true;
                        }
                        if (!estourou) {
                            continue;
                        }
                        cont[1 + tipo]++;
                        boolean at = ativo(m);
                        if (at) {
                            cont[9]++;
                            for (Chunk s : Apoio.slots(m)) {
                                if (s != null && estrutura(m, s.xPosition, s.zPosition) != s) {
                                    cont[4]++;
                                    break;
                                }
                            }
                        }
                        Chunk g = m.get(5, 7);
                        if (g != estrutura(m, 5, 7)) {
                            cont[5]++;
                        }
                        if (!at) {
                            cont[6]++;
                            if (desligados() == antes) {
                                cont[7]++;
                            }
                            if (Apoio.cache(m) != null) {
                                cont[8]++;
                            }
                        }
                    }
                }
            }
        }
    }
}
