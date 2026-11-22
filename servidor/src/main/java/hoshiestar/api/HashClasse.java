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
package hoshiestar.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.MessageDigest;

/**
 * SHA-256 dos bytes ORIGINAIS de uma classe, sem carregar a classe e sem passar por nenhum
 * transformador: le o arquivo "nome/da/Classe.class" de dentro do jar (de um mod, ou do proprio
 * server.jar). E o valor que as travas de versao comparam (ver {@link Trava}).
 *
 * <p>Como acha o arquivo: pergunta ao class loader dos mods (o LaunchClassLoader) pelo RECURSO,
 * com {@code findResource} e depois {@code getResource}. Nao usa
 * {@code LaunchClassLoader.getClassBytes}: no RetroFuturaBootstrap ele guarda "nao existe" num
 * cache negativo, e perguntar cedo demais por uma classe de mod faria o jogo nunca mais acha-la.
 *
 * <p>Classes do Minecraft ficam no jar vanilla com nome ofuscado e recebem binpatch na carga; para
 * elas este calculo responde "nao encontrada". Isso e esperado: classe do servidor e corrigida por
 * patch no fork, nunca por correcao embutida com trava.
 *
 * <p>Erros nunca escapam: viram {@link Resultado#erro}. So usa o JDK (o LaunchClassLoader e achado
 * por reflexao), entao funciona em qualquer fase e nos testes isolados.
 */
public final class HashClasse {

    private static final String[] RESERVADOS = {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5",
        "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};

    private HashClasse() {
    }

    /** Resultado do calculo. */
    public static final class Resultado {
        /** Nome pedido, com pontos. */
        public final String classe;
        /** De onde os bytes vieram (URL do recurso), ou null. */
        public final String origem;
        /** SHA-256 em hexadecimal minusculo, ou null se deu erro. */
        public final String sha256;
        /** Explicacao quando nao deu para calcular, ou null. */
        public final String erro;

        Resultado(String classe, String origem, String sha256, String erro) {
            this.classe = classe;
            this.origem = origem;
            this.sha256 = sha256;
            this.erro = erro;
        }

        /** Uma linha legivel para log e console (com o caminho completo da origem). */
        public String descrever() {
            return descrever(true);
        }

        /**
         * Uma linha legivel. Com {@code caminhoCompleto} false a origem aparece so pelo nome do jar
         * (ex.: "OpenBlocks-1.12.20-GTNH.jar"), sem o caminho do servidor: para mostrar a jogador.
         */
        public String descrever(boolean caminhoCompleto) {
            if (erro != null) {
                return classe + ": " + erro;
            }
            String o = caminhoCompleto ? origem : origemCurta();
            return classe + ": sha256 " + sha256 + (o != null ? " (de " + o + ")" : "");
        }

        /** So o nome do jar (ou do arquivo .class, fora de jar) de onde os bytes vieram, ou null. */
        public String origemCurta() {
            if (origem == null) {
                return null;
            }
            String o = origem;
            int excl = o.indexOf("!/");
            if (excl >= 0) {
                o = o.substring(0, excl);
            }
            int barra = Math.max(o.lastIndexOf('/'), o.lastIndexOf('\\'));
            return barra >= 0 ? o.substring(barra + 1) : o;
        }
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

    /** Calcula o hash do arquivo da classe (nome com pontos), procurando no class loader dos mods. */
    public static Resultado doArquivo(String classe) {
        return doArquivo(classe, carregadorDosMods());
    }

    /** Calcula o hash do arquivo da classe (nome com pontos), procurando no class loader dado. */
    public static Resultado doArquivo(String classe, ClassLoader cl) {
        String interno = classe == null ? "" : classe.trim().replace('.', '/');
        String nome = interno.replace('/', '.');
        if (interno.isEmpty()) {
            return new Resultado(nome, null, null, "nome de classe vazio");
        }
        try {
            URL url = achar(cl, interno + ".class");
            if (url == null && interno.indexOf('/') < 0) {
                for (String r : RESERVADOS) {
                    if (r.equalsIgnoreCase(interno)) {
                        url = achar(cl, "_" + interno + ".class");
                        break;
                    }
                }
            }
            if (url == null) {
                return new Resultado(nome, null, null,
                        "classe nao encontrada (mod ausente ou nome errado; classes do Minecraft tem nome ofuscado no jar)");
            }
            return new Resultado(nome, url.toString(), sha256(ler(url)), null);
        } catch (Throwable t) {
            return new Resultado(nome, null, null, "erro ao ler a classe: " + t);
        }
    }

    /** Os bytes do arquivo da classe, ou null se nao achou. Nao carrega a classe. */
    public static byte[] bytesDoArquivo(String classe, ClassLoader cl) {
        try {
            URL url = achar(cl, classe.replace('.', '/') + ".class");
            return url == null ? null : ler(url);
        } catch (Throwable t) {
            return null;
        }
    }

    /** O LaunchClassLoader ({@code Launch.classLoader}) se existir; senao o class loader desta classe. */
    public static ClassLoader carregadorDosMods() {
        try {
            Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch", false, HashClasse.class.getClassLoader());
            Object cl = launch.getField("classLoader").get(null);
            if (cl instanceof ClassLoader) {
                return (ClassLoader) cl;
            }
        } catch (Throwable t) {
            // fora do servidor (testes): usa o proprio class loader
        }
        return HashClasse.class.getClassLoader();
    }

    private static URL achar(ClassLoader cl, String caminho) throws Exception {
        if (cl == null) {
            return ClassLoader.getSystemResource(caminho);
        }
        URL u = null;
        try {
            // findResource e publico no URLClassLoader/LaunchClassLoader; procura so nas fontes dele
            Method m = cl.getClass().getMethod("findResource", String.class);
            u = (URL) m.invoke(cl, caminho);
        } catch (Exception e) {
            // class loader sem findResource publico, ou publico numa classe nao exportada (no Java 9+ o
            // class loader da aplicacao fica em jdk.internal.loader e a reflexao da IllegalAccessException)
        }
        if (u == null) {
            u = cl.getResource(caminho);
        }
        return u;
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
}
