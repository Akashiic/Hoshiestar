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
package hoshiestar.teste.chromaticraft;

import hoshiestar.api.Recursos;
import hoshiestar.chromaticraft.ChromatiCraft;
import hoshiestar.chromaticraft.DecisaoShift;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import hoshiestar.teste.Verifica;
import hoshiestar.teste.mods.CarregadorMods;
import hoshiestar.teste.mods.RemapeadorSrg;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Printer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Teste isolado do transformador do recurso "chromaticraft" (item 1.B) sobre a classe REAL
 * {@code Reika.ChromatiCraft.Auxiliary.Ability.AbilityCalls} (jar "ChromatiCraft 1.7.10 V33a.jar", so pelo classpath),
 * com as classes do DragonAPI, o Minecraft vanilla e as NOSSAS classes tiradas do server.jar construido (os bytes que
 * vao para o servidor), com os nomes do Minecraft traduzidos como o FML faz ao carregar.
 *
 * <p>O FML nao roda aqui, entao duas coisas que ele faz no servidor sao imitadas so no teste: tirar da classe os
 * metodos {@code @SideOnly(CLIENT)} (o servidor nao tem as classes de cliente que eles citam) e uma cola vazia da
 * interface do mod VoidMonster (o FML a tira de {@code EntityMonsterBait} quando o mod falta). A ordem e a do servidor:
 * a nossa transformacao primeiro, depois a retirada dos metodos de cliente.
 *
 * <p>Prova: (1) a correcao e aplicada e a classe passa no CheckClassAdapter e no verificador da JVM; (2) o gancho e a
 * PRIMEIRA coisa do metodo: as instrucoes antes das originais sao so o carregamento dos 5 parametros, a chamada do gancho,
 * o desvio, o retorno antecipado e o tratador do try/catch protegido; (3) todo o resto do metodo e identico ao original,
 * instrucao por instrucao (nenhuma mudanca dentro do laco); a segunda correcao troca exatamente 2 chamadas por chamadas
 * a pontes protegidas; (4) casos de recusa (desligada, trava, travas
 * das dependencias, gancho ausente, classe ja transformada); (5) execucao: o gancho e chamado antes do corpo original
 * (recusa devolve false sem rodar o original; aceita deixa o original rodar); gancho que nao liga (metodo ausente) NAO
 * derruba: o corpo original roda e a falha e contada; (6) config, linha de estado, subcomando.
 *
 * <p>Argumentos: server.jar construido, pasta dos jars "-srg", jar do ChromatiCraft, jar do DragonAPI, jar vanilla,
 * pasta das classes de teste, pasta das colas.
 */
public final class TesteChromatiCraft {

    static final String CLASSE = ChromatiCraft.CLASSE;
    static final String GANCHO = "hoshiestar/chromaticraft/shift/GanchoShift";
    static final String DESC = "(Lnet/minecraft/world/WorldServer;LReika/DragonAPI/Instantiable/Data/Immutable/BlockBox;"
            + "Lnet/minecraftforge/common/util/ForgeDirection;ILnet/minecraft/entity/player/EntityPlayerMP;)Z";
    static final String FALHAS = "hoshiestar/chromaticraft/ChromatiCraft";
    static final String PONTE = "Reika/ChromatiCraft/Auxiliary/Ability/AbilityCalls.hoshiestar$envolve$";

    private TesteChromatiCraft() {
    }

