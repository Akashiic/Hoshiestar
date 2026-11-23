package hoshiestar.teste.vigia;

import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.teste.Verifica;
import hoshiestar.vigia.Marcas;
import hoshiestar.vigia.NucleoVigia;
import hoshiestar.vigia.Vigia;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * Chaves vigia.* no hoshiestar.yml, registro e "desligado = nada". Uso:
 * TesteVigiaConfig <pasta de trabalho> <classpath para a JVM filha>.
 */
public final class TesteVigiaConfig {

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteVigiaConfig");
        File base = new File(args[0]);
        base.mkdirs();

        // 1. arquivo ausente: criado com os padroes e comentarios
        File f = new File(base, "hoshiestar.yml");
        HoshiestarConfig c = HoshiestarConfig.carregar(f);
        String t = ler(f);
        v.sim(c.avisos().isEmpty(), "ausente: sem avisos " + c.avisos());
        v.igual(true, c.vigia_ligado, "padrao: vigia ligado (unico recurso ligado por padrao)");
        v.igual(2000, c.vigia_limiteMs, "padrao: limite 2000 ms");
        v.igual(5, c.vigia_amostras, "padrao: 5 amostras");
        v.igual(500, c.vigia_intervaloAmostrasMs, "padrao: 500 ms entre amostras");
        v.igual(60, c.vigia_intervaloMinimoSegundos, "padrao: 60 s entre relatorios");
        v.igual(10, c.vigia_maxRelatorios, "padrao: 10 relatorios por janela");
        v.igual(60, c.vigia_janelaMinutos, "padrao: janela de 60 min");
        v.igual(40, c.vigia_quadros, "padrao: 40 quadros");
        v.igual(true, c.vigia_nomeDoJogador, "padrao: nome do jogador sim");
        v.igual(200, c.vigia_maxArquivos, "padrao: 200 arquivos");
        v.igual(30, c.vigia_carenciaSegundos, "padrao: carencia de 30 s");
        for (String k : new String[] {"vigia:", "limite_ms: 2000", "amostras: 5", "intervalo_amostras_ms: 500",
            "intervalo_minimo_segundos: 60", "max_relatorios: 10", "janela_minutos: 60", "quadros: 40",
            "nome_do_jogador: true", "max_arquivos: 200", "carencia_segundos: 30", "# Vigia de tick lento"}) {
            v.sim(t.contains(k), "arquivo criado tem '" + k + "'");
        }

        // 2. valores do admin
        int iv = t.indexOf("\nvigia:");
        int il = iv < 0 ? -1 : t.indexOf("ligado: true", iv);
        v.sim(il > 0, "arquivo criado tem 'vigia:' com 'ligado: true'");
        String admin = (il > 0 ? t.substring(0, il) + "ligado: false" + t.substring(il + "ligado: true".length()) : t)
                .replace("limite_ms: 2000", "limite_ms: 3000").replace("nome_do_jogador: true", "nome_do_jogador: false");
        escrever(f, admin);
        c = HoshiestarConfig.carregar(f);
        v.sim(c.avisos().isEmpty(), "admin: sem avisos " + c.avisos());
        v.igual(false, c.vigia_ligado, "admin: vigia.ligado false lido");
        v.igual(3000, c.vigia_limiteMs, "admin: limite 3000 lido");
        v.igual(false, c.vigia_nomeDoJogador, "admin: nome_do_jogador false lido");
        v.igual(admin, ler(f), "admin: arquivo valido e completo nao foi regravado");

        // 3. registro com valores fora da faixa: usa o padrao
        Recursos.limparParaTeste();
        HoshiestarConfig d = HoshiestarConfig.carregar(new File(base, "outro.yml"));
        d.vigia_limiteMs = 10;
        d.vigia_amostras = 99;
        d.vigia_quadros = 1;
        d.vigia_carenciaSegundos = -5;
        Vigia.registrar(d, Correcoes.global());
        NucleoVigia.Ajustes a = Vigia.ajustesAtuais();
        v.igual(2000L, a.limiteMs, "fora da faixa: limite volta a 2000");
        v.igual(5, a.amostras, "fora da faixa: amostras volta a 5");
        v.igual(40, a.quadros, "fora da faixa: quadros volta a 40");
        v.igual(30000L, a.carenciaMs, "fora da faixa: carencia volta a 30 s");
        Recursos.Recurso r = Recursos.achar("vigia");
        v.sim(r != null && r.ligado, "registro: recurso vigia ligado");
        String linha = r == null ? null : r.linhaDeEstado();
        v.sim(linha != null && linha.contains("ainda nao iniciada"), "registro: linha de estado antes do servidor: " + linha);
        Vigia.parar();
        v.sim(Vigia.linhaDeEstado().equals("parado"), "parar sem thread: nada a esperar");

        // 4. JVM filha com vigia.ligado false: as marcacoes nao escrevem nada
        File dir = new File(base, "filho");
        dir.mkdirs();
        escrever(new File(dir, "hoshiestar.yml"), admin);
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", args[1], Filho.class.getName()).directory(dir).redirectErrorStream(true);
        Process p = pb.start();
        String saida = new String(Verifica.ler(p.getInputStream()), Charset.forName("UTF-8"));
        int cod = p.waitFor();
        System.out.println("  filho (vigia desligado): " + saida.trim());
        v.igual(0, cod, "desligado: JVM filha confirma que as marcacoes nao escreveram nada");
        v.fim();
    }

    /** JVM filha: le o hoshiestar.yml da pasta atual (vigia.ligado false) e chama as marcacoes. */
    public static final class Filho {
        public static void main(String[] args) {
            HoshiestarConfig.carregarPadrao();
            Vigia.FonteServidor fonte = new Vigia.FonteServidor();
            Marcas.inicioTick();
            Marcas.entidade(new Object());
            Marcas.tile(new Object());
            boolean nada = !Marcas.LIGADO && fonte.inicioNanos() == 0L && fonte.entidade() == null && fonte.tile() == null;
            Marcas.fimTick();
            nada &= fonte.seqFim() == 0L;
            System.out.println("LIGADO=" + Marcas.LIGADO + " inicio=" + fonte.inicioNanos() + " entidade=" + fonte.entidade() + " tile=" + fonte.tile());
            System.exit(nada ? 0 : 1);
        }
    }

    static String ler(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    static void escrever(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes(Charset.forName("UTF-8")));
        } finally {
            o.close();
        }
    }
}
