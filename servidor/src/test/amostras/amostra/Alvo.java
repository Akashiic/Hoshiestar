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
package amostra;

import java.util.ArrayList;
import java.util.List;

/**
 * Classe de exemplo dos testes isolados. O test.sh compila esta fonte tres vezes, para Java 6, 7 e
 * 8 (-source/-target 1.6, 1.7, 1.8), e os testes transformam cada versao com os utilitarios ASM.
 * So usa recursos de linguagem do Java 6.
 */
public class Alvo {

    public int efeitos;
    private int segredo = 7;
    private final List<String> lista = new ArrayList<String>();
    private String nome = "alvo";

    /** void de instancia, com laco e desvios (tem quadros de pilha). */
    public void fazer(int a, String s) {
        for (int i = 0; i < a; i++) {
            if (s != null && i % 2 == 0) {
                efeitos++;
            }
        }
        efeitos += 100;
    }

    /** boolean de instancia, com long e double nos parametros. */
    public boolean pode(long x, double y) {
        if (x > 10) {
            return true;
        }
        return y > 1.0;
    }

    /** double de instancia. */
    public double energia() {
        double e = 0;
        for (int i = 0; i < 3; i++) {
            e += i;
        }
        return e + efeitos;
    }

    /** estatico que devolve objeto, com varios tipos de parametro. */
    public static Object estatico(String s, double d, boolean b, char c, byte[] arr) {
        if (b) {
            return s + c + arr.length;
        }
        return Double.valueOf(d);
    }

    /** comeca com um laco: o javac poe um quadro de pilha logo no inicio do metodo. */
    public int laco(int n) {
        while (n > 100) {
            n -= 7;
        }
        return n;
    }

    /** sem nenhum desvio. */
    public long semDesvio(long a) {
        return a * 2;
    }

    /** duas chamadas de interface iguais (size). */
    public int usaLista(List<String> l) {
        int a = l.size();
        l.add("x");
        int b = l.size();
        return a * 1000 + b;
    }

    /** chamada estatica. */
    public int usaEstatico(int x) {
        return Util.dobro(x) + 1;
    }

    /** chamada a metodo privado (invokespecial no javac 8). */
    public int usaPrivado(int x) {
        return privado(x) * 10;
    }

    private int privado(int x) {
        return x + segredo;
    }

    /** chamada estatica void. */
    public void usaVoid() {
        Util.marcar(this);
        efeitos += 1;
    }

    /** chamada a super (invokespecial que os utilitarios recusam). */
    public String usaSuper() {
        return super.toString() + nome + lista.size();
    }

    /** classe auxiliar. */
    public static class Util {
        public static int marcas;

        public static int dobro(int x) {
            return 2 * x;
        }

        public static void marcar(Alvo a) {
            marcas++;
        }
    }
}
