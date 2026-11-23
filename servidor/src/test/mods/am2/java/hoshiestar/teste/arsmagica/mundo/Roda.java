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
import am2.buffs.BuffStatModifiers;
import am2.playerextensions.ExtendedProperties;
import am2.spell.SkillManager;
import am2.spell.SkillTreeManager;
import hoshiestar.arsmagica.Atalhos;
import hoshiestar.teste.arsmagica.ErroCapturado;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.entity.living.LivingEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Harness do recurso 1.F: roda o {@code am2.AMEventHandler} REAL (os bytes do jar do AM2, passados ou
 * nao pelo transformador do Hoshiestar, e reduzidos aos metodos testados) com Minecraft/Forge de
 * mentira, numa sequencia aleatoria (semente fixa) de acontecimentos do jogo, e devolve um hash do
 * estado observavel que tem de ser IGUAL entre o original, o transformado com os atalhos ligados, o
 * transformado com eles desligados e o transformado com os interruptores trocados a toda hora.
 *
 * <p>Os dois metodos exercitados sao os alvos: {@code HandlePlayerSpellExtendedProperties} (F1,
 * varredura de todos os mundos; roda inteiro, inclusive a parte que poe e tira entradas dos mapas) e
 * {@code onEntityLiving} (F2, do inicio ate o ponto em que o AM2 segue adiante: um stub de
 * {@code BuffStatModifiers} para a execucao ali e o harness anota "nao cancelado").
 *
 * <p>Acontecimentos (so do gerador do harness; nunca dependem da variante): feiticos de aceleracao e
 * de retardo (variaveis extras dos jogadores, que o AM2 REAL transforma nas entradas dos mapas),
 * jogadores que saem e voltam (deixam entradas velhas), entidades que nascem e morrem, armadilhas no
 * meio da varredura (uma entidade atualizada pelo Accelerate tira ou poe entrada no mapa durante o
 * proprio laco), habilidade DiluteTime ausente ou desativada, e (no modo 2) os interruptores trocados
 * a cada iteracao e no meio dela.
 */
public final class Roda {

    /** Depuracao: se nao for nulo, recebe uma linha por iteracao (codigos, mapas, atualizacoes). So o teste mexe. */
    public static List<String> historico;

    private Roda() {
    }

