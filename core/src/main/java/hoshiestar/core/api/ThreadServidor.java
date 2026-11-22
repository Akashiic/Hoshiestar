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
 * A thread do servidor ("Server thread", a que roda o laco de ticks).
 *
 * <p><b>Quando passa a valer:</b> no FMLPreInitializationEvent do Hoshiestar-Core. No servidor
 * dedicado 1.7.10 os mods sao carregados de dentro da propria thread do servidor, e o Core grava
 * {@code MinecraftServer.primaryThread} (campo do CraftBukkit/Crucible, criado no construtor do
 * servidor) ou, se ele nao estiver disponivel, a thread atual. Isso acontece antes de qualquer mundo
 * ser carregado. No primeiro tick (ServerTickEvent START) o Core confere de novo e, se a thread do
 * tick for outra, troca e avisa no log.
 *
 * <p><b>Antes disso</b> (fase de coremod, construcao dos mods): {@link #thread()} devolve null e
 * {@link #souAThreadDoServidor()} devolve false. Quem usa deve tratar false como "nao sei, siga o
 * caminho original".
 */
public final class ThreadServidor {

    private static volatile Thread thread;

    private ThreadServidor() {
    }

    /** A thread do servidor, ou null se ainda nao foi capturada. */
    public static Thread thread() {
        return thread;
    }

    /** true se a thread atual e a do servidor. Barato: uma leitura volatile e uma comparacao. */
    public static boolean souAThreadDoServidor() {
        return Thread.currentThread() == thread;
    }

    /** Uso interno do nucleo. */
    public static void definir(Thread t) {
        thread = t;
    }
}
