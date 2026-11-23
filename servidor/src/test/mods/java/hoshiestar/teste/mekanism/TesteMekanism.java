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

import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import hoshiestar.mekanism.Mekanism;
import hoshiestar.teste.Verifica;
import hoshiestar.teste.mods.CarregadorMods;
import hoshiestar.teste.mods.RemapeadorSrg;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Teste isolado da correcao do Mekanism (recurso 1.C), com as classes REAIS:
 * <ul>
 * <li>do Mekanism: o jar do pacote, pelo classpath (nunca copiado para o repositorio);</li>
 * <li>do Hoshiestar: as do server.jar CONSTRUIDO (os bytes que vao para o servidor), com os nomes do
 * Minecraft traduzidos como o FML faz ao carregar ({@link RemapeadorSrg});</li>
 * <li>do Minecraft: o server.jar vanilla da pasta libraries, traduzido do mesmo jeito.</li>
 * </ul>
 * Parte 1 (aqui): o transformador. A classe real {@code SynchronizedMatrixData} passa pelo registro de
 * correcoes REAL (Mekanism.registrar), com os ganchos conferidos no arquivo reofuscado do jar (como
 * no servidor); o resultado passa no CheckClassAdapter e no verificador da JVM e chama o gancho;
 * casos de recusa (desligado, trava, dependencia, gancho ausente). Parte 2: {@code mundo.Cenarios},
 * dentro do class loader de teste; o subcomando "hoshie mekanism" num class loader SEM a correcao.
 * Parte 3 (opcional): {@link TesteMutacao}, mutacoes no codigo-fonte da logica.
 *
 * <p>Argumentos: server.jar construido, pasta com os jars "-srg", jar do Mekanism, jar do IC2, jar
 * vanilla, pasta das classes de teste; opcionais: "rapido" (menos sementes), "fonte=&lt;MatrizInducao.java&gt;"
 * e "cpmut=&lt;classpath&gt;" (ligam a parte 3).
 */
public final class TesteMekanism {

    private TesteMekanism() {
    }

