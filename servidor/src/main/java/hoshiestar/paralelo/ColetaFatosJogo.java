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

import hoshiestar.api.Log;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A coleta dos fatos do jogo para as recusas (decisao 39d), no servidor pronto ("Done", thread do servidor). Le o que a
 * interpretacao precisa ({@link ColetaFatos.Bruto}) e deixa {@code null} tudo o que nao conseguir ler: a interpretacao
 * transforma {@code null} em recusa (nunca fail-open). Escreve no log so nomes de classe e "ligado/desligado"; nunca
 * valor de config.
 *
 * <p>Propriedade de teste (so com {@code -Dhoshiestar.paralelo.teste=true}): {@code -Dhoshiestar.paralelo.teste.esconder=
 * dimensoes,rand,...} trata esses fatos como nao lidos, para provar a recusa em boot de verdade.
 */
public final class ColetaFatosJogo {

    private ColetaFatosJogo() {
    }

    private static boolean feita;

    /** Chamado por {@code Servidor.pronto()}. Com o recurso desligado, nada. Uma vez so. */
    public static void coletarSeLigado() {
        if (!Paralelo.LIGADO || feita) {
            return;
        }
        feita = true;
        Parametros p = Paralelo.parametros();
        ColetaFatos.Bruto b = coletar(p.dimensao);
        Set<String> esconder = ColetaFatos.esconderDaPropriedade(Congelar.TESTE, System.getProperty("hoshiestar.paralelo.teste.esconder"));
        ColetaFatos.Resultado res = ColetaFatos.interpretar(b, Paralelo.fatosDoConfig(), p.dimensao, esconder, Travas.TRANSFORMADORES_COM_VEREDITO);
        List<String> motivos = Recusas.avaliar(true, p, res.fatos);
        Paralelo.configurarDoJogo(motivos, res.classesTiradas);
        // log: so nomes e ligado/desligado
        Log.info("paralelo: fatos do jogo coletados" + (esconder.isEmpty() ? "" : " (TESTE: tratados como nao lidos: " + esconder + ")")
                + ": dimensoes " + (b.dimensoes == null ? "nao lidas" : b.dimensoes.size() + " registradas")
                + "; world.rand " + (b.classeRand == null ? "nao lido" : b.classeRand.isEmpty() ? "(mundo da dimensao nao carregado)" : b.classeRand)
                + "; hodgepodge " + presenca(b.hodgepodgePresente)
                + (Boolean.TRUE.equals(b.hodgepodgePresente) ? " (addSimulationDistance_WIP " + liga(b.distanciaDeSimulacao)
                        + ", preventChunkLoadingFromBlockUpdates " + liga(b.preventChunkLoading) + ")" : "")
                + "; DragonAPI " + presenca(b.dragonApiPresente)
                + (Boolean.TRUE.equals(b.dragonApiPresente) ? " (observadores " + b.observadores + ", watchers " + b.watchers + ")" : "")
                + "; firewall " + presenca(b.firewallPresente)
                + (Boolean.TRUE.equals(b.firewallPresente) ? " (allowTileEntityTick " + (b.firewallTemPortao == null ? "nao conferido"
                        : b.firewallTemPortao ? "presente" : "AUSENTE") + ")" : "")
                + "; mixins nas classes do caminho " + (b.mixins == null ? "nao lidos" : b.mixins.size())
                + "; transformadores " + (b.transformadores == null ? "nao lidos" : b.transformadores.size()));
        if (b.mixins != null) {
            List<String> fm = ColetaFatos.fora(b.mixins, Travas.MIXINS_COM_VEREDITO);
            if (!fm.isEmpty()) {
                Log.info("paralelo: mixins sem veredito nas classes do caminho (" + fm.size() + "): " + fm);
            }
        }
        if (b.transformadores != null) {
            List<String> fora = Travas.TRANSFORMADORES_COM_VEREDITO == null ? b.transformadores
                    : ColetaFatos.fora(b.transformadores, Travas.TRANSFORMADORES_COM_VEREDITO);
            if (!fora.isEmpty()) {
                Log.info("paralelo: transformadores sem veredito embutido (" + fora.size() + "): " + fora);
            }
        }
        if (b.shas != null) {
            for (Travas.Trava t : Travas.JARS) {
                String s = b.shas.get(t.rotulo);
                Log.info("paralelo: trava " + t.rotulo + ": " + (s == null ? "SHA-256 nao lido" : s.isEmpty() ? "jar ausente"
                        : s.equalsIgnoreCase(t.sha256) ? "SHA-256 confere" : "SHA-256 DIFERENTE do revisado (tira " + t.classes + ")"));
            }
        }
        for (Map.Entry<String, String> e : res.classesTiradas.entrySet()) {
            Log.aviso("paralelo: classe '" + e.getKey() + "' tirada da lista: " + e.getValue());
        }
        if (motivos.isEmpty()) {
            Log.info("paralelo: aceito no boot (modo " + p.modo + ", classes efetivas " + Paralelo.classesEfetivas() + ")");
        } else {
            for (String m : motivos) {
                Log.aviso("paralelo recusado no boot: " + m);
            }
        }
    }

    private static String presenca(Boolean b) {
        return b == null ? "nao lido" : b ? "presente" : "ausente";
    }

    private static String liga(Boolean b) {
        return b == null ? "nao lido" : b ? "ligado" : "desligado";
    }