    public static void main(String[] a) throws Exception {
        final Verifica v = new Verifica("TesteChromatiCraft");
        File jarServidor = new File(a[0]);
        File srg = new File(a[1]);
        File cc = new File(a[2]);
        File da = new File(a[3]);
        File vanilla = new File(a[4]);
        File classesTeste = new File(a[5]);
        File colas = new File(a[6]);
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
        final ResolvedorDeGanchos ganchos = new ResolvedorDeGanchos(soOJar, new ResolvedorDeGanchos.Remapeador() {
            public String descritor(String d) {
                return rem.mapMethodDesc(d);
            }
        });
        final URL[] fontes = {classesTeste.toURI().toURL(), colas.toURI().toURL(), mcSrg.toURI().toURL(),
            servidorSrg.toURI().toURL(), cc.toURI().toURL(), da.toURI().toURL(), vanilla.toURI().toURL()};

        File tmp = new File(classesTeste.getParentFile(), "config-cc");
        tmp.mkdirs();
        File yml = new File(tmp, "hoshiestar.yml");

        // ============================================================ config
        System.out.println("-- config");
        yml.delete();
        HoshiestarConfig cfg = HoshiestarConfig.carregar(yml);
        v.igual(false, cfg.chromaticraft_ligado, "config: ligado padrao = false (recurso inteiro DESLIGADO)");
        v.igual(1000, cfg.chromaticraft_intervalo, "config: intervalo padrao = 1000 ms");
        v.igual(true, cfg.chromaticraft_distancia, "config: distancia padrao = true");
        v.igual(4096, cfg.chromaticraft_volume, "config: volume padrao = 4096");
        v.igual(true, cfg.chromaticraft_altura, "config: altura padrao = true");
        v.igual(true, cfg.chromaticraft_chunks, "config: chunks padrao = true");
        v.igual("Mover area recusado: {motivo}.", cfg.chromaticraft_mensagem, "config: mensagem padrao");
        v.igual(50, cfg.chromaticraft_loglento, "config: loglento padrao = 50 ms");
        v.igual(10, cfg.chromaticraft_logsegundos, "config: logsegundos padrao = 10");
        v.igual(0, cfg.avisos().size(), "config: arquivo novo sem avisos");
        HoshiestarConfig de_novo = HoshiestarConfig.carregar(yml);
        v.igual(0, de_novo.avisos().size(), "config: o arquivo gravado le de volta sem avisos");
        v.igual("Mover area recusado: {motivo}.", de_novo.chromaticraft_mensagem, "config: a mensagem com {motivo} volta igual do arquivo");
        v.igual(4096, de_novo.chromaticraft_volume, "config: valores voltam iguais");
        String texto = new String(java.nio.file.Files.readAllBytes(yml.toPath()), "UTF-8");
        v.sim(texto.contains("chromaticraft:") && texto.contains("ligado: false") && texto.contains("intervalo: 1000")
                && texto.contains("volume: 4096") && texto.contains("loglento: 50"), "config: o hoshiestar.yml tem a secao chromaticraft com as chaves");
        // valor invalido: item no padrao + aviso; chaves desconhecidas avisadas
        File yml2 = new File(tmp, "hoshiestar-ruim.yml");
        java.nio.file.Files.write(yml2.toPath(), ("chromaticraft:\n  ligado: sim\n  volume: muitos\n  intervalo: 250\n  volum: 5\n").getBytes("UTF-8"));
        HoshiestarConfig ruim = HoshiestarConfig.carregar(yml2);
        v.sim(ruim.avisos().size() >= 3 && !ruim.chromaticraft_ligado && ruim.chromaticraft_volume == 4096 && ruim.chromaticraft_intervalo == 250,
                "config: 'ligado: sim', 'volume: muitos' e 'volum' dao aviso; item invalido no padrao; intervalo valido vale (" + ruim.avisos().size() + " avisos)");

        // ============================================================ registro e transformador
        System.out.println("-- transformador sobre a classe real");
        cfg.chromaticraft_ligado = true;
        final Correcoes correcoes = Correcoes.novoParaTeste();
        final byte[][] guardados = new byte[3][];
        final CarregadorMods[] ref = new CarregadorMods[1];
        CarregadorMods cl = new CarregadorMods(fontes, TesteChromatiCraft.class.getClassLoader(), new CarregadorMods.Transformador() {
            public byte[] transformar(String nome, byte[] bytes) {
                byte[] r = bytes;
                if (correcoes.ligadasPara(nome) != null) {
                    r = correcoes.aplicar(nome, bytes, ganchos, ref[0]);
                    guardados[0] = bytes;
                    guardados[1] = r;
                }
                // a ordem do servidor: nossa transformacao primeiro, depois o FML tira os metodos de cliente
                return nome.startsWith("Reika.") ? semMetodosDeCliente(r) : r;
            }
        });
        ref[0] = cl;
        Recursos.limparParaTeste();
        ChromatiCraft.registrar(cfg, correcoes, cl);
        correcoes.fechar();
        v.sim(Recursos.achar(ChromatiCraft.ID) != null && Recursos.achar(ChromatiCraft.ID).ligado, "registro: recurso aparece como ligado");
        v.igual(2, correcoes.doRecurso(ChromatiCraft.ID).size(), "registro: duas correcoes (decisao e medicao), as duas na mesma classe");
        v.sim(ChromatiCraft.decisao != null && ChromatiCraft.ativo, "registro: decisao criada e ativa");
        v.igual(1000L, ChromatiCraft.decisao.intervaloMs, "registro: a decisao leva o intervalo do config");
        v.igual(4096L, ChromatiCraft.decisao.volumeMaximo, "registro: a decisao leva o volume do config");
        Class<?> k = Class.forName(CLASSE, true, cl); // carrega, liga e inicializa: o verificador da JVM confere TODOS os metodos
        Correcao cDecide = correcoes.doRecurso(ChromatiCraft.ID).get(0);
        Correcao cMede = correcoes.doRecurso(ChromatiCraft.ID).get(1);
        v.igual(Correcao.Estado.APLICADA, cDecide.estado(), "correcao da decisao aplicada na classe real (" + cDecide.linha() + ")");
        v.igual(Correcao.Estado.APLICADA, cMede.estado(), "correcao da medicao aplicada na classe real (" + cMede.linha() + ")");
        v.sim(k.getClassLoader() == cl, "classe carregada pelo class loader de teste (verificador da JVM ligado)");
        v.sim(guardados[1] != null && guardados[1] != guardados[0], "bytes transformados");
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(semMetodosDeCliente(guardados[1])), cl, false, new PrintWriter(sw));
        v.sim(sw.toString().isEmpty(), "CheckClassAdapter sem erros" + (sw.toString().isEmpty() ? "" : "\n" + sw));
        final byte[] original = guardados[0];
        final byte[] transformada = guardados[1];
        gravar(new File(tmp, "AbilityCalls-transformada.class"), transformada);

        // o hash da classe do jar e o da trava (recontado aqui, do arquivo)
        v.igual(ChromatiCraft.SHA_ABILITYCALLS, hoshiestar.api.HashClasse.sha256(original), "os bytes originais tem o SHA-256 da trava");

