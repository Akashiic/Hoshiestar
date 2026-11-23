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

        // ------------------------------------------------ formato 1 protegido
        formato1Protegido(j, alvo, util);

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

        // ------------------------------------------------ formato 3 protegido
        formato3Protegido(j, alvo, util);

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

    // ------------------------------------------------------------------ formato 1 protegido

    /**
     * Formato 1 protegido: sem falha se comporta como o formato 1; com falha do gancho (lancou, a classe dele nao
     * existe em tempo de execucao, o metodo dele nao existe) o metodo segue pelo corpo original e chama o aoFalhar;
     * o corpo original fica fora do try. Controle: o formato 1 comum, com o mesmo gancho que lanca (ou que nao liga),
     * deixa o erro sair do metodo.
     */
    private static void formato1Protegido(String j, byte[] alvo, byte[] util) throws Exception {
        final Gancho falhou = Gancho.de(G, "falhou");
        j = j + "F1P ";

        // sem falha, com retorno antecipado (instancia, com laco: quadro no inicio)
        Class<?> c = carregar(j + "laco", alvo, util, cn -> Asm.ganchoNoInicioProtegido(cn, "laco", "(I)I",
                Gancho.de(G, "pularLaco"), Gancho.de(G, "valorLaco"), true, falhou, ganchos));
        Object o = c.newInstance();
        Method laco = c.getMethod("laco", int.class);
        gset(c, "decisao", false);
        v.igual(96, laco.invoke(o, 117), j + "sem falha: gancho false = corpo original");
        gset(c, "decisao", true);
        v.igual(-1, laco.invoke(o, 117), j + "sem falha: gancho true = valor do gancho (o corpo nao roda)");
        v.igual(Arrays.asList(117), Arrays.asList((Object[]) gs(c, "args")), j + "sem falha: argumentos certos");
        v.sim(gs(c, "receptor") == o, j + "sem falha: receptor e o objeto");
        v.igual(0, gs(c, "falhas"), j + "sem falha: aoFalhar nunca chamado");

        // forma: um try/catch de Throwable a mais; ordem das chamadas inicio, valor, aoFalhar; o corpo original fora do try
        {
            ClassNode cn = Asm.ler(alvo);
            MethodNode antes = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("laco")) {
                    antes = m;
                }
            }
            int blocosAntes = antes.tryCatchBlocks.size();
            Asm.ganchoNoInicioProtegido(cn, "laco", "(I)I", Gancho.de(G, "pularLaco"), Gancho.de(G, "valorLaco"), true, falhou, ganchos);
            MethodNode m = null;
            for (MethodNode x : cn.methods) {
                if (x.name.equals("laco")) {
                    m = x;
                }
            }
            v.igual(blocosAntes + 1, m.tryCatchBlocks.size(), j + "forma: um unico try/catch a mais");
            org.objectweb.asm.tree.TryCatchBlockNode tc = m.tryCatchBlocks.get(m.tryCatchBlocks.size() - 1);
            v.igual("java/lang/Throwable", tc.type, j + "forma: o try/catch e de Throwable");
            List<String> chamadas = new ArrayList<String>();
            boolean dentro = false;
            boolean originalDentro = false;
            boolean originalFora = false;
            for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n == tc.start) {
                    dentro = true;
                } else if (n == tc.end) {
                    dentro = false;
                }
                if (n instanceof org.objectweb.asm.tree.MethodInsnNode) {
                    chamadas.add(((org.objectweb.asm.tree.MethodInsnNode) n).name + (dentro ? "*" : ""));
                }
                if (dentro && n.getOpcode() == Opcodes.IINC) {
                    originalDentro = true; // o IINC e do laco original ("n -= 7")
                }
                if (!dentro && n.getOpcode() == Opcodes.IINC) {
                    originalFora = true;
                }
            }
            v.igual(Arrays.asList("pularLaco*", "valorLaco*", "falhou"), chamadas, j + "forma: inicio e valor dentro do try (*), aoFalhar fora, nada mais chamado");
            v.sim(originalFora && !originalDentro, j + "forma: (controle: o IINC existe) nenhuma instrucao do corpo original (o IINC do laco) esta dentro do try");
        }

        // o gancho de inicio LANCA: o corpo original roda, e a cada chamada
        c = carregar(j + "inicio lanca", alvo, util, cn -> Asm.ganchoNoInicioProtegido(cn, "laco", "(I)I",
                Gancho.de(G, "pularLacoQuebra"), Gancho.de(G, "valorLaco"), true, falhou, ganchos));
        o = c.newInstance();
        laco = c.getMethod("laco", int.class);
        v.igual(96, laco.invoke(o, 117), j + "gancho lanca: o corpo original roda");
        v.igual(1, gs(c, "falhas"), j + "gancho lanca: aoFalhar chamado uma vez");
        v.sim(gs(c, "ultimaFalha") instanceof IllegalStateException, j + "gancho lanca: aoFalhar recebeu a excecao do gancho");
        v.igual(96, laco.invoke(o, 117), j + "gancho lanca: a segunda chamada tambem segue pelo original");
        v.igual(2, gs(c, "falhas"), j + "gancho lanca: aoFalhar a cada falha");

        // o gancho "valor" lanca (depois de o de inicio mandar sair): original
        c = carregar(j + "valor lanca", alvo, util, cn -> Asm.ganchoNoInicioProtegido(cn, "laco", "(I)I",
                Gancho.de(G, "pularLacoSai"), Gancho.de(G, "valorLacoQuebra"), true, falhou, ganchos));
        o = c.newInstance();
        v.igual(96, c.getMethod("laco", int.class).invoke(o, 117), j + "valor lanca: o corpo original roda");
        v.igual(1, gs(c, "falhas"), j + "valor lanca: aoFalhar chamado");

        // metodo estatico (o caso do shiftArea) e metodo sem desvio (sem quadros em Java 6)
        c = carregar(j + "estatico", alvo, util, cn -> Asm.ganchoNoInicioProtegido(cn, "estatico",
                "(Ljava/lang/String;DZC[B)Ljava/lang/Object;", Gancho.de(G, "pularEstaticoQuebra"), Gancho.de(G, "valorEstatico"), true, falhou, ganchos));
        Method est = c.getMethod("estatico", String.class, double.class, boolean.class, char.class, byte[].class);
        v.igual("sc3", est.invoke(null, "s", 1.5, true, 'c', new byte[3]), j + "estatico, gancho lanca: original");
        v.igual(1, gs(c, "falhas"), j + "estatico, gancho lanca: aoFalhar chamado");
        c = carregar(j + "estatico sem falha", alvo, util, cn -> Asm.ganchoNoInicioProtegido(cn, "estatico",
                "(Ljava/lang/String;DZC[B)Ljava/lang/Object;", Gancho.de(G, "pularEstatico"), Gancho.de(G, "valorEstatico"), true, falhou, ganchos));
        est = c.getMethod("estatico", String.class, double.class, boolean.class, char.class, byte[].class);
        gset(c, "decisao", true);
        v.igual("gancho", est.invoke(null, "s", 1.5, true, 'c', new byte[3]), j + "estatico sem falha: valor do gancho");
        gset(c, "decisao", false);
        v.igual("sc3", est.invoke(null, "s", 1.5, true, 'c', new byte[3]), j + "estatico sem falha: original");
        v.igual(0, gs(c, "falhas"), j + "estatico sem falha: aoFalhar nunca chamado");

        c = carregar(j + "semDesvio", alvo, util, cn -> Asm.ganchoNoInicioProtegido(cn, "semDesvio", "(J)J",
                Gancho.de(G, "pularSemDesvioQuebra"), Gancho.de(G, "valorSemDesvio"), true, falhou, ganchos));
        o = c.newInstance();
        v.igual(10L, c.getMethod("semDesvio", long.class).invoke(o, 5L), j + "semDesvio, gancho lanca: original (long nos parametros)");
        v.igual(1, gs(c, "falhas"), j + "semDesvio, gancho lanca: aoFalhar chamado");
        c = carregar(j + "semDesvio sem falha", alvo, util, cn -> Asm.ganchoNoInicioProtegido(cn, "semDesvio", "(J)J",
                Gancho.de(G, "pularSemDesvio"), Gancho.de(G, "valorSemDesvio"), true, falhou, ganchos));
        o = c.newInstance();
        gset(c, "decisao", true);
        v.igual(-2L, c.getMethod("semDesvio", long.class).invoke(o, 5L), j + "semDesvio sem falha: valor do gancho");
        gset(c, "decisao", false);
        v.igual(10L, c.getMethod("semDesvio", long.class).invoke(o, 5L), j + "semDesvio sem falha: original");

        // metodo void, sem retorno antecipado: gancho que lanca nao impede o corpo
        c = carregar(j + "void sem retorno antecipado", alvo, util, cn -> Asm.ganchoNoInicioProtegido(cn, "fazer",
                "(ILjava/lang/String;)V", Gancho.de(G, "inicioFazer"), null, false, falhou, ganchos));
        o = c.newInstance();
        c.getMethod("fazer", int.class, String.class).invoke(o, 4, "x");
        v.igual(102, campo(o, "efeitos"), j + "void sem retorno antecipado, sem falha: corpo original rodou");
        v.igual(1, gs(c, "chamadas"), j + "void sem retorno antecipado, sem falha: gancho chamado uma vez");
        v.igual(0, gs(c, "falhas"), j + "void sem retorno antecipado, sem falha: aoFalhar nunca chamado");
        c = carregar(j + "void sem retorno antecipado, lanca", alvo, util, cn -> Asm.ganchoNoInicioProtegido(cn, "fazer",
                "(ILjava/lang/String;)V", Gancho.de(G, "inicioFazerQuebra"), null, false, falhou, ganchos));
        o = c.newInstance();
        c.getMethod("fazer", int.class, String.class).invoke(o, 4, "x");
        v.igual(102, campo(o, "efeitos"), j + "void sem retorno antecipado, gancho lanca: corpo original rodou assim mesmo");
        v.igual(1, gs(c, "falhas"), j + "void sem retorno antecipado, gancho lanca: aoFalhar chamado");

        // gancho que NAO LIGA em tempo de execucao: existe no arquivo (o resolvedor ve), nao existe no class loader
        File pasta = Files.createTempDirectory("hoshie-fantasma1").toFile();
        final String d1 = "(Ljava/lang/Object;I)Z";
        final String d2 = "(Ljava/lang/Object;I)I";
        String ausente = "hoshiestar/teste/Fantasma1Ausente";
        String semPular = "hoshiestar/teste/Fantasma1SemPular";
        String semValor = "hoshiestar/teste/Fantasma1SemValor";
        for (String d : new String[] {ausente, semPular, semValor}) {
            File arq = new File(pasta, d + ".class");
            arq.getParentFile().mkdirs();
            FileOutputStream fo = new FileOutputStream(arq);
            try {
                fo.write(fantasma1(d, true, true));
            } finally {
                fo.close();
            }
        }
        ResolvedorDeGanchos vendoOsFantasmas = new ResolvedorDeGanchos(new URLClassLoader(new URL[] {pasta.toURI().toURL()}, TesteAsm.class.getClassLoader()));
        Map<String, byte[]> extras = new HashMap<String, byte[]>();
        extras.put(semPular.replace('/', '.'), fantasma1(semPular, false, true));   // em tempo de execucao falta o 'pular'
        extras.put(semValor.replace('/', '.'), fantasma1(semValor, true, false));   // em tempo de execucao falta o 'valor' (o 'pular' manda sair)
        String[][] casos = {{ausente, "a classe do gancho nao existe", "NoClassDefFoundError"},
            {semPular, "o metodo 'pular' nao existe", "NoSuchMethodError"}, {semValor, "o metodo 'valor' nao existe", "NoSuchMethodError"}};
        for (String[] k : casos) {
            final String dono = k[0];
            c = carregar(j + "nao liga: " + k[1], alvo, util, cn -> Asm.ganchoNoInicioProtegido(cn, "laco", "(I)I",
                    Gancho.de(dono, "pular"), Gancho.de(dono, "valor"), true, falhou, vendoOsFantasmas), extras);
            o = c.newInstance();
            v.igual(96, c.getMethod("laco", int.class).invoke(o, 117), j + "nao liga (" + k[1] + "): segue pelo corpo original");
            Object f = gs(c, "ultimaFalha");
            v.sim(gs(c, "falhas").equals(1) && f != null && f.getClass().getSimpleName().equals(k[2]),
                    j + "nao liga (" + k[1] + "): aoFalhar recebeu " + k[2] + " (" + (f == null ? "nada" : f.getClass().getSimpleName()) + ")");
            v.igual(96, c.getMethod("laco", int.class).invoke(o, 117), j + "nao liga (" + k[1] + "): segunda chamada tambem segue pelo original");
            v.igual(2, gs(c, "falhas"), j + "nao liga (" + k[1] + "): aoFalhar a cada falha");
            // controle negativo: o formato 1 comum, com o mesmo gancho, deixa o erro sair do metodo
            Class<?> cc = carregar(j + "controle sem protecao: " + k[1], alvo, util, cn -> Asm.ganchoNoInicio(cn, "laco", "(I)I",
                    Gancho.de(dono, "pular"), Gancho.de(dono, "valor"), true, vendoOsFantasmas), extras);
            Throwable saiu = null;
            try {
                cc.getMethod("laco", int.class).invoke(cc.newInstance(), 117);
            } catch (java.lang.reflect.InvocationTargetException e) {
                saiu = e.getCause();
            }
            v.sim(saiu != null && saiu.getClass().getSimpleName().equals(k[2]),
                    j + "controle (" + k[1] + "): sem a protecao o erro de ligacao sai do metodo do mod (" + saiu + ")");
        }

        // controle negativo: o formato 1 comum, com o gancho que lanca, deixa a excecao sair
        c = carregar(j + "controle: formato 1 comum", alvo, util, cn -> Asm.ganchoNoInicio(cn, "laco", "(I)I",
                Gancho.de(G, "pularLacoQuebra"), Gancho.de(G, "valorLaco"), true, ganchos));
        Throwable saiu = null;
        try {
            c.getMethod("laco", int.class).invoke(c.newInstance(), 117);
        } catch (java.lang.reflect.InvocationTargetException e) {
            saiu = e.getCause();
        }
        v.sim(saiu instanceof IllegalStateException, j + "controle: sem a protecao a excecao do gancho sai do metodo do mod (" + saiu + ")");

        // recusas (a arvore fica intacta)
        recusa(j + "recusa: sem aoFalhar", alvo, cn -> Asm.ganchoNoInicioProtegido(cn, "laco", "(I)I",
                Gancho.de(G, "pularLaco"), Gancho.de(G, "valorLaco"), true, null, ganchos));
        recusa(j + "recusa: aoFalhar inexistente", alvo, cn -> Asm.ganchoNoInicioProtegido(cn, "laco", "(I)I",
                Gancho.de(G, "pularLaco"), Gancho.de(G, "valorLaco"), true, Gancho.de(G, "naoExiste"), ganchos));
        recusa(j + "recusa: aoFalhar com descritor errado", alvo, cn -> Asm.ganchoNoInicioProtegido(cn, "laco", "(I)I",
                Gancho.de(G, "pularLaco"), Gancho.de(G, "valorLaco"), true, Gancho.de(G, "falhouErrado"), ganchos));
        recusa(j + "recusa: gancho de inicio ausente", alvo, cn -> Asm.ganchoNoInicioProtegido(cn, "laco", "(I)I",
                Gancho.de(G, "naoExiste"), Gancho.de(G, "valorLaco"), true, falhou, ganchos));
        recusa(j + "recusa: gancho valor ausente", alvo, cn -> Asm.ganchoNoInicioProtegido(cn, "laco", "(I)I",
                Gancho.de(G, "pularLaco"), Gancho.de(G, "naoExiste"), true, falhou, ganchos));
        recusa(j + "recusa: retorno antecipado sem gancho valor", alvo, cn -> Asm.ganchoNoInicioProtegido(cn, "laco", "(I)I",
                Gancho.de(G, "pularLaco"), null, true, falhou, ganchos));
        recusa(j + "recusa: construtor", alvo, cn -> Asm.ganchoNoInicioProtegido(cn, "<init>", "()V",
                Gancho.de(G, "pularEnergia"), null, false, falhou, ganchos));
    }

    /** Classe de gancho gerada para o formato 1: "pular(Object,int)Z" devolve true (manda sair) e "valor(Object,int)I" devolve 55; cada metodo e opcional. */
    private static byte[] fantasma1(String dono, boolean comPular, boolean comValor) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, dono, null, "java/lang/Object", null);
        if (comPular) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pular", "(Ljava/lang/Object;I)Z", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        if (comValor) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "valor", "(Ljava/lang/Object;I)I", null, null);
            mv.visitCode();
            mv.visitIntInsn(Opcodes.BIPUSH, 55);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------ formato 3 protegido

    /**
     * Formato 3 protegido: sem falha se comporta como o formato 3; com falha do gancho (lancou, a classe dele nao
     * existe em tempo de execucao, o metodo dele nao existe) segue pela chamada original e chama o aoFalhar; a chamada
     * original fica fora do try. Controle: o formato 3 comum, com o mesmo gancho que lanca, deixa o erro sair.
     */
    private static void formato3Protegido(String j, byte[] alvo, byte[] util) throws Exception {
        final Chamada dobro = Chamada.de("amostra/Alvo$Util", "dobro", "(I)I");
        final Gancho falhou = Gancho.de(G, "falhou");
        j = j + "F3P ";

        // sem falha: igual ao formato 3
        Class<?> c = carregar(j + "estatica", alvo, util, cn -> Asm.envolverChamadaProtegida(cn, "usaEstatico", "(I)I", dobro, 0, 1,
                Gancho.de(G, "deveDobro"), Gancho.de(G, "semDobro"), falhou, ganchos));
        Object o = c.newInstance();
        Method usa = c.getMethod("usaEstatico", int.class);
        gset(c, "decisao", false);
        v.igual(78, usa.invoke(o, 3), j + "sem falha: sem chamar (gancho 'sem' deu o valor)");
        gset(c, "decisao", true);
        v.igual(7, usa.invoke(o, 3), j + "sem falha: chamando a original");
        v.igual(0, gs(c, "falhas"), j + "sem falha: aoFalhar nunca chamado");

        // forma da ponte: um try/catch de Throwable; ordem das chamadas deve, sem, aoFalhar, original
        {
            ClassNode cn = Asm.ler(alvo);
            Asm.envolverChamadaProtegida(cn, "usaEstatico", "(I)I", dobro, 0, 1, Gancho.de(G, "deveDobro"), Gancho.de(G, "semDobro"), falhou, ganchos);
            MethodNode ponte = null;
            for (MethodNode m : cn.methods) {
                if (m.name.startsWith("hoshiestar$envolve$")) {
                    ponte = m;
                }
            }
            v.sim(ponte != null && ponte.tryCatchBlocks.size() == 1 && "java/lang/Throwable".equals(ponte.tryCatchBlocks.get(0).type),
                    j + "forma: a ponte tem um unico try/catch de Throwable");
            List<String> chamadas = new ArrayList<String>();
            for (AbstractInsnNode n = ponte.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof org.objectweb.asm.tree.MethodInsnNode) {
                    org.objectweb.asm.tree.MethodInsnNode mi = (org.objectweb.asm.tree.MethodInsnNode) n;
                    chamadas.add(mi.name);
                }
            }
            v.igual(Arrays.asList("deveDobro", "semDobro", "falhou", "dobro"), chamadas, j + "forma: ordem das chamadas deve, sem, aoFalhar, original (uma de cada)");
            // a chamada original esta FORA do try: o indice dela vem depois do fim do bloco
            org.objectweb.asm.tree.TryCatchBlockNode tc = ponte.tryCatchBlocks.get(0);
            int fim = ponte.instructions.indexOf(tc.end);
            int orig = -1;
            for (AbstractInsnNode n = ponte.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof org.objectweb.asm.tree.MethodInsnNode && ((org.objectweb.asm.tree.MethodInsnNode) n).name.equals("dobro")) {
                    orig = ponte.instructions.indexOf(n);
                }
            }
            v.sim(orig > fim, j + "forma: a chamada original fica fora do bloco try");
        }

        // o gancho 'deve' lanca
        c = carregar(j + "deve lanca", alvo, util, cn -> Asm.envolverChamadaProtegida(cn, "usaEstatico", "(I)I", dobro, 0, 1,
                Gancho.de(G, "deveQuebra"), Gancho.de(G, "semDobro"), falhou, ganchos));
        o = c.newInstance();
        v.igual(7, c.getMethod("usaEstatico", int.class).invoke(o, 3), j + "'deve' lanca: segue pela chamada original (7, nao 78)");
        v.igual(1, gs(c, "falhas"), j + "'deve' lanca: aoFalhar chamado uma vez");
        v.sim(gs(c, "ultimaFalha") instanceof IllegalStateException && "deveQuebra".equals(((Throwable) gs(c, "ultimaFalha")).getMessage()),
                j + "'deve' lanca: aoFalhar recebeu a excecao do gancho");

        // o gancho 'sem' lanca (depois de o 'deve' mandar pular)
        c = carregar(j + "sem lanca", alvo, util, cn -> Asm.envolverChamadaProtegida(cn, "usaEstatico", "(I)I", dobro, 0, 1,
                Gancho.de(G, "devePula"), Gancho.de(G, "semQuebra"), falhou, ganchos));
        o = c.newInstance();
        v.igual(7, c.getMethod("usaEstatico", int.class).invoke(o, 3), j + "'sem' lanca: segue pela chamada original");
        v.igual(1, gs(c, "falhas"), j + "'sem' lanca: aoFalhar chamado uma vez");

        // chamada void
        c = carregar(j + "void, deve lanca", alvo, util, cn -> Asm.envolverChamadaProtegida(cn, "usaVoid", "()V",
                Chamada.de("amostra/Alvo$Util", "marcar", "(Lamostra/Alvo;)V"), 0, 1, Gancho.de(G, "deveQuebraVoid"), null, falhou, ganchos));
        o = c.newInstance();
        Field marcas = c.getClassLoader().loadClass("amostra.Alvo$Util").getField("marcas");
        c.getMethod("usaVoid").invoke(o);
        v.igual(1, marcas.get(null), j + "void, 'deve' lanca: a chamada original rodou");
        v.igual(1, campo(o, "efeitos"), j + "void, 'deve' lanca: resto do metodo rodou");
        v.igual(1, gs(c, "falhas"), j + "void, 'deve' lanca: aoFalhar chamado");
        c = carregar(j + "void, pula", alvo, util, cn -> Asm.envolverChamadaProtegida(cn, "usaVoid", "()V",
                Chamada.de("amostra/Alvo$Util", "marcar", "(Lamostra/Alvo;)V"), 0, 1, Gancho.de(G, "devePulaVoid"), null, falhou, ganchos));
        o = c.newInstance();
        marcas = c.getClassLoader().loadClass("amostra.Alvo$Util").getField("marcas");
        c.getMethod("usaVoid").invoke(o);
        v.igual(0, marcas.get(null), j + "void, sem falha: pulou a chamada");
        v.igual(0, gs(c, "falhas"), j + "void, sem falha: aoFalhar nao chamado");

        // com receptor: interface (2 chamadas, a primeira) e privado
        c = carregar(j + "interface", alvo, util, cn -> Asm.envolverChamadaProtegida(cn, "usaLista", "(Ljava/util/List;)I",
                Chamada.de("java/util/List", "size", "()I"), 0, 2, Gancho.de(G, "deveSize"), Gancho.de(G, "semSize"), falhou, ganchos));
        o = c.newInstance();
        gset(c, "decisao", false);
        v.igual(9001, c.getMethod("usaLista", List.class).invoke(o, new ArrayList<String>()), j + "interface: sem chamar (9 * 1000 + 1)");
        gset(c, "decisao", true);
        v.igual(1, c.getMethod("usaLista", List.class).invoke(o, new ArrayList<String>()), j + "interface: original");
        c = carregar(j + "privado", alvo, util, cn -> Asm.envolverChamadaProtegida(cn, "usaPrivado", "(I)I", Chamada.de(ALVO, "privado", "(I)I"), 0, 1,
                Gancho.de(G, "devePrivado"), Gancho.de(G, "semPrivado"), falhou, ganchos));
        o = c.newInstance();
        gset(c, "decisao", true);
        v.igual(120, c.getMethod("usaPrivado", int.class).invoke(o, 5), j + "privado: original (a ponte alcanca o privado)");
        gset(c, "decisao", false);
        v.igual(30, c.getMethod("usaPrivado", int.class).invoke(o, 5), j + "privado: sem chamar");

        // gancho que NAO LIGA em tempo de execucao: existe no arquivo (o resolvedor ve), nao existe no class loader
        File pasta = Files.createTempDirectory("hoshie-fantasma").toFile();
        String ausente = "hoshiestar/teste/FantasmaAusente";
        String semDeve = "hoshiestar/teste/FantasmaSemDeve";
        String semSem = "hoshiestar/teste/FantasmaSemSem";
        for (String d : new String[] {ausente, semDeve, semSem}) {
            File arq = new File(pasta, d + ".class");
            arq.getParentFile().mkdirs();
            FileOutputStream fo = new FileOutputStream(arq);
            try {
                fo.write(fantasma(d, true, true));
            } finally {
                fo.close();
            }
        }
        ResolvedorDeGanchos vendoOsFantasmas = new ResolvedorDeGanchos(new URLClassLoader(new URL[] {pasta.toURI().toURL()}, TesteAsm.class.getClassLoader()));
        Map<String, byte[]> extras = new HashMap<String, byte[]>();
        extras.put(semDeve.replace('/', '.'), fantasma(semDeve, false, true));   // em tempo de execucao falta o 'deve'
        extras.put(semSem.replace('/', '.'), fantasma(semSem, true, false));     // em tempo de execucao falta o 'sem'
        String[][] casos = {{ausente, "a classe do gancho nao existe", "NoClassDefFoundError"},
            {semDeve, "o metodo 'deve' nao existe", "NoSuchMethodError"}, {semSem, "o metodo 'sem' nao existe", "NoSuchMethodError"}};
        for (String[] k : casos) {
            final String dono = k[0];
            c = carregar(j + "nao liga: " + k[1], alvo, util, cn -> Asm.envolverChamadaProtegida(cn, "usaEstatico", "(I)I", dobro, 0, 1,
                    Gancho.de(dono, "deve"), Gancho.de(dono, "sem"), falhou, vendoOsFantasmas), extras);
            o = c.newInstance();
            v.igual(7, c.getMethod("usaEstatico", int.class).invoke(o, 3), j + "nao liga (" + k[1] + "): segue pela chamada original");
            Object f = gs(c, "ultimaFalha");
            v.sim(gs(c, "falhas").equals(1) && f != null && f.getClass().getSimpleName().equals(k[2]),
                    j + "nao liga (" + k[1] + "): aoFalhar recebeu " + k[2] + " (" + (f == null ? "nada" : f.getClass().getSimpleName()) + ")");
            // continua funcionando a cada chamada seguinte (a ponte nao fica quebrada)
            v.igual(7, c.getMethod("usaEstatico", int.class).invoke(o, 3), j + "nao liga (" + k[1] + "): segunda chamada tambem segue pela original");
            v.igual(2, gs(c, "falhas"), j + "nao liga (" + k[1] + "): aoFalhar chamado a cada falha");
        }

        // controle negativo: o formato 3 comum, com o mesmo gancho que lanca, deixa o erro sair
        c = carregar(j + "controle: formato 3 comum", alvo, util, cn -> Asm.envolverChamada(cn, "usaEstatico", "(I)I", dobro, 0, 1,
                Gancho.de(G, "deveQuebra"), Gancho.de(G, "semDobro"), ganchos));
        o = c.newInstance();
        Throwable saiu = null;
        try {
            c.getMethod("usaEstatico", int.class).invoke(o, 3);
        } catch (java.lang.reflect.InvocationTargetException e) {
            saiu = e.getCause();
        }
        v.sim(saiu instanceof IllegalStateException, j + "controle: sem a protecao o erro do gancho sai do metodo do mod (" + saiu + ")");

        // recusas
        recusa(j + "recusa: sem aoFalhar", alvo, cn -> Asm.envolverChamadaProtegida(cn, "usaEstatico", "(I)I", dobro, 0, 1,
                Gancho.de(G, "deveDobro"), Gancho.de(G, "semDobro"), null, ganchos));
        recusa(j + "recusa: aoFalhar inexistente", alvo, cn -> Asm.envolverChamadaProtegida(cn, "usaEstatico", "(I)I", dobro, 0, 1,
                Gancho.de(G, "deveDobro"), Gancho.de(G, "semDobro"), Gancho.de(G, "naoExiste"), ganchos));
        recusa(j + "recusa: aoFalhar com descritor errado", alvo, cn -> Asm.envolverChamadaProtegida(cn, "usaEstatico", "(I)I", dobro, 0, 1,
                Gancho.de(G, "deveDobro"), Gancho.de(G, "semDobro"), Gancho.de(G, "falhouErrado"), ganchos));
        recusa(j + "recusa: total diferente do esperado", alvo, cn -> Asm.envolverChamadaProtegida(cn, "usaLista", "(Ljava/util/List;)I",
                Chamada.de("java/util/List", "size", "()I"), 0, 3, Gancho.de(G, "deveSize"), Gancho.de(G, "semSize"), falhou, ganchos));
    }

    /** Classe de gancho gerada: "deve(I)Z" devolve false (manda pular) e "sem(I)I" devolve 55; cada metodo e opcional. */
    private static byte[] fantasma(String dono, boolean comDeve, boolean comSem) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, dono, null, "java/lang/Object", null);
        if (comDeve) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "deve", "(I)Z", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        if (comSem) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sem", "(I)I", null, null);
            mv.visitCode();
            mv.visitIntInsn(Opcodes.BIPUSH, 55);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
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
        return carregar(o, alvo, util, m, new HashMap<String, byte[]>());
    }

    /** Com classes extras definidas no class loader de teste (ex.: ganchos "fantasma", que existem no arquivo mas nao em tempo de execucao). */
    private static Class<?> carregar(String o, byte[] alvo, byte[] util, Mudanca m, Map<String, byte[]> extras) throws Exception {
        ClassNode cn = Asm.ler(alvo);
        m.aplicar(cn);
        byte[] novos = Asm.escrever(cn);
        Map<String, byte[]> mapa = new HashMap<String, byte[]>(extras);
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
