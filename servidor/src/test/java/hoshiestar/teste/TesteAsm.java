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

import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.Chamada;
import hoshiestar.embutidos.asm.Gancho;
import hoshiestar.embutidos.asm.PadraoNaoEncontrado;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Testes dos utilitarios ASM (formatos 1, 2 e 3) com a mesma classe de exemplo compilada para Java 6,
 * 7 e 8. Cada classe transformada passa pelo CheckClassAdapter (verificador do ASM) e depois e
 * CARREGADA e EXECUTADA num class loader de teste (verificador da propria JVM).
 *
 * <p>Argumentos: {@code 6=<pasta> 7=<pasta> 8=<pasta>}, cada pasta com amostra/Alvo.class.
 */
public final class TesteAsm {

    private static final String ALVO = "amostra/Alvo";
    private static final String G = "hoshiestar/teste/ganchos/GanchosTeste";

    interface Mudanca {
        void aplicar(ClassNode cn) throws Exception;
    }

    private static Verifica v;
    private static ResolvedorDeGanchos ganchos;

    public static void main(String[] a) throws Exception {
        v = new Verifica("TesteAsm");
        ganchos = new ResolvedorDeGanchos(TesteAsm.class.getClassLoader());
        for (String arg : a) {
            String[] p = arg.split("=", 2);
            int versao = Integer.parseInt(p[0]);
            try {
                rodar(versao, new File(p[1]));
            } catch (Throwable t) {
                v.erro("Java " + versao + ": erro inesperado", t);
            }
        }
        try {
            remapeador();
        } catch (Throwable t) {
            v.erro("remapeador: erro inesperado", t);
        }
        v.fim();
    }

