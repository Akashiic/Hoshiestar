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
package hoshiestar.medidor.mixins.early;

import com.llamalad7.mixinextras.injector.ModifyReceiver;
import hoshiestar.medidor.Medidor;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ganchos de medicao em World. Todos os nomes de alvo sao SRG (nomes de execucao do
 * servidor), com remap = false: nao ha refmap nem reobfuscacao.
 *
 * Regras de seguranca seguidas aqui:
 * - Nenhum gancho substitui nem remove a instrucao original (nada de @Redirect nem
 *   @WrapOperation). So se insere uma chamada antes (que devolve o mesmo objeto) e outra
 *   depois. Assim, o transformador ASM do Akashic-World-Firewall continua achando a
 *   chamada original, rode ele antes ou depois do Mixin.
 * - Todo gancho tem require = 0: se o alvo nao existir, o mixin nao falha; o autoteste
 *   avisa que aquela parte nao esta medida.
 * - Desligado, o custo e uma leitura de Medidor.ATIVO. Os handlers de @Inject nunca
 *   tocam no CallbackInfo, entao o Mixin passa null e nao aloca nada.
 * - Excecao do tick original nunca passa por aqui (os handlers sao metodos separados);
 *   excecao do nosso codigo e engolida e desliga a medicao.
 */
@Mixin(value = World.class, remap = false)
public abstract class MixinWorld {

    // ---- Inicio do laco de entidades/tile entities de um mundo (para o autoteste) ----
    // WorldServer.func_72939_s volta cedo quando o mundo esta ocioso (sem jogador ha 1200
    // ticks); so quando o laco de World roda e que da para cobrar ticks medidos.

    @Inject(method = "func_72939_s()V", at = @At("HEAD"), require = 0, remap = false)
    private void hoshiestar$lacoInicio(CallbackInfo ci) {
        try {
            if (Medidor.ATIVO) {
                Medidor.inicioLacoMundo((World) (Object) this);
            }
        } catch (Throwable t) {
            try {
                Medidor.falha(t);
            } catch (Throwable ignorado) {
                // nada: o tick segue
            }
        }
    }

    // ---- Tile entity, caminho vanilla: tileentity.func_145845_h() dentro de func_72939_s ----

    @ModifyReceiver(method = "func_72939_s()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;func_145845_h()V", remap = false),
            require = 0, remap = false)
    private TileEntity hoshiestar$teInicioVanilla(TileEntity te) {
        try {
            if (Medidor.ATIVO) {
                Medidor.inicioTE((World) (Object) this, te, Medidor.VIA_VANILLA);
            }
        } catch (Throwable t) {
            try {
                Medidor.falha(t);
            } catch (Throwable ignorado) {
                // nada: o tick segue
            }
        }
        return te;
    }

    @Inject(method = "func_72939_s()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;func_145845_h()V",
                    shift = At.Shift.AFTER, remap = false),
            require = 0, remap = false)
    private void hoshiestar$teFimVanilla(CallbackInfo ci) {
        try {
            if (Medidor.ATIVO) {
                Medidor.fimTE();
            }
        } catch (Throwable t) {
            try {
                Medidor.falha(t);
            } catch (Throwable ignorado) {
                // nada: o tick segue
            }
        }
    }

    // ---- Tile entity, caminho com Akashic-World-Firewall ----
    // O transformador do firewall troca a chamada acima por
    //   aload_0; invokestatic WorldMutationGuard.runTileEntityUpdate(TileEntity, World)V
    // quando roda antes do Mixin. Nesse caso estes dois ganchos pegam a chamada nova.

    @ModifyArg(method = "func_72939_s()V",
            at = @At(value = "INVOKE",
                    target = "Lcom/akashic/worldfirewall/WorldMutationGuard;runTileEntityUpdate(Lnet/minecraft/tileentity/TileEntity;Lnet/minecraft/world/World;)V",
                    remap = false),
            index = 0, require = 0, remap = false)
    private TileEntity hoshiestar$teInicioFirewall(TileEntity te) {
        try {
            if (Medidor.ATIVO) {
                Medidor.inicioTE((World) (Object) this, te, Medidor.VIA_FIREWALL);
            }
        } catch (Throwable t) {
            try {
                Medidor.falha(t);
            } catch (Throwable ignorado) {
                // nada: o tick segue
            }
        }
        return te;
    }

    @Inject(method = "func_72939_s()V",
            at = @At(value = "INVOKE",
                    target = "Lcom/akashic/worldfirewall/WorldMutationGuard;runTileEntityUpdate(Lnet/minecraft/tileentity/TileEntity;Lnet/minecraft/world/World;)V",
                    shift = At.Shift.AFTER, remap = false),
            require = 0, remap = false)
    private void hoshiestar$teFimFirewall(CallbackInfo ci) {
        try {
            if (Medidor.ATIVO) {
                Medidor.fimTE();
            }
        } catch (Throwable t) {
            try {
                Medidor.falha(t);
            } catch (Throwable ignorado) {
                // nada: o tick segue
            }
        }
    }

    // ---- Entidades: World.func_72866_a(Entity, boolean) (WorldServer chama super) ----

    @Inject(method = "func_72866_a(Lnet/minecraft/entity/Entity;Z)V", at = @At("HEAD"), require = 0, remap = false)
    private void hoshiestar$entidadeInicio(Entity entidade, boolean forcar, CallbackInfo ci) {
        try {
            if (Medidor.ATIVO) {
                Medidor.inicioEntidade((World) (Object) this, entidade, forcar);
            }
        } catch (Throwable t) {
            try {
                Medidor.falha(t);
            } catch (Throwable ignorado) {
                // nada: o tick segue
            }
        }
    }

    @Inject(method = "func_72866_a(Lnet/minecraft/entity/Entity;Z)V", at = @At("RETURN"), require = 0, remap = false)
    private void hoshiestar$entidadeFim(Entity entidade, boolean forcar, CallbackInfo ci) {
        try {
            if (Medidor.ATIVO) {
                Medidor.fimEntidade(entidade, forcar);
            }
        } catch (Throwable t) {
            try {
                Medidor.falha(t);
            } catch (Throwable ignorado) {
                // nada: o tick segue
            }
        }
    }
}
