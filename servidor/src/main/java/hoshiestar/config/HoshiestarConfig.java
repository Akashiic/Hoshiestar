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
package hoshiestar.config;

import hoshiestar.Hoshiestar;
import io.github.crucible.util.config.Comments;
import io.github.crucible.util.config.ConfigMode;
import io.github.crucible.util.config.ConfigSection;
import io.github.crucible.util.config.InvalidConfigurationException;
import io.github.crucible.util.config.Path;
import io.github.crucible.util.config.YamlConfig;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * O hoshiestar.yml: um interruptor por recurso do Hoshiestar. Usa o mesmo mecanismo do
 * Crucible.yml ({@link YamlConfig} do Crucible, com {@code @Comment}/{@code @Comments}); os nomes
 * dos campos viram caminhos trocando "_" por "." (ex.: {@code autoteste_ligado} vira
 * {@code autoteste: ligado:}).
 *
 * <p><b>Quando e lido:</b> uma vez, na fase de coremod (em {@code CrucibleCoremodHook}, logo
 * depois do Crucible.yml e antes de qualquer classe do Minecraft ou de mod). Mudou? Reinicie.
 * Esta classe so pode usar o JDK e o pacote de config do Crucible (nada do Minecraft).
 *
 * <p><b>Regras de leitura</b> (diferentes do Crucible.yml num ponto: aqui um erro nunca apaga o
 * texto do admin):
 * <ul>
 * <li>arquivo nao existe: e criado com todos os campos, os padroes e os comentarios;</li>
 * <li>arquivo existe, tudo e valido e nada falta: os valores do admin valem e o arquivo NAO e
 * regravado (comentarios do admin, data de modificacao e permissao ficam como estao);</li>
 * <li>tudo valido mas falta algum item (campo novo de uma versao nova, ou item presente sem valor,
 * como "ligado:" vazio): o item usa o padrao e o arquivo e regravado com ele e com os comentarios
 * atuais (como o Crucible faz com o Crucible.yml: comentarios proprios do admin se perdem, valores
 * ficam);</li>
 * <li>algum problema: valor invalido (ex.: "talvez" onde se espera true/false), secao no formato
 * errado (ex.: "autoteste: true" em vez de "autoteste:" com "ligado: true" dentro) ou chave
 * desconhecida (erro de digitacao): aquele item usa o padrao, sai um aviso por problema, e o
 * arquivo NAO e regravado (o texto do admin fica como esta para ele corrigir);</li>
 * <li>arquivo ilegivel ou com lixo (nao e YAML, ou nao e um mapa): todos os campos usam o padrao,
 * sai um aviso, e o arquivo NAO e tocado;</li>
 * <li>nada disso derruba o servidor.</li>
 * </ul>
 *
 * <p><b>Como acrescentar um campo</b> (cada recurso novo faz isso): um campo publico, nao final,
 * nao estatico, com o padrao "desligado" e um {@code @Comments} em portugues; o nome comeca com o
 * id do recurso (ex.: {@code chunkmap_ligado}). Tipos aceitos: boolean, int, long, double, String
 * e listas (como no Crucible.yml).
 */
public final class HoshiestarConfig extends YamlConfig {

    private static final String[] CABECALHO = {
        "Hoshiestar - um interruptor por recurso.",
        "Desligado = o servidor se comporta como o Crucible original naquele ponto.",
        "Este arquivo e lido uma vez, no boot. Mudou? Reinicie o servidor.",
        "Valor invalido, secao no formato errado ou chave desconhecida: aquele item usa o padrao,",
        "sai um aviso no log e o arquivo nao e regravado.",
    };

    // ---------------------------------------------------------------- campos (um por interruptor)

    @Comments({"Autoteste das correcoes embutidas (so para diagnostico; deixe desligado em producao).",
        "Ligado: aplica duas correcoes inofensivas numa classe do OpenBlocks (so contam chamadas) e",
        "tenta uma correcao com trava de versao errada de proposito noutra classe, para provar que a",
        "recusa funciona. O resultado aparece no log ao fim do boot e em 'hoshie status'."})
    public boolean autoteste_ligado = false;

    // ---------------------------------------------------------------- leitura

    private static volatile HoshiestarConfig atual;

    private final transient List<String> avisos = new ArrayList<String>();
    private transient boolean usouPadroes;

