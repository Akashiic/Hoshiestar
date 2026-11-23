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
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Casos montados a mao do recurso 1.F, rodados contra o {@code am2.AMEventHandler} REAL (original ou
 * transformado, conforme o class loader que carregou esta classe). Cada caso devolve dois tipos de
 * resultado:
 * <ul>
 * <li><b>observaveis</b>: o que um jogador ou outro mod poderia ver (atualizacoes extras de cada entidade,
 * cancelamento do evento, conteudo dos mapas, excecoes engolidas pelo AM2). Tem de ser IGUAL no original,
 * no transformado ligado e no transformado desligado;</li>
 * <li><b>internos</b>: quantas vezes o AM2 chegou a pedir a lista de mundos e a consultar o DiluteTime
 * (e quantas vezes o gancho decidiu pular). E o que muda, e e o que mostra que o atalho agiu.</li>
 * </ul>
 */
public final class Casos {

    /** Observaveis (chave -> valor) em ordem fixa. */
    public final Map<String, String> observaveis = new TreeMap<String, String>();

    /** Internos (chave -> numero). */
    public final Map<String, Long> internos = new TreeMap<String, Long>();

    private Class<?> h;
    private Object handler;
    private Method mp;
    private Method mv;
    private Map<String, Integer> acc;
    private Map<String, Integer> slo;

    private WorldServer w;
    private EntityPlayer jogador;
    private ExtendedProperties props;
    private EntityLivingBase e1, e2, e3;
    private Entity naoVivo;

    public Casos() {
    }

    // ------------------------------------------------------------------ montagem

    @SuppressWarnings("unchecked")
    private void montar() throws Exception {
        h = Class.forName("am2.AMEventHandler", true, Casos.class.getClassLoader());
        handler = h.newInstance();
        mp = h.getDeclaredMethod("HandlePlayerSpellExtendedProperties", EntityPlayer.class, ExtendedProperties.class);
        mp.setAccessible(true);
        mv = h.getMethod("onEntityLiving", LivingEvent.LivingUpdateEvent.class);
        acc = (Map<String, Integer>) h.getField("acceleratedEntitiesUUIDs").get(null);
        slo = (Map<String, Integer>) h.getField("slowedEntitiesUUIDs").get(null);
        java.lang.reflect.Field ftick = h.getDeclaredField("tick");
        ftick.setAccessible(true);
        ftick.setInt(null, 0);
    }

    private void mundo() {
        acc.clear();
        slo.clear();
        SkillManager.registrada = true;
        SkillTreeManager.desativada = false;
        w = new WorldServer();
        e1 = vivo(1);
        e2 = vivo(2);
        e3 = vivo(3);
        naoVivo = new Entity();
        naoVivo.uuid = new UUID(7, 4);
        jogador = new EntityPlayer();
        jogador.uuid = new UUID(7, 100);
        jogador.field_70170_p = w;
        w.field_73010_i.add(jogador);
        w.field_72996_f.add(naoVivo);
        w.field_72996_f.add(e1);
        w.field_72996_f.add(e2);
        w.field_72996_f.add(e3);
        w.field_72996_f.add(jogador);
        DimensionManager.worlds = new WorldServer[] {w};
        props = new ExtendedProperties();
    }

    private EntityLivingBase vivo(int n) {
        EntityLivingBase e = new EntityLivingBase();
        e.uuid = new UUID(7, n);
        return e;
    }

    // ------------------------------------------------------------------ operacoes

    private void jogador() throws Exception {
        mp.invoke(handler, jogador, props);
    }

