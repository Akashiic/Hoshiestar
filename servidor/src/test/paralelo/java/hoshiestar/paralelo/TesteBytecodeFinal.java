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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Teste de bytecode nas CLASSES FINAIS (depois de todos os transformadores e mixins; dump de um boot do modpack com
 * {@code -Dlegacy.debugClassLoadingSave=true}). Plano do Degrau B, secao 2 (regras de bytecode) e passo 0, secoes 7 e 8:
 * <ol>
 * <li>{@code World.updateEntities}: (a) o tile no slot 9 (nossa linha le {@code aload 9}; Opis tambem); (b) uma so chamada
 * de tick ({@code TileEntity.updateEntity} ou a troca do firewall, {@code WorldMutationGuard.runTileEntityUpdate}); (c)
 * nenhum {@code isInvalid}, {@code canTileEntityTick} nem {@code blockExists} entre o comeco do laco e o
 * {@code TileUpdateEvent.fire} do DragonAPI; os ganchos do paralelo nos lugares certos.</li>
 * <li>Lista permitida por metodo do caminho de cada classe liberada (arquivo {@code bytecode-permitido.txt}, tirado do dump
 * do passo 2 e revisado): chamada que nao esta na lista (e nao e do {@code hoshiestar}) = injecao nova sem veredito = a
 * classe sai ate nova revisao. Metodos mesclados por mixin (nome com '$') sao seguidos, com o prefixo gerado
 * ({@code redirect$zpb000$modid$}) tirado.</li>
 * </ol>
 * Uso: {@code TesteBytecodeFinal <pasta loader/ do dump> <bytecode-permitido.txt> [--gerar]} ({@code --gerar} imprime a
 * lista do dump dado, para revisao, e sai).
 */
public final class TesteBytecodeFinal {

    /** Metodos do caminho: classe, nome SRG, descritor, rotulo, classes liberadas que dependem dele. */
    static final String[][] CAMINHO = {
        {"net/minecraft/world/World", "func_147439_a", "(III)Lnet/minecraft/block/Block;", "World.getBlock", "mesa,bau,bau_ender"},
        {"net/minecraft/world/World", "func_147438_o", "(III)Lnet/minecraft/tileentity/TileEntity;", "World.getTileEntity", "mesa,bau,bau_ender"},
        {"net/minecraft/world/World", "func_72964_e", "(II)Lnet/minecraft/world/chunk/Chunk;", "World.getChunkFromChunkCoords", "mesa,bau,bau_ender"},
        {"net/minecraft/world/chunk/Chunk", "func_150810_a", "(III)Lnet/minecraft/block/Block;", "Chunk.getBlock", "mesa,bau,bau_ender"},
        {"net/minecraft/world/chunk/Chunk", "func_150806_e", "(III)Lnet/minecraft/tileentity/TileEntity;", "Chunk.func_150806_e", "mesa,bau,bau_ender"},
        {"net/minecraft/world/chunk/storage/ExtendedBlockStorage", "func_150819_a", "(III)Lnet/minecraft/block/Block;", "ExtendedBlockStorage.getBlockByExtId", "mesa,bau,bau_ender"},
        {"net/minecraft/world/gen/ChunkProviderServer", "func_73154_d", "(II)Lnet/minecraft/world/chunk/Chunk;", "CPS.provideChunk", "mesa,bau,bau_ender"},
        {"net/minecraft/world/gen/ChunkProviderServer", "func_73149_a", "(II)Z", "CPS.chunkExists", "mesa,bau,bau_ender"},
        {"net/minecraft/tileentity/TileEntity", "func_145838_q", "()Lnet/minecraft/block/Block;", "TileEntity.getBlockType", "mesa,bau,bau_ender"},
        {"net/minecraft/world/World", "func_72977_a", "(DDDD)Lnet/minecraft/entity/player/EntityPlayer;", "World.getClosestPlayer", "mesa"},
        {"net/minecraft/entity/Entity", "func_70092_e", "(DDD)D", "Entity.getDistanceSq", "mesa"},
        {"net/minecraft/tileentity/TileEntityEnchantmentTable", "func_145845_h", "()V", "TileEntityEnchantmentTable.updateEntity", "mesa"},
        {"net/minecraft/tileentity/TileEntityChest", "func_145845_h", "()V", "TileEntityChest.updateEntity", "bau"},
        {"net/minecraft/tileentity/TileEntityChest", "func_145979_i", "()V", "TileEntityChest.checkForAdjacentChests", "bau"},
        {"net/minecraft/tileentity/TileEntityChest", "func_145977_a", "(III)Z", "TileEntityChest.func_145977_a", "bau"},
        {"net/minecraft/tileentity/TileEntityChest", "func_145978_a", "(Lnet/minecraft/tileentity/TileEntityChest;I)V", "TileEntityChest.func_145978_a", "bau"},
        {"net/minecraft/tileentity/TileEntityEnderChest", "func_145845_h", "()V", "TileEntityEnderChest.updateEntity", "bau_ender"},
        {"net/minecraft/world/WorldServer", "func_147452_c", "(IIILnet/minecraft/block/Block;II)V", "WorldServer.addBlockEvent", "bau_ender"},
    };

