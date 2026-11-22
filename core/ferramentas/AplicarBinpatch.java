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

import cpw.mods.fml.common.patcher.ClassPatch;
import cpw.mods.fml.common.patcher.ClassPatchManager;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;
import com.google.common.collect.ListMultimap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Ferramenta SO DE BUILD (nao vai no jar do mod). Aplica TODOS os binpatches do servidor
 * (binpatches.pack.lzma de dentro do server.jar) nas classes do jar vanilla, usando o proprio
 * ClassPatchManager do FML, do mesmo jeito que o servidor faz ao carregar cada classe.
 *
 * Uso (JDK 8, porque o FML 1.7.10 usa java.util.jar.Pack200):
 *   java -cp <esta-classe>:server.jar:lzma.jar:guava.jar AplicarBinpatch <vanilla.jar> <pasta-de-saida>
 *
 * Saida: uma .class por classe com patch, com o nome do jar (nomes "notch" para as classes do
 * Minecraft), ja com o patch aplicado. Classes que o patch cria do zero tambem saem.
 * Base: o ApplyBin.java do conferente do item 1.E (docs/pesquisa/etapa1/E-chunkmap-conferencia.md).
 */
public class AplicarBinpatch {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("uso: AplicarBinpatch <vanilla.jar> <pasta-de-saida>");
            System.exit(2);
        }
        // O FMLRelaunchLog precisa saber o lado antes do setup (senao da NullPointerException).
        Field sf = FMLRelaunchLog.class.getDeclaredField("side");
        sf.setAccessible(true);
        sf.set(null, Side.SERVER);

        ClassPatchManager m = ClassPatchManager.INSTANCE;
        m.setup(Side.SERVER);

        Field pf = ClassPatchManager.class.getDeclaredField("patches");
        pf.setAccessible(true);
        @SuppressWarnings("unchecked")
        ListMultimap<String, ClassPatch> patches = (ListMultimap<String, ClassPatch>) pf.get(m);
        if (patches == null || patches.isEmpty()) {
            System.err.println("ERRO: nenhum binpatch lido do server.jar");
            System.exit(1);
        }
        List<String> nomes = new ArrayList<String>(patches.keySet());
        Collections.sort(nomes);

        File saida = new File(args[1]);
        ZipFile vz = new ZipFile(args[0]);
        int n = 0;
        int novas = 0;
        try {
            for (String nome : nomes) {
                ZipEntry e = vz.getEntry(nome.replace('.', '/') + ".class");
                byte[] in = e == null ? new byte[0] : ler(vz.getInputStream(e));
                if (e == null) {
                    novas++;
                }
                byte[] out = m.applyPatch(nome, nome, in);
                File f = new File(saida, nome.replace('.', '/') + ".class");
                f.getParentFile().mkdirs();
                FileOutputStream fo = new FileOutputStream(f);
                try {
                    fo.write(out);
                } finally {
                    fo.close();
                }
                n++;
            }
        } finally {
            vz.close();
        }
        System.out.println("[binpatch] " + n + " classes com patch aplicado (" + novas + " criadas pelo patch)");
    }

    private static byte[] ler(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] x = new byte[8192];
            int r;
            while ((r = is.read(x)) > 0) {
                b.write(x, 0, r);
            }
            return b.toByteArray();
        } finally {
            is.close();
        }
    }
}
