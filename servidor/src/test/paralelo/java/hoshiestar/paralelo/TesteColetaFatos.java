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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A interpretacao dos fatos do jogo coletados no boot (decisao 39d): com tudo certo aceita; cada fato faltando recusa;
 * mod ausente nao e fato faltando; nunca fail-open (sorteio de fatos faltando). Funcao pura, sem servidor.
 */
public final class TesteColetaFatos {

    private static Verifica v;
    /** Revisados de mentira, mais um transformador real de tres jars travados (Opis, DragonAPI, firewall), como na copia. */
    static final Set<String> TRANSF = new HashSet<String>(Arrays.asList("a.Transf", "b.Transf",
            "mcp.mobius.mobiuscore.asm.CoreTransformer", "Reika.DragonAPI.ASM.DragonAPIClassTransformer",
            "com.akashic.worldfirewall.WorldSetBlockTransformer"));

    public static void main(String[] args) {
        v = new Verifica("TesteColetaFatos");
        try {
            tudoCerto();
            cadaFato();
            ausentes();
            travas();
            sondaSumida();
            vetorDragonApi();
            nuncaFailOpen();
            propriedade();
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    /** O que o servidor da copia de teste devolve (passo 2): tudo revisado. */
    static ColetaFatos.Bruto bom() {
        ColetaFatos.Bruto b = new ColetaFatos.Bruto();
        b.dimensoes = new HashSet<Integer>(Arrays.asList(0, -1, 1));
        b.classeRand = Travas.CLASSE_RAND;
        b.hodgepodgePresente = true;
        b.distanciaDeSimulacao = false;
        b.preventChunkLoading = false;
        b.dragonApiPresente = true;
        b.observadores = Arrays.asList("Reika.DragonAPI.Auxiliary.WorldGenInterceptionRegistry");
        b.watchers = new ArrayList<String>();
        b.firewallPresente = true;
        b.firewallTemPortao = true;
        b.mixins = new ArrayList<String>(Travas.MIXINS_COM_VEREDITO);
        b.transformadores = new ArrayList<String>(TRANSF);
        b.shas = new HashMap<String, String>();
        for (Travas.Trava t : Travas.JARS) {
            b.shas.put(t.rotulo, t.sha256);
        }
        return b;
    }

    static Recusas.Fatos config() {
        Recusas.Fatos f = new Recusas.Fatos();
        f.orcamentoLigado = true;
        return f;
    }

    static ColetaFatos.Resultado interp(ColetaFatos.Bruto b, String... esconder) {
        return ColetaFatos.interpretar(b, config(), 0, new HashSet<String>(Arrays.asList(esconder)), TRANSF);
    }

    static List<String> recusas(ColetaFatos.Bruto b, String... esconder) {
        return Recusas.avaliar(true, Parametros.ler("sombra", 0, 3, "mesa,bau,bau_ender", "parar", "parar", 64, 5, 1200, 600),
                interp(b, esconder).fatos);
    }

    static boolean tem(List<String> l, String trecho) {
        for (String s : l) {
            if (s.contains(trecho)) {
                return true;
            }
        }
        return false;
    }

    static void tudoCerto() {
        ColetaFatos.Resultado r = interp(bom());
        v.sim(r.fatos.jogoConferido, "interpretacao marca os fatos como conferidos");
        v.sim(r.classesTiradas.isEmpty(), "SHA-256 todos iguais: nenhuma classe tirada");
        List<String> m = recusas(bom());
        v.sim(m.isEmpty(), "tudo certo (como na copia de teste): aceito " + m);
        ColetaFatos.Bruto b = bom();
        b.watchers = Arrays.asList("Reika.RotaryCraft.Auxiliary.EMPTileWatcher");
        v.sim(recusas(b).isEmpty(), "watcher EMPTileWatcher (revisado): aceito");
        v.sim(recusas(bom()).isEmpty() && config().dimensoes == null, "os fatos do config nao sao alterados");
    }

    static void cadaFato() {
        ColetaFatos.Bruto b;
        b = bom();
        b.dimensoes = null;
        v.sim(tem(recusas(b), "dimensoes: lista de dimensoes do servidor nao lida"), "dimensoes nao lidas: recusa");
        b = bom();
        b.dimensoes = new HashSet<Integer>(Arrays.asList(-1, 1));
        v.sim(tem(recusas(b), "dimensao 0 inexistente"), "dimensao escolhida nao existe: recusa");
        b = bom();
        b.classeRand = null;
        v.sim(tem(recusas(b), "rand: classe do world.rand"), "classe do rand nao lida: recusa");
        b = bom();
        b.classeRand = "jss.bugtorch.util.XSTR";
        List<String> m = recusas(b);
        v.sim(tem(m, "world.rand trocado") && tem(m, "jss.bugtorch.util.XSTR"), "rand de outra classe: recusa com o nome " + m);
        b = bom();
        b.classeRand = "";
        v.sim(recusas(b).isEmpty(), "mundo da dimensao nao carregado: o rand nao recusa (a dimensao existe)");
        b = bom();
        b.hodgepodgePresente = null;
        v.sim(tem(recusas(b), "presenca do hodgepodge nao lida"), "presenca do hodgepodge nao lida: recusa");
        b = bom();
        b.distanciaDeSimulacao = null;
        v.sim(tem(recusas(b), "addSimulationDistance_WIP nao lido"), "hodgepodge presente, chave nao lida: recusa");
        b = bom();
        b.distanciaDeSimulacao = true;
        v.sim(tem(recusas(b), "distancia de simulacao do hodgepodge ligada"), "distancia de simulacao ligada: recusa");
        b = bom();
        b.preventChunkLoading = null;
        v.sim(tem(recusas(b), "preventChunkLoadingFromBlockUpdates nao lido"), "preventChunkLoading nao lido: recusa");
        b = bom();
        b.preventChunkLoading = true;
        v.sim(tem(recusas(b), "preventChunkLoadingFromBlockUpdates ligado"), "preventChunkLoading ligado: recusa (decisao 39c)");
        b = bom();
        b.dragonApiPresente = null;
        v.sim(tem(recusas(b), "presenca do DragonAPI nao lida"), "presenca do DragonAPI nao lida: recusa");
        b = bom();
        b.observadores = null;
        v.sim(tem(recusas(b), "observadores do ChunkRequestEvent nao lidos"), "observadores nao lidos: recusa");
        b = bom();
        b.observadores = Arrays.asList("Reika.DragonAPI.Auxiliary.WorldGenInterceptionRegistry", "outro.Observador");
        v.sim(tem(recusas(b), "observador nao revisado no ChunkRequestEvent do DragonAPI: outro.Observador"), "observador novo: recusa");
        b = bom();
        b.watchers = null;
        v.sim(tem(recusas(b), "watchers do TileUpdateEvent nao lidos"), "watchers nao lidos: recusa");
        b = bom();
        b.watchers = Arrays.asList("mod.OutroWatcher");
        v.sim(tem(recusas(b), "watcher nao revisado no TileUpdateEvent do DragonAPI (so EMPTileWatcher tem veredito): mod.OutroWatcher"),
                "watcher fora de {EMPTileWatcher}: recusa (decisao 39c)");
        b = bom();
        b.firewallPresente = null;
        v.sim(tem(recusas(b), "presenca do Akashic-World-Firewall nao lida"), "presenca do firewall nao lida: recusa");
        b = bom();
        b.firewallTemPortao = null;
        v.sim(tem(recusas(b), "allowTileEntityTick nao conferido"), "metodo do firewall nao conferido: recusa");
        b = bom();
        b.firewallTemPortao = false;
        v.sim(tem(recusas(b), "firewall presente sem o metodo allowTileEntityTick"), "firewall sem o metodo: recusa");
        b = bom();
        b.mixins = null;
        v.sim(tem(recusas(b), "veredito: mixins das classes do caminho nao lidos"), "mixins nao lidos: recusa");
        b = bom();
        b.mixins = new ArrayList<String>(Travas.MIXINS_COM_VEREDITO);
        b.mixins.add("mod.novo.MixinWorld");
        v.sim(tem(recusas(b), "sem veredito do passo 0: mixin mod.novo.MixinWorld"), "mixin novo nas classes do caminho: recusa");
        b = bom();
        b.transformadores = Arrays.asList("a.Transf", "c.Novo");
        v.sim(tem(recusas(b), "sem veredito do passo 0: transformador c.Novo"), "transformador novo: recusa");
        b = bom();
        b.transformadores = null;
        v.sim(tem(recusas(b), "veredito: cadeia de transformadores nao lida"), "transformadores nao lidos: recusa");
        ColetaFatos.Resultado r = ColetaFatos.interpretar(bom(), config(), 0, Collections.<String>emptySet(), null);
        v.sim(tem(r.fatos.semVeredito, "lista revisada ainda nao foi embutida"), "jar sem a lista de transformadores embutida: sem veredito");
        b = bom();
        b.shas = null;
        v.sim(tem(recusas(b), "SHA-256 dos jars travados nao lidos"), "SHA-256 nao lidos: recusa");
    }

    static void ausentes() {
        ColetaFatos.Bruto b = bom();
        b.hodgepodgePresente = false;
        b.distanciaDeSimulacao = null;
        b.preventChunkLoading = null;
        b.dragonApiPresente = false;
        b.observadores = null;
        b.watchers = null;
        b.firewallPresente = false;
        b.firewallTemPortao = null;
        // ausente de verdade: sem o codigo deles (revisao do passo 3, I1: codigo presente com a sonda ausente recusa)
        for (String r : Arrays.asList("hodgepodge", "DragonAPI", "Akashic-World-Firewall")) {
            semCodigo(b, r);
        }
        v.sim(recusas(b).isEmpty(), "hodgepodge, DragonAPI e firewall ausentes: as recusas deles nao se aplicam");
    }

    static void travas() {
        ColetaFatos.Bruto b = bom();
        b.shas.put("AM2.5-LTS", "00");
        ColetaFatos.Resultado r = interp(b);
        v.igual(Collections.singleton("mesa"), r.classesTiradas.keySet(), "SHA do AM2 diferente: so a mesa sai");
        v.sim(recusas(b).isEmpty(), "classe tirada por trava nao e recusa");
        b = bom();
        b.shas.put("DragonAPI", "ff");
        r = interp(b);
        v.igual(new HashSet<String>(Arrays.asList("mesa", "bau", "bau_ender")), r.classesTiradas.keySet(), "SHA do DragonAPI diferente: todas saem");
        b = bom();
        b.shas.put("EtFuturum", "");
        semCodigo(b, "EtFuturum");
        v.sim(interp(b).classesTiradas.isEmpty(), "jar ausente: nada a travar");
        b = bom();
        b.shas.remove("Opis");
        r = interp(b);
        v.sim(r.classesTiradas.size() == 3 && r.classesTiradas.get("bau").contains("nao lido"), "SHA de um jar nao lido: as classes dele saem");
        b = bom();
        b.shas.put("hodgepodge", Travas.JARS.get(1).sha256.toUpperCase());
        v.sim(interp(b).classesTiradas.isEmpty(), "SHA em maiusculas confere");
    }


    /**
     * Revisao do passo 3, I1: o recurso-sonda de uma trava some do jar (mod atualizado, classe mudou de lugar), mas o codigo
     * do jar continua no servidor (mixins ou transformadores do pacote dele). Antes: "jar ausente", nada tirado, aceito.
     */
    static void sondaSumida() {
        ColetaFatos.Bruto b = bom();
        b.hodgepodgePresente = false;
        b.distanciaDeSimulacao = null;
        b.preventChunkLoading = null;
        b.shas.put("hodgepodge", "");
        ColetaFatos.Resultado r = interp(b);
        v.igual(new HashSet<String>(Travas.TODAS), r.classesTiradas.keySet(), "sonda do hodgepodge sumiu com mixins dele presentes: as classes da trava saem");
        v.sim(r.classesTiradas.containsKey("bau") && r.classesTiradas.get("bau").contains("com.mitchej123.hodgepodge."),
                "o motivo cita o codigo achado " + r.classesTiradas);
        List<String> m = recusas(b);
        v.sim(tem(m, "hodgepodge: recurso-sonda ausente mas codigo do hodgepodge presente"), "e a presenca vira 'nao lida': recusa " + m);
        // so transformador (sem mixin): tambem pega
        b = bom();
        b.shas.put("Opis", "");
        v.sim(interp(b).classesTiradas.keySet().containsAll(Travas.TODAS), "sonda do Opis sumiu com o transformador dele presente: as classes saem");
        // DragonAPI e firewall: mesma regra para a presenca
        b = bom();
        b.dragonApiPresente = false;
        b.observadores = null;
        b.watchers = null;
        b.shas.put("DragonAPI", "");
        v.sim(tem(recusas(b), "dragonapi: recurso-sonda ausente mas codigo do DragonAPI presente"), "sonda do DragonAPI sumiu com transformadores dele: recusa");
        b = bom();
        b.firewallPresente = false;
        b.firewallTemPortao = null;
        b.shas.put("Akashic-World-Firewall", "");
        v.sim(tem(recusas(b), "firewall: recurso-sonda ausente mas codigo do Akashic-World-Firewall presente"), "sonda do firewall sumiu com transformadores dele: recusa");
        // jar ausente de verdade (sem codigo nenhum dele): continua sem trava e sem recusa
        b = bom();
        b.hodgepodgePresente = false;
        b.distanciaDeSimulacao = null;
        b.preventChunkLoading = null;
        b.shas.put("hodgepodge", "");
        b.mixins = semPrefixo(b.mixins, "com.mitchej123.hodgepodge.");
        b.mixins.add("com.mitchej123.hodgepodgeX.MixinWorld"); // prefixo parecido nao e codigo do hodgepodge
        r = interp(b);
        v.sim(r.classesTiradas.isEmpty(), "hodgepodge ausente de verdade: nada tirado " + r.classesTiradas);
        v.sim(!tem(recusas(b), "hodgepodge:"), "hodgepodge ausente de verdade: sem recusa do hodgepodge");
        // lista de mixins vazia com jar travado presente: MixinMerged nao visivel = nao lido
        b = bom();
        b.mixins = new ArrayList<String>();
        v.sim(tem(recusas(b), "veredito: nenhum mixin mesclado lido nas classes do caminho"), "mixins vazios com jars travados presentes: recusa");
        b = bom();
        b.mixins = new ArrayList<String>();
        for (Travas.Trava t : Travas.JARS) {
            b.shas.put(t.rotulo, "");
        }
        b.hodgepodgePresente = false;
        b.distanciaDeSimulacao = null;
        b.preventChunkLoading = null;
        b.dragonApiPresente = false;
        b.observadores = null;
        b.watchers = null;
        b.firewallPresente = false;
        b.firewallTemPortao = null;
        b.transformadores = Arrays.asList("a.Transf", "b.Transf");
        v.sim(recusas(b).isEmpty(), "servidor sem nenhum jar travado e sem mixins: aceito " + recusas(b));
    }

    /** Tira do Bruto os mixins e transformadores do jar da trava {@code rotulo} e marca o jar ausente. */
    static void semCodigo(ColetaFatos.Bruto b, String rotulo) {
        for (String p : Travas.trava(rotulo).pacotes) {
            b.mixins = semPrefixo(b.mixins, p);
            b.transformadores = semPrefixo(b.transformadores, p);
        }
        b.shas.put(rotulo, "");
    }

    static List<String> semPrefixo(List<String> l, String prefixo) {
        List<String> r = new ArrayList<String>();
        for (String s : l) {
            if (!s.startsWith(prefixo)) {
                r.add(s);
            }
        }
        return r;
    }

    /** Revisao do passo 3, mesma familia do I1: vetor de ouvintes do DragonAPI de tipo inesperado = nao lido (null), nao "vazio". */
    static void vetorDragonApi() {
        v.igual(Arrays.asList("java.lang.String", "java.lang.Integer"), ColetaFatos.classesDoVetor(new Object[] {"a", null, 1}),
                "vetor: classes dos nao nulos");
        v.sim(ColetaFatos.classesDoVetor(null) != null && ColetaFatos.classesDoVetor(null).isEmpty(), "campo nulo: nenhum ouvinte");
        v.sim(ColetaFatos.classesDoVetor(new ArrayList<Object>(Arrays.asList("a"))) == null, "campo de outro tipo (lista): nao lido");
        v.sim(ColetaFatos.classesDoVetor("x") == null, "campo de outro tipo (texto): nao lido");
    }

    /** Sorteio: qualquer subconjunto de fatos faltando (null) ou escondido sempre recusa. */
    static void nuncaFailOpen() {
        Random rnd = new Random(39);
        int casos = 0, falhas = 0;
        for (int k = 0; k < 4000; k++) {
            ColetaFatos.Bruto b = bom();
            boolean faltou = false;
            int nulos = 0;
            if (rnd.nextInt(8) == 0) { b.dimensoes = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.classeRand = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.hodgepodgePresente = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.distanciaDeSimulacao = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.preventChunkLoading = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.dragonApiPresente = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.observadores = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.watchers = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.firewallPresente = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.firewallTemPortao = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.mixins = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.transformadores = null; nulos++; }
            if (rnd.nextInt(8) == 0) { b.shas = null; nulos++; }
            List<String> esc = new ArrayList<String>();
            for (String f : ColetaFatos.FATOS) {
                if (rnd.nextInt(10) == 0) {
                    esc.add(f);
                }
            }
            faltou = nulos > 0 || !esc.isEmpty();
            if (!faltou) {
                continue;
            }
            casos++;
            if (recusas(b, esc.toArray(new String[0])).isEmpty()) {
                falhas++;
            }
        }
        v.sim(casos > 1000 && falhas == 0, "sorteio de " + casos + " combinacoes com fato faltando ou escondido: todas recusam (falhas " + falhas + ")");
        for (String f : ColetaFatos.FATOS) {
            v.sim(tem(recusas(bom(), f), f + ":"), "fato '" + f + "' escondido: recusa com o nome dele");
        }
    }

    static void propriedade() {
        v.sim(ColetaFatos.esconderDaPropriedade(false, "rand,firewall").isEmpty(), "sem a propriedade de teste: nada e escondido");
        v.igual(new HashSet<String>(Arrays.asList("rand", "firewall")), ColetaFatos.esconderDaPropriedade(true, " Rand , firewall,, "),
                "com a propriedade de teste: lista normalizada");
        v.sim(ColetaFatos.esconderDaPropriedade(true, null).isEmpty(), "propriedade ausente: vazio");
        Map<String, String> m = new HashMap<String, String>();
        v.sim(m.isEmpty(), "(fim)");
    }
}
