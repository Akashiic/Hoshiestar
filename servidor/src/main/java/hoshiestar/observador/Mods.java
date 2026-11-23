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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro dos nomes de mod (jar) que aparecem nas tabelas: cada nome ganha um numero pequeno. O numero 0 e "nenhum
 * mod" (codigo do servidor/Minecraft, ou sem amostra de pilha) e o 1 e "outros" (passou do limite de nomes). Seguro
 * entre threads (a tabela T4 e preenchida por outras threads).
 */
final class Mods {

    static final int NENHUM = 0;
    static final int OUTROS = 1;
    /** Cabe em 12 bits da chave das tabelas. */
    static final int MAX = 4000;
    /**
     * Largura de uma linha das tabelas de outra thread (T4 e lista): [item * PASSO + espaco]. Espaco 0 = "sem amostra de
     * pilha"; espaco 1 + id = amostrado, com o mod de numero id (id 0 = amostrado, mas sem mod). Cabe todo id (ate MAX).
     */
    static final int PASSO = 4096;

    /** Nome do que a tabela de outra thread guardou no espaco dado. */
    String nomeDoEspaco(int espaco) {
        return espaco == 0 ? "(sem amostra)" : nome(espaco - 1);
    }

    private final ConcurrentHashMap<String, Integer> ids = new ConcurrentHashMap<String, Integer>();
    private final List<String> nomes = new ArrayList<String>();

    Mods() {
        nomes.add("(nenhum)");
        nomes.add("(outros: limite de nomes)");
    }

    int id(String nome) {
        if (nome == null) {
            return NENHUM;
        }
        Integer i = ids.get(nome);
        if (i != null) {
            return i;
        }
        synchronized (this) {
            i = ids.get(nome);
            if (i != null) {
                return i;
            }
            if (nomes.size() >= MAX) {
                return OUTROS;
            }
            int novo = nomes.size();
            nomes.add(nome);
            ids.put(nome, novo);
            return novo;
        }
    }

    synchronized String nome(int id) {
        return id >= 0 && id < nomes.size() ? nomes.get(id) : "(?)";
    }

    synchronized int tamanho() {
        return nomes.size();
    }

    synchronized boolean cheio() {
        return nomes.size() >= MAX;
    }
}