    /** 1 = cancelado, 2 = seguiu adiante (alcancou o offset 72) sem cancelar. */
    private int vivo(EntityLivingBase e, int tick) throws Exception {
        e.field_70173_aa = tick;
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

    private void obs(String k, Object v) {
        observaveis.put(k, String.valueOf(v));
    }

    private long gw0, sk0, st0, pv0, po0, pd0, pdo0;

    private void zerarInternos() {
        gw0 = DimensionManager.calls;
        sk0 = SkillManager.chamadas;
        st0 = SkillTreeManager.chamadas;
        pv0 = Atalhos.contagem[Atalhos.VARRER_PULOU];
        po0 = Atalhos.contagem[Atalhos.VARRER_ORIGINAL];
        pd0 = Atalhos.contagem[Atalhos.DILUIR_PULOU];
        pdo0 = Atalhos.contagem[Atalhos.DILUIR_ORIGINAL];
    }

    private void interno(String caso) {
        internos.put(caso + "/getWorlds", DimensionManager.calls - gw0);
        internos.put(caso + "/isSkillDisabled", SkillTreeManager.chamadas - st0);
        internos.put(caso + "/getSkill", SkillManager.chamadas - sk0);
        internos.put(caso + "/varrerPulou", Atalhos.contagem[Atalhos.VARRER_PULOU] - pv0);
        internos.put(caso + "/varrerOriginal", Atalhos.contagem[Atalhos.VARRER_ORIGINAL] - po0);
        internos.put(caso + "/diluirPulou", Atalhos.contagem[Atalhos.DILUIR_PULOU] - pd0);
        internos.put(caso + "/diluirOriginal", Atalhos.contagem[Atalhos.DILUIR_ORIGINAL] - pdo0);
    }

    private static void ligar(boolean f1, boolean f2) {
        Atalhos.varredura = f1;
        Atalhos.diluicao = f2;
    }

    // ------------------------------------------------------------------ os casos

    /**
     * Roda todos os casos com os dois atalhos no estado dado (ligados ou desligados; os casos de troca
     * em execucao mexem neles por conta propria) e devolve esta instancia com os resultados.
     */
    public static Casos executar(boolean ligados, Class<?> ponteDe) throws Exception {
        Casos c = new Casos();
        c.montar();
        ErroCapturado.instalar();
        c.mapaVazio(ligados);
        c.mapaComUmaEntrada(ligados);
        c.entradaRemovidaEntreChamadas(ligados);
        c.entradaRemovidaNoMeio(ligados);
        c.interruptorTrocado();
        c.pontes(ponteDe);
        c.outraThread(ligados);
        c.estresse(ligados);
        c.erroNosso(ligados);
        ligar(false, false);
        return c;
    }

    /** Nada acelerado nem retardado: nenhuma entidade muda; com o atalho, o AM2 nem pede a lista de mundos. */
    private void mapaVazio(boolean on) throws Exception {
        mundo();
        ligar(on, on);
        zerarInternos();
        for (int i = 0; i < 5; i++) {
            jogador();
        }
        obs("vazio/atualizacoes", e1.updates + "," + e2.updates + "," + e3.updates + "," + naoVivo.updates + "," + jogador.updates);
        obs("vazio/vivo", vivo(e1, 7) + "," + vivo(e2, 8) + "," + vivo(jogador, 9));
        obs("vazio/mapas", acc.size() + "," + slo.size());
        interno("vazio");
    }

    /** Uma entrada: acelerada (poder 2) e retardada (n = 2). O original roda inteiro e o resultado e o do AM2. */
    private void mapaComUmaEntrada(boolean on) throws Exception {
        mundo();
        ligar(on, on);
        acc.put(e1.uuid.toString(), 2);
        slo.put(e2.uuid.toString(), 2);
        zerarInternos();
        for (int i = 0; i < 3; i++) {
            jogador();
        }
        obs("uma/atualizacoes", e1.updates + "," + e2.updates + "," + e3.updates + "," + jogador.updates);
        // retardada n=2: com ticksExisted impar o evento e cancelado; com par segue
        obs("uma/retardada", vivo(e2, 3) + "," + vivo(e2, 4) + "," + vivo(e2, 5) + "," + vivo(e1, 3) + "," + vivo(e3, 5));
        // habilidade DiluteTime desativada: o AM2 nao olha o mapa (nunca cancela); ausente: olha
        SkillTreeManager.desativada = true;
        obs("uma/desativada", vivo(e2, 3) + "," + vivo(e2, 5));
        SkillTreeManager.desativada = false;
        SkillManager.registrada = false;
        obs("uma/naoRegistrada", vivo(e2, 3) + "," + vivo(e2, 4));
        SkillManager.registrada = true;
        obs("uma/mapas", new TreeMap<String, Integer>(acc) + "|" + new TreeMap<String, Integer>(slo));
        interno("uma");
    }

    /** A entrada some ENTRE duas chamadas: a primeira roda o original, a seguinte (mapa vazio) e pulada. */
    private void entradaRemovidaEntreChamadas(boolean on) throws Exception {
        mundo();
        ligar(on, on);
        acc.put(e1.uuid.toString(), 3);
        slo.put(e1.uuid.toString(), 2);
        zerarInternos();
        jogador();
        obs("entre/depoisDaPrimeira", e1.updates + "," + vivo(e1, 3));
        acc.remove(e1.uuid.toString());
        slo.remove(e1.uuid.toString());
        jogador();
        obs("entre/depoisDaSegunda", e1.updates + "," + vivo(e1, 3));
        acc.put(e1.uuid.toString(), 1);
        jogador();
        obs("entre/depoisDaTerceira", e1.updates);
        interno("entre");
    }

    /** A entrada some NO MEIO da varredura (uma entidade atualizada pelo Accelerate mexe no mapa). */
    private void entradaRemovidaNoMeio(boolean on) throws Exception {
        mundo();
        ligar(on, on);
        // e1 vem antes de e2 na lista: ao ser atualizada, tira a entrada de e2
        acc.put(e1.uuid.toString(), 2);
        acc.put(e2.uuid.toString(), 2);
        e1.gancho = new Runnable() {
            public void run() {
                acc.remove(e2.uuid.toString());
            }
        };
        zerarInternos();
        jogador();
        obs("meio/outraEntrada", e1.updates + "," + e2.updates + "," + acc.size());
        // agora so a de e1: ela mesma se tira na primeira atualizacao (o AM2 real tropeca num NullPointerException
        // dentro do proprio try e a imprime; o harness conta as impressoes)
        e1.gancho = new Runnable() {
            public void run() {
                acc.remove(e1.uuid.toString());
            }
        };
        e1.updates = 0;
        int antes = ErroCapturado.contar();
        jogador();
        obs("meio/propriaEntrada", e1.updates + "," + e2.updates + "," + acc.size() + " erros_engolidos=" + (ErroCapturado.contar() - antes));
        // mapa agora vazio: a chamada seguinte nao tem o que varrer
        e1.gancho = null;
        jogador();
        obs("meio/depois", e1.updates + "," + e2.updates + "," + acc.size());
        interno("meio");
    }

    /** Os interruptores trocados em execucao entre uma chamada e outra (mapa vazio e depois com entrada). */
    private void interruptorTrocado() throws Exception {
        mundo();
        zerarInternos();
        ligar(true, true);
        jogador();
        vivo(e1, 1);
        ligar(false, false);
        jogador();
        vivo(e1, 1);
        ligar(true, false);
        jogador();
        vivo(e1, 1);
        ligar(false, true);
        jogador();
        vivo(e1, 1);
        ligar(true, true);
        acc.put(e1.uuid.toString(), 2);
        slo.put(e1.uuid.toString(), 2);
        jogador();                    // mapas com entrada: o original, mesmo com os atalhos ligados
        obs("troca/atualizacoes", e1.updates);
        obs("troca/vivo", vivo(e1, 3) + "," + vivo(e1, 4));
        ligar(false, false);
        acc.remove(e1.uuid.toString());
        slo.remove(e1.uuid.toString());
        jogador();
        ligar(true, true);
        jogador();
        obs("troca/final", e1.updates + "," + vivo(e1, 3));
        interno("troca");
    }

    /**
     * F2 passo a passo: A (getSkill) e B (isSkillDisabled) pelas pontes REAIS da classe transformada, com o
     * interruptor trocado ENTRE os dois, em todas as combinacoes, contra a conta do original. So na
     * variante transformada ({@code ponteDe} nao nulo).
     */
    private void pontes(Class<?> ponteDe) throws Exception {
        if (ponteDe == null) {
            return;
        }
        Method a = null;
        Method b = null;
        for (Method m : ponteDe.getDeclaredMethods()) {
            if (!m.getName().startsWith("hoshiestar$envolve$")) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == SkillManager.class && p[1] == String.class) {
                a = m;
            } else if (p.length == 2 && p[0] == SkillTreeManager.class && p[1] == am2.api.spell.component.interfaces.ISkillTreeEntry.class) {
                b = m;
            }
        }
        if (a == null || b == null) {
            observaveis.put("pontes/achadas", "NAO");
            return;
        }
        a.setAccessible(true);
        b.setAccessible(true);
        mundo();
        int combos = 0;
        int erros = 0;
        for (int mapaCheio = 0; mapaCheio < 2; mapaCheio++) {
            for (int registrada = 0; registrada < 2; registrada++) {
                for (int desativada = 0; desativada < 2; desativada++) {
                    for (int swA = 0; swA < 2; swA++) {
                        for (int swB = 0; swB < 2; swB++) {
                            slo.clear();
                            if (mapaCheio == 1) {
                                slo.put(e1.uuid.toString(), 2);
                            }
                            SkillManager.registrada = registrada == 1;
                            SkillTreeManager.desativada = desativada == 1;
                            for (int tick = 3; tick <= 4; tick++) {
                                // a conta do original
                                boolean dis = SkillTreeManager.instance.isSkillDisabled(SkillManager.instance.getSkill("DiluteTime"));
                                boolean esperado = !dis && slo.containsKey(e1.uuid.toString()) && tick % (slo.containsKey(e1.uuid.toString()) ? slo.get(e1.uuid.toString()) : 1) != 0;
                                // a conta com as pontes e o interruptor trocado entre A e B
                                ligar(false, swA == 1);
                                Object entrada = a.invoke(null, SkillManager.instance, "DiluteTime");
                                ligar(false, swB == 1);
                                boolean dis2 = (Boolean) b.invoke(null, SkillTreeManager.instance, entrada);
                                boolean obtido = !dis2 && slo.containsKey(e1.uuid.toString()) && tick % (slo.containsKey(e1.uuid.toString()) ? slo.get(e1.uuid.toString()) : 1) != 0;
                                combos++;
                                if (obtido != esperado) {
                                    erros++;
                                }
                            }
                        }
                    }
                }
            }
        }
        SkillManager.registrada = true;
        SkillTreeManager.desativada = false;
        slo.clear();
        ligar(false, false);
        observaveis.put("pontes/combinacoes", combos + " erros=" + erros);
    }

