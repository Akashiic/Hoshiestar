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
package hoshiestar.teste.mekanism;

import hoshiestar.teste.Verifica;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Teste de MUTACAO no codigo-fonte: cada protecao e tirada (ou estragada) no PROPRIO
 * {@code MatrizInducao.java} do workspace, a classe mutante e compilada e posta na frente da original
 * num class loader de teste novo, com a correcao real aplicada na classe real do Mekanism, e TODOS os
 * cenarios rodam. Uma mutacao "exigida" tem de fazer falhar pelo menos um dos cenarios esperados.
 * Mutacoes "equivalentes" (outra protecao ou a propria busca original cobre o caso) so sao relatadas.
 *
 * <p>Controle: o fonte SEM mutacao, compilado do mesmo jeito, tem de dar o mesmo bytecode da classe do
 * jar construido (instrucao por instrucao, sem informacao de depuracao) e passar em todos os cenarios.
 * Isso prova que a traducao de nomes do Minecraft feita aqui (nomes MCP do fonte -&gt; nomes de tempo de
 * execucao) e fiel e que o teste roda mesmo a classe mutante.
 *
 * <p>Cada trecho trocado tem de aparecer EXATAMENTE uma vez no fonte; se alguem mudar o codigo, o
 * teste falha dizendo qual mutacao precisa ser refeita (nunca vira uma mutacao vazia em silencio).
 */
public final class TesteMutacao {

    /** Cria um class loader de teste com a pasta dada NA FRENTE e a correcao do Mekanism aplicada. */
    public interface Fabrica {
        ClassLoader carregador(URL primeiro) throws Exception;
    }

    static final class Mutante {
        final String nome;
        final boolean exigido;
        final String[] esperados;
        final String[] trocas;

        Mutante(String nome, boolean exigido, String[] esperados, String... trocas) {
            this.nome = nome;
            this.exigido = exigido;
            this.esperados = esperados;
            this.trocas = trocas;
        }
    }

    // nomes dos cenarios (iguais aos de mundo.Cenarios; um nome errado faz o teste falhar)
    static final String ORFA = "orfa-0J-remocao (ConfAdv)";
    static final String TROCA = "orfa-0J-troca crua (identidade na escrita)";
    static final String VER_TROCA = "verificar: troca crua e detectada por identidade (soma cega)";
    static final String VER_LIMPO = "verificar: leituras e escritas comparadas, nunca pelo caminho rapido";
    static final String VER_CONFADV = "verificar: ConfAdv com o mapa do mesmo tamanho (outra TE no mesmo chunk)";
    static final String CONFADV_TAM = "ConfAdv com o mapa do mesmo tamanho: escrita recusada pela identidade";
    static final String REENTRADA = "reentrada durante a reconstrucao";
    static final String CHUNK = "chunk descarregado no meio";
    static final String CHUNK_CRU = "chunk tirado do provedor sem onChunkUnload";
    static final String ZERAR = "referencias zeradas quando a validacao falha";
    static final String MESMA = "troca de celula por outra na mesma coordenada";
    static final String CRESCE = "estrutura que cresce e encolhe";
    static final String MUNDO = "mundo diferente";
    static final String THREAD = "chamada fora da thread do servidor";
    static final String ALTURA = "altura fora de 0..255 (mundo fundo)";
    static final String ESPERA = "estrutura incompleta: nova tentativa so a cada 20 ticks";
    static final String RELOGIO = "troca crua e um tick (relogio)";
    static final String TAMANHO = "remocao crua com energia no mesmo tick (tamanho do mapa)";
    static final String MOVIDA = "celula movida para outra coordenada no mesmo chunk";
    static final String OUTRO_MUNDO = "celula levada para outro mundo (como o Spatial IO do AE2)";
    static final String TODAS = "R1 troca crua em cada posicao + escrita cheia (identidade em todas as posicoes)";
    static final String CRUZADA = "R2 reentrada cruzada (durante a reconstrucao de S1, um vizinho le e escreve S2)";
    static final String MORTA = "R3 celula invalidada que ficou no mapa + escrita no mesmo tick";
    static final String EIXO = "R4 celula movida num so eixo (x, y, z), mesmo chunk, mesmo tick";
    static final String TERCEIROS = "R5 excecao de outro mod na busca original (ligado): a mesma excecao sai";
    static final String TERCEIROS_VER = "R5 excecao de outro mod na busca original (verificar): a mesma excecao sai";

