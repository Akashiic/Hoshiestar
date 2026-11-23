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
package hoshiestar.teste.arsmagica.mundo;

import am2.buffs.Alcancou72;
import am2.playerextensions.ExtendedProperties;
import am2.spell.SkillManager;
import am2.spell.SkillTreeManager;
import hoshiestar.arsmagica.Atalhos;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.entity.living.LivingEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Caso adversario da revisao independente: REENTRANCIA. Uma entidade acelerada, ao receber a atualizacao extra DENTRO do
 * laco de varredura do F1, faz o que o jogo poderia fazer: tira a propria entrada do mapa (ele fica vazio), troca os
 * interruptores, chama de novo o metodo do jogador 2 (aninhado: com o mapa vazio e o atalho ligado, pula), dispara
 * {@code onEntityLiving} de outras entidades (aninhado, com e sem entrada de DiluteTime, com cancelamento) e devolve a
 * entrada. O que o AM2 fez (observaveis) tem de ser IGUAL no original, com os atalhos ligados, desligados e trocados no meio.
 *
 * <p>Roda no class loader dos mods do teste (a classe real am2.AMEventHandler, reduzida, transformada ou nao).
 */
public final class Reentrancia {

    private Object handler;
    private Method mp;
    private Method mv;
    private Map<String, Integer> acc;
    private Map<String, Integer> slo;
    private WorldServer w;
    private EntityPlayer p1;
    private EntityPlayer p2;
    private ExtendedProperties pr1;
    private ExtendedProperties pr2;
    private final List<EntityLivingBase> vivos = new ArrayList<EntityLivingBase>();
    private final List<String> obs = new ArrayList<String>();

    @SuppressWarnings("unchecked")
    private void montar(long semente) throws Exception {
        Class<?> h = Class.forName("am2.AMEventHandler", true, Reentrancia.class.getClassLoader());
        handler = h.newInstance();
        mp = h.getDeclaredMethod("HandlePlayerSpellExtendedProperties", EntityPlayer.class, ExtendedProperties.class);
        mp.setAccessible(true);
        mv = h.getMethod("onEntityLiving", LivingEvent.LivingUpdateEvent.class);
        acc = (Map<String, Integer>) h.getField("acceleratedEntitiesUUIDs").get(null);
        slo = (Map<String, Integer>) h.getField("slowedEntitiesUUIDs").get(null);
        acc.clear();
        slo.clear();
        java.lang.reflect.Field ft = h.getDeclaredField("tick");
        ft.setAccessible(true);
        ft.setInt(null, 1);
        SkillManager.registrada = true;
        SkillTreeManager.desativada = false;
        DimensionManager.calls = 0;
        w = new WorldServer();
        for (int i = 0; i < 6; i++) {
            EntityLivingBase e = new EntityLivingBase();
            e.uuid = new UUID(semente, i);
            vivos.add(e);
            w.field_72996_f.add(e);
        }
        p1 = new EntityPlayer();
        p1.uuid = new UUID(semente, 100);
        p1.field_70170_p = w;
        p2 = new EntityPlayer();
        p2.uuid = new UUID(semente, 101);
        p2.field_70170_p = w;
        w.field_73010_i.add(p1);
        w.field_73010_i.add(p2);
        w.field_72996_f.add(p1);
        w.field_72996_f.add(p2);
        DimensionManager.worlds = new WorldServer[] {w};
        pr1 = new ExtendedProperties();
        pr2 = new ExtendedProperties();
    }

    private int vivo(EntityLivingBase e, int t) throws Exception {
        e.field_70173_aa = t;
        LivingEvent.LivingUpdateEvent ev = new LivingEvent.LivingUpdateEvent(e);
        try {
            mv.invoke(handler, ev);
            return ev.isCanceled() ? 1 : 3;
        } catch (InvocationTargetException x) {
            if (x.getCause() instanceof Alcancou72) {
                return ev.isCanceled() ? 4 : 2;
            }
            throw x;
        }
    }

    /**
     * @param modo 0 = atalhos desligados; 1 = ligados; 2 = trocados dentro da chamada aninhada
     * @return as observacoes; a ultima linha e o numero de chamadas a getWorlds (muda entre original e ligado: nao comparar)
     */
    public static List<String> executar(final int modo, long semente) throws Exception {
        final Reentrancia r = new Reentrancia();
        r.montar(semente);
        Atalhos.varredura = modo != 0;
        Atalhos.diluicao = modo != 0;
        final EntityLivingBase e0 = r.vivos.get(0);
        final EntityLivingBase e1 = r.vivos.get(1);
        final EntityLivingBase e2 = r.vivos.get(2);
        final int[] prof = {0};
        e0.gancho = new Runnable() {
            public void run() {
                try {
                    if (prof[0]++ > 3) {
                        return;
                    }
                    r.acc.remove(e0.uuid.toString());
                    if (modo == 2) {
                        Atalhos.varredura = !Atalhos.varredura;
                        Atalhos.diluicao = !Atalhos.diluicao;
                    }
                    r.mp.invoke(r.handler, r.p2, r.pr2);                // aninhado, mapa vazio
                    r.obs.add("aninhado-vivo-e2=" + r.vivo(e2, 7));
                    r.slo.put(e1.uuid.toString(), 2);
                    r.obs.add("aninhado-vivo-e1=" + r.vivo(e1, 7));      // 7 % 2 != 0: cancelado pelo Dilute
                    r.obs.add("aninhado-vivo-e1b=" + r.vivo(e1, 8));
                    r.slo.remove(e1.uuid.toString());
                    r.acc.put(e0.uuid.toString(), 2);                   // a entrada volta (o laco externo le de novo a cada volta)
                    if (modo == 2) {
                        Atalhos.varredura = !Atalhos.varredura;
                    }
                } catch (Exception x) {
                    r.obs.add("EXC " + x.getCause());
                }
            }
        };
        for (int t = 0; t < 40; t++) {
            if (t == 3) {
                r.acc.put(e0.uuid.toString(), 2);
            }
            if (t == 30) {
                r.acc.clear();
            }
            prof[0] = 0;
            r.mp.invoke(r.handler, r.p1, r.pr1);
            for (EntityLivingBase e : r.vivos) {
                r.obs.add("t" + t + " vivo=" + r.vivo(e, t));
            }
            StringBuilder sb = new StringBuilder("t" + t + " upd=");
            for (EntityLivingBase e : r.vivos) {
                sb.append(e.updates).append('.');
            }
            sb.append(" acc=").append(new TreeMap<String, Integer>(r.acc)).append(" slo=").append(new TreeMap<String, Integer>(r.slo));
            r.obs.add(sb.toString());
        }
        r.obs.add("getWorlds(feitos pelo AM2)=" + DimensionManager.calls);
        return r.obs;
    }
}
