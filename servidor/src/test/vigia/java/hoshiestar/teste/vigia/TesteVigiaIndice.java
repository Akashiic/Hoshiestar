package hoshiestar.teste.vigia;

import hoshiestar.teste.Verifica;
import hoshiestar.vigia.IndiceDeJars;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/** Indice "pacote -> jar" com jars de teste. Uso: TesteVigiaIndice <pasta de trabalho>. */
public final class TesteVigiaIndice {

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteVigiaIndice");
        File base = new File(args[0]);
        File mods = new File(base, "mods");
        File plugins = new File(base, "plugins");
        Apoio.jar(mods, "A-1.0.jar", "com/a/X.class", "com/a/b/Y.class", "META-INF/versions/9/com/zzz/Q.class");
        Apoio.jar(mods, "B-2.0.jar", "com/a/b/c/Z.class", "com/a/b/c/Z$1.class");
        Apoio.jar(new File(mods, "1.7.10"), "Sub-3.jar", "org/sub/K.class");                 // subpasta (profundidade 1)
        Apoio.jar(new File(mods, "x/y/z"), "Fundo-4.jar", "org/fundo/K.class");               // profundidade 3: fora
        Apoio.jar(mods, "Lib1.jar", "org/lib/Comum.class");
        Apoio.jar(mods, "Lib2.jar", "org/lib/Comum.class");                                   // pacote repetido
        Apoio.jar(plugins, "Plugin.jar", "ch/njol/skript/Skript.class");
        Apoio.jar(mods, "SemPacote.jar", "Raiz.class");
        new FileOutputStream(new File(mods, "quebrado.jar")).write(new byte[] {1, 2, 3});   // jar ilegivel
        new File(mods, "leiame.txt").createNewFile();

        IndiceDeJars ind = IndiceDeJars.montar(Arrays.asList(mods, plugins, new File(base, "nao-existe")), 2);
        System.out.println("  indice: " + ind.jars() + " jars, " + ind.pacotes() + " pacotes, " + ind.erros() + " erro(s), " + ind.msParaMontar() + " ms");
        v.igual("B-2.0.jar", ind.dono("com.a.b.c.Z"), "classe do pacote exato (B)");
        v.igual("B-2.0.jar", ind.dono("com.a.b.c.Z$1"), "classe interna");
        v.igual("B-2.0.jar", ind.dono("com.a.b.c.d.Gerada"), "prefixo mais longo: subpacote de com.a.b.c -> B, nao A");
        v.igual("A-1.0.jar", ind.dono("com.a.b.Q"), "com.a.b -> A");
        v.igual("A-1.0.jar", ind.dono("com.a.outro.W"), "com.a.outro cai em com.a -> A");
        v.igual(null, ind.dono("com.zzz.Q"), "META-INF/ nao entra no indice");
        v.igual(null, ind.dono("net.desconhecido.C"), "pacote fora de todos os jars -> null");
        v.igual(null, ind.dono("com.Sozinho"), "prefixo 'com' nao existe sozinho -> null");
        v.igual("Sub-3.jar", ind.dono("org.sub.K"), "jar em subpasta (mods/1.7.10)");
        v.igual(null, ind.dono("org.fundo.K"), "jar fundo demais (profundidade 3) fica fora");
        v.igual("Lib1.jar (+1)", ind.dono("org.lib.Comum"), "pacote em dois jars: o primeiro (ordem do nome) e '(+1)'");
        v.igual("Plugin.jar", ind.dono("ch.njol.skript.Skript"), "jar de plugins/");
        v.igual(null, ind.dono("Raiz"), "classe sem pacote: nunca atribuida (as geradas pelo FML, ASMEventHandler_*, ficam sem pacote)");
        v.igual(1, ind.erros(), "jar ilegivel conta como erro e e pulado");
        v.igual(7, ind.jars(), "7 jars lidos (fora: o fundo demais e o quebrado)");
        v.igual(null, ind.dono(null), "classe nula");

        // jars do servidor (server.jar + libraries/): pacotes deles sao neutros mesmo se um mod/plugin os embute (revisao M6)
        Apoio.jar(plugins, "DiscordSRV.jar", "org/slf4j/Logger.class", "org/slf4j/impl/StaticLoggerBinder.class", "github/scarsz/X.class");
        File servidorJar = Apoio.jar(new File(base, "raiz"), "server.jar", "net/minecraft/server/MinecraftServer.class", "org/slf4j/Logger.class");
        Apoio.jar(new File(base, "libraries/org/apache/a/b/1.0"), "lib-1.0.jar", "org/lib/Comum.class");
        IndiceDeJars ind2 = IndiceDeJars.montar(Arrays.asList(mods, plugins), 2, Arrays.asList(servidorJar, new File(base, "libraries")), 12);
        System.out.println("  indice com servidor: " + ind2.jars() + " jars de mods/plugins, " + ind2.jarsDoServidor() + " do servidor");
        v.igual(null, ind2.dono("org.slf4j.Logger"), "servidor: org.slf4j (no server.jar) nao e do plugin que o embute");
        v.igual("DiscordSRV.jar", ind2.dono("org.slf4j.impl.StaticLoggerBinder"), "servidor: subpacote que so o plugin tem continua do plugin (prefixo mais longo)");
        v.igual("DiscordSRV.jar", ind2.dono("github.scarsz.X"), "servidor: pacote proprio do plugin");
        v.igual(null, ind2.dono("org.lib.Comum"), "servidor: pacote de libraries/ (fundo, 5 niveis) neutro, mesmo em Lib1/Lib2");
        v.igual("B-2.0.jar", ind2.dono("com.a.b.c.Z"), "servidor: o resto igual");
        v.igual(2, ind2.jarsDoServidor(), "servidor: 2 jars do servidor lidos");
        v.fim();
    }
}
