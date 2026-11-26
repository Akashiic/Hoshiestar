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
package hoshiestar.orcamento;

import hoshiestar.teste.Verifica;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Confere os patches do orcamento no bytecode REAL das classes do jogo (as que o build rapido gerou): cada chamada a
 * GanchosOrcamento esta atras de "Orcamento.LIGADO" (com ele falso o salto passa por cima da chamada e cai no codigo
 * original), so nos metodos previstos, na quantidade prevista; os ganchos de corte (entidade, tile, chunk) tem a forma
 * "if (LIGADO &amp;&amp; !gancho(...)) continue" e o continue cai no passo do laco; o gancho do tile vem depois do blockExists
 * (fora do trecho que o DragonAPI reescreve) e antes do medidor, numa linha propria, longe da chamada de update (o Opis casa
 * [linha, ALOAD, chamada] exato e o firewall troca a chamada); a puxada so roda com forcar = false e, com ela falsa, o laco
 * original roda; nenhuma variavel local nova (maxLocals igual ao de antes); Orcamento.LIGADO e static final;
 * GanchosOrcamento nao escreve campo de classe do Minecraft.
 */
public final class TesteOrcamentoBytecode {

    private static final String GANCHOS = "hoshiestar/orcamento/GanchosOrcamento";
    private static final String ORC = "hoshiestar/orcamento/Orcamento";
    private static Verifica v;

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        v = new Verifica("TesteOrcamentoBytecode");
        ClassNode world = ler(dir, "net/minecraft/world/World.class");
        ClassNode ws = ler(dir, "net/minecraft/world/WorldServer.class");
        ClassNode servidor = ler(dir, "net/minecraft/server/MinecraftServer.class");
        Map<String, Integer> previstos = new HashMap<String, Integer>();
        previstos.put("net/minecraft/world/World.updateEntities()V", 4);
        previstos.put("net/minecraft/world/WorldServer.tickUpdates(Z)Z", 4);
        previstos.put("net/minecraft/world/WorldServer.func_147456_g()V", 2);
        previstos.put("net/minecraft/server/MinecraftServer.tick()V", 2);
        int total = 0;
        for (ClassNode cn : new ClassNode[] {world, ws, servidor}) {
            for (MethodNode m : cn.methods) {
                int n = 0;
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(GANCHOS)) {
                        n++;
                        if (((MethodInsnNode) i).name.equals("proxima")) {
                            continue; // so roda no else da puxada: conferido em puxada()
                        }
                        v.sim(guardado(m, (MethodInsnNode) i), "chamada a " + ((MethodInsnNode) i).name + " em " + cn.name + "." + m.name
                                + " atras de 'if (Orcamento.LIGADO)'");
                    }
                }
                Integer esperado = previstos.get(cn.name + "." + m.name + m.desc);
                if (n > 0 || esperado != null) {
                    v.igual(esperado == null ? 0 : esperado, n, "chamadas a GanchosOrcamento em " + cn.name + "." + m.name + m.desc);
                }
                total += n;
            }
        }
        v.igual(12, total, "12 chamadas a GanchosOrcamento no total, todas atras de 'Orcamento.LIGADO'");
        // nenhuma variavel local nova (Opis injeta com os indices 2 e 9; os numeros sao os do World/WorldServer de antes)
        v.igual(14, metodo(world, "updateEntities", "()V").maxLocals, "maxLocals de World.updateEntities = 14 (igual ao de antes)");
        v.igual(12, metodo(ws, "tickUpdates", "(Z)Z").maxLocals, "maxLocals de WorldServer.tickUpdates = 12 (igual ao de antes)");
        v.igual(23, metodo(ws, "func_147456_g", "()V").maxLocals, "maxLocals de WorldServer.func_147456_g = 23 (igual ao de antes)");
        // ganchos de corte: "if (LIGADO && !gancho(...)) continue;" e o continue vai para o passo do laco
        corte(metodo(world, "updateEntities", "()V"), "entidade", "tickPosition");
        corte(metodo(world, "updateEntities", "()V"), "tile", "tileTickPosition");
        corte(metodo(ws, "func_147456_g", "()V"), "chunkAleatorio", null);
        // o gancho do tile: depois do blockExists(III) do laco de tiles e antes do gancho do medidor
        MethodNode ue0 = metodo(world, "updateEntities", "()V");
        int iBlock = -1, iTile = -1, iMed = -1, k = 0;
        for (AbstractInsnNode i : ue0.instructions.toArray()) {
            if (i instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) i;
                if (mi.name.equals("blockExists") && mi.desc.equals("(III)Z") && iTile < 0) {
                    iBlock = k;
                }
                if (mi.owner.equals(GANCHOS) && mi.name.equals("tile")) {
                    iTile = k;
                }
                if (mi.owner.equals("hoshiestar/medidor/GanchosMedidor") && mi.name.equals("inicioTE")) {
                    iMed = k;
                }
            }
            k++;
        }
        v.sim(iBlock >= 0 && iTile > iBlock && iMed > iTile, "gancho do tile depois do blockExists e antes do medidor (" + iBlock + " < " + iTile + " < " + iMed + ")");
        // a puxada: so com forcar = false; com ela falsa (ou LIGADO falso, ou forcar) cai no laco original
        puxada(metodo(ws, "tickUpdates", "(Z)Z"));
        // o gancho do tile nao esta na linha da chamada de update
        MethodNode ue = metodo(world, "updateEntities", "()V");
        int linha = -1, linhaHook = -1, linhaUpdate = -2;
        for (AbstractInsnNode i : ue.instructions.toArray()) {
            if (i instanceof LineNumberNode) {
                linha = ((LineNumberNode) i).line;
            }
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(GANCHOS) && ((MethodInsnNode) i).name.equals("tile")) {
                linhaHook = linha;
            }
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals("net/minecraft/tileentity/TileEntity")
                    && ((MethodInsnNode) i).name.equals("updateEntity")) {
                linhaUpdate = linha;
            }
        }
        v.sim(linhaHook > 0 && linhaHook != linhaUpdate, "o gancho do tile esta numa linha propria (" + linhaHook + " x " + linhaUpdate + ")");
        // a chamada de update continua uma so, com ALOAD logo antes (o padrao que Opis e firewall procuram)
        int updates = 0;
        for (AbstractInsnNode i : ue.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals("net/minecraft/tileentity/TileEntity")
                    && ((MethodInsnNode) i).name.equals("updateEntity")) {
                updates++;
                AbstractInsnNode a = real(i, false);
                v.sim(a instanceof VarInsnNode && a.getOpcode() == Opcodes.ALOAD, "o ALOAD continua logo antes de TileEntity.updateEntity()");
            }
        }
        v.igual(1, updates, "uma chamada de TileEntity.updateEntity() em World.updateEntities");
        // interruptor: static final
        ClassNode orc = ler(dir, "hoshiestar/orcamento/Orcamento.class");
        boolean finalEstatico = false;
        for (org.objectweb.asm.tree.FieldNode f : orc.fields) {
            if (f.name.equals("LIGADO")) {
                finalEstatico = (f.access & Opcodes.ACC_STATIC) != 0 && (f.access & Opcodes.ACC_FINAL) != 0 && f.desc.equals("Z");
            }
        }
        v.sim(finalEstatico, "Orcamento.LIGADO e 'static final boolean'");
        // GanchosOrcamento so le o jogo
        ClassNode g = ler(dir, "hoshiestar/orcamento/GanchosOrcamento.class");
        boolean escreve = false;
        for (MethodNode m : g.methods) {
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i instanceof FieldInsnNode && i.getOpcode() == Opcodes.PUTFIELD && ((FieldInsnNode) i).owner.startsWith("net/minecraft/")) {
                    escreve = true;
                }
            }
        }
        v.sim(!escreve, "GanchosOrcamento nao escreve em nenhum campo de classe do Minecraft");
        v.fim();
    }

    /**
     * A chamada: para tras, pulando cargas de argumento (variaveis, campos, chamadas sem efeito como getWorldTotalTime) e
     * testes de parametro ("ILOAD; IFNE" do forcar), vem IFEQ precedido de GETSTATIC Orcamento.LIGADO, e o destino desse
     * IFEQ fica depois da chamada (com LIGADO falso a chamada nunca roda).
     */
    private static boolean guardado(MethodNode m, MethodInsnNode call) {
        AbstractInsnNode i = real(call, false);
        while (i != null) {
            if (i instanceof VarInsnNode || i.getOpcode() == Opcodes.GETFIELD) {
                i = real(i, false);
            } else if (i instanceof MethodInsnNode && ((MethodInsnNode) i).name.equals("getWorldTotalTime")) {
                i = real(i, false);
            } else if (i.getOpcode() == Opcodes.IFNE && real(i, false) instanceof VarInsnNode
                    && real(i, false).getOpcode() == Opcodes.ILOAD) {
                i = real(real(i, false), false);
            } else {
                break;
            }
        }
        if (!(i instanceof JumpInsnNode) || i.getOpcode() != Opcodes.IFEQ) {
            return false;
        }
        AbstractInsnNode g = real(i, false);
        if (!(g instanceof FieldInsnNode) || g.getOpcode() != Opcodes.GETSTATIC) {
            return false;
        }
        FieldInsnNode f = (FieldInsnNode) g;
        if (!f.owner.equals(ORC) || !f.name.equals("LIGADO")) {
            return false;
        }
        // o destino do IFEQ fica depois da chamada
        return m.instructions.indexOf(((JumpInsnNode) i).label) > m.instructions.indexOf(call);
    }

    /** Destino de um salto, pulando rotulos e linhas ate a primeira instrucao real. */
    private static AbstractInsnNode destino(JumpInsnNode j) {
        AbstractInsnNode n = j.label;
        while (n != null && n.getOpcode() < 0) {
            n = n.getNext();
        }
        return n;
    }

    /**
     * "if (Orcamento.LIGADO &amp;&amp; !gancho(...)) continue;": GETSTATIC LIGADO; IFEQ L; ...; INVOKESTATIC gancho ()Z; IFNE L;
     * GOTO passo; L. O passo do laco (destino do GOTO) mexe no campo {@code campoPasso} (ou, sem campo, e um salto para
     * tras: o "continue" do laco do iterador).
     */
    private static void corte(MethodNode m, String nome, String campoPasso) {
        int achados = 0;
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (!(i instanceof MethodInsnNode) || !((MethodInsnNode) i).owner.equals(GANCHOS) || !((MethodInsnNode) i).name.equals(nome)) {
                continue;
            }
            achados++;
            MethodInsnNode c = (MethodInsnNode) i;
            v.sim(c.desc.endsWith(")Z"), nome + " devolve boolean");
            AbstractInsnNode ifne = real(c, true);
            v.sim(ifne != null && ifne.getOpcode() == Opcodes.IFNE, nome + ": o retorno e testado com IFNE");
            AbstractInsnNode go = ifne == null ? null : real(ifne, true);
            v.sim(go != null && go.getOpcode() == Opcodes.GOTO, nome + ": falso = GOTO (continue)");
            // o IFEQ do LIGADO e o IFNE do retorno vao para o mesmo lugar (logo depois do GOTO): o caminho original
            AbstractInsnNode p = real(c, false);
            while (p != null && !(p instanceof JumpInsnNode)) {
                p = real(p, false);
            }
            boolean mesmo = p != null && p.getOpcode() == Opcodes.IFEQ && ifne instanceof JumpInsnNode
                    && destino((JumpInsnNode) p) == destino((JumpInsnNode) ifne) && destino((JumpInsnNode) p) == real(go, true);
            v.sim(mesmo, nome + ": LIGADO falso e gancho verdadeiro caem no mesmo ponto, logo depois do continue");
            if (go instanceof JumpInsnNode) {
                AbstractInsnNode alvo = destino((JumpInsnNode) go);
                if (campoPasso != null) {
                    boolean passo = false;
                    AbstractInsnNode q = alvo;
                    for (int k = 0; q != null && k < 6; k++, q = real(q, true)) {
                        passo |= q instanceof FieldInsnNode && q.getOpcode() == Opcodes.PUTFIELD && ((FieldInsnNode) q).name.equals(campoPasso);
                    }
                    v.sim(passo, nome + ": o continue cai no passo do laco (" + campoPasso + "++)");
                } else {
                    v.sim(m.instructions.indexOf(alvo) < m.instructions.indexOf(go), nome + ": o continue volta para o topo do laco");
                }
            }
        }
        v.igual(1, achados, "um gancho de corte '" + nome + "' em " + m.name);
    }

    /** A puxada: LIGADO e !forcar antes; o retorno verdadeiro pula o laco original; LIGADO falso / forcar cai nele. */
    private static void puxada(MethodNode m) {
        MethodInsnNode c = null;
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(GANCHOS) && ((MethodInsnNode) i).name.equals("puxar")) {
                c = (MethodInsnNode) i;
            }
        }
        v.sim(c != null && c.desc.endsWith(")Z"), "tickUpdates chama puxar(...)Z");
        if (c == null) {
            return;
        }
        AbstractInsnNode depois = real(c, true);
        v.sim(depois != null && depois.getOpcode() == Opcodes.IFNE, "puxada: retorno verdadeiro salta (pula o laco original)");
        AbstractInsnNode orig = real(depois, true); // o laco original comeca aqui (int j = 0)
        // o IFEQ do LIGADO e o IFNE do forcar vao para o comeco do laco original
        int saltos = 0;
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i instanceof JumpInsnNode && m.instructions.indexOf(i) < m.instructions.indexOf(c)
                    && m.instructions.indexOf(i) > m.instructions.indexOf(c) - 20 && destino((JumpInsnNode) i) == orig) {
                saltos++;
            }
        }
        v.igual(2, saltos, "puxada: LIGADO falso e forcar verdadeiro caem no comeco do laco original");
        v.sim(orig.getOpcode() == Opcodes.ICONST_0, "puxada: o laco original comeca em 'int j = 0'");
        AbstractInsnNode alvo = destino((JumpInsnNode) depois);
        v.sim(m.instructions.indexOf(alvo) > m.instructions.indexOf(orig), "puxada: o salto do retorno verdadeiro vai para depois do laco original");
        // o else: while ((e = proxima()) != null) { TreeSet.remove; Set.remove; List.add } -- so alcancavel pelo retorno verdadeiro
        v.sim(alvo instanceof MethodInsnNode && ((MethodInsnNode) alvo).owner.equals(GANCHOS) && ((MethodInsnNode) alvo).name.equals("proxima"),
                "puxada: o retorno verdadeiro cai em proxima() (o else), e so ele");
        int proximas = 0, removeTree = 0, removeSet = 0, adds = 0;
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (!(i instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode mi = (MethodInsnNode) i;
            if (mi.owner.equals(GANCHOS) && mi.name.equals("proxima")) {
                proximas++;
            }
            int k = m.instructions.indexOf(i);
            if (k > m.instructions.indexOf(alvo) && k < m.instructions.indexOf(alvo) + 30) {
                removeTree += mi.owner.equals("java/util/TreeSet") && mi.name.equals("remove") ? 1 : 0;
                removeSet += mi.owner.equals("java/util/Set") && mi.name.equals("remove") ? 1 : 0;
                adds += mi.owner.equals("java/util/List") && mi.name.equals("add") ? 1 : 0;
            }
        }
        v.igual(1, proximas, "puxada: uma chamada de proxima()");
        v.sim(removeTree == 1 && removeSet == 1 && adds == 1,
                "puxada: o else tira com as chamadas do original (TreeSet.remove, Set.remove, List.add: o @Redirect do Hodgepodge em TreeSet.remove de tickUpdates as ve)");
        int treeRemoves = 0;
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals("java/util/TreeSet") && ((MethodInsnNode) i).name.equals("remove")) {
                treeRemoves++;
            }
        }
        v.igual(2, treeRemoves, "puxada: duas chamadas de TreeSet.remove em tickUpdates (o original e a do else)");
    }

    private static AbstractInsnNode real(AbstractInsnNode i, boolean frente) {
        AbstractInsnNode n = frente ? i.getNext() : i.getPrevious();
        while (n != null && n.getOpcode() < 0) {
            n = frente ? n.getNext() : n.getPrevious();
        }
        return n;
    }

    private static ClassNode ler(File dir, String nome) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(new File(dir, nome).toPath())).accept(cn, 0);
        return cn;
    }

    private static MethodNode metodo(ClassNode cn, String nome, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(nome) && m.desc.equals(desc)) {
                return m;
            }
        }
        throw new IllegalStateException("metodo nao achado: " + cn.name + "." + nome + desc);
    }
}
