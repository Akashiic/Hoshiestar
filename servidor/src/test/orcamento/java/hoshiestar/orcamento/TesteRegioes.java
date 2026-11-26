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

import hoshiestar.regioes.FotoMundo;
import hoshiestar.regioes.Mapa;
import hoshiestar.regioes.MapaSecoes;
import hoshiestar.regioes.Regionizador;
import hoshiestar.teste.Verifica;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Regionizador: juncao (raio 3 secoes), divisao, bordas, dimensoes, contagens. Sem Minecraft. */
public final class TesteRegioes {

    static FotoMundo mundo(int dim, int... chunks) {
        FotoMundo f = new FotoMundo(dim);
        for (int i = 0; i < chunks.length; i += 2) {
            f.chunk(chunks[i], chunks[i + 1]);
        }
        return f;
    }

    static int regioes(FotoMundo... m) {
        return new Regionizador().calcular(m, null).nRegioes;
    }

    public static void main(String[] args) {
        Verifica v = new Verifica("TesteRegioes");
        try {
            juncao(v);
            bordas(v);
            divisao(v);
            contagens(v);
            dimensoes(v);
            ordemNaoImporta(v);
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    static void juncao(Verifica v) {
        v.igual(0, regioes(mundo(0)), "sem chunk: nenhuma regiao");
        v.igual(1, regioes(mundo(0, 0, 0)), "um chunk: uma regiao");
        v.igual(1, regioes(mundo(0, 0, 0, 1, 0)), "dois chunks vizinhos: uma regiao");
        // secoes de 16 chunks: chunk 0 = secao 0; chunk 48 = secao 3; chunk 64 = secao 4
        v.igual(1, regioes(mundo(0, 0, 0, 48, 0)), "secoes 0 e 3 (3 secoes de distancia): juntam");
        v.igual(2, regioes(mundo(0, 0, 0, 64, 0)), "secoes 0 e 4: separadas");
        v.igual(1, regioes(mundo(0, 0, 0, 48, 48)), "secoes (0,0) e (3,3): Chebyshev 3, juntam");
        v.igual(2, regioes(mundo(0, 0, 0, 48, 64)), "secoes (0,0) e (3,4): separadas");
        v.igual(1, regioes(mundo(0, 0, 0, 48, 0, 96, 0, 144, 0)), "cadeia 0,3,6,9: transitiva, uma regiao so");
        v.igual(2, regioes(mundo(0, 0, 0, 48, 0, 112, 0, 160, 0)), "0,3 e 7,10: duas regioes");
        v.igual(3, regioes(mundo(0, 0, 0), mundo(1, 0, 0), mundo(-1, 0, 0)), "mesma posicao em tres dimensoes: tres regioes");
        v.igual(2, regioes(mundo(0, 0, 0), mundo(1, 1, 1)), "dimensoes diferentes nunca juntam");
    }

    static void bordas(Verifica v) {
        // limites de secao: chunk 15 = secao 0, chunk 16 = secao 1, chunk -1 = secao -1, chunk -16 = secao -1, chunk -17 = secao -2
        Regionizador r = new Regionizador();
        Mapa m = r.calcular(new FotoMundo[] {mundo(0, 15, 0, 16, 0, -1, 0, -16, 0, -17, 0, 700, 0)}, null);
        v.igual(2, m.nRegioes, "chunks 15,16,-1,-16,-17 numa regiao e o 700 em outra");
        v.sim(m.regiaoNoChunk(0, 15, 0) == m.regiaoNoChunk(0, 0, 0) && m.regiaoNoChunk(0, 0, 0) >= 0,
                "chunk 15 e chunk 0 estao na mesma secao (a secao 0 tem chunk carregado)");
        v.igual(m.regiaoNoChunk(0, 16, 0), m.regiaoNoChunk(0, 31, 0), "chunks 16 e 31: mesma secao (1)");
        v.igual(m.regiaoNoChunk(0, -1, 0), m.regiaoNoChunk(0, -16, 0), "chunks -1 e -16: mesma secao (-1)");
        v.sim(m.regiaoNoChunk(0, -17, 0) >= 0, "chunk -17: secao -2 carregada");
        v.igual(-1, m.regiaoNoChunk(0, 100, 0), "chunk 100 (secao 6) sem chunk carregado nem vizinho na secao: sem regiao");
        v.igual(-1, m.regiaoNoChunk(5, 0, 0), "dimensao que o mapa nao conhece: sem regiao");
        // bloco: -1 >> 8 = -1, 255 >> 8 = 0, 256 >> 8 = 1
        Mapa b = r.calcular(new FotoMundo[] {mundo(0, 0, 0, 16, 0, -1, 0)}, null);
        v.igual(b.regiaoNoBloco(0, 255, 0), b.regiaoNoBloco(0, 0, 0), "bloco 255 e 0: secao 0");
        v.sim(b.regiaoNoBloco(0, 256, 0) >= 0 && b.regiaoNoBloco(0, -1, 0) >= 0, "bloco 256 (secao 1) e -1 (secao -1) tem regiao");
        v.igual(-1, b.regiaoNoBloco(0, 512, 0), "bloco 512 (secao 2) sem chunk: sem regiao");
        v.igual(-1, b.regiaoNoBloco(0, 0, 256), "bloco z 256 (secao z 1) sem chunk: sem regiao");
        // coordenadas muito grandes e negativas
        Mapa g = r.calcular(new FotoMundo[] {mundo(0, 1000000, -1000000, -1875000, 1875000)}, null);
        v.igual(2, g.nRegioes, "coordenadas de ate 30 milhoes de blocos: duas regioes");
        v.sim(g.regiaoNoBloco(0, 16000000, -16000000) >= 0 && g.regiaoNoBloco(0, -30000000, 30000000) >= 0, "e achadas pelo bloco");
        // regioesPerto
        Mapa p = r.calcular(new FotoMundo[] {mundo(0, 0, 0, 80, 0)}, null);
        int d = p.indiceDaDim(0);
        v.igual(1, p.regioesPerto(d, 1, 0, 3).length, "regioesPerto: secao 1 alcanca so a regiao da secao 0 (a da 5 esta a 4)");
        v.igual(2, p.regioesPerto(d, 2, 0, 3).length, "regioesPerto: secao 2 alcanca a da secao 0 (2) e a da 5 (3)");
        v.igual(0, p.regioesPerto(d, 20, 20, 3).length, "regioesPerto: longe de tudo: nenhuma");
        int[] cx = p.caixaEmBlocos(p.regiaoNoChunk(0, 0, 0));
        v.sim(cx[0] == 0 && cx[1] == 0 && cx[2] == 255 && cx[3] == 255, "caixa em blocos da secao 0: 0..255");
    }

    static void divisao(Verifica v) {
        FotoMundo ponte = mundo(0, 0, 0, 48, 0, 96, 0);
        Regionizador r = new Regionizador();
        v.igual(1, r.calcular(new FotoMundo[] {ponte}, null).nRegioes, "com a secao da ponte (3): uma regiao");
        FotoMundo semPonte = mundo(0, 0, 0, 96, 0);
        Mapa m = r.calcular(new FotoMundo[] {semPonte}, null);
        v.igual(2, m.nRegioes, "descarregou a ponte: divide em duas");
        v.sim(m.regiaoNoChunk(0, 0, 0) != m.regiaoNoChunk(0, 96, 0), "e as duas pontas estao em regioes diferentes");
        v.igual(1, r.calcular(new FotoMundo[] {ponte}, null).nRegioes, "carregou a ponte de novo: junta");
    }

    static void contagens(Verifica v) {
        FotoMundo f = mundo(0, 0, 0, 1, 0, 2, 2, 48, 0);
        f.tile(5, 5).tile(780, 20).tile(30000, 30000); // o ultimo esta numa secao sem chunk
        f.entidade(10, 10).entidade(-5000, -5000);
        Mapa m = new Regionizador().calcular(new FotoMundo[] {f}, null);
        v.igual(1, m.nRegioes, "uma regiao");
        v.igual(4, m.chunks[0], "4 chunks");
        v.igual(2, m.tiles[0], "2 tiles contadas (a tile numa secao sem chunk fica fora)");
        v.igual(1, m.ents[0], "1 entidade contada");
        v.igual(2, m.nSecoes[0], "2 secoes (0 e 3)");
        v.sim(m.secX0[0] == 0 && m.secX1[0] == 3 && m.secZ0[0] == 0 && m.secZ1[0] == 0, "caixa em secoes 0..3 x 0..0");
        v.igual(0, m.dim[0], "dimensao 0");
    }

    static void dimensoes(Verifica v) {
        // sempre juntas: duas secoes bem separadas viram uma regiao
        FotoMundo a = mundo(7, 0, 0, 500, 500);
        FotoMundo b = mundo(0, 0, 0, 500, 500);
        Regionizador r = new Regionizador();
        Mapa m = r.calcular(new FotoMundo[] {a, b}, new int[] {7});
        v.igual(3, m.nRegioes, "dim 7 sempre junta: 1 regiao nela + 2 na dim 0");
        v.igual(m.regiaoNoChunk(7, 0, 0), m.regiaoNoChunk(7, 500, 500), "dim 7: as duas pontas na mesma regiao");
        v.sim(m.regiaoNoChunk(0, 0, 0) != m.regiaoNoChunk(0, 500, 500), "dim 0: continuam separadas");
        v.igual(4, new Regionizador().calcular(new FotoMundo[] {a, b}, new int[] {99}).nRegioes,
                "sempre junto de dimensao que nao existe: nao muda nada (4 regioes)");
        // mais de 256 dimensoes: as de fora do indice ficam sem regiao e sao contadas
        FotoMundo[] muitas = new FotoMundo[300];
        for (int i = 0; i < 300; i++) {
            muitas[i] = mundo(1000 + i, 0, 0);
        }
        Regionizador rr = new Regionizador();
        Mapa mm = rr.calcular(muitas, null);
        v.igual(256, mm.nRegioes, "300 dimensoes: so 256 cabem no indice");
        v.igual(44, rr.dimensoesPerdidas, "44 dimensoes ficaram sem regiao (contadas)");
        v.igual(-1, mm.regiaoNoChunk(1299, 0, 0), "dimensao que nao coube: sem regiao (nunca cortada)");
        v.sim(mm.regiaoNoChunk(1000, 0, 0) >= 0, "as primeiras 256 tem regiao");
        // o indice persiste entre calculos: a ordem das dimensoes na foto nao muda a identidade
        Regionizador p = new Regionizador();
        Mapa m1 = p.calcular(new FotoMundo[] {mundo(0, 0, 0), mundo(5, 0, 0)}, null);
        Mapa m2 = p.calcular(new FotoMundo[] {mundo(5, 0, 0), mundo(0, 0, 0)}, null);
        v.igual(m1.indiceDaDim(5), m2.indiceDaDim(5), "indice da dimensao 5 igual nos dois calculos");
        v.igual(m1.indiceDaDim(0), m2.indiceDaDim(0), "indice da dimensao 0 igual nos dois calculos");
    }

    /** A particao nao depende da ordem dos chunks na foto. */
    static void ordemNaoImporta(Verifica v) {
        Random rnd = new Random(42);
        for (int rodada = 0; rodada < 30; rodada++) {
            List<int[]> cs = new ArrayList<int[]>();
            int n = 5 + rnd.nextInt(60);
            for (int i = 0; i < n; i++) {
                cs.add(new int[] {rnd.nextInt(400) - 200, rnd.nextInt(400) - 200});
            }
            Mapa a = calc(cs);
            Collections.shuffle(cs, rnd);
            Mapa b = calc(cs);
            boolean igual = a.nRegioes == b.nRegioes;
            for (int i = 0; igual && i < cs.size(); i++) {
                for (int j = i + 1; j < cs.size() && j < i + 8; j++) {
                    boolean sa = a.regiaoNoChunk(0, cs.get(i)[0], cs.get(i)[1]) == a.regiaoNoChunk(0, cs.get(j)[0], cs.get(j)[1]);
                    boolean sb = b.regiaoNoChunk(0, cs.get(i)[0], cs.get(i)[1]) == b.regiaoNoChunk(0, cs.get(j)[0], cs.get(j)[1]);
                    if (sa != sb) {
                        igual = false;
                    }
                }
            }
            v.sim(igual, "rodada " + rodada + ": mesma particao em outra ordem");
        }
    }

    private static Mapa calc(List<int[]> cs) {
        FotoMundo f = new FotoMundo(0);
        for (int[] c : cs) {
            f.chunk(c[0], c[1]);
        }
        return new Regionizador().calcular(new FotoMundo[] {f}, null);
    }
}
