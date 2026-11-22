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
package hoshiestar.embutidos;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Formato 4: ler (e, se preciso, escrever) um campo PRIVADO de uma classe de mod a partir de um
 * gancho, sem alterar a classe do mod.
 *
 * <p><b>Escolha:</b> MethodHandle, e nao "publicar o campo" pelo transformador. Motivos:
 * <ul>
 * <li>o gancho e compilado contra o jar original do mod, onde o campo e privado: publicar o campo
 * em tempo de execucao nao deixaria o javac compilar um acesso direto, entao seria preciso
 * reflexao do mesmo jeito;</li>
 * <li>nao muda nada no bytecode do mod (menos superficie de erro, nada a conferir com trava);</li>
 * <li>se o campo nao existir ou mudar de tipo, o erro aparece UMA vez, quando o gancho prepara o
 * acesso (no carregamento da classe do gancho), e o recurso pode se desligar com aviso;</li>
 * <li>guardado num campo {@code static final}, o MethodHandle e tratado como constante pelo JIT
 * e o acesso fica tao barato quanto o direto (inlining do invokeExact) - nao medido aqui;</li>
 * <li>funciona no Java 8 e no 21: as classes de mod ficam no modulo sem nome, onde
 * {@code setAccessible} e permitido.</li>
 * </ul>
 *
 * <pre>
 * // no gancho (src/mods/java, compilado contra o jar do mod):
 * private static final MethodHandle ORES = CampoPrivado.leitorExato(OreIngredient.class, "ores", List.class);
 * ...
 * List l = (List) ORES.invokeExact(ingrediente);          // tipos exatos: (OreIngredient)List
 * </pre>
 *
 * Se preferir nao lidar com tipos exatos: {@link #leitor(Class, String)} devolve um handle com tipo
 * (Object)Object, para {@code invoke}, um pouco mais caro.
 */
public final class CampoPrivado {

    private CampoPrivado() {
    }

    /**
     * Handle de leitura com o tipo exato {@code (dono)tipo} (ou {@code ()tipo} se o campo e estatico).
     * Confere que o campo tem exatamente o tipo dado.
     */
    public static MethodHandle leitorExato(Class<?> dono, String campo, Class<?> tipo) throws ReflectiveOperationException {
        Field f = campo(dono, campo, tipo);
        return MethodHandles.lookup().unreflectGetter(f);
    }

    /** Handle de leitura com tipo generico (Object)Object, ou ()Object se o campo e estatico. */
    public static MethodHandle leitor(Class<?> dono, String campo) throws ReflectiveOperationException {
        Field f = campo(dono, campo, null);
        MethodHandle h = MethodHandles.lookup().unreflectGetter(f);
        return Modifier.isStatic(f.getModifiers())
                ? h.asType(MethodType.methodType(Object.class))
                : h.asType(MethodType.methodType(Object.class, Object.class));
    }

    /**
     * Handle de escrita com o tipo exato {@code (dono,tipo)void}. Recusa campo final (escrever num
     * final quebraria as garantias do JIT).
     */
    public static MethodHandle escritorExato(Class<?> dono, String campo, Class<?> tipo) throws ReflectiveOperationException {
        Field f = campo(dono, campo, tipo);
        if (Modifier.isFinal(f.getModifiers())) {
            throw new IllegalAccessException("campo final nao pode ser escrito: " + dono.getName() + "." + campo);
        }
        return MethodHandles.lookup().unreflectSetter(f);
    }

    private static Field campo(Class<?> dono, String campo, Class<?> tipo) throws ReflectiveOperationException {
        Field f = dono.getDeclaredField(campo);
        if (tipo != null && f.getType() != tipo) {
            throw new NoSuchFieldException("campo " + dono.getName() + "." + campo + " tem tipo " + f.getType().getName()
                    + ", esperado " + tipo.getName());
        }
        f.setAccessible(true);
        return f;
    }
}
