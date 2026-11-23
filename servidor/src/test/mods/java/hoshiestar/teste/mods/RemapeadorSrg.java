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
package hoshiestar.teste.mods;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Faz, fora do servidor, o que o DeobfuscationTransformer do FML faz ao carregar cada classe: troca os
 * nomes "notch" do Minecraft (ex.: {@code ahb}, {@code ahb.e(II)Lapx;}) pelos nomes de tempo de
 * execucao (SRG: {@code net/minecraft/world/World.func_72964_e}). Usa o MESMO mapa do servidor (o
 * {@code deobfuscation_data-1.7.10.lzma} de dentro do server.jar construido) e, como o FML, procura
 * campos e metodos herdados subindo a hierarquia (superclasse e interfaces, lidas dos proprios jars).
 *
 * <p>Uso (main): {@code RemapeadorSrg <server.jar construido> <saida> <jar> [<jar>...]}: para cada
 * jar de entrada grava {@code <saida>/<nome>-srg.jar} so com as classes, traduzidas. O teste da
 * correcao do Mekanism usa isto para ter (a) o Minecraft vanilla com nomes SRG (os mods sao
 * compilados contra esses nomes) e (b) as classes do NOSSO jar, tiradas do jar construido (os mesmos
 * bytes que vao para o servidor), com os nomes que elas tem depois de carregadas no servidor.
 */
public final class RemapeadorSrg extends Remapper {

    /** Le os bytes de uma classe pelo nome interno notch (para subir a hierarquia), ou null. */
    public interface FonteDeClasses {
        byte[] bytes(String nomeInterno);
    }

    private final Map<String, String> classes = new HashMap<String, String>();
    private final Map<String, Map<String, String>> campos = new HashMap<String, Map<String, String>>();
    private final Map<String, Map<String, String>> metodos = new HashMap<String, Map<String, String>>();
    private final Map<String, Map<String, String>> camposMesclados = new HashMap<String, Map<String, String>>();
    private final Map<String, Map<String, String>> metodosMesclados = new HashMap<String, Map<String, String>>();
    private final FonteDeClasses fonte;

    public RemapeadorSrg(InputStream mapaSrg, FonteDeClasses fonte) throws Exception {
        this.fonte = fonte;
        BufferedReader r = new BufferedReader(new InputStreamReader(mapaSrg, "UTF-8"));
        String l;
        while ((l = r.readLine()) != null) {
            String[] p = l.trim().split(" ");
            if (p[0].equals("CL:") && p.length >= 3) {
                classes.put(p[1], p[2]);
            } else if (p[0].equals("FD:") && p.length >= 3) {
                int a = p[1].lastIndexOf('/');
                int b = p[2].lastIndexOf('/');
                mapa(campos, p[1].substring(0, a)).put(p[1].substring(a + 1), p[2].substring(b + 1));
            } else if (p[0].equals("MD:") && p.length >= 5) {
                int a = p[1].lastIndexOf('/');
                int b = p[3].lastIndexOf('/');
                mapa(metodos, p[1].substring(0, a)).put(p[1].substring(a + 1) + p[2], p[3].substring(b + 1));
            }
        }
    }

    /** O mapa do FML de dentro de um server.jar (LZMA, como o FML le). Precisa da lzma-0.0.1 no classpath. */
    public static InputStream mapaDoJar(ZipFile jar) throws Exception {
        ZipEntry e = jar.getEntry("deobfuscation_data-1.7.10.lzma");
        if (e == null) {
            throw new IllegalStateException("deobfuscation_data-1.7.10.lzma nao esta em " + jar.getName());
        }
        Class<?> c = Class.forName("LZMA.LzmaInputStream");
        Constructor<?> k = c.getConstructor(InputStream.class);
        return (InputStream) k.newInstance(jar.getInputStream(e));
    }

    private static Map<String, String> mapa(Map<String, Map<String, String>> m, String dono) {
        Map<String, String> x = m.get(dono);
        if (x == null) {
            x = new HashMap<String, String>();
            m.put(dono, x);
        }
        return x;
    }

    public int numeroDeClasses() {
        return classes.size();
    }

