package hoshiestar.observador;

import hoshiestar.regioes.FotoMundo;
import hoshiestar.regioes.MapaSecoes;
import hoshiestar.teste.Verifica;
import teste.moda.Chamador;

import java.util.Map;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Testa o nucleo do observador com um mundo falso: acesso dentro da regiao, no vizinho, distante, em outra dimensao,
 * sem origem; agregacao exata; tetos; amostra de pilha; pilha de contextos; regra de juncao; carga de chunk; valvula;
 * relatorio (json valido, sem dado pessoal).
 */
public final class TesteObservadorNucleo {

    static final class ModAMaquina {
    }

    static final class ModBMaquina {
    }

    /** Donos falsos: ModA -> moda.jar (tambem o pacote teste.moda.), ModB -> modb.jar. */
    static final Pilha.Donos DONOS = new Pilha.Donos() {
        @Override
        public String dono(String c) {
            if (c.startsWith("teste.moda.") || c.contains("TesteObservadorNucleo$ModA")) {
                return "moda.jar";
            }
            if (c.contains("TesteObservadorNucleo$ModB")) {
                return "modb.jar";
            }
            return null;
        }
    };

    /** Relogio falso: cada leitura avanca 100 ns (assim a amostra de custo mede 100 ns por acesso completo). */
    static final class RelogioFalso implements Nucleo.Relogio {
        long t;

        @Override
        public long nano() {
            t += 100;
            return t;
        }
    }

    static Nucleo novo(int amostra) {
        return new Nucleo(DONOS, amostra, 5.0, new RelogioFalso());
    }

    static final Object M0 = new Object();
    static final Object M1 = new Object();

    /** Foto: dimensao 0 com duas areas (secoes 0 e 1 = uma regiao; secao 12 = outra) e dimensao 1 com a secao 0. */
    static FotoMundo[] fotoPadrao() {
        FotoMundo d0 = new FotoMundo(0);
        for (int cx = 0; cx < 32; cx += 4) {
            d0.chunk(cx, 0);
        }
        d0.chunk(200, 5);
        d0.tile(5 * 16, 5 * 16);
        d0.tile(200 * 16, 5 * 16);
        d0.entidade(10, 10);
        FotoMundo d1 = new FotoMundo(1);
        d1.chunk(3, 3);
        return new FotoMundo[] {d0, d1};
    }

