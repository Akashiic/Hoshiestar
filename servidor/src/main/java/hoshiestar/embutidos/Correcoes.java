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

import hoshiestar.api.HashClasse;
import hoshiestar.api.Log;
import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.PadraoNaoEncontrado;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registro das correcoes embutidas e a logica de aplica-las.
 *
 * <p>Ciclo: na fase de coremod o {@code hoshiestar.Catalogo} chama {@link #adicionar} para cada
 * correcao (ligada ou nao) e depois {@link #fechar()}. So as LIGADAS entram no mapa que o
 * transformador consulta; uma correcao desligada nunca toca na classe.
 *
 * <p>Custo para uma classe sem correcao: uma busca num HashMap pelo nome, sem tocar nos bytes.
 */
public final class Correcoes {

    private static final Correcoes GLOBAL = new Correcoes();
    private static final Log LOG = Log.de("embutidos");

    private final List<Correcao> todas = new ArrayList<Correcao>();
    private volatile Map<String, Correcao[]> ligadasPorClasse = Collections.emptyMap();
    private volatile boolean fechado;

    /** O registro do servidor. */
    public static Correcoes global() {
        return GLOBAL;
    }

    /** Um registro separado (testes isolados). */
    public static Correcoes novoParaTeste() {
        return new Correcoes();
    }

    /** Acrescenta uma correcao. So antes de {@link #fechar()}. */
    public synchronized void adicionar(Correcao c) {
        if (fechado) {
            throw new IllegalStateException("registro de correcoes ja fechado; adicione em hoshiestar.Catalogo");
        }
        todas.add(c);
    }

    /** Fecha o registro e monta o mapa das ligadas (chamado uma vez, antes de registrar o transformador). */
    public synchronized void fechar() {
        Map<String, List<Correcao>> m = new HashMap<String, List<Correcao>>();
        for (Correcao c : todas) {
            if (c.ligada) {
                List<Correcao> l = m.get(c.classe());
                if (l == null) {
                    l = new ArrayList<Correcao>();
                    m.put(c.classe(), l);
                }
                l.add(c);
            }
        }
        Map<String, Correcao[]> final_ = new HashMap<String, Correcao[]>();
        for (Map.Entry<String, List<Correcao>> e : m.entrySet()) {
            final_.put(e.getKey(), e.getValue().toArray(new Correcao[0]));
        }
        ligadasPorClasse = final_;
        fechado = true;
    }

    /** Todas as correcoes (ligadas ou nao), na ordem de registro. */
    public synchronized List<Correcao> todas() {
        return Collections.unmodifiableList(new ArrayList<Correcao>(todas));
    }

    /** As correcoes de um recurso. */
    public synchronized List<Correcao> doRecurso(String recurso) {
        List<Correcao> l = new ArrayList<Correcao>();
        for (Correcao c : todas) {
            if (c.recurso.equals(recurso)) {
                l.add(c);
            }
        }
        return l;
    }

    /** As correcoes ligadas para esta classe (nome com pontos), ou null. Uma busca em HashMap. */
    public Correcao[] ligadasPara(String classe) {
        return ligadasPorClasse.get(classe);
    }

    /** Numero de classes com alguma correcao ligada. */
    public int classesComCorrecao() {
        return ligadasPorClasse.size();
    }

    /**
     * Aplica as correcoes ligadas desta classe. Nunca lanca excecao: qualquer erro devolve os bytes
     * que chegaram. {@code carregador} e onde procurar o arquivo .class original (para dizer no log se
     * os bytes que chegaram sao os do jar).
     */
    public byte[] aplicar(String classe, byte[] bytes, ResolvedorDeGanchos ganchos, ClassLoader carregador) {
        Correcao[] cs = ligadasPara(classe);
        if (cs == null || bytes == null) {
            return bytes;
        }
        try {
            return aplicarTodas(classe, bytes, cs, ganchos, carregador);
        } catch (Throwable t) {
            try {
                LOG.e("erro inesperado ao aplicar correcoes em " + classe + "; classe mantida original", t);
                for (Correcao c : cs) {
                    c.registrar(Correcao.Estado.ERRO, "erro inesperado: " + t, null, null);
                }
            } catch (Throwable ignorado) {
                // nunca deixar escapar
            }
            return bytes;
        }
    }

    private byte[] aplicarTodas(String classe, byte[] originais, Correcao[] cs, ResolvedorDeGanchos ganchos,
            ClassLoader carregador) {
        String hash = HashClasse.sha256(originais);
        Boolean igual = null;
        byte[] atuais = originais;
        for (Correcao c : cs) {
            if (!c.trava.aceita(hash)) {
                if (igual == null) {
                    igual = igualAoArquivo(classe, originais, carregador);
                }
                if (c.registrar(Correcao.Estado.RECUSADA_TRAVA, null, hash, igual)) {
                    LOG.a(c.recurso + ": " + c.linha() + ". Aceitos: " + c.trava.aceitos()
                            + ". Mande esta linha para o projeto.");
                }
                continue;
            }
            try {
                ClassNode cn = Asm.ler(atuais);
                c.transformacao.aplicar(cn, ganchos);
                byte[] novos = Asm.escrever(cn);
                atuais = novos;
                if (c.registrar(Correcao.Estado.APLICADA, null, hash, Boolean.TRUE)) {
                    LOG.i(c.recurso + ": " + c.linha());
                }
            } catch (PadraoNaoEncontrado e) {
                if (c.registrar(Correcao.Estado.NAO_APLICADA, e.getMessage(), hash, null)) {
                    LOG.a(c.recurso + ": " + c.linha());
                }
            } catch (Throwable t) {
                if (c.registrar(Correcao.Estado.ERRO, t.toString(), hash, null)) {
                    LOG.e(c.recurso + ": " + c.linha(), t);
                }
            }
        }
        return atuais;
    }

    private static Boolean igualAoArquivo(String classe, byte[] recebidos, ClassLoader carregador) {
        try {
            byte[] arq = HashClasse.bytesDoArquivo(classe, carregador);
            return arq == null ? null : Boolean.valueOf(java.util.Arrays.equals(arq, recebidos));
        } catch (Throwable t) {
            return null;
        }
    }
}
