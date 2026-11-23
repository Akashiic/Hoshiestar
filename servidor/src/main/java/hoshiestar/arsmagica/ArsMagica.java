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
package hoshiestar.arsmagica;

import hoshiestar.api.HashClasse;
import hoshiestar.api.Log;
import hoshiestar.api.Recursos;
import hoshiestar.api.Trava;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.Chamada;
import hoshiestar.embutidos.asm.Gancho;
import hoshiestar.embutidos.asm.PadraoNaoEncontrado;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Recurso "arsmagica" (item 1.F): dois atalhos EXATOS no {@code am2.AMEventHandler} do Ars Magica 2.
 *
 * <p><b>O problema</b> (perfil de producao: {@code onEntityLiving} do AM2 = 3,57% do tick).
 * <ol>
 * <li>F1: {@code HandlePlayerSpellExtendedProperties} (so jogadores, uma vez por pacote de movimento de
 * cada jogador) percorre a lista de entidades de TODOS os mundos carregados e monta o texto do UUID de
 * cada entidade viva, so para achar as que estao aceleradas pelo feitico "Accelerate". O mapa em que
 * procura ({@code acceleratedEntitiesUUIDs}) esta vazio quase sempre. Custo atribuido: ate 2,08%.</li>
 * <li>F2: no inicio de {@code onEntityLiving} (toda entidade viva, todo tick) o AM2 consulta a habilidade
 * "DiluteTime" (copiando listas de arvore de habilidades) antes de olhar o mapa de retardadas
 * ({@code slowedEntitiesUUIDs}), tambem quase sempre vazio. Custo atribuido: cerca de 0,35%.</li>
 * </ol>
 * Esses numeros vem do perfil do spark e de inferencia (ver o doc); o ganho real so o teste no servidor mostra.
 *
 * <p><b>A correcao</b> (formato "envolver uma chamada" da base). A chamada que dispara o trabalho passa a ir
 * por uma ponte que pergunta ao gancho se pode pular (so quando o mapa certo esta vazio); senao chama a
 * original com os mesmos argumentos. A ponte e PROTEGIDA ({@link Asm#envolverChamadaProtegida}): se um gancho
 * falhar (classe ou metodo que nao liga, excecao), a ponte segue pela chamada original e {@link Atalhos#falhaVarredura}
 * ou {@link Atalhos#falhaDiluicao} desliga o atalho e avisa no log; o erro nao sai de dentro do metodo do AM2.
 * Alem disso, ao ligar o servidor {@link #verificarGanchos} confere por reflexao, com as classes de tempo de
 * execucao, que os ganchos ligam com os descritores que as pontes usam. F1 envolve a UNICA {@code DimensionManager.getWorlds()} de
 * {@code HandlePlayerSpellExtendedProperties}; F2 envolve a UNICA {@code SkillManager.getSkill(String)} e a
 * UNICA {@code SkillTreeManager.isSkillDisabled(...)} de {@code onEntityLiving} (as duas na mesma correcao:
 * ou entram as duas, ou nenhuma). Cada atalho tem seu interruptor, os dois desligados por padrao.
 *
 * <p>Roda na fase de coremod: so JDK, ASM e hoshiestar.* (nada do Minecraft, Forge ou AM2). A decisao
 * mora em {@link Atalhos}; o gancho que le os mapas do AM2 fica em src/mods/java.
 */
public final class ArsMagica {

    public static final String ID = Atalhos.ID;

    /** Classe alvo (nome com pontos). */
    public static final String CLASSE = "am2.AMEventHandler";

    /** SHA-256 de am2/AMEventHandler.class em AM2.5-LTS-1.6.11-LE-CR40.jar. */
    public static final String SHA_EVENTOS = "270c2ddf51bada28728b0f7eaa5ea7bc0f7d8248a0db3042f254c1f4155d04c3";

    /** Classe dos ganchos (src/mods/java). */
    public static final String GANCHO = "hoshiestar/arsmagica/atalho/GanchoAM2";

    /** Classe (so JDK) com os ganchos "aoFalhar" das pontes. */
    public static final String FALHAS = "hoshiestar/arsmagica/Atalhos";

    static final String METODO_JOGADOR = "HandlePlayerSpellExtendedProperties";
    static final String DESC_JOGADOR = "(Lnet/minecraft/entity/player/EntityPlayer;Lam2/playerextensions/ExtendedProperties;)V";
    static final String METODO_VIVO = "onEntityLiving";
    static final String DESC_VIVO = "(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V";

    static final Chamada GET_WORLDS = Chamada.de("net/minecraftforge/common/DimensionManager", "getWorlds",
            "()[Lnet/minecraft/world/WorldServer;");
    static final Chamada GET_SKILL = Chamada.de("am2/spell/SkillManager", "getSkill",
            "(Ljava/lang/String;)Lam2/api/spell/component/interfaces/ISkillTreeEntry;");
    static final Chamada IS_SKILL_DISABLED = Chamada.de("am2/spell/SkillTreeManager", "isSkillDisabled",
            "(Lam2/api/spell/component/interfaces/ISkillTreeEntry;)Z");

    /** As correcoes registradas no boot (para a linha de estado dizer se foram aplicadas). Null antes do registro. */
    static volatile Correcao correcaoVarredura;
    static volatile Correcao correcaoDiluicao;

    private ArsMagica() {
    }

    // ---------------------------------------------------------------- registro

    /** Chamado por hoshiestar.Catalogo na fase de coremod. */
    public static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
        int min = cfg.arsmagica_estatistica;
        if (min < 0) {
            Log.de(ID).a("arsmagica.estatistica = " + min + " negativo; usando 0 (nunca)");
            min = 0;
        }
        Atalhos.varredura = cfg.arsmagica_varredura;
        Atalhos.diluicao = cfg.arsmagica_diluicao;
        Atalhos.estatisticaMinutos = min;
        Atalhos.varreduraDesligadaPorErro = null;
        Atalhos.diluicaoDesligadaPorErro = null;
        Atalhos.ganchoComDefeito = null;
        boolean ligado = cfg.arsmagica_varredura || cfg.arsmagica_diluicao;
        Recursos.registrar(ID, "Ars Magica 2: pula a varredura de mundos e a checagem do DiluteTime quando os mapas do AM2 estao vazios", ligado)
                .estado(new Recursos.Estado() {
                    @Override
                    public String linha() {
                        return linhaDeEstado();
                    }
                });
        Correcao f1 = Correcao.nova(ID, "pula a varredura de todos os mundos em HandlePlayerSpellExtendedProperties com o mapa de aceleradas vazio",
                Trava.de(CLASSE, SHA_EVENTOS), cfg.arsmagica_varredura, new Correcao.Transformacao() {
                    @Override
                    public void aplicar(ClassNode classe, ResolvedorDeGanchos ganchos) throws PadraoNaoEncontrado {
                        transformarVarredura(classe, ganchos);
                    }
                });
        Correcao f2 = Correcao.nova(ID, "pula a consulta do DiluteTime em onEntityLiving com o mapa de retardadas vazio",
                Trava.de(CLASSE, SHA_EVENTOS), cfg.arsmagica_diluicao, new Correcao.Transformacao() {
                    @Override
                    public void aplicar(ClassNode classe, ResolvedorDeGanchos ganchos) throws PadraoNaoEncontrado {
                        transformarDiluicao(classe, ganchos);
                    }
                });
        correcaoVarredura = f1;
        correcaoDiluicao = f2;
        correcoes.adicionar(f1);
        correcoes.adicionar(f2);
    }

    /**
     * F1: a UNICA chamada a {@code DimensionManager.getWorlds()} do metodo passa por uma ponte; o gancho
     * {@code varrerMundos()} diz se chama o original; se nao, {@code semMundos()} devolve uma lista vazia
     * (o laco do AM2 faz zero voltas e segue para o mesmo ponto em que o original segue depois do laco).
     */
    static void transformarVarredura(ClassNode cn, ResolvedorDeGanchos ganchos) throws PadraoNaoEncontrado {
        Asm.envolverChamadaProtegida(cn, METODO_JOGADOR, DESC_JOGADOR, GET_WORLDS, 0, 1,
                Gancho.de(GANCHO, "varrerMundos"), Gancho.de(GANCHO, "semMundos"), Gancho.de(FALHAS, "falhaVarredura"), ganchos);
    }

    /**
     * F2: a UNICA chamada a {@code getSkill(String)} e a UNICA a {@code isSkillDisabled(...)} de
     * {@code onEntityLiving}. Cada utilitario confere tudo antes de mudar a arvore; se o segundo falhar,
     * a base descarta a arvore inteira (nada fica meio aplicado).
     */
    static void transformarDiluicao(ClassNode cn, ResolvedorDeGanchos ganchos) throws PadraoNaoEncontrado {
        Asm.envolverChamadaProtegida(cn, METODO_VIVO, DESC_VIVO, GET_SKILL, 0, 1,
                Gancho.de(GANCHO, "consultarSkill"), Gancho.de(GANCHO, "semSkill"), Gancho.de(FALHAS, "falhaDiluicao"), ganchos);
        Asm.envolverChamadaProtegida(cn, METODO_VIVO, DESC_VIVO, IS_SKILL_DISABLED, 0, 1,
                Gancho.de(GANCHO, "consultarDesativada"), Gancho.de(GANCHO, "semDesativada"), Gancho.de(FALHAS, "falhaDiluicao"), ganchos);
    }

    // ---------------------------------------------------------------- servidor de pe

    /** Registra o subcomando "hoshie arsmagica" se o AM2 existe. Chamado por Servidor.iniciando. */
    public static void aoIniciarServidor() {
        if (HashClasse.bytesDoArquivo(CLASSE, HashClasse.carregadorDosMods()) == null) {
            Log.de(ID).i("Ars Magica 2 ausente: subcomando 'hoshie arsmagica' nao registrado");
            return;
        }
        conferirGanchosDoServidor();
        hoshiestar.comando.ComandoHoshie.registrar(new ComandoArsMagica());
    }

    /**
     * Auto-checagem ao ligar o servidor (antes de qualquer jogador se mexer): se algum atalho foi pedido, confere
     * que os ganchos ligam com os tipos de tempo de execucao. Se nao ligarem, as pontes (que ja estao na classe do
     * AM2, se ela carregou) seguem pelo original a cada chamada (protegidas), mas isso custa uma excecao por chamada;
     * por isso o atalho fica desligado, o motivo vai para o log e para o 'hoshie status'.
     */
    static void conferirGanchosDoServidor() {
        Correcao a = correcaoVarredura;
        Correcao b = correcaoDiluicao;
        if (!((a != null && a.ligada) || (b != null && b.ligada))) {
            return; // nenhuma correcao pedida: nao ha ponte nem gancho a conferir
        }
        String motivo = verificarGanchos(HashClasse.carregadorDosMods());
        if (motivo == null) {
            Log.de(ID).i("ganchos conferidos com as classes de tempo de execucao: ligam com os descritores das pontes");
            return;
        }
        Atalhos.ganchoComDefeito = motivo;
        Atalhos.varredura = false;
        Atalhos.diluicao = false;
        Log.de(ID).e("GANCHOS COM DEFEITO: " + motivo + ". Os atalhos do Ars Magica 2 ficam DESLIGADOS; se a classe do AM2 ja foi"
                + " transformada, as pontes seguem pelo caminho original (uma excecao capturada por chamada). Desligue arsmagica.varredura"
                + " e arsmagica.diluicao no hoshiestar.yml e avise o projeto.", null);
    }

    /**
     * Confere por reflexao, no class loader dado (o dos mods), os seis ganchos que as pontes chamam e as chamadas
     * do Forge/AM2 que elas envolvem, com os tipos de TEMPO DE EXECUCAO: e o que a JVM faz ao ligar a ponte (nome
     * e descritor exatos). Chama os tres ganchos "sem..." (sem efeito colateral) para ligar de fato o metodo;
     * os de decisao so sao procurados (chama-los contaria nas estatisticas).
     *
     * @return null se tudo confere; senao o motivo (uma linha)
     */
    static String verificarGanchos(ClassLoader cl) {
        String passo = "carregar o gancho";
        try {
            Class<?> g = Class.forName(GANCHO.replace('/', '.'), true, cl);
            passo = "carregar as classes do Minecraft, do Forge e do AM2";
            Class<?> mundo = Class.forName("net.minecraft.world.WorldServer", false, cl);
            Class<?> mundos = Array.newInstance(mundo, 0).getClass();
            Class<?> dimensoes = Class.forName("net.minecraftforge.common.DimensionManager", false, cl);
            Class<?> skills = Class.forName("am2.spell.SkillManager", false, cl);
            Class<?> arvore = Class.forName("am2.spell.SkillTreeManager", false, cl);
            Class<?> entrada = Class.forName("am2.api.spell.component.interfaces.ISkillTreeEntry", false, cl);
            passo = "conferir as chamadas envolvidas (getWorlds, getSkill, isSkillDisabled)";
            if (dimensoes.getMethod("getWorlds").getReturnType() != mundos) {
                return "DimensionManager.getWorlds() nao devolve WorldServer[] neste servidor";
            }
            if (skills.getMethod("getSkill", String.class).getReturnType() != entrada) {
                return "SkillManager.getSkill(String) nao devolve ISkillTreeEntry";
            }
            if (arvore.getMethod("isSkillDisabled", entrada).getReturnType() != boolean.class) {
                return "SkillTreeManager.isSkillDisabled(ISkillTreeEntry) nao devolve boolean";
            }
            passo = "conferir os ganchos de decisao";
            gancho(g, "varrerMundos", boolean.class);
            gancho(g, "consultarSkill", boolean.class, skills, String.class);
            gancho(g, "consultarDesativada", boolean.class, arvore, entrada);
            passo = "conferir e chamar os ganchos 'sem...'";
            Object vazio = gancho(g, "semMundos", mundos).invoke(null);
            if (vazio == null || Array.getLength(vazio) != 0) {
                return "semMundos() nao devolveu um array vazio";
            }
            if (gancho(g, "semSkill", entrada, skills, String.class).invoke(null, (Object) null, null) != null) {
                return "semSkill(...) nao devolveu null";
            }
            if (!Boolean.TRUE.equals(gancho(g, "semDesativada", boolean.class, arvore, entrada).invoke(null, (Object) null, null))) {
                return "semDesativada(...) nao devolveu true";
            }
            passo = "conferir os ganchos 'aoFalhar'";
            Class<?> falhas = Class.forName(FALHAS.replace('/', '.'), false, cl);
            gancho(falhas, "falhaVarredura", void.class, Throwable.class);
            gancho(falhas, "falhaDiluicao", void.class, Throwable.class);
            return null;
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            return "falhou ao " + passo + ": " + c;
        }
    }

    /** O metodo publico e estatico do gancho com este nome, parametros e retorno exatos (senao lanca). */
    private static Method gancho(Class<?> g, String nome, Class<?> retorno, Class<?>... params) throws NoSuchMethodException {
        Method m = g.getMethod(nome, params);
        if (m.getReturnType() != retorno || !Modifier.isStatic(m.getModifiers())) {
            throw new NoSuchMethodException(g.getName() + "." + nome + " devolve " + m.getReturnType().getName() + " (esperado "
                    + retorno.getName() + ") ou nao e static");
        }
        return m;
    }

    // ---------------------------------------------------------------- estado

    /** Linha do "hoshie status", do fim do boot e do comando. */
    public static String linhaDeEstado() {
        StringBuilder sb = new StringBuilder();
        sb.append("varredura de mundos (F1) ").append(Atalhos.varredura ? "ligada" : "desligada")
                .append(textoDaCorrecao(correcaoVarredura, Atalhos.varredura, Atalhos.varreduraDesligadaPorErro));
        sb.append("; checagem DiluteTime (F2) ").append(Atalhos.diluicao ? "ligada" : "desligada")
                .append(textoDaCorrecao(correcaoDiluicao, Atalhos.diluicao, Atalhos.diluicaoDesligadaPorErro));
        if (Atalhos.ganchoComDefeito != null) {
            sb.append("; GANCHOS COM DEFEITO (atalhos desligados; as pontes seguem pelo original): ").append(Atalhos.ganchoComDefeito);
        }
        sb.append("; ").append(Atalhos.resumoDosContadores());
        sb.append("; estatistica a cada ").append(Atalhos.estatisticaMinutos).append(" min");
        return sb.toString();
    }

    /**
     * Texto de um atalho: se o interruptor esta ligado (no boot) mas a correcao NAO foi aplicada (trava,
     * padrao, gancho ausente, erro) ou ainda aguarda a classe, diz que o atalho NAO roda. Aplicada: so
     * "(correcao aplicada)". Interruptor desligado no boot: nada (a linha da correcao, a parte, explica).
     */
    static String textoDaCorrecao(Correcao k, boolean interruptor, String desligadoPorErro) {
        StringBuilder sb = new StringBuilder();
        if (k != null && k.ligada) {
            Correcao.Estado e = k.estado();
            if (e == Correcao.Estado.APLICADA) {
                sb.append(" (correcao aplicada)");
            } else if (e == Correcao.Estado.AGUARDANDO) {
                sb.append(" (correcao aguardando a classe do AM2 ser carregada: o atalho ainda NAO roda)");
            } else {
                String d = k.detalhe();
                if (d != null && d.length() > 160) {
                    d = d.substring(0, 160) + "...";
                }
                sb.append(" (correcao ").append(e.texto).append(d == null ? "" : ": " + d).append("; o atalho NAO roda, o AM2 segue original)");
            }
        }
        if (desligadoPorErro != null) {
            sb.append(" (DESLIGADO POR ERRO: ").append(desligadoPorErro).append(")");
        }
        return sb.toString();
    }

    /** true se a correcao foi aplicada na classe (so entao ligar o interruptor em execucao tem efeito). */
    static boolean aplicada(Correcao k) {
        return k != null && k.estado() == Correcao.Estado.APLICADA;
    }
}
