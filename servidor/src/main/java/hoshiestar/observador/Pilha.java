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
package hoshiestar.observador;

/**
 * Acha, numa pilha de chamadas, o primeiro quadro que nao e do Minecraft, do Forge/FML, do Bukkit, do Crucible nem do
 * Hoshiestar, e devolve o dono (jar de mod ou de plugin) dele. E o "mod da pilha" do observador. So le.
 */
final class Pilha {

    /** Quem resolve o jar de uma classe (na producao, o indice de jars do vigia). */
    interface Donos {
        /** O jar dono da classe (nome binario), ou null se nao e de mod/plugin. */
        String dono(String classe);
    }

    private static final String[] PULAR = {
        "java.", "javax.", "sun.", "jdk.", "com.sun.",
        "net.minecraft.", "net.minecraftforge.", "cpw.mods.fml.",
        "org.bukkit.", "org.spigotmc.", "io.github.crucible.", "thermos.",
        "hoshiestar.", "org.objectweb.asm.", "org.spongepowered.", "com.gtnewhorizons.retrofuturabootstrap.",
        "gnu.trove.", "com.google.common.", "org.apache.", "io.netty.", "org.yaml."
    };

    private Pilha() {
    }

    static boolean pular(String classe) {
        for (int i = 0; i < PULAR.length; i++) {
            if (classe.startsWith(PULAR[i])) {
                return true;
            }
        }
        return false;
    }

    /** O dono do primeiro quadro que nao e do servidor, ou null se nenhum quadro tem dono. */
    static String achar(StackTraceElement[] pilha, Donos donos) {
        if (pilha == null) {
            return null;
        }
        for (int i = 0; i < pilha.length; i++) {
            String c = pilha[i].getClassName();
            if (c == null || pular(c)) {
                continue;
            }
            String d = donos.dono(c);
            if (d != null) {
                return d;
            }
        }
        return null;
    }
}
