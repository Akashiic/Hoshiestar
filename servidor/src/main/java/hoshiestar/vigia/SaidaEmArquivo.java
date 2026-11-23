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

import hoshiestar.api.Limitador;
import hoshiestar.api.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Saida dos relatorios do vigia: um arquivo {@code vigia-AAAA-MM-DD_HH-MM-SS-t<tick>.txt} na pasta
 * principal (padrao {@code hoshiestar/}, ao lado do hoshiestar.yml) e o mesmo texto no log do servidor
 * (aviso, prefixo "[Hoshiestar] [vigia] "). Se a pasta principal nao der para gravar, usa a pasta de
 * reserva (no servidor: {@code logs/hoshiestar-vigia}); se nenhuma der, o relatorio sai so no log, com
 * o motivo. Guarda no maximo {@code maxArquivos} arquivos vigia-*.txt em cada pasta (apaga os mais
 * velhos, so os deste padrao de nome). Roda so na thread do vigia.
 */
public final class SaidaEmArquivo implements NucleoVigia.Saida {

    static final Log LOG = Log.de("vigia");

    private static final Limitador ERROS = new Limitador(3, 600000L);

    private final File principal;
    private final File reserva;
    private final int maxArquivos;

    /** Caminho do ultimo arquivo gravado (para o status e os testes). */
    private volatile String ultimoArquivo;

    public SaidaEmArquivo(File principal, File reserva, int maxArquivos) {
        this.principal = principal;
        this.reserva = reserva;
        this.maxArquivos = maxArquivos;
    }

    @Override
    public void relatorio(long seq, String texto) {
        String nome = "vigia-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + "-t" + seq + ".txt";
        String onde;
        String motivo = null;
        File f = gravar(principal, nome, texto);
        if (f == null) {
            motivo = "nao consegui gravar em " + principal;
            f = gravar(reserva, nome, texto);
            if (f == null) {
                motivo += " nem em " + reserva;
            }
        }
        if (f != null) {
            onde = f.getPath() + (motivo != null ? " (pasta de reserva: " + motivo + ")" : "");
            ultimoArquivo = f.getPath();
        } else {
            onde = "NAO GRAVADO (" + motivo + "); o relatorio esta so neste log";
        }
        LOG.a(texto + "  Arquivo: " + onde);
    }

    @Override
    public void linha(String texto) {
        LOG.i(texto);
    }

    @Override
    public void erro(String texto, Throwable t) {
        if (ERROS.permitir("erro")) {
            LOG.e(texto, t);
        }
    }

    public String ultimoArquivo() {
        return ultimoArquivo;
    }

    private File gravar(File pasta, String nome, String texto) {
        if (pasta == null) {
            return null;
        }
        try {
            if (!pasta.isDirectory() && !pasta.mkdirs() && !pasta.isDirectory()) {
                return null;
            }
            File f = new File(pasta, nome);
            Writer w = new OutputStreamWriter(new FileOutputStream(f), Charset.forName("UTF-8"));
            try {
                w.write(texto);
            } finally {
                w.close();
            }
            apagarVelhos(pasta);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private void apagarVelhos(File pasta) {
        try {
            File[] todos = pasta.listFiles();
            if (todos == null) {
                return;
            }
            List<File> nossos = new ArrayList<File>();
            for (File f : todos) {
                String n = f.getName();
                if (f.isFile() && n.startsWith("vigia-") && n.endsWith(".txt")) {
                    nossos.add(f);
                }
            }
            if (nossos.size() <= maxArquivos) {
                return;
            }
            File[] ord = nossos.toArray(new File[0]);
            Arrays.sort(ord); // o nome comeca pela data: ordem alfabetica = ordem de tempo
            for (int i = 0; i < ord.length - maxArquivos; i++) {
                if (!ord[i].delete()) {
                    break;
                }
            }
        } catch (Throwable ignorado) {
            // limpeza e so cortesia
        }
    }
}