    private HoshiestarConfig(File arquivo) {
        CONFIG_FILE = arquivo;
        CONFIG_MODE = ConfigMode.PATH_BY_UNDERSCORE;
        CONFIG_HEADER = CABECALHO;
    }

    /** O config lido no boot; se ainda nao foi lido, os padroes (sem tocar em arquivo). */
    public static HoshiestarConfig atual() {
        HoshiestarConfig c = atual;
        return c != null ? c : new HoshiestarConfig(new File(Hoshiestar.ARQUIVO_CONFIG));
    }

    /** Le o arquivo padrao (hoshiestar.yml na pasta do servidor) e guarda como {@link #atual()}. */
    public static HoshiestarConfig carregarPadrao() {
        HoshiestarConfig c = carregar(new File(Hoshiestar.ARQUIVO_CONFIG));
        atual = c;
        return c;
    }

    /**
     * Le (ou cria) o arquivo dado seguindo as regras da classe. Nunca lanca excecao: em erro devolve
     * os padroes; os avisos ficam em {@link #avisos()}.
     */
    public static HoshiestarConfig carregar(File arquivo) {
        HoshiestarConfig c = new HoshiestarConfig(arquivo);
        try {
            c.ler();
        } catch (Throwable t) {
            HoshiestarConfig p = new HoshiestarConfig(arquivo);
            p.usouPadroes = true;
            p.avisos.addAll(c.avisos);
            p.avisos.add("erro inesperado ao ler " + arquivo + ": " + t + "; usando os padroes");
            return p;
        }
        return c;
    }

    /** Avisos da leitura (valores invalidos, arquivo ilegivel, erro ao gravar). Vazio = tudo certo. */
    public List<String> avisos() {
        return Collections.unmodifiableList(avisos);
    }

    /** true se o arquivo nao pode ser lido e todos os campos ficaram no padrao. */
    public boolean usouPadroes() {
        return usouPadroes;
    }

    /** O arquivo deste config. */
    public File arquivo() {
        return CONFIG_FILE;
    }

    private void ler() {
        if (!CONFIG_FILE.exists()) {
            gravar("criar");
            return;
        }
        try {
            loadFromYaml();
        } catch (InvalidConfigurationException e) {
            usouPadroes = true;
            Throwable causa = e.getCause() != null ? e.getCause() : e;
            avisos.add("nao consegui ler " + CONFIG_FILE + " (" + resumo(causa)
                    + "); TODOS os itens usam o padrao e o arquivo NAO foi alterado. Corrija ou apague o arquivo.");
            return;
        }
        HoshiestarConfig padroes = new HoshiestarConfig(CONFIG_FILE);
        List<String> conhecidos = new ArrayList<String>();
        int problemas = 0;
        int faltando = 0;
        for (Field f : getClass().getDeclaredFields()) {
            if (doSkip(f)) {
                continue;
            }
            String caminho = caminho(f);
            conhecidos.add(caminho);
            // secao no formato errado (ex.: "autoteste: true" em vez de "autoteste:" com "ligado: true"
            // dentro): sem isto a chave contaria como "faltando" e o valor do admin sumiria sem aviso
            String errado = prefixoQueNaoESecao(caminho);
            if (errado != null) {
                problemas++;
                avisos.add("'" + errado + "' deveria ser uma secao (com '" + caminho.substring(errado.length() + 1)
                        + ":' dentro), mas tem o valor " + descreverValor(valorBruto(errado)) + "; '" + caminho + "' usa o padrao " + valorDe(f, padroes));
                continue;
            }
            Object bruto = root.has(caminho) ? valorBruto(caminho) : null;
            if (bruto == null) {
                // ausente, ou presente sem valor ("ligado:" vazio): usa o padrao e entra no arquivo
                faltando++;
                continue;
            }
            try {
                if (Modifier.isPrivate(f.getModifiers())) {
                    f.setAccessible(true);
                }
                converter.fromConfig(this, f, root, caminho);
            } catch (Throwable t) {
                problemas++;
                try {
                    f.set(this, f.get(padroes));
                } catch (Throwable t3) {
                    // campo continua com o valor do construtor (o padrao)
                }
                avisos.add("valor invalido em '" + caminho + "' (" + descreverValor(bruto) + ", esperado "
                        + f.getType().getSimpleName() + "); usando o padrao " + valorDe(f, padroes));
            }
        }
        List<String> desconhecidas = new ArrayList<String>();
        procurarDesconhecidas(root, "", conhecidos, desconhecidas);
        for (String d : desconhecidas) {
            problemas++;
            avisos.add("chave desconhecida '" + d + "' (ignorada; erro de digitacao, ou item de outra versao do Hoshiestar?)");
        }
        if (problemas > 0) {
            avisos.add(CONFIG_FILE + " nao foi regravado para preservar o seu texto; corrija o(s) item(ns) acima"
                    + (faltando > 0 ? " (faltam tambem " + faltando + " item(ns), que usam o padrao)" : ""));
            return;
        }
        if (faltando > 0) {
            // so regrava quando ha item novo a acrescentar: um arquivo completo e valido fica como o
            // admin deixou (comentarios dele, data de modificacao, permissao somente-leitura)
            gravar("atualizar");
        }
    }