        // ---- forma: o gancho e a primeira coisa; o resto e identico ao original
        ClassNode cnOrig = Asm.ler(original);
        ClassNode cnNova = Asm.ler(transformada);
        MethodNode mOrig = metodo(cnOrig, "shiftArea");
        MethodNode mNova = metodo(cnNova, "shiftArea");
        List<String> iOrig = instrucoes(mOrig, 0);
        List<String> iNova = instrucoes(mNova, 0);
        // prefixo esperado: carrega os 5 parametros, chama recusar, desvia; se true carrega de novo e devolve valorRecusado
        String[] prefixo = {
            "ALOAD 0", "ALOAD 1", "ALOAD 2", "ILOAD 3", "ALOAD 4",
            "INVOKESTATIC " + GANCHO + ".recusar" + DESC,
            "IFEQ ->",
            "ALOAD 0", "ALOAD 1", "ALOAD 2", "ILOAD 3", "ALOAD 4",
            "INVOKESTATIC " + GANCHO + ".valorRecusado" + DESC,
            "IRETURN",
            "INVOKESTATIC " + FALHAS + ".falhaGancho(Ljava/lang/Throwable;)V", // o tratador do try/catch protegido
        };
        int p = prefixo.length;
        boolean prefixoCerto = iNova.size() > p;
        for (int i = 0; prefixoCerto && i < p; i++) {
            if (!iNova.get(i).startsWith(prefixo[i])) {
                prefixoCerto = false;
                System.out.println("  prefixo[" + i + "]: esperado '" + prefixo[i] + "', visto '" + iNova.get(i) + "'");
            }
        }
        v.sim(prefixoCerto, "o gancho 'recusar' e chamado ANTES de qualquer instrucao original: so os 5 carregamentos de parametro, a chamada, o desvio, o retorno antecipado ('valorRecusado') e o tratador ('falhaGancho')");
        v.sim(prefixoCerto && iNova.get(6).equals("IFEQ ->" + p), "o desvio do gancho cai exatamente na primeira instrucao original (o tratador e pulado)");
        // o try/catch protegido: um bloco a mais, de Throwable, cobrindo so o prefixo (nenhuma instrucao original dentro)
        v.igual(mOrig.tryCatchBlocks.size() + 1, mNova.tryCatchBlocks.size(), "um bloco try/catch a mais (o protegido)");
        {
            TryCatchBlockNode nosso = (TryCatchBlockNode) mNova.tryCatchBlocks.get(mNova.tryCatchBlocks.size() - 1);
            int antesDoInicio = 0;
            int dentro = 0;
            boolean dentroDoBloco = false;
            boolean viuInicio = false;
            for (AbstractInsnNode n = mNova.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n == nosso.start) {
                    dentroDoBloco = true;
                    viuInicio = true;
                } else if (n == nosso.end) {
                    dentroDoBloco = false;
                }
                if (n.getOpcode() >= 0) {
                    if (dentroDoBloco) {
                        dentro++;
                    } else if (!viuInicio) {
                        antesDoInicio++;
                    }
                }
            }
            v.sim("java/lang/Throwable".equals(nosso.type) && antesDoInicio == 0, "o try/catch protegido e de Throwable e comeca na primeira instrucao do metodo");
            v.igual(p - 1, dentro, "o try/catch cobre so as instrucoes do prefixo antes do tratador; o corpo original fica fora");
        }
        v.igual("NEW Reika/DragonAPI/Instantiable/Data/BlockStruct/FilledBlockArray", primeiraReal(mOrig),
                "controle: a primeira instrucao original do metodo e o 'new FilledBlockArray' (antes de qualquer leitura de mundo)");
        // depois do prefixo: identico ao original, exceto as duas chamadas trocadas pela segunda correcao
        List<String> resto = instrucoes(mNova, p);
        resto = resto.subList(p, resto.size());
        v.igual(iOrig.size(), resto.size(), "mesmo numero de instrucoes depois do prefixo (" + iOrig.size() + ")");
        List<Integer> diferentes = new ArrayList<Integer>();
        for (int i = 0; i < Math.min(iOrig.size(), resto.size()); i++) {
            if (!iOrig.get(i).equals(resto.get(i))) {
                diferentes.add(i);
            }
        }
        v.igual(2, diferentes.size(), "o corpo original difere em exatamente 2 instrucoes (as duas chamadas da medicao)");
        final String descSom = "(LReika/ChromatiCraft/Registry/ChromaSounds;Lnet/minecraft/entity/Entity;)V";
        final String descFim = "(LReika/ChromatiCraft/Registry/Chromabilities;Lnet/minecraft/entity/player/EntityPlayer;Z)V";
        if (diferentes.size() == 2) {
            v.igual("INVOKEVIRTUAL Reika/ChromatiCraft/Registry/ChromaSounds.playSound(Lnet/minecraft/entity/Entity;)V", iOrig.get(diferentes.get(0)),
                    "original: ChromaSounds.playSound(Entity) no ramo de falha");
            v.sim(resto.get(diferentes.get(0)).startsWith("INVOKESTATIC " + PONTE) && resto.get(diferentes.get(0)).endsWith(descSom),
                    "trocada por uma chamada a ponte protegida (ChromaSounds, Entity) (" + resto.get(diferentes.get(0)) + ")");
            v.igual("INVOKEVIRTUAL Reika/ChromatiCraft/Registry/Chromabilities.setToPlayer(Lnet/minecraft/entity/player/EntityPlayer;Z)V", iOrig.get(diferentes.get(1)),
                    "original: Chromabilities.setToPlayer(EntityPlayer, boolean) no fim");
            v.sim(resto.get(diferentes.get(1)).startsWith("INVOKESTATIC " + PONTE) && resto.get(diferentes.get(1)).endsWith(descFim),
                    "trocada por uma chamada a ponte protegida (Chromabilities, EntityPlayer, boolean) (" + resto.get(diferentes.get(1)) + ")");
            // a segunda troca e a ultima chamada do metodo antes do retorno; a primeira esta no ramo de falha, depois do laco
            int posUltima = diferentes.get(1);
            v.sim(posUltima == iOrig.size() - 3, "a chamada medida e a ultima antes de 'ILOAD; IRETURN' (nada mais roda depois dela)");
        }
        // as duas pontes: gancho 'antes...' e aoFalhar DENTRO do try; a chamada original FORA; nada mais
        {
            int achadas = 0;
            for (Object o : cnNova.methods) {
                MethodNode m = (MethodNode) o;
                if (!m.name.startsWith("hoshiestar$envolve$")) {
                    continue;
                }
                achadas++;
                boolean som = m.desc.equals(descSom);
                v.sim(som || m.desc.equals(descFim), "ponte " + m.name + ": descritor " + m.desc);
                v.sim((m.access & Opcodes.ACC_PRIVATE) != 0 && (m.access & Opcodes.ACC_STATIC) != 0 && (m.access & Opcodes.ACC_SYNTHETIC) != 0,
                        "ponte " + m.name + ": privada, estatica e sintetica");
                v.igual(1, m.tryCatchBlocks.size(), "ponte " + m.name + ": um try/catch");
                TryCatchBlockNode tc = (TryCatchBlockNode) m.tryCatchBlocks.get(0);
                v.igual("java/lang/Throwable", tc.type, "ponte " + m.name + ": try/catch de Throwable");
                List<String> chamadas = new ArrayList<String>();
                boolean dentro = false;
                for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                    if (n == tc.start) {
                        dentro = true;
                    } else if (n == tc.end) {
                        dentro = false;
                    }
                    if (n instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode) n;
                        chamadas.add(mi.owner + "." + mi.name + (dentro ? "*" : ""));
                    }
                }
                String chamadaOriginal = som ? "Reika/ChromatiCraft/Registry/ChromaSounds.playSound" : "Reika/ChromatiCraft/Registry/Chromabilities.setToPlayer";
                v.igual(java.util.Arrays.asList(GANCHO + "." + (som ? "antesDoSom" : "antesDeTerminar") + "*", FALHAS + ".falhaGancho", chamadaOriginal), chamadas,
                        "ponte " + m.name + ": gancho dentro do try (*), aoFalhar no tratador, chamada original FORA do try, nada mais");
            }
            v.igual(2, achadas, "a classe ganhou exatamente 2 pontes");
        }
        // o laco: nenhuma instrucao dele mudou (as posicoes entre o inicio e o getSizeX final)
        int iLaco0 = indiceDe(iOrig, "INVOKEVIRTUAL Reika/DragonAPI/Instantiable/Data/Immutable/BlockBox.getSizeX()I");
        int fimLaco = indiceDePrefixo(iOrig, "INVOKEVIRTUAL Reika/DragonAPI/Instantiable/Data/BlockStruct/FilledBlockArray.offset(");
        boolean lacoIgual = iLaco0 > 0 && fimLaco > iLaco0;
        for (int i = 0; lacoIgual && i <= fimLaco; i++) {
            if (!iOrig.get(i).equals(resto.get(i))) {
                lacoIgual = false;
            }
        }
        v.sim(lacoIgual, "o laco de varredura (da 1a instrucao ate 'FilledBlockArray.offset', posicoes 0.." + fimLaco + ") ficou identico ao original");
        v.igual(mOrig.access, mNova.access, "shiftArea mantem o acesso (static, pacote)");
        // so um metodo foi mexido: os outros ficam iguais
        int outrosIguais = 0;
        int outros = 0;
        for (Object o : cnOrig.methods) {
            MethodNode m = (MethodNode) o;
            if (m.name.equals("shiftArea")) {
                continue;
            }
            outros++;
            MethodNode n = null;
            for (Object o2 : cnNova.methods) {
                MethodNode x = (MethodNode) o2;
                if (x.name.equals(m.name) && x.desc.equals(m.desc)) {
                    n = x;
                }
            }
            if (n != null && instrucoes(m, 0).equals(instrucoes(n, 0))) {
                outrosIguais++;
            }
        }
        v.igual(outros, outrosIguais, "os outros " + outros + " metodos da classe ficaram identicos (so shiftArea mudou)");
        v.igual(cnOrig.methods.size() + 2, cnNova.methods.size(), "a classe ganhou so as 2 pontes (nenhum metodo perdido)");
        v.igual(cnOrig.fields.size(), cnNova.fields.size(), "a classe nao ganhou nem perdeu campos");

        // ---- controle negativo: sem o quadro de pilha que o utilitario acrescenta, a JVM recusa a classe
        System.out.println("-- controle negativo do verificador");
        if ((cnNova.version & 0xFFFF) >= Opcodes.V1_7) {
            // os dois quadros que o utilitario acrescenta: o do tratador do try/catch (1o) e o do ponto de desvio (2o)
            String[] quais = {"do tratador do try/catch", "do ponto de desvio (primeira instrucao original)"};
            for (int k0 = 0; k0 < quais.length; k0++) {
                ClassNode quebrada = Asm.ler(transformada);
                MethodNode mq = metodo(quebrada, "shiftArea");
                boolean tirou = false;
                int vistos = 0;
                for (Iterator<AbstractInsnNode> it = mq.instructions.iterator(); it.hasNext();) {
                    AbstractInsnNode n = it.next();
                    if (n instanceof FrameNode) {
                        if (vistos++ == k0) {
                            it.remove();
                            tirou = true;
                            break;
                        }
                    }
                }
                final byte[] ruim2 = semMetodosDeCliente(Asm.escrever(quebrada));
                v.sim(tirou, "controle: o quadro de pilha " + quais[k0] + " existe e foi tirado");
                CarregadorMods clq = new CarregadorMods(fontes, TesteChromatiCraft.class.getClassLoader(), new CarregadorMods.Transformador() {
                    public byte[] transformar(String nome, byte[] bytes) {
                        return nome.equals(CLASSE) ? ruim2 : bytes;
                    }
                });
                boolean recusou = false;
                try {
                    Class.forName(CLASSE, true, clq);
                } catch (VerifyError e) {
                    recusou = true;
                }
                v.sim(recusou, "controle: sem o quadro " + quais[k0] + " a JVM recusa a classe com VerifyError (o teste pega esse erro)");
            }
        } else {
            v.sim(true, "controle negativo pulado: a classe e anterior ao Java 7 (sem quadros)");
        }

        // ============================================================ recusas
        System.out.println("-- recusas (a classe fica com os bytes originais)");
        HoshiestarConfig desl = HoshiestarConfig.carregar(yml);
        Correcoes c2 = registro(desl, cl);
        v.sim(c2.ligadasPara(CLASSE) == null && c2.aplicar(CLASSE, original, ganchos, cl) == original, "desligado: a classe nem entra no transformador (bytes originais)");
        v.sim(ChromatiCraft.decisao == null && !ChromatiCraft.ativo, "desligado: nenhuma decisao criada, recurso inativo");
        v.sim(Recursos.achar(ChromatiCraft.ID) != null && !Recursos.achar(ChromatiCraft.ID).ligado, "desligado: recurso aparece como desligado");
        v.igual("desligado", ChromatiCraft.linhaDeEstado(), "desligado: linha de estado curta");

        HoshiestarConfig semMedida = HoshiestarConfig.carregar(yml);
        semMedida.chromaticraft_ligado = true;
        semMedida.chromaticraft_loglento = 0;
        Correcoes c3 = registro(semMedida, cl);
        byte[] r3 = c3.aplicar(CLASSE, original, ganchos, cl);
        v.igual(Correcao.Estado.APLICADA, c3.doRecurso(ChromatiCraft.ID).get(0).estado(), "loglento 0: a correcao da decisao e aplicada");
        v.igual(Correcao.Estado.DESLIGADA, c3.doRecurso(ChromatiCraft.ID).get(1).estado(), "loglento 0: a correcao da medicao fica DESLIGADA");
        List<String> i3 = instrucoes(metodo(Asm.ler(r3), "shiftArea"), p);
        v.sim(i3.subList(p, i3.size()).equals(iOrig), "loglento 0: depois do prefixo o metodo e identico ao original (nenhuma chamada trocada)");

        HoshiestarConfig lig = HoshiestarConfig.carregar(yml);
        lig.chromaticraft_ligado = true;
        Correcoes c4 = registro(lig, cl);
        byte[] outraVersao = comMetodoAMais(original);
        byte[] r4 = c4.aplicar(CLASSE, outraVersao, ganchos, cl);
        v.sim(r4 == outraVersao && c4.doRecurso(ChromatiCraft.ID).get(0).estado() == Correcao.Estado.RECUSADA_TRAVA
                && c4.doRecurso(ChromatiCraft.ID).get(1).estado() == Correcao.Estado.RECUSADA_TRAVA,
                "trava: outra versao da classe e recusada pelas duas correcoes (" + c4.doRecurso(ChromatiCraft.ID).get(0).linha() + ")");
        Correcoes c5 = registro(lig, cl);
        byte[] r5 = c5.aplicar(CLASSE, transformada, ganchos, cl);
        v.sim(r5 == transformada && c5.doRecurso(ChromatiCraft.ID).get(0).estado() == Correcao.Estado.RECUSADA_TRAVA,
                "classe ja transformada nao e transformada de novo (trava)");
        // cada dependencia alterada recusa a correcao da decisao (e so ela); a medicao nao depende delas
        String[][] deps = {
            {"Reika/DragonAPI/Instantiable/Data/Immutable/BlockBox", "da"},
            {"Reika/DragonAPI/Instantiable/Data/BlockStruct/BlockArray", "da"},
            {"Reika/DragonAPI/Instantiable/Data/BlockStruct/FilledBlockArray", "da"},
            {"Reika/ChromatiCraft/Registry/Chromabilities", "cc"},
            {"Reika/ChromatiCraft/Auxiliary/Ability/AbilityHelper", "cc"},
        };
        for (String[] dep : deps) {
            File falsa = new File(tmp, "dep-falsa");
            apagar(falsa);
            File cls = new File(falsa, dep[0] + ".class");
            cls.getParentFile().mkdirs();
            ZipFile zm = new ZipFile(dep[1].equals("da") ? da : cc);
            gravar(cls, comMetodoAMais(RemapeadorSrg.ler(zm.getInputStream(zm.getEntry(dep[0] + ".class")))));
            URLClassLoader depFalsa = new URLClassLoader(new URL[] {falsa.toURI().toURL(), cc.toURI().toURL(), da.toURI().toURL()}, null);
            Correcoes cd = registro(lig, depFalsa);
            byte[] rd = cd.aplicar(CLASSE, original, ganchos, cl);
            Correcao k0 = cd.doRecurso(ChromatiCraft.ID).get(0);
            Correcao k1 = cd.doRecurso(ChromatiCraft.ID).get(1);
            String nomeDep = dep[0].replace('/', '.');
            v.sim(k0.estado() == Correcao.Estado.NAO_APLICADA && k0.detalhe() != null && k0.detalhe().contains("trava de dependencia")
                    && k0.detalhe().contains(nomeDep), "trava de dependencia: " + nomeDep.substring(nomeDep.lastIndexOf('.') + 1) + " diferente recusa a decisao (" + k0.linha() + ")");
            v.sim(k1.estado() == Correcao.Estado.APLICADA && rd != original, "e a medicao, que nao depende dela, segue aplicada (independente)");
            // a classe devolvida nao tem o gancho de decisao
            boolean temRecusar = false;
            for (String s : instrucoes(metodo(Asm.ler(rd), "shiftArea"), 0)) {
                if (s.contains(GANCHO + ".recusar")) {
                    temRecusar = true;
                }
            }
            v.sim(!temRecusar, "e o metodo devolvido nao tem o gancho 'recusar' (a decisao NAO foi aplicada)");
        }
        // auto-checagem do boot reprovou os ganchos: a classe do mod NAO e transformada (nem se carregar depois), nenhuma das duas correcoes
        Correcoes cDef = registro(lig, cl);
        ChromatiCraft.ganchoComDefeito = "defeito de teste";
        byte[] rDef = cDef.aplicar(CLASSE, original, ganchos, cl);
        v.sim(rDef == original && cDef.doRecurso(ChromatiCraft.ID).get(0).estado() == Correcao.Estado.NAO_APLICADA
                && cDef.doRecurso(ChromatiCraft.ID).get(1).estado() == Correcao.Estado.NAO_APLICADA
                && cDef.doRecurso(ChromatiCraft.ID).get(0).detalhe().contains("auto-checagem dos ganchos reprovou"),
                "ganchos com defeito achado no boot: a classe fica original, as duas correcoes recusadas (" + cDef.doRecurso(ChromatiCraft.ID).get(0).linha() + ")");
        ChromatiCraft.ganchoComDefeito = null;
        Correcoes cOk = registro(lig, cl);
        v.sim(cOk.aplicar(CLASSE, original, ganchos, cl) != original, "controle: sem defeito achado, a mesma classe e transformada");
        Correcoes c6 = registro(lig, new URLClassLoader(new URL[0], null));
        byte[] r6 = c6.aplicar(CLASSE, original, ganchos, cl);
        Correcao k6 = c6.doRecurso(ChromatiCraft.ID).get(0);
        v.sim(k6.estado() == Correcao.Estado.NAO_APLICADA && k6.detalhe().contains("dependencia ausente"), "dependencia ausente recusa a decisao (" + k6.linha() + ")");
        Correcoes c7 = registro(lig, cl);
        byte[] r7 = c7.aplicar(CLASSE, original, new ResolvedorDeGanchos(new URLClassLoader(new URL[0], null)), cl);
        v.sim(r7 == original && c7.doRecurso(ChromatiCraft.ID).get(0).estado() == Correcao.Estado.NAO_APLICADA
                && c7.doRecurso(ChromatiCraft.ID).get(0).detalhe().contains("gancho ausente")
                && c7.doRecurso(ChromatiCraft.ID).get(1).estado() == Correcao.Estado.NAO_APLICADA,
                "gancho ausente recusa as duas correcoes (" + c7.doRecurso(ChromatiCraft.ID).get(0).linha() + ")");
        Correcoes c8 = registro(lig, cl);
        byte[] r8 = c8.aplicar(CLASSE, original, new ResolvedorDeGanchos(soOJar), cl);
        v.sim(r8 == original, "sem traduzir os nomes reofuscados do arquivo do gancho, o gancho nao confere (prova de que a traducao foi usada)");

        // ============================================================ linha de estado e config valida
        System.out.println("-- registro: valores do config");
        {
            HoshiestarConfig c = HoshiestarConfig.carregar(yml);
            c.chromaticraft_ligado = true;
            c.chromaticraft_intervalo = -5;
            c.chromaticraft_volume = -1;
            c.chromaticraft_loglento = -2;
            c.chromaticraft_logsegundos = 0;
            c.chromaticraft_mensagem = null;
            registro(c, cl);
            v.igual(1000L, ChromatiCraft.decisao.intervaloMs, "intervalo negativo no registro: usa 1000");
            v.igual(4096L, ChromatiCraft.decisao.volumeMaximo, "volume negativo no registro: usa 4096");
            v.igual(0, ChromatiCraft.lentoMs, "loglento negativo: 0 (medicao desligada)");
            v.igual(1, ChromatiCraft.logSegundos, "logsegundos 0: usa 1");
            v.igual("Mover area recusado: {motivo}.", ChromatiCraft.mensagem, "mensagem nula: usa a padrao");
            c.chromaticraft_intervalo = 0;
            c.chromaticraft_volume = 0;
            c.chromaticraft_distancia = false;
            c.chromaticraft_altura = false;
            c.chromaticraft_chunks = false;
            c.chromaticraft_mensagem = "";
            registro(c, cl);
            DecisaoShift d = ChromatiCraft.decisao;
            v.sim(d.intervaloMs == 0 && d.volumeMaximo == 0 && !d.validarDistancia && !d.validarAltura && !d.exigirChunks, "cada checagem tem o seu interruptor/valor no registro");
            v.sim(ChromatiCraft.mensagemParaJogador(ChromatiCraft.mensagem, "x") == null, "mensagem vazia: nao manda nada");
            v.igual("Aviso: abc.", ChromatiCraft.mensagemParaJogador("Aviso: {motivo}.", "abc"), "mensagem: {motivo} vira o texto do motivo");
        }

        // ============================================================ execucao (classe transformada de verdade, dentro do class loader de teste)
        System.out.println("-- execucao: o gancho roda antes do corpo original");
        Execucao.rodar(v, k, cl);

        // ============================================================ gancho que NAO LIGA em tempo de execucao: nao derruba
        System.out.println("-- gancho que nao liga em tempo de execucao (a classe real, com o metodo do gancho removido)");
        {
            final String[] sumir = {"recusar", "antesDoSom", "antesDeTerminar"};
            final HoshiestarConfig cfgQ = HoshiestarConfig.carregar(yml);
            cfgQ.chromaticraft_ligado = true;
            final Correcoes cq = registro(cfgQ, cl);
            final CarregadorMods[] refQ = new CarregadorMods[1];
            CarregadorMods clQ = new CarregadorMods(fontes, TesteChromatiCraft.class.getClassLoader(), new CarregadorMods.Transformador() {
                public byte[] transformar(String nome, byte[] bytes) {
                    byte[] r = bytes;
                    if (cq.ligadasPara(nome) != null) {
                        r = cq.aplicar(nome, bytes, ganchos, refQ[0]);
                    } else if (nome.equals("hoshiestar.chromaticraft.shift.GanchoShift")) {
                        r = semMetodos(bytes, sumir); // existe no arquivo (o resolvedor ve), falta em tempo de execucao: NoSuchMethodError
                    }
                    return nome.startsWith("Reika.") ? semMetodosDeCliente(r) : r;
                }
            });
            refQ[0] = clQ;
            Class<?> kq = Class.forName(CLASSE, true, clQ);
            v.igual(Correcao.Estado.APLICADA, cq.doRecurso(ChromatiCraft.ID).get(0).estado(), "gancho quebrado: a correcao e aplicada (o resolvedor ve o arquivo do jar)");
            Execucao.ganchoQueNaoLiga(v, kq, clQ);
            registro(HoshiestarConfig.carregar(yml), cl); // limpa o estado estatico
        }

        // ============================================================ auto-checagem dos ganchos, subcomando
        System.out.println("-- auto-checagem dos ganchos e subcomando");
        {
            Class<?> cc0 = Class.forName("hoshiestar.chromaticraft.ChromatiCraft");
            Method vg = cc0.getDeclaredMethod("verificarGanchos", ClassLoader.class);
            vg.setAccessible(true);
            ChromatiCraft.lentoMs = 50;
            v.igual(null, vg.invoke(null, cl), "auto-checagem com as classes de tempo de execucao: os 5 ganchos (inclusive o aoFalhar) ligam e as chamadas do mod existem");
            CarregadorMods semMod = new CarregadorMods(new URL[] {classesTeste.toURI().toURL(), mcSrg.toURI().toURL(), servidorSrg.toURI().toURL(),
                vanilla.toURI().toURL()}, TesteChromatiCraft.class.getClassLoader(), null);
            Object ruimV = vg.invoke(null, semMod);
            v.sim(ruimV instanceof String && ((String) ruimV).startsWith("falhou ao"), "auto-checagem sem o mod no class loader: aponta o defeito (" + ruimV + ")");
        }
        comando(v, tmp, cl, ganchos, original);

        // limpa o estado estatico para nao vazar para outros testes no mesmo processo
        registro(HoshiestarConfig.carregar(yml), cl);
        v.fim();
    }

    // ------------------------------------------------------------------ comando

    static void comando(Verifica v, File tmp, ClassLoader cl, ResolvedorDeGanchos ganchos, byte[] original) throws Exception {
        Class<?> k = Class.forName("hoshiestar.chromaticraft.ComandoChromatiCraft");
        Constructor<?> ct = k.getDeclaredConstructor();
        ct.setAccessible(true);
        Object cmd = ct.newInstance();
        final Method exec = k.getDeclaredMethod("executar", org.bukkit.command.CommandSender.class, String[].class);
        exec.setAccessible(true);
        final List<String> msgs = new ArrayList<String>();
        org.bukkit.command.CommandSender quem = (org.bukkit.command.CommandSender) Proxy.newProxyInstance(TesteChromatiCraft.class.getClassLoader(),
                new Class<?>[] {org.bukkit.command.CommandSender.class}, new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] a) {
                        if (m.getName().equals("sendMessage") && a != null && a.length == 1 && a[0] instanceof String) {
                            msgs.add((String) a[0]);
                        }
                        return null;
                    }
                });
        Method nome = k.getMethod("nome");
        nome.setAccessible(true);
        v.igual("chromaticraft", nome.invoke(cmd), "subcomando se chama chromaticraft");

        // recurso desligado: ligar recusa e explica
        HoshiestarConfig off = HoshiestarConfig.carregar(new File(tmp, "inexistente-1-" + System.nanoTime() + ".yml"));
        registro(off, cl);
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"ligar"});
        v.sim(msgs.size() == 1 && msgs.get(0).startsWith("nao ligo") && !ChromatiCraft.ativo, "desligado no hoshiestar.yml: 'ligar' recusa e explica");
        msgs.clear();
        exec.invoke(cmd, quem, new String[0]);
        v.sim(msgs.size() == 1 && msgs.get(0).equals("desligado"), "sem argumento: mostra o estado (" + msgs + ")");

        // ligado no config, a classe ainda nao carregou (a correcao aguarda): o estado diz; 'desligar' e 'ligar' funcionam (o gancho entra quando a classe carregar)
        HoshiestarConfig on = HoshiestarConfig.carregar(new File(tmp, "inexistente-2-" + System.nanoTime() + ".yml"));
        on.chromaticraft_ligado = true;
        Correcoes c = registro(on, cl);
        msgs.clear();
        exec.invoke(cmd, quem, new String[0]);
        v.sim(msgs.size() == 1 && msgs.get(0).contains("gancho ainda nao aplicado") && msgs.get(0).contains("intervalo 1000 ms")
                && msgs.get(0).contains("volume 4096") && msgs.get(0).contains("pedidos 0: aceitos 0, recusados 0"), "estado: mostra gancho ainda nao aplicado, limites e contadores (" + msgs + ")");
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"desligar"});
        v.sim(!ChromatiCraft.ativo, "desligar antes de a classe carregar: ativo=false");
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"ligar"});
        v.sim(ChromatiCraft.ativo && msgs.size() == 1 && msgs.get(0).contains("ligados em execucao") && msgs.get(0).contains("gancho ainda nao aplicado"),
                "ligar de novo antes de a classe carregar e permitido (senao o 1o uso rodaria sem limites) (" + msgs + ")");
        // trava de versao recusada: ligar nao teria efeito e recusa
        HoshiestarConfig on2 = HoshiestarConfig.carregar(new File(tmp, "inexistente-3-" + System.nanoTime() + ".yml"));
        on2.chromaticraft_ligado = true;
        Correcoes cTrava = registro(on2, cl);
        cTrava.aplicar(CLASSE, comMetodoAMais(original), ganchos, cl);
        ChromatiCraft.ativo = false;
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"ligar"});
        v.sim(!ChromatiCraft.ativo && msgs.size() == 1 && msgs.get(0).startsWith("nao ligo") && msgs.get(0).contains("nao foi aplicada"),
                "trava de versao recusada: 'ligar' recusa e explica");
        c = registro(on, cl);
        // classe real passa pelo transformador: as correcoes ficam APLICADAS
        c.aplicar(CLASSE, original, ganchos, cl);
        for (Correcao kc : c.doRecurso(ChromatiCraft.ID)) {
            v.igual(Correcao.Estado.APLICADA, kc.estado(), "comando: correcao aplicada na classe real antes de ligar (" + kc.linha() + ")");
        }
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"desligar"});
        v.sim(!ChromatiCraft.ativo && msgs.size() == 1 && msgs.get(0).contains("desligados em execucao") && msgs.get(0).contains("DESLIGADO em execucao"),
                "desligar: ativo=false e a linha de estado diz (" + msgs + ")");
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"ligar"});
        v.sim(ChromatiCraft.ativo && msgs.size() == 1 && msgs.get(0).contains("ligados em execucao") && msgs.get(0).contains("gancho aplicado")
                && msgs.get(0).contains("medicao (>= 50 ms): aplicada"), "ligar com a correcao aplicada: ativo=true (" + msgs + ")");
        ChromatiCraft.ganchoComDefeito = "teste";
        ChromatiCraft.ativo = false;
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"ligar"});
        v.sim(!ChromatiCraft.ativo && msgs.size() == 1 && msgs.get(0).contains("auto-checagem dos ganchos reprovou"), "ganchos com defeito: 'ligar' recusa");
        ChromatiCraft.ganchoComDefeito = null;
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"tchau"});
        v.sim(msgs.size() == 1 && msgs.get(0).startsWith("uso: hoshie chromaticraft"), "argumento invalido: mostra o uso");
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"ligar", "x"});
        v.sim(msgs.size() == 1 && msgs.get(0).startsWith("uso: hoshie chromaticraft"), "argumentos demais: mostra o uso");

        // textos
        v.sim(ChromatiCraft.textoDoMotivo(DecisaoShift.Motivo.VOLUME, 5000, 4096, 5, 24).contains("5000") && ChromatiCraft.textoDoMotivo(DecisaoShift.Motivo.VOLUME, 5000, 4096, 5, 24).contains("4096"),
                "texto do motivo volume traz o volume e o limite");
        v.sim(ChromatiCraft.textoDoMotivo(DecisaoShift.Motivo.DISTANCIA, 1, 1, -3, 24).contains("-3") && ChromatiCraft.textoDoMotivo(DecisaoShift.Motivo.DISTANCIA, 1, 1, -3, 24).contains("24"),
                "texto do motivo distancia traz a distancia e o maximo");
        String linha = ChromatiCraft.linhaDeRecusa(DecisaoShift.Motivo.VOLUME, "Fulano", 0, 1, 2, 3, 4, 5, 6, 0, 1, 0, 7, 24, 4096);
        v.sim(linha.contains("Fulano") && linha.contains("dimensao 0") && linha.contains("(1,2,3)-(4,5,6)") && linha.contains("volume 64")
                && linha.contains("distancia 7") && linha.contains("limite 4096"), "linha de log da recusa: quem, dimensao, caixa, volume, distancia (" + linha + ")");
    }

    // ------------------------------------------------------------------ auxiliares

    static Correcoes registro(HoshiestarConfig cfg, ClassLoader deps) {
        Recursos.limparParaTeste();
        Correcoes c = Correcoes.novoParaTeste();
        ChromatiCraft.registrar(cfg, c, deps);
        c.fechar();
        return c;
    }

    /** Imita o SideTransformer do FML num servidor dedicado: tira metodos e campos {@code @SideOnly(CLIENT)}. */
    static byte[] semMetodosDeCliente(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        for (Iterator<?> it = cn.methods.iterator(); it.hasNext();) {
            MethodNode m = (MethodNode) it.next();
            if (soCliente(m.visibleAnnotations)) {
                it.remove();
            }
        }
        for (Iterator<?> it = cn.fields.iterator(); it.hasNext();) {
            FieldNode f = (FieldNode) it.next();
            if (soCliente(f.visibleAnnotations)) {
                it.remove();
            }
        }
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static boolean soCliente(List<?> anotacoes) {
        if (anotacoes == null) {
            return false;
        }
        for (AnnotationNode an : (List<AnnotationNode>) anotacoes) {
            if ("Lcpw/mods/fml/relauncher/SideOnly;".equals(an.desc) && an.values != null && an.values.size() >= 2
                    && an.values.get(1) instanceof String[] && "CLIENT".equals(((String[]) an.values.get(1))[1])) {
                return true;
            }
        }
        return false;
    }

    static MethodNode metodo(ClassNode cn, String nome) {
        MethodNode achado = null;
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (m.name.equals(nome) && m.desc.equals(DESC)) {
                achado = m;
            }
        }
        if (achado == null) {
            throw new IllegalStateException("metodo " + nome + DESC + " nao encontrado");
        }
        return achado;
    }

    static String primeiraReal(MethodNode m) {
        for (AbstractInsnNode n : m.instructions.toArray()) {
            if (n.getOpcode() >= 0) {
                return instrucoes(m, 0).get(0);
            }
        }
        return null;
    }

    static int indiceDe(List<String> l, String s) {
        return l.indexOf(s);
    }

    static int indiceDePrefixo(List<String> l, String prefixo) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).startsWith(prefixo)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * As instrucoes reais do metodo (sem rotulos, numeros de linha nem quadros), uma por linha de texto. Os destinos
     * dos desvios aparecem como "indice da instrucao real de destino menos {@code deslocamento}" (para comparar um
     * metodo com prefixo inserido: o prefixo tem {@code deslocamento} instrucoes).
     */
    static List<String> instrucoes(MethodNode m, int deslocamento) {
        Map<LabelNode, Integer> indice = new IdentityHashMap<LabelNode, Integer>();
        int n = 0;
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i instanceof LabelNode) {
                indice.put((LabelNode) i, n);
            } else if (i.getOpcode() >= 0) {
                n++;
            }
        }
        List<String> r = new ArrayList<String>();
        for (AbstractInsnNode i : m.instructions.toArray()) {
            int op = i.getOpcode();
            if (op < 0) {
                continue;
            }
            String nome = Printer.OPCODES[op];
            if (i instanceof MethodInsnNode) {
                MethodInsnNode x = (MethodInsnNode) i;
                r.add(nome + " " + x.owner + "." + x.name + x.desc);
            } else if (i instanceof FieldInsnNode) {
                FieldInsnNode x = (FieldInsnNode) i;
                r.add(nome + " " + x.owner + "." + x.name + ":" + x.desc);
            } else if (i instanceof VarInsnNode) {
                r.add(nome + " " + ((VarInsnNode) i).var);
            } else if (i instanceof JumpInsnNode) {
                r.add(nome + " ->" + (indice.get(((JumpInsnNode) i).label) - (deslocamento > 0 && indice.get(((JumpInsnNode) i).label) >= deslocamento ? deslocamento : 0)));
            } else if (i instanceof TypeInsnNode) {
                r.add(nome + " " + ((TypeInsnNode) i).desc);
            } else if (i instanceof LdcInsnNode) {
                r.add(nome + " " + ((LdcInsnNode) i).cst);
            } else if (i instanceof IntInsnNode) {
                r.add(nome + " " + ((IntInsnNode) i).operand);
            } else if (i instanceof IincInsnNode) {
                r.add(nome + " " + ((IincInsnNode) i).var + " " + ((IincInsnNode) i).incr);
            } else if (i instanceof InsnNode) {
                r.add(nome);
            } else if (i instanceof TableSwitchInsnNode || i instanceof LookupSwitchInsnNode) {
                r.add(nome + " (switch)");
            } else {
                r.add(nome + " ?");
            }
        }
        return r;
    }

    /** Os mesmos bytes sem os metodos de nome dado (simula um gancho que nao liga: NoSuchMethodError na chamada). */
    static byte[] semMetodos(byte[] b, String[] nomes) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        for (Iterator<?> it = cn.methods.iterator(); it.hasNext();) {
            MethodNode m = (MethodNode) it.next();
            for (String n : nomes) {
                if (m.name.equals(n)) {
                    it.remove();
                    break;
                }
            }
        }
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Uma "outra versao" da classe: os mesmos bytes com um metodo a mais (hash diferente). */
    static byte[] comMetodoAMais(byte[] b) {
        ClassNode cn = Asm.ler(b);
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "hoshiestarTesteVersao", "()V", null, null);
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        m.maxStack = 0;
        m.maxLocals = 0;
        cn.methods.add(m);
        return Asm.escrever(cn);
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

    static void apagar(File f) {
        File[] filhos = f.listFiles();
        if (filhos != null) {
            for (File x : filhos) {
                apagar(x);
            }
        }
        f.delete();
    }

    /** Desembrulha InvocationTargetException. */
    static Throwable causa(Throwable t) {
        return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
    }
}
