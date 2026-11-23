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
package hoshiestar.mekanism.matriz;

/**
 * Embrulho de uma excecao que saiu de DENTRO da busca original de uma coordenada (o mundo, o bloco
 * ou um vizinho avisado, ou seja, codigo de outro mod), e nao do nosso codigo. O gancho
 * ({@link GanchoMatriz}) a desembrulha e relanca a causa, como o corpo original do Mekanism faria no
 * mesmo ponto; o recurso nao desliga. Sem pilha propria (a pilha que importa e a da causa).
 */
final class DeTerceiros extends RuntimeException {

    private static final long serialVersionUID = 1L;

    DeTerceiros(Throwable causa) {
        super("excecao de outro mod dentro da busca original da matriz de inducao", causa, false, false);
    }
}
