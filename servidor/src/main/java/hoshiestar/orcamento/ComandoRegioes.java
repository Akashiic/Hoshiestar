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
package hoshiestar.orcamento;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * O texto do comando {@code hoshie regioes} (sem Minecraft: o adaptador entrega a posicao de quem digitou e as
 * ferramentas que precisam do jogo). Le so o {@link Retrato}; pede mudancas ao {@link Nucleo} por fila.
 */
public final class ComandoRegioes {

    /** Onde esta quem digitou (jogador); null no console. */
    public static final class Posicao {
        public final int dim, bx, bz;

        public Posicao(int dim, int bx, int bz) {
            this.dim = dim;
            this.bx = bx;
            this.bz = bz;
        }
    }

    /** As ferramentas de teste que dependem do jogo (NBT). Entregue so com a propriedade de teste. */
    public interface FerramentasJogo {
        String contar(long idRegiao, String arquivo);

        String foto(String arquivo, long tick, long[] excluir);
    }

    private ComandoRegioes() {
    }

    public static String ajuda(boolean teste) {
        return "regioes [id|aqui|modo medir|modo cortar] - orcamento de tick por regiao (TPS e ms por regiao, quem esta cortada)"
                + (teste ? "; teste <id|aqui|x z> <ms>|parar; registro <s>; contar <id> <arq>; foto <arq> <tick> [ids a excluir]" : "");
    }

    public static String[] executar(Nucleo n, String[] args, Posicao aqui, FerramentasJogo fj) {
        List<String> o = new ArrayList<String>();
        try {
            if (n == null || !n.ativo()) {
                o.add(n == null ? "orcamento desligado (orcamento.ligado: false no hoshiestar.yml; ligue e reinicie)."
                        : "orcamento DESLIGADO por erro interno (veja o log); o servidor segue como o original.");
                return o.toArray(new String[0]);
            }
            Retrato r = n.retrato();
            if (args.length == 0) {
                resumo(o, n, r);
            } else if (args[0].equalsIgnoreCase("modo")) {
                modo(o, n, args);
            } else if (args[0].equalsIgnoreCase("aqui")) {
                if (aqui == null) {
                    o.add("'aqui' so vale para jogador (o console nao tem posicao). Use o numero da regiao.");
                } else if (r == null) {
                    o.add("ainda sem foto das regioes (espere alguns segundos).");
                } else {
                    Retrato.Regiao g = r.emBloco(aqui.dim, aqui.bx, aqui.bz);
                    if (g == null) {
                        o.add("aqui nao ha regiao (chunk sem carga conhecida pelo mapa): nunca e cortado.");
                    } else {
                        detalhe(o, r, g);
                    }
                }
            } else if (n.ferramentasTeste() && args[0].equalsIgnoreCase("teste")) {
                teste(o, n, r, args, aqui);
            } else if (n.ferramentasTeste() && args[0].equalsIgnoreCase("registro")) {
                registro(o, n, args);
            } else if (n.ferramentasTeste() && args[0].equalsIgnoreCase("contar")) {
                if (args.length != 3 || !numero(args[1]) || !nomeSeguro(args[2])) {
                    o.add("uso: regioes contar <id> <arquivo>");
                } else {
                    o.add(fj == null ? "ferramenta indisponivel" : fj.contar(Long.parseLong(args[1]), args[2]));
                }
            } else if (n.ferramentasTeste() && args[0].equalsIgnoreCase("foto")) {
                foto(o, args, fj);
            } else if (numero(args[0])) {
                Retrato.Regiao g = r == null ? null : r.porId(Long.parseLong(args[0]));
                if (g == null) {
                    o.add("nao ha regiao " + args[0] + " (veja os numeros em 'hoshie regioes'; eles mudam se a regiao se juntar ou dividir).");
                } else {
                    detalhe(o, r, g);
                }
            } else {
                o.add("uso: hoshie " + ajuda(n.ferramentasTeste()));
            }
        } catch (RuntimeException e) {
            o.add("erro no comando: " + e);
        }
        return o.toArray(new String[0]);
    }

