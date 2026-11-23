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
package hoshiestar.chromaticraft;

import hoshiestar.api.Limitador;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A decisao do recurso "chromaticraft" (item 1.B): dado o pedido de "mover area" de um jogador, diz se
 * o pedido pode seguir para o codigo original do ChromatiCraft ou deve ser recusado ANTES de qualquer
 * bloco ser lido, movido ou removido.
 *
 * <p>Esta classe e codigo puro: so inteiros, um mapa e um relogio. Nao conhece Minecraft, Forge nem
 * ChromatiCraft (o gancho {@code hoshiestar.chromaticraft.shift.GanchoShift} extrai os numeros e delega
 * para ca). Por isso o teste isolado exercita EXATAMENTE o codigo que roda no servidor.
 *
 * <p>As checagens, nesta ordem (a primeira que falha decide o motivo). Todas sao leitura pura, nenhuma
 * mexe no mundo; a unica coisa que esta classe escreve e a hora do ultimo pedido aceito de cada jogador:
 * <ol>
 * <li><b>Intervalo minimo</b> entre dois pedidos ACEITOS do mesmo jogador ({@code intervaloMs}; 0 = nao confere).
 * Pedido recusado nao renova a hora (o jogador pode corrigir e tentar de novo).</li>
 * <li><b>Distancia</b> entre 0 e a potencia maxima da habilidade (no ChromatiCraft: 24). Nenhum cliente normal
 * manda outro valor: o servidor do mod nao confere o numero que vem no pacote.</li>
 * <li><b>Volume</b>: numero de posicoes que o laco original visita, {@code (dx+1)*(dy+1)*(dz+1)}, no maximo
 * {@code volumeMaximo} (0 = sem limite). Conta feita em {@code long} e com saturacao: nunca estoura.</li>
 * <li><b>Altura do destino</b>: a caixa deslocada tem de ficar inteira em y de 0 a 255. Fora disso o
 * {@code World.setBlock} nao poe o bloco e o original ja teria apagado a origem (bloco perdido).</li>
 * <li><b>Chunks carregados</b>: todos os chunks da caixa de origem e da de destino, alargadas em 1 bloco em x e z
 * (os vizinhos que o {@code setBlock} avisa), ja estao no mapa de chunks carregados. A consulta e so de leitura
 * no mapa: nunca carrega nem gera chunk.</li>
 * </ol>
 *
 * <p>Seguro entre threads (metodos synchronized); no servidor so a thread do servidor chama.
 */
public final class DecisaoShift {

    /** Resultado da decisao. */
    public enum Motivo {
        PASSA("passa"),
        INTERVALO("intervalo"),
        DISTANCIA("distancia"),
        VOLUME("volume"),
        ALTURA("altura"),
        CHUNKS("chunks");

        public final String nome;

        Motivo(String nome) {
            this.nome = nome;
        }
    }

    /** Consulta ao mapa de chunks carregados do mundo (so leitura; no servidor: ChunkProviderServer.chunkExists). */
    public interface Chunks {
        boolean carregado(int chunkX, int chunkZ);
    }

    /** Altura maxima de bloco (inclusive) do Minecraft 1.7.10. */
    public static final int ALTURA_MAXIMA = 255;

    /**
     * Teto de consultas ao mapa de chunks por caixa (origem ou destino). Acima disso a decisao e "chunks":
     * nao ha como provar que tudo esta carregado sem varrer um numero absurdo de chunks. Com o volume padrao
     * (4096) a conta da no maximo cerca de 260 chunks; o teto so aparece se o admin subir muito o volume.
     */
    public static final int LIMITE_CHUNKS = 16384;

    /** Quando o mapa de jogadores chega a este tamanho, os registros vencidos sao esquecidos. */
    static final int LIMPAR_COM = 64;

    /** Se mesmo depois de limpar o mapa passa deste tamanho, esquece tudo (so deixa passar um pedido a mais por jogador). */
    static final int MAXIMO_ABSOLUTO = 10000;

    /** Intervalo minimo entre pedidos aceitos do mesmo jogador, em ms (0 = nao confere). */
    public final long intervaloMs;
    /** Confere a distancia (0 a potencia maxima). */
    public final boolean validarDistancia;
    /** Volume maximo de posicoes visitadas (0 = sem limite). */
    public final long volumeMaximo;
    /** Confere que o destino fica em y 0..255. */
    public final boolean validarAltura;
    /** Confere que os chunks da origem e do destino ja estao carregados. */
    public final boolean exigirChunks;

    private final long intervaloNanos;
    private final Limitador.Tempo tempo;
    private final Map<Object, long[]> ultimos = new HashMap<Object, long[]>();

