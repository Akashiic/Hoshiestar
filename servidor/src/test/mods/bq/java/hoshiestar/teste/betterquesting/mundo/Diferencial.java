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
package hoshiestar.teste.betterquesting.mundo;

import betterquesting.api.utils.BigItemStack;
import betterquesting.api2.utils.ParticipantInfo;
import bq_standard.tasks.TaskOptionalRetrieval;
import bq_standard.tasks.TaskRetrieval;
import hoshiestar.api.ThreadServidor;
import hoshiestar.betterquesting.BetterQuesting;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Teste DIFERENCIAL aleatorio (o harness da conferencia 1.D, agora contra os ganchos REAIS): monta o
 * mesmo cenario duas vezes (mesma semente), roda o laco original numa copia e o laco com os ganchos
 * do Hoshiestar na outra, e compara tudo o que o BQ pode mudar. Exigido: 0 diferencas.
 *
 * <p>Alem do que a conferencia sorteava (grupo de 1 a 3, online/offline, objeto velho de respawn,
 * inventarios de 40 espacos com quantidade 0/negativa/item nulo, 1 a 8 tarefas, flags, itens com
 * dano/curinga/ore/quantidade 0 e -1, progresso ausente/parcial/completo/negativo/tamanho errado (um a
 * mais ou um a menos que a lista de itens),
 * completas aleatorias, itens no ore ""), este sorteia: varios jogadores no MESMO tick (o cache e
 * por ParticipantInfo), tarefas de addon que poem item no inventario no meio do laco (o conjunto
 * de presentes tem de ser refeito), Items com hashCode instavel, jogador com inventario nulo e uuid
 * nulo no grupo.
 */
public final class Diferencial {

    /** Items do sorteio: 0..13 comuns (de 4 em 4, "danificaveis"), 14..15 com hashCode instavel. */
    static final Item[] POOL = new Item[16];

    static {
        for (int i = 0; i < 14; i++) {
            POOL[i] = new Item("it" + i, i % 4 == 0);
        }
        POOL[14] = new Extras.ItemInstavel("instavel14");
        POOL[15] = new Extras.ItemInstavel("instavel15");
    }

    private Diferencial() {
    }

    /**
     * Roda n cenarios a partir da semente. modo = BetterQuesting.LIGADO ou VERIFICAR. Devolve: [0] =
     * resumo; [1..] = as primeiras diferencas (vazio = nenhuma).
     */
    public static List<String> rodar(int n, long semente, int modo) throws Exception {
        ThreadServidor.definir(Thread.currentThread());
        BetterQuesting.modo = modo;
        long[] c0 = BetterQuesting.contagem.clone();
        List<String> saida = new ArrayList<String>();
        saida.add("");
        long dif = 0;
        long excecoes = 0;
        long chamadasOriginais = 0;
        for (int s = 0; s < n; s++) {
            final long seed = semente * 1000003L + s;
            Mundo a = Mundo.novo(montador(seed));
            Mundo b = Mundo.novo(montador(seed));
            a.original();
            b.comGanchos();
            if (a.excecao != null) {
                excecoes++;
            }
            chamadasOriginais += (long) a.infos.size() * a.tarefas.size();
            String fa = a.impressao();
            String fb = b.impressao();
            if (!fa.equals(fb)) {
                dif++;
                if (saida.size() <= 5) {
                    saida.add("DIFERENCA semente=" + seed + "\n   original=" + fa + "\n   ganchos =" + fb + "\n   tarefas=" + a.descrever());
                }
            }
        }
        long[] c = BetterQuesting.contagem;
        long pul = 0;
        long pas = 0;
        for (int i = 0; i <= BetterQuesting.PULA_SEM_ITEM; i++) {
            pul += c[i] - c0[i];
        }
        for (int i = BetterQuesting.PASSA_CLASSE; i <= BetterQuesting.PASSA_ERRO; i++) {
            pas += c[i] - c0[i];
        }
        saida.set(0, "cenarios=" + n + " semente=" + semente + " modo=" + BetterQuesting.nomeDoModo(modo)
                + " chamadas=" + (pul + pas) + " (laco original: " + chamadasOriginais + ")"
                + " puladas=" + pul + " (consume=" + (c[0] - c0[0]) + " completa=" + (c[1] - c0[1]) + " sem item=" + (c[2] - c0[2]) + ")"
                + " original=" + pas + " (classe=" + (c[3] - c0[3]) + " invalido=" + (c[4] - c0[4]) + " ore=" + (c[5] - c0[5])
                + " presente=" + (c[6] - c0[6]) + " parcial=" + (c[7] - c0[7]) + " satisfeita=" + (c[8] - c0[8])
                + " inventario nulo=" + (c[9] - c0[9]) + " estranho=" + (c[10] - c0[10]) + " erro=" + (c[12] - c0[12]) + ")"
                + " reconstrucoes=" + (c[BetterQuesting.RECONSTRUCOES] - c0[BetterQuesting.RECONSTRUCOES])
                + " comparadas=" + (c[BetterQuesting.COMPARADAS] - c0[BetterQuesting.COMPARADAS])
                + " divergencias=" + (c[BetterQuesting.DIVERGENCIAS] - c0[BetterQuesting.DIVERGENCIAS])
                + " erros=" + (c[BetterQuesting.ERROS] - c0[BetterQuesting.ERROS])
                + " cenariosComExcecao=" + excecoes + " DIFERENCAS=" + dif);
        return saida;
    }

