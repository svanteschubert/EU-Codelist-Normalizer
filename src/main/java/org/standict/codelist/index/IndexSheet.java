package org.standict.codelist.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.standict.codelist.statistics.Canonical;

/**
 * The {@code Index} sheet of an EN16931 code list workbook: the workbook's own account of what it contains and what
 * changed since the previous release.
 *
 * <p>Its table is headed, in row 6 of the workbook,
 * {@code Code lists | Tab name | Version/as published on | Usage | Changes | Remark on updates |
 * EN business terms where the code list is used.} The extracted CSV omits empty rows, so the header is on CSV row 5, and
 * on row 1 in the 2019 workbooks, which state their dates on a separate {@code Main} sheet. Columns are therefore found
 * by their label, never by their position.
 */
public record IndexSheet(String publicationDateText, LocalDate publicationDate, String effectiveDateText,
        LocalDate effectiveDate, List<Entry> entries, List<String> notes) {

    static final String TAB_NAME = "Tab name";
    private static final Pattern BUSINESS_TERM = Pattern.compile("(?i)BT\\s*-?\\s*(\\d+)(?:\\s*-\\s*(\\d+))?");
    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ofPattern("M/d/yy", Locale.ROOT),
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.ROOT), DateTimeFormatter.ISO_LOCAL_DATE);

    /**
     * One row of the table.
     *
     * @param businessTerms normalized to {@code BT-n} or {@code BT-n-m}, distinct, in business-term order
     */
    public record Entry(String codeList, String tab, String version, String usage, String changes, String remark,
            String businessTermsText, List<String> businessTerms) {}

    public static IndexSheet read(Path csv) throws IOException {
        List<List<String>> table = Canonical.parseCsv(Files.readString(csv, StandardCharsets.UTF_8));
        int header = -1;
        for (int row = 0; row < Math.min(10, table.size()); row++) {
            if (table.get(row).stream().map(String::strip).anyMatch(TAB_NAME::equals)) {
                header = row;
                break;
            }
        }
        if (header < 0) {
            throw new IOException("No \"" + TAB_NAME + "\" header in the first 10 rows of " + csv);
        }
        List<String> labels = table.get(header).stream().map(String::strip).toList();
        int tab = labels.indexOf(TAB_NAME);
        String publication = "";
        String effective = "";
        for (List<String> row : table.subList(0, header)) {
            String label = cell(row, 0);
            if (label.equalsIgnoreCase("Publication date")) {
                publication = cell(row, 1);
            } else if (label.equalsIgnoreCase("Effective date")) {
                effective = cell(row, 1);
            }
        }
        var entries = new ArrayList<Entry>();
        var notes = new ArrayList<String>();
        boolean inTable = true;
        for (List<String> row : table.subList(header + 1, table.size())) {
            if (inTable && !cell(row, tab).isEmpty()) {
                String terms = column(row, labels, "EN business terms");
                entries.add(new Entry(column(row, labels, "Code lists"), cell(row, tab),
                        column(row, labels, "Version"), column(row, labels, "Usage"),
                        column(row, labels, "Changes"), column(row, labels, "Remark on updates"), terms,
                        businessTerms(terms)));
            } else {
                inTable = false; // The table ends at its first row without a tab; what follows are notes.
                String note = String.join(" ", row.stream().map(String::strip).filter(cell -> !cell.isEmpty())
                        .toList());
                if (!note.isEmpty()) {
                    notes.add(note);
                }
            }
        }
        return new IndexSheet(publication, date(publication), effective, date(effective), List.copyOf(entries),
                List.copyOf(notes));
    }

    /**
     * Takes the dates the Index does not state from the 2019 workbooks' {@code Main} sheet, which states only a
     * publication date, from which its lists apply.
     */
    public IndexSheet withDatesFrom(Path main) throws IOException {
        if (!publicationDateText.isEmpty() || !Files.isRegularFile(main)) {
            return this;
        }
        for (List<String> row : Canonical.parseCsv(Files.readString(main, StandardCharsets.UTF_8))) {
            if (cell(row, 0).equalsIgnoreCase("Publication date")) {
                return new IndexSheet(cell(row, 1), date(cell(row, 1)), effectiveDateText, effectiveDate, entries,
                        notes);
            }
        }
        return this;
    }

    /** The business terms of a cell such as {@code "BT-29-1, BT-30-1,BT 46-1"}. */
    public static List<String> businessTerms(String cell) {
        var terms = new LinkedHashSet<String>();
        var matcher = BUSINESS_TERM.matcher(cell);
        while (matcher.find()) {
            terms.add("BT-" + Integer.parseInt(matcher.group(1))
                    + (matcher.group(2) == null ? "" : "-" + Integer.parseInt(matcher.group(2))));
        }
        return terms.stream().sorted(BUSINESS_TERM_ORDER).toList();
    }

    /** {@code BT-5} before {@code BT-29-1} before {@code BT-30}: by number, then by sub-number. */
    public static final Comparator<String> BUSINESS_TERM_ORDER = Comparator
            .comparingInt((String term) -> part(term, 1)).thenComparingInt(term -> part(term, 2))
            .thenComparing(Comparator.naturalOrder());

    private static int part(String term, int index) {
        String[] parts = term.split("-");
        try {
            return parts.length > index ? Integer.parseInt(parts[index]) : -1;
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** The cell under the first header label that starts with {@code prefix}, or empty. */
    private static String column(List<String> row, List<String> labels, String prefix) {
        for (int column = 0; column < labels.size(); column++) {
            if (labels.get(column).startsWith(prefix)) {
                return cell(row, column);
            }
        }
        return "";
    }

    private static String cell(List<String> row, int column) {
        return column >= 0 && column < row.size() ? row.get(column).strip() : "";
    }

    private static LocalDate date(String text) {
        for (DateTimeFormatter format : DATES) {
            try {
                return LocalDate.parse(text, format);
            } catch (DateTimeParseException e) {
                // Try the next spelling.
            }
        }
        return null;
    }
}
