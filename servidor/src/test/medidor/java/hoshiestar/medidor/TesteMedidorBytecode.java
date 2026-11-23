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
package hoshiestar.medidor;

import hoshiestar.teste.Verifica;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.File;
import java.nio.file.Files;

/**
 * Confere os patches no bytecode compilado do workspace (World, EntityPlayerMP, MinecraftServer, saida do
 * build rapido em eclipse/cauldron/build/classes): (1) caminho desligado = um teste de campo estatico por
 * gancho: toda chamada a GanchosMedidor vem logo depois de GETSTATIC Medidor.ATIVO + IFEQ, e o desvio cai
 * logo depois da chamada; (2) os padroes exatos que outros transformadores procuram em World.updateEntities
 * continuam la: Opis [linha, ALOAD 9, INVOKEVIRTUAL TileEntity.updateEntity] e [linha, ALOAD 0, ALOAD 2,
 * INVOKEVIRTUAL World.updateEntity], o firewall exige exatamente 1 chamada TileEntity.updateEntity, locais 2 e 9
 * (o Opis injeta com ALOAD 2 e ALOAD 9) continuam sendo entity e tileentity; (3) nenhuma variavel local nova
 * nos metodos com gancho de entidade/tile (mixins com captura de locais e o Opis usam os indices).
 *
 * <p>Uso: {@code java hoshiestar.medidor.TesteMedidorBytecode <pasta classes do build>}.
 */
public final class TesteMedidorBytecode {

