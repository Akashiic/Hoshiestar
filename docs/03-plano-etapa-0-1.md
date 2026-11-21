# Plano detalhado: Etapa 0 (medição) e Etapa 1 (fora da thread principal)

Cada tarefa tem: o que é, quem faz (modelo do subagente), como se sabe que está
pronta e o risco. Nenhuma tarefa escreve em servidor real.

Legenda de estado: ✅ feito · 🔄 em andamento · ⏳ a fazer · 🧑 depende do admin

---

## Etapa 0: Medição

Objetivo: saber, com número, **onde o tempo do tick vai** em produção, e ter um
ambiente de teste que qualquer sessão consegue montar de novo.

### 0.1 Ambiente de teste reproduzível ✅
- `scripts/prepare-test-env.sh` monta a cópia de teste e desliga 16 plugins (lista e motivo no script).
- `scripts/run-test-server.sh` resolve as bibliotecas e liga o servidor com console por arquivo.
- Pronto quando: uma pasta nova, criada do zero pelos dois scripts, chega a "Done" e para com `stop`. **Verificado.**

### 0.2 Inventário estático ✅
- `tools/static_inventory.py` → `docs/dados/inventario-mods.csv` e `inventario-plugins.csv`.
- Resultado em `DIAGNOSTICO.md` §2.

### 0.3 Linha de base ociosa ✅ (com ressalva)
- Medida em mundo novo: 3,1 ms por tick, 61 dimensões (`DIAGNOSTICO.md` §4).
- Ressalva: mundo novo contamina o perfil (D-10). Refazer quando houver cópia do mundo real.

### 0.4 Perfil sob carga real 🧑 **a medida mais importante do projeto**
O que preciso saber: em horário de pico, quanto do tick é
(a) tile entities e entidades por dimensão, (b) handlers globais de mods,
(c) plugins, (d) rede, salvamento e chunk.
Sem isso não dá para dizer quanto as regiões vão render.

Roteiro para o admin (só leitura; não muda nada no servidor):
1. No pico, rode `/forge tps` e copie a saída inteira.
2. Rode `/spark tps` e `/spark health` e copie.
3. Rode `/spark profiler start --timeout 300`. Ao terminar ele dá um link.
   Atenção: o spark publica o perfil num site externo. Se não quiser, use
   `/spark profiler start` e depois `/spark profiler stop --save-to-file`
   (não verifiquei se a versão 1.10 para 1.7.10 aceita essa opção; se recusar, me avise).
4. Rode `/timings on`, espere 10 minutos, `/timings report` (se disponível) e copie.
5. Com o Opis: `/opis_te` e `/opis_ent` para as tile entities e entidades mais caras.
6. Me diga: número de jogadores na hora, núcleos e RAM da máquina, flags da JVM.

Pronto quando: `DIAGNOSTICO.md` tiver a tabela "onde vai o tick em produção"
com esses números.

### 0.5 Mundo de teste com carga 🧑
- Preciso de uma cópia do mundo (ou só das regiões `.mca` de 3 a 5 bases pesadas mais `level.dat` e a pasta `data/`).
- Com isso o servidor de teste carrega bases reais e o perfil passa a significar algo.
- Alternativa sem o mundo: construir bases sintéticas por comando. Mais fraco, mas possível.

### 0.6 Bots de teste ⏳ (sonnet)
- Validar `minecraft-protocol` + `minecraft-protocol-forge` contra o servidor de teste na 1.7.10. **Não verificado** que funcione com o handshake FML dessa versão e com 292 mods.
- Entrega: script que conecta N bots, anda, e desconecta; relatório do que funcionou.
- Pronto quando: 20 bots ficam conectados 10 minutos sem serem expulsos. Se não der, registrar o motivo e o plano alternativo (cliente real sem tela, ou bots só para carga de chunk).
- Risco: alto de não funcionar de primeira. Não bloqueia a Etapa 1.

### 0.7 Compilar a base do zero ⏳ (sonnet; opus se o build quebrar)
- Clonar o Crucible, fazer checkout de `67b53034`, instalar JDK 8, `./gradlew setupCrucible buildPackages`.
- Pronto quando: o jar gerado liga o servidor de teste e chega a "Done" igual ao jar de produção.
- Risco: médio (dependências em repositório pessoal). Se uma dependência sumir, espelhar.
- Não é necessário para as Etapas 1 e 2, mas tem que estar provado antes da Etapa 3.

### 0.8 Conferir as estruturas hostis a threads no código decompilado ⏳ (opus)
- A lista de `docs/pesquisa/arte-previa.md` §4 é conhecimento geral. Conferir cada item no código do workspace gerado em 0.7.
- Entrega: tabela "estrutura, onde é usada, quem acessa de fora, tratamento previsto".

### Critério de saída da Etapa 0
1. Ambiente reproduzível ✅
2. Tabela "onde vai o tick em produção" preenchida (0.4)
3. Lista das 10 maiores fontes de custo, com dono (mod/plugin)
4. Base compilável do zero (0.7)
5. `DIAGNOSTICO.md` atualizado e `PROGRESSO.md` sem pendência da etapa

