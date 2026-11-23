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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Regra de juncao das regioes, calculada sobre a foto (fase serial). Unidade = secao de 16x16 chunks. Duas secoes com
 * chunk carregado, na mesma dimensao, a ate {@link Foto#RAIO_JUNCAO} secoes uma da outra (Chebyshev) ficam na mesma
 * regiao; a juncao e transitiva (cadeia de secoes vira uma regiao so). So le o que o jogo entregou; nao escreve em nada.
 *
 * <p>As coordenadas das secoes ficam na {@link Foto}; para outra regra (secao 8x8, outro raio) basta chamar de novo
 * com outros parametros, a partir das mesmas {@link FotoMundo}.
 */
final class Regioes {

    private Regioes() {
    }

    static Foto calcular(FotoMundo[] mundos, int[] dimIdx, long segundos, long ticks, long custoNs) {
        return calcular(mundos, dimIdx, Foto.RAIO_JUNCAO, 4, segundos, ticks, custoNs);
    }

    /**
     * @param sec    expoente da secao em chunks: 4 = 16x16 chunks (o padrao); 3 = 8x8, para o relatorio recalcular.
     * @param dimIdx indice (0..255) de cada mundo, na ordem de {@code mundos}
     */
    static Foto calcular(FotoMundo[] mundos, int[] dimIdx, int raio, int sec, long segundos, long ticks, long custoNs) {
        int totalChunks = 0;
        for (FotoMundo m : mundos) {
            totalChunks += m.nChunks;
        }
        MapaSecoes mapa = new MapaSecoes(Math.max(16, Math.min(totalChunks, 1 << 16)));
        int[] tilesFora = new int[mundos.length];
        int[] entsFora = new int[mundos.length];
        for (int k = 0; k < mundos.length; k++) {
            FotoMundo m = mundos[k];
            int d = dimIdx[k];
            for (int i = 0; i < m.nChunks; i++) {
                int v = mapa.criar(MapaSecoes.chave(d, m.chunksX[i] >> sec, m.chunksZ[i] >> sec));
                mapa.chunks[v]++;
            }
            // bloco -> secao: 4 bits de bloco->chunk mais o expoente da secao
            int s = sec + 4;
            for (int i = 0; i < m.nTiles; i++) {
                int v = mapa.vaga(MapaSecoes.chave(d, m.tilesX[i] >> s, m.tilesZ[i] >> s));
                if (v >= 0) {
                    mapa.tiles[v]++;
                } else {
                    tilesFora[k]++;
                }
            }
            for (int i = 0; i < m.nEnt; i++) {
                int v = mapa.vaga(MapaSecoes.chave(d, m.entX[i] >> s, m.entZ[i] >> s));
                if (v >= 0) {
                    mapa.ents[v]++;
                } else {
                    entsFora[k]++;
                }
            }
        }
        int n = mapa.tamanho();
        // uniao-busca sobre as vagas
        int[] pai = new int[n];
        for (int i = 0; i < n; i++) {
            pai[i] = i;
        }
        for (int v = 0; v < n; v++) {
            long c = mapa.chaveDaVaga(v);
            int d = MapaSecoes.dimDe(c);
            int x = MapaSecoes.xDe(c);
            int z = MapaSecoes.zDe(c);
            for (int dx = -raio; dx <= raio; dx++) {
                for (int dz = -raio; dz <= raio; dz++) {
                    if (dx < 0 || (dx == 0 && dz <= 0)) {
                        continue; // cada par uma vez so
                    }
                    int w = mapa.vaga(MapaSecoes.chave(d, x + dx, z + dz));
                    if (w >= 0) {
                        unir(pai, v, w);
                    }
                }
            }
        }
        // numera as regioes
        int[] reg = new int[n];
        int[] idDaRaiz = new int[n];
        java.util.Arrays.fill(idDaRaiz, -1);
        int nReg = 0;
        for (int v = 0; v < n; v++) {
            int r = achar(pai, v);
            if (idDaRaiz[r] < 0) {
                idDaRaiz[r] = nReg++;
            }
            reg[v] = idDaRaiz[r];
        }
        // soma por regiao
        int[] rSec = new int[nReg], rChunks = new int[nReg], rTiles = new int[nReg], rEnts = new int[nReg];
        int[] rX0 = new int[nReg], rX1 = new int[nReg], rZ0 = new int[nReg], rZ1 = new int[nReg];
        int[] rDim = new int[nReg];
        java.util.Arrays.fill(rX0, Integer.MAX_VALUE);
        java.util.Arrays.fill(rZ0, Integer.MAX_VALUE);
        java.util.Arrays.fill(rX1, Integer.MIN_VALUE);
        java.util.Arrays.fill(rZ1, Integer.MIN_VALUE);
        for (int v = 0; v < n; v++) {
            int r = reg[v];
            long c = mapa.chaveDaVaga(v);
            int x = MapaSecoes.xDe(c);
            int z = MapaSecoes.zDe(c);
            rDim[r] = MapaSecoes.dimDe(c);
            rSec[r]++;
            rChunks[r] += mapa.chunks[v];
            rTiles[r] += mapa.tiles[v];
            rEnts[r] += mapa.ents[v];
            rX0[r] = Math.min(rX0[r], x);
            rX1[r] = Math.max(rX1[r], x);
            rZ0[r] = Math.min(rZ0[r], z);
            rZ1[r] = Math.max(rZ1[r], z);
        }
        // menor distancia entre regioes com carga
        Map<Long, Integer> pares = new HashMap<Long, Integer>();
        for (int v = 0; v < n; v++) {
            int a = reg[v];
            if (rTiles[a] + rEnts[a] == 0) {
                continue;
            }
            long c = mapa.chaveDaVaga(v);
            int d = MapaSecoes.dimDe(c);
            int x = MapaSecoes.xDe(c);
            int z = MapaSecoes.zDe(c);
            for (int r = raio + 1; r <= Foto.RAIO_DISTANCIA; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    boolean borda = dx == -r || dx == r;
                    for (int dz = -r; dz <= r; dz += borda ? 1 : 2 * r) {
                        int w = mapa.vaga(MapaSecoes.chave(d, x + dx, z + dz));
                        if (w < 0) {
                            continue;
                        }
                        int b = reg[w];
                        if (b == a || rTiles[b] + rEnts[b] == 0) {
                            continue;
                        }
                        long k = ((long) Math.min(a, b) << 32) | Math.max(a, b);
                        Integer ant = pares.get(k);
                        if (ant == null || r < ant) {
                            pares.put(k, r);
                        }
                    }
                }
            }
        }
        // resumo por dimensao
        List<Foto.ResumoDim> dims = new ArrayList<Foto.ResumoDim>();
        for (int k = 0; k < mundos.length; k++) {
            int d = dimIdx[k];
            int regs = 0, secs = 0, chunks = 0, tiles = 0, ents = 0, maxT = 0, maxE = 0, secCarga = 0, regCarga = 0;
            List<Integer> daDim = new ArrayList<Integer>();
            for (int r = 0; r < nReg; r++) {
                if (rDim[r] == d) {
                    regs++;
                    secs += rSec[r];
                    chunks += rChunks[r];
                    tiles += rTiles[r];
                    ents += rEnts[r];
                    if (rTiles[r] + rEnts[r] > 0) {
                        regCarga++;
                    }
                    daDim.add(r);
                }
            }
            for (int v = 0; v < n; v++) {
                if (MapaSecoes.dimDe(mapa.chaveDaVaga(v)) == d) {
                    maxT = Math.max(maxT, mapa.tiles[v]);
                    maxE = Math.max(maxE, mapa.ents[v]);
                    if (mapa.tiles[v] + mapa.ents[v] > 0) {
                        secCarga++;
                    }
                }
            }
            final int[] fT = rTiles, fE = rEnts;
            Collections.sort(daDim, new Comparator<Integer>() {
                @Override
                public int compare(Integer p, Integer q) {
                    int c = Integer.compare(fT[q] + fE[q], fT[p] + fE[p]);
                    return c != 0 ? c : Integer.compare(p, q);
                }
            });
            List<Foto.Regiao> maiores = new ArrayList<Foto.Regiao>();
            for (int i = 0; i < daDim.size() && i < Foto.MAX_REGIOES_DETALHADAS; i++) {
                int r = daDim.get(i);
                maiores.add(new Foto.Regiao(rSec[r], rChunks[r], rTiles[r], rEnts[r], rX0[r], rX1[r], rZ0[r], rZ1[r]));
            }
            List<Foto.Par> ps = new ArrayList<Foto.Par>();
            for (Map.Entry<Long, Integer> e : pares.entrySet()) {
                int a = (int) (e.getKey() >> 32);
                int b = (int) (long) e.getKey();
                if (rDim[a] == d) {
                    ps.add(new Foto.Par(a, b, e.getValue()));
                }
            }
            Collections.sort(ps, new Comparator<Foto.Par>() {
                @Override
                public int compare(Foto.Par p, Foto.Par q) {
                    int c = Integer.compare(p.secoes, q.secoes);
                    if (c != 0) {
                        return c;
                    }
                    c = Integer.compare(p.a, q.a);
                    return c != 0 ? c : Integer.compare(p.b, q.b);
                }
            });
            int menor = ps.isEmpty() ? 0 : ps.get(0).secoes;
            if (ps.size() > Foto.MAX_PARES) {
                ps = new ArrayList<Foto.Par>(ps.subList(0, Foto.MAX_PARES));
            }
            dims.add(new Foto.ResumoDim(mundos[k].dim, regs, secs, chunks, tiles, ents, tilesFora[k], entsFora[k],
                    maxT, maxE, secCarga, regCarga, maiores, ps, menor));
        }
        return new Foto(mapa, reg, nReg, new Foto.Resumo(segundos, ticks, dims, custoNs));
    }

    private static int achar(int[] pai, int v) {
        while (pai[v] != v) {
            pai[v] = pai[pai[v]];
            v = pai[v];
        }
        return v;
    }

    private static void unir(int[] pai, int a, int b) {
        int ra = achar(pai, a);
        int rb = achar(pai, b);
        if (ra != rb) {
            pai[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }
}