    private static final String GANCHOS = "hoshiestar/medidor/GanchosMedidor";
    private static Verifica v;

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        v = new Verifica("TesteMedidorBytecode");
        ClassNode world = ler(dir, "net/minecraft/world/World.class");
        ClassNode jogador = ler(dir, "net/minecraft/entity/player/EntityPlayerMP.class");
        ClassNode servidor = ler(dir, "net/minecraft/server/MinecraftServer.class");
        int total = 0;
        total += guardados(world, "updateEntities", "()V", 3);
        total += guardados(world, "updateEntityWithOptionalForce", "(Lnet/minecraft/entity/Entity;Z)V", 4);
        total += guardados(jogador, "onUpdateEntity", "()V", 2);
        v.igual(9, total, "9 chamadas a GanchosMedidor no total, todas atras de 'if (Medidor.ATIVO)'");
        for (ClassNode cn : new ClassNode[] {world, jogador, servidor}) {
            for (MethodNode m : cn.methods) {
                int n = 0;
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(GANCHOS)) {
                        n++;
                    }
                }
                boolean esperado = (cn == world && (m.name.equals("updateEntities") || m.name.equals("updateEntityWithOptionalForce")))
                        || (cn == jogador && m.name.equals("onUpdateEntity"));
                if (n > 0 && !esperado) {
                    v.sim(false, "chamada a GanchosMedidor fora dos pontos previstos: " + cn.name + "." + m.name);
                }
            }
        }
        padroesWorld(world);
        servidor(servidor);
        v.fim();
    }

    private static ClassNode ler(File dir, String nome) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(new File(dir, nome).toPath())).accept(cn, 0); // com quadros, como o Opis
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

    private static AbstractInsnNode real(AbstractInsnNode i, boolean frente) {
        AbstractInsnNode n = frente ? i.getNext() : i.getPrevious();
        while (n != null && n.getOpcode() < 0) {
            n = frente ? n.getNext() : n.getPrevious();
        }
        return n;
    }

    /** Cada chamada a GanchosMedidor: antes dela (pulando os argumentos ALOAD/ILOAD) IFEQ precedido de GETSTATIC ATIVO. */
    private static int guardados(ClassNode cn, String nome, String desc, int esperadas) {
        MethodNode m = metodo(cn, nome, desc);
        int n = 0;
        int ok = 0;
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (!(i instanceof MethodInsnNode) || !((MethodInsnNode) i).owner.equals(GANCHOS)) {
                continue;
            }
            n++;
            AbstractInsnNode p = real(i, false);
            while (p != null && (p.getOpcode() == Opcodes.ALOAD || p.getOpcode() == Opcodes.ILOAD)) {
                p = real(p, false);
            }
            boolean temIfeq = p instanceof JumpInsnNode && p.getOpcode() == Opcodes.IFEQ;
            AbstractInsnNode g = temIfeq ? real(p, false) : null;
            boolean temCampo = g instanceof FieldInsnNode && g.getOpcode() == Opcodes.GETSTATIC
                    && ((FieldInsnNode) g).owner.equals("hoshiestar/medidor/Medidor") && ((FieldInsnNode) g).name.equals("ATIVO");
            // o desvio do "desligado" pula exatamente a chamada: o alvo do IFEQ e o que vem logo depois dela
            boolean pulaSoAChamada = false;
            if (temIfeq) {
                LabelNode alvo = ((JumpInsnNode) p).label;
                AbstractInsnNode depois = real(i, true);
                AbstractInsnNode depoisDoAlvo = real(alvo, true);
                pulaSoAChamada = depois == depoisDoAlvo;
            }
            if (temIfeq && temCampo && pulaSoAChamada) {
                ok++;
            } else {
                v.sim(false, cn.name + "." + nome + ": chamada " + ((MethodInsnNode) i).name + " sem o teste de campo estatico"
                        + " (ifeq=" + temIfeq + ", ATIVO=" + temCampo + ", pula so a chamada=" + pulaSoAChamada + ")");
            }
        }
        v.sim(n == esperadas && ok == n, cn.name + "." + nome + ": " + n + " chamada(s) a GanchosMedidor (esperado "
                + esperadas + "), " + ok + " atras de GETSTATIC Medidor.ATIVO + IFEQ que pula so a chamada");
        return n;
    }

    private static void padroesWorld(ClassNode world) {
        MethodNode m = metodo(world, "updateEntities", "()V");
        AbstractInsnNode[] l = m.instructions.toArray();
        int chamadasTe = 0;
        int opisTe = 0;
        int opisEnt = 0;
        int aloadTe = -1;
        int aloadEnt = -1;
        for (int i = 0; i < l.length; i++) {
            if (l[i] instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) l[i];
                if (mi.owner.equals("net/minecraft/tileentity/TileEntity") && mi.name.equals("updateEntity") && mi.desc.equals("()V")) {
                    chamadasTe++;
                }
            }
            if (i + 2 < l.length && l[i] instanceof LineNumberNode && l[i + 1].getOpcode() == Opcodes.ALOAD
                    && l[i + 2] instanceof MethodInsnNode && l[i + 2].getOpcode() == Opcodes.INVOKEVIRTUAL
                    && ((MethodInsnNode) l[i + 2]).owner.equals("net/minecraft/tileentity/TileEntity")
                    && ((MethodInsnNode) l[i + 2]).name.equals("updateEntity")) {
                opisTe++;
                aloadTe = ((VarInsnNode) l[i + 1]).var;
            }
            if (i + 3 < l.length && l[i] instanceof LineNumberNode && l[i + 1].getOpcode() == Opcodes.ALOAD
                    && l[i + 2].getOpcode() == Opcodes.ALOAD && l[i + 3] instanceof MethodInsnNode
                    && l[i + 3].getOpcode() == Opcodes.INVOKEVIRTUAL && ((MethodInsnNode) l[i + 3]).name.equals("updateEntity")
                    && ((MethodInsnNode) l[i + 3]).desc.equals("(Lnet/minecraft/entity/Entity;)V")) {
                opisEnt++;
                aloadEnt = ((VarInsnNode) l[i + 2]).var;
            }
        }
        v.igual(1, chamadasTe, "World.updateEntities: exatamente 1 chamada TileEntity.updateEntity (o firewall recusa outra contagem)");
        v.sim(opisTe == 1 && aloadTe == 9, "padrao do Opis para tile entity intacto: [linha, ALOAD 9, INVOKEVIRTUAL] (achados "
                + opisTe + ", ALOAD " + aloadTe + ")");
        v.sim(opisEnt == 1 && aloadEnt == 2, "padrao do Opis para entidade intacto: [linha, ALOAD 0, ALOAD 2, INVOKEVIRTUAL]"
                + " (achados " + opisEnt + ", ALOAD " + aloadEnt + ")");
        boolean te9 = false;
        boolean ent2 = false;
        for (LocalVariableNode lv : m.localVariables) {
            te9 |= lv.index == 9 && lv.name.equals("tileentity");
            ent2 |= lv.index == 2 && lv.name.equals("entity");
        }
        v.sim(te9 && ent2, "locais do Opis: 9 = tileentity e 2 = entity (" + te9 + ", " + ent2 + ")");
        v.igual(14, m.maxLocals, "World.updateEntities: maxLocals 14 (o mesmo do jar base: nenhuma variavel local nova)");
        MethodNode f = metodo(world, "updateEntityWithOptionalForce", "(Lnet/minecraft/entity/Entity;Z)V");
        v.igual(13, f.maxLocals, "World.updateEntityWithOptionalForce: maxLocals 13 (o mesmo do jar base)");
        int floors = 0;
        for (AbstractInsnNode i : f.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).name.equals("floor_double")) {
                floors++;
            }
        }
        v.igual(5, floors, "updateEntityWithOptionalForce: 5 floor_double, como no base (o mixin do Bellow casa"
                + " floor_double(?.posY / 16.0) com allow=1)");
    }

    private static void servidor(ClassNode s) {
        MethodNode tick = metodo(s, "tick", "()V");
        int ini = 0;
        int fim = 0;
        for (AbstractInsnNode i : tick.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals("hoshiestar/medidor/Medidor")) {
                if (((MethodInsnNode) i).name.equals("inicioTickServidor")) {
                    ini++;
                }
                if (((MethodInsnNode) i).name.equals("fimTickServidor")) {
                    fim++;
                }
            }
        }
        v.sim(ini == 1 && fim == 1, "MinecraftServer.tick chama inicioTickServidor e fimTickServidor uma vez cada");
        MethodNode run = metodo(s, "run", "()V");
        int parar = 0;
        for (AbstractInsnNode i : run.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).name.equals("pararNoDesligamento")) {
                parar++;
            }
        }
        v.igual(1, parar, "MinecraftServer.run chama pararNoDesligamento uma vez (antes do evento de parada do FML)");
    }
}