    static long t1(Nucleo n, int mc, int mp, int ct, int tipo, int faixa, int mesma, int reg, int cruz) {
        return n.t1.contDe(Nucleo.chaveT1(mc, mp, ct, tipo, faixa, mesma, reg, cruz));
    }

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteObservadorNucleo");
        try {
            tabela(v);
            contexto(v);
            acessos(v);
            agregacao(v);
            pilha(v);
            tetos(v);
            regioes(v);
            carga(v);
            valvula(v);
            relatorio(v);
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    // ------------------------------------------------------------------------------------------------

    static void tabela(Verifica v) {
        TabelaContagem t = new TabelaContagem(16, 10);
        for (int i = 0; i < 25; i++) {
            t.registrar(i, i);
        }
        v.igual(10, t.tamanho(), "tabela: o teto de 10 chaves vale");
        v.igual(15L, t.chavesRecusadas, "tabela: 15 chaves novas recusadas");
        v.igual(15L, t.outrosCont, "tabela: as 15 vao para o balde outros");
        v.igual(24L, t.outrosMax, "tabela: maximo do balde outros");
        v.sim(t.transbordou(), "tabela: transbordou() verdadeiro");
        t.registrar(3, 99);
        t.registrar(3, 1);
        v.igual(3L, t.contDe(3), "tabela: chave que ja existia continua contando depois do teto");
        v.igual(99L, t.maxDe(3), "tabela: guarda o maior valor");
        v.igual(27L, t.total(), "tabela: total = 25 registros + 2 extras da chave 3");
        TabelaContagem g = new TabelaContagem(16, 100000);
        for (int i = 0; i < 50000; i++) {
            g.somar((long) i * 2654435761L);
        }
        v.igual(50000, g.tamanho(), "tabela: cresce sem perder chave (50000)");
        long soma = 0;
        for (int i = 0; i < g.capacidade(); i++) {
            if (g.ocupado(i)) {
                soma += g.cont(i);
            }
        }
        v.igual(50000L, soma, "tabela: soma dos contadores = 50000");
        MapaSecoes m = new MapaSecoes(4);
        int a = m.criar(MapaSecoes.chave(2, -5, 7));
        int b = m.criar(MapaSecoes.chave(2, -5, 7));
        v.sim(a == b && m.tamanho() == 1, "mapa de secoes: a mesma chave da a mesma vaga");
        long k = MapaSecoes.chave(3, -117187, 117187);
        v.sim(MapaSecoes.dimDe(k) == 3 && MapaSecoes.xDe(k) == -117187 && MapaSecoes.zDe(k) == 117187,
                "mapa de secoes: chave com coordenadas negativas ida e volta");
        for (int i = 0; i < 1000; i++) {
            m.criar(MapaSecoes.chave(0, i, -i));
        }
        v.sim(m.vaga(MapaSecoes.chave(0, 999, -999)) >= 0 && m.vaga(MapaSecoes.chave(0, 1000, -1000)) < 0,
                "mapa de secoes: acha o que tem e nao acha o que nao tem (cresceu de 4 para 1000)");
        v.igual("(nenhum)", new Mods().nome(0), "mods: id 0 e 'nenhum'");
        Mods mods = new Mods();
        int x = mods.id("a.jar");
        v.sim(x >= 2 && mods.id("a.jar") == x && mods.id(null) == 0, "mods: mesmo nome, mesmo id; null = 0");
        for (int i = 0; i < Mods.MAX + 10; i++) {
            mods.id("m" + i);
        }
        v.igual(Mods.OUTROS, mods.id("novo.jar"), "mods: passou do limite de nomes vira 'outros'");
        v.sim(Pilha.pular("net.minecraft.world.World") && Pilha.pular("cpw.mods.fml.common.Loader")
                && Pilha.pular("hoshiestar.x.Y") && Pilha.pular("org.bukkit.Bukkit") && !Pilha.pular("ic2.core.Foo"),
                "pilha: pula Minecraft, Forge, Bukkit e Hoshiestar; nao pula mod");
    }

    static void contexto(Verifica v) {
        Nucleo n = novo(1000000);
        v.igual(Nucleo.C_OUTRO, n.contextoAtual(), "contexto: vazio = outro");
        n.entrar(Nucleo.C_TILE, M0, 0, 1, 1, ModAMaquina.class);
        n.entrar(Nucleo.C_TILE, M0, 0, 2, 2, ModAMaquina.class);
        v.igual(1, n.profundidade(), "contexto: entrar no mesmo tipo substitui (sobra de excecao)");
        n.entrar(Nucleo.C_EVENTO_BLOCO, M0, 0, Nucleo.SEM_ORIGEM, 0, null);
        v.igual(2, n.profundidade(), "contexto: tipos diferentes empilham");
        n.sair(Nucleo.C_TILE);
        v.igual(0, n.profundidade(), "contexto: sair do tipo de baixo leva junto o de cima");
        n.sair(Nucleo.C_REDE);
        v.igual(0, n.profundidade(), "contexto: sair de tipo que nao esta nao faz nada");
        for (int t = 1; t <= 12; t++) {
            n.entrar(t, M0, 0, 1, 1, null);
        }
        v.igual(Nucleo.PROF_MAX, n.profundidade(), "contexto: profundidade nunca passa de " + Nucleo.PROF_MAX);
        v.sim(n.transbordosPilha > 0, "contexto: transbordo contado");
        n.inicioTick(1000);
        v.igual(0, n.profundidade(), "contexto: o inicio do tick zera a pilha");
    }

    static void acessos(Verifica v) {
        Nucleo n = novo(1000000);
        n.tirarFoto(fotoPadrao());
        v.sim(n.foto() != null, "foto: tirada");
        int ma = n.mods.id("moda.jar");
        n.inicioTick(0);
        n.entrar(Nucleo.C_TILE, M0, 0, 5, 5, ModAMaquina.class);

        // dentro da mesma secao
        n.acesso(Nucleo.A_LER_BLOCO, M0, 0, 6, 6);
        n.acesso(Nucleo.A_LER_BLOCO, M0, 0, 15, 0);
        v.igual(2L, n.leiturasRapidas(), "mesma secao: a leitura so conta (rapida)");
        v.igual(0L, n.acessosCompletos(), "mesma secao: leitura nao entra na tabela T1");
        n.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, 6, 6);
        v.igual(1L, t1(n, ma, 0, Nucleo.C_TILE, Nucleo.A_ESCREVER_BLOCO, 1, 1, 1, 0),
                "mesma secao: a escrita entra (distancia 1 chunk = faixa 1-2, mesma secao, mesma regiao)");
        // vizinho: secao 1, mesma regiao (dist 15 chunks = faixa 9-16)
        n.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, 20, 5);
        v.igual(1L, t1(n, ma, 0, Nucleo.C_TILE, Nucleo.A_ESCREVER_BLOCO, 3, 0, 1, 0),
                "vizinho: outra secao, mesma regiao, faixa 9-16");
        v.igual(15L, n.t1.maxDe(Nucleo.chaveT1(ma, 0, Nucleo.C_TILE, Nucleo.A_ESCREVER_BLOCO, 3, 0, 1, 0)),
                "vizinho: distancia maxima 15 chunks");
        v.igual(0, n.t2.tamanho(), "vizinho da mesma regiao nao entra em T2");
        // leitura que cruza secao entra em T1
        n.acesso(Nucleo.A_LER_BLOCO, M0, 0, 20, 5);
        v.igual(1L, t1(n, ma, 0, Nucleo.C_TILE, Nucleo.A_LER_BLOCO, 3, 0, 1, 0), "leitura que cruza secao entra em T1");
        // distante: secao 12, outra regiao (195 chunks = 64+)
        n.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, 200, 5);
        v.igual(1L, t1(n, ma, 0, Nucleo.C_TILE, Nucleo.A_ESCREVER_BLOCO, 6, 0, 2, 0), "distante: faixa 64+, outra regiao");
        v.igual(1, n.t2.tamanho(), "distante: o par de secoes entra em T2");
        // fora da foto
        n.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, -100, 5);
        v.igual(1L, t1(n, ma, 0, Nucleo.C_TILE, Nucleo.A_ESCREVER_BLOCO, 6, 0, 3, 0), "fora da foto: relacao 'fora da foto'");
        v.igual(1, n.t2.tamanho(), "fora da foto nao entra em T2 (nao se sabe se cruza regiao)");
        // outra dimensao
        n.acesso(Nucleo.A_ESCREVER_BLOCO, M1, 1, 3, 3);
        v.igual(1L, t1(n, ma, 0, Nucleo.C_TILE, Nucleo.A_ESCREVER_BLOCO, 7, 0, 2, 1), "outra dimensao: cruzou dimensao, faixa n/a");
        v.igual(2, n.t2.tamanho(), "outra dimensao: o par entra em T2");
        // mesma secao em outra dimensao NAO e mesma secao
        n.acesso(Nucleo.A_ESCREVER_BLOCO, M1, 1, 5, 5);
        v.igual(2L, n.t1.contDe(Nucleo.chaveT1(ma, 0, Nucleo.C_TILE, Nucleo.A_ESCREVER_BLOCO, 7, 0, 2, 1)),
                "outra dimensao: as duas escritas somam na mesma linha");
        // mesmo chunk da origem em OUTRO mundo (mesma dimensao): a leitura nao e rapida
        long antes = n.leiturasRapidas();
        n.acesso(Nucleo.A_LER_BLOCO, M1, 0, 5, 5);
        v.igual(antes, n.leiturasRapidas(), "outro objeto de mundo: a leitura rapida nao vale");
        n.sair(Nucleo.C_TILE);

        // sem origem
        n.entrar(Nucleo.C_GLOBAL_PRE, null, 0, Nucleo.SEM_ORIGEM, 0, null);
        n.acesso(Nucleo.A_LER_BLOCO, M0, 0, 6, 6);
        v.igual(1L, n.semOrigemLeituras[Nucleo.C_GLOBAL_PRE], "sem origem: leitura so conta por tipo de contexto");
        n.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, 6, 6);
        v.igual(1L, t1(n, 0, 0, Nucleo.C_GLOBAL_PRE, Nucleo.A_ESCREVER_BLOCO, 7, 0, 0, 0), "sem origem: a escrita entra em T1 (faixa n/a)");
        n.sair(Nucleo.C_GLOBAL_PRE);
        n.acesso(Nucleo.A_LER_BLOCO, M0, 0, 6, 6);
        v.igual(1L, n.semOrigemLeituras[Nucleo.C_OUTRO], "pilha vazia: leitura conta como contexto 'outro'");

        // mod da classe vem do indice de donos, com cache por classe
        n.entrar(Nucleo.C_ENTIDADE, M0, 0, 5, 5, ModBMaquina.class);
        n.acesso(Nucleo.A_ADICIONAR_ENTIDADE, M0, 0, 20, 5);
        int mb = n.mods.id("modb.jar");
        v.igual(1L, t1(n, mb, 0, Nucleo.C_ENTIDADE, Nucleo.A_ADICIONAR_ENTIDADE, 3, 0, 1, 0), "mod do contexto: modb.jar vindo da classe da entidade");
        n.sair(Nucleo.C_ENTIDADE);
        // classe do jogo (sem dono) vira 'nenhum'
        n.entrar(Nucleo.C_ENTIDADE, M0, 0, 5, 5, String.class);
        n.acesso(Nucleo.A_ADICIONAR_ENTIDADE, M0, 0, 20, 5);
        v.igual(1L, t1(n, 0, 0, Nucleo.C_ENTIDADE, Nucleo.A_ADICIONAR_ENTIDADE, 3, 0, 1, 0), "classe sem dono: mod 'nenhum'");
        v.sim(n.acessosCompletos() > 5, "contagem de acessos completos andou");
    }

    static void agregacao(Verifica v) {
        Nucleo n = novo(1000000);
        n.tirarFoto(fotoPadrao());
        int ma = n.mods.id("moda.jar");
        n.inicioTick(0);
        n.entrar(Nucleo.C_TILE, M0, 0, 0, 0, ModAMaquina.class);
        for (int i = 0; i < 1000; i++) {
            n.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, 17 + (i % 5), 3);
        }
        long c = 0;
        for (int f = 0; f < 8; f++) {
            c += n.t1.contDe(Nucleo.chaveT1(ma, 0, Nucleo.C_TILE, Nucleo.A_ESCREVER_BLOCO, f, 0, 1, 0));
        }
        v.igual(1000L, c, "agregacao: 1000 acessos = 1000 na tabela");
        v.igual(21L, n.t1.maxDe(Nucleo.chaveT1(ma, 0, Nucleo.C_TILE, Nucleo.A_ESCREVER_BLOCO, 4, 0, 1, 0)),
                "agregacao: distancia maxima 21 chunks (faixa 17-48)");
        v.igual(1, n.t1.tamanho(), "agregacao: uma linha so (nao uma linha por acesso)");
        v.igual(1000L, n.porAcesso[Nucleo.A_ESCREVER_BLOCO], "agregacao: total por tipo de acesso");
    }

    static void pilha(Verifica v) {
        // 1 em 4 dos que cruzam secao
        Nucleo n = novo(4);
        n.tirarFoto(fotoPadrao());
        int ma = n.mods.id("moda.jar");
        int mb = n.mods.id("modb.jar");
        n.inicioTick(0);
        n.entrar(Nucleo.C_TILE, M0, 0, 0, 0, ModBMaquina.class);
        for (int i = 0; i < 40; i++) {
            Chamador.escrever(n, M0, 0, 20, 3);
        }
        v.igual(10L, n.amostrasDePilha(), "pilha: 1 em 4 de 40 acessos que cruzam secao = 10 amostras");
        long comPilha = 0;
        for (int f = 0; f < 8; f++) {
            comPilha += n.t1.contDe(Nucleo.chaveT1(mb, ma, Nucleo.C_TILE, Nucleo.A_ESCREVER_BLOCO, f, 0, 1, 0));
        }
        v.igual(10L, comPilha, "pilha: as amostras ficam em (mod do contexto modb, mod da pilha moda) = separados");
        long semPilha = 0;
        for (int f = 0; f < 8; f++) {
            semPilha += n.t1.contDe(Nucleo.chaveT1(mb, 0, Nucleo.C_TILE, Nucleo.A_ESCREVER_BLOCO, f, 0, 1, 0));
        }
        v.igual(30L, semPilha, "pilha: o resto fica sem amostra (mod da pilha 0)");
        // acesso dentro da secao nao amostra
        long antes = n.amostrasDePilha();
        for (int i = 0; i < 100; i++) {
            Chamador.escrever(n, M0, 0, 2, 3);
        }
        v.igual(antes, n.amostrasDePilha(), "pilha: acesso na mesma secao nao e amostrado");
        // teto de 20 por tick
        Nucleo m = novo(1);
        m.tirarFoto(fotoPadrao());
        m.inicioTick(0);
        m.entrar(Nucleo.C_TILE, M0, 0, 0, 0, ModBMaquina.class);
        for (int i = 0; i < 100; i++) {
            Chamador.escrever(m, M0, 0, 20, 3);
        }
        v.igual(20L, m.amostrasDePilha(), "pilha: no maximo 20 amostras por tick");
        m.fimTick(10000000);
        m.inicioTick(10000000);
        m.entrar(Nucleo.C_TILE, M0, 0, 0, 0, ModBMaquina.class);
        for (int i = 0; i < 100; i++) {
            Chamador.escrever(m, M0, 0, 20, 3);
        }
        v.igual(40L, m.amostrasDePilha(), "pilha: o teto e por tick (mais 20 no tick seguinte)");
        // contexto sem mod (global): tambem amostra
        Nucleo g = novo(1);
        g.inicioTick(0);
        g.entrar(Nucleo.C_GLOBAL_PRE, null, 0, Nucleo.SEM_ORIGEM, 0, null);
        Chamador.escrever(g, M0, 0, 20, 3);
        v.igual(1L, g.amostrasDePilha(), "pilha: contexto sem origem e sem mod tambem e amostrado");
        v.igual(1L, g.t1.contDe(Nucleo.chaveT1(0, g.mods.id("moda.jar"), Nucleo.C_GLOBAL_PRE, Nucleo.A_ESCREVER_BLOCO, 7, 0, 0, 0)),
                "pilha: handler global: o mod da pilha (moda) aparece");
        // pilha so com codigo do servidor: sem dono
        v.sim(Pilha.achar(new StackTraceElement[] {new StackTraceElement("net.minecraft.world.World", "x", "W.java", 1),
                new StackTraceElement("cpw.mods.fml.common.Loader", "y", "L.java", 2)}, DONOS) == null,
                "pilha: so quadros do servidor = sem dono");
        v.igual("modb.jar", Pilha.achar(new StackTraceElement[] {new StackTraceElement("net.minecraft.world.World", "x", "W.java", 1),
                new StackTraceElement("x.Biblioteca", "y", "L.java", 2),
                new StackTraceElement("teste.modb.TesteObservadorNucleo$ModBMaquina", "z", "M.java", 3)}, DONOS),
                "pilha: pula quadro sem dono e acha o primeiro com dono");
    }

    static void tetos(Verifica v) {
        Nucleo n = novo(1000000);
        // foto com 80 x 66 secoes espacadas de 4 (nenhuma junta com outra: cada uma e uma regiao) e uma longe demais para T2
        FotoMundo d0 = new FotoMundo(0);
        for (int sx = 0; sx < 80; sx++) {
            for (int sz = 0; sz < 66; sz++) {
                d0.chunk(sx * 64, sz * 64);
            }
        }
        d0.chunk(200000, 0);
        n.tirarFoto(new FotoMundo[] {d0});
        v.igual(80 * 66 + 1, n.foto().nRegioes, "teto: a foto de teste tem uma regiao por secao");
        n.inicioTick(0);
        n.entrar(Nucleo.C_TILE, M0, 0, 0, 0, ModAMaquina.class);
        int c = 0;
        for (int sx = 0; sx < 80 && c < 5200; sx++) {
            for (int sz = 0; sz < 66 && c < 5200; sz++) {
                if (sx == 0 && sz == 0) {
                    continue;
                }
                n.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, sx * 64, sz * 64);
                c++;
            }
        }
        v.igual(Nucleo.TETO_T2, n.t2.tamanho(), "teto: T2 para em " + Nucleo.TETO_T2 + " pares");
        v.igual(200L, n.t2.chavesRecusadas, "teto: 200 pares recusados contados");
        n.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, 200000, 0);
        v.igual(1L, n.t2ForaDaFaixa, "teto: coordenada fora da faixa de T2 e contada a parte");
        Relatorio.Meta m = new Relatorio.Meta();
        boolean aviso = false;
        for (String a : Relatorio.avisos(n, m)) {
            aviso |= a.contains("T2 passou do teto");
        }
        v.sim(aviso, "teto: o relatorio avisa que T2 passou do teto");
        v.sim(Relatorio.json(n, m).contains("\"t2_chaves_recusadas_pelo_teto\": 200"), "teto: o json traz as chaves recusadas");
    }

    static void regioes(Verifica v) {
        // regra de juncao: 3 secoes juntam, 4 nao
        v.igual(1, regs(sec(0, 0), sec(3, 0)), "juncao: secoes a 3 de distancia = 1 regiao");
        v.igual(2, regs(sec(0, 0), sec(4, 0)), "juncao: secoes a 4 de distancia = 2 regioes");
        v.igual(1, regs(sec(0, 0), sec(3, 3)), "juncao: diagonal (3,3) = 1 regiao (Chebyshev)");
        v.igual(2, regs(sec(0, 0), sec(3, 4)), "juncao: (3,4) = 2 regioes");
        v.igual(1, regs(sec(0, 0), sec(3, 0), sec(6, 0), sec(9, 1)), "juncao: cadeia de secoes = 1 regiao so");
        v.igual(1, regs(sec(-2, -2), sec(1, 1)), "juncao: coordenadas negativas");
        // dimensoes separadas
        FotoMundo a = new FotoMundo(0).chunk(0, 0);
        FotoMundo b = new FotoMundo(1).chunk(0, 0);
        Foto f = Regioes.calcular(new FotoMundo[] {a, b}, new int[] {0, 1}, 0, 0, 0);
        v.igual(2, f.nRegioes, "juncao: a mesma posicao em duas dimensoes = 2 regioes");
        v.sim(f.regiaoDe(0, 0, 0) >= 0 && f.regiaoDe(0, 0, 0) != f.regiaoDe(1, 0, 0), "juncao: regiao de cada dimensao separada");
        v.igual(-1, f.regiaoDe(0, 5, 5), "foto: secao sem chunk nao tem regiao");
        // recalculo com outra regra (secao 8x8): chunks 0 e 40
        FotoMundo c = new FotoMundo(0).chunk(0, 0).chunk(40, 0);
        v.igual(1, Regioes.calcular(new FotoMundo[] {c}, new int[] {0}, 3, 4, 0, 0, 0).nRegioes,
                "recalculo: com secao 16x16 os chunks 0 e 40 ficam juntos (secoes 0 e 2)");
        v.igual(2, Regioes.calcular(new FotoMundo[] {c}, new int[] {0}, 3, 3, 0, 0, 0).nRegioes,
                "recalculo: com secao 8x8 os mesmos chunks ficam em regioes diferentes (secoes 0 e 5)");
        v.igual(1, Regioes.calcular(new FotoMundo[] {c}, new int[] {0}, 5, 3, 0, 0, 0).nRegioes,
                "recalculo: com secao 8x8 e raio 5 voltam a juntar");
        // tiles e entidades por secao, e a distancia entre regioes com carga
        FotoMundo d = new FotoMundo(0).chunk(0, 0).chunk(16 * 8, 0).chunk(16 * 30, 0);
        d.tile(10, 10).tile(11, 11).tile(16 * 16 * 8 + 5, 5).entidade(7, 7).tile(99999999, 0);
        Foto g = Regioes.calcular(new FotoMundo[] {d}, new int[] {0}, 0, 0, 0);
        Foto.ResumoDim rd = g.resumo.dims.get(0);
        v.igual(3, rd.regioes, "regioes: 3 regioes (secoes 0, 8 e 30)");
        v.igual(3, rd.chunks, "regioes: 3 chunks");
        v.igual(3, rd.tiles, "regioes: 3 tiles dentro de secao carregada");
        v.igual(1, rd.tilesForaDeSecao, "regioes: 1 tile fora de secao carregada contado a parte");
        v.igual(1, rd.ents, "regioes: 1 entidade");
        v.igual(2, rd.maxTilesNumaSecao, "regioes: maximo de tiles numa secao = 2");
        v.igual(2, rd.regioesComCarga, "regioes: 2 regioes com carga (a de secao 30 esta vazia)");
        v.igual(8, rd.menorDistSecoes, "regioes: menor distancia entre regioes com carga = 8 secoes");
        v.sim(rd.pares.size() == 1 && rd.pares.get(0).chunksMin() == 113 && rd.pares.get(0).chunksMax() == 143,
                "regioes: 8 secoes = entre 113 e 143 chunks");
        FotoMundo e = new FotoMundo(0).chunk(0, 0).chunk(16 * 40, 0);
        e.tile(1, 1).tile(16 * 16 * 40 + 1, 1);
        v.igual(0, Regioes.calcular(new FotoMundo[] {e}, new int[] {0}, 0, 0, 0).resumo.dims.get(0).menorDistSecoes,
                "regioes: a mais de 12 secoes nao ha par (0)");
        // tirarFoto guarda no maximo MAX_FOTOS
        Nucleo n = novo(100);
        for (int i = 0; i < Nucleo.MAX_FOTOS + 15; i++) {
            n.tirarFoto(fotoPadrao());
        }
        v.igual(Nucleo.MAX_FOTOS, n.fotos.size(), "T5: guarda no maximo " + Nucleo.MAX_FOTOS + " fotos");
        v.igual((long) Nucleo.MAX_FOTOS + 15, n.fotosTiradas, "T5: conta todas as fotos tiradas");
        Nucleo q = novo(100);
        v.sim(q.fotoDevida(0), "foto: sem foto ainda = devida");
        q.tirarFoto(fotoPadrao());
        v.sim(!q.fotoDevida(q.foto().resumo.custoNs), "foto: logo depois de tirar nao e devida");
    }

    static FotoMundo sec(int sx, int sz) {
        return new FotoMundo(0).chunk(sx * 16, sz * 16);
    }

    static int regs(FotoMundo... partes) {
        FotoMundo f = new FotoMundo(0);
        for (FotoMundo p : partes) {
            f.chunk(p.chunksX[0], p.chunksZ[0]);
        }
        return Regioes.calcular(new FotoMundo[] {f}, new int[] {0}, 0, 0, 0).nRegioes;
    }

    static void carga(Verifica v) {
        Nucleo n = novo(1000000);
        n.inicioTick(0);
        // antes de qualquer foto
        n.entrar(Nucleo.C_TILE, M0, 0, 5, 5, ModAMaquina.class);
        n.cargaChunk(M0, 0, 20, 5);
        n.tirarFoto(fotoPadrao());
        int ma = n.mods.id("moda.jar");
        v.igual(1L, n.t3.contDe(Nucleo.chaveT3(Nucleo.C_TILE, ma, 0, 6)), "carga: sem foto = 'sem foto da origem'");
        n.cargaChunk(M0, 0, 6, 6);
        v.igual(1L, n.t3.contDe(Nucleo.chaveT3(Nucleo.C_TILE, ma, 0, 0)), "carga: na mesma secao da origem");
        n.cargaChunk(M0, 0, 20, 5);
        v.igual(1L, n.t3.contDe(Nucleo.chaveT3(Nucleo.C_TILE, ma, 0, 1)), "carga: em outra secao da mesma regiao");
        n.cargaChunk(M0, 0, -100, 5);
        v.igual(1L, n.t3.contDe(Nucleo.chaveT3(Nucleo.C_TILE, ma, 0, 2)), "carga: em secao vazia (sem chunk na foto)");
        n.cargaChunk(M0, 0, 200, 5);
        v.igual(1L, n.t3.contDe(Nucleo.chaveT3(Nucleo.C_TILE, ma, 0, 3)), "carga: na secao de outra regiao");
        n.cargaChunk(M1, 1, 3, 3);
        v.igual(1L, n.t3.contDe(Nucleo.chaveT3(Nucleo.C_TILE, ma, 0, 5)), "carga: em outra dimensao");
        n.sair(Nucleo.C_TILE);
        n.entrar(Nucleo.C_REDE, null, 0, Nucleo.SEM_ORIGEM, 0, null);
        n.cargaChunk(M0, 0, 1, 1);
        v.igual(1L, n.t3.contDe(Nucleo.chaveT3(Nucleo.C_REDE, 0, 0, 4)), "carga: contexto sem origem");
        v.igual(7L, n.cargasDeChunk(), "carga: 7 cargas contadas");
    }

    static void valvula(Verifica v) {
        // 20000 acessos completos por tick de 10 ms, a 100 ns cada = 2 ms = 20%: passa do limite de 5%
        Nucleo n = novo(1000000);
        n.entrar(Nucleo.C_TILE, M0, 0, 0, 0, ModAMaquina.class);
        boolean parou = false;
        int ticks = 0;
        for (; ticks < 500 && !parou; ticks++) {
            n.inicioTick(ticks * 10000000L);
            n.entrar(Nucleo.C_TILE, M0, 0, 0, 0, ModAMaquina.class);
            for (int i = 0; i < 20000; i++) {
                n.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, 16 + (i & 7), 0);
            }
            parou = n.fimTick(ticks * 10000000L + 10000000L);
        }
        v.sim(parou && ticks == 2 * Nucleo.JANELA_TICKS, "valvula: custo de regime acima do limite para ao fim da segunda janela (a primeira e de aquecimento) de " + Nucleo.JANELA_TICKS + " ticks (parou em " + ticks + ")");
        // aquecimento (D-44): a primeira foto fria e as primeiras amostras caem na primeira janela e nao param a observacao
        Nucleo q = novo(1000000);
        boolean p4 = false;
        for (int t = 0; t < 400; t++) {
            q.inicioTick(t * 10000000L);
            q.entrar(Nucleo.C_TILE, M0, 0, 0, 0, ModAMaquina.class);
            int acessos = t == 3 ? 600000 : 100; // o tick 3 paga o custo frio (cerca de 6% do tempo da janela inteira), os outros sao de regime
            for (int i = 0; i < acessos; i++) {
                q.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, 16 + (i & 7), 0);
            }
            p4 |= q.fimTick(t * 10000000L + 10000000L);
        }
        v.sim(!p4 && q.pctAquecimento > 5.0 && q.pctMaxJanela < 5.0, "valvula: custo alto so na primeira janela (aquecimento " + String.format("%.1f", q.pctAquecimento)
                + "%, depois no maximo " + String.format("%.2f", q.pctMaxJanela) + "%) nao para a observacao");
        v.sim(n.motivoValvula != null && n.motivoValvula.contains("valvula"), "valvula: o motivo fica registrado: " + n.motivoValvula);
        // abaixo do limite: nao para
        Nucleo m = novo(1000000);
        boolean p2 = false;
        for (int t = 0; t < 500; t++) {
            m.inicioTick(t * 10000000L);
            m.entrar(Nucleo.C_TILE, M0, 0, 0, 0, ModAMaquina.class);
            for (int i = 0; i < 100; i++) {
                m.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, 16 + (i & 7), 0);
            }
            p2 |= m.fimTick(t * 10000000L + 10000000L);
        }
        v.sim(!p2, "valvula: custo baixo nao para (" + String.format("%.3f", m.custoMedioPct()) + "% estimado)");
        // percentual alto mas folga absoluta minuscula: nao para
        Nucleo k = novo(1000000);
        boolean p3 = false;
        for (int t = 0; t < 300; t++) {
            k.inicioTick(t * 4000L);
            k.entrar(Nucleo.C_TILE, M0, 0, 0, 0, ModAMaquina.class);
            for (int i = 0; i < 10; i++) {
                k.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, 16, 0);
            }
            p3 |= k.fimTick(t * 4000L + 2000L);
        }
        v.sim(!p3 && k.custoMedioPct() > 5.0, "valvula: percentual alto com menos de 0,25 ms por tick nao para (" + String.format("%.1f", k.custoMedioPct()) + "%)");
        // as leituras rapidas entram na conta
        Nucleo r = novo(1000000);
        r.inicioTick(0);
        r.entrar(Nucleo.C_TILE, M0, 0, 0, 0, ModAMaquina.class);
        for (int i = 0; i < 1000; i++) {
            r.acesso(Nucleo.A_LER_BLOCO, M0, 0, 1, 1);
        }
        r.fimTick(1000000);
        v.igual(1000L, r.leiturasRapidas(), "custo: 1000 leituras rapidas contadas");
        v.sim(r.custoTotalNs >= 1000 * Nucleo.CUSTO_RAPIDA_NS, "custo: as leituras rapidas entram na estimativa (" + r.custoTotalNs + " ns)");
    }

    static void relatorio(Verifica v) throws Exception {
        Nucleo n = novo(2);
        n.tirarFoto(fotoPadrao());
        n.inicioTick(0);
        n.entrar(Nucleo.C_JOGADOR_REDE, M0, 0, 5, 5, null);
        n.acesso(Nucleo.A_ESCREVER_BLOCO, M0, 0, 20, 5);
        n.sair(Nucleo.C_JOGADOR_REDE);
        n.entrar(Nucleo.C_TILE, M0, 0, 5, 5, ModAMaquina.class);
        for (int i = 0; i < 10; i++) {
            Chamador.escrever(n, M0, 0, 200, 5);
        }
        n.acesso(Nucleo.A_ESCREVER_BLOCO, M1, 1, 3, 3);
        n.cargaChunk(M0, 0, 200, 5);
        n.outraThread(Nucleo.A_ESCREVER_BLOCO);
        n.fimTick(1000000);
        Relatorio.Meta m = new Relatorio.Meta();
        m.versao = "teste";
        m.inicio = "2026-01-01 00:00:00";
        m.segundosPedidos = 60;
        m.duracaoSegundos = 12.5;
        m.motivoFim = "teste \"com aspas\"\nnova linha";
        String js = Relatorio.json(n, m);
        Object raiz = JsonLeitor.ler(js);
        v.sim(raiz instanceof Map, "relatorio: o json e valido");
        v.igual("teste", JsonLeitor.caminho(raiz, "hoshiestar"), "relatorio: versao");
        v.igual(Boolean.FALSE, JsonLeitor.caminho(raiz, "observacao", "parou_no_meio"), "relatorio: parou_no_meio");
        v.sim(JsonLeitor.caminho(raiz, "t1_acessos", "0", "contagem") != null, "relatorio: t1_acessos tem linhas");
        v.sim(JsonLeitor.caminho(raiz, "t2_pares_entre_regioes", "0", "secao_destino_x") != null, "relatorio: t2 tem pares");
        v.sim(JsonLeitor.caminho(raiz, "t3_cargas_de_chunk", "0", "onde_caiu") != null, "relatorio: t3 tem cargas");
        v.igual(1L, ((Number) JsonLeitor.caminho(raiz, "t4_acessos_de_outra_thread", "total")).longValue(), "relatorio: t4 total 1");
        v.sim(JsonLeitor.caminho(raiz, "t5_fotos_de_regioes", "0", "dimensoes", "0", "regioes") != null, "relatorio: t5 tem foto");
        v.sim(JsonLeitor.caminho(raiz, "resumo_por_mod_da_pilha_so_amostras", "0", "mod") != null, "relatorio: resumo por mod da pilha");
        // privacidade: nenhuma classe, nenhum nome de jogador, UUID nem IP
        String txt = Relatorio.texto(n, m);
        for (String s : new String[] {js, txt}) {
            v.sim(!Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matcher(s).find(),
                    "privacidade: nenhum UUID");
            v.sim(!Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b").matcher(s).find(), "privacidade: nenhum IP");
            v.sim(!s.contains("TesteObservadorNucleo$") && !s.contains("EntityPlayer"),
                    "privacidade: nenhum nome de classe (so jar de mod) e nada de jogador");
        }
        v.sim(txt.contains("COMO LER") && txt.contains("moda.jar") && txt.contains("T5"), "relatorio: o .txt traz como ler, os mods e T5");
        v.sim(txt.contains("jogador_rede"), "relatorio: o contexto aparece como 'jogador_rede', sem nome");
        // gravacao
        java.io.File pasta = java.nio.file.Files.createTempDirectory("obs-rel").toFile();
        java.io.File[] f = Relatorio.gravar(n, m, new java.io.File(pasta, "hoshiestar"), "20260101-000000");
        v.sim(f[0].length() > 100 && f[1].length() > 100 && f[0].getName().endsWith(".json") && f[1].getName().endsWith(".txt"),
                "relatorio: grava o .json e o .txt");
    }
}
