/*
 * Hoshiestar-Core: base comum dos recursos da Etapa 1 do projeto Hoshiestar.
 * Copyright (C) 2026 Projeto Hoshiestar
 *
 * Este programa e software livre: voce pode redistribui-lo e/ou modifica-lo
 * sob os termos da GNU General Public License publicada pela Free Software
 * Foundation, versao 3 da Licenca ou (a seu criterio) qualquer versao posterior.
 *
 * Este programa e distribuido na esperanca de que seja util, mas SEM QUALQUER
 * GARANTIA; sem mesmo a garantia implicita de COMERCIABILIDADE ou ADEQUACAO A
 * UM PROPOSITO ESPECIFICO. Veja a GNU General Public License para mais detalhes.
 *
 * Voce deve ter recebido uma copia da GNU General Public License junto com este
 * programa. Se nao, veja <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package hoshiestar.core.nucleo;

import hoshiestar.core.api.EstadoRecurso;
import hoshiestar.core.api.Recurso;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Estado de um recurso dentro do nucleo. Todos os metodos sao synchronized (pouco uso, fora do tick). */
final class EstadoInterno implements EstadoRecurso {

    final Recurso recurso;
    private boolean ligado;
    private String motivoRecusa;
    private final Set<String> registrados = new LinkedHashSet<String>();
    private final Set<String> aplicacoes = new LinkedHashSet<String>();
    private final Set<String> aplicados = new LinkedHashSet<String>();
    private String erroGancho;

    EstadoInterno(Recurso recurso) {
        this.recurso = recurso;
    }

    @Override
    public String id() {
        return recurso.id();
    }

    @Override
    public synchronized boolean ligado() {
        return ligado;
    }

    synchronized void definirLigado(boolean b) {
        ligado = b;
    }

    @Override
    public synchronized boolean ativo() {
        return ligado && motivoRecusa == null;
    }

    @Override
    public synchronized String motivoRecusa() {
        return motivoRecusa;
    }

    synchronized void recusar(String motivo) {
        if (motivoRecusa == null) {
            motivoRecusa = motivo;
        }
    }

    synchronized void registrar(String mixinCompleto) {
        registrados.add(mixinCompleto);
    }

    synchronized void anotarAplicado(String mixinCompleto, String alvo) {
        aplicados.add(mixinCompleto);
        aplicacoes.add(Registro.curto(mixinCompleto) + " -> " + alvo);
    }

    synchronized void anotarErroGancho(String s) {
        if (erroGancho == null) {
            erroGancho = s;
        }
    }

    synchronized String erroGancho() {
        return erroGancho;
    }

    @Override
    public synchronized List<String> mixinsRegistrados() {
        return new ArrayList<String>(registrados);
    }

    @Override
    public synchronized List<String> aplicacoes() {
        return new ArrayList<String>(aplicacoes);
    }

    @Override
    public synchronized List<String> mixinsAplicados() {
        return new ArrayList<String>(aplicados);
    }
}