    static final String TE = "net/minecraft/tileentity/TileEntity";
    static final String UPDATE_ENTITY = "func_145845_h", IS_INVALID = "func_145837_r", BLOCK_EXISTS = "func_72899_e";
    static final String GP = "hoshiestar/paralelo/GanchosParalelo";

    private static Verifica v;

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        File lista = new File(args[1]);
        boolean gerar = args.length > 2 && args[2].equals("--gerar");
        if (gerar) {
            for (String[] c : CAMINHO) {
                for (String s : alvos(dir, c[0], c[1], c[2])) {
                    System.out.println(c[3] + "\t" + s);
                }
            }
            return;
        }
        v = new Verifica("TesteBytecodeFinal (" + dir + ")");
        try {
            updateEntities(dir);
        } catch (Throwable t) {
            v.erro("excecao em updateEntities", t);
        }
        try {
            permitidos(dir, lista);
        } catch (Throwable t) {
            v.erro("excecao na lista permitida", t);
        }
        v.fim();
    }

    static ClassNode ler(File dir, String nome) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(new File(dir, nome + ".class").toPath())).accept(cn, 0);
        return cn;
    }

    static MethodNode metodo(ClassNode cn, String nome, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(nome) && (desc == null || m.desc.equals(desc))) {
                return m;
            }
        }
        return null;
    }

    static List<AbstractInsnNode> reais(MethodNode m) {
        List<AbstractInsnNode> r = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i.getOpcode() >= 0) {
                r.add(i);
            }
        }
        return r;
    }

    static boolean chama(AbstractInsnNode i, String owner, String nome) {
        return i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(owner) && ((MethodInsnNode) i).name.equals(nome);
    }

    static int achar(List<AbstractInsnNode> l, int de, String owner, String nome) {
        for (int k = Math.max(0, de); k < l.size(); k++) {
            if (chama(l.get(k), owner, nome)) {
                return k;
            }
        }
        return -1;
    }

    static int contar(List<AbstractInsnNode> l, String owner, String nome) {
        int n = 0;
        for (AbstractInsnNode i : l) {
            if (chama(i, owner, nome)) {
                n++;
            }
        }
        return n;
    }

    static void updateEntities(File dir) throws Exception {
        ClassNode w = ler(dir, "net/minecraft/world/World");
        MethodNode ue = metodo(w, "func_72939_s", "()V");
        v.sim(ue != null, "World.updateEntities (func_72939_s) existe na classe final");
        List<AbstractInsnNode> l = reais(ue);
        int abrir = achar(l, 0, GP, "abrir");
        int desp = achar(l, 0, GP, "despachar");
        int fimLaco = achar(l, 0, GP, "fimLaco");
        v.sim(abrir >= 0 && desp > abrir && fimLaco > desp, "ganchos do paralelo presentes e em ordem: abrir < despachar < fimLaco");
        v.igual(1, contar(l, GP, "abrir"), "um abrir");
        v.igual(1, contar(l, GP, "despachar"), "um despachar");
        v.igual(2, contar(l, GP, "fimTile"), "dois fimTile (caminho normal e catch)");
        v.igual(1, contar(l, GP, "fimLaco"), "um fimLaco");
        // (a) slot 9: o tile que vai para o despachar vem de aload 9; o Opis tambem le aload 9
        AbstractInsnNode antes = desp > 0 ? l.get(desp - 1) : null;
        v.sim(antes instanceof VarInsnNode && antes.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) antes).var == 9,
                "(a) despachar recebe o tile de aload 9 (o slot do tile continua 9)");
        int astore9 = -1;
        for (int k = 0; k < desp; k++) {
            if (l.get(k).getOpcode() == Opcodes.ASTORE && ((VarInsnNode) l.get(k)).var == 9) {
                astore9 = k; // o ultimo antes do despachar (o slot 9 tambem e usado antes, no laco de entidades)
            }
        }
        v.sim(astore9 > abrir && astore9 < desp, "(a) o ultimo astore 9 antes do despachar (o tile da vez) fica entre abrir e despachar");
        // (b) uma chamada de tick
        int upd = contar(l, TE, UPDATE_ENTITY);
        int fw = contar(l, "com/akashic/worldfirewall/WorldMutationGuard", "runTileEntityUpdate");
        v.igual(1, upd + fw, "(b) uma so chamada de tick no metodo (updateEntity " + upd + " + firewall " + fw + ")");
        v.sim(fw == 1 && upd == 0, "(b) com o firewall da copia: a chamada e WorldMutationGuard.runTileEntityUpdate (portao do firewall ativo)");
        int tick = fw == 1 ? achar(l, 0, "com/akashic/worldfirewall/WorldMutationGuard", "runTileEntityUpdate") : achar(l, 0, TE, UPDATE_ENTITY);
        v.sim(tick > desp, "o tick vem depois do despachar");
        int fimTile1 = achar(l, tick, GP, "fimTile");
        v.sim(fimTile1 > tick, "fimTile depois do tick");
        // Opis: start/stop com aload 9 em volta do tick
        int start = -1, stop = -1;
        for (int k = Math.max(0, desp); k < l.size(); k++) {
            if (chama(l.get(k), "mcp/mobius/mobiuscore/profiler/ProfilerSection", "start") && k < tick) {
                start = k;
            }
            if (chama(l.get(k), "mcp/mobius/mobiuscore/profiler/ProfilerSection", "stop") && k > tick && stop < 0) {
                stop = k;
            }
        }
        v.sim(start > desp && start < tick && stop > tick, "Opis: ProfilerSection.start antes e stop depois do tick");
        v.sim(start > 0 && l.get(start - 1) instanceof VarInsnNode && ((VarInsnNode) l.get(start - 1)).var == 9, "Opis le o tile de aload 9");
        // (c) janela do DragonAPI
        int marca = -1;
        for (int k = Math.max(0, abrir); k < l.size(); k++) {
            if (chama(l.get(k), "hoshiestar/vigia/Marcas", "tile")) {
                marca = k;
                break;
            }
        }
        int fire = achar(l, marca, "Reika/DragonAPI/Instantiable/Event/TileUpdateEvent", "fire");
        v.sim(marca > abrir && fire > marca && fire < desp, "TileUpdateEvent.fire (DragonAPI) entre o comeco do laco e o despachar");
        boolean limpo = true;
        for (int k = Math.max(0, marca); k < fire; k++) {
            AbstractInsnNode i = l.get(k);
            if (chama(i, TE, IS_INVALID) || chama(i, "net/minecraft/world/World", BLOCK_EXISTS)
                    || (i instanceof MethodInsnNode && ((MethodInsnNode) i).name.equals("canTileEntityTick"))) {
                limpo = false;
            }
        }
        v.sim(limpo, "(c) nenhum isInvalid, blockExists nem canTileEntityTick entre o comeco do laco e o fire do DragonAPI");
        int orc = achar(l, 0, "hoshiestar/orcamento/GanchosOrcamento", "tile");
        v.sim(desp < orc && orc < tick, "despachar uma linha antes do gancho do orcamento, e os dois antes do tick");
        // cada chamada ao paralelo atras de Paralelo.LIGADO
        int semTeste = 0;
        for (int k = 0; k < l.size(); k++) {
            if (l.get(k) instanceof MethodInsnNode && ((MethodInsnNode) l.get(k)).owner.startsWith("hoshiestar/paralelo/")) {
                boolean achou = false;
                for (int j = k - 1; j >= Math.max(0, k - 6); j--) {
                    AbstractInsnNode a = l.get(j);
                    if (a.getOpcode() == Opcodes.GETSTATIC && ((FieldInsnNode) a).owner.equals("hoshiestar/paralelo/Paralelo")
                            && ((FieldInsnNode) a).name.equals("LIGADO") && l.get(j + 1).getOpcode() == Opcodes.IFEQ) {
                        achou = true;
                    }
                }
                if (!achou) {
                    semTeste++;
                }
            }
        }
        v.igual(0, semTeste, "toda chamada ao paralelo em updateEntities vem logo depois de Paralelo.LIGADO + IFEQ");
    }

    /** Os alvos (chamadas e escritas) do metodo, seguindo os mesclados da mesma classe ate 3 niveis. */
    static Set<String> alvos(File dir, String classe, String nome, String desc) throws Exception {
        ClassNode cn = ler(dir, classe);
        MethodNode m = metodo(cn, nome, desc);
        Set<String> r = new TreeSet<String>();
        if (m == null) {
            r.add("(METODO AUSENTE)");
            return r;
        }
        Set<String> vistos = new LinkedHashSet<String>();
        List<MethodNode> fila = new ArrayList<MethodNode>();
        List<Integer> niveis = new ArrayList<Integer>();
        fila.add(m);
        niveis.add(0);
        while (!fila.isEmpty()) {
            MethodNode atual = fila.remove(0);
            int nivel = niveis.remove(0);
            if (!vistos.add(atual.name + atual.desc)) {
                continue;
            }
            for (AbstractInsnNode i : atual.instructions.toArray()) {
                String owner = null, n = null;
                int op = i.getOpcode();
                if (i instanceof MethodInsnNode) {
                    owner = ((MethodInsnNode) i).owner;
                    n = ((MethodInsnNode) i).name;
                } else if (i instanceof FieldInsnNode && (op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC || op == Opcodes.PUTFIELD)) {
                    owner = ((FieldInsnNode) i).owner;
                    n = (op == Opcodes.PUTFIELD ? "putfield:" : op == Opcodes.PUTSTATIC ? "putstatic:" : "getstatic:") + ((FieldInsnNode) i).name;
                } else {
                    continue;
                }
                if (owner.startsWith("hoshiestar/")) {
                    continue; // nossos ganchos: permitidos (revisados no fork)
                }
                boolean mesclado = owner.equals(classe) && i instanceof MethodInsnNode && n.contains("$");
                r.add((owner.equals(classe) ? "SELF" : owner) + "." + normal(n));
                if (mesclado && nivel < 3) {
                    for (MethodNode m2 : cn.methods) {
                        if (m2.name.equals(n)) {
                            fila.add(m2);
                            niveis.add(nivel + 1);
                        }
                    }
                }
            }
        }
        return r;
    }

    /** Tira o prefixo gerado pelo Mixin ({@code redirect$zpb000$hodgepodge_2$}): ele muda de boot para boot. */
    static String normal(String n) {
        return n.replaceFirst("^(putfield:|putstatic:|getstatic:)?[A-Za-z]+\\$[a-z]{3}\\d{3}\\$[^$]+\\$", "$1");
    }

    static void permitidos(File dir, File lista) throws Exception {
        Map<String, Set<String>> perm = new LinkedHashMap<String, Set<String>>();
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(lista), "UTF-8"));
        try {
            String s;
            while ((s = br.readLine()) != null) {
                if (s.trim().isEmpty() || s.startsWith("#")) {
                    continue;
                }
                String[] p = s.split("\t");
                Set<String> set = perm.get(p[0]);
                if (set == null) {
                    set = new TreeSet<String>();
                    perm.put(p[0], set);
                }
                set.add(p[1]);
            }
        } finally {
            br.close();
        }
        Map<String, List<String>> foraPorClasse = new LinkedHashMap<String, List<String>>();
        for (String c : new String[] {"mesa", "bau", "bau_ender"}) {
            foraPorClasse.put(c, new ArrayList<String>());
        }
        for (String[] c : CAMINHO) {
            Set<String> a = alvos(dir, c[0], c[1], c[2]);
            Set<String> p = perm.get(c[3]);
            List<String> fora = new ArrayList<String>();
            for (String x : a) {
                if (p == null || !p.contains(x)) {
                    fora.add(x);
                }
            }
            v.sim(fora.isEmpty(), c[3] + ": " + a.size() + " alvos, todos na lista permitida" + (fora.isEmpty() ? "" : "; FORA: " + fora));
            for (String cls : c[4].split(",")) {
                foraPorClasse.get(cls).addAll(fora);
            }
        }
        for (Map.Entry<String, List<String>> e : foraPorClasse.entrySet()) {
            System.out.println("  classe " + e.getKey() + ": " + (e.getValue().isEmpty() ? "liberada pelo bytecode final"
                    : "FORA ate nova revisao (" + e.getValue().size() + " chamada(s) nova(s))"));
        }
        // o proprio teste pega chamada nova: tirar um alvo da lista faz o metodo falhar
        Set<String> a0 = alvos(dir, CAMINHO[0][0], CAMINHO[0][1], CAMINHO[0][2]);
        Set<String> p0 = new TreeSet<String>(perm.get(CAMINHO[0][3]));
        String tirado = null;
        for (String x : a0) {
            if (!x.startsWith("java/")) {
                tirado = x;
                break;
            }
        }
        p0.remove(tirado);
        boolean pegou = !p0.containsAll(a0);
        v.sim(tirado != null && pegou, "dente do teste: sem '" + tirado + "' na lista, World.getBlock acusa chamada nova");
    }
}
