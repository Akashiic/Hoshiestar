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

import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import net.minecraft.launchwrapper.IClassTransformer;

/**
 * O UNICO transformador de bytecode do Hoshiestar. Registrado pelo proprio servidor em
 * {@code CrucibleCoremodHook.coremodHandleLaunch} (patch "// Hoshiestar"), ANTES dos transformadores
 * do Crucible e de qualquer coremod ou Mixin de mod: por isso ve as classes de mod com os bytes do
 * jar. Detalhes e provas em servidor/README.md.
 *
 * <p>Custo quando a classe nao tem correcao ligada: uma busca num HashMap pelo nome; os bytes nao
 * sao tocados. Qualquer excecao devolve os bytes que chegaram.
 *
 * <p>Esta classe e carregada na fase de coremod: so pode usar o JDK, ASM, launchwrapper e classes
 * nossas que tambem obedecem essa regra (nada do Minecraft).
 */
public final class TransformadorHoshiestar implements IClassTransformer {

    private final Correcoes correcoes = Correcoes.global();
    private final ClassLoader carregador = TransformadorHoshiestar.class.getClassLoader();
    // ganchos sao conferidos no arquivo .class, que tem os nomes do Minecraft reofuscados: traduz com
    // o mapa do FML antes de comparar com a classe do mod (ver ResolvedorDeGanchos)
    private final ResolvedorDeGanchos ganchos = new ResolvedorDeGanchos(carregador, new RemapeadorFml(carregador));

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || transformedName == null) {
            return basicClass;
        }
        if (correcoes.ligadasPara(transformedName) == null) {
            return basicClass;
        }
        try {
            return correcoes.aplicar(transformedName, basicClass, ganchos, carregador);
        } catch (Throwable t) {
            return basicClass;
        }
    }
}
