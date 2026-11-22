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
package hoshiestar.teste;

import hoshiestar.api.HashClasse;
import hoshiestar.api.Trava;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.embutidos.TransformadorHoshiestar;
import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.Gancho;
import hoshiestar.embutidos.asm.PadraoNaoEncontrado;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;

/**
 * Trava de versao e o registro de correcoes: trava certa aplica, errada recusa sem tocar nos bytes,
 * excecao e padrao ausente devolvem os bytes originais, correcao desligada nem entra no mapa.
 *
 * <p>Argumento: a pasta com amostra/Alvo.class (versao Java 8).
 */
public final class TesteTrava {

    private static final String G = "hoshiestar/teste/ganchos/GanchosTeste";
    private static final String DESC = "(ILjava/lang/String;)V";

    public static void main(String[] a) throws Exception {
        Verifica v = new Verifica("TesteTrava");
        try {
            rodar(v, new File(a[0]));
        } catch (Throwable t) {
            v.erro("erro inesperado", t);
        }
        v.fim();
    }

    private static void rodar(Verifica v, File pasta) throws Exception {
        File arq = new File(pasta, "amostra/Alvo.class");
        byte[] bytes = Verifica.ler(new FileInputStream(arq));
        String hash = HashClasse.sha256(bytes);
        ResolvedorDeGanchos ganchos = new ResolvedorDeGanchos(TesteTrava.class.getClassLoader());
        ClassLoader doArquivo = new java.net.URLClassLoader(new java.net.URL[] {pasta.toURI().toURL()}, null);

        // --- Trava
        v.sim(Trava.de("amostra.Alvo", hash).aceita(hash), "trava aceita o hash certo");
        v.sim(Trava.de("amostra.Alvo", hash.toUpperCase()).aceita(hash), "trava aceita hash em maiusculas");
        v.sim(!Trava.de("amostra.Alvo", zeros()).confere(bytes), "trava errada nao confere");
        lanca(v, "trava sem hash", () -> Trava.de("amostra.Alvo"));
        lanca(v, "trava com hash curto", () -> Trava.de("amostra.Alvo", "abc"));
        lanca(v, "trava com nome com barras", () -> Trava.de("amostra/Alvo", zeros()));

        // --- HashClasse
        HashClasse.Resultado r = HashClasse.doArquivo("amostra.Alvo", doArquivo);
        v.igual(hash, r.sha256, "HashClasse.doArquivo = sha256 do arquivo");
        HashClasse.Resultado r2 = HashClasse.doArquivo("nao.Existe", doArquivo);
        v.sim(r2.sha256 == null && r2.erro != null, "HashClasse de classe inexistente da erro, sem excecao");
        // origem para jogador: so o nome do jar, sem o caminho do servidor (achado R5 da revisao)
        HashClasse.Resultado r3 = HashClasse.doArquivo("org.objectweb.asm.ClassReader", TesteTrava.class.getClassLoader());
        v.sim(r3.origem != null && r3.origem.startsWith("jar:file:") && r3.origem.contains("/"), "HashClasse de classe em jar: origem completa (" + r3.origem + ")");
        v.sim(r3.origemCurta() != null && r3.origemCurta().endsWith(".jar") && r3.origemCurta().indexOf('/') < 0,
                "HashClasse: origem curta e so o nome do jar (" + r3.origemCurta() + ")");
        v.sim(r3.descrever(false).indexOf('/') < 0 && r3.descrever(false).contains(r3.sha256),
                "HashClasse.descrever(false) sem caminho de pasta (" + r3.descrever(false) + ")");
        v.sim(r3.descrever(true).contains(r3.origem), "HashClasse.descrever(true) com o caminho completo");

        // --- trava certa: aplica
        Correcoes reg = Correcoes.novoParaTeste();
        Correcao certa = Correcao.nova("t", "certa", Trava.de("amostra.Alvo", hash), true,
                (cn, g) -> Asm.ganchoNoInicio(cn, "fazer", DESC, Gancho.de(G, "inicioFazer"), null, false, g));
        reg.adicionar(certa);
        reg.fechar();
        byte[] saida = reg.aplicar("amostra.Alvo", bytes, ganchos, doArquivo);
        v.sim(!Arrays.equals(bytes, saida), "trava certa: bytes alterados");
        v.igual(Correcao.Estado.APLICADA, certa.estado(), "trava certa: estado APLICADA");
        v.sim(temChamada(saida, "inicioFazer"), "trava certa: chamada ao gancho presente");
        v.sim(reg.aplicar("outra.Classe", bytes, ganchos, doArquivo) == bytes, "classe sem correcao: mesmo array devolvido");

        // --- trava errada: recusa
        reg = Correcoes.novoParaTeste();
        Correcao errada = Correcao.nova("t", "errada", Trava.de("amostra.Alvo", zeros()), true,
                (cn, g) -> Asm.ganchoNoInicio(cn, "fazer", DESC, Gancho.de(G, "inicioFazer"), null, false, g));
        reg.adicionar(errada);
        reg.fechar();
        saida = reg.aplicar("amostra.Alvo", bytes, ganchos, doArquivo);
        v.sim(Arrays.equals(bytes, saida), "trava errada: bytes intocados");
        v.igual(Correcao.Estado.RECUSADA_TRAVA, errada.estado(), "trava errada: estado RECUSADA_TRAVA");
        v.igual(hash, errada.hashVisto(), "trava errada: hash visto registrado");
        v.igual(Boolean.TRUE, errada.igualAoArquivo(), "trava errada: bytes iguais ao arquivo");
        v.sim(errada.linha().contains(hash), "trava errada: a linha do log mostra o hash visto");
        byte[] mexidos = bytes.clone();
        mexidos[mexidos.length - 1] ^= 1;
        reg.aplicar("amostra.Alvo", mexidos, ganchos, doArquivo);
        v.igual(Boolean.FALSE, errada.igualAoArquivo(), "bytes mexidos por outro transformador: detectado");

        // --- padrao nao encontrado: bytes originais
        reg = Correcoes.novoParaTeste();
        Correcao semPadrao = Correcao.nova("t", "sem padrao", Trava.de("amostra.Alvo", hash), true,
                (cn, g) -> Asm.ganchoNoInicio(cn, "naoExiste", "()V", Gancho.de(G, "inicioFazer"), null, false, g));
        reg.adicionar(semPadrao);
        reg.fechar();
        saida = reg.aplicar("amostra.Alvo", bytes, ganchos, doArquivo);
        v.sim(Arrays.equals(bytes, saida), "padrao ausente: bytes intocados");
        v.igual(Correcao.Estado.NAO_APLICADA, semPadrao.estado(), "padrao ausente: estado NAO_APLICADA");

        // --- transformacao que muda a arvore e depois desiste: nada fica
        reg = Correcoes.novoParaTeste();
        Correcao meia = Correcao.nova("t", "meio caminho", Trava.de("amostra.Alvo", hash), true, (cn, g) -> {
            cn.methods.add(new MethodNode(1, "lixo", "()V", null, null));
            throw new PadraoNaoEncontrado("desisti no meio");
        });
        reg.adicionar(meia);
        reg.fechar();
        saida = reg.aplicar("amostra.Alvo", bytes, ganchos, doArquivo);
        v.sim(Arrays.equals(bytes, saida), "desistencia no meio: bytes intocados");

        // --- excecao inesperada: bytes originais
        reg = Correcoes.novoParaTeste();
        Correcao explode = Correcao.nova("t", "explode", Trava.de("amostra.Alvo", hash), true, (cn, g) -> {
            throw new IllegalStateException("bum");
        });
        reg.adicionar(explode);
        reg.fechar();
        saida = reg.aplicar("amostra.Alvo", bytes, ganchos, doArquivo);
        v.sim(Arrays.equals(bytes, saida), "excecao: bytes intocados");
        v.igual(Correcao.Estado.ERRO, explode.estado(), "excecao: estado ERRO");

        // --- duas na mesma classe: errada recusada, certa aplicada sobre os originais
        reg = Correcoes.novoParaTeste();
        Correcao e2 = Correcao.nova("t", "errada2", Trava.de("amostra.Alvo", zeros()), true,
                (cn, g) -> Asm.ganchoNoInicio(cn, "laco", "(I)I", Gancho.de(G, "pularLaco"), Gancho.de(G, "valorLaco"), true, g));
        Correcao c2 = Correcao.nova("t", "certa2", Trava.de("amostra.Alvo", hash), true,
                (cn, g) -> Asm.ganchoNoInicio(cn, "fazer", DESC, Gancho.de(G, "inicioFazer"), null, false, g));
        Correcao c3 = Correcao.nova("t", "certa3", Trava.de("amostra.Alvo", hash), true,
                (cn, g) -> Asm.ganchoNoInicio(cn, "energia", "()D", Gancho.de(G, "pularEnergia"), Gancho.de(G, "valorEnergia"), true, g));
        reg.adicionar(e2);
        reg.adicionar(c2);
        reg.adicionar(c3);
        reg.fechar();
        saida = reg.aplicar("amostra.Alvo", bytes, ganchos, doArquivo);
        v.sim(e2.estado() == Correcao.Estado.RECUSADA_TRAVA && c2.estado() == Correcao.Estado.APLICADA
                && c3.estado() == Correcao.Estado.APLICADA, "tres na mesma classe: uma recusada, duas aplicadas");
        v.sim(temChamada(saida, "inicioFazer") && temChamada(saida, "pularEnergia") && !temChamada(saida, "pularLaco"),
                "tres na mesma classe: so as duas certas no bytecode");

        // --- desligada: nem entra no mapa
        reg = Correcoes.novoParaTeste();
        Correcao desl = Correcao.nova("t", "desligada", Trava.de("amostra.Alvo", hash), false,
                (cn, g) -> Asm.ganchoNoInicio(cn, "fazer", DESC, Gancho.de(G, "inicioFazer"), null, false, g));
        reg.adicionar(desl);
        reg.fechar();
        v.sim(reg.ligadasPara("amostra.Alvo") == null, "desligada: fora do mapa do transformador");
        v.sim(reg.aplicar("amostra.Alvo", bytes, ganchos, doArquivo) == bytes, "desligada: mesmo array devolvido");
        v.igual(Correcao.Estado.DESLIGADA, desl.estado(), "desligada: estado DESLIGADA");
        try {
            reg.adicionar(desl);
            v.sim(false, "registro fechado recusa adicionar");
        } catch (IllegalStateException e) {
            v.sim(true, "registro fechado recusa adicionar");
        }

        // --- o transformador do servidor (registro global vazio aqui): devolve o mesmo array
        TransformadorHoshiestar t = new TransformadorHoshiestar();
        v.sim(t.transform("amostra.Alvo", "amostra.Alvo", bytes) == bytes, "transformador sem correcoes: mesmo array");
        v.sim(t.transform("x", "x", null) == null, "transformador com bytes nulos: null");
    }

    private static boolean temChamada(byte[] b, String nome) {
        for (MethodNode m : Asm.ler(b).methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof org.objectweb.asm.tree.MethodInsnNode && ((org.objectweb.asm.tree.MethodInsnNode) n).name.equals(nome)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String zeros() {
        char[] c = new char[64];
        Arrays.fill(c, '0');
        return new String(c);
    }

    interface Acao {
        void rodar() throws Exception;
    }

    private static void lanca(Verifica v, String o, Acao a) {
        try {
            a.rodar();
            v.sim(false, o + ": deveria recusar");
        } catch (IllegalArgumentException e) {
            v.sim(true, o + ": recusado (" + e.getMessage() + ")");
        } catch (Exception e) {
            v.erro(o, e);
        }
    }
}
