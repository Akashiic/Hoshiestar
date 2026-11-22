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
 * Um metodo estatico publico de uma classe nossa, chamado de dentro da classe do mod. So o dono
 * (nome interno, com barras) e o nome; o descritor e calculado pelo utilitario a partir do metodo
 * ou da chamada alvo, e conferido no arquivo .class do gancho antes de aplicar.
 *
 * <p>Receptor: quando o metodo/chamada alvo e de instancia, o primeiro parametro do gancho e o
 * objeto. Ele pode ser declarado com o tipo exato (exige compilar contra o jar do mod: fonte em
 * src/mods/java) ou como {@code Object} (nao exige). Os demais parametros e o retorno tem de ter
 * os tipos exatos.
 */
public final class Gancho {

    public final String dono;
    public final String nome;

    private Gancho(String dono, String nome) {
        this.dono = dono;
        this.nome = nome;
    }

    /** @param dono nome interno (com barras ou pontos), ex.: hoshiestar/embutidos/autoteste/GanchoAutoteste */
    public static Gancho de(String dono, String nome) {
        if (dono == null || dono.isEmpty() || nome == null || nome.isEmpty()) {
            throw new IllegalArgumentException("gancho precisa de dono e nome");
        }
        return new Gancho(dono.replace('.', '/'), nome);
    }

    @Override
    public String toString() {
        return dono.replace('/', '.') + "." + nome;
    }
}
