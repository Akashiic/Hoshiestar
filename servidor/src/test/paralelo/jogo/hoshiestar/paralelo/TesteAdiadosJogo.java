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
package hoshiestar.paralelo;

import hoshiestar.teste.Verifica;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEventData;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.WorldInfo;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.SimplePluginManager;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static hoshiestar.paralelo.ApoioJogo.REG;
import static hoshiestar.paralelo.ApoioJogo.cru;
import static hoshiestar.paralelo.ApoioJogo.ler;
import static hoshiestar.paralelo.ApoioJogo.por;

/**
 * Passo 4 (revisao, I1): os 15 pontos adiados de {@code GanchosAdiados} e o {@code callEvent} de {@code GanchosEventos},
 * nas classes REAIS do build rapido (World, WorldServer, ChunkProviderServer e SimplePluginManager com os patches), sem
 * subir o servidor. Cada ponto e chamado pelo metodo do jogo (o patch chama o gancho), primeiro com a fase FECHADA (a
 * referencia: o tick em serie de hoje), depois com um lote aberto (nada roda na hora, tudo vai para o lote, na ordem), e a
 * juncao tem de deixar o mundo falso IGUAL a referencia: os mesmos avisos, na ordem e com os mesmos argumentos, os eventos
 * de bloco com a deduplicacao do original, os ticks agendados (o primeiro vence; tick em chunk ausente nao entra pelo
 * {@code scheduleBlockUpdate}, entra pelo {@code func_147446_b}) e o chunk fora da fila de descarga.
 *
 * <p>Argumento: {@code true} (o hoshiestar.yml da pasta liga o paralelo) ou {@code false} (desligado: os patches nem chamam
 * os ganchos, e tudo roda na hora mesmo com o lote aberto: o comportamento original).
 */
public final class TesteAdiadosJogo {

    static Verifica v;
    static WorldServer ws;
    static ChunkProviderServer cps;
    static Block bloco;
    static EntityItem entidade;
    static EntityPlayerMP jogador;
    static org.bukkit.craftbukkit.v1_7_R4.util.LongHashSet fila;

    /** As chamadas, na ordem; devolve quantas (cada uma vira um adiado com o lote aberto). */
    static int chamar() {
        ws.markBlockForUpdate(1, 2, 3);
        ws.markBlockRangeForRenderUpdate(1, 2, 3, 4, 5, 6);
        ws.func_147479_m(7, 8, 9);
        ws.playSoundAtEntity(entidade, "som.entidade", 0.25f, 1.5f);
        ws.playSoundToNearExcept(jogador, "som.perto", 0.5f, 1.25f);
        ws.playSoundEffect(1.5, 2.5, 3.5, "som.a", 0.5f, 0.75f);
        ws.playRecord("disco", 4, 5, 6);
        ws.spawnParticle("fumaca", 1, 2, 3, 4, 5, 6);
        ws.playBroadcastSound(1013, 1, 2, 3, 0);
        ws.playAuxSFXAtEntity(null, 2001, 5, 6, 7, 99);
        ws.playAuxSFX(1000, 8, 9, 10, 1); // passa por playAuxSFXAtEntity: um adiado so
        ws.destroyBlockInWorldPartially(42, 1, 2, 3, 7);
        ws.addBlockEvent(3, 4, 5, bloco, 1, 2);
        ws.addBlockEvent(3, 4, 5, bloco, 1, 2); // repetido: a deduplicacao do original
        ws.addBlockEvent(5, 4, 3, bloco, 1, 0);
        ws.scheduleBlockUpdateWithPriority(2, 3, 4, bloco, 10, 1);
        ws.scheduleBlockUpdate(2, 3, 4, bloco, 20); // mesma posicao: o primeiro vence
        ws.scheduleBlockUpdate(100, 3, 100, bloco, 5); // chunk (6, 6) ausente: o original nao agenda
        ws.func_147446_b(6, 7, 8, bloco, 5, 0);
        ws.func_147446_b(200, 3, 200, bloco, 5, 2); // chunk ausente: este metodo agenda assim mesmo
        return 20;
    }

