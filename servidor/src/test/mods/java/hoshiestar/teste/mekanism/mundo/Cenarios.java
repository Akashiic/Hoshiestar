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
package hoshiestar.teste.mekanism.mundo;

import hoshiestar.api.Relogio;
import hoshiestar.api.ThreadServidor;
import hoshiestar.mekanism.Mekanism;
import hoshiestar.mekanism.matriz.ComandoMatriz;
import hoshiestar.mekanism.matriz.MatrizInducao;
import hoshiestar.teste.Verifica;
import mekanism.api.Coord4D;
import mekanism.common.content.matrix.SynchronizedMatrixData;
import mekanism.common.tile.TileEntityInductionCasing;
import mekanism.common.tile.TileEntityInductionCell;
import net.minecraft.tileentity.TileEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Testes da correcao do Mekanism com as classes REAIS do Mekanism (do jar do pacote) e a classe de
 * logica REAL (do jar construido do servidor, nomes traduzidos como o FML faz). Roda dentro do class
 * loader de teste (ver TesteMekanism): aqui o Minecraft, o Mekanism e a logica sao "filhos"; os
 * contadores e o modo (hoshiestar.mekanism.Mekanism) e o Verifica vem do pai.
 *
 * <ol>
 * <li>cenarios deterministicos, cada um com as suas conferencias;</li>
 * <li>diferencial aleatorio: mesma sequencia de leituras, escritas e eventos em A (original) e B
 * (rapido), energia de cada celula comparada bit a bit depois de cada passo;</li>
 * <li>banco de medida (indicativo, mundo falso).</li>
 * </ol>
 * A mutacao (tirar cada protecao do CODIGO-FONTE da logica e mostrar que algum cenario falha) fica em
 * {@code TesteMutacao}, que roda estes mesmos cenarios ({@link #rodarParaMutacao}) com a logica
 * mutante num class loader novo. Os cenarios R1 a R5 vieram dos testes adversarios da revisao.
 */
public final class Cenarios {

    /** Conferencias de um cenario (sem contar no Verifica: quem decide e quem chama). */
    static final class Ch {
        final List<String> falhas = new ArrayList<String>();

        void sim(boolean c, String o) {
            if (!c) {
                falhas.add(o);
            }
        }
    }

    interface Cenario {
        void rodar(Ch ch) throws Exception;
    }

    private static final int OX = 12;
    private static final int OY = 64;
    private static final int OZ = 12;

    // ------------------------------------------------------------------ nomes dos cenarios

    static final String C_ORFA = "orfa-0J-remocao (ConfAdv)";
    static final String C_TROCA = "orfa-0J-troca crua (identidade na escrita)";
    static final String C_VER_TROCA = "verificar: troca crua e detectada por identidade (soma cega)";
    static final String C_VER_LIMPO = "verificar: leituras e escritas comparadas, nunca pelo caminho rapido";
    static final String C_VER_CONFADV = "verificar: ConfAdv com o mapa do mesmo tamanho (outra TE no mesmo chunk)";
    static final String C_CONFADV_TAM = "ConfAdv com o mapa do mesmo tamanho: escrita recusada pela identidade";
    static final String C_REENTRADA = "reentrada durante a reconstrucao";
    static final String C_CHUNK = "chunk descarregado no meio";
    static final String C_CHUNK_CRU = "chunk tirado do provedor sem onChunkUnload";
    static final String C_ZERAR = "referencias zeradas quando a validacao falha";
    static final String C_MESMA = "troca de celula por outra na mesma coordenada";
    static final String C_CRESCE = "estrutura que cresce e encolhe";
    static final String C_MUNDO = "mundo diferente";
    static final String C_THREAD = "chamada fora da thread do servidor";
    static final String C_ANTES = "relogio parado (antes do 1o tick)";
    static final String C_ALTURA = "altura fora de 0..255 (mundo fundo)";
    static final String C_ESPERA = "estrutura incompleta: nova tentativa so a cada 20 ticks";
    static final String C_RELOGIO = "troca crua e um tick (relogio)";
    static final String C_TAMANHO = "remocao crua com energia no mesmo tick (tamanho do mapa)";
    static final String C_MOVIDA = "celula movida para outra coordenada no mesmo chunk";
    static final String C_OUTRO_MUNDO = "celula levada para outro mundo (como o Spatial IO do AE2)";
    static final String C_TODAS = "R1 troca crua em cada posicao + escrita cheia (identidade em todas as posicoes)";
    static final String C_CRUZADA = "R2 reentrada cruzada (durante a reconstrucao de S1, um vizinho le e escreve S2)";
    static final String C_MORTA = "R3 celula invalidada que ficou no mapa + escrita no mesmo tick";
    static final String C_EIXO = "R4 celula movida num so eixo (x, y, z), mesmo chunk, mesmo tick";
    static final String C_TERCEIROS = "R5 excecao de outro mod na busca original (ligado): a mesma excecao sai";
    static final String C_TERCEIROS_VER = "R5 excecao de outro mod na busca original (verificar): a mesma excecao sai";
    static final String C_COMANDO = "comando 'hoshie mekanism': relatorio e livro-caixa (correcao aplicada)";
    static final String C_DIF_CURTO = "diferencial curto (2 sementes x 600 passos)";
    static final String C_MARCA = "marcacao do chunk como modificado igual ao original (ganho de energia do teste integrado)";

    private static final Map<String, Cenario> CENARIOS = new LinkedHashMap<String, Cenario>();

    private Cenarios() {
    }

    // ------------------------------------------------------------------ apoio

    static Universo[] par(int l, long semente, int modoB) throws Exception {
        Universo a = new Universo(Mekanism.DESLIGADO);
        Universo b = new Universo(modoB);
        a.iniciar(l, semente, OX, OY, OZ);
        b.iniciar(l, semente, OX, OY, OZ);
        return new Universo[] {a, b};
    }

    static long bits(double d) {
        return Double.doubleToLongBits(d);
    }

    static void tick() {
        Relogio.ticks++;
    }

    /** Le em A e B (B monta a lista), confere igualdade. */
    static void lerPar(Ch ch, Universo[] u, String o) {
        double a = u[0].ler();
        double b = u[1].ler();
        ch.sim(bits(a) == bits(b), o + ": leitura A=" + a + " B=" + b);
    }

    /** Le, injeta x, compara leituras e fotografias. */
    static void injetarPar(Ch ch, Universo[] u, double x, String o) {
        double a = u[0].ler();
        double b = u[1].ler();
        ch.sim(bits(a) == bits(b), o + ": leitura A=" + a + " B=" + b);
        u[0].escrever(a + x);
        u[1].escrever(b + x);
        ch.sim(u[0].foto().equals(u[1].foto()), o + ": energia de cada celula igual em A e B (bit a bit)");
    }

    static void semErros(Ch ch) {
        ch.sim(Mekanism.erros == 0, "nenhuma excecao no caminho rapido (erros=" + Mekanism.erros + ", " + Mekanism.desligadoPorErro + ")");
    }

    static int[] primeira(SynchronizedMatrixData sd) {
        Coord4D c = sd.cells.iterator().next();
        return new int[] {c.xCoord, c.yCoord, c.zCoord};
    }

    static int[] centro(Universo u) {
        return u.layout.get(u.layout.size() / 2);
    }

    static int[] posicao(SynchronizedMatrixData sd, int k) {
        Iterator<Coord4D> it = sd.cells.iterator();
        Coord4D c = null;
        for (int i = 0; i <= k; i++) {
            c = it.next();
        }
        return new int[] {c.xCoord, c.yCoord, c.zCoord};
    }

    static String resultado(Universo x) {
        try {
            return "devolveu " + x.ler();
        } catch (Throwable t) {
            return "lancou " + t.getClass().getName() + ": " + t.getMessage();
        }
    }

    /**
     * R5 da revisao: um vizinho avisado durante a busca original (codigo de outro mod) lanca excecao. O
     * original deixa a excecao sair; com o Hoshiestar tem de sair A MESMA, sem desligar o recurso e sem
     * rodar a busca de novo. No ligado a excecao vem de dentro da reconstrucao; no verificar, de dentro
     * da comparacao (lista ainda valida, mesmo tick).
     */
    static void terceiros(Ch ch, int modo) throws Exception {
        final Universo[] u = par(3, 81, modo);
        lerPar(ch, u, "montagem");
        if (modo == Mekanism.LIGADO) {
            tick();
        }
        final int[] p = centro(u[0]);
        final int[] criacoes = new int[2];
        for (int i = 0; i < 2; i++) {
            final int ii = i;
            u[i].celulaEm(p).func_145843_s(); // invalida sem tirar do mapa: a busca recria e avisa o vizinho
            u[i].w.criador = new MundoFalso.Criador() {
                public TileEntity criar(MundoFalso w, int cx, int cy, int cz) {
                    if (cx != p[0] || cy != p[1] || cz != p[2]) {
                        return null;
                    }
                    criacoes[ii]++;
                    return Universo.celula(1, 0.0);
                }

                public void depoisDeColocar(MundoFalso w, int cx, int cy, int cz) {
                    throw new IllegalStateException("mod de terceiros falhou no aviso ao vizinho");
                }
            };
        }
        String ra = resultado(u[0]);
        String rb = resultado(u[1]);
        for (Universo x : u) {
            x.w.criador = null;
        }
        ch.sim(ra.equals(rb) && ra.startsWith("lancou java.lang.IllegalStateException"),
                "a mesma excecao sai nos dois (A " + ra + "; B " + rb + ")");
        ch.sim(criacoes[0] == 1 && criacoes[1] == 1, "a busca que lanca rodou uma vez so (A " + criacoes[0] + ", B " + criacoes[1] + ")");
        ch.sim(Mekanism.modo == modo && Mekanism.erros == 0, "o recurso nao desligou (modo " + Mekanism.nomeDoModo(Mekanism.modo)
                + ", erros " + Mekanism.erros + ")");
        ch.sim(Mekanism.excecoesDeTerceiros == 1, "contada como excecao de outro mod (" + Mekanism.excecoesDeTerceiros + ")");
        tick();
        injetarPar(ch, u, 1.0e9, "depois, sem o vizinho que lanca");
        semErros(ch);
    }

    static String juntar(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) {
            sb.append(x).append('\n');
        }
        return sb.toString();
    }

    /**
     * O relatorio do subcomando "hoshie mekanism" (ComandoMatriz.relatorio, a mesma classe do jar) num
     * mundo falso: soma direta, leitura normal, livro-caixa com um bloco de energia de fora da matriz,
     * extra recusado (chunk descarregado, celula da propria matriz), posicao sem matriz, chunk
     * descarregado; nada carrega chunk. {@code aplicada}: a correcao esta na classe (ligado) ou nao
     * (class loader sem transformacao: a linha do Hoshiestar nao pode tocar na classe da logica).
     */
    static void comando(Ch ch, int modo, boolean aplicada) throws Exception {
        Universo u = new Universo(modo);
        u.iniciar(3, 7, OX, OY, OZ);
        TileEntityInductionCasing casco = new TileEntityInductionCasing();
        u.w.colocar(OX - 1, OY, OZ, casco);
        casco.structure = u.sd;
        u.w.colocar(5, 64, 5, Universo.celula(1, 1.5e9)); // um "cubo" (bloco de energia do Mekanism) fora da matriz
        double direta = 0.0;
        for (Coord4D c : u.sd.cells) {
            direta += ((TileEntityInductionCell) u.w.cru(c.xCoord, c.yCoord, c.zCoord)).electricityStored;
        }
        Mekanism.modo = modo;
        String t = juntar(ComandoMatriz.relatorio(u.w, 0, OX - 1, OY, OZ, new int[][] {{5, 64, 5}}));
        ch.sim(t.contains("27 coordenadas de celula, 27 com celula, 0 sem"), "contagem de celulas:\n" + t);
        ch.sim(t.contains("soma direta das celulas: " + direta + " J"), "soma direta = " + direta + ":\n" + t);
        ch.sim(t.contains("iguais bit a bit: sim"), "leitura normal igual a soma direta:\n" + t);
        ch.sim(t.contains("livro-caixa (soma direta + extras): " + (direta + 1.5e9) + " J\n"), "livro-caixa = direta + extra:\n" + t);
        ch.sim(aplicada ? t.contains("lista guardada: 27 celulas") : t.contains("correcao nao aplicada nesta classe"),
                "linha do Hoshiestar (" + (aplicada ? "correcao aplicada" : "sem a correcao") + "):\n" + t);
        double eCelula = ((TileEntityInductionCell) u.w.cru(OX, OY, OZ)).electricityStored;
        String t2 = juntar(ComandoMatriz.relatorio(u.w, 0, OX - 1, OY, OZ, new int[][] {{40, 64, 40}, {OX, OY, OZ}}));
        ch.sim(t2.contains("extra 40,64,40: chunk nao carregado") && t2.contains("(INCOMPLETO")
                && t2.contains("extra " + OX + "," + OY + "," + OZ + ": celula desta matriz, " + eCelula + " J (ja esta na soma direta")
                && t2.contains("livro-caixa (soma direta + extras): " + direta + " J"),
                "extras: chunk descarregado recusado; celula da propria matriz so mostrada (nao soma duas vezes):\n" + t2);
        String t3 = juntar(ComandoMatriz.relatorio(u.w, 0, 0, 64, 0, null));
        ch.sim(t3.contains("nao ha moldura/porta"), "posicao sem matriz:\n" + t3);
        String t4 = juntar(ComandoMatriz.relatorio(u.w, 0, 40, 64, 40, null));
        ch.sim(t4.contains("chunk nao carregado em 40,40"), "chunk descarregado:\n" + t4);
        ch.sim(u.w.cargasDeChunk == 0, "nenhuma chamada que carregaria chunk (" + u.w.cargasDeChunk + ")");
        semErros(ch);
    }

    /** Chamado por TesteMekanism num class loader SEM a correcao (Mekanism original). Devolve as falhas. */
    public static List<String> comandoSemCorrecao() {
        Ch ch = new Ch();
        Mekanism.zerarContadores();
        ThreadServidor.definir(Thread.currentThread());
        if (Relogio.ticks <= 0) {
            Relogio.ticks = 1;
        }
        try {
            comando(ch, Mekanism.DESLIGADO, false);
        } catch (Throwable t) {
            ch.falhas.add("excecao: " + t);
        }
        return ch.falhas;
    }

    // ------------------------------------------------------------------ cenarios

    static {
        CENARIOS.put(C_MARCA, new Cenario() {
            // achado do teste integrado: o mundo reabriu com energia diferente da lida em jogo. Pista: a celula
            // nao marca o chunk como modificado (setEnergy so grava o campo) e um chunk sem entidades e sem
            // marca nao e salvo na parada. Aqui: o Hoshiestar marca (ou deixa de marcar) exatamente o que o
            // original marca, em leituras, escritas e reconstrucoes, nos dois modos
            public void rodar(Ch ch) throws Exception {
                for (int modo : new int[] {Mekanism.LIGADO, Mekanism.VERIFICAR}) {
                    Universo[] u = par(5, 31, modo);
                    String m = modo == Mekanism.LIGADO ? "ligado" : "verificar";
                    for (int i = 0; i < 60; i++) {
                        if (i % 3 == 0) {
                            tick();
                        }
                        injetarPar(ch, u, (i % 2 == 0 ? 1.0e9 : -7.0e8), m + " passo " + i);
                    }
                    ch.sim(u[0].w.marcas == u[1].w.marcas && u[0].w.metadados == u[1].w.metadados,
                            m + ": chunk marcado como modificado A=" + u[0].w.marcas + "/" + u[0].w.metadados + " B=" + u[1].w.marcas + "/" + u[1].w.metadados);
                    ch.sim(u[0].w.marcas == 0L, m + ": (registro) o Mekanism original nao marca o chunk ao mudar a energia das celulas (A=" + u[0].w.marcas + ")");
                    if (modo == Mekanism.LIGADO) {
                        ch.sim(Mekanism.escritasRapidas > 0, "ligado: o caminho rapido escreveu de fato (" + Mekanism.escritasRapidas + ")");
                    }
                }
                semErros(ch);
            }
        });
        CENARIOS.put(C_ORFA, new Cenario() {
            // conferencia S1: celula com 0 J tirada do mapa sem invalidar, no mesmo tick da montagem
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 9, Mekanism.LIGADO);
                int[] p = primeira(u[0].sd);
                for (Universo x : u) {
                    x.celulaEm(p).setEnergy(0.0);
                }
                lerPar(ch, u, "montagem");
                for (Universo x : u) {
                    x.orfas.add((TileEntityInductionCell) x.w.remover(p[0], p[1], p[2], true));
                }
                long incompletas = Mekanism.incompletas;
                injetarPar(ch, u, 3.0e10, "injecao no mesmo tick");
                ch.sim(u[1].orfas.get(0).electricityStored == 0.0, "nenhuma energia foi para a orfa (B: " + u[1].orfas.get(0).electricityStored + ")");
                ch.sim(Mekanism.incompletas == incompletas + 1, "a lista guardada foi recusada (tamanho do mapa) e a estrutura ficou incompleta");
                ch.sim(Mekanism.escritasRapidas == 0, "nenhuma escrita rapida");
                semErros(ch);
            }
        });
        CENARIOS.put(C_TROCA, new Cenario() {
            // a celula e trocada no mapa por outra (0 J) sem invalidar: tamanho do mapa igual
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 9, Mekanism.LIGADO);
                int[] p = primeira(u[0].sd);
                for (Universo x : u) {
                    x.celulaEm(p).setEnergy(0.0);
                }
                lerPar(ch, u, "montagem");
                for (Universo x : u) {
                    TileEntityInductionCell velha = x.celulaEm(p);
                    x.w.colocarCru(p[0], p[1], p[2], Universo.celula(0, 0.0));
                    x.orfas.add(velha);
                }
                long rec = Mekanism.recusasIdentidade;
                double a = u[0].ler();
                double b = u[1].ler();
                ch.sim(bits(a) == bits(b), "leitura no mesmo tick A=" + a + " B=" + b);
                u[0].escrever(a + 3.0e10);
                u[1].escrever(b + 3.0e10);
                ch.sim(MatrizInducao.retrato(u[1].sd) == null, "lista apagada depois da recusa (nao prende a celula orfa)");
                ch.sim(u[0].foto().equals(u[1].foto()), "energia de cada celula igual em A e B (bit a bit)");
                ch.sim(u[1].orfas.get(0).electricityStored == 0.0, "nenhuma energia foi para a orfa (B: " + u[1].orfas.get(0).electricityStored + ")");
                ch.sim(Mekanism.recusasIdentidade == rec + 1, "a escrita rapida foi recusada pela identidade (" + (Mekanism.recusasIdentidade - rec) + ")");
                semErros(ch);
            }
        });
        CENARIOS.put(C_VER_TROCA, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 9, Mekanism.VERIFICAR);
                int[] p = primeira(u[0].sd);
                for (Universo x : u) {
                    x.celulaEm(p).setEnergy(0.0);
                }
                lerPar(ch, u, "montagem");
                for (Universo x : u) {
                    TileEntityInductionCell velha = x.celulaEm(p);
                    x.w.colocarCru(p[0], p[1], p[2], Universo.celula(0, 0.0));
                    x.orfas.add(velha);
                }
                injetarPar(ch, u, 3.0e10, "injecao no mesmo tick");
                ch.sim(Mekanism.divergenciasIdentidade == 1, "divergencia de identidade contada (" + Mekanism.divergenciasIdentidade + ")");
                ch.sim(Mekanism.divergenciasSoma == 0, "a soma nao diverge (0 J): so a identidade enxerga (" + Mekanism.divergenciasSoma + ")");
                ch.sim(Mekanism.leiturasRapidas == 0 && Mekanism.escritasRapidas == 0, "verificar nunca usa o caminho rapido");
                semErros(ch);
            }
        });
        CENARIOS.put(C_VER_LIMPO, new Cenario() {
            // lista valida o tick inteiro: cada leitura e cada escrita e comparada, nenhuma vai pelo rapido
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 37, Mekanism.VERIFICAR);
                lerPar(ch, u, "montagem");
                for (int i = 0; i < 3; i++) {
                    injetarPar(ch, u, 1.0e9 * (i + 1), "injecao " + i + " no mesmo tick");
                }
                ch.sim(Mekanism.comparacoes >= 3 && Mekanism.comparacoesEscrita >= 3,
                        "leituras e escritas comparadas (" + Mekanism.comparacoes + ", " + Mekanism.comparacoesEscrita + ")");
                ch.sim(Mekanism.leiturasRapidas == 0 && Mekanism.escritasRapidas == 0, "verificar nunca usa o caminho rapido (leituras "
                        + Mekanism.leiturasRapidas + ", escritas " + Mekanism.escritasRapidas + ")");
                ch.sim(Mekanism.divergenciasIdentidade + Mekanism.divergenciasSoma + Mekanism.divergenciasEscrita == 0, "nenhuma divergencia");
                semErros(ch);
            }
        });
        CENARIOS.put(C_VER_CONFADV, new Cenario() {
            // o caso exato do conferente (orfa 0 J tirada do mapa sem invalidar), com OUTRA tile entity posta
            // no mesmo chunk: o mapa fica do mesmo tamanho e a lista guardada continua "valida"; so a
            // identidade enxerga (a soma nao: 0 J)
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 9, Mekanism.VERIFICAR);
                int[] p = primeira(u[0].sd);
                for (Universo x : u) {
                    x.celulaEm(p).setEnergy(0.0);
                }
                lerPar(ch, u, "montagem");
                for (Universo x : u) {
                    x.orfas.add((TileEntityInductionCell) x.w.remover(p[0], p[1], p[2], true));
                    x.w.colocarCru(3, 70, 3, Universo.celula(0, 0.0)); // fora da matriz, mesmo chunk
                }
                injetarPar(ch, u, 3.0e10, "injecao no mesmo tick");
                ch.sim(Mekanism.divergenciasIdentidade == 1, "identidade acusa a orfa (" + Mekanism.divergenciasIdentidade + ")");
                ch.sim(Mekanism.divergenciasSoma == 0, "soma nao acusa (" + Mekanism.divergenciasSoma + ")");
                semErros(ch);
            }
        });
        CENARIOS.put(C_CONFADV_TAM, new Cenario() {
            // o mesmo, no ligado: a escrita rapida tem de ser recusada pela identidade
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 9, Mekanism.LIGADO);
                int[] p = primeira(u[0].sd);
                for (Universo x : u) {
                    x.celulaEm(p).setEnergy(0.0);
                }
                lerPar(ch, u, "montagem");
                for (Universo x : u) {
                    x.orfas.add((TileEntityInductionCell) x.w.remover(p[0], p[1], p[2], true));
                    x.w.colocarCru(3, 70, 3, Universo.celula(0, 0.0));
                }
                long rec = Mekanism.recusasIdentidade;
                injetarPar(ch, u, 3.0e10, "injecao no mesmo tick");
                ch.sim(u[1].orfas.get(0).electricityStored == 0.0, "nenhuma energia foi para a orfa (B: " + u[1].orfas.get(0).electricityStored + ")");
                ch.sim(Mekanism.recusasIdentidade == rec + 1, "escrita recusada pela identidade (" + (Mekanism.recusasIdentidade - rec) + ")");
                semErros(ch);
            }
        });
        CENARIOS.put(C_REENTRADA, new Cenario() {
            // a busca original acha a entrada invalida, cria uma celula nova e avisa um vizinho, que le e
            // escreve a matriz no meio da reconstrucao
            public void rodar(Ch ch) throws Exception {
                final Universo[] u = par(4, 11, Mekanism.LIGADO);
                lerPar(ch, u, "montagem");
                tick();
                final int[] p = centro(u[0]);
                for (final Universo x : u) {
                    x.celulaEm(p).func_145843_s(); // invalida sem tirar do mapa
                    x.w.criador = new MundoFalso.Criador() {
                        boolean feito;

                        public TileEntity criar(MundoFalso w, int cx, int cy, int cz) {
                            if (feito || cx != p[0] || cy != p[1] || cz != p[2]) {
                                return null;
                            }
                            feito = true;
                            return Universo.celula(2, 7.5e9);
                        }

                        public void depoisDeColocar(MundoFalso w, int cx, int cy, int cz) {
                            double e = x.ler(); // o vizinho le e escreve a matriz
                            x.escrever(e - 1.0e9);
                        }
                    };
                }
                long rec = Mekanism.reconstrucoes;
                long ree = Mekanism.reentradas;
                long err = Mekanism.erros;
                injetarPar(ch, u, 2.0e9, "leitura com reentrada");
                ch.sim(Mekanism.reentradas - ree == 2, "as duas chamadas de dentro rodaram o original (reentradas " + (Mekanism.reentradas - ree) + ")");
                ch.sim(Mekanism.reconstrucoes - rec == 1, "uma so reconstrucao (" + (Mekanism.reconstrucoes - rec) + ")");
                ch.sim(Mekanism.erros == err, "sem excecao");
                for (Universo x : u) {
                    x.w.criador = null;
                }
                tick();
                injetarPar(ch, u, 1.0e9, "tick seguinte");
                semErros(ch);
            }
        });
        CENARIOS.put(C_CHUNK, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(6, 13, Mekanism.LIGADO);
                lerPar(ch, u, "montagem");
                for (Universo x : u) {
                    x.operar(10, new Random(5)); // descarrega um chunk (sorteado igual nos dois)
                }
                injetarPar(ch, u, 1.0e9, "mesmo tick, chunk fora");
                tick();
                injetarPar(ch, u, 1.0e9, "tick seguinte, chunk fora");
                for (Universo x : u) {
                    x.operar(11, new Random(5));
                }
                injetarPar(ch, u, 1.0e9, "chunk de volta (objetos novos)");
                tick();
                injetarPar(ch, u, 1.0e9, "tick seguinte");
                ch.sim(u[0].w.cargasDeChunk == 0 && u[1].w.cargasDeChunk == 0,
                        "nenhuma chamada que carregaria chunk (A " + u[0].w.cargasDeChunk + ", B " + u[1].w.cargasDeChunk + ")");
                semErros(ch);
            }
        });
        CENARIOS.put(C_ZERAR, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 15, Mekanism.LIGADO);
                lerPar(ch, u, "montagem");
                ch.sim(MatrizInducao.retrato(u[1].sd) != null, "lista guardada depois da montagem");
                for (Universo x : u) {
                    x.w.descarregarChunk(0, 0);
                }
                u[0].escrever(1.0e9);
                u[1].escrever(1.0e9);
                ch.sim(MatrizInducao.retrato(u[1].sd) == null, "lista apagada (nao prende mundo/chunk/celula)");
                ch.sim(u[0].foto().equals(u[1].foto()), "energia igual");
                semErros(ch);
            }
        });
        CENARIOS.put(C_MESMA, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 17, Mekanism.LIGADO);
                lerPar(ch, u, "montagem");
                int[] p = centro(u[0]);
                for (Universo x : u) {
                    x.w.colocar(p[0], p[1], p[2], Universo.celula(3, 1.234e12)); // a velha e invalidada
                }
                injetarPar(ch, u, 5.0e9, "mesmo tick");
                semErros(ch);
            }
        });
        CENARIOS.put(C_CRESCE, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 19, Mekanism.LIGADO);
                int[] q = {OX + 6, OY, OZ + 1}; // fora do cubo, chunk carregado, ja existe antes da montagem
                for (Universo x : u) {
                    x.w.colocar(q[0], q[1], q[2], Universo.celula(1, 3.3e9));
                }
                lerPar(ch, u, "montagem");
                for (Universo x : u) {
                    x.sd.cells.add(new Coord4D(q[0], q[1], q[2], 0));
                }
                injetarPar(ch, u, 1.0e9, "cresceu no mesmo tick");
                lerPar(ch, u, "nova montagem");
                for (Universo x : u) {
                    Iterator<Coord4D> it = x.sd.cells.iterator();
                    it.next();
                    it.remove();
                }
                injetarPar(ch, u, 1.0e9, "encolheu no mesmo tick");
                semErros(ch);
            }
        });
        CENARIOS.put(C_MUNDO, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 21, Mekanism.LIGADO);
                Universo[] v = par(4, 22, Mekanism.LIGADO); // outro mundo, mesmas posicoes, outras energias
                lerPar(ch, u, "montagem no mundo 1");
                for (int i = 0; i < 2; i++) {
                    u[i].w = v[i].w; // a MESMA estrutura passa a ser lida com o mundo 2
                }
                injetarPar(ch, u, 1.0e9, "mesmo tick, mundo 2");
                semErros(ch);
            }
        });
        CENARIOS.put(C_THREAD, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                final Universo[] u = par(4, 23, Mekanism.LIGADO);
                lerPar(ch, u, "montagem");
                final double[] r = new double[2];
                final long rapidas = Mekanism.leiturasRapidas;
                final long fora = Mekanism.foraDaThread;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        r[0] = u[0].ler();
                        r[1] = u[1].ler();
                    }
                });
                t.start();
                t.join();
                ch.sim(bits(r[0]) == bits(r[1]), "mesma leitura fora da thread");
                ch.sim(Mekanism.leiturasRapidas == rapidas && Mekanism.foraDaThread == fora + 1, "fora da thread: original, sem tocar no estado");
                semErros(ch);
            }
        });
        CENARIOS.put(C_ALTURA, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo a = new Universo(Mekanism.DESLIGADO);
                Universo b = new Universo(Mekanism.LIGADO);
                for (Universo x : new Universo[] {a, b}) {
                    x.w = MundoFalso.novo();
                    x.w.mundoFundo = true;
                    x.w.carregarChunk(0, 0);
                    for (int y = -1; y <= 0; y++) {
                        for (int z = 1; z <= 2; z++) {
                            int[] p = {1, y, z};
                            x.layout.add(p);
                            x.w.colocar(1, y, z, Universo.celula(1, 1.0e9 + y + z));
                        }
                    }
                    x.reformar();
                }
                Universo[] u = {a, b};
                long rapidas = Mekanism.leiturasRapidas;
                lerPar(ch, u, "primeira");
                lerPar(ch, u, "segunda, mesmo tick");
                ch.sim(Mekanism.leiturasRapidas == rapidas, "estrutura com y<0 fica no original");
                semErros(ch);
            }
        });
        CENARIOS.put(C_ESPERA, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 25, Mekanism.LIGADO);
                lerPar(ch, u, "montagem");
                for (Universo x : u) {
                    x.operar(8, new Random(3)); // quebra uma celula
                }
                long rec = Mekanism.reconstrucoes;
                for (int i = 0; i < 25; i++) {
                    tick();
                    injetarPar(ch, u, 1.0e8, "tick " + i);
                }
                ch.sim(Mekanism.reconstrucoes - rec == 2, "2 reconstrucoes em 25 ticks (" + (Mekanism.reconstrucoes - rec) + ")");
                semErros(ch);
            }
        });
        CENARIOS.put(C_RELOGIO, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 27, Mekanism.LIGADO);
                lerPar(ch, u, "montagem");
                int[] p = centro(u[0]);
                for (Universo x : u) {
                    TileEntityInductionCell velha = x.celulaEm(p);
                    velha.setEnergy(2.0e9);
                    x.w.colocarCru(p[0], p[1], p[2], Universo.celula(2, 9.0e9));
                    x.orfas.add(velha);
                }
                tick();
                injetarPar(ch, u, 1.0e9, "tick seguinte");
                semErros(ch);
            }
        });
        CENARIOS.put(C_TAMANHO, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 29, Mekanism.LIGADO);
                lerPar(ch, u, "montagem");
                int[] p = centro(u[0]);
                for (Universo x : u) {
                    x.celulaEm(p).setEnergy(4.0e9);
                    x.orfas.add((TileEntityInductionCell) x.w.remover(p[0], p[1], p[2], true));
                }
                injetarPar(ch, u, 1.0e9, "mesmo tick");
                semErros(ch);
            }
        });
        CENARIOS.put(C_MOVIDA, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 31, Mekanism.LIGADO);
                lerPar(ch, u, "montagem");
                int[] p = centro(u[0]);
                int[] q = {OX + 2, OY + 10, OZ + 2}; // fora do cubo, mesmo chunk de p
                for (Universo x : u) {
                    TileEntity c = x.w.remover(p[0], p[1], p[2], true);
                    ((TileEntityInductionCell) c).setEnergy(6.0e9);
                    x.w.colocarCru(q[0], q[1], q[2], c);
                    x.orfas.add((TileEntityInductionCell) c);
                }
                injetarPar(ch, u, 1.0e9, "mesmo tick");
                semErros(ch);
            }
        });
        CENARIOS.put(C_OUTRO_MUNDO, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 33, Mekanism.LIGADO);
                lerPar(ch, u, "montagem");
                int[] p = centro(u[0]);
                for (Universo x : u) {
                    MundoFalso outro = MundoFalso.novo();
                    outro.carregarChunk(p[0] >> 4, p[2] >> 4);
                    TileEntityInductionCell c = x.celulaEm(p);
                    c.setEnergy(5.0e9);
                    x.w.colocarCru(p[0], p[1], p[2], Universo.celula(0, 1.0e8)); // outra celula no lugar
                    outro.colocarCru(p[0], p[1], p[2], c); // a velha agora e de outro mundo
                    x.orfas.add(c);
                }
                injetarPar(ch, u, 1.0e9, "mesmo tick");
                semErros(ch);
            }
        });
        CENARIOS.put(C_CHUNK_CRU, new Cenario() {
            // o chunk some do provedor sem onChunkUnload (isChunkLoaded continua true): a lista tem de cair
            // pelo chunkExists, ANTES de pedir o chunk (pedir carregaria um chunk ausente)
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(6, 13, Mekanism.LIGADO);
                lerPar(ch, u, "montagem");
                for (Universo x : u) {
                    x.w.sumirChunk(0, 0);
                }
                double a = u[0].ler();
                double b = u[1].ler();
                ch.sim(bits(a) == bits(b), "leitura no mesmo tick A=" + a + " B=" + b);
                ch.sim(u[0].w.cargasDeChunk == 0 && u[1].w.cargasDeChunk == 0,
                        "nenhuma chamada que carregaria chunk (A " + u[0].w.cargasDeChunk + ", B " + u[1].w.cargasDeChunk + ")");
                ch.sim(MatrizInducao.retrato(u[1].sd) == null, "nenhuma lista guardada (estrutura incompleta)");
                semErros(ch);
            }
        });
        CENARIOS.put(C_ANTES, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                Universo[] u = par(4, 35, Mekanism.LIGADO);
                long antes = Relogio.ticks;
                long sem = Mekanism.semMundoOuAntesDoTick;
                long fora = Mekanism.foraDaThread;
                try {
                    Relogio.ticks = 0;
                    injetarPar(ch, u, 1.0e9, "relogio parado");
                } finally {
                    Relogio.ticks = antes;
                }
                ch.sim(Mekanism.reconstrucoes == 0 && Mekanism.leiturasRapidas == 0 && Mekanism.escritasRapidas == 0,
                        "antes do 1o tick: sempre o original");
                ch.sim(Mekanism.semMundoOuAntesDoTick == sem + 2 && Mekanism.foraDaThread == fora,
                        "contado como 'antes do 1o tick' (2), nao como outra thread");
                semErros(ch);
            }
        });
        CENARIOS.put(C_TODAS, new Cenario() {
            // R1 da revisao: troca crua (mapa do mesmo tamanho) em CADA posicao do conjunto e escrita CHEIA
            // no mesmo tick: o laco chega ate a ultima celula, entao energia indo para a orfa em qualquer
            // posicao apareceria
            public void rodar(Ch ch) throws Exception {
                int n = par(3, 41, Mekanism.LIGADO)[0].sd.cells.size();
                int ruins = 0;
                String primeira = null;
                for (int k = 0; k < n; k++) {
                    tick();
                    Universo[] u = par(3, 41, Mekanism.LIGADO);
                    int[] p = posicao(u[0].sd, k);
                    for (Universo x : u) {
                        x.celulaEm(p).setEnergy(0.0);
                    }
                    Ch c = new Ch();
                    lerPar(c, u, "montagem");
                    for (Universo x : u) {
                        TileEntityInductionCell velha = x.celulaEm(p);
                        x.w.colocarCru(p[0], p[1], p[2], Universo.celula(0, 0.0));
                        x.orfas.add(velha);
                    }
                    u[0].escrever(Double.MAX_VALUE); // limitada a capacidade
                    u[1].escrever(Double.MAX_VALUE);
                    if (!c.falhas.isEmpty() || !u[0].foto().equals(u[1].foto()) || u[1].orfas.get(0).electricityStored != 0.0) {
                        ruins++;
                        if (primeira == null) {
                            primeira = "posicao " + k + " de " + n + ": orfa B=" + u[1].orfas.get(0).electricityStored + " " + c.falhas;
                        }
                    }
                }
                ch.sim(ruins == 0, "identidade conferida em todas as " + n + " posicoes (ruins " + ruins
                        + (primeira == null ? "" : "; primeira: " + primeira) + ")");
                semErros(ch);
            }
        });
        CENARIOS.put(C_CRUZADA, new Cenario() {
            // R2 da revisao: durante a reconstrucao de S1, a busca original cria uma celula e avisa um vizinho,
            // que LE e ESCREVE outra matriz S2 pelo caminho rapido (o valor da leitura de S1 nao pode se misturar)
            public void rodar(Ch ch) throws Exception {
                final Universo[] u = par(3, 51, Mekanism.LIGADO);
                final int[] p = centro(u[0]);
                final SynchronizedMatrixData[] s2 = new SynchronizedMatrixData[2];
                for (int i = 0; i < 2; i++) {
                    Universo x = u[i];
                    SynchronizedMatrixData s = new SynchronizedMatrixData();
                    for (int dx = 0; dx < 3; dx++) {
                        for (int dy = 0; dy < 3; dy++) {
                            for (int dz = 0; dz < 3; dz++) {
                                int[] pp = {2 + dx, 70 + dy, 2 + dz};
                                x.layout.add(pp); // a foto passa a cobrir S2 tambem
                                x.w.colocar(pp[0], pp[1], pp[2], Universo.celula(1, 1.0e9 * (1 + dx + 3 * dy + 9 * dz) + 0.123));
                                s.cells.add(new Coord4D(pp[0], pp[1], pp[2], 0));
                                s.storageCap += Universo.max(1);
                            }
                        }
                    }
                    s2[i] = s;
                }
                for (int i = 0; i < 2; i++) { // as duas listas montadas no mesmo tick
                    Mekanism.modo = u[i].modo;
                    s2[i].getEnergy(u[i].w);
                    u[i].ler();
                }
                for (int i = 0; i < 2; i++) {
                    final int ii = i;
                    final Universo x = u[i];
                    x.celulaEm(p).func_145843_s(); // invalida sem tirar do mapa: a proxima leitura de S1 reconstroi
                    x.w.criador = new MundoFalso.Criador() {
                        boolean feito;

                        public TileEntity criar(MundoFalso w, int cx, int cy, int cz) {
                            if (feito || cx != p[0] || cy != p[1] || cz != p[2]) {
                                return null;
                            }
                            feito = true;
                            return Universo.celula(2, 7.5e9);
                        }

                        public void depoisDeColocar(MundoFalso w, int cx, int cy, int cz) {
                            Mekanism.modo = x.modo;
                            double e2 = s2[ii].getEnergy(x.w);
                            Mekanism.modo = x.modo;
                            s2[ii].setEnergy(x.w, Math.min(e2 + 1.0e9, s2[ii].storageCap));
                        }
                    };
                }
                long rapidas = Mekanism.leiturasRapidas;
                long escritas = Mekanism.escritasRapidas;
                double a = u[0].ler();
                double b = u[1].ler();
                for (Universo x : u) {
                    x.w.criador = null;
                }
                ch.sim(bits(a) == bits(b), "leitura de S1 igual ao original (A=" + a + " B=" + b + ")");
                ch.sim(u[0].foto().equals(u[1].foto()), "energia de cada celula (S1 e S2) igual em A e B");
                ch.sim(Mekanism.leiturasRapidas - rapidas >= 1 && Mekanism.escritasRapidas - escritas >= 1,
                        "S2 foi lida e escrita pelo caminho rapido de dentro da reconstrucao de S1");
                semErros(ch);
            }
        });
        CENARIOS.put(C_MORTA, new Cenario() {
            // R3 da revisao: celula invalidada que ficou no mapa + escrita no mesmo tick, sem ler antes: a
            // busca original recria a celula (vazia); nenhuma energia pode ir para a celula morta
            public void rodar(Ch ch) throws Exception {
                final Universo[] u = par(3, 61, Mekanism.LIGADO);
                double e0 = u[0].ler();
                double e1 = u[1].ler();
                ch.sim(bits(e0) == bits(e1), "montagem: A=" + e0 + " B=" + e1);
                final int[] p = centro(u[0]);
                final List<TileEntityInductionCell> mortas = new ArrayList<TileEntityInductionCell>();
                for (Universo x : u) {
                    TileEntityInductionCell c = x.celulaEm(p);
                    mortas.add(c);
                    c.func_145843_s();
                    x.w.criador = new MundoFalso.Criador() {
                        public TileEntity criar(MundoFalso w, int cx, int cy, int cz) {
                            return cx == p[0] && cy == p[1] && cz == p[2] ? Universo.celula(0, 0.0) : null;
                        }

                        public void depoisDeColocar(MundoFalso w, int cx, int cy, int cz) {
                        }
                    };
                }
                u[0].escrever(e0 + 1.0e9);
                u[1].escrever(e0 + 1.0e9);
                for (Universo x : u) {
                    x.w.criador = null;
                }
                TreeMap<String, Long> fa = u[0].foto();
                TreeMap<String, Long> fb = u[1].foto();
                ch.sim(fa.equals(fb), "energia de cada celula igual em A e B (A " + fa.size() + " posicoes, B " + fb.size() + ")");
                ch.sim(mortas.get(1).electricityStored == mortas.get(0).electricityStored,
                        "nenhuma energia na celula morta (A=" + mortas.get(0).electricityStored + " B=" + mortas.get(1).electricityStored + ")");
                semErros(ch);
            }
        });
        CENARIOS.put(C_EIXO, new Cenario() {
            // R4 da revisao, nos tres eixos: a celula sai do mapa e volta noutra coordenada do MESMO chunk
            // (o mapa fica do mesmo tamanho), mudando um so eixo de cada vez
            public void rodar(Ch ch) throws Exception {
                for (int eixo = 0; eixo < 3; eixo++) {
                    tick();
                    Universo[] u = par(3, 71 + eixo, Mekanism.LIGADO);
                    lerPar(ch, u, "eixo " + eixo + ": montagem");
                    int[] p = centro(u[0]);
                    int[] q = p.clone();
                    q[eixo] = eixo == 1 ? p[1] + 10 : 3; // fora do cubo, mesmo chunk (0,0)
                    for (Universo x : u) {
                        TileEntity c = x.w.remover(p[0], p[1], p[2], true);
                        ((TileEntityInductionCell) c).setEnergy(6.0e9);
                        x.w.colocarCru(q[0], q[1], q[2], c);
                        x.orfas.add((TileEntityInductionCell) c);
                    }
                    injetarPar(ch, u, 1.0e9, "eixo " + eixo + ": movida, mesmo tick");
                }
                semErros(ch);
            }
        });
        CENARIOS.put(C_TERCEIROS, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                terceiros(ch, Mekanism.LIGADO);
            }
        });
        CENARIOS.put(C_TERCEIROS_VER, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                terceiros(ch, Mekanism.VERIFICAR);
            }
        });
        CENARIOS.put(C_COMANDO, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                comando(ch, Mekanism.LIGADO, true);
            }
        });
        CENARIOS.put(C_DIF_CURTO, new Cenario() {
            public void rodar(Ch ch) throws Exception {
                for (long sem = 101; sem <= 102; sem++) {
                    Resultado r = diferencial(sem, 600, 4, Mekanism.LIGADO, PADRAO, false);
                    ch.sim(r.difFoto == 0 && r.difLeitura == 0 && r.rapidaDiferente == 0 && r.cargas == 0 && Mekanism.erros == 0,
                            "semente " + sem + ": " + r);
                    ch.sim(r.leiturasRapidas > 0 && r.escritasRapidas > 0, "semente " + sem + ": caminho rapido usado");
                }
            }
        });
    }

    // ------------------------------------------------------------------ diferencial aleatorio

    static final class Resultado {
        long passos;
        long difFoto;
        long difLeitura;
        long rapidaDiferente;
        long primeiro = -1;
        long cargas;
        long leiturasRapidas;
        long escritasRapidas;
        long reconstrucoes;
        long divId;

        @Override
        public String toString() {
            return passos + " passos: fotos diferentes " + difFoto + ", leituras diferentes " + difLeitura + ", rapida!=original "
                    + rapidaDiferente + ", primeiro " + primeiro + ", cargas de chunk " + cargas + " | leituras rapidas "
                    + leiturasRapidas + ", escritas rapidas " + escritasRapidas + ", reconstrucoes " + reconstrucoes
                    + (divId > 0 ? ", divergencias de identidade " + divId : "");
        }
    }

    /**
     * @param ops operacoes sorteadas (codigos de Universo.operar, 7 = tick)
     * @param tickDepoisDe15 se a troca crua (15) e seguida de um tick antes da proxima leitura
     */
    static Resultado diferencial(long semente, int passos, int l, int modoB, int[] ops, boolean tickDepoisDe15) throws Exception {
        Mekanism.zerarContadores();
        Universo a = new Universo(Mekanism.DESLIGADO);
        Universo b = new Universo(modoB);
        a.iniciar(l, semente, OX + 1, OY, OZ + 1);
        b.iniciar(l, semente, OX + 1, OY, OZ + 1);
        Random ra = new Random(semente * 31 + 7);
        Random rb = new Random(semente * 31 + 7);
        Random sorteio = new Random(semente ^ 0x5eed);
        Resultado res = new Resultado();
        for (int s = 0; s < passos; s++) {
            int op = ops[sorteio.nextInt(ops.length)];
            if (op == Universo.TICK) {
                tick();
            } else {
                double la = a.operar(op, ra);
                double lb = b.operar(op, rb);
                if (bits(la) != bits(lb)) {
                    res.difLeitura++;
                    if (res.primeiro < 0) {
                        res.primeiro = s;
                    }
                }
                if (op == 15 && tickDepoisDe15) {
                    tick();
                }
            }
            // a leitura de B pelo caminho do modo dele tem de ser igual a do corpo original
            double fa1 = a.ler();
            double fa2 = a.lerOriginal();
            double fb1 = b.ler();
            double fb2 = b.lerOriginal();
            if (bits(fb1) != bits(fb2) || bits(fa1) != bits(fb1) || bits(fa2) != bits(fb2)) {
                res.rapidaDiferente++;
                if (res.primeiro < 0) {
                    res.primeiro = s;
                }
            }
            if (!a.foto().equals(b.foto())) {
                res.difFoto++;
                if (res.primeiro < 0) {
                    res.primeiro = s;
                }
            }
        }
        res.passos = passos;
        res.cargas = a.w.cargasDeChunk + b.w.cargasDeChunk;
        res.leiturasRapidas = Mekanism.leiturasRapidas;
        res.escritasRapidas = Mekanism.escritasRapidas;
        res.reconstrucoes = Mekanism.reconstrucoes;
        res.divId = Mekanism.divergenciasIdentidade;
        return res;
    }

    /**
     * Sorteio com pesos: leituras/escritas (0..6) 3 vezes cada, tick 4, carregar chunk e reformar 2,
     * quebrar, colocar, descarregar e trocar 1 (assim a matriz passa boa parte do tempo completa e o
     * caminho rapido e exercitado; com tudo igual ela ficaria quase sempre incompleta).
     */
    static int[] pesos(boolean tick, int... extras) {
        List<Integer> l = new ArrayList<Integer>();
        for (int k = 0; k < 3; k++) {
            for (int op = 0; op <= 6; op++) {
                l.add(op);
            }
        }
        if (tick) {
            for (int k = 0; k < 4; k++) {
                l.add(Universo.TICK);
            }
        }
        l.addAll(Arrays.asList(8, 9, 10, 13, 11, 11, 12, 12));
        for (int e : extras) {
            l.add(e);
        }
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = l.get(i);
        }
        return r;
    }

    static final int[] PADRAO = pesos(true);
    static final int[] SEM_TICK = pesos(false);
    static final int[] COM_REMOCAO_CRUA = pesos(true, 14);
    static final int[] COM_TROCA_CRUA = pesos(true, 15);

    /** Os nomes de todos os cenarios, na ordem (TesteMutacao). */
    public static List<String> nomes() {
        return new ArrayList<String>(CENARIOS.keySet());
    }

    /**
     * Roda os cenarios dados (TesteMutacao, num class loader com a logica mutante). Devolve as falhas
     * ("cenario: falha"); lista vazia = nenhum cenario percebeu a mutacao.
     */
    public static List<String> rodarParaMutacao(List<String> nomes) {
        ThreadServidor.definir(Thread.currentThread());
        if (Relogio.ticks <= 0) {
            Relogio.ticks = 1;
        }
        Mekanism.intervalo = 1;
        List<String> f = new ArrayList<String>();
        for (String n : nomes) {
            if (!CENARIOS.containsKey(n)) {
                f.add("CENARIO DESCONHECIDO: " + n);
                continue;
            }
            for (String x : rodarCenario(n)) {
                f.add(n + ": " + x);
            }
        }
        Mekanism.modo = Mekanism.DESLIGADO;
        Mekanism.zerarContadores();
        return f;
    }

    static List<String> rodarCenario(String nome) {
        Ch ch = new Ch();
        Mekanism.zerarContadores();
        try {
            CENARIOS.get(nome).rodar(ch);
        } catch (Throwable t) {
            ch.falhas.add("excecao: " + t);
        }
        return ch.falhas;
    }

    // ------------------------------------------------------------------ entrada

    /** Chamado por TesteMekanism (por reflexao, dentro do class loader de teste). */
    public static void rodar(Verifica v, boolean rapido) throws Exception {
        ThreadServidor.definir(Thread.currentThread());
        Relogio.ticks = 1;
        Mekanism.intervalo = 1;

        System.out.println("-- cenarios (todas as protecoes ligadas)");
        for (String nome : CENARIOS.keySet()) {
            List<String> f = rodarCenario(nome);
            v.sim(f.isEmpty(), "cenario: " + nome + (f.isEmpty() ? "" : " -> " + f));
        }

        System.out.println("-- diferencial aleatorio (A original x B; energia de cada celula bit a bit apos cada passo)");
        int sementes = rapido ? 4 : 30;
        int passos = rapido ? 1500 : 4000;
        diferencialEmLote(v, "ligado, eventos padrao, 216 celulas", sementes, passos, 6, Mekanism.LIGADO, PADRAO, false, true);
        diferencialEmLote(v, "ligado, eventos padrao, 512 celulas", Math.max(2, sementes / 5), passos, 8, Mekanism.LIGADO, PADRAO, false, true);
        diferencialEmLote(v, "verificar, eventos padrao", Math.max(2, sementes / 3), passos, 6, Mekanism.VERIFICAR, PADRAO, false, true);
        diferencialEmLote(v, "ligado, relogio parado (sem tick)", Math.max(2, sementes / 3), passos, 6, Mekanism.LIGADO, SEM_TICK, false, true);
        diferencialEmLote(v, "ligado, com remocao crua (fora do padrao)", Math.max(2, sementes / 3), passos, 6, Mekanism.LIGADO, COM_REMOCAO_CRUA, false, true);
        diferencialEmLote(v, "ligado, com troca crua seguida de tick", Math.max(2, sementes / 3), passos, 6, Mekanism.LIGADO, COM_TROCA_CRUA, true, true);
        Mekanism.intervalo = 5;
        diferencialEmLote(v, "ligado, intervalo 5, eventos padrao", Math.max(2, sementes / 3), passos, 6, Mekanism.LIGADO, PADRAO, false, true);
        Mekanism.intervalo = 1;
        // risco residual documentado (R1): troca crua e leitura no MESMO tick. So informativo no ligado;
        // no verificar o estado tem de ficar igual (sempre original) e a identidade tem de acusar
        diferencialEmLote(v, "INFORMATIVO ligado, troca crua no mesmo tick (risco residual R1)", Math.max(2, sementes / 5), passos, 6,
                Mekanism.LIGADO, COM_TROCA_CRUA, false, false);
        Resultado rv = diferencial(77, passos, 6, Mekanism.VERIFICAR, COM_TROCA_CRUA, false);
        v.sim(rv.difFoto == 0 && rv.difLeitura == 0 && rv.rapidaDiferente == 0 && rv.divId > 0,
                "verificar, troca crua no mesmo tick: estado igual ao original e a identidade acusa (" + rv + ")");

        Mekanism.zerarContadores();

        System.out.println("-- banco de medida (INDICATIVO: mundo falso, nao e o Crucible)");
        banco(16, rapido ? 200 : 2000);
        Mekanism.modo = Mekanism.DESLIGADO;
    }

    static void diferencialEmLote(Verifica v, String nome, int sementes, int passos, int l, int modo, int[] ops, boolean tick15,
            boolean exigir) throws Exception {
        long foto = 0;
        long leit = 0;
        long rap = 0;
        long cargas = 0;
        long lr = 0;
        long er = 0;
        long rec = 0;
        long err = 0;
        long div = 0;
        for (int s = 1; s <= sementes; s++) {
            Resultado r = diferencial(s, passos, l, modo, ops, tick15);
            foto += r.difFoto;
            leit += r.difLeitura;
            rap += r.rapidaDiferente;
            cargas += r.cargas;
            lr += r.leiturasRapidas;
            er += r.escritasRapidas;
            rec += r.reconstrucoes;
            err += Mekanism.erros;
            div += Mekanism.divergenciasIdentidade + Mekanism.divergenciasSoma + Mekanism.divergenciasEscrita;
        }
        String resumo = nome + ": " + sementes + " sementes x " + passos + " passos; passos com foto diferente " + foto
                + ", leitura diferente " + leit + ", rapida!=original " + rap + ", cargas de chunk " + cargas + ", erros " + err
                + ", divergencias (verificar) " + div + " | leituras rapidas " + lr + ", escritas rapidas " + er + ", reconstrucoes " + rec;
        if (exigir) {
            boolean usouRapido = modo != Mekanism.LIGADO || (lr > 0 && er > 0);
            v.sim(foto == 0 && leit == 0 && rap == 0 && cargas == 0 && err == 0 && div == 0 && usouRapido, resumo);
        } else {
            System.out.println("  info  " + resumo);
        }
    }

    static void banco(int l, int chamadas) throws Exception {
        Universo u = new Universo(Mekanism.LIGADO);
        u.iniciar(l, 1, 0, 64, 0);
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                if (!u.w.carregados.containsKey(MundoFalso.chave(cx, cz))) {
                    u.w.carregarChunk(cx, cz);
                }
            }
        }
        double soma = 0;
        for (int rep = 0; rep < 3; rep++) {
            tick();
            long t0 = System.nanoTime();
            for (int i = 0; i < chamadas; i++) {
                soma += u.lerOriginal();
            }
            long t1 = System.nanoTime();
            for (int i = 0; i < chamadas; i++) {
                soma += u.ler();
            }
            long t2 = System.nanoTime();
            for (int i = 0; i < chamadas; i++) {
                Mekanism.modo = Mekanism.DESLIGADO;
                u.sd.setEnergy(u.w, 1.0e12 + i);
            }
            long t3 = System.nanoTime();
            for (int i = 0; i < chamadas; i++) {
                u.escrever(1.0e12 + i);
            }
            long t4 = System.nanoTime();
            System.out.printf("  %d celulas: getEnergy original %.1f us, rapido %.2f us | setEnergy original %.1f us, rapido %.2f us"
                    + " (leituras rapidas %d, escritas rapidas %d)%n", u.sd.cells.size(), (t1 - t0) / 1e3 / chamadas,
                    (t2 - t1) / 1e3 / chamadas, (t3 - t2) / 1e3 / chamadas, (t4 - t3) / 1e3 / chamadas, Mekanism.leiturasRapidas,
                    Mekanism.escritasRapidas);
        }
        System.out.println("  (soma " + (soma > 0) + ")");
    }
}