    /** O valor cru no caminho, sem criar secoes no caminho (o get do ConfigSection cria). */
    private Object valorBruto(String caminho) {
        Object atual = root;
        for (String parte : caminho.split("\\.")) {
            if (!(atual instanceof ConfigSection)) {
                return null;
            }
            atual = ((ConfigSection) atual).getRawMap().get(parte);
        }
        return atual;
    }

    /**
     * O primeiro prefixo do caminho (ex.: "autoteste" em "autoteste.ligado") que existe no arquivo
     * com um valor que nao e uma secao, ou null. Prefixo presente e vazio conta como secao vazia.
     */
    private String prefixoQueNaoESecao(String caminho) {
        int i = caminho.indexOf('.');
        while (i > 0) {
            String prefixo = caminho.substring(0, i);
            Object v = valorBruto(prefixo);
            if (v != null && !(v instanceof ConfigSection)) {
                return prefixo;
            }
            if (v == null) {
                return null;
            }
            i = caminho.indexOf('.', i + 1);
        }
        return null;
    }

    /** Chaves do arquivo que nao sao campo desta classe nem secao que leva a um campo. */
    private static void procurarDesconhecidas(ConfigSection secao, String base, List<String> conhecidos, List<String> saida) {
        for (Object k : secao.getRawMap().keySet()) {
            String caminho = base.isEmpty() ? String.valueOf(k) : base + "." + k;
            if (conhecidos.contains(caminho)) {
                continue;
            }
            boolean prefixo = false;
            for (String c : conhecidos) {
                if (c.startsWith(caminho + ".")) {
                    prefixo = true;
                    break;
                }
            }
            if (!prefixo) {
                saida.add(caminho);
            } else {
                Object v = secao.getRawMap().get(k);
                if (v instanceof ConfigSection) {
                    procurarDesconhecidas((ConfigSection) v, caminho, conhecidos, saida);
                }
                // prefixo com valor que nao e secao ja foi avisado como "deveria ser uma secao"
            }
        }
    }

    private void gravar(String acao) {
        try {
            if (CONFIG_FILE.getParentFile() != null) {
                CONFIG_FILE.getParentFile().mkdirs();
            }
            save();
        } catch (Throwable t) {
            avisos.add("nao consegui " + acao + " " + CONFIG_FILE + " (" + resumo(t) + "); os valores lidos continuam valendo");
        }
    }

    private String caminho(Field f) {
        if (f.isAnnotationPresent(Path.class)) {
            return f.getAnnotation(Path.class).value();
        }
        return f.getName().replace('_', '.');
    }

    private static String valorDe(Field f, Object o) {
        try {
            return String.valueOf(f.get(o));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String descreverValor(Object v) {
        if (v == null) {
            return "vazio";
        }
        String s = String.valueOf(v);
        if (s.length() > 40) {
            s = s.substring(0, 40) + "...";
        }
        return "'" + s + "' (" + v.getClass().getSimpleName() + ")";
    }

    private static String resumo(Throwable t) {
        String m = t.getMessage();
        if (m != null && m.length() > 200) {
            m = m.substring(0, 200) + "...";
        }
        return t.getClass().getSimpleName() + (m != null ? ": " + m.replace('\n', ' ') : "");
    }
}
