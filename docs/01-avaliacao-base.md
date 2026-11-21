# Avaliação da base: Crucible ou Thermos

Resumo para decisão. A pesquisa completa, com as fontes de cada fato, está em
`docs/pesquisa/avaliacao-base.md`.

## Recomendação

**Crucible, branch `staging`, a partir do commit `67b53034`.**

O motivo principal é simples: é exatamente o que a produção já roda
(`server.jar` → `Implementation-Version: 1.7.10-staging-67b53034`). Qualquer outra
base seria uma migração antes mesmo de começar o projeto.

## Comparação

| Critério | Crucible (`staging`) | Thermos |
|---|---|---|
| Licença | GPLv3 (arquivo `LICENSE` conferido) | GPL-3.0 (página do repositório) |
| Manutenção | ativa: último commit em 2026-11-01, 65 commits desde junho de 2026 | arquivado em 2019, último commit em 2017 |
| Roda em Java 17/21 | sim (lwjgl3ify integrado + RetroFuturaBootstrap) | não verificado; README fala em Java 8 |
| Igual à produção | sim | não |
| Convive com UniMixins | sim, em produção | não verificado |
| Timings v2, activation range | sim | não verificado |
| Checagem de thread (`AsyncCatcher`) | **não tem** | não verificado |
| Risco de regressão | mínimo | alto |

Outros candidatos: Uranium (sem atividade recente encontrada), Contigo (arquivado
em 2019), Mohist (1.7.10 depreciado), KCauldron e Ultramine (não verificados:
repositórios fora do ar ou vazios). Nenhum compete.

## O que a base já tem de threading

- Uma thread de iluminação por mundo (estilo PaperSpigot).
- Leitura de chunk do disco em thread separada (`ChunkIOExecutor`, do Forge).
- Lista de jogadores que pode ser percorrida fora da thread principal.
- Handshake do Forge concorrente por conexão (só depois de `67b53034`).
- **Não tem**: nenhuma checagem "esta thread pode mexer aqui?". Vamos criar.

## Como a base é construída

- Modelo de patches: o repositório tem ~406 arquivos `.patch` aplicados sobre o
  Minecraft+Forge decompilados na hora do build. Não há código da Mojang no repositório.
- Gradle 8.14.3, fork do ForgeGradle (`com.anatawa12.forge:ForgeGradle:1.2-1.1.0`),
  plugin próprio em `buildSrc/`.
- Precisa de **JDK 8 para compilar** (README e CI). Compilar em 17/21: não verificado.
- Comandos: `./gradlew setupCrucible` (monta o workspace), `./gradlew genPatches`
  (gera patches das edições), `./gradlew buildPackages` (gera o jar).

## O que a licença obriga

GPLv3 (leitura padrão, não é parecer jurídico):
- O Hoshiestar também é GPLv3.
- Quem receber o jar tem direito ao código-fonte. Uso interno, sem distribuir, não obriga a publicar.
- O repositório fica só com patches e código próprio. O jar do Minecraft é baixado da Mojang na hora do build ou do primeiro boot, como o Crucible já faz.

## Riscos de forkar o Crucible

1. **Build frágil**: depende de um repositório Maven pessoal hospedado como arquivo
   cru no GitHub, e de JDK 8. Mitigação: espelhar as dependências e automatizar o build
   logo na Etapa 0.
2. **Poucos mantenedores** e nenhuma versão estável desde 2022 (`master` parada na v5.4).
   A produção roda uma build de desenvolvimento.
3. **Divergência**: quanto mais mexermos no laço de tick, mais caro fica trazer
   correções do Crucible original. Mitigação: manter as Etapas 0 a 2 como mod separado
   e concentrar as mudanças do fork em poucos arquivos.
4. **Sem checagem de thread** e com threads já existentes (iluminação, I/O de chunk)
   que terão de ser integradas ao modelo de regiões.
5. **Achado próprio (D-07)**: o desligamento por SIGTERM já tem um erro de concorrência
   na base atual. Sinal de que a base não foi pensada para mais de uma thread mexendo no mundo.

## Procedimento proposto

1. Etapa 0: clonar o Crucible, compilar `67b53034` sem mudança nenhuma e comparar o
   jar com o de produção. Se não reproduzir, parar e investigar antes de qualquer patch.
2. Só depois avaliar trazer os ~21 commits novos da `staging` (há correções de
   concorrência no login que interessam).
3. O fork só nasce quando o laço de tick precisar mudar (Etapa 3). Antes disso,
   tudo sai como mod com mixins, que roda no `server.jar` atual.

**Decisão pendente do admin**: confirmar esta base (ver `PROGRESSO.md`).
