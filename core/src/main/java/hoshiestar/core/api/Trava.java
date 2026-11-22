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
package hoshiestar.core.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trava de versao: "so aplico se a classe X tem exatamente um destes SHA-256". O hash e o dos
 * bytes de distribuicao da classe, calculado por {@link HashClasse} SEM carregar a classe e antes
 * de qualquer transformador de coremod ou mixin:
 * <ul>
 * <li>classe de mod: os bytes do .class dentro do jar do mod (classes de mod nao sao renomeadas);</li>
 * <li>classe do Minecraft (net.minecraft.*): os bytes do jar vanilla (nome "notch", ex. "ahb") com o
 * binpatch do Forge/Crucible aplicado, quando a classe tem binpatch. E o mesmo arquivo que o
 * build.sh gera na visao de compilacao, antes da troca de nomes para SRG;</li>
 * <li>classe propria do server.jar (thermos.*, org.bukkit.craftbukkit.*, cpw.mods.fml.*): os bytes
 * do server.jar.</li>
 * </ul>
 * Para descobrir o valor: comando {@code hoshiecore hash <classe>} no servidor, ou
 * {@code sha256sum} do .class (para MC, da classe com binpatch).
 *
 * <p>Uma trava {@link Fase#EARLY} e conferida antes de registrar os mixins early do recurso; uma
 * {@link Fase#LATE}, antes dos mixins late. Se nao confere, ou o hash nao pode ser calculado, nada
 * daquele recurso e registrado e sai um aviso no log com a classe e o hash visto.
 */
public final class Trava {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private final Fase fase;
    private final String classe;
    private final Set<String> aceitos;

    private Trava(Fase fase, String classe, Set<String> aceitos) {
        this.fase = fase;
        this.classe = classe;
        this.aceitos = aceitos;
    }

    /** Trava conferida na fase early (classe do Minecraft ou do server.jar). */
    public static Trava early(String classe, String... sha256Aceitos) {
        return nova(Fase.EARLY, classe, sha256Aceitos);
    }

    /** Trava conferida na fase late (classe de mod). */
    public static Trava late(String classe, String... sha256Aceitos) {
        return nova(Fase.LATE, classe, sha256Aceitos);
    }

    private static Trava nova(Fase fase, String classe, String... hs) {
        if (classe == null || classe.isEmpty() || classe.indexOf('/') >= 0 || hs == null || hs.length == 0) {
            throw new IllegalArgumentException("trava precisa de classe (com pontos) e de pelo menos um sha256");
        }
        Set<String> s = new LinkedHashSet<String>();
        for (String h : hs) {
            String x = h == null ? "" : h.trim().toLowerCase(Locale.ROOT);
            if (!HEX64.matcher(x).matches()) {
                throw new IllegalArgumentException("sha256 invalido na trava de " + classe + ": " + h);
            }
            s.add(x);
        }
        return new Trava(fase, classe, Collections.unmodifiableSet(s));
    }

    public Fase fase() {
        return fase;
    }

    /** Nome da classe com pontos, como no codigo-fonte (ex.: net.minecraft.server.MinecraftServer). */
    public String classe() {
        return classe;
    }

    public Set<String> aceitos() {
        return aceitos;
    }

    @Override
    public String toString() {
        return fase + " " + classe + " " + aceitos;
    }
}
