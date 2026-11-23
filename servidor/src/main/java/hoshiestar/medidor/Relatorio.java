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
package hoshiestar.medidor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Monta e grava os dois arquivos de uma medicao. Roda numa thread propria (daemon),
 * nunca na thread de tick. So le a {@link Coleta} que recebeu; nao toca em objetos do
 * jogo. Nao grava nome de jogador, UUID nem IP: so coordenadas, dimensoes e nomes de classe.
 * Nada e enviado pela rede.
 */
final class Relatorio implements Runnable {

    static final int TOP_SECOES = 300;
    static final int TOP_CHUNKS = 300;
    static final int TOP_CLASSES = 150;
    static final int CLASSES_POR_SECAO_JSON = 10;
    static final int TOP_TXT = 20;
    static final int[] CONCENTRACAO = {1, 3, 5, 10, 20};

    private static final Locale BR = new Locale("pt", "BR");
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Coleta c;
    private final File pasta;
    private final Coleta.Autoteste a;
    /** Por que a pasta principal falhou (null = nao falhou). So a thread de gravacao escreve. */
    private String falhaPastaPrincipal;

    Relatorio(Coleta c, File pasta) {
        this.c = c;
        this.pasta = pasta;
        Coleta.Autoteste final_ = c.autotesteFinal != null ? c.autotesteFinal : c.autotesteInicial;
        if (final_ == null) {
            final_ = new Coleta.Autoteste();
        }
        this.a = final_;
    }

    /** Uma linha agregada (secao, chunk, dimensao ou classe). */
    static final class Linha {
        int dimIdx;
        int x;
        int z;
        long chave;
        String classe;
        boolean tileEntity;
        long teNs;
        long entNs;
        long teTicks;
        long entTicks;
        List<Linha> topClasses;

        long ns() {
            return teNs + entNs;
        }
    }

    private static final Comparator<Linha> POR_NS = new Comparator<Linha>() {
        @Override
        public int compare(Linha a, Linha b) {
            long x = a.ns();
            long y = b.ns();
            return x < y ? 1 : (x > y ? -1 : 0);
        }
    };

    @Override
    public void run() {
        try {
            File[] arqs = gravar();
            String veredito = " (autoteste: "
                    + (semDados() ? "sem dados uteis" : naoLigado() || a.problema ? "COM PROBLEMA, veja o .txt"
                    : coberturaBaixa() ? "laco rodou em poucos ticks, veja o .txt" : "ok")
                    + (c.valvulaDisparou ? "; parada pela valvula de sobrecarga" : "") + ").";
            // console/log: caminho completo; status (que vai tambem para jogador): so os nomes dos arquivos
            String msg = "Arquivos gravados: " + arqs[0].getPath() + " e " + arqs[1].getName() + veredito
                    + (falhaPastaPrincipal != null ? " ATENCAO: a pasta " + pasta.getPath() + " falhou ("
                    + falhaPastaPrincipal + "); os arquivos foram para a pasta reserva acima. Copie de la logo." : "");
            String curto = "arquivos gravados: " + arqs[0].getName() + " e " + arqs[1].getName()
                    + (falhaPastaPrincipal != null ? " na PASTA RESERVA (a principal falhou; caminho e motivo no console)"
                    : " na pasta hoshiestar/ do servidor") + veredito;
            if (falhaPastaPrincipal != null) {
                Medidor.LOG.a(msg);
            } else {
                Medidor.LOG.i(msg);
            }
            Medidor.registrarResultado(curto);
        } catch (Throwable t) {
            Medidor.LOG.e("Falha ao gravar os arquivos da medicao: " + motivo(t), t);
            Medidor.registrarResultado("falha ao gravar os arquivos (motivo e caminho no console)");
        }
    }

    /**
     * Pasta reserva, se a principal falhar: &lt;java.io.tmpdir&gt;/hoshiestar-&lt;usuario&gt; (null se nao houver).
     * Uma por usuario da maquina; o nome do usuario fica so com letras, numeros, '_' e '-'.
     */
    static File pastaReserva() {
        String tmp = System.getProperty("java.io.tmpdir");
        return tmp == null || tmp.isEmpty() ? null
                : new File(tmp, "hoshiestar-" + nomeSeguro(System.getProperty("user.name"))).getAbsoluteFile();
    }

    /** Troca todo caractere fora de [A-Za-z0-9_-] por '_' (no maximo 64); vazio ou null vira "desconhecido". */
    static String nomeSeguro(String s) {
        StringBuilder b = new StringBuilder();
        if (s != null) {
            for (int i = 0; i < s.length() && b.length() < 64; i++) {
                char ch = s.charAt(i);
                boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                        || ch == '_' || ch == '-';
                b.append(ok ? ch : '_');
            }
        }
        return b.length() == 0 ? "desconhecido" : b.toString();
    }

    /**
     * Prepara a pasta reserva (pasta compartilhada da maquina, como /tmp) sem seguir link simbolico: cria a pasta
     * se nao existe e recusa (IOException) se o caminho e um link simbolico, se nao e uma pasta ou, quando o
     * sistema de arquivos sabe dizer o dono, se o dono nao e o usuario deste processo. So depois disso a pasta e
     * fechada para o dono e recebe arquivos. Roda so na thread de gravacao. As mensagens nao repetem o caminho (quem
     * chama ja diz qual pasta falhou).
     */
    static void prepararReserva(File dir) throws IOException {
        Path p = dir.toPath();
        try {
            try {
                // nao segue link: se o nome ja existe (pasta, arquivo ou link), falha. Ja nasce fechada para o dono
                // (rwx------) onde o sistema de arquivos sabe permissoes POSIX: na 1.0.4 ela nascia com a permissao
                // padrao e so era fechada logo depois (janela de instantes).
                criarFechada(p);
            } catch (FileAlreadyExistsException jaExiste) {
                // ja existe: conferido abaixo
            }
            if (Files.isSymbolicLink(p)) {
                throw new IOException("e um link simbolico; recusada");
            }
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("existe e nao e uma pasta; recusada");
            }
            UserPrincipal dono;
            try {
                dono = Files.getOwner(p, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException semDono) {
                dono = null; // sistema de arquivos sem dono: nao da para conferir
            }
            if (dono != null && !dono.equals(donoDoProcesso(dir))) {
                throw new IOException("pertence a outro usuario (" + dono.getName() + "); recusada");
            }
        } catch (RuntimeException e) {
            throw new IOException("erro ao conferir a pasta: " + e, e);
        }
    }

