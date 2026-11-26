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

import hoshiestar.regioes.Mapa;
import hoshiestar.regioes.MapaSecoes;

import java.util.HashMap;
import java.util.Map;

/**
 * Passa o estado das regioes do mapa velho para as do mapa novo, pelas secoes (os numeros mudam a cada calculo).
 * Juncao: a regiao nova herda de todas as velhas que tinha dentro; vale o maior N e as maiores medidas. Divisao: a parte
 * com mais secoes da velha fica com o id e o objeto; as outras recebem copia com id novo, e a histerese tira o corte de
 * quem nao pesa. Regiao nova sem nenhuma secao velha comeca em N = 1. Quando a regiao e identica a uma velha, o objeto
 * passa intacto (a estatistica continua); em qualquer outra mudanca a estatistica recomeca e N, folga e alerta ficam.
 */
final class Heranca {

    /** Quantas regioes se juntaram (novas com mais de uma velha) e quantas se dividiram (velhas com mais de uma nova). */
    int juncoes;
    int divisoes;
    long proximoId;

    Heranca(long proximoId) {
        this.proximoId = proximoId;
    }

    Estado[] aplicar(Mapa velho, Estado[] velhos, Mapa novo, long agoraMs) {
        int nv = novo.nRegioes;
        Estado[] r = new Estado[nv];
        if (velho == null || velhos == null) {
            for (int i = 0; i < nv; i++) {
                r[i] = new Estado(proximoId++, agoraMs);
            }
            return r;
        }
        Map<Long, int[]> cont = new HashMap<Long, int[]>();
        MapaSecoes sn = novo.secoes;
        for (int w = 0; w < sn.tamanho(); w++) {
            int ro = velho.regiaoDaChave(sn.chaveDaVaga(w));
            if (ro < 0) {
                continue;
            }
            int rn = novo.regiaoDaVaga[w];
            long k = ((long) ro << 32) | rn;
            int[] c = cont.get(k);
            if (c == null) {
                cont.put(k, new int[] {1});
            } else {
                c[0]++;
            }
        }
        // vencedora de cada velha: a nova com mais secoes dela (empate: a de menor indice)
        int[] vencedora = new int[velho.nRegioes];
        int[] melhor = new int[velho.nRegioes];
        java.util.Arrays.fill(vencedora, -1);
        for (Map.Entry<Long, int[]> e : cont.entrySet()) {
            int ro = (int) (e.getKey() >> 32);
            int rn = (int) (long) e.getKey();
            int c = e.getValue()[0];
            if (c > melhor[ro] || (c == melhor[ro] && rn < vencedora[ro])) {
                melhor[ro] = c;
                vencedora[ro] = rn;
            }
        }
        int[] novasPorVelha = new int[velho.nRegioes];
        for (Map.Entry<Long, int[]> e : cont.entrySet()) {
            novasPorVelha[(int) (e.getKey() >> 32)]++;
        }
        for (int ro = 0; ro < velho.nRegioes; ro++) {
            if (novasPorVelha[ro] > 1) {
                divisoes++;
            }
        }
        for (int rn = 0; rn < nv; rn++) {
            // velhas que tocam esta nova
            int tocadas = 0;
            int ro1 = -1;
            int sobreposicaoTotal = 0;
            Estado base = null;
            int baseRo = -1;
            for (int ro = 0; ro < velho.nRegioes; ro++) {
                int[] c = cont.get(((long) ro << 32) | rn);
                if (c == null) {
                    continue;
                }
                tocadas++;
                ro1 = ro;
                sobreposicaoTotal += c[0];
                if (vencedora[ro] == rn && (base == null || velhos[ro].id < base.id)) {
                    base = velhos[ro];
                    baseRo = ro;
                }
            }
            if (tocadas == 0) {
                r[rn] = new Estado(proximoId++, agoraMs);
                continue;
            }
            if (tocadas > 1) {
                juncoes++;
            }
            boolean identica = tocadas == 1 && base != null && sobreposicaoTotal == velho.nSecoes[ro1]
                    && sobreposicaoTotal == novo.nSecoes[rn];
            if (base == null) {
                // so recebe pedacos de velhas que ficaram com outra parte: copia da que mais lhe deu
                int melhorRo = -1, melhorC = -1;
                for (int ro = 0; ro < velho.nRegioes; ro++) {
                    int[] c = cont.get(((long) ro << 32) | rn);
                    if (c != null && c[0] > melhorC) {
                        melhorC = c[0];
                        melhorRo = ro;
                    }
                }
                base = velhos[melhorRo].paraDivisao(proximoId++, agoraMs);
                baseRo = melhorRo;
                base.reiniciarEstatistica();
            } else if (!identica) {
                base.reiniciarEstatistica();
            }
            for (int ro = 0; ro < velho.nRegioes; ro++) {
                if (ro != baseRo && cont.containsKey(((long) ro << 32) | rn)) {
                    base.mesclar(velhos[ro]);
                }
            }
            r[rn] = base;
        }
        return r;
    }
}
