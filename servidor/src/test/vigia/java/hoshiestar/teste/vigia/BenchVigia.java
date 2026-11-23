package hoshiestar.teste.vigia;

import hoshiestar.api.Relogio;
import hoshiestar.teste.Verifica;
import hoshiestar.vigia.Marcas;

/**
 * Custo do caminho quente do vigia (INDICACAO, nao medicao de servidor): ns por marcacao de
 * entidade/tile (Marcas.entidade(x) antes e Marcas.entidade(null) depois de cada "atualizacao"), e
 * ns por par inicioTick/fimTick. Compara um laco com as marcacoes contra o mesmo laco sem elas,
 * melhor de 7 rodadas, depois de aquecer o JIT. Falha so se o custo for absurdo (&gt; 100 ns por marcacao).
 */
public final class BenchVigia {

    /**
     * "Entidade" com tres subclasses misturadas no vetor: a chamada de atualizacao fica megamorfica (o JIT
     * nao a embute), como entity.onUpdate()/tile.updateEntity() no laco real. Assim as duas escritas das
     * marcacoes nao podem ser fundidas nem apagadas pelo JIT.
     */
    abstract static class Caixa {
        int v;

        Caixa(int v) {
            this.v = v;
        }

        abstract int atualizar();
    }

    static final class C1 extends Caixa {
        C1(int v) {
            super(v);
        }

        @Override
        int atualizar() {
            v = v * 1103515245 + 12345;
            return v >>> 16;
        }
    }

    static final class C2 extends Caixa {
        C2(int v) {
            super(v);
        }

        @Override
        int atualizar() {
            v = v * 22695477 + 1;
            return v >>> 15;
        }
    }

    static final class C3 extends Caixa {
        C3(int v) {
            super(v);
        }

        @Override
        int atualizar() {
            v = v ^ (v << 13) ^ 7;
            return v >>> 17;
        }
    }

    static volatile long sumidouro;

    public static void main(String[] args) {
        Verifica v = new Verifica("BenchVigia");
        Caixa[] objs = new Caixa[4096];
        for (int i = 0; i < objs.length; i++) {
            objs[i] = i % 3 == 0 ? new C1(i) : i % 3 == 1 ? new C2(i) : new C3(i);
        }
        int rodadas = 2000;
        for (int aq = 0; aq < 5; aq++) {
            sem(objs, 200);
            com(objs, 200);
            ticks(20000);
        }
        long melhorSem = Long.MAX_VALUE;
        long melhorCom = Long.MAX_VALUE;
        for (int r = 0; r < 7; r++) {
            long t0 = System.nanoTime();
            sem(objs, rodadas);
            long t1 = System.nanoTime();
            com(objs, rodadas);
            long t2 = System.nanoTime();
            melhorSem = Math.min(melhorSem, t1 - t0);
            melhorCom = Math.min(melhorCom, t2 - t1);
        }
        long chamadas = (long) objs.length * rodadas;
        double nsSem = (double) melhorSem / chamadas;
        double nsCom = (double) melhorCom / chamadas;
        double porMarca = Math.max(0.0, (nsCom - nsSem) / 2.0);
        long melhorTick = Long.MAX_VALUE;
        int nTicks = 200000;
        for (int r = 0; r < 7; r++) {
            long t0 = System.nanoTime();
            ticks(nTicks);
            melhorTick = Math.min(melhorTick, System.nanoTime() - t0);
        }
        double porTick = (double) melhorTick / nTicks;
        System.out.printf("  bench (%s %s): laco sem marcas %.2f ns/atualizacao, com marcas %.2f ns/atualizacao -> ~%.2f ns por marcacao (2 por entidade/tile); par inicioTick+fimTick %.1f ns por tick%n",
                System.getProperty("java.vm.name"), System.getProperty("java.version"), nsSem, nsCom, porMarca, porTick);
        System.out.printf("  bench: exemplo de conta: 2500 entidades + 20000 tiles por tick = 45000 marcacoes x %.2f ns = %.3f ms por tick (indicacao)%n",
                porMarca, 45000 * porMarca / 1e6);
        v.sim(Marcas.LIGADO, "bench: marcas ligadas (o custo medido e o do vigia ligado)");
        v.sim(porMarca < 100.0, "bench: custo por marcacao nao e absurdo (< 100 ns): " + porMarca);
        v.fim();
    }

    static void sem(Caixa[] objs, int rodadas) {
        long acc = 0;
        for (int r = 0; r < rodadas; r++) {
            for (Caixa c : objs) {
                acc += c.atualizar();
            }
        }
        sumidouro = acc;
    }

    static void com(Caixa[] objs, int rodadas) {
        long acc = 0;
        for (int r = 0; r < rodadas; r++) {
            for (Caixa c : objs) {
                Marcas.entidade(c);
                acc += c.atualizar();
                Marcas.entidade(null);
            }
        }
        sumidouro = acc;
    }

    static void ticks(int n) {
        for (int i = 0; i < n; i++) {
            ++Relogio.ticks;
            Marcas.inicioTick();
            Marcas.fimTick();
        }
    }
}
