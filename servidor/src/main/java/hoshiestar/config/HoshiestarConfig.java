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
package hoshiestar.config;

import hoshiestar.Hoshiestar;
import io.github.crucible.util.config.Comments;
import io.github.crucible.util.config.ConfigMode;
import io.github.crucible.util.config.ConfigSection;
import io.github.crucible.util.config.InvalidConfigurationException;
import io.github.crucible.util.config.Path;
import io.github.crucible.util.config.YamlConfig;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * O hoshiestar.yml: um interruptor por recurso do Hoshiestar. Usa o mesmo mecanismo do
 * Crucible.yml ({@link YamlConfig} do Crucible, com {@code @Comment}/{@code @Comments}); os nomes
 * dos campos viram caminhos trocando "_" por "." (ex.: {@code autoteste_ligado} vira
 * {@code autoteste: ligado:}).
 *
 * <p><b>Quando e lido:</b> uma vez, na fase de coremod (em {@code CrucibleCoremodHook}, logo
 * depois do Crucible.yml e antes de qualquer classe do Minecraft ou de mod). Mudou? Reinicie.
 * Esta classe so pode usar o JDK e o pacote de config do Crucible (nada do Minecraft).
 *
 * <p><b>Regras de leitura</b> (diferentes do Crucible.yml num ponto: aqui um erro nunca apaga o
 * texto do admin):
 * <ul>
 * <li>arquivo nao existe: e criado com todos os campos, os padroes e os comentarios;</li>
 * <li>arquivo existe, tudo e valido e nada falta: os valores do admin valem e o arquivo NAO e
 * regravado (comentarios do admin, data de modificacao e permissao ficam como estao);</li>
 * <li>tudo valido mas falta algum item (campo novo de uma versao nova, ou item presente sem valor,
 * como "ligado:" vazio): o item usa o padrao e o arquivo e regravado com ele e com os comentarios
 * atuais (como o Crucible faz com o Crucible.yml: comentarios proprios do admin se perdem, valores
 * ficam);</li>
 * <li>algum problema: valor invalido (ex.: "talvez" onde se espera true/false), secao no formato
 * errado (ex.: "autoteste: true" em vez de "autoteste:" com "ligado: true" dentro) ou chave
 * desconhecida (erro de digitacao): aquele item usa o padrao, sai um aviso por problema, e o
 * arquivo NAO e regravado (o texto do admin fica como esta para ele corrigir);</li>
 * <li>arquivo ilegivel ou com lixo (nao e YAML, ou nao e um mapa): todos os campos usam o padrao,
 * sai um aviso, e o arquivo NAO e tocado;</li>
 * <li>nada disso derruba o servidor.</li>
 * </ul>
 *
 * <p><b>Como acrescentar um campo</b> (cada recurso novo faz isso): um campo publico, nao final,
 * nao estatico, com o padrao "desligado" e um {@code @Comments} em portugues; o nome comeca com o
 * id do recurso (ex.: {@code chunkmap_ligado}). Tipos aceitos: boolean, int, long, double, String
 * e listas (como no Crucible.yml).
 */
public final class HoshiestarConfig extends YamlConfig {

    private static final String[] CABECALHO = {
        "Hoshiestar - um interruptor por recurso.",
        "Desligado = o servidor se comporta como o Crucible original naquele ponto.",
        "Este arquivo e lido uma vez, no boot. Mudou? Reinicie o servidor.",
        "Valor invalido, secao no formato errado ou chave desconhecida: aquele item usa o padrao,",
        "sai um aviso no log e o arquivo nao e regravado.",
    };

    // ---------------------------------------------------------------- campos (um por interruptor)

    @Comments({"Autoteste das correcoes embutidas (so para diagnostico; deixe desligado em producao).",
        "Ligado: aplica duas correcoes inofensivas numa classe do OpenBlocks (so contam chamadas) e",
        "tenta uma correcao com trava de versao errada de proposito noutra classe, para provar que a",
        "recusa funciona. O resultado aparece no log ao fim do boot e em 'hoshie status'."})
    public boolean autoteste_ligado = false;

    // ---- chunkmap ----
    @Comments({"Cache de ultima consulta do mapa de chunks (thermos.wrapper.ChunkBlockHashMap), ate 3,6% do tick.",
        "Ligado: cada mundo ganha uma tabela de 64x64 posicoes que lembra o ultimo chunk achado em cada",
        "posicao, no lugar do cache de 4 entradas do Crucible. So a thread do servidor usa e grava essa",
        "tabela; consultas de outras threads leem direto o mapa. Se outra thread mudar o mapa (por ou tirar",
        "chunk) ou se der qualquer erro, o cache daquele mundo se desliga sozinho e volta ao codigo original.",
        "Desligado: o codigo original do Crucible, sem mudanca."})
    public boolean chunkmap_ligado = false;

    @Comments({"Tamanho da tabela do chunkmap: lado = 2^bits chunks (4 a 7; 6 = 64x64 = 4096 posicoes, 16 KB por mundo).",
        "Fora de 4..7: usa 6 e avisa no log."})
    public int chunkmap_bits = 6;

    @Comments({"So para teste: a cada acerto do cache do chunkmap, confere tambem no mapa e conta divergencias",
        "(deve ser sempre 0; o numero aparece em 'hoshie status'). Custa mais que o original; deixe desligado."})
    public boolean chunkmap_conferir = false;

