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

import hoshiestar.core.api.Opcao;
import hoshiestar.core.api.Recurso;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor/gravador pequeno do config/hoshiestar.cfg. So JDK: nao usa a Configuration do Forge nem
 * nenhuma classe do Minecraft, porque roda na fase de coremod.
 *
 * <p>Formato (por linha, UTF-8):
 * <pre>
 * # comentario (tambem vale ";")
 * [secao]
 * chave=valor
 * </pre>
 * Espacos nas pontas sao ignorados. A mesma secao pode aparecer mais de uma vez (as chaves se
 * somam). Chave repetida: vale a ultima (com aviso).
 *
 * <p>Regras:
 * <ul>
 * <li>arquivo nao existe: e criado com todas as chaves, os padroes e um comentario por chave;</li>
 * <li>arquivo existe: os valores do admin sao usados e o texto dele NAO e reescrito; chaves que
 * faltam sao acrescentadas no FIM do arquivo, num bloco com a data (gravacao em arquivo temporario +
 * troca, para nao deixar arquivo pela metade);</li>
 * <li>valor invalido: usa o padrao e avisa no log (o arquivo nao e alterado);</li>
 * <li>chave ou secao desconhecida: aviso, ignorada;</li>
 * <li>erro de leitura: todos os recursos com o padrao, aviso, e o arquivo nao e tocado; erro de
 * gravacao: aviso, e os valores lidos continuam valendo.</li>
 * </ul>
 */
public final class Config {

    /** Resultado da leitura (para o log e para os testes). */
    public static final class Resultado {
        public final List<String> avisos = new ArrayList<String>();
        public boolean criado;
        public int chavesAcrescentadas;
        public boolean usouPadroesPorErro;
    }

    private static final class Valor {
        final String texto;
        final int linha;

        Valor(String texto, int linha) {
            this.texto = texto;
            this.linha = linha;
        }
    }

    private Config() {
    }

