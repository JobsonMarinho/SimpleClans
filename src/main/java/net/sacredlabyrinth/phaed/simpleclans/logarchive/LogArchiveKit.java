package net.sacredlabyrinth.phaed.simpleclans.logarchive;

import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/*
 * LogArchiveKit — compactação de logs em arquivo, o mesmo desenho em todo plugin da rede.
 * Um arquivo, só JDK (nada de Bukkit/Bungee), APIs do Java 8. Fonte da verdade:
 * PengAPI/src/main/java/net/peng1104/logarchive/LogArchiveKit.java — copie trocando o package.
 *
 * Ideia central: o arquivo de um dia que já fechou vira um MEMBRO zstd acrescentado no fim do
 * pacote do período (`2026-10.log.zst` por mês, ou `2026-10-14.log.zst` por dia). Vários frames
 * zstd concatenados são um .zst válido (zstdcat, zstdgrep e o ZstdInputStream leem tudo em
 * sequência). Medido em log real: zstd 43× menor, gzip 7,7×. Pacotes .gz antigos continuam
 * sendo lidos e respeitam a retenção. Consequências:
 *   - nada é reaberto nem descompactado: o jogador antigo que volta escreve num arquivo novo do
 *     dia, e esse arquivo vira mais um membro do pacote quando fechar;
 *   - escrita atrasada num dia já compactado só cria outro arquivo do mesmo dia, que vira outro
 *     membro (nada se perde);
 *   - não há estado: o próprio nome do arquivo diz o que ainda falta fazer.
 *
 * Segurança contra queda do servidor no meio: antes de mexer num pacote grava-se
 * `<pacote>.journal` com o tamanho original e as origens; depois de gravar, sincronizar no disco
 * e conferir o tamanho descompactado de cada membro, o diário ganha a linha `OK` e só então as
 * origens são apagadas. Na passada seguinte: diário sem `OK` = trunca o pacote de volta (as
 * origens ainda existem e são refeitas); diário com `OK` = termina de apagar as origens.
 */

/**
 * Contêiner do kit — todas as classes do kit são estáticas públicas aninhadas aqui.
 *
 * @since 1.19.0
 */
public final class LogArchiveKit {

    private LogArchiveKit() {}

    // ─── LogBundle ───────────────────────────────────────────────────────────────

    /** Como juntar os dias fechados: um .zst por dia, ou um .zst por mês. */
    public enum LogBundle { DAY, MONTH }

    // ─── LogArchiveRule ──────────────────────────────────────────────────────────

    /**
     * Uma família de logs.
     *
     * <br>Campo id           nome curto usado no relatório e na config ({@code retention-days.<id>}).
     * <br>Campo dir          pasta onde os arquivos estão (ou, com {@link #perEntity}, a pasta das
     *                     subpastas por entidade).
     * <br>Campo bundle       DAY ou MONTH.
     * <br>Campo retentionDays dias até apagar o que é antigo; 0 = nunca apagar.
     * <br>Campo perEntity    true = {@code dir/<entidade>/<arquivos>} (um nível); false = direto.
     * <br>Campo prefix       só arquivos cujo nome começa por este prefixo seguido da data;
     *                     null = qualquer prefixo não reivindicado por outra regra da mesma pasta.
     * <br>Campo rotateUndated renomeia o arquivo sem data para {@code <nome>-<hoje>.log} na passada
     *                     diária quando ele não foi escrito hoje ou passou de {@link #rotateMaxBytes}.
     *                     SÓ ligue se o writer abre e fecha o arquivo a cada escrita.
     * <br>Campo undatedNames com {@link #rotateUndated}, restringe aos nomes exatos; null = todos.
     * <br>Campo rotateMaxBytes bytes acima dos quais um arquivo sem data de hoje também é rotacionado.
     * <br>Campo pruneEmptyDirs com {@link #perEntity}, apaga a subpasta da entidade que ficou vazia.
     */
    public static final class LogArchiveRule {
        public final String id;
        public final File dir;
        public final LogBundle bundle;
        public final int retentionDays;
        public final boolean perEntity;
        public final String prefix;           // nullable
        public final boolean rotateUndated;
        public final Set<String> undatedNames; // nullable
        public final long rotateMaxBytes;
        public final boolean pruneEmptyDirs;

        public LogArchiveRule(String id, File dir, LogBundle bundle, int retentionDays,
                              boolean perEntity, String prefix, boolean rotateUndated,
                              Set<String> undatedNames, long rotateMaxBytes, boolean pruneEmptyDirs) {
            this.id = id;
            this.dir = dir;
            this.bundle = bundle;
            this.retentionDays = retentionDays;
            this.perEntity = perEntity;
            this.prefix = prefix;
            this.rotateUndated = rotateUndated;
            this.undatedNames = undatedNames;
            this.rotateMaxBytes = rotateMaxBytes;
            this.pruneEmptyDirs = pruneEmptyDirs;
        }

        /** Construtor curto: só campos obrigatórios, demais com padrões. */
        public LogArchiveRule(String id, File dir, LogBundle bundle, int retentionDays) {
            this(id, dir, bundle, retentionDays, false, null, false, null, 16L * 1024 * 1024, true);
        }

        /** Builder para construção com campos opcionais. */
        public static Builder builder(String id, File dir, LogBundle bundle, int retentionDays) {
            return new Builder(id, dir, bundle, retentionDays);
        }

        public static final class Builder {
            private final String id;
            private final File dir;
            private final LogBundle bundle;
            private final int retentionDays;
            private boolean perEntity = false;
            private String prefix = null;
            private boolean rotateUndated = false;
            private Set<String> undatedNames = null;
            private long rotateMaxBytes = 16L * 1024 * 1024;
            private boolean pruneEmptyDirs = true;