    // ---- parada ----
    @Comments({"Parada por SIGTERM sem crash e sem salvamento duplo (achado D-24).",
        "SIGTERM e o sinal que o sistema manda para um programa fechar ('kill', 'docker stop', alguns paineis).",
        "Desligado: o codigo original do Crucible. No SIGTERM o salvamento roda fora da thread do servidor,",
        "com o laco de tick ainda rodando: da erro, crash-report e o salvamento pode rodar duas vezes ao mesmo tempo.",
        "Ligado: no SIGTERM o servidor pede ao laco de tick que pare e espera a thread do servidor salvar e sair,",
        "como no comando 'stop'. Nunca dois salvamentos de parada ao mesmo tempo. O comando 'stop' nao muda.",
        "ANTES DE LIGAR: saiba como o painel para o servidor e quanto ele espera antes de matar o processo (SIGKILL).",
        "Esse prazo tem de cobrir, com o servidor TRAVADO: espera_laco_segundos + o tempo de um salvamento de parada."})
    public boolean parada_ligado = false;

    @Comments({"Parada: quantos segundos esperar o laco de tick parar depois do SIGTERM (1 a 3600).",
        "Se passar disso (servidor travado), o salvamento roda como no codigo original e sai um aviso no log.",
        "ATENCAO: com o servidor travado o salvamento SO COMECA depois desse tempo (desligado, comecaria na hora).",
        "Se o painel matar o processo antes de (este tempo + o salvamento), nada e salvo. Escolha um valor MENOR",
        "que o prazo do painel menos o tempo de um salvamento de parada. (Reinicio pelo vigia de travamento do",
        "Spigot, o 'watchdog', nao passa por esta espera: o salvamento comeca na hora, como hoje.)"})
    @Path("parada.espera_laco_segundos")
    public int parada_esperaLacoSegundos = 60;

    @Comments({"Parada: quantos segundos esperar um salvamento de parada que ja comecou em outra thread (1 a 7200).",
        "Se passar disso, quem esperava desiste SEM salvar ao mesmo tempo. ATENCAO: se quem esperava era o",
        "desligamento da JVM (SIGTERM), a JVM SAI e o salvamento em curso e CORTADO no meio (parte do mundo nova,",
        "parte velha). Deixe este valor bem acima do salvamento de parada mais demorado do seu mundo."})
    @Path("parada.espera_salvar_segundos")
    public int parada_esperaSalvarSegundos = 600;

    @Comments({"Parada: depois de o mundo estar salvo, quantos segundos esperar a thread do servidor terminar",
        "o fechamento (avisos de 'servidor parado' aos mods) antes de deixar a JVM sair (0 a 600).",
        "So vale quando a thread do servidor ja saiu do laco de tick; com ela travada num tick nao ha essa espera."})
    @Path("parada.folga_saida_segundos")
    public int parada_folgaSaidaSegundos = 30;

    // ---- vigia ----
    @Comments({"Vigia de tick lento (item 1.H): quando um tick passa do limite, grava quem, onde e qual mod.",
        "So le e escreve relatorio: nao muda nada no jogo, nao interrompe nem para a thread do servidor.",
        "Ligado: a thread do servidor so anota, a cada tick/entidade/tile, o que esta rodando (uma ou duas",
        "escritas de campo); uma thread propria ('Hoshiestar Vigia') confere 10 vezes por segundo e, se um tick",
        "passar do limite, tira amostras da pilha da thread do servidor e grava um relatorio no log e num arquivo",
        "hoshiestar/vigia-*.txt. Desligado: nenhuma anotacao e nenhuma thread (o codigo original)."})
    public boolean vigia_ligado = true;

    @Comments({"Vigia: duracao (ms) de um tick que gera relatorio (500 a 600000). O tick normal tem 50 ms.",
        "O vigia do Spigot (watchdog) so avisa a partir de 5 s e nao e alterado por este."})
    @Path("vigia.limite_ms")
    public int vigia_limiteMs = 2000;

    @Comments({"Vigia: quantas amostras da pilha tirar enquanto o mesmo tick continua lento (1 a 20)."})
    @Path("vigia.amostras")
    public int vigia_amostras = 5;

    @Comments({"Vigia: intervalo (ms) entre as amostras da pilha (50 a 10000)."})
    @Path("vigia.intervalo_amostras_ms")
    public int vigia_intervaloAmostrasMs = 500;

    @Comments({"Vigia: intervalo minimo (segundos) entre dois relatorios (1 a 3600). Num travamento longo sai um",
        "relatorio a cada intervalo destes enquanto o mesmo tick continuar travado (dentro do maximo abaixo).",
        "Ticks lentos que nao geram relatorio sao contados e aparecem no proximo relatorio e no 'hoshie status'."})
    @Path("vigia.intervalo_minimo_segundos")
    public int vigia_intervaloMinimoSegundos = 60;

    @Comments({"Vigia: no maximo quantos relatorios por janela (1 a 1000); a janela esta logo abaixo."})
    @Path("vigia.max_relatorios")
    public int vigia_maxRelatorios = 10;

    @Comments({"Vigia: tamanho da janela (minutos) do maximo de relatorios (1 a 1440)."})
    @Path("vigia.janela_minutos")
    public int vigia_janelaMinutos = 60;

    @Comments({"Vigia: quantas linhas (quadros) da pilha mostrar por amostra (5 a 200)."})
    @Path("vigia.quadros")
    public int vigia_quadros = 40;

