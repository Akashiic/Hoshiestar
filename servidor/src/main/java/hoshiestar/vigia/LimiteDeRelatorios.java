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
package hoshiestar.vigia;

/**
 * Limitador dos relatorios do vigia: um intervalo minimo entre dois relatorios e um maximo por janela
 * fixa de tempo. So a thread do vigia chama {@link #permitir}; o "hoshie status" le os numeros.
 * O tempo vem de fora (nanossegundos), para os testes isolados poderem simular horas em milissegundos.
 */
public final class LimiteDeRelatorios {

    private final long intervaloMinNanos;
    private final int maxPorJanela;
    private final long janelaNanos;

    private boolean temUltimo;
    private long ultimo;
    private boolean janelaAberta;
    private long inicioJanela;
    private int usadosNaJanela;

    public LimiteDeRelatorios(long intervaloMinMs, int maxPorJanela, long janelaMs) {
        if (intervaloMinMs < 0 || maxPorJanela < 1 || janelaMs < 1) {
            throw new IllegalArgumentException("limite invalido");
        }
        this.intervaloMinNanos = intervaloMinMs * 1000000L;
        this.maxPorJanela = maxPorJanela;
        this.janelaNanos = janelaMs * 1000000L;
    }

    /** true = pode sair um relatorio agora (e ele ja e contado); false = nao (nada muda). */
    public synchronized boolean permitir(long agoraNanos) {
        if (temUltimo && agoraNanos - ultimo < intervaloMinNanos) {
            return false;
        }
        if (!janelaAberta || agoraNanos - inicioJanela >= janelaNanos) {
            janelaAberta = true;
            inicioJanela = agoraNanos;
            usadosNaJanela = 0;
        }
        if (usadosNaJanela >= maxPorJanela) {
            return false;
        }
        usadosNaJanela++;
        ultimo = agoraNanos;
        temUltimo = true;
        return true;
    }

    /** Relatorios ja usados na janela atual (para o status). */
    public synchronized int usadosNaJanela() {
        return usadosNaJanela;
    }

    public int maxPorJanela() {
        return maxPorJanela;
    }
}
