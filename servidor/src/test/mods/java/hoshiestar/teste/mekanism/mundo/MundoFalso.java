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
package hoshiestar.teste.mekanism.mundo;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Um World (a classe REAL do Minecraft, nomes SRG) criado sem construtor, com so o que a busca de
 * tile entity usa. A busca imita a do Crucible (World.getTileEntity -&gt; getChunkFromChunkCoords
 * -&gt; Chunk.func_150806_e): faixa de altura, chunk pelo provedor, mapa de tile entities do chunk
 * (o campo REAL {@code Chunk.field_150816_i}, com chaves {@code ChunkPosition}), entrada invalidada
 * e removida. {@code getChunkFromChunkCoords} de um chunk ausente conta em {@link #cargasDeChunk}
 * (no Crucible ele CARREGARIA o chunk). Fica de fora (e e coberto por argumento no doc): a lista de
 * tile entities acrescentadas durante o tick e a captura de arvore do Crucible.
 */
public abstract class MundoFalso extends World {

    /** Chamado quando a busca acha a posicao vazia (imita a criacao de tile entity + aviso aos vizinhos). */
    public interface Criador {
        TileEntity criar(MundoFalso w, int x, int y, int z);

        void depoisDeColocar(MundoFalso w, int x, int y, int z);
    }

    public Map<Long, Chunk> carregados;
    public Provedor provedor;
    /** Chamadas que carregariam (ou gerariam) um chunk no Crucible. Tem de ficar 0. */
    public long cargasDeChunk;
    /** Chamadas de getTileEntity (para o banco de medida). */
    public long buscas;
    /**
     * Chamadas que marcam o chunk de uma tile entity como modificado (World.markTileEntityChunkModified,
     * o que TileEntity.markDirty faz) e de getBlockMetadata (a primeira coisa que markDirty chama). So o
     * chunk marcado e salvo na parada sem entidades: compara o original com o Hoshiestar.
     */
    public long marcas;
    public long metadados;
    /** Imita mundo fundo (AkashicBellow): aceita y fora de 0..255. */
    public boolean mundoFundo;
    public Criador criador;

    protected MundoFalso() {
        super(null, null, (WorldSettings) null, null, null); // nunca roda: o objeto e criado com Unsafe
    }

    public static MundoFalso novo() throws Exception {
        MundoFalso w = (MundoFalso) unsafe().allocateInstance(Impl.class);
        w.carregados = new HashMap<Long, Chunk>();
        w.provedor = new Provedor(w);
        return w;
    }

    static sun.misc.Unsafe unsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    public static long chave(int a, int b) {
        return ((long) a << 32) ^ (b & 0xffffffffL);
    }

    public static final class Impl extends MundoFalso {
        @Override
        protected IChunkProvider func_72970_h() {
            return null;
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity func_73045_a(int id) {
            return null;
        }
    }

    @Override
    public IChunkProvider func_72863_F() {
        return provedor;
    }

    @Override
    public void func_147476_b(int x, int y, int z, TileEntity t) {
        marcas++;
    }

    @Override
    public int func_72805_g(int x, int y, int z) {
        metadados++;
        return 0;
    }

    @Override
    public Chunk func_72964_e(int cx, int cz) {
        Chunk c = carregados.get(chave(cx, cz));
        if (c == null) {
            cargasDeChunk++;
        }
        return c;
    }

    @Override
    public TileEntity func_147438_o(int x, int y, int z) {
        buscas++;
        if (!mundoFundo && (y < 0 || y >= 256)) {
            return null;
        }
        Chunk c = func_72964_e(x >> 4, z >> 4);
        if (c == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> m = c.field_150816_i;
        ChunkPosition k = new ChunkPosition(x & 15, y, z & 15);
        TileEntity t = (TileEntity) m.get(k);
        if (t != null && t.func_145837_r()) {
            m.remove(k);
            t = null;
        }
        if (t == null && criador != null) {
            t = criador.criar(this, x, y, z);
            if (t != null) {
                colocar(x, y, z, t);
                criador.depoisDeColocar(this, x, y, z);
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ manipulacao do mundo

    public Chunk carregarChunk(int cx, int cz) throws Exception {
        Chunk c = (Chunk) unsafe().allocateInstance(Chunk.class);
        c.field_150816_i = new HashMap<Object, Object>(512, 0.90F); // como o Chunk do Crucible
        c.field_76636_d = true;
        carregados.put(chave(cx, cz), c);
        return c;
    }

    /** Como Chunk.onChunkUnload: isChunkLoaded = false; as tile entities NAO sao invalidadas. */
    public void descarregarChunk(int cx, int cz) {
        Chunk c = carregados.remove(chave(cx, cz));
        if (c != null) {
            c.field_76636_d = false;
        }
    }

    /**
     * FORA DO PADRAO: o chunk some do provedor SEM onChunkUnload (o objeto continua com
     * isChunkLoaded = true). Serve para provar a ordem "chunkExists antes de getChunkFromChunkCoords".
     */
    public void sumirChunk(int cx, int cz) {
        carregados.remove(chave(cx, cz));
    }

    public boolean chunkCarregado(int x, int z) {
        return carregados.containsKey(chave(x >> 4, z >> 4));
    }

    @SuppressWarnings("unchecked")
    Map<Object, Object> mapa(int x, int z) {
        Chunk c = carregados.get(chave(x >> 4, z >> 4));
        return c == null ? null : c.field_150816_i;
    }

    /** Como Chunk.func_150812_a: invalida a anterior, poe mundo e coordenada, valida e guarda. */
    public void colocar(int x, int y, int z, TileEntity t) {
        Map<Object, Object> m = mapa(x, z);
        ChunkPosition k = new ChunkPosition(x & 15, y, z & 15);
        TileEntity velha = (TileEntity) m.get(k);
        t.func_145834_a(this);
        t.field_145851_c = x;
        t.field_145848_d = y;
        t.field_145849_e = z;
        if (velha != null) {
            velha.func_145843_s();
        }
        t.func_145829_t();
        m.put(k, t);
    }

    /** Fora do padrao (mod que mexe no mapa direto): troca a entrada SEM invalidar a anterior. */
    public void colocarCru(int x, int y, int z, TileEntity t) {
        Map<Object, Object> m = mapa(x, z);
        t.func_145834_a(this);
        t.field_145851_c = x;
        t.field_145848_d = y;
        t.field_145849_e = z;
        t.func_145829_t();
        m.put(new ChunkPosition(x & 15, y, z & 15), t);
    }

    /** Chunk.removeTileEntity (remove e invalida); cru = sem invalidar (fora do padrao). */
    public TileEntity remover(int x, int y, int z, boolean cru) {
        TileEntity t = (TileEntity) mapa(x, z).remove(new ChunkPosition(x & 15, y, z & 15));
        if (t != null && !cru) {
            t.func_145843_s();
        }
        return t;
    }

    /** A entrada do mapa, sem efeito colateral (null se o chunk nao esta carregado). */
    public TileEntity cru(int x, int y, int z) {
        Map<Object, Object> m = mapa(x, z);
        return m == null ? null : (TileEntity) m.get(new ChunkPosition(x & 15, y, z & 15));
    }

    // ------------------------------------------------------------------ provedor

    public static final class Provedor implements IChunkProvider {
        final MundoFalso w;

        Provedor(MundoFalso w) {
            this.w = w;
        }

        @Override
        public boolean func_73149_a(int cx, int cz) {
            return w.carregados.containsKey(chave(cx, cz));
        }

        @Override
        public Chunk func_73154_d(int cx, int cz) {
            return w.func_72964_e(cx, cz);
        }

        @Override
        public Chunk func_73158_c(int cx, int cz) {
            return w.func_72964_e(cx, cz);
        }

        @Override
        public void func_73153_a(IChunkProvider p, int a, int b) {
        }

        @Override
        public boolean func_73151_a(boolean b, IProgressUpdate p) {
            return true;
        }

        @Override
        public boolean func_73156_b() {
            return false;
        }

        @Override
        public boolean func_73157_c() {
            return true;
        }

        @Override
        public String func_73148_d() {
            return "falso";
        }

        @SuppressWarnings("rawtypes")
        @Override
        public List func_73155_a(EnumCreatureType t, int a, int b, int c) {
            return null;
        }

        @Override
        public ChunkPosition func_147416_a(World w, String s, int a, int b, int c) {
            return null;
        }

        @Override
        public int func_73152_e() {
            return 0;
        }

        @Override
        public void func_82695_e(int a, int b) {
        }

        @Override
        public void func_104112_b() {
        }
    }
}