    @Comments({"Vigia: escrever no relatorio o NOME do jogador do ultimo pacote visto e do jogador que estava",
        "sendo atualizado. false = o relatorio diz so 'jogador (nome oculto)', com dimensao e posicao.",
        "O relatorio nunca leva IP nem texto de pacote (chat, placa, comando): so a classe do pacote e campos numericos."})
    @Path("vigia.nome_do_jogador")
    public boolean vigia_nomeDoJogador = true;

    @Comments({"Vigia: no maximo quantos arquivos hoshiestar/vigia-*.txt guardar (10 a 10000); os mais velhos sao apagados."})
    @Path("vigia.max_arquivos")
    public int vigia_maxArquivos = 200;

    @Comments({"Vigia: carencia (segundos) depois que o servidor termina de ligar (0 a 3600). Ticks lentos nesse",
        "periodo so sao contados (uma linha no log e no 'hoshie status'), sem relatorio: o primeiro tick depois do",
        "boot costuma passar de 2 s (salva e descarrega chunks do boot). Um tick que continuar travado depois da",
        "carencia gera relatorio normalmente."})
    @Path("vigia.carencia_segundos")
    public int vigia_carenciaSegundos = 30;

    // ---- medidor ----
    @Comments({"Medidor de carga por regiao (tarefa 0.9): mede onde, no mapa, vai o tempo de tick de tile entities e",
        "entidades, por dimensao, regiao de 256x256 blocos e chunk. So mede quando o admin manda, por tempo limitado:",
        "'hoshie medir <segundos>' (10 a 1800; 'hoshie parar' encerra antes). Grava hoshiestar/medicao-*.json e .txt",
        "(sem nome de jogador, UUID nem IP; nada vai para a rede). Parado, custa uma leitura de variavel por",
        "entidade/tile entity. habilitado: false = o comando recusa e nada e medido (o caminho original)."})
    public boolean medidor_habilitado = true;

    @Comments({"Medidor: margem da regra de custo da valvula (1.0 a 20.0). Enquanto mede, a valvula para a medicao",
        "(gravando o que ja coletou) se o custo ESTIMADO do medidor vezes esta margem passar de 10% do tick de antes",
        "de ligar (no minimo 5 ms, no maximo 10 ms por tick). No servidor de teste o custo real deu 3 a 5 vezes a",
        "estimativa; 4.0 cobre isso. Menor = deixa medir mais no pico, mas o medidor pode pesar mais de 10% do tick",
        "sem a valvula parar. A outra regra da valvula (o tick ficou bem mais lento que antes de ligar) nao muda."})
    @Path("medidor.margem_custo")
    public double medidor_margemCusto = 4.0;

    // ---- mekanism ----
    @Comments({"Mekanism, matriz de inducao: leitura e escrita rapidas da energia (item 1.C; ate 8% do tick medido).",
        "A energia continua morando em cada celula e e SEMPRE lida e escrita ao vivo nas celulas; o que fica",
        "guardado e so 'qual objeto de celula esta em cada coordenada', conferido a cada chamada.",
        "  desligado = Mekanism original (a classe do mod nem e alterada);",
        "  verificar = usa sempre o caminho original e so CONTA quando o caminho rapido daria outra resposta",
        "              (para testar; custa mais que o original); veja 'hoshie status';",
        "  ligado    = usa o caminho rapido quando todas as conferencias passam; na duvida, o original.",
        "So vale com Mekanism-1.7.10-9.10.48-AkashicPerf.3 (trava de versao); com outro jar fica desligado sozinho."})
    public String mekanism_modo = "desligado";

    @Comments({"De quantos em quantos ticks a matriz refaz do zero a lista de celulas (1 a 20). 1 = todo tick (recomendado)."})
    public int mekanism_intervalo = 1;

    // ---- betterquesting ----
    @Comments({"BetterQuesting (bq_standard): checagem de inventario das tarefas de 'retrieval' (item 1.D; ate 4,6% do tick medido).",
        "Cada vez que o inventario de um jogador muda, o BQ reconta o inventario contra TODAS as tarefas de retrieval",
        "nao completas. Este recurso pula so as checagens que COMPROVADAMENTE nao mudam nada (nenhum item exigido",
        "esta nos inventarios varridos, sem ore dictionary, sem progresso parcial a zerar, nada a completar).",
        "  desligado = BetterQuesting original (a classe do mod nem e alterada);",
        "  verificar = roda SEMPRE o original e so CONTA (e registra no log, com limite) os casos em que o filtro",
        "              pularia e o original mudou alguma coisa (para testar; custa mais que o original); veja 'hoshie status';",
        "  ligado    = pula as checagens que nao podem mudar nada; na duvida, roda o original.",
        "So vale com BetterQuesting-3.8.79-GTNH (trava de versao); com outro jar fica desligado sozinho."})
    public String betterquesting_modo = "desligado";

    @Comments({"De quantos em quantos segundos escrever no log uma linha com os contadores do filtro (0 = nunca)."})
    public int betterquesting_relatorio = 0;

    // ---- arsmagica ----

