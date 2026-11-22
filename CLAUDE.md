# Hoshiestar: instruções para o agente

Antes de qualquer coisa, leia nesta ordem:
1. `PROMPT.md` (o prompt do projeto: papel, objetivo, regras, etapas, administração de subagentes)
2. `PROGRESSO.md` (onde estamos, pendências, decisões já tomadas)
3. `DIAGNOSTICO.md` (o que já foi medido; não meça de novo sem motivo)

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
