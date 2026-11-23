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
package hoshiestar.observador;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relatorio do observador (.json e .txt). So classes de mod (nome do jar), numeros e coordenadas de secao: nenhum nome,
 * UUID nem IP de jogador (o contexto "jogador" nunca carrega objeto) e nada vai para a rede.
 */
final class Relatorio {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** Linhas de T1 no relatorio (as de maior contagem); o resto fica no resumo por mod. */
    static final int MAX_LINHAS_T1 = 1500;
    static final int MAX_LINHAS = 500;

    /** Dados que o relatorio traz alem das tabelas. */
    static final class Meta {
        String versao = "?";
        String inicio = "?";
        long segundosPedidos;
        double duracaoSegundos;
        String motivoFim = "?";
        boolean pararNoMeio;
        List<String> avisos = new ArrayList<String>();
    }

    private Relatorio() {
    }

    // ---------------------------------------------------------------- linhas decodificadas

    private static final class LinhaT1 {
        int mc, mp, ct, tipo, faixa, mesmaSec, reg, cruz;
        long cont, max;
    }

    private static List<LinhaT1> linhasT1(Nucleo n) {
        TabelaContagem t = n.t1;
        List<LinhaT1> l = new ArrayList<LinhaT1>();
        for (int i = 0; i < t.capacidade(); i++) {
            if (!t.ocupado(i)) {
                continue;
            }
            long k = t.chave(i);
            LinhaT1 x = new LinhaT1();
            x.mc = (int) (k & 0xFFF);
            x.mp = (int) ((k >> 12) & 0xFFF);
            x.ct = (int) ((k >> 24) & 0xF);
            x.tipo = (int) ((k >> 28) & 0xF);
            x.faixa = (int) ((k >> 32) & 7);
            x.mesmaSec = (int) ((k >> 35) & 1);
            x.reg = (int) ((k >> 36) & 3);
            x.cruz = (int) ((k >> 38) & 1);
            x.cont = t.cont(i);
            x.max = t.max(i);
            l.add(x);
        }
        Collections.sort(l, new Comparator<LinhaT1>() {
            @Override
            public int compare(LinhaT1 a, LinhaT1 b) {
                int c = Long.compare(b.cont, a.cont);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(a.mc, b.mc);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(a.mp, b.mp);
                if (c != 0) {
                    return c;
                }
                return Integer.compare(a.tipo, b.tipo);
            }
        });
        return l;
    }

    /** Resumo por mod: [total, cruzam secao, cruzam regiao, cruzam dimensao, distancia maxima, sem origem]. */
    private static Map<Integer, long[]> porMod(List<LinhaT1> linhas, boolean pelaPilha) {
        Map<Integer, long[]> m = new LinkedHashMap<Integer, long[]>();
        for (LinhaT1 x : linhas) {
            int mod = pelaPilha ? x.mp : (x.mc != 0 ? x.mc : x.mp);
            if (pelaPilha && x.mp == 0) {
                continue;
            }
            long[] a = m.get(mod);
            if (a == null) {
                a = new long[6];
                m.put(mod, a);
            }
            a[0] += x.cont;
            boolean origem = x.reg != 0;
            if (!origem) {
                a[5] += x.cont;
            } else if (x.cruz == 1) {
                a[3] += x.cont;
            } else {
                if (x.mesmaSec == 0) {
                    a[1] += x.cont;
                }
                if (x.reg == 2) {
                    a[2] += x.cont;
                }
                a[4] = Math.max(a[4], x.max);
            }
        }
        return m;
    }

    private static List<Map.Entry<Integer, long[]>> ordenar(Map<Integer, long[]> m) {
        List<Map.Entry<Integer, long[]>> l = new ArrayList<Map.Entry<Integer, long[]>>(m.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<Integer, long[]>>() {
            @Override
            public int compare(Map.Entry<Integer, long[]> a, Map.Entry<Integer, long[]> b) {
                long ka = a.getValue()[1] + a.getValue()[3];
                long kb = b.getValue()[1] + b.getValue()[3];
                int c = Long.compare(kb, ka);
                if (c != 0) {
                    return c;
                }
                c = Long.compare(b.getValue()[0], a.getValue()[0]);
                return c != 0 ? c : Integer.compare(a.getKey(), b.getKey());
            }
        });
        return l;
    }