    @Comments({"Ars Magica 2, varredura de todos os mundos (item 1.F; ate 2% do tick, ESTIMADO a partir de um perfil: nao e medicao).",
        "A cada pacote de movimento de cada jogador o AM2 percorre TODAS as entidades de TODOS os mundos carregados",
        "(e monta um texto de UUID por entidade viva) so para achar entidades aceleradas pelo feitico 'Accelerate'.",
        "  desligado = Ars Magica original (a classe do mod nem e alterada);",
        "  ligado    = pula a varredura SO quando nenhuma entidade esta acelerada (o mapa de aceleradas do AM2",
        "              esta vazio, e a varredura nao teria nada a fazer); com alguma acelerada, roda o original inteiro.",
        "So vale com AM2.5-LTS-1.6.11-LE-CR40 (trava de versao); com outro jar fica desligado sozinho."})
    public boolean arsmagica_varredura = false;

    @Comments({"Ars Magica 2, checagem do 'DiluteTime' em toda entidade viva (item 1.F; cerca de 0,35% do tick, ESTIMADO a partir de um perfil: nao e medicao).",
        "Em todo tick de toda entidade viva o AM2 consulta a habilidade 'DiluteTime' (copiando listas) antes de olhar",
        "o mapa de entidades retardadas.",
        "  desligado = Ars Magica original (a classe do mod nem e alterada);",
        "  ligado    = pula a consulta SO quando nenhuma entidade esta retardada (mapa de retardadas vazio, e o",
        "              resultado do AM2 seria o mesmo); com alguma retardada, roda o original inteiro.",
        "So vale com AM2.5-LTS-1.6.11-LE-CR40 (trava de versao); com outro jar fica desligado sozinho."})
    public boolean arsmagica_diluicao = false;

    @Comments({"De quantos em quantos minutos escrever no log uma linha com quantas chamadas foram puladas e quantas nao",
        "(0 = nunca). So conta e escreve se a correcao foi aplicada na classe do AM2 (algum dos dois atalhos ligado",
        "no boot); o comando 'hoshie arsmagica ... desligar' nao a cala (as pontes seguem contando como 'original').",
        "O ganho so existe enquanto os mapas do AM2 estao vazios; esta linha mostra se isso acontece no seu servidor."})
    public int arsmagica_estatistica = 5;

    // ---- chromaticraft ----

    @Comments({"ChromatiCraft, habilidade 'mover area' (SHIFT): limites para ela nao travar o servidor (item 1.B; o servidor ficou 30 s sem",
        "tick por causa dela). Ligado = recusa o pedido ANTES de ler ou mexer em qualquer bloco quando uma das conferencias abaixo falha;",
        "pedido aceito roda o codigo original do mod, sem nenhuma mudanca. A recusa toca o som de erro, desliga a habilidade no jogador,",
        "manda uma mensagem no chat e escreve uma linha no log (com limite de frequencia). Nao devolve a energia gasta para armar a",
        "habilidade (o original tambem nao devolve quando falta energia). Vale tambem para jogadores em criativo e operadores.",
        "So vale com 'ChromatiCraft 1.7.10 V33a' e 'DragonAPI 1.7.10 V33b-ForbiddenFix1' (trava de versao); com outro jar fica desligado sozinho.",
        "  desligado = ChromatiCraft original (a classe do mod nem e alterada)."})
    public boolean chromaticraft_ligado = false;

    @Comments({"Conferencia 1: tempo minimo, em milissegundos, entre dois 'mover area' ACEITOS do mesmo jogador. 0 = nao confere."})
    public int chromaticraft_intervalo = 1000;

    @Comments({"Conferencia 2: recusa distancia menor que 0 ou maior que o maximo da habilidade (24). Cliente normal nunca manda outro valor."})
    public boolean chromaticraft_distancia = true;

    @Comments({"Conferencia 3: volume maximo da caixa, em blocos, contando as posicoes que o mod percorre: (dx+1)*(dy+1)*(dz+1).",
        "0 = sem limite. O padrao 4096 (16x16x16) e PROVISORIO: o valor final sai da medicao no servidor (veja o doc)."})
    public int chromaticraft_volume = 4096;

    @Comments({"Conferencia 4: recusa se o destino nao cabe inteiro em y de 0 a 255. Sem isto o mod apaga a origem e o bloco que sai do mundo se perde."})
    public boolean chromaticraft_altura = true;

    @Comments({"Conferencia 5: recusa se algum chunk da origem ou do destino (caixa alargada em 1 bloco) nao esta carregado.",
        "A consulta so le o mapa de chunks carregados: nunca carrega nem gera chunk."})
    public boolean chromaticraft_chunks = true;

    @Comments({"Mensagem de chat para o jogador cuja acao foi recusada. {motivo} vira o texto do motivo. Vazio = nao manda mensagem."})
    public String chromaticraft_mensagem = "Mover area recusado: {motivo}.";

    @Comments({"Registra no log todo 'mover area' que demorar pelo menos tantos milissegundos (0 = nao mede nem registra).",
        "A medicao e uma segunda correcao embutida (envolve duas chamadas do metodo em pontes protegidas; so mede, e as chamadas originais acontecem do mesmo jeito)."})
    public int chromaticraft_loglento = 50;

    @Comments({"No maximo uma linha de log de recusa por jogador a cada tantos segundos (minimo 1); as demais so entram na conta."})
    public int chromaticraft_logsegundos = 10;