    private static void rodar(int java, File pasta) throws Exception {
        byte[] alvo = Verifica.ler(new FileInputStream(new File(pasta, "amostra/Alvo.class")));
        byte[] util = Verifica.ler(new FileInputStream(new File(pasta, "amostra/Alvo$Util.class")));
        int major = new ClassReader(alvo).readShort(6);
        v.igual(44 + java, major, "Java " + java + ": versao do .class");
        String j = "Java " + java + ": ";

        // ------------------------------------------------ formato 1
        Class<?> c = carregar(j + "F1 fazer sem retorno antecipado", alvo, util, cn -> Asm.ganchoNoInicio(cn, "fazer",
                "(ILjava/lang/String;)V", Gancho.de(G, "inicioFazer"), null, false, ganchos));
        Object o = c.newInstance();
        c.getMethod("fazer", int.class, String.class).invoke(o, 4, "x");
        v.igual(102, campo(o, "efeitos"), j + "F1 fazer: corpo original rodou");
        v.igual(1, gs(c, "chamadas"), j + "F1 fazer: gancho chamado uma vez");
        v.igual(Arrays.asList(4, "x"), Arrays.asList((Object[]) gs(c, "args")), j + "F1 fazer: argumentos certos");
        v.sim(gs(c, "receptor") == o, j + "F1 fazer: receptor e o objeto");

        c = carregar(j + "F1 fazer com retorno antecipado e receptor exato", alvo, util, cn -> Asm.ganchoNoInicio(cn, "fazer",
                "(ILjava/lang/String;)V", Gancho.de(G, "pularFazer"), null, true, ganchos));
        o = c.newInstance();
        gset(c, "decisao", true);
        c.getMethod("fazer", int.class, String.class).invoke(o, 4, "x");
        v.igual(0, campo(o, "efeitos"), j + "F1 fazer: gancho true = metodo nao roda");
        gset(c, "decisao", false);
        c.getMethod("fazer", int.class, String.class).invoke(o, 4, "x");
        v.igual(102, campo(o, "efeitos"), j + "F1 fazer: gancho false = metodo roda");

        c = carregar(j + "F1 pode (boolean)", alvo, util, cn -> Asm.ganchoNoInicio(cn, "pode", "(JD)Z",
                Gancho.de(G, "pularPode"), Gancho.de(G, "valorPode"), true, ganchos));
        o = c.newInstance();
        Method pode = c.getMethod("pode", long.class, double.class);
        gset(c, "decisao", true);
        v.igual(false, pode.invoke(o, 20L, 0.0), j + "F1 pode: valor do gancho");
        gset(c, "decisao", false);
        v.igual(true, pode.invoke(o, 20L, 0.0), j + "F1 pode: original");
        v.igual(Arrays.asList(20L, 0.0), Arrays.asList((Object[]) gs(c, "args")), j + "F1 pode: long e double passados");

        c = carregar(j + "F1 energia (double)", alvo, util, cn -> Asm.ganchoNoInicio(cn, "energia", "()D",
                Gancho.de(G, "pularEnergia"), Gancho.de(G, "valorEnergia"), true, ganchos));
        o = c.newInstance();
        gset(c, "decisao", true);
        v.igual(42.5, c.getMethod("energia").invoke(o), j + "F1 energia: valor do gancho");
        gset(c, "decisao", false);
        v.igual(3.0, c.getMethod("energia").invoke(o), j + "F1 energia: original");

        c = carregar(j + "F1 estatico (objeto)", alvo, util, cn -> Asm.ganchoNoInicio(cn, "estatico",
                "(Ljava/lang/String;DZC[B)Ljava/lang/Object;", Gancho.de(G, "pularEstatico"), Gancho.de(G, "valorEstatico"), true,
                ganchos));
        Method est = c.getMethod("estatico", String.class, double.class, boolean.class, char.class, byte[].class);
        gset(c, "decisao", true);
        v.igual("gancho", est.invoke(null, "s", 1.5, true, 'c', new byte[3]), j + "F1 estatico: valor do gancho");
        gset(c, "decisao", false);
        v.igual("sc3", est.invoke(null, "s", 1.5, true, 'c', new byte[3]), j + "F1 estatico: original");

        c = carregar(j + "F1 laco (quadro no inicio)", alvo, util, cn -> Asm.ganchoNoInicio(cn, "laco", "(I)I",
                Gancho.de(G, "pularLaco"), Gancho.de(G, "valorLaco"), true, ganchos));
        o = c.newInstance();
        gset(c, "decisao", false);
        v.igual(96, c.getMethod("laco", int.class).invoke(o, 117), j + "F1 laco: original");
        gset(c, "decisao", true);
        v.igual(-1, c.getMethod("laco", int.class).invoke(o, 117), j + "F1 laco: valor do gancho");

        c = carregar(j + "F1 semDesvio (sem quadros)", alvo, util, cn -> Asm.ganchoNoInicio(cn, "semDesvio", "(J)J",
                Gancho.de(G, "pularSemDesvio"), Gancho.de(G, "valorSemDesvio"), true, ganchos));
        o = c.newInstance();
        gset(c, "decisao", false);
        v.igual(10L, c.getMethod("semDesvio", long.class).invoke(o, 5L), j + "F1 semDesvio: original");
        gset(c, "decisao", true);
        v.igual(-2L, c.getMethod("semDesvio", long.class).invoke(o, 5L), j + "F1 semDesvio: valor do gancho");

        // controle negativo: sem o quadro que o utilitario acrescenta, a JVM recusa a classe (Java 7+)
        controleSemQuadro(java, alvo, util);

        // falha fechada do formato 1
        recusa(j + "F1 recusa: metodo inexistente", alvo, cn -> Asm.ganchoNoInicio(cn, "naoExiste", "()V",
                Gancho.de(G, "inicioFazer"), null, false, ganchos));
        recusa(j + "F1 recusa: gancho inexistente", alvo, cn -> Asm.ganchoNoInicio(cn, "fazer", "(ILjava/lang/String;)V",
                Gancho.de(G, "naoExiste"), null, false, ganchos));
        recusa(j + "F1 recusa: gancho valor com tipo errado", alvo, cn -> Asm.ganchoNoInicio(cn, "energia", "()D",
                Gancho.de(G, "pularEnergia"), Gancho.de(G, "valorEnergiaErrado"), true, ganchos));
        recusa(j + "F1 recusa: retorno antecipado sem gancho valor", alvo, cn -> Asm.ganchoNoInicio(cn, "energia", "()D",
                Gancho.de(G, "pularEnergia"), null, true, ganchos));
        recusa(j + "F1 recusa: construtor", alvo, cn -> Asm.ganchoNoInicio(cn, "<init>", "()V",
                Gancho.de(G, "pularEnergia"), null, false, ganchos));
        recusa(j + "F1 recusa: gancho inicio com retorno errado", alvo, cn -> Asm.ganchoNoInicio(cn, "energia", "()D",
                Gancho.de(G, "valorEnergia"), null, false, ganchos));

        // ------------------------------------------------ formato 2
        c = carregar(j + "F2 troca a 2a chamada de size", alvo, util, cn -> Asm.trocarChamada(cn, "usaLista",
                "(Ljava/util/List;)I", Chamada.de("java/util/List", "size", "()I"), 1, 2, Gancho.de(G, "trocaSize"), ganchos));
        o = c.newInstance();
        v.igual(5, c.getMethod("usaLista", List.class).invoke(o, new ArrayList<String>()), j + "F2 size: so a 2a trocada");

        c = carregar(j + "F2 troca chamada estatica", alvo, util, cn -> Asm.trocarChamada(cn, "usaEstatico", "(I)I",
                Chamada.de("amostra/Alvo$Util", "dobro", "(I)I"), 0, 1, Gancho.de(G, "trocaDobro"), ganchos));
        o = c.newInstance();
        v.igual(1004, c.getMethod("usaEstatico", int.class).invoke(o, 3), j + "F2 estatica");

        c = carregar(j + "F2 troca chamada a privado", alvo, util, cn -> Asm.trocarChamada(cn, "usaPrivado", "(I)I",
                Chamada.de(ALVO, "privado", "(I)I"), 0, 1, Gancho.de(G, "trocaPrivado"), ganchos));
        o = c.newInstance();
        v.igual(-50, c.getMethod("usaPrivado", int.class).invoke(o, 5), j + "F2 privado");

        recusa(j + "F2 recusa: total diferente do esperado", alvo, cn -> Asm.trocarChamada(cn, "usaLista",
                "(Ljava/util/List;)I", Chamada.de("java/util/List", "size", "()I"), 0, 3, Gancho.de(G, "trocaSize"), ganchos));
        recusa(j + "F2 recusa: chamada inexistente", alvo, cn -> Asm.trocarChamada(cn, "usaLista",
                "(Ljava/util/List;)I", Chamada.de("java/util/List", "isEmpty", "()Z"), 0, 1, Gancho.de(G, "trocaSize"), ganchos));
        recusa(j + "F2 recusa: chamada a super", alvo, cn -> Asm.trocarChamada(cn, "usaSuper", "()Ljava/lang/String;",
                Chamada.de("java/lang/Object", "toString", "()Ljava/lang/String;"), 0, 1, Gancho.de(G, "trocaSize"), ganchos));

        // ------------------------------------------------ formato 3
        c = carregar(j + "F3 envolve chamada estatica", alvo, util, cn -> Asm.envolverChamada(cn, "usaEstatico", "(I)I",
                Chamada.de("amostra/Alvo$Util", "dobro", "(I)I"), 0, 1, Gancho.de(G, "deveDobro"), Gancho.de(G, "semDobro"), ganchos));
        o = c.newInstance();
        gset(c, "decisao", false);
        v.igual(78, c.getMethod("usaEstatico", int.class).invoke(o, 3), j + "F3 estatica: sem chamar");
        gset(c, "decisao", true);
        v.igual(7, c.getMethod("usaEstatico", int.class).invoke(o, 3), j + "F3 estatica: chamando a original");

        c = carregar(j + "F3 envolve chamada de interface", alvo, util, cn -> Asm.envolverChamada(cn, "usaLista",
                "(Ljava/util/List;)I", Chamada.de("java/util/List", "size", "()I"), 0, 2, Gancho.de(G, "deveSize"),
                Gancho.de(G, "semSize"), ganchos));
        o = c.newInstance();
        gset(c, "decisao", false);
        v.igual(9001, c.getMethod("usaLista", List.class).invoke(o, new ArrayList<String>()), j + "F3 interface: sem chamar");
        gset(c, "decisao", true);
        v.igual(1, c.getMethod("usaLista", List.class).invoke(o, new ArrayList<String>()), j + "F3 interface: original");

        c = carregar(j + "F3 envolve chamada a privado", alvo, util, cn -> Asm.envolverChamada(cn, "usaPrivado", "(I)I",
                Chamada.de(ALVO, "privado", "(I)I"), 0, 1, Gancho.de(G, "devePrivado"), Gancho.de(G, "semPrivado"), ganchos));
        o = c.newInstance();
        gset(c, "decisao", false);
        v.igual(30, c.getMethod("usaPrivado", int.class).invoke(o, 5), j + "F3 privado: sem chamar");
        gset(c, "decisao", true);
        v.igual(120, c.getMethod("usaPrivado", int.class).invoke(o, 5), j + "F3 privado: original (privado alcancado pela ponte)");

        c = carregar(j + "F3 envolve chamada void", alvo, util, cn -> Asm.envolverChamada(cn, "usaVoid", "()V",
                Chamada.de("amostra/Alvo$Util", "marcar", "(Lamostra/Alvo;)V"), 0, 1, Gancho.de(G, "deveMarcar"), null, ganchos));
        o = c.newInstance();
        Class<?> u = c.getClassLoader().loadClass("amostra.Alvo$Util");
        Field marcas = u.getField("marcas");
        gset(c, "decisao", false);
        c.getMethod("usaVoid").invoke(o);
        v.igual(0, marcas.get(null), j + "F3 void: sem chamar");
        v.igual(1, campo(o, "efeitos"), j + "F3 void: resto do metodo rodou");
        gset(c, "decisao", true);
        c.getMethod("usaVoid").invoke(o);
        v.igual(1, marcas.get(null), j + "F3 void: chamando a original");

        recusa(j + "F3 recusa: falta gancho semChamar em chamada com valor", alvo, cn -> Asm.envolverChamada(cn, "usaEstatico",
                "(I)I", Chamada.de("amostra/Alvo$Util", "dobro", "(I)I"), 0, 1, Gancho.de(G, "deveDobro"), null, ganchos));
        recusa(j + "F3 recusa: chamada a super", alvo, cn -> Asm.envolverChamada(cn, "usaSuper", "()Ljava/lang/String;",
                Chamada.de("java/lang/Object", "toString", "()Ljava/lang/String;"), 0, 1, Gancho.de(G, "deveDobro"),
                Gancho.de(G, "semDobro"), ganchos));

        // ------------------------------------------------ varios formatos na mesma classe
        c = carregar(j + "F1+F2+F3 na mesma classe", alvo, util, cn -> {
            Asm.ganchoNoInicio(cn, "laco", "(I)I", Gancho.de(G, "pularLaco"), Gancho.de(G, "valorLaco"), true, ganchos);
            Asm.trocarChamada(cn, "usaPrivado", "(I)I", Chamada.de(ALVO, "privado", "(I)I"), 0, 1, Gancho.de(G, "trocaPrivado"), ganchos);
            Asm.envolverChamada(cn, "usaEstatico", "(I)I", Chamada.de("amostra/Alvo$Util", "dobro", "(I)I"), 0, 1,
                    Gancho.de(G, "deveDobro"), Gancho.de(G, "semDobro"), ganchos);
        });
        o = c.newInstance();
        gset(c, "decisao", false);
        v.igual(Arrays.<Object>asList(96, -50, 78), Arrays.asList(c.getMethod("laco", int.class).invoke(o, 117),
                c.getMethod("usaPrivado", int.class).invoke(o, 5), c.getMethod("usaEstatico", int.class).invoke(o, 3)),
                j + "tres formatos juntos");
    }

