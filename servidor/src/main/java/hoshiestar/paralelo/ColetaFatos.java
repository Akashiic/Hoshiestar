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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A interpretacao dos fatos do jogo coletados no boot (decisao 39d): transforma o que o adaptador leu do servidor de
 * verdade ({@link Bruto}) nos fatos das recusas ({@link Recusas.Fatos}). Funcao pura, sem Minecraft.
 *
 * <p><b>Nunca fail-open.</b> Cada fato do {@link Bruto} tem um valor que quer dizer "nao consegui ler" ({@code null}). Fato
 * nao lido vira motivo de recusa ({@link Recusas.Fatos#naoColetados}), nunca "esta tudo certo". Um mod que nao esta no
 * servidor nao e fato faltando: o fato e "ausente" e a recusa dele nao se aplica (ex.: sem hodgepodge nao ha distancia de
 * simulacao). Mas "o mod esta, e nao consegui ler a chave dele" recusa.
 */
public final class ColetaFatos {

    private ColetaFatos() {
    }

    /** Nomes dos fatos (os mesmos da propriedade de teste {@code hoshiestar.paralelo.teste.esconder}). */
    public static final String DIMENSOES = "dimensoes", RAND = "rand", HODGEPODGE = "hodgepodge", DRAGONAPI = "dragonapi",
            FIREWALL = "firewall", VEREDITO = "veredito";
    public static final List<String> FATOS = Collections.unmodifiableList(Arrays.asList(DIMENSOES, RAND, HODGEPODGE,
            DRAGONAPI, FIREWALL, VEREDITO));

    /** O que o adaptador leu. {@code null} = nao consegui ler (vira recusa). */
    public static final class Bruto {
        /** Dimensoes carregadas (DimensionManager). */
        public Set<Integer> dimensoes;
        /** Classe do objeto {@code rand} do mundo da dimensao escolhida; "" = esse mundo nao existe (a recusa de dimensao cobre). */
        public String classeRand;
        /** O jar do hodgepodge esta no classpath (pelo recurso da classe de config). */
        public Boolean hodgepodgePresente;
        /** {@code FixesConfig.addSimulationDistance_WIP} (so com o hodgepodge presente). */
        public Boolean distanciaDeSimulacao;
        /** {@code FixesConfig.preventChunkLoadingFromBlockUpdates} (so com o hodgepodge presente). */
        public Boolean preventChunkLoading;
        /** O DragonAPI esta no classpath. */
        public Boolean dragonApiPresente;
        /** Classes dos observadores do {@code ChunkRequestEvent} (lidos por reflexao); so com o DragonAPI. */
        public List<String> observadores;
        /** Classes dos watchers do {@code TileUpdateEvent}; so com o DragonAPI. */
        public List<String> watchers;
        /** O firewall esta no classpath. */
        public Boolean firewallPresente;
        /** {@code WorldMutationGuard.allowTileEntityTick(TileEntity, World)Z} existe e e publico estatico. */
        public Boolean firewallTemPortao;
        /** Mixins mesclados nas classes do caminho (anotacao MixinMerged), sem repetir. */
        public List<String> mixins;
        /** Transformadores da cadeia do class loader (nomes de classe). */
        public List<String> transformadores;
        /** Por rotulo de trava ({@link Travas#JARS}): SHA-256 do jar que tem o recurso, "" = jar ausente. */
        public Map<String, String> shas;
    }

    /** O resultado: os fatos das recusas e as classes tiradas por trava de SHA (com o motivo de cada uma). */
    public static final class Resultado {
        public final Recusas.Fatos fatos;
        /** Classe (nome do config) -&gt; motivo. Tirar uma classe nao e recusa: ela so nao entra (plano, secao 6). */
        public final Map<String, String> classesTiradas;

        Resultado(Recusas.Fatos fatos, Map<String, String> classesTiradas) {
            this.fatos = fatos;
            this.classesTiradas = classesTiradas;
        }
    }

    /**
     * Interpreta. {@code base} traz os fatos do config (orcamento, parada, chunkmap), que sao copiados. {@code esconder}:
     * fatos que o teste manda tratar como nao lidos (so com {@code -Dhoshiestar.paralelo.teste=true}; vazio em producao).
     * {@code transformadoresRevisados}: a lista com veredito ({@code null} = lista ainda nao embutida: todo transformador
     * fica sem veredito).
     */
    public static Resultado interpretar(Bruto b, Recusas.Fatos base, int dimensao, Set<String> esconder,
            Set<String> transformadoresRevisados) {
        Recusas.Fatos f = new Recusas.Fatos();
        f.orcamentoLigado = base.orcamentoLigado;
        f.paradaLigada = base.paradaLigada;
        f.chunkmapCache4Seguro = base.chunkmapCache4Seguro;
        List<String> nao = new ArrayList<String>();
        Set<String> esc = esconder == null ? Collections.<String>emptySet() : esconder;

        // dimensoes
        if (b.dimensoes == null || esc.contains(DIMENSOES)) {
            nao.add(DIMENSOES + ": lista de dimensoes do servidor nao lida");
            f.dimensoes = null;
        } else {
            f.dimensoes = new HashSet<Integer>(b.dimensoes);
        }

        // world.rand
        if (b.classeRand == null || esc.contains(RAND)) {
            nao.add(RAND + ": classe do world.rand da dimensao " + dimensao + " nao lida");
        } else if (!b.classeRand.isEmpty() && !b.classeRand.equals(Travas.CLASSE_RAND)) {
            f.randTrocado = true;
            f.classeRand = b.classeRand;
        }

        // hodgepodge
        if (b.hodgepodgePresente == null || esc.contains(HODGEPODGE)) {
            nao.add(HODGEPODGE + ": presenca do hodgepodge nao lida");
        } else if (b.hodgepodgePresente) {
            if (b.distanciaDeSimulacao == null) {
                nao.add(HODGEPODGE + ": hodgepodge presente e addSimulationDistance_WIP nao lido");
            } else {
                f.distanciaDeSimulacaoLigada = b.distanciaDeSimulacao;
            }
            if (b.preventChunkLoading == null) {
                nao.add(HODGEPODGE + ": hodgepodge presente e preventChunkLoadingFromBlockUpdates nao lido");
            } else {
                f.preventChunkLoadingLigado = b.preventChunkLoading;
            }
        } else {
            sondaSumida(nao, HODGEPODGE, b.hodgepodgePresente, b, "hodgepodge");
        }

        // DragonAPI: observadores do ChunkRequestEvent e watchers do TileUpdateEvent
        if (b.dragonApiPresente == null || esc.contains(DRAGONAPI)) {
            nao.add(DRAGONAPI + ": presenca do DragonAPI nao lida");
        } else if (b.dragonApiPresente) {
            if (b.observadores == null) {
                nao.add(DRAGONAPI + ": DragonAPI presente e observadores do ChunkRequestEvent nao lidos");
            } else {
                f.observadoresNaoRevisados = fora(b.observadores, Travas.OBSERVADORES_REVISADOS);
            }
            if (b.watchers == null) {
                nao.add(DRAGONAPI + ": DragonAPI presente e watchers do TileUpdateEvent nao lidos");
            } else {
                f.watchersNaoRevisados = fora(b.watchers, Travas.WATCHERS_REVISADOS);
            }
        } else {
            sondaSumida(nao, DRAGONAPI, b.dragonApiPresente, b, "DragonAPI");
        }

        // firewall
        if (b.firewallPresente == null || esc.contains(FIREWALL)) {
            nao.add(FIREWALL + ": presenca do Akashic-World-Firewall nao lida");
        } else if (b.firewallPresente) {
            if (b.firewallTemPortao == null) {
                nao.add(FIREWALL + ": firewall presente e o metodo allowTileEntityTick nao conferido");
            } else {
                f.firewallSemPortao = !b.firewallTemPortao;
            }
        } else {
            sondaSumida(nao, FIREWALL, b.firewallPresente, b, "Akashic-World-Firewall");
        }

        // vereditos: mixins nas classes do caminho e transformadores
        Map<String, String> tiradas = new LinkedHashMap<String, String>();
        if (esc.contains(VEREDITO)) {
            nao.add(VEREDITO + ": mixins das classes do caminho, cadeia de transformadores e SHA-256 dos jars travados tratados como nao lidos");
        } else if (b.mixins == null || b.transformadores == null || b.shas == null) {
            if (b.mixins == null) {
                nao.add(VEREDITO + ": mixins das classes do caminho nao lidos");
            }
            if (b.transformadores == null) {
                nao.add(VEREDITO + ": cadeia de transformadores nao lida");
            }
            if (b.shas == null) {
                nao.add(VEREDITO + ": SHA-256 dos jars travados nao lidos");
            }
        } else {
            List<String> sem = new ArrayList<String>();
            for (String m : fora(b.mixins, Travas.MIXINS_COM_VEREDITO)) {
                sem.add("mixin " + m);
            }
            if (transformadoresRevisados == null) {
                sem.add("cadeia de transformadores (a lista revisada ainda nao foi embutida neste jar)");
            } else {
                for (String t : fora(b.transformadores, transformadoresRevisados)) {
                    sem.add("transformador " + t);
                }
            }
            f.semVeredito = sem;
            for (Travas.Trava t : Travas.JARS) {
                String sha = b.shas.get(t.rotulo);
                String motivo = null;
                if (sha == null) {
                    motivo = "SHA-256 do jar " + t.rotulo + " nao lido";
                } else if (sha.isEmpty()) {
                    String c = codigoDoJar(b, t);
                    if (c != null) {
                        motivo = "recurso-sonda do jar " + t.rotulo + " ausente mas codigo dele presente (" + c + "): SHA-256 nao conferido";
                    }
                } else if (!sha.equalsIgnoreCase(t.sha256)) {
                    motivo = "jar " + t.rotulo + " com SHA-256 diferente do revisado";
                }
                if (motivo != null) {
                    for (String c : t.classes) {
                        if (!tiradas.containsKey(c)) {
                            tiradas.put(c, motivo);
                        }
                    }
                }
            }
        }
        if (!esc.contains(VEREDITO) && b.mixins != null && b.mixins.isEmpty() && b.shas != null && algumJarTravado(b.shas)) {
            nao.add(VEREDITO + ": nenhum mixin mesclado lido nas classes do caminho com jar travado presente (anotacao MixinMerged nao visivel?)");
        }
        f.naoColetados = nao;
        f.jogoConferido = true;
        return new Resultado(f, Collections.unmodifiableMap(tiradas));
    }

    /** Algum jar travado esta no servidor (SHA lido e nao vazio). */
    static boolean algumJarTravado(Map<String, String> shas) {
        for (String s : shas.values()) {
            if (s != null && !s.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Os nomes de {@code l} (sem repetir, na ordem) que nao estao em {@code revisados}. */
    static List<String> fora(List<String> l, Set<String> revisados) {
        Set<String> r = new LinkedHashSet<String>();
        for (String s : l) {
            if (s == null || !revisados.contains(s)) {
                r.add(String.valueOf(s));
            }
        }
        return new ArrayList<String>(r);
    }

    /**
     * Classes dos itens nao nulos de um vetor estatico de ouvintes (DragonAPI). {@code null} (campo vazio) = nenhum ouvinte;
     * valor de outro tipo = {@code null} ("nao lido", vira recusa), nunca lista vazia (revisao do passo 3).
     */
    public static List<String> classesDoVetor(Object v) {
        List<String> r = new ArrayList<String>();
        if (v == null) {
            return r;
        }
        if (!(v instanceof Object[])) {
            return null;
        }
        for (Object o : (Object[]) v) {
            if (o != null) {
                r.add(o.getClass().getName());
            }
        }
        return r;
    }

    /**
     * Revisao do passo 3, I1: o primeiro mixin ou transformador do {@link Bruto} que e codigo do jar {@code t} (pelos
     * prefixos de pacote), ou null. Com o recurso-sonda ausente, achar um quer dizer que o jar esta, mudado.
     */
    static String codigoDoJar(Bruto b, Travas.Trava t) {
        for (List<String> l : Arrays.asList(b.mixins, b.transformadores)) {
            if (l == null) {
                continue;
            }
            for (String n : l) {
                for (String p : t.pacotes) {
                    if (n != null && n.startsWith(p)) {
                        return n;
                    }
                }
            }
        }
        return null;
    }

    /** Presenca lida como "ausente", mas o codigo do jar da trava esta: a presenca vira "nao lida" (I1). */
    private static void sondaSumida(List<String> nao, String fato, Boolean presente, Bruto b, String rotulo) {
        if (presente == null || presente) {
            return;
        }
        String c = codigoDoJar(b, Travas.trava(rotulo));
        if (c != null) {
            nao.add(fato + ": recurso-sonda ausente mas codigo do " + rotulo + " presente (" + c + "): jar mudado, chaves nao lidas");
        }
    }

    /** Le a propriedade de teste (lista separada por virgula). Sem {@code teste}: sempre vazio. */
    public static Set<String> esconderDaPropriedade(boolean teste, String valor) {
        Set<String> r = new HashSet<String>();
        if (!teste || valor == null) {
            return r;
        }
        for (String s : valor.split(",")) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.isEmpty()) {
                r.add(t);
            }
        }
        return r;
    }
}
