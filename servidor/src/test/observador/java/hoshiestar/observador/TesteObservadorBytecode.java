package hoshiestar.observador;

import hoshiestar.teste.Verifica;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Confere os patches do observador no bytecode REAL das classes do jogo (as que o build rapido gerou): cada chamada a
 * GanchosObservador esta atras de "if (Observador.ATIVO)" (um teste de campo estatico que pula so a chamada), so nos
 * metodos previstos, sem variavel local nova, sem mexer nos padroes que o Opis e o firewall procuram em
 * World.updateEntities, e que GanchosObservador so le o jogo (nenhum PUTFIELD em classe do Minecraft, nenhum dado de jogador).
 */
public final class TesteObservadorBytecode {

    private static final String GANCHOS = "hoshiestar/observador/GanchosObservador";
    private static final String OBS = "hoshiestar/observador/Observador";
    private static Verifica v;

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        v = new Verifica("TesteObservadorBytecode");
        ClassNode world = ler(dir, "net/minecraft/world/World.class");
        ClassNode ws = ler(dir, "net/minecraft/world/WorldServer.class");
        ClassNode chunk = ler(dir, "net/minecraft/world/chunk/Chunk.class");
        ClassNode cps = ler(dir, "net/minecraft/world/gen/ChunkProviderServer.class");
        ClassNode funil = ler(dir, "net/minecraft/tileentity/TileEntityHopper.class");
        ClassNode jogador = ler(dir, "net/minecraft/entity/player/EntityPlayerMP.class");
        ClassNode servidor = ler(dir, "net/minecraft/server/MinecraftServer.class");
        String blk = "Lnet/minecraft/block/Block;";
        String te = "Lnet/minecraft/tileentity/TileEntity;";
        String ent = "Lnet/minecraft/entity/Entity;";
        int total = 0;
        total += guardados(world, "getBlock", "(III)" + blk, 1);
        total += guardados(world, "setBlock", "(III" + blk + "II)Z", 1);
        total += guardados(world, "setBlockMetadataWithNotify", "(IIIII)Z", 1);
        total += guardados(world, "notifyBlockOfNeighborChange", "(III" + blk + ")V", 1);
        total += guardados(world, "addEntity", "(" + ent + "Lorg/bukkit/event/entity/CreatureSpawnEvent$SpawnReason;)Z", 1);
        total += guardados(world, "removeEntity", "(" + ent + ")V", 1);
        total += guardados(world, "getTileEntity", "(III)" + te, 1);
        total += guardados(world, "setTileEntity", "(III" + te + ")V", 1);
        total += guardados(world, "removeTileEntity", "(III)V", 1);
        total += guardados(world, "addTileEntity", "(" + te + ")V", 1);
        total += guardados(world, "updateEntities", "()V", 6);
        total += guardados(world, "updateEntityWithOptionalForce", "(" + ent + "Z)V", 4);
        total += guardados(ws, "scheduleBlockUpdateWithPriority", "(III" + blk + "II)V", 1);
        total += guardados(ws, "func_147446_b", "(III" + blk + "II)V", 1);
        total += guardados(ws, "addBlockEvent", "(III" + blk + "II)V", 1);
        total += guardados(ws, "tickUpdates", "(Z)Z", 3);
        total += guardados(ws, "func_147456_g", "()V", 2);
        total += guardados(ws, "func_147488_Z", "()V", 2);
        total += guardados(chunk, "func_150807_a", "(III" + blk + "I)Z", 1);
        total += guardados(cps, "originalLoadChunk", "(II)Lnet/minecraft/world/chunk/Chunk;", 1);
        total += guardados(funil, "func_145893_b", "(Lnet/minecraft/world/World;DDD)Lnet/minecraft/inventory/IInventory;", 1);
        total += guardados(jogador, "onUpdateEntity", "()V", 2);
        total += guardados(servidor, "tick", "()V", 4);
        total += guardados(servidor, "updateTimeLightAndEntities", "()V", 8);
        v.igual(47, total, "47 chamadas a GanchosObservador no total, todas atras de 'if (Observador.ATIVO)'");
        for (ClassNode cn : new ClassNode[] {world, ws, chunk, cps, funil, jogador, servidor}) {
            Set<String> previstos = new HashSet<String>(Arrays.asList(
                    "getBlock", "setBlock", "setBlockMetadataWithNotify", "notifyBlockOfNeighborChange", "addEntity",
                    "removeEntity", "getTileEntity", "setTileEntity", "removeTileEntity", "addTileEntity", "updateEntities",
                    "updateEntityWithOptionalForce", "scheduleBlockUpdateWithPriority", "func_147446_b", "addBlockEvent",
                    "tickUpdates", "func_147456_g", "func_147488_Z", "func_150807_a", "originalLoadChunk", "func_145893_b",
                    "onUpdateEntity", "tick", "updateTimeLightAndEntities"));
            for (MethodNode m : cn.methods) {
                int n = 0;
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(GANCHOS)) {
                        n++;
                    }
                }
                if (n > 0 && !previstos.contains(m.name)) {
                    v.sim(false, "chamada a GanchosObservador fora dos pontos previstos: " + cn.name + "." + m.name);
                }
            }
        }
        // a tile da vez nao fica na linha da chamada de update (o Opis casa [linha, ALOAD, chamada])
        MethodNode ue = metodo(world, "updateEntities", "()V");
        int linhaHook = -1;
        int linhaUpdate = -2;
        int linha = -1;
        for (AbstractInsnNode i : ue.instructions.toArray()) {
            if (i instanceof LineNumberNode) {
                linha = ((LineNumberNode) i).line;
            }
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(GANCHOS) && ((MethodInsnNode) i).name.equals("tile")) {
                linhaHook = linha;
            }
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals("net/minecraft/tileentity/TileEntity")
                    && ((MethodInsnNode) i).name.equals("updateEntity")) {
                linhaUpdate = linha;
            }
        }
        v.sim(linhaHook > 0 && linhaHook != linhaUpdate, "o gancho do tile esta numa linha propria, longe da linha do update (" + linhaHook + " x " + linhaUpdate + ")");
        padroesWorld(world);
        contextosFechados(world, ws);
        lista(world);
        servidor(servidor);
        ganchos(dir);
        v.fim();
    }

    private static ClassNode ler(File dir, String nome) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(new File(dir, nome).toPath())).accept(cn, 0); // com quadros, como o Opis
        return cn;
    }

    private static MethodNode metodo(ClassNode cn, String nome, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(nome) && m.desc.equals(desc)) {
                return m;
            }
        }
        throw new IllegalStateException("metodo nao achado: " + cn.name + "." + nome + desc);
    }

    private static MethodNode metodoPorNome(ClassNode cn, String nome) {
        MethodNode achado = null;
        for (MethodNode m : cn.methods) {
            if (m.name.equals(nome)) {
                if (achado != null) {
                    throw new IllegalStateException("mais de um metodo " + nome + " em " + cn.name);
                }
                achado = m;
            }
        }
        if (achado == null) {
            throw new IllegalStateException("metodo nao achado: " + cn.name + "." + nome);
        }
        return achado;
    }

    private static AbstractInsnNode real(AbstractInsnNode i, boolean frente) {
        AbstractInsnNode n = frente ? i.getNext() : i.getPrevious();
        while (n != null && n.getOpcode() < 0) {
            n = frente ? n.getNext() : n.getPrevious();
        }
        return n;
    }

    /** Cada chamada a GanchosObservador: antes dela (pulando os argumentos) IFEQ precedido de GETSTATIC Observador.ATIVO. */
    private static final String LISTA = "hoshiestar/observador/ListaObservada";

    /**
     * Prototipo da lista de tiles: as marcas do World (mundo/fimMundo) atras de 'if (Observador.ATIVO)' que pula so a
     * chamada; a troca no construtor e o embrulhar atras de 'if (Observador.LISTA)' que pula so a atribuicao; nada mais
     * da ListaObservada no World.
     */
    private static void lista(ClassNode world) {
        String[][] marcas = {{"updateEntities", "()V", "2"}, {"func_147448_a", "(Ljava/util/Collection;)V", "1"},
            {"setTileEntity", "(IIILnet/minecraft/tileentity/TileEntity;)V", "1"},
            {"addTileEntity", "(Lnet/minecraft/tileentity/TileEntity;)V", "1"}};
        int totalMarcas = 0;
        for (String[] mk : marcas) {
            MethodNode m = metodo(world, mk[0], mk[1]);
            int ini = 0, fim = 0, ok = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof MethodInsnNode) || !((MethodInsnNode) i).owner.equals(LISTA)) {
                    continue;
                }
                String nome = ((MethodInsnNode) i).name;
                if (nome.equals("mundo")) {
                    ini++;
                } else if (nome.equals("fimMundo")) {
                    fim++;
                } else {
                    continue;
                }
                AbstractInsnNode ifeq = real(i, false);
                AbstractInsnNode g = ifeq != null ? real(ifeq, false) : null;
                if (ifeq != null && ifeq.getOpcode() == Opcodes.IFEQ && g instanceof FieldInsnNode
                        && g.getOpcode() == Opcodes.GETSTATIC && ((FieldInsnNode) g).owner.equals(OBS)
                        && ((FieldInsnNode) g).name.equals("ATIVO") && real(i, true) == real(((JumpInsnNode) ifeq).label, true)) {
                    ok++;
                }
            }
            int esp = Integer.parseInt(mk[2]);
            v.sim(ini == esp && fim == esp && ok == 2 * esp, "World." + mk[0] + ": " + ini + " mundo() e " + fim
                    + " fimMundo() (esperado " + esp + " de cada), " + ok + " atras de 'if (Observador.ATIVO)' que pula so a chamada");
            totalMarcas += ini + fim;
        }
        // embrulhar: GETSTATIC LISTA, IFEQ, ALOAD 0, GETFIELD loadedTileEntityList, ALOAD n, INVOKESTATIC, ASTORE n
        MethodNode ue = metodo(world, "updateEntities", "()V");
        int emb = 0;
        boolean embOk = false;
        for (AbstractInsnNode i : ue.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(LISTA) && ((MethodInsnNode) i).name.equals("embrulhar")) {
                emb++;
                AbstractInsnNode a2 = real(i, false), f = real(a2, false), a0 = real(f, false), ifeq = real(a0, false), g = real(ifeq, false);
                AbstractInsnNode st = real(i, true);
                embOk = a2 instanceof VarInsnNode && a2.getOpcode() == Opcodes.ALOAD && f instanceof FieldInsnNode
                        && ((FieldInsnNode) f).name.equals("loadedTileEntityList") && a0 instanceof VarInsnNode && ((VarInsnNode) a0).var == 0
                        && ifeq.getOpcode() == Opcodes.IFEQ && g instanceof FieldInsnNode && ((FieldInsnNode) g).owner.equals(OBS)
                        && ((FieldInsnNode) g).name.equals("LISTA") && st instanceof VarInsnNode && st.getOpcode() == Opcodes.ASTORE
                        && ((VarInsnNode) st).var == ((VarInsnNode) a2).var
                        && real(st, true) == real(((JumpInsnNode) ifeq).label, true);
            }
        }
        v.sim(emb == 1 && embOk, "World.updateEntities: 1 embrulhar atras de 'if (Observador.LISTA)', so troca a variavel local ja"
                + " existente antes da atribuicao do campo (" + emb + ", " + embOk + ")");
        // construtores: GETSTATIC LISTA, IFEQ, ALOAD 0, NEW ListaObservadaHashed, DUP, INVOKESPECIAL, PUTFIELD loadedTileEntityList
        int construtores = 0, trocas = 0;
        for (MethodNode m : world.methods) {
            if (!m.name.equals("<init>")) {
                continue;
            }
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i instanceof FieldInsnNode && i.getOpcode() == Opcodes.PUTFIELD
                        && ((FieldInsnNode) i).name.equals("loadedTileEntityList")) {
                    AbstractInsnNode c = real(i, false), d = real(c, false), nw = real(d, false), a0 = real(nw, false),
                            ifeq = real(a0, false), g = real(ifeq, false);
                    if (c instanceof MethodInsnNode && ((MethodInsnNode) c).owner.equals("io/github/crucible/util/HashedArrayList")) {
                        construtores++; // o inicializador original do campo (so nos construtores que chamam super(...))
                    }
                    if (c instanceof MethodInsnNode && ((MethodInsnNode) c).owner.equals("hoshiestar/observador/ListaObservadaHashed")
                            && nw.getOpcode() == Opcodes.NEW && ifeq.getOpcode() == Opcodes.IFEQ && g instanceof FieldInsnNode
                            && ((FieldInsnNode) g).name.equals("LISTA") && real(i, true) == real(((JumpInsnNode) ifeq).label, true)) {
                        trocas++;
                    }
                }
            }
        }
        v.sim(construtores > 0 && trocas == construtores, "World: cada construtor com o inicializador original do campo (" + construtores + ") troca a lista so atras de"
                + " 'if (Observador.LISTA)' (" + trocas + ")");
        int outros = 0;
        for (MethodNode m : world.methods) {
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i instanceof MethodInsnNode && (((MethodInsnNode) i).owner.startsWith(LISTA))) {
                    outros++;
                }
            }
        }
        v.igual(totalMarcas + emb + trocas, outros, "World: nenhuma outra chamada a ListaObservada* alem das marcas, do embrulhar e da troca no construtor");
    }

    private static int guardados(ClassNode cn, String nome, String desc, int esperadas) {
        MethodNode m;
        if (desc == null) {
            m = metodoPorNome(cn, nome);
        } else {
            try {
                m = metodo(cn, nome, desc);
            } catch (IllegalStateException e) {
                // descritor com nome diferente do esperado: acha so pelo nome, se for unico
                m = metodoPorNome(cn, nome);
            }
        }
        int n = 0;
        int ok = 0;
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (!(i instanceof MethodInsnNode) || !((MethodInsnNode) i).owner.equals(GANCHOS)) {
                continue;
            }
            n++;
            AbstractInsnNode p = real(i, false);
            while (p != null && p.getOpcode() != Opcodes.IFEQ && p.getOpcode() != Opcodes.GETSTATIC
                    && !(p instanceof MethodInsnNode) && !(p instanceof JumpInsnNode)) {
                p = real(p, false); // argumentos: ALOAD, ILOAD, GETFIELD, I2D, D2I, constantes, aritmetica
            }
            // acha o IFEQ voltando pelos argumentos (que nao tem salto nem GETSTATIC de outra coisa que nao ATIVO)
            AbstractInsnNode q = real(i, false);
            AbstractInsnNode ifeq = null;
            for (int k = 0; k < 40 && q != null; k++) {
                if (q.getOpcode() == Opcodes.IFEQ) {
                    ifeq = q;
                    break;
                }
                if (q instanceof MethodInsnNode && ((MethodInsnNode) q).owner.equals(GANCHOS)) {
                    break;
                }
                q = real(q, false);
            }
            boolean temIfeq = ifeq != null;
            AbstractInsnNode g = temIfeq ? real(ifeq, false) : null;
            boolean temCampo = g instanceof FieldInsnNode && g.getOpcode() == Opcodes.GETSTATIC
                    && ((FieldInsnNode) g).owner.equals(OBS) && ((FieldInsnNode) g).name.equals("ATIVO");
            boolean pulaSoAChamada = false;
            if (temIfeq) {
                LabelNode alvo = ((JumpInsnNode) ifeq).label;
                pulaSoAChamada = real(i, true) == real(alvo, true);
            }
            if (temIfeq && temCampo && pulaSoAChamada) {
                ok++;
            } else {
                v.sim(false, cn.name + "." + nome + ": chamada " + ((MethodInsnNode) i).name + " sem o teste de campo estatico"
                        + " (ifeq=" + temIfeq + ", ATIVO=" + temCampo + ", pula so a chamada=" + pulaSoAChamada + ")");
            }
        }
        v.sim(n == esperadas && ok == n, cn.name + "." + nome + ": " + n + " chamada(s) a GanchosObservador (esperado "
                + esperadas + "), " + ok + " atras de GETSTATIC Observador.ATIVO + IFEQ que pula so a chamada");
        return n;
    }

    private static void padroesWorld(ClassNode world) {
        MethodNode m = metodo(world, "updateEntities", "()V");
        AbstractInsnNode[] l = m.instructions.toArray();
        int chamadasTe = 0;
        int opisTe = 0;
        int opisEnt = 0;
        int aloadTe = -1;
        int aloadEnt = -1;
        for (int i = 0; i < l.length; i++) {
            if (l[i] instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) l[i];
                if (mi.owner.equals("net/minecraft/tileentity/TileEntity") && mi.name.equals("updateEntity") && mi.desc.equals("()V")) {
                    chamadasTe++;
                }
            }
            if (i + 2 < l.length && l[i] instanceof LineNumberNode && l[i + 1].getOpcode() == Opcodes.ALOAD
                    && l[i + 2] instanceof MethodInsnNode && l[i + 2].getOpcode() == Opcodes.INVOKEVIRTUAL
                    && ((MethodInsnNode) l[i + 2]).owner.equals("net/minecraft/tileentity/TileEntity")
                    && ((MethodInsnNode) l[i + 2]).name.equals("updateEntity")) {
                opisTe++;
                aloadTe = ((VarInsnNode) l[i + 1]).var;
            }
            if (i + 3 < l.length && l[i] instanceof LineNumberNode && l[i + 1].getOpcode() == Opcodes.ALOAD
                    && l[i + 2].getOpcode() == Opcodes.ALOAD && l[i + 3] instanceof MethodInsnNode
                    && l[i + 3].getOpcode() == Opcodes.INVOKEVIRTUAL && ((MethodInsnNode) l[i + 3]).name.equals("updateEntity")
                    && ((MethodInsnNode) l[i + 3]).desc.equals("(Lnet/minecraft/entity/Entity;)V")) {
                opisEnt++;
                aloadEnt = ((VarInsnNode) l[i + 2]).var;
            }
        }
        v.igual(1, chamadasTe, "World.updateEntities: exatamente 1 chamada TileEntity.updateEntity (o firewall recusa outra contagem)");
        v.sim(opisTe == 1 && aloadTe == 9, "padrao do Opis para tile entity intacto: [linha, ALOAD 9, INVOKEVIRTUAL] (achados "
                + opisTe + ", ALOAD " + aloadTe + ")");
        v.sim(opisEnt == 1 && aloadEnt == 2, "padrao do Opis para entidade intacto: [linha, ALOAD 0, ALOAD 2, INVOKEVIRTUAL]"
                + " (achados " + opisEnt + ", ALOAD " + aloadEnt + ")");
        boolean te9 = false;
        boolean ent2 = false;
        for (LocalVariableNode lv : m.localVariables) {
            te9 |= lv.index == 9 && lv.name.equals("tileentity");
            ent2 |= lv.index == 2 && lv.name.equals("entity");
        }
        v.sim(te9 && ent2, "locais do Opis: 9 = tileentity e 2 = entity (" + te9 + ", " + ent2 + ")");
        v.igual(14, m.maxLocals, "World.updateEntities: maxLocals 14 (o mesmo do jar base: nenhuma variavel local nova)");
        MethodNode f = metodo(world, "updateEntityWithOptionalForce", "(Lnet/minecraft/entity/Entity;Z)V");
        v.igual(13, f.maxLocals, "World.updateEntityWithOptionalForce: maxLocals 13 (o mesmo do jar base)");
        // nenhum bloco try/catch novo por nossa causa em updateEntities: as chamadas do observador nao estao em protecao nossa
        int floors = 0;
        for (AbstractInsnNode i : f.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).name.equals("floor_double")) {
                floors++;
            }
        }
        v.igual(5, floors, "updateEntityWithOptionalForce: 5 floor_double, como no base (o mixin do Bellow casa"
                + " floor_double(?.posY / 16.0) com allow=1)");
    }

    /** Indice da primeira chamada (dono, nome) a partir de {@code de}; -1 se nao ha. */
    private static int chamada(AbstractInsnNode[] l, int de, String dono, String nome) {
        for (int i = de; i < l.length; i++) {
            if (l[i] instanceof MethodInsnNode && ((MethodInsnNode) l[i]).owner.equals(dono) && ((MethodInsnNode) l[i]).name.equals(nome)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Contexto velho (D-39): o que o proprio servidor le antes do gancho seguinte nao pode cair no contexto da entrada
     * anterior, e o contexto nao pode sobrar depois de excecao ou do fim do laco.
     */
    private static void contextosFechados(ClassNode world, ClassNode ws) {
        // tickUpdates: o contexto da entrada anterior fecha antes do getBlock da proxima (que roda antes de tickAgendado)
        AbstractInsnNode[] t = metodo(ws, "tickUpdates", "(Z)Z").instructions.toArray();
        int proxima = chamada(t, 0, "java/util/Iterator", "next");
        int fecha = chamada(t, proxima, GANCHOS, "fimTickAgendado");
        int getBlock = chamada(t, proxima, "net/minecraft/world/WorldServer", "getBlock");
        v.sim(proxima > 0 && fecha > proxima && getBlock > fecha, "WorldServer.tickUpdates: fimTickAgendado entre Iterator.next e o getBlock da entrada ("
                + proxima + ", " + fecha + ", " + getBlock + ")");
        // func_147456_g: o contexto do tick aleatorio fecha dentro do laco dos chunks (antes do desvio de volta), nao so depois dele
        AbstractInsnNode[] r = metodo(ws, "func_147456_g", "()V").instructions.toArray();
        int ultimaVolta = -1;
        for (int i = 0; i < r.length; i++) {
            if (r[i].getOpcode() == Opcodes.GOTO && r[i] instanceof JumpInsnNode) {
                int alvo = Arrays.asList(r).indexOf(((JumpInsnNode) r[i]).label);
                if (alvo >= 0 && alvo < i) {
                    ultimaVolta = i;
                }
            }
        }
        int fimAleat = chamada(r, 0, GANCHOS, "fimTickAleatorio");
        v.sim(fimAleat > 0 && ultimaVolta > fimAleat, "WorldServer.func_147456_g: fimTickAleatorio dentro do laco dos chunks (" + fimAleat + " antes do desvio de volta " + ultimaVolta + ")");
        // updateEntities: fim do contexto na excecao do tile, na excecao da entidade e no fim do laco de entidades
        MethodNode ue = metodo(world, "updateEntities", "()V");
        AbstractInsnNode[] u = ue.instructions.toArray();
        v.sim(handlerFecha(ue, u, "removeErroringTileEntities", "fimTile"), "World.updateEntities: o catch do update do tile chama fimTile");
        v.sim(handlerFecha(ue, u, "removeErroringEntities", "fimEntidade"), "World.updateEntities: o catch do tick da entidade chama fimEntidade");
        int marcaFim = -1;
        for (int i = 0; i < u.length; i++) {
            if (u[i] instanceof MethodInsnNode && ((MethodInsnNode) u[i]).owner.equals("hoshiestar/vigia/Marcas") && ((MethodInsnNode) u[i]).name.equals("entidade")
                    && u[i - 1].getOpcode() == Opcodes.ACONST_NULL) {
                marcaFim = i;
            }
        }
        int fimEnt = chamada(u, marcaFim, GANCHOS, "fimEntidade");
        v.sim(marcaFim > 0 && fimEnt > marcaFim && fimEnt < marcaFim + 12, "World.updateEntities: fimEntidade logo depois de Marcas.entidade(null), no fim do laco de entidades");
    }

    /** O tratador (catch) da ULTIMA leitura do campo {@code campo} (o laco principal vem depois do dos efeitos de clima) chama {@code gancho} antes dela. */
    private static boolean handlerFecha(MethodNode m, AbstractInsnNode[] l, String campo, String gancho) {
        for (int i = l.length - 1; i >= 0; i--) {
            if (l[i] instanceof FieldInsnNode && ((FieldInsnNode) l[i]).name.equals(campo)) {
                for (Object o : m.tryCatchBlocks) {
                    org.objectweb.asm.tree.TryCatchBlockNode tc = (org.objectweb.asm.tree.TryCatchBlockNode) o;
                    int h = Arrays.asList(l).indexOf(tc.handler);
                    if (h >= 0 && h < i && i - h < 60) {
                        int g = chamada(l, h, GANCHOS, gancho);
                        return g > h && g < i;
                    }
                }
            }
        }
        return false;
    }

    private static void servidor(ClassNode s) {
        MethodNode tick = metodo(s, "tick", "()V");
        int ini = 0;
        int fim = 0;
        for (AbstractInsnNode i : tick.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(OBS)) {
                if (((MethodInsnNode) i).name.equals("inicioTickServidor")) {
                    ini++;
                }
                if (((MethodInsnNode) i).name.equals("fimTickServidor")) {
                    fim++;
                }
            }
        }
        v.sim(ini == 1 && fim == 1, "MinecraftServer.tick chama Observador.inicioTickServidor e fimTickServidor uma vez cada");
        MethodNode run = metodo(s, "run", "()V");
        int parar = 0;
        for (AbstractInsnNode i : run.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(OBS)
                    && ((MethodInsnNode) i).name.equals("encerrarNoDesligamento")) {
                parar++;
            }
        }
        v.igual(1, parar, "MinecraftServer.run chama Observador.encerrarNoDesligamento uma vez (antes do evento de parada do FML)");
        // as 6 trocas de contexto do tick (antes/depois) em volta das chamadas do Forge
        int pre = 0;
        for (AbstractInsnNode i : tick.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).name.equals("onPreServerTick")) {
                pre++;
            }
        }
        v.igual(1, pre, "MinecraftServer.tick: onPreServerTick continua sendo chamado uma vez");
    }

    /** GanchosObservador: so le o jogo, nunca lanca, nenhum dado de jogador, encaminha outra thread para T4. */
    private static void ganchos(File dir) throws Exception {
        ClassNode g = ler(dir, "hoshiestar/observador/GanchosObservador.class");
        Set<String> invocadosMc = new TreeSet<String>();
        boolean escreveMc = false;
        int semProtecao = 0;
        String proibido = "";
        ClassNode jogo = ler(dir, "hoshiestar/observador/GanchosObservador$JogoMinecraft.class");
        java.util.List<MethodNode> todos = new java.util.ArrayList<MethodNode>(g.methods);
        todos.addAll(jogo.methods);
        for (MethodNode m : todos) {
            boolean publico = g.methods.contains(m) && (m.access & Opcodes.ACC_PUBLIC) != 0 && (m.access & Opcodes.ACC_STATIC) != 0;
            if (publico && (m.access & Opcodes.ACC_PUBLIC) != 0 && m.tryCatchBlocks.isEmpty()) {
                // os "fim..." chamam sair(), que tem o proprio try/catch; os outros tem de ter
                boolean soChamaPrivado = true;
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i instanceof MethodInsnNode && !((MethodInsnNode) i).owner.equals(GANCHOS)) {
                        soChamaPrivado = false;
                    }
                }
                if (!soChamaPrivado) {
                    semProtecao++;
                    proibido += " " + m.name;
                }
            }
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i instanceof FieldInsnNode && (i.getOpcode() == Opcodes.PUTFIELD || i.getOpcode() == Opcodes.PUTSTATIC)
                        && ((FieldInsnNode) i).owner.startsWith("net/minecraft/")) {
                    escreveMc = true;
                }
                if (i instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) i;
                    if (mi.owner.startsWith("net/minecraft/") || mi.owner.startsWith("net/minecraftforge/")) {
                        invocadosMc.add(mi.owner.substring(mi.owner.lastIndexOf('/') + 1) + "." + mi.name);
                    }
                    String n = mi.name.toLowerCase(java.util.Locale.ROOT);
                    if (n.contains("gameprofile") || n.contains("uniqueid") || n.contains("uuid") || n.contains("commandsendername")
                            || n.contains("displayname") || n.contains("username") || n.contains("getaddress") || n.contains("socket")) {
                        proibido += " " + m.name + "->" + mi.name;
                    }
                }
            }
        }
        v.sim(semProtecao == 0, "GanchosObservador: todo gancho publico tem try/catch (nunca lanca)" + (semProtecao == 0 ? "" : ":" + proibido));
        v.sim(!escreveMc, "GanchosObservador: nenhum PUTFIELD/PUTSTATIC em classe do Minecraft (so le)");
        v.igual("[DimensionManager.getWorlds, MinecraftServer.getFile, MinecraftServer.getServer]", invocadosMc.toString(),
                "GanchosObservador: as unicas chamadas a classes do Minecraft/Forge sao getWorlds, getServer e getFile (so leitura)");
        v.sim(!proibido.contains("->"), "GanchosObservador: nenhuma chamada que traga nome, UUID, IP ou rede de jogador" + proibido);
        MethodNode jog = metodoPorNome(g, "jogadorRede");
        boolean getClass = false;
        boolean nulo = false;
        for (AbstractInsnNode i : jog.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).name.equals("getClass")) {
                getClass = true;
            }
            if (i.getOpcode() == Opcodes.ACONST_NULL) {
                nulo = true;
            }
        }
        v.sim(!getClass && nulo, "jogadorRede: entra com classe null (nenhum objeto do jogador chega ao nucleo)");
        for (String nome : new String[] {"lerBloco", "acesso"}) {
            MethodNode m = metodoPorNome(g, nome);
            boolean souServidor = false;
            boolean outra = false;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i instanceof MethodInsnNode) {
                    souServidor |= ((MethodInsnNode) i).name.equals("souAThreadDoServidor");
                    outra |= ((MethodInsnNode) i).name.equals("outraThread");
                }
            }
            v.sim(souServidor && outra, "GanchosObservador." + nome + ": confere a thread e manda outra thread para T4 (outraThread)");
        }
        MethodNode ctx = metodoPorNome(g, "tile");
        boolean servidorCtx = false;
        for (AbstractInsnNode i : ctx.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(GANCHOS) && ((MethodInsnNode) i).name.equals("servidor")) {
                servidorCtx = true;
            }
        }
        v.sim(servidorCtx, "GanchosObservador.tile: o contexto so muda na thread do servidor");
    }
}
