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
import java.util.List;
import java.util.Locale;

/**
 * O texto do comando {@code hoshie paralelo} (sem Minecraft; o adaptador {@code ComandoParaleloJogo} entrega as linhas a
 * quem digitou). Esqueleto do passo 1: nao ha fase, entao so informa isso e os motivos de recusa do boot.
 */
public final class ComandoParalelo {

    public static final String NAO_IMPLEMENTADA = "fase ainda nao implementada";

    private ComandoParalelo() {
    }

    public static String ajuda() {
        return "paralelo [estado|motivos [n]|modo [m]] - fase paralela do tick (" + NAO_IMPLEMENTADA + "; so mostra o estado e os motivos de recusa)";
    }

    public static String[] executar(String[] args) {
        List<String> o = new ArrayList<String>();
        try {
            String sub = args.length == 0 ? "estado" : args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("estado")) {
                estado(o);
            } else if (sub.equals("motivos")) {
                motivos(o, args);
            } else if (sub.equals("modo")) {
                modo(o, args);
            } else {
                o.add("uso: hoshie " + ajuda());
            }
        } catch (RuntimeException e) {
            o.add("erro no comando: " + e);
        }
        return o.toArray(new String[0]);
    }

    private static void estado(List<String> o) {
        Parametros p = Paralelo.parametros();
        o.add("paralelo: " + Paralelo.linhaDeEstado());
        if (!Paralelo.LIGADO) {
            o.add("(paralelo.ligado: false no hoshiestar.yml; ligue e reinicie. Nenhum gancho roda.)");
            return;
        }
        o.add("modo configurado: " + p.modo + "; modo efetivo: nenhum (" + NAO_IMPLEMENTADA + ")");
        o.add("dimensao: " + p.dimensao + "; threads: " + p.threads + "; classes: " + (p.classes.isEmpty() ? "(nenhuma: fase vazia)" : p.classes));
        o.add("ao_falhar: " + p.aoFalhar + "; ao_violar: " + p.aoViolar + "; leitura_amostra: " + p.leituraAmostra
                + "; aviso_espera_s: " + p.avisoEsperaS + "; sinal_ticks: " + p.sinalTicks + "; aquecimento_ticks: " + p.aquecimentoTicks);
        o.add("regioes elegiveis, tiles na fase, tempo da fase, espera por thread e contadores: " + NAO_IMPLEMENTADA + " (passos 3 a 6 do plano)");
    }

    private static void motivos(List<String> o, String[] args) {
        int max = Integer.MAX_VALUE;
        if (args.length > 1) {
            try {
                max = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                max = -1;
            }
            if (max < 1) {
                o.add("uso: hoshie paralelo motivos [n]   (n = quantos motivos mostrar, 1 ou mais)");
                return;
            }
        }
        if (!Paralelo.LIGADO) {
            o.add("paralelo desligado (paralelo.ligado: false): nao ha o que recusar.");
            return;
        }
        List<String> m = Paralelo.motivos();
        if (m.isEmpty()) {
            o.add("nenhuma recusa no boot.");
        } else {
            o.add(m.size() + " motivo(s) de recusa no boot:");
            for (int i = 0; i < m.size() && i < max; i++) {
                o.add("  " + (i + 1) + ". " + m.get(i));
            }
            if (m.size() > max) {
                o.add("  ... e mais " + (m.size() - max));
            }
        }
        o.add("motivos de inelegibilidade de regiao (com uma posicao de exemplo): " + NAO_IMPLEMENTADA + " (passo 3 do plano)");
    }

    private static void modo(List<String> o, String[] args) {
        Parametros p = Paralelo.parametros();
        if (args.length == 1) {
            o.add("modo configurado: " + p.modo + "; modo efetivo: nenhum (" + NAO_IMPLEMENTADA + ")");
            return;
        }
        String novo = args[1].toLowerCase(Locale.ROOT);
        if (!Parametros.MODOS.contains(novo)) {
            o.add("modo '" + args[1] + "' invalido (use sombra, sombra_ordenada ou paralelo)");
            return;
        }
        o.add("mudar o modo em execucao: " + NAO_IMPLEMENTADA + " (nada foi mudado; o modo configurado so vale no proximo boot).");
    }
}