    // contadores (escritos e lidos so dentro de metodos synchronized; a linha de estado usa retrato(), uma leitura so)
    private long chamadas;
    private long passaram;
    private final long[] recusas = new long[Motivo.values().length];

    public DecisaoShift(long intervaloMs, boolean validarDistancia, long volumeMaximo, boolean validarAltura,
            boolean exigirChunks) {
        this(intervaloMs, validarDistancia, volumeMaximo, validarAltura, exigirChunks, null);
    }

    /** @param tempo relogio em nanossegundos (null = System.nanoTime); os testes trocam. */
    public DecisaoShift(long intervaloMs, boolean validarDistancia, long volumeMaximo, boolean validarAltura,
            boolean exigirChunks, Limitador.Tempo tempo) {
        if (intervaloMs < 0 || volumeMaximo < 0) {
            throw new IllegalArgumentException("intervalo e volume nao podem ser negativos");
        }
        this.intervaloMs = intervaloMs;
        this.validarDistancia = validarDistancia;
        this.volumeMaximo = volumeMaximo;
        this.validarAltura = validarAltura;
        this.exigirChunks = exigirChunks;
        this.intervaloNanos = intervaloMs > Long.MAX_VALUE / 1000000L ? Long.MAX_VALUE : intervaloMs * 1000000L;
        this.tempo = tempo != null ? tempo : new Limitador.Tempo() {
            @Override
            public long nanos() {
                return System.nanoTime();
            }
        };
    }

    // ------------------------------------------------------------------ contas puras

    /**
     * Quantas voltas o laco de {@code AbilityCalls.shiftArea} da em um eixo com este tamanho
     * ({@code BlockBox.getSizeX/Y/Z() = max - min}, em int): {@code for (i = 0; i <= tamanho; i++)}.
     */
    public static long voltas(int tamanho) {
        return tamanho < 0 ? 0L : tamanho + 1L;
    }

    /**
     * Numero de posicoes que o laco original visita para esta caixa. O tamanho de cada eixo e calculado em int
     * ({@code max - min}), igual ao {@code BlockBox.getSizeX()} do mod (que e quem o laco consulta). O produto
     * satura em {@code Long.MAX_VALUE}: uma caixa do tamanho do mundo (60 milhoes x 256 x 60 milhoes) ja cabe
     * em long, e valores absurdos nao viram numero negativo.
     */
    public static long volume(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return multiplicar(multiplicar(voltas(maxX - minX), voltas(maxY - minY)), voltas(maxZ - minZ));
    }

