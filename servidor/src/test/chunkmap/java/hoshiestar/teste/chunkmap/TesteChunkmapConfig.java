package hoshiestar.teste.chunkmap;

import hoshiestar.api.Recursos;
import hoshiestar.chunkmap.ChunkMap;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.teste.Verifica;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/**
 * As chaves chunkmap.* no hoshiestar.yml e o registro do recurso. Uso: {@code TesteChunkmapConfig <pasta vazia>}.
 * Roda numa JVM propria e NAO carrega ChunkBlockHashMap.
 */
public final class TesteChunkmapConfig {

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();
        Verifica v = new Verifica("TesteChunkmapConfig");

        File f = new File(dir, "novo.yml");
        HoshiestarConfig c = HoshiestarConfig.carregar(f);
        String t = ler(f);
        v.sim(t.contains("chunkmap:") && t.contains("ligado: false") && t.contains("bits: 6") && t.contains("conferir: false"),
                "arquivo novo: secao chunkmap com ligado/bits/conferir nos padroes");
        v.igual(false, c.chunkmap_ligado, "padrao: desligado");
        v.igual(6, c.chunkmap_bits, "padrao: bits 6");
        v.igual(false, c.chunkmap_conferir, "padrao: conferir desligado");
        v.sim(t.contains("cache4Seguro: false") && !c.chunkmap_cache4Seguro, "padrao: cache4Seguro desligado, dentro da secao chunkmap ("
                + (secaoChunkmapMudada(t).contains("cache4Seguro: true") ? "ok" : "FORA da secao") + ")");
        v.sim(secaoChunkmapMudada(t).contains("cache4Seguro: true"), "cache4Seguro fica na secao chunkmap do arquivo gerado");
        v.sim(c.avisos().isEmpty(), "arquivo novo sem avisos (" + c.avisos() + ")");

        File f2 = new File(dir, "ligado.yml");
        // arquivo completo: o gerado com os padroes (vale com os campos dos outros recursos), com a secao chunkmap mudada
        escrever(f2, secaoChunkmapMudada(t));
        long antes = f2.lastModified();
        c = HoshiestarConfig.carregar(f2);
        v.igual(true, c.chunkmap_ligado, "admin liga");
        v.igual(5, c.chunkmap_bits, "admin muda bits");
        v.igual(true, c.chunkmap_conferir, "admin liga conferir");
        v.igual(true, c.chunkmap_cache4Seguro, "admin liga cache4Seguro");
        v.sim(c.avisos().isEmpty() && f2.lastModified() == antes, "arquivo completo e valido nao e regravado");

        File f3 = new File(dir, "antigo.yml");
        escrever(f3, "autoteste:\n  ligado: false\n");
        c = HoshiestarConfig.carregar(f3);
        v.sim(ler(f3).contains("chunkmap:") && !c.chunkmap_ligado, "arquivo de versao anterior: chunkmap entra sozinho, desligado");

        File f4 = new File(dir, "invalido.yml");
        escrever(f4, "autoteste:\n  ligado: false\nchunkmap:\n  ligado: sim\n  bits: 6\n  conferir: false\n");
        c = HoshiestarConfig.carregar(f4);
        v.sim(!c.chunkmap_ligado && !c.avisos().isEmpty(), "ligado: sim -> padrao (desligado) e aviso");

        File f5 = new File(dir, "so-cache4.yml");
        escrever(f5, t.replace("  cache4Seguro: false", "  cache4Seguro: true"));
        c = HoshiestarConfig.carregar(f5);
        v.sim(c.chunkmap_cache4Seguro && !c.chunkmap_ligado && c.avisos().isEmpty(), "cache4Seguro ligado com chunkmap.ligado desligado: aceito, sem aviso");
        File f6 = new File(dir, "cache4-invalido.yml");
        escrever(f6, t.replace("  cache4Seguro: false", "  cache4Seguro: talvez"));
        c = HoshiestarConfig.carregar(f6);
        v.sim(!c.chunkmap_cache4Seguro && !c.avisos().isEmpty(), "cache4Seguro: talvez -> padrao (desligado) e aviso");

        v.igual(6, ChunkMap.bitsValidos(3), "bits 3 -> 6");
        v.igual(6, ChunkMap.bitsValidos(8), "bits 8 -> 6");
        v.igual(4, ChunkMap.bitsValidos(4), "bits 4 aceito");
        v.igual(7, ChunkMap.bitsValidos(7), "bits 7 aceito");

        // registro (o que o Catalogo chama no boot): recurso aparece com o interruptor do config
        Recursos.limparParaTeste();
        HoshiestarConfig ligado = HoshiestarConfig.carregar(f2);
        ChunkMap.registrar(ligado, Correcoes.novoParaTeste());
        Recursos.Recurso r = Recursos.achar("chunkmap");
        v.sim(r != null && r.ligado, "registro: recurso chunkmap ligado");
        v.sim(ChunkMap.ligado() && ChunkMap.bits() == 5 && ChunkMap.conferir(), "registro fixa os valores do config");
        v.sim(ChunkMap.cache4Seguro(), "registro fixa cache4Seguro do config");
        String linha = r == null ? null : r.linhaDeEstado();
        v.sim(linha != null && linha.contains("tabela 32x32") && linha.contains("caches criados: 0")
                && linha.contains("cache de 4 entradas seguro (cache4Seguro): ligado"), "linha de estado: " + linha);
        v.fim();
    }

    static String ler(File f) throws Exception {
        return new String(Verifica.ler(new java.io.FileInputStream(f)), Charset.forName("UTF-8"));
    }

    static void escrever(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes(Charset.forName("UTF-8")));
        } finally {
            o.close();
        }
        f.setLastModified(System.currentTimeMillis() - 60000);
    }

    /** O arquivo gerado com a secao chunkmap ligada, bits 5 e conferir ligado (o resto fica nos padroes). */
    static String secaoChunkmapMudada(String gerado) {
        int ini = gerado.indexOf("\nchunkmap:\n");
        if (ini < 0) {
            throw new IllegalStateException("arquivo gerado sem a secao chunkmap");
        }
        ini += "\nchunkmap:\n".length();
        int fim = ini;
        while (fim < gerado.length()) { // a secao vai ate a proxima chave de primeiro nivel
            int nl = gerado.indexOf('\n', fim);
            if (nl < 0) { fim = gerado.length(); break; }
            if (nl + 1 < gerado.length() && gerado.charAt(nl + 1) != ' ' && gerado.charAt(nl + 1) != '#'
                    && gerado.charAt(nl + 1) != '\n') { fim = nl + 1; break; }
            fim = nl + 1;
        }
        String secao = gerado.substring(ini, fim).replace("  ligado: false", "  ligado: true")
                .replace("  bits: 6", "  bits: 5").replace("  conferir: false", "  conferir: true").replace("  cache4Seguro: false", "  cache4Seguro: true");
        return gerado.substring(0, ini) + secao + gerado.substring(fim);
    }
}
