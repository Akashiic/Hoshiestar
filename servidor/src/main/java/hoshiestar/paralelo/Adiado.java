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
 * Uma operacao adiada (plano do Degrau B, secao 4, itens "A"): o tile do lote chamou um metodo {@code void} cujo efeito so
 * vale no tick seguinte (evento de bloco, tick agendado, som, particula ou marca para o cliente, saida da fila de
 * descarga). O metodo original NAO roda na hora: o adaptador anota os argumentos num {@code Adiado} e o lote o guarda, na
 * ordem da anotacao. A {@link Juncao} chama {@link #aplicar} uma vez, na thread do servidor, com o contexto do lote ja
 * fechado; ai o metodo original roda inteiro (com os mixins de terceiros e a deduplicacao dele), como no tick em serie.
 *
 * <p>Sem Minecraft: quem cria o objeto e o adaptador do jogo ({@code GanchosAdiados}).
 */
public interface Adiado {

    /** Chama o metodo original com os argumentos anotados. So a thread do servidor, na juncao, com o contexto fechado. */
    void aplicar();

    /** Texto curto (operacao e posicao) para o log. */
    String descrever();
}
