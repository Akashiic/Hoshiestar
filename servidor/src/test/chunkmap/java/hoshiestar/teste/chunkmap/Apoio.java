package hoshiestar.teste.chunkmap;

import com.koloboke.collect.map.LongObjMap;
import net.minecraft.world.chunk.Chunk;

import java.lang.reflect.Field;

/** Utilitarios dos testes do chunkmap (reflexao nos campos do patch e busca pura no mapa). */
final class Apoio {

    private Apoio() {
    }

    static Object campo(Object o, String nome) {
        try {
            Field f = acharCampo(o.getClass(), nome);
            f.setAccessible(true);
            return f.get(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void definirCampo(Object o, String nome, Object valor) {
        try {
            Field f = acharCampo(o.getClass(), nome);
            f.setAccessible(true);
            f.set(o, valor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Object estatico(Class<?> c, String nome) {
        try {
            Field f = c.getDeclaredField(nome);
            f.setAccessible(true);
            return f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Field acharCampo(Class<?> c, String nome) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(nome);
            } catch (NoSuchFieldException e) {
                // sobe
            }
        }
        throw new NoSuchFieldException(nome);
    }

    /** O cache do patch (null = sem cache). */
    static Object cache(thermos.wrapper.ChunkBlockHashMap m) {
        return campo(m, "hoshieCache");
    }

    static boolean cacheAtivo(thermos.wrapper.ChunkBlockHashMap m) {
        Object c = cache(m);
        return c != null && (Boolean) campo(c, "ativo");
    }

    static Chunk[] slots(thermos.wrapper.ChunkBlockHashMap m) {
        return (Chunk[]) campo(cache(m), "slots");
    }

    /** Busca pura no mapa (as formulas do Crucible), sem cache nenhum. */
    @SuppressWarnings("unchecked")
    static Chunk estrutura(LongObjMap<Chunk[][]> raw, int x, int z) {
        Chunk[][] b = raw.get((((long) (x >> 4)) << 32L) ^ (z >> 4));
        if (b == null) {
            return null;
        }
        int a = x % 16;
        int c = z % 16;
        return b[(a + (a >> 31)) ^ (a >> 31)][(c + (c >> 31)) ^ (c >> 31)];
    }

    static long chave(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }
}
