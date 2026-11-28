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
package hoshiestar.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lista dos recursos do Hoshiestar (um por interruptor do hoshiestar.yml). Serve para o
 * "hoshie status" e para as linhas de log de quando o servidor termina de ligar.
 *
 * <p>Cada recurso se registra UMA vez, na fase de coremod, em {@code hoshiestar.Catalogo}, com o
 * valor do interruptor ja lido do config. As correcoes embutidas de um recurso aparecem sob o id
 * dele automaticamente (ver {@code hoshiestar.embutidos.Correcoes}); um recurso que e patch no
 * fork pode dar uma linha de estado propria com {@link Recurso#estado(Estado)}.
 */
public final class Recursos {

    /** Fornece a linha de estado de um recurso (chamada pelo comando e no fim do boot). */
    public interface Estado {
        String linha();

        /** O recurso esta ligado no config mas recusado (nao roda)? Padrao: nao. (Hoshiestar passo 3 do Degrau B) */
        default boolean recusado() {
            return false;
        }
    }

    /** Um recurso. */
    public static final class Recurso {
        public final String id;
        public final String descricao;
        public final boolean ligado;
        private volatile Estado estado;

        Recurso(String id, String descricao, boolean ligado) {
            this.id = id;
            this.descricao = descricao;
            this.ligado = ligado;
        }

        /** Define quem fornece a linha de estado do recurso (opcional). Devolve o proprio recurso. */
        public Recurso estado(Estado e) {
            this.estado = e;
            return this;
        }

        /** "ligado", "desligado" ou "RECUSADO" (ligado no config, recusado: nao roda). Nunca lanca excecao. */
        public String situacao() {
            if (!ligado) {
                return "desligado";
            }
            Estado e = estado;
            try {
                return e != null && e.recusado() ? "RECUSADO" : "ligado";
            } catch (Throwable t) {
                return "ligado";
            }
        }

        /** Linha de estado do recurso, ou null se ele nao fornece. Nunca lanca excecao. */
        public String linhaDeEstado() {
            Estado e = estado;
            if (e == null) {
                return null;
            }
            try {
                return e.linha();
            } catch (Throwable t) {
                return "erro ao montar o estado: " + t;
            }
        }
    }

    private static final List<Recurso> LISTA = new ArrayList<Recurso>();

    private Recursos() {
    }

    /**
     * Registra um recurso. {@code id} e o nome da secao dele no hoshiestar.yml. Chamar so na fase de
     * coremod (em hoshiestar.Catalogo); id repetido e erro de programacao.
     */
    public static synchronized Recurso registrar(String id, String descricao, boolean ligado) {
        for (Recurso r : LISTA) {
            if (r.id.equals(id)) {
                throw new IllegalArgumentException("recurso registrado duas vezes: " + id);
            }
        }
        Recurso r = new Recurso(id, descricao, ligado);
        LISTA.add(r);
        return r;
    }

    /** Os recursos, na ordem de registro. */
    public static synchronized List<Recurso> todos() {
        return Collections.unmodifiableList(new ArrayList<Recurso>(LISTA));
    }

    /** O recurso com este id, ou null. */
    public static synchronized Recurso achar(String id) {
        for (Recurso r : LISTA) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** Uso dos testes isolados: esvazia a lista. */
    public static synchronized void limparParaTeste() {
        LISTA.clear();
    }
}