    /** Le tudo o que der; o que falhar fica null. */
    static ColetaFatos.Bruto coletar(int dimensao) {
        ColetaFatos.Bruto b = new ColetaFatos.Bruto();
        ClassLoader cl = carregador();
        try {
            Integer[] ids = DimensionManager.getIDs();
            if (ids != null) {
                b.dimensoes = new HashSet<Integer>(Arrays.asList(ids));
            }
        } catch (Throwable t) {
            b.dimensoes = null;
        }
        try {
            WorldServer w = DimensionManager.getWorld(dimensao);
            b.classeRand = w == null ? "" : (w.rand == null ? "null" : w.rand.getClass().getName());
        } catch (Throwable t) {
            b.classeRand = null;
        }
        try {
            b.hodgepodgePresente = cl.getResource(Travas.RECURSO_HODGEPODGE) != null;
            if (b.hodgepodgePresente) {
                Class<?> c = Class.forName(Travas.CLASSE_HODGEPODGE, true, cl);
                b.distanciaDeSimulacao = lerBoolean(c, "addSimulationDistance_WIP");
                b.preventChunkLoading = lerBoolean(c, "preventChunkLoadingFromBlockUpdates");
            }
        } catch (Throwable t) {
            b.hodgepodgePresente = null;
        }
        try {
            b.dragonApiPresente = cl.getResource(Travas.RECURSO_DRAGONAPI) != null;
            if (b.dragonApiPresente) {
                b.observadores = GanchosParalelo.DragonApi.observadores();
                b.watchers = GanchosParalelo.DragonApi.watchers();
            }
        } catch (Throwable t) {
            b.dragonApiPresente = null;
        }
        try {
            b.firewallPresente = cl.getResource(Travas.RECURSO_FIREWALL) != null;
            if (b.firewallPresente) {
                Class<?> c = Class.forName(Travas.CLASSE_FIREWALL, false, cl);
                Class<?> te = Class.forName("net.minecraft.tileentity.TileEntity", false, cl);
                Class<?> wd = Class.forName("net.minecraft.world.World", false, cl);
                Method m = c.getMethod("allowTileEntityTick", te, wd);
                b.firewallTemPortao = Modifier.isStatic(m.getModifiers()) && m.getReturnType() == boolean.class;
            }
        } catch (NoSuchMethodException e) {
            b.firewallTemPortao = false;
        } catch (Throwable t) {
            b.firewallTemPortao = null;
        }
        b.mixins = mixins(cl);
        b.transformadores = transformadores();
        b.shas = shas(cl);
        return b;
    }

    private static ClassLoader carregador() {
        try {
            if (Launch.classLoader != null) {
                return Launch.classLoader;
            }
        } catch (Throwable t) {
            // cai no de baixo
        }
        return ColetaFatosJogo.class.getClassLoader();
    }

    private static Boolean lerBoolean(Class<?> c, String campo) {
        try {
            return c.getField(campo).getBoolean(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Mixins mesclados (anotacao MixinMerged, visivel em execucao) nas classes do caminho. null = nao deu para ler. */
    static List<String> mixins(ClassLoader cl) {
        try {
            Set<String> r = new LinkedHashSet<String>();
            for (String n : Travas.CLASSES_DO_CAMINHO) {
                Class<?> c = Class.forName(n, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    for (Annotation a : m.getDeclaredAnnotations()) {
                        if (a.annotationType().getName().equals("org.spongepowered.asm.mixin.transformer.meta.MixinMerged")) {
                            Object nome = a.annotationType().getMethod("mixin").invoke(a);
                            r.add(String.valueOf(nome));
                        }
                    }
                }
            }
            return new ArrayList<String>(r);
        } catch (Throwable t) {
            return null;
        }
    }

    static List<String> transformadores() {
        try {
            List<IClassTransformer> l = Launch.classLoader.getTransformers();
            Set<String> r = new LinkedHashSet<String>();
            for (IClassTransformer t : l) {
                r.add(t.getClass().getName());
            }
            return new ArrayList<String>(r);
        } catch (Throwable t) {
            return null;
        }
    }

    /** SHA-256 do jar que tem o recurso de cada trava ("" = recurso ausente). null = algum nao deu para ler. */
    static Map<String, String> shas(ClassLoader cl) {
        Map<String, String> r = new HashMap<String, String>();
        Map<String, String> porArquivo = new HashMap<String, String>();
        try {
            for (Travas.Trava t : Travas.JARS) {
                URL u = cl.getResource(t.recurso);
                if (u == null) {
                    r.put(t.rotulo, "");
                    continue;
                }
                File f = arquivoDoJar(u);
                if (f == null || !f.isFile()) {
                    Log.aviso("paralelo: SHA-256 da trava " + t.rotulo + " nao lido: o recurso nao esta num jar conhecido (protocolo '"
                            + u.getProtocol() + "')");
                    return null; // achou o recurso fora de um jar (pasta de classes?): nao sei o SHA
                }
                String k = f.getCanonicalPath();
                String s = porArquivo.get(k);
                if (s == null) {
                    s = sha256(f);
                    porArquivo.put(k, s);
                }
                r.put(t.rotulo, s);
            }
            return r;
        } catch (Throwable t) {
            Log.aviso("paralelo: SHA-256 dos jars travados nao lido: " + t);
            return null;
        }
    }

    static File arquivoDoJar(URL u) throws Exception {
        try {
            java.net.URLConnection c = u.openConnection();
            if (c instanceof java.net.JarURLConnection) {
                URL j = ((java.net.JarURLConnection) c).getJarFileURL();
                if ("file".equals(j.getProtocol())) {
                    return new File(j.toURI());
                }
            }
        } catch (Throwable t) {
            // cai na leitura do texto
        }
        String s = u.toString();
        if (!s.startsWith("jar:")) {
            return null;
        }
        int bang = s.indexOf("!/");
        if (bang < 0) {
            return null;
        }
        String f = s.substring(4, bang);
        if (!f.startsWith("file:")) {
            return null;
        }
        return new File(new java.net.URI(f)); // nao URLDecoder: ele troca '+' por espaco (+unimixins-all..., etfuturum...+77e9...)
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }
}