    private static String dimTexto(Nucleo n, int idx) {
        if (idx >= 15) {
            return "15+";
        }
        int d = n.dimDoIdx(idx);
        return d == Integer.MIN_VALUE ? "?" : String.valueOf(d);
    }

    // ---------------------------------------------------------------- JSON

    static String json(Nucleo n, Meta m) {
        Json j = new Json();
        j.objeto();
        j.campo("formato", 1L);
        j.campo("hoshiestar", m.versao);
        j.campo("inicio", m.inicio);
        j.objeto("observacao");
        j.campo("segundos_pedidos", m.segundosPedidos);
        j.campo("duracao_segundos", m.duracaoSegundos);
        j.campo("ticks", n.ticks);
        j.campo("motivo_do_fim", m.motivoFim);
        j.campo("parou_no_meio", m.pararNoMeio);
        j.campo("amostra_pilha_1_em", (long) n.amostraPilha);
        j.campo("limite_custo_pct", n.limiteCustoPct);
        j.fimObjeto();
        j.lista("avisos");
        for (String a : avisos(n, m)) {
            j.item(a);
        }
        j.fimLista();
        j.objeto("custo_estimado");
        j.campo("pct_medio_do_tick", n.custoMedioPct());
        j.campo("pct_maximo_numa_janela", n.pctMaxJanela);
        j.campo("amostras_de_pilha", n.amostras);
        j.campo("ns_gastos_em_pilha", n.pilhaNsTotal);
        j.campo("fotos_tiradas", n.fotosTiradas);
        j.campo("ns_gastos_em_fotos", n.fotoNsTotal);
        j.campo("nota", "estimativa por contagem x custo medido por amostra; ver src/docs/observador.md");
        j.fimObjeto();
        j.objeto("contagens");
        j.campo("leituras_de_bloco_na_mesma_secao", n.rapidas);
        long sem = 0;
        for (int i = 0; i < 16; i++) {
            sem += n.semOrigemLeituras[i];
        }
        j.campo("leituras_de_bloco_sem_origem", sem);
        j.campo("acessos_registrados", n.completas);
        j.campo("cargas_de_chunk", n.cargas);
        j.campo("trocas_de_contexto", n.entradasTotal);
        j.campo("contextos_que_transbordaram_a_pilha", n.transbordosPilha);
        j.objeto("leituras_sem_origem_por_contexto");
        for (int i = 0; i < 13; i++) {
            if (n.semOrigemLeituras[i] > 0) {
                j.campo(Nucleo.NOME_CTX[i], n.semOrigemLeituras[i]);
            }
        }
        j.fimObjeto();
        j.objeto("acessos_por_tipo");
        for (int i = 1; i < 16; i++) {
            if (n.porAcesso[i] > 0) {
                j.campo(Nucleo.NOME_ACESSO[i], n.porAcesso[i]);
            }
        }
        j.fimObjeto();
        j.fimObjeto();
        j.lista("mods");
        for (int i = 0; i < n.mods.tamanho(); i++) {
            j.item(n.mods.nome(i));
        }
        j.fimLista();

        List<LinhaT1> l1 = linhasT1(n);
        j.lista("resumo_por_mod_do_contexto");
        escreverResumo(j, n, ordenar(porMod(l1, false)));
        j.fimLista();
        j.lista("resumo_por_mod_da_pilha_so_amostras");
        escreverResumo(j, n, ordenar(porMod(l1, true)));
        j.fimLista();
        j.lista("t1_acessos");
        for (int i = 0; i < l1.size() && i < MAX_LINHAS_T1; i++) {
            LinhaT1 x = l1.get(i);
            j.objeto();
            j.campo("mod_do_contexto", n.mods.nome(x.mc));
            j.campo("mod_da_pilha", x.mp == 0 ? "(sem amostra)" : n.mods.nome(x.mp));
            j.campo("contexto", Nucleo.NOME_CTX[x.ct]);
            j.campo("acesso", Nucleo.NOME_ACESSO[x.tipo]);
            j.campo("faixa_chunks", Nucleo.NOME_FAIXA[x.faixa]);
            j.campo("mesma_secao", x.mesmaSec == 1);
            j.campo("regiao", Nucleo.NOME_REGIAO[x.reg]);
            j.campo("cruzou_dimensao", x.cruz == 1);
            j.campo("contagem", x.cont);
            j.campo("distancia_max_chunks", x.max);
            j.fimObjeto();
        }
        j.fimLista();
        j.campo("t1_linhas", (long) n.t1.tamanho());
        j.campo("t1_linhas_no_relatorio", (long) Math.min(l1.size(), MAX_LINHAS_T1));
        j.campo("t1_chaves_recusadas_pelo_teto", n.t1.chavesRecusadas);

        // T2
        List<long[]> l2 = linhas2(n.t2);
        j.lista("t2_pares_entre_regioes");
        for (int i = 0; i < l2.size() && i < MAX_LINHAS; i++) {
            long k = l2.get(i)[0];
            j.objeto();
            j.campo("dimensao_origem", dimTexto(n, (int) (k & 0xF)));
            j.campo("secao_origem_x", (long) (((k >>> 8) & 0x3FFF) - 8192));
            j.campo("secao_origem_z", (long) (((k >>> 22) & 0x3FFF) - 8192));
            j.campo("dimensao_destino", dimTexto(n, (int) ((k >>> 4) & 0xF)));
            j.campo("secao_destino_x", (long) (((k >>> 36) & 0x3FFF) - 8192));
            j.campo("secao_destino_z", (long) (((k >>> 50) & 0x3FFF) - 8192));
            j.campo("contagem", l2.get(i)[1]);
            j.fimObjeto();
        }
        j.fimLista();
        j.campo("t2_linhas", (long) n.t2.tamanho());
        j.campo("t2_fora_da_faixa_de_coordenadas", n.t2ForaDaFaixa);
        j.campo("t2_chaves_recusadas_pelo_teto", n.t2.chavesRecusadas);

        // T3
        List<long[]> l3 = linhas2(n.t3);
        j.lista("t3_cargas_de_chunk");
        for (int i = 0; i < l3.size() && i < MAX_LINHAS; i++) {
            long k = l3.get(i)[0];
            j.objeto();
            j.campo("contexto", Nucleo.NOME_CTX[(int) (k & 0xF)]);
            j.campo("mod_do_contexto", n.mods.nome((int) ((k >> 4) & 0xFFF)));
            int mp = (int) ((k >> 16) & 0xFFF);
            j.campo("mod_da_pilha", mp == 0 ? "(sem amostra)" : n.mods.nome(mp));
            j.campo("onde_caiu", Nucleo.NOME_ONDE[(int) ((k >> 28) & 0xF) % Nucleo.NOME_ONDE.length]);
            j.campo("contagem", l3.get(i)[1]);
            j.fimObjeto();
        }
        j.fimLista();
        j.campo("t3_chaves_recusadas_pelo_teto", n.t3.chavesRecusadas);

        // T4
        j.objeto("t4_acessos_de_outra_thread");
        j.campo("total", n.t4Total.get());
        j.lista("por_tipo_e_mod_da_pilha");
        for (int i = 0; i < n.t4.length(); i++) {
            long v = n.t4.get(i);
            if (v > 0) {
                j.objeto();
                j.campo("acesso", Nucleo.NOME_ACESSO[(i / Mods.PASSO) & 15]);
                j.campo("mod_da_pilha", n.mods.nomeDoEspaco(i % Mods.PASSO));
                j.campo("contagem", v);
                j.fimObjeto();
            }
        }
        j.fimLista();
        j.fimObjeto();

        listaJson(j, n);

        // T5
        j.lista("t5_fotos_de_regioes");
        for (Foto.Resumo r : n.fotos) {
            j.objeto();
            j.campo("segundos", r.segundos);
            j.campo("ticks", r.ticks);
            j.campo("custo_ms", r.custoNs / 1e6);
            j.lista("dimensoes");
            for (Foto.ResumoDim d : r.dims) {
                j.objeto();
                j.campo("dimensao", (long) d.dim);
                j.campo("regioes", (long) d.regioes);
                j.campo("regioes_com_carga", (long) d.regioesComCarga);
                j.campo("secoes", (long) d.secoes);
                j.campo("secoes_com_carga", (long) d.secoesComCarga);
                j.campo("chunks", (long) d.chunks);
                j.campo("tiles", (long) d.tiles);
                j.campo("entidades", (long) d.ents);
                j.campo("tiles_fora_de_secao_carregada", (long) d.tilesForaDeSecao);
                j.campo("entidades_fora_de_secao_carregada", (long) d.entsForaDeSecao);
                j.campo("max_tiles_numa_secao", (long) d.maxTilesNumaSecao);
                j.campo("max_entidades_numa_secao", (long) d.maxEntsNumaSecao);
                j.campo("menor_distancia_em_secoes_entre_regioes_com_carga", (long) d.menorDistSecoes);
                j.lista("regioes_de_mais_carga");
                for (Foto.Regiao g : d.maiores) {
                    j.objeto();
                    j.campo("secoes", (long) g.secoes);
                    j.campo("chunks", (long) g.chunks);
                    j.campo("tiles", (long) g.tiles);
                    j.campo("entidades", (long) g.ents);
                    j.campo("secao_x_de", (long) g.secXMin);
                    j.campo("secao_x_ate", (long) g.secXMax);
                    j.campo("secao_z_de", (long) g.secZMin);
                    j.campo("secao_z_ate", (long) g.secZMax);
                    j.fimObjeto();
                }
                j.fimLista();
                j.lista("pares_de_regioes_com_carga_mais_proximas");
                for (Foto.Par p : d.pares) {
                    j.objeto();
                    j.campo("distancia_secoes", (long) p.secoes);
                    j.campo("distancia_chunks_de", (long) p.chunksMin());
                    j.campo("distancia_chunks_ate", (long) p.chunksMax());
                    j.fimObjeto();
                }
                j.fimLista();
                j.fimObjeto();
            }
            j.fimLista();
            j.fimObjeto();
        }
        j.fimLista();
        j.fimObjeto();
        return j.toString().trim() + "\n";
    }

