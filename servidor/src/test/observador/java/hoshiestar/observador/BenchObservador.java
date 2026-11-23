package hoshiestar.observador;

import hoshiestar.teste.Verifica;

/**
 * Custo do caminho quente do nucleo, em ns por chamada (INDICACAO deste computador, sem Minecraft e sem o resto do
 * servidor; nao e medicao de servidor). O que importa: o caminho "so conta" (leitura na mesma secao) e o
 * caminho completo (registro em T1), e os que a valvula supoe (CUSTO_RAPIDA_NS e CUSTO_ENTRADA_NS).
 */
public final class BenchObservador {

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("BenchObservador");
        try {
            final Object m0 = new Object();
            Nucleo n = new Nucleo(TesteObservadorNucleo.DONOS, 256, 5.0, Nucleo.SISTEMA);
            n.tirarFoto(TesteObservadorNucleo.fotoPadrao());
            n.inicioTick(System.nanoTime());
            n.entrar(Nucleo.C_TILE, m0, 0, 5, 5, TesteObservadorNucleo.ModAMaquina.class);
            int rodadas = 5;
            int voltas = 5000000;
            double rapida = 1e9, entrar = 1e9, completa = 1e9, cruza = 1e9, fora = 1e9;
            long sorvedouro = 0;
            for (int r = 0; r < rodadas + 2; r++) {
                long t0 = System.nanoTime();
                for (int i = 0; i < voltas; i++) {
                    if (n.leituraRapida(m0, 6 + (i & 7), 6 + ((i >> 3) & 7))) {
                        sorvedouro++;
                    }
                }
                double a = (System.nanoTime() - t0) / (double) voltas;
                t0 = System.nanoTime();
                for (int i = 0; i < voltas; i++) {
                    n.entrar(Nucleo.C_TILE, m0, 0, 5, 5, TesteObservadorNucleo.ModAMaquina.class);
                    n.sair(Nucleo.C_TILE);
                }
                double b = (System.nanoTime() - t0) / (double) voltas;
                t0 = System.nanoTime();
                for (int i = 0; i < voltas; i++) {
                    n.acesso(Nucleo.A_ESCREVER_BLOCO, m0, 0, 6 + (i & 7), 6 + ((i >> 3) & 7));
                }
                double c = (System.nanoTime() - t0) / (double) voltas;
                t0 = System.nanoTime();
                for (int i = 0; i < voltas / 10; i++) {
                    n.acesso(Nucleo.A_ESCREVER_BLOCO, m0, 0, 20 + (i & 3), 5);
                    if ((i & 1023) == 0) {
                        n.inicioTick(System.nanoTime()); // zera o teto de amostras de pilha por tick
                        n.entrar(Nucleo.C_TILE, m0, 0, 5, 5, TesteObservadorNucleo.ModAMaquina.class);
                    }
                }
                double d = (System.nanoTime() - t0) / (double) (voltas / 10);
                t0 = System.nanoTime();
                for (int i = 0; i < voltas / 10; i++) {
                    n.acesso(Nucleo.A_ESCREVER_BLOCO, m0, 0, 200, 5);
                    if ((i & 1023) == 0) {
                        n.inicioTick(System.nanoTime());
                        n.entrar(Nucleo.C_TILE, m0, 0, 5, 5, TesteObservadorNucleo.ModAMaquina.class);
                    }
                }
                double e = (System.nanoTime() - t0) / (double) (voltas / 10);
                if (r >= 2) { // as duas primeiras sao aquecimento
                    rapida = Math.min(rapida, a);
                    entrar = Math.min(entrar, b);
                    completa = Math.min(completa, c);
                    cruza = Math.min(cruza, d);
                    fora = Math.min(fora, e);
                }
            }
            System.out.printf("  indicacao (melhor de %d rodadas, este computador, sem Minecraft; NAO e medicao de servidor):%n", rodadas);
            System.out.printf("    leitura na mesma secao ('so conta'): %.1f ns/chamada, so o nucleo; o JIT a embute no laco, entao e um limite inferior (a valvula supoe %d, contando o gancho)%n", rapida, Nucleo.CUSTO_RAPIDA_NS);
            System.out.printf("    entrar+sair de contexto:             %.1f ns/par (a valvula supoe %d)%n", entrar, Nucleo.CUSTO_ENTRADA_NS);
            System.out.printf("    escrita registrada, mesma secao:     %.1f ns/chamada (T1)%n", completa);
            System.out.printf("    escrita que cruza secao, mesma regiao (com amostra de pilha 1 em 256, 20 por tick): %.1f ns/chamada%n", cruza);
            System.out.printf("    escrita que cruza regiao (T1 + T2):  %.1f ns/chamada%n", fora);
            v.sim(sorvedouro > 0, "bench: rodou");
            // so confere que a hipotese da valvula nao esta absurda (folga de 20x para nao depender da maquina)
            v.sim(rapida < Nucleo.CUSTO_RAPIDA_NS * 20, "bench: a leitura rapida custa menos de 20x o que a valvula supoe");
            v.sim(entrar < Nucleo.CUSTO_ENTRADA_NS * 20, "bench: entrar+sair custa menos de 20x o que a valvula supoe");
        } catch (Throwable t) {
            v.erro("excecao no bench", t);
        }
        v.fim();
    }
}
