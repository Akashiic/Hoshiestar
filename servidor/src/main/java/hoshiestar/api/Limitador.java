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
package hoshiestar.api;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Limitador de frequencia: no maximo N mensagens por janela de tempo, por chave (ex.: nome do
 * jogador, ou "geral"). Conta o que foi suprimido.
 *
 * <p>Seguro entre threads (synchronized). Nao e para o caminho quente do tick: e para decidir se
 * uma mensagem de log sai. Guarda no maximo {@code maxChaves} chaves; quando enche, esquece as
 * janelas vencidas sem nada suprimido e, se ainda estiver cheio, esquece tudo (pior caso: deixa
 * passar uma mensagem a mais por chave).
 *
 * <pre>
 * static final Limitador LIM = new Limitador(1, 10000);   // 1 por 10 s por chave
 * ...
 * LOG.aLim(LIM, nomeJogador, "recusado: ...");
 * </pre>
 *
 * <p>A fonte de tempo pode ser trocada (construtor com {@link Tempo}) para os testes isolados.
 */
public final class Limitador {

    /** Fonte de tempo em nanossegundos (troca nos testes). */
    public interface Tempo {
        long nanos();
    }

    private static final Tempo SISTEMA = new Tempo() {
        @Override
        public long nanos() {
            return System.nanoTime();
        }
    };

    private final int maxPorJanela;
    private final long janelaNanos;
    private final int maxChaves;
    private final Tempo tempo;
    private final Map<String, long[]> mapa = new HashMap<String, long[]>();

    /** @param maxPorJanela mensagens permitidas por janela (1 ou mais); @param janelaMs janela em ms. */
    public Limitador(int maxPorJanela, long janelaMs) {
        this(maxPorJanela, janelaMs, 4096, SISTEMA);
    }

    public Limitador(int maxPorJanela, long janelaMs, int maxChaves) {
        this(maxPorJanela, janelaMs, maxChaves, SISTEMA);
    }

    public Limitador(int maxPorJanela, long janelaMs, int maxChaves, Tempo tempo) {
        if (maxPorJanela < 1 || janelaMs < 1 || maxChaves < 1 || tempo == null) {
            throw new IllegalArgumentException("limitador invalido");
        }
        this.maxPorJanela = maxPorJanela;
        this.janelaNanos = janelaMs * 1000000L;
        this.maxChaves = maxChaves;
        this.tempo = tempo;
    }

    /**
     * Tenta registrar uma mensagem com esta chave. Devolve -1 se ela deve ser suprimida; senao
     * devolve quantas foram suprimidas desde a ultima que passou (0 = nenhuma) e zera essa conta.
     */
    public synchronized long tentar(String chave) {
        String k = chave == null ? "" : chave;
        long agora = tempo.nanos();
        long[] e = mapa.get(k); // {inicio da janela, usadas na janela, suprimidas}
        if (e == null) {
            if (mapa.size() >= maxChaves) {
                limpar(agora);
            }
            e = new long[] {agora, 0, 0};
            mapa.put(k, e);
        }
        if (agora - e[0] >= janelaNanos) {
            e[0] = agora;
            e[1] = 0;
        }
        if (e[1] < maxPorJanela) {
            e[1]++;
            long sup = e[2];
            e[2] = 0;
            return sup;
        }
        e[2]++;
        return -1;
    }

    /** true se a mensagem pode sair (atalho de {@link #tentar(String)} &gt;= 0). */
    public boolean permitir(String chave) {
        return tentar(chave) >= 0;
    }

    /** Numero de chaves guardadas (para testes). */
    public synchronized int chaves() {
        return mapa.size();
    }

    private void limpar(long agora) {
        Iterator<Map.Entry<String, long[]>> it = mapa.entrySet().iterator();
        while (it.hasNext()) {
            long[] v = it.next().getValue();
            if (agora - v[0] >= janelaNanos && v[2] == 0) {
                it.remove();
            }
        }
        if (mapa.size() >= maxChaves) {
            mapa.clear();
        }
    }
}
