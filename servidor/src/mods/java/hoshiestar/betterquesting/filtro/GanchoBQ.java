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
package hoshiestar.betterquesting.filtro;

import betterquesting.api2.utils.ParticipantInfo;
import bq_standard.tasks.ITaskInventory;
import cpw.mods.fml.common.gameevent.TickEvent;
import hoshiestar.api.ThreadServidor;
import hoshiestar.betterquesting.BetterQuesting;

import java.util.HashSet;
import java.util.Map;

/**
 * Ganchos chamados DE DENTRO de {@code bq_standard.handlers.EventHandler.onServerTick} depois de
 * transformado:
 *
 * <pre>
 * public void onServerTick(ServerTickEvent event) {
 *     GanchoBQ.inicioDoTick(this, event);                               // novo (formato 1)
 *     if (event.phase != START) return;
 *     synchronized (playerInventoryUpdates) {
 *         for (...) for (...) for (...)
 *             if (task instanceof ITaskInventory)
 *                 GanchoBQ.aoMudarInventario((ITaskInventory) task, entry, pInfo);   // era task.onInventoryChange(entry, pInfo)
 *         GanchoBQ.fimDoLaco(playerInventoryUpdates);                   // era playerInventoryUpdates.clear()
 *     }
 * }
 * </pre>
 *
 * Regras: a chamada ORIGINAL ({@code task.onInventoryChange}, {@code conjunto.clear()}) fica sempre
 * FORA de try/catch, entao as excecoes dela saem exatamente como sairiam sem nos. O nosso codigo fica
 * sempre DENTRO de try/catch: qualquer excecao nossa desliga o recurso ate reiniciar (com registro no
 * log, limitado) e a chamada original roda. O tratamento de erro nao depende de FiltroInventario (se
 * ate a carga dela falhasse, nada escaparia daqui). Modo desligado (inclusive depois de um erro): so o
 * original, e os tres ganchos nem chamam FiltroInventario (um erro conta uma vez so). Outra thread que
 * nao a do servidor: so o original, sem tocar no cache.
 */
public final class GanchoBQ {

    private GanchoBQ() {
    }

    /**
     * Entrada de onServerTick (as duas fases): limpa o cache do tick. Com o recurso desligado (so
     * acontece depois de "DESLIGADO POR ERRO", porque desligado no boot a classe nem e alterada) nao
     * faz nada: o cache ja foi solto no erro e nao e mais usado, e uma falha que se repetisse aqui a
     * cada tick nao vira um erro novo por tick.
     */
    public static void inicioDoTick(Object handler, TickEvent.ServerTickEvent evento) {
        if (BetterQuesting.modo == BetterQuesting.DESLIGADO || !ThreadServidor.souAThreadDoServidor()) {
            return;
        }
        try {
            FiltroInventario.inicioDoTick();
        } catch (Throwable t) {
            falhou("inicioDoTick", t);
        }
    }

    /** No lugar de {@code task.onInventoryChange(quest, pInfo)}. */
    public static void aoMudarInventario(ITaskInventory task, Map.Entry quest, ParticipantInfo pInfo) {
        int modo = BetterQuesting.modo;
        if (modo == BetterQuesting.DESLIGADO) {
            task.onInventoryChange(quest, pInfo);
            return;
        }
        if (!ThreadServidor.souAThreadDoServidor()) {
            contarOutraThread();
            task.onInventoryChange(quest, pInfo);
            return;
        }
        int codigo;
        try {
            codigo = task == null || pInfo == null ? BetterQuesting.PASSA_DADO_ESTRANHO : FiltroInventario.decidir(task, pInfo);
            BetterQuesting.contagem[codigo]++;
        } catch (Throwable t) {
            falhou("decisao", t);
            codigo = BetterQuesting.PASSA_ERRO;
            BetterQuesting.contagem[codigo]++;
        }
        if (codigo <= BetterQuesting.PULA_SEM_ITEM) {
            if (modo == BetterQuesting.LIGADO) {
                return; // comprovadamente nao faria nada
            }
            // verificar: roda o original e compara
            FiltroInventario.Foto foto = null;
            try {
                foto = FiltroInventario.fotografar(task, pInfo);
            } catch (Throwable t) {
                falhou("verificar (foto)", t);
            }
            task.onInventoryChange(quest, pInfo);
            try {
                if (foto != null) {
                    FiltroInventario.registrarComparacao(FiltroInventario.comparar(foto, task, pInfo), codigo, quest, task);
                }
                FiltroInventario.depoisDoOriginal(task);
            } catch (Throwable t) {
                falhou("verificar (comparacao)", t);
            }
            return;
        }
        task.onInventoryChange(quest, pInfo);
        try {
            FiltroInventario.depoisDoOriginal(task);
        } catch (Throwable t) {
            falhou("depois do original", t);
        }
    }

    /**
     * No lugar de {@code playerInventoryUpdates.clear()} no fim do laco: o mesmo clear(), sempre; depois
     * limpa o cache (nada disso com o recurso desligado, pelo mesmo motivo de {@link #inicioDoTick}).
     */
    public static void fimDoLaco(HashSet conjunto) {
        conjunto.clear();
        if (BetterQuesting.modo == BetterQuesting.DESLIGADO || !ThreadServidor.souAThreadDoServidor()) {
            return;
        }
        try {
            FiltroInventario.fimDoLaco();
        } catch (Throwable t) {
            falhou("fimDoLaco", t);
        }
    }

    private static synchronized void contarOutraThread() {
        BetterQuesting.contagem[BetterQuesting.OUTRA_THREAD]++;
    }

    /** Excecao nossa: desliga o recurso (com registro) e solta o cache. Nunca lanca. */
    private static void falhou(String onde, Throwable t) {
        try {
            BetterQuesting.desligarPorErro(onde, t);
        } catch (Throwable ignorado) {
            // nada a fazer
        }
        try {
            FiltroInventario.esquecer();
        } catch (Throwable ignorado) {
            // o recurso ja esta desligado: o cache nao e mais usado
        }
    }
}
