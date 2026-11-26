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
package hoshiestar.orcamento;

import hoshiestar.api.Log;
import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Registro do orcamento (fase de coremod; so JDK, config e hoshiestar.api: nada do Minecraft). O interruptor
 * {@code orcamento.ligado} (padrao false) liga os ganchos; desligado, o servidor e o original.
 */
public final class RegistroOrcamento {

    private RegistroOrcamento() {
    }

    public static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
        List<String> avisos = new ArrayList<String>();
        Parametros p = Parametros.ler(cfg.orcamento_alvoTickMs, cfg.orcamento_regiaoMs, cfg.orcamento_picoMs,
                cfg.orcamento_picoFracaoPct, cfg.orcamento_nMaximo, cfg.orcamento_recalculoTicks,
                cfg.orcamento_protegerSpawn, cfg.orcamento_nuncaCortarDimensoes, cfg.orcamento_nuncaCortarAreas,
                cfg.orcamento_nuncaCortarMods, cfg.orcamento_sempreJuntoDimensoes, cfg.orcamento_cortarComJogador,
                cfg.orcamento_alertaMs, cfg.orcamento_alertaIntervaloMin, cfg.orcamento_alertaOps, avisos);
        String modo = cfg.orcamento_modo == null ? "" : cfg.orcamento_modo.trim().toLowerCase(Locale.ROOT);
        boolean cortar = modo.equals("cortar");
        if (!modo.equals("medir") && !modo.equals("cortar")) {
            avisos.add("orcamento.modo = '" + cfg.orcamento_modo + "' invalido (use medir ou cortar); usando medir");
        }
        if (cfg.orcamento_ligado) {
            for (String a : avisos) {
                Log.aviso(a);
            }
        }
        Orcamento.configurar(p, cortar);
        Recursos.registrar(Orcamento.ID,
                "orcamento de tick por regiao (hoshie regioes; modo medir ou cortar)", cfg.orcamento_ligado)
                .estado(new Recursos.Estado() {
                    @Override
                    public String linha() {
                        return Orcamento.linhaDeEstado();
                    }
                });
    }
}
