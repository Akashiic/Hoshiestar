package hoshiestar.teste.chunkmap;

import hoshiestar.api.ThreadServidor;
import hoshiestar.chunkmap.ChunkMap;
import hoshiestar.teste.Verifica;
import net.minecraft.world.chunk.Chunk;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Correcao H01 do cache de 4 entradas ({@code last1..last4}) do codigo ORIGINAL do
 * {@code thermos.wrapper.ChunkBlockHashMap}, com o chunkmap (cache novo) DESLIGADO, e o contador de versao
 * do mapa. A thread "main" faz o papel da thread do servidor.
 *
 * <p>Uso: {@code TesteChunkmapCache4 <ligado|desligado> [iteracoes]}. A chave chunkmap.cache4Seguro e um
 * {@code static final} lido quando a classe carrega, entao cada modo roda numa JVM.
 *
 * <p>O que vale nos DOIS modos (partes sem interruptor):
 * <ul>
 * <li>parte 1: N threads lendo enquanto a thread do servidor mexe no cache nunca recebem chunk de outra
 * coordenada (nem excecao);</li>
 * <li>a thread do servidor, numa consulta, deixa o cache exatamente como a classe original deixaria, em
 * todos os 1296 estados possiveis das 4 casas;</li>
 * <li>contador de versao.</li>
 * </ul>
 * So com a chave LIGADA (partes 2 e 3):
 * <ul>
 * <li>consulta de thread de fora nunca muda {@code last1..last4};</li>
 * <li>{@code remove} limpa TODAS as casas com a coordenada (caso [F, A, A, C] e os 1296 estados), e sem casa
 * repetida deixa o cache igual ao da classe original;</li>
 * <li>depois de {@code remove(c)} nenhum {@code get} devolve {@code c}: com N threads lendo antes, e com N
 * threads lendo durante (a conferencia so vale quando a leitura inteira caiu depois do fim do remove);</li>
 * <li>thread de fora no caminho do MAPA (achado I1 da revisao do passo 1): nunca chunk de outra coordenada, nem com
 * o bloco trocado a mao, nem com a thread do servidor fazendo a tabela Koloboke crescer. O null para chunk
 * carregado nessa janela e limite conhecido: so informado.</li>
 * </ul>
 * Com a chave DESLIGADA, os mesmos cenarios rodam como CONTROLE: o cache tem de ficar identico ao da classe
 * original em todos os estados (inclusive o defeito do [F, A, A, C]), e o numero de vezes em que o defeito
 * de concorrencia aparece e so informado (depende de sorte; nao e falha nem prova).
 *
 * <p>Cuidado com o JIT (D-63): nenhuma conferencia depende de tempo, de tamanho de quadro ou de qual
 * compilador pegou o metodo. As conferencias com threads so comparam o que o modelo de memoria do Java
 * garante (ha um campo volatile escrito depois da mudanca e lido antes da leitura conferida). O script roda
 * esta classe no modo padrao, com {@code -XX:-TieredCompilation} e com {@code -Xint}.
 */
public final class TesteChunkmapCache4 {

    /** O que os cenarios usam do mapa; uma implementacao para a classe do fork e outra para a copia da original. */
    interface Mapa {
        Chunk get(int x, int z);

        void put(Chunk c);

        void remove(Chunk c);

        Object alvo();
    }

    static Mapa novo() {
        final thermos.wrapper.ChunkBlockHashMap m = new thermos.wrapper.ChunkBlockHashMap();
        return new Mapa() {
            public Chunk get(int x, int z) {
                return m.get(x, z);
            }

            public void put(Chunk c) {
                m.put(c);
            }

            public void remove(Chunk c) {
                m.remove(c);
            }

            public Object alvo() {
                return m;
            }
        };
    }

    static Mapa original() {
        final hoshiestar.teste.chunkmap.original.ChunkBlockHashMap m = new hoshiestar.teste.chunkmap.original.ChunkBlockHashMap();
        return new Mapa() {
            public Chunk get(int x, int z) {
                return m.get(x, z);
            }

            public void put(Chunk c) {
                m.put(c);
            }

            public void remove(Chunk c) {
                m.remove(c);
            }

            public Object alvo() {
                return m;
            }
        };
    }

    static final String[] CASAS = {"last1", "last2", "last3", "last4"};
    static final int LEITORES = 3;

    static boolean ligado;

    public static void main(String[] args) throws Exception {
        String modo = args.length > 0 ? args[0] : "ligado";
        int iter = args.length > 1 ? Integer.parseInt(args[1]) : 3000000;
        ligado = modo.equals("ligado");
        ChunkMap.configurarParaTeste(false, 6, false); // chunkmap (cache novo) desligado: o mapa usa o codigo original
        ChunkMap.configurarCache4ParaTeste(ligado);
        ThreadServidor.definir(Thread.currentThread());
        Verifica v = new Verifica("TesteChunkmapCache4 modo=" + modo + " iteracoes=" + iter);

        Field f = thermos.wrapper.ChunkBlockHashMap.class.getDeclaredField("HOSHIE_CACHE4");
        v.sim(Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()), "a chave e um campo static final do patch");
        v.igual(ligado, Apoio.estatico(thermos.wrapper.ChunkBlockHashMap.class, "HOSHIE_CACHE4"), "chave cache4Seguro lida pelo patch");
        v.igual(false, Apoio.estatico(thermos.wrapper.ChunkBlockHashMap.class, "HOSHIE_CHUNKMAP"), "chunkmap (cache novo) desligado neste teste");