    static String[] e(String... s) {
        return s;
    }

    static Mutante exigido(String nome, String[] esperados, String... trocas) {
        return new Mutante(nome, true, esperados, trocas);
    }

    static Mutante equivalente(String nome, String... trocas) {
        return new Mutante(nome, false, new String[0], trocas);
    }

    static final String CHUNK_EXISTS = "            if (!prov.chunkExists(r.cxs[j], r.czs[j])) {\n                return false;\n            }\n";
    static final String MESMO_CHUNK = "            if (w.getChunkFromChunkCoords(r.cxs[j], r.czs[j]) != ch) {\n                return false;\n            }\n";

    /** A tabela de mutacoes (trocas: trecho original, trecho mutante, ...). */
    static final Mutante[] MUTANTES = {
        exigido("conjunto (mesmo objeto e tamanho)", e(CRESCE),
                "if (r.conjunto != cells || r.tamanho != cells.size()) {", "if (false) {"),
        exigido("relogio (lista vale so no ciclo em que foi montada)", e(RELOGIO),
                "if (idade < 0 || idade >= Mekanism.intervalo) {", "if (false) {"),
        exigido("relogio com > no lugar de >= (lista vale um tick a mais)", e(RELOGIO),
                "idade >= Mekanism.intervalo", "idade > Mekanism.intervalo"),
        exigido("laco inteiro de conferencia dos chunks", e(CHUNK),
                "        for (int j = 0; j < r.chunks.length; j++) {\n            Chunk ch = r.chunks[j];\n            if (!ch.isChunkLoaded) {",
                "        for (int j = 0; j < 0; j++) {\n            Chunk ch = r.chunks[j];\n            if (!ch.isChunkLoaded) {"),
        exigido("chunkExists na conferencia dos chunks", e(CHUNK_CRU),
                CHUNK_EXISTS, ""),
        exigido("ordem: getChunkFromChunkCoords antes de chunkExists (carregaria chunk)", e(CHUNK_CRU),
                CHUNK_EXISTS + MESMO_CHUNK, MESMO_CHUNK + CHUNK_EXISTS),
        equivalente("isChunkLoaded sozinho (coberto por chunkExists)",
                "            if (!ch.isChunkLoaded) {\n                return false;\n            }\n            if (!prov", "            if (!prov"),
        equivalente("chunk e o mesmo objeto, sozinho (coberto por isChunkLoaded e pelo mundo)", MESMO_CHUNK, ""),
        equivalente("mundo da estrutura, sozinho (coberto pelo chunk e pelo mundo da celula)",
                "if (r.mundo != w) {", "if (false) {"),
        exigido("mundo da estrutura + chunk mesmo objeto + mundo da celula", e(MUNDO),
                "if (r.mundo != w) {", "if (false) {", MESMO_CHUNK, "", "if (t.getWorldObj() != w) {", "if (false) {"),
        exigido("tamanho do mapa de tile entities do chunk", e(TAMANHO, ORFA),
                "if (ch.chunkTileEntityMap.size() != r.tamanhoMapa[j]) {", "if (false) {"),
        exigido("celula invalidada", e(MESMA),
                "if (t.isInvalid()) {", "if (false) {"),
        exigido("coordenada da celula (os tres eixos)", e(MOVIDA, EIXO),
                "if (t.xCoord != r.xs[i] || t.yCoord != r.ys[i] || t.zCoord != r.zs[i]) {", "if (false) {"),
        exigido("coordenada da celula sem o eixo x", e(EIXO),
                "t.xCoord != r.xs[i] || t.yCoord", "t.yCoord"),
        exigido("coordenada da celula sem o eixo y", e(EIXO),
                "r.xs[i] || t.yCoord != r.ys[i] || t.zCoord", "r.xs[i] || t.zCoord"),
        exigido("coordenada da celula sem o eixo z (mutante MD da revisao)", e(EIXO),
                " || t.zCoord != r.zs[i]) {", ") {"),
        exigido("mundo da celula", e(OUTRO_MUNDO),
                "if (t.getWorldObj() != w) {", "if (false) {"),
        exigido("flag de reentrada na leitura", e(REENTRADA),
                "        if (em.construindo) {\n            Mekanism.reentradas++;\n            return false;\n        }",
                "        if (false) {\n            Mekanism.reentradas++;\n            return false;\n        }"),
        exigido("flag de reentrada na escrita", e(REENTRADA),
                "        if (em.construindo) {\n            Mekanism.reentradas++;\n            Mekanism.escritasOriginais++;",
                "        if (false) {\n            Mekanism.reentradas++;\n            Mekanism.escritasOriginais++;"),
        equivalente("publicar a lista vazia ANTES de montar, sozinho (coberto pela flag de reentrada)",
                "        em.construindo = true;\n        try {\n            for (Iterator<Coord4D> it = cells.iterator(); it.hasNext(); i++) {\n                Coord4D coord = it.next();\n                TileEntity tile",
                "        em.atual = new Retrato(w, cells, n, agora, refs, xs, ys, zs, grupo, new Chunk[0], new int[0], new int[0], new int[0]);\n"
                + "        em.construindo = true;\n        try {\n            for (Iterator<Coord4D> it = cells.iterator(); it.hasNext(); i++) {\n                Coord4D coord = it.next();\n                TileEntity tile"),
        exigido("publicar antes de montar + sem a flag de reentrada na leitura", e(REENTRADA),
                "        em.construindo = true;\n        try {\n            for (Iterator<Coord4D> it = cells.iterator(); it.hasNext(); i++) {\n                Coord4D coord = it.next();\n                TileEntity tile",
                "        em.atual = new Retrato(w, cells, n, agora, refs, xs, ys, zs, grupo, new Chunk[0], new int[0], new int[0], new int[0]);\n"
                + "        em.construindo = true;\n        try {\n            for (Iterator<Coord4D> it = cells.iterator(); it.hasNext(); i++) {\n                Coord4D coord = it.next();\n                TileEntity tile",
                "        if (em.construindo) {\n            Mekanism.reentradas++;\n            return false;\n        }",
                "        if (false) {\n            Mekanism.reentradas++;\n            return false;\n        }"),
        exigido("espera de 20 ticks da estrutura incompleta", e(ESPERA),
                "        if (em.incompletaEm != NUNCA) {\n            long d = agora - em.incompletaEm;",
                "        if (false) {\n            long d = agora - em.incompletaEm;"),
        equivalente("ordem chunkExists -> getChunk na reconstrucao (a busca original ja conferiu chunkExists)",
                "        if (prov == null || !prov.chunkExists(cx, cz)) {\n            return null;\n        }\n        Chunk ch = w.getChunkFromChunkCoords(cx, cz);",
                "        Chunk ch = w.getChunkFromChunkCoords(cx, cz);\n        if (prov == null || !prov.chunkExists(cx, cz)) {\n            return null;\n        }"),
        exigido("identidade antes da escrita (laco inteiro)", e(TROCA, CONFADV_TAM),
                "        for (int i = 0; i < refs.length; i++) {\n            if (r.chunks[r.grupo[i]].chunkTileEntityMap.get(k[i]) != refs[i]) {",
                "        for (int i = 0; i < 0; i++) {\n            if (r.chunks[r.grupo[i]].chunkTileEntityMap.get(k[i]) != refs[i]) {"),
        exigido("identidade pula a ULTIMA celula (mutante MC da revisao)", e(TODAS),
                "        for (int i = 0; i < refs.length; i++) {\n            if (r.chunks[r.grupo[i]].chunkTileEntityMap.get(k[i]) != refs[i]) {",
                "        for (int i = 0; i < refs.length - 1; i++) {\n            if (r.chunks[r.grupo[i]].chunkTileEntityMap.get(k[i]) != refs[i]) {"),
        exigido("identidade pula a PRIMEIRA celula", e(TROCA, TODAS),
                "        for (int i = 0; i < refs.length; i++) {\n            if (r.chunks[r.grupo[i]].chunkTileEntityMap.get(k[i]) != refs[i]) {",
                "        for (int i = 1; i < refs.length; i++) {\n            if (r.chunks[r.grupo[i]].chunkTileEntityMap.get(k[i]) != refs[i]) {"),
        exigido("escrita sem conferir a celula (so a identidade; mutante MG da revisao)", e(MORTA),
                "        for (int i = 0; i < refs.length; i++) {\n            if (!celulaConfere(refs[i], r, i, w)) {\n                return 1;\n            }\n        }\n", ""),
        exigido("zerar a lista na escrita com lista invalida", e(ZERAR),
                "            if (r != null) {\n                em.atual = null;\n            }\n", ""),
        exigido("zerar a lista depois da recusa da escrita", e(TROCA),
                "                Mekanism.recusasIdentidade++;\n            }\n            em.atual = null;\n",
                "                Mekanism.recusasIdentidade++;\n            }\n"),
        equivalente("zerar a lista na leitura (a reconstrucao logo em seguida sobrescreve)",
                "            em.atual = null; // nao prender mundo, chunk nem celula de uma lista que nao vale mais\n", ""),
        exigido("thread do servidor", e(THREAD),
                "if (!ThreadServidor.souAThreadDoServidor()) {", "if (false) {"),
        exigido("altura 0..255", e(ALTURA),
                "if (y < 0 || y >= 256) {", "if (false) {"),
        equivalente("coordenada/mundo da celula achada na reconstrucao (conferidos de novo a cada uso)",
                "if (tile.xCoord != x || tile.yCoord != y || tile.zCoord != z || tile.getWorldObj() != w) {", "if (false) {"),
        exigido("soma rapida em ordem inversa (mutante MB da revisao)", e(TROCA, VER_LIMPO),
                "        for (int i = 0; i < refs.length; i++) {\n            TileEntityInductionCell c = refs[i];",
                "        for (int i = refs.length - 1; i >= 0; i--) {\n            TileEntityInductionCell c = refs[i];"),
        exigido("reconstrucao acumulando no campo estatico (mutante ME da revisao)", e(CRUZADA),
                "        IChunkProvider prov = w.getChunkProvider();\n        em.construindo = true;",
                "        IChunkProvider prov = w.getChunkProvider();\n        ultimoValor = 0.0;\n        em.construindo = true;",
                "                ret += cell.getEnergy(); // a mesma conta do original, na mesma ordem",
                "                ultimoValor += cell.getEnergy();\n                ret = ultimoValor;",
                "        ultimoValor = ret;\n        if (completa) {", "        if (completa) {"),
        exigido("excecao de outro mod sem o embrulho (tratada como nossa)", e(TERCEIROS, TERCEIROS_VER),
                "        try {\n            return coord.getTileEntity(w);\n        } catch (Throwable t) {\n            throw new DeTerceiros(t);\n        }",
                "        return coord.getTileEntity(w);"),
        exigido("verificar sem comparar a identidade na leitura", e(VER_TROCA, VER_CONFADV),
                "                    original += ((TileEntityInductionCell) t).getEnergy();\n                }\n                if (i >= n || t != r.refs[i]) {",
                "                    original += ((TileEntityInductionCell) t).getEnergy();\n                }\n                if (i >= n) {"),
        exigido("verificar le pelo caminho rapido", e(VER_TROCA, VER_LIMPO),
                "                if (verificar) {\n                    verificarLeitura(w, em, r, cells);\n                    return false;\n                }\n", ""),
        exigido("verificar escreve pelo caminho rapido", e(VER_LIMPO),
                "        if (Mekanism.modo == Mekanism.VERIFICAR) {\n            verificarEscrita(w, em, r, cells);\n            Mekanism.escritasOriginais++;\n            return false;\n        }\n", ""),
        equivalente("verificar sem comparar a soma (mesmos objetos dao a mesma soma)",
                "if (Double.doubleToLongBits(rapida) != Double.doubleToLongBits(original)) {", "if (false) {"),
        equivalente("verificar sem comparar a identidade na escrita (no mundo falso a conferencia da escrita ja pega)",
                "            for (Iterator<Coord4D> it = cells.iterator(); it.hasNext(); i++) {\n                TileEntity t = buscar(it.next(), w);\n                if (i >= n || t != r.refs[i]) {",
                "            for (Iterator<Coord4D> it = cells.iterator(); it.hasNext(); i++) {\n                TileEntity t = buscar(it.next(), w);\n                if (i >= n) {"),
    };

