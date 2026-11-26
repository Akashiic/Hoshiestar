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

import hoshiestar.config.HoshiestarConfig;

/**
 * Porta de entrada do orcamento para os ganchos dos patches (World, WorldServer, MinecraftServer). {@link #LIGADO} e
 * {@code static final}, lido do hoshiestar.yml uma vez (a primeira vez que a classe e usada, depois do boot do config):
 * desligado, cada gancho custa um teste de campo estatico e nada mais roda. Esta classe nao cita o Minecraft.
 */
public final class Orcamento {

    public static final String ID = "orcamento";

    /** {@code orcamento.ligado} do hoshiestar.yml. Falso (padrao) = nenhum gancho faz nada. */
    public static final boolean LIGADO = lerLigado();

    /** O nucleo (criado quando o servidor esta de pe e o jogo entrega os adaptadores). */
    static volatile Nucleo N;

    private static volatile Parametros params = Parametros.padrao();
    private static volatile boolean cortarInicial;

    private Orcamento() {
    }

    private static boolean lerLigado() {
        try {
            return HoshiestarConfig.atual().orcamento_ligado;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fase de coremod: guarda os numeros e o modo inicial. */
    public static void configurar(Parametros p, boolean cortar) {
        params = p;
        cortarInicial = cortar;
    }

    /** Servidor de pe: cria o nucleo (so com {@link #LIGADO}). */
    public static void definirJogo(Jogo j) {
        if (LIGADO) {
            N = new Nucleo(params, j, Tempo.SISTEMA, cortarInicial, ferramentasDeTeste());
        }
    }

    public static Nucleo nucleo() {
        return N;
    }

    /** Ferramentas de teste so existem com {@code -Dhoshiestar.orcamento.teste=true}. */
    public static boolean ferramentasDeTeste() {
        return Boolean.getBoolean("hoshiestar.orcamento.teste");
    }

    public static Parametros parametros() {
        return params;
    }

    /** Linha de estado para o "hoshie status". */
    public static String linhaDeEstado() {
        Nucleo n = N;
        return LIGADO ? (n == null ? "ligado, aguardando o servidor" : n.linhaDeEstado()) : "desligado";
    }
}
