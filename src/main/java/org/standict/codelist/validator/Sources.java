package org.standict.codelist.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.standict.codelist.normalize.SpreadsheetExtractor;
import org.standict.codelist.statistics.Canonical;

/**
 * Where something the report states is written, as a link a reader can follow.
 *
 * <p>The originals are XLSX workbooks and Genericode ZIP archives, which a browser cannot show by line. Their extracted
 * text, the sheets as CSV and the Genericode files byte for byte, is versioned with the release tree, in
 * {@code <release>/rNN/extracted/}, where GitHub shows every file with line numbers. A code links to the line that
 * lists it; a code a file lacks links to that file. Every link names the original it came from, and for a sheet the
 * workbook cell, counted the way the workbook counts it: the extraction drops blank rows and records which, in
 * {@code source.json}, so the cell is exact. The originals themselves are linked where the downloader keeps them.
 *
 * <p>A change from one release to the next links to the commit of the {@code code-history} branch that made it, at the
 * line of the code: that commit is dated by the effective date, and its diff shows the line before and after.
 *
 * <p>A release tree outside a checkout on GitHub is not linked, rather than linked wrongly; nor are the originals when
 * the downloader is not on GitHub.
 */
public final class Sources {
    /** A link to where something is written, with a title naming the original file and the cell or line. */
    public record Spot(String url, String title) {}

    /** A component as the release tree names it: {@code 17_2026-05-15/r02/normalized/xlsx/Time.csv [2475 Code]}. */
    private static final Pattern SOURCE =
            Pattern.compile("^([^/]+/[^/]+)/normalized/(gc|xlsx)/(.+)\\.(?:gc|csv)(?: \\[(.+)])?$");
    private static final Pattern KEY = Pattern.compile("<Key\\b[^>]*>.*?<ColumnRef\\s+Ref=\"([^\"]+)\"", Pattern.DOTALL);

    private final Path releases;
    /** {@code https://github.com/<owner>/<repo>/blob/<branch>/} of the repository holding the copies, or {@code null}. */
    private final String blob;
    /** The release tree's path in that repository, such as {@code src/test/resources}. */
    private final String copies;
    /** The same address of the downloader, which keeps the originals, or {@code null}. */
    private final String originals;
    /** The branch with one commit per effective date, built by {@code build-history-branch.sh}. */
    static final String HISTORY = "code-history";

    /** The checkout holding the release tree and the history branch, or {@code null}. */
    private final GitCheckout checkout;
    /** {@code https://github.com/<owner>/<repo>} of that checkout, or {@code null}. */
    private final String web;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Path, Csv> csvs = new HashMap<>();
    private final Map<Path, Map<String, Integer>> genericodes = new HashMap<>();
    private final Map<Path, JsonNode> manifests = new HashMap<>();
    private final Map<String, String> committed = new HashMap<>();
    /** The history commit of each release, such as {@code 17_2026-05-15}, read when first needed. */
    private Map<String, String> history;

    Sources(Path releases, String blob, String copies, String originals) {
        this(releases, blob, copies, originals, null, null);
    }

    Sources(Path releases, String blob, String copies, String originals, GitCheckout checkout, String web) {
        this.releases = releases;
        this.blob = blob;
        this.copies = copies;
        this.originals = originals;
        this.checkout = checkout;
        this.web = web;
    }

    /** A report without links. */
    public static Sources none() {
        return new Sources(null, null, null, null);
    }

    /**
     * @param releases the normalizer's release tree, linked in the GitHub repository whose checkout holds it
     * @param downloader the downloader checkout, whose GitHub origin serves the original files, or {@code null}
     */
    public static Sources of(Path releases, Path downloader) {
        if (releases == null) {
            return none();
        }
        try {
            Path folder = real(releases);
            var checkout = checkoutHolding(folder);
            String blob = checkout == null ? null : blob(checkout);
            if (blob == null) {
                return none();
            }
            String originals = null;
            if (downloader != null) {
                try {
                    originals = blob(new GitCheckout(downloader));
                } catch (IOException e) {
                    // Not a checkout: the originals are named, not linked.
                }
            }
            return new Sources(releases, blob, checkout.root().relativize(folder).toString().replace('\\', '/'),
                    originals, checkout, checkout.webUrl().orElse(null));
        } catch (IOException e) {
            return none();
        }
    }

    /** {@code https://github.com/<owner>/<repo>/blob/<branch>/}, or {@code null} for a checkout not on GitHub. */
    private static String blob(GitCheckout checkout) {
        var url = checkout.webUrl();
        var branch = checkout.defaultBranch();
        return url.isEmpty() || branch.isEmpty() ? null : url.get() + "/blob/" + branch.get() + "/";
    }

