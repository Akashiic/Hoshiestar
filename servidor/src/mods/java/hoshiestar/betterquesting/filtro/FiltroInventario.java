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
package hoshiestar.betterquesting.filtro;

import betterquesting.api.utils.BigItemStack;
import betterquesting.api2.utils.OreIngredient;
import betterquesting.api2.utils.ParticipantInfo;
import bq_standard.tasks.ITaskInventory;
import bq_standard.tasks.TaskRetrieval;
import bq_standard.tasks.base.TaskProgressableBase;
import hoshiestar.api.Limitador;
import hoshiestar.api.Log;
import hoshiestar.betterquesting.BetterQuesting;
import hoshiestar.embutidos.CampoPrivado;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.lang.invoke.MethodHandle;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A logica do filtro (decisao, cache do tick e comparacao do modo verificar). Os ganchos
 * ({@link GanchoBQ}) so delegam para ca e cuidam das excecoes; os testes isolados chamam os MESMOS
 * ganchos, com as classes reais do BetterQuesting.
 *
 * <p><b>A regra</b> (desenho 1.D, secao 5.1, com as correcoes C1 a C5 da conferencia). A chamada
 * {@code task.onInventoryChange(quest, pInfo)} so e pulada quando, nesta ordem:
 * <ol>
 * <li>a classe da tarefa e EXATAMENTE {@code TaskRetrieval} (subclasses e outros tipos: original);</li>
 * <li>e (a) {@code consume && !autoConsume}: onInventoryChange nao faz nada nesse caso; ou (b) a
 * tarefa ja esta completa para {@code pInfo.UUID}: detect sai na primeira linha; ou</li>
 * <li>(c) TODAS estas valem: a lista de itens exigidos nao e vazia; cada item exigido e nao nulo, tem
 * quantidade &gt; 0, pilha base nao nula e Item nao nulo; nenhum desses Items (comparados por
 * IDENTIDADE, C1) aparece em pilha com quantidade &gt; 0 nos inventarios de {@code ACTIVE_PLAYERS} e
 * de {@code PLAYER}; a lista VIVA do ore dictionary de cada item ({@code OreIngredient.ores}, a mesma
 * que o BQ consulta) esta vazia; e, para cada uuid de {@code ALL_UUIDS}, o progresso salvo (lido sem
 * alocar, C4: ausente ou de tamanho errado = zeros, que e o que {@code getUsersProgress} devolveria)
 * nao tem nada que o Detector zeraria (sem groupDetect: cada valor e 0 ou &gt;= exigido; com
 * groupDetect: todos 0; so quando nao ha consume) e nao satisfaz a conclusao (todos &gt;= exigido, ou
 * algum, com requireOnlyOneItem).</li>
 * </ol>
 * Jogador ou inventario nulo em qualquer lugar do grupo: nao pula nada para aquele
 * {@code ParticipantInfo} (C5). Uuid nulo ou progresso de tipo inesperado: original.
 *
 * <p><b>Por que pular e exato.</b> Nenhuma pilha passa no StackMatch (exige o mesmo Item) nem no
 * OreDictionaryMatch (lista vazia), entao Detector.run nao muda nada; o construtor do Detector nao
 * tem parcial a zerar (e o preenchimento com zero do groupDetect cai em arrays ja zerados); assim
 * {@code updated} fica false, setBulkProgress nao roda, e checkAndComplete ve arrays que nao
 * satisfazem a conclusao: nada de setComplete, markDirtyParty ou evento. getBulkProgress e
 * isComplete so leem. O unico efeito do original seria tempo de CPU e lixo.
 *
 * <p><b>Cache do tick (C2).</b> O conjunto de Items presentes e montado na primeira decisao para um
 * {@code ParticipantInfo} (o BQ cria um novo por jogador por tick; a chave e por {@code ==}) e
 * descartado depois de qualquer chamada ao original que possa mexer em inventario (toda chamada
 * que nao seja um TaskRetrieval exato sem consume: TaskFluid troca recipientes, consume tira itens).
 * Um conjunto velho so pode ter Items A MAIS (mais chamadas ao original, nunca um pulo errado). O
 * cache e limpo na entrada do onServerTick e no fim do laco: nenhuma referencia a jogador passa de
 * um tick para outro. Tudo roda na thread do servidor, dentro do synchronized do proprio BQ.
 *
 * <p>Sem alocacao no caminho de decisao: o conjunto e um IdentityHashMap reaproveitado, as listas
 * sao percorridas por indice e o progresso e lido direto do TreeMap ({@link CampoPrivado}).
 */
