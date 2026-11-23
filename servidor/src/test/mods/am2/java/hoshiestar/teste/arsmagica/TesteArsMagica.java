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
package hoshiestar.teste.arsmagica;

import hoshiestar.api.HashClasse;
import hoshiestar.api.Recursos;
import hoshiestar.api.Relogio;
import hoshiestar.api.ThreadServidor;
import hoshiestar.api.Trava;
import hoshiestar.arsmagica.ArsMagica;
import hoshiestar.arsmagica.Atalhos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.Chamada;
import hoshiestar.embutidos.asm.Gancho;
import hoshiestar.embutidos.asm.PadraoNaoEncontrado;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import hoshiestar.teste.Verifica;
import hoshiestar.teste.mods.CarregadorMods;
import hoshiestar.teste.mods.RemapeadorSrg;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Teste isolado do recurso 1.F (Ars Magica 2: atalhos no {@code am2.AMEventHandler}).
 * <ol>
 * <li><b>Unidades</b>: a logica de decisao ({@link Atalhos}), a estatistica no log, o registro e a config,
 * o subcomando.</li>
 * <li><b>Transformador sobre a classe REAL</b> {@code am2.AMEventHandler} (do jar do pacote), pelo registro
 * de correcoes REAL ({@link ArsMagica#registrar}), com os ganchos conferidos no arquivo do server.jar
 * construido: trava, passa no CheckClassAdapter e no verificador da JVM, exatamente as chamadas esperadas
 * envolvidas (e nenhuma outra instrucao muda), recusas e atomicidade.</li>
 * <li><b>Harness com o bytecode REAL</b> (classe real, reduzida aos metodos testados, com Minecraft/Forge de
 * mentira) apontando para os ganchos do jar construido: hash do estado identico entre original, ligado,
 * desligado e trocado em execucao, varias sementes; casos montados a mao; controle negativo com ganchos
 * errados.</li>
 * <li><b>Ordem dos transformadores</b>: nenhuma classe do AM2 nem do AM2PlayGround que cita
 * {@code AMEventHandler} e transformador.</li>
 * </ol>
 *
 * <p>Argumentos: server.jar construido, pasta com os jars "-srg", jar do AM2, jar do AM2PlayGround, jar vanilla,
 * pasta das classes de teste, pasta dos stubs compilados; opcionais: "rapido" e "parte=": "verificado" (unidades,
 * transformador, comando e ordem: tem de rodar COM o verificador da JVM ligado), "harness" (o harness, que roda com
 * -XX:-BytecodeVerificationRemote porque a classe reduzida cita centenas de classes que nao ha como simular) e
 * "diferencial" (o harness sem a indicacao de custo; usado pela mutacao do fonte; sai com codigo 1 se algo falhar).
 */
public final class TesteArsMagica {

    static final String AM = "am2.AMEventHandler";
    static final String JOGADOR = "HandlePlayerSpellExtendedProperties";
    static final String DESC_JOGADOR = "(Lnet/minecraft/entity/player/EntityPlayer;Lam2/playerextensions/ExtendedProperties;)V";
    static final String VIVO = "onEntityLiving";
    static final String DESC_VIVO = "(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V";
    static final String GANCHO = "hoshiestar/arsmagica/atalho/GanchoAM2";

    private TesteArsMagica() {
    }

    /** O ambiente: arquivos e o conferidor de ganchos (os do jar construido, com os nomes do Minecraft traduzidos). */
    static final class Amb {
        File jarServidor;
        File servidorSrg;
        File mcSrg;
        File am2;
        File am2pg;
        File vanilla;
        File classesTeste;
        File stubs;
        File tmp;
        ResolvedorDeGanchos ganchos;
        byte[] originais;
        boolean rapido;
    }

    public static void main(String[] a) throws Exception {
        final Verifica v = new Verifica("TesteArsMagica");
        Amb amb = new Amb();
        amb.jarServidor = new File(a[0]);
        File srg = new File(a[1]);
        amb.am2 = new File(a[2]);
        amb.am2pg = new File(a[3]);
        amb.vanilla = new File(a[4]);
        amb.classesTeste = new File(a[5]);
        amb.stubs = new File(a[6]);
        String parte = "tudo";
        for (int i = 7; i < a.length; i++) {
            if (a[i].equals("rapido")) {
                amb.rapido = true;
            } else if (a[i].startsWith("parte=")) {
                parte = a[i].substring(6);
            }
        }
        amb.servidorSrg = new File(srg, amb.jarServidor.getName().replaceAll("\\.jar$", "") + "-srg.jar");
        amb.mcSrg = new File(srg, amb.vanilla.getName().replaceAll("\\.jar$", "") + "-srg.jar");
        amb.tmp = new File(amb.classesTeste.getParentFile(), "tmp-am2");
        amb.tmp.mkdirs();
        try {
            preparar(amb);
            ThreadServidor.definir(Thread.currentThread());
            if (parte.equals("diferencial")) {
                harness(v, amb, true);
            } else {
                if (!parte.equals("harness")) {
                    unidades(v, amb);
                    transformador(v, amb);
                    comando(v, amb);
                    ordemDosTransformadores(v, amb);
                }
                if (!parte.equals("verificado")) {
                    harness(v, amb, false);
                }
            }
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    static void preparar(Amb amb) throws Exception {
        final ZipFile zv = new ZipFile(amb.vanilla);
        final ZipFile zs = new ZipFile(amb.jarServidor);
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
        URLClassLoader soOJar = new URLClassLoader(new URL[] {amb.jarServidor.toURI().toURL()}, null);
        amb.ganchos = new ResolvedorDeGanchos(soOJar, new ResolvedorDeGanchos.Remapeador() {
            public String descritor(String d) {
                return rem.mapMethodDesc(d);
            }
        });
        ZipFile za = new ZipFile(amb.am2);
        amb.originais = RemapeadorSrg.ler(za.getInputStream(za.getEntry("am2/AMEventHandler.class")));
        za.close();
    }

    // ------------------------------------------------------------------ parte 1: unidades

    static void unidades(Verifica v, Amb amb) throws Exception {
        System.out.println("-- unidades (decisao, estatistica, registro e config)");
        // ---- a decisao
        Atalhos.zerarParaTeste();
        Atalhos.varredura = true;
        Atalhos.diluicao = true;
        Atalhos.estatisticaMinutos = 0;
        ThreadServidor.definir(Thread.currentThread());
        Map<String, Integer> vazio = new HashMap<String, Integer>();
        Map<String, Integer> um = new HashMap<String, Integer>();
        um.put("x", 1);
        v.sim(!Atalhos.varrerMundos(vazio), "F1: mapa vazio e interruptor ligado -> pula");
        v.sim(Atalhos.varrerMundos(um), "F1: mapa com 1 entrada -> original");
        v.sim(Atalhos.varrerMundos(null), "F1: mapa nulo -> original");
        Atalhos.varredura = false;
        v.sim(Atalhos.varrerMundos(vazio), "F1: interruptor desligado -> original, mesmo com mapa vazio");
        Atalhos.varredura = true;
        v.sim(!Atalhos.consultarDesativada(vazio) && !Atalhos.consultarSkill(vazio), "F2: mapa vazio e interruptor ligado -> pula os dois passos");
        v.sim(Atalhos.consultarDesativada(um) && Atalhos.consultarSkill(um), "F2: mapa com 1 entrada -> original nos dois passos");
        v.sim(Atalhos.consultarDesativada(null) && Atalhos.consultarSkill(null), "F2: mapa nulo -> original");
        Atalhos.diluicao = false;
        v.sim(Atalhos.consultarDesativada(vazio) && Atalhos.consultarSkill(vazio), "F2: interruptor desligado -> original");
        Atalhos.diluicao = true;
        long[] c = Atalhos.contagem;
        v.sim(c[Atalhos.VARRER_PULOU] == 1 && c[Atalhos.VARRER_ORIGINAL] == 3 && c[Atalhos.DILUIR_PULOU] == 1 && c[Atalhos.DILUIR_ORIGINAL] == 3
                && c[Atalhos.ERROS] == 0, "contadores: F1 puladas 1 / original 3; F2 puladas 1 / original 3 (so o segundo passo conta); 0 erros: "
                + Arrays.toString(c));
        // outra thread
        final boolean[] r = new boolean[3];
        Thread t = new Thread(new Runnable() {
            public void run() {
                r[0] = Atalhos.varrerMundos(new HashMap<String, Integer>());
                r[1] = Atalhos.consultarDesativada(new HashMap<String, Integer>());
                r[2] = Atalhos.consultarSkill(new HashMap<String, Integer>());
            }
        });
        t.start();
        t.join();
        v.sim(r[0] && r[1] && r[2] && c[Atalhos.OUTRA_THREAD] == 2, "de outra thread: sempre o original (2 contadas)");
        Thread antes = ThreadServidor.thread();
        ThreadServidor.definir(null);
        v.sim(Atalhos.varrerMundos(vazio) && Atalhos.consultarDesativada(vazio), "antes de existir a thread do servidor: original");
        ThreadServidor.definir(antes);
        // erro nosso
        Map<String, Integer> quebrado = new HashMap<String, Integer>() {
            @Override
            public boolean isEmpty() {
                throw new IllegalStateException("teste");
            }
        };
        v.sim(Atalhos.varrerMundos(quebrado) && !Atalhos.varredura && Atalhos.varreduraDesligadaPorErro != null && c[Atalhos.ERROS] == 1,
                "F1: excecao no mapa -> original, atalho desligado, erro registrado");
        v.sim(Atalhos.consultarDesativada(quebrado) && !Atalhos.diluicao && Atalhos.diluicaoDesligadaPorErro != null && c[Atalhos.ERROS] == 2,
                "F2: excecao no mapa -> original, atalho desligado, erro registrado");

        // falha de gancho informada pelo tratador da ponte: desliga so o atalho dele, conta e registra o motivo
        Atalhos.zerarParaTeste();
        Atalhos.varredura = true;
        Atalhos.diluicao = true;
        Atalhos.falhaVarredura(new NoSuchMethodError("teste"));
        v.sim(!Atalhos.varredura && Atalhos.diluicao && Atalhos.varreduraDesligadaPorErro != null && Atalhos.varreduraDesligadaPorErro.contains("NoSuchMethodError")
                && c[Atalhos.ERROS] == 1, "falha de gancho na ponte da varredura: desliga so a varredura, conta e guarda o motivo (" + Atalhos.varreduraDesligadaPorErro + ")");
        Atalhos.falhaDiluicao(new NoClassDefFoundError("teste"));
        v.sim(!Atalhos.diluicao && Atalhos.diluicaoDesligadaPorErro != null && Atalhos.diluicaoDesligadaPorErro.contains("NoClassDefFoundError")
                && c[Atalhos.ERROS] == 2, "falha de gancho nas pontes do DiluteTime: desliga o DiluteTime, conta e guarda o motivo");
        Atalhos.zerarParaTeste();

        // ---- a estatistica no log (relogio de parede e de ticks falsos)
        Atalhos.zerarParaTeste();
        Atalhos.varredura = true;
        Atalhos.diluicao = true;
        Atalhos.estatisticaMinutos = 5;
        final List<String> linhas = new ArrayList<String>();
        final long[] agora = {1000000L};
        Atalhos.saidaParaTeste(new Atalhos.Saida() {
            public void linha(String texto) {
                linhas.add(texto);
            }
        });
        Atalhos.relogioParaTeste(new Atalhos.RelogioDeParede() {
            public long ms() {
                return agora[0];
            }
        });
        Relogio.ticks = 1000;
        Atalhos.varrerMundos(vazio);      // arma o relogio (nenhuma linha)
        v.igual(0, linhas.size(), "estatistica: a primeira chamada so arma o relogio, sem linha");
        for (int i = 0; i < 40; i++) {
            Atalhos.varrerMundos(vazio);
        }
        for (int i = 0; i < 10; i++) {
            Atalhos.varrerMundos(um);
        }
        for (int i = 0; i < 7; i++) {
            Atalhos.consultarDesativada(vazio);
        }
        agora[0] += 4 * 60000L + 59000L;
        Relogio.ticks += 200;
        Atalhos.varrerMundos(vazio);
        v.igual(0, linhas.size(), "estatistica: antes de 5 minutos nao escreve");
        agora[0] += 2000L;
        Relogio.ticks += 200;
        Atalhos.varrerMundos(vazio);
        v.igual(1, linhas.size(), "estatistica: depois de 5 minutos escreve uma linha");
        String l = linhas.isEmpty() ? "" : linhas.get(0);
        // janela desde a primeira chamada: F1 puladas 1+40+1+1 = 43? (1 de armar, 40, 1, 1), original 10; DiluteTime puladas 7
        v.sim(l.contains("varredura de mundos: puladas 42, rodou o original 10") && l.contains("checagem DiluteTime: puladas 7, rodou o original 0")
                && l.contains("mapas do AM2 agora: aceleradas ?, retardadas ?"),
                "estatistica: a linha traz as puladas e as que rodaram o original: " + l);
        v.sim(l.contains("totais desde o boot: varredura puladas 43 / original 10, DiluteTime puladas 7 / original 0, erros 0, outra thread 0"),
                "estatistica: a linha traz os totais desde o boot: " + l);
        Relogio.ticks += 50;
        Atalhos.varrerMundos(vazio);
        v.igual(1, linhas.size(), "estatistica: sem nova linha ate passar mais 5 minutos");
        agora[0] += 5 * 60000L + 1;
        Relogio.ticks += 200;
        Atalhos.varrerMundos(um);
        v.igual(2, linhas.size(), "estatistica: segunda linha depois de mais 5 minutos");
        v.sim(linhas.get(1).contains("varredura de mundos: puladas 1, rodou o original 1"), "estatistica: a segunda linha so conta a janela nova: " + linhas.get(1));
        // o relogio monotonico (nanoTime) pode comecar em zero ou negativo: a primeira chamada arma mesmo assim
        for (long inicio : new long[] {0L, -7000000L}) {
            Atalhos.zerarParaTeste();
            Atalhos.varredura = true;
            Atalhos.estatisticaMinutos = 5;
            linhas.clear();
            agora[0] = inicio;
            Relogio.ticks += 1000;
            Atalhos.varrerMundos(vazio);
            v.igual(0, linhas.size(), "estatistica com relogio comecando em " + inicio + ": a primeira chamada so arma");
            agora[0] += 5 * 60000L + 1;
            Relogio.ticks += 200;
            Atalhos.varrerMundos(vazio);
            v.igual(1, linhas.size(), "estatistica com relogio comecando em " + inicio + ": escreve depois de 5 minutos");
        }
        // 0 = nunca
        Atalhos.zerarParaTeste();
        linhas.clear();
        Atalhos.estatisticaMinutos = 0;
        Relogio.ticks += 100000;
        for (int i = 0; i < 100; i++) {
            agora[0] += 3600000L;
            Relogio.ticks += 1000;
            Atalhos.varrerMundos(vazio);
        }
        v.igual(0, linhas.size(), "estatistica 0 = nunca escreve");
        Atalhos.saidaParaTeste(null);
        Atalhos.relogioParaTeste(null);
        Atalhos.zerarParaTeste();

        // ---- config e registro
        File yml = new File(amb.tmp, "hoshiestar.yml");
        yml.delete();
        HoshiestarConfig cfg = HoshiestarConfig.carregar(yml);
        v.sim(!cfg.arsmagica_varredura && !cfg.arsmagica_diluicao && cfg.arsmagica_estatistica == 5,
                "config: padrao varredura=false, diluicao=false, estatistica=5");
        String texto = new String(java.nio.file.Files.readAllBytes(yml.toPath()), "UTF-8");
        v.sim(texto.contains("arsmagica:") && texto.contains("varredura: false") && texto.contains("diluicao: false") && texto.contains("estatistica: 5"),
                "config: o hoshiestar.yml criado tem a secao arsmagica com as tres chaves");
        v.sim(cfg.avisos().isEmpty(), "config: nenhum aviso na criacao");

        Recursos.limparParaTeste();
        Correcoes cs = Correcoes.novoParaTeste();
        ArsMagica.registrar(cfg, cs);
        cs.fechar();
        v.sim(!Recursos.achar(ArsMagica.ID).ligado && cs.ligadasPara(AM) == null, "registro: tudo desligado -> recurso desligado e a classe nem entra no transformador");
        List<Correcao> lista = cs.doRecurso(ArsMagica.ID);
        v.sim(lista.size() == 2 && lista.get(0).estado() == Correcao.Estado.DESLIGADA && lista.get(1).estado() == Correcao.Estado.DESLIGADA,
                "registro: duas correcoes (varredura e DiluteTime), ambas desligadas");
        for (Correcao k : lista) {
            v.sim(k.trava.aceitos().contains(ArsMagica.SHA_EVENTOS) && k.classe().equals(AM), "trava: " + AM + " com o SHA-256 do AM2.5-LTS-1.6.11-LE-CR40");
        }
        v.igual("270c2ddf51bada28728b0f7eaa5ea7bc0f7d8248a0db3042f254c1f4155d04c3", HashClasse.sha256(amb.originais), "o AMEventHandler do jar tem o hash da trava");
        cfg.arsmagica_varredura = true;
        Recursos.limparParaTeste();
        cs = Correcoes.novoParaTeste();
        ArsMagica.registrar(cfg, cs);
        cs.fechar();
        v.sim(Recursos.achar(ArsMagica.ID).ligado && cs.ligadasPara(AM) != null && cs.ligadasPara(AM).length == 1,
                "registro: so a varredura ligada -> recurso ligado, uma correcao entra no transformador");
        cfg.arsmagica_estatistica = -3;
        Recursos.limparParaTeste();
        ArsMagica.registrar(cfg, Correcoes.novoParaTeste());
        v.igual(0, Atalhos.estatisticaMinutos, "registro: estatistica negativa vira 0 (nunca)");
        v.sim(ArsMagica.linhaDeEstado().contains("estatistica a cada 0 min"), "linha de estado: " + ArsMagica.linhaDeEstado());
        Recursos.limparParaTeste();
    }

    // ------------------------------------------------------------------ parte 2: transformador

    /** Registro novo com os dois atalhos (ou so um) ligados. */
    static Correcoes registro(boolean f1, boolean f2, File tmp) {
        File yml = new File(tmp, "hoshiestar-reg.yml");
        yml.delete();
        HoshiestarConfig cfg = HoshiestarConfig.carregar(yml);
        cfg.arsmagica_varredura = f1;
        cfg.arsmagica_diluicao = f2;
        Recursos.limparParaTeste();
        Correcoes c = Correcoes.novoParaTeste();
        ArsMagica.registrar(cfg, c);
        c.fechar();
        return c;
    }

    static void transformador(Verifica v, final Amb amb) throws Exception {
        System.out.println("-- transformador (classe real am2.AMEventHandler)");
        final Correcoes correcoes = registro(true, true, amb.tmp);
        final byte[][] guardados = new byte[2][];
        final CarregadorMods[] ref = new CarregadorMods[1];
        URL[] urls = {amb.mcSrg.toURI().toURL(), amb.servidorSrg.toURI().toURL(), amb.am2.toURI().toURL(), amb.vanilla.toURI().toURL()};
        CarregadorMods cl = new CarregadorMods(urls, TesteArsMagica.class.getClassLoader(), new CarregadorMods.Transformador() {
            public byte[] transformar(String nome, byte[] bytes) {
                if (!nome.equals(AM)) {
                    return bytes;
                }
                byte[] r = correcoes.aplicar(nome, bytes, amb.ganchos, ref[0]);
                guardados[0] = bytes;
                guardados[1] = r;
                return r;
            }
        });
        ref[0] = cl;
        v.sim(ArsMagica.linhaDeEstado().contains("varredura de mundos (F1) ligada (correcao aguardando a classe do AM2 ser carregada: o atalho ainda NAO roda)"),
                "linha de estado antes de a classe carregar: diz que os atalhos ainda NAO rodam");
        Class<?> k = Class.forName(AM, false, cl);
        List<Correcao> lista = correcoes.doRecurso(ArsMagica.ID);
        v.igual(Correcao.Estado.APLICADA, lista.get(0).estado(), "correcao F1 aplicada na classe real (" + lista.get(0).linha() + ")");
        v.igual(Correcao.Estado.APLICADA, lista.get(1).estado(), "correcao F2 aplicada na classe real (" + lista.get(1).linha() + ")");
        v.sim(ArsMagica.linhaDeEstado().contains("varredura de mundos (F1) ligada (correcao aplicada)")
                && ArsMagica.linhaDeEstado().contains("checagem DiluteTime (F2) ligada (correcao aplicada)"),
                "linha de estado com as duas correcoes aplicadas: " + ArsMagica.linhaDeEstado());
        v.sim(guardados[1] != null && guardados[1] != guardados[0] && !Arrays.equals(guardados[0], guardados[1]), "bytes transformados");
        v.igual(HashClasse.sha256(amb.originais), HashClasse.sha256(guardados[0]), "os bytes que chegaram ao transformador sao os do jar");
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(guardados[1]), cl, false, new PrintWriter(sw));
        v.sim(sw.toString().isEmpty(), "CheckClassAdapter sem erros na classe real transformada" + (sw.toString().isEmpty() ? "" : "\n" + sw));
        // verificador da JVM: getDeclaredMethods() liga (verifica) a classe sem inicializar
        try {
            k.getDeclaredMethods();
            v.sim(true, "verificador da JVM aceita a classe real transformada (carregada e ligada, " + k.getDeclaredMethods().length + " metodos)");
        } catch (Throwable t) {
            v.erro("verificador da JVM recusou a classe real transformada", t);
        }
        // a original tambem liga neste class loader (controle: se a original falhasse, o teste nao diria nada)
        CarregadorMods clOrig = new CarregadorMods(urls, TesteArsMagica.class.getClassLoader(), null);
        try {
            Class.forName(AM, false, clOrig).getDeclaredMethods();
            v.sim(true, "controle: a classe original liga no mesmo class loader");
        } catch (Throwable t) {
            v.erro("controle: a classe original nao liga neste class loader (o teste do verificador nao vale)", t);
        }
        // controle negativo: sem o quadro de pilha da ponte a JVM tem de recusar
        {
            ClassNode cn = Asm.ler(guardados[1]);
            MethodNode ponte = null;
            for (MethodNode m : cn.methods) {
                if (m.name.startsWith("hoshiestar$envolve$")) {
                    ponte = m;
                    break;
                }
            }
            if (ponte != null) {
                for (AbstractInsnNode n : ponte.instructions.toArray()) {
                    if (n instanceof FrameNode) {
                        ponte.instructions.remove(n);
                    }
                }
            }
            final byte[] semQuadro = Asm.escrever(cn);
            CarregadorMods clSem = new CarregadorMods(urls, TesteArsMagica.class.getClassLoader(), new CarregadorMods.Transformador() {
                public byte[] transformar(String nome, byte[] bytes) {
                    return nome.equals(AM) ? semQuadro : bytes;
                }
            });
            boolean recusou = false;
            try {
                Class.forName(AM, false, clSem).getDeclaredMethods();
            } catch (VerifyError e) {
                recusou = true;
            } catch (Throwable t) {
                System.out.println("    controle negativo: outro erro " + t);
            }
            v.sim(ponte != null && recusou, "controle negativo: sem o quadro de pilha da ponte a JVM recusa a classe (VerifyError)");
        }
        gravar(new File(amb.tmp, "AMEventHandler-transformada.class"), guardados[1]);
        conferirForma(v, guardados[0], guardados[1], true, true);

        // so um atalho ligado: so ele e transformado
        for (int caso = 0; caso < 2; caso++) {
            boolean f1 = caso == 0;
            Correcoes c1 = registro(f1, !f1, amb.tmp);
            byte[] r1 = c1.aplicar(AM, amb.originais, amb.ganchos, cl);
            conferirForma(v, amb.originais, r1, f1, !f1);
        }

        // recusas (cada uma num registro novo, sobre os bytes originais)
        byte[] orig = amb.originais;
        Correcoes cd = registro(false, false, amb.tmp);
        v.sim(cd.ligadasPara(AM) == null && cd.aplicar(AM, orig, amb.ganchos, cl) == orig, "desligado: a classe nem entra no transformador (bytes originais)");
        Correcoes ct = registro(true, true, amb.tmp);
        byte[] outra = comMetodoAMais(orig);
        byte[] rt = ct.aplicar(AM, outra, amb.ganchos, cl);
        v.sim(rt == outra && ct.doRecurso(ArsMagica.ID).get(0).estado() == Correcao.Estado.RECUSADA_TRAVA
                && ct.doRecurso(ArsMagica.ID).get(1).estado() == Correcao.Estado.RECUSADA_TRAVA,
                "trava: outra versao do AMEventHandler e recusada pelas duas correcoes (" + ct.doRecurso(ArsMagica.ID).get(0).linha() + ")");
        v.sim(ArsMagica.linhaDeEstado().contains("correcao NAO aplicada: trava de versao nao confere") && ArsMagica.linhaDeEstado().contains("o atalho NAO roda"),
                "linha de estado com a trava recusada: diz que o atalho NAO roda");
        Correcoes cg = registro(true, true, amb.tmp);
        byte[] rg = cg.aplicar(AM, orig, new ResolvedorDeGanchos(new URLClassLoader(new URL[0], null)), cl);
        v.sim(rg == orig && cg.doRecurso(ArsMagica.ID).get(0).estado() == Correcao.Estado.NAO_APLICADA
                && cg.doRecurso(ArsMagica.ID).get(0).detalhe().contains("gancho ausente"), "gancho ausente recusa a correcao (" + cg.doRecurso(ArsMagica.ID).get(0).linha() + ")");

        // atomicidade do F2: se a segunda chamada nao existir, nem a primeira fica trocada
        {
            ClassNode cn = Asm.ler(orig);
            for (MethodNode m : cn.methods) {
                if (m.name.equals(VIVO) && m.desc.equals(DESC_VIVO)) {
                    for (AbstractInsnNode n : m.instructions.toArray()) {
                        if (n instanceof MethodInsnNode && ((MethodInsnNode) n).name.equals("isSkillDisabled")) {
                            ((MethodInsnNode) n).name = "isSkillDisabledX";
                        }
                    }
                }
            }
            final byte[] semSegunda = Asm.escrever(cn);
            final Method tf = ArsMagica.class.getDeclaredMethod("transformarDiluicao", ClassNode.class, ResolvedorDeGanchos.class);
            tf.setAccessible(true);
            Correcao k2 = Correcao.nova("arsmagica", "teste de atomicidade", Trava.de(AM, HashClasse.sha256(semSegunda)), true, new Correcao.Transformacao() {
                public void aplicar(ClassNode classe, ResolvedorDeGanchos g) throws PadraoNaoEncontrado {
                    try {
                        tf.invoke(null, classe, g);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        if (e.getCause() instanceof PadraoNaoEncontrado) {
                            throw (PadraoNaoEncontrado) e.getCause();
                        }
                        throw new RuntimeException(e.getCause());
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            Correcoes c2 = Correcoes.novoParaTeste();
            c2.adicionar(k2);
            c2.fechar();
            byte[] r2 = c2.aplicar(AM, semSegunda, amb.ganchos, cl);
            v.sim(r2 == semSegunda && k2.estado() == Correcao.Estado.NAO_APLICADA && k2.detalhe().contains("isSkillDisabled"),
                    "F2 atomico: sem a chamada isSkillDisabled nada e aplicado, nem o getSkill (" + k2.linha() + ")");
        }
        // as chamadas esperadas no metodo errado nao sao tocadas por engano: contagens de ordinal (1 de cada)
        {
            ClassNode cn = Asm.ler(orig);
            v.igual(1, contar(metodo(cn, JOGADOR, DESC_JOGADOR), Opcodes.INVOKESTATIC, "net/minecraftforge/common/DimensionManager", "getWorlds"),
                    "original: exatamente 1 DimensionManager.getWorlds em HandlePlayerSpellExtendedProperties");
            v.igual(1, contar(metodo(cn, VIVO, DESC_VIVO), Opcodes.INVOKEVIRTUAL, "am2/spell/SkillManager", "getSkill"),
                    "original: exatamente 1 SkillManager.getSkill em onEntityLiving");
            v.igual(1, contar(metodo(cn, VIVO, DESC_VIVO), Opcodes.INVOKEVIRTUAL, "am2/spell/SkillTreeManager", "isSkillDisabled"),
                    "original: exatamente 1 SkillTreeManager.isSkillDisabled em onEntityLiving");
            int outrosGet = 0;
            int outrosIs = 0;
            for (MethodNode m : cn.methods) {
                if (!m.name.equals(VIVO)) {
                    outrosGet += contar(m, Opcodes.INVOKEVIRTUAL, "am2/spell/SkillManager", "getSkill");
                    outrosIs += contar(m, Opcodes.INVOKEVIRTUAL, "am2/spell/SkillTreeManager", "isSkillDisabled");
                }
            }
            v.sim(outrosGet == 2 && outrosIs == 2, "original: getSkill/isSkillDisabled aparecem tambem 2x fora de onEntityLiving (em " + JOGADOR + "); esses NAO sao tocados ("
                    + outrosGet + "/" + outrosIs + ")");
        }
        Recursos.limparParaTeste();
    }

    /**
     * Forma da classe transformada: o resto da classe igual, os dois metodos alvo = original + exatamente as
     * chamadas trocadas pelas pontes, e as pontes com a chamada original uma vez e cada gancho uma vez.
     */
    static void conferirForma(Verifica v, byte[] orig, byte[] novo, boolean f1, boolean f2) {
        String rot = (f1 ? "F1" : "") + (f1 && f2 ? "+" : "") + (f2 ? "F2" : "");
        ClassNode a = Asm.ler(orig);
        ClassNode b = Asm.ler(novo);
        int pontes = (f1 ? 1 : 0) + (f2 ? 2 : 0);
        v.igual(a.methods.size() + pontes, b.methods.size(), "[" + rot + "] metodos: os " + a.methods.size() + " originais + " + pontes + " pontes");
        v.igual(a.fields.size(), b.fields.size(), "[" + rot + "] mesmo numero de campos");
        int iguais = 0;
        int comparados = 0;
        for (MethodNode m : a.methods) {
            if ((f1 && m.name.equals(JOGADOR)) || (f2 && m.name.equals(VIVO))) {
                continue;
            }
            comparados++;
            MethodNode n = metodo(b, m.name, m.desc);
            if (n != null && texto(m).equals(texto(n))) {
                iguais++;
            } else {
                System.out.println("    metodo diferente: " + m.name + m.desc);
            }
        }
        v.igual(comparados, iguais, "[" + rot + "] todos os outros metodos da classe estao identicos, instrucao por instrucao (" + comparados + ")");
        List<MethodNode> bs = new ArrayList<MethodNode>();
        for (MethodNode m : b.methods) {
            if (m.name.startsWith("hoshiestar$envolve$")) {
                bs.add(m);
            }
        }
        v.igual(pontes, bs.size(), "[" + rot + "] pontes criadas: " + pontes);
        for (MethodNode m : bs) {
            v.igual(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, m.access, "[" + rot + "] ponte " + m.name + " e private static synthetic");
        }
        // cada alvo trocado: o original com so a chamada trocada por invokestatic da ponte
        if (f1) {
            MethodNode ponte = ponte(bs, "()[Lnet/minecraft/world/WorldServer;");
            confereMetodoAlvo(v, rot, a, b, JOGADOR, DESC_JOGADOR,
                    "INVOKESTATIC net/minecraftforge/common/DimensionManager.getWorlds ()[Lnet/minecraft/world/WorldServer;", ponte,
                    "INVOKESTATIC net/minecraftforge/common/DimensionManager.getWorlds ()[Lnet/minecraft/world/WorldServer;", "varrerMundos", "semMundos", "falhaVarredura");
        }
        if (f2) {
            MethodNode pa = ponte(bs, "(Lam2/spell/SkillManager;Ljava/lang/String;)Lam2/api/spell/component/interfaces/ISkillTreeEntry;");
            MethodNode pb = ponte(bs, "(Lam2/spell/SkillTreeManager;Lam2/api/spell/component/interfaces/ISkillTreeEntry;)Z");
            // o metodo com as duas trocas
            List<String> esperado = new ArrayList<String>();
            int trocas = 0;
            MethodNode mo = metodo(a, VIVO, DESC_VIVO);
            for (String l : linhas(mo)) {
                if (l.equals("INVOKEVIRTUAL am2/spell/SkillManager.getSkill (Ljava/lang/String;)Lam2/api/spell/component/interfaces/ISkillTreeEntry;")) {
                    esperado.add("INVOKESTATIC am2/AMEventHandler." + (pa == null ? "?" : pa.name)
                            + " (Lam2/spell/SkillManager;Ljava/lang/String;)Lam2/api/spell/component/interfaces/ISkillTreeEntry;");
                    trocas++;
                } else if (l.equals("INVOKEVIRTUAL am2/spell/SkillTreeManager.isSkillDisabled (Lam2/api/spell/component/interfaces/ISkillTreeEntry;)Z")) {
                    esperado.add("INVOKESTATIC am2/AMEventHandler." + (pb == null ? "?" : pb.name)
                            + " (Lam2/spell/SkillTreeManager;Lam2/api/spell/component/interfaces/ISkillTreeEntry;)Z");
                    trocas++;
                } else {
                    esperado.add(l);
                }
            }
            List<String> visto = linhas(metodo(b, VIVO, DESC_VIVO));
            v.sim(trocas == 2 && esperado.equals(visto), "[" + rot + "] onEntityLiving transformado = original com exatamente as 2 trocas (" + visto.size() + " linhas)"
                    + (esperado.equals(visto) ? "" : "\n esperado=" + esperado + "\n visto=" + visto));
            v.igual(metodo(a, VIVO, DESC_VIVO).tryCatchBlocks.size(), metodo(b, VIVO, DESC_VIVO).tryCatchBlocks.size(), "[" + rot + "] onEntityLiving: mesmos blocos try/catch");
            conferePonte(v, rot, pa, "INVOKEVIRTUAL am2/spell/SkillManager.getSkill (Ljava/lang/String;)Lam2/api/spell/component/interfaces/ISkillTreeEntry;", "consultarSkill", "semSkill", "falhaDiluicao");
            conferePonte(v, rot, pb, "INVOKEVIRTUAL am2/spell/SkillTreeManager.isSkillDisabled (Lam2/api/spell/component/interfaces/ISkillTreeEntry;)Z", "consultarDesativada", "semDesativada", "falhaDiluicao");
        }
    }

    static MethodNode ponte(List<MethodNode> bs, String desc) {
        for (MethodNode m : bs) {
            if (m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }

    static void confereMetodoAlvo(Verifica v, String rot, ClassNode a, ClassNode b, String nome, String desc, String chamadaOriginal, MethodNode ponte,
            String ponteChamada, String deve, String sem, String falha) {
        List<String> esperado = new ArrayList<String>();
        int trocas = 0;
        for (String l : linhas(metodo(a, nome, desc))) {
            if (l.equals(chamadaOriginal)) {
                esperado.add("INVOKESTATIC am2/AMEventHandler." + (ponte == null ? "?" : ponte.name) + " ()[Lnet/minecraft/world/WorldServer;");
                trocas++;
            } else {
                esperado.add(l);
            }
        }
        List<String> visto = linhas(metodo(b, nome, desc));
        v.sim(trocas == 1 && esperado.equals(visto), "[" + rot + "] " + nome + " transformado = original com exatamente 1 troca (" + visto.size() + " linhas)"
                + (esperado.equals(visto) ? "" : "\n esperado=" + esperado + "\n visto=" + visto));
        v.igual(metodo(a, nome, desc).tryCatchBlocks.size(), metodo(b, nome, desc).tryCatchBlocks.size(), "[" + rot + "] " + nome + ": mesmos blocos try/catch");
        conferePonte(v, rot, ponte, ponteChamada, deve, sem, falha);
    }

    /**
     * A ponte PROTEGIDA: o gancho "deve" uma vez, o "sem" uma vez, o "aoFalhar" (Atalhos.falha...) uma vez e a chamada
     * original uma vez, nessa ordem, mais nenhuma chamada; um unico bloco try/catch de Throwable que cobre so
     * deve e sem (a chamada original e o aoFalhar ficam fora dele).
     */
    static void conferePonte(Verifica v, String rot, MethodNode ponte, String chamadaOriginal, String deve, String sem, String falha) {
        if (ponte == null) {
            v.sim(false, "[" + rot + "] ponte para " + deve + " nao encontrada");
            return;
        }
        List<String> chamadas = new ArrayList<String>();
        for (String l : linhas(ponte)) {
            if (l.startsWith("INVOKE")) {
                chamadas.add(l);
            }
        }
        boolean ok = chamadas.size() == 4 && chamadas.get(0).startsWith("INVOKESTATIC " + GANCHO + "." + deve + " ")
                && chamadas.get(1).startsWith("INVOKESTATIC " + GANCHO + "." + sem + " ")
                && chamadas.get(2).equals("INVOKESTATIC hoshiestar/arsmagica/Atalhos." + falha + " (Ljava/lang/Throwable;)V")
                && chamadas.get(3).equals(chamadaOriginal);
        v.sim(ok, "[" + rot + "] ponte " + ponte.name + ": " + deve + " -> " + sem + " -> " + falha + " -> chamada original (e mais nenhuma chamada): " + chamadas);
        boolean bloco = ponte.tryCatchBlocks.size() == 1 && "java/lang/Throwable".equals(ponte.tryCatchBlocks.get(0).type);
        int fim = bloco ? ponte.instructions.indexOf(ponte.tryCatchBlocks.get(0).end) : -1;
        int inicio = bloco ? ponte.instructions.indexOf(ponte.tryCatchBlocks.get(0).start) : -1;
        int iDeve = -1, iSem = -1, iFalha = -1, iOrig = -1;
        for (AbstractInsnNode n = ponte.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) n;
                int i = ponte.instructions.indexOf(n);
                if (mi.name.equals(deve)) {
                    iDeve = i;
                } else if (mi.name.equals(sem)) {
                    iSem = i;
                } else if (mi.name.equals(falha)) {
                    iFalha = i;
                } else {
                    iOrig = i;
                }
            }
        }
        v.sim(bloco && iDeve > inicio && iSem > inicio && iDeve < fim && iSem < fim && iFalha > fim && iOrig > fim,
                "[" + rot + "] ponte " + ponte.name + ": um try/catch de Throwable cobre so o 'deve' e o 'sem'; aoFalhar e a chamada original ficam fora");
    }

    // ------------------------------------------------------------------ comando

    static void comando(Verifica v, Amb amb) throws Exception {
        System.out.println("-- subcomando hoshie arsmagica");
        Class<?> k = Class.forName("hoshiestar.arsmagica.ComandoArsMagica");
        Constructor<?> ct = k.getDeclaredConstructor();
        ct.setAccessible(true);
        Object cmd = ct.newInstance();
        final Method exec = k.getDeclaredMethod("executar", org.bukkit.command.CommandSender.class, String[].class);
        exec.setAccessible(true);
        final List<String> msgs = new ArrayList<String>();
        org.bukkit.command.CommandSender quem = (org.bukkit.command.CommandSender) Proxy.newProxyInstance(TesteArsMagica.class.getClassLoader(),
                new Class<?>[] {org.bukkit.command.CommandSender.class}, new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] a) {
                        if (m.getName().equals("sendMessage") && a != null && a.length == 1 && a[0] instanceof String) {
                            msgs.add((String) a[0]);
                        }
                        return null;
                    }
                });
        // neste ponto as correcoes do ultimo registro bem sucedido do teste anterior ja nao existem; registra e aplica de novo
        Correcoes c = registro(true, true, amb.tmp);
        Method nome = k.getMethod("nome");
        nome.setAccessible(true);
        v.igual("arsmagica", nome.invoke(cmd), "subcomando se chama arsmagica");
        Atalhos.varredura = true;
        Atalhos.diluicao = true;
        exec.invoke(cmd, quem, new String[0]);
        v.sim(msgs.size() == 1 && msgs.get(0).contains("varredura de mundos (F1) ligada") && msgs.get(0).contains("checagem DiluteTime (F2)"), "sem argumento: mostra o estado: " + msgs);
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"varredura", "desligar"});
        v.sim(!Atalhos.varredura && Atalhos.diluicao && msgs.size() == 1 && msgs.get(0).contains("atalho varredura desligado"), "varredura desligar: desliga so a varredura");
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"varredura", "ligar"});
        v.sim(!Atalhos.varredura && msgs.size() == 1 && msgs.get(0).startsWith("nao ligo"),
                "varredura ligar sem a correcao aplicada: recusa e explica (" + (msgs.isEmpty() ? "" : msgs.get(0).substring(0, Math.min(60, msgs.get(0).length()))) + "...)");
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"diluicao", "tchau"});
        v.sim(msgs.size() == 1 && msgs.get(0).startsWith("uso: hoshie arsmagica"), "argumento invalido: mostra o uso");
        msgs.clear();
        exec.invoke(cmd, quem, new String[] {"x", "ligar", "y"});
        v.sim(msgs.size() == 1 && msgs.get(0).startsWith("uso: hoshie arsmagica"), "argumentos demais: mostra o uso");
        // com a correcao aplicada (classe real passada pelo transformador) o ligar funciona e limpa o erro
        msgs.clear();
        Atalhos.varreduraDesligadaPorErro = "teste";
        Atalhos.diluicaoDesligadaPorErro = "teste";
        Atalhos.diluicao = false;
        c.aplicar(AM, amb.originais, amb.ganchos, null);   // a classe real passa pelo transformador: as correcoes ficam APLICADAS
        for (Correcao kc : c.doRecurso(ArsMagica.ID)) {
            v.igual(Correcao.Estado.APLICADA, kc.estado(), "comando: correcao aplicada na classe real antes de ligar (" + kc.linha() + ")");
        }
        exec.invoke(cmd, quem, new String[] {"varredura", "ligar"});
        exec.invoke(cmd, quem, new String[] {"diluicao", "ligar"});
        v.sim(Atalhos.varredura && Atalhos.diluicao && Atalhos.varreduraDesligadaPorErro == null && Atalhos.diluicaoDesligadaPorErro == null,
                "com a correcao aplicada: ligar funciona e limpa o 'desligado por erro'");
        Atalhos.varredura = false;
        Atalhos.diluicao = false;
        Atalhos.zerarParaTeste();

        // auto-checagem dos ganchos ao ligar o servidor, caminho de REPROVACAO: aqui o class loader dos mods (HashClasse.carregadorDosMods) e o
        // do teste, que nao enxerga o Minecraft nem o AM2: o gancho nao liga, entao o atalho fica desligado, com o motivo, e o comando recusa ligar
        msgs.clear();
        Atalhos.varredura = true;
        Atalhos.diluicao = true;
        Method conferir = ArsMagica.class.getDeclaredMethod("conferirGanchosDoServidor");
        conferir.setAccessible(true);
        conferir.invoke(null);
        v.sim(Atalhos.ganchoComDefeito != null && !Atalhos.varredura && !Atalhos.diluicao,
                "auto-checagem reprovada: os dois atalhos ficam desligados e o motivo e guardado (" + Atalhos.ganchoComDefeito + ")");
        v.sim(ArsMagica.linhaDeEstado().contains("GANCHOS COM DEFEITO"), "auto-checagem reprovada: a linha de estado diz GANCHOS COM DEFEITO");
        exec.invoke(cmd, quem, new String[] {"varredura", "ligar"});
        v.sim(!Atalhos.varredura && msgs.size() == 1 && msgs.get(0).startsWith("nao ligo: a auto-checagem"), "auto-checagem reprovada: o comando recusa ligar: " + msgs);
        // sem nenhuma correcao pedida a auto-checagem nem roda
        Atalhos.zerarParaTeste();
        registro(false, false, amb.tmp);
        conferir.invoke(null);
        v.sim(Atalhos.ganchoComDefeito == null, "auto-checagem: com os dois atalhos desligados no yml nem e feita");
        Atalhos.zerarParaTeste();
        Recursos.limparParaTeste();
    }

    // ------------------------------------------------------------------ parte 3: harness

    /** Um class loader com a classe REAL reduzida aos metodos testados; transformada ou nao. */
    static CarregadorMods variante(final Amb amb, final Correcoes correcoes, final byte[][] guardado) throws Exception {
        URL[] urls = {amb.classesTeste.toURI().toURL(), amb.stubs.toURI().toURL(), amb.servidorSrg.toURI().toURL(), amb.am2.toURI().toURL()};
        final CarregadorMods[] ref = new CarregadorMods[1];
        CarregadorMods cl = new CarregadorMods(urls, TesteArsMagica.class.getClassLoader(), new CarregadorMods.Transformador() {
            public byte[] transformar(String nome, byte[] bytes) {
                if (!nome.equals(AM)) {
                    return bytes;
                }
                byte[] r = correcoes == null ? bytes : correcoes.aplicar(nome, bytes, amb.ganchos, ref[0]);
                if (guardado != null) {
                    guardado[0] = bytes;
                    guardado[1] = r;
                }
                return reduzir(r);
            }
        });
        ref[0] = cl;
        return cl;
    }

    /** Fica so com o construtor, o inicializador estatico, os dois metodos alvo e as pontes (o resto precisaria de centenas de stubs). */
    static byte[] reduzir(byte[] b) {
        ClassNode cn = Asm.ler(b);
        List<MethodNode> manter = new ArrayList<MethodNode>();
        for (MethodNode m : cn.methods) {
            if (m.name.equals("<init>") || m.name.equals("<clinit>") || m.name.equals(JOGADOR) || m.name.equals(VIVO) || m.name.startsWith("hoshiestar$envolve$")) {
                manter.add(m);
            }
        }
        cn.methods = manter;
        return Asm.escrever(cn);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> rodar(ClassLoader cl, long semente, int iter, int modo, int taxa) throws Exception {
        Class<?> r = Class.forName("hoshiestar.teste.arsmagica.mundo.Roda", true, cl);
        long[] antes = Atalhos.contagem.clone();
        Map<String, Object> m = (Map<String, Object>) r.getMethod("rodar", long.class, int.class, int.class, int.class).invoke(null, semente, iter, modo, taxa);
        long[] d = new long[Atalhos.N];
        for (int i = 0; i < d.length; i++) {
            d[i] = Atalhos.contagem[i] - antes[i];
        }
        m.put("delta", d);
        return m;
    }

    static long num(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).longValue();
    }

    static void harness(Verifica v, Amb amb, boolean soDiferencial) throws Exception {
        System.out.println("-- harness (am2.AMEventHandler real, reduzido; Minecraft/Forge de mentira; ganchos do jar construido)");
        Recursos.limparParaTeste();
        Correcoes corr = registro(true, true, amb.tmp);
        byte[][] guardado = new byte[2][];
        CarregadorMods clL = variante(amb, corr, guardado);
        CarregadorMods clO = variante(amb, null, null);
        Class<?> gancho = Class.forName("hoshiestar.arsmagica.atalho.GanchoAM2", false, clL);
        URL gu = clL.getResource("hoshiestar/arsmagica/atalho/GanchoAM2.class");
        v.sim(gancho.getClassLoader() == clL && gu != null && gu.toString().contains("-srg.jar"),
                "o gancho testado e o do server.jar construido (" + gu + ")");
        Class.forName(AM, true, clL);
        for (Correcao c : corr.doRecurso(ArsMagica.ID)) {
            v.igual(Correcao.Estado.APLICADA, c.estado(), "harness: " + c.linha());
        }
        v.sim(guardado[1] != null && !Arrays.equals(guardado[0], guardado[1]), "harness: a classe do variante transformado difere da original");

        int iter = amb.rapido ? 3000 : 12000;
        long[][] sementes = {{1, 400}, {2, 400}, {3, 2000}, {4, 50}, {5, 400}, {6, 150}, {7, 3000}, {8, 400}};
        long totalJogador = 0, totalVivo = 0, totalNaoVazioAcel = 0, totalNaoVazioRet = 0, totalPulouF1 = 0, totalPulouF2 = 0, totalOrigF1 = 0, totalOrigF2 = 0,
                totalExtras = 0, totalCancel = 0, totalNpe = 0;
        for (long[] s : sementes) {
            ThreadServidor.definir(Thread.currentThread());
            // cada rodada num class loader NOVO: o AMEventHandler guarda estado em campos estaticos (mapas de efeitos
            // por UUID, Random...) que passaria de uma rodada para a seguinte e tornaria as rodadas incomparaveis
            Map<String, Object> o = rodar(variante(amb, null, null), s[0], iter, 0, (int) s[1]);
            Map<String, Object> l0 = rodar(variante(amb, corr, null), s[0], iter, 0, (int) s[1]);
            Map<String, Object> l1 = rodar(variante(amb, corr, null), s[0], iter, 1, (int) s[1]);
            Map<String, Object> l2 = rodar(variante(amb, corr, null), s[0], iter, 2, (int) s[1]);
            Map<String, Object> l3 = rodar(variante(amb, corr, null), s[0], iter, 3, (int) s[1]);
            Map<String, Object> l4 = rodar(variante(amb, corr, null), s[0], iter, 4, (int) s[1]);
            System.out.println("    original : " + o.get("linha"));
            System.out.println("    ligado   : " + l1.get("linha") + " | F1 puladas " + ((long[]) l1.get("delta"))[Atalhos.VARRER_PULOU] + ", F2 puladas "
                    + ((long[]) l1.get("delta"))[Atalhos.DILUIR_PULOU]);
            String t = String.valueOf(o.get("trace"));
            boolean igual = true;
            for (Map<String, Object> x : Arrays.asList(l0, l1, l2, l3, l4)) {
                igual &= t.equals(x.get("trace")) && num(o, "atualizacoesExtras") == num(x, "atualizacoesExtras") && num(o, "cancelados") == num(x, "cancelados")
                        && num(o, "errosEngolidos") == num(x, "errosEngolidos") && num(o, "alcancou72") == num(x, "alcancou72");
            }
            v.sim(igual, "semente " + s[0] + " (" + iter + " iteracoes, taxa " + s[1] + "): hash do estado IGUAL entre original, desligado, ligado, trocado em execucao, so F1 e so F2 (trace="
                    + t + ")" + (igual ? "" : "\n  l0=" + l0.get("linha") + "\n  l1=" + l1.get("linha") + "\n  l2=" + l2.get("linha")));
            long[] d0 = (long[]) l0.get("delta");
            long[] d1 = (long[]) l1.get("delta");
            long[] d3 = (long[]) l3.get("delta");
            long[] d4 = (long[]) l4.get("delta");
            // desligado: nada pulado e as mesmas chamadas internas do AM2 que o original
            v.sim(d0[Atalhos.VARRER_PULOU] == 0 && d0[Atalhos.DILUIR_PULOU] == 0 && num(l0, "getWorlds") == num(o, "getWorlds")
                    && num(l0, "isSkillDisabled") == num(o, "isSkillDisabled") && num(l0, "getSkill") == num(o, "getSkill"),
                    "semente " + s[0] + ": desligado = o AM2 pede a lista de mundos e consulta o DiluteTime exatamente como o original (getWorlds " + num(l0, "getWorlds") + ")");
            // ligado: o que o AM2 deixou de pedir e exatamente o que o gancho contou como pulado
            v.sim(num(o, "getWorlds") - num(l1, "getWorlds") == d1[Atalhos.VARRER_PULOU] && d1[Atalhos.VARRER_PULOU] + d1[Atalhos.VARRER_ORIGINAL] == num(o, "chamadasJogador")
                    && num(o, "isSkillDisabled") - num(l1, "isSkillDisabled") == d1[Atalhos.DILUIR_PULOU]
                    && num(o, "getSkill") - num(l1, "getSkill") == d1[Atalhos.DILUIR_PULOU]
                    && d1[Atalhos.DILUIR_PULOU] + d1[Atalhos.DILUIR_ORIGINAL] == num(o, "chamadasVivo"),
                    "semente " + s[0] + ": ligado = as chamadas que o AM2 deixou de fazer (getWorlds " + (num(o, "getWorlds") - num(l1, "getWorlds")) + ", isSkillDisabled "
                            + (num(o, "isSkillDisabled") - num(l1, "isSkillDisabled")) + ") batem com as puladas contadas pelo gancho (" + d1[Atalhos.VARRER_PULOU] + "/" + d1[Atalhos.DILUIR_PULOU] + ")");
            // so um atalho: o outro nao age
            v.sim(d3[Atalhos.DILUIR_PULOU] == 0 && d3[Atalhos.VARRER_PULOU] > 0 && d4[Atalhos.VARRER_PULOU] == 0 && d4[Atalhos.DILUIR_PULOU] > 0
                    && num(l3, "isSkillDisabled") == num(o, "isSkillDisabled") && num(l3, "getSkill") == num(o, "getSkill") && num(l4, "getWorlds") == num(o, "getWorlds"),
                    "semente " + s[0] + ": so F1 ligado pula so varredura (" + d3[Atalhos.VARRER_PULOU] + "), so F2 ligado pula so DiluteTime (" + d4[Atalhos.DILUIR_PULOU] + ")");
            v.sim(d1[Atalhos.ERROS] == 0 && ((long[]) l2.get("delta"))[Atalhos.ERROS] == 0, "semente " + s[0] + ": nenhum erro nosso");
            totalJogador += num(o, "chamadasJogador");
            totalVivo += num(o, "chamadasVivo");
            totalNaoVazioAcel += num(o, "acelNaoVazio");
            totalNaoVazioRet += num(o, "retNaoVazio");
            totalPulouF1 += d1[Atalhos.VARRER_PULOU];
            totalPulouF2 += d1[Atalhos.DILUIR_PULOU];
            totalOrigF1 += d1[Atalhos.VARRER_ORIGINAL];
            totalOrigF2 += d1[Atalhos.DILUIR_ORIGINAL];
            totalExtras += num(o, "atualizacoesExtras");
            totalCancel += num(o, "cancelados");
            totalNpe += num(o, "errosEngolidos");
        }
        System.out.println("    total (ligado): F1 " + totalPulouF1 + " puladas e " + totalOrigF1 + " no original; F2 " + totalPulouF2 + " puladas e " + totalOrigF2
                + " no original; atualizacoes extras do Accelerate " + totalExtras + "; eventos cancelados pelo Dilute " + totalCancel
                + "; NPE engolidos pelo proprio AM2 (entrada que some no meio) " + totalNpe);
        v.sim(totalPulouF1 > 0 && totalOrigF1 > 0 && totalPulouF2 > 0 && totalOrigF2 > 0 && totalExtras > 0 && totalCancel > 0 && totalNpe > 0,
                "o harness exercitou os dois lados de cada decisao (puladas e original), atualizacoes extras, cancelamentos e a entrada que some no meio");
        v.sim(totalNaoVazioAcel > 0 && totalNaoVazioAcel < totalJogador && totalNaoVazioRet > 0 && totalNaoVazioRet < totalVivo,
                "os mapas ficaram vazios e nao vazios (aceleradas nao vazio em " + totalNaoVazioAcel + " de " + totalJogador + " chamadas; retardadas em " + totalNaoVazioRet
                        + " de " + totalVivo + ")");

        // ---- casos montados a mao
        {
            ThreadServidor.definir(Thread.currentThread());
            Map<String, ?>[] vO = casos(variante(amb, null, null), true, false);
            Map<String, ?>[] vL = casos(variante(amb, corr, null), true, true);
            Map<String, ?>[] vD = casos(variante(amb, corr, null), false, true);
            conferirCasos(v, vO, vL, vD);
        }

        // ---- reentrancia (caso adversario da revisao)
        reentrancia(v, amb, corr);

        // ---- ganchos que NAO LIGAM em tempo de execucao: a ponte protegida segue pelo original; a auto-checagem acusa
        if (!soDiferencial) {
            ganchosQuebrados(v, amb, corr, Math.min(iter, 3000));
        }

        // ---- controle negativo: ganchos errados tem de ser pegos
        String[] nomes = {"F1 pula sempre", "F1 pula quando o mapa NAO esta vazio", "F2 (isSkillDisabled) pula sempre", "F2 (getSkill) pula sempre"};
        for (int modo = 1; modo <= 4; modo++) {
            boolean aplicou = novoErrado(amb, modo, new boolean[1]) != null;
            boolean pego = false;
            for (long[] s : sementes) {
                Map<String, Object> o = rodar(variante(amb, null, null), s[0], Math.min(iter, 4000), 0, (int) s[1]);
                boolean[] ap = new boolean[1];
                Map<String, Object> e = rodar(novoErrado(amb, modo, ap), s[0], Math.min(iter, 4000), 1, (int) s[1]);
                aplicou &= ap[0];
                if (!String.valueOf(o.get("trace")).equals(e.get("trace"))) {
                    pego = true;
                    break;
                }
            }
            v.sim(aplicou && pego, "controle negativo: o gancho errado '" + nomes[modo - 1] + "' muda o hash do estado (o harness enxerga o erro)");
        }

        // ---- indicacao de custo (NAO e medicao de servidor)
        if (!soDiferencial) {
            Class<?> r = Class.forName("hoshiestar.teste.arsmagica.mundo.Roda", true, variante(amb, corr, null));
            Method b = r.getMethod("bancadaF1", int.class, int.class, int.class, boolean.class);
            double off = 0, on = 0;
            for (int i = 0; i < 3; i++) {
                off = (Double) b.invoke(null, 60, 40, 20000, false);
                on = (Double) b.invoke(null, 60, 40, 20000, true);
            }
            System.out.println("    indicacao do harness (NAO e medicao de servidor): HandlePlayerSpellExtendedProperties com mapa vazio, 60 mundos x 40 entidades:"
                    + " desligado " + Math.round(off) + " ns/chamada, ligado " + Math.round(on) + " ns/chamada (inclui o custo da reflexao do harness)");
            v.sim(on < off, "indicacao: com o mapa vazio o ligado e mais rapido que o desligado no harness (" + Math.round(off) + " -> " + Math.round(on) + " ns)");
        }
        Atalhos.varredura = false;
        Atalhos.diluicao = false;
        Atalhos.zerarParaTeste();
        Recursos.limparParaTeste();
    }

    /** Um class loader com a transformacao feita com os GANCHOS ERRADOS (modo 1 a 4 de GanchoErrado). {@code aplicou[0]} diz se a classe mudou. */
    static CarregadorMods novoErrado(final Amb amb, final int modo, final boolean[] aplicou) throws Exception {
        final Correcoes errada = Correcoes.novoParaTeste();
        errada.adicionar(Correcao.nova("arsmagica", "gancho errado " + modo, Trava.de(AM, ArsMagica.SHA_EVENTOS), true, new Correcao.Transformacao() {
            public void aplicar(ClassNode cn, ResolvedorDeGanchos r) throws PadraoNaoEncontrado {
                String errado = "hoshiestar/teste/arsmagica/mundo/GanchoErrado";
                Asm.envolverChamada(cn, JOGADOR, DESC_JOGADOR, Chamada.de("net/minecraftforge/common/DimensionManager", "getWorlds", "()[Lnet/minecraft/world/WorldServer;"), 0, 1,
                        Gancho.de(errado, "varrerMundos"), Gancho.de(errado, "semMundos"), r);
                Asm.envolverChamada(cn, VIVO, DESC_VIVO, Chamada.de("am2/spell/SkillManager", "getSkill", "(Ljava/lang/String;)Lam2/api/spell/component/interfaces/ISkillTreeEntry;"), 0, 1,
                        Gancho.de(errado, "consultarSkill"), Gancho.de(errado, "semSkill"), r);
                Asm.envolverChamada(cn, VIVO, DESC_VIVO, Chamada.de("am2/spell/SkillTreeManager", "isSkillDisabled", "(Lam2/api/spell/component/interfaces/ISkillTreeEntry;)Z"), 0, 1,
                        Gancho.de(errado, "consultarDesativada"), Gancho.de(errado, "semDesativada"), r);
            }
        }));
        errada.fechar();
        final ResolvedorDeGanchos soErrado = new ResolvedorDeGanchos(new URLClassLoader(new URL[] {amb.classesTeste.toURI().toURL()}, null));
        URL[] urls = {amb.classesTeste.toURI().toURL(), amb.stubs.toURI().toURL(), amb.servidorSrg.toURI().toURL(), amb.am2.toURI().toURL()};
        final CarregadorMods[] ref = new CarregadorMods[1];
        CarregadorMods cl = new CarregadorMods(urls, TesteArsMagica.class.getClassLoader(), new CarregadorMods.Transformador() {
            public byte[] transformar(String nome, byte[] bytes) {
                if (!nome.equals(AM)) {
                    return bytes;
                }
                byte[] r = errada.aplicar(nome, bytes, soErrado, ref[0]);
                aplicou[0] = r != bytes;
                return reduzir(r);
            }
        });
        ref[0] = cl;
        Class<?> ge = Class.forName("hoshiestar.teste.arsmagica.mundo.GanchoErrado", true, cl);
        ge.getField("modo").setInt(null, modo);
        Class.forName(AM, true, cl);
        return cl;
    }

    @SuppressWarnings("unchecked")
    static Map<String, ?>[] casos(ClassLoader cl, boolean ligados, boolean transformado) throws Exception {
        Class<?> k = Class.forName("hoshiestar.teste.arsmagica.mundo.Casos", true, cl);
        Class<?> am = Class.forName(AM, true, cl);
        Atalhos.zerarParaTeste();
        Object c = k.getMethod("executar", boolean.class, Class.class).invoke(null, ligados, transformado ? am : null);
        Map<String, String> obs = (Map<String, String>) k.getField("observaveis").get(c);
        Map<String, Long> inter = (Map<String, Long>) k.getField("internos").get(c);
        return new Map[] {obs, inter};
    }

    @SuppressWarnings("unchecked")
    static void conferirCasos(Verifica v, Map<String, ?>[] o, Map<String, ?>[] l, Map<String, ?>[] d) {
        Map<String, String> oo = (Map<String, String>) o[0];
        Map<String, String> lo = (Map<String, String>) l[0];
        Map<String, String> dobs = (Map<String, String>) d[0];
        Map<String, Long> oi = (Map<String, Long>) o[1];
        Map<String, Long> li = (Map<String, Long>) l[1];
        Map<String, Long> di = (Map<String, Long>) d[1];
        Set<String> chaves = new TreeSet<String>(oo.keySet());
        int iguais = 0;
        int total = 0;
        for (String k : chaves) {
            total++;
            if (oo.get(k).equals(lo.get(k)) && oo.get(k).equals(dobs.get(k))) {
                iguais++;
            } else {
                System.out.println("    DIFERENTE " + k + ": original=" + oo.get(k) + " ligado=" + lo.get(k) + " desligado=" + dobs.get(k));
            }
        }
        v.sim(total > 0 && iguais == total, "casos: os " + total + " observaveis (atualizacoes extras, cancelamentos, mapas, excecoes engolidas) sao IGUAIS no original, no ligado e no desligado");
        for (String k : chaves) {
            System.out.println("    caso " + k + " = " + oo.get(k));
        }
        v.sim(lo.get("pontes/combinacoes") != null && lo.get("pontes/combinacoes").endsWith("erros=0")
                && dobs.get("pontes/combinacoes") != null && dobs.get("pontes/combinacoes").endsWith("erros=0"),
                "pontes do F2 com o interruptor trocado ENTRE getSkill e isSkillDisabled, todas as combinacoes: igual a conta do original (" + lo.get("pontes/combinacoes") + ")");
        v.igual("0", String.valueOf(lo.get("estresse/divergencias")), "estresse: outra thread trocando os interruptores sem parar durante 240 mil chamadas: 0 divergencias");
        // relacoes entre o que o AM2 deixou de fazer e o que o gancho pulou, caso a caso
        int relOk = 0;
        int relTotal = 0;
        for (String k : li.keySet()) {
            if (!k.endsWith("/getWorlds")) {
                continue;
            }
            String caso = k.substring(0, k.length() - "/getWorlds".length());
            if (caso.equals("estresse") || caso.equals("troca")) {
                continue;
            }
            relTotal++;
            long ow = oi.get(k), lw = li.get(k), dw = di.get(k);
            long osd = oi.get(caso + "/isSkillDisabled"), lsd = li.get(caso + "/isSkillDisabled"), dsd = di.get(caso + "/isSkillDisabled");
            boolean ok = ow - lw == li.get(caso + "/varrerPulou") && osd - lsd == li.get(caso + "/diluirPulou") && dw == ow && dsd == osd
                    && di.get(caso + "/varrerPulou") == 0 && di.get(caso + "/diluirPulou") == 0;
            if (ok) {
                relOk++;
            } else {
                System.out.println("    relacao falhou em " + caso + ": getWorlds O/L/D=" + ow + "/" + lw + "/" + dw + " pulou " + li.get(caso + "/varrerPulou")
                        + "; isSkillDisabled O/L/D=" + osd + "/" + lsd + "/" + dsd + " pulou " + li.get(caso + "/diluirPulou"));
            }
        }
        v.sim(relTotal >= 6 && relOk == relTotal, "casos: em cada caso, o que o AM2 deixou de pedir (getWorlds, isSkillDisabled) = o que o gancho pulou; desligado = original (" + relOk + "/" + relTotal + ")");
        // expectativas especificas
        v.sim(li.get("vazio/getWorlds") == 0 && li.get("vazio/varrerPulou") == 5 && li.get("vazio/isSkillDisabled") == 0 && li.get("vazio/diluirPulou") == 3
                && oi.get("vazio/getWorlds") == 5 && oi.get("vazio/isSkillDisabled") == 3,
                "caso mapa vazio: ligado nao pede a lista de mundos (0 de 5) nem consulta o DiluteTime (0 de 3); o original pede 5 e 3");
        v.sim(li.get("uma/varrerPulou") == 0 && li.get("uma/diluirPulou") == 0 && li.get("uma/getWorlds") == 3 && oi.get("uma/getWorlds") == 3,
                "caso 1 entrada: com entrada nos dois mapas nada e pulado (o original roda inteiro)");
        v.sim(li.get("entre/varrerPulou") == 1 && li.get("entre/diluirPulou") == 1 && li.get("entre/getWorlds") == 2,
                "caso entrada removida entre chamadas: so a chamada com o mapa ja vazio e pulada (varredura 1 de 3, DiluteTime 1)");
        v.sim(li.get("meio/varrerPulou") == 1 && li.get("meio/getWorlds") == 2,
                "caso entrada removida no meio da varredura: a varredura em curso roda inteira; so a chamada seguinte (mapa ja vazio) e pulada");
        v.sim(li.get("troca/varrerPulou") == 3 && li.get("troca/diluirPulou") == 3 && li.get("troca/getWorlds") == 4,
                "caso interruptor trocado em execucao: pula so enquanto ligado e com o mapa vazio (F1 3 de 7, F2 3 de 7: " + li.get("troca/varrerPulou") + "/" + li.get("troca/diluirPulou") + ")");
        v.sim(li.get("outraThread/varrerPulou") == 0 && li.get("outraThread/diluirPulou") == 0 && li.get("outraThread/contadorOutraThread") == 3 && li.get("outraThread/getWorlds") == 1,
                "caso outra thread: nao pula nada (3 chamadas contadas como 'outra thread')");
        v.sim(li.get("erro/erros") == 2 && li.get("erro/varreduraDesligada") == 1 && li.get("erro/diluicaoDesligada") == 1 && di.get("erro/erros") == 0 && di.get("erro/varreduraDesligada") == 0,
                "caso erro nosso (mapa lanca excecao): 2 erros, os dois atalhos se desligam e o AM2 segue o caminho original; desligado: 0 erros");
        v.sim(oi.get("vazio/varrerPulou") == 0 && oi.get("erro/erros") == 0, "o original (sem ganchos) nao toca nos contadores");
    }


    // ------------------------------------------------------------------ reentrancia

    @SuppressWarnings("unchecked")
    static List<String> reent(ClassLoader cl, int modo, long semente) throws Exception {
        Class<?> k = Class.forName("hoshiestar.teste.arsmagica.mundo.Reentrancia", true, cl);
        Class.forName(AM, true, cl);
        return (List<String>) k.getMethod("executar", int.class, long.class).invoke(null, modo, semente);
    }

    /**
     * Caso adversario da revisao: uma entidade acelerada, dentro do laco do F1, tira a propria entrada, troca os interruptores, chama o
     * metodo do jogador 2 e o onEntityLiving aninhados, e devolve a entrada. Original = ligado = desligado = trocado.
     */
    static void reentrancia(Verifica v, Amb amb, Correcoes corr) throws Exception {
        for (long semente = 1; semente <= 3; semente++) {
            ThreadServidor.definir(Thread.currentThread());
            List<String> o = reent(variante(amb, null, null), 0, semente);
            long[] antes = Atalhos.contagem.clone();
            List<String> l = reent(variante(amb, corr, null), 1, semente);
            long pulouF1 = Atalhos.contagem[Atalhos.VARRER_PULOU] - antes[Atalhos.VARRER_PULOU];
            long pulouF2 = Atalhos.contagem[Atalhos.DILUIR_PULOU] - antes[Atalhos.DILUIR_PULOU];
            List<String> d = reent(variante(amb, corr, null), 0, semente);
            List<String> t = reent(variante(amb, corr, null), 2, semente);
            o.remove(o.size() - 1);
            l.remove(l.size() - 1);
            d.remove(d.size() - 1);
            t.remove(t.size() - 1);
            boolean igual = o.equals(l) && o.equals(d) && o.equals(t);
            boolean aninhado = false;
            for (String x : o) {
                aninhado |= x.startsWith("aninhado-vivo-e1=");
            }
            v.sim(igual && aninhado && pulouF1 > 0 && pulouF2 > 0,
                    "reentrancia (semente " + semente + "): chamadas aninhadas dentro do laco do F1, entrada tirada e devolvida, interruptores trocados: original = ligado = desligado = trocado ("
                            + o.size() + " observaveis; puladas F1 " + pulouF1 + ", F2 " + pulouF2 + ")");
            if (!igual) {
                for (int i = 0; i < Math.min(o.size(), l.size()); i++) {
                    if (!o.get(i).equals(l.get(i)) || !o.get(i).equals(t.get(i))) {
                        System.out.println("    primeira diferenca: o=" + o.get(i) + " l=" + l.get(i) + " t=" + t.get(i));
                        break;
                    }
                }
            }
        }
        Atalhos.varredura = false;
        Atalhos.diluicao = false;
        Atalhos.zerarParaTeste();
    }

    // ------------------------------------------------------------------ ganchos que nao ligam

    /**
     * Um class loader do harness em que o GanchoAM2 e MUTILADO: sem o metodo {@code remover} (NoSuchMethodError) ou com o descritor de
     * {@code remover} trocado ({@code mudarRetorno}: o retorno vira Object / Object[]), e a classe do AM2 transformada de dois jeitos:
     * {@code protegido} (o formato que o servidor usa) ou com o formato 3 comum (controle: sem protecao o erro sai).
     */
    static CarregadorMods varianteComGanchoMutilado(final Amb amb, final String remover, final boolean mudarRetorno, final boolean protegido) throws Exception {
        final Correcoes c = protegido ? registro(true, true, amb.tmp) : correcoesSemProtecao();
        URL[] urls = {amb.classesTeste.toURI().toURL(), amb.stubs.toURI().toURL(), amb.servidorSrg.toURI().toURL(), amb.am2.toURI().toURL()};
        final CarregadorMods[] ref = new CarregadorMods[1];
        CarregadorMods cl = new CarregadorMods(urls, TesteArsMagica.class.getClassLoader(), new CarregadorMods.Transformador() {
            public byte[] transformar(String nome, byte[] bytes) {
                if (nome.equals(AM)) {
                    return reduzir(c.aplicar(nome, bytes, amb.ganchos, ref[0]));
                }
                if (nome.equals("hoshiestar.arsmagica.atalho.GanchoAM2") && remover != null) {
                    ClassNode cn = Asm.ler(bytes);
                    for (java.util.Iterator<MethodNode> it = cn.methods.iterator(); it.hasNext();) {
                        MethodNode m = it.next();
                        if (m.name.equals(remover)) {
                            if (mudarRetorno) {
                                int fecha = m.desc.indexOf(')');
                                m.desc = m.desc.substring(0, fecha + 1) + (m.desc.charAt(fecha + 1) == '[' ? "[Ljava/lang/Object;" : "Ljava/lang/Object;");
                            } else {
                                it.remove();
                            }
                        }
                    }
                    return Asm.escrever(cn);
                }
                return bytes;
            }
        });
        ref[0] = cl;
        Class.forName(AM, true, cl);
        return cl;
    }

    /** O mesmo registro de correcoes, mas com o formato 3 COMUM (sem protecao): so o controle do teste. */
    static Correcoes correcoesSemProtecao() {
        Correcoes c = Correcoes.novoParaTeste();
        c.adicionar(Correcao.nova("arsmagica", "controle sem protecao", Trava.de(AM, ArsMagica.SHA_EVENTOS), true, new Correcao.Transformacao() {
            public void aplicar(ClassNode cn, ResolvedorDeGanchos r) throws PadraoNaoEncontrado {
                Asm.envolverChamada(cn, JOGADOR, DESC_JOGADOR, Chamada.de("net/minecraftforge/common/DimensionManager", "getWorlds", "()[Lnet/minecraft/world/WorldServer;"), 0, 1,
                        Gancho.de(GANCHO, "varrerMundos"), Gancho.de(GANCHO, "semMundos"), r);
                Asm.envolverChamada(cn, VIVO, DESC_VIVO, Chamada.de("am2/spell/SkillManager", "getSkill", "(Ljava/lang/String;)Lam2/api/spell/component/interfaces/ISkillTreeEntry;"), 0, 1,
                        Gancho.de(GANCHO, "consultarSkill"), Gancho.de(GANCHO, "semSkill"), r);
                Asm.envolverChamada(cn, VIVO, DESC_VIVO, Chamada.de("am2/spell/SkillTreeManager", "isSkillDisabled", "(Lam2/api/spell/component/interfaces/ISkillTreeEntry;)Z"), 0, 1,
                        Gancho.de(GANCHO, "consultarDesativada"), Gancho.de(GANCHO, "semDesativada"), r);
            }
        }));
        c.fechar();
        return c;
    }

    static Throwable raiz(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * Revisao I1: um gancho que nao liga em tempo de execucao NAO pode derrubar o AM2 (nem o servidor). Para cada um dos seis ganchos, a
     * classe do gancho e mutilada (sem o metodo; ou com o retorno trocado, que e o caso do tipo do Minecraft traduzido diferente):
     * (1) a ponte protegida segue pelo original: mesmo hash do estado do original, e o erro e contado; (2) a auto-checagem do boot acusa o
     * gancho certo; (3) controle: com o formato 3 comum o NoSuchMethodError sai do metodo do AM2. E a classe intacta passa na auto-checagem.
     */
    static void ganchosQuebrados(Verifica v, Amb amb, Correcoes corr, int iter) throws Exception {
        System.out.println("-- ganchos que nao ligam em tempo de execucao");
        final Method verificar = ArsMagica.class.getDeclaredMethod("verificarGanchos", ClassLoader.class);
        verificar.setAccessible(true);
        ThreadServidor.definir(Thread.currentThread());
        CarregadorMods intacto = variante(amb, corr, null);
        v.sim(verificar.invoke(null, intacto) == null, "auto-checagem: com o GanchoAM2 do server.jar construido tudo confere (ganchos, tipos de tempo de execucao, retornos dos 'sem...')");
        // sem a classe do gancho no class loader
        Object semAM2 = verificar.invoke(null, new URLClassLoader(new URL[0], null));
        v.sim(semAM2 != null, "auto-checagem: sem a classe do gancho no class loader ela acusa (" + semAM2 + ")");

        Map<String, Object> o = rodar(variante(amb, null, null), 1, iter, 0, 400);
        String[][] ganchos = {{"varrerMundos", "n"}, {"semMundos", "n"}, {"consultarSkill", "n"}, {"semSkill", "n"}, {"consultarDesativada", "n"}, {"semDesativada", "n"},
            {"semMundos", "r"}, {"semSkill", "r"}};
        for (String[] g : ganchos) {
            boolean retorno = g[1].equals("r");
            String rot = g[0] + (retorno ? " (retorno de outro tipo)" : " (metodo ausente)");
            // (2) auto-checagem acusa
            CarregadorMods ruim = varianteComGanchoMutilado(amb, g[0], retorno, true);
            Object motivo = verificar.invoke(null, ruim);
            v.sim(motivo != null && String.valueOf(motivo).contains(g[0]), "auto-checagem acusa o gancho " + rot + ": " + motivo);
            // (1) a ponte protegida segue pelo original, em varios modos
            for (int modo : new int[] {1, 2, 3, 4}) {
                Map<String, Object> m = rodar(varianteComGanchoMutilado(amb, g[0], retorno, true), 1, iter, modo, 400);
                long[] d = (long[]) m.get("delta");
                boolean igual = String.valueOf(o.get("trace")).equals(m.get("trace")) && num(o, "atualizacoesExtras") == num(m, "atualizacoesExtras")
                        && num(o, "cancelados") == num(m, "cancelados") && num(o, "errosEngolidos") == num(m, "errosEngolidos") && num(o, "alcancou72") == num(m, "alcancou72");
                // o gancho "deve" ausente falha em toda chamada, com qualquer interruptor; o "sem" so quando se tenta pular (o atalho dele ligado)
                boolean f1 = g[0].endsWith("Mundos");
                boolean esperaErro = !g[0].startsWith("sem") || (f1 ? modo != 4 : modo != 3);
                v.sim(igual && (!esperaErro || d[Atalhos.ERROS] > 0),
                        "gancho " + rot + ", modo " + modo + ": a ponte protegida segue pelo original (hash do estado igual ao original; erros contados " + d[Atalhos.ERROS] + ")"
                                + (igual ? "" : "\n  original=" + o.get("linha") + "\n  quebrado=" + m.get("linha")));
                Atalhos.zerarParaTeste();
            }
            // (3) controle: sem a protecao o erro sai do metodo do AM2
            Throwable saiu = null;
            try {
                rodar(varianteComGanchoMutilado(amb, g[0], retorno, false), 1, iter, 1, 400);
            } catch (Throwable t) {
                saiu = raiz(t);
            }
            v.sim(saiu instanceof NoSuchMethodError, "controle (formato 3 sem protecao), gancho " + rot + ": o erro sai do metodo do AM2: " + saiu);
            Atalhos.zerarParaTeste();
        }
        Atalhos.varredura = false;
        Atalhos.diluicao = false;
        Atalhos.zerarParaTeste();
    }

    // ------------------------------------------------------------------ parte 4: ordem dos transformadores

    static void ordemDosTransformadores(Verifica v, Amb amb) throws Exception {
        System.out.println("-- ordem dos transformadores (AM2 e AM2PlayGround)");
        for (File jar : new File[] {amb.am2, amb.am2pg}) {
            ZipFile z = new ZipFile(jar);
            Set<String> citam = new TreeSet<String>();
            List<String> transformadores = new ArrayList<String>();
            int classes = 0;
            for (java.util.Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry ze = e.nextElement();
                if (!ze.getName().endsWith(".class")) {
                    continue;
                }
                classes++;
                byte[] b = RemapeadorSrg.ler(z.getInputStream(ze));
                boolean cita = indexOf(b, "AMEventHandler".getBytes("UTF-8")) >= 0;
                ClassNode cn = new ClassNode();
                new ClassReader(b).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                boolean transf = cn.interfaces.contains("net/minecraft/launchwrapper/IClassTransformer") || cn.name.endsWith("Transformer")
                        || (cn.superName != null && (cn.superName.endsWith("ClassTransformerBase") || cn.superName.endsWith("CustomLoadingPlugin")
                        || cn.superName.contains("TransformerBase")))
                        || cn.name.contains("/preloader/") || cn.name.contains("/asm/common/") || cn.name.contains("/asm/core/");
                if (transf) {
                    transformadores.add(cn.name);
                }
                if (cita && !cn.name.startsWith("am2/AMEventHandler")) {
                    citam.add(cn.name + (transf ? " [TRANSFORMADOR]" : ""));
                }
            }
            boolean algum = false;
            for (String s : citam) {
                algum |= s.endsWith("[TRANSFORMADOR]");
            }
            // access transformers (META-INF/*_at.cfg): nenhum cita as classes alteradas pelo recurso
            List<String> atsCitam = new ArrayList<String>();
            int ats = 0;
            for (java.util.Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry ze = e.nextElement();
                if (ze.getName().startsWith("META-INF/") && ze.getName().endsWith("_at.cfg")) {
                    ats++;
                    String txt = new String(RemapeadorSrg.ler(z.getInputStream(ze)), "UTF-8");
                    for (String linha : txt.split("\\r?\\n")) {
                        String l = linha.trim();
                        if (l.startsWith("#")) {
                            continue;
                        }
                        for (String alvo : new String[] {"am2.AMEventHandler", "am2.spell.SkillManager", "am2.spell.SkillTreeManager"}) {
                            if (l.contains(alvo)) {
                                atsCitam.add(ze.getName() + ": " + l);
                            }
                        }
                    }
                }
            }
            System.out.println("    " + jar.getName() + ": " + ats + " arquivo(s) de access transformer em META-INF; linhas que citam AMEventHandler/SkillManager/SkillTreeManager: " + atsCitam);
            v.sim(ats >= 1 && atsCitam.isEmpty(), jar.getName() + ": nenhum access transformer (META-INF/*_at.cfg, " + ats + " arquivo(s)) cita AMEventHandler, SkillManager nem SkillTreeManager");
            System.out.println("    " + jar.getName() + ": " + classes + " classes, " + transformadores.size() + " de transformador/coremod; citam AMEventHandler: " + citam);
            v.sim(!algum, jar.getName() + ": nenhuma classe de transformador ou coremod cita AMEventHandler (so " + citam.size() + " classes comuns citam)");
            z.close();
        }
    }

    static int indexOf(byte[] a, byte[] p) {
        outer:
        for (int i = 0; i <= a.length - p.length; i++) {
            for (int j = 0; j < p.length; j++) {
                if (a[i + j] != p[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ auxiliares

    static byte[] comMetodoAMais(byte[] b) {
        ClassNode cn = Asm.ler(b);
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "hoshiestarTesteVersao", "()V", null, null);
        m.instructions.add(new InsnNode(Opcodes.RETURN));
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
            if (x.getOpcode() == opcode && x instanceof MethodInsnNode && ((MethodInsnNode) x).owner.equals(dono) && ((MethodInsnNode) x).name.equals(nome)) {
                n++;
            }
        }
        return n;
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
