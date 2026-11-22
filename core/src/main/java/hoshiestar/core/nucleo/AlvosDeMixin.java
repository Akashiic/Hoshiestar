/*
 * Hoshiestar-Core: base comum dos recursos da Etapa 1 do projeto Hoshiestar.
 * Copyright (C) 2026 Projeto Hoshiestar
 *
 * Este programa e software livre: voce pode redistribui-lo e/ou modifica-lo
 * sob os termos da GNU General Public License publicada pela Free Software
 * Foundation, versao 3 da Licenca ou (a seu criterio) qualquer versao posterior.
 *
 * Este programa e distribuido na esperanca de que seja util, mas SEM QUALQUER
 * GARANTIA; sem mesmo a garantia implicita de COMERCIABILIDADE ou ADEQUACAO A
 * UM PROPOSITO ESPECIFICO. Veja a GNU General Public License para mais detalhes.
 *
 * Voce deve ter recebido uma copia da GNU General Public License junto com este
 * programa. Se nao, veja <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package hoshiestar.core.nucleo;

import org.spongepowered.asm.lib.AnnotationVisitor;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.ClassVisitor;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.Type;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Le, do bytecode da classe de mixin (sem carrega-la), as classes alvo da anotacao @Mixin
 * ("value" e "targets"). So para diagnostico: dizer qual classe alvo ainda nao foi carregada.
 */
final class AlvosDeMixin {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private AlvosDeMixin() {
    }

    static List<String> ler(String mixinCompleto) {
        final List<String> alvos = new ArrayList<String>();
        try {
            ClassLoader cl = AlvosDeMixin.class.getClassLoader();
            InputStream is = cl.getResourceAsStream(mixinCompleto.replace('.', '/') + ".class");
            if (is == null) {
                return alvos;
            }
            byte[] b;
            try {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] x = new byte[4096];
                int r;
                while ((r = is.read(x)) > 0) {
                    o.write(x, 0, r);
                }
                b = o.toByteArray();
            } finally {
                is.close();
            }
            new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (!MIXIN.equals(desc)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM5) {
                        @Override
                        public AnnotationVisitor visitArray(final String nome) {
                            return new AnnotationVisitor(Opcodes.ASM5) {
                                @Override
                                public void visit(String n, Object v) {
                                    if ("value".equals(nome) && v instanceof Type) {
                                        alvos.add(((Type) v).getClassName());
                                    } else if ("targets".equals(nome) && v instanceof String) {
                                        alvos.add(((String) v).replace('/', '.'));
                                    }
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable t) {
            // so diagnostico
        }
        return alvos;
    }
}
