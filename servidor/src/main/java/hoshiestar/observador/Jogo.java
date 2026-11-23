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

import java.io.File;

/** O que o observador pede ao jogo (nada do Minecraft aparece aqui: os testes entregam um falso). */
public interface Jogo {

    /** Foto dos mundos carregados (chunks, tiles, entidades), tirada na thread do servidor, no fim do tick. */
    FotoMundo[] fotografar();

    /** Pasta de saida: &lt;pasta do servidor&gt;/hoshiestar. */
    File pastaDeSaida();

    /**
     * Fim do tick, com {@code observador.lista} ligado: confere se o campo {@code loadedTileEntityList} de cada mundo
     * ainda e uma lista nossa (se nao for, {@link ContagemLista#campoTrocado}). So le.
     */
    default void conferirListas(ContagemLista c) {
    }
}
