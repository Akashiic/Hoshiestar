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
package hoshiestar.paralelo;

import hoshiestar.api.Log;
import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;

import java.util.List;

/**
 * Registro do paralelo (fase de coremod; so JDK, config e hoshiestar.api: nada do Minecraft). O interruptor
 * {@code paralelo.ligado} (padrao false) e o unico que importa para o comportamento; desligado, o servidor e o original.
 */
public final class RegistroParalelo {

    private RegistroParalelo() {
    }

    public static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
        Parametros p = Parametros.ler(cfg.paralelo_modo, cfg.paralelo_dimensao, cfg.paralelo_threads, cfg.paralelo_classes,
                cfg.paralelo_aoFalhar, cfg.paralelo_aoViolar, cfg.paralelo_leituraAmostra, cfg.paralelo_avisoEsperaS,
                cfg.paralelo_sinalTicks, cfg.paralelo_aquecimentoTicks);
        Recusas.Fatos f = new Recusas.Fatos();
        f.orcamentoLigado = cfg.orcamento_ligado;
        f.paradaLigada = cfg.parada_ligado;
        f.chunkmapCache4Seguro = cfg.chunkmap_cache4Seguro;
        // os fatos que so o jogo sabe (dimensoes, transformadores, observadores...) nao existem na fase de coremod:
        // f.jogoConferido fica falso e isso e motivo de recusa. ColetaFatosJogo (servidor pronto) coleta, avalia de novo e
        // chama Paralelo.configurarDoJogo (passo 3)
        List<String> motivos = Recusas.avaliar(cfg.paralelo_ligado, p, f);
        Paralelo.configurar(p, motivos);
        Paralelo.guardarFatosDoConfig(f);
        if (cfg.paralelo_ligado) {
            for (String m : motivos) {
                Log.aviso("paralelo recusado no boot: " + m);
            }
        }
        Recursos.registrar(Paralelo.ID,
                "fase paralela sincronizada com barreira (Degrau B; hoshie paralelo)", cfg.paralelo_ligado)
                .estado(new Recursos.Estado() {
                    @Override
                    public String linha() {
                        return Paralelo.linhaDeEstado();
                    }

                    @Override
                    public boolean recusado() {
                        return Paralelo.recusado();
                    }
                });
    }
}