public final class FiltroInventario {

    /** Leitura de OreIngredient.ores (private final List; a lista viva do Forge). null = regra 3 desligada. */
    static final MethodHandle ORES;
    /** Leitura de TaskProgressableBase.userProgress (protected final TreeMap). null = regra 3 desligada. */
    static final MethodHandle PROGRESSO;

    static {
        MethodHandle o = null;
        MethodHandle p = null;
        try {
            o = CampoPrivado.leitorExato(OreIngredient.class, "ores", List.class);
            p = CampoPrivado.leitorExato(TaskProgressableBase.class, "userProgress", TreeMap.class);
        } catch (Throwable t) {
            o = null;
            p = null;
            BetterQuesting.regra3Desligada = String.valueOf(t);
            Log.de(BetterQuesting.ID).a("nao consegui ler os campos privados do BQ (" + t + "); so as regras 'consume' e"
                    + " 'completa' ficam valendo (o resto vai sempre para o original)");
        }
        ORES = o;
        PROGRESSO = p;
    }

    // ---------------------------------------------------------------- cache do tick (so thread do servidor)

    /** Items com pilha de quantidade &gt; 0 nos inventarios varridos (chave por identidade, C1). */
    private static final IdentityHashMap<Item, Boolean> presentes = new IdentityHashMap<Item, Boolean>(512);
    /** O ParticipantInfo para o qual {@link #presentes} foi montado (chave por ==, C2), ou null. */
    private static ParticipantInfo ultimoInfo;
    /** false = {@link #presentes} precisa ser montado de novo. */
    private static boolean presentesValidos;
    /** true = algum jogador/inventario do grupo e nulo: nao pular nada para {@link #ultimoInfo} (C5). */
    private static boolean inventarioNulo;

    private static long t0;
    private static boolean houveChamada;
    private static long ultimoRelatorio;

    private static final Limitador LIM_DIVERGENCIA = new Limitador(10, 60000L);

    private FiltroInventario() {
    }

    // ---------------------------------------------------------------- ciclo do tick

    /** Entrada de onServerTick (fases START e END): esquece o cache. */
    static void inicioDoTick() {
        esquecer();
        houveChamada = false;
        t0 = System.nanoTime();
    }

    /** Fim do laco (logo depois do playerInventoryUpdates.clear() original): tempo, relatorio, esquece o cache. */
    static void fimDoLaco() {
        esquecer();
        long agora = System.nanoTime();
        if (houveChamada) {
            houveChamada = false;
            BetterQuesting.contagem[BetterQuesting.TICKS]++;
            BetterQuesting.contagem[BetterQuesting.NANOS] += agora - t0;
        }
        int rel = BetterQuesting.relatorioSegundos;
        if (rel > 0) {
            if (ultimoRelatorio == 0L) {
                ultimoRelatorio = agora;
            } else if (agora - ultimoRelatorio >= rel * 1000000000L) {
                ultimoRelatorio = agora;
                Log.de(BetterQuesting.ID).i("relatorio: " + BetterQuesting.linhaDeEstado());
            }
        }
    }

    /** Solta todas as referencias do cache (jogadores, ParticipantInfo). */
    static void esquecer() {
        ultimoInfo = null;
        presentesValidos = false;
        inventarioNulo = false;
        presentes.clear();
    }

    // ---------------------------------------------------------------- decisao

