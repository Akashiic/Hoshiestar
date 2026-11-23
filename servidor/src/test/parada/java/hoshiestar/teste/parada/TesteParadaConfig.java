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
package hoshiestar.teste.parada;

import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.parada.Parada;
import hoshiestar.teste.Verifica;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** As chaves parada.* no hoshiestar.yml e o registro do recurso. Uso: {@code TesteParadaConfig <pasta vazia>}. */
public final class TesteParadaConfig {

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();
        Verifica v = new Verifica("TesteParadaConfig");

        File f = new File(dir, "novo.yml");
        HoshiestarConfig c = HoshiestarConfig.carregar(f);
        String t = ler(f);
        v.sim(t.contains("parada:") && t.contains("espera_laco_segundos: 60") && t.contains("espera_salvar_segundos: 600")
                && t.contains("folga_saida_segundos: 30"), "arquivo novo: secao parada com os tres tempos nos padroes");
        v.igual(false, c.parada_ligado, "padrao: desligado");
        v.igual(60, c.parada_esperaLacoSegundos, "padrao: T1 = 60 s");
        v.igual(600, c.parada_esperaSalvarSegundos, "padrao: T2 = 600 s");
        v.igual(30, c.parada_folgaSaidaSegundos, "padrao: folga = 30 s");
        v.sim(c.avisos().isEmpty(), "arquivo novo sem avisos (" + c.avisos() + ")");

        File f2 = new File(dir, "ligado.yml");
        escrever(f2, t.replace("espera_laco_segundos: 60", "espera_laco_segundos: 5")
                .replace("espera_salvar_segundos: 600", "espera_salvar_segundos: 90")
                .replace("folga_saida_segundos: 30", "folga_saida_segundos: 7")
                .replaceAll("(?s)(parada:.*?ligado: )false", "$1true"));
        long antes = f2.lastModified();
        c = HoshiestarConfig.carregar(f2);
        v.igual(true, c.parada_ligado, "admin liga");
        v.igual(5, c.parada_esperaLacoSegundos, "admin muda T1");
        v.igual(90, c.parada_esperaSalvarSegundos, "admin muda T2");
        v.igual(7, c.parada_folgaSaidaSegundos, "admin muda a folga");
        v.sim(c.avisos().isEmpty() && f2.lastModified() == antes, "arquivo completo e valido nao e regravado (" + c.avisos() + ")");

        File f3 = new File(dir, "antigo.yml");
        escrever(f3, "autoteste:\n  ligado: false\n");
        c = HoshiestarConfig.carregar(f3);
        v.sim(ler(f3).contains("parada:") && !c.parada_ligado, "arquivo de versao anterior: parada entra sozinha, desligada");

        v.igual(60, Parada.segundosValidos("x", 0, 1, 3600, 60), "T1 0 -> padrao");
        v.igual(60, Parada.segundosValidos("x", 3601, 1, 3600, 60), "T1 3601 -> padrao");
        v.igual(1, Parada.segundosValidos("x", 1, 1, 3600, 60), "T1 1 aceito");
        v.igual(0, Parada.segundosValidos("x", 0, 0, 600, 30), "folga 0 aceita");
        v.igual(30, Parada.segundosValidos("x", -1, 0, 600, 30), "folga -1 -> padrao");

        // registro (o que o Catalogo chama no boot)
        Recursos.limparParaTeste();
        Parada.registrar(HoshiestarConfig.carregar(f2), null);
        Recursos.Recurso r = null;
        for (Recursos.Recurso x : Recursos.todos()) {
            if (x.id.equals("parada")) {
                r = x;
            }
        }
        v.sim(r != null && r.ligado, "registro: recurso 'parada' aparece ligado");
        String linha = r == null ? "" : r.linhaDeEstado();
        v.sim(linha.contains("estado: ocioso") && linha.contains("laco 5 s") && linha.contains("salvar 90 s") && linha.contains("folga 7 s"),
                "linha de estado com os tempos do arquivo: " + linha);
        v.sim(Parada.ligado(), "Parada.ligado() segue o arquivo");
        v.fim();
    }

    static String ler(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void escrever(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } finally {
            o.close();
        }
    }
}
