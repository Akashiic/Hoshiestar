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

import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.teste.Verifica;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;

/**
 * A chave medidor.habilitado no hoshiestar.yml (padrao true, comentario em portugues), o registro do recurso e o
 * efeito de false (o comando recusa e os ganchos de tick nao fazem nada).
 *
 * <p>Uso: {@code java hoshiestar.medidor.TesteMedidorConfig <pasta de trabalho>}.
 */
public final class TesteMedidorConfig {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteMedidorConfig");
        File base = new File(args[0]);
        base.mkdirs();
        File f = new File(base, "hoshiestar.yml");
        f.delete();
        HoshiestarConfig c = HoshiestarConfig.carregar(f);
        String t = new String(Files.readAllBytes(f.toPath()), UTF8);
        v.sim(c.avisos().isEmpty(), "ausente: sem avisos " + c.avisos());
        v.igual(true, c.medidor_habilitado, "padrao: medidor.habilitado = true");
        int i = t.indexOf("\nmedidor:");
        v.sim(i >= 0 && t.indexOf("habilitado: true", i) > i, "arquivo criado tem 'medidor:' com 'habilitado: true'");
        v.sim(t.contains("# Medidor de carga por regiao"), "arquivo criado tem o comentario do medidor");
        v.igual(4.0, c.medidor_margemCusto, "padrao: medidor.margem_custo = 4.0");
        v.sim(t.indexOf("margem_custo: 4.0", i) > i, "arquivo criado tem 'margem_custo: 4.0' na secao medidor");

        String admin = t.substring(0, t.indexOf("habilitado: true", i)) + "habilitado: false"
                + t.substring(t.indexOf("habilitado: true", i) + "habilitado: true".length());
        Files.write(f.toPath(), admin.getBytes(UTF8));
        c = HoshiestarConfig.carregar(f);
        v.sim(c.avisos().isEmpty(), "admin: sem avisos " + c.avisos());
        v.igual(false, c.medidor_habilitado, "admin: medidor.habilitado false lido");
        String admin2 = admin.replace("margem_custo: 4.0", "margem_custo: 2.5");
        Files.write(f.toPath(), admin2.getBytes(UTF8));
        HoshiestarConfig c2 = HoshiestarConfig.carregar(f);
        v.sim(c2.avisos().isEmpty() && c2.medidor_margemCusto == 2.5, "admin: margem_custo 2.5 lida " + c2.avisos());
        Recursos.limparParaTeste();
        RegistroMedidor.registrar(c2, Correcoes.global());
        v.sim(Medidor.margemCusto() == 2.5, "registro: margem 2.5 vale");
        Files.write(f.toPath(), admin.replace("margem_custo: 4.0", "margem_custo: 99").getBytes(UTF8));
        Recursos.limparParaTeste();
        RegistroMedidor.registrar(HoshiestarConfig.carregar(f), Correcoes.global());
        v.sim(Medidor.margemCusto() == 4.0, "registro: margem 99 fora de 1..20 vira o padrao 4");
        Files.write(f.toPath(), admin.getBytes(UTF8));
        c = HoshiestarConfig.carregar(f);
        v.igual(admin, new String(Files.readAllBytes(f.toPath()), UTF8), "admin: arquivo valido e completo nao foi regravado");

        Recursos.limparParaTeste();
        RegistroMedidor.registrar(c, Correcoes.global());
        Recursos.Recurso r = Recursos.achar("medidor");
        v.sim(r != null && !r.ligado, "registro com false: recurso 'medidor' desligado no status");
        List<String> resp = Medidor.comandoMedir(30, false);
        v.sim(!Medidor.ATIVO && resp.get(0).contains("medidor.habilitado: false"), "false: hoshie medir recusa: " + resp);
        Medidor.inicioTickServidor();
        Medidor.fimTickServidor();
        v.sim(!Medidor.ATIVO, "false: ganchos de tick nao fazem nada");

        Recursos.limparParaTeste();
        HoshiestarConfig p = HoshiestarConfig.carregar(new File(base, "padrao.yml"));
        RegistroMedidor.registrar(p, Correcoes.global());
        r = Recursos.achar("medidor");
        String linha = r == null ? null : r.linhaDeEstado();
        v.sim(r != null && r.ligado && linha != null && linha.startsWith("aguardando o servidor ligar"),
                "registro com o padrao: recurso ligado; antes do servidor: " + linha);
        Medidor.definirJogo(new Jogo() {
            @Override
            public boolean morto(Object o, int tipo) {
                return false;
            }

            @Override
            public List<Mundo> mundos() {
                return new java.util.ArrayList<Mundo>();
            }

            @Override
            public File pastaDeSaida() {
                return new File("x");
            }
        }, "vanilla", "Akashic-World-Firewall ausente");
        linha = r.linhaDeEstado();
        v.sim(linha.startsWith("pronto; nenhuma medicao em andamento") && linha.contains("tile entities=vanilla")
                && linha.contains("entidades=sim") && linha.contains("jogadores pela rede=sim"),
                "linha do status com o servidor de pe: " + linha);

