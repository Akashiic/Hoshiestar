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
 * O que o nucleo de concorrencia pede a quem o usa (nada do Minecraft aparece aqui; os testes entregam um falso, no
 * estilo de {@code hoshiestar/orcamento/Jogo.java}). Os tres metodos podem ser chamados por qualquer thread.
 */
public interface Ambiente {

    /** Linha de log do Hoshiestar (despejos de pilha, avisos de espera). Nunca e chamado com a trava do nucleo na mao. */
    void log(String linha);

    /** Erro com a causa (thread de regiao que morreu). Nunca e chamado com a trava do nucleo na mao. */
    void erro(String linha, Throwable causa);

    /**
     * Relogio em nanossegundos para o tempo de cada lote (o que a juncao entrega ao orcamento). As esperas e os prazos
     * do nucleo NAO usam este relogio: usam sempre {@code System.nanoTime()}.
     */
    long agora();
}
