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

import hoshiestar.embutidos.CampoPrivado;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/** Formato 4: leitura/escrita de campo privado por MethodHandle. */
public final class TesteCampoPrivado {

    static final class ComCampos {
        private int numero = 7;
        private final List<String> lista = new ArrayList<String>();
        private static String estatico = "e";
    }

    private static final MethodHandle NUMERO;
    private static final MethodHandle LISTA;
    private static final MethodHandle ESCREVE_NUMERO;

    static {
        try {
            NUMERO = CampoPrivado.leitorExato(ComCampos.class, "numero", int.class);
            LISTA = CampoPrivado.leitorExato(ComCampos.class, "lista", List.class);
            ESCREVE_NUMERO = CampoPrivado.escritorExato(ComCampos.class, "numero", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void main(String[] a) throws Throwable {
        Verifica v = new Verifica("TesteCampoPrivado");
        ComCampos c = new ComCampos();
        v.igual(7, (int) NUMERO.invokeExact(c), "le int privado (invokeExact)");
        List<?> l = (List<?>) LISTA.invokeExact(c);
        v.sim(l == c.lista, "le List privada final: o mesmo objeto (lista viva)");
        ESCREVE_NUMERO.invokeExact(c, 42);
        v.igual(42, c.numero, "escreve int privado");
        MethodHandle gen = CampoPrivado.leitor(ComCampos.class, "numero");
        v.igual(42, gen.invoke((Object) c), "leitor generico (Object)Object");
        MethodHandle est = CampoPrivado.leitor(ComCampos.class, "estatico");
        v.igual("e", est.invoke(), "leitor de campo estatico");
        try {
            CampoPrivado.escritorExato(ComCampos.class, "lista", List.class);
            v.sim(false, "escrita em campo final recusada");
        } catch (IllegalAccessException e) {
            v.sim(true, "escrita em campo final recusada");
        }
        try {
            CampoPrivado.leitorExato(ComCampos.class, "numero", long.class);
            v.sim(false, "tipo errado recusado");
        } catch (NoSuchFieldException e) {
            v.sim(true, "tipo errado recusado (" + e.getMessage() + ")");
        }
        try {
            CampoPrivado.leitorExato(ComCampos.class, "naoExiste", int.class);
            v.sim(false, "campo inexistente recusado");
        } catch (NoSuchFieldException e) {
            v.sim(true, "campo inexistente recusado");
        }
        v.fim();
    }
}
