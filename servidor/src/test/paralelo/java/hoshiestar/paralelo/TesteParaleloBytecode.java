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
 * feito) os unicos pontos do jogo que chamam o paralelo sao o {@code FMLCommonHandler.getEffectiveSide}, uma vez, depois do
 * teste original do nome "Server thread", e (passo 2) o gancho do {@code congelar} no
 * {@code MinecraftServer.updateTimeLightAndEntities}, sempre atras de {@code Congelar.TESTE} ({@code static final}).
 *
 * <p>{@code TesteParaleloBytecode <pasta com as classes compiladas pelo teste> [<pasta de classes do build rapido>]}.
 */
public final class TesteParaleloBytecode {

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final String[] MC_FREE = {"Paralelo", "Parametros", "Recusas", "Recusas$Fatos", "RegistroParalelo",
        "ComandoParalelo", "Congelar",
        // nucleo de concorrencia (passo 5): nenhuma dependencia do jogo
        "Ambiente", "Andamento", "Andamento$Fecho", "Andamento$Fim", "Barreira", "Barreira$EsperaPortao", "Barreira$Foto",
        "Despejo", "Fase", "Fase$Estado", "Operacao", "Pedido", "Preso", "ThreadRegiao", "Trabalhador", "Trabalho",
        // passo 3: classificacao, guardas, invariantes, fatos do jogo, travas, painel
        "Classificador", "Classificador$Visao", "Guardas", "Guardas$Op", "Guardas$Res", "Invariantes", "Invariantes$Foto",
        "ColetaFatos", "ColetaFatos$Bruto", "ColetaFatos$Resultado", "Travas", "Travas$Trava", "Registro", "Registro$Saida",
        "Painel", "Painel$Foto",
        // passo 4: lote, adiados, sombra_ordenada e juncao
        "Adiado", "Lote", "Lote$Ticador", "Ordenada", "Ordenada$Contexto", "Ordenada$1", "Juncao", "Juncao$Acao", "Juncao$Falha",
        "Juncao$Resultado", "Juncao$1", "Injecao"};

    /** Classes do jogo (no build rapido) que chamam o paralelo, e so elas. */
    static final List<String> CITAM = java.util.Arrays.asList("cpw/mods/fml/common/FMLCommonHandler.class",
        "cpw/mods/fml/common/eventhandler/EventBus.class", "net/minecraft/network/NetHandlerPlayServer.class",
        "net/minecraft/server/MinecraftServer.class", "net/minecraft/world/World.class", "net/minecraft/world/WorldServer.class",
        "net/minecraft/world/gen/ChunkProviderServer.class", "org/bukkit/plugin/SimplePluginManager.class",
        "thermos/thermite/ThermiteRandom.class");

