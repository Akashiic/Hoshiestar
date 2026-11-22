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
 * Contador de ticks proprio do Hoshiestar-Core. Sobe 1 por tick do servidor, no inicio do tick
 * (TickEvent.ServerTickEvent, fase START), num unico handler do Core registrado com prioridade
 * HIGHEST (roda antes dos handlers normais dos mods no mesmo evento).
 *
 * <ul>
 * <li>Vale 0 ate o primeiro tick. Nao e salvo: recomeca do 0 a cada partida.</li>
 * <li>Nao depende do relogio de nenhum mundo (diferente de World.getTotalWorldTime, que fica parado
 * em mundo sem tick e pode ser mudado por comando).</li>
 * <li>So a thread do servidor escreve; leitura de qualquer thread (campo volatile, barato).</li>
 * </ul>
 */
public final class Relogio {

    private static volatile long ticks;

    private Relogio() {
    }

    /** Numero de ticks do servidor ja iniciados desde a partida. */
    public static long ticks() {
        return ticks;
    }

    /** Uso interno: chamado so pelo handler de tick do nucleo, na thread do servidor. */
    public static void avancar() {
        ticks = ticks + 1; // um so escritor (a thread do servidor): nao precisa de operacao atomica
    }
}
