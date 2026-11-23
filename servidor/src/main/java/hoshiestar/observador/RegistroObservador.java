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

import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;

/**
 * Registro do observador (fase de coremod; so JDK, config e hoshiestar.api: nada do Minecraft). O interruptor
 * {@code observador.ligado} (padrao false) habilita o comando {@code hoshie observar}; mesmo ligado, o observador so
 * anota quando o admin manda e por tempo limitado.
 */
public final class RegistroObservador {

    private RegistroObservador() {
    }

    public static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
        Observador.configurar(cfg.observador_ligado, cfg.observador_amostraPilha, cfg.observador_limiteCustoPct);
        Observador.configurarLista(cfg.observador_ligado && cfg.observador_lista);
        Recursos.registrar(Observador.ID,
                "observador de acessos entre regioes (so mede quando o admin manda: hoshie observar <segundos>)",
                cfg.observador_ligado).estado(new Recursos.Estado() {
                    @Override
                    public String linha() {
                        return Observador.linhaDeEstado();
                    }
                });
    }
}
