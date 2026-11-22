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
 * A thread do servidor ("Server thread", a que roda o laco de ticks).
 *
 * <p><b>Desde quando vale:</b> desde a primeira linha de {@code MinecraftServer.run()} (patch
 * "// Hoshiestar" no fork), que e o corpo da propria thread do servidor. No servidor dedicado do
 * Crucible, a construcao e o carregamento dos mods (FML) acontecem DEPOIS disso, dentro de
 * {@code DedicatedServer.startServer()}, na mesma thread; ou seja: vale antes de qualquer mod,
 * mundo ou plugin ser carregado.
 *
 * <p><b>Antes disso</b> (fase de coremod e transformadores, que rodam na thread "main"):
 * {@link #thread()} devolve null e {@link #souAThreadDoServidor()} devolve false. Quem usa deve
 * tratar false como "nao sei, siga o caminho original".
 */
public final class ThreadServidor {

    private static volatile Thread thread;

    private ThreadServidor() {
    }

    /** A thread do servidor, ou null se ela ainda nao comecou. */
    public static Thread thread() {
        return thread;
    }

    /** true se a thread atual e a do servidor. Barato: uma leitura volatile e uma comparacao. */
    public static boolean souAThreadDoServidor() {
        return Thread.currentThread() == thread;
    }

    /** Uso interno: chamado so pelo patch em MinecraftServer.run(). */
    public static void definir(Thread t) {
        thread = t;
    }
}
