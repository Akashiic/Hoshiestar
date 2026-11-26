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
package hoshiestar.orcamento;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.DimensionManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.CRC32;

/**
 * Ferramentas de teste que dependem do jogo (so existem com {@code -Dhoshiestar.orcamento.teste=true}): {@code contar}
 * (itens e energia de uma regiao, varrendo o NBT das tiles e dos itens no chao) e {@code foto} (NBT das tiles e soma dos
 * blocos dos chunks, no tick exato do mundo, fora das regioes excluidas). Rodam na thread do servidor, no fim do tick
 * (um ponto serial: nenhum laco esta no meio). Os pedidos chegam por fila. So leem o jogo (escrevem so o arquivo).
 *
 * <p>NAO VERIFICADO em jogo: as chaves de NBT de energia (padrao {@code electricityStored} do Mekanism, {@code energy} do
 * IC2, {@code Energy} do CoFH) vem de memoria dos mods; o relatorio lista a soma por chave para o testador conferir, e a
 * propriedade {@code hoshiestar.orcamento.chaves_energia} troca a lista.
 */
final class FerramentasMinecraft implements ComandoRegioes.FerramentasJogo {

    private static final class Pedido {
        final boolean foto;
        final long id;
        final String arquivo;
        final long tick;
        final Set<Long> excluir;

        Pedido(boolean foto, long id, String arquivo, long tick, Set<Long> excluir) {
            this.foto = foto;
            this.id = id;
            this.arquivo = arquivo;
            this.tick = tick;
            this.excluir = excluir;
        }
    }

    private final ConcurrentLinkedQueue<Pedido> fila = new ConcurrentLinkedQueue<Pedido>();
    private final List<Pedido> esperandoFoto = new ArrayList<Pedido>();

    @Override
    public String contar(long idRegiao, String arquivo) {
        fila.add(new Pedido(false, idRegiao, arquivo, 0, null));
        return "contar regiao " + idRegiao + ": pedido (roda no fim do proximo tick; saida hoshiestar/" + arquivo + ")";
    }

    @Override
    public String foto(String arquivo, long tick, long[] excluir) {
        Set<Long> ex = new HashSet<Long>();
        for (long l : excluir) {
            ex.add(l);
        }
        fila.add(new Pedido(true, 0, arquivo, tick, ex));
        return "foto: pedido para o tick do mundo " + tick + " (saida hoshiestar/" + arquivo + ")";
    }

    /** Fim de cada tick do servidor (thread do servidor). */
    void aoFimDoTick(Retrato r) {
        Pedido p;
        while ((p = fila.poll()) != null) {
            if (p.foto) {
                esperandoFoto.add(p);
            } else {
                try {
                    contarAgora(r, p);
                } catch (Throwable t) {
                    log("contar falhou: " + t);
                }
            }
        }
        if (esperandoFoto.isEmpty()) {
            return;
        }
        WorldServer w0 = DimensionManager.getWorld(0);
        if (w0 == null) {
            return;
        }
        long agora = w0.getTotalWorldTime();
        for (int i = esperandoFoto.size() - 1; i >= 0; i--) {
            Pedido f = esperandoFoto.get(i);
            if (agora == f.tick) {
                esperandoFoto.remove(i);
                try {
                    fotoAgora(r, f);
                } catch (Throwable t) {
                    log("foto falhou: " + t);
                }
            } else if (agora > f.tick) {
                esperandoFoto.remove(i);
                log("foto: o tick do mundo " + f.tick + " ja passou (agora " + agora + "); pedido descartado");
            }
        }
    }

    private static void log(String s) {
        hoshiestar.api.Log.info("orcamento (teste): " + s);
    }

    private static File saida(String nome) {
        MinecraftServer s = MinecraftServer.getServer();
        File d = s.getFile("hoshiestar");
        d.mkdirs();
        return new File(d, nome);
    }

    private static Set<String> chavesEnergia() {
        String v = System.getProperty("hoshiestar.orcamento.chaves_energia", "electricityStored,energy,Energy");
        Set<String> r = new HashSet<String>();
        for (String s : v.split(",")) {
            if (!s.trim().isEmpty()) {
                r.add(s.trim());
            }
        }
        return r;
    }

    // ---------------------------------------------------------------- contar

