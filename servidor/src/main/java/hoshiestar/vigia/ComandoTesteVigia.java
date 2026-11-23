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
package hoshiestar.vigia;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import hoshiestar.comando.ComandoHoshie;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Subcomando de TESTE do vigia: provoca um tick lento de verdade, de forma controlada, numa copia de
 * teste. {@code hoshie vigia-teste <comando|tile|entidade> <ms>}:
 * <ul>
 * <li>{@code comando}: a propria thread do servidor dorme N ms dentro do comando (que roda no tick);</li>
 * <li>{@code tile}: poe na lista de tiles do mundo 0 um tile de teste (sem bloco, y=255 sobre o spawn,
 * nunca salvo: nao esta no mapa do chunk) que dorme N ms no PRIMEIRO updateEntity e se invalida
 * (o laco de tiles o tira da lista no tick seguinte). Ele passa pela chamada de update do laco de
 * tiles, a mesma que o Akashic-WorldFirewall troca, entre as marcacoes do vigia;</li>
 * <li>{@code entidade}: arma um ouvinte de LivingUpdateEvent (registrado so com a propriedade de teste)
 * que dorme N ms na atualizacao da PRIMEIRA entidade viva que nao e jogador, no mundo 0. E preciso
 * haver uma entidade viva ativa (ex.: um 'summon Pig' perto de um jogador).</li>
 * <li>{@code jogador}: o mesmo ouvinte dorme N ms na atualizacao do PRIMEIRO jogador; ela roda dentro do
 * tratamento do pacote de movimento (C03), entao o relatorio mostra o ultimo pacote e o jogador.</li>
 * </ul>
 * So existe com {@code -Dhoshiestar.vigia.teste=true} (desligado por padrao); so o console pode usar.
 * Nunca ligue isso em producao.
 */
public final class ComandoTesteVigia implements ComandoHoshie.Subcomando {

    public static final String PROPRIEDADE = "hoshiestar.vigia.teste";

    private static final int MAX_MS = 120000;

    /** ms a dormir na proxima entidade viva (0 = desarmado). */
    static final AtomicLong ENTIDADE_MS = new AtomicLong();

    /** ms a dormir na proxima atualizacao de jogador (0 = desarmado). */
    static final AtomicLong JOGADOR_MS = new AtomicLong();

    private ComandoTesteVigia() {
    }

    /** Chamado por hoshiestar.Servidor.iniciando (servidor de pe). */
    public static void registrarSePedido() {
        if (!Boolean.getBoolean(PROPRIEDADE)) {
            return;
        }
        ComandoHoshie.registrar(new ComandoTesteVigia());
        MinecraftForge.EVENT_BUS.register(new Ouvinte());
        Vigia.LOG.a("subcomando de TESTE 'hoshie vigia-teste' registrado (-D" + PROPRIEDADE + "=true); nao use em producao");
    }

    @Override
    public String nome() {
        return "vigia-teste";
    }

    @Override
    public String ajuda() {
        return "vigia-teste <comando|tile|entidade|jogador> <ms> - TESTE: provoca um tick lento (so console)";
    }

    @Override
    public void executar(CommandSender quem, String[] args) {
        if (!(quem instanceof ConsoleCommandSender)) {
            quem.sendMessage("vigia-teste: so no console.");
            return;
        }
        int ms;
        try {
            ms = Integer.parseInt(args[1]);
        } catch (RuntimeException e) {
            ms = -1;
        }
        String modo = args.length > 0 ? args[0] : "";
        if (ms < 1 || ms > MAX_MS || !(modo.equals("comando") || modo.equals("tile") || modo.equals("entidade") || modo.equals("jogador"))) {
            quem.sendMessage("uso: hoshie vigia-teste <comando|tile|entidade|jogador> <ms, 1 a " + MAX_MS + ">");
            return;
        }
        if (modo.equals("comando")) {
            Vigia.LOG.a("TESTE: thread " + Thread.currentThread().getName() + " dorme " + ms + " ms no comando");
            dormir(ms);
            Vigia.LOG.a("TESTE: fim do sono no comando");
        } else if (modo.equals("tile")) {
            WorldServer w = DimensionManager.getWorld(0);
            if (w == null) {
                quem.sendMessage("vigia-teste: mundo 0 nao carregado");
                return;
            }
            ChunkCoordinates s = w.getSpawnPoint();
            TileTeste t = new TileTeste(ms);
            t.setWorldObj(w);
            t.xCoord = s.posX;
            t.yCoord = 255;
            t.zCoord = s.posZ;
            w.func_147448_a(Collections.singletonList(t)); // addTileEntities: entra na lista de tiles do mundo
            Vigia.LOG.a("TESTE: tile de teste em " + t.xCoord + " " + t.yCoord + " " + t.zCoord + " dim 0 vai dormir " + ms + " ms no proximo update");
        } else if (modo.equals("jogador")) {
            JOGADOR_MS.set(ms);
            Vigia.LOG.a("TESTE: a proxima atualizacao de jogador (pacote de movimento) vai dormir " + ms + " ms");
        } else {
            ENTIDADE_MS.set(ms);
            Vigia.LOG.a("TESTE: a proxima entidade viva (nao jogador) do mundo 0 vai dormir " + ms + " ms na atualizacao");
        }
    }

    static void dormir(long ms) {
        long fim = System.nanoTime() + ms * 1000000L;
        for (;;) {
            long falta = fim - System.nanoTime();
            if (falta <= 0L) {
                return;
            }
            try {
                Thread.sleep(Math.max(1L, falta / 1000000L));
            } catch (InterruptedException e) {
                // segue ate o fim do prazo: e para simular um tick lento
            }
        }
    }

    /** Tile de teste: dorme no primeiro update e se invalida. Nao tem bloco e nao entra no mapa do chunk (nunca salvo). */
    public static final class TileTeste extends TileEntity {
        private final long ms;
        private boolean feito;

        TileTeste(long ms) {
            this.ms = ms;
        }

        @Override
        public void updateEntity() {
            if (!feito) {
                feito = true;
                Vigia.LOG.a("TESTE: tile de teste dormindo " + ms + " ms");
                dormir(ms);
            }
            invalidate();
        }
    }

    /** Ouvinte de LivingUpdateEvent (so registrado com a propriedade de teste). */
    public static final class Ouvinte {
        @SubscribeEvent
        public void aoAtualizar(LivingEvent.LivingUpdateEvent ev) {
            if (JOGADOR_MS.get() != 0L && ev.entityLiving instanceof EntityPlayer) {
                long ms = JOGADOR_MS.getAndSet(0L);
                if (ms > 0L) {
                    Vigia.LOG.a("TESTE: atualizacao de jogador dormindo " + ms + " ms");
                    dormir(ms);
                }
                return;
            }
            if (ENTIDADE_MS.get() == 0L || ev.entityLiving instanceof EntityPlayer || ev.entityLiving.worldObj == null
                    || ev.entityLiving.worldObj.isRemote || ev.entityLiving.dimension != 0) {
                return;
            }
            long ms = ENTIDADE_MS.getAndSet(0L);
            if (ms > 0L) {
                Vigia.LOG.a("TESTE: entidade " + ev.entityLiving.getClass().getSimpleName() + " id " + ev.entityLiving.getEntityId() + " dormindo " + ms + " ms");
                dormir(ms);
            }
        }
    }
}
