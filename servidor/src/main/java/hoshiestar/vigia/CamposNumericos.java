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
package hoshiestar.vigia;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Descreve um pacote de rede SEM nenhum texto dele: so o nome da classe e os campos de tipo numerico
 * ou booleano (int, long, short, byte, float, double, boolean), da classe e das superclasses.
 * Campos String, char, arrays e objetos (texto de chat, de placa, de comando, itens, NBT, bytes de
 * canal de mod) nunca sao lidos. Correcao de privacidade do conferente: um C01PacketChatMessage sai
 * so como o nome da classe.
 *
 * <p>Nao chama toString, nem metodo nenhum do pacote: so le campos por reflexao (pode ver valor velho
 * ou de momentos diferentes; isso so muda o texto do relatorio). Nunca lanca.
 *
 * <p>Campos so de classes do Minecraft e do FML ({@link #COM_CAMPOS}); de pacote de mod, so o nome da
 * classe (revisao M4): {@code getDeclaredFields} faz a JVM resolver o tipo de TODOS os campos da classe,
 * e um tipo ainda nao carregado seria carregado pelo class loader do jogo (com os transformadores dos
 * mods) na thread do vigia. Os tipos dos campos dos pacotes do Minecraft/FML ja estao carregados quando
 * o pacote existe (risco residual: um mod que acrescente por ASM/mixin um campo de tipo proprio a um
 * pacote do Minecraft; nao visto neste modpack, nao verificado em geral).
 */
public final class CamposNumericos {

    /** No maximo tantos campos por pacote (o relatorio fica curto). */
    static final int MAX_CAMPOS = 12;

    /** Prefixos de classe cujos campos numericos sao lidos (classe e superclasses). */
    static final String[] COM_CAMPOS = {"net.minecraft.", "cpw.mods.fml."};

    private CamposNumericos() {
    }

    /** "NomeSimples (pacote.Completo) campos: a=1, b=true" ou so "NomeSimples (pacote.Completo)". */
    public static String descrever(Object pacote) {
        return descrever(pacote, COM_CAMPOS);
    }

    /** Como {@link #descrever(Object)}, lendo campos so de classes com um dos prefixos dados (os testes passam o deles). */
    public static String descrever(Object pacote, String[] prefixosComCampos) {
        if (pacote == null) {
            return "nenhum";
        }
        Class<?> c = pacote.getClass();
        StringBuilder sb = new StringBuilder();
        sb.append(nomeSimples(c)).append(" (").append(c.getName()).append(')');
        int n = 0;
        StringBuilder campos = new StringBuilder();
        boolean pulou = false;
        try {
            for (Class<?> k = c; k != null && k != Object.class && n < MAX_CAMPOS; k = k.getSuperclass()) {
                if (!comPrefixo(k.getName(), prefixosComCampos)) {
                    pulou = true; // classe de mod: nem pede os campos (nao carrega tipo nenhum)
                    continue;
                }
                for (Field f : k.getDeclaredFields()) {
                    if (n >= MAX_CAMPOS) {
                        break;
                    }
                    if (Modifier.isStatic(f.getModifiers()) || !numericoOuBooleano(f.getType())) {
                        continue;
                    }
                    String v;
                    try {
                        f.setAccessible(true);
                        v = String.valueOf(f.get(pacote)); // tipo primitivo numerico/boolean: o valor vem embrulhado (Integer, Double...)
                    } catch (Throwable t) {
                        v = "?";
                    }
                    campos.append(n == 0 ? "" : ", ").append(f.getName()).append('=').append(v);
                    n++;
                }
            }
        } catch (Throwable t) {
            campos.append(n == 0 ? "" : ", ").append("(erro ao ler campos)");
        }
        if (campos.length() > 0) {
            sb.append(" campos: ").append(campos);
        }
        if (pulou) {
            sb.append(" (campos de classe de mod nao lidos)");
        }
        return sb.toString();
    }

    private static boolean comPrefixo(String nome, String[] prefixos) {
        if (prefixos == null) {
            return false;
        }
        for (String p : prefixos) {
            if (nome.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Nome curto da classe sem {@code Class.getSimpleName()} (no Java 8 ele lanca InternalError em classe
     * aninhada de nome fora do padrao, como algumas do Scala; revisao M7): o nome depois do ultimo '.' e,
     * se for classe aninhada com nome, depois do ultimo '$' ("Apoio$PacoteTeste" -&gt; "PacoteTeste";
     * anonima "X$1" fica "X$1"). Nunca lanca.
     */
    public static String nomeSimples(Class<?> c) {
        if (c == null) {
            return "?";
        }
        String n = c.getName();
        int ponto = n.lastIndexOf('.');
        if (ponto >= 0) {
            n = n.substring(ponto + 1);
        }
        int dolar = n.lastIndexOf('$');
        if (dolar >= 0 && dolar + 1 < n.length() && !Character.isDigit(n.charAt(dolar + 1))) {
            n = n.substring(dolar + 1);
        }
        return n;
    }

    static boolean numericoOuBooleano(Class<?> t) {
        return t == int.class || t == long.class || t == short.class || t == byte.class
                || t == float.class || t == double.class || t == boolean.class;
    }
}
