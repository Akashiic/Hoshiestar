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

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Class loader dos testes com classes de mods: faz o papel do LaunchClassLoader. "Filho primeiro"
 * para o Minecraft (nomes SRG), os mods, as bibliotecas do Minecraft, os ganchos que usam classes de
 * mod ({@code hoshiestar.mekanism.matriz.}, {@code hoshiestar.betterquesting.filtro.},
 * {@code hoshiestar.arsmagica.atalho.}, {@code hoshiestar.chromaticraft.shift.}) e o codigo de teste que usa Minecraft
 * ({@code hoshiestar.teste.mekanism.mundo.}, {@code hoshiestar.teste.betterquesting.mundo.},
 * {@code hoshiestar.teste.arsmagica.mundo.}, {@code hoshiestar.teste.chromaticraft.mundo.});
 * o resto do Hoshiestar (api, embutidos, config, o registro
 * do recurso, o comando hoshie e a API do Bukkit que ele usa), o JDK, o ASM e o log4j vem do class
 * loader pai, como no servidor (um so exemplar dos
 * contadores e do modo, visto pelos dois lados). Cada classe pedida passa por {@link Transformador}
 * antes de ser definida: o teste liga ali o registro de correcoes REAL.
 */
public final class CarregadorMods extends URLClassLoader {

    /** Recebe o nome (com pontos) e os bytes do arquivo; devolve os bytes a definir. */
    public interface Transformador {
        byte[] transformar(String nome, byte[] bytes);
    }

    private final Transformador transformador;

    public CarregadorMods(URL[] fontes, ClassLoader pai, Transformador t) {
        super(fontes, pai);
        this.transformador = t;
    }

    static boolean doPai(String n) {
        if (n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("sun.") || n.startsWith("jdk.")
                || n.startsWith("org.objectweb.asm.") || n.startsWith("org.apache.logging.") || n.startsWith("org.yaml.")
                || n.startsWith("io.github.crucible.util.config.") || n.startsWith("org.bukkit.")) {
            return true;
        }
        return n.startsWith("hoshiestar.") && !n.startsWith("hoshiestar.mekanism.matriz.")
                && !n.startsWith("hoshiestar.teste.mekanism.mundo.")
                && !n.startsWith("hoshiestar.betterquesting.filtro.")
                && !n.startsWith("hoshiestar.teste.betterquesting.mundo.")
                && !n.startsWith("hoshiestar.arsmagica.atalho.")
                && !n.startsWith("hoshiestar.teste.arsmagica.mundo.")
                && !n.startsWith("hoshiestar.chromaticraft.shift.")
                && !n.startsWith("hoshiestar.teste.chromaticraft.mundo.");
    }

    @Override
    protected Class<?> loadClass(String n, boolean resolver) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(n)) {
            Class<?> c = findLoadedClass(n);
            if (c == null) {
                if (doPai(n)) {
                    c = getParent().loadClass(n);
                } else {
                    try {
                        c = findClass(n);
                    } catch (ClassNotFoundException e) {
                        c = getParent().loadClass(n);
                    }
                }
            }
            if (resolver) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String n) throws ClassNotFoundException {
        URL u = findResource(n.replace('.', '/') + ".class");
        if (u == null) {
            throw new ClassNotFoundException(n);
        }
        byte[] b;
        try {
            InputStream is = u.openStream();
            b = RemapeadorSrg.ler(is);
        } catch (Exception e) {
            throw new ClassNotFoundException(n, e);
        }
        if (transformador != null) {
            b = transformador.transformar(n, b);
        }
        return defineClass(n, b, 0, b.length);
    }
}