    // ---- observador ----
    @Comments({"Observador (Etapa 2): anota, em tabelas de contagem, 'o mod X, executando a partir da regiao A, tocou a posicao",
        "P da regiao B' (leitura/escrita de bloco, tile entity, entidade, tick agendado, carga de chunk...). So LE e ANOTA: nao",
        "barra nada, nao muda o que nenhum metodo devolve. So anota quando o admin manda, por tempo limitado:",
        "'hoshie observar <segundos>' (5 a 3600; 'hoshie observar parar' encerra antes; 'hoshie observar status').",
        "Grava hoshiestar/observador-*.json e .txt (sem nome de jogador, UUID nem IP; nada vai para a rede).",
        "ligado: false = o comando recusa e nenhum gancho faz nada alem de um teste de variavel (comportamento original)."})
    public boolean observador_ligado = false;

    @Comments({"Observador: a pilha de chamadas e olhada em 1 de cada tantos acessos que cruzam secao (1 a 1000000), no maximo",
        "20 por tick, para descobrir QUAL mod fez o acesso quando o contexto nao diz. Menor = mais amostras e mais custo."})
    @Path("observador.amostra_pilha")
    public int observador_amostraPilha = 256;

    @Comments({"Observador: valvula de custo (0.5 a 50). Se o custo ESTIMADO do observador passar desta porcentagem do tempo",
        "do tick (media de 100 ticks, e mais de 0,25 ms por tick), ele para sozinho e grava o que ja tem."})
    @Path("observador.limite_custo_pct")
    public double observador_limiteCustoPct = 5.0;

    @Comments({"Observador: prototipo da lista de tiles (plano da Etapa 2, secao 4). Lido so no boot; so vale com ligado: true.",
        "true = cada mundo cria a lista de tiles (loadedTileEntityList) como uma lista nossa que devolve EXATAMENTE o mesmo",
        "que a original e, durante 'hoshie observar', conta quem a usa de fora do World (operacao, contexto, mod). Serve para",
        "decidir se da para trocar o objeto da lista por uma lista por regiao sem quebrar mods.",
        "false = nenhuma troca de objeto (o comportamento original)."})
    @Path("observador.lista")
    public boolean observador_lista = false;

    // ---- orcamento ----
    @Comments({"Orcamento de tick por regiao (Etapa 2.5, Degrau A): mede o tempo de cada regiao do mapa (secoes de 16x16 chunks;",
        "regioes a ate 3 secoes uma da outra se juntam), mostra TPS e ms por regiao ('hoshie regioes'), avisa os operadores quando",
        "uma regiao comeca a pesar e diz onde e. No modo 'cortar' a regiao que passa do orcamento (com o servidor acima do alvo)",
        "roda 1 tick a cada N, inteira (tiles, entidades, ticks agendados e aleatorios), para isolar o lag; as maquinas dela ficam N",
        "vezes mais lentas. Numeros de carga sintetica sao indicacao, nao medicao de producao.",
        "ligado: false = nenhum gancho faz nada alem de um teste de variavel (comportamento original). Lido so no boot."})
    public boolean orcamento_ligado = false;

    @Comments({"Orcamento: modo inicial. 'medir' (padrao) calcula e avisa sem cortar nada; 'cortar' aplica o corte (jogadores,",
        "spawn e areas protegidas nunca sao cortados). Troca em jogo: 'hoshie regioes modo medir|cortar'."})
    @Path("orcamento.modo")
    public String orcamento_modo = "medir";

    @Comments({"Orcamento: so age quando a media do tick do servidor, em 100 ticks, passa deste valor em ms (10 a 50)."})
    @Path("orcamento.alvo_tick_ms")
    public double orcamento_alvoTickMs = 45.0;

    @Comments({"Orcamento: quanto uma regiao pode usar por tick real, em ms (0,5 a 50). Acima disso, e com o servidor acima do alvo,",
        "ela passa a ticar 1 vez a cada N = ceil(custo por rodada / este valor), entre 2 e n_maximo."})
    @Path("orcamento.regiao_ms")
    public double orcamento_regiaoMs = 10.0;

    @Comments({"Orcamento: rodada acima de tantos ms (5 a 1000) obriga N a ser pelo menos 100 / pico_fracao_pct: a rodada pesada",
        "cai em no maximo pico_fracao_pct por cento dos ticks e o p95 do servidor nao a ve."})
    @Path("orcamento.pico_ms")
    public double orcamento_picoMs = 40.0;

    @Comments({"Orcamento: porcentagem maxima de ticks com a rodada pesada (1 a 50)."})
    @Path("orcamento.pico_fracao_pct")
    public int orcamento_picoFracaoPct = 4;

    @Comments({"Orcamento: maior N (2 a 200): a regiao nunca roda menos que 1 vez a cada n_maximo ticks."})
    @Path("orcamento.n_maximo")
    public int orcamento_nMaximo = 40;

    @Comments({"Orcamento: de quantos em quantos ticks o mapa de regioes e recalculado (40 a 2400). Tambem recalcula antes (no minimo",
        "20 ticks depois) quando acha tile, entidade ou tick agendado numa secao que nao esta no mapa."})
    @Path("orcamento.recalculo_ticks")
    public int orcamento_recalculoTicks = 100;

    @Comments({"Orcamento: true = a regiao do spawn da dimensao 0 nunca e cortada."})
    @Path("orcamento.proteger_spawn")
    public boolean orcamento_protegerSpawn = true;

    @Comments({"Orcamento: dimensoes que nunca entram no corte, numeros separados por virgula (ex.: 0,-1). Vazio = nenhuma."})
    @Path("orcamento.nunca_cortar_dimensoes")
    public String orcamento_nuncaCortarDimensoes = "";

