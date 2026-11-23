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
package hoshiestar.mekanism.matriz;

import hoshiestar.mekanism.Mekanism;
import mekanism.common.content.matrix.SynchronizedMatrixData;
import net.minecraft.world.World;

/**
 * Ganchos chamados DE DENTRO da classe do Mekanism depois de transformada (formato 1 da base):
 *
 * <pre>
 * public double getEnergy(World w) {
 *     if (GanchoMatriz.antesDeLer(this, w)) return GanchoMatriz.valorLido(this, w);
 *     ...corpo original...
 * }
 * public void setEnergy(World w, double e) {
 *     if (GanchoMatriz.antesDeEscrever(this, w, e)) return;
 *     ...corpo original...
 * }
 * </pre>
 *
 * So delegam para {@link MatrizInducao}. Excecao que veio de dentro da busca original (de outro mod,
 * embrulhada em {@link DeTerceiros}): e relancada como o original faria, sem desligar nada. Qualquer
 * outra excecao e nossa: o recurso desliga ate reiniciar, ela e registrada (com limitador) e o corpo
 * original roda. O tratamento de erro nao depende de MatrizInducao (se ate a carga dela falhasse, nada
 * escaparia daqui). "public static" numa classe publica (exigencia do utilitario); o receptor tem o
 * tipo exato da classe do mod (esta classe compila com --mods).
 */
public final class GanchoMatriz {

    private GanchoMatriz() {
    }

    /** Inicio de getEnergy: true = use {@link #valorLido} (o corpo original nao roda). */
    public static boolean antesDeLer(SynchronizedMatrixData m, World w) {
        if (Mekanism.modo == Mekanism.DESLIGADO) {
            return false;
        }
        try {
            return MatrizInducao.antesDeLer(m, w);
        } catch (DeTerceiros e) {
            throw deOutroMod(e);
        } catch (Throwable t) {
            falhou(m, "getEnergy", t);
            return false;
        }
    }

    /** O valor da leitura rapida que {@link #antesDeLer} acabou de calcular (mesma thread, logo em seguida). */
    public static double valorLido(SynchronizedMatrixData m, World w) {
        return MatrizInducao.ultimoValor;
    }

    /** Inicio de setEnergy: true = a escrita ja foi feita (o corpo original nao roda). */
    public static boolean antesDeEscrever(SynchronizedMatrixData m, World w, double energia) {
        if (Mekanism.modo == Mekanism.DESLIGADO) {
            return false;
        }
        try {
            return MatrizInducao.antesDeEscrever(m, w, energia);
        } catch (DeTerceiros e) {
            throw deOutroMod(e);
        } catch (Throwable t) {
            falhou(m, "setEnergy", t);
            return false;
        }
    }

    /** Conta e relanca a excecao original de outro mod (ela sai do getEnergy/setEnergy como sairia sem nos). */
    private static RuntimeException deOutroMod(DeTerceiros e) {
        Mekanism.excecoesDeTerceiros++;
        Throwable causa = e.getCause() != null ? e.getCause() : e;
        return GanchoMatriz.<RuntimeException>lancar(causa);
    }

    /** Lanca qualquer Throwable (inclusive verificada) sem embrulhar. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T lancar(Throwable t) throws T {
        throw (T) t;
    }

    /** Excecao nossa: desliga o recurso (com registro) e apaga o estado da estrutura. Nunca lanca. */
    private static void falhou(SynchronizedMatrixData m, String onde, Throwable t) {
        try {
            Mekanism.desligarPorErro(onde, t);
        } catch (Throwable ignorado) {
            // nada a fazer
        }
        try {
            MatrizInducao.esquecer(m);
        } catch (Throwable ignorado) {
            // o recurso ja esta desligado: o estado nao e mais usado
        }
    }
}
