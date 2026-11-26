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

import hoshiestar.regioes.FotoMundo;

import java.io.File;

/** O que o orcamento pede ao jogo (nada do Minecraft aparece aqui: os testes entregam um falso). Todos so na thread do servidor. */
public interface Jogo {

    /** Foto dos mundos carregados (chunks, tiles com a classe, entidades). */
    FotoMundo[] fotografar();

    /** Ponto de spawn de cada mundo carregado: {dim, bloco x, bloco z}. */
    int[][] spawns();

    /** Posicao de cada jogador: {dim, bloco x, bloco z}. */
    int[][] jogadores();

    /** O mod (nome do jar sem ".jar", minusculo) a que a classe pertence, ou null se for do servidor, do JDK ou desconhecida. */
    String modDaClasse(String nomeDaClasse);

    /** Pasta de saida: &lt;pasta do servidor&gt;/hoshiestar. */
    File pastaDeSaida();

    /** Linha de log do Hoshiestar. */
    void log(String linha);

    /** Mensagem aos operadores online (so texto de regiao; nunca nome de jogador nem IP). */
    void avisarOperadores(String linha);

    /** Fim de cada tick (depois do recalculo e da contabilidade): ferramentas de teste do jogo (foto, contar). */
    default void aoFimDoTick(long tick, Retrato retrato) {
    }
}
