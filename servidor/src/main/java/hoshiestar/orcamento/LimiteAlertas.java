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

/**
 * Limitador dos alertas: no maximo 1 por (regiao, tipo) a cada {@code intervaloMs} e no maximo {@code porMinuto} no total
 * em 60 s. So a thread do servidor usa. O relogio vem de fora (ms).
 */
final class LimiteAlertas {

    private final long intervaloMs;
    private final int porMinuto;
    private long[] ids = new long[32];
    private int[] tipos = new int[32];
    private long[] quando = new long[32];
    private int n;
    private final long[] recentes;
    private int pos;
    private int nRecentes;

    LimiteAlertas(long intervaloMs, int porMinuto) {
        this.intervaloMs = intervaloMs;
        this.porMinuto = porMinuto;
        this.recentes = new long[porMinuto];
    }

    boolean permitir(long id, int tipo, long agoraMs) {
        int achou = -1;
        for (int i = 0; i < n; i++) {
            if (ids[i] == id && tipos[i] == tipo) {
                achou = i;
                break;
            }
        }
        if (achou >= 0 && agoraMs - quando[achou] < intervaloMs) {
            return false;
        }
        // global: no maximo porMinuto nos ultimos 60 s
        int dentro = 0;
        for (int i = 0; i < nRecentes; i++) {
            if (agoraMs - recentes[i] < 60000L) {
                dentro++;
            }
        }
        if (dentro >= porMinuto) {
            return false;
        }
        if (achou < 0) {
            if (n == ids.length) {
                // descarta as mais velhas (limite de memoria): reaproveita a posicao mais antiga
                int velha = 0;
                for (int i = 1; i < n; i++) {
                    if (quando[i] < quando[velha]) {
                        velha = i;
                    }
                }
                achou = velha;
            } else {
                achou = n++;
            }
            ids[achou] = id;
            tipos[achou] = tipo;
        }
        quando[achou] = agoraMs;
        recentes[pos] = agoraMs;
        pos = (pos + 1) % recentes.length;
        if (nRecentes < recentes.length) {
            nRecentes++;
        }
        return true;
    }
}
