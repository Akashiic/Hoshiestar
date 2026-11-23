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

import java.util.ArrayList;
import java.util.List;

/**
 * Foto das regioes num instante (tira-se a cada 30 s, na fase serial do fim do tick): quais secoes (16x16 chunks) tem
 * chunk carregado, a que regiao cada uma pertence pela regra de juncao, e o resumo por dimensao (tabela T5). So a thread do
 * servidor cria e le a foto viva; o resumo ({@link Resumo}) e imutavel e vai para o relatorio.
 */
final class Foto {

    /** Regra do estudo Folia x NeoFolia: secoes com chunk a ate 3 secoes (Chebyshev) viram uma regiao. */
    static final int RAIO_JUNCAO = 3;
    /** Distancia maxima (em secoes) procurada entre regioes; alem disso o relatorio diz "mais de 12". */
    static final int RAIO_DISTANCIA = 12;
    /** Quantos pares de regioes proximas, no maximo, o resumo guarda por dimensao. */
    static final int MAX_PARES = 10;
    /** Quantas regioes, no maximo, o resumo detalha por dimensao (as de mais carga). */
    static final int MAX_REGIOES_DETALHADAS = 30;

    final MapaSecoes mapa;
    /** Regiao de cada vaga do mapa (0..nRegioes-1). */
    final int[] regiaoDaVaga;
    final int nRegioes;
    final Resumo resumo;

    Foto(MapaSecoes mapa, int[] regiaoDaVaga, int nRegioes, Resumo resumo) {
        this.mapa = mapa;
        this.regiaoDaVaga = regiaoDaVaga;
        this.nRegioes = nRegioes;
        this.resumo = resumo;
    }

    /** A regiao da secao, ou -1 se a secao nao tem chunk carregado na foto. */
    int regiaoDe(int dimIdx, int secX, int secZ) {
        int v = mapa.vaga(MapaSecoes.chave(dimIdx, secX, secZ));
        return v < 0 ? -1 : regiaoDaVaga[v];
    }

    static final class Regiao {
        final int secoes;
        final int chunks;
        final int tiles;
        final int ents;
        final int secXMin, secXMax, secZMin, secZMax;

        Regiao(int secoes, int chunks, int tiles, int ents, int x0, int x1, int z0, int z1) {
            this.secoes = secoes;
            this.chunks = chunks;
            this.tiles = tiles;
            this.ents = ents;
            this.secXMin = x0;
            this.secXMax = x1;
            this.secZMin = z0;
            this.secZMax = z1;
        }
    }

    static final class Par {
        final int a;
        final int b;
        /** Distancia em secoes (Chebyshev), de 4 ate {@link #RAIO_DISTANCIA}. */
        final int secoes;

        Par(int a, int b, int secoes) {
            this.a = a;
            this.b = b;
            this.secoes = secoes;
        }

        /** Menor distancia possivel em chunks entre as duas regioes (conta: (d-1)*16+1). */
        int chunksMin() {
            return (secoes - 1) * 16 + 1;
        }

        /** Maior das menores distancias em chunks (conta: (d+1)*16-1). */
        int chunksMax() {
            return (secoes + 1) * 16 - 1;
        }
    }

    /** Resumo de uma dimensao na foto (tabela T5). */
    static final class ResumoDim {
        final int dim;
        final int regioes;
        final int secoes;
        final int chunks;
        final int tiles;
        final int ents;
        final int tilesForaDeSecao;
        final int entsForaDeSecao;
        final int maxTilesNumaSecao;
        final int maxEntsNumaSecao;
        final int secoesComCarga;
        final int regioesComCarga;
        /** Regioes de mais carga (tiles + entidades), da maior para a menor. */
        final List<Regiao> maiores;
        /** Pares de regioes COM carga, do mais proximo para o mais longe (so os {@link #MAX_PARES} primeiros). */
        final List<Par> pares;
        /** Menor distancia em secoes entre duas regioes com carga (0 = nao ha par a ate 12 secoes). */
        final int menorDistSecoes;

        ResumoDim(int dim, int regioes, int secoes, int chunks, int tiles, int ents, int tilesFora, int entsFora,
                  int maxTiles, int maxEnts, int secoesComCarga, int regioesComCarga, List<Regiao> maiores,
                  List<Par> pares, int menorDist) {
            this.dim = dim;
            this.regioes = regioes;
            this.secoes = secoes;
            this.chunks = chunks;
            this.tiles = tiles;
            this.ents = ents;
            this.tilesForaDeSecao = tilesFora;
            this.entsForaDeSecao = entsFora;
            this.maxTilesNumaSecao = maxTiles;
            this.maxEntsNumaSecao = maxEnts;
            this.secoesComCarga = secoesComCarga;
            this.regioesComCarga = regioesComCarga;
            this.maiores = maiores;
            this.pares = pares;
            this.menorDistSecoes = menorDist;
        }
    }

    static final class Resumo {
        /** Segundos desde o inicio da observacao. */
        final long segundos;
        final long ticks;
        final List<ResumoDim> dims;
        /** Quanto custou calcular a foto (ns). */
        final long custoNs;

        Resumo(long segundos, long ticks, List<ResumoDim> dims, long custoNs) {
            this.segundos = segundos;
            this.ticks = ticks;
            this.dims = dims;
            this.custoNs = custoNs;
        }
    }

    static List<Regiao> lista() {
        return new ArrayList<Regiao>();
    }
}
