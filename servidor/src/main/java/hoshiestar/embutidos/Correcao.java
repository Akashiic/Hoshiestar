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
package hoshiestar.embutidos;

import hoshiestar.api.Trava;
import hoshiestar.embutidos.asm.PadraoNaoEncontrado;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import org.objectweb.asm.tree.ClassNode;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uma correcao embutida: "na classe X do mod, se o recurso R esta ligado e a trava de versao confere,
 * faca a transformacao T". Criada na fase de coremod (em {@code hoshiestar.Catalogo}); o estado
 * muda quando a classe alvo passa pelo transformador.
 *
 * <p>Pode haver mais de uma correcao na mesma classe; cada uma confere a trava contra os bytes
 * ORIGINAIS que chegaram ao transformador e e aplicada sobre o resultado da anterior.
 */
public final class Correcao {

    /** O que a correcao faz na arvore da classe. Lanca PadraoNaoEncontrado para recusar (sem alterar). */
    public interface Transformacao {
        void aplicar(ClassNode classe, ResolvedorDeGanchos ganchos) throws PadraoNaoEncontrado;
    }

    /** Estado da correcao. */
    public enum Estado {
        /** O interruptor do recurso esta desligado: a classe nunca e tocada. */
        DESLIGADA("desligada"),
        /** Ligada, mas a classe alvo ainda nao passou pelo transformador. */
        AGUARDANDO("aguardando a classe ser carregada"),
        /** Aplicada. */
        APLICADA("aplicada"),
        /** Ligada, mas a trava de versao nao conferiu: nada foi alterado. */
        RECUSADA_TRAVA("NAO aplicada: trava de versao nao confere"),
        /** Ligada, trava conferiu, mas o padrao esperado nao foi encontrado: nada foi alterado. */
        NAO_APLICADA("NAO aplicada: padrao nao encontrado"),
        /** Erro inesperado: nada foi alterado. */
        ERRO("NAO aplicada: erro");

        public final String texto;

        Estado(String texto) {
            this.texto = texto;
        }
    }

    public final String recurso;
    public final String descricao;
    public final Trava trava;
    public final boolean ligada;
    final Transformacao transformacao;

    private volatile Estado estado;
    private volatile String detalhe;
    private volatile String hashVisto;
    private volatile Boolean igualAoArquivo;
    private final AtomicInteger aplicacoes = new AtomicInteger();

    private Correcao(String recurso, String descricao, Trava trava, boolean ligada, Transformacao t) {
        this.recurso = recurso;
        this.descricao = descricao;
        this.trava = trava;
        this.ligada = ligada;
        this.transformacao = t;
        this.estado = ligada ? Estado.AGUARDANDO : Estado.DESLIGADA;
    }

    /**
     * @param recurso id do recurso (a secao dele no hoshiestar.yml)
     * @param descricao uma frase curta (vai para o log e para o "hoshie status")
     * @param trava classe alvo + SHA-256 aceitos dos bytes originais
     * @param ligada valor do interruptor do recurso, lido UMA vez no boot
     * @param t a transformacao (use os utilitarios de {@code hoshiestar.embutidos.asm.Asm})
     */
    public static Correcao nova(String recurso, String descricao, Trava trava, boolean ligada, Transformacao t) {
        if (recurso == null || descricao == null || trava == null || t == null) {
            throw new IllegalArgumentException("correcao incompleta");
        }
        return new Correcao(recurso, descricao, trava, ligada, t);
    }

    /** Nome da classe alvo, com pontos (o "transformedName" do LaunchClassLoader). */
    public String classe() {
        return trava.classe();
    }

    public Estado estado() {
        return estado;
    }

    /** Detalhe do ultimo resultado (motivo da recusa ou erro), ou null. */
    public String detalhe() {
        return detalhe;
    }

    /** SHA-256 dos bytes que chegaram ao transformador da ultima vez, ou null se ainda nao chegaram. */
    public String hashVisto() {
        return hashVisto;
    }

    /** Se os bytes que chegaram sao iguais aos do arquivo .class do jar (null = nao conferido). */
    public Boolean igualAoArquivo() {
        return igualAoArquivo;
    }

    /** Quantas vezes a transformacao foi aplicada (o Mixin pode pedir a mesma classe mais de uma vez). */
    public int aplicacoes() {
        return aplicacoes.get();
    }

    /** Muda o estado; devolve true se mudou (para logar so na primeira vez). */
    boolean registrar(Estado novo, String det, String hash, Boolean igual) {
        boolean mudou = estado != novo;
        estado = novo;
        detalhe = det;
        hashVisto = hash;
        igualAoArquivo = igual;
        if (novo == Estado.APLICADA) {
            aplicacoes.incrementAndGet();
        }
        return mudou;
    }

    /** Uma linha para log e comando. */
    public String linha() {
        StringBuilder sb = new StringBuilder();
        sb.append(descricao).append(" [").append(classe()).append("]: ").append(estado.texto);
        if (detalhe != null) {
            sb.append(" (").append(detalhe).append(")");
        }
        if (estado == Estado.RECUSADA_TRAVA && hashVisto != null) {
            sb.append("; hash visto ").append(hashVisto);
            if (igualAoArquivo != null) {
                sb.append(igualAoArquivo ? " (igual ao do arquivo do jar)" : " (DIFERENTE do arquivo do jar: outro transformador mexeu antes)");
            }
        }
        if (estado == Estado.APLICADA && aplicacoes.get() > 1) {
            sb.append(" (").append(aplicacoes.get()).append(" vezes)");
        }
        return sb.toString();
    }
}
