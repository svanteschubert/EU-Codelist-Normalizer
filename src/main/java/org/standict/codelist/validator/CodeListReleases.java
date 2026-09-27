package org.standict.codelist.validator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.standict.codelist.compare.CodeListReader;
import org.standict.codelist.normalize.GenericodeNormalizer;
import org.standict.codelist.statistics.Canonical;

/**
 * The published code lists the validator is compared with: the normalized Genericode files and spreadsheet sheets of
 * each release, as the default run writes them to {@code <version>_<effective-date>/rNN/normalized/{gc,xlsx}/}.
 *
 * <p>Where a release was corrected, the highest revision that carries a format is the one in force for that format.
 * A correction that republished only one format does not withdraw the other.
 */
public final class CodeListReleases {
    private static final Pattern RELEASE = Pattern.compile("^[^_]+_(\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern REVISION = Pattern.compile("^r(\\d{2,})$");

    /**
     * One release of the code lists.
     *
     * @param genericode {@code normalized/gc} of the highest revision that has one, or {@code null}
     * @param spreadsheet {@code normalized/xlsx} of the highest revision that has one, or {@code null}
     */
    public record Release(String directory, LocalDate effectiveDate, Path genericode, Path spreadsheet) {}

    /** The codes one component publishes for one code list, and where they were read. */
    public record Published(String source, List<String> codes) {}

    private final Path root;
    private final List<Release> releases;

    private CodeListReleases(Path root, List<Release> releases) {
        this.root = root;
        this.releases = releases;
    }

    /** Reads the release directories below {@code root}, ignoring everything else there. */
    public static CodeListReleases read(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("No code-list releases at " + root);
        }
        var releases = new ArrayList<Release>();
        try (var entries = Files.list(root)) {
            for (Path release : entries.filter(Files::isDirectory).sorted().toList()) {
                var matcher = RELEASE.matcher(release.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                releases.add(new Release(release.getFileName().toString(), LocalDate.parse(matcher.group(1)),
                        highestRevisionWith(release, "gc"), highestRevisionWith(release, "xlsx")));
            }
        }
        if (releases.isEmpty()) {
            throw new IOException("No <version>_<effective-date> release directories in " + root
                    + "; run the normalizer without options first");
        }
        releases.sort(java.util.Comparator.comparing(Release::effectiveDate));
        return new CodeListReleases(root, List.copyOf(releases));
    }

    public List<Release> releases() {
        return releases;
    }

    /** The release in force on {@code date}: the latest one whose effective date is not after it. */
    public Optional<Release> inForce(LocalDate date) {
        Release current = null;
        for (Release release : releases) {
            if (!release.effectiveDate().isAfter(date)) {
                current = release;
            }
        }
        return Optional.ofNullable(current);
    }

    /** The codes of {@code codeList} in the release's Genericode files, if it publishes that list as Genericode. */
    public Optional<Published> genericode(Release release, String codeList) throws IOException {
        if (release.genericode() == null) {
            return Optional.empty();
        }
        Path file = release.genericode().resolve(codeList + ".gc");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        var codes = new CodeListReader().read(file).rows().keySet();
        return Optional.of(new Published(relative(file), sorted(codes)));
    }

    /**
     * The codes of {@code codeList} in the release's spreadsheet sheet of the same name.
     *
     * @param column the exact header label of the code column, or empty to find it as the statistics do
     */
    public Optional<Published> spreadsheet(Release release, String codeList, String column) throws IOException {
        if (release.spreadsheet() == null) {
            return Optional.empty();
        }
        Path file = release.spreadsheet().resolve(codeList + ".csv");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        List<String> codes;
        if (column.isEmpty()) {
            codes = sorted(Canonical.readSpreadsheet(file, codeList).stream()
                    .filter(row -> row.role() == Canonical.Role.CODE).map(Canonical.Row::value).toList());
        } else {
            Optional<List<String>> labelled = labelledColumn(file, column);
            if (labelled.isEmpty()) {
                return Optional.empty(); // This release's sheet does not yet carry that syntax's column.
            }
            codes = labelled.get();
        }
        return Optional.of(new Published(relative(file) + (column.isEmpty() ? "" : " [" + column + "]"), codes));
    }

    /**
     * Reads the column headed {@code label} in one of the first three rows, as the sheets with a header per syntax
     * need: the Time sheet heads its UBL column {@code "2005 Code"} and its CII column {@code "2475 Code"} in its
     * second row, below a row naming the syntaxes.
     */
    private static Optional<List<String>> labelledColumn(Path csv, String label) throws IOException {
        List<List<String>> table = Canonical.parseCsv(Files.readString(csv, StandardCharsets.UTF_8));
        for (int header = 0; header < Math.min(3, table.size()); header++) {
            int column = table.get(header).stream().map(String::strip).toList().indexOf(label);
            if (column < 0) {
                continue;
            }
            var codes = new ArrayList<String>();
            for (List<String> row : table.subList(header + 1, table.size())) {
                if (row.size() > column && !row.get(column).isBlank()) {
                    codes.add(row.get(column).strip());
                }
            }
            return Optional.of(sorted(codes));
        }
        return Optional.empty();
    }

    private static List<String> sorted(java.util.Collection<String> codes) {
        var distinct = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        distinct.addAll(codes);
        return List.copyOf(distinct);
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static Path highestRevisionWith(Path release, String format) throws IOException {
        Path highest = null;
        int number = -1;
        try (var revisions = Files.list(release)) {
            for (Path revision : revisions.filter(Files::isDirectory).toList()) {
                var matcher = REVISION.matcher(revision.getFileName().toString());
                Path candidate = revision.resolve("normalized").resolve(format);
                if (matcher.matches() && Files.isDirectory(candidate) && Integer.parseInt(matcher.group(1)) > number) {
                    number = Integer.parseInt(matcher.group(1));
                    highest = candidate;
                }
            }
        }
        return highest;
    }
}