    static Mundo.Montador montador(final long seed) {
        return new Mundo.Montador() {
            public void montar(Mundo m) throws Exception {
                montarAleatorio(m, new Random(seed), seed);
            }
        };
    }

    static void montarAleatorio(Mundo m, Random r, long seed) throws Exception {
        // ore dictionary do cenario (listas reaproveitadas, como as listas vivas do Forge)
        for (String k : new String[] {"", "oreA", "oreB"}) {
            OreDictionary.getOres(k).clear();
        }
        if (r.nextInt(20) == 0) {
            OreDictionary.getOres("").add(new ItemStack(POOL[r.nextInt(POOL.length)], 1, r.nextBoolean() ? 32767 : 0));
        }
        if (r.nextInt(3) > 0) {
            OreDictionary.getOres("oreA").add(new ItemStack(POOL[r.nextInt(POOL.length)], 1, r.nextBoolean() ? 32767 : r.nextInt(3)));
        }
        // jogadores do tick (normalmente 1; as vezes 2 ou 3, as vezes do mesmo grupo)
        int np = r.nextInt(4) == 0 ? 2 + r.nextInt(2) : 1;
        List<UUID> todosU = new ArrayList<UUID>();
        List<EntityPlayer> ja = new ArrayList<EntityPlayer>();
        List<UUID> jaU = new ArrayList<UUID>();
        for (int q = 0; q < np; q++) {
            int all = 1 + r.nextInt(3);
            List<UUID> allU = new ArrayList<UUID>();
            List<EntityPlayer> active = new ArrayList<EntityPlayer>();
            EntityPlayer me;
            UUID meU;
            if (q > 0 && r.nextBoolean()) { // outro jogador do mesmo grupo de antes
                int i = r.nextInt(ja.size());
                me = ja.get(i);
                meU = jaU.get(i);
            } else {
                me = jogador(m, r);
                meU = new UUID(seed, q * 8);
            }
            allU.add(meU);
            active.add(me);
            for (int i = 1; i < all; i++) {
                if (q > 0 && r.nextInt(3) == 0 && !ja.isEmpty()) {
                    int j = r.nextInt(ja.size());
                    if (!allU.contains(jaU.get(j))) {
                        allU.add(jaU.get(j));
                        active.add(ja.get(j));
                    }
                    continue;
                }
                UUID u = new UUID(seed, q * 8 + i);
                allU.add(u);
                if (r.nextBoolean()) {
                    active.add(jogador(m, r));
                }
            }
            if (r.nextInt(25) == 0) {
                active.set(0, jogador(m, r)); // objeto velho: PLAYER != o objeto da lista (respawn)
            }
            if (r.nextInt(40) == 0) {
                EntityPlayer x = r.nextBoolean() ? me : active.get(r.nextInt(active.size()));
                x.field_71071_by = null; // inventario nulo (C5)
            }
            if (r.nextInt(300) == 0) {
                allU.add(null); // uuid nulo no grupo
            }
            for (int i = 0; i < active.size(); i++) {
                if (!ja.contains(active.get(i))) {
                    ja.add(active.get(i));
                    jaU.add(i < allU.size() ? allU.get(i) : meU);
                }
            }
            for (UUID u : allU) {
                if (u != null && !todosU.contains(u)) {
                    todosU.add(u);
                }
            }
            m.info(me, meU, Collections.unmodifiableList(active), Collections.unmodifiableList(allU));
        }
        // tarefas
        int nt = 1 + r.nextInt(8);
        for (int k = 0; k < nt; k++) {
            int tipo = r.nextInt(15);
            TaskRetrieval t = tipo == 0 ? new TaskOptionalRetrieval()
                    : tipo == 1 ? new Extras.TarefaQueMexe(POOL[r.nextInt(POOL.length)]) : new TaskRetrieval();
            t.consume = r.nextInt(4) == 0;
            t.autoConsume = r.nextBoolean();
            t.groupDetect = r.nextInt(3) == 0;
            t.requireOnlyOneItem = r.nextInt(3) == 0;
            t.ignoreNBT = r.nextInt(4) > 0;
            t.partialMatch = r.nextBoolean();
            int ni = r.nextInt(12) == 0 ? 0 : 1 + r.nextInt(3);
            List<BigItemStack> itens = new ArrayList<BigItemStack>();
            for (int i = 0; i < ni; i++) {
                int dmg = r.nextInt(6) == 0 ? 32767 : r.nextInt(3);
                BigItemStack b = new BigItemStack(new ItemStack(POOL[r.nextInt(POOL.length)], 1, dmg));
                int[] sizes = {1, 1, 2, 3, 5, 16, 64, 0, -1};
                b.stackSize = sizes[r.nextInt(r.nextInt(10) == 0 ? sizes.length : sizes.length - 2)];
                int o = r.nextInt(10);
                if (o == 0) {
                    b.setOreDict("oreA");
                } else if (o == 1) {
                    b.setOreDict("oreB");
                }
                itens.add(b);
            }
            m.tarefa(t, itens.toArray(new BigItemStack[0]));
            Map<UUID, Object> up = Mundo.progresso(t);
            for (UUID u : todosU) {
                int x = r.nextInt(10);
                if (x < 4) {
                    continue; // ausente
                }
                // tamanho do progresso salvo: o certo; as vezes um a mais; as vezes um a MENOS (quest
                // editada por um op depois que o progresso foi salvo, ganhou um item: revisao M1)
                int xl = r.nextInt(12);
                int len = xl == 0 ? ni + 1 : xl == 1 && ni >= 1 ? ni - 1 : ni;
                int[] pr = new int[len];
                for (int i = 0; i < len; i++) {
                    int req = i < ni ? itens.get(i).stackSize : 1;
                    int c = r.nextInt(8);
                    pr[i] = c < 4 ? 0 : c == 4 ? Math.max(0, req - 1) : c == 5 ? req : c == 6 ? req + 3 : -1;
                }
                up.put(u, pr);
            }
            for (UUID u : todosU) {
                if (r.nextInt(6) == 0) {
                    Mundo.completos(t).add(u);
                }
            }
        }
    }

