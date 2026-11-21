![Hoshiestar](docs/assets/hoshiestar.jpg)

# Hoshiestar

Servidor híbrido (Forge + Bukkit) para Minecraft 1.7.10 em que **uma base pesada
laga só a região dela**. Inspirado no Folia, mas feito para rodar mods e plugins
existentes sem reescrever nenhum.

Estado: planejamento e medição. Ainda não há jar do Hoshiestar.

## Por onde começar

| Arquivo | O que é |
|---|---|
| [`PROMPT.md`](PROMPT.md) | o prompt completo do projeto: objetivo, regras, etapas |
| [`PROGRESSO.md`](PROGRESSO.md) | onde estamos, pendências e decisões |
| [`DIAGNOSTICO.md`](DIAGNOSTICO.md) | o que foi medido e descoberto, com evidência |
| [`docs/01-avaliacao-base.md`](docs/01-avaliacao-base.md) | por que partir do Crucible |
| [`docs/02-arquitetura.md`](docs/02-arquitetura.md) | o desenho, os riscos e o que não dá para prometer |
| [`docs/03-plano-etapa-0-1.md`](docs/03-plano-etapa-0-1.md) | plano detalhado das duas primeiras etapas |
| [`docs/pesquisa/`](docs/pesquisa) | pesquisa com as fontes |

## Montar o servidor de teste

O pacote do servidor não fica no repositório (tem senhas e jars de terceiros).

```bash
HOSHIE_ACCEPT_EULA=true scripts/prepare-test-env.sh /caminho/do/pacote.tar.gz /caminho/servidor-teste
```

```bash
scripts/run-test-server.sh /caminho/servidor-teste 9G
```

Comandos vão por arquivo, um por linha:

```bash
echo "forge tps" >> /caminho/servidor-teste/console.in
```

```bash
echo "stop" >> /caminho/servidor-teste/console.in
```

Requisitos: Java 17 ou 21, cerca de 10 GB de RAM livres, `curl`. O servidor de
teste fica preso em `127.0.0.1` e nunca fala com Discord, banco, loja ou proxy
de produção.

## Regras que não mudam

1. Nunca mexer no servidor real. Só em cópia de teste.
2. Todo recurso novo tem interruptor para desligar.
3. Segurança do mundo vem antes de desempenho.
4. Nada de jar de mod, mundo, config de produção ou código da Mojang no Git.

## Licença

O Hoshiestar deriva do Crucible (GPLv3) e será distribuído sob GPLv3.