    @Comments({"Orcamento: areas (ex.: de eventos) que nunca entram no corte: dim:x1,z1,x2,z2 em blocos, varias separadas por ';'.",
        "A regiao inteira que tocar a area fica protegida. Vazio = nenhuma."})
    @Path("orcamento.nunca_cortar_areas")
    public String orcamento_nuncaCortarAreas = "";

    @Comments({"Orcamento: mods cujas regioes nunca sao cortadas: pedacos do nome do jar, separados por virgula, sem diferenca entre",
        "maiusculas e minusculas (ex.: teleporter,entangloporter). Serve para ligacoes sem fio, cujas pontas podem ficar em regioes",
        "diferentes. Vazio = nenhum."})
    @Path("orcamento.nunca_cortar_mods")
    public String orcamento_nuncaCortarMods = "";

    @Comments({"Orcamento: dimensoes que viram UMA regiao so (ex.: uma dimensao de mineracao pequena), separadas por virgula. Vazio = nenhuma."})
    @Path("orcamento.sempre_junto_dimensoes")
    public String orcamento_sempreJuntoDimensoes = "";

    @Comments({"Orcamento: true = a regiao com jogador dentro tambem pode ser cortada (o jogador em si nunca e cortado).",
        "false = regiao com jogador nunca e cortada."})
    @Path("orcamento.cortar_com_jogador")
    public boolean orcamento_cortarComJogador = true;

    @Comments({"Orcamento: alerta quando o custo de uma regiao passa de tantos ms por rodada (1 a 1000): o que ela custaria por tick",
        "sem corte. No log e para os operadores online; diz a dimensao, a caixa em blocos e o centro. Nunca nome de jogador nem IP."})
    @Path("orcamento.alerta_ms")
    public double orcamento_alertaMs = 25.0;

    @Comments({"Orcamento: no minimo tantos minutos (1 a 1440) entre dois alertas iguais da mesma regiao (e no maximo 10 por minuto no total)."})
    @Path("orcamento.alerta_intervalo_min")
    public int orcamento_alertaIntervaloMin = 5;

    @Comments({"Orcamento: true = o alerta tambem vai para os operadores online (alem do log). false = so no log."})
    @Path("orcamento.alerta_ops")
    public boolean orcamento_alertaOps = true;

    // ---- chunkmap.cache4Seguro ----
    @Comments({"Correcao do cache de 4 entradas do codigo ORIGINAL do mapa de chunks (achado H01). Nao depende de",
        "chunkmap.ligado: vale sempre que o mapa esta no codigo original (chunkmap desligado, ou cache novo desligado",
        "por seguranca). Ligado: (1) consulta de thread que nao e a do servidor nao grava mais nesse cache (le, e se nao",
        "achar vai ao mapa); (2) tirar um chunk limpa TODAS as casas do cache com aquela coordenada, e por um chunk",
        "tira antes a casa velha da mesma coordenada. Sem isso, duas threads consultando juntas podem deixar um chunk",
        "ja descarregado no cache (item posto nele se perde, item tirado duplica). Necessario para o recurso paralelo.",
        "Desligado: o codigo original do Crucible nesses pontos."})
    public boolean chunkmap_cache4Seguro = false;

    // ---- paralelo ----
    @Comments({"Fase paralela sincronizada com barreira (Degrau B): tiles de regioes diferentes tickam em threads de regiao",
        "enquanto a thread do servidor espera, e tudo volta a ser serie na juncao. NESTA VERSAO EXISTEM 'sombra' (tudo em",
        "serie, na ordem de sempre; o servidor classifica as regioes e as guardas so contam o que seria ilegal na fase) e",
        "'sombra_ordenada' (a thread do servidor roda sozinha os lotes das regioes elegiveis depois do laco, com os adiados e",
        "a juncao; nenhuma thread de regiao). Ver 'hoshie paralelo estado' e 'motivos'. O modo paralelo ainda recusa ligar.",
        "ligado: false = comportamento original. Lido so no boot. O recurso recusa ligar (motivo no log, em 'hoshie paralelo",
        "motivos' e em 'hoshie status') se: orcamento.ligado for false; o modo for sombra_ordenada ou paralelo e parada.ligado",
        "for false; o modo for paralelo e chunkmap.cache4Seguro for false; a dimensao nao existir; houver classe fora da lista;",
        "algum valor abaixo estiver fora da faixa; ou, com os fatos do jogo lidos quando o servidor fica pronto: world.rand",
        "trocado, distancia de simulacao ou preventChunkLoadingFromBlockUpdates do hodgepodge ligados, observador ou watcher",
        "do DragonAPI nao revisado, firewall sem allowTileEntityTick, mixin ou transformador sem veredito, ou fato nao lido."})
    public boolean paralelo_ligado = false;

    @Comments({"Paralelo: modo. 'sombra' (tudo em serie, so conta o que seria ilegal), 'sombra_ordenada' (a thread do servidor",
        "roda os lotes sozinha, com adiamentos) ou 'paralelo' (threads de regiao de verdade). Os dois ultimos exigem parada.ligado."})
    @Path("paralelo.modo")
    public String paralelo_modo = "sombra";

    @Comments({"Paralelo: numero da dimensao (uma so; 0 = mundo principal). Tem de existir no servidor."})
    @Path("paralelo.dimensao")
    public int paralelo_dimensao = 0;

