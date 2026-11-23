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
package hoshiestar.observador;

/** Escritor de JSON minimo (so o que o relatorio precisa). Nao valida a ordem das chamadas. */
final class Json {

    private final StringBuilder sb = new StringBuilder(4096);
    private boolean primeiro = true;
    private int nivel;

    private void sep() {
        if (!primeiro) {
            sb.append(',');
        }
        sb.append('\n');
        for (int i = 0; i < nivel; i++) {
            sb.append("  ");
        }
        primeiro = false;
    }

    Json objeto() {
        sep();
        sb.append('{');
        nivel++;
        primeiro = true;
        return this;
    }

    Json objeto(String nome) {
        sep();
        sb.append(texto(nome)).append(": {");
        nivel++;
        primeiro = true;
        return this;
    }

    Json fimObjeto() {
        nivel--;
        sb.append('\n');
        for (int i = 0; i < nivel; i++) {
            sb.append("  ");
        }
        sb.append('}');
        primeiro = false;
        return this;
    }

    Json lista(String nome) {
        sep();
        sb.append(texto(nome)).append(": [");
        nivel++;
        primeiro = true;
        return this;
    }

    Json fimLista() {
        nivel--;
        if (!primeiro) {
            sb.append('\n');
            for (int i = 0; i < nivel; i++) {
                sb.append("  ");
            }
        }
        sb.append(']');
        primeiro = false;
        return this;
    }

    Json campo(String nome, String v) {
        sep();
        sb.append(texto(nome)).append(": ").append(v == null ? "null" : texto(v));
        return this;
    }

    Json campo(String nome, long v) {
        sep();
        sb.append(texto(nome)).append(": ").append(v);
        return this;
    }

    Json campo(String nome, double v) {
        sep();
        sb.append(texto(nome)).append(": ").append(String.format(java.util.Locale.ROOT, "%.3f", v));
        return this;
    }

    Json campo(String nome, boolean v) {
        sep();
        sb.append(texto(nome)).append(": ").append(v);
        return this;
    }

    /** Elemento solto de lista (texto). */
    Json item(String v) {
        sep();
        sb.append(texto(v));
        return this;
    }

    @Override
    public String toString() {
        return sb.append('\n').toString();
    }

    static String texto(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }
}
