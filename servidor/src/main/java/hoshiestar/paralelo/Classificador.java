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

import java.util.IdentityHashMap;

/**
 * A classificacao das regioes de um tick (plano do Degrau B, secao 6): quais regioes do mapa do orcamento podem ter os
 * tiles ticados na fase. Funcao pura sobre uma {@link Visao} (o jogo de verdade entrega a sua em {@code GanchosParalelo};
 * os testes entregam uma falsa). So le; nao escreve em nada do jogo.
 *
 * <p>Uma regiao e <b>elegivel</b> quando tem pelo menos um tile valido e TODOS os tiles validos dela passam:
 * <ul>
 * <li>G3: classe exata liberada (mesa, bau/bau-armadilha, bau de ender, conforme {@code paralelo.classes} e as travas);</li>
 * <li>G4: nenhum tile liberado aparece duas vezes na lista;</li>
 * <li>G1: os 3x3 chunks em volta do tile estao carregados e sao da mesma regiao no mapa;</li>
 * <li>condicoes da classe: bau e bau de ender fechados (ninguem usando, tampa em 0); bau com cada vizinho bau tendo tile
 * valido (o tick do bau nao pode criar tile, achado A1 do passo 0);</li>
 * <li>G2: a regiao nao esta em corte nem suspensa.</li>
 * </ul>
 * Tile invalido esquecido na lista nao conta e nunca entra em lote (G3/G4 so olham tiles validos). Tile sem regiao (secao
 * sem chunk no mapa) roda em serie e nao muda a regiao de ninguem. Tile que o firewall barra nao entra no lote (nao tica,
 * como no original), e a regiao continua elegivel. O primeiro motivo de cada regiao fica guardado, com a posicao.
 *
 * <p>Uma instancia so, usada so pela thread do servidor (os vetores sao reaproveitados entre ticks).
 */
public final class Classificador {

    /** Tipo do tile pela classe exata. */
    public static final int OUTRO = 0, MESA = 1, BAU = 2, BAU_ENDER = 3;
    public static final int N_TIPOS = 4;

    /** Motivos (0 = elegivel). */
    public static final int ELEGIVEL = 0, G1_CHUNK = 1, G1_REGIAO = 2, G2_CORTE = 3, G3_CLASSE = 4, G4_REPETIDO = 5,
            BAU_ABERTO = 6, BAU_VIZINHO = 7, ENDER_ABERTO = 8, PORTAO = 9;
    static final String[] TEXTO = {
        "elegivel",
        "G1: chunk nao carregado nos 3x3 em volta de um tile",
        "G1: chunk de outra regiao (ou sem regiao) nos 3x3 em volta de um tile",
        "G2: regiao em corte ou suspensa",
        "G3: tile de classe nao liberada",
        "G4: tile repetido na lista de tiles",
        "bau aberto, fechando ou com alguem usando",
        "bau com vizinho bau sem tile valido (o tick criaria tile)",
        "bau de ender aberto, fechando ou com alguem usando",
        "portao da dimensao",
    };

    public static String texto(int motivo) {
        return motivo >= 0 && motivo < TEXTO.length ? TEXTO[motivo] : "motivo " + motivo;
    }

    /** O que a classificacao pergunta ao jogo. Indices de tile de 0 a {@link #tamanho()} - 1. So na thread do servidor. */
    public interface Visao {
        int tamanho();

        /** O objeto do tile (para identidade, G4); null = posicao vazia na lista (pulada). */
        Object tile(int i);

        /** Valido: nao invalidado e com mundo. NUNCA chama hasWorldObj (o AM2 troca esse metodo e escreve num mapa). */
        boolean valido(int i);

        /** {@link #MESA}, {@link #BAU}, {@link #BAU_ENDER} pela classe exata; {@link #OUTRO} para o resto. */
        int tipo(int i);

        /** Nome da classe (so para o motivo). */
        String classe(int i);

        int x(int i);

        int y(int i);

        int z(int i);

        /** Bau ou bau de ender: ninguem usando e tampa parada em 0. */
        boolean fechado(int i);

        /** Bau: cada vizinho bau (4 lados) tem tile valido. */
        boolean vizinhosComTile(int i);

        /** O firewall deixa este tile ticar? (falso = nao entra no lote, como no original). */
        boolean firewallDeixa(int i);

        boolean chunkCarregado(int cx, int cz);

        /** Regiao do chunk no mapa do orcamento (indice), ou -1 sem regiao. */
        int regiaoDoChunk(int cx, int cz);

        /** Numero de regioes do mapa. */
        int nRegioes();

        /** G2: a regiao nao esta em corte nem suspensa. */
        boolean regiaoLivre(int r);
    }

    // ---- resultado do ultimo classificar (vetores por regiao, reaproveitados)
    private int nRegioes;
    private int[] motivo = new int[0];
    private int[] exX = new int[0], exY = new int[0], exZ = new int[0];
    private String[] detalhe = new String[0];
    private boolean[] temTile = new boolean[0];
    private int[] tilesNaRegiao = new int[0];
    private int regioesComTile, elegiveis, tilesValidos, tilesNaFase, tilesSemRegiao, invalidosIgnorados, barradosFirewall;
    private String portao;
    private final IdentityHashMap<Object, Boolean> vistos = new IdentityHashMap<Object, Boolean>();

