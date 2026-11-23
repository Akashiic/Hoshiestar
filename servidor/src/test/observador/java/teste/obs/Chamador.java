package teste.obs;

/** Quadro de pilha "de mod" para os testes (pacote fora da lista de pulos do hoshiestar.observador.Pilha). */
public final class Chamador {
    private Chamador() {
    }

    public static void rodar(Runnable r) {
        r.run();
    }
}
