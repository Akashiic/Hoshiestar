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
package hoshiestar.mekanism.matriz;

import hoshiestar.api.Limitador;
import hoshiestar.api.Log;
import hoshiestar.api.Relogio;
import hoshiestar.api.ThreadServidor;
import hoshiestar.mekanism.Mekanism;
import mekanism.api.Coord4D;
import mekanism.common.content.matrix.SynchronizedMatrixData;
import mekanism.common.tile.TileEntityInductionCell;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Iterator;
import java.util.Set;

/**
 * A logica da matriz de inducao rapida (recurso "mekanism"). Os ganchos ({@link GanchoMatriz}) so
 * delegam para ca dentro de um try/catch; o teste isolado chama esta MESMA classe (tirada do jar
 * construido) atraves da classe real do Mekanism transformada.
 *
 * <p><b>O que fica guardado</b>, por estrutura (no campo {@value Mekanism#CAMPO} que a correcao
 * acrescenta na classe do Mekanism): a lista "posicao i do conjunto {@code cells} -&gt; objeto da
 * celula", as coordenadas de cada uma, os chunks onde elas estao e quantas tile entities cada um
 * desses chunks tinha. NUNCA a energia: ela e lida e escrita ao vivo nas celulas.
 *
 * <p><b>Leitura rapida</b> (getEnergy): so se a lista foi montada neste mesmo ciclo de
 * {@link Mekanism#intervalo} ticks, para o mesmo mundo e o mesmo conjunto (mesmo objeto, mesmo
 * tamanho), com cada chunk ainda carregado, ainda existente no provedor, ainda o mesmo objeto e com
 * o mesmo numero de tile entities; e se cada celula guardada nao foi invalidada e continua no mesmo
 * mundo e na mesma coordenada. Entao soma {@code getEnergy()} das celulas na ordem do conjunto,
 * partindo de 0.0, exatamente como o original: o resultado e o mesmo double, bit a bit.
 *
 * <p><b>Reconstrucao</b>: quando nada disso vale, refaz a lista com as MESMAS chamadas do original
 * (Coord4D.getTileEntity na ordem do conjunto) e devolve a mesma soma. Monta tudo em variaveis
 * locais e so publica no fim; uma chamada que chegue no meio (reentrada: a busca original pode criar
 * uma tile entity e avisar os vizinhos) roda o original. Chunk e pedido so depois de a celula ter
 * sido achada, uma vez por chunk, e so depois de chunkExists (nunca carrega nem gera chunk).
 * Estrutura incompleta (alguma coordenada sem celula, chunk descarregado, altura fora de 0..255):
 * nada fica guardado e o original roda; nova tentativa so depois de
 * {@link Mekanism#ESPERA_INCOMPLETA} ticks.
 *
 * <p><b>Escrita rapida</b> (setEnergy): so com a lista valida (as mesmas conferencias da leitura) e,
 * ANTES de escrever qualquer coisa, a conferencia de IDENTIDADE de todas as celulas: o objeto
 * guardado tem de ser o que esta no mapa de tile entities do chunk naquela posicao (o que o original
 * acharia). Depois roda o corpo original (zera, enche, desconta) nas mesmas celulas e na mesma
 * ordem.
 *
 * <p><b>Threads</b>: so a thread do servidor usa ou mexe no estado; qualquer outra (e o cliente)
 * roda o original sem tocar em nada.
 *
 * <p><b>Excecoes</b>: o que sai de dentro da busca original de uma coordenada ({@link #buscar}) e de
 * outro mod ou do mundo e e relancado pelo gancho tal como o original lancaria; qualquer outra excecao
 * e nossa: o gancho desliga o recurso e roda o corpo original.
 */
public final class MatrizInducao {

    static final long NUNCA = Long.MIN_VALUE;

    private static final Log LOG = Log.de(Mekanism.ID);
    private static final Limitador LIM = new Limitador(5, 60000L);

    /** Valor da ultima leitura rapida (entregue logo em seguida por GanchoMatriz.valorLido). */
    static double ultimoValor;