    /** Produto de dois numeros nao negativos, saturando em Long.MAX_VALUE. */
    static long multiplicar(long a, long b) {
        if (a == 0 || b == 0) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    /**
     * Todos os chunks que tocam a caixa [x0..x1] x [z0..z1] (em blocos), alargada em 1 bloco em x e z, estao
     * carregados? Falso tambem se a conta passa de {@link #LIMITE_CHUNKS} ou sai da faixa de int (esses
     * chunks nunca estao carregados). Para no primeiro que nao estiver.
     */
    static boolean chunksCarregados(long x0, long x1, long z0, long z1, Chunks mapa) {
        long cx0 = (x0 - 1) >> 4;
        long cx1 = (x1 + 1) >> 4;
        long cz0 = (z0 - 1) >> 4;
        long cz1 = (z1 + 1) >> 4;
        if (cx0 < Integer.MIN_VALUE || cx1 > Integer.MAX_VALUE || cz0 < Integer.MIN_VALUE || cz1 > Integer.MAX_VALUE) {
            return false;
        }
        long total = (cx1 - cx0 + 1) * (cz1 - cz0 + 1); // cada fator cabe em 33 bits; o produto cabe em long
        if (total > LIMITE_CHUNKS) {
            return false;
        }
        for (long cx = cx0; cx <= cx1; cx++) {
            for (long cz = cz0; cz <= cz1; cz++) {
                if (!mapa.carregado((int) cx, (int) cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ decisao

    /**
     * Decide um pedido. Se aceito, guarda a hora para o intervalo minimo do jogador.
     *
     * @param jogador chave estavel do jogador (no servidor: o UUID); so usada como chave de mapa
     * @param minX..maxZ a caixa marcada pelo jogador (BlockBox.minX..maxZ)
     * @param dx, dy, dz a direcao do deslocamento (ForgeDirection.offsetX/Y/Z)
     * @param dist a distancia pedida (cada eixo desloca direcao * dist)
     * @param potenciaMaxima o maior valor de distancia legitimo (Chromabilities.SHIFT.getMaxPower())
     * @param chunks consulta ao mapa de chunks carregados; so e chamada se {@link #exigirChunks}
     * @throws RuntimeException o que {@code chunks} lancar (o pedido nao e contado como aceito nem recusado)
     */
    public synchronized Motivo decidir(Object jogador, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            int dx, int dy, int dz, int dist, int potenciaMaxima, Chunks chunks) {
        chamadas++;
        long agora = tempo.nanos();
        Motivo m = avaliar(jogador, agora, minX, minY, minZ, maxX, maxY, maxZ, dx, dy, dz, dist, potenciaMaxima, chunks);
        if (m == Motivo.PASSA) {
            passaram++;
            guardar(jogador, agora);
        } else {
            recusas[m.ordinal()]++;
        }
        return m;
    }

    private Motivo avaliar(Object jogador, long agora, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            int dx, int dy, int dz, int dist, int potenciaMaxima, Chunks chunks) {
        // 1. intervalo minimo
        if (intervaloNanos > 0) {
            long[] e = ultimos.get(jogador);
            if (e != null && agora - e[0] < intervaloNanos) {
                return Motivo.INTERVALO;
            }
        }
        // 2. distancia
        if (validarDistancia && (dist < 0 || dist > potenciaMaxima)) {
            return Motivo.DISTANCIA;
        }
        // 3. volume
        long vol = volume(minX, minY, minZ, maxX, maxY, maxZ);
        if (volumeMaximo > 0 && vol > volumeMaximo) {
            return Motivo.VOLUME;
        }
        if (vol == 0) {
            return Motivo.PASSA; // nenhuma posicao a ler nem a escrever: nada para conferir em altura ou chunks
        }
        // deslocamento em long: mesmo resultado do original (int) quando a distancia e valida, sem estouro quando nao e
        long ox = (long) dx * dist;
        long oy = (long) dy * dist;
        long oz = (long) dz * dist;
        // 4. altura do destino
        if (validarAltura && (minY + oy < 0 || maxY + oy > ALTURA_MAXIMA)) {
            return Motivo.ALTURA;
        }
        // 5. chunks carregados: origem e destino
        if (exigirChunks) {
            if (!chunksCarregados(minX, maxX, minZ, maxZ, chunks)
                    || !chunksCarregados(minX + ox, maxX + ox, minZ + oz, maxZ + oz, chunks)) {
                return Motivo.CHUNKS;
            }
        }
        return Motivo.PASSA;
    }

    private void guardar(Object jogador, long agora) {
        if (intervaloNanos <= 0) {
            return; // sem intervalo nao ha o que lembrar (e o mapa nao cresce)
        }
        long[] e = ultimos.get(jogador);
        if (e != null) {
            e[0] = agora;
            return;
        }
        if (ultimos.size() >= LIMPAR_COM) {
            limpar(agora);
        }
        ultimos.put(jogador, new long[] {agora});
    }

    /**
     * Esquece quem ja esta fora do intervalo (um registro assim e igual a nao ter registro). Se ainda sobrar
     * demais (so com intervalo gigante e muitos jogadores), esquece tudo.
     */
    private void limpar(long agora) {
        Iterator<Map.Entry<Object, long[]>> it = ultimos.entrySet().iterator();
        while (it.hasNext()) {
            if (agora - it.next().getValue()[0] >= intervaloNanos) {
                it.remove();
            }
        }
        if (ultimos.size() >= MAXIMO_ABSOLUTO) {
            ultimos.clear();
        }
    }

    // ------------------------------------------------------------------ leitura dos contadores

    /** Pedidos decididos (aceitos + recusados). */
    public synchronized long chamadas() {
        return chamadas;
    }

    /** Pedidos aceitos (seguiram para o codigo original). */
    public synchronized long passaram() {
        return passaram;
    }

    /**
     * Um retrato dos contadores numa leitura so (sob a mesma trava; a soma fecha): [0] pedidos decididos, [1] aceitos,
     * [2] recusados, e de [3] em diante as recusas por motivo, na ordem de {@code Motivo.values()}.
     */
    public synchronized long[] retrato() {
        long[] r = new long[3 + recusas.length];
        r[0] = chamadas;
        r[1] = passaram;
        long total = 0;
        for (int i = 0; i < recusas.length; i++) {
            r[3 + i] = recusas[i];
            total += recusas[i];
        }
        r[2] = total;
        return r;
    }

    /** Pedidos recusados por este motivo. */
    public synchronized long recusas(Motivo m) {
        return recusas[m.ordinal()];
    }

    /** Total de recusas. */
    public synchronized long recusasTotal() {
        long s = 0;
        for (long r : recusas) {
            s += r;
        }
        return s;
    }

    /** Quantos jogadores o mapa do intervalo guarda agora (para testes e para o status). */
    public synchronized int jogadoresGuardados() {
        return ultimos.size();
    }
}
