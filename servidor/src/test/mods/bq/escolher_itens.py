#!/usr/bin/env python3
# Escolhe os itens X, Y e Z do plano de teste do recurso betterquesting (so le o livro de quests).
# Uso: python3 -I escolher_itens.py <AkashaQuests.json> [--so-contagens]
#  X = item (id) que nenhuma tarefa de retrieval pede, entre alguns blocos vanilla comuns;
#  Y = o id pedido pelo maior numero de tarefas de retrieval sem ore dictionary;
#  Z = os itens exatos de uma quest com UMA tarefa de retrieval, sem ore, sem NBT, 2 itens distintos,
#      quantidade total pequena (para dar metade e depois o resto).
import json, sys, collections

def g(o, nome, padrao=None):
    for k, v in o.items():
        if k.split(':')[0] == nome:
            return v
    return padrao

d = json.load(open(sys.argv[1]))
so_contagens = '--so-contagens' in sys.argv
qd = g(d, 'questDatabase')
pedidos = collections.Counter()
candidatos_z = []
for qk, q in qd.items():
    tarefas = list(g(q, 'tasks', {}).values())
    retr = [t for t in tarefas if g(t, 'taskID') == 'bq_standard:retrieval']
    for t in retr:
        for i in g(t, 'requiredItems', {}).values():
            if not g(i, 'OreDict', ''):
                pedidos[g(i, 'id')] += 1
    if len(tarefas) == 1 and len(retr) == 1:
        t = retr[0]
        itens = list(g(t, 'requiredItems', {}).values())
        if (len(itens) == 2 and not any(g(i, 'OreDict', '') for i in itens)
                and not any(g(i, 'tag') for i in itens)
                and len({g(i, 'id') for i in itens}) == 2
                and not g(t, 'consume') and not g(t, 'groupDetect') and not g(t, 'requireOnlyOneItem')
                and all(2 <= int(g(i, 'Count', 1)) <= 16 for i in itens)):
            qid = g(q, 'questIDHigh'), g(q, 'questIDLow')
            candidatos_z.append((qk, qid, [(g(i, 'id'), int(g(i, 'Damage', 0)), int(g(i, 'Count', 1))) for i in itens]))
vanilla = ['minecraft:dead_bush', 'minecraft:deadbush', 'minecraft:snow_layer', 'minecraft:web',
           'minecraft:fire_charge', 'minecraft:pumpkin_seeds', 'minecraft:melon_seeds', 'minecraft:flint',
           'minecraft:clay_ball', 'minecraft:stick', 'minecraft:dirt', 'minecraft:cobblestone']
x = [v for v in vanilla if pedidos.get(v, 0) == 0]
y = pedidos.most_common(1)[0] if pedidos else None
if so_contagens:
    print('ids distintos pedidos (sem ore):', len(pedidos))
    print('candidatos a X:', len(x), '; tarefas que pedem o Y:', y[1] if y else 0, '; candidatos a Z:', len(candidatos_z))
else:
    print('X =', x[0] if x else '(nenhum; escolha outro bloco vanilla com contagem 0)')
    print('Y =', y[0] if y else '?', '(pedido por', y[1] if y else 0, 'tarefas)')
    if candidatos_z:
        qk, qid, itens = candidatos_z[0]
        print('Z = quest', qk, qid, 'itens (id, dano, quantidade):', itens)