    /** Leitor/escritor do campo acrescentado pela correcao (a classe do mod nao muda por causa disso). */
    private static final MethodHandle LER;
    private static final MethodHandle GRAVAR;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            LER = l.findGetter(SynchronizedMatrixData.class, Mekanism.CAMPO, Object.class);
            GRAVAR = l.findSetter(SynchronizedMatrixData.class, Mekanism.CAMPO, Object.class);
        } catch (Exception e) {
            // sem o campo nao ha como guardar estado: a classe nao carrega e o gancho cai no original
            throw new IllegalStateException("campo " + Mekanism.CAMPO + " ausente em SynchronizedMatrixData", e);
        }
    }

    /** Estado de UMA estrutura. So a thread do servidor mexe. */
    static final class EstadoMatriz {
        /** Uma reconstrucao desta estrutura esta em andamento (chamadas reentrantes rodam o original). */
        boolean construindo;
        /** Tick da ultima reconstrucao que achou a estrutura incompleta, ou NUNCA. */
        long incompletaEm = NUNCA;
        /** A lista publicada (so de estrutura completa), ou null. */
        Retrato atual;
    }

    /** Lista publicada. Os arrays nao mudam depois de publicados (so {@link #chaves}, preenchido uma vez). */
    static final class Retrato {
        final World mundo;
        final Set<Coord4D> conjunto;
        final int tamanho;
        final long construidoEm;
        final TileEntityInductionCell[] refs;
        final int[] xs;
        final int[] ys;
        final int[] zs;
        /** Indice em {@link #chunks} do chunk de cada celula. */
        final int[] grupo;
        final Chunk[] chunks;
        final int[] cxs;
        final int[] czs;
        /** Numero de tile entities de cada chunk no fim da reconstrucao. */
        final int[] tamanhoMapa;
        /** Chaves do mapa de tile entities do chunk (criadas na primeira escrita rapida). */
        ChunkPosition[] chaves;

        Retrato(World mundo, Set<Coord4D> conjunto, int tamanho, long construidoEm, TileEntityInductionCell[] refs,
                int[] xs, int[] ys, int[] zs, int[] grupo, Chunk[] chunks, int[] cxs, int[] czs, int[] tamanhoMapa) {
            this.mundo = mundo;
            this.conjunto = conjunto;
            this.tamanho = tamanho;
            this.construidoEm = construidoEm;
            this.refs = refs;
            this.xs = xs;
            this.ys = ys;
            this.zs = zs;
            this.grupo = grupo;
            this.chunks = chunks;
            this.cxs = cxs;
            this.czs = czs;
            this.tamanhoMapa = tamanhoMapa;
        }
    }

    private MatrizInducao() {
    }

    // ------------------------------------------------------------------ leitura (getEnergy)

    /**
     * Inicio de getEnergy. true = a soma esta em {@link #ultimoValor} e o corpo original nao roda;
     * false = o corpo original roda (sempre, no modo verificar).
     */
    static boolean antesDeLer(SynchronizedMatrixData m, World w) {
        if (!podeUsar(w)) {
            return false;
        }
        Set<Coord4D> cells = m.cells;
        if (cells == null) {
            return false;
        }
        EstadoMatriz em = estadoDe(m);
        if (em == null) {
            em = new EstadoMatriz();
            gravarEstado(m, em);
        }
        if (em.construindo) {
            Mekanism.reentradas++;
            return false;
        }
        boolean verificar = Mekanism.modo == Mekanism.VERIFICAR;
        long agora = Relogio.ticks;
        Retrato r = em.atual;
        if (r != null) {
            if (valido(r, cells, w, agora)) {
                if (verificar) {
                    verificarLeitura(w, em, r, cells);
                    return false;
                }
                if (somar(r, w)) {
                    Mekanism.leiturasRapidas++;
                    return true;
                }
            }
            em.atual = null; // nao prender mundo, chunk nem celula de uma lista que nao vale mais
        }
        if (em.incompletaEm != NUNCA) {
            long d = agora - em.incompletaEm;
            if (d >= 0 && d < Mekanism.ESPERA_INCOMPLETA) {
                Mekanism.leiturasOriginais++;
                return false;
            }
        }
        reconstruir(cells, w, em, agora);
        return !verificar;
    }

    /** Soma rapida com as conferencias por celula. false = alguma celula nao confere (nada e devolvido). */
    static boolean somar(Retrato r, World w) {
        TileEntityInductionCell[] refs = r.refs;
        double soma = 0.0;
        for (int i = 0; i < refs.length; i++) {
            TileEntityInductionCell c = refs[i];
            if (!celulaConfere(c, r, i, w)) {
                return false;
            }
            soma += c.getEnergy();
        }
        ultimoValor = soma;
        return true;
    }

    /**
     * A celula guardada na posicao i ainda e uma celula viva no mesmo lugar: nao invalidada (quem tira
     * uma tile entity do chunk pelo caminho normal a invalida), mesma coordenada, mesmo mundo.
     * Os membros do Minecraft sao acessados por uma variavel do tipo TileEntity de proposito (assim a
     * reofuscacao do build traduz o nome; pela classe do Mekanism ela nao saberia).
     */
    private static boolean celulaConfere(TileEntityInductionCell c, Retrato r, int i, World w) {
        TileEntity t = c;
        if (t.isInvalid()) {
            return false;
        }
        if (t.xCoord != r.xs[i] || t.yCoord != r.ys[i] || t.zCoord != r.zs[i]) {
            return false;
        }
        if (t.getWorldObj() != w) {
            return false;
        }
        return true;
    }

    /**
     * A lista ainda vale? Mundo, conjunto, relogio e, por chunk, na ordem obrigatoria e com curto
     * circuito: carregado -&gt; chunkExists no provedor -&gt; so entao getChunkFromChunkCoords (que
     * CARREGARIA um chunk ausente) e o mesmo objeto -&gt; mesmo numero de tile entities no mapa.
     */
    static boolean valido(Retrato r, Set<Coord4D> cells, World w, long agora) {
        if (r.mundo != w) {
            return false;
        }
        if (r.conjunto != cells || r.tamanho != cells.size()) {
            return false;
        }
        long idade = agora - r.construidoEm;
        if (idade < 0 || idade >= Mekanism.intervalo) {
            return false;
        }
        IChunkProvider prov = w.getChunkProvider();
        if (prov == null) {
            return false;
        }
        for (int j = 0; j < r.chunks.length; j++) {
            Chunk ch = r.chunks[j];
            if (!ch.isChunkLoaded) {
                return false;
            }
            if (!prov.chunkExists(r.cxs[j], r.czs[j])) {
                return false;
            }
            if (w.getChunkFromChunkCoords(r.cxs[j], r.czs[j]) != ch) {
                return false;
            }
            if (ch.chunkTileEntityMap.size() != r.tamanhoMapa[j]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Refaz a lista com as mesmas chamadas e a mesma conta do original (o valor fica em
     * {@link #ultimoValor}, igual ao do original). Publica no fim: lista completa em em.atual, ou
     * nada (e marca o tick) se incompleta.
     */
    static void reconstruir(Set<Coord4D> cells, World w, EstadoMatriz em, long agora) {
        Mekanism.reconstrucoes++;
        int n = cells.size();
        TileEntityInductionCell[] refs = new TileEntityInductionCell[n];
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] zs = new int[n];
        int[] grupo = new int[n];
        Chunk[] chunks = new Chunk[4];
        int[] cxs = new int[4];
        int[] czs = new int[4];
        int ng = 0;
        boolean completa = true;
        double ret = 0.0;
        int i = 0;
        IChunkProvider prov = w.getChunkProvider();
        em.construindo = true;
        try {
            for (Iterator<Coord4D> it = cells.iterator(); it.hasNext(); i++) {
                Coord4D coord = it.next();
                TileEntity tile = buscar(coord, w); // a mesma chamada do original
                if (!(tile instanceof TileEntityInductionCell)) {
                    completa = false;
                    continue;
                }
                TileEntityInductionCell cell = (TileEntityInductionCell) tile;
                ret += cell.getEnergy(); // a mesma conta do original, na mesma ordem
                if (!completa) {
                    continue;
                }
                if (i >= n) {
                    completa = false;
                    continue;
                }
                int x = coord.xCoord;
                int y = coord.yCoord;
                int z = coord.zCoord;
                if (y < 0 || y >= 256) {
                    completa = false; // mundos fundos (AkashicBellow) mudam essa busca: fica no original
                    continue;
                }
                if (tile.xCoord != x || tile.yCoord != y || tile.zCoord != z || tile.getWorldObj() != w) {
                    completa = false;
                    continue;
                }
                int cx = x >> 4;
                int cz = z >> 4;
                int g = -1;
                for (int j = 0; j < ng; j++) {
                    if (cxs[j] == cx && czs[j] == cz) {
                        g = j;
                        break;
                    }
                }
                if (g < 0) {
                    Chunk ch = chunkSemCarregar(prov, w, cx, cz);
                    if (ch == null) {
                        completa = false;
                        continue;
                    }
                    if (ng == chunks.length) {
                        chunks = copiar(chunks, ng * 2);
                        cxs = copiar(cxs, ng * 2);
                        czs = copiar(czs, ng * 2);
                    }
                    chunks[ng] = ch;
                    cxs[ng] = cx;
                    czs[ng] = cz;
                    g = ng++;
                }
                refs[i] = cell;
                xs[i] = x;
                ys[i] = y;
                zs[i] = z;
                grupo[i] = g;
            }
        } finally {
            em.construindo = false;
        }
        if (i != n) {
            completa = false;
        }
        ultimoValor = ret;
        if (completa) {
            int[] tam = new int[ng];
            for (int j = 0; j < ng; j++) {
                tam[j] = chunks[j].chunkTileEntityMap.size();
            }
            em.atual = new Retrato(w, cells, n, agora, refs, xs, ys, zs, grupo, copiar(chunks, ng), copiar(cxs, ng),
                    copiar(czs, ng), tam);
            em.incompletaEm = NUNCA;
        } else {
            em.atual = null;
            em.incompletaEm = agora;
            Mekanism.incompletas++;
        }
    }

    /**
     * O chunk (cx,cz) se ele ja esta carregado; senao null. Ordem obrigatoria: chunkExists ANTES de
     * getChunkFromChunkCoords (no Crucible este carrega, e ate gera, um chunk ausente).
     */
    static Chunk chunkSemCarregar(IChunkProvider prov, World w, int cx, int cz) {
        if (prov == null || !prov.chunkExists(cx, cz)) {
            return null;
        }
        Chunk ch = w.getChunkFromChunkCoords(cx, cz);
        return ch != null && ch.isChunkLoaded ? ch : null;
    }

    // ------------------------------------------------------------------ escrita (setEnergy)

    /** Inicio de setEnergy. true = a escrita ja foi feita (o corpo original nao roda). */
    static boolean antesDeEscrever(SynchronizedMatrixData m, World w, double energia) {
        if (!podeUsar(w)) {
            return false;
        }
        Set<Coord4D> cells = m.cells;
        EstadoMatriz em = estadoDe(m);
        if (cells == null || em == null) {
            Mekanism.escritasOriginais++;
            return false;
        }
        if (em.construindo) {
            Mekanism.reentradas++;
            Mekanism.escritasOriginais++;
            return false;
        }
        Retrato r = em.atual;
        if (r == null || !valido(r, cells, w, Relogio.ticks)) {
            if (r != null) {
                em.atual = null;
            }
            Mekanism.escritasOriginais++;
            return false;
        }
        if (Mekanism.modo == Mekanism.VERIFICAR) {
            verificarEscrita(w, em, r, cells);
            Mekanism.escritasOriginais++;
            return false;
        }
        int c = conferirParaEscrever(r, w);
        if (c != 0) {
            if (c == 2) {
                Mekanism.recusasIdentidade++;
            }
            em.atual = null;
            Mekanism.escritasOriginais++;
            return false;
        }
        // o corpo original, nas mesmas celulas e na mesma ordem (setEnergy da celula so grava um campo)
        double energy = energia;
        TileEntityInductionCell[] refs = r.refs;
        for (int i = 0; i < refs.length; i++) {
            TileEntityInductionCell cell = refs[i];
            cell.setEnergy(0.0);
            double toAdd = Math.min(cell.getMaxEnergy(), energy);
            cell.setEnergy(toAdd);
            energy -= toAdd;
        }
        Mekanism.escritasRapidas++;
        return true;
    }

    /**
     * Conferencia completa ANTES de escrever (nada e escrito ate tudo passar): cada celula confere
     * (nao invalidada, coordenada, mundo) e e o MESMO objeto que esta no mapa de tile entities do seu
     * chunk naquela posicao, que e o que a busca original devolveria (o chunk ja foi conferido em
     * {@link #valido}). 0 = pode escrever; 1 = alguma celula nao confere; 2 = identidade nao confere.
     */
    static int conferirParaEscrever(Retrato r, World w) {
        TileEntityInductionCell[] refs = r.refs;
        for (int i = 0; i < refs.length; i++) {
            if (!celulaConfere(refs[i], r, i, w)) {
                return 1;
            }
        }
        ChunkPosition[] k = r.chaves;
        if (k == null) {
            k = new ChunkPosition[refs.length];
            for (int i = 0; i < refs.length; i++) {
                k[i] = new ChunkPosition(r.xs[i] & 15, r.ys[i], r.zs[i] & 15);
            }
            r.chaves = k;
        }
        for (int i = 0; i < refs.length; i++) {
            if (r.chunks[r.grupo[i]].chunkTileEntityMap.get(k[i]) != refs[i]) {
                return 2;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ modo verificar

    /**
     * Modo verificar, leitura com lista valida: calcula a soma rapida, refaz a busca original (as
     * mesmas chamadas) e compara a IDENTIDADE de cada celula guardada com o que o mundo devolve, e
     * tambem a soma bit a bit. Conta e registra divergencias. O corpo original roda depois.
     */
    static void verificarLeitura(World w, EstadoMatriz em, Retrato r, Set<Coord4D> cells) {
        boolean rapidaOk = somar(r, w);
        double rapida = ultimoValor;
        int n = r.tamanho;
        int difId = 0;
        int primeira = -1;
        double original = 0.0;
        int i = 0;
        em.construindo = true;
        try {
            for (Iterator<Coord4D> it = cells.iterator(); it.hasNext(); i++) {
                Coord4D coord = it.next();
                TileEntity t = buscar(coord, w);
                if (t instanceof TileEntityInductionCell) {
                    original += ((TileEntityInductionCell) t).getEnergy();
                }
                if (i >= n || t != r.refs[i]) {
                    difId++;
                    if (primeira < 0) {
                        primeira = i;
                    }
                }
            }
        } finally {
            em.construindo = false;
        }
        if (i != n) {
            difId++;
        }
        if (!rapidaOk) {
            em.atual = null; // o caminho rapido teria reconstruido: nada a comparar
            return;
        }
        Mekanism.comparacoes++;
        if (difId > 0) {
            Mekanism.divergenciasIdentidade++;
            em.atual = null;
            LOG.aLim(LIM, "id", "verificar: " + difId + " celula(s) guardada(s) nao sao o que o mundo devolve"
                    + descreverPosicao(r, primeira) + " (o original foi usado)");
        }
        if (Double.doubleToLongBits(rapida) != Double.doubleToLongBits(original)) {
            Mekanism.divergenciasSoma++;
            em.atual = null;
            LOG.aLim(LIM, "soma", "verificar: soma rapida " + rapida + " != original " + original + " (o original foi usado)");
        }
    }

    /**
     * Modo verificar, escrita com lista valida: se a conferencia da escrita rapida aceitaria, refaz a
     * busca original e compara a identidade de cada celula. O corpo original roda depois.
     */
    static void verificarEscrita(World w, EstadoMatriz em, Retrato r, Set<Coord4D> cells) {
        if (conferirParaEscrever(r, w) != 0) {
            em.atual = null;
            return;
        }
        Mekanism.comparacoesEscrita++;
        int n = r.tamanho;
        int difId = 0;
        int primeira = -1;
        int i = 0;
        em.construindo = true;
        try {
            for (Iterator<Coord4D> it = cells.iterator(); it.hasNext(); i++) {
                TileEntity t = buscar(it.next(), w);
                if (i >= n || t != r.refs[i]) {
                    difId++;
                    if (primeira < 0) {
                        primeira = i;
                    }
                }
            }
        } finally {
            em.construindo = false;
        }
        if (i != n) {
            difId++;
        }
        if (difId > 0) {
            Mekanism.divergenciasEscrita++;
            em.atual = null;
            LOG.aLim(LIM, "escrita", "verificar: a escrita rapida seria aceita mas " + difId
                    + " celula(s) nao sao o que o mundo devolve" + descreverPosicao(r, primeira) + " (o original foi usado)");
        }
    }

    // ------------------------------------------------------------------ apoio

    /**
     * So a thread do servidor, com um mundo do servidor e depois do primeiro tick (antes dele o relogio
     * esta parado) usa o caminho rapido. Outra thread nao toca em nada nosso: so conta (sincronizado).
     */
    private static boolean podeUsar(World w) {
        if (!ThreadServidor.souAThreadDoServidor()) {
            Mekanism.contarOutraThread();
            return false;
        }
        if (w == null || w.isRemote || Relogio.ticks <= 0) {
            Mekanism.semMundoOuAntesDoTick++;
            return false;
        }
        return true;
    }

    /**
     * A busca original de UMA coordenada (Coord4D.getTileEntity: chunkExists + World.getTileEntity). O
     * que for lancado la dentro e do mundo ou de outro mod (a busca pode criar a tile entity e avisar os
     * vizinhos), nao nosso: vai embrulhado em {@link DeTerceiros} e o gancho relanca a excecao original,
     * como o corpo original faria no mesmo ponto (sem desligar o recurso nem rodar o corpo de novo).
     */
    static TileEntity buscar(Coord4D coord, World w) {
        try {
            return coord.getTileEntity(w);
        } catch (Throwable t) {
            throw new DeTerceiros(t);
        }
    }

    static EstadoMatriz estadoDe(SynchronizedMatrixData m) {
        try {
            return (EstadoMatriz) (Object) LER.invokeExact(m);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static void gravarEstado(SynchronizedMatrixData m, Object e) {
        try {
            GRAVAR.invokeExact(m, e);
        } catch (RuntimeException x) {
            throw x;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * Apaga o estado desta estrutura (chamado pelo gancho depois de uma excecao nossa, com o recurso ja
     * desligado). Pode lancar; o gancho protege.
     */
    static void esquecer(SynchronizedMatrixData m) {
        EstadoMatriz em = estadoDe(m);
        if (em != null) {
            em.atual = null;
            em.construindo = false;
        }
    }

    private static String descreverPosicao(Retrato r, int i) {
        if (i < 0 || i >= r.tamanho) {
            return "";
        }
        return " (primeira na posicao " + i + " do conjunto: " + r.xs[i] + "," + r.ys[i] + "," + r.zs[i] + ")";
    }

    private static Chunk[] copiar(Chunk[] a, int n) {
        Chunk[] b = new Chunk[n];
        System.arraycopy(a, 0, b, 0, Math.min(n, a.length));
        return b;
    }

    private static int[] copiar(int[] a, int n) {
        int[] b = new int[n];
        System.arraycopy(a, 0, b, 0, Math.min(n, a.length));
        return b;
    }

    // ------------------------------------------------------------------ diagnostico (comando e testes)

    /** A lista publicada desta estrutura (para testes), ou null. */
    public static Object retrato(SynchronizedMatrixData m) {
        EstadoMatriz em = estadoDe(m);
        return em == null ? null : em.atual;
    }

    /** Uma linha sobre o estado guardado desta estrutura (comando "hoshie mekanism"). */
    public static String descrever(SynchronizedMatrixData m) {
        EstadoMatriz em = estadoDe(m);
        if (em == null) {
            return "nenhuma lista guardada (ainda nao lida pelo caminho do Hoshiestar)";
        }
        Retrato r = em.atual;
        StringBuilder sb = new StringBuilder();
        if (r == null) {
            sb.append("nenhuma lista guardada agora");
        } else {
            sb.append("lista guardada: ").append(r.tamanho).append(" celulas em ").append(r.chunks.length)
                    .append(" chunk(s), montada no tick ").append(r.construidoEm);
        }
        sb.append("; relogio ").append(Relogio.ticks);
        if (em.incompletaEm != NUNCA) {
            sb.append("; incompleta na ultima reconstrucao (tick ").append(em.incompletaEm).append(")");
        }
        return sb.toString();
    }
}
