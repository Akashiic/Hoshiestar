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
package hoshiestar.teste.chromaticraft;

import hoshiestar.api.Limitador;
import hoshiestar.chromaticraft.DecisaoShift;
import hoshiestar.chromaticraft.DecisaoShift.Motivo;
import hoshiestar.teste.Verifica;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Teste isolado da DECISAO do recurso "chromaticraft" (item 1.B): a classe {@link DecisaoShift} do jar construido
 * (os mesmos bytes que rodam no servidor), com relogio e mapa de chunks de mentira. Nao precisa de mod nem de Minecraft.
 *
 * <p>Cobre: volume no limite e limite+1; caixa do tamanho do mundo (sem estouro) e tamanhos que estouram int/long;
 * distancia -1/0/24/25; altura do destino -1/0/255/256 nas seis direcoes; intervalo minimo (primeira passa, segunda
 * dentro do intervalo recusa, depois passa; recusa nao renova; jogadores independentes; relogio que da a volta); limpeza do mapa
 * de jogadores; chunks nao carregados na origem, no destino e na borda alargada; a ordem das checagens; contadores;
 * excecao vinda do mapa de chunks; varias threads.
 */
public final class TesteShiftDecisao {

    private TesteShiftDecisao() {
    }

    // ---- relogio de mentira
    static final class Relogio implements Limitador.Tempo {
        long ns;

        Relogio(long inicio) {
            ns = inicio;
        }

        @Override
        public long nanos() {
            return ns;
        }

        void ms(long ms) {
            ns += ms * 1000000L;
        }
    }

    // ---- mapa de chunks de mentira: so carregados os que estao no conjunto (ou todos); conta as consultas
    static final class Mundo implements DecisaoShift.Chunks {
        boolean todos;
        final Set<Long> carregados = new HashSet<Long>();
        int consultas;
        final Set<Long> consultados = new HashSet<Long>();
        RuntimeException lancar;

        Mundo(boolean todos) {
            this.todos = todos;
        }

        static long chave(int cx, int cz) {
            return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        }

        Mundo com(int cx, int cz) {
            carregados.add(chave(cx, cz));
            return this;
        }

        /** Carrega um retangulo de chunks. */
        Mundo comRetangulo(int cx0, int cz0, int cx1, int cz1) {
            for (int x = cx0; x <= cx1; x++) {
                for (int z = cz0; z <= cz1; z++) {
                    com(x, z);
                }
            }
            return this;
        }

        @Override
        public boolean carregado(int cx, int cz) {
            consultas++;
            consultados.add(chave(cx, cz));
            if (lancar != null) {
                throw lancar;
            }
            return todos || carregados.contains(chave(cx, cz));
        }
    }

    static final UUID A = new UUID(1, 1);
    static final UUID B = new UUID(2, 2);

    static final int[][] DIRECOES = { // nome, dx, dy, dz  (ForgeDirection.offsetX/Y/Z)
        {0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0},
    };
    static final String[] NOMES = {"UP", "DOWN", "NORTH", "SOUTH", "WEST", "EAST"};

    /** Decisao padrao do teste: 1000 ms, distancia, volume 4096, altura, chunks (relogio e mundo dados). */
    static DecisaoShift padrao(Relogio r) {
        return new DecisaoShift(1000, true, 4096, true, true, r);
    }

    static Motivo d(DecisaoShift x, Object jogador, int[] c, int[] dir, int dist, Mundo m) {
        return x.decidir(jogador, c[0], c[1], c[2], c[3], c[4], c[5], dir[0], dir[1], dir[2], dist, 24, m);
    }

    static int[] caixa(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new int[] {x0, y0, z0, x1, y1, z1};
    }

