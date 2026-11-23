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
package hoshiestar.teste.ganchos;

import amostra.Alvo;

import java.util.List;

/**
 * Ganchos usados pelos testes isolados. Nos testes esta classe e definida no MESMO class loader da
 * classe transformada (como no servidor, onde ganchos e mods ficam no LaunchClassLoader). O estado
 * e lido por reflexao.
 */
public final class GanchosTeste {

    public static int chamadas;
    public static Object receptor;
    public static Object[] args;
    public static boolean decisao;
    /** formato 3 protegido: quantas vezes o gancho aoFalhar foi chamado e com que Throwable. */
    public static int falhas;
    public static Throwable ultimaFalha;

    private GanchosTeste() {
    }

    public static void zerar() {
        chamadas = 0;
        receptor = null;
        args = null;
        decisao = false;
        falhas = 0;
        ultimaFalha = null;
    }

    private static boolean reg(Object r, Object... a) {
        chamadas++;
        receptor = r;
        args = a;
        return decisao;
    }

    // ---- formato 1
    public static void inicioFazer(Object alvo, int a, String s) {
        reg(alvo, a, s);
    }

    public static boolean pularFazer(Alvo alvo, int a, String s) {
        return reg(alvo, a, s);
    }

    public static boolean pularPode(Object alvo, long x, double y) {
        return reg(alvo, x, y);
    }

    public static boolean valorPode(Object alvo, long x, double y) {
        return false;
    }

    public static boolean pularEnergia(Object alvo) {
        return reg(alvo);
    }

    public static double valorEnergia(Object alvo) {
        return 42.5;
    }

    public static boolean pularEstatico(String s, double d, boolean b, char c, byte[] arr) {
        return reg(null, s, d, b, c, arr);
    }

    public static Object valorEstatico(String s, double d, boolean b, char c, byte[] arr) {
        return "gancho";
    }

    public static boolean pularLaco(Object alvo, int n) {
        return reg(alvo, n);
    }

    public static int valorLaco(Object alvo, int n) {
        return -1;
    }

    public static boolean pularSemDesvio(Object alvo, long a) {
        return reg(alvo, a);
    }

    public static long valorSemDesvio(Object alvo, long a) {
        return -2L;
    }

    /** descritor errado de proposito (valor de energia deveria devolver double). */
    public static int valorEnergiaErrado(Object alvo) {
        return 1;
    }

    // ---- formato 1 protegido
    /** gancho "inicio" que lanca (bug do gancho). */
    public static void inicioFazerQuebra(Object alvo, int a, String s) {
        chamadas++;
        throw new IllegalStateException("inicioFazerQuebra");
    }

    public static boolean pularLacoQuebra(Object alvo, int n) {
        chamadas++;
        throw new IllegalStateException("pularLacoQuebra");
    }

    /** "pular" que manda sair (true) para o "valor" que lanca ser alcancado. */
    public static boolean pularLacoSai(Object alvo, int n) {
        chamadas++;
        return true;
    }

    public static int valorLacoQuebra(Object alvo, int n) {
        throw new IllegalStateException("valorLacoQuebra");
    }

    public static boolean pularEstaticoQuebra(String s, double d, boolean b, char c, byte[] arr) {
        chamadas++;
        throw new IllegalStateException("pularEstaticoQuebra");
    }

    public static boolean pularSemDesvioQuebra(Object alvo, long a) {
        chamadas++;
        throw new IllegalStateException("pularSemDesvioQuebra");
    }

    // ---- formato 2
    public static int trocaSize(List<?> l) {
        reg(l);
        return 5;
    }

    public static int trocaDobro(int x) {
        reg(null, x);
        return 1000 + x;
    }

    public static int trocaPrivado(Object alvo, int x) {
        reg(alvo, x);
        return -x;
    }

    // ---- formato 3
    public static boolean deveDobro(int x) {
        return reg(null, x);
    }

    public static int semDobro(int x) {
        return 77;
    }

    public static boolean deveSize(List<?> l) {
        return reg(l);
    }

    public static int semSize(List<?> l) {
        return 9;
    }

    public static boolean devePrivado(Alvo a, int x) {
        return reg(a, x);
    }

    public static int semPrivado(Alvo a, int x) {
        return 3;
    }

    /** chamada estatica Util.marcar(Alvo): sem receptor, o argumento tem o tipo exato. */
    public static boolean deveMarcar(Alvo a) {
        return reg(a);
    }

    // ---- formato 3 protegido
    /** o gancho aoFalhar: so anota. */
    public static void falhou(Throwable t) {
        falhas++;
        ultimaFalha = t;
    }

    /** gancho "deve" que lanca (bug do gancho). */
    public static boolean deveQuebra(int x) {
        chamadas++;
        throw new IllegalStateException("deveQuebra");
    }

    /** gancho "deve" que manda pular (false), para o "sem" que lanca ser alcancado. */
    public static boolean devePula(int x) {
        chamadas++;
        return false;
    }

    /** gancho "sem" que lanca. */
    public static int semQuebra(int x) {
        throw new IllegalStateException("semQuebra");
    }

    public static boolean deveQuebraVoid(Alvo a) {
        throw new IllegalStateException("deveQuebraVoid");
    }

    public static boolean devePulaVoid(Alvo a) {
        return false;
    }

    /** aoFalhar com descritor errado de proposito (devolve int). */
    public static int falhouErrado(Throwable t) {
        return 1;
    }
}