    /**
     * @param modo 0 = os dois atalhos desligados; 1 = os dois ligados; 2 = trocados ao acaso no inicio de
     *        cada iteracao e de novo no meio dela; 3 = so a varredura; 4 = so o DiluteTime
     * @param taxa uma conjuracao de aceleracao e uma de retardo, em media, a cada {@code taxa} iteracoes
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> rodar(long semente, int iteracoes, int modo, int taxa) throws Exception {
        ErroCapturado.instalar();
        int nulos0 = ErroCapturado.contar();
        ClassLoader cl = Roda.class.getClassLoader();
        Class<?> h = Class.forName("am2.AMEventHandler", true, cl);
        Map<String, Integer> acc = (Map<String, Integer>) h.getField("acceleratedEntitiesUUIDs").get(null);
        Map<String, Integer> slo = (Map<String, Integer>) h.getField("slowedEntitiesUUIDs").get(null);
        acc.clear();
        slo.clear();
        java.lang.reflect.Field ftick = h.getDeclaredField("tick");
        ftick.setAccessible(true);
        ftick.setInt(null, 0);
        DimensionManager.calls = 0;
        SkillManager.chamadas = 0;
        SkillTreeManager.chamadas = 0;
        BuffStatModifiers.alcancadas = 0;
        SkillManager.registrada = true;
        SkillTreeManager.desativada = false;
        Object handler = h.newInstance();
        Method mp = h.getDeclaredMethod("HandlePlayerSpellExtendedProperties", EntityPlayer.class, ExtendedProperties.class);
        mp.setAccessible(true);
        Method mv = h.getMethod("onEntityLiving", LivingEvent.LivingUpdateEvent.class);

        Random r = new Random(semente);          // so os acontecimentos do jogo
        Random rs = new Random(semente * 31 + 7); // so a troca de interruptores
        WorldServer[] ws = new WorldServer[3];
        List<Entity> todas = new ArrayList<Entity>();
        for (int w = 0; w < ws.length; w++) {
            ws[w] = new WorldServer();
            for (int i = 0; i < 40; i++) {
                Entity e = criar(r);
                e.uuid = new UUID(semente, todas.size());
                ws[w].field_72996_f.add(e);
                todas.add(e);
            }
        }
        DimensionManager.worlds = ws;
        int np = 3;
        EntityPlayer[] ps = new EntityPlayer[np];
        ExtendedProperties[] pr = new ExtendedProperties[np];
        boolean[] online = new boolean[np];
        for (int p = 0; p < np; p++) {
            ps[p] = new EntityPlayer();
            ps[p].uuid = new UUID(semente, 100000 + p);
            ps[p].field_70170_p = ws[0];
            ws[0].field_73010_i.add(ps[p]);
            ws[0].field_72996_f.add(ps[p]);
            todas.add(ps[p]);
            pr[p] = new ExtendedProperties();
            online[p] = true;
        }
        long chamadasJogador = 0, chamadasVivo = 0, acelNaoVazio = 0, retNaoVazio = 0, cancelados = 0, alcancou = 0, outras = 0;
        long hash = 17;
        for (int t = 0; t < iteracoes; t++) {
            StringBuilder dep = historico == null ? null : new StringBuilder();
            // ---- acontecimentos (gerador do jogo)
            if (r.nextInt(taxa) == 0) {
                lancar(r, pr[r.nextInt(np)], "accelerated_fast_entity_", todas, acc, true);
            }
            if (r.nextInt(taxa) == 0) {
                lancar(r, pr[r.nextInt(np)], "accelerated_slow_entity_", todas, slo, false);
            }
            if (r.nextInt(300) == 0) {
                int p = r.nextInt(np);
                online[p] = !online[p];
            }
            if (r.nextInt(50) == 0) {
                WorldServer w = ws[r.nextInt(ws.length)];
                if (w.field_72996_f.size() > 5) {
                    Object e = w.field_72996_f.remove(r.nextInt(w.field_72996_f.size()));
                    if (e instanceof EntityPlayer) {
                        w.field_72996_f.add(e);
                    }
                }
            }
            if (r.nextInt(50) == 0) {
                Entity e = criar(r);
                e.uuid = new UUID(semente, 200000 + t);
                ws[r.nextInt(ws.length)].field_72996_f.add(e);
                todas.add(e);
            }
            if (r.nextInt(97) == 0) {
                SkillManager.registrada = r.nextInt(5) != 0;
            }
            if (r.nextInt(131) == 0) {
                SkillTreeManager.desativada = r.nextBoolean();
            }
            if (modo == 2) {
                Atalhos.varredura = rs.nextBoolean();
                Atalhos.diluicao = rs.nextBoolean();
            } else {
                Atalhos.varredura = modo == 1 || modo == 3;
                Atalhos.diluicao = modo == 1 || modo == 4;
            }
            // ---- F1: o metodo dos jogadores
            for (int p = 0; p < np; p++) {
                if (!online[p]) {
                    continue;
                }
                chamadasJogador++;
                if (!acc.isEmpty()) {
                    acelNaoVazio++;
                }
                mp.invoke(handler, ps[p], pr[p]);
            }
            if (modo == 2) {
                Atalhos.varredura = rs.nextBoolean();
                Atalhos.diluicao = rs.nextBoolean();
            }
            // ---- F2: o evento de toda entidade viva
            for (int k = 0; k < 6; k++) {
                Entity e = vivo(todas, r);
                e.field_70173_aa = r.nextInt(1000);
                LivingEvent.LivingUpdateEvent ev = new LivingEvent.LivingUpdateEvent((EntityLivingBase) e);
                chamadasVivo++;
                if (!slo.isEmpty()) {
                    retNaoVazio++;
                }
                int codigo;
                try {
                    mv.invoke(handler, ev);
                    codigo = ev.isCanceled() ? 1 : 3;
                } catch (InvocationTargetException x) {
                    if (x.getCause() instanceof Alcancou72) {
                        codigo = ev.isCanceled() ? 4 : 2;
                        alcancou++;
                    } else {
                        throw x;
                    }
                }
                if (ev.isCanceled()) {
                    cancelados++;
                }
                hash = hash * 1000003 + codigo;
                if (dep != null) {
                    dep.append(codigo).append(',');
                }
            }
            // ---- o que o AM2 deixou para tras (tem de ser igual em todas as variantes)
            long x = t;
            for (Entity e : todas) {
                x = x * 31 + e.updates;
            }
            x = x * 31 + new TreeMap<String, Integer>(acc).toString().hashCode();
            x = x * 31 + new TreeMap<String, Integer>(slo).toString().hashCode();
            for (ExtendedProperties e : pr) {
                x = x * 31 + new TreeMap<String, String>(e.extra_properties).toString().hashCode();
            }
            x = x * 31 + ftick.getInt(null);
            hash = hash * 1000003 + x;
            if (dep != null) {
                StringBuilder up = new StringBuilder();
                for (Entity e : todas) {
                    up.append(e.updates).append('.');
                }
                historico.add("t=" + t + " codigos=" + dep + " acc=" + new TreeMap<String, Integer>(acc) + " slo=" + new TreeMap<String, Integer>(slo)
                        + " tick=" + ftick.getInt(null) + " upd=" + up);
            }
        }
        long upd = 0;
        for (Entity e : todas) {
            upd += e.updates;
        }
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("trace", Long.toHexString(hash));
        m.put("errosEngolidos", (long) (ErroCapturado.contar() - nulos0));
        m.put("chamadasJogador", chamadasJogador);
        m.put("chamadasVivo", chamadasVivo);
        m.put("acelNaoVazio", acelNaoVazio);
        m.put("retNaoVazio", retNaoVazio);
        m.put("atualizacoesExtras", upd);
        m.put("cancelados", cancelados);
        m.put("alcancou72", alcancou);
        m.put("getWorlds", DimensionManager.calls);
        m.put("getSkill", SkillManager.chamadas);
        m.put("isSkillDisabled", SkillTreeManager.chamadas);
        m.put("mapas", "aceleradas=" + new TreeMap<String, Integer>(acc).size() + " retardadas=" + new TreeMap<String, Integer>(slo).size());
        m.put("linha", "semente=" + semente + " iteracoes=" + iteracoes + " modo=" + modo + " taxa=" + taxa + " jogador=" + chamadasJogador
                + " (mapa de aceleradas nao vazio em " + acelNaoVazio + ") vivo=" + chamadasVivo + " (retardadas nao vazio em " + retNaoVazio
                + ") extras=" + upd + " cancelados=" + cancelados + " NPE_engolidos_pelo_AM2=" + (ErroCapturado.contar() - nulos0)
                + " trace=" + Long.toHexString(hash));
        return m;
    }

    /** Um feitico: poe a variavel extra no jogador (o AM2 real a transforma em entrada do mapa) e, as vezes, uma armadilha no alvo. */
    private static void lancar(Random r, ExtendedProperties p, String prefixo, List<Entity> todas, final Map<String, Integer> mapa, boolean acelera) {
        final Entity alvo = vivo(todas, r);
        String uuid = r.nextInt(4) == 0 ? new UUID(-1L, r.nextLong()).toString() : alvo.uuid.toString();
        p.addToExtraVariables(prefixo + (1 + r.nextInt(3)) + "_" + uuid, String.valueOf(10 + r.nextInt(60)));
        int arm = r.nextInt(6);
        if (acelera && arm < 3) {
            final int tipo = arm;
            final UUID outro = new UUID(-2L, r.nextLong());
            alvo.gancho = new Runnable() {
                public void run() {
                    if (tipo == 0) {
                        mapa.remove(alvo.uuid.toString());          // a propria entrada some no meio da varredura
                    } else if (tipo == 1 && !mapa.isEmpty()) {
                        mapa.remove(mapa.keySet().iterator().next()); // outra entrada some
                    } else {
                        mapa.put(outro.toString(), 1);              // entrada nova no meio da varredura
                    }
                }
            };
        }
    }

