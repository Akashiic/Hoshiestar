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

import java.util.ArrayList;
import java.util.List;

/**
 * Os invariantes por tick (plano do Degrau B, secao 3): coisas que nenhum tile da fase pode mudar. Sem Minecraft: o
 * adaptador tira a {@link Foto} (numeros e referencias) e esta classe compara.
 *
 * <p>No modo {@code sombra} (passo 3) a foto e tirada antes de cada tile de regiao elegivel e conferida logo depois do tick
 * dele (o "lote R" de um tile so). Uma diferenca la e algo que, na fase de verdade, seria violacao. No passo 6 a mesma
 * comparacao vale entre o comeco de {@code rodarFase} e a barreira.
 */
public final class Invariantes {

    /** Os itens da foto, na ordem. */
    public static final String[] NOMES = {
        "lista de tiles (objeto)",
        "lista de tiles (tamanho)",
        "tiles pendentes (objeto)",
        "tiles pendentes (tamanho)",
        "lista de entidades (objeto)",
        "lista de entidades (tamanho)",
        "versao do mapa de chunks",
        "fila de ticks agendados (tamanho)",
        "mapa de regioes (objeto)",
        "captura de blocos ou de drops",
    };
    public static final int N = NOMES.length;

    /** Uma foto: um valor por item (referencia = identityHashCode do objeto ou -1 se nulo). Reaproveitavel. */
    public static final class Foto {
        final long[] v = new long[N];
        boolean tirada;

        public void tirar(Object tiles, int nTiles, Object pendentes, int nPendentes, Object entidades, int nEntidades,
                long versaoMapaChunks, int nAgendados, Object mapa, boolean captura) {
            v[0] = id(tiles);
            v[1] = nTiles;
            v[2] = id(pendentes);
            v[3] = nPendentes;
            v[4] = id(entidades);
            v[5] = nEntidades;
            v[6] = versaoMapaChunks;
            v[7] = nAgendados;
            v[8] = id(mapa);
            v[9] = captura ? 1 : 0;
            tirada = true;
        }

        public boolean tirada() {
            return tirada;
        }

        public void esquecer() {
            tirada = false;
        }

        private static long id(Object o) {
            return o == null ? -1 : System.identityHashCode(o);
        }
    }

    private Invariantes() {
    }

    /** Os itens que mudaram entre as duas fotos (vazio = nada mudou). Foto nao tirada = nada a comparar. */
    public static List<String> comparar(Foto antes, Foto depois) {
        List<String> r = new ArrayList<String>(0);
        if (antes == null || depois == null || !antes.tirada || !depois.tirada) {
            return r;
        }
        for (int i = 0; i < N; i++) {
            if (antes.v[i] != depois.v[i]) {
                r.add(NOMES[i]);
            }
        }
        // captura ligada na foto de depois tambem e quebra, mesmo que ja estivesse ligada antes (nao deveria estar)
        if (depois.v[9] != 0 && antes.v[9] != 0) {
            r.add(NOMES[9] + " (ligada)");
        }
        return r;
    }
}
