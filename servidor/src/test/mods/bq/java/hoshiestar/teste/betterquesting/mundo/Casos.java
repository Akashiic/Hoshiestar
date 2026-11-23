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

import betterquesting.api2.utils.ParticipantInfo;
import bq_standard.tasks.ITaskInventory;
import bq_standard.tasks.TaskOptionalRetrieval;
import bq_standard.tasks.TaskRetrieval;
import hoshiestar.api.ThreadServidor;
import hoshiestar.betterquesting.BetterQuesting;
import hoshiestar.betterquesting.filtro.GanchoBQ;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Casos montados a mao (os pedidos pelo coordenador e os da conferencia): cada um roda o laco
 * original e o laco com os ganchos REAIS em duas copias do mesmo mundo, exige o MESMO resultado e
 * confere a decisao do filtro (qual contador subiu) e, quando o caso existe para isso, que o original
 * de fato mudou alguma coisa (o caso nao e vazio).
 */
public final class Casos {

    static final Item X = new Item("X", false);
    static final Item Y = new Item("Y", false);
    static final Item Z = new Item("Z", false);
    static final Item D = new Item("D", true);
    static final Item INSTAVEL = new Extras.ItemInstavel("instavel");
    static final UUID EU = new UUID(1, 1);
    static final UUID OUTRO = new UUID(1, 2);

    interface Caso {
        String rodar() throws Exception;
    }

    static final List<String> NOMES = new ArrayList<String>();
    static final List<Caso> CASOS = new ArrayList<Caso>();

    static void caso(String nome, Caso c) {
        NOMES.add(nome);
        CASOS.add(c);
    }

    private Casos() {
    }

    public static List<String> nomes() {
        return Collections.unmodifiableList(NOMES);
    }

    /** Roda todos; devolve as falhas ("nome: detalhe"); vazio = todos passaram. */
    public static List<String> rodar() {
        List<String> falhas = new ArrayList<String>();
        for (int i = 0; i < CASOS.size(); i++) {
            String r;
            try {
                prepararModo(BetterQuesting.LIGADO);
                r = CASOS.get(i).rodar();
            } catch (Throwable t) {
                r = "excecao no teste: " + t;
            }
            if (r != null) {
                falhas.add(NOMES.get(i) + ": " + r);
            }
        }
        prepararModo(BetterQuesting.LIGADO);
        return falhas;
    }

    static void prepararModo(int modo) {
        ThreadServidor.definir(Thread.currentThread());
        BetterQuesting.zerarContadores();
        BetterQuesting.modo = modo;
        for (String k : new String[] {"", "oreA", "oreB", "oreVazio"}) {
            OreDictionary.getOres(k).clear();
        }
    }

    // ------------------------------------------------------------------ ajuda

    /** Um jogador sozinho (grupo de 1) com estas pilhas no inventario. */
    static ParticipantInfo sozinho(Mundo m, ItemStack... pilhas) throws Exception {
        EntityPlayer p = m.jogador();
        for (int i = 0; i < pilhas.length; i++) {
            p.field_71071_by.slots[i] = pilhas[i];
        }
        return m.info(p, EU, Collections.singletonList(p), Collections.singletonList(EU));
    }

    /**
     * Roda o caso: mesmo resultado nas duas copias; o contador {@code esperado} subiu; se
     * {@code muda}, o original mudou o mundo (comparado com uma terceira copia que nao roda nada).
     */
    static String duplo(Mundo.Montador mm, int esperado, boolean muda) throws Exception {
        Mundo a = Mundo.novo(mm);
        Mundo b = Mundo.novo(mm);
        Mundo parado = Mundo.novo(mm);
        a.original();
        b.comGanchos();
        String fa = a.impressao();
        String fb = b.impressao();
        if (!fa.equals(fb)) {
            return "DIFERENCA\n   original=" + fa + "\n   ganchos =" + fb;
        }
        if (esperado >= 0 && b.delta(esperado) < 1) {
            return "decisao esperada (contador " + esperado + ") nao aconteceu; contadores: " + BetterQuesting.linhaDeEstado();
        }
        if (BetterQuesting.contagem[BetterQuesting.ERROS] != 0) {
            return "erro no nosso codigo: " + BetterQuesting.desligadoPorErro;
        }
        boolean mudou = !parado.impressao().equals(fa);
        if (muda && !mudou) {
            return "caso vazio: o original nao mudou nada (o caso nao testa o que diz)";
        }
        if (!muda && mudou && a.excecao == null) {
            return "o original mudou alguma coisa num caso que devia ser 'nao faz nada': " + fa;
        }
        return null;
    }

