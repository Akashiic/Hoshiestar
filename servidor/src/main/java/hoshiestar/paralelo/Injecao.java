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
 * Pedidos de injecao de falha para os testes do plano (secao 10: {@code hoshie paralelo injetar ...}), so com
 * {@code -Dhoshiestar.paralelo.teste=true} ({@link Congelar#TESTE}). Passo 4: so {@code quebrar} (em serie, quebra um
 * tile ja despachado para um lote; o tile tem de ser pulado pela reconferencia e a conservacao tem de fechar). Os outros
 * ({@code escrever}, {@code carregar}, {@code erro}, {@code dormir}, {@code erro_serie}) sao do passo 6.
 *
 * <p>O comando (qualquer thread) so anota o pedido; a thread do servidor o tira e executa no proximo tick com lotes. Sem
 * Minecraft.
 */
public final class Injecao {

    /** Pedido de quebrar: {x, y, z} ou vazio (o servidor escolhe: o primeiro bau com itens dos lotes). null = nenhum. */
    private static volatile int[] quebrar;
    /** O que aconteceu com o ultimo pedido (texto para o log e o estado). */
    static volatile String ultimo = "nenhum pedido";

    private Injecao() {
    }

    /** Anota o pedido. Devolve a resposta do comando. */
    public static String pedirQuebrar(int[] xyz) {
        if (!Congelar.TESTE) {
            return "injetar so existe com -Dhoshiestar.paralelo.teste=true";
        }
        quebrar = xyz == null ? new int[0] : xyz.clone();
        return "injetar quebrar: pedido anotado" + (xyz == null ? " (o primeiro bau com itens dos lotes)" : " em (" + xyz[0] + ", " + xyz[1] + ", " + xyz[2] + ")")
                + "; o servidor quebra em serie, depois do despacho, no proximo tick com lotes (modo sombra_ordenada). Resultado no log e no 'estado'.";
    }

    /** Thread do servidor: tira o pedido (ou null). */
    static synchronized int[] tirarQuebrar() {
        int[] q = quebrar;
        quebrar = null;
        return q;
    }

    /** Thread do servidor: devolve o pedido (nao havia tile para quebrar neste tick), se nenhum outro chegou. */
    static synchronized void devolver(int[] q) {
        if (quebrar == null) {
            quebrar = q;
        }
    }

    public static String ultimo() {
        return ultimo;
    }
}
