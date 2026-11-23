package hoshiestar.teste.vigia;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Classes geradas com ASM para os testes adversarios da revisao (trazidos de rev/vigia/adv):
 * <ul>
 * <li>{@link #malformada()}: classe aninhada cujo nome nao comeca pelo nome da classe externa (como
 * algumas do Scala); no Java 8, {@code Class.getSimpleName()} lanca InternalError nela;</li>
 * <li>{@link CarregadorQueAnota}: um "pacote de mod" {@code revpk.Pacote} com um campo de tipo
 * {@code revpk.Pesado} ainda nao carregado; o carregador anota cada classe revpk.* pedida e por qual
 * thread (prova se o vigia usa o class loader do jogo sem querer).</li>
 * </ul>
 */
final class ClassesGeradas {

    private ClassesGeradas() {
    }

    static Object malformada() throws Exception {
        final byte[] ext = aninhada("revgen/Externa", "revgen/Estranha", "revgen/Externa");
        final byte[] in = aninhada("revgen/Estranha", "revgen/Estranha", "revgen/Externa");
        ClassLoader cl = new ClassLoader(ClassesGeradas.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String n) throws ClassNotFoundException {
                byte[] b = n.equals("revgen.Externa") ? ext : n.equals("revgen.Estranha") ? in : null;
                if (b == null) {
                    throw new ClassNotFoundException(n);
                }
                return defineClass(n, b, 0, b.length);
            }
        };
        return cl.loadClass("revgen.Estranha").newInstance();
    }

    private static byte[] aninhada(String nome, String interna, String externa) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, nome, null, "java/lang/Object", null);
        cw.visitInnerClass(interna, externa, "Interna", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        cw.visitField(Opcodes.ACC_PUBLIC, "x", "I", null, null).visitEnd();
        construtor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void construtor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static byte[] simples(String nome, String[][] campos) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, nome, null, "java/lang/Object", null);
        for (String[] c : campos) {
            cw.visitField(Opcodes.ACC_PUBLIC, c[0], c[1], null, null).visitEnd();
        }
        construtor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Carregador do "jogo" de teste: define revpk.Pacote e revpk.Pesado e anota "classe @thread" de cada pedido revpk.*. */
    static final class CarregadorQueAnota extends ClassLoader {
        final List<String> pedidos = Collections.synchronizedList(new ArrayList<String>());
        private final byte[] pacote = simples("revpk/Pacote", new String[][] {{"pesado", "Lrevpk/Pesado;"}, {"x", "I"}});
        private final byte[] pesado = simples("revpk/Pesado", new String[0][]);

        CarregadorQueAnota() {
            super(ClassesGeradas.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("revpk.")) {
                pedidos.add(name + " @" + Thread.currentThread().getName());
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(String n) throws ClassNotFoundException {
            byte[] b = n.equals("revpk.Pacote") ? pacote : n.equals("revpk.Pesado") ? pesado : null;
            if (b == null) {
                throw new ClassNotFoundException(n);
            }
            return defineClass(n, b, 0, b.length);
        }
    }
}