    public static void main(String[] a) throws Exception {
        final Verifica v = new Verifica("TesteMekanism");
        File jarServidor = new File(a[0]);
        File srg = new File(a[1]);
        File mek = new File(a[2]);
        File ic2 = new File(a[3]);
        File vanilla = new File(a[4]);
        File classesTeste = new File(a[5]);
        boolean rapido = false;
        String fonteMut = null;
        String cpMut = null;
        for (int i = 6; i < a.length; i++) {
            if (a[i].equals("rapido")) {
                rapido = true;
            } else if (a[i].startsWith("fonte=")) {
                fonteMut = a[i].substring(6);
            } else if (a[i].startsWith("cpmut=")) {
                cpMut = a[i].substring(6);
            }
        }
        File servidorSrg = new File(srg, jarServidor.getName().replaceAll("\\.jar$", "") + "-srg.jar");
        File mcSrg = new File(srg, vanilla.getName().replaceAll("\\.jar$", "") + "-srg.jar");

        // tradutor de descritores dos ganchos (o papel do RemapeadorFml no servidor)
        final ZipFile zv = new ZipFile(vanilla);
        final ZipFile zs = new ZipFile(jarServidor);
        final RemapeadorSrg rem = new RemapeadorSrg(RemapeadorSrg.mapaDoJar(zs), new RemapeadorSrg.FonteDeClasses() {
            public byte[] bytes(String n) {
                for (ZipFile z : new ZipFile[] {zv, zs}) {
                    ZipEntry e = z.getEntry(n + ".class");
                    if (e != null) {
                        try {
                            return RemapeadorSrg.ler(z.getInputStream(e));
                        } catch (Exception x) {
                            return null;
                        }
                    }
                }
                return null;
            }
        });
        // os ganchos sao conferidos no ARQUIVO do jar construido (nomes reofuscados), como no servidor
        URLClassLoader soOJar = new URLClassLoader(new URL[] {jarServidor.toURI().toURL()}, null);
        ResolvedorDeGanchos ganchos = new ResolvedorDeGanchos(soOJar, new ResolvedorDeGanchos.Remapeador() {
            public String descritor(String d) {
                return rem.mapMethodDesc(d);
            }
        });

        // registro real, modo ligado
        File tmp = new File(classesTeste.getParentFile(), "config-mek");
        tmp.mkdirs();
        File yml = new File(tmp, "hoshiestar.yml");
        yml.delete();
        HoshiestarConfig cfg = HoshiestarConfig.carregar(yml);
        v.igual("desligado", cfg.mekanism_modo, "config: modo padrao = desligado");
        v.igual(1, cfg.mekanism_intervalo, "config: intervalo padrao = 1");
        cfg.mekanism_modo = "ligado";

        final Correcoes correcoes = Correcoes.novoParaTeste();
        final byte[][] guardados = new byte[2][];
        final CarregadorMods[] ref = new CarregadorMods[1];
        final ResolvedorDeGanchos g = ganchos;
        CarregadorMods cl = new CarregadorMods(new URL[] {classesTeste.toURI().toURL(), mcSrg.toURI().toURL(),
            servidorSrg.toURI().toURL(), mek.toURI().toURL(), ic2.toURI().toURL(), vanilla.toURI().toURL()},
                TesteMekanism.class.getClassLoader(), new CarregadorMods.Transformador() {
                    public byte[] transformar(String nome, byte[] bytes) {
                        if (correcoes.ligadasPara(nome) == null) {
                            return bytes;
                        }
                        byte[] r = correcoes.aplicar(nome, bytes, g, ref[0]);
                        guardados[0] = bytes;
                        guardados[1] = r;
                        return r;
                    }
                });
        ref[0] = cl;
        Recursos.limparParaTeste();
        Mekanism.registrar(cfg, correcoes, cl);
        correcoes.fechar();
        v.igual(Mekanism.LIGADO, Mekanism.modo, "registro: modo ligado");
        v.sim(Recursos.achar(Mekanism.ID) != null && Recursos.achar(Mekanism.ID).ligado, "registro: recurso aparece como ligado");

        System.out.println("-- transformador");
        Class<?> k = Class.forName(Mekanism.CLASSE, true, cl);
        Correcao c = correcoes.doRecurso(Mekanism.ID).get(0);
        v.igual(Correcao.Estado.APLICADA, c.estado(), "correcao aplicada na classe real do Mekanism (" + c.linha() + ")");
        v.sim(k.getClassLoader() == cl, "classe carregada pelo class loader de teste (verificador da JVM)");
        v.sim(guardados[1] != null && guardados[1] != guardados[0], "bytes transformados");
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(guardados[1]), cl, false, new PrintWriter(sw));
        v.sim(sw.toString().isEmpty(), "CheckClassAdapter sem erros" + (sw.toString().isEmpty() ? "" : "\n" + sw));
        conferirForma(v, guardados[0], guardados[1]);
        try {
            k.getField(Mekanism.CAMPO);
            v.sim(true, "campo " + Mekanism.CAMPO + " presente na classe carregada");
        } catch (NoSuchFieldException e) {
            v.sim(false, "campo " + Mekanism.CAMPO + " presente na classe carregada");
        }
        gravar(new File(tmp, "SynchronizedMatrixData-transformada.class"), guardados[1]);

