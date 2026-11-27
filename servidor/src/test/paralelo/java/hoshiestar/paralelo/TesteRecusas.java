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

import hoshiestar.teste.Verifica;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** As recusas do paralelo e as faixas dos valores (funcoes puras): cada recusa sozinha, a ordem, o desligado. */
public final class TesteRecusas {

    private static Verifica v;

    public static void main(String[] args) {
        v = new Verifica("TesteRecusas");
        try {
            padroes();
            faixas();
            classes();
            recusas();
            ordemEDeterminismo();
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    /** Fatos em que nada impede o modo sombra (os do jogo ja coletados, com as dimensoes 0, -1 e 1). */
    static Recusas.Fatos bons() {
        Recusas.Fatos f = new Recusas.Fatos();
        f.jogoConferido = true;
        f.dimensoes = new HashSet<Integer>(Arrays.asList(0, -1, 1));
        f.orcamentoLigado = true;
        f.paradaLigada = false;
        f.chunkmapCache4Seguro = false;
        return f;
    }

    static Parametros com(String modo, int dim, int threads, String classes) {
        return Parametros.ler(modo, dim, threads, classes, "parar", "parar", 64, 5, 1200, 600);
    }

    static boolean tem(List<String> l, String trecho) {
        for (String s : l) {
            if (s.contains(trecho)) {
                return true;
            }
        }
        return false;
    }

    static void padroes() {
        Parametros p = Parametros.padrao();
        v.sim(p.problemas.isEmpty(), "os padroes do plano (secao 10) estao todos na faixa " + p.problemas);
        v.sim(p.modo.equals("sombra") && p.dimensao == 0 && p.threads == 3 && p.classes.isEmpty() && p.aoFalhar.equals("parar")
                && p.aoViolar.equals("parar") && p.leituraAmostra == 64 && p.avisoEsperaS == 5 && p.sinalTicks == 1200
                && p.aquecimentoTicks == 600, "padroes: sombra, dimensao 0, 3 threads, classes vazio, parar, 64, 5, 1200, 600");
        v.sim(Recusas.avaliar(true, p, bons()).isEmpty(), "ligado + padroes + orcamento ligado: nenhuma recusa");
    }

    static void faixas() {
        v.sim(Parametros.ler("Sombra_Ordenada ", 0, 1, "", " PARAR", "Rebaixar", 0, 1, 0, 0).problemas.isEmpty(),
                "limites de baixo, texto com maiuscula e espaco passam");
        v.sim(Parametros.ler("paralelo", 0, 6, "", "parar", "parar", 1000000, 3600, 1728000, 1728000).problemas.isEmpty(),
                "limites de cima passam");
        v.igual("sombra_ordenada", Parametros.ler("Sombra_Ordenada ", 0, 1, "", "parar", "parar", 0, 1, 0, 0).modo, "modo e normalizado");
        String[] campos = {"paralelo.modo", "paralelo.threads", "paralelo.threads", "paralelo.ao_falhar", "paralelo.ao_violar",
            "paralelo.leitura_amostra", "paralelo.leitura_amostra", "paralelo.aviso_espera_s", "paralelo.aviso_espera_s",
            "paralelo.sinal_ticks", "paralelo.sinal_ticks", "paralelo.aquecimento_ticks", "paralelo.aquecimento_ticks"};
        Parametros[] ruins = {
            Parametros.ler("lixo", 0, 3, "", "parar", "parar", 64, 5, 1200, 600),
            Parametros.ler("sombra", 0, 0, "", "parar", "parar", 64, 5, 1200, 600),
            Parametros.ler("sombra", 0, 7, "", "parar", "parar", 64, 5, 1200, 600),
            Parametros.ler("sombra", 0, 3, "", "talvez", "parar", 64, 5, 1200, 600),
            Parametros.ler("sombra", 0, 3, "", "parar", "", 64, 5, 1200, 600),
            Parametros.ler("sombra", 0, 3, "", "parar", "parar", -1, 5, 1200, 600),
            Parametros.ler("sombra", 0, 3, "", "parar", "parar", 1000001, 5, 1200, 600),
            Parametros.ler("sombra", 0, 3, "", "parar", "parar", 64, 0, 1200, 600),
            Parametros.ler("sombra", 0, 3, "", "parar", "parar", 64, 3601, 1200, 600),
            Parametros.ler("sombra", 0, 3, "", "parar", "parar", 64, 5, -1, 600),
            Parametros.ler("sombra", 0, 3, "", "parar", "parar", 64, 5, 1728001, 600),
            Parametros.ler("sombra", 0, 3, "", "parar", "parar", 64, 5, 1200, -1),
            Parametros.ler("sombra", 0, 3, "", "parar", "parar", 64, 5, 1200, 1728001),
        };
        for (int i = 0; i < ruins.length; i++) {
            v.sim(ruins[i].problemas.size() == 1 && ruins[i].problemas.get(0).contains(campos[i]),
                    "fora da faixa gera exatamente um problema, de " + campos[i] + ": " + ruins[i].problemas);
        }
        Parametros nulos = Parametros.ler(null, 0, 3, null, null, null, 64, 5, 1200, 600);
        v.sim(nulos.problemas.size() == 3 && nulos.classes.isEmpty(), "texto nulo: modo, ao_falhar e ao_violar invalidos; classes vazio " + nulos.problemas);
        // problema de faixa vira recusa (e so com o recurso ligado)
        v.sim(tem(Recusas.avaliar(true, ruins[1], bons()), "paralelo.threads"), "valor fora da faixa vira motivo de recusa");
        v.sim(Recusas.avaliar(false, ruins[1], bons()).isEmpty(), "desligado: nem valor ruim recusa (nada roda)");
    }

    static void classes() {
        Parametros p = com("sombra", 0, 3, " Mesa, bau,,BAU ,bau_ender");
        v.igual(Arrays.asList("mesa", "bau", "bau_ender"), p.classes, "classes: minusculas, sem vazio, sem repetir");
        v.sim(p.classesForaDaLista.isEmpty() && Recusas.avaliar(true, p, bons()).isEmpty(), "as tres classes da onda 1 passam");
        v.sim(com("sombra", 0, 3, "").classes.isEmpty() && Recusas.avaliar(true, com("sombra", 0, 3, ""), bons()).isEmpty(),
                "vazio = fase vazia, sem recusa");
        Parametros q = com("sombra", 0, 3, "bau, funil, xyz, funil");
        v.igual(Arrays.asList("funil", "xyz"), q.classesForaDaLista, "fora da lista, na ordem e sem repetir");
        List<String> r = Recusas.avaliar(true, q, bons());
        v.sim(r.size() == 2 && tem(r, "'funil'") && tem(r, "'xyz'"), "uma recusa por classe fora da lista: " + r);
    }

    static void recusas() {
        Recusas.Fatos f = bons();
        Parametros sombra = com("sombra", 0, 3, "");
        // orcamento
        f.orcamentoLigado = false;
        List<String> r = Recusas.avaliar(true, sombra, f);
        v.sim(r.size() == 1 && r.get(0).startsWith("orcamento desligado"), "orcamento desligado: uma recusa " + r);
        v.sim(Recusas.avaliar(false, sombra, f).isEmpty(), "orcamento desligado + paralelo desligado: sem recusa");
        // parada: sombra nao exige; os outros dois exigem
        f = bons();
        v.sim(Recusas.avaliar(true, com("sombra", 0, 3, ""), f).isEmpty(), "sombra sem parada: sem recusa");
        r = Recusas.avaliar(true, com("sombra_ordenada", 0, 3, ""), f);
        v.sim(r.size() == 1 && r.get(0).contains("exige parada.ligado"), "sombra_ordenada sem parada: recusa " + r);
        f.paradaLigada = true;
        v.sim(Recusas.avaliar(true, com("sombra_ordenada", 0, 3, ""), f).isEmpty(), "sombra_ordenada com parada: sem recusa");
        f.paradaLigada = false;
        r = Recusas.avaliar(true, com("paralelo", 0, 3, ""), f);
        v.sim(tem(r, "exige parada.ligado") && tem(r, "exige chunkmap.cache4Seguro") && r.size() == 2, "paralelo sem parada e sem H01: duas recusas " + r);
        f.paradaLigada = true;
        r = Recusas.avaliar(true, com("paralelo", 0, 3, ""), f);
        v.sim(r.size() == 1 && r.get(0).contains("chunkmap.cache4Seguro"), "paralelo com parada, sem H01: so a do H01 " + r);
        f.chunkmapCache4Seguro = true;
        v.sim(Recusas.avaliar(true, com("paralelo", 0, 3, ""), f).isEmpty(), "paralelo com parada e H01: sem recusa");
        f = bons();
        f.chunkmapCache4Seguro = false;
        v.sim(!tem(Recusas.avaliar(true, com("sombra_ordenada", 0, 3, ""), f), "cache4Seguro"), "o H01 so e exigido no modo paralelo");
        // modo invalido nao dispara as recusas de modo (so o problema de faixa)
        r = Recusas.avaliar(true, com("lixo", 0, 3, ""), bons());
        v.sim(r.size() == 1 && r.get(0).startsWith("paralelo.modo"), "modo invalido: so o motivo do valor " + r);
        // fatos do jogo ainda nao coletados (o valor inicial): recusa em TODOS os modos (achado importante 3 da revisao do passo 1)
        Recusas.Fatos cru = new Recusas.Fatos();
        cru.orcamentoLigado = true;
        cru.paradaLigada = true;
        cru.chunkmapCache4Seguro = true;
        for (String modo : new String[] {"sombra", "sombra_ordenada", "paralelo"}) {
            r = Recusas.avaliar(true, com(modo, 0, 3, ""), cru);
            v.sim(r.size() == 1 && r.get(0).equals(Recusas.FATOS_NAO_CONFERIDOS), "fatos do jogo nao coletados, modo " + modo + ": uma recusa " + r);
        }
        v.sim(Recusas.avaliar(false, sombra, cru).isEmpty(), "fatos do jogo nao coletados + paralelo desligado: sem recusa");
        cru.randTrocado = true;
        r = Recusas.avaliar(true, sombra, cru);
        v.sim(r.size() == 2 && tem(r, "world.rand"), "fato ruim ja preenchido conta mesmo antes de 'conferido': " + r);
        cru.randTrocado = false;
        cru.jogoConferido = true; // marcou conferido sem dizer as dimensoes: continua recusando
        r = Recusas.avaliar(true, sombra, cru);
        v.sim(r.size() == 1 && r.get(0).contains("lista de dimensoes do servidor nao informada"), "conferido sem a lista de dimensoes: recusa " + r);
        // dimensao unica e existente
        f = bons();
        v.sim(Recusas.avaliar(true, com("sombra", -1, 3, ""), f).isEmpty(), "dimensao -1 existe: sem recusa");
        r = Recusas.avaliar(true, com("sombra", 7, 3, ""), f);
        v.sim(r.size() == 1 && r.get(0).contains("dimensao 7 inexistente"), "dimensao 7 inexistente: recusa " + r);
        f.dimensoes = new HashSet<Integer>();
        v.sim(tem(Recusas.avaliar(true, sombra, f), "dimensao 0 inexistente"), "servidor sem nenhuma dimensao: recusa a 0");
        // fatos do jogo
        f = bons();
        f.randTrocado = true;
        r = Recusas.avaliar(true, sombra, f);
        v.sim(r.size() == 1 && r.get(0).contains("world.rand"), "world.rand trocado: recusa " + r);
        f = bons();
        f.distanciaDeSimulacaoLigada = true;
        r = Recusas.avaliar(true, sombra, f);
        v.sim(r.size() == 1 && r.get(0).contains("addSimulationDistance_WIP"), "distancia de simulacao do hodgepodge: recusa " + r);
        f = bons();
        f.observadoresNaoRevisados = Arrays.asList("a.B", "c.D");
        r = Recusas.avaliar(true, sombra, f);
        v.sim(r.size() == 2 && r.get(0).endsWith("a.B") && r.get(1).endsWith("c.D"), "observador nao revisado: uma recusa por nome " + r);
        f = bons();
        f.firewallSemPortao = true;
        r = Recusas.avaliar(true, sombra, f);
        v.sim(r.size() == 1 && r.get(0).contains("allowTileEntityTick"), "firewall sem o portao: recusa " + r);
        f = bons();
        f.semVeredito = Arrays.asList("mod X (mixin)", "mod Y (transformador)");
        r = Recusas.avaliar(true, sombra, f);
        v.sim(r.size() == 2 && r.get(0).endsWith("mod X (mixin)") && r.get(1).endsWith("mod Y (transformador)"),
                "mods da rede sem veredito: uma recusa por nome " + r);
        v.sim(Recusas.avaliar(false, sombra, f).isEmpty(), "desligado: nenhuma recusa, mesmo com fato ruim");
    }

    static void ordemEDeterminismo() {
        Recusas.Fatos f = new Recusas.Fatos(); // orcamento desligado, parada desligada, fatos do jogo nao marcados como conferidos
        f.dimensoes = new HashSet<Integer>(Arrays.asList(0));
        f.randTrocado = true;
        f.distanciaDeSimulacaoLigada = true;
        f.firewallSemPortao = true;
        f.observadoresNaoRevisados = Arrays.asList("o");
        f.semVeredito = Arrays.asList("s");
        Parametros p = Parametros.ler("paralelo", 5, 9, "funil", "parar", "parar", 64, 5, 1200, 600);
        List<String> a = Recusas.avaliar(true, p, f);
        List<String> b = Recusas.avaliar(true, p, f);
        v.igual(a, b, "mesma entrada, mesma lista na mesma ordem");
        String[] ordem = {"paralelo.threads", "orcamento desligado", "exige parada.ligado", "exige chunkmap.cache4Seguro",
            "fatos do jogo ainda nao conferidos", "dimensao 5 inexistente", "classe 'funil'", "world.rand", "addSimulationDistance_WIP", "ChunkRequestEvent",
            "allowTileEntityTick", "sem veredito"};
        boolean ok = a.size() == ordem.length;
        for (int i = 0; ok && i < ordem.length; i++) {
            ok = a.get(i).contains(ordem[i]);
        }
        v.sim(ok, "todos os motivos juntos, na ordem fixa (" + a.size() + " motivos): " + a);
        Set<String> sem = new HashSet<String>(a);
        v.igual(a.size(), sem.size(), "nenhum motivo repetido");
    }
}
