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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * Texto dos despejos de pilha (plano do Degrau B, secao 2: a cada fatia de espera vencida, a pilha de cada trabalhador
 * atrasado e as threads em deadlock). So le: {@code Thread.getStackTrace} e o {@code ThreadMXBean} nao interrompem nem
 * param ninguem alem do instante da leitura. Sem estado.
 */
final class Despejo {

    /** Limite de quadros por pilha no log. */
    static final int MAX_QUADROS = 48;

    private Despejo() {
    }

    /** Acrescenta "nome (estado da JVM)" e a pilha da thread. */
    static void pilha(StringBuilder sb, Thread t) {
        if (t == null) {
            sb.append("  (thread desconhecida)\n");
            return;
        }
        StackTraceElement[] p;
        try {
            p = t.getStackTrace();
        } catch (Throwable e) {
            sb.append("  ").append(t.getName()).append(": pilha indisponivel (").append(e).append(")\n");
            return;
        }
        sb.append("  thread \"").append(t.getName()).append("\" (").append(t.getState()).append(")\n");
        quadros(sb, p);
    }

    static void quadros(StringBuilder sb, StackTraceElement[] p) {
        if (p == null || p.length == 0) {
            sb.append("      (sem quadros)\n");
            return;
        }
        int n = Math.min(p.length, MAX_QUADROS);
        for (int i = 0; i < n; i++) {
            sb.append("      at ").append(p[i]).append('\n');
        }
        if (p.length > n) {
            sb.append("      ... mais ").append(p.length - n).append(" quadro(s)\n");
        }
    }

    /** Acrescenta a lista das threads em deadlock de monitor ou de trava (ou "nenhuma"). */
    static void emDeadlock(StringBuilder sb) {
        try {
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            long[] ids;
            try {
                ids = mx.findDeadlockedThreads();
            } catch (UnsupportedOperationException e) {
                ids = mx.findMonitorDeadlockedThreads();
            }
            if (ids == null || ids.length == 0) {
                sb.append("  threads em deadlock (findDeadlockedThreads): nenhuma\n");
                return;
            }
            sb.append("  threads em deadlock (findDeadlockedThreads): ").append(ids.length).append('\n');
            ThreadInfo[] infos = mx.getThreadInfo(ids, MAX_QUADROS);
            for (ThreadInfo ti : infos) {
                if (ti == null) {
                    continue;
                }
                sb.append("    \"").append(ti.getThreadName()).append("\" espera ").append(ti.getLockName())
                        .append(" de \"").append(ti.getLockOwnerName()).append("\"\n");
                quadros(sb, ti.getStackTrace());
            }
        } catch (Throwable e) {
            sb.append("  threads em deadlock: consulta falhou (").append(e).append(")\n");
        }
    }
}