    static Object campo(String nome) throws Exception {
        Field f = Class.forName("hoshiestar.betterquesting.filtro.FiltroInventario").getDeclaredField(nome);
        f.setAccessible(true);
        return f.get(null);
    }

    // ------------------------------------------------------------------ os casos

    static {
        caso("progresso parcial: 5 de 10 sem o item no inventario (o original zera e marca)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Y, 3, 0));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        Mundo.progresso(t).put(EU, new int[] {5});
                    }
                }, BetterQuesting.PASSA_PARCIAL, true);
            }
        });
        caso("progresso parcial negativo (-1, NBT estragado: o original zera)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m);
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        Mundo.progresso(t).put(EU, new int[] {-1});
                    }
                }, BetterQuesting.PASSA_PARCIAL, true);
            }
        });
        caso("progresso parcial de OUTRO membro offline (ALL_UUIDS)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        EntityPlayer p = m.jogador();
                        m.info(p, EU, Collections.singletonList(p), Arrays.asList(EU, OUTRO));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        Mundo.progresso(t).put(OUTRO, new int[] {4});
                    }
                }, BetterQuesting.PASSA_PARCIAL, true);
            }
        });
        caso("groupDetect com progresso 3 (o Detector zera direto no array salvo)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m);
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        t.groupDetect = true;
                        Mundo.progresso(t).put(EU, new int[] {3});
                    }
                }, BetterQuesting.PASSA_PARCIAL, true);
            }
        });
        caso("groupDetect com progresso 0: pulada", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Y, 1, 0));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        t.groupDetect = true;
                        Mundo.progresso(t).put(EU, new int[] {0});
                    }
                }, BetterQuesting.PULA_SEM_ITEM, false);
            }
        });
        caso("consume sem autoConsume: pulada (o original nao faz nada)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(X, 64, 0));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        t.consume = true;
                        t.autoConsume = false;
                    }
                }, BetterQuesting.PULA_CONSUMO, false);
            }
        });
        caso("consume + autoConsume com o item: original (consome igual)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(X, 4, 0));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        t.consume = true;
                        t.autoConsume = true;
                    }
                }, BetterQuesting.PASSA_PRESENTE, true);
            }
        });
        caso("consume + autoConsume sem o item: pulada", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Y, 4, 0));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        t.consume = true;
                        t.autoConsume = true;
                        Mundo.progresso(t).put(EU, new int[] {3}); // com consume o parcial NAO e zerado
                    }
                }, BetterQuesting.PULA_SEM_ITEM, false);
            }
        });
        caso("inventario nulo de um membro ativo: original (a mesma excecao)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        EntityPlayer p = m.jogador();
                        EntityPlayer q = m.jogador();
                        q.field_71071_by = null;
                        m.info(p, EU, Arrays.asList(p, q), Arrays.asList(EU, OUTRO));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                    }
                }, BetterQuesting.PASSA_INVENTARIO, false);
            }
        });
        caso("inventario nulo do PLAYER velho fora da lista (respawn): original, sem excecao", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        EntityPlayer velho = m.jogador();
                        velho.field_71071_by = null;
                        EntityPlayer novo = m.jogador();
                        m.info(velho, EU, Collections.singletonList(novo), Collections.singletonList(EU));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                    }
                }, BetterQuesting.PASSA_INVENTARIO, false);
            }
        });
        caso("ore '' registrado com o item no inventario: original (o NO_ORE casa)", new Caso() {
            public String rodar() throws Exception {
                OreDictionary.getOres("").add(Mundo.pilha(Z, 1, 32767));
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Z, 5, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0)); // sem ore: usa o NO_ORE (ore "")
                    }
                }, BetterQuesting.PASSA_ORE, true);
            }
        });
        caso("ore com nome sem itens (lista vazia): pulada", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Z, 5, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0).setOreDict("oreVazio"));
                    }
                }, BetterQuesting.PULA_SEM_ITEM, false);
            }
        });
        caso("ore com o item do ore no inventario: original", new Caso() {
            public String rodar() throws Exception {
                OreDictionary.getOres("oreA").add(Mundo.pilha(Z, 1, 32767));
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Z, 5, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0).setOreDict("oreA"));
                    }
                }, BetterQuesting.PASSA_ORE, true);
            }
        });
        caso("Item com hashCode instavel no inventario: original (conjunto por identidade)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Y, 1, 0), Mundo.pilha(INSTAVEL, 5, 0), Mundo.pilha(Z, 1, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(INSTAVEL, 10, 0));
                    }
                }, BetterQuesting.PASSA_PRESENTE, true);
            }
        });
        caso("conclusao ja satisfeita mas tarefa nao completa: original (completa)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m);
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        Mundo.progresso(t).put(EU, new int[] {10});
                    }
                }, BetterQuesting.PASSA_SATISFEITA, true);
            }
        });
        caso("requireOnlyOneItem com um dos itens satisfeito: original (completa)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m);
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0), Mundo.exige(Y, 2, 0));
                        t.requireOnlyOneItem = true;
                        Mundo.progresso(t).put(EU, new int[] {0, 2});
                    }
                }, BetterQuesting.PASSA_SATISFEITA, true);
            }
        });
        caso("ja completa para o PLAYER: pulada", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(X, 64, 0));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        Mundo.completos(t).add(EU);
                        Mundo.progresso(t).put(EU, new int[] {4});
                    }
                }, BetterQuesting.PULA_COMPLETA, false);
            }
        });
        caso("completa para outro membro mas nao para o PLAYER: nao conta como completa", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        EntityPlayer p = m.jogador();
                        p.field_71071_by.slots[0] = Mundo.pilha(X, 3, 0);
                        m.info(p, EU, Collections.singletonList(p), Arrays.asList(EU, OUTRO));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        Mundo.completos(t).add(OUTRO);
                    }
                }, BetterQuesting.PASSA_PRESENTE, true);
            }
        });
        caso("TaskOptionalRetrieval (subclasse): original", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m);
                        m.tarefa(new TaskOptionalRetrieval(), Mundo.exige(X, 10, 0));
                    }
                }, BetterQuesting.PASSA_CLASSE, false);
            }
        });
        caso("tarefa de addon que poe item: original (classe exata)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m);
                        m.tarefa(new Extras.TarefaQueMexe(Y), Mundo.exige(X, 10, 0));
                    }
                }, BetterQuesting.PASSA_CLASSE, true);
            }
        });
        caso("uuid nulo em ALL_UUIDS: original (a mesma excecao)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        EntityPlayer p = m.jogador();
                        m.info(p, EU, Collections.singletonList(p), Arrays.asList(EU, null));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                    }
                }, BetterQuesting.PASSA_DADO_ESTRANHO, false);
            }
        });
        caso("lista de itens vazia: original (o BQ completa a tarefa)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m);
                        m.tarefa(new TaskRetrieval());
                    }
                }, BetterQuesting.PASSA_ITEM_INVALIDO, true);
            }
        });
        caso("quantidade exigida 0: original (o BQ completa a tarefa)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m);
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 0, 0));
                    }
                }, BetterQuesting.PASSA_ITEM_INVALIDO, true);
            }
        });
        caso("respawn: item so no inventario do PLAYER velho: original (conservador)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        EntityPlayer velho = m.jogador();
                        velho.field_71071_by.slots[0] = Mundo.pilha(X, 5, 0);
                        EntityPlayer novo = m.jogador();
                        m.info(velho, EU, Collections.singletonList(novo), Collections.singletonList(EU));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                    }
                }, BetterQuesting.PASSA_PRESENTE, false);
            }
        });
        caso("respawn + consume/autoConsume: o original varre o PLAYER velho e consome", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        EntityPlayer velho = m.jogador();
                        velho.field_71071_by.slots[0] = Mundo.pilha(X, 5, 0);
                        EntityPlayer novo = m.jogador();
                        m.info(velho, EU, Collections.singletonList(novo), Collections.singletonList(EU));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        t.consume = true;
                        t.autoConsume = true;
                    }
                }, BetterQuesting.PASSA_PRESENTE, true);
            }
        });
        caso("dois jogadores no mesmo tick: o conjunto e de cada ParticipantInfo", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        EntityPlayer a = m.jogador();
                        EntityPlayer b = m.jogador();
                        b.field_71071_by.slots[5] = Mundo.pilha(X, 7, 0);
                        m.info(a, EU, Collections.singletonList(a), Collections.singletonList(EU));
                        m.info(b, OUTRO, Collections.singletonList(b), Collections.singletonList(OUTRO));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                    }
                }, BetterQuesting.PASSA_PRESENTE, true);
            }
        });
        caso("tarefa que poe item no meio do laco: o conjunto e refeito", new Caso() {
            public String rodar() throws Exception {
                final Mundo[] guardado = new Mundo[1];
                String r = duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Z, 1, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(D, 3, 0));     // monta o conjunto (sem Y)
                        m.tarefa(new Extras.TarefaQueMexe(Y), Mundo.exige(D, 3, 0)); // poe Y
                        m.tarefa(new TaskRetrieval(), Mundo.exige(Y, 3, 0));     // exige Y: tem de ir ao original
                        guardado[0] = m;
                    }
                }, BetterQuesting.PASSA_PRESENTE, true);
                if (r == null && BetterQuesting.contagem[BetterQuesting.RECONSTRUCOES] < 2) {
                    return "o conjunto nao foi refeito depois da tarefa que mexe (" + BetterQuesting.linhaDeEstado() + ")";
                }
                return r;
            }
        });
        caso("excecao no meio do laco: a entrada do tick seguinte solta o cache (C2)", new Caso() {
            public String rodar() throws Exception {
                Mundo b = Mundo.novo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Y, 1, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0)); // pulada: monta o conjunto
                        m.tarefa(new TaskRetrieval());                        // lista vazia: original
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0)); // progresso de tipo errado
                        Mundo.progresso(t).put(EU, "lixo");
                    }
                });
                b.comGanchos();
                if (b.excecao == null || !b.excecao.contains("ClassCast")) {
                    return "esperava a ClassCastException do proprio BQ, veio " + b.excecao;
                }
                if (campo("ultimoInfo") == null) {
                    return "depois da excecao o cache deveria ainda estar montado (o fim do laco nao rodou)";
                }
                GanchoBQ.inicioDoTick(null, null);
                if (campo("ultimoInfo") != null || !((java.util.Map<?, ?>) campo("presentes")).isEmpty()) {
                    return "a entrada do tick nao soltou o cache";
                }
                return BetterQuesting.contagem[BetterQuesting.ERROS] == 0 ? null : "erro nosso: " + BetterQuesting.desligadoPorErro;
            }
        });
        caso("fim do laco: o conjunto do BQ e esvaziado e o cache solto", new Caso() {
            public String rodar() throws Exception {
                Mundo b = Mundo.novo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Y, 1, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                    }
                });
                b.comGanchos(); // comGanchos ja confere que o conjunto ficou vazio
                if (b.excecao != null) {
                    return "excecao: " + b.excecao;
                }
                if (campo("ultimoInfo") != null || !((java.util.Map<?, ?>) campo("presentes")).isEmpty()) {
                    return "o fim do laco nao soltou o cache";
                }
                return BetterQuesting.contagem[BetterQuesting.TICKS] == 1 ? null : "ticks com checagem != 1";
            }
        });
        caso("desligado: so o original, nenhum contador muda", new Caso() {
            public String rodar() throws Exception {
                prepararModo(BetterQuesting.DESLIGADO);
                String r = duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Y, 1, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(Y, 10, 0));
                    }
                }, -1, true);
                long soma = 0;
                for (int i = 0; i <= BetterQuesting.PASSA_ERRO; i++) {
                    soma += BetterQuesting.contagem[i];
                }
                return r != null ? r : soma == 0 ? null : "contadores mudaram no modo desligado: " + BetterQuesting.linhaDeEstado();
            }
        });
        caso("outra thread: original, contador 'outra thread'", new Caso() {
            public String rodar() throws Exception {
                final Mundo a = Mundo.novo(montadorSimples());
                final Mundo b = Mundo.novo(montadorSimples());
                a.original();
                ThreadServidor.definir(new Thread()); // a thread do teste deixa de ser "a do servidor"
                try {
                    b.comGanchos();
                } finally {
                    ThreadServidor.definir(Thread.currentThread());
                }
                if (!a.impressao().equals(b.impressao())) {
                    return "DIFERENCA " + a.impressao() + " x " + b.impressao();
                }
                return BetterQuesting.contagem[BetterQuesting.OUTRA_THREAD] == 1
                        && BetterQuesting.contagem[BetterQuesting.PULA_SEM_ITEM] == 0 ? null
                        : "esperava 1 chamada de outra thread e nenhuma pulada: " + BetterQuesting.linhaDeEstado();
            }
        });
        caso("verificar: chamada pulavel roda o original e e comparada (0 divergencias)", new Caso() {
            public String rodar() throws Exception {
                prepararModo(BetterQuesting.VERIFICAR);
                String r = duplo(montadorSimples(), BetterQuesting.PULA_SEM_ITEM, false);
                if (r != null) {
                    return r;
                }
                return BetterQuesting.contagem[BetterQuesting.COMPARADAS] == 1 && BetterQuesting.contagem[BetterQuesting.DIVERGENCIAS] == 0
                        ? null : "esperava 1 comparada e 0 divergencias: " + BetterQuesting.linhaDeEstado();
            }
        });
        caso("verificar: inventario mudado por fora entre duas chamadas (cache velho) e ACUSADO", new Caso() {
            public String rodar() throws Exception {
                prepararModo(BetterQuesting.VERIFICAR);
                final ParticipantInfo[] pi = new ParticipantInfo[1];
                Mundo b = Mundo.novo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        pi[0] = sozinho(m, Mundo.pilha(Y, 1, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(Z, 10, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                    }
                });
                ThreadServidor.definir(Thread.currentThread());
                GanchoBQ.inicioDoTick(null, null);
                GanchoBQ.aoMudarInventario((ITaskInventory) b.tarefas.get(0), b.entradas.get(0), pi[0]); // monta o conjunto
                pi[0].PLAYER.field_71071_by.slots[9] = Mundo.pilha(X, 4, 0); // mudanca que o laco do BQ nunca faria
                GanchoBQ.aoMudarInventario((ITaskInventory) b.tarefas.get(1), b.entradas.get(1), pi[0]);
                GanchoBQ.fimDoLaco(new HashSet<Object>());
                int[] p = (int[]) Mundo.progresso(b.tarefas.get(1)).get(EU);
                if (p == null || p[0] != 4) {
                    return "no modo verificar o original tinha de rodar e contar 4: " + (p == null ? null : Arrays.toString(p));
                }
                return BetterQuesting.contagem[BetterQuesting.DIVERGENCIAS] == 1 ? null
                        : "a divergencia nao foi acusada: " + BetterQuesting.linhaDeEstado();
            }
        });
        // ---- casos adversarios da revisao independente (T1, T1b, T2, T3, T5)
        caso("progresso salvo MAIS CURTO que a lista (quest editada, ganhou um item): pulada, como zeros", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Z, 1, 0));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 5, 0), Mundo.exige(X, 3, 1));
                        Mundo.progresso(t).put(EU, new int[] {2}); // parcial antigo, de quando a quest tinha 1 item
                    }
                }, BetterQuesting.PULA_SEM_ITEM, false);
            }
        });
        caso("progresso curto de um membro e parcial de tamanho certo de outro depois: original (zera)", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        EntityPlayer p = m.jogador();
                        p.field_71071_by.slots[0] = Mundo.pilha(Z, 1, 0);
                        m.info(p, EU, Collections.singletonList(p), Arrays.asList(EU, OUTRO));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 5, 0), Mundo.exige(X, 3, 1));
                        Mundo.progresso(t).put(EU, new int[] {2});
                        Mundo.progresso(t).put(OUTRO, new int[] {2, 0});
                    }
                }, BetterQuesting.PASSA_PARCIAL, true);
            }
        });
        caso("ore registrado NO MEIO do laco por tarefa de addon: a lista viva e lida a cada decisao", new Caso() {
            public String rodar() throws Exception {
                Mundo.Montador mm = new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(Z, 4, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 2, 0).setOreDict("oreB"));   // ore vazio: pulada
                        m.tarefa(new Extras.TarefaQueRegistraOre("oreB", Z), Mundo.exige(X, 1, 0)); // registra Z em oreB
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 2, 0).setOreDict("oreB"));   // agora casa: original
                    }
                };
                Mundo a = Mundo.novo(mm);
                Mundo b = Mundo.novo(mm);
                Mundo parado = Mundo.novo(mm);
                OreDictionary.getOres("oreB").clear();
                a.original();
                OreDictionary.getOres("oreB").clear();
                b.comGanchos();
                String fa = a.impressao();
                String fb = b.impressao();
                if (!fa.equals(fb)) {
                    return "DIFERENCA\n   original=" + fa + "\n   ganchos =" + fb;
                }
                if (fa.equals(parado.impressao())) {
                    return "caso vazio: o original nao mudou nada";
                }
                if (b.delta(BetterQuesting.PULA_SEM_ITEM) != 1 || b.delta(BetterQuesting.PASSA_ORE) != 1 || b.delta(BetterQuesting.PASSA_CLASSE) != 1) {
                    return "esperava 1 pulada (sem item), 1 de classe e 1 de ore: " + BetterQuesting.linhaDeEstado();
                }
                return BetterQuesting.contagem[BetterQuesting.ERROS] == 0 ? null : "erro nosso: " + BetterQuesting.desligadoPorErro;
            }
        });
        caso("A, B, A no mesmo tick (o mesmo ParticipantInfo duas vezes) com B mexendo no inventario de A", new Caso() {
            public String rodar() throws Exception {
                return duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        EntityPlayer pa = m.jogador();
                        EntityPlayer pb = m.jogador();
                        ParticipantInfo ia = m.info(pa, EU, Collections.singletonList(pa), Collections.singletonList(EU));
                        m.info(pb, OUTRO, Arrays.asList(pa, pb), Arrays.asList(OUTRO)); // o primeiro ativo de B e o jogador A
                        m.infos.add(ia);
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 1, 0));
                        m.tarefa(new Extras.TarefaQueMexe(X), Mundo.exige(Y, 1, 0));
                    }
                }, -1, true);
            }
        });
        caso("verificar com consume+autoConsume e o item presente: nao pulavel, o original consome, nada comparado", new Caso() {
            public String rodar() throws Exception {
                prepararModo(BetterQuesting.VERIFICAR);
                String r = duplo(new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        sozinho(m, Mundo.pilha(X, 3, 0));
                        TaskRetrieval t = m.tarefa(new TaskRetrieval(), Mundo.exige(X, 2, 0));
                        t.consume = true;
                        t.autoConsume = true;
                    }
                }, BetterQuesting.PASSA_PRESENTE, true);
                if (r != null) {
                    return r;
                }
                return BetterQuesting.contagem[BetterQuesting.COMPARADAS] == 0 ? null
                        : "nada devia ser comparado (a chamada nao era pulavel): " + BetterQuesting.linhaDeEstado();
            }
        });
        caso("depois de desligado por erro, mais ticks nao geram erro novo", new Caso() {
            public String rodar() throws Exception {
                BetterQuesting.desligarPorErro("teste", new IllegalStateException("erro de teste"));
                for (int i = 0; i < 20; i++) {
                    Mundo b = Mundo.novo(montadorSimples());
                    b.comGanchos();
                    if (b.excecao != null) {
                        return "excecao: " + b.excecao;
                    }
                }
                return BetterQuesting.contagem[BetterQuesting.ERROS] == 1 && BetterQuesting.modo == BetterQuesting.DESLIGADO
                        && BetterQuesting.contagem[BetterQuesting.PULA_SEM_ITEM] == 0 && BetterQuesting.contagem[BetterQuesting.TICKS] == 0
                        ? null : "esperava 1 erro, nada pulado e nenhum tick contado: " + BetterQuesting.linhaDeEstado();
            }
        });
        caso("erro no nosso codigo: o recurso desliga, o original roda, mesmo resultado", new Caso() {
            public String rodar() throws Exception {
                Mundo.Montador mm = new Mundo.Montador() {
                    public void montar(Mundo m) throws Exception {
                        EntityPlayer p = m.jogador();
                        p.field_71071_by.slots[0] = Mundo.pilha(X, 3, 0);
                        m.info(p, EU, new Extras.ListaQueQuebra(Collections.singletonList(p)), Collections.singletonList(EU));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
                        m.tarefa(new TaskRetrieval(), Mundo.exige(Y, 10, 0));
                    }
                };
                Mundo a = Mundo.novo(mm);
                Mundo b = Mundo.novo(mm);
                a.original();
                b.comGanchos();
                if (!a.impressao().equals(b.impressao())) {
                    return "DIFERENCA " + a.impressao() + " x " + b.impressao();
                }
                if (BetterQuesting.contagem[BetterQuesting.ERROS] != 1 || BetterQuesting.modo != BetterQuesting.DESLIGADO
                        || BetterQuesting.desligadoPorErro == null) {
                    return "esperava 1 erro e o recurso desligado: " + BetterQuesting.linhaDeEstado();
                }
                return null;
            }
        });
    }

    /**
     * Falha persistente nos ganchos de inicio/fim do laco (so com um FiltroInventario mutante que sempre
     * lanca excecao ali; o teste de mutacao usa): n ticks vazios; devolve os erros contados. Esperado: 1
     * (o primeiro erro desliga o recurso e os ticks seguintes nem chamam o filtro); -1 se o recurso nao
     * desligou. Revisao M2.
     */
    public static long persistente(int n) {
        prepararModo(BetterQuesting.LIGADO);
        for (int i = 0; i < n; i++) {
            GanchoBQ.inicioDoTick(null, null);
            GanchoBQ.fimDoLaco(new HashSet<Object>());
        }
        long e = BetterQuesting.contagem[BetterQuesting.ERROS];
        boolean desligado = BetterQuesting.modo == BetterQuesting.DESLIGADO;
        prepararModo(BetterQuesting.LIGADO);
        return desligado ? e : -1; // -1 = o recurso nem desligou
    }

    /** Um jogador com Y; uma tarefa que exige X (pulavel). */
    static Mundo.Montador montadorSimples() {
        return new Mundo.Montador() {
            public void montar(Mundo m) throws Exception {
                sozinho(m, Mundo.pilha(Y, 1, 0));
                m.tarefa(new TaskRetrieval(), Mundo.exige(X, 10, 0));
            }
        };
    }
}
