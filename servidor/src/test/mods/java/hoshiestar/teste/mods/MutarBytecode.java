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
package hoshiestar.teste.mods;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Ferramenta de teste de mutacao no BYTECODE de uma classe nossa ja construida (o que vai para o servidor): estraga de
 * proposito um ponto de um metodo, para ver se os testes acusam. Usada pelos scripts de teste com mods (a cola de
 * {@code GanchoShift}: nove inteiros posicionais que nenhum teste da decisao pura enxerga).
 *
 * <p>Uso: {@code MutarBytecode entrada.class saida.class metodo tipo args...}
 * <ul>
 * <li>{@code campo <dono> <nomeVelho> <nomeNovo> <ordinal>}: o N-esimo GETFIELD de dono.nomeVelho passa a ler nomeNovo;</li>
 * <li>{@code ret1para0}: a primeira sequencia {@code ICONST_1; IRETURN} vira {@code ICONST_0; IRETURN};</li>
 * <li>{@code getstaticbool <dono> <campo> <0 ou 1>}: o GETSTATIC booleano vira a constante.</li>
 * </ul>
 * Sai com erro se nada mudou (o padrao sumiu: a mutacao nao testaria nada).
 */
public final class MutarBytecode {

    private MutarBytecode() {
    }

    public static void main(String[] a) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(Paths.get(a[0]))).accept(cn, 0);
        int mudou = 0;
        for (Object o : cn.methods) {
            MethodNode mn = (MethodNode) o;
            if (!mn.name.equals(a[2])) {
                continue;
            }
            int ordinal = 0;
            for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                if (a[3].equals("campo") && in.getOpcode() == Opcodes.GETFIELD) {
                    FieldInsnNode f = (FieldInsnNode) in;
                    if (f.owner.equals(a[4]) && f.name.equals(a[5]) && ordinal++ == Integer.parseInt(a[7])) {
                        f.name = a[6];
                        mudou++;
                    }
                } else if (a[3].equals("ret1para0") && mudou == 0 && in.getOpcode() == Opcodes.ICONST_1
                        && in.getNext() != null && in.getNext().getOpcode() == Opcodes.IRETURN) {
                    mn.instructions.set(in, new InsnNode(Opcodes.ICONST_0));
                    mudou++;
                    break;
                } else if (a[3].equals("getstaticbool") && in.getOpcode() == Opcodes.GETSTATIC) {
                    FieldInsnNode f = (FieldInsnNode) in;
                    if (f.owner.equals(a[4]) && f.name.equals(a[5])) {
                        AbstractInsnNode n = new InsnNode(a[6].equals("1") ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                        mn.instructions.set(in, n);
                        in = n;
                        mudou++;
                    }
                }
            }
        }
        if (mudou == 0) {
            System.err.println("MutarBytecode: nada mudou (" + String.join(" ", a) + ")");
            System.exit(3);
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        Files.write(Paths.get(a[1]), cw.toByteArray());
    }
}
