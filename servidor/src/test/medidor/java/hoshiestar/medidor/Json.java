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
package hoshiestar.medidor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor de JSON minimo e estrito, so para os testes: confere que o .json do relatorio e valido (lanca
 * IllegalArgumentException com a posicao se nao for) e devolve Map/List/Double/String/Boolean/null.
 */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object ler(String texto) {
        Json j = new Json(texto);
        j.espaco();
        Object v = j.valor();
        j.espaco();
        if (j.i != j.s.length()) {
            throw j.erro("lixo depois do fim");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    static Object caminho(Object raiz, String... chaves) {
        Object o = raiz;
        for (String k : chaves) {
            if (o instanceof Map) {
                o = ((Map<String, Object>) o).get(k);
            } else if (o instanceof List) {
                o = ((List<Object>) o).get(Integer.parseInt(k));
            } else {
                return null;
            }
        }
        return o;
    }

    private IllegalArgumentException erro(String m) {
        return new IllegalArgumentException("JSON invalido na posicao " + i + ": " + m);
    }

    private void espaco() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private Object valor() {
        if (i >= s.length()) {
            throw erro("fim inesperado");
        }
        char c = s.charAt(i);
        if (c == '{') {
            return objeto();
        }
        if (c == '[') {
            return lista();
        }
        if (c == '"') {
            return texto();
        }
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        return numero();
    }

    private Map<String, Object> objeto() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;
        espaco();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            espaco();
            if (s.charAt(i) != '"') {
                throw erro("chave sem aspas");
            }
            String k = texto();
            espaco();
            if (s.charAt(i++) != ':') {
                throw erro("falta ':'");
            }
            espaco();
            if (m.containsKey(k)) {
                throw erro("chave repetida: " + k);
            }
            m.put(k, valor());
            espaco();
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw erro("falta ',' ou '}'");
            }
        }
    }

    private List<Object> lista() {
        List<Object> l = new ArrayList<Object>();
        i++;
        espaco();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            espaco();
            l.add(valor());
            espaco();
            char c = s.charAt(i++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw erro("falta ',' ou ']'");
            }
        }
    }

    private String texto() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw erro("caractere de controle dentro de texto");
            }
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: throw erro("escape invalido");
                }
            } else {
                b.append(c);
            }
        }
    }

    private Double numero() {
        int ini = i;
        if (i < s.length() && s.charAt(i) == '-') {
            i++;
        }
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String t = s.substring(ini, i);
        if (t.isEmpty() || !t.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            throw erro("numero invalido: '" + t + "'");
        }
        return Double.valueOf(t);
    }
}