    @Comments({"Paralelo: threads de regiao (1 a 6). A thread do servidor nao pega lote."})
    @Path("paralelo.threads")
    public int paralelo_threads = 3;

    @Comments({"Paralelo: classes de tile liberadas, separadas por virgula, entre: mesa, bau, bau_ender.",
        "Vazio = fase vazia (nenhum tile sai da serie)."})
    @Path("paralelo.classes")
    public String paralelo_classes = "";

    @Comments({"Paralelo: o que fazer se um tile de regiao lancar excecao. 'parar' (padrao: crash-report e parada que salva) ou",
        "'rebaixar' (a dimensao fica em serie ate o proximo boot)."})
    @Path("paralelo.ao_falhar")
    public String paralelo_aoFalhar = "parar";

    @Comments({"Paralelo: o que fazer numa violacao (dono ou invariante quebrado). 'parar' (padrao) ou 'rebaixar'."})
    @Path("paralelo.ao_violar")
    public String paralelo_aoViolar = "parar";

    @Comments({"Paralelo: em modo paralelo, confere 1 a cada N leituras (0 a 1000000; 0 = nao confere)."})
    @Path("paralelo.leitura_amostra")
    public int paralelo_leituraAmostra = 64;

    @Comments({"Paralelo: fatia da espera na barreira, no pedido delegado e no portao, em segundos (1 a 3600).",
        "So avisa no log; nunca libera uma operacao proibida nem interrompe uma thread."})
    @Path("paralelo.aviso_espera_s")
    public int paralelo_avisoEsperaS = 5;

    @Comments({"Paralelo: depois de um sinal (algo que torna a fase insegura), quantos ticks ficar em sombra (0 a 1728000)."})
    @Path("paralelo.sinal_ticks")
    public int paralelo_sinalTicks = 1200;

    @Comments({"Paralelo: depois do boot, quantos ticks ficar em sombra antes de abrir a fase (0 a 1728000)."})
    @Path("paralelo.aquecimento_ticks")
    public int paralelo_aquecimentoTicks = 600;

    // ---------------------------------------------------------------- leitura

    private static volatile HoshiestarConfig atual;

    private final transient List<String> avisos = new ArrayList<String>();
    private transient boolean usouPadroes;

    private HoshiestarConfig(File arquivo) {
        CONFIG_FILE = arquivo;
        CONFIG_MODE = ConfigMode.PATH_BY_UNDERSCORE;
        CONFIG_HEADER = CABECALHO;
    }

    /** O config lido no boot; se ainda nao foi lido, os padroes (sem tocar em arquivo). */
    public static HoshiestarConfig atual() {
        HoshiestarConfig c = atual;
        return c != null ? c : new HoshiestarConfig(new File(Hoshiestar.ARQUIVO_CONFIG));
    }

    /** Le o arquivo padrao (hoshiestar.yml na pasta do servidor) e guarda como {@link #atual()}. */
    public static HoshiestarConfig carregarPadrao() {
        HoshiestarConfig c = carregar(new File(Hoshiestar.ARQUIVO_CONFIG));
        atual = c;
        return c;
    }

    /**
     * Le (ou cria) o arquivo dado seguindo as regras da classe. Nunca lanca excecao: em erro devolve
     * os padroes; os avisos ficam em {@link #avisos()}.
     */
    public static HoshiestarConfig carregar(File arquivo) {
        HoshiestarConfig c = new HoshiestarConfig(arquivo);
        try {
            c.ler();
        } catch (Throwable t) {
            HoshiestarConfig p = new HoshiestarConfig(arquivo);
            p.usouPadroes = true;
            p.avisos.addAll(c.avisos);
            p.avisos.add("erro inesperado ao ler " + arquivo + ": " + t + "; usando os padroes");
            return p;
        }
        return c;
    }

    /** Avisos da leitura (valores invalidos, arquivo ilegivel, erro ao gravar). Vazio = tudo certo. */
    public List<String> avisos() {
        return Collections.unmodifiableList(avisos);
    }

    /** true se o arquivo nao pode ser lido e todos os campos ficaram no padrao. */
    public boolean usouPadroes() {
        return usouPadroes;
    }

    /** O arquivo deste config. */
    public File arquivo() {
        return CONFIG_FILE;
    }

