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
 * O padrao de bytecode que a correcao espera nao foi encontrado exatamente como devia (metodo
 * ausente, chamada ausente ou repetida, gancho ausente...). Quem lanca nao alterou nada que fique:
 * a correcao inteira e descartada e a classe segue com os bytes que tinha (falha fechada).
 */
public class PadraoNaoEncontrado extends Exception {

    private static final long serialVersionUID = 1L;

    public PadraoNaoEncontrado(String mensagem) {
        super(mensagem);
    }
}
