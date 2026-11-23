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
package hoshiestar.teste.betterquesting.mundo;

import betterquesting.api.events.MarkDirtyPlayerEvent;
import betterquesting.api.utils.BigItemStack;
import betterquesting.api2.utils.ParticipantInfo;
import bq_standard.tasks.ITaskInventory;
import bq_standard.tasks.TaskRetrieval;
import cpw.mods.fml.common.eventhandler.Event;
import hoshiestar.betterquesting.filtro.GanchoBQ;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Um "tick" do laco de {@code bq_standard.handlers.EventHandler.onServerTick}, montado fora do jogo com
 * as classes REAIS do BetterQuesting (TaskRetrieval, Detector, ItemComparison, BigItemStack,
 * OreIngredient, TaskBase, TaskProgressableBase, ParticipantInfo, DirtyPlayerMarker) e Minecraft/Forge
 * de mentira (stubs com os nomes SRG). Duas copias do mesmo mundo (mesma montagem): uma roda o laco
 * ORIGINAL ({@code task.onInventoryChange}), a outra o laco TRANSFORMADO (os ganchos REAIS do
 * Hoshiestar, {@link GanchoBQ}, os mesmos bytes do server.jar construido). A {@link #impressao()}
 * junta tudo o que o BQ pode mudar: tarefas completas, progresso salvo, inventarios, eventos e excecao.
 */
public final class Mundo {

    /** Monta o conteudo de um mundo (chamado igual nas duas copias). */
    public interface Montador {
        void montar(Mundo m) throws Exception;
    }

    static final Field USERPROG;
    static final Field COMPLETE;
    static final sun.misc.Unsafe U;

    static {
        try {
            USERPROG = Class.forName("bq_standard.tasks.base.TaskProgressableBase").getDeclaredField("userProgress");
            USERPROG.setAccessible(true);
            COMPLETE = Class.forName("bq_standard.tasks.base.TaskBase").getDeclaredField("completeUsers");
            COMPLETE.setAccessible(true);
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public final List<TaskRetrieval> tarefas = new ArrayList<TaskRetrieval>();
    public final List<Map.Entry<UUID, Object>> entradas = new ArrayList<Map.Entry<UUID, Object>>();
    public final List<ParticipantInfo> infos = new ArrayList<ParticipantInfo>();
    public final List<EntityPlayer> jogadores = new ArrayList<EntityPlayer>();
    /** Excecao (nome da classe) que saiu do laco, ou null. */
    public String excecao;
    public List<Event> eventos = new ArrayList<Event>();
    /** Contadores do Hoshiestar antes e depois (so no laco com ganchos). */
    public final long[] antes = new long[hoshiestar.betterquesting.BetterQuesting.contagem.length];

    public static Mundo novo(Montador mm) throws Exception {
        Mundo m = new Mundo();
        mm.montar(m);
        return m;
    }

    // ------------------------------------------------------------------ montagem

    public EntityPlayer jogador() {
        EntityPlayer p = new EntityPlayer();
        p.field_71071_by = new InventoryPlayer();
        jogadores.add(p);
        return p;
    }

    public static ItemStack pilha(Item it, int n, int dano) {
        return new ItemStack(it, n, dano);
    }

    public ParticipantInfo info(EntityPlayer eu, UUID euId, List<EntityPlayer> ativos, List<UUID> todos) throws Exception {
        ParticipantInfo p = (ParticipantInfo) U.allocateInstance(ParticipantInfo.class);
        set(p, "PLAYER", eu);
        set(p, "UUID", euId);
        set(p, "ALL_UUIDS", todos);
        set(p, "ACTIVE_PLAYERS", ativos);
        set(p, "ACTIVE_UUIDS", null);
        set(p, "PARTY_INSTANCE", null);
        infos.add(p);
        return p;
    }

    static void set(ParticipantInfo p, String n, Object v) throws Exception {
        Field f = ParticipantInfo.class.getDeclaredField(n);
        f.setAccessible(true);
        f.set(p, v);
    }

    public <T extends TaskRetrieval> T tarefa(T t, BigItemStack... itens) {
        for (BigItemStack b : itens) {
            t.requiredItems.add(b);
        }
        tarefas.add(t);
        entradas.add(new AbstractMap.SimpleEntry<UUID, Object>(new UUID(0x51L, tarefas.size()), null));
        return t;
    }

    public static BigItemStack exige(Item it, int n, int dano) {
        BigItemStack b = new BigItemStack(new ItemStack(it, 1, dano));
        b.stackSize = n;
        return b;
    }

    @SuppressWarnings("unchecked")
    public static Map<UUID, Object> progresso(TaskRetrieval t) {
        try {
            return (Map<UUID, Object>) USERPROG.get(t);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Set<UUID> completos(TaskRetrieval t) {
        try {
            return (Set<UUID>) COMPLETE.get(t);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ os dois lacos

    /** O laco original (o que o BQ faz para cada jogador de playerInventoryUpdates). */
    public void original() {
        int ev0 = MinecraftForge.EVENT_BUS.posted.size();
        try {
            for (ParticipantInfo p : infos) {
                for (int k = 0; k < tarefas.size(); k++) {
                    ((ITaskInventory) tarefas.get(k)).onInventoryChange((Map.Entry) entradas.get(k), p);
                }
            }
        } catch (RuntimeException e) {
            excecao = e.getClass().getName();
        }
        eventos = new ArrayList<Event>(MinecraftForge.EVENT_BUS.posted.subList(ev0, MinecraftForge.EVENT_BUS.posted.size()));
        MinecraftForge.EVENT_BUS.posted.clear();
    }

    /**
     * O laco transformado: o que o onServerTick do BQ faz depois da correcao (entrada do metodo, a
     * chamada trocada, o clear trocado). Uma excecao sai do laco antes do fim, como no original.
     */
    public void comGanchos() {
        System.arraycopy(hoshiestar.betterquesting.BetterQuesting.contagem, 0, antes, 0, antes.length);
        int ev0 = MinecraftForge.EVENT_BUS.posted.size();
        GanchoBQ.inicioDoTick(null, null);
        try {
            for (ParticipantInfo p : infos) {
                for (int k = 0; k < tarefas.size(); k++) {
                    GanchoBQ.aoMudarInventario((ITaskInventory) tarefas.get(k), entradas.get(k), p);
                }
            }
            HashSet<Object> conjunto = new HashSet<Object>();
            conjunto.add("jogador");
            GanchoBQ.fimDoLaco(conjunto);
            if (!conjunto.isEmpty()) {
                excecao = "fimDoLaco NAO esvaziou o conjunto";
            }
        } catch (RuntimeException e) {
            excecao = e.getClass().getName();
        }
        eventos = new ArrayList<Event>(MinecraftForge.EVENT_BUS.posted.subList(ev0, MinecraftForge.EVENT_BUS.posted.size()));
        MinecraftForge.EVENT_BUS.posted.clear();
    }

    /** Quanto o contador i do Hoshiestar subiu no ultimo {@link #comGanchos}. */
    public long delta(int i) {
        return hoshiestar.betterquesting.BetterQuesting.contagem[i] - antes[i];
    }

    // ------------------------------------------------------------------ comparacao

    /** Tudo o que o BQ pode mudar, em texto. */
    public String impressao() {
        StringBuilder b = new StringBuilder();
        for (TaskRetrieval t : tarefas) {
            b.append("C").append(new TreeSet<UUID>(completos(t))).append("P{");
            for (Map.Entry<UUID, Object> e : progresso(t).entrySet()) {
                b.append(e.getKey()).append('=').append(e.getValue() instanceof int[] ? Arrays.toString((int[]) e.getValue())
                        : String.valueOf(e.getValue())).append(',');
            }
            b.append("} ");
        }
        for (EntityPlayer p : jogadores) {
            b.append("ext=").append(p.extPropCalls).append(" inv=");
            if (p.field_71071_by == null) {
                b.append("null");
            } else {
                for (ItemStack s : p.field_71071_by.slots) {
                    b.append(s == null ? "-" : s.toString()).append(',');
                }
            }
        }
        b.append("|ev=");
        for (Event e : eventos) {
            b.append(e instanceof MarkDirtyPlayerEvent ? new TreeSet<UUID>(((MarkDirtyPlayerEvent) e).getDirtyPlayerIDs()).toString()
                    : e.getClass().getSimpleName()).append(';');
        }
        b.append("|ex=").append(excecao);
        return b.toString();
    }

    public String descrever() {
        StringBuilder b = new StringBuilder();
        for (TaskRetrieval t : tarefas) {
            b.append(t.getClass().getSimpleName()).append("[c=").append(t.consume).append(" ac=").append(t.autoConsume)
                    .append(" gd=").append(t.groupDetect).append(" one=").append(t.requireOnlyOneItem).append(" itens=");
            for (BigItemStack s : t.requiredItems) {
                b.append(s == null ? "null" : s.stackSize + "x" + s.getBaseStack() + "/ore='" + s.getOreDict() + "'").append(' ');
            }
            b.append("] ");
        }
        return b.toString();
    }
}
