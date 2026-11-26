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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * Registro por tick em CSV (ferramenta de teste): {@code tick,regiao,rodou,ms}; a linha com regiao -1 e o tick do
 * servidor inteiro. Escrito so pela thread do servidor; descarrega a cada 200 ticks e ao fechar.
 */
final class RegistroCsv {

    private final BufferedWriter w;
    final File arquivo;
    final long fimMs;
    private int linhasDesdeFlush;
    private boolean fechado;

    RegistroCsv(File arquivo, long fimMs) throws IOException {
        this.arquivo = arquivo;
        this.fimMs = fimMs;
        if (arquivo.getParentFile() != null) {
            arquivo.getParentFile().mkdirs();
        }
        w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(arquivo), Charset.forName("UTF-8")), 1 << 16);
        w.write("tick,regiao,rodou,ms\n");
    }

    void linha(long tick, long regiao, boolean rodou, double ms) {
        if (fechado) {
            return;
        }
        try {
            w.write(tick + "," + regiao + "," + (rodou ? 1 : 0) + "," + String.format(java.util.Locale.ROOT, "%.3f", ms) + "\n");
            if (++linhasDesdeFlush >= 2000) {
                w.flush();
                linhasDesdeFlush = 0;
            }
        } catch (IOException e) {
            fechar();
        }
    }

    void fechar() {
        if (fechado) {
            return;
        }
        fechado = true;
        try {
            w.close();
        } catch (IOException e) {
            // nada a fazer
        }
    }

    boolean aberto() {
        return !fechado;
    }
}
