package org.standict.codelist.statistics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.standict.codelist.compare.CodeListReader;

/**
 * The one format every component is read into before anything is compared.
 *
 * <p>The Commission publishes the same code lists twice, and labels their columns differently each time: Genericode
 * calls them {@code Code}, {@code Name} and {@code Remark}, while the spreadsheet writes {@code "CODE"},
 * {@code "Code name (english)"}, {@code "EN16931 interpretation"} — and for EAS even {@code "AES"}, a typo for the list
 * it heads. Comparing the two on column labels is therefore impossible; comparing them on what a column *is* works.
 * Each value is recorded with a {@link Role} as well as the label its source used, so a report can compare by role and
 * still show the reader where the value came from.
 *
 * <p>The form is deliberately long rather than wide: one row per value, not per code. A wide table would need a column
 * per source label, which differs per list, per component and per delivery. A long table absorbs a third component —
 * the validator — without changing its shape.
 */
public final class Canonical {
    /** Where a value was published. A delivery states the same code list in several of these. */
    public enum Component { GENERICODE, SPREADSHEET, VALIDATOR }

    /** What a column is, across components that name it differently. */
    public enum Role { CODE, NAME, REMARK, OTHER }

    /**
     * One published value.
     *
     * @param label the column name the source itself used, kept so a report can quote it
     */
    public record Row(Component component, String codeList, String code, Role role, String label, String value) {}

    /** Sheets that are not code lists, and sheet names that differ from the code list they hold. */
    public record Catalog(Set<String> nonCodeListSheets, Map<String, String> sheetAliases) {
        public String codeListOf(String sheet) {
            return sheetAliases.getOrDefault(sheet, sheet);
        }

        public boolean isCodeList(String sheet) {
            return !nonCodeListSheets.contains(sheet);
        }
    }

    private static final String CATALOG = "/statistics/spreadsheet-catalog.csv";

    private Canonical() {
    }

