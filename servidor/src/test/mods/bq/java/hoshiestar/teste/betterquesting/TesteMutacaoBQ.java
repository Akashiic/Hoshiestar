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
package hoshiestar.teste.betterquesting;

import hoshiestar.betterquesting.BetterQuesting;
import hoshiestar.teste.Verifica;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Teste de MUTACAO no codigo-fonte do filtro: cada condicao e tirada (ou estragada) no PROPRIO
 * {@code FiltroInventario.java} (ou {@code GanchoBQ.java}) do workspace, a classe mutante e compilada e
 * posta na frente da do jar num class loader novo, e o diferencial (20 mil cenarios, semente 7) e os
 * casos rodam. Cada mutacao "exigida" tem de ser acusada (alguma diferenca no diferencial ou algum caso
 * falhando). Mutacoes "equivalentes" (a condicao e so conservadora: tirar faz ir menos ao original sem
 * mudar o resultado, ou outra protecao cobre) so sao relatadas.
 *
 * <p>Controle: os fontes SEM mutacao, compilados do mesmo jeito, tem de dar o mesmo bytecode das
 * classes do jar construido (instrucao por instrucao, sem depuracao) e passar em tudo. Cada trecho
 * trocado tem de aparecer EXATAMENTE uma vez no fonte (codigo mudou = o teste diz qual mutacao refazer).
 */
public final class TesteMutacaoBQ {

    /** Cria um class loader de teste com a pasta dada NA FRENTE. */
    public interface Fabrica {
        ClassLoader carregador(URL primeiro) throws Exception;
    }

    static final String FILTRO = "FiltroInventario.java";
    static final String GANCHO = "GanchoBQ.java";

    static final class Mutante {
        final String nome;
        final boolean exigido;
        final String arquivo;
        final String[] trocas;

        Mutante(String nome, boolean exigido, String arquivo, String... trocas) {
            this.nome = nome;
            this.exigido = exigido;
            this.arquivo = arquivo;
            this.trocas = trocas;
        }
    }

    static Mutante exigido(String nome, String... trocas) {
        return new Mutante(nome, true, FILTRO, trocas);
    }

    static Mutante equivalente(String nome, String... trocas) {
        return new Mutante(nome, false, FILTRO, trocas);
    }

