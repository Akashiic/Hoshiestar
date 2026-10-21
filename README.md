<div align="center">
  <h1 align="center">Hoshiestar</h1>
  <h3 align="center">Servidor híbrido NeoForge + Paper/Purpur para Minecraft 26.1.2</h3>

  <p align="center">
    <img alt="Minecraft" src="https://img.shields.io/badge/Minecraft-26.1.2-62B47A?style=flat-square">
    <a href="https://neoforged.net/">
      <img alt="NeoForge" src="https://img.shields.io/badge/NeoForge-26.1.2.122-FF8B00?style=flat-square">
    </a>
    <a href="https://papermc.io/">
      <img alt="Paper" src="https://img.shields.io/badge/Paper-26.1.2%20%2374-444444?style=flat-square">
    </a>
    <a href="https://purpurmc.org/">
      <img alt="Purpur" src="https://img.shields.io/badge/Purpur-26.1.2-9D5CFF?style=flat-square">
    </a>
    <img alt="Java" src="https://img.shields.io/badge/Java-25-007396?logo=openjdk&logoColor=white&style=flat-square">
    <a href="LICENSE.md">
      <img alt="Licença" src="https://img.shields.io/badge/licen%C3%A7a-GPLv3-blue?style=flat-square">
    </a>
  </p>

  <p align="center"><i>O nome é uma homenagem à artista Hoshie Star.</i></p>
</div>

## Sobre

O Hoshiestar roda **mods NeoForge e plugins Bukkit/Spigot/Paper/Purpur no mesmo servidor**, no Minecraft **26.1.2**.

