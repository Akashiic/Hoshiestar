# Roteiro do admin: testar o server.jar do Hoshiestar

Uma página. O detalhe de cada recurso está em `servidor/src/docs/<recurso>.md`.

## O que é
`Hoshiestar-1.7.10-<versão>-server.jar` é o Crucible `staging@67b53034` (o mesmo da produção)
com os recursos da Etapa 1 embutidos. Você troca o `server.jar` e pronto: **nenhum jar nosso
em `mods/` nem em `plugins/`**. Para mods e plugins ele continua se identificando como Crucible.

## Passo a passo
1. **Faça primeiro numa cópia de teste do servidor.** Backup do mundo antes.
2. Se o jar antigo `Hoshiestar-Medidor-*.jar` estiver em `mods/`, **tire**. (Se ficar, o
   servidor avisa e desabilita o medidor embutido.)
3. Troque o `server.jar` pelo do Hoshiestar e ligue com o mesmo comando de sempre.
4. No console deve aparecer `[Hoshiestar] <versão> sobre Crucible 1.7.10-staging-67b53034` e,
   depois do "Done", uma linha por recurso. Confira com `hoshie status`.
5. No primeiro boot é criado o `hoshiestar.yml` na pasta do servidor. **Tudo vem desligado,
   menos o vigia** (que só lê). Desligado = comportamento original do Crucible.
6. Ligue **um recurso por vez**, reinicie, jogue um tempo, olhe o `hoshie status`.

## Ordem sugerida para ligar

| # | Chave no `hoshiestar.yml` | O que faz | Como ligar com segurança |
|---|---|---|---|
| 0 | `vigia.ligado` (já vem `true`) | Quando um tick passa de 2 s, grava quem, onde e qual mod em `hoshiestar/vigia-*.txt` | Nada a fazer. Mande os arquivos quando houver travada |
| 1 | `betterquesting.modo` | Pula checagens de inventário de missões que não podem mudar | Primeiro `verificar` por um dia: `hoshie status` tem de mostrar **0 divergências**. Depois `ligado` |
| 2 | `mekanism.modo` | Acelera a leitura de energia da matriz de indução | Primeiro `verificar` por um dia com as matrizes em uso: **0 divergências**. Depois `ligado` |
| 3 | `arsmagica.varredura` e `arsmagica.diluicao` | Atalhos no handler do Ars Magica 2 quando ninguém está acelerado por feitiço | `true`. O status mostra quantas chamadas foram puladas |
| 4 | `chunkmap.ligado` | Cache da consulta de chunk carregado | Primeiro com `chunkmap.conferir: true` (0 divergências), depois `conferir: false` |
| 5 | `chromaticraft.ligado` | Limita a habilidade de mover área (o travamento de 30 s): volume, distância, altura, intervalo | `true`. `chromaticraft.volume` começa em 4096 blocos; ajuste se os jogadores reclamarem |
| 6 | `parada.ligado` | Parada por SIGTERM sem crash e sem salvar duas vezes | **Só depois de me dizer como o painel para o servidor e quanto espera antes de matar o processo** (ver `parada.md`, tabela de prazos) |

Medidor de carga por região: não precisa ligar nada. No pico, `hoshie medir 10` (aquecimento)
e depois `hoshie medir 600`; mande os dois arquivos `hoshiestar/medicao-*`. Detalhes em
`servidor/src/docs/ROTEIRO-MEDIDOR.md`.

## Se algo der errado
- Qualquer recurso: volte a chave para o padrão e reinicie. O servidor volta ao caminho original.
- `correcao ... NAO aplicada ... hash visto <...>`: a versão do mod é diferente da que conferimos.
  A correção não entra (é a trava de versão). Mande a linha.
- Erro com `[Hoshiestar]` no log, crash-report ou `vigia-*.txt`: mande os arquivos.
- Para voltar tudo: recoloque o `server.jar` do Crucible. O Hoshiestar não muda o formato do mundo.

## O que não foi verificado (leia antes de ligar em produção)
- Nada foi medido com carga de produção: os ganhos são estimativas do perfil. O número real
  sai de um perfil antes/depois no seu servidor.
- Mekanism: num teste com tudo ligado, o mundo reabriu com 0,008% de energia a mais numa matriz;
  não se repetiu em três tentativas e a causa provável é do próprio Mekanism (a célula não marca
  o chunk para salvar), mas não ficou provado no servidor. Por isso o passo "verificar" é obrigatório.
- ChromatiCraft: caso de altura e bot sem op não testados no jogo. Ars Magica 2: caso com alguém
  acelerado por feitiço não testado no jogo (precisa de um jogador de verdade).
- Boot em Java 8 não testado (só Java 21, que é o da produção).
