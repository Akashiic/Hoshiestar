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
import hoshiestar.regioes.MapaSecoes;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.ReportedException;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * O modo {@code sombra_ordenada} no jogo (plano do Degrau B, passo 4). So entra aqui quem {@link GanchosParalelo} manda,
 * e so com {@link GanchosParalelo#ordenada} verdadeiro.
 *
 * <ul>
 * <li><b>Despacho</b> ({@link #despachar}, no laco, uma linha antes do gancho do orcamento): pergunta ao firewall, em
 * serie, se o tile pode ticar ({@code WorldMutationGuard.allowTileEntityTick}, a mesma pergunta da chamada que o firewall
 * pos no laco); "nao" = o tile nao tica, como no original. "Sim" = o tile entra no lote da regiao, na ordem do laco, e o
 * laco segue ({@code continue}).</li>
 * <li><b>Fase</b> ({@link #fimDoLaco}, depois do laco): (teste) {@code injetar quebrar}; os lotes que uma escrita em serie
 * sujou; fecha a vez do orcamento (secao 7: o tempo dos lotes nao cai na conta da ultima regiao em serie); foto dos
 * invariantes; a thread do servidor roda os lotes sozinha ({@link Ordenada}), com o contexto "lote R" aberto (as guardas
 * contam e os pontos "A" vao para o lote); confere os invariantes; {@link Juncao}; a politica da secao 9.</li>
 * </ul>
 *
 * <p>Erro no nosso codigo no meio de um tick: os tiles ja despachados rodam em serie, uma vez (a {@link Juncao#emergencia}
 * anda pelos cursores do lote), e os que ainda nao foram despachados ficam no laco original. Nunca um tile despachado
 * fica sem ticar por erro nosso.
 *
 * <p>So a thread do servidor.
 */
final class OrdenadaJogo {

    private OrdenadaJogo() {
    }

    static final ArrayList<Lote> LOTES = new ArrayList<Lote>();
    private static Lote[] porRegiao = new Lote[0];
    private static int dimIdx = -1;
    /** Erro nosso neste tick: nenhum despacho novo; os lotes rodam em serie no fim do laco. */
    static boolean falhouNoTick;
    /** {@code rebaixar} (secao 9): a dimensao fica em serie ate o proximo boot. */
    static volatile String rebaixado;
    private static final Invariantes.Foto FASE_ANTES = new Invariantes.Foto();
    private static final Invariantes.Foto FASE_DEPOIS = new Invariantes.Foto();
    private static int falhasLogadas;

    // ---- comeco do tick e despacho

    /** {@code abrir}: lotes vazios para as regioes deste tick. Lotes que sobraram (fase esquecida) sao descartados antes. */
    static void novoTick(int nRegioes, int di) {
        if (!LOTES.isEmpty()) {
            descartar();
        }
        if (porRegiao.length < nRegioes) {
            porRegiao = new Lote[Math.max(nRegioes, porRegiao.length * 2)];
        } else {
            java.util.Arrays.fill(porRegiao, null);
        }
        dimIdx = di;
        falhouNoTick = false;
    }

    /**
     * Fase esquecida em MONTANDO (um tile em serie lancou depois do despacho e o laco saiu sem {@code fimLaco}): os tiles
     * dos lotes nao ticam neste tick, como os tiles depois do erro no original (plano, secao 2).
     */
    static void descartar() {
        int n = 0;
        for (Lote l : LOTES) {
            n += l.tamanho() - Math.min(l.proximo, l.tamanho());
        }
        GanchosParalelo.PAINEL.lotesDescartados += LOTES.size();
        if (GanchosParalelo.PAINEL.lotesDescartados <= 5 * Math.max(1, LOTES.size())) {
            Log.aviso("paralelo (sombra_ordenada): " + LOTES.size() + " lote(s) de uma fase esquecida descartado(s) (" + n
                    + " tile(s) nao ticaram naquele tick; o laco saiu por excecao de um tile em serie, como no original)");
        }
        LOTES.clear();
        java.util.Arrays.fill(porRegiao, null);
    }

    static boolean temLotes() {
        return !LOTES.isEmpty();
    }

    /**
     * Despacho de um tile de regiao elegivel e classe liberada (ja conferido por quem chama). Devolve verdadeiro se o laco
     * deve seguir sem ticar o tile aqui (foi para o lote, ou o firewall disse "nao"). Erro sobe (quem chama trata como erro
     * nosso e o tile tica pela chamada original).
     */
    static boolean despachar(World w, TileEntity te, int r) throws Exception {
        if (falhouNoTick || r < 0 || r >= porRegiao.length) {
            return false;
        }
        Method fw = Firewall.metodo();
        if (fw != null && !Boolean.TRUE.equals(fw.invoke(null, te, w))) {
            GanchosParalelo.PAINEL.barradosNoDespacho++;
            return true; // o firewall nao deixa ticar: o original tambem nao ticaria (runTileEntityUpdate)
        }
        Lote l = porRegiao[r];
        if (l == null) {
            l = new Lote(r, TICADOR);
            porRegiao[r] = l;
            LOTES.add(l);
        }
        l.adicionar(te, MapaSecoes.chave(dimIdx, te.xCoord >> 8, te.zCoord >> 8));
        return true;
    }

    // ---- fim do laco: a fase e a juncao

    /**
     * Roda a fase deste tick. Devolve a excecao que o jogo deve receber ({@code ao_falhar}/{@code ao_violar: parar}: o
     * caminho de crash de hoje) ou null. Nunca lanca (erro nosso vira emergencia).
     */
    static RuntimeException fimDoLaco(World w) {
        if (LOTES.isEmpty()) {
            return null;
        }
        Painel P = GanchosParalelo.PAINEL;
        Parametros p = Paralelo.parametros();
        List<Lote> lotes = new ArrayList<Lote>(LOTES);
        LOTES.clear();
        java.util.Arrays.fill(porRegiao, null);
        long t0 = System.nanoTime();
        int tiles = 0;
        for (Lote l : lotes) {
            tiles += l.tamanho();
        }
        P.ticksComLotes++;
        P.lotesTick = lotes.size();
        P.tilesLotesTick = tiles;
        P.lotesTotal += lotes.size();
        P.tilesLotesTotal += tiles;
        Juncao.Resultado res = null;
        boolean invariante = false;
        Quebra q = null;
        try {
            if (!falhouNoTick && Congelar.TESTE) {
                q = Quebra.executar(w, lotes);
            }
            int sujos = 0;
            for (Lote l : lotes) {
                if (Registro.sujo(l.regiao)) {
                    l.sujar();
                    sujos++;
                }
            }
            P.lotesSujosFase += sujos;
            if (hoshiestar.orcamento.Orcamento.LIGADO) {
                hoshiestar.orcamento.GanchosOrcamento.fimLaco(); // secao 7: fecha a vez da ultima regiao em serie
            }
            if (falhouNoTick) {
                res = emergencia(lotes, "erro no nosso codigo no despacho deste tick");
            } else {
                GanchosParalelo.foto(w, FASE_ANTES);
                List<Andamento> and;
                long tl = System.nanoTime();
                try {
                    and = Ordenada.rodar(lotes, CONTEXTO);
                } finally {
                    CONTEXTO.fechar();
                }
                P.nsLotesTotal += System.nanoTime() - tl;
                GanchosParalelo.foto(w, FASE_DEPOIS);
                List<String> dif = Invariantes.comparar(FASE_ANTES, FASE_DEPOIS);
                if (!dif.isEmpty() && Registro.violacoesTick == 0) {
                    invariante = true;
                    P.invariantesFase++;
                    if (P.invariantesFase <= 5) {
                        Log.aviso("paralelo (sombra_ordenada): invariante da fase quebrado entre o comeco dos lotes e a barreira: " + dif);
                    }
                }
                Guardas.fase = Guardas.FECHADA; // juncao: em serie, como depois da barreira (o portao nao segura mais)
                long tj = System.nanoTime();
                res = Juncao.juntar(and);
                P.nsJuncaoTotal += System.nanoTime() - tj;
            }
        } catch (Throwable t) {
            GanchosParalelo.PAINEL.errosNossos++;
            Log.erro("paralelo (sombra_ordenada): erro no nosso codigo na fase; os tiles que faltam rodam em serie, uma vez", t);
            CONTEXTO.fechar();
            Juncao.Resultado e = emergencia(lotes, "erro no nosso codigo na fase");
            if (res == null) {
                res = e;
            } else {
                res.falhas.addAll(e.falhas);
                res.itensEmSerie += e.itensEmSerie;
                res.adiadosAplicados += e.adiadosAplicados;
            }
        }
        int pulados = 0;
        for (Lote l : lotes) {
            pulados += l.pulados();
        }
        P.puladosTotal += pulados;
        P.adiadosAplicadosTotal += res.adiadosAplicados;
        P.itensEmSerieTotal += res.itensEmSerie;
        P.lotesEmSerieTotal += res.lotesEmSerie;
        P.falhasTotal += res.falhas.size();
        P.nsFaseTotal += System.nanoTime() - t0;
        if (q != null) {
            q.relatar();
        }
        for (Juncao.Falha f : res.falhas) {
            if (falhasLogadas++ < 10) {
                Log.erro("paralelo (sombra_ordenada): falha na fase: " + f, f.erro);
            }
        }
        hoshiestar.vigia.Marcas.tile(null); // a juncao marcou os tiles em serie; o laco ja tinha acabado
        Juncao.Acao a = Juncao.decidir(res, Registro.violacoesTick > 0, invariante, p.aoFalhar, p.aoViolar);
        if (a == Juncao.Acao.PARAR) {
            return crash(res, Registro.violacoesTick, invariante);
        }
        if (a == Juncao.Acao.REBAIXAR) {
            rebaixado = res.temFalha() ? "falha na fase (" + res.falhas.get(0) + "), ao_falhar: rebaixar"
                    : "violacao ou invariante quebrado na fase, ao_violar: rebaixar";
            Log.erro("paralelo (sombra_ordenada): " + rebaixado + ". A dimensao fica em serie ate o proximo boot. Confira a"
                    + " conservacao antes de religar.", null);
        }
        return null;
    }

    private static Juncao.Resultado emergencia(List<Lote> lotes, String porque) {
        GanchosParalelo.PAINEL.emergencias++;
        Guardas.fase = Guardas.FECHADA;
        return Juncao.emergencia(lotes);
    }

    private static RuntimeException crash(Juncao.Resultado res, int violacoes, boolean invariante) {
        Juncao.Falha f = null;
        for (Juncao.Falha x : res.falhas) {
            if (f == null || (f.erro == null && x.erro != null)) {
                f = x;
            }
        }
        String texto = f != null ? "paralelo (sombra_ordenada), ao_falhar: parar: " + f
                : "paralelo (sombra_ordenada), ao_violar: parar: " + (violacoes > 0 ? violacoes + " violacao(oes) de guarda na fase" : "invariante da fase quebrado");
        Throwable causa = f != null && f.erro != null ? f.erro : new IllegalStateException(texto);
        CrashReport cr;
        try {
            cr = CrashReport.makeCrashReport(causa, f != null && f.erro != null ? "Ticking block entity" : "Hoshiestar paralelo: violacao na fase");
        } catch (Throwable t) {
            // sem crash-report (nao montou): o 'parar' vale assim mesmo, com uma excecao simples (revisao do passo 4, m1);
            // lancar daqui faria o GanchosParalelo.fimLaco tratar como erro nosso e engolir a parada
            RuntimeException e = new RuntimeException(texto + " (crash-report nao montado: " + t + ")", causa);
            e.addSuppressed(t);
            return e;
        }
        try {
            if (f != null && f.lote != null && f.item >= 0 && f.item < f.lote.tamanho() && f.lote.tile(f.item) instanceof TileEntity) {
                ((TileEntity) f.lote.tile(f.item)).func_145828_a(cr.makeCategory("Block entity being ticked"));
            }
            CrashReportCategory c = cr.makeCategory("Hoshiestar paralelo (Degrau B)");
            c.addCrashSection("Modo", "sombra_ordenada");
            c.addCrashSection("Motivo", texto);
            c.addCrashSection("Falhas", String.valueOf(res.falhas));
        } catch (Throwable t) {
            // o crash-report sai assim mesmo
        }
        return new ReportedException(cr);
    }

    // ---- contexto do lote e tick de cada tile

    static final Ordenada.Contexto CONTEXTO = new Ordenada.Contexto() {
        @Override
        public void abrir(Lote l) {
            Guardas.regiaoAberta = l.regiao;
            GanchosParalelo.loteDaVez = l;
            Guardas.loteAberto = true;
        }

        @Override
        public void fechar() {
            GanchosParalelo.loteDaVez = null;
            Guardas.loteAberto = false;
            Guardas.regiaoAberta = -1;
            GanchosParalelo.esquecerTile();
            hoshiestar.vigia.Marcas.tile(null);
        }

        @Override
        public boolean violou() {
            return Registro.violacoesTick > 0;
        }

        @Override
        public long agora() {
            return System.nanoTime();
        }
    };

    static final Lote.Ticador TICADOR = new Lote.Ticador() {
        @Override
        public boolean vivo(Object o, boolean serie) {
            TileEntity te = (TileEntity) o;
            if (te.isInvalid()) {
                return false;
            }
            World w = te.getWorldObj(); // nunca hasWorldObj (o AM2 troca esse metodo e escreve num mapa)
            if (w == null) {
                return false;
            }
            if (!serie) {
                return true;
            }
            // lote em serie na juncao: o tile ainda e o do chunk naquela posicao (le o mapa do chunk: nao cria tile)
            if (!(w instanceof WorldServer)) {
                return false;
            }
            ChunkProviderServer cps = ((WorldServer) w).theChunkProviderServer;
            int cx = te.xCoord >> 4, cz = te.zCoord >> 4;
            if (cps == null || !cps.chunkExists(cx, cz)) {
                return false;
            }
            Chunk c = cps.provideChunk(cx, cz);
            return c != null && c.chunkTileEntityMap.get(new ChunkPosition(te.xCoord & 15, te.yCoord, te.zCoord & 15)) == te;
        }

        @Override
        public void ticar(Object o) {
            TileEntity te = (TileEntity) o;
            boolean noLote = GanchosParalelo.loteDaVez != null;
            if (noLote) {
                try {
                    GanchosParalelo.contextoTile(te.getWorldObj(), te, Guardas.regiaoAberta);
                } catch (Throwable t) {
                    GanchosParalelo.PAINEL.errosNossos++;
                }
            }
            hoshiestar.vigia.Marcas.tile(te);
            te.updateEntity(); // o tick do tile: uma excecao daqui sobe para o lote (plano, secao 9)
            if (noLote) {
                try {
                    GanchosParalelo.fimTileDoLote();
                } catch (Throwable t) {
                    GanchosParalelo.PAINEL.errosNossos++;
                }
            }
        }

        @Override
        public String descrever(Object o) {
            TileEntity te = (TileEntity) o;
            return te.getClass().getName() + " em (" + te.xCoord + ", " + te.yCoord + ", " + te.zCoord + ")";
        }
    };

    // ---- firewall (lido uma vez; o boot ja conferiu que o metodo existe quando o firewall esta presente)

    static final class Firewall {
        private static boolean lido;
        private static Method metodo;
        private static String erro;

        static Method metodo() {
            if (!lido) {
                lido = true;
                try {
                    ClassLoader cl = net.minecraft.launchwrapper.Launch.classLoader != null ? net.minecraft.launchwrapper.Launch.classLoader
                            : OrdenadaJogo.class.getClassLoader();
                    if (cl.getResource(Travas.RECURSO_FIREWALL) != null) {
                        Class<?> c = Class.forName(Travas.CLASSE_FIREWALL, false, cl);
                        Method m = c.getMethod("allowTileEntityTick", Class.forName("net.minecraft.tileentity.TileEntity", false, cl),
                                Class.forName("net.minecraft.world.World", false, cl));
                        if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == boolean.class) {
                            metodo = m;
                        } else {
                            erro = "allowTileEntityTick nao e estatico booleano";
                        }
                    }
                } catch (Throwable t) {
                    erro = "allowTileEntityTick ilegivel: " + t;
                }
            }
            if (erro != null) {
                throw new IllegalStateException("firewall presente e " + erro);
            }
            return metodo;
        }
    }

    // ---- teste: injetar quebrar (so com -Dhoshiestar.paralelo.teste=true)

    static final class Quebra {
        final TileEntity alvo;
        final Lote lote;
        final int puladosAntes, itens;
        final String desc;

        private Quebra(TileEntity alvo, Lote lote, int itens) {
            this.alvo = alvo;
            this.lote = lote;
            this.puladosAntes = lote.pulados();
            this.itens = itens;
            this.desc = TICADOR.descrever(alvo);
        }

        /** Tira o pedido e quebra o tile em serie (antes de os lotes rodarem, depois do despacho). */
        static Quebra executar(World w, List<Lote> lotes) {
            int[] q = Injecao.tirarQuebrar();
            if (q == null) {
                return null;
            }
            for (Lote l : lotes) {
                for (int i = 0; i < l.tamanho(); i++) {
                    TileEntity te = (TileEntity) l.tile(i);
                    boolean bate = q.length == 3 ? te.xCoord == q[0] && te.yCoord == q[1] && te.zCoord == q[2]
                            : te.getClass() == TileEntityChest.class && itens(te) > 0;
                    if (bate && !te.isInvalid()) {
                        Quebra qb = new Quebra(te, l, itens(te));
                        Injecao.ultimo = "quebrando " + qb.desc;
                        Log.aviso("paralelo (teste): injetar quebrar: " + qb.desc + " ja despachado para o lote da regiao " + l.regiao
                                + " (" + l.tamanho() + " tile(s)); " + qb.itens + " item(ns) dentro; quebrando em serie (func_147480_a sem o"
                                + " drop do proprio bloco: o conteudo cai no chao)");
                        w.func_147480_a(te.xCoord, te.yCoord, te.zCoord, false);
                        return qb;
                    }
                }
            }
            Injecao.devolver(q);
            return null;
        }

        void relatar() {
            boolean pulou = lote.pulados() > puladosAntes;
            String r = "injetar quebrar: " + desc + (alvo.isInvalid() ? " invalidado" : " AINDA VALIDO") + "; lote da regiao "
                    + lote.regiao + (lote.sujo() ? " sujo (rodou em serie na juncao)" : " LIMPO") + "; o tile "
                    + (pulou ? "foi pulado pela reconferencia (nao ticou)" : "NAO foi pulado") + "; " + itens + " item(ns) cairam no chao";
            Injecao.ultimo = r;
            Log.aviso("paralelo (teste): " + r);
        }

        static int itens(TileEntity te) {
            if (!(te instanceof IInventory)) {
                return 0;
            }
            IInventory inv = (IInventory) te;
            int n = 0;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s != null) {
                    n += s.stackSize;
                }
            }
            return n;
        }
    }
}
