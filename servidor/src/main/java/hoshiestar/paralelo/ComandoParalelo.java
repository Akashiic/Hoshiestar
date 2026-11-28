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

    public static final String NAO_IMPLEMENTADA = "fase paralela com threads ainda nao implementada (so os modos sombra e sombra_ordenada)";

    private ComandoParalelo() {
    }

    public static String ajuda() {
        return "paralelo [estado|motivos [n]|modo [m]" + (Congelar.TESTE ? "|congelar [tick]|injetar quebrar [x y z]" : "")
                + "] - fase paralela do tick (" + NAO_IMPLEMENTADA + "; estado, contadores das guardas e motivos)";
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
            } else if (sub.equals("congelar") && Congelar.TESTE) {
                congelar(o, args);
            } else if (sub.equals("injetar") && Congelar.TESTE) {
                injetar(o, args);
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
        o.add("modo configurado: " + p.modo + "; dimensao: " + p.dimensao + "; threads: " + p.threads + "; classes pedidas: " + (p.classes.isEmpty() ? "(nenhuma: fase vazia)" : p.classes)
                + "; efetivas: " + Paralelo.classesEfetivas() + (Paralelo.classesTiradas().isEmpty() ? "" : "; tiradas por trava: " + Paralelo.classesTiradas()));
        o.add("ao_falhar: " + p.aoFalhar + "; ao_violar: " + p.aoViolar + "; leitura_amostra: " + p.leituraAmostra
                + "; aviso_espera_s: " + p.avisoEsperaS + "; sinal_ticks: " + p.sinalTicks + "; aquecimento_ticks: " + p.aquecimentoTicks);
        if (!Paralelo.aceito()) {
            o.add("modo efetivo: nenhum (recusado: tudo como o original; veja 'hoshie paralelo motivos'); " + NAO_IMPLEMENTADA);
            return;
        }
        Painel.Foto f = Painel.ATUAL.foto();
        if (f == null) {
            o.add("modo efetivo: " + p.modo + "; o laco de tiles da dimensao " + p.dimensao + " ainda nao rodou (mundo nao carregado?)");
        } else {
            o.add("modo efetivo: " + f.modoEfetivo + " (tick " + f.tick + " do mundo da fase)");
            o.add("ultimo tick: regioes elegiveis " + f.elegiveis + " de " + f.regioesComTile + " com tile; tiles no contexto da fase "
                    + f.tilesNaFase + " de " + f.tilesValidos + " validos (sem regiao: " + f.tilesSemRegiao + "; invalidos ignorados: "
                    + f.invalidos + "; barrados pelo firewall: " + f.barradosFirewall + "); lotes sujos " + f.sujasTick);
            o.add(String.format(java.util.Locale.ROOT, "classificacao: %.3f ms por tick em media, pior %.3f ms, em %d ticks; tiles no contexto, somados: %d",
                    f.msClassMedia, f.msClassMax, f.ticksClassificados, f.tilesNaFaseTotal));
            o.add("invariantes quebrados: " + f.invariantesQuebrados + "; contextos descartados: " + f.contextosDescartados
                    + "; lotes sujos (somados): " + f.lotesSujos + "; erros no nosso codigo: " + f.errosNossos);
            if (Parametros.MODO_SOMBRA_ORDENADA.equals(p.modo)) {
                o.add("sombra_ordenada, ultimo tick: lotes " + f.lotesTick + " com " + f.tilesLotesTick + " tile(s); somados: " + f.lotesTotal
                        + " lotes, " + f.tilesLotesTotal + " tiles, em " + f.ticksComLotes + " ticks com lotes");
                o.add(String.format(java.util.Locale.ROOT, "  fase: %.3f ms por tick com lotes (lotes %.3f, juncao %.3f); adiados aplicados %d;"
                        + " lotes em serie na juncao %d (%d tiles); lotes sujos na fase %d; pulados na reconferencia %d; barrados pelo firewall"
                        + " no despacho %d", f.msFaseMedia, f.msLotesMedia, f.msJuncaoMedia, f.adiadosAplicadosTotal, f.lotesEmSerieTotal,
                        f.itensEmSerieTotal, f.lotesSujosFase, f.puladosTotal, f.barradosNoDespacho));
                o.add("  falhas na fase " + f.falhasTotal + "; invariantes da fase quebrados " + f.invariantesFase + "; emergencias (erro nosso) "
                        + f.emergencias + "; lotes descartados (fase esquecida) " + f.lotesDescartados
                        + (Congelar.TESTE ? "; injetar: " + Injecao.ultimo() : ""));
            }
        }
        o.add("guardas (seria ilegal na fase = violacao + portao): " + Guardas.seriaIlegal() + "; adiados: " + Guardas.total(Guardas.Res.ADIADO)
                + "; sujas: " + Guardas.total(Guardas.Res.SUJA));
        for (Guardas.Op op : Guardas.Op.values()) {
            StringBuilder b = new StringBuilder();
            for (Guardas.Res r : Guardas.Res.values()) {
                long c = Guardas.contagem(op, r);
                if (c > 0) {
                    b.append(b.length() == 0 ? "" : ", ").append(r.name().toLowerCase(Locale.ROOT)).append(' ').append(c);
                }
            }
            if (b.length() > 0) {
                o.add("  " + op.name().toLowerCase(Locale.ROOT) + ": " + b + "  (" + op.texto + ")");
            }
        }
        o.add("espera por thread, tempo da fase: " + NAO_IMPLEMENTADA + " (passo 6 do plano)");
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
        Painel.Foto f = Painel.ATUAL.foto();
        if (f == null || f.motivos.isEmpty()) {
            o.add("motivos de inelegibilidade de regiao: nenhum registrado ainda");
            return;
        }
        o.add("motivos de inelegibilidade de regiao, somados por tick (mais comuns primeiro; uma posicao de exemplo):");
        for (int i = 0; i < f.motivos.size() && i < max; i++) {
            Object[] mi = f.motivos.get(i);
            o.add("  " + mi[1] + "x  " + mi[0] + "  (ex.: " + mi[2] + ", " + mi[3] + ", " + mi[4] + ")");
        }
        if (f.motivos.size() > max) {
            o.add("  ... e mais " + (f.motivos.size() - max));
        }
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

    /** So com -Dhoshiestar.paralelo.teste=true. Passo 4: so 'quebrar' (os outros sao do passo 6). */
    private static void injetar(List<String> o, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("quebrar")) {
            if (args.length == 2) {
                o.add(Injecao.pedirQuebrar(null));
                return;
            }
            if (args.length == 5) {
                try {
                    o.add(Injecao.pedirQuebrar(new int[] {Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4])}));
                    return;
                } catch (NumberFormatException e) {
                    // cai no uso
                }
            }
        }
        o.add("uso: hoshie paralelo injetar quebrar [x y z]   (passo 4: so 'quebrar'; os outros sao do passo 6). Ultimo: " + Injecao.ultimo());
    }

    /** So com -Dhoshiestar.paralelo.teste=true (passo 2 do plano); vale com o recurso ligado ou desligado. */
    private static void congelar(List<String> o, String[] args) {
        if (args.length == 1) {
            o.add(Congelar.estado());
            return;
        }
        long t;
        try {
            t = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            o.add("uso: hoshie paralelo congelar <tick do mundo 0>   (sem numero: mostra o estado)");
            return;
        }
        o.add(Congelar.pedir(t));
    }
}