    /**
     * No server.jar o arquivo .class de um gancho tem os nomes do Minecraft reofuscados (ex.: Lmw;),
     * mas a classe do mod usa os nomes de tempo de execucao. Simula isso com um gancho gerado cujo
     * descritor no arquivo usa "Lzz;" e um remapeador que traduz zz para net/minecraft/Coisa.
     */
    private static void remapeador() throws Exception {
        String dono = "hoshiestar/teste/GanchoReofuscado";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, dono, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "deve", "(Lzz;I)Z", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        File pasta = Files.createTempDirectory("hoshie-remap").toFile();
        File arq = new File(pasta, dono + ".class");
        arq.getParentFile().mkdirs();
        FileOutputStream fo = new FileOutputStream(arq);
        try {
            fo.write(cw.toByteArray());
        } finally {
            fo.close();
        }
        URLClassLoader cl = new URLClassLoader(new URL[] {pasta.toURI().toURL()}, null);
        String runtime = "(Lnet/minecraft/Coisa;I)Z";

        ResolvedorDeGanchos semTraducao = new ResolvedorDeGanchos(cl);
        v.sim(semTraducao.descritorDeclarado(dono, "deve", runtime) == null,
                "remapeador: sem traducao, o descritor de tempo de execucao NAO casa com o do arquivo");

        ResolvedorDeGanchos comTraducao = new ResolvedorDeGanchos(cl, new ResolvedorDeGanchos.Remapeador() {
            @Override
            public String descritor(String d) {
                return d.replace("Lzz;", "Lnet/minecraft/Coisa;");
            }
        });
        v.igual(runtime, comTraducao.descritorDeclarado(dono, "deve", runtime), "remapeador: com traducao, casa");
        v.sim(comTraducao.descritorDeclarado(dono, "deve", "(Lzz;I)Z") == null,
                "remapeador: com traducao, o descritor cru do arquivo nao vale mais");

        ResolvedorDeGanchos quebrado = new ResolvedorDeGanchos(cl, new ResolvedorDeGanchos.Remapeador() {
            @Override
            public String descritor(String d) {
                throw new IllegalStateException("teste");
            }
        });
        v.igual("(Lzz;I)Z", quebrado.descritorDeclarado(dono, "deve", runtime, "(Lzz;I)Z"),
                "remapeador: se a traducao falha, usa o descritor do arquivo (sem excecao)");
    }

