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
package hoshiestar.embutidos.asm;

/**
 * Uma chamada de metodo dentro do bytecode de um metodo do mod: dono (nome interno, com barras),
 * nome e descritor, exatamente como aparecem na instrucao invoke* (veja com {@code javap -c}).
 */
public final class Chamada {

    public final String dono;
    public final String nome;
    public final String desc;

    private Chamada(String dono, String nome, String desc) {
        this.dono = dono;
        this.nome = nome;
        this.desc = desc;
    }

    public static Chamada de(String dono, String nome, String desc) {
        if (dono == null || nome == null || desc == null || !desc.startsWith("(")) {
            throw new IllegalArgumentException("chamada precisa de dono, nome e descritor");
        }
        return new Chamada(dono.replace('.', '/'), nome, desc);
    }

    @Override
    public String toString() {
        return dono + "." + nome + desc;
    }
}
