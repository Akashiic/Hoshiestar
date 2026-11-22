/*
 * Hoshiestar-Core: base comum dos recursos da Etapa 1 do projeto Hoshiestar.
 * Copyright (C) 2026 Projeto Hoshiestar
 *
 * Este programa e software livre: voce pode redistribui-lo e/ou modifica-lo
 * sob os termos da GNU General Public License publicada pela Free Software
 * Foundation, versao 3 da Licenca ou (a seu criterio) qualquer versao posterior.
 *
 * Este programa e distribuido na esperanca de que seja util, mas SEM QUALQUER
 * GARANTIA; sem mesmo a garantia implicita de COMERCIABILIDADE ou ADEQUACAO A
 * UM PROPOSITO ESPECIFICO. Veja a GNU General Public License para mais detalhes.
 *
 * Voce deve ter recebido uma copia da GNU General Public License junto com este
 * programa. Se nao, veja <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package hoshiestar.core.api;

import java.util.HashMap;
import java.util.Map;

/**
 * Limitador de frequencia reutilizavel: no maximo N mensagens por janela de tempo, por chave
 * (ex.: chave = nome do jogador, ou "geral"). Conta o que foi suprimido.
 *
 * <p>Seguro entre threads (synchronized). Nao e para o caminho quente de cada tick: e para decidir
 * se uma mensagem de log sai. Guarda no maximo {@code maxChaves} chaves; quando enche, esquece as
 * janelas ja vencidas e, se ainda estiver cheio, esquece tudo (pior caso: deixa passar uma mensagem
 * a mais por chave).
 *
 * <pre>
 * static final Limitador LIM = new Limitador(1, 10_000);   // 1 por 10 s por chave
 * ...
 * LOG.aLim(LIM, nomeJogador, "Shift recusado: ...");
 * </pre>
 */
public final class Limitador {

    private final int maxPorJanela;
    private final long janelaNanos;
    private final int maxChaves;
    private final Map<String, long[]> mapa = new HashMap<String, long[]>();

    /** @param maxPorJanela mensagens permitidas por janela (1 ou mais); @param janelaMs tamanho da janela em ms. */
    public Limitador(int maxPorJanela, long janelaMs) {
        this(maxPorJanela, janelaMs, 4096);
    }

    public Limitador(int maxPorJanela, long janelaMs, int maxChaves) {
        if (maxPorJanela < 1 || janelaMs < 1 || maxChaves < 1) {
            throw new IllegalArgumentException("limitador invalido");
        }
        this.maxPorJanela = maxPorJanela;
        this.janelaNanos = janelaMs * 1000000L;
        this.maxChaves = maxChaves;
    }

    /**
     * Tenta registrar uma mensagem com esta chave. Devolve -1 se deve ser suprimida; senao devolve
     * quantas foram suprimidas desde a ultima que passou (0 = nenhuma) e zera essa conta.
     */
    public synchronized long tentar(String chave) {
        String k = chave == null ? "" : chave;
        long agora = System.nanoTime();
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

    private void limpar(long agora) {
        mapa.entrySet().removeIf(en -> agora - en.getValue()[0] >= janelaNanos && en.getValue()[2] == 0);
        if (mapa.size() >= maxChaves) {
            mapa.clear();
        }
    }
}
