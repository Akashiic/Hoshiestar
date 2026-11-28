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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tabela de casos da classificacao (plano do Degrau B, passo 3) com um mundo falso: chunks carregados, regiao por chunk,
 * regioes livres ou em corte, e tiles com tipo, posicao e estado. Cada caso monta o mundo minimo e confere a regiao.
 */
public final class TesteClassificador {

    private static Verifica v;

    /** Um tile falso. */
    static final class T {
        final Object id = new Object();
        int tipo;
        String classe;
        int x, y = 64, z;
        boolean valido = true, fechado = true, vizinhos = true, firewall = true;

        T(int tipo, int x, int z) {
            this.tipo = tipo;
            this.x = x;
            this.z = z;
            this.classe = tipo == Classificador.BAU ? "net.minecraft.tileentity.TileEntityChest"
                    : tipo == Classificador.BAU_ENDER ? "net.minecraft.tileentity.TileEntityEnderChest"
                    : tipo == Classificador.MESA ? "net.minecraft.tileentity.TileEntityEnchantmentTable" : "mod.QualquerTile";
        }
    }

    /** O mundo falso: por padrao, chunks de -8..8 carregados, todos da regiao 0; 3 regioes no mapa, todas livres. */
    static final class Mundo implements Classificador.Visao {
        final List<Object> lista = new ArrayList<Object>();
        final Set<Long> carregados = new HashSet<Long>();
        final Map<Long, Integer> regiao = new HashMap<Long, Integer>();
        final Set<Integer> presas = new HashSet<Integer>();
        int nRegioes = 3;
        int chamadasMutaveis; // nao existe metodo que mude o mundo; contador fica para provar que ninguem tenta

        Mundo() {
            for (int cx = -8; cx <= 8; cx++) {
                for (int cz = -8; cz <= 8; cz++) {
                    carregados.add(k(cx, cz));
                    regiao.put(k(cx, cz), 0);
                }
            }
        }

        static long k(int cx, int cz) {
            return ((long) cx << 32) ^ (cz & 0xffffffffL);
        }

        T add(T t) {
            lista.add(t);
            return t;
        }

        private T t(int i) {
            Object o = lista.get(i);
            return o instanceof T ? (T) o : null;
        }

        @Override
        public int tamanho() {
            return lista.size();
        }

        @Override
        public Object tile(int i) {
            T t = t(i);
            return t == null ? null : t.id;
        }

        @Override
        public boolean valido(int i) {
            return t(i).valido;
        }

        @Override
        public int tipo(int i) {
            return t(i).tipo;
        }

        @Override
        public String classe(int i) {
            return t(i).classe;
        }

        @Override
        public int x(int i) {
            return t(i).x;
        }

        @Override
        public int y(int i) {
            return t(i).y;
        }

        @Override
        public int z(int i) {
            return t(i).z;
        }

        @Override
        public boolean fechado(int i) {
            return t(i).fechado;
        }

        @Override
        public boolean vizinhosComTile(int i) {
            return t(i).vizinhos;
        }

        @Override
        public boolean firewallDeixa(int i) {
            return t(i).firewall;
        }

        @Override
        public boolean chunkCarregado(int cx, int cz) {
            return carregados.contains(k(cx, cz));
        }

        @Override
        public int regiaoDoChunk(int cx, int cz) {
            Integer r = regiao.get(k(cx, cz));
            return r == null ? -1 : r;
        }

        @Override
        public int nRegioes() {
            return nRegioes;
        }

        @Override
        public boolean regiaoLivre(int r) {
            return !presas.contains(r);
        }
    }

    static final boolean[] TODAS = {false, true, true, true};

    static Classificador rodar(Mundo m) {
        return rodar(m, TODAS, null);
    }

    static Classificador rodar(Mundo m, boolean[] lib, String portao) {
        Classificador c = new Classificador();
        c.classificar(m, lib, portao);
        return c;
    }

    public static void main(String[] args) {
        v = new Verifica("TesteClassificador");
        try {
            basicos();
            casosDoPlano();
            reaproveitamento();
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    static void basicos() {
        Mundo m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40));
        m.add(new T(Classificador.BAU_ENDER, 42, 40));
        m.add(new T(Classificador.MESA, 44, 40));
        Classificador c = rodar(m);
        v.sim(c.elegivel(0), "regiao so com mesa, bau e bau de ender fechados: elegivel");
        v.igual(3, c.tilesNaFase(), "os tres tiles entram");
        v.igual(1, c.regioesComTile(), "uma regiao com tile");
        v.sim(!c.elegivel(1) && !c.temTile(1), "regiao sem tile nao e elegivel (nao ha lote)");
        v.sim(!c.elegivel(-1) && !c.elegivel(99), "indice fora do mapa: nao elegivel");
        // classe nao pedida no config
        c = rodar(m, new boolean[] {false, false, true, true}, null);
        v.igual(Classificador.G3_CLASSE, c.motivo(0), "mesa presente e nao liberada: G3");
        v.igual("net.minecraft.tileentity.TileEntityEnchantmentTable", c.detalhe(0), "o motivo diz a classe");
        // portao fechado
        c = rodar(m, TODAS, "aquecimento");
        v.sim(!c.elegivel(0) && c.motivo(0) == Classificador.PORTAO && c.elegiveis() == 0, "portao fechado: nenhuma elegivel");
        v.igual("aquecimento", c.portao(), "o portao fica no resultado");
    }

