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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * O que se sabe, no carregamento das classes, sobre quais ganchos ficaram no bytecode.
 *
 * Preenchido pelo plugin de mixin ({@code MedidorMixinPlugin.postApply}) depois que os
 * mixins sao aplicados em World e EntityPlayerMP. Esta classe nao pode citar classes do
 * Minecraft: ela e carregada cedo, antes dos transformadores.
 *
 * A prova definitiva e o autoteste em tempo de execucao (ver {@link Medidor}); isto aqui
 * so diz o que o bytecode tinha no momento em que o Mixin rodou.
 */
public final class Ganchos {

    private Ganchos() {
    }

    /**
     * O unico logger do mod, pedido uma vez so. Pedir o mesmo nome de novo em outra classe faz o
     * log4j avisar que o logger foi criado com outro message factory (o FoamFix troca o factory).
     */
    public static final Logger LOG = LogManager.getLogger("Hoshiestar-Medidor");

    /** O mixin de World chegou a ser aplicado. */
    public static volatile boolean mundoAplicado;
    /** O mixin de EntityPlayerMP chegou a ser aplicado. */
    public static volatile boolean jogadorAplicado;

    /** Ganchos em volta da chamada original {@code TileEntity.func_145845_h()} dentro de func_72939_s. */
    public static volatile boolean teVanilla;
    /** Ganchos em volta de {@code WorldMutationGuard.runTileEntityUpdate(...)} (Akashic-World-Firewall). */
    public static volatile boolean teFirewall;
    /** Na hora em que o Mixin rodou, func_72939_s ja tinha a chamada do firewall. */
    public static volatile boolean firewallJaPresenteNoMixin;
    /** Gancho no inicio de World.func_72939_s (usado so pelo autoteste). */
    public static volatile boolean lacoMundo;
    /** Ganchos de inicio e fim em func_72866_a (updateEntityWithOptionalForce). */
    public static volatile boolean entidadeInicio;
    public static volatile boolean entidadeFim;
    /** Ganchos de inicio e fim em EntityPlayerMP.func_71127_g (onUpdateEntity). */
    public static volatile boolean jogadorInicio;
    public static volatile boolean jogadorFim;

    /** O jar do Akashic-World-Firewall estava no classpath no boot. */
    public static volatile boolean firewallInstalado;

    /** Resumo legivel do gancho de tile entity aplicado no carregamento. */
    public static String resumoTE() {
        if (teVanilla && teFirewall) {
            return "vanilla+firewall";
        }
        if (teVanilla) {
            return "vanilla";
        }
        if (teFirewall) {
            return "firewall";
        }
        return "NENHUM";
    }

    public static boolean entidadesOk() {
        return entidadeInicio && entidadeFim;
    }

    public static boolean jogadoresOk() {
        return jogadorInicio && jogadorFim;
    }
}
