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
package hoshiestar.teste.chromaticraft.mundo;

import net.minecraft.world.gen.ChunkProviderServer;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mapa de chunks de teste: um {@code ChunkProviderServer} (do Minecraft vanilla, com nomes SRG) que so responde
 * {@code chunkExists} ({@code func_73149_a}), consultando um conjunto estatico. E alocado SEM construtor (o construtor
 * de verdade precisa de um mundo). Carregado pelo class loader de teste, junto com o Minecraft e o gancho.
 */
public final class ProvedorFalso extends ChunkProviderServer {

    private static final Set<Long> CARREGADOS = new HashSet<Long>();
    private static int consultas;
    /** Modo "tudo carregado": responde true para qualquer chunk (e o teste da cola olha QUAIS chunks foram perguntados). */
    private static boolean todos;
    private static final Set<String> CONSULTADOS = new TreeSet<String>();

    private ProvedorFalso() {
        super(null, null, null); // nunca chamado: o teste aloca sem construtor
    }

    private static long chave(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public static void carregar(int cx, int cz) {
        CARREGADOS.add(chave(cx, cz));
    }

    public static void limpar() {
        CARREGADOS.clear();
        CONSULTADOS.clear();
        todos = false;
        consultas = 0;
    }

    public static void todosCarregados(boolean b) {
        todos = b;
    }

    /** Os chunks perguntados desde o ultimo {@link #limpar()}, sem repeticao, em ordem de texto: "cx:cz cx:cz ...". */
    public static String consultados() {
        StringBuilder sb = new StringBuilder();
        for (String c : CONSULTADOS) {
            sb.append(sb.length() == 0 ? "" : " ").append(c);
        }
        return sb.toString();
    }

    public static int consultas() {
        return consultas;
    }

    /** ChunkProviderServer.chunkExists (nome de tempo de execucao). */
    @Override
    public boolean func_73149_a(int cx, int cz) {
        consultas++;
        CONSULTADOS.add(cx + ":" + cz);
        return todos || CARREGADOS.contains(chave(cx, cz));
    }
}
