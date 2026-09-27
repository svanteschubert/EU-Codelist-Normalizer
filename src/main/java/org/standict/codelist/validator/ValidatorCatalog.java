package org.standict.codelist.validator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.standict.codelist.statistics.Canonical;

/**
 * The two facts about the validation artefacts that cannot be read from the artefacts themselves: from which date each
 * release applies, and which published code list each {@code BR-CL} rule enforces.
 *
 * <p>Both live in resources next to the spreadsheet catalogue, so a new release or a new rule is a data change, not a
 * code one.
 */
public final class ValidatorCatalog {
    private static final String RELEASES = "/validator/validator-releases.csv";
    private static final String RULES = "/validator/rule-catalog.csv";

    /** One tagged release of the validator and the date it applies from. */
    public record Release(String tag, LocalDate effectiveDate, String source) {
        /** {@code 2026-05-15_validation-1.3.16}: sorts by date, and names the release it holds. */
        public String directory() {
            return effectiveDate + "_" + tag;
        }
    }

    /** The code list one rule of one syntax enforces, and where the spreadsheet keeps that syntax's codes. */
    public record Mapping(String codeList, String spreadsheetColumn) {}

    private final List<Release> releases;
    private final Map<String, Mapping> rules;

    private ValidatorCatalog(List<Release> releases, Map<String, Mapping> rules) {
        this.releases = releases;
        this.rules = rules;
    }

    /** A catalogue of the given releases and rules, keyed {@code "UBL BR-CL-01"}; for tests. */
    static ValidatorCatalog of(List<Release> releases, Map<String, Mapping> rules) {
        return new ValidatorCatalog(List.copyOf(releases), Map.copyOf(rules));
    }

    public static ValidatorCatalog load() throws IOException {
        var releases = new ArrayList<Release>();
        for (List<String> row : rows(RELEASES, 3)) {
            LocalDate date;
            try {
                date = LocalDate.parse(row.get(1));
            } catch (DateTimeParseException e) {
                throw new IOException("Invalid effective date in " + RELEASES + ": " + row, e);
            }
            if (!releases.isEmpty() && date.isBefore(releases.get(releases.size() - 1).effectiveDate())) {
                throw new IOException("Releases in " + RELEASES + " must have non-decreasing dates: " + row.get(0));
            }
            if (releases.stream().anyMatch(release -> release.tag().equals(row.get(0)))) {
                throw new IOException("Duplicate tag in " + RELEASES + ": " + row.get(0));
            }
            releases.add(new Release(row.get(0), date, row.get(2)));
        }
        var rules = new LinkedHashMap<String, Mapping>();
        for (List<String> row : rows(RULES, 4)) {
            var mapping = new Mapping(row.get(2), row.get(3));
            List<String> syntaxes = row.get(0).isEmpty() ? List.of("UBL", "CII") : List.of(row.get(0));
            for (String syntax : syntaxes) {
                if (rules.put(syntax + " " + row.get(1), mapping) != null) {
                    throw new IOException("Rule mapped twice in " + RULES + ": " + syntax + " " + row.get(1));
                }
            }
        }
        return new ValidatorCatalog(List.copyOf(releases), Map.copyOf(rules));
    }

    /** Every release, in release order. */
    public List<Release> releases() {
        return releases;
    }

    /** The release in force on {@code date}: the last one whose effective date is not after it. */
    public Optional<Release> inForce(LocalDate date) {
        Release current = null;
        for (Release release : releases) {
            if (!release.effectiveDate().isAfter(date)) {
                current = release;
            }
        }
        return Optional.ofNullable(current);
    }

    public Optional<Mapping> mapping(Syntax syntax, String rule) {
        return Optional.ofNullable(rules.get(syntax.name() + " " + rule));
    }

    private static List<List<String>> rows(String resource, int columns) throws IOException {
        String text;
        try (InputStream stream = ValidatorCatalog.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing resource " + resource);
            }
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var rows = new ArrayList<List<String>>();
        boolean header = true;
        for (String line : text.split("\n")) {
            String row = line.strip();
            if (row.isEmpty() || row.startsWith("#")) {
                continue;
            }
            if (header) {
                header = false; // The column labels, documented in the file itself.
                continue;
            }
            List<String> cells = Canonical.parseCsvLine(row);
            if (cells.size() != columns) {
                throw new IOException("Expected " + columns + " columns in " + resource + ": " + line);
            }
            rows.add(cells);
        }
        return rows;
    }
}