    static String estado() throws Exception {
        Object[] ev = (Object[]) ler(ws, "field_147490_S");
        List<String> evs = new ArrayList<String>();
        for (Object e : (List<?>) ev[0]) {
            BlockEventData d = (BlockEventData) e;
            evs.add(d.func_151340_a() + "," + d.func_151342_b() + "," + d.func_151341_c() + ":" + d.getEventID() + "/" + d.getEventParameter());
        }
        List<String> ag = new ArrayList<String>();
        for (Object o : (Set<?>) ler(ws, "pendingTickListEntriesHashSet")) {
            NextTickListEntry n = (NextTickListEntry) o;
            ag.add(n.xCoord + "," + n.yCoord + "," + n.zCoord + "@" + n.scheduledTime + "p" + n.priority);
        }
        return "acessos=" + REG + " eventos=" + evs + " agendados=" + ag + " arvore=" + ((Set<?>) ler(ws, "pendingTickListEntriesTreeSet")).size();
    }

    static void zerar() throws Exception {
        REG.clear();
        Object[] ev = (Object[]) ler(ws, "field_147490_S");
        ((List<?>) ev[0]).clear();
        ((List<?>) ev[1]).clear();
        ((Set<?>) ler(ws, "pendingTickListEntriesHashSet")).clear();
        ((Set<?>) ler(ws, "pendingTickListEntriesTreeSet")).clear();
    }

    static void montar() throws Exception {
        ws = cru(WorldServer.class);
        cps = cru(ChunkProviderServer.class);
        bloco = cru(Block.class);
        por(ws, "worldAccesses", new ArrayList<Object>(Arrays.asList(ApoioJogo.ACESSO)));
        por(ws, "isRemote", false);
        por(ws, "worldInfo", cru(WorldInfo.class));
        por(ws, "scheduledUpdatesAreImmediate", false);
        Class<?> lista = Class.forName("net.minecraft.world.WorldServer$ServerBlockEventList");
        Constructor<?> k = lista.getDeclaredConstructor(Object.class);
        k.setAccessible(true);
        Object arr = Array.newInstance(lista, 2);
        Array.set(arr, 0, k.newInstance((Object) null));
        Array.set(arr, 1, k.newInstance((Object) null));
        por(ws, "field_147490_S", arr);
        por(ws, "blockEventCacheIndex", 0);
        por(ws, "pendingTickListEntriesHashSet", Collections.synchronizedSet(new LinkedHashSet<Object>()));
        por(ws, "pendingTickListEntriesTreeSet", new TreeSet<Object>());
        thermos.wrapper.ChunkBlockHashMap mapa = new thermos.wrapper.ChunkBlockHashMap();
        Chunk c00 = cru(Chunk.class); // so o chunk (0, 0) presente
        por(c00, "xPosition", 0);
        por(c00, "zPosition", 0);
        mapa.put(c00);
        por(cps, "loadedChunkHashMap_KC", new thermos.wrapper.VanillaChunkHashMap(mapa));
        fila = new org.bukkit.craftbukkit.v1_7_R4.util.LongHashSet();
        por(cps, "chunksToUnload", fila);
        por(cps, "worldObj", ws);
        por(ws, "chunkProvider", cps);
        por(ws, "theChunkProviderServer", cps);
        entidade = cru(EntityItem.class);
        por(entidade, "posX", 10.5);
        por(entidade, "posY", 64.0);
        por(entidade, "posZ", -3.5);
        por(entidade, "yOffset", 0.125f);
        jogador = cru(EntityPlayerMP.class);
        por(jogador, "posX", -20.5);
        por(jogador, "posY", 70.0);
        por(jogador, "posZ", 30.25);
        por(jogador, "yOffset", 1.62f);
    }

