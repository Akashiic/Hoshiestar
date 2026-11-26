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
package hoshiestar.orcamento;

import hoshiestar.teste.Verifica;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Casos da revisao independente da Etapa 2.5 (etapa25/rev/revisao.md, achados 1 a 5), no mundo falso do TesteCorte.
 * Cada caso falhava no codigo antes da correcao do achado. Relogio falso: numero de simulacao, nao medicao de servidor.
 */
public final class TesteAdversario {

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteAdversario");
        try {
            nuncaVolta(v);
            fasesAlinhadas(v, true);
            fasesAlinhadas(v, false);
            tetoFome(v, true);
            tetoFome(v, false);
            reservaContraModelo(v, 300000);
            alertaPerdido(v, false);
            alertaPerdido(v, true);
            filaSemDono(v);
        } catch (Throwable t) {
            v.erro("excecao", t);
        }
        v.fim();
    }

    /**
     * Achado 1 (plano 2: "servidor folgado: os cortados descem, ate 1"). A custa 25 ms por rodada; com o resto pesado ela
     * e cortada; depois o resto fica leve (servidor a ~14 ms; sem corte seria ~30 ms, abaixo de 80% de 45). A volta a
     * N = 1 e, sem corte, o servidor fica abaixo do alvo (nao volta a cortar: nao oscila).
     */
    static void nuncaVolta(Verifica v) {
        TesteCorte.Mundo m = TesteCorte.tresBases(true, true, 25.0);
        m.baseMs = 30.0;
        m.ticks(3000);
        Retrato.Regiao a = m.n.retrato().emBloco(0, 20, 20);
        int nPesado = a == null ? -1 : a.n;
        m.baseMs = 1.0;
        m.ticks(20000);
        a = m.n.retrato().emBloco(0, 20, 20);
        int nLeve = a == null ? -1 : a.n;
        int maxDepois = 0;
        for (int i = 0; i < 50; i++) {
            m.ticks(100);
            Retrato.Regiao g = m.n.retrato().emBloco(0, 20, 20);
            maxDepois = Math.max(maxDepois, g == null ? -1 : g.n);
        }
        System.out.println("  info  nuncaVolta: N com o resto pesado = " + nPesado + "; N depois de 20000 ticks com o servidor a "
                + String.format("%.1f", m.n.retrato().servidorMediaMs) + " ms = " + nLeve);
        v.sim(nPesado > 1, "(volta) A entrou em corte com o servidor pesado: N=" + nPesado);
        v.sim(nLeve == 1, "(volta) A volta a N=1 com o servidor folgado e custo sem corte abaixo do alvo: N=" + nLeve);
        v.igual(1, maxDepois, "(volta) depois de voltar, A nao volta a ser cortada (servidor sem corte abaixo do alvo)");
    }

    /**
     * Achado 2: 4 bases pesadas (150 ms por rodada) entram em corte na mesma avaliacao. Antes, com N = 1 (e no modo medir)
     * o acumulador ficava em 0 e todas subiam para N = 25 com a mesma fase: rodavam sempre no mesmo tick (o tick da rodada
     * custava a soma das 4). {@code cortarDesdeOInicio} false: 3000 ticks no modo medir (N calculado, sem corte) e depois o
     * comando muda para cortar.
     */
    static void fasesAlinhadas(Verifica v, boolean cortarDesdeOInicio) {
        TesteCorte.Mundo m = new TesteCorte.Mundo(Parametros.padrao(), true, cortarDesdeOInicio);
        TesteCorte.Tile[] t = new TesteCorte.Tile[4];
        for (int r = 0; r < 4; r++) {
            int bx = r * 20 * 256;
            m.chunk(bx >> 4, 1);
            t[r] = new TesteCorte.Tile("p" + r, bx + 20, 20, 150.0);
            m.tiles.add(t[r]);
        }
        m.ticks(3000);
        if (!cortarDesdeOInicio) {
            m.n.pedirModo(true);
            m.ticks(200);
        }
        int[] antes = new int[4];
        for (int r = 0; r < 4; r++) {
            antes[r] = t[r].ticks;
        }
        int juntas = 0, rodadas = 0, ticks = 2000;
        for (int k = 0; k < ticks; k++) {
            m.tick();
            int q = 0;
            for (int r = 0; r < 4; r++) {
                if (t[r].ticks > antes[r]) {
                    q++;
                }
                antes[r] = t[r].ticks;
            }
            if (q > 0) {
                rodadas++;
            }
            if (q > 1) {
                juntas++;
            }
        }
        StringBuilder ns = new StringBuilder();
        for (Retrato.Regiao g : m.n.retrato().regioes) {
            ns.append(g.n).append(' ');
        }
        String nome = cortarDesdeOInicio ? "cortar" : "medir->cortar";
        System.out.println("  info  fasesAlinhadas(" + nome + "): N=" + ns + "; ticks com rodada " + rodadas
                + "; ticks com 2+ bases pesadas juntas " + juntas + "; max do servidor "
                + String.format("%.0f", m.n.retrato().servidorMaxMs) + " ms");
        v.sim(rodadas > 0, "(fase, " + nome + ") as bases cortadas rodam: " + rodadas);
        v.sim(juntas * 4 < rodadas, "(fase, " + nome + ") bases cortadas com o mesmo N nao rodam sempre no mesmo tick: juntas="
                + juntas + " de " + rodadas);
    }

    /**
     * Achado 3, teto do CraftBukkit (1000 por tick): A cortada (N=25) com 1536 blocos que se reagendam a +20 (maquinas).
     * Sem corte: ~77 por tick, ninguem espera. Com corte: na rodada de A ha 1536 vencidas, todas mais velhas que as de B;
     * antes A ocupava o teto inteiro e o relogio de B (+1, regiao NAO cortada) ficava sem rodar naquele tick. Confere
     * tambem que nenhum bloco de A se perde (todos continuam rodando).
     */
    static void tetoFome(Verifica v, boolean cortar) {
        TesteCorte.Mundo m = TesteCorte.tresBases(true, cortar, 60.0);
        int nBlocos = 0;
        final int base = 5000;
        int[][] pos = new int[1536][];
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 32; x++) {
                for (int z = 16; z < 32; z++) {
                    pos[nBlocos] = new int[] {x, y, z};
                    m.agendar(x, y, z, "maq", 1 + (nBlocos % 20), 0, base + nBlocos);
                    nBlocos++;
                }
            }
        }
        int[] visto = new int[nBlocos];
        int bAntes = 0, faltasB = 0, ticksMedidos = 0, maxPuxadas = 0;
        for (int k = 0; k < 3000; k++) {
            m.tick();
            for (int i = 0; i < nBlocos; i++) {
                Integer c = m.execAg.get((long) (base + i));
                int ci = c == null ? 0 : c;
                if (ci > visto[i]) {
                    visto[i] = ci;
                    m.agendar(pos[i][0], pos[i][1], pos[i][2], "maq", 20, 0, base + i);
                }
            }
            Integer b = m.execAg.get(1001L);
            int bi = b == null ? 0 : b;
            if (k >= 500) {
                ticksMedidos++;
                if (bi == bAntes) {
                    faltasB++;
                }
                maxPuxadas = Math.max(maxPuxadas, m.puxadasNoTick);
            }
            bAntes = bi;
        }
        int minA = Integer.MAX_VALUE;
        for (int i = 0; i < nBlocos; i++) {
            minA = Math.min(minA, visto[i]);
        }
        Retrato.Regiao a = m.n.retrato().emBloco(0, 20, 20);
        System.out.println("  info  tetoFome(cortar=" + cortar + "): N de A=" + (a == null ? -1 : a.n) + "; ticks medidos "
                + ticksMedidos + "; ticks em que o relogio de B (nao cortada) NAO rodou: " + faltasB + "; max puxadas "
                + maxPuxadas + "; menor numero de execucoes de um bloco de A " + minA);
        v.sim(faltasB == 0, "(teto, cortar=" + cortar + ") relogio da regiao B (nao cortada) roda todo tick: faltas=" + faltasB);
        v.sim(maxPuxadas <= 1000, "(teto, cortar=" + cortar + ") o teto de 1000 vale: " + maxPuxadas);
        v.sim(minA >= 2, "(teto, cortar=" + cortar + ") todo bloco de A continua rodando (nenhum perdido): menor " + minA);
    }

    /**
     * Achado 3: a selecao com reserva contra um modelo independente. Modelo: em ordem, ate o primeiro nao vencido; as de
     * regiao que nao roda ficam; as normais ate o teto; as do acumulo (regiao cortada na rodada) so na sobra, as mais velhas
     * primeiro; a saida na ordem da fila. A fila nunca muda na selecao.
     */
    static void reservaContraModelo(Verifica v, int operacoes) {
        Random r = new Random(20261126L);
        TreeSet<TesteCorte.Ag> fila = new TreeSet<TesteCorte.Ag>();
        Set<TesteCorte.Ag> conj = new HashSet<TesteCorte.Ag>();
        long agora = 0;
        int erros = 0, comAcumulo = 0, cortadasPelaReserva = 0;
        final boolean[] roda = new boolean[4];
        final boolean[] acum = new boolean[4];
        PuxadaAgendada.Decisor dec = new PuxadaAgendada.Decisor() {
            int ultimo = -1;

            @Override
            public boolean roda(int x, int z) {
                ultimo = (x >> 8) & 3;
                return roda[ultimo];
            }

            @Override
            public boolean acumulada(int x, int z) {
                return acum[ultimo];
            }
        };
        for (int op = 0; op < operacoes; op++) {
            if (r.nextInt(10) < 6) {
                TesteCorte.Ag e = new TesteCorte.Ag(r.nextInt(4) * 256 + r.nextInt(16), 64, r.nextInt(16), "b" + r.nextInt(3),
                        agora + r.nextInt(30), r.nextInt(3) - 1, op);
                if (!conj.contains(e)) {
                    conj.add(e);
                    fila.add(e);
                }
                continue;
            }
            agora++;
            for (int k = 0; k < 4; k++) {
                roda[k] = r.nextInt(3) > 0;
                acum[k] = roda[k] && r.nextInt(3) == 0;
            }
            int teto = r.nextBoolean() ? PuxadaAgendada.teto(fila.size()) : 1 + r.nextInt(40);
            List<TesteCorte.Ag> ordem = new ArrayList<TesteCorte.Ag>(fila);
            Collections.sort(ordem);
            List<TesteCorte.Ag> normais = new ArrayList<TesteCorte.Ag>(), acumulo = new ArrayList<TesteCorte.Ag>();
            for (TesteCorte.Ag e : ordem) {
                if (e.t > agora) {
                    break;
                }
                int g = (e.x >> 8) & 3;
                if (!roda[g]) {
                    continue;
                }
                if (acum[g]) {
                    acumulo.add(e);
                } else if (normais.size() < teto) {
                    normais.add(e);
                }
            }
            int sobra = teto - normais.size();
            Set<TesteCorte.Ag> escolhidas = new HashSet<TesteCorte.Ag>(normais);
            for (int i = 0; i < Math.min(sobra, acumulo.size()); i++) {
                escolhidas.add(acumulo.get(i));
            }
            if (acumulo.size() > Math.max(0, sobra)) {
                cortadasPelaReserva++;
            }
            if (!acumulo.isEmpty()) {
                comAcumulo++;
            }
            List<TesteCorte.Ag> esperado = new ArrayList<TesteCorte.Ag>();
            for (TesteCorte.Ag e : ordem) {
                if (escolhidas.contains(e)) {
                    esperado.add(e);
                }
            }
            int tamAntes = fila.size();
            List<Object> saida = new ArrayList<Object>();
            int n = PuxadaAgendada.selecionar(fila, teto, agora, TesteCorte.ACESSO, dec, saida);
            if (!saida.equals(esperado) || n != saida.size() || n > teto || fila.size() != tamAntes) {
                erros++;
            }
            for (Object o : saida) {
                fila.remove(o);
                conj.remove(o);
            }
        }
        System.out.println("  info  reservaContraModelo: " + operacoes + " operacoes; selecoes com acumulo " + comAcumulo
                + "; em que a reserva segurou parte do acumulo " + cortadasPelaReserva);
        v.sim(comAcumulo > 1000 && cortadasPelaReserva > 100, "(reserva) o caso aparece no sorteio");
        v.igual(0, erros, "(reserva) selecao igual ao modelo (ordem, teto, sobra, fila intacta)");
    }

    /**
     * Achado 4: 12 bases pesadas (30 ms, acima de alerta_ms 25) ao mesmo tempo. O limitador global (10 por minuto) segura
     * alertas; antes, quem perdia o "comecou a pesar" nunca recebia (pesada ja ficava true). {@code cortar}: com corte,
     * tambem o "passou a ticar 1 a cada N" de cada uma tem de sair.
     */
    static void alertaPerdido(Verifica v, boolean cortar) {
        TesteCorte.Mundo m = new TesteCorte.Mundo(Parametros.padrao(), true, cortar);
        for (int r = 0; r < 12; r++) {
            int bx = r * 20 * 256;
            m.chunk(bx >> 4, 1);
            m.tiles.add(new TesteCorte.Tile("t" + r, bx + 20, 20, 30.0));
        }
        m.ticks(12000); // >= 10 min de relogio falso
        Set<String> pesadas = regioesCom(m, "comecou a pesar");
        Set<String> cortadas = regioesCom(m, "passou a ticar 1 a cada");
        int nCortadas = 0;
        for (Retrato.Regiao g : m.n.retrato().regioes) {
            if (g.n > 1) {
                nCortadas++;
            }
        }
        String nome = cortar ? "cortar" : "medir";
        System.out.println("  info  alertaPerdido(" + nome + "): 'comecou a pesar' em " + pesadas.size() + " de 12; 'passou a ticar' em "
                + cortadas.size() + " (regioes com N > 1: " + nCortadas + "); linhas de log " + m.logs.size());
        v.igual(12, pesadas.size(), "(alerta, " + nome + ") toda regiao pesada recebe 'comecou a pesar'");
        if (cortar) {
            v.sim(nCortadas > 0, "(alerta, cortar) ha regioes cortadas: " + nCortadas);
            v.igual(nCortadas, cortadas.size(), "(alerta, cortar) toda regiao cortada recebe 'passou a ticar 1 a cada N'");
        }
    }

    private static Set<String> regioesCom(TesteCorte.Mundo m, String texto) {
        Set<String> r = new HashSet<String>();
        for (String l : m.logs) {
            if (l.startsWith("[orcamento] regiao ") && l.contains(texto)) {
                int i = l.indexOf("regiao ");
                r.add(l.substring(i, l.indexOf(' ', i + 7)));
            }
        }
        return r;
    }

    /** Achado 5: com o nucleo desligado por erro, os pedidos do comando nao se acumulam na fila (ninguem a esvazia). */
    @SuppressWarnings("rawtypes")
    static void filaSemDono(Verifica v) throws Exception {
        TesteCorte.Mundo m = TesteCorte.tresBases(true, true, 2.0);
        m.ticks(5);
        m.n.pedirModo(false);
        m.n.pedirModo(true);
        m.n.falha(new RuntimeException("teste"));
        for (int i = 0; i < 100000; i++) {
            m.n.pedirModo(i % 2 == 0);
            m.n.pedirEspera(-1, 1.0);
        }
        m.ticks(5);
        Field f = Nucleo.class.getDeclaredField("pedidos");
        f.setAccessible(true);
        int tam = ((Queue) f.get(m.n)).size();
        System.out.println("  info  filaSemDono: pedidos na fila depois de desligar = " + tam);
        v.igual(0, tam, "(fila) pedidos nao se acumulam com o nucleo desligado");
        v.sim(!m.n.ativo(), "(fila) o nucleo segue desligado");
    }
}
