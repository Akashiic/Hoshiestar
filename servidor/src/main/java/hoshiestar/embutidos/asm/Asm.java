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
package hoshiestar.embutidos.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Utilitarios de bytecode (ASM, API de arvore) para as correcoes embutidas em classes de mods.
 * Tres formatos de alteracao e um de leitura de campo (este em {@code CampoPrivado}):
 * <ol>
 * <li>{@link #ganchoNoInicio}: chama um gancho estatico na entrada de um metodo, com retorno
 * antecipado opcional;</li>
 * <li>{@link #trocarChamada}: troca UMA chamada especifica por uma chamada a um gancho estatico que
 * recebe o receptor e os argumentos (o gancho faz o que quiser, inclusive chamar o original);</li>
 * <li>{@link #envolverChamada}: envolve UMA chamada: um gancho decide se a chamada original
 * acontece; se nao, outro gancho da o valor. A chamada original continua DENTRO da classe do mod
 * (num metodo-ponte sintetico), entao vale para metodo privado da propria classe, que o gancho nao
 * poderia chamar. (Metodo protegido herdado de classe de outro pacote: o verificador exige que o
 * receptor seja da classe atual, e a ponte declara o receptor com o tipo do dono da chamada; NAO
 * verificado; prefira o formato 2 nesse caso.) A variante {@link #envolverChamadaProtegida} faz a
 * ponte seguir pela chamada original se qualquer gancho falhar (nao ligar, nao existir, lancar).</li>
 * </ol>
 *
 * <p><b>Falha fechada.</b> Cada utilitario confere tudo ANTES de mudar a arvore (metodo existe e
 * tem corpo; a chamada aparece exatamente o numero de vezes esperado; o gancho existe com o
 * descritor certo). Se algo nao confere, lanca {@link PadraoNaoEncontrado} sem ter mexido em
 * nada. Quem aplica ({@code Correcoes}) parte de uma arvore nova a cada correcao, entao nem um
 * erro inesperado no meio deixa a classe "meio alterada".
 *
 * <p><b>Quadros de pilha (StackMapTable).</b> A classe e lida com {@code EXPAND_FRAMES} e
 * gravada SEM {@code COMPUTE_FRAMES} (so {@code COMPUTE_MAXS}): os quadros originais ficam como
 * estao, e o utilitario acrescenta, explicitamente, so os quadros dos pontos de desvio que ele
 * cria. Esses pontos ficam sempre onde o estado e conhecido sem analise: a entrada do metodo
 * (locais = parametros, pilha vazia). Assim nao e preciso calcular a superclasse comum de tipos
 * (o que exigiria carregar classes no meio da transformacao). Regras por versao do .class:
 * <ul>
 * <li>Java 5 ou anterior (versao &lt; 50): nenhum quadro (o verificador antigo infere);</li>
 * <li>Java 6 (50): quadro so se o metodo ja tem quadros ou nao tem desvios (um .class 50 sem
 * quadros e verificado por inferencia; um quadro solto atrapalharia);</li>
 * <li>Java 7 em diante (51+): sempre.</li>
 * </ul>
 * Os tres formatos foram testados com classes compiladas para Java 6, 7 e 8
 * (servidor/src/test/java).
 */
public final class Asm {

    private static final String OBJETO = "java/lang/Object";

    /** Descritor do gancho {@code aoFalhar} do formato 3 protegido. */
    private static final String DESC_FALHOU = "(Ljava/lang/Throwable;)V";

    private Asm() {
    }

    // ------------------------------------------------------------------ leitura e escrita

    /** Le a classe numa arvore, com os quadros expandidos (necessario para acrescentar quadros). */
    public static ClassNode ler(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        return cn;
    }

    /**
     * Grava a arvore. So COMPUTE_MAXS: os quadros sao os da arvore. Le de novo o resultado para
     * garantir que e um .class bem formado.
     */
    public static byte[] escrever(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        byte[] b = cw.toByteArray();
        new ClassReader(b).accept(new ClassNode(), 0);
        return b;
    }

    // ------------------------------------------------------------------ formato 1

    /**
     * Formato 1: chama {@code inicio} na entrada do metodo.
     *
     * <p>Descritor do gancho {@code inicio}: os parametros do metodo, precedidos do receptor se o
     * metodo e de instancia (tipo da classe ou Object). Retorno: {@code void} se
     * {@code retornoAntecipado} e false; {@code boolean} se true (true = sair do metodo agora).
     *
     * <p>Com retorno antecipado num metodo nao-void, {@code valor} da o valor de retorno: mesmos
     * parametros, retorno do mesmo tipo do metodo. Em metodo void, {@code valor} deve ser null.
     *
     * <pre>
     * // metodo original:  double getEnergy()          (de instancia, em a/b/Matriz)
     * // ganchos:          static boolean pular(Object m)   static double valor(Object m)
     * // resultado:        if (Gancho.pular(this)) return Gancho.valor(this);  ...corpo original...
     * </pre>
     */
    public static void ganchoNoInicio(ClassNode cn, String metodo, String desc, Gancho inicio, Gancho valor,
            boolean retornoAntecipado, ResolvedorDeGanchos r) throws PadraoNaoEncontrado {
        noInicio(cn, metodo, desc, inicio, valor, retornoAntecipado, null, r);
    }

    /**
     * Formato 1 PROTEGIDO: igual a {@link #ganchoNoInicio}, mas se qualquer gancho FALHAR (a classe do gancho nao liga:
     * {@code NoClassDefFoundError}; o metodo dele nao existe com aquele descritor: {@code NoSuchMethodError}; ou ele
     * lanca qualquer {@code Throwable}) o metodo do mod segue pelo corpo ORIGINAL, em vez de deixar o erro sair de dentro
     * dele. Sem isso, um erro de ligacao sai do metodo do mod como {@code Error}, que o servidor nao captura (derruba).
     *
     * <pre>
     * try {
     *     if (inicio(this?, params...)) return valor(this?, params...);   // so com retorno antecipado
     * } catch (Throwable t) {
     *     aoFalhar(t);                                                    // so avisa; nunca lanca
     * }
     * ...corpo original, FORA do try...
     * </pre>
     *
     * O corpo original fica fora do {@code try}: o que ele lancar sai como sairia sem o gancho. O gancho {@code aoFalhar}
     * tem descritor {@code (Ljava/lang/Throwable;)V}, publico, estatico, de classe publica, e nao pode lancar; como ele
     * tem de ligar mesmo quando a classe dos outros ganchos nao liga, deve ficar numa classe do jar sem tipos do
     * Minecraft nem do mod. Sem falha, o caminho executado e o mesmo do formato 1 comum.
     */
    public static void ganchoNoInicioProtegido(ClassNode cn, String metodo, String desc, Gancho inicio, Gancho valor,
            boolean retornoAntecipado, Gancho aoFalhar, ResolvedorDeGanchos r) throws PadraoNaoEncontrado {
        if (aoFalhar == null) {
            throw new PadraoNaoEncontrado("gancho no inicio protegido precisa do gancho 'aoFalhar'");
        }
        noInicio(cn, metodo, desc, inicio, valor, retornoAntecipado, aoFalhar, r);
    }

    private static void noInicio(ClassNode cn, String metodo, String desc, Gancho inicio, Gancho valor,
            boolean retornoAntecipado, Gancho aoFalhar, ResolvedorDeGanchos r) throws PadraoNaoEncontrado {
        MethodNode mn = acharMetodoComCorpo(cn, metodo, desc);
        if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) {
            throw new PadraoNaoEncontrado("gancho no inicio de construtor/inicializador nao e suportado: " + metodo);
        }
        boolean estatico = (mn.access & Opcodes.ACC_STATIC) != 0;
        Type ret = Type.getReturnType(desc);
        String receptor = estatico ? null : cn.name;

        String descInicio = resolver(r, inicio, receptor, desc, retornoAntecipado ? Type.BOOLEAN_TYPE : Type.VOID_TYPE);
        String descValor = null;
        if (retornoAntecipado && ret.getSort() != Type.VOID) {
            if (valor == null) {
                throw new PadraoNaoEncontrado("metodo " + metodo + desc + " devolve valor: o retorno antecipado precisa do gancho 'valor'");
            }
            descValor = resolver(r, valor, receptor, desc, ret);
        } else if (valor != null) {
            throw new PadraoNaoEncontrado("gancho 'valor' so faz sentido com retorno antecipado em metodo que devolve valor");
        }
        String descFalhou = null;
        if (aoFalhar != null) {
            descFalhou = r.descritorDeclarado(aoFalhar.dono, aoFalhar.nome, DESC_FALHOU);
            if (descFalhou == null) {
                throw new PadraoNaoEncontrado("gancho ausente: " + aoFalhar + " com descritor " + DESC_FALHOU
                        + ", public static, em classe publica");
            }
        }

        // tudo conferido: daqui para baixo so altera
        boolean quadros = precisaQuadro(cn, mn); // antes de acrescentar o tratador (ele conta como desvio)
        InsnList il = new InsnList();
        LabelNode comeco = new LabelNode();
        LabelNode fim = new LabelNode();
        LabelNode tratador = new LabelNode();
        LabelNode continuar = new LabelNode();
        if (aoFalhar != null) {
            il.add(comeco);
        }
        carregarParametros(il, estatico, desc, 0);
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, inicio.dono, inicio.nome, descInicio, false));
        if (retornoAntecipado) {
            il.add(new JumpInsnNode(Opcodes.IFEQ, continuar));
            if (ret.getSort() == Type.VOID) {
                il.add(new InsnNode(Opcodes.RETURN));
            } else {
                carregarParametros(il, estatico, desc, 0);
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, valor.dono, valor.nome, descValor, false));
                il.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            }
        } else if (aoFalhar != null) {
            il.add(new JumpInsnNode(Opcodes.GOTO, continuar)); // pula o tratador
        }
        if (aoFalhar != null) {
            il.add(fim);
            il.add(tratador);
            if (quadros) {
                il.add(quadroDeEntrada(estatico ? null : cn.name, desc, "java/lang/Throwable"));
            }
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, aoFalhar.dono, aoFalhar.nome, descFalhou, false));
        }
        if (retornoAntecipado || aoFalhar != null) {
            il.add(continuar);
            if (quadros && !proximoRealEQuadro(mn.instructions.getFirst())) {
                il.add(quadroDeEntrada(estatico ? null : cn.name, desc));
            }
        }
        if (aoFalhar != null) {
            mn.tryCatchBlocks.add(new TryCatchBlockNode(comeco, fim, tratador, "java/lang/Throwable"));
        }
        mn.instructions.insert(il);
    }

    // ------------------------------------------------------------------ formato 2

    /**
     * Formato 2: troca a chamada {@code alvo} de numero {@code ordinal} (0 = a primeira) dentro do
     * metodo por {@code invokestatic gancho}. O metodo tem de conter exatamente
     * {@code totalEsperado} chamadas iguais a {@code alvo} (invokevirtual, invokeinterface,
     * invokestatic, ou invokespecial de metodo privado da propria classe; chamada a super e construtor
     * nao sao suportados).
     *
     * <p>Descritor do gancho: o da chamada, precedido do receptor se ela e de instancia (tipo do dono
     * da chamada ou Object). A pilha antes e depois e a mesma, entao nenhum quadro muda.
     */
    public static void trocarChamada(ClassNode cn, String metodo, String desc, Chamada alvo, int ordinal,
            int totalEsperado, Gancho gancho, ResolvedorDeGanchos r) throws PadraoNaoEncontrado {
        MethodNode mn = acharMetodoComCorpo(cn, metodo, desc);
        MethodInsnNode chamada = acharChamada(cn, mn, alvo, ordinal, totalEsperado);
        boolean estatica = chamada.getOpcode() == Opcodes.INVOKESTATIC;
        String descGancho = resolver(r, gancho, estatica ? null : alvo.dono, alvo.desc, Type.getReturnType(alvo.desc));
        mn.instructions.set(chamada, new MethodInsnNode(Opcodes.INVOKESTATIC, gancho.dono, gancho.nome, descGancho, false));
    }

    // ------------------------------------------------------------------ formato 3

    /**
     * Formato 3: envolve a chamada {@code alvo} de numero {@code ordinal}. A chamada passa a ir para um
     * metodo-ponte privado, estatico e sintetico criado na propria classe
     * ({@code hoshiestar$envolve$N}), que faz:
     *
     * <pre>
     * if (deveChamar(receptor, args...)) return receptor.alvo(args...);   // a chamada original
     * return semChamar(receptor, args...);                                // ou so "return" se void
     * </pre>
     *
     * Descritores: {@code deveChamar} recebe o mesmo que a chamada (receptor primeiro, se de
     * instancia; tipo do dono ou Object) e devolve boolean; {@code semChamar} recebe o mesmo e
     * devolve o tipo da chamada (null se a chamada e void). Mesmas regras de contagem do formato 2.
     * Nao vale em interface (metodo privado em interface so existe a partir do Java 9).
     */
    public static void envolverChamada(ClassNode cn, String metodo, String desc, Chamada alvo, int ordinal,
            int totalEsperado, Gancho deveChamar, Gancho semChamar, ResolvedorDeGanchos r) throws PadraoNaoEncontrado {
        envolver(cn, metodo, desc, alvo, ordinal, totalEsperado, deveChamar, semChamar, null, r);
    }

    /**
     * Formato 3 PROTEGIDO: igual a {@link #envolverChamada}, mas se qualquer gancho FALHAR a ponte segue pela
     * chamada original, em vez de deixar o erro sair de dentro do metodo do mod. Falhar = a classe do gancho
     * nao ligar ({@code NoClassDefFoundError}), o metodo do gancho nao existir com aquele descritor
     * ({@code NoSuchMethodError}, {@code LinkageError} em geral) ou o gancho lancar qualquer {@code Throwable}.
     * Sem isso, um erro de ligacao sai do metodo do mod como {@code Error}, que o servidor nao captura (derruba).
     *
     * <pre>
     * try {
     *     if (!deveChamar(receptor, args...)) return semChamar(receptor, args...);   // ou so "return" se void
     * } catch (Throwable t) {
     *     aoFalhar(t);                                                               // so avisa; nunca lanca
     * }
     * return receptor.alvo(args...);                                                 // a chamada original, FORA do try
     * </pre>
     *
     * A chamada original fica fora do {@code try}: o que ela lancar sai como sairia sem o gancho. O gancho
     * {@code aoFalhar} tem descritor {@code (Ljava/lang/Throwable;)V}, e tem de ser publico, estatico, de classe
     * publica, e nao lancar. Se ele proprio nao ligar, o erro dele sai (por isso deve ficar numa classe do
     * jar sem tipos do Minecraft nem do mod). Sem falha, o caminho executado e o mesmo do formato 3.
     */
    public static void envolverChamadaProtegida(ClassNode cn, String metodo, String desc, Chamada alvo, int ordinal,
            int totalEsperado, Gancho deveChamar, Gancho semChamar, Gancho aoFalhar, ResolvedorDeGanchos r)
            throws PadraoNaoEncontrado {
        if (aoFalhar == null) {
            throw new PadraoNaoEncontrado("envolver chamada protegida precisa do gancho 'aoFalhar'");
        }
        envolver(cn, metodo, desc, alvo, ordinal, totalEsperado, deveChamar, semChamar, aoFalhar, r);
    }

    private static void envolver(ClassNode cn, String metodo, String desc, Chamada alvo, int ordinal,
            int totalEsperado, Gancho deveChamar, Gancho semChamar, Gancho aoFalhar, ResolvedorDeGanchos r)
            throws PadraoNaoEncontrado {
        if ((cn.access & Opcodes.ACC_INTERFACE) != 0) {
            throw new PadraoNaoEncontrado("envolver chamada dentro de interface nao e suportado: " + cn.name);
        }
        MethodNode mn = acharMetodoComCorpo(cn, metodo, desc);
        MethodInsnNode chamada = acharChamada(cn, mn, alvo, ordinal, totalEsperado);
        boolean estatica = chamada.getOpcode() == Opcodes.INVOKESTATIC;
        String receptor = estatica ? null : alvo.dono;
        Type ret = Type.getReturnType(alvo.desc);
        String descDeve = resolver(r, deveChamar, receptor, alvo.desc, Type.BOOLEAN_TYPE);
        String descSem = null;
        if (ret.getSort() != Type.VOID) {
            if (semChamar == null) {
                throw new PadraoNaoEncontrado("a chamada " + alvo + " devolve valor: precisa do gancho 'semChamar'");
            }
            descSem = resolver(r, semChamar, receptor, alvo.desc, ret);
        } else if (semChamar != null) {
            descSem = resolver(r, semChamar, receptor, alvo.desc, Type.VOID_TYPE);
        }
        String descFalhou = null;
        if (aoFalhar != null) {
            descFalhou = r.descritorDeclarado(aoFalhar.dono, aoFalhar.nome, DESC_FALHOU);
            if (descFalhou == null) {
                throw new PadraoNaoEncontrado("gancho ausente: " + aoFalhar + " com descritor " + DESC_FALHOU
                        + ", public static, em classe publica");
            }
        }

        // descritor da ponte: receptor (tipo exato do dono da chamada) + argumentos -> retorno
        String descPonte = descritor(receptor, alvo.desc, ret);
        String nomePonte = nomeLivre(cn, "hoshiestar$envolve$");
        boolean comQuadros = (cn.version & 0xFFFF) >= Opcodes.V1_6;

        MethodNode ponte = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                nomePonte, descPonte, null, null);
        InsnList il = ponte.instructions;
        if (aoFalhar == null) {
            LabelNode pular = new LabelNode();
            carregarParametros(il, true, descPonte, 0);
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, deveChamar.dono, deveChamar.nome, descDeve, false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, pular));
            carregarParametros(il, true, descPonte, 0);
            il.add(new MethodInsnNode(chamada.getOpcode(), chamada.owner, chamada.name, chamada.desc, chamada.itf));
            il.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            il.add(pular);
            if (comQuadros) {
                il.add(quadroDeEntrada(null, descPonte));
            }
            if (descSem != null) {
                carregarParametros(il, true, descPonte, 0);
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, semChamar.dono, semChamar.nome, descSem, false));
            }
            il.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        } else {
            // try { if (!deve(...)) return sem(...); } catch (Throwable t) { aoFalhar(t); }  return original(...);
            LabelNode inicio = new LabelNode();
            LabelNode fim = new LabelNode();
            LabelNode tratador = new LabelNode();
            LabelNode original = new LabelNode();
            il.add(inicio);
            carregarParametros(il, true, descPonte, 0);
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, deveChamar.dono, deveChamar.nome, descDeve, false));
            il.add(new JumpInsnNode(Opcodes.IFNE, original));
            if (descSem != null) {
                carregarParametros(il, true, descPonte, 0);
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, semChamar.dono, semChamar.nome, descSem, false));
            }
            il.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            il.add(fim);
            il.add(tratador);
            if (comQuadros) {
                il.add(quadroDeEntrada(null, descPonte, "java/lang/Throwable"));
            }
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, aoFalhar.dono, aoFalhar.nome, descFalhou, false));
            il.add(original);
            if (comQuadros) {
                il.add(quadroDeEntrada(null, descPonte));
            }
            carregarParametros(il, true, descPonte, 0);
            il.add(new MethodInsnNode(chamada.getOpcode(), chamada.owner, chamada.name, chamada.desc, chamada.itf));
            il.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            ponte.tryCatchBlocks.add(new TryCatchBlockNode(inicio, fim, tratador, "java/lang/Throwable"));
        }
        ponte.maxLocals = tamanhoParametros(descPonte);
        ponte.maxStack = Math.max(1, ponte.maxLocals) + 2; // recalculado pelo COMPUTE_MAXS

        cn.methods.add(ponte);
        mn.instructions.set(chamada, new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, nomePonte, descPonte, false));
    }

    // ------------------------------------------------------------------ auxiliares

    /** O metodo com este nome e descritor, que precisa ter corpo (nao abstrato nem nativo). */
    public static MethodNode acharMetodoComCorpo(ClassNode cn, String metodo, String desc) throws PadraoNaoEncontrado {
        MethodNode achado = null;
        for (MethodNode m : cn.methods) {
            if (m.name.equals(metodo) && m.desc.equals(desc)) {
                if (achado != null) {
                    throw new PadraoNaoEncontrado("metodo repetido (classe invalida?): " + metodo + desc);
                }
                achado = m;
            }
        }
        if (achado == null) {
            throw new PadraoNaoEncontrado("metodo nao encontrado: " + cn.name + "." + metodo + desc);
        }
        if ((achado.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || achado.instructions.size() == 0) {
            throw new PadraoNaoEncontrado("metodo sem corpo (abstrato ou nativo): " + metodo + desc);
        }
        return achado;
    }

    /**
     * Conta as chamadas iguais a {@code alvo} e devolve a de numero {@code ordinal}. invokespecial so
     * e aceito para metodo privado da propria classe (o javac ate o Java 10 chama metodo privado com
     * invokespecial); chamada a super e construtor sao recusados.
     */
    static MethodInsnNode acharChamada(ClassNode cn, MethodNode mn, Chamada alvo, int ordinal, int totalEsperado)
            throws PadraoNaoEncontrado {
        if (ordinal < 0 || totalEsperado < 1 || ordinal >= totalEsperado) {
            throw new PadraoNaoEncontrado("ordinal/total invalidos: " + ordinal + "/" + totalEsperado);
        }
        List<MethodInsnNode> achadas = new ArrayList<MethodInsnNode>();
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) n;
                if (m.owner.equals(alvo.dono) && m.name.equals(alvo.nome) && m.desc.equals(alvo.desc)) {
                    if (m.getOpcode() == Opcodes.INVOKESPECIAL && (!m.owner.equals(cn.name) || m.name.startsWith("<"))) {
                        throw new PadraoNaoEncontrado("chamada invokespecial (super ou construtor) nao suportada: " + alvo);
                    }
                    achadas.add(m);
                }
            }
        }
        if (achadas.size() != totalEsperado) {
            throw new PadraoNaoEncontrado("esperava " + totalEsperado + " chamada(s) a " + alvo + " em " + mn.name + mn.desc
                    + ", achei " + achadas.size());
        }
        return achadas.get(ordinal);
    }

    /**
     * Confere o gancho e devolve o descritor que ele de fato declara: primeiro com o receptor no tipo
     * exato, depois com o receptor como Object.
     */
    private static String resolver(ResolvedorDeGanchos r, Gancho g, String receptor, String descAlvo, Type ret)
            throws PadraoNaoEncontrado {
        if (g == null) {
            throw new PadraoNaoEncontrado("gancho nao informado");
        }
        String exato = descritor(receptor, descAlvo, ret);
        String comObjeto = receptor == null ? null : descritor(OBJETO, descAlvo, ret);
        String d = r.descritorDeclarado(g.dono, g.nome, exato, comObjeto);
        if (d == null) {
            throw new PadraoNaoEncontrado("gancho ausente: " + g + " com descritor " + exato
                    + (comObjeto != null ? " (ou " + comObjeto + ")" : "") + ", public static, em classe publica");
        }
        return d;
    }

    /** "(" + [receptor] + argumentos de descAlvo + ")" + retorno. */
    static String descritor(String receptor, String descAlvo, Type ret) {
        StringBuilder sb = new StringBuilder("(");
        if (receptor != null) {
            sb.append('L').append(receptor).append(';');
        }
        for (Type t : Type.getArgumentTypes(descAlvo)) {
            sb.append(t.getDescriptor());
        }
        return sb.append(')').append(ret.getDescriptor()).toString();
    }

    /** Empilha "this" (se de instancia) e os parametros do descritor, a partir do slot dado. */
    private static void carregarParametros(InsnList il, boolean estatico, String desc, int inicio) {
        int slot = inicio;
        if (!estatico) {
            il.add(new VarInsnNode(Opcodes.ALOAD, slot));
            slot++;
        }
        for (Type t : Type.getArgumentTypes(desc)) {
            il.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
            slot += t.getSize();
        }
    }

    private static int tamanhoParametros(String desc) {
        int n = 0;
        for (Type t : Type.getArgumentTypes(desc)) {
            n += t.getSize();
        }
        return n;
    }

    /** Quadro completo da entrada de um metodo: locais = [this] + parametros; pilha vazia. */
    static FrameNode quadroDeEntrada(String dono, String desc) {
        return quadroDeEntrada(dono, desc, (String[]) null);
    }

    /** Idem, com a pilha contendo os tipos dados (nomes internos), por exemplo o Throwable de um tratador. */
    static FrameNode quadroDeEntrada(String dono, String desc, String... pilha) {
        List<Object> locais = new ArrayList<Object>();
        if (dono != null) {
            locais.add(dono);
        }
        for (Type t : Type.getArgumentTypes(desc)) {
            switch (t.getSort()) {
                case Type.BOOLEAN:
                case Type.CHAR:
                case Type.BYTE:
                case Type.SHORT:
                case Type.INT:
                    locais.add(Opcodes.INTEGER);
                    break;
                case Type.FLOAT:
                    locais.add(Opcodes.FLOAT);
                    break;
                case Type.LONG:
                    locais.add(Opcodes.LONG);
                    break;
                case Type.DOUBLE:
                    locais.add(Opcodes.DOUBLE);
                    break;
                case Type.ARRAY:
                    locais.add(t.getDescriptor());
                    break;
                default:
                    locais.add(t.getInternalName());
                    break;
            }
        }
        Object[] p = pilha == null ? new Object[0] : pilha.clone();
        return new FrameNode(Opcodes.F_NEW, locais.size(), locais.toArray(), p.length, p);
    }

    /** Se o metodo precisa de quadro no ponto de desvio criado na entrada (regras da classe). */
    static boolean precisaQuadro(ClassNode cn, MethodNode mn) {
        int versao = cn.version & 0xFFFF;
        if (versao < Opcodes.V1_6) {
            return false;
        }
        if (versao > Opcodes.V1_6) {
            return true;
        }
        boolean temQuadro = false;
        boolean temDesvio = mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty();
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof FrameNode) {
                temQuadro = true;
            } else if (n instanceof JumpInsnNode || n instanceof TableSwitchInsnNode || n instanceof LookupSwitchInsnNode) {
                temDesvio = true;
            }
        }
        return temQuadro || !temDesvio;
    }

    /** true se, pulando rotulos e numeros de linha, o primeiro no a partir de n e um quadro. */
    static boolean proximoRealEQuadro(AbstractInsnNode n) {
        while (n != null && (n instanceof LabelNode || n instanceof LineNumberNode)) {
            n = n.getNext();
        }
        return n instanceof FrameNode;
    }

    private static String nomeLivre(ClassNode cn, String prefixo) {
        for (int i = 0;; i++) {
            String nome = prefixo + i;
            boolean usado = false;
            for (MethodNode m : cn.methods) {
                if (m.name.equals(nome)) {
                    usado = true;
                    break;
                }
            }
            if (!usado) {
                return nome;
            }
        }
    }
}