            private Builder(String id, File dir, LogBundle bundle, int retentionDays) {
                this.id = id; this.dir = dir; this.bundle = bundle; this.retentionDays = retentionDays;
            }
            public Builder perEntity(boolean v) { perEntity = v; return this; }
            public Builder prefix(String v) { prefix = v; return this; }
            public Builder rotateUndated(boolean v) { rotateUndated = v; return this; }
            public Builder undatedNames(Set<String> v) { undatedNames = v; return this; }
            public Builder rotateMaxBytes(long v) { rotateMaxBytes = v; return this; }
            public Builder pruneEmptyDirs(boolean v) { pruneEmptyDirs = v; return this; }
            public LogArchiveRule build() {
                return new LogArchiveRule(id, dir, bundle, retentionDays, perEntity, prefix,
                        rotateUndated, undatedNames, rotateMaxBytes, pruneEmptyDirs);
            }
        }
    }

    // ─── LogArchiveSettings ──────────────────────────────────────────────────────

    /** {@code config → log-archive}. Chaves em inglês, como o resto das configs da rede. */
    public static final class LogArchiveSettings {

        public static final LogArchiveSettings DEFAULT = new LogArchiveSettings(
                true, LocalTime.of(0, 20), 5L, 10L, 15L, 40, Collections.<String, Integer>emptyMap());

        public final boolean enabled;
        public final LocalTime dailyAt;
        public final long startupDelayMinutes;
        public final long maxMinutesPerRun;
        public final long quietMinutes;
        public final int maxMbPerSecond;
        public final Map<String, Integer> retentionOverrides;

        public LogArchiveSettings(boolean enabled, LocalTime dailyAt, long startupDelayMinutes,
                                   long maxMinutesPerRun, long quietMinutes,
                                   int maxMbPerSecond, Map<String, Integer> retentionOverrides) {
            this.enabled = enabled;
            this.dailyAt = dailyAt;
            this.startupDelayMinutes = startupDelayMinutes;
            this.maxMinutesPerRun = maxMinutesPerRun;
            this.quietMinutes = quietMinutes;
            this.maxMbPerSecond = maxMbPerSecond;
            this.retentionOverrides = retentionOverrides;
        }

        public int retentionFor(LogArchiveRule rule) {
            Integer v = retentionOverrides.get(rule.id);
            return v != null ? v : rule.retentionDays;
        }

