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
package hoshiestar.medidor;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Os pontos de entrada que os patches "// Hoshiestar - medidor" chamam (World.updateEntities,
 * World.updateEntityWithOptionalForce, EntityPlayerMP.onUpdateEntity). Os patches so chamam daqui quando
 * {@link Medidor#ATIVO} e verdadeiro (um teste de campo estatico por gancho; desligado nada mais roda).
 *
 * <p>Esta e a unica classe do medidor que cita classe do Minecraft: tira do objeto do jogo a dimensao, o
 * chunk e a montaria e passa para o {@link Medidor} (que nao conhece o Minecraft e e o que os testes
 * isolados exercitam). Nenhum metodo daqui lanca excecao: tudo que e nosso fica dentro de try/catch, que
 * desliga a medicao ({@link Medidor#falha}) e deixa o tick seguir. O patch chama SEM try/catch no ponto de
 * chamada (excecao a regra da base, como as marcas do vigia): um try/catch ali poe um quadro de pilha no
 * bytecode de World.updateEntities, e o transformador do Opis (MobiusCore) procura uma sequencia exata de
 * instrucoes nesse metodo (provado pelo vigia: o boot caiu). Esta classe nao tem inicializacao estatica que
 * possa falhar.
 *
 * <p>So leitura: nenhum metodo daqui escreve em objeto do jogo.
 */
public final class GanchosMedidor {

    private GanchosMedidor() {
    }

    /** Numero da dimensao do mundo (ou {@link Coleta#DIM_DESCONHECIDA}). */
    static int dim(World w) {
        return w != null && w.provider != null ? w.provider.dimensionId : Coleta.DIM_DESCONHECIDA;
    }

    /** A montaria de {@code e}, so se ela e mesmo a montaria (montaria.riddenByEntity == e); senao null. */
    private static Object montaria(Entity e) {
        Entity m = e.ridingEntity;
        return m != null && m.riddenByEntity == e ? m : null;
    }

    /** Primeira linha de World.updateEntities (o WorldServer ocioso nem chega aqui). */
    public static void inicioLacoMundo(World w) {
        try {
            Medidor.inicioLacoMundo(w, dim(w), w.loadedTileEntityList != null ? w.loadedTileEntityList.size() : 0,
                    w.loadedEntityList != null ? w.loadedEntityList.size() : 0);
        } catch (Throwable t) {
            Medidor.falha(t);
        }
    }

    /** Antes do update de uma tile entity, dentro do laco de World.updateEntities. */
    public static void inicioTE(World w, TileEntity te) {
        try {
            if (te != null) {
                Medidor.inicioTE(w, dim(w), te, te.xCoord >> 4, te.zCoord >> 4);
            }
        } catch (Throwable t) {
            Medidor.falha(t);
        }
    }

    /** Depois do update de uma tile entity. */
    public static void fimTE() {
        Medidor.fimTE(); // nunca lanca
    }

    /**
     * Inicio de World.updateEntityWithOptionalForce (depois da saida para entidade morta). Chamada com
     * {@code forcar = false} e so contabilidade de chunk (teleporte, troca de dimensao): nao e tick e nao conta.
     */
    public static void inicioEntidade(World w, Entity e, boolean forcar) {
        try {
            if (forcar && e != null) {
                Medidor.inicioEntidade(w, dim(w), e, Medidor.piso(e.posX) >> 4, Medidor.piso(e.posZ) >> 4, montaria(e),
                        false);
            }
        } catch (Throwable t) {
            Medidor.falha(t);
        }
    }

    /** Antes de cada saida de World.updateEntityWithOptionalForce. */
    public static void fimEntidade(Entity e, boolean forcar) {
        if (forcar) {
            Medidor.fimEntidade(e); // nunca lanca
        }
    }

    /** Inicio de EntityPlayerMP.onUpdateEntity: o tick do jogador pela rede (uma vez por pacote de movimento). */
    public static void inicioJogadorRede(EntityPlayerMP p) {
        try {
            if (p != null) {
                Medidor.inicioEntidade(p.worldObj, dim(p.worldObj), p, Medidor.piso(p.posX) >> 4,
                        Medidor.piso(p.posZ) >> 4, montaria(p), true);
            }
        } catch (Throwable t) {
            Medidor.falha(t);
        }
    }

    /** Fim de EntityPlayerMP.onUpdateEntity (so a saida normal; com excecao o quadro e descartado depois). */
    public static void fimJogadorRede(EntityPlayerMP p) {
        Medidor.fimEntidade(p); // nunca lanca
    }

    /**
     * Servidor de pe (FMLServerStartingEvent): entrega ao {@link Medidor} o {@link JogoMinecraft} e diz que chamada
     * de update de tile entity os ganchos envolvem. O gancho de tile entity fica sempre em volta da chamada que
     * estiver no bytecode; se o Akashic-World-Firewall esta na cadeia de transformadores, essa chamada e a dele
     * (WorldMutationGuard.runTileEntityUpdate): o transformador dele troca a chamada original e nao toca nos nossos
     * ganchos. Isto e deduzido da cadeia (o bytecode final de World nao fica acessivel); a prova e a linha do
     * proprio firewall no log do boot: "Patched TileEntity tick gate at World#updateEntities.".
     */
    static void instalar() {
        String chamada = "vanilla";
        String detalhe = "Akashic-World-Firewall ausente: os ganchos envolvem a chamada original TileEntity.updateEntity()";
        try {
            List<IClassTransformer> l = Launch.classLoader.getTransformers();
            for (int i = 0; i < l.size(); i++) {
                if ("com.akashic.worldfirewall.PassiveItemTransformer".equals(l.get(i).getClass().getName())) {
                    chamada = "firewall";
                    detalhe = "Akashic-World-Firewall na posicao " + (i + 1) + " de " + l.size() + " da cadeia de"
                            + " transformadores: os ganchos envolvem a chamada WorldMutationGuard.runTileEntityUpdate que"
                            + " ele poe no lugar da original (confira no log do boot: 'Patched TileEntity tick gate')";
                    break;
                }
            }
        } catch (Throwable t) {
            chamada = "desconhecida";
            detalhe = "nao consegui ler a cadeia de transformadores (" + t + ")";
        }
        Medidor.definirJogo(new JogoMinecraft(), chamada, detalhe);
    }

    /** O jogo de verdade, visto pelo {@link Medidor}. */
    static final class JogoMinecraft implements Jogo {

        @Override
        public boolean morto(Object o, int tipo) {
            if (tipo == Coleta.TE) {
                return o instanceof TileEntity && ((TileEntity) o).isInvalid();
            }
            return o instanceof Entity && ((Entity) o).isDead;
        }

        @Override
        public List<Mundo> mundos() {
            List<Mundo> r = new ArrayList<Mundo>();
            WorldServer[] ws = DimensionManager.getWorlds();
            if (ws == null) {
                return r;
            }
            for (WorldServer w : ws) {
                if (w == null) {
                    continue;
                }
                boolean forcado = false;
                try {
                    forcado = !ForgeChunkManager.getPersistentChunksFor(w).isEmpty();
                } catch (Throwable t) {
                    // so diagnostico
                }
                r.add(new Mundo(w, dim(w), w.loadedTileEntityList != null ? w.loadedTileEntityList.size() : 0,
                        w.loadedEntityList != null ? w.loadedEntityList.size() : 0,
                        w.playerEntities != null && !w.playerEntities.isEmpty(), forcado));
            }
            return r;
        }

        @Override
        public File pastaDeSaida() {
            MinecraftServer s = MinecraftServer.getServer();
            return s != null ? s.getFile("hoshiestar") : null;
        }
    }
}
