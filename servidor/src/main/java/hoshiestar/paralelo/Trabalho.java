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
 * O lote de uma regiao como o nucleo de concorrencia o ve (plano do Degrau B, secao 2): uma lista de itens (tiles) que
 * UMA thread de regiao tica em ordem, do 0 ao ultimo. Nada do Minecraft aparece aqui: o jogo entrega a implementacao (a
 * classe {@code Lote} e do passo 4 do plano) e os testes entregam regioes falsas.
 *
 * <p><b>Quem chama o que:</b> {@link #chave}, {@link #tamanho} e {@link #custo} sao lidos uma vez, pela thread do
 * servidor, quando a fase e publicada. {@link #ticar} roda numa thread de regiao (a mesma para o lote inteiro), ou na
 * thread do servidor se a juncao rodar o resto de um lote em serie. {@link #descrever} pode ser chamado por QUALQUER
 * thread enquanto o item roda (despejo de pilhas): so pode ler dado que nao muda (classe e posicao do tile).
 */
public interface Trabalho {

    /** Chave estavel do lote (plano, secao 8: a menor chave de secao entre os tiles dele). Uma por lote na fase. */
    long chave();

    /** Quantos itens o lote tem. */
    int tamanho();

    /** Custo estimado (o tempo do orcamento): os lotes mais caros sao pegos primeiro. So ordena; pode ser 0. */
    long custo();

    /**
     * Tica o item {@code i}. Qualquer {@code Throwable} que sair daqui e guardado no lote, e o resto do lote nao roda
     * (plano, secao 9). A reconferencia do tile antes do tick (secao 2) e de quem implementa.
     */
    void ticar(int i) throws Throwable;

    /** Texto curto do item {@code i} para o log (classe e posicao). Chamado por qualquer thread; nao pode travar. */
    String descrever(int i);
}
