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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Os numeros dos modos sombra e sombra_ordenada para o comando {@code hoshie paralelo estado|motivos}. A thread do servidor soma
 * ({@link #fimDoTick}) e publica uma {@link Foto} imutavel a cada 20 ticks; o comando (que pode rodar na thread do RCON) so
 * le a foto publicada. Sem Minecraft.
 */
public final class Painel {

    /** O painel do servidor (um so). */
    public static final Painel ATUAL = new Painel();

    /** O que o comando mostra. Imutavel. */
    public static final class Foto {
        public final long tick, ticksClassificados;
        public final String modoEfetivo;
        public final int regioesComTile, elegiveis, tilesValidos, tilesNaFase, tilesSemRegiao, invalidos, barradosFirewall, sujasTick;
        public final double msClassMedia, msClassMax;
        public final long tilesNaFaseTotal, invariantesQuebrados, contextosDescartados, errosNossos, lotesSujos;
        /** Passo 4 (sombra_ordenada). */
        public final int lotesTick, tilesLotesTick;
        public final long ticksComLotes, lotesTotal, tilesLotesTotal, puladosTotal, adiadosAplicadosTotal, itensEmSerieTotal,
                lotesEmSerieTotal, lotesSujosFase, barradosNoDespacho, falhasTotal, invariantesFase, emergencias, lotesDescartados;
        public final double msLotesMedia, msJuncaoMedia, msFaseMedia;
        /** Motivos de inelegibilidade, mais comuns primeiro: {texto, contagem, x, y, z}. */
        public final List<Object[]> motivos;

        Foto(Painel p, List<Object[]> motivos) {
            tick = p.tick;
            ticksClassificados = p.ticksClassificados;
            modoEfetivo = p.modoEfetivo;
            regioesComTile = p.regioesComTile;
            elegiveis = p.elegiveis;
            tilesValidos = p.tilesValidos;
            tilesNaFase = p.tilesNaFase;
            tilesSemRegiao = p.tilesSemRegiao;
            invalidos = p.invalidos;
            barradosFirewall = p.barradosFirewall;
            sujasTick = p.sujasTick;
            msClassMedia = p.ticksClassificados == 0 ? 0 : p.nsClassTotal / 1e6 / p.ticksClassificados;
            msClassMax = p.nsClassMax / 1e6;
            tilesNaFaseTotal = p.tilesNaFaseTotal;
            invariantesQuebrados = p.invariantesQuebrados;
            contextosDescartados = p.contextosDescartados;
            errosNossos = p.errosNossos;
            lotesSujos = Registro.lotesSujos;
            lotesTick = p.lotesTick;
            tilesLotesTick = p.tilesLotesTick;
            ticksComLotes = p.ticksComLotes;
            lotesTotal = p.lotesTotal;
            tilesLotesTotal = p.tilesLotesTotal;
            puladosTotal = p.puladosTotal;
            adiadosAplicadosTotal = p.adiadosAplicadosTotal;
            itensEmSerieTotal = p.itensEmSerieTotal;
            lotesEmSerieTotal = p.lotesEmSerieTotal;
            lotesSujosFase = p.lotesSujosFase;
            barradosNoDespacho = p.barradosNoDespacho;
            falhasTotal = p.falhasTotal;
            invariantesFase = p.invariantesFase;
            emergencias = p.emergencias;
            lotesDescartados = p.lotesDescartados;
            msLotesMedia = p.ticksComLotes == 0 ? 0 : p.nsLotesTotal / 1e6 / p.ticksComLotes;
            msJuncaoMedia = p.ticksComLotes == 0 ? 0 : p.nsJuncaoTotal / 1e6 / p.ticksComLotes;
            msFaseMedia = p.ticksComLotes == 0 ? 0 : p.nsFaseTotal / 1e6 / p.ticksComLotes;
            this.motivos = motivos;
        }
    }

    // ---- so a thread do servidor
    long tick, ticksClassificados, nsClassTotal, nsClassMax;
    String modoEfetivo = "nenhum (fase ainda nao rodou)";
    int regioesComTile, elegiveis, tilesValidos, tilesNaFase, tilesSemRegiao, invalidos, barradosFirewall, sujasTick;
    long tilesNaFaseTotal, invariantesQuebrados, contextosDescartados, errosNossos;
    // passo 4 (sombra_ordenada): escritos por OrdenadaJogo, so a thread do servidor
    int lotesTick, tilesLotesTick;
    long ticksComLotes, lotesTotal, tilesLotesTotal, puladosTotal, adiadosAplicadosTotal, itensEmSerieTotal, lotesEmSerieTotal,
            lotesSujosFase, barradosNoDespacho, falhasTotal, invariantesFase, emergencias, lotesDescartados, nsLotesTotal,
            nsJuncaoTotal, nsFaseTotal;
    private final Map<String, long[]> motivos = new HashMap<String, long[]>();

    private volatile Foto publicada;

    /** Uma regiao inelegivel neste tick (texto do motivo, posicao de exemplo; nunca nome de jogador). */
    void motivo(String texto, int x, int y, int z) {
        long[] m = motivos.get(texto);
        if (m == null) {
            if (motivos.size() >= 500) {
                texto = "(outros motivos: mais de 500 textos diferentes)";
                m = motivos.get(texto);
            }
            if (m == null) {
                m = new long[] {0, x, y, z};
                motivos.put(texto, m);
            }
        }
        m[0]++;
        m[1] = x; // a posicao de exemplo e a ultima vista (a primeira podia ja ter sido resolvida)
        m[2] = y;
        m[3] = z;
    }

    void fimDoTick(Classificador c, long ns) {
        ticksClassificados++;
        nsClassTotal += ns;
        if (ns > nsClassMax) {
            nsClassMax = ns;
        }
        regioesComTile = c.regioesComTile();
        elegiveis = c.elegiveis();
        tilesValidos = c.tilesValidos();
        tilesNaFase = c.tilesNaFase();
        tilesSemRegiao = c.tilesSemRegiao();
        invalidos = c.invalidosIgnorados();
        barradosFirewall = c.barradosFirewall();
        tilesNaFaseTotal += c.tilesNaFase();
    }

    void publicarSePreciso(boolean forcar) {
        if (!forcar && publicada != null && tick % 20 != 0) {
            return;
        }
        List<Object[]> l = new ArrayList<Object[]>(motivos.size());
        for (Map.Entry<String, long[]> e : motivos.entrySet()) {
            long[] m = e.getValue();
            l.add(new Object[] {e.getKey(), m[0], m[1], m[2], m[3]});
        }
        Collections.sort(l, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Long.compare((Long) b[1], (Long) a[1]);
            }
        });
        publicada = new Foto(this, Collections.unmodifiableList(l));
    }

    public Foto foto() {
        return publicada;
    }
}
