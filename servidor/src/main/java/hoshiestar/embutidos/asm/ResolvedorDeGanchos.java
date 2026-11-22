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
package hoshiestar.embutidos.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Confere se um gancho existe com o descritor certo SEM carregar a classe do gancho: le o arquivo
 * .class dele como recurso e lista os metodos "public static". Carregar a classe do gancho no
 * meio da transformacao de uma classe de mod poderia puxar a propria classe do mod (circularidade),
 * por isso so os bytes sao lidos.
 *
 * <p>Seguro entre threads (cache em ConcurrentHashMap).
 */
public final class ResolvedorDeGanchos {

    private static final Set<String> NENHUM = Collections.emptySet();

    private final ClassLoader carregador;
    private final ConcurrentHashMap<String, Set<String>> cache = new ConcurrentHashMap<String, Set<String>>();

    /** @param carregador onde procurar o .class dos ganchos (no servidor: o class loader dos mods). */
    public ResolvedorDeGanchos(ClassLoader carregador) {
        this.carregador = carregador;
    }

    /**
     * Devolve o primeiro dos descritores candidatos que o dono declara como metodo
     * "public static" com este nome (e a classe dona e publica), ou null se nenhum.
     */
    public String descritorDeclarado(String dono, String nome, String... candidatos) {
        Set<String> metodos = metodosPublicosEstaticos(dono);
        for (String c : candidatos) {
            if (c != null && metodos.contains(nome + c)) {
                return c;
            }
        }
        return null;
    }

    private Set<String> metodosPublicosEstaticos(String dono) {
        Set<String> s = cache.get(dono);
        if (s != null) {
            return s;
        }
        s = lerMetodos(dono);
        Set<String> antes = cache.putIfAbsent(dono, s);
        return antes != null ? antes : s;
    }

    private Set<String> lerMetodos(String dono) {
        byte[] b = ler(dono + ".class");
        if (b == null) {
            return NENHUM;
        }
        final Set<String> s = new HashSet<String>();
        final boolean[] publica = {false};
        try {
            new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override
                public void visit(int version, int access, String name, String signature, String superName,
                        String[] interfaces) {
                    publica[0] = (access & Opcodes.ACC_PUBLIC) != 0;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                        String[] exceptions) {
                    if ((access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_STATIC) != 0) {
                        s.add(name + desc);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable t) {
            return NENHUM;
        }
        return publica[0] ? s : NENHUM;
    }

    private byte[] ler(String caminho) {
        try {
            URL u = carregador == null ? ClassLoader.getSystemResource(caminho) : carregador.getResource(caminho);
            if (u == null) {
                return null;
            }
            InputStream is = u.openStream();
            try {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] x = new byte[8192];
                int r;
                while ((r = is.read(x)) > 0) {
                    o.write(x, 0, r);
                }
                return o.toByteArray();
            } finally {
                is.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
