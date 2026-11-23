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

import betterquesting.api2.utils.ParticipantInfo;
import bq_standard.tasks.TaskRetrieval;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pecas a mais dos cenarios: tipos que o pacote nao tem, mas que outro addon/mod poderia ter. */
public final class Extras {

    private Extras() {
    }

    /**
     * Item com hashCode que muda a cada chamada (equals por identidade). Num HashSet/HashMap comum, um
     * Item assim presente no inventario "sumiria" na busca; no conjunto por identidade (C1), nao.
     */
    public static final class ItemInstavel extends Item {
        private int h;

        public ItemInstavel(String n) {
            super(n, false);
        }

        @Override
        public int hashCode() {
            return h++;
        }
    }

    /**
     * Tarefa de addon (subclasse de TaskRetrieval, como poderia existir em outro mod) que PÕE um item no
     * inventario do primeiro jogador ativo (como o TaskFluid troca recipientes) e depois faz o que o
     * TaskRetrieval faz. Serve para: (a) a regra "classe EXATA" (pular uma subclasse perderia o item
     * posto); (b) o conjunto de presentes ter de ser refeito depois de uma chamada que pode mexer em
     * inventario.
     */
    public static final class TarefaQueMexe extends TaskRetrieval {
        public final Item poe;

        public TarefaQueMexe(Item poe) {
            this.poe = poe;
        }

        @Override
        public void onInventoryChange(Map.Entry<UUID, betterquesting.api.questing.IQuest> quest, ParticipantInfo pInfo) {
            if (!pInfo.ACTIVE_PLAYERS.isEmpty()) {
                EntityPlayer p = pInfo.ACTIVE_PLAYERS.get(0);
                if (p.field_71071_by != null) {
                    ItemStack[] s = p.field_71071_by.slots;
                    for (int i = 0; i < s.length; i++) {
                        if (s[i] == null) {
                            s[i] = new ItemStack(poe, 1, 0);
                            break;
                        }
                    }
                }
            }
            super.onInventoryChange(quest, pInfo);
        }
    }

    /**
     * Tarefa de addon (subclasse) que, na primeira chamada, REGISTRA um Item no ore dictionary (lista
     * viva do Forge) e depois faz o que o TaskRetrieval faz. Serve para provar que a lista do ore e lida
     * a cada decisao (a tarefa exata seguinte que pede esse ore tem de ir ao original). Caso T2 da revisao.
     */
    public static final class TarefaQueRegistraOre extends TaskRetrieval {
        public final String ore;
        public final Item registra;

        public TarefaQueRegistraOre(String ore, Item registra) {
            this.ore = ore;
            this.registra = registra;
        }

        @Override
        public void onInventoryChange(Map.Entry<UUID, betterquesting.api.questing.IQuest> quest, ParticipantInfo pInfo) {
            List<ItemStack> l = net.minecraftforge.oredict.OreDictionary.getOres(ore);
            if (l.isEmpty()) {
                l.add(new ItemStack(registra, 1, 0));
            }
            super.onInventoryChange(quest, pInfo);
        }
    }

    /**
     * Lista de jogadores cujo get(i) lanca excecao (forEach e size funcionam): o laco original do BQ
     * (forEach) segue normal, mas o NOSSO codigo de decisao (que usa get) quebra. Para testar o
     * caminho "erro nosso: desliga e roda o original".
     */
    public static final class ListaQueQuebra extends AbstractList<EntityPlayer> {
        private final List<EntityPlayer> dentro;

        public ListaQueQuebra(List<EntityPlayer> dentro) {
            this.dentro = dentro;
        }

        @Override
        public EntityPlayer get(int i) {
            throw new IllegalStateException("get de teste");
        }

        @Override
        public int size() {
            return dentro.size();
        }

        @Override
        public void forEach(java.util.function.Consumer<? super EntityPlayer> c) {
            for (EntityPlayer p : dentro) {
                c.accept(p);
            }
        }

        @Override
        public java.util.Iterator<EntityPlayer> iterator() {
            return dentro.iterator();
        }
    }
}
