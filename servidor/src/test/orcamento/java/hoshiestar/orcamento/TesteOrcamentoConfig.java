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
package hoshiestar.orcamento;

import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.teste.Verifica;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Chaves do hoshiestar.yml (secao orcamento), faixas, registro e o interruptor {@code static final}. Rodar duas vezes, uma
 * com cada valor de {@code orcamento.ligado} (a classe {@link Orcamento} le o valor uma vez por JVM):
 * {@code TesteOrcamentoConfig <pasta de trabalho (cwd)> true|false}.
 */
public final class TesteOrcamentoConfig {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteOrcamentoConfig(" + args[1] + ")");
        File base = new File(args[0]);
        boolean ligado = Boolean.parseBoolean(args[1]);
        try {
            chaves(v, new File(base, "chaves.yml"));
            faixas(v);
            interruptor(v, base, ligado);
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    static void chaves(Verifica v, File f) throws Exception {
        f.delete();
        HoshiestarConfig c = HoshiestarConfig.carregar(f);
        String t = new String(Files.readAllBytes(f.toPath()), UTF8);
        v.sim(c.avisos().isEmpty(), "config ausente: sem avisos " + c.avisos());
        v.igual(false, c.orcamento_ligado, "padrao: orcamento.ligado = false");
        v.igual("medir", c.orcamento_modo, "padrao: modo = medir");
        v.sim(c.orcamento_alvoTickMs == 45.0 && c.orcamento_regiaoMs == 10.0 && c.orcamento_picoMs == 40.0
                && c.orcamento_picoFracaoPct == 4 && c.orcamento_nMaximo == 40 && c.orcamento_recalculoTicks == 100,
                "padroes numericos do plano (45, 10, 40, 4, 40, 100)");
        v.sim(c.orcamento_protegerSpawn && c.orcamento_cortarComJogador && c.orcamento_alertaOps && c.orcamento_alertaMs == 25.0
                && c.orcamento_alertaIntervaloMin == 5, "padroes: proteger_spawn, cortar_com_jogador, alerta_ops true; alerta 25 ms a cada 5 min");
        v.sim(c.orcamento_nuncaCortarDimensoes.isEmpty() && c.orcamento_nuncaCortarAreas.isEmpty()
                && c.orcamento_nuncaCortarMods.isEmpty() && c.orcamento_sempreJuntoDimensoes.isEmpty(), "padroes: listas vazias");
        int i = t.indexOf("\norcamento:");
        v.sim(i >= 0 && t.indexOf("\n  ligado: false\n", i) > i, "arquivo criado tem 'orcamento:' com 'ligado: false'");
        for (String k : new String[] {"modo:", "alvo_tick_ms:", "regiao_ms:", "pico_ms:", "pico_fracao_pct:", "n_maximo:",
            "recalculo_ticks:", "proteger_spawn:", "nunca_cortar_dimensoes:", "nunca_cortar_areas:", "nunca_cortar_mods:",
            "sempre_junto_dimensoes:", "cortar_com_jogador:", "alerta_ms:", "alerta_intervalo_min:", "alerta_ops:"}) {
            v.sim(t.indexOf("\n  " + k, i) > i, "arquivo criado tem a chave orcamento." + k.replace(":", ""));
        }
        v.sim(t.contains("# Orcamento de tick por regiao"), "arquivo criado tem o comentario do orcamento");
        // um arquivo valido e completo (inclusive as listas vazias) nao e regravado nem gera aviso
        String antes = t;
        HoshiestarConfig c2 = HoshiestarConfig.carregar(f);
        v.sim(c2.avisos().isEmpty(), "segunda leitura: sem avisos (listas vazias nao contam como faltando) " + c2.avisos());
        v.igual(antes, new String(Files.readAllBytes(f.toPath()), UTF8), "segunda leitura: arquivo nao foi regravado");
        // valores do admin
        int j = t.indexOf("\n  ligado: false\n", i);
        String admin = t.substring(0, j) + "\n  ligado: true\n" + t.substring(j + "\n  ligado: false\n".length());
        admin = admin.replace("modo: medir", "modo: cortar").replace("nunca_cortar_dimensoes: ''", "nunca_cortar_dimensoes: '0,-1'")
                .replace("n_maximo: 40", "n_maximo: 20");
        Files.write(f.toPath(), admin.getBytes(UTF8));
        HoshiestarConfig c3 = HoshiestarConfig.carregar(f);
        v.sim(c3.avisos().isEmpty() && c3.orcamento_ligado && c3.orcamento_modo.equals("cortar") && c3.orcamento_nMaximo == 20
                && c3.orcamento_nuncaCortarDimensoes.equals("0,-1"), "admin: valores lidos " + c3.avisos());
        v.igual(admin, new String(Files.readAllBytes(f.toPath()), UTF8), "admin: arquivo valido nao foi regravado");
        // valor invalido: aviso, padrao, arquivo intacto
        String ruim = admin.replace("n_maximo: 20", "n_maximo: muito");
        Files.write(f.toPath(), ruim.getBytes(UTF8));
        HoshiestarConfig c4 = HoshiestarConfig.carregar(f);
        v.sim(!c4.avisos().isEmpty() && c4.orcamento_nMaximo == 40, "valor invalido: aviso e padrao " + c4.avisos());
        v.igual(ruim, new String(Files.readAllBytes(f.toPath()), UTF8), "valor invalido: arquivo nao regravado");
        f.delete();
    }

    static void faixas(Verifica v) {
        List<String> av = new ArrayList<String>();
        Parametros p = Parametros.ler(5, 100, 1, 99, 1, 5, true, "", "", "", "", true, 0, 0, true, av);
        v.sim(av.size() == 8, "oito valores fora da faixa: oito avisos (" + av.size() + ")");
        v.sim(p.alvoTickMs == 45.0 && p.regiaoMs == 10.0 && p.picoMs == 40.0 && p.picoFracaoPct == 4 && p.nMaximo == 40
                && p.recalculoTicks == 100 && p.alertaMs == 25.0 && p.alertaIntervaloMin == 5, "e todos voltam ao padrao");
        av.clear();
        Parametros q = Parametros.ler(40, 5, 30, 10, 30, 200, false, "0, -1, abc", "0:1,2,3,4; lixo; 1:5,6,7", " IC2 , Mekanism ,",
                "7", false, 30, 10, false, av);
        v.sim(q.alvoTickMs == 40 && q.regiaoMs == 5 && q.nMaximo == 30 && !q.protegerSpawn && !q.cortarComJogador, "valores validos passam");
        v.sim(q.nuncaDimensoes.length == 2 && q.nuncaDimensoes[0] == 0 && q.nuncaDimensoes[1] == -1, "dimensoes: 0 e -1 (o 'abc' e ignorado)");
        v.sim(q.nuncaAreas.size() == 1 && q.nuncaAreas.get(0).x2 == 3, "areas: so a bem formada");
        v.sim(q.nuncaMods.length == 2 && q.nuncaMods[0].equals("ic2") && q.nuncaMods[1].equals("mekanism"), "mods: minusculos, sem vazios");
        v.sim(q.sempreJuntoDimensoes.length == 1 && q.sempreJuntoDimensoes[0] == 7, "sempre junto: 7");
        v.igual(3, av.size(), "tres avisos (dimensao ruim e duas areas ruins): " + av);
        v.igual(10, q.nDoPico(), "pico 10%: N >= 10");
        v.igual(25, Parametros.padrao().nDoPico(), "padrao: pico 4% = N >= 25");
        Parametros area = Parametros.ler(45, 10, 40, 4, 40, 100, true, "", "0:10,20,-5,-6", "", "", true, 25, 5, true, new ArrayList<String>());
        v.sim(area.nuncaAreas.get(0).x1 == -5 && area.nuncaAreas.get(0).z1 == -6 && area.nuncaAreas.get(0).x2 == 10, "area com cantos invertidos e normalizada");
    }

    static void interruptor(Verifica v, File base, boolean ligado) throws Exception {
        File f = new File(base, "hoshiestar.yml");
        f.delete();
        HoshiestarConfig c0 = HoshiestarConfig.carregar(f);
        String t = new String(Files.readAllBytes(f.toPath()), UTF8);
        int i = t.indexOf("\norcamento:");
        int j = t.indexOf("\n  ligado: false\n", i);
        String conteudo = t.substring(0, j) + "\n  ligado: " + ligado + "\n" + t.substring(j + "\n  ligado: false\n".length());
        Files.write(f.toPath(), conteudo.getBytes(UTF8));
        HoshiestarConfig c = HoshiestarConfig.carregarPadrao(); // le ./hoshiestar.yml (a pasta de trabalho e a base)
        v.igual(ligado, c.orcamento_ligado, "carregarPadrao leu orcamento.ligado = " + ligado);
        v.igual(ligado, Orcamento.LIGADO, "Orcamento.LIGADO (static final) vale " + ligado + " (lido do config uma vez)");
        Recursos.limparParaTeste();
        RegistroOrcamento.registrar(c, Correcoes.global());
        Recursos.Recurso r = Recursos.achar("orcamento");
        v.sim(r != null && r.ligado == ligado, "recurso 'orcamento' registrado com ligado = " + ligado);
        v.sim(r.linhaDeEstado().contains(ligado ? "aguardando o servidor" : "desligado"), "linha de estado: " + r.linhaDeEstado());
        v.sim(!ligado || r.linhaDeEstado().contains("aguardando o servidor"), "ligado sem servidor: a linha diz que aguarda o servidor");
        v.igual(null, Orcamento.nucleo(), "antes do servidor de pe nao ha nucleo");
        TesteOrcamento.JogoFalso jogo = new TesteOrcamento.JogoFalso();
        Orcamento.definirJogo(jogo);
        v.igual(ligado, Orcamento.nucleo() != null, "definirJogo cria o nucleo so com orcamento.ligado");
        v.sim(Orcamento.parametros().alvoTickMs == 45.0, "parametros do config chegaram");
        v.sim(!Orcamento.ferramentasDeTeste(), "ferramentas de teste desligadas sem a propriedade");
        System.setProperty("hoshiestar.orcamento.teste", "true");
        v.sim(Orcamento.ferramentasDeTeste(), "e ligadas com -Dhoshiestar.orcamento.teste=true");
        System.clearProperty("hoshiestar.orcamento.teste");
        // modo
        HoshiestarConfig cm = HoshiestarConfig.carregar(new File(base, "modo.yml"));
        cm.orcamento_modo = "talvez";
        RegistroOrcamento.class.getName();
        Recursos.limparParaTeste();
        RegistroOrcamento.registrar(cm, Correcoes.global());
        v.sim(Recursos.achar("orcamento") != null, "modo invalido nao impede o registro (usa medir e avisa)");
        new File(base, "modo.yml").delete();
        f.delete();
    }
}