    /** The checkout {@code folder} lies in, or {@code null}. */
    private static GitCheckout checkoutHolding(Path folder) throws IOException {
        for (Path directory = folder; directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve(".git"))) {
                return new GitCheckout(directory);
            }
        }
        return null;
    }

    public boolean linked() {
        return blob != null;
    }

    /** The line listing {@code code} in a revision's sheet or Genericode file, or the file when it lacks the code. */
    public Spot code(String revision, String format, String tab, String code) {
        return code(revision, format, tab, "", code);
    }

    /**
     * @param column the label of the code column, such as the Time sheet's {@code 2005 Code}, or empty to find it as
     *     the comparison does
     */
    public Spot code(String revision, String format, String tab, String column, String code) {
        if (!linked()) {
            return null;
        }
        Path file = copy(revision, format, tab);
        try {
            if (format.equals("gc")) {
                Integer line = genericode(file).get(code);
                return line == null ? missing(revision, format, tab, code)
                        : new Spot(url(revision, format, file, line), tab + ".gc, line " + line + " — "
                                + original(revision, format));
            }
            Csv csv = csv(file, column);
            Integer record = csv.codes().get(code);
            return record == null ? missing(revision, format, tab, code)
                    : new Spot(url(revision, format, file, csv.records().get(record).lineOf(csv.codeColumn())),
                            tab + " sheet, cell "
                            + cell(csv.codeColumn(), workbookRow(revision, tab, record)) + " — "
                            + original(revision, format));
        } catch (IOException e) {
            return file(revision, format, tab);
        }
    }

    /**
     * The line of {@code code} in the {@code code-history} commit of the revision's release, whose diff shows the
     * change: the line after it for a code that came or changed, the line before it for one that went. {@code null}
     * when there is no such commit, so that the caller links the release tree instead.
     *
     * @param removed whether the code went, so that the line is that of the file before the commit
     */
    public Spot change(String revision, String format, String tab, String code, boolean removed) {
        String release = revision.replaceFirst("/.*", "");
        String commit = web == null ? null : history().get(release);
        if (commit == null) {
            return null;
        }
        String path = format + "/" + tab + (format.equals("gc") ? ".gc" : ".csv");
        String text = committed(commit + (removed ? "^" : ""), path);
        int line = 0;
        if (text != null) {
            if (format.equals("gc")) {
                line = genericodeLines(text).getOrDefault(code, 0);
            } else {
                Csv csv = csv(text, "");
                Integer record = csv.codes().get(code);
                line = record == null ? 0 : csv.records().get(record).lineOf(csv.codeColumn());
            }
        }
        String date = release.replaceFirst("^[^_]*_", "");
        return new Spot(web + "/commit/" + commit + "#diff-" + sha256(path) + (line > 0 ? (removed ? "L" : "R") + line
                : ""), (format.equals("gc") ? tab + ".gc" : tab + " sheet") + (line > 0 ? ", line " + line : "")
                + (removed ? " before" : "") + " — the change in force from " + date + ", as the code-history commit of "
                + release + " shows it");
    }

    /** The history commit of each release, from the {@code Code-List-Release} trailers of the history branch. */
    private Map<String, String> history() {
        if (history == null) {
            history = new HashMap<>();
            try {
                for (String line : checkout.text("log", "--format=%H|%(trailers:key=Code-List-Release,valueonly,"
                        + "separator=)", HISTORY).split("\n")) {
                    int bar = line.indexOf('|');
                    if (bar > 0 && bar < line.length() - 1) {
                        history.putIfAbsent(line.substring(bar + 1), line.substring(0, bar));
                    }
                }
            } catch (IOException e) {
                // No history branch: changes link to the release tree.
            }
        }
        return history;
    }

    /** A file as a commit holds it, or {@code null} when it does not. */
    private String committed(String commit, String path) {
        return committed.computeIfAbsent(commit + ":" + path, key -> {
            try {
                return new String(checkout.git("show", key), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        });
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether the revision's copy lists {@code code}. */
    public boolean lists(String revision, String format, String tab, String code) {
        if (!linked()) {
            return false;
        }
        Path file = copy(revision, format, tab);
        try {
            return format.equals("gc") ? genericode(file).containsKey(code) : csv(file, "").codes().containsKey(code);
        } catch (IOException e) {
            return false;
        }
    }

    /** A revision's sheet or Genericode file as a whole, or {@code null} when it has none. */
    public Spot file(String revision, String format, String tab) {
        Path file = linked() ? copy(revision, format, tab) : null;
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        return new Spot(url(revision, format, file, 0), (format.equals("gc") ? tab + ".gc" : tab + " sheet") + " — "
                + original(revision, format));
    }

    /**
     * The cell of a tab's row on the Index sheet under the column whose label starts with {@code label}, such as
     * {@code "Remark on updates"}, or {@code null} when the Index has no such row.
     */
    public Spot indexCell(String revision, String tab, String label) {
        if (!linked()) {
            return null;
        }
        Path file = copy(revision, "xlsx", "Index");
        try {
            var records = csv(file, "").records();
            for (int header = 0; header < Math.min(10, records.size()); header++) {
                List<String> labels = records.get(header).cells().stream().map(String::strip).toList();
                int tabColumn = labels.indexOf("Tab name");
                if (tabColumn < 0) {
                    continue;
                }
                int column = 0;
                while (column < labels.size() && !labels.get(column).startsWith(label)) {
                    column++;
                }
                for (int record = header + 1; record < records.size(); record++) {
                    List<String> cells = records.get(record).cells();
                    if (cells.size() > tabColumn && cells.get(tabColumn).strip().equals(tab)) {
                        String name = column < labels.size() ? " (" + labels.get(column) + ")" : "";
                        int cellColumn = column < labels.size() ? column : tabColumn;
                        return new Spot(url(revision, "xlsx", file, records.get(record).lineOf(cellColumn)),
                                "Index sheet, cell "
                                + cell(cellColumn, workbookRow(revision, "Index", record)) + name + " — "
                                + original(revision, "xlsx"));
                    }
                }
            }
        } catch (IOException e) {
            // No Index: nothing to link.
        }
        return null;
    }

    /** The effective date the Index states, or the publication date the 2019 workbooks state on their Main sheet. */
    public Spot indexDate(String revision) {
        if (!linked()) {
            return null;
        }
        for (String[] sheet : new String[][] {{"Index", "Effective date"}, {"Main", "Publication date"}}) {
            Path file = copy(revision, "xlsx", sheet[0]);
            try {
                var records = csv(file, "").records();
                for (int record = 0; record < records.size(); record++) {
                    List<String> cells = records.get(record).cells();
                    if (!cells.isEmpty() && cells.get(0).strip().equalsIgnoreCase(sheet[1])) {
                        return new Spot(url(revision, "xlsx", file, records.get(record).lineOf(1)), sheet[0]
                                + " sheet, cell " + cell(1, workbookRow(revision, sheet[0], record)) + " (" + sheet[1]
                                + ") — " + original(revision, "xlsx"));
                    }
                }
            } catch (IOException e) {
                // Try the next sheet.
            }
        }
        return null;
    }

    /** The original workbook and archive a revision was extracted from, in the downloader. */
    public List<Spot> originals(String revision) {
        var spots = new ArrayList<Spot>();
        if (!linked()) {
            return spots;
        }
        for (String format : List.of("xlsx", "gc")) {
            JsonNode manifest = manifest(revision, format);
            if (originals != null && manifest != null && !manifest.path("source_path").asText().isEmpty()) {
                spots.add(new Spot(originals + encode(manifest.path("source_path").asText()),
                        manifest.path("source_filename").asText() + " — the original, as the European Commission "
                                + "published it"));
            }
        }
        return spots;
    }

    /**
     * The line listing {@code code} in the component a comparison read, given as the release tree names it:
     * {@code 17_2026-05-15/r02/normalized/gc/Currency.gc}, or {@code …/normalized/xlsx/Time.csv [2005 Code]} for a
     * labelled column. The normalized file was compared; the link goes to its extracted original.
     */
    public Spot published(String source, String code) {
        Matcher matcher = SOURCE.matcher(source);
        if (!linked() || !matcher.matches()) {
            return null;
        }
        return code(matcher.group(1), matcher.group(2), matcher.group(3),
                matcher.group(4) == null ? "" : matcher.group(4), code);
    }

    /** The file a comparison read, given as {@link #published} takes it, as its extracted copy. */
    public Spot declared(String source) {
        Matcher matcher = SOURCE.matcher(source);
        return linked() && matcher.matches() ? file(matcher.group(1), matcher.group(2), matcher.group(3)) : null;
    }

    private Spot missing(String revision, String format, String tab, String code) {
        Spot file = file(revision, format, tab);
        return file == null ? null : new Spot(file.url(), code + " is not in the " + file.title());
    }

    private Path copy(String revision, String format, String tab) {
        return releases.resolve(revision).resolve("extracted").resolve(format)
                .resolve(tab + (format.equals("gc") ? ".gc" : ".csv"));
    }

    /** GitHub shows a CSV file as a table; {@code ?plain=1} shows its lines, so that a line can be pointed at. */
    private String url(String revision, String format, Path file, int line) {
        String name = file.getFileName().toString();
        return blob + encode((copies.isEmpty() ? "" : copies + "/") + revision + "/extracted/" + format + "/" + name)
                + (name.endsWith(".csv") ? "?plain=1" : "")
                + (line > 0 ? "#L" + line : "");
    }

    private String original(String revision, String format) {
        JsonNode manifest = manifest(revision, format);
        return manifest == null ? revision : manifest.path("source_filename").asText(revision);
    }

    /** The workbook row of a sheet's CSV record, from the blank rows the extraction recorded as omitted. */
    private int workbookRow(String revision, String sheet, int record) {
        JsonNode manifest = manifest(revision, "xlsx");
        var omitted = new ArrayList<Integer>();
        if (manifest != null) {
            for (JsonNode file : manifest.path("files")) {
                if (file.path("filename").asText().equals(sheet + ".csv")) {
                    file.path("omitted_rows").forEach(row -> omitted.add(row.asInt()));
                }
            }
        }
        return SpreadsheetExtractor.SheetResult.workbookRow(omitted, record);
    }

    private JsonNode manifest(String revision, String format) {
        Path file = releases.resolve(revision).resolve("extracted").resolve(format).resolve("source.json");
        return manifests.computeIfAbsent(file, path -> {
            try {
                return Files.isRegularFile(path) ? json.readTree(path.toFile()) : null;
            } catch (IOException e) {
                return null;
            }
        });
    }

    /**
     * A CSV copy: its records with their lines, and the record listing each code.
     *
     * @param codeColumn the column the codes were read from
     */
    private record Csv(List<Canonical.CsvRecord> records, int codeColumn, Map<String, Integer> codes) {}

    /** Reads the code column as the comparison does: headed {@code column} in the first three rows, else by "code". */
    private Csv csv(Path file, String column) throws IOException {
        Path key = column.isEmpty() ? file : file.resolveSibling(file.getFileName() + " [" + column + "]");
        Csv cached = csvs.get(key);
        if (cached == null) {
            cached = csv(Files.readString(file, StandardCharsets.UTF_8), column);
            csvs.put(key, cached);
        }
        return cached;
    }

    private static Csv csv(String text, String column) {
        var records = Canonical.parseCsvRecords(text);
        int header = 0;
        int codeColumn = 0;
        if (!column.isEmpty()) {
            header = -1;
            for (int row = 0; row < Math.min(3, records.size()) && header < 0; row++) {
                int index = records.get(row).cells().stream().map(String::strip).toList().indexOf(column);
                if (index >= 0) {
                    header = row;
                    codeColumn = index;
                }
            }
        } else if (!records.isEmpty()) {
            List<String> labels = records.get(0).cells();
            for (int index = 0; index < labels.size(); index++) {
                if (labels.get(index).toLowerCase(Locale.ROOT).contains("code")) {
                    codeColumn = index;
                    break;
                }
            }
        }
        var codes = new HashMap<String, Integer>();
        for (int record = header + 1; header >= 0 && record < records.size(); record++) {
            List<String> cells = records.get(record).cells();
            if (cells.size() > codeColumn && !cells.get(codeColumn).isBlank()) {
                codes.putIfAbsent(cells.get(codeColumn).strip(), record);
            }
        }
        return new Csv(records, codeColumn, Map.copyOf(codes));
    }

    /** The line of each code in a Genericode file: where its key column's value is written. */
    private Map<String, Integer> genericode(Path file) throws IOException {
        Map<String, Integer> cached = genericodes.get(file);
        if (cached == null) {
            cached = genericodeLines(Files.readString(file, StandardCharsets.UTF_8));
            genericodes.put(file, cached);
        }
        return cached;
    }

    private static Map<String, Integer> genericodeLines(String text) {
        Matcher key = KEY.matcher(text);
        String column = key.find() ? key.group(1) : "Code";
        Matcher values = Pattern.compile("<Value\\s+ColumnRef=\"" + Pattern.quote(column)
                + "\"\\s*>\\s*<SimpleValue>([^<]*)</SimpleValue>").matcher(text);
        int[] lineStarts = lineStarts(text);
        var lines = new HashMap<String, Integer>();
        while (values.find()) {
            String code = unescape(values.group(1)).strip();
            int line = Arrays.binarySearch(lineStarts, values.start(1));
            lines.putIfAbsent(code, line >= 0 ? line + 1 : -line - 1);
        }
        return Map.copyOf(lines);
    }

    private static int[] lineStarts(String text) {
        var starts = new ArrayList<Integer>(List.of(0));
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /** {@code F12}: a 0-based column and a 1-based row as the workbook names the cell. */
    static String cell(int column, int row) {
        var letters = new StringBuilder();
        for (int index = column; index >= 0; index = index / 26 - 1) {
            letters.insert(0, (char) ('A' + index % 26));
        }
        return letters + String.valueOf(row);
    }

    /** A path for a URL: every segment percent-encoded, spaces as {@code %20}, the slashes kept. */
    static String encode(String path) {
        try {
            return new URI(null, null, path, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(path, e);
        }
    }

    /** The real path of {@code path}, which need not exist yet: its deepest existing ancestor, resolved. */
    private static Path real(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing == null ? absolute : existing.toRealPath().resolve(existing.relativize(absolute));
    }
}
