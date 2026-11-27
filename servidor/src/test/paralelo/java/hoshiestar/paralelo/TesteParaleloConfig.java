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
package hoshiestar.paralelo;

import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.teste.Verifica;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Chaves do hoshiestar.yml (secao paralelo): leitura (valores validos, lixo, ausente), registro, o interruptor
 * {@code static final}, o criterio de nome da thread de regiao e o texto do comando. Rodar duas vezes, uma com cada
 * valor de {@code paralelo.ligado} (a classe {@link Paralelo} le o valor uma vez por JVM):
 * {@code TesteParaleloConfig <pasta de trabalho (cwd)> true|false}.
 */
public final class TesteParaleloConfig {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String[] CHAVES = {"ligado:", "modo:", "dimensao:", "threads:", "classes:", "ao_falhar:", "ao_violar:",
        "leitura_amostra:", "aviso_espera_s:", "sinal_ticks:", "aquecimento_ticks:"};

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteParaleloConfig(" + args[1] + ")");
        File base = new File(args[0]);
        boolean ligado = Boolean.parseBoolean(args[1]);
        try {
            chaves(v, new File(base, "chaves.yml"));
            problemas(v, new File(base, "problemas.yml"));
            interruptor(v, base, ligado);
            comando(v, ligado);
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    private static String ler(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), UTF8);
    }

    private static void escrever(File f, String s) throws Exception {
        Files.write(f.toPath(), s.getBytes(UTF8));
    }

    /** Troca a primeira ocorrencia de {@code de} depois da secao {@code paralelo:}. */
    private static String trocar(String t, String de, String para) {
        if (!de.startsWith("\n")) { // so a linha da chave (o texto dos comentarios tambem cita "ligado: false" etc.)
            de = "\n  " + de;
            para = "\n  " + para;
        }
        int i = t.indexOf("\nparalelo:");
        int j = t.indexOf(de, i);
        if (i < 0 || j < 0) {
            throw new IllegalStateException("nao achei '" + de + "' na secao paralelo");
        }
        return t.substring(0, j) + para + t.substring(j + de.length());
    }

    static void chaves(Verifica v, File f) throws Exception {
        f.delete();
        HoshiestarConfig c = HoshiestarConfig.carregar(f);
        String t = ler(f);
        v.sim(c.avisos().isEmpty(), "config ausente: sem avisos " + c.avisos());
        v.igual(false, c.paralelo_ligado, "padrao: paralelo.ligado = false");
        Parametros p = Parametros.ler(c.paralelo_modo, c.paralelo_dimensao, c.paralelo_threads, c.paralelo_classes,
                c.paralelo_aoFalhar, c.paralelo_aoViolar, c.paralelo_leituraAmostra, c.paralelo_avisoEsperaS,
                c.paralelo_sinalTicks, c.paralelo_aquecimentoTicks);
        Parametros d = Parametros.padrao();
        v.sim(p.problemas.isEmpty() && p.modo.equals(d.modo) && p.dimensao == d.dimensao && p.threads == d.threads
                && p.classes.equals(d.classes) && p.aoFalhar.equals(d.aoFalhar) && p.aoViolar.equals(d.aoViolar)
                && p.leituraAmostra == d.leituraAmostra && p.avisoEsperaS == d.avisoEsperaS && p.sinalTicks == d.sinalTicks
                && p.aquecimentoTicks == d.aquecimentoTicks, "os padroes do config sao os da secao 10 do plano (os de Parametros.padrao)");
        int i = t.indexOf("\nparalelo:");
        v.sim(i >= 0 && t.indexOf("\n  ligado: false\n", i) > i, "arquivo criado tem 'paralelo:' com 'ligado: false'");
        for (String k : CHAVES) {
            v.sim(t.indexOf("\n  " + k, i) > i, "arquivo criado tem a chave paralelo." + k.replace(":", ""));
        }
        v.sim(t.contains("# Fase paralela sincronizada com barreira"), "arquivo criado tem o comentario do paralelo");
        // o bloco e o ultimo do arquivo (depois dele nenhuma outra secao de primeiro nivel)
        boolean ultima = true;
        for (String linha : t.substring(i + 1).split("\n")) {
            if (!linha.isEmpty() && !linha.startsWith(" ") && !linha.startsWith("#") && !linha.equals("paralelo:")) {
                ultima = false;
            }
        }
        v.sim(ultima, "a secao paralelo e a ultima do arquivo (bloco continuo no fim)");
        // um arquivo valido e completo nao e regravado nem gera aviso
        HoshiestarConfig c2 = HoshiestarConfig.carregar(f);
        v.sim(c2.avisos().isEmpty(), "segunda leitura: sem avisos " + c2.avisos());
        v.igual(t, ler(f), "segunda leitura: arquivo nao foi regravado");
        // valores validos do admin
        String admin = trocar(t, "\n  ligado: false\n", "\n  ligado: true\n");
        admin = trocar(admin, "modo: sombra", "modo: paralelo");
        admin = trocar(admin, "dimensao: 0", "dimensao: -1");
        admin = trocar(admin, "threads: 3", "threads: 5");
        admin = trocar(admin, "classes: ''", "classes: 'mesa,bau'");
        admin = trocar(admin, "ao_falhar: parar", "ao_falhar: rebaixar");
        admin = trocar(admin, "leitura_amostra: 64", "leitura_amostra: 0");
        admin = trocar(admin, "aviso_espera_s: 5", "aviso_espera_s: 10");
        escrever(f, admin);
        HoshiestarConfig c3 = HoshiestarConfig.carregar(f);
        v.sim(c3.avisos().isEmpty() && c3.paralelo_ligado && c3.paralelo_modo.equals("paralelo") && c3.paralelo_dimensao == -1
                && c3.paralelo_threads == 5 && c3.paralelo_classes.equals("mesa,bau") && c3.paralelo_aoFalhar.equals("rebaixar")
                && c3.paralelo_leituraAmostra == 0 && c3.paralelo_avisoEsperaS == 10, "admin: valores validos lidos " + c3.avisos());
        v.igual(admin, ler(f), "admin: arquivo valido nao foi regravado");
        f.delete();
    }

    /** Lixo: valor com tipo errado, chave desconhecida, secao no formato errado, arquivo que nao e YAML. */
    static void problemas(Verifica v, File f) throws Exception {
        f.delete();
        HoshiestarConfig.carregar(f);
        String t = ler(f);
        // tipo errado: aviso, padrao, arquivo intacto
        for (String[] par : new String[][] {{"threads: 3", "threads: muitas"}, {"ligado: false", "ligado: talvez"},
            {"dimensao: 0", "dimensao: 0,1"}, {"sinal_ticks: 1200", "sinal_ticks: 1.5x"}}) {
            String ruim = trocar(t, par[0], par[1]);
            escrever(f, ruim);
            HoshiestarConfig c = HoshiestarConfig.carregar(f);
            v.sim(!c.avisos().isEmpty(), "lixo '" + par[1] + "': ha aviso " + c.avisos());
            v.sim(!c.paralelo_ligado && c.paralelo_threads == 3 && c.paralelo_dimensao == 0 && c.paralelo_sinalTicks == 1200,
                    "lixo '" + par[1] + "': o valor usa o padrao (e o interruptor continua desligado)");
            v.igual(ruim, ler(f), "lixo '" + par[1] + "': arquivo nao regravado");
        }
        // texto invalido em chave de texto passa pelo leitor e e recusado depois (aqui so o config)
        escrever(f, trocar(t, "modo: sombra", "modo: lixo"));
        HoshiestarConfig cm = HoshiestarConfig.carregar(f);
        v.sim(cm.avisos().isEmpty() && cm.paralelo_modo.equals("lixo"), "texto de modo invalido chega como texto (a recusa e do Parametros)");
        Parametros pm = Parametros.ler(cm.paralelo_modo, 0, 3, "", "parar", "parar", 64, 5, 1200, 600);
        v.sim(!pm.problemas.isEmpty(), "e Parametros acusa o modo invalido");
        // chave desconhecida
        escrever(f, trocar(t, "threads: 3", "threadz: 3\n  threads: 3"));
        HoshiestarConfig cd = HoshiestarConfig.carregar(f);
        boolean achou = false;
        for (String a : cd.avisos()) {
            achou |= a.contains("paralelo.threadz");
        }
        v.sim(achou && cd.paralelo_threads == 3, "chave desconhecida paralelo.threadz: aviso " + cd.avisos());
        // secao no formato errado
        escrever(f, "paralelo: true\n");
        HoshiestarConfig cs = HoshiestarConfig.carregar(f);
        v.sim(!cs.avisos().isEmpty() && !cs.paralelo_ligado, "'paralelo: true' (secao errada): aviso, desligado " + cs.avisos());
        v.igual("paralelo: true\n", ler(f), "secao errada: arquivo nao regravado");
        // arquivo ilegivel
        escrever(f, "{{{ nao e yaml: [\n\t- :");
        HoshiestarConfig ci = HoshiestarConfig.carregar(f);
        v.sim(!ci.avisos().isEmpty() && !ci.paralelo_ligado && ci.paralelo_threads == 3 && ci.paralelo_modo.equals("sombra"),
                "arquivo que nao e YAML: aviso e todos os padroes " + ci.avisos());
        v.igual("{{{ nao e yaml: [\n\t- :", ler(f), "arquivo ilegivel: nao e tocado");
        // item presente sem valor: padrao, e o arquivo e regravado com ele
        escrever(f, trocar(t, "threads: 3", "threads:"));
        HoshiestarConfig cv = HoshiestarConfig.carregar(f);
        v.sim(cv.avisos().isEmpty() && cv.paralelo_threads == 3, "'threads:' sem valor: padrao, sem aviso " + cv.avisos());
        // secao inteira ausente (arquivo de versao antiga): padroes, e a secao entra no arquivo
        escrever(f, "autoteste:\n  ligado: false\n");
        HoshiestarConfig ca = HoshiestarConfig.carregar(f);
        v.sim(ca.avisos().isEmpty() && !ca.paralelo_ligado, "arquivo sem a secao paralelo: padroes, sem aviso " + ca.avisos());
        v.sim(ler(f).contains("\nparalelo:") && ler(f).contains("autoteste:"), "e a secao entra no arquivo");
        f.delete();
    }

    static void interruptor(Verifica v, File base, boolean ligado) throws Exception {
        File f = new File(base, "hoshiestar.yml");
        f.delete();
        HoshiestarConfig.carregar(f);
        String t = ler(f);
        escrever(f, trocar(t, "\n  ligado: false\n", "\n  ligado: " + ligado + "\n"));
        HoshiestarConfig c = HoshiestarConfig.carregarPadrao(); // le ./hoshiestar.yml (a pasta de trabalho e a base)
        v.igual(ligado, c.paralelo_ligado, "carregarPadrao leu paralelo.ligado = " + ligado);
        v.igual(ligado, Paralelo.LIGADO, "Paralelo.LIGADO (static final) vale " + ligado + " (lido do config uma vez)");
        // ANTES do registro (o caso de um recurso anterior do catalogo lancar excecao: RegistroParalelo.registrar nunca roda):
        // o estado inicial recusa, mesmo com o config ligado (achado importante 1 da revisao do passo 1)
        v.sim(!Paralelo.aceito(), "sem o registro: aceito() falso (config ligado = " + ligado + ")");
        v.sim(Paralelo.motivos().size() == 1 && Paralelo.motivos().get(0).equals(Paralelo.NAO_AVALIADO),
                "sem o registro: o unico motivo e 'recusas do boot nao avaliadas' " + Paralelo.motivos());
        v.sim(ligado ? Paralelo.linhaDeEstado().contains("RECUSADO") : Paralelo.linhaDeEstado().equals("desligado"),
                "sem o registro: linha de estado " + Paralelo.linhaDeEstado());
        if (ligado) {
            String sem = String.join("\n", ComandoParalelo.executar(new String[] {"motivos"}));
            v.sim(sem.contains("nao avaliadas"), "sem o registro: 'hoshie paralelo motivos' mostra o motivo: " + sem.replace('\n', '|'));
        }
        Recursos.limparParaTeste();
        RegistroParalelo.registrar(c, Correcoes.global());
        Recursos.Recurso r = Recursos.achar("paralelo");
        v.sim(r != null && r.ligado == ligado, "recurso 'paralelo' registrado com ligado = " + ligado);
        if (ligado) {
            // orcamento.ligado e false neste arquivo: recusa; e o registro (fase de coremod) nunca tem os fatos do jogo: recusa
            v.sim(Paralelo.motivos().size() == 2 && Paralelo.motivos().get(0).startsWith("orcamento desligado")
                    && Paralelo.motivos().get(1).equals(Recusas.FATOS_NAO_CONFERIDOS),
                    "ligado com orcamento desligado: recusa (mais a dos fatos do jogo) " + Paralelo.motivos());
            v.sim(r.linhaDeEstado().contains("RECUSADO") && r.linhaDeEstado().contains("fase ainda nao implementada"),
                    "linha de estado: " + r.linhaDeEstado());
            v.sim(!Paralelo.aceito(), "recusado: aceito() falso");
            c.orcamento_ligado = true;
            Recursos.limparParaTeste();
            RegistroParalelo.registrar(c, Correcoes.global());
            // config todo certo: o registro sozinho NAO deixa o recurso aceito, porque ninguem coletou os fatos do jogo
            // (achado importante 3 da revisao do passo 1: as recusas de dimensao, world.rand, hodgepodge, observador,
            // firewall e veredito nao rodam no boot; sem a coleta, fail-closed)
            v.sim(Paralelo.motivos().size() == 1 && Paralelo.motivos().get(0).equals(Recusas.FATOS_NAO_CONFERIDOS) && !Paralelo.aceito(),
                    "config todo certo, fatos do jogo nao coletados: recusado, aceito() falso " + Paralelo.motivos());
            v.sim(Recursos.achar("paralelo").linhaDeEstado().contains("RECUSADO"), "linha de estado: " + Recursos.achar("paralelo").linhaDeEstado());
            // so com uma avaliacao sem motivo nenhum (a que o boot do servidor fara com os fatos coletados) fica aceito
            Paralelo.configurar(Paralelo.parametros(), new java.util.ArrayList<String>());
            v.sim(Paralelo.motivos().isEmpty() && Paralelo.aceito(), "avaliacao sem motivo: aceito() verdadeiro");
            v.sim(Recursos.achar("paralelo").linhaDeEstado().contains("modo sombra, sem recusa"), "linha de estado: " + Recursos.achar("paralelo").linhaDeEstado());
            String nenhum = String.join("\n", ComandoParalelo.executar(new String[] {"motivos"}));
            v.sim(nenhum.contains("nenhuma recusa no boot"), "motivos sem recusa: " + nenhum.replace('\n', '|'));
            c.paralelo_modo = "paralelo";
            Recursos.limparParaTeste();
            RegistroParalelo.registrar(c, Correcoes.global());
            v.sim(Paralelo.motivos().size() == 3 && !Paralelo.aceito(), "modo paralelo sem parada e sem H01: duas recusas, mais a dos fatos do jogo " + Paralelo.motivos());
            c.paralelo_modo = "sombra";
            Recursos.limparParaTeste();
            RegistroParalelo.registrar(c, Correcoes.global());
        } else {
            v.igual("desligado", r.linhaDeEstado(), "linha de estado: desligado");
            v.sim(Paralelo.motivos().isEmpty() && !Paralelo.aceito(), "desligado: sem motivos e nunca aceito");
        }
        // o criterio de nome (plano, secao 2: "Hoshiestar Regiao #i")
        v.sim(Paralelo.ehNomeDeRegiao("Hoshiestar Regiao #0") && Paralelo.ehNomeDeRegiao("Hoshiestar Regiao #12"), "criterio de nome: #0 e #12");
        v.sim(!Paralelo.ehNomeDeRegiao("Server thread") && !Paralelo.ehNomeDeRegiao("Hoshiestar Regiao")
                && !Paralelo.ehNomeDeRegiao("hoshiestar regiao #1") && !Paralelo.ehNomeDeRegiao("X Hoshiestar Regiao #1")
                && !Paralelo.ehNomeDeRegiao("Hoshiestar Vigia") && !Paralelo.ehNomeDeRegiao(null), "criterio de nome: outros nomes nao");
        String[] nomes = {"Hoshiestar Regiao #1", "Server thread", "Client thread", "Hoshiestar Regiao", "main", "Netty Server IO #0"};
        for (String n : nomes) {
            boolean esperado = ligado && n.startsWith("Hoshiestar Regiao #");
            v.igual(esperado, Paralelo.ehThreadDeRegiao(new Thread(n)), "ehThreadDeRegiao(thread '" + n + "') com ligado = " + ligado);
        }
        f.delete();
    }

    static void comando(Verifica v, boolean ligado) {
        String e = String.join("\n", ComandoParalelo.executar(new String[0]));
        v.sim(ligado ? e.contains(ComandoParalelo.NAO_IMPLEMENTADA) : e.contains("desligado"), "sem argumento = estado: ligado diz '" + ComandoParalelo.NAO_IMPLEMENTADA + "', desligado diz 'desligado'");
        v.igual(e, String.join("\n", ComandoParalelo.executar(new String[] {"ESTADO"})), "estado (maiuscula tambem)");
        v.sim(e.contains(ligado ? "modo configurado: sombra" : "paralelo.ligado: false"), "estado traz o modo (ligado) ou a dica (desligado)");
        String m = String.join("\n", ComandoParalelo.executar(new String[] {"motivos"}));
        v.sim(ligado ? m.contains("1 motivo(s) de recusa no boot") && m.contains("fatos do jogo ainda nao conferidos") : m.contains("desligado"), "motivos: " + m.replace('\n', '|'));
        v.sim(String.join("\n", ComandoParalelo.executar(new String[] {"motivos", "0"})).contains("uso:"), "motivos 0: uso");
        v.sim(String.join("\n", ComandoParalelo.executar(new String[] {"motivos", "x"})).contains("uso:"), "motivos x: uso");
        v.sim(String.join("\n", ComandoParalelo.executar(new String[] {"motivos", "1"})).length() > 0, "motivos 1: responde");
        String md = String.join("\n", ComandoParalelo.executar(new String[] {"modo"}));
        v.sim(md.contains("modo configurado:"), "modo sem argumento mostra o modo");
        String mp = String.join("\n", ComandoParalelo.executar(new String[] {"modo", "paralelo"}));
        v.sim(mp.contains(ComandoParalelo.NAO_IMPLEMENTADA) && mp.contains("nada foi mudado"), "modo paralelo: nao implementado, nada mudou");
        v.sim(String.join("\n", ComandoParalelo.executar(new String[] {"modo", "xyz"})).contains("invalido"), "modo xyz: invalido");
        v.sim(String.join("\n", ComandoParalelo.executar(new String[] {"congelar"})).startsWith("uso:"), "subcomando desconhecido (e os de teste ainda nao existem): uso");
        v.sim(ComandoParalelo.ajuda().contains(ComandoParalelo.NAO_IMPLEMENTADA), "ajuda diz que a fase nao existe");
    }
}