    /** Reads the catalogue of spreadsheet quirks from the resources, so a new sheet is a data change, not a code one. */
    public static Catalog catalog() throws IOException {
        var nonCodeLists = new LinkedHashSet<String>();
        var aliases = new LinkedHashMap<String, String>();
        try (InputStream stream = Canonical.class.getResourceAsStream(CATALOG)) {
            if (stream == null) {
                throw new IOException("Missing resource " + CATALOG);
            }
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String row = line.strip();
                if (row.isEmpty() || row.startsWith("#") || row.startsWith("\"sheet\"")) {
                    continue;
                }
                List<String> cells = parseCsvLine(row);
                if (cells.size() < 2) {
                    throw new IOException("Expected \"sheet\",\"kind\",\"code list\" in " + CATALOG + ": " + line);
                }
                String sheet = cells.get(0);
                switch (cells.get(1)) {
                    case "metadata" -> nonCodeLists.add(sheet);
                    case "code-list" -> {
                        if (cells.size() > 2 && !cells.get(2).isBlank()) {
                            aliases.put(sheet, cells.get(2));
                        }
                    }
                    default -> throw new IOException("Unknown kind " + cells.get(1) + " in " + CATALOG);
                }
            }
        }
        return new Catalog(Set.copyOf(nonCodeLists), Map.copyOf(aliases));
    }

    /** Reads a normalized Genericode file. Its columns already carry the roles this format uses. */
    public static List<Row> readGenericode(Path genericode, String codeList) throws IOException {
        CodeListReader.CodeList read = new CodeListReader().read(genericode);
        var rows = new ArrayList<Row>();
        for (var entry : read.rows().entrySet()) {
            // Iterate the declared columns, not the row map, so the order is the file's regardless of the map type.
            for (String column : read.columns()) {
                String value = entry.getValue().get(column);
                if (value != null) {
                    rows.add(new Row(Component.GENERICODE, codeList, entry.getKey(),
                            roleOfGenericodeColumn(column), column, value));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Reads a sheet extracted as CSV, finding the code and name columns from the header labels.
     *
     * <p>Neither the labels nor the positions can be trusted on their own. The Currency sheet moved its code column:
     * in 2019 it reads {@code "ENTITY","Currency","Alphabetic Code"} with the code third, in 2026
     * {@code "Currency","Alphabetic Code"} with the code second. The EAS sheet, in turn, labels its code column
     * {@code "AES"}, which names no column at all. So the code column is the first whose label mentions a code, and
     * only when none does is the first column assumed — which is right for EAS.
     *
     * <p>The name is the first column whose label mentions a name, else the one labelled after the code list itself
     * (the Currency sheet calls it {@code "Currency"}), else the first column that is not the code.
     */
    public static List<Row> readSpreadsheet(Path csv, String codeList) throws IOException {
        List<List<String>> table = parseCsv(Files.readString(csv, StandardCharsets.UTF_8));
        if (table.isEmpty()) {
            return List.of();
        }
        List<String> header = table.get(0);
        int codeColumn = columnMentioning(header, "code", 0);
        int nameColumn = columnMentioning(header, "name", -1);
        if (nameColumn < 0 || nameColumn == codeColumn) {
            nameColumn = labelled(header, codeList, firstOtherThan(header, codeColumn));
        }
        var rows = new ArrayList<Row>();
        for (List<String> line : table.subList(1, table.size())) {
            if (line.size() <= codeColumn || line.get(codeColumn).isBlank()) {
                continue;
            }
            // A code may legitimately repeat: the Currency sheet is the ISO 4217 table with one row per country, so a
            // shared currency such as ANG appears once for Curacao and once for Sint Maarten. Every row is read and
            // the values are merged per code, rather than treating the second row as corruption.
            String code = line.get(codeColumn).strip();
            for (int column = 0; column < line.size(); column++) {
                String value = line.get(column).strip();
                if (value.isEmpty()) {
                    continue;
                }
                Role role = column == codeColumn ? Role.CODE : column == nameColumn ? Role.NAME : Role.OTHER;
                rows.add(new Row(Component.SPREADSHEET, codeList, code, role, label(header, column), value));
            }
        }
        return List.copyOf(rows);
    }

    /** The first column whose label mentions {@code word}, or {@code fallback} when none does. */
    private static int columnMentioning(List<String> header, String word, int fallback) {
        for (int column = 0; column < header.size(); column++) {
            if (header.get(column).toLowerCase(Locale.ROOT).contains(word)) {
                return column;
            }
        }
        return fallback;
    }

    private static int labelled(List<String> header, String label, int fallback) {
        for (int column = 0; column < header.size(); column++) {
            if (header.get(column).strip().equalsIgnoreCase(label)) {
                return column;
            }
        }
        return fallback;
    }

    private static int firstOtherThan(List<String> header, int excluded) {
        return excluded == 0 && header.size() > 1 ? 1 : 0;
    }

    private static Role roleOfGenericodeColumn(String column) {
        return switch (column.toLowerCase(Locale.ROOT)) {
            case "code" -> Role.CODE;
            case "name" -> Role.NAME;
            case "remark" -> Role.REMARK;
            default -> Role.OTHER;
        };
    }

    private static String label(List<String> header, int column) {
        return column < header.size() ? header.get(column).strip() : "column " + (column + 1);
    }

    /**
     * Reads a whole CSV file as this project writes it, where a quoted cell may span several lines: the EAS and VATEX
     * sheets wrap scheme names and remarks. Splitting the file at every line break first would turn the continuation
     * of such a cell into a row of its own, and its text into a code. Records whose cells are all blank are skipped.
     */
    public static List<List<String>> parseCsv(String text) {
        return parseCsvRecords(text).stream().map(CsvRecord::cells)
                .filter(cells -> cells.stream().anyMatch(value -> !value.isBlank())).toList();
    }

    /**
     * One record of a CSV file and the lines it is written on.
     *
     * @param line 1-based; a record whose quoted cell spans lines starts the next one further down
     * @param cellLines the line each cell starts on, which differs from {@code line} after a cell spanning lines
     */
    public record CsvRecord(int line, List<String> cells, List<Integer> cellLines) {
        /** The line {@code column} starts on, or the record's first line when it has no such cell. */
        public int lineOf(int column) {
            return column >= 0 && column < cellLines.size() ? cellLines.get(column) : line;
        }
    }

    /** Every record of a CSV file, blank ones included, each with the line it starts on. */
    public static List<CsvRecord> parseCsvRecords(String text) {
        var records = new ArrayList<CsvRecord>();
        var record = new ArrayList<String>();
        var cell = new StringBuilder();
        var cellLines = new ArrayList<Integer>();
        boolean quoted = false;
        int line = 1;
        int start = 1;
        int cellStart = 1;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (quoted) {
                if (character == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(character);
                    if (character == '\n') {
                        line++;
                    }
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                record.add(cell.toString());
                cellLines.add(cellStart);
                cell.setLength(0);
                cellStart = line;
            } else if (character == '\n') {
                record.add(cell.toString());
                cellLines.add(cellStart);
                cell.setLength(0);
                records.add(new CsvRecord(start, List.copyOf(record), List.copyOf(cellLines)));
                record = new ArrayList<>();
                cellLines.clear();
                start = ++line;
                cellStart = line;
            } else if (character != '\r') {
                cell.append(character);
            }
        }
        if (!record.isEmpty() || cell.length() > 0) {
            record.add(cell.toString());
            cellLines.add(cellStart);
            records.add(new CsvRecord(start, List.copyOf(record), List.copyOf(cellLines)));
        }
        return List.copyOf(records);
    }

    /** Minimal RFC 4180 reader for one line of the catalogue resources, whose cells never span lines. */
    public static List<String> parseCsvLine(String line) {
        var cells = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (quoted) {
                if (character == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(character);
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(character);
            }
        }
        cells.add(cell.toString());
        return cells;
    }
}
