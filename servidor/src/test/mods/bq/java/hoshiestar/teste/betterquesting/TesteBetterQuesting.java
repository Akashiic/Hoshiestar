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
package hoshiestar.teste.betterquesting;

import hoshiestar.api.Recursos;
import hoshiestar.api.ThreadServidor;
import hoshiestar.betterquesting.BetterQuesting;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import hoshiestar.embutidos.autoteste.Autoteste;
import hoshiestar.teste.Verifica;
import hoshiestar.teste.mods.CarregadorMods;
import hoshiestar.teste.mods.RemapeadorSrg;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Teste isolado do recurso 1.D (filtro de inventario do BetterQuesting).
 * <ol>
 * <li><b>Transformador sobre a classe REAL</b> {@code bq_standard.handlers.EventHandler} (do jar do
 * pacote), pelo registro de correcoes REAL ({@link BetterQuesting#registrar}), com os ganchos conferidos
 * no arquivo do server.jar construido: passa no CheckClassAdapter e no verificador da JVM (a classe e
 * carregada e inicializada); a chamada trocada e exatamente uma; o metodo transformado e o original
 * mais as tres alteracoes e NADA mais (instrucao por instrucao); as chamadas que o Akashic-BQRelief
 * redireciona por Mixin (getSharedQuests e filterKeys nos 10 metodos dele) continuam iguais; o
 * onServerTick transformado roda (fases START e END, com um jogador sem inventario no conjunto do BQ:
 * o fim do laco esvazia o conjunto); recusas (desligado, trava, trava de dependencia, dependencia
 * ausente, gancho ausente); convivencia com a correcao de trava errada do autoteste na mesma classe.</li>
 * <li><b>Diferencial</b> ({@code mundo.Diferencial}, harness da conferencia contra os ganchos REAIS do
 * jar construido): 240 mil cenarios aleatorios (200 mil com a semente 3, 20 mil com a 1 e 20 mil com a
 * 7), 0 diferencas exigidas; modo verificar (20 mil); os casos montados a mao ({@code mundo.Casos}).</li>
 * <li><b>Mutacao</b> ({@link TesteMutacaoBQ}): cada condicao tirada do fonte tem de ser acusada.</li>
 * </ol>
 *
 * <p>Argumentos: server.jar construido, pasta com os jars "-srg", jar do BetterQuesting, jar vanilla,
 * pasta das classes de teste, pasta dos stubs compilados; opcionais: "rapido", "fonte=&lt;pasta
 * src/mods/java&gt;" e "cpmut=&lt;classpath&gt;" (ligam a mutacao).
 */
public final class TesteBetterQuesting {

    static final String GANCHO = "hoshiestar/betterquesting/filtro/GanchoBQ";
    static final String DESC = "(Lcpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent;)V";

    private TesteBetterQuesting() {
    }

    public static void main(String[] a) throws Exception {
        final Verifica v = new Verifica("TesteBetterQuesting");
        File jarServidor = new File(a[0]);
        File srg = new File(a[1]);
        File bq = new File(a[2]);
        File vanilla = new File(a[3]);
        File classesTeste = new File(a[4]);
        File stubs = new File(a[5]);
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
        try {
            transformador(v, jarServidor, servidorSrg, mcSrg, bq, vanilla, classesTeste);
            final URL[] mundo = {classesTeste.toURI().toURL(), stubs.toURI().toURL(), servidorSrg.toURI().toURL(), bq.toURI().toURL()};
            diferencial(v, new CarregadorMods(mundo, TesteBetterQuesting.class.getClassLoader(), null), rapido);
            if (fonteMut != null && cpMut != null) {
                TesteMutacaoBQ.rodar(v, new TesteMutacaoBQ.Fabrica() {
                    public ClassLoader carregador(URL primeiro) {
                        URL[] u = new URL[mundo.length + 1];
                        u[0] = primeiro;
                        System.arraycopy(mundo, 0, u, 1, mundo.length);
                        return new CarregadorMods(u, TesteBetterQuesting.class.getClassLoader(), null);
                    }
                }, new File(fonteMut), new File(classesTeste.getParentFile(), "mutacao-bq"), cpMut, servidorSrg);
            }
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    // ------------------------------------------------------------------ parte 1: transformador

    static void transformador(Verifica v, File jarServidor, File servidorSrg, File mcSrg, File bq, File vanilla, File classesTeste)
            throws Exception {
        System.out.println("-- transformador (classe real do bq_standard)");
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
        URLClassLoader soOJar = new URLClassLoader(new URL[] {jarServidor.toURI().toURL()}, null);
        final ResolvedorDeGanchos ganchos = new ResolvedorDeGanchos(soOJar, new ResolvedorDeGanchos.Remapeador() {
            public String descritor(String d) {
                return rem.mapMethodDesc(d);
            }
        });

        File tmp = new File(classesTeste.getParentFile(), "config-bq");
        tmp.mkdirs();
        File yml = new File(tmp, "hoshiestar.yml");
        yml.delete();
        HoshiestarConfig cfg = HoshiestarConfig.carregar(yml);
        v.igual("desligado", cfg.betterquesting_modo, "config: modo padrao = desligado");
        v.igual(0, cfg.betterquesting_relatorio, "config: relatorio padrao = 0");
        cfg.betterquesting_modo = "ligado";

        final Correcoes correcoes = Correcoes.novoParaTeste();
        final byte[][] guardados = new byte[2][];
        final CarregadorMods[] ref = new CarregadorMods[1];
        CarregadorMods cl = new CarregadorMods(new URL[] {classesTeste.toURI().toURL(), mcSrg.toURI().toURL(),
            servidorSrg.toURI().toURL(), bq.toURI().toURL(), vanilla.toURI().toURL()},
                TesteBetterQuesting.class.getClassLoader(), new CarregadorMods.Transformador() {
                    public byte[] transformar(String nome, byte[] bytes) {
                        if (correcoes.ligadasPara(nome) == null) {
                            return bytes;
                        }
                        byte[] r = correcoes.aplicar(nome, bytes, ganchos, ref[0]);
                        guardados[0] = bytes;
                        guardados[1] = r;
                        return r;
                    }
                });
        ref[0] = cl;
        Recursos.limparParaTeste();
        BetterQuesting.registrar(cfg, correcoes, cl);
        correcoes.fechar();
        v.igual(BetterQuesting.LIGADO, BetterQuesting.modo, "registro: modo ligado");
        v.sim(Recursos.achar(BetterQuesting.ID) != null && Recursos.achar(BetterQuesting.ID).ligado, "registro: recurso aparece como ligado");
        v.sim(BetterQuesting.linhaDeEstado().startsWith("modo ligado (correcao aguardando"),
                "linha de estado antes de a classe carregar: diz que o filtro ainda nao roda (" + inicioDaLinha() + ")");

        Class<?> k = Class.forName(BetterQuesting.CLASSE, true, cl);
        Correcao c = correcoes.doRecurso(BetterQuesting.ID).get(0);
        v.igual(Correcao.Estado.APLICADA, c.estado(), "correcao aplicada na classe real do bq_standard (" + c.linha() + ")");
        v.sim(BetterQuesting.linhaDeEstado().startsWith("modo ligado (correcao aplicada);"),
                "linha de estado com a correcao aplicada (" + inicioDaLinha() + ")");
        v.sim(k.getClassLoader() == cl, "classe carregada e inicializada pelo class loader de teste (verificador da JVM)");
        v.sim(guardados[1] != null && guardados[1] != guardados[0], "bytes transformados");
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(guardados[1]), cl, false, new PrintWriter(sw));
        v.sim(sw.toString().isEmpty(), "CheckClassAdapter sem erros" + (sw.toString().isEmpty() ? "" : "\n" + sw));
        conferirForma(v, guardados[0], guardados[1]);
        gravar(new File(tmp, "EventHandler-transformada.class"), guardados[1]);
        executar(v, k, cl);

        // recusas (cada uma num registro novo, sobre os bytes originais)
        byte[] orig = guardados[0];
        HoshiestarConfig desl = HoshiestarConfig.carregar(yml);
        Correcoes c2 = registro(desl, cl);
        v.sim(c2.ligadasPara(BetterQuesting.CLASSE) == null && c2.aplicar(BetterQuesting.CLASSE, orig, ganchos, cl) == orig,
                "desligado: a classe nem entra no transformador (bytes originais)");
        v.sim(BetterQuesting.linhaDeEstado().startsWith("modo desligado;"), "linha de estado desligado: sem texto de correcao (" + inicioDaLinha() + ")");
        Correcoes c3 = registro(cfg, cl);
        byte[] outros = comMetodoAMais(orig);
        byte[] r3 = c3.aplicar(BetterQuesting.CLASSE, outros, ganchos, cl);
        v.sim(r3 == outros && c3.doRecurso(BetterQuesting.ID).get(0).estado() == Correcao.Estado.RECUSADA_TRAVA,
                "trava: outra versao do EventHandler e recusada (" + c3.doRecurso(BetterQuesting.ID).get(0).linha() + ")");
        v.sim(BetterQuesting.linhaDeEstado().startsWith("modo ligado (correcao NAO aplicada: trava de versao nao confere")
                && BetterQuesting.linhaDeEstado().contains("o filtro NAO roda"),
                "linha de estado com a trava recusada: diz que o filtro NAO roda (" + inicioDaLinha() + ")");
        ZipFile zb = new ZipFile(bq);
        String[] deps = {"bq_standard/tasks/TaskRetrieval", "bq_standard/tasks/TaskRetrieval$Detector", "bq_standard/tasks/base/TaskBase",
            "bq_standard/tasks/base/TaskProgressableBase", "betterquesting/api/utils/ItemComparison",
            "betterquesting/api/utils/BigItemStack", "betterquesting/api2/utils/OreIngredient", "betterquesting/api2/utils/ParticipantInfo"};
        int recusadas = 0;
        for (String d : deps) {
            File falsa = new File(tmp, "dep-falsa-" + d.replace('/', '_').replace('$', '_'));
            gravar(new File(falsa, d + ".class"), comMetodoAMais(RemapeadorSrg.ler(zb.getInputStream(zb.getEntry(d + ".class")))));
            URLClassLoader depFalsa = new URLClassLoader(new URL[] {falsa.toURI().toURL(), bq.toURI().toURL()}, null);
            Correcoes c4 = registro(cfg, depFalsa);
            byte[] r4 = c4.aplicar(BetterQuesting.CLASSE, orig, ganchos, cl);
            Correcao k4 = c4.doRecurso(BetterQuesting.ID).get(0);
            if (r4 == orig && k4.estado() == Correcao.Estado.NAO_APLICADA && k4.detalhe().contains("trava de dependencia")
                    && k4.detalhe().contains(d.replace('/', '.'))) {
                recusadas++;
            } else {
                System.out.println("    dependencia " + d + ": " + k4.linha());
            }
        }
        v.igual(deps.length, recusadas, "trava de dependencia: cada uma das " + deps.length + " classes travadas, mudada, recusa a correcao");
        Correcoes c5 = registro(cfg, new URLClassLoader(new URL[0], null));
        byte[] r5 = c5.aplicar(BetterQuesting.CLASSE, orig, ganchos, cl);
        Correcao k5 = c5.doRecurso(BetterQuesting.ID).get(0);
        v.sim(r5 == orig && k5.estado() == Correcao.Estado.NAO_APLICADA && k5.detalhe().contains("dependencia ausente"),
                "dependencia ausente recusa a correcao (" + k5.linha() + ")");
        v.sim(BetterQuesting.linhaDeEstado().startsWith("modo ligado (correcao NAO aplicada: padrao nao encontrado")
                && BetterQuesting.linhaDeEstado().contains("dependencia ausente")
                && BetterQuesting.linhaDeEstado().contains("o filtro NAO roda"),
                "linha de estado com dependencia ausente: diz que o filtro NAO roda (" + inicioDaLinha() + ")");
        Correcoes c6 = registro(cfg, cl);
        byte[] r6 = c6.aplicar(BetterQuesting.CLASSE, orig, new ResolvedorDeGanchos(new URLClassLoader(new URL[0], null)), cl);
        Correcao k6 = c6.doRecurso(BetterQuesting.ID).get(0);
        v.sim(r6 == orig && k6.estado() == Correcao.Estado.NAO_APLICADA && k6.detalhe().contains("gancho ausente"),
                "gancho ausente recusa a correcao (" + k6.linha() + ")");
        // convivencia com o autoteste (que poe na mesma classe uma correcao de trava errada de proposito)
        HoshiestarConfig ambos = HoshiestarConfig.carregar(yml);
        ambos.betterquesting_modo = "ligado";
        ambos.autoteste_ligado = true;
        Recursos.limparParaTeste();
        Correcoes c7 = Correcoes.novoParaTeste();
        Autoteste.registrar(ambos, c7);
        BetterQuesting.registrar(ambos, c7, cl);
        c7.fechar();
        byte[] r7 = c7.aplicar(BetterQuesting.CLASSE, orig, ganchos, cl);
        Correcao bq7 = c7.doRecurso(BetterQuesting.ID).get(0);
        Correcao at7 = null;
        for (Correcao x : c7.doRecurso("autoteste")) {
            if (x.classe().equals(BetterQuesting.CLASSE)) {
                at7 = x;
            }
        }
        v.sim(r7 != orig && bq7.estado() == Correcao.Estado.APLICADA && at7 != null && at7.estado() == Correcao.Estado.RECUSADA_TRAVA,
                "com o autoteste ligado: a do BQ e aplicada e a de trava errada do autoteste e recusada ("
                        + bq7.linha() + " / " + (at7 == null ? "?" : at7.linha()) + ")");
        BetterQuesting.zerarContadores();
    }

    /** O metodo transformado = o original + as tres alteracoes, e nada mais; o resto da classe igual. */
    static void conferirForma(Verifica v, byte[] orig, byte[] novo) {
        ClassNode a = new ClassNode();
        new ClassReader(orig).accept(a, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        ClassNode b = new ClassNode();
        new ClassReader(novo).accept(b, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        v.igual(a.methods.size(), b.methods.size(), "mesmo numero de metodos (nenhum acrescentado)");
        v.igual(a.fields.size(), b.fields.size(), "mesmo numero de campos");
        int iguais = 0;
        for (MethodNode m : a.methods) {
            MethodNode n = metodo(b, m.name, m.desc);
            if (m.name.equals("onServerTick") && m.desc.equals(DESC)) {
                continue;
            }
            if (n != null && texto(m).equals(texto(n))) {
                iguais++;
            } else {
                System.out.println("    metodo diferente: " + m.name + m.desc);
            }
        }
        v.igual(a.methods.size() - 1, iguais, "todos os outros metodos da classe estao identicos (instrucao por instrucao)");

        MethodNode mo = metodo(a, "onServerTick", DESC);
        MethodNode mn = metodo(b, "onServerTick", DESC);
        v.igual(1, contar(mo, Opcodes.INVOKEINTERFACE, "bq_standard/tasks/ITaskInventory", "onInventoryChange"),
                "original: exatamente 1 invokeinterface ITaskInventory.onInventoryChange");
        v.igual(0, contar(mn, Opcodes.INVOKEINTERFACE, "bq_standard/tasks/ITaskInventory", "onInventoryChange"),
                "transformado: nenhuma chamada direta a onInventoryChange sobrou");
        v.igual(1, contar(mn, Opcodes.INVOKESTATIC, GANCHO, "aoMudarInventario"), "transformado: exatamente 1 chamada a GanchoBQ.aoMudarInventario");
        v.igual(1, contar(mo, Opcodes.INVOKEVIRTUAL, "java/util/HashSet", "clear"), "original: exatamente 1 HashSet.clear");
        v.igual(0, contar(mn, Opcodes.INVOKEVIRTUAL, "java/util/HashSet", "clear"), "transformado: o clear virou o gancho");
        v.igual(1, contar(mn, Opcodes.INVOKESTATIC, GANCHO, "fimDoLaco"), "transformado: exatamente 1 chamada a GanchoBQ.fimDoLaco");
        v.igual(1, contar(mn, Opcodes.INVOKESTATIC, GANCHO, "inicioDoTick"), "transformado: exatamente 1 chamada a GanchoBQ.inicioDoTick");

        // instrucao por instrucao: [ALOAD 0, ALOAD 1, INVOKESTATIC inicioDoTick] + original com as duas trocas
        // (o Textifier imprime antes do codigo as anotacoes e os TRYCATCHBLOCK: as 3 instrucoes entram depois deles)
        List<String> esperado = new ArrayList<String>();
        int trocas = 0;
        boolean entrada = false;
        for (String l : linhas(mo)) {
            if (!entrada && !l.startsWith("@") && !l.startsWith("TRYCATCHBLOCK")) {
                entrada = true;
                esperado.add("ALOAD 0");
                esperado.add("ALOAD 1");
                esperado.add("INVOKESTATIC " + GANCHO + ".inicioDoTick (Ljava/lang/Object;Lcpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent;)V");
            }
            if (l.equals("INVOKEINTERFACE bq_standard/tasks/ITaskInventory.onInventoryChange (Ljava/util/Map$Entry;Lbetterquesting/api2/utils/ParticipantInfo;)V (itf)")) {
                esperado.add("INVOKESTATIC " + GANCHO + ".aoMudarInventario (Lbq_standard/tasks/ITaskInventory;Ljava/util/Map$Entry;Lbetterquesting/api2/utils/ParticipantInfo;)V");
                trocas++;
            } else if (l.equals("INVOKEVIRTUAL java/util/HashSet.clear ()V")) {
                esperado.add("INVOKESTATIC " + GANCHO + ".fimDoLaco (Ljava/util/HashSet;)V");
                trocas++;
            } else {
                esperado.add(l);
            }
        }
        List<String> visto = linhas(mn);
        v.sim(trocas == 2 && esperado.equals(visto), "onServerTick transformado = 3 instrucoes na entrada + o original com exatamente as 2 trocas ("
                + visto.size() + " linhas)" + (esperado.equals(visto) ? "" : "\n esperado=" + esperado + "\n visto=" + visto));
        v.igual(mo.tryCatchBlocks.size(), mn.tryCatchBlocks.size(), "mesmos blocos try/catch (o synchronized do BQ intacto)");
        v.igual(mo.maxLocals, mn.maxLocals, "mesmo numero de variaveis locais");

        // os alvos do Akashic-BQRelief (StandardLoopMixin): getSharedQuests em 10 metodos (require=10),
        // filterKeys em 8 (os @Redirect por metodo), com a mesma instrucao (opcode, dono, nome, descritor)
        String[] comShared = {"onPlayerInteract", "onEntityAttack", "onEntityInteract", "onItemCrafted", "onItemSmelted", "onItemAnvil",
            "onEntityKilled", "onBlockBreak", "onEntityLiving", "onServerTick"};
        int shared = 0;
        int filter = 0;
        boolean iguaisRelief = true;
        for (String nome : comShared) {
            for (MethodNode m : a.methods) {
                if (!m.name.equals(nome)) {
                    continue;
                }
                MethodNode n = metodo(b, m.name, m.desc);
                List<String> sa = chamadas(m, "betterquesting/api2/utils/ParticipantInfo", "getSharedQuests");
                List<String> sb = chamadas(n, "betterquesting/api2/utils/ParticipantInfo", "getSharedQuests");
                List<String> fa = chamadas(m, "betterquesting/api/questing/IQuestDatabase", "filterKeys");
                List<String> fb = chamadas(n, "betterquesting/api/questing/IQuestDatabase", "filterKeys");
                iguaisRelief &= sa.equals(sb) && fa.equals(fb);
                shared += sb.size();
                filter += fb.size();
            }
        }
        v.sim(iguaisRelief && shared == 10 && filter == 10,
                "alvos do BQRelief intactos: " + shared + " getSharedQuests (require=10) e " + filter
                        + " filterKeys nos metodos dele, mesma instrucao antes e depois");
        List<String> st = chamadas(mn, "betterquesting/api2/utils/ParticipantInfo", "getSharedQuests");
        List<String> ft = chamadas(mn, "betterquesting/api/questing/IQuestDatabase", "filterKeys");
        v.sim(st.size() == 1 && st.get(0).equals("INVOKEVIRTUAL betterquesting/api2/utils/ParticipantInfo.getSharedQuests ()Ljava/util/Set;")
                && ft.size() == 1 && ft.get(0).equals("INVOKEINTERFACE betterquesting/api/questing/IQuestDatabase.filterKeys (Ljava/util/Collection;)Ljava/util/Map; (itf)"),
                "onServerTick: os dois alvos do BQRelief (getSharedQuests, filterKeys) existem uma vez cada, como o @Redirect espera " + st + ft);
    }

    /** Roda o onServerTick transformado (fases START e END) com um jogador sem inventario no conjunto do BQ. */
    @SuppressWarnings("unchecked")
    static void executar(Verifica v, Class<?> k, ClassLoader cl) throws Exception {
        ThreadServidor.definir(Thread.currentThread());
        BetterQuesting.zerarContadores();
        BetterQuesting.modo = BetterQuesting.LIGADO;
        Object handler = k.getConstructor().newInstance();
        Class<?> fase = Class.forName("cpw.mods.fml.common.gameevent.TickEvent$Phase", true, cl);
        Class<?> ev = Class.forName("cpw.mods.fml.common.gameevent.TickEvent$ServerTickEvent", true, cl);
        Constructor<?> ce = ev.getConstructor(fase);
        Method m = k.getMethod("onServerTick", ev);
        Field f = k.getDeclaredField("playerInventoryUpdates");
        f.setAccessible(true);
        Set<Object> conjunto = (Set<Object>) f.get(null);
        Object jogador = null;
        try {
            Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
            jogador = u.allocateInstance(Class.forName("net.minecraft.entity.player.EntityPlayerMP", true, cl));
        } catch (Throwable t) {
            System.out.println("    (nao consegui criar um EntityPlayerMP vazio: " + t + ")");
        }
        if (jogador != null) {
            conjunto.add(jogador);
        }
        m.invoke(handler, ce.newInstance(Enum.valueOf((Class<Enum>) fase, "START")));
        v.sim(jogador != null && conjunto.isEmpty(),
                "onServerTick transformado rodou (START): o jogador sem inventario foi pulado pelo BQ e GanchoBQ.fimDoLaco esvaziou o conjunto real");
        conjunto.add(jogador != null ? jogador : new Object());
        m.invoke(handler, ce.newInstance(Enum.valueOf((Class<Enum>) fase, "END")));
        v.sim(conjunto.size() == 1, "fase END: sai logo depois do gancho de entrada (conjunto intocado)");
        conjunto.clear();
        v.igual(0L, BetterQuesting.contagem[BetterQuesting.ERROS], "nenhum erro nosso ao rodar a classe transformada");
    }

    // ------------------------------------------------------------------ parte 2: diferencial e casos

    @SuppressWarnings("unchecked")
    static void diferencial(Verifica v, ClassLoader cl, boolean rapido) throws Exception {
        System.out.println("-- diferencial (classes reais do BQ, ganchos do jar construido, Minecraft/Forge de mentira)");
        Class<?> dif = Class.forName("hoshiestar.teste.betterquesting.mundo.Diferencial", true, cl);
        Method rodar = dif.getMethod("rodar", int.class, long.class, int.class);
        Class<?> g = Class.forName("hoshiestar.betterquesting.filtro.GanchoBQ", false, cl);
        v.sim(g.getClassLoader() == cl && cl.getResource("hoshiestar/betterquesting/filtro/GanchoBQ.class").toString().contains("-srg.jar"),
                "o gancho testado e o do server.jar construido (" + cl.getResource("hoshiestar/betterquesting/filtro/GanchoBQ.class") + ")");
        long[][] rodadas = rapido ? new long[][] {{20000, 3}} : new long[][] {{200000, 3}, {20000, 1}, {20000, 7}};
        long total = 0;
        for (long[] r : rodadas) {
            BetterQuesting.zerarContadores();
            List<String> s = (List<String>) rodar.invoke(null, (int) r[0], r[1], BetterQuesting.LIGADO);
            total += r[0];
            System.out.println("    " + s.get(0));
            for (int i = 1; i < s.size(); i++) {
                System.out.println("    " + s.get(i));
            }
            long[] c = BetterQuesting.contagem;
            v.sim(s.size() == 1 && s.get(0).endsWith("DIFERENCAS=0") && c[BetterQuesting.ERROS] == 0 && BetterQuesting.modo == BetterQuesting.LIGADO,
                    "diferencial semente " + r[1] + ": " + r[0] + " cenarios, 0 diferencas, nenhum erro nosso");
            v.sim(c[BetterQuesting.PULA_CONSUMO] > 0 && c[BetterQuesting.PULA_COMPLETA] > 0 && c[BetterQuesting.PULA_SEM_ITEM] > 0
                    && c[BetterQuesting.PASSA_PARCIAL] > 0 && c[BetterQuesting.PASSA_SATISFEITA] > 0 && c[BetterQuesting.PASSA_ORE] > 0
                    && c[BetterQuesting.PASSA_PRESENTE] > 0 && c[BetterQuesting.PASSA_CLASSE] > 0 && c[BetterQuesting.PASSA_INVENTARIO] > 0
                    && c[BetterQuesting.PASSA_ITEM_INVALIDO] > 0,
                    "diferencial semente " + r[1] + ": todas as regras de pular e todos os motivos principais de ir ao original aconteceram");
        }
        System.out.println("    total: " + total + " cenarios no modo ligado");
        BetterQuesting.zerarContadores();
        List<String> s = (List<String>) rodar.invoke(null, 20000, 11L, BetterQuesting.VERIFICAR);
        System.out.println("    " + s.get(0));
        long[] c = BetterQuesting.contagem;
        v.sim(s.size() == 1 && s.get(0).endsWith("DIFERENCAS=0") && c[BetterQuesting.COMPARADAS] > 0 && c[BetterQuesting.DIVERGENCIAS] == 0
                && c[BetterQuesting.ERROS] == 0,
                "modo verificar: 20000 cenarios, resultado = original, " + c[BetterQuesting.COMPARADAS] + " comparadas, 0 divergencias");

        Class<?> casos = Class.forName("hoshiestar.teste.betterquesting.mundo.Casos", true, cl);
        List<String> nomes = (List<String>) casos.getMethod("nomes").invoke(null);
        List<String> falhas = (List<String>) casos.getMethod("rodar").invoke(null);
        for (String n : nomes) {
            String f = null;
            for (String x : falhas) {
                if (x.startsWith(n + ": ")) {
                    f = x;
                }
            }
            v.sim(f == null, "caso: " + n + (f == null ? "" : " -> " + f.substring(n.length() + 2)));
        }
        BetterQuesting.zerarContadores();
        System.out.println("    " + dif.getMethod("bancada").invoke(null));
        BetterQuesting.zerarContadores();
        BetterQuesting.modo = BetterQuesting.LIGADO;
    }

    // ------------------------------------------------------------------ auxiliares

    /** O comeco da linha de estado (modo e correcao), para as mensagens dos testes. */
    static String inicioDaLinha() {
        String l = BetterQuesting.linhaDeEstado();
        int i = l.indexOf("; chamadas ");
        return i < 0 ? l : l.substring(0, i);
    }

    static Correcoes registro(HoshiestarConfig cfg, ClassLoader deps) {
        Recursos.limparParaTeste();
        Correcoes c = Correcoes.novoParaTeste();
        BetterQuesting.registrar(cfg, c, deps);
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

    static MethodNode metodo(ClassNode cn, String nome, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(nome) && m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }

    static int contar(MethodNode m, int opcode, String dono, String nome) {
        int n = 0;
        for (AbstractInsnNode x = m.instructions.getFirst(); x != null; x = x.getNext()) {
            if (x.getOpcode() == opcode && x instanceof MethodInsnNode && ((MethodInsnNode) x).owner.equals(dono)
                    && ((MethodInsnNode) x).name.equals(nome)) {
                n++;
            }
        }
        return n;
    }

    static List<String> chamadas(MethodNode m, String dono, String nome) {
        List<String> l = new ArrayList<String>();
        if (m == null) {
            l.add("(metodo ausente)");
            return l;
        }
        for (String s : linhas(m)) {
            if (s.contains(" " + dono + "." + nome + " ")) {
                l.add(s);
            }
        }
        return l;
    }

    /** As instrucoes do metodo em texto (sem linhas, quadros, maxstack/maxlocals). */
    static List<String> linhas(MethodNode m) {
        Textifier t = new Textifier();
        m.accept(new TraceMethodVisitor(t));
        StringWriter sw = new StringWriter();
        t.print(new PrintWriter(sw));
        List<String> l = new ArrayList<String>();
        for (String s : sw.toString().split("\n")) {
            String x = s.trim();
            if (x.isEmpty() || x.startsWith("MAXSTACK") || x.startsWith("MAXLOCALS") || x.startsWith("LINENUMBER") || x.startsWith("FRAME")) {
                continue;
            }
            l.add(x);
        }
        return l;
    }

    static String texto(MethodNode m) {
        return linhas(m).toString();
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