    /**
     * Decide uma chamada. Devolve um indice de {@link BetterQuesting#contagem}: PULA_* (0 a 2) =
     * a chamada original seria um "nao faz nada" comprovado; PASSA_* = chamar o original. So le
     * (nunca escreve em tarefa, inventario ou BQ). Excecao = erro nosso (quem chama trata).
     */
    static int decidir(ITaskInventory task, ParticipantInfo pInfo) throws Throwable {
        houveChamada = true;
        if (task.getClass() != TaskRetrieval.class) {
            return BetterQuesting.PASSA_CLASSE;
        }
        TaskRetrieval t = (TaskRetrieval) task;
        if (t.consume && !t.autoConsume) {
            return BetterQuesting.PULA_CONSUMO;
        }
        UUID eu = pInfo.UUID;
        if (eu == null) {
            return BetterQuesting.PASSA_DADO_ESTRANHO;
        }
        if (t.isComplete(eu)) {
            return BetterQuesting.PULA_COMPLETA;
        }
        if (ORES == null || PROGRESSO == null) {
            return BetterQuesting.PASSA_SEM_REGRA3;
        }
        List<BigItemStack> exigidos = t.requiredItems;
        int n = exigidos.size();
        if (n == 0) {
            return BetterQuesting.PASSA_ITEM_INVALIDO;
        }
        if (pInfo != ultimoInfo || !presentesValidos) {
            montarPresentes(pInfo);
        }
        if (inventarioNulo) {
            return BetterQuesting.PASSA_INVENTARIO;
        }
        for (int i = 0; i < n; i++) {
            BigItemStack r = exigidos.get(i);
            if (r == null || r.stackSize <= 0) {
                return BetterQuesting.PASSA_ITEM_INVALIDO;
            }
            ItemStack base = r.getBaseStack();
            if (base == null) {
                return BetterQuesting.PASSA_ITEM_INVALIDO;
            }
            Item item = base.getItem();
            if (item == null) {
                return BetterQuesting.PASSA_ITEM_INVALIDO;
            }
            if (presentes.containsKey(item)) {
                return BetterQuesting.PASSA_PRESENTE;
            }
            OreIngredient ore = r.getOreIngredient();
            if (ore == null) {
                return BetterQuesting.PASSA_ORE;
            }
            List<?> lista = (List<?>) ORES.invokeExact(ore);
            if (lista == null || !lista.isEmpty()) {
                return BetterQuesting.PASSA_ORE;
            }
        }
        List<UUID> todos = pInfo.ALL_UUIDS;
        if (todos == null) {
            return BetterQuesting.PASSA_DADO_ESTRANHO;
        }
        TreeMap<?, ?> progresso = (TreeMap<?, ?>) PROGRESSO.invokeExact((TaskProgressableBase) t);
        if (progresso == null) {
            return BetterQuesting.PASSA_DADO_ESTRANHO;
        }
        boolean consome = t.consume;
        boolean grupo = t.groupDetect;
        boolean umSo = t.requireOnlyOneItem;
        for (int k = 0, m = todos.size(); k < m; k++) {
            UUID u = todos.get(k);
            if (u == null) {
                return BetterQuesting.PASSA_DADO_ESTRANHO;
            }
            Object o = progresso.get(u);
            if (o == null) {
                continue; // getUsersProgress daria zeros novos: nada a zerar, nada satisfeito (exigido > 0)
            }
            if (!(o instanceof int[])) {
                return BetterQuesting.PASSA_DADO_ESTRANHO;
            }
            int[] p = (int[]) o;
            if (p.length != n) {
                continue; // idem: getUsersProgress devolveria um array novo de zeros
            }
            boolean algum = false;
            boolean todosOk = true;
            for (int i = 0; i < n; i++) {
                int v = p[i];
                int exigido = exigidos.get(i).stackSize;
                if (!consome) {
                    if (grupo) {
                        if (v != 0) {
                            return BetterQuesting.PASSA_PARCIAL;
                        }
                    } else if (v != 0 && v < exigido) {
                        return BetterQuesting.PASSA_PARCIAL;
                    }
                }
                boolean ok = v >= exigido;
                algum |= ok;
                todosOk &= ok;
            }
            if (umSo ? algum : todosOk) {
                return BetterQuesting.PASSA_SATISFEITA;
            }
        }
        return BetterQuesting.PULA_SEM_ITEM;
    }

    /**
     * Depois de uma chamada ao original: se ela pode ter mexido em inventario (toda chamada que nao
     * seja um TaskRetrieval exato sem consume), o conjunto de presentes sera montado de novo.
     */
    static void depoisDoOriginal(ITaskInventory task) {
        if (task == null || task.getClass() != TaskRetrieval.class || ((TaskRetrieval) task).consume) {
            presentesValidos = false;
        }
    }

