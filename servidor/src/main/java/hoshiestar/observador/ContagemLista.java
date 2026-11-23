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
package hoshiestar.observador;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * As contagens do prototipo da lista de tiles ({@link ListaObservada}) numa observacao. Fica dentro do {@link Nucleo}
 * (uma por observacao). Tudo e da thread do servidor, menos {@link #outra}/{@link #outraTotal}/{@link #outraSorteio}
 * (atomicos, de qualquer thread).
 */
final class ContagemLista {

    /** Teto de linhas da tabela de amostras. */
    static final int TETO_AMOSTRAS = 5000;
    /** No maximo tantas amostras de pilha por tick (alem das do nucleo). */
    static final int MAX_AMOSTRAS_TICK = 20;
    /** Custo estimado (ns) de uma chamada contada, para a valvula. */
    static final long CUSTO_CHAMADA_NS = 15;

    /** Contextos que existem tambem na fase paralela de um Degrau B/C (codigo de uma regiao). */
    static boolean contextoDeRegiao(int ct) {
        return ct == Nucleo.C_TILE || ct == Nucleo.C_ENTIDADE || ct == Nucleo.C_JOGADOR_REDE || ct == Nucleo.C_TICK_AGENDADO
                || ct == Nucleo.C_TICK_ALEATORIO || ct == Nucleo.C_EVENTO_BLOCO;
    }

    // ---- thread do servidor ----
    /** Chamadas do proprio World (trechos marcados), por operacao. */
    final long[] dentro = new long[ListaObservada.NOPS];
    /** Chamadas de fora, por operacao * 16 + tipo de contexto. */
    final long[] fora = new long[ListaObservada.NOPS * 16];
    /** Chamadas de fora numa lista aposentada (alguem guardou a referencia antiga), por operacao. */
    final long[] velha = new long[ListaObservada.NOPS];
    /** Amostras: chave = op (6 bits) | contexto (4) | mod do contexto (12) | mod da pilha (12) | chamador (12). */
    final TabelaContagem amostras = new TabelaContagem(256, TETO_AMOSTRAS);
    /** Nomes "classe.metodo" dos chamadores (o mesmo registro de nomes dos mods). */
    final Mods chamadores = new Mods();
    long trocasPeloMundo;
    /** Ticks x mundos em que o campo do mundo NAO era uma lista nossa (alguem trocou o objeto). */
    long trocasDeFora;
    long mundosConferidos;
    final Map<String, Long> classesNoCampo = new LinkedHashMap<String, Long>();
    long estourosMarca;
    long amostrasFeitas;
    long pilhaNs;
    long sorteio;
    int amostrasTick;
    long chamadasTick;
    long pilhaNsTick;

    // ---- outras threads ----
    /** [op * Mods.PASSO + espaco]; espaco 0 = sem amostra de pilha, 1 + id do mod = amostrado. */
    final AtomicLongArray outra = new AtomicLongArray(ListaObservada.NOPS * Mods.PASSO);
    final AtomicLong outraTotal = new AtomicLong();
    private final AtomicLong outraSorteio = new AtomicLong();

    void inicioTick() {
        amostrasTick = 0;
        chamadasTick = 0;
        pilhaNsTick = 0;
    }

    /** Custo estimado deste tick (ns), para a valvula do nucleo. */
    long custoTick() {
        return chamadasTick * CUSTO_CHAMADA_NS + pilhaNsTick;
    }

    /** Uma chamada de fora, na thread do servidor. */
    void contarFora(Nucleo n, int op) {
        int ct = n.contextoAtual() & 15;
        fora[op * 16 + ct]++;
        boolean sortear;
        if (ListaObservada.modifica(op)) {
            sortear = true; // modificacao de fora e o que decide: sempre amostra (ate o teto por tick)
        } else {
            sortear = ++sorteio % n.amostraPilha == 0;
        }
        if (sortear && amostrasTick < MAX_AMOSTRAS_TICK) {
            long t0 = System.nanoTime();
            StackTraceElement[] p = new Throwable().getStackTrace();
            int mp = n.modDaPilha(p);
            int ch = chamadores.id(chamador(p));
            int mc = n.modDoContextoAtual();
            long d = System.nanoTime() - t0;
            pilhaNs += d;
            pilhaNsTick += d;
            amostrasTick++;
            amostrasFeitas++;
            amostras.somar(chave(op, ct, mc, mp, ch));
        }
    }

    static long chave(int op, int ct, int mc, int mp, int ch) {
        return (long) (op & 63) | ((long) (ct & 15) << 6) | ((long) (mc & 0xFFF) << 10) | ((long) (mp & 0xFFF) << 22)
                | ((long) (ch & 0xFFF) << 34);
    }

    /** O primeiro quadro que nao e da lista (nossa, do Crucible ou do JDK): "classe.metodo". */
    static String chamador(StackTraceElement[] p) {
        if (p == null) {
            return null;
        }
        for (StackTraceElement e : p) {
            String c = e.getClassName();
            if (c == null || c.startsWith("hoshiestar.observador.") || c.startsWith("java.")
                    || c.startsWith("io.github.crucible.util.HashedArrayList")) {
                continue;
            }
            return c + "." + e.getMethodName();
        }
        return null;
    }

    /** Uma chamada de outra thread (seguro entre threads). */
    void contarOutraThread(Nucleo n, int op) {
        outraTotal.incrementAndGet();
        int espaco = 0;
        if ((outraSorteio.incrementAndGet() & 63) == 1) {
            espaco = 1 + n.modDaPilha(new Throwable().getStackTrace());
        }
        outra.incrementAndGet(op * Mods.PASSO + espaco);
    }

    /** Conferencia do fim do tick: o campo de um mundo nao e uma lista nossa. */
    void campoTrocado(String classe) {
        trocasDeFora++;
        String c = classe == null ? "null" : classe;
        Long v = classesNoCampo.get(c);
        if (v != null || classesNoCampo.size() < 20) {
            classesNoCampo.put(c, v == null ? 1L : v + 1L);
        }
    }

    // =====================================================================================================
    // Resumo para o relatorio e o criterio do plano (secao 4)
    // =====================================================================================================

    long totalDentro() {
        long s = 0;
        for (long v : dentro) {
            s += v;
        }
        return s;
    }

    long totalFora() {
        long s = 0;
        for (long v : fora) {
            s += v;
        }
        return s;
    }

    long foraPorOp(int op) {
        long s = 0;
        for (int c = 0; c < 16; c++) {
            s += fora[op * 16 + c];
        }
        return s;
    }

    long totalVelha() {
        long s = 0;
        for (long v : velha) {
            s += v;
        }
        return s;
    }

    /** Indicadores do criterio: [fora, fora em contexto serial (conhecido), fora em contexto de regiao, modificacoes de
     *  fora, modificacoes de fora em contexto de regiao, leituras/iteracoes de fora em contexto de regiao, fora SEM contexto
     *  conhecido ({@link Nucleo#C_OUTRO}: nem serial nem de regiao)]. */
    long[] indicadores() {
        long[] r = new long[7];
        for (int op = 0; op < ListaObservada.NOPS; op++) {
            boolean mod = ListaObservada.modifica(op);
            for (int c = 0; c < 16; c++) {
                long v = fora[op * 16 + c];
                if (v == 0) {
                    continue;
                }
                r[0] += v;
                boolean reg = contextoDeRegiao(c);
                if (reg) {
                    r[2] += v;
                } else if (c == Nucleo.C_OUTRO) {
                    r[6] += v; // contexto desconhecido: nao prova que e serial
                } else {
                    r[1] += v;
                }
                if (mod) {
                    r[3] += v;
                    if (reg) {
                        r[4] += v;
                    }
                } else if (reg) {
                    r[5] += v;
                }
            }
        }
        return r;
    }

    /** Mods (pela pilha; sem pilha, pelo contexto) vistos nas amostras de fora em contexto de regiao. */
    List<String> modsEmContextoDeRegiao(Nucleo n, boolean soModificacao) {
        List<String> r = new ArrayList<String>();
        for (int i = 0; i < amostras.capacidade(); i++) {
            if (!amostras.ocupado(i)) {
                continue;
            }
            long k = amostras.chave(i);
            int op = (int) (k & 63);
            int ct = (int) ((k >> 6) & 15);
            if (!contextoDeRegiao(ct) || (soModificacao && !ListaObservada.modifica(op))) {
                continue;
            }
            int mp = (int) ((k >> 22) & 0xFFF);
            int mc = (int) ((k >> 10) & 0xFFF);
            String nome = mp != 0 ? n.mods.nome(mp) : mc != 0 ? n.mods.nome(mc) + " (pelo contexto)" : "(servidor/sem mod)";
            if (!r.contains(nome)) {
                r.add(nome);
            }
        }
        return r;
    }

    /**
     * A indicacao automatica pelo criterio do plano (secao 4). E so indicacao: a decisao fica no relatorio do portao,
     * que confere se o mod "da para por em serie" e se a carga era representativa.
     */
    String indicacao(Nucleo n) {
        long[] x = indicadores();
        if (trocasDeFora > 0) {
            return "criterio 1 (DESCARTA): o campo loadedTileEntityList foi trocado por outro objeto fora do World ("
                    + trocasDeFora + " vezes); a lista por regiao teria de mentir para quem trocou";
        }
        if (x[4] > 0) {
            return "criterio 1 (DESCARTA, a confirmar): " + x[4] + " modificacoes de fora em contexto de regiao, de "
                    + modsEmContextoDeRegiao(n, true) + "; confira no portao se esses mods dao para por em serie";
        }
        if (x[0] == 0) {
            return "criterio 2 (DESNECESSARIA no Degrau B): nenhum acesso de fora";
        }
        if (x[6] * 2 > x[0]) {
            return "inconclusivo: " + semContexto(x) + "; sem contexto conhecido nao da para dizer se o acesso e serial ou"
                    + " de regiao (veja a pilha de chamadores nas amostras)";
        }
        long conhecidos = x[0] - x[6];
        if (x[1] * 100 >= conhecidos * 99) {
            return "criterio 2 (DESNECESSARIA no Degrau B): " + String.format(java.util.Locale.ROOT,
                    "%.2f%% dos acessos de fora com contexto conhecido vem de contextos seriais", 100.0 * x[1] / conhecidos)
                    + "; " + semContexto(x) + " (nao entram na conta)";
        }
        List<String> mods = modsEmContextoDeRegiao(n, false);
        if (mods.size() <= 5) {
            return "criterio 3 (PROVA a hipotese): os acessos de fora em contexto de regiao (" + x[2]
                    + ") sao so leitura/iteracao e vem de " + mods.size() + " mod(s): " + mods + "; " + semContexto(x);
        }
        return "inconclusivo: acessos de fora em contexto de regiao so de leitura, mas de " + mods.size()
                + " mods (mais de 5): " + mods + "; " + semContexto(x);
    }

    /** A parte das chamadas de fora feita sem contexto conhecido, para o relatorio e a indicacao. */
    static String semContexto(long[] x) {
        return x[6] + " de " + x[0] + " chamadas de fora (" + String.format(java.util.Locale.ROOT, "%.2f",
                x[0] == 0 ? 0.0 : 100.0 * x[6] / x[0]) + "%) sem contexto conhecido";
    }
}