    public static void main(String[] a) throws Exception {
        final Verifica v = new Verifica("TesteShiftDecisao");
        int[] sul = DIRECOES[3];
        int[] cima = DIRECOES[0];
        int[] baixo = DIRECOES[1];

        // ============================================================ volume
        System.out.println("-- volume");
        // 16x16x16 posicoes = tamanho 15 em cada eixo
        v.igual(4096L, DecisaoShift.volume(0, 60, 0, 15, 75, 15), "volume(16x16x16) = 4096 posicoes (o laco da (tam+1) voltas por eixo)");
        v.igual(1L, DecisaoShift.volume(5, 5, 5, 5, 5, 5), "volume de um bloco so = 1");
        {
            DecisaoShift x = new DecisaoShift(0, true, 4096, true, true, new Relogio(0));
            Mundo todos = new Mundo(true);
            v.igual(Motivo.PASSA, d(x, A, caixa(0, 60, 0, 15, 75, 15), sul, 1, todos), "volume 4096 (no limite) passa");
            v.igual(Motivo.VOLUME, d(x, A, caixa(0, 60, 0, 16, 60, 240), sul, 1, todos), "volume 4097 (17 x 1 x 241, limite+1) e recusado");
            v.igual(17L * 241L, DecisaoShift.volume(0, 60, 0, 16, 60, 240), "volume(17 x 1 x 241) = 4097");
            v.igual(Motivo.PASSA, d(x, A, caixa(0, 60, 0, 62, 124, 0), sul, 1, todos), "volume 4095 (63 x 65 x 1, limite-1) passa");
            v.igual(Motivo.PASSA, d(x, A, caixa(0, 60, 0, 4095, 60, 0), sul, 1, new Mundo(true)), "volume 4096 como uma linha de 4096 blocos passa (so chunks carregados)");
            v.igual(Motivo.VOLUME, d(x, A, caixa(0, 60, 0, 4096, 60, 0), sul, 1, todos), "linha de 4097 blocos e recusada");
            v.igual(Motivo.VOLUME, d(x, A, caixa(0, 0, 0, 15, 15, 16), sul, 1, todos), "um eixo a mais: 16 x 16 x 17 = 4352 recusado");
            // volume padrao exato do config
            v.igual(Motivo.PASSA, d(new DecisaoShift(0, true, 1, true, true, new Relogio(0)), A, caixa(7, 7, 7, 7, 7, 7), sul, 1, todos), "limite 1: um bloco passa");
            v.igual(Motivo.VOLUME, d(new DecisaoShift(0, true, 1, true, true, new Relogio(0)), A, caixa(7, 7, 7, 8, 7, 7), sul, 1, todos), "limite 1: dois blocos recusados");
        }
        // caixa do tamanho do mundo: sem estouro
        {
            long esperado = 60000001L * 256L * 60000001L;
            v.igual(esperado, DecisaoShift.volume(-30000000, 0, -30000000, 30000000, 255, 30000000), "caixa do tamanho do mundo: volume exato em long (" + esperado + "), sem estourar");
            v.sim(esperado > 0 && esperado < Long.MAX_VALUE, "o volume do mundo cabe em long sem sinal trocado");
            DecisaoShift x = padrao(new Relogio(0));
            v.igual(Motivo.VOLUME, d(x, A, caixa(-30000000, 0, -30000000, 30000000, 255, 30000000), sul, 1, new Mundo(true)),
                    "caixa do tamanho do mundo e recusada por VOLUME (nao por estouro)");
            // o int de BlockBox.getVolume() estoura; o nosso nao
            int estouro = (60000000) * 255 * 60000000;
            v.sim(estouro != 60000000L * 255L * 60000000L, "controle: a conta em int do proprio mod (getVolume) estoura aqui (" + estouro + ")");
            // tamanhos absurdos: satura, nunca fica negativo
            v.igual(Long.MAX_VALUE, DecisaoShift.volume(0, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), "tamanhos Integer.MAX_VALUE: satura em Long.MAX_VALUE");
            v.igual(Long.MAX_VALUE, DecisaoShift.volume(0, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 1), "2^31 x 2^31 x 2 satura");
            v.igual(Motivo.VOLUME, d(x, A, caixa(0, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), sul, 1, new Mundo(true)), "caixa absurda: VOLUME, sem excecao");
            // BlockBox.infinity(): max - min estoura int para -1 => nenhuma volta no laco original
            v.igual(0L, DecisaoShift.volume(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
                    "BlockBox.infinity(): tamanho em int vira -1 => zero voltas, como no original");
            Mundo semNada = new Mundo(false);
            v.igual(Motivo.PASSA, d(new DecisaoShift(0, true, 4096, true, true, new Relogio(0)), A,
                    caixa(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), sul, 1, semNada),
                    "zero posicoes: nada a ler nem escrever, passa sem olhar altura nem chunks");
            v.igual(0, semNada.consultas, "zero posicoes: nenhuma consulta ao mapa de chunks");
        }
        // volume desligado (0) deixa passar caixa grande; com o resto desligado
        {
            DecisaoShift x = new DecisaoShift(0, false, 0, false, false, new Relogio(0));
            v.igual(Motivo.PASSA, d(x, A, caixa(0, 0, 0, 5000, 255, 5000), sul, 1, new Mundo(false)), "volume 0 = sem limite (com os outros desligados passa)");
        }

        // ============================================================ distancia
        System.out.println("-- distancia");
        {
            Mundo todos = new Mundo(true);
            int[] c = caixa(0, 100, 0, 3, 103, 3);
            DecisaoShift x = new DecisaoShift(0, true, 4096, true, true, new Relogio(0));
            v.igual(Motivo.DISTANCIA, d(x, A, c, sul, -1, todos), "distancia -1 recusada");
            v.igual(Motivo.PASSA, d(x, A, c, sul, 0, todos), "distancia 0 passa (limite de baixo)");
            v.igual(Motivo.PASSA, d(x, A, c, sul, 24, todos), "distancia 24 passa (o maximo do SHIFT)");
            v.igual(Motivo.DISTANCIA, d(x, A, c, sul, 25, todos), "distancia 25 recusada");
            v.igual(Motivo.DISTANCIA, d(x, A, c, sul, Integer.MIN_VALUE, todos), "distancia Integer.MIN_VALUE recusada");
            v.igual(Motivo.DISTANCIA, d(x, A, c, sul, Integer.MAX_VALUE, todos), "distancia Integer.MAX_VALUE recusada");
            v.igual(Motivo.DISTANCIA, d(x, A, c, sul, 1000, todos), "distancia 1000 recusada (cliente modificado)");
            // o maximo vem do mod: potencia diferente muda o limite
            v.igual(Motivo.PASSA, x.decidir(A, c[0], c[1], c[2], c[3], c[4], c[5], 0, 0, 1, 30, 30, todos), "com potencia maxima 30, a distancia 30 passa");
            v.igual(Motivo.DISTANCIA, x.decidir(A, c[0], c[1], c[2], c[3], c[4], c[5], 0, 0, 1, 31, 30, todos), "com potencia maxima 30, a distancia 31 e recusada");
            // interruptor da distancia desligado: aceita (as outras checagens seguem valendo)
            DecisaoShift sem = new DecisaoShift(0, false, 4096, false, false, new Relogio(0));
            v.igual(Motivo.PASSA, d(sem, A, c, sul, -1, todos), "interruptor da distancia off: -1 passa");
            v.igual(Motivo.PASSA, d(sem, A, c, sul, 25, todos), "interruptor da distancia off: 25 passa");
        }

        // ============================================================ altura do destino
        System.out.println("-- altura do destino");
        {
            DecisaoShift x = new DecisaoShift(0, true, 4096, true, false, new Relogio(0)); // sem chunks para isolar a altura
            Mundo nada = new Mundo(true);
            for (int i = 0; i < 6; i++) {
                int[] dir = DIRECOES[i];
                for (int alvo : new int[] {-1, 0, 255, 256}) {
                    int dist = 5;
                    // caixa de uma camada cuja imagem deslocada fica exatamente em y = alvo
                    int y0 = alvo - dir[1] * dist;
                    Motivo esperado = alvo >= 0 && alvo <= 255 ? Motivo.PASSA : Motivo.ALTURA;
                    v.igual(esperado, d(x, A, caixa(10, y0, 10, 11, y0, 11), dir, dist, nada),
                            NOMES[i] + ": destino em y=" + alvo + " (origem y=" + y0 + ", dist 5)");
                }
            }
            // com distancia 0 (sem deslocamento) nas quatro horizontais: origem y -1/0/255/256
            for (int i = 2; i < 6; i++) {
                for (int y : new int[] {-1, 0, 255, 256}) {
                    Motivo esperado = y >= 0 && y <= 255 ? Motivo.PASSA : Motivo.ALTURA;
                    v.igual(esperado, d(x, A, caixa(0, y, 0, 1, y, 1), DIRECOES[i], 0, nada), NOMES[i] + ", dist 0, caixa em y=" + y);
                }
            }
            // o exemplo do relatorio: de y >= 232 para cima 24 => 256+ ; de y <= 23 para baixo 24 => -1
            v.igual(Motivo.ALTURA, d(x, A, caixa(0, 232, 0, 3, 240, 3), cima, 24, nada), "UP 24 a partir de y 232..240: destino 256..264 recusado");
            v.igual(Motivo.PASSA, d(x, A, caixa(0, 231, 0, 3, 231, 3), cima, 24, nada), "UP 24 a partir de y 231: destino 255 passa");
            v.igual(Motivo.ALTURA, d(x, A, caixa(0, 0, 0, 3, 23, 3), baixo, 24, nada), "DOWN 24 a partir de y 0..23: destino -24..-1 recusado");
            v.igual(Motivo.PASSA, d(x, A, caixa(0, 24, 0, 3, 30, 3), baixo, 24, nada), "DOWN 24 a partir de y 24: destino 0 passa");
            // caixa alta: so a ponta que sai ja basta
            v.igual(Motivo.ALTURA, d(x, A, caixa(0, 200, 0, 3, 250, 3), cima, 6, nada), "caixa alta 200..250 para cima 6: topo em 256 recusado");
            v.igual(Motivo.PASSA, d(x, A, caixa(0, 200, 0, 3, 250, 3), cima, 5, nada), "caixa alta 200..250 para cima 5: topo em 255 passa");
            v.igual(Motivo.PASSA, d(x, A, caixa(0, 0, 0, 3, 255, 3), sul, 24, nada), "caixa de 0 a 255 deslocada na horizontal passa (volume 4 x 256 x 4 = 4096)");
            // distancia absurda (com a checagem de distancia desligada): a conta e em long, sem voltar para dentro de 0..255
            DecisaoShift sem = new DecisaoShift(0, false, 4096, true, false, new Relogio(0));
            v.igual(Motivo.ALTURA, d(sem, A, caixa(0, 100, 0, 3, 100, 3), cima, Integer.MAX_VALUE, nada), "UP com distancia Integer.MAX_VALUE e recusado por altura (sem estouro)");
            v.igual(Motivo.ALTURA, d(sem, A, caixa(0, 100, 0, 3, 100, 3), baixo, Integer.MAX_VALUE, nada), "DOWN com distancia Integer.MAX_VALUE e recusado por altura");
            v.igual(Motivo.ALTURA, d(sem, A, caixa(0, 100, 0, 3, 100, 3), cima, Integer.MIN_VALUE, nada), "UP com distancia Integer.MIN_VALUE e recusado por altura");
            v.igual(Motivo.ALTURA, d(sem, A, caixa(0, 100, 0, 3, 100, 3), baixo, Integer.MIN_VALUE, nada), "DOWN com distancia Integer.MIN_VALUE (o int de -1 * MIN_VALUE voltaria a MIN_VALUE) recusado");
            // a conta e feita em long mesmo para uma direcao fora do normal (ForgeDirection so tem -1, 0 e 1; este caso prova que
            // o produto direcao * distancia nao e feito em int: 4 * 2^30 em int da 0 e o destino parecia ficar em y 100)
            v.igual(Motivo.ALTURA, d(sem, A, caixa(0, 100, 0, 3, 100, 3), new int[] {0, 4, 0}, 1 << 30, nada),
                    "direcao hipotetica dy=4 com distancia 2^30: produto 2^32 (0 se fosse em int) e recusado por altura");
            // checagem de altura desligada: aceita
            DecisaoShift off = new DecisaoShift(0, true, 4096, false, false, new Relogio(0));
            v.igual(Motivo.PASSA, d(off, A, caixa(0, 250, 0, 3, 255, 3), cima, 24, nada), "interruptor da altura off: destino fora de 0..255 passa");
        }

        // ============================================================ intervalo minimo
        System.out.println("-- intervalo minimo");
        {
            Relogio r = new Relogio(5000000000L);
            DecisaoShift x = padrao(r);
            Mundo todos = new Mundo(true);
            int[] c = caixa(0, 100, 0, 3, 103, 3);
            v.igual(Motivo.PASSA, d(x, A, c, sul, 5, todos), "primeira chamada do jogador passa");
            r.ms(500);
            v.igual(Motivo.INTERVALO, d(x, A, c, sul, 5, todos), "segunda, 500 ms depois (dentro do intervalo), e recusada");
            r.ns += 499999999L;
            v.igual(Motivo.INTERVALO, d(x, A, c, sul, 5, todos), "999,999999 ms depois da primeira: ainda recusada");
            r.ns += 1L;
            v.igual(Motivo.PASSA, d(x, A, c, sul, 5, todos), "exatamente 1000 ms depois da primeira: passa (a recusa de 500 ms nao renovou a hora)");
            r.ms(999);
            v.igual(Motivo.INTERVALO, d(x, A, c, sul, 5, todos), "999 ms depois da segunda aceita: recusada");
            r.ms(1);
            v.igual(Motivo.PASSA, d(x, A, c, sul, 5, todos), "1000 ms depois da segunda aceita: passa de novo");
            // jogadores independentes
            Relogio r2 = new Relogio(0);
            DecisaoShift y = padrao(r2);
            v.igual(Motivo.PASSA, d(y, A, c, sul, 5, todos), "jogador A passa");
            r2.ms(1);
            v.igual(Motivo.PASSA, d(y, B, c, sul, 5, todos), "jogador B, 1 ms depois, tambem passa (cada um com a sua hora)");
            r2.ms(500);
            v.igual(Motivo.INTERVALO, d(y, A, c, sul, 5, todos), "A de novo, 501 ms depois: recusado");
            v.igual(Motivo.INTERVALO, d(y, B, c, sul, 5, todos), "B de novo, 500 ms depois: recusado");
            r2.ms(500);
            v.igual(Motivo.PASSA, d(y, A, c, sul, 5, todos), "A passa 1001 ms depois");
            v.igual(Motivo.INTERVALO, d(y, A, c, sul, 5, todos), "A de novo no mesmo instante: recusado");
            v.igual(Motivo.PASSA, d(y, B, c, sul, 5, todos), "B passa 1000 ms depois da sua primeira");
            // a chave e o valor do UUID, nao a referencia
            Relogio r3 = new Relogio(0);
            DecisaoShift z = padrao(r3);
            v.igual(Motivo.PASSA, d(z, new UUID(7, 8), c, sul, 5, todos), "UUID(7,8) passa");
            r3.ms(10);
            v.igual(Motivo.INTERVALO, d(z, new UUID(7, 8), c, sul, 5, todos), "outro objeto UUID(7,8) e o mesmo jogador");
            // recusa por outro motivo nao guarda a hora: a primeira chamada valida logo depois passa
            Relogio r4 = new Relogio(0);
            DecisaoShift w = padrao(r4);
            v.igual(Motivo.VOLUME, d(w, A, caixa(0, 0, 0, 100, 100, 100), sul, 5, todos), "recusa por volume");
            r4.ns += 1;
            v.igual(Motivo.PASSA, d(w, A, c, sul, 5, todos), "recusa por volume nao conta como pedido aceito: a chamada valida 1 ns depois passa");
            v.igual(1, w.jogadoresGuardados(), "so o pedido aceito deixou registro");
            // relogio que da a volta (System.nanoTime pode ser qualquer long)
            Relogio r5 = new Relogio(Long.MAX_VALUE - 400000000L);
            DecisaoShift q = padrao(r5);
            v.igual(Motivo.PASSA, d(q, A, c, sul, 5, todos), "relogio perto de Long.MAX_VALUE: primeira passa");
            r5.ms(900); // passa de Long.MAX_VALUE (da a volta para negativo)
            v.sim(r5.ns < 0, "controle: o relogio de teste deu a volta");
            v.igual(Motivo.INTERVALO, d(q, A, c, sul, 5, todos), "depois da volta do relogio, 900 ms: ainda recusado");
            r5.ms(100);
            v.igual(Motivo.PASSA, d(q, A, c, sul, 5, todos), "depois da volta do relogio, 1000 ms: passa");
            // intervalo 0 = nao confere e nao guarda nada
            DecisaoShift sem = new DecisaoShift(0, true, 4096, true, true, new Relogio(0));
            for (int i = 0; i < 5; i++) {
                v.igual(Motivo.PASSA, d(sem, A, c, sul, 5, todos), "intervalo 0: chamada " + i + " no mesmo instante passa");
            }
            v.igual(0, sem.jogadoresGuardados(), "intervalo 0: o mapa de jogadores fica vazio");
            // intervalo gigante nao estoura a conta de nanossegundos
            DecisaoShift enorme = new DecisaoShift(Long.MAX_VALUE, true, 4096, true, true, new Relogio(0));
            v.igual(Motivo.PASSA, d(enorme, A, c, sul, 5, todos), "intervalo Long.MAX_VALUE ms: primeira passa");
            v.igual(Motivo.INTERVALO, d(enorme, A, c, sul, 5, todos), "intervalo Long.MAX_VALUE ms: segunda recusada");
        }

        // ============================================================ limpeza do mapa de jogadores
        System.out.println("-- limpeza do mapa de jogadores");
        {
            int[] c = caixa(0, 100, 0, 3, 103, 3);
            Mundo todos = new Mundo(true);
            // 20000 jogadores diferentes, um a cada 2 s (todos vencem antes do proximo): o mapa nao cresce sem limite
            Relogio r = new Relogio(0);
            DecisaoShift x = padrao(r);
            int maior = 0;
            for (int i = 0; i < 20000; i++) {
                r.ms(2000);
                v2(d(x, new UUID(100, i), c, sul, 5, todos));
                maior = Math.max(maior, x.jogadoresGuardados());
            }
            v.sim(maior <= 64, "20000 jogadores distintos, cada um vencido antes do proximo: o mapa chegou a no maximo " + maior + " registros (limite 64)");
            v.sim(x.jogadoresGuardados() <= 64, "no fim o mapa tem " + x.jogadoresGuardados() + " registros");
            // a limpeza nao esquece quem ainda esta dentro do intervalo
            Relogio r2 = new Relogio(0);
            DecisaoShift y = padrao(r2);
            v.igual(Motivo.PASSA, d(y, A, c, sul, 5, todos), "A passa em t=0");
            r2.ms(100);
            int falhasPasso = 0;
            for (int i = 0; i < 200; i++) { // 200 outros jogadores em t=100 ms: passa de 64 e dispara a limpeza
                if (d(y, new UUID(200, i), c, sul, 5, todos) != Motivo.PASSA) {
                    falhasPasso++;
                }
            }
            v.igual(0, falhasPasso, "200 outros jogadores no mesmo instante: todos passam");
            v.sim(y.jogadoresGuardados() >= 201, "ninguem foi esquecido dentro do intervalo (" + y.jogadoresGuardados() + " registros)");
            r2.ms(100);
            v.igual(Motivo.INTERVALO, d(y, A, c, sul, 5, todos), "A em t=200 ms continua barrado (a limpeza nao apagou o registro valido)");
            v.igual(Motivo.INTERVALO, d(y, new UUID(200, 199), c, sul, 5, todos), "o ultimo dos 200 tambem");
            r2.ms(901);
            v.igual(Motivo.PASSA, d(y, A, c, sul, 5, todos), "A passa em t=1101 ms (t=0 + 1000 ms vencido)");
            // pior caso absurdo: 25000 jogadores no MESMO instante: o mapa nao passa de 10000
            Relogio r3 = new Relogio(0);
            DecisaoShift z = padrao(r3);
            int maior3 = 0;
            for (int i = 0; i < 25000; i++) {
                v2(d(z, new UUID(300, i), c, sul, 5, todos));
                maior3 = Math.max(maior3, z.jogadoresGuardados());
            }
            v.sim(maior3 <= 10000, "25000 jogadores no mesmo instante: o mapa nunca passou de " + maior3 + " (teto 10000)");
        }

        // ============================================================ chunks
        System.out.println("-- chunks carregados");
        {
            // caixa x 100..110, z 100..110: chunks 6..6 (alargada: 99..111 -> 6..6)
            int[] c = caixa(100, 100, 100, 110, 110, 110);
            DecisaoShift x = new DecisaoShift(0, true, 4096, true, true, new Relogio(0));
            Mundo m = new Mundo(false).com(6, 6).com(6, 7).com(6, 6);
            // destino SUL 5: z 105..115 (alargada 104..116 -> chunks z 6..7)
            v.igual(Motivo.PASSA, d(x, A, c, sul, 5, m), "origem e destino carregados passam");
            v.sim(m.consultados.contains(Mundo.chave(6, 6)) && m.consultados.contains(Mundo.chave(6, 7)), "consultou os chunks da origem (6,6) e do destino (6,7)");
            // so leituras e so dos chunks necessarios
            v.sim(m.consultas <= 3, "poucas consultas: " + m.consultas);
            // chunk nao carregado na origem
            Mundo semOrigem = new Mundo(false).com(6, 7);
            v.igual(Motivo.CHUNKS, d(x, A, c, sul, 5, semOrigem), "chunk da origem nao carregado: recusado");
            v.igual(1, semOrigem.consultas, "parou na primeira consulta que falhou (origem primeiro)");
            // chunk nao carregado so no destino
            Mundo semDestino = new Mundo(false).com(6, 6);
            v.igual(Motivo.CHUNKS, d(x, A, c, sul, 5, semDestino), "chunk do destino nao carregado: recusado");
            // dist 0: destino = origem, so um chunk
            v.igual(Motivo.PASSA, d(x, A, c, sul, 0, new Mundo(false).com(6, 6)), "dist 0: so o chunk da origem basta");
            // borda alargada: x 16..20 (chunk 1), mas o vizinho x=15 esta no chunk 0
            int[] b = caixa(16, 100, 100, 20, 100, 100);
            v.igual(Motivo.CHUNKS, d(x, A, b, sul, 0, new Mundo(false).com(1, 6)), "borda alargada em x (x=16 toca x=15, chunk 0): chunk 0 nao carregado, recusado");
            v.igual(Motivo.PASSA, d(x, A, b, sul, 0, new Mundo(false).com(0, 6).com(1, 6)), "borda alargada em x: chunks 0 e 1 carregados, passa");
            int[] b2 = caixa(17, 100, 100, 20, 100, 100); // 17-1 = 16 -> chunk 1
            v.igual(Motivo.PASSA, d(x, A, b2, sul, 0, new Mundo(false).com(1, 6)), "x 17..20: a alargada fica no chunk 1, so ele basta");
            int[] b3 = caixa(25, 100, 100, 30, 100, 100); // 31 -> chunk 1
            v.igual(Motivo.PASSA, d(x, A, b3, sul, 0, new Mundo(false).com(1, 6)), "x 25..30: alargada 24..31, chunk 1 so");
            int[] b4 = caixa(25, 100, 100, 31, 100, 100); // 32 -> chunk 2
            v.igual(Motivo.CHUNKS, d(x, A, b4, sul, 0, new Mundo(false).com(1, 6)), "x 25..31: alargada toca x=32 (chunk 2), nao carregado: recusado");
            v.igual(Motivo.PASSA, d(x, A, b4, sul, 0, new Mundo(false).com(1, 6).com(2, 6)), "x 25..31: chunks 1 e 2 carregados, passa");
            // borda alargada em z
            int[] bz = caixa(100, 100, 16, 100, 100, 20);
            v.igual(Motivo.CHUNKS, d(x, A, bz, DIRECOES[5], 0, new Mundo(false).com(6, 1)), "borda alargada em z (z=16 toca z=15, chunk 0): recusado");
            v.igual(Motivo.PASSA, d(x, A, bz, DIRECOES[5], 0, new Mundo(false).com(6, 0).com(6, 1)), "borda alargada em z: passa com os dois");
            // lado maximo em z: z 25..31 toca z=32 (chunk 2)
            int[] bz2 = caixa(100, 100, 25, 100, 100, 31);
            v.igual(Motivo.CHUNKS, d(x, A, bz2, DIRECOES[5], 0, new Mundo(false).com(6, 1)), "borda alargada em z, lado maximo (z=31 toca z=32, chunk 2): recusado so com o chunk 1");
            v.igual(Motivo.PASSA, d(x, A, bz2, DIRECOES[5], 0, new Mundo(false).com(6, 1).com(6, 2)), "borda alargada em z, lado maximo: passa com os chunks 1 e 2");
            // borda alargada no DESTINO: origem x 100..110 (chunk 6) para LESTE 6 -> x 106..116 (alargada 105..117 -> chunks 6 e 7)
            v.igual(Motivo.CHUNKS, d(x, A, c, DIRECOES[5], 6, new Mundo(false).com(6, 6)), "destino a leste toca o chunk 7 (x=117), nao carregado: recusado");
            v.igual(Motivo.PASSA, d(x, A, c, DIRECOES[5], 6, new Mundo(false).com(6, 6).com(7, 6)), "destino a leste com chunk 7 carregado passa");
            // destino a leste 5: x 105..115, alargada 104..116 -> chunk 6 e 7 (116>>4 = 7)
            v.igual(Motivo.CHUNKS, d(x, A, c, DIRECOES[5], 5, new Mundo(false).com(6, 6)), "destino a leste 5: alargada vai ate x=116 (chunk 7): recusado sem ele");
            // destino a oeste: x 94..104 (alargada 93..105 -> chunks 5 e 6)
            v.igual(Motivo.CHUNKS, d(x, A, c, DIRECOES[4], 6, new Mundo(false).com(6, 6)), "destino a oeste toca o chunk 5, nao carregado: recusado");
            v.igual(Motivo.PASSA, d(x, A, c, DIRECOES[4], 6, new Mundo(false).com(6, 6).com(5, 6)), "destino a oeste com o chunk 5 carregado passa");
            // coordenadas negativas: x=-1..-1 -> alargada -2..0 -> chunks -1 e 0
            int[] n = caixa(-1, 100, -1, -1, 100, -1);
            v.igual(Motivo.CHUNKS, d(x, A, n, sul, 0, new Mundo(false).com(-1, -1)), "x=-1: alargada toca x=0 (chunk 0): recusado so com o chunk -1");
            v.igual(Motivo.PASSA, d(x, A, n, sul, 0, new Mundo(false).comRetangulo(-1, -1, 0, 0)), "x=-1,z=-1: chunks (-1..0, -1..0) carregados passa");
            // exigirChunks desligado: nao consulta nada
            DecisaoShift sem = new DecisaoShift(0, true, 4096, true, false, new Relogio(0));
            Mundo vazio = new Mundo(false);
            v.igual(Motivo.PASSA, d(sem, A, c, sul, 5, vazio), "interruptor dos chunks off: passa mesmo sem nenhum chunk carregado");
            v.igual(0, vazio.consultas, "interruptor dos chunks off: nenhuma consulta ao mapa");
            // teto de consultas: caixa de 128 x 128 chunks passa, mais um bloco e recusado sem consultar
            DecisaoShift grande = new DecisaoShift(0, true, 0, true, true, new Relogio(0));
            Mundo todos = new Mundo(true);
            v.igual(Motivo.PASSA, d(grande, A, caixa(16, 100, 16, 2046, 100, 2046), sul, 0, todos), "128 x 128 chunks (16384, o teto) passa");
            v.igual(2 * 128 * 128, todos.consultas, "consultou os 16384 chunks da origem e os 16384 do destino (iguais, com dist 0)");
            Mundo todos2 = new Mundo(true);
            v.igual(Motivo.CHUNKS, d(grande, A, caixa(16, 100, 16, 2047, 100, 2046), sul, 0, todos2), "129 x 128 chunks (acima do teto) e recusado");
            v.igual(0, todos2.consultas, "acima do teto: recusa sem consultar o mapa");
            Mundo todos3 = new Mundo(true);
            v.igual(Motivo.CHUNKS, d(grande, A, caixa(-30000000, 0, -30000000, 30000000, 255, 30000000), sul, 1, todos3), "caixa do mundo com volume sem limite: recusada por chunks, sem varrer");
            v.igual(0, todos3.consultas, "caixa do mundo: zero consultas");
            // destino longe (checagem de distancia desligada): coordenadas de chunk dentro de int, nao carregado
            DecisaoShift longe = new DecisaoShift(0, false, 4096, false, true, new Relogio(0));
            v.igual(Motivo.CHUNKS, d(longe, A, c, DIRECOES[5], Integer.MAX_VALUE, new Mundo(false).com(6, 6)), "destino a 2 bilhoes de blocos: chunk nao carregado, sem estouro");
            v.igual(Motivo.PASSA, d(longe, A, caixa(Integer.MAX_VALUE - 5, 100, 100, Integer.MAX_VALUE - 1, 100, 100), DIRECOES[5], Integer.MAX_VALUE, new Mundo(true)),
                    "caixa em x perto de Integer.MAX_VALUE deslocada Integer.MAX_VALUE: a conta em long nao estoura (mundo todo carregado passa)");
            v.igual(Motivo.CHUNKS, d(longe, A, caixa(Integer.MAX_VALUE - 5, 100, 100, Integer.MAX_VALUE - 1, 100, 100), DIRECOES[5], Integer.MAX_VALUE, new Mundo(false)),
                    "a mesma caixa com nenhum chunk carregado: recusada");
        }

        // ============================================================ ordem das checagens
        System.out.println("-- ordem das checagens");
        {
            Relogio r = new Relogio(0);
            DecisaoShift x = padrao(r);
            Mundo ok = new Mundo(true);
            int[] pequena = caixa(0, 100, 0, 3, 103, 3);
            v.igual(Motivo.PASSA, d(x, A, pequena, sul, 5, ok), "A passa (registra a hora)");
            // pedido ruim em tudo: distancia 99, volume grande, destino fora de y, chunks nao carregados
            int[] pessima = caixa(0, 200, 0, 100, 250, 100);
            Mundo nada = new Mundo(false);
            v.igual(Motivo.INTERVALO, d(x, A, pessima, cima, 99, nada), "falha em tudo, mas o intervalo vem primeiro");
            r.ms(1000);
            v.igual(Motivo.DISTANCIA, d(x, A, pessima, cima, 99, nada), "depois do intervalo: a distancia vem em segundo");
            v.igual(Motivo.VOLUME, d(x, A, pessima, cima, 5, nada), "distancia certa: o volume vem em terceiro");
            int[] altaPouca = caixa(0, 200, 0, 3, 250, 3);
            v.igual(Motivo.ALTURA, d(x, A, altaPouca, cima, 10, nada), "volume certo: a altura vem em quarto");
            v.igual(0, nada.consultas, "ate aqui nenhuma consulta ao mapa de chunks");
            v.igual(Motivo.CHUNKS, d(x, A, altaPouca, cima, 5, nada), "altura certa: os chunks vem por ultimo");
            v.igual(Motivo.PASSA, d(x, A, altaPouca, cima, 5, new Mundo(true)), "tudo certo: passa");
            // contadores
            v.igual(7L, x.chamadas(), "contador: 7 chamadas decididas");
            v.igual(2L, x.passaram(), "contador: 2 aceitas");
            v.igual(1L, x.recusas(Motivo.INTERVALO), "contador: 1 por intervalo");
            v.igual(1L, x.recusas(Motivo.DISTANCIA), "contador: 1 por distancia");
            v.igual(1L, x.recusas(Motivo.VOLUME), "contador: 1 por volume");
            v.igual(1L, x.recusas(Motivo.ALTURA), "contador: 1 por altura");
            v.igual(1L, x.recusas(Motivo.CHUNKS), "contador: 1 por chunks");
            v.igual(5L, x.recusasTotal(), "contador: 5 recusas no total");
        }

        // ============================================================ excecao do mapa de chunks
        System.out.println("-- excecao vinda do mapa de chunks");
        {
            Relogio r = new Relogio(0);
            DecisaoShift x = padrao(r);
            Mundo quebrado = new Mundo(true);
            quebrado.lancar = new IllegalStateException("mapa quebrado");
            boolean lancou = false;
            try {
                d(x, A, caixa(0, 100, 0, 3, 103, 3), sul, 5, quebrado);
            } catch (IllegalStateException e) {
                lancou = true;
            }
            v.sim(lancou, "a excecao do mapa de chunks sai de decidir (o gancho a captura e segue pelo original)");
            v.igual(0L, x.passaram() + x.recusasTotal(), "pedido que deu excecao nao conta como aceito nem recusado");
            v.igual(1L, x.chamadas(), "mas conta como chamada");
            v.igual(0, x.jogadoresGuardados(), "e nao guarda a hora (o jogador nao fica barrado por causa de um erro nosso)");
            quebrado.lancar = null;
            v.igual(Motivo.PASSA, d(x, A, caixa(0, 100, 0, 3, 103, 3), sul, 5, quebrado), "passado o erro, o proximo pedido passa");
        }

        // ============================================================ construtor
        System.out.println("-- construtor");
        {
            boolean a1 = false;
            try {
                new DecisaoShift(-1, true, 4096, true, true);
            } catch (IllegalArgumentException e) {
                a1 = true;
            }
            v.sim(a1, "intervalo negativo e recusado pelo construtor");
            boolean a2 = false;
            try {
                new DecisaoShift(1000, true, -1, true, true);
            } catch (IllegalArgumentException e) {
                a2 = true;
            }
            v.sim(a2, "volume negativo e recusado pelo construtor");
            DecisaoShift real = new DecisaoShift(1000, true, 4096, true, true); // relogio do sistema
            Mundo todos = new Mundo(true);
            v.igual(Motivo.PASSA, d(real, A, caixa(0, 100, 0, 3, 103, 3), sul, 5, todos), "relogio do sistema: primeira passa");
            v.igual(Motivo.INTERVALO, d(real, A, caixa(0, 100, 0, 3, 103, 3), sul, 5, todos), "relogio do sistema: segunda logo depois recusada");
        }

        // ============================================================ varias threads
        System.out.println("-- varias threads");
        {
            final DecisaoShift x = new DecisaoShift(0, true, 4096, true, false, new Relogio(0));
            final int[] c = caixa(0, 100, 0, 3, 103, 3);
            final int[] c2 = caixa(0, 100, 0, 100, 103, 100); // volume 10201: recusado
            final AtomicLong erros = new AtomicLong();
            Thread[] ts = new Thread[8];
            for (int i = 0; i < ts.length; i++) {
                final int n = i;
                ts[i] = new Thread() {
                    @Override
                    public void run() {
                        try {
                            for (int k = 0; k < 20000; k++) {
                                d(x, new UUID(9, n), k % 2 == 0 ? c : c2, DIRECOES[3], 5, new Mundo(true));
                            }
                        } catch (Throwable t) {
                            erros.incrementAndGet();
                        }
                    }
                };
                ts[i].start();
            }
            for (Thread t : ts) {
                t.join();
            }
            v.igual(0L, erros.get(), "8 threads x 20000 chamadas: nenhuma excecao");
            v.igual(160000L, x.chamadas(), "160000 chamadas contadas");
            v.igual(80000L, x.passaram(), "80000 aceitas");
            v.igual(80000L, x.recusas(Motivo.VOLUME), "80000 recusadas por volume");
        }

        v.fim();
    }

    /** Usado so para dizer ao compilador que o resultado foi lido. */
    private static void v2(Motivo m) {
        if (m == null) {
            throw new IllegalStateException();
        }
    }
}