    /**
     * Le o arquivo e resolve todas as opcoes dos recursos (cada {@link Opcao} recebe o valor). Nunca
     * lanca excecao.
     *
     * @param arq       config/hoshiestar.cfg
     * @param recursos  recursos validos (ids unicos)
     * @param versao    vai no comentario do bloco de chaves novas
     * @param data      idem (texto livre, ex. "2026-11-22")
     */
    public static Resultado carregar(File arq, List<Recurso> recursos, String versao, String data) {
        Resultado res = new Resultado();
        for (Recurso r : recursos) {
            for (Opcao o : r.opcoes()) {
                o.voltarAoPadrao();
            }
        }
        boolean existe;
        String conteudo = null;
        try {
            existe = arq.isFile();
            if (existe) {
                conteudo = new String(Files.readAllBytes(arq.toPath()), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            res.avisos.add("nao consegui ler " + arq + " (" + t + "); todos os recursos ficam com o padrao"
                    + " e o arquivo nao sera alterado");
            res.usouPadroesPorErro = true;
            return res;
        }

        Map<String, Map<String, Valor>> lidos = new LinkedHashMap<String, Map<String, Valor>>();
        if (existe) {
            analisar(conteudo, lidos, res.avisos);
        }

        Map<String, Recurso> porId = new LinkedHashMap<String, Recurso>();
        for (Recurso r : recursos) {
            porId.put(r.id(), r);
        }
        StringBuilder novos = new StringBuilder();
        for (Recurso r : recursos) {
            Map<String, Valor> sec = lidos.get(r.id());
            List<Opcao> faltam = new ArrayList<Opcao>();
            Map<String, Opcao> conhecidas = new LinkedHashMap<String, Opcao>();
            for (Opcao o : r.opcoes()) {
                conhecidas.put(o.nome(), o);
                Valor v = sec == null ? null : sec.get(o.nome());
                if (v == null) {
                    faltam.add(o);
                    continue;
                }
                String erro = o.aplicarTexto(v.texto);
                if (erro != null) {
                    o.voltarAoPadrao();
                    res.avisos.add("valor invalido em [" + r.id() + "] " + o.nome() + "=" + resumir(v.texto) + " (linha " + v.linha
                            + "): " + erro + "; usando o padrao " + o.padraoComoTexto());
                }
            }
            if (sec != null) {
                for (Map.Entry<String, Valor> e : sec.entrySet()) {
                    if (!conhecidas.containsKey(e.getKey())) {
                        res.avisos.add("chave desconhecida em [" + r.id() + "]: " + e.getKey() + " (linha " + e.getValue().linha
                                + "), ignorada");
                    }
                }
            }
            if (!faltam.isEmpty()) {
                res.chavesAcrescentadas += faltam.size();
                escreverSecao(novos, r, faltam, sec == null);
            }
        }
        for (String s : lidos.keySet()) {
            if (!porId.containsKey(s)) {
                res.avisos.add("secao [" + s + "] nao corresponde a nenhum recurso deste jar; ignorada (fica no arquivo)");
            }
        }

        if (novos.length() == 0) {
            return res;
        }
        StringBuilder saida = new StringBuilder();
        if (!existe) {
            res.criado = true;
            saida.append(cabecalho(versao));
            saida.append(novos);
        } else {
            saida.append(conteudo);
            if (conteudo.length() > 0 && !conteudo.endsWith("\n")) {
                saida.append('\n');
            }
            saida.append('\n');
            saida.append("# ---- chaves novas acrescentadas pelo Hoshiestar-Core ").append(versao).append(" em ").append(data)
                    .append(" (os valores acima foram mantidos) ----\n");
            saida.append(novos);
        }
        try {
            gravar(arq, saida.toString());
        } catch (Throwable t) {
            res.avisos.add("nao consegui gravar " + arq + " (" + t + "); os valores lidos continuam valendo, as chaves"
                    + " novas ficam com o padrao");
            res.criado = false;
            res.chavesAcrescentadas = 0;
        }
        return res;
    }

    private static void analisar(String conteudo, Map<String, Map<String, Valor>> lidos, List<String> avisos) {
        String c = conteudo;
        if (c.length() > 0 && c.charAt(0) == '﻿') {
            c = c.substring(1);
        }
        String[] linhas = c.split("\r\n|\n|\r", -1);
        String secao = null;
        for (int i = 0; i < linhas.length; i++) {
            int n = i + 1;
            String l = linhas[i].trim();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith(";")) {
                continue;
            }
            if (l.startsWith("[")) {
                if (!l.endsWith("]") || l.length() < 3) {
                    avisos.add("linha " + n + " ignorada: cabecalho de secao malformado: " + resumir(l));
                    secao = null;
                    continue;
                }
                secao = l.substring(1, l.length() - 1).trim();
                if (!lidos.containsKey(secao)) {
                    lidos.put(secao, new LinkedHashMap<String, Valor>());
                }
                continue;
            }
            int eq = l.indexOf('=');
            if (eq <= 0) {
                avisos.add("linha " + n + " ignorada: esperava chave=valor: " + resumir(l));
                continue;
            }
            if (secao == null) {
                avisos.add("linha " + n + " ignorada: chave fora de secao: " + resumir(l));
                continue;
            }
            String k = l.substring(0, eq).trim();
            String v = l.substring(eq + 1).trim();
            Map<String, Valor> sec = lidos.get(secao);
            if (sec.containsKey(k)) {
                avisos.add("chave repetida em [" + secao + "] " + k + " (linha " + n + "): vale a ultima");
            }
            sec.put(k, new Valor(v, n));
        }
    }

    static String cabecalho(String versao) {
        return "# Hoshiestar-Core " + versao + ": configuracao.\n"
                + "# Lido uma vez, na partida do servidor (mudou algo? reinicie).\n"
                + "# Formato: [secao] e chave=valor; linhas com # sao comentarios.\n"
                + "# Cada secao e um recurso. Recurso com ligado=false nao registra mixin nenhum: as classes do jogo e\n"
                + "# dos mods ficam com o bytecode original. Valor invalido: o recurso usa o padrao e avisa no log.\n"
                + "# Chaves que faltarem sao acrescentadas no fim do arquivo; o que voce escreveu nao e apagado.\n";
    }

    private static void escreverSecao(StringBuilder sb, Recurso r, List<Opcao> opcoes, boolean secaoNova) {
        sb.append('\n');
        if (secaoNova) {
            sb.append("# ==== ").append(r.id()).append(": ").append(umaLinha(r.descricao())).append('\n');
        }
        sb.append('[').append(r.id()).append("]\n");
        for (Opcao o : opcoes) {
            sb.append("# ").append(o.comentario());
            sb.append(" (tipo: ").append(o.tipo());
            if (o.faixa() != null) {
                sb.append("; aceita: ").append(o.faixa());
            }
            sb.append("; padrao: ").append(o.padraoComoTexto().isEmpty() ? "vazio" : o.padraoComoTexto()).append(")\n");
            sb.append(o.nome()).append('=').append(o.padraoComoTexto()).append('\n');
        }
    }

    private static void gravar(File arq, String texto) throws IOException {
        File pasta = arq.getAbsoluteFile().getParentFile();
        if (pasta != null && !pasta.isDirectory() && !pasta.mkdirs()) {
            throw new IOException("nao consegui criar a pasta " + pasta);
        }
        File tmp = new File(pasta, arq.getName() + ".tmp");
        Files.write(tmp.toPath(), texto.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp.toPath(), arq.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), arq.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String umaLinha(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }

    private static String resumir(String s) {
        String x = umaLinha(s);
        return x.length() > 80 ? x.substring(0, 80) + "..." : x;
    }
}
