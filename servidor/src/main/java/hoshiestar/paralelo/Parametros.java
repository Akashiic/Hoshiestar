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
package hoshiestar.paralelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * As chaves {@code paralelo.*} do hoshiestar.yml ja conferidas (plano do Degrau B, secao 10). Sem Minecraft.
 *
 * <p>Valor fora da faixa NAO cai num padrao em silencio, como no orcamento: o paralelo e o recurso mais arriscado do
 * projeto, entao valor invalido vira motivo de recusa ({@link #problemas}) e o recurso fica desligado. (Valor com o tipo
 * errado, como "lixo" onde se espera numero, o leitor do config ja troca pelo padrao e avisa; isso acontece antes daqui.)
 * Os limites abaixo que o plano nao fixa (leitura_amostra, aviso_espera_s, sinal_ticks, aquecimento_ticks) sao decisao
 * deste passo, registrada em PROGRESSO.md.
 */
public final class Parametros {

    public static final String MODO_SOMBRA = "sombra";
    public static final String MODO_SOMBRA_ORDENADA = "sombra_ordenada";
    public static final String MODO_PARALELO = "paralelo";
    public static final List<String> MODOS = Collections.unmodifiableList(
            Arrays.asList(MODO_SOMBRA, MODO_SOMBRA_ORDENADA, MODO_PARALELO));

    /** Classes liberaveis nesta entrega (onda 1). "funil" (onda 2, condicional) entra com o passo 8 do plano. */
    public static final List<String> CLASSES_PERMITIDAS = Collections.unmodifiableList(
            Arrays.asList("mesa", "bau", "bau_ender"));

    public static final String PARAR = "parar";
    public static final String REBAIXAR = "rebaixar";

    public static final int THREADS_MIN = 1;
    public static final int THREADS_MAX = 6;
    public static final int LEITURA_AMOSTRA_MAX = 1000000;
    public static final int AVISO_ESPERA_MIN_S = 1;
    public static final int AVISO_ESPERA_MAX_S = 3600;
    /** 24 horas de ticks. */
    public static final int TICKS_MAX = 1728000;

    /** Os valores normalizados (texto em minuscula, sem espacos nas pontas). Texto invalido fica como veio, em minuscula. */
    public final String modo;
    public final int dimensao;
    public final int threads;
    public final List<String> classes;
    /** Nomes pedidos que nao estao em {@link #CLASSES_PERMITIDAS}, na ordem em que apareceram, sem repetir. */
    public final List<String> classesForaDaLista;
    public final String aoFalhar;
    public final String aoViolar;
    public final int leituraAmostra;
    public final int avisoEsperaS;
    public final int sinalTicks;
    public final int aquecimentoTicks;
    /** Um texto por valor fora da faixa (vazia = tudo na faixa). */
    public final List<String> problemas;

    private Parametros(String modo, int dimensao, int threads, List<String> classes, List<String> fora, String aoFalhar,
            String aoViolar, int leituraAmostra, int avisoEsperaS, int sinalTicks, int aquecimentoTicks, List<String> problemas) {
        this.modo = modo;
        this.dimensao = dimensao;
        this.threads = threads;
        this.classes = Collections.unmodifiableList(classes);
        this.classesForaDaLista = Collections.unmodifiableList(fora);
        this.aoFalhar = aoFalhar;
        this.aoViolar = aoViolar;
        this.leituraAmostra = leituraAmostra;
        this.avisoEsperaS = avisoEsperaS;
        this.sinalTicks = sinalTicks;
        this.aquecimentoTicks = aquecimentoTicks;
        this.problemas = Collections.unmodifiableList(problemas);
    }

    public static Parametros ler(String modo, int dimensao, int threads, String classes, String aoFalhar, String aoViolar,
            int leituraAmostra, int avisoEsperaS, int sinalTicks, int aquecimentoTicks) {
        List<String> problemas = new ArrayList<String>();
        String m = norm(modo);
        if (!MODOS.contains(m)) {
            problemas.add("paralelo.modo = '" + mostrar(modo) + "' invalido (use sombra, sombra_ordenada ou paralelo)");
        }
        if (threads < THREADS_MIN || threads > THREADS_MAX) {
            problemas.add("paralelo.threads = " + threads + " fora da faixa " + THREADS_MIN + " a " + THREADS_MAX);
        }
        List<String> pedidas = new ArrayList<String>();
        List<String> fora = new ArrayList<String>();
        if (classes != null) {
            for (String c : classes.split(",")) {
                String n = norm(c);
                if (n.isEmpty() || pedidas.contains(n)) {
                    continue;
                }
                pedidas.add(n);
                if (!CLASSES_PERMITIDAS.contains(n)) {
                    fora.add(n);
                }
            }
        }
        String f = norm(aoFalhar);
        if (!f.equals(PARAR) && !f.equals(REBAIXAR)) {
            problemas.add("paralelo.ao_falhar = '" + mostrar(aoFalhar) + "' invalido (use parar ou rebaixar)");
        }
        String v = norm(aoViolar);
        if (!v.equals(PARAR) && !v.equals(REBAIXAR)) {
            problemas.add("paralelo.ao_violar = '" + mostrar(aoViolar) + "' invalido (use parar ou rebaixar)");
        }
        if (leituraAmostra < 0 || leituraAmostra > LEITURA_AMOSTRA_MAX) {
            problemas.add("paralelo.leitura_amostra = " + leituraAmostra + " fora da faixa 0 a " + LEITURA_AMOSTRA_MAX);
        }
        if (avisoEsperaS < AVISO_ESPERA_MIN_S || avisoEsperaS > AVISO_ESPERA_MAX_S) {
            problemas.add("paralelo.aviso_espera_s = " + avisoEsperaS + " fora da faixa " + AVISO_ESPERA_MIN_S + " a " + AVISO_ESPERA_MAX_S);
        }
        if (sinalTicks < 0 || sinalTicks > TICKS_MAX) {
            problemas.add("paralelo.sinal_ticks = " + sinalTicks + " fora da faixa 0 a " + TICKS_MAX);
        }
        if (aquecimentoTicks < 0 || aquecimentoTicks > TICKS_MAX) {
            problemas.add("paralelo.aquecimento_ticks = " + aquecimentoTicks + " fora da faixa 0 a " + TICKS_MAX);
        }
        return new Parametros(m, dimensao, threads, pedidas, fora, f, v, leituraAmostra, avisoEsperaS, sinalTicks,
                aquecimentoTicks, problemas);
    }

    /** Os padroes da secao 10 do plano (todos validos). */
    public static Parametros padrao() {
        return ler(MODO_SOMBRA, 0, 3, "", PARAR, PARAR, 64, 5, 1200, 600);
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String mostrar(String s) {
        if (s == null) {
            return "(vazio)";
        }
        String t = s.trim();
        return t.length() > 40 ? t.substring(0, 40) + "..." : t;
    }
}
