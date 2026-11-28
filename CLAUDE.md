# Hoshiestar: instruções para o agente

**Coordenador (a sessão principal):** antes de qualquer coisa, leia nesta ordem:
1. `PROMPT.md` (o prompt do projeto: papel, objetivo, regras, etapas, administração de subagentes)
2. `PROGRESSO.md` (onde estamos, pendências, decisões já tomadas)
3. `DIAGNOSTICO.md` (o que já foi medido; não meça de novo sem motivo)

**Subagente (você foi chamado pelo coordenador):** não leia esses três arquivos inteiros (são ~90 KB). Leia só o que o briefing citar, com `grep` ou trecho. As regras duras abaixo valem para você também.

## Agentes do projeto (`.claude/agents/`)
Chame pelo nome. O modelo e o esforço já estão no arquivo de cada agente, com o ID completo da versão (`claude-haiku-5-5`, `claude-sonnet-5-5`, `claude-opus-5-5`, `claude-fable-5-2`), para não mudar sozinho quando sair versão nova (decisão 40 do `PROGRESSO.md`). Sessão principal: Opus 5.5; Fable 5.2 de advisor só nas etapas críticas. Quando o plano da etapa já diz o modelo de cada passo (ex.: `docs/09-plano-degrau-b.md` §11), vale o plano: chame o agente com aquele `model` (o passo 6 do Degrau B é escrito em Fable). Fora disso, para código de concorrência, chame o `implementador` com `model: claude-opus-5-5`; para risco de perder item, energia ou bloco, suba o esforço do `revisor` para alto.

| Agente | Modelo | Para quê |
|---|---|---|
| `coletor` | Haiku 5.5 | coleta e trabalho mecânico (listar, contar, extrair de log, formatar) |
| `investigador` | Opus 5.5 | causa raiz, desenho, estudo de código de terceiros; entrega relatório |
| `implementador` | Sonnet 5.5 | código a partir de especificação clara |
| `testador` | Sonnet 5.5 | roda roteiro de teste na cópia de teste e relata |
| `revisor` | Opus 5.5 | revisão que tenta quebrar a entrega (nunca quem construiu) |
| `revisor-concorrencia` | Fable 5.2 | só thread, lock, fila entre regiões, parada (nunca quem escreveu) |

Checagem que diz se o trabalho está pronto: `servidor/test.sh <workspace-do-fork>` (testes isolados; saída 0 = passou). Build: `servidor/build.sh --rapido <workspace> --mods <pasta mods/>`. Peça sempre o comando e a saída como prova.

Briefing de cada chamada: objetivo, arquivos de entrada, formato de saída, restrições e critério de "pronto". O agente não vê a conversa.

Regras duras (resumo; o texto completo está em `PROMPT.md`):
- **O Hoshiestar é um `server.jar` híbrido (fork do Crucible), não um mod. Não entregamos mods:** nenhum jar nosso em `mods/` ou `plugins/`. Correção em classe do servidor = patch no fork (`servidor/`, marcação `// Hoshiestar`); correção em mod de terceiros = embutida no jar, com trava de versão (SHA-256). Um interruptor por recurso em `hoshiestar.yml`; desligado = comportamento original.
- Nunca mexer no servidor real. Trabalhar só na cópia criada por `scripts/prepare-test-env.sh`.
- Nunca commitar, colar em conversa ou enviar para fora: jars, mundo, configs de produção, senhas e tokens.
- O profiler do spark publica o resultado em site externo. No projeto use Java Flight Recorder (`jcmd <pid> JFR.start ... filename=...`).
- Parar o servidor de teste com `stop` no console (`echo stop >> console.in`) sempre que der: `kill` derruba o laço de tick (D-24) e estraga o teste. O admin deu controle total das cópias de teste (2026-11-22), então `kill` é permitido **só em cópia de teste** em dois casos: (a) teste da própria parada por SIGTERM (item 1.G); (b) cópia travada, que não chegou ao "Done" em 15 min ou ignorou o `stop` por 5 min, e só depois de guardar dois thread dumps (`jcmd <pid> Thread.print`). Registrar cada uso.
- Não escrever código de etapa futura. Cada etapa passa nos testes dela antes da próxima.
- Decisão difícil de desfazer: perguntar ao admin. O resto: decidir e registrar em `PROGRESSO.md`.
- Código de concorrência é sempre revisado por um agente diferente do que escreveu.
- Não inventar medição nem assinatura de API. Marcar "não verificado" quando for o caso.
- Responder em português simples e explicar termos técnicos.
