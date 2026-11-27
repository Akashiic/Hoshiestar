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
package hoshiestar.paralelo;

/**
 * O laco de uma thread de regiao: espera uma fase, pega lotes pelo contador atomico da {@link Fase}, tica cada lote em
 * ordem e fecha. Entre dois itens (o "ponto seguro") obedece aos sinais da {@link Barreira}: pausa (um pedido delegado
 * vai ser atendido) e aborto.
 *
 * <p><b>Invariantes:</b>
 * <ul>
 * <li>O trabalhador so chama codigo de fora ({@link Trabalho#ticar}) no estado {@link #RODANDO} e SEM a trava da
 * barreira na mao. Ele nunca espera outro trabalhador: so espera a thread do servidor (fase nova, fim da pausa, resposta
 * do proprio pedido).</li>
 * <li>O estado so vira {@code RODANDO} com a trava da barreira e com o sinal de pausa desligado; a thread do servidor so
 * roda a operacao de um pedido depois de ver, com a mesma trava, nenhum trabalhador em {@code RODANDO}. Dai sai "a
 * operacao proibida nunca roda junto com outro trabalhador".</li>
 * <li>Um item so comeca depois de o item anterior do mesmo lote terminar, e so esta thread tica o lote que pegou.</li>
 * <li>O {@code Throwable} de um item e guardado no lote (escopo do {@code catch} de World.updateEntities); o que escapar
 * do resto do laco mata a thread, e a morte e avisada a barreira no {@code finally}.</li>
 * </ul>
 *
 * <p><b>Quem escreve cada campo:</b> {@link #barreira}, {@link #indice}, {@link #nome}, {@link #thread}: {@code final}
 * (a thread e criada no construtor da barreira e so comeca depois). {@link #estado}, {@link #causaDaMorte} e
 * {@link #contado}: qualquer thread, SEMPRE com a trava da barreira. {@link #daVez} ({@code volatile}): so a propria
 * thread (as outras leem para o despejo e para fechar o lote de uma thread morta). {@link #vista} e {@link #nsParado}:
 * so a propria thread, sem sincronizacao.
 */
final class Trabalhador implements Runnable {

    /** Estacionado, fora de qualquer lote. */
    static final int OCIOSO = 0;
    /** Pode estar dentro de um item (codigo do jogo). */
    static final int RODANDO = 1;
    /** Parado num ponto seguro, entre dois itens. */
    static final int PARADO = 2;
    /** Parado no comeco do proprio pedido delegado, no meio de um item, esperando a thread do servidor. */
    static final int PEDINDO = 3;
    /** A thread acabou por causa de {@code encerrar}. */
    static final int FIM = 4;
    /** A thread acabou sem ser por {@code encerrar}. */
    static final int MORTO = 5;

    private static final String[] NOMES = {"ocioso", "rodando", "parado", "pedindo", "fim", "morto"};

    final Barreira barreira;
    final int indice;
    final String nome;
    final ThreadRegiao thread;

    int estado = OCIOSO;
    Throwable causaDaMorte;
    boolean contado;

    volatile Andamento daVez;

    Fase vista;
    long nsParado;

    Trabalhador(Barreira barreira, int indice, String nome, long tamanhoDaPilha) {
        this.barreira = barreira;
        this.indice = indice;
        this.nome = nome;
        this.thread = new ThreadRegiao(this, nome, tamanhoDaPilha);
    }

    static String nomeDoEstado(int estado) {
        return estado >= 0 && estado < NOMES.length ? NOMES[estado] : "?" + estado;
    }

    @Override
    public void run() {
        Throwable causa = null;
        boolean normal = false;
        try {
            normal = laco();
        } catch (Throwable t) {
            causa = t;
        } finally {
            barreira.saiu(this, normal, causa);
        }
    }

    /** Devolve true quando sai por {@code encerrar}. */
    private boolean laco() {
        for (;;) {
            Fase f = barreira.esperarFase(this);
            if (f == null) {
                return true;
            }
            final int total = f.total();
            for (;;) {
                if (f.semNovos || f.proximo.get() >= total) {
                    break;
                }
                int j = f.proximo.getAndIncrement();
                if (j >= total) {
                    break;
                }
                rodarLote(f, f.lote(j));
            }
        }
    }

    private void rodarLote(Fase f, Andamento a) {
        a.dono = this;
        daVez = a;
        nsParado = 0L;
        final long t0 = barreira.ambiente.agora();
        final Trabalho trabalho = a.trabalho;
        final int n = a.tamanho;
        Andamento.Fim fim = Andamento.Fim.COMPLETO;
        Throwable erro = null;
        int i = 0;
        while (i < n) {
            // ponto seguro: entre dois itens (e antes do primeiro)
            if (barreira.sinais != 0 || a.sujo) {
                int r = barreira.pontoSeguro(this, a);
                if (r == Barreira.PARA_ABORTO) {
                    fim = Andamento.Fim.ABORTADO;
                    break;
                }
                if (r == Barreira.PARA_SUJO) {
                    fim = i == 0 ? Andamento.Fim.SOBROU : Andamento.Fim.SUJO;
                    break;
                }
            }
            if (i == 0 && f.semNovos) {
                fim = Andamento.Fim.SOBROU;
                break;
            }
            try {
                trabalho.ticar(i);
            } catch (Throwable t) {
                erro = t;
                fim = Andamento.Fim.ERRO;
                f.semNovos = true;
                break;
            }
            i++;
            a.feitos = i;
        }
        long ns = barreira.ambiente.agora() - t0 - nsParado;
        f.fechar(a, fim, i, erro, ns < 0L ? 0L : ns, nome);
        daVez = null;
        barreira.loteFechado(f);
    }
}
