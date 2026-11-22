/*
 * Hoshiestar-Medidor: mede onde, no mapa, vai o tempo de tick.
 * Copyright (C) 2026 Projeto Hoshiestar
 *
 * Este programa e software livre: voce pode redistribui-lo e/ou modifica-lo
 * sob os termos da GNU General Public License publicada pela Free Software
 * Foundation, versao 3 da Licenca ou (a seu criterio) qualquer versao posterior.
 *
 * Este programa e distribuido na esperanca de que seja util, mas SEM QUALQUER
 * GARANTIA; sem mesmo a garantia implicita de COMERCIABILIDADE ou ADEQUACAO A
 * UM PROPOSITO ESPECIFICO. Veja a GNU General Public License para mais detalhes.
 *
 * Voce deve ter recebido uma copia da GNU General Public License junto com este
 * programa. Se nao, veja <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package hoshiestar.medidor;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;

import java.util.List;

/**
 * Mod "Hoshiestar-Medidor": so do lado do servidor (o cliente nao precisa do jar).
 */
@Mod(modid = "hoshiestar_medidor", name = "Hoshiestar-Medidor", version = Medidor.VERSAO,
        acceptableRemoteVersions = "*")
public class MedidorMod {

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent ev) {
        FMLCommonHandler.instance().bus().register(new Ticks());
        try {
            registrarOrdemDosTransformadores();
        } catch (Throwable t) {
            // so diagnostico
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent ev) {
        ev.registerServerCommand(new ComandoHoshie());
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent ev) {
        String te = Ganchos.resumoTE();
        String linha = Medidor.P + "Pronto (desligado). Ganchos: tile entities=" + te + ", entidades="
                + (Ganchos.entidadesOk() ? "sim" : "NAO") + ", jogadores pela rede=" + (Ganchos.jogadoresOk() ? "sim" : "NAO")
                + ". Use 'hoshie medir <segundos>'.";
        if ("NENHUM".equals(te) || !Ganchos.entidadesOk()) {
            Medidor.LOG.warn(linha + " ATENCAO: parte da medicao nao esta ligada; veja 'hoshie status'.");
        } else {
            Medidor.LOG.info(linha);
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent ev) {
        Medidor.pararNoDesligamento();
    }

    /** Registra no log a posicao do firewall e do Mixin na lista de transformadores (diagnostico). */
    private static void registrarOrdemDosTransformadores() {
        List<IClassTransformer> lista = Launch.classLoader.getTransformers();
        int fw = -1;
        int mixin = -1;
        for (int i = 0; i < lista.size(); i++) {
            String n = lista.get(i).getClass().getName();
            if (fw < 0 && n.startsWith("com.akashic.worldfirewall.")) {
                fw = i;
            }
            if (mixin < 0 && n.startsWith("org.spongepowered.asm.")) {
                mixin = i;
            }
        }
        Medidor.LOG.info(Medidor.P + "Transformadores: " + lista.size() + " no total; primeiro do Akashic-World-Firewall na posicao "
                + fw + ", Mixin na posicao " + mixin + " (-1 = ausente). Gancho de tile entity aplicado: "
                + Ganchos.resumoTE() + ".");
    }

    /** Handler de tick do servidor: liga/desliga, conta ticks, autoteste, fim por tempo. */
    public static final class Ticks {
        @SubscribeEvent
        public void aoTickDoServidor(TickEvent.ServerTickEvent ev) {
            try {
                if (ev.phase == TickEvent.Phase.START) {
                    Medidor.inicioTickServidor();
                } else {
                    Medidor.fimTickServidor();
                }
            } catch (Throwable t) {
                Medidor.falha(t);
            }
        }
    }
}
