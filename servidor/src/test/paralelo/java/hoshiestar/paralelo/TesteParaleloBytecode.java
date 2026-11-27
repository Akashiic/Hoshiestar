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

import hoshiestar.teste.Verifica;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Bytecode: o interruptor e {@code static final}, as classes do paralelo nao citam o Minecraft, e (com o build rapido
 * feito) o unico ponto do jogo que chama o paralelo e o {@code FMLCommonHandler.getEffectiveSide}, uma vez, depois do
 * teste original do nome "Server thread".
 *
 * <p>{@code TesteParaleloBytecode <pasta com as classes compiladas pelo teste> [<pasta de classes do build rapido>]}.
 */
public final class TesteParaleloBytecode {

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final String[] MC_FREE = {"Paralelo", "Parametros", "Recusas", "Recusas$Fatos", "RegistroParalelo",
        "ComandoParalelo",
        // nucleo de concorrencia (passo 5): nenhuma dependencia do jogo
        "Ambiente", "Andamento", "Andamento$Fecho", "Andamento$Fim", "Barreira", "Barreira$EsperaPortao", "Barreira$Foto",
        "Despejo", "Fase", "Fase$Estado", "Operacao", "Pedido", "Preso", "ThreadRegiao", "Trabalhador", "Trabalho"};

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteParaleloBytecode");
        try {
            File dir = new File(args[0]);
            interruptor(v, dir);
            semMinecraft(v, dir);
            if (args.length > 1 && new File(args[1], "cpw/mods/fml/common/FMLCommonHandler.class").isFile()) {
                jogo(v, new File(args[1]));
            } else {
                System.out.println("  AVISO: parte do jogo PULADA (sem a pasta de classes do build rapido)");
            }
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    static ClassNode ler(File dir, String nome) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(new File(dir, nome + ".class").toPath())).accept(cn, 0);
        return cn;
    }

    static void interruptor(Verifica v, File dir) throws Exception {
        ClassNode p = ler(dir, "hoshiestar/paralelo/Paralelo");
        int achados = 0;
        for (FieldNode f : p.fields) {
            if (f.name.equals("LIGADO")) {
                achados++;
                v.sim((f.access & Opcodes.ACC_STATIC) != 0 && (f.access & Opcodes.ACC_FINAL) != 0 && f.desc.equals("Z")
                        && (f.access & Opcodes.ACC_PUBLIC) != 0, "Paralelo.LIGADO e 'public static final boolean'");
            }
        }
        v.igual(1, achados, "um campo LIGADO em Paralelo");
        // o valor e atribuido uma so vez, no inicializador estatico (um PUTSTATIC de LIGADO, em <clinit>)
        int puts = 0;
        for (MethodNode m : p.methods) {
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() == Opcodes.PUTSTATIC && ((org.objectweb.asm.tree.FieldInsnNode) i).name.equals("LIGADO")) {
                    puts++;
                    v.igual("<clinit>", m.name, "LIGADO so e atribuido no inicializador estatico");
                }
            }
        }
        v.igual(1, puts, "uma atribuicao de LIGADO");
        // ehThreadDeRegiao testa LIGADO antes de olhar o nome (desligado nao toca em nada)
        for (MethodNode m : p.methods) {
            if (m.name.equals("ehThreadDeRegiao")) {
                AbstractInsnNode a = m.instructions.getFirst();
                while (a != null && a.getOpcode() < 0) {
                    a = a.getNext();
                }
                v.sim(a != null && a.getOpcode() == Opcodes.GETSTATIC && ((org.objectweb.asm.tree.FieldInsnNode) a).name.equals("LIGADO"),
                        "ehThreadDeRegiao comeca testando LIGADO");
            }
        }
    }

    static void semMinecraft(Verifica v, File dir) throws Exception {
        for (String n : MC_FREE) {
            String bytes = new String(Files.readAllBytes(new File(dir, "hoshiestar/paralelo/" + n + ".class").toPath()), LATIN1);
            boolean limpo = !bytes.contains("net/minecraft/") && !bytes.contains("org/bukkit/") && !bytes.contains("cpw/mods/")
                    && !bytes.contains("org/spigotmc/");
            v.sim(limpo, n + " nao cita Minecraft, Bukkit, Forge nem Spigot (roda na fase de coremod)");
        }
    }

    static void jogo(Verifica v, File build) throws Exception {
        ClassNode fml = ler(build, "cpw/mods/fml/common/FMLCommonHandler");
        MethodNode ge = null;
        for (MethodNode m : fml.methods) {
            if (m.name.equals("getEffectiveSide") && m.desc.equals("()Lcpw/mods/fml/relauncher/Side;")) {
                ge = m;
            }
        }
        v.sim(ge != null, "FMLCommonHandler.getEffectiveSide existe");
        int chamadas = 0, iLdc = -1, iHook = -1, k = 0;
        int iEquals = -1;
        for (AbstractInsnNode i : ge.instructions.toArray()) {
            if (i instanceof LdcInsnNode && "Server thread".equals(((LdcInsnNode) i).cst)) {
                iLdc = k;
            }
            if (i instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) i;
                if (mi.owner.equals("java/lang/String") && mi.name.equals("equals")) {
                    iEquals = k;
                }
                if (mi.owner.equals("hoshiestar/paralelo/Paralelo")) {
                    chamadas++;
                    iHook = k;
                    v.sim(mi.name.equals("ehThreadDeRegiao") && mi.desc.equals("(Ljava/lang/Thread;)Z"),
                            "a chamada e Paralelo.ehThreadDeRegiao(Thread)");
                }
            }
            k++;
        }
        v.igual(1, chamadas, "uma chamada ao paralelo em getEffectiveSide");
        v.sim(iLdc >= 0 && iEquals > iLdc && iHook > iEquals, "o teste original do nome 'Server thread' vem antes do gancho (" + iLdc + " < " + iEquals + " < " + iHook + ")");
        // nenhuma outra classe do jogo (net/minecraft, cpw) cita o paralelo: sem patch de tick neste passo
        List<String> citam = new ArrayList<String>();
        varrer(new File(build, "net"), "net", citam);
        varrer(new File(build, "cpw"), "cpw", citam);
        v.igual(java.util.Arrays.asList("cpw/mods/fml/common/FMLCommonHandler.class"), citam,
                "so o FMLCommonHandler cita hoshiestar/paralelo entre as classes do jogo (nenhum gancho de tick)");
    }

    private static void varrer(File d, String rel, List<String> citam) throws Exception {
        File[] fs = d.listFiles();
        if (fs == null) {
            return;
        }
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            if (f.isDirectory()) {
                varrer(f, rel + "/" + f.getName(), citam);
            } else if (f.getName().endsWith(".class")
                    && new String(Files.readAllBytes(f.toPath()), LATIN1).contains("hoshiestar/paralelo")) {
                citam.add(rel + "/" + f.getName());
            }
        }
    }
}