    private void contarAgora(Retrato r, Pedido p) throws Exception {
        Map<String, long[]> itens = new TreeMap<String, long[]>();
        Map<String, double[]> energia = new TreeMap<String, double[]>();
        Set<String> chaves = chavesEnergia();
        int tiles = 0, chao = 0;
        WorldServer[] ws = DimensionManager.getWorlds();
        for (WorldServer w : ws) {
            if (w == null || w.provider == null) {
                continue;
            }
            int dim = w.provider.dimensionId;
            for (Object o : new ArrayList<Object>(w.loadedTileEntityList)) {
                if (!(o instanceof TileEntity)) {
                    continue;
                }
                TileEntity te = (TileEntity) o;
                Retrato.Regiao g = r.emBloco(dim, te.xCoord, te.zCoord);
                if (g == null || g.id != p.id) {
                    continue;
                }
                NBTTagCompound t = new NBTTagCompound();
                te.writeToNBT(t);
                varrer(t, itens, energia, chaves);
                tiles++;
            }
            for (Object o : new ArrayList<Object>(w.loadedEntityList)) {
                if (!(o instanceof EntityItem)) {
                    continue;
                }
                Entity e = (Entity) o;
                Retrato.Regiao g = r.emBloco(dim, GanchosOrcamento.floor(e.posX), GanchosOrcamento.floor(e.posZ));
                if (g == null || g.id != p.id) {
                    continue;
                }
                ItemStack s = ((EntityItem) o).getEntityItem();
                if (s != null) {
                    NBTTagCompound t = new NBTTagCompound();
                    s.writeToNBT(t);
                    varrer(t, itens, energia, chaves);
                    chao++;
                }
            }
        }
        long somaItens = 0;
        double somaEnergia = 0;
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(saida(p.arquivo)), Charset.forName("UTF-8")));
        try {
            bw.write("regiao " + p.id + " tiles " + tiles + " itens_no_chao " + chao + "\n");
            for (Map.Entry<String, long[]> e : itens.entrySet()) {
                bw.write("item " + e.getKey() + " " + e.getValue()[0] + "\n");
                somaItens += e.getValue()[0];
            }
            for (Map.Entry<String, double[]> e : energia.entrySet()) {
                bw.write("energia " + e.getKey() + " " + String.format(Locale.ROOT, "%.3f", e.getValue()[0]) + "\n");
                somaEnergia += e.getValue()[0];
            }
            bw.write("totais itens=" + somaItens + " energia=" + String.format(Locale.ROOT, "%.3f", somaEnergia) + "\n");
        } finally {
            bw.close();
        }
        log("contar regiao " + p.id + ": " + tiles + " tiles, " + chao + " itens no chao, itens=" + somaItens + ", energia="
                + String.format(Locale.ROOT, "%.3f", somaEnergia) + " (hoshiestar/" + p.arquivo + ")");
    }

    private static void varrer(NBTBase b, Map<String, long[]> itens, Map<String, double[]> energia, Set<String> chaves) {
        if (b instanceof NBTTagCompound) {
            NBTTagCompound c = (NBTTagCompound) b;
            if (c.hasKey("id") && c.hasKey("Count") && c.getTag("Count") instanceof NBTBase.NBTPrimitive) {
                NBTBase idt = c.getTag("id");
                String id = idt instanceof NBTBase.NBTPrimitive ? String.valueOf(((NBTBase.NBTPrimitive) idt).func_150289_e())
                        : c.getString("id");
                long qtd = ((NBTBase.NBTPrimitive) c.getTag("Count")).func_150291_c();
                String chave = id + ":" + c.getShort("Damage");
                long[] a = itens.get(chave);
                if (a == null) {
                    itens.put(chave, new long[] {qtd});
                } else {
                    a[0] += qtd;
                }
            }
            for (Object k : c.func_150296_c()) {
                String nome = (String) k;
                NBTBase f = c.getTag(nome);
                if (f instanceof NBTBase.NBTPrimitive && chaves.contains(nome)) {
                    double[] a = energia.get(nome);
                    double v = ((NBTBase.NBTPrimitive) f).func_150286_g();
                    if (a == null) {
                        energia.put(nome, new double[] {v});
                    } else {
                        a[0] += v;
                    }
                } else if (f instanceof NBTTagCompound || f instanceof NBTTagList) {
                    varrer(f, itens, energia, chaves);
                }
            }
        } else if (b instanceof NBTTagList) {
            NBTTagList l = (NBTTagList) b;
            if (l.func_150303_d() == 10) {
                for (int i = 0; i < l.tagCount(); i++) {
                    varrer(l.getCompoundTagAt(i), itens, energia, chaves);
                }
            }
        }
    }

    // ---------------------------------------------------------------- foto

    private void fotoAgora(Retrato r, Pedido p) throws Exception {
        List<String> linhas = new ArrayList<String>();
        WorldServer[] ws = DimensionManager.getWorlds();
        for (WorldServer w : ws) {
            if (w == null || w.provider == null) {
                continue;
            }
            int dim = w.provider.dimensionId;
            for (Object o : new ArrayList<Object>(w.loadedTileEntityList)) {
                if (!(o instanceof TileEntity)) {
                    continue;
                }
                TileEntity te = (TileEntity) o;
                Retrato.Regiao g = r.emBloco(dim, te.xCoord, te.zCoord);
                if (g != null && p.excluir.contains(g.id)) {
                    continue;
                }
                NBTTagCompound t = new NBTTagCompound();
                te.writeToNBT(t);
                linhas.add("T " + dim + " " + te.xCoord + " " + te.yCoord + " " + te.zCoord + " " + te.getClass().getName()
                        + " " + ordenado(t));
            }
            for (Object o : new ArrayList<Object>(w.theChunkProviderServer.loadedChunks)) {
                if (!(o instanceof Chunk)) {
                    continue;
                }
                Chunk ch = (Chunk) o;
                Retrato.Regiao g = r.emBloco(dim, ch.xPosition << 4, ch.zPosition << 4);
                if (g != null && p.excluir.contains(g.id)) {
                    continue;
                }
                CRC32 crc = new CRC32();
                ExtendedBlockStorage[] arr = ch.getBlockStorageArray();
                for (int i = 0; i < arr.length; i++) {
                    ExtendedBlockStorage s = arr[i];
                    if (s == null) {
                        continue;
                    }
                    crc.update(i);
                    byte[] lsb = s.getBlockLSBArray();
                    if (lsb != null) {
                        crc.update(lsb, 0, lsb.length);
                    }
                    NibbleArray msb = s.getBlockMSBArray();
                    if (msb != null && msb.data != null) {
                        crc.update(msb.data, 0, msb.data.length);
                    }
                    NibbleArray meta = s.getMetadataArray();
                    if (meta != null && meta.data != null) {
                        crc.update(meta.data, 0, meta.data.length);
                    }
                }
                linhas.add("C " + dim + " " + ch.xPosition + " " + ch.zPosition + " " + Long.toHexString(crc.getValue()));
            }
        }
        Collections.sort(linhas);
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(saida(p.arquivo)), Charset.forName("UTF-8")), 1 << 16);
        try {
            bw.write("foto tick_do_mundo " + p.tick + " excluidas " + p.excluir + " linhas " + linhas.size() + "\n");
            for (String l : linhas) {
                bw.write(l);
                bw.write('\n');
            }
        } finally {
            bw.close();
        }
        log("foto no tick do mundo " + p.tick + ": " + linhas.size() + " linhas (hoshiestar/" + p.arquivo + ")");
    }

    /** NBT em texto com as chaves em ordem (o toString do jogo segue a ordem do HashMap, que pode variar). */
    static String ordenado(NBTBase b) {
        if (b instanceof NBTTagCompound) {
            NBTTagCompound c = (NBTTagCompound) b;
            List<String> ks = new ArrayList<String>();
            for (Object k : c.func_150296_c()) {
                ks.add((String) k);
            }
            Collections.sort(ks);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < ks.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(ks.get(i)).append(':').append(ordenado(c.getTag(ks.get(i))));
            }
            return sb.append('}').toString();
        }
        if (b instanceof NBTTagList) {
            NBTTagList l = (NBTTagList) b;
            if (l.func_150303_d() == 10) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < l.tagCount(); i++) {
                    sb.append(i == 0 ? "" : ",").append(ordenado(l.getCompoundTagAt(i)));
                }
                return sb.append(']').toString();
            }
        }
        return String.valueOf(b);
    }
}
