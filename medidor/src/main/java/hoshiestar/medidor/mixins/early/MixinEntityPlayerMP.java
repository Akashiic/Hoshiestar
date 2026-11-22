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

import hoshiestar.medidor.Medidor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * O tick "pesado" do jogador (EntityPlayer.onUpdate) roda pela rede, em
 * EntityPlayerMP.func_71127_g (onUpdateEntity), chamado por NetHandlerPlayServer.
 * Ele nao passa por World.func_72866_a, entao nao ha contagem dupla de tempo; mesmo assim
 * a pilha de quadros do {@link Medidor} desconta tempo aninhado. O jogador tambem e tickado
 * pelo laco do mundo (EntityPlayerMP.onUpdate); para nao misturar as contagens de ticks, o
 * caminho da rede entra no relatorio como uma classe separada, com o sufixo
 * " (tick pela rede)" (ele roda uma vez por pacote de movimento, nao uma vez por tick).
 */
@Mixin(value = EntityPlayerMP.class, remap = false)
public abstract class MixinEntityPlayerMP {

    @Inject(method = "func_71127_g()V", at = @At("HEAD"), require = 0, remap = false)
    private void hoshiestar$jogadorInicio(CallbackInfo ci) {
        try {
            if (Medidor.ATIVO) {
                Medidor.inicioJogadorRede((Entity) (Object) this);
            }
        } catch (Throwable t) {
            try {
                Medidor.falha(t);
            } catch (Throwable ignorado) {
                // nada: o tick segue
            }
        }
    }

    @Inject(method = "func_71127_g()V", at = @At("RETURN"), require = 0, remap = false)
    private void hoshiestar$jogadorFim(CallbackInfo ci) {
        try {
            if (Medidor.ATIVO) {
                Medidor.fimEntidade((Entity) (Object) this, true);
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
