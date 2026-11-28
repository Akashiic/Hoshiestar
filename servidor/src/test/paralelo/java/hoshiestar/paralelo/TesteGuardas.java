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
import java.util.List;

/**
 * Tabela de casos das guardas (plano do Degrau B, passo 3): mesma secao, outra regiao, sem regiao, thread estranha, fase
 * fechada; categorias (proibida, dono, adiada, evento); lote sujo; contadores e registro da primeira pilha; invariantes.
 */
public final class TesteGuardas {

    private static Verifica v;
    static final int F = Guardas.FECHADA, M = Guardas.MONTANDO, S = Guardas.SERVIDOR, E = Guardas.ESTRANHA, R = Guardas.REGIAO;

    public static void main(String[] args) {
        v = new Verifica("TesteGuardas");
        try {
            decisao();
            registro();
            invariantes();
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    static Guardas.Res d(Guardas.Op op, int fase, int quem, int ctx, int alvo, boolean eleg, boolean ouvinte) {
        return Guardas.decidir(op, fase, quem, ctx, alvo, eleg, ouvinte);
    }

    static void decisao() {
        // fase fechada: nada, para qualquer operacao e thread
        boolean todasFora = true;
        for (Guardas.Op op : Guardas.Op.values()) {
            for (int quem : new int[] {S, E, R}) {
                todasFora &= d(op, F, quem, 3, 3, true, true) == Guardas.Res.FORA;
            }
        }
        v.sim(todasFora, "fase fechada: toda operacao, de qualquer thread, fica FORA");
        // leitura: mesma regiao segue; outra regiao, sem regiao, outro mundo = violacao
        v.igual(Guardas.Res.SEGUE, d(Guardas.Op.LER_BLOCO, M, S, 2, 2, true, false), "leitura na mesma regiao (mesma secao ou secao da regiao): segue");
        v.igual(Guardas.Res.VIOLACAO, d(Guardas.Op.LER_BLOCO, M, S, 2, 5, true, false), "leitura em outra regiao: violacao");
        v.igual(Guardas.Res.VIOLACAO, d(Guardas.Op.LER_TILE, M, S, 2, -1, false, false), "leitura em secao sem regiao: violacao");
        v.igual(Guardas.Res.VIOLACAO, d(Guardas.Op.LER_TILE, M, S, 2, -2, false, false), "leitura em outro mundo: violacao");
        v.igual(Guardas.Res.FORA, d(Guardas.Op.LER_BLOCO, M, S, -1, 5, true, false), "leitura de tile em serie: fora");
        v.igual(Guardas.Res.VIOLACAO, d(Guardas.Op.LER_BLOCO, M, R, 2, 5, true, false), "thread de regiao lendo outra regiao: violacao");
        // dono (markDirty)
        v.igual(Guardas.Res.SEGUE, d(Guardas.Op.VIZINHO_MARKDIRTY, M, S, 1, 1, true, false), "vizinhos do markDirty no dono: segue");
        v.igual(Guardas.Res.VIOLACAO, d(Guardas.Op.CHUNK_MODIFICADO, M, S, 1, 4, true, false), "chunk modificado fora do dono: violacao");
        // proibidas: violacao mesmo no proprio dono
        for (Guardas.Op op : new Guardas.Op[] {Guardas.Op.ESCREVER_BLOCO, Guardas.Op.ESCREVER_META, Guardas.Op.AVISAR_VIZINHO,
            Guardas.Op.POR_TILE, Guardas.Op.TIRAR_TILE, Guardas.Op.ADD_TILE, Guardas.Op.ADD_TILES, Guardas.Op.ADD_ENTIDADE,
            Guardas.Op.TIRAR_ENTIDADE, Guardas.Op.SORTEIO, Guardas.Op.CARGA_CHUNK, Guardas.Op.FALTA_CHUNK, Guardas.Op.EVENTO_FORGE,
            Guardas.Op.PACOTE, Guardas.Op.SALVAR}) {
            v.igual(Guardas.Res.VIOLACAO, d(op, M, S, 1, 1, true, false), op + " no proprio dono: violacao (proibida)");
        }
        // adiadas
        for (Guardas.Op op : new Guardas.Op[] {Guardas.Op.TICK_AGENDADO, Guardas.Op.EVENTO_BLOCO, Guardas.Op.AVISO_CLIENTE}) {
            v.igual(Guardas.Res.ADIADO, d(op, M, S, 1, 7, false, false), op + ": adiado");
        }
        // evento do Bukkit: so com ouvinte e violacao
        v.igual(Guardas.Res.SEGUE, d(Guardas.Op.EVENTO_BUKKIT, M, S, 1, -2, false, false), "evento Bukkit sem ouvinte: segue");
        v.igual(Guardas.Res.VIOLACAO, d(Guardas.Op.EVENTO_BUKKIT, M, S, 1, -2, false, true), "evento Bukkit com ouvinte: violacao");
        // thread estranha: escrita, carga, salvamento e aviso esperam no portao; leitura, sorteio, evento e pacote ficam fora
        v.igual(Guardas.Res.PORTAO, d(Guardas.Op.ESCREVER_BLOCO, M, E, -1, 1, true, false), "thread estranha escrevendo: portao");
        v.igual(Guardas.Res.PORTAO, d(Guardas.Op.CARGA_CHUNK, M, E, -1, -2, false, false), "thread estranha carregando chunk: portao");
        v.igual(Guardas.Res.PORTAO, d(Guardas.Op.SALVAR, M, E, -1, -2, false, false), "thread estranha salvando: portao");
        v.igual(Guardas.Res.PORTAO, d(Guardas.Op.VIZINHO_MARKDIRTY, M, E, -1, 1, true, false), "thread estranha em operacao de dono: portao");
        v.igual(Guardas.Res.PORTAO, d(Guardas.Op.AVISO_CLIENTE, M, E, -1, 1, true, false), "thread estranha tocando som: portao");
        v.igual(Guardas.Res.FORA, d(Guardas.Op.LER_BLOCO, M, E, -1, 1, true, false), "thread estranha lendo: fora");
        v.igual(Guardas.Res.FORA, d(Guardas.Op.SORTEIO, M, E, -1, -2, false, false), "thread estranha sorteando: fora");
        v.igual(Guardas.Res.FORA, d(Guardas.Op.EVENTO_BUKKIT, M, E, -1, -2, false, true), "evento assincrono de plugin: fora");
        v.igual(Guardas.Res.FORA, d(Guardas.Op.PACOTE, M, E, -1, -2, false, false), "pacote de outra thread: fora");
        v.igual(Guardas.Res.FORA, d(Guardas.Op.EVENTO_FORGE, M, E, -1, -2, false, false), "evento Forge de outra thread: fora");
        // tile em serie (sem contexto) escrevendo em regiao elegivel: lote sujo; em regiao nao elegivel ou sem regiao: fora
        v.igual(Guardas.Res.SUJA, d(Guardas.Op.ESCREVER_BLOCO, M, S, -1, 3, true, false), "tile em serie escrevendo bloco em regiao elegivel: suja");
        v.igual(Guardas.Res.SUJA, d(Guardas.Op.POR_TILE, M, S, -1, 3, true, false), "tile em serie pondo tile em regiao elegivel: suja");
        v.igual(Guardas.Res.SUJA, d(Guardas.Op.ADD_ENTIDADE, M, S, -1, 3, true, false), "tile em serie criando entidade em regiao elegivel: suja");
        v.igual(Guardas.Res.FORA, d(Guardas.Op.ESCREVER_BLOCO, M, S, -1, 3, false, false), "escrita em regiao nao elegivel: fora");
        v.igual(Guardas.Res.FORA, d(Guardas.Op.ESCREVER_BLOCO, M, S, -1, -1, true, false), "escrita sem regiao: fora");
        v.igual(Guardas.Res.FORA, d(Guardas.Op.TICK_AGENDADO, M, S, -1, 3, true, false), "tick agendado de tile em serie: fora (nao suja)");
        v.igual(Guardas.Res.FORA, d(Guardas.Op.ADD_TILES, M, S, -1, 3, true, false), "func_147448_a sem posicao: nao suja");
    }

    static void registro() {
        Guardas.zerar();
        final List<String> linhas = new ArrayList<String>();
        Registro.Saida antiga = Registro.saida;
        Registro.saida = new Registro.Saida() {
            @Override
            public void linha(String s) {
                linhas.add(s);
            }
        };
        try {
            Guardas.servidor = Thread.currentThread();
            Registro.novoTick(4);
            Registro.tileDaVez = "tile-falso";
            Registro.vezX = 1;
            Registro.vezY = 2;
            Registro.vezZ = 3;
            Registro.registrar(Guardas.Op.ESCREVER_BLOCO, Guardas.Res.VIOLACAO, 10, 20, 1);
            Registro.registrar(Guardas.Op.ESCREVER_BLOCO, Guardas.Res.VIOLACAO, 11, 21, 1);
            v.igual(2L, Guardas.contagem(Guardas.Op.ESCREVER_BLOCO, Guardas.Res.VIOLACAO), "duas violacoes somadas");
            v.igual(1, linhas.size(), "so a primeira vai ao log");
            v.sim(linhas.get(0).contains("VIOLACAO") && linhas.get(0).contains("java.lang.String em (1, 2, 3)")
                    && linhas.get(0).contains("pilha da guarda"), "a linha diz o resultado, o tile da vez e a pilha");
            v.sim(Registro.violouNaVez, "violacao marca o tile da vez");
            Registro.registrar(Guardas.Op.EVENTO_BLOCO, Guardas.Res.ADIADO, 0, 0, -1);
            v.igual(1, linhas.size(), "adiado nao vai ao log");
            Registro.registrar(Guardas.Op.LER_BLOCO, Guardas.Res.FORA, 0, 0, -1);
            v.igual(0L, Guardas.contagem(Guardas.Op.LER_BLOCO, Guardas.Res.FORA), "FORA nao conta");
            Registro.registrar(Guardas.Op.POR_TILE, Guardas.Res.SUJA, 0, 0, 2);
            Registro.registrar(Guardas.Op.ESCREVER_BLOCO, Guardas.Res.SUJA, 0, 0, 2);
            Registro.registrar(Guardas.Op.ESCREVER_BLOCO, Guardas.Res.SUJA, 0, 0, 3);
            v.igual(2, Registro.nSujasTick, "duas regioes sujas no tick (a mesma regiao conta uma vez)");
            v.igual(2L, Guardas.seriaIlegal(), "seria ilegal = violacoes + portao (sujo nao entra)");
            v.igual(3L, Guardas.total(Guardas.Res.SUJA), "tres escritas sujas contadas");
            Registro.novoTick(4);
            v.igual(0, Registro.nSujasTick, "novo tick zera as sujas do tick");
            // thread estranha: soma, vai ao log uma vez, nao marca o tile da vez do servidor
            Registro.violouNaVez = false;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    Registro.registrar(Guardas.Op.CARGA_CHUNK, Guardas.Res.PORTAO, 5, 5, -1);
                }
            }, "plugin-assincrono");
            t.start();
            t.join();
            v.igual(1L, Guardas.contagem(Guardas.Op.CARGA_CHUNK, Guardas.Res.PORTAO), "portao de thread estranha contado");
            v.sim(!Registro.violouNaVez, "thread estranha nao marca o tile da vez");
            v.sim(linhas.get(linhas.size() - 1).contains("plugin-assincrono"), "o log diz a thread estranha");
            v.igual(3L, Guardas.seriaIlegal(), "seria ilegal inclui o portao");
        } catch (Throwable t) {
            v.erro("registro", t);
        } finally {
            Registro.saida = antiga;
            Registro.tileDaVez = null;
            Guardas.zerar();
        }
    }

    static void invariantes() {
        Invariantes.Foto a = new Invariantes.Foto();
        Invariantes.Foto b = new Invariantes.Foto();
        Object tiles = new Object(), pend = new Object(), ents = new Object(), mapa = new Object();
        v.sim(Invariantes.comparar(a, b).isEmpty(), "fotos nao tiradas: nada a comparar");
        a.tirar(tiles, 10, pend, 0, ents, 5, 7L, 3, mapa, false);
        b.tirar(tiles, 10, pend, 0, ents, 5, 7L, 3, mapa, false);
        v.sim(Invariantes.comparar(a, b).isEmpty(), "nada mudou: vazio");
        b.tirar(tiles, 11, pend, 1, new Object(), 5, 8L, 4, new Object(), false);
        List<String> dif = Invariantes.comparar(a, b);
        v.igual(6, dif.size(), "tamanho dos tiles, pendentes, lista de entidades trocada, versao do mapa de chunks, fila de agendados e mapa: " + dif);
        b.tirar(tiles, 10, pend, 0, ents, 5, 7L, 3, mapa, true);
        v.sim(Invariantes.comparar(a, b).size() == 1 && Invariantes.comparar(a, b).get(0).startsWith("captura"), "captura ligada no meio: quebra");
        a.tirar(tiles, 10, pend, 0, ents, 5, 7L, 3, mapa, true);
        v.sim(!Invariantes.comparar(a, b).isEmpty(), "captura ligada antes e depois: tambem quebra");
        a.esquecer();
        v.sim(Invariantes.comparar(a, b).isEmpty(), "foto esquecida: nada a comparar");
    }
}