    private TesteMutacao() {
    }

    /** Nomes do Minecraft no fonte (MCP) -&gt; nomes de tempo de execucao (SRG), so os que a logica usa. */
    static final String[][] SRG = {
        {"\\.isInvalid\\(\\)", ".func_145837_r()"},
        {"(?<!coord)\\.xCoord\\b", ".field_145851_c"},
        {"(?<!coord)\\.yCoord\\b", ".field_145848_d"},
        {"(?<!coord)\\.zCoord\\b", ".field_145849_e"},
        {"\\.getWorldObj\\(\\)", ".func_145831_w()"},
        {"\\.getChunkProvider\\(\\)", ".func_72863_F()"},
        {"\\.getChunkFromChunkCoords\\(", ".func_72964_e("},
        {"\\.isRemote\\b", ".field_72995_K"},
        {"\\.chunkExists\\(", ".func_73149_a("},
        {"\\.isChunkLoaded\\b", ".field_76636_d"},
        {"\\.chunkTileEntityMap\\b", ".field_150816_i"},
    };

    static String paraSrg(String s) {
        for (String[] t : SRG) {
            s = Pattern.compile(t[0]).matcher(s).replaceAll(Matcher.quoteReplacement(t[1]));
        }
        return s;
    }

    /**
     * @param fonte o MatrizInducao.java do workspace; @param trabalho pasta de trabalho;
     * @param cp classpath para compilar (Minecraft SRG, servidor SRG, Mekanism, IC2, ASM);
     * @param jarServidorSrg o jar do servidor traduzido (para o controle de bytecode)
     */
    public static void rodar(Verifica v, Fabrica f, File fonte, File trabalho, String cp, File jarServidorSrg) throws Exception {
        String original = new String(ler(fonte), Charset.forName("UTF-8"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            v.sim(false, "mutacao: compilador Java nao disponivel (rode com um JDK)");
            return;
        }
        // controle: sem mutacao
        File ctl = compilar(javac, paraSrg(original), new File(trabalho, "controle"), cp);
        v.sim(ctl != null, "mutacao, controle: o fonte sem mutacao compila com os nomes de tempo de execucao");
        if (ctl == null) {
            return;
        }
        ZipFile jar = new ZipFile(jarServidorSrg);
        for (String c : new String[] {"MatrizInducao", "MatrizInducao$EstadoMatriz", "MatrizInducao$Retrato"}) {
            String caminho = "hoshiestar/mekanism/matriz/" + c + ".class";
            String a = texto(ler(new File(ctl, caminho)));
            ZipEntry ze = jar.getEntry(caminho);
            String b = ze == null ? "(ausente no jar)" : texto(lerTudo(jar.getInputStream(ze)));
            v.sim(a.equals(b), "mutacao, controle: " + c + " compilada aqui = a do jar construido, instrucao por instrucao"
                    + (a.equals(b) ? "" : "\n" + primeiraDiferenca(a, b)));
        }
        jar.close();
        ClassLoader cl0 = f.carregador(ctl.toURI().toURL());
        URL origem = ((URLClassLoader) cl0).findResource("hoshiestar/mekanism/matriz/MatrizInducao.class");
        v.sim(origem != null && origem.toString().startsWith(ctl.toURI().toURL().toString()),
                "mutacao, controle: a logica testada vem da pasta da variante (" + origem + ")");
        List<String> todos = nomes(cl0);
        List<String> f0 = rodarCenarios(cl0, todos);
        v.sim(f0.isEmpty(), "mutacao, controle: os " + todos.size() + " cenarios passam com a classe recompilada"
                + (f0.isEmpty() ? "" : " -> " + f0));

        int pegos = 0;
        int exigidos = 0;
        for (int k = 0; k < MUTANTES.length; k++) {
            Mutante m = MUTANTES[k];
            String erro = null;
            String mut = original;
            for (int t = 0; t < m.trocas.length; t += 2) {
                int n = contar(mut, m.trocas[t]);
                if (n != 1) {
                    erro = "trecho aparece " + n + " vez(es) no fonte (precisa ser 1; refaca a mutacao): "
                            + m.trocas[t].trim().split("\n")[0];
                    break;
                }
                mut = mut.replace(m.trocas[t], m.trocas[t + 1]);
            }
            if (erro == null) {
                for (String esp : m.esperados) {
                    if (!todos.contains(esp)) {
                        erro = "cenario esperado nao existe: " + esp;
                    }
                }
            }
            if (erro != null) {
                v.sim(false, "mutacao '" + m.nome + "': " + erro);
                continue;
            }
            File dir = compilar(javac, paraSrg(mut), new File(trabalho, "m" + k), cp);
            if (dir == null) {
                v.sim(false, "mutacao '" + m.nome + "': nao compilou");
                continue;
            }
            List<String> falhas = rodarCenarios(f.carregador(dir.toURI().toURL()), todos);
            List<String> cenariosQueFalharam = new ArrayList<String>();
            for (String x : falhas) {
                String c = cenarioDa(x, todos);
                if (!cenariosQueFalharam.contains(c)) {
                    cenariosQueFalharam.add(c);
                }
            }
            boolean esperadoFalhou = false;
            for (String esp : m.esperados) {
                esperadoFalhou |= cenariosQueFalharam.contains(esp);
            }
            String resumo = cenariosQueFalharam.size() + " cenario(s) falharam" + (falhas.isEmpty() ? "" : " " + cenariosQueFalharam
                    + "; primeira falha: " + encurtar(falhas.get(0)));
            if (m.exigido) {
                exigidos++;
                if (esperadoFalhou) {
                    pegos++;
                }
                v.sim(esperadoFalhou, "mutacao '" + m.nome + "': acusada por " + Arrays.toString(m.esperados) + " -> " + resumo);
            } else {
                System.out.println("  info  mutacao equivalente '" + m.nome + "': " + resumo);
            }
        }
        System.out.println("  mutacao: " + pegos + " de " + exigidos + " mutacoes exigidas acusadas; "
                + (MUTANTES.length - exigidos) + " equivalentes relatadas");
    }

    @SuppressWarnings("unchecked")
    static List<String> nomes(ClassLoader cl) throws Exception {
        Class<?> cen = Class.forName("hoshiestar.teste.mekanism.mundo.Cenarios", true, cl);
        return (List<String>) cen.getMethod("nomes").invoke(null);
    }

    @SuppressWarnings("unchecked")
    static List<String> rodarCenarios(ClassLoader cl, List<String> nomes) throws Exception {
        Class<?> cen = Class.forName("hoshiestar.teste.mekanism.mundo.Cenarios", true, cl);
        return (List<String>) cen.getMethod("rodarParaMutacao", List.class).invoke(null, nomes);
    }

    /** O cenario de uma linha de falha ("nome do cenario: falha"; o nome pode ter ": " dentro). */
    static String cenarioDa(String falha, List<String> todos) {
        String melhor = "?";
        for (String n : todos) {
            if (falha.startsWith(n + ": ") && n.length() > melhor.length()) {
                melhor = n;
            }
        }
        return melhor;
    }

    static int contar(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) {
            n++;
        }
        return n;
    }