    /** Cria a pasta ja so para o dono quando da (POSIX); senao, cria normal (o chamador fecha logo depois). */
    static void criarFechada(Path p) throws IOException {
        boolean posix;
        try {
            posix = p.getFileSystem().supportedFileAttributeViews().contains("posix");
        } catch (RuntimeException e) {
            posix = false;
        }
        if (posix) {
            Files.createDirectory(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectory(p);
        }
    }

    /**
     * Motivo legivel de uma falha de disco. Varias excecoes do java.nio trazem so o caminho na mensagem (ex.:
     * AccessDeniedException("/srv/mc/hoshiestar")); na 1.0.4 a mensagem de erro da pasta reserva as vezes saia so com
     * o caminho, sem dizer o que deu errado. Aqui o tipo da excecao vira um motivo em portugues e o caminho fica.
     */
    static String motivo(Throwable e) {
        return motivo(e, null);
    }

    /**
     * Como {@link #motivo(Throwable)}, mas sem repetir o caminho quando a excecao e sobre a propria pasta {@code base}
     * (quem chama ja diz qual pasta falhou). Caminho diferente (ex.: a pasta-mae sem permissao) continua aparecendo.
     */
    static String motivo(Throwable e, File base) {
        if (e == null) {
            return "motivo desconhecido";
        }
        String msg = e.getMessage();
        String tipo;
        if (e instanceof java.nio.file.AccessDeniedException) {
            tipo = "sem permissao";
        } else if (e instanceof java.nio.file.NoSuchFileException) {
            tipo = "caminho nao existe";
        } else if (e instanceof java.nio.file.NotDirectoryException) {
            tipo = "parte do caminho nao e pasta";
        } else if (e instanceof FileAlreadyExistsException) {
            tipo = "ja existe";
        } else if (e instanceof java.nio.file.FileSystemException) {
            String r = ((java.nio.file.FileSystemException) e).getReason();
            tipo = r != null ? r : "erro do sistema de arquivos";
        } else {
            tipo = null;
        }
        if (tipo == null) {
            return msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName();
        }
        // a mensagem de FileSystemException ja junta caminho e motivo ("/x: disco cheio"): usa so o(s) caminho(s)
        java.nio.file.FileSystemException f = (java.nio.file.FileSystemException) e;
        String det = f.getFile() != null ? f.getFile() + (f.getOtherFile() != null ? " -> " + f.getOtherFile() : "") : msg;
        if (base != null && f.getFile() != null && f.getOtherFile() == null
                && new File(f.getFile()).getAbsoluteFile().equals(base.getAbsoluteFile())) {
            det = null;
        }
        return tipo + (det != null && !det.isEmpty() ? " (" + det + ")" : "") + " [" + e.getClass().getSimpleName() + "]";
    }

    /**
     * Usuario deste processo: o dono de um arquivo temporario novo (nome aleatorio, criado de forma exclusiva) DENTRO
     * da propria pasta reserva. Na 1.0.4 a sonda ia para a pasta-mae (/tmp): se ela nao aceitasse escrita, a reserva
     * era recusada mesmo ja existindo e sendo do dono certo. Pasta de outro usuario: ou a sonda nem e criada
     * (IOException, recusada) ou e criada com o nosso dono, diferente do da pasta (recusada do mesmo jeito).
     */
    private static UserPrincipal donoDoProcesso(File pasta) throws IOException {
        File sonda;
        try {
            sonda = File.createTempFile("hoshiestar-", ".dono", pasta);
        } catch (IOException e) {
            // pasta de outro usuario fechada (0700) ou sem escrita: o Java so diz "Permission denied"
            throw new IOException("sem acesso para criar arquivo nela (pasta de outro usuario ou sem permissao de"
                    + " escrita: " + motivo(e, pasta) + "); recusada", e);
        }
        try {
            return Files.getOwner(sonda.toPath(), LinkOption.NOFOLLOW_LINKS);
        } finally {
            sonda.delete();
        }
    }

    /**
     * Monta o conteudo uma vez e grava na pasta principal; se ela falhar (sem permissao, disco cheio), tenta a
     * pasta reserva. Devolve os arquivos gravados (o caminho diz a pasta que valeu).
     */
    File[] gravar() throws IOException {
        String[] conteudo = montarConteudo();
        try {
            return gravarEm(pasta, conteudo, false);
        } catch (IOException e) {
            File reserva = pastaReserva();
            if (reserva == null || reserva.equals(pasta)) {
                throw new IOException("a pasta " + pasta + " falhou (" + motivo(e, pasta) + ") e nao ha pasta reserva", e);
            }
            falhaPastaPrincipal = motivo(e, pasta);
            try {
                return gravarEm(reserva, conteudo, true);
            } catch (IOException e2) {
                throw new IOException("a pasta " + pasta + " falhou (" + falhaPastaPrincipal + ") e a pasta reserva "
                        + reserva + " tambem (" + motivo(e2, reserva) + ")", e2);
            }
        }
    }

    /** @param soDono pasta reserva (compartilhada, como /tmp): pasta e arquivos so para o dono */
    private File[] gravarEm(File dir, String[] conteudo, boolean soDono) throws IOException {
        if (soDono) {
            prepararReserva(dir); // antes de qualquer chmod ou escrita: recusa link simbolico e pasta de outro dono
            soDono(dir);
        } else if (!dir.isDirectory()) {
            try {
                Files.createDirectories(dir.toPath()); // diferente do mkdirs da 1.0.4, diz o motivo quando falha
            } catch (IOException e) {
                throw new IOException("nao consegui criar a pasta: " + motivo(e, dir), e);
            }
        }
        String base = "medicao-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(c.inicioMs));
        // Reserva os dois nomes de forma atomica (createNewFile): duas gravacoes com o mesmo
        // segundo de inicio nunca escrevem no mesmo arquivo.
        File json = null;
        File txt = null;
        for (int n = 1; n < 1000 && json == null; n++) {
            String nome = n == 1 ? base : base + "-" + n;
            File j = new File(dir, nome + ".json");
            File t = new File(dir, nome + ".txt");
            if (j.createNewFile()) {
                if (t.createNewFile()) {
                    json = j;
                    txt = t;
                } else {
                    j.delete();
                }
            }
        }
        if (json == null) {
            throw new IOException("nao consegui reservar um nome livre para os arquivos (999 tentativas)");
        }
        boolean ok = false;
        try {
            escrever(json, conteudo[0], soDono);
            escrever(txt, conteudo[1], soDono);
            ok = true;
        } finally {
            if (!ok) {
                json.delete();
                txt.delete();
            }
        }
        return new File[] {json, txt};
    }

    /** Monta o texto dos dois arquivos: {json, txt}. */
    private String[] montarConteudo() {

        List<Linha> secoes = linhasDe(c.secoes);
        List<Linha> chunks = linhasDe(c.chunks);
        Collections.sort(secoes, POR_NS);
        Collections.sort(chunks, POR_NS);
        List<Linha> topSecoes = new ArrayList<Linha>(secoes.subList(0, Math.min(TOP_SECOES, secoes.size())));
        List<Linha> topChunks = new ArrayList<Linha>(chunks.subList(0, Math.min(TOP_CHUNKS, chunks.size())));
        preencherClassesDasSecoes(topSecoes);
        List<Linha> classes = linhasDeClasses();
        Collections.sort(classes, POR_NS);
        List<Linha> topClasses = new ArrayList<Linha>(classes.subList(0, Math.min(TOP_CLASSES, classes.size())));
        List<Linha> dims = linhasDeDims();
        Collections.sort(dims, POR_NS);
        double[] conc = concentracao(secoes);

        return new String[] {
            json(topSecoes, topChunks, topClasses, dims, conc, secoes.size(), chunks.size(), classes.size()),
            txt(topSecoes, topChunks, topClasses, dims, conc, secoes.size(), chunks.size())
        };
    }

    /** Escreve num temporario de nome unico e troca pelo arquivo reservado; o temporario nunca fica para tras. */
    private static void escrever(File f, String conteudo, boolean soDono) throws IOException {
        File tmp = File.createTempFile(f.getName() + ".", ".tmp", f.getParentFile());
        try {
            if (soDono) {
                soDono(tmp); // antes de escrever: o arquivo final e este temporario, com as mesmas permissoes
            }
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
            try {
                w.write(conteudo);
            } finally {
                w.close();
            }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    /** Tira leitura, escrita e execucao de grupo e outros (o resultado e ignorado: pode nao ser o dono). */
    private static void soDono(File f) {
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
        if (f.isDirectory()) {
            f.setExecutable(false, false);
            f.setExecutable(true, true);
        }
    }

    // ------------------------------------------------------------------ agregacao

    private static List<Linha> linhasDe(TabelaLong t) {
        List<Linha> r = new ArrayList<Linha>(t.tamanho());
        for (int i = 0; i < t.capacidade(); i++) {
            if (!t.ocupado(i)) {
                continue;
            }
            Linha l = new Linha();
            long k = t.chave(i);
            l.chave = k;
            l.dimIdx = Coleta.dimDaChave(k);
            l.x = Coleta.xDaChave(k);
            l.z = Coleta.zDaChave(k);
            l.teNs = t.teNs(i);
            l.entNs = t.entNs(i);
            l.teTicks = t.teTicks(i);
            l.entTicks = t.entTicks(i);
            r.add(l);
        }
        return r;
    }

    private void preencherClassesDasSecoes(List<Linha> topSecoes) {
        Map<Long, Linha> porChave = new HashMap<Long, Linha>();
        for (Linha s : topSecoes) {
            s.topClasses = new ArrayList<Linha>();
            porChave.put(s.chave, s);
        }
        TabelaLong t = c.secaoClasse;
        for (int i = 0; i < t.capacidade(); i++) {
            if (!t.ocupado(i)) {
                continue;
            }
            long k = t.chave(i);
            long chaveSecao = Coleta.chaveChunk(Coleta.dimDaChave(k), Coleta.sxDaChaveSC(k), Coleta.szDaChaveSC(k));
            Linha s = porChave.get(chaveSecao);
            if (s == null) {
                continue;
            }
            Coleta.Classe cl = c.classePorIdx[Coleta.classeDaChaveSC(k)];
            Linha l = new Linha();
            l.classe = cl == null ? "?" : cl.nome;
            l.tileEntity = cl != null && cl.tileEntity;
            l.teNs = t.teNs(i);
            l.entNs = t.entNs(i);
            l.teTicks = t.teTicks(i);
            l.entTicks = t.entTicks(i);
            s.topClasses.add(l);
        }
        for (Linha s : topSecoes) {
            Collections.sort(s.topClasses, POR_NS);
            if (s.topClasses.size() > CLASSES_POR_SECAO_JSON) {
                s.topClasses = new ArrayList<Linha>(s.topClasses.subList(0, CLASSES_POR_SECAO_JSON));
            }
        }
    }

    private List<Linha> linhasDeClasses() {
        List<Linha> r = new ArrayList<Linha>();
        for (int i = 0; i <= c.nClasses; i++) {
            Coleta.Classe cl = c.classePorIdx[i];
            if (cl == null || cl.ns() == 0L && cl.teTicks + cl.entTicks == 0L) {
                continue;
            }
            Linha l = new Linha();
            l.classe = cl.nome;
            l.tileEntity = cl.tileEntity;
            l.teNs = cl.teNs;
            l.entNs = cl.entNs;
            l.teTicks = cl.teTicks;
            l.entTicks = cl.entTicks;
            r.add(l);
        }
        return r;
    }

    private List<Linha> linhasDeDims() {
        List<Linha> r = new ArrayList<Linha>();
        for (int i = 0; i < c.nDims; i++) {
            Linha l = new Linha();
            l.dimIdx = i;
            l.teNs = c.dimTeNs[i];
            l.entNs = c.dimEntNs[i];
            l.teTicks = c.dimTeTicks[i];
            l.entTicks = c.dimEntTicks[i];
            if (l.teTicks + l.entTicks > 0) {
                r.add(l);
            }
        }
        return r;
    }

    private double[] concentracao(List<Linha> secoesOrdenadas) {
        double[] r = new double[CONCENTRACAO.length];
        long total = c.totalNs();
        long acum = 0L;
        int j = 0;
        for (int i = 0; i < CONCENTRACAO.length; i++) {
            while (j < CONCENTRACAO[i] && j < secoesOrdenadas.size()) {
                acum += secoesOrdenadas.get(j).ns();
                j++;
            }
            r[i] = total > 0 ? 100.0 * acum / total : 0.0;
        }
        return r;
    }

    // ------------------------------------------------------------------ utilidades

    /** A chamada de update de tile entity que os ganchos envolvem e a do Akashic-World-Firewall. */
    private boolean viaFirewall() {
        return "firewall".equals(c.chamadaTe);
    }

    private boolean semDados() {
        return "sem_dados_nenhum_mundo_tickou".equals(a.te) || "sem_dados_nenhum_mundo_tickou".equals(a.ent);
    }

    /** Fracao dos ticks do servidor em que algum mundo rodou o laco (1 = o tempo todo); -1 se desconhecido. */
    private double cobertura() {
        if (c.tickSeq <= 0) {
            return -1.0;
        }
        return Math.min(1.0, (double) c.ticksComLaco / c.tickSeq);
    }

    /** O laco rodou em menos da metade dos ticks: os totais por tick ficam muito abaixo do real. */
    private boolean coberturaBaixa() {
        double f = cobertura();
        return f >= 0.0 && f < 0.5;
    }

    private boolean naoLigado() {
        return "NAO_LIGADO".equals(a.te) || "NAO_LIGADO".equals(a.ent);
    }

    /** Custo estimado do medidor, em ns no total da medicao (quadros x custo calibrado de um quadro vazio). */
    private long custoEstimadoNs() {
        return (long) ((c.teTicksTotal + c.entTicksTotal) * c.calibQuadroNs);
    }

    /** Tempo da linha menos o artefato estimado (ticks x parte do custo do medidor que cai dentro de cada quadro). */
    private long semArtefato(Linha l) {
        return Math.max(0L, l.ns() - (long) ((l.teTicks + l.entTicks) * c.calibArtefatoNs));
    }

    private void autotesteJson(StringBuilder b, Coleta.Autoteste t) {
        if (t == null) {
            b.append("null");
            return;
        }
        b.append("{\"tile_entities\":").append(esc(t.te))
                .append(",\"entidades\":").append(esc(t.ent))
                .append(",\"no_tick\":").append(t.noTick)
                .append(",\"te_carregados\":").append(t.teCarregados)
                .append(",\"entidades_carregadas\":").append(t.entCarregadas)
                .append(",\"lacos_de_mundo\":").append(t.lacos)
                .append(",\"te_ticks_medidos\":").append(t.teTicks)
                .append(",\"ent_ticks_medidos\":").append(t.entTicks)
                .append(",\"te_ticks_via_vanilla\":").append(viaFirewall() ? 0L : t.teTicks)
                .append(",\"te_ticks_via_firewall\":").append(viaFirewall() ? t.teTicks : 0L)
                .append(",\"ticks_com_laco\":").append(t.ticksComLaco)
                .append(",\"ticks_iniciados\":").append(t.tickSeq)
                .append(",\"dimensoes_com_problema\":[");
        for (int i = 0; i < t.dimsSemMedicao.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(esc(t.dimsSemMedicao.get(i)));
        }
        b.append("]}");
    }

    private String dimTexto(int idx) {
        int id = c.dimId[idx];
        return id == Coleta.DIM_DESCONHECIDA ? "?" : Integer.toString(id);
    }

    private String dimJson(int idx) {
        int id = c.dimId[idx];
        return id == Coleta.DIM_DESCONHECIDA ? "null" : Integer.toString(id);
    }

    private double pct(long ns) {
        long t = c.totalNs();
        return t > 0 ? 100.0 * ns / t : 0.0;
    }

    private double msPorTick(long ns) {
        return c.ticksServidor > 0 ? ns / 1e6 / c.ticksServidor : 0.0;
    }

    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "null";
        }
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String br(double v, int casas) {
        return String.format(BR, "%." + casas + "f", v);
    }

    private static String dataIso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(new Date(ms));
    }

    private static String dataTexto(long ms) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss (XXX)", Locale.ROOT).format(new Date(ms));
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
            }
        }
        b.append('"');
        return b.toString();
    }

    private void camposNs(StringBuilder b, Linha l) {
        b.append("\"te_ns\":").append(l.teNs)
                .append(",\"ent_ns\":").append(l.entNs)
                .append(",\"te_ticks\":").append(l.teTicks)
                .append(",\"ent_ticks\":").append(l.entTicks)
                .append(",\"total_ns\":").append(l.ns())
                .append(",\"pct_do_medido\":").append(num(pct(l.ns())))
                .append(",\"ms_por_tick\":").append(num(msPorTick(l.ns())))
                .append(",\"total_ns_sem_artefato_est\":").append(semArtefato(l));
    }

    private static void outrosJson(StringBuilder b, String nome, TabelaLong t) {
        b.append(esc(nome)).append(":{\"chaves_distintas\":").append(t.tamanho())
                .append(",\"limite\":").append(t.limite())
                .append(",\"limite_atingido\":").append(t.transbordou())
                .append(",\"registros_no_outros\":").append(t.chavesRecusadas)
                .append(",\"te_ns\":").append(t.outrosTeNs)
                .append(",\"ent_ns\":").append(t.outrosEntNs)
                .append(",\"te_ticks\":").append(t.outrosTeTicks)
                .append(",\"ent_ticks\":").append(t.outrosEntTicks).append('}');
    }

    // ------------------------------------------------------------------ JSON

    private String json(List<Linha> secoes, List<Linha> chunks, List<Linha> classes, List<Linha> dims, double[] conc,
            int nSecoes, int nChunks, int nClasses) {
        StringBuilder b = new StringBuilder(256 * 1024);
        double segMedidos = (c.fimNano - c.inicioNano) / 1e9;
        b.append("{\n\"meta\":{");
        b.append("\"mod\":\"Hoshiestar-Medidor\",\"versao\":").append(esc(Medidor.VERSAO));
        b.append(",\"servidor\":").append(esc("Hoshiestar " + hoshiestar.Hoshiestar.VERSAO + " (medidor dentro do server.jar)"));
        b.append(",\"inicio\":").append(esc(dataIso(c.inicioMs)));
        b.append(",\"fim\":").append(esc(dataIso(c.fimMs)));
        b.append(",\"segundos_pedidos\":").append(c.segundosPedidos);
        b.append(",\"segundos\":").append(num(segMedidos));
        b.append(",\"motivo_da_parada\":").append(esc(c.motivoParada));
        b.append(",\"ticks_servidor\":").append(c.ticksServidor);
        b.append(",\"te_ns_total\":").append(c.teNsTotal);
        b.append(",\"ent_ns_total\":").append(c.entNsTotal);
        b.append(",\"medido_ns_total\":").append(c.totalNs());
        b.append(",\"te_ticks_total\":").append(c.teTicksTotal);
        b.append(",\"ent_ticks_total\":").append(c.entTicksTotal);
        b.append(",\"ms_por_tick_te\":").append(num(msPorTick(c.teNsTotal)));
        b.append(",\"ms_por_tick_ent\":").append(num(msPorTick(c.entNsTotal)));
        b.append(",\"ms_por_tick_medido\":").append(num(msPorTick(c.totalNs())));
        b.append(",\"tick_servidor_ns_total\":").append(c.nsTickServidor);
        b.append(",\"ms_por_tick_servidor\":").append(num(msPorTick(c.nsTickServidor)));
        b.append(",\"pct_do_tick_servidor_medido\":")
                .append(num(c.nsTickServidor > 0 ? 100.0 * c.totalNs() / c.nsTickServidor : 0.0));
        b.append(",\"tps_medio\":").append(num(segMedidos > 0 ? c.ticksServidor / segMedidos : 0.0));
        b.append(",\"ganchos\":\"fork\"");
        b.append(",\"gancho_te_no_carregamento\":").append(esc(c.chamadaTe));
        b.append(",\"firewall_instalado\":").append(viaFirewall());
        b.append(",\"te_ticks_via_vanilla\":").append(viaFirewall() ? 0L : c.teTicksTotal);
        b.append(",\"te_ticks_via_firewall\":").append(viaFirewall() ? c.teTicksTotal : 0L);
        b.append(",\"gancho_entidades\":true");
        b.append(",\"gancho_jogadores_rede\":true");
        b.append(",\"ticks_jogador_rede\":").append(c.ticksJogadorRede);
        b.append(",\"lacos_de_mundo\":").append(c.lacosMundo);
        b.append(",\"te_nas_listas_dos_lacos\":").append(c.teNasListas);
        b.append(",\"ent_nas_listas_dos_lacos\":").append(c.entNasListas);
        b.append(",\"autoteste\":");
        autotesteJson(b, a);
        b.append(",\"autoteste_no_tick_").append(Medidor.TICKS_AUTOTESTE).append("\":");
        autotesteJson(b, c.autotesteInicial);
        b.append(",\"ticks_com_laco\":").append(c.ticksComLaco);
        b.append(",\"ticks_iniciados\":").append(c.tickSeq);
        b.append(",\"cobertura_laco_pct\":").append(cobertura() < 0 ? "null" : num(100.0 * cobertura()));
        b.append(",\"dados_validos\":").append(!semDados() && !naoLigado() && !coberturaBaixa());
        b.append(",\"calibracao\":{\"nanotime_ns\":").append(num(c.calibNanoNs))
                .append(",\"limite_nanotime_ns\":").append(num(Medidor.LIMITE_NANOTIME_NS))
                .append(",\"forcado\":").append(c.forcado)
                .append(",\"custo_quadro_total_ns\":").append(num(c.calibQuadroNs))
                .append(",\"artefato_dentro_do_quadro_ns\":").append(num(c.calibArtefatoNs)).append('}');
        b.append(",\"custo_estimado_do_medidor_ns_por_tick_medido\":").append(num(c.calibQuadroNs));
        b.append(",\"custo_estimado_do_medidor_ms_por_tick\":").append(num(msPorTick(custoEstimadoNs())));
        b.append(",\"valvula\":{\"disparou\":").append(c.valvulaDisparou)
                .append(",\"motivo\":").append(c.motivoValvula == null ? "null" : esc(c.motivoValvula))
                .append(",\"tick_antes_de_ligar_mediana_ms\":").append(c.refMedianaNs > 0 ? num(c.refMedianaNs / 1e6) : "null")
                .append(",\"tick_antes_de_ligar_amostras\":").append(c.refAmostras)
                .append(",\"janelas\":").append(c.janelas)
                .append(",\"ticks_por_janela\":").append(Medidor.JANELA)
                .append(",\"maior_mediana_janela_ms\":").append(num(c.maiorMedianaJanelaNs / 1e6))
                .append(",\"maior_custo_estimado_ms_por_tick\":").append(num(c.maiorCustoEstNsPorTick / 1e6))
                .append(",\"limite_pct\":").append(Math.round((Medidor.VALVULA_FATOR - 1) * 100))
                .append(",\"limite_ms\":").append(Medidor.VALVULA_NS / 1000000L)
                .append(",\"janelas_seguidas\":").append(Medidor.VALVULA_JANELAS)
                .append(",\"limite_custo_estimado_ms_por_tick\":").append(num(c.limiteCustoNs / 1e6))
                .append(",\"limite_custo_regra\":").append(esc(Medidor.VALVULA_CUSTO_PCT_REF + "% do tick antes de ligar, no"
                        + " minimo " + Medidor.VALVULA_CUSTO_NS / 1000000L + " ms e no maximo "
                        + Medidor.VALVULA_CUSTO_MAX_NS / 1000000L + " ms"))
                .append(",\"margem_custo_estimado\":").append(num(c.margemCusto)).append('}');
        b.append(",\"quadros_descartados\":").append(c.quadrosDescartados);
        b.append(",\"quadros_pulados\":").append(c.quadrosPulados);
        b.append(",\"secoes_distintas\":").append(nSecoes);
        b.append(",\"chunks_distintos\":").append(nChunks);
        b.append(",\"classes_distintas\":").append(nClasses);
        b.append(",\"concentracao_secoes_pct\":{");
        for (int i = 0; i < CONCENTRACAO.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append('"').append(CONCENTRACAO[i]).append("\":").append(num(conc[i]));
        }
        b.append('}');
        b.append(",\"definicoes\":{\"secao\":\"16x16 chunks (256x256 blocos); secao_x = chunk_x >> 4\","
                + "\"chunk_x\":\"bloco_x >> 4\",\"tempo\":\"tempo de parede (System.nanoTime) proprio de cada tick,"
                + " sem o tempo de ticks aninhados\",\"pct_do_medido\":\"porcentagem do total medido (TE + entidades)\","
                + "\"ticks\":\"chamadas do laco medidas (inclui ticks bloqueados pelo Akashic-World-Firewall e ticks"
                + " inativos do Spigot, de custo quase zero)\",\"tick_pela_rede\":\"classe com sufixo '(tick pela rede)':"
                + " EntityPlayerMP.onUpdateEntity, uma vez por pacote de movimento; a classe sem sufixo e o tick do"
                + " jogador pelo laco do mundo e tambem o updateEntity que o caminho da rede chama, depois do"
                + " onUpdateEntity, para jogador montado ou dormindo (sem contar tempo duas vezes)\",\"autoteste\":\"refeito no fim da medicao; autoteste_no_tick_100 e a foto"
                + " do inicio\",\"total_ns_sem_artefato_est\":\"total_ns menos ticks x calibracao.artefato_dentro_do_quadro_ns"
                + " (parte do custo do proprio medidor que cai dentro de cada tick medido); estimativa\","
                + "\"calibracao.custo_quadro_total_ns\":\"custo total de um tick medido vazio (abrir, ler o relogio, fechar,"
                + " somar), melhor caso, sem a parte do gancho\",\"calibracao.artefato_dentro_do_quadro_ns\":\"a parte"
                + " desse custo que cai DENTRO do tempo medido de cada tick\"}");
        b.append("},\n\"limites\":{");
        outrosJson(b, "chunks", c.chunks);
        b.append(',');
        outrosJson(b, "secoes", c.secoes);
        b.append(',');
        outrosJson(b, "secao_classe", c.secaoClasse);
        b.append(",\"classes\":{\"distintas\":").append(c.nClasses).append(",\"limite\":").append(Coleta.MAX_CLASSES)
                .append(",\"registros_no_outros\":").append(c.classesRecusadas).append('}');
        b.append(",\"posicoes_fora_da_faixa\":").append(c.posicoesForaDoLimite);
        b.append("},\n\"dimensoes\":[");
        for (int i = 0; i < dims.size(); i++) {
            Linha l = dims.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("{\"dim\":").append(dimJson(l.dimIdx)).append(',');
            camposNs(b, l);
            b.append('}');
        }
        b.append("],\n\"secoes\":[");
        for (int i = 0; i < secoes.size(); i++) {
            Linha l = secoes.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("{\"dim\":").append(dimJson(l.dimIdx))
                    .append(",\"secao_x\":").append(l.x).append(",\"secao_z\":").append(l.z)
                    .append(",\"bloco_x_min\":").append(l.x * 256L).append(",\"bloco_x_max\":").append(l.x * 256L + 255)
                    .append(",\"bloco_z_min\":").append(l.z * 256L).append(",\"bloco_z_max\":").append(l.z * 256L + 255)
                    .append(',');
            camposNs(b, l);
            b.append(",\"top_classes\":[");
            if (l.topClasses != null) {
                for (int j = 0; j < l.topClasses.size(); j++) {
                    Linha k = l.topClasses.get(j);
                    if (j > 0) {
                        b.append(',');
                    }
                    b.append("{\"classe\":").append(esc(k.classe)).append(",\"tipo\":")
                            .append(k.tileEntity ? "\"te\"" : (k.teTicks > 0 && k.entTicks == 0 ? "\"te\"" : "\"entidade\""))
                            .append(",\"ns\":").append(k.ns()).append(",\"ticks\":").append(k.teTicks + k.entTicks)
                            .append(",\"pct_da_secao\":").append(num(l.ns() > 0 ? 100.0 * k.ns() / l.ns() : 0.0))
                            .append('}');
                }
            }
            b.append("]}");
        }
        b.append("],\n\"chunks\":[");
        for (int i = 0; i < chunks.size(); i++) {
            Linha l = chunks.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("{\"dim\":").append(dimJson(l.dimIdx))
                    .append(",\"chunk_x\":").append(l.x).append(",\"chunk_z\":").append(l.z)
                    .append(",\"bloco_x_min\":").append(l.x * 16L).append(",\"bloco_z_min\":").append(l.z * 16L)
                    .append(",\"secao_x\":").append(l.x >> 4).append(",\"secao_z\":").append(l.z >> 4).append(',');
            camposNs(b, l);
            b.append('}');
        }
        b.append("],\n\"classes\":[");
        for (int i = 0; i < classes.size(); i++) {
            Linha l = classes.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("{\"classe\":").append(esc(l.classe)).append(",\"tipo\":")
                    .append(l.tileEntity ? "\"te\"" : (l.entTicks == 0 && l.teTicks > 0 ? "\"te\"" : "\"entidade\""))
                    .append(',');
            camposNs(b, l);
            b.append('}');
        }
        b.append("]\n}\n");
        return b.toString();
    }

    // ------------------------------------------------------------------ TXT

    private String ms(long ns) {
        return br(ns / 1e6, 1);
    }

    private String txt(List<Linha> secoes, List<Linha> chunks, List<Linha> classes, List<Linha> dims, double[] conc,
            int nSecoes, int nChunks) {
        StringBuilder b = new StringBuilder(32 * 1024);
        String nl = System.getProperty("line.separator", "\n");
        double segMedidos = (c.fimNano - c.inicioNano) / 1e9;
        long total = c.totalNs();

        b.append("HOSHIESTAR-MEDIDOR ").append(Medidor.VERSAO).append(" (no server.jar Hoshiestar ").append(hoshiestar.Hoshiestar.VERSAO)
                .append(") - onde vai o tempo de tick no mapa").append(nl);
        b.append("Inicio: ").append(dataTexto(c.inicioMs)).append("   Fim: ").append(dataTexto(c.fimMs)).append(nl);
        b.append("Duracao: ").append(br(segMedidos, 1)).append(" s (pedido: ").append(c.segundosPedidos)
                .append(" s; parada: ").append(c.motivoParada).append(")").append(nl);
        b.append("Ticks do servidor contados: ").append(c.ticksServidor).append("  (TPS medio ")
                .append(br(segMedidos > 0 ? c.ticksServidor / segMedidos : 0.0, 2)).append(")").append(nl);
        b.append("O arquivo nao tem nome de jogador, UUID nem IP. Nada foi enviado pela rede.").append(nl).append(nl);

        if (semDados()) {
            b.append("!!! ATENCAO: ESTA MEDICAO NAO TEM DADOS UTEIS !!!").append(nl);
            b.append("    Nenhum mundo rodou o laco de entidades/tile entities durante a medicao (o servidor sem").append(nl);
            b.append("    jogadores e sem chunk forcado fica ocioso depois de 1 minuto). Os totais e a concentracao").append(nl);
            b.append("    abaixo NAO representam o servidor. Repita com jogadores conectados.").append(nl).append(nl);
        }
        if (!semDados() && coberturaBaixa()) {
            b.append("!!! ATENCAO: OS MUNDOS SO RODARAM O LACO EM ").append(br(100.0 * cobertura(), 1))
                    .append("% DOS TICKS DESTA MEDICAO !!!").append(nl);
            b.append("    (servidor sem jogadores e sem chunk forcado fica ocioso.) Os totais e os 'ms por tick' abaixo")
                    .append(nl);
            b.append("    ficam muito abaixo do real e a concentracao vale so para os poucos ticks medidos.").append(nl);
            b.append("    Repita com jogadores conectados.").append(nl).append(nl);
        }
        if (naoLigado()) {
            b.append("!!! ATENCAO: PARTE DA MEDICAO NAO ESTAVA LIGADA (veja AUTOTESTE). Os zeros dessa parte NAO sao dado.")
                    .append(nl).append(nl);
        }
        if (!a.dimsSemMedicao.isEmpty()) {
            b.append("!!! ATENCAO: ").append(a.dimsSemMedicao.size())
                    .append(" dimensao(oes) podem nao estar medidas (veja AUTOTESTE).").append(nl).append(nl);
        }
        if (c.valvulaDisparou) {
            b.append("!!! A MEDICAO FOI PARADA ANTES DO TEMPO PELA VALVULA DE SOBRECARGA: ").append(c.motivoValvula)
                    .append('.').append(nl);
            b.append("    Os numeros abaixo valem para o trecho medido.").append(nl).append(nl);
        }

        b.append("AUTOTESTE (os ganchos estavam mesmo ligados? conferido de novo no fim da medicao)").append(nl);
        b.append("  Tile entities: ").append(textoAutoteste(a.te)).append("  [ganchos do fork em volta da chamada de update; ")
                .append(viaFirewall() ? "chamada do Akashic-World-Firewall (WorldMutationGuard.runTileEntityUpdate)"
                        : "chamada vanilla (" + c.chamadaTe + ")").append("]").append(nl);
        b.append("  Entidades: ").append(textoAutoteste(a.ent)).append("  [tick de jogador pela rede: ")
                .append(c.ticksJogadorRede > 0 ? "medido (" + c.ticksJogadorRede + " ticks)"
                        : "gancho aplicado, nenhum jogador visto nesta medicao")
                .append("]").append(nl);
        if ("NAO_LIGADO".equals(a.te)) {
            b.append("  ATENCAO: os mundos que rodaram o laco tinham tile entities na lista (").append(c.teNasListas)
                    .append(" no total dos lacos) e nenhum tick foi medido. Os zeros de tile entity abaixo NAO sao dado.")
                    .append(nl);
        }
        if ("NAO_LIGADO".equals(a.ent)) {
            b.append("  ATENCAO: os mundos que rodaram o laco tinham entidades na lista (").append(c.entNasListas)
                    .append(" no total dos lacos) e nenhum tick foi medido. Os zeros de entidade abaixo NAO sao dado.")
                    .append(nl);
        }
        for (String d : a.dimsSemMedicao) {
            b.append("  ATENCAO: ").append(d).append('.').append(nl);
        }
        Coleta.Autoteste ini = c.autotesteInicial;
        if (ini != null && (!ini.te.equals(a.te) || !ini.ent.equals(a.ent))) {
            b.append("  (no tick ").append(ini.noTick).append(" o autoteste dizia: tile entities ").append(ini.te)
                    .append(", entidades ").append(ini.ent).append("; o resultado acima e o da medicao inteira.)")
                    .append(nl);
        }
        b.append("  Lacos de mundo que rodaram: ").append(c.lacosMundo);
        if (cobertura() >= 0) {
            b.append(", em ").append(c.ticksComLaco).append(" de ").append(c.tickSeq).append(" ticks do servidor (")
                    .append(br(100.0 * cobertura(), 1)).append("%)");
        }
        b.append(nl);
        b.append(nl);

        b.append("TOTAIS (tempo proprio de cada tick; tempo aninhado nao e contado duas vezes)").append(nl);
        b.append("  Tile entities: ").append(ms(c.teNsTotal)).append(" ms em ").append(c.teTicksTotal).append(" ticks = ")
                .append(br(msPorTick(c.teNsTotal), 2)).append(" ms por tick do servidor").append(nl);
        b.append("  Entidades:     ").append(ms(c.entNsTotal)).append(" ms em ").append(c.entTicksTotal).append(" ticks = ")
                .append(br(msPorTick(c.entNsTotal), 2)).append(" ms por tick do servidor").append(nl);
        b.append("  Total medido:  ").append(ms(total)).append(" ms = ").append(br(msPorTick(total), 2))
                .append(" ms por tick do servidor").append(nl);
        b.append("  Tick inteiro do servidor (do inicio ao fim do tick, aproximado): ")
                .append(br(msPorTick(c.nsTickServidor), 2)).append(" ms por tick; a parte medida e ")
                .append(br(c.nsTickServidor > 0 ? 100.0 * total / c.nsTickServidor : 0.0, 1)).append("% dele.")
                .append(nl);
        b.append("  Custo do proprio medidor enquanto liga (calibrado no inicio, melhor caso: ")
                .append(br(c.calibQuadroNs, 0)).append(" ns por tick medido): ~")
                .append(br(msPorTick(custoEstimadoNs()), 2))
                .append(" ms por tick do servidor, ja dentro do 'tick inteiro'. Desligado, o custo e zero.").append(nl);
        b.append("  Calibracao: System.nanoTime custa ").append(br(c.calibNanoNs, 0)).append(" ns por chamada (limite ")
                .append(br(Medidor.LIMITE_NANOTIME_NS, 0)).append(" ns").append(c.forcado ? "; ACIMA DO LIMITE, medicao forcada" : "")
                .append("); de cada tick medido, ~").append(br(c.calibArtefatoNs, 0))
                .append(" ns sao do proprio medidor (artefato).").append(nl);
        b.append("  Valvula de sobrecarga: ");
        if (c.refMedianaNs > 0) {
            b.append("tick antes de ligar (mediana de ").append(c.refAmostras).append(" ticks) ")
                    .append(br(c.refMedianaNs / 1e6, 1)).append(" ms; ");
        } else {
            b.append("sem referencia de tick antes de ligar; ");
        }
        b.append("maior mediana numa janela de ").append(Medidor.JANELA).append(" ticks medidos ")
                .append(br(c.maiorMedianaJanelaNs / 1e6, 1)).append(" ms; maior custo estimado ")
                .append(br(c.maiorCustoEstNsPorTick / 1e6, 2)).append(" ms por tick; ")
                .append(c.valvulaDisparou ? "DISPAROU." : "nao disparou.").append(nl);
        b.append("    (para sozinha se a mediana do tick passar a de antes de ligar em mais de ")
                .append(Math.round((Medidor.VALVULA_FATOR - 1) * 100)).append("% e de ").append(Medidor.VALVULA_NS / 1000000L)
                .append(" ms em ").append(Medidor.VALVULA_JANELAS).append(" janelas seguidas, ou se o custo estimado,")
                .append(nl);
        b.append("    vezes ").append(br(c.margemCusto, 1)).append(" de margem (medidor.margem_custo), passar de ")
                .append(br(c.limiteCustoNs / 1e6, 1))
                .append(" ms por tick (").append(Medidor.VALVULA_CUSTO_PCT_REF).append("% do tick antes de ligar, entre ")
                .append(Medidor.VALVULA_CUSTO_NS / 1000000L).append(" e ").append(Medidor.VALVULA_CUSTO_MAX_NS / 1000000L)
                .append(" ms): a calibracao e o melhor caso e nao inclui a parte do gancho.)").append(nl).append(nl);

        b.append("CONCENTRACAO").append(nl);
        b.append("  As 1, 3, 5, 10 e 20 secoes mais pesadas concentram ");
        for (int i = 0; i < conc.length; i++) {
            b.append(br(conc[i], 1)).append('%');
            b.append(i < conc.length - 2 ? ", " : (i == conc.length - 2 ? " e " : ""));
        }
        b.append(" do tempo medido.").append(nl);
        if (semDados()) {
            b.append("  ATENCAO: medicao sem dados uteis (nenhum mundo rodou o laco); estes numeros NAO valem.").append(nl);
        } else if (coberturaBaixa()) {
            b.append("  ATENCAO: o laco so rodou em ").append(br(100.0 * cobertura(), 1))
                    .append("% dos ticks; estes numeros valem so para esses poucos ticks.").append(nl);
        }
        b.append("  (").append(nSecoes).append(" secoes e ").append(nChunks)
                .append(" chunks tiveram algum tick medido. Secao = 16 x 16 chunks = 256 x 256 blocos.)").append(nl)
                .append(nl);

        b.append("TOP ").append(TOP_TXT).append(" SECOES").append(nl);
        b.append(String.format(Locale.ROOT, "  %3s %6s %-22s %-22s %8s %9s %9s %9s%n", "#", "dim", "blocos X", "blocos Z",
                "%medido", "ms/tick", "TE ms", "Ent ms"));
        for (int i = 0; i < Math.min(TOP_TXT, secoes.size()); i++) {
            Linha l = secoes.get(i);
            b.append(String.format(Locale.ROOT, "  %3d %6s %-22s %-22s %8s %9s %9s %9s%n", i + 1, dimTexto(l.dimIdx),
                    "[" + (l.x * 256L) + " .. " + (l.x * 256L + 255) + "]",
                    "[" + (l.z * 256L) + " .. " + (l.z * 256L + 255) + "]",
                    br(pct(l.ns()), 1) + "%", br(msPorTick(l.ns()), 2), ms(l.teNs), ms(l.entNs)));
            if (l.topClasses != null) {
                for (int j = 0; j < Math.min(3, l.topClasses.size()); j++) {
                    Linha k = l.topClasses.get(j);
                    b.append("        - ").append(k.classe).append(" (").append(k.tileEntity ? "TE" : "entidade")
                            .append("): ").append(br(l.ns() > 0 ? 100.0 * k.ns() / l.ns() : 0.0, 1))
                            .append("% da secao, ").append(k.teTicks + k.entTicks).append(" ticks").append(nl);
                }
            }
        }
        b.append(nl);

        b.append("TOP ").append(TOP_TXT).append(" CHUNKS").append(nl);
        b.append(String.format(Locale.ROOT, "  %3s %6s %10s %10s %-24s %8s %9s %9s %9s%n", "#", "dim", "chunk X", "chunk Z",
                "bloco inicial (X, Z)", "%medido", "ms/tick", "TE ms", "Ent ms"));
        for (int i = 0; i < Math.min(TOP_TXT, chunks.size()); i++) {
            Linha l = chunks.get(i);
            b.append(String.format(Locale.ROOT, "  %3d %6s %10d %10d %-24s %8s %9s %9s %9s%n", i + 1, dimTexto(l.dimIdx),
                    l.x, l.z, "(" + (l.x * 16L) + ", " + (l.z * 16L) + ")", br(pct(l.ns()), 1) + "%",
                    br(msPorTick(l.ns()), 2), ms(l.teNs), ms(l.entNs)));
        }
        b.append(nl);

        b.append("TOP ").append(TOP_TXT).append(" CLASSES").append(nl);
        b.append(String.format(Locale.ROOT, "  %3s %-9s %8s %9s %11s %12s  %s%n", "#", "tipo", "%medido", "ms/tick",
                "s/artef.", "ticks", "classe"));
        for (int i = 0; i < Math.min(TOP_TXT, classes.size()); i++) {
            Linha l = classes.get(i);
            b.append(String.format(Locale.ROOT, "  %3d %-9s %8s %9s %11s %12d  %s%n", i + 1,
                    l.tileEntity ? "TE" : (l.entTicks == 0 && l.teTicks > 0 ? "TE" : "entidade"), br(pct(l.ns()), 1) + "%",
                    br(msPorTick(l.ns()), 2), br(msPorTick(semArtefato(l)), 2), l.teTicks + l.entTicks, l.classe));
        }
        b.append("  's/artef.' = ms/tick menos ticks x ").append(br(c.calibArtefatoNs, 0))
                .append(" ns (a parte do custo do medidor que cai dentro de cada tick medido; estimativa). Classes com")
                .append(nl);
        b.append("  muitos ticks baratos (funis parados, TEs bloqueadas pelo firewall, entidades inativas) carregam ate")
                .append(nl);
        b.append("  ticks x esse valor de artefato na coluna ms/tick.").append(nl);
        b.append(nl);

        b.append("POR DIMENSAO").append(nl);
        b.append(String.format(Locale.ROOT, "  %6s %8s %9s %10s %10s %12s %12s%n", "dim", "%medido", "ms/tick", "TE ms",
                "Ent ms", "TE ticks", "Ent ticks"));
        for (Linha l : dims) {
            b.append(String.format(Locale.ROOT, "  %6s %8s %9s %10s %10s %12d %12d%n", dimTexto(l.dimIdx),
                    br(pct(l.ns()), 1) + "%", br(msPorTick(l.ns()), 2), ms(l.teNs), ms(l.entNs), l.teTicks, l.entTicks));
        }
        b.append(nl);

        b.append("LIMITES DE MEMORIA").append(nl);
        limiteTxt(b, nl, "chunks", c.chunks);
        limiteTxt(b, nl, "secoes", c.secoes);
        limiteTxt(b, nl, "classes por secao", c.secaoClasse);
        b.append("  classes: ").append(c.nClasses).append(" de no maximo ").append(Coleta.MAX_CLASSES);
        if (c.classesRecusadas > 0) {
            b.append(" - LIMITE ATINGIDO: ").append(c.classesRecusadas).append(" ticks foram para o balde 'outros'");
        }
        b.append(nl);
        if (c.posicoesForaDoLimite > 0) {
            b.append("  ").append(c.posicoesForaDoLimite).append(" ticks tinham coordenada absurda e foram para 'outros'.")
                    .append(nl);
        }
        b.append("  quadros descartados (tick que terminou em excecao): ").append(c.quadrosDescartados)
                .append("; aninhamento alem do limite: ").append(c.quadrosPulados).append(nl);
        b.append(nl);
        b.append("Como ler: 'ms/tick' e quanto aquele lugar custa, em media, por tick do servidor. Um tick saudavel")
                .append(nl);
        b.append("tem no maximo 50 ms no total. '%medido' e a fatia do tempo medido (tile entities + entidades).")
                .append(nl);
        b.append("'ticks' conta as chamadas do laco medidas: inclui ticks bloqueados pelo Akashic-World-Firewall e")
                .append(nl);
        b.append("ticks inativos do Spigot (custo quase zero); o tempo e que diz onde esta o peso.").append(nl);
        b.append("Jogador: 'EntityPlayerMP (tick pela rede)' e o tick pela rede (onUpdateEntity), que roda uma vez por")
                .append(nl);
        b.append("pacote de movimento (perto de 1 por tick, as vezes mais). A classe EntityPlayerMP sem sufixo e o tick")
                .append(nl);
        b.append("pelo laco do mundo e tambem o updateEntity que o caminho da rede chama, logo depois, para jogador")
                .append(nl);
        b.append("montado ou dormindo. Nenhum tempo e contado duas vezes.").append(nl);
        b.append("Os dados completos (300 secoes, 300 chunks, 150 classes) estao no arquivo .json de mesmo nome.")
                .append(nl);
        return b.toString();
    }

    private static String textoAutoteste(String s) {
        if ("ok".equals(s)) {
            return "ligado e medindo";
        }
        if ("NAO_LIGADO".equals(s)) {
            return "NAO LIGADO";
        }
        if ("sem_dados_nenhum_mundo_tickou".equals(s)) {
            return "nao deu para testar: nenhum mundo rodou o laco de entidades (servidor sem jogadores fica ocioso)";
        }
        if (s != null && s.startsWith("sem_")) {
            return "sem nada carregado para medir";
        }
        return String.valueOf(s);
    }

    private static void limiteTxt(StringBuilder b, String nl, String nome, TabelaLong t) {
        b.append("  ").append(nome).append(": ").append(t.tamanho()).append(" de no maximo ").append(t.limite());
        long noOutros = t.outrosTeTicks + t.outrosEntTicks;
        if (noOutros > 0) {
            b.append(" - LIMITE ATINGIDO: ").append(noOutros).append(" ticks (")
                    .append(br((t.outrosTeNs + t.outrosEntNs) / 1e6, 1))
                    .append(" ms) foram para o balde 'outros' e nao aparecem nas listas");
        }
        b.append(nl);
    }
}
