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
package hoshiestar.api;

/**
 * Contador de ticks proprio do Hoshiestar. Sobe 1 por tick do servidor, na PRIMEIRA linha de
 * {@code MinecraftServer.tick()} (patch "// Hoshiestar" no fork), antes de qualquer outra coisa do
 * tick, inclusive do ServerTickEvent dos mods.
 *
 * <ul>
 * <li>Vale 0 ate o primeiro tick. Nao e salvo: recomeca do 0 a cada partida.</li>
 * <li>Nao depende do relogio de nenhum mundo (diferente de World.getTotalWorldTime, que fica parado
 * em mundo sem tick e pode ser mudado por comando) nem do {@code tickCounter} do servidor (que o
 * Forge/plugins podem ler, mas nao e garantido monotono para todo o codigo).</li>
 * <li>So a thread do servidor escreve. Leitura de qualquer thread pelo campo {@link #ticks}
 * (volatile; uma leitura simples, sem chamada de metodo).</li>
 * </ul>
 */
public final class Relogio {

    /**
     * Numero de ticks do servidor ja iniciados desde a partida. Escrito so pela thread do servidor
     * (um so escritor: o "++" nao precisa ser atomico). Leia direto: {@code Relogio.ticks}.
     */
    public static volatile long ticks;

    private Relogio() {
    }

    /** O mesmo que ler {@link #ticks}. */
    public static long ticks() {
        return ticks;
    }
}