    static final Mutante[] MUTANTES = {
        exigido("classe exata vira instanceof (subclasse de addon seria pulada)",
                "if (task.getClass() != TaskRetrieval.class) {\n            return BetterQuesting.PASSA_CLASSE;",
                "if (!(task instanceof TaskRetrieval)) {\n            return BetterQuesting.PASSA_CLASSE;"),
        exigido("regra 'consume' sem olhar autoConsume",
                "if (t.consume && !t.autoConsume) {", "if (t.consume) {"),
        exigido("regra 'completa' olhando outro membro do grupo",
                "if (t.isComplete(eu)) {", "if (t.isComplete(pInfo.ALL_UUIDS.get(pInfo.ALL_UUIDS.size() - 1))) {"),
        exigido("lista de itens vazia (o BQ completa a tarefa)",
                "if (n == 0) {\n            return BetterQuesting.PASSA_ITEM_INVALIDO;\n        }\n", ""),
        exigido("inventario nulo no grupo (C5)",
                "if (inventarioNulo) {\n            return BetterQuesting.PASSA_INVENTARIO;\n        }\n", ""),
        exigido("quantidade exigida <= 0",
                "if (r == null || r.stackSize <= 0) {", "if (r == null) {"),
        exigido("Item exigido presente no inventario",
                "if (presentes.containsKey(item)) {\n                return BetterQuesting.PASSA_PRESENTE;\n            }\n", ""),
        exigido("lista do ore dictionary nao vazia",
                "if (lista == null || !lista.isEmpty()) {", "if (lista == null) {"),
        exigido("ore pelo NOME (getOreDict().isEmpty(), o atalho que nao e exato)",
                "List<?> lista = (List<?>) ORES.invokeExact(ore);",
                "List<?> lista = r.getOreDict().isEmpty() ? java.util.Collections.emptyList() : java.util.Collections.singletonList(r);"),
        exigido("groupDetect com progresso diferente de zero",
                "if (v != 0) {\n                            return BetterQuesting.PASSA_PARCIAL;",
                "if (false) {\n                            return BetterQuesting.PASSA_PARCIAL;"),
        exigido("progresso parcial a zerar",
                "} else if (v != 0 && v < exigido) {", "} else if (false) {"),
        exigido("parcial com 'v > 0' (progresso negativo escaparia)",
                "} else if (v != 0 && v < exigido) {", "} else if (v > 0 && v < exigido) {"),
        exigido("conclusao ja satisfeita",
                "if (umSo ? algum : todosOk) {", "if (false) {"),
        exigido("requireOnlyOneItem ignorado na conclusao",
                "if (umSo ? algum : todosOk) {", "if (todosOk) {"),
        exigido("progresso so do PLAYER (sem os outros de ALL_UUIDS)",
                "for (int k = 0, m = todos.size(); k < m; k++) {\n            UUID u = todos.get(k);",
                "for (int k = 0, m = Math.min(1, todos.size()); k < m; k++) {\n            UUID u = todos.get(k);"),
        exigido("presentes so do PLAYER (sem os outros ACTIVE_PLAYERS)",
                "            if (!acrescentar(p)) {\n                return;\n            }",
                "            if (p == eu && !acrescentar(p)) {\n                return;\n            }"),
        exigido("presentes sem o PLAYER que nao esta na lista (respawn)",
                "        if (!euNaLista) {\n            acrescentar(eu);\n        }\n", ""),
        exigido("conjunto nao e refeito depois de um original que pode mexer em inventario",
                "if (task == null || task.getClass() != TaskRetrieval.class || ((TaskRetrieval) task).consume) {",
                "if (false) {"),
        exigido("cache sem a chave do ParticipantInfo (C2)",
                "if (pInfo != ultimoInfo || !presentesValidos) {", "if (!presentesValidos) {"),
        exigido("HashMap no lugar do IdentityHashMap (C1)",
                "private static final IdentityHashMap<Item, Boolean> presentes = new IdentityHashMap<Item, Boolean>(512);",
                "private static final java.util.Map<Item, Boolean> presentes = new java.util.HashMap<Item, Boolean>(512);"),
        exigido("a entrada do tick nao solta o cache (C2)",
                "    static void inicioDoTick() {\n        esquecer();\n", "    static void inicioDoTick() {\n"),
        exigido("o fim do laco nao solta o cache (C2)",
                "    static void fimDoLaco() {\n        esquecer();\n", "    static void fimDoLaco() {\n"),
        exigido("verificar sem comparar o progresso salvo",
                "            Object o = progresso.get(u);\n            if (o != f.objeto[k]) {",
                "            Object o = f.objeto[k];\n            if (o != f.objeto[k]) {"),
        new Mutante("fim do laco sem o clear() original do BQ", true, GANCHO,
                "        conjunto.clear();\n        if (BetterQuesting.modo", "        if (BetterQuesting.modo"),
        new Mutante("modo verificar pula de verdade", true, GANCHO,
                "            if (modo == BetterQuesting.LIGADO) {", "            if (modo != BetterQuesting.DESLIGADO) {"),
        new Mutante("modo desligado passa pelo filtro", true, GANCHO,
                "        if (modo == BetterQuesting.DESLIGADO) {\n            task.onInventoryChange(quest, pInfo);\n            return;\n        }\n", ""),
        equivalente("Item nulo no item exigido (conservador: o StackMatch com Item nulo so casa pilha de Item nulo, que entra no conjunto)",
                "if (item == null) {\n                return BetterQuesting.PASSA_ITEM_INVALIDO;\n            }\n", ""),
        // ---- mutacoes da revisao independente (R1 a R10)
        exigido("R1 progresso de tamanho errado vira 'pular' na hora (ignora os outros uuids)",
                "if (p.length != n) {\n                continue;", "if (p.length != n) {\n                return BetterQuesting.PULA_SEM_ITEM;"),
        exigido("R2 presentes ignoram pilhas de quantidade 1",
                "if (st != null && st.stackSize > 0) {", "if (st != null && st.stackSize > 1) {"),
        exigido("R3 uuid nulo em ALL_UUIDS e pulado em vez de ir ao original",
                "if (u == null) {\n                return BetterQuesting.PASSA_DADO_ESTRANHO;\n            }\n            Object o = progresso.get(u);\n            if (o == null) {",
                "if (u == null) {\n                continue;\n            }\n            Object o = progresso.get(u);\n            if (o == null) {"),
        exigido("R4 quantidade exigida sempre lida do item 0",
                "int exigido = exigidos.get(i).stackSize;", "int exigido = exigidos.get(0).stackSize;"),
        exigido("R5 requireOnlyOneItem com 'algum &= ok' (nunca satisfaz)",
                "algum |= ok;", "algum &= ok;"),
        new Mutante("R6 o gancho pula tambem PASSA_CLASSE (subclasses e addons)", true, GANCHO,
                "if (codigo <= BetterQuesting.PULA_SEM_ITEM) {", "if (codigo <= BetterQuesting.PASSA_CLASSE) {"),
        equivalente("R7 reconstruir o conjunto sem olhar consume (consume so TIRA itens: conjunto velho so tem Items a mais)",
                "if (task == null || task.getClass() != TaskRetrieval.class || ((TaskRetrieval) task).consume) {",
                "if (task == null || task.getClass() != TaskRetrieval.class) {"),
        equivalente("R8 inventario nulo nao interrompe a montagem (a marca inventarioNulo ja fica)",
                "            if (!acrescentar(p)) {\n                return;\n            }", "            acrescentar(p);"),
        exigido("R9 sem o teste de tamanho do progresso (progresso curto leria fora do array)",
                "            if (p.length != n) {\n                continue; // idem: getUsersProgress devolveria um array novo de zeros\n            }\n", ""),
        exigido("R10 progresso conferido mesmo com consume (com consume o parcial NAO e zerado)",
                "if (!consome) {\n                    if (grupo) {", "if (true) {\n                    if (grupo) {"),
    };

