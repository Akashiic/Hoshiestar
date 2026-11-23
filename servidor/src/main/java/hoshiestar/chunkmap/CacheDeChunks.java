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
package hoshiestar.chunkmap;

import com.koloboke.collect.map.LongObjMap;
import hoshiestar.api.ThreadServidor;
import net.minecraft.world.chunk.Chunk;

/**
 * Cache direto ("direct-mapped") de um {@code thermos.wrapper.ChunkBlockHashMap}: uma tabela de
 * 2^bits x 2^bits posicoes; o chunk (x, z) so pode ficar na posicao {@code (x & m, z & m)}. Uma
 * consulta olha UMA posicao e confere as coordenadas do chunk que esta la; se nao bate, faz a busca
 * original no mapa e guarda o resultado.
 *
 * <p><b>Regras de seguranca</b> (o argumento completo esta em src/docs/chunkmap.md):
 * <ol>
 * <li>A tabela ({@link #slots}) so e lida e gravada pela thread do servidor ({@link #dono}, vinda de
 * {@link ThreadServidor} quando o mapa e criado). Nenhuma outra thread toca nela.</li>
 * <li>Consulta de outra thread: busca original no mapa, sem ler nem gravar a tabela e sem tocar no
 * cache de 4 entradas do Crucible.</li>
 * <li>Mudanca no mapa (put/remove) feita por outra thread, ou qualquer erro no nosso codigo: o cache
 * deste mapa se desliga para sempre ({@link #ativo} = false, campo volatile) ANTES da mudanca, e o
 * mapa volta ao codigo original do Crucible. Na chamada seguinte o patch solta o cache (o campo do
 * mapa vira null, a tabela sai da memoria). O cache de 4 entradas do Crucible ja esta vazio: com o
 * cache ativo, o put da thread do servidor o zera no fim; soltar nao grava nele (pode ser outra
 * thread, e a do servidor pode estar lendo esses campos no codigo original).</li>
 * <li>Invariante: com o cache ativo, toda posicao da tabela tem null ou um chunk que o mapa guarda
 * nas coordenadas dele. Antes de put/remove mexerem no mapa, a posicao com as mesmas coordenadas e
 * LIMPA; so depois do put a posicao recebe o chunk novo. Assim um erro em qualquer ponto (ate
 * estouro de pilha) deixa a posicao vazia ou certa. A consulta so grava o que acabou de achar no
 * mapa, na mesma thread que faz put/remove.</li>
 * </ol>
 *
 * <p>Esta classe usa a classe Chunk do Minecraft: so e carregada quando o primeiro mundo carrega
 * (com o interruptor ligado), nunca na fase de coremod.
 */
public final class CacheDeChunks {

    private final LongObjMap<Chunk[][]> mapa;
    private final Thread dono;
    private final Chunk[] slots;
    private final int bits;
    private final int mascara;
    private final boolean conferir;

    /**
     * false = desligado para sempre (o mapa usa o codigo original). volatile: outra thread pode
     * desligar. Publico para o catch do patch gravar sem chamar metodo (funciona com a pilha estourada).
     */
    public volatile boolean ativo = true;

    /** Erro que o catch do patch pegou (gravado sem chamada de metodo); contado em {@link #soltou()}. */
    public volatile Throwable erroNoPonto;

    /** true depois que o desligamento entrou no contador do status (guardado por synchronized(this)). */
    private boolean contado;

    private CacheDeChunks(LongObjMap<Chunk[][]> mapa, Thread dono, int bits, boolean conferir) {
        this.mapa = mapa;
        this.dono = dono;
        this.bits = bits;
        this.mascara = (1 << bits) - 1;
        this.slots = new Chunk[1 << (2 * bits)];
        this.conferir = conferir;
    }

    /**
     * Cria o cache de um mapa, ou devolve null (o mapa fica com o codigo original) se a thread do
     * servidor ainda nao existe ou se der qualquer erro. Nunca lanca excecao.
     */
    public static CacheDeChunks novo(LongObjMap<Chunk[][]> mapa) {
        try {
            Thread t = ThreadServidor.thread();
            if (t == null || mapa == null) {
                ChunkMap.SEM_THREAD_DO_SERVIDOR.incrementAndGet();
                return null;
            }
            CacheDeChunks c = new CacheDeChunks(mapa, t, ChunkMap.bits(), ChunkMap.conferir());
            ChunkMap.CACHES_CRIADOS.incrementAndGet();
            return c;
        } catch (Throwable t) {
            ChunkMap.CACHES_DESLIGADOS.incrementAndGet();
            ChunkMap.desligou("erro ao criar o cache: " + t, t);
            return null;
        }
    }

    /** true enquanto o cache vale; false = o mapa segue o codigo original. */
    public boolean ativo() {
        return ativo;
    }