    /**
     * Classifica. {@code liberado[tipo]} diz quais tipos podem entrar (indice {@link #OUTRO} e ignorado). {@code portao}
     * nao nulo = um portao da dimensao esta fechado (aquecimento, sinal...): nenhuma regiao elegivel, todas com esse motivo.
     */
    public void classificar(Visao v, boolean[] liberado, String portao) {
        int nr = Math.max(0, v.nRegioes());
        preparar(nr);
        this.portao = portao;
        vistos.clear();
        int n = v.tamanho();
        // passo 1: G3, G4 e condicoes da classe, por tile valido
        for (int i = 0; i < n; i++) {
            Object t = v.tile(i);
            if (t == null) {
                continue;
            }
            if (!v.valido(i)) {
                invalidosIgnorados++;
                continue;
            }
            tilesValidos++;
            int x = v.x(i), y = v.y(i), z = v.z(i);
            int r = v.regiaoDoChunk(x >> 4, z >> 4);
            if (r < 0 || r >= nr) {
                tilesSemRegiao++;
                continue;
            }
            if (!temTile[r]) {
                temTile[r] = true;
                regioesComTile++;
            }
            tilesNaRegiao[r]++;
            if (motivo[r] != ELEGIVEL) {
                continue; // a regiao ja caiu; o primeiro motivo fica
            }
            if (portao != null) {
                marcar(r, PORTAO, x, y, z, portao);
                continue;
            }
            int tipo = v.tipo(i);
            if (tipo <= OUTRO || tipo >= N_TIPOS || !liberado[tipo]) {
                marcar(r, G3_CLASSE, x, y, z, v.classe(i));
                continue;
            }
            if (vistos.put(t, Boolean.TRUE) != null) {
                marcar(r, G4_REPETIDO, x, y, z, v.classe(i));
                continue;
            }
            if (tipo == BAU && !v.fechado(i)) {
                marcar(r, BAU_ABERTO, x, y, z, null);
                continue;
            }
            if (tipo == BAU && !v.vizinhosComTile(i)) {
                marcar(r, BAU_VIZINHO, x, y, z, null);
                continue;
            }
            if (tipo == BAU_ENDER && !v.fechado(i)) {
                marcar(r, ENDER_ABERTO, x, y, z, null);
                continue;
            }
            int m = g1(v, x >> 4, z >> 4, r);
            if (m != ELEGIVEL) {
                marcar(r, m, x, y, z, null);
            }
        }
        // passo 2: G2 por regiao; contagens
        for (int r = 0; r < nr; r++) {
            if (!temTile[r]) {
                continue;
            }
            if (motivo[r] == ELEGIVEL && !v.regiaoLivre(r)) {
                marcar(r, G2_CORTE, 0, 0, 0, null);
            }
            if (motivo[r] == ELEGIVEL) {
                elegiveis++;
            }
        }
        // passo 3: tiles que entram (validos, de regiao elegivel, que o firewall deixa)
        if (elegiveis > 0) {
            for (int i = 0; i < n; i++) {
                if (v.tile(i) == null || !v.valido(i)) {
                    continue;
                }
                int r = v.regiaoDoChunk(v.x(i) >> 4, v.z(i) >> 4);
                if (r >= 0 && r < nr && motivo[r] == ELEGIVEL && temTile[r]) {
                    if (v.firewallDeixa(i)) {
                        tilesNaFase++;
                    } else {
                        barradosFirewall++;
                    }
                }
            }
        }
    }

    private static int g1(Visao v, int cx, int cz, int r) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!v.chunkCarregado(cx + dx, cz + dz)) {
                    return G1_CHUNK;
                }
                if (v.regiaoDoChunk(cx + dx, cz + dz) != r) {
                    return G1_REGIAO;
                }
            }
        }
        return ELEGIVEL;
    }

    private void marcar(int r, int m, int x, int y, int z, String d) {
        motivo[r] = m;
        exX[r] = x;
        exY[r] = y;
        exZ[r] = z;
        detalhe[r] = d;
    }

    private void preparar(int nr) {
        if (motivo.length < nr) {
            int c = Math.max(nr, motivo.length * 2);
            motivo = new int[c];
            exX = new int[c];
            exY = new int[c];
            exZ = new int[c];
            detalhe = new String[c];
            temTile = new boolean[c];
            tilesNaRegiao = new int[c];
        } else {
            for (int r = 0; r < nr; r++) {
                motivo[r] = ELEGIVEL;
                detalhe[r] = null;
                temTile[r] = false;
                tilesNaRegiao[r] = 0;
            }
        }
        nRegioes = nr;
        regioesComTile = 0;
        elegiveis = 0;
        tilesValidos = 0;
        tilesNaFase = 0;
        tilesSemRegiao = 0;
        invalidosIgnorados = 0;
        barradosFirewall = 0;
    }

    // ---- leitura do resultado

    /** A regiao entra na fase neste tick? (com tile e sem motivo). Indice fora do mapa: falso. */
    public boolean elegivel(int r) {
        return r >= 0 && r < nRegioes && temTile[r] && motivo[r] == ELEGIVEL;
    }

    /** O primeiro motivo da regiao ({@link #ELEGIVEL} = entra). */
    public int motivo(int r) {
        return r >= 0 && r < nRegioes ? motivo[r] : ELEGIVEL;
    }

    public boolean temTile(int r) {
        return r >= 0 && r < nRegioes && temTile[r];
    }

    public String detalhe(int r) {
        return r >= 0 && r < nRegioes ? detalhe[r] : null;
    }

    public int[] exemplo(int r) {
        return new int[] {exX[r], exY[r], exZ[r]};
    }

    public int nRegioes() {
        return nRegioes;
    }

    public int regioesComTile() {
        return regioesComTile;
    }

    public int elegiveis() {
        return elegiveis;
    }

    public int tilesValidos() {
        return tilesValidos;
    }

    public int tilesNaFase() {
        return tilesNaFase;
    }

    public int tilesSemRegiao() {
        return tilesSemRegiao;
    }

    public int invalidosIgnorados() {
        return invalidosIgnorados;
    }

    public int barradosFirewall() {
        return barradosFirewall;
    }

    public String portao() {
        return portao;
    }
}
