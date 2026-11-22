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
package hoshiestar.embutidos;

import hoshiestar.api.Log;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;

import java.lang.reflect.Method;

/**
 * Traduz descritores de metodo do arquivo de um gancho (nomes do Minecraft reofuscados pelo build,
 * ex.: {@code Lmw;}) para os nomes de tempo de execucao (ex.:
 * {@code Lnet/minecraft/entity/player/EntityPlayerMP;}), com o mesmo mapa que o
 * DeobfuscationTransformer do FML usa ao carregar a classe do gancho:
 * {@code cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper.INSTANCE.mapMethodDesc}.
 *
 * <p>Por REFLEXAO e so na primeira traducao (que acontece quando uma classe de mod com correcao
 * ligada passa pelo transformador, muito depois de o FML montar o mapa em
 * FMLSanityChecker.injectData). Assim esta classe nao amarra nada do FML na fase de coremod e
 * continua compilando nos testes isolados. Se o FML nao estiver la (testes) ou der erro: identidade
 * e um aviso.
 */
final class RemapeadorFml implements ResolvedorDeGanchos.Remapeador {

    private static final String REMAPEADOR = "cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper";

    private final ClassLoader carregador;
    private volatile Object instancia;
    private volatile Method mapMethodDesc;
    private volatile boolean desistiu;

    RemapeadorFml(ClassLoader carregador) {
        this.carregador = carregador;
    }

    @Override
    public String descritor(String d) {
        if (desistiu) {
            return d;
        }
        try {
            Method m = mapMethodDesc;
            if (m == null) {
                Class<?> c = Class.forName(REMAPEADOR, true, carregador);
                instancia = c.getField("INSTANCE").get(null);
                m = c.getMethod("mapMethodDesc", String.class);
                mapMethodDesc = m;
            }
            return (String) m.invoke(instancia, d);
        } catch (Throwable t) {
            desistiu = true;
            Log.de("embutidos").a("nao consegui usar o mapa de nomes do FML (" + t
                    + "); ganchos com tipos do Minecraft serao recusados como 'gancho ausente'");
            return d;
        }
    }
}
