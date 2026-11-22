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
package hoshiestar.medidor.core;

import hoshiestar.medidor.Ganchos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Plugin do config de mixin. Nao decide nada: so olha o bytecode depois que os mixins
 * foram aplicados e anota em {@link Ganchos} quais ganchos ficaram no lugar, para o log
 * e para o comando "hoshie status".
 *
 * Os nomes dos metodos de gancho terminam com sufixos unicos (por exemplo
 * "hoshiestar$teFimVanilla"); o Mixin renomeia o metodo, mas mantem o nome original no fim.
 */
public class MedidorMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LogManager.getLogger("Hoshiestar-Medidor");

    private static final String WORLD = "net.minecraft.world.World";
    private static final String PLAYER = "net.minecraft.entity.player.EntityPlayerMP";
    private static final String FIREWALL_OWNER = "com/akashic/worldfirewall/WorldMutationGuard";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        try {
            if (WORLD.equals(targetClassName)) {
                Ganchos.mundoAplicado = true;
                for (MethodNode m : targetClass.methods) {
                    if ("func_72939_s".equals(m.name) && "()V".equals(m.desc)) {
                        Ganchos.teVanilla = chama(m, "hoshiestar$teFimVanilla");
                        Ganchos.teFirewall = chama(m, "hoshiestar$teFimFirewall");
                        Ganchos.firewallJaPresenteNoMixin = chamaDono(m, FIREWALL_OWNER);
                        Ganchos.lacoMundo = chama(m, "hoshiestar$lacoInicio");
                    } else if ("func_72866_a".equals(m.name) && "(Lnet/minecraft/entity/Entity;Z)V".equals(m.desc)) {
                        Ganchos.entidadeInicio = chama(m, "hoshiestar$entidadeInicio");
                        Ganchos.entidadeFim = chama(m, "hoshiestar$entidadeFim");
                    }
                }
                LOG.info("[Hoshiestar-Medidor] Mixin em World aplicado. Gancho de tile entity: " + Ganchos.resumoTE()
                        + (Ganchos.firewallJaPresenteNoMixin ? " (a chamada do Akashic-World-Firewall ja estava no bytecode)"
                                : " (a chamada original ainda estava no bytecode)")
                        + "; entidades: " + (Ganchos.entidadesOk() ? "sim" : "NAO") + ".");
                if ("NENHUM".equals(Ganchos.resumoTE())) {
                    LOG.warn("[Hoshiestar-Medidor] ATENCAO: nenhum gancho de tile entity ficou no bytecode de World.func_72939_s."
                            + " A medicao de tile entities vai aparecer como NAO LIGADA.");
                }
            } else if (PLAYER.equals(targetClassName)) {
                Ganchos.jogadorAplicado = true;
                for (MethodNode m : targetClass.methods) {
                    if ("func_71127_g".equals(m.name) && "()V".equals(m.desc)) {
                        Ganchos.jogadorInicio = chama(m, "hoshiestar$jogadorInicio");
                        Ganchos.jogadorFim = chama(m, "hoshiestar$jogadorFim");
                    }
                }
                LOG.info("[Hoshiestar-Medidor] Mixin em EntityPlayerMP aplicado. Tick de jogador pela rede: "
                        + (Ganchos.jogadoresOk() ? "sim" : "NAO") + ".");
            }
        } catch (Throwable t) {
            // so diagnostico: nunca derruba o carregamento
            LOG.warn("[Hoshiestar-Medidor] Nao consegui inspecionar o bytecode depois do mixin: " + t);
        }
    }

    private static boolean chama(MethodNode m, String sufixo) {
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode && ((MethodInsnNode) n).name.endsWith(sufixo)) {
                return true;
            }
        }
        return false;
    }

    private static boolean chamaDono(MethodNode m, String dono) {
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode && dono.equals(((MethodInsnNode) n).owner)) {
                return true;
            }
        }
        return false;
    }
}
