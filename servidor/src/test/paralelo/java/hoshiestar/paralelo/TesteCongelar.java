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

/**
 * Logica do {@code hoshie paralelo congelar} (passo 2), sem Minecraft. Roda duas vezes: com
 * {@code -Dhoshiestar.paralelo.teste=true} e sem a propriedade ({@code TesteCongelar <true|false>}).
 */
public final class TesteCongelar {

    public static void main(String[] args) {
        Verifica v = new Verifica("TesteCongelar (teste=" + args[0] + ")");
        try {
            boolean esperado = Boolean.parseBoolean(args[0]);
            v.igual(esperado, Congelar.TESTE, "Congelar.TESTE segue a propriedade -Dhoshiestar.paralelo.teste");
            if (esperado) {
                ligado(v);
            } else {
                desligado(v);
            }
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    static void desligado(Verifica v) {
        String[] r = ComandoParalelo.executar(new String[] {"congelar", "100"});
        v.sim(r.length > 0 && r[0].startsWith("uso:"), "sem a propriedade, 'congelar' nao existe no comando (cai no uso): " + r[0]);
        v.sim(!ComandoParalelo.ajuda().contains("congelar"), "sem a propriedade, a ajuda nao cita congelar");
        v.sim(Congelar.pedir(100).contains("-D"), "pedir sem a propriedade recusa");
        v.igual(-1L, Congelar.alvo(), "nenhum alvo guardado");
        // passo 4: injetar quebrar tambem so existe com a propriedade
        String[] q = ComandoParalelo.executar(new String[] {"injetar", "quebrar"});
        v.sim(q.length > 0 && q[0].startsWith("uso:"), "sem a propriedade, 'injetar' nao existe no comando: " + q[0]);
        v.sim(!ComandoParalelo.ajuda().contains("injetar"), "sem a propriedade, a ajuda nao cita injetar");
        v.sim(Injecao.pedirQuebrar(null).contains("-D"), "pedir quebrar sem a propriedade recusa");
        v.sim(Injecao.tirarQuebrar() == null, "nenhum pedido de quebrar guardado");
    }

    static void ligado(Verifica v) {
        Congelar.zerarParaTeste();
        v.sim(ComandoParalelo.ajuda().contains("congelar"), "com a propriedade, a ajuda cita congelar");
        // passo 4: injetar quebrar (o comando so anota; a thread do servidor tira o pedido)
        v.sim(ComandoParalelo.ajuda().contains("injetar quebrar"), "com a propriedade, a ajuda cita injetar quebrar");
        String[] q = ComandoParalelo.executar(new String[] {"injetar", "quebrar", "1", "2", "3"});
        v.sim(q[0].contains("pedido anotado") && q[0].contains("(1, 2, 3)"), "injetar quebrar x y z: anotado: " + q[0]);
        int[] iq = Injecao.tirarQuebrar();
        v.sim(iq != null && iq.length == 3 && iq[0] == 1 && iq[2] == 3, "o servidor tira o pedido com as coordenadas");
        v.sim(Injecao.tirarQuebrar() == null, "tirado uma vez so");
        ComandoParalelo.executar(new String[] {"injetar", "quebrar"});
        iq = Injecao.tirarQuebrar();
        v.sim(iq != null && iq.length == 0, "sem coordenadas: pedido vazio (o servidor escolhe)");
        Injecao.devolver(iq);
        v.sim(Injecao.tirarQuebrar() != null, "pedido devolvido (sem tile no tick) volta para a fila");
        v.sim(ComandoParalelo.executar(new String[] {"injetar", "quebrar", "x", "1", "2"})[0].startsWith("uso:"), "coordenada invalida: uso");
        v.sim(ComandoParalelo.executar(new String[] {"injetar", "erro"})[0].startsWith("uso:"), "injetar erro (passo 6): uso");
        // antes do primeiro tick: recusa
        v.sim(Congelar.pedir(100).contains("ainda nao comecou"), "pedido antes do primeiro tick dos mundos e recusado");
        v.igual(-1L, Congelar.alvo(), "pedido recusado nao guarda alvo");
        // sem pedido, o gancho nunca congela
        for (long t = 0; t < 50; t++) {
            v.sim(!Congelar.antesDosMundos(t), "sem pedido nao congela (t=" + t + ")");
        }
        v.sim(!Congelar.congelado(), "nao congelado sem pedido");
        v.igual(49L, Congelar.ultimo(), "ultimo tick visto");
        // tick que ja passou ou proximo demais
        v.sim(Congelar.pedir(49).contains("ja passou"), "tick atual recusado");
        v.sim(Congelar.pedir(50).contains("ja passou"), "tick seguinte (proximo gancho) recusado");
        v.sim(!Congelar.pedir(51).contains("ja passou"), "ultimo + 2 aceito");
        v.igual(51L, Congelar.alvo(), "alvo guardado");
        // troca de pedido
        String r = Congelar.pedir(60);
        v.sim(r.contains("trocado"), "segundo pedido troca o primeiro: " + r);
        v.igual(60L, Congelar.alvo(), "alvo trocado");
        // pelo comando (texto)
        String[] c = ComandoParalelo.executar(new String[] {"congelar", "x"});
        v.sim(c[0].startsWith("uso:"), "numero invalido mostra o uso");
        c = ComandoParalelo.executar(new String[] {"congelar"});
        v.sim(c[0].contains("pedido para o tick 60"), "estado sem argumento mostra o pedido: " + c[0]);
        // o gancho anda ate o alvo: congela exatamente no tick 60, uma vez
        int vezes = 0;
        long em = -1;
        for (long t = 50; t <= 80; t++) {
            if (Congelar.antesDosMundos(t)) {
                vezes++;
                em = t;
            }
            v.igual(t >= 60, Congelar.congelado(), "congelado so a partir do tick 60 (t=" + t + ")");
        }
        v.igual(1, vezes, "o gancho manda salvar uma vez so");
        v.igual(60L, em, "congela no tick pedido");
        v.igual(60L, Congelar.congeladoEm(), "congeladoEm");
        v.sim(Congelar.pedir(200).contains("ja congelado"), "depois de congelar nao aceita outro pedido");
        v.igual(60L, Congelar.alvo(), "alvo nao muda depois de congelar");
        v.sim(Congelar.estado().startsWith("congelado no tick 60"), "estado congelado: " + Congelar.estado());
        // tick saltado (o mundo 0 pulou o alvo): congela no primeiro tick >= alvo e diz onde
        Congelar.zerarParaTeste();
        Congelar.antesDosMundos(10);
        Congelar.pedir(20);
        v.sim(!Congelar.antesDosMundos(19), "antes do alvo nao congela");
        v.sim(Congelar.antesDosMundos(25), "tick acima do alvo congela");
        v.igual(25L, Congelar.congeladoEm(), "congeladoEm diz o tick real (o log mostra pedido e real)");
        Congelar.zerarParaTeste();
    }
}
