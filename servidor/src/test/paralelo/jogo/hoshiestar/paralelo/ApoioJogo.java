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

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.IWorldAccess;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Apoio dos testes dos adaptadores do jogo (passo 4: {@code GanchosAdiados}, {@code GanchosEventos}, {@code OrdenadaJogo}),
 * que rodam contra as classes REAIS compiladas pelo build rapido ({@code eclipse/cauldron/build/classes/java/main}), sem
 * subir o servidor. Objetos do jogo sao criados sem construtor ({@code Unsafe.allocateInstance}) e recebem so os campos
 * que os metodos testados tocam. Veio das sondas da revisao do passo 4.
 */
final class ApoioJogo {

    private ApoioJogo() {
    }

    static final sun.misc.Unsafe U;

    static {
        try {
            Field fu = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            fu.setAccessible(true);
            U = (sun.misc.Unsafe) fu.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T cru(Class<T> c) throws Exception {
        return (T) U.allocateInstance(c);
    }

    static Field campo(Class<?> c, String n) throws Exception {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // sobe
            }
        }
        throw new NoSuchFieldException(c + "." + n);
    }

    /** Escreve o campo (tambem final) pelo Unsafe. */
    static void por(Object o, String n, Object val) throws Exception {
        Field f = campo(o.getClass(), n);
        long off = U.objectFieldOffset(f);
        if (f.getType() == boolean.class) {
            U.putBoolean(o, off, (Boolean) val);
        } else if (f.getType() == int.class) {
            U.putInt(o, off, (Integer) val);
        } else if (f.getType() == double.class) {
            U.putDouble(o, off, (Double) val);
        } else if (f.getType() == float.class) {
            U.putFloat(o, off, (Float) val);
        } else {
            U.putObject(o, off, val);
        }
    }

    static Object ler(Object o, String n) throws Exception {
        return campo(o.getClass(), n).get(o);
    }

    /** O que os avisos ao cliente chegaram a fazer, na ordem (um IWorldAccess falso que so anota). */
    static final List<String> REG = new ArrayList<String>();

    static final IWorldAccess ACESSO = new IWorldAccess() {
        public void markBlockForUpdate(int a, int b, int c) {
            REG.add("markBlockForUpdate " + a + "," + b + "," + c);
        }

        public void markBlockForRenderUpdate(int a, int b, int c) {
            REG.add("markBlockForRenderUpdate " + a + "," + b + "," + c);
        }

        public void markBlockRangeForRenderUpdate(int a, int b, int c, int d, int e, int f) {
            REG.add("markBlockRangeForRenderUpdate " + a + "," + b + "," + c + "," + d + "," + e + "," + f);
        }

        public void playSound(String s, double x, double y, double z, float vol, float tom) {
            REG.add("playSound " + s + " " + x + "," + y + "," + z + " " + vol + " " + tom);
        }

        public void playSoundToNearExcept(EntityPlayer p, String s, double x, double y, double z, float vol, float tom) {
            REG.add("playSoundToNearExcept " + s + " " + x + "," + y + "," + z + " " + vol + " " + tom);
        }

        public void spawnParticle(String s, double a, double b, double c, double d, double e, double f) {
            REG.add("spawnParticle " + s + " " + a + "," + b + "," + c + "," + d + "," + e + "," + f);
        }

        public void onEntityCreate(Entity e) {
            REG.add("onEntityCreate");
        }

        public void onEntityDestroy(Entity e) {
            REG.add("onEntityDestroy");
        }

        public void playRecord(String s, int a, int b, int c) {
            REG.add("playRecord " + s + " " + a + "," + b + "," + c);
        }

        public void broadcastSound(int a, int b, int c, int d, int e) {
            REG.add("broadcastSound " + a + "," + b + "," + c + "," + d + "," + e);
        }

        public void playAuxSFX(EntityPlayer p, int a, int b, int c, int d, int e) {
            REG.add("playAuxSFX " + (p == null ? "null" : "jogador") + " " + a + "," + b + "," + c + "," + d + "," + e);
        }

        public void destroyBlockPartially(int a, int b, int c, int d, int e) {
            REG.add("destroyBlockPartially " + a + "," + b + "," + c + "," + d + "," + e);
        }

        public void onStaticEntitiesChanged() {
            REG.add("onStaticEntitiesChanged");
        }
    };

    /** O hoshiestar.yml da pasta de trabalho (o script escreve um com paralelo.ligado). Como o boot faz. */
    static void carregarConfig() {
        hoshiestar.config.HoshiestarConfig.carregarPadrao();
    }
}
