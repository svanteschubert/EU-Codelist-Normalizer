package org.standict.codelist.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.standict.codelist.compare.CodeListDiff;
import org.standict.codelist.compare.CodeListReader;
import org.standict.codelist.normalize.GenericodeNormalizer;
import org.standict.codelist.statistics.Canonical;

/**
 * What really changed in one code list between two releases, in the terms the Index sheet uses.
 *
 * <p>A changed value is only counted as a change when more than its whitespace differs: the sheets and the Genericode
 * files re-wrap and trim their descriptions from one release to the next, and counting that would bury every real
 * change. The name is told apart from the other columns because the Index reports renamed codes, but rarely a changed
 * remark.
 *
 * <p>Both components are compared by what a column <em>is</em>, not what it is called: a sheet that relabels its name
 * column from {@code "Name"} to {@code "Code name"} renames no code.
 */
public record ActualChanges(Set<String> added, Set<String> removed, Set<String> renamed, Set<String> otherColumns,
        Set<String> whitespaceOnly, Set<String> before, Set<String> after) {

    /** The column the name of a code is kept under, whatever its source calls it. */
    static final String NAME = "(name)";

    public boolean changesCodes() {
        return !added.isEmpty() || !removed.isEmpty() || !renamed.isEmpty();
    }

    public static ActualChanges between(CodeListReader.CodeList before, CodeListReader.CodeList after) {
        var result = new CodeListDiff().compare(before, after);
        var added = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        var removed = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        var renamed = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        var other = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        var whitespace = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        for (CodeListDiff.Entry entry : result.entries()) {
            switch (entry.change()) {
                case ADDED -> added.add(entry.code());
                case REMOVED -> removed.add(entry.code());
                case CHANGED -> {
                    if (collapse(entry.before()).equals(collapse(entry.after()))) {
                        whitespace.add(entry.code());
                    } else if (entry.column().equals(NAME)) {
                        renamed.add(entry.code());
                    } else {
                        other.add(entry.code());
                    }
                }
            }
        }
        other.removeAll(renamed);
        whitespace.removeAll(renamed);
        whitespace.removeAll(other);
        return new ActualChanges(ordered(added), ordered(removed), ordered(renamed), ordered(other),
                ordered(whitespace), ordered(before.rows().keySet()), ordered(after.rows().keySet()));
    }

    /**
     * Reads a normalized Genericode file with its {@code Name} column under {@link #NAME} and without its code column,
     * which is the key.
     */
    public static CodeListReader.CodeList genericode(Path file) throws IOException {
        var read = new CodeListReader().read(file);
        var columns = new ArrayList<String>();
        for (String column : read.columns()) {
            if (!column.equals("Code")) {
                columns.add(column.equals("Name") ? NAME : column);
            }
        }
        var rows = new LinkedHashMap<String, Map<String, String>>();
        read.rows().forEach((code, values) -> {
            var row = new LinkedHashMap<String, String>();
            values.forEach((column, value) -> {
                if (!column.equals("Code")) {
                    row.put(column.equals("Name") ? NAME : column, value);
                }
            });
            rows.put(code, row);
        });
        return new CodeListReader.CodeList(read.shortName(), read.version(), List.copyOf(columns), rows);
    }

    /**
     * Reads a spreadsheet sheet the way the statistics do, keyed by code, with its name column under {@link #NAME}.
     * A code the sheet repeats (the Currency sheet lists a currency once per country) keeps all its values.
     */
    public static CodeListReader.CodeList spreadsheet(Path csv, String codeList) throws IOException {
        var columns = new LinkedHashSet<String>();
        var rows = new LinkedHashMap<String, Map<String, String>>();
        for (Canonical.Row row : Canonical.readSpreadsheet(csv, codeList)) {
            if (row.role() == Canonical.Role.CODE) {
                rows.computeIfAbsent(row.code(), key -> new LinkedHashMap<>());
                continue;
            }
            String column = row.role() == Canonical.Role.NAME ? NAME : row.label();
            columns.add(column);
            rows.computeIfAbsent(row.code(), key -> new LinkedHashMap<>())
                    .merge(column, row.value(), (first, second) -> first.equals(second) ? first : first + " | " + second);
        }
        return new CodeListReader.CodeList(codeList, "", List.copyOf(columns), rows);
    }

    /** An empty list, for a code list that did not exist before. */
    public static CodeListReader.CodeList none(String codeList) {
        return new CodeListReader.CodeList(codeList, "", List.of(), Map.of());
    }

    public static boolean exists(Path file) {
        return file != null && Files.isRegularFile(file);
    }

    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static Set<String> ordered(Set<String> codes) {
        var sorted = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        sorted.addAll(codes);
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
