# Hoshiestar: instruções para o agente

Antes de qualquer coisa, leia nesta ordem:
1. `PROMPT.md` (o prompt do projeto: papel, objetivo, regras, etapas, administração de subagentes)
2. `PROGRESSO.md` (onde estamos, pendências, decisões já tomadas)
3. `DIAGNOSTICO.md` (o que já foi medido; não meça de novo sem motivo)

Regras duras (resumo; o texto completo está em `PROMPT.md`):
- Nunca mexer no servidor real. Trabalhar só na cópia criada por `scripts/prepare-test-env.sh`.
- Nunca commitar, colar em conversa ou enviar para fora: jars, mundo, configs de produção, senhas e tokens.
- O profiler do spark publica o resultado em site externo. No projeto use Java Flight Recorder (`jcmd <pid> JFR.start ... filename=...`).
- Parar o servidor de teste sempre com `stop` no console (`echo stop >> console.in`), nunca com `kill`.
- Não escrever código de etapa futura. Cada etapa passa nos testes dela antes da próxima.
- Decisão difícil de desfazer: perguntar ao admin. O resto: decidir e registrar em `PROGRESSO.md`.
- Código de concorrência é sempre revisado por um agente diferente do que escreveu.
- Não inventar medição nem assinatura de API. Marcar "não verificado" quando for o caso.
- Responder em português simples e explicar termos técnicos.