    /**
     * Consulta (ChunkBlockHashMap.get com o cache ativo). Thread do servidor: tabela e, se errar,
     * busca no mapa e grava. Outra thread: so a busca no mapa.
     */
    public Chunk get(int x, int z) {
        if (Thread.currentThread() != dono) {
            if (primeiraLeituraDeForaNaoVista()) {
                ChunkMap.primeiraLeituraDeFora = Thread.currentThread().getName();
            }
            return buscar(x, z);
        }
        try {
            Chunk[] s = slots;
            int i = (x & mascara) | ((z & mascara) << bits);
            Chunk c = s[i];
            if (c != null && c.xPosition == x && c.zPosition == z) {
                return conferir ? conferirAcerto(s, i, x, z, c) : c;
            }
            c = buscar(x, z);
            if (c != null) {
                s[i] = c;
            }
            if (conferir) {
                ChunkMap.faltas++;
            }
            return c;
        } catch (Throwable t) {
            desligar("erro na consulta (" + x + ", " + z + "): " + t, t);
            return buscar(x, z);
        }
    }

    private static boolean primeiraLeituraDeForaNaoVista() {
        return ChunkMap.primeiraLeituraDeFora == null;
    }

    /** Modo conferir: o acerto tem de ser o que o mapa guarda; se nao for, conta, avisa e corrige. */
    private Chunk conferirAcerto(Chunk[] s, int i, int x, int z, Chunk c) {
        Chunk m = buscar(x, z);
        if (m == c) {
            ChunkMap.acertos++;
            return c;
        }
        ChunkMap.divergencias++;
        s[i] = m;
        ChunkMap.LOG.aLim(ChunkMap.LIMITE, "divergencia", "DIVERGENCIA em (" + x + ", " + z + "): cache "
                + descrever(c) + ", mapa " + descrever(m) + "; devolvendo o do mapa");
        return m;
    }

    private static String descrever(Chunk c) {
        return c == null ? "null" : c.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(c));
    }

    /**
     * Antes de put/remove (ChunkBlockHashMap, cache ativo), ANTES de o mapa mudar.
     * Outra thread: desliga o cache antes da mudanca. Thread do servidor: limpa a posicao da tabela
     * se ela guarda um chunk com as mesmas coordenadas (o mapa vai trocar ou tirar o que esta la).
     * Com o modo conferir, conta put sobre posicao ja ocupada (busca extra no mapa: so em teste).
     */
    public void antesDeMudar(Chunk chunk, boolean put) {
        if (chunk == null) {
            return; // put(null) nao muda nada; remove(null) lanca NullPointerException no codigo original
        }
        if (Thread.currentThread() != dono) {
            desligar((put ? "put" : "remove") + " fora da thread do servidor (thread " + Thread.currentThread().getName() + ")", null);
            return;
        }
        try {
            int x = chunk.xPosition;
            int z = chunk.zPosition;
            int i = (x & mascara) | ((z & mascara) << bits);
            Chunk c = slots[i];
            if (c != null && c.xPosition == x && c.zPosition == z) {
                slots[i] = null;
            }
            if (put && conferir && buscar(x, z) != null) {
                ChunkMap.PUTS_SOBRE_OCUPADO.incrementAndGet();
            }
        } catch (Throwable t) {
            desligar("erro antes de " + (put ? "put" : "remove") + ": " + t, t);
        }
    }

    /** Depois de put (ChunkBlockHashMap ja guardou o chunk): a posicao passa a ter o chunk novo. */
    public void depoisDePor(Chunk chunk) {
        if (Thread.currentThread() != dono || chunk == null) {
            return; // outra thread ja desligou o cache em antesDeMudar
        }
        try {
            slots[(chunk.xPosition & mascara) | ((chunk.zPosition & mascara) << bits)] = chunk;
        } catch (Throwable t) {
            desligar("erro depois do put: " + t, t);
        }
    }

    /**
     * O patch viu o cache desligado e vai solta-lo. Garante que o desligamento entrou no contador do
     * status, inclusive quando foi o catch do patch que desligou (sem pilha para contar na hora).
     */
    public void soltou() {
        Throwable e = erroNoPonto;
        contar(e != null ? "erro no ponto de chamada: " + e : "desligado", e);
    }

    /** Desliga para sempre. Seguro de qualquer thread; so a primeira vez conta e loga. */
    void desligar(String motivo, Throwable t) {
        ativo = false; // primeiro, antes de qualquer chamada
        contar(motivo, t);
    }

    private void contar(String motivo, Throwable t) {
        synchronized (this) {
            if (contado) {
                return;
            }
            ChunkMap.CACHES_DESLIGADOS.incrementAndGet();
            contado = true;
        }
        ChunkMap.desligou(motivo, t);
    }

    /**
     * A busca ORIGINAL do Crucible no mapa (mesmas formulas de ChunkBlockHashMap.get, sem o cache
     * de 4 entradas): so le, nao grava nada.
     */
    private Chunk buscar(int x, int z) {
        Chunk[][] bunch = this.mapa.get((((long) (x >> 4)) << 32L) ^ (z >> 4));
        if (bunch == null) return null;

        x %= 16;
        z %= 16;
        return bunch[(x + (x >> 31)) ^ (x >> 31)][(z + (z >> 31)) ^ (z >> 31)];
    }
}
