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
package hoshiestar.teste;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;

/** Mini-arcabouco dos testes isolados: conta falhas e termina com codigo 1 se houver alguma. */
public final class Verifica {

    private int ok;
    private int falhas;
    private final String nome;

    public Verifica(String nome) {
        this.nome = nome;
        System.out.println("== " + nome + " (Java " + System.getProperty("java.version") + ")");
    }

    public void sim(boolean cond, String o) {
        if (cond) {
            ok++;
            System.out.println("  ok    " + o);
        } else {
            falhas++;
            System.out.println("  FALHA " + o);
        }
    }

    public void igual(Object esperado, Object visto, String o) {
        boolean eq = esperado == null ? visto == null : esperado.equals(visto);
        sim(eq, o + (eq ? "" : " (esperado " + esperado + ", visto " + visto + ")"));
    }

    public void erro(String o, Throwable t) {
        falhas++;
        System.out.println("  FALHA " + o + ": " + t);
        t.printStackTrace(System.out);
    }

    public void fim() {
        System.out.println("== " + nome + ": " + ok + " ok, " + falhas + " falha(s)");
        System.exit(falhas == 0 ? 0 : 1);
    }

    public static byte[] ler(InputStream is) throws Exception {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = is.read(b)) > 0) {
                o.write(b, 0, r);
            }
            return o.toByteArray();
        } finally {
            is.close();
        }
    }

    /**
     * Class loader de teste: define as classes do mapa (as transformadas) e, "filho primeiro", as do
     * pacote dos ganchos de teste, como no servidor (ganchos e mods no mesmo carregador).
     */
    public static final class Carregador extends ClassLoader {
        private final Map<String, byte[]> classes;

        public Carregador(ClassLoader pai, Map<String, byte[]> classes) {
            super(pai);
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String n, boolean resolver) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(n)) {
                Class<?> c = findLoadedClass(n);
                if (c != null) {
                    return c;
                }
                byte[] b = classes.get(n);
                if (b == null && n.startsWith("hoshiestar.teste.ganchos.")) {
                    try {
                        b = ler(getParent().getResourceAsStream(n.replace('.', '/') + ".class"));
                    } catch (Exception e) {
                        throw new ClassNotFoundException(n, e);
                    }
                }
                if (b == null) {
                    return super.loadClass(n, resolver);
                }
                return defineClass(n, b, 0, b.length);
            }
        }
    }
}