        /**
         * Lê a seção pelo acessor do plugin ({@code section.get(key)}); chave ausente = padrão.
         * {@code retention-days} é um mapa {@code <id da regra>: <dias>} (seção do YAML ou Map).
         */
        public static LogArchiveSettings read(Function<String, Object> get) {
            LogArchiveSettings d = DEFAULT;

            boolean enabled = readBool(get.apply("enabled"), d.enabled);
            LocalTime time = d.dailyAt;
            Object rawTime = get.apply("daily-at");
            if (rawTime instanceof String) {
                String s = ((String) rawTime).trim();
                try { time = LocalTime.parse(s.length() == 4 ? "0" + s : s); } catch (Exception ignored) {}
            }
            long startupDelay = readLong(get.apply("startup-delay-minutes"), d.startupDelayMinutes, 0);
            long maxMinutes   = readLong(get.apply("max-minutes-per-run"), d.maxMinutesPerRun, 1);
            long quietMinutes = readLong(get.apply("quiet-minutes"), d.quietMinutes, 1);
            int maxMbs        = (int) readLong(get.apply("max-mb-per-second"), d.maxMbPerSecond, 0);

            Map<String, Integer> overrides = new HashMap<>();
            Object r = get.apply("retention-days");
            if (r instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) r).entrySet()) {
                    Integer v = toInt(e.getValue());
                    if (v != null) overrides.put(e.getKey().toString(), Math.max(0, v));
                }
            } else if (r != null) {
                // Seção do Bukkit (getValues(false)) ou do Bungee sem depender da API.
                try {
                    Map<String, Object> values;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) r.getClass()
                                .getMethod("getValues", boolean.class).invoke(r, false);
                        values = m;
                    } catch (Exception ex) {
                        @SuppressWarnings("unchecked")
                        java.util.Collection<String> keys = (java.util.Collection<String>)
                                r.getClass().getMethod("getKeys").invoke(r);
                        java.lang.reflect.Method getter = r.getClass().getMethod("get", String.class);
                        values = new HashMap<>();
                        for (String k : keys) values.put(k, getter.invoke(r, k));
                    }
                    for (Map.Entry<String, Object> e : values.entrySet()) {
                        Integer v = toInt(e.getValue());
                        if (v != null) overrides.put(e.getKey(), Math.max(0, v));
                    }
                } catch (Exception ignored) {}
            }

            return new LogArchiveSettings(enabled, time, startupDelay, maxMinutes,
                    quietMinutes, maxMbs, overrides);
        }

        private static boolean readBool(Object v, boolean def) {
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) return ((String) v).trim().equalsIgnoreCase("true");
            return def;
        }

        private static long readLong(Object v, long def, long min) {
            long val;
            if (v instanceof Number) val = ((Number) v).longValue();
            else if (v instanceof String) {
                try { val = Long.parseLong(((String) v).trim()); }
                catch (NumberFormatException e) { val = def; }
            } else val = def;
            return Math.max(val, min);
        }

        private static Integer toInt(Object v) {
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) {
                try { return Integer.parseInt(((String) v).trim()); }
                catch (NumberFormatException e) { return null; }
            }
            return null;
        }

        /** Copia com alguns campos alterados (estilo Kotlin data class copy). */
        public LogArchiveSettings copy(Boolean enabled, LocalTime dailyAt, Long startupDelayMinutes,
                                        Long maxMinutesPerRun, Long quietMinutes,
                                        Integer maxMbPerSecond,
                                        Map<String, Integer> retentionOverrides) {
            return new LogArchiveSettings(
                    enabled != null ? enabled : this.enabled,
                    dailyAt != null ? dailyAt : this.dailyAt,
                    startupDelayMinutes != null ? startupDelayMinutes : this.startupDelayMinutes,
                    maxMinutesPerRun != null ? maxMinutesPerRun : this.maxMinutesPerRun,
                    quietMinutes != null ? quietMinutes : this.quietMinutes,
                    maxMbPerSecond != null ? maxMbPerSecond : this.maxMbPerSecond,
                    retentionOverrides != null ? retentionOverrides : this.retentionOverrides);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LogArchiveSettings)) return false;
            LogArchiveSettings s = (LogArchiveSettings) o;
            return enabled == s.enabled && startupDelayMinutes == s.startupDelayMinutes
                    && maxMinutesPerRun == s.maxMinutesPerRun && quietMinutes == s.quietMinutes
                    && maxMbPerSecond == s.maxMbPerSecond
                    && dailyAt.equals(s.dailyAt) && retentionOverrides.equals(s.retentionOverrides);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(enabled, dailyAt, startupDelayMinutes, maxMinutesPerRun,
                    quietMinutes, maxMbPerSecond, retentionOverrides);
        }
    }

    // ─── LogArchiveReport ────────────────────────────────────────────────────────

    /** O que uma passada fez. */
    public static final class LogArchiveReport {
        public final int compressedFiles;
        public final long bytesIn;
        public final long bytesOut;
        public final int deletedFiles;
        public final long deletedBytes;
        public final int rotatedFiles;
        public final int recovered;
        public final int errors;
        public final boolean more;

        public LogArchiveReport(int compressedFiles, long bytesIn, long bytesOut,
                                 int deletedFiles, long deletedBytes, int rotatedFiles,
                                 int recovered, int errors, boolean more) {
            this.compressedFiles = compressedFiles;
            this.bytesIn = bytesIn;
            this.bytesOut = bytesOut;
            this.deletedFiles = deletedFiles;
            this.deletedBytes = deletedBytes;
            this.rotatedFiles = rotatedFiles;
            this.recovered = recovered;
            this.errors = errors;
            this.more = more;
        }

        public boolean isIdle() {
            return compressedFiles == 0 && deletedFiles == 0 && rotatedFiles == 0
                    && recovered == 0 && errors == 0;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder("Logs: ");
            if (compressedFiles > 0)
                sb.append(compressedFiles).append(" arquivo(s) compactado(s) (")
                  .append(human(bytesIn)).append(" → ").append(human(bytesOut)).append(")");
            if (rotatedFiles > 0)
                sep(sb).append(rotatedFiles).append(" arquivo(s) sem data separado(s) por dia");
            if (deletedFiles > 0)
                sep(sb).append(deletedFiles).append(" removido(s) pela retenção (").append(human(deletedBytes)).append(")");
            if (recovered > 0)
                sep(sb).append(recovered).append(" pacote(s) recuperado(s) de uma passada interrompida");
            if (errors > 0)
                sep(sb).append(errors).append(" erro(s)");
            if (more)
                sep(sb).append("continua na próxima passada");
            return sb.toString();
        }

        private static StringBuilder sep(StringBuilder sb) {
            if (sb.length() > 6) sb.append("; ");
            return sb;
        }

        public LogArchiveReport withMore(boolean more) {
            return new LogArchiveReport(compressedFiles, bytesIn, bytesOut, deletedFiles,
                    deletedBytes, rotatedFiles, recovered, errors, more);
        }

        public static String human(long bytes) {
            if (bytes < 1024) return bytes + " B";
            String[] units = {"KB", "MB", "GB", "TB"};
            double v = bytes / 1024.0;
            int i = 0;
            while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
            return String.format(Locale.ROOT, "%.1f %s", v, units[i]);
        }
    }

    // ─── LogArchiveFiles ─────────────────────────────────────────────────────────

    /**
     * Nomes e leitura dos arquivos de log, compactados ou não. Use nos comandos/menus de histórico:
     * {@link #forEachLine} lê {@code .log}, {@code .zst} e {@code .gz} (com vários membros) do mesmo jeito.
     */
    public static final class LogArchiveFiles {

        private LogArchiveFiles() {}

        /** Sufixos tratados como log de texto. */
        public static final Set<String> LOG_SUFFIXES;
        static {
            Set<String> s = new HashSet<>(Arrays.asList(".log", ".txt", ".jsonl", ".json", ".csv"));
            LOG_SUFFIXES = Collections.unmodifiableSet(s);
        }

        private static final String DAY_ISO   = "\\d{4}-\\d{2}-\\d{2}";
        private static final String DAY_BR    = "\\d{2}-\\d{2}-\\d{4}";
        private static final String MONTH_ISO = "\\d{4}-\\d{2}";
        private static final String MONTH_BR  = "\\d{2}-\\d{4}";
        private static final String SUFFIX    = "\\.(?:log|txt|jsonl|json|csv)";
        /** Pedaço do mesmo dia cortado por tamanho: {@code 2026-10-14-1.jsonl}. */
        private static final String PART      = "(?:-(\\d{1,4}))?";

        /** {@code <prefixo><data do dia>[-N]<sufixo>} — arquivo de um dia, ainda em texto. */
        private static final java.util.regex.Pattern DATED =
                java.util.regex.Pattern.compile("^(.*?)(" + DAY_ISO + "|" + DAY_BR + ")" + PART + "(" + SUFFIX + ")$");

        /** {@code <prefixo><dia ou mês>[-N]<sufixo>.zst|.gz} — pacote já compactado. */
        private static final java.util.regex.Pattern ARCHIVE =
                java.util.regex.Pattern.compile("^(.*?)(" + DAY_ISO + "|" + DAY_BR + "|" + MONTH_ISO + "|" + MONTH_BR + ")" + PART + "(" + SUFFIX + ")\\.(?:zst|gz)$");

        /** Extensão dos pacotes novos. */
        public static final String BUNDLE_EXT = ".zst";

        private static final DateTimeFormatter ISO_DAY   = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        private static final DateTimeFormatter BR_DAY    = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        private static final DateTimeFormatter ISO_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
        private static final DateTimeFormatter BR_MONTH  = DateTimeFormatter.ofPattern("MM-yyyy");

        /** Arquivo diário em texto: prefixo, dia, sufixo, e se a data veio no estilo brasileiro. */
        public static final class Dated {
            public final String prefix;
            public final LocalDate date;
            public final String suffix;
            public final boolean brazilian;
            public final int part;
            public Dated(String prefix, LocalDate date, String suffix, boolean brazilian, int part) {
                this.prefix = prefix; this.date = date; this.suffix = suffix;
                this.brazilian = brazilian; this.part = part;
            }
        }

        /** Pacote compactado: prefixo, último dia que ele cobre, primeiro dia. */
        public static final class Archive {
            public final String prefix;
            public final LocalDate lastDay;
            public final LocalDate firstDay;
            public final String suffix;
            public Archive(String prefix, LocalDate lastDay, LocalDate firstDay, String suffix) {
                this.prefix = prefix; this.lastDay = lastDay;
                this.firstDay = firstDay; this.suffix = suffix;
            }
        }

        public static Dated parseDated(String name) {
            java.util.regex.Matcher m = DATED.matcher(name);
            if (!m.matches()) return null;
            String raw = m.group(2);
            boolean br = raw.length() == 10 && raw.charAt(2) == '-';
            LocalDate date;
            try { date = LocalDate.parse(raw, br ? BR_DAY : ISO_DAY); }
            catch (Exception e) { return null; }
            int part = 0;
            String pg = m.group(3);
            if (pg != null && !pg.isEmpty()) { try { part = Integer.parseInt(pg); } catch (Exception ignored) {} }
            return new Dated(m.group(1), date, m.group(4), br, part);
        }

        public static Archive parseArchive(String name) {
            java.util.regex.Matcher m = ARCHIVE.matcher(name);
            if (!m.matches()) return null;
            String raw = m.group(2);
            LocalDate first, last;
            try {
                if (raw.length() == 10) {
                    boolean br = raw.charAt(2) == '-';
                    first = last = LocalDate.parse(raw, br ? BR_DAY : ISO_DAY);
                } else {
                    boolean br = raw.charAt(2) == '-';
                    YearMonth ym = YearMonth.parse(raw, br ? BR_MONTH : ISO_MONTH);
                    first = ym.atDay(1);
                    last  = ym.atEndOfMonth();
                }
            } catch (Exception e) { return null; }
            return new Archive(m.group(1), last, first, m.group(4));
        }

        /** Nome do pacote onde o arquivo diário entra. Mantém o estilo da data da origem. */
        public static String bundleName(Dated d, LogBundle bundle) {
            String period;
            if (bundle == LogBundle.DAY) {
                period = d.date.format(d.brazilian ? BR_DAY : ISO_DAY);
            } else {
                period = YearMonth.from(d.date).format(d.brazilian ? BR_MONTH : ISO_MONTH);
            }
            return d.prefix + period + d.suffix + BUNDLE_EXT;
        }

        public static boolean isLogSuffix(String name) {
            for (String s : LOG_SUFFIXES) {
                if (name.endsWith(s)) return true;
            }
            return false;
        }

        public static boolean isCompressed(String name) {
            return name.endsWith(".zst") || name.endsWith(".gz");
        }

        /**
         * Arquivos de log de uma pasta (texto e .zst/.gz), do mais antigo para o mais novo pelo
         * período no nome — o que o histórico precisa ler. {@code prefix} filtra uma família.
         */
        public static List<File> logFilesIn(File dir, String prefix) {
            File[] raw = dir.listFiles(f -> f.isFile() && (prefix == null || f.getName().startsWith(prefix)));
            if (raw == null) return Collections.emptyList();
            List<Object[]> tagged = new ArrayList<>();
            for (File f : raw) {
                LocalDate key = null;
                Dated d = parseDated(f.getName());
                if (d != null) key = d.date;
                else {
                    Archive a = parseArchive(f.getName());
                    if (a != null) key = a.firstDay;
                    else if (isLogSuffix(f.getName())) key = LocalDate.MAX;
                }
                if (key != null) tagged.add(new Object[]{f, key});
            }
            tagged.sort((a, b) -> {
                int cmp = ((LocalDate) a[1]).compareTo((LocalDate) b[1]);
                if (cmp != 0) return cmp;
                boolean aGz = isCompressed(((File) a[0]).getName());
                boolean bGz = isCompressed(((File) b[0]).getName());
                return Boolean.compare(aGz, bGz);
            });
            List<File> result = new ArrayList<>(tagged.size());
            for (Object[] t : tagged) result.add((File) t[0]);
            return result;
        }

        /** Overload sem prefixo. */
        public static List<File> logFilesIn(File dir) { return logFilesIn(dir, null); }

        /** Abre o arquivo para leitura de texto, descompactando se for .zst ou .gz. */
        public static BufferedReader openReader(File file) throws IOException {
            Long committed = null;
            File journal = new File(file.getParentFile(), file.getName() + ".journal");
            if (journal.isFile()) {
                try {
                    List<String> lines = Files.readAllLines(journal.toPath(), StandardCharsets.UTF_8);
                    if (!lines.isEmpty()) {
                        String first = lines.get(0).trim();
                        if (!first.isEmpty()) {
                            try { committed = Long.parseLong(first); } catch (NumberFormatException ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
            InputStream fileIn = new FileInputStream(file);
            if (committed != null) fileIn = new LimitedInputStream(fileIn, committed);
            InputStream raw = new BufferedInputStream(fileIn, 64 * 1024);
            InputStream input;
            if (file.getName().endsWith(".zst")) {
                input = new TolerantInputStream(new ZstdInputStream(raw));
            } else if (file.getName().endsWith(".gz")) {
                input = new TolerantInputStream(new GZIPInputStream(raw, 64 * 1024));
            } else {
                input = raw;
            }
            return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 64 * 1024);
        }

        /**
         * Lê linha a linha um .log, .zst ou .gz. Um pacote que está recebendo um membro neste
         * instante termina "cortado": a leitura para no último membro inteiro, sem exceção.
         */
        public static void forEachLine(File file, Consumer<String> action) {
            try (BufferedReader r = openReader(file)) {
                String line;
                while ((line = r.readLine()) != null) action.accept(line);
            } catch (IOException ignored) {}
        }

        /** {@link #forEachLine} para todos os arquivos de {@link #logFilesIn}. */
        public static void forEachLineIn(File dir, String prefix, Consumer<String> action) {
            for (File f : logFilesIn(dir, prefix)) {
                try { forEachLine(f, action); } catch (Exception ignored) {}
            }
        }

        /** Overload sem prefixo. */
        public static void forEachLineIn(File dir, Consumer<String> action) {
            forEachLineIn(dir, null, action);
        }

        /**
         * Adoção do legado: um arquivo que crescia para sempre ({@code claims.log}) é renomeado para
         * {@code claims-<dia da última escrita>.log}, que o archiver compacta normalmente. Chame no
         * enable, ANTES de o writer do plugin abrir o arquivo.
         */
        public static File adoptUndated(File file, ZoneId zone) {
            if (!file.isFile() || file.length() == 0L) return null;
            int dot = file.getName().lastIndexOf('.');
            if (dot <= 0) return null;
            String day = Instant.ofEpochMilli(file.lastModified()).atZone(zone).toLocalDate().format(ISO_DAY);
            File target = new File(file.getParentFile(),
                    file.getName().substring(0, dot) + "-" + day + file.getName().substring(dot));
            if (target.exists()) {
                try (FileOutputStream out = new FileOutputStream(target, true);
                     FileInputStream in  = new FileInputStream(file)) {
                    copy(in, out, null);
                } catch (IOException | InterruptedException e) { return null; }
                return file.delete() ? target : null;
            }
            return file.renameTo(target) ? target : null;
        }

        /** Overload com zona padrão do sistema. */
        public static File adoptUndated(File file) {
            return adoptUndated(file, ZoneId.systemDefault());
        }

        static long copy(InputStream input, OutputStream out, RateLimiter limiter) throws IOException, InterruptedException {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            while (true) {
                int n = input.read(buf);
                if (n < 0) break;
                out.write(buf, 0, n);
                total += n;
                if (limiter != null) limiter.consume(n);
            }
            return total;
        }
    }

    // ─── RateLimiter ─────────────────────────────────────────────────────────────

    /** Limita a vazão de leitura para não disputar disco com o servidor. 0 = sem limite. */
    static final class RateLimiter {
        private final long bytesPerSecond;
        private final Supplier<Boolean> cancelled;
        private long windowStart = System.nanoTime();
        private long windowBytes = 0;

        RateLimiter(long bytesPerSecond, Supplier<Boolean> cancelled) {
            this.bytesPerSecond = bytesPerSecond;
            this.cancelled = cancelled;
        }

        void consume(int n) throws InterruptedException {
            if (cancelled.get()) throw new InterruptedException("parando");
            if (bytesPerSecond <= 0) return;
            windowBytes += n;
            if (windowBytes < bytesPerSecond / 10) return;
            long expectedNanos = windowBytes * 1_000_000_000L / bytesPerSecond;
            long elapsed = System.nanoTime() - windowStart;
            if (expectedNanos > elapsed) Thread.sleep((expectedNanos - elapsed) / 1_000_000L);
            windowStart = System.nanoTime();
            windowBytes = 0;
        }
    }

    // ─── LogArchiver ─────────────────────────────────────────────────────────────

    /**
     * O motor. Uma thread própria (daemon, prioridade mínima): uma passada
     * {@code startupDelayMinutes} depois de ligar, outra todo dia em
     * {@link LogArchiveSettings#dailyAt}, e — enquanto houver atraso — uma nova passada um minuto
     * depois da anterior, cada uma limitada a {@code max-minutes-per-run}.
     *
     * <br>Campo rules    lido a cada passada (a config pode mudar no reload).
     * <br>Campo settings idem.
     */
    public static final class LogArchiver {

        private static final LogArchiveReport EMPTY =
                new LogArchiveReport(0, 0, 0, 0, 0, 0, 0, 0, false);

        private final Supplier<List<LogArchiveRule>> rules;
        private final Supplier<LogArchiveSettings>   settings;
        private final Logger                          logger;
        private final Clock                           clock;
        private final String                          threadName;

        private final AtomicBoolean running  = new AtomicBoolean(false);
        private volatile boolean    stopping = false;
        private ScheduledExecutorService executor;
        private ScheduledFuture<?>       next;

        public LogArchiver(Supplier<List<LogArchiveRule>> rules,
                           Supplier<LogArchiveSettings>   settings,
                           Logger                          logger,
                           Clock                           clock,
                           String                          threadName) {
            this.rules      = rules;
            this.settings   = settings;
            this.logger     = logger;
            this.clock      = clock;
            this.threadName = threadName;
        }

        public LogArchiver(Supplier<List<LogArchiveRule>> rules,
                           Supplier<LogArchiveSettings>   settings,
                           Logger logger) {
            this(rules, settings, logger, Clock.systemDefaultZone(), "log-archiver");
        }

        public synchronized void start() {
            if (executor != null) return;
            stopping = false;
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            schedule(TimeUnit.MINUTES.toMillis(settings.get().startupDelayMinutes), false);
        }

        /** Para no próximo arquivo; o pacote pela metade é desfeito na hora (ou pelo diário). */
        public synchronized void stop() {
            stopping = true;
            if (next != null) next.cancel(false);
            if (executor != null) executor.shutdown();
            try { if (executor != null) executor.awaitTermination(10, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
            executor = null;
        }

        private synchronized void schedule(long delayMillis, boolean daily) {
            ScheduledExecutorService ex = executor;
            if (ex == null) return;
            next = ex.schedule(() -> tick(daily), Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
        }

        private void tick(boolean daily) {
            LogArchiveReport report = null;
            try {
                report = runOnce(daily);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Logs: a passada de compactação falhou", e);
            }
            if (stopping) return;
            if (report != null && report.more) {
                schedule(TimeUnit.MINUTES.toMillis(1), false);
            } else {
                schedule(millisUntilDaily(), true);
            }
        }

        private long millisUntilDaily() {
            ZonedDateTime now = ZonedDateTime.now(clock);
            ZonedDateTime at  = now.with(settings.get().dailyAt);
            if (!at.isAfter(now)) at = at.plusDays(1);
            return java.time.Duration.between(now, at).toMillis();
        }

        /**
         * Uma passada, bloqueante (o comando "compactar agora" pode chamar fora da thread própria).
         * @param rotate separa os arquivos sem data (só faz sentido na passada diária).
         */
        public LogArchiveReport runOnce(boolean rotate) {
            if (!running.compareAndSet(false, true)) return EMPTY.withMore(true);
            try {
                LogArchiveSettings s = settings.get();
                if (!s.enabled) return EMPTY;
                return new Pass(s, rotate).run();
            } finally {
                running.set(false);
            }
        }

        /** Overload com rotate = true. */
        public LogArchiveReport runOnce() { return runOnce(true); }

        // ─── Pass (inner class) ───────────────────────────────────────────────

        private final class Pass {
            private final LogArchiveSettings s;
            private final boolean rotate;
            private final ZoneId    zone;
            private final LocalDate today;
            private final long      startOfToday;
            private final long      quietBefore;
            private final long      deadline;
            private final RateLimiter limiter;

            int compressed = 0; long bytesIn = 0; long bytesOut = 0;
            int deleted = 0;    long deletedBytes = 0;
            int rotated = 0;    int recovered = 0;   int errors = 0;
            boolean more = false;

            Pass(LogArchiveSettings s, boolean rotate) {
                this.s = s;
                this.rotate = rotate;
                zone = clock.getZone();
                today = LocalDate.now(clock);
                startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli();
                quietBefore  = clock.millis() - TimeUnit.MINUTES.toMillis(s.quietMinutes);
                deadline     = System.nanoTime() + TimeUnit.MINUTES.toNanos(s.maxMinutesPerRun);
                limiter      = new RateLimiter((long) s.maxMbPerSecond * 1024 * 1024, () -> stopping);
            }

            private boolean outOfTime() { return stopping || System.nanoTime() > deadline; }

            LogArchiveReport run() {
                List<LogArchiveRule> all = rules.get();

                Map<String, List<LogArchiveRule>> byDir = new LinkedHashMap<>();
                for (LogArchiveRule rule : all) {
                    String key = rule.dir.getAbsolutePath() + "|" + rule.perEntity;
                    byDir.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
                }

                for (Map.Entry<String, List<LogArchiveRule>> entry : byDir.entrySet()) {
                    if (outOfTime()) { more = true; break; }
                    List<LogArchiveRule> group = entry.getValue();
                    File root = group.get(0).dir.getAbsoluteFile();
                    if (!root.isDirectory()) continue;
                    if (group.get(0).perEntity) {
                        File[] entities = root.listFiles(File::isDirectory);
                        if (entities == null) continue;
                        for (File entity : entities) {
                            if (outOfTime()) { more = true; break; }
                            processDir(entity, group);
                            if (anyPrune(group) && isEmpty(entity)) entity.delete();
                        }
                    } else {
                        processDir(root, group);
                    }
                }
                LogArchiveReport r = new LogArchiveReport(compressed, bytesIn, bytesOut,
                        deleted, deletedBytes, rotated, recovered, errors, more);
                if (!r.isIdle()) logger.info(r.describe());
                return r;
            }

            private boolean anyPrune(List<LogArchiveRule> group) {
                for (LogArchiveRule r : group) if (r.pruneEmptyDirs) return true;
                return false;
            }

            private boolean isEmpty(File dir) {
                String[] list = dir.list();
                return list != null && list.length == 0;
            }

            private LogArchiveRule ruleFor(List<LogArchiveRule> group, String prefix) {
                for (LogArchiveRule r : group) if (r.prefix != null && r.prefix.equals(prefix)) return r;
                for (LogArchiveRule r : group) if (r.prefix == null) return r;
                return null;
            }

            private void processDir(File dir, List<LogArchiveRule> group) {
                File[] files = dir.listFiles(File::isFile);
                if (files == null) return;

                for (File j : files) if (j.getName().endsWith(".journal")) recover(j);
                File[] current = dir.listFiles(File::isFile);
                if (current == null) return;

                if (rotate) {
                    for (File f : current) {
                        if (outOfTime()) { more = true; return; }
                        if (LogArchiveFiles.parseDated(f.getName()) != null
                                || !LogArchiveFiles.isLogSuffix(f.getName())) continue;
                        LogArchiveRule rule = findRotateRule(group, f.getName());
                        if (rule != null) rotateUndated(f, rule);
                    }
                }

                Map<File, List<File>> byTarget = new LinkedHashMap<>();
                Map<File, LogArchiveRule> targetRule = new HashMap<>();
                File[] all2 = dir.listFiles(File::isFile);
                if (all2 == null) return;

                for (File f : all2) {
                    LogArchiveFiles.Dated d = LogArchiveFiles.parseDated(f.getName());
                    if (d != null) {
                        LogArchiveRule rule = ruleFor(group, d.prefix);
                        if (rule == null) continue;
                        int retention = s.retentionFor(rule);
                        if (retention > 0 && d.date.isBefore(today.minusDays(retention))) {
                            if (f.lastModified() < quietBefore) deleteForRetention(f);
                            continue;
                        }
                        if (!d.date.isBefore(today) || f.lastModified() >= quietBefore) continue;
                        File target = new File(dir, LogArchiveFiles.bundleName(d, rule.bundle));
                        byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(f);
                        targetRule.put(target, rule);
                        continue;
                    }
                    LogArchiveFiles.Archive a = LogArchiveFiles.parseArchive(f.getName());
                    if (a == null) continue;
                    LogArchiveRule rule = ruleFor(group, a.prefix);
                    if (rule == null) continue;
                    int retention = s.retentionFor(rule);
                    if (retention > 0 && a.lastDay.isBefore(today.minusDays(retention)))
                        deleteForRetention(f);
                }

                for (Map.Entry<File, List<File>> entry : byTarget.entrySet()) {
                    if (outOfTime()) { more = true; return; }
                    List<File> sources = entry.getValue();
                    sources.sort(Comparator
                            .<File, LocalDate>comparing(f -> {
                                LogArchiveFiles.Dated d = LogArchiveFiles.parseDated(f.getName());
                                return d != null ? d.date : LocalDate.MIN;
                            })
                            .thenComparingInt(f -> {
                                LogArchiveFiles.Dated d = LogArchiveFiles.parseDated(f.getName());
                                return d != null ? d.part : 0;
                            }));
                    appendMembers(entry.getKey(), sources);
                }
            }

            private LogArchiveRule findRotateRule(List<LogArchiveRule> group, String fileName) {
                for (LogArchiveRule r : group) {
                    if (!r.rotateUndated) continue;
                    if (r.undatedNames == null || r.undatedNames.contains(fileName)) return r;
                }
                return null;
            }

            private void deleteForRetention(File f) {
                long len = f.length();
                if (f.delete()) { deleted++; deletedBytes += len; } else errors++;
            }

            private void rotateUndated(File f, LogArchiveRule rule) {
                long len = f.length();
                if (len == 0L) return;
                boolean writtenToday = f.lastModified() >= startOfToday;
                if (writtenToday && len < rule.rotateMaxBytes) return;
                int dot = f.getName().lastIndexOf('.');
                if (dot <= 0) return;
                File target = new File(f.getParentFile(),
                        f.getName().substring(0, dot) + "-" + today + f.getName().substring(dot));
                if (target.exists()) return;
                if (f.renameTo(target)) rotated++; else errors++;
            }

            private void appendMembers(File target, List<File> sources) {
                File journal = new File(target.getParentFile(), target.getName() + ".journal");
                long preLength = target.exists() ? target.length() : 0L;
                try {
                    writeJournal(journal, preLength, sources);
                    long[] sizes = new long[sources.size()];
                    try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
                        raf.seek(preLength);
                        FileOutputStream channelOut = new FileOutputStream(raf.getFD());
                        for (int i = 0; i < sources.size(); i++) {
                            if (stopping) throw new InterruptedException("parando");
                            long start = raf.getFilePointer();
                            NonClosingOutputStream member =
                                    new NonClosingOutputStream(new BufferedOutputStream(channelOut, 64 * 1024));
                            try (ZstdBlockOutputStream zst = new ZstdBlockOutputStream(member);
                                 FileInputStream fis = new FileInputStream(sources.get(i))) {
                                sizes[i] = LogArchiveFiles.copy(fis, zst, limiter);
                            }
                            long end = raf.getFilePointer();
                            long read;
                            try (FileInputStream fin = new FileInputStream(target)) {
                                skipFully(fin, start);
                                read = countBytes(new ZstdInputStream(
                                        new BufferedInputStream(new LimitedInputStream(fin, end - start), 64 * 1024)));
                            }
                            if (read != sizes[i]) {
                                throw new IOException("conferência falhou em " + target.getName()
                                        + ": " + sources.get(i).getName() + " tem " + sizes[i] + " bytes, o membro tem " + read);
                            }
                        }
                        raf.getChannel().force(true);
                    }
                    try (FileOutputStream fos = new FileOutputStream(journal, true)) {
                        fos.write("OK\n".getBytes(StandardCharsets.UTF_8));
                        fos.getFD().sync();
                    }
                    for (int i = 0; i < sources.size(); i++) {
                        if (!sources.get(i).delete()) { errors++; continue; }
                        compressed++;
                        bytesIn += sizes[i];
                    }
                    bytesOut += target.length() - preLength;
                    journal.delete();
                } catch (Throwable e) {
                    undo(target, preLength, journal);
                    if (e instanceof InterruptedException) { more = true; return; }
                    errors++;
                    logger.log(Level.WARNING,
                            "Logs: não deu para compactar em " + target.getPath() + "; as origens ficaram intactas", e);
                }
            }

            private void undo(File target, long preLength, File journal) {
                try {
                    if (preLength == 0L) target.delete();
                    else if (target.exists() && target.length() > preLength) {
                        try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
                            raf.setLength(preLength);
                        }
                    }
                } catch (Exception ignored) {}
                journal.delete();
            }

            private void recover(File journal) {
                File target = new File(journal.getParentFile(),
                        journal.getName().substring(0, journal.getName().length() - ".journal".length()));
                List<String> lines;
                try { lines = Files.readAllLines(journal.toPath(), StandardCharsets.UTF_8); }
                catch (Exception e) { journal.delete(); return; }
                if (lines.isEmpty()) { journal.delete(); return; }
                Long preLength;
                try { preLength = Long.parseLong(lines.get(0).trim()); }
                catch (NumberFormatException e) { journal.delete(); return; }
                boolean committed = false;
                for (String l : lines) if (l.trim().equals("OK")) { committed = true; break; }
                List<File> sources = new ArrayList<>();
                for (int i = 1; i < lines.size(); i++) {
                    String l = lines.get(i);
                    if (!l.trim().isEmpty() && !l.trim().equals("OK"))
                        sources.add(new File(journal.getParentFile(), l));
                }
                if (committed) {
                    for (File src : sources) src.delete();
                    journal.delete();
                } else {
                    undo(target, preLength, journal);
                }
                recovered++;
            }

            private void writeJournal(File journal, long preLength, List<File> sources) throws IOException {
                StringBuilder text = new StringBuilder();
                text.append(preLength).append('\n');
                for (File src : sources) text.append(src.getName()).append('\n');
                try (FileOutputStream fos = new FileOutputStream(journal)) {
                    fos.write(text.toString().getBytes(StandardCharsets.UTF_8));
                    fos.getFD().sync();
                }
            }
        }

        // ─── Helpers estáticos do LogArchiver ────────────────────────────────

        private static void skipFully(InputStream input, long n) throws IOException {
            long left = n;
            while (left > 0) {
                long s = input.skip(left);
                if (s <= 0) throw new EOFException("pacote menor que o esperado");
                left -= s;
            }
        }

        private static long countBytes(InputStream input) throws IOException {
            try {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                while (true) {
                    int n = input.read(buf);
                    if (n < 0) return total;
                    total += n;
                }
            } finally {
                input.close();
            }
        }
    }

    // ─── ZstdBlockOutputStream ──────────────────────────────────────────────────

    /**
     * zstd por blocos: junta até blockSize bytes e grava cada bloco como um frame zstd completo.
     * Frames concatenados = .zst válido. Um buffer de entrada e um de saída por membro.
     */
    private static final class ZstdBlockOutputStream extends OutputStream {
        private static final int BLOCK_SIZE = 8 * 1024 * 1024;
        private final OutputStream out;
        private final ZstdCompressor compressor;
        private final byte[] buf;
        private final byte[] frame;
        private int used = 0;

        ZstdBlockOutputStream(OutputStream out) {
            this.out = out;
            this.compressor = new ZstdCompressor();
            this.buf = new byte[BLOCK_SIZE];
            this.frame = new byte[compressor.maxCompressedLength(BLOCK_SIZE)];
        }

        @Override
        public void write(int b) throws IOException {
            if (used == buf.length) flushBlock();
            buf[used++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int o = off;
            int left = len;
            while (left > 0) {
                if (used == buf.length) flushBlock();
                int n = Math.min(left, buf.length - used);
                System.arraycopy(b, o, buf, used, n);
                used += n; o += n; left -= n;
            }
        }

        private void flushBlock() throws IOException {
            if (used == 0) return;
            int n = compressor.compress(buf, 0, used, frame, 0, frame.length);
            out.write(frame, 0, n);
            used = 0;
        }

        @Override public void flush() throws IOException { flushBlock(); out.flush(); }
        @Override public void close() throws IOException { flush(); out.close(); }
    }

    // ─── LimitedInputStream ──────────────────────────────────────────────────────

    /** Lê no máximo {@code limit} bytes (só o membro que acabou de ser gravado). */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream input;
        private long limit;

        LimitedInputStream(InputStream input, long limit) {
            this.input = input;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            if (limit <= 0) return -1;
            int b = input.read();
            if (b >= 0) limit--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (limit <= 0) return -1;
            int n = input.read(buf, off, (int) Math.min(len, limit));
            if (n > 0) limit -= n;
            return n;
        }

        @Override public void close() throws IOException { input.close(); }
    }

    // ─── TolerantInputStream ─────────────────────────────────────────────────────

    /**
     * Descompactador que trata o corte no fim (pacote recebendo um membro neste instante) como
     * fim de arquivo: a leitura para no último membro inteiro.
     */
    private static final class TolerantInputStream extends InputStream {
        private final InputStream input;
        private boolean done = false;

        TolerantInputStream(InputStream input) { this.input = input; }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (done) return -1;
            try {
                return input.read(buf, off, len);
            } catch (IOException e) { done = true; return -1; }
              catch (RuntimeException e) { done = true; return -1; }
        }

        @Override public void close() throws IOException { input.close(); }
    }

    // ─── NonClosingOutputStream ──────────────────────────────────────────────────

    /** Impede que o finish/close de um membro feche o arquivo do pacote. */
    private static final class NonClosingOutputStream extends OutputStream {
        private final OutputStream out;
        NonClosingOutputStream(OutputStream out) { this.out = out; }
        @Override public void write(int b) throws IOException { out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.flush(); }
    }
}
