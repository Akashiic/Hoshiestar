/*
 * Hoshiestar-Core: base comum dos recursos da Etapa 1 do projeto Hoshiestar.
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
package hoshiestar.core.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Um mixin "late" (alvo dentro do jar de um mod) e os modids que precisam estar carregados para
 * ele ser registrado. O nome e relativo ao pacote {@link Recurso#PACOTE_LATE}
 * (ex.: "mekanism.MixinSynchronizedMatrixData" = classe
 * hoshiestar.core.mixins.late.mekanism.MixinSynchronizedMatrixData); o nome completo tambem e aceito.
 *
 * <p>O modid e o do {@code @Mod(modid = ...)} / mcmod.info (diferencia maiusculas): "Mekanism",
 * "betterquesting", "bq_standard", "arsmagica2", "ChromatiCraft", "OpenBlocks".
 */
public final class MixinTardio {

    private final String nome;
    private final Set<String> modids;

    private MixinTardio(String nome, Set<String> modids) {
        this.nome = nome;
        this.modids = modids;
    }

    /** @param nome nome do mixin (relativo ao pacote late, ou completo); @param modids exigidos (pelo menos 1). */
    public static MixinTardio de(String nome, String... modids) {
        if (nome == null || nome.isEmpty() || modids == null || modids.length == 0) {
            throw new IllegalArgumentException("mixin late precisa de nome e de pelo menos um modid");
        }
        return new MixinTardio(nome, Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(modids))));
    }

    public String nome() {
        return nome;
    }

    public Set<String> modids() {
        return modids;
    }

    @Override
    public String toString() {
        return nome + " (mods " + modids + ")";
    }
}