    public static void main(String[] args) throws Exception {
        boolean esperaLigado = Boolean.parseBoolean(args[0]);
        ApoioJogo.carregarConfig();
        v = new Verifica("TesteAdiadosJogo (Paralelo.LIGADO=" + Paralelo.LIGADO + ", esperado " + esperaLigado + ")");
        v.igual(esperaLigado, Paralelo.LIGADO, "o hoshiestar.yml da pasta de trabalho deu o LIGADO esperado");
        montar();

        // ---- A. referencia: fase FECHADA (o jogo fora da fase): tudo roda na hora
        Guardas.servidor = Thread.currentThread();
        Guardas.fase = Guardas.FECHADA;
        int n = chamar();
        String ref = estado();
        System.out.println("  referencia: " + ref);
        v.igual(12, REG.size(), "fase fechada: os 12 avisos ao cliente rodam na hora");
        v.sim(ref.contains("100,3,100") == false && ref.contains("200,3,200@5p2"),
                "fase fechada: scheduleBlockUpdate em chunk ausente nao agenda; func_147446_b agenda");
        fila.add(0, 0);
        Chunk r0 = cps.loadChunk(0, 0);
        v.sim(r0 != null && !fila.contains(0, 0), "fase fechada: loadChunk com o chunk presente devolve o chunk e tira da fila de descarga");

        // ---- B. lote aberto (sombra_ordenada): nada roda na hora, tudo vai para o lote, na ordem
        zerar();
        Guardas.fase = Guardas.MONTANDO;
        Lote lote = new Lote(0, OrdenadaJogo.TICADOR);
        OrdenadaJogo.CONTEXTO.abrir(lote);
        chamar();
        fila.add(0, 0);
        Chunk r1 = cps.loadChunk(0, 0);
        String durante = estado();
        v.sim(r1 == r0, "lote aberto: loadChunk com o chunk presente devolve o chunk presente");
        if (!esperaLigado) {
            v.igual(ref, durante, "DESLIGADO com o lote aberto: tudo rodou na hora, igual a referencia (comportamento original)");
            v.sim(!fila.contains(0, 0), "DESLIGADO: o chunk saiu da fila de descarga na hora");
            v.igual(0, lote.adiados().size(), "DESLIGADO: nenhum adiado anotado");
            OrdenadaJogo.CONTEXTO.fechar();
            Guardas.fase = Guardas.FECHADA;
            eventos(false);
            v.fim();
            return;
        }
        v.sim(REG.isEmpty(), "lote aberto: nenhum aviso ao cliente rodou na hora (" + REG + ")");
        v.sim(durante.contains("eventos=[]") && durante.contains("agendados=[]") && durante.contains("arvore=0"),
                "lote aberto: nenhum evento de bloco nem tick agendado entrou na fila na hora");
        v.sim(fila.contains(0, 0), "lote aberto: o chunk continua na fila de descarga (a saida foi adiada)");
        v.igual(n + 1, lote.adiados().size(), "lote aberto: um adiado por chamada, mais o 'nao descarregar'");
        // outra thread nunca adia
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ws.playRecord("de_fora", 9, 9, 9);
            }
        }, "thread estranha");
        t.start();
        t.join();
        v.sim(REG.size() == 1 && REG.get(0).startsWith("playRecord de_fora") && lote.adiados().size() == n + 1,
                "lote aberto: chamada de outra thread roda na hora e nao entra no lote");
        REG.clear();
        v.sim(!GanchosAdiados.naoDescarregar(cps, 5, 5, null), "lote aberto: loadChunk de chunk AUSENTE nao e adiado (gancho devolve falso)");
        v.sim(!GanchosAdiados.naoDescarregar(cps, 0, 0, new Runnable() {
            @Override
            public void run() {
            }
        }), "lote aberto: loadChunk com Runnable (assincrono) nao e adiado");
        int antesFechar = lote.adiados().size();
        OrdenadaJogo.CONTEXTO.fechar();
        v.sim(GanchosParalelo.loteDaVez == null && !Guardas.loteAberto && Guardas.regiaoAberta == -1, "fechar: contexto do lote fechado");

        // ---- C. juncao: fase FECHADA, aplica na ordem, pelo metodo original
        Guardas.fase = Guardas.FECHADA;
        Juncao.Resultado res = Juncao.emergencia(Arrays.asList(lote));
        String depois = estado();
        System.out.println("  depois da juncao: " + depois);
        v.sim(res.adiadosAplicados == antesFechar && !res.temFalha(),
                "juncao: " + antesFechar + " adiados aplicados, sem falha (" + res.adiadosAplicados + ", falhas " + res.falhas + ")");
        v.igual(ref, depois, "juncao: avisos (ordem e argumentos), eventos de bloco (deduplicados) e ticks agendados IGUAIS ao tick em serie");
        v.sim(!fila.contains(0, 0), "juncao: o chunk saiu da fila de descarga");
        v.igual(antesFechar, lote.adiados().size(), "juncao: aplicar nao anota adiado novo (sem laco)");
        Juncao.Resultado res2 = Juncao.emergencia(Arrays.asList(lote));
        v.sim(res2.adiadosAplicados == 0 && estado().equals(ref), "juncao de novo: nada repetido");

        eventos(true);
        v.fim();
    }

    // ---- D. callEvent (SPM:482): sem ouvinte, com lote aberto, volta sem o monitor; o resto como o original

    static SimplePluginManager spm;

    /** Outra thread segura o monitor do SimplePluginManager ate {@link #soltar}. */
    static final Object TRAVA = new Object();
    static boolean segurando, soltar;
    static Thread dono;

    static void segurarMonitor() throws InterruptedException {
        segurando = false;
        soltar = false;
        dono = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (spm) {
                    synchronized (TRAVA) {
                        segurando = true;
                        TRAVA.notifyAll();
                        while (!soltar) {
                            try {
                                TRAVA.wait();
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                }
            }
        }, "dono do monitor");
        dono.start();
        synchronized (TRAVA) {
            while (!segurando) {
                TRAVA.wait();
            }
        }
    }

    static void soltarMonitor() throws InterruptedException {
        synchronized (TRAVA) {
            soltar = true;
            TRAVA.notifyAll();
        }
        dono.join(5000);
    }

    /** Resultado de uma chamada feita por uma thread que faz o papel da thread do servidor. */
    static final class Chamada {
        volatile boolean voltou;
        volatile Throwable erro;
        Thread t;
    }

    /** Chama callEvent numa thread nova que passa a ser a "thread do servidor" (Guardas.servidor). */
    static Chamada chamarComoServidor(final Event e, long esperaMs) throws InterruptedException {
        final Chamada c = new Chamada();
        c.t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    spm.callEvent(e);
                } catch (Throwable x) {
                    c.erro = x;
                }
                c.voltou = true;
            }
        }, "servidor do teste");
        Guardas.servidor = c.t;
        c.t.start();
        c.t.join(esperaMs);
        return c;
    }

    static void eventos(boolean ligado) throws Exception {
        spm = cru(SimplePluginManager.class);
        por(spm, "server", Proxy.newProxyInstance(TesteAdiadosJogo.class.getClassLoader(), new Class<?>[] {org.bukkit.Server.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object p, Method m, Object[] a) {
                        if (m.getName().equals("isPrimaryThread")) {
                            return Boolean.TRUE; // quem chama e a thread do servidor
                        }
                        return padrao(m.getReturnType());
                    }
                }));
        EventoComOuvinte.HANDLERS.register(new RegisteredListener(new Listener() {
        }, new EventExecutor() {
            @Override
            public void execute(Listener l, Event e) {
                EventoComOuvinte.chamadas++;
            }
        }, EventPriority.NORMAL, plugin(), false));
        Thread servidorAntes = Guardas.servidor;

        // D1. sincrono, sem ouvinte, lote aberto, monitor preso por outra thread
        Guardas.fase = Guardas.MONTANDO;
        OrdenadaJogo.CONTEXTO.abrir(new Lote(0, OrdenadaJogo.TICADOR));
        segurarMonitor();
        Chamada d1 = chamarComoServidor(new EventoSemOuvinte(), 700);
        if (ligado) {
            v.sim(d1.voltou && d1.erro == null, "callEvent sincrono sem ouvinte, lote aberto: volta sem entrar no synchronized (outra thread segura o monitor)");
        } else {
            v.sim(!d1.voltou, "DESLIGADO: callEvent sem ouvinte espera o monitor, como o original");
        }
        // D2. sincrono COM ouvinte, lote aberto: entra no synchronized (espera o monitor) e chama o ouvinte
        Chamada d2 = chamarComoServidor(new EventoComOuvinte(), 700);
        v.sim(!d2.voltou, "callEvent sincrono COM ouvinte, lote aberto: espera o monitor (nao pula o fireEvent)");
        soltarMonitor();
        d1.t.join(5000);
        d2.t.join(5000);
        v.sim(d2.voltou && d2.erro == null && EventoComOuvinte.chamadas == 1, "callEvent com ouvinte: o ouvinte foi chamado uma vez quando o monitor foi solto ("
                + EventoComOuvinte.chamadas + ")");
        // D3. assincrono, sem ouvinte, lote aberto, chamado pela thread do servidor: o original recusa com IllegalStateException
        Chamada d3 = chamarComoServidor(new EventoAssincrono(), 5000);
        v.sim(d3.voltou && d3.erro instanceof IllegalStateException,
                "callEvent ASSINCRONO sem ouvinte, lote aberto: nao e pulado; o original recusa (thread do servidor) (" + d3.erro + ")");
        OrdenadaJogo.CONTEXTO.fechar();
        // D4. sem lote: espera o monitor, como o original
        segurarMonitor();
        Chamada d4 = chamarComoServidor(new EventoSemOuvinte(), 700);
        v.sim(!d4.voltou, "callEvent sem lote aberto: espera o monitor, como o original");
        soltarMonitor();
        d4.t.join(5000);
        v.sim(d4.voltou && d4.erro == null, "callEvent sem lote: volta quando o monitor e solto");
        Guardas.servidor = servidorAntes;
        Guardas.fase = Guardas.FECHADA;
    }

    static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(TesteAdiadosJogo.class.getClassLoader(), new Class<?>[] {Plugin.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("isEnabled")) {
                    return Boolean.TRUE;
                }
                if (m.getName().equals("getName")) {
                    return "teste";
                }
                if (m.getName().equals("hashCode")) {
                    return 1;
                }
                if (m.getName().equals("equals")) {
                    return p == a[0];
                }
                return padrao(m.getReturnType());
            }
        });
    }

    static Object padrao(Class<?> t) {
        if (t == boolean.class) {
            return Boolean.FALSE;
        }
        if (t == int.class) {
            return 0;
        }
        if (t == long.class) {
            return 0L;
        }
        if (t == double.class) {
            return 0.0;
        }
        if (t == float.class) {
            return 0f;
        }
        if (t == short.class) {
            return (short) 0;
        }
        if (t == byte.class) {
            return (byte) 0;
        }
        if (t == char.class) {
            return (char) 0;
        }
        return null;
    }

    public static class EventoSemOuvinte extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static class EventoComOuvinte extends Event {
        static final HandlerList HANDLERS = new HandlerList();
        static volatile int chamadas;

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static class EventoAssincrono extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        public EventoAssincrono() {
            super(true);
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}