    private static Entity criar(Random r) {
        return r.nextInt(3) == 0 ? new Entity() : new EntityLivingBase();
    }

    private static Entity vivo(List<Entity> todas, Random r) {
        while (true) {
            Entity e = todas.get(r.nextInt(todas.size()));
            if (e instanceof EntityLivingBase) {
                return e;
            }
        }
    }

    /**
     * Indicacao (NAO e medicao de servidor): tempo por chamada de {@code HandlePlayerSpellExtendedProperties}
     * com o mapa de aceleradas vazio, com {@code mundos} mundos de {@code porMundo} entidades (metade viva).
     * Devolve nanossegundos por chamada.
     */
    public static double bancadaF1(int mundos, int porMundo, int chamadas, boolean ligado) throws Exception {
        ClassLoader cl = Roda.class.getClassLoader();
        Class<?> h = Class.forName("am2.AMEventHandler", true, cl);
        Method mp = h.getDeclaredMethod("HandlePlayerSpellExtendedProperties", EntityPlayer.class, ExtendedProperties.class);
        mp.setAccessible(true);
        Object handler = h.newInstance();
        @SuppressWarnings("unchecked")
        Map<String, Integer> acc = (Map<String, Integer>) h.getField("acceleratedEntitiesUUIDs").get(null);
        acc.clear();
        WorldServer[] ws = new WorldServer[mundos];
        Random r = new Random(5);
        for (int w = 0; w < mundos; w++) {
            ws[w] = new WorldServer();
            for (int i = 0; i < porMundo; i++) {
                ws[w].field_72996_f.add(i % 2 == 0 ? new EntityLivingBase() : new Entity());
            }
        }
        DimensionManager.worlds = ws;
        EntityPlayer p = new EntityPlayer();
        p.field_70170_p = ws[0];
        ws[0].field_73010_i.add(p);
        ExtendedProperties props = new ExtendedProperties();
        Atalhos.varredura = ligado;
        for (int i = 0; i < chamadas / 4; i++) {
            mp.invoke(handler, p, props);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < chamadas; i++) {
            mp.invoke(handler, p, props);
        }
        return (System.nanoTime() - t0) / (double) chamadas;
    }
}