    static String encurtar(String s) {
        String u = s.replace('\n', ' ');
        return u.length() > 260 ? u.substring(0, 260) + "..." : u;
    }

    /** Compila o fonte (nomes SRG) numa pasta nova; devolve a pasta das classes ou null. */
    static File compilar(JavaCompiler javac, String fonte, File dir, String cp) throws Exception {
        apagar(dir);
        File src = new File(dir, "src/hoshiestar/mekanism/matriz/MatrizInducao.java");
        File cls = new File(dir, "classes");
        src.getParentFile().mkdirs();
        cls.mkdirs();
        FileOutputStream o = new FileOutputStream(src);
        try {
            o.write(fonte.getBytes(Charset.forName("UTF-8")));
        } finally {
            o.close();
        }
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        int rc = javac.run(null, saida, saida, "-nowarn", "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8",
                "-cp", cp, "-d", cls.getPath(), src.getPath());
        if (rc != 0) {
            System.out.println(saida.toString("UTF-8"));
            return null;
        }
        return cls;
    }

    /** Texto da classe sem depuracao (linhas, nomes de variaveis) e sem quadros de pilha. */
    static String texto(byte[] b) {
        StringWriter sw = new StringWriter();
        new ClassReader(b).accept(new TraceClassVisitor(null, new Textifier(), new PrintWriter(sw)),
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return sw.toString();
    }

    static String primeiraDiferenca(String a, String b) {
        String[] la = a.split("\n");
        String[] lb = b.split("\n");
        for (int i = 0; i < Math.max(la.length, lb.length); i++) {
            String x = i < la.length ? la[i] : "(fim)";
            String y = i < lb.length ? lb[i] : "(fim)";
            if (!x.equals(y)) {
                return "  linha " + (i + 1) + ": aqui '" + x.trim() + "' x jar '" + y.trim() + "'";
            }
        }
        return "";
    }

    static void apagar(File f) {
        File[] l = f.listFiles();
        if (l != null) {
            for (File x : l) {
                apagar(x);
            }
        }
        f.delete();
    }

    static byte[] ler(File f) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
        try {
            return lerTudo(in);
        } finally {
            in.close();
        }
    }

    static byte[] lerTudo(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0;) {
            o.write(buf, 0, n);
        }
        return o.toByteArray();
    }
}
