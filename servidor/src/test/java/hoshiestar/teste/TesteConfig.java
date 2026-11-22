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
package hoshiestar.teste;

import hoshiestar.config.HoshiestarConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/** Leitura e gravacao do hoshiestar.yml: ausente, valido, chave faltando, valor invalido, lixo. */
public final class TesteConfig {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] a) throws Exception {
        Verifica v = new Verifica("TesteConfig");
        File dir = new File(a[0]);
        dir.mkdirs();

        // ausente: cria com comentarios e padroes
        File f = new File(dir, "ausente.yml");
        f.delete();
        HoshiestarConfig c = HoshiestarConfig.carregar(f);
        String t = ler(f);
        v.sim(f.exists(), "ausente: arquivo criado");
        v.sim(t.contains("# Autoteste das correcoes embutidas") && t.contains("# Hoshiestar"), "ausente: com comentarios");
        v.sim(t.contains("autoteste:") && t.contains("ligado: false"), "ausente: com o padrao (desligado)");
        v.igual(false, c.autoteste_ligado, "ausente: valor padrao");
        v.sim(c.avisos().isEmpty() && !c.usouPadroes(), "ausente: sem avisos");

        // valido: valor do admin preservado
        f = arquivo(dir, "valido.yml", "autoteste:\n  ligado: true\n");
        c = HoshiestarConfig.carregar(f);
        v.igual(true, c.autoteste_ligado, "valido: valor do admin vale");
        t = ler(f);
        v.sim(t.contains("ligado: true") && t.contains("# Autoteste"), "valido: regravado com comentarios e o valor do admin");
        c = HoshiestarConfig.carregar(f);
        v.igual(true, c.autoteste_ligado, "valido: relido igual depois de regravar");

        // chave faltando: entra o padrao, chave desconhecida fica
        f = arquivo(dir, "faltando.yml", "outra:\n  coisa: 5\n");
        c = HoshiestarConfig.carregar(f);
        t = ler(f);
        v.igual(false, c.autoteste_ligado, "faltando: padrao");
        v.sim(t.contains("ligado: false"), "faltando: chave acrescentada");
        v.sim(t.contains("coisa: 5"), "faltando: chave desconhecida preservada");

        // valor invalido: padrao, aviso, arquivo intocado
        String invalido = "# meu comentario\nautoteste:\n  ligado: talvez\n";
        f = arquivo(dir, "invalido.yml", invalido);
        c = HoshiestarConfig.carregar(f);
        v.igual(false, c.autoteste_ligado, "invalido: padrao");
        v.sim(!c.avisos().isEmpty() && c.avisos().get(0).contains("autoteste.ligado"), "invalido: aviso cita a chave (" + c.avisos() + ")");
        v.igual(invalido, ler(f), "invalido: arquivo NAO regravado");

        f = arquivo(dir, "numero.yml", "autoteste:\n  ligado: 1\n");
        c = HoshiestarConfig.carregar(f);
        v.sim(!c.autoteste_ligado && !c.avisos().isEmpty(), "numero onde se espera boolean: padrao e aviso");

        // valor vazio: o YAML le como nulo e o mecanismo do Crucible trata como chave ausente
        f = arquivo(dir, "vazio-valor.yml", "autoteste:\n  ligado:\n");
        c = HoshiestarConfig.carregar(f);
        v.sim(!c.autoteste_ligado && ler(f).contains("ligado: false"), "valor vazio: tratado como ausente (padrao gravado)");

        // lixo: padroes, aviso, arquivo intocado
        String lixo = "autoteste: [ligado: {{{ :::\n\t\u0000 ruido";
        f = arquivo(dir, "lixo.yml", lixo);
        c = HoshiestarConfig.carregar(f);
        v.sim(c.usouPadroes() && !c.avisos().isEmpty(), "lixo: padroes e aviso (" + c.avisos() + ")");
        v.igual(false, c.autoteste_ligado, "lixo: valor padrao");
        v.igual(lixo, ler(f), "lixo: arquivo intocado");

        String lista = "- a\n- b\n";
        f = arquivo(dir, "lista.yml", lista);
        c = HoshiestarConfig.carregar(f);
        v.sim(c.usouPadroes(), "lista no topo (nao e mapa): padroes");
        v.igual(lista, ler(f), "lista no topo: arquivo intocado");

        // vazio: tratado como sem chaves (recebe os padroes)
        f = arquivo(dir, "vazio.yml", "");
        c = HoshiestarConfig.carregar(f);
        v.sim(!c.usouPadroes() && ler(f).contains("ligado: false"), "arquivo vazio: recebe os padroes");

        // pasta no lugar do arquivo: nao derruba
        File pasta = new File(dir, "pasta.yml");
        pasta.mkdirs();
        c = HoshiestarConfig.carregar(pasta);
        v.sim(!c.avisos().isEmpty() && !c.autoteste_ligado, "pasta no lugar do arquivo: aviso e padroes, sem excecao");
        v.fim();
    }

    private static File arquivo(File dir, String nome, String conteudo) throws Exception {
        File f = new File(dir, nome);
        FileOutputStream o = new FileOutputStream(f);
        o.write(conteudo.getBytes(UTF8));
        o.close();
        return f;
    }

    private static String ler(File f) throws Exception {
        return new String(Verifica.ler(new java.io.FileInputStream(f)), UTF8);
    }

    static boolean iguais(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
