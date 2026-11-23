package modfalso.lento;

/**
 * "Codigo de mod" de teste: fica num pacote que NAO e do Hoshiestar nem neutro, e vai num jar de
 * teste (ModFalso-1.0.jar) para o indice de jars achar o dono pela pilha.
 */
public final class Trabalho {

    private Trabalho() {
    }

    /** Dorme {@code ms} (o "tick lento"); acordar antes da hora so acontece se o teste mandar. */
    public static void metodoLentoDoMod(long ms) {
        long fim = System.nanoTime() + ms * 1000000L;
        for (;;) {
            long falta = fim - System.nanoTime();
            if (falta <= 0L) {
                return;
            }
            try {
                Thread.sleep(Math.max(1L, falta / 1000000L));
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
