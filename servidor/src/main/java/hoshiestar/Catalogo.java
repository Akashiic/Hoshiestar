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
package hoshiestar;

import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.embutidos.autoteste.Autoteste;

/**
 * A lista de recursos do Hoshiestar. Cada recurso novo acrescenta UMA linha em {@link #registrar}:
 * a chamada ao metodo estatico de registro dele, que (a) registra o recurso em
 * {@code hoshiestar.api.Recursos} com o valor do interruptor e (b) adiciona as correcoes embutidas
 * dele (se tiver) em {@link Correcoes}.
 *
 * <p>Roda na fase de coremod: o metodo de registro de cada recurso so pode tocar no JDK, no config
 * e em hoshiestar.api/hoshiestar.embutidos (nada do Minecraft, Forge ou Bukkit; ganchos sao
 * referidos pelo NOME, como texto).
 */
public final class Catalogo {

    private Catalogo() {
    }

    static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
        Autoteste.registrar(cfg, correcoes);
        hoshiestar.chunkmap.ChunkMap.registrar(cfg, correcoes);
        hoshiestar.parada.Parada.registrar(cfg, correcoes);
        hoshiestar.vigia.Vigia.registrar(cfg, correcoes);
        hoshiestar.medidor.RegistroMedidor.registrar(cfg, correcoes);
        hoshiestar.mekanism.Mekanism.registrar(cfg, correcoes);
        hoshiestar.betterquesting.BetterQuesting.registrar(cfg, correcoes);
        hoshiestar.arsmagica.ArsMagica.registrar(cfg, correcoes);
        hoshiestar.chromaticraft.ChromatiCraft.registrar(cfg, correcoes);
        hoshiestar.observador.RegistroObservador.registrar(cfg, correcoes);
        // Proximos recursos (cada um no proprio pacote), por exemplo:
        // ChunkMap.registrar(cfg);                 patch em thermos.wrapper.ChunkBlockHashMap
        // Parada.registrar(cfg);                   patches em MinecraftServer/DedicatedServer
        // Vigia.registrar(cfg);
        // Mekanism.registrar(cfg, correcoes);      correcao embutida em mekanism.common...
    }
}
