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

/**
 * Em que momento uma lista de mixins e montada.
 * <ul>
 * <li>{@link #EARLY}: na fase de coremod (antes de qualquer classe do Minecraft ou de mod carregar).
 * Para alvos do Minecraft, do Forge, do Crucible/CraftBukkit (classes do server.jar).</li>
 * <li>{@link #LATE}: no estado CONSTRUCTING do FML (os mods ja foram descobertos e a lista de
 * modids carregados ja existe, mas nenhuma classe de mod rodou ainda). Para alvos dentro de jars de
 * mods.</li>
 * </ul>
 */
public enum Fase {
    EARLY,
    LATE
}
