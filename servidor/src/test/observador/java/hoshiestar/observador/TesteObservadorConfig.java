package hoshiestar.observador;

import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.teste.Verifica;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Chaves do hoshiestar.yml, registro, comando e o ciclo de vida completo do observador (jogo falso e relogio falso):
 * iniciar, status, fim por tempo, parar no meio, parada do servidor, valvula, erro interno, relatorio gravado.
 */
public final class TesteObservadorConfig {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final class JogoFalso implements Jogo {
        final File pasta;
        boolean falhar;

        JogoFalso(File pasta) {
            this.pasta = pasta;
        }

        @Override
        public FotoMundo[] fotografar() {
            if (falhar) {
                throw new IllegalStateException("foto falhou de proposito");
            }
            return TesteObservadorNucleo.fotoPadrao();
        }

        @Override
        public File pastaDeSaida() {
            return pasta;
        }
    }

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteObservadorConfig");
        File base = new File(args[0]);
        base.mkdirs();
        try {
            config(v, base);
            ciclo(v, base);
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    static void config(Verifica v, File base) throws Exception {
        File f = new File(base, "hoshiestar.yml");
        f.delete();
        HoshiestarConfig c = HoshiestarConfig.carregar(f);
        String t = new String(Files.readAllBytes(f.toPath()), UTF8);
        v.sim(c.avisos().isEmpty(), "config ausente: sem avisos " + c.avisos());
        v.igual(false, c.observador_ligado, "padrao: observador.ligado = false");
        v.igual(256, c.observador_amostraPilha, "padrao: observador.amostra_pilha = 256");
        v.igual(5.0, c.observador_limiteCustoPct, "padrao: observador.limite_custo_pct = 5.0");
        int i = t.indexOf("\nobservador:");
        v.sim(i >= 0 && t.indexOf("\n  ligado: false\n", i) > i, "arquivo criado tem 'observador:' com 'ligado: false'");
        v.sim(t.indexOf("amostra_pilha: 256", i) > i && t.indexOf("limite_custo_pct: 5.0", i) > i,
                "arquivo criado tem amostra_pilha e limite_custo_pct na secao observador");
        v.sim(t.contains("# Observador (Etapa 2)"), "arquivo criado tem o comentario do observador");

        int j = t.indexOf("\n  ligado: false\n", i);
        String admin = t.substring(0, j) + "\n  ligado: true\n" + t.substring(j + "\n  ligado: false\n".length());
        Files.write(f.toPath(), admin.getBytes(UTF8));
        c = HoshiestarConfig.carregar(f);
        v.sim(c.avisos().isEmpty() && c.observador_ligado, "admin: observador.ligado true lido " + c.avisos());
        v.igual(admin, new String(Files.readAllBytes(f.toPath()), UTF8), "admin: arquivo valido e completo nao foi regravado");
        HoshiestarConfig c2 = carregarCom(f, admin.replace("amostra_pilha: 256", "amostra_pilha: 64").replace("limite_custo_pct: 5.0", "limite_custo_pct: 2.5"));
        v.sim(c2.observador_amostraPilha == 64 && c2.observador_limiteCustoPct == 2.5, "admin: 64 e 2.5 lidos");

        Observador.reiniciarParaTeste();
        Recursos.limparParaTeste();
        RegistroObservador.registrar(c2, Correcoes.global());
        Recursos.Recurso r = Recursos.achar("observador");
        v.sim(r != null && r.ligado, "registro com true: recurso 'observador' ligado no status");
        v.sim(Observador.habilitado(), "registro: habilitado");
        v.sim(r.linhaDeEstado().contains("parado") && r.linhaDeEstado().contains("1 em 64"), "registro: linha de estado: " + r.linhaDeEstado());
        // valores fora da faixa viram o padrao
        Observador.configurar(true, 0, 99.0);
        v.sim(Observador.linhaDeEstado().contains("1 em 256") && Observador.linhaDeEstado().contains("limite de custo 5.0%"),
                "registro: amostra 0 e limite 99 voltam ao padrao: " + Observador.linhaDeEstado());
        // desligado
        Observador.reiniciarParaTeste();
        Recursos.limparParaTeste();
        RegistroObservador.registrar(HoshiestarConfig.carregar(new File(base, "naoexiste.yml")), Correcoes.global());
        r = Recursos.achar("observador");
        v.sim(r != null && !r.ligado, "registro com false: recurso desligado");
        List<String> resp = Observador.comando(new String[] {"30"});
        v.sim(!Observador.ATIVO && resp.size() == 1 && resp.get(0).contains("observador.ligado: false"), "desligado: o comando recusa: " + resp);
        Observador.inicioTickServidor();
        Observador.fimTickServidor();
        v.sim(!Observador.ATIVO && Observador.N == null, "desligado: os ganchos de tick nao fazem nada");
        new File(base, "naoexiste.yml").delete();
    }

    static HoshiestarConfig carregarCom(File f, String conteudo) throws Exception {
        Files.write(f.toPath(), conteudo.getBytes(UTF8));
        return HoshiestarConfig.carregar(f);
    }

    static void ciclo(Verifica v, File base) throws Exception {
        File saida = new File(base, "saida");
        JogoFalso jogo = new JogoFalso(new File(saida, "hoshiestar"));
        TesteObservadorNucleo.RelogioFalso rf = new TesteObservadorNucleo.RelogioFalso();
        Observador.reiniciarParaTeste();
        Observador.configurar(true, 1, 5.0);
        Observador.definirJogo(jogo);
        Observador.definirDonos(TesteObservadorNucleo.DONOS);
        Observador.definirRelogio(rf);

        // argumentos
        v.sim(Observador.comando(new String[0]).get(0).startsWith("Uso:"), "comando sem argumento: mostra o uso");
        v.sim(Observador.comando(new String[] {"abc"}).get(0).contains("invalido"), "comando 'abc': argumento invalido");
        v.sim(Observador.comando(new String[] {"3"}).get(0).contains("fora da faixa"), "comando '3': fora da faixa");
        v.sim(Observador.comando(new String[] {"99999"}).get(0).contains("fora da faixa"), "comando '99999': fora da faixa");
        v.sim(Observador.comando(new String[] {"10", "20"}).get(0).contains("um argumento so"), "comando com 2 argumentos: recusa");
        v.sim(Observador.comando(new String[] {"parar"}).get(0).contains("Nao ha observacao"), "parar sem observacao: avisa");
        v.sim(Observador.comando(new String[] {"status"}).get(0).equals("Parado."), "status parado");
        v.sim(!Observador.ATIVO, "antes do comando: inativo");

        // 1) fim por tempo
        v.sim(Observador.comando(new String[] {"60"}).get(0).contains("pedida"), "comando 60: pedida");
        v.sim(Observador.comando(new String[] {"status"}).get(0).contains("pedida"), "status: pedida, comeca no proximo tick");
        v.sim(Observador.comando(new String[] {"30"}).get(0).contains("Ja ha"), "segundo pedido enquanto o primeiro espera: recusa");
        Observador.inicioTickServidor();
        v.sim(Observador.ATIVO && Observador.N != null, "o inicio do tick comeca a observar");
        Nucleo n = Observador.N;
        n.entrar(Nucleo.C_TILE, TesteObservadorNucleo.M0, 0, 5, 5, TesteObservadorNucleo.ModAMaquina.class);
        for (int i = 0; i < 50; i++) {
            n.acesso(Nucleo.A_ESCREVER_BLOCO, TesteObservadorNucleo.M0, 0, 20, 5);
        }
        n.acesso(Nucleo.A_ESCREVER_BLOCO, TesteObservadorNucleo.M0, 0, 200, 5);
        Observador.fimTickServidor();
        v.sim(Observador.ATIVO && n.fotosTiradas == 1, "fim do tick: a primeira foto das regioes saiu");
        String st = Observador.comando(new String[] {"status"}).get(0);
        v.sim(st.startsWith("Observando:") && st.contains("51 acessos registrados"), "status: " + st);
        v.sim(Observador.comando(new String[] {"30"}).get(0).contains("Ja ha"), "pedido durante a observacao: recusa");
        rf.t += 61_000_000_000L;
        Observador.inicioTickServidor();
        Observador.fimTickServidor();
        v.sim(!Observador.ATIVO && Observador.N == null, "passou o tempo: parou");
        Observador.aguardarGravacao(10000);
        String res = Observador.ultimoResultado();
        v.sim(res != null && res.startsWith("gravado ") && res.contains("o tempo pedido acabou"), "resultado: " + res);
        File[] arq = saida.listFiles() == null ? null : new File(saida, "hoshiestar").listFiles();
        v.sim(arq != null && arq.length == 2, "gravou 2 arquivos na pasta hoshiestar/");
        File json = null;
        for (File a : arq) {
            if (a.getName().endsWith(".json")) {
                json = a;
            }
        }
        Object raiz = JsonLeitor.ler(new String(Files.readAllBytes(json.toPath()), UTF8));
        v.igual(Boolean.FALSE, JsonLeitor.caminho(raiz, "observacao", "parou_no_meio"), "relatorio por tempo: nao parou no meio");
        v.igual("o tempo pedido acabou", JsonLeitor.caminho(raiz, "observacao", "motivo_do_fim"), "relatorio: motivo do fim");
        v.igual(51L, ((Number) JsonLeitor.caminho(raiz, "contagens", "acessos_registrados")).longValue(), "relatorio: 51 acessos");
        v.igual(Boolean.FALSE, JsonLeitor.caminho(raiz, "observacao", "parou_no_meio"), "relatorio: nao parou no meio");
        v.sim(Observador.comando(new String[] {"status"}).get(1).startsWith("Ultimo resultado: gravado"), "status mostra o ultimo resultado");
        limpar(saida);

        // 2) parar no meio, pelo comando
        Observador.comando(new String[] {"120"});
        Observador.inicioTickServidor();
        Observador.fimTickServidor();
        v.sim(Observador.ATIVO, "segunda observacao: comecou");
        Observador.comando(new String[] {"parar"});
        Observador.inicioTickServidor();
        v.sim(!Observador.ATIVO, "parar: o proximo tick encerra");
        Observador.aguardarGravacao(10000);
        raiz = JsonLeitor.ler(new String(Files.readAllBytes(primeiro(saida, ".json").toPath()), UTF8));
        v.igual(Boolean.TRUE, JsonLeitor.caminho(raiz, "observacao", "parou_no_meio"), "parar: relatorio marca parou_no_meio");
        v.igual("parado pelo comando", JsonLeitor.caminho(raiz, "observacao", "motivo_do_fim"), "parar: motivo");
        limpar(saida);

        // 3) parada do servidor no meio
        Observador.comando(new String[] {"120"});
        Observador.inicioTickServidor();
        Observador.N.entrar(Nucleo.C_TILE, TesteObservadorNucleo.M0, 0, 5, 5, TesteObservadorNucleo.ModAMaquina.class);
        Observador.N.acesso(Nucleo.A_ESCREVER_BLOCO, TesteObservadorNucleo.M0, 0, 20, 5);
        Observador.fimTickServidor();
        Observador.encerrarNoDesligamento();
        v.sim(!Observador.ATIVO, "parada do servidor: encerra");
        raiz = JsonLeitor.ler(new String(Files.readAllBytes(primeiro(saida, ".json").toPath()), UTF8));
        v.igual(Boolean.TRUE, JsonLeitor.caminho(raiz, "observacao", "parou_no_meio"), "parada do servidor: gravou, parou_no_meio");
        v.igual("o servidor foi parado", JsonLeitor.caminho(raiz, "observacao", "motivo_do_fim"), "parada do servidor: motivo");
        v.igual(1L, ((Number) JsonLeitor.caminho(raiz, "contagens", "acessos_registrados")).longValue(), "parada do servidor: o que ja tinha foi gravado");
        Observador.encerrarNoDesligamento(); // sem observacao: nao faz nada nem trava
        limpar(saida);

        // 4) valvula pelo fachada
        Observador.comando(new String[] {"3000"});
        Observador.configurar(true, 1000000, 5.0);
        Observador.inicioTickServidor();
        v.sim(Observador.ATIVO, "valvula: observacao comecou");
        int tk = 0;
        while (Observador.ATIVO && tk < 1000) {
            Observador.inicioTickServidor();
            Observador.N.entrar(Nucleo.C_TILE, TesteObservadorNucleo.M0, 0, 0, 0, TesteObservadorNucleo.ModAMaquina.class);
            long ini = rf.t;
            for (int i = 0; i < 20000; i++) {
                Observador.N.acesso(Nucleo.A_ESCREVER_BLOCO, TesteObservadorNucleo.M0, 0, 16 + (i & 7), 0);
            }
            rf.t = ini + 10_000_000L; // o tick "durou" 10 ms
            Observador.fimTickServidor();
            tk++;
        }
        v.sim(!Observador.ATIVO, "valvula: parou sozinha (apos " + tk + " ticks)");
        Observador.aguardarGravacao(10000);
        raiz = JsonLeitor.ler(new String(Files.readAllBytes(primeiro(saida, ".json").toPath()), UTF8));
        Object av = JsonLeitor.caminho(raiz, "avisos", "0");
        v.sim(av != null && String.valueOf(av).contains("valvula"), "valvula: o aviso esta no relatorio: " + av);
        limpar(saida);

        // 5) erro interno (foto falha): para e grava
        jogo.falhar = true;
        Observador.configurar(true, 1, 5.0);
        Observador.comando(new String[] {"60"});
        Observador.inicioTickServidor();
        Observador.fimTickServidor();
        v.sim(!Observador.ATIVO, "erro na foto: a observacao para (nunca derruba o tick)");
        Observador.aguardarGravacao(10000);
        raiz = JsonLeitor.ler(new String(Files.readAllBytes(primeiro(saida, ".json").toPath()), UTF8));
        v.sim(String.valueOf(JsonLeitor.caminho(raiz, "observacao", "motivo_do_fim")).contains("erro interno"),
                "erro na foto: o motivo diz 'erro interno'");
        jogo.falhar = false;
        limpar(saida);

        // 6) pasta de saida que nao da para criar: nao derruba e o status diz
        Observador.reiniciarParaTeste();
        Observador.configurar(true, 1, 5.0);
        File arquivoNoLugar = new File(base, "arquivo-no-lugar");
        Files.write(arquivoNoLugar.toPath(), new byte[] {1});
        Observador.definirJogo(new JogoFalso(new File(arquivoNoLugar, "hoshiestar")));
        Observador.definirDonos(TesteObservadorNucleo.DONOS);
        Observador.definirRelogio(rf);
        Observador.comando(new String[] {"60"});
        Observador.inicioTickServidor();
        Observador.fimTickServidor();
        Observador.comando(new String[] {"parar"});
        Observador.inicioTickServidor();
        Observador.aguardarGravacao(10000);
        v.sim(Observador.ultimoResultado() != null && Observador.ultimoResultado().contains("NAO foi gravado"),
                "pasta impossivel: o resultado diz que nao gravou: " + Observador.ultimoResultado());

        // 7) caminho assincrono do indice de jars (sem donos de teste): pede, espera, comeca
        Observador.reiniciarParaTeste();
        Observador.configurar(true, 256, 5.0);
        Observador.definirJogo(new JogoFalso(new File(saida, "hoshiestar")));
        List<String> r1 = Observador.comando(new String[] {"30"});
        v.sim(r1.get(0).contains("Montando o indice"), "indice: pedido monta o indice em outra thread: " + r1);
        long fim = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < fim && !Observador.status().get(0).startsWith("Observacao pedida")
                && !Observador.status().get(0).startsWith("Parado")) {
            Thread.sleep(20);
        }
        v.sim(Observador.status().get(0).startsWith("Observacao pedida"), "indice: pronto, observacao pedida: " + Observador.status());
        Observador.inicioTickServidor();
        v.sim(Observador.ATIVO, "indice: a observacao comecou no proximo tick");
        Observador.encerrarNoDesligamento();
        v.sim(!Observador.ATIVO, "indice: parada do servidor encerra");
        limpar(saida);
        Observador.reiniciarParaTeste();
    }

    static File primeiro(File saida, String ext) {
        File[] l = new File(saida, "hoshiestar").listFiles();
        if (l != null) {
            for (File f : l) {
                if (f.getName().endsWith(ext)) {
                    return f;
                }
            }
        }
        throw new IllegalStateException("sem arquivo " + ext + " em " + saida);
    }

    static void limpar(File d) {
        File[] l = d.listFiles();
        if (l != null) {
            for (File f : l) {
                limpar(f);
            }
        }
        d.delete();
    }
}