    /** Monta {@link #presentes} com os inventarios de ACTIVE_PLAYERS e de PLAYER (o PLAYER pode ser um objeto velho, de antes de um respawn). */
    private static void montarPresentes(ParticipantInfo pInfo) {
        presentes.clear();
        ultimoInfo = pInfo;
        presentesValidos = true;
        inventarioNulo = false;
        BetterQuesting.contagem[BetterQuesting.RECONSTRUCOES]++;
        List<EntityPlayer> ativos = pInfo.ACTIVE_PLAYERS;
        EntityPlayer eu = pInfo.PLAYER;
        if (ativos == null) {
            inventarioNulo = true;
            return;
        }
        boolean euNaLista = false;
        for (int k = 0, m = ativos.size(); k < m; k++) {
            EntityPlayer p = ativos.get(k);
            if (p == eu) {
                euNaLista = true;
            }
            if (!acrescentar(p)) {
                return;
            }
        }
        if (!euNaLista) {
            acrescentar(eu);
        }
    }

    /** Acrescenta os Items do inventario do jogador; false (e inventarioNulo) se jogador ou inventario e nulo. */
    private static boolean acrescentar(EntityPlayer p) {
        InventoryPlayer inv = p == null ? null : p.inventory;
        if (inv == null) {
            inventarioNulo = true;
            return false;
        }
        for (int i = 0, s = inv.getSizeInventory(); i < s; i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st != null && st.stackSize > 0) {
                presentes.put(st.getItem(), Boolean.TRUE);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- modo verificar

    /**
     * Foto do que o original poderia mudar numa chamada que o filtro pularia (TaskRetrieval exato, codigo
     * hash-travado): por uuid de ALL_UUIDS, "completa?" e o progresso salvo (objeto e conteudo); por
     * espaco dos inventarios de ACTIVE_PLAYERS e PLAYER, a pilha (objeto) e a quantidade. Todo caminho de
     * escrita do TaskRetrieval muda pelo menos uma dessas coisas (setComplete acrescenta um uuid;
     * setBulkProgress poe um array novo ou muda o conteudo; Detector escreve no array salvo; consume
     * tira itens; markDirtyParty so acontece junto com uma dessas). So o modo verificar usa; aloca.
     */
    static final class Foto {
        boolean[] completa;
        Object[] objeto;
        int[][] conteudo;
        Object[] pilhas;
        int[] quantidades;
        int nPilhas;
    }

    static Foto fotografar(ITaskInventory task, ParticipantInfo pInfo) {
        TaskRetrieval t = (TaskRetrieval) task;
        Foto f = new Foto();
        List<UUID> todos = pInfo.ALL_UUIDS;
        int m = todos == null ? 0 : todos.size();
        f.completa = new boolean[m];
        f.objeto = new Object[m];
        f.conteudo = new int[m][];
        Map<?, ?> progresso = lerProgresso(t);
        for (int k = 0; k < m; k++) {
            UUID u = todos.get(k);
            if (u == null) {
                continue;
            }
            f.completa[k] = t.isComplete(u);
            Object o = progresso.get(u);
            f.objeto[k] = o;
            f.conteudo[k] = o instanceof int[] ? ((int[]) o).clone() : null;
        }
        int total = 0;
        for (int k = 0, nj = jogadores(pInfo); k < nj; k++) {
            InventoryPlayer inv = inventario(pInfo, k);
            total += inv == null ? 0 : inv.getSizeInventory();
        }
        f.pilhas = new Object[total];
        f.quantidades = new int[total];
        int j = 0;
        for (int k = 0, nj = jogadores(pInfo); k < nj; k++) {
            InventoryPlayer inv = inventario(pInfo, k);
            for (int i = 0, s = inv == null ? 0 : inv.getSizeInventory(); i < s && j < total; i++, j++) {
                ItemStack st = inv.getStackInSlot(i);
                f.pilhas[j] = st;
                f.quantidades[j] = st == null ? 0 : st.stackSize;
            }
        }
        f.nPilhas = j;
        return f;
    }

    /** O que mudou desde a foto, ou null se nada mudou. */
    static String comparar(Foto f, ITaskInventory task, ParticipantInfo pInfo) {
        TaskRetrieval t = (TaskRetrieval) task;
        StringBuilder sb = null;
        List<UUID> todos = pInfo.ALL_UUIDS;
        Map<?, ?> progresso = lerProgresso(t);
        for (int k = 0; k < f.completa.length && todos != null && k < todos.size(); k++) {
            UUID u = todos.get(k);
            if (u == null) {
                continue;
            }
            if (t.isComplete(u) != f.completa[k]) {
                sb = mais(sb, "completa(" + u + ") " + f.completa[k] + " -> " + !f.completa[k]);
            }
            Object o = progresso.get(u);
            if (o != f.objeto[k]) {
                sb = mais(sb, "progresso(" + u + ") trocado: " + texto(f.objeto[k]) + " -> " + texto(o));
            } else if (o instanceof int[] && !java.util.Arrays.equals((int[]) o, f.conteudo[k])) {
                sb = mais(sb, "progresso(" + u + ") escrito: " + java.util.Arrays.toString(f.conteudo[k]) + " -> " + texto(o));
            }
        }
        int j = 0;
        for (int k = 0, nj = jogadores(pInfo); k < nj; k++) {
            InventoryPlayer inv = inventario(pInfo, k);
            for (int i = 0, s = inv == null ? 0 : inv.getSizeInventory(); i < s && j < f.nPilhas; i++, j++) {
                ItemStack st = inv.getStackInSlot(i);
                if (st != f.pilhas[j] || (st == null ? 0 : st.stackSize) != f.quantidades[j]) {
                    sb = mais(sb, "inventario: espaco " + i + " do jogador " + k + " mudou");
                }
            }
        }
        return sb == null ? null : sb.toString();
    }

    /** Quantos inventarios a foto olha: os de ACTIVE_PLAYERS mais o de PLAYER. */
    private static int jogadores(ParticipantInfo pInfo) {
        return (pInfo.ACTIVE_PLAYERS == null ? 0 : pInfo.ACTIVE_PLAYERS.size()) + 1;
    }

    /** Inventario numero k (ACTIVE_PLAYERS na ordem, depois PLAYER), ou null. */
    private static InventoryPlayer inventario(ParticipantInfo pInfo, int k) {
        List<EntityPlayer> ativos = pInfo.ACTIVE_PLAYERS;
        int na = ativos == null ? 0 : ativos.size();
        EntityPlayer p = k < na ? ativos.get(k) : pInfo.PLAYER;
        return p == null ? null : p.inventory;
    }

    /** Conta a comparacao e registra (com limitador) a divergencia, se houver. */
    static void registrarComparacao(String diferenca, int codigo, Map.Entry<?, ?> quest, ITaskInventory task) {
        BetterQuesting.contagem[BetterQuesting.COMPARADAS]++;
        if (diferenca == null) {
            return;
        }
        BetterQuesting.contagem[BetterQuesting.DIVERGENCIAS]++;
        if (LIM_DIVERGENCIA.permitir("divergencia")) {
            TaskRetrieval t = (TaskRetrieval) task;
            Log.de(BetterQuesting.ID).a("verificar: DIVERGENCIA (o filtro pularia, regra " + codigo + ", e o original mudou algo):"
                    + " quest " + (quest == null ? "?" : quest.getKey()) + ", consume=" + t.consume + " autoConsume=" + t.autoConsume
                    + " groupDetect=" + t.groupDetect + " umSo=" + t.requireOnlyOneItem + " itens=" + t.requiredItems.size()
                    + ": " + diferenca);
        }
    }

    private static Map<?, ?> lerProgresso(TaskRetrieval t) {
        if (PROGRESSO == null) {
            return java.util.Collections.emptyMap(); // regra 3 desligada: so 'consume' e 'completa' sao comparadas
        }
        try {
            return (TreeMap<?, ?>) PROGRESSO.invokeExact((TaskProgressableBase) t);
        } catch (Throwable e) {
            throw new IllegalStateException("leitura de userProgress falhou", e);
        }
    }

    private static StringBuilder mais(StringBuilder sb, String s) {
        if (sb == null) {
            return new StringBuilder(s);
        }
        return sb.append("; ").append(s);
    }

    private static String texto(Object o) {
        return o instanceof int[] ? java.util.Arrays.toString((int[]) o) : String.valueOf(o);
    }
}
