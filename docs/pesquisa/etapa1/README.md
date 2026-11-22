# Etapa 1: desenho de cada item e conferência independente

Cada item tem dois arquivos: o desenho (investigador) e a conferência (outro agente, que
refez assinaturas e números e tentou quebrar a correção). **Onde divergem, vale a
conferência** (`PROGRESSO.md`, decisão 17). Nada aqui foi medido em servidor: os ganhos
são estimativas a partir do perfil de produção e de testes isolados ("harness": um
programa pequeno que roda só a classe em questão, fora do jogo).

| Item | Arquivos | Parte do tick no perfil | Ganho esperado (estimativa) | Risco |
|---|---|---|---|---|
| 1.B ChromatiCraft, habilidade de mover área (travamento de 30 s) | `B-chromaticraft*.md` | 0,00% (não rodou durante o perfil) | nenhum na média; limita o pior caso | baixo |
| 1.C Mekanism, leitura de energia da matriz de indução | `C-mekanism*.md` | 8,27% | 5,5 a 7 pontos (faixa 4,4 a 7,6) | médio |
| 1.D BetterQuesting, checagem de inventário | `D-betterquesting*.md` | 4,62% | 0,8 a 1,4 ponto | baixo |
| 1.E Mapa de chunks do Crucible | `E-chunkmap*.md` | 3,63% | 0,9 a 2,2 pontos (ponta de baixo otimista) | baixo, com a correção da corrida |
| 1.G Parada por SIGTERM (D-24) | `E-chunkmap*.md` | não é item de desempenho | evita crash e salvamento duplo na parada | médio (concorrência) |
| 1.F Ars Magica 2, handler em toda entidade viva | `F-arsmagica*.md` | 3,57% | 1,8 a 2,4 pontos | baixo |
| 1.H Vigia de tick lento | `F-arsmagica*.md` | custa cerca de 0,05% | diagnóstico: diz quem, onde e qual mod | baixo |

Os caminhos que começam com `$S` ou `<scratchpad>` apontam para a pasta temporária da
sessão em que o estudo foi feito; os programas de teste úteis de lá são trazidos para
`core/` junto com a implementação de cada item.