    /**
     * Prova de que o teste de carga pega quadro de pilha faltando: aplica o formato 1 com retorno
     * antecipado e TIRA o quadro que o utilitario acrescentou. Em classe Java 7 ou 8 a JVM tem de
     * recusar (VerifyError). Em Java 6 nada e afirmado (a JVM pode cair no verificador antigo).
     */
    private static void controleSemQuadro(int java, byte[] alvo, byte[] util) throws Exception {
        ClassNode cn = Asm.ler(alvo);
        Asm.ganchoNoInicio(cn, "energia", "()D", Gancho.de(G, "pularEnergia"), Gancho.de(G, "valorEnergia"), true, ganchos);
        boolean tirou = false;
        for (MethodNode m : cn.methods) {
            if (m.name.equals("energia")) {
                for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                    if (n instanceof FrameNode) {
                        m.instructions.remove(n);
                        tirou = true;
                        break;
                    }
                }
            }
        }
        if (java < 7) {
            return;
        }
        v.sim(tirou, "Java " + java + ": controle: havia um quadro acrescentado para tirar");
        Map<String, byte[]> mapa = new HashMap<String, byte[]>();
        mapa.put("amostra.Alvo", Asm.escrever(cn));
        mapa.put("amostra.Alvo$Util", util);
        Verifica.Carregador cl = new Verifica.Carregador(TesteAsm.class.getClassLoader(), mapa);
        Throwable erro = null;
        try {
            Class.forName("amostra.Alvo", true, cl);
        } catch (VerifyError e) {
            erro = e;
        }
        v.sim(erro != null, "Java " + java + ": controle: sem o quadro a JVM recusa a classe"
                + (erro != null ? " (" + erro.getClass().getSimpleName() + ")" : " (NAO recusou: o teste nao pegaria o erro)"));
    }

    /** Transforma, verifica com o ASM, carrega num class loader novo e devolve a classe (ou null). */
    private static Class<?> carregar(String o, byte[] alvo, byte[] util, Mudanca m) throws Exception {
        ClassNode cn = Asm.ler(alvo);
        m.aplicar(cn);
        byte[] novos = Asm.escrever(cn);
        Map<String, byte[]> mapa = new HashMap<String, byte[]>();
        mapa.put("amostra.Alvo", novos);
        mapa.put("amostra.Alvo$Util", util);
        Verifica.Carregador cl = new Verifica.Carregador(TesteAsm.class.getClassLoader(), mapa);
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(novos), cl, false, new PrintWriter(sw));
        v.sim(sw.toString().isEmpty(), o + ": CheckClassAdapter sem erros" + (sw.toString().isEmpty() ? "" : "\n" + sw));
        Class<?> c = Class.forName("amostra.Alvo", true, cl); // a JVM verifica o bytecode aqui
        v.sim(c.getClassLoader() == cl, o + ": carregada e verificada pela JVM");
        Class<?> g = cl.loadClass("hoshiestar.teste.ganchos.GanchosTeste");
        g.getMethod("zerar").invoke(null);
        return c;
    }

    private static void recusa(String o, byte[] alvo, Mudanca m) {
        ClassNode cn = Asm.ler(alvo);
        byte[] antes = Asm.escrever(cn);
        try {
            m.aplicar(cn);
            v.sim(false, o + " (nao recusou)");
        } catch (PadraoNaoEncontrado e) {
            byte[] depois = Asm.escrever(cn);
            v.sim(Arrays.equals(antes, depois), o + ": recusou sem alterar a arvore (" + e.getMessage() + ")");
        } catch (Exception e) {
            v.erro(o + ": excecao errada", e);
        }
    }

    private static Object campo(Object o, String nome) throws Exception {
        Field f = o.getClass().getField(nome);
        return f.get(o);
    }

    private static Object gs(Class<?> alvo, String nome) throws Exception {
        return alvo.getClassLoader().loadClass("hoshiestar.teste.ganchos.GanchosTeste").getField(nome).get(null);
    }

    private static void gset(Class<?> alvo, String nome, Object valor) throws Exception {
        alvo.getClassLoader().loadClass("hoshiestar.teste.ganchos.GanchosTeste").getField(nome).set(null, valor);
    }
}