    /** Trecho do inicio do laco no FiltroInventario e a versao que sempre falha (teste de falha persistente). */
    static final String INICIO = "    static void inicioDoTick() {\n        esquecer();";
    static final String INICIO_FALHA = "    static void inicioDoTick() {\n        if (BetterQuesting.modo >= 0) {\n            throw new IllegalStateException(\"teste: falha persistente\");\n        }\n        esquecer();";
    static final String FIM = "    static void fimDoLaco() {\n        esquecer();";
    static final String FIM_FALHA = "    static void fimDoLaco() {\n        if (BetterQuesting.modo >= 0) {\n            throw new IllegalStateException(\"teste: falha persistente\");\n        }\n        esquecer();";
    /** No GanchoBQ: a volta antecipada com o recurso desligado (tirada no controle do teste de falha persistente). */
    static final String VOLTA = "        if (BetterQuesting.modo == BetterQuesting.DESLIGADO || !ThreadServidor.souAThreadDoServidor()) {";
    static final String SEM_VOLTA = "        if (!ThreadServidor.souAThreadDoServidor()) {";

    private TesteMutacaoBQ() {
    }

    /** Nomes do Minecraft no fonte (MCP) -&gt; nomes de tempo de execucao (SRG), so os que o filtro usa. */
    static final String[][] SRG = {
        {"\\.inventory\\b", ".field_71071_by"},
        {"\\.getSizeInventory\\(\\)", ".func_70302_i_()"},
        {"\\.getStackInSlot\\(", ".func_70301_a("},
        {"\\bst\\.stackSize\\b", "st.field_77994_a"},
        {"\\.getItem\\(\\)", ".func_77973_b()"},
    };

    static String paraSrg(String s) {
        for (String[] t : SRG) {
            s = Pattern.compile(t[0]).matcher(s).replaceAll(Matcher.quoteReplacement(t[1]));
        }
        return s;
    }