    /**
     * O que os adaptadores do modo sombra podem chamar no jogo e nos mods: so leitura (passo 3: "sombra nao muda o
     * comportamento do jogo"). Nomes MCP (classes do build rapido). Escrita em campo que nao e do hoshiestar e proibida.
     */
    static final java.util.Set<String> LEITURAS = new java.util.HashSet<String>(java.util.Arrays.asList(
        "net/minecraft/tileentity/TileEntity.isInvalid", "net/minecraft/tileentity/TileEntity.getWorldObj",
        "net/minecraft/world/World.getBlock", "net/minecraft/world/World.hoshieTilesPendentes",
        "net/minecraft/world/WorldServer.hoshieAgendados", "net/minecraft/world/gen/ChunkProviderServer.chunkExists",
        "net/minecraft/world/gen/ChunkProviderServer.provideChunk", "thermos/wrapper/VanillaChunkHashMap.rawThermos",
        "thermos/wrapper/ChunkBlockHashMap.hoshieVersao", "io/github/crucible/util/WorldChangeHolder.isCapturing",
        "net/minecraft/world/ChunkPosition.<init>", "net/minecraftforge/common/DimensionManager.getIDs",
        "net/minecraftforge/common/DimensionManager.getWorld", "net/minecraft/launchwrapper/LaunchClassLoader.getTransformers",
        "org/bukkit/event/Event.getHandlers", "org/bukkit/event/HandlerList.getRegisteredListeners", "org/bukkit/event/Event.isAsynchronous"));

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
        // passo 2: Congelar.TESTE (propriedade -Dhoshiestar.paralelo.teste) tambem e 'public static final boolean', atribuido em <clinit>
        ClassNode c = ler(dir, "hoshiestar/paralelo/Congelar");
        int tf = 0, tp = 0;
        for (FieldNode f : c.fields) {
            if (f.name.equals("TESTE")) {
                tf++;
                v.sim((f.access & Opcodes.ACC_STATIC) != 0 && (f.access & Opcodes.ACC_FINAL) != 0 && f.desc.equals("Z")
                        && (f.access & Opcodes.ACC_PUBLIC) != 0, "Congelar.TESTE e 'public static final boolean'");
            }
        }
        for (MethodNode m : c.methods) {
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() == Opcodes.PUTSTATIC && ((org.objectweb.asm.tree.FieldInsnNode) i).name.equals("TESTE")) {
                    tp++;
                    v.igual("<clinit>", m.name, "TESTE so e atribuido no inicializador estatico");
                }
            }
        }
        v.igual(1, tf, "um campo TESTE em Congelar");
        v.igual(1, tp, "uma atribuicao de TESTE");
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
        // so estas classes do jogo citam o paralelo: getEffectiveSide, congelar (passo 2) e as linhas do passo 3
        List<String> citam = new ArrayList<String>();
        varrer(new File(build, "cpw"), "cpw", citam);
        varrer(new File(build, "net"), "net", citam);
        varrer(new File(build, "org/bukkit"), "org/bukkit", citam);
        varrer(new File(build, "thermos"), "thermos", citam);
        v.igual(CITAM, citam, "so as classes esperadas citam hoshiestar/paralelo entre as classes do jogo");
        congelar(v, build);
        atrasDoInterruptor(v, build);
        sombraSoLe(v, build);
    }

    /**
     * Passo 3: em toda classe do jogo, cada chamada aos ganchos do paralelo vem logo depois de GETSTATIC
     * {@code Paralelo.LIGADO} seguido de IFEQ (desligado = um teste de campo static final, que o JIT apaga). As do
     * {@code Congelar} (atras de {@code Congelar.TESTE}) e a do getEffectiveSide (conferida acima) ficam de fora.
     */
    static void atrasDoInterruptor(Verifica v, File build) throws Exception {
        int total = 0, semTeste = 0;
        List<String> sem = new ArrayList<String>();
        for (String arq : CITAM) {
            String nome = arq.substring(0, arq.length() - 6);
            if (nome.endsWith("FMLCommonHandler")) {
                continue;
            }
            ClassNode cn = ler(build, nome);
            for (MethodNode m : cn.methods) {
                List<AbstractInsnNode> l = new ArrayList<AbstractInsnNode>();
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i.getOpcode() >= 0) {
                        l.add(i);
                    }
                }
                for (int k = 0; k < l.size(); k++) {
                    if (!(l.get(k) instanceof MethodInsnNode)) {
                        continue;
                    }
                    MethodInsnNode mi = (MethodInsnNode) l.get(k);
                    if (!mi.owner.startsWith("hoshiestar/paralelo/") || mi.owner.equals("hoshiestar/paralelo/Congelar")) {
                        continue;
                    }
                    total++;
                    boolean ok = false;
                    for (int j = k - 1; j >= Math.max(0, k - 14); j--) {
                        AbstractInsnNode a = l.get(j);
                        if (a.getOpcode() == Opcodes.GETSTATIC && ((org.objectweb.asm.tree.FieldInsnNode) a).owner.equals("hoshiestar/paralelo/Paralelo")
                                && ((org.objectweb.asm.tree.FieldInsnNode) a).name.equals("LIGADO") && l.get(j + 1).getOpcode() == Opcodes.IFEQ) {
                            ok = true;
                            break;
                        }
                    }
                    if (!ok) {
                        semTeste++;
                        sem.add(nome + "." + m.name + "->" + mi.name);
                    }
                }
            }
        }
        v.sim(total >= 40, "ganchos do passo 3 encontrados nas classes do jogo: " + total);
        v.igual(0, semTeste, "toda chamada aos ganchos do paralelo vem logo depois de Paralelo.LIGADO + IFEQ " + sem);
    }

    /** O que OrdenadaJogo e GanchosAdiados (o modo sombra_ordenada, passo 4) podem chamar no jogo. Nomes MCP. */
    static final java.util.Set<String> ORDENADA = new java.util.HashSet<String>(java.util.Arrays.asList(
        "net/minecraft/tileentity/TileEntity.updateEntity", "net/minecraft/tileentity/TileEntity.isInvalid",
        "net/minecraft/tileentity/TileEntity.getWorldObj", "net/minecraft/tileentity/TileEntity.func_145828_a",
        "net/minecraft/world/World.markBlockForUpdate", "net/minecraft/world/World.markBlockRangeForRenderUpdate",
        "net/minecraft/world/World.func_147479_m", "net/minecraft/world/World.playSoundAtEntity", "net/minecraft/world/World.playSoundToNearExcept",
        "net/minecraft/world/World.playSoundEffect", "net/minecraft/world/World.playRecord", "net/minecraft/world/World.spawnParticle",
        "net/minecraft/world/World.playBroadcastSound", "net/minecraft/world/World.playAuxSFXAtEntity",
        "net/minecraft/world/World.destroyBlockInWorldPartially", "net/minecraft/world/World.func_147480_a",
        "net/minecraft/world/WorldServer.scheduleBlockUpdateWithPriority", "net/minecraft/world/WorldServer.func_147446_b",
        "net/minecraft/world/WorldServer.addBlockEvent", "net/minecraft/world/gen/ChunkProviderServer.loadChunk",
        "net/minecraft/world/gen/ChunkProviderServer.chunkExists", "net/minecraft/world/gen/ChunkProviderServer.provideChunk",
        "thermos/wrapper/VanillaChunkHashMap.rawThermos", "thermos/wrapper/ChunkBlockHashMap.get", "net/minecraft/world/ChunkPosition.<init>",
        "net/minecraft/crash/CrashReport.makeCrashReport", "net/minecraft/crash/CrashReport.makeCategory",
        "net/minecraft/crash/CrashReportCategory.addCrashSection", "net/minecraft/util/ReportedException.<init>",
        "net/minecraft/inventory/IInventory.getSizeInventory", "net/minecraft/inventory/IInventory.getStackInSlot"));

    /**
     * Passo 3 e 4, "o modo sombra nao muda o comportamento do jogo": (1) {@code despachar} so devolve falso, a nao ser o
     * resultado de {@code OrdenadaJogo.despachar}, que so e chamado com {@code GanchosParalelo.ordenada} verdadeiro; (2) os
     * adaptadores do modo sombra nao escrevem em campo de classe que nao e do hoshiestar e chamam no jogo so metodos de
     * leitura (lista {@link #LEITURAS}); (3) o que muda o jogo (sombra_ordenada) fica em {@code OrdenadaJogo} e
     * {@code GanchosAdiados}, so com metodos da lista {@link #ORDENADA}; (4) {@code GanchosParalelo} so entra em
     * {@code OrdenadaJogo} (despachar, novoTick, fimDoLaco) depois de testar {@code ordenada}; (5) so {@code OrdenadaJogo}
     * abre um lote ({@code loteDaVez} nao nulo, {@code Guardas.loteAberto} verdadeiro): sem lote, todo gancho de
     * {@code GanchosAdiados} devolve falso, e cada verdadeiro dele vem depois de {@code Lote.adiar}.
     */
    static void sombraSoLe(Verifica v, File build) throws Exception {
        ClassNode gp = ler(build, "hoshiestar/paralelo/GanchosParalelo");
        MethodNode desp = null;
        for (MethodNode m : gp.methods) {
            if (m.name.equals("despachar")) {
                desp = m;
            }
        }
        v.sim(desp != null && desp.desc.endsWith(")Z"), "GanchosParalelo.despachar devolve boolean");
        int ireturn = 0, ireturnOk = 0;
        for (AbstractInsnNode i : desp.instructions.toArray()) {
            if (i.getOpcode() == Opcodes.IRETURN) {
                ireturn++;
                AbstractInsnNode a = i.getPrevious();
                while (a != null && a.getOpcode() < 0) {
                    a = a.getPrevious();
                }
                if (a != null && (a.getOpcode() == Opcodes.ICONST_0 || chama(a, "hoshiestar/paralelo/OrdenadaJogo", "despachar"))) {
                    ireturnOk++;
                }
            }
        }
        v.sim(ireturn > 0 && ireturn == ireturnOk, "despachar: todo retorno e 'false' ou o resultado de OrdenadaJogo.despachar (" + ireturnOk + " de " + ireturn + ")");
        List<String> semTeste = new ArrayList<String>();
        int chamadasOrdenada = 0;
        for (MethodNode m : gp.methods) {
            List<AbstractInsnNode> l = reais(m);
            for (int k = 0; k < l.size(); k++) {
                if (chama(l.get(k), "hoshiestar/paralelo/OrdenadaJogo", "despachar") || chama(l.get(k), "hoshiestar/paralelo/OrdenadaJogo", "novoTick")
                        || chama(l.get(k), "hoshiestar/paralelo/OrdenadaJogo", "fimDoLaco")) {
                    chamadasOrdenada++;
                    boolean ok = false;
                    for (int j = k - 1; j >= Math.max(0, k - 14); j--) {
                        AbstractInsnNode a = l.get(j);
                        if (a.getOpcode() == Opcodes.GETSTATIC && ((org.objectweb.asm.tree.FieldInsnNode) a).owner.equals("hoshiestar/paralelo/GanchosParalelo")
                                && ((org.objectweb.asm.tree.FieldInsnNode) a).name.equals("ordenada") && l.get(j + 1).getOpcode() == Opcodes.IFEQ) {
                            ok = true;
                            break;
                        }
                    }
                    if (!ok) {
                        semTeste.add(m.name + "->" + ((MethodInsnNode) l.get(k)).name);
                    }
                }
            }
        }
        v.igual(3, chamadasOrdenada, "GanchosParalelo chama OrdenadaJogo.despachar, novoTick e fimDoLaco uma vez cada");
        v.igual(new ArrayList<String>(), semTeste, "cada uma vem logo depois de 'GanchosParalelo.ordenada' + IFEQ");
        List<String> escritas = new ArrayList<String>(), chamadas = new ArrayList<String>(), abreLote = new ArrayList<String>();
        File d = new File(build, "hoshiestar/paralelo");
        File[] fs = d.listFiles();
        java.util.Arrays.sort(fs);
        int adiadosVerdadeiro = 0, adiadosSemAdiar = 0;
        for (File f : fs) {
            String n = f.getName();
            if (!n.endsWith(".class")) {
                continue;
            }
            boolean sombra = n.startsWith("GanchosParalelo") || n.startsWith("GanchosEventos") || n.startsWith("ColetaFatosJogo");
            boolean ordenada = n.startsWith("OrdenadaJogo") || n.startsWith("GanchosAdiados");
            ClassNode cn = ler(build, "hoshiestar/paralelo/" + n.substring(0, n.length() - 6));
            for (MethodNode m : cn.methods) {
                List<AbstractInsnNode> l = reais(m);
                boolean adiou = false;
                for (int k = 0; k < l.size(); k++) {
                    AbstractInsnNode i = l.get(k);
                    int op = i.getOpcode();
                    // (5) quem abre lote
                    if (op == Opcodes.PUTSTATIC) {
                        org.objectweb.asm.tree.FieldInsnNode fi = (org.objectweb.asm.tree.FieldInsnNode) i;
                        boolean nulo = k > 0 && (l.get(k - 1).getOpcode() == Opcodes.ACONST_NULL || l.get(k - 1).getOpcode() == Opcodes.ICONST_0);
                        if (((fi.owner.equals("hoshiestar/paralelo/GanchosParalelo") && fi.name.equals("loteDaVez"))
                                || (fi.owner.equals("hoshiestar/paralelo/Guardas") && fi.name.equals("loteAberto"))) && !nulo && !n.startsWith("OrdenadaJogo")) {
                            abreLote.add(cn.name + "." + m.name + " -> " + fi.name);
                        }
                    }
                    if (!sombra && !ordenada) {
                        continue;
                    }
                    if ((op == Opcodes.PUTFIELD || op == Opcodes.PUTSTATIC) && !((org.objectweb.asm.tree.FieldInsnNode) i).owner.startsWith("hoshiestar/")) {
                        escritas.add(cn.name + "." + m.name + " -> " + ((org.objectweb.asm.tree.FieldInsnNode) i).owner + "." + ((org.objectweb.asm.tree.FieldInsnNode) i).name);
                    }
                    if (i instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode) i;
                        boolean jogo = mi.owner.startsWith("net/minecraft") || mi.owner.startsWith("cpw/") || mi.owner.startsWith("org/bukkit")
                                || mi.owner.startsWith("thermos/") || mi.owner.startsWith("io/github/crucible") || mi.owner.startsWith("org/spigotmc");
                        java.util.Set<String> lista = sombra ? LEITURAS : ORDENADA;
                        if (jogo && !lista.contains(mi.owner + "." + mi.name) && !(ordenada && LEITURAS.contains(mi.owner + "." + mi.name))) {
                            chamadas.add(cn.name + "." + m.name + " -> " + mi.owner + "." + mi.name);
                        }
                        if (mi.owner.equals("java/lang/reflect/Field") && mi.name.startsWith("set") && !mi.name.equals("setAccessible")) {
                            escritas.add(cn.name + "." + m.name + " -> Field." + mi.name);
                        }
                        if (mi.owner.equals("hoshiestar/paralelo/Lote") && mi.name.equals("adiar")) {
                            adiou = true;
                        }
                    }
                    // (5) GanchosAdiados: 'true' so depois de Lote.adiar
                    if (cn.name.equals("hoshiestar/paralelo/GanchosAdiados") && op == Opcodes.IRETURN && k > 0 && l.get(k - 1).getOpcode() == Opcodes.ICONST_1) {
                        adiadosVerdadeiro++;
                        if (!adiou) {
                            adiadosSemAdiar++;
                        }
                    }
                }
            }
        }
        v.igual(new ArrayList<String>(), escritas, "adaptadores do paralelo nao escrevem em campo do jogo nem de mod");
        v.igual(new ArrayList<String>(), chamadas, "adaptadores do modo sombra so leem o jogo; os do sombra_ordenada so chamam a lista revisada");
        v.igual(new ArrayList<String>(), abreLote, "so OrdenadaJogo abre um lote (loteDaVez e loteAberto)");
        v.sim(adiadosVerdadeiro >= 15 && adiadosSemAdiar == 0, "GanchosAdiados: cada 'true' vem depois de Lote.adiar (" + adiadosVerdadeiro + " retornos, " + adiadosSemAdiar + " sem adiar)");
        // GanchosEventos.eventoBukkit: so pode devolver verdadeiro depois de ler Guardas.loteAberto
        ClassNode ge = ler(build, "hoshiestar/paralelo/GanchosEventos");
        for (MethodNode m : ge.methods) {
            if (m.name.equals("eventoBukkit")) {
                boolean leu = false, ok = true;
                for (AbstractInsnNode i : reais(m)) {
                    if (i.getOpcode() == Opcodes.GETSTATIC && ((org.objectweb.asm.tree.FieldInsnNode) i).name.equals("loteAberto")) {
                        leu = true;
                    }
                    if (i.getOpcode() == Opcodes.IRETURN) {
                        AbstractInsnNode a = i.getPrevious();
                        while (a != null && a.getOpcode() < 0) {
                            a = a.getPrevious();
                        }
                        if (a != null && a.getOpcode() != Opcodes.ICONST_0 && !leu) {
                            ok = false;
                        }
                    }
                }
                v.sim(leu && ok, "GanchosEventos.eventoBukkit: retorno que nao e 'false' so depois de ler Guardas.loteAberto");
            }
        }
    }

    static List<AbstractInsnNode> reais(MethodNode m) {
        List<AbstractInsnNode> l = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i.getOpcode() >= 0) {
                l.add(i);
            }
        }
        return l;
    }

    static boolean chama(AbstractInsnNode i, String owner, String nome) {
        return i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(owner) && ((MethodInsnNode) i).name.equals(nome);
    }

    /**
     * Passo 2: no MinecraftServer, toda chamada ao paralelo e ao {@code Congelar} (nada de Paralelo, Barreira...), e cada
     * chamada vem logo depois de um GETSTATIC {@code Congelar.TESTE} seguido de IFEQ (desligado = so o teste do campo).
     */
    static void congelar(Verifica v, File build) throws Exception {
        ClassNode ms = ler(build, "net/minecraft/server/MinecraftServer");
        int chamadas = 0, guardadas = 0, getstatics = 0;
        List<String> nomes = new ArrayList<String>();
        for (MethodNode m : ms.methods) {
            AbstractInsnNode ultimoTeste = null;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() == Opcodes.GETSTATIC && ((org.objectweb.asm.tree.FieldInsnNode) i).owner.equals("hoshiestar/paralelo/Congelar")) {
                    org.objectweb.asm.tree.FieldInsnNode f = (org.objectweb.asm.tree.FieldInsnNode) i;
                    getstatics++;
                    v.sim(f.owner.equals("hoshiestar/paralelo/Congelar") && f.name.equals("TESTE"), "campo lido no MinecraftServer e Congelar.TESTE (" + f.owner + "." + f.name + ")");
                    AbstractInsnNode prox = i.getNext();
                    while (prox != null && prox.getOpcode() < 0) {
                        prox = prox.getNext();
                    }
                    v.sim(prox != null && prox.getOpcode() == Opcodes.IFEQ, "Congelar.TESTE seguido de IFEQ em " + m.name);
                    ultimoTeste = i;
                }
                if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals("hoshiestar/paralelo/Congelar")) {
                    MethodInsnNode mi = (MethodInsnNode) i;
                    chamadas++;
                    nomes.add(m.name + "->" + mi.name);
                    v.sim(mi.owner.equals("hoshiestar/paralelo/Congelar"), "chamada do MinecraftServer ao paralelo e ao Congelar (" + mi.owner + ")");
                    if (m.name.equals("updateTimeLightAndEntities") || m.name.equals("func_71190_q")) {
                        if (ultimoTeste != null) {
                            guardadas++;
                        }
                    }
                }
            }
        }
        // antesDosMundos e congelado no laco (atras de TESTE); congeladoEm e alvo so dentro do salvamento (que so roda atras de TESTE)
        v.igual(2, getstatics, "dois testes de Congelar.TESTE no MinecraftServer (antes do laco dos mundos e dentro dele)");
        v.sim(chamadas == 4, "quatro chamadas ao Congelar no MinecraftServer: " + nomes);
        v.igual(2, guardadas, "as duas chamadas do tick vem depois de um teste de Congelar.TESTE");
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