        jarAntigo(v, base, p);
        v.fim();
    }

    /**
     * Revisao I1: o jar do mod antigo Hoshiestar-Medidor (1.0.x) em mods/ tem classes com o mesmo nome das do
     * server.jar; o registro (fase de coremod) lista mods/, desabilita o medidor e diz o que fazer.
     */
    private static void jarAntigo(Verifica v, File base, HoshiestarConfig p) throws Exception {
        // conferencia pela classe (servidor de pe): so a classe MedidorMod do mod antigo conta, mesmo num jar renomeado
        File semMod = new File(base, "cl-sem-mod/hoshiestar/medidor");
        semMod.mkdirs();
        Files.write(new File(semMod, "Medidor.class").toPath(), new byte[] {1});
        java.net.URLClassLoader cl1 = new java.net.URLClassLoader(new java.net.URL[] {
            new File(base, "cl-sem-mod").toURI().toURL()}, null);
        v.sim(Medidor.conferirClasseAntiga(cl1) == null && Medidor.motivoDesabilitado() == null,
                "conferirClasseAntiga: sem MedidorMod no classpath = nada");
        File jarRenomeado = new File(base, "outro-nome.jar");
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(jarRenomeado));
        z.putNextEntry(new java.util.zip.ZipEntry("hoshiestar/medidor/MedidorMod.class"));
        z.write(1);
        z.closeEntry();
        z.close();
        java.net.URLClassLoader cl2 = new java.net.URLClassLoader(new java.net.URL[] {jarRenomeado.toURI().toURL()}, null);
        v.igual("outro-nome.jar", Medidor.conferirClasseAntiga(cl2), "conferirClasseAntiga: acha o mod antigo num jar renomeado");
        v.sim(!Medidor.habilitado() && Medidor.motivoDesabilitado().contains("MedidorMod em outro-nome.jar"),
                "conferirClasseAntiga: desabilita com o nome do jar: " + Medidor.motivoDesabilitado());

        v.sim(Medidor.jarsAntigos(new File(base, "nao-existe")) == null, "jarsAntigos: pasta mods/ ausente = nenhum");
        File mods = new File(base, "mods");
        mods.mkdirs();
        for (String n : new String[] {"outro-mod.jar", "Hoshiestar-Medidor-1.0.4-serveronly.jar.disabled", "leia.txt"}) {
            Files.write(new File(mods, n).toPath(), new byte[] {1});
        }
        v.sim(Medidor.jarsAntigos(mods) == null, "jarsAntigos: so outros mods e jar renomeado para .disabled = nenhum");
        Files.write(new File(mods, "Hoshiestar-Medidor-1.0.4-serveronly.jar").toPath(), new byte[] {1});
        Files.write(new File(mods, "hoshiestar-medidor-1.0.2.JAR").toPath(), new byte[] {1});
        v.igual("Hoshiestar-Medidor-1.0.4-serveronly.jar, hoshiestar-medidor-1.0.2.JAR", Medidor.jarsAntigos(mods),
                "jarsAntigos: acha os jars do mod antigo (maiusculas ou minusculas)");
        // o registro le "mods" relativo ao diretorio de trabalho do servidor; o script roda este teste dentro de base
        if (!new File("mods").getCanonicalFile().equals(mods.getCanonicalFile())) {
            System.out.println("  AVISO teste do registro com jar antigo PULADO (diretorio de trabalho nao e " + base + ")");
            return;
        }
        Recursos.limparParaTeste();
        RegistroMedidor.registrar(p, Correcoes.global());
        Recursos.Recurso r = Recursos.achar("medidor");
        v.sim(r != null && !r.ligado && r.descricao.contains("DESABILITADO: mod antigo")
                && r.descricao.contains("Hoshiestar-Medidor-1.0.4-serveronly.jar"),
                "jar antigo em mods/: recurso registrado desligado, descricao diz o que apagar: " + (r == null ? null : r.descricao));
        List<String> resp = Medidor.comandoMedir(30, false);
        v.sim(!Medidor.ATIVO && resp.get(0).contains("mod antigo Hoshiestar-Medidor")
                && resp.get(0).contains("Hoshiestar-Medidor-1.0.4-serveronly.jar") && resp.get(0).contains("APAGUE"),
                "jar antigo: hoshie medir recusa e diz qual jar apagar: " + resp);
        String linha = r == null ? "" : r.linhaDeEstado();
        v.sim(linha.startsWith("DESABILITADO: o mod antigo"), "jar antigo: status comeca pelo motivo: " + linha);
        Medidor.inicioTickServidor();
        Medidor.fimTickServidor();
        v.sim(!Medidor.ATIVO, "jar antigo: ganchos de tick nao fazem nada");
    }
}