    private static boolean numero(String s) {
        try {
            Long.parseLong(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Nome de arquivo sem pasta e sem "..": o comando so grava dentro de hoshiestar/. */
    static boolean nomeSeguro(String s) {
        return s != null && !s.isEmpty() && s.length() <= 80 && s.matches("[A-Za-z0-9._-]+") && !s.contains("..");
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static void resumo(List<String> o, Nucleo n, Retrato r) {
        if (r == null) {
            o.add("orcamento ligado, modo " + (n.cortar() ? "cortar" : "medir") + "; ainda sem foto (espere alguns segundos).");
            return;
        }
        o.add("Orcamento de tick por regiao: modo " + (r.cortar ? "cortar" : "medir") + ". Servidor: TPS " + f1(r.servidorTps)
                + ", tick medio " + f1(r.servidorMediaMs) + " ms (100 ticks), p95 " + f1(r.servidorP95Ms) + ", p99 "
                + f1(r.servidorP99Ms) + ", maximo " + f1(r.servidorMaxMs) + " ms (ultimos " + r.servidorTicksNaJanela
                + " ticks).");
        o.add(r.regioes.length + " regiao(oes): " + r.contar("cortada") + " cortada(s), " + r.contar("estouraria")
                + " estouraria(m) (modo medir), " + r.contar("protegida") + " protegida(s), " + r.contar("suspensa")
                + " suspensa(s). Alvo " + f1(n.parametros().alvoTickMs) + " ms; orcamento por regiao "
                + f1(n.parametros().regiaoMs) + " ms.");
        for (Retrato.Regiao g : r.maisPesadas(10)) {
            o.add(linha(g));
        }
        if (r.regioes.length > 10) {
            o.add("(mostrando as 10 mais pesadas; 'hoshie regioes <id>' mostra uma)");
        }
    }

    private static String linha(Retrato.Regiao g) {
        return "  #" + g.id + " dim " + g.dim + " x " + g.caixa[0] + ".." + g.caixa[2] + " z " + g.caixa[1] + ".." + g.caixa[3]
                + " | " + g.chunks + " chunks " + g.tiles + " tiles " + g.ents + " ents | rodada " + f1(g.custoRodadaMs)
                + " ms (p95 " + f1(g.p95RodadaMs) + ") | N " + g.n + " | TPS " + f1(g.tps) + " | " + g.estado
                + (g.protecao != null ? " (" + g.protecao + ")" : "") + (g.pesada ? " | PESADA" : "");
    }

    private static void detalhe(List<String> o, Retrato r, Retrato.Regiao g) {
        o.add("Regiao " + g.id + " (dim " + g.dim + ", centro " + g.centroX() + "," + g.centroZ() + ", " + g.secoes
                + " secao(oes) de 16x16 chunks)");
        o.add(linha(g));
        o.add("  ms por tick real (media do periodo): " + f1(g.custoRealMs) + "; maior rodada vista: " + f1(g.maxRodadaMs)
                + " ms; estado: " + g.estado + (g.protecao != null ? " (" + g.protecao + ")" : ""));
        StringBuilder sb = new StringBuilder("  classes de tile mais comuns: ");
        if (g.classes.length == 0) {
            sb.append("(sem dado)");
        }
        for (int i = 0; i < g.classes.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(g.classes[i]);
        }
        o.add(sb.toString());
        if (g.esperaMs > 0) {
            o.add("  TESTE: espera de " + f1(g.esperaMs) + " ms por rodada ligada nesta regiao");
        }
    }

    private static void modo(List<String> o, Nucleo n, String[] args) {
        if (args.length != 2 || !(args[1].equalsIgnoreCase("medir") || args[1].equalsIgnoreCase("cortar"))) {
            o.add("uso: hoshie regioes modo medir|cortar");
            return;
        }
        boolean c = args[1].equalsIgnoreCase("cortar");
        n.pedirModo(c);
        o.add("modo " + (c ? "cortar" : "medir") + " pedido (vale no proximo tick)."
                + (c ? " Regioes acima do orcamento passam a ticar 1 a cada N (inteiras), so com o servidor acima do alvo." : ""));
    }

    private static void teste(List<String> o, Nucleo n, Retrato r, String[] args, Posicao aqui) {
        if (args.length == 2 && args[1].equalsIgnoreCase("parar")) {
            n.pedirEspera(-1, 0);
            o.add("teste: esperas desligadas (vale no proximo tick).");
            return;
        }
        long id;
        String ms;
        if (args.length == 3 && args[1].equalsIgnoreCase("aqui")) {
            Retrato.Regiao g = aqui == null || r == null ? null : r.emBloco(aqui.dim, aqui.bx, aqui.bz);
            if (g == null) {
                o.add("teste: aqui nao ha regiao conhecida.");
                return;
            }
            id = g.id;
            ms = args[2];
        } else if (args.length == 3 && numero(args[1])) {
            id = Long.parseLong(args[1]);
            ms = args[2];
        } else if (args.length == 4 && numero(args[1]) && numero(args[2])) {
            Retrato.Regiao g = r == null ? null : r.emBloco(0, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            if (g == null) {
                o.add("teste: na dimensao 0 no ponto " + args[1] + "," + args[2] + " nao ha regiao conhecida.");
                return;
            }
            id = g.id;
            ms = args[3];
        } else {
            o.add("uso: hoshie regioes teste <id|aqui|x z> <ms>|parar");
            return;
        }
        double v;
        try {
            v = Double.parseDouble(ms);
        } catch (NumberFormatException e) {
            o.add("teste: '" + ms + "' nao e um numero de ms.");
            return;
        }
        if (!(v >= 0 && v <= 2000) || (r != null && r.porId(id) == null)) {
            o.add("teste: ms de 0 a 2000 e uma regiao que exista.");
            return;
        }
        n.pedirEspera(id, v);
        o.add("teste: regiao " + id + " passa a gastar " + f1(v) + " ms por rodada na primeira tile (vale no proximo tick).");
    }

    private static void registro(List<String> o, Nucleo n, String[] args) {
        if (args.length != 2 || !numero(args[1]) || Long.parseLong(args[1]) < 1 || Long.parseLong(args[1]) > 3600) {
            o.add("uso: hoshie regioes registro <segundos 1-3600>");
            return;
        }
        String nome = "orcamento-registro-" + System.currentTimeMillis() + ".csv";
        n.pedirRegistro((int) Long.parseLong(args[1]), nome);
        o.add("registro por tick: hoshiestar/" + nome + " por " + args[1] + " s (comeca no proximo tick).");
    }

    private static void foto(List<String> o, String[] args, FerramentasJogo fj) {
        if (args.length < 3 || !nomeSeguro(args[1]) || !numero(args[2])) {
            o.add("uso: hoshie regioes foto <arquivo> <tick do mundo> [ids de regiao a excluir]");
            return;
        }
        long[] ex = new long[args.length - 3];
        for (int i = 3; i < args.length; i++) {
            if (!numero(args[i])) {
                o.add("foto: '" + args[i] + "' nao e numero de regiao.");
                return;
            }
            ex[i - 3] = Long.parseLong(args[i]);
        }
        o.add(fj == null ? "ferramenta indisponivel" : fj.foto(args[1], Long.parseLong(args[2]), ex));
    }
}
