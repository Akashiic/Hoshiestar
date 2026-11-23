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
package hoshiestar.teste.chromaticraft;

import hoshiestar.chromaticraft.ChromatiCraft;
import hoshiestar.chromaticraft.DecisaoShift;
import hoshiestar.chromaticraft.DecisaoShift.Motivo;
import hoshiestar.teste.Verifica;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Executa de verdade o {@code shiftArea} transformado (classe real do ChromatiCraft, dentro do class loader de teste),
 * com objetos de mentira para o mundo e o jogador (alocados sem construtor: o Minecraft real nao sobe aqui) e um mapa de
 * chunks de teste ({@code ChunkProviderServer} que so responde {@code chunkExists}). Prova a
 * ORDEM: o gancho roda antes do corpo original; quando recusa, o corpo original NAO roda e a recusa faz os passos do ramo
 * original (som de erro, desligar a habilidade) mais a mensagem; quando aceita ou falha, o corpo original roda (e, sem
 * mundo de verdade, quebra: a excecao vem do codigo original, com o nome de {@code shiftArea} na pilha).
 *
 * <p>O ChromatiCraft de verdade nao inicia fora do servidor (o DragonAPI pede o Loader do Forge ao iniciar as enumeracoes),
 * por isso o gancho pede tudo ao mod por {@code GanchoShift.mod}: aqui um registro de chamadas no lugar do mod.
 */
final class Execucao {

    private Execucao() {
    }