        // recusas (cada uma num registro novo, sobre os bytes originais)
        byte[] orig = guardados[0];
        HoshiestarConfig desl = HoshiestarConfig.carregar(yml);
        Correcoes c2 = registro(desl, cl);
        v.sim(c2.ligadasPara(Mekanism.CLASSE) == null && c2.aplicar(Mekanism.CLASSE, orig, ganchos, cl) == orig,
                "desligado: a classe nem entra no transformador (bytes originais)");
        Correcoes c3 = registro(cfg, cl);
        byte[] outros = comMetodoAMais(orig);
        byte[] r3 = c3.aplicar(Mekanism.CLASSE, outros, ganchos, cl);
        v.sim(r3 == outros && c3.doRecurso(Mekanism.ID).get(0).estado() == Correcao.Estado.RECUSADA_TRAVA,
                "trava: outra versao da classe e recusada (" + c3.doRecurso(Mekanism.ID).get(0).linha() + ")");
        File falsa = new File(tmp, "dep-falsa");
        File coord = new File(falsa, "mekanism/api/Coord4D.class");
        coord.getParentFile().mkdirs();
        ZipFile zm = new ZipFile(mek);
        gravar(coord, comMetodoAMais(RemapeadorSrg.ler(zm.getInputStream(zm.getEntry("mekanism/api/Coord4D.class")))));
        URLClassLoader depFalsa = new URLClassLoader(new URL[] {falsa.toURI().toURL(), mek.toURI().toURL()}, null);
        Correcoes c4 = registro(cfg, depFalsa);
        byte[] r4 = c4.aplicar(Mekanism.CLASSE, orig, ganchos, cl);
        Correcao k4 = c4.doRecurso(Mekanism.ID).get(0);
        v.sim(r4 == orig && k4.estado() == Correcao.Estado.NAO_APLICADA && k4.detalhe().contains("trava de dependencia"),
                "trava de dependencia: Coord4D diferente recusa a correcao (" + k4.linha() + ")");
        Correcoes c5 = registro(cfg, new URLClassLoader(new URL[0], null));
        byte[] r5 = c5.aplicar(Mekanism.CLASSE, orig, ganchos, cl);
        Correcao k5 = c5.doRecurso(Mekanism.ID).get(0);
        v.sim(r5 == orig && k5.estado() == Correcao.Estado.NAO_APLICADA && k5.detalhe().contains("dependencia ausente"),
                "dependencia ausente recusa a correcao (" + k5.linha() + ")");
        Correcoes c6 = registro(cfg, cl);
        byte[] r6 = c6.aplicar(Mekanism.CLASSE, orig, new ResolvedorDeGanchos(new URLClassLoader(new URL[0], null)), cl);
        Correcao k6 = c6.doRecurso(Mekanism.ID).get(0);
        v.sim(r6 == orig && k6.estado() == Correcao.Estado.NAO_APLICADA && k6.detalhe().contains("gancho ausente"),
                "gancho ausente recusa a correcao (" + k6.linha() + ")");
        Correcoes c7 = registro(cfg, cl);
        byte[] r7 = c7.aplicar(Mekanism.CLASSE, orig, new ResolvedorDeGanchos(soOJar), cl);
        v.sim(r7 == orig, "sem traduzir os nomes reofuscados do arquivo do gancho, o gancho nao confere (prova de que a traducao foi usada)");
        Correcoes c8 = registro(cfg, cl);
        byte[] r8 = c8.aplicar(Mekanism.CLASSE, guardados[1], ganchos, cl);
        v.sim(r8 == guardados[1] && c8.doRecurso(Mekanism.ID).get(0).estado() == Correcao.Estado.RECUSADA_TRAVA,
                "classe ja transformada nao e transformada de novo (trava)");
        v.igual(Mekanism.DESLIGADO, Mekanism.lerModo("desligado"), "modo: desligado");
        v.igual(Mekanism.VERIFICAR, Mekanism.lerModo(" Verificar "), "modo: verificar (espacos e maiusculas)");
        v.igual(Mekanism.LIGADO, Mekanism.lerModo("LIGADO"), "modo: ligado");
        v.igual(Mekanism.DESLIGADO, Mekanism.lerModo("ligad"), "modo: erro de digitacao vira desligado");
        v.igual(Mekanism.DESLIGADO, Mekanism.lerModo(null), "modo: vazio vira desligado");
        Mekanism.modo = Mekanism.LIGADO;
        Mekanism.intervalo = 1;
        v.sim(Mekanism.linhaDeEstado().startsWith("modo ligado"), "linha de estado: " + Mekanism.linhaDeEstado());

