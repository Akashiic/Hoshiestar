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
package hoshiestar.teste.arsmagica;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Conta (e engole) as pilhas de {@code NullPointerException} que o AM2 REAL imprime com
 * {@code e.printStackTrace()} dentro do proprio try quando uma entrada do mapa some no meio da varredura
 * (comportamento do AM2, igual no original e no transformado). O resto da saida de erro passa adiante.
 */
public final class ErroCapturado {

    private static int nulos;
    private static boolean instalado;

    private ErroCapturado() {
    }

    public static synchronized void instalar() {
        if (instalado) {
            return;
        }
        instalado = true;
        final PrintStream original = System.err;
        System.setErr(new PrintStream(new OutputStream() {
            private final StringBuilder linha = new StringBuilder();
            private boolean engolindo;

            @Override
            public void write(int b) {
                if (b == '\n') {
                    fechar();
                } else {
                    linha.append((char) b);
                }
            }

            private void fechar() {
                String l = linha.toString();
                linha.setLength(0);
                if (l.startsWith("java.lang.NullPointerException")) {
                    synchronized (ErroCapturado.class) {
                        nulos++;
                    }
                    engolindo = true;
                    return;
                }
                if (engolindo && (l.startsWith("\tat ") || l.startsWith("\t...") || l.startsWith("Caused by"))) {
                    return;
                }
                engolindo = false;
                original.println(l);
            }
        }, true));
    }

    /** Pilhas de NullPointerException vistas ate agora. */
    public static synchronized int contar() {
        return nulos;
    }
}