    /**
     * @param dirFonte a pasta src/mods/java/hoshiestar/betterquesting/filtro do workspace
     * @param cp classpath para compilar (Minecraft SRG, servidor SRG, BQ, ASM)
     */
    public static void rodar(Verifica v, Fabrica f, File dirFonte, File trabalho, String cp, File jarServidorSrg) throws Exception {
        System.out.println("-- mutacao no codigo-fonte do filtro");
        String filtro = new String(ler(new File(dirFonte, FILTRO)), Charset.forName("UTF-8"));
        String gancho = new String(ler(new File(dirFonte, GANCHO)), Charset.forName("UTF-8"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            v.sim(false, "mutacao: compilador Java nao disponivel (rode com um JDK)");
            return;
        }
        File ctl = compilar(javac, paraSrg(filtro), paraSrg(gancho), new File(trabalho, "controle"), cp);
        v.sim(ctl != null, "mutacao, controle: os fontes sem mutacao compilam com os nomes de tempo de execucao");
        if (ctl == null) {
            return;
        }
        ZipFile jar = new ZipFile(jarServidorSrg);
        for (String c : new String[] {"FiltroInventario", "FiltroInventario$Foto", "GanchoBQ"}) {
            String caminho = "hoshiestar/betterquesting/filtro/" + c + ".class";
            String a = texto(ler(new File(ctl, caminho)));
            ZipEntry ze = jar.getEntry(caminho);
            String b = ze == null ? "(ausente no jar)" : texto(lerTudo(jar.getInputStream(ze)));
            v.sim(a.equals(b), "mutacao, controle: " + c + " compilada aqui = a do jar construido, instrucao por instrucao"
                    + (a.equals(b) ? "" : "\n" + primeiraDiferenca(a, b)));
        }
        jar.close();
        ClassLoader cl0 = f.carregador(ctl.toURI().toURL());
        URL origem = ((URLClassLoader) cl0).findResource("hoshiestar/betterquesting/filtro/FiltroInventario.class");
        v.sim(origem != null && origem.toString().startsWith(ctl.toURI().toURL().toString()),
                "mutacao, controle: o filtro testado vem da pasta da variante (" + origem + ")");
        List<String> f0 = rodarTudo(cl0);
        v.sim(f0.isEmpty(), "mutacao, controle: diferencial e casos passam com as classes recompiladas" + (f0.isEmpty() ? "" : " -> " + f0));

        int pegos = 0;
        int exigidos = 0;
        for (int k = 0; k < MUTANTES.length; k++) {
            Mutante m = MUTANTES[k];
            String mut = m.arquivo.equals(FILTRO) ? filtro : gancho;
            String erro = null;
            for (int t = 0; t < m.trocas.length; t += 2) {
                int n = contar(mut, m.trocas[t]);
                if (n != 1) {
                    erro = "trecho aparece " + n + " vez(es) no fonte (precisa ser 1; refaca a mutacao): " + m.trocas[t].trim().split("\n")[0];
                    break;
                }
                mut = mut.replace(m.trocas[t], m.trocas[t + 1]);
            }
            if (erro != null) {
                v.sim(false, "mutacao '" + m.nome + "': " + erro);
                continue;
            }
            File dir = m.arquivo.equals(FILTRO) ? compilar(javac, paraSrg(mut), paraSrg(gancho), new File(trabalho, "m" + k), cp)
                    : compilar(javac, paraSrg(filtro), paraSrg(mut), new File(trabalho, "m" + k), cp);
            if (dir == null) {
                v.sim(false, "mutacao '" + m.nome + "': nao compilou");
                continue;
            }
            List<String> falhas = rodarTudo(f.carregador(dir.toURI().toURL()));
            String resumo = falhas.isEmpty() ? "nada acusou" : falhas.size() + " acusacao(oes); primeira: " + encurtar(falhas.get(0));
            if (m.exigido) {
                exigidos++;
                if (!falhas.isEmpty()) {
                    pegos++;
                }
                v.sim(!falhas.isEmpty(), "mutacao '" + m.nome + "': acusada -> " + resumo);
            } else {
                System.out.println("  info  mutacao equivalente '" + m.nome + "': " + resumo);
            }
        }
        System.out.println("  mutacao: " + pegos + " de " + exigidos + " mutacoes exigidas acusadas; "
                + (MUTANTES.length - exigidos) + " equivalente(s) relatada(s)");
        persistente(v, f, javac, filtro, gancho, trabalho, cp);
        BetterQuesting.zerarContadores();
        BetterQuesting.modo = BetterQuesting.LIGADO;
    }

    /**
     * Falha persistente (revisao M2): um FiltroInventario mutante que SEMPRE lanca excecao no inicio (ou
     * no fim) do laco; 20 ticks. Com o GanchoBQ de verdade tem de contar 1 erro so (o primeiro desliga o
     * recurso e os ganchos param de chamar o filtro). Controle: com o GanchoBQ sem a volta antecipada,
     * o mesmo teste ve 20 erros (o teste enxerga a falha).
     */
    static void persistente(Verifica v, Fabrica f, JavaCompiler javac, String filtro, String gancho, File trabalho, String cp)
            throws Exception {
        if (contar(filtro, INICIO) != 1 || contar(filtro, FIM) != 1 || contar(gancho, VOLTA) != 2) {
            v.sim(false, "falha persistente: trechos do fonte mudaram (refaca o teste)");
            return;
        }
        String falhaInicio = filtro.replace(INICIO, INICIO_FALHA);
        String falhaFim = filtro.replace(FIM, FIM_FALHA);
        String semVolta = gancho.replace(VOLTA, SEM_VOLTA);
        Object[][] variantes = {
            {"falha persistente no inicio do laco", falhaInicio, gancho, 1L},
            {"falha persistente no fim do laco", falhaFim, gancho, 1L},
            {"controle: falha no inicio com o gancho sem a volta antecipada", falhaInicio, semVolta, 20L},
        };
        for (int k = 0; k < variantes.length; k++) {
            Object[] x = variantes[k];
            File dir = compilar(javac, paraSrg((String) x[1]), paraSrg((String) x[2]), new File(trabalho, "p" + k), cp);
            if (dir == null) {
                v.sim(false, x[0] + ": nao compilou");
                continue;
            }
            Class<?> casos = Class.forName("hoshiestar.teste.betterquesting.mundo.Casos", true, f.carregador(dir.toURI().toURL()));
            long erros = (Long) casos.getMethod("persistente", int.class).invoke(null, 20);
            v.igual(x[3], erros, x[0] + ": erros contados em 20 ticks");
        }
    }

    /** Diferencial (20 mil, semente 7) + casos; devolve as acusacoes (vazio = nada acusou). */
    @SuppressWarnings("unchecked")
    static List<String> rodarTudo(ClassLoader cl) throws Exception {
        List<String> r = new ArrayList<String>();
        BetterQuesting.zerarContadores();
        Class<?> dif = Class.forName("hoshiestar.teste.betterquesting.mundo.Diferencial", true, cl);
        List<String> s = (List<String>) dif.getMethod("rodar", int.class, long.class, int.class).invoke(null, 20000, 7L, BetterQuesting.LIGADO);
        if (!s.get(0).endsWith("DIFERENCAS=0")) {
            r.add("diferencial: " + s.get(0).substring(s.get(0).lastIndexOf("DIFERENCAS")));
        }
        if (BetterQuesting.contagem[BetterQuesting.ERROS] != 0) {
            r.add("diferencial: erro no nosso codigo (" + BetterQuesting.desligadoPorErro + ")");
        }
        Class<?> casos = Class.forName("hoshiestar.teste.betterquesting.mundo.Casos", true, cl);
        r.addAll((List<String>) casos.getMethod("rodar").invoke(null));
        return r;
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
        return u.length() > 220 ? u.substring(0, 220) + "..." : u;
    }

    /** Compila os dois fontes (nomes SRG) numa pasta nova; devolve a pasta das classes ou null. */
    static File compilar(JavaCompiler javac, String filtro, String gancho, File dir, String cp) throws Exception {
        apagar(dir);
        File base = new File(dir, "src/hoshiestar/betterquesting/filtro");
        File cls = new File(dir, "classes");
        base.mkdirs();
        cls.mkdirs();
        escrever(new File(base, FILTRO), filtro);
        escrever(new File(base, GANCHO), gancho);
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        int rc = javac.run(null, saida, saida, "-nowarn", "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8",
                "-cp", cp, "-d", cls.getPath(), new File(base, FILTRO).getPath(), new File(base, GANCHO).getPath());
        if (rc != 0) {
            System.out.println(saida.toString("UTF-8"));
            return null;
        }
        return cls;
    }

    static void escrever(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes(Charset.forName("UTF-8")));
        } finally {
            o.close();
        }
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
        InputStream in = new FileInputStream(f);
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
