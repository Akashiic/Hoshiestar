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

import hoshiestar.config.HoshiestarConfig;

import java.util.Collections;
import java.util.List;

/**
 * Porta de entrada do recurso {@code paralelo} (Degrau B: fase paralela sincronizada com barreira). Neste passo so existem
 * o interruptor, as recusas e o comando; nenhuma fase, nenhuma thread de regiao e nenhum gancho no tick.
 *
 * <p>Esta classe nao cita o Minecraft nem o Bukkit (e carregada ate pelo {@code FMLCommonHandler}, cedo no boot).
 */
public final class Paralelo {

    public static final String ID = "paralelo";

    /** Prefixo do nome das threads de regiao (plano, secao 2: "Hoshiestar Regiao #i"). A classe ThreadRegiao e do passo 5. */
    public static final String PREFIXO_THREAD_REGIAO = "Hoshiestar Regiao #";

    /**
     * {@code paralelo.ligado} do hoshiestar.yml, lido uma vez (na primeira vez que a classe e usada, depois do boot do
     * config). Falso (padrao) = nenhum gancho faz nada alem de um teste deste campo: o comportamento original.
     */
    public static final boolean LIGADO = lerLigado();

    private static volatile Parametros params = Parametros.padrao();
    /**
     * O motivo que vale enquanto {@link #configurar} nao rodou. O estado inicial RECUSA (fail-closed): se o registro do
     * paralelo nao rodar (ele e o ultimo do catalogo, e um erro num recurso anterior interrompe o catalogo inteiro),
     * nenhuma recusa foi avaliada e {@link #aceito} tem de ser falso mesmo com o config ligado.
     */
    static final String NAO_AVALIADO = "recusas do boot nao avaliadas: o registro do paralelo nao rodou (um erro no registro de outro recurso interrompe o catalogo; veja o log do boot)";
    private static volatile List<String> motivos = Collections.singletonList(NAO_AVALIADO);

    private Paralelo() {
    }

    private static boolean lerLigado() {
        try {
            return HoshiestarConfig.atual().paralelo_ligado;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A thread e uma thread de regiao? Hoje so o nome diz (as threads vem no passo 5; o criterio do plano e o nome
     * "Hoshiestar Regiao #i"). Desligado, sempre falso: nenhum nome muda o resultado original de {@code getEffectiveSide}.
     */
    public static boolean ehThreadDeRegiao(Thread t) {
        return LIGADO && ehNomeDeRegiao(t.getName());
    }

    /** O criterio de nome, sem o interruptor (para teste). */
    public static boolean ehNomeDeRegiao(String nome) {
        return nome != null && nome.startsWith(PREFIXO_THREAD_REGIAO);
    }

    /** Fase de coremod: guarda os parametros lidos e os motivos de recusa do boot. */
    static void configurar(Parametros p, List<String> m) {
        params = p;
        motivos = Collections.unmodifiableList(m);
    }

    public static Parametros parametros() {
        return params;
    }

    /**
     * Os motivos de recusa do boot (vazia = nenhum; tambem vazia quando o recurso esta desligado e o registro rodou).
     * Antes do registro: so {@link #NAO_AVALIADO}.
     */
    public static List<String> motivos() {
        return motivos;
    }

    /**
     * Ligado no config, recusas avaliadas e nenhuma recusa. Sem o registro, falso. Ainda nao liga nenhuma fase (passos 3
     * a 6 do plano).
     */
    public static boolean aceito() {
        return LIGADO && motivos.isEmpty();
    }

    /** Linha de estado para o "hoshie status". */
    public static String linhaDeEstado() {
        if (!LIGADO) {
            return "desligado";
        }
        if (!motivos.isEmpty()) {
            return "ligado no config, mas RECUSADO no boot (" + motivos.size() + " motivo(s); veja 'hoshie paralelo motivos'); fase ainda nao implementada";
        }
        return "ligado no config, modo " + params.modo + ", sem recusa; fase ainda nao implementada (nenhum gancho roda)";
    }
}