    private static void escreverResumo(Json j, Nucleo n, List<Map.Entry<Integer, long[]>> l) {
        for (int i = 0; i < l.size() && i < MAX_LINHAS; i++) {
            long[] a = l.get(i).getValue();
            j.objeto();
            j.campo("mod", n.mods.nome(l.get(i).getKey()));
            j.campo("acessos", a[0]);
            j.campo("cruzam_secao", a[1]);
            j.campo("cruzam_regiao", a[2]);
            j.campo("cruzam_dimensao", a[3]);
            j.campo("distancia_max_chunks", a[4]);
            j.campo("sem_origem", a[5]);
            j.fimObjeto();
        }
    }

    private static List<long[]> linhas2(TabelaContagem t) {
        List<long[]> l = new ArrayList<long[]>();
        for (int i = 0; i < t.capacidade(); i++) {
            if (t.ocupado(i)) {
                l.add(new long[] {t.chave(i), t.cont(i)});
            }
        }
        Collections.sort(l, new Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                int c = Long.compare(b[1], a[1]);
                return c != 0 ? c : Long.compare(a[0], b[0]);
            }
        });
        return l;
    }

    static List<String> avisos(Nucleo n, Meta m) {
        List<String> a = new ArrayList<String>(m.avisos);
        if (n.t1.transbordou()) {
            a.add("T1 passou do teto de " + n.t1.teto() + " linhas: " + n.t1.chavesRecusadas
                    + " acessos foram para o balde 'outros' (" + n.t1.outrosCont + " no total)");
        }
        if (n.t2.transbordou()) {
            a.add("T2 passou do teto de " + n.t2.teto() + " pares: " + n.t2.chavesRecusadas + " acessos nao entraram");
        }
        if (n.t3.transbordou()) {
            a.add("T3 passou do teto de " + n.t3.teto() + " linhas: " + n.t3.chavesRecusadas + " cargas nao entraram");
        }
        if (n.mods.cheio()) {
            a.add("o registro de nomes de mod encheu (" + Mods.MAX + "): o resto aparece como 'outros'");
        }
        if (n.transbordosPilha > 0) {
            a.add("a pilha de contextos passou de " + Nucleo.PROF_MAX + " niveis " + n.transbordosPilha + " vezes");
        }
        if (n.motivoValvula != null) {
            a.add(n.motivoValvula);
        }
        return a;
    }

    // ---------------------------------------------------------------- texto

    static String texto(Nucleo n, Meta m) {
        StringBuilder s = new StringBuilder();
        s.append("OBSERVADOR do Hoshiestar ").append(m.versao).append("\n");
        s.append("inicio: ").append(m.inicio).append(" | duracao: ")
                .append(String.format(java.util.Locale.ROOT, "%.1f", m.duracaoSegundos)).append(" s de ")
                .append(m.segundosPedidos).append(" pedidos | ticks: ").append(n.ticks).append(" | fim: ")
                .append(m.motivoFim).append(m.pararNoMeio ? " (parou no meio)" : "").append("\n");
        s.append("custo estimado do observador: ")
                .append(String.format(java.util.Locale.ROOT, "%.2f%% do tick (medio), %.2f%% no pior trecho de %d ticks",
                        n.custoMedioPct(), n.pctMaxJanela, Nucleo.JANELA_TICKS))
                .append(" (limite ").append(n.limiteCustoPct).append("%). Estimativa, nao medicao.\n");
        for (String a : avisos(n, m)) {
            s.append("AVISO: ").append(a).append("\n");
        }
        long sem = 0;
        for (int i = 0; i < 16; i++) {
            sem += n.semOrigemLeituras[i];
        }
        s.append("\nCOMO LER: 'secao' = quadrado de 16x16 chunks (256x256 blocos). 'regiao' = grupo de secoes a ate 3 secoes\n")
                .append("uma da outra (regra do estudo Folia); a foto das regioes e refeita a cada 30 s. Cada acesso e uma\n")
                .append("coisa que o codigo de um contexto (tile, entidade, tick agendado...) fez numa posicao. 'x secao'\n")
                .append("e 'x regiao' sao os acessos que uma regiao paralela NAO poderia fazer sozinha. Mod da pilha\n")
                .append("e uma AMOSTRA (1 em ").append(n.amostraPilha).append(", no maximo ").append(Nucleo.MAX_AMOSTRAS_TICK)
                .append(" por tick): serve para ver quem e, nao para contar.\n\n");
        s.append("Contagens: leituras de bloco na mesma secao ").append(n.rapidas).append("; leituras sem origem ")
                .append(sem).append("; acessos registrados ").append(n.completas).append("; cargas de chunk ")
                .append(n.cargas).append("; acessos de outra thread ").append(n.t4Total.get()).append("\n\n");
        List<LinhaT1> l1 = linhasT1(n);
        s.append("== Por mod do contexto (os que mais cruzam secao/dimensao primeiro) ==\n");
        resumoTexto(s, n, ordenar(porMod(l1, false)), 25);
        s.append("\n== Por mod da pilha (so as amostras) ==\n");
        resumoTexto(s, n, ordenar(porMod(l1, true)), 25);
        s.append("\n== T1: maiores linhas (de ").append(n.t1.tamanho()).append(") ==\n");
        for (int i = 0; i < l1.size() && i < 30; i++) {
            LinhaT1 x = l1.get(i);
            s.append(String.format(java.util.Locale.ROOT, "%10d  %s / pilha %s | %s | %s | %s chunks%s | %s%s | max %d%n",
                    x.cont, n.mods.nome(x.mc), x.mp == 0 ? "-" : n.mods.nome(x.mp), Nucleo.NOME_CTX[x.ct],
                    Nucleo.NOME_ACESSO[x.tipo], Nucleo.NOME_FAIXA[x.faixa], x.mesmaSec == 1 ? " (mesma secao)" : "",
                    Nucleo.NOME_REGIAO[x.reg], x.cruz == 1 ? ", OUTRA DIMENSAO" : "", x.max));
        }
        s.append("\n== T2: pares de secoes entre regioes (de ").append(n.t2.tamanho()).append(") ==\n");
        List<long[]> l2 = linhas2(n.t2);
        for (int i = 0; i < l2.size() && i < 20; i++) {
            long k = l2.get(i)[0];
            s.append(String.format(java.util.Locale.ROOT, "%10d  dim %s secao (%d,%d) -> dim %s secao (%d,%d)%n", l2.get(i)[1],
                    dimTexto(n, (int) (k & 0xF)), ((k >>> 8) & 0x3FFF) - 8192, ((k >>> 22) & 0x3FFF) - 8192,
                    dimTexto(n, (int) ((k >>> 4) & 0xF)), ((k >>> 36) & 0x3FFF) - 8192, ((k >>> 50) & 0x3FFF) - 8192));
        }
        s.append("\n== T3: cargas de chunk dentro do tick (de ").append(n.t3.tamanho()).append(") ==\n");
        List<long[]> l3 = linhas2(n.t3);
        for (int i = 0; i < l3.size() && i < 20; i++) {
            long k = l3.get(i)[0];
            int mp = (int) ((k >> 16) & 0xFFF);
            s.append(String.format(java.util.Locale.ROOT, "%10d  contexto %s, mod %s, pilha %s: %s%n", l3.get(i)[1],
                    Nucleo.NOME_CTX[(int) (k & 0xF)], n.mods.nome((int) ((k >> 4) & 0xFFF)),
                    mp == 0 ? "-" : n.mods.nome(mp), Nucleo.NOME_ONDE[(int) ((k >> 28) & 0xF) % Nucleo.NOME_ONDE.length]));
        }
        s.append("\n== T4: acessos de outra thread: ").append(n.t4Total.get()).append(" ==\n");
        for (int i = 0; i < n.t4.length(); i++) {
            long v = n.t4.get(i);
            if (v > 0) {
                s.append(String.format(java.util.Locale.ROOT, "%10d  %s, pilha %s%n", v, Nucleo.NOME_ACESSO[(i / Mods.PASSO) & 15],
                        n.mods.nomeDoEspaco(i % Mods.PASSO)));
            }
        }
        listaTexto(s, n);
        s.append("\n== T5: fotos das regioes (").append(n.fotosTiradas).append(" tiradas; ").append(n.fotos.size())
                .append(" guardadas) ==\n");
        Foto.Resumo ult = n.fotos.isEmpty() ? null : n.fotos.get(n.fotos.size() - 1);
        if (ult != null) {
            s.append("ultima foto (").append(ult.segundos).append(" s):\n");
            for (Foto.ResumoDim d : ult.dims) {
                s.append(String.format(java.util.Locale.ROOT,
                        "  dim %d: %d regioes (%d com carga), %d secoes, %d chunks, %d tiles, %d entidades; menor distancia entre regioes com carga: %s%n",
                        d.dim, d.regioes, d.regioesComCarga, d.secoes, d.chunks, d.tiles, d.ents,
                        d.menorDistSecoes == 0 ? "nenhum par a ate " + Foto.RAIO_DISTANCIA + " secoes"
                                : d.menorDistSecoes + " secoes (" + ((d.menorDistSecoes - 1) * 16 + 1) + " a "
                                        + ((d.menorDistSecoes + 1) * 16 - 1) + " chunks)"));
            }
        }
        return s.toString();
    }

    // ---------------------------------------------------------------- prototipo da lista de tiles (plano, secao 4)

    private static List<long[]> linhasAmostrasLista(ContagemLista c) {
        return linhas2(c.amostras);
    }

    static void listaJson(Json j, Nucleo n) {
        ContagemLista c = n.lista;
        j.objeto("lista_tiles");
        j.campo("ligada", Observador.LISTA);
        if (Observador.LISTA) {
            long[] x = c.indicadores();
            j.campo("indicacao", c.indicacao(n));
            j.campo("chamadas_do_world", c.totalDentro());
            j.campo("chamadas_de_fora", x[0]);
            j.campo("de_fora_em_contexto_serial", x[1]);
            j.campo("de_fora_sem_contexto_conhecido", x[6]);
            j.campo("de_fora_em_contexto_de_regiao", x[2]);
            j.campo("modificacoes_de_fora", x[3]);
            j.campo("modificacoes_de_fora_em_contexto_de_regiao", x[4]);
            j.campo("leituras_de_fora_em_contexto_de_regiao", x[5]);
            j.campo("chamadas_numa_lista_aposentada", c.totalVelha());
            j.campo("chamadas_de_outra_thread", c.outraTotal.get());
            j.campo("trocas_do_objeto_pelo_world", c.trocasPeloMundo);
            j.campo("trocas_do_objeto_de_fora", c.trocasDeFora);
            j.campo("mundos_conferidos", c.mundosConferidos);
            j.campo("amostras_de_pilha", c.amostrasFeitas);
            j.campo("estouros_da_pilha_de_marcas", c.estourosMarca);
            j.lista("classes_achadas_no_campo");
            for (Map.Entry<String, Long> e : c.classesNoCampo.entrySet()) {
                j.objeto();
                j.campo("classe", e.getKey());
                j.campo("vezes", e.getValue());
                j.fimObjeto();
            }
            j.fimLista();
            j.lista("por_operacao");
            for (int op = 0; op < ListaObservada.NOPS; op++) {
                long f = c.foraPorOp(op);
                if (f == 0 && c.dentro[op] == 0 && c.velha[op] == 0) {
                    continue;
                }
                j.objeto();
                j.campo("operacao", ListaObservada.NOME_OP[op]);
                j.campo("categoria", String.valueOf(ListaObservada.CATEGORIA[op]));
                j.campo("do_world", c.dentro[op]);
                j.campo("de_fora", f);
                j.campo("lista_aposentada", c.velha[op]);
                j.objeto("de_fora_por_contexto");
                for (int ct = 0; ct < 16; ct++) {
                    long v = c.fora[op * 16 + ct];
                    if (v > 0) {
                        j.campo(Nucleo.NOME_CTX[ct], v);
                    }
                }
                j.fimObjeto();
                j.fimObjeto();
            }
            j.fimLista();
            j.lista("amostras");
            List<long[]> l = linhasAmostrasLista(c);
            for (int i = 0; i < l.size() && i < MAX_LINHAS; i++) {
                long k = l.get(i)[0];
                j.objeto();
                j.campo("operacao", ListaObservada.NOME_OP[(int) (k & 63) % ListaObservada.NOPS]);
                j.campo("contexto", Nucleo.NOME_CTX[(int) ((k >> 6) & 15)]);
                j.campo("mod_do_contexto", n.mods.nome((int) ((k >> 10) & 0xFFF)));
                j.campo("mod_da_pilha", n.mods.nome((int) ((k >> 22) & 0xFFF)));
                j.campo("chamador", c.chamadores.nome((int) ((k >> 34) & 0xFFF)));
                j.campo("contagem", l.get(i)[1]);
                j.fimObjeto();
            }
            j.fimLista();
            j.lista("outra_thread_por_operacao_e_mod_da_pilha");
            for (int i = 0; i < c.outra.length(); i++) {
                long v = c.outra.get(i);
                if (v > 0) {
                    j.objeto();
                    j.campo("operacao", ListaObservada.NOME_OP[i / Mods.PASSO]);
                    j.campo("mod_da_pilha", n.mods.nomeDoEspaco(i % Mods.PASSO));
                    j.campo("contagem", v);
                    j.fimObjeto();
                }
            }
            j.fimLista();
        }
        j.fimObjeto();
    }

    static void listaTexto(StringBuilder s, Nucleo n) {
        ContagemLista c = n.lista;
        s.append("\n== Prototipo da lista de tiles (observador.lista): ");
        if (!Observador.LISTA) {
            s.append("desligado ==\n");
            return;
        }
        long[] x = c.indicadores();
        s.append("ligado ==\n");
        s.append("INDICACAO (so indicacao; a decisao e do relatorio do portao): ").append(c.indicacao(n)).append("\n");
        s.append("chamadas do World ").append(c.totalDentro()).append("; de fora ").append(x[0]).append(" (contexto serial ")
                .append(x[1]).append(", contexto de regiao ").append(x[2]).append(", sem contexto conhecido ").append(x[6]).append("); modificacoes de fora ").append(x[3])
                .append(" (em contexto de regiao ").append(x[4]).append("); numa lista aposentada ").append(c.totalVelha())
                .append("; de outra thread ").append(c.outraTotal.get()).append("\n");
        s.append("trocas do objeto: pelo World ").append(c.trocasPeloMundo).append(", de fora ").append(c.trocasDeFora)
                .append(" (em ").append(c.mundosConferidos).append(" conferencias de mundo)");
        if (!c.classesNoCampo.isEmpty()) {
            s.append("; classes achadas no campo: ").append(c.classesNoCampo);
        }
        s.append("\n");
        s.append(String.format(java.util.Locale.ROOT, "%12s %12s %10s  %s%n", "do World", "de fora", "aposent.", "operacao (L leitura, I iteracao, M modificacao, A so ArrayList, O Object)"));
        for (int op = 0; op < ListaObservada.NOPS; op++) {
            long f = c.foraPorOp(op);
            if (f == 0 && c.dentro[op] == 0 && c.velha[op] == 0) {
                continue;
            }
            s.append(String.format(java.util.Locale.ROOT, "%12d %12d %10d  %s %s%n", c.dentro[op], f, c.velha[op],
                    ListaObservada.CATEGORIA[op], ListaObservada.NOME_OP[op]));
        }
        s.append("amostras de pilha das chamadas de fora (").append(c.amostrasFeitas)
                .append("; toda modificacao e as leituras 1 em ").append(n.amostraPilha).append(", no maximo ")
                .append(ContagemLista.MAX_AMOSTRAS_TICK).append(" por tick):\n");
        List<long[]> l = linhasAmostrasLista(c);
        for (int i = 0; i < l.size() && i < 30; i++) {
            long k = l.get(i)[0];
            int mp = (int) ((k >> 22) & 0xFFF);
            s.append(String.format(java.util.Locale.ROOT, "%10d  %s | contexto %s, mod %s | pilha %s | chamador %s%n", l.get(i)[1],
                    ListaObservada.NOME_OP[(int) (k & 63) % ListaObservada.NOPS], Nucleo.NOME_CTX[(int) ((k >> 6) & 15)],
                    n.mods.nome((int) ((k >> 10) & 0xFFF)), mp == 0 ? "-" : n.mods.nome(mp),
                    c.chamadores.nome((int) ((k >> 34) & 0xFFF))));
        }
    }

    private static void resumoTexto(StringBuilder s, Nucleo n, List<Map.Entry<Integer, long[]>> l, int max) {
        s.append(String.format(java.util.Locale.ROOT, "%10s %10s %10s %10s %8s %10s  %s%n", "acessos", "x secao", "x regiao",
                "x dimens.", "dist.max", "sem orig.", "mod"));
        for (int i = 0; i < l.size() && i < max; i++) {
            long[] a = l.get(i).getValue();
            s.append(String.format(java.util.Locale.ROOT, "%10d %10d %10d %10d %8d %10d  %s%n", a[0], a[1], a[2], a[3], a[4],
                    a[5], n.mods.nome(l.get(i).getKey())));
        }
    }

    // ---------------------------------------------------------------- gravacao

    /** Grava {@code observador-<nome>.json} e {@code .txt} na pasta. Devolve os dois arquivos. */
    static File[] gravar(Nucleo n, Meta m, File pasta, String nome) throws IOException {
        if (!pasta.isDirectory() && !pasta.mkdirs() && !pasta.isDirectory()) {
            throw new IOException("nao consegui criar a pasta " + pasta.getPath());
        }
        File j;
        File t;
        synchronized (Relatorio.class) {
            // dois relatorios no mesmo segundo nao se sobrescrevem: o segundo ganha "-2", o terceiro "-3"...
            int k = 1;
            do {
                String suf = k == 1 ? "" : "-" + k;
                j = new File(pasta, "observador-" + nome + suf + ".json");
                t = new File(pasta, "observador-" + nome + suf + ".txt");
                k++;
            } while (j.exists() || t.exists());
            Files.write(j.toPath(), json(n, m).getBytes(UTF8));
            Files.write(t.toPath(), texto(n, m).getBytes(UTF8));
        }
        return new File[] {j, t};
    }
}