É um fork do [Youer](https://github.com/MohistMC/Youer), do MohistMC. O Youer oficial não tem uma versão 26.1.2 com Paper: o desenvolvimento passou direto da 26.1.2 (só com CraftBukkit) para a 26.2. O Hoshiestar fecha essa lacuna trazendo a integração Paper/Purpur da 26.2 de volta para a 26.1.2, para quem precisa ficar nessa versão (por exemplo, por causa de um modpack).

> [!IMPORTANT]
> Este é um projeto **não oficial**, sem vínculo com o MohistMC, o PaperMC ou o PurpurMC.
> Problemas encontrados aqui devem ser reportados nas [issues deste repositório](https://github.com/Akashiic/Hoshiestar/issues), não nos projetos originais.

## Componentes

| Componente | Versão | Origem |
|---|---|---|
| Minecraft | 26.1.2 | Mojang |
| NeoForge | 26.1.2.122 | [neoforged/NeoForge](https://github.com/neoforged/NeoForge) `26.1.x` (`02fa4bbcb`) |
| Paper (API + servidor) | 26.1.2, build #74 | [PaperMC/Paper](https://github.com/PaperMC/Paper) `ver/26.1.2` (`e4e17fc90`) |
| Purpur | 26.1.2 | [PurpurMC/Purpur](https://github.com/PurpurMC/Purpur) `ver/26.1.2` |
| Integração híbrida | base 26.2 | [MohistMC/Youer](https://github.com/MohistMC/Youer) `26.2` |
| Adventure | 4.26.1 | a mesma do Paper 26.1.2 |

## Requisitos

- **Java 25** (recomendado: [Temurin](https://adoptium.net/) ou [Zulu](https://www.azul.com/downloads/?version=java-25-lts))
- 4 GB de RAM ou mais para o servidor (mais, se usar muitos mods)
- Para compilar: Git e cerca de 10 GB livres em disco

## Baixando

O jar pronto de cada versão fica em [Releases](https://github.com/Akashiic/Hoshiestar/releases), junto com o
SHA-256. Builds de cada commit ficam disponíveis por 14 dias em [Actions](https://github.com/Akashiic/Hoshiestar/actions)
(workflow **Build**, seção *Artifacts*).

## Compilando

```bash
git clone https://github.com/Akashiic/Hoshiestar
cd Hoshiestar
./gradlew setup      # decompila o Minecraft e aplica os patches
./gradlew hoshiestarJar   # gera o jar do servidor
```

O jar fica em `projects/youer/build/libs/hoshiestar-26.1.2-<commit>.jar`.

## Rodando o servidor

```bash
mkdir servidor && cd servidor
cp ../projects/youer/build/libs/hoshiestar-26.1.2-*.jar server.jar
java -Xmx4G -jar server.jar nogui
```

Na primeira execução, o launcher monta o ambiente em `libraries/` e pede para aceitar a EULA (`eula.txt`).
Todas as bibliotecas (inclusive o jar do servidor vanilla e o NeoForge) vão **dentro do próprio jar** e são só
extraídas: o servidor sobe sem acesso à internet e não depende de nenhum repositório externo. A única coisa que
usa rede são as bibliotecas que plugins pedem no `plugin.yml`, baixadas do Maven Central como no Paper.

Depois disso:

| Pasta / arquivo | Para que serve |
|---|---|
| `mods/` | mods NeoForge 26.1.2 |
| `plugins/` | plugins Bukkit/Spigot/Paper/Purpur |
| `config/` | configurações do Paper (`paper-global.yml`, `paper-world-defaults.yml`), do NeoForge e dos mods |
| `purpur.yml`, `spigot.yml`, `bukkit.yml` | configurações do Purpur, do Spigot e do Bukkit |
| `hoshiestar-config/` | configurações próprias da camada híbrida (`hoshiestar.yml`; uma pasta `youer-config/` antiga é renomeada sozinha) |

## Compatibilidade testada

Testes automáticos feitos num servidor real:

| Teste | Resultado |
|---|---|
| Mods: Biomes O' Plenty, TerraBlender, GlitchCore, Architectury, spark | carregam sem erros |
| Plugin: LuckPerms (Bukkit) 5.5.71 | carrega e funciona ("Running on Bukkit") |
| Materiais e biomas de mods vistos pela API Bukkit | 512 materiais e 68 biomas do Biomes O' Plenty |
| Blocos, entidades, eventos, Adventure, schedulers síncrono e assíncrono | 15 de 15 verificações OK |
| Clone limpo → `setup` → `hoshiestarJar` → boot → `stop` | OK, sem erros no log |
| **All the Mods 11** (server pack 0.12.0-beta, 280 mods) | sobe em ~6 min, 20 TPS e ~4 ms/tick gerando chunks nas três dimensões, nenhum erro depois do "Done", `stop` limpo |
| 3 jogadores (bots) por 15 min num servidor sem mods, com mortes, PvE e troca de dimensão forçadas via RCON | 20 TPS, nenhum kick, nenhum erro no servidor |

### Modpacks feitos para um NeoForge mais antigo

O Hoshiestar usa sempre o NeoForge 26.1.2 mais recente (hoje o 26.1.2.122), mesmo quando o modpack foi montado
para um build anterior. Quase sempre isso não muda nada, mas um mod que injeta código num método que o próprio
NeoForge mudou entre os builds quebra também no NeoForge puro. No ATM 11 (feito para o 26.1.2.109) isso aconteceu
com um único mod:

- **KubeJS 8.0.6** não carrega em NeoForge 26.1.2.110 ou mais novo. Use o **KubeJS 8.0.7** ou mais novo.

Se encontrar outro caso, conte nas issues.

### Como a compatibilidade com mods é verificada

Além dos testes acima, três verificações automáticas comparam o bytecode do Hoshiestar com o do NeoForge puro,
cobrindo todos os mixins dos mods de um modpack, inclusive os que estão dentro de outros jars (jar-in-jar):

- se cada ponto de injeção (`INVOKE`, `FIELD`, `NEW`, `INVOKE_ASSIGN`) ainda existe com o mesmo dono e descritor;
- se `@Shadow`, `@Accessor` e `@Invoker` ainda encontram o campo ou método;
- se o primeiro método (e o primeiro construtor) com cada nome é o mesmo do vanilla, porque é nele que um mixin
  sem descritor se aplica.

No ATM 11 sobram apenas os mixins que o Hoshiestar desativa de propósito, porque duplicam otimizações que o Paper
e o Moonrise já fazem (lista em `YouerMixinPlugin`).

## Limitações conhecidas

- **Conteúdo da 26.2 não existe aqui.** Sulfur Cube, Potent Sulfur, Speleothem e as APIs ligadas a eles ficaram de fora.
- **Algumas APIs do Paper 26.2 foram trazidas para cá**, como os eventos de pick item e de efeito de poção com fonte. Elas só acrescentam recursos e não quebram plugins feitos para a 26.1.2.
- **Paralelismo de mundos (Parallel World Ticking) fica desligado**, porque não é adequado para servidores com mods (mesma decisão do Youer 26.2).
- **Mods que mexem nas mesmas partes do jogo que o Paper** (principalmente o sistema de chunks) podem precisar de compatibilidade específica, como já existe para o Architectury e o spark.

## Como o port foi feito

1. Ponto de partida: o último estado 26.1.2 do Youer (`dc8737b4d`, só com CraftBukkit), com o NeoForge atualizado para 26.1.2.122.
2. API e CraftBukkit: a versão oficial `ver/26.1.2` do Paper e do Purpur, com as modificações do Youer reaplicadas em 3 vias.
3. Patches do Minecraft: a diferença "Youer 26.2 sem Paper → Youer 26.2 atual", sem as atualizações do NeoForge 26.2, aplicada sobre a 26.1.2. Os conflitos foram resolvidos tendo como referência os patches oficiais do Paper 26.1.2.

## Estrutura do repositório

| Caminho | Conteúdo |
|---|---|
| `patches/` | patches aplicados ao código do Minecraft (NeoForge + Paper + Purpur + Youer) |
| `src/main/java` | NeoForge, API Bukkit/Paper/Purpur, CraftBukkit e código da camada híbrida (`com.mohistmc.youer`) |
| `youerlauncher/` | launcher que instala as bibliotecas e inicia o servidor |
| `buildSrc/`, `projects/` | sistema de build (Gradle) |

Para editar o código do Minecraft: rode `./gradlew setup`, altere `projects/youer/src/`, rode `./gradlew genPatches` e faça o commit dos arquivos em `patches/`.

## Publicando uma versão

1. Crie e envie uma tag começando com `v`, por exemplo `git tag v26.1.2-1 && git push origin v26.1.2-1`
   (ou rode **Actions → Release → Run workflow** informando a tag).
2. O workflow **Release** compila do zero, testa o boot e o `stop`, e publica a release com
   `Hoshiestar-26.1.2-1.jar` e o `.sha256`. Tags com `alpha`, `beta` ou `rc` saem como pré-release.

O workflow **Build** roda em cada push e pull request para `26.1.2`, com o mesmo teste de boot.

## Recebendo correções do Youer oficial

O repositório original fica como um remoto separado, só para leitura:

```bash
git remote add mohistmc https://github.com/MohistMC/Youer
git fetch mohistmc 26.2
```

Correções da branch `26.2` que também valham para a 26.1.2 podem ser portadas com `git cherry-pick`, adaptando o que for preciso.

## Créditos

- [MohistMC/Youer](https://github.com/MohistMC/Youer): a base híbrida NeoForge + Bukkit/Paper. Todo o trabalho de integração vem de lá.
- [NeoForged](https://neoforged.net/), [PaperMC](https://papermc.io/), [PurpurMC](https://purpurmc.org/), [SpigotMC](https://www.spigotmc.org/) e [Arclight](https://github.com/IzzelAliz/Arclight).

## Licença

Assim como o Youer e o Paper, o Hoshiestar é distribuído sob a **GNU General Public License v3**. Veja [LICENSE.md](LICENSE.md) e a pasta [LICENCE](LICENCE/) para as licenças de cada projeto de origem.
