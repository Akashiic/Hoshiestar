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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trava de versao: "so aplico se a classe X tem exatamente um destes SHA-256". O hash e o dos
 * bytes ORIGINAIS da classe do mod, isto e, o .class como ele esta dentro do jar do mod.
 *
 * <p>No transformador do Hoshiestar a conferencia e feita nos bytes que chegam a ele. Como ele e o
 * primeiro transformador que mexe em classes de mod (ver servidor/README.md), esses bytes sao os
 * do jar; se algum transformador anterior tiver mexido, o hash nao bate e a correcao NAO e
 * aplicada (falha fechada). O log mostra o hash visto e se ele e igual ao do arquivo.
 *
 * <p>Para descobrir o valor: {@code hoshie hash <classe>} no servidor, ou {@code sha256sum} do
 * .class tirado do jar do mod.
 */
public final class Trava {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private final String classe;
    private final Set<String> aceitos;

    private Trava(String classe, Set<String> aceitos) {
        this.classe = classe;
        this.aceitos = aceitos;
    }

    /**
     * Cria a trava. {@code classe} com pontos (ex.: mekanism.common.Foo); pelo menos um sha256
     * (64 caracteres hexadecimais; maiusculas viram minusculas). Erro de programacao vira
     * IllegalArgumentException na hora (no boot, nunca no meio do jogo).
     */
    public static Trava de(String classe, String... sha256Aceitos) {
        if (classe == null || classe.isEmpty() || classe.indexOf('/') >= 0 || sha256Aceitos == null
                || sha256Aceitos.length == 0) {
            throw new IllegalArgumentException("trava precisa de classe (com pontos) e de pelo menos um sha256");
        }
        Set<String> s = new LinkedHashSet<String>();
        for (String h : sha256Aceitos) {
            String x = h == null ? "" : h.trim().toLowerCase(Locale.ROOT);
            if (!HEX64.matcher(x).matches()) {
                throw new IllegalArgumentException("sha256 invalido na trava de " + classe + ": " + h);
            }
            s.add(x);
        }
        return new Trava(classe, Collections.unmodifiableSet(s));
    }

    /** Nome da classe com pontos. */
    public String classe() {
        return classe;
    }

    public Set<String> aceitos() {
        return aceitos;
    }

    /** true se o hash (hexadecimal) esta na lista aceita. */
    public boolean aceita(String sha256) {
        return sha256 != null && aceitos.contains(sha256.toLowerCase(Locale.ROOT));
    }

    /** true se os bytes dados tem um hash aceito. */
    public boolean confere(byte[] bytes) {
        return bytes != null && aceita(HashClasse.sha256(bytes));
    }

    @Override
    public String toString() {
        return classe + " " + aceitos;
    }
}