    static EntityPlayer jogador(Mundo m, Random r) {
        EntityPlayer p = m.jogador();
        int density = r.nextInt(4);
        for (int i = 0; i < 40; i++) {
            if (r.nextInt(8) >= density) {
                continue;
            }
            int[] sz = {1, 1, 4, 64, 0, -2};
            Item it = r.nextInt(60) == 0 ? null : POOL[r.nextInt(POOL.length)];
            p.field_71071_by.slots[i] = new ItemStack(it, sz[r.nextInt(r.nextInt(8) == 0 ? sz.length : 4)], r.nextInt(3));
        }
        return p;
    }

    // ------------------------------------------------------------------ bancada (indicacao, nao medicao)

    /**
     * Indicacao de custo (NAO e medicao de servidor: stubs sem o delegate do Forge, sem JIT de
     * producao): 7.400 tarefas de 1-2 itens que nenhum inventario tem, 34 espacos ocupados; ns por
     * tarefa do laco original e do laco com ganchos (modo ligado).
     */
    public static String bancada() throws Exception {
        ThreadServidor.definir(Thread.currentThread());
        final Mundo.Montador mm = new Mundo.Montador() {
            public void montar(Mundo m) throws Exception {
                Random r = new Random(42);
                Item[] muitos = new Item[400];
                for (int i = 0; i < muitos.length; i++) {
                    muitos[i] = new Item("b" + i, false);
                }
                EntityPlayer p = m.jogador();
                for (int i = 0; i < 34; i++) {
                    p.field_71071_by.slots[i] = new ItemStack(muitos[i], 1 + r.nextInt(64), 0);
                }
                UUID u = new UUID(9, 9);
                m.info(p, u, Collections.singletonList(p), Collections.singletonList(u));
                for (int k = 0; k < 7400; k++) {
                    int ni = 1 + r.nextInt(2);
                    BigItemStack[] it = new BigItemStack[ni];
                    for (int i = 0; i < ni; i++) {
                        it[i] = Mundo.exige(muitos[40 + r.nextInt(360)], 1 + r.nextInt(16), 0);
                    }
                    m.tarefa(new TaskRetrieval(), it);
                }
            }
        };
        Mundo a = Mundo.novo(mm);
        Mundo b = Mundo.novo(mm);
        BetterQuesting.modo = BetterQuesting.LIGADO;
        for (int w = 0; w < 30; w++) {
            a.original();
            b.comGanchos();
        }
        long ta = Long.MAX_VALUE;
        long tb = Long.MAX_VALUE;
        for (int w = 0; w < 30; w++) {
            long t0 = System.nanoTime();
            a.original();
            long t1 = System.nanoTime();
            b.comGanchos();
            long t2 = System.nanoTime();
            ta = Math.min(ta, t1 - t0);
            tb = Math.min(tb, t2 - t1);
        }
        return "bancada (indicacao com stubs, melhor de 30): laco original " + (ta / 7400) + " ns/tarefa, com o filtro "
                + (tb / 7400) + " ns/tarefa (" + (100 * tb / Math.max(1, ta)) + "% do original); mesmos resultados: "
                + a.impressao().equals(b.impressao());
    }
}