    private void ler() {
        if (!CONFIG_FILE.exists()) {
            gravar("criar");
            return;
        }
        try {
            loadFromYaml();
        } catch (InvalidConfigurationException e) {
            usouPadroes = true;
            Throwable causa = e.getCause() != null ? e.getCause() : e;
            avisos.add("nao consegui ler " + CONFIG_FILE + " (" + resumo(causa)
                    + "); TODOS os itens usam o padrao e o arquivo NAO foi alterado. Corrija ou apague o arquivo.");
            return;
        }
        HoshiestarConfig padroes = new HoshiestarConfig(CONFIG_FILE);
        List<String> conhecidos = new ArrayList<String>();
        int problemas = 0;
        int faltando = 0;
        for (Field f : getClass().getDeclaredFields()) {
            if (doSkip(f)) {
                continue;
            }
            String caminho = caminho(f);
            conhecidos.add(caminho);
            // secao no formato errado (ex.: "autoteste: true" em vez de "autoteste:" com "ligado: true"
            // dentro): sem isto a chave contaria como "faltando" e o valor do admin sumiria sem aviso
            String errado = prefixoQueNaoESecao(caminho);
            if (errado != null) {
                problemas++;
                avisos.add("'" + errado + "' deveria ser uma secao (com '" + caminho.substring(errado.length() + 1)
                        + ":' dentro), mas tem o valor " + descreverValor(valorBruto(errado)) + "; '" + caminho + "' usa o padrao " + valorDe(f, padroes));
                continue;
            }
            Object bruto = root.has(caminho) ? valorBruto(caminho) : null;
            if (bruto == null) {
                // ausente, ou presente sem valor ("ligado:" vazio): usa o padrao e entra no arquivo
                faltando++;
                continue;
            }
            try {
                if (Modifier.isPrivate(f.getModifiers())) {
                    f.setAccessible(true);
                }
                converter.fromConfig(this, f, root, caminho);
            } catch (Throwable t) {
                problemas++;
                try {
                    f.set(this, f.get(padroes));
                } catch (Throwable t3) {
                    // campo continua com o valor do construtor (o padrao)
                }
                avisos.add("valor invalido em '" + caminho + "' (" + descreverValor(bruto) + ", esperado "
                        + f.getType().getSimpleName() + "); usando o padrao " + valorDe(f, padroes));
            }
        }
        List<String> desconhecidas = new ArrayList<String>();
        procurarDesconhecidas(root, "", conhecidos, desconhecidas);
        for (String d : desconhecidas) {
            problemas++;
            avisos.add("chave desconhecida '" + d + "' (ignorada; erro de digitacao, ou item de outra versao do Hoshiestar?)");
        }
        if (problemas > 0) {
            avisos.add(CONFIG_FILE + " nao foi regravado para preservar o seu texto; corrija o(s) item(ns) acima"
                    + (faltando > 0 ? " (faltam tambem " + faltando + " item(ns), que usam o padrao)" : ""));
            return;
        }
        if (faltando > 0) {
            // so regrava quando ha item novo a acrescentar: um arquivo completo e valido fica como o
            // admin deixou (comentarios dele, data de modificacao, permissao somente-leitura)
            gravar("atualizar");
        }
    }

    /** O valor cru no caminho, sem criar secoes no caminho (o get do ConfigSection cria). */
    private Object valorBruto(String caminho) {
        Object atual = root;
        for (String parte : caminho.split("\\.")) {
            if (!(atual instanceof ConfigSection)) {
                return null;
            }
            atual = ((ConfigSection) atual).getRawMap().get(parte);
        }
        return atual;
    }

    /**
     * O primeiro prefixo do caminho (ex.: "autoteste" em "autoteste.ligado") que existe no arquivo
     * com um valor que nao e uma secao, ou null. Prefixo presente e vazio conta como secao vazia.
     */
    private String prefixoQueNaoESecao(String caminho) {
        int i = caminho.indexOf('.');
        while (i > 0) {
            String prefixo = caminho.substring(0, i);
            Object v = valorBruto(prefixo);
            if (v != null && !(v instanceof ConfigSection)) {
                return prefixo;
            }
            if (v == null) {
                return null;
            }
            i = caminho.indexOf('.', i + 1);
        }
        return null;
    }

    /** Chaves do arquivo que nao sao campo desta classe nem secao que leva a um campo. */
    private static void procurarDesconhecidas(ConfigSection secao, String base, List<String> conhecidos, List<String> saida) {
        for (Object k : secao.getRawMap().keySet()) {
            String caminho = base.isEmpty() ? String.valueOf(k) : base + "." + k;
            if (conhecidos.contains(caminho)) {
                continue;
            }
            boolean prefixo = false;
            for (String c : conhecidos) {
                if (c.startsWith(caminho + ".")) {
                    prefixo = true;
                    break;
                }
            }
            if (!prefixo) {
                saida.add(caminho);
            } else {
                Object v = secao.getRawMap().get(k);
                if (v instanceof ConfigSection) {
                    procurarDesconhecidas((ConfigSection) v, caminho, conhecidos, saida);
                }
                // prefixo com valor que nao e secao ja foi avisado como "deveria ser uma secao"
            }
        }
    }

    private void gravar(String acao) {
        try {
            if (CONFIG_FILE.getParentFile() != null) {
                CONFIG_FILE.getParentFile().mkdirs();
            }
            save();
        } catch (Throwable t) {
            avisos.add("nao consegui " + acao + " " + CONFIG_FILE + " (" + resumo(t) + "); os valores lidos continuam valendo");
        }
    }

    private String caminho(Field f) {
        if (f.isAnnotationPresent(Path.class)) {
            return f.getAnnotation(Path.class).value();
        }
        return f.getName().replace('_', '.');
    }

    private static String valorDe(Field f, Object o) {
        try {
            return String.valueOf(f.get(o));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String descreverValor(Object v) {
        if (v == null) {
            return "vazio";
        }
        String s = String.valueOf(v);
        if (s.length() > 40) {
            s = s.substring(0, 40) + "...";
        }
        return "'" + s + "' (" + v.getClass().getSimpleName() + ")";
    }

    private static String resumo(Throwable t) {
        String m = t.getMessage();
        if (m != null && m.length() > 200) {
            m = m.substring(0, 200) + "...";
        }
        return t.getClass().getSimpleName() + (m != null ? ": " + m.replace('\n', ' ') : "");
    }
}