        System.out.println("-- logica (classes reais, dentro do class loader de teste)");
        Class<?> cen = Class.forName("hoshiestar.teste.mekanism.mundo.Cenarios", true, cl);
        v.sim(cen.getClassLoader() == cl, "codigo de teste com Minecraft carregado no class loader de teste");
        v.sim(Class.forName("hoshiestar.mekanism.matriz.MatrizInducao", false, cl).getClassLoader() == cl,
                "logica (do jar construido) no class loader de teste, junto com o Mekanism");
        cen.getMethod("rodar", Verifica.class, boolean.class).invoke(null, v, rapido);

        System.out.println("-- subcomando 'hoshie mekanism' sem a correcao (Mekanism original: modo desligado)");
        final URL[] fontes = {classesTeste.toURI().toURL(), mcSrg.toURI().toURL(), servidorSrg.toURI().toURL(),
            mek.toURI().toURL(), ic2.toURI().toURL(), vanilla.toURI().toURL()};
        CarregadorMods semCorrecao = new CarregadorMods(fontes, TesteMekanism.class.getClassLoader(), null);
        Class<?> smd = Class.forName(Mekanism.CLASSE, true, semCorrecao);
        boolean temCampo = true;
        try {
            smd.getField(Mekanism.CAMPO);
        } catch (NoSuchFieldException e) {
            temCampo = false;
        }
        v.sim(!temCampo, "sem a correcao a classe do Mekanism fica sem o campo (original)");
        Object f = Class.forName("hoshiestar.teste.mekanism.mundo.Cenarios", true, semCorrecao).getMethod("comandoSemCorrecao").invoke(null);
        v.sim(((List<?>) f).isEmpty(), "subcomando sem a correcao: relatorio completo, sem tocar na classe da logica" + (((List<?>) f).isEmpty() ? "" : " -> " + f));

