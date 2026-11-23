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

import java.io.File;
import java.util.List;

/**
 * O que o {@link Medidor} precisa saber do jogo, sem citar classe do Minecraft. No servidor, quem
 * responde e {@link GanchosMedidor.JogoMinecraft}; nos testes isolados, um jogo falso. Assim o teste
 * exercita a MESMA logica (pilha de quadros, coleta, valvula, autoteste, relatorio) que roda no
 * servidor.
 */
public interface Jogo {

    /**
     * O objeto de um quadro aberto ja "morreu"? (entidade com isDead; tile entity invalida). E o sinal
     * de que o tick dele lancou excecao e o gancho de fim nunca vai rodar. So a thread do servidor chama.
     *
     * @param tipo {@link Coleta#TE} ou {@link Coleta#ENT}
     */
    boolean morto(Object o, int tipo);

    /** Foto dos mundos carregados, para o autoteste (duas vezes por medicao; pode alocar). */
    List<Mundo> mundos();

    /** Pasta de saida: &lt;pasta do servidor&gt;/hoshiestar. */
    File pastaDeSaida();

    /** Um mundo carregado, visto pelo autoteste. */
    final class Mundo {
        public final Object mundo;
        public final int dimId;
        public final int nTe;
        public final int nEnt;
        public final boolean temJogador;
        public final boolean temForcado;

        public Mundo(Object mundo, int dimId, int nTe, int nEnt, boolean temJogador, boolean temForcado) {
            this.mundo = mundo;
            this.dimId = dimId;
            this.nTe = nTe;
            this.nEnt = nEnt;
            this.temJogador = temJogador;
            this.temForcado = temForcado;
        }
    }
}
