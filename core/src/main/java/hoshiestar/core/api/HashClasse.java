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

import com.google.common.hash.Hashing;
import cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import cpw.mods.fml.common.patcher.ClassPatch;
import cpw.mods.fml.common.patcher.ClassPatchManager;
import cpw.mods.fml.repackage.com.nothome.delta.GDiffPatcher;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * SHA-256 dos bytes de distribuicao de uma classe, SEM carregar a classe e sem passar por nenhum
 * transformador (ver {@link Trava} para o que entra no hash). Funciona na fase de coremod, no
 * CONSTRUCTING e com o servidor rodando.
 *
 * <p>Como le os bytes: pede o recurso "nome/da/Classe.class" ao LaunchClassLoader
 * ({@code findResource}, e depois {@code getResource} do pai) e le o arquivo. NAO usa
 * {@code LaunchClassLoader.getClassBytes}: no RetroFuturaBootstrap ele guarda "nao existe" num cache
 * negativo, e perguntar por uma classe de mod cedo demais faria o jogo nunca mais achar a classe.
 *
 * <p>Nomes: recebe o nome do codigo-fonte (com pontos). Classes do Minecraft sao procuradas pelo
 * nome do jar vanilla (o FMLDeobfuscatingRemapper do FML diz qual e: net.minecraft.world.World ->
 * "ahb"); classes de mod e do server.jar nao mudam de nome. Se a classe tem binpatch do Forge/
 * Crucible, o patch e aplicado aqui mesmo, com as mesmas regras do ClassPatchManager (soma Adler-32
 * da entrada e GDiff), mas sem mexer no cache dele.
 *
 * <p>Erros nunca escapam: viram {@link Resultado#erro}.
 */
public final class HashClasse {

    private static final String[] RESERVADOS = {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5",
        "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};

    private HashClasse() {
    }

    /** Resultado do calculo. */
    public static final class Resultado {
        /** Nome pedido (com pontos). */
        public final String classe;
        /** Nome no jar, com barras (igual ao pedido para classe que nao e do Minecraft). */
        public final String nomeNoJar;
        /** De onde os bytes vieram (URL do recurso), ou null. */
        public final String origem;
        /** SHA-256 dos bytes do jar, sem patch, ou null se a classe nao esta em jar nenhum. */
        public final String sha256Cru;
        /** SHA-256 depois do binpatch, ou null se a classe nao tem binpatch. */
        public final String sha256ComPatch;
        /** Bytes de distribuicao (com o patch, se houver), ou null. */
        final byte[] bytes;
        /** Explicacao se nao deu para calcular, ou null. */
        public final String erro;

        Resultado(String classe, String nomeNoJar, String origem, String sha256Cru, String sha256ComPatch, byte[] bytes,
                String erro) {
            this.classe = classe;
            this.nomeNoJar = nomeNoJar;
            this.origem = origem;
            this.sha256Cru = sha256Cru;
            this.sha256ComPatch = sha256ComPatch;
            this.bytes = bytes;
            this.erro = erro;
        }

        /** O hash que as travas comparam: com patch se houver, senao o cru; null se deu erro. */
        public String sha256() {
            if (erro != null) {
                return null;
            }
            return sha256ComPatch != null ? sha256ComPatch : sha256Cru;
        }

        public boolean temPatch() {
            return sha256ComPatch != null;
        }

        /** Uma linha legivel para log e comando. */
        public String descrever() {
            if (erro != null) {
                return classe + ": " + erro;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(classe).append(": sha256 ").append(sha256());
            if (temPatch()) {
                sb.append(" (com binpatch; sem patch: ").append(sha256Cru == null ? "classe criada pelo patch" : sha256Cru)
                        .append(")");
            }
            if (!nomeNoJar.replace('/', '.').equals(classe)) {
                sb.append(", nome no jar ").append(nomeNoJar);
            }
            if (origem != null) {
                sb.append(", de ").append(origem);
            }
            return sb.toString();
        }
    }

    /** Calcula o hash da classe (nome com pontos, como no codigo-fonte). */
    public static Resultado calcular(String classe) {
        String interno = classe == null ? "" : classe.trim().replace('.', '/');
        String nomePedido = interno.replace('/', '.');
        if (interno.isEmpty()) {
            return new Resultado(nomePedido, interno, null, null, null, null, "nome de classe vazio");
        }
        String noJar = interno;
        try {
            String u = FMLDeobfuscatingRemapper.INSTANCE.unmap(interno);
            if (u != null && !u.isEmpty()) {
                noJar = u;
            }
        } catch (Throwable t) {
            // sem o remapeador (fora do servidor): usa o nome como veio
        }
        try {
            URL url = achar(noJar + ".class");
            if (url == null && noJar.indexOf('/') < 0 && noJar.length() >= 3 && noJar.length() <= 4) {
                for (String r : RESERVADOS) {
                    if (r.equalsIgnoreCase(noJar)) {
                        url = achar("_" + noJar + ".class");
                        break;
                    }
                }
            }
            byte[] cru = url == null ? null : ler(url);
            byte[] patchado;
            try {
                patchado = aplicarBinpatch(noJar.replace('/', '.'), cru);
            } catch (Exception e) {
                return new Resultado(nomePedido, noJar, url == null ? null : url.toString(), cru == null ? null : sha256(cru),
                        null, null, "binpatch nao confere: " + e.getMessage());
            }
            if (cru == null && patchado == null) {
                return new Resultado(nomePedido, noJar, null, null, null, null,
                        "classe nao encontrada no classpath (mod ausente, nome errado, ou o jar ainda nao foi adicionado)");
            }
            String hCru = cru == null ? null : sha256(cru);
            String hPatch = patchado == null ? null : sha256(patchado);
            return new Resultado(nomePedido, noJar, url == null ? null : url.toString(), hCru, hPatch,
                    patchado != null ? patchado : cru, null);
        } catch (Throwable t) {
            return new Resultado(nomePedido, noJar, null, null, null, null, "erro ao ler a classe: " + t);
        }
    }

    /**
     * Os bytes de distribuicao da classe (os mesmos do hash), para uma conferencia extra com um
     * leitor de bytecode em {@link Recurso#verificar(Fase)}. null se nao achou. Nao carrega a classe.
     */
    public static byte[] bytesOriginais(String classe) {
        Resultado r = calcular(classe);
        return r.bytes == null ? null : r.bytes.clone();
    }

    /** SHA-256 em hexadecimal minusculo. */
    public static String sha256(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }

    private static URL achar(String caminho) {
        LaunchClassLoader lcl = Launch.classLoader;
        if (lcl != null) {
            URL u = lcl.findResource(caminho);
            if (u == null) {
                u = lcl.getResource(caminho);
            }
            return u;
        }
        ClassLoader cl = HashClasse.class.getClassLoader();
        return cl == null ? ClassLoader.getSystemResource(caminho) : cl.getResource(caminho);
    }

    private static byte[] ler(URL url) throws IOException {
        InputStream is = url.openStream();
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

    /**
     * Aplica os binpatches da classe (nome do jar, com pontos) sobre os bytes crus, como o
     * ClassPatchManager faz, sem usar o cache dele. Devolve null se a classe nao tem patch.
     */
    private static byte[] aplicarBinpatch(String nomeNoJar, byte[] cru) throws Exception {
        List<ClassPatch> lista = patches(nomeNoJar);
        if (lista == null || lista.isEmpty()) {
            return null;
        }
        byte[] entrada = cru;
        GDiffPatcher patcher = new GDiffPatcher();
        for (ClassPatch p : lista) {
            if (!p.existsAtTarget) {
                if (entrada != null && entrada.length > 0) {
                    throw new IllegalStateException("o patch de " + nomeNoJar + " cria a classe, mas ela ja existe no jar");
                }
                entrada = new byte[0];
            } else {
                if (entrada == null) {
                    throw new IllegalStateException("a classe " + nomeNoJar + " tem patch mas nao esta no jar vanilla");
                }
                int soma = Hashing.adler32().hashBytes(entrada).asInt();
                if (soma != p.inputChecksum) {
                    throw new IllegalStateException("soma Adler-32 de " + nomeNoJar + " e " + Integer.toHexString(soma)
                            + ", o patch espera " + Integer.toHexString(p.inputChecksum));
                }
            }
            entrada = patcher.patch(entrada, p.patch);
        }
        return entrada;
    }

    private static volatile Field campoPatches;

    @SuppressWarnings("unchecked")
    private static List<ClassPatch> patches(String nomeNoJar) throws Exception {
        Field f = campoPatches;
        if (f == null) {
            f = ClassPatchManager.class.getDeclaredField("patches");
            f.setAccessible(true);
            campoPatches = f;
        }
        Object m = f.get(ClassPatchManager.INSTANCE);
        if (m == null) {
            return null;
        }
        // ListMultimap<String, ClassPatch>; escrito so no setup do FML, antes de tudo
        Map<String, java.util.Collection<ClassPatch>> mapa = ((com.google.common.collect.Multimap<String, ClassPatch>) m).asMap();
        java.util.Collection<ClassPatch> c = mapa.get(nomeNoJar);
        return c == null ? null : new java.util.ArrayList<ClassPatch>(c);
    }
}
