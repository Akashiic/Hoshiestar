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
package hoshiestar.paralelo;

/**
 * A operacao de um pedido delegado (plano do Degrau B, secao 3): o metodo original que a guarda nao deixou o trabalhador
 * rodar. Roda uma vez, na thread do servidor, com todos os outros trabalhadores parados.
 */
public interface Operacao<T> {

    /** Roda a operacao. O que ela devolver ou lancar volta para o trabalhador que pediu. */
    T rodar() throws Throwable;
}
