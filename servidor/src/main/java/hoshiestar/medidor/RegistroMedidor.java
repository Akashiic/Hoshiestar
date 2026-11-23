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
package hoshiestar.medidor;

import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;

import java.io.File;

/**
 * Registro do medidor (fase de coremod; so JDK, config e hoshiestar.api: nada do Minecraft). O medidor nao tem
 * interruptor "ligado": ele so mede quando o admin manda ({@code hoshie medir}). A chave
 * {@code medidor.habilitado} (padrao true) existe para o admin poder proibir o comando: em false o
 * {@code hoshie medir} recusa e os ganchos de tick do servidor nem leem o relogio.
 */
public final class RegistroMedidor {

    private RegistroMedidor() {
    }

    public static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
        boolean habilitado = cfg.medidor_habilitado;
        Medidor.definirHabilitado(habilitado);
        Medidor.definirMargemCusto(cfg.medidor_margemCusto);
        // O jar do mod antigo (Hoshiestar-Medidor 1.0.x) em mods/ quebra junto com este: desabilita e avisa ja aqui,
        // na fase de coremod (so lista a pasta), antes de o FML carregar o mod antigo.
        String antigos = Medidor.jarsAntigos(new File("mods"));
        if (antigos != null) {
            Medidor.desabilitarPorConflito(Medidor.textoJarAntigo("mods/" + antigos));
            habilitado = false;
        }
        // o status da base mostra so a descricao de recurso desligado: com o jar antigo, ela diz o que fazer
        String descricao = antigos != null
                ? "DESABILITADO: mod antigo Hoshiestar-Medidor em mods/ (" + antigos + "); apague o jar e reinicie"
                : "medidor de carga por regiao (so mede quando o admin manda: hoshie medir <segundos>)";
        Recursos.registrar(Medidor.ID, descricao, habilitado).estado(new Recursos.Estado() {
                    @Override
                    public String linha() {
                        return Medidor.linhaDeEstado();
                    }
                });
    }
}