        if (fonteMut != null && cpMut != null) {
            System.out.println("-- mutacao no codigo-fonte da logica (cada protecao tirada tem de ser acusada)");
            final File yml2 = yml;
            final File cs = classesTeste;
            TesteMutacao.rodar(v, new TesteMutacao.Fabrica() {
                public ClassLoader carregador(URL primeiro) throws Exception {
                    return comCorrecao(primeiro, fontes, yml2, g);
                }
            }, new File(fonteMut), new File(cs.getParentFile(), "mutacao"), cpMut, servidorSrg);
        }
        Mekanism.modo = Mekanism.DESLIGADO;
        v.fim();
    }

    /**
     * Class loader de teste novo com {@code primeiro} na frente das fontes e a correcao REAL aplicada
     * (modo ligado) na classe real do Mekanism, conferida.
     */
    static ClassLoader comCorrecao(URL primeiro, URL[] fontes, File yml, final ResolvedorDeGanchos g) throws Exception {
        URL[] todas = new URL[fontes.length + 1];
        todas[0] = primeiro;
        System.arraycopy(fontes, 0, todas, 1, fontes.length);
        final Correcoes cs = Correcoes.novoParaTeste();
        final CarregadorMods[] ref = new CarregadorMods[1];
        CarregadorMods c = new CarregadorMods(todas, TesteMekanism.class.getClassLoader(), new CarregadorMods.Transformador() {
            public byte[] transformar(String nome, byte[] bytes) {
                return cs.ligadasPara(nome) == null ? bytes : cs.aplicar(nome, bytes, g, ref[0]);
            }
        });
        ref[0] = c;
        HoshiestarConfig cfg = HoshiestarConfig.carregar(yml);
        cfg.mekanism_modo = "ligado";
        Recursos.limparParaTeste();
        Mekanism.registrar(cfg, cs, c);
        cs.fechar();
        Class.forName(Mekanism.CLASSE, true, c);
        Correcao k = cs.doRecurso(Mekanism.ID).get(0);
        if (k.estado() != Correcao.Estado.APLICADA) {
            throw new IllegalStateException("correcao nao aplicada no class loader de mutacao: " + k.linha());
        }
        return c;
    }

    static Correcoes registro(HoshiestarConfig cfg, ClassLoader deps) {
        Recursos.limparParaTeste();
        Correcoes c = Correcoes.novoParaTeste();
        Mekanism.registrar(cfg, c, deps);
        c.fechar();
        return c;
    }

    /** Uma "outra versao" da classe: os mesmos bytes com um metodo a mais (hash diferente). */
    static byte[] comMetodoAMais(byte[] b) {
        ClassNode cn = Asm.ler(b);
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "hoshiestarTesteVersao", "()V", null, null);
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        m.maxStack = 0;
        m.maxLocals = 0;
        cn.methods.add(m);
        return Asm.escrever(cn);
    }

    /** O inicio de getEnergy/setEnergy chama os ganchos; o resto do corpo e o original, instrucao por instrucao. */
    static void conferirForma(Verifica v, byte[] orig, byte[] novo) {
        ClassNode a = Asm.ler(orig);
        ClassNode b = Asm.ler(novo);
        boolean campo = false;
        for (FieldNode f : b.fields) {
            if (f.name.equals(Mekanism.CAMPO)) {
                campo = f.desc.equals("Ljava/lang/Object;") && (f.access & Opcodes.ACC_PUBLIC) != 0 && (f.access & Opcodes.ACC_SYNTHETIC) != 0;
            }
        }
        v.sim(campo, "forma: campo de estado publico, sintetico, Object");
        v.igual(a.fields.size() + 1, b.fields.size(), "forma: so um campo a mais");
        v.igual(a.methods.size(), b.methods.size(), "forma: nenhum metodo a mais");
        String[][] esperado = {
            {"getEnergy", "(Lnet/minecraft/world/World;)D", "antesDeLer", "valorLido"},
            {"setEnergy", "(Lnet/minecraft/world/World;D)V", "antesDeEscrever", null},
        };
        for (String[] e : esperado) {
            MethodNode ma = metodo(a, e[0], e[1]);
            MethodNode mb = metodo(b, e[0], e[1]);
            List<String> oa = opcodes(ma);
            List<String> ob = opcodes(mb);
            List<String> chamadas = new ArrayList<String>();
            for (AbstractInsnNode n = mb.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof MethodInsnNode && ((MethodInsnNode) n).owner.equals(Mekanism.GANCHO)) {
                    chamadas.add(((MethodInsnNode) n).name + ((MethodInsnNode) n).desc);
                }
            }
            String descGancho = "(Lmekanism/common/content/matrix/SynchronizedMatrixData;" + e[1].substring(1, e[1].indexOf(')')) + ")";
            List<String> esperadas = new ArrayList<String>();
            esperadas.add(e[2] + descGancho + "Z");
            if (e[3] != null) {
                esperadas.add(e[3] + descGancho + "D");
            }
            v.igual(esperadas, chamadas, "forma: " + e[0] + " chama os ganchos com os tipos de tempo de execucao");
            boolean cauda = ob.size() > oa.size() && ob.subList(ob.size() - oa.size(), ob.size()).equals(oa);
            v.sim(cauda, "forma: " + e[0] + " termina com o corpo original intacto (" + oa.size() + " instrucoes)");
        }
    }

    static MethodNode metodo(ClassNode cn, String nome, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(nome) && m.desc.equals(desc)) {
                return m;
            }
        }
        throw new IllegalStateException("metodo ausente: " + nome + desc);
    }

    static List<String> opcodes(MethodNode m) {
        List<String> l = new ArrayList<String>();
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() >= 0) {
                String s = String.valueOf(n.getOpcode());
                if (n instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) n;
                    s += " " + mi.owner + "." + mi.name + mi.desc;
                }
                l.add(s);
            }
        }
        return l;
    }

    static void gravar(File f, byte[] b) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(b);
        } finally {
            o.close();
        }
    }
}