    static Object aloca(Class<?> c) throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object u = f.get(null);
        return u.getClass().getMethod("allocateInstance", Class.class).invoke(u, c);
    }

    /** O que o mod de mentira registra, na ordem. */
    static class Registro implements InvocationHandler {
        final List<String> chamadas = new ArrayList<String>();
        int potencia = 24;

        public Object invoke(Object p, Method m, Object[] a) {
            String n = m.getName();
            if (n.equals("potenciaMaxima")) {
                chamadas.add("potencia");
                return potencia;
            }
            if (n.equals("somDeErro")) {
                chamadas.add("som");
            } else if (n.equals("desligarHabilidade")) {
                chamadas.add("desligar");
            } else if (n.equals("avisar")) {
                chamadas.add("msg:" + a[1]);
            } else if (n.equals("nome")) {
                return "JogadorDeTeste";
            }
            return null;
        }

        List<String> semPotencia() {
            List<String> r = new ArrayList<String>(chamadas);
            r.remove("potencia");
            return r;
        }
    }

    static void rodar(Verifica v, Class<?> alvo, ClassLoader cl) throws Exception {
        Class<?> mundoC = Class.forName("net.minecraft.world.WorldServer", false, cl);
        Class<?> jogadorC = Class.forName("net.minecraft.entity.player.EntityPlayerMP", false, cl);
        Class<?> caixaC = Class.forName("Reika.DragonAPI.Instantiable.Data.Immutable.BlockBox", true, cl);
        Class<?> direcaoC = Class.forName("net.minecraftforge.common.util.ForgeDirection", true, cl);
        Class<?> ganchoC = Class.forName("hoshiestar.chromaticraft.shift.GanchoShift", true, cl);
        Class<?> modC = Class.forName("hoshiestar.chromaticraft.shift.GanchoShift$Mod", true, cl);
        Method shift = alvo.getDeclaredMethod("shiftArea", mundoC, caixaC, direcaoC, int.class, jogadorC);
        shift.setAccessible(true);
        Constructor<?> ctCaixa = caixaC.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
        Object cima = direcaoC.getField("UP").get(null);
        Object sul = direcaoC.getField("SOUTH").get(null);
        Object mundo = aloca(mundoC);
        Object jogador = aloca(jogadorC);
        Object caixa4 = ctCaixa.newInstance(0, 60, 0, 3, 63, 3); // 4 x 4 x 4 = 64 posicoes

        Registro reg = new Registro();
        Field campoMod = ganchoC.getField("mod");
        Object modReal = campoMod.get(null);
        v.sim(modReal != null && modReal.getClass().getSimpleName().equals("ModReal"), "o acesso ao mod em uso no servidor e o ModReal (so o teste troca)");
        campoMod.set(null, Proxy.newProxyInstance(cl, new Class<?>[] {modC}, reg));

        // o mapa de chunks: um ChunkProviderServer de teste (sobre o vanilla), alocado sem construtor, que so responde chunkExists
        Class<?> provedorC = Class.forName("hoshiestar.teste.chromaticraft.mundo.ProvedorFalso", true, cl);
        Object provedor = aloca(provedorC);
        mundoC.getField("field_73059_b").set(mundo, provedor); // WorldServer.theChunkProviderServer (nome de tempo de execucao)
        final Method carrega = provedorC.getMethod("carregar", int.class, int.class);
        final Method limpa = provedorC.getMethod("limpar");
        final Method consultas = provedorC.getMethod("consultas");
        Method existe = provedorC.getMethod("func_73149_a", int.class, int.class);
        v.igual(false, existe.invoke(provedor, 0, 0), "o provedor de teste, vazio, diz que (0,0) nao esta carregado");
        carrega.invoke(null, 5, 5);
        v.igual(true, existe.invoke(provedor, 5, 5), "e diz que um chunk carregado esta carregado");
        limpa.invoke(null);

        ChromatiCraft.mensagem = "Mover area recusado: {motivo}.";
        ChromatiCraft.logSegundos = 100;
        ChromatiCraft.lentoMs = 0;
        ChromatiCraft.ativo = true;
        ChromatiCraft.zerarContadores();

        // ---- A: volume acima do limite: o gancho recusa e shiftArea devolve false SEM rodar o corpo original
        ChromatiCraft.decisao = new DecisaoShift(0, true, 1, false, false);
        ChromatiCraft.zerarContadores();
        reg.chamadas.clear();
        Object r = null;
        Throwable t = null;
        try {
            r = shift.invoke(null, mundo, caixa4, cima, 5, jogador);
        } catch (InvocationTargetException e) {
            t = e.getCause();
        }
        v.sim(t == null && Boolean.FALSE.equals(r), "recusa por volume: shiftArea devolve false sem excecao (o corpo original, que quebraria sem mundo, nao rodou)"
                + (t != null ? " -> " + t : ""));
        v.igual(1L, ChromatiCraft.decisao.recusas(Motivo.VOLUME), "recusa por volume contada (1)");
        v.igual(0L, ChromatiCraft.decisao.passaram(), "nenhum pedido aceito");
        List<String> esperado = new ArrayList<String>();
        esperado.add("som");
        esperado.add("desligar");
        esperado.add("msg:Mover area recusado: area grande demais (64 blocos; limite 1).");
        v.igual(esperado, reg.semPotencia(), "a recusa faz, nesta ordem, o ramo original (som de erro, habilidade desligada no jogador) e manda a mensagem");
        v.igual(0L, ChromatiCraft.erros, "recusa sem erro nosso");
        v.igual(1L, ChromatiCraft.recusasLogadas, "uma linha de log da recusa");

        // ---- A2: o log da recusa tem limite de frequencia (5 recusas seguidas do mesmo jogador: nenhuma linha nova)
        long logadasAntes = ChromatiCraft.recusasLogadas;
        for (int i = 0; i < 5; i++) {
            shift.invoke(null, mundo, caixa4, cima, 5, jogador);
        }
        v.igual(logadasAntes, ChromatiCraft.recusasLogadas, "5 recusas seguidas do mesmo jogador dentro da janela de 100 s: nenhuma linha nova de log");
        v.igual(6L, ChromatiCraft.decisao.recusas(Motivo.VOLUME), "mas as 6 recusas foram contadas");

        // ---- B: distancia invalida
        ChromatiCraft.decisao = new DecisaoShift(0, true, 4096, false, false);
        reg.chamadas.clear();
        r = null;
        t = null;
        try {
            r = shift.invoke(null, mundo, caixa4, cima, 99, jogador);
        } catch (InvocationTargetException e) {
            t = e.getCause();
        }
        v.sim(t == null && Boolean.FALSE.equals(r), "recusa por distancia 99: devolve false sem rodar o original" + (t != null ? " -> " + t : ""));
        v.igual(1L, ChromatiCraft.decisao.recusas(Motivo.DISTANCIA), "recusa por distancia contada");
        v.sim(reg.chamadas.contains("potencia") && reg.chamadas.contains("msg:Mover area recusado: distancia 99 invalida (de 0 a 24).")
                && reg.chamadas.contains("som") && reg.chamadas.contains("desligar"), "pediu o maximo ao mod e mandou a mensagem da distancia (" + reg.chamadas + ")");

        // ---- B2: destino fora da altura
        ChromatiCraft.decisao = new DecisaoShift(0, true, 4096, true, false);
        reg.chamadas.clear();
        Object alta = ctCaixa.newInstance(0, 240, 0, 3, 243, 3);
        r = shift.invoke(null, mundo, alta, cima, 24, jogador);
        v.sim(Boolean.FALSE.equals(r) && ChromatiCraft.decisao.recusas(Motivo.ALTURA) == 1, "recusa por altura (UP 24 de y 240..243): false e contada");

        // ---- B3: chunks (o mapa real do Crucible esta vazio): recusa; carregados os 4 chunks da caixa: o original roda
        ChromatiCraft.decisao = new DecisaoShift(0, true, 4096, true, true);
        reg.chamadas.clear();
        r = shift.invoke(null, mundo, caixa4, sul, 5, jogador);
        v.sim(Boolean.FALSE.equals(r) && ChromatiCraft.decisao.recusas(Motivo.CHUNKS) == 1, "chunks: mapa vazio -> recusa (false) sem rodar o original");
        v.sim(reg.chamadas.contains("msg:Mover area recusado: parte da area ou do destino esta em chunks nao carregados."), "e a mensagem diz o motivo (" + reg.chamadas + ")");
        // caixa (0,60,0)-(3,63,3) para o sul 5: origem alargada x -1..4, z -1..4 => chunks (-1..0, -1..0); destino z 4..8 (alargada 4..9) => chunk z 0
        int[][] quatro = {{-1, -1}, {-1, 0}, {0, -1}, {0, 0}};
        for (int i = 0; i < 3; i++) {
            carrega.invoke(null, quatro[i][0], quatro[i][1]);
            r = shift.invoke(null, mundo, caixa4, sul, 5, jogador);
            v.sim(Boolean.FALSE.equals(r), "com " + (i + 1) + " de 4 chunks carregados ainda recusa (falta algum)");
        }
        carrega.invoke(null, quatro[3][0], quatro[3][1]);
        ChromatiCraft.zerarContadores();
        t = null;
        try {
            r = shift.invoke(null, mundo, caixa4, sul, 5, jogador);
        } catch (InvocationTargetException e) {
            t = e.getCause();
        }
        v.sim(t != null && estaNaPilha(t, "AbilityCalls", "shiftArea") && !estaNaPilha(t, "GanchoShift", "recusar"),
                "com os 4 chunks carregados o pedido passa e o corpo ORIGINAL roda (quebra sem mundo: " + (t == null ? "devolveu " + r : t.getClass().getSimpleName()) + ")");
        v.igual(1L, ChromatiCraft.decisao.passaram(), "o gancho foi chamado antes e deixou o original seguir (1 aceito)");
        v.igual(0L, ChromatiCraft.erros, "pedido aceito sem erro nosso");

        // ---- C: intervalo minimo com o relogio real: aceito, e logo depois recusado
        ChromatiCraft.decisao = new DecisaoShift(60000, true, 4096, true, false);
        ChromatiCraft.zerarContadores();
        reg.chamadas.clear();
        t = null;
        try {
            shift.invoke(null, mundo, caixa4, sul, 5, jogador);
        } catch (InvocationTargetException e) {
            t = e.getCause();
        }
        v.sim(t != null && estaNaPilha(t, "AbilityCalls", "shiftArea"), "intervalo: o primeiro pedido passa e o original roda");
        r = shift.invoke(null, mundo, caixa4, sul, 5, jogador);
        v.sim(Boolean.FALSE.equals(r) && ChromatiCraft.decisao.recusas(Motivo.INTERVALO) == 1, "intervalo: o segundo pedido, logo depois, e recusado sem rodar o original");
        v.sim(reg.chamadas.contains("msg:Mover area recusado: aguarde um instante antes de mover outra area."), "e a mensagem diz o motivo");

        // ---- D: interruptor em execucao desligado: o gancho nem decide; segue pelo original
        ChromatiCraft.decisao = new DecisaoShift(0, true, 1, false, false); // recusaria
        ChromatiCraft.ativo = false;
        t = null;
        try {
            shift.invoke(null, mundo, caixa4, cima, 5, jogador);
        } catch (InvocationTargetException e) {
            t = e.getCause();
        }
        v.sim(t != null && estaNaPilha(t, "AbilityCalls", "shiftArea"), "desligado em execucao: o original roda (nao recusa)");
        v.igual(0L, ChromatiCraft.decisao.chamadas(), "desligado em execucao: a decisao nem foi consultada");
        ChromatiCraft.ativo = true;

        // ---- E: sem decisao (recurso desligado no config mas classe ja transformada): segue pelo original
        ChromatiCraft.decisao = null;
        t = null;
        try {
            shift.invoke(null, mundo, caixa4, cima, 5, jogador);
        } catch (InvocationTargetException e) {
            t = e.getCause();
        }
        v.sim(t != null && estaNaPilha(t, "AbilityCalls", "shiftArea"), "sem decisao: o original roda");

        // ---- F: excecao nossa na decisao (mundo sem provedor de chunks): cai no original e e contada
        Object mundoSemProvedor = aloca(mundoC);
        ChromatiCraft.decisao = new DecisaoShift(0, true, 4096, false, true); // exige chunks
        ChromatiCraft.zerarContadores();
        t = null;
        try {
            shift.invoke(null, mundoSemProvedor, caixa4, cima, 5, jogador);
        } catch (InvocationTargetException e) {
            t = e.getCause();
        }
        v.sim(t != null && estaNaPilha(t, "AbilityCalls", "shiftArea") && !estaNaPilha(t, "GanchoShift", "recusar"),
                "excecao nossa na decisao: o pedido cai no corpo original (a excecao que sai e a do original, nao a nossa)");
        v.igual(1L, ChromatiCraft.erros, "a excecao nossa foi contada em erros");
        v.igual(0L, ChromatiCraft.decisao.passaram() + ChromatiCraft.decisao.recusasTotal(), "e o pedido nao foi contado como aceito nem recusado");

        // ---- G: falha em cada passo da recusa (som, desligar, mensagem, nome): a recusa termina mesmo assim
        reg = new Registro() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                chamadas.add(m.getName());
                if (m.getName().equals("potenciaMaxima")) {
                    return 24;
                }
                if (m.getName().equals("nome")) {
                    return "X";
                }
                throw new IllegalStateException("falha de teste em " + m.getName());
            }
        };
        campoMod.set(null, Proxy.newProxyInstance(cl, new Class<?>[] {modC}, reg));
        ChromatiCraft.decisao = new DecisaoShift(0, true, 1, false, false);
        ChromatiCraft.zerarContadores();
        r = null;
        t = null;
        try {
            r = shift.invoke(null, mundo, caixa4, cima, 5, jogador);
        } catch (InvocationTargetException e) {
            t = e.getCause();
        }
        v.sim(t == null && Boolean.FALSE.equals(r), "som, desligar e mensagem lancando excecao: a recusa termina assim mesmo (false), sem rodar o original");
        v.sim(reg.chamadas.contains("somDeErro") && reg.chamadas.contains("desligarHabilidade") && reg.chamadas.contains("avisar"),
                "e cada passo foi tentado, um nao impede o outro (" + reg.chamadas + ")");
        v.igual(3L, ChromatiCraft.erros, "as 3 falhas foram contadas em erros");

        // ---- H: fimDaChamada (a parte nossa do gancho de medicao): so registra se estava medindo e demorou
        reg = new Registro();
        campoMod.set(null, Proxy.newProxyInstance(cl, new Class<?>[] {modC}, reg));
        Method fim = ganchoC.getMethod("fimDaChamada", Class.forName("net.minecraft.entity.player.EntityPlayer", false, cl));
        ChromatiCraft.decisao = new DecisaoShift(0, true, 4096, false, false);
        ChromatiCraft.zerarContadores();
        ChromatiCraft.lentoMs = 20;
        fim.invoke(null, jogador); // sem medicao em andamento
        v.igual(0L, ChromatiCraft.lentas, "fimDaChamada sem medicao em andamento: nada registrado");
        try {
            shift.invoke(null, mundo, caixa4, sul, 5, jogador); // aceito: comeca a medir; o original quebra
        } catch (InvocationTargetException e) {
            // esperado: sem mundo de verdade
        }
        Thread.sleep(40);
        fim.invoke(null, jogador);
        v.igual(1L, ChromatiCraft.lentas, "chamada de 40 ms com limite de 20 ms: registrada como lenta");
        v.sim(ChromatiCraft.maiorLentoMs >= 40, "e o maior tempo medido e >= 40 ms (" + ChromatiCraft.maiorLentoMs + ")");
        fim.invoke(null, jogador);
        v.igual(1L, ChromatiCraft.lentas, "chamar de novo nao registra outra vez (a medicao acabou)");
        ChromatiCraft.lentoMs = 100000;
        try {
            shift.invoke(null, mundo, caixa4, sul, 5, jogador);
        } catch (InvocationTargetException e) {
            // esperado
        }
        fim.invoke(null, jogador);
        v.igual(1L, ChromatiCraft.lentas, "chamada rapida (abaixo do limite): nao registra");
        ChromatiCraft.lentoMs = 0;
        try {
            shift.invoke(null, mundo, caixa4, sul, 5, jogador);
        } catch (InvocationTargetException e) {
            // esperado
        }
        Thread.sleep(25);
        fim.invoke(null, jogador);
        v.igual(1L, ChromatiCraft.lentas, "loglento 0: nem comeca a medir");

        // ---- I: a COLA do gancho (os nove inteiros da caixa e da direcao, na ordem certa, ate a decisao)
        reg = new Registro();
        campoMod.set(null, Proxy.newProxyInstance(cl, new Class<?>[] {modC}, reg));
        cola(v, cl, mundoC, jogadorC, caixaC, direcaoC, ganchoC, ctCaixa, mundo, jogador, reg, provedorC);

        // ---- J: as pontes da medicao com o gancho ligado (controle da ponte com gancho que nao liga)
        ChromatiCraft.zerarContadores();
        pontes(v, alvo, false);

        campoMod.set(null, modReal);
        ChromatiCraft.decisao = null;
        ChromatiCraft.ativo = false;
        ChromatiCraft.zerarContadores();
    }

    /**
     * A cola: {@code GanchoShift.recusar} tira da {@code BlockBox} e da {@code ForgeDirection} nove inteiros posicionais
     * e os passa a {@code DecisaoShift.decidir}. Trocar dois deles (minY por maxY, offsetX por offsetZ...) nao muda
     * nenhum teste da decisao pura, que chama {@code decidir} direto. Aqui cada eixo e cada lado tem um valor DIFERENTE
     * (caixa assimetrica) e se confere o efeito de cada um: volume exato no limite e um abaixo, altura onde so o
     * maxY ou so o minY estoura, e o conjunto EXATO de chunks que o gancho pergunta ao mapa (origem e destino, alargados
     * em 1 bloco), calculado aqui por conta propria nas seis direcoes.
     */
    static void cola(Verifica v, ClassLoader cl, Class<?> mundoC, Class<?> jogadorC, Class<?> caixaC, Class<?> direcaoC, Class<?> ganchoC,
            Constructor<?> ctCaixa, Object mundo, Object jogador, Registro reg, Class<?> provedorC) throws Exception {
        Method recusar = ganchoC.getMethod("recusar", mundoC, caixaC, direcaoC, int.class, jogadorC);
        Method limpa = provedorC.getMethod("limpar");
        Method todos = provedorC.getMethod("todosCarregados", boolean.class);
        Method perguntados = provedorC.getMethod("consultados");
        ChromatiCraft.ativo = true;
        ChromatiCraft.lentoMs = 0;
        ChromatiCraft.mensagem = "";
        ChromatiCraft.logSegundos = 100;
        limpa.invoke(null);

        // caixa assimetrica: spans 38 (x), 6 (y), 36 (z); nenhuma coordenada repete em eixos diferentes
        final int[] c = {-17, 70, 35, 20, 75, 70}; // minX minY minZ maxX maxY maxZ
        final long volume = 38L * 6L * 36L; // 8208
        Object caixa = ctCaixa.newInstance(c[0], c[1], c[2], c[3], c[4], c[5]);
        Object norte = direcaoC.getField("NORTH").get(null);

        // volume: no limite passa, um abaixo recusa (cada eixo conta com o span certo: trocar min por max muda o numero)
        ChromatiCraft.decisao = new DecisaoShift(0, true, volume, false, false);
        v.igual(false, recusar.invoke(null, mundo, caixa, norte, 1, jogador), "cola/volume: caixa 38x6x36 = " + volume + " com limite " + volume + " passa");
        ChromatiCraft.decisao = new DecisaoShift(0, true, volume - 1, false, false);
        v.igual(true, recusar.invoke(null, mundo, caixa, norte, 1, jogador), "cola/volume: com limite " + (volume - 1) + " recusa");
        v.igual(1L, ChromatiCraft.decisao.recusas(Motivo.VOLUME), "cola/volume: recusa contada como volume");
        // a mesma caixa nos 3 eixos (1 x 200): cada eixo entra no volume
        int[][] finas = {{0, 60, 0, 199, 60, 0}, {0, 0, 0, 0, 199, 0}, {0, 60, 0, 0, 60, 199}, {-199, 60, 0, 0, 60, 0}, {0, 60, -199, 0, 60, 0}};
        String[] nomes = {"x", "y", "z", "x (minX)", "z (minZ)"};
        for (int i = 0; i < finas.length; i++) {
            ChromatiCraft.decisao = new DecisaoShift(0, true, 100, false, false);
            int[] f = finas[i];
            Object fina = ctCaixa.newInstance(f[0], f[1], f[2], f[3], f[4], f[5]);
            v.igual(true, recusar.invoke(null, mundo, fina, norte, 1, jogador), "cola/volume: 1x200 no eixo " + nomes[i] + " recusado com limite 100");
        }

        // altura: so o maxY estoura (y 220..240 UP 24: min 244 cabe, max 264 nao) e so o minY estoura (y 10..30 DOWN 24: min -14, max 6 cabe)
        Object cima = direcaoC.getField("UP").get(null);
        Object baixo = direcaoC.getField("DOWN").get(null);
        ChromatiCraft.decisao = new DecisaoShift(0, true, 0, true, false);
        v.igual(true, recusar.invoke(null, mundo, ctCaixa.newInstance(0, 220, 0, 3, 240, 3), cima, 24, jogador),
                "cola/altura: so o maxY estoura (y 220..240 para CIMA 24): recusa");
        v.igual(true, recusar.invoke(null, mundo, ctCaixa.newInstance(0, 10, 0, 3, 30, 3), baixo, 24, jogador),
                "cola/altura: so o minY estoura (y 10..30 para BAIXO 24): recusa");
        v.igual(2L, ChromatiCraft.decisao.recusas(Motivo.ALTURA), "cola/altura: as duas recusas contadas como altura");
        v.igual(false, recusar.invoke(null, mundo, ctCaixa.newInstance(0, 220, 0, 3, 231, 3), cima, 24, jogador),
                "cola/altura: controle: y 220..231 para CIMA 24 termina em 255 e passa");
        v.igual(false, recusar.invoke(null, mundo, ctCaixa.newInstance(0, 24, 0, 3, 40, 3), baixo, 24, jogador),
                "cola/altura: controle: y 24..40 para BAIXO 24 comeca em 0 e passa");

        // chunks: com "tudo carregado" o gancho pergunta ao mapa EXATAMENTE origem e destino alargados em 1 bloco
        ChromatiCraft.decisao = new DecisaoShift(0, false, 0, false, true);
        String[] dirs = {"NORTH", "SOUTH", "EAST", "WEST", "UP", "DOWN"};
        int[][] vet = {{0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        for (int i = 0; i < dirs.length; i++) {
            limpa.invoke(null);
            todos.invoke(null, true);
            int dist = 13 + i; // distancia diferente por direcao
            Object dir = direcaoC.getField(dirs[i]).get(null);
            Object r = recusar.invoke(null, mundo, caixa, dir, dist, jogador);
            java.util.Set<String> esperado = new java.util.TreeSet<String>();
            for (int rodada = 0; rodada < 2; rodada++) {
                int ox = rodada == 0 ? 0 : vet[i][0] * dist;
                int oz = rodada == 0 ? 0 : vet[i][2] * dist;
                for (int cx = (c[0] + ox - 1) >> 4; cx <= (c[3] + ox + 1) >> 4; cx++) {
                    for (int cz = (c[2] + oz - 1) >> 4; cz <= (c[5] + oz + 1) >> 4; cz++) {
                        esperado.add(cx + ":" + cz);
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String e : esperado) {
                sb.append(sb.length() == 0 ? "" : " ").append(e);
            }
            v.igual(false, r, "cola/chunks " + dirs[i] + " " + dist + ": com tudo carregado o pedido passa");
            v.igual(sb.toString(), perguntados.invoke(null),
                    "cola/chunks " + dirs[i] + " " + dist + ": os chunks perguntados sao exatamente os da origem e do destino, alargados em 1 bloco");
        }
        limpa.invoke(null);

        // chunks fora do carregado: so os da origem carregados (caixa 4x4x4 na origem, SUL 60 e LESTE 60 saem; NORTE 10 fica)
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                provedorC.getMethod("carregar", int.class, int.class).invoke(null, cx, cz);
            }
        }
        Object quatro = ctCaixa.newInstance(0, 60, 0, 3, 63, 3);
        ChromatiCraft.decisao = new DecisaoShift(0, false, 0, true, true);
        v.igual(true, recusar.invoke(null, mundo, quatro, direcaoC.getField("SOUTH").get(null), 60, jogador), "cola/chunks: SUL 60 sai do carregado em z: recusa");
        v.igual(true, recusar.invoke(null, mundo, quatro, direcaoC.getField("EAST").get(null), 60, jogador), "cola/chunks: LESTE 60 sai do carregado em x: recusa");
        v.igual(true, recusar.invoke(null, mundo, quatro, direcaoC.getField("NORTH").get(null), 60, jogador), "cola/chunks: NORTE 60 sai do carregado em z: recusa");
        v.igual(true, recusar.invoke(null, mundo, quatro, direcaoC.getField("WEST").get(null), 60, jogador), "cola/chunks: OESTE 60 sai do carregado em x: recusa");
        v.igual(false, recusar.invoke(null, mundo, quatro, norte, 10, jogador), "cola/chunks: controle: NORTE 10 fica dentro do carregado e passa");
        v.igual(4L, ChromatiCraft.decisao.recusas(Motivo.CHUNKS), "cola/chunks: as 4 recusas contadas como chunks");

        // distancia: a potencia vem do mod e a distancia do pedido
        ChromatiCraft.decisao = new DecisaoShift(0, true, 0, false, false);
        reg.potencia = 5;
        v.igual(true, recusar.invoke(null, mundo, quatro, norte, 6, jogador), "cola/distancia: potencia 5 do mod, distancia 6: recusa");
        v.igual(false, recusar.invoke(null, mundo, quatro, norte, 5, jogador), "cola/distancia: potencia 5, distancia 5: passa");
        reg.potencia = 24;

        // medicao: aceito e demorado conta 1 lenta; recusa nao mede
        Method fim = ganchoC.getMethod("fimDaChamada", Class.forName("net.minecraft.entity.player.EntityPlayer", false, cl));
        ChromatiCraft.zerarContadores();
        ChromatiCraft.lentoMs = 1;
        ChromatiCraft.decisao = new DecisaoShift(0, true, 0, false, false);
        recusar.invoke(null, mundo, quatro, norte, 1, jogador);
        Thread.sleep(20);
        fim.invoke(null, jogador);
        v.igual(1L, ChromatiCraft.lentas, "cola/medicao: pedido aceito e demorado conta 1 lenta");
        fim.invoke(null, jogador);
        v.igual(1L, ChromatiCraft.lentas, "cola/medicao: o segundo fim, sem pedido, nao conta de novo");
        recusar.invoke(null, mundo, quatro, norte, 99, jogador);
        Thread.sleep(5);
        fim.invoke(null, jogador);
        v.igual(1L, ChromatiCraft.lentas, "cola/medicao: pedido recusado nao e medido");
        ChromatiCraft.lentoMs = 0;
        limpa.invoke(null);
    }

    /**
     * Gancho que NAO LIGA em tempo de execucao (aqui: o {@code GanchoShift} carregado nao tem os metodos {@code recusar},
     * {@code antesDoSom} e {@code antesDeTerminar}, embora o arquivo do jar os tenha e a transformacao tenha sido aplicada):
     * o {@code shiftArea} NAO pode sair com {@code NoSuchMethodError}; o corpo original roda (aqui quebra por falta de
     * mundo, com erro que nao e de ligacao), a falha e contada, e as duas pontes da medicao chamam a original.
     */
    static void ganchoQueNaoLiga(Verifica v, Class<?> alvo, ClassLoader cl) throws Exception {
        Class<?> mundoC = Class.forName("net.minecraft.world.WorldServer", false, cl);
        Class<?> jogadorC = Class.forName("net.minecraft.entity.player.EntityPlayerMP", false, cl);
        Class<?> caixaC = Class.forName("Reika.DragonAPI.Instantiable.Data.Immutable.BlockBox", true, cl);
        Class<?> direcaoC = Class.forName("net.minecraftforge.common.util.ForgeDirection", true, cl);
        Class<?> ganchoC = Class.forName("hoshiestar.chromaticraft.shift.GanchoShift", true, cl);
        boolean sumiu = false;
        try {
            ganchoC.getMethod("recusar", mundoC, caixaC, direcaoC, int.class, jogadorC);
        } catch (NoSuchMethodException e) {
            sumiu = true;
        }
        v.sim(sumiu, "controle: o GanchoShift carregado NAO tem 'recusar' (o gancho nao liga)");
        Method shift = alvo.getDeclaredMethod("shiftArea", mundoC, caixaC, direcaoC, int.class, jogadorC);
        shift.setAccessible(true);
        Object mundo = aloca(mundoC);
        Object jogador = aloca(jogadorC);
        Object caixa = caixaC.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class).newInstance(0, 60, 0, 3, 63, 3);
        Object cima = direcaoC.getField("UP").get(null);
        ChromatiCraft.decisao = new DecisaoShift(0, true, 1, false, false); // se o gancho ligasse, recusaria por volume
        ChromatiCraft.ativo = true;
        ChromatiCraft.zerarContadores();
        for (int vez = 1; vez <= 2; vez++) {
            Throwable t = null;
            Object r = null;
            try {
                r = shift.invoke(null, mundo, caixa, cima, 5, jogador);
            } catch (InvocationTargetException e) {
                t = e.getCause();
            }
            // o corpo original, sem mundo de verdade, quebra por outro motivo (ex.: a enumeracao do ChromatiCraft nao inicia
            // fora do servidor); o que NAO pode sair e o NoSuchMethodError do gancho que nao liga
            v.sim(t != null && !(t instanceof NoSuchMethodError) && estaNaPilha(t, "AbilityCalls", "shiftArea"),
                    "gancho que nao liga (pedido " + vez + "): o corpo ORIGINAL roda (quebra sem mundo, por outro motivo) e o NoSuchMethodError do gancho nao sai de shiftArea ("
                            + (t == null ? "devolveu " + r : t.getClass().getSimpleName()) + ")");
            v.igual((long) vez, ChromatiCraft.falhasDeGancho, "gancho que nao liga (pedido " + vez + "): a falha foi contada");
        }
        v.sim(ChromatiCraft.ultimaFalhaDeGancho != null && ChromatiCraft.ultimaFalhaDeGancho.contains("NoSuchMethodError"),
                "a ultima falha guardada e o NoSuchMethodError (" + ChromatiCraft.ultimaFalhaDeGancho + ")");
        v.igual(0L, ChromatiCraft.decisao.chamadas(), "a decisao nem foi consultada (o gancho nao ligou)");
        String linha = ChromatiCraft.linhaDeEstado();
        v.sim(linha.contains("GANCHOS FALHARAM EM EXECUCAO 2 vez(es)"), "a linha de estado mostra as falhas dos ganchos (" + linha.substring(Math.max(0, linha.length() - 160)) + ")");
        pontes(v, alvo, true);
        ChromatiCraft.decisao = null;
        ChromatiCraft.ativo = false;
        ChromatiCraft.zerarContadores();
    }

    /**
     * As duas pontes da medicao, chamadas direto com nulos: o gancho "antes..." roda (ou falha e e contado), e a chamada
     * ORIGINAL (fora do try) roda do mesmo jeito: com receptor nulo ela lanca NullPointerException, vista de DENTRO da
     * ponte. {@code esperaFalha}: o gancho nao liga, entao cada ponte conta uma falha; senao nenhuma.
     */
    static void pontes(Verifica v, Class<?> alvo, boolean esperaFalha) throws Exception {
        long antes = ChromatiCraft.falhasDeGancho;
        int feitas = 0;
        for (Method m : alvo.getDeclaredMethods()) {
            if (!m.getName().startsWith("hoshiestar$envolve$")) {
                continue;
            }
            m.setAccessible(true);
            Object[] args = new Object[m.getParameterTypes().length];
            if (args.length == 3) {
                args[2] = Boolean.FALSE; // o boolean de setToPlayer
            }
            Throwable t = null;
            try {
                m.invoke(null, args);
            } catch (InvocationTargetException e) {
                t = e.getCause();
            }
            v.sim(t instanceof NullPointerException && t.getStackTrace().length > 0 && t.getStackTrace()[0].getMethodName().equals(m.getName()),
                    "ponte " + m.getName() + (esperaFalha ? " (gancho nao liga)" : "") + ": a chamada ORIGINAL roda dentro da ponte (NullPointerException do receptor nulo) ("
                            + (t == null ? "nada lancado" : t.getClass().getSimpleName()) + ")");
            feitas++;
        }
        v.igual(2, feitas, "as 2 pontes foram chamadas");
        v.igual(esperaFalha ? antes + 2 : antes, ChromatiCraft.falhasDeGancho,
                "pontes: " + (esperaFalha ? "cada uma contou a falha do gancho" : "nenhuma falha de gancho (o gancho ligou)"));
    }

    /** A pilha da excecao tem um quadro da classe (nome termina com {@code classe}) e do metodo dado? */
    static boolean estaNaPilha(Throwable t, String classe, String metodo) {
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getClassName().endsWith(classe) && e.getMethodName().equals(metodo)) {
                return true;
            }
        }
        return false;
    }
}