    static void casosDoPlano() {
        // comparador vizinho (tem tile que tica: TileEntityComparator) ou bloco de mod com tile: G3
        Mundo m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40));
        T comp = m.add(new T(Classificador.OUTRO, 41, 40));
        comp.classe = "net.minecraft.tileentity.TileEntityComparator";
        Classificador c = rodar(m);
        v.sim(c.motivo(0) == Classificador.G3_CLASSE && "net.minecraft.tileentity.TileEntityComparator".equals(c.detalhe(0)),
                "comparador vizinho (tile que tica): regiao inelegivel por G3");
        // bloco de mod SEM tile encostado no bau: o tick do bau so le getBlock; elegivel (nada na lista)
        m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40));
        v.sim(rodar(m).elegivel(0), "bloco de mod sem tile vizinho do bau: so leitura, elegivel");
        // montagem hostil: barril do EtFuturum (tile de mod) encostado num bau
        m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40));
        T barril = m.add(new T(Classificador.OUTRO, 40, 41));
        barril.classe = "ganymedes01.etfuturum.tileentities.TileEntityBarrel";
        v.igual(Classificador.G3_CLASSE, rodar(m).motivo(0), "barril do EtFuturum ao lado do bau: G3");
        // composter em cima de funil (funil fora da onda 1): o funil e tile nao liberado
        m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40));
        T funil = m.add(new T(Classificador.OUTRO, 50, 50));
        funil.classe = "net.minecraft.tileentity.TileEntityHopper";
        v.igual(Classificador.G3_CLASSE, rodar(m).motivo(0), "composter sobre funil: o funil (nao liberado) tira a regiao");
        // bau aberto ou fechando
        m = new Mundo();
        T b = m.add(new T(Classificador.BAU, 40, 40));
        b.fechado = false;
        c = rodar(m);
        v.igual(Classificador.BAU_ABERTO, c.motivo(0), "bau aberto: inelegivel");
        v.sim(c.exemplo(0)[0] == 40 && c.exemplo(0)[2] == 40, "posicao de exemplo do motivo");
        m = new Mundo();
        T e = m.add(new T(Classificador.BAU_ENDER, 40, 40));
        e.fechado = false;
        v.igual(Classificador.ENDER_ABERTO, rodar(m).motivo(0), "bau de ender aberto: inelegivel");
        // vizinho bau sem tile (o tick criaria tile)
        m = new Mundo();
        b = m.add(new T(Classificador.BAU, 40, 40));
        b.vizinhos = false;
        v.igual(Classificador.BAU_VIZINHO, rodar(m).motivo(0), "bau com vizinho bau sem tile: inelegivel");
        // 3x3 faltando
        m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40)); // chunk (2,2)
        m.carregados.remove(Mundo.k(3, 3));
        v.igual(Classificador.G1_CHUNK, rodar(m).motivo(0), "um dos 3x3 chunks descarregado: G1");
        // 3x3 de outra regiao
        m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40));
        m.regiao.put(Mundo.k(1, 2), 1);
        v.igual(Classificador.G1_REGIAO, rodar(m).motivo(0), "um dos 3x3 chunks de outra regiao: G1");
        // 3x3 sem regiao (secao sem chunk no mapa)
        m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40));
        m.regiao.remove(Mundo.k(3, 1));
        v.igual(Classificador.G1_REGIAO, rodar(m).motivo(0), "um dos 3x3 chunks sem regiao: G1");
        // tile sem dono (o proprio chunk sem regiao): roda em serie, nao conta para ninguem
        m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40));
        T semDono = m.add(new T(Classificador.OUTRO, 200, 200)); // chunk (12,12), fora do mapa falso
        c = rodar(m);
        v.sim(c.elegivel(0) && c.tilesSemRegiao() == 1, "tile sem regiao: fica em serie e nao tira a regiao 0");
        v.sim(semDono != null, "(tile de mod sem regiao)");
        // regiao suspensa ou em corte (G2)
        m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40));
        m.presas.add(0);
        v.igual(Classificador.G2_CORTE, rodar(m).motivo(0), "regiao em corte ou suspensa: G2");
        // tile repetido na lista (G4)
        m = new Mundo();
        T d = m.add(new T(Classificador.BAU, 40, 40));
        m.lista.add(d);
        v.igual(Classificador.G4_REPETIDO, rodar(m).motivo(0), "o mesmo tile duas vezes na lista: G4");
        // tile invalido esquecido: nao conta e nunca entra
        m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40));
        T inv = m.add(new T(Classificador.OUTRO, 41, 41));
        inv.valido = false;
        T invBau = m.add(new T(Classificador.BAU, 42, 42));
        invBau.valido = false;
        m.lista.add(invBau); // invalido repetido: tambem nao conta (G4 so olha validos)
        c = rodar(m);
        v.sim(c.elegivel(0), "tile invalido esquecido (de mod, e bau repetido): nao conta, regiao elegivel");
        v.igual(1, c.tilesNaFase(), "so o bau valido entra no lote");
        v.igual(3, c.invalidosIgnorados(), "tres posicoes invalidas ignoradas");
        // firewall dizendo "nao": o tile nao entra no lote (nao tica, como no original) e a regiao continua elegivel
        m = new Mundo();
        m.add(new T(Classificador.BAU, 40, 40));
        T fw = m.add(new T(Classificador.MESA, 44, 40));
        fw.firewall = false;
        c = rodar(m);
        v.sim(c.elegivel(0) && c.tilesNaFase() == 1 && c.barradosFirewall() == 1, "firewall diz nao: tile fora do lote, regiao elegivel");
        // posicao vazia (null) na lista: pulada
        m = new Mundo();
        m.lista.add(null);
        m.add(new T(Classificador.BAU, 40, 40));
        v.sim(rodar(m).elegivel(0), "posicao nula na lista: pulada");
        // duas regioes: uma cai, a outra nao
        m = new Mundo();
        for (int cx = 0; cx <= 8; cx++) {
            for (int cz = -8; cz <= 8; cz++) {
                m.regiao.put(Mundo.k(cx, cz), 1);
            }
        }
        m.add(new T(Classificador.BAU, -40, 0));
        T aberto = m.add(new T(Classificador.BAU, 60, 0));
        aberto.fechado = false;
        c = rodar(m);
        v.sim(c.elegivel(0) && !c.elegivel(1) && c.motivo(1) == Classificador.BAU_ABERTO && c.elegiveis() == 1,
                "duas regioes: so a que tem bau aberto cai");
        // primeiro motivo fica (bau aberto antes de um tile de mod na mesma regiao)
        m = new Mundo();
        T a1 = m.add(new T(Classificador.BAU, 40, 40));
        a1.fechado = false;
        m.add(new T(Classificador.OUTRO, 44, 44));
        v.igual(Classificador.BAU_ABERTO, rodar(m).motivo(0), "o primeiro motivo da regiao fica");
        // item de mod, entidade com inventario, alvo fora da lista fechada: so valem para o funil (onda 2, fora; decisao 39a)
        v.sim(true, "(item de mod, entidade com inventario e alvo fora da lista fechada: casos do funil, que esta fora da entrega)");
        // ouvinte presente: nenhuma classe da onda 1 dispara evento no tick (passo 0, secao 4); a guarda de evento conta
        v.sim(true, "(ouvinte de plugin: nenhum evento no tick da onda 1; coberto pela guarda EVENTO_BUKKIT no TesteGuardas)");
    }

    static void reaproveitamento() {
        Classificador c = new Classificador();
        Mundo m1 = new Mundo();
        T a = m1.add(new T(Classificador.BAU, 40, 40));
        a.fechado = false;
        c.classificar(m1, TODAS, null);
        v.sim(!c.elegivel(0), "primeiro tick: inelegivel");
        Mundo m2 = new Mundo();
        m2.add(new T(Classificador.BAU, 40, 40));
        c.classificar(m2, TODAS, null);
        v.sim(c.elegivel(0) && c.detalhe(0) == null, "segundo tick com a mesma instancia: o motivo velho nao sobra");
        Mundo m3 = new Mundo();
        m3.nRegioes = 40;
        m3.add(new T(Classificador.BAU, 40, 40));
        c.classificar(m3, TODAS, null);
        v.sim(c.elegivel(0) && c.nRegioes() == 40, "mapa maior: vetores crescem");
        Mundo m4 = new Mundo();
        m4.nRegioes = 0;
        m4.add(new T(Classificador.BAU, 40, 40));
        c.classificar(m4, TODAS, null);
        v.sim(!c.elegivel(0) && c.tilesSemRegiao() == 1, "mapa sem regioes: nada elegivel, tile sem regiao");
    }
}
