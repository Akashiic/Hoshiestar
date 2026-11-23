/*
 * Hoshiestar: servidor hibrido Forge + Bukkit para Minecraft 1.7.10, fork do Crucible.
 * Copyright (C) 2026 Projeto Hoshiestar
 *
 * Este programa e software livre: voce pode redistribui-lo e/ou modifica-lo sob os termos da
 * GNU General Public License publicada pela Free Software Foundation, versao 3 da Licenca ou
 * (a seu criterio) qualquer versao posterior. Distribuido SEM QUALQUER GARANTIA.
 * Veja <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package hoshiestar.chunkmap;

import hoshiestar.api.Limitador;
import hoshiestar.api.Log;
import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recurso "chunkmap" (item 1.E): cache de ultima consulta dentro de
 * {@code thermos.wrapper.ChunkBlockHashMap} (patch "// Hoshiestar" no fork). O trabalho fica em
 * {@link CacheDeChunks}; esta classe guarda o interruptor, os contadores do "hoshie status" e o
 * registro.
 *
 * <p>Roda na fase de coremod (registro): so JDK, config e hoshiestar.api; NADA do Minecraft aqui
 * (a {@link CacheDeChunks}, que usa a classe Chunk, so e carregada quando um mundo carrega).
 */
public final class ChunkMap {

    public static final String ID = "chunkmap";

    static final Log LOG = Log.de(ID);

    /** No maximo 5 avisos iguais por minuto. */
    static final Limitador LIMITE = new Limitador(5, 60000L);

    public static final int BITS_PADRAO = 6;

    // valores do hoshiestar.yml, fixados no registro (fase de coremod) ou, se o registro nao rodou
    // (teste isolado sem Catalogo), lidos do config atual na primeira consulta
    private static volatile boolean lido;
    private static boolean ligado;
    private static int bits = BITS_PADRAO;
    private static boolean conferir;

    // contadores para o status (todos baratos; nenhum e tocado no caminho quente sem o modo conferir)
    static final AtomicInteger CACHES_CRIADOS = new AtomicInteger();
    static final AtomicInteger CACHES_DESLIGADOS = new AtomicInteger();
    static final AtomicInteger SEM_THREAD_DO_SERVIDOR = new AtomicInteger();
    static final AtomicLong PUTS_SOBRE_OCUPADO = new AtomicLong();
    static volatile String primeiroMotivo;
    static volatile String primeiraLeituraDeFora;

    // modo conferir: escritos so pela thread do servidor (a unica que usa o cache), lidos pelo status
    static long acertos;
    static long faltas;
    static long divergencias;

    private ChunkMap() {
    }

    /** Chamado por hoshiestar.Catalogo na fase de coremod. */
    public static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
        fixar(cfg);
        Recursos.registrar(ID, "cache de ultima consulta do mapa de chunks (ChunkBlockHashMap)", ligado)
                .estado(new Recursos.Estado() {
                    @Override
                    public String linha() {
                        return linhaDeEstado();
                    }
                });
    }

    private static synchronized void fixar(HoshiestarConfig cfg) {
        if (lido) {
            return;
        }
        boolean l = cfg.chunkmap_ligado;
        ligado = l;
        bits = bitsValidos(cfg.chunkmap_bits);
        conferir = cfg.chunkmap_conferir;
        lido = true;
    }

    /** O valor de chunkmap.bits se estiver em 4..7; senao avisa e devolve o padrao (6). */
    public static int bitsValidos(int b) {
        if (b < 4 || b > 7) {
            try {
                LOG.a("chunkmap.bits = " + b + " fora de 4..7; usando " + BITS_PADRAO);
            } catch (Throwable ignorado) {
                // log nunca derruba o boot
            }
            return BITS_PADRAO;
        }
        return b;
    }

    /**
     * O interruptor. Lido UMA vez pelo patch (campo static final de ChunkBlockHashMap), quando a
     * classe carrega (primeiro mundo). Se o registro ainda nao rodou, le o config atual.
     */
    public static boolean ligado() {
        if (!lido) {
            fixar(HoshiestarConfig.atual());
        }
        return ligado;
    }

    public static int bits() {
        ligado();
        return bits;
    }

    public static boolean conferir() {
        ligado();
        return conferir;
    }

    /** Uso dos testes isolados: fixa os valores sem config (antes de ChunkBlockHashMap carregar). */
    public static synchronized void configurarParaTeste(boolean ligadoTeste, int bitsTeste, boolean conferirTeste) {
        ligado = ligadoTeste;
        bits = bitsTeste;
        conferir = conferirTeste;
        lido = true;
    }

    /** Linha do "hoshie status" e do fim do boot. */
    public static String linhaDeEstado() {
        StringBuilder sb = new StringBuilder();
        int criados = CACHES_CRIADOS.get();
        int desligados = CACHES_DESLIGADOS.get();
        sb.append("tabela ").append(1 << bits).append('x').append(1 << bits).append(" por mundo");
        sb.append("; caches criados: ").append(criados).append(" (um por mundo carregado desde o boot)");
        sb.append("; desligados por seguranca: ").append(desligados);
        String m = primeiroMotivo;
        if (m != null) {
            sb.append(" (primeiro: ").append(m).append(')');
        }
        int sem = SEM_THREAD_DO_SERVIDOR.get();
        if (sem > 0) {
            sb.append("; mapas criados antes da thread do servidor (sem cache): ").append(sem);
        }
        sb.append("; puts sobre posicao ocupada: ")
                .append(conferir ? String.valueOf(PUTS_SOBRE_OCUPADO.get()) : "(contado so com conferir)");
        String f = primeiraLeituraDeFora;
        sb.append("; leitura de outra thread: ").append(f == null ? "nenhuma vista" : "sim (primeira: " + f + ")");
        if (conferir) {
            sb.append("; conferir: acertos ").append(acertos).append(", faltas ").append(faltas)
                    .append(", divergencias ").append(divergencias);
        }
        return sb.toString();
    }

    /** Guarda o motivo e loga que um cache se desligou. Quem chama ja somou CACHES_DESLIGADOS. */
    static void desligou(String motivo, Throwable t) {
        if (primeiroMotivo == null) {
            primeiroMotivo = motivo;
        }
        try {
            if (t != null) {
                if (LIMITE.permitir("erro")) {
                    LOG.e("cache de um mundo desligado (volta ao codigo original): " + motivo, t);
                }
            } else {
                LOG.aLim(LIMITE, "desligou", "cache de um mundo desligado (volta ao codigo original): " + motivo);
            }
        } catch (Throwable ignorado) {
            // log nunca pode atrapalhar o caminho do jogo
        }
    }
}