    @Override
    public String map(String nomeInterno) {
        String s = classes.get(nomeInterno);
        if (s != null) {
            return s;
        }
        int d = nomeInterno.indexOf('$');
        if (d > 0) {
            String fora = classes.get(nomeInterno.substring(0, d));
            if (fora != null) {
                return fora + nomeInterno.substring(d);
            }
        }
        return nomeInterno;
    }

    @Override
    public String mapFieldName(String dono, String nome, String desc) {
        String s = mesclado(dono, true).get(nome);
        return s != null ? s : nome;
    }

    @Override
    public String mapMethodName(String dono, String nome, String desc) {
        if (nome.startsWith("<")) {
            return nome;
        }
        String s = mesclado(dono, false).get(nome + desc);
        return s != null ? s : nome;
    }

    /** Mapa do dono + tudo o que ele herda (como o findAndMergeSuperMaps do FML). */
    private Map<String, String> mesclado(String dono, boolean campo) {
        Map<String, Map<String, String>> cache = campo ? camposMesclados : metodosMesclados;
        Map<String, String> m = cache.get(dono);
        if (m != null) {
            return m;
        }
        cache.put(dono, Collections.<String, String>emptyMap()); // contra ciclos
        Map<String, String> r = new HashMap<String, String>();
        byte[] b = dono.startsWith("java/") ? null : fonte.bytes(dono);
        if (b != null) {
            ClassReader cr = new ClassReader(b);
            List<String> pais = new ArrayList<String>();
            if (cr.getSuperName() != null) {
                pais.add(cr.getSuperName());
            }
            Collections.addAll(pais, cr.getInterfaces());
            for (String p : pais) {
                r.putAll(mesclado(p, campo));
            }
        }
        Map<String, String> proprio = (campo ? campos : metodos).get(dono);
        if (proprio != null) {
            r.putAll(proprio);
        }
        cache.put(dono, r);
        return r;
    }

    /** Traduz uma classe inteira (nomes, descritores, quadros). */
    public byte[] traduzir(byte[] b) {
        ClassReader cr = new ClassReader(b);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new ClassRemapper(cw, this), 0);
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------ ferramenta

    public static void main(String[] a) throws Exception {
        if (a.length < 3) {
            System.err.println("uso: RemapeadorSrg <server.jar construido> <pasta de saida> <jar>...");
            System.exit(2);
        }
        final List<ZipFile> jars = new ArrayList<ZipFile>();
        for (int i = 2; i < a.length; i++) {
            jars.add(new ZipFile(a[i]));
        }
        FonteDeClasses fonte = new FonteDeClasses() {
            @Override
            public byte[] bytes(String n) {
                for (ZipFile z : jars) {
                    ZipEntry e = z.getEntry(n + ".class");
                    if (e != null) {
                        try {
                            return ler(z.getInputStream(e));
                        } catch (Exception x) {
                            return null;
                        }
                    }
                }
                return null;
            }
        };
        ZipFile servidor = new ZipFile(a[0]);
        RemapeadorSrg r = new RemapeadorSrg(mapaDoJar(servidor), fonte);
        File saida = new File(a[1]);
        saida.mkdirs();
        for (ZipFile z : jars) {
            String nome = new File(z.getName()).getName().replaceAll("\\.jar$", "") + "-srg.jar";
            File out = new File(saida, nome);
            int n = 0;
            ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(out));
            try {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements();) {
                    ZipEntry e = en.nextElement();
                    if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) {
                        continue;
                    }
                    byte[] b = ler(z.getInputStream(e));
                    String interno = e.getName().substring(0, e.getName().length() - 6);
                    zo.putNextEntry(new ZipEntry(r.map(interno) + ".class"));
                    zo.write(r.traduzir(b));
                    zo.closeEntry();
                    n++;
                }
            } finally {
                zo.close();
            }
            System.out.println("[remapeador] " + out.getName() + ": " + n + " classes traduzidas (mapa com "
                    + r.numeroDeClasses() + " classes)");
        }
    }

    public static byte[] ler(InputStream is) throws Exception {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] x = new byte[8192];
            int k;
            while ((k = is.read(x)) > 0) {
                o.write(x, 0, k);
            }
            return o.toByteArray();
        } finally {
            is.close();
        }
    }
}