    /** De outra thread (nao a do servidor) os atalhos nunca pulam: roda o original. */
    private void outraThread(boolean on) throws Exception {
        mundo();
        ligar(on, on);
        zerarInternos();
        final Exception[] falha = new Exception[1];
        final int[] codigos = new int[2];
        long outra0 = Atalhos.contagem[Atalhos.OUTRA_THREAD];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    jogador();
                    codigos[0] = vivo(e1, 7);
                    codigos[1] = vivo(e2, 8);
                } catch (Exception x) {
                    falha[0] = x;
                }
            }
        }, "outra-thread");
        t.start();
        t.join();
        if (falha[0] != null) {
            throw falha[0];
        }
        obs("outraThread/vivo", codigos[0] + "," + codigos[1]);
        interno("outraThread");
        internos.put("outraThread/contadorOutraThread", Atalhos.contagem[Atalhos.OUTRA_THREAD] - outra0);
    }

    /**
     * Uma thread troca os interruptores sem parar enquanto a thread do servidor chama os dois metodos com
     * o mapa vazio e depois com uma entrada. O resultado nunca pode sair diferente do original.
     */
    private void estresse(boolean on) throws Exception {
        mundo();
        final AtomicBoolean parar = new AtomicBoolean();
        Thread t = new Thread(new Runnable() {
            public void run() {
                Random r = new Random(1);
                while (!parar.get()) {
                    ligar(r.nextBoolean(), r.nextBoolean());
                }
            }
        }, "troca-interruptores");
        t.setDaemon(true);
        t.start();
        long erros = 0;
        long chamadas = 0;
        try {
            for (int fase = 0; fase < 2; fase++) {
                slo.clear();
                acc.clear();
                if (fase == 1) {
                    slo.put(e1.uuid.toString(), 2);
                    acc.put(e2.uuid.toString(), 1);
                }
                e2.updates = 0;
                for (int i = 0; i < 60000; i++) {
                    int c1 = vivo(e1, 3);    // retardada n=2 e tick impar: cancela se a entrada existe
                    int c3 = vivo(e3, 3);    // sem entrada: nunca cancela
                    int esp1 = fase == 1 ? 1 : 2;
                    chamadas += 2;
                    if (c1 != esp1 || c3 != 2) {
                        erros++;
                    }
                    if (i % 30 == 0) {
                        jogador();
                    }
                }
                long esperado = fase == 1 ? 2000 : 0;   // 60000/30 chamadas do jogador, 1 atualizacao extra cada
                if (e2.updates != esperado) {
                    erros++;
                }
            }
        } finally {
            parar.set(true);
            t.join();
            ligar(false, false);
        }
        obs("estresse/divergencias", erros);
        internos.put("estresse/chamadas", chamadas);
    }

    /** Um erro nosso (o mapa lanca excecao em isEmpty): o atalho se desliga e o AM2 segue pelo caminho original. */
    @SuppressWarnings("unchecked")
    private void erroNosso(boolean on) throws Exception {
        mundo();
        ligar(on, on);
        zerarInternos();
        long e0 = Atalhos.contagem[Atalhos.ERROS];
        Atalhos.varreduraDesligadaPorErro = null;
        Atalhos.diluicaoDesligadaPorErro = null;
        Map<String, Integer> origAcc = acc;
        Map<String, Integer> origSlo = slo;
        java.lang.reflect.Field fa = h.getField("acceleratedEntitiesUUIDs");
        java.lang.reflect.Field fs = h.getField("slowedEntitiesUUIDs");
        acc = new HashMap<String, Integer>() {
            @Override
            public boolean isEmpty() {
                throw new IllegalStateException("erro de teste (F1)");
            }
        };
        slo = new HashMap<String, Integer>() {
            @Override
            public boolean isEmpty() {
                throw new IllegalStateException("erro de teste (F2)");
            }
        };
        fa.set(null, acc);
        fs.set(null, slo);
        try {
            jogador();
            jogador();
            int c1 = vivo(e1, 3);
            int c2 = vivo(e2, 4);
            obs("erro/resultado", e1.updates + "," + c1 + "," + c2);
        } finally {
            fa.set(null, origAcc);
            fs.set(null, origSlo);
            acc = origAcc;
            slo = origSlo;
        }
        interno("erro");
        internos.put("erro/erros", Atalhos.contagem[Atalhos.ERROS] - e0);
        internos.put("erro/varreduraDesligada", Atalhos.varreduraDesligadaPorErro != null ? 1L : 0L);
        internos.put("erro/diluicaoDesligada", Atalhos.diluicaoDesligadaPorErro != null ? 1L : 0L);
        Atalhos.varreduraDesligadaPorErro = null;
        Atalhos.diluicaoDesligadaPorErro = null;
        ligar(false, false);
    }
}