---

## Etapa 1: Tirar da thread principal o que é independente

Regra: **só atacar o que a Etapa 0 mostrar que pesa.** Se o salvamento já custa
1 ms, não se mexe nele. Cada item tem interruptor no config, desligado por padrão.

Formato de entrega: um mod `Hoshiestar-Core` com mixins "early", que roda no
`server.jar` atual. Não precisa do fork.

### 1.0 Auditoria do que já existe ⏳ (opus; leitura de código)
Antes de escrever qualquer coisa, ler e documentar:
- `Akashic-SaveRelief`: o que foi para a thread de I/O e o que ainda roda na principal.
- `Akashic-PlayerChunkAsync`: o que exatamente é assíncrono.
- Thread de iluminação do Crucible: está ligada? Quais são os pontos de sincronização?
- `ChunkIOExecutor` do Forge: tamanho do pool, o que ainda bloqueia.
- Opções relevantes de Hodgepodge, CoreTweaks e ArchaicFix (do lado servidor).

Entrega: tabela "trabalho, onde roda hoje, quem já tirou da thread principal, o que sobra".
Pronto quando: cada um dos 4 temas (geração, salvamento, luz, login) tem a linha
"já resolvido por X" ou "sobra Y, custa Z ms" (Z vindo da Etapa 0).

Preciso do admin 🧑: o código-fonte dos mods `Akashic-*` de desempenho, se puder
compartilhar. Sem ele, a auditoria é por decompilação (mais lenta e menos confiável).

### 1.1 Salvamento ⏳ (opus; revisão por outro agente, é concorrência)
- Hipótese: `SaveRelief` cobre `level.dat` e `data/*.dat`; a serialização dos chunks em NBT pode ainda rodar na thread principal.
- Proposta, se a medição confirmar: tirar uma cópia do chunk na thread principal (rápido) e serializar/gravar na thread de I/O.
- Teste: diferencial (mesmo mundo, salvo dos dois jeitos, arquivos equivalentes); teste de queda (matar o processo no meio do salvamento, mundo abre íntegro).
- Risco: **alto**. Erro aqui corrompe chunk. Só entra com os dois testes passando 50 vezes seguidas.

### 1.2 Carga de chunk no login e no teleporte ⏳ (opus)
- Hipótese: `PlayerChunkAsync` já cobre. Confirmar na auditoria.
- Se sobrar algo: carregar dados do jogador (`playerdata`, dados de plugins) fora da thread principal antes de colocar o jogador no mundo.
- Teste: 50 logins simultâneos com bots; tempo de tick durante o login.

### 1.3 Iluminação ⏳ (opus)
- O Crucible já tem thread de luz. Tarefa: medir se está ativa e se há disputa com a thread principal.
- Provável resultado: nada a fazer além de documentar e garantir que o modelo de regiões saiba dela.

### 1.4 Geração de chunks ⏳ (decisão de rumo, depois fable se for adiante)
É o item mais arriscado da etapa, e o prompt precisa saber disso:
- Na 1.7.10 a geração roda na thread principal e a "população" (árvores, minérios, estruturas) escreve em chunks vizinhos já carregados.
- Os geradores de mods (ChromatiCraft, Galacticraft, Twilight Forest, Thaumcraft...) não foram feitos para threads. `IntCache`, usado nos biomas, é estático.
- Proposta em ordem de risco:
  1. **Pré-gerar o mundo** (operacional, risco zero). Resolve a maior parte do custo.
  2. **Limite de geração por tick** (orçamento): gerar no máximo N ms de chunks novos por tick. Risco baixo.
  3. Geração do terreno-base em thread separada, com a população ainda na principal. Risco alto; só como experimento, desligado por padrão.
- Pronto quando: 1 e 2 medidos; 3 só se a Etapa 0 mostrar que geração ainda pesa depois de 1 e 2.

### 1.5 Interruptores e medição ⏳ (sonnet)
- Arquivo `config/hoshiestar.cfg`: um interruptor por recurso, tudo desligado por padrão.
- Comando `/hoshie status`: o que está ligado, e o tempo gasto em cada trabalho movido.
- Teste de não-regressão: com tudo desligado, mesmo comportamento e tick dentro de 5% do original.

### Critério de saída da Etapa 1
1. Tabela da auditoria 1.0 completa.
2. Cada recurso implementado tem: interruptor, teste diferencial, teste de queda quando grava em disco, e medição antes/depois.
3. Zero corrupção em todos os testes.
4. Código de concorrência revisado por um agente diferente do que escreveu.
5. Jar entregue ao admin com roteiro de teste de uma página; números do admin registrados.

---

## O que estas duas etapas não entregam

Elas **não isolam o lag de uma base**. Tiram picos (salvamento, login, geração)
da thread principal. O isolamento começa na Etapa 2.5 (orçamento de tick por
região). Se o perfil da Etapa 0 mostrar que o problema de produção é quase todo
tile entity e entidade, a Etapa 1 pode ser curta e o esforço vai para a 2 e a 2.5.