        casoFAAC(v);
        todosOsEstados(v);
        threadDeFora(v);
        versao(v);
        leitoresComCacheMexendo(v, iter);
        removeComLeitoresAntes(v, Math.max(200, iter / 3000));
        removeComLeitoresDurante(v, Math.max(2000, iter / 100));
        blocoTrocado(v);
        leitoresComMapaCrescendo(v, Math.max(10, iter / 30000));
        v.fim();
    }

    // ------------------------------------------------------------------ 8. leitor de fora e o caminho do MAPA (revisao do passo 1, achado I1)

    static long chaveDoBloco(int x, int z) {
        return (((long) (x >> 4)) << 32L) ^ (z >> 4);
    }

    /**
     * Sem threads e sem sorte: poe no mapa, na chave do bloco de A, o bloco de 16x16 de B. E o que a consulta ao mapa
     * Koloboke entrega a um leitor de fora quando pega a posicao numa tabela e o valor na outra, durante o crescimento
     * (as duas leituras do get do Koloboke nao tem trava). Chave ligada: a thread de fora confere a coordenada e devolve
     * null, nunca B. Chave desligada (controle): identico a classe original, que devolve B. A thread do servidor nao
     * muda em nenhum dos modos (ela e a unica que escreve: para ela a tabela nunca esta pela metade).
     */
    static void blocoTrocado(Verifica v) throws Exception {
        final Chunk a = new Chunk(3, 5);
        final Chunk b = new Chunk(40 * 16 + 3, 77 * 16 + 5); // mesma casa (3, 5) dentro do bloco, outro bloco
        final Mapa n = novo();
        final Mapa o = original();
        for (Mapa m : new Mapa[]{n, o}) {
            m.put(a);
            m.put(b);
            com.koloboke.collect.map.LongObjMap<Chunk[][]> raw = m.alvo() instanceof thermos.wrapper.ChunkBlockHashMap
                    ? ((thermos.wrapper.ChunkBlockHashMap) m.alvo()).raw()
                    : ((hoshiestar.teste.chunkmap.original.ChunkBlockHashMap) m.alvo()).raw();
            raw.put(chaveDoBloco(3, 5), raw.get(chaveDoBloco(b.xPosition, b.zPosition)));
            definirCasas(m, new Chunk[4]); // cache de 4 vazio: a consulta vai ao mapa
        }
        final Chunk[] visto = new Chunk[2];
        emOutraThread(new Runnable() {
            @Override
            public void run() {
                visto[0] = n.get(3, 5);
                visto[1] = o.get(3, 5);
            }
        });
        v.sim(visto[1] == b, "bloco trocado, classe ORIGINAL, thread de fora: devolve o chunk de outra coordenada (" + visto[1] + " para o pedido (3, 5))");
        if (ligado) {
            v.sim(visto[0] == null, "bloco trocado, chave ligada, thread de fora: NUNCA chunk de outra coordenada (null no lugar); veio " + visto[0]);
            v.sim(mesmas(casas(n), new Chunk[4]), "bloco trocado, chave ligada: a consulta de fora nao gravou o cache");
            final Chunk[] certo = new Chunk[1];
            emOutraThread(new Runnable() {
                @Override
                public void run() {
                    certo[0] = n.get(b.xPosition, b.zPosition);
                }
            });
            v.sim(certo[0] == b, "chave ligada, thread de fora: o chunk da coordenada pedida continua sendo devolvido pelo caminho do mapa");
        } else {
            v.sim(visto[0] == b, "bloco trocado, chave desligada (controle), thread de fora: identico a original; veio " + visto[0]);
        }
        definirCasas(n, new Chunk[4]);
        definirCasas(o, new Chunk[4]);
        Chunk rn = n.get(3, 5);
        Chunk ro = o.get(3, 5);
        v.sim(rn == ro && rn == b && mesmas(casas(n), casas(o)), "bloco trocado, thread do servidor: resultado e cache identicos a original nos dois modos (o caminho dela nao mudou)");
    }

    static volatile Object[] rodadaAtual; // {Mapa, Chunk[]} publicados juntos
    static volatile boolean pararCrescendo;

    /**
     * Com threads: a thread do servidor (a unica que escreve) carrega chunks NOVOS, cada um num bloco de 16x16 novo, e a
     * tabela do mapa Koloboke cresce varias vezes por mapa; leitores de fora consultam chunks que nunca mudam (dois
     * repetem sempre os mesmos 3, um passeia pelos 100).
     *
     * <p>Chave ligada: NENHUMA consulta devolve chunk de outra coordenada (falha se devolver). LIMITE CONHECIDO, so
     * informado: null para chunk carregado (a tabela nova ja esta publicada e ainda vazia) e excecao na consulta
     * (nunca vista; o modelo de memoria nao a exclui). Chave desligada e classe original: tudo so informado.
     */
    static void leitoresComMapaCrescendo(Verifica v, int mapas) throws Exception {
        long[] r = crescendo(false, mapas);
        String resumo = mapas + " mapas, " + r[4] + " puts de bloco novo pela thread do servidor, " + r[0] + " consultas de " + LEITORES
                + " leitores de fora a chunks que nao mudam: chunk de OUTRA coordenada " + r[1] + "; LIMITE CONHECIDO: null para chunk carregado "
                + r[2] + ", excecoes " + r[3];
        if (ligado) {
            v.sim(r[0] > 0, "mapa crescendo: os leitores de fora consultaram (" + r[0] + ")");
            v.igual(0L, r[1], "chave ligada, mapa crescendo: " + resumo);
        } else {
            System.out.println("  controle (chave desligada): " + resumo + " (informativo)");
        }
        long[] o = crescendo(true, mapas);
        System.out.println("  controle (classe ORIGINAL, mesma carga): " + o[0] + " consultas, chunk de outra coordenada " + o[1] + ", null para chunk carregado "
                + o[2] + ", excecoes " + o[3] + " (informativo)");
    }

    /** {consultas, outra coordenada, null, excecoes, puts}. */
    static long[] crescendo(final boolean original, int mapas) throws Exception {
        final int lado = 10; // 100 chunks estaveis, cada um num bloco de 16x16 diferente
        final AtomicLong consultas = new AtomicLong();
        final AtomicLong outra = new AtomicLong();
        final AtomicLong nulo = new AtomicLong();
        final AtomicLong excecoes = new AtomicLong();
        rodadaAtual = null;
        pararCrescendo = false;
        Thread[] ts = new Thread[LEITORES];
        for (int t = 0; t < ts.length; t++) {
            final int quantos = t < 2 ? 3 : lado * lado;
            final long semente = 0x9E3779B97F4A7C15L * (t + 1);
            ts[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    long s = semente;
                    while (!pararCrescendo) {
                        Object[] rodada = rodadaAtual; // volatile: os chunks estaveis ja estavam no mapa quando ele foi publicado
                        if (rodada == null) {
                            continue;
                        }
                        Mapa m = (Mapa) rodada[0];
                        Chunk[] e = (Chunk[]) rodada[1];
                        long ruim = 0;
                        long nul = 0;
                        long exc = 0;
                        for (int k = 0; k < 4096; k++) {
                            s ^= s << 13;
                            s ^= s >>> 7;
                            s ^= s << 17;
                            Chunk esperado = e[(int) ((s >>> 8) % quantos)];
                            try {
                                Chunk c = m.get(esperado.xPosition, esperado.zPosition);
                                if (c == null) {
                                    nul++;
                                } else if (c != esperado) {
                                    ruim++;
                                }
                            } catch (Throwable ex) {
                                exc++;
                            }
                        }
                        consultas.addAndGet(4096);
                        outra.addAndGet(ruim);
                        nulo.addAndGet(nul);
                        excecoes.addAndGet(exc);
                    }
                }
            }, "leitor-crescendo-" + t);
            ts[t].setDaemon(true);
            ts[t].start();
        }
        long puts = 0;
        for (int r = 0; r < mapas; r++) {
            Mapa m = original ? original() : novo();
            Chunk[] e = new Chunk[lado * lado];
            for (int i = 0; i < lado; i++) {
                for (int j = 0; j < lado; j++) {
                    Chunk c = new Chunk(i * 16 + 3, j * 16 + 5); // todos na casa (3, 5) do seu bloco: bloco trocado = chunk de outra coordenada
                    e[i * lado + j] = c;
                    m.put(c);
                }
            }
            rodadaAtual = new Object[]{m, e}; // daqui em diante os 100 chunks estaveis nunca mudam neste mapa
            for (int k = 0; k < 6000; k++) {
                m.put(new Chunk((7000 + k) * 16 + 3, (9000 + r) * 16 + 5));
                puts++;
            }
        }
        pararCrescendo = true;
        for (Thread t : ts) {
            t.join(20000);
        }
        return new long[]{consultas.get(), outra.get(), nulo.get(), excecoes.get(), puts};
    }

    // ------------------------------------------------------------------ apoio

    static Chunk[] casas(Mapa m) {
        Chunk[] r = new Chunk[4];
        for (int i = 0; i < 4; i++) {
            r[i] = (Chunk) Apoio.campo(m.alvo(), CASAS[i]);
        }
        return r;
    }

    static void definirCasas(Mapa m, Chunk[] c) {
        for (int i = 0; i < 4; i++) {
            Apoio.definirCampo(m.alvo(), CASAS[i], c[i]);
        }
    }

    static boolean mesmas(Chunk[] a, Chunk[] b) {
        for (int i = 0; i < 4; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    static String texto(Chunk[] c) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 4; i++) {
            sb.append(i == 0 ? "" : ", ").append(c[i]);
        }
        return sb.append(']').toString();
    }

    static boolean bate(Chunk c, int x, int z) {
        return c != null && c.xPosition == x && c.zPosition == z;
    }

    static boolean temCoordenada(Chunk[] c, int x, int z) {
        for (int i = 0; i < 4; i++) {
            if (bate(c[i], x, z)) {
                return true;
            }
        }
        return false;
    }

    /** O que a correcao promete: as casas que nao batem com (x, z), na mesma ordem, juntas no comeco. */
    static Chunk[] semCoordenada(Chunk[] c, int x, int z) {
        Chunk[] r = new Chunk[4];
        int n = 0;
        for (int i = 0; i < 4; i++) {
            if (c[i] != null && !bate(c[i], x, z)) {
                r[n++] = c[i];
            }
        }
        return r;
    }

    /** Estado que o codigo original (numa thread, sem put duplo) consegue produzir: casas juntas no comeco e nenhuma coordenada repetida. */
    static boolean estadoNormal(Chunk[] c) {
        boolean vazio = false;
        for (int i = 0; i < 4; i++) {
            if (c[i] == null) {
                vazio = true;
            } else {
                if (vazio) {
                    return false;
                }
                for (int j = 0; j < i; j++) {
                    if (bate(c[j], c[i].xPosition, c[i].zPosition)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    static void emOutraThread(Runnable r) throws Exception {
        Thread t = new Thread(r, "fora");
        t.start();
        t.join();
    }

    static long xorshift(long s) {
        s ^= s << 13;
        s ^= s >>> 7;
        s ^= s << 17;
        return s;
    }

    // ------------------------------------------------------------------ 1. o caso do plano: [F, A, A, C]

    static void casoFAAC(Verifica v) {
        Chunk fC = new Chunk(1, 1);
        Chunk a = new Chunk(2, 2);
        Chunk c = new Chunk(3, 3);
        Mapa n = novo();
        Mapa o = original();
        for (Mapa m : new Mapa[]{n, o}) {
            m.put(fC);
            m.put(a);
            m.put(c);
            definirCasas(m, new Chunk[]{fC, a, a, c});
            m.remove(a);
        }
        Chunk[] cn = casas(n);
        Chunk[] co = casas(o);
        v.sim(co[1] == a && o.get(2, 2) == a, "[F, A, A, C] + remove(A) na classe ORIGINAL: A continua no cache " + texto(co)
                + " e get devolve o chunk ja tirado do mapa (o defeito existe)");
        if (ligado) {
            v.sim(mesmas(cn, new Chunk[]{fC, c, null, null}), "[F, A, A, C] + remove(A), chave ligada: cache fica [F, C, null, null]; visto " + texto(cn));
            v.sim(n.get(2, 2) == null, "[F, A, A, C] + remove(A), chave ligada: get(A) devolve null (o que o mapa guarda)");
            v.sim(n.get(1, 1) == fC && n.get(3, 3) == c, "[F, A, A, C] + remove(A), chave ligada: F e C continuam achados");
        } else {
            v.sim(mesmas(cn, co) && n.get(2, 2) == a, "[F, A, A, C] + remove(A), chave desligada: identico a original " + texto(cn));
        }
    }

    // ------------------------------------------------------------------ 2. todos os estados das 4 casas

    /**
     * As 4 casas com cada combinacao de {null, A, A2, X, Y, Z} (A2 = outro objeto na coordenada de A): 1296
     * estados, e em cada um: remove (do objeto A, de A2 e de um objeto novo na mesma coordenada), put (do
     * mesmo A, de um objeto novo na coordenada de A, de uma coordenada nova) e uma consulta com falta feita
     * pela thread do servidor. A classe do fork e a copia da original partem do mesmo estado.
     */
    static void todosOsEstados(Verifica v) {
        Chunk a = new Chunk(-5, 7);
        Chunk a2 = new Chunk(-5, 7);
        Chunk x = new Chunk(10, 10);
        Chunk y = new Chunk(-17, 3);
        Chunk z = new Chunk(0, -1);
        Chunk q = new Chunk(40, -40); // no mapa, nunca nas casas: a consulta dele e sempre falta
        Chunk[] alfabeto = {null, a, a2, x, y, z};
        Mapa n = novo();
        Mapa o = original();
        for (Mapa m : new Mapa[]{n, o}) {
            m.put(a);
            m.put(x);
            m.put(y);
            m.put(z);
            m.put(q);
        }
        long estados = 0;
        long normais = 0;
        long removeErrado = 0;
        long removeDifNormal = 0;
        long removeDifQualquer = 0;
        long putErrado = 0;
        long putDifNormal = 0;
        long putDifQualquer = 0;
        long getDif = 0;
        String primeiro = null;
        for (int i = 0; i < 1296; i++) {
            Chunk[] e = {alfabeto[i % 6], alfabeto[(i / 6) % 6], alfabeto[(i / 36) % 6], alfabeto[(i / 216) % 6]};
            estados++;
            boolean normal = estadoNormal(e);
            if (normal) {
                normais++;
            }
            // --- remove
            Chunk[] alvos = {a, a2, new Chunk(-5, 7)};
            for (Chunk alvo : alvos) {
                for (Mapa m : new Mapa[]{n, o}) {
                    m.put(a); // o mapa volta a guardar A
                    definirCasas(m, e);
                    m.remove(alvo);
                }
                Chunk[] cn = casas(n);
                Chunk[] co = casas(o);
                if (!mesmas(cn, co)) {
                    removeDifQualquer++;
                    if (normal) {
                        removeDifNormal++;
                    }
                }
                // sem casa da coordenada o remove nao grava nada (como a original); com casa, limpa todas e junta o resto
                Chunk[] esperado = temCoordenada(e, -5, 7) ? semCoordenada(e, -5, 7) : e;
                if (ligado && (temCoordenada(cn, -5, 7) || !mesmas(cn, esperado) || n.get(-5, 7) != null)) {
                    removeErrado++;
                    if (primeiro == null) {
                        primeiro = "remove em " + texto(e) + " deixou " + texto(cn);
                    }
                }
            }
            // --- put
            Chunk[] postos = {a, new Chunk(-5, 7), new Chunk(77, 78)};
            for (Chunk p : postos) {
                for (Mapa m : new Mapa[]{n, o}) {
                    definirCasas(m, e);
                    m.put(p);
                }
                Chunk[] cn = casas(n);
                Chunk[] co = casas(o);
                boolean semCasaDaCoordenada = !temCoordenada(e, p.xPosition, p.zPosition);
                if (!mesmas(cn, co)) {
                    putDifQualquer++;
                    if (normal && semCasaDaCoordenada) {
                        putDifNormal++;
                    }
                }
                if (ligado) {
                    Chunk[] resto = semCoordenada(e, p.xPosition, p.zPosition);
                    Chunk[] esperado = {p, resto[0], resto[1], resto[2]};
                    if (!mesmas(cn, esperado) || n.get(p.xPosition, p.zPosition) != p) {
                        putErrado++;
                        if (primeiro == null) {
                            primeiro = "put de " + p + " em " + texto(e) + " deixou " + texto(cn);
                        }
                    }
                }
                for (Mapa m : new Mapa[]{n, o}) {
                    m.remove(p);
                }
            }
            // --- consulta com falta, feita pela thread do servidor (esta thread): igual a original em TODO estado
            for (Mapa m : new Mapa[]{n, o}) {
                m.put(a);
                definirCasas(m, e);
            }
            Chunk rn = n.get(40, -40);
            Chunk ro = o.get(40, -40);
            if (rn != q || ro != q || !mesmas(casas(n), casas(o))) {
                getDif++;
            }
        }
        v.igual(1296L, estados, "estados das 4 casas percorridos");
        v.igual(0L, getDif, "consulta com falta feita pela thread do servidor: resultado e cache identicos a original em todos os estados (" + normais + " deles normais)");
        v.igual(0L, removeDifNormal, "remove a partir de estado normal (casas juntas, sem coordenada repetida): cache identico a original");
        v.igual(0L, putDifNormal, "put a partir de estado normal sem casa da mesma coordenada: cache identico a original");
        if (ligado) {
            v.igual(0L, removeErrado, "chave ligada: depois de remove nenhuma casa tem a coordenada, as outras ficam na ordem (juntas no comeco quando algo saiu), get devolve null"
                    + (primeiro == null ? "" : " (primeiro erro: " + primeiro + ")"));
            v.igual(0L, putErrado, "chave ligada: depois de put o chunk novo e a unica casa com a coordenada e get o devolve"
                    + (primeiro == null ? "" : " (primeiro erro: " + primeiro + ")"));
            v.sim(removeDifQualquer > 0 && putDifQualquer > 0, "chave ligada: as diferencas para a original existem e so aparecem fora do estado normal (remove: "
                    + removeDifQualquer + ", put: " + putDifQualquer + " de " + (3 * 1296) + ")");
        } else {
            v.igual(0L, removeDifQualquer, "chave desligada: remove identico a original em TODOS os estados");
            v.igual(0L, putDifQualquer, "chave desligada: put identico a original em TODOS os estados");
        }
    }

    // ------------------------------------------------------------------ 3. thread de fora nunca muda last1..last4

    static void threadDeFora(Verifica v) throws Exception {
        final Mapa n = novo();
        final Chunk[][] grade = new Chunk[9][9];
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 9; j++) {
                grade[i][j] = new Chunk(i - 4, j - 4);
                n.put(grade[i][j]);
            }
        }
        // a thread do servidor consulta 4 chunks que nao estao no cache (4 faltas): o cache fica com eles
        n.get(-4, -4);
        n.get(0, 0);
        n.get(2, 2);
        n.get(-1, 3);
        Chunk[] antes = casas(n);
        v.sim(mesmas(antes, new Chunk[]{grade[3][7], grade[6][6], grade[4][4], grade[0][0]}),
                "a thread do servidor continua gravando o cache nas faltas (igual a original): " + texto(antes));
        final AtomicLong errados = new AtomicLong();
        final AtomicLong mudou = new AtomicLong();
        emOutraThread(new Runnable() {
            @Override
            public void run() {
                Chunk[] ini = casas(n);
                for (int r = 0; r < 2000; r++) {
                    for (int i = 0; i < 9; i++) {
                        for (int j = 0; j < 9; j++) {
                            if (n.get(i - 4, j - 4) != grade[i][j]) { // acertos e faltas
                                errados.incrementAndGet();
                            }
                        }
                    }
                    if (n.get(100 + r, -100) != null) { // coordenada que o mapa nao guarda
                        errados.incrementAndGet();
                    }
                    if (!mesmas(ini, casas(n))) {
                        mudou.incrementAndGet();
                    }
                }
            }
        });
        Chunk[] depois = casas(n);
        v.igual(0L, errados.get(), "thread de fora: 164 000 consultas, todas com o chunk certo (ou null fora do mapa)");
        if (ligado) {
            v.sim(mesmas(antes, depois) && mudou.get() == 0, "chave ligada: thread de fora NUNCA muda last1..last4 (antes " + texto(antes) + ", depois " + texto(depois) + ")");
            // thread do servidor desconhecida: ninguem grava (nao da para saber quem e a dona)
            ThreadServidor.definir(null);
            try {
                n.get(2, -3);
                v.sim(mesmas(antes, casas(n)), "chave ligada, thread do servidor ainda desconhecida: a consulta nao grava o cache");
            } finally {
                ThreadServidor.definir(Thread.currentThread());
            }
            Chunk c = n.get(2, -3);
            v.sim(c == grade[6][1] && casas(n)[0] == c, "chave ligada: a thread do servidor grava o cache na falta");
        } else {
            v.sim(!mesmas(antes, depois) && mudou.get() > 0, "chave desligada (controle): a thread de fora muda last1..last4, como na original");
        }
    }

    // ------------------------------------------------------------------ 4. contador de versao do mapa

    static void versao(Verifica v) throws Exception {
        final thermos.wrapper.ChunkBlockHashMap m = new thermos.wrapper.ChunkBlockHashMap();
        v.igual(0L, m.hoshieVersao(), "versao: mapa novo = 0");
        Chunk c = new Chunk(3, -9);
        m.put(c);
        v.igual(1L, m.hoshieVersao(), "versao: put soma 1");
        m.put(null);
        m.get(3, -9);
        m.get(50, 50);
        List<int[]> lista = new ArrayList<int[]>();
        lista.add(new int[]{3, -9});
        m.bulkCheck(lista);
        m.size();
        m.raw();
        v.igual(1L, m.hoshieVersao(), "versao: put(null), get, bulkCheck, size e raw nao mudam");
        m.remove(c);
        v.igual(2L, m.hoshieVersao(), "versao: remove soma 1");
        m.remove(c);
        v.igual(3L, m.hoshieVersao(), "versao: remove de chunk que nao esta no mapa tambem soma (toda chamada conta)");
        boolean npe = false;
        try {
            m.remove(null);
        } catch (NullPointerException e) {
            npe = true;
        }
        v.sim(npe && m.hoshieVersao() == 3L, "versao: remove(null) lanca NullPointerException (como a original) e nao soma");
        m.put(c);
        m.put(c);
        v.igual(5L, m.hoshieVersao(), "versao: put sobre posicao ocupada soma");
        emOutraThread(new Runnable() {
            @Override
            public void run() {
                m.put(new Chunk(4, -9));
            }
        });
        v.igual(6L, m.hoshieVersao(), "versao: mudanca feita por outra thread aparece");
        // soma atomica: 4 threads, cada uma pondo e tirando o proprio chunk (bloco de 16x16 ja existente: o
        // mapa Koloboke nao muda de forma; so as casas do bloco, cada thread a sua)
        final int porThread = 50000;
        final long base = m.hoshieVersao();
        Thread[] ts = new Thread[4];
        for (int i = 0; i < ts.length; i++) {
            final Chunk meu = new Chunk(8 + i, -16 + i);
            if (i == 0) {
                m.put(new Chunk(15, -1)); // garante o bloco (x>>4 = 0, z>>4 = -1)
            }
            ts[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int k = 0; k < porThread; k++) {
                        // put/remove de varias threads juntas NAO e protegido (nem na original): com a chave desligada o
                        // remove original pode lancar NullPointerException no cache de 4 entradas. A soma vem antes
                        // disso, entao a contagem tem de fechar mesmo assim
                        try {
                            m.put(meu);
                        } catch (Throwable e) {
                            // ver acima
                        }
                        try {
                            m.remove(meu);
                        } catch (Throwable e) {
                            // ver acima
                        }
                    }
                }
            }, "versao-" + i);
        }
        long esperado = m.hoshieVersao() + 4L * 2L * porThread;
        for (Thread t : ts) {
            t.start();
        }
        for (Thread t : ts) {
            t.join();
        }
        v.igual(esperado, m.hoshieVersao(), "versao: 4 threads x " + porThread + " put+remove juntas, nenhuma soma perdida (base " + base + ")");
    }

    // ------------------------------------------------------------------ 5. parte 1: leitores enquanto o cache mexe

    static final int LADO = 12;

    static Chunk[] encher(Mapa m) {
        Chunk[] esperado = new Chunk[LADO * LADO];
        for (int i = 0; i < LADO; i++) {
            for (int j = 0; j < LADO; j++) {
                Chunk c = new Chunk(i - 6, j - 6); // negativos e positivos, 4 blocos de 16x16
                esperado[i * LADO + j] = c;
                m.put(c);
            }
        }
        return esperado;
    }

    /** Resultado de uma rodada de leitores: [leituras, chunk errado, excecoes]. */
    static long[] rodarLeitores(final Mapa m, final Chunk[] esperado, int iter) throws Exception {
        final AtomicLong leituras = new AtomicLong();
        final AtomicLong errados = new AtomicLong();
        final AtomicLong excecoes = new AtomicLong();
        final boolean[] parar = new boolean[1];
        final Object trava = new Object();
        final CountDownLatch prontos = new CountDownLatch(LEITORES);
        Thread[] ts = new Thread[LEITORES];
        for (int t = 0; t < LEITORES; t++) {
            final long semente = 0x9E3779B97F4A7C15L * (t + 1);
            ts[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    long s = semente;
                    long n = 0;
                    long ruim = 0;
                    prontos.countDown();
                    while (true) {
                        for (int k = 0; k < 1024; k++) {
                            s = xorshift(s);
                            int i = (int) ((s >>> 8) % LADO);
                            int j = (int) ((s >>> 24) % LADO);
                            try {
                                Chunk c = m.get(i - 6, j - 6);
                                if (c != esperado[i * LADO + j]) {
                                    ruim++;
                                }
                            } catch (Throwable e) {
                                excecoes.incrementAndGet();
                            }
                            n++;
                        }
                        leituras.addAndGet(1024);
                        synchronized (trava) {
                            if (parar[0]) {
                                break;
                            }
                        }
                    }
                    errados.addAndGet(ruim);
                }
            }, "leitor-" + t);
            ts[t].start();
        }
        prontos.await();
        // a thread do servidor mexe no cache: consultas em posicoes sorteadas (quase todas faltas: 144 posicoes, 4 casas)
        long s = 12345;
        long ruimServidor = 0;
        long feitas = 0;
        long prazo = System.nanoTime() + 120L * 1000000000L;
        while (feitas < iter || leituras.get() < (long) iter * LEITORES) {
            for (int k = 0; k < 1024; k++) {
                s = xorshift(s);
                int i = (int) ((s >>> 8) % LADO);
                int j = (int) ((s >>> 24) % LADO);
                try {
                    if (m.get(i - 6, j - 6) != esperado[i * LADO + j]) {
                        ruimServidor++;
                    }
                } catch (Throwable e) {
                    excecoes.incrementAndGet();
                }
            }
            feitas += 1024;
            if (System.nanoTime() > prazo) {
                break;
            }
        }
        synchronized (trava) {
            parar[0] = true;
        }
        for (Thread t : ts) {
            t.join();
        }
        return new long[]{leituras.get() + feitas, errados.get() + ruimServidor, excecoes.get(),
            (feitas >= iter && leituras.get() >= (long) iter * LEITORES) ? 1 : 0};
    }

    static void leitoresComCacheMexendo(Verifica v, int iter) throws Exception {
        Mapa n = novo();
        long[] r = rodarLeitores(n, encher(n), iter);
        v.sim(r[3] == 1, "leitores: a rodada chegou ao fim no prazo (" + r[0] + " consultas)");
        v.sim(r[1] == 0 && r[2] == 0, LEITORES + " threads lendo enquanto a thread do servidor mexe no cache (chave " + (ligado ? "ligada" : "desligada")
                + "): " + r[0] + " consultas, chunk de outra coordenada ou errado: " + r[1] + ", excecoes: " + r[2]);
        // controle: a classe original, mesma carga (so informa; o defeito depende de sorte)
        Mapa o = original();
        long[] ro = rodarLeitores(o, encher(o), iter);
        System.out.println("  controle (classe ORIGINAL, mesma carga): " + ro[0] + " consultas, chunk errado: " + ro[1] + ", excecoes: " + ro[2]
                + " (informativo)");
    }

    // ------------------------------------------------------------------ 6. remove(c) com N threads lendo ANTES

    /**
     * O teste do plano: N threads consultam (inclusive o chunk c) e terminam; a thread do servidor faz
     * remove(c); depois N threads novas consultam c. Com a chave ligada: nenhuma casa guarda c e nenhum get
     * devolve c. Devolve quantas rodadas deixaram c no cache.
     */
    static long rodadasComChunkMorto(final Mapa m, int rodadas) throws Exception {
        final Chunk[] esperado = encher(m);
        long mortas = 0;
        long s = 777;
        for (int r = 0; r < rodadas; r++) {
            s = xorshift(s);
            final int idx = (int) ((s >>> 8) % (LADO * LADO));
            final Chunk c = esperado[idx];
            // a thread do servidor poe c no cache, numa casa que muda de rodada para rodada (0 a 3 faltas depois dele)
            m.get(c.xPosition, c.zPosition);
            for (int k = (int) ((s >>> 40) & 3); k > 0; k--) {
                s = xorshift(s);
                Chunk outro = esperado[(int) ((s >>> 8) % (LADO * LADO))];
                m.get(outro.xPosition, outro.zPosition);
            }
            final CountDownLatch largada = new CountDownLatch(1);
            Thread[] ts = new Thread[LEITORES];
            for (int t = 0; t < LEITORES; t++) {
                final long semente = s + 31 * t;
                ts[t] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            largada.await();
                        } catch (InterruptedException e) {
                            return;
                        }
                        long q = semente;
                        for (int k = 0; k < 400; k++) {
                            q = xorshift(q);
                            int i = (int) ((q >>> 8) % (LADO * LADO));
                            try {
                                m.get(c.xPosition, c.zPosition);
                                m.get(esperado[i].xPosition, esperado[i].zPosition);
                            } catch (Throwable e) {
                                // a original pode lancar NullPointerException aqui; a rodada de leitura so prepara o cache
                            }
                        }
                    }
                });
                ts[t].start();
            }
            largada.countDown();
            for (Thread t : ts) {
                t.join();
            }
            m.remove(c); // thread do servidor
            final AtomicLong devolveuC = new AtomicLong();
            for (int t = 0; t < LEITORES; t++) {
                ts[t] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        for (int k = 0; k < 50; k++) {
                            if (m.get(c.xPosition, c.zPosition) != null) {
                                devolveuC.incrementAndGet();
                            }
                        }
                    }
                });
                ts[t].start();
            }
            for (Thread t : ts) {
                t.join();
            }
            boolean naCasa = temCoordenada(casas(m), c.xPosition, c.zPosition);
            if (naCasa || devolveuC.get() != 0 || m.get(c.xPosition, c.zPosition) != null) {
                mortas++;
            }
            // repoe um objeto novo na mesma coordenada (limpando antes o que tiver sobrado, para a rodada seguinte comecar limpa)
            definirCasas(m, new Chunk[4]);
            Chunk novo = new Chunk(c.xPosition, c.zPosition);
            esperado[idx] = novo;
            m.put(novo);
        }
        return mortas;
    }

    static void removeComLeitoresAntes(Verifica v, int rodadas) throws Exception {
        long mortasNovo = rodadasComChunkMorto(novo(), rodadas);
        if (ligado) {
            v.igual(0L, mortasNovo, "chave ligada: " + rodadas + " rodadas de [" + LEITORES + " threads lendo, depois remove(c)]: rodadas em que c ficou no cache ou algum get devolveu c");
        } else {
            System.out.println("  controle (chave desligada): " + mortasNovo + " de " + rodadas + " rodadas deixaram o chunk removido no cache (informativo)");
        }
        long mortasOriginal = rodadasComChunkMorto(original(), rodadas);
        System.out.println("  controle (classe ORIGINAL): " + mortasOriginal + " de " + rodadas + " rodadas deixaram o chunk removido no cache (informativo)");
    }

    // ------------------------------------------------------------------ 7. remove(c) com N threads lendo DURANTE

    /** Estado publicado pela thread do servidor (campo volatile): o que get(x, z) tem de devolver enquanto ele valer. */
    static final class Estado {
        final int x;
        final int z;
        final Chunk esperado;
        final boolean mudando;

        Estado(int x, int z, Chunk esperado, boolean mudando) {
            this.x = x;
            this.z = z;
            this.esperado = esperado;
            this.mudando = mudando;
        }
    }

    static volatile Estado estado;

    /**
     * A thread do servidor tira e repoe um chunk por rodada, e entre uma coisa e outra mexe no cache com
     * consultas. Antes de cada mudanca publica "mudando"; depois, o estado novo (objeto novo a cada vez).
     * O leitor le o estado, consulta, le o estado de novo: se e o MESMO objeto e nao e "mudando", a consulta
     * inteira caiu num trecho sem mudanca e depois da publicacao (o volatile garante que o leitor enxerga
     * tudo o que a thread do servidor gravou antes), entao o resultado tem de ser exatamente o esperado.
     * Devolve [conferidas, devolveu chunk removido, outro erro, excecoes, erros vistos pela thread do servidor].
     */
    static long[] rodarRemoveDurante(final Mapa m, int rodadas) throws Exception {
        final Chunk[] esperado = encher(m);
        final AtomicLong conferidas = new AtomicLong();
        final AtomicLong removido = new AtomicLong();
        final AtomicLong outro = new AtomicLong();
        final AtomicLong excecoes = new AtomicLong();
        final boolean[] parar = new boolean[1];
        final Object trava = new Object();
        final CountDownLatch prontos = new CountDownLatch(LEITORES);
        estado = new Estado(esperado[0].xPosition, esperado[0].zPosition, esperado[0], false);
        Thread[] ts = new Thread[LEITORES];
        for (int t = 0; t < LEITORES; t++) {
            final long semente = 0xD1B54A32D192ED03L * (t + 1);
            ts[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    long q = semente;
                    long ok = 0;
                    prontos.countDown();
                    while (true) {
                        for (int k = 0; k < 256; k++) {
                            try {
                                Estado e1 = estado;
                                Chunk c = m.get(e1.x, e1.z);
                                Estado e2 = estado;
                                if (e1 == e2 && !e1.mudando) {
                                    ok++;
                                    if (c != e1.esperado) {
                                        if (e1.esperado == null) {
                                            removido.incrementAndGet();
                                        } else {
                                            outro.incrementAndGet();
                                        }
                                    }
                                }
                                // outra posicao qualquer (quase sempre falta: com a chave desligada, e aqui que o leitor grava o cache)
                                q = xorshift(q);
                                int i = (int) ((q >>> 8) % (LADO * LADO));
                                Chunk d = m.get(i / LADO - 6, i % LADO - 6);
                                if (d != null && (d.xPosition != i / LADO - 6 || d.zPosition != i % LADO - 6)) {
                                    outro.incrementAndGet();
                                }
                            } catch (Throwable e) {
                                excecoes.incrementAndGet();
                            }
                        }
                        conferidas.addAndGet(ok);
                        ok = 0;
                        synchronized (trava) {
                            if (parar[0]) {
                                break;
                            }
                        }
                    }
                }
            }, "leitor-" + t);
            ts[t].start();
        }
        prontos.await();
        long s = 4242;
        long erroServidor = 0;
        for (int r = 0; r < rodadas; r++) {
            s = xorshift(s);
            int idx = (int) ((s >>> 8) % (LADO * LADO));
            Chunk c = esperado[idx];
            int x = c.xPosition;
            int z = c.zPosition;
            // o chunk esta no mapa: os leitores passam a olhar para ele
            estado = new Estado(x, z, c, false);
            s = mexer(m, esperado, s, 24);
            estado = new Estado(x, z, null, true);
            try {
                m.remove(c);
            } catch (Throwable e) {
                excecoes.incrementAndGet();
            }
            estado = new Estado(x, z, null, false);
            try {
                if (m.get(x, z) != null) {
                    erroServidor++;
                }
            } catch (Throwable e) {
                excecoes.incrementAndGet();
            }
            esperado[idx] = null;
            s = mexer(m, esperado, s, 24);
            try {
                if (m.get(x, z) != null) {
                    erroServidor++;
                }
            } catch (Throwable e) {
                excecoes.incrementAndGet();
            }
            estado = new Estado(x, z, null, true);
            Chunk novo = new Chunk(x, z);
            try {
                m.put(novo);
            } catch (Throwable e) {
                excecoes.incrementAndGet();
            }
            esperado[idx] = novo;
            estado = new Estado(x, z, novo, false);
        }
        synchronized (trava) {
            parar[0] = true;
        }
        for (Thread t : ts) {
            t.join();
        }
        return new long[]{conferidas.get(), removido.get(), outro.get(), excecoes.get(), erroServidor};
    }

    /** A thread do servidor consulta posicoes sorteadas (faltas: empurram o cache). */
    static long mexer(Mapa m, Chunk[] esperado, long s, int vezes) {
        for (int k = 0; k < vezes; k++) {
            s = xorshift(s);
            int i = (int) ((s >>> 8) % (LADO * LADO));
            try {
                m.get(i / LADO - 6, i % LADO - 6);
            } catch (Throwable e) {
                // so a classe original chega aqui (NullPointerException da leitura dupla); contado pelos leitores
            }
        }
        return s;
    }

    static void removeComLeitoresDurante(Verifica v, int rodadas) throws Exception {
        long[] r = rodarRemoveDurante(novo(), rodadas);
        String resumo = rodadas + " rodadas de tirar e repor com " + LEITORES + " threads lendo: " + r[0] + " consultas conferidas, devolveu chunk removido: "
                + r[1] + " (leitores) + " + r[4] + " (thread do servidor), outro erro: " + r[2] + ", excecoes: " + r[3];
        if (ligado) {
            v.sim(r[0] > rodadas, "leitores conferiram consultas em trechos sem mudanca (" + r[0] + ")");
            v.sim(r[1] == 0 && r[2] == 0 && r[3] == 0 && r[4] == 0, "chave ligada: " + resumo);
        } else {
            // sem a chave, o remove original ainda le cada casa duas vezes (null, depois coordenada): um leitor de fora que
            // empurra o cache no meio pode causar NullPointerException no remove da thread do servidor. E o codigo original
            System.out.println("  controle (chave desligada): " + resumo + " (informativo)");
        }
        long[] o = rodarRemoveDurante(original(), rodadas);
        System.out.println("  controle (classe ORIGINAL): " + o[0] + " consultas conferidas, devolveu chunk removido: " + o[1] + " (leitores) + " + o[4]
                + " (thread do servidor), outro erro: " + o[2] + ", excecoes: " + o[3] + " (informativo)");
    }
}
