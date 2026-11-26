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

import hoshiestar.regioes.FotoMundo;
import hoshiestar.regioes.Mapa;
import hoshiestar.regioes.MapaSecoes;
import hoshiestar.regioes.Regionizador;
import hoshiestar.teste.Verifica;

import java.util.Random;

/**
 * Diferencial: o regionizador novo (hoshiestar.regioes) e a regra de juncao do observador ({@link Regioes}) dao a MESMA
 * particao das secoes em milhares de fotos aleatorias (varias dimensoes, grupos de chunks e chunks soltos).
 */
public final class TesteRegioesObservador {

    public static void main(String[] args) {
        Verifica v = new Verifica("TesteRegioesObservador");
        try {
            Random rnd = new Random(7);
            int iguais = 0;
            int total = 3000;
            for (int rodada = 0; rodada < total; rodada++) {
                int nd = 1 + rnd.nextInt(3);
                FotoMundo[] mundos = new FotoMundo[nd];
                int[] dimIdx = new int[nd];
                for (int k = 0; k < nd; k++) {
                    mundos[k] = new FotoMundo(10 * k - 1);
                    dimIdx[k] = k;
                    int grupos = 1 + rnd.nextInt(6);
                    for (int g = 0; g < grupos; g++) {
                        int cx = rnd.nextInt(1200) - 600, cz = rnd.nextInt(1200) - 600;
                        int n = 1 + rnd.nextInt(8);
                        for (int i = 0; i < n; i++) {
                            mundos[k].chunk(cx + rnd.nextInt(40) - 20, cz + rnd.nextInt(40) - 20);
                        }
                    }
                    for (int i = 0; i < rnd.nextInt(20); i++) {
                        mundos[k].tile(rnd.nextInt(20000) - 10000, rnd.nextInt(20000) - 10000);
                    }
                }
                Foto velho = Regioes.calcular(mundos, dimIdx, 0, 0, 0);
                Mapa novo = new Regionizador().calcular(mundos, null);
                boolean ok = velho.nRegioes == novo.nRegioes && velho.mapa.tamanho() == novo.secoes.tamanho();
                // mesma particao: secoes na mesma regiao num lado se e so se no outro (compara cada secao com a primeira da regiao)
                int[] primeiraDoVelho = new int[velho.nRegioes];
                java.util.Arrays.fill(primeiraDoVelho, -1);
                for (int vaga = 0; ok && vaga < velho.mapa.tamanho(); vaga++) {
                    long chave = velho.mapa.chaveDaVaga(vaga);
                    int rv = velho.regiaoDaVaga[vaga];
                    int rn = novo.regiaoDaChave(chave);
                    if (rn < 0) {
                        ok = false;
                        break;
                    }
                    if (primeiraDoVelho[rv] < 0) {
                        primeiraDoVelho[rv] = rn;
                    } else if (primeiraDoVelho[rv] != rn) {
                        ok = false;
                    }
                }
                // e o contrario: regioes diferentes do velho nao caem na mesma do novo
                java.util.Set<Integer> usados = new java.util.HashSet<Integer>();
                for (int r = 0; ok && r < velho.nRegioes; r++) {
                    ok &= usados.add(primeiraDoVelho[r]);
                }
                if (ok) {
                    iguais++;
                }
            }
            v.igual(total, iguais, "regra do observador e regionizador novo: mesma particao em " + total + " fotos aleatorias");
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }
}
