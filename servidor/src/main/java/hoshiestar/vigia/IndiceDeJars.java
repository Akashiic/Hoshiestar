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
package hoshiestar.vigia;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Indice "pacote Java -&gt; jar" dos jars de mods/ e plugins/, para dizer de qual mod e uma classe que
 * aparece na pilha. Montado pela PROPRIA thread do vigia (so no primeiro relatorio), lendo o
 * diretorio central de cada jar com {@link ZipFile}. Nao usa o class loader do jogo: nenhuma trava
 * dele, nenhuma classe carregada ou inicializada fora da hora (correcao do conferente).
 *
 * <p>Busca: pelo prefixo de pacote mais longo que existe no indice (ex.: a classe
 * {@code a.b.c.d.X}, com {@code a.b.c} num jar e {@code a.b} noutro, da o jar de {@code a.b.c}).
 * Assim tambem acha classes geradas em tempo de execucao num subpacote do mod. Pacote presente em
 * mais de um jar (biblioteca embutida em varios mods) devolve o primeiro, com "(+N)". Classe sem
 * pacote nunca tem dono.
 *
 * <p>Jars do proprio servidor (o server.jar e libraries/) tambem entram, marcados "do servidor": um
 * pacote que existe neles (ex.: org.slf4j, que um plugin tambem embute) nao e de mod nenhum e
 * {@link #dono} devolve null para ele, mesmo que um mod tenha o mesmo pacote (revisao M6: antes
 * org.slf4j saia como DiscordSRV). Prefixo mais longo continua valendo: se o mod tem um subpacote que
 * o servidor nao tem, o subpacote e do mod.
 *
 * <p>Nao e seguro entre threads: so a thread do vigia monta e consulta.
 */
public final class IndiceDeJars {

    /** Valor no mapa para pacote que existe num jar do servidor (comparado por identidade). */
    private static final Object SERVIDOR = new Object();

    /** valor: String (um jar), List&lt;String&gt; (varios jars com o mesmo pacote) ou {@link #SERVIDOR}. */
    private final Map<String, Object> mapa = new HashMap<String, Object>();
    private int jars;
    private int jarsDoServidor;
    private boolean lendoServidor;
    private int erros;
    private long msParaMontar;

    private IndiceDeJars() {
    }

    /**
     * Monta o indice a partir das pastas dadas (procura *.jar e *.zip ate {@code profundidade} niveis
     * de subpasta). Pasta inexistente e ignorada; jar ilegivel conta como erro e e pulado. Nunca lanca.
     */
    public static IndiceDeJars montar(List<File> pastas, int profundidade) {
        return montar(pastas, profundidade, null, 0);
    }

    /**
     * Como {@link #montar(List, int)}, mais os jars do servidor: {@code doServidor} pode ter jars
     * (server.jar) e pastas (libraries/, varrida ate {@code profundidadeServidor} niveis). Nunca lanca.
     */
    public static IndiceDeJars montar(List<File> pastas, int profundidade, List<File> doServidor, int profundidadeServidor) {
        long t0 = System.nanoTime();
        IndiceDeJars ind = new IndiceDeJars();
        for (File p : pastas) {
            ind.varrer(p, profundidade);
        }
        if (doServidor != null) {
            ind.lendoServidor = true;
            for (File p : doServidor) {
                try {
                    if (p.isFile()) {
                        ind.indexar(p);
                    } else {
                        ind.varrer(p, profundidadeServidor);
                    }
                } catch (Throwable t) {
                    ind.erros++;
                }
            }
            ind.lendoServidor = false;
        }
        ind.msParaMontar = (System.nanoTime() - t0) / 1000000L;
        return ind;
    }

    private void varrer(File pasta, int profundidade) {
        File[] lista;
        try {
            lista = pasta.listFiles();
        } catch (Throwable t) {
            erros++;
            return;
        }
        if (lista == null) {
            return;
        }
        Arrays.sort(lista); // ordem estavel: o "primeiro jar" de um pacote repetido nao muda de um boot para outro
        for (File f : lista) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (f.isDirectory()) {
                if (profundidade > 0) {
                    varrer(f, profundidade - 1);
                }
            } else if (n.endsWith(".jar") || n.endsWith(".zip")) {
                indexar(f);
            }
        }
    }

    private void indexar(File jar) {
        ZipFile z = null;
        try {
            z = new ZipFile(jar);
            String nome = jar.getName();
            if (lendoServidor) {
                jarsDoServidor++;
            } else {
                jars++;
            }
            String ultimo = null;
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String e = en.nextElement().getName();
                if (!e.endsWith(".class") || e.startsWith("META-INF/")) {
                    continue;
                }
                int barra = e.lastIndexOf('/');
                String pacote = barra < 0 ? "" : e.substring(0, barra).replace('/', '.');
                if (pacote.equals(ultimo)) {
                    continue; // entradas do mesmo pacote costumam vir juntas
                }
                ultimo = pacote;
                acrescentar(pacote, nome);
            }
        } catch (Throwable t) {
            erros++;
        } finally {
            if (z != null) {
                try {
                    z.close();
                } catch (Throwable ignorado) {
                    // so leitura
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void acrescentar(String pacote, String jar) {
        if (lendoServidor) {
            mapa.put(pacote, SERVIDOR); // o pacote do servidor vence o de qualquer mod
            return;
        }
        Object v = mapa.get(pacote);
        if (v == SERVIDOR) {
            return;
        }
        if (v == null) {
            mapa.put(pacote, jar);
        } else if (v instanceof String) {
            if (!v.equals(jar)) {
                List<String> l = new ArrayList<String>(2);
                l.add((String) v);
                l.add(jar);
                mapa.put(pacote, l);
            }
        } else {
            List<String> l = (List<String>) v;
            if (!l.contains(jar)) {
                l.add(jar);
            }
        }
    }

    /**
     * O jar da classe (nome binario, com '.' e talvez '$'), pelo prefixo de pacote mais longo, ou null se
     * nenhum prefixo esta no indice. Classe sem pacote: sempre null (as classes geradas pelo FML para
     * eventos, ASMEventHandler_*, ficam sem pacote e nao sao de jar nenhum).
     */
    @SuppressWarnings("unchecked")
    public String dono(String classe) {
        if (classe == null) {
            return null;
        }
        int ponto = classe.lastIndexOf('.');
        if (ponto <= 0) {
            return null;
        }
        String p = classe.substring(0, ponto);
        for (;;) {
            Object v = mapa.get(p);
            if (v == SERVIDOR) {
                return null; // pacote do proprio servidor ou de biblioteca dele: neutro
            }
            if (v instanceof String) {
                return (String) v;
            }
            if (v != null) {
                List<String> l = (List<String>) v;
                return l.get(0) + " (+" + (l.size() - 1) + ")";
            }
            int i = p.lastIndexOf('.');
            if (i <= 0) {
                return null;
            }
            p = p.substring(0, i);
        }
    }

    public int jars() {
        return jars;
    }

    public int jarsDoServidor() {
        return jarsDoServidor;
    }

    public int pacotes() {
        return mapa.size();
    }

    public int erros() {
        return erros;
    }

    public long msParaMontar() {
        return msParaMontar;
    }
}
