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
package hoshiestar.orcamento;


/** Relogio e espera, injetaveis (os testes entregam um falso). */
public interface Tempo {

    /** Relogio monotonico em ns (System.nanoTime). */
    long nano();

    /** Relogio de parede em ms (System.currentTimeMillis). */
    long milis();

    /** O real. */
    Tempo SISTEMA = new Tempo() {
        @Override
        public long nano() {
            return System.nanoTime();
        }

        @Override
        public long milis() {
            return System.currentTimeMillis();
        }
    };
}
