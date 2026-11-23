package teste.moda;

import hoshiestar.observador.Nucleo;

/** "Codigo de mod" falso: a amostra de pilha do observador tem de achar este pacote (teste.moda.) como dono. */
public final class Chamador {

    private Chamador() {
    }

    public static void escrever(Nucleo n, Object mundo, int dim, int cx, int cz) {
        n.acesso(Nucleo.A_ESCREVER_BLOCO, mundo, dim, cx, cz);
    }

    public static void outraThread(Nucleo n, int tipo) {
        n.outraThread(tipo);
    }

    public static void carregar(Nucleo n, Object mundo, int dim, int cx, int cz) {
        n.cargaChunk(mundo, dim, cx, cz);
    }

    /** Um "mod" que usa a lista de tiles do mundo direto: le o tamanho, itera, se tira e se poe de volta. */
    public static void mexerNaLista(java.util.List l) {
        l.size();
        for (Object o : l) {
            o.hashCode();
        }
        Object x = new Object();
        l.add(x);
        l.remove(x);
    }
}
